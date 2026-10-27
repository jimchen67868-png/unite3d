package com.example.minieditor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.sqrt

/** On-screen virtual joystick. jx: right = +1, jy: up = +1. */
class JoystickView(ctx: Context) : View(ctx) {
    var jx = 0f
    var jy = 0f
    var active = false

    private var kx = 0f
    private var ky = 0f
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 255, 255, 255); style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 4f
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 255, 255, 255); style = Paint.Style.FILL
    }

    private fun radius() = minOf(width, height) / 2f - 4f

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = radius()
        c.drawCircle(cx, cy, r, basePaint)
        c.drawCircle(cx, cy, r, ringPaint)
        c.drawCircle(cx + kx, cy + ky, r * 0.38f, knobPaint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                var dx = e.x - width / 2f
                var dy = e.y - height / 2f
                val maxR = radius() * 0.62f
                val len = sqrt(dx * dx + dy * dy)
                if (len > maxR) { dx = dx / len * maxR; dy = dy / len * maxR }
                kx = dx; ky = dy
                jx = dx / maxR
                jy = -dy / maxR
                active = true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> reset()
        }
        invalidate()
        return true
    }

    fun reset() {
        kx = 0f; ky = 0f; jx = 0f; jy = 0f; active = false
        invalidate()
    }
}
