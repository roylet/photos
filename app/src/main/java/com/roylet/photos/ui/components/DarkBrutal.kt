package com.roylet.photos.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.roylet.photos.ui.theme.Brutal
import com.roylet.photos.ui.theme.BrutalDark
import com.roylet.photos.ui.theme.Type

/*
 * Dark brutalist counterparts for the viewer and editor: ink canvas, paper-coloured 2dp outlines,
 * black hard shadows and orange for anything active. Same shapes and press behaviour as the
 * light kit in Brutal.kt.
 */

/** [Modifier.brutal] on the dark canvas. */
fun Modifier.brutalDark(
    fill: Color = BrutalDark.Panel,
    shape: Shape = BrutalShape,
    shadow: Dp = 4.dp,
    border: Color = BrutalDark.Line,
    reserve: Dp = shadow,
): Modifier = brutal(fill = fill, shape = shape, shadow = shadow, border = border, reserve = reserve, shadowColor = BrutalDark.Shadow)

@Composable
fun DarkPanel(modifier: Modifier = Modifier, contentPadding: PaddingValues = PaddingValues(16.dp), content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.brutalDark().padding(contentPadding), content = content)
}

@Composable
fun DarkIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier, active: Boolean = false, enabled: Boolean = true) {
    Pressable(
        onClick, modifier,
        fill = if (active) BrutalDark.Orange else BrutalDark.Panel,
        shadow = 3.dp, enabled = enabled, border = BrutalDark.Line, shadowColor = BrutalDark.Shadow, disabledBlend = BrutalDark.Canvas,
    ) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription, tint = (if (active) Brutal.Ink else BrutalDark.Text).copy(alpha = if (enabled) 1f else 0.4f), modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
fun DarkButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = true, icon: ImageVector? = null, enabled: Boolean = true) {
    val ink = if (primary) Brutal.Ink else BrutalDark.Text
    Pressable(
        onClick, modifier,
        fill = if (primary) BrutalDark.Orange else BrutalDark.Panel,
        enabled = enabled, border = BrutalDark.Line, shadowColor = BrutalDark.Shadow, disabledBlend = BrutalDark.Canvas,
    ) {
        Row(Modifier.padding(horizontal = 16.dp).height(44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (icon != null) {
                Icon(icon, null, tint = ink, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text.uppercase(), style = Type.Label, color = ink.copy(alpha = if (enabled) 1f else 0.5f), maxLines = 1)
        }
    }
}

@Composable
fun DarkChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Pressable(
        onClick, modifier,
        fill = if (selected) BrutalDark.Orange else BrutalDark.Panel,
        shadow = if (selected) 0.dp else 3.dp, shape = RoundedCornerShape(50), border = BrutalDark.Line, shadowColor = BrutalDark.Shadow, disabledBlend = BrutalDark.Canvas,
    ) {
        val ink = if (selected) Brutal.Ink else BrutalDark.Text
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, tint = ink, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(text, style = Type.Label, color = ink, maxLines = 1)
        }
    }
}
