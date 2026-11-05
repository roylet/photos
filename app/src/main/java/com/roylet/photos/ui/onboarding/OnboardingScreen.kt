package com.roylet.photos.ui.onboarding

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
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.roylet.photos.ui.components.AppMark
import com.roylet.photos.ui.components.BrutalButton
import com.roylet.photos.ui.components.BrutalCard
import com.roylet.photos.ui.components.ButtonStyle
import com.roylet.photos.ui.components.IconBadge
import com.roylet.photos.ui.theme.Brutal
import com.roylet.photos.ui.theme.Palette
import com.roylet.photos.ui.theme.Type

/**
 * First launch: use the app as the phone's gallery (primary) or connect an Immich server.
 * Either choice marks onboarding done; Immich can be connected or removed later in Settings.
 */
@Composable
fun OnboardingScreen(onUseDevice: () -> Unit, onConnectImmich: () -> Unit, modifier: Modifier = Modifier) {
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppMark()
                Spacer(Modifier.width(10.dp))
                Text("PHOTOS", style = Type.Headline, color = Palette.ink)
            }
            Spacer(Modifier.height(18.dp))
            BrutalCard(fill = Brutal.Orange, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "EVERY PHOTO.\nON THIS PHONE.\nEDIT ANYTHING.",
                    style = Type.Huge.copy(fontSize = Type.Huge.fontSize * 0.66f, lineHeight = Type.Huge.lineHeight * 0.7f),
                    color = Brutal.Ink,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "A fast gallery and photo editor for the pictures and videos on your phone. Got an Immich server? Connect it too, now or later.",
                    style = Type.Body,
                    color = Brutal.Ink,
                )
            }
            Spacer(Modifier.height(22.dp))
            Feature(Icons.Rounded.Bolt, Palette.yellow, "Fast, private gallery", "Your photos stay on the phone. No account needed.")
            Feature(Icons.Rounded.Tune, Palette.mint, "Pro editing", "Light, colour, curves and crops for photos and videos.")
            Feature(Icons.Rounded.Cloud, Palette.lilac, "Immich, if you like", "Albums, people, places and smart search from your own server.")
            Spacer(Modifier.height(18.dp))
            BrutalButton("Use on this device", onUseDevice, icon = Icons.Rounded.PhoneAndroid, large = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            BrutalButton("Connect to Immich", onConnectImmich, style = ButtonStyle.Outline, icon = Icons.Rounded.Cloud, large = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Text(
                "You can connect or disconnect Immich any time in Settings.",
                style = Type.Small,
                color = Palette.mutedInk,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun Feature(icon: ImageVector, fill: Color, title: String, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(icon, fill = fill, size = 44.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, style = Type.Title, color = Palette.ink)
            Text(text, style = Type.Small, color = Palette.mutedInk)
        }
    }
}
