package com.example.minieditor

import android.util.Base64
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Terrain = a grid of heights (0..1) stored on the object. World size comes from the object's scale:
 * sx = width, sy = tallest height, sz = depth. The terrain is centred on x/z and sits on y.
 */
object TerrainGen {
    const val N = 48            // cells per side
    private const val V = N + 1  // vertices per side

    private fun hash(ix: Int, iy: Int, seed: Int): Float {
        var h = ix * 374761393 + iy * 668265263 + seed * 1442695041
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFF) / 65535f
    }

    private fun noise(x: Float, y: Float, seed: Int): Float {
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val fx = x - x0
        val fy = y - y0
        val sx = fx * fx * (3f - 2f * fx)
        val sy = fy * fy * (3f - 2f * fy)
        val a = hash(x0, y0, seed)
        val b = hash(x0 + 1, y0, seed)
        val c = hash(x0, y0 + 1, seed)
        val d = hash(x0 + 1, y0 + 1, seed)
        return a + (b - a) * sx + (c - a) * sy + (a - b - c + d) * sx * sy
    }

    /** Rolling hills. rough 0 = smooth, 1 = rugged. */
    fun generate(seed: Int, rough: Float): FloatArray {
        val out = FloatArray(V * V)
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (j in 0 until V) {
            for (i in 0 until V) {
                var amp = 1f
                var freq = 2.5f
                var v = 0f
                var norm = 0f
                for (o in 0 until 4) {
                    v += amp * noise(i.toFloat() / N * freq, j.toFloat() / N * freq, seed + o * 17)
                    norm += amp
                    amp *= 0.3f + rough * 0.4f
                    freq *= 2f
                }
                v /= norm
                out[j * V + i] = v
                lo = min(lo, v)
                hi = max(hi, v)
            }
        }
        val range = max(hi - lo, 0.0001f)
        for (k in out.indices) {
            val n = (out[k] - lo) / range
            out[k] = n * n * (3f - 2f * n) * 0.5f + n * 0.5f   // gentle S-curve: flatter valleys, clearer peaks
        }
        return out
    }

    fun flat(): FloatArray = FloatArray(V * V)

    /** World height of the terrain surface at (x, z), or null if outside the terrain. */
    fun heightAt(t: SceneObject, x: Float, z: Float): Float? {
        val hs = t.heights ?: return null
        if (hs.size != V * V) return null
        val u = (x - t.x) / t.sx + 0.5f
        val v = (z - t.z) / t.sz + 0.5f
        if (u < 0f || u > 1f || v < 0f || v > 1f) return null
        val gx = u * N
        val gz = v * N
        val i = min(gx.toInt(), N - 1)
        val j = min(gz.toInt(), N - 1)
        val fx = gx - i
        val fz = gz - j
        val h00 = hs[j * V + i]
        val h10 = hs[j * V + i + 1]
        val h01 = hs[(j + 1) * V + i]
        val h11 = hs[(j + 1) * V + i + 1]
        val h = h00 * (1 - fx) * (1 - fz) + h10 * fx * (1 - fz) + h01 * (1 - fx) * fz + h11 * fx * fz
        return t.y + h * t.sy
    }

    /** Triangles in world scale (x, y, z, nx, ny, nz), relative to the terrain's position. Same size every time. */
    fun buildMesh(o: SceneObject): FloatArray {
        val hs = o.heights ?: FloatArray(V * V)
        val out = FloatArray(N * N * 6 * 6)
        var p = 0
        val dx = o.sx / N
        val dz = o.sz / N
        fun h(i: Int, j: Int): Float = hs[j.coerceIn(0, N) * V + i.coerceIn(0, N)]
        fun put(i: Int, j: Int) {
            val x = (i.toFloat() / N - 0.5f) * o.sx
            val z = (j.toFloat() / N - 0.5f) * o.sz
            val y = h(i, j) * o.sy
            val nx = -(h(i + 1, j) - h(i - 1, j)) * o.sy / (2f * dx)
            val nz = -(h(i, j + 1) - h(i, j - 1)) * o.sy / (2f * dz)
            val l = sqrt(nx * nx + 1f + nz * nz)
            out[p++] = x; out[p++] = y; out[p++] = z
            out[p++] = nx / l; out[p++] = 1f / l; out[p++] = nz / l
        }
        for (j in 0 until N) {
            for (i in 0 until N) {
                put(i, j); put(i + 1, j); put(i + 1, j + 1)
                put(i, j); put(i + 1, j + 1); put(i, j + 1)
            }
        }
        return out
    }

    /** Distance along the ray [ox, oy, oz, dx, dy, dz] to the terrain surface, or null. */
    fun rayHit(o: SceneObject, r: FloatArray): Float? {
        if (o.heights == null) return null
        val lo = floatArrayOf(o.x - o.sx / 2f, o.y, o.z - o.sz / 2f)
        val hi = floatArrayOf(o.x + o.sx / 2f, o.y + o.sy, o.z + o.sz / 2f)
        var tmin = 0f
        var tmax = Float.MAX_VALUE
        for (a in 0..2) {
            val d = r[3 + a]
            val p = r[a]
            if (abs(d) < 1e-9f) {
                if (p < lo[a] || p > hi[a]) return null
            } else {
                var t1 = (lo[a] - p) / d
                var t2 = (hi[a] - p) / d
                if (t1 > t2) { val tmp = t1; t1 = t2; t2 = tmp }
                tmin = max(tmin, t1)
                tmax = min(tmax, t2)
                if (tmin > tmax) return null
            }
        }
        val step = max(min(o.sx, o.sz) / N * 0.5f, 0.02f)
        var t = tmin
        var prevT = t
        var prevAbove = true
        var first = true
        while (t <= tmax) {
            val px = r[0] + r[3] * t
            val py = r[1] + r[4] * t
            val pz = r[2] + r[5] * t
            val h = heightAt(o, px, pz)
            val above = h == null || py > h
            if (first) {
                if (!above) return t   // started inside the ground
                first = false
            } else if (prevAbove && !above) {
                var a = prevT
                var b = t
                for (k in 0 until 10) {
                    val m = (a + b) / 2f
                    val hh = heightAt(o, r[0] + r[3] * m, r[2] + r[5] * m)
                    if (hh == null || r[1] + r[4] * m > hh) a = m else b = m
                }
                return (a + b) / 2f
            }
            prevAbove = above
            prevT = t
            t += step
        }
        return null
    }

    /** Sculpt around world point (wx, wz). mode 1 raise, 2 lower, 3 smooth, 4 flatten. */
    fun brush(o: SceneObject, wx: Float, wz: Float, mode: Int, radius: Float, strength: Float) {
        val hs = o.heights ?: return
        val target = ((heightAt(o, wx, wz) ?: o.y) - o.y) / max(o.sy, 0.001f)
        for (j in 0 until V) {
            for (i in 0 until V) {
                val vx = o.x + (i.toFloat() / N - 0.5f) * o.sx
                val vz = o.z + (j.toFloat() / N - 0.5f) * o.sz
                val dx = vx - wx
                val dz = vz - wz
                val d = sqrt(dx * dx + dz * dz)
                if (d >= radius) continue
                val f = 1f - d / radius
                val w = f * f
                val k = j * V + i
                when (mode) {
                    1 -> hs[k] += strength * 0.05f * w
                    2 -> hs[k] -= strength * 0.05f * w
                    3 -> {
                        val a = hs[j.coerceIn(1, N - 1).let { (it - 1) * V } + i.coerceIn(0, N)]
                        val b = hs[j.coerceIn(1, N - 1).let { (it + 1) * V } + i.coerceIn(0, N)]
                        val c = hs[j.coerceIn(0, N) * V + (i.coerceIn(1, N - 1) - 1)]
                        val e = hs[j.coerceIn(0, N) * V + (i.coerceIn(1, N - 1) + 1)]
                        val avg = (a + b + c + e) / 4f
                        hs[k] += (avg - hs[k]) * min(1f, w * strength * 1.5f)
                    }
                    4 -> hs[k] += (target - hs[k]) * min(1f, w * strength * 1.5f)
                }
                hs[k] = hs[k].coerceIn(0f, 1f)
            }
        }
        o.hVersion++
    }

    fun encode(h: FloatArray): String {
        val b = ByteArray(h.size * 2)
        for (i in h.indices) {
            val v = (h[i].coerceIn(0f, 1f) * 65535f).toInt()
            b[2 * i] = (v and 0xFF).toByte()
            b[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return Base64.encodeToString(b, Base64.NO_WRAP)
    }

    fun decode(s: String): FloatArray? {
        val b = try { Base64.decode(s, Base64.DEFAULT) } catch (e: Exception) { return null }
        if (b.size != V * V * 2) return null
        return FloatArray(V * V) { i -> ((b[2 * i].toInt() and 0xFF) or ((b[2 * i + 1].toInt() and 0xFF) shl 8)) / 65535f }
    }
}
