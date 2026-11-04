package com.roylet.photos.ui.viewer

import android.graphics.Color as AndroidColor
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Stable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.roylet.photos.ui.components.DarkIconButton
import com.roylet.photos.ui.components.brutalDark
import com.roylet.photos.ui.gallery.LocalMediaHttpClient
import com.roylet.photos.ui.gallery.clock
import com.roylet.photos.ui.theme.BrutalDark
import com.roylet.photos.ui.theme.Type
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient

/**
 * An ExoPlayer for [url] that streams through the app's shared OkHttp client (so the API key
 * header and LAN/public switching apply), released when it leaves composition. Null in previews.
 */
@OptIn(UnstableApi::class)
@Composable
fun rememberPlayer(url: String?, autoPlay: Boolean = true, loop: Boolean = false): ExoPlayer? {
    if (url == null || LocalInspectionMode.current) return null
    val context = LocalContext.current
    val client = LocalMediaHttpClient.current ?: remember { OkHttpClient() }
    val player = remember(url) {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(client)))
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(url))
                repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                playWhenReady = autoPlay
                prepare()
            }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    return player
}

/** The video picture, letterboxed; transparent until the first frame so a poster underneath shows through. */
@OptIn(UnstableApi::class)
@Composable
fun VideoSurface(player: Player, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                setShutterBackgroundColor(AndroidColor.TRANSPARENT)
                setBackgroundColor(AndroidColor.TRANSPARENT)
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                this.player = player
            }
        },
        update = { it.player = player },
        onRelease = { it.player = null },
        modifier = modifier,
    )
}

/** Snapshot of a player's position for the control bar, polled while visible. */
class PlaybackState {
    var playing by mutableStateOf(false)
    var position by mutableLongStateOf(0L)
    var duration by mutableLongStateOf(0L)
    var muted by mutableStateOf(false)
}

@Composable
fun rememberPlaybackState(player: Player?, fallbackDurationMs: Long = 0): PlaybackState {
    val state = remember(player) { PlaybackState().apply { duration = fallbackDurationMs } }
    if (player != null) {
        DisposableEffect(player) {
            val l = object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) { state.playing = isPlaying }
            }
            player.addListener(l)
            onDispose { player.removeListener(l) }
        }
        LaunchedEffect(player) {
            while (true) {
                state.position = player.currentPosition.coerceAtLeast(0)
                if (player.duration > 0) state.duration = player.duration
                state.muted = player.volume == 0f
                delay(200)
            }
        }
    }
    return state
}

/**
 * Scrubbing on [player]: while [fraction] is non-null the user is dragging the bar. The player is
 * paused and put in Media3's scrubbing mode (which coalesces the stream of seeks and decodes
 * just enough to show each frame), then restored on release, resuming only if it was playing.
 */
@Stable
class ScrubController {
    /** Where the finger is on the bar (0..1) while scrubbing; null otherwise. Drives the thumb directly. */
    var fraction by mutableStateOf<Float?>(null)
        private set
    private var resume = false
    private var lastSeekMs = -1L
    private var lastTick = 0

    val scrubbing get() = fraction != null

    @OptIn(UnstableApi::class)
    fun start(f: Float, player: Player?, durationMs: Long) {
        fraction = f.coerceIn(0f, 1f)
        lastTick = tickIndex(scrubPositionMs(f, durationMs), durationMs)
        if (player != null) {
            resume = player.playWhenReady && player.playbackState != Player.STATE_ENDED
            player.pause()
            (player as? ExoPlayer)?.isScrubbingModeEnabled = true
        }
        lastSeekMs = -1
        seek(player, durationMs)
    }

    /** Moves the scrub point; returns true when it crossed a tick (10% or a chapter step) for a haptic. */
    fun move(f: Float, player: Player?, durationMs: Long): Boolean {
        if (fraction == null) return false
        fraction = f.coerceIn(0f, 1f)
        seek(player, durationMs)
        val tick = tickIndex(scrubPositionMs(fraction!!, durationMs), durationMs)
        if (tick == lastTick) return false
        lastTick = tick
        return true
    }

    /** Ends scrubbing on the last spot; returns that position. */
    @OptIn(UnstableApi::class)
    fun end(player: Player?, durationMs: Long): Long? {
        val f = fraction ?: return null
        val ms = scrubPositionMs(f, durationMs)
        if (player != null) {
            // The final, exact seek; then back to normal playback.
            player.seekTo(ms)
            (player as? ExoPlayer)?.isScrubbingModeEnabled = false
            if (resume) player.play()
        }
        fraction = null
        resume = false
        return ms
    }

    private fun seek(player: Player?, durationMs: Long) {
        val ms = scrubPositionMs(fraction ?: return, durationMs)
        if (player == null || ms == lastSeekMs) return
        lastSeekMs = ms
        player.seekTo(ms)
    }
}

/**
 * Brutal minimal video controls: play/pause, elapsed / total, a chunky seek bar, mute. Touching
 * the bar anywhere scrubs: the video frame follows the finger, and a preview bubble with the
 * nearest [frames] thumbnail and the time rides above a strip of frames.
 */
