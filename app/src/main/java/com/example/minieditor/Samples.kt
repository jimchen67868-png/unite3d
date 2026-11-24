package com.example.minieditor

/** Built-in sample scenes with Lua scripts attached. */
object Samples {

    /** A sliding door: opens when the player or a soldier comes close, closes when they leave. Bullets are blocked while it is shut. */
    private val BR_DOOR = """-- DOOR: slides open when somebody is close, shuts when they walk away. The player can also tap the DOOR button next to it
-- to open or close it by hand; it then stays like that for a while (soldiers still open it when they come).
local NBOTS = 8

function start()
  self.isOpen = false
  closedX = self.x
  closedY = self.y
  closedZ = self.z
  amt = 0
  want = 0
  checkT = math.random() * 0.15   -- doors do not all look at the same moment (that made a little stutter every 0.15 s)
  self.collider = 1
  self.toggle = 0
  lastToggle = 0
  manual = false
  manualOpen = false
  manualT = 0
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
  -- the player tapped DOOR next to this door
  local tg = self.toggle or 0
  if tg ~= lastToggle then
    lastToggle = tg
    manual = true
    manualOpen = want < 0.5
    manualT = 25
    if manualOpen then want = 1 else want = 0 end
    playSound("door", 0.6, 0.95 + math.random() * 0.1)
    checkT = 0.3
  end
  if manual then
    manualT = manualT - dt
    if manualT <= 0 then manual = false end
  end
  checkT = checkT - dt
  if checkT <= 0 then
    -- far from the player: look less often
    local far = true
    if player ~= nil then
      local pdx, pdz = player.x - self.x, player.z - self.z
      far = pdx * pdx + pdz * pdz > 2500
    end
    if far then checkT = 0.5 + math.random() * 0.3 else checkT = 0.15 end
    local n = false
    if not manual then n = near(player) end
    if not n then
      for k = 1, NBOTS do
        if near(bots[k]) then
          n = true
          break
        end
      end
    end
    local target = 0
    if n or (manual and manualOpen) then target = 1 end
    if target ~= want then
      want = target
      playSound("door", 0.6, 0.95 + math.random() * 0.1)
    end
  end
  -- only move while it is opening or closing (a door that is settled costs nothing)
  if amt ~= want then
    amt = amt + (want - amt) * math.min(1, dt * 5)
    if math.abs(want - amt) < 0.002 then amt = want end
    self.x = closedX - slide * amt
    self.y = closedY
    self.z = closedZ
    self.isOpen = amt > 0.6
    if amt > 0.5 then self.collider = 0 else self.collider = 1 end
  end
end
"""

