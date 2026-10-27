package com.example.minieditor

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Interleaved vertices: x y z nx ny nz */
class Mesh(data: FloatArray, private val mode: Int) {
    private val buf = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder())
        .asFloatBuffer().also { it.put(data); it.position(0) }
    val count = data.size / 6

    fun draw(posLoc: Int, normLoc: Int, first: Int = 0, n: Int = count) {
        buf.position(0)
        GLES20.glVertexAttribPointer(posLoc, 3, GLES20.GL_FLOAT, false, 24, buf)
        GLES20.glEnableVertexAttribArray(posLoc)
        buf.position(3)
        GLES20.glVertexAttribPointer(normLoc, 3, GLES20.GL_FLOAT, false, 24, buf)
        GLES20.glEnableVertexAttribArray(normLoc)
        GLES20.glDrawArrays(mode, first, n)
    }
}

object Meshes {
    private fun tri(o: ArrayList<Float>, a: FloatArray, b: FloatArray, c: FloatArray, inside: FloatArray) {
        val ux = b[0] - a[0]; val uy = b[1] - a[1]; val uz = b[2] - a[2]
        val vx = c[0] - a[0]; val vy = c[1] - a[1]; val vz = c[2] - a[2]
        var nx = uy * vz - uz * vy
        var ny = uz * vx - ux * vz
        var nz = ux * vy - uy * vx
        val len = sqrt(nx * nx + ny * ny + nz * nz)
        if (len > 0f) { nx /= len; ny /= len; nz /= len }
        val cx = (a[0] + b[0] + c[0]) / 3f - inside[0]
        val cy = (a[1] + b[1] + c[1]) / 3f - inside[1]
        val cz = (a[2] + b[2] + c[2]) / 3f - inside[2]
        if (nx * cx + ny * cy + nz * cz < 0f) { nx = -nx; ny = -ny; nz = -nz }
        for (p in arrayOf(a, b, c)) {
            o.add(p[0]); o.add(p[1]); o.add(p[2]); o.add(nx); o.add(ny); o.add(nz)
        }
    }

    private fun line(o: ArrayList<Float>, x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float) {
        o.addAll(listOf(x1, y1, z1, 0f, 0f, 0f, x2, y2, z2, 0f, 0f, 0f))
    }

    fun cubeData(): FloatArray {
        val o = ArrayList<Float>()
        val c = Array(8) { i ->
            floatArrayOf(
                if ((i and 1) == 0) -0.5f else 0.5f,
                if (((i shr 1) and 1) == 0) -0.5f else 0.5f,
                if (((i shr 2) and 1) == 0) -0.5f else 0.5f
            )
        }
        val origin = floatArrayOf(0f, 0f, 0f)
        val faces = arrayOf(
            intArrayOf(0, 4, 6, 2), intArrayOf(1, 3, 7, 5), intArrayOf(0, 1, 5, 4),
            intArrayOf(2, 6, 7, 3), intArrayOf(0, 2, 3, 1), intArrayOf(4, 5, 7, 6)
        )
        for (f in faces) {
            tri(o, c[f[0]], c[f[1]], c[f[2]], origin)
            tri(o, c[f[0]], c[f[2]], c[f[3]], origin)
        }
        return o.toFloatArray()
    }

    fun pyramidData(): FloatArray {
        val o = ArrayList<Float>()
        val p0 = floatArrayOf(-0.5f, -0.5f, -0.5f)
        val p1 = floatArrayOf(0.5f, -0.5f, -0.5f)
        val p2 = floatArrayOf(0.5f, -0.5f, 0.5f)
        val p3 = floatArrayOf(-0.5f, -0.5f, 0.5f)
        val apex = floatArrayOf(0f, 0.5f, 0f)
        val inside = floatArrayOf(0f, -0.25f, 0f)
        tri(o, p0, p1, p2, inside); tri(o, p0, p2, p3, inside)
        tri(o, p0, p1, apex, inside); tri(o, p1, p2, apex, inside)
        tri(o, p2, p3, apex, inside); tri(o, p3, p0, apex, inside)
        return o.toFloatArray()
    }

    fun sphereData(): FloatArray {
        val stacks = 16
        val slices = 24
        val o = ArrayList<Float>()
        fun v(i: Int, j: Int): FloatArray {
            val phi = PI * i / stacks
            val th = 2 * PI * j / slices
            return floatArrayOf((sin(phi) * cos(th)).toFloat(), cos(phi).toFloat(), (sin(phi) * sin(th)).toFloat())
        }
        fun push(p: FloatArray) {
            o.add(p[0] * 0.5f); o.add(p[1] * 0.5f); o.add(p[2] * 0.5f)
            o.add(p[0]); o.add(p[1]); o.add(p[2])
        }
        for (i in 0 until stacks) for (j in 0 until slices) {
            val a = v(i, j); val b = v(i + 1, j); val c = v(i + 1, j + 1); val d = v(i, j + 1)
            push(a); push(b); push(c); push(a); push(c); push(d)
        }
        return o.toFloatArray()
    }


    fun cube() = Mesh(cubeData(), GLES20.GL_TRIANGLES)
    fun pyramid() = Mesh(pyramidData(), GLES20.GL_TRIANGLES)
    fun sphere() = Mesh(sphereData(), GLES20.GL_TRIANGLES)

    fun dataFor(shape: Shape): FloatArray? = when (shape) {
        Shape.CUBE -> cubeData()
        Shape.PYRAMID -> pyramidData()
        Shape.SPHERE -> sphereData()
        Shape.MODEL -> null
    }

    /** Grid lines first; the last 4 vertices are the X axis and Z axis. */
    fun grid(): Mesh {
        val o = ArrayList<Float>()
        for (k in -10..10) {
            if (k == 0) continue
            line(o, -10f, 0f, k.toFloat(), 10f, 0f, k.toFloat())
            line(o, k.toFloat(), 0f, -10f, k.toFloat(), 0f, 10f)
        }
        line(o, -10f, 0f, 0f, 10f, 0f, 0f)
        line(o, 0f, 0f, -10f, 0f, 0f, 10f)
        return Mesh(o.toFloatArray(), GLES20.GL_LINES)
    }

    fun gizmo(): Mesh {
        val o = ArrayList<Float>()
        line(o, 0f, 0f, 0f, 1.2f, 0f, 0f)
        line(o, 0f, 0f, 0f, 0f, 1.2f, 0f)
        line(o, 0f, 0f, 0f, 0f, 0f, 1.2f)
        return Mesh(o.toFloatArray(), GLES20.GL_LINES)
    }
}
