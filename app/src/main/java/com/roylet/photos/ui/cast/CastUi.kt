package com.roylet.photos.ui.cast

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.cast.MediaRouteButton
import com.roylet.photos.cast.CastController
import com.roylet.photos.cast.CastState
import com.roylet.photos.ui.components.BrutalSpinner
import com.roylet.photos.ui.components.Pressable
import com.roylet.photos.ui.components.brutal
import com.roylet.photos.ui.components.brutalDark
import com.roylet.photos.ui.gallery.AssetImage
import com.roylet.photos.ui.gallery.LocalMediaUrls
import com.roylet.photos.ui.theme.Brutal
import com.roylet.photos.ui.theme.BrutalDark
import com.roylet.photos.ui.theme.Palette
import com.roylet.photos.ui.theme.Type

/** The app's cast controller (AppNav provides `container.cast`); null in previews and isolated screens. */
val LocalCastController = staticCompositionLocalOf<CastController?> { null }

/** The cast state, or an idle one without a controller. */
@Composable
fun rememberCastState(): CastState {
    val controller = LocalCastController.current ?: return remember { CastState() }
    val state by controller.state.collectAsState()
    return state
}

/**
 * The cast button in our frame: Media3's Compose MediaRouteButton (device picker, the
 * connected-device dialog, the system Output Switcher where the platform has one), drawn as one
 * of our square buttons, orange while casting. [dark] is the viewer's ink kit; otherwise the
 * theme's. Previews and screenshots (no Cast SDK) draw the same frame with a static icon.
 */
@Composable
fun CastButton(dark: Boolean, modifier: Modifier = Modifier) {
    val state = rememberCastState()
    val connected = state.casting
    val fill = when {
        connected -> Brutal.Orange
        dark -> BrutalDark.Panel
        else -> Palette.card
    }
    val ink = when {
        connected -> Brutal.Ink
        dark -> BrutalDark.Text
        else -> Palette.ink
    }
    val frame = if (dark) Modifier.brutalDark(fill, shadow = 3.dp) else Modifier.brutal(fill, shadow = 3.dp)
    val side = if (dark) 44.dp else 40.dp
    Box(modifier.then(frame).size(side).testTag("cast-button"), contentAlignment = Alignment.Center) {
        if (LocalInspectionMode.current || !state.supported) {
            Icon(
                if (connected) Icons.Rounded.CastConnected else Icons.Rounded.Cast,
                if (connected) "Casting" else "Cast",
                tint = ink, modifier = Modifier.size(if (dark) 22.dp else 20.dp),
            )
        } else {
            CompositionLocalProvider(LocalContentColor provides ink, LocalMinimumInteractiveComponentSize provides 0.dp) {
                MediaRouteButton(Modifier.size(side))
            }
        }
    }
}

/**
 * "Casting to <TV>" under the viewer's top bar: what's happening on the TV (sending, an error in
 * words, a one-time note) and STOP.
 */
@Composable
fun CastingBanner(state: CastState, onStop: () -> Unit, onDismissNote: () -> Unit, modifier: Modifier = Modifier) {
    val target = state.target ?: return
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).testTag("casting-banner")) {
        Row(
            Modifier.fillMaxWidth().brutalDark(BrutalDark.Orange, shape = RoundedCornerShape(50), shadow = 3.dp)
                .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.CastConnected, null, tint = Brutal.Ink, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("CASTING TO", style = Type.Tiny, color = Brutal.Ink.copy(alpha = 0.7f))
                Text(target.name, style = Type.Label, color = Brutal.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (state.loading) {
                BrutalSpinner(size = 18.dp)
                Spacer(Modifier.width(8.dp))
            } else if (state.slideshow != null) {
                Row(
                    Modifier.clip(RoundedCornerShape(50)).border(2.dp, Brutal.Ink, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Slideshow, "Slideshow", tint = Brutal.Ink, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("${state.slideshow.intervalMs / 1000}s", style = Type.Tiny, color = Brutal.Ink)
                }
                Spacer(Modifier.width(8.dp))
            }
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(Brutal.Ink).clickable(onClick = onStop)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("STOP", style = Type.Label, color = BrutalDark.Text)
            }
        }
        val message = state.error ?: state.notice
        AnimatedVisibility(message != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            val error = state.error != null
            Row(
                Modifier.padding(top = 8.dp).fillMaxWidth().brutalDark(if (error) Color(0xFF3D1C1F) else BrutalDark.Panel, shadow = 3.dp)
                    .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (error) Icons.Rounded.ErrorOutline else Icons.Rounded.Info, null, tint = if (error) BrutalDark.Red else BrutalDark.Text, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text((state.error ?: state.notice).orEmpty(), style = Type.Small, color = BrutalDark.Text, modifier = Modifier.weight(1f))
                Box(Modifier.size(32.dp).clip(RoundedCornerShape(50)).clickable(onClick = onDismissNote).semantics { contentDescription = "Dismiss" }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Close, null, tint = BrutalDark.MutedText, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

/**
 * Above the tab bar while casting and the viewer is closed: the item on the TV, play/pause for
 * videos, and stop.
 */
@Composable
fun CastMiniController(state: CastState, onTogglePlay: () -> Unit, onStop: () -> Unit, modifier: Modifier = Modifier, onOpen: (() -> Unit)? = null) {
    val target = state.target ?: return
    val urls = LocalMediaUrls.current
    val now = state.current
    Row(
        modifier.fillMaxWidth().background(Palette.paper).padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 4.dp)
            .brutal(Palette.card, shadow = 3.dp)
            .then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier)
            .padding(6.dp)
            .testTag("cast-mini"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val px = with(LocalDensity.current) { 44.dp.roundToPx() }
        val shape = RoundedCornerShape(4.dp)
        Box(Modifier.size(44.dp).clip(shape).border(2.dp, Palette.border, shape).background(Palette.placeholder), contentAlignment = Alignment.Center) {
            if (now != null) AssetImage(urls.thumbnail(now.asset.id), now.asset.thumbhash, px, px, Modifier.size(44.dp))
            else Icon(Icons.Rounded.CastConnected, null, tint = Palette.ink, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("CASTING TO ${target.name.uppercase()}", style = Type.Tiny, color = Palette.mutedInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val title = when {
                state.error != null -> state.error
                now != null -> now.media.title ?: if (now.media.isVideo) "Video" else "Photo"
                state.loading -> "Sending…"
                else -> "Open a photo to show it on the TV"
            }
            Text(title, style = Type.Label, color = if (state.error != null) Palette.danger else Palette.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (now?.media?.isVideo == true) {
            MiniButton(if (state.playback.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (state.playback.playing) "Pause" else "Play", onTogglePlay, accent = true)
            Spacer(Modifier.width(6.dp))
        }
        MiniButton(Icons.Rounded.Close, "Stop casting", onStop)
    }
}

@Composable
private fun MiniButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit, accent: Boolean = false) {
    Pressable(onClick, fill = if (accent) Brutal.Orange else Palette.card, shadow = 2.dp) {
        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
            Icon(icon, description, tint = if (accent) Brutal.Ink else Palette.ink, modifier = Modifier.size(20.dp))
        }
    }
}

/** For screens that list what the cast row offers (Settings): "Living room TV" or the reason it's off. */
fun castSummary(state: CastState): String = when {
    state.casting -> "Casting to ${state.target?.name}"
    !state.supported -> "Needs Google Play services"
    state.devicesAvailable -> "Cast devices found on this Wi-Fi"
    else -> "No cast devices found on this Wi-Fi"
}
