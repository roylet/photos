package com.roylet.photos.ui.library

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoFilter
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Screenshot
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.SdCard
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.VideoChat
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.roylet.photos.domain.library.DeviceFolder
import com.roylet.photos.domain.library.DeviceFolderStore
import com.roylet.photos.domain.library.DeviceFolders
import com.roylet.photos.domain.library.FolderKind
import com.roylet.photos.domain.library.FolderPrefs
import com.roylet.photos.domain.timeline.TimelineAsset
import com.roylet.photos.domain.timeline.TimelineRepository
import com.roylet.photos.ui.components.BrutalButton
import com.roylet.photos.ui.components.BrutalShape
import com.roylet.photos.ui.components.BrutalSheet
import com.roylet.photos.ui.components.BrutalSpinner
import com.roylet.photos.ui.components.ButtonStyle
import com.roylet.photos.ui.components.EmptyState
import com.roylet.photos.ui.components.IconBadge
import com.roylet.photos.ui.components.ScreenHeader
import com.roylet.photos.ui.components.SectionLabel
import com.roylet.photos.ui.components.SquareIconButton
import com.roylet.photos.ui.components.Tag
import com.roylet.photos.ui.components.ToggleRow
import com.roylet.photos.ui.components.brutal
import com.roylet.photos.ui.gallery.AssetImage
import com.roylet.photos.ui.gallery.LocalMediaUrls
import com.roylet.photos.ui.gallery.TimelineScreen
import com.roylet.photos.ui.permissions.LimitedAccessBanner
import com.roylet.photos.ui.permissions.LocalMediaPermissions
import com.roylet.photos.ui.permissions.MediaAccess
import com.roylet.photos.ui.permissions.MediaAccessDenied
import com.roylet.photos.ui.theme.Palette
import com.roylet.photos.ui.theme.Type
import com.roylet.photos.util.Format

/*
 * Library → On this device (Google Photos' "Photos on device"): a row of the phone's folders on
 * the Library tab, the full grid behind VIEW ALL, and one folder's own timeline. Long-press a
 * folder (or its ⋯) to hide it from Library or to keep it out of the Photos timeline.
 */

/** The small icon for a well-known folder. */
fun folderIcon(kind: FolderKind): ImageVector = when (kind) {
    FolderKind.Camera -> Icons.Rounded.PhotoCamera
    FolderKind.Screenshots -> Icons.Rounded.Screenshot
    FolderKind.ScreenRecordings -> Icons.Rounded.Videocam
    FolderKind.Downloads -> Icons.Rounded.Download
    FolderKind.WhatsAppImages -> Icons.Rounded.Chat
    FolderKind.WhatsAppVideo -> Icons.Rounded.VideoChat
    FolderKind.Telegram -> Icons.Rounded.Send
    FolderKind.Instagram -> Icons.Rounded.PhotoFilter
    FolderKind.Snapchat -> Icons.Rounded.Face
    FolderKind.Pictures -> Icons.Rounded.Image
    FolderKind.Movies -> Icons.Rounded.Movie
    FolderKind.Edited -> Icons.Rounded.AutoFixHigh
    FolderKind.Other -> Icons.Rounded.Folder
}

@Composable
private fun folderTint(kind: FolderKind): Color = when (kind) {
    FolderKind.Camera -> Palette.yellow
    FolderKind.Screenshots -> Palette.mint
    FolderKind.ScreenRecordings, FolderKind.Movies -> Palette.lilac
    FolderKind.Downloads, FolderKind.Pictures -> Palette.blue
    FolderKind.Edited -> Palette.peach
    FolderKind.Other -> Palette.card
    else -> Palette.peach
}

/** `1,234 items` (and nothing more): the count under a card. */
private fun countLine(f: DeviceFolder) = Format.plural(f.count, "item")

/**
 * The Library tab's section: ON THIS DEVICE with VIEW ALL, then a row of folder cards. Without
 * media access a compact card asks for it; with partial access a MANAGE card sits above the row.
 */
