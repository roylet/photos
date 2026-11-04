package com.roylet.photos.editor.gl

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLUtils
import android.util.Log
import com.roylet.photos.editor.cpu.ArgbImage
import com.roylet.photos.editor.recipe.CurveMath
import com.roylet.photos.editor.recipe.Lut3D
import com.roylet.photos.editor.recipe.RenderParams
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * GL resources for one context: the program, a unit quad, and the source / blur / curve / LUT
 * textures. Every method must run on the thread where that context is current.
 */
internal class GlPipeline {
    private val program: Int = linkProgram(EditShaders.VERTEX, EditShaders.FRAGMENT)
    private val locations = HashMap<String, Int>()
    private val vbo: Int
    private val vao: Int
    private val texSource: Int
    private val texBlur: Int
    private val texCurve: Int
    private val texLut: Int

    var sourceWidth = 0; private set
    var sourceHeight = 0; private set
    private var lastCurve: FloatArray? = null
    private var curveUploaded = false
    private var lastLut: Lut3D? = null
    private var lutSize = 2f

    init {
        for (name in EditShaders.ALL_UNIFORMS) locations[name] = GLES30.glGetUniformLocation(program, name)
        val ids = IntArray(4)
        GLES30.glGenTextures(4, ids, 0)
        texSource = ids[0]; texBlur = ids[1]; texCurve = ids[2]; texLut = ids[3]

        val quad = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
        val buf = ByteBuffer.allocateDirect(quad.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(quad).position(0)
        val b = IntArray(1)
        GLES30.glGenBuffers(1, b, 0); vbo = b[0]
        GLES30.glGenVertexArrays(1, b, 0); vao = b[0]
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, quad.size * 4, buf, GLES30.GL_STATIC_DRAW)
        val aPos = GLES30.glGetAttribLocation(program, EditShaders.A_POS)
        GLES30.glEnableVertexAttribArray(aPos)
        GLES30.glVertexAttribPointer(aPos, 2, GLES30.GL_FLOAT, false, 8, 0)
        GLES30.glBindVertexArray(0)

        // Placeholders so every sampler is complete before real data arrives.
        uploadBlur(ArgbImage.filled(1, 1, 0xFF808080.toInt()))
        uploadCurve(null)
        uploadLut(null)
        configure2D(texSource, mipmap = false)
        checkGl("init")
    }

