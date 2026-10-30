package com.example.minieditor

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

enum class Shape(val label: String, val radius: Float) {
    CUBE("Cube", 0.87f), PYRAMID("Pyramid", 0.75f), SPHERE("Sphere", 0.5f), MODEL("Model", 0.87f),
    JOYSTICK("Joystick", 0f), BUTTON("Button", 0f),
    LIGHT("Light", 0.4f), SUN("Sun", 0.4f),
    SETTINGS("Settings", 0f),
    CAMERA("Camera", 0.4f),
    TERRAIN("Terrain", 0f);

    val isScreen: Boolean get() = this == JOYSTICK || this == BUTTON
    val isLight: Boolean get() = this == LIGHT || this == SUN
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
    var script: String = ""
    var shadow: Boolean = true   // casts a shadow on the ground
    // material
    var spec = 0.25f      // shininess 0-1
    var gloss = 0.5f      // how tight the highlight is 0-1
    var emit = 0f         // glow 0-1
    var opacity = 1f      // 1 = solid, lower = see-through
    var pattern = 0       // 0 none, 1 checker, 2 stripes
    var soft = 0.4f       // Settings: shadow softness 0-1
    var tint = floatArrayOf(0f, 0f, 0f)   // Settings: shadow colour
    var photo: String = ""   // key of an imported photo used as the surface picture
    var heights: FloatArray? = null   // Terrain: 49 x 49 heights, 0..1
    var hVersion = 0                  // bumps whenever the heights change
    var collider: Int = 0   // 0 = none, 1 = solid (blocks), 2 = trigger (events only)
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

    /** Adds the on-screen joystick (x, y = screen position in %, sx = size). Only one is allowed. */
    fun addJoystick(): SceneObject? {
        if (objects.any { it.shape == Shape.JOYSTICK }) return null
        val o = SceneObject(Shape.JOYSTICK, "Joystick", x = 18f, y = 62f, z = 0f, color = floatArrayOf(1f, 1f, 1f))
        objects.add(o)
        selected = o
        return o
    }

    /** On-screen buttons (max 4). The first is named "Jump". Rename a button in the Inspector to change its label. */
    fun addButton(): SceneObject? {
        val n = objects.count { it.shape == Shape.BUTTON }
        if (n >= 4) return null
        val pos = arrayOf(floatArrayOf(85f, 66f), floatArrayOf(70f, 56f), floatArrayOf(88f, 48f), floatArrayOf(58f, 66f))
        val name = if (n == 0) "Jump" else "Button " + (n + 1)
        val o = SceneObject(Shape.BUTTON, name, x = pos[n][0], y = pos[n][1], z = 0f, color = floatArrayOf(1f, 1f, 1f))
        objects.add(o)
        selected = o
        return o
    }

    /**
     * Scene settings (one per scene). x = gravity, y = fog distance (0 = off), z = ambient light 0-1,
     * sx = time scale, sy = show grid (1/0), colour = sky + fog colour.
     */
    fun addSettings(): SceneObject? {
        if (objects.any { it.shape == Shape.SETTINGS }) return null
        val o = SceneObject(Shape.SETTINGS, "Settings", x = 9.8f, y = 0f, z = 0.3f, ry = 0.5f, sx = 1f, sy = 1f,
            color = floatArrayOf(0.15f, 0.16f, 0.19f))
        objects.add(o)
        selected = o
        return o
    }

    /**
     * Camera object (one per scene). x/y/z = position, rx = pitch (positive looks down), ry = yaw, sx = field of view in degrees.
     * While playing (or previewing), the view comes from this object instead of the orbit camera.
     */
    fun addCamera(): SceneObject? {
        if (objects.any { it.shape == Shape.CAMERA }) return null
        val o = SceneObject(Shape.CAMERA, "Camera", x = 0f, y = 5f, z = 10f, rx = 25f, ry = 0f, sx = 60f, sy = 1f,
            color = floatArrayOf(1f, 1f, 1f))
        objects.add(o)
        selected = o
        return o
    }

    fun canAddLight(shape: Shape): Boolean {
        val n = objects.count { it.shape == shape }
        return if (shape == Shape.SUN) n == 0 else n < 4
    }

