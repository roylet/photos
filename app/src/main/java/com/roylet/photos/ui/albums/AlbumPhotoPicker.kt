package com.roylet.photos.ui.albums

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.roylet.photos.domain.timeline.TimelineRepository
import com.roylet.photos.ui.components.BrutalButton
import com.roylet.photos.ui.components.BrutalSpinner
import com.roylet.photos.ui.components.ButtonStyle
import com.roylet.photos.ui.components.EmptyState
import com.roylet.photos.ui.components.ScreenHeader
import com.roylet.photos.ui.gallery.GridSelection
import com.roylet.photos.ui.gallery.TimelineGrid
import com.roylet.photos.ui.theme.Palette
import com.roylet.photos.util.Format

/**
 * Full-screen photo picker shown right after naming a new album: the timeline grid in pick mode
 * (tap or long-press-drag to choose), "ADD (n)" at the bottom, and "Skip" to create it empty.
 * Back cancels without creating anything.
 */
@Composable
fun AlbumPhotoPicker(
    albumName: String,
    timeline: TimelineRepository,
    onAdd: (ids: List<String>) -> Unit,
    onSkip: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    selection: GridSelection = remember { GridSelection(picking = true) },
) {
    BackHandler { if (selection.active) selection.clear() else onBack() }
    val snapshot by timeline.snapshot.collectAsState()
    val status by timeline.status.collectAsState()
    val count = selection.ids.size
    Column(modifier.fillMaxSize().background(Palette.paper)) {
        ScreenHeader(
            "Add photos",
            subtitle = if (count == 0) "to “$albumName” · tap to choose" else "${Format.plural(count, "photo")} for “$albumName”",
            onBack = onBack,
            actions = { BrutalButton("Skip", onSkip, style = ButtonStyle.Ghost) },
        )
        Box(Modifier.weight(1f).testTag("album-picker-grid")) {
            when {
                snapshot.buckets.isNotEmpty() -> TimelineGrid(
                    timeline = timeline,
                    onOpen = { _, _ -> },
                    selection = selection,
                    contentPadding = PaddingValues(bottom = 16.dp),
                )
                status is TimelineRepository.Status.Failed -> EmptyState(
                    Icons.Rounded.CloudOff, "Couldn't load your photos", (status as TimelineRepository.Status.Failed).message,
                ) { BrutalButton("Try again", { timeline.refresh() }) }
                status == TimelineRepository.Status.Ready -> EmptyState(
                    Icons.Rounded.PhotoLibrary, "No photos yet", "Skip for now; you can add photos to the album later.",
                )
                else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { BrutalSpinner(size = 32.dp) }
            }
        }
        val rule = Palette.border
        Box(
            Modifier
                .fillMaxWidth()
                .background(Palette.paper)
                .drawBehind { drawLine(rule, Offset(0f, 0f), Offset(size.width, 0f), 2.dp.toPx()) }
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        ) {
            BrutalButton(
                if (count == 0) "Add" else "Add ($count)",
                { onAdd(selection.ids.toList()) },
                modifier = Modifier.fillMaxWidth(),
                icon = Icons.Rounded.Add,
                enabled = count > 0,
                large = true,
            )
        }
    }
}
