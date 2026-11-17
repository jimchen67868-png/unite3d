package com.example.minieditor

import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Ready-made complex objects for the editor (Add > +Build). Every object is made of cubes, pyramids, spheres
 * (and real trees / fire), put into one group, so it moves, turns and scales as one thing.
 * Parts that stand straight are solid (collider 1); turned parts are only decoration (collider 0),
 * because the engine's collision ignores rotation. Change it in the Inspector if you want.
 */
object Builder {
    class Item(val name: String, val make: (Scene, Float, Float) -> SceneObject?)

    private fun c(r: Float, g: Float, b: Float) = floatArrayOf(r, g, b)

    private val WALL = c(0.85f, 0.80f, 0.70f)
    private val STONE = c(0.58f, 0.58f, 0.60f)
    private val DARKSTONE = c(0.42f, 0.42f, 0.45f)
    private val WOOD = c(0.55f, 0.38f, 0.22f)
    private val DARKWOOD = c(0.38f, 0.26f, 0.15f)
    private val ROOF = c(0.62f, 0.25f, 0.20f)
    private val GLASS = c(0.55f, 0.75f, 0.95f)
    private val GRASS = c(0.30f, 0.58f, 0.26f)
    private val BLACK = c(0.08f, 0.08f, 0.09f)
    private val WHITE = c(0.95f, 0.95f, 0.95f)

    class Opening(val at: Float, val w: Float, val bottom: Float, val top: Float)

    /** Collects the parts of one object, then groups them. */
    private class Kit(val scene: Scene, val bx: Float, val bz: Float) {
        val parts = ArrayList<SceneObject>()

        fun part(
            shape: Shape, x: Float, y: Float, z: Float, sx: Float, sy: Float, sz: Float, col: FloatArray,
            solid: Boolean = true, ry: Float = 0f, rx: Float = 0f, rz: Float = 0f,
            opacity: Float = 1f, emit: Float = 0f, name: String = "Part"
        ): SceneObject {
            val o = scene.add(shape, null, name)
            o.x = bx + x; o.y = y; o.z = bz + z
            o.sx = sx; o.sy = sy; o.sz = sz
            o.rx = rx; o.ry = ry; o.rz = rz
            o.color = col.copyOf()
            o.collider = if (solid) 1 else 0
            o.opacity = opacity
            o.emit = emit
            parts.add(o)
            return o
        }

        fun box(x: Float, y: Float, z: Float, sx: Float, sy: Float, sz: Float, col: FloatArray,
                solid: Boolean = true, ry: Float = 0f, rx: Float = 0f, rz: Float = 0f, name: String = "Part") =
            part(Shape.CUBE, x, y, z, sx, sy, sz, col, solid, ry, rx, rz, 1f, 0f, name)

        fun ball(x: Float, y: Float, z: Float, sx: Float, sy: Float, sz: Float, col: FloatArray,
                 solid: Boolean = false, emit: Float = 0f, name: String = "Part") =
            part(Shape.SPHERE, x, y, z, sx, sy, sz, col, solid, 0f, 0f, 0f, 1f, emit, name)

        fun cone(x: Float, y: Float, z: Float, sx: Float, sy: Float, sz: Float, col: FloatArray,
                 solid: Boolean = false, ry: Float = 0f, rx: Float = 0f, name: String = "Part") =
            part(Shape.PYRAMID, x, y, z, sx, sy, sz, col, solid, ry, rx, 0f, 1f, 0f, name)

        fun glass(x: Float, y: Float, z: Float, sx: Float, sy: Float, sz: Float) =
            part(Shape.CUBE, x, y, z, sx, sy, sz, GLASS, false, 0f, 0f, 0f, 0.35f, 0f, "Glass")

        /** A straight wall with openings (doors, windows). alongX = runs along X, otherwise along Z. y0 = height of its bottom. */
        fun wall(alongX: Boolean, cx: Float, cz: Float, len: Float, h: Float, t: Float, col: FloatArray,
                 y0: Float = 0f, openings: List<Opening> = emptyList()) {
            fun seg(a: Float, b: Float, lo: Float, hi: Float) {
                if (b - a < 0.01f || hi - lo < 0.01f) return
                val mid = (a + b) / 2f
                val sy = hi - lo
                val y = y0 + lo + sy / 2f
                if (alongX) box(cx + mid, y, cz, b - a, sy, t, col, true, 0f, 0f, 0f, "Wall")
                else box(cx, y, cz + mid, t, sy, b - a, col, true, 0f, 0f, 0f, "Wall")
            }
            var cur = -len / 2f
            for (o in openings.sortedBy { it.at }) {
                val a = (o.at - o.w / 2f).coerceAtLeast(cur)
                val b = (o.at + o.w / 2f).coerceAtMost(len / 2f)
                seg(cur, a, 0f, h)
                seg(a, b, 0f, o.bottom)
                seg(a, b, o.top, h)
                cur = b
            }
            seg(cur, len / 2f, 0f, h)
        }

