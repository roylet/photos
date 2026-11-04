package com.roylet.photos.editor.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.RotateLeft
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.roylet.photos.editor.recipe.CropRect
import com.roylet.photos.editor.recipe.Geometry
import com.roylet.photos.editor.recipe.Params
import com.roylet.photos.editor.recipe.Ranges
import com.roylet.photos.editor.recipe.Recipe
import com.roylet.photos.ui.components.DarkChip
import com.roylet.photos.ui.components.DarkIconButton
import com.roylet.photos.ui.theme.BrutalDark
import com.roylet.photos.ui.theme.Type
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/** Crop aspect presets. [ratio] is width / height in pixels; null = free; NaN = the frame's own. */
enum class AspectPreset(val label: String, val ratio: Float?) {
    FREE("Free", null),
    ORIGINAL("Original", Float.NaN),
    SQUARE("1:1", 1f),
    P4_5("4:5", 4f / 5f),
    L3_2("3:2", 3f / 2f),
    L16_9("16:9", 16f / 9f),
    P9_16("9:16", 9f / 16f);

    /** The pixel ratio for a frame of [frameAspect], or null when free. */
    fun pixelRatio(frameAspect: Float): Float? = when {
        ratio == null -> null
        ratio.isNaN() -> frameAspect
        else -> ratio
    }
}

/** Pure crop-rectangle maths in normalised frame coordinates (`0..1`). */
object CropMath {
    enum class Handle(val left: Boolean = false, val top: Boolean = false, val right: Boolean = false, val bottom: Boolean = false) {
        TOP_LEFT(left = true, top = true), TOP(top = true), TOP_RIGHT(top = true, right = true), RIGHT(right = true),
        BOTTOM_RIGHT(right = true, bottom = true), BOTTOM(bottom = true), BOTTOM_LEFT(left = true, bottom = true), LEFT(left = true),
        MOVE;

        val isCorner get() = (left || right) && (top || bottom)
    }

    const val MIN = 0.06f

    /**
     * The crop after dragging [handle] by ([dx], [dy]) from [start]. [ratio] locks the normalised
     * width/height (pixel ratio ÷ frame aspect). Edges within [snap] of the frame edge stick to it.
     */
    fun drag(start: CropRect, handle: Handle, dx: Float, dy: Float, ratio: Float?, snap: Float = 0f): CropRect {
        var l = start.left; var t = start.top; var r = start.right; var b = start.bottom
        fun s(v: Float) = when {
            v < snap -> 0f
            v > 1f - snap -> 1f
            else -> v
        }
        if (handle == Handle.MOVE) {
            val w = r - l; val h = b - t
            var nl = (l + dx).coerceIn(0f, 1f - w)
            var nt = (t + dy).coerceIn(0f, 1f - h)
            if (nl < snap) nl = 0f else if (nl + w > 1f - snap) nl = 1f - w
            if (nt < snap) nt = 0f else if (nt + h > 1f - snap) nt = 1f - h
            return CropRect(nl, nt, nl + w, nt + h)
        }
        if (ratio == null) {
            if (handle.left) l = s(l + dx).coerceIn(0f, r - MIN)
            if (handle.right) r = s(r + dx).coerceIn(l + MIN, 1f)
            if (handle.top) t = s(t + dy).coerceIn(0f, b - MIN)
            if (handle.bottom) b = s(b + dy).coerceIn(t + MIN, 1f)
            return CropRect(l, t, r, b)
        }
        if (handle.isCorner) {
            val ax = if (handle.left) r else l
            val ay = if (handle.top) b else t
            val px = (if (handle.left) l else r) + dx
            val py = (if (handle.top) t else b) + dy
            var w = abs(px - ax)
            var h = abs(py - ay)
            if (w / ratio > h) h = w / ratio else w = h * ratio
            val availW = if (handle.left) ax else 1f - ax
            val availH = if (handle.top) ay else 1f - ay
            if (w > availW) { w = availW; h = w / ratio }
            if (h > availH) { h = availH; w = h * ratio }
            if (w < MIN || h < MIN) { val k = maxOf(MIN / w, MIN / h); w *= k; h *= k }
            l = if (handle.left) ax - w else ax; r = l + w
            t = if (handle.top) ay - h else ay; b = t + h
            return CropRect(l, t, r, b).normalized()
        }
        // Edge with a locked ratio: scale about the centre of the other axis.
        val horizontal = handle.left || handle.right
        if (horizontal) {
            var w = if (handle.left) r - (l + dx) else (r + dx) - l
            val cy = (t + b) / 2f
            val maxH = 2f * min(cy, 1f - cy)
            val maxW = if (handle.left) r else 1f - l
            w = w.coerceIn(MIN, min(maxW, maxH * ratio))
            val h = w / ratio
            if (handle.left) l = r - w else r = l + w
            t = cy - h / 2f; b = cy + h / 2f
        } else {
            var h = if (handle.top) b - (t + dy) else (b + dy) - t
            val cx = (l + r) / 2f
            val maxW = 2f * min(cx, 1f - cx)
            val maxH = if (handle.top) b else 1f - t
            h = h.coerceIn(MIN, min(maxH, maxW / ratio))
            val w = h * ratio
            if (handle.top) t = b - h else b = t + h
            l = cx - w / 2f; r = cx + w / 2f
        }
        return CropRect(l, t, r, b).normalized()
    }

