package com.example.minieditor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View

/**
 * The backpack screen, drawn like the PUBG bag: weapon slots, ammo, grenades and medkits, and a weight bar.
 * The script sends the data as text lines (camera.bagText). A tap on a weapon or the medkit is sent back (input.bagTap).
 * Touches outside the panel go through to the game.
 */
class BagView(ctx: Context, private val onTap: (String) -> Unit) : View(ctx) {
    private var raw = ""
    private val rows = ArrayList<List<String>>()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private val rect = RectF()
    private var scale = 1f
    private var ox = 0f
    private var oy = 0f
    private var downKey: String? = null

    // the layout is made in a 150 x 100 box and scaled to the screen
    private fun weaponR(i: Int) = RectF(6f + i * 45f, 24f, 6f + i * 45f + 42f, 46f)
    private val medR = RectF(76f, 79f, 144f, 95f)
    private val grenR = RectF(6f, 79f, 74f, 95f)
    private val closeR = RectF(136f, 3f, 147f, 14f)

    fun setData(text: String) {
        if (text == raw) return
        raw = text
        rows.clear()
        for (line in text.split("\n")) if (line.isNotEmpty()) rows.add(line.split("|"))
        invalidate()
    }

    private fun measure() {
        val w = width.toFloat()
        val h = height.toFloat()
        scale = minOf(w * 0.9f / 150f, h * 0.88f / 100f)
        ox = (w - 150f * scale) / 2f
        oy = (h - 100f * scale) / 2f
    }

    private fun hitKey(x: Float, y: Float): String? {
        if (closeR.contains(x, y)) return "close"
        for (i in 0 until 3) if (weaponR(i).contains(x, y)) return "w" + (i + 1)
        if (medR.contains(x, y)) return "med"
        return null
    }

