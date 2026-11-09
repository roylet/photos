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
import com.roylet.photos.domain.library.DeleteTarget
import com.roylet.photos.share.OptionSummary
import com.roylet.photos.share.ShareEstimator
import com.roylet.photos.share.ShareItemInfo
import com.roylet.photos.share.SharePhase
import com.roylet.photos.share.ShareQuality
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.PhoneAndroid

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
            is ActionSheet.DeleteChoice -> DeleteChoiceContent(s, actions::chooseDelete)
            is ActionSheet.Compress -> CompressContent(actions, s)
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
            ModeTile(Icons.Rounded.Send, "Send file", if (actions.canCompress) "Original or smaller" else "Originals to any app", selected = mode == "file", enabled = !albumMode, modifier = Modifier.weight(1f)) { mode = "file" }
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

/**
 * "Delete from:" for items that are both on this phone and in Immich. Each goes to that side's
 * Trash (undo brings it back); the answer is used for the rest of the session.
 */
@Composable
fun DeleteChoiceContent(sheet: ActionSheet.DeleteChoice, onChoose: (DeleteTarget) -> Unit) {
    val n = sheet.ids.size
    Text("Delete from", style = Type.Headline, color = Palette.ink)
    Text(
        when {
            n == 1 -> "This item is on this device and in Immich."
            sheet.paired == n -> "All ${Format.plural(n, "item")} are on this device and in Immich."
            else -> "${sheet.paired} of ${Format.plural(n, "item")} are on this device and in Immich. The others go from where they are."
        },
        style = Type.Small, color = Palette.mutedInk,
    )
    Spacer(Modifier.height(14.dp))
    // In a backed-up folder, "Immich" asks once more: the phone copy won't be backed up again.
    var confirmImmich by remember(sheet) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        DeleteOption(Icons.Rounded.PhoneAndroid, "This device", "Frees space on the phone. Immich keeps its copy.") { onChoose(DeleteTarget.Device) }
        DeleteOption(
            Icons.Rounded.Cloud,
            if (confirmImmich) "Immich only" else "Immich",
            if (confirmImmich) "Tap again to delete from Immich and keep the phone copy." else "Removes it from the server. The phone keeps its copy.",
            selected = confirmImmich,
        ) { if (sheet.backedUp && !confirmImmich) confirmImmich = true else onChoose(DeleteTarget.Immich) }
        if (confirmImmich) Text("It won't be backed up again. Delete from phone too?", style = Type.Body, color = Palette.ink)
        DeleteOption(Icons.Rounded.DeleteOutline, "Both", "Gone from the phone and from Immich.", danger = true, highlighted = confirmImmich) { onChoose(DeleteTarget.Both) }
    }
    Spacer(Modifier.height(12.dp))
    Text(
        "Everything goes to Trash first, so you can undo. Your answer is used until you close the app.",
        style = Type.Small, color = Palette.mutedInk,
    )
}

@Composable
private fun DeleteOption(
    icon: ImageVector,
    title: String,
    subtitle: String,
    danger: Boolean = false,
    selected: Boolean = false,
    /** A deeper, orange shadow: the suggested answer. */
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    Pressable(
        onClick,
        Modifier.fillMaxWidth(),
        fill = if (danger) Palette.red else if (selected) Palette.sand else Palette.card,
        shadow = if (highlighted) 6.dp else 3.dp,
        shadowColor = if (highlighted) Brutal.Orange else Palette.shadow,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            val ink = if (danger) Palette.onStrong else Palette.ink
            Icon(icon, null, tint = ink, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title.uppercase(), style = Type.Label, color = ink)
                Text(subtitle, style = Type.Small, color = if (danger) Palette.onStrong else Palette.mutedInk, maxLines = 2)
            }
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
    if (t != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("DOWNLOADING ${minOf(t.done + 1, t.count)} OF ${t.count}", style = Type.Label, color = Palette.ink, modifier = Modifier.weight(1f))
            if (t.totalBytes > 0) Text("${Format.bytes(t.bytes)} / ${Format.bytes(t.totalBytes)}", style = Type.Small, color = Palette.mutedInk)
        }
        Spacer(Modifier.height(8.dp))
        BrutalProgress(t.fraction, moving = true)
        Spacer(Modifier.height(14.dp))
        BrutalButton("Cancel", actions::cancelTransfer, style = ButtonStyle.Outline, modifier = Modifier.fillMaxWidth())
        return
    }
    if (!actions.canCompress) {
        Text(
            if (ids.size == 1) "Downloads the full-quality original, then opens Android's share sheet."
            else "Downloads ${ids.size} full-quality originals, then shares them together.",
            style = Type.Small, color = Palette.mutedInk,
        )
        Spacer(Modifier.height(14.dp))
        BrutalButton("Send ${Format.plural(ids.size, "file")}", { actions.sendFiles(ids) }, icon = Icons.Rounded.Share, large = true, modifier = Modifier.fillMaxWidth())
        return
    }
    // Sizes come from each item's details; until they're in, only Original can be sent.
    var measured by remember(ids) { mutableStateOf<List<ShareItemInfo>?>(null) }
    LaunchedEffect(ids) { measured = actions.measureShare(ids) }
    SendQualityPane(
        count = ids.size,
        measured = measured?.let { m -> val byId = m.associateBy { it.id }; ids.map { byId[it] ?: ShareItemInfo(it, it, null, null, null, null, null) } },
        remembered = ShareQuality.of(actions.prefs.shareQuality),
        onSend = { q -> actions.sendFiles(ids, q, measured.orEmpty()) },
    )
}