@Composable
fun VideoControls(
    player: Player?,
    playback: PlaybackState,
    modifier: Modifier = Modifier,
    frames: VideoFrameStrip? = null,
    aspect: Float = 16f / 9f,
    scrub: ScrubController = remember { ScrubController() },
    onBarTouched: () -> Unit = {},
) {
    val haptics = LocalHapticFeedback.current
    val holder = remember { CoordsHolder() }
    val thumbPx = with(LocalDensity.current) { ThumbWidth.toPx() }
    val duration = playback.duration
    val latestDuration by rememberUpdatedState(duration)
    // Let go of the player if the controls vanish mid-scrub (page swiped, chrome hidden).
    DisposableEffect(player, scrub) { onDispose { scrub.end(player, latestDuration)?.let { playback.position = it } } }
    Column(modifier.onPlaced { holder.column = it }) {
        ScrubPreview(
            fraction = scrub.fraction,
            anchorX = {
                val col = holder.column
                val bar = holder.bar
                if (col == null || bar == null || !col.isAttached || !bar.isAttached) 0f
                else col.localPositionOf(bar, Offset.Zero).x + thumbPx / 2 + (bar.size.width - thumbPx) * (scrub.fraction ?: 0f)
            },
            durationMs = duration,
            strip = frames,
            aspect = aspect,
        )
        Row(
            Modifier.fillMaxWidth().brutalDark(shadow = 3.dp).padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DarkIconButton(
                if (playback.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                if (playback.playing) "Pause" else "Play",
                onClick = {
                    player ?: return@DarkIconButton
                    if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                    if (player.isPlaying) player.pause() else player.play()
                },
                active = !playback.playing,
            )
            Spacer(Modifier.width(10.dp))
            val position = scrub.fraction?.let { scrubPositionMs(it, duration) } ?: playback.position
            Text(clock(position), style = Type.Label, color = if (scrub.scrubbing) BrutalDark.Orange else BrutalDark.Text)
            Spacer(Modifier.width(10.dp))
            SeekBar(
                fraction = scrub.fraction ?: if (duration > 0) playback.position.toFloat() / duration else 0f,
                onStart = { f -> onBarTouched(); scrub.start(f, player, duration) },
                onMove = { f -> if (scrub.move(f, player, duration)) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick) },
                onEnd = { scrub.end(player, duration)?.let { playback.position = it } },
                active = scrub.scrubbing,
                modifier = Modifier.weight(1f).onPlaced { holder.bar = it }.testTag("video-seekbar"),
            )
            Spacer(Modifier.width(10.dp))
            Text(clock(duration), style = Type.Label, color = BrutalDark.MutedText)
            Spacer(Modifier.width(6.dp))
            DarkIconButton(
                if (playback.muted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                if (playback.muted) "Unmute" else "Mute",
                onClick = { player?.let { it.volume = if (it.volume == 0f) 1f else 0f; playback.muted = it.volume == 0f } },
            )
        }
    }
}

/** Layout coordinates read at layout time (not state: they change without recomposing). */
private class CoordsHolder {
    var column: LayoutCoordinates? = null
    var bar: LayoutCoordinates? = null
}

private val ThumbWidth = 14.dp

/**
 * The bar. A touch anywhere on it starts scrubbing at once (no slop, so the pager and the
 * drag-to-dismiss never get the gesture); the thumb follows the finger, not the player.
 */
@Composable
private fun SeekBar(
    fraction: Float,
    onStart: (Float) -> Unit,
    onMove: (Float) -> Unit,
    onEnd: () -> Unit,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val start by rememberUpdatedState(onStart)
    val move by rememberUpdatedState(onMove)
    val end by rememberUpdatedState(onEnd)
    val shown = fraction.coerceIn(0f, 1f)
    val shape = RoundedCornerShape(3.dp)
    BoxWithConstraints(
        modifier.height(36.dp).pointerInput(Unit) {
            fun at(x: Float) = (x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                start(at(down.position.x))
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) { change.consume(); break }
                        if (change.positionChanged()) {
                            change.consume()
                            move(at(change.position.x))
                        }
                    }
                } finally {
                    end()
                }
            }
        },
        contentAlignment = Alignment.CenterStart,
    ) {
        val w = maxWidth
        val trackHeight = if (active) 16.dp else 12.dp
        Box(Modifier.fillMaxWidth().height(trackHeight).background(BrutalDark.Raised, shape).border(2.dp, BrutalDark.Line, shape)) {
            Box(Modifier.fillMaxHeight().width(w * shown).background(BrutalDark.Orange, shape).border(2.dp, BrutalDark.Line, shape))
        }
        Box(
            Modifier.offset { IntOffset(((w - ThumbWidth) * shown).roundToPx(), 0) }
                .size(ThumbWidth, if (active) 30.dp else 24.dp)
                .background(BrutalDark.Text, shape)
                .border(2.dp, BrutalDark.Shadow, shape),
        )
    }
}
