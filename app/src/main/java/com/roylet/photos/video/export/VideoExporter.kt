package com.roylet.photos.video.export

import android.content.Context
import android.net.Uri
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.DefaultGainProvider
import androidx.media3.common.audio.GainProcessor
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.Mp4LocationData
import androidx.media3.container.Mp4TimestampData
import androidx.media3.effect.Crop
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.InAppMp4Muxer
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.TransformationRequest
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** What an export produced. */
data class ExportSummary(
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val fileSizeBytes: Long,
    val videoMime: String?,
    /** Something the user should know (a fallback was used); null when it went as planned. */
    val warning: String? = null,
)

/**
 * An export failure with a sentence for the user. [fallbackMayHelp] says whether retrying with
 * [ExportPlan.fallback] (H.264, SDR) is worth it; [code] is the Media3 `ExportException` code.
 */
class VideoExportException(
    message: String,
    val fallbackMayHelp: Boolean = false,
    val code: Int = 0,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Renders a plan into an MP4. Implemented by [Media3VideoExporter]; tests use a fake, since
 * Transformer needs real codecs.
 */
interface VideoExportEngine {
    /**
     * Exports [source] (a local file) per [plan] into [output]. Reports progress `0..1` from any
     * thread. Cancelling the coroutine cancels the export. Throws [VideoExportException].
     */
    suspend fun export(source: File, plan: ExportPlan, output: File, onProgress: (Float) -> Unit): ExportSummary
}

/** Builds Media3 objects from an [ExportPlan] / [VideoEffectSpec]s. Shared by export and preview. */
@OptIn(UnstableApi::class)
object Media3Compositions {

    /** Media3 video effects for [specs]; the colour pass uses [colorHolder] when given (live preview). */
    fun videoEffects(specs: List<VideoEffectSpec>, colorHolder: ColorParamsHolder? = null): List<Effect> = specs.map { s ->
        when (s) {
            is VideoEffectSpec.Rotate -> ScaleAndRotateTransformation.Builder().setRotationDegrees(s.degreesCcw).build()
            is VideoEffectSpec.Flip ->
                ScaleAndRotateTransformation.Builder().setScale(if (s.horizontal) -1f else 1f, if (s.vertical) -1f else 1f).build()
            is VideoEffectSpec.Crop -> Crop(s.left, s.right, s.bottom, s.top)
            is VideoEffectSpec.Resize -> Presentation.createForWidthAndHeight(s.width, s.height, Presentation.LAYOUT_STRETCH_TO_FIT)
                .let { if (s.downscale) it.copyWithTextureMinFilter(GLES20.GL_LINEAR_MIPMAP_LINEAR) else it }
            is VideoEffectSpec.Color -> colorHolder?.also { it.set(s.recipe) }?.let { VideoColorEffect(it) } ?: VideoColorEffect(s.recipe)
        }
    }

    fun audioProcessors(plan: ExportPlan): List<AudioProcessor> =
        plan.audioGain?.let { listOf(GainProcessor(DefaultGainProvider.Builder(it).build())) } ?: emptyList()

    fun hdrMode(h: HdrHandling): Int = when (h) {
        HdrHandling.NONE, HdrHandling.KEEP -> Composition.HDR_MODE_KEEP_HDR
        HdrHandling.TONE_MAP -> Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL
    }

    /** One `EditedMediaItem` per clip, in one sequence. Each item gets its own processor instances. */
    fun composition(plan: ExportPlan, uri: Uri): Composition {
        val items = plan.clips.map { clip ->
            val mediaItem = MediaItem.Builder()
                .setUri(uri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder().setStartPositionMs(clip.startMs).setEndPositionMs(clip.endMs).build(),
                )
                .build()
            EditedMediaItem.Builder(mediaItem)
                .setRemoveAudio(plan.removeAudio)
                .setEffects(Effects(audioProcessors(plan), videoEffects(plan.videoEffects)))
                .apply { if (plan.speed != 1f) setSpeed(ConstantSpeed(plan.speed)) }
                .build()
        }
        return Composition.Builder(EditedMediaItemSequence.Builder(items).build())
            .setHdrMode(hdrMode(plan.hdr))
            .build()
    }
}

/** A speed that never changes. Media3 time-stretches audio with Sonic, so pitch is kept. */
@OptIn(UnstableApi::class)
class ConstantSpeed(val speed: Float) : SpeedProvider {
    override fun getSpeed(timeUs: Long): Float = speed
    override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
}

/** Friendly text for a Media3 `ExportException` code, and whether H.264/SDR might succeed instead. */
@OptIn(UnstableApi::class)
fun describeExportError(code: Int): Pair<String, Boolean> = when (code) {
    ExportException.ERROR_CODE_ENCODER_INIT_FAILED,
    ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED ->
        "This phone's video encoder can't produce this format or size." to true
    ExportException.ERROR_CODE_ENCODING_FAILED ->
        "The video encoder failed partway through." to true
    ExportException.ERROR_CODE_DECODER_INIT_FAILED,
    ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
        "This phone can't decode this video (its codec or resolution isn't supported here)." to false
    ExportException.ERROR_CODE_DECODING_FAILED ->
        "The video couldn't be decoded; the file may be damaged." to false
    ExportException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED ->
        "Applying the edits to the frames failed on this phone's GPU." to true
    ExportException.ERROR_CODE_AUDIO_PROCESSING_FAILED ->
        "The audio couldn't be processed. Try muting it." to false
    ExportException.ERROR_CODE_MUXING_FAILED,
    ExportException.ERROR_CODE_MUXING_TIMEOUT,
    ExportException.ERROR_CODE_MUXING_APPEND ->
        "Writing the video file failed. Is the phone's storage full?" to true
    ExportException.ERROR_CODE_IO_FILE_NOT_FOUND ->
        "The downloaded original was removed from the cache." to false
    ExportException.ERROR_CODE_IO_NO_PERMISSION,
    ExportException.ERROR_CODE_IO_UNSPECIFIED,
    ExportException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE ->
        "The original video couldn't be read." to false
    else -> "The video couldn't be exported." to true
}

/**
 * Exports with Media3 Transformer: hardware decoders/encoders (MediaCodec), GL effects, Media3's
 * encoder fallback (a smaller size or another profile when the requested one isn't supported),
 * then one retry with [ExportPlan.fallback] (H.264, SDR) for encoder/GPU failures.
 *
 * The MP4 is written by Media3's in-app muxer, which carries the source's `mvhd` creation time and
 * `©xyz` location over and lets us add them when missing ([ExportPlan.creationTimeMs]).
 *
 * Each export runs Transformer on its own looper thread, so the caller's thread (a WorkManager
 * worker) only waits; progress is polled every 250 ms.
 */
@OptIn(UnstableApi::class)
class Media3VideoExporter(context: Context) : VideoExportEngine {
    private val app = context.applicationContext

    override suspend fun export(source: File, plan: ExportPlan, output: File, onProgress: (Float) -> Unit): ExportSummary =
        withFallback(plan, output, onProgress) { p ->
            if (!source.exists()) throw VideoExportException("The downloaded original was removed from the cache.")
            runOnce(Uri.fromFile(source), p, output, onProgress)
        }

    /** The same for a video read in place by its content URI (a phone video, for a compressed share). */
    suspend fun export(source: Uri, plan: ExportPlan, output: File, onProgress: (Float) -> Unit): ExportSummary =
        withFallback(plan, output, onProgress) { p -> runOnce(source, p, output, onProgress) }

    private suspend fun withFallback(
        plan: ExportPlan,
        output: File,
        onProgress: (Float) -> Unit,
        run: suspend (ExportPlan) -> ExportSummary,
    ): ExportSummary = try {
        run(plan)
    } catch (e: VideoExportException) {
        val fallback = plan.fallback()
        if (!e.fallbackMayHelp || fallback == null) throw e
        output.delete()
        onProgress(0f)
        val note = buildList {
            if (plan.videoMime != fallback.videoMime) add("H.264")
            if (plan.hdr != fallback.hdr) add("SDR")
        }.joinToString(" and ").ifEmpty { "a simpler format" }
        run(fallback).let { it.copy(warning = it.warning ?: "Exported as $note: ${e.message}") }
    }

    private suspend fun runOnce(source: Uri, plan: ExportPlan, output: File, onProgress: (Float) -> Unit): ExportSummary {
        output.parentFile?.mkdirs()
        output.delete()
        val thread = HandlerThread("video-export").apply { start() }
        val handler = Handler(thread.looper)
        try {
            return suspendCancellableCoroutine { cont ->
                var transformer: Transformer? = null
                var done = false
                var warning: String? = null
                cont.invokeOnCancellation { handler.post { done = true; transformer?.cancel() } }
                handler.post {
                    if (done) return@post
                    val listener = object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, result: ExportResult) {
                            done = true
                            onProgress(1f)
                            cont.resume(
                                ExportSummary(result.width, result.height, result.durationMs, output.length(), result.videoMimeType, warning),
                            )
                        }

                        override fun onError(composition: Composition, result: ExportResult, exception: ExportException) {
                            done = true
                            val (msg, fallback) = describeExportError(exception.errorCode)
                            cont.resumeWithException(VideoExportException(msg, fallback, exception.errorCode, exception))
                        }

                        override fun onFallbackApplied(composition: Composition, original: TransformationRequest, fallback: TransformationRequest) {
                            warning = if (fallback.outputHeight != original.outputHeight && fallback.outputHeight > 0) {
                                "Exported at ${fallback.outputHeight}p: this phone's encoder can't handle the full size."
                            } else {
                                "Adjusted to settings this phone's encoder supports."
                            }
                        }
                    }
                    try {
                        val t = build(plan, listener)
                        transformer = t
                        t.start(Media3Compositions.composition(plan, source), output.path)
                        val holder = ProgressHolder()
                        handler.post(object : Runnable {
                            override fun run() {
                                if (done) return
                                if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress / 100f)
                                handler.postDelayed(this, 250)
                            }
                        })
                    } catch (e: Exception) {
                        done = true
                        cont.resumeWithException(VideoExportException("The export couldn't start (${e.message ?: e.javaClass.simpleName}).", true, cause = e))
                    }
                }
            }
        } finally {
            thread.quitSafely()
        }
    }

    private fun build(plan: ExportPlan, listener: Transformer.Listener): Transformer {
        val encoders = DefaultEncoderFactory.Builder(app)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(plan.bitrate).build())
            .setEnableFallback(true)
            .apply { plan.audioBitrate?.let { setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(it).build()) } }
            .build()
        val muxer = InAppMp4Muxer.Factory { entries ->
            plan.creationTimeMs?.takeIf { t -> entries.none { it is Mp4TimestampData } }?.let {
                val secs = Mp4TimestampData.unixTimeToMp4TimeSeconds(it)
                entries.add(Mp4TimestampData(secs, secs))
            }
            if (plan.latitude != null && plan.longitude != null && entries.none { it is Mp4LocationData }) {
                entries.add(Mp4LocationData(plan.latitude, plan.longitude))
            }
        }
        return Transformer.Builder(app)
            .setLooper(android.os.Looper.myLooper()!!)
            .setVideoMimeType(plan.videoMime)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(encoders)
            .setMuxerFactory(muxer)
            .experimentalSetTrimOptimizationEnabled(plan.trimOptimization)
            .addListener(listener)
            .build()
    }
}
