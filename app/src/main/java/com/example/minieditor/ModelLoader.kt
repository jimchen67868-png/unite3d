package com.example.minieditor

import android.opengl.Matrix
import android.util.Base64
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.sqrt

internal class FloatList {
    var a = FloatArray(4096)
    var n = 0
    fun add(v: Float) { if (n == a.size) a = a.copyOf(n * 2); a[n++] = v }
    operator fun get(i: Int): Float {
        if (i < 0 || i >= n) throw IndexOutOfBoundsException("Bad index in model file")
        return a[i]
    }
    val size: Int get() = n
    fun toArray(): FloatArray = a.copyOf(n)
}

internal class Doc(val json: JSONObject, val buffers: List<ByteArray?>)

/** Loads .obj, .glb and .gltf (embedded buffers) into interleaved x y z nx ny nz triangles, fitted to a unit box. */
object ModelLoader {
    fun load(fileName: String, bytes: ByteArray): FloatArray {
        val lower = fileName.lowercase()
        val raw = when {
            lower.endsWith(".obj") -> parseObj(String(bytes))
            lower.endsWith(".glb") || lower.endsWith(".gltf") -> parseGltf(bytes)
            else -> throw IllegalArgumentException("Unsupported file type (use .obj, .glb or .gltf)")
        }
        if (raw.isEmpty()) throw IllegalArgumentException("No triangles found")
        return fitToUnitBox(raw)
    }

    private fun fitToUnitBox(d: FloatArray): FloatArray {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        var i = 0
        while (i < d.size) {
            minX = minOf(minX, d[i]); maxX = maxOf(maxX, d[i])
            minY = minOf(minY, d[i + 1]); maxY = maxOf(maxY, d[i + 1])
            minZ = minOf(minZ, d[i + 2]); maxZ = maxOf(maxZ, d[i + 2])
            i += 6
        }
        val cx = (minX + maxX) / 2; val cy = (minY + maxY) / 2; val cz = (minZ + maxZ) / 2
        val ext = max(max(maxX - minX, maxY - minY), maxZ - minZ)
        val s = if (ext > 0f) 1f / ext else 1f
        i = 0
        while (i < d.size) {
            d[i] = (d[i] - cx) * s; d[i + 1] = (d[i + 1] - cy) * s; d[i + 2] = (d[i + 2] - cz) * s
            i += 6
        }
        return d
    }

    private fun faceNormal(a: FloatArray, b: FloatArray, c: FloatArray): FloatArray {
        val ux = b[0] - a[0]; val uy = b[1] - a[1]; val uz = b[2] - a[2]
        val vx = c[0] - a[0]; val vy = c[1] - a[1]; val vz = c[2] - a[2]
        val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
        val l = sqrt(nx * nx + ny * ny + nz * nz)
        return if (l > 0f) floatArrayOf(nx / l, ny / l, nz / l) else floatArrayOf(0f, 1f, 0f)
    }

    private fun normalize3(v: FloatArray): FloatArray {
        val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        return if (l > 0f) floatArrayOf(v[0] / l, v[1] / l, v[2] / l) else v
    }

    // ---------------- OBJ ----------------

    private fun objIndex(s: String, count: Int): Int {
        val i = s.toInt()
        return if (i < 0) count + i else i - 1
    }

