package com.example.minieditor

import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Water surface maths. The renderer's shader uses the same three waves, so scripts can float things on the real surface.
 * Water object: x/y/z = centre of the surface, sx/sz = size, sy = wave height, rx = wave speed, ry = wave direction (degrees).
 */
object Water {
    fun time(): Float = (SystemClock.uptimeMillis() % 3600000L) / 1000f

    fun height(o: SceneObject, x: Float, z: Float): Float {
        val dir = Math.toRadians(o.ry.toDouble()).toFloat()
        val c = cos(dir)
        val s = sin(dir)
        val qx = x * c - z * s
        val qz = x * s + z * c
        val t = time() * o.rx
        val h = 0.5f * sin(qx * 0.9f + t * 1.3f) +
            0.3f * sin(qz * 1.4f + t * 1.0f) +
            0.2f * sin((qx * 0.6f + qz * 0.8f) * 2.1f + t * 1.9f)
        return o.y + h * o.sy
    }

    /** Height of the highest water surface over (x, z), or null if there is no water there. */
    fun surfaceAt(scene: Scene, x: Float, z: Float): Float? {
        var best: Float? = null
        for (o in scene.objects) {
            if (o.shape != Shape.WATER) continue
            if (abs(x - o.x) > o.sx / 2f || abs(z - o.z) > o.sz / 2f) continue
            val h = height(o, x, z)
            best = if (best == null) h else maxOf(best, h)
        }
        return best
    }
}
