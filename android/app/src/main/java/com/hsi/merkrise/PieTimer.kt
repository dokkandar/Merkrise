package com.hsi.merkrise

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View

/** The timed cards' clock (Figma "oct 2026" quick review): a pie wedge of the time left over a pale dial. */
class PieTimer(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val face = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66F5F2F2 }
    private val outerRim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.25f * density; color = 0x66D9D9D9
    }
    private val innerRim = Paint(outerRim).apply { color = 0x33D9D9D9 }
    private val wedge = Paint(Paint.ANTI_ALIAS_FLAG)
    private var left = 1f

    /** Share of the time left, 1 = full. */
    fun setLeft(value: Float) {
        left = value.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        wedge.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(0xCC4B5CCA.toInt(), 0xCC4B5CCA.toInt(), 0xCCDEF4FF.toInt(), 0xCCDEF4FF.toInt()),
            floatArrayOf(0f, 0.48f, 0.83f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = minOf(width, height) / 2f
        val cx = width / 2f
        val cy = height / 2f
        val inner = r * 220f / 391f
        val rim = outerRim.strokeWidth / 2f
        canvas.drawCircle(cx, cy, r, face)
        canvas.drawCircle(cx, cy, r - rim, outerRim)
        canvas.drawCircle(cx, cy, inner, face)
        canvas.drawCircle(cx, cy, inner - rim, innerRim)
        // the time already used is cut out clockwise from 12 o'clock
        if (left > 0f) canvas.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -90f + 360f * (1 - left), 360f * left, true, wedge)
    }
}
