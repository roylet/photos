package com.roylet.photos.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.dp
import com.roylet.photos.ui.theme.Brutal
import com.roylet.photos.ui.theme.Palette
import com.roylet.photos.ui.theme.Type
import kotlinx.coroutines.delay

/* ---------------- Snackbar ---------------- */

enum class SnackTone { Info, Success, Error }

class SnackbarData(
    val id: Long,
    val message: String,
    val actionLabel: String?,
    val onAction: (() -> Unit)?,
    val tone: SnackTone,
    val durationMs: Long,
)

/**
 * App-wide snackbar: anything can call [show] (through [LocalSnackbar]); [BrutalSnackbarHost]
 * draws the latest one, bouncing in from the bottom. A new message replaces the current one.
 */
@Stable
class SnackbarController {
    var current by mutableStateOf<SnackbarData?>(null)
        private set
    private var next = 0L

    fun show(
        message: String,
        actionLabel: String? = null,
        tone: SnackTone = SnackTone.Info,
        durationMs: Long = if (actionLabel != null) 6000 else 3200,
        onAction: (() -> Unit)? = null,
    ) {
        current = SnackbarData(++next, message, actionLabel, onAction, tone, durationMs)
    }

    fun error(message: String) = show(message, tone = SnackTone.Error)

    fun dismiss(id: Long? = null) {
        if (id == null || current?.id == id) current = null
    }
}

val LocalSnackbar = staticCompositionLocalOf { SnackbarController() }

@Composable
fun BrutalSnackbarHost(controller: SnackbarController, modifier: Modifier = Modifier) {
    val reduced = reducedMotion()
    val data = controller.current
    var shown by remember { mutableStateOf<SnackbarData?>(null) }
    if (data != null) shown = data
    LaunchedEffect(data?.id) {
        val d = data ?: return@LaunchedEffect
        delay(d.durationMs)
        controller.dismiss(d.id)
    }
    AnimatedVisibility(
        visible = data != null,
        modifier = modifier,
        enter = if (reduced) fadeIn(snap()) else slideInVertically(spring(dampingRatio = 0.48f, stiffness = Spring.StiffnessMediumLow)) { it * 2 } +
            scaleIn(spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium), initialScale = 0.85f, transformOrigin = TransformOrigin(0.5f, 1f)),
        exit = if (reduced) fadeOut(snap()) else slideOutVertically(tween(160)) { it } + fadeOut(tween(140)),
    ) {
        val d = shown ?: return@AnimatedVisibility
        // A fresh message while one is up just swaps the text; the key makes the swap pop.
        androidx.compose.runtime.key(d.id) { SnackbarCard(d, onAction = { d.onAction?.invoke(); controller.dismiss(d.id) }) }
    }
}

@Composable
fun SnackbarCard(data: SnackbarData, onAction: () -> Unit, modifier: Modifier = Modifier) {
    // Inverted: an ink bar with paper text (cream bar, near-black text in dark mode).
    val c = Palette.current
    Row(
        modifier
            .widthIn(max = 520.dp)
            .fillMaxWidth()
            .brutal(c.ink, shadow = 4.dp, border = c.border, shadowColor = if (data.tone == SnackTone.Error) Brutal.Red else Brutal.Orange)
            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
            .height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (data.tone) {
            SnackTone.Success -> Icon(Icons.Rounded.CheckCircle, null, tint = c.snackSuccess, modifier = Modifier.size(20.dp).padding(end = 0.dp))
            SnackTone.Error -> Icon(Icons.Rounded.ErrorOutline, null, tint = Brutal.Red, modifier = Modifier.size(20.dp))
            SnackTone.Info -> Unit
        }
        if (data.tone != SnackTone.Info) Spacer(Modifier.width(10.dp))
        Text(data.message, style = Type.Label, color = c.paper, maxLines = 2, modifier = Modifier.weight(1f))
        if (data.actionLabel != null) {
            Box(
                Modifier.clip(BrutalShape).clickable(onClick = onAction).background(Brutal.Orange, BrutalShape)
                    .border(2.dp, c.paper, BrutalShape).padding(horizontal = 12.dp, vertical = 7.dp),
            ) { Text(data.actionLabel.uppercase(), style = Type.Label, color = Brutal.Ink) }
        }
    }
}

/* ---------------- Bottom sheet ---------------- */

private val SheetShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)

/**
 * A brutal bottom sheet drawn in-window (so it layers with the app and shows in screenshots):
 * a dimmed scrim and a paper panel that slides up with a slight overshoot. Back or a scrim tap
 * dismisses it.
 */
