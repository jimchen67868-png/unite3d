package com.example.minieditor

import org.luaj.vm2.Globals
import org.luaj.vm2.LoadState
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.compiler.LuaC
import org.luaj.vm2.lib.BaseLib
import org.luaj.vm2.lib.DebugLib
import org.luaj.vm2.lib.OneArgFunction
import org.luaj.vm2.lib.PackageLib
import org.luaj.vm2.lib.StringLib
import org.luaj.vm2.lib.TableLib
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.lib.ZeroArgFunction
import org.luaj.vm2.lib.jse.JseMathLib
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Runs each object's Lua script. Scripts may define start() and update(dt).
 * Sandboxed: no io, os, require or Java access.
 */
class ScriptEngine(private val scene: Scene, private val log: (String) -> Unit) {
    private class Inst(val obj: SceneObject, val globals: Globals, val sethook: LuaValue?) {
        var failed = false
        var onCollide: LuaValue? = null
    }

    private val tables = LinkedHashMap<SceneObject, LuaTable>()
    private val instances = ArrayList<Inst>()
    private val input = LuaTable()
    private val instByObj = HashMap<SceneObject, Inst>()
    private val prev = HashMap<SceneObject, FloatArray>()
    private val grounded = HashSet<SceneObject>()
    private val world = LuaTable()
    private val camera = LuaTable()
    private val buttonsT = LuaTable()
    private val pressedT = LuaTable()
    private val camPushed = FloatArray(2)
    private var fpPushed = false
    private val worldPushed = FloatArray(4)
    private var time = 0f
    private var spawnCount = 0
    private val maxObjects = 500

    var touching = false
    var touchX = 0f
    var touchY = 0f
    var ax = 0f
    var ay = 0f
    var az = 0f
    var camYaw = 45f
    var joyX = 0f
    var joyY = 0f
    var joyActive = false
    var jump = false
    var jumpPressed = false
    var joyTap = false
    var buttonsHeld: Map<String, Boolean> = emptyMap()
    var buttonsPressed: Map<String, Boolean> = emptyMap()
    var camPitch = 30f
    var fpOn = false
    var camTargetName = "Player"
    var fpRequest: Boolean? = null
    var yawOut: Float? = null
    var pitchOut: Float? = null

    private val hookFn: LuaValue = object : ZeroArgFunction() {
        override fun call(): LuaValue {
            throw LuaError("script ran too long (infinite loop?)")
        }
    }

    private val findFn = object : OneArgFunction() {
        override fun call(arg: LuaValue): LuaValue {
            val n = arg.tojstring()
            for ((o, t) in tables) if (o.name == n) return t
            return LuaValue.NIL
        }
    }

    private val logFn = object : VarArgFunction() {
        override fun invoke(args: Varargs): Varargs {
            val sb = StringBuilder()
            for (i in 1..args.narg()) {
                if (i > 1) sb.append('\t')
                sb.append(args.arg(i).tojstring())
            }
            log(sb.toString())
            return LuaValue.NONE
        }
    }

    private val spawnFn = object : VarArgFunction() {
        override fun invoke(args: Varargs): Varargs {
            val shape = when (args.arg(1).tojstring().lowercase()) {
                "cube" -> Shape.CUBE
                "pyramid" -> Shape.PYRAMID
                "sphere" -> Shape.SPHERE
                else -> throw LuaError("spawn: shape must be \"cube\", \"pyramid\" or \"sphere\"")
            }
            if (scene.objects.size >= maxObjects) throw LuaError("spawn: object limit reached")
            spawnCount++
            val o = SceneObject(
                shape, shape.label + " (spawned " + spawnCount + ")",
                args.optdouble(2, 0.0).toFloat(), args.optdouble(3, 0.0).toFloat(), args.optdouble(4, 0.0).toFloat(),
                color = floatArrayOf(0.8f, 0.8f, 0.8f)
            )
            val t = LuaTable()
            push(o, t)
            scene.objects.add(o)
            tables[o] = t
            return t
        }
    }

    private val destroyFn = object : OneArgFunction() {
        override fun call(arg: LuaValue): LuaValue {
            for ((o, t) in tables.entries.toList()) {
                if (t === arg) {
                    tables.remove(o)
                    scene.objects.remove(o)
                    if (scene.selected === o) scene.selected = null
                    for (inst in instances) if (inst.obj === o) inst.failed = true
                    break
                }
            }
            return LuaValue.NIL
        }
    }

