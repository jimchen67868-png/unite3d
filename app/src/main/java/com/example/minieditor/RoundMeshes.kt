package com.example.minieditor

import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Round shapes as .obj text (smooth sides, flat ends). They fit a box of about 1 x 1 x 1 and are scaled by the object. */
object RoundMeshes {
    private class Obj {
        val v = ArrayList<FloatArray>()
        val n = ArrayList<FloatArray>()
        val f = StringBuilder()

        fun addV(x: Float, y: Float, z: Float): Int { v.add(floatArrayOf(x, y, z)); return v.size }
        fun addN(x: Float, y: Float, z: Float): Int {
            val l = sqrt(x * x + y * y + z * z)
            n.add(if (l < 1e-9f) floatArrayOf(0f, 1f, 0f) else floatArrayOf(x / l, y / l, z / l))
            return n.size
        }

        /** A triangle; it is turned round when needed so that it faces the same way as its normals. */
        fun tri(a: Int, na: Int, b: Int, nb: Int, c: Int, nc: Int) {
            val pa = v[a - 1]; val pb = v[b - 1]; val pc = v[c - 1]
            val ux = pb[0] - pa[0]; val uy = pb[1] - pa[1]; val uz = pb[2] - pa[2]
            val wx = pc[0] - pa[0]; val wy = pc[1] - pa[1]; val wz = pc[2] - pa[2]
            val cx = uy * wz - uz * wy; val cy = uz * wx - ux * wz; val cz = ux * wy - uy * wx
            if (cx * cx + cy * cy + cz * cz < 1e-14f) return
            val s = n[na - 1]; val t = n[nb - 1]; val r = n[nc - 1]
            val d = cx * (s[0] + t[0] + r[0]) + cy * (s[1] + t[1] + r[1]) + cz * (s[2] + t[2] + r[2])
            if (d >= 0f) f.append("f ").append(a).append("//").append(na).append(' ').append(b).append("//").append(nb).append(' ').append(c).append("//").append(nc).append('\n')
            else f.append("f ").append(a).append("//").append(na).append(' ').append(c).append("//").append(nc).append(' ').append(b).append("//").append(nb).append('\n')
        }

        fun text(): String {
            val sb = StringBuilder()
            for (p in v) sb.append("v ").append(fmt(p[0])).append(' ').append(fmt(p[1])).append(' ').append(fmt(p[2])).append('\n')
            for (p in n) sb.append("vn ").append(fmt(p[0])).append(' ').append(fmt(p[1])).append(' ').append(fmt(p[2])).append('\n')
            sb.append(f)
            return sb.toString()
        }
    }

    private fun fmt(x: Float): String = String.format(Locale.US, "%.5f", x)

    private fun ang(i: Int, seg: Int): Float = 2f * PI.toFloat() * i / seg

    /** A cylinder standing on the y axis: radius 0.5, height 1. */
    fun cylinder(seg: Int = 40): String {
        val o = Obj()
        val up = o.addN(0f, 1f, 0f)
        val down = o.addN(0f, -1f, 0f)
        val ct = o.addV(0f, 0.5f, 0f)
        val cb = o.addV(0f, -0.5f, 0f)
        val rad = IntArray(seg) { o.addN(cos(ang(it, seg)), 0f, sin(ang(it, seg))) }
        val top = IntArray(seg) { o.addV(0.5f * cos(ang(it, seg)), 0.5f, 0.5f * sin(ang(it, seg))) }
        val bot = IntArray(seg) { o.addV(0.5f * cos(ang(it, seg)), -0.5f, 0.5f * sin(ang(it, seg))) }
        for (i in 0 until seg) {
            val j = (i + 1) % seg
            o.tri(bot[i], rad[i], top[i], rad[i], top[j], rad[j])
            o.tri(bot[i], rad[i], top[j], rad[j], bot[j], rad[j])
            o.tri(ct, up, top[j], up, top[i], up)
            o.tri(cb, down, bot[i], down, bot[j], down)
        }
        return o.text()
    }

