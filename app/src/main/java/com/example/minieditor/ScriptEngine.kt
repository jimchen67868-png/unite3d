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
import kotlin.math.cos
import kotlin.math.sin

/**
 * Runs each object's Lua script. Scripts may define start() and update(dt).
 * Sandboxed: no io, os, require or Java access.
 */
class ScriptEngine(private val scene: Scene, private val log: (String) -> Unit) {
    private class Inst(val obj: SceneObject, val globals: Globals, val sethook: LuaValue?) {
        var failed = false
    }

    private val tables = LinkedHashMap<SceneObject, LuaTable>()
    private val instances = ArrayList<Inst>()
    private val input = LuaTable()
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
        input.set("joyMoveX", (joyX * c - joyY * s).toDouble())
        input.set("joyMoveZ", (-joyX * s - joyY * c).toDouble())
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

    fun start() {
        for (o in scene.objects) {
            val t = LuaTable()
            push(o, t)
            tables[o] = t
        }
        updateInput()
        for ((o, t) in tables.entries.toList()) {
            if (o.script.isBlank()) continue
            try {
                val g = newGlobals()
                val sethook: LuaValue? = try { g.get("debug").get("sethook") } catch (e: Throwable) { null }
                g.set("debug", LuaValue.NIL)
                g.set("self", t)
                g.set("input", input)
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
                instances.add(inst)
            } catch (e: Throwable) {
                log(o.name + ": " + e.message)
            }
        }
        for (inst in instances.toList()) callFn(inst, "start", null)
        pullAll()
    }

    fun update(dt: Float) {
        time += dt
        for ((o, t) in tables.entries.toList()) push(o, t)
        updateInput()
        for (inst in instances.toList()) {
            inst.globals.set("time", time.toDouble())
            inst.globals.set("dt", dt.toDouble())
            callFn(inst, "update", dt.toDouble())
        }
        pullAll()
    }
}