    private fun newGlobals(): Globals {
        val g = Globals()
        g.load(BaseLib())
        g.load(PackageLib())
        g.load(TableLib())
        g.load(StringLib())
        g.load(JseMathLib())
        g.load(DebugLib())
        LoadState.install(g)
        LuaC.install(g)
        g.set("dofile", LuaValue.NIL)
        g.set("require", LuaValue.NIL)
        g.set("package", LuaValue.NIL)
        g.set("loadfile", LuaValue.NIL)
        return g
    }

    private fun guard(inst: Inst) {
        val h = inst.sethook ?: return
        try {
            h.call(hookFn, LuaValue.valueOf(""), LuaValue.valueOf(1000000))
        } catch (e: Throwable) {
        }
    }

    private fun push(o: SceneObject, t: LuaTable) {
        t.set("name", o.name)
        t.set("x", o.x.toDouble()); t.set("y", o.y.toDouble()); t.set("z", o.z.toDouble())
        t.set("rx", o.rx.toDouble()); t.set("ry", o.ry.toDouble()); t.set("rz", o.rz.toDouble())
        t.set("sx", o.sx.toDouble()); t.set("sy", o.sy.toDouble()); t.set("sz", o.sz.toDouble())
        t.set("r", o.color[0].toDouble()); t.set("g", o.color[1].toDouble()); t.set("b", o.color[2].toDouble())
        t.set("collider", o.collider.toDouble())
    }

    private fun num(t: LuaTable, k: String): Float? {
        val v = t.get(k)
        if (!v.isnumber()) return null
        val f = v.tofloat()
        return if (f.isNaN() || f.isInfinite()) null else f
    }

    private fun pull(o: SceneObject, t: LuaTable) {
        num(t, "x")?.let { o.x = it }
        num(t, "y")?.let { o.y = it }
        num(t, "z")?.let { o.z = it }
        num(t, "rx")?.let { o.rx = it }
        num(t, "ry")?.let { o.ry = it }
        num(t, "rz")?.let { o.rz = it }
        num(t, "sx")?.let { o.sx = it.coerceIn(0.01f, 100f) }
        num(t, "sy")?.let { o.sy = it.coerceIn(0.01f, 100f) }
        num(t, "sz")?.let { o.sz = it.coerceIn(0.01f, 100f) }
        num(t, "collider")?.let { o.collider = it.toInt().coerceIn(0, 2) }
        o.color = floatArrayOf(
            (num(t, "r") ?: o.color[0]).coerceIn(0f, 1f),
            (num(t, "g") ?: o.color[1]).coerceIn(0f, 1f),
            (num(t, "b") ?: o.color[2]).coerceIn(0f, 1f)
        )
    }

    private fun pullAll() { for ((o, t) in tables.entries.toList()) pull(o, t) }

    private fun updateInput() {
        input.set("touching", LuaValue.valueOf(touching))
        input.set("x", touchX.toDouble())
        input.set("y", touchY.toDouble())
        val g = 9.81f
        val tx = (-ax / g).coerceIn(-1f, 1f)
        val ty = (-ay / g).coerceIn(-1f, 1f)
        val yaw = Math.toRadians(camYaw.toDouble())
        val c = cos(yaw).toFloat()
        val s = sin(yaw).toFloat()
        input.set("ax", ax.toDouble()); input.set("ay", ay.toDouble()); input.set("az", az.toDouble())
        input.set("yaw", camYaw.toDouble())
        input.set("tiltX", tx.toDouble()); input.set("tiltY", ty.toDouble())
        input.set("moveX", (tx * c - ty * s).toDouble())
        input.set("moveZ", (-tx * s - ty * c).toDouble())
        input.set("joyX", joyX.toDouble()); input.set("joyY", joyY.toDouble())
        input.set("joyActive", LuaValue.valueOf(joyActive))
        input.set("jump", LuaValue.valueOf(jump))
        input.set("jumpPressed", LuaValue.valueOf(jumpPressed))
        input.set("joyMoveX", (joyX * c - joyY * s).toDouble())
        input.set("joyMoveZ", (-joyX * s - joyY * c).toDouble())
        input.set("joyTap", LuaValue.valueOf(joyTap))
        for ((k, v) in buttonsHeld) buttonsT.set(k, LuaValue.valueOf(v))
        for ((k, v) in buttonsPressed) pressedT.set(k, LuaValue.valueOf(v))
        input.set("buttons", buttonsT)
        input.set("buttonsPressed", pressedT)
        pushCamera()
    }

