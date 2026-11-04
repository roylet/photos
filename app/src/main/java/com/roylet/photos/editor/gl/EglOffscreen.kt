package com.roylet.photos.editor.gl

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface

/**
 * A private OpenGL ES 3.0 context with a 1×1 pbuffer, current on the creating thread until
 * [close] (which restores whatever context was current before). Used for export, which renders
 * into FBOs.
 */
internal class EglOffscreen : AutoCloseable {
    private val prevDisplay: EGLDisplay = EGL14.eglGetCurrentDisplay()
    private val prevContext: EGLContext = EGL14.eglGetCurrentContext()
    private val prevDraw: EGLSurface = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
    private val prevRead: EGLSurface = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)

    private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val context: EGLContext
    private val surface: EGLSurface

    init {
        check(display != EGL14.EGL_NO_DISPLAY) { "No EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
        val attribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) && count[0] > 0) {
            "No ES3 EGL config"
        }
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed: 0x${Integer.toHexString(EGL14.eglGetError())}" }
        surface = EGL14.eglCreatePbufferSurface(display, configs[0],
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        check(surface != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface failed" }
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent failed" }
    }

    override fun close() {
        if (prevContext != EGL14.EGL_NO_CONTEXT) {
            EGL14.eglMakeCurrent(prevDisplay, prevDraw, prevRead, prevContext)
        } else {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        }
        EGL14.eglDestroySurface(display, surface)
        EGL14.eglDestroyContext(display, context)
        if (prevContext == EGL14.EGL_NO_CONTEXT) EGL14.eglReleaseThread()
    }
}
