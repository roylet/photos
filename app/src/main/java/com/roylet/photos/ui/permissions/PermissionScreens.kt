package com.roylet.photos.ui.permissions

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.roylet.photos.ui.components.BrutalButton
import com.roylet.photos.ui.components.ButtonStyle
import com.roylet.photos.ui.components.EmptyState
import com.roylet.photos.ui.components.IconBadge
import com.roylet.photos.ui.components.brutal
import com.roylet.photos.ui.theme.Palette
import com.roylet.photos.ui.theme.Type

/**
 * Shown before the system's media prompt: what the app does with access and that picking only
 * some photos is fine. [onAllow] should call [MediaPermissionsState.request]; when access was
 * turned off for good the button opens app settings instead.
 */
@Composable
fun MediaAccessExplainer(
    permanentlyDenied: Boolean,
    onAllow: () -> Unit,
    onOpenSettings: () -> Unit,
    onNotNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().background(Palette.paper), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = 560.dp)
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .windowInsetsPadding(WindowInsets.navigationBars),
        ) {
            Spacer(Modifier.height(12.dp))
            Box(Modifier.brutal(Palette.mint).padding(18.dp)) {
                Icon(Icons.Rounded.PhotoLibrary, null, tint = Palette.ink, modifier = Modifier.width(52.dp).height(52.dp))
            }
            Spacer(Modifier.height(20.dp))
            Text("LET PHOTOS SEE\nYOUR PHOTOS", style = Type.Huge.copy(fontSize = Type.Huge.fontSize * 0.6f, lineHeight = Type.Huge.lineHeight * 0.66f), color = Palette.ink)
            Spacer(Modifier.height(10.dp))
            Text(
                "To show the pictures and videos on this phone, Android asks you to allow access. Here's what that means.",
                style = Type.Body,
                color = Palette.mutedInk,
            )
            Spacer(Modifier.height(20.dp))
            Column(
                Modifier.fillMaxWidth().brutal(Palette.card).padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Point(Icons.Rounded.Collections, Palette.peach, "Your whole library, fast", "Every photo and video, by month, with editing.")
                Point(Icons.Rounded.Lock, Palette.yellow, "Stays on this phone", "Nothing is uploaded. Immich is optional.")
                Point(Icons.Rounded.TouchApp, Palette.lilac, "Your choice", "Pick \"Select photos\" to share only some. Change it any time.")
            }
            Spacer(Modifier.height(24.dp))
            if (permanentlyDenied) {
                Text(
                    "Access is turned off for this app. Open Settings, then Permissions, then Photos and videos.",
                    style = Type.Small,
                    color = Palette.ink,
                    modifier = Modifier.fillMaxWidth().background(Palette.current.noticeWarning).padding(12.dp),
                )
                Spacer(Modifier.height(14.dp))
                BrutalButton("Open settings", onOpenSettings, icon = Icons.Rounded.Settings, large = true, modifier = Modifier.fillMaxWidth())
            } else {
                BrutalButton("Allow access", onAllow, icon = Icons.Rounded.PhotoLibrary, large = true, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(10.dp))
            BrutalButton("Not now", onNotNow, style = ButtonStyle.Ghost, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun Point(icon: ImageVector, fill: Color, title: String, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(icon, fill = fill, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.Title, color = Palette.ink)
            Text(text, style = Type.Small, color = Palette.mutedInk)
        }
    }
}

/**
 * The phone timeline's empty state without access: GRANT ACCESS (the system prompt), or OPEN
 * SETTINGS once Android won't ask again. [onConnectImmich] offers the server instead.
 */
@Composable
fun MediaAccessDenied(
    permanentlyDenied: Boolean,
    onGrant: () -> Unit,
    onOpenSettings: () -> Unit,
    onConnectImmich: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    EmptyState(
        Icons.Rounded.PhotoLibrary,
        "Allow access to your photos",
        if (permanentlyDenied) "Access is turned off. Turn on Photos and videos for this app in Settings to see what's on this phone."
        else "Photos shows the pictures and videos on this phone. Nothing leaves it unless you connect Immich.",
        modifier,
    ) {
        if (permanentlyDenied) BrutalButton("Open settings", onOpenSettings, icon = Icons.Rounded.Settings, modifier = Modifier.fillMaxWidth())
        else BrutalButton("Grant access", onGrant, icon = Icons.Rounded.PhotoLibrary, modifier = Modifier.fillMaxWidth())
        if (onConnectImmich != null) BrutalButton("Connect to Immich", onConnectImmich, style = ButtonStyle.Outline, icon = Icons.Rounded.Cloud, modifier = Modifier.fillMaxWidth())
    }
}

/** The phone timeline's empty state with access but nothing to show. */
@Composable
fun DeviceLibraryEmpty(partial: Boolean, onManage: () -> Unit, modifier: Modifier = Modifier) {
    if (partial) {
        EmptyState(
            Icons.Rounded.TouchApp, "No photos picked yet",
            "You've allowed access to selected photos only. Pick some to see them here.", modifier,
        ) { BrutalButton("Pick photos", onManage, icon = Icons.Rounded.TouchApp, modifier = Modifier.fillMaxWidth()) }
    } else {
        EmptyState(
            Icons.Rounded.PhotoCamera, "No photos on this phone yet",
            "Photos and videos you take or save on this phone show up here, newest first.", modifier,
        )
    }
}

/** "Limited access · MANAGE" above the phone timeline while only some photos are shared. */
@Composable
fun LimitedAccessBanner(onManage: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(Palette.paper)
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 2.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().brutal(Palette.yellow, shadow = 3.dp).padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Lock, null, tint = Palette.ink, modifier = Modifier.width(20.dp).height(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                Text("LIMITED ACCESS", style = Type.Label, color = Palette.ink)
                Text("Showing only the photos you picked", style = Type.Small, color = Palette.ink.copy(alpha = 0.75f))
            }
            BrutalButton("Manage", onManage, style = ButtonStyle.Dark)
        }
    }
}
