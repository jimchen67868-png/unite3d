package com.example.minieditor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.sqrt

/**
 * On-screen virtual joystick. jx: right = +1, jy: up = +1.
 * The touch area is ZONE times bigger than the ring. The ring appears under the finger that touches it and follows the
 * thumb when it goes past the edge, so the thumb never has to stretch far (a stretched, flat thumb can make the phone cancel the touch).
 */
class JoystickView(ctx: Context) : View(ctx) {
    companion object { const val ZONE = 1.9f }

    var jx = 0f
    var jy = 0f
    var active = false
    var interactive = false
    var onNote: ((String) -> Unit)? = null

    private var downTime = 0L
    private var maxMove = 0f
    private var tapLatched = false
    private var kx = 0f
    private var ky = 0f
    private var ax = 0f          // ring centre while a finger is down (view coordinates)
    private var ay = 0f
    private var dnx = 0f         // where the finger first landed
    private var dny = 0f
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 255, 255, 255); style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 4f
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 255, 255, 255); style = Paint.Style.FILL
    }

    private fun radius() = minOf(width, height) / ZONE / 2f - 4f

    /** The resting ring in the parent's coordinates (used to keep the phone's edge gestures away from it). */
    fun ringRect(): android.graphics.Rect {
        val r = radius().toInt() + 4
        val cx = left + width / 2
        val cy = top + height / 2
        return android.graphics.Rect(cx - r, cy - r, cx + r, cy + r)
    }

    override fun onDraw(c: Canvas) {
        val r = radius()
        val cx = if (active) ax else width / 2f
        val cy = if (active) ay else height / 2f
        c.drawCircle(cx, cy, r, basePaint)
        c.drawCircle(cx, cy, r, ringPaint)
        c.drawCircle(cx + kx, cy + ky, r * 0.38f, knobPaint)
    }

    private var pid = -1
    private val loc = IntArray(2)

    private fun follow(px: Float, py: Float, down: Boolean, time: Long) {
        val r = radius() + 4f
        val maxR = radius() * 0.62f
        if (down) {
            // the ring appears under the finger (kept inside the touch area)
            ax = px.coerceIn(minOf(r, width / 2f), maxOf(width - r, width / 2f))
            ay = py.coerceIn(minOf(r, height / 2f), maxOf(height - r, height / 2f))
            dnx = px; dny = py
            downTime = time; maxMove = 0f
        }
        var dx = px - ax
        var dy = py - ay
        var len = sqrt(dx * dx + dy * dy)
        maxMove = maxOf(maxMove, sqrt((px - dnx) * (px - dnx) + (py - dny) * (py - dny)))
        if (len > maxR) {
            // drag the ring along with the finger
            val ex = len - maxR
            ax += dx / len * ex
            ay += dy / len * ex
            ax = ax.coerceIn(minOf(r, width / 2f), maxOf(width - r, width / 2f))
            ay = ay.coerceIn(minOf(r, height / 2f), maxOf(height - r, height / 2f))
            dx = px - ax; dy = py - ay
            len = sqrt(dx * dx + dy * dy)
            if (len > maxR) { dx = dx / len * maxR; dy = dy / len * maxR }
        }
        kx = dx; ky = dy
        jx = dx / maxR
        jy = -dy / maxR
        active = true
        invalidate()
    }

    private fun endTouch(time: Long, tapAllowed: Boolean) {
        if (tapAllowed && time - downTime < 250 && maxMove < radius() * 0.2f) tapLatched = true
        pid = -1
        reset()
    }

    /**
     * Called by the activity for EVERY touch event, before the views see it. The stick follows the one finger that
     * started on it, however many other fingers touch FIRE / JUMP / the screen, and whatever the views do with those events.
     */
    fun rawTouch(e: MotionEvent) {
        if (!interactive || visibility != View.VISIBLE) { if (pid != -1) { pid = -1; reset() }; return }
        getLocationInWindow(loc)
        val act = e.actionMasked
        if (act == MotionEvent.ACTION_DOWN) {
            // a brand new touch sequence: forget any stale finger
            if (pid != -1) { pid = -1; reset() }
        }
        if (act == MotionEvent.ACTION_DOWN || act == MotionEvent.ACTION_POINTER_DOWN) {
            val i = e.actionIndex
            val x = e.getX(i) - loc[0]
            val y = e.getY(i) - loc[1]
            if (pid == -1 && x >= 0f && y >= 0f && x <= width && y <= height) {
                pid = e.getPointerId(i)
                follow(x, y, true, e.eventTime)
                return
            }
        }
        if (pid == -1) return
        val i = e.findPointerIndex(pid)
        when (act) {
            MotionEvent.ACTION_CANCEL -> {
                val sb = StringBuilder("Stick cancel: n=").append(e.pointerCount).append(" scr=")
                    .append(resources.displayMetrics.widthPixels).append('x').append(resources.displayMetrics.heightPixels)
                for (k in 0 until e.pointerCount) {
                    sb.append(" [").append(e.getPointerId(k)).append(' ').append((e.getX(k) + 0f).toInt()).append(',')
                        .append(e.getY(k).toInt()).append(" size=").append((e.getSize(k) * 100f).toInt()).append(']')
                }
                onNote?.invoke(sb.toString())
                endTouch(e.eventTime, false)
            }
            MotionEvent.ACTION_UP -> endTouch(e.eventTime, i >= 0)
            MotionEvent.ACTION_POINTER_UP -> {
                if (e.getPointerId(e.actionIndex) == pid) endTouch(e.eventTime, true)
                else if (i >= 0) follow(e.getX(i) - loc[0], e.getY(i) - loc[1], false, e.eventTime)
            }
            else -> {
                // MOVE and any other event: keep following our finger; if it vanished, let go
                if (i >= 0) follow(e.getX(i) - loc[0], e.getY(i) - loc[1], false, e.eventTime)
                else endTouch(e.eventTime, false)
            }
        }
    }

    // the touch itself is handled in rawTouch; this only keeps a finger that lands on the stick from also turning the camera
    override fun onTouchEvent(e: MotionEvent): Boolean = interactive

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
