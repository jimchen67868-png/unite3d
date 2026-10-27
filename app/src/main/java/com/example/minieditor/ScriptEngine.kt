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
import org.luaj.vm2.lib.StringLib
import org.luaj.vm2.lib.TableLib
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.lib.ZeroArgFunction
import org.luaj.vm2.lib.jse.JseMathLib

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

    var touching = false
    var touchX = 0f
    var touchY = 0f

    private val hookFn: LuaValue = object : ZeroArgFunction() {
        override fun call(): LuaValue {
            throw LuaError("script ran too long (infinite loop?)")
        }
    }

    private fun newGlobals(): Globals {
        val g = Globals()
        g.load(BaseLib())
        g.load(TableLib())
        g.load(StringLib())
        g.load(JseMathLib())
        g.load(DebugLib())
        LoadState.install(g)
        LuaC.install(g)
        g.set("dofile", LuaValue.NIL)
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

    private fun pullAll() { for ((o, t) in tables) pull(o, t) }

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
        val find = object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                val n = arg.tojstring()
                for ((o, t) in tables) if (o.name == n) return t
                return LuaValue.NIL
            }
        }
        val logFn = object : VarArgFunction() {
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
        for ((o, t) in tables) {
            if (o.script.isBlank()) continue
            try {
                val g = newGlobals()
                val sethook: LuaValue? = try { g.get("debug").get("sethook") } catch (e: Throwable) { null }
                g.set("debug", LuaValue.NIL)
                g.set("self", t)
                g.set("input", input)
                g.set("find", find)
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
        for (inst in instances) callFn(inst, "start", null)
        pullAll()
    }

    fun update(dt: Float) {
        time += dt
        for ((o, t) in tables) push(o, t)
        input.set("touching", LuaValue.valueOf(touching))
        input.set("x", touchX.toDouble())
        input.set("y", touchY.toDouble())
        for (inst in instances) {
            inst.globals.set("time", time.toDouble())
            inst.globals.set("dt", dt.toDouble())
            callFn(inst, "update", dt.toDouble())
        }
        pullAll()
    }
}