    private fun callFn(inst: Inst, name: String, dt: Double?) {
        if (inst.failed) return
        try {
            val f = inst.globals.get(name)
            if (!f.isfunction()) return
            guard(inst)
            if (dt == null) f.call() else f.call(LuaValue.valueOf(dt))
        } catch (e: Throwable) {
            inst.failed = true
            log(inst.obj.name + " (" + name + "): " + e.message)
        }
    }

    /** camera.firstPerson, camera.target (object name), camera.yaw, camera.pitch. Changes are applied after each frame. */
    private fun pushCamera() {
        camPushed[0] = camYaw
        camPushed[1] = camPitch
        fpPushed = fpOn
        camera.set("firstPerson", LuaValue.valueOf(fpOn))
        camera.set("target", camTargetName)
        camera.set("yaw", camYaw.toDouble())
        camera.set("pitch", camPitch.toDouble())
    }

    private fun pullCamera() {
        val f = camera.get("firstPerson")
        if (f.isboolean() && f.toboolean() != fpPushed) fpRequest = f.toboolean()
        val t = camera.get("target")
        if (t.isstring()) camTargetName = t.tojstring()
        num(camera, "yaw")?.let { if (it != camPushed[0]) yawOut = it }
        num(camera, "pitch")?.let { if (it != camPushed[1]) pitchOut = it }
    }

    private fun settingsObj() = scene.objects.firstOrNull { it.shape == Shape.SETTINGS }

    /** world.gravity, world.fog, world.ambient, world.timeScale mirror the Settings object (defaults if none). */
    private fun pushWorld() {
        val s = settingsObj()
        worldPushed[0] = s?.x ?: 9.8f
        worldPushed[1] = s?.y ?: 0f
        worldPushed[2] = s?.z ?: 0.3f
        worldPushed[3] = s?.sx ?: 1f
        world.set("gravity", worldPushed[0].toDouble())
        world.set("fog", worldPushed[1].toDouble())
        world.set("ambient", worldPushed[2].toDouble())
        world.set("timeScale", worldPushed[3].toDouble())
    }

    private fun pullWorld() {
        val s = settingsObj() ?: return
        num(world, "gravity")?.let { if (it != worldPushed[0]) s.x = it.coerceIn(0f, 100f) }
        num(world, "fog")?.let { if (it != worldPushed[1]) s.y = it.coerceIn(0f, 500f) }
        num(world, "ambient")?.let { if (it != worldPushed[2]) s.z = it.coerceIn(0f, 1f) }
        num(world, "timeScale")?.let { if (it != worldPushed[3]) s.sx = it.coerceIn(0f, 5f) }
    }

    /** Box overlap per axis (positive = penetration). null if any axis is separated by more than -eps. */
    private fun overlap(a: SceneObject, b: SceneObject, eps: Float): FloatArray? {
        val ox = 0.5f * (a.sx + b.sx) - abs(a.x - b.x)
        val oy = 0.5f * (a.sy + b.sy) - abs(a.y - b.y)
        val oz = 0.5f * (a.sz + b.sz) - abs(a.z - b.z)
        return if (ox > eps && oy > eps && oz > eps) floatArrayOf(ox, oy, oz) else null
    }

    private fun fire(a: SceneObject, b: SceneObject) {
        val inst = instByObj[a] ?: return
        val h = inst.onCollide ?: return
        val tb = tables[b] ?: return
        if (inst.failed) return
        try {
            guard(inst)
            h.call(tb)
        } catch (e: Throwable) {
            inst.failed = true
            log(a.name + " (oncollide): " + e.message)
        }
    }

