package com.roylet.photos.ui.gallery

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.roylet.photos.domain.timeline.TimelineAsset
import com.roylet.photos.domain.timeline.TimelineRepository
import com.roylet.photos.ui.components.BrutalButton
import com.roylet.photos.ui.components.BrutalSpinner
import com.roylet.photos.ui.components.ButtonStyle
import com.roylet.photos.ui.components.EmptyState
import com.roylet.photos.ui.components.ScreenHeader
import com.roylet.photos.ui.components.ScrollToTopOnReselect
import com.roylet.photos.ui.components.SquareIconButton
import com.roylet.photos.ui.theme.Brutal
import com.roylet.photos.ui.theme.Palette

/**
 * A full timeline screen: header (or the selection bar while selecting) over a [TimelineGrid].
 * The main "Photos" tab uses it as-is; Favourites, Archive, Trash, an album or a person can reuse
 * it with their own [timeline], [title] and [onBack].
 *
 * @param headerActions trailing header content, e.g. the ConnectionChip and a settings button.
 * @param selectionActions the selection bar's buttons; defaults to [defaultSelectionActions].
 * @param banner shown between the header and the grid (e.g. the phone's "Limited access" banner).
 * @param emptyContent replaces the default empty state once the timeline is loaded and empty.
 */
@Composable
fun TimelineScreen(
    timeline: TimelineRepository,
    title: String,
    onOpen: (index: Int, asset: TimelineAsset) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    selection: GridSelection = remember { GridSelection() },
    revealIndex: Int? = null,
    onRevealed: () -> Unit = {},
    emptyTitle: String = "No photos yet",
    emptyText: String = "Photos and videos you upload to your server show up here, newest first.",
    headerActions: @Composable RowScope.() -> Unit = {},
    selectionActions: @Composable (SelectionScope) -> List<SelectionAction> = { defaultSelectionActions(it) },
    topContent: (@Composable () -> Unit)? = null,
    banner: (@Composable () -> Unit)? = null,
    emptyContent: (@Composable () -> Unit)? = null,
) {
    val status by timeline.status.collectAsState()
    val snapshot by timeline.snapshot.collectAsState()
    val listState = rememberLazyListState()
    BackHandler(enabled = selection.active) { selection.clear() }
    // On a tab's root (Photos): re-tapping the tab closes the selection (bringing the header back) and scrolls to the top.
    ScrollToTopOnReselect(listState) { selection.clear() }
    // Re-fetch if something elsewhere changed this timeline (e.g. photos were added to this album).
    androidx.compose.runtime.LaunchedEffect(timeline) { timeline.startOrRefresh() }

    Column(modifier.fillMaxSize().background(Palette.paper)) {
        AnimatedContent(
            targetState = selection.active,
            transitionSpec = { fadeIn(tween(140)) togetherWith fadeOut(tween(100)) },
            label = "header",
        ) { selecting ->
            if (selecting) {
                val scope = SelectionScope(selection.ids.toList(), timeline, selection::clear)
                SelectionTopBar(selection.ids.size, onClose = selection::clear, actions = selectionActions(scope))
            } else {
                ScreenHeader(title, subtitle = subtitle, onBack = onBack, actions = headerActions)
            }
        }
        if (!selection.active) banner?.invoke()
        Box(Modifier.weight(1f)) {
            when {
                snapshot.buckets.isNotEmpty() -> TimelineGrid(
                    timeline = timeline,
                    onOpen = onOpen,
                    selection = selection,
                    state = listState,
                    revealIndex = revealIndex,
                    onRevealed = onRevealed,
                    topContent = topContent,
                )
                status is TimelineRepository.Status.Failed -> EmptyState(
                    Icons.Rounded.CloudOff,
                    "Couldn't load your photos",
                    (status as TimelineRepository.Status.Failed).message,
                ) { BrutalButton("Try again", { timeline.refresh() }, style = ButtonStyle.Primary) }
                status == TimelineRepository.Status.Ready ->
                    if (emptyContent != null) emptyContent() else EmptyState(Icons.Rounded.PhotoLibrary, emptyTitle, emptyText)
                else -> {
                    // First load: a quick spinner over the paper (months appear as soon as the list arrives).
                    androidx.compose.runtime.LaunchedEffect(timeline) { timeline.start() }
                    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { BrutalSpinner(size = 32.dp) }
                }
            }
        }
    }
}

/** The "Photos" tab: the main timeline, with a slot for the connection chip and a settings button. */
@Composable
fun PhotosScreen(
    timeline: TimelineRepository,
    onOpen: (index: Int, asset: TimelineAsset) -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    revealIndex: Int? = null,
    onRevealed: () -> Unit = {},
    selection: GridSelection = remember { GridSelection() },
    connectionChip: @Composable () -> Unit = {},
    /** The cast button, shown only while cast devices are around (AppNav decides). */
    castButton: (@Composable () -> Unit)? = null,
    selectionActions: @Composable (SelectionScope) -> List<SelectionAction> = { defaultSelectionActions(it) },
    topContent: (@Composable () -> Unit)? = null,
    banner: (@Composable () -> Unit)? = null,
    /** Replaces the timeline entirely (the phone's "no access" state). */
    blocked: (@Composable () -> Unit)? = null,
    emptyContent: (@Composable () -> Unit)? = null,
) {
    if (blocked != null) {
        Column(modifier.fillMaxSize().background(Palette.paper)) {
            ScreenHeader("Photos", actions = {
                // [connection chip] [cast] [settings]
                connectionChip()
                castButton?.invoke()
                SquareIconButton(Icons.Rounded.Settings, "Settings", onSettings)
            })
            Box(Modifier.weight(1f)) { blocked() }
        }
        return
    }
    TimelineScreen(
        timeline = timeline,
        title = "Photos",
        onOpen = onOpen,
        modifier = modifier,
        selection = selection,
        revealIndex = revealIndex,
        onRevealed = onRevealed,
        headerActions = {
            // [connection chip] [cast] [settings]
            connectionChip()
            castButton?.invoke()
            SquareIconButton(Icons.Rounded.Settings, "Settings", onSettings)
        },
        selectionActions = selectionActions,
        topContent = topContent,
        banner = banner,
        emptyContent = emptyContent,
    )
}
