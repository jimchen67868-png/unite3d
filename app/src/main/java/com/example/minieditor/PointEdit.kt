package com.example.minieditor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.opengl.Matrix
import android.view.MotionEvent
import android.view.View
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Editing the points (vertices) of an object. A cube, pyramid or sphere becomes a Model whose points can be dragged;
 * points that sit at the same place move together. "save" writes a .obj text under a name and returns the loaded model.
 */
class PointEdit private constructor(
    val scene: Scene,
    val renderer: SceneRenderer,
    private val save: (String, String) -> ModelData?,
    private val original: SceneObject
) {
    lateinit var obj: SceneObject
    var pts = FloatArray(0)      // x y z of every point, in the object's own space
    var nPts = 0
    var tris = IntArray(0)       // 3 point numbers per triangle
    var sel = -1
    var axis = 0                 // 0 = free, 1 = X, 2 = Y, 3 = Z (the object's own axes)
    private var serial = 0
    private val stamp = System.currentTimeMillis()

    companion object {
        const val MAX_POINTS = 2000

        /** Starts editing the points of [s]. Null if the object cannot be edited (or has too many points). */
        fun start(scene: Scene, renderer: SceneRenderer, s: SceneObject, save: (String, String) -> ModelData?): PointEdit? {
            if (s.shape != Shape.CUBE && s.shape != Shape.PYRAMID && s.shape != Shape.SPHERE && s.shape != Shape.MODEL) return null
            val pe = PointEdit(scene, renderer, save, s)
            return if (pe.begin()) pe else null
        }

        /** Gives the object another basic shape (cube / pyramid / sphere), keeping its place, size and look. */
        fun reshape(scene: Scene, s: SceneObject, shape: Shape): SceneObject? {
            if (s.shape != Shape.CUBE && s.shape != Shape.PYRAMID && s.shape != Shape.SPHERE) return null
            val n = copyOf(s, shape, null)
            swap(scene, s, n)
            return n
        }

        private fun copyOf(s: SceneObject, shape: Shape, model: ModelData?): SceneObject {
            val o = SceneObject(shape, s.name, s.x, s.y, s.z, s.rx, s.ry, s.rz, s.sx, s.sy, s.sz, s.color.copyOf(), model)
            o.script = s.script
            o.collider = s.collider
            o.shadow = s.shadow
            o.photo = s.photo
            o.uid = s.uid
            o.parentId = s.parentId
            o.rel = s.rel?.copyOf()
            o.spec = s.spec; o.gloss = s.gloss; o.emit = s.emit; o.opacity = s.opacity; o.pattern = s.pattern
            o.soft = s.soft; o.cone = s.cone; o.tint = s.tint.copyOf(); o.wind = s.wind; o.windDir = s.windDir
            return o
        }

        private fun swap(scene: Scene, old: SceneObject, nw: SceneObject) {
            val i = scene.objects.indexOf(old)
            if (i >= 0) scene.objects[i] = nw else scene.objects.add(nw)
            if (scene.selected === old) scene.selected = nw
        }
    }

    // ---------- building the point list ----------

    private fun begin(): Boolean {
        val ok = when (original.shape) {
            Shape.MODEL -> {
                val d = original.model?.data
                d != null && loadData(d, false)
            }
            Shape.CUBE -> loadData(Meshes.cubeData(), true)
            Shape.PYRAMID -> loadData(Meshes.pyramidData(), true)
            Shape.SPHERE -> makeSphere()
            else -> false
        }
        if (!ok || nPts > MAX_POINTS || nPts < 4) return false
        val md = save(nextKey(), toObj(pts)) ?: return false
        obj = copyOf(original, Shape.MODEL, md)
        swap(scene, original, obj)
        loadData(md.data, false)
        return true
    }

    private fun nextKey(): String {
        serial++
        return "pts_" + stamp + "_" + serial + ".obj"
    }

    private fun keyOf(x: Float, y: Float, z: Float): Long {
        val off = 524288L
        val qx = (Math.round(x * 10000f).toLong() + off).coerceIn(0L, 1048575L)
        val qy = (Math.round(y * 10000f).toLong() + off).coerceIn(0L, 1048575L)
        val qz = (Math.round(z * 10000f).toLong() + off).coerceIn(0L, 1048575L)
        return (qx shl 40) or (qy shl 20) or qz
    }

    /** Reads x y z nx ny nz triangles; points at the same place become one point. */
    private fun loadData(data: FloatArray, fixWinding: Boolean): Boolean {
        val verts = data.size / 6
        if (verts < 3) return false
        val map = HashMap<Long, Int>()
        val p = FloatArray(verts * 3)
        val t = IntArray(verts - verts % 3)
        var count = 0
        for (v in 0 until t.size) {
            val x = data[v * 6]; val y = data[v * 6 + 1]; val z = data[v * 6 + 2]
            val k = keyOf(x, y, z)
            var id = map[k]
            if (id == null) {
                id = count++
                map[k] = id
                p[id * 3] = x; p[id * 3 + 1] = y; p[id * 3 + 2] = z
            }
            t[v] = id
        }
        pts = p.copyOf(max(count * 3, 12))
        nPts = count
        tris = t
        sel = -1
        if (fixWinding) orient()
        return count > 0
    }

    /** Makes every triangle face away from the middle of the shape (only for convex basic shapes). */
    private fun orient() {
        var cx = 0f; var cy = 0f; var cz = 0f
        for (i in 0 until nPts) { cx += pts[i * 3]; cy += pts[i * 3 + 1]; cz += pts[i * 3 + 2] }
        cx /= nPts; cy /= nPts; cz /= nPts
        var t = 0
        while (t + 2 < tris.size) {
            val a = tris[t] * 3; val b = tris[t + 1] * 3; val c = tris[t + 2] * 3
            val ux = pts[b] - pts[a]; val uy = pts[b + 1] - pts[a + 1]; val uz = pts[b + 2] - pts[a + 2]
            val vx = pts[c] - pts[a]; val vy = pts[c + 1] - pts[a + 1]; val vz = pts[c + 2] - pts[a + 2]
            val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
            val mx = (pts[a] + pts[b] + pts[c]) / 3f - cx
            val my = (pts[a + 1] + pts[b + 1] + pts[c + 1]) / 3f - cy
            val mz = (pts[a + 2] + pts[b + 2] + pts[c + 2]) / 3f - cz
            if (nx * mx + ny * my + nz * mz < 0f) {
                val s = tris[t + 1]; tris[t + 1] = tris[t + 2]; tris[t + 2] = s
            }
            t += 3
        }
    }

    private fun makeSphere(): Boolean {
        val stacks = 6
        val slices = 10
        val total = 2 + (stacks - 1) * slices
        val p = FloatArray(total * 3)
        p[1] = 0.5f            // top point = 0
        p[4] = -0.5f           // bottom point = 1
        for (i in 1 until stacks) {
            val phi = PI.toFloat() * i / stacks
            for (j in 0 until slices) {
                val th = 2f * PI.toFloat() * j / slices
                val id = 2 + (i - 1) * slices + j
                p[id * 3] = 0.5f * sin(phi) * cos(th)
                p[id * 3 + 1] = 0.5f * cos(phi)
                p[id * 3 + 2] = 0.5f * sin(phi) * sin(th)
            }
        }
        val t = ArrayList<Int>()
        fun ring(i: Int, j: Int) = 2 + (i - 1) * slices + (j % slices)
        for (j in 0 until slices) {
            t.add(0); t.add(ring(1, j)); t.add(ring(1, j + 1))
            t.add(1); t.add(ring(stacks - 1, j + 1)); t.add(ring(stacks - 1, j))
        }
        for (i in 1 until stacks - 1) for (j in 0 until slices) {
            val a = ring(i, j); val b = ring(i, j + 1); val c = ring(i + 1, j); val d = ring(i + 1, j + 1)
            t.add(a); t.add(c); t.add(b)
            t.add(b); t.add(c); t.add(d)
        }
        pts = p
        nPts = total
        tris = t.toIntArray()
        sel = -1
        orient()
        return true
    }

    // ---------- the model text and data ----------

    private fun f(v: Float): String = String.format(Locale.US, "%.5f", v)

    private fun toObj(p: FloatArray): String {
        val sb = StringBuilder()
        for (i in 0 until nPts) sb.append("v ").append(f(p[i * 3])).append(' ').append(f(p[i * 3 + 1])).append(' ').append(f(p[i * 3 + 2])).append('\n')
        var t = 0
        while (t + 2 < tris.size) {
            val a = tris[t]; val b = tris[t + 1]; val c = tris[t + 2]
            if (a != b && b != c && a != c) sb.append("f ").append(a + 1).append(' ').append(b + 1).append(' ').append(c + 1).append('\n')
            t += 3
        }
        return sb.toString()
    }

    private fun fill(d: FloatArray) {
        var t = 0
        var o = 0
        while (t + 2 < tris.size && o + 17 < d.size) {
            val a = tris[t] * 3; val b = tris[t + 1] * 3; val c = tris[t + 2] * 3
            val ux = pts[b] - pts[a]; val uy = pts[b + 1] - pts[a + 1]; val uz = pts[b + 2] - pts[a + 2]
            val vx = pts[c] - pts[a]; val vy = pts[c + 1] - pts[a + 1]; val vz = pts[c + 2] - pts[a + 2]
            var nx = uy * vz - uz * vy; var ny = uz * vx - ux * vz; var nz = ux * vy - uy * vx
            val l = sqrt(nx * nx + ny * ny + nz * nz)
            if (l > 1e-8f) { nx /= l; ny /= l; nz /= l }
            for (k in 0 until 3) {
                val q = (if (k == 0) a else if (k == 1) b else c)
                d[o] = pts[q]; d[o + 1] = pts[q + 1]; d[o + 2] = pts[q + 2]
                d[o + 3] = nx; d[o + 4] = ny; d[o + 5] = nz
                o += 6
            }
            t += 3
        }
    }

    /** Shows the changed points on the object. */
    fun apply() {
        val md = obj.model ?: return
        if (md.data.size != (tris.size / 3) * 18) { rebuild(); return }
        synchronized(scene.lock) { fill(md.data) }
        renderer.refreshModel(md.key)
    }

    /** The number of triangles changed: make a new model. */
    private fun rebuild() {
        val old = obj.model?.key
        val md = save(nextKey(), toObj(pts)) ?: return
        val nw = copyOf(obj, Shape.MODEL, md)
        swap(scene, obj, nw)
        obj = nw
        loadData(md.data, false)
        if (old != null) renderer.refreshModel(old)
    }

    fun setPoint(i: Int, x: Float, y: Float, z: Float) {
        if (i < 0 || i >= nPts) return
        pts[i * 3] = x; pts[i * 3 + 1] = y; pts[i * 3 + 2] = z
    }

    // ---------- shape tools ----------

    /** Every triangle becomes four: more points to move. False when there would be too many. */
    fun subdivide(): Boolean {
        if (nPts + tris.size / 2 > MAX_POINTS) return false
        val mid = HashMap<Long, Int>()
        var p = pts.copyOf(max(pts.size, (nPts + tris.size / 2 + 4) * 3))
        var n = nPts
        fun midOf(a: Int, b: Int): Int {
            val k = if (a < b) a.toLong() * 1000003L + b else b.toLong() * 1000003L + a
            val have = mid[k]
            if (have != null) return have
            p[n * 3] = (p[a * 3] + p[b * 3]) / 2f
            p[n * 3 + 1] = (p[a * 3 + 1] + p[b * 3 + 1]) / 2f
            p[n * 3 + 2] = (p[a * 3 + 2] + p[b * 3 + 2]) / 2f
            mid[k] = n
            return n++
        }
        val nt = IntArray(tris.size * 4)
        var o = 0
        var t = 0
        while (t + 2 < tris.size) {
            val a = tris[t]; val b = tris[t + 1]; val c = tris[t + 2]
            val ab = midOf(a, b); val bc = midOf(b, c); val ca = midOf(c, a)
            nt[o++] = a; nt[o++] = ab; nt[o++] = ca
            nt[o++] = ab; nt[o++] = b; nt[o++] = bc
            nt[o++] = ca; nt[o++] = bc; nt[o++] = c
            nt[o++] = ab; nt[o++] = bc; nt[o++] = ca
            t += 3
        }
        pts = p
        nPts = n
        tris = nt
        sel = -1
        rebuild()
        return true
    }

    private fun bounds(): FloatArray {
        val b = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (i in 0 until nPts) for (k in 0 until 3) {
            b[k] = min(b[k], pts[i * 3 + k])
            b[3 + k] = max(b[3 + k], pts[i * 3 + k])
        }
        return b
    }

    /** Pulls every point towards a ball (smoother, rounder). */
    fun roundOff() {
        val b = bounds()
        val cx = (b[0] + b[3]) / 2f; val cy = (b[1] + b[4]) / 2f; val cz = (b[2] + b[5]) / 2f
        var rs = 0f
        for (i in 0 until nPts) {
            val dx = pts[i * 3] - cx; val dy = pts[i * 3 + 1] - cy; val dz = pts[i * 3 + 2] - cz
            rs += sqrt(dx * dx + dy * dy + dz * dz)
        }
        val r = rs / max(1, nPts)
        for (i in 0 until nPts) {
            val dx = pts[i * 3] - cx; val dy = pts[i * 3 + 1] - cy; val dz = pts[i * 3 + 2] - cz
            val l = sqrt(dx * dx + dy * dy + dz * dz)
            if (l < 1e-6f) continue
            val f = 0.4f
            pts[i * 3] = cx + dx * (1f - f) + dx / l * r * f
            pts[i * 3 + 1] = cy + dy * (1f - f) + dy / l * r * f
            pts[i * 3 + 2] = cz + dz * (1f - f) + dz / l * r * f
        }
        apply()
    }

    /** The top gets narrower than the bottom. */
    fun taper() {
        val b = bounds()
        val h = b[4] - b[1]
        if (h < 1e-6f) return
        val cx = (b[0] + b[3]) / 2f; val cz = (b[2] + b[5]) / 2f
        for (i in 0 until nPts) {
            val t = (pts[i * 3 + 1] - b[1]) / h
            val k = 1f - 0.3f * t
            pts[i * 3] = cx + (pts[i * 3] - cx) * k
            pts[i * 3 + 2] = cz + (pts[i * 3 + 2] - cz) * k
        }
        apply()
    }

    /** The top turns against the bottom. */
    fun twist() {
        val b = bounds()
        val h = b[4] - b[1]
        if (h < 1e-6f) return
        val cx = (b[0] + b[3]) / 2f; val cz = (b[2] + b[5]) / 2f
        for (i in 0 until nPts) {
            val t = (pts[i * 3 + 1] - b[1]) / h
            val a = (40f * t) * PI.toFloat() / 180f
            val dx = pts[i * 3] - cx; val dz = pts[i * 3 + 2] - cz
            pts[i * 3] = cx + dx * cos(a) - dz * sin(a)
            pts[i * 3 + 2] = cz + dx * sin(a) + dz * cos(a)
        }
        apply()
    }

    // ---------- finishing ----------

    /** Puts the original object back. */
    fun cancel(): SceneObject {
        val md = obj.model
        swap(scene, obj, original)
        if (md != null) renderer.refreshModel(md.key)
        return original
    }

    /**
     * Keeps the changes: the points are centred and fitted to a unit box again (the object's place and size are adjusted
     * so it looks exactly the same), and the model is saved. Returns the finished object, or null if saving failed.
     */
    fun finish(): SceneObject? {
        val b = bounds()
        val cx = (b[0] + b[3]) / 2f; val cy = (b[1] + b[4]) / 2f; val cz = (b[2] + b[5]) / 2f
        var ext = max(b[3] - b[0], max(b[4] - b[1], b[5] - b[2]))
        if (ext < 1e-4f) ext = 1f
        val np = FloatArray(nPts * 3)
        for (i in 0 until nPts) {
            np[i * 3] = (pts[i * 3] - cx) / ext
            np[i * 3 + 1] = (pts[i * 3 + 1] - cy) / ext
            np[i * 3 + 2] = (pts[i * 3 + 2] - cz) / ext
        }
        val md = save(nextKey(), toObj(np)) ?: return null
        val r = FloatArray(16)
        Matrix.setIdentityM(r, 0)
        Matrix.rotateM(r, 0, obj.ry, 0f, 1f, 0f)
        Matrix.rotateM(r, 0, obj.rx, 1f, 0f, 0f)
        Matrix.rotateM(r, 0, obj.rz, 0f, 0f, 1f)
        val out = FloatArray(4)
        Matrix.multiplyMV(out, 0, r, 0, floatArrayOf(obj.sx * cx, obj.sy * cy, obj.sz * cz, 1f), 0)
        val fin = copyOf(obj, Shape.MODEL, md)
        fin.x = obj.x + out[0]; fin.y = obj.y + out[1]; fin.z = obj.z + out[2]
        fin.sx = obj.sx * ext; fin.sy = obj.sy * ext; fin.sz = obj.sz * ext
        val old = obj.model?.key
        swap(scene, obj, fin)
        obj = fin
        if (old != null) renderer.refreshModel(old)
        if (fin.parentId != 0) Rig.rebind(scene, fin)
        return fin
    }
}

