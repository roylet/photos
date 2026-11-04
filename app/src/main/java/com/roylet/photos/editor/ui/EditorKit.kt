package com.roylet.photos.editor.ui

import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.roylet.photos.ui.components.DarkButton
import com.roylet.photos.ui.components.brutalDark
import com.roylet.photos.ui.theme.BrutalDark
import com.roylet.photos.ui.theme.Type

/** True when the system "Remove animations" setting is on. */
@Composable
fun rememberReducedMotion(): Boolean {
    val ctx = LocalContext.current
    return remember {
        runCatching { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
}

/** [spec] normally, an instant snap under reduced motion. */
fun <T> motion(reduced: Boolean, spec: FiniteAnimationSpec<T>): FiniteAnimationSpec<T> = if (reduced) snap() else spec

/** The editor's three haptic flavours. */
class Haptics(private val view: View) {
    /** A light tick: crossing zero, each straighten degree mark. */
    fun tick() { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
    /** A firmer click: snapping to an edge or a detent. */
    fun snap() { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
    /** Hold-to-compare engaged. */
    fun press() { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}

/** The dark progress bar: raised track, orange fill, paper outline. [moving] sweeps a block when the fraction is unknown. */
@Composable
fun DarkProgress(fraction: Float?, modifier: Modifier = Modifier, height: Dp = 14.dp, moving: Boolean = fraction == null) {
    val reduced = rememberReducedMotion()
    val f by animateFloatAsState((fraction ?: 0f).coerceIn(0f, 1f), motion(reduced, tween(300)), label = "progress")
    val phase = if (moving && !reduced) {
        rememberInfiniteTransition(label = "sweep").animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "phase").value
    } else 0.35f
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier.fillMaxWidth().height(height).clip(shape).background(BrutalDark.Raised).border(2.dp, BrutalDark.Line, shape)
            .drawBehind {
                if (fraction == null) {
                    val bw = size.width * 0.3f
                    val x = -bw + (size.width + bw) * phase
                    drawRect(BrutalDark.Orange, Offset(x, 0f), size.copy(width = bw))
                    return@drawBehind
                }
                val w = size.width * f
                drawRect(BrutalDark.Orange, size = size.copy(width = w))
                if (w > 0f) drawLine(BrutalDark.Line, Offset(w, 0f), Offset(w, size.height), 2.dp.toPx())
            },
    )
}

/** A brutal two-button confirmation dialog on the dark canvas. */
@Composable
fun DarkConfirm(
    title: String,
    text: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismiss: String = "Keep editing",
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.widthIn(max = 420.dp).brutalDark(shadow = 6.dp).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title.uppercase(), style = Type.Headline, color = BrutalDark.Text)
            Text(text, style = Type.Body, color = BrutalDark.MutedText)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                DarkButton(dismiss, onDismiss, primary = false)
                DarkButton(confirm, onConfirm)
            }
        }
    }
}

/** A small uppercase tag (e.g. "ORIGINAL" while comparing). */
@Composable
fun DarkTag(text: String, modifier: Modifier = Modifier, fill: Color = BrutalDark.Orange, ink: Color = Color(0xFF111111)) {
    Box(modifier.brutalDark(fill = fill, shadow = 3.dp, border = Color(0xFF111111)).padding(horizontal = 10.dp, vertical = 5.dp)) {
        Text(text, style = Type.Label, color = ink)
    }
}

/** Fills the editor while there's nothing to edit yet. */
@Composable
fun EditorBackdrop(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(BrutalDark.Canvas), contentAlignment = Alignment.Center) { content() }
}
