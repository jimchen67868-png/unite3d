package com.example.minieditor

/** Built-in sample scenes with Lua scripts attached. */
object Samples {
    private val FLOOR = """-- Game manager: 30 second timer
function start()
  last = -1
  over = false
  log("Coin Rush! Move, JUMP over red cubes, grab pyramids. VIEW button = 1st person.")
end

function update(dt)
  if over then return end
  local p = find("Player")
  if p == nil then return end
  local left = math.ceil(30 - time)
  if left ~= last then
    last = left
    if left > 0 then
      log("Time " .. left .. "   Score " .. (p.score or 0))
    end
  end
  if time >= 30 then
    over = true
    p.frozen = true
    log("TIME UP!  Final score: " .. (p.score or 0))
  end
end
"""

    private val PLAYER = """-- Joystick or tilt to move, JUMP button to hop.
-- The floor and walls are solid, so this script only needs gravity.
function start()
  self.score = 0
  self.frozen = false
  self.vy = 0
end

function update(dt)
  -- the VIEW button (or a quick tap on the joystick) switches first / third person
  if input.joyTap or input.buttonsPressed["View"] then
    camera.firstPerson = not camera.firstPerson
  end

  if self.frozen then return end
  local mx, mz = input.moveX * 2.5, input.moveZ * 2.5
  if input.joyActive then
    mx, mz = input.joyMoveX, input.joyMoveZ
  end
  local len = math.sqrt(mx * mx + mz * mz)
  if len > 1 then
    mx = mx / len
    mz = mz / len
  end
  self.x = self.x + mx * 6 * dt
  self.z = self.z + mz * 6 * dt

  -- gravity and jumping (self.grounded is set by the collision system)
  if self.grounded then
    if self.vy < 0 then self.vy = 0 end
    if input.jumpPressed then self.vy = 8 end
  end
  self.vy = self.vy - world.gravity * dt
  self.y = self.y + self.vy * dt

  if self.y < -10 then
    self.x = 0
    self.y = 1
    self.z = 0
    self.vy = 0
  end
end
"""

    private val LAMP = """-- A lantern that follows the player
function update(dt)
  local p = find("Player")
  if p then
    self.x = p.x
    self.z = p.z
    self.y = p.y + 2
  end
end
"""

    private val COIN = """-- Trigger: spins and bobs; oncollide runs when something overlaps it
function start()
  phase = math.random() * 6
end

function update(dt)
  self.ry = self.ry + 200 * dt
  self.y = 0.6 + math.sin(time * 4 + phase) * 0.12
end

function oncollide(other)
  if other.name == "Player" and not other.frozen then
    other.score = (other.score or 0) + 1
    self.x = math.random(-50, 50) / 10
    self.z = math.random(-50, 50) / 10
  end
end
"""

    private val HAZARD = """-- Trigger: slides side to side; jump over it or lose a point
function start()
  phase = math.random() * 6
end

function update(dt)
  self.x = math.sin(time * 1.2 + phase) * 4.5
  self.ry = self.ry + 60 * dt
end

function oncollide(other)
  if other.name == "Player" and not other.frozen then
    other.score = math.max(0, (other.score or 0) - 1)
    other.x = 0
    other.y = 1
    other.z = 0
    log("Ouch!  Score " .. other.score)
  end
end
"""

    private fun put(
        scene: Scene, shape: Shape, name: String,
        x: Float, y: Float, z: Float, sx: Float, sy: Float, sz: Float,
        color: FloatArray, script: String, collider: Int = 0
    ) {
        val o = scene.add(shape, null, name)
        o.x = x; o.y = y; o.z = z
        o.sx = sx; o.sy = sy; o.sz = sz
        o.color = color
        o.script = script
        o.collider = collider
    }