/**
 * Draws the points and edges of the object being edited on top of the 3D view, and lets you drag a point.
 * A touch that is not near a point goes through, so you can still look around.
 */
class PointsView(ctx: Context, private val renderer: SceneRenderer, private val pe: () -> PointEdit?) : View(ctx) {
    var onEdited: (() -> Unit)? = null

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val m = FloatArray(16)
    private val inv = FloatArray(16)
    private var sx = FloatArray(0)
    private var sy = FloatArray(0)
    private var ok = BooleanArray(0)
    private var dragging = false
    private var grabX = 0f
    private var grabY = 0f
    private var lastApply = 0L

    private fun modelMatrix(o: SceneObject) {
        Matrix.setIdentityM(m, 0)
        Matrix.translateM(m, 0, o.x, o.y, o.z)
        Matrix.rotateM(m, 0, o.ry, 0f, 1f, 0f)
        Matrix.rotateM(m, 0, o.rx, 1f, 0f, 0f)
        Matrix.rotateM(m, 0, o.rz, 0f, 0f, 1f)
        Matrix.scaleM(m, 0, o.sx, o.sy, o.sz)
    }

    private fun toScreen(e: PointEdit) {
        if (sx.size < e.nPts) { sx = FloatArray(e.nPts); sy = FloatArray(e.nPts); ok = BooleanArray(e.nPts) }
        modelMatrix(e.obj)
        val v = FloatArray(4)
        val inP = FloatArray(4)
        inP[3] = 1f
        for (i in 0 until e.nPts) {
            inP[0] = e.pts[i * 3]; inP[1] = e.pts[i * 3 + 1]; inP[2] = e.pts[i * 3 + 2]
            Matrix.multiplyMV(v, 0, m, 0, inP, 0)
            val s = renderer.project(v[0], v[1], v[2])
            if (s == null) { ok[i] = false } else { ok[i] = true; sx[i] = s[0]; sy[i] = s[1] }
        }
    }

