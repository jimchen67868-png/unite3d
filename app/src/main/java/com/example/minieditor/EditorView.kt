package com.example.minieditor

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import kotlin.math.abs
import kotlin.math.sqrt

enum class Mode { MOVE, ROTATE, SCALE }

@SuppressLint("ClickableViewAccessibility")
class EditorView(ctx: Context, val scene: Scene) : GLSurfaceView(ctx) {
    val renderer = SceneRenderer(scene)
    var mode = Mode.MOVE
    var yLock = false
    var onChange: (() -> Unit)? = null
    var onTouchStart: (() -> Unit)? = null
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
    private var yAcc = 0f
    private var pending: SceneObject? = null      // object under the finger, not selected until we know it is not a pinch
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

    private fun pick(x: Float, y: Float): SceneObject? {
        val r = renderer.ray(x, y)
        var best: SceneObject? = null
        var bestT = Float.MAX_VALUE
        for (o in scene.objects) {
            if (o.shape.isScreen || o.shape == Shape.SETTINGS) continue
            val rad = if (o.shape.isLight) 0.4f else o.shape.radius * o.maxScale
            val ocx = r[0] - o.x; val ocy = r[1] - o.y; val ocz = r[2] - o.z
            val b = ocx * r[3] + ocy * r[4] + ocz * r[5]
            val c = ocx * ocx + ocy * ocy + ocz * ocz - rad * rad
            val disc = b * b - c
            if (disc < 0f) continue
            val sq = sqrt(disc)
            var t = -b - sq
            if (t < 0f) t = -b + sq
            if (t > 0f && t < bestT) { bestT = t; best = o }
        }
        return best
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
                // Only remember what is under the finger. Selecting or moving waits until
                // the finger moves or lifts, so a pinch that starts on an object does nothing to it.
                val hit = if (playing) null else pick(e.x, e.y)
                pending = hit
                if (hit != null) {
                    groundPoint(e.x, e.y, hit.y)?.let { offX = it[0] - hit.x; offZ = it[1] - hit.z }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (multi) return true
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
                        yAcc = p.y
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
                    scene.selected = pending   // tap: select the object, or deselect on empty space
                    refresh()
                }
                dragging = false; dragTarget = null; pending = null
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false; dragTarget = null; pending = null
            }
        }
        return true
    }

    /** Snaps a centre coordinate so the object's edges land on the grid. snap = 0 means off. */
    private fun snapV(v: Float, half: Float, snap: Float): Float =
        if (snap <= 0f) v else Math.round((v - half) / snap) * snap + half

    private fun transform(o: SceneObject, x: Float, y: Float, dx: Float, dy: Float) {
        when (mode) {
            Mode.MOVE -> {
                val snap = scene.objects.firstOrNull { it.shape == Shape.SETTINGS }?.rx ?: 0f
                val lit = o.shape.isLight
                if (yLock) {
                    yAcc -= dy * renderer.dist * 0.0025f
                    o.y = snapV(yAcc, if (lit) 0f else 0.5f * o.sy, snap)
                } else {
                    groundPoint(x, y, o.y)?.let {
                        o.x = snapV(it[0] - offX, if (lit) 0f else 0.5f * o.sx, snap)
                        o.z = snapV(it[1] - offZ, if (lit) 0f else 0.5f * o.sz, snap)
                    }
                }
            }
            Mode.ROTATE -> { o.ry += dx * 0.5f; o.rx += dy * 0.5f }
            Mode.SCALE -> {
                val f = 1f - dy * 0.005f
                if (o.shape.isLight) { o.sx = (o.sx * f).coerceIn(0.05f, 5f); refresh(); return }
                o.sx = (o.sx * f).coerceIn(0.05f, 20f)
                o.sy = (o.sy * f).coerceIn(0.05f, 20f)
                o.sz = (o.sz * f).coerceIn(0.05f, 20f)
            }
        }
        refresh()
    }
}
