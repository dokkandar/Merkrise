package com.hsi.merkrise

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

class ProgressRing(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val stroke = 7f * density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        color = 0x33D1D1C5
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
        color = YELLOW
    }
    private var fraction = 0f

    fun setProgress(value: Float) {
        fraction = value.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = stroke / 2f + 2f * density
        val rect = RectF(inset, inset, width - inset, height - inset)
        canvas.drawArc(rect, 0f, 360f, false, track)
        if (fraction > 0f) canvas.drawArc(rect, -90f, 360f * fraction, false, arc)
    }
}
