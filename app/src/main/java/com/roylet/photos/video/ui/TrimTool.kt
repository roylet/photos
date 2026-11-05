package com.roylet.photos.video.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.ZoomOutMap
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.roylet.photos.editor.ui.rememberHaptics
import com.roylet.photos.editor.ui.rememberReducedMotion
import com.roylet.photos.ui.components.DarkButton
import com.roylet.photos.ui.components.DarkIconButton
import com.roylet.photos.ui.theme.Brutal
import com.roylet.photos.ui.theme.BrutalDark
import com.roylet.photos.ui.theme.Type
import com.roylet.photos.video.model.VideoEdit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

private val StripPad = 20.dp
private val RulerH = 18.dp
private val StripH = 58.dp
private val HandleW = 15.dp
private val MiniH = 8.dp

/**
 * The Trim tool: a filmstrip of the whole source with the kept segments outlined and orange trim
 * handles at their edges, a draggable playhead, SPLIT / DELETE, pinch to zoom, and the in/out
 * times and resulting length.
 */
@Composable
fun TrimPanel(session: VideoEditorSession, playback: VideoPlayback, edit: State<VideoEdit>, modifier: Modifier = Modifier) {
    val haptics = rememberHaptics()
    val position by playback.outputPosition.collectAsState()
    var zoom by remember { mutableStateOf(1f) }
    var resetZoom by remember { mutableIntStateOf(0) }
    val e = edit.value
    val sel = session.selected?.takeIf { it in e.segments.indices }
    val canSplit by remember(edit) { derivedStateOf { edit.value.splitAt(position) != edit.value } }

    Column(modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TrimInfoRow(e, sel, Modifier.padding(horizontal = 16.dp))
        TrimStrip(
            session, playback, edit,
            onZoomChange = { zoom = it },
            resetZoomKey = resetZoom,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            if (zoom > 1.01f) "DRAG THE BAR TO SCROLL · PINCH TO ZOOM" else "TAP A CLIP · DRAG HANDLES · PINCH TO ZOOM",
            style = Type.Tiny, color = BrutalDark.MutedText, maxLines = 1,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 0.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DarkButton("Split", {
                val before = session.history.edit
                val next = before.splitAt(playback.outputPosition.value)
                if (next != before) {
                    session.history.set(next)
                    // Select the part after the cut, where the playhead now is.
                    session.selected = next.outputToSource(playback.outputPosition.value).segmentIndex
                    haptics.snap()
                }
            }, icon = Icons.Rounded.ContentCut, enabled = canSplit)
            DarkButton("Delete", {
                val i = session.selected ?: return@DarkButton
                session.history.update { it.deleteSegment(i) }
                session.selected = null
                haptics.snap()
            }, primary = false, icon = Icons.Rounded.DeleteOutline, enabled = sel != null && e.segments.size > 1)
            Spacer(Modifier.weight(1f))
            if (zoom > 1.01f) {
                Text(String.format(Locale.US, "%.1f×", zoom), style = Type.Label, color = BrutalDark.Orange)
                DarkIconButton(Icons.Rounded.ZoomOutMap, "Fit whole video", { resetZoom++ })
            }
            DarkIconButton(Icons.Rounded.RestartAlt, "Reset trim", {
                session.history.update { it.resetSegments() }
                session.selected = null
            }, enabled = !e.isFullLength)
        }
    }
}

@Composable
private fun TrimInfoRow(e: VideoEdit, selected: Int?, modifier: Modifier) {
    val seg = selected?.let { e.segments[it] }
    val inMs = seg?.startMs ?: e.segments.first().startMs
    val outMs = seg?.endMs ?: e.segments.last().endMs
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        TimeStat(if (seg != null) "CLIP ${selected + 1} IN" else "IN", TrimMath.formatTime(inMs))
        Spacer(Modifier.width(16.dp))
        TimeStat("OUT", TrimMath.formatTime(outMs))
        Spacer(Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                if (e.segments.size > 1) "${e.segments.size} CLIPS · DURATION" else "DURATION",
                style = Type.Tiny, color = BrutalDark.MutedText,
            )
            Text(TrimMath.formatDuration(e.outputDurationMs()), style = Type.Title, color = BrutalDark.Orange)
        }
    }
}

