package com.roylet.photos.ui.gallery

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.roylet.photos.domain.timeline.TimeBucket
import com.roylet.photos.domain.timeline.TimelineAsset
import com.roylet.photos.domain.timeline.TimelineRepository
import com.roylet.photos.ui.components.reducedMotion
import com.roylet.photos.ui.theme.Brutal
import com.roylet.photos.ui.theme.Type
import com.roylet.photos.util.Format
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.DateFormatSymbols
import java.util.Calendar
import java.util.Locale

val GridSpacing = 3.dp
private val HeaderHeight = 52.dp
/** Densities are tuned for this width; wider windows get proportionally more columns. */
private val PhoneWidth = 420.dp
private val PinchIn = 1.28f
private val PinchOut = 0.78f

/**
 * The month-by-month justified photo grid used by every timeline-like screen.
 *
 * - Months are fetched lazily as they scroll into view (with neighbours prefetched) and laid out on demand.
 * - Pinch changes density continuously around the fingers, snapping between [GridDensity] levels.
 * - Long-press selects (with haptics); dragging extends the range and auto-scrolls near the edges.
 * - A date scrubber appears while scrolling.
 *
 * @param onOpen called with the asset's global timeline index (for the viewer's pager) when tapped outside selection mode.
 * @param topContent an optional first item (e.g. a memories carousel) that scrolls with the grid.
 */
