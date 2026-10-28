package com.example.minieditor

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

class SceneRenderer(private val scene: Scene) : GLSurfaceView.Renderer {
    @Volatile var yaw = 45f
    @Volatile var pitch = 30f
    @Volatile var dist = 9f
    @Volatile var playing = false
    @Volatile var fpMode = false
    @Volatile var fpX = 0f
    @Volatile var fpY = 0f
    @Volatile var fpZ = 0f
    @Volatile var fpHide: SceneObject? = null
    private val target = floatArrayOf(0f, 0.5f, 0f)

    private val lock = Any()
    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val vp = FloatArray(16)
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)
    private val eye = FloatArray(3)
    private val curEye = FloatArray(3)
    private var w = 1
    private var h = 1

    private var prog = 0
    private var aPos = 0; private var aNorm = 0
    private var uMVP = 0; private var uModel = 0; private var uColor = 0; private var uLit = 0
    private var uSunDir = 0; private var uSunCol = 0; private var uAmbient = 0
    private var uLPos = 0; private var uLCol = 0; private var uLCount = 0
    private var uEye = 0; private var uFogColor = 0; private var uFogEnd = 0
    private lateinit var meshes: Map<Shape, Mesh>
    private lateinit var grid: Mesh
    private lateinit var gizmo: Mesh
    private val modelMeshes = HashMap<String, Mesh>()

    private val vertexSrc = """
        uniform mat4 uMVP;
        uniform mat4 uModel;
        attribute vec3 aPos;
        attribute vec3 aNorm;
        varying vec3 vN;
        varying vec3 vW;
        void main() {
            vN = (uModel * vec4(aNorm, 0.0)).xyz;
            vW = (uModel * vec4(aPos, 1.0)).xyz;
            gl_Position = uMVP * vec4(aPos, 1.0);
        }
    """.trimIndent()

    private val fragmentSrc = """
        #ifdef GL_FRAGMENT_PRECISION_HIGH
        precision highp float;
        #else
        precision mediump float;
        #endif
        uniform vec4 uColor;
        uniform float uLit;
        uniform vec3 uSunDir;
        uniform vec3 uSunCol;
        uniform vec3 uAmbient;
        uniform vec3 uLPos[4];
        uniform vec4 uLCol[4];
        uniform int uLCount;
        uniform vec3 uEye;
        uniform vec3 uFogColor;
        uniform float uFogEnd;
        varying vec3 vN;
        varying vec3 vW;
        void main() {
            if (uLit < 0.5) { gl_FragColor = uColor; return; }
            vec3 n = normalize(vN);
            vec3 light = uAmbient + uSunCol * max(dot(n, uSunDir), 0.0);
            for (int i = 0; i < 4; i++) {
                if (i >= uLCount) break;
                vec3 d = uLPos[i] - vW;
                float dist = max(length(d), 0.001);
                float att = clamp(1.0 - dist / uLCol[i].w, 0.0, 1.0);
                att = att * att;
                light += uLCol[i].rgb * max(dot(n, d / dist), 0.0) * att;
            }
            vec3 c = uColor.rgb * light;
            if (uFogEnd > 0.0) {
                float fd = length(vW - uEye);
                float ff = clamp((fd - uFogEnd * 0.3) / (uFogEnd * 0.7), 0.0, 1.0);
                c = mix(c, uFogColor, ff);
            }
            gl_FragColor = vec4(c, uColor.a);
        }
    """.trimIndent()

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        return s
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.15f, 0.16f, 0.19f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, compile(GLES20.GL_VERTEX_SHADER, vertexSrc))
        GLES20.glAttachShader(prog, compile(GLES20.GL_FRAGMENT_SHADER, fragmentSrc))
        GLES20.glLinkProgram(prog)
        aPos = GLES20.glGetAttribLocation(prog, "aPos")
        aNorm = GLES20.glGetAttribLocation(prog, "aNorm")
        uMVP = GLES20.glGetUniformLocation(prog, "uMVP")
        uModel = GLES20.glGetUniformLocation(prog, "uModel")
        uColor = GLES20.glGetUniformLocation(prog, "uColor")
        uLit = GLES20.glGetUniformLocation(prog, "uLit")
        uSunDir = GLES20.glGetUniformLocation(prog, "uSunDir")
        uSunCol = GLES20.glGetUniformLocation(prog, "uSunCol")
        uAmbient = GLES20.glGetUniformLocation(prog, "uAmbient")
        uLPos = GLES20.glGetUniformLocation(prog, "uLPos[0]")
        uLCol = GLES20.glGetUniformLocation(prog, "uLCol[0]")
        uLCount = GLES20.glGetUniformLocation(prog, "uLCount")
        uEye = GLES20.glGetUniformLocation(prog, "uEye")
        uFogColor = GLES20.glGetUniformLocation(prog, "uFogColor")
        uFogEnd = GLES20.glGetUniformLocation(prog, "uFogEnd")
        meshes = mapOf(Shape.CUBE to Meshes.cube(), Shape.PYRAMID to Meshes.pyramid(), Shape.SPHERE to Meshes.sphere())
        grid = Meshes.grid()
        gizmo = Meshes.gizmo()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        synchronized(lock) { w = max(width, 1); h = max(height, 1) }
    }

    private fun updateMatrices() {
        synchronized(lock) {
            Matrix.perspectiveM(proj, 0, 50f, w.toFloat() / h, 0.1f, 100f)
            val p = Math.toRadians(pitch.toDouble())
            val y = Math.toRadians(yaw.toDouble())
            if (fpMode) {
                val dx = (-cos(p) * sin(y)).toFloat()
                val dy = (-sin(p)).toFloat()
                val dz = (-cos(p) * cos(y)).toFloat()
                Matrix.setLookAtM(view, 0, fpX, fpY, fpZ, fpX + dx, fpY + dy, fpZ + dz, 0f, 1f, 0f)
                eye[0] = fpX; eye[1] = fpY; eye[2] = fpZ
            } else {
                val ex = target[0] + dist * (cos(p) * sin(y)).toFloat()
                val ey = target[1] + dist * sin(p).toFloat()
                val ez = target[2] + dist * (cos(p) * cos(y)).toFloat()
                Matrix.setLookAtM(view, 0, ex, ey, ez, target[0], target[1], target[2], 0f, 1f, 0f)
                eye[0] = ex; eye[1] = ey; eye[2] = ez
            }
            Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
        }
    }

    /** Screen point -> world ray [ox, oy, oz, dx, dy, dz]. Safe to call from the UI thread. */
    fun ray(sx: Float, sy: Float): FloatArray = synchronized(lock) {
        updateMatrices()
        val inv = FloatArray(16)
        Matrix.invertM(inv, 0, vp, 0)
        val nx = 2f * sx / w - 1f
        val ny = 1f - 2f * sy / h
        val a = unproject(inv, nx, ny, -1f)
        val b = unproject(inv, nx, ny, 1f)
        val dx = b[0] - a[0]; val dy = b[1] - a[1]; val dz = b[2] - a[2]
        val l = sqrt(dx * dx + dy * dy + dz * dz)
        floatArrayOf(a[0], a[1], a[2], dx / l, dy / l, dz / l)
    }

    private fun unproject(inv: FloatArray, x: Float, y: Float, z: Float): FloatArray {
        val out = FloatArray(4)
        Matrix.multiplyMV(out, 0, inv, 0, floatArrayOf(x, y, z, 1f), 0)
        return floatArrayOf(out[0] / out[3], out[1] / out[3], out[2] / out[3])
    }

    private fun drawMesh(
        m: Mesh, vpc: FloatArray, modelM: FloatArray, color: FloatArray, lit: Boolean,
        first: Int = 0, n: Int = m.count
    ) {
        Matrix.multiplyMM(mvp, 0, vpc, 0, modelM, 0)
        GLES20.glUniformMatrix4fv(uMVP, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(uModel, 1, false, modelM, 0)
        GLES20.glUniform4f(uColor, color[0], color[1], color[2], 1f)
        GLES20.glUniform1f(uLit, if (lit) 1f else 0f)
        m.draw(aPos, aNorm, first, n)
    }

    /** Sun if present (rx/ry = direction, sx = intensity, sy = ambient), else the built-in sun. Up to 4 point lights. */
    private fun setLights(eyeP: FloatArray) {
        var sd = floatArrayOf(0.4f, 1f, 0.6f)
        var sc = floatArrayOf(0.7f, 0.7f, 0.7f)
        var amb = 0.3f
        val sun = scene.objects.firstOrNull { it.shape == Shape.SUN }
        if (sun != null) {
            val t = Math.toRadians(sun.rx.toDouble())
            val p = Math.toRadians(sun.ry.toDouble())
            sd = floatArrayOf((sin(t) * sin(p)).toFloat(), cos(t).toFloat(), (sin(t) * cos(p)).toFloat())
            val k = 0.7f * sun.sx
            sc = floatArrayOf(sun.color[0] * k, sun.color[1] * k, sun.color[2] * k)
            amb = sun.sy
        }
        val st = scene.objects.firstOrNull { it.shape == Shape.SETTINGS }
        if (st != null) amb = st.z.coerceIn(0f, 1f)
        var l = sqrt(sd[0] * sd[0] + sd[1] * sd[1] + sd[2] * sd[2])
        if (l <= 0f) l = 1f
        GLES20.glUniform3f(uSunDir, sd[0] / l, sd[1] / l, sd[2] / l)
        GLES20.glUniform3f(uSunCol, sc[0], sc[1], sc[2])
        GLES20.glUniform3f(uAmbient, amb, amb, amb)
        val pts = scene.objects.filter { it.shape == Shape.LIGHT }.take(4)
        val pos = FloatArray(12)
        val col = FloatArray(16)
        for ((i, o) in pts.withIndex()) {
            pos[i * 3] = o.x; pos[i * 3 + 1] = o.y; pos[i * 3 + 2] = o.z
            col[i * 4] = o.color[0] * o.sx
            col[i * 4 + 1] = o.color[1] * o.sx
            col[i * 4 + 2] = o.color[2] * o.sx
            col[i * 4 + 3] = o.sy
        }
        GLES20.glUniform3fv(uLPos, 4, pos, 0)
        GLES20.glUniform4fv(uLCol, 4, col, 0)
        GLES20.glUniform1i(uLCount, pts.size)
        val sky = st?.color ?: floatArrayOf(0.15f, 0.16f, 0.19f)
        GLES20.glUniform3f(uFogColor, sky[0], sky[1], sky[2])
        GLES20.glUniform1f(uFogEnd, if (st != null) st.y.coerceAtLeast(0f) else 0f)
        GLES20.glUniform3f(uEye, eyeP[0], eyeP[1], eyeP[2])
    }

    /** Editor-only marker: a glowing ball whose on-screen size stays about the same at any zoom. */
    private fun drawLightMarker(o: SceneObject, vpc: FloatArray) {
        val dx = o.x - curEye[0]
        val dy = o.y - curEye[1]
        val dz = o.z - curEye[2]
        val d = sqrt(dx * dx + dy * dy + dz * dz)
        val k = max(0.3f, d * 0.035f)
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, o.x, o.y, o.z)
        Matrix.scaleM(model, 0, k, k, k)
        val c = floatArrayOf(
            minOf(1f, o.color[0] + 0.2f), minOf(1f, o.color[1] + 0.2f), minOf(1f, o.color[2] + 0.2f)
        )
        drawMesh(meshes.getValue(Shape.SPHERE), vpc, model, c, false)
        if (o.shape == Shape.SUN) {
            val g = max(1f, d * 0.12f)
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, o.x, o.y, o.z)
            Matrix.rotateM(model, 0, o.ry, 0f, 1f, 0f)
            Matrix.rotateM(model, 0, o.rx, 1f, 0f, 0f)
            Matrix.scaleM(model, 0, g, g, g)
            GLES20.glLineWidth(4f)
            drawMesh(gizmo, vpc, model, c, false, 2, 2)
        }
    }

    private fun meshFor(o: SceneObject): Mesh {
        val md = o.model
        return if (md != null) modelMeshes.getOrPut(md.key) { Mesh(md.data, GLES20.GL_TRIANGLES) }
        else meshes.getValue(o.shape)
    }

    override fun onDrawFrame(gl: GL10?) {
        val eyeC = FloatArray(3)
        val vpc = synchronized(lock) { updateMatrices(); System.arraycopy(eye, 0, eyeC, 0, 3); vp.copyOf() }
        val settings = scene.objects.firstOrNull { it.shape == Shape.SETTINGS }
        if (settings != null) GLES20.glClearColor(settings.color[0], settings.color[1], settings.color[2], 1f)
        else GLES20.glClearColor(0.15f, 0.16f, 0.19f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glUseProgram(prog)
        System.arraycopy(eyeC, 0, curEye, 0, 3)
        setLights(eyeC)

        // Grid + axes
        Matrix.setIdentityM(model, 0)
        val showGrid = settings == null || settings.sy >= 0.5f
        GLES20.glLineWidth(1f)
        if (showGrid) drawMesh(grid, vpc, model, floatArrayOf(0.35f, 0.37f, 0.42f), false, 0, grid.count - 4)
        GLES20.glLineWidth(2f)
        if (showGrid) drawMesh(grid, vpc, model, floatArrayOf(0.85f, 0.3f, 0.3f), false, grid.count - 4, 2)
        if (showGrid) drawMesh(grid, vpc, model, floatArrayOf(0.3f, 0.45f, 0.9f), false, grid.count - 2, 2)

        // Objects
        val sel = scene.selected
        for (o in scene.objects) {
            if (o.shape.isLight) continue
            if (o.shape.isScreen || o.shape == Shape.SETTINGS || o === fpHide) continue
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, o.x, o.y, o.z)
            Matrix.rotateM(model, 0, o.ry, 0f, 1f, 0f)
            Matrix.rotateM(model, 0, o.rx, 1f, 0f, 0f)
            Matrix.rotateM(model, 0, o.rz, 0f, 0f, 1f)
            Matrix.scaleM(model, 0, o.sx, o.sy, o.sz)
            val c = if (o === sel)
                floatArrayOf(o.color[0] * 0.6f + 0.4f, o.color[1] * 0.6f + 0.4f, o.color[2] * 0.6f + 0.4f)
            else o.color
            drawMesh(meshFor(o), vpc, model, c, true)
        }

        // Light markers: drawn on top of everything, kept a constant size on screen so they stay visible when zoomed out
        if (!playing) {
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            for (o in scene.objects) if (o.shape.isLight) drawLightMarker(o, vpc)
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        }

        // Gizmo on selection
        if (sel != null && !sel.shape.isScreen && sel.shape != Shape.SETTINGS) {
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glLineWidth(4f)
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, sel.x, sel.y, sel.z)
            val s = max(1f, sel.maxScale) * 0.9f
            Matrix.scaleM(model, 0, s, s, s)
            drawMesh(gizmo, vpc, model, floatArrayOf(1f, 0.25f, 0.25f), false, 0, 2)
            drawMesh(gizmo, vpc, model, floatArrayOf(0.3f, 1f, 0.3f), false, 2, 2)
            drawMesh(gizmo, vpc, model, floatArrayOf(0.35f, 0.5f, 1f), false, 4, 2)
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        }
    }
}
