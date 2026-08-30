/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.bar

import android.content.Context
import android.os.Build
import android.util.Size
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestion
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.LinearLayout
import android.widget.ViewAnimator
import android.widget.inline.InlineContentView
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.R
import com.osfans.trime.core.Candidates
import com.osfans.trime.core.CompositionProto
import com.osfans.trime.core.RimeMessage
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.daemon.launchOnReady
import com.osfans.trime.data.db.ClipboardHelper
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.KeyActionManager
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.ime.bar.ui.AlwaysUi
import com.osfans.trime.ime.bar.ui.CandidateUi
import com.osfans.trime.ime.bar.ui.TabUi
import com.osfans.trime.ime.broadcast.InputBroadcastReceiver
import com.osfans.trime.ime.candidates.compact.CompactCandidateDelegate
import com.osfans.trime.ime.candidates.unrolled.window.FlexboxUnrolledCandidateWindow
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.dependency.InputDependencyManager
import com.osfans.trime.ime.keyboard.CommonKeyboardActionListener
import com.osfans.trime.ime.keyboard.KeyBehavior
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.switches.SwitchOptionWindow
import com.osfans.trime.ime.t9.T9PinyinView
import com.osfans.trime.ime.window.BoardWindow
import com.osfans.trime.ime.window.BoardWindowManager
import com.osfans.trime.ui.main.ClipEditActivity
import com.osfans.trime.util.AppUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.kodein.di.instance
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import java.util.concurrent.Executor
import kotlin.coroutines.resume

class InputBarDelegate : InputBroadcastReceiver {
    private val di = InputDependencyManager.getInstance().di
    private val context: Context by di.instance()
    private val service: TrimeInputMethodService by di.instance()
    private val theme: Theme by di.instance()
    private val windowManager: BoardWindowManager by di.instance()
    private val commonKeyboardActionListener: CommonKeyboardActionListener by di.instance()
    private val candidate: CompactCandidateDelegate by di.instance()
    private val rime: RimeSession by di.instance()

    // 主题原始 dp 值。注意：InputView 添加输入条时会统一做 dp->px 换算，
    // 这里必须保持原始 dp，否则会双重换算把输入条撑大数倍（候选栏下方出现大片空白）
    private val t9PinyinHeight = theme.generalStyle.candidateViewHeight

    private val baseThemedHeight = theme.generalStyle.candidateViewHeight

    // 输入条总高 = 候选栏高度 + 九宫格拼音栏（原始 dp）
    val themedHeight = baseThemedHeight + t9PinyinHeight

    /** 仅当前方案 id/名称包含 "t9"（不区分大小写）时启用拼音栏 */
    val isT9Schema: Boolean
        get() = runCatching { rime.run { statusCached } }.getOrNull()
            ?.let { it.schemaId.contains("t9", true) || it.schemaName.contains("t9", true) }
            ?: false

    /** 当前输入条应有的总高（原始 dp）：九宫格方案两行，其余一行 */
    val currentThemedHeight: Int
        get() = if (isT9Schema) themedHeight else baseThemedHeight

    private val t9PinyinUi =
        T9PinyinView(
            context,
            theme,
        ).apply {
            visibility = View.GONE

            setOnPinyinSelectedListener { token ->
                service.t9InputController.onSelectPinyin(
                    token.pos,
                    token.raw,
                    token.pinYin,
                )
            }
        }

    private val prefs = AppPrefs.defaultInstance()

    private val hideQuickBar by prefs.keyboard.hideInputBar

    private val clipboardSuggestion by prefs.clipboard.clipboardSuggestion

    private val clipboardSuggestionTimeout by prefs.clipboard.clipboardSuggestionTimeout

    private var clipboardTimeoutJob: Job? = null

    private var isClipboardFresh: Boolean = false
    private var isInlineSuggestionPresent: Boolean = false