        fun finish(title: String): SceneObject? {
            if (parts.isEmpty()) return null
            val g = scene.makeGroup(parts)
            g.name = g.name + " " + title
            return g
        }
    }

    // ================= Buildings =================

    private fun house(scene: Scene, bx: Float, bz: Float): SceneObject? {
        val k = Kit(scene, bx, bz)
        val w = 8f; val d = 6f; val h = 3f; val t = 0.3f
        k.box(0f, 0.1f, 0f, w, 0.2f, d, DARKWOOD, true, 0f, 0f, 0f, "Floor")
        // front wall (door), back wall (2 windows), side walls (1 window each)
        k.wall(true, 0f, d / 2f - t / 2f, w, h, t, WALL, 0.2f, listOf(Opening(0f, 1.3f, 0f, 2.3f), Opening(2.6f, 1.2f, 1.0f, 2.2f), Opening(-2.6f, 1.2f, 1.0f, 2.2f)))
        k.wall(true, 0f, -d / 2f + t / 2f, w, h, t, WALL, 0.2f, listOf(Opening(-2f, 1.4f, 1.0f, 2.2f), Opening(2f, 1.4f, 1.0f, 2.2f)))
        k.wall(false, -w / 2f + t / 2f, 0f, d - 2f * t, h, t, WALL, 0.2f, listOf(Opening(0f, 1.4f, 1.0f, 2.2f)))
        k.wall(false, w / 2f - t / 2f, 0f, d - 2f * t, h, t, WALL, 0.2f, listOf(Opening(0f, 1.4f, 1.0f, 2.2f)))
        // window glass
        k.glass(2.6f, 0.2f + 1.6f, d / 2f - t / 2f, 1.2f, 1.2f, 0.04f)
        k.glass(-2.6f, 0.2f + 1.6f, d / 2f - t / 2f, 1.2f, 1.2f, 0.04f)
        k.glass(-2f, 0.2f + 1.6f, -d / 2f + t / 2f, 1.4f, 1.2f, 0.04f)
        k.glass(2f, 0.2f + 1.6f, -d / 2f + t / 2f, 1.4f, 1.2f, 0.04f)
        k.glass(-w / 2f + t / 2f, 0.2f + 1.6f, 0f, 0.04f, 1.2f, 1.4f)
        k.glass(w / 2f - t / 2f, 0.2f + 1.6f, 0f, 0.04f, 1.2f, 1.4f)
        // ceiling, roof, chimney
        k.box(0f, 0.2f + h + 0.1f, 0f, w, 0.2f, d, DARKWOOD, true, 0f, 0f, 0f, "Ceiling")
        k.cone(0f, 0.2f + h + 0.2f + 1.1f, 0f, w + 1f, 2.2f, d + 1f, ROOF, false, 0f, 0f, "Roof")
        k.box(2.2f, 0.2f + h + 1.6f, -1.2f, 0.7f, 2.2f, 0.7f, STONE, false, 0f, 0f, 0f, "Chimney")
        return k.finish("House")
    }

