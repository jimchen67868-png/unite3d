package com.example.minieditor

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Procedural trees and grass.
 * Tree mesh: 1 unit tall, base at y = 0, about 0.7 wide. type 0 = round tree, 1 = pine.
 * Grass: single-triangle blades in world coordinates. normal.x carries the blade height factor (0 base, 1 tip) for wind sway.
 */
object Veg {
    private fun cylinder(o: ArrayList<Float>, r: Float, y0: Float, y1: Float, seg: Int) {
        for (i in 0 until seg) {
            val a0 = 2.0 * Math.PI * i / seg
            val a1 = 2.0 * Math.PI * (i + 1) / seg
            val c0 = cos(a0).toFloat(); val s0 = sin(a0).toFloat()
            val c1 = cos(a1).toFloat(); val s1 = sin(a1).toFloat()
            fun v(c: Float, s: Float, y: Float) {
                o.add(r * c); o.add(y); o.add(r * s); o.add(c); o.add(0f); o.add(s)
            }
            v(c0, s0, y0); v(c1, s1, y0); v(c1, s1, y1)
            v(c0, s0, y0); v(c1, s1, y1); v(c0, s0, y1)
        }
    }

    private fun cone(o: ArrayList<Float>, y0: Float, y1: Float, r: Float, seg: Int) {
        val h = y1 - y0
        for (i in 0 until seg) {
            val a0 = 2.0 * Math.PI * i / seg
            val a1 = 2.0 * Math.PI * (i + 1) / seg
            val am = (a0 + a1) / 2.0
            fun n(a: Double, out: FloatArray) {
                val nx = cos(a).toFloat() * h
                val ny = r
                val nz = sin(a).toFloat() * h
                val l = sqrt(nx * nx + ny * ny + nz * nz)
                out[0] = nx / l; out[1] = ny / l; out[2] = nz / l
            }
            val n0 = FloatArray(3); val n1 = FloatArray(3); val nm = FloatArray(3)
            n(a0, n0); n(a1, n1); n(am, nm)
            o.add(r * cos(a0).toFloat()); o.add(y0); o.add(r * sin(a0).toFloat()); o.add(n0[0]); o.add(n0[1]); o.add(n0[2])
            o.add(r * cos(a1).toFloat()); o.add(y0); o.add(r * sin(a1).toFloat()); o.add(n1[0]); o.add(n1[1]); o.add(n1[2])
            o.add(0f); o.add(y1); o.add(0f); o.add(nm[0]); o.add(nm[1]); o.add(nm[2])
        }
    }

    fun trunk(type: Int): FloatArray {
        val o = ArrayList<Float>()
        if (type == 1) cylinder(o, 0.05f, 0f, 0.28f, 8) else cylinder(o, 0.055f, 0f, 0.42f, 8)
        return o.toFloatArray()
    }

    fun crown(type: Int): FloatArray {
        val o = ArrayList<Float>()
        if (type == 1) {
            cone(o, 0.18f, 0.56f, 0.34f, 10)
            cone(o, 0.38f, 0.76f, 0.26f, 10)
            cone(o, 0.58f, 0.97f, 0.17f, 10)
        } else {
            val d = Meshes.sphereData()
            var i = 0
            while (i < d.size) {
                o.add(d[i] * 0.68f); o.add(d[i + 1] * 0.68f + 0.66f); o.add(d[i + 2] * 0.68f)
                o.add(d[i + 3]); o.add(d[i + 4]); o.add(d[i + 5])
                i += 6
            }
        }
        return o.toFloatArray()
    }

    fun both(type: Int): FloatArray = trunk(type) + crown(type)

    /** Grass blades over the patch. [groundAt] returns the ground height at (x, z), e.g. the terrain surface. */
    fun grass(o: SceneObject, groundAt: (Float, Float) -> Float): FloatArray {
        val n = (o.sx * o.sz * 8f * o.rx).toInt().coerceIn(0, 9000)
        val rnd = Random(o.hVersion * 7919 + 17)
        val out = FloatArray(n * 18)
        var p = 0
        fun put(x: Float, y: Float, z: Float, tip: Float) {
            out[p++] = x; out[p++] = y; out[p++] = z
            out[p++] = tip; out[p++] = 1f; out[p++] = 0f
        }
        for (k in 0 until n) {
            val x = o.x + (rnd.nextFloat() - 0.5f) * o.sx
            val z = o.z + (rnd.nextFloat() - 0.5f) * o.sz
            val y = groundAt(x, z)
            val h = o.sy * (0.6f + rnd.nextFloat() * 0.7f)
            val a = rnd.nextFloat() * 6.2831853f
            val w = 0.08f + rnd.nextFloat() * 0.05f
            val dx = cos(a) * w
            val dz = sin(a) * w
            val lean = (rnd.nextFloat() - 0.5f) * 0.3f * h
            put(x - dx, y, z - dz, 0f)
            put(x + dx, y, z + dz, 0f)
            put(x + dz * 2f * lean, y + h, z - dx * 2f * lean, 1f)
        }
        return out
    }
}