    @Keep
    private val onClipboardUpdateListener = ClipboardHelper.OnClipboardUpdateListener {
        if (!clipboardSuggestion) return@OnClipboardUpdateListener
        service.lifecycleScope.launch {
            if (it.text.isNullOrEmpty()) {
                isClipboardFresh = false
            } else {
                alwaysUi.clipboardUi.text.text = it.text.take(42)
                isClipboardFresh = true
                launchClipboardTimeoutJob()
            }
            evalAlwaysUiState()
        }
    }

    private fun launchClipboardTimeoutJob() {
        clipboardTimeoutJob?.cancel()
        val timeout = clipboardSuggestionTimeout * 1000L
        if (timeout < 0L) return
        clipboardTimeoutJob = service.lifecycleScope.launch {
            delay(timeout)
            isClipboardFresh = false
            clipboardTimeoutJob = null
            evalAlwaysUiState()
        }
    }

    private fun evalAlwaysUiState() {
        val newState =
            when {
                isClipboardFresh -> AlwaysUi.State.Clipboard
                isInlineSuggestionPresent -> AlwaysUi.State.InlineSuggestion
                else -> AlwaysUi.State.Toolbar
            }
        if (newState == alwaysUi.currentState) return
        alwaysUi.updateState(newState)
    }

    private val swipeDownHideKeyboardCallback: ((KeyBehavior) -> Unit) = { d ->
        if (d == KeyBehavior.SWIPE_DOWN) {
            service.requestHideSelf(0)
        }
    }

    private val alwaysUi: AlwaysUi by lazy {
        AlwaysUi(context, theme) { action ->
            if (action.isNotEmpty()) {
                commonKeyboardActionListener.listener.onAction(KeyActionManager.getAction(action))
            } else {
                windowManager.attachWindow(SwitchOptionWindow())
            }
        }.apply {
            hideKeyboardButton.apply {
                setOnClickListener { service.requestHideSelf(0) }
                onSwipe = swipeDownHideKeyboardCallback
            }
            clipboardUi.suggestionView.apply {
                setOnClickListener {
                    val content = ClipboardHelper.lastBean?.text
                    content?.let { service.commitText(it) }
                    dismissClipboardSuggestion()
                }
                setOnLongClickListener {
                    ClipboardHelper.lastBean?.let {
                        AppUtils.launchClipEdit(context, it.id, ClipEditActivity.FROM_CLIPBOARD)
                    }
                    true
                }
            }
            clipboardUi.dismiss.setOnClickListener {
                dismissClipboardSuggestion()
            }
        }
    }

    private fun dismissClipboardSuggestion() {
        clipboardTimeoutJob?.cancel()
        clipboardTimeoutJob = null
        isClipboardFresh = false
        evalAlwaysUiState()
    }

    private val candidateUi by lazy {
        CandidateUi(context, theme, candidate.view).apply {
            unrollButton.apply {
                onSwipe = swipeDownHideKeyboardCallback
            }
        }
    }

    private val tabUi by lazy {
        TabUi(context, theme)
    }

    private val barStateMachine =
        QuickBarStateMachine.new {
            switchUiByState(it)
        }

    val unrollButtonStateMachine =
        UnrollButtonStateMachine.new {
            when (it) {
                UnrollButtonStateMachine.State.ClickToAttachWindow -> {
                    setUnrollButtonToAttach()
                    setUnrollButtonEnabled(true)
                }
                UnrollButtonStateMachine.State.ClickToDetachWindow -> {
                    setUnrollButtonToDetach()
                    setUnrollButtonEnabled(true)
                    setUnrollWindowToAttach()
                }
                UnrollButtonStateMachine.State.Hidden -> {
                    setUnrollButtonEnabled(false)
                }
            }
        }

    private fun setUnrollButtonToAttach() {
        candidateUi.unrollButton.setOnClickListener {
            windowManager.attachWindow(FlexboxUnrolledCandidateWindow())
        }
        candidateUi.unrollButton.setIcon(R.drawable.ic_baseline_expand_more_24)
    }