    /** The largest rect of normalised [ratio] centred on [current]'s centre that fits the frame. */
    fun fitAspect(current: CropRect, ratio: Float): CropRect {
        var w = 1f
        var h = w / ratio
        if (h > 1f) { h = 1f; w = h * ratio }
        var cx = (current.left + current.right) / 2f
        var cy = (current.top + current.bottom) / 2f
        cx = cx.coerceIn(w / 2f, 1f - w / 2f)
        cy = cy.coerceIn(h / 2f, 1f - h / 2f)
        return CropRect(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f).normalized()
    }

    /** Which frame edges [c] touches, as a bit mask (for snap haptics). */
    fun edgeMask(c: CropRect): Int =
        (if (c.left <= 0f) 1 else 0) or (if (c.top <= 0f) 2 else 0) or (if (c.right >= 1f) 4 else 0) or (if (c.bottom >= 1f) 8 else 0)
}

/** Crop handles, dimmed outside, thirds while dragging; drawn over the full frame in crop mode. */
@Composable
fun CropOverlay(session: EditorSession, recipe: State<Recipe>, modifier: Modifier) {
    val boundsF by session.bounds.collectAsState()
    val haptics = rememberHaptics()
    val reduced = rememberReducedMotion()
    var dragging by remember { mutableStateOf(false) }
    val grid by animateFloatAsState(if (dragging) 1f else 0f, motion(reduced, tween(160)), label = "grid")
    val crop by remember(recipe) { derivedStateOf { recipe.value.geometry.crop } }

    Canvas(
        modifier.pointerInput(session) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val bf = session.bounds.value ?: return@awaitEachGesture
                val b = Rect(bf.left, bf.top, bf.right, bf.bottom)
                val start = session.history.recipe.geometry.crop
                val handle = hitTest(start, b, down.position, 30.dp.toPx(), 22.dp.toPx()) ?: return@awaitEachGesture
                down.consume()
                session.history.beginGesture()
                dragging = true
                var mask = CropMath.edgeMask(start)
                val frame = session.frameAspect(session.history.recipe)
                val ratio = session.cropAspect.pixelRatio(frame)?.let { it / frame }
                val snap = 10.dp.toPx() / b.width
                try {
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        c.consume()
                        val d = c.position - down.position
                        val next = CropMath.drag(start, handle, d.x / b.width, d.y / b.height, ratio, snap)
                        val m = CropMath.edgeMask(next)
                        if (m and mask.inv() != 0) haptics.snap()
                        mask = m
                        val r = session.history.recipe
                        session.history.set(r.copy(geometry = r.geometry.copy(crop = next)))
                    }
                } finally {
                    dragging = false
                    session.history.endGesture()
                }
            }
        },
    ) {
        val bf = boundsF ?: return@Canvas
        val b = Rect(bf.left, bf.top, bf.right, bf.bottom)
        val r = Rect(b.left + crop.left * b.width, b.top + crop.top * b.height, b.left + crop.right * b.width, b.top + crop.bottom * b.height)
        // Dim outside the crop.
        val dim = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(0f, 0f, size.width, size.height))
            addRect(r)
        }
        drawPath(dim, Color.Black.copy(alpha = 0.62f))
        // Thirds while dragging.
        if (grid > 0f) {
            val gc = BrutalDark.Line.copy(alpha = 0.55f * grid)
            for (i in 1..2) {
                val x = r.left + r.width * i / 3f
                val y = r.top + r.height * i / 3f
                drawLine(gc, Offset(x, r.top), Offset(x, r.bottom), 1.dp.toPx())
                drawLine(gc, Offset(r.left, y), Offset(r.right, y), 1.dp.toPx())
            }
        }
        drawRect(BrutalDark.Line, r.topLeft, r.size, style = Stroke(1.5.dp.toPx()))
        cropHandles(r)
    }
}

