package com.osfans.trime.ime.t9

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.FontManager
import com.osfans.trime.data.theme.Theme
import splitties.dimensions.dp
import splitties.views.horizontalPadding

class T9PinyinView(
    context: Context,
    private val theme: Theme,
) : HorizontalScrollView(context) {

    private val container = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private var onPinyinSelected: ((T9InputController.PinYinToken) -> Unit)? = null

    private val textColor: Int by lazy {
        runCatching {
            ColorManager.getColor("candidate_text_color")
        }.getOrElse {
            Color.WHITE
        }
    }

    private val textSize: Float = theme.generalStyle.candidateTextSize

    private val textFont: Typeface = FontManager.getTypeface("candidate_font")

    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        isFillViewport = false
        horizontalPadding = dp(4)

        addView(
            container,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    fun setOnPinyinSelectedListener(
        listener: (T9InputController.PinYinToken) -> Unit,
    ) {
        onPinyinSelected = listener
    }

    // 视图池：按键频率高，避免每次候选更新都销毁重建 TextView
    private val pooledItems = ArrayDeque<TextView>()
    private val pooledDividers = ArrayDeque<TextView>()

    private var lastShownTokens: List<T9InputController.PinYinToken> = emptyList()

    fun updateItems(
        items: List<T9InputController.PinYinToken>,
    ) {
        // 内容未变化（重复回调），跳过重建
        if (items == lastShownTokens) {
            visibility = if (items.isEmpty()) GONE else VISIBLE
            return
        }
        lastShownTokens = items

        // 当前子视图回收入池（可点击的是候选项，否则是分隔符）
        for (i in 0 until container.childCount) {
            val v = container.getChildAt(i) as TextView
            if (v.isClickable) pooledItems.addLast(v) else pooledDividers.addLast(v)
        }
        container.removeAllViews()

        if (items.isEmpty()) {
            visibility = GONE
            return
        }

        visibility = VISIBLE

        items.forEachIndexed { index, token ->
            val item =
                pooledItems.removeFirstOrNull()
                    ?: createItemView()
            bindItem(item, token)
            container.addView(item)

            if (index < items.lastIndex) {
                val divider =
                    pooledDividers.removeFirstOrNull()
                        ?: createDividerView()
                container.addView(divider)
            }
        }

        // 多余的视图留池复用
        container.requestLayout()
        requestLayout()
    }

    private fun createItemView(): TextView = TextView(context).apply {
        setTextColor(textColor)
        textSize = this@T9PinyinView.textSize
        typeface = textFont
        gravity = Gravity.CENTER
        includeFontPadding = false

        isClickable = true
        isFocusable = false

        setPadding(
            dp(6),
            0,
            dp(6),
            0,
        )

        layoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )

        setOnClickListener { v ->
            onPinyinSelected?.invoke(v.tag as T9InputController.PinYinToken)
        }
    }

    private fun bindItem(
        view: TextView,
        token: T9InputController.PinYinToken,
    ) {
        view.text = token.pinYin
        view.tag = token
    }

    private fun createDividerView(): TextView = TextView(context).apply {
        text = "│"
        setTextColor(textColor)
        alpha = 0.4f
        textSize = this@T9PinyinView.textSize * 0.7f
        gravity = Gravity.CENTER
        includeFontPadding = false

        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
}
