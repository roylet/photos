package com.roylet.photos.share

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** A video target: fit inside [longEdge] × [shortEdge] (either orientation), H.264 at [videoBitrate]. */
data class VideoPreset(val longEdge: Int, val shortEdge: Int, val videoBitrate: Int, val audioBitrate: Int)

/** A photo target: JPEG, long edge at most [maxEdge] px, at [jpegQuality]. */
data class PhotoPreset(val maxEdge: Int, val jpegQuality: Int)

/** "Send file" quality: the original, or a re-encoded copy that's easier to send. */
enum class ShareQuality(val title: String, val subtitle: String, val video: VideoPreset?, val photo: PhotoPreset?) {
    ORIGINAL("Original", "Full quality, as stored", null, null),
    SMALLER("Smaller", "Video ≤1080p · photos ≤2560 px", VideoPreset(1920, 1080, 7_000_000, 128_000), PhotoPreset(2560, 85)),
    SMALLEST("Smallest", "Video ≤720p · photos ≤1600 px", VideoPreset(1280, 720, 2_800_000, 96_000), PhotoPreset(1600, 80));

    /** What a failed video encode retries with (a smaller, easier size); null when there's nothing smaller. */
    val fallback: ShareQuality? get() = if (this == SMALLER) SMALLEST else null

    companion object {
        fun of(name: String?): ShareQuality = entries.firstOrNull { it.name == name } ?: ORIGINAL
    }
}

/** What the share sheet knows about one item before sending it (from its details). */
data class ShareItemInfo(
    val id: String,
    val fileName: String,
    val mimeType: String?,
    /** The original's size in bytes; null when unknown. */
    val sizeBytes: Long?,
    /** As stored (orientation doesn't matter for the long/short edges). */
    val width: Int?,
    val height: Int?,
    val durationMs: Long?,
) {
    val isVideo: Boolean get() = mimeType?.startsWith("video/") == true || (mimeType == null && (durationMs ?: 0) > 0)

    /** Photos that decode to a still and lose nothing as JPEG; GIFs (animation), PNGs (transparency) and raw files go as they are. */
    val isCompressiblePhoto: Boolean get() = !isVideo && mimeType?.lowercase() in COMPRESSIBLE_PHOTOS

    companion object {
        val COMPRESSIBLE_PHOTOS = setOf("image/jpeg", "image/jpg", "image/heic", "image/heif", "image/webp")

        /** A MIME type from a file name, for items whose source doesn't say. */
        fun mimeFromName(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "heic", "heif" -> "image/heif"
            "webp" -> "image/webp"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "dng" -> "image/x-adobe-dng"
            "mp4", "m4v" -> "video/mp4"
            "mov" -> "video/quicktime"
            "3gp" -> "video/3gpp"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            else -> null
        }
    }
}

/** The encoder settings for one video: the upright output size, and bitrates. */
data class VideoOutputSettings(
    val width: Int,
    val height: Int,
    val videoBitrate: Int,
    val audioBitrate: Int,
    /** The output is smaller than the source (needs a Presentation/resize effect). */
    val resized: Boolean,
)

/** The photo output size (as stored) and JPEG quality. */
data class PhotoOutputSettings(val width: Int, val height: Int, val jpegQuality: Int)

/** One item's fate for a [ShareQuality]: re-encoded (with an estimate) or sent as its original. */
data class ItemPlan(val compress: Boolean, val originalBytes: Long?, val estimatedBytes: Long?) {
    /** What this item adds to the share: the estimate when compressed, else the original. */
    val sentBytes: Long? get() = if (compress) estimatedBytes else originalBytes
}

/** A quality option for a whole selection: whether it helps at all, and the total sizes. */
data class OptionSummary(
    val quality: ShareQuality,
    /** False when no item would get smaller: the option is greyed out. */
    val enabled: Boolean,
    /** How many items would be re-encoded. */
    val compressCount: Int,
    val totalBytes: Long,
    /** Some sizes are unknown, so [totalBytes] is a lower bound. */
    val partial: Boolean,
)

/**
 * The share sheet's size maths and the encoder settings per preset. Pure, so unit tested: never
 * upscale, portrait/rotation aware, and an option only counts when it really shrinks a file.
 */
object ShareEstimator {
    /** An option must save at least this share of a file to be worth re-encoding (and the quality loss). */
    const val MIN_SAVING = 0.10
    /** MP4 container overhead on top of the streams. */
    const val CONTAINER_OVERHEAD = 1.02
    /** Lower bound on scaling a preset's bitrate down for small frames. */
    const val MIN_BITRATE_SCALE = 0.35

    /** Typical JPEG bytes per pixel at a quality (camera-like content). */
    fun jpegBytesPerPixel(quality: Int): Double = when {
        quality >= 90 -> 0.42
        quality >= 85 -> 0.30
        quality >= 80 -> 0.22
        else -> 0.16
    }

