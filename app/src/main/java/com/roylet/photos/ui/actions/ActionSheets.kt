package com.roylet.photos.ui.actions

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhotoAlbum
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Share
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.roylet.photos.domain.library.Album
import com.roylet.photos.domain.library.AlbumSort
import com.roylet.photos.domain.library.ShareLinkOptions
import com.roylet.photos.domain.source.Capability
import com.roylet.photos.ui.sources.LocalCapabilities
import com.roylet.photos.ui.sources.forIds
import com.roylet.photos.ui.components.BrutalButton
import com.roylet.photos.ui.components.BrutalCheck
import com.roylet.photos.ui.components.BrutalProgress
import com.roylet.photos.ui.components.BrutalSheet
import com.roylet.photos.ui.components.BrutalShape
import com.roylet.photos.ui.components.BrutalSpinner
import com.roylet.photos.ui.components.BrutalTextField
import com.roylet.photos.ui.components.ButtonStyle
import com.roylet.photos.ui.components.Chip
import com.roylet.photos.ui.components.Pressable
import com.roylet.photos.ui.components.ToggleRow
import com.roylet.photos.ui.components.brutal
import com.roylet.photos.ui.components.reducedMotion
import com.roylet.photos.ui.gallery.AssetImage
import com.roylet.photos.ui.gallery.LocalMediaUrls
import com.roylet.photos.ui.theme.Brutal
import com.roylet.photos.ui.theme.Palette
import com.roylet.photos.ui.theme.Type
import com.roylet.photos.util.Format

/** Draws whichever sheet [actions] has open. Place it above the app content. */
@Composable
fun ActionSheets(actions: LibraryActions, modifier: Modifier = Modifier) {
    val sheet = actions.sheet
    // Keep drawing the last sheet while it slides away.
    var last by remember { mutableStateOf(sheet) }
    if (sheet != null) last = sheet
    BrutalSheet(visible = sheet != null, onDismiss = actions::closeSheet, modifier = modifier) {
        when (val s = last) {
            is ActionSheet.AddToAlbum -> AddToAlbumContent(actions, s)
            is ActionSheet.Share -> ShareContent(actions, s)
            null -> Unit
        }
    }
}

@Composable
private fun SheetTitle(title: String, subtitle: String?) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(title, style = Type.Headline, color = Palette.ink, modifier = Modifier.weight(1f))
        if (subtitle != null) Text(subtitle.uppercase(), style = Type.Tiny, color = Palette.mutedInk, modifier = Modifier.padding(bottom = 4.dp))
    }
}

/* ---------------- Add to album ---------------- */

@Composable
fun AddToAlbumContent(actions: LibraryActions, sheet: ActionSheet.AddToAlbum) {
    val all by actions.albums.albums.collectAsState()
    var query by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    // Albums holding every selected asset; flipped optimistically on tap.
    var members by remember(sheet.ids) { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(sheet.ids) { members = actions.membership(sheet.ids) }

    SheetTitle("Add to album", Format.plural(sheet.ids.size, "item"))
    Spacer(Modifier.height(12.dp))

    AnimatedContent(creating, transitionSpec = { fadeIn(tween(140)) togetherWith fadeOut(tween(100)) }, label = "new") { open ->
        if (open) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BrutalTextField(
                    newName, { newName = it }, "Album name",
                    modifier = Modifier.weight(1f).testTag("newAlbumName"),
                    keyboard = KeyboardOptions(imeAction = ImeAction.Done),
                )
                Spacer(Modifier.width(8.dp))
                BrutalButton("Create", {
                    actions.createAlbum(newName, sheet.ids) { actions.closeSheet() }
                }, enabled = newName.isNotBlank(), modifier = Modifier.testTag("createAlbum"))
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                BrutalTextField(query, { query = it }, "Find an album", leading = Icons.Rounded.Search, modifier = Modifier.weight(1f))
                BrutalButton("New album", { creating = true }, icon = Icons.Rounded.Add, modifier = Modifier.testTag("newAlbum"))
            }
        }
    }
    Spacer(Modifier.height(10.dp))

    val list = all
    if (list == null) {
        Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { BrutalSpinner(size = 28.dp) }
        return
    }
    val shown = remember(list, query) {
        AlbumSort.Recent.apply(list).filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
    }
    if (shown.isEmpty()) {
        Text(
            if (list.isEmpty()) "No albums yet. Make one with New album." else "No album called “${query.trim()}”.",
            style = Type.Body, color = Palette.mutedInk, modifier = Modifier.padding(vertical = 24.dp),
        )
        return
    }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
        items(shown, key = { it.id }) { album ->
            val checked = album.id in members
            AlbumPickRow(album, checked) {
                val add = !checked
                members = if (add) members + album.id else members - album.id
                actions.toggleAlbum(album, sheet.ids, add) { now -> members = if (now) members + album.id else members - album.id }
            }
        }
    }
}

