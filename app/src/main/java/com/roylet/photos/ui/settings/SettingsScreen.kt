package com.roylet.photos.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AutoMode
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.roylet.photos.AppContainer
import com.roylet.photos.data.ConnectionMode
import com.roylet.photos.data.ServerConfig
import com.roylet.photos.net.ConnState
import com.roylet.photos.net.ConnectionCheck
import com.roylet.photos.net.StepStatus
import com.roylet.photos.net.LocalUrlResult
import com.roylet.photos.net.ScanState
import com.roylet.photos.ui.components.BrutalButton
import com.roylet.photos.ui.components.BrutalCard
import com.roylet.photos.ui.components.BrutalProgress
import com.roylet.photos.ui.components.BrutalTextField
import com.roylet.photos.ui.components.ButtonStyle
import com.roylet.photos.ui.components.Chip
import com.roylet.photos.ui.components.ConnectionChip
import com.roylet.photos.ui.components.IconBadge
import com.roylet.photos.ui.components.Notice
import com.roylet.photos.ui.components.NoticeTone
import com.roylet.photos.ui.components.ScreenHeader
import com.roylet.photos.ui.components.SectionLabel
import com.roylet.photos.ui.components.Tag
import com.roylet.photos.data.ThemeMode
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.TouchApp
import com.roylet.photos.ui.permissions.LocalMediaPermissions
import com.roylet.photos.ui.permissions.MediaAccess
import com.roylet.photos.ui.permissions.MediaPermissionsState
import com.roylet.photos.ui.theme.Brutal
import com.roylet.photos.ui.theme.Palette
import com.roylet.photos.ui.theme.Type
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit, onConnect: () -> Unit = {}) {
    val server by container.settings.server.collectAsState()
    val state by container.connection.state.collectAsState()
    val activeUrl by container.connection.activeUrl.collectAsState()
    val scan by container.connection.scan.collectAsState()
    val check by container.connection.check.collectAsState()
    val theme by container.uiPrefs.themeMode.collectAsState()
    SettingsContent(
        server = server,
        state = state,
        activeUrl = activeUrl,
        scan = scan,
        check = check,
        onBack = onBack,
        onMode = container.connection::setMode,
        onSavePublic = container.connection::setPublicUrl,
        onSaveLocal = container.connection::setLocalUrl,
        onScan = container.connection::rescan,
        onSignOut = container.settings::signOut,
        theme = theme,
        onTheme = container.uiPrefs::setThemeMode,
        onConnect = onConnect,
    )
}

