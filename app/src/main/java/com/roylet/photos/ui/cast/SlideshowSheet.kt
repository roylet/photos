package com.roylet.photos.ui.cast

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.roylet.photos.cast.SlideshowRunner
import com.roylet.photos.ui.components.BrutalButton
import com.roylet.photos.ui.components.BrutalShape
import com.roylet.photos.ui.components.BrutalSheet
import com.roylet.photos.ui.components.Chip
import com.roylet.photos.ui.components.IconBadge
import com.roylet.photos.ui.components.ToggleRow
import com.roylet.photos.ui.theme.Palette
import com.roylet.photos.ui.theme.Type

/**
 * Slideshow options: 3 / 5 / 10 seconds and shuffle. [tvName] non-null starts it on the TV
 * ("Slideshow on TV"); otherwise it plays on the phone.
 */
@Composable
fun SlideshowSheet(
    visible: Boolean,
    tvName: String?,
    initialIntervalS: Int,
    initialShuffle: Boolean,
    onStart: (intervalS: Int, shuffle: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    BrutalSheet(visible, onDismiss) {
        var interval by remember(visible) { mutableIntStateOf(initialIntervalS.takeIf { it in SlideshowRunner.INTERVALS_S } ?: 5) }
        var shuffle by remember(visible) { mutableStateOf(initialShuffle) }
        Column(Modifier.fillMaxWidth().testTag("slideshow-sheet")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(if (tvName != null) Icons.Rounded.CastConnected else Icons.Rounded.Slideshow, fill = Palette.peach, size = 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (tvName != null) "Slideshow on TV" else "Slideshow", style = Type.Headline, color = Palette.ink)
                    Text(
                        if (tvName != null) "On $tvName, from this photo. Videos play to the end." else "Full screen on this phone, from this photo. Tap to stop.",
                        style = Type.Small, color = Palette.mutedInk,
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Text("EVERY", style = Type.Label, color = Palette.mutedInk)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SlideshowRunner.INTERVALS_S.forEach { s ->
                    Chip("$s seconds", selected = interval == s, onClick = { interval = s }, modifier = Modifier.testTag("slideshow-$s"))
                }
            }
            Spacer(Modifier.height(10.dp))
            ToggleRow("Shuffle", "In random order instead of by date", shuffle, { shuffle = it })
            Spacer(Modifier.height(14.dp))
            BrutalButton(
                if (tvName != null) "Start on TV" else "Start slideshow",
                { onStart(interval, shuffle) },
                modifier = Modifier.fillMaxWidth().testTag("slideshow-start"),
                icon = if (tvName != null) Icons.Rounded.Cast else Icons.Rounded.Slideshow,
                large = true,
            )
        }
    }
}

/** One row of the viewer's ⋯ sheet. */
class MoreAction(val icon: ImageVector, val label: String, val detail: String?, val onClick: () -> Unit)

/** The viewer's ⋯: slideshow (on the TV while casting), stop casting. */
@Composable
fun ViewerMoreSheet(visible: Boolean, actions: List<MoreAction>, onDismiss: () -> Unit) {
    BrutalSheet(visible, onDismiss) {
        Column(Modifier.fillMaxWidth().testTag("viewer-more"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            actions.forEach { a ->
                Row(
                    Modifier.fillMaxWidth().clip(BrutalShape).clickable { a.onClick() }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconBadge(a.icon, fill = Palette.card, size = 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.label, style = Type.Title, color = Palette.ink)
                        if (a.detail != null) Text(a.detail, style = Type.Small, color = Palette.mutedInk)
                    }
                }
            }
        }
    }
}

/** The ⋯ row that stops casting. */
fun stopCastingAction(tvName: String, onStop: () -> Unit) = MoreAction(Icons.Rounded.StopCircle, "Stop casting", "Disconnect from $tvName", onStop)
