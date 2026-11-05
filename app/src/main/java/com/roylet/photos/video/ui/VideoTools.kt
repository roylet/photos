package com.roylet.photos.video.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.RotateLeft
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.roylet.photos.editor.recipe.ParamGroup
import com.roylet.photos.editor.recipe.Params
import com.roylet.photos.editor.recipe.Recipe
import com.roylet.photos.editor.ui.AspectPreset
import com.roylet.photos.editor.ui.CropMath
import com.roylet.photos.editor.ui.CurvePanel
import com.roylet.photos.editor.ui.FiltersPanel
import com.roylet.photos.editor.ui.HslPanel
import com.roylet.photos.editor.ui.SessionSlider
import com.roylet.photos.editor.ui.ValueSlider
import com.roylet.photos.editor.ui.cropHandles
import com.roylet.photos.editor.ui.formatValue
import com.roylet.photos.editor.ui.hitTest
import com.roylet.photos.editor.ui.motion
import com.roylet.photos.editor.ui.rememberHaptics
import com.roylet.photos.editor.ui.rememberReducedMotion
import com.roylet.photos.ui.components.DarkChip
import com.roylet.photos.ui.components.DarkIconButton
import com.roylet.photos.ui.components.Pressable
import com.roylet.photos.ui.components.brutalDark
import com.roylet.photos.ui.theme.Brutal
import com.roylet.photos.ui.theme.BrutalDark
import com.roylet.photos.ui.theme.Type
import com.roylet.photos.util.Format
import com.roylet.photos.video.export.CompositionPlanner
import com.roylet.photos.video.export.EncoderCaps
import com.roylet.photos.video.export.SourceInfo
import com.roylet.photos.video.model.AudioSettings
import com.roylet.photos.video.model.OutputQuality
import com.roylet.photos.video.model.OutputResolution
import com.roylet.photos.video.model.VideoEdit
import com.roylet.photos.video.model.VideoGeometry
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

// ---- Crop ------------------------------------------------------------------------------------

/** The aspect presets video offers (no 3:2). */
val VideoAspects = listOf(AspectPreset.FREE, AspectPreset.ORIGINAL, AspectPreset.L16_9, AspectPreset.P9_16, AspectPreset.SQUARE, AspectPreset.P4_5)

/** Aspect (w/h) of the frame the crop lives in: the upright source after quarter turns. */
fun frameAspect(session: VideoEditorSession, g: VideoGeometry): Float =
    if (g.rotate90.mod(2) == 1) session.height.toFloat() / session.width else session.width.toFloat() / session.height

/** Where a frame of [aspect] sits when fitted (letterboxed) into [w]×[h]. */
fun fitRect(aspect: Float, w: Float, h: Float): Rect {
    if (w <= 0f || h <= 0f) return Rect.Zero
    val fw: Float
    val fh: Float
    if (w / h > aspect) { fh = h; fw = h * aspect } else { fw = w; fh = w / aspect }
    return Rect((w - fw) / 2f, (h - fh) / 2f, (w + fw) / 2f, (h + fh) / 2f)
}

/** The photo crop handles over the (uncropped) video frame; dims outside, thirds while dragging. */
@Composable
fun VideoCropOverlay(session: VideoEditorSession, edit: State<VideoEdit>, modifier: Modifier) {
    val haptics = rememberHaptics()
    val reduced = rememberReducedMotion()
    var dragging by remember { mutableStateOf(false) }
    val grid by animateFloatAsState(if (dragging) 1f else 0f, motion(reduced, tween(160)), label = "grid")
    Canvas(
        modifier.pointerInput(session) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val g0 = session.history.edit.geometry
                val b = fitRect(frameAspect(session, g0), size.width.toFloat(), size.height.toFloat())
                val start = g0.crop
                val handle = hitTest(start, b, down.position, 30.dp.toPx(), 22.dp.toPx()) ?: return@awaitEachGesture
                down.consume()
                session.history.beginGesture()
                dragging = true
                var mask = CropMath.edgeMask(start)
                val frame = frameAspect(session, g0)
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
                        session.history.update { it.copy(geometry = it.geometry.copy(crop = next)) }
                    }
                } finally {
                    dragging = false
                    session.history.endGesture()
                }
            }
        },
    ) {
        val g = edit.value.geometry
        val crop = g.crop
        val b = fitRect(frameAspect(session, g), size.width, size.height)
        val r = Rect(b.left + crop.left * b.width, b.top + crop.top * b.height, b.left + crop.right * b.width, b.top + crop.bottom * b.height)
        val dim = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(0f, 0f, size.width, size.height))
            addRect(r)
        }
        drawPath(dim, Color.Black.copy(alpha = 0.62f))
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

