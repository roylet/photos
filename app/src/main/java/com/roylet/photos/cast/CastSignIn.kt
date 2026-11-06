package com.roylet.photos.cast

import com.roylet.photos.data.ServerConfig
import com.roylet.photos.immich.ImmichApi
import com.roylet.photos.immich.ImmichException
import okhttp3.OkHttpClient

/**
 * "Sign in once to cast": an API-key sign-in has no Immich login session, and an old one can be
 * revoked or expire, but `POST /api/sessions` needs one. This logs in with the password once
 * (`/api/auth/login`) and stores the returned session token encrypted, the same way the email
 * sign-in does ([com.roylet.photos.data.Settings.saveServer] → Keystore). No API key is made and
 * the password isn't kept.
 */
class CastSignIn(
    private val http: OkHttpClient,
    private val server: () -> ServerConfig?,
    private val store: (token: String) -> Unit,
    /** Whether the token really got stored (it's dropped when the Keystore can't seal it). */
    private val stored: () -> Boolean,
    private val onSignedIn: () -> Unit,
) {
    /** The email to prefill. */
    val email: String? get() = server()?.email

    /** Null on success, else what to tell the person. */
    suspend fun signIn(email: String, password: String): String? {
        val cfg = server() ?: return "Connect to Immich first"
        if (email.isBlank() || password.isEmpty()) return "Enter your email and password"
        val login = try {
            ImmichApi(cfg.publicUrl, null, http).login(email.trim(), password)
        } catch (e: ImmichException) {
            return if (e.status == 401) "Wrong email or password" else ImmichApi.describe(e)
        } catch (e: Exception) {
            return ImmichApi.describe(e)
        }
        if (cfg.userId.isNotEmpty() && login.userId.isNotEmpty() && login.userId != cfg.userId) {
            return "That's a different Immich account from the one this app uses"
        }
        store(login.accessToken)
        if (!stored()) return "This phone couldn't store the sign-in securely, so casting from Immich isn't available"
        onSignedIn()
        return null
    }
}
