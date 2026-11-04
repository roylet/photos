package com.roylet.photos.ui.albums

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.PhotoAlbum
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.roylet.photos.data.UiPrefs
import com.roylet.photos.domain.library.Album
import com.roylet.photos.domain.library.AlbumSort
import com.roylet.photos.domain.library.AlbumStore
import com.roylet.photos.ui.actions.LibraryActions
import com.roylet.photos.ui.components.BrutalButton
import com.roylet.photos.ui.components.BrutalDialog
import com.roylet.photos.ui.components.BrutalSpinner
import com.roylet.photos.ui.components.BrutalTextField
import com.roylet.photos.ui.components.Chip
import com.roylet.photos.ui.components.DialogButtons
import com.roylet.photos.ui.components.EmptyState
import com.roylet.photos.ui.components.Pressable
import com.roylet.photos.ui.components.ScreenHeader
import com.roylet.photos.ui.gallery.AssetImage
import com.roylet.photos.ui.gallery.LocalMediaUrls
import com.roylet.photos.ui.theme.Brutal
import com.roylet.photos.ui.theme.Type
import com.roylet.photos.util.Format

/** The "Albums" tab: a grid of album cards (more columns on wider screens), sortable, with "+ New album". */
@Composable
fun AlbumsScreen(
    store: AlbumStore,
    actions: LibraryActions?,
    prefs: UiPrefs,
    onOpen: (Album) -> Unit,
    modifier: Modifier = Modifier,
) {
    val albums by store.albums.collectAsState()
    val error by store.error.collectAsState()
    val loading by store.loading.collectAsState()
    LaunchedEffect(store) { store.start() }
    var sort by rememberSaveable { mutableStateOf(runCatching { AlbumSort.valueOf(prefs.albumSort) }.getOrDefault(AlbumSort.Recent)) }
    var creating by remember { mutableStateOf(false) }

    Box(modifier.fillMaxSize().background(Brutal.Paper)) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(
                "Albums",
                subtitle = albums?.let { list -> "${Format.plural(list.size, "album")} · ${"${list.count { it.shared }} shared"}" },
                actions = { if (actions != null) BrutalButton("New album", { creating = true }, icon = Icons.Rounded.Add) },
                below = {
                    Row(Modifier.padding(top = 12.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AlbumSort.entries.forEach { s -> Chip(s.label, selected = s == sort, onClick = { sort = s; prefs.albumSort = s.name }) }
                    }
                },
            )
            val list = albums
            when {
                list == null && error != null -> EmptyState(Icons.Rounded.CloudOff, "Couldn't load albums", error.orEmpty()) {
                    BrutalButton("Try again", { store.refresh() })
                }
                list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { BrutalSpinner(size = 32.dp) }
                list.isEmpty() -> EmptyState(Icons.Rounded.PhotoAlbum, "No albums yet", "Group photos into albums to find and share them easily.") {
                    if (actions != null) BrutalButton("New album", { creating = true }, icon = Icons.Rounded.Add)
                }
                else -> {
                    val sorted = remember(list, sort) { sort.apply(list) }
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(158.dp),
                        contentPadding = PaddingValues(start = 16.dp, end = 12.dp, top = 16.dp, bottom = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        if (loading) item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { BrutalSpinner(size = 20.dp) }
                        }
                        items(sorted, key = { it.id }) { album -> AlbumCard(album, onClick = { onOpen(album) }) }
                    }
                }
            }
        }
        NewAlbumDialog(creating, onDismiss = { creating = false }) { name ->
            creating = false
            actions?.createAlbum(name, emptyList()) { onOpen(it) }
        }
    }
}

/** Square cover with the album's name, count and badges underneath. */
@Composable
fun AlbumCard(album: Album, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val urls = LocalMediaUrls.current
    Column(modifier) {
        Pressable(onClick, Modifier.fillMaxWidth(), fill = Brutal.Lilac, shadow = 4.dp) {
            var px by remember { mutableStateOf(300) }
            Box(Modifier.fillMaxWidth().aspectRatio(1f).onSizeChanged { px = it.width }, contentAlignment = Alignment.Center) {
                if (album.coverAssetId != null) AssetImage(urls.thumbnail(album.coverAssetId), null, px, px, Modifier.matchParentSize())
                else Icon(Icons.Rounded.PhotoAlbum, null, tint = Brutal.Ink, modifier = Modifier.size(40.dp))
                Row(Modifier.align(Alignment.TopEnd).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (album.hasSharedLink) Badge(Icons.Rounded.Link)
                    if (album.shared) Badge(Icons.Rounded.People)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(album.name, style = Type.Title, color = Brutal.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val meta = buildList {
            add(Format.plural(album.assetCount, "item"))
            if (!album.ownedByMe && album.ownerName != null) add("by ${album.ownerName}")
        }.joinToString(" · ")
        Text(meta, style = Type.Small, color = Brutal.MutedInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Badge(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    val shape = RoundedCornerShape(50)
    Box(Modifier.size(26.dp).clip(shape).background(Brutal.Card, shape).border(2.dp, Brutal.Ink, shape), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Brutal.Ink, modifier = Modifier.size(15.dp))
    }
}

@Composable
fun NewAlbumDialog(visible: Boolean, onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember(visible) { mutableStateOf("") }
    BrutalDialog(
        visible, "New album", onDismiss,
        text = "Add photos from the timeline afterwards, or select some first and use Add to album.",
        content = { BrutalTextField(name, { name = it }, "Album name") },
    ) { DialogButtons("Create", { onCreate(name) }, onDismiss, enabled = name.isNotBlank()) }
}

