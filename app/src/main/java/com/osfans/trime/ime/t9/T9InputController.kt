package com.osfans.trime.ime.t9

import android.view.KeyEvent
import com.osfans.trime.core.RimeMessage
import com.osfans.trime.daemon.RimeSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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
    private var lastRimeInput = ""
    private var messageJob: Job? = null

    companion object {
        const val SEGMENT_KEY_CHAR = '\''
        const val SEGMENT_KEY_CHAR_ALIAS = '1'
    }

    init {
        messageJob = rime.lifecycleScope.launch {
            rime.run { messageFlow }.collect { message ->
                if (message is RimeMessage.CommitTextMessage) {
                    val text = message.data.text
                    if (!text.isNullOrEmpty()) {
                        clear()
                    }
                }
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

        if (!modified) {
            return false
        }

        // T9 输入已经完全退格清空：
        // 同时清除本地选择状态、Rime 的旧 composition，
        // 防止下一次输入继续继承上一轮的拼音候选。
        if (cachedInputString.isEmpty()) {
            selectedQueue.clear()
            behaviorQueue.clear()
            lastRimeInput = ""

            rime.lifecycleScope.launch {
                rime.runOnReady {
                    clearComposition()
                }
            }
        }

        fireCandidatesChanged()

        return true
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
        selectedQueue.add(
            PinYinToken(
                pos = pos,
                raw = raw,
                pinYin = pinYin,
            ),
        )

        behaviorQueue.add(Behavior.SELECT_PINYIN)
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
        val input = cachedInputString

        if (selectedQueue.isEmpty()) {
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

        return result
            .append(input.substring(end))
            .toString()
    }

    fun updateRimeInput() {
        val input = buildRimeInput()
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
        lastRimeInput = ""
        fireCandidatesChanged()
    }

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
