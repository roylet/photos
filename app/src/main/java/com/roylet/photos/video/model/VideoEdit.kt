package com.roylet.photos.video.model

import com.roylet.photos.editor.recipe.CropRect
import com.roylet.photos.editor.recipe.Geometry
import com.roylet.photos.editor.recipe.Recipe
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.roundToLong

/**
 * A video edit as pure data (no Android imports, so it can move to a KMP module with the photo
 * [Recipe]). The exporter ([com.roylet.photos.video.export.CompositionPlanner]) and the live
 * preview ([com.roylet.photos.video.preview.VideoPreviewController]) both read it.
 *
 * Times are milliseconds. *Source* time is a position in the original file; *output* time is a
 * position in the edited result, i.e. the kept [segments] played back to back at [speed].
 *
 * Invariants kept by [normalized] (and so by every edit operation): segments are sorted by source
 * time, don't overlap, lie inside `0..sourceDurationMs`, are at least [MIN_SEGMENT_MS] long, and
 * there is always at least one.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class VideoEdit(
    @EncodeDefault val version: Int = CURRENT_VERSION,
    /** Length of the source in ms; segments are clamped to it. */
    val sourceDurationMs: Long,
    /** The kept parts of the source, in playback order. */
    val segments: List<TimeRange> = listOf(TimeRange(0, sourceDurationMs)),
    val geometry: VideoGeometry = VideoGeometry(),
    /** Playback speed, [SPEED_RANGE]. Audio keeps its pitch (Sonic time-stretch). */
    val speed: Float = 1f,
    val audio: AudioSettings = AudioSettings(),
    /**
     * The photo editor's colour recipe (light, colour, HSL, curves, effects, filter), so sliders
     * and filters look identical. Its geometry is always identity: use [geometry]. Clarity,
     * dehaze and sharpen are not rendered for video in v1 (they need a blurred copy per frame).
     */
    val color: Recipe = Recipe(),
    val output: OutputSettings = OutputSettings(),
) {
    /** True when exporting would reproduce the source (nothing worth saving). */
    val isIdentity: Boolean
        get() = isFullLength && geometry.isIdentity && speed == 1f && audio.isIdentity &&
            color.isColorIdentity && output.resolution == OutputResolution.ORIGINAL

    /** True when the segments cover the whole source in one piece. */
    val isFullLength: Boolean
        get() = segments.size == 1 && segments[0].startMs <= 0 && segments[0].endMs >= sourceDurationMs

    /** Total kept source time. */
    val keptSourceMs: Long get() = segments.sumOf { it.durationMs }

    /** Length of the edited video. */
    fun outputDurationMs(): Long = (keptSourceMs / speed.toDouble()).roundToLong()

    // ---- Time mapping -------------------------------------------------------------------------

    /**
     * Where output time [outputMs] comes from. Clamped into the output; a time exactly on a cut
     * maps to the start of the later segment (except at the very end).
     */
    fun outputToSource(outputMs: Long): SourcePosition {
        val keptMs = (outputMs.coerceAtLeast(0) * speed.toDouble()).roundToLong()
        var before = 0L
        segments.forEachIndexed { i, s ->
            val offset = keptMs - before
            if (offset < s.durationMs || i == segments.lastIndex) {
                val within = offset.coerceIn(0, s.durationMs)
                return SourcePosition(i, s.startMs + within, within)
            }
            before += s.durationMs
        }
        error("no segments")
    }

    /** Output time of source time [sourceMs], or null when that moment was cut out. */
    fun sourceToOutput(sourceMs: Long): Long? {
        var before = 0L
        for (s in segments) {
            if (sourceMs in s.startMs..s.endMs) return ((before + sourceMs - s.startMs) / speed.toDouble()).roundToLong()
            before += s.durationMs
        }
        return null
    }

    /** Output time at which segment [index] starts. */
    fun segmentOutputStartMs(index: Int): Long =
        (segments.take(index.coerceIn(0, segments.size)).sumOf { it.durationMs } / speed.toDouble()).roundToLong()

    // ---- Segment editing ----------------------------------------------------------------------

    /**
     * Splits the segment under output time [outputMs] in two. No-op when the cut would leave a
     * piece shorter than [MIN_SEGMENT_MS].
     */
    fun splitAt(outputMs: Long): VideoEdit {
        val pos = outputToSource(outputMs)
        val s = segments[pos.segmentIndex]
        if (pos.sourceMs - s.startMs < MIN_SEGMENT_MS || s.endMs - pos.sourceMs < MIN_SEGMENT_MS) return this
        val parts = listOf(TimeRange(s.startMs, pos.sourceMs), TimeRange(pos.sourceMs, s.endMs))
        return copy(segments = segments.take(pos.segmentIndex) + parts + segments.drop(pos.segmentIndex + 1))
    }

    /** Removes segment [index]; the last remaining segment can't be deleted. */
    fun deleteSegment(index: Int): VideoEdit {
        if (segments.size <= 1 || index !in segments.indices) return this
        return copy(segments = segments.filterIndexed { i, _ -> i != index })
    }

    /**
     * Moves segment [index]'s edges to [startMs]/[endMs] (source time), clamped so it stays
     * inside the source, doesn't overlap its neighbours and is at least [MIN_SEGMENT_MS] long.
     * When the requested range is too short, the edge being dragged gives way.
     */
    fun trimSegment(index: Int, startMs: Long, endMs: Long): VideoEdit {
        if (index !in segments.indices) return this
        val lo = segments.getOrNull(index - 1)?.endMs ?: 0L
        val hi = segments.getOrNull(index + 1)?.startMs ?: sourceDurationMs
        val cur = segments[index]
        var s = startMs.coerceIn(lo, hi)
        var e = endMs.coerceIn(lo, hi)
        if (e - s < MIN_SEGMENT_MS) {
            if (s != cur.startMs) s = (e - MIN_SEGMENT_MS).coerceAtLeast(lo) else e = (s + MIN_SEGMENT_MS).coerceAtMost(hi)
            if (e - s < MIN_SEGMENT_MS) return this
        }
        return copy(segments = segments.mapIndexed { i, r -> if (i == index) TimeRange(s, e) else r })
    }

    /** Moves the start of the first segment (the overall in-point). */
    fun trimStart(sourceMs: Long): VideoEdit = trimSegment(0, sourceMs, segments.first().endMs)

    /** Moves the end of the last segment (the overall out-point). */
    fun trimEnd(sourceMs: Long): VideoEdit = trimSegment(segments.lastIndex, segments.last().startMs, sourceMs)

    /** Back to the whole source in one segment. */
    fun resetSegments(): VideoEdit = copy(segments = listOf(TimeRange(0, sourceDurationMs)))

    fun withSpeed(value: Float): VideoEdit = copy(speed = value.coerceIn(SPEED_RANGE))

    /** Clamps every value and repairs the segment list (see the class invariants). */
    fun normalized(): VideoEdit {
        val dur = sourceDurationMs.coerceAtLeast(MIN_SEGMENT_MS)
        val fixed = ArrayList<TimeRange>()
        for (r in segments.map { TimeRange(minOf(it.startMs, it.endMs), maxOf(it.startMs, it.endMs)) }.sortedBy { it.startMs }) {
            val start = maxOf(r.startMs.coerceIn(0, dur), fixed.lastOrNull()?.endMs ?: 0L)
            val end = r.endMs.coerceIn(0, dur)
            if (end - start >= MIN_SEGMENT_MS) fixed += TimeRange(start, end)
        }
        if (fixed.isEmpty()) fixed += TimeRange(0, dur)
        return copy(
            version = CURRENT_VERSION,
            sourceDurationMs = dur,
            segments = fixed,
            geometry = geometry.normalized(),
            speed = if (speed.isNaN()) 1f else speed.coerceIn(SPEED_RANGE),
            audio = audio.normalized(),
            color = color.normalized().copy(geometry = Geometry()),
        )
    }

    fun toJson(): String = VideoEditJson.encodeToString(serializer(), this)

    companion object {
        const val CURRENT_VERSION = 1
        const val MIN_SEGMENT_MS = 100L
        val SPEED_RANGE = 0.25f..4f

        /** Speeds the UI offers as chips. */
        val SPEED_STEPS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.5f, 2f, 3f, 4f)

        /** The untouched edit of a source of [durationMs]. */
        fun initial(durationMs: Long): VideoEdit = VideoEdit(sourceDurationMs = durationMs).normalized()

        /** Decodes an edit (unknown keys ignored, newer fields defaulted) and [normalized]s it. */
        fun fromJson(json: String): VideoEdit = VideoEditJson.decodeFromString(serializer(), json).normalized()
    }
}