    private fun setUnrollButtonToDetach() {
        candidateUi.unrollButton.setOnClickListener {
            windowManager.attachWindow(KeyboardWindow)
        }
        candidateUi.unrollButton.setIcon(R.drawable.ic_baseline_expand_less_24)
    }

    private fun setUnrollButtonEnabled(enabled: Boolean) {
        candidateUi.unrollButton.visibility = if (enabled) View.VISIBLE else View.INVISIBLE
    }

    private fun setUnrollWindowToAttach() {
        unrollButtonStateMachine.getBooleanState(
            UnrollButtonStateMachine.BooleanKey.UnrolledCandidatesHighlighted,
        )?.let {
            if (!it) return@let
            windowManager.attachWindow(FlexboxUnrolledCandidateWindow())
        }
    }

    override fun onCandidateListUpdate(data: Candidates.Bulk) {
        // 仅九宫格方案需要合并 T9 拼音候选，其它方案跳过计算
        val t9HasCandidates = isT9Schema && service.t9InputController.computeCandidates().isNotEmpty()
        val isEmpty = data.candidates.isEmpty() && !t9HasCandidates
        barStateMachine.push(
            QuickBarStateMachine.TransitionEvent.CandidatesUpdated,
            QuickBarStateMachine.BooleanKey.CandidateEmpty to isEmpty,
        )
    }

    private fun switchUiByState(state: QuickBarStateMachine.State) {
        val index = state.ordinal
        if (view.displayedChild == index) return
        val new = view.getChildAt(index)
        if (new != tabUi.root) {
            tabUi.setBackButtonOnClickListener { }
            tabUi.setTitle("")
            tabUi.removeExternal()
        }
        view.displayedChild = index
    }

    val view by lazy {
        ViewAnimator(context).apply {

            service.t9InputController.onCandidatesChanged = { tokens ->
                t9PinyinUi.post {
                    // 仅九宫格方案显示拼音栏；其余方案或无拼音时隐藏，候选行占满整个输入条
                    t9PinyinUi.updateItems(if (isT9Schema) tokens else emptyList())
                }
                if (tokens.isNotEmpty()) {
                    barStateMachine.push(
                        QuickBarStateMachine.TransitionEvent.CandidatesUpdated,
                        QuickBarStateMachine.BooleanKey.CandidateEmpty to false,
                    )
                }
            }
            visibility =
                if (hideQuickBar) {
                    View.GONE
                } else {
                    View.VISIBLE
                }
            background =
                ColorManager.getDecorDrawable(
                    "candidate_background",
                    "candidate_border_color",
                    dp(theme.generalStyle.candidateBorder),
                    dp(theme.generalStyle.candidateBorderRound),
                )
            add(alwaysUi.root, lParams(matchParent, matchParent))
            add(candidateContainer, lParams(matchParent, matchParent))
            add(tabUi.root, lParams(matchParent, matchParent))

            evalAlwaysUiState()
            ClipboardHelper.addOnUpdateListener(onClipboardUpdateListener)
            syncToolbarOptionStates()
        }
    }

    private val candidateContainer by lazy {
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL

            // 拼音行：固定高度；非九宫格方案或无拼音候选时隐藏（隐藏后候选行 weight 撑满剩余空间）
            addView(
                t9PinyinUi,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    context.dp(t9PinyinHeight),
                ),
            )