    private fun tower(scene: Scene, bx: Float, bz: Float): SceneObject? {
        val k = Kit(scene, bx, bz)
        val w = 5f; val h = 12f; val t = 0.5f
        k.box(0f, 0.15f, 0f, w + 1f, 0.3f, w + 1f, DARKSTONE, true, 0f, 0f, 0f, "Base")
        val wins = listOf(Opening(-1.5f, 0.8f, 6f, 7.6f), Opening(1.5f, 0.8f, 6f, 7.6f))
        k.wall(true, 0f, w / 2f - t / 2f, w, h, t, STONE, 0.3f, listOf(Opening(0f, 1.4f, 0f, 2.4f)) + wins)
        k.wall(true, 0f, -w / 2f + t / 2f, w, h, t, STONE, 0.3f, wins)
        k.wall(false, -w / 2f + t / 2f, 0f, w - 2f * t, h, t, STONE, 0.3f, wins)
        k.wall(false, w / 2f - t / 2f, 0f, w - 2f * t, h, t, STONE, 0.3f, wins)
        // top platform with a low wall and corner stones
        k.box(0f, 0.3f + h + 0.1f, 0f, w + 0.6f, 0.2f, w + 0.6f, DARKSTONE, true, 0f, 0f, 0f, "Top")
        val e = (w + 0.6f) / 2f - 0.2f
        k.box(0f, 0.3f + h + 0.2f + 0.5f, e, w + 0.6f, 1f, 0.4f, STONE, true, 0f, 0f, 0f, "Parapet")
        k.box(0f, 0.3f + h + 0.2f + 0.5f, -e, w + 0.6f, 1f, 0.4f, STONE, true, 0f, 0f, 0f, "Parapet")
        k.box(e, 0.3f + h + 0.2f + 0.5f, 0f, 0.4f, 1f, w + 0.6f, STONE, true, 0f, 0f, 0f, "Parapet")
        k.box(-e, 0.3f + h + 0.2f + 0.5f, 0f, 0.4f, 1f, w + 0.6f, STONE, true, 0f, 0f, 0f, "Parapet")
        for (sx in intArrayOf(-1, 1)) for (sz in intArrayOf(-1, 1)) {
            k.box(sx * e, 0.3f + h + 0.2f + 1.3f, sz * e, 0.7f, 0.6f, 0.7f, DARKSTONE, true, 0f, 0f, 0f, "Corner")
        }
        k.box(0f, 0.3f + h + 0.2f + 2.5f, 0f, 0.12f, 3.6f, 0.12f, DARKWOOD, false, 0f, 0f, 0f, "Flagpole")
        k.box(0.55f, 0.3f + h + 0.2f + 3.8f, 0f, 1.0f, 0.6f, 0.04f, c(0.85f, 0.2f, 0.2f), false, 0f, 0f, 0f, "Flag")
        return k.finish("Tower")
    }

    private fun bridge(scene: Scene, bx: Float, bz: Float): SceneObject? {
        val k = Kit(scene, bx, bz)
        val len = 16f; val wd = 3.2f; val dy = 2.2f
        k.box(0f, dy, 0f, wd, 0.3f, len, WOOD, true, 0f, 0f, 0f, "Deck")
        var z = -len / 2f + 1f
        while (z <= len / 2f - 0.9f) {
            k.box(-(wd / 2f - 0.35f), dy / 2f, z, 0.6f, dy, 0.6f, DARKSTONE, true, 0f, 0f, 0f, "Pillar")
            k.box(wd / 2f - 0.35f, dy / 2f, z, 0.6f, dy, 0.6f, DARKSTONE, true, 0f, 0f, 0f, "Pillar")
            z += 4f
        }
        z = -len / 2f
        while (z <= len / 2f + 0.01f) {
            k.box(-(wd / 2f - 0.1f), dy + 0.8f, z, 0.15f, 1.3f, 0.15f, DARKWOOD, false, 0f, 0f, 0f, "Post")
            k.box(wd / 2f - 0.1f, dy + 0.8f, z, 0.15f, 1.3f, 0.15f, DARKWOOD, false, 0f, 0f, 0f, "Post")
            z += 2f
        }
        k.box(-(wd / 2f - 0.1f), dy + 1.4f, 0f, 0.12f, 0.12f, len, DARKWOOD, false, 0f, 0f, 0f, "Rail")
        k.box(wd / 2f - 0.1f, dy + 1.4f, 0f, 0.12f, 0.12f, len, DARKWOOD, false, 0f, 0f, 0f, "Rail")
        // ramps at both ends (a turned slab; decoration + a flat landing that is solid)
        val ang = Math.toDegrees(Math.atan2(dy.toDouble(), 4.0)).toFloat()
        k.box(0f, dy / 2f, len / 2f + 2f, wd, 0.3f, 4.5f, WOOD, false, 0f, ang, 0f, "Ramp")
        k.box(0f, dy / 2f, -len / 2f - 2f, wd, 0.3f, 4.5f, WOOD, false, 0f, -ang, 0f, "Ramp")
        return k.finish("Bridge")
    }

    private fun wallFence(scene: Scene, bx: Float, bz: Float): SceneObject? {
        val k = Kit(scene, bx, bz)
        var x = -6f
        while (x <= 6.01f) {
            k.box(x, 0.6f, 0f, 0.15f, 1.2f, 0.15f, DARKWOOD, true, 0f, 0f, 0f, "Post")
            x += 1.5f
        }
        k.box(0f, 0.95f, 0f, 12.2f, 0.12f, 0.06f, WOOD, false, 0f, 0f, 0f, "Rail")
        k.box(0f, 0.5f, 0f, 12.2f, 0.12f, 0.06f, WOOD, false, 0f, 0f, 0f, "Rail")
        return k.finish("Fence")
    }

