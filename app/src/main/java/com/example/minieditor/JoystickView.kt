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
    var interactive = false

    private var downTime = 0L
    private var maxMove = 0f
    private var tapLatched = false
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
        if (!interactive) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                var dx = e.x - width / 2f
                var dy = e.y - height / 2f
                val maxR = radius() * 0.62f
                val len = sqrt(dx * dx + dy * dy)
                if (e.actionMasked == MotionEvent.ACTION_DOWN) { downTime = e.eventTime; maxMove = 0f }
                maxMove = maxOf(maxMove, len)
                if (len > maxR) { dx = dx / len * maxR; dy = dy / len * maxR }
                kx = dx; ky = dy
                jx = dx / maxR
                jy = -dy / maxR
                active = true
            }
            MotionEvent.ACTION_UP -> {
                if (e.eventTime - downTime < 250 && maxMove < radius() * 0.2f) tapLatched = true
                reset()
            }
            MotionEvent.ACTION_CANCEL -> reset()
        }
        invalidate()
        return true
    }

    /** True once after a quick tap on the stick (touch and release without dragging). */
    fun consumeTap(): Boolean {
        val r = tapLatched
        tapLatched = false
        return r
    }

    fun reset() {
        kx = 0f; ky = 0f; jx = 0f; jy = 0f; active = false
        invalidate()
    }
}
