package com.example.minieditor

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

enum class Shape(val label: String, val radius: Float) {
    CUBE("Cube", 0.87f), PYRAMID("Pyramid", 0.75f), SPHERE("Sphere", 0.5f)
}

class SceneObject(
    val shape: Shape,
    var x: Float = 0f, var y: Float = 0.5f, var z: Float = 0f,
    var rx: Float = 0f, var ry: Float = 0f,
    var scale: Float = 1f,
    val color: FloatArray
)

class Scene {
    val objects = CopyOnWriteArrayList<SceneObject>()
    @Volatile var selected: SceneObject? = null

    private val palette = arrayOf(
        floatArrayOf(0.90f, 0.35f, 0.30f), floatArrayOf(0.30f, 0.70f, 0.45f),
        floatArrayOf(0.30f, 0.55f, 0.90f), floatArrayOf(0.95f, 0.75f, 0.25f),
        floatArrayOf(0.70f, 0.45f, 0.85f)
    )
    private var next = 0

    fun add(shape: Shape): SceneObject {
        val o = SceneObject(shape, x = (objects.size % 5 - 2) * 1.5f, color = palette[next++ % palette.size])
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
            put("x", o.x.toDouble()); put("y", o.y.toDouble()); put("z", o.z.toDouble())
            put("rx", o.rx.toDouble()); put("ry", o.ry.toDouble())
            put("scale", o.scale.toDouble())
            put("r", o.color[0].toDouble()); put("g", o.color[1].toDouble()); put("b", o.color[2].toDouble())
        })
        return arr.toString()
    }

    fun load(json: String) {
        val arr = JSONArray(json)
        val list = ArrayList<SceneObject>()
        for (i in 0 until arr.length()) {
            val j = arr.getJSONObject(i)
            list.add(SceneObject(
                Shape.valueOf(j.getString("shape")),
                j.getDouble("x").toFloat(), j.getDouble("y").toFloat(), j.getDouble("z").toFloat(),
                j.getDouble("rx").toFloat(), j.getDouble("ry").toFloat(),
                j.getDouble("scale").toFloat(),
                floatArrayOf(j.getDouble("r").toFloat(), j.getDouble("g").toFloat(), j.getDouble("b").toFloat())
            ))
        }
        selected = null
        objects.clear()
        objects.addAll(list)
    }
}
