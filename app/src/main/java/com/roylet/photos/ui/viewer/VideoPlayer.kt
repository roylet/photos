package com.roylet.photos.ui.viewer

import android.graphics.Color as AndroidColor
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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

/** Brutal minimal video controls: play/pause, elapsed / total, a chunky seek bar, mute. */
@Composable
fun VideoControls(player: Player?, playback: PlaybackState, modifier: Modifier = Modifier) {
    Row(
        modifier.brutalDark(shadow = 3.dp).padding(horizontal = 8.dp, vertical = 8.dp),
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
        Text(clock(playback.position), style = Type.Label, color = BrutalDark.Text)
        Spacer(Modifier.width(10.dp))
        SeekBar(
            fraction = if (playback.duration > 0) playback.position.toFloat() / playback.duration else 0f,
            onSeek = { f -> player?.seekTo((f * playback.duration).toLong()); playback.position = (f * playback.duration).toLong() },
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(10.dp))
        Text(clock(playback.duration), style = Type.Label, color = BrutalDark.MutedText)
        Spacer(Modifier.width(6.dp))
        DarkIconButton(
            if (playback.muted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
            if (playback.muted) "Unmute" else "Mute",
            onClick = { player?.let { it.volume = if (it.volume == 0f) 1f else 0f; playback.muted = it.volume == 0f } },
        )
    }
}

@Composable
private fun SeekBar(fraction: Float, onSeek: (Float) -> Unit, modifier: Modifier = Modifier) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = (dragging ?: fraction).coerceIn(0f, 1f)
    val shape = RoundedCornerShape(3.dp)
    BoxWithConstraints(
        modifier.height(36.dp)
            .pointerInput(Unit) { detectTapGestures { onSeek((it.x / size.width).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = (it.x / size.width).coerceIn(0f, 1f) },
                    onHorizontalDrag = { c, _ -> c.consume(); dragging = (c.position.x / size.width).coerceIn(0f, 1f); onSeek(dragging!!) },
                    onDragEnd = { dragging = null },
                    onDragCancel = { dragging = null },
                )
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val w = maxWidth
        Box(Modifier.fillMaxWidth().height(12.dp).background(BrutalDark.Raised, shape).border(2.dp, BrutalDark.Line, shape)) {
            Box(Modifier.fillMaxHeight().width(w * shown).background(BrutalDark.Orange, shape).border(2.dp, BrutalDark.Line, shape))
        }
        Box(
            Modifier.offset { IntOffset(((w - 14.dp) * shown).roundToPx(), 0) }
                .size(14.dp, 24.dp).background(BrutalDark.Text, RoundedCornerShape(3.dp)).border(2.dp, BrutalDark.Shadow, RoundedCornerShape(3.dp)),
        )
    }
}

