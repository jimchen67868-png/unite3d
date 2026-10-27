package com.example.minieditor

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.Locale

class MainActivity : Activity() {
    private enum class Panel { NONE, HIER, INSPECT }

    private val scene = Scene()
    private lateinit var editor: EditorView
    private lateinit var root: FrameLayout
    private lateinit var info: TextView
    private val modeButtons = HashMap<Mode, Button>()
    private lateinit var yButton: Button

    private lateinit var topPanel: LinearLayout
    private lateinit var hierarchyView: LinearLayout
    private lateinit var hierList: LinearLayout
    private lateinit var inspectorView: LinearLayout
    private lateinit var nameField: EditText
    private val fields = ArrayList<EditText>()

    private var panel = Panel.NONE
    private var lastCount = -1
    private var lastSel: SceneObject? = null
    private val modelCache = HashMap<String, ModelData>()
    private val reqImport = 42
    private val reqExport = 43
    private val reqOpen = 44
    private val reqObj = 45

    private val swatchColors = intArrayOf(
        0xFFE65A4D.toInt(), 0xFF4DB373.toInt(), 0xFF4D8CE6.toInt(),
        0xFFF2BF40.toInt(), 0xFFB373D9.toInt(), 0xFFBFBFBF.toInt()
    )

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ---------- UI helpers ----------

    private fun btn(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(Color.WHITE)
        setBackgroundColor(0xFF444444.toInt())
        minWidth = 0; minHeight = 0
        setPadding(dp(4), dp(8), dp(4), dp(8))
        layoutParams = LinearLayout.LayoutParams(0, -2, 1f).also { it.setMargins(dp(2), dp(2), dp(2), dp(2)) }
        setOnClickListener { onClick() }
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
        modeButtons[Mode.MOVE] = btn("Move") { setMode(Mode.MOVE) }
        modeButtons[Mode.ROTATE] = btn("Rotate") { setMode(Mode.ROTATE) }
        modeButtons[Mode.SCALE] = btn("Scale") { setMode(Mode.SCALE) }
        yButton = btn("Y: off") { editor.yLock = !editor.yLock; refreshButtons() }

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xAA000000.toInt())
            addView(info)
            addView(row(
                btn("+Cube") { scene.add(Shape.CUBE); editor.refresh() },
                btn("+Pyramid") { scene.add(Shape.PYRAMID); editor.refresh() },
                btn("+Sphere") { scene.add(Shape.SPHERE); editor.refresh() },
                btn("Import") { pickModel() }
            ))
            addView(row(modeButtons[Mode.MOVE]!!, modeButtons[Mode.ROTATE]!!, modeButtons[Mode.SCALE]!!, yButton))
            addView(row(
                btn("Hierarchy") { setPanel(Panel.HIER) },
                btn("Inspector") { setPanel(Panel.INSPECT) },
                btn("Save") { save() },
                btn("Load") { load() }
            ))
            addView(row(
                btn("Export zip") { exportProject() },
                btn("Open zip") { openProject() },
                btn("Export OBJ") { exportObj() }
            ))
        }

        buildHierarchy()
        buildInspector()
        topPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xCC000000.toInt())
            visibility = View.GONE
            addView(hierarchyView)
            addView(inspectorView)
        }

        root = FrameLayout(this).apply { isFocusable = true; isFocusableInTouchMode = true }
        root.addView(editor, FrameLayout.LayoutParams(-1, -1))
        root.addView(topPanel, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)

        editor.onChange = { updateInfo() }
        scene.add(Shape.CUBE)
        setMode(Mode.MOVE)
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
                btn("Duplicate") { scene.duplicate(); editor.refresh() },
                btn("Delete") { scene.removeSelected(); editor.refresh() },
                btn("Clear all") { scene.objects.clear(); scene.selected = null; editor.refresh() }
            ))
            addView(scroll)
        }
    }

    private fun rebuildHierarchy() {
        hierList.removeAllViews()
        for (o in scene.objects) {
            val sel = o === scene.selected
            hierList.addView(TextView(this).apply {
                text = (if (sel) "▶ " else "    ") + o.name
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
        info.text = (o?.name ?: "Nothing selected") + "  |  " + scene.objects.size + " objects  |  drag: " +
            editor.mode.name.lowercase()
        if (panel == Panel.HIER && (scene.objects.size != lastCount || scene.selected !== lastSel)) rebuildHierarchy()
        if (panel == Panel.INSPECT) refreshInspector()
    }

    // ---------- Save / load ----------

    private fun modelsDir() = File(filesDir, "models").apply { mkdirs() }

    private fun loadModel(key: String): ModelData? = modelCache[key] ?: try {
        ModelData(key, ModelLoader.load(key, File(modelsDir(), key).readBytes())).also { modelCache[key] = it }
    } catch (e: Exception) { null }

    private fun save() {
        File(filesDir, "scene.json").writeText(scene.toJson())
        toast("Saved")
    }

    private fun load() {
        try {
            scene.load(File(filesDir, "scene.json").readText()) { loadModel(it) }
            editor.refresh()
            toast("Loaded")
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

    // ---------- Export / open (scene + models as a .zip in any folder you choose) ----------

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

    // ---------- Export as .obj (selected object, or everything if nothing is selected) ----------

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

    override fun onPause() { super.onPause(); editor.onPause() }
    override fun onResume() { super.onResume(); editor.onResume() }
}