@Composable
fun TimelineGrid(
    timeline: TimelineRepository,
    onOpen: (index: Int, asset: TimelineAsset) -> Unit,
    modifier: Modifier = Modifier,
    selection: GridSelection = remember { GridSelection() },
    state: LazyListState = rememberLazyListState(),
    initialDensity: GridDensity = GridDensity.Medium,
    contentPadding: PaddingValues = PaddingValues(bottom = 16.dp),
    /** Scroll so this global index is visible on first composition (returning from the viewer). */
    revealIndex: Int? = null,
    /** Called once [revealIndex] has been handled, so the caller can clear it. */
    onRevealed: () -> Unit = {},
    topContent: (@Composable () -> Unit)? = null,
) {
    val snapshot by timeline.snapshot.collectAsState()
    LaunchedEffect(timeline) { timeline.start() }
    val urls = LocalMediaUrls.current
    val haptics = LocalHapticFeedback.current
    val reduced = reducedMotion()
    val scope = rememberCoroutineScope()
    val px = LocalDensity.current
    val spacingPx = with(px) { GridSpacing.toPx() }
    val headerPx = with(px) { HeaderHeight.toPx() }
    val lead = if (topContent != null) 1 else 0
    val open by rememberUpdatedState(onOpen)

    BoxWithConstraints(modifier.fillMaxSize().background(Brutal.Paper)) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        var densityIndex by rememberSaveable { mutableIntStateOf(initialDensity.ordinal) }
        val density = GridDensity.entries[densityIndex]
        val cache = remember { GridLayoutCache() }
        val columnScale = (maxWidth / PhoneWidth).coerceAtLeast(1f)
        val model = remember(snapshot, widthPx, density, columnScale) { cache.build(snapshot, widthPx, density, spacingPx, columnScale) }
        val currentModel by rememberUpdatedState(model)

        // ---- Pinch-to-zoom density ----
        var scale by remember { mutableFloatStateOf(1f) }
        var focal by remember { mutableStateOf(Offset.Zero) }
        val settle = remember { arrayOfNulls<Job>(1) }
        /** (global asset index, y in viewport) to keep under the fingers after a density change. */
        var pendingAnchor by remember { mutableStateOf<Pair<Int, Float>?>(null) }

        fun hit(pos: Offset): Int? {
            val m = currentModel
            val visible = state.layoutInfo.visibleItemsInfo
            // A (possibly stuck) month header covers the rows under it.
            if (visible.any { m.items.getOrNull(it.index - lead) is GridItem.Header && pos.y >= it.offset && pos.y < it.offset + it.size }) return null
            val info = visible.firstOrNull { pos.y >= it.offset && pos.y < it.offset + it.size && m.items.getOrNull(it.index - lead) is GridItem.Row }
                ?: return null
            val item = m.items[info.index - lead] as GridItem.Row
            val lefts = item.row.lefts(m.spacingPx)
            val c = lefts.indexOfLast { it <= pos.x }.coerceAtLeast(0)
            return m.snapshot.offsets[item.bucket] + item.row.start + c
        }

        fun topAssetIndex(): Int {
            val m = currentModel
            val item = m.items.getOrNull(state.firstVisibleItemIndex - lead) ?: return 0
            val base = m.snapshot.offsets.getOrElse(item.bucket) { 0 }
            return when (item) {
                is GridItem.Header -> base
                is GridItem.Row -> base + item.row.start
                is GridItem.Pending -> base + (state.firstVisibleItemScrollOffset / item.heightPx.coerceAtLeast(1f) * m.snapshot.countOf(item.bucket)).toInt()
            }
        }

        fun switchDensity(to: Int) {
            val from = GridDensity.entries[densityIndex]
            val target = GridDensity.entries[to]
            pendingAnchor = (hit(focal) ?: topAssetIndex()) to focal.y
            densityIndex = to
            // The new layout is (from/target) times the size of the old; rescale so nothing jumps, then settle to 1.
            scale *= target.columns.toFloat() / from.columns
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        }

        // Apply the anchor in the same frame the new layout is measured.
        val anchor = pendingAnchor
        if (anchor != null) SideEffect {
            pendingAnchor = null
            val idx = model.itemIndexOfAsset(anchor.first)
            var remaining = anchor.second - (model.items.getOrNull(idx)?.let { heightOf(it, headerPx) } ?: 0f) / 2f
            var i = idx
            while (remaining > 0 && i > 0) { i--; remaining -= heightOf(model.items[i], headerPx) }
            state.requestScrollToItem(i + lead, (-remaining).toInt().coerceAtLeast(0))
        }

        // Returning from the viewer: make sure the photo it ended on is on screen for the shared-element return.
        LaunchedEffect(revealIndex, model.items.size > 0) {
            val r = revealIndex ?: return@LaunchedEffect
            if (model.items.isEmpty()) return@LaunchedEffect
            val idx = model.itemIndexOfAsset(r) + lead
            val visible = state.layoutInfo.visibleItemsInfo
            if (visible.none { it.index == idx && it.offset >= 0 && it.offset + it.size <= state.layoutInfo.viewportEndOffset }) {
                state.scrollToItem(idx)
                state.scrollBy(-heightPx / 3)
            }
            onRevealed()
        }

        // ---- Selection drag ----
        var dragPos by remember { mutableStateOf<Offset?>(null) }
        val dragging = dragPos != null
        LaunchedEffect(dragging) {
            if (!dragging) return@LaunchedEffect
            val edge = with(px) { 96.dp.toPx() }
            val maxStep = with(px) { 22.dp.toPx() }
            while (isActive) {
                val p = dragPos ?: break
                val v = when {
                    p.y < edge -> -(1f - p.y / edge).coerceIn(0f, 1f)
                    p.y > heightPx - edge -> (1f - (heightPx - p.y) / edge).coerceIn(0f, 1f)
                    else -> 0f
                }
                if (v != 0f) {
                    state.scrollBy(v * v * maxStep * kotlin.math.sign(v))
                    hit(p)?.let { selection.extendTo(it, currentModel.snapshot) }
                }
                withFrameNanos { }
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                // Two fingers: intercept before the list scrolls, and drive the density pinch.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        var pinching = false
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.count { it.pressed } >= 2) {
                                if (!pinching) { pinching = true; settle[0]?.cancel() }
                                focal = event.calculateCentroid(useCurrent = true).takeIf { it.isSpecified() } ?: focal
                                scale = (scale * event.calculateZoom()).coerceIn(0.45f, 2.4f)
                                if (scale > PinchIn && densityIndex > 0) switchDensity(densityIndex - 1)
                                else if (scale < PinchOut && densityIndex < GridDensity.entries.lastIndex) switchDensity(densityIndex + 1)
                            }
                            if (pinching) event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                        if (pinching) {
                            settle[0] = scope.launch {
                                if (reduced) scale = 1f
                                else animate(scale, 1f, animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow)) { v, _ -> scale = v }
                            }
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { pos ->
                            val i = hit(pos) ?: return@detectTapGestures
                            val asset = currentModel.snapshot.assetAt(i) ?: return@detectTapGestures
                            if (selection.active) {
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                selection.toggle(asset.id)
                            } else open(i, asset)
                        },
                        // Claimed so a long press doesn't also count as a tap; the drag detector below handles it.
                        onLongPress = {},
                    )
                }
                .pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { pos ->
                            val i = hit(pos) ?: return@detectDragGesturesAfterLongPress
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            selection.beginDrag(i, currentModel.snapshot)
                            dragPos = pos
                        },
                        onDrag = { change, _ ->
                            if (dragPos == null) return@detectDragGesturesAfterLongPress
                            change.consume()
                            dragPos = change.position
                            hit(change.position)?.let { selection.extendTo(it, currentModel.snapshot) }
                        },
                        onDragEnd = { selection.endDrag(); dragPos = null },
                        onDragCancel = { selection.endDrag(); dragPos = null },
                    )
                },
        ) {
            LazyColumn(
                state = state,
                userScrollEnabled = !dragging,
                contentPadding = contentPadding,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = if (size.width > 0f) TransformOrigin(focal.x / size.width, focal.y / size.height) else TransformOrigin.Center
                    },
            ) {
                if (topContent != null) item(key = "top", contentType = -1) { topContent() }
                val items = model.items
                snapshot.buckets.forEachIndexed { b, bucket ->
                    val h = model.headerIndex[b]
                    val end = if (b + 1 < snapshot.buckets.size) model.headerIndex[b + 1] else items.size
                    stickyHeader(key = items[h].key, contentType = 0) {
                        MonthHeader(bucket, snapshot.countOf(b), selection, { snapshot.assetsOf(b) }, compact = density.squares)
                    }
                    items(count = end - h - 1, key = { items[h + 1 + it].key }, contentType = { items[h + 1 + it].contentType }) { i ->
                        when (val item = items[h + 1 + i]) {
                            is GridItem.Row -> GridRow(item, selection, urls, density)
                            is GridItem.Pending -> PendingMonth(item) { timeline.ensureLoaded(item.bucket) }
                            is GridItem.Header -> Unit
                        }
                    }
                }
            }

            if (snapshot.buckets.size > 1) {
                TimelineScrubber(
                    state = state,
                    model = model,
                    lead = lead,
                    topAssetIndex = ::topAssetIndex,
                    onJump = { b -> timeline.ensureLoaded(b) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

private fun Offset.isSpecified() = x.isFinite() && y.isFinite()

internal fun heightOf(item: GridItem, headerPx: Float): Float = when (item) {
    is GridItem.Header -> headerPx
    is GridItem.Row -> item.row.height
    is GridItem.Pending -> item.heightPx
}

@Composable
private fun GridRow(item: GridItem.Row, selection: GridSelection, urls: MediaUrls, density: GridDensity) {
    val px = LocalDensity.current
    val row = item.row
    val spacing = GridSpacing
    Row(
        Modifier.fillMaxWidth().height(with(px) { row.height.toDp() }),
        horizontalArrangement = Arrangement.spacedBy(spacing),
    ) {
        for (c in 0 until row.count) {
            val asset = item.assets[row.start + c]
            GridCell(asset, row.widths[c], row.height, selection, urls, showBadges = !density.squares)
        }
    }
    Spacer(Modifier.height(spacing))
}

@Composable
private fun GridCell(asset: TimelineAsset, widthPx: Float, heightPx: Float, selection: GridSelection, urls: MediaUrls, showBadges: Boolean) {
    val px = LocalDensity.current
    val selected by remember(asset.id) { derivedStateOf { asset.id in selection.ids } }
    val selecting by remember { derivedStateOf { selection.active } }
    val reduced = reducedMotion()
    val cellScale by animateFloatAsState(
        if (selected) 0.86f else 1f,
        if (reduced) spring(stiffness = Spring.StiffnessHigh * 10) else spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium),
        label = "cellScale",
    )
    val w = with(px) { widthPx.toDp() }
    val h = with(px) { heightPx.toDp() }
    // Above ~180px the 250px thumbnail starts to look soft, so big cells get the preview rendition.
    val url = if (widthPx > 360f || heightPx > 360f) urls.preview(asset.id) else urls.thumbnail(asset.id)
    Box(Modifier.size(w, h).background(if (selected) Brutal.Peach else Color.Transparent)) {
        AssetImage(
            url, asset.thumbhash, widthPx.toInt(), heightPx.toInt(),
            Modifier
                .matchParentSize()
                .graphicsLayer { scaleX = cellScale; scaleY = cellScale }
                .then(if (selected) Modifier.clip(RoundedCornerShape(6.dp)).border(2.dp, Brutal.Ink, RoundedCornerShape(6.dp)) else Modifier)
                .sharedAsset(asset.id),
        )
        if (showBadges && w > 44.dp) {
            if (asset.isVideo) {
                Row(
                    Modifier.align(Alignment.BottomEnd).padding(5.dp).background(Brutal.Ink.copy(alpha = 0.82f), RoundedCornerShape(50))
                        .padding(start = 3.dp, end = if (w > 72.dp) 7.dp else 3.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.PlayArrow, "Video", tint = Brutal.Paper, modifier = Modifier.size(13.dp))
                    if (w > 72.dp) Text(asset.durationMs?.let(::clock) ?: "", style = Type.Tiny, color = Brutal.Paper, maxLines = 1, softWrap = false)
                }
            }
            if (asset.isFavorite) {
                Icon(
                    Icons.Rounded.Favorite, "Favourite", tint = Color.White,
                    modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).size(15.dp)
                        .drawBehind { drawCircle(Brutal.Ink.copy(alpha = 0.3f), radius = size.minDimension * 0.62f) },
                )
            }
        }
        if (selecting) SelectCheck(selected, Modifier.align(Alignment.TopStart).padding(5.dp))
    }
}

/** Round check in the corner of each cell during selection: an empty ring, filling orange with a check when selected. */
@Composable
fun SelectCheck(selected: Boolean, modifier: Modifier = Modifier) {
    val reduced = reducedMotion()
    val pop by animateFloatAsState(if (selected) 1f else 0f, if (reduced) spring(stiffness = 1e5f) else spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium), label = "check")
    Box(
        modifier.size(22.dp).drawBehind {
            drawCircle(Color.White.copy(alpha = 0.35f))
            drawCircle(Brutal.Ink, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
        },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(22.dp).graphicsLayer { scaleX = pop; scaleY = pop; alpha = pop.coerceIn(0f, 1f) }
                .background(Brutal.Orange, CircleShape).border(2.dp, Brutal.Ink, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Check, "Selected", tint = Brutal.Ink, modifier = Modifier.size(15.dp)) }
    }
}