    private val BR_PLAYER = """-- BATTLE ROYALE player.
-- Joystick = move, drag the screen = look, hold FIRE = shoot, RELOAD, JUMP, VIEW = 1st / 3rd person.
local NBOTS = 8
STAIRS = {}
TREES = {}
-- ===== weapons: every gun has its own numbers =====
WEAPONS = {
  rifle   = { name = "M416",   mag = 30, dmg = 22, rate = 0.1,   spread = 0.010,  auto = true,  pellets = 1, speed = 420, life = 1.0, ammo = "r556", reload = 2.1, recoil = 0.4, pitch = 1.0,  vol = 0.8,  assist = true,  len = 1.0,  thick = 1.0 },
  smg     = { name = "UMP45",  mag = 25, dmg = 16, rate = 0.075, spread = 0.018,  auto = true,  pellets = 1, speed = 340, life = 0.8, ammo = "r9",   reload = 1.8, recoil = 0.3,  pitch = 1.25, vol = 0.65, assist = true,  len = 0.75, thick = 0.9 },
  pistol  = { name = "P1911",  mag = 12, dmg = 26, rate = 0.2,   spread = 0.012,  auto = false, pellets = 1, speed = 380, life = 0.8, ammo = "r9",   reload = 1.4, recoil = 0.6,  pitch = 1.45, vol = 0.6,  assist = true,  len = 0.5,  thick = 0.8 },
  shotgun = { name = "S12K",   mag = 5,  dmg = 12, rate = 0.85,  spread = 0.07,   auto = false, pellets = 9, speed = 220, life = 0.3, ammo = "g12",  reload = 2.7, recoil = 1.6,  pitch = 0.6,  vol = 1.0,  assist = false, len = 0.95, thick = 1.3 },
  sniper  = { name = "Kar98k", mag = 5,  dmg = 95, rate = 1.5,   spread = 0.0015, auto = false, pellets = 1, speed = 650, life = 1.2, ammo = "r762", reload = 3.2, recoil = 1.8,  pitch = 0.5,  vol = 1.0,  assist = false, len = 1.35, thick = 0.9 }
}
-- shooting distance: full damage up to "near" metres, then less and less until "far" metres, where the bullet is gone
-- (at "far" the damage is "minD" times the full damage)
WEAPONS.rifle.near, WEAPONS.rifle.far, WEAPONS.rifle.minD = 50, 300, 0.55
WEAPONS.smg.near, WEAPONS.smg.far, WEAPONS.smg.minD = 25, 120, 0.4
WEAPONS.pistol.near, WEAPONS.pistol.far, WEAPONS.pistol.minD = 20, 100, 0.4
WEAPONS.shotgun.near, WEAPONS.shotgun.far, WEAPONS.shotgun.minD = 8, 50, 0.15
WEAPONS.sniper.near, WEAPONS.sniper.far, WEAPONS.sniper.minD = 200, 750, 0.85

-- after you stop shooting, the view settles back down by the amount the gun kicked it up
-- (what you already pulled down by hand counts as settled)
function updateRecoil(dt)
  sinceShot = sinceShot + dt
  local dp = camera.pitch - lastPitch
  if dp > 0 then recoilAcc = math.max(0, recoilAcc - dp) end
  if sinceShot > 0.12 and recoilAcc > 0 then
    local rec = math.min(recoilAcc, 10 * dt)
    camera.pitch = camera.pitch + rec
    recoilAcc = recoilAcc - rec
  end
  lastPitch = camera.pitch
end

function rangeMul(d, near, far, minD)
  if near == nil or d <= near then return 1 end
  if d >= far then return minD end
  return 1 - (1 - minD) * (d - near) / (far - near)
end

WEAPONS.rifle.ads = { 38 }          -- field of view when you aim (the normal one is 50); the sniper has two zoom steps and a scope
WEAPONS.smg.ads = { 42 }
WEAPONS.pistol.ads = { 44 }
WEAPONS.shotgun.ads = { 42 }
WEAPONS.sniper.ads = { 12.5, 6.3 }
WEAPONS.sniper.scope = true
aimLevel = 0
AMMONAMES = { r556 = "5.56", r9 = "9mm", g12 = "12 gauge", r762 = "7.62" }
AMMOCAP = { r556 = 240, r9 = 150, g12 = 40, r762 = 40 }
MAXWEAPONS = 3
MAXGREN = 5
MAXMED = 4
grenList = {}
shells = {}
tankT = 0
tankAimT = 0
chute = false
chuteT = 0
bloom = 0
SEATS = { { -0.45, -0.3 }, { 0.45, -0.3 }, { -0.45, 0.7 }, { 0.45, 0.7 } }   -- driver, front passenger, rear left, rear right

function start()
  self.hp = 100
  self.dead = false
  self.vy = 0
  self.reserveAdd = 0
  fx = {}
  decals = {}
  decalT = 0
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
  -- the bag: weapons (max 3), ammo of four kinds, grenades and medkits
  slots = { "rifle", "sniper", "pistol" }
  cur = 1
  W = WEAPONS[slots[cur]]
  mags = { rifle = 30, sniper = WEAPONS.sniper.mag, pistol = 12 }
  ammoBag = { r556 = 90, r9 = 36, g12 = 0, r762 = 25 }
  grenades = 2
  medkits = 1
  bagOpenState = false
  bagT = 0
  recoilAcc = 0
  sinceShot = 1
  lastPitch = camera.pitch or 0
  camera.bagOpen = false
  camera.bagText = ""
  grenList = {}
  shells = {}
  tankT = 0
  tankAimT = 0
  chute = false
  chuteT = 0
  bloom = 0
  mag = mags[slots[cur]]
  reserve = ammoBag[W.ammo]
  gunBase = nil
  aimLevel = 0
  camera.fov = 50
  reloadT = 0
  fireT = 0
  bullets = {}
  walls = {}
  grid = {}
  doors = {}
  drops = {}
  nearDoor = nil
  doorT = 0
  doorBtn = find("Door")
  if doorBtn ~= nil then doorBtn.opacity = 0 end
  local di = 1
  while true do
    local dd = find("Door " .. di)
    if dd == nil then break end
    addWall(dd, dd)
    doors[di] = dd
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
  for _, prefix in ipairs({ "Car ", "Bike ", "Heli ", "Boat ", "Plane ", "Tank " }) do
    local ci = 1
    while true do
      local cc = find(prefix .. ci)
      if cc == nil then break end
      cars[#cars + 1] = cc
      ci = ci + 1
    end
  end
  buildTreeGrid()
  self.wallAt = pointInWall   -- the vehicles ask these to stop at walls and trees
  self.treeAt = treeAt
  self.roofAt = roofTop
  nearCars = {}
  hitKind = 1
  climbBtn = find("Climb")
  descBtn = find("Descend")
  if climbBtn ~= nil then climbBtn.opacity = 0 end
  if descBtn ~= nil then descBtn.opacity = 0 end
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

-- ===== the bag =====
function storeAmmo()
  mags[slots[cur]] = mag
  ammoBag[W.ammo] = reserve
end

function syncAmmo()
  W = WEAPONS[slots[cur]]
  mag = mags[slots[cur]] or 0
  reserve = ammoBag[W.ammo] or 0
end

-- the gun in the hands: its length and thickness (first person model and the one in third person)
function applyGunLook()
  if gunBase == nil then
    gunBase = {}
    local function grab(o)
      if o ~= nil then return { o.sx, o.sy, o.sz } end
      return nil
    end
    gunBase.v1 = grab(VM[1])
    gunBase.v2 = grab(VM[2])
    gunBase.pg = grab(P.gun)
  end
  local function scale(o, b)
    if o ~= nil and b ~= nil then
      o.sx = b[1] * W.thick
      o.sy = b[2] * W.thick
      o.sz = b[3] * W.len
    end
  end
  scale(VM[1], gunBase.v1)
  scale(VM[2], gunBase.v2)
  scale(P.gun, gunBase.pg)
end

-- AIM: zoom in (the sniper has a scope with two zoom steps); tap again for the next step, then back to normal
function applyAim()
  if aimLevel > 0 and W.ads[aimLevel] ~= nil then
    camera.fov = W.ads[aimLevel]
  else
    aimLevel = 0
    camera.fov = 50
  end
end

function toggleAim()
  aimLevel = (aimLevel + 1) % (#W.ads + 1)
  applyAim()
  if aimLevel > 0 then playSound("reload", 0.3, 2.0) end
end

function switchWeapon(k)
  if reloadT > 0 then reloadT = 0 end
  storeAmmo()
  cur = k
  syncAmmo()
  aimLevel = 0
  applyAim()
  applyGunLook()
  fireT = 0.3
  playSound("reload", 0.5, 1.5)
  log("Weapon: " .. W.name .. "   " .. mag .. " / " .. reserve .. "  (" .. AMMONAMES[W.ammo] .. ")   full damage to " .. W.near .. " m, range " .. W.far .. " m")
end

function nextWeapon()
  if #slots > 1 then
    switchWeapon(cur % #slots + 1)
  else
    log("You carry only one weapon")
  end
end

function addAmmo(key, n)
  ammoBag[key] = math.min(AMMOCAP[key], (ammoBag[key] or 0) + n)
  if W.ammo == key then reserve = ammoBag[key] end
  log("+" .. n .. " " .. AMMONAMES[key] .. " ammo  (" .. ammoBag[key] .. ")")
end

function addWeapon(key, magOverride)
  local w = WEAPONS[key]
  if w == nil then return end
  for i = 1, #slots do
    if slots[i] == key then
      addAmmo(w.ammo, magOverride or w.mag)
      log("You already carry the " .. w.name .. " - took its ammo")
      return
    end
  end
  storeAmmo()
  if #slots < MAXWEAPONS then
    slots[#slots + 1] = key
    mags[key] = magOverride or w.mag
    log("Picked up the " .. w.name .. "  (bag: " .. #slots .. " / " .. MAXWEAPONS .. " weapons)")
    switchWeapon(#slots)
  else
    -- the bag is full: the weapon in your hands is swapped for the new one
    local old = slots[cur]
    ammoBag[WEAPONS[old].ammo] = math.min(AMMOCAP[WEAPONS[old].ammo], (ammoBag[WEAPONS[old].ammo] or 0) + (mags[old] or 0))
    mags[old] = nil
    slots[cur] = key
    mags[key] = magOverride or w.mag
    syncAmmo()
    applyGunLook()
    log("Swapped the " .. WEAPONS[old].name .. " for the " .. w.name)
  end
  if magOverride == nil then addAmmo(w.ammo, w.mag) end
end

-- ===== dropping a weapon =====
ACOL = { r556 = { 0.95, 0.8, 0.15 }, r9 = { 0.95, 0.5, 0.1 }, g12 = { 0.85, 0.2, 0.2 }, r762 = { 0.25, 0.45, 0.95 } }

-- puts a small thing on the ground in front of the player: kind = "weapon", "med", "ammo" or "gren"
function placeDrop(kind, key, amt, sx, sy, sz, r, g, b)
  local yaw = math.rad(input.yaw)
  local x = self.x - math.sin(yaw) * 1.4 + (math.random() - 0.5) * 0.5
  local z = self.z - math.cos(yaw) * 1.4 + (math.random() - 0.5) * 0.5
  local gy = groundHeight(x, z)
  local ok, o = pcall(spawn, "cube", x, gy + 0.14, z)
  if not ok then return false end
  o.sx = sx
  o.sy = sy
  o.sz = sz
  o.r = r
  o.g = g
  o.b = b
  o.emit = 0.45
  o.shadow = false
  o.collider = 0
  o.ry = math.deg(yaw)
  drops[#drops + 1] = { o = o, kind = kind, key = key, mag = amt, amt = amt, t = 0, armed = false, y = gy + 0.14 }
  if #drops > 30 then
    destroy(drops[1].o)
    table.remove(drops, 1)
  end
  return true
end

function spawnDrop(key, m)
  local w = WEAPONS[key]
  local c = ACOL[w.ammo]
  placeDrop("weapon", key, m, 0.16, 0.12, 0.9 * w.len, c[1], c[2], c[3])
end

-- ammo: a pack of bullets is dropped each time (30 / 25 / 5 / 5 by kind)
DROPPACK = { r556 = 30, r9 = 25, g12 = 5, r762 = 5 }

function dropAmmo(key)
  if self.car ~= nil then log("Get out of the vehicle first") return end
  storeAmmo()
  local have = ammoBag[key] or 0
  if have <= 0 then log("You have no " .. AMMONAMES[key] .. " ammo to drop") return end
  local n = math.min(have, DROPPACK[key])
  if not placeDrop("ammo", key, n, 0.3, 0.18, 0.22, ACOL[key][1], ACOL[key][2], ACOL[key][3]) then return end
  ammoBag[key] = have - n
  syncAmmo()
  playSound("pickup", 0.6, 0.7)
  log("Dropped " .. n .. " " .. AMMONAMES[key] .. " bullets. Walk over them to take them again")
end

function dropMed()
  if self.car ~= nil then log("Get out of the vehicle first") return end
  if medkits <= 0 then log("You have no medkit to drop") return end
  if not placeDrop("med", nil, 1, 0.34, 0.2, 0.42, 0.95, 0.3, 0.3) then return end
  medkits = medkits - 1
  playSound("pickup", 0.6, 0.7)
  log("Dropped a medkit. Walk over it to take it again")
end

function dropGren()
  if self.car ~= nil then log("Get out of the vehicle first") return end
  if grenades <= 0 then log("You have no grenade to drop") return end
  if not placeDrop("gren", nil, 1, 0.2, 0.2, 0.3, 0.3, 0.5, 0.25) then return end
  grenades = grenades - 1
  playSound("pickup", 0.6, 0.7)
  log("Dropped a grenade. Walk over it to take it again")
end

-- takes a dropped thing into the bag; false when the bag has no room for it (it stays on the ground)
function pickDrop(d)
  if d.kind == "weapon" then
    addWeapon(d.key, d.mag)
    return true
  elseif d.kind == "med" then
    if medkits >= MAXMED then return false end
    medkits = math.min(MAXMED, medkits + d.amt)
    playSound("pickup", 0.8)
    log("Medkit in the bag (" .. medkits .. ")")
    return true
  elseif d.kind == "gren" then
    if grenades >= MAXGREN then return false end
    grenades = math.min(MAXGREN, grenades + d.amt)
    playSound("pickup", 0.8)
    log("Grenade in the bag (" .. grenades .. ")")
    return true
  elseif d.kind == "ammo" then
    if (ammoBag[d.key] or 0) >= AMMOCAP[d.key] then return false end
    playSound("pickup", 0.8)
    addAmmo(d.key, d.amt)
    return true
  end
  return true
end

-- drops the weapon in slot n (the one in your hands if n is not given); you keep at least one weapon
function dropWeapon(n)
  if self.car ~= nil then log("Get out of the vehicle first") return end
  n = n or cur
  if slots[n] == nil then return end
  if #slots <= 1 then log("You must keep one weapon") return end
  storeAmmo()
  local key = slots[n]
  local m = mags[key] or 0
  table.remove(slots, n)
  mags[key] = nil
  if n < cur then
    cur = cur - 1
  elseif cur > #slots then
    cur = #slots
  end
  reloadT = 0
  syncAmmo()
  aimLevel = 0
  applyAim()
  applyGunLook()
  fireT = 0.3
  spawnDrop(key, m)
  playSound("pickup", 0.6, 0.7)
  log("Dropped the " .. WEAPONS[key].name .. " (" .. m .. " rounds in it). Walk over it to pick it up again")
end

-- dropped weapons turn slowly on the ground; walk away from one and back to take it again
function updateDrops(dt)
  for i = #drops, 1, -1 do
    local d = drops[i]
    d.t = d.t + dt
    d.o.ry = d.o.ry + 50 * dt
    d.o.y = d.y + math.sin(d.t * 3) * 0.05
    local dx, dz = d.o.x - self.x, d.o.z - self.z
    local dd = dx * dx + dz * dz
    if not d.armed then
      if dd > 6.5 then d.armed = true end
    elseif dd < 2.25 and self.car == nil and not self.dead then
      if pickDrop(d) then
        destroy(d.o)
        table.remove(drops, i)
      end
    end
  end
end

-- loot scripts leave their gifts in these fields of the player; they are put into the bag here
function collectLoot()
  if (self.addR556 or 0) > 0 then addAmmo("r556", self.addR556); self.addR556 = 0 end
  if (self.addR9 or 0) > 0 then addAmmo("r9", self.addR9); self.addR9 = 0 end
  if (self.addG12 or 0) > 0 then addAmmo("g12", self.addG12); self.addG12 = 0 end
  if (self.addR762 or 0) > 0 then addAmmo("r762", self.addR762); self.addR762 = 0 end
  if (self.addGren or 0) > 0 then
    grenades = math.min(MAXGREN, grenades + self.addGren)
    self.addGren = 0
    log("Grenades: " .. grenades)
  end
  if (self.addMed or 0) > 0 then
    medkits = math.min(MAXMED, medkits + self.addMed)
    self.addMed = 0
    log("Medkit in the bag (" .. medkits .. "). Tap HEAL to use one")
  end
  if self.addWeapon ~= nil and self.addWeapon ~= "" then
    local k = self.addWeapon
    self.addWeapon = ""
    addWeapon(k)
  end
end

function useMedkit()
  if medkits <= 0 then
    log("No medkit in the bag")
  elseif self.hp >= 100 then
    log("Your health is full")
  else
    medkits = medkits - 1
    self.hp = math.min(100, self.hp + 40)
    playSound("pickup", 0.8)
    log("Medkit used +40 HP  (" .. math.floor(self.hp) .. ").  Medkits left: " .. medkits)
  end
end

-- the backpack screen (drawn by the app like the PUBG bag): the lines are sent in camera.bagText
function bagLines()
  storeAmmo()
  local t = {}
  local used = 0
  for i = 1, MAXWEAPONS do
    local k = slots[i]
    if k ~= nil then
      local a = 0
      if i == cur then a = 1 end
      t[#t + 1] = "W|" .. WEAPONS[k].name .. "|" .. (mags[k] or 0) .. "|" .. WEAPONS[k].mag .. "|" .. AMMONAMES[WEAPONS[k].ammo] .. "|" .. a
      used = used + 8
    else
      t[#t + 1] = "E"
    end
  end
  local order = { "r556", "r9", "g12", "r762" }
  local cols = { r556 = "F2CC26", r9 = "F28019", g12 = "D93333", r762 = "4073F2" }
  for n = 1, 4 do
    local key = order[n]
    local c = ammoBag[key] or 0
    t[#t + 1] = "A|" .. AMMONAMES[key] .. "|" .. c .. "|" .. AMMOCAP[key] .. "|" .. cols[key]
    used = used + math.floor(c * 0.25)
  end
  t[#t + 1] = "G|" .. grenades .. "|" .. MAXGREN
  t[#t + 1] = "M|" .. medkits .. "|" .. MAXMED
  used = used + grenades * 4 + medkits * 6
  t[#t + 1] = "C|" .. used .. "|200"
  return table.concat(t, "\n")
end

function setBag(on)
  bagOpenState = on
  camera.bagOpen = on
  if on then
    camera.bagText = bagLines()
    bagT = 0.2
    playSound("pickup", 0.5, 1.3)
  end
end

function bagTapped(k)
  if k == "close" then
    setBag(false)
  elseif k == "med" then
    useMedkit()
  elseif k == "dmed" then
    dropMed()
  elseif k == "dgren" then
    dropGren()
  elseif string.sub(k, 1, 2) == "da" then
    local order = { "r556", "r9", "g12", "r762" }
    local key = order[tonumber(string.sub(k, 3))]
    if key ~= nil then dropAmmo(key) end
  elseif string.sub(k, 1, 1) == "d" and #k == 2 then
    dropWeapon(tonumber(string.sub(k, 2)))
  elseif string.sub(k, 1, 1) == "w" then
    local n = tonumber(string.sub(k, 2))
    if n ~= nil and slots[n] ~= nil and n ~= cur then switchWeapon(n) end
  end
  if bagOpenState then camera.bagText = bagLines() end
end

-- WEAPON, GRENADE, HEAL and BAG buttons (work on foot and in a vehicle)
function handleItems(dt)
  if gunBase == nil then applyGunLook() end
  bloom = math.max(0, bloom - dt * 0.09)
  updateRecoil(dt)
  if input.buttonsPressed["Weapon"] then nextWeapon() end
  if input.buttonsPressed["Aim"] then toggleAim() end
  if input.buttonsPressed["Grenade"] then throwGrenade() end
  if input.buttonsPressed["Heal"] then useMedkit() end
  if input.buttonsPressed["Bag"] then setBag(not bagOpenState) end
  local tap = input.bagTap
  if tap ~= nil and tap ~= "" and bagOpenState then bagTapped(tap) end
  if bagOpenState then
    bagT = bagT - dt
    if bagT <= 0 then
      bagT = 0.2
      camera.bagText = bagLines()
    end
  end
  self.nMed = medkits
  self.nGren = grenades
end

-- vehicles (cars, motorcycles, helicopters). DRIVE: driver / pilot seat (or get out of it). SEAT: a passenger seat, then the next seat. JUMP: get out.
function setGunVisible(on)
  local v = 0
  if on then v = 1 end
  if P.gun ~= nil then P.gun.opacity = v end
end

-- the buttons only show next to a vehicle (or while you sit in one); CLIMB and DESCEND only while you fly a helicopter
function updateCarButtons()
  local show = self.car ~= nil
  if not show then show = nearestCar() ~= nil end
  local v = 0
  if show then v = 1 end
  if driveBtn ~= nil then driveBtn.opacity = v end
  if seatBtn ~= nil then seatBtn.opacity = v end
  local fly = self.car ~= nil and self.seat == 1 and carObj ~= nil and (carObj.kind == "heli" or carObj.kind == "plane")
  local vf = 0
  if fly then vf = 1 end
  if climbBtn ~= nil then climbBtn.opacity = vf end
  if descBtn ~= nil then descBtn.opacity = vf end
end

-- the door that is close enough to open or close by hand; the DOOR button shows only then
function updateDoorButton(dt)
  doorT = doorT - dt
  if doorT > 0 then return end
  doorT = 0.15
  nearDoor = nil
  if self.car == nil and not self.dead then
    local bd = 12.25
    for i = 1, #doors do
      local d = doors[i]
      local dx, dz = d.x - self.x, d.z - self.z
      local dd = dx * dx + dz * dz
      if dd < bd and math.abs(d.y - self.y) < 3.5 then
        nearDoor = d
        bd = dd
      end
    end
  end
  if doorBtn ~= nil then
    if nearDoor ~= nil then doorBtn.opacity = 1 else doorBtn.opacity = 0 end
  end
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

-- where a seat is: x right, z back (from the middle of the vehicle), and how high the rider's middle is
function seatDef(c, n)
  local d = c.seatsDef
  if d ~= nil and d[n] ~= nil then return d[n] end
  local st = SEATS[n] or SEATS[1]
  return { st[1], st[2], 0.43 }
end

function seatName(n)
  local k = nil
  if carObj ~= nil then k = carObj.kind end
  if n == 1 then
    if k == "heli" or k == "plane" then return "pilot seat" end
    return "driver seat"
  end
  if k == "bike" then return "passenger seat" end
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
  camera.dist = c.camDist or 6.5
  camera.yaw = c.ry     -- the camera starts behind the vehicle
  playSound("door", 0.8, 0.8)
  if seat == 1 and c.kind == "heli" then
    log("Pilot seat: the rotor spins up. Joystick up / down = forward / back, left / right = turn. CLIMB and DESCEND buttons = height. You can shoot. DRIVE = get out (in the air you jump with a parachute; you can land on roofs)")
  elseif seat == 1 and c.kind == "plane" then
    log("Pilot seat: joystick up / down = engine power, left / right = steer and bank. Take off: full power along the runway, then hold CLIMB. DESCEND = nose down. DRIVE = get out (after landing and stopping)")
  elseif seat == 1 and c.kind == "tank" then
    log("Tank driver: joystick up / down = forward / back, left / right = turn (also on the spot). The cannon follows the crosshair. FIRE = shell (every 2.5 s). Enemies in front get run over. DRIVE = get out")
  elseif seat == 1 then
    log("Driver seat: joystick up = gas, down = brake / reverse, left and right = steer. You can shoot. DRIVE = get out, SEAT = other seat, JUMP = jump out")
  else
    log("Sitting in the " .. seatName(seat) .. ". Aim and shoot. SEAT = next seat, DRIVE = driver seat, JUMP = get out")
  end
end

function leaveCar(force)
  local c = carObj
  if not force and c ~= nil and c.kind == "plane" and ((c.agl or 0) > 1 or math.abs(c.speed or 0) > 5) then
    log("Land and stop the plane first")
    return
  end
  local jumped = false
  if not force and c ~= nil and c.kind == "heli" and (c.agl or 0) > 2.5 then
    jumped = true   -- you jump out of the helicopter in the air: the parachute opens after a moment
  end
  if c ~= nil then
    local r = math.rad(c.ry)
    local side = 3
    if c.kind == "heli" then side = (c.roof and 2.6 or 4) elseif c.kind == "plane" then side = 3.5 elseif c.kind == "tank" then side = 3.6 end
    if seatDef(c, self.seat or 1)[1] < 0 then side = -side end
    self.x = c.x + math.cos(r) * side
    self.z = c.z - math.sin(r) * side
    self.y = groundHeight(self.x, self.z) + 1.2
    if jumped then
      self.y = c.y - 0.3
    elseif c.kind == "heli" then
      self.y = math.max(self.y, c.y + 0.35)   -- standing on a roof
    end
  end
  self.vy = 0
  if jumped then
    chute = true
    chuteT = 0
    log("You jumped out! The parachute opens in a moment")
  end
  self.car = nil
  self.seat = nil
  carObj = nil
  self.collider = 1
  fpWas = nil          -- the body / gun visibility is set again in the next frame
  camera.dist = 3.6
end

-- shooting and reloading (on foot and inside a vehicle)
function startReload()
  aimLevel = 0
  applyAim()
  reloadT = W.reload
  log("Reloading the " .. W.name .. "...")
  playSound("reload", 0.8)
end

function handleShooting(dt)
  if carObj ~= nil and carObj.kind == "tank" and self.seat == 1 then
    tankFire(dt)   -- the tank driver fires the cannon, not the rifle
    return
  end
  fireT = fireT - dt
  if reloadT > 0 then
    reloadT = reloadT - dt
    if reloadT <= 0 then
      local take = math.min(W.mag - mag, reserve)
      mag = mag + take
      reserve = reserve - take
      storeAmmo()
      log("Reloaded  " .. mag .. " / " .. reserve)
    end
  elseif input.buttonsPressed["Reload"] and mag < W.mag and reserve > 0 then
    startReload()
  elseif ((input.buttons["Fire"] and W.auto) or input.buttonsPressed["Fire"]) and fireT <= 0 then
    if mag > 0 then
      shoot()
    elseif reserve > 0 then
      startReload()
    else
      fireT = 1
      log("Out of " .. AMMONAMES[W.ammo] .. " ammo for the " .. W.name .. "! Find ammo or tap WEAPON.")
    end
  end
end

function carRide(dt)
  local c = carObj
  if c == nil then
    leaveCar(true)
    return
  end
  local st = seatDef(c, self.seat or 1)
  local r = math.rad(c.ry)
  self.x = c.x + math.cos(r) * st[1] + math.sin(r) * st[2]
  self.z = c.z - math.sin(r) * st[1] + math.cos(r) * st[2]
  self.y = c.y + st[3]
  self.vy = 0
  -- the tank's turret follows the crosshair
  if c.kind == "tank" and self.seat == 1 then
    tankAimT = tankAimT - dt
    if tankAimT <= 0 then
      tankAimT = 0.05
      local tfx, tfy, tfz = camDir()
      c.tx, c.ty, c.tz = aimPoint(tfx, tfy, tfz)
    end
  end
  -- shooting from any seat: the aim follows the camera, the glass does not stop bullets
  handleShooting(dt)
  aimT = aimT - dt
  local aiming = aimT > 0
  if aiming then self.ry = input.yaw else self.ry = c.ry end
  -- the body is hidden in first person
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
  end
  setGunVisible(aiming and not fp)
  local vmWant = fp and not (aimLevel > 0 and W.scope == true)
  if vmWant ~= vmOn then
    vmOn = vmWant
    setVm(vmWant)
  end
  if vmWant then placeVm(dt) end
  -- sitting: legs forward; hands on the wheel (driver), the weapon raised when aiming, otherwise in the lap
  local arm = 15
  if self.seat == 1 then arm = 70 end
  if aiming then arm = 88 end
  local leg = c.legSwing or 80
  if P.legL ~= nil then P.legL.swing = leg end
  if P.legR ~= nil then P.legR.swing = leg end
  if P.armL ~= nil then P.armL.swing = arm end
  if P.armR ~= nil then P.armR.swing = arm end
  -- the driver's camera swings round behind the vehicle (unless you drag the screen)
  if self.seat == 1 and c.kind ~= "heli" and c.kind ~= "tank" and not input.touching and aimT <= 0 and not input.buttons["Fire"] and math.abs(c.speed or 0) > 0.5 then
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
  local e = { x = w.x, y = w.y, z = w.z, hx = w.sx / 2, hy = w.sy / 2, hz = w.sz / 2, door = door, r = w.r or 0.7, g = w.g or 0.7, b = w.b or 0.7 }
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

-- the top of the highest wall / roof box under (x, y) at this x, z (nil if none): a helicopter lands on it
function roofTop(x, y, z)
  local c = grid[math.floor(x / CELL) * 100003 + math.floor(z / CELL)]
  if c == nil then return nil end
  local best = nil
  for i = 1, #c do
    local w = c[i]
    if w.door == nil and math.abs(x - w.x) < w.hx and math.abs(z - w.z) < w.hz then
      local top = w.y + w.hy
      if top <= y and (best == nil or top > best) then best = top end
    end
  end
  return best
end

-- the wall box that holds a point (nil if none)
function wallBoxAt(x, y, z)
  local c = grid[math.floor(x / CELL) * 100003 + math.floor(z / CELL)]
  if c == nil then return nil end
  for i = 1, #c do
    local w = c[i]
    if math.abs(x - w.x) < w.hx and math.abs(y - w.y) < w.hy and math.abs(z - w.z) < w.hz then
      if w.door == nil or not w.door.isOpen then return w end
    end
  end
  return nil
end

-- where a bullet (unit direction dx, dy, dz) went into the wall at (px, py, pz), which way that face looks, and the wall colour
function wallEntry(px, py, pz, dx, dy, dz)
  local w = wallBoxAt(px, py, pz)
  if w == nil then return nil end
  local x0, y0, z0 = px - dx * 0.4, py - dy * 0.4, pz - dz * 0.4
  local ex = math.abs(x0 - w.x) - w.hx
  local ey = math.abs(y0 - w.y) - w.hy
  local ez = math.abs(z0 - w.z) - w.hz
  local nx, ny, nz = 0, 0, 0
  local t
  if ex >= ey and ex >= ez then
    if math.abs(dx) < 0.0001 then return nil end
    if x0 > w.x then nx = 1 else nx = -1 end
    t = (w.x + nx * w.hx - x0) / dx
  elseif ey >= ez then
    if math.abs(dy) < 0.0001 then return nil end
    if y0 > w.y then ny = 1 else ny = -1 end
    t = (w.y + ny * w.hy - y0) / dy
  else
    if math.abs(dz) < 0.0001 then return nil end
    if z0 > w.z then nz = 1 else nz = -1 end
    t = (w.z + nz * w.hz - z0) / dz
  end
  if t < -0.05 or t > 0.5 then return nil end
  return x0 + dx * t, y0 + dy * t, z0 + dz * t, nx, ny, nz, w.r, w.g, w.b
end

-- tree trunks are put into a grid once, so a bullet only tests the few trunks near it
TGRID = {}
function buildTreeGrid()
  TGRID = {}
  for i = 1, #TREES do
    local t = TREES[i]
    local seen = {}
    for _, ox in ipairs({ -t[4], t[4] }) do
      for _, oz in ipairs({ -t[4], t[4] }) do
        local key = (math.floor((t[1] + ox) / 8) + 512) * 1024 + math.floor((t[2] + oz) / 8) + 512
        if not seen[key] then
          seen[key] = true
          local l = TGRID[key]
          if l == nil then
            l = {}
            TGRID[key] = l
          end
          l[#l + 1] = t
        end
      end
    end
  end
end

function treeAt(x, y, z)
  local l = TGRID[(math.floor(x / 8) + 512) * 1024 + math.floor(z / 8) + 512]
  if l == nil then return false end
  for i = 1, #l do
    local t = l[i]
    local dx, dz = x - t[1], z - t[2]
    if dx * dx + dz * dz < t[4] * t[4] and y > t[3] and y < t[3] + t[5] then return true end
  end
  return false
end

-- is the point inside a vehicle (a box turned by the vehicle's heading)?
function carAt(x, y, z)
  for i = 1, #nearCars do
    local c = nearCars[i]
    local dy = y - c.y
    if dy > -0.5 and dy < 1.3 then
      local dx, dz = x - c.x, z - c.z
      if math.abs(dx) < 4 and math.abs(dz) < 4 then
        local a = math.rad(c.ry or 0)
        local cs, sn = math.cos(a), math.sin(a)
        local lx = dx * cs - dz * sn
        local lz = dx * sn + dz * cs
        if math.abs(lx) < math.max(0.45, c.sx * 0.5) and math.abs(lz) < math.max(0.6, c.sz * 0.5) then return true end
      end
    end
  end
  return false
end

-- moves a bullet in small steps so it cannot jump through a thin wall; true if it hit a wall, a tree trunk or a vehicle (hitKind says which)
function moveBullet(b, dt)
  local dx, dy, dz = b.vx * dt, b.vy * dt, b.vz * dt
  local n = math.ceil(math.sqrt(dx * dx + dy * dy + dz * dz) / 0.2)
  if n < 1 then n = 1 end
  if n > 60 then n = 60 end
  local sx, sy, sz = dx / n, dy / n, dz / n
  local x, y, z = b.x, b.y, b.z
  for s = 1, n do
    x = x + sx
    y = y + sy
    z = z + sz
    local k = 0
    if pointInWall(x, y, z) then
      k = 1
    elseif treeAt(x, y, z) then
      k = 4
    elseif #nearCars > 0 and carAt(x, y, z) then
      k = 5
    end
    if k > 0 then
      hitKind = k
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

-- damage by body part: h = height of the hit above the middle of the body, lat = how far from the body's centre line
function zoneMul(h, lat)
  if h > 0.55 then return 2.5, "head" end
  if h < -0.05 then return 0.6, "leg" end
  if lat > 0.25 then return 0.75, "arm" end
  if h > 0.25 then return 1.0, "chest" end
  return 0.9, "stomach"
end

-- walks along the camera's line of sight to the first thing it meets
function aimPoint(fx, fy, fz)
  local cx, cy, cz = camera.x, camera.y, camera.z
  -- start the walk where the line of sight passes the player: what lies between the camera and the player
  -- (the ground behind you, a wall, a tree) must not catch it
  local d = (self.x - cx) * fx + (self.y - cy) * fy + (self.z - cz) * fz + 0.5
  if d < 1 then d = 1 end
  while d < 500 do
    local x, y, z = cx + fx * d, cy + fy * d, cz + fz * d
    if pointInWall(x, y, z) or treeAt(x, y, z) then return x, y, z end
    local g = groundHeight(x, z)
    if g ~= nil and y < g then return x, y, z end
    for k = 1, NBOTS do
      local e = bots[k]
      if e ~= nil and not e.dead and e.hp > 0 then
        local dx, dz = x - e.x, z - e.z
        if dx * dx + dz * dz < 0.5 and math.abs(y - e.y) < 1 then return x, y, z end
      end
    end
    if d < 60 then d = d + 1 elseif d < 150 then d = d + 1.5 else d = d + 3 end
  end
  return cx + fx * 500, cy + fy * 500, cz + fz * 500
end

-- where the crosshair points, with a gentle snap onto an enemy that is almost in line
function aimDir(assist)
  local yaw = math.rad(input.yaw)
  local p = math.rad(camera.pitch)
  local fx = -math.cos(p) * math.sin(yaw)
  local fy = -math.sin(p)
  local fz = -math.cos(p) * math.cos(yaw)
  -- the real direction the camera looks (from the screen's own view matrix), so the shot goes exactly where the crosshair is
  local cl = 0
  if camera.fx ~= nil then cl = camera.fx * camera.fx + camera.fy * camera.fy + camera.fz * camera.fz end
  if cl > 0.9 then
    fx, fy, fz = camera.fx / math.sqrt(cl), camera.fy / math.sqrt(cl), camera.fz / math.sqrt(cl)
  end
  -- the bullet starts ON the camera's line of sight (just in front of the player) and flies along it,
  -- so it always goes exactly where the crosshair points, at any distance and any angle
  local ox, oy, oz = self.x + fx * 1.3, self.y + 0.5 + fy * 1.3, self.z + fz * 1.3
  if camera.x ~= nil then
    local cx, cy, cz = camera.x, camera.y, camera.z
    local d = (self.x - cx) * fx + (self.y + 0.5 - cy) * fy + (self.z - cz) * fz + 1.2
    if d < 0.8 then d = 0.8 end
    local px, py, pz = cx + fx * d, cy + fy * d, cz + fz * d
    -- not through a wall: only use that start when nothing solid lies between the player and it
    local gp = groundHeight(px, pz)
    if lineClear(self.x, self.y + 0.5, self.z, px, py, pz) and not pointInWall(px, py, pz) and (gp == nil or py > gp + 0.1) then ox, oy, oz = px, py, pz end
  end
  local best = nil
  local bd = 0.993
  local range = 45
  if carObj ~= nil then
    bd = 0.975      -- from a vehicle: a wider snap, farther away
    range = 80
  end
  if assist == false then bd = 2 end
  for k = 1, NBOTS do
    local e = bots[k]
    if e ~= nil and not e.dead and e.hp > 0 then
      local dx, dy, dz = e.x - self.x, e.y - self.y, e.z - self.z
      local d = math.sqrt(dx * dx + dy * dy + dz * dz)
      if d > 0.5 and d < range then
        local dot = (dx * fx + dy * fy + dz * fz) / d
        if dot > bd and (carObj == nil or lineClear(ox, oy, oz, e.x, e.y + 0.25, e.z)) then
          bd = dot
          best = e
        end
      end
    end
  end
  if best ~= nil then
    -- the snap points at the upper body (chest), not at the belly
    local dx, dy, dz = best.x - ox, best.y + 0.25 - oy, best.z - oz
    local d = math.sqrt(dx * dx + dy * dy + dz * dz) + 0.001
    return dx / d, dy / d, dz / d, ox, oy, oz
  end
  return fx, fy, fz, ox, oy, oz
end

-- a flash at the muzzle for a moment
function muzzleFlash(x, y, z)
  local o = fxSpawn(x, y, z, 0.3, 0.3, 0.42, 1, 0.8, 0.3, 1)
  if o ~= nil then
    o.kind = "flash"
    o.life = 0.05
    o.emit = 1
    fx[#fx + 1] = o
  end
end

-- a small piece of the surface flying off (a tumbling cube); (nx, ny, nz) points away from the surface
function chunk(x, y, z, r, g, b, size, sp, up, life, nx, ny, nz)
  local k = size * (0.6 + math.random() * 0.8)
  local o = fxSpawn(x, y, z, k, k * (0.6 + math.random() * 0.8), k * (0.6 + math.random() * 0.8), r, g, b, 1, "cube")
  if o ~= nil then
    local a = math.random() * 6.28
    local s = sp * (0.4 + math.random() * 0.8)
    o.kind = "chunk"
    o.vx = math.cos(a) * s * 0.6 + (nx or 0) * sp * 0.9
    o.vz = math.sin(a) * s * 0.6 + (nz or 0) * sp * 0.9
    o.vy = up * (0.4 + math.random() * 0.9) + (ny or 0) * sp * 0.9
    o.avx = (math.random() - 0.5) * 800
    o.avy = (math.random() - 0.5) * 800
    o.avz = (math.random() - 0.5) * 800
    o.life = life * (0.7 + math.random() * 0.6)
    o.emit = 0
    fx[#fx + 1] = o
  end
end

-- a hot spark: a thin bright streak that points along its flight
function streak(x, y, z, sp, life, nx, ny, nz, len)
  local o = fxSpawn(x, y, z, 0.03, 0.03, len or 0.3, 1, 0.7 + math.random() * 0.3, 0.25, 1, "cube")
  if o ~= nil then
    o.kind = "streak"
    o.vx = (nx or 0) * sp * 0.8 + (math.random() - 0.5) * sp
    o.vy = (ny or 0) * sp * 0.8 + (math.random() - 0.3) * sp * 0.7 + 1
    o.vz = (nz or 0) * sp * 0.8 + (math.random() - 0.5) * sp
    o.life = life * (0.7 + math.random() * 0.6)
    o.emit = 1
    fx[#fx + 1] = o
  end
end

-- a soft cloud (dust, smoke) that grows and fades
function puff(x, y, z, r, g, b, size, grow, life, op, vy, spread)
  local o = fxSpawn(x, y, z, size, size, size, r, g, b, op)
  if o ~= nil then
    o.kind = "puff"
    o.grow = grow
    o.dur = life
    o.life = life
    o.op = op
    o.vx = (math.random() - 0.5) * (spread or 0.8)
    o.vy = vy
    o.vz = (math.random() - 0.5) * (spread or 0.8)
    o.emit = 0
    fx[#fx + 1] = o
  end
end

-- a mark that stays on the surface (a bullet hole, a scorch): a very thin flat cube turned to the surface
function decal(x, y, z, nx, ny, nz, size, r, g, b, op, life)
  local ok, o = pcall(spawn, "cube", x, y, z)
  if not ok then return end
  local t = 0.012
  if math.abs(nx) > 0.5 then
    o.sx = t
    o.sy = size
    o.sz = size
  elseif math.abs(nz) > 0.5 then
    o.sx = size
    o.sy = size
    o.sz = t
  else
    o.sx = size
    o.sy = t
    o.sz = size
    o.ry = math.random() * 90
  end
  o.r = r
  o.g = g
  o.b = b
  o.opacity = op
  o.shadow = false
  o.collider = 0
  o.emit = 0
  o.life = life
  o.op = op
  decals[#decals + 1] = o
  if #decals > 70 then
    destroy(decals[1])
    table.remove(decals, 1)
  end
end

function updateDecals(dt)
  decalT = decalT + dt
  if decalT < 0.5 then return end
  local step = decalT
  decalT = 0
  for i = #decals, 1, -1 do
    local o = decals[i]
    o.life = o.life - step
    if o.life <= 0 then
      destroy(o)
      table.remove(decals, i)
    elseif o.life < 2 then
      o.opacity = o.op * o.life / 2
    end
  end
end

-- what a bullet does where it lands: 1 = wall / block, 2 = ground, 4 = tree, 5 = vehicle
-- (nx, ny, nz) = which way the surface looks; (wr, wg, wb) = the colour of the thing that was hit
function impactFx(x, y, z, kind, nx, ny, nz, wr, wg, wb)
  if kind == 2 then
    for i = 1, 6 do chunk(x, y + 0.05, z, 0.36 + math.random() * 0.1, 0.27, 0.17, 0.07, 2.5, 4, 0.8, 0, 1, 0) end
    puff(x, y + 0.1, z, 0.6, 0.5, 0.36, 0.35, 2.4, 0.9, 0.4, 0.9)
    puff(x, y + 0.1, z, 0.55, 0.46, 0.33, 0.25, 1.8, 0.7, 0.35, 0.6)
    decal(x, y + 0.03, z, 0, 1, 0, 0.32, 0.2, 0.15, 0.1, 0.75, 12)
  elseif kind == 4 then
    for i = 1, 5 do chunk(x, y, z, 0.62, 0.45, 0.25, 0.05, 3, 2.5, 0.7, nx, ny, nz) end
    for i = 1, 3 do chunk(x, y, z, 0.3, 0.55, 0.2, 0.06, 2, 1.5, 0.9, nx, ny, nz) end
    puff(x, y, z, 0.5, 0.4, 0.25, 0.25, 1.2, 0.5, 0.35, 0.4)
  elseif kind == 5 then
    local f = fxSpawn(x, y, z, 0.35, 0.35, 0.35, 1, 0.85, 0.4, 1)
    if f ~= nil then
      f.kind = "flash"
      f.life = 0.06
      f.emit = 1
      fx[#fx + 1] = f
    end
    for i = 1, 8 do streak(x, y, z, 6, 0.4, nx, ny, nz, 0.35) end
    puff(x, y, z, 0.3, 0.3, 0.32, 0.2, 1, 0.5, 0.35, 0.5)
  else
    -- a wall or block: dust and chips in the colour of the wall, hot sparks, a hole in the surface
    local r, g, b = wr or 0.7, wg or 0.7, wb or 0.7
    if nx == nil then nx, ny, nz = 0, 0, 0 end
    for i = 1, 5 do streak(x, y, z, 5, 0.35, nx, ny, nz, 0.28) end
    for i = 1, 4 do chunk(x, y, z, r * 0.9, g * 0.9, b * 0.9, 0.06, 3, 2.5, 0.7, nx, ny, nz) end
    puff(x + nx * 0.1, y + ny * 0.1, z + nz * 0.1, r * 0.8 + 0.15, g * 0.8 + 0.15, b * 0.8 + 0.15, 0.3, 1.8, 0.7, 0.4, 0.5)
    puff(x + nx * 0.1, y + ny * 0.1, z + nz * 0.1, r * 0.8 + 0.1, g * 0.8 + 0.1, b * 0.8 + 0.1, 0.2, 1.2, 0.5, 0.3, 0.3)
    if nx ~= 0 or ny ~= 0 or nz ~= 0 then
      decal(x + nx * 0.015, y + ny * 0.015, z + nz * 0.015, nx, ny, nz, 0.26, r * 0.55, g * 0.55, b * 0.55, 0.55, 14)
      decal(x + nx * 0.02, y + ny * 0.02, z + nz * 0.02, nx, ny, nz, 0.09, 0.04, 0.04, 0.04, 0.95, 14)
    end
  end
end

function shoot()
  mag = mag - 1
  storeAmmo()
  fireT = W.rate
  aimT = 0.7
  kick = 1
  playSound("shot", W.vol, W.pitch * (0.97 + math.random() * 0.06))
  local fx0, fy0, fz0, ox, oy, oz = aimDir(W.assist)
  muzzleFlash(ox, oy, oz)
  local sp = W.spread + bloom
  if legAmp > 1 then sp = sp * 1.6 end
  if aimLevel > 0 then sp = sp * 0.4 end
  if carObj ~= nil then sp = sp * 0.5 end   -- steadier from a seat
  for p = 1, W.pellets do
    local dx = fx0 + (math.random() - 0.5) * 2 * sp
    local dy = fy0 + (math.random() - 0.5) * 2 * sp
    local dz = fz0 + (math.random() - 0.5) * 2 * sp
    local l = math.sqrt(dx * dx + dy * dy + dz * dz)
    dx, dy, dz = dx / l, dy / l, dz / l
    local ok, b = pcall(spawn, "cube", ox, oy, oz)
    if ok then
      -- a thin bright streak along the flight direction (a tracer), not a ball
      b.sx = 0.035
      b.sy = 0.035
      b.sz = 2.2
      b.ry = math.deg(math.atan2(dx, dz))
      b.rx = -math.deg(math.asin(math.max(-1, math.min(1, dy))))
      b.r = 1
      b.g = 0.85
      b.b = 0.3
      b.emit = 1
      b.shadow = false
      b.vx = dx * W.speed
      b.vy = dy * W.speed
      b.vz = dz * W.speed
      b.life = W.life
      b.dmg = W.dmg
      b.o0x = ox
      b.o0y = oy
      b.o0z = oz
      b.near = W.near
      b.far = W.far
      b.minD = W.minD
      bullets[#bullets + 1] = b
    end
  end
  bloom = math.min(0.045, bloom + W.spread * 0.35 + 0.002)
  -- recoil: the view jumps up and wobbles a little
  local rk = 1
  if aimLevel > 0 then rk = 0.6 end
  local kickUp = W.recoil * rk * (0.7 + math.random() * 0.6)
  camera.pitch = camera.pitch - kickUp
  recoilAcc = math.min(25, recoilAcc + kickUp)
  sinceShot = 0
  lastPitch = camera.pitch
  camera.yaw = camera.yaw + (math.random() - 0.5) * W.recoil * rk * 0.7
end

-- where the line from (ox, oy, oz) to (ex, ey, ez) first goes under the ground (or under the water)
function crossAt(ox, oy, oz, ex, ey, ez, water)
  local t0, t1 = 0, 1
  for k = 1, 10 do
    local tm = (t0 + t1) / 2
    local mx, my, mz = ox + (ex - ox) * tm, oy + (ey - oy) * tm, oz + (ez - oz) * tm
    local g
    if water then g = waterHeight(mx, mz) else g = groundHeight(mx, mz) end
    if g ~= nil and my < g then t1 = tm else t0 = tm end
  end
  return ox + (ex - ox) * t1, oy + (ey - oy) * t1, oz + (ez - oz) * t1
end

function updateBullets(dt)
  for i = #bullets, 1, -1 do
    local b = bullets[i]
    local ox, oy, oz = b.x, b.y, b.z
    local hit = moveBullet(b, dt)
    b.life = b.life - dt
    local gh = groundHeight(b.x, b.z)
    local low = b.y < gh - 0.1
    local wy = waterHeight(b.x, b.z)
    local wet = wy ~= nil and b.y < wy
    local tx, ty, tz = b.x - (b.o0x or b.x), b.y - (b.o0y or b.y), b.z - (b.o0z or b.z)
    local dist = math.sqrt(tx * tx + ty * ty + tz * tz)
    local gone = b.life <= 0 or hit or low or wet or (b.far ~= nil and dist > b.far)
    local botHit = false
    -- enemies are tested on the path of this frame FIRST: a bullet that hits a wall, the ground or the end of its range
    -- in the same frame must still hit the enemy it passed on the way
    do
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
            local dmg = b.dmg or 25
            local hy = (oy + (b.y - oy) * t) - e.y
            local mul, zone = zoneMul(hy, math.sqrt(dx * dx + dz * dz))
            local head = zone == "head"
            dmg = dmg * mul * rangeMul(dist, b.near, b.far, b.minD)
            e.hp = e.hp - dmg
            camera.hit = (head or e.hp <= 0) and 2 or 1
            playSound("hit", 0.7, head and 1.4 or 1)
            botHit = true
            local ds = ""
            if dist > 40 then ds = "  (" .. math.floor(dist) .. " m)" end
            if e.hp <= 0 then
              if head then log("Headshot! Enemy down!  " .. countAlive() .. " left" .. ds) else log("Enemy down!  " .. countAlive() .. " left" .. ds) end
            elseif head then
              log("Headshot!" .. ds)
            end
            break
          end
        end
      end
    end
    if botHit then gone = true end
    if botHit then
      -- it hit an enemy: no mark on whatever lies behind
    elseif hit then
      local bl = math.sqrt(b.vx * b.vx + b.vy * b.vy + b.vz * b.vz) + 0.001
      local ux, uy, uz = b.vx / bl, b.vy / bl, b.vz / bl
      local done = false
      if hitKind == 1 then
        -- a wall: the exact spot on its face, the way the face looks, and its colour
        local hx, hy, hz, nx, ny, nz, wr, wg, wb = wallEntry(b.x, b.y, b.z, ux, uy, uz)
        if hx ~= nil then
          impactFx(hx, hy, hz, 1, nx, ny, nz, wr, wg, wb)
          done = true
        end
      end
      if not done then
        impactFx(b.x - ux * 0.3, b.y - uy * 0.3, b.z - uz * 0.3, hitKind, -ux, -uy, -uz)
      end
    elseif wet then
      -- the bullet flies several metres per frame: find the spot where it really crossed the water surface
      local sx0, sy0, sz0 = crossAt(ox, oy, oz, b.x, b.y, b.z, true)
      splash(sx0, waterHeight(sx0, sz0) or wy, sz0, 4)
    elseif low then
      -- ... and the spot where it really touched the ground (not where the step ended, which is further on)
      local gx, gy, gz = crossAt(ox, oy, oz, b.x, b.y, b.z, false)
      impactFx(gx, groundHeight(gx, gz), gz, 2, 0, 1, 0)
    end
    if gone then
      destroy(b)
      table.remove(bullets, i)
    end
  end
end

-- ===== tank cannon =====
-- the direction the camera really looks
function camDir()
  local yaw = math.rad(input.yaw)
  local p = math.rad(camera.pitch)
  local fx = -math.cos(p) * math.sin(yaw)
  local fy = -math.sin(p)
  local fz = -math.cos(p) * math.cos(yaw)
  local cl = 0
  if camera.fx ~= nil then cl = camera.fx * camera.fx + camera.fy * camera.fy + camera.fz * camera.fz end
  if cl > 0.9 then
    local l = math.sqrt(cl)
    fx, fy, fz = camera.fx / l, camera.fy / l, camera.fz / l
  end
  return fx, fy, fz
end

function tankFire(dt)
  tankT = tankT - dt
  local c = carObj
  if c == nil then return end
  if input.buttonsPressed["Fire"] or input.buttons["Fire"] then
    if tankT > 0 then
      if input.buttonsPressed["Fire"] then log("The cannon is loading... " .. math.ceil(tankT) .. " s") end
      return
    end
    if c.mx == nil or c.aimx == nil then return end
    local ok, s = pcall(spawn, "sphere", c.mx, c.my, c.mz)
    if not ok then
      log("Too many objects - cannot fire")
      return
    end
    tankT = 2.5
    playSound("boom", 0.9, 1.4)
    muzzleFlash(c.mx, c.my, c.mz)
    s.sx = 0.28
    s.sy = 0.28
    s.sz = 0.5
    s.r = 1
    s.g = 0.75
    s.b = 0.25
    s.emit = 1
    s.shadow = false
    s.ry = math.deg(math.atan2(c.aimx, c.aimz))
    s.rx = -math.deg(math.asin(math.max(-1, math.min(1, c.aimy))))
    s.vx = c.aimx * 100
    s.vy = c.aimy * 100
    s.vz = c.aimz * 100
    s.life = 4
    shells[#shells + 1] = s
    c.recoil = 1
    camera.pitch = camera.pitch - 1.5
  end
end

function updateShells(dt)
  for i = #shells, 1, -1 do
    local s = shells[i]
    s.life = s.life - dt
    s.vy = s.vy - 1.5 * dt
    local dx, dy, dz = s.vx * dt, s.vy * dt, s.vz * dt
    local n = math.ceil(math.sqrt(dx * dx + dy * dy + dz * dz) / 0.5)
    if n < 1 then n = 1 end
    if n > 40 then n = 40 end
    local sx, sy, sz = dx / n, dy / n, dz / n
    local x, y, z = s.x, s.y, s.z
    local boom = false
    for k = 1, n do
      x, y, z = x + sx, y + sy, z + sz
      local g = groundHeight(x, z)
      local wy = waterHeight(x, z)
      if pointInWall(x, y, z) or treeAt(x, y, z) or (g ~= nil and y < g) or (wy ~= nil and y < wy) or (#nearCars > 0 and carAt(x, y, z)) then
        boom = true
        break
      end
      for b = 1, NBOTS do
        local e = bots[b]
        if e ~= nil and not e.dead and e.hp > 0 then
          local ex, ez = x - e.x, z - e.z
          if ex * ex + ez * ez < 1.5 and math.abs(y - e.y) < 1.3 then boom = true end
        end
      end
      if boom then break end
    end
    s.x, s.y, s.z = x, y, z
    s.ry = math.deg(math.atan2(s.vx, s.vz))
    if boom or s.life <= 0 then
      explode(x, y + 0.2, z, 8, 220, 60)
      destroy(s)
      table.remove(shells, i)
    end
  end
end

-- ===== grenades =====
function throwGrenade()
  if grenades <= 0 then
    log("No grenades")
    return
  end
  if swimming then return end
  grenades = grenades - 1
  playSound("pin", 0.8)
  aimT = 0.5
  local fx0, fy0, fz0 = aimDir(false)
  local ok, g = pcall(spawn, "sphere", self.x + fx0 * 0.9, self.y + 0.7, self.z + fz0 * 0.9)
  if not ok then
    grenades = grenades + 1
    log("Too many objects - cannot throw")
    return
  end
  g.sx = 0.24
  g.sy = 0.24
  g.sz = 0.24
  g.r = 0.22
  g.g = 0.32
  g.b = 0.14
  g.shadow = false
  g.vx = fx0 * 17
  g.vy = fy0 * 17 + 5
  g.vz = fz0 * 17
  g.fuse = 3
  grenList[#grenList + 1] = g
  log("Grenade thrown (" .. grenades .. " left) - it explodes in 3 seconds")
end

function updateGrenades(dt)
  for i = #grenList, 1, -1 do
    local g = grenList[i]
    g.fuse = g.fuse - dt
    g.vy = g.vy - 15 * dt
    local nx, ny, nz = g.x + g.vx * dt, g.y + g.vy * dt, g.z + g.vz * dt
    if pointInWall(nx, ny, nz) then
      -- bounce off a wall or a ceiling / floor
      if pointInWall(nx, g.y, g.z) then g.vx = -g.vx * 0.4; nx = g.x end
      if pointInWall(g.x, g.y, nz) then g.vz = -g.vz * 0.4; nz = g.z end
      if pointInWall(g.x, ny, g.z) then g.vy = -g.vy * 0.3; ny = g.y end
    end
    local gh = groundHeight(nx, nz)
    if ny < gh + 0.13 then
      ny = gh + 0.13
      if g.vy < 0 then g.vy = -g.vy * 0.35 end
      g.vx = g.vx * 0.7
      g.vz = g.vz * 0.7
    end
    g.x, g.y, g.z = nx, ny, nz
    if g.fuse <= 0 then
      explode(g.x, g.y, g.z)
      destroy(g)
      table.remove(grenList, i)
    end
  end
end

function blastFx(x, y, z)
  local gh = groundHeight(x, z) or y
  local by = math.max(y, gh) + 0.4
  -- 1. a short, very bright flash
  local f = fxSpawn(x, by + 0.4, z, 3, 3, 3, 1, 0.92, 0.55, 1)
  if f ~= nil then
    f.kind = "blast"
    f.life = 0.12
    f.dur = 0.12
    f.big = 9
    f.age = 0
    f.emit = 1
    fx[#fx + 1] = f
  end
  -- 2. the fireball: bright yellow and orange, wide and low, a burst that lies on the ground
  local cols = { { 1, 0.9, 0.35 }, { 1, 0.72, 0.18 }, { 1, 0.5, 0.1 }, { 0.95, 0.38, 0.07 } }
  for i = 1, 4 do
    local c = cols[i]
    local o = fxSpawn(x + (math.random() - 0.5) * 1.8, by + 0.2 + i * 0.12, z + (math.random() - 0.5) * 1.8, 1.4, 1.4, 1.4, c[1], c[2], c[3], 0.95)
    if o ~= nil then
      o.kind = "fire"
      o.life = 0.45 + i * 0.12
      o.dur = o.life
      o.age = 0
      o.s0 = 1.4
      o.big = 4.2 + i * 0.9
      o.flat = 0.62
      o.vy = 0.8 + i * 0.35
      o.g0 = c[2]
      o.b0 = c[3]
      o.emit = 1
      fx[#fx + 1] = o
    end
  end
  -- 3. the dust ring racing over the ground (earth coloured)
  local ring = fxSpawn(x, gh + 0.3, z, 2, 0.35, 2, 0.62, 0.52, 0.38, 0.6)
  if ring ~= nil then
    ring.kind = "ring"
    ring.life = 0.8
    ring.dur = 0.8
    ring.age = 0
    ring.s0 = 2
    ring.big = 28
    ring.op = 0.6
    ring.emit = 0
    fx[#fx + 1] = ring
  end
  -- 4. the brown cloud of earth and dust that rises and stays a while (no black smoke)
  for i = 1, 16 do
    local t = math.random()
    puff(x + (math.random() - 0.5) * 2.4, by + math.random() * 1.0, z + (math.random() - 0.5) * 2.4,
      0.5 + 0.14 * t, 0.4 + 0.12 * t, 0.28 + 0.1 * t, 1.6, 2.4 + math.random() * 2.2, 2.0 + math.random() * 1.6, 0.6, 0.8 + math.random() * 1.6, 5)
  end
  for i = 1, 6 do
    puff(x + (math.random() - 0.5) * 3, by + 0.5 + math.random() * 1.5, z + (math.random() - 0.5) * 3,
      0.72, 0.66, 0.54, 2.4, 2 + math.random() * 1.5, 3 + math.random() * 1.5, 0.3, 0.6 + math.random(), 3)
  end
  for i = 1, 4 do
    puff(x + (math.random() - 0.5) * 1.6, by + math.random() * 0.8, z + (math.random() - 0.5) * 1.6,
      0.22, 0.17, 0.12, 1.2, 1.8, 1.6 + math.random(), 0.55, 1 + math.random(), 3)
  end
  -- 5. earth and rubble thrown out, and hot sparks
  for i = 1, 14 do
    local a = math.random() * 6.28
    local sp = 4 + math.random() * 9
    local nx, nz = math.cos(a) * sp * 0.15, math.sin(a) * sp * 0.15
    chunk(x, by, z, 0.25 + math.random() * 0.15, 0.2 + math.random() * 0.1, 0.15, 0.2, sp, 5 + math.random() * 7, 1.6, nx / 4, 0, nz / 4)
  end
  for i = 1, 22 do
    local a = math.random() * 6.28
    streak(x, by, z, 8 + math.random() * 10, 0.7, math.cos(a), 0.6, math.sin(a), 0.4)
  end
  -- 6. a black scorch mark on the ground
  if math.abs(y - gh) < 3 then
    decal(x, gh + 0.04, z, 0, 1, 0, 5.5, 0.1, 0.09, 0.08, 0.35, 40)
    decal(x, gh + 0.05, z, 0, 1, 0, 3, 0.04, 0.035, 0.03, 0.8, 40)
  end
  -- 7. the view shakes when you are close
  local sdx, sdz = self.x - x, self.z - z
  local sd = math.sqrt(sdx * sdx + sdz * sdz)
  if sd < 30 then
    local k = 1 - sd / 30
    camera.pitch = camera.pitch - k * 7
    camera.yaw = camera.yaw + (math.random() - 0.5) * k * 8
  end
end

function explode(x, y, z, R, dmgB, dmgP)
  R = R or 10
  dmgB = dmgB or 200
  dmgP = dmgP or 100
  playSound("boom", 1, 0.9 + math.random() * 0.2)
  for k = 1, NBOTS do
    local e = bots[k]
    if e ~= nil and not e.dead and e.hp > 0 then
      local dx, dy, dz = e.x - x, e.y - y, e.z - z
      local d = math.sqrt(dx * dx + dy * dy + dz * dz)
      if d < R then
        -- full damage in the middle (about 2.5 m), then less and less out to the edge
        local fall = 1
        if d > 2.5 then fall = 1 - (d - 2.5) / (R - 2.5) end
        local dmg = dmgB * fall
        -- cover: a wall between the blast and the body (feet-high or head-high line) takes most of it
        if not (lineClear(x, y + 0.5, z, e.x, e.y, e.z) or lineClear(x, y + 0.5, z, e.x, e.y + 0.6, e.z)) then dmg = dmg * 0.25 end
        e.hp = e.hp - dmg
        camera.hit = (e.hp <= 0) and 2 or 1
        if e.hp <= 0 then
          log("Enemy blown up!  " .. countAlive() .. " left")
        else
          log("Grenade hit an enemy  -" .. math.floor(dmg))
        end
      end
    end
  end
  local dx, dy, dz = self.x - x, self.y - y, self.z - z
  local d = math.sqrt(dx * dx + dy * dy + dz * dz)
  if d < R and not self.dead then
    local fall = 1
    if d > 2.5 then fall = 1 - (d - 2.5) / (R - 2.5) end
    local dmg = dmgP * fall
    if not lineClear(x, y + 0.5, z, self.x, self.y, self.z) then dmg = dmg * 0.25 end
    if carObj ~= nil and carObj.kind == "tank" then dmg = dmg * 0.3 end   -- the armour
    self.hp = self.hp - dmg
    log("The blast hit you!  HP " .. math.max(0, math.floor(self.hp)))
  end
  blastFx(x, y, z)
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
function fxSpawn(x, y, z, sx, sy, sz, r, g, b, op, shape)
  if #fx >= 220 then return nil end
  local ok, o = pcall(spawn, shape or "sphere", x, y, z)
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
    elseif o.kind == "flash" then
      -- the muzzle flash just stays for its short life
    elseif o.kind == "spark" then
      o.vy = o.vy - 14 * dt
      o.x = o.x + o.vx * dt
      o.y = o.y + o.vy * dt
      o.z = o.z + o.vz * dt
      o.opacity = math.min(1, o.life / 0.3)
    elseif o.kind == "chunk" then
      if not o.rest then
        o.vy = o.vy - 14 * dt
        o.x = o.x + o.vx * dt
        o.y = o.y + o.vy * dt
        o.z = o.z + o.vz * dt
        o.rx = o.rx + o.avx * dt
        o.ry = o.ry + o.avy * dt
        o.rz = o.rz + o.avz * dt
        local gh = groundHeight(o.x, o.z)
        if gh ~= nil and o.y < gh + 0.04 and o.vy < 0 then
          o.y = gh + 0.04
          o.rest = true
        end
      end
      o.opacity = math.min(1, o.life / 0.3)
    elseif o.kind == "streak" then
      o.vy = o.vy - 14 * dt
      o.x = o.x + o.vx * dt
      o.y = o.y + o.vy * dt
      o.z = o.z + o.vz * dt
      local sp = math.sqrt(o.vx * o.vx + o.vy * o.vy + o.vz * o.vz) + 0.001
      o.ry = math.deg(math.atan2(o.vx, o.vz))
      o.rx = -math.deg(math.asin(math.max(-1, math.min(1, o.vy / sp))))
      o.opacity = math.min(1, o.life / 0.2)
    elseif o.kind == "fire" then
      o.age = o.age + dt
      local k = math.min(1, o.age / o.dur)
      o.y = o.y + o.vy * dt
      o.sx = o.s0 + (o.big - o.s0) * (1 - (1 - k) * (1 - k))
      o.sy = o.sx * (o.flat or 0.9)
      o.sz = o.sx
      o.r = 1 - 0.7 * k
      o.g = o.g0 * (1 - k) + 0.08 * k
      o.b = o.b0 * (1 - k) + 0.04 * k
      o.emit = 1 - k
      o.opacity = 0.95 * (1 - k * k)
    elseif o.kind == "ring" then
      o.age = o.age + dt
      local k = math.min(1, o.age / o.dur)
      o.sx = o.s0 + k * o.big
      o.sz = o.sx
      o.opacity = o.op * (1 - k)
    elseif o.kind == "puff" then
      local drag = math.max(0, 1 - 1.4 * dt)
      o.vx = o.vx * drag
      o.vz = o.vz * drag
      o.x = o.x + o.vx * dt
      o.y = o.y + o.vy * dt
      o.z = o.z + o.vz * dt
      o.sx = o.sx + o.grow * dt
      o.sy = o.sx
      o.sz = o.sx
      o.opacity = o.op * math.min(1, o.life / (o.dur * 0.7))
    elseif o.kind == "smoke" then
      o.x = o.x + o.vx * dt
      o.y = o.y + o.vy * dt
      o.z = o.z + o.vz * dt
      o.sx = o.sx + 1.6 * dt
      o.sy = o.sx
      o.sz = o.sx
      o.opacity = 0.7 * math.min(1, o.life / 1)
    elseif o.kind == "blast" then
      o.age = o.age + dt
      local k = math.min(1, o.age / (o.dur or 0.3))
      o.sx = 3 + k * (o.big or 14)
      o.sy = o.sx
      o.sz = o.sx
      o.opacity = 0.95 * (1 - k)
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
  -- vehicles close to the player (bullets test against these)
  nearCars = {}
  for i = 1, #cars do
    local c = cars[i]
    if math.abs(c.x - self.x) < 70 and math.abs(c.z - self.z) < 70 and c ~= carObj then nearCars[#nearCars + 1] = c end
  end
  updateBullets(dt)
  updateFx(dt)
  updateDecals(dt)
  updateGrenades(dt)
  updateShells(dt)
  if self.dead then
    if bagOpenState then setBag(false) end
    deadT = deadT + dt
    if camera.firstPerson then camera.firstPerson = false end
    if aimLevel > 0 then
      aimLevel = 0
      camera.fov = 50
    end
    fallPose(deadT, deadG or groundHeight(self.x, self.z))
    return
  end

  -- cars: DRIVE = driver seat, SEAT = passenger seats, JUMP = get out
  updateCarButtons()
  updateDoorButton(dt)
  updateDrops(dt)
  if input.buttonsPressed["Door"] and nearDoor ~= nil then nearDoor.toggle = (nearDoor.toggle or 0) + 1 end
  handleItems(dt)
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
      self.seat = self.seat % (carObj.nseats or 4) + 1
      log("Now in the " .. seatName(self.seat))
    end
  elseif input.jumpPressed and self.car ~= nil then
    leaveCar()
  end
  if self.car ~= nil then
    if self.hp <= 0 then leaveCar(true) else carRide(dt) return end
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
  if aimLevel > 0 and not swimming then sp = sp * 0.6 end
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
      chute = false
      chuteT = 0
    end
    if not onStairs or self.vy > 0 then
      self.vy = self.vy - world.gravity * dt
      if chute then
        chuteT = chuteT + dt
        if chuteT > 1.2 and self.vy < -7 then self.vy = -7 end   -- the parachute: a slow, safe fall
      end
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
  collectLoot()

  -- shooting (not while swimming)
  if swimming then
    fireT = fireT - dt
  else
    handleShooting(dt)
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
  local vmWant = fp and (not swimming) and (not self.dead) and not (aimLevel > 0 and W.scope == true)
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
    log("HP " .. math.floor(self.hp) .. "   " .. W.name .. " " .. mag .. "/" .. reserve .. "   Grenades " .. grenades .. "   Medkits " .. medkits .. "   Enemies " .. countAlive() .. "   Zone " .. math.floor(zone.radius or 0))
  end
end
"""