    private fun parseObj(text: String): FloatArray {
        val pos = FloatList(); val nor = FloatList(); val out = FloatList()
        val ws = Regex("\\s+")
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.length < 3) continue
            when {
                line.startsWith("v ") -> {
                    val p = line.split(ws)
                    pos.add(p[1].toFloat()); pos.add(p[2].toFloat()); pos.add(p[3].toFloat())
                }
                line.startsWith("vn ") -> {
                    val p = line.split(ws)
                    nor.add(p[1].toFloat()); nor.add(p[2].toFloat()); nor.add(p[3].toFloat())
                }
                line.startsWith("f ") -> {
                    val parts = line.substring(2).trim().split(ws)
                    val vi = IntArray(parts.size)
                    val ni = IntArray(parts.size)
                    for (k in parts.indices) {
                        val c = parts[k].split("/")
                        vi[k] = objIndex(c[0], pos.size / 3)
                        ni[k] = if (c.size >= 3 && c[2].isNotEmpty()) objIndex(c[2], nor.size / 3) else -1
                    }
                    for (k in 1 until parts.size - 1) {
                        objTri(out, pos, nor, intArrayOf(vi[0], vi[k], vi[k + 1]), intArrayOf(ni[0], ni[k], ni[k + 1]))
                    }
                }
            }
        }
        return out.toArray()
    }

    private fun objTri(out: FloatList, pos: FloatList, nor: FloatList, v: IntArray, n: IntArray) {
        val pts = Array(3) { k -> floatArrayOf(pos[v[k] * 3], pos[v[k] * 3 + 1], pos[v[k] * 3 + 2]) }
        val fn = faceNormal(pts[0], pts[1], pts[2])
        for (k in 0..2) {
            out.add(pts[k][0]); out.add(pts[k][1]); out.add(pts[k][2])
            if (n[k] >= 0) {
                out.add(nor[n[k] * 3]); out.add(nor[n[k] * 3 + 1]); out.add(nor[n[k] * 3 + 2])
            } else {
                out.add(fn[0]); out.add(fn[1]); out.add(fn[2])
            }
        }
    }

    // ---------------- glTF / GLB ----------------

    private fun parseGltf(bytes: ByteArray): FloatArray {
        val json: JSONObject
        var glbBin: ByteArray? = null
        if (bytes.size > 20 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "glTF") {
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            var off = 12
            var jsonText: String? = null
            while (off + 8 <= bytes.size) {
                val len = bb.getInt(off)
                val type = bb.getInt(off + 4)
                val start = off + 8
                if (type == 0x4E4F534A) jsonText = String(bytes, start, len)
                else if (type == 0x004E4942) glbBin = bytes.copyOfRange(start, start + len)
                off = start + len
            }
            json = JSONObject(jsonText ?: throw IllegalArgumentException("Bad GLB file"))
        } else {
            json = JSONObject(String(bytes))
        }

        val bufArr = json.optJSONArray("buffers") ?: throw IllegalArgumentException("No buffers in file")
        val buffers = ArrayList<ByteArray?>()
        for (i in 0 until bufArr.length()) {
            val uri = bufArr.getJSONObject(i).optString("uri", "")
            buffers.add(
                when {
                    uri.startsWith("data:") -> Base64.decode(uri.substring(uri.indexOf(',') + 1), Base64.DEFAULT)
                    uri.isEmpty() -> glbBin
                    else -> null
                }
            )
        }
        val doc = Doc(json, buffers)
        val out = FloatList()
        val ident = FloatArray(16)
        Matrix.setIdentityM(ident, 0)

        val nodes = json.optJSONArray("nodes")
        if (nodes == null || nodes.length() == 0) {
            val ms = json.optJSONArray("meshes") ?: throw IllegalArgumentException("No meshes in file")
            for (i in 0 until ms.length()) emitMesh(doc, i, ident, out)
        } else {
            val roots = ArrayList<Int>()
            val sc = json.optJSONArray("scenes")
            val sceneNodes = if (sc != null && sc.length() > 0)
                sc.getJSONObject(json.optInt("scene", 0)).optJSONArray("nodes") else null
            if (sceneNodes != null) {
                for (i in 0 until sceneNodes.length()) roots.add(sceneNodes.getInt(i))
            } else {
                val isChild = BooleanArray(nodes.length())
                for (i in 0 until nodes.length()) {
                    val ch = nodes.getJSONObject(i).optJSONArray("children")
                    if (ch != null) for (k in 0 until ch.length()) isChild[ch.getInt(k)] = true
                }
                for (i in isChild.indices) if (!isChild[i]) roots.add(i)
            }
            for (r in roots) visit(doc, r, ident, out, 0)
        }
        return out.toArray()
    }

    private fun visit(doc: Doc, idx: Int, parent: FloatArray, out: FloatList, depth: Int) {
        if (depth > 64) return
        val n = doc.json.getJSONArray("nodes").getJSONObject(idx)
        val m = FloatArray(16)
        Matrix.multiplyMM(m, 0, parent, 0, localMatrix(n), 0)
        if (n.has("mesh")) emitMesh(doc, n.getInt("mesh"), m, out)
        val ch = n.optJSONArray("children")
        if (ch != null) for (k in 0 until ch.length()) visit(doc, ch.getInt(k), m, out, depth + 1)
    }

    private fun localMatrix(n: JSONObject): FloatArray {
        val m = FloatArray(16)
        val mat = n.optJSONArray("matrix")
        if (mat != null && mat.length() == 16) {
            for (k in 0..15) m[k] = mat.getDouble(k).toFloat()
            return m
        }
        Matrix.setIdentityM(m, 0)
        val t = n.optJSONArray("translation")
        if (t != null) Matrix.translateM(m, 0, t.getDouble(0).toFloat(), t.getDouble(1).toFloat(), t.getDouble(2).toFloat())
        val q = n.optJSONArray("rotation")
        if (q != null) {
            val x = q.getDouble(0).toFloat(); val y = q.getDouble(1).toFloat()
            val z = q.getDouble(2).toFloat(); val w = q.getDouble(3).toFloat()
            val r = FloatArray(16)
            r[0] = 1 - 2 * (y * y + z * z); r[1] = 2 * (x * y + z * w); r[2] = 2 * (x * z - y * w)
            r[4] = 2 * (x * y - z * w); r[5] = 1 - 2 * (x * x + z * z); r[6] = 2 * (y * z + x * w)
            r[8] = 2 * (x * z + y * w); r[9] = 2 * (y * z - x * w); r[10] = 1 - 2 * (x * x + y * y)
            r[15] = 1f
            val tmp = FloatArray(16)
            Matrix.multiplyMM(tmp, 0, m, 0, r, 0)
            System.arraycopy(tmp, 0, m, 0, 16)
        }
        val s = n.optJSONArray("scale")
        if (s != null) Matrix.scaleM(m, 0, s.getDouble(0).toFloat(), s.getDouble(1).toFloat(), s.getDouble(2).toFloat())
        return m
    }

    private fun transformPoint(m: FloatArray, arr: FloatArray, i: Int, w: Float): FloatArray {
        val inp = floatArrayOf(arr[i * 3], arr[i * 3 + 1], arr[i * 3 + 2], w)
        val o = FloatArray(4)
        Matrix.multiplyMV(o, 0, m, 0, inp, 0)
        return floatArrayOf(o[0], o[1], o[2])
    }

    private fun emitMesh(doc: Doc, meshIdx: Int, m: FloatArray, out: FloatList) {
        val prims = doc.json.getJSONArray("meshes").getJSONObject(meshIdx).getJSONArray("primitives")
        for (p in 0 until prims.length()) {
            val prim = prims.getJSONObject(p)
            if (prim.optInt("mode", 4) != 4) continue
            val attrs = prim.getJSONObject("attributes")
            if (!attrs.has("POSITION")) continue
            val pos = floats(doc, attrs.getInt("POSITION"), 3)
            val nor = if (attrs.has("NORMAL")) floats(doc, attrs.getInt("NORMAL"), 3) else null
            val idx = if (prim.has("indices")) ints(doc, prim.getInt("indices")) else IntArray(pos.size / 3) { it }
            var t = 0
            while (t + 2 < idx.size) {
                val v = Array(3) { transformPoint(m, pos, idx[t + it], 1f) }
                val nn: Array<FloatArray> = if (nor != null) {
                    Array(3) { normalize3(transformPoint(m, nor, idx[t + it], 0f)) }
                } else {
                    val f = faceNormal(v[0], v[1], v[2])
                    Array(3) { f }
                }
                for (k in 0..2) {
                    out.add(v[k][0]); out.add(v[k][1]); out.add(v[k][2])
                    out.add(nn[k][0]); out.add(nn[k][1]); out.add(nn[k][2])
                }
                t += 3
            }
        }
    }

    private fun view(doc: Doc, acc: JSONObject, elemSize: Int): Triple<ByteBuffer, Int, Int> {
        val bvIdx = acc.optInt("bufferView", -1)
        if (bvIdx < 0) throw IllegalArgumentException("Unsupported accessor (sparse or empty)")
        val bv = doc.json.getJSONArray("bufferViews").getJSONObject(bvIdx)
        val buf = doc.buffers.getOrNull(bv.getInt("buffer"))
            ?: throw IllegalArgumentException("External .bin files are not supported - use .glb")
        val start = bv.optInt("byteOffset", 0) + acc.optInt("byteOffset", 0)
        val stride = bv.optInt("byteStride", 0).let { if (it == 0) elemSize else it }
        return Triple(ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN), start, stride)
    }

    private fun floats(doc: Doc, accIdx: Int, comps: Int): FloatArray {
        val acc = doc.json.getJSONArray("accessors").getJSONObject(accIdx)
        if (acc.optInt("componentType") != 5126) throw IllegalArgumentException("Unsupported vertex format")
        val count = acc.getInt("count")
        val (bb, start, stride) = view(doc, acc, 4 * comps)
        val out = FloatArray(count * comps)
        for (i in 0 until count) for (c in 0 until comps) out[i * comps + c] = bb.getFloat(start + i * stride + c * 4)
        return out
    }

    private fun ints(doc: Doc, accIdx: Int): IntArray {
        val acc = doc.json.getJSONArray("accessors").getJSONObject(accIdx)
        val ct = acc.optInt("componentType")
        val size = when (ct) { 5121 -> 1; 5123 -> 2; 5125 -> 4; else -> throw IllegalArgumentException("Unsupported index format") }
        val count = acc.getInt("count")
        val (bb, start, stride) = view(doc, acc, size)
        return IntArray(count) { i ->
            val o = start + i * stride
            when (size) {
                1 -> bb.get(o).toInt() and 0xFF
                2 -> bb.getShort(o).toInt() and 0xFFFF
                else -> bb.getInt(o)
            }
        }
    }
}
