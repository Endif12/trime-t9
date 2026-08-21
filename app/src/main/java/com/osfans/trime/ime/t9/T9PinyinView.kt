package com.osfans.trime.ime.t9

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.util.sp
import splitties.dimensions.dp

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

    private val highlightedTextColor: Int by lazy {
        runCatching {
            ColorManager.getColor("hilited_candidate_text_color")
        }.getOrElse {
            textColor
        }
    }

    private val highlightedBackColor: Int by lazy {
        runCatching {
            ColorManager.getColor("hilited_candidate_back_color")
        }.getOrElse {
            Color.TRANSPARENT
        }
    }

    private val textFont: Typeface by lazy {
        Typeface.create("sans-serif", Typeface.NORMAL)
    }

    private val textSize: Float by lazy {
        sp(theme.window.foreground.textFontSize)
    }

    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        isFillViewport = false

        addView(
            container,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    fun setOnPinyinSelectedListener(
        listener: (T9InputController.PinYinToken) -> Unit,
    ) {
        onPinyinSelected = listener
    }

    fun updateItems(
        items: List<T9InputController.PinYinToken>,
    ) {
        container.removeAllViews()

        if (items.isEmpty()) {
            visibility = GONE
            return
        }

        visibility = VISIBLE

        items.forEachIndexed { index, token ->
            val item = createItem(token)
            container.addView(item)

            if (index < items.lastIndex) {
                container.addView(createDivider())
            }
        }

        container.requestLayout()
        requestLayout()
    }

    private fun createItem(
        token: T9InputController.PinYinToken,
    ): TextView = TextView(context).apply {
        text = token.display
        setTextColor(textColor)
        textSize = this@T9PinyinView.textSize
        typeface = textFont
        gravity = Gravity.CENTER

        val horizontal = dp(theme.window.itemPadding.horizontal)
        val vertical = dp(theme.window.itemPadding.vertical)

        setPadding(
            horizontal,
            vertical / 2,
            horizontal,
            vertical / 2,
        )

        isClickable = true
        isFocusable = false

        background = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = dp(theme.generalStyle.candidateCornerRadius).toFloat()
        }

        setOnClickListener {
            onPinyinSelected?.invoke(token)
        }
    }

    private fun createDivider(): TextView = TextView(context).apply {
        text = "│"
        setTextColor(highlightedTextColor)
        textSize = this@T9PinyinView.textSize * 0.8f
        gravity = Gravity.CENTER

        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )

        setPadding(
            dp(2),
            0,
            dp(2),
            0,
        )

        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
}
