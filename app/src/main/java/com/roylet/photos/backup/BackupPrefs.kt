package com.roylet.photos.backup

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/**
 * Backup's switches (the `backup` prefs file). Folder choices, failures and exclusions are in the
 * app database. In memory when [context] is null (tests).
 */
class BackupPrefs(context: Context?) {
    private val prefs: SharedPreferences? = context?.getSharedPreferences("backup", Context.MODE_PRIVATE)

    private val _options = MutableStateFlow(read())
    val options: StateFlow<BackupOptions> = _options.asStateFlow()

    /** Set when Immich refused the key (401/403): backup waits for a new sign-in. */
    private val _authError = MutableStateFlow(prefs?.getString(KEY_AUTH_ERROR, null))
    val authError: StateFlow<String?> = _authError.asStateFlow()

    /** Notification permission was asked for on the first enable. */
    var askedNotifications: Boolean
        get() = prefs?.getBoolean(KEY_ASKED_NOTIFICATIONS, false) ?: memAsked
        set(v) { memAsked = v; prefs?.edit()?.putBoolean(KEY_ASKED_NOTIFICATIONS, v)?.apply() }
    private var memAsked = false

    /** A random id made once per install (kept for diagnostics; Immich's current upload schema has no deviceId). */
    val installId: String by lazy {
        prefs?.getString(KEY_INSTALL_ID, null) ?: UUID.randomUUID().toString().also { prefs?.edit()?.putString(KEY_INSTALL_ID, it)?.apply() }
    }

    fun update(change: (BackupOptions) -> BackupOptions) {
        _options.update(change)
        val o = _options.value
        prefs?.edit()
            ?.putBoolean(KEY_ENABLED, o.enabled)
            ?.putBoolean(KEY_WIFI, o.wifiOnly)
            ?.putBoolean(KEY_CHARGING, o.whileChargingOnly)
            ?.putBoolean(KEY_VIDEOS, o.includeVideos)
            ?.putLong(KEY_SINCE, o.newerThan ?: -1L)
            ?.putBoolean(KEY_PAUSED, o.paused)
            ?.apply()
    }

    fun setAuthError(message: String?) {
        _authError.value = message
        prefs?.edit()?.apply { if (message == null) remove(KEY_AUTH_ERROR) else putString(KEY_AUTH_ERROR, message) }?.apply()
    }

    private fun read(): BackupOptions {
        val p = prefs ?: return BackupOptions()
        val d = BackupOptions()
        return BackupOptions(
            enabled = p.getBoolean(KEY_ENABLED, d.enabled),
            wifiOnly = p.getBoolean(KEY_WIFI, d.wifiOnly),
            whileChargingOnly = p.getBoolean(KEY_CHARGING, d.whileChargingOnly),
            includeVideos = p.getBoolean(KEY_VIDEOS, d.includeVideos),
            newerThan = p.getLong(KEY_SINCE, -1L).takeIf { it >= 0 },
            paused = p.getBoolean(KEY_PAUSED, d.paused),
        )
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_WIFI = "wifiOnly"
        const val KEY_CHARGING = "whileCharging"
        const val KEY_VIDEOS = "includeVideos"
        const val KEY_SINCE = "newerThan"
        const val KEY_PAUSED = "paused"
        const val KEY_AUTH_ERROR = "authError"
        const val KEY_ASKED_NOTIFICATIONS = "askedNotifications"
        const val KEY_INSTALL_ID = "installId"
    }
}