    val maxTextureSize: Int by lazy {
        val v = IntArray(1); GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, v, 0); v[0]
    }

    val maxViewport: Int by lazy {
        val v = IntArray(2); GLES30.glGetIntegerv(GLES30.GL_MAX_VIEWPORT_DIMS, v, 0); minOf(v[0], v[1])
    }

    /** Uploads [bitmap] (software ARGB_8888) as the source, with mipmaps for zoomed-out preview. */
    fun uploadSource(bitmap: Bitmap) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texSource)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        configure2D(texSource, mipmap = true)
        sourceWidth = bitmap.width
        sourceHeight = bitmap.height
        checkGl("uploadSource")
    }

    fun uploadBlur(img: ArgbImage) {
        val buf = ByteBuffer.allocateDirect(img.width * img.height * 4).order(ByteOrder.nativeOrder())
        for (p in img.pixels) {
            buf.put((p shr 16 and 0xFF).toByte()); buf.put((p shr 8 and 0xFF).toByte())
            buf.put((p and 0xFF).toByte()); buf.put(0xFF.toByte())
        }
        buf.position(0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texBlur)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, img.width, img.height, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        configure2D(texBlur, mipmap = false)
    }

    /** Uploads the composite curve LUT (or identity for null) as a 256×1 RGBA16F texture. */
    fun uploadCurve(lut: FloatArray?) {
        if (curveUploaded && (lut === lastCurve || (lut != null && lastCurve != null && lut.contentEquals(lastCurve)))) return
        val n = CurveMath.LUT_SIZE
        val data = FloatBuffer.allocate(n * 4)
        for (i in 0 until n) {
            if (lut == null) { val v = i / (n - 1f); data.put(v).put(v).put(v) }
            else data.put(lut[i * 3]).put(lut[i * 3 + 1]).put(lut[i * 3 + 2])
            data.put(1f)
        }
        data.position(0)
        val direct = ByteBuffer.allocateDirect(n * 16).order(ByteOrder.nativeOrder()).asFloatBuffer().put(data)
        direct.position(0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texCurve)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, n, 1, 0, GLES30.GL_RGBA, GLES30.GL_FLOAT, direct)
        configure2D(texCurve, mipmap = false)
        lastCurve = lut
        curveUploaded = true
    }

    /** Uploads [lut] as an RGB16F 3D texture (a 2³ identity when null). */
    fun uploadLut(lut: Lut3D?) {
        if (lut != null && lut === lastLut) return
        val l = lut ?: IDENTITY_LUT
        if (l === lastLut) return
        val buf = ByteBuffer.allocateDirect(l.data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(l.data).position(0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, texLut)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB16F, l.size, l.size, l.size, 0,
            GLES30.GL_RGB, GLES30.GL_FLOAT, buf)
        for (p in intArrayOf(GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_TEXTURE_MAG_FILTER)) {
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, p, GLES30.GL_LINEAR)
        }
        for (p in intArrayOf(GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_TEXTURE_WRAP_R)) {
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, p, GLES30.GL_CLAMP_TO_EDGE)
        }
        lastLut = l
        lutSize = l.size.toFloat()
    }

    /**
     * Draws one pass into the current framebuffer/viewport.
     * @param outToSrc column-major output-uv → source-uv homography.
     * @param tile output-uv rect `x0, y0, x1, y1` this draw covers.
     * @param clip quad placement: `clip = (aPos * 2 - 1) * clip.xy + clip.zw`.
     * @param srcRect source-uv rect covered by the uploaded source texture (`x, y, w, h`).
     */
    fun draw(
        p: RenderParams,
        outToSrc: FloatArray,
        tile: FloatArray,
        clip: FloatArray,
        srcRect: FloatArray,
        grainScale: Pair<Float, Float>,
    ) {
        // Disabled tables are never sampled (curveOn / lutAmount are 0), so keep whatever is bound:
        // the compare crossfade draws the original between edit frames without re-uploading.
        if (p.curveLut != null) uploadCurve(p.curveLut)
        if (p.lut != null) uploadLut(p.lut)
        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texSource)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texBlur)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texCurve)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE3); GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, texLut)
        i(EditShaders.U_SOURCE, 0); i(EditShaders.U_BLUR, 1); i(EditShaders.U_CURVE, 2); i(EditShaders.U_LUT, 3)

        loc(EditShaders.U_OUT_TO_SRC).let { if (it >= 0) GLES30.glUniformMatrix3fv(it, 1, false, outToSrc, 0) }
        v4(EditShaders.U_TILE, tile); v4(EditShaders.U_CLIP, clip); v4(EditShaders.U_SRC_RECT, srcRect)
        loc(EditShaders.U_SRC_TEXEL).let {
            if (it >= 0) GLES30.glUniform2f(it, 1f / maxOf(1, sourceWidth), 1f / maxOf(1, sourceHeight))
        }

        f(EditShaders.U_EXPOSURE, p.exposure); f(EditShaders.U_CONTRAST, p.contrast)
        f(EditShaders.U_HIGHLIGHTS, p.highlights); f(EditShaders.U_SHADOWS, p.shadows)
        f(EditShaders.U_WHITES, p.whites); f(EditShaders.U_BLACKS, p.blacks)
        f(EditShaders.U_TEMPERATURE, p.temperature); f(EditShaders.U_TINT, p.tint)
        f(EditShaders.U_VIBRANCE, p.vibrance); f(EditShaders.U_SATURATION, p.saturation)
        loc(EditShaders.U_HSL).let { if (it >= 0) GLES30.glUniform3fv(it, 8, p.hsl, 0) }
        i(EditShaders.U_HSL_ON, if (p.hslOn) 1 else 0)
        i(EditShaders.U_CURVE_ON, if (p.curveLut != null) 1 else 0)
        f(EditShaders.U_LUT_SIZE, lutSize)
        f(EditShaders.U_LUT_AMOUNT, if (p.lut != null) p.lutAmount else 0f)
        f(EditShaders.U_CLARITY, p.clarity); f(EditShaders.U_DEHAZE, p.dehaze)
        f(EditShaders.U_SHARPEN, p.sharpen); f(EditShaders.U_VIGNETTE, p.vignette)
        f(EditShaders.U_GRAIN, p.grain)
        loc(EditShaders.U_GRAIN_SCALE).let { if (it >= 0) GLES30.glUniform2f(it, grainScale.first, grainScale.second) }

        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)
        checkGl("draw")
    }

    fun release() {
        GLES30.glDeleteTextures(4, intArrayOf(texSource, texBlur, texCurve, texLut), 0)
        GLES30.glDeleteBuffers(1, intArrayOf(vbo), 0)
        GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
        GLES30.glDeleteProgram(program)
    }

    private fun loc(name: String) = locations[name] ?: -1
    private fun f(name: String, v: Float) { val l = loc(name); if (l >= 0) GLES30.glUniform1f(l, v) }
    private fun i(name: String, v: Int) { val l = loc(name); if (l >= 0) GLES30.glUniform1i(l, v) }
    private fun v4(name: String, v: FloatArray) { val l = loc(name); if (l >= 0) GLES30.glUniform4fv(l, 1, v, 0) }

    private fun configure2D(tex: Int, mipmap: Boolean) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER,
            if (mipmap) GLES30.GL_LINEAR_MIPMAP_LINEAR else GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    }

    companion object {
        private const val TAG = "EditGl"
        private val IDENTITY_LUT = Lut3D.identity(2)

        fun checkGl(op: String) {
            val err = GLES30.glGetError()
            if (err != GLES30.GL_NO_ERROR) Log.w(TAG, "$op: GL error 0x${Integer.toHexString(err)}")
        }

        private fun compile(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src)
            GLES30.glCompileShader(s)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) {
                val log = GLES30.glGetShaderInfoLog(s)
                GLES30.glDeleteShader(s)
                throw IllegalStateException("Shader compile failed: $log")
            }
            return s
        }

        fun linkProgram(vs: String, fs: String): Int {
            val v = compile(GLES30.GL_VERTEX_SHADER, vs)
            val f = compile(GLES30.GL_FRAGMENT_SHADER, fs)
            val p = GLES30.glCreateProgram()
            GLES30.glAttachShader(p, v); GLES30.glAttachShader(p, f)
            GLES30.glLinkProgram(p)
            GLES30.glDeleteShader(v); GLES30.glDeleteShader(f)
            val ok = IntArray(1)
            GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
            if (ok[0] == 0) {
                val log = GLES30.glGetProgramInfoLog(p)
                GLES30.glDeleteProgram(p)
                throw IllegalStateException("Program link failed: $log")
            }
            return p
        }
    }
}
