package com.roylet.photos.editor.gl

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.opengl.GLES30
import com.roylet.photos.editor.cpu.ArgbImage
import com.roylet.photos.editor.cpu.BlurMath
import com.roylet.photos.editor.recipe.GeometryMath
import com.roylet.photos.editor.recipe.LutResolver
import com.roylet.photos.editor.recipe.Mat3
import com.roylet.photos.editor.recipe.Recipe
import com.roylet.photos.editor.recipe.RenderParams
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Full-resolution offscreen rendering in FBO tiles. */
internal object GlExporter {
    /** Source pixels kept around each tile's footprint (bilinear + sharpen taps). */
    private const val MARGIN = 3

    fun export(
        source: ExportSource,
        recipe: Recipe,
        lutResolver: LutResolver,
        maxTileSize: Int,
        maxOutputPixels: Long?,
        blurBasis: Bitmap?,
    ): Bitmap {
        val g = recipe.geometry.normalized()
        var (outW, outH) = GeometryMath.outputSize(g, source.width, source.height)
        if (maxOutputPixels != null && outW.toLong() * outH > maxOutputPixels) {
            val s = sqrt(maxOutputPixels.toDouble() / (outW.toLong() * outH))
            outW = max(1, (outW * s).toInt()); outH = max(1, (outH * s).toInt())
        }
        val m = GeometryMath.outputToSource(g, source.width, source.height)
        val params = RenderParams.from(recipe, lutResolver)
        val grain = RenderParams.grainScale(outW.toFloat() / outH)
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val copyPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }

        EglOffscreen().use {
            val pipeline = GlPipeline()
            try {
                if (params.clarity != 0f || params.dehaze != 0f) {
                    val basis = blurBasis ?: source.decodePreview(BLUR_BASIS_EDGE)
                    pipeline.uploadBlur(BlurMath.blurredCopy(basis.toArgbImage()))
                }
                val tile = minOf(maxTileSize, pipeline.maxTextureSize, pipeline.maxViewport)
                val fbo = IntArray(1)
                val color = IntArray(1)
                GLES30.glGenFramebuffers(1, fbo, 0)
                GLES30.glGenTextures(1, color, 0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, color[0])
                GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, tile, tile)
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
                GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                    GLES30.GL_TEXTURE_2D, color[0], 0)
                check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) {
                    "Export framebuffer incomplete"
                }
                val pixels = ByteBuffer.allocateDirect(tile * tile * 4).order(ByteOrder.nativeOrder())
                val tileBitmaps = HashMap<Long, Bitmap>()
                val outToSrc = m.toColumnMajor()
                val clip = floatArrayOf(1f, 1f, 0f, 0f) // aPos.y = 0 (top) lands on framebuffer row 0
                GLES30.glDisable(GLES30.GL_BLEND)

                var ty = 0
                while (ty < outH) {
                    val th = min(tile, outH - ty)
                    var tx = 0
                    while (tx < outW) {
                        val tw = min(tile, outW - tx)
                        val tileUv = floatArrayOf(tx.toFloat() / outW, ty.toFloat() / outH,
                            (tx + tw).toFloat() / outW, (ty + th).toFloat() / outH)
                        val region = sourceRegion(m, tileUv, source.width, source.height)
                        val bmp = source.decodeRegion(region, tile).toSoftwareArgb()
                        pipeline.uploadSource(bmp)
                        bmp.recycleIfTemporary(source)
                        val srcRect = floatArrayOf(
                            region.left.toFloat() / source.width, region.top.toFloat() / source.height,
                            region.width().toFloat() / source.width, region.height().toFloat() / source.height)
                        GLES30.glViewport(0, 0, tw, th)
                        pipeline.draw(params, outToSrc, tileUv, clip, srcRect, grain)
                        pixels.position(0)
                        GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 4)
                        GLES30.glReadPixels(0, 0, tw, th, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
                        pixels.position(0)
                        val key = (tw.toLong() shl 32) or th.toLong()
                        val tb = tileBitmaps.getOrPut(key) { Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888) }
                        tb.copyPixelsFromBuffer(pixels)
                        canvas.drawBitmap(tb, tx.toFloat(), ty.toFloat(), copyPaint)
                        tx += tw
                    }
                    ty += th
                }
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                GLES30.glDeleteFramebuffers(1, fbo, 0)
                GLES30.glDeleteTextures(1, color, 0)
                tileBitmaps.values.forEach { it.recycle() }
            } finally {
                pipeline.release()
            }
        }
        return out
    }

    /** Pixel rect of the source needed for the output-uv [tile] (bounding box + margin). */
    fun sourceRegion(m: Mat3, tile: FloatArray, srcW: Int, srcH: Int): Rect {
        var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        for (x in doubleArrayOf(tile[0].toDouble(), tile[2].toDouble())) for (y in doubleArrayOf(tile[1].toDouble(), tile[3].toDouble())) {
            val p = m.map(x, y)
            minX = min(minX, p[0]); maxX = max(maxX, p[0]); minY = min(minY, p[1]); maxY = max(maxY, p[1])
        }
        val l = (floor(minX * srcW).toInt() - MARGIN).coerceIn(0, srcW - 1)
        val t = (floor(minY * srcH).toInt() - MARGIN).coerceIn(0, srcH - 1)
        val r = (ceil(maxX * srcW).toInt() + MARGIN).coerceIn(l + 1, srcW)
        val b = (ceil(maxY * srcH).toInt() + MARGIN).coerceIn(t + 1, srcH)
        return Rect(l, t, r, b)
    }

    private fun Bitmap.recycleIfTemporary(source: ExportSource) {
        // BitmapExportSource may hand back its own bitmap for a full-frame region.
        if (source is BitmapExportSource) return
        recycle()
    }

    const val BLUR_BASIS_EDGE = 1024
}

internal fun Bitmap.toArgbImage(): ArgbImage {
    val px = IntArray(width * height)
    getPixels(px, 0, width, 0, 0, width, height)
    return ArgbImage(width, height, px)
}
