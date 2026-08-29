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
        // 光标感知：若 displayCursor 不在末尾，按光标左侧字符类型处理
        val (displayText, displayCursor) = getDisplayTextAndCursor()
        if (displayCursor == 0) return false

        // 仅剩汉字且 cached 为空时，按光标在汉字内的位置处理
        if (cachedInputString.isEmpty()) {
            if (committedPrefix.isNotEmpty()) {
                // display 仅为汉字前缀，如 "什么" 光标在 "什^么" 或 "什么^"
                val hanBefore = displayCursor - 1
                if (hanBefore < 0 || hanBefore >= committedPrefix.length) return false
                // 若光标左侧是汉字，按 “汉字->拼音->数字” 逐级回退该字
                // 为简化：若 prefix 长度>1 且光标不在末尾，仍整体回退为拼音（用户主要场景为末尾）
                // 此处实现末尾回退为拼音，与之前逻辑一致；光标在中间时逐字删汉字
                if (displayCursor == committedPrefix.length && committedPrefixDigits.isNotEmpty()) {
                    val tokens = getPinyinTokensForDigits(committedPrefixDigits, committedPrefix)
                    val newCached = committedPrefixDigits
                    committedPrefix = ""
                    committedPrefixDigits = ""
                    cachedInputString = newCached
                    inputQueue.clear()
                    inputQueue.addAll(newCached.map { it.toString() })
                    selectedQueue.clear()
                    behaviorQueue.clear()
                    if (tokens.isNotEmpty()) {
                        for (t in tokens) {
                            selectedQueue.add(t)
                            behaviorQueue.add(Behavior.SELECT_PINYIN)
                        }
                    } else {
                        getPinyinForDigits(newCached)?.let { pinyin ->
                            selectedQueue.add(
                                PinYinToken(
                                    pos = 0,
                                    raw = newCached,
                                    pinYin = pinyin,
                                ),
                            )
                            behaviorQueue.add(Behavior.SELECT_PINYIN)
                        }
                    }
                    t9CursorPos = newCached.length
                    val displayCursorNew = t9CursorPos + countApostrophesBefore(t9CursorPos)
                    updateRimeInputWithCursor(displayCursorNew)
                    fireCandidatesChanged()
                    return true
                }
                // 光标在汉字中间：删除该汉字及对应数字段
                // 简化：按汉字逐字删
                val beforeHanIdx = hanBefore
                // 找到该汉字对应的数字段长度（通过切分）
                val tokens = getPinyinTokensForDigits(committedPrefixDigits, committedPrefix)
                if (tokens.size == committedPrefix.length) {
                    val tokenToRemove = tokens.getOrNull(beforeHanIdx)
                    if (tokenToRemove != null) {
                        val newDigits = committedPrefixDigits.removeRange(tokenToRemove.pos, tokenToRemove.pos + tokenToRemove.raw.length)
                        val newHan = committedPrefix.removeRange(beforeHanIdx, beforeHanIdx + 1)
                        committedPrefix = newHan
                        committedPrefixDigits = newDigits
                        if (newHan.isEmpty()) {
                            // 全部删完
                            inputQueue.clear()
                            selectedQueue.clear()
                            behaviorQueue.clear()
                            lastRimeInput = ""
                            fireCandidatesChanged()
                            rime.lifecycleScope.launch { rime.runOnReady { clearComposition() } }
                        } else {
                            fireCandidatesChanged()
                            rime.lifecycleScope.launch { rime.runOnReady { clearComposition() } }
                        }
                        return true
                    }
                }
                committedPrefix = committedPrefix.removeRange(hanBefore, hanBefore + 1)
                // 粗略同步 digits：按比例删
                if (committedPrefixDigits.isNotEmpty()) {
                    val avgLen = committedPrefixDigits.length / (committedPrefix.length + 1)
                    val start = hanBefore * avgLen
                    val end = (start + avgLen).coerceAtMost(committedPrefixDigits.length)
                    if (start < end) {
                        committedPrefixDigits = committedPrefixDigits.removeRange(start, end)
                    }
                }
                fireCandidatesChanged()
                rime.lifecycleScope.launch {
                    rime.runOnReady {
                        clearComposition()
                    }
                }
                if (committedPrefix.isEmpty()) {
                    committedPrefixDigits = ""
                    inputQueue.clear()
                    selectedQueue.clear()
                    behaviorQueue.clear()
                    lastRimeInput = ""
                }
                return true
            }
            return false
        }

        // 光标感知的通用删除
        val (displayText2, displayCursor2) = getDisplayTextAndCursor()
        if (displayCursor2 <= 0 || displayCursor2 > displayText2.length) return false
        val charBefore = displayText2[displayCursor2 - 1]
        val isHanBefore = Character.UnicodeScript.of(charBefore.code) == Character.UnicodeScript.HAN
        val isPinyinBefore = charBefore in 'a'..'z' || charBefore in 'A'..'Z'
        val isDigitBefore = charBefore in '2'..'9'
        val isApostropheBefore = charBefore == '\''

        // 1. 汉字左侧：汉字 -> 拼音 -> 数字
        if (isHanBefore) {
            // 光标在汉字区内
            val hanIdx = displayCursor2 - 1 // 0-based in display, prefix part is 0..prefixLen-1
            if (hanIdx < committedPrefix.length) {
                // 删除该汉字
                if (committedPrefixDigits.isNotEmpty()) {
                    val tokens = getPinyinTokensForDigits(committedPrefixDigits, committedPrefix)
                    if (tokens.size == committedPrefix.length && hanIdx < tokens.size) {
                        val tok = tokens[hanIdx]
                        // 将该汉字还原为拼音/数字：先变为拼音
                        val newTokens = tokens.toMutableList()
                        // 移除该汉字的 token，替换为拼音 token 保持可再删为数字
                        // 简化：整体回退为拼音态
                        val allTokens = getPinyinTokensForDigits(committedPrefixDigits, committedPrefix)
                        if (allTokens.isNotEmpty()) {
                            // 将整个前缀转为拼音态
                            val remaining = cachedInputString
                            val newCached = committedPrefixDigits + remaining
                            committedPrefix = ""
                            committedPrefixDigits = ""
                            cachedInputString = newCached
                            inputQueue.clear()
                            inputQueue.addAll(newCached.map { it.toString() })
                            selectedQueue.clear()
                            behaviorQueue.clear()
                            for (t in allTokens) {
                                // 调整 pos 加上 prefix 已转的偏移
                                selectedQueue.add(t)
                                behaviorQueue.add(Behavior.SELECT_PINYIN)
                            }
                            t9CursorPos = newCached.length
                            val displayCursor = t9CursorPos + countApostrophesBefore(t9CursorPos)
                            updateRimeInputWithCursor(displayCursor)
                            fireCandidatesChanged()
                            return true
                        }
                    }
                }
                committedPrefix = committedPrefix.removeRange(hanIdx, hanIdx + 1)
                if (committedPrefixDigits.isNotEmpty()) {
                    // 按字数比例删对应数字段
                    val handler = committedPrefix.length + 1
                    val avg = committedPrefixDigits.length / handler.coerceAtLeast(1)
                    val start = hanIdx * avg
                    val end = (start + avg).coerceAtMost(committedPrefixDigits.length)
                    if (start < end) committedPrefixDigits = committedPrefixDigits.removeRange(start, end)
                    if (committedPrefix.isEmpty()) committedPrefixDigits = ""
                }
                fireCandidatesChanged()
                rime.lifecycleScope.launch { rime.runOnReady { clearComposition() } }
                return true
            }
        }

        // 2. 拼音左侧：拼音 -> 数字
        if (isPinyinBefore || isApostropheBefore) {
            // 找到光标前的拼音 token
            // 将 displayCursor 映射到 Rime 侧
            val prefixLen = committedPrefix.length
            val rimeCursorInDisplay = displayCursor2 - prefixLen
            // 在 selectedQueue 中找包含 rimeCursor-1 的 token
            var target: PinYinToken? = null
            // 构建 Rime 侧的显示（不含前缀）以定位
            val rimeDisplay = getDisplayText(lastRimeInput).removePrefix(committedPrefix)
            // 简化：直接移除最后一个拼音（光标在拼音区时通常为末尾拼音）
            if (selectedQueue.isNotEmpty()) {
                // 若光标在拼音区，移除包含光标前字符的 token
                // 近似：移除最后选中的拼音
                val removed = selectedQueue.removeLast()
                if (behaviorQueue.isNotEmpty()) behaviorQueue.removeLast()
                if (selectedQueue.isEmpty()) behaviorQueue.clear()
                // 光标回退到被删拼音的起始位置
                t9CursorPos = removed.pos.coerceIn(0, cachedInputString.length)
                val displayCursor = committedPrefix.length + t9CursorPos + countApostrophesBefore(t9CursorPos)
                updateRimeInputWithCursor(displayCursor)
                fireCandidatesChanged()
                return true
            }
        }

        // 3. 数字左侧：逐位删数字
        if (isDigitBefore) {
            // 将 displayCursor 映射到 cached 数字索引
            val prefixLen = committedPrefix.length
            // 统计 display 中前缀后的非汉字字符中，数字字符数到光标前
            val rimePart = if (displayText2.length > prefixLen) displayText2.substring(prefixLen) else ""
            var digitCountBefore = 0
            for (i in 0 until (displayCursor2 - prefixLen).coerceAtLeast(0)) {
                if (i < rimePart.length && rimePart[i] in '2'..'9') digitCountBefore++
            }
            // digitCountBefore 为光标前数字个数，删除第 digitCountBefore-1 个数字
            val digitIdx = digitCountBefore - 1
            if (digitIdx >= 0 && digitIdx < cachedInputString.length) {
                // 考虑 selected 拼音占用的 raw 段，需映射到 cached 索引
                // 简化：若存在选中拼音，优先按拼音 token 边界删
                if (selectedQueue.isNotEmpty()) {
                    // 找到包含 digitIdx 的 token 边界
                    for (tok in selectedQueue) {
                        if (digitIdx >= tok.pos && digitIdx < tok.pos + tok.raw.length) {
                            // 光标在拼音内，按拼音->数字处理（已在上面处理）
                            selectedQueue.remove(tok)
                            if (behaviorQueue.isNotEmpty()) behaviorQueue.removeLast()
                            t9CursorPos = tok.pos.coerceIn(0, cachedInputString.length)
                            val displayCursor = committedPrefix.length + t9CursorPos + countApostrophesBefore(t9CursorPos)
                            updateRimeInputWithCursor(displayCursor)
                            fireCandidatesChanged()
                            return true
                        }
                    }
                }
                // 纯数字删除：保持光标，严格按光标分隔
                cachedInputString = cachedInputString.removeRange(digitIdx, digitIdx + 1)
                inputQueue.clear()
                inputQueue.addAll(cachedInputString.map { it.toString() })
                val newSelected = ArrayDeque<PinYinToken>()
                for (tok in selectedQueue) {
                    if (tok.pos > digitIdx) {
                        newSelected.add(tok.copy(pos = tok.pos - 1))
                    } else if (tok.pos + tok.raw.length <= digitIdx) {
                        newSelected.add(tok)
                    }
                }
                selectedQueue.clear()
                selectedQueue.addAll(newSelected)
                // 更新光标：删后停在被删位置
                t9CursorPos = digitIdx.coerceIn(0, cachedInputString.length)
                if (cachedInputString.isEmpty()) {
                    inputQueue.clear()
                    selectedQueue.clear()
                    behaviorQueue.clear()
                    lastRimeInput = ""
                    t9CursorPos = 0
                    fireCandidatesChanged()
                    rime.lifecycleScope.launch { rime.runOnReady { clearComposition() } }
                    return true
                }
                val newDisplayCursor = committedPrefix.length + t9CursorPos + countApostrophesBefore(t9CursorPos)
                updateRimeInputWithCursor(newDisplayCursor)
                fireCandidatesChanged()
                return true
            }
        }

        // 兜底：按末位删
        if (cachedInputString.isNotEmpty()) {
            // 兜底也需维护光标：若 t9CursorPos 在末尾则同步
            val deletePos = (t9CursorPos - 1).coerceIn(0, cachedInputString.length - 1)
            cachedInputString = cachedInputString.removeRange(deletePos, deletePos + 1)
            if (inputQueue.isNotEmpty()) {
                // 重建 inputQueue 以保持与 cached 一致
                inputQueue.clear()
                inputQueue.addAll(cachedInputString.map { it.toString() })
            }
            t9CursorPos = deletePos.coerceIn(0, cachedInputString.length)
            if (cachedInputString.isEmpty()) {
                inputQueue.clear()
                selectedQueue.clear()
                behaviorQueue.clear()
                lastRimeInput = ""
                t9CursorPos = 0
                fireCandidatesChanged()
                rime.lifecycleScope.launch { rime.runOnReady { clearComposition() } }
                return true
            }
            // 调整 selectedQueue
            val newSelected2 = ArrayDeque<PinYinToken>()
            for (tok in selectedQueue) {
                if (tok.pos > deletePos) {
                    newSelected2.add(tok.copy(pos = tok.pos - 1))
                } else if (tok.pos + tok.raw.length <= deletePos) {
                    newSelected2.add(tok)
                }
            }
            selectedQueue.clear()
            selectedQueue.addAll(newSelected2)
            val displayCursor = committedPrefix.length + t9CursorPos + countApostrophesBefore(t9CursorPos)
            updateRimeInputWithCursor(displayCursor)
            fireCandidatesChanged()
            return true
        }

        return false
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
