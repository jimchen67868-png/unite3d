package com.example.minieditor

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Procedural trees and grass.
 * Tree mesh: about 1 unit tall, base at y = 0, about 0.8 wide. type 0 = broadleaf tree, 1 = pine.
 * Leaves, bark and shading detail come from the fragment shader (noise), so the geometry stays light.
 * Grass: single-triangle blades in world coordinates. normal.x carries the blade height factor (0 base, 1 tip) for wind sway.
 */
object Veg {
    private fun hash(x: Float, y: Float, z: Float): Float {
        val s = sin(x * 12.9898f + y * 78.233f + z * 37.719f) * 43758.5453f
        return s - floor(s)
    }

    private fun addV(o: ArrayList<Float>, x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float) {
        o.add(x); o.add(y); o.add(z); o.add(nx); o.add(ny); o.add(nz)
    }

    /** Cone-like tube that narrows from r0 at y0 to r1 at y1. */
    private fun taper(o: ArrayList<Float>, r0: Float, r1: Float, y0: Float, y1: Float, seg: Int) {
        for (i in 0 until seg) {
            val a0 = 2.0 * Math.PI * i / seg
            val a1 = 2.0 * Math.PI * (i + 1) / seg
            val c0 = cos(a0).toFloat(); val s0 = sin(a0).toFloat()
            val c1 = cos(a1).toFloat(); val s1 = sin(a1).toFloat()
            addV(o, r0 * c0, y0, r0 * s0, c0, 0f, s0)
            addV(o, r0 * c1, y0, r0 * s1, c1, 0f, s1)
            addV(o, r1 * c1, y1, r1 * s1, c1, 0f, s1)
            addV(o, r0 * c0, y0, r0 * s0, c0, 0f, s0)
            addV(o, r1 * c1, y1, r1 * s1, c1, 0f, s1)
            addV(o, r1 * c0, y1, r1 * s0, c0, 0f, s0)
        }
    }

    /** A tube between two points (a branch). */
    private fun limb(
        o: ArrayList<Float>, x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float,
        r0: Float, r1: Float, seg: Int
    ) {
        var dx = x1 - x0
        var dy = y1 - y0
        var dz = z1 - z0
        val l = sqrt(dx * dx + dy * dy + dz * dz)
        if (l < 1e-5f) return
        dx /= l; dy /= l; dz /= l
        // any vector not parallel to the branch
        val hx = if (abs(dy) < 0.9f) 0f else 1f
        val hy = if (abs(dy) < 0.9f) 1f else 0f
        var ux = dy * 0f - dz * hy
        var uy = dz * hx - dx * 0f
        var uz = dx * hy - dy * hx
        val ul = sqrt(ux * ux + uy * uy + uz * uz)
        ux /= ul; uy /= ul; uz /= ul
        val wx = dy * uz - dz * uy
        val wy = dz * ux - dx * uz
        val wz = dx * uy - dy * ux
        fun ring(i: Int, r: Float, px: Float, py: Float, pz: Float, out: FloatArray) {
            val a = 2.0 * Math.PI * i / seg
            val c = cos(a).toFloat()
            val s = sin(a).toFloat()
            val nx = c * ux + s * wx
            val ny = c * uy + s * wy
            val nz = c * uz + s * wz
            out[0] = px + nx * r; out[1] = py + ny * r; out[2] = pz + nz * r
            out[3] = nx; out[4] = ny; out[5] = nz
        }
        val a0 = FloatArray(6); val a1 = FloatArray(6); val b0 = FloatArray(6); val b1 = FloatArray(6)
        for (i in 0 until seg) {
            ring(i, r0, x0, y0, z0, a0); ring(i + 1, r0, x0, y0, z0, a1)
            ring(i, r1, x1, y1, z1, b0); ring(i + 1, r1, x1, y1, z1, b1)
            for (q in arrayOf(a0, a1, b1, a0, b1, b0)) addV(o, q[0], q[1], q[2], q[3], q[4], q[5])
        }
    }