/** Stateless Settings UI, so it can be previewed and screenshotted with sample data. */
@Composable
fun SettingsContent(
    server: ServerConfig?,
    state: ConnState,
    activeUrl: String,
    scan: ScanState,
    /** The last connection check's steps, shown under the scan card; null hides the panel. */
    check: ConnectionCheck? = null,
    onBack: () -> Unit,
    onMode: (ConnectionMode) -> Unit,
    /** Saves the address; returns whether it was verified as this account's server. */
    onSavePublic: suspend (String) -> Boolean,
    /** Saves (or, with null, clears) the LAN address and verifies it. */
    onSaveLocal: suspend (String?) -> LocalUrlResult,
    onScan: () -> Unit,
    onSignOut: () -> Unit,
    /** The Appearance choice; switching it re-themes the whole app at once. */
    theme: ThemeMode = ThemeMode.System,
    onTheme: (ThemeMode) -> Unit = {},
    /** Opens the Immich sign-in (Accounts → Immich → Connect). */
    onConnect: () -> Unit = {},
    /** The phone's media access, for Accounts → This device. */
    permissions: MediaPermissionsState = LocalMediaPermissions.current,
) {
    Column(Modifier.fillMaxSize().background(Palette.paper)) {
        ScreenHeader("Settings", onBack = onBack, actions = { if (server != null) ConnectionChip(state) })
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp).windowInsetsPadding(WindowInsets.navigationBars),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionLabel("Accounts")
            ImmichAccountCard(server, onConnect = onConnect, onSignOut = onSignOut)
            DeviceCard(permissions)

            if (server != null) ServerSections(server, state, activeUrl, scan, check, onMode, onSavePublic, onSaveLocal, onScan)

            Spacer(Modifier.height(4.dp))
            SectionLabel("Appearance")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("System", selected = theme == ThemeMode.System, onClick = { onTheme(ThemeMode.System) }, icon = Icons.Rounded.BrightnessAuto)
                Chip("Light", selected = theme == ThemeMode.Light, onClick = { onTheme(ThemeMode.Light) }, icon = Icons.Rounded.LightMode)
                Chip("Dark", selected = theme == ThemeMode.Dark, onClick = { onTheme(ThemeMode.Dark) }, icon = Icons.Rounded.DarkMode)
            }
            Text(
                when (theme) {
                    ThemeMode.System -> "Follows your phone's dark theme setting."
                    ThemeMode.Light -> "Cream paper, always."
                    ThemeMode.Dark -> "Dark paper, always. Photos and the editor are always dark."
                },
                style = Type.Small,
                color = Palette.mutedInk,
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Accounts → Immich: who's signed in and DISCONNECT, or CONNECT when there's no server. */
@Composable
private fun ImmichAccountCard(server: ServerConfig?, onConnect: () -> Unit, onSignOut: () -> Unit) {
    BrutalCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.Cloud, fill = Palette.lilac, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Immich", style = Type.Title, color = Palette.ink)
                    Spacer(Modifier.width(8.dp))
                    if (server == null) Tag("Not connected", fill = Palette.card) else Tag("Connected", fill = Palette.mint)
                }
                Text(
                    if (server == null) "Your own photo server, optional" else server.publicUrl.removePrefix("https://").removePrefix("http://"),
                    style = Type.Small, color = Palette.mutedInk, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        if (server == null) {
            Text("Connect a server for albums, people, places, memories, smart search and share links.", style = Type.Small, color = Palette.mutedInk)
            Spacer(Modifier.height(12.dp))
            BrutalButton("Connect to Immich", onConnect, icon = Icons.Rounded.Cloud, modifier = Modifier.fillMaxWidth())
        } else {
            Row(
                Modifier.fillMaxWidth().background(Palette.sand, RoundedCornerShape(6.dp)).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBadge(Icons.Rounded.Person, fill = Palette.card, size = 34.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(server.name ?: server.email ?: "Signed in", style = Type.Title, color = Palette.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (server.name != null && server.email != null) Text(server.email, style = Type.Small, color = Palette.mutedInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(12.dp))
            BrutalButton("Disconnect", onSignOut, style = ButtonStyle.Danger, icon = Icons.AutoMirrored.Rounded.Logout, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            Text("Signs out and forgets the server. Photos on this phone stay.", style = Type.Small, color = Palette.mutedInk)
        }
    }
}

/** Accounts → This device: media access (re-checked on resume) and "Skip confirmation dialogs". */
@Composable
private fun DeviceCard(permissions: MediaPermissionsState) {
    BrutalCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.PhoneAndroid, fill = Palette.mint, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("This device", style = Type.Title, color = Palette.ink)
                    Spacer(Modifier.width(8.dp))
                    when (permissions.access) {
                        MediaAccess.Full -> Tag("Full access", fill = Palette.mint)
                        MediaAccess.Partial -> Tag("Limited", fill = Palette.yellow)
                        MediaAccess.Denied -> Tag("No access", fill = Palette.red, ink = Palette.onStrong)
                    }
                }
                Text("Photos and videos on this phone", style = Type.Small, color = Palette.mutedInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            when (permissions.access) {
                MediaAccess.Full -> "Photos can see every photo and video on this phone."
                MediaAccess.Partial -> "Photos can only see the photos you picked. Pick more, or allow all in system settings."
                MediaAccess.Denied ->
                    if (permissions.permanentlyDenied) "Access is turned off. Turn on Photos and videos for this app in system settings."
                    else "Allow access to see the photos and videos on this phone."
            },
            style = Type.Small, color = Palette.mutedInk,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                permissions.access == MediaAccess.Partial -> {
                    BrutalButton("Pick photos", { permissions.request() }, icon = Icons.Rounded.TouchApp, modifier = Modifier.weight(1f))
                    BrutalButton("Allow all", permissions::openSettings, style = ButtonStyle.Outline, modifier = Modifier.weight(1f))
                }
                permissions.access == MediaAccess.Denied && !permissions.permanentlyDenied ->
                    BrutalButton("Grant access", { permissions.request() }, icon = Icons.Rounded.PhotoLibrary, modifier = Modifier.weight(1f))
                else -> BrutalButton("Manage", permissions::openSettings, style = ButtonStyle.Outline, icon = Icons.Rounded.Settings, modifier = Modifier.weight(1f))
            }
        }
        val manageMedia = permissions.manageMedia
        if (manageMedia != null) {
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth().height(2.dp).background(Palette.sand))
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Skip confirmation dialogs", style = Type.Title.copy(fontSize = Type.Body.fontSize), color = Palette.ink)
                        Spacer(Modifier.width(6.dp))
                        if (permissions.canManageMedia) Tag("On", fill = Palette.mint) else Tag("Off", fill = Palette.card)
                    }
                    Text("Favourite and delete phone photos without Android asking each time.", style = Type.Small, color = Palette.mutedInk)
                }
                Spacer(Modifier.width(10.dp))
                BrutalButton(if (permissions.canManageMedia) "Change" else "Allow", manageMedia, style = ButtonStyle.Outline)
            }
        }
    }
}