/** The quality choice for "Send file": Original / Smaller / Smallest with sizes, then the send button. */
@Composable
fun SendQualityPane(count: Int, measured: List<ShareItemInfo>?, remembered: ShareQuality, onSend: (ShareQuality) -> Unit) {
    val summaries = remember(measured) { measured?.let { items -> ShareQuality.entries.associateWith { ShareEstimator.summary(items, it) } } }
    var choice by remember { mutableStateOf(remembered) }
    // A remembered choice that doesn't help this selection shows as Original (the memory stays).
    val effective = if (choice == ShareQuality.ORIGINAL || summaries?.get(choice)?.enabled != false) choice else ShareQuality.ORIGINAL
    Text("QUALITY", style = Type.Tiny, color = Palette.mutedInk)
    Spacer(Modifier.height(8.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ShareQuality.entries.forEach { q ->
            QualityRow(q, summaries?.get(q), original = summaries?.get(ShareQuality.ORIGINAL), selected = effective == q) { choice = q }
        }
    }
    Spacer(Modifier.height(10.dp))
    val s = summaries?.get(effective)
    Text(
        when {
            effective == ShareQuality.ORIGINAL ->
                if (count == 1) "Sends the full-quality original to any app." else "Sends $count full-quality originals together."
            s == null -> "Measuring…"
            s.compressCount == count -> "Makes ${if (count == 1) "a smaller copy" else "smaller copies"} first. Keep this open while it works."
            else -> "Makes smaller copies of ${s.compressCount} of $count; the rest are already small and go as they are."
        },
        style = Type.Small, color = Palette.mutedInk,
    )
    Spacer(Modifier.height(14.dp))
    val ready = effective == ShareQuality.ORIGINAL || summaries != null
    BrutalButton(
        if (effective == ShareQuality.ORIGINAL) "Send ${Format.plural(count, "file")}" else "Compress & send",
        { onSend(effective) },
        icon = Icons.Rounded.Share, large = true, enabled = ready, modifier = Modifier.fillMaxWidth().testTag("sendFiles"),
    )
}

@Composable
private fun QualityRow(quality: ShareQuality, summary: OptionSummary?, original: OptionSummary?, selected: Boolean, onClick: () -> Unit) {
    val enabled = summary?.enabled ?: (quality == ShareQuality.ORIGINAL)
    val ink = if (selected) Brutal.Ink else Palette.ink
    val muted = if (selected) Brutal.Ink else Palette.mutedInk
    Pressable(
        onClick, Modifier.fillMaxWidth().testTag("quality-${quality.name}").graphicsLayer { alpha = if (enabled) 1f else 0.45f },
        fill = if (selected) Brutal.Orange else Palette.card, shadow = if (selected) 4.dp else 2.dp, enabled = enabled,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioDot(selected, ink)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (quality == ShareQuality.ORIGINAL) "ORIGINAL" else "COMPRESSED: ${quality.title.uppercase()}", style = Type.Label, color = ink)
                Text(quality.subtitle, style = Type.Small, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                val size = when {
                    summary == null -> "…"
                    !enabled -> "—"
                    summary.totalBytes <= 0 -> "?"
                    quality == ShareQuality.ORIGINAL -> Format.bytes(summary.totalBytes) + if (summary.partial) "+" else ""
                    else -> "~" + Format.bytes(summary.totalBytes) + if (summary.partial) "+" else ""
                }
                Text(size, style = Type.Label, color = ink)
                val note = when {
                    summary == null || quality == ShareQuality.ORIGINAL -> null
                    !enabled -> "Not smaller"
                    original != null && original.totalBytes > 0 && !original.partial && !summary.partial ->
                        "−${((1 - summary.totalBytes.toDouble() / original.totalBytes) * 100).toInt().coerceIn(0, 99)}%"
                    else -> null
                }
                if (note != null) Text(note, style = Type.Small, color = muted)
            }
        }
    }
}