    private val BR_BOT = """-- Enemy soldier: wanders, hunts the player, strafes and shoots, runs from the storm.
local DMG = 8

function start()
  self.hp = 60
  self.dead = false
  self.vy = 0
  -- what this soldier carries: it is dropped in a loot crate when he dies
  loot = { med = 0, gren = 0, a556 = 0, a9 = 0, g12 = 0, a762 = 0, weapon = "rifle", name = "M416" }
  local wr = math.random()
  if wr < 0.4 then
    loot.weapon = "rifle"; loot.name = "M416"; loot.a556 = 30 + math.random(0, 30)
  elseif wr < 0.6 then
    loot.weapon = "smg"; loot.name = "UMP45"; loot.a9 = 25 + math.random(0, 25)
  elseif wr < 0.75 then
    loot.weapon = "shotgun"; loot.name = "S12K"; loot.g12 = 5 + math.random(0, 5)
  elseif wr < 0.9 then
    loot.weapon = "pistol"; loot.name = "P1911"; loot.a9 = 12 + math.random(0, 12)
  else
    loot.weapon = "sniper"; loot.name = "Kar98k"; loot.a762 = 5 + math.random(0, 5)
  end
  if math.random() < 0.5 then loot.med = 1 end
  if math.random() < 0.4 then loot.gren = 1 + math.random(0, 1) end
  crate = nil
  looted = false
  lootT = 0
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
  if crate ~= nil then
    destroy(crate)
    crate = nil
  end
  for k, part in pairs(P) do
    if part ~= nil then destroy(part) end
  end
  destroy(self)
end

-- the loot crate: a glowing box on the ground next to the body. Walk up to it to take everything.
function dropCrate()
  local ok, c = pcall(spawn, "cube", self.x + 1.1, deadG + 0.3, self.z)
  if not ok then return end
  c.sx = 0.9
  c.sy = 0.55
  c.sz = 0.9
  c.r = 0.62
  c.g = 0.42
  c.b = 0.12
  c.emit = 0.45
  c.shadow = false
  c.collider = 0
  crate = c
  crateX = self.x + 1.1
  crateZ = self.z
end

function updateCrate(dt)
  if crate == nil or looted then return end
  crate.y = deadG + 0.35 + math.sin(deadT * 3) * 0.06
  crate.ry = deadT * 50
  if player == nil or player.dead then return end
  local dx, dz = player.x - crateX, player.z - crateZ
  if dx * dx + dz * dz < 5 and math.abs(player.y - deadG) < 3.5 then lootCrate() end
end

function lootCrate()
  looted = true
  lootT = deadT
  player.addWeapon = loot.weapon
  if loot.a556 > 0 then player.addR556 = (player.addR556 or 0) + loot.a556 end
  if loot.a9 > 0 then player.addR9 = (player.addR9 or 0) + loot.a9 end
  if loot.g12 > 0 then player.addG12 = (player.addG12 or 0) + loot.g12 end
  if loot.a762 > 0 then player.addR762 = (player.addR762 or 0) + loot.a762 end
  if loot.gren > 0 then player.addGren = (player.addGren or 0) + loot.gren end
  if loot.med > 0 then player.addMed = (player.addMed or 0) + loot.med end
  playSound("pickup", 0.9, 0.8)
  local t = loot.name
  if loot.med > 0 then t = t .. ", medkit" end
  if loot.gren > 0 then t = t .. ", " .. loot.gren .. " grenade" end
  log("Looted the enemy crate: " .. t .. " + ammo")
  destroy(crate)
  crate = nil
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

-- damage by body part: h = height of the hit above the middle of the body, lat = how far from the body's centre line
function zoneMul(h, lat)
  if h > 0.55 then return 2.5, "head" end
  if h < -0.05 then return 0.6, "leg" end
  if lat > 0.25 then return 0.75, "arm" end
  if h > 0.25 then return 1.0, "chest" end
  return 0.9, "stomach"
end

function botShoot(dx, dz, d)
  playSound("shot", math.max(0.1, 0.55 - d / 70), 0.8)
  local ex = (math.random() - 0.5) * d * 0.12
  local ez = (math.random() - 0.5) * d * 0.12
  local ey = (math.random() - 0.5) * d * 0.06
  local ddx, ddy, ddz = dx + ex, (player.y - self.y) + ey, dz + ez
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
  b.o0x = b.x
  b.o0z = b.z
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
        local mul = zoneMul((oy + (b.y - oy) * t) - player.y, math.sqrt(dx * dx + dz * dz))
        local fx0, fz0 = b.x - (b.o0x or b.x), b.z - (b.o0z or b.z)
        local fd = math.sqrt(fx0 * fx0 + fz0 * fz0)
        local fall = 1
        if fd > 25 then fall = math.max(0.4, 1 - (fd - 25) / 100) end
        player.hp = player.hp - DMG * mul * fall
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
      dropCrate()
    end
    deadT = deadT + dt
    fallPose(deadT, deadG)
    updateCrate(dt)
    if looted and deadT > lootT + 2 then removeBody() end
    if deadT > 45 then removeBody() end
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
SEATS = { { -0.45, -0.3 }, { 0.45, -0.3 }, { -0.45, 0.7 }, { 0.45, 0.7 } }   -- driver, front passenger, rear left, rear right

WOFF = { { -0.98, -0.15, -1.35 }, { 0.98, -0.15, -1.35 }, { -0.98, -0.15, 1.35 }, { 0.98, -0.15, 1.35 } }   -- front left, front right, rear left, rear right

function start()
  speed = 0
  heading = self.ry
  self.speed = 0
  self.driven = false
  local nm = self.name
  wheels = { find(nm .. " wheel FL"), find(nm .. " wheel FR"), find(nm .. " wheel RL"), find(nm .. " wheel RR") }
  bars = { find(nm .. " steer1"), find(nm .. " steer2") }
  wheelSteer = 0
  placed = false
end

-- puts an object at a spot given in the car's own frame (x right, y up, z back) and turns it
function put(o, lx, ly, lz, nx, nz, cs, sn, cp, sp, ry2, rx2, rz2)
  if o == nil then return end
  local y2 = ly * cp - lz * sp
  local z2 = ly * sp + lz * cp
  o.x = nx + lx * cs + z2 * sn
  o.z = nz - lx * sn + z2 * cs
  o.y = self.y + y2
  o.ry = ry2
  o.rx = rx2
  o.rz = rz2
end

CP = { { 0, -2.1 }, { -0.95, -2.1 }, { 0.95, -2.1 }, { -0.95, -0.7 }, { 0.95, -0.7 }, { -0.95, 0.7 }, { 0.95, 0.7 }, { -0.95, 2.1 }, { 0.95, 2.1 }, { 0, 2.1 } }

-- solid ground vehicle: it stops at walls, closed doors and tree trunks (the player script knows where they are)
function blockedAt(pl, x, z, h)
  local r = math.rad(h)
  local cs, sn = math.cos(r), math.sin(r)
  local y0 = self.y + 0.1
  local y1 = self.y + 0.8
  for i = 1, #CP do
    local p = CP[i]
    local wx = x + p[1] * cs + p[2] * sn
    local wz = z - p[1] * sn + p[2] * cs
    if pl.wallAt(wx, y0, wz) or pl.wallAt(wx, y1, wz) or pl.treeAt(wx, y0, wz) then return true end
  end
  return false
end

-- moves from here towards (wx, wz) in small steps so a thin wall cannot be jumped; returns the place reached and whether something stopped it
function slide(pl, wx, wz, h, oldH)
  if pl == nil or pl.wallAt == nil then return wx, wz, false end
  local x0, z0 = self.x, self.z
  local dx, dz = wx - x0, wz - z0
  if dx * dx + dz * dz < 0.000001 and h == oldH then return wx, wz, false end   -- standing still: nothing to test
  local steps = math.max(1, math.min(10, math.ceil(math.sqrt(dx * dx + dz * dz) / 0.3)))
  local nx, nz = x0, z0
  for i = 1, steps do
    local tx, tz = x0 + dx * i / steps, z0 + dz * i / steps
    if blockedAt(pl, tx, tz, h) then
      -- already inside something (not just blocked now)? then let it drive out
      if i == 1 and blockedAt(pl, x0, z0, oldH) then return wx, wz, false end
      return nx, nz, true
    end
    nx, nz = tx, tz
  end
  return nx, nz, false
end

function update(dt)
  local pl = find("Player")
  local driven = pl ~= nil and pl.car == self.name and pl.seat == 1
  self.driven = driven
  crashT = (crashT or 0) - dt
  -- parked and still: nothing to do (this saves a lot of work when there are many vehicles)
  if not driven and speed == 0 and placed and wheelSteer * wheelSteer < 0.01 then return end
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
  local oldHeading = heading
  heading = heading - steer * 62 * math.min(1, math.abs(speed) / 7) * dir * dt
  local r = math.rad(heading)
  local fx = -math.sin(r)
  local fz = -math.cos(r)
  local nx, nz, hit = slide(pl, self.x + fx * speed * dt, self.z + fz * speed * dt, heading, oldHeading)
  if hit then
    if math.abs(speed) > 4 and (crashT or 0) <= 0 then
      crashT = 0.6   -- one crash sound and one hurt per 0.6 s, not one per frame
      playSound("hit", 0.7, 0.6)
      -- a hard crash hurts the people inside
      if pl ~= nil and pl.car == self.name and math.abs(speed) > 14 then pl.hp = pl.hp - (math.abs(speed) - 14) * 1.5 end
    end
    speed = -speed * 0.25
    if blockedAt(pl, nx, nz, heading) then   -- the turn itself ran into something: keep the old heading
      heading = oldHeading
      r = math.rad(heading)
    end
  end
  self.ry = heading
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
  -- whoever sits in the car is carried along with the NEW position (reading it in the player script would be one frame late)
  if pl ~= nil and pl.car == self.name then
    local st = SEATS[pl.seat or 1]
    pl.x = nx + math.cos(r) * st[1] + math.sin(r) * st[2]
    pl.z = nz - math.sin(r) * st[1] + math.cos(r) * st[2]
    pl.y = self.y + 0.43
    pl.vy = 0
  end
  if self.waterY ~= nil and self.y < self.waterY then speed = speed * (1 - 1.5 * dt) end

  -- the front wheels turn when you steer, and the steering wheel turns too
  local target = -steer * 32
  wheelSteer = wheelSteer + (target - wheelSteer) * math.min(1, dt * 8)
  if driven or math.abs(speed) > 0.01 or math.abs(wheelSteer) > 0.1 or not placed then
    placed = true
    local cs, sn = math.cos(r), math.sin(r)
    local pitch = math.rad(self.rx)
    local cp, sp = math.cos(pitch), math.sin(pitch)
    for i = 1, 4 do
      local w = WOFF[i]
      local yawW = heading
      if i <= 2 then yawW = heading + wheelSteer end
      put(wheels[i], w[1], w[2], w[3], nx, nz, cs, sn, cp, sp, yawW, self.rx, 90)
    end
    local roll = -wheelSteer * 3
    put(bars[1], -0.45, 0.62, -0.62, nx, nz, cs, sn, cp, sp, heading, self.rx - 20, roll)
    put(bars[2], -0.45, 0.62, -0.62, nx, nz, cs, sn, cp, sp, heading, self.rx - 20, roll)
  end
end

function oncollide(other)
  if math.abs(speed) > 3 then
    if self.driven and math.abs(speed) > 7 and other.hp ~= nil then other.hp = other.hp - 40 end
    speed = speed * 0.4
  end
end
"""

