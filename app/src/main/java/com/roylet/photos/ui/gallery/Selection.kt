package com.roylet.photos.ui.gallery

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.roylet.photos.domain.timeline.TimelineRepository
import com.roylet.photos.domain.timeline.TimelineSnapshot
import com.roylet.photos.ui.components.SquareIconButton
import com.roylet.photos.ui.theme.Brutal
import com.roylet.photos.ui.theme.Palette
import com.roylet.photos.ui.theme.Type
import kotlinx.coroutines.launch
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import com.roylet.photos.domain.timeline.Visibility
import com.roylet.photos.ui.components.reducedMotion
import kotlin.math.max
import kotlin.math.min

/** Multi-select state for a grid: tap-to-toggle plus long-press-drag range selection. */
@Stable
class GridSelection {
    var ids: Set<String> by mutableStateOf(emptySet())
        private set
    val active: Boolean get() = ids.isNotEmpty()

    private var base: Set<String> = emptySet()
    private var anchor = -1
    private var adding = true

    fun toggle(id: String) { ids = if (id in ids) ids - id else ids + id }
    fun set(newIds: Set<String>) { ids = newIds }
    fun clear() { ids = emptySet(); anchor = -1 }
    fun selectAll(more: Collection<String>) { ids = ids + more }
    fun deselectAll(less: Collection<String>) { ids = ids - less.toSet() }

    /** Long-press on global index [index]: it either starts adding or (if already selected) removing. */
    fun beginDrag(index: Int, snapshot: TimelineSnapshot) {
        val id = snapshot.assetAt(index)?.id ?: return
        anchor = index
        base = ids
        adding = id !in ids
        extendTo(index, snapshot)
    }

    /** Drag moved over [index]: the range anchor..index is added to (or removed from) what was selected before the drag. */
    fun extendTo(index: Int, snapshot: TimelineSnapshot) {
        if (anchor < 0) return
        val range = (min(anchor, index)..max(anchor, index)).mapNotNullTo(HashSet()) { snapshot.assetAt(it)?.id }
        ids = if (adding) base + range else base - range
    }

    fun endDrag() { anchor = -1 }
    val dragging: Boolean get() = anchor >= 0
}

/** What a selection-actions slot gets: the chosen ids and the timeline they came from. */
class SelectionScope(val ids: List<String>, val timeline: TimelineRepository?, val clear: () -> Unit)

/** Top bar shown instead of the screen header while assets are selected. */
@Composable
fun SelectionTopBar(count: Int, onClose: () -> Unit, actions: @Composable RowScope.() -> Unit) {
    val rule = Palette.border
    Row(
        Modifier
            .fillMaxWidth()
            .background(Brutal.Orange)
            .windowInsetsPadding(WindowInsets.statusBars)
            .drawBehind { drawLine(rule, Offset(0f, size.height), Offset(size.width, size.height), 2.dp.toPx()) }
            .padding(start = 12.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SquareIconButton(Icons.Rounded.Close, "Clear selection", onClose)
        Spacer(Modifier.width(12.dp))
        AnimatedContent(
            targetState = count,
            transitionSpec = {
                val up = targetState > initialState
                (slideInVertically(tween(160)) { if (up) it else -it } + fadeIn(tween(160))) togetherWith
                    (slideOutVertically(tween(160)) { if (up) -it else it } + fadeOut(tween(120)))
            },
            label = "count",
            modifier = Modifier.weight(1f),
        ) { n ->
            Text("$n selected", style = Type.Headline, color = Brutal.Ink, maxLines = 1)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** Whether every one of [ids] is a favourite in [timeline]'s loaded months. */
@Composable
internal fun allFavorite(ids: List<String>, timeline: TimelineRepository?): Boolean {
    val snapshot = timeline?.snapshot?.collectAsState()?.value ?: return false
    return remember(ids, snapshot) {
        val want = ids.toHashSet()
        var found = 0
        var all = true
        snapshot.loaded.values.forEach { list -> list.forEach { if (it.id in want) { found++; if (!it.isFavorite) all = false } } }
        all && found > 0
    }
}

/** The selection bar's actions: favourite, add to album, share, archive and delete, wired to [LocalAssetActions]. */
@Composable
fun RowScope.DefaultSelectionActions(scope: SelectionScope, actions: AssetActions = LocalAssetActions.current) {
    val allFav = allFavorite(scope.ids, scope.timeline)
    val unarchive = scope.timeline?.filter?.visibility == Visibility.ARCHIVE
    FavoritePopButton(allFav, onClick = { actions.favorite(scope.ids, !allFav, scope.timeline) })
    SquareIconButton(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to album", { actions.addToAlbum(scope.ids, scope.timeline) })
    SquareIconButton(Icons.Rounded.Share, "Share", { actions.share(scope.ids, scope.timeline) })
    SquareIconButton(if (unarchive) Icons.Rounded.Unarchive else Icons.Rounded.Archive, if (unarchive) "Unarchive" else "Archive", {
        actions.archive(scope.ids, scope.timeline); scope.clear()
    })
    SquareIconButton(Icons.Rounded.DeleteOutline, "Delete", { actions.delete(scope.ids, scope.timeline); scope.clear() }, fill = Palette.card)
}

/** Heart button for the light selection bar: pops (overshoot + an orange burst ring) when turned on. */
@Composable
fun FavoritePopButton(favorite: Boolean, onClick: () -> Unit) {
    val reduced = reducedMotion()
    val pop = remember { Animatable(1f) }
    val ring = remember { Animatable(1f) }
    var last by remember { mutableStateOf(favorite) }
    LaunchedEffect(favorite) {
        if (favorite != last && favorite && !reduced) {
            launch { pop.snapTo(0.6f); pop.animateTo(1f, spring(dampingRatio = 0.32f, stiffness = 700f)) }
            launch { ring.snapTo(0f); ring.animateTo(1f, tween(420)) }
        }
        last = favorite
    }
    val ringColor = Palette.ink
    Box(contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(43.dp).drawBehind {
                val t = ring.value
                if (t < 1f) drawCircle(ringColor.copy(alpha = 1f - t), radius = size.minDimension * (0.4f + 0.7f * t), style = Stroke(3.dp.toPx() * (1f - t) + 1f))
            },
        )
        Box(Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value }) {
            SquareIconButton(
                if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                if (favorite) "Unfavourite" else "Favourite",
                onClick,
                fill = if (favorite) Palette.yellow else Palette.card,
            )
        }
    }
}
