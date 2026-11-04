package com.roylet.photos.video.export

import com.roylet.photos.editor.recipe.Recipe
import com.roylet.photos.video.model.OutputCodec
import com.roylet.photos.video.model.OutputQuality
import com.roylet.photos.video.model.VideoEdit
import com.roylet.photos.video.model.VideoGeometry
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** What the exporter needs to know about the original file (see [SourceProbe]). */
data class SourceInfo(
    /** Upright size, i.e. after the container's rotation, as players show it. */
    val width: Int,
    val height: Int,
    val durationMs: Long,
    /** e.g. `video/hevc`; null when unknown. */
    val videoMime: String? = null,
    /** `MediaFormat`/Media3 `C.COLOR_TRANSFER_*`; 6 = PQ (ST 2084), 7 = HLG. */
    val colorTransfer: Int? = null,
    val hasAudio: Boolean = true,
    val frameRate: Float = 30f,
    /** Average bits per second of the video track (or the whole file); null when unknown. */
    val bitrate: Int? = null,
    /** From the container (`©day`/`mvhd`), epoch ms; null when absent. */
    val creationTimeMs: Long? = null,
    val latitude: Float? = null,
    val longitude: Float? = null,
) {
    val isHdr: Boolean get() = colorTransfer == TRANSFER_PQ || colorTransfer == TRANSFER_HLG
    val isHevc: Boolean get() = videoMime == MIME_HEVC

    companion object {
        const val TRANSFER_PQ = 6
        const val TRANSFER_HLG = 7
    }
}

/** Encoder support on this phone ([SourceProbe.encoderCaps]). H.264 encoding is assumed everywhere. */
data class EncoderCaps(
    val hevc: Boolean = false,
    /** An HEVC encoder can keep the source's HDR (10-bit, same transfer). */
    val hevcHdr: Boolean = false,
)

/** How HDR sources are handled. */
enum class HdrHandling {
    /** SDR source; nothing to do. */
    NONE,
    /** Kept as HDR (HEVC 10-bit). Only when there's no colour edit and the encoder can. */
    KEEP,
    /** Tone-mapped to SDR with OpenGL (works on every API 29+ device). */
    TONE_MAP,
}

/** One video effect, in application order. A description; [Media3Compositions] builds the real thing. */
sealed interface VideoEffectSpec {
    /** Media3 rotations are counter-clockwise. */
    data class Rotate(val degreesCcw: Float) : VideoEffectSpec
    data class Flip(val horizontal: Boolean, val vertical: Boolean) : VideoEffectSpec
    /** NDC (`-1..1`, y up), in Media3 `Crop(left, right, bottom, top)` order. */
    data class Crop(val left: Float, val right: Float, val bottom: Float, val top: Float) : VideoEffectSpec
    /** Exact output size (both even); [downscale] picks mipmapped filtering. */
    data class Resize(val width: Int, val height: Int, val downscale: Boolean) : VideoEffectSpec
    /** The photo editor's colour pipeline (see [VideoColorEffect]). */
    data class Color(val recipe: Recipe) : VideoEffectSpec
}

/** A kept source range for one `EditedMediaItem` (`ClippingConfiguration`). */
data class ClipSpec(val startMs: Long, val endMs: Long)

/**
 * Everything the export does, as plain data: built by [CompositionPlanner.plan] (pure and unit
 * tested) and turned into a Media3 `Composition` + `Transformer` by [Media3Compositions].
 */
data class ExportPlan(
    val clips: List<ClipSpec>,
    val videoEffects: List<VideoEffectSpec>,
    val speed: Float,
    val removeAudio: Boolean,
    /** Linear audio gain, or null for unchanged. */
    val audioGain: Float?,
    val videoMime: String,
    val bitrate: Int,
    val hdr: HdrHandling,
    val outputWidth: Int,
    val outputHeight: Int,
    val outputDurationMs: Long,
    /** Written into the MP4 when the source had none (the original asset's `fileCreatedAt`). */
    val creationTimeMs: Long?,
    val latitude: Float? = null,
    val longitude: Float? = null,
    /**
     * A plain trim (one clip, no effects, same speed/audio): lets Media3 copy the middle of the
     * stream and only re-encode up to the first keyframe. Much faster for long 4K footage.
     */
    val trimOptimization: Boolean = false,
) {
    /**
     * The safest variant after an encoder/HDR failure: H.264, SDR (tone-mapped), no transmux.
     * Null when this plan already is that.
     */
    fun fallback(): ExportPlan? {
        val safe = copy(
            videoMime = MIME_H264,
            hdr = if (hdr == HdrHandling.KEEP) HdrHandling.TONE_MAP else hdr,
            bitrate = if (videoMime == MIME_HEVC) (bitrate / OutputQuality.HEVC_FACTOR).roundToInt() else bitrate,
            trimOptimization = false,
        )
        return if (safe == this) null else safe
    }
}

const val MIME_H264 = "video/avc"
const val MIME_HEVC = "video/hevc"

/** Pure translation of a [VideoEdit] into an [ExportPlan] for one source on one phone. */
object CompositionPlanner {
    const val MIN_BITRATE = 1_000_000
    const val MAX_BITRATE = 150_000_000

