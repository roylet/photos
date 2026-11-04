package com.roylet.photos.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.AutoAwesome
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
import com.roylet.photos.ui.components.IconBadge
import com.roylet.photos.ui.components.Pressable
import com.roylet.photos.ui.components.ScreenHeader
import com.roylet.photos.ui.components.SectionLabel
import com.roylet.photos.ui.components.SquareIconButton
import com.roylet.photos.ui.theme.Brutal
import com.roylet.photos.ui.theme.Type

/** Where a Library tile leads. */
enum class LibraryEntry { Favorites, People, Places, Memories, Archive, Trash, Settings }

/** The "Library" tab: big brutal tiles for the collections that don't get a tab of their own. */
@Composable
fun LibraryScreen(onOpen: (LibraryEntry) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().background(Brutal.Paper)) {
        ScreenHeader("Library", actions = { SquareIconButton(Icons.Rounded.Settings, "Settings", { onOpen(LibraryEntry.Settings) }) })
        LazyVerticalGrid(
            columns = GridCells.Adaptive(160.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 12.dp, top = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { SectionLabel("Collections") }
            item { Tile(Icons.Rounded.Favorite, Brutal.Peach, "Favorites", "Everything you've hearted") { onOpen(LibraryEntry.Favorites) } }
            item { Tile(Icons.Rounded.People, Brutal.Lilac, "People", "Faces Immich found") { onOpen(LibraryEntry.People) } }
            item { Tile(Icons.Rounded.Map, Brutal.Mint, "Places", "Your photos on a map") { onOpen(LibraryEntry.Places) } }
            item { Tile(Icons.Rounded.AutoAwesome, Brutal.Yellow, "Memories", "On this day, years ago") { onOpen(LibraryEntry.Memories) } }
            item(span = { GridItemSpan(maxLineSpan) }) { SectionLabel("Tidy up", Modifier.padding(top = 12.dp)) }
            item { Tile(Icons.Rounded.Archive, Brutal.Sand, "Archive", "Hidden from Photos") { onOpen(LibraryEntry.Archive) } }
            item { Tile(Icons.Rounded.Delete, Color(0xFFFFE1E3), "Trash", "Restore or delete") { onOpen(LibraryEntry.Trash) } }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Pressable({ onOpen(LibraryEntry.Settings) }, Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(Icons.Rounded.Settings, fill = Brutal.Card, size = 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Settings", style = Type.Title, color = Brutal.Ink)
                            Text("Server, connection and account", style = Type.Small, color = Brutal.MutedInk)
                        }
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Brutal.Ink)
                    }
                }
            }
        }
    }
}

@Composable
private fun Tile(icon: ImageVector, fill: Color, title: String, subtitle: String, onClick: () -> Unit) {
    Pressable(onClick, Modifier.fillMaxWidth(), fill = fill) {
        Column(Modifier.padding(14.dp).height(118.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                IconBadge(icon, fill = Brutal.Card, size = 44.dp)
                Spacer(Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = Brutal.Ink, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.weight(1f))
            Text(title, style = Type.Title, color = Brutal.Ink, maxLines = 1)
            Text(subtitle, style = Type.Small, color = Brutal.Ink.copy(alpha = 0.7f), maxLines = 1)
        }
    }
}

