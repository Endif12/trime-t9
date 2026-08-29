package com.osfans.trime.ime.t9

import android.view.KeyEvent
import com.osfans.trime.core.RimeMessage
import com.osfans.trime.daemon.RimeSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber

class T9InputController(
    private val rime: RimeSession,
) {
    data class PinYinToken(
        val pos: Int,
        val raw: String,
        val pinYin: String,
        val display: String = pinYin,
    )

    enum class Behavior {
        NONE,
        NORMAL,
        SEGMENT,
        SELECT_PINYIN,
        SELECT_CANDIDATE,
    }

    private val inputQueue = ArrayDeque<String>()
    private val selectedQueue = ArrayDeque<PinYinToken>()
    private val behaviorQueue = ArrayDeque<Behavior>()

    var onCandidatesChanged: ((List<PinYinToken>) -> Unit)? = null

    private var cachedInputString = ""
    private var committedPrefix = ""
    private var committedPrefixDigits = ""
    private var pendingCandidateCommit: String? = null
    private var lastRimeInput = ""
    private var t9CursorPos: Int = 0
    private var messageJob: Job? = null

    companion object {
        const val SEGMENT_KEY_CHAR = '\''
        const val SEGMENT_KEY_CHAR_ALIAS = '1'
    }

    init {
        messageJob = rime.lifecycleScope.launch {
            rime.run { messageFlow }.collect { message ->
                // CommitTextMessage 不能在这里直接 clear T9 状态。
                //
                // 候选词选择时，Rime 可能先发 commit，
                // 随后才发 composition。
                //
                // 此时我们还需要保留：
                //   committedPrefix
                //   cachedInputString
                //
                // 真正的状态同步由 onCompositionUpdated() 完成。
            }
        }
    }

    fun destroy() {
        messageJob?.cancel()
        messageJob = null
    }

    fun onDigitKey(digit: String) {
        // 严格按光标分隔：插入到 t9CursorPos 而非总是末尾
        val insertPos = t9CursorPos.coerceIn(0, cachedInputString.length)
        cachedInputString = buildString {
            append(cachedInputString.substring(0, insertPos))
            append(digit)
            append(cachedInputString.substring(insertPos))
        }
        inputQueue.clear()
        inputQueue.addAll(cachedInputString.map { it.toString() })
        if (selectedQueue.isNotEmpty()) {
            val newSelected = ArrayDeque<PinYinToken>()
            for (tok in selectedQueue) {
                if (tok.pos >= insertPos) {
                    newSelected.add(tok.copy(pos = tok.pos + 1))
                } else {
                    newSelected.add(tok)
                }
            }
            selectedQueue.clear()
            selectedQueue.addAll(newSelected)
        }
        behaviorQueue.add(Behavior.NORMAL)
        t9CursorPos = insertPos + 1
        val displayCursor = committedPrefix.length + t9CursorPos + countApostrophesBefore(t9CursorPos)
        updateRimeInputWithCursor(displayCursor)
        fireCandidatesChanged()
    }

    private fun countApostrophesBefore(digitPos: Int): Int {
        var c = 0
        for (tok in selectedQueue) {
            if (tok.pos + tok.raw.length <= digitPos) {
                c += 1
            } else if (tok.pos < digitPos) {
                c += 1 // 光标在拼音内部也算已插入的 '
            }
            // 已插入 token 的 ' 紧跟拼音后，若光标在拼音内也会在 Rime 侧处于拼音内，但为简化按已过 token 计
        }
        return c
    }

    private fun getRimeCursorForDigitPos(digitPos: Int): Int {
        var extra = 0
        for (tok in selectedQueue) {
            if (tok.pos + tok.raw.length <= digitPos) {
                extra += 1 // 每个已选拼音后的 '
            } else if (tok.pos < digitPos) {
                // 光标在拼音 token 内部
                // pinYin.length == raw.length，故光标在拼音内的偏移等价于 digit 偏移
                extra += 0
                break
            }
        }
        return digitPos + extra
    }

    private fun getDisplayTextAndCursor(): Pair<String, Int> {
        val rimePreedit = lastRimeInput
        val displayText = getDisplayText(rimePreedit)
        // displayCursor = prefixLen + rimeCursor (adjusted for digitPart)
        val prefixLen = committedPrefix.length
        val rimeCursor = lastRimeCursorPos
        // 估算 displayCursor：若 rimePreedit 含汉字前缀则需映射
        val displayCursor = if (rimePreedit.isEmpty()) {
            prefixLen
        } else {
            val firstNonHan = rimePreedit.indexOfFirst {
                Character.UnicodeScript.of(it.code) != Character.UnicodeScript.HAN
            }
            if (firstNonHan >= 0 && rimePreedit.startsWith(committedPrefix) && committedPrefix.isNotEmpty()) {
                // 已在合并分支中高亮全选，display 即 rimePreedit 本身
                rimeCursor
            } else if (firstNonHan >= 0) {
                prefixLen + (rimeCursor - firstNonHan).coerceAtLeast(0)
            } else {
                prefixLen + rimeCursor
            }
        }.coerceIn(0, displayText.length)
        return displayText to displayCursor
    }

    fun onBackspace(): Boolean {
        // 光标感知：退格只删除光标左侧的内容
        val (displayText, displayCursor) = getDisplayTextAndCursor()
        if (displayCursor <= 0 || displayText.isEmpty()) return false

        val prefixLen = committedPrefix.length

        // 光标位于汉字前缀区：左侧是汉字
        // 汉字 -> 拼音 -> 数字，再按数字逻辑删除一位
        if (displayCursor <= prefixLen) {
            return deleteHanBefore(displayCursor - 1)
        }

        // 光标位于拼音/数字区：先定位光标左侧的内容
        var displayStart = prefixLen
        var digitPos = 0

        for (tok in selectedQueue) {
            val lettersStart = displayStart
            val lettersEnd = lettersStart + tok.raw.length

            when {
                // 光标左侧是 token 后的分隔符 '：拼音 -> 数字，删除该段最后一位
                displayCursor == lettersEnd + 1 ->
                    return deleteTokenDigits(tok, tok.raw.length - 1)
                // 光标在 token 整体（含分隔符）之后，继续向右定位
                displayCursor > lettersEnd -> {
                    displayStart = lettersEnd + 1
                    digitPos = tok.pos + tok.raw.length
                }
                // 光标左侧是 token 拼音中的字母：拼音 -> 数字，删除对应的一位
                displayCursor > lettersStart ->
                    return deleteTokenDigits(tok, displayCursor - lettersStart - 1)
                // 光标在 token 起始处，左侧内容属于 token 之前的数字
                else -> break
            }
        }

        // 光标左侧是数字：按数字逐个删除
        if (cachedInputString.isEmpty()) return false
        var deleteIndex = digitPos + (displayCursor - displayStart) - 1
        if (deleteIndex < 0) return false
        if (deleteIndex >= cachedInputString.length) {
            deleteIndex = cachedInputString.length - 1
        }

        // 防御：若该位数字被某个选中拼音覆盖，按拼音 -> 数字处理
        for (tok in selectedQueue) {
            if (deleteIndex >= tok.pos && deleteIndex < tok.pos + tok.raw.length) {
                return deleteTokenDigits(tok, deleteIndex - tok.pos)
            }
        }

        return deleteDigitAt(deleteIndex)
    }

    /** 删除数字流中指定位置的一位数字，光标停在被删位置 */
    private fun deleteDigitAt(deleteIndex: Int): Boolean {
        cachedInputString = cachedInputString.removeRange(deleteIndex, deleteIndex + 1)
        inputQueue.clear()
        inputQueue.addAll(cachedInputString.map { it.toString() })

        val newSelected = ArrayDeque<PinYinToken>()
        for (tok in selectedQueue) {
            when {
                tok.pos > deleteIndex -> newSelected.add(tok.copy(pos = tok.pos - 1))
                tok.pos + tok.raw.length <= deleteIndex -> newSelected.add(tok)
                // 覆盖被删数字的 token 还原为数字，不再保留
            }
        }
        selectedQueue.clear()
        selectedQueue.addAll(newSelected)

        if (cachedInputString.isEmpty() && committedPrefix.isEmpty()) {
            clearForEmpty()
            return true
        }

        t9CursorPos = deleteIndex.coerceIn(0, cachedInputString.length)
        val displayCursor = committedPrefix.length + t9CursorPos + countApostrophesBefore(t9CursorPos)
        updateRimeInputWithCursor(displayCursor)
        fireCandidatesChanged()
        return true
    }

    /**
     * 删除选中拼音 token 中第 off 位数字：
     * 拼音还原为数字后删除对应的一位，光标停在被删位置
     */
    private fun deleteTokenDigits(
        tok: PinYinToken,
        off: Int,
    ): Boolean {
        if (cachedInputString.isEmpty()) return false
        val deleteIndex = (tok.pos + off).coerceIn(0, cachedInputString.length - 1)

        selectedQueue.remove(tok)
        if (behaviorQueue.isNotEmpty()) behaviorQueue.removeLast()

        cachedInputString = cachedInputString.removeRange(deleteIndex, deleteIndex + 1)
        inputQueue.clear()
        inputQueue.addAll(cachedInputString.map { it.toString() })

        val newSelected = ArrayDeque<PinYinToken>()
        for (t in selectedQueue) {
            when {
                t.pos > deleteIndex -> newSelected.add(t.copy(pos = t.pos - 1))
                t.pos + t.raw.length <= deleteIndex -> newSelected.add(t)
                // 覆盖被删数字的 token 还原为数字，不再保留
            }
        }
        selectedQueue.clear()
        selectedQueue.addAll(newSelected)

        if (cachedInputString.isEmpty() && committedPrefix.isEmpty()) {
            clearForEmpty()
            return true
        }

        t9CursorPos = deleteIndex.coerceIn(0, cachedInputString.length)
        val displayCursor = committedPrefix.length + t9CursorPos + countApostrophesBefore(t9CursorPos)
        updateRimeInputWithCursor(displayCursor)
        fireCandidatesChanged()
        return true
    }

    /**
     * 删除汉字前缀中 hanIdx 处的汉字：
     * 汉字 -> 拼音 -> 数字进入数字流首位并删除一位；
     * 其后的汉字随之还原为拼音 token 进入数字流
     */
    private fun deleteHanBefore(hanIdx: Int): Boolean {
        if (hanIdx < 0 || hanIdx >= committedPrefix.length) return false

        val tokens = getPinyinTokensForDigits(committedPrefixDigits, committedPrefix)

        if (tokens.size != committedPrefix.length) {
            // 无法定位该汉字的数字段时退化为直接删除该汉字
            committedPrefix = committedPrefix.removeRange(hanIdx, hanIdx + 1)
            if (committedPrefixDigits.isNotEmpty()) {
                val avg = committedPrefixDigits.length / (committedPrefix.length + 1).coerceAtLeast(1)
                val start = hanIdx * avg
                val end = (start + avg).coerceAtMost(committedPrefixDigits.length)
                if (start < end) committedPrefixDigits = committedPrefixDigits.removeRange(start, end)
            }
            if (committedPrefix.isEmpty()) {
                committedPrefixDigits = ""
                clearForEmpty()
            } else {
                fireCandidatesChanged()
                rime.lifecycleScope.launch { rime.runOnReady { clearComposition() } }
            }
            return true
        }

        val seg = tokens[hanIdx]

        // 该汉字还原为数字并删除一位，剩余数字留在数字流首位
        val keepDigits = seg.raw.dropLast(1)

        // 光标之后的汉字还原为拼音 token，其数字紧跟在剩余数字之后
        val suffixTokens = mutableListOf<PinYinToken>()
        val suffixDigits = StringBuilder()
        var suffixPos = keepDigits.length
        for (j in hanIdx + 1 until tokens.size) {
            val t = tokens[j]
            suffixTokens.add(PinYinToken(pos = suffixPos, raw = t.raw, pinYin = t.pinYin))
            suffixPos += t.raw.length
            suffixDigits.append(t.raw)
        }

        committedPrefix = committedPrefix.substring(0, hanIdx)
        committedPrefixDigits = if (seg.pos > 0) committedPrefixDigits.substring(0, seg.pos) else ""

        cachedInputString = keepDigits + suffixDigits + cachedInputString
        inputQueue.clear()
        inputQueue.addAll(cachedInputString.map { it.toString() })

        val newSelected = ArrayDeque<PinYinToken>()
        for (t in suffixTokens) newSelected.add(t)
        val shift = keepDigits.length + suffixDigits.length
        for (t in selectedQueue) newSelected.add(t.copy(pos = t.pos + shift))
        selectedQueue.clear()
        selectedQueue.addAll(newSelected)
        behaviorQueue.clear()

        if (cachedInputString.isEmpty() && committedPrefix.isEmpty()) {
            clearForEmpty()
            return true
        }

        t9CursorPos = keepDigits.length
        val displayCursor = committedPrefix.length + t9CursorPos + countApostrophesBefore(t9CursorPos)
        updateRimeInputWithCursor(displayCursor)
        fireCandidatesChanged()
        return true
    }

    private fun clearForEmpty() {
        inputQueue.clear()
        selectedQueue.clear()
        behaviorQueue.clear()
        committedPrefixDigits = ""
        lastRimeInput = ""
        t9CursorPos = 0
        fireCandidatesChanged()
        rime.lifecycleScope.launch { rime.runOnReady { clearComposition() } }
    }

    private fun updateRimeInputWithCursor(displayCursor: Int) {
        val input = buildRimeInput()
        lastRimeInput = input
        val prefixLen = committedPrefix.length
        var rimeCursor = (displayCursor - prefixLen).coerceAtLeast(0)
        rimeCursor = rimeCursor.coerceIn(0, input.length)
        rime.lifecycleScope.launch {
            rime.runOnReady {
                setRawInput(input)
            }
            rime.runOnReady {
                moveCursorPos(rimeCursor)
            }
        }
    }

    private fun getPinyinForDigits(digits: String): String? {
        if (digits.isEmpty()) return null
        val candidates = T9PinYin.possibleCombinations(digits)
        if (candidates.isEmpty()) return null
        for (pinyin in candidates) {
            if (pinyin.length != digits.length) continue
            val mapped = buildString {
                for (c in pinyin) {
                    append(
                        when (c) {
                            in 'a'..'c' -> '2'
                            in 'd'..'f' -> '3'
                            in 'g'..'i' -> '4'
                            in 'j'..'l' -> '5'
                            in 'm'..'o' -> '6'
                            in 'p'..'s' -> '7'
                            in 't'..'v' -> '8'
                            in 'w'..'z' -> '9'
                            else -> c
                        },
                    )
                }
            }
            if (mapped == digits) return pinyin
        }
        return null
    }

    private fun getPinyinTokensForDigits(
        digits: String,
        han: String,
    ): List<PinYinToken> {
        if (digits.isEmpty() || han.isEmpty()) return emptyList()
        // 单字直接映射
        if (han.length == 1) {
            val pinyin = getPinyinForDigits(digits) ?: return emptyList()
            return listOf(PinYinToken(pos = 0, raw = digits, pinYin = pinyin))
        }
        // 多字：按字数切分数字串，寻找可映射为合法拼音的切分
        val hanCount = han.length

        // 动态规划寻找覆盖全长的切分
        fun dfs(
            pos: Int,
            hanIdx: Int,
            acc: MutableList<PinYinToken>,
        ): List<PinYinToken>? {
            if (hanIdx == hanCount) {
                return if (pos == digits.length) acc.toList() else null
            }
            if (pos >= digits.length) return null
            // 尝试长度 2..6 的拼音段
            for (len in 2..6) {
                if (pos + len > digits.length) break
                val subDigits = digits.substring(pos, pos + len)
                val pinyin = getPinyinForDigits(subDigits) ?: continue
                // 避免单字母 pinyin（如 a/i）除非必要
                if (pinyin.length == 1 && hanCount > 1) continue
                acc.add(PinYinToken(pos = pos, raw = subDigits, pinYin = pinyin))
                val res = dfs(pos + len, hanIdx + 1, acc)
                if (res != null) return res
                acc.removeAt(acc.lastIndex)
            }
            return null
        }
        dfs(0, 0, mutableListOf())?.let { return it }
        // 退化：整体作为一个拼音（若存在）
        getPinyinForDigits(digits)?.let {
            return listOf(PinYinToken(pos = 0, raw = digits, pinYin = it))
        }
        // 退化：按 4+2 等常见切分
        return emptyList()
    }

    fun onCandidateClicked(text: String) {
        if (text.isEmpty()) {
            return
        }

        pendingCandidateCommit = text

        Timber.d(
            "T9DBG onCandidateClicked: " +
                "text=[$text], " +
                "pendingCandidateCommit=[$pendingCandidateCommit], " +
                debugState(),
        )
    }

    fun onEscape(): Boolean {
        val hasInput =
            cachedInputString.isNotEmpty() ||
                selectedQueue.isNotEmpty() ||
                behaviorQueue.isNotEmpty() ||
                committedPrefix.isNotEmpty()

        if (!hasInput) {
            return false
        }

        inputQueue.clear()
        selectedQueue.clear()
        behaviorQueue.clear()
        cachedInputString = ""
        committedPrefix = ""
        committedPrefixDigits = ""
        lastRimeInput = ""
        t9CursorPos = 0

        fireCandidatesChanged()

        rime.lifecycleScope.launch {
            rime.runOnReady {
                clearComposition()
            }
        }

        return true
    }

    fun onCandidateSelected() {
        if (cachedInputString.isEmpty()) {
            return
        }

        selectedQueue.clear()
        behaviorQueue.clear()
    }

    private var lastRimeCursorPos: Int = 0
    private var lastRimeSelStart: Int = 0
    private var lastRimeSelEnd: Int = 0

    fun onCompositionUpdated(preedit: String) {
        onCompositionUpdatedInternal(preedit, -1, -1, -1)
    }

    fun onCompositionUpdated(composition: com.osfans.trime.core.CompositionProto) {
        onCompositionUpdatedInternal(
            composition.preedit.orEmpty(),
            composition.cursorPos,
            composition.selStart,
            composition.selEnd,
        )
    }

    private fun countDigitsBeforeCursor(preedit: String, cursorPos: Int): Int {
        if (preedit.isEmpty() || cursorPos <= 0) return 0
        val limit = cursorPos.coerceAtMost(preedit.length)
        var cnt = 0
        for (i in 0 until limit) {
            val c = preedit[i]
            if (c in '2'..'9') cnt++
        }
        return cnt
    }

    private fun syncT9CursorFromRime(preedit: String, cursorPos: Int) {
        if (preedit.isEmpty() || cursorPos < 0) return
        // 若有已选拼音或汉字前缀，T9 已自行管理光标，避免被 Rime 的带空格 preedit 误覆盖
        // 仅在纯数字态下严格按光标左侧的数字个数同步
        if (selectedQueue.isNotEmpty() || committedPrefix.isNotEmpty()) {
            // 对有前缀的情况，尝试按 digitPart 计数
            if (committedPrefix.isNotEmpty()) {
                val firstNonHan = preedit.indexOfFirst {
                    Character.UnicodeScript.of(it.code) != Character.UnicodeScript.HAN
                }
                if (firstNonHan >= 0 && cursorPos > firstNonHan) {
                    val digitPartCnt = countDigitsBeforeCursor(preedit.substring(firstNonHan), cursorPos - firstNonHan)
                    val newPos = digitPartCnt.coerceIn(0, cachedInputString.length)
                    if (newPos != t9CursorPos) {
                        t9CursorPos = newPos
                        fireCandidatesChanged()
                    }
                } else if (firstNonHan >= 0 && cursorPos <= firstNonHan) {
                    // 光标在汉字区内，拼音应为空
                    if (t9CursorPos != 0) {
                        t9CursorPos = 0
                        fireCandidatesChanged()
                    }
                }
            }
            return
        }
        val digitCnt = countDigitsBeforeCursor(preedit, cursorPos)
        if (digitCnt in 0..cachedInputString.length && digitCnt != t9CursorPos) {
            // 忽略 setRawInput 后、moveCursorPos 前的中间态（光标在末尾但 T9 预期在中间）
            if (digitCnt == cachedInputString.length && t9CursorPos < cachedInputString.length - 1) {
                return
            }
            t9CursorPos = digitCnt
            fireCandidatesChanged()
        }
    }

    private fun onCompositionUpdatedInternal(
        preedit: String,
        cursorPos: Int,
        selStart: Int,
        selEnd: Int,
    ) {
        if (cursorPos >= 0) {
            lastRimeCursorPos = cursorPos
            lastRimeSelStart = selStart
            lastRimeSelEnd = selEnd
            syncT9CursorFromRime(preedit, cursorPos)
        }
        Timber.d(
            "T9DBG onCompositionUpdated ENTER: " +
                "preedit=[$preedit], cursor=$cursorPos sel=$selStart-$selEnd, " +
                debugState(),
        )

        val pendingCandidate = pendingCandidateCommit

        if (pendingCandidate != null && preedit.isNotEmpty()) {
            pendingCandidateCommit = null

            Timber.d(
                "T9DBG onCompositionUpdated PENDING CANDIDATE -> PARTIAL: " +
                    "candidate=[$pendingCandidate], " +
                    "preedit=[$preedit]",
            )
        }

        if (preedit.isEmpty()) {
            Timber.d(
                "T9DBG onCompositionUpdated EMPTY -> keep T9 state: " +
                    debugState(),
            )
            lastRimeInput = ""
            return
        }

        // 查找首个非汉字字符，分离已提交汉字前缀与后续拼音/数字
        val firstNonHanIndex = preedit.indexOfFirst {
            Character.UnicodeScript.of(it.code) != Character.UnicodeScript.HAN
        }
        if (firstNonHanIndex > 0) {
            val rawPrefix = preedit.substring(0, firstNonHanIndex)
            val prefix = rawPrefix.trim()
            if (
                prefix.isNotEmpty() &&
                prefix.any {
                    Character.UnicodeScript.of(it.code) ==
                        Character.UnicodeScript.HAN
                }
            ) {
                val oldCached = cachedInputString
                val remainingPart = preedit.substring(firstNonHanIndex)
                val remainingDigits = remainingPart.filter { it in '2'..'9' }
                // 避免重复消息：同一 preedit 二次回调（Service+InputBar）或已含前缀的重复
                if (prefix == committedPrefix && committedPrefixDigits.isNotEmpty()) {
                    lastRimeInput = preedit
                    return
                }
                if (committedPrefix.isNotEmpty() && committedPrefix.endsWith(prefix) && oldCached == remainingDigits) {
                    lastRimeInput = preedit
                    return
                }

                if (remainingDigits.isEmpty()) {
                    if (oldCached.isNotEmpty() && committedPrefixDigits.isEmpty()) {
                        committedPrefixDigits = oldCached
                    }
                    // 即使无剩余数字也需记录前缀（用于 什么 单独显示）
                    if (committedPrefix.isEmpty()) {
                        committedPrefix = prefix
                    } else if (prefix != committedPrefix && !prefix.startsWith(committedPrefix)) {
                        // 追加新字，如 什 + 么 = 什么
                        committedPrefix += prefix
                    } else if (prefix.length > committedPrefix.length) {
                        committedPrefix = prefix
                    }
                    t9CursorPos = 0
                    lastRimeInput = preedit
                    return
                }

                // 计算本次新增前缀对应的数字段
                val computedDigits =
                    if (oldCached.endsWith(remainingDigits)) {
                        oldCached.substring(0, oldCached.length - remainingDigits.length)
                    } else {
                        // 尝试从 remainingPart 中提取数字段长度匹配
                        remainingDigits
                            .let { rd ->
                                if (oldCached.length >= rd.length) {
                                    oldCached.takeLast(rd.length).let { tail ->
                                        if (tail == rd) {
                                            oldCached.substring(0, oldCached.length - rd.length)
                                        } else {
                                            ""
                                        }
                                    }
                                } else {
                                    ""
                                }
                            }
                    }

                if (committedPrefix.isEmpty()) {
                    committedPrefix = prefix
                    committedPrefixDigits = computedDigits
                } else {
                    // 已有前缀，追加新字（什 + 么）
                    if (prefix == committedPrefix) {
                        // 重复不处理
                    } else if (prefix.startsWith(committedPrefix)) {
                        // 新前缀已包含旧，如 什 -> 什么
                        val added = prefix.substring(committedPrefix.length)
                        committedPrefix = prefix
                        if (computedDigits.isNotEmpty() && computedDigits.length >= committedPrefixDigits.length) {
                            committedPrefixDigits = computedDigits
                        } else if (computedDigits.isNotEmpty()) {
                            committedPrefixDigits += computedDigits
                        }
                    } else {
                        // 追加，如 什 + 么
                        committedPrefix += prefix
                        if (computedDigits.isNotEmpty()) {
                            // computedDigits 是本次新增汉字的数字（如 63 对应 么）
                            committedPrefixDigits += computedDigits
                        }
                    }
                }

                Timber.d(
                    "T9DBG onCompositionUpdated PREFIX: " +
                        "prefix=[$prefix], " +
                        "committedPrefix=[$committedPrefix], " +
                        "committedPrefixDigits=[$committedPrefixDigits]",
                )

                cachedInputString = remainingDigits

                inputQueue.clear()
                selectedQueue.clear()
                behaviorQueue.clear()

                inputQueue.addAll(
                    remainingDigits.map { it.toString() },
                )
                // 光标同步：按 Rime 光标在 remainingPart 中的位置
                if (cursorPos >= 0) {
                    val afterHan = preedit.substring(firstNonHanIndex)
                    val digitPosInRemaining = countDigitsBeforeCursor(afterHan, cursorPos - firstNonHanIndex)
                    t9CursorPos = digitPosInRemaining.coerceIn(0, remainingDigits.length)
                } else {
                    t9CursorPos = remainingDigits.length
                }

                lastRimeInput = preedit

                Timber.d(
                    "T9DBG onCompositionUpdated APPLY: " +
                        debugState(),
                )

                fireCandidatesChanged()
                return
            }
        }

        lastRimeInput = preedit
    }

    fun onRimeCommitText(text: String) {
        Timber.d(
            "T9DBG onRimeCommitText IGNORED: text=[$text], " +
                "controller=${debugState()}",
        )
    }

    fun onSegmentKey(): Boolean {
        if (inputQueue.isEmpty()) {
            return true
        }

        var selectedSize = 0
        selectedQueue.forEach {
            selectedSize += it.raw.length
        }

        if (selectedSize == inputQueue.size) {
            return true
        }

        val insertPos = t9CursorPos.coerceIn(0, cachedInputString.length)
        if (insertPos > 0 && cachedInputString.getOrNull(insertPos - 1) == SEGMENT_KEY_CHAR) return true
        if (insertPos < cachedInputString.length && cachedInputString.getOrNull(insertPos) == SEGMENT_KEY_CHAR) return true
        // 若末尾已是分隔符也禁止
        if (cachedInputString.isNotEmpty() && cachedInputString.last() == SEGMENT_KEY_CHAR && insertPos == cachedInputString.length) return true

        cachedInputString = buildString {
            append(cachedInputString.substring(0, insertPos))
            append(SEGMENT_KEY_CHAR)
            append(cachedInputString.substring(insertPos))
        }
        inputQueue.clear()
        inputQueue.addAll(cachedInputString.map { it.toString() })
        if (selectedQueue.isNotEmpty()) {
            val newSelected = ArrayDeque<PinYinToken>()
            for (tok in selectedQueue) {
                if (tok.pos >= insertPos) {
                    newSelected.add(tok.copy(pos = tok.pos + 1))
                } else {
                    newSelected.add(tok)
                }
            }
            selectedQueue.clear()
            selectedQueue.addAll(newSelected)
        }
        behaviorQueue.add(Behavior.SEGMENT)
        t9CursorPos = insertPos + 1
        val displayCursor = committedPrefix.length + t9CursorPos + countApostrophesBefore(t9CursorPos)
        updateRimeInputWithCursor(displayCursor)
        fireCandidatesChanged()

        return false
    }

    fun hasT9State(): Boolean = cachedInputString.isNotEmpty() ||
        committedPrefix.isNotEmpty() ||
        selectedQueue.isNotEmpty()

    fun getDisplayText(rimePreedit: String): String {
        val prefix = committedPrefix
        if (prefix.isEmpty() && rimePreedit.isEmpty() && cachedInputString.isEmpty() && selectedQueue.isEmpty()) return ""
        if (rimePreedit.isEmpty()) return prefix
        if (rimePreedit.startsWith(prefix)) return rimePreedit
        val firstNonHan = rimePreedit.indexOfFirst {
            Character.UnicodeScript.of(it.code) != Character.UnicodeScript.HAN
        }
        val digitPart = if (firstNonHan >= 0) rimePreedit.substring(firstNonHan) else rimePreedit
        return prefix + digitPart
    }

    fun isSegmentKeyCode(keyEventCode: Int): Boolean = keyEventCode == KeyEvent.KEYCODE_APOSTROPHE

    fun onSelectPinyin(
        pos: Int,
        raw: String,
        pinYin: String,
    ) {
        Timber.d(
            "T9DBG onSelectPinyin ENTER: " +
                "pos=$pos, raw=[$raw], pinYin=[$pinYin], " +
                debugState(),
        )
        selectedQueue.add(
            PinYinToken(
                pos = pos,
                raw = raw,
                pinYin = pinYin,
            ),
        )

        behaviorQueue.add(Behavior.SELECT_PINYIN)
        Timber.d(
            "T9DBG onSelectPinyin AFTER QUEUE: " +
                debugState(),
        )
        // 保持光标：若光标在已选段后则保持，否则移到选段末尾
        val tokenEnd = pos + raw.length
        if (t9CursorPos < tokenEnd) {
            t9CursorPos = tokenEnd
        }
        val displayCursor = committedPrefix.length + t9CursorPos + countApostrophesBefore(t9CursorPos)
        updateRimeInputWithCursor(displayCursor)
        fireCandidatesChanged()
    }

    fun computeCandidates(): List<PinYinToken> {
        if (inputQueue.isEmpty()) {
            return emptyList()
        }

        val position = nextSequencePosition()

        if (position < 0) {
            return emptyList()
        }
        // 严格按光标分隔：只取 position 到光标之间的数字
        val cursorLimit = if (t9CursorPos in (position + 1)..cachedInputString.length) {
            t9CursorPos
        } else if (t9CursorPos <= position) {
            return emptyList()
        } else {
            cachedInputString.length
        }
        if (cursorLimit <= position) return emptyList()
        val sequence = cachedInputString.substring(position, cursorLimit)

        return T9PinYin.possibleCombinations(sequence).map { pinYin ->
            var raw = sequence.substring(
                0,
                minOf(pinYin.length, sequence.length),
            )

            if (sequence.getOrNull(pinYin.length) == SEGMENT_KEY_CHAR) {
                raw += SEGMENT_KEY_CHAR.toString()
            }

            PinYinToken(
                pos = position,
                raw = raw,
                pinYin = pinYin,
            )
        }
    }

    fun buildRimeInput(): String {
        Timber.d(
            "T9DBG buildRimeInput ENTER: " +
                debugState(),
        )

        val input = cachedInputString

        if (selectedQueue.isEmpty()) {
            Timber.d(
                "T9DBG buildRimeInput RESULT: [$input], selectedQueue empty",
            )
            return input
        }

        val first = selectedQueue.first()
        val last = selectedQueue.last()

        val start = first.pos
        val end = last.pos + last.raw.length

        if (start < 0 || end > input.length) {
            return input
        }

        val result = StringBuilder()
            .append(input.substring(0, start))

        var cursor = start

        for (token in selectedQueue) {
            if (token.pos > cursor) {
                result.append(
                    input.substring(cursor, token.pos),
                )
            }

            val rawEnd = token.pos + token.raw.length

            if (
                rawEnd <= input.length &&
                input.regionMatches(
                    token.pos,
                    token.raw,
                    0,
                    token.raw.length,
                )
            ) {
                result.append(token.pinYin)
                result.append(SEGMENT_KEY_CHAR)
            } else {
                result.append(
                    input.substring(token.pos, rawEnd),
                )
            }

            cursor = rawEnd
        }

        val finalResult = result
            .append(input.substring(end))
            .toString()

        Timber.d(
            "T9DBG buildRimeInput RESULT: " +
                "[$finalResult], " +
                debugState(),
        )

        return finalResult
    }

    fun updateRimeInput() {
        val input = buildRimeInput()

        Timber.d(
            "T9DBG updateRimeInput: " +
                "setRawInput=[$input], " +
                debugState(),
        )
        lastRimeInput = input
        rime.lifecycleScope.launch {
            rime.runOnReady {
                setRawInput(input)
            }
        }
    }

    fun clear() {
        inputQueue.clear()
        selectedQueue.clear()
        behaviorQueue.clear()
        cachedInputString = ""
        committedPrefix = ""
        committedPrefixDigits = ""
        pendingCandidateCommit = null
        lastRimeInput = ""
        t9CursorPos = 0
        fireCandidatesChanged()
    }

    fun getCommittedPrefix(): String = committedPrefix

    fun hasPendingCandidateCommit(): Boolean = pendingCandidateCommit != null

    fun takePendingCandidateCommit(): String? {
        val text = pendingCandidateCommit
        pendingCandidateCommit = null
        return text
    }

    fun debugState(): String = "cachedInput=[$cachedInputString], " +
        "committedPrefix=[$committedPrefix], " +
        "committedPrefixDigits=[$committedPrefixDigits], " +
        "inputQueue=$inputQueue, " +
        "selectedQueue=$selectedQueue, " +
        "behaviorQueue=$behaviorQueue, " +
        "t9CursorPos=$t9CursorPos, " +
        "lastRimeInput=[$lastRimeInput]"

    private fun fireCandidatesChanged() {
        onCandidatesChanged?.invoke(computeCandidates())
    }

    private fun nextSequencePosition(): Int {
        if (selectedQueue.isEmpty()) {
            return 0
        }

        var pos = 0

        for (token in selectedQueue) {
            if (token.pos > pos) {
                return pos
            }

            val end = token.pos + token.raw.length

            if (end > pos) {
                pos = end
            }
        }

        if (pos >= inputQueue.size) {
            return pos
        }

        return pos
    }
}