private fun DrawScope.cropHandles(r: Rect) {
    val len = 22.dp.toPx()
    val w = 4.dp.toPx()
    val sh = 2.dp.toPx()
    val ink = Color(0xFF111111)
    fun bar(a: Offset, b: Offset) {
        drawLine(ink, a + Offset(sh, sh), b + Offset(sh, sh), w, StrokeCap.Square)
        drawLine(BrutalDark.Line, a, b, w, StrokeCap.Square)
    }
    val o = w / 2f
    // Corners: L shapes sitting just outside the crop line.
    bar(Offset(r.left - o, r.top - o), Offset(r.left - o + len, r.top - o)); bar(Offset(r.left - o, r.top - o), Offset(r.left - o, r.top - o + len))
    bar(Offset(r.right + o, r.top - o), Offset(r.right + o - len, r.top - o)); bar(Offset(r.right + o, r.top - o), Offset(r.right + o, r.top - o + len))
    bar(Offset(r.left - o, r.bottom + o), Offset(r.left - o + len, r.bottom + o)); bar(Offset(r.left - o, r.bottom + o), Offset(r.left - o, r.bottom + o - len))
    bar(Offset(r.right + o, r.bottom + o), Offset(r.right + o - len, r.bottom + o)); bar(Offset(r.right + o, r.bottom + o), Offset(r.right + o, r.bottom + o - len))
    // Edge handles.
    val e = 12.dp.toPx()
    if (r.width > 4 * len) {
        bar(Offset(r.center.x - e, r.top - o), Offset(r.center.x + e, r.top - o))
        bar(Offset(r.center.x - e, r.bottom + o), Offset(r.center.x + e, r.bottom + o))
    }
    if (r.height > 4 * len) {
        bar(Offset(r.left - o, r.center.y - e), Offset(r.left - o, r.center.y + e))
        bar(Offset(r.right + o, r.center.y - e), Offset(r.right + o, r.center.y + e))
    }
}

private fun hitTest(crop: CropRect, b: Rect, p: Offset, corner: Float, edge: Float): CropMath.Handle? {
    val l = b.left + crop.left * b.width
    val t = b.top + crop.top * b.height
    val r = b.left + crop.right * b.width
    val bt = b.top + crop.bottom * b.height
    val corners = listOf(
        CropMath.Handle.TOP_LEFT to Offset(l, t), CropMath.Handle.TOP_RIGHT to Offset(r, t),
        CropMath.Handle.BOTTOM_LEFT to Offset(l, bt), CropMath.Handle.BOTTOM_RIGHT to Offset(r, bt),
    )
    corners.minByOrNull { (it.second - p).getDistance() }?.let { if ((it.second - p).getDistance() < corner) return it.first }
    val inX = p.x in l..r
    val inY = p.y in t..bt
    val edges = listOfNotNull(
        (CropMath.Handle.LEFT to abs(p.x - l)).takeIf { inY },
        (CropMath.Handle.RIGHT to abs(p.x - r)).takeIf { inY },
        (CropMath.Handle.TOP to abs(p.y - t)).takeIf { inX },
        (CropMath.Handle.BOTTOM to abs(p.y - bt)).takeIf { inX },
    ).filter { it.second < edge }
    edges.minByOrNull { it.second }?.let { return it.first }
    if (inX && inY) return CropMath.Handle.MOVE
    return null
}

/** The Crop tool's controls: aspect presets, straighten dial, rotate/flip, perspective. */
@Composable
fun CropPanel(session: EditorSession, recipe: State<Recipe>, modifier: Modifier) {
    var page by rememberSaveable { mutableStateOf(0) }
    val haptics = rememberHaptics()
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DarkChip("CROP", page == 0, { page = 0 })
            DarkChip("PERSPECTIVE", page == 1, { page = 1 })
            Spacer(Modifier.weight(1f))
            DarkIconButton(Icons.Rounded.RestartAlt, "Reset crop & rotation", {
                session.cropAspect = AspectPreset.FREE
                session.history.set(session.history.recipe.copy(geometry = Geometry()))
            }, enabled = !recipe.value.geometry.isIdentity)
        }
        if (page == 0) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for (a in AspectPreset.entries) {
                    DarkChip(a.label, session.cropAspect == a, {
                        session.cropAspect = a
                        val r = session.history.recipe
                        val frame = session.frameAspect(r)
                        a.pixelRatio(frame)?.let { px ->
                            session.history.set(r.copy(geometry = r.geometry.copy(crop = CropMath.fitAspect(r.geometry.crop, px / frame))))
                        }
                    })
                }
            }
            val straighten by remember(recipe) { derivedStateOf { recipe.value.geometry.straighten } }
            StraightenDial(
                value = straighten,
                onBegin = { session.history.beginGesture() },
                onChange = { v -> session.history.update { it.copy(geometry = it.geometry.copy(straighten = v)) } },
                onEnd = { session.history.endGesture() },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                fun geo(f: (Geometry) -> Geometry, rotates: Boolean) {
                    if (rotates && session.cropAspect != AspectPreset.SQUARE && session.cropAspect != AspectPreset.ORIGINAL) session.cropAspect = AspectPreset.FREE
                    session.history.update { it.copy(geometry = f(it.geometry)) }
                    haptics.tick()
                }
                DarkIconButton(Icons.Rounded.RotateLeft, "Rotate left", { geo(Geometry::rotatedCcw, true) })
                DarkIconButton(Icons.Rounded.RotateRight, "Rotate right", { geo(Geometry::rotatedCw, true) })
                DarkIconButton(Icons.Rounded.Flip, "Flip horizontally", { geo(Geometry::flippedHorizontally, false) })
                DarkIconButton(Icons.Rounded.SwapVert, "Flip vertically", { geo(Geometry::flippedVertically, false) })
            }
        } else {
            SessionSlider(session, recipe, Params.perspectiveVertical)
            SessionSlider(session, recipe, Params.perspectiveHorizontal)
            SessionSlider(session, recipe, Params.straighten)
        }
    }
}

