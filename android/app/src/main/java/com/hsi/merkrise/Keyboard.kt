package com.hsi.merkrise

import android.content.Context
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.LinearLayout

/**
 * The Figma's on-screen keyboard (grey keys, QWERTZ) with the German letters.
 * Punctuation is not needed: answer checking ignores it.
 */
class GermanKeyboard(
    context: Context,
    private val onKey: (String) -> Unit,
    private val onBackspace: () -> Unit,
) : LinearLayout(context) {
    init {
        orientation = VERTICAL
        addRow(letters("qwertzuiopü"))
        addRow(letters("asdfghjklöä"))
        addRow(letters("yxcvbnmß") + backspaceKey())
        addRow(listOf(key("space", 6f) { onKey(" ") }))
    }

    private fun letters(s: String) = s.map { ch -> key(ch.toString(), 1f) { onKey(ch.toString()) } }

    private fun key(label: String, weight: Float, click: () -> Unit): View =
        context.text(label, if (label.length > 1) 15f else 21f, INK, MEDIUM).apply {
            background = context.rounded(KEY, 8)
            layoutParams = lp(0, context.dp(46), weight).apply { setMargins(context.dp(2), context.dp(3), context.dp(2), context.dp(3)) }
            setOnClickListener { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); click() }
        }

    private fun backspaceKey(): View = context.icon(R.drawable.ic_backspace, INK, 24).apply {
        scaleType = android.widget.ImageView.ScaleType.CENTER
        background = context.rounded(KEY, 8)
        layoutParams = lp(0, context.dp(46), 1.6f).apply { setMargins(context.dp(2), context.dp(3), context.dp(2), context.dp(3)) }
        setOnClickListener { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); onBackspace() }
    }

    private fun addRow(keys: List<View>) {
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER
            keys.forEach { addView(it) }
        }, lp(MATCH, WRAP))
    }
}