@Composable
fun VideoCropPanel(session: VideoEditorSession, edit: State<VideoEdit>, modifier: Modifier) {
    val haptics = rememberHaptics()
    val g = edit.value.geometry
    ToolColumn(modifier) {
        PanelHeader("Aspect") {
            DarkIconButton(Icons.Rounded.RestartAlt, "Reset crop & rotation", {
                session.cropAspect = AspectPreset.FREE
                session.history.update { it.copy(geometry = VideoGeometry()) }
            }, enabled = !g.isIdentity)
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for (a in VideoAspects) {
                DarkChip(a.label, session.cropAspect == a, {
                    session.cropAspect = a
                    val cur = session.history.edit.geometry
                    val frame = frameAspect(session, cur)
                    a.pixelRatio(frame)?.let { px ->
                        session.history.update { it.copy(geometry = it.geometry.copy(crop = CropMath.fitAspect(it.geometry.crop, px / frame))) }
                    }
                    haptics.tick()
                })
            }
        }
        Spacer(Modifier.height(4.dp))
        PanelHeader("Rotate & flip")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            fun geo(f: (VideoGeometry) -> VideoGeometry, rotates: Boolean) {
                if (rotates && session.cropAspect != AspectPreset.SQUARE && session.cropAspect != AspectPreset.ORIGINAL) session.cropAspect = AspectPreset.FREE
                session.history.update { it.copy(geometry = f(it.geometry)) }
                haptics.tick()
            }
            DarkIconButton(Icons.Rounded.RotateLeft, "Rotate left", { geo(VideoGeometry::rotatedCcw, true) })
            DarkIconButton(Icons.Rounded.RotateRight, "Rotate right", { geo(VideoGeometry::rotatedCw, true) })
            DarkIconButton(Icons.Rounded.Flip, "Flip horizontally", { geo(VideoGeometry::flippedHorizontally, false) }, active = g.flipH)
            DarkIconButton(Icons.Rounded.SwapVert, "Flip vertically", { geo(VideoGeometry::flippedVertically, false) }, active = g.flipV)
            Spacer(Modifier.weight(1f))
            val (ow, oh) = g.outputSize(session.width, session.height)
            Column(horizontalAlignment = Alignment.End) {
                Text("OUTPUT", style = Type.Tiny, color = BrutalDark.MutedText)
                Text("$ow × $oh", style = Type.Label, color = BrutalDark.Text)
            }
        }
    }
}

// ---- Speed -----------------------------------------------------------------------------------

/** The chips the Speed tool offers. */
val SpeedChips = listOf(0.25f, 0.5f, 1f, 1.5f, 2f, 4f)

/** The fine speed slider is logarithmic: `u = log4(speed)`, `-1..1`, 0 = 1×. */
object SpeedScale {
    fun toSlider(speed: Float): Float = (ln(speed.toDouble()) / ln(4.0)).toFloat().coerceIn(-1f, 1f)
    fun fromSlider(u: Float): Float {
        val s = 4.0.pow(u.toDouble()).toFloat()
        // Round to 0.05× so values read cleanly and chips light up.
        return ((s * 20f).roundToInt() / 20f).coerceIn(VideoEdit.SPEED_RANGE)
    }
    fun format(speed: Float): String = if (abs(speed - speed.roundToInt()) < 1e-3f) "${speed.roundToInt()}×"
    else String.format(Locale.US, "%.2f×", speed).replace(Regex("0×$"), "×")
}

