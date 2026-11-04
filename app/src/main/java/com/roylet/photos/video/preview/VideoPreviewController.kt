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
import androidx.media3.common.C
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
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.roylet.photos.ui.gallery.LocalMediaHttpClient
import com.roylet.photos.video.export.ColorParamsHolder
import com.roylet.photos.video.export.CompositionPlanner
import com.roylet.photos.video.export.Media3Compositions
import com.roylet.photos.video.export.VideoColorEffect
import com.roylet.photos.video.export.VideoEffectSpec
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
 * Plays a [VideoEdit] live in an ExoPlayer, as the export will render it:
 * - **segments** → a playlist of the source clipped per segment (`ClippingConfiguration`), so cuts
 *   play seamlessly; the playlist is rebuilt only when the segments change, keeping the position;
 * - **colour** → the export's [VideoColorEffect] (the photo editor's shader) through
 *   `setVideoEffects`; slider changes update a shared [ColorParamsHolder] read every frame, so no
 *   effect-chain rebuild; while paused the current frame is re-rendered (a throttled exact seek);
 * - **crop/rotate/flip** → Media3 `Crop`/`ScaleAndRotateTransformation` (rebuilds the chain);
 * - **speed** → `PlaybackParameters` (pitch kept); **volume** → a live [GainProcessor] in the audio
 *   sink (so 100–200 % is audible too); **mute** → player volume 0.
 *
 * All methods must be called on the main thread. Create one per editor screen; [release] it.
 * Positions are output time (see [VideoEdit.outputToSource]).
 *
 * Trim handles: the playlist only holds kept time, so show the frame under a dragged handle with
 * `FrameStripProvider.frame(file, sourceMs)` and call [setEdit] on release (or as it moves; a
 * rebuild of a local file is cheap).
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
        .apply {
            setSeekParameters(SeekParameters.EXACT)
            setVideoEffects(listOf(colorEffect))
        }

    private var uri: Uri? = null
    private var edit: VideoEdit? = null
    private var playlist: List<TimeRange> = emptyList()
    private var geometry: List<VideoEffectSpec> = emptyList()

    private val _outputPosition = MutableStateFlow(0L)
    private val _outputDuration = MutableStateFlow(0L)
    private val _isPlaying = MutableStateFlow(false)

    /** Current position in the edited video (ms), updated ~30×/s while playing. */
    val outputPosition: StateFlow<Long> = _outputPosition.asStateFlow()
    val outputDuration: StateFlow<Long> = _outputDuration.asStateFlow()
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
                if (isPlaying) startTicker() else { ticker?.cancel(); publishPosition() }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) { player.pause(); _outputPosition.value = _outputDuration.value }
            }

            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) = publishPosition()
        })
    }

    /** Plays a local file (the downloaded original). */
    fun setSource(file: File) = setSource(Uri.fromFile(file))

    /** Plays a remote URL (needs the `httpClient`), e.g. while the original downloads. */
    fun setSource(url: String) = setSource(Uri.parse(url))

    fun setSource(uri: Uri) {
        if (uri == this.uri) return
        val keep = _outputPosition.value
        this.uri = uri
        playlist = emptyList()
        edit?.let { apply(it, keep) }
    }

    /** Applies [edit]: cheap for colour/speed/volume changes; segments and geometry rebuild what they must. */
    fun setEdit(edit: VideoEdit) {
        val e = edit.normalized()
        val old = this.edit
        this.edit = e
        apply(e, if (old != null) currentOutputMs(old) else 0L)
    }

    private fun apply(e: VideoEdit, keepOutputMs: Long) {
        val sourceMs = if (playlist.isNotEmpty()) currentSourceMs() else null
        _outputDuration.value = e.outputDurationMs()

        val newGeometry = CompositionPlanner.geometryEffects(e.geometry)
        if (newGeometry != geometry) {
            geometry = newGeometry
            player.setVideoEffects(Media3Compositions.videoEffects(newGeometry) + colorEffect)
        }
        val colorChanged = e.color != color.recipe
        color.set(e.color)
        player.playbackParameters = PlaybackParameters(e.speed)
        player.volume = if (e.audio.mute) 0f else 1f
        liveGain.gain = if (e.audio.mute) 0f else e.audio.gain

        val u = uri ?: return
        if (e.segments != playlist) {
            playlist = e.segments
            // Keep the same source moment when it's still kept; else the same output time.
            val target = sourceMs?.let { e.sourceToOutput(it) } ?: keepOutputMs
            val pos = e.outputToSource(target.coerceIn(0, e.outputDurationMs()))
            val items = e.segments.mapIndexed { i, s ->
                MediaItem.Builder()
                    .setUri(u)
                    .setMediaId("segment-$i")
                    .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(s.startMs).setEndPositionMs(s.endMs).build())
                    .build()
            }
            player.setMediaItems(items, pos.segmentIndex, pos.offsetMs)
            player.prepare()
            publishPosition()
        } else if (colorChanged && !player.isPlaying) {
            refreshFrame()
        }
    }

    fun play() {
        if (player.playbackState == Player.STATE_ENDED || _outputPosition.value >= _outputDuration.value) seekToOutputTime(0)
        player.play()
    }

    fun pause() = player.pause()

    fun togglePlay() = if (player.isPlaying) pause() else play()

    /** Seeks to [ms] of the edited video. */
    fun seekToOutputTime(ms: Long) {
        val e = edit ?: return
        if (playlist.isEmpty()) return
        val pos = e.outputToSource(ms.coerceIn(0, e.outputDurationMs()))
        player.seekTo(pos.segmentIndex, pos.offsetMs)
        _outputPosition.value = ms.coerceIn(0, e.outputDurationMs())
    }

    /** Stops playback and frees the decoder and GL pipeline. The controller can't be reused. */
    fun release() {
        scope.cancel()
        player.release()
    }

    private fun currentSourceMs(): Long {
        val i = player.currentMediaItemIndex.coerceIn(0, (playlist.size - 1).coerceAtLeast(0))
        return (playlist.getOrNull(i)?.startMs ?: 0L) + player.currentPosition.coerceAtLeast(0)
    }

    private fun currentOutputMs(e: VideoEdit): Long {
        if (playlist.isEmpty()) return _outputPosition.value
        val i = player.currentMediaItemIndex.coerceIn(0, playlist.size - 1)
        val before = playlist.take(i).sumOf { it.durationMs }
        return ((before + player.currentPosition.coerceAtLeast(0)) / e.speed.toDouble()).roundToLong()
    }

    private fun publishPosition() {
        val e = edit ?: return
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
            if (!player.isPlaying && player.playbackState == Player.STATE_READY) player.seekTo(player.currentMediaItemIndex, player.currentPosition)
        }
    }
}

/** A gain the audio sink applies live (the preview's volume, up to 200 %). Never reports unity, so it stays active. */
@OptIn(UnstableApi::class)
internal class LiveGain : GainProcessor.GainProvider {
    @Volatile var gain: Float = 1f
    override fun getGainFactorAtSamplePosition(samplePosition: Long, sampleRate: Int): Float = gain
    override fun isUnityUntil(samplePosition: Long, sampleRate: Int): Long = C.TIME_UNSET
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
 * Remembers a [VideoPreviewController] for this composition (using the app's shared OkHttp client
 * for remote sources) and releases it on dispose. Null in previews/screenshots.
 */
@Composable
fun rememberVideoPreviewController(): VideoPreviewController? {
    if (LocalInspectionMode.current) return null
    val context = LocalContext.current
    val client = LocalMediaHttpClient.current
    val controller = remember { VideoPreviewController(context, client) }
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