/** Server address, connection mode and LAN discovery (only with a server). */
@Composable
private fun ServerSections(
    server: ServerConfig,
    state: ConnState,
    activeUrl: String,
    scan: ScanState,
    check: ConnectionCheck?,
    onMode: (ConnectionMode) -> Unit,
    onSavePublic: suspend (String) -> Boolean,
    onSaveLocal: suspend (String?) -> LocalUrlResult,
    onScan: () -> Unit,
) {
        Spacer(Modifier.height(4.dp))
        SectionLabel("Server")
        BrutalCard(Modifier.fillMaxWidth().animateContentSize()) {
            UrlField(
                tag = "Public",
                tagFill = Palette.blue,
                tagInk = Palette.onStrong,
                value = server.publicUrl,
                empty = "",
                active = state != ConnState.Offline && activeUrl == server.publicUrl,
                placeholder = "https://photos.example.com",
                hint = "Works from anywhere.",
                onSave = {
                    if (onSavePublic(it ?: server.publicUrl)) SaveOutcome(true, "Verified: same server and account.")
                    else SaveOutcome(false, "Saved, but couldn't confirm it's your server from here.")
                },
                allowClear = false,
            )
            Spacer(Modifier.height(16.dp))
            UrlField(
                tag = "Home network",
                tagFill = Palette.mint,
                tagInk = Palette.ink,
                value = server.localUrl,
                empty = "Not found yet",
                active = state == ConnState.Home && activeUrl == server.localUrl,
                placeholder = "http://192.168.1.20:2283",
                hint = if (scan is ScanState.Found && scan.url == server.localUrl) "Found automatically on this network." else "Used whenever it answers. Found automatically on Wi-Fi.",
                onSave = {
                    when (onSaveLocal(it)) {
                        LocalUrlResult.Verified -> SaveOutcome(true, "Verified: same server and account.")
                        LocalUrlResult.VerifiedWithNote -> SaveOutcome(true, "Verified by your account. Some server settings look different from the public address: see Connection check below.")
                        LocalUrlResult.Unverified -> SaveOutcome(
                            false,
                            "Saved, but not verified, so it won't be used or sent your key. Connection check below shows which step failed.",
                        )
                        LocalUrlResult.NotLanAddress -> SaveOutcome(false, "Not saved: use a home network IP address like 192.168.x.x or 10.x.x.x.")
                        LocalUrlResult.Cleared -> null
                    }
                },
                allowClear = true,
            )
        }

        Spacer(Modifier.height(4.dp))
        SectionLabel("Connection")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Auto", selected = server.mode == ConnectionMode.AUTO, onClick = { onMode(ConnectionMode.AUTO) }, icon = Icons.Rounded.AutoMode)
            Chip("Home", selected = server.mode == ConnectionMode.HOME, onClick = { onMode(ConnectionMode.HOME) }, icon = Icons.Rounded.Bolt)
            Chip("Remote", selected = server.mode == ConnectionMode.REMOTE, onClick = { onMode(ConnectionMode.REMOTE) }, icon = Icons.Rounded.Public)
        }
        Text(
            when (server.mode) {
                ConnectionMode.AUTO -> "Uses your home network address whenever it answers, and the public one everywhere else."
                ConnectionMode.HOME -> "Always uses the home network address once it's verified on this network, even if it stops answering."
                ConnectionMode.REMOTE -> "Always uses the public address, even at home."
            },
            style = Type.Small,
            color = Palette.mutedInk,
        )

        BrutalCard(Modifier.fillMaxWidth().animateContentSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Rounded.Radar, fill = Palette.mint, size = 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Find on this network", style = Type.Title, color = Palette.ink)
                    Text("Looks for your server on the Wi-Fi you're on.", style = Type.Small, color = Palette.mutedInk)
                }
            }
            Spacer(Modifier.height(12.dp))
            when (scan) {
                is ScanState.Running -> {
                    BrutalProgress(if (scan.total == 0) 0f else scan.done.toFloat() / scan.total, fill = Palette.mint, moving = true)
                    Spacer(Modifier.height(6.dp))
                    Text("Checked ${scan.done} of ${scan.total} addresses…", style = Type.Small, color = Palette.mutedInk)
                }
                is ScanState.Found -> Notice("Found your server at ${scan.url}", NoticeTone.Success, Icons.Rounded.CheckCircle)
                ScanState.NotFound -> Notice("Didn't find your server on this network. Check it's on port 2283, or enter the address above.", NoticeTone.Warning, Icons.Rounded.WarningAmber)
                ScanState.NoLan -> Notice("Connect to Wi-Fi to scan your home network.", NoticeTone.Info, Icons.Rounded.WarningAmber)
                ScanState.NeedsPublic -> Notice("Connect once via the public address first, so the app can recognise your server on the LAN.", NoticeTone.Info, Icons.Rounded.WarningAmber)
                ScanState.Idle -> {}
            }
            if (scan !is ScanState.Idle) Spacer(Modifier.height(12.dp))
            check?.let {
                ConnectionCheckPanel(it)
                Spacer(Modifier.height(12.dp))
            }
            BrutalButton(
                if (scan is ScanState.Running) "Scanning…" else "Scan network",
                onClick = onScan,
                enabled = scan !is ScanState.Running,
                style = ButtonStyle.Outline,
                icon = Icons.Rounded.Radar,
                modifier = Modifier.fillMaxWidth(),
            )
        }
}