    override fun onDraw(c: Canvas) {
        val e = pe() ?: return
        toScreen(e)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.5f
        p.color = 0x88FFFFFF.toInt()
        var t = 0
        while (t + 2 < e.tris.size) {
            val a = e.tris[t]; val b = e.tris[t + 1]; val d = e.tris[t + 2]
            if (a < e.nPts && b < e.nPts && d < e.nPts) {
                if (ok[a] && ok[b]) c.drawLine(sx[a], sy[a], sx[b], sy[b], p)
                if (ok[b] && ok[d]) c.drawLine(sx[b], sy[b], sx[d], sy[d], p)
                if (ok[d] && ok[a]) c.drawLine(sx[d], sy[d], sx[a], sy[a], p)
            }
            t += 3
        }
        p.style = Paint.Style.FILL
        for (i in 0 until e.nPts) {
            if (!ok[i]) continue
            if (i == e.sel) continue
            p.color = 0xFF202020.toInt()
            c.drawCircle(sx[i], sy[i], 8f, p)
            p.color = 0xFF7FD4FF.toInt()
            c.drawCircle(sx[i], sy[i], 6f, p)
        }
        if (e.sel in 0 until e.nPts && ok[e.sel]) {
            p.color = 0xFF202020.toInt()
            c.drawCircle(sx[e.sel], sy[e.sel], 14f, p)
            p.color = 0xFFFF9020.toInt()
            c.drawCircle(sx[e.sel], sy[e.sel], 11f, p)
        }
        postInvalidateDelayed(40)
    }