@Composable
fun BrutalSheet(visible: Boolean, onDismiss: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val reduced = reducedMotion()
    BackHandler(enabled = visible, onBack = onDismiss)
    var panel by remember { mutableStateOf(Rect.Zero) }
    Box(modifier.fillMaxSize().dismissOutside(visible, { panel }, onDismiss)) {
        AnimatedVisibility(visible, enter = fadeIn(tween(if (reduced) 0 else 180)), exit = fadeOut(tween(if (reduced) 0 else 160))) {
            Box(Modifier.fillMaxSize().background(Palette.scrim))
        }
        AnimatedVisibility(
            visible,
            Modifier.align(Alignment.BottomCenter).onGloballyPositioned { panel = it.boundsInParent() },
            enter = if (reduced) fadeIn(snap()) else slideInVertically(spring(dampingRatio = 0.74f, stiffness = Spring.StiffnessMediumLow)) { it },
            exit = if (reduced) fadeOut(snap()) else slideOutVertically(tween(200)) { it },
        ) {
            Column(
                Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .clip(SheetShape)
                    .background(Palette.paper, SheetShape)
                    .border(2.dp, Palette.border, SheetShape)
                    .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                    .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 14.dp),
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).size(44.dp, 5.dp).background(Palette.ink, RoundedCornerShape(50)))
                Spacer(Modifier.height(12.dp))
                content()
            }
        }
    }
}

/**
 * While [active], a tap outside [bounds] calls [onDismiss] and every touch is held here, so
 * nothing under the overlay reacts. Taps the overlay's own controls consume never get here.
 */
private fun Modifier.dismissOutside(active: Boolean, bounds: () -> Rect, onDismiss: () -> Unit): Modifier =
    if (!active) this else pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val inside = bounds().contains(down.position)
            val up = waitForUpOrCancellation()
            if (!inside && up != null && !bounds().contains(up.position)) onDismiss()
            if (!inside) up?.consume()
        }
    }

/* ---------------- Dialog ---------------- */

/** A centred brutal card over a scrim; pops in with a spring. */
@Composable
fun BrutalDialog(
    visible: Boolean,
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    text: String? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
    buttons: @Composable BoxScope.() -> Unit,
) {
    val reduced = reducedMotion()
    BackHandler(enabled = visible, onBack = onDismiss)
    var card by remember { mutableStateOf(Rect.Zero) }
    Box(modifier.fillMaxSize().dismissOutside(visible, { card }, onDismiss), contentAlignment = Alignment.Center) {
        AnimatedVisibility(visible, enter = fadeIn(tween(if (reduced) 0 else 160)), exit = fadeOut(tween(if (reduced) 0 else 140))) {
            Box(Modifier.fillMaxSize().background(Palette.scrim))
        }
        AnimatedVisibility(
            visible,
            Modifier.onGloballyPositioned { card = it.boundsInParent() },
            enter = if (reduced) fadeIn(snap()) else scaleIn(spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium), initialScale = 0.8f) + fadeIn(tween(120)),
            exit = if (reduced) fadeOut(snap()) else scaleOut(tween(120), targetScale = 0.92f) + fadeOut(tween(120)),
        ) {
            Column(
                Modifier.padding(24.dp).widthIn(max = 420.dp).fillMaxWidth()
                    .brutal(Palette.card, shadow = 6.dp)
                    .windowInsetsPadding(WindowInsets.ime)
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(title, style = Type.Headline, color = Palette.ink)
                if (text != null) Text(text, style = Type.Body, color = Palette.mutedInk)
                content?.invoke(this)
                Spacer(Modifier.height(2.dp))
                Box(Modifier.fillMaxWidth(), content = buttons)
            }
        }
    }
}

/** The usual dialog footer: a ghost "Cancel" and one primary/danger action, right-aligned. */
@Composable
fun BoxScope.DialogButtons(confirm: String, onConfirm: () -> Unit, onCancel: () -> Unit, danger: Boolean = false, enabled: Boolean = true) {
    Row(Modifier.align(Alignment.CenterEnd), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        BrutalButton("Cancel", onCancel, style = ButtonStyle.Ghost)
        BrutalButton(confirm, onConfirm, style = if (danger) ButtonStyle.Danger else ButtonStyle.Primary, enabled = enabled)
    }
}

/** A labelled on/off row used in option lists (share link settings). */
@Composable
fun ToggleRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(BrutalShape).clickable { onChange(!checked) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.Title.copy(fontSize = Type.Body.fontSize), color = Palette.ink)
            if (subtitle != null) Text(subtitle, style = Type.Small, color = Palette.mutedInk)
        }
        BrutalSwitch(checked)
    }
}

/** Pill switch: ink outline, orange when on, knob slides with a spring. */
@Composable
fun BrutalSwitch(checked: Boolean, modifier: Modifier = Modifier) {
    val reduced = reducedMotion()
    val x by androidx.compose.animation.core.animateDpAsState(if (checked) 22.dp else 2.dp, if (reduced) snap() else spring(dampingRatio = 0.6f), label = "knob")
    Box(
        modifier.size(48.dp, 28.dp).background(if (checked) Brutal.Orange else Palette.sand, RoundedCornerShape(50)).border(2.dp, Palette.border, RoundedCornerShape(50)),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.padding(start = x).size(20.dp).background(Palette.card, RoundedCornerShape(50)).border(2.dp, Palette.border, RoundedCornerShape(50)))
    }
}
