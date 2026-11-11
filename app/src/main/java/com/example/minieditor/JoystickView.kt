package com.example.minieditor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.sqrt

/** On-screen virtual joystick with a fixed ring. jx: right = +1, jy: up = +1. */
class JoystickView(ctx: Context) : View(ctx) {
    companion object { const val ZONE = 1.0f }

    private var vjx = 0f
    private var vjy = 0f
    private var vactive = false
    val jx: Float get() { expireLimbo(); return vjx }
    val jy: Float get() { expireLimbo(); return vjy }
    val active: Boolean get() { expireLimbo(); return vactive }

    // When the phone cancels the touch, the stick keeps its last push for a moment and waits for the finger to come back.
    private var limbo = false
    private var limboUntil = 0L
    private var lastWx = 0f
    private var lastWy = 0f
    private var claimX = -999f
    private var claimY = -999f
    private var claimTime = 0L
    private fun expireLimbo() {
        if (limbo && SystemClock.uptimeMillis() > limboUntil) { limbo = false; reset() }
    }
    /** True if a finger that has just landed at this window point was taken back by the stick (so the camera must not use it). */
    fun justClaimed(wx: Float, wy: Float): Boolean =
        SystemClock.uptimeMillis() - claimTime < 80 && abs(wx - claimX) < 6f && abs(wy - claimY) < 6f
    var interactive = false
    var onNote: ((String) -> Unit)? = null
    /** Pixels kept free between a thumb on the stick and the screen edge (a thumb at the bezel can be taken for a palm). */
    var edgeKeep = 0f

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
        val cx = if (vactive) ax else width / 2f
        val cy = if (vactive) ay else height / 2f
        c.drawCircle(cx, cy, r, basePaint)
        c.drawCircle(cx, cy, r, ringPaint)
        c.drawCircle(cx + kx, cy + ky, r * 0.38f, knobPaint)
    }

    private var pid = -1
    private val loc = IntArray(2)

    private fun follow(px: Float, py: Float, down: Boolean, time: Long) {
        val maxR = radius() * 0.62f
        // the ring stays where it is; only the knob moves
        ax = width / 2f
        ay = height / 2f
        if (down) {
            dnx = px; dny = py
            downTime = time; maxMove = 0f
        }
        var dx = px - ax
        var dy = py - ay
        val len = sqrt(dx * dx + dy * dy)
        maxMove = maxOf(maxMove, sqrt((px - dnx) * (px - dnx) + (py - dny) * (py - dny)))
        if (len > maxR) { dx = dx / len * maxR; dy = dy / len * maxR }
        kx = dx; ky = dy
        vjx = dx / maxR
        vjy = -dy / maxR
        vactive = true
        lastWx = px + loc[0]
        lastWy = py + loc[1]
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
            val wx = e.getX(i)
            val wy = e.getY(i)
            val x = wx - loc[0]
            val y = wy - loc[1]
            if (pid == -1) {
                val inside = x >= 0f && y >= 0f && x <= width && y <= height
                val dxl = wx - lastWx
                val dyl = wy - lastWy
                val back = limbo && e.eventTime <= limboUntil && sqrt(dxl * dxl + dyl * dyl) <= maxOf(width, height) * 1.5f
                if (inside || back) {
                    if (back) {
                        onNote?.invoke("Stick: touch came back")
                        claimX = wx; claimY = wy; claimTime = SystemClock.uptimeMillis()
                    }
                    limbo = false
                    pid = e.getPointerId(i)
                    follow(x, y, !back, e.eventTime)
                    return
                }
            }
        }
        if (limbo && act == MotionEvent.ACTION_UP) { limbo = false; reset() }   // every finger is gone
        if (pid == -1) return
        val i = e.findPointerIndex(pid)
        when (act) {
            MotionEvent.ACTION_CANCEL -> {
                val sb = StringBuilder("Stick cancel: n=").append(e.pointerCount).append(" scr=")
                    .append(resources.displayMetrics.widthPixels).append('x').append(resources.displayMetrics.heightPixels)
                for (k in 0 until e.pointerCount) {
                    sb.append(" [").append(e.getPointerId(k)).append(' ').append((e.getX(k) + 0f).toInt()).append(',')
                        .append(e.getY(k).toInt()).append(" size=").append((e.getSize(k) * 100f).toInt())
                        .append(" maj=").append(e.getTouchMajor(k).toInt()).append(" pr=").append((e.getPressure(k) * 100f).toInt())
                        .append(" tool=").append(e.getToolType(k)).append(']')
                }
                sb.append(" flags=").append(e.flags).append(" src=").append(e.source)
                onNote?.invoke(sb.toString())
                // keep the last push for a moment: if the phone gives the finger back, the stick carries on
                pid = -1
                limbo = true
                limboUntil = e.eventTime + 400
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
        kx = 0f; ky = 0f; vjx = 0f; vjy = 0f; vactive = false; limbo = false
        postInvalidate()
    }
}
