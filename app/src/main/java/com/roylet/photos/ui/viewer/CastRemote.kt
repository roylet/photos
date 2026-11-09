package com.roylet.photos.ui.viewer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.roylet.photos.cast.CastState
import com.roylet.photos.domain.timeline.TimelineAsset
import com.roylet.photos.ui.components.BrutalSpinner
import com.roylet.photos.ui.components.DarkButton
import com.roylet.photos.ui.components.DarkIconButton
import com.roylet.photos.ui.components.brutalDark
import com.roylet.photos.ui.components.reducedMotion
import com.roylet.photos.ui.gallery.AssetImage
import com.roylet.photos.ui.gallery.MediaUrls
import com.roylet.photos.ui.gallery.clock
import com.roylet.photos.ui.gallery.sharedAsset
import com.roylet.photos.ui.theme.Brutal
import com.roylet.photos.ui.theme.BrutalDark
import com.roylet.photos.ui.theme.Type

/** What the remote panel can make the TV do. */
internal class CastRemoteActions(
    val togglePlay: () -> Unit,
    val seekTo: (Long) -> Unit,
    val setVolume: (Float) -> Unit,
    val stop: () -> Unit,
)

/**
 * A video page while casting: the poster stays on the phone (the local player never starts) and
 * the controls drive the TV: play/pause, the same chunky seek bar as local playback, volume and
 * STOP CASTING.
 */
@Composable
internal fun CastVideoPage(
    asset: TimelineAsset,
    urls: MediaUrls,
    state: CastState,
    isCurrent: Boolean,
    chrome: Boolean,
    actions: CastRemoteActions,
    onTap: () -> Unit,
) {
    BoxWithConstraints(
        Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null, onClick = onTap).testTag("cast-remote-${asset.id}"),
        contentAlignment = Alignment.Center,
    ) {
        val px = LocalDensity.current
        val fitted = fitPx(asset.ratio, constraints.maxWidth, constraints.maxHeight)
        Box(Modifier.size(with(px) { fitted.width.toDp() }, with(px) { fitted.height.toDp() }).sharedAsset(asset.id, inViewer = true).graphicsLayer { alpha = 0.45f }) {
            AssetImage(urls.preview(asset.id), asset.thumbhash, fitted.width, fitted.height, Modifier.fillMaxSize())
        }
        val onTv = state.current?.asset?.id == asset.id
        val playing = onTv && state.playback.playing
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.brutalDark(if (onTv) BrutalDark.Orange else BrutalDark.Panel, shape = RoundedCornerShape(50), shadow = 4.dp).size(76.dp)
                    .clickable(enabled = onTv && isCurrent) { actions.togglePlay() },
                contentAlignment = Alignment.Center,
            ) {
                when {
                    !onTv || state.playback.buffering -> BrutalSpinner(size = 30.dp)
                    else -> Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause on TV" else "Play on TV", tint = Brutal.Ink, modifier = Modifier.size(40.dp))
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.brutalDark(BrutalDark.Panel, shape = RoundedCornerShape(50), shadow = 3.dp).padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.CastConnected, null, tint = BrutalDark.Orange, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (onTv) "Playing on ${state.target?.name ?: "TV"}" else "Sending to ${state.target?.name ?: "TV"}…",
                    style = Type.Label, color = BrutalDark.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                )
            }
        }
        // The remote stays up with the chrome hidden too (it's the only way to drive the TV here);
        // it just drops into the space the action bar and filmstrip leave.
        val reduced = reducedMotion()
        val bottom by animateDpAsState(if (chrome) 150.dp else 24.dp, if (reduced) snap() else tween(200), label = "remote")
        AnimatedVisibility(isCurrent, Modifier.align(Alignment.BottomCenter), enter = fadeIn(), exit = fadeOut()) {
            CastRemoteControls(
                state, onTv, actions,
                Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(start = 12.dp, end = 12.dp, bottom = bottom).fillMaxWidth(),
                fallbackDurationMs = asset.durationMs ?: 0,
            )
        }
    }
}

@Composable
internal fun CastRemoteControls(state: CastState, onTv: Boolean, actions: CastRemoteActions, modifier: Modifier = Modifier, fallbackDurationMs: Long = 0) {
    val duration = state.playback.durationMs.takeIf { it > 0 } ?: fallbackDurationMs
    var scrub by remember { mutableStateOf<Float?>(null) }
    var volume by remember { mutableStateOf<Float?>(null) }
    Column(modifier.testTag("cast-remote-controls"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth().brutalDark(shadow = 3.dp).padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val playing = onTv && state.playback.playing
            DarkIconButton(
                if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                if (playing) "Pause on TV" else "Play on TV",
                onClick = actions.togglePlay,
                active = !playing,
                enabled = onTv,
            )
            Spacer(Modifier.width(10.dp))
            val position = scrub?.let { scrubPositionMs(it, duration) } ?: if (onTv) state.playback.positionMs else 0
            Text(clock(position), style = Type.Label, color = if (scrub != null) BrutalDark.Orange else BrutalDark.Text)
            Spacer(Modifier.width(10.dp))
            SeekBar(
                fraction = scrub ?: if (duration > 0 && onTv) state.playback.positionMs.toFloat() / duration else 0f,
                onStart = { f -> if (onTv) scrub = f },
                onMove = { f -> if (scrub != null) scrub = f },
                onEnd = { scrub?.let { actions.seekTo(scrubPositionMs(it, duration)) }; scrub = null },
                active = scrub != null,
                modifier = Modifier.weight(1f).testTag("cast-seekbar"),
            )
            Spacer(Modifier.width(10.dp))
            Text(clock(duration), style = Type.Label, color = BrutalDark.MutedText)
        }
        Row(
            Modifier.fillMaxWidth().brutalDark(shadow = 3.dp).padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val v = volume ?: state.playback.volume
            Icon(if (v < 0.5f) Icons.AutoMirrored.Rounded.VolumeDown else Icons.AutoMirrored.Rounded.VolumeUp, "TV volume", tint = BrutalDark.Text, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            SeekBar(
                fraction = v,
                onStart = { f -> volume = f; actions.setVolume(f) },
                onMove = { f -> if (volume?.let { kotlin.math.abs(it - f) > 0.02f } != false) { volume = f; actions.setVolume(f) } },
                onEnd = { volume?.let(actions.setVolume); volume = null },
                active = volume != null,
                modifier = Modifier.weight(1f).testTag("cast-volume"),
            )
            Spacer(Modifier.width(12.dp))
            DarkButton("Stop casting", actions.stop, primary = false)
        }
    }
}