    // ================= Nature =================

    private fun rock(scene: Scene, bx: Float, bz: Float): SceneObject? {
        val k = Kit(scene, bx, bz)
        k.ball(0f, 0.7f, 0f, 2.6f, 1.8f, 2.2f, STONE, true, 0f, "Rock")
        k.ball(1.5f, 0.45f, 0.6f, 1.6f, 1.0f, 1.5f, DARKSTONE, true, 0f, "Rock")
        k.ball(-1.3f, 0.4f, 0.8f, 1.3f, 0.9f, 1.2f, STONE, true, 0f, "Rock")
        k.ball(0.4f, 0.3f, -1.4f, 1.2f, 0.7f, 1.4f, DARKSTONE, true, 0f, "Rock")
        k.ball(-0.3f, 1.4f, 0.1f, 1.3f, 0.9f, 1.2f, c(0.65f, 0.65f, 0.67f), true, 0f, "Rock")
        return k.finish("Rock")
    }

    private fun hill(scene: Scene, bx: Float, bz: Float): SceneObject? {
        val k = Kit(scene, bx, bz)
        k.ball(0f, 0f, 0f, 26f, 8f, 22f, GRASS, false, 0f, "Hill")
        k.ball(5f, 0f, 3f, 14f, 6f, 12f, c(0.34f, 0.62f, 0.28f), false, 0f, "Hill")
        k.ball(-6f, 0f, -2f, 12f, 5f, 12f, c(0.28f, 0.54f, 0.24f), false, 0f, "Hill")
        return k.finish("Hill")
    }

    private fun bigTree(scene: Scene, bx: Float, bz: Float): SceneObject? {
        val k = Kit(scene, bx, bz)
        k.box(0f, 1.8f, 0f, 0.7f, 3.6f, 0.7f, WOOD, true, 0f, 0f, 0f, "Trunk")
        k.ball(0f, 4.6f, 0f, 4.2f, 3.4f, 4.2f, c(0.22f, 0.52f, 0.2f), false, 0f, "Leaves")
        k.ball(1.2f, 5.6f, 0.4f, 3.0f, 2.6f, 3.0f, c(0.26f, 0.58f, 0.22f), false, 0f, "Leaves")
        k.ball(-1.1f, 5.3f, -0.5f, 2.8f, 2.4f, 2.8f, c(0.2f, 0.48f, 0.18f), false, 0f, "Leaves")
        return k.finish("Big tree")
    }

    private fun forest(scene: Scene, bx: Float, bz: Float): SceneObject? {
        val rnd = Random(bx.toInt() * 31L + bz.toInt() * 17L + 5L)
        val trees = ArrayList<SceneObject>()
        for (i in 0 until 14) {
            val a = rnd.nextFloat() * 2f * PI.toFloat()
            val r = 2f + rnd.nextFloat() * 9f
            val tr = scene.addTree(rnd.nextInt(2))
            val s = 0.8f + rnd.nextFloat() * 0.6f
            tr.x = bx + cos(a) * r; tr.z = bz + sin(a) * r; tr.y = 0f
            tr.sx = 2f * s; tr.sy = 3.4f * s; tr.sz = 2f * s
            tr.ry = rnd.nextFloat() * 360f
            trees.add(tr)
        }
        val g = scene.makeGroup(trees)
        g.name = g.name + " Forest"
        return g
    }

    private fun cliff(scene: Scene, bx: Float, bz: Float): SceneObject? {
        val k = Kit(scene, bx, bz)
        k.box(0f, 1.5f, 0f, 8f, 3f, 3f, STONE, true, 0f, 0f, 0f, "Cliff")
        k.box(-0.5f, 4f, 0.2f, 6f, 2f, 2.6f, DARKSTONE, true, 0f, 0f, 0f, "Cliff")
        k.box(0.5f, 6f, 0.4f, 4f, 2f, 2.2f, STONE, true, 0f, 0f, 0f, "Cliff")
        k.box(1f, 7.6f, 0.5f, 2.2f, 1.4f, 1.8f, DARKSTONE, true, 0f, 0f, 0f, "Cliff")
        k.box(-4.4f, 0.8f, 0.8f, 1.8f, 1.6f, 1.8f, DARKSTONE, true, 25f, 0f, 0f, "Stone")
        k.box(4.6f, 0.6f, 0.9f, 1.4f, 1.2f, 1.5f, STONE, true, -20f, 0f, 0f, "Stone")
        return k.finish("Cliff")
    }

