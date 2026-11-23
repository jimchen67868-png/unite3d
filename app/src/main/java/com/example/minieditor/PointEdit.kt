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
import kotlin.math.acos
import kotlin.math.tan
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
    var sel2 = -1                // second point of a selected line (-1 when a single point is selected)
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
            o.soft = s.soft; o.cone = s.cone; o.tint = s.tint.copyOf(); o.wind = s.wind; o.windDir = s.windDir; o.viewDist = s.viewDist
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
        sel2 = -1
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
        sel2 = -1
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
        sel2 = -1
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

    private fun nearestPoint(x: Float, y: Float, z: Float, not: Int): Int {
        var best = -1
        var bd = Float.MAX_VALUE
        for (i in 0 until nPts) {
            if (i == not) continue
            val dx = pts[i * 3] - x; val dy = pts[i * 3 + 1] - y; val dz = pts[i * 3 + 2] - z
            val d = dx * dx + dy * dy + dz * dz
            if (d < bd) { bd = d; best = i }
        }
        return best
    }

    private fun averageEdge(): Float {
        var sum = 0f
        var n = 0
        var t = 0
        while (t + 2 < tris.size) {
            for (k in 0 until 3) {
                val a = tris[t + k] * 3; val b = tris[t + (k + 1) % 3] * 3
                val dx = pts[a] - pts[b]; val dy = pts[a + 1] - pts[b + 1]; val dz = pts[a + 2] - pts[b + 2]
                sum += sqrt(dx * dx + dy * dy + dz * dz)
                n++
            }
            t += 3
        }
        return if (n == 0) 0f else sum / n
    }

    /**
     * Rounds the selected line (or the corner at the selected point): the points near it are smoothed, the rest stay.
     * More points are added first if the mesh is too coarse to round. Press again to round more.
     */
    fun roundSelected(): Boolean {
        if (sel !in 0 until nPts) return false
        val hasLine = sel2 in 0 until nPts
        val ax = pts[sel * 3]; val ay = pts[sel * 3 + 1]; val az = pts[sel * 3 + 2]
        val bx = if (hasLine) pts[sel2 * 3] else ax
        val by = if (hasLine) pts[sel2 * 3 + 1] else ay
        val bz = if (hasLine) pts[sel2 * 3 + 2] else az
        val bb = bounds()
        val ext = max(bb[3] - bb[0], max(bb[4] - bb[1], bb[5] - bb[2]))
        val len = sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay) + (bz - az) * (bz - az))
        val r = if (hasLine) (0.3f * len).coerceIn(0.08f * ext, 0.35f * ext) else 0.25f * ext
        var added = 0
        while (added < 3 && averageEdge() > r * 0.5f && nPts + tris.size / 2 <= MAX_POINTS) {
            if (!subdivide()) break
            added++
        }
        if (added > 0) {
            // the old points are still where they were: find the selected ones again
            sel = nearestPoint(ax, ay, az, -1)
            sel2 = if (hasLine) nearestPoint(bx, by, bz, sel) else -1
        }
        val nb = Array(nPts) { HashSet<Int>() }
        var t = 0
        while (t + 2 < tris.size) {
            for (k in 0 until 3) {
                val a = tris[t + k]; val b = tris[t + (k + 1) % 3]
                if (a != b) { nb[a].add(b); nb[b].add(a) }
            }
            t += 3
        }
        // how strongly each point is smoothed: 1 on the line, 0 at the distance r from it
        val w = FloatArray(nPts)
        val abx = bx - ax; val aby = by - ay; val abz = bz - az
        val l2 = abx * abx + aby * aby + abz * abz
        for (i in 0 until nPts) {
            val px = pts[i * 3] - ax; val py = pts[i * 3 + 1] - ay; val pz = pts[i * 3 + 2] - az
            var u = if (l2 < 1e-9f) 0f else (px * abx + py * aby + pz * abz) / l2
            u = u.coerceIn(0f, 1f)
            val dx = px - abx * u; val dy = py - aby * u; val dz = pz - abz * u
            val d = sqrt(dx * dx + dy * dy + dz * dz)
            w[i] = max(0f, 1f - d / r)
        }
        for (iter in 0 until 4) {
            val np = pts.copyOf()
            for (i in 0 until nPts) {
                if (w[i] <= 0f || nb[i].isEmpty()) continue
                var sx = 0f; var sy = 0f; var sz = 0f
                for (q in nb[i]) { sx += pts[q * 3]; sy += pts[q * 3 + 1]; sz += pts[q * 3 + 2] }
                val n = nb[i].size
                val k = 0.5f * w[i]
                np[i * 3] = pts[i * 3] + (sx / n - pts[i * 3]) * k
                np[i * 3 + 1] = pts[i * 3 + 1] + (sy / n - pts[i * 3 + 1]) * k
                np[i * 3 + 2] = pts[i * 3 + 2] + (sz / n - pts[i * 3 + 2]) * k
            }
            pts = np
        }
        apply()
        return true
    }

    // ---------- exact fillet (a round edge with a radius) ----------

    private fun sub3(a: FloatArray, b: FloatArray) = floatArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])
    private fun dot3(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun cross3(a: FloatArray, b: FloatArray) = floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    private fun len3(a: FloatArray) = sqrt(dot3(a, a))
    private fun unit3(a: FloatArray): FloatArray { val l = len3(a); return if (l < 1e-9f) floatArrayOf(0f, 0f, 0f) else floatArrayOf(a[0] / l, a[1] / l, a[2] / l) }
    private fun at3(a: FloatArray, b: FloatArray, k: Float) = floatArrayOf(a[0] + b[0] * k, a[1] + b[1] * k, a[2] + b[2] * k)
    private fun pt(i: Int) = floatArrayOf(pts[i * 3], pts[i * 3 + 1], pts[i * 3 + 2])
    private fun ek(a: Int, b: Int): Long = if (a < b) a.toLong() * 4194304L + b else b.toLong() * 4194304L + a

    /**
     * Rounds the selected line with an exact circular fillet of the given radius (in the object's own units, where the
     * object's biggest size is about 1). The line must be a straight corner between two flat faces. Returns null when
     * it worked, otherwise the reason it did not.
     */
    fun fillet(radius: Float): String? {
        if (sel !in 0 until nPts || sel2 !in 0 until nPts) return "Tap a line first"
        if (radius <= 0f) return "The radius must be above 0"
        val bb = bounds()
        val ext = max(bb[3] - bb[0], max(bb[4] - bb[1], bb[5] - bb[2]))
        val nT = tris.size / 3
        val nrm = Array(nT) { t ->
            unit3(cross3(sub3(pt(tris[t * 3 + 1]), pt(tris[t * 3])), sub3(pt(tris[t * 3 + 2]), pt(tris[t * 3]))))
        }
        val edgeTris = HashMap<Long, ArrayList<Int>>()
        for (t in 0 until nT) for (k in 0 until 3) {
            edgeTris.getOrPut(ek(tris[t * 3 + k], tris[t * 3 + (k + 1) % 3])) { ArrayList() }.add(t)
        }
        val pair = edgeTris[ek(sel, sel2)]
        if (pair == null || pair.size != 2) return "This line is not a corner between two faces"
        val tA = pair[0]; val tB = pair[1]

        // all triangles of a flat face (same plane) that touch each other
        fun region(seed: Int): HashSet<Int> {
            val set = HashSet<Int>()
            val st = ArrayList<Int>()
            set.add(seed); st.add(seed)
            val rn = nrm[seed]
            val p0 = pt(tris[seed * 3])
            while (st.isNotEmpty()) {
                val t = st.removeAt(st.size - 1)
                for (k in 0 until 3) {
                    val list = edgeTris[ek(tris[t * 3 + k], tris[t * 3 + (k + 1) % 3])] ?: continue
                    for (o in list) {
                        if (set.contains(o)) continue
                        if (dot3(nrm[o], rn) < 0.9999f) continue
                        if (abs(dot3(sub3(pt(tris[o * 3]), p0), rn)) > 0.002f * ext) continue
                        set.add(o); st.add(o)
                    }
                }
            }
            return set
        }
        val r1 = region(tA)
        if (r1.contains(tB)) return "This line is flat: there is no corner to round"
        val r2 = region(tB)

        // the outline of a face as an ordered list of points (it follows the triangle direction)
        fun loopOf(r: HashSet<Int>): IntArray? {
            val dir = HashSet<Long>()
            for (t in r) for (k in 0 until 3) dir.add(tris[t * 3 + k].toLong() * 4194304L + tris[t * 3 + (k + 1) % 3])
            val next = HashMap<Int, Int>()
            for (t in r) for (k in 0 until 3) {
                val a = tris[t * 3 + k]; val b = tris[t * 3 + (k + 1) % 3]
                if (!dir.contains(b.toLong() * 4194304L + a)) {
                    if (next.containsKey(a)) return null
                    next[a] = b
                }
            }
            if (next.size < 3) return null
            val start = next.keys.first()
            val out = ArrayList<Int>()
            var cur = start
            while (true) {
                out.add(cur)
                cur = next[cur] ?: return null
                if (cur == start) break
                if (out.size > next.size) return null
            }
            if (out.size != next.size) return null
            return out.toIntArray()
        }
        val l1 = loopOf(r1) ?: return "The first face is not a simple flat shape"
        val l2 = loopOf(r2) ?: return "The second face is not a simple flat shape"
        val n1c = l1.size; val n2c = l2.size

        // the straight run of points the two faces share
        val in2 = HashSet<Int>()
        for (v in l2) in2.add(v)
        val shared = BooleanArray(n1c) { in2.contains(l1[it]) }
        var s0 = -1
        for (i in 0 until n1c) if (shared[i] && !shared[(i + n1c - 1) % n1c]) { if (s0 >= 0) return "The two faces touch in more than one place"; s0 = i }
        if (s0 < 0) return "The two faces do not share an edge"
        val chain = ArrayList<Int>()
        var ci = s0
        while (shared[ci % n1c]) { chain.add(l1[ci % n1c]); ci++; if (chain.size > n1c) return "The two faces touch in more than one place" }
        val m = chain.size - 1
        if (m < 1) return "The shared edge is too short"
        if (!chain.contains(sel) || !chain.contains(sel2)) return "Select a line on the edge between two faces"
        val j0 = l2.indexOf(chain[m])
        if (j0 < 0) return "The faces do not match"
        for (q in 0..m) if (l2[(j0 + q) % n2c] != chain[m - q]) return "The faces do not share one straight edge"
        val wp1 = l1[(s0 + n1c - 1) % n1c]; val wn1 = l1[(s0 + m + 1) % n1c]
        val wp2 = l2[(j0 + n2c - 1) % n2c]; val wn2 = l2[(j0 + m + 1) % n2c]

        val v0 = pt(chain[0]); val vm = pt(chain[m])
        val u = unit3(sub3(vm, v0))
        if (len3(u) < 0.5f) return "The edge has no length"
        for (k in 0..m) {
            val d = sub3(pt(chain[k]), v0)
            val along = dot3(d, u)
            val off = sub3(d, floatArrayOf(u[0] * along, u[1] * along, u[2] * along))
            if (len3(off) > 0.002f * ext) return "The edge is not straight"
        }
        val n1 = nrm[tA]; val n2 = nrm[tB]
        // a corner that points outwards (not a dent)
        if (dot3(n1, sub3(pt(wn2), v0)) > -1e-4f * ext) return "This edge is a dent, not a corner (only corners can be rounded)"
        val alpha = acos(dot3(n1, n2).coerceIn(-1f, 1f))
        if (alpha < 0.05f || alpha > 3.0f) return "The angle at this edge is too flat or too sharp"
        val d = radius * tan(alpha / 2f)

        // directions across each face, away from the edge
        fun across(w: Int): FloatArray {
            val v = sub3(pt(w), v0)
            val along = dot3(v, u)
            return unit3(sub3(v, floatArrayOf(u[0] * along, u[1] * along, u[2] * along)))
        }
        val t1 = across(wp1)
        val t2 = across(wn2)
        if (len3(t1) < 0.5f || len3(t2) < 0.5f) return "The faces are not usable here"

        // the new points where the flat faces end and the round part begins
        val newPts = ArrayList<FloatArray>()
        fun pos(id: Int): FloatArray = if (id < nPts) pt(id) else newPts[id - nPts]
        fun addNew(p: FloatArray): Int { newPts.add(p); return nPts + newPts.size - 1 }
        val t1ids = IntArray(m + 1)
        val t2ids = IntArray(m + 1)
        for (k in 0..m) {
            val vk = pt(chain[k])
            var p1 = at3(vk, t1, d)
            var p2 = at3(vk, t2, d)
            if (k == 0 || k == m) {
                val w1 = if (k == 0) wp1 else wn1
                val w2 = if (k == 0) wn2 else wp2
                val e1 = sub3(pt(w1), vk); val e2 = sub3(pt(w2), vk)
                val den1 = dot3(e1, t1); val den2 = dot3(e2, t2)
                if (den1 < 1e-6f || den2 < 1e-6f) return "The radius does not fit at the end of the edge"
                val lam1 = d / den1; val lam2 = d / den2
                if (lam1 > 0.999f || lam2 > 0.999f) return "The radius is too big for this edge"
                p1 = at3(vk, e1, lam1)
                p2 = at3(vk, e2, lam2)
            }
            t1ids[k] = addNew(p1)
            t2ids[k] = addNew(p2)
        }

        // the round strip: n steps from the first face to the second
        val steps = ((alpha * 180f / PI.toFloat()) / 15f).toInt().coerceIn(3, 12)
        val sinA = sin(alpha)
        val dirs = Array(steps + 1) { i ->
            val phi = alpha * i / steps
            val a = sin(alpha - phi) / sinA
            val b = sin(phi) / sinA
            floatArrayOf(n1[0] * a + n2[0] * b, n1[1] * a + n2[1] * b, n1[2] * a + n2[2] * b)
        }
        val arc = Array(m + 1) { IntArray(steps + 1) }
        for (k in 0..m) {
            val p1 = pos(t1ids[k]); val p2 = pos(t2ids[k])
            val c = at3(p1, n1, -radius)
            val err = sub3(p2, at3(c, n2, radius))
            for (i in 0..steps) {
                arc[k][i] = when (i) {
                    0 -> t1ids[k]
                    steps -> t2ids[k]
                    else -> {
                        val f = i.toFloat() / steps
                        addNew(floatArrayOf(
                            c[0] + dirs[i][0] * radius + err[0] * f,
                            c[1] + dirs[i][1] * radius + err[1] * f,
                            c[2] + dirs[i][2] * radius + err[2] * f))
                    }
                }
            }
        }

        // the two faces again, as flat shapes with the new outline; they must stay a proper convex shape
        val chainIdx = HashMap<Int, Int>()
        for (k in 0..m) chainIdx[chain[k]] = k
        val nl1 = IntArray(n1c) { val k = chainIdx[l1[it]]; if (k != null) t1ids[k] else l1[it] }
        val nl2 = IntArray(n2c) { val k = chainIdx[l2[it]]; if (k != null) t2ids[k] else l2[it] }
        val out = ArrayList<Int>()
        fun fanFace(loop: IntArray, n: FloatArray): Boolean {
            val cnt = loop.size
            var apex = -1
            var areaSum = 0f
            for (i in 0 until cnt) {
                val a = pos(loop[(i + cnt - 1) % cnt]); val b = pos(loop[i]); val c = pos(loop[(i + 1) % cnt])
                val cr = dot3(cross3(sub3(b, a), sub3(c, b)), n)
                if (cr < -1e-7f * ext * ext) return false
                if (cr > 1e-6f * ext * ext && apex < 0) apex = i
            }
            if (apex < 0) return false
            for (i in 1 until cnt - 1) {
                val a = loop[apex]; val b = loop[(apex + i) % cnt]; val c = loop[(apex + i + 1) % cnt]
                areaSum += dot3(cross3(sub3(pos(b), pos(a)), sub3(pos(c), pos(a))), n)
                if (len3(cross3(sub3(pos(b), pos(a)), sub3(pos(c), pos(a)))) < 1e-9f) continue
                out.add(a); out.add(b); out.add(c)
            }
            return areaSum > 0f
        }
        if (!fanFace(nl1, n1)) return "The radius is too big for the first face"
        if (!fanFace(nl2, n2)) return "The radius is too big for the second face"

        // the strip along the edge
        fun addTri(a: Int, b: Int, c: Int, ref: FloatArray) {
            val nn = cross3(sub3(pos(b), pos(a)), sub3(pos(c), pos(a)))
            if (len3(nn) < 1e-10f) return
            if (dot3(nn, ref) >= 0f) { out.add(a); out.add(b); out.add(c) } else { out.add(a); out.add(c); out.add(b) }
        }
        for (k in 0 until m) for (i in 0 until steps) {
            val ref = floatArrayOf(dirs[i][0] + dirs[i + 1][0], dirs[i][1] + dirs[i + 1][1], dirs[i][2] + dirs[i + 1][2])
            addTri(arc[k][i], arc[k][i + 1], arc[k + 1][i + 1], ref)
            addTri(arc[k][i], arc[k + 1][i + 1], arc[k + 1][i], ref)
        }

        // the faces at the two ends of the edge get the round outline instead of the sharp corner
        val removed = HashSet<Int>()
        removed.addAll(r1); removed.addAll(r2)
        fun capEnd(v: Int, y0: Int, y1: Int, q: IntArray): Boolean {
            val fan = ArrayList<Int>()
            for (t in 0 until nT) {
                if (removed.contains(t)) continue
                if (tris[t * 3] == v || tris[t * 3 + 1] == v || tris[t * 3 + 2] == v) fan.add(t)
            }
            if (fan.isEmpty()) return false
            val link = HashMap<Int, Int>()
            for (t in fan) {
                var idx = 0
                for (k in 0 until 3) if (tris[t * 3 + k] == v) idx = k
                link[tris[t * 3 + (idx + 1) % 3]] = tris[t * 3 + (idx + 2) % 3]
            }
            fun walk(from: Int, to: Int): ArrayList<Int>? {
                val c = ArrayList<Int>()
                c.add(from)
                var cur = from
                while (cur != to) {
                    cur = link[cur] ?: return null
                    c.add(cur)
                    if (c.size > fan.size + 2) return null
                }
                return if (c.size == fan.size + 1) c else null
            }
            var y = walk(y0, y1)
            if (y == null) {
                val back = walk(y1, y0)
                if (back != null) { back.reverse(); y = back }
            }
            if (y == null) return false
            val ref = nrm[fan[0]]
            val mY = y.size - 1
            val nQ = q.size - 1
            var i = 0
            var j = 0
            while (i < nQ || j < mY) {
                val advanceY = j < mY && (i >= nQ || (j + 1f) / mY <= (i + 1f) / nQ)
                if (advanceY) { addTri(q[i], y[j], y[j + 1], ref); j++ } else { addTri(q[i], q[i + 1], y[j], ref); i++ }
            }
            for (t in fan) removed.add(t)
            return true
        }
        if (!capEnd(chain[0], wp1, wn2, arc[0])) return "The end of this edge is too complicated to round"
        if (!capEnd(chain[m], wn1, wp2, arc[m])) return "The end of this edge is too complicated to round"

        // put it all together
        val nt = ArrayList<Int>()
        for (t in 0 until nT) {
            if (removed.contains(t)) continue
            nt.add(tris[t * 3]); nt.add(tris[t * 3 + 1]); nt.add(tris[t * 3 + 2])
        }
        nt.addAll(out)
        val total = nPts + newPts.size
        if (total > MAX_POINTS + 400) return "Too many points"
        val np = pts.copyOf(max(pts.size, total * 3 + 3))
        for (i in newPts.indices) {
            np[(nPts + i) * 3] = newPts[i][0]; np[(nPts + i) * 3 + 1] = newPts[i][1]; np[(nPts + i) * 3 + 2] = newPts[i][2]
        }
        pts = np
        nPts = total
        tris = nt.toIntArray()
        sel = -1
        sel2 = -1
        rebuild()
        return null
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
        if (e.sel2 >= 0 && e.sel in 0 until e.nPts && e.sel2 < e.nPts && ok[e.sel] && ok[e.sel2]) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = 7f
            p.color = 0xFFFF9020.toInt()
            c.drawLine(sx[e.sel], sy[e.sel], sx[e.sel2], sy[e.sel2], p)
        }
        p.style = Paint.Style.FILL
        for (i in 0 until e.nPts) {
            if (!ok[i]) continue
            if (i == e.sel || i == e.sel2) continue
            p.color = 0xFF202020.toInt()
            c.drawCircle(sx[i], sy[i], 8f, p)
            p.color = 0xFF7FD4FF.toInt()
            c.drawCircle(sx[i], sy[i], 6f, p)
        }
        for (k in intArrayOf(e.sel, e.sel2)) {
            if (k in 0 until e.nPts && ok[k]) {
                p.color = 0xFF202020.toInt()
                c.drawCircle(sx[k], sy[k], 14f, p)
                p.color = 0xFFFF9020.toInt()
                c.drawCircle(sx[k], sy[k], 11f, p)
            }
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

    private fun segDist(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay
        val l2 = dx * dx + dy * dy
        var t = if (l2 < 1e-6f) 0f else ((px - ax) * dx + (py - ay) * dy) / l2
        t = t.coerceIn(0f, 1f)
        val cx = ax + t * dx - px; val cy = ay + t * dy - py
        return sqrt(cx * cx + cy * cy)
    }

    /** The line (two point numbers) closest to the touch, if it is near enough. */
    private fun nearestLine(e: PointEdit, x: Float, y: Float): IntArray? {
        var best = -1
        var ba = 0
        var bb = 0
        var bd = 34f
        var t = 0
        while (t + 2 < e.tris.size) {
            for (k in 0 until 3) {
                val a = e.tris[t + k]; val b = e.tris[t + (k + 1) % 3]
                if (a >= e.nPts || b >= e.nPts || a == b || !ok[a] || !ok[b]) continue
                val d = segDist(x, y, sx[a], sy[a], sx[b], sy[b])
                if (d < bd) { bd = d; best = 1; ba = a; bb = b }
            }
            t += 3
        }
        return if (best < 0) null else intArrayOf(ba, bb)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val e = pe() ?: return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                toScreen(e)
                val i = nearest(e, ev.x, ev.y)
                if (i >= 0) {
                    e.sel = i
                    e.sel2 = -1
                    grabX = sx[i] - ev.x
                    grabY = sy[i] - ev.y
                } else {
                    val ln = nearestLine(e, ev.x, ev.y)
                    if (ln == null) return false      // not on a point or a line: the 3D view gets the touch (look around)
                    e.sel = ln[0]
                    e.sel2 = ln[1]
                    grabX = (sx[ln[0]] + sx[ln[1]]) / 2f - ev.x
                    grabY = (sy[ln[0]] + sy[ln[1]]) / 2f - ev.y
                }
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

    /** Moves the selected point (or both ends of the selected line) so that it follows the finger, on the plane facing the camera. */
    private fun moveSelected(e: PointEdit, x: Float, y: Float) {
        val i = e.sel
        if (i < 0 || i >= e.nPts) return
        val j = if (e.sel2 in 0 until e.nPts) e.sel2 else -1
        modelMatrix(e.obj)
        // the spot that follows the finger: the point, or the middle of the line
        val cur = FloatArray(4)
        cur[3] = 1f
        for (k in 0 until 3) cur[k] = if (j < 0) e.pts[i * 3 + k] else (e.pts[i * 3 + k] + e.pts[j * 3 + k]) / 2f
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
        val dx = if (e.axis == 0 || e.axis == 1) loc[0] - cur[0] else 0f
        val dy = if (e.axis == 0 || e.axis == 2) loc[1] - cur[1] else 0f
        val dz = if (e.axis == 0 || e.axis == 3) loc[2] - cur[2] else 0f
        e.setPoint(i, e.pts[i * 3] + dx, e.pts[i * 3 + 1] + dy, e.pts[i * 3 + 2] + dz)
        if (j >= 0) e.setPoint(j, e.pts[j * 3] + dx, e.pts[j * 3 + 1] + dy, e.pts[j * 3 + 2] + dz)
        val now = System.currentTimeMillis()
        if (now - lastApply > 30L) {
            lastApply = now
            e.apply()
            onEdited?.invoke()
        }
        invalidate()
    }
}