@Composable
private fun AlbumPickRow(album: Album, checked: Boolean, onClick: () -> Unit) {
    val reduced = reducedMotion()
    val pop = remember { Animatable(1f) }
    var last by remember { mutableStateOf(checked) }
    LaunchedEffect(checked) {
        if (checked != last && checked && !reduced) { pop.snapTo(0.6f); pop.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = 800f)) }
        last = checked
    }
    Row(
        Modifier.fillMaxWidth().clip(BrutalShape).clickable(onClick = onClick).padding(vertical = 6.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumThumb(album.coverAssetId, 52.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(album.name, style = Type.Title, color = Palette.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(Format.plural(album.assetCount, "item"), if (album.shared) "Shared" else null).joinToString(" · ")
            Text(meta, style = Type.Small, color = Palette.mutedInk, maxLines = 1)
        }
        BrutalCheck(checked, Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value }, size = 28.dp)
        Spacer(Modifier.width(4.dp))
    }
}

/** A square album cover with an ink outline; a lilac album glyph when there's no cover yet. */
@Composable
fun AlbumThumb(coverId: String?, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    val urls = LocalMediaUrls.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val shape = RoundedCornerShape(6.dp)
    Box(modifier.size(size).clip(shape).background(Palette.lilac, shape).border(2.dp, Palette.ink, shape), contentAlignment = Alignment.Center) {
        if (coverId != null) AssetImage(urls.thumbnail(coverId), null, px, px, Modifier.matchParentSize())
        else Icon(Icons.Rounded.PhotoAlbum, null, tint = Palette.ink, modifier = Modifier.size(size * 0.45f))
    }
}

/* ---------------- Share ---------------- */

private val expiryChoices = listOf<Pair<String, Int?>>("Never" to null, "1 day" to 1, "1 week" to 7, "1 month" to 30)

@Composable
fun ShareContent(actions: LibraryActions, sheet: ActionSheet.Share) {
    val albumMode = sheet.albumId != null
    // Share links need Immich (and an asset it has); without them it's "send file" only.
    val links = Capability.SHARE_LINKS in LocalCapabilities.current.forIds(sheet.ids)
    var mode by remember(sheet, links) { mutableStateOf(if (albumMode) "link" else if (!links) "file" else actions.prefs.shareMode) }
    val albumName = sheet.albumId?.let { actions.albums.find(it)?.name }
    SheetTitle("Share", if (albumMode) albumName ?: "Album" else Format.plural(sheet.ids.size, "item"))
    Spacer(Modifier.height(14.dp))
    if (links || albumMode) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ModeTile(Icons.Rounded.Send, "Send file", "Originals to any app", selected = mode == "file", enabled = !albumMode, modifier = Modifier.weight(1f)) { mode = "file" }
            ModeTile(Icons.Rounded.Link, "Share link", "A link anyone can open", selected = mode == "link", modifier = Modifier.weight(1f)) { mode = "link" }
        }
        Spacer(Modifier.height(16.dp))
    }
    AnimatedContent(mode, transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(100)) }, label = "shareMode") { m ->
        Column {
            if (m == "file") SendFilePane(actions, sheet.ids) else LinkPane(actions, sheet)
        }
    }
}

