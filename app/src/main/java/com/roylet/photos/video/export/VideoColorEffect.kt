package com.roylet.photos.video.export

import android.content.Context
import android.opengl.GLES30
import androidx.annotation.OptIn
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.roylet.photos.editor.gl.EditShaders
import com.roylet.photos.editor.gl.GlPipeline
import com.roylet.photos.editor.recipe.CurveMath
import com.roylet.photos.editor.recipe.Lut3D
import com.roylet.photos.editor.recipe.LutResolver
import com.roylet.photos.editor.recipe.Recipe
import com.roylet.photos.editor.recipe.RenderParams
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The values the photo editor's [EditShaders.COLOR_PIPELINE] uniforms get for a video frame:
 * the same mapping as `GlPipeline.draw`, except that clarity, dehaze and sharpen are forced off
 * (they need a blurred copy of the frame; v1 skips them for video) and the grain lattice follows
 * the frame's aspect. Pure, so it is unit tested against [EditShaders.COLOR_UNIFORMS].
 */
class ColorUniforms(
    val floats: Map<String, Float>,
    val ints: Map<String, Int>,
    val vec2s: Map<String, FloatArray>,
    /** `uHsl`: 8 × vec3. */
    val hsl: FloatArray,
    /** Sampler uniform → texture unit. */
    val samplers: Map<String, Int>,
    val curveLut: FloatArray?,
    val lut: Lut3D?,
) {
    /** Every uniform name this sets (for the parity test). */
    val names: Set<String> get() = floats.keys + ints.keys + vec2s.keys + samplers.keys + EditShaders.U_HSL

    companion object {
        const val UNIT_INPUT = 0
        const val UNIT_CURVE = 1
        const val UNIT_LUT = 2

        fun from(p: RenderParams, width: Int, height: Int, lutSize: Int = p.lut?.size ?: 2): ColorUniforms {
            val grain = RenderParams.grainScale(width.toFloat() / height.coerceAtLeast(1))
            return ColorUniforms(
                floats = mapOf(
                    EditShaders.U_EXPOSURE to p.exposure,
                    EditShaders.U_CONTRAST to p.contrast,
                    EditShaders.U_HIGHLIGHTS to p.highlights,
                    EditShaders.U_SHADOWS to p.shadows,
                    EditShaders.U_WHITES to p.whites,
                    EditShaders.U_BLACKS to p.blacks,
                    EditShaders.U_TEMPERATURE to p.temperature,
                    EditShaders.U_TINT to p.tint,
                    EditShaders.U_VIBRANCE to p.vibrance,
                    EditShaders.U_SATURATION to p.saturation,
                    EditShaders.U_LUT_SIZE to lutSize.toFloat(),
                    EditShaders.U_LUT_AMOUNT to if (p.lut != null) p.lutAmount else 0f,
                    // Local contrast: off for video (see class doc).
                    EditShaders.U_CLARITY to 0f,
                    EditShaders.U_DEHAZE to 0f,
                    EditShaders.U_SHARPEN to 0f,
                    EditShaders.U_VIGNETTE to p.vignette,
                    EditShaders.U_GRAIN to p.grain,
                ),
                ints = mapOf(
                    EditShaders.U_HSL_ON to if (p.hslOn) 1 else 0,
                    EditShaders.U_CURVE_ON to if (p.curveLut != null) 1 else 0,
                ),
                vec2s = mapOf(EditShaders.U_GRAIN_SCALE to floatArrayOf(grain.first, grain.second)),
                hsl = p.hsl,
                samplers = mapOf(VideoColorShaders.U_INPUT to UNIT_INPUT, EditShaders.U_CURVE to UNIT_CURVE, EditShaders.U_LUT to UNIT_LUT),
                curveLut = p.curveLut,
                lut = p.lut,
            )
        }
    }
}

/** GLSL for the video colour pass: the photo editor's [EditShaders.COLOR_PIPELINE] on a full-frame quad. */
object VideoColorShaders {
    const val A_POSITION = "aFramePosition"
    const val U_INPUT = "uInput"
    /** True passes the frame through untouched (identity recipe, or an HDR frame). */
    const val U_BYPASS = "uBypass"

    val VERTEX: String = """
#version 300 es
in vec4 $A_POSITION;
out vec2 vUv;
void main() {
    gl_Position = $A_POSITION;
    vUv = $A_POSITION.xy * 0.5 + 0.5;
}
""".trimStart()

