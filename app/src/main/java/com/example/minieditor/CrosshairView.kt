package com.example.minieditor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/** The aiming mark in the middle of the screen (shown in first person). It never takes touches. */
class CrosshairView(ctx: Context) : View(ctx) {
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    init {
        isClickable = false
        isFocusable = false
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val w = width.toFloat()
        val inner = w * 0.14f
        val outer = w * 0.40f
        for (pass in 0..1) {
            line.color = if (pass == 0) 0xAA000000.toInt() else 0xFFFFFFFF.toInt()
            line.strokeWidth = if (pass == 0) w * 0.075f else w * 0.04f
            c.drawLine(cx - outer, cy, cx - inner, cy, line)
            c.drawLine(cx + inner, cy, cx + outer, cy, line)
            c.drawLine(cx, cy - outer, cx, cy - inner, line)
            c.drawLine(cx, cy + inner, cx, cy + outer, line)
        }
        fill.color = 0xAA000000.toInt()
        c.drawCircle(cx, cy, w * 0.05f, fill)
        fill.color = 0xFFFF4040.toInt()
        c.drawCircle(cx, cy, w * 0.03f, fill)
    }
}