    private val BR_BIKE = """-- A motorcycle for two. Walk up and tap DRIVE (rider) or SEAT (passenger). Joystick: up = gas, down = brake and reverse, left / right = steer.
SEATS = { { 0, 0.25, 0.46 }, { 0, 0.7, 0.46 } }

function start()
  speed = 0
  heading = self.ry
  self.speed = 0
  self.driven = false
  self.kind = "bike"
  self.nseats = 2
  self.seatsDef = SEATS
  self.camDist = 5.5
  self.legSwing = 60
  local nm = self.name
  fw = find(nm .. " wheel F")
  fork = find(nm .. " fork")
  bar = find(nm .. " bar")
  wheelSteer = 0
  placed = false
end

-- puts an object at a spot given in the vehicle's own frame (x right, y up, z back) and turns it
function put(o, lx, ly, lz, nx, nz, cs, sn, cp, sp, ry2, rx2, rz2)
  if o == nil then return end
  local y2 = ly * cp - lz * sp
  local z2 = ly * sp + lz * cp
  o.x = nx + lx * cs + z2 * sn
  o.z = nz - lx * sn + z2 * cs
  o.y = self.y + y2
  o.ry = ry2
  o.rx = rx2
  o.rz = rz2
end

CP = { { 0, -1 }, { 0, 1 }, { -0.3, 0 }, { 0.3, 0 }, { 0, 0 } }

-- solid ground vehicle: it stops at walls, closed doors and tree trunks (the player script knows where they are)
function blockedAt(pl, x, z, h)
  local r = math.rad(h)
  local cs, sn = math.cos(r), math.sin(r)
  local y0 = self.y + 0.1
  local y1 = self.y + 0.8
  for i = 1, #CP do
    local p = CP[i]
    local wx = x + p[1] * cs + p[2] * sn
    local wz = z - p[1] * sn + p[2] * cs
    if pl.wallAt(wx, y0, wz) or pl.wallAt(wx, y1, wz) or pl.treeAt(wx, y0, wz) then return true end
  end
  return false
end

-- moves from here towards (wx, wz) in small steps so a thin wall cannot be jumped; returns the place reached and whether something stopped it
function slide(pl, wx, wz, h, oldH)
  if pl == nil or pl.wallAt == nil then return wx, wz, false end
  local x0, z0 = self.x, self.z
  local dx, dz = wx - x0, wz - z0
  if dx * dx + dz * dz < 0.000001 and h == oldH then return wx, wz, false end   -- standing still: nothing to test
  local steps = math.max(1, math.min(10, math.ceil(math.sqrt(dx * dx + dz * dz) / 0.3)))
  local nx, nz = x0, z0
  for i = 1, steps do
    local tx, tz = x0 + dx * i / steps, z0 + dz * i / steps
    if blockedAt(pl, tx, tz, h) then
      -- already inside something (not just blocked now)? then let it drive out
      if i == 1 and blockedAt(pl, x0, z0, oldH) then return wx, wz, false end
      return nx, nz, true
    end
    nx, nz = tx, tz
  end
  return nx, nz, false
end

function update(dt)
  local pl = find("Player")
  local driven = pl ~= nil and pl.car == self.name and pl.seat == 1
  self.driven = driven
  crashT = (crashT or 0) - dt
  -- parked and still: nothing to do (this saves a lot of work when there are many vehicles)
  if not driven and speed == 0 and placed and wheelSteer * wheelSteer < 0.01 then return end
  local throttle = 0
  local steer = 0
  if driven and input.joyActive then
    throttle = input.joyY
    steer = input.joyX
  end
  if throttle > 0.1 then
    local a = 20 * throttle
    if speed < 0 then a = a + 16 end
    speed = speed + a * dt
  elseif throttle < -0.1 then
    if speed > 0.5 then
      speed = speed - 28 * dt
    else
      speed = speed + 9 * throttle * dt
    end
  else
    local drag = 6 * dt
    if speed > 0 then speed = math.max(0, speed - drag) else speed = math.min(0, speed + drag) end
  end
  speed = math.max(-5, math.min(36, speed))
  local dir = 1
  if speed < 0 then dir = -1 end
  local oldHeading = heading
  heading = heading - steer * 80 * math.min(1, math.abs(speed) / 6) * dir * dt
  local r = math.rad(heading)
  local fx = -math.sin(r)
  local fz = -math.cos(r)
  local nx, nz, hit = slide(pl, self.x + fx * speed * dt, self.z + fz * speed * dt, heading, oldHeading)
  if hit then
    if math.abs(speed) > 4 and (crashT or 0) <= 0 then
      crashT = 0.6   -- one crash sound and one hurt per 0.6 s, not one per frame
      playSound("hit", 0.7, 0.6)
      -- a hard crash hurts the people inside
      if pl ~= nil and pl.car == self.name and math.abs(speed) > 14 then pl.hp = pl.hp - (math.abs(speed) - 14) * 1.5 end
    end
    speed = -speed * 0.25
    if blockedAt(pl, nx, nz, heading) then   -- the turn itself ran into something: keep the old heading
      heading = oldHeading
      r = math.rad(heading)
    end
  end
  self.ry = heading
  local gF = groundHeight(nx + fx * 0.85, nz + fz * 0.85)
  local gB = groundHeight(nx - fx * 0.85, nz - fz * 0.85)
  local g = groundHeight(nx, nz)
  if gF ~= nil and gB ~= nil and g ~= nil then
    self.rx = math.deg(math.atan2(gF - gB, 1.7))
    self.y = math.max(g, (gF + gB) / 2) + 0.62
  end
  self.x = nx
  self.z = nz
  self.speed = speed
  if self.waterY ~= nil and self.y < self.waterY then speed = speed * (1 - 1.5 * dt) end
  -- riders are carried with the NEW position (reading it in the player script would be a frame late)
  if pl ~= nil and pl.car == self.name then
    local st = SEATS[pl.seat or 1]
    pl.x = nx + math.cos(r) * st[1] + math.sin(r) * st[2]
    pl.z = nz - math.sin(r) * st[1] + math.cos(r) * st[2]
    pl.y = self.y + st[3]
    pl.vy = 0
  end
  -- the front wheel, the fork and the handlebar turn with the steering
  local target = -steer * 28
  wheelSteer = wheelSteer + (target - wheelSteer) * math.min(1, dt * 8)
  if driven or math.abs(speed) > 0.01 or math.abs(wheelSteer) > 0.1 or not placed then
    placed = true
    local cs, sn = math.cos(r), math.sin(r)
    local pitch = math.rad(self.rx)
    local cp, sp = math.cos(pitch), math.sin(pitch)
    local yw = heading + wheelSteer
    put(fw, 0, -0.27, -0.85, nx, nz, cs, sn, cp, sp, yw, self.rx, 90)
    put(fork, 0, 0.0, -0.85, nx, nz, cs, sn, cp, sp, yw, self.rx, 0)
    put(bar, 0, 0.5, -0.75, nx, nz, cs, sn, cp, sp, yw, self.rx, 0)
  end
end

function oncollide(other)
  if math.abs(speed) > 3 then
    if self.driven and math.abs(speed) > 7 and other.hp ~= nil then other.hp = other.hp - 40 end
    speed = speed * 0.4
  end
end
"""

