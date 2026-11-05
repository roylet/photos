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
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.rounded.Cloud
import com.roylet.photos.domain.source.Capabilities
import com.roylet.photos.domain.source.Capability
import com.roylet.photos.ui.sources.LocalActiveSources
import com.roylet.photos.ui.sources.LocalCapabilities
import com.roylet.photos.ui.components.IconBadge
import com.roylet.photos.ui.components.Pressable
import com.roylet.photos.ui.components.ScreenHeader
import com.roylet.photos.ui.components.SectionLabel
import com.roylet.photos.ui.components.SquareIconButton
import com.roylet.photos.ui.theme.Brutal
import com.roylet.photos.ui.theme.Palette
import com.roylet.photos.ui.theme.Type

/** Where a Library tile leads. */
enum class LibraryEntry { Favorites, People, Places, Memories, Archive, Trash, Settings, ConnectImmich, OnThisDevice }

/**
 * The "Library" tab: big brutal tiles for the collections that don't get a tab of their own.
 * Only what the active sources can do is shown ([capabilities]); without a server a card offers
 * Immich for the rest.
 */
@Composable
fun LibraryScreen(
    onOpen: (LibraryEntry) -> Unit,
    modifier: Modifier = Modifier,
    capabilities: Capabilities = LocalCapabilities.current,
    hasImmich: Boolean = LocalActiveSources.current.hasImmich,
    /** Library → On this device: the phone's own photos and folders while the Photos tab shows Immich. */
    showDevice: Boolean = hasImmich && !LocalActiveSources.current.hasLocal,
) {
    val collections = buildList {
        if (Capability.FAVORITE in capabilities) add(LibraryEntry.Favorites)
        if (Capability.PEOPLE in capabilities) add(LibraryEntry.People)
        if (Capability.MAP in capabilities) add(LibraryEntry.Places)
        if (Capability.MEMORIES in capabilities) add(LibraryEntry.Memories)
    }
    val tidyUp = buildList {
        if (Capability.ARCHIVE in capabilities) add(LibraryEntry.Archive)
        if (Capability.TRASH in capabilities) add(LibraryEntry.Trash)
    }
    // A few tiles (the phone alone) sit together in one section rather than one lonely tile each.
    val oneSection = collections.size + tidyUp.size <= 3
    val tiles = if (oneSection) collections + tidyUp else collections
    val tidy = if (oneSection) emptyList() else tidyUp
    Column(modifier.fillMaxSize().background(Palette.paper)) {
        ScreenHeader("Library", actions = { SquareIconButton(Icons.Rounded.Settings, "Settings", { onOpen(LibraryEntry.Settings) }) })
        LazyVerticalGrid(
            columns = GridCells.Adaptive(160.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 12.dp, top = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (showDevice) {
                item(span = { GridItemSpan(maxLineSpan) }) { SectionLabel("On this phone") }
                item(span = { GridItemSpan(maxLineSpan) }) { DeviceCard(Modifier.padding(bottom = 12.dp)) { onOpen(LibraryEntry.OnThisDevice) } }
            }
            if (tiles.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { SectionLabel("Collections") }
            tiles.forEach { e ->
                item {
                    when (e) {
                        LibraryEntry.Archive -> Tile(Icons.Rounded.Archive, Palette.sand, "Archive", "Hidden from Photos") { onOpen(e) }
                        LibraryEntry.Trash -> Tile(Icons.Rounded.Delete, Palette.current.noticeError, "Trash", "Restore or delete") { onOpen(e) }
                        LibraryEntry.Favorites -> Tile(Icons.Rounded.Favorite, Palette.peach, "Favorites", "Everything you've hearted") { onOpen(e) }
                        LibraryEntry.People -> Tile(Icons.Rounded.People, Palette.lilac, "People", "Faces Immich found") { onOpen(e) }
                        LibraryEntry.Places -> Tile(Icons.Rounded.Map, Palette.mint, "Places", "Your photos on a map") { onOpen(e) }
                        else -> Tile(Icons.Rounded.AutoAwesome, Palette.yellow, "Memories", "On this day, years ago") { onOpen(e) }
                    }
                }
            }
            if (tidy.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { SectionLabel("Tidy up", Modifier.padding(top = if (collections.isEmpty()) 0.dp else 12.dp)) }
            tidy.forEach { e ->
                item {
                    if (e == LibraryEntry.Archive) Tile(Icons.Rounded.Archive, Palette.sand, "Archive", "Hidden from Photos") { onOpen(e) }
                    else Tile(Icons.Rounded.Delete, Palette.current.noticeError, "Trash", "Restore or delete") { onOpen(e) }
                }
            }
            if (!hasImmich) {
                item(span = { GridItemSpan(maxLineSpan) }) { ImmichCard(Modifier.padding(top = 12.dp)) { onOpen(LibraryEntry.ConnectImmich) } }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Pressable({ onOpen(LibraryEntry.Settings) }, Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(Icons.Rounded.Settings, fill = Palette.card, size = 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Settings", style = Type.Title, color = Palette.ink)
                            Text(if (hasImmich) "Accounts, server and connection" else "Accounts, this device and appearance", style = Type.Small, color = Palette.mutedInk)
                        }
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Palette.ink)
                    }
                }
            }
        }
    }
}

/** Library → On this device: the phone's photos, folders and trash next to the server's. */
@Composable
private fun DeviceCard(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Pressable(onClick, modifier.fillMaxWidth(), fill = Palette.mint) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.PhoneAndroid, fill = Palette.card, size = 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("On this device", style = Type.Title, color = Palette.ink)
                Text("Photos, videos and folders on this phone", style = Type.Small, color = Palette.ink.copy(alpha = 0.7f))
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = Palette.ink, modifier = Modifier.size(20.dp))
        }
    }
}

/** Offers Immich for what the phone alone can't do. */
@Composable
private fun ImmichCard(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Pressable(onClick, modifier.fillMaxWidth(), fill = Palette.lilac) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.Cloud, fill = Palette.card, size = 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Got an Immich server?", style = Type.Title, color = Palette.ink)
                Text("Connect it for people, places, memories, smart search and shared albums.", style = Type.Small, color = Palette.ink.copy(alpha = 0.7f))
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = Palette.ink, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun Tile(icon: ImageVector, fill: Color, title: String, subtitle: String, onClick: () -> Unit) {
    Pressable(onClick, Modifier.fillMaxWidth(), fill = fill) {
        Column(Modifier.padding(14.dp).height(118.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                IconBadge(icon, fill = Palette.card, size = 44.dp)
                Spacer(Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = Palette.ink, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.weight(1f))
            Text(title, style = Type.Title, color = Palette.ink, maxLines = 1)
            Text(subtitle, style = Type.Small, color = Palette.ink.copy(alpha = 0.7f), maxLines = 1)
        }
    }
}

