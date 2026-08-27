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
        /*
         * 如果上一次点击候选词后 Rime 已经把 composition 清空，
         * 那么下一次输入数字应该从“新的 T9 输入”开始，
         * 而不是把数字追加到上一段已经选完的 raw input。
         *
         * 注意：
         * committedPrefix 保留，因为它仍然属于当前可编辑的长句。
         */
        if (candidateCommitPending) {
            cachedInputString = ""
            inputQueue.clear()
            selectedQueue.clear()
            behaviorQueue.clear()
            lastRimeInput = ""

            candidateCommitPending = false
        }

        inputQueue.add(digit)
        cachedInputString += digit
        behaviorQueue.add(Behavior.NORMAL)

        fireCandidatesChanged()
    }

    fun onBackspace(): Boolean {
        if (cachedInputString.isEmpty()) {
            return false
        }

        // 最后一个已选拼音在原始数字串中的结束位置。
        // 例如：
        // 94354 + 选择 zhe
        // selectedQueue.last() = zhe, pos=0, raw.length=3
        // lastSelectedEnd = 3
        val lastSelectedEnd =
            selectedQueue.lastOrNull()?.let {
                it.pos + it.raw.length
            } ?: 0

        when {
            // 还有“已选拼音”后面的未锁定输入。
            // 优先删除这些输入，而不是取消前面已经锁定的拼音。
            cachedInputString.length > lastSelectedEnd -> {
                cachedInputString = cachedInputString.dropLast(1)

                if (inputQueue.isNotEmpty()) {
                    inputQueue.removeLast()
                }
            }

            // 没有未锁定尾部了，才取消最后一个已选拼音。
            selectedQueue.isNotEmpty() -> {
                selectedQueue.removeLast()
            }

            // 完全没有已选拼音，就是普通 T9 输入。
            else -> {
                cachedInputString = cachedInputString.dropLast(1)

                if (inputQueue.isNotEmpty()) {
                    inputQueue.removeLast()
                }
            }
        }

        // T9 已经完全清空。
        if (cachedInputString.isEmpty()) {
            inputQueue.clear()
            selectedQueue.clear()
            behaviorQueue.clear()
            lastRimeInput = ""

            fireCandidatesChanged()

            rime.lifecycleScope.launch {
                rime.runOnReady {
                    clearComposition()
                }
            }

            return true
        }

        // 本地状态改变后，Rime 也必须立即同步。
        updateRimeInput()
        fireCandidatesChanged()

        return true
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
                behaviorQueue.isNotEmpty()

        if (!hasInput) {
            return false
        }

        inputQueue.clear()
        selectedQueue.clear()
        behaviorQueue.clear()
        cachedInputString = ""
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

        val firstDigitIndex = preedit.indexOfFirst {
            it in '2'..'9'
        }

        if (firstDigitIndex > 0) {
            val prefix = preedit.substring(0, firstDigitIndex)

            if (
                prefix.any {
                    Character.UnicodeScript.of(it.code) ==
                        Character.UnicodeScript.HAN
                }
            ) {
                committedPrefix = prefix

                candidateCommitPending = false

                Timber.d(
                    "T9DBG onCompositionUpdated PREFIX: " +
                        "prefix=[$prefix], " +
                        "committedPrefix=[$committedPrefix]",
                )

                val remainingDigits = preedit
                    .substring(firstDigitIndex)
                    .filter { it in '2'..'9' }

                if (remainingDigits.isEmpty()) {
                    return
                }

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
        pendingCandidateCommit = text

        Timber.d(
            "T9DBG onRimeCommitText: " +
                "pendingCandidateCommit=[$pendingCandidateCommit], " +
                debugState(),
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
        lastRimeInput = ""
        fireCandidatesChanged()
    }

    fun getCommittedPrefix(): String = committedPrefix

    fun hasPendingCandidateCommit(): Boolean =
        pendingCandidateCommit != null

    fun takePendingCandidateCommit(): String? {
        val text = pendingCandidateCommit
        pendingCandidateCommit = null
        return text
    }

    fun debugState(): String = "cachedInput=[$cachedInputString], " +
        "committedPrefix=[$committedPrefix], " +
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
