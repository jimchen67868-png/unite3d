package com.example.minieditor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View

/** On-screen jump button. Scripts read input.jump (held) and input.jumpPressed (one frame per tap). */
class JumpButtonView(ctx: Context) : View(ctx) {
    var interactive = false
    var pressedNow = false
        private set
    private var latched = false

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 4f
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }

    /** True once per press, even if the tap was shorter than a frame. */
    fun consumePress(): Boolean {
        val r = latched
        latched = false
        return r
    }

    fun reset() {
        pressedNow = false
        latched = false
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f - 4f
        fill.color = if (pressedNow) Color.argb(190, 255, 255, 255) else Color.argb(80, 255, 255, 255)
        c.drawCircle(cx, cy, r, fill)
        c.drawCircle(cx, cy, r, ring)
        text.textSize = r * 0.42f
        c.drawText("JUMP", cx, cy - (text.ascent() + text.descent()) / 2f, text)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!interactive) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pressedNow = true; latched = true }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pressedNow = false
        }
        invalidate()
        return true
    }
}