    /**
     * Fits [w]×[h] inside [longEdge]×[shortEdge] in whichever orientation the frame has, without
     * upscaling. Both results even (H.264 needs it), at least 2.
     */
    fun fit(w: Int, h: Int, longEdge: Int, shortEdge: Int): Pair<Int, Int> {
        if (w <= 0 || h <= 0) return w to h
        val scale = min(1.0, min(longEdge.toDouble() / max(w, h), shortEdge.toDouble() / min(w, h)))
        return even(w * scale) to even(h * scale)
    }

    /**
     * The video encoder settings for a source of [width]×[height] pixels as stored, shown rotated by
     * [rotationDegrees] (container rotation): the output is the upright frame fitted to [preset].
     * The bitrate scales down with the pixel count for small frames (a 480p clip doesn't need 7 Mbps).
     */
    fun videoSettings(width: Int, height: Int, rotationDegrees: Int, preset: VideoPreset): VideoOutputSettings {
        val quarter = rotationDegrees.mod(360) / 90 % 2 == 1
        val (uw, uh) = if (quarter) height to width else width to height
        val (ow, oh) = fit(uw, uh, preset.longEdge, preset.shortEdge)
        val box = preset.longEdge.toDouble() * preset.shortEdge
        val scale = if (ow > 0 && oh > 0) (ow.toDouble() * oh / box).coerceIn(MIN_BITRATE_SCALE, 1.0) else 1.0
        return VideoOutputSettings(
            width = ow,
            height = oh,
            videoBitrate = (preset.videoBitrate * scale).roundToLong().toInt(),
            audioBitrate = preset.audioBitrate,
            resized = ow != uw || oh != uh,
        )
    }

    /** The photo output size (as stored) for [preset]: long edge at most [PhotoPreset.maxEdge], never upscaled. */
    fun photoSettings(width: Int, height: Int, preset: PhotoPreset): PhotoOutputSettings {
        if (width <= 0 || height <= 0) return PhotoOutputSettings(width, height, preset.jpegQuality)
        val scale = min(1.0, preset.maxEdge.toDouble() / max(width, height))
        val w = (width * scale).roundToLong().toInt().coerceAtLeast(1)
        val h = (height * scale).roundToLong().toInt().coerceAtLeast(1)
        return PhotoOutputSettings(w, h, preset.jpegQuality)
    }

    /** Estimated output bytes of [item] at [quality]; null when it can't be known (no duration or size). */
    fun estimate(item: ShareItemInfo, quality: ShareQuality): Long? {
        if (quality == ShareQuality.ORIGINAL) return item.sizeBytes
        return if (item.isVideo) {
            val preset = quality.video ?: return null
            val ms = item.durationMs?.takeIf { it > 0 } ?: return null
            val w = item.width ?: 0
            val h = item.height ?: 0
            val v = if (w > 0 && h > 0) videoSettings(w, h, 0, preset).videoBitrate else preset.videoBitrate
            (ms / 1000.0 * (v + preset.audioBitrate) / 8 * CONTAINER_OVERHEAD).roundToLong()
        } else {
            val preset = quality.photo ?: return null
            if (!item.isCompressiblePhoto) return null
            val w = item.width?.takeIf { it > 0 } ?: return null
            val h = item.height?.takeIf { it > 0 } ?: return null
            val out = photoSettings(w, h, preset)
            (out.width.toDouble() * out.height * jpegBytesPerPixel(preset.jpegQuality)).roundToLong()
        }
    }

    /**
     * Whether [item] gets re-encoded at [quality]: only when it's a kind we can compress and the
     * estimate is at least [MIN_SAVING] smaller than the original. With an unknown original size
     * (or estimate) a compressible item is still compressed: it's the best guess.
     */
    fun plan(item: ShareItemInfo, quality: ShareQuality): ItemPlan {
        if (quality == ShareQuality.ORIGINAL) return ItemPlan(false, item.sizeBytes, item.sizeBytes)
        val kindOk = item.isVideo || item.isCompressiblePhoto
        if (!kindOk) return ItemPlan(false, item.sizeBytes, item.sizeBytes)
        val est = estimate(item, quality)
        val original = item.sizeBytes
        val smaller = if (est != null && original != null) est < original * (1 - MIN_SAVING) else true
        return ItemPlan(smaller, original, if (smaller) est else original)
    }

    /** The option for a whole selection: enabled when at least one item would shrink; totals per item fate. */
    fun summary(items: List<ShareItemInfo>, quality: ShareQuality): OptionSummary {
        val plans = items.map { plan(it, quality) }
        val compressing = plans.count { it.compress }
        return OptionSummary(
            quality = quality,
            enabled = quality == ShareQuality.ORIGINAL || compressing > 0,
            compressCount = compressing,
            totalBytes = plans.sumOf { it.sentBytes ?: 0L },
            partial = plans.any { it.sentBytes == null },
        )
    }

    private fun even(v: Double): Int = max(2, (v.toLong() / 2 * 2).toInt())
}
