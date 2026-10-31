package com.example.minieditor

import android.opengl.Matrix
import kotlin.math.asin
import kotlin.math.atan2

/**
 * Bone rigging. An object can be attached to a Bone (or a bone to another bone). It then follows that bone:
 * its position and rotation are recomputed from the bone every frame, so rotating a bone swings everything on it.
 * Objects keep ordinary world x/y/z/rx/ry/rz, so picking, scripts and collisions keep working.
 */
object Rig {
    var nextUid = 1
    /** The object being dragged / edited right now. It is left alone so it can move freely; it is re-attached afterwards. */
    var editing: SceneObject? = null

    fun ensureUid(o: SceneObject): Int {
        if (o.uid == 0) o.uid = nextUid++
        return o.uid
    }

    fun find(scene: Scene, uid: Int): SceneObject? =
        if (uid == 0) null else scene.objects.firstOrNull { it.uid == uid }

    /** Position and rotation of an object (no scale), in the same order the renderer uses. */
    fun worldMatrix(o: SceneObject, out: FloatArray) {
        Matrix.setIdentityM(out, 0)
        Matrix.translateM(out, 0, o.x, o.y, o.z)
        Matrix.rotateM(out, 0, o.ry, 0f, 1f, 0f)
        Matrix.rotateM(out, 0, o.rx, 1f, 0f, 0f)
        Matrix.rotateM(out, 0, o.rz, 0f, 0f, 1f)
    }

    /** Attach child to parent, keeping the child exactly where it is now. */
    fun bind(child: SceneObject, parent: SceneObject) {
        val pm = FloatArray(16)
        val cm = FloatArray(16)
        val inv = FloatArray(16)
        worldMatrix(parent, pm)
        worldMatrix(child, cm)
        if (!Matrix.invertM(inv, 0, pm, 0)) return
        val rel = FloatArray(16)
        Matrix.multiplyMM(rel, 0, inv, 0, cm, 0)
        child.parentId = ensureUid(parent)
        child.rel = rel
    }

    /** Re-learn the offset after the child was moved by hand. */
    fun rebind(scene: Scene, o: SceneObject) {
        val p = find(scene, o.parentId) ?: return
        bind(o, p)
    }

    fun detach(o: SceneObject) {
        o.parentId = 0
        o.rel = null
    }

    /** True if [candidate] is [ancestor] or hangs below it (attaching ancestor to candidate would make a loop). */
    fun isDescendant(scene: Scene, candidate: SceneObject, ancestor: SceneObject): Boolean {
        var cur: SceneObject? = candidate
        var n = 0
        while (cur != null && n < 64) {
            if (cur === ancestor) return true
            cur = find(scene, cur.parentId)
            n++
        }
        return false
    }

    private fun depthOf(scene: Scene, o: SceneObject): Int {
        var n = 0
        var cur = o
        while (n < 64) {
            val p = find(scene, cur.parentId) ?: break
            cur = p
            n++
        }
        return n
    }

    /** Moves every attached object to follow its bone. Parents are handled before their children. */
    fun apply(scene: Scene) {
        val kids = scene.objects.filter { it.parentId != 0 && it.rel != null }
        if (kids.isEmpty()) return
        val pm = FloatArray(16)
        val wm = FloatArray(16)
        for (c in kids.sortedBy { depthOf(scene, it) }) {
            if (c === editing) continue
            val p = find(scene, c.parentId) ?: continue
            val rel = c.rel ?: continue
            worldMatrix(p, pm)
            Matrix.multiplyMM(wm, 0, pm, 0, rel, 0)
            c.x = wm[12]
            c.y = wm[13]
            c.z = wm[14]
            // rotation order is Ry * Rx * Rz (column-major: element row i, col j = wm[j * 4 + i])
            val sinX = (-wm[9]).coerceIn(-1f, 1f)
            c.rx = Math.toDegrees(asin(sinX).toDouble()).toFloat()
            c.ry = Math.toDegrees(atan2(wm[8], wm[10]).toDouble()).toFloat()
            c.rz = Math.toDegrees(atan2(wm[1], wm[5]).toDouble()).toFloat()
        }
    }
}
