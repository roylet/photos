package com.roylet.photos.video.preview

import android.content.Context
import android.graphics.Color as AndroidColor
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.PlaybackException
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.audio.GainProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.video.VideoSink
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.roylet.photos.ui.gallery.LocalMediaHttpClient
import com.roylet.photos.video.export.ColorParamsHolder
import com.roylet.photos.video.export.CompositionPlanner
import com.roylet.photos.video.export.Media3Compositions
import com.roylet.photos.video.export.VideoColorEffect
import com.roylet.photos.video.export.VideoEffectSpec
import com.roylet.photos.video.export.isVideoColorIdentity
import com.roylet.photos.video.model.AudioSettings
import com.roylet.photos.video.model.TimeRange
import com.roylet.photos.video.model.VideoEdit
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import kotlin.math.roundToLong

/**
 * Which video effects the preview runs for an edit. The custom colour pass is a raw-GLES30 shader
 * on Media3's GL thread: when the recipe changes nothing (or the effects are [disabled] after a
 * pipeline failure) the preview runs with no effects at all, the plain decoder-to-surface path.
 */
data class PreviewEffectPlan(val geometry: List<VideoEffectSpec>, val color: Boolean) {
    val isEmpty: Boolean get() = geometry.isEmpty() && !color

    companion object {
        val NONE = PreviewEffectPlan(emptyList(), false)

        fun of(edit: VideoEdit, disabled: Boolean = false): PreviewEffectPlan {
            if (disabled) return NONE
            return PreviewEffectPlan(CompositionPlanner.geometryEffects(edit.geometry), !isVideoColorIdentity(edit.color))
        }
    }
}

/** Something the preview tells the person about itself. */
sealed interface PreviewNotice {
    /** The effect pipeline failed on this phone: the preview plays without colour/crop (the export still applies them). */
    data object NoFilters : PreviewNotice

    /** The player failed; [VideoPreviewController.retry] prepares it again. */
    data class Failed(val message: String) : PreviewNotice
}

/** Error classification for the preview's fallback. */
@OptIn(UnstableApi::class)
object PreviewErrors {
    /** True when [error] comes from the video effect / frame-processing pipeline (not the source or decoder). */
    fun isEffectError(error: Throwable): Boolean {
        if (error is PlaybackException &&
            (error.errorCode == PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED)
        ) return true
        var c: Throwable? = error
        var depth = 0
        while (c != null && depth++ < 12) {
            if (c is VideoFrameProcessingException || c is VideoSink.VideoSinkException || c is GlUtil.GlException) return true
            c = c.cause
        }
        return false
    }

    fun message(error: PlaybackException): String = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "The downloaded original is missing."
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        -> "This phone couldn't decode the video for the preview."
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED, PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED -> "The preview's audio failed."
        else -> "The preview stopped (${error.errorCodeName})."
    }
}

/**
 * Plays a [VideoEdit] live in an ExoPlayer, as the export will render it:
 * - **segments** → a playlist of the source clipped per segment (`ClippingConfiguration`), so cuts
 *   play seamlessly; the playlist is updated in place only when the segments change, keeping the
 *   output position and play/pause;
 * - **colour** → the export's [VideoColorEffect] (the photo editor's shader) through
 *   `setVideoEffects`, only while the recipe changes something ([PreviewEffectPlan]); slider changes
 *   update a shared [ColorParamsHolder] read every frame, so no effect-chain rebuild per change;
 *   while paused the current frame is re-rendered (a throttled exact seek). If the effect pipeline
 *   fails, the preview drops its effects, prepares again and says so ([PreviewNotice.NoFilters]);
 * - **crop/rotate/flip** → Media3 `Crop`/`ScaleAndRotateTransformation` (rebuilds the chain);
 * - **speed** → `PlaybackParameters` (pitch kept); **volume** → a live [GainProcessor] in the audio
 *   sink (so 100–200 % is audible too); **mute** → player volume 0.
 *
 * All methods must be called on the main thread. Create one per editor screen; [release] it.
 * Positions are output time (see [VideoEdit.outputToSource]).
 *
 * Scrubbing: [beginScrub] pauses and turns on Media3's scrubbing mode. With `sourceTime` (a trim
 * handle, which can move into cut-out time) the player temporarily plays the whole, unclipped
 * source and [scrubToSource] shows any source moment; [endScrub] puts the edit's playlist back,
 * rests on the given output time and resumes if it was playing.
 */
