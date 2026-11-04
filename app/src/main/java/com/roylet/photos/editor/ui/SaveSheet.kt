package com.roylet.photos.editor.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.roylet.photos.editor.save.EditSaveManager
import com.roylet.photos.editor.save.SaveMode
import com.roylet.photos.editor.save.SaveProgress
import com.roylet.photos.editor.save.SaveStage
import com.roylet.photos.ui.components.DarkButton
import com.roylet.photos.ui.components.brutalDark
import com.roylet.photos.ui.theme.Brutal
import com.roylet.photos.ui.theme.BrutalDark
import com.roylet.photos.ui.theme.Type

/** The SAVE sheet: two big brutal choices. Slides up with a slight overshoot. */
@Composable
fun SaveSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    onSave: (SaveMode, Boolean) -> Unit,
    subtitle: String = "Exports a full-resolution JPEG to Immich",
    /** Extra content under the title (the video editor's export settings row). */
    extra: (@Composable () -> Unit)? = null,
) {
    val reduced = rememberReducedMotion()
    var group by rememberSaveable { mutableStateOf(true) }
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(motion(reduced, tween(180))), exit = fadeOut(motion(reduced, tween(160)))) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))
                    .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            )
        }
        AnimatedVisibility(
            visible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(motion(reduced, spring(dampingRatio = 0.72f, stiffness = 420f))) { it } + fadeIn(motion(reduced, tween(120))),
            exit = slideOutVertically(motion(reduced, tween(200))) { it } + fadeOut(motion(reduced, tween(200))),
        ) {
            SaveSheetContent(group, onGroupChange = { group = it }, onSave = onSave, onDismiss = onDismiss, subtitle = subtitle, extra = extra)
        }
    }
}

@Composable
internal fun SaveSheetContent(
    group: Boolean,
    onGroupChange: (Boolean) -> Unit,
    onSave: (SaveMode, Boolean) -> Unit,
    onDismiss: () -> Unit,
    subtitle: String = "Exports a full-resolution JPEG to Immich",
    extra: (@Composable () -> Unit)? = null,
) {
    Column(
        Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 10.dp)
            .brutalDark(shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp), shadow = 0.dp)
            // Swallow taps so they don't reach the scrim.
            .clickable(remember { MutableInteractionSource() }, indication = null) {}
            .navigationBarsPadding()
            .padding(start = 18.dp, end = 14.dp, top = 10.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.align(Alignment.CenterHorizontally).size(44.dp, 5.dp).clip(RoundedCornerShape(3.dp)).background(BrutalDark.MutedText))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("SAVE EDIT", style = Type.Headline, color = BrutalDark.Text)
                Text(subtitle, style = Type.Small, color = BrutalDark.MutedText)
            }
        }
        extra?.invoke()
        ChoiceCard(
            title = "Save as copy",
            body = "Keeps the original untouched. The copy joins the same albums.",
            primary = true,
            onClick = { onSave(SaveMode.COPY, group) },
        ) {
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.clip(RoundedCornerShape(6.dp))
                    .clickable(role = Role.Checkbox) { onGroupChange(!group) }
                    .padding(vertical = 4.dp, horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(24.dp).brutalDark(fill = if (group) Brutal.Ink else Color.Transparent, shadow = 0.dp, border = Brutal.Ink),
                    contentAlignment = Alignment.Center,
                ) { if (group) Icon(Icons.Rounded.Check, null, tint = BrutalDark.Orange, modifier = Modifier.size(18.dp)) }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("GROUP WITH ORIGINAL", style = Type.Label, color = Brutal.Ink)
                    Text("Stacks the copy on top of the original", style = Type.Small, color = Brutal.Ink.copy(alpha = 0.75f))
                }
            }
        }
        ChoiceCard(
            title = "Replace original",
            body = "The original goes to Trash and can be restored. Albums, favourite and sharing move to the edit.",
            primary = false,
            onClick = { onSave(SaveMode.REPLACE, false) },
        )
        DarkButton("Keep editing", onDismiss, primary = false, modifier = Modifier.align(Alignment.End))
    }
}

/** In-app progress for a running save; blocks editing while it runs. */
@Composable
fun SaveProgressPanel(progress: SaveProgress?, onBackground: () -> Unit, onDismissError: () -> Unit) {
    val reduced = rememberReducedMotion()
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(progress != null, enter = fadeIn(motion(reduced, tween(160))), exit = fadeOut(motion(reduced, tween(160)))) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(remember { MutableInteractionSource() }, indication = null) {})
        }
        AnimatedVisibility(
            progress != null,
            modifier = Modifier.align(Alignment.Center),
            enter = slideInVertically(motion(reduced, spring(dampingRatio = 0.7f, stiffness = 500f))) { it / 3 } + fadeIn(motion(reduced, tween(120))),
            exit = fadeOut(motion(reduced, tween(150))),
        ) {
            val p = progress ?: return@AnimatedVisibility
            Column(
                Modifier.widthIn(max = 440.dp).fillMaxWidth().padding(20.dp).brutalDark(shadow = 6.dp).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (p) {
                    is SaveProgress.Running -> {
                        val overall = EditSaveManager.overall(p.stage, p.fraction)
                        Text("SAVING", style = Type.Label, color = BrutalDark.Orange)
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("${(overall * 100).toInt()}%", style = Type.Huge, color = BrutalDark.Text, modifier = Modifier.weight(1f))
                        }
                        DarkProgress(overall, height = 18.dp, moving = p.stage == SaveStage.QUEUED || p.stage == SaveStage.WAITING)
                        Text(p.stage.label, style = Type.Small, color = BrutalDark.MutedText)
                        Text("You can leave; the save keeps going with a notification.", style = Type.Small, color = BrutalDark.MutedText)
                        DarkButton("Continue in background", onBackground, primary = false, modifier = Modifier.align(Alignment.End))
                    }
                    is SaveProgress.Failed -> {
                        DarkTag("SAVE FAILED", fill = BrutalDark.Red)
                        Text(p.message, style = Type.Title, color = BrutalDark.Text)
                        Text("Nothing was deleted. Your edits are still here.", style = Type.Small, color = BrutalDark.MutedText)
                        DarkButton("Back to editing", onDismissError, modifier = Modifier.align(Alignment.End))
                    }
                    is SaveProgress.Done -> {
                        DarkTag("SAVED", fill = BrutalDark.Mint)
                        Text(p.result.warning ?: "Your edit is in Immich.", style = Type.Title, color = BrutalDark.Text)
                    }
                }
            }
        }
    }
}
