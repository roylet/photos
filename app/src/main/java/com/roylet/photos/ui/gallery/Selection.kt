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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import com.roylet.photos.ui.components.BrutalShape
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

/**
 * Multi-select state for a grid: tap-to-toggle plus long-press-drag range selection.
 *
 * @param picking a photo picker: taps always toggle and the check rings show even with nothing chosen.
 */
@Stable
class GridSelection(val picking: Boolean = false) {
    var ids: Set<String> by mutableStateOf(emptySet())
        private set
    val active: Boolean get() = ids.isNotEmpty()
    /** Whether cells show their check rings and taps toggle instead of opening the viewer. */
    val selecting: Boolean get() = picking || active

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

/**
 * One button of the selection bar. When the bar is short of room, actions with [primary] = false
 * move into the ⋯ menu first (then primaries, last first), so the count keeps reading "6 selected".
 *
 * @param content a custom button (e.g. the popping heart); [icon] and [label] are still used in the menu.
 */
@Immutable
class SelectionAction(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit,
    val primary: Boolean = true,
    val fill: Color? = null,
    val content: (@Composable () -> Unit)? = null,
)

/** Which actions get a button of their own; the rest go into the overflow menu. */
internal data class SelectionBarPlan(val inline: List<Int>, val overflow: List<Int>)

/**
 * Picks the inline buttons for a bar [availablePx] wide (what's left after the close button and the
 * count text). Keeps the original order; drops secondaries from the end first, then primaries from
 * the end, and reserves one slot for the ⋯ button whenever anything is dropped.
 */
internal fun planSelectionBar(primary: List<Boolean>, availablePx: Float, buttonPx: Float, gapPx: Float): SelectionBarPlan {
    fun width(n: Int) = if (n <= 0) 0f else n * buttonPx + (n - 1) * gapPx
    val all = primary.indices.toList()
    if (width(all.size) <= availablePx) return SelectionBarPlan(all, emptyList())
    val dropOrder = all.filter { !primary[it] }.reversed() + all.filter { primary[it] }.reversed()
    val inline = all.toMutableList()
    for (i in dropOrder) {
        if (width(inline.size + 1) <= availablePx) break
        inline.remove(i)
    }
    return SelectionBarPlan(inline, all - inline.toSet())
}

private val BarButton = 43.dp // SquareIconButton: 40dp plus its 3dp shadow
private val BarGap = 2.dp

/** Top bar shown instead of the screen header while assets are selected. */
@Composable
fun SelectionTopBar(count: Int, onClose: () -> Unit, actions: List<SelectionAction>) {
    val rule = Palette.border
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .background(Brutal.Orange)
            .windowInsetsPadding(WindowInsets.statusBars)
            .drawBehind { drawLine(rule, Offset(0f, size.height), Offset(size.width, size.height), 2.dp.toPx()) }
            .padding(start = 12.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
    ) {
        val density = LocalDensity.current
        val measurer = rememberTextMeasurer()
        val label = "$count selected"
        val textPx = remember(label, measurer) { measurer.measure(label, Type.Headline, maxLines = 1).size.width.toFloat() }
        val plan = with(density) {
            val available = constraints.maxWidth - (BarButton + 12.dp + 8.dp).toPx() - textPx
            remember(actions.map { it.primary }, available) {
                planSelectionBar(actions.map { it.primary }, available, BarButton.toPx(), BarGap.toPx())
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
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
                Text("$n selected", style = Type.Headline, color = Brutal.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(BarGap), verticalAlignment = Alignment.CenterVertically) {
                plan.inline.forEach { i ->
                    val a = actions[i]
                    key(a.label) {
                        a.content?.invoke() ?: SquareIconButton(a.icon, a.label, a.onClick, fill = a.fill ?: Palette.card)
                    }
                }
                if (plan.overflow.isNotEmpty()) SelectionOverflow(plan.overflow.map { actions[it] })
            }
        }
    }
}

/** The ⋯ button and its menu of the actions that didn't fit. */
@Composable
private fun SelectionOverflow(actions: List<SelectionAction>) {
    var open by remember { mutableStateOf(false) }
    Box {
        SquareIconButton(Icons.Rounded.MoreHoriz, "More actions", { open = true })
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = BrutalShape,
            containerColor = Palette.card,
            border = BorderStroke(2.dp, Palette.border),
        ) {
            actions.forEach { a ->
                DropdownMenuItem(
                    text = { Text(a.label.uppercase(), style = Type.Label) },
                    leadingIcon = { Icon(a.icon, null) },
                    onClick = { open = false; a.onClick() },
                    colors = MenuDefaults.itemColors(textColor = Palette.ink, leadingIconColor = Palette.ink),
                )
            }
        }
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

/**
 * The selection bar's actions: favourite, add to album, share, archive and delete, wired to
 * [LocalAssetActions]. Add-to-album and archive are the first to move into the ⋯ menu.
 */
@Composable
fun defaultSelectionActions(scope: SelectionScope, actions: AssetActions = LocalAssetActions.current): List<SelectionAction> {
    val allFav = allFavorite(scope.ids, scope.timeline)
    val unarchive = scope.timeline?.filter?.visibility == Visibility.ARCHIVE
    val toggleFav = { actions.favorite(scope.ids, !allFav, scope.timeline) }
    return listOf(
        SelectionAction(
            if (allFav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (allFav) "Unfavourite" else "Favourite", toggleFav,
            content = { FavoritePopButton(allFav, onClick = toggleFav) },
        ),
        SelectionAction(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to album", { actions.addToAlbum(scope.ids, scope.timeline) }, primary = false),
        SelectionAction(Icons.Rounded.Share, "Share", { actions.share(scope.ids, scope.timeline) }),
        SelectionAction(
            if (unarchive) Icons.Rounded.Unarchive else Icons.Rounded.Archive, if (unarchive) "Unarchive" else "Archive",
            { actions.archive(scope.ids, scope.timeline); scope.clear() }, primary = false,
        ),
        SelectionAction(Icons.Rounded.DeleteOutline, "Delete", { actions.delete(scope.ids, scope.timeline); scope.clear() }),
    )
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