@Composable
fun DeviceFoldersSection(
    store: DeviceFolderStore,
    onFolder: (DeviceFolder) -> Unit,
    onViewAll: () -> Unit,
    onAccessChanged: () -> Unit,
    onMenu: (DeviceFolder) -> Unit,
    modifier: Modifier = Modifier,
) {
    val permissions = LocalMediaPermissions.current
    val all by store.folders.collectAsState()
    val prefs by store.prefs.collectAsState()
    LaunchedEffect(store, permissions.access) { if (permissions.access != MediaAccess.Denied) store.start() }
    val shown = remember(all, prefs) { all?.filterNot(prefs::isHidden) }
    val hints = remember(all) { all?.let(DeviceFolders::pathHints).orEmpty() }
    Column(modifier.fillMaxWidth()) {
        SectionLabel("On this device", trailing = if (permissions.access != MediaAccess.Denied) { { ViewAll(onViewAll) } } else null)
        when {
            permissions.access == MediaAccess.Denied -> FolderAccessCard(
                partial = false,
                permanentlyDenied = permissions.permanentlyDenied,
                onGrant = { permissions.request { if (it != MediaAccess.Denied) onAccessChanged() } },
                onOpenSettings = permissions::openSettings,
            )
            else -> {
                if (permissions.access == MediaAccess.Partial) {
                    FolderAccessCard(partial = true, permanentlyDenied = false, onGrant = { permissions.request { onAccessChanged() } }, onOpenSettings = permissions::openSettings)
                    Spacer(Modifier.height(12.dp))
                }
                val list = shown
                when {
                    list == null -> PlaceholderRow()
                    list.isEmpty() -> Text(
                        if (all.isNullOrEmpty()) "No photos on this phone yet. Camera, Screenshots and Downloads show up here."
                        else "Every folder is hidden. VIEW ALL to bring them back.",
                        style = Type.Small, color = Palette.mutedInk, modifier = Modifier.padding(bottom = 8.dp),
                    )
                    else -> LazyRow(
                        // Edge to edge: the row scrolls under the screen's side padding.
                        Modifier.bleed(start = 16.dp, end = 12.dp),
                        contentPadding = PaddingValues(start = 16.dp, end = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(list, key = { it.bucketId }) { f ->
                            FolderCard(f, hints[f.bucketId], onClick = { onFolder(f) }, onLongClick = { onMenu(f) }, modifier = Modifier.width(104.dp), notInPhotos = !prefs.showsInPhotos(f))
                        }
                    }
                }
            }
        }
    }
}

/** Lets a child reach past its parent's horizontal padding (a row that scrolls edge to edge). */
private fun Modifier.bleed(start: Dp, end: Dp) = layout { measurable, constraints ->
    val extra = (start + end).roundToPx()
    val p = measurable.measure(constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra))
    layout(constraints.maxWidth, p.height) { p.place(-start.roundToPx(), 0) }
}

@Composable
private fun ViewAll(onClick: () -> Unit) {
    Row(
        Modifier.clip(BrutalShape).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("VIEW ALL", style = Type.Label, color = Palette.ink)
        Spacer(Modifier.width(4.dp))
        Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = Palette.ink, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun PlaceholderRow() {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 8.dp)) {
        repeat(3) { Box(Modifier.size(100.dp).background(Palette.sand, BrutalShape)) }
    }
}

/** No access (GRANT ACCESS) or limited access (MANAGE), in a compact card. */
@Composable
fun FolderAccessCard(partial: Boolean, permanentlyDenied: Boolean, onGrant: () -> Unit, onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().brutal(if (partial) Palette.yellow else Palette.mint, shadow = 4.dp).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(if (partial) Icons.Rounded.TouchApp else Icons.Rounded.PhotoLibrary, fill = Palette.card, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (partial) "Limited access" else "Your phone's folders", style = Type.Title, color = Palette.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (partial) "Only the photos you picked show here" else "Camera, Screenshots, Downloads and more, once Photos can see them",
                    style = Type.Small, color = Palette.ink.copy(alpha = 0.7f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            if (partial) {
                Spacer(Modifier.width(8.dp))
                BrutalButton("Manage", onGrant, style = ButtonStyle.Outline)
            }
        }
        if (!partial) {
            Spacer(Modifier.height(12.dp))
            if (permanentlyDenied) BrutalButton("Open settings", onOpenSettings, style = ButtonStyle.Outline, modifier = Modifier.fillMaxWidth())
            else BrutalButton("Grant access", onGrant, icon = Icons.Rounded.PhotoLibrary, modifier = Modifier.fillMaxWidth())
        }
    }
}

