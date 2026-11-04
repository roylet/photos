package com.roylet.photos.editor.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.roylet.photos.editor.recipe.Recipe
import com.roylet.photos.editor.recipe.RecipeEdits
import com.roylet.photos.editor.recipe.SliderParam
import com.roylet.photos.ui.theme.BrutalDark
import com.roylet.photos.ui.theme.Type
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign

/** "+0.35 EV", "-12", "+1.5°", "0". */
fun formatValue(param: SliderParam, v: Float): String {
    val span = param.range.endInclusive - param.range.start
    val decimals = when {
        span <= 10f -> 2
        param.unit == "°" -> 1
        else -> 0
    }
    val factor = 10f.pow(decimals)
    val steps = (v * factor).roundToInt()
    val num = if (decimals == 0) steps.toString() else String.format(Locale.US, "%.${decimals}f", steps / factor)
    val signed = when {
        steps == 0 -> num.trimStart('-')
        steps > 0 && param.range.start < 0f -> "+$num"
        else -> num
    }
    return signed + when (param.unit) { "" -> ""; "°" -> "°"; else -> " ${param.unit}" }
}

/**
 * A big editor slider for one [SliderParam]. Dragging is relative (the thumb moves with the
 * finger, never jumps to it), sticks briefly at zero with a haptic tick when it crosses, and the
 * whole drag is one undo step. Double tap resets.
 *
 * @param track optional gradient for the track (HSL hue/saturation/luminance).
 */
@Composable
fun EditorSlider(
    param: SliderParam,
    recipe: State<Recipe>,
    onBegin: () -> Unit,
    onChange: (Recipe) -> Unit,
    onEnd: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
    track: Brush? = null,
) {
    val value by remember(param, recipe) { derivedStateOf { param.get(recipe.value) } }
    ValueSlider(
        label = param.label,
        value = value,
        range = param.range,
        default = param.default,
        format = { formatValue(param, it) },
        onBegin = onBegin,
        onChange = { v -> onChange(param.set(recipe.value, v)) },
        onEnd = onEnd,
        onReset = onReset,
        modifier = modifier,
        track = track,
    )
}

/**
 * The editor slider over a plain value (the look and feel of [EditorSlider], for values that
 * aren't in a [Recipe], e.g. video speed and volume). [zero] is where the fill starts and the
 * detent sits (default: 0 for two-sided ranges, else the left end). [enabled] false dims it and
 * ignores touches.
 */
