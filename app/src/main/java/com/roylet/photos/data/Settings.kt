package com.roylet.photos.data

import android.content.Context
import com.roylet.photos.net.ServerStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which address the connection manager should use. AUTO picks the LAN address whenever it answers. */
enum class ConnectionMode { AUTO, HOME, REMOTE }

data class ServerConfig(
    /** The address that works from anywhere, e.g. https://photos.example.com. */
    val publicUrl: String,
    /**
     * The server's address on the home network, e.g. http://192.168.1.20:2283. Found automatically
     * by the connection manager (or entered by hand) and preferred whenever it's reachable.
     */
    val localUrl: String? = null,
    val apiKey: String,
    /** `GET /users/me` id, used to prove a LAN address is the same server and account. */
    val userId: String,
    val email: String? = null,
    val name: String? = null,
    /** User override for the automatic LAN/public switch. */
    val mode: ConnectionMode = ConnectionMode.AUTO,
    /**
     * Hash of the server's unauthenticated self-description (version/config/features), cached from
     * the public URL. A LAN address must match it before it's ever sent the API key.
     */
    val fingerprint: String? = null,
    /** [localUrl] has passed the full check (fingerprint, then `/users/me` id) at least once. */
    val localVerified: Boolean = false,
    /**
     * The fingerprint [localUrl] showed when it was verified. Re-checks on a new network accept
     * this or the public [fingerprint], so a hand-entered address that passed the account check
     * despite a differing config field keeps working.
     */
    val localFingerprint: String? = null,
)

class Settings(context: Context) : ServerStore {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _server = MutableStateFlow(readServer())
    override val server: StateFlow<ServerConfig?> = _server.asStateFlow()

    fun saveServer(config: ServerConfig) {
        prefs.edit()
            .putString(KEY_PUBLIC, config.publicUrl)
            .putString(KEY_LOCAL, config.localUrl)
            .putString(KEY_API_KEY, config.apiKey)
            .putString(KEY_USER_ID, config.userId)
            .putString(KEY_EMAIL, config.email)
            .putString(KEY_NAME, config.name)
            .putString(KEY_MODE, config.mode.name)
            .putString(KEY_FINGERPRINT, config.fingerprint)
            .putBoolean(KEY_LOCAL_VERIFIED, config.localVerified)
            .putString(KEY_LOCAL_FINGERPRINT, config.localFingerprint)
            .apply()
        _server.value = config
    }

    /** Applies [change] to the saved server, if there is one. */
    @Synchronized
    override fun updateServer(change: (ServerConfig) -> ServerConfig) {
        val current = _server.value ?: return
        saveServer(change(current))
    }

    /** Sets an unverified LAN address; the connection manager verifies it before sending it the key. */
    fun updateLocalUrl(localUrl: String?) = updateServer {
        val url = localUrl?.trim()?.takeIf { u -> u.isNotEmpty() }
        if (url == it.localUrl) it else it.copy(localUrl = url, localVerified = false, localFingerprint = null)
    }

    fun updateMode(mode: ConnectionMode) = updateServer { it.copy(mode = mode) }

    fun signOut() {
        prefs.edit().apply { SERVER_KEYS.forEach(::remove) }.apply()
        _server.value = null
    }

    private fun readServer(): ServerConfig? {
        val url = prefs.getString(KEY_PUBLIC, null) ?: return null
        val key = prefs.getString(KEY_API_KEY, null) ?: return null
        return ServerConfig(
            publicUrl = url,
            localUrl = prefs.getString(KEY_LOCAL, null),
            apiKey = key,
            userId = prefs.getString(KEY_USER_ID, null).orEmpty(),
            email = prefs.getString(KEY_EMAIL, null),
            name = prefs.getString(KEY_NAME, null),
            mode = prefs.getString(KEY_MODE, null)?.let { runCatching { ConnectionMode.valueOf(it) }.getOrNull() } ?: ConnectionMode.AUTO,
            fingerprint = prefs.getString(KEY_FINGERPRINT, null),
            localVerified = prefs.getBoolean(KEY_LOCAL_VERIFIED, false),
            localFingerprint = prefs.getString(KEY_LOCAL_FINGERPRINT, null),
        )
    }

    private companion object {
        const val KEY_PUBLIC = "publicUrl"
        const val KEY_LOCAL = "localUrl"
        const val KEY_API_KEY = "apiKey"
        const val KEY_USER_ID = "userId"
        const val KEY_EMAIL = "email"
        const val KEY_NAME = "name"
        const val KEY_MODE = "connectionMode"
        const val KEY_FINGERPRINT = "serverFingerprint"
        const val KEY_LOCAL_VERIFIED = "localVerified"
        const val KEY_LOCAL_FINGERPRINT = "localFingerprint"
        val SERVER_KEYS = listOf(KEY_PUBLIC, KEY_LOCAL, KEY_API_KEY, KEY_USER_ID, KEY_EMAIL, KEY_NAME, KEY_MODE, KEY_FINGERPRINT, KEY_LOCAL_VERIFIED, KEY_LOCAL_FINGERPRINT)
    }
}
