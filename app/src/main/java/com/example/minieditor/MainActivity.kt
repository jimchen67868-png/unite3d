package com.example.minieditor

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.ExifInterface
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
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.random.Random
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class MainActivity : Activity(), SensorEventListener {
    private enum class Panel { NONE, HIER, INSPECT, SCRIPT, MATERIAL }
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
    private lateinit var coordLabel: TextView
    private var showCoords = true
    @Volatile private var labelPending = false
    private lateinit var colliderBtn: Button
    private lateinit var terrainPanel: LinearLayout
    private lateinit var sculptBtn: Button
    private lateinit var brushLabels: Array<TextView>
    private lateinit var brushSliders: Array<SeekBar>
    private lateinit var importButton: Button
    private lateinit var photoBtn: Button
    private lateinit var skyPhotoBtn: Button
    private lateinit var skyModeBtn: Button
    private lateinit var topScroll: MaxHeightScrollView
    private lateinit var shadowPanel: LinearLayout
    private lateinit var skyPanel: LinearLayout
    private lateinit var skyLabels: Array<TextView>
    private lateinit var skySliders: Array<SeekBar>
    private lateinit var skyPreview: View
    private lateinit var shLabels: Array<TextView>
    private lateinit var shSliders: Array<SeekBar>
    private lateinit var materialView: LinearLayout
    private lateinit var matLabels: Array<TextView>
    private lateinit var matSliders: Array<SeekBar>
    private lateinit var presetBtn: Button
    private lateinit var patternBtn: Button
    private lateinit var shadowBtn: Button
    private lateinit var lightPanel: LinearLayout
    private lateinit var sliderA: SeekBar
    private lateinit var sliderB: SeekBar
    private lateinit var labelA: TextView
    private lateinit var labelB: TextView
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
    private val reqPhoto = 46
    private var photoForSelected = false
    private var photoForSky = false

    // play mode
    private var playing = false
    private var engine: ScriptEngine? = null
    private var snaps: List<Snap> = emptyList()
    private var lastFrame = 0L
    private val logLines = ArrayList<String>()
    private var ax = 0f
    private var fax = 0f
    private var fay = 0f
    private var faz = 0f
    private var bax = 0f
    private var bay = 0f
    private var baz = 0f
    private var sensorInit = false
    private var tiltOn = true
    private var ay = 0f
    private var az = 0f

    private val tintColors = intArrayOf(
        0xFF000000.toInt(), 0xFF0D1330.toInt(), 0xFF2A0F33.toInt(), 0xFF2B1A0A.toInt(), 0xFF0F2B14.toInt()
    )

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
        importButton = btn("Import", true) { showImportMenu() }

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xAA000000.toInt())
            addView(consoleView)
            addView(info)
            addView(row(
                btn("+Cube", true) { scene.add(Shape.CUBE); editor.refresh() },
                btn("+Pyramid", true) { scene.add(Shape.PYRAMID); editor.refresh() },
                btn("+Sphere", true) { scene.add(Shape.SPHERE); editor.refresh() },
                importButton
            ))
            addView(row(modeButtons[Mode.MOVE]!!, modeButtons[Mode.ROTATE]!!, modeButtons[Mode.SCALE]!!, yButton))
            addView(row(
                btn("Hierarchy") { setPanel(Panel.HIER) },
                btn("Inspector") { setPanel(Panel.INSPECT) },
                btn("Material") { setPanel(Panel.MATERIAL) },
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
        buildMaterial()
        topPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xCC000000.toInt())
            visibility = View.GONE
            addView(hierarchyView)
            addView(inspectorView)
            addView(scriptView)
            addView(materialView)
        }

        root = FrameLayout(this).apply { isFocusable = true; isFocusableInTouchMode = true }
        root.addView(editor, FrameLayout.LayoutParams(-1, -1))
        coordLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setBackgroundColor(0xAA000000.toInt())
            setPadding(dp(6), dp(3), dp(6), dp(3))
            visibility = View.GONE
        }
        root.addView(coordLabel, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
        topScroll = MaxHeightScrollView(this)
        topScroll.addView(topPanel)
        root.addView(topScroll, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        joystick = JoystickView(this).apply { visibility = View.GONE }
        root.addView(joystick, FrameLayout.LayoutParams(dp(150), dp(150), Gravity.TOP or Gravity.START))
        for (i in 0 until 4) {
            val v = JumpButtonView(this).apply { visibility = View.GONE }
            buttonViews.add(v)
            root.addView(v, FrameLayout.LayoutParams(dp(90), dp(90), Gravity.TOP or Gravity.START))
        }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            topScroll.maxHeightPx = (root.height * 0.6f).toInt()
            refreshJoystick()
        }
        setContentView(root)

        editor.onChange = { updateInfo() }
        editor.renderer.photoLoader = { key -> decodePhoto(key) }
        editor.renderer.onFrame = {
            if (!labelPending) {
                labelPending = true
                coordLabel.post { labelPending = false; updateCoordLabel() }
            }
        }
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
        pm.menu.add(0, 9, 8, "Show coordinates: " + (if (showCoords) "on" else "off"))
        pm.menu.add(0, 10, 9, "Shadow settings...")
        pm.menu.add(0, 11, 10, "Tilt control: " + (if (tiltOn) "on" else "off"))
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
                    9 -> { showCoords = !showCoords; editor.refresh() }
                    11 -> { tiltOn = !tiltOn; toast("Tilt control " + (if (tiltOn) "on" else "off")) }
                    10 -> {
                        val s = scene.objects.firstOrNull { it.shape == Shape.SETTINGS } ?: scene.addSettings()
                        scene.selected = s
                        if (panel != Panel.INSPECT) setPanel(Panel.INSPECT)
                        editor.refresh()
                    }
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
                it.ax = if (tiltOn) fax - bax else 0f
                it.ay = if (tiltOn) fay - bay else 0f
                it.az = if (tiltOn) faz - baz else 0f
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
        sensorInit = false
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

    // ---------- Material ----------

    private fun matTarget(): SceneObject? {
        val o = scene.selected ?: return null
        return if (o.shape.isScreen || o.shape.isLight || o.shape == Shape.SETTINGS || o.shape == Shape.CAMERA) null else o
    }

    private fun buildMaterial() {
        matLabels = Array(4) {
            TextView(this).apply {
                textSize = 12f; setTextColor(0xFFDDDDDD.toInt()); setPadding(dp(4), dp(6), dp(4), 0)
            }
        }
        matSliders = Array(4) { i ->
            SeekBar(this).apply {
                max = 100
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                        if (fromUser) applyMat(i, p)
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            }
        }
        presetBtn = btn("Preset...") { showPresetMenu() }
        photoBtn = btn("Photo: none") { showPhotoMenu() }
        patternBtn = btn("Pattern: None") {
            val o = matTarget()
            if (o != null) { o.pattern = (o.pattern + 1) % 3; editor.refresh() }
        }
        materialView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(6), dp(4), dp(6), dp(4))
            addView(row(presetBtn, patternBtn, photoBtn))
            for (i in 0..3) {
                addView(matLabels[i])
                addView(matSliders[i])
                if (i == 3) {
                    // fine control: the slider is touchy, these change opacity by an exact amount
                    addView(row(
                        btn("-10%") { nudgeOpacity(-0.1f) },
                        btn("-1%") { nudgeOpacity(-0.01f) },
                        btn("+1%") { nudgeOpacity(0.01f) },
                        btn("+10%") { nudgeOpacity(0.1f) },
                        btn("Solid") { nudgeOpacity(1f) }
                    ))
                }
            }
        }
    }

    private fun nudgeOpacity(d: Float) {
        val o = matTarget() ?: return
        o.opacity = (Math.round((o.opacity + d) * 100f) / 100f).coerceIn(0.02f, 1f)
        editor.refresh()
    }

    private fun applyMat(i: Int, p: Int) {
        val o = matTarget() ?: return
        when (i) {
            0 -> o.spec = p / 100f
            1 -> o.gloss = p / 100f
            2 -> o.emit = p / 100f
            3 -> o.opacity = (p / 100f).coerceAtLeast(0.02f)
        }
        editor.refresh()
    }

    private fun showPresetMenu() {
        val o = matTarget()
        if (o == null) { toast("Select a cube, pyramid, sphere or model first"); return }
        val pm = PopupMenu(this, presetBtn)
        listOf("Plastic", "Rubber (matte)", "Metal", "Glass", "Glow", "Ghost", "Checker", "Stripes")
            .forEachIndexed { i, t -> pm.menu.add(0, i, i, t) }
        pm.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                0 -> { o.spec = 0.5f; o.gloss = 0.5f; o.emit = 0f; o.opacity = 1f; o.pattern = 0 }
                1 -> { o.spec = 0f; o.gloss = 0.1f; o.emit = 0f; o.opacity = 1f; o.pattern = 0 }
                2 -> { o.spec = 1f; o.gloss = 0.85f; o.emit = 0f; o.opacity = 1f; o.pattern = 0 }
                3 -> { o.spec = 1f; o.gloss = 0.9f; o.emit = 0f; o.opacity = 0.35f; o.pattern = 0 }
                4 -> { o.spec = 0.2f; o.gloss = 0.3f; o.emit = 0.8f; o.opacity = 1f; o.pattern = 0 }
                5 -> { o.spec = 0f; o.gloss = 0.3f; o.emit = 0f; o.opacity = 0.45f; o.pattern = 0 }
                6 -> o.pattern = 1
                7 -> o.pattern = 2
            }
            editor.refresh()
            true
        }
        pm.show()
    }

    private fun refreshMaterial() {
        val o = matTarget()
        for (sb in matSliders) sb.isEnabled = o != null
        presetBtn.isEnabled = o != null
        patternBtn.isEnabled = o != null
        photoBtn.isEnabled = o != null
        if (o == null) {
            for (l in matLabels) l.text = "Select a cube, pyramid, sphere or model to edit its material"
            for (i in 1..3) matLabels[i].text = ""
            patternBtn.text = "Pattern: -"
            photoBtn.text = "Photo: -"
            return
        }
        matLabels[0].text = String.format(Locale.US, "Shininess   %.2f", o.spec)
        matLabels[1].text = String.format(Locale.US, "Gloss (tight highlight)   %.2f", o.gloss)
        matLabels[2].text = String.format(Locale.US, "Glow (lights itself)   %.2f", o.emit)
        val pct = Math.round(o.opacity * 100f)
        matLabels[3].text = String.format(Locale.US, "Opacity   %d%% visible,  %d%% see-through", pct, 100 - pct)
        val vals = floatArrayOf(o.spec, o.gloss, o.emit, o.opacity)
        for (i in 0..3) if (!matSliders[i].isPressed) matSliders[i].progress = (vals[i] * 100f).toInt().coerceIn(0, 100)
        patternBtn.text = "Pattern: " + arrayOf("None", "Checker", "Stripes")[o.pattern.coerceIn(0, 2)]
        photoBtn.text = "Photo: " + (if (o.photo.isEmpty()) "none" else "on")
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
            addView(row(
                btn("+Camera", true) { if (scene.addCamera() == null) toast("Camera already added") else editor.refresh() },
                btn("+Terrain", true) { scene.addTerrain(); editor.refresh() }
            ))
            addView(scroll)
        }
    }

    private fun rebuildHierarchy() {
        hierList.removeAllViews()
        for (o in scene.objects) {
            val sel = o === scene.selected
            hierList.addView(TextView(this).apply {
                text = (if (sel) "▶ " else "    ") + o.name + (if (o.script.isNotBlank()) "  {lua}" else "") + (if (o.shape.isScreen) "  [screen]" else "") + (if (o.shape.isLight) "  [light]" else "") + (if (o.shape == Shape.SETTINGS) "  [settings]" else "") + (if (o.shape == Shape.CAMERA) "  [camera]" else "") + (if (o.shape == Shape.TERRAIN) "  [terrain]" else "")
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

    private fun swatchRow(colors: IntArray, onPick: ((Int) -> Unit)? = null) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (c in colors) addView(View(this@MainActivity).apply {
            setBackgroundColor(c)
            layoutParams = LinearLayout.LayoutParams(0, dp(28), 1f).also { it.setMargins(dp(2), dp(6), dp(2), dp(2)) }
            setOnClickListener {
                if (onPick != null) { onPick(c); return@setOnClickListener }
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
            skyLabels = Array(3) {
                TextView(this@MainActivity).apply {
                    textSize = 12f; setTextColor(0xFFDDDDDD.toInt()); setPadding(dp(4), dp(4), dp(4), 0)
                }
            }
            val sliderTints = intArrayOf(0xFFE65A4D.toInt(), 0xFF4DB373.toInt(), 0xFF4D8CE6.toInt())
            skySliders = Array(3) { i ->
                SeekBar(this@MainActivity).apply {
                    max = 100
                    progressTintList = ColorStateList.valueOf(sliderTints[i])
                    thumbTintList = ColorStateList.valueOf(sliderTints[i])
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                            if (fromUser) applySky(i, p)
                        }
                        override fun onStartTrackingTouch(sb: SeekBar?) {}
                        override fun onStopTrackingTouch(sb: SeekBar?) {}
                    })
                }
            }
            skyPreview = View(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(-1, dp(18)).also { it.setMargins(dp(4), dp(6), dp(4), dp(2)) }
            }
            skyPhotoBtn = btn("Sky photo: none") { showSkyPhotoMenu() }
            skyModeBtn = btn("Mapping: Panorama") {
                val s = scene.selected
                if (s != null && s.shape == Shape.SETTINGS) {
                    s.pattern = if (s.pattern == 1) 0 else 1
                    editor.refresh()
                }
            }
            skyPanel = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                addView(TextView(this@MainActivity).apply {
                    text = "Sky colour - mix any colour"
                    textSize = 12f; setTextColor(0xFFAAAAAA.toInt()); setPadding(dp(4), dp(6), dp(4), 0)
                })
                addView(skyPreview)
                addView(row(skyPhotoBtn, skyModeBtn))
                for (i in 0..2) { addView(skyLabels[i]); addView(skySliders[i]) }
            }
            addView(skyPanel)
            settingsHint = TextView(this@MainActivity).apply {
                text = "Settings: World = gravity, fog distance (0 = off), ambient 0-1.  Snap/Shd/Gnd = grid snap (0 = off), shadow strength 0-1 (0 = off), ground height for shadows.  Last row = time scale, show grid (1/0), collisions on (1/0).  Swatches = sky colour."
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
                    if (o != null && !o.shape.isScreen && !o.shape.isLight && o.shape != Shape.SETTINGS && o.shape != Shape.CAMERA) {
                        o.collider = (o.collider + 1) % 3
                        editor.refresh()
                    }
                }
            }
            addView(colliderBtn)
            shadowBtn = Button(this@MainActivity).apply {
                text = "Casts shadow: On"
                textSize = 12f
                setTextColor(Color.WHITE)
                setBackgroundColor(0xFF444444.toInt())
                minWidth = 0; minHeight = 0
                setPadding(dp(6), dp(8), dp(6), dp(8))
                layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.setMargins(dp(2), dp(2), dp(2), dp(2)) }
                setOnClickListener {
                    val o = scene.selected
                    if (o != null && !o.shape.isScreen && !o.shape.isLight && o.shape != Shape.SETTINGS && o.shape != Shape.CAMERA) {
                        o.shadow = !o.shadow
                        editor.refresh()
                    }
                }
            }
            addView(shadowBtn)
            labelA = TextView(this@MainActivity).apply {
                textSize = 12f; setTextColor(0xFFDDDDDD.toInt()); setPadding(dp(4), dp(6), dp(4), 0)
            }
            labelB = TextView(this@MainActivity).apply {
                textSize = 12f; setTextColor(0xFFDDDDDD.toInt()); setPadding(dp(4), dp(6), dp(4), 0)
            }
            sliderA = makeSlider(0)
            sliderB = makeSlider(1)
            lightPanel = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                addView(labelA)
                addView(sliderA)
                addView(labelB)
                addView(sliderB)
            }
            addView(lightPanel)
            shLabels = Array(3) {
                TextView(this@MainActivity).apply {
                    textSize = 12f; setTextColor(0xFFDDDDDD.toInt()); setPadding(dp(4), dp(6), dp(4), 0)
                }
            }
            shSliders = Array(3) { i ->
                SeekBar(this@MainActivity).apply {
                    max = 100
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                            if (fromUser) applyShadow(i, p)
                        }
                        override fun onStartTrackingTouch(sb: SeekBar?) {}
                        override fun onStopTrackingTouch(sb: SeekBar?) {}
                    })
                }
            }
            val tintLabel = TextView(this@MainActivity).apply {
                text = "Shadow colour"
                textSize = 12f; setTextColor(0xFFDDDDDD.toInt()); setPadding(dp(4), dp(6), dp(4), 0)
            }
            shadowPanel = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                addView(row(
                    btn("Hard black") {
                        val s = scene.selected
                        if (s != null && s.shape == Shape.SETTINGS) {
                            s.ry = 1f; s.soft = 0f; s.tint = floatArrayOf(0f, 0f, 0f)
                            editor.refresh()
                        }
                    },
                    btn("Soft") {
                        val s = scene.selected
                        if (s != null && s.shape == Shape.SETTINGS) {
                            s.ry = 0.5f; s.soft = 0.5f; s.tint = floatArrayOf(0f, 0f, 0f)
                            editor.refresh()
                        }
                    }
                ))
                addView(shLabels[0]); addView(shSliders[0])
                addView(shLabels[1]); addView(shSliders[1])
                addView(shLabels[2]); addView(shSliders[2])
                addView(tintLabel)
                addView(swatchRow(tintColors) { c ->
                    val st = scene.selected
                    if (st != null && st.shape == Shape.SETTINGS) {
                        st.tint = floatArrayOf(Color.red(c) / 255f, Color.green(c) / 255f, Color.blue(c) / 255f)
                        editor.refresh()
                    }
                })
            }
            addView(shadowPanel)
            sculptBtn = btn("Sculpt: Off") { editor.sculpt = (editor.sculpt + 1) % 5; editor.refresh() }
            brushLabels = Array(2) {
                TextView(this@MainActivity).apply {
                    textSize = 12f; setTextColor(0xFFDDDDDD.toInt()); setPadding(dp(4), dp(6), dp(4), 0)
                }
            }
            brushSliders = Array(2) { i ->
                SeekBar(this@MainActivity).apply {
                    max = 100
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                            if (!fromUser) return
                            if (i == 0) editor.brushRadius = 1f + p * 0.09f else editor.brushStrength = (p / 100f).coerceAtLeast(0.05f)
                            editor.refresh()
                        }
                        override fun onStartTrackingTouch(sb: SeekBar?) {}
                        override fun onStopTrackingTouch(sb: SeekBar?) {}
                    })
                }
            }
            terrainPanel = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                addView(TextView(this@MainActivity).apply {
                    text = "Terrain: turn Sculpt on, then drag over the hills. Turn it off to orbit again. Size W/H/D sets width, tallest height and depth."
                    textSize = 11f; setTextColor(0xFFAAAAAA.toInt()); setPadding(dp(4), dp(4), dp(4), dp(2))
                })
                addView(row(
                    sculptBtn,
                    btn("New hills") { regenTerrain(false) },
                    btn("Flat") { regenTerrain(true) }
                ))
                addView(brushLabels[0]); addView(brushSliders[0])
                addView(brushLabels[1]); addView(brushSliders[1])
            }
            addView(terrainPanel)
        }
    }

    private fun makeSlider(which: Int) = SeekBar(this).apply {
        max = 100
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) applySlider(which, p)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    /** Light sliders: 0 = brightness (0-5). 1 = distance 1-100 for a point light, or ambient 0-1 for the sun. */
    private fun applySlider(which: Int, p: Int) {
        val o = scene.selected ?: return
        if (!o.shape.isLight) return
        if (which == 0) o.sx = p / 20f
        else if (o.shape == Shape.SUN) o.sy = p / 100f
        else o.sy = (1f + p).coerceAtMost(100f)
        editor.refresh()
    }

    /** Settings shadow sliders: 0 = strength, 1 = softness, 2 = ground height (-5 to 10). */
    private fun applyShadow(i: Int, p: Int) {
        val o = scene.selected ?: return
        if (o.shape != Shape.SETTINGS) return
        when (i) {
            0 -> o.ry = p / 100f
            1 -> o.soft = p / 100f
            2 -> o.rz = -5f + p * 0.15f
        }
        editor.refresh()
    }

    /** Sky colour sliders: 0 = red, 1 = green, 2 = blue (the Settings object's colour). */
    private fun applySky(i: Int, p: Int) {
        val o = scene.selected ?: return
        if (o.shape != Shape.SETTINGS) return
        val c = o.color.copyOf()
        c[i] = p / 100f
        o.color = c
        editor.refresh()
    }

    private fun regenTerrain(flat: Boolean) {
        val o = scene.selected
        if (o == null || o.shape != Shape.TERRAIN) return
        o.heights = if (flat) TerrainGen.flat() else TerrainGen.generate(Random.nextInt(1, 100000), 0.5f)
        o.hVersion++
        editor.refresh()
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
        if (o.shape == Shape.CAMERA) {
            when (i) {
                0 -> o.x = v; 1 -> o.y = v; 2 -> o.z = v
                3 -> o.rx = v.coerceIn(-89f, 89f)
                4 -> o.ry = v
                6 -> o.sx = v.coerceIn(20f, 120f)
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
                4 -> o.ry = v.coerceIn(0f, 1f)
                5 -> o.rz = v
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
        val cam = o?.shape == Shape.CAMERA
        val ter = o?.shape == Shape.TERRAIN
        skyRow.visibility = if (st) View.VISIBLE else View.GONE
        skyPanel.visibility = if (st) View.VISIBLE else View.GONE
        objSwatches.visibility = if (st) View.GONE else View.VISIBLE
        settingsHint.visibility = if (st) View.VISIBLE else View.GONE
        colliderBtn.visibility = if (o != null && !joy && !light && !st && !cam) View.VISIBLE else View.GONE
        lightPanel.visibility = if (light) View.VISIBLE else View.GONE
        shadowPanel.visibility = if (st) View.VISIBLE else View.GONE
        terrainPanel.visibility = if (ter) View.VISIBLE else View.GONE
        shadowBtn.visibility = colliderBtn.visibility
        nameField.isEnabled = o != null
        for ((i, f) in fields.withIndex()) {
            f.isEnabled = o != null && when {
                st -> i in 0..5 || i == 6 || i == 7 || i == 8
                cam -> i in 0..4 || i == 6
                ter -> i in 0..2 || i in 6..8
                joy -> i == 0 || i == 1 || i == 6
                light -> i in 0..2 || i == 6 || i == 7 || (sun && (i == 3 || i == 4))
                else -> true
            }
        }
        rowLabels[0].text = if (joy) "Screen %" else if (st) "World" else "Pos"
        rowLabels[1].text = if (st) "Snap/Shd/Gnd" else if (cam) "Pitch/Yaw" else "Rot"
        rowLabels[2].text = if (joy) "Size" else if (st) "Time/Grid/Col" else if (cam) "FOV" else if (ter) "Size W/H/D" else if (sun) "Power/Amb" else if (light) "Power/Rng" else "Scale"
        if (o == null) {
            if (!nameField.hasFocus()) nameField.setText("")
            for (f in fields) if (!f.hasFocus()) f.setText("")
            return
        }
        if (!nameField.hasFocus()) nameField.setText(o.name)
        shadowBtn.text = "Casts shadow: " + (if (o.shadow) "On" else "Off")
        sculptBtn.text = "Sculpt: " + arrayOf("Off", "Raise", "Lower", "Smooth", "Flatten")[editor.sculpt.coerceIn(0, 4)]
        brushLabels[0].text = String.format(Locale.US, "Brush size   %.1f", editor.brushRadius)
        brushLabels[1].text = String.format(Locale.US, "Brush strength   %.2f", editor.brushStrength)
        if (!brushSliders[0].isPressed) brushSliders[0].progress = ((editor.brushRadius - 1f) / 0.09f).toInt().coerceIn(0, 100)
        if (!brushSliders[1].isPressed) brushSliders[1].progress = (editor.brushStrength * 100f).toInt().coerceIn(0, 100)
        if (o.shape.isLight) {
            val isSun = o.shape == Shape.SUN
            labelA.text = String.format(Locale.US, "Brightness   %.2f", o.sx)
            labelB.text = if (isSun) String.format(Locale.US, "Ambient (light in the shadows)   %.2f", o.sy)
            else String.format(Locale.US, "Distance (how far the light reaches)   %.0f", o.sy)
            if (!sliderA.isPressed) sliderA.progress = (o.sx * 20f).toInt().coerceIn(0, 100)
            if (!sliderB.isPressed) sliderB.progress = (if (isSun) o.sy * 100f else o.sy - 1f).toInt().coerceIn(0, 100)
        }
        if (o.shape == Shape.SETTINGS) {
            shLabels[0].text = String.format(Locale.US, "Shadow strength   %.2f  (0 = no shadows)", o.ry)
            shLabels[1].text = String.format(Locale.US, "Softness (blurry edge)   %.2f", o.soft)
            shLabels[2].text = String.format(Locale.US, "Ground height   %.2f  (where shadows land)", o.rz)
            if (!shSliders[0].isPressed) shSliders[0].progress = (o.ry * 100f).toInt().coerceIn(0, 100)
            if (!shSliders[1].isPressed) shSliders[1].progress = (o.soft * 100f).toInt().coerceIn(0, 100)
            if (!shSliders[2].isPressed) shSliders[2].progress = ((o.rz + 5f) / 0.15f).toInt().coerceIn(0, 100)
            skyPhotoBtn.text = "Sky photo: " + (if (o.photo.isEmpty()) "none" else "on")
            skyModeBtn.text = "Mapping: " + (if (o.pattern == 1) "Backdrop" else "Panorama")
            val names = arrayOf("Red", "Green", "Blue")
            for (i in 0..2) {
                skyLabels[i].text = String.format(Locale.US, "%s   %.2f", names[i], o.color[i])
                if (!skySliders[i].isPressed) skySliders[i].progress = (o.color[i] * 100f).toInt().coerceIn(0, 100)
            }
            skyPreview.setBackgroundColor(
                Color.rgb((o.color[0] * 255f).toInt().coerceIn(0, 255), (o.color[1] * 255f).toInt().coerceIn(0, 255), (o.color[2] * 255f).toInt().coerceIn(0, 255))
            )
        }
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
        materialView.visibility = if (panel == Panel.MATERIAL) View.VISIBLE else View.GONE
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

    /** Floating X / Y / Z tag that follows the selected object on screen (editor only). */
    private fun updateCoordLabel() {
        val o = scene.selected
        if (!showCoords || playing || o == null || o.shape.isScreen || o.shape == Shape.SETTINGS) {
            coordLabel.visibility = View.GONE
            return
        }
        val p = editor.renderer.project(o.x, o.y, o.z)
        if (p == null) {
            coordLabel.visibility = View.GONE
            return
        }
        coordLabel.text = String.format(Locale.US, "%s\nX %.2f  Y %.2f  Z %.2f", o.name, o.x, o.y, o.z)
        coordLabel.visibility = View.VISIBLE
        val w = coordLabel.width.toFloat()
        val h = coordLabel.height.toFloat()
        coordLabel.x = (p[0] - w / 2f).coerceIn(0f, maxOf(0f, root.width - w))
        coordLabel.y = (p[1] - h - dp(26)).coerceIn(0f, maxOf(0f, root.height - h))
    }

    private fun updateInfo() {
        val o = scene.selected
        info.text = (if (playing) "PLAYING  |  " else "") + (o?.name ?: "Nothing selected") +
            (if (o != null && !o.shape.isScreen && o.shape != Shape.SETTINGS) String.format(Locale.US, " (%.2f, %.2f, %.2f)", o.x, o.y, o.z) else "") +
            "  |  " + scene.objects.size + " objects" + (if (playing) "" else "  |  drag: " + editor.mode.name.lowercase() + (if (editor.axis > 0) " " + arrayOf("", "X", "Y", "Z")[editor.axis] else ""))
        if (scene.selected?.shape != Shape.TERRAIN && editor.sculpt != 0) editor.sculpt = 0
        if (panel == Panel.HIER && (scene.objects.size != lastCount || scene.selected !== lastSel)) rebuildHierarchy()
        if (panel == Panel.INSPECT) refreshInspector()
        if (panel == Panel.SCRIPT) refreshScript()
        if (panel == Panel.MATERIAL) refreshMaterial()
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
            "Narrow lens (30 deg)", "Normal lens (50 deg)", "Wide lens (75 deg)", "Reset view",
            "Camera object: preview view (" + (if (editor.renderer.previewCam) "on" else "off") + ")",
            "Camera object: copy this view"
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
            12 -> {
                if (scene.objects.none { o -> o.shape == Shape.CAMERA }) toast("Add a Camera object first (Hierarchy)")
                else r.previewCam = !r.previewCam
            }
            13 -> alignCameraObject()
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

    /** Puts the Camera object where the editor camera is now, looking the same way. */
    private fun alignCameraObject() {
        val cam = scene.objects.firstOrNull { o -> o.shape == Shape.CAMERA }
        if (cam == null) { toast("Add a Camera object first (Hierarchy)"); return }
        val r = editor.renderer
        val p = Math.toRadians(r.pitch.toDouble())
        val y = Math.toRadians(r.yaw.toDouble())
        cam.x = r.target[0] + r.dist * (cos(p) * sin(y)).toFloat()
        cam.y = r.target[1] + r.dist * sin(p).toFloat()
        cam.z = r.target[2] + r.dist * (cos(p) * cos(y)).toFloat()
        cam.rx = r.pitch
        cam.ry = r.yaw
        cam.sx = r.fov
        editor.refresh()
        toast("Camera object set to the current view")
    }

    /** Centres the camera on everything in the scene and zooms out to fit it. */
    private fun frameAll() {
        val r = editor.renderer
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        var any = false
        for (o in scene.objects) {
            if (o.shape.isScreen || o.shape == Shape.SETTINGS || o.shape == Shape.CAMERA) continue
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
            reqPhoto -> importPhoto(uri)
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

    // ---------- Photos ----------

    private fun photosDir() = File(filesDir, "photos").apply { mkdirs() }

    private fun photoKeys(): List<String> = photosDir().listFiles()?.map { it.name }?.sorted() ?: emptyList()

    /** Called on the GL thread. Photos are stored already rotated and shrunk, so this is a plain decode. */
    private fun decodePhoto(key: String): Bitmap? {
        val f = File(photosDir(), key)
        return if (f.exists()) BitmapFactory.decodeFile(f.path) else null
    }

    private fun showImportMenu() {
        val pm = PopupMenu(this, importButton)
        pm.menu.add(0, 1, 0, "3D model (.obj, .glb, .gltf)")
        pm.menu.add(0, 2, 1, "Photo (as a picture panel)")
        pm.setOnMenuItemClickListener { item ->
            if (item.itemId == 1) pickModel() else { photoForSelected = false; photoForSky = false; pickPhoto() }
            true
        }
        pm.show()
    }

    private fun showSkyPhotoMenu() {
        val st = scene.selected
        if (st == null || st.shape != Shape.SETTINGS) return
        val keys = photoKeys()
        val pm = PopupMenu(this, skyPhotoBtn)
        pm.menu.add(0, 0, 0, "No sky photo")
        keys.forEachIndexed { i, _ -> pm.menu.add(0, 100 + i, 1 + i, "Photo " + (i + 1)) }
        pm.menu.add(0, 1, 1000, "Import new photo...")
        pm.setOnMenuItemClickListener { item ->
            when {
                item.itemId == 0 -> { st.photo = ""; editor.refresh() }
                item.itemId == 1 -> { photoForSky = true; photoForSelected = false; pickPhoto() }
                item.itemId >= 100 -> { st.photo = keys[item.itemId - 100]; editor.refresh() }
            }
            true
        }
        pm.show()
    }

    private fun pickPhoto() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        startActivityForResult(i, reqPhoto)
    }

    private fun showPhotoMenu() {
        val o = matTarget()
        if (o == null) { toast("Select a cube, pyramid, sphere or model first"); return }
        val keys = photoKeys()
        val pm = PopupMenu(this, photoBtn)
        pm.menu.add(0, 0, 0, "No photo")
        keys.forEachIndexed { i, _ -> pm.menu.add(0, 100 + i, 1 + i, "Photo " + (i + 1)) }
        pm.menu.add(0, 1, 1000, "Import new photo...")
        pm.setOnMenuItemClickListener { item ->
            when {
                item.itemId == 0 -> { o.photo = ""; editor.refresh() }
                item.itemId == 1 -> { photoForSelected = true; photoForSky = false; pickPhoto() }
                item.itemId >= 100 -> { o.photo = keys[item.itemId - 100]; editor.refresh() }
            }
            true
        }
        pm.show()
    }

    /** Copies the picked picture into app storage (rotated upright, at most 1280 px) and shows it on an object. */
    private fun importPhoto(uri: Uri) {
        toast("Importing photo ...")
        Thread {
            try {
                val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
                var bmp = BitmapFactory.decodeByteArray(
                    bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }
                ) ?: throw IllegalArgumentException("Not a picture")
                val orient = try {
                    ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                    )
                } catch (e: Throwable) { ExifInterface.ORIENTATION_NORMAL }
                val deg = when (orient) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
                if (deg != 0f) {
                    val m = android.graphics.Matrix()
                    m.postRotate(deg)
                    bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                }
                val longest = maxOf(bmp.width, bmp.height)
                if (longest > 1280) {
                    val k = 1280f / longest
                    bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true)
                }
                val key = System.currentTimeMillis().toString() + "_photo.jpg"
                FileOutputStream(File(photosDir(), key)).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                val aspect = bmp.width.toFloat() / bmp.height
                runOnUiThread {
                    val sel = matTarget()
                    val skyObj = scene.objects.firstOrNull { it.shape == Shape.SETTINGS }
                    if (photoForSky && skyObj != null) {
                        skyObj.photo = key
                        toast("Sky photo set")
                    } else if (photoForSelected && sel != null) {
                        sel.photo = key
                        toast("Photo applied")
                    } else {
                        scene.addPhoto(key, aspect)
                        toast("Photo added as a panel. Resize it in Inspector.")
                    }
                    photoForSelected = false
                    photoForSky = false
                    editor.refresh()
                }
            } catch (e: Throwable) {
                runOnUiThread { toast("Photo import failed: " + e.message) }
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
        val photoKeys = scene.objects.map { it.photo }.filter { it.isNotEmpty() }.toSet()
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
                        for (k in photoKeys) {
                            val f = File(photosDir(), k)
                            if (f.exists()) {
                                z.putNextEntry(ZipEntry("photos/" + k))
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
                        else if (e.name.startsWith("photos/")) File(photosDir(), name).writeBytes(data)
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
            if (!sensorInit) {
                // the way you hold the phone when Play starts counts as "level"
                fax = e.values[0]; fay = e.values[1]; faz = e.values[2]
                bax = fax; bay = fay; baz = faz
                sensorInit = true
            } else {
                fax += 0.15f * (e.values[0] - fax)
                fay += 0.15f * (e.values[1] - fay)
                faz += 0.15f * (e.values[2] - faz)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onPause() { stopPlay(); autoSave(); super.onPause(); editor.onPause() }
    override fun onResume() { super.onResume(); editor.onResume() }
}
