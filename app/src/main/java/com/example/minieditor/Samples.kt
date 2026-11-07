package com.example.minieditor

/** Built-in sample scenes with Lua scripts attached. */
object Samples {

    private val BR_PLAYER = """-- BATTLE ROYALE player.
-- Joystick = move, drag the screen = look, hold FIRE = shoot, RELOAD, JUMP, VIEW = 1st / 3rd person.
local NBOTS = 8
STAIRS = {}

function start()
  self.hp = 100
  self.dead = false
  self.vy = 0
  self.reserveAdd = 0
  fx = {}
  findParts()
  aimT = 0
  fpWas = nil
  deadT = 0
  swimming = false
  diveDepth = 0
  breath = 20
  lastB = 20
  underT = 0
  wasUnder = false
  bubbleT = 0
  bubbleSndT = 0
  uwK = 0
  rippleT = 0
  strokeT = 0
  hurtSndT = 0
  mag = 30
  reserve = 90
  reloadT = 0
  fireT = 0
  bullets = {}
  walls = {}
  grid = {}
  local i = 1
  while true do
    local w = find("Wall " .. i)
    if w == nil then break end
    walls[i] = w
    addWall(w)
    i = i + 1
  end
  bots = {}
  for k = 1, NBOTS do bots[k] = find("Bot " .. k) end
  zone = find("Zone")
  home = { x = self.x, z = self.z }
  lastHp = 100
  statT = 8
  over = false
end

function countAlive()
  local n = 0
  for k = 1, NBOTS do
    local e = bots[k]
    if e ~= nil and not e.dead and e.hp > 0 then n = n + 1 end
  end
  return n
end

-- walls are put into a coarse grid once, so a bullet only tests the few walls near it
CELL = 6

function addWall(w)
  local e = { x = w.x, y = w.y, z = w.z, hx = w.sx / 2, hy = w.sy / 2, hz = w.sz / 2 }
  local x0 = math.floor((e.x - e.hx) / CELL)
  local x1 = math.floor((e.x + e.hx) / CELL)
  local z0 = math.floor((e.z - e.hz) / CELL)
  local z1 = math.floor((e.z + e.hz) / CELL)
  for cx = x0, x1 do
    for cz = z0, z1 do
      local k = cx * 100003 + cz
      local c = grid[k]
      if c == nil then
        c = {}
        grid[k] = c
      end
      c[#c + 1] = e
    end
  end
end

function pointInWall(x, y, z)
  local c = grid[math.floor(x / CELL) * 100003 + math.floor(z / CELL)]
  if c == nil then return false end
  for i = 1, #c do
    local w = c[i]
    if math.abs(x - w.x) < w.hx and math.abs(y - w.y) < w.hy and math.abs(z - w.z) < w.hz then
      return true
    end
  end
  return false
end

-- moves a bullet in small steps so it cannot jump through a thin wall; true if it hit one
function moveBullet(b, dt)
  local dx, dy, dz = b.vx * dt, b.vy * dt, b.vz * dt
  local n = math.ceil(math.sqrt(dx * dx + dy * dy + dz * dz) / 0.2)
  if n < 1 then n = 1 end
  if n > 40 then n = 40 end
  local sx, sy, sz = dx / n, dy / n, dz / n
  local x, y, z = b.x, b.y, b.z
  for s = 1, n do
    x = x + sx
    y = y + sy
    z = z + sz
    if pointInWall(x, y, z) then
      b.x, b.y, b.z = x, y, z
      return true
    end
  end
  b.x, b.y, b.z = x, y, z
  return false
end

-- true when no wall is between the two points
function lineClear(x0, y0, z0, x1, y1, z1)
  local dx, dy, dz = x1 - x0, y1 - y0, z1 - z0
  local n = math.ceil(math.sqrt(dx * dx + dy * dy + dz * dz) / 0.2)
  for s = 1, n - 1 do
    local t = s / n
    if pointInWall(x0 + dx * t, y0 + dy * t, z0 + dz * t) then return false end
  end
  return true
end

-- where the crosshair points, with a gentle snap onto an enemy that is almost in line
function aimDir()
  local yaw = math.rad(input.yaw)
  local p = 0
  if camera.firstPerson then p = math.rad(camera.pitch) end
  local fx = -math.cos(p) * math.sin(yaw)
  local fy = -math.sin(p)
  local fz = -math.cos(p) * math.cos(yaw)
  local best = nil
  local bd = 0.97
  for k = 1, NBOTS do
    local e = bots[k]
    if e ~= nil and not e.dead and e.hp > 0 then
      local dx, dy, dz = e.x - self.x, e.y - self.y, e.z - self.z
      local d = math.sqrt(dx * dx + dy * dy + dz * dz)
      if d > 0.5 and d < 60 then
        local dot = (dx * fx + dy * fy + dz * fz) / d
        if dot > bd then
          bd = dot
          best = e
        end
      end
    end
  end
  if best ~= nil then
    local dx, dy, dz = best.x - self.x, best.y - self.y, best.z - self.z
    local d = math.sqrt(dx * dx + dy * dy + dz * dz)
    return dx / d, dy / d, dz / d
  end
  return fx, fy, fz
end

function shoot()
  mag = mag - 1
  fireT = 0.12
  aimT = 0.7
  playSound("shot", 0.7)
  local fx, fy, fz = aimDir()
  local ok, b = pcall(spawn, "sphere", self.x + fx * 1.2, self.y + 0.5 + fy * 1.2, self.z + fz * 1.2)
  if not ok then return end
  b.sx = 0.2
  b.sy = 0.2
  b.sz = 0.2
  b.r = 1
  b.g = 0.85
  b.b = 0.2
  b.emit = 1
  b.vx = fx * 80
  b.vy = fy * 80
  b.vz = fz * 80
  b.life = 1.4
  bullets[#bullets + 1] = b
end

function updateBullets(dt)
  for i = #bullets, 1, -1 do
    local b = bullets[i]
    local ox, oy, oz = b.x, b.y, b.z
    local hit = moveBullet(b, dt)
    b.life = b.life - dt
    local gone = b.life <= 0 or hit or b.y < groundHeight(b.x, b.z) - 0.1
    if not gone then
      local ex, ez = b.x - ox, b.z - oz
      local seg2 = ex * ex + ez * ez + 0.0001
      for k = 1, NBOTS do
        local e = bots[k]
        if e ~= nil and not e.dead and e.hp > 0 then
          -- closest point of this frame's path to the enemy (a fast bullet must not skip over it)
          local t = ((e.x - ox) * ex + (e.z - oz) * ez) / seg2
          if t < 0 then t = 0 elseif t > 1 then t = 1 end
          local dx, dz = e.x - (ox + ex * t), e.z - (oz + ez * t)
          local dy = e.y - (oy + (b.y - oy) * t)
          if dx * dx + dz * dz < 0.9 and math.abs(dy) < 1.1 then
            e.hp = e.hp - 25
            playSound("hit", 0.7)
            gone = true
            if e.hp <= 0 then log("Enemy down!  " .. countAlive() .. " left") end
            break
          end
        end
      end
    end
    if gone then
      destroy(b)
      table.remove(bullets, i)
    end
  end
end

-- ===== watchtower stairs: the floor height under (x, z), or nil when there is no stair there =====
function stairAt(px, pz)
  for i = 1, #STAIRS do
    local st = STAIRS[i]
    if px >= st.x0 and px <= st.x1 and pz >= st.z0 and pz <= st.z1 then
      local k = math.ceil((st.z1 - pz) / st.tread)
      if k < 0 then k = 0 end
      if k > st.n then k = st.n end
      return st.base + k * st.rise
    end
  end
  return nil
end

function stairFloor(x, z)
  local best = stairAt(x, z)
  local r = 0.4
  local a = stairAt(x + r, z)
  if a ~= nil and (best == nil or a > best) then best = a end
  a = stairAt(x - r, z)
  if a ~= nil and (best == nil or a > best) then best = a end
  a = stairAt(x, z + r)
  if a ~= nil and (best == nil or a > best) then best = a end
  a = stairAt(x, z - r)
  if a ~= nil and (best == nil or a > best) then best = a end
  return best
end

-- ===== the person: find the parts (they follow this body) and swing the arms and legs =====
walkT = 0
legAmp = 0

function findParts()
  local n = self.name
  P = {
    head = find(n .. " head"), helm = find(n .. " helm"), torso = find(n .. " torso"), pack = find(n .. " pack"),
    armL = find(n .. " armL"), armR = find(n .. " armR"), legL = find(n .. " legL"), legR = find(n .. " legR"),
    gun = find(n .. " gun")
  }
end

-- walking: legs and arms swing; aiming: arms come up with the rifle
function walkPose(dt, moving, speed, aiming)
  if moving then walkT = walkT + dt * speed * 1.7 end
  local want = 0
  if moving then want = 38 end
  legAmp = legAmp + (want - legAmp) * math.min(1, dt * 8)
  local s = math.sin(walkT)
  P.legL.swing = legAmp * s
  P.legR.swing = -legAmp * s
  local base = 55
  if aiming then base = 78 end
  P.armL.swing = base + legAmp * 0.12 * s
  P.armR.swing = base - legAmp * 0.12 * s
end

-- lying down when shot: the body tips over backwards and sinks to the ground
function fallPose(t, groundY)
  local k = math.min(1, t / 0.6)
  self.rx = 90 * k
  self.y = groundY + 0.9 - 0.7 * k
  P.legL.swing = 10 * k
  P.legR.swing = -10 * k
  P.armL.swing = 55 + 80 * k
  P.armR.swing = 55 + 80 * k
end

function removeBody()
  for k, part in pairs(P) do
    if part ~= nil then destroy(part) end
  end
  destroy(self)
end

-- ===== water effects: ripples and splash drops (little see-through spheres) =====
function fxSpawn(x, y, z, sx, sy, sz, r, g, b, op)
  if #fx >= 40 then return nil end
  local ok, o = pcall(spawn, "sphere", x, y, z)
  if not ok then return nil end
  o.sx = sx
  o.sy = sy
  o.sz = sz
  o.r = r
  o.g = g
  o.b = b
  o.opacity = op
  o.shadow = false
  o.emit = 0.2
  return o
end

function ripple(x, y, z)
  local o = fxSpawn(x, y, z, 0.8, 0.05, 0.8, 0.8, 0.92, 1, 0.55)
  if o ~= nil then
    o.kind = "ripple"
    o.life = 0.9
    o.age = 0
    fx[#fx + 1] = o
  end
end

function splash(x, y, z, n)
  for i = 1, n do
    local o = fxSpawn(x, y, z, 0.18, 0.18, 0.18, 0.75, 0.9, 1, 0.8)
    if o ~= nil then
      local a = math.random() * 6.28
      local sp = 1 + math.random() * 2.5
      o.kind = "drop"
      o.vx = math.cos(a) * sp
      o.vz = math.sin(a) * sp
      o.vy = 3 + math.random() * 3
      o.life = 0.8
      o.baseY = y
      fx[#fx + 1] = o
    end
  end
  ripple(x, y + 0.03, z)
end

function bubble(x, y, z, surface)
  local o = fxSpawn(x, y, z, 0.12, 0.12, 0.12, 0.85, 0.95, 1, 0.6)
  if o ~= nil then
    o.kind = "bubble"
    o.vy = 1.2 + math.random() * 0.8
    o.life = 3
    o.surf = surface
    o.ph = math.random() * 6
    fx[#fx + 1] = o
  end
end

function updateFx(dt)
  for i = #fx, 1, -1 do
    local o = fx[i]
    o.life = o.life - dt
    if o.kind == "ripple" then
      o.age = o.age + dt
      local k = o.age / 0.9
      o.sx = 0.8 + k * 3.5
      o.sz = o.sx
      o.opacity = 0.55 * (1 - k)
    elseif o.kind == "bubble" then
      o.y = o.y + o.vy * dt
      o.x = o.x + math.sin(time * 5 + o.ph) * 0.25 * dt
      o.opacity = 0.6 * math.min(1, o.life / 0.5)
    else
      o.vy = o.vy - 12 * dt
      o.x = o.x + o.vx * dt
      o.y = o.y + o.vy * dt
      o.z = o.z + o.vz * dt
      o.opacity = math.max(0, o.life / 0.8) * 0.8
    end
    if o.life <= 0 or (o.kind == "drop" and o.y < o.baseY - 0.1) or (o.kind == "bubble" and o.y >= o.surf) then
      destroy(o)
      table.remove(fx, i)
    end
  end
end

function update(dt)
  if input.joyTap or input.buttonsPressed["View"] then
    camera.firstPerson = not camera.firstPerson
  end
  updateBullets(dt)
  updateFx(dt)
  if self.dead then
    deadT = deadT + dt
    if camera.firstPerson then camera.firstPerson = false end
    fallPose(deadT, deadG or groundHeight(self.x, self.z))
    return
  end

  -- move
  local mx, mz = 0, 0
  if input.joyActive then mx, mz = input.joyMoveX, input.joyMoveZ end
  local len = math.sqrt(mx * mx + mz * mz)
  if len > 1 then
    mx = mx / len
    mz = mz / len
  end
  -- water deeper than a metre: you swim on the surface (no jumping, no shooting)
  local wy = self.waterY
  local ground = groundHeight(self.x, self.z)
  local depth = 0
  if wy ~= nil then depth = wy - ground end
  local wasSwim = swimming
  if depth > 1.1 then swimming = true elseif depth < 0.7 then swimming = false end
  local moving = len > 0.1
  local sp = 6
  if swimming then sp = 3.2 elseif self.inWater then sp = 4 end
  -- cliffs: a slope steeper than about 55 degrees cannot be walked up (no popping up a cliff face)
  local mvx, mvz = mx * sp * dt, mz * sp * dt
  local feet0 = self.y - 0.9
  if (mvx ~= 0 or mvz ~= 0) and feet0 < ground + 0.6 then
    local lim = 1.4 * math.sqrt(mvx * mvx + mvz * mvz)
    local gNew = groundHeight(self.x + mvx, self.z + mvz)
    if gNew - ground > lim and gNew > feet0 + 0.15 then
      local gx = groundHeight(self.x + mvx, self.z)
      local gz = groundHeight(self.x, self.z + mvz)
      if gx - ground <= 1.4 * math.abs(mvx) or gx <= feet0 + 0.15 then
        mvz = 0
      elseif gz - ground <= 1.4 * math.abs(mvz) or gz <= feet0 + 0.15 then
        mvx = 0
      else
        mvx = 0
        mvz = 0
      end
    end
  end
  self.x = self.x + mvx
  self.z = self.z + mvz
  self.ry = input.yaw

  if swimming and not wasSwim then
    splash(self.x, wy + 0.05, self.z, 10)
    playSound("splash", 1)
    log("Swimming - hold JUMP to dive. No shooting in deep water")
  elseif wasSwim and not swimming then
    splash(self.x, (wy or self.y) + 0.05, self.z, 4)
    playSound("splash", 0.6, 1.2)
  end
  if swimming or (self.inWater and moving) then
    rippleT = rippleT - dt
    if rippleT <= 0 then
      rippleT = moving and 0.35 or 0.9
      ripple(self.x, wy + 0.04, self.z)
    end
    if moving then
      strokeT = strokeT - dt
      if strokeT <= 0 then
        strokeT = swimming and 0.75 or 0.5
        playSound("swim", swimming and 0.6 or 0.35, 0.9 + math.random() * 0.2)
      end
    end
  end

  -- stairs: walk up and down a watchtower stair (the steps are only looks, the floor comes from here)
  local onStairs = false
  if #STAIRS > 0 and not swimming and self.vy <= 0.1 then
    local fl = stairFloor(self.x, self.z)
    if fl ~= nil then
      local feet = self.y - 0.9
      if feet > fl - 0.8 and feet < fl + 0.8 then
        self.y = fl + 0.9
        self.vy = 0
        onStairs = true
      end
    end
  end

  -- gravity and jumping (swimming: float on the surface and bob a little)
  if swimming then
    self.vy = 0
    -- hold JUMP to dive, let go and you float back up
    local maxDive = math.min(5, math.max(0, depth - 0.75))
    if input.jump then
      diveDepth = math.min(diveDepth + 2.5 * dt, maxDive)
    else
      diveDepth = math.max(0, diveDepth - 3 * dt)
    end
    if diveDepth > maxDive then diveDepth = maxDive end
    local bob = 0
    if diveDepth < 0.3 then bob = math.sin(time * 3) * 0.05 end
    local target = wy - 0.15 - diveDepth + bob
    self.y = self.y + (target - self.y) * math.min(1, dt * 6)
  else
    diveDepth = 0
    if self.grounded or onStairs then
      if self.vy < 0 then self.vy = 0 end
      if input.jumpPressed then self.vy = 7 end
    end
    if not onStairs or self.vy > 0 then
      self.vy = self.vy - world.gravity * dt
      self.y = self.y + self.vy * dt
    end
  end
  if self.y < -8 then
    self.x = home.x
    self.z = home.z
    self.y = groundHeight(home.x, home.z) + 2
    self.vy = 0
  end

  -- underwater: bubbles, breath, the murky look, sounds
  self.diveDepth = diveDepth
  local under = swimming and diveDepth > 0.45
  if under and not wasUnder then
    splash(self.x, wy + 0.05, self.z, 5)
    playSound("splash", 0.7, 0.7)
    log("Diving - breath " .. math.floor(breath) .. "s. Let go of JUMP to surface")
  elseif wasUnder and not under then
    splash(self.x, (wy or self.y) + 0.05, self.z, 4)
    if underT > 3 then playSound("gasp", 0.9) end
  end
  wasUnder = under
  if under then
    underT = underT + dt
    breath = breath - dt
    local bi = math.floor(breath)
    if bi ~= lastB and (bi == 10 or bi == 5 or bi == 2) then log("Breath " .. bi .. "s - surface!") end
    lastB = bi
    if breath <= 0 then
      breath = 0
      self.hp = self.hp - 6 * dt
    end
    bubbleT = bubbleT - dt
    if bubbleT <= 0 then
      bubbleT = 0.25 + math.random() * 0.2
      bubble(self.x + (math.random() - 0.5) * 0.5, self.y + 0.35, self.z + (math.random() - 0.5) * 0.5, wy)
    end
    bubbleSndT = bubbleSndT - dt
    if bubbleSndT <= 0 then
      bubbleSndT = 1.3
      playSound("bubble", 0.5, 0.9 + math.random() * 0.2)
    end
  else
    underT = 0
    breath = math.min(20, breath + 8 * dt)
    lastB = math.floor(breath)
  end
  if fogNorm == nil then
    fogNorm = world.fog or 0
    if fogNorm <= 0 then fogNorm = 400 end
    ambNorm = world.ambient or 0.45
  end
  if under then uwK = math.min(1, uwK + dt * 3) else uwK = math.max(0, uwK - dt * 3) end
  if uwK > 0 or wasUwK then
    world.fog = fogNorm + (14 - fogNorm) * uwK
    world.ambient = ambNorm * (1 - 0.5 * uwK)
  end
  wasUwK = uwK > 0

  -- pickups
  if self.reserveAdd > 0 then
    reserve = reserve + self.reserveAdd
    self.reserveAdd = 0
    log("Ammo picked up  (" .. reserve .. " spare)")
  end

  -- shooting (not while swimming)
  fireT = fireT - dt
  if swimming then
    -- no shooting in deep water
  elseif reloadT > 0 then
    reloadT = reloadT - dt
    if reloadT <= 0 then
      local take = math.min(30 - mag, reserve)
      mag = mag + take
      reserve = reserve - take
      log("Reloaded  " .. mag .. " / " .. reserve)
    end
  elseif input.buttonsPressed["Reload"] and mag < 30 and reserve > 0 then
    reloadT = 1.4
    log("Reloading...")
    playSound("reload", 0.8)
  elseif input.buttons["Fire"] and fireT <= 0 then
    if mag > 0 then
      shoot()
    elseif reserve > 0 then
      reloadT = 1.4
      log("Reloading...")
    playSound("reload", 0.8)
    else
      fireT = 1
      log("Out of ammo! Find an ammo box.")
    end
  end

  -- the person: pose, swim stroke, and hide the body in first person
  aimT = aimT - dt
  local fp = camera.firstPerson
  if fp ~= fpWas then
    fpWas = fp
    local o = 1
    if fp then o = 0 end
    P.head.opacity = o
    P.helm.opacity = o
    P.torso.opacity = o
    P.pack.opacity = o
    P.legL.opacity = o
    P.legR.opacity = o
  end
  local leanTo = 0
  if swimming then
    leanTo = -80
    walkT = walkT + dt * (moving and 5 or 2)
    local s = math.sin(walkT)
    P.armL.swing = 135 + 35 * s
    P.armR.swing = 135 - 35 * s
    P.legL.swing = 22 * math.sin(walkT * 2.2)
    P.legR.swing = -22 * math.sin(walkT * 2.2)
  else
    walkPose(dt, moving and (self.grounded or onStairs), sp, aimT > 0)
  end
  self.rx = self.rx + (leanTo - self.rx) * math.min(1, dt * 6)

  -- health
  if self.hp < lastHp and math.floor(self.hp / 10) < math.floor(lastHp / 10) then
    log("HP " .. math.max(0, math.floor(self.hp)))
  end
  hurtSndT = hurtSndT - dt
  if self.hp < lastHp - 0.5 and hurtSndT <= 0 then
    playSound("hurt", 1, 0.92 + math.random() * 0.16)
    hurtSndT = 0.35
  end
  lastHp = self.hp
  if self.hp <= 0 then
    self.dead = true
    deadG = groundHeight(self.x, self.z)
    for k, part in pairs(P) do
      if part ~= nil then part.opacity = 1 end
    end
    log("YOU DIED.  Enemies left: " .. countAlive())
    return
  end
  if not over and countAlive() == 0 then
    over = true
    log("WINNER WINNER CHICKEN DINNER!")
  end
  statT = statT - dt
  if statT <= 0 and not over then
    statT = 8
    log("HP " .. math.floor(self.hp) .. "   Ammo " .. mag .. "/" .. reserve .. "   Enemies " .. countAlive() .. "   Zone " .. math.floor(zone.radius or 0))
  end
end
"""