    /**
     * Solid objects that moved this frame are pushed out of other solid objects (axis-aligned boxes sized by scale;
     * rotation is ignored). Triggers and solids call oncollide(other). Sets self.grounded for objects standing on something.
     */
    private fun collide() {
        val st = settingsObj()
        if (st != null && st.sz < 0.5f) return
        val cols = ArrayList<SceneObject>()
        for (o in tables.keys) {
            if (o.collider != 0 && !o.shape.isScreen && !o.shape.isLight && o.shape != Shape.SETTINGS) cols.add(o)
        }
        val n = cols.size
        if (n < 2) return
        val moved = BooleanArray(n) { i ->
            val o = cols[i]
            val p = prev[o]
            p == null || abs(o.x - p[0]) > 1e-5f || abs(o.y - p[1]) > 1e-5f || abs(o.z - p[2]) > 1e-5f
        }
        grounded.clear()
        repeat(2) {
            for (i in 0 until n) {
                val a = cols[i]
                if (a.collider != 1 || !moved[i]) continue
                for (j in 0 until n) {
                    if (i == j) continue
                    val b = cols[j]
                    if (b.collider != 1) continue
                    val ov = overlap(a, b, 0f) ?: continue
                    val k = if (moved[j]) 0.5f else 1f
                    val dx = a.x - b.x
                    val dy = a.y - b.y
                    val dz = a.z - b.z
                    if (ov[0] <= ov[1] && ov[0] <= ov[2]) {
                        a.x += (if (dx >= 0f) 1f else -1f) * ov[0] * k
                    } else if (ov[1] <= ov[2]) {
                        a.y += (if (dy >= 0f) 1f else -1f) * ov[1] * k
                        if (dy >= 0f) grounded.add(a)
                    } else {
                        a.z += (if (dz >= 0f) 1f else -1f) * ov[2] * k
                    }
                }
            }
        }
        for ((o, t) in tables.entries.toList()) {
            push(o, t)
            if (o.collider != 0) t.set("grounded", LuaValue.valueOf(grounded.contains(o)))
        }
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                if (!moved[i] && !moved[j]) continue
                if (overlap(cols[i], cols[j], -0.002f) == null) continue
                fire(cols[i], cols[j])
                fire(cols[j], cols[i])
            }
        }
        pullAll()
    }

    fun start() {
        for (o in scene.objects) {
            val t = LuaTable()
            push(o, t)
            tables[o] = t
        }
        updateInput()
        pushWorld()
        for (t in tables.values) t.set("grounded", LuaValue.FALSE)
        for ((o, t) in tables.entries.toList()) {
            if (o.script.isBlank()) continue
            try {
                val g = newGlobals()
                val sethook: LuaValue? = try { g.get("debug").get("sethook") } catch (e: Throwable) { null }
                g.set("debug", LuaValue.NIL)
                g.set("self", t)
                g.set("input", input)
                g.set("world", world)
                g.set("camera", camera)
                g.set("find", findFn)
                g.set("spawn", spawnFn)
                g.set("destroy", destroyFn)
                g.set("log", logFn)
                g.set("print", logFn)
                g.set("time", 0.0)
                g.set("dt", 0.0)
                val inst = Inst(o, g, sethook)
                guard(inst)
                g.load(o.script, o.name).call()
                inst.onCollide = g.get("oncollide").let { if (it.isfunction()) it else null }
                instByObj[o] = inst
                instances.add(inst)
            } catch (e: Throwable) {
                log(o.name + ": " + e.message)
            }
        }
        for (inst in instances.toList()) callFn(inst, "start", null)
        pullAll()
        pullWorld()
        pullCamera()
    }

    fun update(rawDt: Float) {
        val dt = rawDt * (settingsObj()?.sx ?: 1f).coerceIn(0f, 5f)
        time += dt
        for ((o, _) in tables) {
            val p = prev.getOrPut(o) { FloatArray(3) }
            p[0] = o.x; p[1] = o.y; p[2] = o.z
        }
        for ((o, t) in tables.entries.toList()) push(o, t)
        updateInput()
        pushWorld()
        for (inst in instances.toList()) {
            inst.globals.set("time", time.toDouble())
            inst.globals.set("dt", dt.toDouble())
            callFn(inst, "update", dt.toDouble())
        }
        pullAll()
        pullWorld()
        pullCamera()
        collide()
    }
}
