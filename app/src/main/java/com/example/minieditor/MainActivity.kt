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
    private lateinit var fileButton: Button

    private lateinit var topPanel: LinearLayout
    private lateinit var hierarchyView: LinearLayout
    private lateinit var hierList: LinearLayout
    private lateinit var inspectorView: LinearLayout
    private lateinit var scriptView: LinearLayout
    private lateinit var scriptField: EditText
    private lateinit var nameField: EditText
    private val fields = ArrayList<EditText>()
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

    private val swatchColors = intArrayOf(
        0xFFE65A4D.toInt(), 0xFF4DB373.toInt(), 0xFF4D8CE6.toInt(),
        0xFFF2BF40.toInt(), 0xFFB373D9.toInt(), 0xFFBFBFBF.toInt()
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
        yButton = btn("Y: off") { editor.yLock = !editor.yLock; refreshButtons() }
        fileButton = btn("File") { showFileMenu() }
        playButton = btn("Play") { togglePlay() }
        playButton.setBackgroundColor(0xFF2E8B57.toInt())

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
        val jlp = FrameLayout.LayoutParams(dp(150), dp(150), Gravity.BOTTOM or Gravity.START)
        jlp.leftMargin = dp(16)
        root.addView(joystick, jlp)
        bottom.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val want = v.height + dp(12)
            if (jlp.bottomMargin != want) {
                jlp.bottomMargin = want
                joystick.layoutParams = jlp
            }
        }
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
            engine?.let {
                it.ax = ax; it.ay = ay; it.az = az
                it.camYaw = editor.renderer.yaw
                it.joyX = joystick.jx; it.joyY = joystick.jy; it.joyActive = joystick.active
                it.touching = editor.touching
                it.touchX = editor.touchX / maxOf(editor.width, 1)
                it.touchY = editor.touchY / maxOf(editor.height, 1)
                it.update(dt)
            }
            editor.refresh()
            Choreographer.getInstance().postFrameCallback(this)
        }
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
                it.color[0], it.color[1], it.color[2]))
        }
        logLines.clear(); showLog()
        playing = true
        editor.playing = true
        joystick.reset()
        joystick.visibility = View.VISIBLE
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
        joystick.visibility = View.GONE
        Choreographer.getInstance().removeFrameCallback(frameCb)
        engine = null
        for (s in snaps) {
            val o = s.o; val v = s.v
            o.x = v[0]; o.y = v[1]; o.z = v[2]
            o.rx = v[3]; o.ry = v[4]; o.rz = v[5]
            o.sx = v[6]; o.sy = v[7]; o.sz = v[8]
            o.color = floatArrayOf(v[9], v[10], v[11])
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
                "input.touching .x .y (0-1) | input.tiltX .tiltY .moveX .moveZ (tilt) | input.joyX .joyY .joyMoveX .joyMoveZ .joyActive | log(...)"
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
                btn("Duplicate", true) { scene.duplicate(); editor.refresh() },
                btn("Delete", true) { scene.removeSelected(); editor.refresh() },
                btn("Clear all", true) { scene.objects.clear(); scene.selected = null; editor.refresh() }
            ))
            addView(scroll)
        }
    }

    private fun rebuildHierarchy() {
        hierList.removeAllViews()
        for (o in scene.objects) {
            val sel = o === scene.selected
            hierList.addView(TextView(this).apply {
                text = (if (sel) "▶ " else "    ") + o.name + (if (o.script.isNotBlank()) "  {lua}" else "")
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
            layoutParams = LinearLayout.LayoutParams(dp(40), -2)
        })
        for (k in 0 until 3) addView(makeField(start + k))
    }

    private fun swatchRow() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (c in swatchColors) addView(View(this@MainActivity).apply {
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
            addView(swatchRow())
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
        nameField.isEnabled = o != null
        for (f in fields) f.isEnabled = o != null
        if (o == null) {
            if (!nameField.hasFocus()) nameField.setText("")
            for (f in fields) if (!f.hasFocus()) f.setText("")
            return
        }
        if (!nameField.hasFocus()) nameField.setText(o.name)
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
        yButton.text = if (editor.yLock) "Y: on" else "Y: off"
        yButton.setBackgroundColor(if (editor.yLock) 0xFF3D7BFF.toInt() else 0xFF444444.toInt())
        updateInfo()
    }

    private fun updateInfo() {
        val o = scene.selected
        info.text = (if (playing) "PLAYING  |  " else "") + (o?.name ?: "Nothing selected") +
            "  |  " + scene.objects.size + " objects" + (if (playing) "" else "  |  drag: " + editor.mode.name.lowercase())
        if (panel == Panel.HIER && (scene.objects.size != lastCount || scene.selected !== lastSel)) rebuildHierarchy()
        if (panel == Panel.INSPECT) refreshInspector()
        if (panel == Panel.SCRIPT) refreshScript()
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