/** Json for video edits: compact, forward compatible (same settings as the photo recipe). */
val VideoEditJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
    coerceInputValues = true
}

/** A kept range of source time, `[startMs, endMs)`. */
@Serializable
data class TimeRange(val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = endMs - startMs
}

/** Result of [VideoEdit.outputToSource]. [offsetMs] is the source time into the segment. */
data class SourcePosition(val segmentIndex: Int, val sourceMs: Long, val offsetMs: Long)

/**
 * Crop/rotate/flip, with the photo editor's semantics: [rotate90] quarter turns clockwise, then
 * [flipH]/[flipV], then [crop] (normalised, in that rotated/flipped frame). The frame is the
 * upright picture as players show it (container rotation already applied). No straighten or
 * perspective for video.
 */
@Serializable
data class VideoGeometry(
    val crop: CropRect = CropRect(),
    val rotate90: Int = 0,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
) {
    val isIdentity: Boolean get() = crop.isFull && rotate90.mod(4) == 0 && !flipH && !flipV

    fun normalized() = copy(crop = crop.normalized(), rotate90 = rotate90.mod(4))

    // The photo Geometry's transforms already move the crop with the picture; reuse them.
    private fun asPhoto() = Geometry(crop = crop, rotate90 = rotate90, flipH = flipH, flipV = flipV)
    private fun Geometry.toVideo() = VideoGeometry(crop, rotate90, flipH, flipV)

    fun rotatedCw(): VideoGeometry = asPhoto().rotatedCw().toVideo()
    fun rotatedCcw(): VideoGeometry = asPhoto().rotatedCcw().toVideo()
    fun flippedHorizontally(): VideoGeometry = asPhoto().flippedHorizontally().toVideo()
    fun flippedVertically(): VideoGeometry = asPhoto().flippedVertically().toVideo()

    /** Size of the output frame (before scaling) for a source frame of [width]×[height]. */
    fun outputSize(width: Int, height: Int): Pair<Int, Int> {
        val swap = rotate90.mod(2) == 1
        val w = if (swap) height else width
        val h = if (swap) width else height
        val c = crop.normalized()
        return Pair((w * c.width).roundToLong().toInt().coerceAtLeast(2), (h * c.height).roundToLong().toInt().coerceAtLeast(2))
    }
}

