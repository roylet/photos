package com.roylet.photos.editor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.roylet.photos.editor.recipe.CurveMath
import com.roylet.photos.editor.recipe.CurvePoint
import com.roylet.photos.editor.recipe.Curves
import com.roylet.photos.editor.recipe.Recipe
import com.roylet.photos.editor.recipe.RecipeEdits
import com.roylet.photos.ui.components.DarkChip
import com.roylet.photos.ui.components.DarkIconButton
import com.roylet.photos.ui.theme.BrutalDark
import com.roylet.photos.ui.theme.Type

/** Tone-curve channels. */
enum class CurveChannel(val label: String, val color: Color) {
    RGB("RGB", BrutalDark.Text),
    RED("R", Color(0xFFFF5C5C)),
    GREEN("G", Color(0xFF4CD787)),
    BLUE("B", Color(0xFF5B8CFF));

    fun get(c: Curves) = when (this) { RGB -> c.rgb; RED -> c.red; GREEN -> c.green; BLUE -> c.blue }
    fun set(c: Curves, p: List<CurvePoint>) = when (this) {
        RGB -> c.copy(rgb = p); RED -> c.copy(red = p); GREEN -> c.copy(green = p); BLUE -> c.copy(blue = p)
    }
}

/** Point editing on one curve: pure, so the gesture code stays small. */
object CurveEdit {
    const val GAP = 0.02f

    /** Inserts (x, y) in x order; returns the new list and the point's index. */
    fun add(points: List<CurvePoint>, x: Float, y: Float): Pair<List<CurvePoint>, Int> {
        val p = CurveMath.normalize(points)
        val i = p.indexOfFirst { it.x > x }.let { if (it < 0) p.size else it }
        if (i == 0 || i == p.size) return p to (if (i == 0) 0 else p.lastIndex)
        if (x - p[i - 1].x < GAP || p[i].x - x < GAP) return p to (if (x - p[i - 1].x < p[i].x - x) i - 1 else i)
        return (p.take(i) + CurvePoint(x, y.coerceIn(0f, 1f)) + p.drop(i)) to i
    }

    /** Moves point [i] to (x, y), kept between its neighbours. */
    fun move(points: List<CurvePoint>, i: Int, x: Float, y: Float): List<CurvePoint> {
        val lo = if (i == 0) 0f else points[i - 1].x + GAP
        val hi = if (i == points.lastIndex) 1f else points[i + 1].x - GAP
        val nx = if (lo > hi) points[i].x else x.coerceIn(lo, hi)
        return points.toMutableList().also { it[i] = CurvePoint(nx, y.coerceIn(0f, 1f)) }
    }

    /** Inner points can be removed; the two ends always stay. */
    fun canRemove(points: List<CurvePoint>, i: Int) = i in 1 until points.lastIndex
}

@Composable
fun CurvePanel(session: EditorSession, recipe: State<Recipe>, modifier: Modifier) = CurvePanel(session.history, recipe, modifier)