    private fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, align: Paint.Align = Paint.Align.LEFT) {
        tp.textSize = size
        tp.color = color
        tp.textAlign = align
        c.drawText(s, x, y, tp)
    }

    private fun box(c: Canvas, r: RectF, fill: Int, stroke: Int, sw: Float) {
        p.style = Paint.Style.FILL
        p.color = fill
        c.drawRoundRect(r, 2f, 2f, p)
        if (sw > 0f) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = sw
            p.color = stroke
            c.drawRoundRect(r, 2f, 2f, p)
        }
    }

    private fun num(s: List<String>, i: Int): Int = s.getOrNull(i)?.toIntOrNull() ?: 0

    override fun onDraw(c: Canvas) {
        measure()
        c.save()
        c.translate(ox, oy)
        c.scale(scale, scale)
        rect.set(0f, 0f, 150f, 100f)
        box(c, rect, 0xEE0E1217.toInt(), 0xFF3A424C.toInt(), 0.6f)
        text(c, "BACKPACK", 6f, 12f, 6.5f, 0xFFFFFFFF.toInt())

        var used = 0
        var total = 200
        for (r in rows) if (r[0] == "C") { used = num(r, 1); total = maxOf(1, num(r, 2)) }
        // weight bar
        rect.set(60f, 6.5f, 112f, 10.5f)
        box(c, rect, 0xFF1E252D.toInt(), 0, 0f)
        val k = (used.toFloat() / total).coerceIn(0f, 1f)
        rect.set(60f, 6.5f, 60f + 52f * k, 10.5f)
        box(c, rect, if (k > 0.85f) 0xFFE05A3A.toInt() else 0xFFF5A623.toInt(), 0, 0f)
        text(c, "$used / $total", 115f, 10.8f, 4f, 0xFFDDE3EA.toInt())
        // close button
        box(c, closeR, 0xFF2A323B.toInt(), 0xFF566270.toInt(), 0.5f)
        text(c, "X", (closeR.left + closeR.right) / 2f, closeR.bottom - 3f, 6f, 0xFFFFFFFF.toInt(), Paint.Align.CENTER)

        // weapons
        text(c, "WEAPONS", 6f, 21.5f, 3.6f, 0xFF9AA4B0.toInt())
        var slot = 0
        for (r in rows) {
            if (r[0] != "W" && r[0] != "E") continue
            if (slot > 2) break
            val wr = weaponR(slot)
            if (r[0] == "W") {
                val active = r.getOrNull(5) == "1"
                box(c, wr, if (active) 0xFF2A2418.toInt() else 0xFF1E252D.toInt(), if (active) 0xFFF5A623.toInt() else 0xFF3A424C.toInt(), if (active) 1.2f else 0.5f)
                text(c, "${slot + 1}", wr.left + 3f, wr.top + 6f, 4.2f, 0xFF9AA4B0.toInt())
                if (active) text(c, "IN HANDS", wr.right - 3f, wr.top + 6f, 3.2f, 0xFFF5A623.toInt(), Paint.Align.RIGHT)
                text(c, r.getOrElse(1) { "" }, wr.left + 4f, wr.top + 14f, 6f, 0xFFFFFFFF.toInt())
                text(c, "${num(r, 2)} / ${num(r, 3)}", wr.left + 4f, wr.top + 20.5f, 4.4f, 0xFFDDE3EA.toInt())
                text(c, r.getOrElse(4) { "" }, wr.right - 3f, wr.top + 20.5f, 3.6f, 0xFF9AA4B0.toInt(), Paint.Align.RIGHT)
            } else {
                box(c, wr, 0xFF161B21.toInt(), 0xFF2A323B.toInt(), 0.5f)
                text(c, "EMPTY", (wr.left + wr.right) / 2f, (wr.top + wr.bottom) / 2f + 1.5f, 4.2f, 0xFF566270.toInt(), Paint.Align.CENTER)
            }
            slot++
        }
        while (slot < 3) {
            val wr = weaponR(slot)
            box(c, wr, 0xFF161B21.toInt(), 0xFF2A323B.toInt(), 0.5f)
            text(c, "EMPTY", (wr.left + wr.right) / 2f, (wr.top + wr.bottom) / 2f + 1.5f, 4.2f, 0xFF566270.toInt(), Paint.Align.CENTER)
            slot++
        }

        // ammo
        text(c, "AMMO", 6f, 53f, 3.6f, 0xFF9AA4B0.toInt())
        var ai = 0
        for (r in rows) {
            if (r[0] != "A" || ai > 3) continue
            val x = 6f + ai * 34.5f
            rect.set(x, 56f, x + 32f, 70f)
            box(c, rect, 0xFF1E252D.toInt(), 0xFF3A424C.toInt(), 0.5f)
            val col = (0xFF000000.toInt()) or (r.getOrElse(4) { "888888" }.toLongOrNull(16)?.toInt() ?: 0x888888)
            rect.set(x, 56f, x + 2.6f, 70f)
            box(c, rect, col, 0, 0f)
            text(c, r.getOrElse(1) { "" }, x + 5f, 62f, 4.4f, 0xFFFFFFFF.toInt())
            text(c, "${num(r, 2)}", x + 5f, 68.2f, 5.6f, col)
            text(c, "/${num(r, 3)}", x + 30f, 68.2f, 3f, 0xFF7A8592.toInt(), Paint.Align.RIGHT)
            ai++
        }

        // items
        text(c, "ITEMS", 6f, 76f, 3.6f, 0xFF9AA4B0.toInt())
        var gn = 0; var gm = 5; var mn = 0; var mm = 4
        for (r in rows) {
            if (r[0] == "G") { gn = num(r, 1); gm = num(r, 2) }
            if (r[0] == "M") { mn = num(r, 1); mm = num(r, 2) }
        }
        box(c, grenR, 0xFF1E252D.toInt(), 0xFF3A424C.toInt(), 0.5f)
        p.style = Paint.Style.FILL
        p.color = 0xFF4E7A33.toInt()
        c.drawCircle(grenR.left + 8f, (grenR.top + grenR.bottom) / 2f, 4.5f, p)
        text(c, "GRENADES", grenR.left + 16f, grenR.top + 7f, 4.4f, 0xFFFFFFFF.toInt())
        text(c, "$gn / $gm", grenR.left + 16f, grenR.top + 13.5f, 5f, 0xFFDDE3EA.toInt())
        val medOn = mn > 0
        box(c, medR, 0xFF1E252D.toInt(), if (medOn) 0xFFD9453A.toInt() else 0xFF3A424C.toInt(), if (medOn) 0.9f else 0.5f)
        p.style = Paint.Style.FILL
        p.color = 0xFFE23B3B.toInt()
        val my = (medR.top + medR.bottom) / 2f
        c.drawRect(medR.left + 3.5f, my - 1.3f, medR.left + 12.5f, my + 1.3f, p)
        c.drawRect(medR.left + 6.7f, my - 4.5f, medR.left + 9.3f, my + 4.5f, p)
        text(c, "MEDKITS", medR.left + 16f, medR.top + 7f, 4.4f, 0xFFFFFFFF.toInt())
        text(c, "$mn / $mm", medR.left + 16f, medR.top + 13.5f, 5f, 0xFFDDE3EA.toInt())
        if (medOn) text(c, "TAP TO USE", medR.right - 3f, medR.top + 13.5f, 3.4f, 0xFFD9453A.toInt(), Paint.Align.RIGHT)
        c.restore()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        measure()
        val lx = (e.x - ox) / scale
        val ly = (e.y - oy) / scale
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (lx < 0f || ly < 0f || lx > 150f || ly > 100f) return false   // outside the panel: the game gets the touch
                downKey = hitKey(lx, ly)
                return true
            }
            MotionEvent.ACTION_UP -> {
                val d = downKey
                downKey = null
                if (d != null && d == hitKey(lx, ly)) onTap(d)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                downKey = null
                return true
            }
        }
        return true
    }
}
