package com.roylet.photos.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.roylet.photos.AppContainer
import com.roylet.photos.backup.BackupExclusion
import com.roylet.photos.backup.BackupFailure
import com.roylet.photos.backup.BackupFolder
import com.roylet.photos.backup.BackupOptions
import com.roylet.photos.backup.BackupState
import com.roylet.photos.backup.FolderBackupStatus
import com.roylet.photos.domain.source.AssetRef
import com.roylet.photos.ui.components.BrutalButton
import com.roylet.photos.ui.components.BrutalCard
import com.roylet.photos.ui.components.BrutalProgress
import com.roylet.photos.ui.components.BrutalShape
import com.roylet.photos.ui.components.BrutalSwitch
import com.roylet.photos.ui.components.ButtonStyle
import com.roylet.photos.ui.components.Chip
import com.roylet.photos.ui.components.IconBadge
import com.roylet.photos.ui.components.ScreenHeader
import com.roylet.photos.ui.components.SectionLabel
import com.roylet.photos.ui.components.Tag
import com.roylet.photos.ui.components.ToggleRow
import com.roylet.photos.ui.components.brutal
import com.roylet.photos.ui.gallery.AssetImage
import com.roylet.photos.ui.gallery.LocalMediaUrls
import com.roylet.photos.ui.theme.Palette
import com.roylet.photos.ui.theme.Type
import com.roylet.photos.util.Format

/*
 * Settings → Backup (route `backup`, docs/SOURCES.md §10): the master switch with status and
 * progress, the phone's folders with counts and toggles, the options, Back up now, and the
 * "Couldn't back up" / "Not backing up" lists.
 */

/**
 * What folder cards and folder screens need to show and switch backup (Library → On this device).
 * Provided by AppNav on those routes; null (screenshots, no backup) hides it.
 */
@Immutable
class FolderBackupUi(
    val statuses: Map<Long, FolderBackupStatus>,
    /** Backup is on, with a server, not paused: an enabled folder is being backed up. */
    val active: Boolean,
    val onToggle: (bucketId: Long, enabled: Boolean) -> Unit,
    /** Opens the Backup screen. */
    val onOpenBackup: () -> Unit,
) {
    fun status(bucketId: Long): FolderBackupStatus? = statuses[bucketId]
}

val LocalFolderBackup = staticCompositionLocalOf<FolderBackupUi?> { null }

/** Provides [LocalFolderBackup] from `container.backup` for [content] (the Library routes). */
@Composable
fun ProvideFolderBackup(container: AppContainer, onOpenBackup: () -> Unit, content: @Composable () -> Unit) {
    val backup = container.backup
    val folders by backup.folders.collectAsState()
    val options by backup.options.collectAsState()
    val server by container.settings.server.collectAsState()
    val ui = remember(folders, options, server) {
        FolderBackupUi(
            statuses = folders.orEmpty().associate { it.bucketId to it.status },
            active = options.enabled && !options.paused && server != null,
            onToggle = backup::setFolderEnabled,
            onOpenBackup = onOpenBackup,
        )
    }
    CompositionLocalProvider(LocalFolderBackup provides ui, content = content)
}