    private fun campfire(scene: Scene, bx: Float, bz: Float): SceneObject? {
        val k = Kit(scene, bx, bz)
        for (i in 0 until 8) {
            val a = i * 45f * PI.toFloat() / 180f
            k.ball(cos(a) * 0.9f, 0.18f, sin(a) * 0.9f, 0.45f, 0.35f, 0.45f, STONE, false, 0f, "Stone")
        }
        k.box(0f, 0.15f, 0f, 1.3f, 0.2f, 0.22f, DARKWOOD, false, 45f, 0f, 0f, "Log")
        k.box(0f, 0.15f, 0f, 1.3f, 0.2f, 0.22f, DARKWOOD, false, -45f, 0f, 0f, "Log")
        val f = scene.addFire()
        f.x = bx; f.y = 0.3f; f.z = bz
        k.parts.add(f)
        return k.finish("Campfire")
    }

    // ================= Shapes =================

    private fun cylinder(scene: Scene, bx: Float, bz: Float): SceneObject? {
        val k = Kit(scene, bx, bz)
        val s = 1.8f
        k.box(0f, 1.5f, 0f, s, 3f, s, WALL, true, 0f, 0f, 0f, "Cylinder")
        k.box(0f, 1.5f, 0f, s, 3f, s, WALL, false, 30f, 0f, 0f, "Cylinder")
        k.box(0f, 1.5f, 0f, s, 3f, s, WALL, false, 60f, 0f, 0f, "Cylinder")
        return k.finish("Cylinder")
    }

    private fun cone(scene: Scene, bx: Float, bz: Float): SceneObject? {
        val k = Kit(scene, bx, bz)
        k.cone(0f, 1.5f, 0f, 3f, 3f, 3f, WALL, false, 0f, 0f, "Cone")
        k.cone(0f, 1.5f, 0f, 3f, 3f, 3f, WALL, false, 45f, 0f, "Cone")
        return k.finish("Cone")
    }

    private fun arch(scene: Scene, bx: Float, bz: Float): SceneObject? {
        val k = Kit(scene, bx, bz)
        k.box(-3f, 2.25f, 0f, 1.2f, 4.5f, 1.2f, STONE, true, 0f, 0f, 0f, "Pillar")
        k.box(3f, 2.25f, 0f, 1.2f, 4.5f, 1.2f, STONE, true, 0f, 0f, 0f, "Pillar")
        val n = 9
        for (i in 0 until n) {
            val deg = 180f * i / (n - 1)
            val a = deg * PI.toFloat() / 180f
            k.box(cos(a) * 3f, 4.5f + sin(a) * 3f, 0f, 1.25f, 1.1f, 1.2f, DARKSTONE, false, 0f, 0f, deg + 90f, "Arch stone")
        }
        return k.finish("Arch")
    }

    private fun stairs(scene: Scene, bx: Float, bz: Float): SceneObject? {
        val k = Kit(scene, bx, bz)
        for (i in 0 until 10) {
            val hh = (i + 1) * 0.5f
            k.box(0f, hh / 2f, -i * 0.6f, 3f, hh, 0.6f, WALL, true, 0f, 0f, 0f, "Step")
        }
        return k.finish("Stairs")
    }

    private fun roundWall(scene: Scene, bx: Float, bz: Float): SceneObject? {
        val k = Kit(scene, bx, bz)
        val n = 16
        for (i in 0 until n) {
            if (i == 4) continue   // the gate
            val deg = 360f * i / n
            val a = deg * PI.toFloat() / 180f
            k.box(cos(a) * 6f, 1.5f, sin(a) * 6f, 2.5f, 3f, 0.5f, STONE, false, -(deg + 90f), 0f, 0f, "Wall piece")
        }
        return k.finish("Round wall")
    }

    private fun ziggurat(scene: Scene, bx: Float, bz: Float): SceneObject? {
        val k = Kit(scene, bx, bz)
        for (i in 0 until 5) {
            val s = 10f - i * 2f
            k.box(0f, 0.5f + i, 0f, s, 1f, s, if (i % 2 == 0) STONE else DARKSTONE, true, 0f, 0f, 0f, "Level")
        }
        return k.finish("Ziggurat")
    }

    private fun dome(scene: Scene, bx: Float, bz: Float): SceneObject? {
        val k = Kit(scene, bx, bz)
        k.ball(0f, 0f, 0f, 8f, 8f, 8f, WALL, false, 0f, "Dome")
        k.box(0f, 0.15f, 0f, 8.6f, 0.3f, 8.6f, DARKSTONE, true, 0f, 0f, 0f, "Floor")
        return k.finish("Dome")
    }