    /** A cone with its point up: base radius 0.5, height 1. */
    fun cone(seg: Int = 40): String {
        val o = Obj()
        val down = o.addN(0f, -1f, 0f)
        val cb = o.addV(0f, -0.5f, 0f)
        val bot = IntArray(seg) { o.addV(0.5f * cos(ang(it, seg)), -0.5f, 0.5f * sin(ang(it, seg))) }
        // the side leans: normal = (cos, 0.5, sin) for a cone 0.5 wide at the base and 1 high
        val slant = IntArray(seg) { o.addN(cos(ang(it, seg)), 0.5f, sin(ang(it, seg))) }
        for (i in 0 until seg) {
            val j = (i + 1) % seg
            val mid = (ang(i, seg) + ang(i, seg) + 2f * PI.toFloat() / seg) / 2f
            val nm = o.addN(cos(mid), 0.5f, sin(mid))
            val apex = o.addV(0f, 0.5f, 0f)
            o.tri(apex, nm, bot[i], slant[i], bot[j], slant[j])
            o.tri(cb, down, bot[i], down, bot[j], down)
        }
        return o.text()
    }

    /** The top half of a ball (flat underneath): 1 wide, 0.5 high. */
    fun dome(stacks: Int = 12, slices: Int = 40): String {
        val o = Obj()
        val down = o.addN(0f, -1f, 0f)
        val cb = o.addV(0f, 0f, 0f)
        val top = o.addV(0f, 0.5f, 0f)
        val topN = o.addN(0f, 1f, 0f)
        val rings = Array(stacks) { s ->
            val phi = (PI.toFloat() / 2f) * (s + 1) / stacks      // from the top down to the rim
            val y = 0.5f * cos(phi)
            val r = 0.5f * sin(phi)
            IntArray(slices) { j -> o.addV(r * cos(ang(j, slices)), y, r * sin(ang(j, slices))) }
        }
        val ringN = Array(stacks) { s ->
            val phi = (PI.toFloat() / 2f) * (s + 1) / stacks
            IntArray(slices) { j -> o.addN(sin(phi) * cos(ang(j, slices)), cos(phi), sin(phi) * sin(ang(j, slices))) }
        }
        for (j in 0 until slices) {
            val k = (j + 1) % slices
            o.tri(top, topN, rings[0][j], ringN[0][j], rings[0][k], ringN[0][k])
        }
        for (s in 0 until stacks - 1) for (j in 0 until slices) {
            val k = (j + 1) % slices
            o.tri(rings[s][j], ringN[s][j], rings[s + 1][j], ringN[s + 1][j], rings[s + 1][k], ringN[s + 1][k])
            o.tri(rings[s][j], ringN[s][j], rings[s + 1][k], ringN[s + 1][k], rings[s][k], ringN[s][k])
        }
        val last = stacks - 1
        for (j in 0 until slices) {
            val k = (j + 1) % slices
            o.tri(cb, down, rings[last][j], down, rings[last][k], down)
        }
        return o.text()
    }

    /** A ring (donut) lying flat: 1 wide, 0.3 high. */
    fun torus(segs: Int = 32, tube: Int = 14): String {
        val o = Obj()
        val big = 0.35f
        val small = 0.15f
        val p = Array(segs) { i ->
            IntArray(tube) { j ->
                val th = ang(i, segs); val ph = ang(j, tube)
                o.addV((big + small * cos(ph)) * cos(th), small * sin(ph), (big + small * cos(ph)) * sin(th))
            }
        }
        val nn = Array(segs) { i ->
            IntArray(tube) { j ->
                val th = ang(i, segs); val ph = ang(j, tube)
                o.addN(cos(ph) * cos(th), sin(ph), cos(ph) * sin(th))
            }
        }
        for (i in 0 until segs) for (j in 0 until tube) {
            val i2 = (i + 1) % segs; val j2 = (j + 1) % tube
            o.tri(p[i][j], nn[i][j], p[i2][j], nn[i2][j], p[i2][j2], nn[i2][j2])
            o.tri(p[i][j], nn[i][j], p[i2][j2], nn[i2][j2], p[i][j2], nn[i][j2])
        }
        return o.text()
    }
}