@Composable
fun CurvePanel(edits: RecipeEdits, recipe: State<Recipe>, modifier: Modifier) {
    var channelIndex by rememberSaveable { mutableIntStateOf(0) }
    val channel = CurveChannel.entries[channelIndex]
    val curves by remember(recipe) { derivedStateOf { recipe.value.curves } }
    Column(modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            for ((i, c) in CurveChannel.entries.withIndex()) {
                DarkChip(c.label, channelIndex == i, { channelIndex = i })
            }
            Spacer(Modifier.weight(1f))
            DarkIconButton(Icons.Rounded.RestartAlt, "Reset ${channel.label} curve", {
                edits.update { it.copy(curves = channel.set(it.curves, Curves.IDENTITY)) }
            }, enabled = !CurveMath.isIdentity(channel.get(curves)))
        }
        CurveGraph(edits, curves, channel, Modifier.fillMaxWidth().weight(1f))
        Text("TAP TO ADD · DRAG TO SHAPE · DRAG OFF TO REMOVE", style = Type.Tiny, color = BrutalDark.MutedText, modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}

@Composable
private fun CurveGraph(edits: RecipeEdits, curves: Curves, channel: CurveChannel, modifier: Modifier) {
    val haptics = rememberHaptics()
    var active by remember { mutableIntStateOf(-1) }
    val ch by rememberUpdatedState(channel)
    Canvas(
        modifier.pointerInput(Unit) {
            awaitEachGesture {
                val pad = 10.dp.toPx()
                val w = size.width - 2 * pad
                val h = size.height - 2 * pad
                fun toCurve(o: Offset) = Offset((o.x - pad) / w, 1f - (o.y - pad) / h)
                fun toView(p: CurvePoint) = Offset(pad + p.x * w, pad + (1f - p.y) * h)
                val down = awaitFirstDown()
                down.consume()
                val c = ch
                val start = CurveMath.normalize(c.get(edits.recipe.curves))
                val hit = start.indices.minByOrNull { (toView(start[it]) - down.position).getDistance() }
                    ?.takeIf { (toView(start[it]) - down.position).getDistance() < 28.dp.toPx() }
                edits.beginGesture()
                var points: List<CurvePoint>
                var index: Int
                if (hit != null) {
                    points = start; index = hit
                } else {
                    val q = toCurve(down.position)
                    val added = CurveEdit.add(start, q.x.coerceIn(0f, 1f), q.y)
                    points = added.first; index = added.second
                    haptics.tick()
                }
                fun commit(list: List<CurvePoint>) {
                    edits.update { it.copy(curves = c.set(it.curves, list)) }
                }
                commit(points)
                active = index
                var removed = false
                val off = 36.dp.toPx()
                try {
                    while (true) {
                        val e = awaitPointerEvent()
                        val change = e.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        change.consume()
                        val pos = change.position
                        val outside = pos.x < -off || pos.x > size.width + off || pos.y < -off || pos.y > size.height + off
                        if (outside && CurveEdit.canRemove(points, index)) {
                            if (!removed) { removed = true; haptics.snap(); active = -1 }
                            commit(points.filterIndexed { i, _ -> i != index })
                        } else {
                            if (removed) { removed = false; active = index }
                            val q = toCurve(pos)
                            points = CurveEdit.move(points, index, q.x, q.y)
                            commit(points)
                        }
                    }
                } finally {
                    active = -1
                    edits.endGesture()
                }
            }
        },
    ) {
        val pad = 10.dp.toPx()
        val w = size.width - 2 * pad
        val h = size.height - 2 * pad
        val stroke = 2.dp.toPx()
        // Frame and quarter grid.
        drawRoundRect(BrutalDark.Raised, Offset(pad, pad), Size(w, h), CornerRadius(4.dp.toPx()))
        for (i in 1..3) {
            val gx = pad + w * i / 4f
            val gy = pad + h * i / 4f
            drawLine(BrutalDark.MutedText.copy(alpha = 0.25f), Offset(gx, pad), Offset(gx, pad + h), 1.dp.toPx())
            drawLine(BrutalDark.MutedText.copy(alpha = 0.25f), Offset(pad, gy), Offset(pad + w, gy), 1.dp.toPx())
        }
        drawLine(BrutalDark.MutedText.copy(alpha = 0.35f), Offset(pad, pad + h), Offset(pad + w, pad), 1.dp.toPx())
        // Other channels, faint.
        for (other in CurveChannel.entries) {
            if (other == channel || CurveMath.isIdentity(other.get(curves))) continue
            drawCurve(other.get(curves), other.color.copy(alpha = 0.4f), pad, w, h, 1.5.dp.toPx())
        }
        val pts = CurveMath.normalize(channel.get(curves))
        drawCurve(pts, channel.color, pad, w, h, 3.dp.toPx())
        drawRoundRect(BrutalDark.Line, Offset(pad, pad), Size(w, h), CornerRadius(4.dp.toPx()), style = Stroke(stroke))
        // Points: squares with a hard shadow.
        val s = 13.dp.toPx()
        for ((i, p) in pts.withIndex()) {
            val c = Offset(pad + p.x * w, pad + (1f - p.y) * h)
            val tl = c - Offset(s / 2, s / 2)
            drawRect(Color.Black, tl + Offset(2.dp.toPx(), 2.dp.toPx()), Size(s, s))
            drawRect(if (i == active) BrutalDark.Orange else BrutalDark.Text, tl, Size(s, s))
            drawRect(Color(0xFF111111), tl, Size(s, s), style = Stroke(stroke))
        }
    }
}

private fun DrawScope.drawCurve(points: List<CurvePoint>, color: Color, pad: Float, w: Float, h: Float, width: Float) {
    val lut = CurveMath.bake(points, 96)
    val path = Path()
    for (i in lut.indices) {
        val x = pad + w * i / (lut.size - 1)
        val y = pad + (1f - lut[i].coerceIn(0f, 1f)) * h
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, color, style = Stroke(width))
}
