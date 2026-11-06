package com.example.minieditor

import android.opengl.GLSurfaceView
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay

/** Asks the phone for a multisampled (anti-aliased) screen surface: 4x, then 2x, then plain if neither exists. */
class MsaaConfigChooser : GLSurfaceView.EGLConfigChooser {
    override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
        // 24-bit depth first (a big map needs the precision), then 16; each with 4x, 2x, then no anti-aliasing
        val tries = listOf(24 to 4, 24 to 2, 16 to 4, 16 to 2, 24 to 0, 16 to 0)
        for ((depth, samples) in tries) {
            val attribs = ArrayList<Int>().apply {
                add(EGL10.EGL_RENDERABLE_TYPE); add(4)          // OpenGL ES 2
                add(EGL10.EGL_RED_SIZE); add(8)
                add(EGL10.EGL_GREEN_SIZE); add(8)
                add(EGL10.EGL_BLUE_SIZE); add(8)
                add(EGL10.EGL_ALPHA_SIZE); add(8)
                add(EGL10.EGL_DEPTH_SIZE); add(depth)
                if (samples > 0) {
                    add(EGL10.EGL_SAMPLE_BUFFERS); add(1)
                    add(EGL10.EGL_SAMPLES); add(samples)
                }
                add(EGL10.EGL_NONE)
            }.toIntArray()
            val num = IntArray(1)
            if (!egl.eglChooseConfig(display, attribs, null, 0, num) || num[0] <= 0) continue
            val configs = arrayOfNulls<EGLConfig>(num[0])
            if (!egl.eglChooseConfig(display, attribs, configs, num[0], num)) continue
            // prefer the config whose colour/depth sizes match what we asked for
            val best = configs.filterNotNull().firstOrNull { c ->
                fun v(a: Int): Int { val o = IntArray(1); egl.eglGetConfigAttrib(display, c, a, o); return o[0] }
                v(EGL10.EGL_RED_SIZE) == 8 && v(EGL10.EGL_GREEN_SIZE) == 8 && v(EGL10.EGL_BLUE_SIZE) == 8 &&
                    v(EGL10.EGL_DEPTH_SIZE) >= depth && (samples == 0 || v(EGL10.EGL_SAMPLES) >= samples)
            } ?: configs.filterNotNull().firstOrNull()
            if (best != null) return best
        }
        throw IllegalStateException("No EGL config")
    }
}
