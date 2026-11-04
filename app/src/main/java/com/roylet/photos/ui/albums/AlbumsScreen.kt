package com.roylet.photos.ui.albums

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhotoAlbum
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.roylet.photos.AppContainer
import com.roylet.photos.ui.components.EmptyState
import com.roylet.photos.ui.components.ScreenHeader
import com.roylet.photos.ui.theme.Brutal

/** The "Albums" tab. Placeholder until the albums list lands. */
@Composable
fun AlbumsScreen(@Suppress("UNUSED_PARAMETER") container: AppContainer) {
    Column(Modifier.fillMaxSize().background(Brutal.Paper)) {
        ScreenHeader("Albums")
        EmptyState(Icons.Rounded.PhotoAlbum, "Albums", "Your Immich albums, shared albums and new ones you make here.")
    }
}
