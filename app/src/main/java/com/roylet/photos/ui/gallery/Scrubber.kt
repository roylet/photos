package com.roylet.photos.ui.gallery

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.roylet.photos.ui.components.brutal
import com.roylet.photos.ui.components.reducedMotion
import com.roylet.photos.ui.theme.Brutal
import com.roylet.photos.ui.theme.Palette
import com.roylet.photos.ui.theme.Type
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val ThumbHeight = 44.dp
private val ThumbWidth = 30.dp
private val TrackPadding = 8.dp

/**
 * Fast-scroll date scrubber over the right edge of a [TimelineGrid]. Fades in while the list
 * scrolls; dragging it jumps through the timeline proportionally to asset count, with a month
 * bubble beside the thumb, year markers along the track and a haptic tick at each month.
 */
@Composable
internal fun TimelineScrubber(
    state: LazyListState,
    model: GridModel,
    lead: Int,
    topAssetIndex: () -> Int,
    onJump: (bucket: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val current by rememberUpdatedState(model)
    val haptics = LocalHapticFeedback.current
    val px = LocalDensity.current
    val reduced = reducedMotion()
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val dragging = dragFraction != null

    var visible by remember { mutableStateOf(false) }
    val scrolling = state.isScrollInProgress
    LaunchedEffect(scrolling, dragging) {
        if (scrolling || dragging) visible = true else { delay(1500); visible = false }
    }
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(if (reduced) 0 else if (visible) 120 else 400), label = "scrubberAlpha")

    fun fraction(): Float {
        dragFraction?.let { return it }
        val total = current.snapshot.total
        return if (total <= 1) 0f else (topAssetIndex() / (total - 1f)).coerceIn(0f, 1f)
    }

    val bucket by remember { derivedStateOf { current.snapshot.bucketIndexOf((fraction() * (current.snapshot.total - 1).coerceAtLeast(0)).roundToInt()) } }

    BoxWithConstraints(modifier.graphicsLayer { this.alpha = alpha }) {
        val trackPx = constraints.maxHeight - with(px) { (TrackPadding * 2 + ThumbHeight).toPx() }
        val thumbHalf = with(px) { ThumbHeight.toPx() / 2 + TrackPadding.toPx() }

        fun jump(f: Float) {
            val m = current
            val total = m.snapshot.total
            if (total == 0) return
            val asset = (f * (total - 1)).roundToInt().coerceIn(0, total - 1)
            val b = m.snapshot.bucketIndexOf(asset)
            onJump(b)
            val idx = m.itemIndexOfAsset(asset)
            val offset = (m.items.getOrNull(idx) as? GridItem.Pending)?.let { p ->
                val inBucket = asset - m.snapshot.offsets[b]
                (inBucket.toFloat() / m.snapshot.countOf(b).coerceAtLeast(1) * p.heightPx).toInt()
            } ?: 0
            state.requestScrollToItem(idx + lead, offset)
        }

        // Year markers, only while dragging, thinned so they never overlap.
        if (dragging) {
            val minGap = with(px) { 22.dp.toPx() }
            val years = remember(model.snapshot.buckets, trackPx) {
                val out = ArrayList<Pair<Int, Float>>()
                var lastY = -1e9f
                val s = model.snapshot
                s.buckets.forEachIndexed { i, b ->
                    if (i == 0 || s.buckets[i - 1].year != b.year) {
                        val y = s.offsets[i] / (s.total - 1f).coerceAtLeast(1f) * trackPx
                        if (y - lastY >= minGap) { out += b.year to y; lastY = y }
                    }
                }
                out
            }
            years.forEach { (year, y) ->
                Text(
                    year.toString(), style = Type.Tiny, color = Palette.ink,
                    modifier = Modifier.align(Alignment.TopEnd)
                        .offset { IntOffset(0, (y + thumbHalf).roundToInt() - 7.dp.roundToPx()) }
                        .padding(end = ThumbWidth + 16.dp)
                        .background(Palette.paper, RoundedCornerShape(4.dp))
                        .border(1.5.dp, Palette.border, RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }

        // Touch strip along the edge (only live while the scrubber is showing, so it never steals list scrolls).
        Box(
            Modifier.align(Alignment.TopEnd).fillMaxHeight().width(48.dp)
                .then(
                    if (!visible && !dragging) Modifier else Modifier.pointerInput(trackPx) {
                        var last = -1
                        fun update(y: Float) {
                            val f = ((y - thumbHalf) / trackPx).coerceIn(0f, 1f)
                            dragFraction = f
                            val m = current
                            val b = m.snapshot.bucketIndexOf((f * (m.snapshot.total - 1).coerceAtLeast(0)).roundToInt())
                            if (b != last) {
                                if (last != -1) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                last = b
                            }
                            jump(f)
                        }
                        detectVerticalDragGestures(
                            onDragStart = { o -> last = -1; update(o.y) },
                            onVerticalDrag = { change, _ -> change.consume(); update(change.position.y) },
                            onDragEnd = { dragFraction = null },
                            onDragCancel = { dragFraction = null },
                        )
                    },
                ),
        )

        // The thumb and, while dragging, the month bubble beside it. Position is read at layout time so scrolling doesn't recompose.
        Box(
            Modifier.align(Alignment.TopEnd)
                .layout { measurable, constraints ->
                    val p = measurable.measure(constraints)
                    layout(p.width, p.height) { p.place(0, (fraction() * trackPx).roundToInt() + TrackPadding.roundToPx()) }
                }
                .padding(end = 6.dp),
        ) {
            Box(
                Modifier.brutal(Brutal.Orange, shape = RoundedCornerShape(50), shadow = 3.dp).size(ThumbWidth, ThumbHeight),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.UnfoldMore, "Scroll by date", tint = Brutal.Ink, modifier = Modifier.size(18.dp)) }
        }
        if (dragging) {
            val b = model.snapshot.buckets.getOrNull(bucket)
            if (b != null) {
                Box(
                    Modifier.align(Alignment.TopEnd)
                        .layout { measurable, constraints ->
                            val p = measurable.measure(constraints)
                            val y = (fraction() * trackPx).roundToInt() + TrackPadding.roundToPx() + (ThumbHeight.roundToPx() - p.height) / 2
                            layout(p.width, p.height) { p.place(0, y.coerceAtLeast(0)) }
                        }
                        .padding(end = ThumbWidth + 22.dp),
                ) {
                    Text(
                        monthLabel(b, compact = true), style = Type.Title, color = Palette.ink,
                        modifier = Modifier.brutal(Palette.card, shadow = 4.dp).padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}