@Composable
private fun TimeStat(label: String, value: String) {
    Column {
        Text(label, style = Type.Tiny, color = BrutalDark.MutedText)
        Text(value, style = Type.Title, color = BrutalDark.Text)
    }
}

/** What a touch on the strip is doing. */
private enum class StripMode { NONE, HANDLE, SCRUB, PINCH, MINIMAP }

@Composable
fun TrimStrip(
    session: VideoEditorSession,
    playback: VideoPlayback,
    edit: State<VideoEdit>,
    onZoomChange: (Float) -> Unit,
    resetZoomKey: Int,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    val reduced = rememberReducedMotion()
    val measurer = rememberTextMeasurer(cacheSize = 32)
    val padPx = with(LocalDensity.current) { StripPad.toPx() }
    val position = playback.outputPosition.collectAsState()
    val playing by playback.isPlaying.collectAsState()
    var vp by remember(session) { mutableStateOf(TimelineViewport(session.durationMs, 0f)) }
    var mode by remember { mutableStateOf(StripMode.NONE) }
    var active by remember { mutableStateOf<TrimHandle?>(null) }
    val lift by animateFloatAsState(if (active != null) 1f else 0f, if (reduced) tween(0) else spring(dampingRatio = 0.55f, stiffness = 800f), label = "lift")
    val selectedAlpha by animateFloatAsState(if (session.selected != null) 1f else 0f, tween(if (reduced) 0 else 160), label = "sel")
    val zoomChange by rememberUpdatedState(onZoomChange)
    val zoomAnim = remember { Animatable(1f) }

    LaunchedEffect(vp.zoom) { zoomChange(vp.zoom) }
    LaunchedEffect(resetZoomKey) {
        if (resetZoomKey == 0) return@LaunchedEffect
        val from = vp.zoom
        zoomAnim.snapTo(from)
        val focus = vp.xOf(TrimMath.playheadSourceMs(edit.value, position.value))
        zoomAnim.animateTo(1f, if (reduced) tween(0) else spring(dampingRatio = 0.85f, stiffness = 500f)) {
            vp = vp.zoomedAround(value, focus)
        }
    }
    // Keep the playhead in view while playing on a zoomed strip.
    LaunchedEffect(session, playing) {
        if (!playing) return@LaunchedEffect
        snapshotFlow { position.value }.collect { p ->
            if (mode == StripMode.NONE && vp.zoom > 1f) vp = vp.revealing(TrimMath.playheadSourceMs(edit.value, p), vp.widthPx * 0.15f)
        }
    }
    // The exact frame under a dragged handle, for the preview (latest wins; the strip frame shows meanwhile).
    LaunchedEffect(session) {
        val file = session.sourceFile ?: return@LaunchedEffect
        val frames = session.frames ?: return@LaunchedEffect
        snapshotFlow { if (session.scrubHandle != null) session.scrubMs else null }.collectLatest { ms ->
            if (ms == null) { session.scrubFrame = null; return@collectLatest }
            delay(24)
            frames.frame(file, ms, maxPx = 720)?.let { session.scrubFrame = it.asImageBitmap() }
        }
    }
    // Zoomed in: decode extra frames for the visible part (power-of-two zoom levels, so they cache).
    LaunchedEffect(session) {
        val file = session.sourceFile ?: return@LaunchedEffect
        val frames = session.frames ?: return@LaunchedEffect
        snapshotFlow {
            val level = 2f.pow(floor(ln(vp.zoom.toDouble()) / ln(2.0)).toFloat())
            Triple(level, vp.visibleStartMs, vp.visibleEndMs)
        }.collectLatest { (level, from, to) ->
            if (level < 2f) return@collectLatest
            delay(180)
            val step = session.durationMs / (VideoEditorSession.STRIP_FRAMES * level)
            if (step <= 0) return@collectLatest
            val first = (from / step).toLong()
            val last = (to / step).toLong() + 1
            val times = (first..last).map { (it * step + step / 2).toLong().coerceAtMost(session.durationMs - 1) }
                .filter { it !in session.zoomFrames }.toLongArray()
            frames.load(file, times, exact = step < 1500) { i, bmp -> session.zoomFrames[times[i]] = bmp.asImageBitmap() }
        }
    }

    // Sorted times of every loaded frame, for nearest-frame lookup while drawing.
    val bank by remember(session) {
        derivedStateOf {
            val m = HashMap<Long, ImageBitmap>()
            session.strip.frames.forEachIndexed { i, f -> if (f != null) m[session.strip.timesMs[i]] = f }
            m.putAll(session.zoomFrames)
            FrameBank(m)
        }
    }

    Canvas(
        modifier
            .height(RulerH + StripH + 8.dp + MiniH + 6.dp)
            .semantics { contentDescription = "Trim timeline" }
            .onSizeChanged { sz ->
                val w = sz.width - 2 * padPx
                if (w > 0f && vp.widthPx != w) {
                    // First layout (or a resize): keep the zoom, re-clamp the scroll.
                    val ratio = if (vp.widthPx > 0f) w / vp.widthPx else 1f
                    vp = vp.copy(widthPx = w, scrollPx = vp.scrollPx * ratio).clamped()
                }
            }
            .pointerInput(session) {
                val pad = StripPad.toPx()
                val stripTop = RulerH.toPx()
                val stripBottom = stripTop + StripH.toPx()
                val miniTop = stripBottom + 8.dp.toPx()
                val slop = 22.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val v0 = vp
                    val x0 = down.position.x - pad
                    val startEdit = session.history.edit
                    val hit = if (down.position.y >= miniTop - 2.dp.toPx() && v0.zoom > 1f) null else TrimMath.hitTest(startEdit, v0, x0, slop)
                    var m = when {
                        hit == null -> StripMode.MINIMAP
                        hit is StripHit.Handle -> StripMode.HANDLE
                        else -> StripMode.NONE
                    }
                    val handle = (hit as? StripHit.Handle)?.handle
                    var moved = false
                    var lastSnap: Long? = null
                    var lastSegment = -1
                    var grabOffset = 0f
                    var targets: List<Long> = emptyList()
                    if (m == StripMode.HANDLE && handle != null) {
                        val s = startEdit.segments[handle.segment]
                        val edgeMs = if (handle.edge == TrimEdge.START) s.startMs else s.endMs
                        grabOffset = v0.xOf(edgeMs) - x0
                        targets = TrimMath.snapTargets(startEdit, TrimMath.playheadSourceMs(startEdit, position.value), handle)
                        session.selected = handle.segment
                        session.history.beginGesture()
                        playback.pause()
                        active = handle
                        mode = m
                        session.scrubMs = edgeMs
                        session.scrubHandle = handle
                        haptics.tick()
                    }
                    if (m == StripMode.MINIMAP) mode = m
                    try {
                        while (true) {
                            val ev = awaitPointerEvent()
                            if (ev.changes.none { it.pressed }) break
                            val pressed = ev.changes.count { it.pressed }
                            if (pressed > 1 && m != StripMode.HANDLE) {
                                if (m != StripMode.PINCH) { m = StripMode.PINCH; mode = m }
                                val z = ev.calculateZoom()
                                val pan = ev.calculatePan()
                                val c = ev.calculateCentroid(useCurrent = true)
                                vp = vp.zoomedAround(vp.zoom * z, c.x - pad).scrolledBy(-pan.x)
                                ev.changes.forEach { if (it.positionChanged()) it.consume() }
                                continue
                            }
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: ev.changes.first()
                            val x = ch.position.x - pad
                            if (!moved && abs(ch.position.x - down.position.x) > viewConfiguration.touchSlop) {
                                moved = true
                                if (m == StripMode.NONE) {
                                    m = StripMode.SCRUB; mode = m
                                    playback.pause()
                                }
                            }
                            when (m) {
                                StripMode.HANDLE -> if (handle != null) {
                                    ch.consume()
                                    // Auto-scroll at the strip ends when zoomed.
                                    val edgeZone = 28.dp.toPx()
                                    if (vp.zoom > 1f) {
                                        if (x < edgeZone) vp = vp.scrolledBy(-(edgeZone - x) * 0.35f)
                                        else if (x > vp.widthPx - edgeZone) vp = vp.scrolledBy((x - (vp.widthPx - edgeZone)) * 0.35f)
                                    }
                                    val requested = vp.msAt(x + grabOffset)
                                    val threshold = (TrimMath.SNAP_PX.dp.toPx() * vp.msPerPx).toLong()
                                    val r = TrimMath.dragHandle(startEdit, handle, requested, targets, threshold)
                                    if (r.snappedTo != null && r.snappedTo != lastSnap) haptics.snap()
                                    lastSnap = r.snappedTo
                                    session.history.set(r.edit)
                                    session.scrubMs = r.edgeMs
                                }
                                StripMode.SCRUB -> {
                                    ch.consume()
                                    if (vp.zoom > 1f) {
                                        val edgeZone = 28.dp.toPx()
                                        if (x < edgeZone) vp = vp.scrolledBy(-(edgeZone - x) * 0.3f)
                                        else if (x > vp.widthPx - edgeZone) vp = vp.scrolledBy((x - (vp.widthPx - edgeZone)) * 0.3f)
                                    }
                                    val e = session.history.edit
                                    val out = TrimMath.scrubToOutput(e, vp, x.coerceIn(0f, vp.widthPx))
                                    val seg = e.outputToSource(out).segmentIndex
                                    if (lastSegment >= 0 && seg != lastSegment) haptics.tick()
                                    lastSegment = seg
                                    playback.seekTo(out)
                                }
                                StripMode.MINIMAP -> {
                                    ch.consume()
                                    val frac = (x / vp.widthPx.coerceAtLeast(1f)).coerceIn(0f, 1f)
                                    vp = vp.copy(scrollPx = frac * vp.contentWidth - vp.widthPx / 2f).clamped()
                                }
                                else -> Unit
                            }
                        }
                    } finally {
                        when (m) {
                            StripMode.HANDLE -> if (handle != null) {
                                session.history.endGesture()
                                val e = session.history.edit
                                playback.setEdit(e)
                                val s = e.segments.getOrNull(handle.segment)
                                if (s != null) {
                                    val edgeMs = if (handle.edge == TrimEdge.START) s.startMs else (s.endMs - 40).coerceAtLeast(s.startMs)
                                    e.sourceToOutput(edgeMs)?.let(playback::seekTo)
                                }
                                session.scrubHandle = null
                                active = null
                            }
                            StripMode.NONE -> if (!moved) {
                                // Tap: select the segment (again to deselect) and move the playhead there.
                                when (hit) {
                                    is StripHit.Segment -> {
                                        session.selected = if (session.selected == hit.index) null else hit.index
                                        playback.seekTo(TrimMath.scrubToOutput(startEdit, v0, x0))
                                        haptics.tick()
                                    }
                                    else -> session.selected = null
                                }
                            }
                            StripMode.MINIMAP -> if (!moved && v0.zoom <= 1f) Unit
                            else -> Unit
                        }
                        mode = StripMode.NONE
                    }
                }
            },
    ) {
        val pad = StripPad.toPx()
        val e = edit.value
        val v = vp
        val stripTop = RulerH.toPx()
        val sh = StripH.toPx()
        drawRuler(v, pad, measurer)
        clipRect(left = 0f, top = 0f, right = size.width, bottom = size.height) {
            drawFrames(v, bank, pad, stripTop, sh, session.width.toFloat() / session.height.coerceAtLeast(1))
            drawCuts(e, v, pad, stripTop, sh)
            drawSegments(e, v, pad, stripTop, sh, session.selected, selectedAlpha)
            drawHandles(e, v, pad, stripTop, sh, active, lift)
            drawPlayhead(TrimMath.playheadX(e, v, position.value) + pad, stripTop, sh)
        }
        if (v.zoom > 1.01f) drawMinimap(e, v, pad, stripTop + sh + 8.dp.toPx(), TrimMath.playheadSourceMs(e, position.value))
    }
}