    private fun mk(
        scene: Scene, shape: Shape, name: String, x: Float, y: Float, z: Float,
        sx: Float, sy: Float, sz: Float, color: FloatArray, script: String = ""
    ): SceneObject {
        val o = scene.add(shape, null, name)
        o.x = x; o.y = y; o.z = z
        o.sx = sx; o.sy = sy; o.sz = sz
        o.color = color
        o.script = script
        return o
    }

    /** Hills with a lake: floating crates ride the real wave height through waterHeight(). */
    fun lake(scene: Scene) {
        scene.objects.clear()
        scene.selected = null
        scene.addSettings()?.let {
            it.y = 70f; it.z = 0.4f; it.ry = 0f
            it.color = floatArrayOf(0.55f, 0.75f, 0.92f)
        }
        scene.addLight(Shape.SUN)?.let { it.sx = 1.1f; it.sy = 0.35f }
        val land = scene.addTerrain()
        land.sx = 30f; land.sy = 4f; land.sz = 30f
        land.heights = TerrainGen.generate(7, 0.55f)
        land.hVersion++
        val sea = scene.addWater()
        sea.sx = 30f; sea.sz = 30f; sea.y = 1.2f
        val floater = "function update(dt)\n  local wy = waterHeight(self.x, self.z)\n  if wy then\n    self.y = wy + 0.1 + math.sin(time * 2 + self.x) * 0.03\n    self.rz = math.sin(time * 1.5 + self.x) * 8\n    self.rx = math.cos(time * 1.2 + self.z) * 8\n  end\nend\n"
        var made = 0
        var gz = -12f
        while (gz <= 12f && made < 5) {
            var gx = -12f
            while (gx <= 12f && made < 5) {
                val h = TerrainGen.heightAt(land, gx, gz)
                if (h != null && h < sea.y - 0.5f && (gx.toInt() + gz.toInt()) % 2 == 0) {
                    val o = mk(scene, if (made % 2 == 0) Shape.CUBE else Shape.SPHERE, "Floater " + (made + 1),
                        gx, sea.y, gz, 0.7f, 0.7f, 0.7f,
                        floatArrayOf(0.9f - made * 0.1f, 0.45f + made * 0.08f, 0.25f), floater)
                    o.emit = 0.1f
                    made++
                }
                gx += 3f
            }
            gz += 3f
        }
        scene.selected = null
    }

    /** A two-joint arm: rotating a bone swings every part attached to it. Scripts on the bones make it wave. */
    fun robotArm(scene: Scene) {
        scene.objects.clear()
        scene.selected = null
        mk(scene, Shape.CUBE, "Floor", 0f, -0.1f, 0f, 14f, 0.2f, 14f, floatArrayOf(0.35f, 0.38f, 0.42f))
        mk(scene, Shape.CUBE, "Base", 0f, 0.2f, 0f, 1.6f, 0.4f, 1.6f, floatArrayOf(0.25f, 0.28f, 0.33f))

        val shoulder = mk(scene, Shape.BONE, "Shoulder", 0f, 0.4f, 0f, 1f, 2f, 1f, floatArrayOf(1f, 1f, 1f),
            "-- swings the whole arm forward and back\nfunction update(dt)\n  self.rx = math.sin(time * 1.2) * 30\nend\n")
        val upper = mk(scene, Shape.CUBE, "Upper arm", 0f, 1.4f, 0f, 0.4f, 2f, 0.4f, floatArrayOf(0.85f, 0.35f, 0.3f))
        val elbow = mk(scene, Shape.BONE, "Elbow", 0f, 2.4f, 0f, 1f, 2f, 1f, floatArrayOf(1f, 1f, 1f),
            "-- bends the forearm (the elbow is attached to the shoulder, so it follows it)\nfunction update(dt)\n  self.rx = 20 + math.sin(time * 2.4) * 45\nend\n")
        val fore = mk(scene, Shape.CUBE, "Forearm", 0f, 3.4f, 0f, 0.35f, 2f, 0.35f, floatArrayOf(0.3f, 0.6f, 0.9f))
        val hand = mk(scene, Shape.SPHERE, "Hand", 0f, 4.55f, 0f, 0.6f, 0.6f, 0.6f, floatArrayOf(0.95f, 0.8f, 0.25f))

        Rig.bind(upper, shoulder)
        Rig.bind(elbow, shoulder)
        Rig.bind(fore, elbow)
        Rig.bind(hand, elbow)
        scene.selected = null
    }