    private val BR_HELI = """-- A helicopter. Walk up and tap DRIVE (pilot) or SEAT (passenger). The pilot: joystick up / down = forward / back, left / right = turn, CLIMB / DESCEND = height.
SEATS = { { -0.4, -0.55, 0.47 }, { 0.4, -0.55, 0.47 }, { -0.4, 0.55, 0.47 }, { 0.4, 0.55, 0.47 } }

function start()
  speed = 0
  vy = 0
  heading = self.ry
  rotor = 0
  spin = 0
  self.speed = 0
  self.driven = false
  self.agl = 0
  self.kind = "heli"
  self.nseats = 4
  self.seatsDef = SEATS
  self.camDist = 11
  self.legSwing = 80
  local nm = self.name
  r1 = find(nm .. " rotor1")
  r2 = find(nm .. " rotor2")
  tailR = find(nm .. " tailrotor")
  placed = false
  padLift = math.max(0, self.y - ((groundHeight(self.x, self.z) or 0) + 0.85))   -- it stands on a pad a little above the ground
end

function put(o, lx, ly, lz, nx, nz, cs, sn, cp, sp, ry2, rx2, rz2)
  if o == nil then return end
  local y2 = ly * cp - lz * sp
  local z2 = ly * sp + lz * cp
  o.x = nx + lx * cs + z2 * sn
  o.z = nz - lx * sn + z2 * cs
  o.y = self.y + y2
  o.ry = ry2
  o.rx = rx2
  o.rz = rz2
end

-- the helicopter stops at walls, roofs and tree trunks (the player script knows where they are)
CP = { { 0, -1.8 }, { -1, -0.5 }, { 1, -0.5 }, { -1, 0.8 }, { 1, 0.8 }, { 0, 2 }, { 0, 3.5 }, { 0, 4.6 } }
LP = { { -1, -0.5 }, { 1, -0.5 }, { -1, 0.8 }, { 1, 0.8 }, { 0, -1.8 }, { 0, 2 } }   -- where the skids stand (for roofs)

function blockedAt(pl, x, y, z, h)
  local r = math.rad(h)
  local cs, sn = math.cos(r), math.sin(r)
  for i = 1, #CP do
    local p = CP[i]
    local wx = x + p[1] * cs + p[2] * sn
    local wz = z - p[1] * sn + p[2] * cs
    if pl.wallAt(wx, y - 0.3, wz) or pl.wallAt(wx, y + 0.5, wz) or pl.treeAt(wx, y - 0.3, wz) or pl.treeAt(wx, y + 0.5, wz) then return true end
  end
  -- the rotor hub
  if pl.wallAt(x - 0.3 * sn, y + 1.85, z - 0.3 * cs) then return true end
  return false
end

-- moves from here towards (wx, wz) in small steps so a thin wall cannot be jumped; returns the place reached and whether something stopped it
function slide(pl, wx, wz, h, oldH)
  if pl == nil or pl.wallAt == nil then return wx, wz, false end
  local x0, z0 = self.x, self.z
  local dx, dz = wx - x0, wz - z0
  if dx * dx + dz * dz < 0.000001 and h == oldH then return wx, wz, false end   -- standing still: nothing to test
  local steps = math.max(1, math.min(10, math.ceil(math.sqrt(dx * dx + dz * dz) / 0.3)))
  local nx, nz = x0, z0
  for i = 1, steps do
    local tx, tz = x0 + dx * i / steps, z0 + dz * i / steps
    if blockedAt(pl, tx, self.y, tz, h) then
      if i == 1 and blockedAt(pl, x0, self.y, z0, oldH) then return wx, wz, false end   -- already inside something: let it fly out
      return nx, nz, true
    end
    nx, nz = tx, tz
  end
  return nx, nz, false
end

function update(dt)
  local pl = find("Player")
  local pilot = pl ~= nil and pl.car == self.name and pl.seat == 1
  self.driven = pilot
  local fwd = 0
  local turn = 0
  local climb = 0
  if pilot then
    if input.joyActive then
      fwd = input.joyY
      turn = input.joyX
    end
    if input.buttons["Climb"] then climb = 1 end
    if input.buttons["Descend"] then climb = -1 end
  end
  -- the rotor spins up while the pilot sits in
  local rt = 0
  if pilot then rt = 1 end
  rotor = rotor + (rt - rotor) * math.min(1, dt * 0.6)
  if rt == 0 and rotor < 0.03 then rotor = 0 end
  local flying = rotor > 0.8
  local top = 40
  crashT = (crashT or 0) - dt
  local oldHeading = heading
  if flying then
    vy = vy + (climb * 7 - vy) * math.min(1, dt * 2)
    speed = speed + (fwd * top - speed) * math.min(1, dt * 0.9)
    heading = heading - turn * 70 * dt
  else
    vy = vy - 12 * dt
    speed = speed * math.max(0, 1 - dt * 2)
  end
  local r = math.rad(heading)
  local fx = -math.sin(r)
  local fz = -math.cos(r)
  local nx, nz, hit = slide(pl, self.x + fx * speed * dt, self.z + fz * speed * dt, heading, oldHeading)
  local ny = self.y + vy * dt
  if hit then
    if math.abs(speed) > 4 and (crashT or 0) <= 0 then
      crashT = 0.6   -- one crash sound and one hurt per 0.6 s, not one per frame
      playSound("hit", 0.8, 0.5)
      -- a hard crash hurts the people inside
      if pl ~= nil and pl.car == self.name and math.abs(speed) > 14 then pl.hp = pl.hp - (math.abs(speed) - 14) * 1.5 end
    end
    speed = -speed * 0.25
    if blockedAt(pl, nx, self.y, nz, heading) then   -- the turn itself ran into something: keep the old heading
      heading = oldHeading
      r = math.rad(heading)
    end
  end
  -- up into a ceiling or down onto a roof: it stops there
  if pl ~= nil and pl.wallAt ~= nil and vy > 0.3 and blockedAt(pl, nx, ny, nz, heading) and not blockedAt(pl, nx, self.y, nz, heading) then
    if pl.car == self.name and math.abs(vy) > 9 then pl.hp = pl.hp - (math.abs(vy) - 9) * 3 end
    ny = self.y
    vy = 0
  end
  local gh = groundHeight(nx, nz)
  -- the floor is the ground, or the roof / wall top under the skids: the helicopter can land on a roof
  local floorY = gh or 0
  local lift = padLift
  if pl ~= nil and pl.roofAt ~= nil then
    local hcs, hsn = math.cos(r), math.sin(r)
    for i = 1, #LP do
      local q = LP[i]
      local rt = pl.roofAt(nx + q[1] * hcs + q[2] * hsn, self.y - 0.25, nz - q[1] * hsn + q[2] * hcs)
      if rt ~= nil and rt > floorY then floorY = rt end
    end
  end
  if floorY > (gh or 0) + 0.3 then
    lift = 0
    self.roof = true
  else
    self.roof = false
  end
  local minY = floorY + 0.85 + lift
  if ny <= minY then
    ny = minY
    if vy < 0 then vy = 0 end
  end
  self.ry = heading
  self.rx = -(speed / top) * 12
  self.x = nx
  self.y = ny
  self.z = nz
  self.speed = speed
  self.agl = ny - minY
  -- passengers and the pilot are carried with the NEW position
  if pl ~= nil and pl.car == self.name then
    local st = SEATS[pl.seat or 1]
    pl.x = nx + math.cos(r) * st[1] + math.sin(r) * st[2]
    pl.z = nz - math.sin(r) * st[1] + math.cos(r) * st[2]
    pl.y = ny + st[3]
    pl.vy = 0
  end
  -- the rotors
  spin = spin + rotor * 1500 * dt
  if rotor > 0.01 or math.abs(speed) > 0.01 or math.abs(vy) > 0.01 or not placed then
    placed = true
    local cs, sn = math.cos(r), math.sin(r)
    local pitch = math.rad(self.rx)
    local cp, sp = math.cos(pitch), math.sin(pitch)
    put(r1, 0, 1.85, -0.3, nx, nz, cs, sn, cp, sp, heading + spin, self.rx, 0)
    put(r2, 0, 1.85, -0.3, nx, nz, cs, sn, cp, sp, heading + spin, self.rx, 0)
    put(tailR, 0.2, 0.6, 4.5, nx, nz, cs, sn, cp, sp, heading, self.rx + spin * 1.3, 0)
  end
end

function oncollide(other)
  if math.abs(speed) > 6 then speed = speed * 0.5 end
end
"""

    private val BR_PLANE = """-- A small plane for two. Walk up and tap DRIVE (pilot) or SEAT (passenger).
-- Joystick up / down = engine power up / down (down also brakes on the ground), left / right = steer on the ground, bank and turn in the air.
-- Take off: full power along the runway; at speed it lifts off (or hold CLIMB). CLIMB / DESCEND = nose up / down. Too slow in the air and it stalls.
-- Land gently, stop, then tap DRIVE to get out.
SEATS = { { 0, -0.2, 0.7 }, { 0, 1.2, 0.7 } }
H = 1.22   -- the height of the middle of the plane over the ground when it stands on its wheels

function clamp(v, a, b)
  if v < a then return a end
  if v > b then return b end
  return v
end

function start()
  speed = 0
  thr = 0
  vy = 0
  heading = self.ry
  pitch = 0
  roll = 0
  spin = 0
  ps = 0
  air = false
  self.speed = 0
  self.driven = false
  self.agl = 0
  self.kind = "plane"
  self.nseats = 2
  self.seatsDef = SEATS
  self.camDist = 15
  self.legSwing = 80
  local nm = self.name
  blade1 = find(nm .. " prop1")
  blade2 = find(nm .. " prop2")
  placed = false
  crashT = 0
end

function put(o, lx, ly, lz, nx, nz, cs, sn, cp, sp, ry2, rx2, rz2)
  if o == nil then return end
  local y2 = ly * cp - lz * sp
  local z2 = ly * sp + lz * cp
  o.x = nx + lx * cs + z2 * sn
  o.z = nz - lx * sn + z2 * cs
  o.y = self.y + y2
  o.ry = ry2
  o.rx = rx2
  o.rz = rz2
end

-- nose, wing tips, tail: the plane stops at walls and tree trunks (the player script knows where they are)
CP = { { 0, -3.8 }, { -4.4, -0.3 }, { 4.4, -0.3 }, { -2.4, -0.3 }, { 2.4, -0.3 }, { 0, 3.6 }, { -1.6, 4.5 }, { 1.6, 4.5 }, { 0, 4.9 } }

function blockedAt(pl, x, y, z, h)
  local r = math.rad(h)
  local cs, sn = math.cos(r), math.sin(r)
  for i = 1, #CP do
    local p = CP[i]
    local wx = x + p[1] * cs + p[2] * sn
    local wz = z - p[1] * sn + p[2] * cs
    if pl.wallAt(wx, y - 0.4, wz) or pl.wallAt(wx, y + 0.4, wz) or pl.treeAt(wx, y - 0.4, wz) or pl.treeAt(wx, y + 0.4, wz) then return true end
  end
  return false
end

function slide(pl, wx, wz, h, oldH)
  if pl == nil or pl.wallAt == nil then return wx, wz, false end
  local x0, z0 = self.x, self.z
  local dx, dz = wx - x0, wz - z0
  if dx * dx + dz * dz < 0.000001 and h == oldH then return wx, wz, false end
  local steps = math.max(1, math.min(10, math.ceil(math.sqrt(dx * dx + dz * dz) / 0.4)))
  local nx, nz = x0, z0
  for i = 1, steps do
    local tx, tz = x0 + dx * i / steps, z0 + dz * i / steps
    if blockedAt(pl, tx, self.y, tz, h) then
      if i == 1 and blockedAt(pl, x0, self.y, z0, oldH) then return wx, wz, false end
      return nx, nz, true
    end
    nx, nz = tx, tz
  end
  return nx, nz, false
end

function update(dt)
  local pl = find("Player")
  local pilot = pl ~= nil and pl.car == self.name and pl.seat == 1
  self.driven = pilot
  crashT = crashT - dt
  -- parked and still: nothing to do
  if not pilot and not air and speed < 0.05 and placed and ps < 0.02 then
    self.speed = 0
    return
  end
  local joyX, joyY, climb = 0, 0, 0
  if pilot then
    if input.joyActive then
      joyX = input.joyX
      joyY = input.joyY
    end
    if input.buttons["Climb"] then climb = 1 end
    if input.buttons["Descend"] then climb = -1 end
    thr = clamp(thr + joyY * 0.7 * dt, 0, 1)
  else
    thr = 0
  end
  -- engine, drag and brakes
  local sinP = math.sin(math.rad(pitch))
  local brake = pilot and joyY < -0.3 and not air
  local acc
  if thr > 0.02 then
    acc = clamp((thr * 58 - speed) * 0.5, -3, 4.2)
  elseif air then
    acc = -1.5
  else
    acc = -3
  end
  if brake then acc = acc - 14 end
  if air then acc = acc - sinP * 12 end
  speed = math.max(0, speed + acc * dt)
  -- steering and the nose
  local lift = clamp((speed - 13) / 9, 0, 1)
  local oldHeading = heading
  if air then
    local ctl = math.min(1, speed / 18)
    heading = heading - joyX * 38 * ctl * dt
    roll = roll + (-joyX * 35 * ctl - roll) * math.min(1, dt * 3)
    local target = 0
    if climb > 0 then target = 20 elseif climb < 0 then target = -22 end
    target = target * (0.3 + 0.7 * lift)
    if lift < 0.35 then target = -28 end   -- too slow: the nose drops (stall)
    pitch = pitch + (target - pitch) * math.min(1, dt * 2)
    local want = math.sin(math.rad(pitch)) * speed * lift - (1 - lift) * 16
    vy = vy + (want - vy) * math.min(1, dt * 2.2)
  else
    heading = heading - joyX * 40 * math.min(1, speed / 6) * dt
    roll = roll * math.max(0, 1 - dt * 5)
  end
  local r = math.rad(heading)
  local fx = -math.sin(r)
  local fz = -math.cos(r)
  local hs = speed * math.cos(math.rad(pitch))
  local nx, nz, hit = slide(pl, self.x + fx * hs * dt, self.z + fz * hs * dt, heading, oldHeading)
  if hit then
    if speed > 6 and crashT <= 0 then
      crashT = 0.7
      playSound("hit", 0.9, 0.5)
      if pl ~= nil and pl.car == self.name and speed > 12 then pl.hp = pl.hp - (speed - 12) * 2 end
    end
    speed = speed * 0.35
    if blockedAt(pl, nx, self.y, nz, heading) then
      heading = oldHeading
      r = math.rad(heading)
      fx = -math.sin(r)
      fz = -math.cos(r)
    end
  end
  -- height
  local gh = groundHeight(nx, nz) or self.y
  local minY = gh + H
  local ny
  if air then
    ny = self.y + vy * dt
    if ny > 350 then
      ny = 350
      if vy > 0 then vy = 0 end
    end
    -- up into a ceiling or down onto a roof: it stops there
    if pl ~= nil and pl.wallAt ~= nil and math.abs(vy) > 0.3 and blockedAt(pl, nx, ny, nz, heading) and not blockedAt(pl, nx, self.y, nz, heading) then
      ny = self.y
      vy = 0
    end
    if ny <= minY then
      -- touching the ground: a gentle landing is fine, a hard one hurts
      local hard = vy < -8 or (pitch < -16 and speed > 12) or (math.abs(roll) > 22 and speed > 10)
      if hard and crashT <= 0 then
        crashT = 1
        playSound("hit", 1, 0.5)
        log("Crash landing!")
        if pl ~= nil and pl.car == self.name then
          local dmg = math.max(0, -vy - 5) * 5
          if math.abs(roll) > 22 then dmg = dmg + 15 end
          pl.hp = pl.hp - dmg
        end
        speed = speed * 0.5
      end
      ny = minY
      air = false
      vy = 0
      roll = 0
    end
  else
    -- on the wheels: follows the slope of the ground
    local gF = groundHeight(nx + fx * 3, nz + fz * 3) or gh
    local gB = groundHeight(nx - fx * 3, nz - fz * 3) or gh
    pitch = clamp(math.deg(math.atan2(gF - gB, 6)), -12, 12)
    ny = math.max(gh, (gF + gB) / 2) + H
    vy = 0
    if speed > 26 or (climb > 0 and speed > 20) then   -- fast enough: it lifts off
      air = true
      vy = 3
    end
  end
  self.ry = heading
  self.rx = pitch
  self.rz = roll
  self.x = nx
  self.y = ny
  self.z = nz
  self.speed = speed
  if air then self.agl = math.max(0, ny - minY) else self.agl = 0 end
  -- the pilot and the passenger are carried with the NEW position
  if pl ~= nil and pl.car == self.name then
    local st = SEATS[pl.seat or 1]
    pl.x = nx + math.cos(r) * st[1] + math.sin(r) * st[2]
    pl.z = nz - math.sin(r) * st[1] + math.cos(r) * st[2]
    pl.y = ny + st[3]
    pl.vy = 0
  end
  -- the propeller
  local want = 0
  if pilot then want = 1 end
  ps = ps + (want - ps) * math.min(1, dt * 1.5)
  if ps < 0.02 and want == 0 then ps = 0 end
  spin = (spin + ps * (900 + thr * 2200) * dt) % 360
  if ps > 0.01 or air or speed > 0.01 or not placed then
    placed = true
    local cs, sn = math.cos(r), math.sin(r)
    local pr = math.rad(pitch)
    local cp, sp = math.cos(pr), math.sin(pr)
    put(blade1, 0, 0, -4.05, nx, nz, cs, sn, cp, sp, heading, pitch, spin)
    put(blade2, 0, 0, -4.05, nx, nz, cs, sn, cp, sp, heading, pitch, spin + 90)
  end
end
"""

