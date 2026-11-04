package com.roylet.photos.ui.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.roylet.photos.AppContainer
import com.roylet.photos.ui.components.EmptyState
import com.roylet.photos.ui.components.ScreenHeader
import com.roylet.photos.ui.components.SquareIconButton
import com.roylet.photos.ui.theme.Brutal

/** The main "Photos" tab. Placeholder until the timeline grid lands. */
@Composable
fun TimelineScreen(container: AppContainer, onSettings: () -> Unit) {
    val server by container.settings.server.collectAsState()
    Column(Modifier.fillMaxSize().background(Brutal.Paper)) {
        ScreenHeader(
            "Photos",
            subtitle = server?.email ?: server?.publicUrl,
            actions = { SquareIconButton(Icons.Rounded.Settings, "Settings", onSettings) },
        )
        EmptyState(Icons.Rounded.PhotoLibrary, "Your timeline", "Every photo and video on your server, newest first. Coming soon.")
    }
}