    private fun nearest(e: PointEdit, x: Float, y: Float): Int {
        var best = -1
        var bd = 44f * 44f
        for (i in 0 until e.nPts) {
            if (!ok[i]) continue
            val dx = sx[i] - x; val dy = sy[i] - y
            val d = dx * dx + dy * dy
            if (d < bd) { bd = d; best = i }
        }
        return best
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val e = pe() ?: return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                toScreen(e)
                val i = nearest(e, ev.x, ev.y)
                if (i < 0) return false          // not on a point: the 3D view gets the touch (look around)
                e.sel = i
                grabX = sx[i] - ev.x
                grabY = sy[i] - ev.y
                dragging = true
                invalidate()
                onEdited?.invoke()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging || ev.pointerCount > 1) return true
                moveSelected(e, ev.x + grabX, ev.y + grabY)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) { e.apply(); onEdited?.invoke() }
                dragging = false
                return true
            }
        }
        return true
    }

    /** Moves the selected point so that it is under the finger, on the plane through it that faces the camera. */
    private fun moveSelected(e: PointEdit, x: Float, y: Float) {
        val i = e.sel
        if (i < 0 || i >= e.nPts) return
        modelMatrix(e.obj)
        val cur = floatArrayOf(e.pts[i * 3], e.pts[i * 3 + 1], e.pts[i * 3 + 2], 1f)
        val w = FloatArray(4)
        Matrix.multiplyMV(w, 0, m, 0, cur, 0)
        val r = renderer.ray(x, y)
        val fx = renderer.fwdAt(0); val fy = renderer.fwdAt(1); val fz = renderer.fwdAt(2)
        val denom = r[3] * fx + r[4] * fy + r[5] * fz
        if (abs(denom) < 1e-5f) return
        val t = ((w[0] - r[0]) * fx + (w[1] - r[1]) * fy + (w[2] - r[2]) * fz) / denom
        if (t <= 0f) return
        val nw = floatArrayOf(r[0] + r[3] * t, r[1] + r[4] * t, r[2] + r[5] * t, 1f)
        if (!Matrix.invertM(inv, 0, m, 0)) return
        val loc = FloatArray(4)
        Matrix.multiplyMV(loc, 0, inv, 0, nw, 0)
        val nx = if (e.axis == 0 || e.axis == 1) loc[0] else cur[0]
        val ny = if (e.axis == 0 || e.axis == 2) loc[1] else cur[1]
        val nz = if (e.axis == 0 || e.axis == 3) loc[2] else cur[2]
        e.setPoint(i, nx, ny, nz)
        val now = System.currentTimeMillis()
        if (now - lastApply > 30L) {
            lastApply = now
            e.apply()
            onEdited?.invoke()
        }
        invalidate()
    }
}
