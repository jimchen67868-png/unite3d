package com.example.minieditor

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

enum class Shape(val label: String, val radius: Float) {
    CUBE("Cube", 0.87f), PYRAMID("Pyramid", 0.75f), SPHERE("Sphere", 0.5f), MODEL("Model", 0.87f)
}

/** Imported mesh (x y z nx ny nz per vertex), fitted to a unit box. [key] = file name in app storage. */
class ModelData(val key: String, val data: FloatArray)

class SceneObject(
    val shape: Shape,
    var name: String,
    var x: Float = 0f, var y: Float = 0.5f, var z: Float = 0f,
    var rx: Float = 0f, var ry: Float = 0f, var rz: Float = 0f,
    var sx: Float = 1f, var sy: Float = 1f, var sz: Float = 1f,
    var color: FloatArray,
    val model: ModelData? = null
) {
    val maxScale: Float get() = maxOf(sx, sy, sz)
}

class Scene {
    val objects = CopyOnWriteArrayList<SceneObject>()
    @Volatile var selected: SceneObject? = null

    private val palette = arrayOf(
        floatArrayOf(0.90f, 0.35f, 0.30f), floatArrayOf(0.30f, 0.70f, 0.45f),
        floatArrayOf(0.30f, 0.55f, 0.90f), floatArrayOf(0.95f, 0.75f, 0.25f),
        floatArrayOf(0.70f, 0.45f, 0.85f)
    )
    private var next = 0
    private var counter = 0

    fun add(shape: Shape, model: ModelData? = null, name: String? = null): SceneObject {
        counter++
        val o = SceneObject(
            shape, name ?: (shape.label + " " + counter),
            x = (objects.size % 5 - 2) * 1.5f,
            color = palette[next++ % palette.size].copyOf(),
            model = model
        )
        objects.add(o)
        selected = o
        return o
    }

    fun duplicate(): SceneObject? {
        val s = selected ?: return null
        val o = SceneObject(s.shape, s.name + " copy", s.x + 1f, s.y, s.z, s.rx, s.ry, s.rz,
            s.sx, s.sy, s.sz, s.color.copyOf(), s.model)
        objects.add(o)
        selected = o
        return o
    }

    fun removeSelected() {
        selected?.let { objects.remove(it) }
        selected = null
    }

    fun toJson(): String {
        val arr = JSONArray()
        for (o in objects) arr.put(JSONObject().apply {
            put("shape", o.shape.name)
            put("name", o.name)
            o.model?.let { put("model", it.key) }
            put("x", o.x.toDouble()); put("y", o.y.toDouble()); put("z", o.z.toDouble())
            put("rx", o.rx.toDouble()); put("ry", o.ry.toDouble()); put("rz", o.rz.toDouble())
            put("sx", o.sx.toDouble()); put("sy", o.sy.toDouble()); put("sz", o.sz.toDouble())
            put("r", o.color[0].toDouble()); put("g", o.color[1].toDouble()); put("b", o.color[2].toDouble())
        })
        return arr.toString()
    }

    fun load(json: String, modelFor: (String) -> ModelData?) {
        val arr = JSONArray(json)
        val list = ArrayList<SceneObject>()
        for (i in 0 until arr.length()) {
            val j = arr.getJSONObject(i)
            val shape = Shape.valueOf(j.getString("shape"))
            var model: ModelData? = null
            if (shape == Shape.MODEL) {
                model = modelFor(j.optString("model"))
                if (model == null) continue
            }
            val old = j.optDouble("scale", 1.0)
            list.add(SceneObject(
                shape, j.optString("name", shape.label),
                j.getDouble("x").toFloat(), j.getDouble("y").toFloat(), j.getDouble("z").toFloat(),
                j.getDouble("rx").toFloat(), j.getDouble("ry").toFloat(), j.optDouble("rz", 0.0).toFloat(),
                j.optDouble("sx", old).toFloat(), j.optDouble("sy", old).toFloat(), j.optDouble("sz", old).toFloat(),
                floatArrayOf(j.getDouble("r").toFloat(), j.getDouble("g").toFloat(), j.getDouble("b").toFloat()),
                model
            ))
        }
        selected = null
        objects.clear()
        objects.addAll(list)
    }
}
