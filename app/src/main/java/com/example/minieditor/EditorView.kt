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

    private var lastX = 0f; private var lastY = 0f
    private var downX = 0f; private var downY = 0f
    private var moved = false
    private var dragging = false
    private var multi = false
    private var offX = 0f; private var offZ = 0f

    private val scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            renderer.dist = (renderer.dist / d.scaleFactor).coerceIn(2f, 40f)
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
            val rad = o.shape.radius * o.scale
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

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        if (e.pointerCount > 1 || scaleDetector.isInProgress) {
            multi = true
            dragging = false
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                multi = false; moved = false
                lastX = e.x; lastY = e.y; downX = e.x; downY = e.y
                val hit = pick(e.x, e.y)
                if (hit != null) {
                    scene.selected = hit
                    dragging = true
                    groundPoint(e.x, e.y, hit.y)?.let { offX = it[0] - hit.x; offZ = it[1] - hit.z }
                    refresh()
                } else dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (multi) return true
                val dx = e.x - lastX
                val dy = e.y - lastY
                lastX = e.x; lastY = e.y
                if (abs(e.x - downX) > 12f || abs(e.y - downY) > 12f) moved = true
                val sel = scene.selected
                if (dragging && sel != null) {
                    transform(sel, e.x, e.y, dx, dy)
                } else {
                    renderer.yaw -= dx * 0.4f
                    renderer.pitch = (renderer.pitch + dy * 0.4f).coerceIn(5f, 85f)
                    requestRender()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!moved && !dragging && !multi) { scene.selected = null; refresh() }
                dragging = false
            }
        }
        return true
    }

    private fun transform(o: SceneObject, x: Float, y: Float, dx: Float, dy: Float) {
        when (mode) {
            Mode.MOVE -> if (yLock) {
                o.y -= dy * renderer.dist * 0.0025f
            } else {
                groundPoint(x, y, o.y)?.let { o.x = it[0] - offX; o.z = it[1] - offZ }
            }
            Mode.ROTATE -> { o.ry += dx * 0.5f; o.rx += dy * 0.5f }
            Mode.SCALE -> o.scale = (o.scale * (1f - dy * 0.005f)).coerceIn(0.1f, 10f)
        }
        refresh()
    }
}