@OptIn(UnstableApi::class)
class VideoPreviewController(context: Context, httpClient: OkHttpClient? = null) {
    private val liveGain = LiveGain()
    private val color = ColorParamsHolder()
    private val colorEffect = VideoColorEffect(color)
    private val scope = MainScope()
    private var ticker: Job? = null
    private var refreshJob: Job? = null

    val player: ExoPlayer = ExoPlayer.Builder(context, GainRenderersFactory(context, liveGain))
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(
                if (httpClient != null) DefaultDataSource.Factory(context, OkHttpDataSource.Factory(httpClient))
                else DefaultDataSource.Factory(context),
            ),
        )
        .build()
        .apply { setSeekParameters(SeekParameters.EXACT) }

    private var uri: Uri? = null
    private var edit: VideoEdit? = null
    private var playlist: List<TimeRange> = emptyList()

    /** The effects currently set on the player. */
    internal var effects: PreviewEffectPlan = PreviewEffectPlan.NONE
        private set

    /** Set after the effect pipeline failed: the rest of this session previews without effects. */
    internal var effectsDisabled = false
        private set
    private var renderedFrame = false

    private var scrubbing = false
    private var sourceScrub = false
    private var resumeAfterScrub = false

    private val _outputPosition = MutableStateFlow(0L)
    private val _outputDuration = MutableStateFlow(0L)
    private val _isPlaying = MutableStateFlow(false)
    private val _notice = MutableStateFlow<PreviewNotice?>(null)

    /** Current position in the edited video (ms), updated ~30×/s while playing. */
    val outputPosition: StateFlow<Long> = _outputPosition.asStateFlow()
    val outputDuration: StateFlow<Long> = _outputDuration.asStateFlow()

    /** Playing, or about to (buffering with play requested). */
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    /** A fallback or failure to show over the preview; null when all is well. */
    val notice: StateFlow<PreviewNotice?> = _notice.asStateFlow()

    init {
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                val playing = wantsToPlay()
                if (playing != _isPlaying.value) {
                    _isPlaying.value = playing
                    if (playing) startTicker() else { ticker?.cancel(); publishPosition() }
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && !sourceScrub) { player.pause(); _outputPosition.value = _outputDuration.value }
            }

            override fun onRenderedFirstFrame() { renderedFrame = true }

            override fun onPlayerError(error: PlaybackException) = handleError(error)

            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) = publishPosition()
        })
    }

    private fun wantsToPlay(): Boolean =
        player.playWhenReady && (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING)

    /** Plays a local file (the downloaded original). */
    fun setSource(file: File) = setSource(Uri.fromFile(file))

    /** Plays a remote URL (needs the `httpClient`), e.g. while the original downloads. */
    fun setSource(url: String) = setSource(Uri.parse(url))

    fun setSource(uri: Uri) {
        if (uri == this.uri) return
        val keep = _outputPosition.value
        this.uri = uri
        playlist = emptyList()
        renderedFrame = false
        edit?.let { apply(it, keep, keepSourceMoment = false) }
    }

    /** Applies [edit]: cheap for colour/speed/volume changes; segments and geometry rebuild what they must. */
    fun setEdit(edit: VideoEdit) {
        val e = edit.normalized()
        val old = this.edit
        this.edit = e
        apply(e, if (old != null) currentOutputMs(old) else 0L)
    }

    private fun apply(e: VideoEdit, keepOutputMs: Long, keepSourceMoment: Boolean = true) {
        val sourceMs = if (keepSourceMoment && playlist.isNotEmpty() && !sourceScrub) currentSourceMs() else null
        _outputDuration.value = e.outputDurationMs()

        val colorChanged = e.color != color.recipe
        color.set(e.color)
        updateEffects(e)
        player.playbackParameters = PlaybackParameters(e.speed)
        val audio = PreviewAudio.of(e.audio)
        player.volume = audio.playerVolume
        liveGain.gain = audio.gain

        val u = uri ?: return
        if (sourceScrub) return // the playlist comes back in [endScrub]
        if (e.segments != playlist) {
            playlist = e.segments
            // Keep the same source moment when it's still kept; else the same output time.
            val target = sourceMs?.let { e.sourceToOutput(it) } ?: keepOutputMs
            val out = target.coerceIn(0, e.outputDurationMs())
            val pos = e.outputToSource(out)
            val items = e.segments.mapIndexed { i, s ->
                MediaItem.Builder()
                    .setUri(u)
                    .setMediaId("segment-$i")
                    .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(s.startMs).setEndPositionMs(s.endMs).build())
                    .build()
            }
            // Replaces the playlist in place: play/pause (playWhenReady) is untouched.
            player.setMediaItems(items, pos.segmentIndex, pos.offsetMs)
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            _outputPosition.value = out
        } else if (colorChanged && !player.isPlaying) {
            refreshFrame()
        }
    }

    /**
     * Sets the effects [e] needs on the player (none for an identity look). Switching between no
     * effects and some re-prepares at the same spot so the renderer picks up the new video path.
     */
    private fun updateEffects(e: VideoEdit) {
        val plan = PreviewEffectPlan.of(e, effectsDisabled)
        if (effectsDisabled && _notice.value !is PreviewNotice.Failed) _notice.value = if (PreviewEffectPlan.of(e).isEmpty) null else PreviewNotice.NoFilters
        if (plan == effects) return
        val reprepare = plan.isEmpty != effects.isEmpty && player.playbackState != Player.STATE_IDLE
        effects = plan
        player.setVideoEffects(effectList(plan))
        if (reprepare) {
            renderedFrame = false
            player.stop()
            player.prepare()
        }
    }

    private fun effectList(plan: PreviewEffectPlan): List<Effect> =
        if (plan.isEmpty) emptyList()
        else Media3Compositions.videoEffects(plan.geometry) + (if (plan.color) listOf<Effect>(colorEffect) else emptyList())

    /**
     * A player error. Effects on and the effect pipeline to blame (or no frame ever made it out):
     * drop the effects for this session, prepare again where it was and keep its play state.
     * Anything else becomes a [PreviewNotice.Failed] with [retry].
     */
    internal fun handleError(error: PlaybackException) {
        Log.w(TAG, "Preview error ${error.errorCodeName}", error)
        if (!effects.isEmpty && (PreviewErrors.isEffectError(error) || !renderedFrame)) {
            effectsDisabled = true
            effects = PreviewEffectPlan.NONE
            player.setVideoEffects(emptyList())
            _notice.value = PreviewNotice.NoFilters
            if (player.mediaItemCount > 0) player.prepare()
            return
        }
        _notice.value = PreviewNotice.Failed(PreviewErrors.message(error))
    }

    /** After a [PreviewNotice.Failed]: prepares again at the same spot. */
    fun retry() {
        if (_notice.value is PreviewNotice.Failed) _notice.value = if (effectsDisabled) PreviewNotice.NoFilters else null
        if (player.playbackState == Player.STATE_IDLE && player.mediaItemCount > 0) player.prepare()
    }

    /** Plays from the playhead (from the start when it sits at the very end). */
    fun play() {
        if (sourceScrub) return
        if (player.playbackState == Player.STATE_IDLE && player.mediaItemCount > 0) retry()
        val dur = _outputDuration.value
        if (player.playbackState == Player.STATE_ENDED || _outputPosition.value >= dur - END_SLACK_MS) seekToOutputTime(0)
        player.play()
    }

    fun pause() = player.pause()

    fun togglePlay() = if (player.playWhenReady && player.playbackState != Player.STATE_ENDED) pause() else play()

    /** Seeks to [ms] of the edited video. */
    fun seekToOutputTime(ms: Long) {
        val e = edit ?: return
        if (playlist.isEmpty() || sourceScrub) return
        val out = ms.coerceIn(0, e.outputDurationMs())
        val pos = e.outputToSource(out)
        player.seekTo(pos.segmentIndex, pos.offsetMs)
        _outputPosition.value = out
    }

    /**
     * Starts scrubbing: pauses (remembering whether it played) and turns on Media3's scrubbing mode
     * (fast, coalesced seeks). With [sourceTime] the player shows the unclipped source, so a trim
     * handle can preview time that is currently cut out ([scrubToSource]).
     */
    fun beginScrub(sourceTime: Boolean) {
        if (scrubbing) return
        scrubbing = true
        resumeAfterScrub = player.playWhenReady && player.playbackState != Player.STATE_ENDED
        player.pause()
        player.isScrubbingModeEnabled = true
        val u = uri
        if (sourceTime && u != null && edit != null) {
            val at = if (playlist.isNotEmpty()) currentSourceMs() else 0L
            sourceScrub = true
            player.setMediaItem(MediaItem.Builder().setUri(u).setMediaId("source").build(), at)
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
        }
    }

    /** While scrubbing: shows source time [sourceMs] (cut-out time too, in source mode). */
    fun scrubToSource(sourceMs: Long) {
        if (!sourceScrub) {
            edit?.let { e -> seekToOutputTime(sourceToOutputClamped(e, sourceMs)) }
            return
        }
        player.seekTo(0, sourceMs.coerceAtLeast(0))
    }

    /**
     * Ends scrubbing. [edit] (if given) is the edit to play now; the preview rests exactly on
     * output time [restOutputMs] (or where it is), and plays again if it was playing.
     */
    fun endScrub(edit: VideoEdit? = null, restOutputMs: Long? = null) {
        if (edit != null) this.edit = edit.normalized()
        val e = this.edit
        player.isScrubbingModeEnabled = false
        val wasSource = sourceScrub
        sourceScrub = false
        if (e != null) {
            val rest = (restOutputMs ?: _outputPosition.value).coerceIn(0, e.outputDurationMs())
            if (wasSource) playlist = emptyList()
            apply(e, rest, keepSourceMoment = false)
            seekToOutputTime(rest) // the exact, final seek
        }
        if (scrubbing && resumeAfterScrub) play()
        scrubbing = false
        resumeAfterScrub = false
    }

    /**
     * The screen went to the background: pause and free the decoders (a 4K one is scarce); the
     * playlist, effects and position are kept for [onForeground].
     */
    fun onBackground() {
        if (scrubbing) endScrub()
        player.pause()
        player.stop()
    }

    /** Back from [onBackground]: prepares again at the same position (paused). */
    fun onForeground() {
        if (player.playbackState == Player.STATE_IDLE && player.mediaItemCount > 0 && _notice.value !is PreviewNotice.Failed) player.prepare()
    }

    /** Stops playback and frees the decoder and GL pipeline. The controller can't be reused. */
    fun release() {
        scope.cancel()
        player.release()
    }

    private fun sourceToOutputClamped(e: VideoEdit, sourceMs: Long): Long {
        e.sourceToOutput(sourceMs)?.let { return it }
        val nearest = e.segments.minBy { s -> kotlin.math.abs(sourceMs.coerceIn(s.startMs, s.endMs) - sourceMs) }
        return e.sourceToOutput(sourceMs.coerceIn(nearest.startMs, nearest.endMs)) ?: 0L
    }

    private fun currentSourceMs(): Long {
        val i = player.currentMediaItemIndex.coerceIn(0, (playlist.size - 1).coerceAtLeast(0))
        return (playlist.getOrNull(i)?.startMs ?: 0L) + player.currentPosition.coerceAtLeast(0)
    }

    private fun currentOutputMs(e: VideoEdit): Long {
        if (playlist.isEmpty() || sourceScrub) return _outputPosition.value
        val i = player.currentMediaItemIndex.coerceIn(0, playlist.size - 1)
        val before = playlist.take(i).sumOf { it.durationMs }
        return ((before + player.currentPosition.coerceAtLeast(0)) / e.speed.toDouble()).roundToLong()
    }

    private fun publishPosition() {
        val e = edit ?: return
        if (sourceScrub) return
        _outputPosition.value = currentOutputMs(e).coerceIn(0, e.outputDurationMs())
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                publishPosition()
                delay(33)
            }
        }
    }

    /** Re-renders the paused frame with the new colour (an exact seek in place), at most ~10×/s. */
    private fun refreshFrame() {
        if (refreshJob?.isActive == true) return
        refreshJob = scope.launch {
            delay(100)
            if (!player.isPlaying && player.playbackState == Player.STATE_READY && !scrubbing) player.seekTo(player.currentMediaItemIndex, player.currentPosition)
        }
    }

    private companion object {
        const val TAG = "VideoPreview"

        /** Play pressed this close to the end starts over. */
        const val END_SLACK_MS = 250L
    }
}