/** [volume] is a percentage, `0..200` (100 = unchanged). */
@Serializable
data class AudioSettings(val mute: Boolean = false, val volume: Float = 100f) {
    val isIdentity: Boolean get() = !mute && volume == 100f
    /** Linear gain the audio is multiplied by (0 when muted). */
    val gain: Float get() = if (mute) 0f else volume / 100f
    fun normalized() = copy(volume = if (volume.isNaN()) 100f else volume.coerceIn(VOLUME_RANGE))

    companion object {
        val VOLUME_RANGE = 0f..200f
    }
}

/** Output size by short side; never upscales. */
enum class OutputResolution(val shortSide: Int?, val label: String) {
    ORIGINAL(null, "Original"),
    P1080(1080, "1080p"),
    P720(720, "720p"),
}

/**
 * Bitrate preset as bits per pixel per frame for H.264 (HEVC gets [HEVC_FACTOR] of it). For
 * reference, phones record 1080p30 at ~0.27 and 4K30 at ~0.2.
 */
enum class OutputQuality(val bitsPerPixel: Float, val label: String) {
    HIGH(0.2f, "High"),
    STANDARD(0.12f, "Standard"),
    SMALL(0.06f, "Small"),
    ;

    companion object {
        const val HEVC_FACTOR = 0.6f
    }
}

/**
 * Video codec choice. [AUTO] (the default) is H.264, except HEVC when the source is HEVC and this
 * phone has an HEVC encoder (keeps files small and HDR possible). [H264] is the most compatible.
 * [HEVC] uses HEVC whenever an encoder exists.
 */
enum class OutputCodec(val label: String) {
    AUTO("Auto"),
    H264("H.264"),
    HEVC("HEVC"),
}

@Serializable
data class OutputSettings(
    val resolution: OutputResolution = OutputResolution.ORIGINAL,
    val quality: OutputQuality = OutputQuality.HIGH,
    /** Preferred codec; the planner falls back to H.264 when HEVC isn't possible. */
    val codec: OutputCodec = OutputCodec.AUTO,
)