    private val BR_BOT = """-- Enemy soldier: wanders, hunts the player, strafes and shoots, runs from the storm.
local DMG = 8

function start()
  self.hp = 60
  self.dead = false
  self.vy = 0
  spd = 3.0 + math.random() * 1.2
  tx = self.x
  tz = self.z
  wanderT = 0
  fireT = 1 + math.random() * 2
  strafe = 1
  strafeT = 0
  bullets = {}
  walls = {}
  grid = {}
  local i = 1
  while true do
    local w = find("Wall " .. i)
    if w == nil then break end
    walls[i] = w
    addWall(w)
    i = i + 1
  end
  player = find("Player")
  zone = find("Zone")
  home = { x = self.x, z = self.z }
  findParts()
  deadT = -1
end

-- ===== the person: find the parts (they follow this body) and swing the arms and legs =====
walkT = 0
legAmp = 0

function findParts()
  local n = self.name
  P = {
    head = find(n .. " head"), helm = find(n .. " helm"), torso = find(n .. " torso"), pack = find(n .. " pack"),
    armL = find(n .. " armL"), armR = find(n .. " armR"), legL = find(n .. " legL"), legR = find(n .. " legR"),
    gun = find(n .. " gun")
  }
end

-- walking: legs and arms swing; aiming: arms come up with the rifle
function walkPose(dt, moving, speed, aiming)
  if moving then walkT = walkT + dt * speed * 1.7 end
  local want = 0
  if moving then want = 38 end
  legAmp = legAmp + (want - legAmp) * math.min(1, dt * 8)
  local s = math.sin(walkT)
  P.legL.swing = legAmp * s
  P.legR.swing = -legAmp * s
  local base = 55
  if aiming then base = 78 end
  P.armL.swing = base + legAmp * 0.12 * s
  P.armR.swing = base - legAmp * 0.12 * s
end

-- lying down when shot: the body tips over backwards and sinks to the ground
function fallPose(t, groundY)
  local k = math.min(1, t / 0.6)
  self.rx = 90 * k
  self.y = groundY + 0.9 - 0.7 * k
  P.legL.swing = 10 * k
  P.legR.swing = -10 * k
  P.armL.swing = 55 + 80 * k
  P.armR.swing = 55 + 80 * k
end

function removeBody()
  for k, part in pairs(P) do
    if part ~= nil then destroy(part) end
  end
  destroy(self)
end

-- walls are put into a coarse grid once, so a bullet only tests the few walls near it
CELL = 6

function addWall(w)
  local e = { x = w.x, y = w.y, z = w.z, hx = w.sx / 2, hy = w.sy / 2, hz = w.sz / 2 }
  local x0 = math.floor((e.x - e.hx) / CELL)
  local x1 = math.floor((e.x + e.hx) / CELL)
  local z0 = math.floor((e.z - e.hz) / CELL)
  local z1 = math.floor((e.z + e.hz) / CELL)
  for cx = x0, x1 do
    for cz = z0, z1 do
      local k = cx * 100003 + cz
      local c = grid[k]
      if c == nil then
        c = {}
        grid[k] = c
      end
      c[#c + 1] = e
    end
  end
end

function pointInWall(x, y, z)
  local c = grid[math.floor(x / CELL) * 100003 + math.floor(z / CELL)]
  if c == nil then return false end
  for i = 1, #c do
    local w = c[i]
    if math.abs(x - w.x) < w.hx and math.abs(y - w.y) < w.hy and math.abs(z - w.z) < w.hz then
      return true
    end
  end
  return false
end

-- moves a bullet in small steps so it cannot jump through a thin wall; true if it hit one
function moveBullet(b, dt)
  local dx, dy, dz = b.vx * dt, b.vy * dt, b.vz * dt
  local n = math.ceil(math.sqrt(dx * dx + dy * dy + dz * dz) / 0.2)
  if n < 1 then n = 1 end
  if n > 40 then n = 40 end
  local sx, sy, sz = dx / n, dy / n, dz / n
  local x, y, z = b.x, b.y, b.z
  for s = 1, n do
    x = x + sx
    y = y + sy
    z = z + sz
    if pointInWall(x, y, z) then
      b.x, b.y, b.z = x, y, z
      return true
    end
  end
  b.x, b.y, b.z = x, y, z
  return false
end

-- true when no wall is between the two points
function lineClear(x0, y0, z0, x1, y1, z1)
  local dx, dy, dz = x1 - x0, y1 - y0, z1 - z0
  local n = math.ceil(math.sqrt(dx * dx + dy * dy + dz * dz) / 0.4)
  for s = 1, n - 1 do
    local t = s / n
    if pointInWall(x0 + dx * t, y0 + dy * t, z0 + dz * t) then return false end
  end
  return true
end

function pickTarget()
  local a = math.random() * 6.28
  local d = 6 + math.random() * 12
  tx = self.x + math.cos(a) * d
  tz = self.z + math.sin(a) * d
  local zx, zz = tx - zone.x, tz - zone.z
  local zd = math.sqrt(zx * zx + zz * zz) + 0.001
  local zr = (zone.radius or 30) - 3
  if zd > zr then
    tx = zone.x + zx / zd * zr
    tz = zone.z + zz / zd * zr
  end
  wanderT = 4 + math.random() * 4
end

function botShoot(dx, dz, d)
  playSound("shot", math.max(0.1, 0.55 - d / 70), 0.8)
  local ex = (math.random() - 0.5) * d * 0.12
  local ez = (math.random() - 0.5) * d * 0.12
  local ddx, ddy, ddz = dx + ex, (player.y - self.y), dz + ez
  local l = math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz) + 0.001
  local ok, b = pcall(spawn, "sphere", self.x + ddx / l * 1.2, self.y + 0.4 + ddy / l * 1.2, self.z + ddz / l * 1.2)
  if not ok then return end
  b.sx = 0.2
  b.sy = 0.2
  b.sz = 0.2
  b.r = 1
  b.g = 0.35
  b.b = 0.15
  b.emit = 1
  b.vx = ddx / l * 45
  b.vy = ddy / l * 45
  b.vz = ddz / l * 45
  b.life = 1.6
  bullets[#bullets + 1] = b
end

function updateBullets(dt)
  for i = #bullets, 1, -1 do
    local b = bullets[i]
    local ox, oy, oz = b.x, b.y, b.z
    local hit = moveBullet(b, dt)
    b.life = b.life - dt
    local gone = b.life <= 0 or hit or b.y < groundHeight(b.x, b.z) - 0.1
    if not gone and not player.dead then
      local ex, ez = b.x - ox, b.z - oz
      local t = ((player.x - ox) * ex + (player.z - oz) * ez) / (ex * ex + ez * ez + 0.0001)
      if t < 0 then t = 0 elseif t > 1 then t = 1 end
      local dx, dz = player.x - (ox + ex * t), player.z - (oz + ez * t)
      local dy = player.y - (oy + (b.y - oy) * t)
      if dx * dx + dz * dz < 0.8 and math.abs(dy) < 1.1 and (player.diveDepth or 0) < 0.8 then
        player.hp = player.hp - DMG
        gone = true
      end
    end
    if gone then
      destroy(b)
      table.remove(bullets, i)
    end
  end
end

function update(dt)
  if self.hp <= 0 or self.dead then
    if deadT < 0 then
      deadT = 0
      deadG = groundHeight(self.x, self.z)
      self.dead = true
      self.collider = 0
      for i = 1, #bullets do destroy(bullets[i]) end
      bullets = {}
    end
    deadT = deadT + dt
    fallPose(deadT, deadG)
    if deadT > 6 then removeBody() end
    return
  end

  local dx, dz = player.x - self.x, player.z - self.z
  local d = math.sqrt(dx * dx + dz * dz) + 0.001
  local mx, mz = 0, 0
  local aiming = false
  local zx, zz = self.x - zone.x, self.z - zone.z
  local zd = math.sqrt(zx * zx + zz * zz) + 0.001
  local zr = zone.radius or 40

  if zd > zr then
    -- caught in the storm: run for the middle
    self.hp = self.hp - 2 * dt
    mx, mz = -zx / zd, -zz / zd
    self.ry = math.deg(math.atan2(-mx, -mz))
  elseif (not player.dead) and d < 30 then
    -- fight: keep a medium distance, strafe, shoot
    aiming = true
    self.ry = math.deg(math.atan2(-dx, -dz))
    if d > 16 then
      mx, mz = dx / d, dz / d
    elseif d < 8 then
      mx, mz = -dx / d, -dz / d
    else
      strafeT = strafeT - dt
      if strafeT <= 0 then
        strafe = -strafe
        strafeT = 1 + math.random() * 2
      end
      mx, mz = -dz / d * strafe, dx / d * strafe
    end
    fireT = fireT - dt
    if fireT <= 0 then
      if (player.diveDepth or 0) < 0.8 and lineClear(self.x, self.y + 0.4, self.z, player.x, player.y + 0.4, player.z) then
        fireT = 0.7 + math.random() * 0.6
        botShoot(dx, dz, d)
      else
        fireT = 0.3   -- a wall is in the way: hold fire and look again soon
      end
    end
  else
    -- wander
    wanderT = wanderT - dt
    local wx, wz = tx - self.x, tz - self.z
    local wd = math.sqrt(wx * wx + wz * wz) + 0.001
    if wanderT <= 0 or wd < 1.5 then pickTarget() end
    mx, mz = wx / wd * 0.6, wz / wd * 0.6
    self.ry = math.deg(math.atan2(-mx, -mz))
  end

  local sp = spd
  if self.inWater then sp = sp * 0.5 end
  self.x = self.x + mx * sp * dt
  self.z = self.z + mz * sp * dt

  if self.grounded and self.vy < 0 then self.vy = 0 end
  self.vy = self.vy - world.gravity * dt
  self.y = self.y + self.vy * dt
  if self.y < -8 then
    self.x = home.x
    self.z = home.z
    self.y = groundHeight(home.x, home.z) + 2
    self.vy = 0
  end
  walkPose(dt, (mx ~= 0 or mz ~= 0) and self.grounded, sp, aiming)
  updateBullets(dt)
end
"""