    fun plan(edit: VideoEdit, source: SourceInfo, caps: EncoderCaps, fallbackCreationTimeMs: Long? = null): ExportPlan {
        val e = edit.normalized()
        val clips = e.segments.map { ClipSpec(it.startMs, it.endMs.coerceAtMost(max(source.durationMs, it.startMs + 1))) }

        // Geometry: rotate, flip, crop (photo order), then size.
        val g = e.geometry
        val quarter = g.rotate90.mod(4)
        val effects = ArrayList(geometryEffects(g))
        val (gw, gh) = g.outputSize(source.width, source.height)
        val target = e.output.resolution.shortSide
        val scale = if (target != null && min(gw, gh) > target) target.toFloat() / min(gw, gh) else 1f
        val outW = even(gw * scale)
        val outH = even(gh * scale)
        val (rw, rh) = if (quarter % 2 == 1) source.height to source.width else source.width to source.height
        if (outW != rw || outH != rh) effects += VideoEffectSpec.Resize(outW, outH, downscale = scale < 1f)
        if (!isVideoColorIdentity(e.color)) effects += VideoEffectSpec.Color(e.color)

        // Codec and HDR.
        val hevc = when (e.output.codec) {
            OutputCodec.AUTO -> source.isHevc && caps.hevc
            OutputCodec.H264 -> false
            OutputCodec.HEVC -> caps.hevc
        }
        val colorEdit = effects.any { it is VideoEffectSpec.Color }
        val hdr = when {
            !source.isHdr -> HdrHandling.NONE
            hevc && caps.hevcHdr && !colorEdit -> HdrHandling.KEEP
            else -> HdrHandling.TONE_MAP
        }
        val mime = if (hevc) MIME_HEVC else MIME_H264

        val audioGain = e.audio.gain.takeIf { !e.audio.mute && it != 1f }
        val removeAudio = e.audio.mute || !source.hasAudio
        val trimOnly = clips.size == 1 && effects.isEmpty() && e.speed == 1f && audioGain == null &&
            !(e.audio.mute && source.hasAudio) && hdr != HdrHandling.TONE_MAP &&
            (source.videoMime == null || source.videoMime == mime)

        return ExportPlan(
            clips = clips,
            videoEffects = effects,
            speed = e.speed,
            removeAudio = removeAudio,
            audioGain = if (removeAudio) null else audioGain,
            videoMime = mime,
            bitrate = bitrate(outW, outH, source, e.output.quality, hevc),
            hdr = hdr,
            outputWidth = outW,
            outputHeight = outH,
            outputDurationMs = clips.sumOf { it.endMs - it.startMs }.let { (it / e.speed.toDouble()).roundToLong() },
            creationTimeMs = source.creationTimeMs ?: fallbackCreationTimeMs,
            latitude = source.latitude,
            longitude = source.longitude,
            trimOptimization = trimOnly,
        )
    }

    /** Rotate → flip → crop, as Media3 effect specs (no resize). Also used by the live preview. */
    fun geometryEffects(geometry: VideoGeometry): List<VideoEffectSpec> {
        val g = geometry.normalized()
        val out = ArrayList<VideoEffectSpec>(3)
        val quarter = g.rotate90.mod(4)
        if (quarter != 0) out += VideoEffectSpec.Rotate(((4 - quarter) * 90).toFloat())
        if (g.flipH || g.flipV) out += VideoEffectSpec.Flip(g.flipH, g.flipV)
        val c = g.crop
        if (!c.isFull) out += VideoEffectSpec.Crop(2 * c.left - 1, 2 * c.right - 1, 1 - 2 * c.bottom, 1 - 2 * c.top)
        return out
    }

    /**
     * Pixels × frames × bits-per-pixel for the [quality], HEVC at [OutputQuality.HEVC_FACTOR], never
     * much above what the source spent on the same pixels.
     */
    fun bitrate(width: Int, height: Int, source: SourceInfo, quality: OutputQuality, hevc: Boolean): Int {
        val fps = source.frameRate.takeIf { it.isFinite() && it > 0 }?.coerceIn(1f, 60f) ?: 30f
        var bps = width.toDouble() * height * fps * quality.bitsPerPixel * (if (hevc) OutputQuality.HEVC_FACTOR else 1f)
        val src = source.bitrate
        if (src != null && src > 0 && source.width > 0 && source.height > 0) {
            val pixelRatio = width.toDouble() * height / (source.width.toDouble() * source.height)
            val codecRatio = when {
                source.isHevc && !hevc -> 1 / OutputQuality.HEVC_FACTOR.toDouble()
                !source.isHevc && hevc -> OutputQuality.HEVC_FACTOR.toDouble()
                else -> 1.0
            }
            bps = min(bps, src * pixelRatio * codecRatio * 1.2)
        }
        return bps.roundToLong().coerceIn(MIN_BITRATE.toLong(), MAX_BITRATE.toLong()).toInt()
    }

    private fun even(v: Float): Int = max(2, (v / 2).roundToInt() * 2)
}

/**
 * True when the colour recipe changes nothing *that video renders*: clarity, dehaze and sharpen
 * need a blurred copy of every frame and are skipped for video in v1.
 */
fun isVideoColorIdentity(recipe: Recipe): Boolean {
    val e = recipe.effects
    return recipe.light.isIdentity && recipe.color.isIdentity && recipe.curves.isIdentity &&
        e.vignette == 0f && e.grain == 0f && (recipe.filter == null || recipe.filter.isIdentity)
}