    /** Collect coins for 30 seconds while dodging hazards. Tilt the phone or touch to move. */
    fun coinRush(scene: Scene) {
        scene.objects.clear()
        scene.selected = null
        put(scene, Shape.CUBE, "Floor", 0f, -0.1f, 0f, 12f, 0.2f, 12f, floatArrayOf(0.25f, 0.45f, 0.30f), FLOOR, 1)
        put(scene, Shape.SPHERE, "Player", 0f, 0.5f, 0f, 1f, 1f, 1f, floatArrayOf(0.30f, 0.55f, 0.90f), PLAYER, 1)
        val spots = arrayOf(
            floatArrayOf(-4f, -4f), floatArrayOf(4f, -4f), floatArrayOf(-4f, 4f), floatArrayOf(4f, 4f),
            floatArrayOf(0f, -4.5f), floatArrayOf(0f, 4.5f), floatArrayOf(-4.5f, 0f), floatArrayOf(4.5f, 0f)
        )
        for (i in spots.indices) {
            put(scene, Shape.PYRAMID, "Coin " + (i + 1), spots[i][0], 0.6f, spots[i][1],
                0.5f, 0.5f, 0.5f, floatArrayOf(0.95f, 0.78f, 0.15f), COIN, 2)
        }
        put(scene, Shape.CUBE, "Hazard 1", 0f, 0.4f, -2.5f, 0.8f, 0.8f, 0.8f, floatArrayOf(0.85f, 0.2f, 0.2f), HAZARD, 2)
        put(scene, Shape.CUBE, "Hazard 2", 0f, 0.4f, 2.5f, 0.8f, 0.8f, 0.8f, floatArrayOf(0.85f, 0.2f, 0.2f), HAZARD, 2)
        for (o in scene.objects) {
            when {
                o.name.startsWith("Coin") -> { o.emit = 0.45f; o.spec = 0.6f }
                o.name == "Player" -> { o.spec = 1f; o.gloss = 0.85f }
                o.name.startsWith("Hazard") -> o.emit = 0.3f
                o.name == "Floor" -> { o.pattern = 1; o.spec = 0f }
            }
        }
        val wall = floatArrayOf(0.45f, 0.35f, 0.30f)
        put(scene, Shape.CUBE, "Wall N", 0f, 0.5f, -6.2f, 12.8f, 1f, 0.4f, wall, "", 1)
        put(scene, Shape.CUBE, "Wall S", 0f, 0.5f, 6.2f, 12.8f, 1f, 0.4f, wall, "", 1)
        put(scene, Shape.CUBE, "Wall W", -6.2f, 0.5f, 0f, 0.4f, 1f, 12f, wall, "", 1)
        put(scene, Shape.CUBE, "Wall E", 6.2f, 0.5f, 0f, 0.4f, 1f, 12f, wall, "", 1)
        scene.addSettings()?.let {
            it.x = 18f; it.y = 45f; it.z = 0.2f
            it.color = floatArrayOf(0.09f, 0.11f, 0.20f)
        }
        scene.addLight(Shape.SUN)?.let { it.sx = 0.45f; it.sy = 0.2f }
        scene.addLight(Shape.LIGHT)?.let { it.y = 2.5f; it.sx = 2.2f; it.sy = 9f; it.script = LAMP }
        scene.addJoystick()
        scene.addButton()
        scene.addButton()?.let { it.name = "View"; it.x = 70f; it.y = 56f; it.sx = 0.8f }
        scene.selected = null
    }
}