/** Loaded frames by source time, for nearest-frame lookup. */
private class FrameBank(map: Map<Long, ImageBitmap>) {
    private val times = map.keys.sorted().toLongArray()
    private val images = times.map { map.getValue(it) }

    fun nearest(ms: Long): ImageBitmap? {
        if (times.isEmpty()) return null
        var i = java.util.Arrays.binarySearch(times, ms)
        if (i >= 0) return images[i]
        i = -i - 1
        val a = (i - 1).coerceAtLeast(0)
        val b = i.coerceAtMost(times.lastIndex)
        return if (abs(times[a] - ms) <= abs(times[b] - ms)) images[a] else images[b]
    }
}

private val RulerSteps = longArrayOf(100, 250, 500, 1_000, 2_000, 5_000, 10_000, 15_000, 30_000, 60_000, 120_000, 300_000, 600_000, 900_000, 1_800_000, 3_600_000)

private fun DrawScope.drawRuler(v: TimelineViewport, pad: Float, measurer: androidx.compose.ui.text.TextMeasurer) {
    val minLabelPx = 64.dp.toPx()
    val step = RulerSteps.firstOrNull { it / v.msPerPx >= minLabelPx } ?: RulerSteps.last()
    val minor = step / 4
    val base = RulerH.toPx() - 3.dp.toPx()
    val first = (v.visibleStartMs / minor) * minor
    var t = first
    val style = Type.Tiny.copy(color = BrutalDark.MutedText)
    while (t <= v.visibleEndMs + minor) {
        val x = pad + v.xOf(t)
        if (x >= pad - 1f && x <= pad + v.widthPx + 1f) {
            val major = t % step == 0L
            val h = if (major) 6.dp.toPx() else 3.dp.toPx()
            drawLine(BrutalDark.MutedText.copy(alpha = if (major) 0.9f else 0.45f), Offset(x, base - h), Offset(x, base), 1.dp.toPx())
            if (major) {
                val label = rulerLabel(t, step)
                val layout = measurer.measure(label, style)
                val lx = (x + 3.dp.toPx()).coerceAtMost(size.width - layout.size.width.toFloat())
                drawText(layout, topLeft = Offset(lx, base - 6.dp.toPx() - layout.size.height + 3.dp.toPx()))
            }
        }
        t += minor
    }
}

