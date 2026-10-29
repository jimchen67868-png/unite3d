package com.example.minieditor

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Locale
import kotlin.math.sin
import kotlin.math.sqrt
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class MainActivity : Activity(), SensorEventListener {
    private enum class Panel { NONE, HIER, INSPECT, SCRIPT }
    private class Snap(val o: SceneObject, val v: FloatArray)

    private val scene = Scene()
    private lateinit var editor: EditorView
    private lateinit var root: FrameLayout
    private lateinit var info: TextView
    private lateinit var consoleView: TextView
    private val modeButtons = HashMap<Mode, Button>()
    private lateinit var yButton: Button
    private lateinit var playButton: Button
    private lateinit var joystick: JoystickView
    private lateinit var colliderBtn: Button
    private lateinit var skyRow: LinearLayout
    private lateinit var objSwatches: LinearLayout
    private lateinit var settingsHint: TextView
    private val buttonViews = ArrayList<JumpButtonView>()
    private var btnJump = false
    private var btnJumpPressed = false
    private var btnHeld: Map<String, Boolean> = emptyMap()
    private var btnPressed: Map<String, Boolean> = emptyMap()
    private lateinit var viewButton: Button
    private lateinit var cameraButton: Button
    private var fpTarget: SceneObject? = null
    private var camTargetName = "Player"
    private var savedPitch = 30f
    private var savedDist = 9f
    private lateinit var fileButton: Button

    private lateinit var topPanel: LinearLayout
    private lateinit var hierarchyView: LinearLayout
    private lateinit var hierList: LinearLayout
    private lateinit var inspectorView: LinearLayout
    private lateinit var scriptView: LinearLayout
    private lateinit var scriptField: EditText
    private lateinit var nameField: EditText
    private val fields = ArrayList<EditText>()
    private val rowLabels = ArrayList<TextView>()
    private var settingScript = false

    private var panel = Panel.NONE
    private var lastCount = -1
    private var lastSel: SceneObject? = null
    private val modelCache = HashMap<String, ModelData>()
    private val reqImport = 42
    private val reqExport = 43
    private val reqOpen = 44
    private val reqObj = 45

    // play mode
    private var playing = false
    private var engine: ScriptEngine? = null
    private var snaps: List<Snap> = emptyList()
    private var lastFrame = 0L
    private val logLines = ArrayList<String>()
    private var ax = 0f
    private var ay = 0f
    private var az = 0f

    private val skyColors = intArrayOf(
        0xFF0D0F1F.toInt(), 0xFF59406B.toInt(), 0xFF87CEEB.toInt(),
        0xFF9AA5B3.toInt(), 0xFF000000.toInt(), 0xFFFFFFFF.toInt()
    )

    private val swatchColors = intArrayOf(
        0xFFE65A4D.toInt(), 0xFF4DB373.toInt(), 0xFF4D8CE6.toInt(),
        0xFFF2BF40.toInt(), 0xFFB373D9.toInt(), 0xFFBFBFBF.toInt(),
        0xFFFFFFFF.toInt(), 0xFFFFE6B3.toInt()
    )

    private val scriptTemplate = """-- Runs once when Play starts
function start()
  speed = 90
end

-- Runs every frame. dt = seconds since last frame
function update(dt)
  self.ry = self.ry + speed * dt
  self.y = 0.5 + math.sin(time * 2) * 0.3
  if input.touching then
    self.r = input.x
  end
end
"""

    private val tiltTemplate = """-- Hold the phone flat and tilt it to roll this object
function update(dt)
  self.x = self.x + input.moveX * 4 * dt
  self.z = self.z + input.moveZ * 4 * dt
end
"""

    private val spawnTemplate = """-- Drops a sphere every second and removes it after 3 seconds
function start()
  timer = 0
  balls = {}
end

function update(dt)
  timer = timer + dt
  if timer > 1 then
    timer = 0
    local b = spawn("sphere", math.random(-3, 3), 4, 0)
    b.sx = 0.5; b.sy = 0.5; b.sz = 0.5
    b.age = 0
    b.vy = 0
    table.insert(balls, b)
  end
  for i = #balls, 1, -1 do
    local b = balls[i]
    b.age = b.age + dt
    b.vy = b.vy - 9.8 * dt
    b.y = math.max(0.25, b.y + b.vy * dt)
    if b.age > 3 then
      destroy(b)
      table.remove(balls, i)
    end
  end
end
"""

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ---------- UI helpers ----------

    private fun btn(text: String, structural: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(Color.WHITE)
        setBackgroundColor(0xFF444444.toInt())
        minWidth = 0; minHeight = 0
        setPadding(dp(4), dp(8), dp(4), dp(8))
        layoutParams = LinearLayout.LayoutParams(0, -2, 1f).also { it.setMargins(dp(2), dp(2), dp(2), dp(2)) }
        setOnClickListener { if (structural && playing) toast("Stop play mode first") else onClick() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        editor = EditorView(this, scene)
        editor.onTouchStart = { clearFieldFocus() }

        info = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 12f
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        consoleView = TextView(this).apply {
            setTextColor(0xFF9BE59B.toInt()); textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(dp(8), dp(2), dp(8), dp(2))
            visibility = View.GONE
        }
        modeButtons[Mode.MOVE] = btn("Move") { setMode(Mode.MOVE) }
        modeButtons[Mode.ROTATE] = btn("Rotate") { setMode(Mode.ROTATE) }
        modeButtons[Mode.SCALE] = btn("Scale") { setMode(Mode.SCALE) }
        yButton = btn("Axis: Free") { editor.axis = (editor.axis + 1) % 4; refreshButtons() }
        fileButton = btn("File") { showFileMenu() }
        playButton = btn("Play") { togglePlay() }
        playButton.setBackgroundColor(0xFF2E8B57.toInt())
        viewButton = btn("1st person") { toggleFirstPerson() }
        cameraButton = btn("Camera") { showCameraMenu() }

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xAA000000.toInt())
            addView(consoleView)
            addView(info)
            addView(row(
                btn("+Cube", true) { scene.add(Shape.CUBE); editor.refresh() },
                btn("+Pyramid", true) { scene.add(Shape.PYRAMID); editor.refresh() },
                btn("+Sphere", true) { scene.add(Shape.SPHERE); editor.refresh() },
                btn("Import", true) { pickModel() }
            ))
            addView(row(modeButtons[Mode.MOVE]!!, modeButtons[Mode.ROTATE]!!, modeButtons[Mode.SCALE]!!, yButton))
            addView(row(
                btn("Hierarchy") { setPanel(Panel.HIER) },
                btn("Inspector") { setPanel(Panel.INSPECT) },
                btn("Script") { setPanel(Panel.SCRIPT) },
                fileButton
            ))
            addView(row(
                playButton,
                viewButton,
                cameraButton,
                btn("Clear log") { logLines.clear(); showLog() }
            ))
        }

        buildHierarchy()
        buildInspector()
        buildScript()
        topPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xCC000000.toInt())
            visibility = View.GONE
            addView(hierarchyView)
            addView(inspectorView)
            addView(scriptView)
        }

        root = FrameLayout(this).apply { isFocusable = true; isFocusableInTouchMode = true }
        root.addView(editor, FrameLayout.LayoutParams(-1, -1))
        root.addView(topPanel, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        joystick = JoystickView(this).apply { visibility = View.GONE }
        root.addView(joystick, FrameLayout.LayoutParams(dp(150), dp(150), Gravity.TOP or Gravity.START))
        for (i in 0 until 4) {
            val v = JumpButtonView(this).apply { visibility = View.GONE }
            buttonViews.add(v)
            root.addView(v, FrameLayout.LayoutParams(dp(90), dp(90), Gravity.TOP or Gravity.START))
        }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> refreshJoystick() }
        setContentView(root)

        editor.onChange = { updateInfo() }
        if (!autoLoad()) scene.add(Shape.CUBE)
        setMode(Mode.MOVE)
    }

    // ---------- File menu ----------

    private fun showFileMenu() {
        val pm = PopupMenu(this, fileButton)
        pm.menu.add(0, 1, 0, "Save slot (internal storage)")
        pm.menu.add(0, 2, 1, "Load slot (internal storage)")
        pm.menu.add(0, 3, 2, "Export scene zip...")
        pm.menu.add(0, 4, 3, "Open scene zip...")
        pm.menu.add(0, 5, 4, "Export OBJ...")
        pm.menu.add(0, 6, 5, "New scene")
        pm.menu.add(0, 7, 6, "Load sample game (Coin Rush)")
        pm.menu.add(0, 8, 7, "Editor lighting: " + (if (editor.renderer.editorBright) "Bright (tap for Scene)" else "Scene (tap for Bright)"))
        pm.setOnMenuItemClickListener { item ->
            if (playing) {
                toast("Stop play mode first")
            } else {
                when (item.itemId) {
                    1 -> save()
                    2 -> load()
                    3 -> exportProject()
                    4 -> openProject()
                    5 -> exportObj()
                    6 -> { scene.objects.clear(); scene.selected = null; editor.refresh() }
                    7 -> loadSample()
                    8 -> { editor.renderer.editorBright = !editor.renderer.editorBright; editor.refresh() }
                }
            }
            true
        }
        pm.show()
    }

    private fun loadSample() {
        val go = {
            Samples.coinRush(scene)
            editor.renderer.dist = 14f
            editor.renderer.pitch = 55f
            editor.refresh()
            toast("Coin Rush loaded - tap Play")
        }
        if (scene.objects.isEmpty()) {
            go()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Load sample game?")
                .setMessage("This replaces the current scene. Use File > Export scene zip first if you want to keep it.")
                .setPositiveButton("Load") { _, _ -> go() }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    // ---------- Play mode ----------

    private val frameCb = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!playing) return
            val dt = if (lastFrame == 0L) 0.016f
            else ((frameTimeNanos - lastFrame) / 1_000_000_000f).coerceAtMost(0.1f)
            lastFrame = frameTimeNanos
            readButtons()
            engine?.let {
                it.ax = ax; it.ay = ay; it.az = az
                it.camYaw = editor.renderer.yaw
                it.joyX = joystick.jx; it.joyY = joystick.jy; it.joyActive = joystick.active
                it.jump = btnJump
                it.jumpPressed = btnJumpPressed
                it.buttonsHeld = btnHeld
                it.buttonsPressed = btnPressed
                it.touching = editor.touching
                it.touchX = editor.touchX / maxOf(editor.width, 1)
                it.touchY = editor.touchY / maxOf(editor.height, 1)
                it.camPitch = editor.renderer.pitch
                it.fpOn = editor.renderer.fpMode
                it.camTargetName = camTargetName
                it.joyTap = joystick.consumeTap()
                it.update(dt)
                camTargetName = it.camTargetName
                it.fpRequest?.let { want ->
                    it.fpRequest = null
                    if (want != editor.renderer.fpMode) setFirstPerson(want)
                }
                it.yawOut?.let { yv ->
                    editor.renderer.yaw = yv
                    it.yawOut = null
                }
                it.pitchOut?.let { pv ->
                    val fp = editor.renderer.fpMode
                    editor.renderer.pitch = pv.coerceIn(if (fp) -80f else 5f, if (fp) 80f else 85f)
                    it.pitchOut = null
                }
            }
            if (editor.renderer.fpMode) {
                val t = fpTarget
                if (t == null || !scene.objects.contains(t)) {
                    setFirstPerson(false)
                } else {
                    val want = findCamTarget()
                    if (want != null && want !== t) {
                        editor.renderer.fpHide = want
                        fpTarget = want
                    }
                    updateFpEye()
                }
            }
            editor.refresh()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /** Reads the on-screen buttons. A button named "Jump" also feeds input.jump / input.jumpPressed. */
    private fun readButtons() {
        val bs = scene.objects.filter { o -> o.shape == Shape.BUTTON }.take(4)
        val held = HashMap<String, Boolean>()
        val pressed = HashMap<String, Boolean>()
        var j = false
        var jp = false
        for ((i, b) in bs.withIndex()) {
            val v = buttonViews[i]
            val down = v.pressedNow
            val press = v.consumePress()
            held[b.name] = down
            pressed[b.name] = press
            if (b.name.startsWith("jump", true)) { j = down; jp = press }
        }
        btnJump = j
        btnJumpPressed = jp
        btnHeld = held
        btnPressed = pressed
    }

    private fun appendLog(msg: String) {
        logLines.add(msg)
        while (logLines.size > 5) logLines.removeAt(0)
        showLog()
    }

    private fun showLog() {
        consoleView.text = logLines.joinToString("\n")
        consoleView.visibility = if (logLines.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun togglePlay() { if (playing) stopPlay() else startPlay() }

    private fun startPlay() {
        clearFieldFocus()
        snaps = scene.objects.map {
            Snap(it, floatArrayOf(it.x, it.y, it.z, it.rx, it.ry, it.rz, it.sx, it.sy, it.sz,
                it.color[0], it.color[1], it.color[2], it.collider.toFloat()))
        }
        logLines.clear(); showLog()
        playing = true
        editor.playing = true
        joystick.reset()
        buttonViews.forEach { it.reset() }
        playButton.text = "Stop"
        playButton.setBackgroundColor(0xFFC0392B.toInt())
        try {
            val eng = ScriptEngine(scene) { appendLog(it) }
            engine = eng
            eng.start()
        } catch (e: Throwable) {
            appendLog("Script error: " + e.message)
        }
        val sm = getSystemService(SENSOR_SERVICE) as SensorManager
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        lastFrame = 0L
        Choreographer.getInstance().postFrameCallback(frameCb)
        updateInfo()
    }

    private fun stopPlay() {
        if (!playing) return
        playing = false
        editor.playing = false
        joystick.reset()
        buttonViews.forEach { it.reset() }
        if (editor.renderer.fpMode) setFirstPerson(false)
        camTargetName = "Player"
        Choreographer.getInstance().removeFrameCallback(frameCb)
        engine = null
        for (s in snaps) {
            val o = s.o; val v = s.v
            o.x = v[0]; o.y = v[1]; o.z = v[2]
            o.rx = v[3]; o.ry = v[4]; o.rz = v[5]
            o.sx = v[6]; o.sy = v[7]; o.sz = v[8]
            o.color = floatArrayOf(v[9], v[10], v[11])
            o.collider = v[12].toInt()
        }
        val orig = snaps.map { it.o }
        if (scene.objects.toList() != orig) {
            scene.objects.clear()
            scene.objects.addAll(orig)
        }
        val sel = scene.selected
        if (sel != null && sel !in orig) scene.selected = null
        (getSystemService(SENSOR_SERVICE) as SensorManager).unregisterListener(this)
        playButton.text = "Play"
        playButton.setBackgroundColor(0xFF2E8B57.toInt())
        editor.refresh()
    }

    // ---------- Script panel ----------

    private fun buildScript() {
        scriptField = EditText(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0xFF1E1E1E.toInt())
            textSize = 12f
            typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(8), dp(6), dp(8), dp(6))
            layoutParams = LinearLayout.LayoutParams(-1, dp(170))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (!settingScript) scene.selected?.script = s?.toString() ?: ""
                }
            })
        }
        val help = TextView(this).apply {
            text = "Lua. Define start() and update(dt).\n" +
                "self.x .y .z .rx .ry .rz .sx .sy .sz .r .g .b | find(\"Name\") | spawn(\"cube\", x, y, z) | destroy(obj) | time, dt\n" +
                "input.touching .x .y (0-1) | input.tiltX .tiltY .moveX .moveZ (tilt) | input.joyX .joyY .joyMoveX .joyMoveZ .joyActive | input.jump .jumpPressed | log(...)\n" +
                "oncollide(other) | self.grounded | self.collider (0 off, 1 solid, 2 trigger) | world.gravity .fog .ambient .timeScale\n" +
                "camera.firstPerson .target .yaw .pitch | input.joyTap (quick tap on the joystick) | input.buttons[\"Name\"] .buttonsPressed[\"Name\"]"
            textSize = 11f
            setTextColor(0xFFAAAAAA.toInt())
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        scriptView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(help)
            addView(scriptField)
            addView(row(
                btn("Spin") { setScript(scriptTemplate) },
                btn("Tilt") { setScript(tiltTemplate) },
                btn("Spawn") { setScript(spawnTemplate) },
                btn("Clear") { setScript("") }
            ))
        }
    }

    private fun setScript(src: String) {
        val o = scene.selected
        if (o == null) { toast("Select an object first"); return }
        o.script = src
        settingScript = true
        scriptField.setText(src)
        settingScript = false
        updateInfo()
    }

    private fun refreshScript() {
        val o = scene.selected
        scriptField.isEnabled = o != null
        scriptField.hint = if (o == null) "Select an object to edit its script" else "-- Lua script for " + o.name
        val want = o?.script ?: ""
        if (!scriptField.hasFocus() && scriptField.text.toString() != want) {
            settingScript = true
            scriptField.setText(want)
            settingScript = false
        }
    }

    // ---------- Hierarchy ----------

    private fun buildHierarchy() {
        hierList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            addView(hierList)
            layoutParams = LinearLayout.LayoutParams(-1, dp(180))
        }
        hierarchyView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(row(
                btn("+Stick", true) { if (scene.addJoystick() == null) toast("Joystick already added") else editor.refresh() },
                btn("+Button", true) { if (scene.addButton() == null) toast("Max 4 buttons") else editor.refresh() },
                btn("Copy", true) { scene.duplicate(); editor.refresh() },
                btn("Delete", true) { scene.removeSelected(); editor.refresh() },
                btn("Clear", true) { scene.objects.clear(); scene.selected = null; editor.refresh() }
            ))
            addView(row(
                btn("+Light", true) { if (scene.addLight(Shape.LIGHT) == null) toast("Max 4 point lights") else editor.refresh() },
                btn("+Sun", true) { if (scene.addLight(Shape.SUN) == null) toast("Sun already added") else editor.refresh() },
                btn("+Settings", true) { if (scene.addSettings() == null) toast("Settings already added") else editor.refresh() },
                btn("+Block", true) { scene.addBlock(); editor.refresh() }
            ))
            addView(scroll)
        }
    }

    private fun rebuildHierarchy() {
        hierList.removeAllViews()
        for (o in scene.objects) {
            val sel = o === scene.selected
            hierList.addView(TextView(this).apply {
                text = (if (sel) "▶ " else "    ") + o.name + (if (o.script.isNotBlank()) "  {lua}" else "") + (if (o.shape.isScreen) "  [screen]" else "") + (if (o.shape.isLight) "  [light]" else "") + (if (o.shape == Shape.SETTINGS) "  [settings]" else "")
                textSize = 14f
                setTextColor(if (sel) 0xFF7FB0FF.toInt() else Color.WHITE)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                setOnClickListener { clearFieldFocus(); scene.selected = o; editor.refresh() }
            })
        }
        lastCount = scene.objects.size
        lastSel = scene.selected
    }

    // ---------- Inspector ----------

    private fun styleField(e: EditText) {
        e.setSingleLine()
        e.textSize = 13f
        e.setTextColor(Color.WHITE)
        e.setBackgroundColor(0xFF2A2A2A.toInt())
        e.setPadding(dp(6), dp(6), dp(6), dp(6))
        e.imeOptions = EditorInfo.IME_ACTION_DONE
    }

    private fun makeField(i: Int) = EditText(this).apply {
        styleField(this)
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        layoutParams = LinearLayout.LayoutParams(0, -2, 1f).also { it.setMargins(dp(2), dp(2), dp(2), dp(2)) }
        setOnFocusChangeListener { _, has -> if (!has) applyField(i) }
        setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_DONE) applyField(i); false }
        fields.add(this)
    }

    private fun fieldRow(label: String, start: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(this@MainActivity).apply {
            text = label; textSize = 12f; setTextColor(0xFFAAAAAA.toInt())
            layoutParams = LinearLayout.LayoutParams(dp(58), -2)
            rowLabels.add(this)
        })
        for (k in 0 until 3) addView(makeField(start + k))
    }

    private fun swatchRow(colors: IntArray) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (c in colors) addView(View(this@MainActivity).apply {
            setBackgroundColor(c)
            layoutParams = LinearLayout.LayoutParams(0, dp(28), 1f).also { it.setMargins(dp(2), dp(6), dp(2), dp(2)) }
            setOnClickListener {
                scene.selected?.let { o ->
                    o.color = floatArrayOf(Color.red(c) / 255f, Color.green(c) / 255f, Color.blue(c) / 255f)
                    editor.refresh()
                }
            }
        })
    }

    private fun buildInspector() {
        nameField = EditText(this).apply {
            styleField(this)
            hint = "Name"
            setOnFocusChangeListener { _, has -> if (!has) applyName() }
            setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_DONE) applyName(); false }
        }
        inspectorView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(6), dp(4), dp(6), dp(4))
            addView(nameField)
            addView(fieldRow("Pos", 0))
            addView(fieldRow("Rot", 3))
            addView(fieldRow("Scale", 6))
            objSwatches = swatchRow(swatchColors)
            addView(objSwatches)
            skyRow = swatchRow(skyColors)
            skyRow.visibility = View.GONE
            addView(skyRow)
            settingsHint = TextView(this@MainActivity).apply {
                text = "Settings: World = gravity, fog distance (0 = off), ambient 0-1.  Snap = grid size when dragging (0 = off).  Last row = time scale, show grid (1/0), collisions on (1/0).  Swatches = sky colour."
                textSize = 11f
                setTextColor(0xFFAAAAAA.toInt())
                setPadding(dp(4), dp(4), dp(4), dp(2))
                visibility = View.GONE
            }
            addView(settingsHint)
            colliderBtn = Button(this@MainActivity).apply {
                text = "Collider: Off"
                textSize = 12f
                setTextColor(Color.WHITE)
                setBackgroundColor(0xFF444444.toInt())
                minWidth = 0; minHeight = 0
                setPadding(dp(6), dp(8), dp(6), dp(8))
                layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.setMargins(dp(2), dp(4), dp(2), dp(2)) }
                setOnClickListener {
                    val o = scene.selected
                    if (o != null && !o.shape.isScreen && !o.shape.isLight && o.shape != Shape.SETTINGS) {
                        o.collider = (o.collider + 1) % 3
                        editor.refresh()
                    }
                }
            }
            addView(colliderBtn)
        }
    }

    private fun applyName() {
        val o = scene.selected ?: return
        val t = nameField.text.toString().trim()
        if (t.isNotEmpty() && t != o.name) { o.name = t; lastCount = -1; editor.refresh() }
    }

    private fun applyField(i: Int) {
        val o = scene.selected ?: return
        val v = fields[i].text.toString().toFloatOrNull()
        if (v == null) { refreshInspector(); return }
        if (o.shape.isScreen) {
            when (i) {
                0 -> o.x = v.coerceIn(0f, 100f)
                1 -> o.y = v.coerceIn(0f, 100f)
                6 -> o.sx = v.coerceIn(0.4f, 2.5f)
            }
            editor.refresh()
            return
        }
        if (o.shape == Shape.SETTINGS) {
            when (i) {
                0 -> o.x = v.coerceIn(0f, 100f)
                1 -> o.y = v.coerceIn(0f, 500f)
                2 -> o.z = v.coerceIn(0f, 1f)
                3 -> o.rx = v.coerceIn(0f, 10f)
                6 -> o.sx = v.coerceIn(0f, 5f)
                7 -> o.sy = if (v >= 0.5f) 1f else 0f
                8 -> o.sz = if (v >= 0.5f) 1f else 0f
            }
            editor.refresh()
            return
        }
        if (o.shape.isLight) {
            when (i) {
                0 -> o.x = v; 1 -> o.y = v; 2 -> o.z = v
                3 -> o.rx = v; 4 -> o.ry = v; 5 -> o.rz = v
                6 -> o.sx = v.coerceIn(0f, 5f)
                7 -> o.sy = if (o.shape == Shape.SUN) v.coerceIn(0f, 1f) else v.coerceIn(1f, 100f)
            }
            editor.refresh()
            return
        }
        val s = v.coerceAtLeast(0.01f)
        when (i) {
            0 -> o.x = v; 1 -> o.y = v; 2 -> o.z = v
            3 -> o.rx = v; 4 -> o.ry = v; 5 -> o.rz = v
            6 -> o.sx = s; 7 -> o.sy = s; 8 -> o.sz = s
        }
        editor.refresh()
    }

    private fun refreshInspector() {
        val o = scene.selected
        val joy = o?.shape?.isScreen == true
        val light = o?.shape?.isLight == true
        val sun = o?.shape == Shape.SUN
        val st = o?.shape == Shape.SETTINGS
        skyRow.visibility = if (st) View.VISIBLE else View.GONE
        objSwatches.visibility = if (st) View.GONE else View.VISIBLE
        settingsHint.visibility = if (st) View.VISIBLE else View.GONE
        colliderBtn.visibility = if (o != null && !joy && !light && !st) View.VISIBLE else View.GONE
        nameField.isEnabled = o != null
        for ((i, f) in fields.withIndex()) {
            f.isEnabled = o != null && when {
                st -> i in 0..3 || i == 6 || i == 7 || i == 8
                joy -> i == 0 || i == 1 || i == 6
                light -> i in 0..2 || i == 6 || i == 7 || (sun && (i == 3 || i == 4))
                else -> true
            }
        }
        rowLabels[0].text = if (joy) "Screen %" else if (st) "World" else "Pos"
        rowLabels[1].text = if (st) "Snap" else "Rot"
        rowLabels[2].text = if (joy) "Size" else if (st) "Time/Grid/Col" else if (sun) "Power/Amb" else if (light) "Power/Rng" else "Scale"
        if (o == null) {
            if (!nameField.hasFocus()) nameField.setText("")
            for (f in fields) if (!f.hasFocus()) f.setText("")
            return
        }
        if (!nameField.hasFocus()) nameField.setText(o.name)
        colliderBtn.text = "Collider: " + arrayOf("Off", "Solid (blocks)", "Trigger (events only)")[o.collider.coerceIn(0, 2)]
        val v = floatArrayOf(o.x, o.y, o.z, o.rx, o.ry, o.rz, o.sx, o.sy, o.sz)
        for (i in 0 until 9) {
            if (!fields[i].hasFocus()) {
                fields[i].setText(String.format(Locale.US, if (i in 3..5) "%.1f" else "%.2f", v[i]))
            }
        }
    }

    private fun clearFieldFocus() {
        val f = currentFocus
        if (f is EditText) {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(f.windowToken, 0)
            root.requestFocus()
        }
    }

    private fun setPanel(p: Panel) {
        clearFieldFocus()
        panel = if (panel == p) Panel.NONE else p
        topPanel.visibility = if (panel == Panel.NONE) View.GONE else View.VISIBLE
        hierarchyView.visibility = if (panel == Panel.HIER) View.VISIBLE else View.GONE
        inspectorView.visibility = if (panel == Panel.INSPECT) View.VISIBLE else View.GONE
        scriptView.visibility = if (panel == Panel.SCRIPT) View.VISIBLE else View.GONE
        lastCount = -1
        updateInfo()
    }

    // ---------- State ----------

    private fun setMode(m: Mode) { editor.mode = m; refreshButtons() }

    private fun refreshButtons() {
        for ((m, b) in modeButtons) b.setBackgroundColor(if (editor.mode == m) 0xFF3D7BFF.toInt() else 0xFF444444.toInt())
        yButton.text = "Axis: " + arrayOf("Free", "X", "Y", "Z")[editor.axis]
        yButton.setBackgroundColor(arrayOf(0xFF444444.toInt(), 0xFFC0392B.toInt(), 0xFF27AE60.toInt(), 0xFF3D7BFF.toInt())[editor.axis])
        updateInfo()
    }

    private fun updateInfo() {
        val o = scene.selected
        info.text = (if (playing) "PLAYING  |  " else "") + (o?.name ?: "Nothing selected") +
            "  |  " + scene.objects.size + " objects" + (if (playing) "" else "  |  drag: " + editor.mode.name.lowercase() + (if (editor.axis > 0) " " + arrayOf("", "X", "Y", "Z")[editor.axis] else ""))
        if (panel == Panel.HIER && (scene.objects.size != lastCount || scene.selected !== lastSel)) rebuildHierarchy()
        if (panel == Panel.INSPECT) refreshInspector()
        if (panel == Panel.SCRIPT) refreshScript()
        refreshJoystick()
    }

    /** Shows screen controls (Joystick, Jump button): a faint preview while editing, live while playing. */
    private fun refreshJoystick() {
        val j = scene.objects.firstOrNull { it.shape == Shape.JOYSTICK }
        if (j == null) {
            if (joystick.visibility != View.GONE) { joystick.reset(); joystick.visibility = View.GONE }
        } else {
            joystick.visibility = View.VISIBLE
            joystick.interactive = playing
            joystick.alpha = if (playing) 1f else 0.45f
            placeControl(joystick, j, 150)
        }
        val bs = scene.objects.filter { o -> o.shape == Shape.BUTTON }.take(4)
        for (i in 0 until 4) {
            val v = buttonViews[i]
            if (i >= bs.size) {
                if (v.visibility != View.GONE) { v.reset(); v.visibility = View.GONE }
            } else {
                v.visibility = View.VISIBLE
                v.interactive = playing
                v.alpha = if (playing) 1f else 0.45f
                v.label = bs[i].name.uppercase().take(10)
                placeControl(v, bs[i], 90)
            }
        }
    }

    private fun placeControl(v: View, o: SceneObject, baseDp: Int) {
        val w = root.width
        val h = root.height
        if (w == 0 || h == 0) return
        val size = (dp(baseDp) * o.sx.coerceIn(0.4f, 2.5f)).toInt()
        val left = (o.x.coerceIn(0f, 100f) / 100f * w - size / 2f).toInt()
        val top = (o.y.coerceIn(0f, 100f) / 100f * h - size / 2f).toInt()
        val lp = v.layoutParams as FrameLayout.LayoutParams
        if (lp.width != size || lp.height != size || lp.leftMargin != left || lp.topMargin != top) {
            lp.width = size; lp.height = size; lp.leftMargin = left; lp.topMargin = top
            v.layoutParams = lp
        }
    }

    // ---------- Editor camera presets ----------

    private fun showCameraMenu() {
        if (editor.renderer.fpMode) { toast("Switch to 3rd person first"); return }
        val pm = PopupMenu(this, cameraButton)
        val items = listOf(
            "Perspective", "Top", "Front", "Back", "Left", "Right",
            "Focus selected", "Frame all",
            "Narrow lens (30 deg)", "Normal lens (50 deg)", "Wide lens (75 deg)", "Reset view"
        )
        items.forEachIndexed { i, t -> pm.menu.add(0, i, i, t) }
        pm.setOnMenuItemClickListener { item -> applyCameraPreset(item.itemId); true }
        pm.show()
    }

    private fun applyCameraPreset(id: Int) {
        val r = editor.renderer
        when (id) {
            0 -> { r.yaw = 45f; r.pitch = 30f }
            1 -> { r.yaw = 0f; r.pitch = 85f }
            2 -> { r.yaw = 0f; r.pitch = 5f }
            3 -> { r.yaw = 180f; r.pitch = 5f }
            4 -> { r.yaw = 270f; r.pitch = 5f }
            5 -> { r.yaw = 90f; r.pitch = 5f }
            6 -> {
                val o = scene.selected
                if (o == null || o.shape.isScreen || o.shape == Shape.SETTINGS) {
                    toast("Select an object first")
                } else {
                    r.target[0] = o.x; r.target[1] = o.y; r.target[2] = o.z
                }
            }
            7 -> frameAll()
            8 -> r.fov = 30f
            9 -> r.fov = 50f
            10 -> r.fov = 75f
            11 -> {
                r.yaw = 45f; r.pitch = 30f; r.dist = 9f; r.fov = 50f
                r.target[0] = 0f; r.target[1] = 0.5f; r.target[2] = 0f
            }
        }
        editor.refresh()
    }

    /** Centres the camera on everything in the scene and zooms out to fit it. */
    private fun frameAll() {
        val r = editor.renderer
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        var any = false
        for (o in scene.objects) {
            if (o.shape.isScreen || o.shape == Shape.SETTINGS) continue
            val hx = if (o.shape.isLight) 0.3f else 0.5f * o.sx
            val hy = if (o.shape.isLight) 0.3f else 0.5f * o.sy
            val hz = if (o.shape.isLight) 0.3f else 0.5f * o.sz
            minX = minOf(minX, o.x - hx); maxX = maxOf(maxX, o.x + hx)
            minY = minOf(minY, o.y - hy); maxY = maxOf(maxY, o.y + hy)
            minZ = minOf(minZ, o.z - hz); maxZ = maxOf(maxZ, o.z + hz)
            any = true
        }
        if (!any) { applyCameraPreset(11); return }
        r.target[0] = (minX + maxX) / 2f
        r.target[1] = (minY + maxY) / 2f
        r.target[2] = (minZ + maxZ) / 2f
        val dx = maxX - minX
        val dy = maxY - minY
        val dz = maxZ - minZ
        val radius = 0.5f * sqrt(dx * dx + dy * dy + dz * dz)
        val half = Math.toRadians(r.fov / 2.0)
        r.dist = (radius / sin(half).toFloat() * 1.15f).coerceIn(2f, 40f)
    }

    // ---------- First-person view (play mode) ----------

    private fun toggleFirstPerson() {
        if (!playing) { toast("Tap Play first"); return }
        setFirstPerson(!editor.renderer.fpMode)
    }

    private fun findCamTarget(): SceneObject? = scene.objects.firstOrNull {
        !it.shape.isScreen && !it.shape.isLight && it.shape != Shape.SETTINGS && it.name.equals(camTargetName, true)
    }

    private fun updateFpEye() {
        val t = fpTarget ?: return
        val r = editor.renderer
        r.fpX = t.x
        r.fpY = t.y + 0.6f * t.sy
        r.fpZ = t.z
    }

    private fun setFirstPerson(on: Boolean) {
        val r = editor.renderer
        if (on) {
            val t = findCamTarget()
                ?: scene.selected?.takeIf { !it.shape.isScreen && !it.shape.isLight && it.shape != Shape.SETTINGS }
            if (t == null) { toast("Add an object named Player, or select one"); return }
            savedPitch = r.pitch
            savedDist = r.dist
            fpTarget = t
            r.fpHide = t
            r.pitch = 5f
            updateFpEye()
            r.fpMode = true
            viewButton.text = "3rd person"
        } else {
            r.fpMode = false
            r.fpHide = null
            fpTarget = null
            r.pitch = savedPitch.coerceIn(5f, 85f)
            r.dist = savedDist
            viewButton.text = "1st person"
        }
        editor.refresh()
    }

    // ---------- Save / load ----------

    private fun modelsDir() = File(filesDir, "models").apply { mkdirs() }

    private fun loadModel(key: String): ModelData? = modelCache[key] ?: try {
        ModelData(key, ModelLoader.load(key, File(modelsDir(), key).readBytes())).also { modelCache[key] = it }
    } catch (e: Exception) { null }

    private fun save() {
        File(filesDir, "scene.json").writeText(scene.toJson())
        toast("Saved to internal storage")
    }

    private fun load() {
        try {
            scene.load(File(filesDir, "scene.json").readText()) { loadModel(it) }
            editor.refresh()
            toast("Loaded from internal storage")
        } catch (e: Exception) {
            toast("No saved scene")
        }
    }

    // ---------- Import ----------

    private fun pickModel() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(i, reqImport)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            reqImport -> importModel(uri)
            reqExport -> writeProject(uri)
            reqOpen -> readProject(uri)
            reqObj -> writeObj(uri)
        }
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0) return c.getString(i)
            }
        }
        return uri.lastPathSegment ?: "model"
    }

    private fun importModel(uri: Uri) {
        val display = displayName(uri)
        toast("Importing $display ...")
        Thread {
            try {
                val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                val key = System.currentTimeMillis().toString() + "_" + display.replace(Regex("[^A-Za-z0-9._-]"), "_")
                val md = ModelData(key, ModelLoader.load(display, bytes))
                File(modelsDir(), key).writeBytes(bytes)
                modelCache[key] = md
                runOnUiThread {
                    scene.add(Shape.MODEL, md, display.substringBeforeLast('.'))
                    editor.refresh()
                }
            } catch (e: Throwable) {
                runOnUiThread { toast("Import failed: " + e.message) }
            }
        }.start()
    }

    // ---------- Export / open (scene + models + scripts as a .zip) ----------

    private fun exportProject() {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_TITLE, "scene.zip")
        }
        startActivityForResult(i, reqExport)
    }

    private fun openProject() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(i, reqOpen)
    }

    private fun writeProject(uri: Uri) {
        val json = scene.toJson()
        val keys = scene.objects.mapNotNull { it.model?.key }.toSet()
        Thread {
            try {
                contentResolver.openOutputStream(uri)!!.use { os ->
                    ZipOutputStream(os).use { z ->
                        z.putNextEntry(ZipEntry("scene.json"))
                        z.write(json.toByteArray())
                        z.closeEntry()
                        for (k in keys) {
                            val f = File(modelsDir(), k)
                            if (f.exists()) {
                                z.putNextEntry(ZipEntry("models/" + k))
                                z.write(f.readBytes())
                                z.closeEntry()
                            }
                        }
                    }
                }
                runOnUiThread { toast("Exported") }
            } catch (e: Throwable) {
                runOnUiThread { toast("Export failed: " + e.message) }
            }
        }.start()
    }

    private fun readProject(uri: Uri) {
        Thread {
            try {
                var json: String? = null
                contentResolver.openInputStream(uri)!!.use { ins ->
                    val z = ZipInputStream(ins)
                    while (true) {
                        val e = z.nextEntry ?: break
                        val name = e.name.substringAfterLast('/')
                        if (e.isDirectory || name.isEmpty() || name == "." || name == "..") continue
                        val data = z.readBytes()
                        if (name == "scene.json") json = String(data)
                        else if (e.name.startsWith("models/")) File(modelsDir(), name).writeBytes(data)
                    }
                }
                val text = json ?: throw IllegalArgumentException("No scene.json in file")
                runOnUiThread {
                    try {
                        scene.load(text) { loadModel(it) }
                        editor.refresh()
                        toast("Opened")
                    } catch (e: Throwable) {
                        toast("Open failed: " + e.message)
                    }
                }
            } catch (e: Throwable) {
                runOnUiThread { toast("Open failed: " + e.message) }
            }
        }.start()
    }

    // ---------- Export as .obj ----------

    private fun exportObj() {
        if (scene.objects.isEmpty()) { toast("Nothing to export"); return }
        val title = (scene.selected?.name ?: "scene").replace(Regex("[^A-Za-z0-9._-]"), "_") + ".obj"
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, title)
        }
        startActivityForResult(i, reqObj)
    }

    private fun writeObj(uri: Uri) {
        val list = scene.selected?.let { listOf(it) } ?: scene.objects.toList()
        Thread {
            try {
                val text = ObjExporter.export(list)
                contentResolver.openOutputStream(uri)!!.use { it.write(text.toByteArray()) }
                runOnUiThread { toast("Exported OBJ") }
            } catch (e: Throwable) {
                runOnUiThread { toast("Export failed: " + e.message) }
            }
        }.start()
    }

    // Auto-save: the scene is written to internal storage whenever the app is paused
    // and reloaded on launch, so nothing is lost when you close it.
    private fun autoSave() {
        try {
            val tmp = File(filesDir, "autosave.tmp")
            tmp.writeText(scene.toJson())
            val dst = File(filesDir, "autosave.json")
            if (!tmp.renameTo(dst)) { dst.writeText(tmp.readText()); tmp.delete() }
        } catch (e: Exception) {
        }
    }

    private fun autoLoad(): Boolean {
        val f = File(filesDir, "autosave.json")
        if (!f.exists()) return false
        return try {
            scene.load(f.readText()) { loadModel(it) }
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            ax = e.values[0]; ay = e.values[1]; az = e.values[2]
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onPause() { stopPlay(); autoSave(); super.onPause(); editor.onPause() }
    override fun onResume() { super.onResume(); editor.onResume() }
}
