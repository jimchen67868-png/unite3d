package com.example.minieditor

/** Built-in sample scenes with Lua scripts attached. */
object Samples {

    /** A sliding door: opens when the player or a soldier comes close, closes when they leave. Bullets are blocked while it is shut. */
    private val BR_DOOR = """-- DOOR: slides open when somebody is close, shuts when they walk away.
local NBOTS = 8

function start()
  self.isOpen = false
  closedX = self.x
  closedY = self.y
  closedZ = self.z
  amt = 0
  want = 0
  checkT = 0
  slide = self.sx + 0.15
  player = find("Player")
  bots = {}
  for k = 1, NBOTS do
    bots[k] = find("Bot " .. k)
  end
end

function near(o)
  if o == nil or o.dead then return false end
  local dx, dz = o.x - self.x, o.z - self.z
  return dx * dx + dz * dz < 25 and math.abs(o.y - self.y) < 3.5
end

function update(dt)
  checkT = checkT - dt
  if checkT <= 0 then
    checkT = 0.15
    local n = near(player)
    if not n then
      for k = 1, NBOTS do
        if near(bots[k]) then
          n = true
          break
        end
      end
    end
    local target = 0
    if n then target = 1 end
    if target ~= want then
      want = target
      playSound("door", 0.6, 0.95 + math.random() * 0.1)
    end
  end
  amt = amt + (want - amt) * math.min(1, dt * 5)
  self.x = closedX - slide * amt
  self.y = closedY
  self.z = closedZ
  self.isOpen = amt > 0.6
  if amt > 0.5 then self.collider = 0 else self.collider = 1 end
end
"""