@Composable
fun BackupScreen(container: AppContainer, onBack: () -> Unit, onConnect: () -> Unit) {
    val backup = container.backup
    val server by container.settings.server.collectAsState()
    val state by backup.state.collectAsState()
    val options by backup.options.collectAsState()
    val folders by backup.folders.collectAsState()
    val failures by backup.failures.collectAsState(emptyList())
    val exclusions by backup.exclusions.collectAsState(emptyList())
    // The first switch-on asks for notifications (Android 13+): the progress notification needs them.
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    BackupContent(
        hasServer = server != null,
        state = state,
        options = options,
        folders = folders,
        failures = failures,
        exclusions = exclusions,
        onBack = onBack,
        onConnect = onConnect,
        onEnabled = { on ->
            if (on && !container.backupPrefs.askedNotifications && Build.VERSION.SDK_INT >= 33) {
                container.backupPrefs.askedNotifications = true
                runCatching { askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
            }
            backup.setEnabled(on)
        },
        onFolder = backup::setFolderEnabled,
        onWifiOnly = backup::setWifiOnly,
        onCharging = backup::setWhileChargingOnly,
        onVideos = backup::setIncludeVideos,
        onFromNowOn = backup::setFromNowOn,
        onBackUpNow = backup::backUpNow,
        onPause = backup::pause,
        onResume = backup::resume,
        onRetryFailed = backup::retryFailed,
        onAllowAgain = backup::allowBackupAgain,
    )
}

/** The Backup screen, stateless (screenshots use sample data). */
@Composable
fun BackupContent(
    hasServer: Boolean,
    state: BackupState,
    options: BackupOptions,
    folders: List<BackupFolder>?,
    failures: List<BackupFailure>,
    exclusions: List<BackupExclusion>,
    onBack: () -> Unit,
    onConnect: () -> Unit = {},
    onEnabled: (Boolean) -> Unit = {},
    onFolder: (Long, Boolean) -> Unit = { _, _ -> },
    onWifiOnly: (Boolean) -> Unit = {},
    onCharging: (Boolean) -> Unit = {},
    onVideos: (Boolean) -> Unit = {},
    onFromNowOn: (Boolean) -> Unit = {},
    onBackUpNow: () -> Unit = {},
    onPause: () -> Unit = {},
    onResume: () -> Unit = {},
    onRetryFailed: () -> Unit = {},
    onAllowAgain: (Long) -> Unit = {},
) {
    Column(Modifier.fillMaxSize().background(Palette.paper)) {
        ScreenHeader("Backup", subtitle = "Phone folders to Immich", onBack = onBack)
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp).windowInsetsPadding(WindowInsets.navigationBars),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!hasServer) {
                NoServerCard(onConnect)
                return@Column
            }
            StatusCard(state, options, onEnabled, onBackUpNow, onPause, onResume)

            Spacer(Modifier.height(4.dp))
            SectionLabel("Folders to back up")
            FoldersCard(folders, onFolder)

            Spacer(Modifier.height(4.dp))
            SectionLabel("Options")
            BrutalCard(Modifier.fillMaxWidth()) {
                ToggleRow("Wi-Fi only", "Don't use mobile data", options.wifiOnly, onWifiOnly)
                ToggleRow("Only while charging", "Wait until the phone is plugged in", options.whileChargingOnly, onCharging)
                ToggleRow("Include videos", "Videos can be large", options.includeVideos, onVideos)
                Spacer(Modifier.height(8.dp))
                Text("Back up", style = Type.Title.copy(fontSize = Type.Body.fontSize), color = Palette.ink)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Everything", selected = options.newerThan == null, onClick = { onFromNowOn(false) })
                    Chip("From now on", selected = options.newerThan != null, onClick = { onFromNowOn(true) })
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (options.newerThan == null) "Every photo and video in the selected folders." else "Only photos and videos taken from ${java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(options.newerThan))} on.",
                    style = Type.Small, color = Palette.mutedInk,
                )
            }

            if (failures.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                SectionLabel("Couldn't back up (${failures.size})", trailing = { SmallAction("Try again", onRetryFailed) })
                BrutalCard(Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
                    failures.take(50).forEach { f -> ItemRow(f.localId, f.name, f.reason, Icons.Rounded.ErrorOutline) }
                    if (failures.size > 50) Text("and ${failures.size - 50} more", style = Type.Small, color = Palette.mutedInk)
                }
            }
            if (exclusions.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                SectionLabel("Not backing up (${exclusions.size})")
                Text("Deleted from Immich and kept on the phone, so they aren't uploaded again. ALLOW backs one up again.", style = Type.Small, color = Palette.mutedInk)
                BrutalCard(Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
                    exclusions.take(50).forEach { e ->
                        ItemRow(e.localId, e.name, e.reason, Icons.Rounded.CloudOff) { SmallAction("Allow") { onAllowAgain(e.localId) } }
                    }
                    if (exclusions.size > 50) Text("and ${exclusions.size - 50} more", style = Type.Small, color = Palette.mutedInk)
                }
            }
            Text(
                "Backup only adds to Immich: nothing is ever deleted from the phone or from Immich. Live photos are backed up as the still image.",
                style = Type.Small, color = Palette.mutedInk,
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun NoServerCard(onConnect: () -> Unit) {
    BrutalCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.CloudOff, fill = Palette.sand, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Text("Connect to Immich to back up", style = Type.Title, color = Palette.ink, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Backup copies new photos and videos from the folders you pick to your own Immich server, in the background.",
            style = Type.Small, color = Palette.mutedInk,
        )
        Spacer(Modifier.height(12.dp))
        BrutalButton("Connect", onConnect, icon = Icons.Rounded.Cloud, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun StatusCard(
    state: BackupState,
    options: BackupOptions,
    onEnabled: (Boolean) -> Unit,
    onBackUpNow: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
) {
    BrutalCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clip(BrutalShape).clickable { onEnabled(!options.enabled) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBadge(statusIcon(state), fill = statusFill(state), size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Back up to Immich", style = Type.Title, color = Palette.ink)
                Text(statusLine(state), style = Type.Small, color = Palette.mutedInk, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            BrutalSwitch(options.enabled)
        }
        if (!options.enabled) return@BrutalCard
        val progress = when (state) {
            is BackupState.Uploading -> ((state.current - 1) + state.fraction) / state.total.coerceAtLeast(1)
            else -> null
        }
        if (state is BackupState.Uploading || state is BackupState.Scanning) {
            Spacer(Modifier.height(12.dp))
            BrutalProgress(progress ?: 0f, Modifier.fillMaxWidth(), height = 12.dp, moving = progress == null)
            if (state is BackupState.Uploading && state.name != null) {
                Spacer(Modifier.height(4.dp))
                Text(state.name, style = Type.Small, color = Palette.mutedInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BrutalButton(
                "Back up now", onBackUpNow, icon = Icons.Rounded.CloudUpload, modifier = Modifier.weight(1f),
                enabled = state !is BackupState.Uploading && state !is BackupState.Scanning,
            )
            if (state is BackupState.Paused) BrutalButton("Resume", onResume, style = ButtonStyle.Outline, icon = Icons.Rounded.PlayArrow)
            else BrutalButton("Pause", onPause, style = ButtonStyle.Outline, icon = Icons.Rounded.Pause)
        }
    }
}

@Composable
private fun FoldersCard(folders: List<BackupFolder>?, onFolder: (Long, Boolean) -> Unit) {
    BrutalCard(Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
        when {
            folders == null -> Text("Reading folders…", style = Type.Small, color = Palette.mutedInk, modifier = Modifier.padding(vertical = 8.dp))
            folders.isEmpty() -> Text("No folders with photos on this phone yet.", style = Type.Small, color = Palette.mutedInk, modifier = Modifier.padding(vertical = 8.dp))
            else -> folders.forEachIndexed { i, f ->
                if (i > 0) Box(Modifier.fillMaxWidth().height(2.dp).background(Palette.sand))
                FolderRow(f, onFolder)
            }
        }
    }
}

@Composable
private fun FolderRow(f: BackupFolder, onFolder: (Long, Boolean) -> Unit) {
    val urls = LocalMediaUrls.current
    val s = f.status
    Row(
        Modifier.fillMaxWidth().clip(BrutalShape).clickable { onFolder(f.bucketId, !s.enabled) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(52.dp).brutal(Palette.sand, shadow = 0.dp)) {
            AssetImage(urls.thumbnail(AssetRef.local(f.coverId).id), null, 120, 120, Modifier.matchParentSize())
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(f.name, style = Type.Title, color = Palette.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(folderLine(f), style = Type.Small, color = Palette.mutedInk, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        BrutalSwitch(s.enabled)
    }
}

@Composable
private fun ItemRow(localId: Long, name: String?, reason: String, icon: ImageVector, action: (@Composable () -> Unit)? = null) {
    val urls = LocalMediaUrls.current
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).brutal(Palette.sand, shadow = 0.dp), contentAlignment = Alignment.Center) {
            AssetImage(urls.thumbnail(AssetRef.local(localId).id), null, 96, 96, Modifier.matchParentSize())
            Icon(icon, null, tint = Palette.ink, modifier = Modifier.align(Alignment.BottomEnd).padding(2.dp).size(14.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(name ?: "Item $localId", style = Type.Body, color = Palette.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(reason, style = Type.Small, color = Palette.mutedInk, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (action != null) { Spacer(Modifier.width(8.dp)); action() }
    }
}

@Composable
private fun SmallAction(text: String, onClick: () -> Unit) {
    Text(
        text.uppercase(), style = Type.Label, color = Palette.ink, maxLines = 1,
        modifier = Modifier.clip(BrutalShape).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 4.dp),
    )
}

/** `64 items · 40 backed up · 24 to go`. */
fun folderLine(f: BackupFolder): String {
    val s = f.status
    val parts = mutableListOf("${Format.count(f.count)} ${if (f.count == 1) "item" else "items"}")
    if (s.enabled) {
        parts += if (s.pending == 0 && s.eligible > 0) "All backed up" else "${Format.count(s.backedUp)} backed up"
        if (s.pending > 0) parts += "${Format.count(s.pending)} to go"
        if (s.failed > 0) parts += "${s.failed} failed"
    } else if (s.backedUp > 0) {
        parts += "${Format.count(s.backedUp)} in Immich"
    }
    return parts.joinToString(" · ")
}

/** The status line under the master switch. */
fun statusLine(state: BackupState): String = when (state) {
    BackupState.Off -> "Off. Turn on to copy new photos to Immich."
    BackupState.NoServer -> "Connect to Immich to back up"
    is BackupState.Idle -> if (state.pending == 0) "Everything is backed up" else "${Format.plural(state.pending, "item")} to back up · next check within the hour"
    BackupState.Scanning -> "Checking what Immich already has"
    is BackupState.Uploading -> "Backing up ${Format.count(state.current)} of ${Format.count(state.total)}"
    is BackupState.WaitingForWifi -> "Waiting for Wi-Fi · ${Format.plural(state.pending, "item")} to go"
    is BackupState.WaitingForCharging -> "Waiting for charging · ${Format.plural(state.pending, "item")} to go"
    BackupState.Paused -> "Paused"
    is BackupState.Error -> if (state.signIn) "Paused: sign in to Immich again (${state.message})" else "Couldn't reach Immich, trying again soon (${state.message})"
}

private fun statusIcon(state: BackupState): ImageVector = when (state) {
    BackupState.Off, BackupState.NoServer -> Icons.Rounded.CloudOff
    is BackupState.Idle -> if (state.pending == 0) Icons.Rounded.CloudDone else Icons.Rounded.Backup
    BackupState.Scanning, is BackupState.Uploading -> Icons.Rounded.CloudUpload
    is BackupState.WaitingForWifi -> Icons.Rounded.Wifi
    is BackupState.WaitingForCharging -> Icons.Rounded.Refresh
    BackupState.Paused -> Icons.Rounded.Pause
    is BackupState.Error -> Icons.Rounded.ErrorOutline
}

@Composable
private fun statusFill(state: BackupState) = when (state) {
    is BackupState.Idle -> if (state.pending == 0) Palette.mint else Palette.yellow
    BackupState.Scanning, is BackupState.Uploading -> Palette.lilac
    is BackupState.Error -> Palette.peach
    else -> Palette.sand
}

/** For Settings: a one-line summary of backup. */
@Composable
fun BackupSettingsCard(state: BackupState, onOpen: () -> Unit) {
    BrutalCard(Modifier.fillMaxWidth(), onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(statusIcon(state), fill = statusFill(state), size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Backup", style = Type.Title, color = Palette.ink)
                    Spacer(Modifier.width(8.dp))
                    if (state is BackupState.Off || state is BackupState.NoServer) Tag("Off", fill = Palette.card) else Tag("On", fill = Palette.mint)
                }
                Text(statusLine(state), style = Type.Small, color = Palette.mutedInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
