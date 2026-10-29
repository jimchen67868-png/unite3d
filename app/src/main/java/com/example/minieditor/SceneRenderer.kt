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
    @Volatile var fov = 50f
    @Volatile var playing = false
    @Volatile var axisMode = 0
    @Volatile var editorBright = false
    @Volatile var previewCam = false
    @Volatile var onFrame: (() -> Unit)? = null
    @Volatile var fpMode = false
    @Volatile var fpX = 0f
    @Volatile var fpY = 0f
    @Volatile var fpZ = 0f
    @Volatile var fpHide: SceneObject? = null
    val target = floatArrayOf(0f, 0.5f, 0f)

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
    private var uSpec = 0; private var uGloss = 0; private var uEmit = 0; private var uPattern = 0; private var uObjScale = 0
    private lateinit var meshes: Map<Shape, Mesh>
    private lateinit var grid: Mesh
    private lateinit var gizmo: Mesh
    private lateinit var circle: Mesh
    private val modelMeshes = HashMap<String, Mesh>()

    private val vertexSrc = """
        uniform mat4 uMVP;
        uniform mat4 uModel;
        attribute vec3 aPos;
        attribute vec3 aNorm;
        varying vec3 vN;
        varying vec3 vW;
        varying vec3 vL;
        void main() {
            vN = (uModel * vec4(aNorm, 0.0)).xyz;
            vW = (uModel * vec4(aPos, 1.0)).xyz;
            vL = aPos;
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
        uniform float uSpec;
        uniform float uGloss;
        uniform float uEmit;
        uniform float uPattern;
        uniform vec3 uObjScale;
        varying vec3 vN;
        varying vec3 vW;
        varying vec3 vL;
        void main() {
            if (uLit < 0.5) { gl_FragColor = uColor; return; }
            vec3 n = normalize(vN);
            vec3 v = normalize(uEye - vW);
            float shin = 4.0 + uGloss * 120.0;
            vec3 light = uAmbient;
            vec3 spec = vec3(0.0);
            float sd = max(dot(n, uSunDir), 0.0);
            light += uSunCol * sd;
            if (sd > 0.0) {
                vec3 h = normalize(uSunDir + v);
                spec += uSunCol * pow(max(dot(n, h), 0.0), shin);
            }
            for (int i = 0; i < 4; i++) {
                if (i >= uLCount) break;
                vec3 d = uLPos[i] - vW;
                float dist = max(length(d), 0.001);
                vec3 l = d / dist;
                float att = clamp(1.0 - dist / uLCol[i].w, 0.0, 1.0);
                att = att * att;
                float nl = max(dot(n, l), 0.0);
                light += uLCol[i].rgb * nl * att;
                if (nl > 0.0) {
                    vec3 h = normalize(l + v);
                    spec += uLCol[i].rgb * att * pow(max(dot(n, h), 0.0), shin);
                }
            }
            vec3 base = uColor.rgb;
            if (uPattern > 0.5) {
                vec3 q = vL * uObjScale * 2.0;
                float k = 0.0;
                if (uPattern < 1.5) {
                    k = mod(floor(q.x) + floor(q.y) + floor(q.z), 2.0);
                } else {
                    k = step(0.5, fract((q.x + q.y + q.z) * 0.5));
                }
                base = mix(base, base * 0.45, k);
            }
            vec3 c = base * light + spec * uSpec + base * uEmit;
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
        uSpec = GLES20.glGetUniformLocation(prog, "uSpec")
        uGloss = GLES20.glGetUniformLocation(prog, "uGloss")
        uEmit = GLES20.glGetUniformLocation(prog, "uEmit")
        uPattern = GLES20.glGetUniformLocation(prog, "uPattern")
        uObjScale = GLES20.glGetUniformLocation(prog, "uObjScale")
        meshes = mapOf(Shape.CUBE to Meshes.cube(), Shape.PYRAMID to Meshes.pyramid(), Shape.SPHERE to Meshes.sphere())
        grid = Meshes.grid()
        gizmo = Meshes.gizmo()
        circle = Meshes.circle()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        synchronized(lock) { w = max(width, 1); h = max(height, 1) }
    }

    private fun updateMatrices() {
        synchronized(lock) {
            val cam = if (!fpMode && (playing || previewCam)) scene.objects.firstOrNull { it.shape == Shape.CAMERA } else null
            Matrix.perspectiveM(proj, 0, if (cam != null) cam.sx.coerceIn(20f, 120f) else fov, w.toFloat() / h, 0.1f, 100f)
            val p = Math.toRadians(pitch.toDouble())
            val y = Math.toRadians(yaw.toDouble())
            if (fpMode) {
                val dx = (-cos(p) * sin(y)).toFloat()
                val dy = (-sin(p)).toFloat()
                val dz = (-cos(p) * cos(y)).toFloat()
                Matrix.setLookAtM(view, 0, fpX, fpY, fpZ, fpX + dx, fpY + dy, fpZ + dz, 0f, 1f, 0f)
                eye[0] = fpX; eye[1] = fpY; eye[2] = fpZ
            } else if (cam != null) {
                val cp = Math.toRadians(cam.rx.toDouble())
                val cy = Math.toRadians(cam.ry.toDouble())
                val cdx = (-cos(cp) * sin(cy)).toFloat()
                val cdy = (-sin(cp)).toFloat()
                val cdz = (-cos(cp) * cos(cy)).toFloat()
                Matrix.setLookAtM(view, 0, cam.x, cam.y, cam.z, cam.x + cdx, cam.y + cdy, cam.z + cdz, 0f, 1f, 0f)
                eye[0] = cam.x; eye[1] = cam.y; eye[2] = cam.z
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

    /** World point -> screen pixels (x, y), or null if it is behind the camera. */
    fun project(wx: Float, wy: Float, wz: Float): FloatArray? = synchronized(lock) {
        updateMatrices()
        val out = FloatArray(4)
        Matrix.multiplyMV(out, 0, vp, 0, floatArrayOf(wx, wy, wz, 1f), 0)
        if (out[3] <= 0.001f) null
        else floatArrayOf(
            (out[0] / out[3] * 0.5f + 0.5f) * w,
            (1f - (out[1] / out[3] * 0.5f + 0.5f)) * h
        )
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
        first: Int = 0, n: Int = m.count, alpha: Float = 1f
    ) {
        Matrix.multiplyMM(mvp, 0, vpc, 0, modelM, 0)
        GLES20.glUniformMatrix4fv(uMVP, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(uModel, 1, false, modelM, 0)
        GLES20.glUniform4f(uColor, color[0], color[1], color[2], alpha)
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
        // Editor-only "Bright" mode: ignore the scene's dark ambient/sun/fog so you can see what you are editing.
        val bright = editorBright && !playing
        if (bright) {
            amb = 0.6f
            sd = floatArrayOf(0.4f, 1f, 0.6f)
            sc = floatArrayOf(0.7f, 0.7f, 0.7f)
        }
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
        GLES20.glUniform1f(uFogEnd, if (st != null && !bright) st.y.coerceAtLeast(0f) else 0f)
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
        if (o.shape == Shape.LIGHT) {
            // range rings: show how far the light reaches
            val rr = o.sy.coerceAtLeast(0.5f)
            GLES20.glLineWidth(2f)
            for (axis in 0..2) {
                Matrix.setIdentityM(model, 0)
                Matrix.translateM(model, 0, o.x, o.y, o.z)
                if (axis == 1) Matrix.rotateM(model, 0, 90f, 1f, 0f, 0f)
                if (axis == 2) Matrix.rotateM(model, 0, 90f, 0f, 0f, 1f)
                Matrix.scaleM(model, 0, rr, rr, rr)
                drawMesh(circle, vpc, model, c, false)
            }
        }
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

    /** Editor-only camera marker: a small body plus a line showing where it looks. */
    private fun drawCameraMarker(o: SceneObject, vpc: FloatArray) {
        val dx = o.x - curEye[0]
        val dy = o.y - curEye[1]
        val dz = o.z - curEye[2]
        val d = sqrt(dx * dx + dy * dy + dz * dz)
        val k = max(0.3f, d * 0.035f)
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, o.x, o.y, o.z)
        Matrix.rotateM(model, 0, o.ry, 0f, 1f, 0f)
        Matrix.rotateM(model, 0, -o.rx, 1f, 0f, 0f)
        Matrix.scaleM(model, 0, k * 1.3f, k * 0.9f, k * 1.6f)
        drawMesh(meshes.getValue(Shape.CUBE), vpc, model, floatArrayOf(0.85f, 0.88f, 0.95f), false)
        val g = max(1.5f, d * 0.15f)
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, o.x, o.y, o.z)
        Matrix.rotateM(model, 0, o.ry, 0f, 1f, 0f)
        Matrix.rotateM(model, 0, -o.rx, 1f, 0f, 0f)
        Matrix.scaleM(model, 0, g, g, -g)
        GLES20.glLineWidth(4f)
        drawMesh(gizmo, vpc, model, floatArrayOf(0.4f, 0.8f, 1f), false, 4, 2)
    }

    private fun setMaterial(o: SceneObject) {
        GLES20.glUniform1f(uSpec, o.spec.coerceIn(0f, 1f))
        GLES20.glUniform1f(uGloss, o.gloss.coerceIn(0f, 1f))
        GLES20.glUniform1f(uEmit, o.emit.coerceIn(0f, 1f))
        GLES20.glUniform1f(uPattern, o.pattern.toFloat())
        GLES20.glUniform3f(uObjScale, o.sx, o.sy, o.sz)
    }

    private fun drawObject(o: SceneObject, vpc: FloatArray, sel: SceneObject?, alpha: Float) {
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, o.x, o.y, o.z)
        Matrix.rotateM(model, 0, o.ry, 0f, 1f, 0f)
        Matrix.rotateM(model, 0, o.rx, 1f, 0f, 0f)
        Matrix.rotateM(model, 0, o.rz, 0f, 0f, 1f)
        Matrix.scaleM(model, 0, o.sx, o.sy, o.sz)
        val c = if (o === sel)
            floatArrayOf(o.color[0] * 0.6f + 0.4f, o.color[1] * 0.6f + 0.4f, o.color[2] * 0.6f + 0.4f)
        else o.color
        setMaterial(o)
        val mesh = meshFor(o)
        drawMesh(mesh, vpc, model, c, true, 0, mesh.count, alpha)
    }

    private val shM = FloatArray(16)
    private val shObj = FloatArray(16)
    private val shOut = FloatArray(16)

    /** Flattens geometry onto the ground plane y = h, away from a directional light that travels along (dx, dy, dz). */
    private fun dirMatrix(m: FloatArray, dx: Float, dy: Float, dz: Float, h: Float) {
        Matrix.setIdentityM(m, 0)
        m[5] = 0f
        m[4] = -dx / dy
        m[6] = -dz / dy
        m[12] = dx * h / dy
        m[13] = h
        m[14] = dz * h / dy
    }

    /** Flattens geometry onto the ground plane y = h as seen from a point light at (lx, ly, lz). */
    private fun pointMatrix(m: FloatArray, lx: Float, ly: Float, lz: Float, h: Float) {
        val dot = ly - h
        m[0] = dot; m[1] = 0f; m[2] = 0f; m[3] = 0f
        m[4] = -lx; m[5] = -h; m[6] = -lz; m[7] = -1f
        m[8] = 0f; m[9] = 0f; m[10] = dot; m[11] = 0f
        m[12] = h * lx; m[13] = h * ly; m[14] = h * lz; m[15] = dot + h
    }

    private fun drawShadowSet(casters: List<SceneObject>, vpc: FloatArray, black: FloatArray, alpha: Float, skip: (SceneObject) -> Boolean) {
        for (o in casters) {
            if (skip(o)) continue
            Matrix.setIdentityM(shObj, 0)
            Matrix.translateM(shObj, 0, o.x, o.y, o.z)
            Matrix.rotateM(shObj, 0, o.ry, 0f, 1f, 0f)
            Matrix.rotateM(shObj, 0, o.rx, 1f, 0f, 0f)
            Matrix.rotateM(shObj, 0, o.rz, 0f, 0f, 1f)
            Matrix.scaleM(shObj, 0, o.sx, o.sy, o.sz)
            Matrix.multiplyMM(shOut, 0, shM, 0, shObj, 0)
            drawMesh(meshFor(o), vpc, shOut, black, false, 0, meshFor(o).count, alpha)
        }
    }

    /**
     * Soft ground shadows: every object that casts a shadow is flattened onto the ground (Settings: ground height)
     * once for the sun and once for each point light. Strength = Settings shadow value (0 = off, default 0.5).
     */
    private fun drawShadows(vpc: FloatArray) {
        val st = scene.objects.firstOrNull { it.shape == Shape.SETTINGS }
        val strength = if (st != null) st.ry.coerceIn(0f, 1f) else 0.5f
        if (strength <= 0.01f) return
        val gh = (if (st != null) st.rz else 0f) + 0.01f
        val casters = scene.objects.filter { o ->
            o.shadow && !o.shape.isScreen && !o.shape.isLight && o.shape != Shape.SETTINGS && o.shape != Shape.CAMERA &&
                o.y + 0.5f * o.sy > gh + 0.04f
        }
        if (casters.isEmpty()) return
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        val black = floatArrayOf(0f, 0f, 0f)

        // sun (the Sun object, or the built-in sun)
        val sun = scene.objects.firstOrNull { it.shape == Shape.SUN }
        var lx = 0.4f
        var ly = 1f
        var lz = 0.6f
        var sunPower = 1f
        if (sun != null) {
            val t = Math.toRadians(sun.rx.toDouble())
            val p = Math.toRadians(sun.ry.toDouble())
            lx = (sin(t) * sin(p)).toFloat()
            ly = cos(t).toFloat()
            lz = (sin(t) * cos(p)).toFloat()
            sunPower = sun.sx
        }
        val ll = sqrt(lx * lx + ly * ly + lz * lz)
        if (ll > 0f && ly / ll > 0.08f && sunPower > 0.05f) {
            dirMatrix(shM, -lx / ll, -ly / ll, -lz / ll, gh)
            drawShadowSet(casters, vpc, black, strength * 0.6f * minOf(1f, sunPower)) { false }
        }

        // point lights
        for (l in scene.objects.filter { it.shape == Shape.LIGHT }.take(4)) {
            if (l.y < gh + 0.3f || l.sx < 0.05f) continue
            pointMatrix(shM, l.x, l.y, l.z, gh)
            val a = strength * 0.6f * minOf(1f, l.sx / 1.5f)
            drawShadowSet(casters, vpc, black, a) { o ->
                val dx = o.x - l.x
                val dy = o.y - l.y
                val dz = o.z - l.z
                o.y >= l.y - 0.1f || dx * dx + dy * dy + dz * dz > l.sy * l.sy
            }
        }
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun meshFor(o: SceneObject): Mesh {
        val md = o.model
        return if (md != null) modelMeshes.getOrPut(md.key) { Mesh(md.data, GLES20.GL_TRIANGLES) }
        else meshes.getValue(o.shape)
    }

    override fun onDrawFrame(gl: GL10?) {
        onFrame?.invoke()
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

        // Objects: solid ones first, then see-through ones (far to near) so they blend correctly
        val sel = scene.selected
        val seeThrough = ArrayList<SceneObject>()
        for (o in scene.objects) {
            if (o.shape.isLight || o.shape == Shape.CAMERA) continue
            if (o.shape.isScreen || o.shape == Shape.SETTINGS || o === fpHide) continue
            if (o.opacity < 0.99f) { seeThrough.add(o); continue }
            drawObject(o, vpc, sel, 1f)
        }
        if (seeThrough.isNotEmpty()) {
            seeThrough.sortByDescending { o ->
                (o.x - eyeC[0]) * (o.x - eyeC[0]) + (o.y - eyeC[1]) * (o.y - eyeC[1]) + (o.z - eyeC[2]) * (o.z - eyeC[2])
            }
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glDepthMask(false)
            for (o in seeThrough) drawObject(o, vpc, sel, o.opacity.coerceIn(0.05f, 1f))
            GLES20.glDepthMask(true)
            GLES20.glDisable(GLES20.GL_BLEND)
        }

        drawShadows(vpc)

        // Light markers: drawn on top of everything, kept a constant size on screen so they stay visible when zoomed out
        if (!playing) {
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            for (o in scene.objects) {
                if (o.shape.isLight) drawLightMarker(o, vpc)
                else if (o.shape == Shape.CAMERA) drawCameraMarker(o, vpc)
            }
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        }

        // Gizmo on selection
        if (sel != null && !sel.shape.isScreen && sel.shape != Shape.SETTINGS) {
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glLineWidth(4f)
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, sel.x, sel.y, sel.z)
            val gx = sel.x - curEye[0]
            val gy = sel.y - curEye[1]
            val gz = sel.z - curEye[2]
            val s = max(0.5f, sqrt(gx * gx + gy * gy + gz * gz) * 0.14f)
            Matrix.scaleM(model, 0, s, s, s)
            GLES20.glLineWidth(if (axisMode == 1) 10f else 4f)
            drawMesh(gizmo, vpc, model, floatArrayOf(1f, 0.25f, 0.25f), false, 0, 2)
            GLES20.glLineWidth(if (axisMode == 2) 10f else 4f)
            drawMesh(gizmo, vpc, model, floatArrayOf(0.3f, 1f, 0.3f), false, 2, 2)
            GLES20.glLineWidth(if (axisMode == 3) 10f else 4f)
            drawMesh(gizmo, vpc, model, floatArrayOf(0.35f, 0.5f, 1f), false, 4, 2)
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        }
    }
}