    /** A lumpy ball: a sphere whose surface is pushed in and out so it does not look perfectly round. */
    private fun lump(o: ArrayList<Float>, cx: Float, cy: Float, cz: Float, r: Float, bump: Float) {
        val d = Meshes.sphereData()
        var i = 0
        while (i < d.size) {
            val nx = d[i + 3]; val ny = d[i + 4]; val nz = d[i + 5]
            // smooth function of the surface direction, so copies of the same vertex (sphere seams, poles) always agree
            val n = (sin(nx * 6.1f + cy * 9f) * sin(ny * 5.3f + cx * 7f + 1.3f) + 0.5f * sin(nz * 7.7f + cz * 5f + cx * 3f)) * bump * 0.8f
            val rr = r + n
            addV(o, cx + nx * rr, cy + ny * rr, cz + nz * rr, nx, ny, nz)
            i += 6
        }
    }

    /** One flat-shaded triangle with a normal that points away from the trunk. */
    private fun faceOut(o: ArrayList<Float>, a: FloatArray, b: FloatArray, c: FloatArray) {
        val ux = b[0] - a[0]; val uy = b[1] - a[1]; val uz = b[2] - a[2]
        val vx = c[0] - a[0]; val vy = c[1] - a[1]; val vz = c[2] - a[2]
        var nx = uy * vz - uz * vy
        var ny = uz * vx - ux * vz
        var nz = ux * vy - uy * vx
        val l = sqrt(nx * nx + ny * ny + nz * nz)
        if (l > 0f) { nx /= l; ny /= l; nz /= l }
        val mx = (a[0] + b[0] + c[0]) / 3f
        val mz = (a[2] + b[2] + c[2]) / 3f
        if (nx * mx + nz * mz < 0f) { nx = -nx; ny = -ny; nz = -nz }
        addV(o, a[0], a[1], a[2], nx, ny, nz)
        addV(o, b[0], b[1], b[2], nx, ny, nz)
        addV(o, c[0], c[1], c[2], nx, ny, nz)
    }

    fun trunk(type: Int): FloatArray {
        val o = ArrayList<Float>()
        if (type == 1) {
            taper(o, 0.065f, 0.05f, 0f, 0.06f, 8)       // root flare
            taper(o, 0.05f, 0.016f, 0.06f, 0.96f, 8)
        } else {
            taper(o, 0.105f, 0.075f, 0f, 0.07f, 9)      // root flare
            taper(o, 0.075f, 0.04f, 0.07f, 0.56f, 9)
            limb(o, 0f, 0.4f, 0f, 0.17f, 0.64f, 0.05f, 0.03f, 0.014f, 6)
            limb(o, 0f, 0.46f, 0f, -0.16f, 0.7f, -0.07f, 0.028f, 0.012f, 6)
            limb(o, 0f, 0.5f, 0f, 0.03f, 0.78f, -0.16f, 0.025f, 0.012f, 6)
        }
        return o.toFloatArray()
    }

    fun crown(type: Int): FloatArray {
        val o = ArrayList<Float>()
        if (type == 1) {
            // six tiers of ragged, drooping branches
            val tiers = 6
            for (k in 0 until tiers) {
                val y0 = 0.14f + k * 0.125f
                val y1 = y0 + 0.3f
                val rad = 0.37f * (1f - k * 0.145f)
                val pts = 14
                val ring = Array(pts) { FloatArray(3) }
                for (i in 0 until pts) {
                    val a = 2.0 * Math.PI * i / pts + k * 0.4
                    val rr = if (i % 2 == 0) rad else rad * (0.72f + 0.1f * hash(i.toFloat(), k.toFloat(), 1f))
                    val droop = if (i % 2 == 0) -0.03f else 0f
                    ring[i][0] = rr * cos(a).toFloat()
                    ring[i][1] = y0 + droop
                    ring[i][2] = rr * sin(a).toFloat()
                }
                val apex = floatArrayOf(0f, y1, 0f)
                for (i in 0 until pts) faceOut(o, ring[i], ring[(i + 1) % pts], apex)
            }
        } else {
            lump(o, 0f, 0.62f, 0f, 0.27f, 0.07f)
            lump(o, 0.2f, 0.71f, 0.07f, 0.2f, 0.06f)
            lump(o, -0.19f, 0.7f, -0.09f, 0.21f, 0.06f)
            lump(o, 0.05f, 0.86f, -0.13f, 0.19f, 0.06f)
            lump(o, -0.07f, 0.85f, 0.15f, 0.18f, 0.06f)
            lump(o, 0.14f, 0.55f, -0.18f, 0.16f, 0.05f)
            lump(o, -0.15f, 0.52f, 0.13f, 0.15f, 0.05f)
            lump(o, 0.02f, 0.97f, 0.01f, 0.15f, 0.05f)
        }
        return o.toFloatArray()
    }

