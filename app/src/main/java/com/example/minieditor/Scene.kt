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
    TERRAIN("Terrain", 0f),
    BONE("Bone", 0.3f),
    WATER("Water", 0f),
    TREE("Tree", 0f),
    GRASS("Grass", 0f),
    FIRE("Fire", 0f),
    SMOKE("Smoke", 0f),
    MIRROR("Mirror", 0.87f);

    val isScreen: Boolean get() = this == JOYSTICK || this == BUTTON
    val isLight: Boolean get() = this == LIGHT || this == SUN
    val isEmitter: Boolean get() = this == FIRE || this == SMOKE
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
    var wind = 0f         // Settings: wind strength 0-1 (trees and grass sway, smoke and flames lean)
    var windDir = 0f      // Settings: direction the wind blows toward, degrees (0 = +X, 90 = +Z)
    var tint = floatArrayOf(0f, 0f, 0f)   // Settings: shadow colour
    var photo: String = ""   // key of an imported photo used as the surface picture
    var heights: FloatArray? = null   // Terrain: 49 x 49 heights, 0..1
    var hVersion = 0                  // bumps whenever the heights change
    var uid = 0                       // stable id so other objects can attach to this one
    var parentId = 0                  // uid of the bone this object is attached to (0 = free)
    var swing = 0f                    // runtime only: degrees this attached object swings about its top end (arms, legs)
    var rel: FloatArray? = null       // position/rotation relative to that bone
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

    /** Flames with sparks of light. Size = sx (width), sy = flame height, Glow/emit = how strongly it lights its surroundings. */
    fun addFire(): SceneObject {
        val o = add(Shape.FIRE)
        o.name = "Fire " + counter
        o.y = 0f
        o.sx = 1f; o.sy = 1.5f
        o.color = floatArrayOf(1f, 1f, 1f)
        o.emit = 1f
        o.shadow = false
        return o
    }

    /** Rising smoke. sx = puff size, sy = how high it climbs, rx = wind push, opacity = how thick. */
    fun addSmoke(): SceneObject {
        val o = add(Shape.SMOKE)
        o.name = "Smoke " + counter
        o.y = 0f
        o.sx = 1f; o.sy = 4f; o.rx = 0.5f
        o.color = floatArrayOf(1f, 1f, 1f)
        o.opacity = 0.5f
        o.shadow = false
        return o
    }

    /** A tree standing on y (its base). pattern 0 = round tree, 1 = pine. Scale = width / height / depth. */
    fun addTree(type: Int): SceneObject {
        val o = add(Shape.TREE)
        o.name = (if (type == 1) "Pine " else "Tree ") + counter
        o.pattern = type
        o.y = 0f
        o.sx = 2f; o.sy = 3f; o.sz = 2f
        o.color = floatArrayOf(0.2f, 0.55f, 0.22f)
        return o
    }

    /** A flat mirror that shows a real reflection of the scene. Size = width/height (X/Y), it faces its local +Z (use Rotation to turn it). */
    fun addMirror(): SceneObject {
        val o = add(Shape.MIRROR)
        o.name = "Mirror " + counter
        o.y = 1.5f
        o.sx = 3f; o.sy = 2.5f; o.sz = 0.05f
        o.color = floatArrayOf(0.9f, 0.95f, 1f)
        return o
    }

    /** A patch of grass: size = scale width/depth, blade height = scale Y, rx = density (1 = normal), y = ground level. */
    fun addGrass(): SceneObject {
        val o = add(Shape.GRASS)
        o.name = "Grass " + counter
        o.x = 0f; o.y = 0f; o.z = 0f
        o.sx = 8f; o.sy = 0.3f; o.sz = 8f
        o.rx = 1f
        o.color = floatArrayOf(1f, 1f, 1f)
        o.shadow = false
        return o
    }

    /**
     * Water with moving waves. Size = scale width/depth, sy = wave height, rx = wave speed, ry = wave direction.
     * Use the Material panel for transparency and shininess, the colour swatches for the tint.
     */
    fun addWater(): SceneObject {
        counter++
        val o = SceneObject(Shape.WATER, "Water", x = 0f, y = 0.5f, z = 0f, rx = 1f, ry = 0f, sx = 24f, sy = 0.15f, sz = 24f,
            color = floatArrayOf(0.12f, 0.45f, 0.70f))
        o.opacity = 0.6f
        o.spec = 1f
        o.gloss = 0.85f
        o.shadow = false
        objects.add(o)
        selected = o
        return o
    }

    /** A joint for rigging. Rotate it to swing everything attached to it. Length (the Y scale) is how long the bone is. */
    fun addBone(): SceneObject {
        val o = add(Shape.BONE)
        o.name = "Bone " + counter
        o.y = 0.5f
        o.sy = 1.5f
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

    private fun canCopy(s: SceneObject): Boolean =
        !(s.shape.isScreen || s.shape == Shape.SETTINGS || s.shape == Shape.CAMERA || isGroup(s))

    private fun cloneOf(s: SceneObject, name: String, dx: Float): SceneObject {
        val o = SceneObject(s.shape, name, s.x + dx, s.y, s.z, s.rx, s.ry, s.rz,
            s.sx, s.sy, s.sz, s.color.copyOf(), s.model)
        o.script = s.script
        o.collider = s.collider
        o.shadow = s.shadow
        o.photo = s.photo
        o.heights = s.heights?.copyOf()
        o.parentId = s.parentId
        o.rel = s.rel?.copyOf()
        o.spec = s.spec; o.gloss = s.gloss; o.emit = s.emit; o.opacity = s.opacity; o.pattern = s.pattern
        o.soft = s.soft; o.tint = s.tint.copyOf()
        return o
    }

    fun duplicate(): SceneObject? {
        val s = selected ?: return null
        if (!canCopy(s)) return null
        if (s.shape.isLight && !canAddLight(s.shape)) return null
        val o = cloneOf(s, s.name + " copy", 1f)
        objects.add(o)
        selected = o
        return o
    }

    // ---------- clipboard ----------
    private var clipboard: SceneObject? = null
    private var pasteCount = 0

    /** Remembers a copy of the selected object. False if nothing copyable is selected. */
    fun copySelected(): Boolean {
        val s = selected ?: return false
        if (!canCopy(s)) return false
        clipboard = cloneOf(s, s.name, 0f)
        pasteCount = 0
        return true
    }

    /** Copy, then remove. */
    fun cutSelected(): Boolean {
        if (!copySelected()) return false
        removeSelected()
        return true
    }

    /** Adds the remembered object, a little further over each time. Null if the clipboard is empty or a light limit is reached. */
    fun paste(): SceneObject? {
        val c = clipboard ?: return null
        if (c.shape.isLight && !canAddLight(c.shape)) return null
        pasteCount++
        val o = cloneOf(c, c.name, pasteCount.toFloat())
        objects.add(o)
        selected = o
        return o
    }

    fun hasClipboard(): Boolean = clipboard != null

    fun removeSelected() {
        val s = selected
        if (s != null) {
            if (isGroup(s)) objects.removeAll(objects.filter { it !== s && Rig.isDescendant(this, it, s) }.toSet())
            objects.remove(s)
        }
        selected = null
        multi.clear()
    }

    /** Deletes every object picked in Multi mode. */
    fun removeMulti() {
        for (o in multi.toList()) {
            if (isGroup(o)) objects.removeAll(objects.filter { it !== o && Rig.isDescendant(this, it, o) }.toSet())
            objects.remove(o)
        }
        multi.clear()
        selected = null
    }

    // ---------- groups ----------
    // A group is an invisible Bone called "Group n": the objects in it are attached to it, so moving or rotating
    // the group moves them all together.

    /** Objects picked in Multi mode (safe to read while drawing). */
    val multi = java.util.concurrent.CopyOnWriteArraySet<SceneObject>()

    fun isGroup(o: SceneObject): Boolean = o.shape == Shape.BONE && o.name.startsWith("Group ")

    /** The outermost group an object belongs to, or the object itself if it is not in a group. */
    fun groupRoot(o: SceneObject): SceneObject {
        var cur = o
        var n = 0
        while (n < 32) {
            val p = Rig.find(this, cur.parentId) ?: break
            if (isGroup(p)) cur = p else break
            n++
        }
        return cur
    }

    /** Puts the given objects into a new group (placed at their centre) and returns it. */
    fun makeGroup(members: List<SceneObject>): SceneObject {
        counter++
        var cx = 0f
        var cy = 0f
        var cz = 0f
        for (m in members) { cx += m.x; cy += m.y; cz += m.z }
        val n = members.size.coerceAtLeast(1)
        val g = SceneObject(Shape.BONE, "Group " + counter, x = cx / n, y = cy / n, z = cz / n, sx = 1f, sy = 1f, sz = 1f,
            color = floatArrayOf(1f, 1f, 1f))
        objects.add(g)
        for (m in members) {
            if (m === g || Rig.isDescendant(this, g, m)) continue
            Rig.bind(m, g)
        }
        return g
    }

    /** Releases the members of a group (they stay where they are) and removes the group itself. */
    fun ungroup(g: SceneObject) {
        if (g.uid != 0) for (o in objects) if (o.parentId == g.uid) Rig.detach(o)
        objects.remove(g)
        if (selected === g) selected = null
        multi.clear()
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
            put("hv", o.hVersion)
            if (o.uid != 0) put("uid", o.uid)
            if (o.parentId != 0) {
                put("parent", o.parentId)
                o.rel?.let { r -> put("rel", JSONArray().also { a -> for (v in r) a.put(v.toDouble()) }) }
            }
            put("wind", o.wind.toDouble()); put("windDir", o.windDir.toDouble())
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
            ).also { it.script = j.optString("script", ""); it.collider = j.optInt("collider", 0); it.shadow = j.optBoolean("shadow", true) || it.shape == Shape.MIRROR   // mirrors saved before they could block light
                it.spec = j.optDouble("spec", 0.25).toFloat(); it.gloss = j.optDouble("gloss", 0.5).toFloat()
                it.emit = j.optDouble("emit", 0.0).toFloat(); it.opacity = j.optDouble("opacity", 1.0).toFloat()
                it.pattern = j.optInt("pattern", 0)
                it.soft = j.optDouble("soft", 0.4).toFloat()
                it.wind = j.optDouble("wind", 0.0).toFloat()
                it.windDir = j.optDouble("windDir", 0.0).toFloat()
                it.photo = j.optString("photo", "")
                it.heights = if (j.has("heights")) TerrainGen.decode(j.getString("heights")) else null
                it.hVersion = j.optInt("hv", 1)
                it.uid = j.optInt("uid", 0)
                it.parentId = j.optInt("parent", 0)
                it.rel = j.optJSONArray("rel")?.let { a -> FloatArray(16) { k -> a.getDouble(k).toFloat() } }
                it.tint = floatArrayOf(j.optDouble("tintR", 0.0).toFloat(), j.optDouble("tintG", 0.0).toFloat(), j.optDouble("tintB", 0.0).toFloat()) })
        }
        Rig.nextUid = maxOf(Rig.nextUid, (list.maxOfOrNull { it.uid } ?: 0) + 1)
        selected = null
        objects.clear()
        objects.addAll(list)
    }
}
