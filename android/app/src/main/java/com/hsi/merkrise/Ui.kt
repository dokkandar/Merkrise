package com.hsi.merkrise

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

// Palette sampled from the Figma file (Memrise / "Merkrise" page).
internal const val BG = 0xFF352542.toInt()      // screen background
internal const val SURFACE = 0xFF241431.toInt() // header, bottom bar, cards
internal const val YELLOW = 0xFFFEE73E.toInt()  // accent, primary button
internal const val DIM_YELLOW = 0xFF917E37.toInt() // "in progress" lesson pills
internal const val TEXT = 0xFFD1D1C5.toInt()
internal const val MUTED = 0x99D1D1C5.toInt()
internal const val TRACK = 0xFF9B9797.toInt()   // progress track
internal const val KEY = 0xFFD9D9D9.toInt()     // input field / keys / chips
internal const val INK = 0xFF233A4D.toInt()     // dark text on yellow / grey
internal const val GREEN = 0xFF5FA669.toInt()
internal const val RED = 0xFFC0392B.toInt()
internal const val LIGHT_RED = 0xFFFF7B72.toInt()   // wrong answer, readable on the dark background
internal const val LIGHT_GREEN = 0xFF7FD18B.toInt() // right answer, readable on the dark background

internal const val MEDIUM = "sans-serif-medium"
internal const val LIGHT = "sans-serif-light"
internal const val BLACK = "sans-serif-black"
internal const val REGULAR = "sans-serif"

/** Egg photos, in order: the progress indicator from first meeting a word to learning it. */
internal val EGGS = intArrayOf(
    R.drawable.egg_1, R.drawable.egg_2, R.drawable.egg_3, R.drawable.egg_4,
    R.drawable.egg_5, R.drawable.egg_6, R.drawable.egg_7,
)

internal const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
internal const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

internal fun Context.dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

internal fun Context.corners(color: Int, top: Int, bottom: Int) = GradientDrawable().apply {
    setColor(color)
    val t = dp(top).toFloat(); val b = dp(bottom).toFloat()
    cornerRadii = floatArrayOf(t, t, t, t, b, b, b, b)
}
internal fun Context.rounded(color: Int, r: Int) = corners(color, r, r)
internal fun Context.oval(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

internal fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

internal fun Context.text(
    s: CharSequence, size: Float, color: Int = TEXT, face: String = REGULAR,
    gravity: Int = Gravity.CENTER,
) = TextView(this).apply {
    text = s
    textSize = size
    setTextColor(color)
    typeface = Typeface.create(face, Typeface.NORMAL)
    this.gravity = gravity
}

/** A tinted vector icon. */
internal fun Context.icon(res: Int, tint: Int, size: Int = 24) = ImageView(this).apply {
    setImageResource(res)
    imageTintList = ColorStateList.valueOf(tint)
    layoutParams = lp(dp(size), dp(size))
}

/** Stacked "Merk / rise" wordmark in the Figma's big/small letter rhythm. */
internal fun Context.logo(size: Float = 28f) = text(
    SpannableStringBuilder("Merk\nrIse").apply {
        for ((from, to) in listOf(1 to 4, 5 to 6, 7 to 9)) {
            setSpan(RelativeSizeSpan(if (from == 5) 0.75f else 0.8f), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    },
    size, YELLOW, BLACK, Gravity.START,
).apply { setLineSpacing(0f, 0.85f) }

internal fun Context.gap(h: Int) = View(this).apply { layoutParams = lp(1, dp(h)) }

internal fun Context.rule() = View(this).apply {
    setBackgroundColor(0x33D1D1C5)
    layoutParams = lp(MATCH, dp(1)).apply { topMargin = dp(8) }
}

internal fun Context.pill(caption: String, fill: Int = YELLOW, ink: Int = INK, onClick: () -> Unit) =
    text(caption, 20f, ink, MEDIUM).apply {
        background = rounded(fill, 28)
        elevation = dp(3).toFloat()
        setPadding(dp(24), dp(14), dp(24), dp(14))
        layoutParams = lp(MATCH, WRAP).apply { topMargin = dp(12) }
        setOnClickListener { onClick() }
    }

internal fun Context.badge(label: String, size: Int, fill: Int = YELLOW, ink: Int = INK) =
    text(label, if (size > 50) 22f else 16f, ink, BLACK).apply {
        background = oval(fill)
        layoutParams = lp(dp(size), dp(size))
    }

/** Rounded grey chip, e.g. "already learnt". */
internal fun Context.chip(label: String, ink: Int = GREEN, onClick: (() -> Unit)? = null) =
    text(label, 13f, ink, MEDIUM).apply {
        background = rounded(KEY, 16)
        setPadding(dp(14), dp(6), dp(14), dp(6))
        onClick?.let { f -> setOnClickListener { f() } }
    }

internal fun Context.roundButton(label: String, size: Int = 40, onClick: (TextView) -> Unit) =
    text(label, 18f, INK).apply {
        background = oval(KEY)
        layoutParams = lp(dp(size), dp(size)).apply { marginStart = dp(8) }
        setOnClickListener { onClick(this) }
    }

/** Segmented progress bar: known (yellow) / learning (dim yellow) / rest (track). */
internal fun Context.segmentedBar(known: Int, learning: Int, total: Int, height: Int = 6, top: Int = 0) = LinearLayout(this).apply {
    background = rounded(TRACK, 3)
    layoutParams = lp(MATCH, dp(height)).apply { topMargin = dp(top) }
    val rest = (total - known - learning).coerceAtLeast(0)
    addView(View(context).apply { background = rounded(YELLOW, 3) }, lp(0, MATCH, known.toFloat()))
    addView(View(context).apply { setBackgroundColor(DIM_YELLOW) }, lp(0, MATCH, learning.toFloat()))
    addView(View(context), lp(0, 1, rest.toFloat().coerceAtLeast(if (total == 0) 1f else 0f)))
}

internal fun Context.card(vararg cells: View) = LinearLayout(this).apply {
    gravity = Gravity.CENTER_VERTICAL
    background = rounded(SURFACE, 22)
    elevation = dp(4).toFloat()
    setPadding(dp(16), dp(14), dp(16), dp(14))
    cells.forEach { addView(it) }
    layoutParams = lp(MATCH, WRAP).apply { bottomMargin = dp(12) }
}

internal fun Context.column(vararg lines: View) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    lines.forEach { addView(it) }
    layoutParams = lp(0, WRAP, 1f).apply { marginStart = dp(16) }
}

/** Left-to-right wrapping layout for the sentence tiles. */
class FlowLayout(context: Context, private val gapPx: Int) : ViewGroup(context) {
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val maxW = MeasureSpec.getSize(widthSpec) - paddingLeft - paddingRight
        var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            if (v.visibility == GONE) continue
            measureChild(v, MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED)
            if (x > 0 && x + v.measuredWidth > maxW) { x = 0; y += rowH + gapPx; rowH = 0 }
            x += v.measuredWidth + gapPx
            rowH = maxOf(rowH, v.measuredHeight)
        }
        setMeasuredDimension(
            resolveSize(maxW + paddingLeft + paddingRight, widthSpec),
            resolveSize(y + rowH + paddingTop + paddingBottom, heightSpec),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l - paddingLeft - paddingRight
        var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            if (v.visibility == GONE) continue
            if (x > 0 && x + v.measuredWidth > maxW) { x = 0; y += rowH + gapPx; rowH = 0 }
            v.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + v.measuredWidth, paddingTop + y + v.measuredHeight)
            x += v.measuredWidth + gapPx
            rowH = maxOf(rowH, v.measuredHeight)
        }
    }
}