    private val BR_ZONE = """-- The storm: a ring of blue posts that closes in. Standing outside it hurts.
local NPOSTS = 40
local stages = { { 35, 25, 22 }, { 30, 20, 12 }, { 25, 20, 5 } }

function start()
  r = 38
  self.radius = r
  stage = 1
  phase = "wait"
  pt = stages[1][1]
  fromR = r
  hurtT = 0
  player = find("Player")
  posts = {}
  for i = 1, NPOSTS do
    local ok, p = pcall(spawn, "cube", self.x, 8, self.z)
    if ok then
      p.sx = 0.7
      p.sy = 16
      p.sz = 0.7
      p.r = 0.25
      p.g = 0.55
      p.b = 1
      p.emit = 0.8
      p.shadow = false
      p.opacity = 0.4
      posts[#posts + 1] = p
    end
  end
  log("BATTLE ROYALE!  8 enemies on the island. Stay inside the blue ring, find medkits and ammo.")
  log("Joystick = move, drag screen = look, hold FIRE, RELOAD, JUMP, VIEW = 1st/3rd person.")
end

function update(dt)
  if stage <= #stages then
    local st = stages[stage]
    pt = pt - dt
    if phase == "wait" then
      if pt <= 0 then
        phase = "shrink"
        pt = st[2]
        fromR = r
        log("The storm is closing in!")
      end
    else
      local f = 1 - math.max(pt, 0) / st[2]
      r = fromR + (st[3] - fromR) * f
      if pt <= 0 then
        r = st[3]
        stage = stage + 1
        phase = "wait"
        if stages[stage] ~= nil then
          pt = stages[stage][1]
          log("Next storm in " .. pt .. " seconds")
        end
      end
    end
  end
  self.radius = r

  local n = #posts
  for i = 1, n do
    local a = (i - 1) / n * 6.2832
    local p = posts[i]
    p.x = self.x + math.cos(a) * r
    p.z = self.z + math.sin(a) * r
  end

  if not player.dead then
    local dx, dz = player.x - self.x, player.z - self.z
    if dx * dx + dz * dz > r * r then
      player.hp = player.hp - 3 * dt
      hurtT = hurtT - dt
      if hurtT <= 0 then
        hurtT = 3
        log("You are in the storm! Run to the middle!")
      end
    end
  end
end
"""