    /** `shade(c, c, c, uv)`: passing the pixel as its own blur disables the local-contrast terms. */
    val FRAGMENT: String = """
#version 300 es
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp sampler3D;
${EditShaders.COLOR_PIPELINE}
uniform sampler2D $U_INPUT;
uniform bool $U_BYPASS;
in vec2 vUv;
out vec4 fragColor;
void main() {
    vec4 src = texture($U_INPUT, vUv);
    vec3 c = clamp(src.rgb, 0.0, 1.0);
    vec3 shaded = shade(c, c, c, vUv);
    fragColor = $U_BYPASS ? src : vec4(shaded, src.a);
}
""".trimStart()
}

/**
 * The recipe a [VideoColorEffect] renders. The preview keeps one and calls [set] as sliders move:
 * the shader reads it every frame, so there is no effect-chain rebuild per change. Thread-safe.
 */
class ColorParamsHolder(recipe: Recipe = Recipe(), private val lutResolver: LutResolver = LutResolver.Default) {
    @Volatile var recipe: Recipe = recipe
        private set
    @Volatile var params: RenderParams = RenderParams.from(recipe, lutResolver)
        private set
    /** True when the current recipe changes nothing video renders. */
    @Volatile var isIdentity: Boolean = isVideoColorIdentity(recipe)
        private set

    fun set(recipe: Recipe) {
        if (recipe == this.recipe) return
        params = RenderParams.from(recipe, lutResolver)
        isIdentity = isVideoColorIdentity(recipe)
        this.recipe = recipe
    }
}

/**
 * A Media3 [GlEffect] that runs the photo editor's colour pipeline on each frame, so filters and
 * sliders look the same on video. Frames arrive as SDR (Media3's default working space is the
 * video's own gamma-encoded BT.709, close to the sRGB the pipeline expects); HDR sources are
 * tone-mapped before this runs (see [CompositionPlanner]). On an HDR pipeline (`useHdr`, only
 * possible in the preview) the pass is skipped rather than clipping the HDR signal.
 */
@OptIn(UnstableApi::class)
class VideoColorEffect(val holder: ColorParamsHolder) : GlEffect {
    constructor(recipe: Recipe) : this(ColorParamsHolder(recipe))

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = VideoColorShaderProgram(holder, useHdr)

    // Never a no-op: the preview's holder may gain a recipe after the chain is configured.
    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = false
}

/**
 * Draws [VideoColorShaders] into Media3's output texture. Runs on Media3's GL thread (ES 3
 * context: Media3 tries 3.0 first, and API 29+ phones have it). Uses raw GLES30 rather than
 * Media3's `GlProgram`, which can't bind `vec3[8]`, `bool` or `sampler3D` uniforms.
 *
 * The curve/LUT uploads mirror `GlPipeline.uploadCurve/uploadLut` (private there); unify later.
 */
