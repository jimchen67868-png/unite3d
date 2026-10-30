package com.example.minieditor

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import kotlin.math.abs
import kotlin.math.sqrt

enum class Mode { MOVE, ROTATE, SCALE }

@SuppressLint("ClickableViewAccessibility")
class EditorView(ctx: Context, val scene: Scene) : GLSurfaceView(ctx) {
    val renderer = SceneRenderer(scene)
    var mode = Mode.MOVE
    var axis = 0   // 0 = free, 1 = X, 2 = Y, 3 = Z
        set(v) { field = v; renderer.axisMode = v }
    var onChange: (() -> Unit)? = null
    var onTouchStart: (() -> Unit)? = null
    var sculpt = 0   // 0 off, 1 raise, 2 lower, 3 smooth, 4 flatten
    var brushRadius = 3f
    var brushStrength = 0.5f
    private var sculpting = false
    var playing: Boolean = false
        set(v) { field = v; renderer.playing = v }
    var touching = false
    var touchX = 0f
    var touchY = 0f

    private var lastX = 0f; private var lastY = 0f
    private var downX = 0f; private var downY = 0f
    private var moved = false
    private var dragging = false
    private var multi = false
    private var offX = 0f; private var offZ = 0f
    private var axT0 = 0f
    private val axStart = FloatArray(3)
    private var pending: SceneObject? = null      // object under the finger, not selected until we know it is not a pinch
    private var pendingList: List<SceneObject> = emptyList()
    private var lastTapX = -999f
    private var lastTapY = -999f
    private var lastTapTime = 0L
    private var dragTarget: SceneObject? = null
    private var dragStartTime = 0L
    private val startVals = FloatArray(9)