@Composable
fun SpeedPanel(session: VideoEditorSession, edit: State<VideoEdit>, modifier: Modifier) {
    val haptics = rememberHaptics()
    val e = edit.value
    ToolColumn(modifier) {
        PanelHeader("Speed") {
            Column(horizontalAlignment = Alignment.End) {
                Text("NEW LENGTH", style = Type.Tiny, color = BrutalDark.MutedText)
                Text(
                    "${TrimMath.formatDuration(e.keptSourceMs)} → ${TrimMath.formatDuration(e.outputDurationMs())}",
                    style = Type.Label, color = if (e.speed != 1f) BrutalDark.Orange else BrutalDark.Text,
                )
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for (s in SpeedChips) {
                DarkChip(SpeedScale.format(s), abs(e.speed - s) < 1e-3f, {
                    session.history.update { it.withSpeed(s) }
                    haptics.tick()
                })
            }
        }
        ValueSlider(
            label = "Fine speed",
            value = SpeedScale.toSlider(e.speed),
            range = -1f..1f,
            default = 0f,
            format = { SpeedScale.format(SpeedScale.fromSlider(it)) },
            onBegin = { session.history.beginGesture() },
            onChange = { u -> session.history.update { it.withSpeed(SpeedScale.fromSlider(u)) } },
            onEnd = { session.history.endGesture() },
            onReset = { session.history.update { it.withSpeed(1f) } },
        )
        Hint("Audio keeps its pitch. Slow motion works best on high frame rate clips.")
    }
}

// ---- Audio -----------------------------------------------------------------------------------

@Composable
fun AudioPanel(session: VideoEditorSession, edit: State<VideoEdit>, modifier: Modifier) {
    val haptics = rememberHaptics()
    val a = edit.value.audio
    val hasAudio = session.source.hasAudio
    ToolColumn(modifier) {
        Pressable(
            { session.history.update { it.copy(audio = it.audio.copy(mute = !it.audio.mute)) }; haptics.tick() },
            Modifier.fillMaxWidth(),
            fill = if (a.mute) BrutalDark.Orange else BrutalDark.Raised,
            shadow = 4.dp, border = if (a.mute) Brutal.Ink else BrutalDark.Line, shadowColor = Color.Black,
            enabled = hasAudio, disabledBlend = BrutalDark.Canvas,
        ) {
            val ink = if (a.mute) Brutal.Ink else BrutalDark.Text
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (a.mute) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp, null, tint = ink, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (a.mute) "MUTED" else "SOUND ON", style = Type.Title, color = ink)
                    Text(
                        if (!hasAudio) "This video has no sound" else if (a.mute) "The saved video has no audio track" else "Tap to remove the sound",
                        style = Type.Small, color = ink.copy(alpha = 0.75f),
                    )
                }
                Toggle(a.mute)
            }
        }
        ValueSlider(
            label = "Volume",
            value = a.volume,
            range = AudioSettings.VOLUME_RANGE,
            default = 100f,
            zero = 100f,
            format = { "${it.roundToInt()}%" },
            onBegin = { session.history.beginGesture() },
            onChange = { v -> session.history.update { it.copy(audio = it.audio.copy(volume = v.roundToInt().toFloat())) } },
            onEnd = { session.history.endGesture() },
            onReset = { session.history.update { it.copy(audio = it.audio.copy(volume = 100f)) } },
            enabled = hasAudio && !a.mute,
        )
        Hint("Above 100% boosts quiet clips; loud parts may clip.")
    }
}

@Composable
private fun Toggle(on: Boolean) {
    val reduced = rememberReducedMotion()
    val t by animateFloatAsState(if (on) 1f else 0f, motion(reduced, androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = 700f)), label = "toggle")
    Box(Modifier.size(52.dp, 30.dp).brutalDark(fill = if (on) Brutal.Ink else BrutalDark.Canvas, shadow = 0.dp, border = if (on) Brutal.Ink else BrutalDark.Line, shape = androidx.compose.foundation.shape.RoundedCornerShape(50))) {
        Box(
            Modifier.padding(4.dp).size(22.dp)
                .offset(x = (t * 22f).dp)
                .background(if (on) BrutalDark.Orange else BrutalDark.Text, androidx.compose.foundation.shape.CircleShape),
        )
    }
}


// ---- Adjust ----------------------------------------------------------------------------------

enum class AdjustPage(val label: String) { LIGHT("Light"), COLOR("Color"), CURVE("Curve"), HSL("HSL"), EFFECTS("Effects") }

/** Effects video renders; clarity, dehaze and sharpen don't yet (they need a blurred copy per frame). */
private val VideoEffects = listOf(Params.vignette, Params.grain)
private val PhotoOnlyEffects = listOf(Params.clarity, Params.dehaze, Params.sharpen)

@Composable
fun AdjustPanel(session: VideoEditorSession, edit: State<VideoEdit>, modifier: Modifier, initialPage: AdjustPage = AdjustPage.LIGHT) {
    var page by rememberSaveable { mutableIntStateOf(initialPage.ordinal) }
    val recipe = remember(edit) { derivedStateOf { edit.value.color } }
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            for (p in AdjustPage.entries) {
                val edited by remember(p) { derivedStateOf { p.isEdited(recipe.value) } }
                DarkChip(if (edited) "${p.label} •" else p.label, page == p.ordinal, { page = p.ordinal })
            }
        }
        val m = Modifier.weight(1f).fillMaxWidth()
        when (AdjustPage.entries[page]) {
            AdjustPage.LIGHT -> com.roylet.photos.editor.ui.SliderPanel(session.colorEdits, recipe, Params.group(ParamGroup.LIGHT), m)
            AdjustPage.COLOR -> com.roylet.photos.editor.ui.SliderPanel(session.colorEdits, recipe, Params.group(ParamGroup.COLOR), m)
            AdjustPage.CURVE -> CurvePanel(session.colorEdits, recipe, m)
            AdjustPage.HSL -> HslPanel(session.colorEdits, recipe, m)
            AdjustPage.EFFECTS -> Column(m.verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp)) {
                for (p in VideoEffects) SessionSlider(session.colorEdits, recipe, p)
                for (p in PhotoOnlyEffects) {
                    ValueSlider(p.label, p.default, p.range, p.default, { formatValue(p, it) }, {}, {}, {}, {}, enabled = false)
                }
                Hint("Clarity, dehaze and sharpen are photo-only for now.", Modifier.padding(horizontal = 8.dp))
            }
        }
    }
}