/** What the preview hands the player for [AudioSettings]: [playerVolume] stays in 0..1, [gain] (0..2) goes to the [LiveGain]. */
data class PreviewAudio(val playerVolume: Float, val gain: Float) {
    companion object {
        fun of(audio: AudioSettings): PreviewAudio {
            val a = audio.normalized()
            return if (a.mute) PreviewAudio(0f, 0f) else PreviewAudio(1f, (a.volume / 100f).coerceIn(0f, MAX_GAIN))
        }

        const val MAX_GAIN = 2f
    }
}

/**
 * A gain the audio sink applies live (the preview's volume, up to 200 %).
 *
 * Media3's [GainProcessor] contract: [isUnityUntil] is only asked while the gain is exactly 1 and
 * must return a real sample position (`C.TIME_UNSET` fails a `checkState` on the playback thread,
 * which is what stopped every 100 % preview in v0.2.1 with ERROR_CODE_FAILED_RUNTIME_CHECK), or
 * `C.TIME_END_OF_SOURCE`, which would make the processor inactive and freeze the volume at 100 %.
 * So it reports a short unity region ahead ([UNITY_CHUNK_SECONDS]); the processor passes that
 * through untouched and asks again, so a volume change is heard within a fraction of a second.
 */
@OptIn(UnstableApi::class)
class LiveGain : GainProcessor.GainProvider {
    @Volatile var gain: Float = 1f
    override fun getGainFactorAtSamplePosition(samplePosition: Long, sampleRate: Int): Float = gain
    override fun isUnityUntil(samplePosition: Long, sampleRate: Int): Long =
        samplePosition.coerceAtLeast(0) + (sampleRate * UNITY_CHUNK_SECONDS).toLong().coerceAtLeast(1)