private fun rulerLabel(ms: Long, step: Long): String {
    val s = ms / 1000
    return if (step < 1000) String.format(Locale.US, "%d:%02d.%d", s / 60, s % 60, (ms / 100) % 10)
    else if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
    else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

private fun DrawScope.drawFrames(v: TimelineViewport, bank: FrameBank, pad: Float, top: Float, h: Float, aspect: Float) {
    val r = CornerRadius(4.dp.toPx())
    val left = pad + v.xOf(0)
    val right = pad + v.xOf(v.durationMs)
    val visL = max(left, pad - HandleW.toPx())
    val visR = min(right, pad + v.widthPx + HandleW.toPx())
    drawRoundRect(BrutalDark.Raised, Offset(left, top), Size(right - left, h), r)
    val tileW = (h * aspect.coerceIn(0.56f, 1f)).coerceAtLeast(8f)
    val firstTile = floor((visL - left) / tileW).toInt().coerceAtLeast(0)
    val lastTile = ceil((visR - left) / tileW).toInt()
    clipRect(left = left, top = top, right = right, bottom = top + h) {
        for (i in firstTile..lastTile) {
            val x = left + i * tileW
            if (x > right) break
            val centre = ((x + tileW / 2f - left) * v.msPerPx).toLong().coerceIn(0, v.durationMs)
            val img = bank.nearest(centre) ?: continue
            // Centre-crop the frame to the tile.
            val iw = img.width.toFloat(); val ih = img.height.toFloat()
            val tileAspect = tileW / h
            val (sw, shh) = if (iw / ih > tileAspect) (ih * tileAspect) to ih else iw to (iw / tileAspect)
            drawImage(
                img,
                srcOffset = IntOffset(((iw - sw) / 2f).roundToInt(), ((ih - shh) / 2f).roundToInt()),
                srcSize = IntSize(sw.roundToInt().coerceAtLeast(1), shh.roundToInt().coerceAtLeast(1)),
                dstOffset = IntOffset(x.roundToInt(), top.roundToInt()),
                dstSize = IntSize(ceil(tileW).toInt() + 1, h.roundToInt()),
            )
        }
        // Thin separators between tiles read as film.
        var x = left + firstTile * tileW
        while (x <= visR) {
            drawLine(Color.Black.copy(alpha = 0.35f), Offset(x, top), Offset(x, top + h), 1.dp.toPx())
            x += tileW
        }
    }
}

/** Cut-out time: darkened with a diagonal hatch. */
private fun DrawScope.drawCuts(e: VideoEdit, v: TimelineViewport, pad: Float, top: Float, h: Float) {
    val gaps = ArrayList<Pair<Long, Long>>()
    var t = 0L
    for (s in e.segments) {
        if (s.startMs > t) gaps += t to s.startMs
        t = s.endMs
    }
    if (t < e.sourceDurationMs) gaps += t to e.sourceDurationMs
    val hatch = 9.dp.toPx()
    for ((a, b) in gaps) {
        val l = pad + v.xOf(a)
        val r = pad + v.xOf(b)
        if (r < 0f || l > size.width) continue
        drawRect(Color.Black.copy(alpha = 0.66f), Offset(l, top), Size(r - l, h))
        clipRect(l, top, r, top + h) {
            var x = l - h
            while (x < r) {
                drawLine(BrutalDark.MutedText.copy(alpha = 0.22f), Offset(x, top + h), Offset(x + h, top), 1.5.dp.toPx())
                x += hatch
            }
        }
    }
}

private fun DrawScope.drawSegments(e: VideoEdit, v: TimelineViewport, pad: Float, top: Float, h: Float, selected: Int?, selectedAlpha: Float) {
    val stroke = 3.dp.toPx()
    e.segments.forEachIndexed { i, s ->
        val l = pad + v.xOf(s.startMs)
        val r = pad + v.xOf(s.endMs)
        if (r < -20f || l > size.width + 20f) return@forEachIndexed
        val isSel = i == selected
        if (isSel) drawRect(BrutalDark.Orange.copy(alpha = 0.16f * selectedAlpha), Offset(l, top), Size(r - l, h))
        drawRect(
            if (isSel) BrutalDark.Orange else BrutalDark.Line,
            Offset(l + stroke / 2, top + stroke / 2), Size((r - l - stroke).coerceAtLeast(0f), h - stroke),
            style = Stroke(stroke),
        )
    }
}

private fun DrawScope.drawHandles(e: VideoEdit, v: TimelineViewport, pad: Float, top: Float, h: Float, active: TrimHandle?, lift: Float) {
    val hw = HandleW.toPx()
    val over = 5.dp.toPx()
    val shadow = 3.dp.toPx()
    val border = 2.dp.toPx()
    val r = CornerRadius(5.dp.toPx())
    e.segments.forEachIndexed { i, s ->
        for (edge in TrimEdge.entries) {
            val ms = if (edge == TrimEdge.START) s.startMs else s.endMs
            val x = pad + v.xOf(ms)
            if (x < -hw * 2 || x > size.width + hw * 2) continue
            val isActive = active == TrimHandle(i, edge)
            val l = if (edge == TrimEdge.START) x - hw else x
            val k = if (isActive) lift else 0f
            val grow = 3.dp.toPx() * k
            val rect = Rect(l - grow / 2, top - over - grow, l + hw + grow / 2, top + h + over + grow)
            val sh = shadow * (1f - k) + 1.dp.toPx()
            drawRoundRect(Color.Black, rect.topLeft + Offset(sh, sh), rect.size, r)
            drawRoundRect(if (isActive) BrutalDark.Yellow else BrutalDark.Orange, rect.topLeft, rect.size, r)
            drawRoundRect(Brutal.Ink, rect.topLeft, rect.size, r, style = Stroke(border))
            // Grip: two short ink bars.
            val cx = rect.center.x
            val cy = rect.center.y
            val gl = 7.dp.toPx()
            val gap = 2.5.dp.toPx()
            drawLine(Brutal.Ink, Offset(cx - gap, cy - gl), Offset(cx - gap, cy + gl), 2.dp.toPx())
            drawLine(Brutal.Ink, Offset(cx + gap, cy - gl), Offset(cx + gap, cy + gl), 2.dp.toPx())
        }
    }
}

private fun DrawScope.drawPlayhead(x: Float, top: Float, h: Float) {
    val w = 3.dp.toPx()
    val knob = 12.dp.toPx()
    val y0 = top - knob + 2.dp.toPx()
    val y1 = top + h + 6.dp.toPx()
    drawLine(Color.Black, Offset(x + 1.5.dp.toPx(), y0), Offset(x + 1.5.dp.toPx(), y1 + 1.5.dp.toPx()), w + 2.dp.toPx())
    drawLine(BrutalDark.Text, Offset(x, y0), Offset(x, y1), w)
    withTransform({ translate(x - knob / 2, y0 - knob / 2) }) {
        drawRoundRect(Color.Black, Offset(2.dp.toPx(), 2.dp.toPx()), Size(knob, knob), CornerRadius(3.dp.toPx()))
        drawRoundRect(BrutalDark.Text, Offset.Zero, Size(knob, knob), CornerRadius(3.dp.toPx()))
        drawRoundRect(Brutal.Ink, Offset.Zero, Size(knob, knob), CornerRadius(3.dp.toPx()), style = Stroke(2.dp.toPx()))
    }
}

/** Zoomed: the whole source in a thin bar, the visible window outlined (drag it to scroll). */
private fun DrawScope.drawMinimap(e: VideoEdit, v: TimelineViewport, pad: Float, top: Float, playheadMs: Long) {
    val w = v.widthPx
    val h = MiniH.toPx()
    val dur = v.durationMs.coerceAtLeast(1).toFloat()
    val r = CornerRadius(h / 2)
    drawRoundRect(BrutalDark.Raised, Offset(pad, top), Size(w, h), r)
    for (s in e.segments) {
        drawRect(BrutalDark.MutedText.copy(alpha = 0.7f), Offset(pad + s.startMs / dur * w, top + 2.dp.toPx()), Size((s.durationMs / dur * w).coerceAtLeast(1f), h - 4.dp.toPx()))
    }
    val l = pad + v.visibleStartMs / dur * w
    val rr = pad + v.visibleEndMs / dur * w
    drawRoundRect(BrutalDark.Orange, Offset(l, top - 1.dp.toPx()), Size(rr - l, h + 2.dp.toPx()), CornerRadius(3.dp.toPx()), style = Stroke(2.dp.toPx()))
    val px = pad + playheadMs / dur * w
    drawLine(BrutalDark.Text, Offset(px, top - 1.dp.toPx()), Offset(px, top + h + 1.dp.toPx()), 2.dp.toPx())
}
