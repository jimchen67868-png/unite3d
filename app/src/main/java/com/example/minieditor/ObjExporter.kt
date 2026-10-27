package com.example.minieditor

import android.opengl.Matrix
import java.util.Locale
import kotlin.math.sqrt

/** Writes objects as Wavefront OBJ with position, rotation and scale baked into the vertices. */
object ObjExporter {
    fun export(objects: List<SceneObject>): String {
        val sb = StringBuilder("# Exported from Mini 3D Editor\n")
        var base = 1
        for (o in objects) {
            val data = o.model?.data ?: Meshes.dataFor(o.shape) ?: continue
            val m = FloatArray(16)
            Matrix.setIdentityM(m, 0)
            Matrix.translateM(m, 0, o.x, o.y, o.z)
            Matrix.rotateM(m, 0, o.ry, 0f, 1f, 0f)
            Matrix.rotateM(m, 0, o.rx, 1f, 0f, 0f)
            Matrix.rotateM(m, 0, o.rz, 0f, 0f, 1f)
            Matrix.scaleM(m, 0, o.sx, o.sy, o.sz)
            val inv = FloatArray(16)
            val nm = FloatArray(16)
            if (Matrix.invertM(inv, 0, m, 0)) Matrix.transposeM(nm, 0, inv, 0) else System.arraycopy(m, 0, nm, 0, 16)

            val count = data.size / 6
            val pos = FloatArray(count * 3)
            val nor = FloatArray(count * 3)
            val inp = FloatArray(4)
            val res = FloatArray(4)
            for (i in 0 until count) {
                inp[0] = data[i * 6]; inp[1] = data[i * 6 + 1]; inp[2] = data[i * 6 + 2]; inp[3] = 1f
                Matrix.multiplyMV(res, 0, m, 0, inp, 0)
                pos[i * 3] = res[0]; pos[i * 3 + 1] = res[1]; pos[i * 3 + 2] = res[2]

                inp[0] = data[i * 6 + 3]; inp[1] = data[i * 6 + 4]; inp[2] = data[i * 6 + 5]; inp[3] = 0f
                Matrix.multiplyMV(res, 0, nm, 0, inp, 0)
                val l = sqrt(res[0] * res[0] + res[1] * res[1] + res[2] * res[2])
                val d = if (l > 0f) l else 1f
                nor[i * 3] = res[0] / d; nor[i * 3 + 1] = res[1] / d; nor[i * 3 + 2] = res[2] / d
            }

            sb.append("o ").append(o.name.replace(Regex("\\s+"), "_")).append('\n')
            for (i in 0 until count) {
                sb.append(String.format(Locale.US, "v %.5f %.5f %.5f\n", pos[i * 3], pos[i * 3 + 1], pos[i * 3 + 2]))
            }
            for (i in 0 until count) {
                sb.append(String.format(Locale.US, "vn %.4f %.4f %.4f\n", nor[i * 3], nor[i * 3 + 1], nor[i * 3 + 2]))
            }
            var t = 0
            while (t + 2 < count) {
                val ax = pos[(t + 1) * 3] - pos[t * 3]
                val ay = pos[(t + 1) * 3 + 1] - pos[t * 3 + 1]
                val az = pos[(t + 1) * 3 + 2] - pos[t * 3 + 2]
                val bx = pos[(t + 2) * 3] - pos[t * 3]
                val by = pos[(t + 2) * 3 + 1] - pos[t * 3 + 1]
                val bz = pos[(t + 2) * 3 + 2] - pos[t * 3 + 2]
                val cx = ay * bz - az * by
                val cy = az * bx - ax * bz
                val cz = ax * by - ay * bx
                val dot = cx * (nor[t * 3] + nor[(t + 1) * 3] + nor[(t + 2) * 3]) +
                    cy * (nor[t * 3 + 1] + nor[(t + 1) * 3 + 1] + nor[(t + 2) * 3 + 1]) +
                    cz * (nor[t * 3 + 2] + nor[(t + 1) * 3 + 2] + nor[(t + 2) * 3 + 2])
                val a = base + t
                var b = base + t + 1
                var c = base + t + 2
                if (dot < 0f) { val tmp = b; b = c; c = tmp }
                sb.append("f ").append(a).append("//").append(a).append(' ')
                    .append(b).append("//").append(b).append(' ')
                    .append(c).append("//").append(c).append('\n')
                t += 3
            }
            base += count
        }
        return sb.toString()
    }
}
