package com.roylet.photos.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.roylet.photos.AppContainer
import com.roylet.photos.ui.components.BrutalCard
import com.roylet.photos.ui.components.IconBadge
import com.roylet.photos.ui.components.ScreenHeader
import com.roylet.photos.ui.components.SquareIconButton
import com.roylet.photos.ui.theme.Brutal
import com.roylet.photos.ui.theme.Type

/** The "Library" tab: entry points to the collections that don't get a tab of their own. */
@Composable
fun LibraryScreen(@Suppress("UNUSED_PARAMETER") container: AppContainer, onSettings: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Brutal.Paper)) {
        ScreenHeader("Library", actions = { SquareIconButton(Icons.Rounded.Settings, "Settings", onSettings) })
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Entry(Icons.Rounded.Favorite, Brutal.Peach, "Favorites", "Everything you've hearted", {})
            Entry(Icons.Rounded.People, Brutal.Lilac, "People", "Faces Immich recognised", {})
            Entry(Icons.Rounded.Map, Brutal.Mint, "Map", "Where your photos were taken", {})
            Entry(Icons.Rounded.Archive, Brutal.Sand, "Archive", "Hidden from the timeline", {})
            Entry(Icons.Rounded.Delete, Color(0xFFFFE1E3), "Trash", "Restore or delete for good", {})
            Entry(Icons.Rounded.Settings, Brutal.Card, "Settings", "Server, connection and account", onSettings)
        }
    }
}

@Composable
private fun Entry(icon: ImageVector, fill: Color, title: String, subtitle: String, onClick: () -> Unit) {
    BrutalCard(Modifier.fillMaxWidth(), onClick = onClick, contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, fill = fill, size = 44.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = Type.Title, color = Brutal.Ink)
                Text(subtitle, style = Type.Small, color = Brutal.MutedInk)
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Brutal.Ink)
        }
    }
}
