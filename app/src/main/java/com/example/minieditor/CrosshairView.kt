package com.example.minieditor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.view.View

/** The aiming mark in the middle of the screen (shown in first person). It never takes touches. */
class CrosshairView(ctx: Context) : View(ctx) {
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private var hitAt = 0L
    private var hitKind = 0

    /** A bullet or blast hit an enemy: the mark turns red for a moment (kind 2 = headshot or kill: longer, with an X). */
    fun hit(kind: Int) {
        hitAt = SystemClock.uptimeMillis()
        hitKind = kind
        postInvalidateOnAnimation()
    }

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
        val span = if (hitKind == 2) 600L else 350L
        val el = SystemClock.uptimeMillis() - hitAt
        val hot = hitAt != 0L && el < span
        val a = if (hot) (255 * (1f - el.toFloat() / span)).toInt().coerceIn(0, 255) else 0
        for (pass in 0..1) {
            line.color = if (pass == 0) 0xAA000000.toInt() else if (hot) ((a shl 24) or 0xFF2020) else 0xFFFFFFFF.toInt()
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
        if (hot) {
            // a red X mark like PUBG: four short slanted strokes around the middle
            val i2 = w * 0.07f
            val o2 = w * (if (hitKind == 2) 0.20f else 0.15f)
            for (pass in 0..1) {
                line.color = if (pass == 0) ((a / 2 shl 24)) else ((a shl 24) or 0xFF2020)
                line.strokeWidth = if (pass == 0) w * 0.08f else w * 0.05f
                for (sx in intArrayOf(-1, 1)) for (sy in intArrayOf(-1, 1))
                    c.drawLine(cx + sx * i2, cy + sy * i2, cx + sx * o2, cy + sy * o2, line)
            }
            postInvalidateOnAnimation()
        }
    }
}