    private fun pillar(scene: Scene, bx: Float, bz: Float): SceneObject? {
        val k = Kit(scene, bx, bz)
        k.box(0f, 0.2f, 0f, 1.6f, 0.4f, 1.6f, DARKSTONE, true, 0f, 0f, 0f, "Base")
        k.box(0f, 2.6f, 0f, 0.9f, 4.4f, 0.9f, STONE, true, 0f, 0f, 0f, "Shaft")
        k.box(0f, 4.95f, 0f, 1.5f, 0.3f, 1.5f, DARKSTONE, true, 0f, 0f, 0f, "Cap")
        return k.finish("Pillar")
    }

    // ================= Vehicles and props =================

    private fun car(scene: Scene, bx: Float, bz: Float): SceneObject? {
        val k = Kit(scene, bx, bz)
        k.box(0f, 0.7f, 0f, 1.9f, 0.6f, 4.2f, c(0.80f, 0.15f, 0.15f), true, 0f, 0f, 0f, "Body")
        k.box(0f, 1.25f, 0.3f, 1.7f, 0.55f, 2.2f, c(0.72f, 0.12f, 0.12f), false, 0f, 0f, 0f, "Cabin")
        k.glass(0f, 1.3f, 0.3f, 1.74f, 0.42f, 2.24f)
        for (sx in intArrayOf(-1, 1)) for (sz in intArrayOf(-1, 1)) {
            k.ball(sx * 0.95f, 0.4f, sz * 1.35f, 0.3f, 0.8f, 0.8f, BLACK, false, 0f, "Wheel")
        }
        k.box(-0.65f, 0.75f, -2.12f, 0.4f, 0.2f, 0.06f, c(1f, 0.95f, 0.6f), false, 0f, 0f, 0f, "Light").emit = 1f
        k.box(0.65f, 0.75f, -2.12f, 0.4f, 0.2f, 0.06f, c(1f, 0.95f, 0.6f), false, 0f, 0f, 0f, "Light").emit = 1f
        k.box(-0.65f, 0.75f, 2.12f, 0.4f, 0.2f, 0.06f, c(0.9f, 0.1f, 0.1f), false, 0f, 0f, 0f, "Light")
        k.box(0.65f, 0.75f, 2.12f, 0.4f, 0.2f, 0.06f, c(0.9f, 0.1f, 0.1f), false, 0f, 0f, 0f, "Light")
        return k.finish("Car")
    }

    private fun boat(scene: Scene, bx: Float, bz: Float): SceneObject? {
        val k = Kit(scene, bx, bz)
        k.box(0f, 0.4f, 0f, 2.2f, 0.5f, 6f, WOOD, false, 0f, 0f, 0f, "Hull")
        k.box(-1f, 0.95f, 0f, 0.2f, 0.8f, 6f, DARKWOOD, false, 0f, 0f, 0f, "Side")
        k.box(1f, 0.95f, 0f, 0.2f, 0.8f, 6f, DARKWOOD, false, 0f, 0f, 0f, "Side")
        k.box(0f, 0.95f, 2.9f, 2.2f, 0.8f, 0.2f, DARKWOOD, false, 0f, 0f, 0f, "Stern")
        k.cone(0f, 0.8f, -3.8f, 2.2f, 1.6f, 0.9f, DARKWOOD, false, 0f, -90f, "Prow")
        k.box(0f, 3.1f, -0.4f, 0.15f, 4.6f, 0.15f, DARKWOOD, false, 0f, 0f, 0f, "Mast")
        k.box(0f, 3.4f, 0.9f, 0.05f, 3f, 2.4f, WHITE, false, 0f, 0f, 0f, "Sail")
        k.box(0f, 0.75f, 0.8f, 1.8f, 0.1f, 0.5f, WOOD, false, 0f, 0f, 0f, "Seat")
        return k.finish("Boat")
    }

    private fun table(scene: Scene, bx: Float, bz: Float): SceneObject? {
        val k = Kit(scene, bx, bz)
        k.box(0f, 0.8f, 0f, 1.6f, 0.1f, 0.9f, WOOD, true, 0f, 0f, 0f, "Top")
        for (sx in intArrayOf(-1, 1)) for (sz in intArrayOf(-1, 1)) {
            k.box(sx * 0.7f, 0.4f, sz * 0.38f, 0.1f, 0.8f, 0.1f, DARKWOOD, false, 0f, 0f, 0f, "Leg")
        }
        return k.finish("Table")
    }

