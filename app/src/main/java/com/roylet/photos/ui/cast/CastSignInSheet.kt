package com.roylet.photos.ui.cast

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.roylet.photos.cast.CastSignIn
import com.roylet.photos.ui.components.BrutalButton
import com.roylet.photos.ui.components.BrutalSheet
import com.roylet.photos.ui.components.BrutalTextField
import com.roylet.photos.ui.components.ButtonStyle
import com.roylet.photos.ui.components.IconBadge
import com.roylet.photos.ui.components.Notice
import com.roylet.photos.ui.components.NoticeTone
import com.roylet.photos.ui.theme.Palette
import com.roylet.photos.ui.theme.Type
import kotlinx.coroutines.launch

/** The app's "Sign in once to cast" ([CastSignIn]); AppNav provides it. */
val LocalCastSignIn = staticCompositionLocalOf<CastSignIn?> { null }

/**
 * "Sign in once to cast": the Immich password, once, for a login session the TV's short-lived
 * links are made from. Stateless (screenshots); [CastSignInHost] runs it.
 */
@Composable
fun CastSignInSheet(
    visible: Boolean,
    initialEmail: String?,
    busy: Boolean,
    error: String?,
    onSubmit: (email: String, password: String) -> Unit,
    onDismiss: () -> Unit,
    initialPassword: String = "",
) {
    BrutalSheet(visible, onDismiss) {
        var email by remember(visible, initialEmail) { mutableStateOf(initialEmail.orEmpty()) }
        var password by remember(visible) { mutableStateOf(initialPassword) }
        Column(Modifier.fillMaxWidth().testTag("cast-sign-in")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Rounded.Cast, fill = Palette.peach, size = 44.dp)
                Spacer(Modifier.width(12.dp))
                Text("Sign in once to cast", style = Type.Headline, color = Palette.ink, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Your TV plays photos straight from Immich using short-lived links. Making them needs an Immich " +
                    "login, which an API-key sign-in doesn't have. Your password is used once and isn't stored; no new API key is made.",
                style = Type.Small, color = Palette.mutedInk,
            )
            Spacer(Modifier.height(14.dp))
            BrutalTextField(
                email, { email = it }, "you@example.com", label = "Email", leading = Icons.Rounded.Mail,
                keyboard = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            BrutalTextField(
                password, { password = it }, "Immich password", label = "Password", leading = Icons.Rounded.Lock, password = true,
                keyboard = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth().testTag("cast-password"),
            )
            if (error != null) {
                Spacer(Modifier.height(12.dp))
                Notice(error, NoticeTone.Error, Icons.Rounded.ErrorOutline)
            }
            Spacer(Modifier.height(16.dp))
            BrutalButton(
                if (busy) "Signing in…" else "Sign in and cast",
                { onSubmit(email, password) },
                modifier = Modifier.fillMaxWidth().testTag("cast-sign-in-submit"),
                enabled = !busy && email.isNotBlank() && password.isNotEmpty(),
                large = true,
            )
            Spacer(Modifier.height(6.dp))
            BrutalButton("Not now", onDismiss, modifier = Modifier.fillMaxWidth(), style = ButtonStyle.Ghost)
        }
    }
}

/** [CastSignInSheet] wired to [LocalCastSignIn]. [onDone] after a successful sign-in. */
@Composable
fun CastSignInHost(visible: Boolean, onDismiss: () -> Unit, onDone: () -> Unit = onDismiss) {
    val signIn = LocalCastSignIn.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember(visible) { mutableStateOf<String?>(null) }
    CastSignInSheet(
        visible, signIn?.email, busy, error,
        onSubmit = { email, password ->
            val s = signIn ?: return@CastSignInSheet
            busy = true
            error = null
            scope.launch {
                val failed = s.signIn(email, password)
                busy = false
                if (failed == null) onDone() else error = failed
            }
        },
        onDismiss = onDismiss,
    )
}