@Composable
private fun RadioDot(selected: Boolean, ink: androidx.compose.ui.graphics.Color) {
    Box(Modifier.size(20.dp).border(2.dp, ink, RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
        if (selected) Box(Modifier.size(10.dp).background(ink, RoundedCornerShape(50)))
    }
}

/* ---------------- Compress progress ---------------- */

/**
 * "Compressing 2 of 3 · 45%" with CANCEL. Compression runs while this sheet is shown: leaving it
 * (CANCEL, back, a tap outside) cancels; when it's done Android's share sheet opens.
 */
@Composable
fun CompressContent(actions: LibraryActions, sheet: ActionSheet.Compress) {
    LaunchedEffect(sheet) { actions.runCompression(sheet) }
    CompressProgressPane(
        sheet, actions.compressing,
        onCancel = actions::closeSheet,
        onSendOriginal = actions::sendOriginalInstead,
    )
}

@Composable
fun CompressProgressPane(sheet: ActionSheet.Compress, state: CompressState?, onCancel: () -> Unit, onSendOriginal: () -> Unit) {
    when (state) {
        is CompressState.Failed -> {
            SheetTitle("Couldn't compress", sheet.quality.title)
            Spacer(Modifier.height(8.dp))
            Text(state.message, style = Type.Body, color = Palette.ink)
            Spacer(Modifier.height(6.dp))
            Text("The originals can still go: they're bigger, but they'll arrive as they are.", style = Type.Small, color = Palette.mutedInk)
            Spacer(Modifier.height(14.dp))
            BrutalButton("Send original instead", onSendOriginal, icon = Icons.Rounded.Share, large = true, modifier = Modifier.fillMaxWidth().testTag("sendOriginal"))
            Spacer(Modifier.height(10.dp))
            BrutalButton("Close", onCancel, style = ButtonStyle.Outline, modifier = Modifier.fillMaxWidth())
        }
        else -> {
            val p = (state as? CompressState.Running)?.progress
            val count = p?.count ?: sheet.items.size
            val n = minOf((p?.index ?: 0) + 1, count)
            val overall = ((p?.overall ?: 0f) * 100).toInt().coerceIn(0, 100)
            SheetTitle("Preparing to send", sheet.quality.title)
            Spacer(Modifier.height(12.dp))
            Text(
                if (count == 1) "COMPRESSING · $overall%" else "COMPRESSING $n OF $count · $overall%",
                style = Type.Label, color = Palette.ink, modifier = Modifier.testTag("compressProgress"),
            )
            Spacer(Modifier.height(8.dp))
            BrutalProgress(p?.overall ?: 0f, moving = true)
            if (p != null && (count > 1 || p.phase == SharePhase.Downloading)) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.name, style = Type.Small, color = Palette.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    val what = when (p.phase) {
                        SharePhase.Preparing -> "Starting"
                        SharePhase.Downloading -> "Downloading"
                        SharePhase.Compressing -> "Encoding"
                    }
                    Text("$what ${(p.itemFraction * 100).toInt()}%", style = Type.Small, color = Palette.mutedInk)
                }
                Spacer(Modifier.height(6.dp))
                BrutalProgress(p.itemFraction, height = 8.dp, fill = Palette.mint)
            }
            Spacer(Modifier.height(10.dp))
            Text("Keep this open: closing it stops compressing. Android's share sheet opens when it's done.", style = Type.Small, color = Palette.mutedInk)
            Spacer(Modifier.height(14.dp))
            BrutalButton("Cancel", onCancel, style = ButtonStyle.Outline, modifier = Modifier.fillMaxWidth().testTag("cancelCompress"))
        }
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
