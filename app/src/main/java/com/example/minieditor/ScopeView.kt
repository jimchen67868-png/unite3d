package com.example.minieditor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** A sniper scope: black all round, a round window in the middle and a fine crosshair. Shown while the view is zoomed in a lot. */
class ScopeView(ctx: Context) : View(ctx) {
    private val black = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt(); style = Paint.Style.FILL }
    private val thin = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt(); style = Paint.Style.STROKE; strokeWidth = 2.5f; strokeCap = Paint.Cap.BUTT }
    private val thick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt(); style = Paint.Style.STROKE; strokeWidth = 9f; strokeCap = Paint.Cap.BUTT }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF3030.toInt(); style = Paint.Style.FILL }

    init {
        isClickable = false
        isFocusable = false
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val r = minOf(w, h) * 0.46f
        val p = Path()
        p.addCircle(cx, cy, r, Path.Direction.CW)
        p.fillType = Path.FillType.INVERSE_WINDING
        c.drawPath(p, black)
        // the crosshair: fine in the middle, thick towards the rim
        c.drawLine(cx - r, cy, cx + r, cy, thin)
        c.drawLine(cx, cy - r, cx, cy + r, thin)
        val a = r * 0.35f
        c.drawLine(cx - r, cy, cx - a, cy, thick)
        c.drawLine(cx + a, cy, cx + r, cy, thick)
        c.drawLine(cx, cy - r, cx, cy - a, thick)
        c.drawLine(cx, cy + a, cx, cy + r, thick)
        c.drawCircle(cx, cy, 3f, dot)
    }
}