    private val BR_MEDKIT = """-- Medkit: walk over it to heal 40 HP. It comes back after 25 seconds.
function start()
  phase = math.random() * 6
  home = self.y
  taken = 0
end

function update(dt)
  if taken > 0 then
    taken = taken - dt
    if taken <= 0 then self.y = home end
    return
  end
  self.ry = self.ry + 120 * dt
  self.y = home + 0.15 + math.sin(time * 3 + phase) * 0.1
end

function oncollide(other)
  if taken > 0 or other.name ~= "Player" or other.dead then return end
  if other.hp >= 100 then return end
  other.hp = math.min(100, other.hp + 40)
  playSound("pickup", 0.8)
  taken = 25
  self.y = -50
  log("Medkit +40 HP  (" .. math.floor(other.hp) .. ")")
end
"""

    private val BR_AMMO = """-- Ammo box: walk over it for 30 more bullets. It comes back after 25 seconds.
function start()
  phase = math.random() * 6
  home = self.y
  taken = 0
end

function update(dt)
  if taken > 0 then
    taken = taken - dt
    if taken <= 0 then self.y = home end
    return
  end
  self.ry = self.ry + 90 * dt
  self.y = home + 0.15 + math.sin(time * 3 + phase) * 0.1
end

function oncollide(other)
  if taken > 0 or other.name ~= "Player" or other.dead then return end
  other.reserveAdd = (other.reserveAdd or 0) + 30
  playSound("pickup", 0.8, 1.2)
  taken = 25
  self.y = -50
end
"""
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

