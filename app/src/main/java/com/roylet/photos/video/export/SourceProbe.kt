package com.roylet.photos.video.export

import android.media.MediaExtractor
import android.os.Build
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.EncoderUtil
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Reads a local video's [SourceInfo], and this phone's [EncoderCaps]. */
object SourceProbe {

    /** Throws [VideoExportException] when the file has no readable video track. */
    fun probe(file: File): SourceInfo {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.path)
            fun meta(key: Int): String? = runCatching { retriever.extractMetadata(key) }.getOrNull()
            val w = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val h = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            if (w == null || h == null || w <= 0 || h <= 0) throw VideoExportException("This file has no video track this phone can read.")
            val rotation = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val upright = if (rotation.mod(180) == 90) h to w else w to h
            var mime: String? = null
            var transfer: Int? = if (Build.VERSION.SDK_INT >= 30) meta(MediaMetadataRetriever.METADATA_KEY_COLOR_TRANSFER)?.toIntOrNull() else null
            val durationMs = meta(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val frames = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toLongOrNull()
            var fps: Float? = if (frames != null && durationMs > 0) frames * 1000f / durationMs else null
            var bitrate: Int? = meta(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()
            var hasAudio = meta(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            runCatching {
                val ex = MediaExtractor()
                try {
                    ex.setDataSource(file.path)
                    for (i in 0 until ex.trackCount) {
                        val f = ex.getTrackFormat(i)
                        val m = f.getString(MediaFormat.KEY_MIME) ?: continue
                        if (m.startsWith("audio/")) hasAudio = true
                        if (m.startsWith("video/") && mime == null) {
                            mime = m
                            if (f.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) transfer = f.getInteger(MediaFormat.KEY_COLOR_TRANSFER)
                            if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) fps = runCatching { f.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }
                                .getOrElse { f.getFloat(MediaFormat.KEY_FRAME_RATE) }
                            if (f.containsKey(MediaFormat.KEY_BIT_RATE)) bitrate = f.getInteger(MediaFormat.KEY_BIT_RATE)
                        }
                    }
                } finally {
                    ex.release()
                }
            }
            val (lat, lon) = parseIso6709(meta(MediaMetadataRetriever.METADATA_KEY_LOCATION)) ?: (null to null)
            return SourceInfo(
                width = upright.first,
                height = upright.second,
                durationMs = durationMs,
                videoMime = mime,
                colorTransfer = transfer,
                hasAudio = hasAudio,
                frameRate = fps ?: 30f,
                bitrate = bitrate,
                creationTimeMs = parseMp4Date(meta(MediaMetadataRetriever.METADATA_KEY_DATE)),
                latitude = lat,
                longitude = lon,
            )
        } catch (e: VideoExportException) {
            throw e
        } catch (e: Exception) {
            throw VideoExportException("This video can't be read on this phone (${e.message ?: e.javaClass.simpleName}).", cause = e)
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** Which encoders exist here. HDR support is checked for [source]'s transfer. */
    @OptIn(UnstableApi::class)
    fun encoderCaps(source: SourceInfo? = null): EncoderCaps {
        val hevc = runCatching { EncoderUtil.getSupportedEncoders(MIME_HEVC).isNotEmpty() }.getOrDefault(false)
        val hdr = hevc && source?.isHdr == true && runCatching {
            val color = ColorInfo.Builder()
                .setColorSpace(C.COLOR_SPACE_BT2020)
                .setColorRange(C.COLOR_RANGE_LIMITED)
                .setColorTransfer(source.colorTransfer ?: C.COLOR_TRANSFER_HLG)
                .build()
            EncoderUtil.getSupportedEncodersForHdrEditing(MIME_HEVC, color).isNotEmpty()
        }.getOrDefault(false)
        return EncoderCaps(hevc = hevc, hevcHdr = hdr)
    }

    /** `+37.4219-122.0840/` (ISO 6709, as `MediaMetadataRetriever` reports it) → lat, lon. */
    fun parseIso6709(s: String?): Pair<Float, Float>? {
        if (s.isNullOrBlank()) return null
        val m = Regex("""^([+-]\d+(?:\.\d+)?)([+-]\d+(?:\.\d+)?)""").find(s.trim()) ?: return null
        val lat = m.groupValues[1].toFloatOrNull() ?: return null
        val lon = m.groupValues[2].toFloatOrNull() ?: return null
        if (lat !in -90f..90f || lon !in -180f..180f || (lat == 0f && lon == 0f)) return null
        return lat to lon
    }

    /** `20240501T100000.000Z` (MP4 date as `MediaMetadataRetriever` reports it) → epoch ms; null for 1904/1970 placeholders. */
    fun parseMp4Date(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        for (pattern in listOf("yyyyMMdd'T'HHmmss.SSS'Z'", "yyyyMMdd'T'HHmmss'Z'", "yyyyMMdd'T'HHmmss")) {
            val f = SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC"); isLenient = false }
            val t = runCatching { f.parse(s.trim())?.time }.getOrNull() ?: continue
            return t.takeIf { it > 86_400_000L } // 1904-01-01 / 1970-01-01 mean "unset"
        }
        return null
    }
}