    private val BR_PLAYER = """-- BATTLE ROYALE player.
-- Joystick = move, drag the screen = look, hold FIRE = shoot, RELOAD, JUMP, VIEW = 1st / 3rd person.
local NBOTS = 8
STAIRS = {}
SEATS = { { -0.45, 0.1 }, { 0.45, 0.1 }, { -0.45, 1.1 }, { 0.45, 1.1 } }   -- driver, front passenger, rear left, rear right

function start()
  self.hp = 100
  self.dead = false
  self.vy = 0
  self.reserveAdd = 0
  fx = {}
  findParts()
  VM = { find("Player vgun"), find("Player vbar"), find("Player vhand"), find("Player vsleeve") }
  for i = 1, #VM do
    if VM[i] ~= nil then VM[i].hud = true end
  end
  vmOn = false
  kick = 0
  vmDip = 0
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
  mag = 30
  reserve = 90
  reloadT = 0
  fireT = 0
  bullets = {}
  walls = {}
  grid = {}
  local di = 1
  while true do
    local dd = find("Door " .. di)
    if dd == nil then break end
    addWall(dd, dd)
    di = di + 1
  end
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
  cars = {}
  local ci = 1
  while true do
    local cc = find("Car " .. ci)
    if cc == nil then break end
    cars[ci] = cc
    ci = ci + 1
  end
  self.car = nil
  carObj = nil
  driveBtn = find("Drive")
  seatBtn = find("Seat")
  if driveBtn ~= nil then driveBtn.opacity = 0 end
  if seatBtn ~= nil then seatBtn.opacity = 0 end
  lastHp = 100
  statT = 8
  over = false
end

-- cars. DRIVE: driver seat (or get out of the driver seat). SEAT: a passenger seat, then the next seat. JUMP: get out.
function setGunVisible(on)
  local v = 0
  if on then v = 1 end
  if P.gun ~= nil then P.gun.opacity = v end
end

-- the DRIVE and SEAT buttons only show next to a car (or while you sit in one)
function updateCarButtons()
  local show = self.car ~= nil
  if not show then show = nearestCar() ~= nil end
  local v = 0
  if show then v = 1 end
  if driveBtn ~= nil then driveBtn.opacity = v end
  if seatBtn ~= nil then seatBtn.opacity = v end
end

function nearestCar()
  local best = nil
  local bd = 5.5
  for i = 1, #cars do
    local c = cars[i]
    local dx, dz = c.x - self.x, c.z - self.z
    local d = math.sqrt(dx * dx + dz * dz)
    if d < bd and math.abs(c.y - self.y) < 3 then best = c; bd = d end
  end
  return best
end

function seatName(n)
  if n == 1 then return "driver seat" end
  if n == 2 then return "front passenger seat" end
  if n == 3 then return "rear left seat" end
  return "rear right seat"
end

function enterCar(c, seat)
  self.car = c.name
  carObj = c
  self.seat = seat
  self.collider = 0
  self.vy = 0
  swimming = false
  setGunVisible(false)
  setVm(false)
  camera.dist = 6.5
  playSound("door", 0.8, 0.8)
  if seat == 1 then
    log("Driver seat: joystick up = gas, down = brake / reverse, left and right = steer. DRIVE = get out, SEAT = other seat, JUMP = jump out")
  else
    log("Sitting in the " .. seatName(seat) .. ". SEAT = next seat, DRIVE = driver seat, JUMP = get out")
  end
end

function leaveCar()
  local c = carObj
  if c ~= nil then
    local r = math.rad(c.ry)
    local side = 3
    if SEATS[self.seat or 1][1] < 0 then side = -3 end
    self.x = c.x + math.cos(r) * side
    self.z = c.z - math.sin(r) * side
    self.y = groundHeight(self.x, self.z) + 1.2
  end
  self.vy = 0
  self.car = nil
  self.seat = nil
  carObj = nil
  self.collider = 1
  setGunVisible(true)
  camera.dist = 3.6
end

function carRide(dt)
  local c = carObj
  if c == nil then
    leaveCar()
    return
  end
  local st = SEATS[self.seat or 1]
  local r = math.rad(c.ry)
  self.x = c.x + math.cos(r) * st[1] + math.sin(r) * st[2]
  self.z = c.z - math.sin(r) * st[1] + math.cos(r) * st[2]
  self.y = c.y + 0.43
  self.vy = 0
  self.ry = c.ry
  -- sitting: legs forward, hands on the wheel (driver) or in the lap (passengers)
  local arm = 15
  if self.seat == 1 then arm = 70 end
  if P.legL ~= nil then P.legL.swing = 80 end
  if P.legR ~= nil then P.legR.swing = 80 end
  if P.armL ~= nil then P.armL.swing = arm end
  if P.armR ~= nil then P.armR.swing = arm end
  setGunVisible(false)
  -- the camera swings round behind the car while you drive (unless you drag the screen)
  if not input.touching and math.abs(c.speed or 0) > 1 then
    local d = ((c.ry - input.yaw + 540) % 360) - 180
    camera.yaw = input.yaw + d * math.min(1, dt * 2.2)
  end
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

function addWall(w, door)
  local e = { x = w.x, y = w.y, z = w.z, hx = w.sx / 2, hy = w.sy / 2, hz = w.sz / 2, door = door }
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
      if w.door == nil or not w.door.isOpen then return true end
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
  local p = math.rad(camera.pitch)
  local fx = -math.cos(p) * math.sin(yaw)
  local fy = -math.sin(p)
  local fz = -math.cos(p) * math.cos(yaw)
  if not camera.firstPerson and camera.x ~= nil then
    -- 3rd person: the bullet flies from the player to the point the crosshair looks at
    local ax, ay, az = camera.x + fx * 45, camera.y + fy * 45, camera.z + fz * 45
    local mx, my, mz = self.x, self.y + 0.4, self.z
    local ddx, ddy, ddz = ax - mx, ay - my, az - mz
    local dl = math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz)
    if dl > 0.1 then fx, fy, fz = ddx / dl, ddy / dl, ddz / dl end
  end
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
  kick = 1
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
function stairAt(px, pz, feet)
  local best = nil
  local bd = 99
  for i = 1, #STAIRS do
    local st = STAIRS[i]
    if px >= st.x0 and px <= st.x1 and pz >= st.z0 and pz <= st.z1 then
      local k
      if st.dir == 1 then
        k = (pz - st.z0) / st.tread
      else
        k = (st.z1 - pz) / st.tread
      end
      if not st.smooth then k = math.ceil(k) end
      if k < 0 then k = 0 end
      if k > st.n then k = st.n end
      local f = st.base + k * st.rise
      local dd = math.abs(f - feet)
      if dd < bd then
        bd = dd
        best = f
      end
    end
  end
  return best
end

-- the floor height the feet can stand on here (the highest of the spots around the body that is within reach)
function stairFloor(x, z, feet)
  local best = stairAt(x, z, feet)
  local r = 0.4
  local a = stairAt(x + r, z, feet)
  if a ~= nil and (best == nil or a > best) then best = a end
  a = stairAt(x - r, z, feet)
  if a ~= nil and (best == nil or a > best) then best = a end
  a = stairAt(x, z + r, feet)
  if a ~= nil and (best == nil or a > best) then best = a end
  a = stairAt(x, z - r, feet)
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
  self.rx = 84 * k
  self.y = groundY + 0.9 - 0.7 * k
  P.legL.swing = 0
  P.legR.swing = 0
  P.armL.swing = 55 + 80 * k
  P.armR.swing = 55 + 80 * k
end

function removeBody()
  for k, part in pairs(P) do
    if part ~= nil then destroy(part) end
  end
  destroy(self)
end

-- ===== first person: the rifle and arm stay in front of the camera =====
function setVm(on)
  local o = 0
  if on then o = 1 end
  for i = 1, #VM do
    if VM[i] ~= nil then VM[i].opacity = o end
  end
end

function placeVm(dt)
  -- the parts are measured from the camera (hud): x = right, y = up, -z = forward
  kick = math.max(0, kick - dt * 9)
  local dipTo = 0
  if reloadT > 0 then dipTo = 0.2 end
  vmDip = vmDip + (dipTo - vmDip) * math.min(1, dt * 10)
  local R, U, F = 0.2, -0.2 - vmDip, 0.55 - kick * 0.06
  if aimT > 0 then R = 0.15; U = -0.17 - vmDip end
  if legAmp > 1 then
    R = R + math.sin(walkT) * 0.01 * (legAmp / 38)
    U = U + math.sin(walkT * 2) * 0.012 * (legAmp / 38)
  end
  local offs = { { 0, 0, 0 }, { 0, 0.005, 0.4 }, { 0.0, -0.09, 0.04 }, { 0.03, -0.11, -0.24 } }
  for i = 1, #VM do
    local p = VM[i]
    if p ~= nil then
      local o = offs[i]
      p.x = R + o[1]
      p.y = U + o[2]
      p.z = -(F + o[3])
      p.rx = kick * 4
    end
  end
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

  -- cars: DRIVE = driver seat, SEAT = passenger seats, JUMP = get out
  updateCarButtons()
  if input.buttonsPressed["Drive"] then
    if self.car == nil then
      local c = nearestCar()
      if c ~= nil then enterCar(c, 1) else log("No car close enough - walk up to a car and tap DRIVE or SEAT") end
    elseif self.seat == 1 then
      leaveCar()
    else
      self.seat = 1
      log("Driver seat")
    end
  elseif input.buttonsPressed["Seat"] then
    if self.car == nil then
      local c = nearestCar()
      if c ~= nil then enterCar(c, 2) else log("No car close enough - walk up to a car and tap DRIVE or SEAT") end
    else
      self.seat = self.seat % 4 + 1
      log("Now in the " .. seatName(self.seat))
    end
  elseif input.jumpPressed and self.car ~= nil then
    leaveCar()
  end
  if self.car ~= nil then
    if self.hp <= 0 then leaveCar() else carRide(dt) return end
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
    local fl = stairFloor(self.x, self.z, self.y - 0.9)
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
    P.armL.opacity = o
    P.armR.opacity = o
    P.gun.opacity = o
  end
  local vmWant = fp and (not swimming) and (not self.dead)
  if vmWant ~= vmOn then
    vmOn = vmWant
    setVm(vmWant)
  end
  if vmWant then placeVm(dt) end
  local leanTo = 0
  if swimming then
    leanTo = -70
    walkT = walkT + dt * (moving and 5 or 2)
    local s = math.sin(walkT)
    P.armL.swing = 115 + 35 * s
    P.armR.swing = 115 - 35 * s
    P.legL.swing = 14 * math.sin(walkT * 2.2)
    P.legR.swing = -14 * math.sin(walkT * 2.2)
  else
    walkPose(dt, moving and (self.grounded or onStairs), sp, aimT > 0)
  end
  self.rx = self.rx + (leanTo - self.rx) * math.min(1, dt * 6)

  -- health
  if self.hp < lastHp and math.floor(self.hp / 10) < math.floor(lastHp / 10) then
    log("HP " .. math.max(0, math.floor(self.hp)))
  end
  lastHp = self.hp
  if self.hp <= 0 then
    self.dead = true
    deadG = groundHeight(self.x, self.z)
    for k, part in pairs(P) do
      if part ~= nil then part.opacity = 1 end
    end
    setVm(false)
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
  local di = 1
  while true do
    local dd = find("Door " .. di)
    if dd == nil then break end
    addWall(dd, dd)
    di = di + 1
  end
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
  swimming = false
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
  self.rx = 84 * k
  self.y = groundY + 0.9 - 0.7 * k
  P.legL.swing = 0
  P.legR.swing = 0
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

function addWall(w, door)
  local e = { x = w.x, y = w.y, z = w.z, hx = w.sx / 2, hy = w.sy / 2, hz = w.sz / 2, door = door }
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
      if w.door == nil or not w.door.isOpen then return true end
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
  -- now and then go for a swim: look for deep water close by
  if math.random() < 0.3 then
    for k = 1, 10 do
      local a2 = math.random() * 6.28
      local d2 = 8 + math.random() * 30
      local px, pz = self.x + math.cos(a2) * d2, self.z + math.sin(a2) * d2
      local w = waterHeight(px, pz)
      if w ~= nil and w - groundHeight(px, pz) > 1.6 then
        local zx2, zz2 = px - zone.x, pz - zone.z
        if math.sqrt(zx2 * zx2 + zz2 * zz2) < (zone.radius or 30) - 3 then
          tx = px
          tz = pz
          wanderT = 8
          break
        end
      end
    end
  end
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

  -- deep water: swim on the surface
  local ground = groundHeight(self.x, self.z)
  local wy = self.waterY
  local depth = 0
  if wy ~= nil then depth = wy - ground end
  if depth > 1.1 then swimming = true elseif depth < 0.7 then swimming = false end

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
    aiming = not swimming
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
      if (not swimming) and (player.diveDepth or 0) < 0.8 and lineClear(self.x, self.y + 0.4, self.z, player.x, player.y + 0.4, player.z) then
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
  if swimming then sp = sp * 0.5 elseif self.inWater then sp = sp * 0.6 end
  self.x = self.x + mx * sp * dt
  self.z = self.z + mz * sp * dt

  if swimming then
    self.vy = 0
    local target = wy - 0.15 + math.sin(time * 3 + self.x) * 0.05
    self.y = self.y + (target - self.y) * math.min(1, dt * 6)
  else
    if self.grounded and self.vy < 0 then self.vy = 0 end
    self.vy = self.vy - world.gravity * dt
    self.y = self.y + self.vy * dt
  end
  if self.y < -8 then
    self.x = home.x
    self.z = home.z
    self.y = groundHeight(home.x, home.z) + 2
    self.vy = 0
  end
  local moving = (mx ~= 0 or mz ~= 0)
  local leanTo = 0
  if swimming then
    -- front crawl, lying flat on the water
    leanTo = -70
    walkT = walkT + dt * (moving and 5 or 2)
    local sw = math.sin(walkT + self.x)
    P.armL.swing = 115 + 35 * sw
    P.armR.swing = 115 - 35 * sw
    P.legL.swing = 14 * math.sin(walkT * 2.2)
    P.legR.swing = -14 * math.sin(walkT * 2.2)
  else
    walkPose(dt, moving and self.grounded, sp, aiming)
  end
  self.rx = self.rx + (leanTo - self.rx) * math.min(1, dt * 6)
  updateBullets(dt)
end
"""