    /**
     * A person made of simple parts that follow one invisible body (the root): head, helmet, torso, backpack,
     * two arms, two legs and a rifle. The root keeps the name, the script and the collision; scripts swing the
     * arms and legs through the parts' "swing" value. Front of the person is -z, like the rest of the sample.
     */
    private fun human(
        scene: Scene, name: String, x: Float, y: Float, z: Float, script: String,
        shirt: FloatArray, pants: FloatArray, helmet: FloatArray
    ): SceneObject {
        val skin = floatArrayOf(0.93f, 0.74f, 0.6f)
        val dark = floatArrayOf(0.16f, 0.16f, 0.18f)
        val root = mk(scene, Shape.SPHERE, name, x, y, z, 0.9f, 1.8f, 0.9f, shirt, script)
        root.collider = 1
        root.shadow = false
        root.opacity = 0.02f       // the body is only the collision shape; the parts are what you see
        fun part(suffix: String, shape: Shape, dx: Float, dy: Float, dz: Float, sx: Float, sy: Float, sz: Float, color: FloatArray) {
            val p = mk(scene, shape, name + " " + suffix, x + dx, y + dy, z + dz, sx, sy, sz, color)
            p.shadow = false
            Rig.bind(p, root)
        }
        part("head", Shape.SPHERE, 0f, 0.74f, 0f, 0.30f, 0.33f, 0.30f, skin)
        part("helm", Shape.SPHERE, 0f, 0.83f, 0f, 0.37f, 0.24f, 0.39f, helmet)
        part("torso", Shape.CUBE, 0f, 0.30f, 0f, 0.50f, 0.60f, 0.28f, shirt)
        part("pack", Shape.CUBE, 0f, 0.32f, 0.22f, 0.36f, 0.42f, 0.18f, dark)
        part("armL", Shape.CUBE, -0.31f, 0.305f, 0f, 0.13f, 0.55f, 0.13f, shirt)
        part("armR", Shape.CUBE, 0.31f, 0.305f, 0f, 0.13f, 0.55f, 0.13f, shirt)
        part("legL", Shape.CUBE, -0.12f, -0.46f, 0f, 0.18f, 0.88f, 0.18f, pants)
        part("legR", Shape.CUBE, 0.12f, -0.46f, 0f, 0.18f, 0.88f, 0.18f, pants)
        part("gun", Shape.CUBE, 0.1f, 0.32f, -0.45f, 0.08f, 0.1f, 0.72f, dark)
        return root
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

    /** A night camp: the fire lights the logs and trees, and smoke rises from it. */
    fun campfire(scene: Scene) {
        scene.objects.clear()
        scene.selected = null
        scene.addSettings()?.let {
            it.y = 45f; it.z = 0.1f; it.ry = 0f
            it.color = floatArrayOf(0.02f, 0.03f, 0.08f)
        }
        scene.addLight(Shape.SUN)?.let { it.sx = 0.15f; it.sy = 0.1f; it.color = floatArrayOf(0.6f, 0.7f, 1f) }
        mk(scene, Shape.CUBE, "Ground", 0f, -0.1f, 0f, 30f, 0.2f, 30f, floatArrayOf(0.2f, 0.34f, 0.18f))
        for (k in 0 until 4) {
            val a = k * 90f + 20f
            val rad = Math.toRadians(a.toDouble())
            val log = mk(scene, Shape.CUBE, "Log " + (k + 1), (Math.cos(rad) * 0.7).toFloat(), 0.12f, (Math.sin(rad) * 0.7).toFloat(),
                1.1f, 0.2f, 0.2f, floatArrayOf(0.35f, 0.22f, 0.12f))
            log.ry = -a + 90f
        }
        val fire = scene.addFire()
        fire.x = 0f; fire.z = 0f; fire.y = 0.1f; fire.sx = 1.1f; fire.sy = 1.6f
        val smoke = scene.addSmoke()
        smoke.x = 0f; smoke.z = 0f; smoke.y = 1.0f; smoke.sy = 6f; smoke.rx = 0.7f
        val rnd = kotlin.random.Random(5)
        for (k in 0 until 14) {
            val a = rnd.nextFloat() * 6.2831853f
            val d = 5f + rnd.nextFloat() * 7f
            val tr = scene.addTree(1)
            val s = 0.8f + rnd.nextFloat() * 0.6f
            tr.x = Math.cos(a.toDouble()).toFloat() * d
            tr.z = Math.sin(a.toDouble()).toFloat() * d
            tr.y = 0f
            tr.sx = 2f * s; tr.sy = 3.4f * s; tr.sz = 2f * s
            tr.ry = rnd.nextFloat() * 360f
            tr.shadow = false
        }
        scene.selected = null
    }

    /** Hills with a lake: floating crates ride the real wave height through waterHeight(). */
    fun lake(scene: Scene) {
        scene.objects.clear()
        scene.selected = null
        scene.addSettings()?.let {
            it.y = 70f; it.z = 0.4f; it.ry = 0.6f; it.wind = 0.4f; it.windDir = 40f
            it.color = floatArrayOf(0.55f, 0.75f, 0.92f)
        }
        scene.addLight(Shape.SUN)?.let { it.sx = 1.1f; it.sy = 0.35f }
        val land = scene.addTerrain()
        land.sx = 30f; land.sy = 4f; land.sz = 30f
        land.heights = TerrainGen.generate(7, 0.55f)
        land.hVersion++
        val sea = scene.addWater()
        sea.sx = 30f; sea.sz = 30f; sea.y = 1.2f; sea.sy = 0.3f; sea.rx = 1.2f; sea.ry = 40f
        val grass = scene.addGrass()
        grass.x = 0f; grass.z = 0f; grass.y = 0f; grass.sx = 30f; grass.sz = 30f
        val rnd = kotlin.random.Random(11)
        var trees = 0
        var tries = 0
        while (trees < 14 && tries < 300) {
            tries++
            val tx = (rnd.nextFloat() - 0.5f) * 27f
            val tz = (rnd.nextFloat() - 0.5f) * 27f
            val th = TerrainGen.heightAt(land, tx, tz) ?: continue
            if (th < sea.y + 0.3f || th > land.y + land.sy * 0.7f) continue
            val tr = scene.addTree(rnd.nextInt(2))
            val k = 0.8f + rnd.nextFloat() * 0.5f
            tr.x = tx; tr.z = tz; tr.y = th - 0.05f
            tr.sx = 2f * k; tr.sy = 3f * k; tr.sz = 2f * k
            tr.ry = rnd.nextFloat() * 360f
            tr.shadow = false
            trees++
        }
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

    /** A PUBG-style island: loot houses, 8 enemy soldiers, a closing storm. Joystick, FIRE, RELOAD, JUMP, VIEW. */
    /**
     * Battle Royale. mode 0 = small island, 1 = large map (300 x 300, 16 houses, 20 enemies),
     * 2 = HUGE map (500 x 500, tall hills, deep lakes, watchtowers with stairs, 22 enemies).
     */
    fun battleRoyale(scene: Scene, mode: Int = 0) {
        val large = mode >= 1
        val huge = mode == 2
        val map = if (huge) 500f else if (large) 300f else 70f
        val sp = map / 70f                       // everything that is a distance scales with the map
        val relief = if (huge) 55f else if (large) 16f else 7f
        val slopeK = if (huge) 2.6f else 1f      // hills are steeper on the huge map, so house sites may be less flat
        val nHouses = if (large) 16 else 5
        val houseGap = if (huge) 40.0 else if (large) 32.0 else 16.0
        val nTrees = if (huge) 110 else if (large) 130 else 40
        val nBots = if (huge) 18 else if (large) 16 else 8
        val nExtraLoot = if (huge) 24 else if (large) 14 else 6
        val playerScriptBase = BR_PLAYER.replace("local NBOTS = 8", "local NBOTS = " + nBots)
        val zoneScript = when (mode) {
            0 -> BR_ZONE
            1 -> BR_ZONE
                .replace("local NPOSTS = 40", "local NPOSTS = 56")
                .replace("local stages = { { 35, 25, 22 }, { 30, 20, 12 }, { 25, 20, 5 } }", "local stages = { { 45, 40, 110 }, { 40, 35, 70 }, { 35, 30, 40 }, { 30, 25, 15 } }")
                .replace("  r = 38\n", "  r = 165\n")
                .replace("\"cube\", self.x, 8, self.z", "\"cube\", self.x, 20, self.z")
                .replace("p.sy = 16", "p.sy = 60")
                .replace("p.sx = 0.7", "p.sx = 1.2")
                .replace("p.sz = 0.7", "p.sz = 1.2")
                .replace("8 enemies on the island", nBots.toString() + " enemies on the island")
            else -> BR_ZONE
                .replace("local NPOSTS = 40", "local NPOSTS = 72")
                .replace("local stages = { { 35, 25, 22 }, { 30, 20, 12 }, { 25, 20, 5 } }", "local stages = { { 70, 60, 190 }, { 60, 50, 120 }, { 50, 40, 70 }, { 40, 30, 35 }, { 30, 25, 12 } }")
                .replace("  r = 38\n", "  r = 270\n")
                .replace("\"cube\", self.x, 8, self.z", "\"cube\", self.x, 60, self.z")
                .replace("p.sy = 16", "p.sy = 140")
                .replace("p.sx = 0.7", "p.sx = 1.6")
                .replace("p.sz = 0.7", "p.sz = 1.6")
                .replace("8 enemies on the island", nBots.toString() + " enemies on the island")
        }

        scene.objects.clear()
        scene.selected = null
        scene.addSettings()?.let {
            it.x = 12f; it.y = if (huge) 700f else if (large) 380f else 95f; it.z = 0.45f; it.ry = 0.55f; it.wind = 0.3f; it.windDir = 60f
            it.color = floatArrayOf(0.55f, 0.75f, 0.92f)
        }
        scene.addLight(Shape.SUN)?.let { it.sx = 1.1f; it.sy = 0.4f }
        val land = scene.addTerrain()
        land.sx = map; land.sy = relief; land.sz = map
        land.heights = TerrainGen.generate(3, 0.6f)
        land.hVersion++
        val sea = scene.addWater()
        sea.sx = map * 1.3f; sea.sz = map * 1.3f; sea.y = relief * (if (huge) 0.34f else 0.3f); sea.sy = if (huge) 0.35f else 0.2f
        val seaY = sea.y
        val grass = scene.addGrass()
        grass.x = 0f; grass.z = 0f; grass.y = 0f; grass.sx = if (huge) 140f else if (large) 100f else 50f; grass.sz = if (huge) 140f else if (large) 100f else 50f; grass.rx = 0.6f

        val rnd = kotlin.random.Random(21)
        fun ground(x: Float, z: Float): Float? = TerrainGen.heightAt(land, x, z)

        // ---- storm centre: the highest ground near the middle ----
        var zcx = 0f
        var zcz = 0f
        var zbest = -1f
        for (k in 0 until 120) {
            val x = (rnd.nextFloat() - 0.5f) * 0.4f * map
            val z = (rnd.nextFloat() - 0.5f) * 0.4f * map
            val h = ground(x, z) ?: continue
            if (h > zbest) { zbest = h; zcx = x; zcz = z }
        }

        if (huge) { grass.x = zcx; grass.z = zcz }

        // ---- houses ----
        val houses = ArrayList<FloatArray>()   // centre x, centre z, floor top y
        var tries = 0
        while (houses.size < nHouses && tries < 3000) {
            tries++
            val hx = (rnd.nextFloat() - 0.5f) * 0.74f * map
            val hz = (rnd.nextFloat() - 0.5f) * 0.74f * map
            var lo = 1e9f
            var hi = -1e9f
            var ok = true
            for (dx in intArrayOf(-4, 0, 4)) for (dz in intArrayOf(-4, 0, 4)) {
                val h = ground(hx + dx, hz + dz)
                if (h == null) ok = false else { lo = minOf(lo, h); hi = maxOf(hi, h) }
            }
            val tol = (if (tries < 1500) 1.0f else 2.2f) * slopeK
            if (!ok || lo < seaY + 0.6f || hi - lo > tol) continue
            if (houses.any { Math.hypot((it[0] - hx).toDouble(), (it[1] - hz).toDouble()) < houseGap }) continue
            houses.add(floatArrayOf(hx, hz, hi + 0.2f, lo))
        }
        var wallN = 0
        // walls, roofs and decks all stop bullets (the scripts look for objects named "Wall n")
        fun wall(x: Float, y: Float, z: Float, sx: Float, sy: Float, sz: Float, color: FloatArray = floatArrayOf(0.78f, 0.70f, 0.55f)) {
            wallN++
            val o = mk(scene, Shape.CUBE, "Wall " + wallN, x, y, z, sx, sy, sz, color)
            o.collider = 1
        }
        var houseN = 0
        for (h in houses) {
            houseN++
            val cx = h[0]
            val cz = h[1]
            val top = h[2]
            val lo = h[3]
            val slabH = top - lo + 0.5f
            val slab = mk(scene, Shape.CUBE, "Floor " + houseN, cx, lo - 0.3f + slabH / 2f, cz, 8f, slabH, 8f, floatArrayOf(0.5f, 0.5f, 0.5f))
            slab.collider = 1
            val wy = top + 1.5f
            wall(cx, wy, cz - 3.5f, 7.3f, 3f, 0.3f)
            wall(cx + 3.5f, wy, cz, 0.3f, 3f, 7f)
            wall(cx - 3.5f, wy, cz, 0.3f, 3f, 7f)
            wall(cx - 2.25f, wy, cz + 3.5f, 2.5f, 3f, 0.3f)
            wall(cx + 2.25f, wy, cz + 3.5f, 2.5f, 3f, 0.3f)
            wall(cx, top + 3.15f, cz, 7.8f, 0.3f, 7.8f, floatArrayOf(0.55f, 0.2f, 0.18f))
        }

        // ---- watchtowers with a long stair up to the deck (huge map) ----
        val stairLua = StringBuilder("{")
        val towerSpots = ArrayList<FloatArray>()
        if (huge) {
            val H = 10f          // deck height above the tower base
            val nSteps = 20
            val tread = 0.8f
            tries = 0
            while (towerSpots.size < 5 && tries < 5000) {
                tries++
                val tx = (rnd.nextFloat() - 0.5f) * 0.7f * map
                val tz = (rnd.nextFloat() - 0.5f) * 0.7f * map
                var lo = 1e9f
                var hi = -1e9f
                var ok = true
                for (dx in intArrayOf(-5, 0, 5)) for (dz in intArrayOf(-5, 0, 5)) {
                    val h = ground(tx + dx, tz + dz)
                    if (h == null) ok = false else { lo = minOf(lo, h); hi = maxOf(hi, h) }
                }
                if (!ok || lo < seaY + relief * 0.12f || hi - lo > 2.5f) continue
                val z1 = tz + 3.7f + nSteps * tread
                val g1 = ground(tx, z1) ?: continue
                val topT = hi + 0.2f
                if (g1 < seaY + 0.5f || g1 < topT - 3.5f || g1 > topT + 3.5f) continue
                if (houses.any { Math.hypot((it[0] - tx).toDouble(), (it[1] - tz).toDouble()) < 26.0 }) continue
                if (houses.any { Math.hypot((it[0] - tx).toDouble(), (it[1] - (tz + 12f)).toDouble()) < 14.0 }) continue
                if (towerSpots.any { Math.hypot((it[0] - tx).toDouble(), (it[1] - tz).toDouble()) < 70.0 }) continue
                towerSpots.add(floatArrayOf(tx, tz, topT, lo, g1))
            }
            var tn = 0
            for (t in towerSpots) {
                tn++
                val tx = t[0]
                val tz = t[1]
                val T = t[2]
                val lo = t[3]
                val g1 = t[4]
                val slabH = T - lo + 0.5f
                val baseSlab = mk(scene, Shape.CUBE, "Tower base " + tn, tx, lo - 0.3f + slabH / 2f, tz, 9f, slabH, 9f, floatArrayOf(0.45f, 0.45f, 0.48f))
                baseSlab.collider = 1
                val legH = H + 3.4f
                for (sx in intArrayOf(-1, 1)) for (sz in intArrayOf(-1, 1)) {
                    wall(tx + sx * 3.2f, T + legH / 2f, tz + sz * 3.2f, 0.6f, legH, 0.6f)
                }
                wall(tx, T + H - 0.2f, tz, 7.4f, 0.4f, 7.4f, floatArrayOf(0.55f, 0.42f, 0.3f))
                wall(tx, T + H + 0.5f, tz - 3.6f, 7.4f, 1f, 0.2f)
                wall(tx + 3.6f, T + H + 0.5f, tz, 0.2f, 1f, 7f)
                wall(tx - 3.6f, T + H + 0.5f, tz, 0.2f, 1f, 7f)
                wall(tx - 2.4f, T + H + 0.5f, tz + 3.6f, 2.6f, 1f, 0.2f)
                wall(tx + 2.4f, T + H + 0.5f, tz + 3.6f, 2.6f, 1f, 0.2f)
                wall(tx, T + H + 3.45f, tz, 8f, 0.3f, 8f, floatArrayOf(0.55f, 0.2f, 0.18f))
                // the stair: 20 steps coming up from the south; the player script walks up them (see STAIRS)
                val z0 = tz + 3.7f
                val z1 = z0 + nSteps * tread
                val rise = (T + H - g1) / nSteps
                val bottom = minOf(g1, T) - 3f
                for (i in 0 until nSteps) {
                    val top = g1 + rise * (i + 1)
                    val zc = z1 - (i + 0.5f) * tread
                    val h = top - bottom
                    mk(scene, Shape.CUBE, "Step " + tn + "-" + (i + 1), tx, bottom + h / 2f, zc, 2.2f, h, tread, floatArrayOf(0.72f, 0.62f, 0.45f))
                }
                if (tn > 1) stairLua.append(", ")
                stairLua.append("{ x0 = ").append(tx - 1.1f).append(", x1 = ").append(tx + 1.1f)
                    .append(", z0 = ").append(z0).append(", z1 = ").append(z1)
                    .append(", tread = ").append(tread).append(", n = ").append(nSteps)
                    .append(", rise = ").append(rise).append(", base = ").append(g1).append(" }")
            }
        }
        stairLua.append("}")

        // ---- trees (away from houses and water) ----
        var trees = 0
        tries = 0
        while (trees < nTrees && tries < 1500) {
            tries++
            val tx = (rnd.nextFloat() - 0.5f) * 0.91f * map
            val tz = (rnd.nextFloat() - 0.5f) * 0.91f * map
            val th = ground(tx, tz) ?: continue
            if (th < seaY + 0.4f || th > land.sy * 0.8f) continue
            if (houses.any { Math.hypot((it[0] - tx).toDouble(), (it[1] - tz).toDouble()) < 7.0 }) continue
            if (towerSpots.any { Math.hypot((it[0] - tx).toDouble(), (it[1] - (tz + 10f)).toDouble()) < 16.0 }) continue
            val tr = scene.addTree(rnd.nextInt(2))
            val k = 0.8f + rnd.nextFloat() * 0.6f
            tr.x = tx; tr.z = tz; tr.y = th - 0.05f
            tr.sx = 2f * k; tr.sy = 3.4f * k; tr.sz = 2f * k
            tr.ry = rnd.nextFloat() * 360f
            trees++
        }

        // ---- loot: one medkit and one ammo box in every house, plus some outside ----
        var medN = 0
        var ammoN = 0
        fun medkit(x: Float, y: Float, z: Float) {
            medN++
            val o = mk(scene, Shape.CUBE, "Medkit " + medN, x, y, z, 0.5f, 0.5f, 0.5f, floatArrayOf(0.9f, 0.15f, 0.15f), BR_MEDKIT)
            o.collider = 2; o.emit = 0.35f; o.shadow = false
        }
        fun ammo(x: Float, y: Float, z: Float) {
            ammoN++
            val o = mk(scene, Shape.CUBE, "Ammo " + ammoN, x, y, z, 0.5f, 0.35f, 0.6f, floatArrayOf(0.95f, 0.8f, 0.15f), BR_AMMO)
            o.collider = 2; o.emit = 0.35f; o.shadow = false
        }
        for (h in houses) {
            medkit(h[0] - 1.8f, h[2] + 0.5f, h[1] - 1.6f)
            ammo(h[0] + 1.8f, h[2] + 0.5f, h[1] - 1.6f)
        }
        for (t in towerSpots) {
            medkit(t[0] - 1.6f, t[2] + 10f + 0.5f, t[1] - 1.6f)
            ammo(t[0] + 1.6f, t[2] + 10f + 0.5f, t[1] - 1.6f)
        }
        var extra = 0
        tries = 0
        while (extra < nExtraLoot && tries < 600) {
            tries++
            val x = (rnd.nextFloat() - 0.5f) * 0.8f * map
            val z = (rnd.nextFloat() - 0.5f) * 0.8f * map
            val h = ground(x, z) ?: continue
            if (h < seaY + 0.5f) continue
            if (extra % 2 == 0) medkit(x, h + 0.5f, z) else ammo(x, h + 0.5f, z)
            extra++
        }

        // ---- player ----
        var px = zcx + 18f
        var pz = zcz
        var pg = 0f
        tries = 0
        while (tries < 400) {
            tries++
            val a = rnd.nextFloat() * 6.2832f
            val d = (20f + rnd.nextFloat() * 10f) * sp
            val x = zcx + Math.cos(a.toDouble()).toFloat() * d
            val z = zcz + Math.sin(a.toDouble()).toFloat() * d
            val h = ground(x, z) ?: continue
            if (h < seaY + 0.8f) continue
            if (houses.any { Math.hypot((it[0] - x).toDouble(), (it[1] - z).toDouble()) < 9.0 }) continue
            px = x; pz = z; pg = h
            break
        }
        human(
            scene, "Player", px, pg + 1.2f, pz, playerScriptBase.replace("STAIRS = {}", "STAIRS = " + stairLua),
            floatArrayOf(0.22f, 0.42f, 0.78f), floatArrayOf(0.2f, 0.22f, 0.3f), floatArrayOf(0.2f, 0.3f, 0.5f)
        )

        // ---- enemy soldiers ----
        var bots = 0
        tries = 0
        while (bots < nBots && tries < 2000) {
            tries++
            val x = (rnd.nextFloat() - 0.5f) * 0.8f * map
            val z = (rnd.nextFloat() - 0.5f) * 0.8f * map
            val h = ground(x, z) ?: continue
            if (h < seaY + 0.6f) continue
            if (Math.hypot((x - px).toDouble(), (z - pz).toDouble()) < 16.0 * sp) continue
            if (houses.any { Math.hypot((it[0] - x).toDouble(), (it[1] - z).toDouble()) < 9.0 }) continue
            bots++
            val shirts = arrayOf(floatArrayOf(0.7f, 0.2f, 0.18f), floatArrayOf(0.35f, 0.45f, 0.25f), floatArrayOf(0.55f, 0.45f, 0.25f), floatArrayOf(0.3f, 0.3f, 0.34f))
            val shirt = shirts[bots % shirts.size]
            human(
                scene, "Bot " + bots, x, h + 1.2f, z, BR_BOT,
                shirt, floatArrayOf(0.25f, 0.25f, 0.22f), floatArrayOf(shirt[0] * 0.6f, shirt[1] * 0.6f, shirt[2] * 0.6f)
            )
        }

        // ---- storm controller (hidden under the ground at the storm centre) ----
        val zone = mk(scene, Shape.SPHERE, "Zone", zcx, -3f, zcz, 0.3f, 0.3f, 0.3f, floatArrayOf(0.3f, 0.5f, 1f), zoneScript)
        zone.shadow = false

        scene.addJoystick()
        scene.addButton()?.let { it.name = "Jump"; it.x = 90f; it.y = 48f; it.sx = 0.9f }
        scene.addButton()?.let { it.name = "Fire"; it.x = 78f; it.y = 66f; it.sx = 1.3f }
        scene.addButton()?.let { it.name = "Reload"; it.x = 62f; it.y = 70f; it.sx = 0.9f }
        scene.addButton()?.let { it.name = "View"; it.x = 62f; it.y = 52f; it.sx = 0.8f }
        scene.selected = null
    }
}