    private val BR_TANK = """-- A tank for four. Walk up and tap DRIVE (driver) or SEAT (passenger on the deck).
-- Joystick up / down = forward / back, left / right = turn (also on the spot). The cannon follows the crosshair: FIRE = a shell, one every 2.5 s.
-- It crushes enemies in front of it, stops at walls and tree trunks, and cannot go into deep water.
SEATS = { { -0.75, -1.6, 0.85 }, { 0.75, -1.6, 0.85 }, { -0.7, 2.0, 1.05 }, { 0.7, 2.0, 1.05 } }   -- driver, front passenger, two on the back deck

function clamp(v, a, b)
  if v < a then return a end
  if v > b then return b end
  return v
end

function start()
  speed = 0
  heading = self.ry
  self.speed = 0
  self.driven = false
  self.kind = "tank"
  self.nseats = 4
  self.seatsDef = SEATS
  self.camDist = 13
  self.legSwing = 80
  self.recoil = 0
  local nm = self.name
  turret = find(nm .. " turret")
  barrel = find(nm .. " barrel")
  tYaw = self.ry
  bp = 0
  placed = false
  crashT = 0
  bots = {}
  local i = 1
  while true do
    local b = find("Bot " .. i)
    if b == nil then break end
    bots[i] = b
    i = i + 1
  end
end

function put(o, lx, ly, lz, nx, nz, cs, sn, cp, sp, ry2, rx2, rz2)
  if o == nil then return end
  local y2 = ly * cp - lz * sp
  local z2 = ly * sp + lz * cp
  o.x = nx + lx * cs + z2 * sn
  o.z = nz - lx * sn + z2 * cs
  o.y = self.y + y2
  o.ry = ry2
  o.rx = rx2
  o.rz = rz2
end

CP = { { 0, -2.9 }, { -1.5, -2.9 }, { 1.5, -2.9 }, { -1.7, -1.4 }, { 1.7, -1.4 }, { -1.7, 0 }, { 1.7, 0 }, { -1.7, 1.4 }, { 1.7, 1.4 }, { -1.5, 2.9 }, { 1.5, 2.9 }, { 0, 2.9 } }

-- solid: walls, closed doors, tree trunks and deep water stop the tank
function blockedAt(pl, x, z, h)
  local r = math.rad(h)
  local cs, sn = math.cos(r), math.sin(r)
  local y0 = self.y - 0.3
  local y1 = self.y + 0.4
  for i = 1, #CP do
    local p = CP[i]
    local wx = x + p[1] * cs + p[2] * sn
    local wz = z - p[1] * sn + p[2] * cs
    if pl.wallAt(wx, y0, wz) or pl.wallAt(wx, y1, wz) or pl.treeAt(wx, y0, wz) or pl.treeAt(wx, y1, wz) then return true end
    local w = waterHeight(wx, wz)
    if w ~= nil then
      local g = groundHeight(wx, wz)
      if g ~= nil and g < w - 1.6 then return true end
    end
  end
  return false
end

function slide(pl, wx, wz, h, oldH)
  if pl == nil or pl.wallAt == nil then return wx, wz, false end
  local x0, z0 = self.x, self.z
  local dx, dz = wx - x0, wz - z0
  if dx * dx + dz * dz < 0.000001 and h == oldH then return wx, wz, false end
  local steps = math.max(1, math.min(10, math.ceil(math.sqrt(dx * dx + dz * dz) / 0.3)))
  local nx, nz = x0, z0
  for i = 1, steps do
    local tx, tz = x0 + dx * i / steps, z0 + dz * i / steps
    if blockedAt(pl, tx, tz, h) then
      if i == 1 and blockedAt(pl, x0, z0, oldH) then return wx, wz, false end
      return nx, nz, true
    end
    nx, nz = tx, tz
  end
  return nx, nz, false
end

function update(dt)
  local pl = find("Player")
  local driven = pl ~= nil and pl.car == self.name and pl.seat == 1
  self.driven = driven
  crashT = crashT - dt
  local rc = self.recoil or 0
  -- parked and still: nothing to do
  if not driven and speed == 0 and placed and rc <= 0 then return end
  if rc > 0 then self.recoil = math.max(0, rc - dt * 2.5) end
  local throttle, steer = 0, 0
  if driven and input.joyActive then
    throttle = input.joyY
    steer = input.joyX
  end
  if throttle > 0.1 then
    local a = 7 * throttle
    if speed < 0 then a = a + 8 end
    speed = speed + a * dt
  elseif throttle < -0.1 then
    if speed > 0.5 then
      speed = speed - 14 * dt
    else
      speed = speed + 5 * throttle * dt
    end
  else
    local drag = 6 * dt
    if speed > 0 then speed = math.max(0, speed - drag) else speed = math.min(0, speed + drag) end
  end
  speed = clamp(speed, -5, 11)
  local dir = 1
  if speed < -0.3 then dir = -1 end
  local oldHeading = heading
  heading = heading - steer * 48 * dir * dt   -- tracks: it also turns on the spot
  local r = math.rad(heading)
  local fx = -math.sin(r)
  local fz = -math.cos(r)
  local nx, nz, hit = slide(pl, self.x + fx * speed * dt, self.z + fz * speed * dt, heading, oldHeading)
  if hit then
    if math.abs(speed) > 4 and crashT <= 0 then
      crashT = 0.6
      playSound("hit", 0.6, 0.5)
    end
    speed = speed * 0.2
    if blockedAt(pl, nx, nz, heading) then
      heading = oldHeading
      r = math.rad(heading)
      fx = -math.sin(r)
      fz = -math.cos(r)
    end
  end
  self.ry = heading
  local gF = groundHeight(nx + fx * 2.6, nz + fz * 2.6)
  local gB = groundHeight(nx - fx * 2.6, nz - fz * 2.6)
  local g = groundHeight(nx, nz)
  if gF ~= nil and gB ~= nil and g ~= nil then
    self.rx = math.deg(math.atan2(gF - gB, 5.2))
    self.y = math.max(g, (gF + gB) / 2) + 0.75
  end
  self.x = nx
  self.z = nz
  self.speed = speed
  -- crush enemies in front of a moving tank
  if driven and speed > 2.5 then
    local cx, cz = nx + fx * 2.4, nz + fz * 2.4
    for i = 1, #bots do
      local e = bots[i]
      if e ~= nil and not e.dead and e.hp > 0 and math.abs(e.y - self.y) < 2.5 then
        local ex, ez = e.x - cx, e.z - cz
        if ex * ex + ez * ez < 4.5 then
          e.hp = e.hp - 150 * dt
          if e.hp <= 0 then log("Enemy run over!") end
        end
      end
    end
  end
  -- whoever sits on the tank is carried with the NEW position
  if pl ~= nil and pl.car == self.name then
    local st = SEATS[pl.seat or 1]
    pl.x = nx + math.cos(r) * st[1] + math.sin(r) * st[2]
    pl.z = nz - math.sin(r) * st[1] + math.cos(r) * st[2]
    pl.y = self.y + st[3]
    pl.vy = 0
  end
  -- the turret and the cannon
  local cs, sn = math.cos(r), math.sin(r)
  local pitch = math.rad(self.rx)
  local cp, sp = math.cos(pitch), math.sin(pitch)
  if turret ~= nil and barrel ~= nil then
    if driven and self.tx ~= nil then
      local ddx, ddz = self.tx - turret.x, self.tz - turret.z
      local want = math.deg(math.atan2(-ddx, -ddz))
      local diff = ((want - tYaw + 540) % 360) - 180
      local step = 85 * dt
      tYaw = tYaw + clamp(diff, -step, step)
      local hor = math.sqrt(ddx * ddx + ddz * ddz)
      local wp = math.deg(math.atan2(self.ty - (turret.y + 0.05), math.max(hor, 8)))
      wp = clamp(wp, -8, 28)
      bp = bp + clamp(wp - bp, -35 * dt, 35 * dt)
    end
    put(turret, 0, 0.75, 0.2, nx, nz, cs, sn, cp, sp, tYaw, 0, 0)
    local ty = math.rad(tYaw)
    local br = math.rad(bp)
    local ax = -math.sin(ty) * math.cos(br)
    local ay = math.sin(br)
    local az = -math.cos(ty) * math.cos(br)
    local back = (self.recoil or 0) * 0.5
    local rx0 = turret.x - math.sin(ty) * 1.1 - ax * back
    local ry0 = turret.y + 0.05 - ay * back
    local rz0 = turret.z - math.cos(ty) * 1.1 - az * back
    barrel.x = rx0 + ax * 1.8
    barrel.y = ry0 + ay * 1.8
    barrel.z = rz0 + az * 1.8
    barrel.ry = tYaw
    barrel.rx = bp
    barrel.rz = 0
    self.mx = rx0 + ax * 3.7
    self.my = ry0 + ay * 3.7
    self.mz = rz0 + az * 3.7
    self.aimx = ax
    self.aimy = ay
    self.aimz = az
  end
  placed = true
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

    private val BR_BOAT = """-- A boat for four. Swim or walk up and tap DRIVE (driver) or SEAT. Joystick: up = gas, down = brake and reverse, left / right = steer.
-- It floats on the waves and stays in deep water (the beach stops it).
SEATS = { { -0.5, -0.9, 0.62 }, { 0.5, -0.9, 0.62 }, { -0.5, 0.9, 0.62 }, { 0.5, 0.9, 0.62 } }   -- driver, front passenger, two in the back

function start()
  speed = 0
  heading = self.ry
  self.speed = 0
  self.driven = false
  self.kind = "boat"
  self.nseats = 4
  self.seatsDef = SEATS
  self.camDist = 9
  bobT = math.random() * 6
  tiltX = 0
  tiltZ = 0
  foam = {}
  foamT = 0
  crashT = 0
end

-- is the water here deep enough for the boat?
function deepAt(x, z)
  local w = waterHeight(x, z)
  if w == nil then return false end
  return groundHeight(x, z) < w - 0.45
end

function update(dt)
  local pl = find("Player")
  local driven = pl ~= nil and pl.car == self.name and pl.seat == 1
  self.driven = driven
  bobT = bobT + dt
  crashT = crashT - dt
  local throttle = 0
  local steer = 0
  if driven and input.joyActive then
    throttle = input.joyY
    steer = input.joyX
  end
  if throttle > 0.1 then
    local a = 7 * throttle
    if speed < 0 then a = a + 6 end
    speed = speed + a * dt
  elseif throttle < -0.1 then
    if speed > 0.5 then
      speed = speed - 9 * dt
    else
      speed = speed + 5 * throttle * dt
    end
  else
    local drag = 2.2 * dt
    if speed > 0 then speed = math.max(0, speed - drag) else speed = math.min(0, speed + drag) end
  end
  speed = math.max(-5, math.min(19, speed))
  local dir = 1
  if speed < 0 then dir = -1 end
  local oldHeading = heading
  heading = heading - steer * 42 * math.min(1, math.abs(speed) / 3) * dir * dt
  local r = math.rad(heading)
  local fx = -math.sin(r)
  local fz = -math.cos(r)
  local nx = self.x + fx * speed * dt
  local nz = self.z + fz * speed * dt
  -- the front, the back and the middle must be in deep water; otherwise the boat has touched the beach
  if not (deepAt(nx, nz) and deepAt(nx + fx * 2.8, nz + fz * 2.8) and deepAt(nx - fx * 2.8, nz - fz * 2.8)) then
    if math.abs(speed) > 4 and crashT <= 0 then
      crashT = 0.8
      playSound("hit", 0.5, 0.7)
      if pl ~= nil and pl.car == self.name and math.abs(speed) > 12 then pl.hp = pl.hp - (math.abs(speed) - 12) * 1.2 end
    end
    speed = -speed * 0.3
    nx, nz = self.x, self.z
    heading = oldHeading
    r = math.rad(heading)
    fx = -math.sin(r)
    fz = -math.cos(r)
  end
  local cs, sn = math.cos(r), math.sin(r)
  -- float on the waves: height from the water under the boat, tilted by the water at the front / back / sides
  local w0 = waterHeight(nx, nz) or self.y
  local wF = waterHeight(nx + fx * 2.2, nz + fz * 2.2) or w0
  local wB = waterHeight(nx - fx * 2.2, nz - fz * 2.2) or w0
  local wR = waterHeight(nx + cs * 1.0, nz - sn * 1.0) or w0
  local wL = waterHeight(nx - cs * 1.0, nz + sn * 1.0) or w0
  local wantX = math.deg(math.atan2(wF - wB, 4.4)) + math.max(0, speed) * 0.25   -- the bow lifts a little when going fast
  local wantZ = math.deg(math.atan2(wR - wL, 2.0)) - steer * speed * 0.15       -- it leans into a turn
  local k = math.min(1, dt * 4)
  tiltX = tiltX + (wantX - tiltX) * k
  tiltZ = tiltZ + (wantZ - tiltZ) * k
  self.x = nx
  self.z = nz
  self.y = (w0 + wF + wB) / 3 + 0.22 + math.sin(bobT * 1.7) * 0.03
  self.ry = heading
  self.rx = tiltX
  self.rz = tiltZ
  self.speed = speed
  -- whoever sits in the boat is carried along with the NEW position
  if pl ~= nil and pl.car == self.name then
    local st = SEATS[pl.seat or 1]
    pl.x = nx + cs * st[1] + sn * st[2]
    pl.z = nz - sn * st[1] + cs * st[2]
    pl.y = self.y + st[3]
    pl.vy = 0
  end
  -- a wake of foam behind the boat you drive
  foamT = foamT - dt
  if driven and speed > 2 and foamT <= 0 then
    foamT = 0.13
    local ok, o = pcall(spawn, "sphere", nx - fx * 3.1, w0 + 0.08, nz - fz * 3.1)
    if ok then
      o.sx = 0.6
      o.sy = 0.12
      o.sz = 0.6
      o.r = 0.95
      o.g = 0.98
      o.b = 1
      o.opacity = 0.6
      o.emit = 0.2
      o.shadow = false
      o.collider = 0
      foam[#foam + 1] = { o = o, t = 0 }
    end
  end
  for i = #foam, 1, -1 do
    local f = foam[i]
    f.t = f.t + dt
    local s2 = 0.6 + f.t * 1.4
    f.o.sx = s2
    f.o.sz = s2
    f.o.opacity = math.max(0, 0.6 * (1 - f.t / 1.6))
    if f.t > 1.6 then
      destroy(f.o)
      table.remove(foam, i)
    end
  end
end
"""

    private val BR_MEDKIT = """-- Medkit: walk over it to put it in your bag (tap HEAL to use it). It comes back after 25 seconds.
function start()
  phase = math.random() * 6
  home = self.y
  taken = 0
  player = find("Player")
  nearT = math.random() * 0.3
  near = true
end

function update(dt)
  if taken > 0 then
    taken = taken - dt
    if taken <= 0 then self.y = home end
    return
  end
  -- far from the player: nothing to animate (saves a lot of work on big maps)
  nearT = nearT - dt
  if nearT <= 0 then
    nearT = 0.3
    near = true
    if player ~= nil then
      local dx, dz = player.x - self.x, player.z - self.z
      near = dx * dx + dz * dz < 3600
    end
  end
  if not near then return end
  self.ry = self.ry + 120 * dt
  self.y = home + 0.15 + math.sin(time * 3 + phase) * 0.1
end

function oncollide(other)
  if taken > 0 or other.name ~= "Player" or other.dead then return end
  if (other.nMed or 0) >= 4 then return end
  other.addMed = (other.addMed or 0) + 1
  playSound("pickup", 0.8)
  taken = 25
  self.y = -50
end
"""

    private val BR_AMMO = """-- Ammo box (the kind and the amount are filled in when the game is built): walk over it. It comes back after 25 seconds.
function start()
  phase = math.random() * 6
  home = self.y
  taken = 0
  player = find("Player")
  nearT = math.random() * 0.3
  near = true
end

function update(dt)
  if taken > 0 then
    taken = taken - dt
    if taken <= 0 then self.y = home end
    return
  end
  -- far from the player: nothing to animate (saves a lot of work on big maps)
  nearT = nearT - dt
  if nearT <= 0 then
    nearT = 0.3
    near = true
    if player ~= nil then
      local dx, dz = player.x - self.x, player.z - self.z
      near = dx * dx + dz * dz < 3600
    end
  end
  if not near then return end
  self.ry = self.ry + 90 * dt
  self.y = home + 0.15 + math.sin(time * 3 + phase) * 0.1
end

function oncollide(other)
  if taken > 0 or other.name ~= "Player" or other.dead then return end
  other.%F% = (other.%F% or 0) + %N%
  playSound("pickup", 0.8, 1.2)
  taken = 25
  self.y = -50
end
"""
    private val BR_WEAPON = """-- A weapon on the ground: walk over it to put it in your bag. It comes back after 60 seconds.
function start()
  phase = math.random() * 6
  home = self.y
  taken = 0
  player = find("Player")
  nearT = math.random() * 0.3
  near = true
end

function update(dt)
  if taken > 0 then
    taken = taken - dt
    if taken <= 0 then self.y = home end
    return
  end
  -- far from the player: nothing to animate (saves a lot of work on big maps)
  nearT = nearT - dt
  if nearT <= 0 then
    nearT = 0.3
    near = true
    if player ~= nil then
      local dx, dz = player.x - self.x, player.z - self.z
      near = dx * dx + dz * dz < 3600
    end
  end
  if not near then return end
  self.ry = self.ry + 60 * dt
  self.y = home + 0.15 + math.sin(time * 3 + phase) * 0.1
end

function oncollide(other)
  if taken > 0 or other.name ~= "Player" or other.dead then return end
  other.addWeapon = "%W%"
  playSound("pickup", 0.8, 0.8)
  taken = 60
  self.y = -50
end
"""
    private val BR_GRENADE = """-- Grenades: walk over them to put two in your bag. They come back after 40 seconds.
function start()
  phase = math.random() * 6
  home = self.y
  taken = 0
  player = find("Player")
  nearT = math.random() * 0.3
  near = true
end

function update(dt)
  if taken > 0 then
    taken = taken - dt
    if taken <= 0 then self.y = home end
    return
  end
  -- far from the player: nothing to animate (saves a lot of work on big maps)
  nearT = nearT - dt
  if nearT <= 0 then
    nearT = 0.3
    near = true
    if player ~= nil then
      local dx, dz = player.x - self.x, player.z - self.z
      near = dx * dx + dz * dz < 3600
    end
  end
  if not near then return end
  self.ry = self.ry + 100 * dt
  self.y = home + 0.15 + math.sin(time * 3 + phase) * 0.1
end

function oncollide(other)
  if taken > 0 or other.name ~= "Player" or other.dead then return end
  if (other.nGren or 0) >= 5 then return end
  other.addGren = (other.addGren or 0) + 2
  playSound("pickup", 0.8, 0.9)
  taken = 40
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
        val houseLoot = ArrayList<FloatArray>()      // x, y, z, 0 = medkit / 1 = ammo box / 2 = weapon / 3 = grenades
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
            houseLoot.add(floatArrayOf(cx + W * 0.34f, top + 0.5f, cz - hd * 0.7f, 2f))
            houseLoot.add(floatArrayOf(cx - W * 0.1f, top + 0.5f, cz + hd * 0.5f, 3f))
            for (k in 1 until floors) {
                houseLoot.add(floatArrayOf(cx + W * 0.12f, top + k * floorH + 0.5f, cz + hd * 0.2f, if (k % 3 == 0) 2f else if (k % 3 == 1) 1f else 0f))
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
            carPart(0f, 0.8f, 0.1f, 1.8f, 1.1f, 2.3f, floatArrayOf(0.45f, 0.58f, 0.68f)).opacity = 0.12f
            carPart(0f, 1.39f, 0.1f, 1.86f, 0.08f, 2.36f, floatArrayOf(col[0] * 0.8f, col[1] * 0.8f, col[2] * 0.8f))   // roof
            carPart(0f, 0.36f, -0.85f, 1.7f, 0.22f, 0.35f, dark)                                                      // dashboard
            for (sx in floatArrayOf(-0.45f, 0.45f)) {                                                                // the two front seats
                carPart(sx, 0.29f, -0.3f, 0.5f, 0.08f, 0.5f, fabric)
                carPart(sx, 0.61f, 0.0f, 0.5f, 0.56f, 0.1f, fabric)
            }
            carPart(0f, 0.29f, 0.7f, 1.5f, 0.08f, 0.5f, fabric)                                                       // the back seat
            carPart(0f, 0.61f, 1.0f, 1.5f, 0.56f, 0.1f, fabric)
            // round wheels and the steering wheel are moved by the car script (the front wheels turn), so they are not fixed to the body
            val yr = Math.toRadians(yawDeg.toDouble())
            val ycs = Math.cos(yr).toFloat()
            val ysn = Math.sin(yr).toFloat()
            val wheelNames = arrayOf("FL", "FR", "RL", "RR")
            var wi = 0
            for (wz in floatArrayOf(-1.35f, 1.35f)) for (wx in floatArrayOf(-0.98f, 0.98f)) {
                val wname = "Car " + carN + " wheel " + wheelNames[wi++]
                val w = if (wheel != null) scene.add(Shape.MODEL, wheel, wname) else scene.add(Shape.SPHERE, null, wname)
                w.x = x + wx * ycs + wz * ysn
                w.y = y - 0.15f
                w.z = z - wx * ysn + wz * ycs
                if (wheel != null) { w.sx = 0.8f; w.sy = 0.34f; w.sz = 0.8f; w.rz = 90f } else { w.sx = 0.34f; w.sy = 0.8f; w.sz = 0.8f }
                w.ry = yawDeg
                w.color = dark
                w.shadow = false
            }
            for (k in 0..1) {
                val bar = mk(scene, Shape.CUBE, "Car " + carN + " steer" + (k + 1), x + (-0.45f) * ycs + (-0.62f) * ysn, y + 0.62f, z - (-0.45f) * ysn + (-0.62f) * ycs,
                    if (k == 0) 0.38f else 0.04f, if (k == 0) 0.04f else 0.38f, 0.05f, dark)
                bar.ry = yawDeg
                bar.rx = -20f
                bar.shadow = false
            }
            for (lx in floatArrayOf(-0.65f, 0.65f)) carPart(lx, 0.05f, -2.12f, 0.4f, 0.2f, 0.08f, floatArrayOf(1f, 0.95f, 0.7f)).emit = 0.8f   // headlights (front is -z)
            for (p in parts) Rig.bind(p, body)
            body.ry = yawDeg
        }

        // ---- motorcycles (2 seats) ----
        var bikeN = 0
        fun buildBike(x: Float, z: Float, yawDeg: Float) {
            val g = ground(x, z) ?: return
            if (g < seaY + 0.3f) return
            bikeN++
            val col = carCols[rnd.nextInt(carCols.size)]
            val y = g + 0.62f
            val body = mk(scene, Shape.CUBE, "Bike " + bikeN, x, y, z, 0.36f, 0.45f, 1.2f, col, BR_BIKE)
            body.collider = 1
            val dark = floatArrayOf(0.07f, 0.07f, 0.08f)
            val steel = floatArrayOf(0.35f, 0.36f, 0.38f)
            val bparts = ArrayList<SceneObject>()
            fun bikePart(dx: Float, dy: Float, dz: Float, psx: Float, psy: Float, psz: Float, c: FloatArray): SceneObject {
                val p = mk(scene, Shape.CUBE, "Bike " + bikeN + " part", x + dx, y + dy, z + dz, psx, psy, psz, c)
                p.shadow = false
                bparts.add(p)
                return p
            }
            bikePart(0f, -0.15f, 0f, 0.3f, 0.28f, 0.45f, steel)                                   // engine
            bikePart(0f, 0.3f, 0.45f, 0.3f, 0.1f, 0.75f, dark)                                    // seat
            bikePart(0f, 0.3f, -0.2f, 0.34f, 0.22f, 0.5f, floatArrayOf(col[0] * 0.8f, col[1] * 0.8f, col[2] * 0.8f))   // tank
            bikePart(0f, 0.2f, -0.95f, 0.2f, 0.2f, 0.1f, floatArrayOf(1f, 0.95f, 0.7f)).emit = 0.8f                   // headlight
            val yr = Math.toRadians(yawDeg.toDouble())
            val ycs = Math.cos(yr).toFloat()
            val ysn = Math.sin(yr).toFloat()
            fun bikeFree(name: String, lz: Float, ly: Float, w0: Float, w1: Float, w2: Float, c: FloatArray, wheelObj: Boolean): SceneObject {
                val o = if (wheelObj && wheel != null) scene.add(Shape.MODEL, wheel, name) else if (wheelObj) scene.add(Shape.SPHERE, null, name) else scene.add(Shape.CUBE, null, name)
                o.x = x + lz * ysn
                o.y = y + ly
                o.z = z + lz * ycs
                o.sx = w0; o.sy = w1; o.sz = w2
                o.color = c
                o.shadow = false
                o.ry = yawDeg
                if (wheelObj && wheel != null) o.rz = 90f
                return o
            }
            val rear = bikeFree("Bike " + bikeN + " wheel R", 0.85f, -0.27f, 0.7f, 0.18f, 0.7f, dark, true)
            bparts.add(rear)                                                                       // the rear wheel stays on the body
            bikeFree("Bike " + bikeN + " wheel F", -0.85f, -0.27f, 0.7f, 0.18f, 0.7f, dark, true)   // the front wheel, fork and handlebar are turned by the script
            bikeFree("Bike " + bikeN + " fork", -0.85f, 0f, 0.07f, 0.75f, 0.07f, steel, false)
            bikeFree("Bike " + bikeN + " bar", -0.75f, 0.5f, 0.8f, 0.05f, 0.05f, dark, false)
            for (p in bparts) Rig.bind(p, body)
            body.ry = yawDeg
        }

        // ---- helicopters (4 seats) on levelled pads ----
        var heliN = 0
        fun buildHeli(x: Float, z: Float, yawDeg: Float, level: Float) {
            heliN++
            val col = carCols[rnd.nextInt(carCols.size)]
            val y = level + 0.12f + 0.85f
            val body = mk(scene, Shape.CUBE, "Heli " + heliN, x, y, z, 1.7f, 0.55f, 3.2f, col, BR_HELI)
            body.collider = 1
            val dark = floatArrayOf(0.07f, 0.07f, 0.08f)
            val fabric = floatArrayOf(0.35f, 0.33f, 0.30f)
            val hparts = ArrayList<SceneObject>()
            fun hPart(dx: Float, dy: Float, dz: Float, psx: Float, psy: Float, psz: Float, c: FloatArray): SceneObject {
                val p = mk(scene, Shape.CUBE, "Heli " + heliN + " part", x + dx, y + dy, z + dz, psx, psy, psz, c)
                p.shadow = false
                hparts.add(p)
                return p
            }
            hPart(0f, 0.85f, -0.3f, 1.6f, 1.2f, 2.0f, floatArrayOf(0.45f, 0.58f, 0.68f)).opacity = 0.12f     // glass cabin
            hPart(0f, 1.5f, -0.3f, 1.7f, 0.12f, 2.2f, floatArrayOf(col[0] * 0.8f, col[1] * 0.8f, col[2] * 0.8f))   // roof
            hPart(0f, 0.15f, 3.1f, 0.3f, 0.3f, 3.0f, col)                                                     // tail boom
            hPart(0f, 0.65f, 4.4f, 0.08f, 0.9f, 0.6f, col)                                                    // tail fin
            hPart(0f, 1.7f, -0.3f, 0.14f, 0.3f, 0.14f, dark)                                                  // rotor mast
            hPart(-0.8f, -0.8f, 0f, 0.12f, 0.12f, 3.2f, dark)                                                 // skids
            hPart(0.8f, -0.8f, 0f, 0.12f, 0.12f, 3.2f, dark)
            hPart(0f, -0.55f, -0.8f, 1.5f, 0.08f, 0.08f, dark)                                                // struts
            hPart(0f, -0.55f, 0.8f, 1.5f, 0.08f, 0.08f, dark)
            hPart(-0.4f, 0.31f, -0.55f, 0.45f, 0.08f, 0.45f, fabric)                                          // seats
            hPart(0.4f, 0.31f, -0.55f, 0.45f, 0.08f, 0.45f, fabric)
            hPart(0f, 0.31f, 0.55f, 1.3f, 0.08f, 0.45f, fabric)
            for (p in hparts) Rig.bind(p, body)
            // the rotors are turned by the script
            val yr = Math.toRadians(yawDeg.toDouble())
            val ycs = Math.cos(yr).toFloat()
            val ysn = Math.sin(yr).toFloat()
            fun heliFree(name: String, lx: Float, ly: Float, lz: Float, w0: Float, w1: Float, w2: Float) {
                val o = scene.add(Shape.CUBE, null, name)
                o.x = x + lx * ycs + lz * ysn
                o.y = y + ly
                o.z = z - lx * ysn + lz * ycs
                o.sx = w0; o.sy = w1; o.sz = w2
                o.color = dark
                o.shadow = false
                o.ry = yawDeg
            }
            heliFree("Heli " + heliN + " rotor1", 0f, 1.85f, -0.3f, 7f, 0.04f, 0.28f)
            heliFree("Heli " + heliN + " rotor2", 0f, 1.85f, -0.3f, 0.28f, 0.04f, 7f)
            heliFree("Heli " + heliN + " tailrotor", 0.2f, 0.6f, 4.5f, 0.05f, 0.9f, 0.1f)
            body.ry = yawDeg
        }
        fun buildHelipad(x: Float, z: Float, level: Float) {
            val concrete = floatArrayOf(0.62f, 0.62f, 0.60f)
            val pad = mk(scene, Shape.CUBE, "Helipad", x, level + 0.06f, z, 12f, 0.12f, 12f, concrete)
            pad.shadow = false
            val white = floatArrayOf(0.95f, 0.95f, 0.92f)
            for (b in arrayOf(floatArrayOf(-1.4f, 0f, 0.6f, 4f), floatArrayOf(1.4f, 0f, 0.6f, 4f), floatArrayOf(0f, 0f, 2.2f, 0.6f))) {
                val m = mk(scene, Shape.CUBE, "Helipad mark", x + b[0], level + 0.125f, z + b[1], b[2], 0.02f, b[3], white)
                m.shadow = false
            }
        }

        // ---- boats (4 seats) floating on the sea near the shore ----
        var boatN = 0
        fun buildBoat(x: Float, z: Float, yawDeg: Float) {
            boatN++
            val cols = arrayOf(floatArrayOf(0.92f, 0.92f, 0.90f), floatArrayOf(0.15f, 0.35f, 0.75f), floatArrayOf(0.85f, 0.25f, 0.15f))
            val col = cols[rnd.nextInt(cols.size)]
            val dark = floatArrayOf(0.07f, 0.07f, 0.08f)
            val fabric = floatArrayOf(0.35f, 0.33f, 0.30f)
            val wood = floatArrayOf(0.62f, 0.46f, 0.3f)
            val y = seaY + 0.25f
            val body = mk(scene, Shape.CUBE, "Boat " + boatN, x, y, z, 2.4f, 0.5f, 6f, col, BR_BOAT)
            body.collider = 0
            body.shadow = false
            val bparts = ArrayList<SceneObject>()
            fun bPart(shape: Shape, dx: Float, dy: Float, dz: Float, psx: Float, psy: Float, psz: Float, c: FloatArray): SceneObject {
                val p = if (shape == Shape.CUBE) mk(scene, Shape.CUBE, "Boat " + boatN + " part", x + dx, y + dy, z + dz, psx, psy, psz, c)
                else { val o = scene.add(shape, null, "Boat " + boatN + " part"); o.x = x + dx; o.y = y + dy; o.z = z + dz; o.sx = psx; o.sy = psy; o.sz = psz; o.color = c; o }
                p.shadow = false
                bparts.add(p)
                return p
            }
            val side = floatArrayOf(col[0] * 0.85f, col[1] * 0.85f, col[2] * 0.85f)
            bPart(Shape.CUBE, -1.1f, 0.5f, 0f, 0.2f, 0.7f, 6f, side)                       // the two sides
            bPart(Shape.CUBE, 1.1f, 0.5f, 0f, 0.2f, 0.7f, 6f, side)
            bPart(Shape.CUBE, 0f, 0.5f, 2.9f, 2.4f, 0.7f, 0.2f, side)                      // the back
            val bow = bPart(Shape.PYRAMID, 0f, 0.35f, -3.9f, 2.4f, 1.8f, 0.9f, col)       // the pointed front (front is -z)
            bow.rx = -90f
            bPart(Shape.CUBE, 0f, 0.28f, 0f, 2.0f, 0.06f, 5.6f, wood)                      // the deck
            bPart(Shape.CUBE, 0f, 0.5f, -1.5f, 1.3f, 0.45f, 0.35f, dark)                   // the dashboard
            bPart(Shape.CUBE, 0f, 1.0f, -1.7f, 2.0f, 0.7f, 0.06f, floatArrayOf(0.45f, 0.58f, 0.68f)).opacity = 0.12f   // the windscreen
            for (sx in floatArrayOf(-0.5f, 0.5f)) {                                        // the two front seats
                bPart(Shape.CUBE, sx, 0.42f, -0.9f, 0.5f, 0.1f, 0.5f, fabric)
                bPart(Shape.CUBE, sx, 0.72f, -0.62f, 0.5f, 0.5f, 0.1f, fabric)
            }
            bPart(Shape.CUBE, 0f, 0.42f, 1.0f, 1.6f, 0.1f, 0.5f, fabric)                   // the back bench
            bPart(Shape.CUBE, 0f, 0.72f, 1.28f, 1.6f, 0.5f, 0.1f, fabric)
            bPart(Shape.CUBE, 0f, 0.35f, 3.15f, 0.35f, 0.9f, 0.4f, dark)                   // the outboard motor
            for (p in bparts) Rig.bind(p, body)
            body.ry = yawDeg
        }

        // ---- a runway, a small plane and a tank ----
        val strips = ArrayList<FloatArray>()      // x, z, half width, half depth of flat places kept free of trees
        fun buildRunway(x: Float, z: Float, level: Float) {
            val asphalt = floatArrayOf(0.20f, 0.20f, 0.21f)
            val white = floatArrayOf(0.95f, 0.95f, 0.92f)
            val rw = mk(scene, Shape.CUBE, "Runway", x, level + 0.06f, z, 14f, 0.12f, 116f, asphalt)
            rw.shadow = false
            for (i in -4..4) {
                val m = mk(scene, Shape.CUBE, "Runway mark", x, level + 0.125f, z + i * 11f, 0.5f, 0.02f, 5f, white)
                m.shadow = false
            }
            for (sx in floatArrayOf(-6.4f, 6.4f)) {
                val m = mk(scene, Shape.CUBE, "Runway mark", x + sx, level + 0.125f, z, 0.3f, 0.02f, 112f, white)
                m.shadow = false
            }
            for (ez in floatArrayOf(-52f, 52f)) for (k in 0..6) {
                val m = mk(scene, Shape.CUBE, "Runway mark", x - 4.5f + k * 1.5f, level + 0.125f, z + ez, 0.6f, 0.02f, 4f, white)
                m.shadow = false
            }
        }

        var planeN = 0
        fun buildPlane(x: Float, z: Float, yawDeg: Float, level: Float) {
            planeN++
            val cols = arrayOf(floatArrayOf(0.92f, 0.92f, 0.90f), floatArrayOf(0.85f, 0.25f, 0.15f), floatArrayOf(0.15f, 0.35f, 0.75f), floatArrayOf(0.90f, 0.75f, 0.15f))
            val col = cols[rnd.nextInt(cols.size)]
            val dark = floatArrayOf(0.07f, 0.07f, 0.08f)
            val fabric = floatArrayOf(0.35f, 0.33f, 0.30f)
            val trim = floatArrayOf(col[0] * 0.75f, col[1] * 0.75f, col[2] * 0.75f)
            val y = level + 0.12f + 1.1f
            val body = mk(scene, Shape.CUBE, "Plane " + planeN, x, y, z, 0.9f, 0.9f, 7f, col, BR_PLANE)
            body.collider = 0
            val pparts = ArrayList<SceneObject>()
            fun pPart(shape: Shape, dx: Float, dy: Float, dz: Float, psx: Float, psy: Float, psz: Float, c: FloatArray): SceneObject {
                val p = if (shape == Shape.CUBE) mk(scene, Shape.CUBE, "Plane " + planeN + " part", x + dx, y + dy, z + dz, psx, psy, psz, c)
                else { val o = scene.add(shape, null, "Plane " + planeN + " part"); o.x = x + dx; o.y = y + dy; o.z = z + dz; o.sx = psx; o.sy = psy; o.sz = psz; o.color = c; o }
                p.shadow = false
                pparts.add(p)
                return p
            }
            pPart(Shape.CUBE, 0f, -0.05f, -0.2f, 9f, 0.14f, 1.7f, col)                          // the wing
            pPart(Shape.CUBE, -4.2f, -0.05f, -0.2f, 0.6f, 0.15f, 1.72f, trim)                   // wing tips
            pPart(Shape.CUBE, 4.2f, -0.05f, -0.2f, 0.6f, 0.15f, 1.72f, trim)
            pPart(Shape.CUBE, 0f, 0.1f, 4.15f, 0.55f, 0.6f, 1.9f, col)                          // the tail
            pPart(Shape.CUBE, 0f, 0.95f, 4.3f, 0.1f, 1.3f, 1.1f, trim)                          // the fin
            pPart(Shape.CUBE, 0f, 0.3f, 4.5f, 3.4f, 0.1f, 0.9f, col)                            // the tail plane
            pPart(Shape.CUBE, 0f, 0f, -3.75f, 0.75f, 0.75f, 0.55f, dark)                        // the engine cowl
            pPart(Shape.CUBE, 0f, 0.72f, 0.05f, 0.82f, 0.7f, 2.9f, floatArrayOf(0.45f, 0.58f, 0.68f)).opacity = 0.12f   // the glass cockpit
            pPart(Shape.CUBE, 0f, 0.38f, -0.2f, 0.5f, 0.08f, 0.5f, fabric)                      // the two seats
            pPart(Shape.CUBE, 0f, 0.38f, 1.2f, 0.5f, 0.08f, 0.5f, fabric)
            pPart(Shape.CUBE, 0f, 0.62f, -1.45f, 0.8f, 0.2f, 0.12f, dark)                       // the dashboard
            for (wx in floatArrayOf(-1.1f, 1.1f)) {
                pPart(Shape.CUBE, wx * 0.8f, -0.45f, -0.6f, 0.08f, 0.7f, 0.08f, dark)           // legs
                pPart(Shape.SPHERE, wx, -0.8f, -0.6f, 0.28f, 0.6f, 0.6f, dark)                  // main wheels
            }
            pPart(Shape.SPHERE, 0f, -0.55f, 4.1f, 0.2f, 0.4f, 0.4f, dark)                       // tail wheel
            for (p in pparts) Rig.bind(p, body)
            // the propeller is turned by the script
            val yr = Math.toRadians(yawDeg.toDouble())
            val ycs = Math.cos(yr).toFloat()
            val ysn = Math.sin(yr).toFloat()
            for (k in 1..2) {
                val o = scene.add(Shape.CUBE, null, "Plane " + planeN + " prop" + k)
                o.x = x + (-4.05f) * ysn
                o.y = y
                o.z = z + (-4.05f) * ycs
                o.sx = 2.3f; o.sy = 0.14f; o.sz = 0.05f
                o.color = dark
                o.shadow = false
                o.ry = yawDeg
                o.rz = if (k == 2) 90f else 0f
            }
            body.ry = yawDeg
        }

        var tankN = 0
        fun buildTank(x: Float, z: Float, yawDeg: Float) {
            val g = ground(x, z) ?: return
            if (g < seaY + 0.5f) return
            tankN++
            val cols = arrayOf(floatArrayOf(0.30f, 0.36f, 0.22f), floatArrayOf(0.60f, 0.52f, 0.35f), floatArrayOf(0.38f, 0.40f, 0.40f))
            val col = cols[rnd.nextInt(cols.size)]
            val dark = floatArrayOf(0.10f, 0.10f, 0.11f)
            val steel = floatArrayOf(0.20f, 0.21f, 0.22f)
            val dcol = floatArrayOf(col[0] * 0.8f, col[1] * 0.8f, col[2] * 0.8f)
            val y = g + 0.75f
            val body = mk(scene, Shape.CUBE, "Tank " + tankN, x, y, z, 2.5f, 0.8f, 5.6f, col, BR_TANK)
            body.collider = 0
            val tparts = ArrayList<SceneObject>()
            fun tPart(dx: Float, dy: Float, dz: Float, psx: Float, psy: Float, psz: Float, c: FloatArray): SceneObject {
                val p = mk(scene, Shape.CUBE, "Tank " + tankN + " part", x + dx, y + dy, z + dz, psx, psy, psz, c)
                p.shadow = false
                tparts.add(p)
                return p
            }
            for (sx in floatArrayOf(-1.65f, 1.65f)) {
                tPart(sx, -0.2f, 0f, 0.8f, 0.9f, 6.0f, dark)                                    // the tracks
                tPart(sx, 0.32f, 0f, 0.95f, 0.08f, 6.3f, dcol)                                  // the mudguards
            }
            tPart(0f, 0.33f, -2.75f, 2.5f, 0.1f, 1.2f, col).rx = -20f                           // the sloped front
            tPart(0f, 0.5f, 2.0f, 2.3f, 0.25f, 1.6f, dcol)                                      // the engine deck at the back
            for (lx in floatArrayOf(-0.9f, 0.9f)) tPart(lx, 0.2f, -2.83f, 0.25f, 0.18f, 0.06f, floatArrayOf(1f, 0.95f, 0.7f)).emit = 0.8f
            for (p in tparts) Rig.bind(p, body)
            // the turret and the cannon are turned by the script; the small parts on the turret follow it
            val yr = Math.toRadians(yawDeg.toDouble())
            val ycs = Math.cos(yr).toFloat()
            val ysn = Math.sin(yr).toFloat()
            val ty0 = y + 0.75f
            val tz0 = z + 0.2f
            val turret = mk(scene, Shape.CUBE, "Tank " + tankN + " turret", x, ty0, tz0, 2.0f, 0.7f, 2.4f, col)
            turret.shadow = false
            val tkids = ArrayList<SceneObject>()
            fun tKid(dx: Float, dy: Float, dz: Float, psx: Float, psy: Float, psz: Float, c: FloatArray) {
                val p = mk(scene, Shape.CUBE, "Tank " + tankN + " tpart", x + dx, ty0 + dy, tz0 + dz, psx, psy, psz, c)
                p.shadow = false
                tkids.add(p)
            }
            tKid(0f, 0.05f, -1.25f, 1.0f, 0.6f, 0.4f, steel)                                    // the gun mantlet
            tKid(0.5f, 0.4f, 0.3f, 0.6f, 0.12f, 0.6f, dark)                                     // the commander's hatch
            tKid(-0.75f, 1.0f, 0.9f, 0.04f, 1.5f, 0.04f, dark)                                  // the aerial
            for (k in tkids) Rig.bind(k, turret)
            turret.x = x + 0.2f * ysn
            turret.z = z + 0.2f * ycs
            turret.ry = yawDeg
            val barrel = mk(scene, Shape.CUBE, "Tank " + tankN + " barrel", x + (-2.7f) * ysn, ty0 + 0.05f, z + (-2.7f) * ycs, 0.2f, 0.2f, 3.6f, steel)
            barrel.shadow = false
            barrel.ry = yawDeg
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
            // motorcycles beside the road
            val nBikes = if (mega) 5 else if (giant) 4 else 3
            var bikeTries = 0
            var bikesDone = 0
            while (bikesDone < nBikes && bikeTries++ < 300 && roadPieces.isNotEmpty()) {
                val rp = roadPieces[rnd.nextInt(roadPieces.size)]
                val rr = Math.toRadians(rp[2].toDouble())
                val side = if (rnd.nextBoolean()) 1f else -1f
                val bx2 = rp[0] + Math.cos(rr).toFloat() * 2.6f * side
                val bz2 = rp[1] - Math.sin(rr).toFloat() * 2.6f * side
                if (carSpots.any { Math.hypot((it[0] - bx2).toDouble(), (it[1] - bz2).toDouble()) < 14.0 }) continue
                carSpots.add(floatArrayOf(bx2, bz2))
                buildBike(bx2, bz2, rp[2] + (if (rnd.nextBoolean()) 0f else 180f))
                bikesDone++
            }
            // helicopters on levelled pads, away from the roads and the houses
            val nHelis = if (mega) 3 else if (giant) 2 else 1
            val heliSpots = ArrayList<FloatArray>()
            var heliTries = 0
            while (heliSpots.size < nHelis && heliTries++ < 600) {
                val hx = (rnd.nextFloat() - 0.5f) * 0.75f * map
                val hz = (rnd.nextFloat() - 0.5f) * 0.75f * map
                val g0 = ground(hx, hz) ?: continue
                if (g0 < seaY + 1.5f) continue
                var lo2 = g0
                var hi2 = g0
                var ok2 = true
                for (q in 0 until 8) {
                    val qa = q * Math.PI / 4.0
                    val gq = ground(hx + Math.cos(qa).toFloat() * 9f, hz + Math.sin(qa).toFloat() * 9f)
                    if (gq == null || gq < seaY + 1.0f) { ok2 = false; break }
                    lo2 = minOf(lo2, gq); hi2 = maxOf(hi2, gq)
                }
                if (!ok2 || hi2 - lo2 > 3.0f) continue
                if (nearRoad(hx, hz, 16f)) continue
                if (houses.any { Math.abs(it[0] - hx) < it[5] / 2f + 20f && Math.abs(it[1] - hz) < it[6] / 2f + 20f }) continue
                if (heliSpots.any { Math.hypot((it[0] - hx).toDouble(), (it[1] - hz).toDouble()) < 120.0 }) continue
                val lvl = (lo2 + hi2) / 2f
                flattenPad(hx, hz, 7f, 7f, lvl)
                val lg = ground(hx, hz) ?: lvl
                heliSpots.add(floatArrayOf(hx, hz))
                buildHelipad(hx, hz, lg)
                buildHeli(hx, hz, rnd.nextFloat() * 360f, lg)
            }
            // boats on the water a little way off the beach, lying along the shore
            val nBoats = if (mega) 4 else if (giant) 3 else if (huge) 2 else 1
            val boatSpots = ArrayList<FloatArray>()
            var boatTries = 0
            fun isLand(x: Float, z: Float): Boolean = (ground(x, z) ?: -1000f) >= seaY + 0.5f
            fun isDeep(x: Float, z: Float): Boolean = (ground(x, z) ?: -1000f) <= seaY - 0.8f
            while (boatSpots.size < nBoats && boatTries++ < 500) {
                val ang = rnd.nextFloat() * 2.0 * Math.PI
                val ca = Math.cos(ang).toFloat()
                val sa = Math.sin(ang).toFloat()
                // walk out from the middle of the map until the land ends: that is the shore
                var rr = 0.1f * map
                var wasLand = false
                var shoreR = -1f
                while (rr < 0.75f * map) {
                    val gx = ca * rr
                    val gz = sa * rr
                    if (isLand(gx, gz)) wasLand = true
                    else if (wasLand && isDeep(gx, gz)) { shoreR = rr; break }
                    rr += 5f
                }
                if (shoreR < 0f) continue
                var spot: FloatArray? = null
                var dd = 7f
                while (dd < 45f && spot == null) {
                    val bx = ca * (shoreR + dd)
                    val bz = sa * (shoreR + dd)
                    var deepAll = isDeep(bx, bz)
                    for (q in 0 until 8) {
                        if (!deepAll) break
                        val qa = q * Math.PI / 4.0
                        if (!isDeep(bx + Math.cos(qa).toFloat() * 5f, bz + Math.sin(qa).toFloat() * 5f)) deepAll = false
                    }
                    if (deepAll) spot = floatArrayOf(bx, bz)
                    dd += 4f
                }
                val sp = spot ?: continue
                if (boatSpots.any { Math.hypot((it[0] - sp[0]).toDouble(), (it[1] - sp[1]).toDouble()) < 150.0 }) continue
                boatSpots.add(sp)
                buildBoat(sp[0], sp[1], (ang * 180.0 / Math.PI).toFloat() + 90f)
            }
            // runways with a small plane on every large map (not on the smallest "large" one: it is too small for a runway)
            val nPlanes = if (mega) 2 else if (giant) 1 else if (huge) 1 else 0
            val planeSpots = ArrayList<FloatArray>()
            var planeTries = 0
            while (planeSpots.size < nPlanes && planeTries++ < 800) {
                val px = (rnd.nextFloat() - 0.5f) * 0.7f * map
                val pz = (rnd.nextFloat() - 0.5f) * 0.7f * map
                var lo3 = 1e9f
                var hi3 = -1e9f
                var sum3 = 0f
                var cnt3 = 0
                var ok3 = true
                for (ix in intArrayOf(-12, 0, 12)) for (iz in intArrayOf(-66, -44, -22, 0, 22, 44, 66)) {
                    val gq = ground(px + ix, pz + iz)
                    if (gq == null || gq < seaY + 1.2f) { ok3 = false } else { lo3 = minOf(lo3, gq); hi3 = maxOf(hi3, gq); sum3 += gq; cnt3++ }
                }
                if (!ok3 || cnt3 == 0 || hi3 - lo3 > 5.0f) continue
                var roadNear = false
                for (iz in intArrayOf(-66, -33, 0, 33, 66)) if (nearRoad(px, pz + iz, 16f)) roadNear = true
                if (roadNear) continue
                if (houses.any { Math.abs(it[0] - px) < it[5] / 2f + 24f && Math.abs(it[1] - pz) < it[6] / 2f + 72f }) continue
                if (heliSpots.any { Math.abs(it[0] - px) < 30.0 && Math.abs(it[1] - pz) < 80.0 }) continue
                if (planeSpots.any { Math.hypot((it[0] - px).toDouble(), (it[1] - pz).toDouble()) < 150.0 }) continue
                val lvl = sum3 / cnt3
                flattenPad(px, pz, 8f, 60f, lvl)
                val lg = ground(px, pz) ?: lvl
                planeSpots.add(floatArrayOf(px, pz))
                strips.add(floatArrayOf(px, pz, 14f, 66f))
                buildRunway(px, pz, lg)
                val north = rnd.nextBoolean()
                buildPlane(px, if (north) pz - 50f else pz + 50f, if (north) 180f else 0f, lg)
            }
            // tanks standing on open, level ground
            val nTanks = if (mega) 3 else if (giant) 2 else 1
            val tankSpots = ArrayList<FloatArray>()
            var tankTries = 0
            while (tankSpots.size < nTanks && tankTries++ < 800) {
                val kx = (rnd.nextFloat() - 0.5f) * 0.7f * map
                val kz = (rnd.nextFloat() - 0.5f) * 0.7f * map
                val g0 = ground(kx, kz) ?: continue
                if (g0 < seaY + 1.5f) continue
                var flat = true
                for (q in 0 until 8) {
                    val qa = q * Math.PI / 4.0
                    val gq = ground(kx + Math.cos(qa).toFloat() * 5f, kz + Math.sin(qa).toFloat() * 5f)
                    if (gq == null || gq < seaY + 1.0f || Math.abs(gq - g0) > 1.5f) { flat = false; break }
                }
                if (!flat) continue
                if (nearRoad(kx, kz, 10f)) continue
                if (houses.any { Math.hypot((it[0] - kx).toDouble(), (it[1] - kz).toDouble()) < 0.5 * maxOf(it[5], it[6]) + 14.0 }) continue
                if (heliSpots.any { Math.hypot((it[0] - kx).toDouble(), (it[1] - kz).toDouble()) < 25.0 }) continue
                if (planeSpots.any { Math.abs(it[0] - kx) < 30.0 && Math.abs(it[1] - kz) < 80.0 }) continue
                if (carSpots.any { Math.hypot((it[0] - kx).toDouble(), (it[1] - kz).toDouble()) < 12.0 }) continue
                if (tankSpots.any { Math.hypot((it[0] - kx).toDouble(), (it[1] - kz).toDouble()) < 80.0 }) continue
                tankSpots.add(floatArrayOf(kx, kz))
                strips.add(floatArrayOf(kx, kz, 6f, 6f))
                buildTank(kx, kz, rnd.nextFloat() * 360f)
            }
        }

        // ---- trees (away from houses and water) ----
        var trees = 0
        val treeLua = StringBuilder()   // the tree trunks for the player script: x, z, ground y, trunk radius, trunk height
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
            if (strips.any { Math.abs(it[0] - tx) < it[2] && Math.abs(it[1] - tz) < it[3] }) continue
            val ttype = rnd.nextInt(2)
            val tr = scene.addTree(ttype)
            val k = 0.8f + rnd.nextFloat() * 0.6f
            tr.x = tx; tr.z = tz; tr.y = th - 0.05f
            tr.sx = 2f * k; tr.sy = 3.4f * k; tr.sz = 2f * k
            tr.ry = rnd.nextFloat() * 360f
            trees++
            val trR = (if (ttype == 1) 0.06f else 0.09f) * tr.sx + 0.06f
            val trH = (if (ttype == 1) 0.9f else 0.6f) * tr.sy
            treeLua.append(String.format(java.util.Locale.US, "{%.1f,%.1f,%.2f,%.2f,%.2f},", tx, tz, tr.y, trR, trH))
        }

