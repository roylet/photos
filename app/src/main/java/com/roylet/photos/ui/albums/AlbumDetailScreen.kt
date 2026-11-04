package com.roylet.photos.ui.albums

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.roylet.photos.domain.library.Album
import com.roylet.photos.domain.library.AlbumStore
import com.roylet.photos.domain.timeline.TimelineAsset
import com.roylet.photos.domain.timeline.TimelineRepository
import com.roylet.photos.ui.actions.LibraryActions
import com.roylet.photos.ui.components.BrutalDialog
import com.roylet.photos.ui.components.BrutalSheet
import com.roylet.photos.ui.components.BrutalShape
import com.roylet.photos.ui.components.BrutalTextField
import com.roylet.photos.ui.components.DialogButtons
import com.roylet.photos.ui.components.IconBadge
import com.roylet.photos.ui.components.SquareIconButton
import com.roylet.photos.ui.components.Tag
import com.roylet.photos.ui.gallery.AssetActions
import com.roylet.photos.ui.gallery.FavoritePopButton
import com.roylet.photos.ui.gallery.GridSelection
import com.roylet.photos.ui.gallery.LocalAssetActions
import com.roylet.photos.ui.gallery.SelectionScope
import com.roylet.photos.ui.gallery.TimelineScreen
import com.roylet.photos.ui.gallery.allFavorite
import com.roylet.photos.ui.theme.Brutal
import com.roylet.photos.ui.theme.Type
import com.roylet.photos.util.Format

/**
 * One album: its timeline under a header with the description, count, dates and sharing badges.
 * The menu renames, shares a link or deletes it; selecting photos adds "set as cover" and
 * "remove from album".
 */
@Composable
fun AlbumDetailScreen(
    albumId: String,
    store: AlbumStore,
    timeline: TimelineRepository,
    actions: LibraryActions?,
    onOpen: (index: Int, asset: TimelineAsset) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    revealIndex: Int? = null,
    onRevealed: () -> Unit = {},
    selection: GridSelection = remember { GridSelection() },
) {
    val albums by store.albums.collectAsState()
    LaunchedEffect(store) { store.start() }
    val album = albums?.firstOrNull { it.id == albumId }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    Box(modifier.fillMaxSize()) {
        TimelineScreen(
            timeline = timeline,
            title = album?.name ?: "Album",
            subtitle = album?.let { Format.plural(it.assetCount, "item") },
            onOpen = onOpen,
            onBack = onBack,
            selection = selection,
            revealIndex = revealIndex,
            onRevealed = onRevealed,
            emptyTitle = "This album is empty",
            emptyText = "Select photos on the timeline and choose Add to album to fill it.",
            headerActions = {
                if (actions != null) {
                    SquareIconButton(Icons.Rounded.Link, "Share link", { actions.shareAlbum(albumId) })
                    SquareIconButton(Icons.Rounded.MoreVert, "Album options", { menu = true })
                }
            },
            selectionActions = { scope -> AlbumSelectionActions(scope, albumId) },
            topContent = album?.let { a -> { AlbumHeader(a) } },
        )

        BrutalSheet(menu, onDismiss = { menu = false }) {
            Text(album?.name ?: "Album", style = Type.Headline, color = Brutal.Ink)
            Spacer(Modifier.padding(top = 8.dp))
            MenuRow(Icons.Rounded.Edit, "Rename", "Change the name or description") { menu = false; renaming = true }
            MenuRow(Icons.Rounded.Link, "Share link", "A web page for this album") { menu = false; actions?.shareAlbum(albumId) }
            MenuRow(Icons.Rounded.DeleteOutline, "Delete album", "Photos stay in your library", danger = true) { menu = false; deleting = true }
        }

        var name by remember(renaming) { mutableStateOf(album?.name.orEmpty()) }
        var description by remember(renaming) { mutableStateOf(album?.description.orEmpty()) }
        BrutalDialog(
            renaming, "Rename album", onDismiss = { renaming = false },
            content = {
                BrutalTextField(name, { name = it }, "Album name", label = "Name")
                BrutalTextField(description, { description = it }, "What's it about?", label = "Description")
            },
        ) {
            DialogButtons("Save", { renaming = false; album?.let { actions?.renameAlbum(it, name, description) } }, { renaming = false }, enabled = name.isNotBlank())
        }

        BrutalDialog(
            deleting, "Delete “${album?.name ?: "album"}”?", onDismiss = { deleting = false },
            text = "The album goes, but its ${Format.plural(album?.assetCount ?: 0, "photo")} stay in your library. Shared links to it stop working.",
        ) {
            DialogButtons("Delete", { deleting = false; album?.let { actions?.deleteAlbum(it, onBack) } }, { deleting = false }, danger = true)
        }
    }
}

@Composable
private fun RowScope.AlbumSelectionActions(scope: SelectionScope, albumId: String, actions: AssetActions = LocalAssetActions.current) {
    val allFav = allFavorite(scope.ids, scope.timeline)
    FavoritePopButton(allFav, onClick = { actions.favorite(scope.ids, !allFav, scope.timeline) })
    SquareIconButton(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to another album", { actions.addToAlbum(scope.ids, scope.timeline) })
    SquareIconButton(Icons.Rounded.Share, "Share", { actions.share(scope.ids, scope.timeline) })
    if (scope.ids.size == 1) SquareIconButton(Icons.Rounded.Wallpaper, "Set as album cover", { actions.setAlbumCover(albumId, scope.ids.single()); scope.clear() })
    SquareIconButton(Icons.Rounded.RemoveCircleOutline, "Remove from album", { actions.removeFromAlbum(albumId, scope.ids, scope.timeline); scope.clear() })
}

/** Description, dates and owner/sharing badges above the album's photos. */
@Composable
fun AlbumHeader(album: Album) {
    Column(Modifier.fillMaxWidth().background(Brutal.Paper).padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Tag(if (album.ownedByMe) "Yours" else "by ${album.ownerName ?: "someone"}", fill = if (album.ownedByMe) Brutal.Mint else Brutal.Peach)
            if (album.shared) Tag("Shared", fill = Brutal.Lilac)
            if (album.hasSharedLink) Tag("Link", fill = Brutal.Yellow)
            val start = album.startDate
            val end = album.endDate
            if (start != null) {
                Spacer(Modifier.width(2.dp))
                val range = if (end == null || Format.shortDate(start) == Format.shortDate(end)) Format.shortDate(start) else "${Format.shortDate(start)} – ${Format.shortDate(end)}"
                Text(range, style = Type.Small, color = Brutal.MutedInk, maxLines = 1)
            }
        }
        if (album.description.isNotBlank()) {
            Spacer(Modifier.padding(top = 8.dp))
            Text(album.description, style = Type.Body, color = Brutal.Ink)
        }
    }
}

@Composable
private fun MenuRow(icon: ImageVector, title: String, subtitle: String, danger: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(BrutalShape).clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(icon, fill = if (danger) Color(0xFFFFE1E3) else Brutal.Peach, size = 42.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.Title, color = if (danger) Brutal.Red else Brutal.Ink)
            Text(subtitle, style = Type.Small, color = Brutal.MutedInk)
        }
    }
}

