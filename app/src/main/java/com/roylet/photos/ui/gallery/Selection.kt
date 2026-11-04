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
import androidx.compose.runtime.rememberCoroutineScope
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
import com.roylet.photos.ui.theme.Type
import kotlinx.coroutines.launch
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
    Row(
        Modifier
            .fillMaxWidth()
            .background(Brutal.Orange)
            .windowInsetsPadding(WindowInsets.statusBars)
            .drawBehind { drawLine(Brutal.Ink, Offset(0f, size.height), Offset(size.width, size.height), 2.dp.toPx()) }
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

/** Stub selection actions (favourite, add to album, share, delete) wired to [LocalAssetActions]. */
@Composable
fun RowScope.DefaultSelectionActions(scope: SelectionScope, actions: AssetActions = LocalAssetActions.current) {
    val co = rememberCoroutineScope()
    SquareIconButton(Icons.Rounded.FavoriteBorder, "Favourite", {
        val ids = scope.ids
        scope.timeline?.setFavorite(ids, true)
        co.launch { runCatching { actions.setFavorite(ids, true) }.onFailure { scope.timeline?.setFavorite(ids, false) } }
    })
    SquareIconButton(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to album", { actions.addToAlbum(scope.ids, scope.timeline) })
    SquareIconButton(Icons.Rounded.Share, "Share", { actions.share(scope.ids, scope.timeline) })
    SquareIconButton(Icons.Rounded.DeleteOutline, "Delete", { actions.delete(scope.ids, scope.timeline) }, fill = Brutal.Card)
}