    private companion object {
        const val UNITY_CHUNK_SECONDS = 0.05
    }
}

@OptIn(UnstableApi::class)
private class GainRenderersFactory(context: Context, private val gain: LiveGain) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink =
        DefaultAudioSink.Builder(context)
            .setAudioProcessors(arrayOf(GainProcessor(gain)))
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
            .build()
}

/**
 * Remembers a [VideoPreviewController] for this composition and [key] (using the app's shared OkHttp client
 * for remote sources), pauses it and frees its decoders while the app is in the background, and
 * releases it on dispose. Null in previews/screenshots.
 */
@Composable
fun rememberVideoPreviewController(key: Any?): VideoPreviewController? {
    if (LocalInspectionMode.current) return null
    val context = LocalContext.current
    val client = LocalMediaHttpClient.current
    // One player per editor screen (keyed by the asset), prepared once and updated in place.
    val controller = remember(key) { VideoPreviewController(context.applicationContext, client) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(controller, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> controller.onBackground()
                Lifecycle.Event.ON_START -> controller.onForeground()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    DisposableEffect(controller) { onDispose { controller.release() } }
    return controller
}

/** The edited video picture, letterboxed (SurfaceView-backed `PlayerView`, no built-in controls). */
@OptIn(UnstableApi::class)
@Composable
fun VideoEditPreview(controller: VideoPreviewController, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                setShutterBackgroundColor(AndroidColor.BLACK)
                setBackgroundColor(AndroidColor.BLACK)
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                player = controller.player
            }
        },
        update = { it.player = controller.player },
        onRelease = { it.player = null },
        modifier = modifier,
    )
}