    private fun chair(scene: Scene, bx: Float, bz: Float): SceneObject? {
        val k = Kit(scene, bx, bz)
        k.box(0f, 0.45f, 0f, 0.5f, 0.08f, 0.5f, WOOD, true, 0f, 0f, 0f, "Seat")
        k.box(0f, 0.85f, 0.23f, 0.5f, 0.8f, 0.06f, WOOD, false, 0f, 0f, 0f, "Back")
        for (sx in intArrayOf(-1, 1)) for (sz in intArrayOf(-1, 1)) {
            k.box(sx * 0.21f, 0.2f, sz * 0.21f, 0.06f, 0.4f, 0.06f, DARKWOOD, false, 0f, 0f, 0f, "Leg")
        }
        return k.finish("Chair")
    }

    private fun bed(scene: Scene, bx: Float, bz: Float): SceneObject? {
        val k = Kit(scene, bx, bz)
        k.box(0f, 0.3f, 0f, 1.5f, 0.3f, 2.1f, DARKWOOD, true, 0f, 0f, 0f, "Frame")
        k.box(0f, 0.55f, 0.1f, 1.4f, 0.25f, 1.9f, c(0.85f, 0.88f, 0.95f), false, 0f, 0f, 0f, "Mattress")
        k.box(0f, 0.75f, -0.75f, 0.9f, 0.15f, 0.4f, WHITE, false, 0f, 0f, 0f, "Pillow")
        k.box(0f, 0.7f, -1.05f, 1.5f, 0.8f, 0.1f, DARKWOOD, false, 0f, 0f, 0f, "Head")
        k.box(0f, 0.68f, 0.4f, 1.42f, 0.1f, 1.1f, c(0.25f, 0.4f, 0.7f), false, 0f, 0f, 0f, "Blanket")
        return k.finish("Bed")
    }

    private fun sofa(scene: Scene, bx: Float, bz: Float): SceneObject? {
        val k = Kit(scene, bx, bz)
        val cl = c(0.35f, 0.38f, 0.6f)
        k.box(0f, 0.3f, 0f, 2.2f, 0.5f, 0.9f, cl, true, 0f, 0f, 0f, "Seat")
        k.box(0f, 0.8f, 0.4f, 2.2f, 0.6f, 0.2f, cl, false, 0f, 0f, 0f, "Back")
        k.box(-1.0f, 0.55f, 0f, 0.2f, 0.5f, 0.9f, c(0.3f, 0.33f, 0.55f), false, 0f, 0f, 0f, "Arm")
        k.box(1.0f, 0.55f, 0f, 0.2f, 0.5f, 0.9f, c(0.3f, 0.33f, 0.55f), false, 0f, 0f, 0f, "Arm")
        return k.finish("Sofa")
    }

    private fun shelf(scene: Scene, bx: Float, bz: Float): SceneObject? {
        val k = Kit(scene, bx, bz)
        k.box(0f, 1f, 0.15f, 1.6f, 2f, 0.04f, DARKWOOD, false, 0f, 0f, 0f, "Back")
        k.box(-0.78f, 1f, 0f, 0.04f, 2f, 0.4f, DARKWOOD, true, 0f, 0f, 0f, "Side")
        k.box(0.78f, 1f, 0f, 0.04f, 2f, 0.4f, DARKWOOD, true, 0f, 0f, 0f, "Side")
        for (i in 0..4) k.box(0f, 0.05f + i * 0.5f, 0f, 1.6f, 0.04f, 0.4f, WOOD, false, 0f, 0f, 0f, "Board")
        val rnd = Random(7L)
        for (i in 0..3) for (j in 0..3) {
            k.box(-0.55f + j * 0.37f, 0.25f + i * 0.5f + 0.0f, 0f, 0.22f, 0.36f, 0.25f,
                c(0.3f + rnd.nextFloat() * 0.6f, 0.3f + rnd.nextFloat() * 0.5f, 0.3f + rnd.nextFloat() * 0.5f), false, 0f, 0f, 0f, "Book")
        }
        return k.finish("Shelf")
    }

    private fun streetLamp(scene: Scene, bx: Float, bz: Float): SceneObject? {
        val k = Kit(scene, bx, bz)
        k.box(0f, 0.15f, 0f, 0.5f, 0.3f, 0.5f, DARKSTONE, true, 0f, 0f, 0f, "Base")
        k.box(0f, 2.5f, 0f, 0.14f, 4.8f, 0.14f, BLACK, false, 0f, 0f, 0f, "Pole")
        k.box(0.4f, 4.9f, 0f, 0.9f, 0.1f, 0.12f, BLACK, false, 0f, 0f, 0f, "Arm")
        k.ball(0.8f, 4.75f, 0f, 0.4f, 0.3f, 0.4f, c(1f, 0.95f, 0.7f), false, 1f, "Bulb")
        return k.finish("Street lamp")
    }

