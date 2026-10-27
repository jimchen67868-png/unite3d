package com.example.minieditor

/** Built-in sample scenes with Lua scripts attached. */
object Samples {
    private val FLOOR = """-- Game manager: 30 second timer
function start()
  last = -1
  over = false
  log("Coin Rush! Joystick or tilt to move. Grab yellow pyramids, dodge red cubes.")
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

    private val PLAYER = """-- Move with the on-screen joystick (bottom left) or tilt the phone flat
function start()
  self.score = 0
  self.frozen = false
end

function update(dt)
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
  self.x = math.max(-5.7, math.min(5.7, self.x + mx * 6 * dt))
  self.z = math.max(-5.7, math.min(5.7, self.z + mz * 6 * dt))
end
"""

    private val COIN = """-- Spins, bobs, and jumps to a new spot when the player touches it
function start()
  phase = math.random() * 6
end

function update(dt)
  self.ry = self.ry + 200 * dt
  self.y = 0.6 + math.sin(time * 4 + phase) * 0.12
  local p = find("Player")
  if p and not p.frozen then
    local dx, dz = p.x - self.x, p.z - self.z
    if dx * dx + dz * dz < 0.55 then
      p.score = (p.score or 0) + 1
      self.x = math.random(-50, 50) / 10
      self.z = math.random(-50, 50) / 10
    end
  end
end
"""

    private val HAZARD = """-- Slides side to side; touching it costs a point and resets the player
function start()
  phase = math.random() * 6
end

function update(dt)
  self.x = math.sin(time * 1.2 + phase) * 4.5
  self.ry = self.ry + 60 * dt
  local p = find("Player")
  if p and not p.frozen then
    local dx, dz = p.x - self.x, p.z - self.z
    if dx * dx + dz * dz < 0.8 then
      p.score = math.max(0, (p.score or 0) - 1)
      p.x = 0
      p.z = 0
      log("Ouch!  Score " .. p.score)
    end
  end
end
"""

    private fun put(
        scene: Scene, shape: Shape, name: String,
        x: Float, y: Float, z: Float, sx: Float, sy: Float, sz: Float,
        color: FloatArray, script: String
    ) {
        val o = scene.add(shape, null, name)
        o.x = x; o.y = y; o.z = z
        o.sx = sx; o.sy = sy; o.sz = sz
        o.color = color
        o.script = script
    }

    /** Collect coins for 30 seconds while dodging hazards. Tilt the phone or touch to move. */
    fun coinRush(scene: Scene) {
        scene.objects.clear()
        scene.selected = null
        put(scene, Shape.CUBE, "Floor", 0f, -0.1f, 0f, 12f, 0.2f, 12f, floatArrayOf(0.25f, 0.45f, 0.30f), FLOOR)
        put(scene, Shape.SPHERE, "Player", 0f, 0.5f, 0f, 1f, 1f, 1f, floatArrayOf(0.30f, 0.55f, 0.90f), PLAYER)
        val spots = arrayOf(
            floatArrayOf(-4f, -4f), floatArrayOf(4f, -4f), floatArrayOf(-4f, 4f), floatArrayOf(4f, 4f),
            floatArrayOf(0f, -4.5f), floatArrayOf(0f, 4.5f), floatArrayOf(-4.5f, 0f), floatArrayOf(4.5f, 0f)
        )
        for (i in spots.indices) {
            put(scene, Shape.PYRAMID, "Coin " + (i + 1), spots[i][0], 0.6f, spots[i][1],
                0.5f, 0.5f, 0.5f, floatArrayOf(0.95f, 0.78f, 0.15f), COIN)
        }
        put(scene, Shape.CUBE, "Hazard 1", 0f, 0.4f, -2.5f, 0.8f, 0.8f, 0.8f, floatArrayOf(0.85f, 0.2f, 0.2f), HAZARD)
        put(scene, Shape.CUBE, "Hazard 2", 0f, 0.4f, 2.5f, 0.8f, 0.8f, 0.8f, floatArrayOf(0.85f, 0.2f, 0.2f), HAZARD)
        scene.selected = null
    }
}