/**
 * A ruler dial for straighten: drag to turn, a tick under the finger at each whole degree, a firm
 * detent at 0°. Double tap levels it.
 */
@Composable
fun StraightenDial(value: Float, onBegin: () -> Unit, onChange: (Float) -> Unit, onEnd: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberHaptics()
    val measurer = rememberTextMeasurer()
    val current by androidx.compose.runtime.rememberUpdatedState(value)
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("STRAIGHTEN", style = Type.Label, color = if (value != 0f) BrutalDark.Text else BrutalDark.MutedText, modifier = Modifier.weight(1f))
            Text(formatValue(Params.straighten, value), style = Type.Label, color = if (value != 0f) BrutalDark.Orange else BrutalDark.MutedText)
        }
        Spacer(Modifier.height(4.dp))
        Canvas(
            Modifier.fillMaxWidth().height(52.dp)
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { onBegin(); onChange(0f); onEnd(); haptics.snap() })
                }
                .pointerInput(Unit) {
                    var raw = 0f
                    var last = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { raw = current; last = current; onBegin() },
                        onDragEnd = onEnd,
                        onDragCancel = onEnd,
                    ) { ch, dx ->
                        ch.consume()
                        raw = (raw - dx / PX_PER_DEG.dp.toPx()).coerceIn(Ranges.STRAIGHTEN)
                        val out = if (abs(raw) < 0.5f) 0f else (raw * 10f).roundToInt() / 10f
                        if (out != last) {
                            if (out == 0f) haptics.snap()
                            else if (floor(out) != floor(last)) haptics.tick()
                            last = out
                            onChange(out)
                        }
                    }
                },
        ) {
            val cx = size.width / 2f
            val ppd = PX_PER_DEG.dp.toPx()
            val base = size.height - 16.dp.toPx()
            val first = floor(value - cx / ppd).toInt().coerceAtLeast(-45)
            val last = (value + cx / ppd).toInt().coerceAtMost(45)
            for (d in first..last) {
                val x = cx + (d - value) * ppd
                val major = d % 15 == 0
                val mid = d % 5 == 0
                val h = if (major) 22.dp.toPx() else if (mid) 14.dp.toPx() else 8.dp.toPx()
                val fade = 1f - (abs(x - cx) / cx).coerceIn(0f, 1f) * 0.8f
                drawLine(BrutalDark.MutedText.copy(alpha = fade), Offset(x, base - h), Offset(x, base), if (major) 2.dp.toPx() else 1.dp.toPx())
                if (major) {
                    val t = measurer.measure(if (d > 0) "+$d" else "$d", Type.Tiny.copy(color = BrutalDark.MutedText.copy(alpha = fade)))
                    drawText(t, topLeft = Offset(x - t.size.width / 2f, base + 3.dp.toPx()))
                }
            }
            // Centre needle with a hard shadow.
            val nTop = base - 30.dp.toPx()
            drawLine(Color.Black, Offset(cx + 2.dp.toPx(), nTop + 2.dp.toPx()), Offset(cx + 2.dp.toPx(), base + 2.dp.toPx()), 3.dp.toPx())
            drawLine(BrutalDark.Orange, Offset(cx, nTop), Offset(cx, base), 3.dp.toPx())
            val tri = Path().apply {
                moveTo(cx - 6.dp.toPx(), nTop - 2.dp.toPx()); lineTo(cx + 6.dp.toPx(), nTop - 2.dp.toPx()); lineTo(cx, nTop + 6.dp.toPx()); close()
            }
            drawPath(tri, BrutalDark.Orange)
        }
    }
}

private const val PX_PER_DEG = 9
