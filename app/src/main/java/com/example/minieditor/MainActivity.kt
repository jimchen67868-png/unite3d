package com.example.minieditor

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Locale

class MainActivity : Activity() {
    private val scene = Scene()
    private lateinit var editor: EditorView
    private lateinit var info: TextView
    private val modeButtons = HashMap<Mode, Button>()
    private lateinit var yButton: Button

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun btn(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(Color.WHITE)
        setBackgroundColor(0xFF444444.toInt())
        minWidth = 0; minHeight = 0
        setPadding(dp(4), dp(8), dp(4), dp(8))
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also {
            it.setMargins(dp(2), dp(2), dp(2), dp(2))
        }
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: Button) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        editor = EditorView(this, scene)
        info = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 12f
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }

        modeButtons[Mode.MOVE] = btn("Move") { setMode(Mode.MOVE) }
        modeButtons[Mode.ROTATE] = btn("Rotate") { setMode(Mode.ROTATE) }
        modeButtons[Mode.SCALE] = btn("Scale") { setMode(Mode.SCALE) }
        yButton = btn("Y: off") {
            editor.yLock = !editor.yLock
            refreshButtons()
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xAA000000.toInt())
            addView(info)
            addView(row(
                btn("+Cube") { scene.add(Shape.CUBE); editor.refresh() },
                btn("+Pyramid") { scene.add(Shape.PYRAMID); editor.refresh() },
                btn("+Sphere") { scene.add(Shape.SPHERE); editor.refresh() },
                btn("Delete") { scene.removeSelected(); editor.refresh() }
            ))
            addView(row(modeButtons[Mode.MOVE]!!, modeButtons[Mode.ROTATE]!!, modeButtons[Mode.SCALE]!!, yButton))
            addView(row(
                btn("Save") { save() },
                btn("Load") { load() },
                btn("Clear") { scene.objects.clear(); scene.selected = null; editor.refresh() }
            ))
        }

        val root = FrameLayout(this)
        root.addView(editor, FrameLayout.LayoutParams(-1, -1))
        root.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)

        editor.onChange = { updateInfo() }
        scene.add(Shape.CUBE)
        setMode(Mode.MOVE)
    }

    private fun setMode(m: Mode) {
        editor.mode = m
        refreshButtons()
    }

    private fun refreshButtons() {
        for ((m, b) in modeButtons) b.setBackgroundColor(if (editor.mode == m) 0xFF3D7BFF.toInt() else 0xFF444444.toInt())
        yButton.text = if (editor.yLock) "Y: on" else "Y: off"
        yButton.setBackgroundColor(if (editor.yLock) 0xFF3D7BFF.toInt() else 0xFF444444.toInt())
        updateInfo()
    }

    private fun updateInfo() {
        val o = scene.selected
        info.text = if (o == null) {
            "Nothing selected - drag to orbit, pinch to zoom, tap an object to select"
        } else String.format(
            Locale.US, "%s  pos(%.1f, %.1f, %.1f)  rot(%.0f, %.0f)  scale %.2f",
            o.shape.label, o.x, o.y, o.z, o.rx, o.ry, o.scale
        )
    }

    private fun save() {
        File(filesDir, "scene.json").writeText(scene.toJson())
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
    }

    private fun load() {
        try {
            scene.load(File(filesDir, "scene.json").readText())
            editor.refresh()
            Toast.makeText(this, "Loaded", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "No saved scene", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onPause() { super.onPause(); editor.onPause() }
    override fun onResume() { super.onResume(); editor.onResume() }
}