@OptIn(UnstableApi::class)
internal class VideoColorShaderProgram(
    private val holder: ColorParamsHolder,
    private val hdr: Boolean,
) : BaseGlShaderProgram(/* useHighPrecisionColorComponents = */ hdr, /* texturePoolCapacity = */ 1) {
    private var program = 0
    private var vbo = 0
    private var vao = 0
    private var texCurve = 0
    private var texLut = 0
    private val locations = HashMap<String, Int>()
    private var width = 0
    private var height = 0
    private var uploadedCurve: FloatArray? = null
    private var curveReady = false
    private var uploadedLut: Lut3D? = null
    private var lutSize = 2

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        width = inputWidth
        height = inputHeight
        return Size(inputWidth, inputHeight)
    }

    private fun init() {
        if (program != 0) return
        try {
            program = GlPipeline.linkProgram(VideoColorShaders.VERTEX, VideoColorShaders.FRAGMENT)
        } catch (e: IllegalStateException) {
            throw VideoFrameProcessingException("Colour shader failed to build", e)
        }
        val names = EditShaders.COLOR_UNIFORMS + VideoColorShaders.U_INPUT + VideoColorShaders.U_BYPASS
        for (n in names) locations[n] = GLES30.glGetUniformLocation(program, n)
        val ids = IntArray(2)
        GLES30.glGenTextures(2, ids, 0)
        texCurve = ids[0]; texLut = ids[1]
        val quad = floatArrayOf(-1f, -1f, 0f, 1f, 1f, -1f, 0f, 1f, -1f, 1f, 0f, 1f, 1f, 1f, 0f, 1f)
        val buf = ByteBuffer.allocateDirect(quad.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(quad)
        buf.position(0)
        val b = IntArray(1)
        GLES30.glGenBuffers(1, b, 0); vbo = b[0]
        GLES30.glGenVertexArrays(1, b, 0); vao = b[0]
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, quad.size * 4, buf, GLES30.GL_STATIC_DRAW)
        val a = GLES30.glGetAttribLocation(program, VideoColorShaders.A_POSITION)
        GLES30.glEnableVertexAttribArray(a)
        GLES30.glVertexAttribPointer(a, 4, GLES30.GL_FLOAT, false, 16, 0)
        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        init()
        val p = holder.params
        val skip = hdr || holder.isIdentity
        uploadCurve(p.curveLut)
        uploadLut(p.lut)
        val u = ColorUniforms.from(p, width, height, lutSize)
        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + ColorUniforms.UNIT_INPUT)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, inputTexId)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + ColorUniforms.UNIT_CURVE)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texCurve)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + ColorUniforms.UNIT_LUT)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, texLut)
        for ((n, unit) in u.samplers) loc(n)?.let { GLES30.glUniform1i(it, unit) }
        loc(VideoColorShaders.U_BYPASS)?.let { GLES30.glUniform1i(it, if (skip) 1 else 0) }
        for ((n, v) in u.floats) loc(n)?.let { GLES30.glUniform1f(it, v) }
        for ((n, v) in u.ints) loc(n)?.let { GLES30.glUniform1i(it, v) }
        for ((n, v) in u.vec2s) loc(n)?.let { GLES30.glUniform2f(it, v[0], v[1]) }
        loc(EditShaders.U_HSL)?.let { GLES30.glUniform3fv(it, 8, u.hsl, 0) }
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        val err = GLES30.glGetError()
        if (err != GLES30.GL_NO_ERROR) throw VideoFrameProcessingException("GL error 0x${Integer.toHexString(err)} in colour pass", presentationTimeUs)
    }

    override fun release() {
        super.release()
        if (program != 0) {
            GLES30.glDeleteTextures(2, intArrayOf(texCurve, texLut), 0)
            GLES30.glDeleteBuffers(1, intArrayOf(vbo), 0)
            GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
            GLES30.glDeleteProgram(program)
            program = 0
        }
    }

    private fun loc(name: String): Int? = locations[name]?.takeIf { it >= 0 }

    private fun uploadCurve(lut: FloatArray?) {
        if (curveReady && (lut === uploadedCurve || (lut != null && uploadedCurve != null && lut.contentEquals(uploadedCurve)))) return
        val n = CurveMath.LUT_SIZE
        val data = ByteBuffer.allocateDirect(n * 16).order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (i in 0 until n) {
            if (lut == null) { val v = i / (n - 1f); data.put(v).put(v).put(v) } else data.put(lut[i * 3]).put(lut[i * 3 + 1]).put(lut[i * 3 + 2])
            data.put(1f)
        }
        data.position(0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texCurve)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, n, 1, 0, GLES30.GL_RGBA, GLES30.GL_FLOAT, data)
        for (p in intArrayOf(GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_TEXTURE_MAG_FILTER)) GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, p, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        uploadedCurve = lut
        curveReady = true
    }

    private fun uploadLut(lut: Lut3D?) {
        val l = lut ?: IDENTITY_LUT
        if (l === uploadedLut) return
        val buf = ByteBuffer.allocateDirect(l.data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(l.data)
        buf.position(0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, texLut)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB16F, l.size, l.size, l.size, 0, GLES30.GL_RGB, GLES30.GL_FLOAT, buf)
        for (p in intArrayOf(GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_TEXTURE_MAG_FILTER)) GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, p, GLES30.GL_LINEAR)
        for (p in intArrayOf(GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_TEXTURE_WRAP_R)) {
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, p, GLES30.GL_CLAMP_TO_EDGE)
        }
        uploadedLut = l
        lutSize = l.size
    }

    private companion object {
        val IDENTITY_LUT = Lut3D.identity(2)
    }
}
