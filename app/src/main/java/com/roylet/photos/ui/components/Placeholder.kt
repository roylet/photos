package com.roylet.photos.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.roylet.photos.ui.theme.Brutal
import com.roylet.photos.ui.theme.Palette
import com.roylet.photos.ui.theme.Type

/** Centered "nothing here yet" block: a big icon badge, a title and a line of text. */
@Composable
fun EmptyState(icon: ImageVector, title: String, text: String, modifier: Modifier = Modifier, actions: (@Composable ColumnScope.() -> Unit)? = null) {
    Column(
        modifier.fillMaxSize().background(Palette.paper).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        IconBadge(icon, fill = Palette.peach, size = 72.dp)
        Spacer(Modifier.height(18.dp))
        Text(title, style = Type.Headline, color = Palette.ink, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(text, style = Type.Body, color = Palette.mutedInk, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 360.dp))
        if (actions != null) {
            Spacer(Modifier.height(18.dp))
            Column(Modifier.widthIn(max = 320.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp), content = actions)
        }
    }
}
