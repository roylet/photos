package com.roylet.photos.editor.gl

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Offscreen targets for the live preview (GL-thread only):
 *
 * - **draft**: while a slider or crop handle is being dragged the edit is rendered into a smaller
 *   target (long edge ≤ [EditRenderer.DRAFT_EDGE]) and stretched to the view, so each frame shades
 *   a fraction of the pixels;
 * - **compare**: the edit and the original are rendered into one target each and mixed, so
 *   hold-to-compare crossfades the image itself.
 */
internal class PreviewCompositor {
    private val program = GlPipeline.linkProgram(VERTEX, FRAGMENT)
    private val uEdit = GLES30.glGetUniformLocation(program, U_EDIT)
    private val uOriginal = GLES30.glGetUniformLocation(program, U_ORIGINAL)
    private val uMix = GLES30.glGetUniformLocation(program, U_MIX)
    private val vao: Int
    private val vbo: Int
    val edit = Target()
    val original = Target()

    init {
        val quad = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
        val buf = ByteBuffer.allocateDirect(quad.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(quad).position(0)
        val b = IntArray(1)
        GLES30.glGenBuffers(1, b, 0); vbo = b[0]
        GLES30.glGenVertexArrays(1, b, 0); vao = b[0]
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, quad.size * 4, buf, GLES30.GL_STATIC_DRAW)
        val aPos = GLES30.glGetAttribLocation(program, A_POS)
        GLES30.glEnableVertexAttribArray(aPos)
        GLES30.glVertexAttribPointer(aPos, 2, GLES30.GL_FLOAT, false, 8, 0)
        GLES30.glBindVertexArray(0)
    }

    /** A colour texture plus framebuffer, reallocated only when its size changes. */
    class Target {
        private var fbo = 0
        private var tex = 0
        var width = 0; private set
        var height = 0; private set
        val texture: Int get() = tex

        /** Binds this target for drawing at [w]×[h] (and sets the viewport). */
        fun bind(w: Int, h: Int) {
            if (fbo == 0) {
                val ids = IntArray(1)
                GLES30.glGenFramebuffers(1, ids, 0); fbo = ids[0]
                GLES30.glGenTextures(1, ids, 0); tex = ids[0]
            }
            if (w != width || h != height) {
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
                GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex, 0)
                width = w; height = h
            }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
            GLES30.glViewport(0, 0, w, h)
        }

        fun release() {
            if (fbo != 0) {
                GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
                GLES30.glDeleteTextures(1, intArrayOf(tex), 0)
            }
            fbo = 0; tex = 0; width = 0; height = 0
        }
    }

    /**
     * Draws `mix(edit, original, mix)` stretched over the default framebuffer ([viewW]×[viewH]).
     * With mix 0 only [edit] is read (and vice versa), so a draft needs just one target.
     */
    fun present(viewW: Int, viewH: Int, mix: Float) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, viewW, viewH)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glUseProgram(program)
        val a = if (mix >= 1f) original.texture else edit.texture
        val b = if (mix <= 0f) edit.texture else original.texture
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, a)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, b)
        GLES30.glUniform1i(uEdit, 0)
        GLES30.glUniform1i(uOriginal, 1)
        GLES30.glUniform1f(uMix, mix.coerceIn(0f, 1f))
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)
        GlPipeline.checkGl("present")
    }

    fun release() {
        edit.release(); original.release()
        GLES30.glDeleteBuffers(1, intArrayOf(vbo), 0)
        GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
        GLES30.glDeleteProgram(program)
    }

    companion object {
        const val A_POS = "aPos"
        const val U_EDIT = "uEdit"
        const val U_ORIGINAL = "uOriginal"
        const val U_MIX = "uMix"
        val UNIFORMS = listOf(U_EDIT, U_ORIGINAL, U_MIX)

        val VERTEX = """#version 300 es
in vec2 $A_POS;
out vec2 vUv;
void main() {
    vUv = $A_POS;
    gl_Position = vec4($A_POS * 2.0 - 1.0, 0.0, 1.0);
}
"""

        val FRAGMENT = """#version 300 es
precision mediump float;
in vec2 vUv;
uniform sampler2D $U_EDIT;
uniform sampler2D $U_ORIGINAL;
uniform float $U_MIX;
out vec4 fragColor;
void main() {
    fragColor = mix(texture($U_EDIT, vUv), texture($U_ORIGINAL, vUv), $U_MIX);
}
"""
    }
}
