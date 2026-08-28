/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.composition

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewOutlineProvider
import com.osfans.trime.core.CompositionProto
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.daemon.launchOnReady
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.ime.broadcast.InputBroadcastReceiver
import com.osfans.trime.ime.core.TouchEventReceiverWindow
import com.osfans.trime.ime.dependency.InputDependencyManager
import org.kodein.di.instance
import splitties.dimensions.dp
import splitties.views.horizontalPadding

class PreeditDelegate : InputBroadcastReceiver {

    private val context: Context by InputDependencyManager.getInstance().di.instance()
    private val theme: Theme by InputDependencyManager.getInstance().di.instance()
    private val rime: RimeSession by InputDependencyManager.getInstance().di.instance()
    private val service: com.osfans.trime.ime.core.TrimeInputMethodService by InputDependencyManager.getInstance().di.instance()

    val ui =
        PreeditUi(
            context,
            theme,
            setupPreeditView = {
                val radiusSize = dp(theme.preedit.topEndRadius)
                val radii = if (layoutDirection == View.LAYOUT_DIRECTION_LTR) {
                    floatArrayOf(0f, 0f, radiusSize, radiusSize, 0f, 0f, 0f, 0f)
                } else {
                    floatArrayOf(radiusSize, radiusSize, 0f, 0f, 0f, 0f, 0f, 0f)
                }
                background = GradientDrawable().apply {
                    setColor(ColorManager.getColor("text_back_color"))
                    shape = GradientDrawable.RECTANGLE
                    cornerRadii = radii
                }
                clipToOutline = true
                outlineProvider = ViewOutlineProvider.BACKGROUND
                horizontalPadding = dp(theme.preedit.horizontalPadding)
            },
            onMoveCursor = { pos ->
                val prefix = service.t9InputController.getCommittedPrefix()
                val adjustedPos =
                    if (prefix.isNotEmpty()) {
                        (pos - prefix.length).coerceAtLeast(0)
                    } else {
                        pos
                    }
                rime.launchOnReady { it.moveCursorPos(adjustedPos) }
            },
        ).apply {
            root.alpha = theme.preedit.alpha
            root.visibility = View.INVISIBLE
        }

    private val touchEventReceiverWindow = TouchEventReceiverWindow(ui.root)

    override fun onCompositionUpdate(data: CompositionProto) {
        val merged = mergeCompositionWithT9Prefix(data)
        ui.update(merged)
        ui.root.visibility = if (ui.visible) View.VISIBLE else View.INVISIBLE
        if (merged.length > 0) {
            touchEventReceiverWindow.show()
        } else {
            touchEventReceiverWindow.dismiss()
        }
    }

    private fun mergeCompositionWithT9Prefix(data: CompositionProto): CompositionProto {
        if (!service.t9InputController.hasT9State()) return data
        val prefix = service.t9InputController.getCommittedPrefix()
        if (prefix.isEmpty()) return data
        val preedit = data.preedit.orEmpty()
        if (preedit.isEmpty()) {
            return CompositionProto(
                length = prefix.length,
                cursorPos = prefix.length,
                selStart = 0,
                selEnd = prefix.length,
                preedit = prefix,
                commitTextPreview = prefix,
            )
        }
        if (preedit.startsWith(prefix)) {
            return data.copy(
                selStart = 0,
                selEnd = data.length,
            )
        }
        val firstNonHan = preedit.indexOfFirst {
            Character.UnicodeScript.of(it.code) != Character.UnicodeScript.HAN
        }
        val digitPart = if (firstNonHan >= 0) preedit.substring(firstNonHan) else preedit
        val mergedPreedit = prefix + digitPart
        val prefixLen = prefix.length
        return data.copy(
            length = mergedPreedit.length,
            cursorPos = if (firstNonHan >= 0) data.cursorPos + prefixLen - firstNonHan else data.cursorPos + prefixLen,
            selStart = 0,
            selEnd = mergedPreedit.length,
            preedit = mergedPreedit,
            commitTextPreview = data.commitTextPreview?.let { prefix + digitPart } ?: mergedPreedit,
        )
    }
}
