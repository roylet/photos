package com.roylet.photos.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
import androidx.exifinterface.media.ExifInterface
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import com.roylet.photos.domain.library.LibrarySource
import com.roylet.photos.domain.library.SharedUri
import com.roylet.photos.editor.save.EditSource
import com.roylet.photos.video.export.ClipSpec
import com.roylet.photos.video.export.ExportPlan
import com.roylet.photos.video.export.HdrHandling
import com.roylet.photos.video.export.MIME_H264
import com.roylet.photos.video.export.Media3VideoExporter
import com.roylet.photos.video.export.VideoEffectSpec
import com.roylet.photos.video.export.VideoExportException
import com.roylet.photos.video.io.VideoOriginals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Item bytes for compressed shares. Phone items (and merged items with a phone copy) are read in
 * place by their MediaStore URI. Immich videos come from the video editor's originals cache
 * ([VideoOriginals], a download through the shared client that the editor may already have made,
 * held while it's being encoded); Immich photos are downloaded like "Send file" originals.
 */
class AndroidShareSources(
    private val library: () -> LibrarySource,
    private val editSource: () -> EditSource,
    private val originals: () -> VideoOriginals,
) : ShareSources {
    override suspend fun info(id: String): ShareItemInfo? {
        val d = library().details(id) ?: return null
        val name = d.fileName?.takeIf { it.isNotBlank() } ?: id
        return ShareItemInfo(
            id = id,
            fileName = name,
            mimeType = d.mimeType ?: ShareItemInfo.mimeFromName(name),
            sizeBytes = d.sizeBytes?.takeIf { it > 0 },
            width = d.width,
            height = d.height,
            durationMs = d.durationMs,
        )
    }

    override suspend fun open(item: ShareItemInfo, work: File, onProgress: (Long, Long) -> Unit): ShareSource {
        library().shareUri(item.id)?.let { return ShareSource.Content(it.uri) }
        if (item.isVideo) {
            val cache = originals()
            val source = editSource()
            val file = cache.obtain(source, source.info(item.id), onProgress)
            val hold = cache.hold(file)
            return ShareSource.Local(file) { hold.close() }
        }
        val file = library().download(item.id, File(work, "src"), onProgress)
        return ShareSource.Local(file) { file.parentFile?.deleteRecursively() }
    }

    override suspend fun original(item: ShareItemInfo, dir: File, onProgress: (Long, Long) -> Unit): Pair<SharedUri?, File?> {
        library().shareUri(item.id)?.let { return it to null }
        return null to library().download(item.id, dir, onProgress)
    }
}

/**
 * Videos through the editor's [Media3VideoExporter]: hardware H.264 encoder (with Media3's encoder
 * fallback), a Presentation resize to the preset's box, the requested video and AAC bitrates, HDR
 * tone-mapped to SDR, and the container rotation applied (the frame stays upright).
 */
@OptIn(UnstableApi::class)
class Media3ShareTranscoder(private val context: Context, private val exporter: Media3VideoExporter = Media3VideoExporter(context)) : VideoTranscoder {
    override suspend fun transcode(source: ShareSource, preset: VideoPreset, output: File, onProgress: (Float) -> Unit) {
        val probe = withContext(Dispatchers.IO) { probe(source) }
        val plan = plan(probe, preset)
        try {
            when (source) {
                is ShareSource.Content -> exporter.export(Uri.parse(source.uri), plan, output, onProgress)
                is ShareSource.Local -> exporter.export(source.file, plan, output, onProgress)
            }
        } catch (e: VideoExportException) {
            output.delete()
            throw ShareEncodeException(e.message ?: "The video couldn't be compressed.", e.fallbackMayHelp, e)
        }
    }

    /** Size, rotation and audio of the source; enough to plan a share encode. */
    data class Probe(val width: Int, val height: Int, val rotation: Int, val durationMs: Long, val hasAudio: Boolean)

    private fun probe(source: ShareSource): Probe {
        val r = MediaMetadataRetriever()
        try {
            when (source) {
                is ShareSource.Content -> r.setDataSource(context, Uri.parse(source.uri))
                is ShareSource.Local -> r.setDataSource(source.file.path)
            }
            fun meta(key: Int) = runCatching { r.extractMetadata(key) }.getOrNull()
            val w = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            if (w <= 0 || h <= 0) throw ShareEncodeException("This file has no video track this phone can read.", fallbackMayHelp = false)
            return Probe(
                width = w,
                height = h,
                rotation = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0,
                durationMs = meta(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
                hasAudio = meta(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes",
            )
        } catch (e: ShareEncodeException) {
            throw e
        } catch (e: Exception) {
            throw ShareEncodeException("This video can't be read on this phone (${e.message ?: e.javaClass.simpleName}).", fallbackMayHelp = false, cause = e)
        } finally {
            runCatching { r.release() }
        }
    }

    companion object {
        /**
         * The editor's [ExportPlan] for a share encode: the whole clip, H.264, an exact upright
         * size when it shrinks, and tone-mapping (a no-op for SDR sources, so it needn't be probed).
         */
        fun plan(probe: Probe, preset: VideoPreset): ExportPlan {
            val s = ShareEstimator.videoSettings(probe.width, probe.height, probe.rotation, preset)
            return ExportPlan(
                clips = listOf(ClipSpec(0, C.TIME_END_OF_SOURCE)),
                videoEffects = if (s.resized) listOf(VideoEffectSpec.Resize(s.width, s.height, downscale = true)) else emptyList(),
                speed = 1f,
                removeAudio = !probe.hasAudio,
                audioGain = null,
                videoMime = MIME_H264,
                bitrate = s.videoBitrate,
                hdr = HdrHandling.TONE_MAP,
                outputWidth = s.width,
                outputHeight = s.height,
                outputDurationMs = probe.durationMs,
                creationTimeMs = null,
                trimOptimization = false,
                audioBitrate = s.audioBitrate,
            )
        }
    }
}

/**
 * Photos: decoded at the target size (ImageDecoder applies the orientation), saved as JPEG, and
 * the original's EXIF copied over, location included, like the original "Send file" sends it. A
 * phone photo is read through its plain MediaStore URI, so it carries what that URI shows
 * (Android hides GPS there unless the app may see it), the same as sharing the original.
 */
class AndroidPhotoTranscoder(private val context: Context) : PhotoTranscoder {
    override suspend fun compress(source: ShareSource, preset: PhotoPreset, output: File) = withContext(Dispatchers.IO) {
        val decoderSource = when (source) {
            is ShareSource.Content -> ImageDecoder.createSource(context.contentResolver, Uri.parse(source.uri))
            is ShareSource.Local -> ImageDecoder.createSource(source.file)
        }
        val bitmap = ImageDecoder.decodeBitmap(decoderSource) { decoder, info, _ ->
            val out = ShareEstimator.photoSettings(info.size.width, info.size.height, preset)
            if (out.width < info.size.width) decoder.setTargetSize(out.width, out.height)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        try {
            output.parentFile?.mkdirs()
            output.outputStream().use { if (!bitmap.compress(Bitmap.CompressFormat.JPEG, preset.jpegQuality, it)) error("JPEG encoding failed") }
            runCatching { copyExif(source, output, bitmap.width, bitmap.height) }
        } finally {
            bitmap.recycle()
        }
        Unit
    }

    private fun copyExif(source: ShareSource, output: File, width: Int, height: Int) {
        val from = when (source) {
            is ShareSource.Content -> context.contentResolver.openFileDescriptor(Uri.parse(source.uri), "r")?.use { ExifInterface(it.fileDescriptor).snapshot() }
            is ShareSource.Local -> ExifInterface(source.file).snapshot()
        } ?: return
        val to = ExifInterface(output)
        from.forEach { (tag, value) -> to.setAttribute(tag, value) }
        // The pixels are upright now and smaller.
        to.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        to.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, width.toString())
        to.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, height.toString())
        to.saveAttributes()
    }

    private fun ExifInterface.snapshot(): Map<String, String> = COPIED_TAGS.mapNotNull { t -> getAttribute(t)?.let { t to it } }.toMap()

    companion object {
        /** Descriptive EXIF worth keeping on a smaller copy (not sizes, thumbnails or maker notes). */
        val COPIED_TAGS = listOf(
            ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
            ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
            ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL,
            ExifInterface.TAG_SOFTWARE, ExifInterface.TAG_ARTIST, ExifInterface.TAG_COPYRIGHT,
            ExifInterface.TAG_IMAGE_DESCRIPTION, ExifInterface.TAG_USER_COMMENT,
            ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_APERTURE_VALUE,
            ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
            ExifInterface.TAG_EXPOSURE_BIAS_VALUE, ExifInterface.TAG_EXPOSURE_PROGRAM, ExifInterface.TAG_METERING_MODE,
            ExifInterface.TAG_FLASH, ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_COLOR_SPACE,
            ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF, ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP, ExifInterface.TAG_GPS_IMG_DIRECTION,
            ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
        )
    }
}