private class SaveOutcome(val ok: Boolean, val message: String)

/** Each step of the last check with pass/fail and the reason; selectable so it can be copied into a report. */
@Composable
private fun ConnectionCheckPanel(check: ConnectionCheck) {
    Column(
        Modifier.fillMaxWidth().background(Palette.sand).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Connection check", style = Type.Label, color = Palette.ink, modifier = Modifier.weight(1f))
            if (check.running) Tag("Running", fill = Palette.yellow)
        }
        Text(check.title, style = Type.Small, color = Palette.mutedInk, maxLines = 2, overflow = TextOverflow.Ellipsis)
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                check.steps.forEach { step ->
                    Row(verticalAlignment = Alignment.Top) {
                        val (label, fill, ink) = when (step.status) {
                            StepStatus.Pass -> Triple("Pass", Palette.mint, Palette.ink)
                            StepStatus.Warn -> Triple("Note", Palette.yellow, Palette.ink)
                            StepStatus.Fail -> Triple("Fail", Palette.red, Palette.onStrong)
                            StepStatus.Running -> Triple("…", Palette.card, Palette.ink)
                            StepStatus.Skipped -> Triple("Skip", Palette.card, Palette.mutedInk)
                        }
                        Tag(label, fill = fill, ink = ink, modifier = Modifier.width(52.dp))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(step.name, style = Type.Small.copy(fontWeight = FontWeight.Bold), color = Palette.ink)
                            Text(step.detail, style = Type.Small, color = Palette.mutedInk)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UrlField(
    tag: String,
    tagFill: androidx.compose.ui.graphics.Color,
    tagInk: androidx.compose.ui.graphics.Color,
    value: String?,
    empty: String,
    active: Boolean,
    placeholder: String,
    hint: String,
    onSave: suspend (String?) -> SaveOutcome?,
    allowClear: Boolean,
) {
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<SaveOutcome?>(null) }
    val scope = rememberCoroutineScope()

    fun save(text: String?) {
        busy = true
        scope.launch {
            try {
                result = onSave(text)
                editing = false
            } finally {
                busy = false
            }
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Tag(tag, fill = tagFill, ink = tagInk)
        if (active) {
            Spacer(Modifier.width(6.dp))
            Tag("In use", fill = Palette.yellow)
        }
        Spacer(Modifier.weight(1f))
        if (!editing) {
            BrutalButton("Edit", onClick = { draft = value.orEmpty(); result = null; editing = true }, style = ButtonStyle.Ghost, icon = Icons.Rounded.Edit)
        }
    }
    if (!editing) {
        Text(value ?: empty, style = Type.Body, color = if (value == null) Palette.mutedInk else Palette.ink, modifier = Modifier.padding(top = 2.dp))
        Text(hint, style = Type.Small, color = Palette.mutedInk)
    }
    AnimatedVisibility(editing) {
        Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            BrutalTextField(draft, { draft = it }, placeholder, keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BrutalButton(if (busy) "Checking…" else "Save", onClick = { save(draft.trim().ifEmpty { null }) }, enabled = !busy && (allowClear || draft.isNotBlank()))
                if (allowClear && value != null) BrutalButton("Clear", onClick = { save(null) }, style = ButtonStyle.Outline, enabled = !busy)
                BrutalButton("Cancel", onClick = { editing = false }, style = ButtonStyle.Ghost, enabled = !busy)
            }
        }
    }
    result?.let { Text(it.message, style = Type.Small, color = if (it.ok) Palette.success else Palette.danger, modifier = Modifier.padding(top = 4.dp)) }
}