    fun both(type: Int): FloatArray = trunk(type) + crown(type)

    /**
     * Grass in small clumps of thin, slightly bent blades (short, like ground cover in a shooter).
     * [groundAt] returns the ground height at (x, z), or null where no grass should grow (e.g. under water).
     * Density rx: 1 = about 1.6 clumps per square unit, 4 blades per clump.
     */
    fun grass(o: SceneObject, groundAt: (Float, Float) -> Float?): FloatArray {
        val clumps = (o.sx * o.sz * 1.6f * o.rx).toInt().coerceIn(0, 3000)
        val rnd = Random(o.hVersion * 7919 + 17)
        val out = FloatArray(clumps * 4 * 9 * 6)
        var p = 0
        fun put(x: Float, y: Float, z: Float, tip: Float, rv: Float) {
            out[p++] = x; out[p++] = y; out[p++] = z
            out[p++] = tip; out[p++] = 1f; out[p++] = rv
        }
        for (k in 0 until clumps) {
            val cx = o.x + (rnd.nextFloat() - 0.5f) * o.sx
            val cz = o.z + (rnd.nextFloat() - 0.5f) * o.sz
            val y = groundAt(cx, cz) ?: continue
            val clumpH = o.sy * (0.55f + rnd.nextFloat() * 0.6f)
            val rv = rnd.nextFloat()
            for (b in 0 until 4) {
                val ang = rnd.nextFloat() * 6.2831853f
                val off = 0.03f + rnd.nextFloat() * 0.05f
                val bx = cx + cos(ang) * off
                val bz = cz + sin(ang) * off
                val h = clumpH * (0.7f + rnd.nextFloat() * 0.5f)
                val face = rnd.nextFloat() * 6.2831853f
                val hw = 0.016f + rnd.nextFloat() * 0.012f      // half width: very thin
                val dx = cos(face) * hw
                val dz = sin(face) * hw
                val lean = h * (0.25f + rnd.nextFloat() * 0.25f)  // blades lean outward from the clump
                val lx = cos(ang) * lean
                val lz = sin(ang) * lean
                val my = y + h * 0.5f
                val mx = bx + lx * 0.35f
                val mz = bz + lz * 0.35f
                val tx = bx + lx
                val tz = bz + lz
                val ty = y + h * 0.95f
                // lower half: a narrow quad, upper half: a pointed tip
                put(bx - dx, y, bz - dz, 0f, rv); put(bx + dx, y, bz + dz, 0f, rv); put(mx - dx * 0.7f, my, mz - dz * 0.7f, 0.5f, rv)
                put(bx + dx, y, bz + dz, 0f, rv); put(mx + dx * 0.7f, my, mz + dz * 0.7f, 0.5f, rv); put(mx - dx * 0.7f, my, mz - dz * 0.7f, 0.5f, rv)
                put(mx - dx * 0.7f, my, mz - dz * 0.7f, 0.5f, rv); put(mx + dx * 0.7f, my, mz + dz * 0.7f, 0.5f, rv); put(tx, ty, tz, 1f, rv)
            }
        }
        return out.copyOf(p)
    }
}