    private val BR_CAR = """-- A car you can drive. Walk up to it and tap DRIVE. Joystick: up = gas, down = brake and reverse, left / right = steer.
function start()
  speed = 0
  heading = self.ry
  self.speed = 0
  self.driven = false
end

function update(dt)
  local pl = find("Player")
  local driven = pl ~= nil and pl.car == self.name and pl.seat == 1
  self.driven = driven
  local throttle = 0
  local steer = 0
  if driven and input.joyActive then
    throttle = input.joyY
    steer = input.joyX
  end
  if throttle > 0.1 then
    local a = 15 * throttle
    if speed < 0 then a = a + 14 end
    speed = speed + a * dt
  elseif throttle < -0.1 then
    if speed > 0.5 then
      speed = speed - 24 * dt
    else
      speed = speed + 11 * throttle * dt
    end
  else
    local drag = 7 * dt
    if speed > 0 then speed = math.max(0, speed - drag) else speed = math.min(0, speed + drag) end
  end
  speed = math.max(-9, math.min(30, speed))
  local dir = 1
  if speed < 0 then dir = -1 end
  heading = heading - steer * 62 * math.min(1, math.abs(speed) / 7) * dir * dt
  self.ry = heading
  local r = math.rad(heading)
  local fx = -math.sin(r)
  local fz = -math.cos(r)
  local nx = self.x + fx * speed * dt
  local nz = self.z + fz * speed * dt
  local gF = groundHeight(nx + fx * 1.9, nz + fz * 1.9)
  local gB = groundHeight(nx - fx * 1.9, nz - fz * 1.9)
  local g = groundHeight(nx, nz)
  if gF ~= nil and gB ~= nil and g ~= nil then
    self.rx = math.deg(math.atan2(gF - gB, 3.8))
    self.y = math.max(g, (gF + gB) / 2) + 0.55
  end
  self.x = nx
  self.z = nz
  self.speed = speed
  if self.waterY ~= nil and self.y < self.waterY then speed = speed * (1 - 1.5 * dt) end
end

function oncollide(other)
  if math.abs(speed) > 3 then
    if self.driven and math.abs(speed) > 7 and other.hp ~= nil then other.hp = other.hp - 40 end
    speed = speed * 0.4
  end
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
    fun battleRoyale(scene: Scene, mode: Int = 0, wheel: ModelData? = null) {
        val large = mode >= 1
        val huge = mode >= 2
        val giant = mode >= 3
        val mega = mode == 4
        val map = if (mega) 1400f else if (giant) 800f else if (huge) 500f else if (large) 300f else 70f
        val sp = map / 70f                       // everything that is a distance scales with the map
        val relief = if (mega) 95f else if (giant) 70f else if (huge) 55f else if (large) 16f else 7f
        val slopeK = if (huge) 2.6f else 1f      // hills are steeper on the huge map, so house sites may be less flat
        val nHouses = if (mega) 8 else if (giant) 6 else if (huge) 5 else if (large) 4 else 3
        // every house gets its own random size: width, depth and ceiling height
        val minW = if (large) 31f else 14f
        val maxW = if (giant) 68f else if (huge) 56f else if (large) 42f else 14f
        val minD = if (large) 28f else 11f
        val maxD = if (giant) 57f else if (huge) 48f else if (large) 36f else 11f
        val minH = 4f
        val maxH = 5f                                // every floor has a ceiling 4 to 5 metres high
        val houseGap = if (mega) 70.0 else if (giant) 56.0 else if (huge) 48.0 else if (large) 40.0 else 24.0
        val maxFloors = 5                            // every house gets 1 to 5 floors at random
        val nTowers = if (mega) 6 else 4
        val towerGap = if (mega) 150.0 else if (giant) 110.0 else 70.0
        val nTrees = if (mega) 110 else if (giant) 80 else if (huge) 90 else if (large) 110 else 40
        val nBots = if (mega) 16 else if (giant) 15 else if (huge) 15 else if (large) 14 else 8
        val nExtraLoot = if (mega) 40 else if (giant) 28 else if (huge) 22 else if (large) 14 else 6
        val doorScript = BR_DOOR.replace("local NBOTS = 8", "local NBOTS = " + nBots)
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
            4 -> BR_ZONE
                .replace("local NPOSTS = 40", "local NPOSTS = 130")
                .replace("local stages = { { 35, 25, 22 }, { 30, 20, 12 }, { 25, 20, 5 } }", "local stages = { { 150, 130, 580 }, { 135, 115, 370 }, { 120, 100, 210 }, { 90, 75, 110 }, { 70, 55, 40 } }")
                .replace("  r = 38\n", "  r = 770\n")
                .replace("\"cube\", self.x, 8, self.z", "\"cube\", self.x, 120, self.z")
                .replace("p.sy = 16", "p.sy = 340")
                .replace("p.sx = 0.7", "p.sx = 3.0")
                .replace("p.sz = 0.7", "p.sz = 3.0")
                .replace("8 enemies on the island", nBots.toString() + " enemies on the island")
            3 -> BR_ZONE
                .replace("local NPOSTS = 40", "local NPOSTS = 96")
                .replace("local stages = { { 35, 25, 22 }, { 30, 20, 12 }, { 25, 20, 5 } }", "local stages = { { 100, 90, 330 }, { 90, 80, 210 }, { 80, 70, 120 }, { 60, 50, 60 }, { 50, 40, 20 } }")
                .replace("  r = 38\n", "  r = 440\n")
                .replace("\"cube\", self.x, 8, self.z", "\"cube\", self.x, 80, self.z")
                .replace("p.sy = 16", "p.sy = 200")
                .replace("p.sx = 0.7", "p.sx = 2.2")
                .replace("p.sz = 0.7", "p.sz = 2.2")
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
            it.x = 12f; it.y = if (mega) 1900f else if (giant) 1100f else if (huge) 700f else if (large) 380f else 95f; it.z = 0.45f; it.ry = 0.55f; it.wind = 0.3f; it.windDir = 60f
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
        grass.x = 0f; grass.z = 0f; grass.y = 0f; grass.sx = if (giant) 180f else if (huge) 140f else if (large) 100f else 50f; grass.sz = if (giant) 180f else if (huge) 140f else if (large) 100f else 50f; grass.rx = 0.6f

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

        // ---- houses (like a real building): random size, ceilings 4 to 5 metres on every floor, 1 to 5 floors, rooms inside,
        //      a stair for each floor and a sliding front door; the ground under the house is levelled, so you just walk in ----
        val stairLua = StringBuilder("{")
        var stairCount = 0
        // one flight of stairs for the player script (it gives the floor height there); dir 0 = climbs towards -z, 1 = towards +z
        fun addStair(x0: Float, x1: Float, z0: Float, z1: Float, tread: Float, n: Int, rise: Float, base: Float, dir: Int, smooth: Boolean = false) {
            if (stairCount++ > 0) stairLua.append(", ")
            stairLua.append("{ x0 = ").append(x0).append(", x1 = ").append(x1).append(", z0 = ").append(z0).append(", z1 = ").append(z1)
                .append(", tread = ").append(tread).append(", n = ").append(n).append(", rise = ").append(rise)
                .append(", base = ").append(base).append(", dir = ").append(dir).append(", smooth = ").append(smooth).append(" }")
        }
        val houseLoot = ArrayList<FloatArray>()      // x, y, z, 0 = medkit / 1 = ammo box
        // level the ground under a house (and a little beyond), so the door is simply walked into: no stairs, no steps
        fun flattenPad(cx: Float, cz: Float, hw: Float, hd: Float, level: Float) {
            val hs = land.heights ?: return
            val n = TerrainGen.N
            val vv = n + 1
            val tgt = ((level - land.y) / maxOf(land.sy, 0.001f)).coerceIn(0f, 1f)
            val u0 = (cx - hw - 0.6f - land.x) / land.sx + 0.5f
            val u1 = (cx + hw + 0.6f - land.x) / land.sx + 0.5f
            val v0 = (cz - hd - 0.6f - land.z) / land.sz + 0.5f
            val v1 = (cz + hd + 0.6f - land.z) / land.sz + 0.5f
            val i0 = Math.floor((u0 * n).toDouble()).toInt()
            val i1 = Math.ceil((u1 * n).toDouble()).toInt()
            val j0 = Math.floor((v0 * n).toDouble()).toInt()
            val j1 = Math.ceil((v1 * n).toDouble()).toInt()
            for (j in (j0 - 1)..(j1 + 1)) for (i in (i0 - 1)..(i1 + 1)) {
                if (i < 0 || j < 0 || i > n || j > n) continue
                val core = i in i0..i1 && j in j0..j1
                val k = j * vv + i
                hs[k] = if (core) tgt else (hs[k] + tgt) / 2f
            }
            land.hVersion++
        }
        val houses = ArrayList<FloatArray>()         // centre x, centre z, floor top y, lowest ground, floors, width, depth, room height
        var tries = 0
        while (houses.size < nHouses && tries < 4000) {
            tries++
            val hw = minW + rnd.nextFloat() * (maxW - minW)
            val hd = minD + rnd.nextFloat() * (maxD - minD)
            val hr = minH + rnd.nextFloat() * (maxH - minH)
            val hx = (rnd.nextFloat() - 0.5f) * 0.7f * map
            val hz = (rnd.nextFloat() - 0.5f) * 0.7f * map
            var lo = 1e9f
            var hi = -1e9f
            var sum = 0f
            var ok = true
            for (fx in floatArrayOf(-0.46f, 0f, 0.46f)) for (fz in floatArrayOf(-0.46f, 0f, 0.46f)) {
                val g = ground(hx + fx * hw, hz + fz * hd)
                if (g == null) ok = false else { lo = minOf(lo, g); hi = maxOf(hi, g); sum += g }
            }
            // a big building may stand on a podium: the foundation reaches down to the lowest ground
            val tol = (2.5f + 0.06f * maxOf(hw, hd)) * (if (tries < 2000) 1f else 1.8f)
            if (!ok || lo < seaY + 0.6f || hi - lo > tol) continue
            val reach = 0.5f * maxOf(hw, hd)
            if (houses.any { Math.hypot((it[0] - hx).toDouble(), (it[1] - hz).toDouble()) < reach + 0.5f * maxOf(it[5], it[6]) + 14.0 + 2.0 * (map / TerrainGen.N) }) continue
            var floors = 1 + rnd.nextInt(maxFloors)
            floors = minOf(floors, maxOf(1, (36f / (hr + 0.3f)).toInt()))     // very high rooms: fewer floors
            val lvl = sum / 9f
            flattenPad(hx, hz, hw, hd, lvl)
            houses.add(floatArrayOf(hx, hz, lvl + 0.02f, lvl, floors.toFloat(), hw, hd, hr))
        }
        var wallN = 0
        // walls, roofs and decks all stop bullets (the scripts look for objects named "Wall n")
        fun wall(x: Float, y: Float, z: Float, sx: Float, sy: Float, sz: Float, color: FloatArray = floatArrayOf(0.78f, 0.70f, 0.55f), pat: Int = 0) {
            wallN++
            val o = mk(scene, Shape.CUBE, "Wall " + wallN, x, y, z, sx, sy, sz, color)
            o.collider = 1
            o.pattern = pat
        }
        var houseN = 0
        var doorN = 0
        val planks = 4
        val tiles = 5
        val plaster = 6
        val concrete = 7
        val woodC = floatArrayOf(0.62f, 0.46f, 0.3f)
        val innerC = floatArrayOf(0.9f, 0.87f, 0.8f)
        val concreteC = floatArrayOf(0.6f, 0.6f, 0.61f)
        // looks of the houses: wall colour, wall material, roof colour
        val wallLooks = arrayOf(
            Triple(floatArrayOf(0.74f, 0.42f, 0.33f), 3, 0), Triple(floatArrayOf(0.9f, 0.84f, 0.68f), plaster, 1),
            Triple(floatArrayOf(0.7f, 0.72f, 0.74f), concrete, 2), Triple(floatArrayOf(0.68f, 0.76f, 0.63f), plaster, 0),
            Triple(floatArrayOf(0.84f, 0.68f, 0.47f), 3, 1), Triple(floatArrayOf(0.94f, 0.86f, 0.58f), plaster, 2)
        )
        val roofLooks = arrayOf(floatArrayOf(0.58f, 0.22f, 0.17f), floatArrayOf(0.4f, 0.28f, 0.2f), floatArrayOf(0.3f, 0.34f, 0.4f))
        // the house being built (the helpers below use these)
        var rh = 3.9f           // room height
        var sillH = 1.0f
        var headH = 2.6f
        var winW = 2.2f
        fun wx(base: Float, z: Float, a: Float, b: Float, y0: Float, y1: Float, th: Float, c: FloatArray, pat: Int) {
            if (b - a < 0.05f || y1 - y0 < 0.05f) return
            wall((a + b) / 2f, base + (y0 + y1) / 2f, z, b - a, y1 - y0, th, c, pat)
        }
        fun wz(base: Float, x: Float, a: Float, b: Float, y0: Float, y1: Float, th: Float, c: FloatArray, pat: Int) {
            if (b - a < 0.05f || y1 - y0 < 0.05f) return
            wall(x, base + (y0 + y1) / 2f, (a + b) / 2f, th, y1 - y0, b - a, c, pat)
        }
        // a long wall with [count] evenly spread window openings (solid below the sill and above the head)
        fun runX(base: Float, z: Float, a: Float, b: Float, count: Int, th: Float, c: FloatArray, pat: Int) {
            var x = a
            for (i in 0 until count) {
                val m = a + (i + 0.5f) * (b - a) / count
                wx(base, z, x, m - winW / 2f, 0f, rh, th, c, pat)
                wx(base, z, m - winW / 2f, m + winW / 2f, 0f, sillH, th, c, pat)
                wx(base, z, m - winW / 2f, m + winW / 2f, headH, rh, th, c, pat)
                x = m + winW / 2f
            }
            wx(base, z, x, b, 0f, rh, th, c, pat)
        }
        fun runZ(base: Float, x: Float, a: Float, b: Float, count: Int, th: Float, c: FloatArray, pat: Int) {
            var zz = a
            for (i in 0 until count) {
                val m = a + (i + 0.5f) * (b - a) / count
                wz(base, x, zz, m - winW / 2f, 0f, rh, th, c, pat)
                wz(base, x, m - winW / 2f, m + winW / 2f, 0f, sillH, th, c, pat)
                wz(base, x, m - winW / 2f, m + winW / 2f, headH, rh, th, c, pat)
                zz = m + winW / 2f
            }
            wz(base, x, zz, b, 0f, rh, th, c, pat)
        }
        // ---- furniture for the houses: tables with chairs, beds, sofas, shelves, wardrobes, kitchen counters, TVs, crates ----
        class Fp(val ox: Float, val oz: Float, val pw: Float, val pd: Float, val y0: Float, val ph: Float, val col: FloatArray, val pat: Int, val solid: Boolean)
        class Fitem(val w: Float, val d: Float, val parts: List<Fp>)
        val fDark = floatArrayOf(0.30f, 0.19f, 0.11f)
        val fLight = floatArrayOf(0.64f, 0.48f, 0.30f)
        val fWhite = floatArrayOf(0.88f, 0.88f, 0.85f)
        val fBlack = floatArrayOf(0.06f, 0.06f, 0.07f)
        val fCrate = floatArrayOf(0.55f, 0.42f, 0.22f)
        val fabrics = arrayOf(
            floatArrayOf(0.45f, 0.47f, 0.50f), floatArrayOf(0.60f, 0.20f, 0.18f), floatArrayOf(0.20f, 0.32f, 0.55f),
            floatArrayOf(0.25f, 0.45f, 0.30f), floatArrayOf(0.78f, 0.70f, 0.52f)
        )
        var furnN = 0
        val withFurniture = false                    // furniture is switched off (the code stays here)
        // a chair at (cx, cz) whose back is on side 0 = +z, 1 = -z, 2 = +x, 3 = -x
        fun chairParts(cx: Float, cz: Float, back: Int): List<Fp> {
            val bx = floatArrayOf(0f, 0f, 0.2f, -0.2f)[back]
            val bz = floatArrayOf(0.2f, -0.2f, 0f, 0f)[back]
            val sideBack = back >= 2
            return listOf(
                Fp(cx, cz, 0.3f, 0.3f, 0f, 0.42f, fDark, planks, false),
                Fp(cx, cz, 0.5f, 0.5f, 0.42f, 0.06f, fLight, planks, false),
                Fp(cx + bx, cz + bz, if (sideBack) 0.06f else 0.5f, if (sideBack) 0.5f else 0.06f, 0.48f, 0.45f, fLight, planks, false)
            )
        }
        // kinds: 0 bed, 1 sofa, 2 bookshelf, 3 wardrobe, 4 kitchen counter, 5 fridge, 6 TV on a cabinet, 7 crate, 8 table with 4 chairs, 9 table with 2 chairs, 10 dresser
        fun makeFurniture(kind: Int): Fitem {
            val fab = fabrics[rnd.nextInt(fabrics.size)]
            return when (kind) {
                0 -> Fitem(1.1f, 2.1f, listOf(
                    Fp(0f, 0f, 1.1f, 2.1f, 0f, 0.35f, fDark, planks, true),
                    Fp(0f, 0.02f, 1.0f, 1.95f, 0.35f, 0.2f, fab, 0, true),
                    Fp(0f, -0.82f, 0.8f, 0.35f, 0.55f, 0.12f, fWhite, 0, false)))
                1 -> Fitem(2.0f, 0.95f, listOf(
                    Fp(0f, 0.05f, 2.0f, 0.85f, 0f, 0.45f, fab, 0, true),
                    Fp(0f, -0.38f, 2.0f, 0.2f, 0.45f, 0.5f, fab, 0, true)))
                2 -> Fitem(1.3f, 0.4f, listOf(Fp(0f, 0f, 1.3f, 0.4f, 0f, 2.0f, fDark, planks, true)))
                3 -> Fitem(1.0f, 0.6f, listOf(Fp(0f, 0f, 1.0f, 0.6f, 0f, 2.2f, fLight, planks, true)))
                4 -> Fitem(2.4f, 0.7f, listOf(
                    Fp(0f, 0f, 2.4f, 0.7f, 0f, 0.88f, fWhite, 0, true),
                    Fp(0f, 0f, 2.5f, 0.78f, 0.88f, 0.05f, fDark, planks, true)))
                5 -> Fitem(0.8f, 0.7f, listOf(Fp(0f, 0f, 0.8f, 0.7f, 0f, 1.8f, fWhite, 0, true)))
                6 -> Fitem(1.4f, 0.45f, listOf(
                    Fp(0f, 0f, 1.4f, 0.45f, 0f, 0.5f, fDark, planks, true),
                    Fp(0f, -0.1f, 1.0f, 0.07f, 0.55f, 0.6f, fBlack, 0, false)))
                7 -> Fitem(0.9f, 0.9f, listOf(Fp(0f, 0f, 0.9f, 0.9f, 0f, 0.9f, fCrate, planks, true)))
                10 -> Fitem(1.2f, 0.5f, listOf(Fp(0f, 0f, 1.2f, 0.5f, 0f, 0.85f, fLight, planks, true)))
                else -> {
                    val four = kind == 8
                    val parts = ArrayList<Fp>()
                    parts.add(Fp(0f, 0f, 1.6f, 0.9f, 0.72f, 0.08f, fLight, planks, true))
                    parts.add(Fp(-0.62f, 0f, 0.1f, 0.75f, 0f, 0.72f, fDark, planks, true))
                    parts.add(Fp(0.62f, 0f, 0.1f, 0.75f, 0f, 0.72f, fDark, planks, true))
                    parts.addAll(chairParts(0f, 0.8f, 0))
                    parts.addAll(chairParts(0f, -0.8f, 1))
                    if (four) {
                        parts.addAll(chairParts(1.15f, 0f, 2))
                        parts.addAll(chairParts(-1.15f, 0f, 3))
                    }
                    Fitem(if (four) 2.8f else 1.6f, 2.1f, parts)
                }
            }
        }
        // puts a piece down: facing 0 = front towards +z, 1 = -z, 2 = +x, 3 = -x
        fun placeFurniture(item: Fitem, facing: Int, px: Float, pz: Float, base: Float, house: Int) {
            val fx = floatArrayOf(0f, 0f, 1f, -1f)[facing]
            val fz = floatArrayOf(1f, -1f, 0f, 0f)[facing]
            val rx = floatArrayOf(1f, -1f, 0f, 0f)[facing]
            val rz = floatArrayOf(0f, 0f, -1f, 1f)[facing]
            for (p in item.parts) {
                val wx = px + rx * p.ox + fx * p.oz
                val wz = pz + rz * p.ox + fz * p.oz
                val sxw = Math.abs(rx) * p.pw + Math.abs(fx) * p.pd
                val szw = Math.abs(rz) * p.pw + Math.abs(fz) * p.pd
                furnN++
                val o = mk(scene, Shape.CUBE, "Furniture " + house + "-" + furnN, wx, base + p.y0 + p.ph / 2f, wz, sxw, p.ph, szw, p.col)
                o.pattern = p.pat
                o.collider = if (p.solid) 1 else 0
                o.shadow = false
            }
        }
        for (h in houses) {
            houseN++
            val cx = h[0]
            val cz = h[1]
            val top = h[2]
            val lo = h[3]
            val floors = h[4].toInt()
            val W = h[5]
            val D = h[6]
            rh = h[7]
            val hw = W / 2f
            val hd = D / 2f
            val floorH = rh + 0.3f                 // room height and a 0.3 slab
            sillH = if (rh < 4.5f) 1.0f else 1.2f
            headH = sillH + minOf(3.2f, rh * 0.45f)
            winW = if (W < 20f) 2.2f else 2.6f + 0.02f * W
            val look = wallLooks[rnd.nextInt(wallLooks.size)]
            val wc = look.first
            val wp = look.second
            val roofC = roofLooks[look.third]
            val slabH = top - lo + 0.3f   // the floor surface is exactly at 'top' (no step at the door)
            val slab = mk(scene, Shape.CUBE, "Floor " + houseN, cx, lo - 0.3f + slabH / 2f, cz, W + 0.6f, slabH, D + 0.6f, woodC)
            slab.collider = 1
            slab.pattern = planks
            // the stairs: along the west wall going north, then the east wall going south, and so on; a long gentle ramp of boards
            val sw = 2.6f                               // width of the stair strip
            val runLen = minOf(D - 4f, floorH * 1.5f)       // how far one flight reaches
            val doorW = if (W < 20f) 2.2f else 3.2f
            val doorH = minOf(rh, 4.2f)
            for (k in 0 until floors) {
                val base = top + k * floorH
                val nx = maxOf(1, Math.round(W / 24f))     // windows along the front and back
                val nz = maxOf(1, Math.round(D / 24f))     // windows along the sides
                if (k == 0) {
                    runX(base, cz - hd, cx - hw, cx + hw, nx, 0.3f, wc, wp)                     // back
                    runZ(base, cx + hw - 0.15f, cz - hd, cz + hd, nz, 0.3f, wc, wp)              // east side
                    wz(base, cx - hw + 0.15f, cz - hd, cz + hd, 0f, rh, 0.3f, wc, wp)             // west side (the stair)
                    // the front: a doorway in the middle, windows on both sides of it
                    runX(base, cz + hd, cx - hw, cx - doorW / 2f, maxOf(1, nx / 2), 0.3f, wc, wp)
                    runX(base, cz + hd, cx + doorW / 2f, cx + hw, maxOf(1, nx / 2), 0.3f, wc, wp)
                    wx(base, cz + hd, cx - doorW / 2f, cx + doorW / 2f, doorH, rh, 0.3f, wc, wp)  // above the door
                } else {
                    wx(base, cz - hd, cx - hw, cx + hw, 0f, rh, 0.3f, wc, wp)
                    wz(base, cx + hw - 0.15f, cz - hd, cz + hd, 0f, rh, 0.3f, wc, wp)
                    wz(base, cx - hw + 0.15f, cz - hd, cz + hd, 0f, rh, 0.3f, wc, wp)
                    runX(base, cz + hd, cx - hw, cx + hw, nx, 0.3f, wc, wp)
                }
                // rooms: a wall across the middle with a doorway (the stair strips stay open) ...
                val pl = cx - hw + sw + 0.4f
                val pr = cx + hw - sw - 0.4f
                wx(base, cz, pl, cx - 1.6f, 0f, rh, 0.2f, innerC, plaster)
                wx(base, cz, cx + 1.6f, pr, 0f, rh, 0.2f, innerC, plaster)
                // ... and on the ground floor two more across the back, with a doorway each: three rooms in the back
                if (k == 0) {
                    for (px in floatArrayOf(cx - W * 0.17f, cx + W * 0.17f)) {
                        val dz = cz - hd * 0.5f
                        wz(base, px, cz - hd + 0.15f, dz - 1.5f, 0f, rh, 0.2f, innerC, plaster)
                        wz(base, px, dz + 1.5f, cz - 0.1f, 0f, rh, 0.2f, innerC, plaster)
                    }
                }
            }
            // the front door slides sideways in front of the wall when somebody comes close (see BR_DOOR)
            doorN++
            val door = mk(scene, Shape.CUBE, "Door " + doorN, cx, top + doorH / 2f, cz + hd + 0.24f, doorW, doorH, 0.12f, floatArrayOf(0.42f, 0.27f, 0.15f), doorScript)
            door.collider = 1
            door.shadow = false
            door.pattern = planks
            // no steps outside: the ground is levelled, you just walk in. A flat landing through the doorway lifts the player over the 2 cm sill.
            addStair(cx - doorW / 2f - 0.2f, cx + doorW / 2f + 0.2f, cz + hd - 1.0f, cz + hd + 1.6f, 0.8f, 1, 0f, top, 0)
            // upper floors: a slab with a hole where the stair comes up
            val zS = cz + hd - 1.0f                      // where a flight going north starts (south end)
            val zN = cz - hd + 1.0f                      // where a flight going south starts (north end)
            for (k in 1 until floors) {
                val level = top + k * floorH
                val westStrip = (k - 1) % 2 == 0          // the flight that arrives here: west side (going north) or east side (going south)
                val sy = level - 0.15f
                if (westStrip) {
                    val capEnd = zS - runLen                  // the ramp reaches this floor here
                    wall(cx + sw / 2f + 0.05f, sy, cz, W - sw + 0.1f, 0.3f, D + 0.2f, woodC, planks)               // everything east of the hole
                    wall(cx - hw + sw / 2f, sy, (cz - hd - 0.1f + capEnd) / 2f, sw + 0.1f, 0.3f, capEnd - (cz - hd - 0.1f), woodC, planks)  // the landing at the north end
                } else {
                    val capStart = zN + runLen
                    wall(cx - sw / 2f - 0.05f, sy, cz, W - sw + 0.1f, 0.3f, D + 0.2f, woodC, planks)
                    wall(cx + hw - sw / 2f, sy, (capStart + cz + hd + 0.1f) / 2f, sw + 0.1f, 0.3f, cz + hd + 0.1f - capStart, woodC, planks)
                }
            }
            // one flight per floor: a single long board ramp for the looks, and the stair list for the player script
            for (j in 0 until floors - 1) {
                val base = top + j * floorH
                val west = j % 2 == 0
                val n = Math.ceil((floorH / 0.6f).toDouble()).toInt()
                val tread = runLen / n
                val rise = floorH / n
                val phi = Math.atan((floorH / runLen).toDouble())
                val len = Math.sqrt((runLen * runLen + floorH * floorH).toDouble()).toFloat()
                val zMid = if (west) zS - runLen / 2f else zN + runLen / 2f
                val xs = if (west) cx - hw + sw / 2f else cx + hw - sw / 2f
                val ramp = mk(
                    scene, Shape.CUBE, "Stair " + houseN + "-" + j, xs, base + floorH / 2f - 0.2f / Math.cos(phi).toFloat(), zMid,
                    sw - 0.3f, 0.4f, len, woodC
                )
                ramp.pattern = planks
                ramp.rx = Math.toDegrees(phi).toFloat() * (if (west) 1f else -1f)
                if (west) addStair(cx - hw, cx - hw + sw, zS - runLen, zS, tread, n, rise, base, 0, true)
                else addStair(cx + hw - sw, cx + hw, zN, zN + runLen, tread, n, rise, base, 1, true)
            }
            wall(cx, top + (floors - 1) * floorH + rh + 0.15f, cz, W + 0.8f, 0.3f, D + 0.8f, roofC, tiles)
            // loot: one in each of the three back rooms of the ground floor, one on each upper floor
            houseLoot.add(floatArrayOf(cx - W * 0.34f, top + 0.5f, cz - hd * 0.7f, 0f))
            houseLoot.add(floatArrayOf(cx, top + 0.5f, cz - hd * 0.7f, 1f))
            houseLoot.add(floatArrayOf(cx + W * 0.34f, top + 0.5f, cz - hd * 0.7f, 0f))
            houseLoot.add(floatArrayOf(cx - W * 0.1f, top + 0.5f, cz + hd * 0.5f, 1f))
            for (k in 1 until floors) {
                houseLoot.add(floatArrayOf(cx + W * 0.12f, top + k * floorH + 0.5f, cz + hd * 0.2f, if (k % 2 == 0) 1f else 0f))
            }
            // furniture on every floor: between the two stair strips, along the walls or free in the room, never in a doorway or on a loot spot
            if (withFurniture) run {
                val ix0 = cx - hw + sw + 0.7f
                val ix1 = cx + hw - sw - 0.7f
                val iz0 = cz - hd + 0.2f
                val iz1 = cz + hd - 0.2f
                val cnt = ((ix1 - ix0) * (iz1 - iz0) / 45f).toInt().coerceIn(2, 6)
                val kinds = intArrayOf(0, 0, 1, 2, 2, 3, 4, 5, 6, 7, 8, 9, 10)
                val pl2 = cx - hw + sw + 0.4f
                val pr2 = cx + hw - sw - 0.4f
                for (k in 0 until floors) {
                    val base = top + k * floorH
                    val bad = ArrayList<FloatArray>()      // rectangles that stay empty: minX, maxX, minZ, maxZ
                    bad.add(floatArrayOf(pl2 - 0.1f, pr2 + 0.1f, cz - 0.1f, cz + 0.1f))          // the wall across the middle
                    bad.add(floatArrayOf(cx - 2.1f, cx + 2.1f, cz - 1.3f, cz + 1.3f))            // and its doorway
                    if (k == 0) {
                        bad.add(floatArrayOf(cx - doorW / 2f - 1.2f, cx + doorW / 2f + 1.2f, cz + hd - 3.4f, cz + hd))   // in front of the door
                        for (px in floatArrayOf(cx - W * 0.17f, cx + W * 0.17f)) {
                            val dz = cz - hd * 0.5f
                            bad.add(floatArrayOf(px - 0.1f, px + 0.1f, cz - hd, cz - 0.1f))       // the walls across the back
                            bad.add(floatArrayOf(px - 1.3f, px + 1.3f, dz - 1.9f, dz + 1.9f))     // and their doorways
                        }
                        val lx = floatArrayOf(cx - W * 0.34f, cx, cx + W * 0.34f, cx - W * 0.1f)
                        val lz = floatArrayOf(cz - hd * 0.7f, cz - hd * 0.7f, cz - hd * 0.7f, cz + hd * 0.5f)
                        for (q in 0 until 4) bad.add(floatArrayOf(lx[q] - 1.0f, lx[q] + 1.0f, lz[q] - 1.0f, lz[q] + 1.0f))   // the loot
                    } else {
                        bad.add(floatArrayOf(cx + W * 0.12f - 1.0f, cx + W * 0.12f + 1.0f, cz + hd * 0.2f - 1.0f, cz + hd * 0.2f + 1.0f))
                    }
                    val placed = ArrayList<FloatArray>()
                    var n = 0
                    var tries = 0
                    while (n < cnt && tries < 80) {
                        tries++
                        val kind = kinds[rnd.nextInt(kinds.size)]
                        val item = makeFurniture(kind)
                        val freeStanding = kind == 7 || kind == 8 || kind == 9
                        var facing = 0
                        var pz = 0f
                        if (!freeStanding) {
                            when (rnd.nextInt(4)) {
                                0 -> { facing = 0; pz = cz - hd + 0.15f + 0.08f + item.d / 2f }           // the back wall
                                1 -> { facing = 0; pz = cz + 0.1f + 0.06f + item.d / 2f }                 // the middle wall, south face
                                2 -> { facing = 1; pz = cz - 0.1f - 0.06f - item.d / 2f }                 // the middle wall, north face
                                else -> { facing = 1; pz = cz + hd - 0.15f - 0.08f - item.d / 2f }        // the front wall
                            }
                        } else {
                            facing = rnd.nextInt(2)
                            pz = iz0 + item.d / 2f + rnd.nextFloat() * maxOf(0f, (iz1 - iz0) - item.d)
                        }
                        val px = ix0 + item.w / 2f + rnd.nextFloat() * maxOf(0f, (ix1 - ix0) - item.w)
                        val x0 = px - item.w / 2f
                        val x1 = px + item.w / 2f
                        val z0 = pz - item.d / 2f
                        val z1 = pz + item.d / 2f
                        if (x0 < ix0 - 0.01f || x1 > ix1 + 0.01f || z0 < iz0 - 0.01f || z1 > iz1 + 0.01f) continue
                        var ok = true
                        for (b in bad) if (x0 < b[1] && x1 > b[0] && z0 < b[3] && z1 > b[2]) { ok = false; break }
                        if (ok) for (b in placed) if (x0 - 0.25f < b[1] && x1 + 0.25f > b[0] && z0 - 0.25f < b[3] && z1 + 0.25f > b[2]) { ok = false; break }
                        if (!ok) continue
                        placeFurniture(item, facing, px, pz, base, houseN)
                        placed.add(floatArrayOf(x0, x1, z0, z1))
                        n++
                    }
                }
            }
        }

        // ---- watchtowers with a long stair up to the deck (huge map) ----
        val towerSpots = ArrayList<FloatArray>()
        if (huge) {
            val H = 10f          // deck height above the tower base
            val nSteps = 20
            val tread = 0.8f
            tries = 0
            while (towerSpots.size < nTowers && tries < 5000) {
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
                if (houses.any { Math.hypot((it[0] - tx).toDouble(), (it[1] - tz).toDouble()) < 0.5 * maxOf(it[5], it[6]) + 24.0 }) continue
                if (houses.any { Math.hypot((it[0] - tx).toDouble(), (it[1] - (tz + 12f)).toDouble()) < 0.5 * maxOf(it[5], it[6]) + 12.0 }) continue
                if (towerSpots.any { Math.hypot((it[0] - tx).toDouble(), (it[1] - tz).toDouble()) < towerGap }) continue
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
                val baseSlab = mk(scene, Shape.CUBE, "Tower base " + tn, tx, lo - 0.3f + slabH / 2f, tz, 9f, slabH, 9f, floatArrayOf(0.55f, 0.55f, 0.57f))
                baseSlab.collider = 1
                baseSlab.pattern = 7
                val legH = H + 3.4f
                for (sx in intArrayOf(-1, 1)) for (sz in intArrayOf(-1, 1)) {
                    wall(tx + sx * 3.2f, T + legH / 2f, tz + sz * 3.2f, 0.6f, legH, 0.6f, floatArrayOf(0.5f, 0.38f, 0.26f), 4)
                }
                wall(tx, T + H - 0.2f, tz, 7.4f, 0.4f, 7.4f, floatArrayOf(0.58f, 0.44f, 0.3f), 4)
                wall(tx, T + H + 0.5f, tz - 3.6f, 7.4f, 1f, 0.2f, floatArrayOf(0.5f, 0.38f, 0.26f), 4)
                wall(tx + 3.6f, T + H + 0.5f, tz, 0.2f, 1f, 7f, floatArrayOf(0.5f, 0.38f, 0.26f), 4)
                wall(tx - 3.6f, T + H + 0.5f, tz, 0.2f, 1f, 7f, floatArrayOf(0.5f, 0.38f, 0.26f), 4)
                wall(tx - 2.4f, T + H + 0.5f, tz + 3.6f, 2.6f, 1f, 0.2f, floatArrayOf(0.5f, 0.38f, 0.26f), 4)
                wall(tx + 2.4f, T + H + 0.5f, tz + 3.6f, 2.6f, 1f, 0.2f, floatArrayOf(0.5f, 0.38f, 0.26f), 4)
                wall(tx, T + H + 3.45f, tz, 8f, 0.3f, 8f, floatArrayOf(0.5f, 0.2f, 0.17f), 5)
                // the stair: 20 steps coming up from the south; the player script walks up them (see STAIRS)
                val z0 = tz + 3.7f
                val z1 = z0 + nSteps * tread
                val rise = (T + H - g1) / nSteps
                val bottom = minOf(g1, T) - 3f
                for (i in 0 until nSteps) {
                    val top = g1 + rise * (i + 1)
                    val zc = z1 - (i + 0.5f) * tread
                    val h = top - bottom
                    mk(scene, Shape.CUBE, "Step " + tn + "-" + (i + 1), tx, bottom + h / 2f, zc, 2.2f, h, tread, floatArrayOf(0.7f, 0.58f, 0.42f)).pattern = 4
                }
                addStair(tx - 1.1f, tx + 1.1f, z0, z1, tread, nSteps, rise, g1, 0)
            }
        }
        stairLua.append("}")

        // ---- cars you can drive (walk up to one and tap DRIVE) ----
        var carN = 0
        val carCols = arrayOf(
            floatArrayOf(0.75f, 0.12f, 0.10f), floatArrayOf(0.12f, 0.25f, 0.70f), floatArrayOf(0.90f, 0.90f, 0.88f),
            floatArrayOf(0.15f, 0.15f, 0.16f), floatArrayOf(0.85f, 0.70f, 0.10f), floatArrayOf(0.15f, 0.50f, 0.25f)
        )
        fun buildCar(x: Float, z: Float, yawDeg: Float) {
            val g = ground(x, z) ?: return
            if (g < seaY + 0.3f) return
            carN++
            val col = carCols[rnd.nextInt(carCols.size)]
            val y = g + 0.55f
            val body = mk(scene, Shape.CUBE, "Car " + carN, x, y, z, 1.9f, 0.5f, 4.2f, col, BR_CAR)
            body.collider = 1
            val dark = floatArrayOf(0.07f, 0.07f, 0.08f)
            val fabric = floatArrayOf(0.35f, 0.33f, 0.30f)
            val parts = ArrayList<SceneObject>()
            fun carPart(dx: Float, dy: Float, dz: Float, psx: Float, psy: Float, psz: Float, c: FloatArray): SceneObject {
                val p = mk(scene, Shape.CUBE, "Car " + carN + " part", x + dx, y + dy, z + dz, psx, psy, psz, c)
                p.shadow = false
                parts.add(p)
                return p
            }
            // glass cabin: you can see the seats and the people sitting in them
            carPart(0f, 0.8f, 0.45f, 1.8f, 1.1f, 2.3f, floatArrayOf(0.55f, 0.68f, 0.78f)).opacity = 0.28f
            carPart(0f, 1.39f, 0.45f, 1.86f, 0.08f, 2.36f, floatArrayOf(col[0] * 0.8f, col[1] * 0.8f, col[2] * 0.8f))   // roof
            carPart(0f, 0.36f, -0.55f, 1.7f, 0.22f, 0.35f, dark)                                                      // dashboard
            for (sx in floatArrayOf(-0.45f, 0.45f)) {                                                                // the two front seats
                carPart(sx, 0.29f, 0.1f, 0.5f, 0.08f, 0.5f, fabric)
                carPart(sx, 0.61f, 0.4f, 0.5f, 0.56f, 0.1f, fabric)
            }
            carPart(0f, 0.29f, 1.1f, 1.5f, 0.08f, 0.5f, fabric)                                                       // the back seat
            carPart(0f, 0.61f, 1.4f, 1.5f, 0.56f, 0.1f, fabric)
            for (wx in floatArrayOf(-0.98f, 0.98f)) for (wz in floatArrayOf(-1.35f, 1.35f)) {                        // round wheels
                val w = if (wheel != null) scene.add(Shape.MODEL, wheel, "Car " + carN + " wheel") else scene.add(Shape.SPHERE, null, "Car " + carN + " wheel")
                w.x = x + wx; w.y = y - 0.15f; w.z = z + wz
                if (wheel != null) { w.sx = 0.8f; w.sy = 0.34f; w.sz = 0.8f; w.rz = 90f } else { w.sx = 0.34f; w.sy = 0.8f; w.sz = 0.8f }
                w.color = dark
                w.shadow = false
                parts.add(w)
            }
            for (lx in floatArrayOf(-0.65f, 0.65f)) carPart(lx, 0.05f, -2.12f, 0.4f, 0.2f, 0.08f, floatArrayOf(1f, 0.95f, 0.7f)).emit = 0.8f   // headlights (front is -z)
            for (p in parts) Rig.bind(p, body)
            body.ry = yawDeg
        }

        // ---- roads between the houses ----
        class RoadSeg(val x0: Float, val z0: Float, val x1: Float, val z1: Float)
        val roadSegs = ArrayList<RoadSeg>()
        val roadPieces = ArrayList<FloatArray>()      // x, z and heading (degrees) of every road piece: cars are parked on them
        fun nearRoad(x: Float, z: Float, margin: Float): Boolean {
            for (sg in roadSegs) {
                val dx = sg.x1 - sg.x0
                val dz = sg.z1 - sg.z0
                val l2 = dx * dx + dz * dz
                var t = if (l2 < 0.001f) 0f else ((x - sg.x0) * dx + (z - sg.z0) * dz) / l2
                t = t.coerceIn(0f, 1f)
                val ex = sg.x0 + dx * t - x
                val ez = sg.z0 + dz * t - z
                if (ex * ex + ez * ez < margin * margin) return true
            }
            return false
        }
        if (large) {
            val roadW = if (huge) 7f else 6f
            val asphalt = floatArrayOf(0.17f, 0.17f, 0.18f)
            // one road end in front of every house door, then join them: always the closest house that is not on the network yet
            val nodes = ArrayList<FloatArray>()
            for (h in houses) nodes.add(floatArrayOf(h[0], h[1] + h[6] * 0.5f + 9f))
            val joined = BooleanArray(nodes.size)
            if (nodes.isNotEmpty()) joined[0] = true
            val edges = ArrayList<FloatArray>()
            var guard = 0
            while (joined.any { !it } && guard++ < 100) {
                var bi = -1
                var bj = -1
                var bd = 1e9
                for (i in nodes.indices) if (!joined[i]) for (j in nodes.indices) if (joined[j]) {
                    val d = Math.hypot((nodes[i][0] - nodes[j][0]).toDouble(), (nodes[i][1] - nodes[j][1]).toDouble())
                    if (d < bd) { bd = d; bi = i; bj = j }
                }
                if (bi < 0) break
                joined[bi] = true
                edges.add(floatArrayOf(nodes[bj][0], nodes[bj][1], nodes[bi][0], nodes[bi][1]))
            }
            var totalLen = 0.0
            for (e in edges) totalLen += Math.hypot((e[2] - e[0]).toDouble(), (e[3] - e[1]).toDouble())
            val pieceLen = maxOf(12f, (totalLen / 200.0).toFloat())      // at most about 200 road pieces
            for (e in edges) {
                roadSegs.add(RoadSeg(e[0], e[1], e[2], e[3]))
                val dx = e[2] - e[0]
                val dz = e[3] - e[1]
                val len = Math.hypot(dx.toDouble(), dz.toDouble()).toFloat()
                val n = Math.ceil((len / pieceLen).toDouble()).toInt().coerceAtLeast(1)
                val yaw = Math.toDegrees(Math.atan2(dx.toDouble(), dz.toDouble())).toFloat()
                val seg = len / n
                for (i in 0 until n) {
                    val ax = e[0] + dx * i / n
                    val az = e[1] + dz * i / n
                    val bx = e[0] + dx * (i + 1) / n
                    val bz = e[1] + dz * (i + 1) / n
                    val mx = (ax + bx) / 2f
                    val mz = (az + bz) / 2f
                    val ga = ground(ax, az) ?: continue
                    val gb = ground(bx, bz) ?: continue
                    val gm = ground(mx, mz) ?: continue
                    if (minOf(ga, gb, gm) < seaY + 0.5f) continue                      // no road through the lakes
                    if (houses.any { Math.abs(it[0] - mx) < it[5] / 2f + 5f && Math.abs(it[1] - mz) < it[6] / 2f + 5f }) continue   // none through a house
                    val y = maxOf((ga + gb) / 2f, gm) + 0.14f
                    val rd = mk(scene, Shape.CUBE, "Road", mx, y, mz, roadW, 0.25f, seg + 0.8f, asphalt)
                    rd.ry = yaw
                    rd.rx = -Math.toDegrees(Math.atan2((gb - ga).toDouble(), seg.toDouble())).toFloat()
                    rd.shadow = false
                    roadPieces.add(floatArrayOf(mx, mz, yaw))
                }
            }
            // cars parked beside the road
            val nCars = if (mega) 7 else if (giant) 5 else if (huge) 4 else 3
            val carSpots = ArrayList<FloatArray>()
            var carTries = 0
            while (carSpots.size < nCars && carTries++ < 300 && roadPieces.isNotEmpty()) {
                val rp = roadPieces[rnd.nextInt(roadPieces.size)]
                val rr = Math.toRadians(rp[2].toDouble())
                val side = if (rnd.nextBoolean()) 1f else -1f
                val cx2 = rp[0] + Math.cos(rr).toFloat() * 1.9f * side
                val cz2 = rp[1] - Math.sin(rr).toFloat() * 1.9f * side
                if (carSpots.any { Math.hypot((it[0] - cx2).toDouble(), (it[1] - cz2).toDouble()) < 25.0 }) continue
                carSpots.add(floatArrayOf(cx2, cz2))
                buildCar(cx2, cz2, rp[2] + (if (rnd.nextBoolean()) 0f else 180f))
            }
        }

        // ---- trees (away from houses and water) ----
        var trees = 0
        tries = 0
        while (trees < nTrees && tries < 1500) {
            tries++
            val tx = (rnd.nextFloat() - 0.5f) * 0.91f * map
            val tz = (rnd.nextFloat() - 0.5f) * 0.91f * map
            val th = ground(tx, tz) ?: continue
            if (th < seaY + 0.4f || th > land.sy * 0.8f) continue
            if (houses.any { Math.hypot((it[0] - tx).toDouble(), (it[1] - tz).toDouble()) < 0.5 * maxOf(it[5], it[6]) + 8.0 }) continue
            if (towerSpots.any { Math.hypot((it[0] - tx).toDouble(), (it[1] - (tz + 10f)).toDouble()) < 16.0 }) continue
            if (nearRoad(tx, tz, 7f)) continue
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
        for (l in houseLoot) {
            if (l[3] < 0.5f) medkit(l[0], l[1], l[2]) else ammo(l[0], l[1], l[2])
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
            if (houses.any { Math.hypot((it[0] - x).toDouble(), (it[1] - z).toDouble()) < 0.5 * maxOf(it[5], it[6]) + 5.0 }) continue
            px = x; pz = z; pg = h
            break
        }
        human(
            scene, "Player", px, pg + 1.2f, pz, playerScriptBase.replace("STAIRS = {}", "STAIRS = " + stairLua),
            floatArrayOf(0.22f, 0.42f, 0.78f), floatArrayOf(0.2f, 0.22f, 0.3f), floatArrayOf(0.2f, 0.3f, 0.5f)
        )
        // the rifle and arm you see in first person: free objects the script keeps in front of the camera
        val vmDark = floatArrayOf(0.14f, 0.14f, 0.16f)
        for ((nm, size, col) in listOf(
            Triple("Player vgun", floatArrayOf(0.07f, 0.09f, 0.55f), vmDark),
            Triple("Player vbar", floatArrayOf(0.035f, 0.035f, 0.3f), vmDark),
            Triple("Player vhand", floatArrayOf(0.09f, 0.09f, 0.3f), floatArrayOf(0.93f, 0.74f, 0.6f)),
            Triple("Player vsleeve", floatArrayOf(0.13f, 0.13f, 0.5f), floatArrayOf(0.22f, 0.42f, 0.78f))
        )) {
            val vm = mk(scene, Shape.CUBE, nm, px, pg + 1.2f, pz, size[0], size[1], size[2], col)
            vm.shadow = false
            vm.opacity = 0.02f
        }

        if (large) buildCar(px + 7f, pz, 0f)     // a car close to where you start

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
            if (houses.any { Math.hypot((it[0] - x).toDouble(), (it[1] - z).toDouble()) < 0.5 * maxOf(it[5], it[6]) + 5.0 }) continue
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
        scene.addButton()?.let { it.name = "Drive"; it.x = 90f; it.y = 30f; it.sx = 0.9f }
        scene.addButton()?.let { it.name = "Seat"; it.x = 75f; it.y = 34f; it.sx = 0.8f }
        Rig.apply(scene)
        scene.selected = null
    }
}