private fun AdjustPage.isEdited(r: Recipe): Boolean = when (this) {
    AdjustPage.LIGHT -> !r.light.isIdentity
    AdjustPage.COLOR -> r.color.temperature != 0f || r.color.tint != 0f || r.color.vibrance != 0f || r.color.saturation != 0f
    AdjustPage.CURVE -> !r.curves.isIdentity
    AdjustPage.HSL -> !r.color.isHslIdentity
    AdjustPage.EFFECTS -> r.effects.vignette != 0f || r.effects.grain != 0f
}

@Composable
fun VideoFiltersPanel(session: VideoEditorSession, edit: State<VideoEdit>, modifier: Modifier) {
    val thumbs by session.filterThumbs.collectAsState()
    val recipe = remember(edit) { derivedStateOf { edit.value.color } }
    FiltersPanel(session.colorEdits, thumbs, recipe, modifier)
}

// ---- Export ----------------------------------------------------------------------------------

/** What an export will produce, for the settings panel. */
data class ExportEstimate(val width: Int, val height: Int, val durationMs: Long, val bytes: Long, val codec: String)

object ExportEstimates {
    const val AUDIO_BPS = 128_000L

    /** The planner's own output size and bitrate, times the length (+ audio). A copy-only trim keeps the source rate. */
    fun estimate(edit: VideoEdit, source: SourceInfo, caps: EncoderCaps): ExportEstimate {
        val plan = CompositionPlanner.plan(edit, source, caps)
        val videoBps = if (plan.trimOptimization && source.bitrate != null) source.bitrate.toLong() else plan.bitrate.toLong()
        val bps = videoBps + if (plan.removeAudio) 0L else AUDIO_BPS
        val bytes = (bps.toDouble() * plan.outputDurationMs / 8000.0).roundToLong()
        return ExportEstimate(plan.outputWidth, plan.outputHeight, plan.outputDurationMs, bytes, if (plan.videoMime.endsWith("hevc")) "HEVC" else "H.264")
    }

    /** A resolution is offered when it is smaller than the (cropped) picture; it never upscales. */
    fun offered(res: OutputResolution, edit: VideoEdit, source: SourceInfo): Boolean {
        val side = res.shortSide ?: return true
        val (w, h) = edit.geometry.outputSize(source.width, source.height)
        return min(w, h) > side
    }
}

/** The export qualities the UI offers. */
val QualityChoices = listOf(OutputQuality.STANDARD, OutputQuality.HIGH)

@Composable
fun ExportPanel(session: VideoEditorSession, edit: State<VideoEdit>, modifier: Modifier) {
    val haptics = rememberHaptics()
    val e = edit.value
    val est = remember(e) { ExportEstimates.estimate(e, session.source, session.caps) }
    ToolColumn(modifier) {
        PanelHeader("Resolution")
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for (r in OutputResolution.entries) {
                val ok = ExportEstimates.offered(r, e, session.source)
                if (!ok && e.output.resolution != r) continue
                DarkChip(r.label, e.output.resolution == r, {
                    session.history.update { it.copy(output = it.output.copy(resolution = r)) }
                    haptics.tick()
                })
            }
        }
        PanelHeader("Quality")
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for (q in QualityChoices) {
                DarkChip(q.label, e.output.quality == q, {
                    session.history.update { it.copy(output = it.output.copy(quality = q)) }
                    haptics.tick()
                })
            }
        }
        Spacer(Modifier.height(4.dp))
        EstimateCard(est)
    }
}

@Composable
fun EstimateCard(est: ExportEstimate, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().brutalDark(fill = BrutalDark.Raised, shadow = 3.dp).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("ESTIMATED SIZE", style = Type.Tiny, color = BrutalDark.MutedText)
            Text("≈ ${Format.bytes(est.bytes)}", style = Type.Headline, color = BrutalDark.Orange)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("${est.width} × ${est.height}", style = Type.Label, color = BrutalDark.Text)
            Text("${est.codec} · ${TrimMath.formatDuration(est.durationMs)}", style = Type.Small, color = BrutalDark.MutedText)
        }
    }
}

// ---- Shared bits -----------------------------------------------------------------------------

@Composable
internal fun ToolColumn(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
internal fun PanelHeader(title: String, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title.uppercase(), style = Type.Label, color = BrutalDark.MutedText, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
internal fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, style = Type.Small, color = BrutalDark.MutedText, modifier = modifier)
}