            // 候选行：weight=1 填满剩余高度
            // 拼音栏可见时 = 候选栏高度；拼音栏隐藏时占满整个输入条（两行）
            addView(
                candidateUi.root,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                ),
            )
        }
    }

    override fun onStartInput(info: EditorInfo) {
        evalAlwaysUiState()
        applyBarHeight()
    }

    override fun onRimeSchemaUpdated(schema: com.osfans.trime.core.SchemaItem) {
        // 方案切换时同步拼音栏可见性与输入条高度（两行 <-> 一行）
        if (!isT9Schema) {
            t9PinyinUi.post { t9PinyinUi.updateItems(emptyList()) }
        }
        applyBarHeight()
    }

    override fun onInputStatusUpdate(value: com.osfans.trime.core.StatusProto) {
        applyBarHeight()
    }

    /** 输入条总高随方案变化：九宫格两行（拼音+候选），其它一行（仅候选） */
    private fun applyBarHeight() {
        val heightPx = context.dp(currentThemedHeight)
        if (view.height == heightPx) return
        view.updateLayoutParams { height = heightPx }
        // 非九宫格方案强制隐藏拼音栏
        if (!isT9Schema) {
            t9PinyinUi.visibility = View.GONE
        }
        view.requestLayout()
    }

    override fun onWindowAttached(window: BoardWindow) {
        if (window is BoardWindow.BarBoardWindow) {
            tabUi.setTitle(window.title)
            window.onCreateBarView()?.let { tabUi.addExternal(it, window.showTitle) }
            tabUi.setBackButtonOnClickListener {
                windowManager.attachWindow(KeyboardWindow)
            }
            barStateMachine.push(QuickBarStateMachine.TransitionEvent.BarBoardWindowAttached)
        }
    }

    override fun onWindowDetached(window: BoardWindow) {
        barStateMachine.push(QuickBarStateMachine.TransitionEvent.WindowDetached)
    }

    private val suggestionSize by lazy {
        Size(ViewGroup.LayoutParams.WRAP_CONTENT, context.dp(themedHeight))
    }

    private val directExecutor by lazy {
        Executor { it.run() }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun handleInlineSuggestions(response: InlineSuggestionsResponse): Boolean {
        val suggestions = response.inlineSuggestions
        if (suggestions.isEmpty()) {
            isInlineSuggestionPresent = false
            return true
        }
        var pinned: InlineSuggestion? = null
        val scrollable = mutableListOf<InlineSuggestion>()
        var extraPinnedCount = 0
        suggestions.forEach {
            if (it.info.isPinned) {
                if (pinned == null) {
                    pinned = it
                } else {
                    scrollable.add(extraPinnedCount++, it)
                }
            } else {
                scrollable.add(it)
            }
        }
        service.lifecycleScope.launch {
            alwaysUi.inlineSuggestionsUi.setPinnedView(
                pinned?.let { inflateInlineContentView(it) },
            )
        }
        service.lifecycleScope.launch {
            val views = scrollable.map { s ->
                service.lifecycleScope.async {
                    inflateInlineContentView(s)
                }
            }.awaitAll()
            alwaysUi.inlineSuggestionsUi.setScrollableViews(views)
        }
        isInlineSuggestionPresent = true
        evalAlwaysUiState()
        return true
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun inflateInlineContentView(suggestion: InlineSuggestion): InlineContentView? = suspendCancellableCoroutine { c ->
        // callback view might be null
        suggestion.inflate(context, suggestionSize, directExecutor) { v ->
            c.resume(v)
        }
    }

    /**
     * Seed the toolbar toggle buttons with the current value of their rime
     * options. Rime access stays here in the delegate: the buttons themselves
     * are pure views and only react to [updateButtonsStyle].
     */
    private fun syncToolbarOptionStates() {
        val options = alwaysUi.toggleOptions()
        if (options.isEmpty()) return
        rime.launchOnReady { api ->
            val states = options.associateWith { api.getRuntimeOption(it) }
            ContextCompat.getMainExecutor(context).execute {
                states.forEach { (option, enabled) ->
                    alwaysUi.updateButtonsStyle(option, enabled)
                }
            }
        }
    }

    override fun onRimeOptionUpdated(value: RimeMessage.OptionMessage.Data) {
        alwaysUi.updateButtonsStyle(value.option, value.value)
    }

    override fun onCompositionUpdate(data: CompositionProto) {
        service.t9InputController.onCompositionUpdated(data)
    }
}
