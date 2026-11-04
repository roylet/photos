package com.roylet.photos.ui.connect

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.roylet.photos.AppContainer
import com.roylet.photos.data.ServerConfig
import com.roylet.photos.immich.ImmichApi
import com.roylet.photos.ui.components.BrutalButton
import com.roylet.photos.ui.components.BrutalCard
import com.roylet.photos.ui.components.BrutalTextField
import com.roylet.photos.ui.components.Chip
import com.roylet.photos.ui.components.Notice
import com.roylet.photos.ui.components.NoticeTone
import com.roylet.photos.ui.theme.Brutal
import com.roylet.photos.ui.theme.Type
import kotlinx.coroutines.launch
import java.io.IOException

/** Prefixes a failure with which connect step it came from, so "401" errors say what was rejected. */
private suspend fun <T> step(label: String, block: suspend () -> T): T = try {
    block()
} catch (e: Exception) {
    throw IOException("$label: ${ImmichApi.describe(e)}", e)
}

@Composable
fun ConnectScreen(container: AppContainer, onConnected: () -> Unit) {
    var url by rememberSaveable { mutableStateOf("") }
    var useKey by rememberSaveable { mutableStateOf(false) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun connect() {
        busy = true
        error = null
        scope.launch {
            try {
                val origin = ImmichApi.discoverOrigin(url, container.http)
                if (origin == null) {
                    error = "Couldn't reach an Immich server at ${url.trim()}. Check the address (include the port, e.g. :2283) and that you're on the right network."
                    return@launch
                }
                val probe = ImmichApi(origin, null, container.http)
                val key = if (useKey) {
                    apiKey.trim()
                } else {
                    val login = step("Sign-in failed") { probe.login(email.trim(), password) }
                    step("Signed in, but couldn't create an API key") { probe.createApiKey(login.accessToken, ImmichApi.KEY_NAME) }
                }
                // Proves the key works, and gives us the user id the LAN check matches against.
                val me = step(if (useKey) "That API key didn't work" else "The new API key didn't work") { ImmichApi(origin, key, container.http).me() }
                password = ""
                container.settings.saveServer(
                    ServerConfig(
                        publicUrl = probe.origin,
                        apiKey = key,
                        userId = me.id,
                        email = me.email.ifEmpty { email.trim() }.takeIf { it.isNotEmpty() },
                        name = me.name.takeIf { it.isNotEmpty() },
                    ),
                )
                onConnected()
            } catch (e: Exception) {
                error = ImmichApi.describe(e)
            } finally {
                busy = false
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Brutal.Paper), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = 560.dp)
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .windowInsetsPadding(WindowInsets.navigationBars),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            BrutalCard(fill = Brutal.Orange, modifier = Modifier.fillMaxWidth()) {
                Text("YOUR PHOTOS.\nYOUR SERVER.\nEDIT ANYTHING.", style = Type.Huge.copy(fontSize = Type.Huge.fontSize * 0.7f, lineHeight = Type.Huge.lineHeight * 0.72f), color = Brutal.Ink)
                Spacer(Modifier.height(10.dp))
                Text(
                    "A fast gallery and photo editor for your Immich library. Switches to your home network automatically.",
                    style = Type.Body,
                    color = Brutal.Ink,
                )
            }

            Text("Connect to your server", style = Type.Headline, color = Brutal.Ink)

            BrutalTextField(
                url, { url = it }, "photos.example.com or 192.168.1.20:2283",
                label = "Server address",
                leading = Icons.Rounded.Dns,
                keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Email & password", selected = !useKey, onClick = { useKey = false }, icon = Icons.Rounded.Mail)
                Chip("API key", selected = useKey, onClick = { useKey = true }, icon = Icons.Rounded.Key)
            }

            if (useKey) {
                BrutalTextField(apiKey, { apiKey = it }, "Paste an API key", label = "API key", password = true, leading = Icons.Rounded.Key)
                Text(
                    "Create one in Immich under Account Settings → API Keys. Give it all permissions, or at least: " +
                        ImmichApi.PERMISSIONS.joinToString(", ") + ".",
                    style = Type.Small,
                    color = Brutal.MutedInk,
                )
            } else {
                BrutalTextField(email, { email = it }, "you@example.com", label = "Email", leading = Icons.Rounded.Mail, keyboard = KeyboardOptions(keyboardType = KeyboardType.Email))
                BrutalTextField(password, { password = it }, "Password", label = "Password", password = true, leading = Icons.Rounded.Lock)
                Text(
                    "Your password is only used once to create an API key for this app (\"${ImmichApi.KEY_NAME}\"), then forgotten.",
                    style = Type.Small,
                    color = Brutal.MutedInk,
                )
            }

            error?.let { Notice(it, NoticeTone.Error, Icons.Rounded.ErrorOutline) }

            val ready = url.isNotBlank() && (if (useKey) apiKey.isNotBlank() else email.isNotBlank() && password.isNotEmpty())
            BrutalButton(
                if (busy) "Connecting…" else "Connect",
                onClick = ::connect,
                enabled = ready && !busy,
                large = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