    /**
     * LIGHT = point light: sx = intensity, sy = range, color = light colour.
     * SUN = directional light: rx/ry = direction, sx = intensity, sy = ambient level. Only one Sun; max 4 point lights.
     */
    fun addLight(shape: Shape): SceneObject? {
        if (!shape.isLight || !canAddLight(shape)) return null
        counter++
        val o = if (shape == Shape.SUN)
            SceneObject(shape, "Sun", x = 0f, y = 6f, z = 0f, rx = 34f, ry = 34f, sx = 1f, sy = 0.3f,
                color = floatArrayOf(1f, 1f, 1f))
        else
            SceneObject(shape, "Light " + counter, x = 0f, y = 3f, z = 0f, sx = 1.5f, sy = 12f,
                color = floatArrayOf(1f, 0.95f, 0.8f))
        objects.add(o)
        selected = o
        return o
    }

    /** Hills you can reshape. Size comes from the scale: width, tallest height, depth. Solid by default. */
    fun addTerrain(): SceneObject {
        counter++
        val o = SceneObject(Shape.TERRAIN, "Terrain", x = 0f, y = 0f, z = 0f, sx = 24f, sy = 3f, sz = 24f,
            color = floatArrayOf(1f, 1f, 1f))
        o.heights = TerrainGen.generate(1, 0.5f)
        o.hVersion = 1
        o.collider = 1
        o.shadow = false
        objects.add(o)
        selected = o
        return o
    }

    /** A thin standing panel showing an imported photo, sized to the photo's shape. */
    fun addPhoto(key: String, aspect: Float): SceneObject {
        val o = add(Shape.CUBE)
        o.name = "Photo " + counter
        val a = aspect.coerceIn(0.2f, 5f)
        val h = if (a >= 1f) 2f else 2.5f
        o.sx = h * a
        o.sy = h
        o.sz = 0.06f
        o.y = h / 2f
        o.photo = key
        o.spec = 0.1f
        return o
    }

    /** A solid 1x1x1 cube sitting on the grid, ready for building. */
    fun addBlock(): SceneObject {
        val o = add(Shape.CUBE)
        o.name = "Block " + counter
        o.x = ((objects.size - 1) % 5 - 2) + 0.5f
        o.y = 0.5f
        o.z = 0.5f
        o.collider = 1
        return o
    }

    fun duplicate(): SceneObject? {
        val s = selected ?: return null
        if (s.shape.isScreen || s.shape == Shape.SETTINGS || s.shape == Shape.CAMERA) return null
        if (s.shape.isLight && !canAddLight(s.shape)) return null
        val o = SceneObject(s.shape, s.name + " copy", s.x + 1f, s.y, s.z, s.rx, s.ry, s.rz,
            s.sx, s.sy, s.sz, s.color.copyOf(), s.model)
        o.script = s.script
        o.collider = s.collider
        o.shadow = s.shadow
        o.photo = s.photo
        o.heights = s.heights?.copyOf()
        o.spec = s.spec; o.gloss = s.gloss; o.emit = s.emit; o.opacity = s.opacity; o.pattern = s.pattern
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
            put("script", o.script)
            put("collider", o.collider)
            put("shadow", o.shadow)
            put("spec", o.spec.toDouble()); put("gloss", o.gloss.toDouble())
            put("emit", o.emit.toDouble()); put("opacity", o.opacity.toDouble()); put("pattern", o.pattern)
            o.heights?.let { put("heights", TerrainGen.encode(it)) }
            put("photo", o.photo); put("soft", o.soft.toDouble()); put("tintR", o.tint[0].toDouble()); put("tintG", o.tint[1].toDouble()); put("tintB", o.tint[2].toDouble())
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
            ).also { it.script = j.optString("script", ""); it.collider = j.optInt("collider", 0); it.shadow = j.optBoolean("shadow", true)
                it.spec = j.optDouble("spec", 0.25).toFloat(); it.gloss = j.optDouble("gloss", 0.5).toFloat()
                it.emit = j.optDouble("emit", 0.0).toFloat(); it.opacity = j.optDouble("opacity", 1.0).toFloat()
                it.pattern = j.optInt("pattern", 0)
                it.soft = j.optDouble("soft", 0.4).toFloat()
                it.photo = j.optString("photo", "")
                it.heights = if (j.has("heights")) TerrainGen.decode(j.getString("heights")) else null
                it.hVersion = 1
                it.tint = floatArrayOf(j.optDouble("tintR", 0.0).toFloat(), j.optDouble("tintG", 0.0).toFloat(), j.optDouble("tintB", 0.0).toFloat()) })
        }
        selected = null
        objects.clear()
        objects.addAll(list)
    }
}
