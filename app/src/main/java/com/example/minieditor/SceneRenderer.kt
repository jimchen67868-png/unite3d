package com.example.minieditor

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
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
    private var uLPos = 0; private var uLCol = 0; private var uLCount = 0; private var uLVis = 0
    private val litPos = FloatArray(12)
    private var litCount = 0
    private val lightVis = floatArrayOf(1f, 1f, 1f, 1f)
    private val visOnes = floatArrayOf(1f, 1f, 1f, 1f)
    private val visDepth = FloatArray(4 * 6 * 128 * 128)
    private var visKey = ""
    private var visTime = 0L
    private var visReady = false
    private var visActive = false
    private var uEye = 0; private var uFogColor = 0; private var uFogEnd = 0
    private var uSpec = 0; private var uGloss = 0; private var uEmit = 0; private var uPattern = 0; private var uObjScale = 0
    private var uTex = 0; private var uUseTex = 0; private var uMapMode = 0; private var uTerrain = 0
    private lateinit var meshes: Map<Shape, Mesh>
    private lateinit var grid: Mesh
    private lateinit var gizmo: Mesh
    private lateinit var circle: Mesh
    private lateinit var quad: Mesh
    private lateinit var waterMesh: Mesh
    private var uFoam = 0
    private var uMirror = 0; private var uMirrorTex = 0; private var uScreen = 0; private var uClipOn = 0; private var uClipPl = 0
    private val MAXM = 2
    private val mirFbo = IntArray(2)
    private val mirTex = IntArray(2)
    private val mirDepth = IntArray(2)
    private var mirW = 0
    private var mirH = 0
    private var mirrorOk = false
    private val mirrorIndex = HashMap<SceneObject, Int>()
    private var uWater = 0; private var uTime = 0; private var uWave = 0; private var uGrass = 0
    private var uParticle = 0; private var uCamRight = 0; private var uCamUp = 0
    private var uPSize = 0; private var uPHeight = 0; private var uPWind = 0
    private var uGlass = 0; private var uVeg = 0; private var uEnv = 0; private var uHasEnv = 0
    private var uShadow = 0; private var uShadowOn = 0; private var uShadowArea = 0
    private var uCube = 0; private var uCubeOn = 0
    private var cubeTex = 0
    private val cubeAtlas: java.nio.ByteBuffer = java.nio.ByteBuffer.allocateDirect(6 * 128 * 4 * 128 * 4)
    private var uWind = 0; private var uSwayLocal = 0; private var uTree = 0
    private val SH = 96   // terrain shadow map resolution
    private var shTex = 0
    private var shKey = ""
    private var shTime = 0L
    private val shBuf: java.nio.ByteBuffer = java.nio.ByteBuffer.allocateDirect(SH * SH * 4)
    private val shSunMap = FloatArray(SH * SH)
    private val shPtMap = FloatArray(SH * SH)
    private val shTmpMap = FloatArray(SH * SH)
    private lateinit var fireMesh: Mesh
    private lateinit var smokeMesh: Mesh
    private val treeTrunk = arrayOfNulls<Mesh>(2)
    private val treeCrown = arrayOfNulls<Mesh>(2)
    private val treeAll = arrayOfNulls<Mesh>(2)
    private val grassCache = HashMap<SceneObject, Pair<String, Mesh>>()
    private val photoAspect = HashMap<String, Float>()
    private var skyLit = 0f
    private var windX = 1f
    private var windZ = 0f
    private var windStr = 0f
    private val modelMeshes = HashMap<String, Mesh>()
    private val photoTex = HashMap<String, Int>()
    private val terrainCache = HashMap<SceneObject, Pair<String, Mesh>>()
    @Volatile var photoLoader: ((String) -> Bitmap?)? = null

    private val vertexSrc = """
        uniform mat4 uMVP;
        uniform mat4 uModel;
        uniform float uWater;
        uniform float uTime;
        uniform vec3 uWave;
        uniform float uGrass;
        uniform float uParticle;
        uniform vec3 uCamRight;
        uniform vec3 uCamUp;
        uniform float uPSize;
        uniform float uPHeight;
        uniform float uPWind;
        uniform vec3 uWind;
        uniform vec3 uSwayLocal;
        uniform float uTree;
        attribute vec3 aPos;
        attribute vec3 aNorm;
        varying vec3 vN;
        varying vec3 vW;
        varying vec3 vL;
        varying vec3 vLN;
        varying float vIsWater;
        varying float vWaveH;
        varying float vIsGrass;
        varying float vTip;
        varying float vIsPart;
        varying float vPLife;
        vec3 waveSum(vec2 p) {
            float t = uTime * uWave.y;
            float c = cos(uWave.z);
            float s = sin(uWave.z);
            vec2 q = vec2(p.x * c - p.y * s, p.x * s + p.y * c);
            float ph1 = q.x * 0.9 + t * 1.3;
            float ph2 = q.y * 1.4 + t * 1.0;
            float ph3 = (q.x * 0.6 + q.y * 0.8) * 2.1 + t * 1.9;
            float h = 0.5 * sin(ph1) + 0.3 * sin(ph2) + 0.2 * sin(ph3);
            float dqx = 0.5 * 0.9 * cos(ph1) + 0.2 * 2.1 * 0.6 * cos(ph3);
            float dqy = 0.3 * 1.4 * cos(ph2) + 0.2 * 2.1 * 0.8 * cos(ph3);
            float dx = dqx * c + dqy * s;
            float dz = -dqx * s + dqy * c;
            return vec3(h, dx, dz) * uWave.x;
        }
        void main() {
            vec3 pos = aPos;
            vec3 nrm = aNorm;
            vIsWater = uWater;
            vWaveH = 0.0;
            if (uWater > 0.5) {
                vec2 wxz = (uModel * vec4(aPos, 1.0)).xz;
                vec3 w = waveSum(wxz);
                pos.y += w.x;
                vWaveH = w.x / max(uWave.x, 0.001);
                nrm = normalize(vec3(-w.y, 1.0, -w.z));
                vN = nrm;
            } else {
                vN = (uModel * vec4(nrm, 0.0)).xyz;
            }
            vIsPart = uParticle;
            vPLife = 0.0;
            if (uParticle > 0.5) {
                float r2 = aNorm.x;
                float r3 = aNorm.y;
                float r4 = aNorm.z;
                float rate = 0.9;
                if (uParticle > 1.5) rate = 0.3;
                float life = fract(uTime * rate + aPos.z);
                vec3 c = vec3(0.0);
                float size = uPSize;
                if (uParticle < 1.5) {
                    c.x = (r2 - 0.5) * uPSize * (1.0 - life) * 1.2 + sin(uTime * 3.0 + r3 * 20.0) * 0.08 * uPSize * life;
                    c.z = (r3 - 0.5) * uPSize * (1.0 - life) * 1.2 + cos(uTime * 2.7 + r2 * 20.0) * 0.08 * uPSize * life;
                    c.y = life * uPHeight * (0.6 + 0.6 * r4);
                    c.x += uWind.x * uWind.z * life * life * 0.9;
                    c.z += uWind.y * uWind.z * life * life * 0.9;
                    size = uPSize * (0.55 + 0.35 * r4) * (1.0 - life * 0.75);
                } else {
                    c.x = (r2 - 0.5) * uPSize * 0.6 * life + uPWind * life * life * 2.0 + sin(uTime * 1.3 + r3 * 20.0) * 0.1 * life;
                    c.z = (r3 - 0.5) * uPSize * 0.6 * life + cos(uTime * 1.1 + r2 * 20.0) * 0.1 * life;
                    c.y = life * uPHeight * (0.7 + 0.3 * r4);
                    c.x += uWind.x * uWind.z * life * life * 3.0;
                    c.z += uWind.y * uWind.z * life * life * 3.0;
                    size = uPSize * (0.5 + life * 1.6);
                }
                pos = c + (uCamRight * aPos.x + uCamUp * aPos.y) * size;
                vPLife = life;
                vN = vec3(0.0, 1.0, 0.0);
            }
            if (uTree > 0.5) {
                float ph = uModel[3].x * 0.7 + uModel[3].z * 0.5;
                float hh = max(aPos.y, 0.0);
                float g = 0.65 + 0.35 * sin(uTime * 0.8 + ph);
                float fl = 0.12 * sin(uTime * 2.6 + ph * 1.9 + hh * 5.0);
                pos += uSwayLocal * (hh * hh) * (g + fl);
            }
            vIsGrass = uGrass;
            vTip = aNorm.x;
            if (uGrass > 0.5) {
                pos.x += sin(uTime * 2.0 + aPos.x * 1.7 + aPos.z * 1.3) * 0.05 * aNorm.x;
                pos.z += cos(uTime * 1.6 + aPos.x * 1.1 - aPos.z * 1.9) * 0.035 * aNorm.x;
                float gu = 0.7 + 0.3 * sin(uTime * 0.9 + aPos.x * 0.35 + aPos.z * 0.3);
                pos.x += uWind.x * uWind.z * 0.22 * aNorm.x * gu;
                pos.z += uWind.y * uWind.z * 0.22 * aNorm.x * gu;
                vN = vec3(0.0, 1.0, 0.0);
            }
            vLN = aNorm;
            vW = (uModel * vec4(pos, 1.0)).xyz;
            vL = aPos;
            gl_Position = uMVP * vec4(pos, 1.0);
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
        uniform float uLVis[4];
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
        varying vec3 vLN;
        varying float vIsWater;
        varying float vWaveH;
        uniform float uFoam;
        uniform float uMirror;
        uniform sampler2D uMirrorTex;
        uniform vec2 uScreen;
        uniform float uClipOn;
        uniform vec4 uClipPl;
        varying float vIsGrass;
        varying float vTip;
        varying float vIsPart;
        varying float vPLife;
        uniform sampler2D uTex;
        uniform float uUseTex;
        uniform float uMapMode;
        uniform float uTerrain;
        uniform float uGlass;
        uniform float uVeg;
        uniform sampler2D uEnv;
        uniform float uHasEnv;
        uniform sampler2D uShadow;
        uniform float uShadowOn;
        uniform vec4 uShadowArea;
        uniform sampler2D uCube;
        uniform float uCubeOn;
        vec2 photoUV(vec3 p, vec3 n) {
            vec3 a = abs(n);
            if (uMapMode > 0.5) {
                float u = atan(p.z, p.x) / 6.2831853 + 0.5;
                float v = 0.5 - asin(clamp(p.y / 0.5, -1.0, 1.0)) / 3.1415926;
                return vec2(u, v);
            }
            if (a.y >= a.x && a.y >= a.z) {
                if (n.y > 0.0) return vec2(p.x + 0.5, p.z + 0.5);
                return vec2(p.x + 0.5, 0.5 - p.z);
            }
            if (a.x >= a.z) {
                if (n.x > 0.0) return vec2(0.5 - p.z, 0.5 - p.y);
                return vec2(p.z + 0.5, 0.5 - p.y);
            }
            if (n.z > 0.0) return vec2(p.x + 0.5, 0.5 - p.y);
            return vec2(0.5 - p.x, 0.5 - p.y);
        }
        float hash31(vec3 p) {
            p = fract(p * 0.3183099 + 0.1);
            p *= 17.0;
            return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
        }
        float vnoise(vec3 x) {
            vec3 i = floor(x);
            vec3 f = fract(x);
            f = f * f * (3.0 - 2.0 * f);
            return mix(
                mix(mix(hash31(i), hash31(i + vec3(1.0, 0.0, 0.0)), f.x),
                    mix(hash31(i + vec3(0.0, 1.0, 0.0)), hash31(i + vec3(1.0, 1.0, 0.0)), f.x), f.y),
                mix(mix(hash31(i + vec3(0.0, 0.0, 1.0)), hash31(i + vec3(1.0, 0.0, 1.0)), f.x),
                    mix(hash31(i + vec3(0.0, 1.0, 1.0)), hash31(i + vec3(1.0, 1.0, 1.0)), f.x), f.y), f.z);
        }
        // what a mirror-like surface sees in direction r: the sky photo if there is one, else a sky gradient and the sun
        vec3 envColor(vec3 r) {
            if (uHasEnv > 0.5) {
                float eu = atan(r.z, r.x) / 6.2831853 + 0.5;
                float ev = 0.5 - asin(clamp(r.y, -1.0, 1.0)) / 3.1415926;
                return texture2D(uEnv, vec2(eu, ev)).rgb;
            }
            float t = clamp(r.y * 0.5 + 0.5, 0.0, 1.0);
            vec3 sky = mix(uFogColor * 0.75, uFogColor * 1.1 + vec3(0.12), t);
            float spot = pow(max(dot(r, uSunDir), 0.0), 220.0);
            return sky + uSunCol * spot * 3.0;
        }
        // Is the point light number li blocked from this pixel? Looks up the light's cube depth map
        // (6 faces side by side, one row per light; depth stored as 1/z packed into 3 bytes).
        float lightVisAt(int li, vec3 lp) {
            vec3 q = vW - lp;
            vec3 a = abs(q);
            float f = 0.0;
            float z = 1.0;
            float x = 0.0;
            float y = 0.0;
            if (a.x >= a.y && a.x >= a.z) {
                if (q.x >= 0.0) { f = 0.0; z = q.x; x = -q.z; } else { f = 1.0; z = -q.x; x = q.z; }
                y = q.y;
            } else if (a.y >= a.z) {
                if (q.y >= 0.0) { f = 2.0; z = q.y; y = -q.z; } else { f = 3.0; z = -q.y; y = q.z; }
                x = q.x;
            } else {
                if (q.z >= 0.0) { f = 4.0; z = q.z; x = q.x; } else { f = 5.0; z = -q.z; x = -q.x; }
                y = q.y;
            }
            if (z < 0.06) return 1.0;
            float ux = clamp((x / z * 0.5 + 0.5) * 128.0, 0.5, 127.5);
            float uy = clamp((y / z * 0.5 + 0.5) * 128.0, 0.5, 127.5);
            vec2 uv = vec2((f * 128.0 + ux) / 768.0, (float(li) * 128.0 + uy) / 512.0);
            vec3 c = texture2D(uCube, uv).rgb;
            float stored = dot(c, vec3(1.0, 1.0 / 255.0, 1.0 / 65025.0)) * 20.0;
            return (stored > (1.0 / z) * 1.06 + 0.002) ? 0.0 : 1.0;
        }
        void main() {
            if (uClipOn > 0.5 && dot(vW, uClipPl.xyz) + uClipPl.w < 0.0) discard;
            if (vIsPart > 0.5) {
                float d = length(vL.xy) * 2.0;
                float a = clamp(1.0 - d, 0.0, 1.0);
                a = a * a * (3.0 - 2.0 * a);
                float l = vPLife;
                if (vIsPart < 1.5) {
                    vec3 fc = mix(vec3(1.0, 0.95, 0.6), vec3(1.0, 0.45, 0.08), smoothstep(0.0, 0.45, l));
                    fc = mix(fc, vec3(0.5, 0.05, 0.0), smoothstep(0.45, 1.0, l));
                    float fade = (1.0 - l) * smoothstep(0.0, 0.08, l);
                    gl_FragColor = vec4(fc * uColor.rgb, a * fade);
                } else {
                    vec3 sc = mix(vec3(0.30), vec3(0.62), l) * uColor.rgb;
                    // smoke is lit like everything else: ambient + sun, and nearby lights (a fire lights it orange)
                    vec3 sl = uAmbient + uSunCol * 0.7;
                    for (int i = 0; i < 4; i++) {
                        if (i >= uLCount) break;
                        float pd = length(uLPos[i] - vW);
                        float pa = clamp(1.0 - pd / max(uLCol[i].w, 0.001), 0.0, 1.0);
                        sl += uLCol[i].rgb * pa * pa * 0.9;
                    }
                    sc *= clamp(sl * 1.25, 0.0, 1.5);
                    float fade = smoothstep(0.0, 0.15, l) * (1.0 - l);
                    gl_FragColor = vec4(sc, a * fade * uColor.a);
                }
                return;
            }
            if (uLit > 2.5) {
                vec2 q = vec2(vL.x * 0.5 + 0.5, 0.5 - vL.y * 0.5);
                vec2 uv = vec2(0.5 + (q.x - 0.5) * uObjScale.x + uObjScale.z, 0.5 + (q.y - 0.5) * uObjScale.y);
                gl_FragColor = vec4(texture2D(uTex, uv).rgb, 1.0);
                return;
            }
            if (uLit > 1.5) {
                gl_FragColor = vec4(texture2D(uTex, photoUV(vL, normalize(vLN))).rgb, 1.0);
                return;
            }
            if (uLit < 0.5) { gl_FragColor = uColor; return; }
            vec3 n = normalize(vN);
            vec3 v = normalize(uEye - vW);
            if (uVeg > 0.5 && uVeg < 1.5) {
                vec3 pp = vL * 14.0;
                vec3 pert = vec3(vnoise(pp), vnoise(pp + 7.1), vnoise(pp + 13.7)) - 0.5;
                n = normalize(n + pert * 1.1);
            }
            float shin = 4.0 + uGloss * 120.0;
            vec3 light = uAmbient;
            vec3 spec = vec3(0.0);
            float shSun = 1.0;
            float shPt = 1.0;
            if ((uTerrain > 0.5 || vIsGrass > 0.5) && uShadowOn > 0.5) {
                // the ground shadow map, looked up by world position (so grass is shaded like the ground under it)
                vec2 shUV = (vW.xz - uShadowArea.xy) / max(uShadowArea.zw, vec2(0.001)) + 0.5;
                vec2 shM = texture2D(uShadow, shUV).rg;
                shSun = 1.0 - shM.r;
                shPt = 1.0 - shM.g;
            }
            float sd = max(dot(n, uSunDir), 0.0);
            light += uSunCol * sd * shSun;
            if (sd > 0.0) {
                vec3 h = normalize(uSunDir + v);
                spec += uSunCol * pow(max(dot(n, h), 0.0), shin) * shSun;
            }
            for (int i = 0; i < 4; i++) {
                if (i >= uLCount) break;
                vec3 d = uLPos[i] - vW;
                float dist = max(length(d), 0.001);
                vec3 l = d / dist;
                float att = clamp(1.0 - dist / uLCol[i].w, 0.0, 1.0);
                att = att * att * uLVis[i];
                if (uCubeOn > 0.5 && uTerrain < 0.5 && vIsGrass < 0.5) att *= lightVisAt(i, uLPos[i]);
                float nl = max(dot(n, l), 0.0);
                light += uLCol[i].rgb * nl * att * shPt;
                if (nl > 0.0) {
                    vec3 h = normalize(l + v);
                    spec += uLCol[i].rgb * att * pow(max(dot(n, h), 0.0), shin) * shPt;
                }
            }
            vec3 base = uColor.rgb;
            if (uVeg > 0.5 && uVeg < 1.5) {
                float nn = vnoise(vL * 22.0);
                float nn2 = vnoise(vL * 7.0 + 3.0);
                float hgt = smoothstep(0.35, 1.0, vL.y);
                base = base * (0.55 + 0.7 * nn) * mix(0.6, 1.2, hgt) * (0.85 + 0.3 * nn2);
                base = mix(base, base * vec3(1.15, 1.1, 0.7), nn2 * 0.35);
            } else if (uVeg > 1.5) {
                float bk = vnoise(vec3(vL.x * 45.0, vL.y * 5.0, vL.z * 45.0));
                base = base * (0.55 + 0.7 * bk);
            }
            if (uTerrain > 0.5) {
                float f = clamp(vL.y / max(uObjScale.y, 0.001), 0.0, 1.0);
                vec3 g = mix(vec3(0.30, 0.55, 0.25), vec3(0.50, 0.42, 0.30), smoothstep(0.25, 0.65, f));
                g = mix(g, vec3(0.93, 0.94, 0.97), smoothstep(0.75, 0.95, f));
                base = g * uColor.rgb;
            }
            if (vIsGrass > 0.5) {
                float rv = vLN.z;
                base = mix(vec3(0.06, 0.20, 0.04), vec3(0.45, 0.72, 0.22), vTip) * (0.75 + 0.5 * rv) * uColor.rgb;
                base = mix(base, base * vec3(1.25, 1.1, 0.55), rv * 0.4 * vTip);
            }
            if (uUseTex > 0.5) {
                base = texture2D(uTex, photoUV(vL, normalize(vLN))).rgb * uColor.rgb;
            }
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
            float alpha = uColor.a;
            float nv = clamp(dot(n, v), 0.0, 1.0);
            vec3 env = envColor(reflect(-v, n));
            if (vIsWater > 0.5) {
                // water: mostly see-through straight down, mirror-like at grazing angles
                float wf = 0.02 + 0.98 * pow(1.0 - nv, 4.0);
                c = mix(c, env, clamp(wf * 0.9 + 0.06, 0.0, 1.0));
                alpha = clamp(alpha + wf * 0.6, 0.0, 1.0);
                // white foam on the wave crests, more of it the bigger the waves
                float foam = smoothstep(0.5, 0.95, vWaveH) * uFoam;
                c = mix(c, vec3(0.95, 0.97, 1.0) * (0.55 + 0.45 * max(light.r, 0.3)), foam * 0.85);
                alpha = clamp(alpha + foam * 0.7, 0.0, 1.0);
            } else {
                float f0 = 0.03 + 0.55 * uSpec * uGloss;
                float fresnel = f0 + (1.0 - f0) * pow(1.0 - nv, 5.0);
                // metals tint what they reflect
                vec3 envT = env * mix(vec3(1.0), base * 1.4, clamp(uSpec * uGloss * 0.7, 0.0, 1.0));
                if (uGlass > 0.5) {
                    // glass: clear in the middle, bright reflections and more opaque toward the edges
                    c = base * (0.45 + 0.4 * light) + spec * uSpec * 1.6 + base * uEmit;
                    c = mix(c, envT, clamp(fresnel * 1.2 + 0.06, 0.0, 1.0));
                    alpha = clamp(alpha + fresnel * 0.85, 0.0, 1.0);
                } else {
                    c = mix(c, envT, clamp(fresnel * uSpec * 1.3, 0.0, 1.0));
                }
            }
            if (uMirror > 0.5) {
                // mirror: the picture of the scene drawn from the mirrored camera, looked up at this pixel
                vec3 mr = texture2D(uMirrorTex, gl_FragCoord.xy / uScreen).rgb;
                c = mix(base * 0.35, mr, 0.93) + spec * uSpec * 0.3;
                alpha = 1.0;
            }
            if (uFogEnd > 0.0) {
                float fd = length(vW - uEye);
                float ff = clamp((fd - uFogEnd * 0.3) / (uFogEnd * 0.7), 0.0, 1.0);
                c = mix(c, uFogColor, ff);
            }
            gl_FragColor = vec4(c, alpha);
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
        photoTex.clear()   // textures are lost with the GL context
        shTex = 0
        shKey = ""
        cubeTex = 0
        visKey = ""
        visReady = false
        for (i in 0 until 2) { mirFbo[i] = 0; mirTex[i] = 0; mirDepth[i] = 0 }
        mirW = 0
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
        uLVis = GLES20.glGetUniformLocation(prog, "uLVis[0]")
        uLCount = GLES20.glGetUniformLocation(prog, "uLCount")
        uEye = GLES20.glGetUniformLocation(prog, "uEye")
        uFogColor = GLES20.glGetUniformLocation(prog, "uFogColor")
        uFogEnd = GLES20.glGetUniformLocation(prog, "uFogEnd")
        uSpec = GLES20.glGetUniformLocation(prog, "uSpec")
        uGloss = GLES20.glGetUniformLocation(prog, "uGloss")
        uEmit = GLES20.glGetUniformLocation(prog, "uEmit")
        uPattern = GLES20.glGetUniformLocation(prog, "uPattern")
        uObjScale = GLES20.glGetUniformLocation(prog, "uObjScale")
        uTex = GLES20.glGetUniformLocation(prog, "uTex")
        uUseTex = GLES20.glGetUniformLocation(prog, "uUseTex")
        uMapMode = GLES20.glGetUniformLocation(prog, "uMapMode")
        uTerrain = GLES20.glGetUniformLocation(prog, "uTerrain")
        uWater = GLES20.glGetUniformLocation(prog, "uWater")
        uTime = GLES20.glGetUniformLocation(prog, "uTime")
        uWave = GLES20.glGetUniformLocation(prog, "uWave")
        uFoam = GLES20.glGetUniformLocation(prog, "uFoam")
        uMirror = GLES20.glGetUniformLocation(prog, "uMirror")
        uMirrorTex = GLES20.glGetUniformLocation(prog, "uMirrorTex")
        uScreen = GLES20.glGetUniformLocation(prog, "uScreen")
        uClipOn = GLES20.glGetUniformLocation(prog, "uClipOn")
        uClipPl = GLES20.glGetUniformLocation(prog, "uClipPl")
        uGrass = GLES20.glGetUniformLocation(prog, "uGrass")
        uParticle = GLES20.glGetUniformLocation(prog, "uParticle")
        uCamRight = GLES20.glGetUniformLocation(prog, "uCamRight")
        uCamUp = GLES20.glGetUniformLocation(prog, "uCamUp")
        uPSize = GLES20.glGetUniformLocation(prog, "uPSize")
        uPHeight = GLES20.glGetUniformLocation(prog, "uPHeight")
        uPWind = GLES20.glGetUniformLocation(prog, "uPWind")
        uGlass = GLES20.glGetUniformLocation(prog, "uGlass")
        uVeg = GLES20.glGetUniformLocation(prog, "uVeg")
        uEnv = GLES20.glGetUniformLocation(prog, "uEnv")
        uHasEnv = GLES20.glGetUniformLocation(prog, "uHasEnv")
        uWind = GLES20.glGetUniformLocation(prog, "uWind")
        uSwayLocal = GLES20.glGetUniformLocation(prog, "uSwayLocal")
        uTree = GLES20.glGetUniformLocation(prog, "uTree")
        uShadow = GLES20.glGetUniformLocation(prog, "uShadow")
        uShadowOn = GLES20.glGetUniformLocation(prog, "uShadowOn")
        uShadowArea = GLES20.glGetUniformLocation(prog, "uShadowArea")
        uCube = GLES20.glGetUniformLocation(prog, "uCube")
        uCubeOn = GLES20.glGetUniformLocation(prog, "uCubeOn")
        meshes = mapOf(Shape.CUBE to Meshes.cube(), Shape.PYRAMID to Meshes.pyramid(), Shape.SPHERE to Meshes.sphere())
        grid = Meshes.grid()
        gizmo = Meshes.gizmo()
        circle = Meshes.circle()
        quad = Meshes.quad()
        waterMesh = Meshes.waterGrid()
        fireMesh = Meshes.particles(70)
        smokeMesh = Meshes.particles(48)
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
        GLES20.glUniform1f(uLit, if (skyLit > 0f) skyLit else if (lit) 1f else 0f)
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
        // a scene with Settings but no Sun has no sun light (the built-in sun is only for plain scenes)
        if (sun == null && st != null) sc = floatArrayOf(0f, 0f, 0f)
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
        // point lights, plus every fire (a flickering orange light; Glow = its strength)
        val pts = scene.objects.filter { it.shape == Shape.LIGHT || (it.shape == Shape.FIRE && it.emit > 0.01f) }.take(4)
        val pos = FloatArray(12)
        val col = FloatArray(16)
        val tt = Water.time()
        for ((i, o) in pts.withIndex()) {
            val fireL = o.shape == Shape.FIRE
            pos[i * 3] = o.x
            pos[i * 3 + 1] = if (fireL) o.y + o.sy * 0.25f else o.y
            pos[i * 3 + 2] = o.z
            val k = if (fireL) o.emit * 1.8f * (0.85f + 0.15f * sin(tt * 17f + i) + 0.07f * sin(tt * 41f)) else o.sx
            col[i * 4] = (if (fireL) 1.0f else 1f) * o.color[0] * k
            col[i * 4 + 1] = (if (fireL) 0.55f else 1f) * o.color[1] * k
            col[i * 4 + 2] = (if (fireL) 0.2f else 1f) * o.color[2] * k
            col[i * 4 + 3] = if (fireL) 4f + o.sx * 3f else o.sy
        }
        System.arraycopy(pos, 0, litPos, 0, 12)
        litCount = pts.size
        updateLightVisibility()
        GLES20.glUniform1fv(uLVis, 4, visOnes, 0)
        if (visActive) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE4)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, cubeTex)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glUniform1i(uCube, 4)
        }
        GLES20.glUniform1f(uCubeOn, if (visActive) 1f else 0f)
        GLES20.glUniform3fv(uLPos, 4, pos, 0)
        GLES20.glUniform4fv(uLCol, 4, col, 0)
        GLES20.glUniform1i(uLCount, pts.size)
        val sky = st?.color ?: floatArrayOf(0.15f, 0.16f, 0.19f)
        GLES20.glUniform3f(uFogColor, sky[0], sky[1], sky[2])
        GLES20.glUniform1f(uFogEnd, if (st != null && !bright) st.y.coerceAtLeast(0f) else 0f)
        GLES20.glUniform3f(uEye, eyeP[0], eyeP[1], eyeP[2])
        val wr = Math.toRadians((st?.windDir ?: 0f).toDouble())
        windX = cos(wr).toFloat()
        windZ = sin(wr).toFloat()
        windStr = (st?.wind ?: 0f).coerceIn(0f, 1f)
        GLES20.glUniform3f(uWind, windX, windZ, windStr)
        Water.boost = 1f + windStr * 1.2f
        GLES20.glUniform1f(uTree, 0f)
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

    /** Editor-only bone: a joint ball and a tapered bone along the bone's local Y axis (its length = Y scale). */
    private fun drawBoneMarker(o: SceneObject, vpc: FloatArray) {
        val dx = o.x - curEye[0]
        val dy = o.y - curEye[1]
        val dz = o.z - curEye[2]
        val d = sqrt(dx * dx + dy * dy + dz * dz)
        val len = max(o.sy, 0.2f)
        val selected = o === scene.selected
        val col = if (selected) floatArrayOf(1f, 1f, 0.45f) else floatArrayOf(0.95f, 0.72f, 0.3f)
        val w = max(0.14f * len, d * 0.012f)
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, o.x, o.y, o.z)
        Matrix.rotateM(model, 0, o.ry, 0f, 1f, 0f)
        Matrix.rotateM(model, 0, o.rx, 1f, 0f, 0f)
        Matrix.rotateM(model, 0, o.rz, 0f, 0f, 1f)
        Matrix.translateM(model, 0, 0f, len / 2f, 0f)
        Matrix.scaleM(model, 0, w, len, w)
        drawMesh(meshes.getValue(Shape.PYRAMID), vpc, model, col, false)
        val k = max(0.12f, d * 0.022f)
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, o.x, o.y, o.z)
        Matrix.scaleM(model, 0, k, k, k)
        drawMesh(meshes.getValue(Shape.SPHERE), vpc, model, floatArrayOf(1f, 0.92f, 0.55f), false)
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

    /** GL texture for an imported photo (loaded once on first use; 0 if the file is missing). */
    private fun textureFor(key: String): Int {
        val cached = photoTex[key]
        if (cached != null) return cached
        var id = 0
        val bmp = try { photoLoader?.invoke(key) } catch (e: Throwable) { null }
        if (bmp != null) {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            photoAspect[key] = bmp.width.toFloat() / bmp.height
            bmp.recycle()
            id = ids[0]
        }
        photoTex[key] = id
        return id
    }

    private fun setMaterial(o: SceneObject) {
        GLES20.glUniform1f(uSpec, o.spec.coerceIn(0f, 1f))
        GLES20.glUniform1f(uGloss, o.gloss.coerceIn(0f, 1f))
        GLES20.glUniform1f(uEmit, o.emit.coerceIn(0f, 1f))
        GLES20.glUniform1f(uPattern, o.pattern.toFloat())
        GLES20.glUniform3f(uObjScale, o.sx, o.sy, o.sz)
        GLES20.glUniform1f(uTerrain, if (o.shape == Shape.TERRAIN) 1f else 0f)
        val tex = if (o.photo.isNotEmpty()) textureFor(o.photo) else 0
        if (tex > 0) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
            GLES20.glUniform1i(uTex, 0)
            GLES20.glUniform1f(uMapMode, if (o.shape == Shape.SPHERE) 1f else 0f)
            GLES20.glUniform1f(uUseTex, 1f)
        } else {
            GLES20.glUniform1f(uUseTex, 0f)
        }
    }

    private fun trunkMesh(t: Int): Mesh = treeTrunk[t] ?: Mesh(Veg.trunk(t), GLES20.GL_TRIANGLES).also { treeTrunk[t] = it }
    private fun crownMesh(t: Int): Mesh = treeCrown[t] ?: Mesh(Veg.crown(t), GLES20.GL_TRIANGLES).also { treeCrown[t] = it }
    private fun treeAllMesh(t: Int): Mesh = treeAll[t] ?: Mesh(Veg.both(t), GLES20.GL_TRIANGLES).also { treeAll[t] = it }

    private fun setPlain(spec: Float, gloss: Float) {
        GLES20.glUniform1f(uSpec, spec)
        GLES20.glUniform1f(uGloss, gloss)
        GLES20.glUniform1f(uEmit, 0f)
        GLES20.glUniform1f(uPattern, 0f)
        GLES20.glUniform3f(uObjScale, 1f, 1f, 1f)
        GLES20.glUniform1f(uTerrain, 0f)
        GLES20.glUniform1f(uUseTex, 0f)
    }

    /** A tree: brown trunk plus a crown in the object's colour. */
    private fun drawTree(o: SceneObject, vpc: FloatArray, sel: SceneObject?) {
        val t = o.pattern.coerceIn(0, 1)
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, o.x, o.y, o.z)
        Matrix.rotateM(model, 0, o.ry, 0f, 1f, 0f)
        Matrix.scaleM(model, 0, o.sx, o.sy, o.sz)
        setPlain(0.1f, 0.2f)
        if (windStr > 0.001f) {
            // wind pushes the top of the tree; worked out in the tree's own (rotated, scaled) space
            val ry = Math.toRadians(o.ry.toDouble())
            val lx = (windX * cos(ry) - windZ * sin(ry)).toFloat()
            val lz = (windX * sin(ry) + windZ * cos(ry)).toFloat()
            val amp = windStr * 0.25f * o.sy
            GLES20.glUniform3f(uSwayLocal, lx * amp / max(o.sx, 0.01f), 0f, lz * amp / max(o.sz, 0.01f))
            GLES20.glUniform1f(uTime, Water.time())
            GLES20.glUniform1f(uTree, 1f)
        }
        GLES20.glUniform1f(uVeg, 2f)
        drawMesh(trunkMesh(t), vpc, model, floatArrayOf(0.38f, 0.26f, 0.15f), true)
        val c = if (hl(o, sel))
            floatArrayOf(minOf(1f, o.color[0] * 0.6f + 0.4f), minOf(1f, o.color[1] * 0.6f + 0.4f), minOf(1f, o.color[2] * 0.6f + 0.4f))
        else o.color
        GLES20.glUniform1f(uVeg, 1f)
        drawMesh(crownMesh(t), vpc, model, c, true)
        GLES20.glUniform1f(uVeg, 0f)
        GLES20.glUniform1f(uTree, 0f)
    }

    /** Grass blades sit on the terrain surface when there is terrain under them; they sway in the wind. */
    private fun drawGrass(o: SceneObject, vpc: FloatArray, sel: SceneObject?) {
        val terrains = scene.objects.filter { it.shape == Shape.TERRAIN }
        val waters = scene.objects.filter { it.shape == Shape.WATER }
        val sig = StringBuilder()
        sig.append(o.x).append('|').append(o.y).append('|').append(o.z).append('|')
            .append(o.sx).append('|').append(o.sy).append('|').append(o.sz).append('|').append(o.rx).append('|').append(o.hVersion)
        for (t in terrains) {
            sig.append('#').append(t.hVersion).append(',').append(t.x).append(',').append(t.y).append(',').append(t.z)
                .append(',').append(t.sx).append(',').append(t.sy).append(',').append(t.sz)
        }
        for (w in waters) {
            sig.append('~').append(w.x).append(',').append(w.y).append(',').append(w.z).append(',').append(w.sx).append(',').append(w.sz)
        }
        val key = sig.toString()
        var entry = grassCache[o]
        if (entry == null || entry.first != key) {
            val data = Veg.grass(o) { x, z ->
                var best = o.y
                for (t in terrains) {
                    val h = TerrainGen.heightAt(t, x, z)
                    if (h != null) best = h
                }
                // no grass under (or right at the edge of) water
                var wet = false
                for (w in waters) {
                    if (Math.abs(x - w.x) <= w.sx / 2f && Math.abs(z - w.z) <= w.sz / 2f && best < w.y + 0.08f) wet = true
                }
                if (wet) null else best
            }
            if (data.isEmpty()) return
            entry = Pair(key, Mesh(data, GLES20.GL_TRIANGLES))
            grassCache[o] = entry
        }
        Matrix.setIdentityM(model, 0)
        setPlain(0f, 0.1f)
        GLES20.glUniform1f(uGrass, 1f)
        GLES20.glUniform1f(uTime, Water.time())
        val c = if (hl(o, sel)) floatArrayOf(1.4f, 1.4f, 1.4f) else o.color
        drawMesh(entry.second, vpc, model, c, true)
        GLES20.glUniform1f(uGrass, 0f)
    }

    /** Fire and smoke are made of camera-facing soft sprites that the vertex shader moves along each particle's life. */
    private fun drawEmitter(o: SceneObject, vpc: FloatArray, camAxes: FloatArray) {
        val fire = o.shape == Shape.FIRE
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, o.x, o.y, o.z)
        setPlain(0f, 0.1f)
        GLES20.glUniform1f(uParticle, if (fire) 1f else 2f)
        GLES20.glUniform1f(uTime, Water.time())
        GLES20.glUniform3f(uCamRight, camAxes[0], camAxes[1], camAxes[2])
        GLES20.glUniform3f(uCamUp, camAxes[3], camAxes[4], camAxes[5])
        GLES20.glUniform1f(uPSize, o.sx)
        GLES20.glUniform1f(uPHeight, o.sy)
        GLES20.glUniform1f(uPWind, o.rx)
        // fire adds light (glows), smoke blends normally
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, if (fire) GLES20.GL_ONE else GLES20.GL_ONE_MINUS_SRC_ALPHA)
        val mesh = if (fire) fireMesh else smokeMesh
        drawMesh(mesh, vpc, model, o.color, true, 0, mesh.count, if (fire) 1f else o.opacity.coerceIn(0.02f, 1f))
        GLES20.glUniform1f(uParticle, 0f)
    }

    private fun drawWater(o: SceneObject, vpc: FloatArray, sel: SceneObject?, alpha: Float) {
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, o.x, o.y, o.z)
        Matrix.scaleM(model, 0, o.sx, 1f, o.sz)
        GLES20.glUniform1f(uSpec, o.spec.coerceIn(0f, 1f))
        GLES20.glUniform1f(uGloss, o.gloss.coerceIn(0f, 1f))
        GLES20.glUniform1f(uEmit, o.emit.coerceIn(0f, 1f))
        GLES20.glUniform1f(uPattern, 0f)
        GLES20.glUniform3f(uObjScale, 1f, 1f, 1f)
        GLES20.glUniform1f(uTerrain, 0f)
        GLES20.glUniform1f(uUseTex, 0f)
        GLES20.glUniform1f(uWater, 1f)
        GLES20.glUniform1f(uTime, Water.time())
        val waveH = o.sy * Water.boost
        GLES20.glUniform3f(uWave, waveH, o.rx, Math.toRadians(o.ry.toDouble()).toFloat())
        GLES20.glUniform1f(uFoam, ((waveH - 0.06f) * 2.2f).coerceIn(0f, 1f))
        val c = if (hl(o, sel))
            floatArrayOf(minOf(1f, o.color[0] + 0.25f), minOf(1f, o.color[1] + 0.25f), minOf(1f, o.color[2] + 0.25f))
        else o.color
        drawMesh(waterMesh, vpc, model, c, true, 0, waterMesh.count, alpha)
        GLES20.glUniform1f(uWater, 0f)
    }

    private fun drawObject(o: SceneObject, vpc: FloatArray, sel: SceneObject?, alpha: Float) {
        setLightVis(o)
        if (o.shape == Shape.WATER) { drawWater(o, vpc, sel, alpha); return }
        if (o.shape == Shape.MIRROR) { drawMirror(o, vpc, sel); return }
        if (o.shape == Shape.TREE) { drawTree(o, vpc, sel); return }
        if (o.shape == Shape.GRASS) { drawGrass(o, vpc, sel); return }
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, o.x, o.y, o.z)
        Matrix.rotateM(model, 0, o.ry, 0f, 1f, 0f)
        Matrix.rotateM(model, 0, o.rx, 1f, 0f, 0f)
        Matrix.rotateM(model, 0, o.rz, 0f, 0f, 1f)
        if (o.shape == Shape.TERRAIN) {
            // terrain geometry is already in world size
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, o.x, o.y, o.z)
        } else {
            Matrix.scaleM(model, 0, o.sx, o.sy, o.sz)
        }
        val hasPhoto = o.photo.isNotEmpty() && textureFor(o.photo) > 0
        val c = if (hasPhoto || o.shape == Shape.TERRAIN) {
            if (hl(o, sel)) floatArrayOf(1.3f, 1.3f, 1.3f) else floatArrayOf(1f, 1f, 1f)
        } else if (hl(o, sel)) {
            floatArrayOf(o.color[0] * 0.6f + 0.4f, o.color[1] * 0.6f + 0.4f, o.color[2] * 0.6f + 0.4f)
        } else o.color
        setMaterial(o)
        val mesh = meshFor(o)
        GLES20.glUniform1f(uGlass, if (alpha < 0.99f) 1f else 0f)
        drawMesh(mesh, vpc, model, c, true, 0, mesh.count, alpha)
        GLES20.glUniform1f(uGlass, 0f)
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

    private fun passAlpha(total: Float, n: Int): Float =
        1f - Math.pow((1f - total.coerceIn(0f, 1f)).toDouble(), 1.0 / n).toFloat()

    /**
     * Ground shadows: every object that casts a shadow is flattened onto the ground (Settings: ground height)
     * for the sun and for each point light. Settings controls strength, softness (several slightly shifted copies
     * blended together), colour and ground height. Defaults without a Settings object: strength 0.5, softness 0.4, black.
     */
    private fun drawShadows(vpc: FloatArray) {
        val st = scene.objects.firstOrNull { it.shape == Shape.SETTINGS }
        val strength = if (st != null) st.ry.coerceIn(0f, 1f) else 0.5f
        if (strength <= 0.01f) return
        val gh = (if (st != null) st.rz else 0f) + 0.01f
        val soft = (st?.soft ?: 0.4f).coerceIn(0f, 1f)
        val tint = st?.tint ?: floatArrayOf(0f, 0f, 0f)
        val terrains = scene.objects.filter { it.shape == Shape.TERRAIN && it.heights != null }
        val casters = scene.objects.filter { o ->
            terrains.none { t -> TerrainGen.heightAt(t, o.x, o.z) != null } &&
                o.shadow && !o.shape.isScreen && !o.shape.isLight && o.shape != Shape.SETTINGS && o.shape != Shape.CAMERA && o.shape != Shape.TERRAIN && o.shape != Shape.BONE && o.shape != Shape.WATER && o.shape != Shape.GRASS && !o.shape.isEmitter && max(o.sx, max(o.sy, o.sz)) >= 0.35f &&
                o.y + 0.5f * o.sy > gh + 0.04f
        }
        if (casters.isEmpty()) return

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
        } else if (st != null) {
            sunPower = 0f
        }
        val ll = sqrt(lx * lx + ly * ly + lz * lz)
        val sunOk = ll > 0f && ly / ll > 0.08f && sunPower > 0.05f
        val lights = scene.objects.filter { it.shape == Shape.LIGHT }.take(4).filter { l -> l.y >= gh + 0.3f && l.sx >= 0.05f }
        if (!sunOk && lights.isEmpty()) return

        val samples = if (soft > 0.02f) 5 else 1
        val ox = floatArrayOf(0f, 1f, -1f, 0f, 0f)
        val oz = floatArrayOf(0f, 0f, 0f, 1f, -1f)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glEnable(GLES20.GL_POLYGON_OFFSET_FILL)
        for (k in 0 until samples) {
            // each copy sits a hair closer to the camera so every copy blends, but copies of the same
            // pass never double up where one object's flattened faces overlap
            GLES20.glPolygonOffset(0f, -(k + 1) * 1.5f)
            if (sunOk) {
                val sp = soft * 0.15f
                dirMatrix(shM, -lx / ll + ox[k] * sp, -ly / ll, -lz / ll + oz[k] * sp, gh)
                val total = strength
                drawShadowSet(casters, vpc, tint, passAlpha(total, samples)) { false }
            }
            for (l in lights) {
                val j = soft * 0.8f
                pointMatrix(shM, l.x + ox[k] * j, l.y, l.z + oz[k] * j, gh)
                val total = strength * minOf(1f, l.sx / 1.5f)
                drawShadowSet(casters, vpc, tint, passAlpha(total, samples)) { o ->
                    val dx = o.x - l.x
                    val dy = o.y - l.y
                    val dz = o.z - l.z
                    o.y >= l.y - 0.1f || dx * dx + dy * dy + dz * dz > l.sy * l.sy
                }
            }
        }
        GLES20.glDisable(GLES20.GL_POLYGON_OFFSET_FILL)
        GLES20.glDisable(GLES20.GL_BLEND)
    }


    private fun blurMap(a: FloatArray, r: Int) {
        if (r <= 0) return
        val t = shTmpMap
        val inv = 1f / (2 * r + 1)
        for (j in 0 until SH) for (i in 0 until SH) {
            var sum = 0f
            for (k in -r..r) sum += a[j * SH + (i + k).coerceIn(0, SH - 1)]
            t[j * SH + i] = sum * inv
        }
        for (j in 0 until SH) for (i in 0 until SH) {
            var sum = 0f
            for (k in -r..r) sum += t[(j + k).coerceIn(0, SH - 1) * SH + i]
            a[j * SH + i] = sum * inv
        }
    }

    /**
     * Terrain shadows: a small shadow map is worked out on the CPU for the first terrain (hills shade each other,
     * and every shadow-casting object blocks the sun and point lights) and the terrain shader reads it.
     * It is only redone when the terrain, the lights or a caster changes.
     */
    private fun bakeTerrainShadows() {
        val terrain = scene.objects.firstOrNull { it.shape == Shape.TERRAIN && it.heights != null }
        val st = scene.objects.firstOrNull { it.shape == Shape.SETTINGS }
        val strength = if (st != null) st.ry.coerceIn(0f, 1f) else 0.5f
        if (terrain == null || strength <= 0.01f) { GLES20.glUniform1f(uShadowOn, 0f); return }
        val soft = (st?.soft ?: 0.4f).coerceIn(0f, 1f)
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
        } else if (st != null) {
            sunPower = 0f
        }
        val ll = sqrt(lx * lx + ly * ly + lz * lz)
        val sunOk = ll > 0f && ly / ll > 0.08f && sunPower > 0.05f
        val lights = scene.objects.filter { it.shape == Shape.LIGHT }.take(4).filter { it.sx >= 0.05f }
        val casters = scene.objects.filter { o ->
            o.shadow && !o.shape.isScreen && !o.shape.isLight && o.shape != Shape.SETTINGS && o.shape != Shape.CAMERA &&
                o.shape != Shape.TERRAIN && o.shape != Shape.BONE && o.shape != Shape.WATER && o.shape != Shape.GRASS && !o.shape.isEmitter && max(o.sx, max(o.sy, o.sz)) >= 0.35f
        }

        val sb = StringBuilder()
        fun f(v: Float) { sb.append(v).append(',') }
        sb.append(terrain.hVersion).append(',')
        f(terrain.x); f(terrain.y); f(terrain.z); f(terrain.sx); f(terrain.sy); f(terrain.sz)
        f(strength); f(soft); f(lx); f(ly); f(lz); f(sunPower)
        for (l in lights) { f(l.x); f(l.y); f(l.z); f(l.sx); f(l.sy) }
        sb.append('|')
        for (o in casters) {
            sb.append(o.shape.ordinal).append(',')
            f(o.x); f(o.y); f(o.z); f(o.rx); f(o.ry); f(o.rz); f(o.sx); f(o.sy); f(o.sz)
        }
        val key = sb.toString()
        val now = System.currentTimeMillis()
        if (key != shKey && !(shTex != 0 && playing && now - shTime < 100)) {
            bakeShadowMap(terrain, strength, soft, sunOk, lx / ll, ly / ll, lz / ll, lights, casters)
            shKey = key
            shTime = now
        }
        if (shTex == 0) { GLES20.glUniform1f(uShadowOn, 0f); return }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, shTex)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glUniform4f(uShadowArea, terrain.x, terrain.z, terrain.sx, terrain.sz)
        GLES20.glUniform1f(uShadowOn, 1f)
    }

    // depth buffers for the real-shape shadows
    private val SM = 512      // sun depth map size
    private val CS = 128      // size of each face of a point-light cube map
    private val sunDepth = FloatArray(SM * SM)
    private val cubeDepth = FloatArray(4 * 6 * CS * CS)
    private val shShapeData = HashMap<Shape, FloatArray>()
    private val shTreeData = HashMap<Int, FloatArray>()
    private val faceF = arrayOf(floatArrayOf(1f, 0f, 0f), floatArrayOf(-1f, 0f, 0f), floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, -1f, 0f), floatArrayOf(0f, 0f, 1f), floatArrayOf(0f, 0f, -1f))
    private val faceR = arrayOf(floatArrayOf(0f, 0f, -1f), floatArrayOf(0f, 0f, 1f), floatArrayOf(1f, 0f, 0f), floatArrayOf(1f, 0f, 0f), floatArrayOf(1f, 0f, 0f), floatArrayOf(-1f, 0f, 0f))
    private val faceU = arrayOf(floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, 0f, -1f), floatArrayOf(0f, 0f, 1f), floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, 1f, 0f))
    private val clipPoly = FloatArray(5 * 3)
    private val clipVx = FloatArray(3)
    private val clipVy = FloatArray(3)
    private val clipVz = FloatArray(3)

    /** Fills a screen-space triangle, keeping the largest value per pixel (the occluder nearest the light). */
    private fun fillTri(buf: FloatArray, off: Int, size: Int, x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float) {
        val minx = kotlin.math.floor(minOf(x0, x1, x2) - 0.5f).toInt().coerceAtLeast(0)
        val maxx = kotlin.math.ceil(maxOf(x0, x1, x2) - 0.5f).toInt().coerceAtMost(size - 1)
        val miny = kotlin.math.floor(minOf(y0, y1, y2) - 0.5f).toInt().coerceAtLeast(0)
        val maxy = kotlin.math.ceil(maxOf(y0, y1, y2) - 0.5f).toInt().coerceAtMost(size - 1)
        if (maxx < minx || maxy < miny) return
        val den = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2)
        if (abs(den) < 1e-9f) return
        val inv = 1f / den
        val eps = -0.04f
        for (y in miny..maxy) {
            val py = y + 0.5f
            for (x in minx..maxx) {
                val px = x + 0.5f
                val w0 = ((y1 - y2) * (px - x2) + (x2 - x1) * (py - y2)) * inv
                val w1 = ((y2 - y0) * (px - x2) + (x0 - x2) * (py - y2)) * inv
                val w2 = 1f - w0 - w1
                if (w0 < eps || w1 < eps || w2 < eps) continue
                val z = w0 * z0 + w1 * z1 + w2 * z2
                val k = off + y * size + x
                if (z > buf[k]) buf[k] = z
            }
        }
    }

    /** A triangle in a cube face's camera space (x right, y up, z forward): clipped to z >= near, projected at 90 degrees, stores 1/z. */
    private fun fillPersp(buf: FloatArray, off: Int, size: Int, ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, cx: Float, cy: Float, cz: Float) {
        val near = 0.05f
        if (az < near && bz < near && cz < near) return
        if (ax > az && bx > bz && cx > cz) return
        if (-ax > az && -bx > bz && -cx > cz) return
        if (ay > az && by > bz && cy > cz) return
        if (-ay > az && -by > bz && -cy > cz) return
        val vx = clipVx
        val vy = clipVy
        val vz = clipVz
        vx[0] = ax; vx[1] = bx; vx[2] = cx
        vy[0] = ay; vy[1] = by; vy[2] = cy
        vz[0] = az; vz[1] = bz; vz[2] = cz
        var n = 0
        for (i in 0..2) {
            val j = (i + 1) % 3
            val d1 = vz[i] - near
            val d2 = vz[j] - near
            if (d1 >= 0f) { clipPoly[n * 3] = vx[i]; clipPoly[n * 3 + 1] = vy[i]; clipPoly[n * 3 + 2] = vz[i]; n++ }
            if (d1 * d2 < 0f) {
                val t = d1 / (d1 - d2)
                clipPoly[n * 3] = vx[i] + (vx[j] - vx[i]) * t
                clipPoly[n * 3 + 1] = vy[i] + (vy[j] - vy[i]) * t
                clipPoly[n * 3 + 2] = near
                n++
            }
        }
        if (n < 3) return
        val h = size * 0.5f
        for (i in 1 until n - 1) {
            val z0 = clipPoly[2]
            val z1 = clipPoly[i * 3 + 2]
            val z2 = clipPoly[(i + 1) * 3 + 2]
            fillTri(
                buf, off, size,
                (clipPoly[0] / z0 + 1f) * h, (clipPoly[1] / z0 + 1f) * h, 1f / z0,
                (clipPoly[i * 3] / z1 + 1f) * h, (clipPoly[i * 3 + 1] / z1 + 1f) * h, 1f / z1,
                (clipPoly[(i + 1) * 3] / z2 + 1f) * h, (clipPoly[(i + 1) * 3 + 1] / z2 + 1f) * h, 1f / z2
            )
        }
    }

    /** World-space triangle corners (x, y, z per vertex) of every caster, using the real mesh of each object. */
    private fun casterTriangles(casters: List<SceneObject>): FloatArray {
        val parts = ArrayList<FloatArray>()
        var total = 0
        val m = FloatArray(16)
        for (o in casters) {
            val md = o.model
            val data: FloatArray = when {
                o.shape == Shape.TREE -> shTreeData.getOrPut(o.pattern.coerceIn(0, 1)) { Veg.both(o.pattern.coerceIn(0, 1)) }
                md != null -> md.data
                else -> shShapeData.getOrPut(o.shape) { Meshes.dataFor(o.shape) ?: FloatArray(0) }
            }
            val nv = data.size / 6 / 3 * 3
            if (nv == 0) continue
            Matrix.setIdentityM(m, 0)
            Matrix.translateM(m, 0, o.x, o.y, o.z)
            Matrix.rotateM(m, 0, o.ry, 0f, 1f, 0f)
            if (o.shape != Shape.TREE) {
                Matrix.rotateM(m, 0, o.rx, 1f, 0f, 0f)
                Matrix.rotateM(m, 0, o.rz, 0f, 0f, 1f)
            }
            Matrix.scaleM(m, 0, o.sx, o.sy, o.sz)
            val out = FloatArray(nv * 3)
            for (v in 0 until nv) {
                val x = data[v * 6]
                val y = data[v * 6 + 1]
                val z = data[v * 6 + 2]
                out[v * 3] = m[0] * x + m[4] * y + m[8] * z + m[12]
                out[v * 3 + 1] = m[1] * x + m[5] * y + m[9] * z + m[13]
                out[v * 3 + 2] = m[2] * x + m[6] * y + m[10] * z + m[14]
            }
            parts.add(out)
            total += out.size
        }
        val all = FloatArray(total)
        var p = 0
        for (a in parts) { System.arraycopy(a, 0, all, p, a.size); p += a.size }
        return all
    }

    /**
     * Objects block point lights (and fires) for everything behind them: each light gets a cube depth map of the
     * real shapes of all shadow casters, and every object is lit only by the lights it can "see".
     */
    private fun updateLightVisibility() {
        visActive = false
        if (litCount == 0) return
        val casters = scene.objects.filter { o ->
            o.shadow && !o.shape.isScreen && !o.shape.isLight && o.shape != Shape.SETTINGS && o.shape != Shape.CAMERA &&
                o.shape != Shape.TERRAIN && o.shape != Shape.BONE && o.shape != Shape.WATER && o.shape != Shape.GRASS && !o.shape.isEmitter && max(o.sx, max(o.sy, o.sz)) >= 0.35f
        }
        if (casters.isEmpty()) return
        val sb = StringBuilder()
        sb.append(litCount).append('|')
        for (i in 0 until litCount * 3) sb.append(litPos[i]).append(',')
        sb.append('|')
        for (o in casters) {
            sb.append(o.shape.ordinal).append(',').append(o.x).append(',').append(o.y).append(',').append(o.z).append(',')
                .append(o.rx).append(',').append(o.ry).append(',').append(o.rz).append(',')
                .append(o.sx).append(',').append(o.sy).append(',').append(o.sz).append(';')
        }
        val key = sb.toString()
        val now = System.currentTimeMillis()
        if (key != visKey && !(visReady && playing && now - visTime < 100)) {
            val tris = casterTriangles(casters)
            val nTri = tris.size / 9
            for (i in 0 until litCount) buildCube(visDepth, i, litPos[i * 3], litPos[i * 3 + 1], litPos[i * 3 + 2], tris, nTri)
            visKey = key
            visTime = now
            visReady = true
            uploadCubeAtlas()
        }
        visActive = visReady && cubeTex != 0
    }

    /** Packs the light cube depth maps into one RGBA texture (6 faces across, one row per light) for the shader. */
    private fun uploadCubeAtlas() {
        val buf = cubeAtlas
        buf.clear()
        val wAtlas = 6 * CS
        val hAtlas = 4 * CS
        for (row in 0 until hAtlas) {
            val li = row / CS
            val iy = row % CS
            for (col in 0 until wAtlas) {
                val f = col / CS
                val ix = col % CS
                val inv = if (li < litCount) visDepth[(li * 6 + f) * CS * CS + iy * CS + ix] else 0f
                val sc = (inv / 20f).coerceIn(0f, 0.99999f) * 255f
                val r8 = sc.toInt()
                val rem = (sc - r8) * 255f
                val g8 = rem.toInt()
                val b8 = ((rem - g8) * 255f).toInt()
                buf.put(r8.toByte()); buf.put(g8.toByte()); buf.put(b8.toByte()); buf.put(255.toByte())
            }
        }
        buf.position(0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE4)
        if (cubeTex == 0) {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            cubeTex = ids[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, cubeTex)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        } else {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, cubeTex)
        }
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, wAtlas, hAtlas, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    /** How much of each light reaches this object: measured at the point of its bounding sphere that faces the light. */
    /** Selected, picked in Multi mode, or a member of the selected group. */
    private fun hl(o: SceneObject, sel: SceneObject?): Boolean {
        if (o === sel) return true
        if (scene.multi.contains(o)) return true
        return sel != null && scene.isGroup(sel) && Rig.isDescendant(scene, o, sel)
    }

    private fun setLightVis(o: SceneObject) {
        var shaded = false
        if (false && visActive && o.shape != Shape.TERRAIN && o.shape != Shape.WATER && o.shape != Shape.GRASS && !o.shape.isEmitter) {
            val cx = o.x
            val cy = if (o.shape == Shape.TREE) o.y + o.sy * 0.5f else o.y
            val cz = o.z
            val r = 0.5f * sqrt(o.sx * o.sx + o.sy * o.sy + o.sz * o.sz)
            for (i in 0 until 4) {
                lightVis[i] = 1f
                if (i >= litCount) continue
                val lx = litPos[i * 3]
                val ly = litPos[i * 3 + 1]
                val lz = litPos[i * 3 + 2]
                val dx = lx - cx
                val dy = ly - cy
                val dz = lz - cz
                val dist = sqrt(dx * dx + dy * dy + dz * dz)
                if (dist < 0.15f) continue
                val m = max(0f, min(r, dist - 0.15f))
                val px = cx + dx / dist * m
                val py = cy + dy / dist * m
                val pz = cz + dz / dist * m
                val frac = lightShadowFrac(visDepth, i, lx, ly, lz, px, py, pz)
                lightVis[i] = 1f - frac
                if (frac > 0f) shaded = true
            }
        }
        GLES20.glUniform1fv(uLVis, 4, if (shaded) lightVis else visOnes, 0)
    }

    private fun buildCube(buf: FloatArray, li: Int, lx: Float, ly: Float, lz: Float, tris: FloatArray, nTri: Int) {
        val base = li * 6 * CS * CS
        java.util.Arrays.fill(buf, base, base + 6 * CS * CS, 0f)
        for (f in 0 until 6) {
            val fr = faceR[f]; val fu = faceU[f]; val ff = faceF[f]
            val off = base + f * CS * CS
            for (t in 0 until nTri) {
                val o = t * 9
                val ax = tris[o] - lx; val ay = tris[o + 1] - ly; val az = tris[o + 2] - lz
                val bx = tris[o + 3] - lx; val by = tris[o + 4] - ly; val bz = tris[o + 5] - lz
                val cx = tris[o + 6] - lx; val cy = tris[o + 7] - ly; val cz = tris[o + 8] - lz
                fillPersp(
                    buf, off, CS,
                    ax * fr[0] + ay * fr[1] + az * fr[2], ax * fu[0] + ay * fu[1] + az * fu[2], ax * ff[0] + ay * ff[1] + az * ff[2],
                    bx * fr[0] + by * fr[1] + bz * fr[2], bx * fu[0] + by * fu[1] + bz * fu[2], bx * ff[0] + by * ff[1] + bz * ff[2],
                    cx * fr[0] + cy * fr[1] + cz * fr[2], cx * fu[0] + cy * fu[1] + cz * fu[2], cx * ff[0] + cy * ff[1] + cz * ff[2]
                )
            }
        }
    }

    /** 0..1: how much of the point light's view of (px,py,pz) is blocked by a caster's real shape. */
    private fun lightShadowFrac(buf: FloatArray, li: Int, lx: Float, ly: Float, lz: Float, px: Float, py: Float, pz: Float): Float {
        val qx = px - lx
        val qy = py - ly
        val qz = pz - lz
        val ax = abs(qx); val ay = abs(qy); val az = abs(qz)
        val f = if (ax >= ay && ax >= az) (if (qx >= 0f) 0 else 1)
        else if (ay >= az) (if (qy >= 0f) 2 else 3)
        else (if (qz >= 0f) 4 else 5)
        val fr = faceR[f]; val fu = faceU[f]; val ff = faceF[f]
        val z = qx * ff[0] + qy * ff[1] + qz * ff[2]
        if (z < 0.05f) return 0f
        val x = qx * fr[0] + qy * fr[1] + qz * fr[2]
        val y = qx * fu[0] + qy * fu[1] + qz * fu[2]
        val cx = ((x / z + 1f) * 0.5f * CS).toInt()
        val cy = ((y / z + 1f) * 0.5f * CS).toInt()
        val off = (li * 6 + f) * CS * CS
        val invZ = 1f / z
        var cnt = 0
        var tot = 0
        for (dy in -1..1) for (dx in -1..1) {
            val ix = cx + dx
            val iy = cy + dy
            if (ix < 0 || iy < 0 || ix >= CS || iy >= CS) continue
            tot++
            if (buf[off + iy * CS + ix] > invZ * 1.04f) cnt++
        }
        return if (tot == 0) 0f else cnt.toFloat() / tot
    }

    private fun bakeShadowMap(
        terrain: SceneObject, strength: Float, soft: Float, sunOk: Boolean,
        dx0: Float, dy0: Float, dz0: Float, lights: List<SceneObject>, casters: List<SceneObject>
    ) {
        val tris = casterTriangles(casters)
        val nTri = tris.size / 9

        // ---- sun: orthographic depth map looking down the sun's direction over the terrain ----
        var rx = 0f; var ry = 0f; var rz = 0f
        var ux = 0f; var uy = 0f; var uz = 0f
        var aMin = 0f; var bMin = 0f; var sA = 1f; var sB = 1f
        val sunMap = sunOk && nTri > 0
        if (sunMap) {
            // right = up x dir
            rx = dz0; ry = 0f; rz = -dx0
            var rl = sqrt(rx * rx + rz * rz)
            if (rl < 0.01f) { rx = 1f; rz = 0f; rl = 1f }
            rx /= rl; rz /= rl
            // up = dir x right
            ux = dy0 * rz - dz0 * ry
            uy = dz0 * rx - dx0 * rz
            uz = dx0 * ry - dy0 * rx
            var aLo = Float.MAX_VALUE; var aHi = -Float.MAX_VALUE
            var bLo = Float.MAX_VALUE; var bHi = -Float.MAX_VALUE
            for (cxI in 0..1) for (cyI in 0..1) for (czI in 0..1) {
                val px = terrain.x + (cxI - 0.5f) * terrain.sx
                val py = terrain.y + cyI * terrain.sy
                val pz = terrain.z + (czI - 0.5f) * terrain.sz
                val a = px * rx + py * ry + pz * rz
                val b = px * ux + py * uy + pz * uz
                aLo = minOf(aLo, a); aHi = maxOf(aHi, a)
                bLo = minOf(bLo, b); bHi = maxOf(bHi, b)
            }
            aMin = aLo; bMin = bLo
            sA = SM / max(aHi - aLo, 0.01f)
            sB = SM / max(bHi - bLo, 0.01f)
            java.util.Arrays.fill(sunDepth, -1e30f)
            for (t in 0 until nTri) {
                val o = t * 9
                val x0 = tris[o]; val y0 = tris[o + 1]; val z0 = tris[o + 2]
                val x1 = tris[o + 3]; val y1 = tris[o + 4]; val z1 = tris[o + 5]
                val x2 = tris[o + 6]; val y2 = tris[o + 7]; val z2 = tris[o + 8]
                fillTri(
                    sunDepth, 0, SM,
                    (x0 * rx + y0 * ry + z0 * rz - aMin) * sA, (x0 * ux + y0 * uy + z0 * uz - bMin) * sB, x0 * dx0 + y0 * dy0 + z0 * dz0,
                    (x1 * rx + y1 * ry + z1 * rz - aMin) * sA, (x1 * ux + y1 * uy + z1 * uz - bMin) * sB, x1 * dx0 + y1 * dy0 + z1 * dz0,
                    (x2 * rx + y2 * ry + z2 * rz - aMin) * sA, (x2 * ux + y2 * uy + z2 * uz - bMin) * sB, x2 * dx0 + y2 * dy0 + z2 * dz0
                )
            }
        }

        val cell = max(terrain.sx, terrain.sz) / TerrainGen.N
        val top = terrain.y + terrain.sy
        // sample spacing in sun-map pixels (about a third of one shadow-map texel)
        val spA = terrain.sx / SH * sA / 3f
        val spB = terrain.sz / SH * sB / 3f

        // ---- one texel at a time ----
        // each light gets a cube depth map made of its real-shape casters
        if (nTri > 0) for ((li, l) in lights.withIndex()) buildCube(cubeDepth, li, l.x, l.y, l.z, tris, nTri)
        for (j in 0 until SH) {
            for (i in 0 until SH) {
                val wx = terrain.x + ((i + 0.5f) / SH - 0.5f) * terrain.sx
                val wz = terrain.z + ((j + 0.5f) / SH - 0.5f) * terrain.sz
                val wy = (TerrainGen.heightAt(terrain, wx, wz) ?: terrain.y) + 0.06f
                var sunS = 0f
                if (sunOk) {
                    var hit = false
                    var t = cell
                    while (t < 400f) {
                        val py = wy + dy0 * t
                        if (py > top) break
                        val h = TerrainGen.heightAt(terrain, wx + dx0 * t, wz + dz0 * t) ?: break
                        if (h > py) { hit = true; break }
                        t += cell
                    }
                    if (hit) {
                        sunS = 1f
                    } else if (sunMap) {
                        val depth = wx * dx0 + wy * dy0 + wz * dz0
                        val pa = (wx * rx + wy * ry + wz * rz - aMin) * sA
                        val pb = (wx * ux + wy * uy + wz * uz - bMin) * sB
                        var cnt = 0
                        for (qy in -1..1) for (qx in -1..1) {
                            val ix = (pa + qx * spA).toInt()
                            val iy = (pb + qy * spB).toInt()
                            if (ix < 0 || iy < 0 || ix >= SM || iy >= SM) continue
                            if (sunDepth[iy * SM + ix] > depth + 0.1f) cnt++
                        }
                        sunS = cnt / 9f
                    }
                }
                var pw = 0f
                var tw = 0f
                for ((li, l) in lights.withIndex()) {
                    val ex = l.x - wx
                    val ey = l.y - wy
                    val ez = l.z - wz
                    val dist = sqrt(ex * ex + ey * ey + ez * ez)
                    val range = max(l.sy, 0.1f)
                    if (dist >= range || dist < 0.05f) continue
                    val k = 1f - dist / range
                    val att = k * k * kotlin.math.min(1f, l.sx / 1.5f)
                    tw += att
                    val dx = ex / dist
                    val dy = ey / dist
                    val dz = ez / dist
                    var hit = false
                    var t = cell
                    while (t < dist) {
                        val h = TerrainGen.heightAt(terrain, wx + dx * t, wz + dz * t) ?: break
                        if (h > wy + dy * t) { hit = true; break }
                        t += cell
                    }
                    val frac = if (hit) 1f else if (nTri > 0) lightShadowFrac(cubeDepth, li, l.x, l.y, l.z, wx, wy, wz) else 0f
                    pw += att * frac
                }
                shSunMap[j * SH + i] = sunS * strength
                shPtMap[j * SH + i] = (if (tw > 0f) pw / tw else 0f) * strength
            }
        }
        val br = (soft * 3f + 0.5f).toInt()
        blurMap(shSunMap, br)
        blurMap(shPtMap, br)
        shBuf.clear()
        for (k in 0 until SH * SH) {
            shBuf.put((shSunMap[k].coerceIn(0f, 1f) * 255f).toInt().toByte())
            shBuf.put((shPtMap[k].coerceIn(0f, 1f) * 255f).toInt().toByte())
            shBuf.put(0.toByte())
            shBuf.put(255.toByte())
        }
        shBuf.position(0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        if (shTex == 0) {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            shTex = ids[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, shTex)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        } else {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, shTex)
        }
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, SH, SH, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, shBuf)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    private fun terrainMeshFor(o: SceneObject): Mesh {
        val key = "" + o.hVersion + "|" + o.sx + "|" + o.sy + "|" + o.sz
        val e = terrainCache[o]
        if (e != null && e.first == key) return e.second
        val data = TerrainGen.buildMesh(o)
        if (e == null) {
            val m = Mesh(data, GLES20.GL_TRIANGLES)
            terrainCache[o] = Pair(key, m)
            return m
        }
        e.second.update(data)
        terrainCache[o] = Pair(key, e.second)
        return e.second
    }

    private fun meshFor(o: SceneObject): Mesh {
        if (o.shape == Shape.TERRAIN) return terrainMeshFor(o)
        if (o.shape == Shape.TREE) return treeAllMesh(o.pattern.coerceIn(0, 1))
        val md = o.model
        return if (md != null) modelMeshes.getOrPut(md.key) { Mesh(md.data, GLES20.GL_TRIANGLES) }
        else meshes.getValue(o.shape)
    }

    override fun onDrawFrame(gl: GL10?) {
        onFrame?.invoke()
        val eyeC = FloatArray(3)
        val camAxes = FloatArray(6)
        val vpc = synchronized(lock) {
            updateMatrices()
            System.arraycopy(eye, 0, eyeC, 0, 3)
            camAxes[0] = view[0]; camAxes[1] = view[4]; camAxes[2] = view[8]
            camAxes[3] = view[1]; camAxes[4] = view[5]; camAxes[5] = view[9]
            vp.copyOf()
        }
        val settings = scene.objects.firstOrNull { it.shape == Shape.SETTINGS }
        if (settings != null) GLES20.glClearColor(settings.color[0], settings.color[1], settings.color[2], 1f)
        else GLES20.glClearColor(0.15f, 0.16f, 0.19f, 1f)
        GLES20.glUseProgram(prog)
        System.arraycopy(eyeC, 0, curEye, 0, 3)
        setLights(eyeC)
        GLES20.glUniform1f(uWater, 0f)
        GLES20.glUniform1f(uGrass, 0f)
        GLES20.glUniform1f(uParticle, 0f)
        GLES20.glUniform1f(uGlass, 0f)
        GLES20.glUniform1f(uVeg, 0f)
        GLES20.glUniform1f(uHasEnv, 0f)
        GLES20.glUniform1i(uEnv, 1)
        GLES20.glUniform1i(uShadow, 2)
        bakeTerrainShadows()

        // Mirrors: draw the scene again from the mirrored camera into a texture (up to 2 mirrors)
        mirrorIndex.clear()
        mirrorOk = false
        val mirrors = scene.objects.filter { it.shape == Shape.MIRROR }.take(MAXM)
        if (mirrors.isNotEmpty() && ensureMirrorTargets()) {
            mirrorOk = true
            for ((i, mo) in mirrors.withIndex()) {
                mirrorIndex[mo] = i
                renderMirrorPass(i, mo, vpc, eyeC, camAxes, settings)
            }
        }
        renderScene(vpc, eyeC, camAxes, settings, false)
    }

    private fun ensureMirrorTargets(): Boolean {
        val fw = max(w / 2, 128)
        val fh = max(h / 2, 128)
        if (mirW == fw && mirH == fh && mirFbo[0] != 0) return true
        for (i in 0 until MAXM) {
            if (mirTex[i] != 0) GLES20.glDeleteTextures(1, intArrayOf(mirTex[i]), 0)
            if (mirFbo[i] != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(mirFbo[i]), 0)
            if (mirDepth[i] != 0) GLES20.glDeleteRenderbuffers(1, intArrayOf(mirDepth[i]), 0)
        }
        val tex = IntArray(MAXM)
        val fbo = IntArray(MAXM)
        val rb = IntArray(MAXM)
        GLES20.glGenTextures(MAXM, tex, 0)
        GLES20.glGenFramebuffers(MAXM, fbo, 0)
        GLES20.glGenRenderbuffers(MAXM, rb, 0)
        var ok = true
        for (i in 0 until MAXM) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[i])
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, fw, fh, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, rb[i])
            GLES20.glRenderbufferStorage(GLES20.GL_RENDERBUFFER, GLES20.GL_DEPTH_COMPONENT16, fw, fh)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[i])
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex[i], 0)
            GLES20.glFramebufferRenderbuffer(GLES20.GL_FRAMEBUFFER, GLES20.GL_DEPTH_ATTACHMENT, GLES20.GL_RENDERBUFFER, rb[i])
            if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) ok = false
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, 0)
        for (i in 0 until MAXM) { mirTex[i] = tex[i]; mirFbo[i] = fbo[i]; mirDepth[i] = rb[i] }
        mirW = fw
        mirH = fh
        return ok
    }

    /** Draws the scene as seen in the mirror: the world is flipped across the mirror plane and anything behind the mirror is cut away. */
    private fun renderMirrorPass(idx: Int, m: SceneObject, vpc: FloatArray, eyeC: FloatArray, camAxes: FloatArray, settings: SceneObject?) {
        val rm = FloatArray(16)
        Matrix.setIdentityM(rm, 0)
        Matrix.rotateM(rm, 0, m.ry, 0f, 1f, 0f)
        Matrix.rotateM(rm, 0, m.rx, 1f, 0f, 0f)
        Matrix.rotateM(rm, 0, m.rz, 0f, 0f, 1f)
        val nIn = floatArrayOf(0f, 0f, 1f, 0f)
        val nOut = FloatArray(4)
        Matrix.multiplyMV(nOut, 0, rm, 0, nIn, 0)
        var nx = nOut[0]
        var ny = nOut[1]
        var nz = nOut[2]
        val nl = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(0.0001f)
        nx /= nl; ny /= nl; nz /= nl
        // the mirror shows whichever side the camera is on
        if ((eyeC[0] - m.x) * nx + (eyeC[1] - m.y) * ny + (eyeC[2] - m.z) * nz < 0f) { nx = -nx; ny = -ny; nz = -nz }
        val d = -(nx * m.x + ny * m.y + nz * m.z)
        val r = FloatArray(16)
        val nn = floatArrayOf(nx, ny, nz)
        for (j in 0..2) for (i in 0..2) r[j * 4 + i] = (if (i == j) 1f else 0f) - 2f * nn[i] * nn[j]
        for (i in 0..2) r[12 + i] = -2f * d * nn[i]
        r[15] = 1f
        val vpr = FloatArray(16)
        Matrix.multiplyMM(vpr, 0, vpc, 0, r, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mirFbo[idx])
        GLES20.glViewport(0, 0, mirW, mirH)
        GLES20.glUniform1f(uClipOn, 1f)
        GLES20.glUniform4f(uClipPl, nx, ny, nz, d)
        renderScene(vpr, eyeC, camAxes, settings, true)
        GLES20.glUniform1f(uClipOn, 0f)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, w, h)
    }

    private fun drawMirror(o: SceneObject, vpc: FloatArray, sel: SceneObject?) {
        val idx = mirrorIndex[o] ?: -1
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, o.x, o.y, o.z)
        Matrix.rotateM(model, 0, o.ry, 0f, 1f, 0f)
        Matrix.rotateM(model, 0, o.rx, 1f, 0f, 0f)
        Matrix.rotateM(model, 0, o.rz, 0f, 0f, 1f)
        Matrix.scaleM(model, 0, o.sx * 0.5f, o.sy * 0.5f, 1f)
        setPlain(0.5f, 0.8f)
        GLES20.glUniform1f(uGlass, 0f)
        if (idx >= 0 && mirrorOk) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mirTex[idx])
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glUniform1i(uMirrorTex, 3)
            GLES20.glUniform2f(uScreen, w.toFloat(), h.toFloat())
            GLES20.glUniform1f(uMirror, 1f)
        }
        val c = if (hl(o, sel)) floatArrayOf(minOf(1f, o.color[0] + 0.25f), minOf(1f, o.color[1] + 0.25f), minOf(1f, o.color[2] + 0.25f)) else o.color
        drawMesh(quad, vpc, model, c, true)
        GLES20.glUniform1f(uMirror, 0f)
    }

    /** Sky, grid, all objects, shadows, markers and gizmo. [mirror] = drawing the reflection into the mirror texture. */
    private fun renderScene(vpc: FloatArray, eyeC: FloatArray, camAxes: FloatArray, settings: SceneObject?, mirror: Boolean) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        // Sky photo (Settings): panorama wraps all the way round the camera; backdrop is a fixed picture behind the scene
        val skyKey = settings?.photo ?: ""
        if (skyKey.isNotEmpty()) {
            val tex = textureFor(skyKey)
            if (tex > 0) {
                GLES20.glUniform1f(uClipOn, 0f)
                // the sky photo is also what shiny things (glass, water, metal) reflect
                GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glUniform1f(uHasEnv, 1f)
                val backdrop = settings != null && settings.pattern == 1
                GLES20.glDisable(GLES20.GL_DEPTH_TEST)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
                GLES20.glUniform1i(uTex, 0)
                GLES20.glUniform1f(uUseTex, 1f)
                val white = floatArrayOf(1f, 1f, 1f)
                Matrix.setIdentityM(model, 0)
                if (backdrop) {
                    val a = photoAspect[skyKey] ?: 1f
                    val sa = w.toFloat() / h
                    var uS = 1f
                    var vS = 1f
                    if (a > sa) uS = sa / a else vS = a / sa
                    val pan = (1f - uS) * 0.5f * sin(Math.toRadians(yaw.toDouble())).toFloat()
                    GLES20.glUniform3f(uObjScale, uS, vS, pan)
                    val ident = FloatArray(16)
                    Matrix.setIdentityM(ident, 0)
                    skyLit = 3f
                    drawMesh(quad, ident, model, white, false)
                } else {
                    GLES20.glUniform1f(uMapMode, 1f)
                    Matrix.translateM(model, 0, eyeC[0], eyeC[1], eyeC[2])
                    Matrix.scaleM(model, 0, 140f, 140f, 140f)
                    skyLit = 2f
                    drawMesh(meshes.getValue(Shape.SPHERE), vpc, model, white, false)
                }
                skyLit = 0f
                GLES20.glEnable(GLES20.GL_DEPTH_TEST)
                if (mirror) GLES20.glUniform1f(uClipOn, 1f)
            }
        }

        // Grid + axes
        Matrix.setIdentityM(model, 0)
        val showGrid = settings == null || settings.sy >= 0.5f
        GLES20.glLineWidth(1f)
        if (showGrid && !mirror) drawMesh(grid, vpc, model, floatArrayOf(0.35f, 0.37f, 0.42f), false, 0, grid.count - 4)
        GLES20.glLineWidth(2f)
        if (showGrid && !mirror) drawMesh(grid, vpc, model, floatArrayOf(0.85f, 0.3f, 0.3f), false, grid.count - 4, 2)
        if (showGrid && !mirror) drawMesh(grid, vpc, model, floatArrayOf(0.3f, 0.45f, 0.9f), false, grid.count - 2, 2)

        // Objects: solid ones first, then see-through ones (far to near) so they blend correctly
        val sel = scene.selected
        val seeThrough = ArrayList<SceneObject>()
        val emitters = ArrayList<SceneObject>()
        for (o in scene.objects) {
            if (o.shape.isLight || o.shape == Shape.CAMERA || o.shape == Shape.BONE) continue
            if (mirror && o.shape == Shape.MIRROR) continue
            if (o.shape.isScreen || o.shape == Shape.SETTINGS || o === fpHide) continue
            if (o.shape.isEmitter) { emitters.add(o); continue }
            if (o.shape == Shape.WATER || o.opacity < 0.99f) { seeThrough.add(o); continue }
            drawObject(o, vpc, sel, 1f)
        }
        if (seeThrough.isNotEmpty()) {
            seeThrough.sortByDescending { o ->
                (o.x - eyeC[0]) * (o.x - eyeC[0]) + (o.y - eyeC[1]) * (o.y - eyeC[1]) + (o.z - eyeC[2]) * (o.z - eyeC[2])
            }
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glDepthMask(false)
            for (o in seeThrough) drawObject(o, vpc, sel, o.opacity.coerceIn(0.02f, 1f))
            GLES20.glDepthMask(true)
            GLES20.glDisable(GLES20.GL_BLEND)
        }

        if (emitters.isNotEmpty()) {
            emitters.sortByDescending { o ->
                (o.x - eyeC[0]) * (o.x - eyeC[0]) + (o.y - eyeC[1]) * (o.y - eyeC[1]) + (o.z - eyeC[2]) * (o.z - eyeC[2])
            }
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glDepthMask(false)
            for (o in emitters) drawEmitter(o, vpc, camAxes)
            GLES20.glDepthMask(true)
            GLES20.glDisable(GLES20.GL_BLEND)
        }

        GLES20.glUniform1fv(uLVis, 4, visOnes, 0)
        drawShadows(vpc)

        // Light markers: drawn on top of everything, kept a constant size on screen so they stay visible when zoomed out
        if (!playing && !mirror) {
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            for (o in scene.objects) {
                if (o.shape.isLight) drawLightMarker(o, vpc)
                else if (o.shape == Shape.CAMERA) drawCameraMarker(o, vpc)
                else if (o.shape == Shape.BONE) drawBoneMarker(o, vpc)
            }
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        }

        // Gizmo on selection
        if (!mirror && sel != null && !sel.shape.isScreen && sel.shape != Shape.SETTINGS) {
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
