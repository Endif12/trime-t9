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
        inputQueue.add(digit)
        cachedInputString += digit
        behaviorQueue.add(Behavior.NORMAL)

        fireCandidatesChanged()
    }

    fun onBackspace(): Boolean {
        if (cachedInputString.isEmpty()) {
            if (committedPrefix.isNotEmpty()) {
                if (committedPrefixDigits.isNotEmpty()) {
                    val pinyin = getPinyinForDigits(committedPrefixDigits)
                    val newCached = committedPrefixDigits
                    committedPrefix = ""
                    committedPrefixDigits = ""
                    cachedInputString = newCached
                    inputQueue.clear()
                    inputQueue.addAll(newCached.map { it.toString() })
                    selectedQueue.clear()
                    behaviorQueue.clear()
                    if (pinyin != null) {
                        selectedQueue.add(
                            PinYinToken(
                                pos = 0,
                                raw = newCached,
                                pinYin = pinyin,
                            ),
                        )
                        behaviorQueue.add(Behavior.SELECT_PINYIN)
                    }
                    updateRimeInput()
                    fireCandidatesChanged()
                    return true
                }
                committedPrefix = committedPrefix.dropLast(1)
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

        // 优先处理已提交汉字：汉字 -> 拼音 -> 数字（满足 什么9474 -> shenme9474 -> 7436639474）
        if (committedPrefix.isNotEmpty()) {
            if (committedPrefixDigits.isNotEmpty()) {
                val prefixDigits = committedPrefixDigits
                val remaining = cachedInputString
                val newCached = prefixDigits + remaining
                val pinyin = getPinyinForDigits(prefixDigits)
                committedPrefix = ""
                committedPrefixDigits = ""
                cachedInputString = newCached
                inputQueue.clear()
                inputQueue.addAll(newCached.map { it.toString() })
                selectedQueue.clear()
                behaviorQueue.clear()
                if (pinyin != null) {
                    selectedQueue.add(
                        PinYinToken(
                            pos = 0,
                            raw = prefixDigits,
                            pinYin = pinyin,
                        ),
                    )
                    behaviorQueue.add(Behavior.SELECT_PINYIN)
                }
                updateRimeInput()
                fireCandidatesChanged()
                return true
            }
            // 无数字记录时逐字删汉字
            committedPrefix = committedPrefix.dropLast(1)
            if (committedPrefix.isEmpty()) {
                committedPrefixDigits = ""
            }
            fireCandidatesChanged()
            rime.lifecycleScope.launch {
                rime.runOnReady { clearComposition() }
            }
            return true
        }

        // 已有拼音选择时，优先将拼音还原为数字
        if (selectedQueue.isNotEmpty()) {
            selectedQueue.removeLast()
            // 若移除后仍有前序拼音，保留；否则回到纯数字
            if (selectedQueue.isEmpty()) {
                behaviorQueue.clear()
            } else {
                // 移除对应的 behavior
                if (behaviorQueue.isNotEmpty()) {
                    behaviorQueue.removeLast()
                }
            }
            updateRimeInput()
            fireCandidatesChanged()
            return true
        }

        // 仅剩数字时，从末尾逐位删除
        if (cachedInputString.isNotEmpty()) {
            cachedInputString = cachedInputString.dropLast(1)
            if (inputQueue.isNotEmpty()) {
                inputQueue.removeLast()
            }
            if (cachedInputString.isEmpty()) {
                inputQueue.clear()
                selectedQueue.clear()
                behaviorQueue.clear()
                lastRimeInput = ""
                fireCandidatesChanged()
                rime.lifecycleScope.launch {
                    rime.runOnReady { clearComposition() }
                }
                return true
            }
            updateRimeInput()
            fireCandidatesChanged()
            return true
        }

        return false
    }

    private fun getPinyinForDigits(digits: String): String? {
        if (digits.isEmpty()) return null
        val candidates = T9PinYin.possibleCombinations(digits)
        if (candidates.isEmpty()) return null
        // 优先选长度与数字串等长且映射完全一致的拼音（如 743663 -> shenme）
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
        // 退化：取最长候选
        return candidates.firstOrNull()
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

    fun onCompositionUpdated(preedit: String) {
        Timber.d(
            "T9DBG onCompositionUpdated ENTER: " +
                "preedit=[$preedit], " +
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

        if (inputQueue.last() == SEGMENT_KEY_CHAR.toString()) {
            return true
        }

        var selectedSize = 0
        selectedQueue.forEach {
            selectedSize += it.raw.length
        }

        if (selectedSize == inputQueue.size) {
            return true
        }

        inputQueue.add(SEGMENT_KEY_CHAR.toString())
        cachedInputString += SEGMENT_KEY_CHAR.toString()
        behaviorQueue.add(Behavior.SEGMENT)
        fireCandidatesChanged()

        return false
    }

    fun hasT9State(): Boolean = cachedInputString.isNotEmpty() ||
        committedPrefix.isNotEmpty() ||
        selectedQueue.isNotEmpty()

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
        updateRimeInput()
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

        val sequence = cachedInputString.substring(position)

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