/**
 * A folder: square cover (its newest item, through the fast local thumbnail fetcher), the kind's
 * icon in the corner, then name, count and (for two folders of the same name) where it lives.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FolderCard(
    folder: DeviceFolder,
    pathHint: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Turned off with Show in Photos: said under the count. */
    notInPhotos: Boolean = false,
    onMore: (() -> Unit)? = null,
) {
    val urls = LocalMediaUrls.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shadow = 4.dp
    val sink by animateDpAsState(if (pressed) shadow else 0.dp, tween(60), label = "sink")
    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(end = shadow, bottom = shadow)
                .offset { IntOffset(sink.roundToPx(), sink.roundToPx()) }
                .brutal(fill = folderTint(folder.kind), shadow = shadow - sink, reserve = 0.dp)
                .combinedClickable(interactionSource = interaction, indication = null, onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "Folder options"),
        ) {
            var px by remember { mutableStateOf(300) }
            Box(Modifier.fillMaxWidth().aspectRatio(1f).onSizeChanged { px = it.width }, contentAlignment = Alignment.Center) {
                val cover = folder.coverId
                if (cover != null) AssetImage(urls.thumbnail(cover), null, px, px, Modifier.matchParentSize())
                else Icon(folderIcon(folder.kind), null, tint = Palette.ink, modifier = Modifier.size(36.dp))
                if (folder.kind != FolderKind.Other || folder.removable) {
                    Row(Modifier.align(Alignment.BottomStart).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (folder.kind != FolderKind.Other) KindBadge(folderIcon(folder.kind), folderTint(folder.kind))
                        if (folder.removable) KindBadge(Icons.Rounded.SdCard, Palette.card)
                    }
                }
                if (onMore != null) {
                    // ⋯ on the cover, so the name below keeps the card's whole width.
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(4.dp).size(32.dp).clip(RoundedCornerShape(50))
                            .clickable(onClickLabel = "Folder options", onClick = onMore),
                        contentAlignment = Alignment.Center,
                    ) { KindBadge(Icons.Rounded.MoreHoriz, Palette.card, "Options for ${folder.name}") }
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(folder.name, style = Type.Body.copy(fontWeight = Type.Title.fontWeight), color = Palette.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(if (notInPhotos) "${countLine(folder)} · Not in Photos" else countLine(folder), style = Type.Small, color = Palette.mutedInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (pathHint != null) Text(pathHint, style = Type.Small.copy(fontSize = Type.Small.fontSize * 0.9f), color = Palette.mutedInk.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun KindBadge(icon: ImageVector, fill: Color, description: String? = null) {
    val shape = RoundedCornerShape(50)
    Box(Modifier.size(26.dp).clip(shape).background(fill, shape).border(2.dp, Palette.border, shape), contentAlignment = Alignment.Center) {
        Icon(icon, description, tint = Palette.ink, modifier = Modifier.size(15.dp))
    }
}

/** A folder's ⋯ menu: Show in Photos, and Hide from / Show in Library. */
@Composable
fun FolderMenuSheet(
    folder: DeviceFolder?,
    prefs: FolderPrefs,
    onDismiss: () -> Unit,
    onHidden: (DeviceFolder, Boolean) -> Unit,
    onShowInPhotos: (DeviceFolder, Boolean) -> Unit,
) {
    // Keeps the last folder while the sheet animates away.
    var last by remember { mutableStateOf(folder) }
    if (folder != null) last = folder
    val f = last
    BrutalSheet(folder != null, onDismiss = onDismiss) {
        if (f != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(folderIcon(f.kind), fill = folderTint(f.kind), size = 42.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(f.name, style = Type.Headline, color = Palette.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${countLine(f)} · ${DeviceFolders.pathHint(f)}", style = Type.Small, color = Palette.mutedInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(10.dp))
            ToggleRow("Show in Photos", "Its photos and videos appear in the Photos timeline", prefs.showsInPhotos(f), { onShowInPhotos(f, it) })
            val hidden = prefs.isHidden(f)
            MenuRow(
                if (hidden) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                if (hidden) "Show in Library" else "Hide from Library",
                if (hidden) "Back in On this device" else "Still in Photos and search; unhide from On this device",
            ) { onHidden(f, !hidden); onDismiss() }
        }
    }
}

@Composable
private fun MenuRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(BrutalShape).clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(icon, fill = Palette.peach, size = 42.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.Title, color = Palette.ink)
            Text(subtitle, style = Type.Small, color = Palette.mutedInk)
        }
    }
}

/**
 * VIEW ALL: every folder on the phone as a grid (3 columns on phones, more on tablets), then the
 * hidden ones with UNHIDE. With a server, the header also opens the phone's own timeline and trash.
 */
@Composable
fun DeviceFoldersScreen(
    store: DeviceFolderStore,
    onFolder: (DeviceFolder) -> Unit,
    onBack: () -> Unit,
    onAccessChanged: () -> Unit,
    modifier: Modifier = Modifier,
    /** With a server: the phone's whole timeline. */
    onPhoneTimeline: (() -> Unit)? = null,
    /** With a server: the phone's Trash. */
    onPhoneTrash: (() -> Unit)? = null,
) {
    val permissions = LocalMediaPermissions.current
    val all by store.folders.collectAsState()
    val prefs by store.prefs.collectAsState()
    val error by store.error.collectAsState()
    LaunchedEffect(store, permissions.access) { if (permissions.access != MediaAccess.Denied) store.start() }
    var menu by remember { mutableStateOf<DeviceFolder?>(null) }
    val list = all
    val (hidden, shown) = remember(list, prefs) { list.orEmpty().partition(prefs::isHidden) }
    val hints = remember(list) { list?.let(DeviceFolders::pathHints).orEmpty() }

    Box(modifier.fillMaxSize().background(Palette.paper)) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(
                "On this device",
                subtitle = list?.takeIf { permissions.access != MediaAccess.Denied }?.let {
                    "${Format.plural(it.size, "folder")} · ${Format.plural(it.sumOf { f -> f.count }, "item")}"
                } ?: "Folders on this phone",
                onBack = onBack,
                actions = {
                    if (onPhoneTimeline != null) SquareIconButton(Icons.Rounded.PhotoLibrary, "All photos on this phone", onPhoneTimeline)
                    if (onPhoneTrash != null) SquareIconButton(Icons.Rounded.Delete, "Phone trash", onPhoneTrash)
                },
            )
            if (permissions.access == MediaAccess.Partial) LimitedAccessBanner(onManage = { permissions.request { onAccessChanged() } })
            when {
                permissions.access == MediaAccess.Denied -> MediaAccessDenied(
                    permanentlyDenied = permissions.permanentlyDenied,
                    onGrant = { permissions.request { if (it != MediaAccess.Denied) onAccessChanged() } },
                    onOpenSettings = permissions::openSettings,
                )
                list == null && error != null -> EmptyState(Icons.Rounded.FolderOff, "Couldn't read the folders", error.orEmpty()) {
                    BrutalButton("Try again", { store.refresh() })
                }
                list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { BrutalSpinner(size = 32.dp) }
                list.isEmpty() -> EmptyState(Icons.Rounded.Folder, "No folders yet", "Camera, Screenshots, Downloads and other folders with photos show up here.")
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(108.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 12.dp, top = 16.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (shown.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                        Text("Every folder is hidden from Library.", style = Type.Small, color = Palette.mutedInk)
                    }
                    items(shown, key = { it.bucketId }) { f ->
                        FolderCard(f, hints[f.bucketId], onClick = { onFolder(f) }, onLongClick = { menu = f }, notInPhotos = !prefs.showsInPhotos(f), onMore = { menu = f })
                    }
                    if (hidden.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "hidden-heading") {
                            SectionLabel("Hidden from Library · ${hidden.size}", Modifier.padding(top = 14.dp))
                        }
                        items(hidden, key = { "hidden-${it.bucketId}" }, span = { GridItemSpan(maxLineSpan) }) { f ->
                            HiddenFolderRow(f, hints[f.bucketId], onOpen = { onFolder(f) }, onUnhide = { store.setHidden(f.bucketId, false) })
                        }
                    }
                }
            }
        }
        FolderMenuSheet(
            menu, prefs, onDismiss = { menu = null },
            onHidden = { f, h -> store.setHidden(f.bucketId, h) },
            onShowInPhotos = { f, s -> store.setShowInPhotos(f.bucketId, s) },
        )
    }
}

/** A hidden folder: thumbnail, name and count, UNHIDE. */
@Composable
private fun HiddenFolderRow(folder: DeviceFolder, pathHint: String?, onOpen: () -> Unit, onUnhide: () -> Unit) {
    val urls = LocalMediaUrls.current
    Row(
        Modifier.fillMaxWidth().clip(BrutalShape).clickable(onClick = onOpen).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(52.dp).brutal(folderTint(folder.kind), shadow = 0.dp), contentAlignment = Alignment.Center) {
            val cover = folder.coverId
            if (cover != null) AssetImage(urls.thumbnail(cover), null, 120, 120, Modifier.matchParentSize())
            else Icon(folderIcon(folder.kind), null, tint = Palette.ink, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(folder.name, style = Type.Title, color = Palette.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(countLine(folder), pathHint).joinToString(" · "), style = Type.Small, color = Palette.mutedInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        BrutalButton("Unhide", onUnhide, style = ButtonStyle.Outline, icon = Icons.Rounded.Visibility)
    }
}

/**
 * One folder: its timeline (the normal grid, viewer, selection and actions) under a header with
 * the name, count and path. ⋯ toggles Show in Photos and Hide from Library.
 */
@Composable
fun DeviceFolderScreen(
    bucketId: Long,
    store: DeviceFolderStore,
    timeline: TimelineRepository,
    onOpen: (index: Int, asset: TimelineAsset) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    revealIndex: Int? = null,
    onRevealed: () -> Unit = {},
) {
    val all by store.folders.collectAsState()
    val prefs by store.prefs.collectAsState()
    LaunchedEffect(store) { store.start() }
    val folder = all?.firstOrNull { it.bucketId == bucketId }
    var menu by remember { mutableStateOf(false) }
    Box(modifier.fillMaxSize()) {
        TimelineScreen(
            timeline = timeline,
            title = folder?.name ?: "Folder",
            subtitle = folder?.let { "${countLine(it)} · ${DeviceFolders.pathHint(it)}" },
            onOpen = onOpen,
            onBack = onBack,
            revealIndex = revealIndex,
            onRevealed = onRevealed,
            emptyTitle = "This folder is empty",
            emptyText = "Photos and videos saved to this folder show up here.",
            headerActions = { if (folder != null) SquareIconButton(Icons.Rounded.MoreHoriz, "Folder options", { menu = true }) },
            topContent = folder?.let { f -> { FolderHeader(f, prefs) } },
        )
        FolderMenuSheet(
            folder?.takeIf { menu }, prefs, onDismiss = { menu = false },
            onHidden = { f, h -> store.setHidden(f.bucketId, h) },
            onShowInPhotos = { f, s -> store.setShowInPhotos(f.bucketId, s) },
        )
    }
}

/** Above a folder's photos: its kind and whether it's in Photos / hidden from Library. */
@Composable
private fun FolderHeader(folder: DeviceFolder, prefs: FolderPrefs) {
    val inPhotos = prefs.showsInPhotos(folder)
    Row(
        Modifier.fillMaxWidth().background(Palette.paper).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(folderIcon(folder.kind), null, tint = Palette.ink, modifier = Modifier.size(18.dp))
        Tag("On this device", fill = Palette.mint)
        if (inPhotos) Tag("In Photos", fill = Palette.yellow) else Tag("Not in Photos", fill = Palette.card)
        if (prefs.isHidden(folder)) Tag("Hidden", fill = Palette.sand)
        if (folder.removable) Tag("SD card", fill = Palette.lilac)
    }
}