    private fun barrelStack(scene: Scene, bx: Float, bz: Float): SceneObject? {
        val k = Kit(scene, bx, bz)
        val red = c(0.7f, 0.2f, 0.15f)
        for (p in listOf(floatArrayOf(0f, 0f), floatArrayOf(0.9f, 0.1f), floatArrayOf(0.4f, 0.9f))) {
            k.box(p[0], 0.5f, p[1], 0.7f, 1f, 0.7f, red, true, 0f, 0f, 0f, "Barrel")
            k.box(p[0], 0.5f, p[1], 0.7f, 1f, 0.7f, red, false, 45f, 0f, 0f, "Barrel")
            k.box(p[0], 0.25f, p[1], 0.8f, 0.06f, 0.8f, DARKSTONE, false, 22f, 0f, 0f, "Band")
            k.box(p[0], 0.75f, p[1], 0.8f, 0.06f, 0.8f, DARKSTONE, false, 22f, 0f, 0f, "Band")
        }
        k.box(0.45f, 1.5f, 0.3f, 0.7f, 1f, 0.7f, red, true, 0f, 0f, 0f, "Barrel")
        return k.finish("Barrels")
    }

    private fun crateStack(scene: Scene, bx: Float, bz: Float): SceneObject? {
        val k = Kit(scene, bx, bz)
        k.box(0f, 0.5f, 0f, 1f, 1f, 1f, WOOD, true, 0f, 0f, 0f, "Crate")
        k.box(1.05f, 0.5f, 0.1f, 1f, 1f, 1f, c(0.5f, 0.35f, 0.2f), true, 0f, 0f, 0f, "Crate")
        k.box(0.5f, 1.5f, 0.05f, 1f, 1f, 1f, c(0.6f, 0.42f, 0.25f), true, 0f, 0f, 0f, "Crate")
        k.box(-1.05f, 0.5f, -0.1f, 1f, 1f, 1f, c(0.46f, 0.33f, 0.2f), true, 0f, 0f, 0f, "Crate")
        return k.finish("Crates")
    }

    // ================= The menu =================

    val categories: List<Pair<String, List<Item>>> = listOf(
        "Buildings" to listOf(
            Item("House (door, windows, roof)") { s, x, z -> house(s, x, z) },
            Item("Tower (12 m, flag)") { s, x, z -> tower(s, x, z) },
            Item("Bridge (16 m, rails)") { s, x, z -> bridge(s, x, z) },
            Item("Fence (12 m)") { s, x, z -> wallFence(s, x, z) },
            Item("Round wall with gate") { s, x, z -> roundWall(s, x, z) }
        ),
        "Nature" to listOf(
            Item("Rock pile") { s, x, z -> rock(s, x, z) },
            Item("Hill") { s, x, z -> hill(s, x, z) },
            Item("Big tree") { s, x, z -> bigTree(s, x, z) },
            Item("Forest (14 trees)") { s, x, z -> forest(s, x, z) },
            Item("Cliff") { s, x, z -> cliff(s, x, z) },
            Item("Campfire") { s, x, z -> campfire(s, x, z) }
        ),
        "Shapes" to listOf(
            Item("Cylinder") { s, x, z -> cylinder(s, x, z) },
            Item("Cone") { s, x, z -> cone(s, x, z) },
            Item("Arch") { s, x, z -> arch(s, x, z) },
            Item("Stairs (10 steps)") { s, x, z -> stairs(s, x, z) },
            Item("Ziggurat (5 levels)") { s, x, z -> ziggurat(s, x, z) },
            Item("Dome") { s, x, z -> dome(s, x, z) },
            Item("Pillar") { s, x, z -> pillar(s, x, z) }
        ),
        "Vehicles and props" to listOf(
            Item("Car") { s, x, z -> car(s, x, z) },
            Item("Boat") { s, x, z -> boat(s, x, z) },
            Item("Table") { s, x, z -> table(s, x, z) },
            Item("Chair") { s, x, z -> chair(s, x, z) },
            Item("Bed") { s, x, z -> bed(s, x, z) },
            Item("Sofa") { s, x, z -> sofa(s, x, z) },
            Item("Bookshelf") { s, x, z -> shelf(s, x, z) },
            Item("Street lamp") { s, x, z -> streetLamp(s, x, z) },
            Item("Barrels") { s, x, z -> barrelStack(s, x, z) },
            Item("Crates") { s, x, z -> crateStack(s, x, z) }
        )
    )
}