@Composable
private fun PendingMonth(item: GridItem.Pending, load: () -> Unit) {
    LaunchedEffect(item.key) { load() }
    val px = LocalDensity.current
    Box(
        Modifier.fillMaxWidth().height(with(px) { item.heightPx.toDp() }).drawBehind {
            // Faint tiles hint at the grid while the month loads.
            val tile = 96.dp.toPx()
            val gap = GridSpacing.toPx()
            var y = 0f
            while (y < size.height) {
                var x = 0f
                while (x < size.width) {
                    drawRoundRect(Brutal.Sand, Offset(x, y), Size(minOf(tile, size.width - x), minOf(tile, size.height - y)), CornerRadius(0f))
                    x += tile + gap
                }
                y += tile + gap
            }
        },
    )
}

@Composable
private fun MonthHeader(bucket: TimeBucket, count: Int, selection: GridSelection, assets: () -> List<TimelineAsset>?, compact: Boolean) {
    val selecting by remember { derivedStateOf { selection.active } }
    Row(
        Modifier.fillMaxWidth().height(HeaderHeight).background(Brutal.Paper).padding(start = 14.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(monthLabel(bucket, compact), style = Type.Title, color = Brutal.Ink, maxLines = 1)
        Spacer(Modifier.weight(1f))
        Text(Format.plural(count, "item").uppercase(), style = Type.Tiny, color = Brutal.MutedInk, modifier = Modifier.padding(horizontal = 4.dp))
        if (selecting) {
            val all by remember(bucket.key) { derivedStateOf { assets()?.let { list -> list.isNotEmpty() && list.all { it.id in selection.ids } } == true } }
            Box(
                Modifier.size(40.dp).clickable {
                    val ids = assets()?.map { it.id } ?: return@clickable
                    if (all) selection.deselectAll(ids) else selection.selectAll(ids)
                },
                contentAlignment = Alignment.Center,
            ) { SelectCheck(all) }
        }
    }
}

private val monthNames: Array<String> = DateFormatSymbols.getInstance(Locale.getDefault()).months

internal fun monthLabel(bucket: TimeBucket, compact: Boolean = false, now: Int = Calendar.getInstance().get(Calendar.YEAR)): String {
    val name = monthNames.getOrNull(bucket.month - 1).orEmpty()
    return if (bucket.year == now && !compact) name else "$name ${bucket.year}"
}

/** 75_000 -> "1:15". */
internal fun clock(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