@Composable
private fun ModeTile(icon: ImageVector, title: String, subtitle: String, selected: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Pressable(onClick, modifier, fill = if (selected) Brutal.Orange else Palette.card, shadow = if (selected) 4.dp else 3.dp, enabled = enabled) {
        Column(Modifier.padding(12.dp)) {
            Icon(icon, null, tint = if (selected) Brutal.Ink else Palette.ink, modifier = Modifier.size(24.dp))
            Spacer(Modifier.height(8.dp))
            Text(title.uppercase(), style = Type.Label, color = if (selected) Brutal.Ink else Palette.ink)
            Text(subtitle, style = Type.Small, color = if (selected) Brutal.Ink else Palette.mutedInk, maxLines = 2)
        }
    }
}

@Composable
private fun SendFilePane(actions: LibraryActions, ids: List<String>) {
    val t = actions.transfer
    if (t == null) {
        Text(
            if (ids.size == 1) "Downloads the full-quality original, then opens Android's share sheet."
            else "Downloads ${ids.size} full-quality originals, then shares them together.",
            style = Type.Small, color = Palette.mutedInk,
        )
        Spacer(Modifier.height(14.dp))
        BrutalButton("Send ${Format.plural(ids.size, "file")}", { actions.sendFiles(ids) }, icon = Icons.Rounded.Share, large = true, modifier = Modifier.fillMaxWidth())
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("DOWNLOADING ${minOf(t.done + 1, t.count)} OF ${t.count}", style = Type.Label, color = Palette.ink, modifier = Modifier.weight(1f))
            if (t.totalBytes > 0) Text("${Format.bytes(t.bytes)} / ${Format.bytes(t.totalBytes)}", style = Type.Small, color = Palette.mutedInk)
        }
        Spacer(Modifier.height(8.dp))
        BrutalProgress(t.fraction, moving = true)
        Spacer(Modifier.height(14.dp))
        BrutalButton("Cancel", actions::cancelTransfer, style = ButtonStyle.Outline, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun LinkPane(actions: LibraryActions, sheet: ActionSheet.Share) {
    when (val l = actions.link) {
        is LinkState.Ready -> {
            Text("YOUR LINK", style = Type.Tiny, color = Palette.mutedInk)
            Spacer(Modifier.height(6.dp))
            Text(
                l.url, style = Type.Body.copy(fontFamily = FontFamily.Monospace), color = Palette.ink, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().brutal(Palette.sand, shadow = 0.dp).padding(12.dp),
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BrutalButton("Share", { actions.sendLink(l.url) }, icon = Icons.Rounded.Share, modifier = Modifier.weight(1f))
                BrutalButton("Copy", { actions.copyLink(l.url) }, icon = Icons.Rounded.ContentCopy, style = ButtonStyle.Outline, modifier = Modifier.weight(1f))
            }
        }
        else -> {
            var expiry by remember { mutableStateOf<Int?>(null) }
            var download by remember { mutableStateOf(true) }
            var metadata by remember { mutableStateOf(true) }
            var password by remember { mutableStateOf("") }
            Text("EXPIRES", style = Type.Tiny, color = Palette.mutedInk)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                expiryChoices.forEach { (label, days) -> Chip(label, selected = expiry == days, onClick = { expiry = days }) }
            }
            Spacer(Modifier.height(8.dp))
            ToggleRow("Allow download", "Visitors can save the originals", download, { download = it })
            ToggleRow("Show metadata", "Dates, places and camera details", metadata, { metadata = it })
            Spacer(Modifier.height(6.dp))
            BrutalTextField(password, { password = it }, "Password (optional)", password = true, leading = Icons.Rounded.Lock)
            Spacer(Modifier.height(14.dp))
            val busy = l is LinkState.Creating
            BrutalButton(
                if (busy) "Creating…" else "Create link",
                { actions.createLink(sheet.ids, sheet.albumId, ShareLinkOptions(expiry, download, metadata, password.ifBlank { null })) },
                icon = Icons.Rounded.Link, large = true, enabled = !busy, modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