    private val scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            if (!renderer.fpMode) renderer.dist = (renderer.dist / d.scaleFactor).coerceIn(2f, 40f)
            requestRender()
            return true
        }
    })

    init {
        setEGLContextClientVersion(2)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun refresh() { requestRender(); onChange?.invoke() }

    private fun raySphere(r: FloatArray, cx: Float, cy: Float, cz: Float, rad: Float): Float? {
        val ox = r[0] - cx
        val oy = r[1] - cy
        val oz = r[2] - cz
        val b = ox * r[3] + oy * r[4] + oz * r[5]
        val c = ox * ox + oy * oy + oz * oz - rad * rad
        val disc = b * b - c
        if (disc < 0f) return null
        val sq = sqrt(disc)
        var t = -b - sq
        if (t < 0f) t = -b + sq
        return if (t > 0f) t else null
    }

    /** Ray vs the object's real (rotated, scaled) box or ellipsoid, with a little extra reach so small objects are easy to hit. */
    private fun hitT(o: SceneObject, r: FloatArray): Float? {
        val tol = renderer.dist * 0.02f
        if (o.shape == Shape.TERRAIN) return TerrainGen.rayHit(o, r)
        if (o.shape.isLight || o.shape == Shape.CAMERA) return raySphere(r, o.x, o.y, o.z, maxOf(0.4f, renderer.dist * 0.04f))
        val m = FloatArray(16)
        Matrix.setIdentityM(m, 0)
        Matrix.translateM(m, 0, o.x, o.y, o.z)
        Matrix.rotateM(m, 0, o.ry, 0f, 1f, 0f)
        Matrix.rotateM(m, 0, o.rx, 1f, 0f, 0f)
        Matrix.rotateM(m, 0, o.rz, 0f, 0f, 1f)
        Matrix.scaleM(m, 0, o.sx, o.sy, o.sz)
        val inv = FloatArray(16)
        if (!Matrix.invertM(inv, 0, m, 0)) return null
        val lo = FloatArray(4)
        val ld = FloatArray(4)
        Matrix.multiplyMV(lo, 0, inv, 0, floatArrayOf(r[0], r[1], r[2], 1f), 0)
        Matrix.multiplyMV(ld, 0, inv, 0, floatArrayOf(r[3], r[4], r[5], 0f), 0)
        if (o.shape == Shape.SPHERE) {
            val rad = 0.5f + tol / maxOf((o.sx + o.sy + o.sz) / 3f, 0.01f)
            val a = ld[0] * ld[0] + ld[1] * ld[1] + ld[2] * ld[2]
            val b = lo[0] * ld[0] + lo[1] * ld[1] + lo[2] * ld[2]
            val c = lo[0] * lo[0] + lo[1] * lo[1] + lo[2] * lo[2] - rad * rad
            val disc = b * b - a * c
            if (disc < 0f || a < 1e-12f) return null
            val sq = sqrt(disc)
            var t = (-b - sq) / a
            if (t < 0f) t = (-b + sq) / a
            return if (t > 0f) t else null
        }
        val half = floatArrayOf(
            0.5f + tol / maxOf(o.sx, 0.01f),
            0.5f + tol / maxOf(o.sy, 0.01f),
            0.5f + tol / maxOf(o.sz, 0.01f)
        )
        var tmin = -Float.MAX_VALUE
        var tmax = Float.MAX_VALUE
        for (i in 0..2) {
            val d = ld[i]
            val p = lo[i]
            if (abs(d) < 1e-9f) {
                if (p < -half[i] || p > half[i]) return null
            } else {
                var t1 = (-half[i] - p) / d
                var t2 = (half[i] - p) / d
                if (t1 > t2) { val tmp = t1; t1 = t2; t2 = tmp }
                tmin = maxOf(tmin, t1)
                tmax = minOf(tmax, t2)
                if (tmin > tmax) return null
            }
        }
        if (tmax < 0f) return null
        return if (tmin > 0f) tmin else 0f
    }

    /** Raises, lowers, smooths or flattens the selected terrain under the finger. */
    private fun applyBrush(x: Float, y: Float) {
        val o = scene.selected ?: return
        if (o.shape != Shape.TERRAIN) return
        val r = renderer.ray(x, y)
        val t = TerrainGen.rayHit(o, r) ?: return
        TerrainGen.brush(o, r[0] + r[3] * t, r[2] + r[5] * t, sculpt, brushRadius, brushStrength)
        refresh()
    }

    /** Everything under the finger, nearest first. */
    private fun pickAll(x: Float, y: Float): List<SceneObject> {
        val r = renderer.ray(x, y)
        val hits = ArrayList<Pair<Float, SceneObject>>()
        for (o in scene.objects) {
            if (o.shape.isScreen || o.shape == Shape.SETTINGS) continue
            val t = hitT(o, r) ?: continue
            hits.add(Pair(t, o))
        }
        hits.sortBy { it.first }
        return hits.map { it.second }
    }

    /** Intersect the touch ray with the horizontal plane y = planeY; returns (x, z). */
    private fun groundPoint(x: Float, y: Float, planeY: Float): FloatArray? {
        val r = renderer.ray(x, y)
        if (abs(r[4]) < 1e-5f) return null
        val t = (planeY - r[1]) / r[4]
        if (t < 0f) return null
        return floatArrayOf(r[0] + t * r[3], r[2] + t * r[5])
    }

    private fun saveVals(o: SceneObject) {
        startVals[0] = o.x; startVals[1] = o.y; startVals[2] = o.z
        startVals[3] = o.rx; startVals[4] = o.ry; startVals[5] = o.rz
        startVals[6] = o.sx; startVals[7] = o.sy; startVals[8] = o.sz
    }

    private fun restoreVals(o: SceneObject) {
        o.x = startVals[0]; o.y = startVals[1]; o.z = startVals[2]
        o.rx = startVals[3]; o.ry = startVals[4]; o.rz = startVals[5]
        o.sx = startVals[6]; o.sy = startVals[7]; o.sz = startVals[8]
    }

    /** A second finger arrived: this is a pinch, not an object touch. Undo any tiny accidental drag. */
    private fun cancelGesture(eventTime: Long) {
        val t = dragTarget
        if (dragging && t != null && eventTime - dragStartTime < 400) {
            restoreVals(t)
            refresh()
        }
        dragging = false
        dragTarget = null
        pending = null
        sculpting = false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        if (e.pointerCount > 1 || scaleDetector.isInProgress) {
            if (!multi) cancelGesture(e.eventTime)
            multi = true
            touching = false
            return true
        }
        touchX = e.x; touchY = e.y
        touching = e.actionMasked != MotionEvent.ACTION_UP && e.actionMasked != MotionEvent.ACTION_CANCEL
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                multi = false; moved = false; dragging = false; dragTarget = null
                onTouchStart?.invoke()
                lastX = e.x; lastY = e.y; downX = e.x; downY = e.y
                val selNow = scene.selected
                sculpting = !playing && sculpt > 0 && selNow != null && selNow.shape == Shape.TERRAIN
                if (sculpting) {
                    moved = true
                    pendingList = emptyList()
                    pending = null
                    applyBrush(e.x, e.y)
                    return true
                }
                // Only remember what is under the finger. Selecting or moving waits until
                // the finger moves or lifts, so a pinch that starts on an object does nothing to it.
                pendingList = if (playing) emptyList() else pickAll(e.x, e.y)
                val hit = pendingList.firstOrNull()
                pending = hit
                if (hit != null) {
                    groundPoint(e.x, e.y, hit.y)?.let { offX = it[0] - hit.x; offZ = it[1] - hit.z }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (multi) return true
                if (sculpting) { applyBrush(e.x, e.y); lastX = e.x; lastY = e.y; return true }
                val dx = e.x - lastX
                val dy = e.y - lastY
                lastX = e.x; lastY = e.y
                if (!moved && (abs(e.x - downX) > 12f || abs(e.y - downY) > 12f)) {
                    moved = true
                    val p = pending
                    if (p != null) {
                        scene.selected = p
                        dragTarget = p
                        dragging = true
                        dragStartTime = e.eventTime
                        saveVals(p)
                        axStart[0] = p.x; axStart[1] = p.y; axStart[2] = p.z
                        axT0 = axisParam(downX, downY, axis) ?: 0f
                        refresh()
                    }
                }
                val t = dragTarget
                if (dragging && t != null) {
                    if (moved) transform(t, e.x, e.y, dx, dy)
                } else if (moved) {
                    renderer.yaw -= dx * 0.4f
                    val fp = renderer.fpMode
                    renderer.pitch = (renderer.pitch + dy * 0.4f).coerceIn(if (fp) -80f else 5f, if (fp) 80f else 85f)
                    requestRender()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!moved && !multi && !playing) {
                    // tap: select the front-most object under the finger, or deselect on empty space.
                    // Tapping the same spot again steps to the next object underneath (floor, walls, hidden blocks...).
                    val list = pendingList
                    var choice: SceneObject? = list.firstOrNull()
                    val sel = scene.selected
                    if (list.size > 1 && sel != null &&
                        abs(e.x - lastTapX) < 48f && abs(e.y - lastTapY) < 48f && e.eventTime - lastTapTime < 1500
                    ) {
                        val idx = list.indexOf(sel)
                        if (idx >= 0) choice = list[(idx + 1) % list.size]
                    }
                    lastTapX = e.x
                    lastTapY = e.y
                    lastTapTime = e.eventTime
                    scene.selected = choice
                    refresh()
                }
                dragging = false; dragTarget = null; pending = null; sculpting = false
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false; dragTarget = null; pending = null; sculpting = false
            }
        }
        return true
    }

    /** Snaps a centre coordinate so the object's edges land on the grid. snap = 0 means off. */
    private fun snapV(v: Float, half: Float, snap: Float): Float =
        if (snap <= 0f) v else Math.round((v - half) / snap) * snap + half

    /** Distance along the chosen world axis (through axStart) of the point closest to the touch ray. */
    private fun axisParam(sx: Float, sy: Float, ax: Int): Float? {
        if (ax < 1 || ax > 3) return null
        val r = renderer.ray(sx, sy)
        val ux = if (ax == 1) 1f else 0f
        val uy = if (ax == 2) 1f else 0f
        val uz = if (ax == 3) 1f else 0f
        val wx = axStart[0] - r[0]
        val wy = axStart[1] - r[1]
        val wz = axStart[2] - r[2]
        val b = ux * r[3] + uy * r[4] + uz * r[5]
        val dd = ux * wx + uy * wy + uz * wz
        val e = r[3] * wx + r[4] * wy + r[5] * wz
        val den = 1f - b * b
        if (abs(den) < 1e-3f) return null
        return (b * e - dd) / den
    }

    private fun transform(o: SceneObject, x: Float, y: Float, dx: Float, dy: Float) {
        when (mode) {
            Mode.MOVE -> {
                val snap = scene.objects.firstOrNull { it.shape == Shape.SETTINGS }?.rx ?: 0f
                val lit = o.shape.isLight
                if (axis in 1..3) {
                    // move along exactly one world axis, following the finger
                    val t = axisParam(x, y, axis) ?: return
                    val delta = t - axT0
                    when (axis) {
                        1 -> o.x = snapV(axStart[0] + delta, if (lit) 0f else 0.5f * o.sx, snap)
                        2 -> o.y = snapV(axStart[1] + delta, if (lit) 0f else 0.5f * o.sy, snap)
                        else -> o.z = snapV(axStart[2] + delta, if (lit) 0f else 0.5f * o.sz, snap)
                    }
                } else {
                    groundPoint(x, y, o.y)?.let {
                        o.x = snapV(it[0] - offX, if (lit) 0f else 0.5f * o.sx, snap)
                        o.z = snapV(it[1] - offZ, if (lit) 0f else 0.5f * o.sz, snap)
                    }
                }
            }
            Mode.ROTATE -> when (axis) {
                1 -> o.rx += dy * 0.5f
                2 -> o.ry += dx * 0.5f
                3 -> o.rz += dx * 0.5f
                else -> { o.ry += dx * 0.5f; o.rx += dy * 0.5f }
            }
            Mode.SCALE -> {
                val f = 1f - dy * 0.005f
                if (o.shape.isLight) { o.sx = (o.sx * f).coerceIn(0.05f, 5f); refresh(); return }
                if (o.shape == Shape.CAMERA) { o.sx = (o.sx * f).coerceIn(20f, 120f); refresh(); return }
                when (axis) {
                    1 -> o.sx = (o.sx * f).coerceIn(0.05f, 20f)
                    2 -> o.sy = (o.sy * f).coerceIn(0.05f, 20f)
                    3 -> o.sz = (o.sz * f).coerceIn(0.05f, 20f)
                    else -> {
                        o.sx = (o.sx * f).coerceIn(0.05f, 20f)
                        o.sy = (o.sy * f).coerceIn(0.05f, 20f)
                        o.sz = (o.sz * f).coerceIn(0.05f, 20f)
                    }
                }
            }
        }
        refresh()
    }
}