@Composable
fun ValueSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    default: Float,
    format: (Float) -> String,
    onBegin: () -> Unit,
    onChange: (Float) -> Unit,
    onEnd: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
    track: Brush? = null,
    zero: Float = if (range.start < 0f) 0f else range.start,
    enabled: Boolean = true,
) {
    var dragging by remember { mutableStateOf(false) }
    val reduced = rememberReducedMotion()
    val haptics = rememberHaptics()
    val current by rememberUpdatedState(value)
    val latestOnChange by rememberUpdatedState(onChange)
    // Undo/reset glide; drags follow the finger exactly.
    val shown by animateFloatAsState(value, if (dragging || reduced) snap() else tween(220), label = "value")
    val lift by animateFloatAsState(if (dragging) 1f else 0f, if (reduced) snap() else spring(dampingRatio = 0.6f, stiffness = 900f), label = "lift")
    val lo = range.start
    val hi = range.endInclusive
    val span = hi - lo
    val changed = abs(value - default) > 1e-4f

    Column(
        modifier
            .fillMaxWidth()
            .semantics { contentDescription = "$label ${format(value)}" }
            .then(if (!enabled) Modifier.graphicsLayer { alpha = 0.38f } else Modifier)
            .pointerInput(label, enabled) { if (enabled) detectTapGestures(onDoubleTap = { onReset(); haptics.tick() }) }
            .pointerInput(label, range, enabled) {
                if (!enabled) return@pointerInput
                var raw = 0f
                var last = 0f
                detectHorizontalDragGestures(
                    onDragStart = {
                        dragging = true
                        raw = current
                        last = raw
                        onBegin()
                    },
                    onDragEnd = { dragging = false; onEnd() },
                    onDragCancel = { dragging = false; onEnd() },
                ) { change, dx ->
                    change.consume()
                    val trackW = (size.width - 24.dp.toPx()).coerceAtLeast(1f)
                    raw = (raw + dx / trackW * span).coerceIn(lo, hi)
                    // A soft detent at zero.
                    val out = if (abs(raw - zero) < span * 0.012f) zero else raw
                    if (out != last) {
                        val crossed = (out == zero && last != zero) || sign(out - zero) * sign(last - zero) < 0f
                        if (crossed) haptics.tick()
                        last = out
                        latestOnChange(out)
                    }
                }
            }
            .padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label.uppercase(), style = Type.Label, color = if (changed) BrutalDark.Text else BrutalDark.MutedText, modifier = Modifier.weight(1f))
            Text(
                format(shown),
                style = Type.Label.copy(fontSize = Type.Label.fontSize * 1.1f),
                color = if (changed || dragging) BrutalDark.Orange else BrutalDark.MutedText,
                modifier = Modifier.graphicsLayer {
                    val s = 1f + 0.18f * lift
                    scaleX = s; scaleY = s
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0.5f)
                },
            )
        }
        Box(
            Modifier.fillMaxWidth().height(36.dp).drawBehind {
                val pad = 12.dp.toPx()
                val w = size.width - 2 * pad
                val cy = size.height / 2f
                val th = 8.dp.toPx()
                val r = CornerRadius(th / 2f)
                val stroke = 2.dp.toPx()
                fun x(v: Float) = pad + (v - lo) / span * w
                // Track
                if (track != null) drawRoundRect(track, Offset(pad, cy - th / 2), Size(w, th), r)
                else drawRoundRect(BrutalDark.Raised, Offset(pad, cy - th / 2), Size(w, th), r)
                // Active fill from zero to the value
                if (track == null) {
                    val a = x(zero); val b = x(shown)
                    if (abs(b - a) > 0.5f) drawRect(BrutalDark.Orange, Offset(minOf(a, b), cy - th / 2 + stroke / 2), Size(abs(b - a), th - stroke))
                }
                drawRoundRect(BrutalDark.Line.copy(alpha = 0.85f), Offset(pad, cy - th / 2), Size(w, th), r, style = Stroke(stroke))
                // Zero tick
                val zx = x(zero)
                drawLine(BrutalDark.Line, Offset(zx, cy - 11.dp.toPx()), Offset(zx, cy - th / 2 - 2.dp.toPx()), stroke)
                drawLine(BrutalDark.Line, Offset(zx, cy + th / 2 + 2.dp.toPx()), Offset(zx, cy + 11.dp.toPx()), stroke)
                // Thumb: a square with a hard shadow; lifts while dragging.
                val tx = x(shown)
                val ts = (20.dp.toPx()) * (1f + 0.15f * lift)
                val sh = 3.dp.toPx() * (1f - lift) + 1.dp.toPx()
                val tl = Offset(tx - ts / 2, cy - ts / 2)
                val cr = CornerRadius(4.dp.toPx())
                drawRoundRect(Color.Black, tl + Offset(sh, sh), Size(ts, ts), cr)
                drawRoundRect(if (dragging) BrutalDark.Orange else BrutalDark.Text, tl, Size(ts, ts), cr)
                drawRoundRect(Color(0xFF111111), tl, Size(ts, ts), cr, style = Stroke(stroke))
            },
        )
    }
}

/** A scrolling column of [EditorSlider]s wired to the session's history. */
@Composable
fun SliderPanel(session: EditorSession, recipe: State<Recipe>, params: List<SliderParam>, modifier: Modifier = Modifier) =
    SliderPanel(session.history, recipe, params, modifier)

/** A scrolling column of [EditorSlider]s wired to [edits] (the photo history or a video edit's colour). */
@Composable
fun SliderPanel(edits: RecipeEdits, recipe: State<Recipe>, params: List<SliderParam>, modifier: Modifier = Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (p in params) SessionSlider(edits, recipe, p)
    }
}

@Composable
fun SessionSlider(session: EditorSession, recipe: State<Recipe>, param: SliderParam, track: Brush? = null) =
    SessionSlider(session.history, recipe, param, track)

@Composable
fun SessionSlider(edits: RecipeEdits, recipe: State<Recipe>, param: SliderParam, track: Brush? = null) {
    EditorSlider(
        param, recipe,
        onBegin = { edits.beginGesture() },
        onChange = { edits.set(it) },
        onEnd = { edits.endGesture() },
        onReset = { edits.set(param.reset(edits.recipe)) },
        track = track,
    )
}
