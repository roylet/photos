package com.roylet.photos.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.roylet.photos.AppContainer
import com.roylet.photos.ui.components.BrutalButton
import com.roylet.photos.ui.components.BrutalCard
import com.roylet.photos.ui.components.ButtonStyle
import com.roylet.photos.ui.components.ScreenHeader
import com.roylet.photos.ui.components.SectionLabel
import com.roylet.photos.ui.components.Tag
import com.roylet.photos.ui.theme.Brutal
import com.roylet.photos.ui.theme.Type

@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit) {
    val server by container.settings.server.collectAsState()
    Column(Modifier.fillMaxSize().background(Brutal.Paper)) {
        ScreenHeader("Settings", onBack = onBack)
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp).windowInsetsPadding(WindowInsets.navigationBars),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionLabel("Server")
            BrutalCard(Modifier.fillMaxWidth()) {
                val s = server
                if (s == null) {
                    Text("Not connected", style = Type.Title, color = Brutal.Ink)
                } else {
                    Text(s.name ?: s.email ?: "Signed in", style = Type.Title, color = Brutal.Ink)
                    if (s.name != null && s.email != null) Text(s.email, style = Type.Small, color = Brutal.MutedInk)
                    Spacer(Modifier.height(12.dp))
                    Tag("Public", fill = Brutal.Blue, ink = Brutal.Card)
                    Text(s.publicUrl, style = Type.Body, color = Brutal.Ink, modifier = Modifier.padding(top = 4.dp))
                    Spacer(Modifier.height(10.dp))
                    Tag("Home network", fill = Brutal.Mint)
                    Text(s.localUrl ?: "Not found yet", style = Type.Body, color = if (s.localUrl == null) Brutal.MutedInk else Brutal.Ink, modifier = Modifier.padding(top = 4.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            BrutalButton(
                "Sign out",
                onClick = { container.settings.signOut() },
                style = ButtonStyle.Danger,
                icon = Icons.AutoMirrored.Rounded.Logout,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