        // ---- loot: one medkit and one ammo box in every house, plus some outside ----
        var medN = 0
        var ammoN = 0
        fun medkit(x: Float, y: Float, z: Float) {
            medN++
            val o = mk(scene, Shape.CUBE, "Medkit " + medN, x, y, z, 0.5f, 0.5f, 0.5f, floatArrayOf(0.9f, 0.15f, 0.15f), BR_MEDKIT)
            o.collider = 2; o.emit = 0.35f; o.shadow = false
        }
        // four kinds of ammo (colour coded): 5.56 yellow, 9mm orange, 12 gauge red, 7.62 blue
        val ammoField = arrayOf("addR556", "addR9", "addG12", "addR762")
        val ammoAmt = intArrayOf(30, 24, 6, 8)
        val ammoCol = arrayOf(floatArrayOf(0.95f, 0.8f, 0.15f), floatArrayOf(0.95f, 0.5f, 0.1f), floatArrayOf(0.85f, 0.2f, 0.2f), floatArrayOf(0.25f, 0.45f, 0.95f))
        val ammoPick = intArrayOf(0, 0, 0, 1, 1, 2, 3)
        fun ammo(x: Float, y: Float, z: Float) {
            ammoN++
            val t = ammoPick[rnd.nextInt(ammoPick.size)]
            val o = mk(scene, Shape.CUBE, "Ammo " + ammoN, x, y, z, 0.5f, 0.35f, 0.6f, ammoCol[t],
                BR_AMMO.replace("%F%", ammoField[t]).replace("%N%", ammoAmt[t].toString()))
            o.collider = 2; o.emit = 0.35f; o.shadow = false
        }
        // weapons lie around as long flat shapes in the colour of the gun
        val weaponKeys = arrayOf("rifle", "rifle", "rifle", "rifle", "smg", "smg", "smg", "shotgun", "shotgun", "pistol", "pistol", "sniper")
        val weaponLen = mapOf("rifle" to 1.0f, "smg" to 0.7f, "pistol" to 0.4f, "shotgun" to 0.95f, "sniper" to 1.3f)
        val weaponCol = mapOf(
            "rifle" to floatArrayOf(0.25f, 0.3f, 0.25f), "smg" to floatArrayOf(0.3f, 0.3f, 0.38f), "pistol" to floatArrayOf(0.35f, 0.35f, 0.35f),
            "shotgun" to floatArrayOf(0.45f, 0.28f, 0.15f), "sniper" to floatArrayOf(0.2f, 0.34f, 0.15f)
        )
        var weapN = 0
        fun weaponBox(x: Float, y: Float, z: Float, key: String) {
            weapN++
            val o = mk(scene, Shape.CUBE, "Weapon " + weapN, x, y, z, 0.14f, 0.16f, weaponLen[key] ?: 0.8f, weaponCol[key] ?: floatArrayOf(0.3f, 0.3f, 0.3f),
                BR_WEAPON.replace("%W%", key))
            o.collider = 2; o.emit = 0.45f; o.shadow = false
            o.ry = rnd.nextFloat() * 360f
        }
        fun randomWeapon(x: Float, y: Float, z: Float) = weaponBox(x, y, z, weaponKeys[rnd.nextInt(weaponKeys.size)])
        var grenN = 0
        fun grenadePack(x: Float, y: Float, z: Float) {
            grenN++
            val o = mk(scene, Shape.SPHERE, "Grenades " + grenN, x, y, z, 0.32f, 0.32f, 0.32f, floatArrayOf(0.22f, 0.4f, 0.16f), BR_GRENADE)
            o.collider = 2; o.emit = 0.4f; o.shadow = false
        }
        for (l in houseLoot) {
            when (l[3].toInt()) {
                0 -> medkit(l[0], l[1], l[2])
                1 -> ammo(l[0], l[1], l[2])
                2 -> randomWeapon(l[0], l[1], l[2])
                else -> grenadePack(l[0], l[1], l[2])
            }
        }
        for (t in towerSpots) {
            medkit(t[0] - 1.6f, t[2] + 10f + 0.5f, t[1] - 1.6f)
            ammo(t[0] + 1.6f, t[2] + 10f + 0.5f, t[1] - 1.6f)
            weaponBox(t[0], t[2] + 10f + 0.5f, t[1] + 1.6f, "sniper")
        }
        var extra = 0
        tries = 0
        while (extra < nExtraLoot && tries < 600) {
            tries++
            val x = (rnd.nextFloat() - 0.5f) * 0.8f * map
            val z = (rnd.nextFloat() - 0.5f) * 0.8f * map
            val h = ground(x, z) ?: continue
            if (h < seaY + 0.5f) continue
            when (extra % 4) {
                0 -> medkit(x, h + 0.5f, z)
                1 -> ammo(x, h + 0.5f, z)
                2 -> randomWeapon(x, h + 0.5f, z)
                else -> grenadePack(x, h + 0.5f, z)
            }
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
            scene, "Player", px, pg + 1.2f, pz, playerScriptBase.replace("STAIRS = {}", "STAIRS = " + stairLua).replace("TREES = {}", "TREES = {" + treeLua + "}"),
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
        scene.addButton()?.let { it.name = "Door"; it.x = 58f; it.y = 30f; it.sx = 0.9f }
        scene.addButton()?.let { it.name = "Climb"; it.x = 90f; it.y = 14f; it.sx = 0.9f }
        scene.addButton()?.let { it.name = "Descend"; it.x = 75f; it.y = 16f; it.sx = 0.9f }
        scene.addButton()?.let { it.name = "Weapon"; it.x = 46f; it.y = 66f; it.sx = 0.9f }
        scene.addButton()?.let { it.name = "Aim"; it.x = 76f; it.y = 48f; it.sx = 0.9f }
        scene.addButton()?.let { it.name = "Grenade"; it.x = 92f; it.y = 70f; it.sx = 0.9f }
        scene.addButton()?.let { it.name = "Heal"; it.x = 46f; it.y = 80f; it.sx = 0.8f }
        scene.addButton()?.let { it.name = "Bag"; it.x = 62f; it.y = 86f; it.sx = 0.8f }
        Rig.apply(scene)
        scene.selected = null
    }
}
