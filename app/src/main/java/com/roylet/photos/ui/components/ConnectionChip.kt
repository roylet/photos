package com.roylet.photos.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.roylet.photos.net.ConnState
import com.roylet.photos.ui.theme.Brutal
import com.roylet.photos.ui.theme.Type

private val ChipShape = RoundedCornerShape(50)

private data class ChipLook(val label: String, val fill: Color, val ink: Color, val description: String)

private fun look(state: ConnState) = when (state) {
    ConnState.Home -> ChipLook("HOME", Brutal.Mint, Brutal.Ink, "Connected on your home network")
    ConnState.Remote -> ChipLook("REMOTE", Brutal.Blue, Brutal.Card, "Connected over the internet")
    ConnState.Offline -> ChipLook("OFFLINE", Brutal.Red, Brutal.Card, "Can't reach your server")
    ConnState.Checking -> ChipLook("CHECKING", Brutal.Card, Brutal.Ink, "Checking connection")
}

/**
 * Small brutal pill showing how the app reaches the server: HOME (mint, bolt), REMOTE (blue,
 * globe), OFFLINE (red) or CHECKING (spinner). The fill crossfades and the label rolls vertically
 * when the state changes. Pass [onClick] to make it a button (e.g. open Settings).
 *
 * Usage: `val state by container.connection.state.collectAsState(); ConnectionChip(state)`
 */
@Composable
fun ConnectionChip(state: ConnState, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val target = look(state)
    val fill by animateColorAsState(target.fill, tween(220), label = "chipFill")
    val ink by animateColorAsState(target.ink, tween(220), label = "chipInk")
    Box(
        modifier
            .brutal(fill = fill, shape = ChipShape, shadow = 2.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics { contentDescription = target.description },
    ) {
        AnimatedContent(
            targetState = state,
            transitionSpec = {
                val up = targetState.ordinal > initialState.ordinal
                (slideInVertically(spring(dampingRatio = Spring.DampingRatioMediumBouncy)) { if (up) it else -it } + fadeIn(tween(150)))
                    .togetherWith(slideOutVertically(tween(150)) { if (up) -it else it } + fadeOut(tween(100)))
                    .using(SizeTransform(clip = true))
            },
            contentAlignment = Alignment.Center,
            label = "chipState",
        ) { s ->
            Row(
                Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                when (s) {
                    ConnState.Home -> Icon(Icons.Rounded.Bolt, null, tint = ink, modifier = Modifier.size(14.dp))
                    ConnState.Remote -> Icon(Icons.Rounded.Public, null, tint = ink, modifier = Modifier.size(14.dp))
                    ConnState.Offline -> Icon(Icons.Rounded.CloudOff, null, tint = ink, modifier = Modifier.size(14.dp))
                    ConnState.Checking -> BrutalSpinner(size = 12.dp)
                }
                Text(look(s).label, style = Type.Tiny, color = ink, maxLines = 1)
            }
        }
    }
}
