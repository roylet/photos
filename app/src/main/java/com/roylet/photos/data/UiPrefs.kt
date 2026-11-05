package com.roylet.photos.data

import android.content.Context
import android.content.SharedPreferences
import com.roylet.photos.domain.library.FolderPrefs
import com.roylet.photos.domain.library.PhotosFolderMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The Appearance setting: follow the system, or force the light or dark palette. */
enum class ThemeMode { System, Light, Dark }

/** Small UI memories: the last share option, album sort, recent searches, appearance. In-memory when [context] is null (tests). */
class UiPrefs(context: Context?) {
    private val prefs: SharedPreferences? = context?.getSharedPreferences("ui", Context.MODE_PRIVATE)

    var shareMode: String
        get() = prefs?.getString("shareMode", null) ?: memShare
        set(v) { memShare = v; prefs?.edit()?.putString("shareMode", v)?.apply() }
    private var memShare = "file"

    var albumSort: String
        get() = prefs?.getString("albumSort", null) ?: memSort
        set(v) { memSort = v; prefs?.edit()?.putString("albumSort", v)?.apply() }
    private var memSort = "Recent"

    private val _theme = MutableStateFlow(
        prefs?.getString("themeMode", null)?.let { v -> ThemeMode.entries.firstOrNull { it.name == v } } ?: ThemeMode.System,
    )
    val themeMode: StateFlow<ThemeMode> = _theme.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        _theme.value = mode
        prefs?.edit()?.putString("themeMode", mode.name)?.apply()
    }

    private val _backupBadges = MutableStateFlow(prefs?.getBoolean("showBackupBadges", true) ?: true)
    /** Settings → "Show backup badges": the grid's on-this-phone-only / in-Immich-only marks (with a server). */
    val showBackupBadges: StateFlow<Boolean> = _backupBadges.asStateFlow()

    fun setShowBackupBadges(show: Boolean) {
        _backupBadges.value = show
        prefs?.edit()?.putBoolean("showBackupBadges", show)?.apply()
    }

    /** Library → On this device: hidden folders and which folders show in Photos (bucket ids). */
    var folderPrefs: FolderPrefs
        get() = prefs?.let { p ->
            FolderPrefs(
                hidden = p.ids("folderHidden"),
                inPhotos = p.ids("folderInPhotos"),
                notInPhotos = p.ids("folderNotInPhotos"),
                mode = p.getString("folderPhotosMode", null)?.let { v -> PhotosFolderMode.entries.firstOrNull { it.name == v } } ?: PhotosFolderMode.All,
            )
        } ?: memFolders
        set(v) {
            memFolders = v
            prefs?.edit()
                ?.putStringSet("folderHidden", v.hidden.mapTo(HashSet()) { it.toString() })
                ?.putStringSet("folderInPhotos", v.inPhotos.mapTo(HashSet()) { it.toString() })
                ?.putStringSet("folderNotInPhotos", v.notInPhotos.mapTo(HashSet()) { it.toString() })
                ?.putString("folderPhotosMode", v.mode.name)
                ?.apply()
        }
    private var memFolders = FolderPrefs()

    private fun SharedPreferences.ids(key: String): Set<Long> = getStringSet(key, null).orEmpty().mapNotNullTo(HashSet()) { it.toLongOrNull() }

    private val _recent = MutableStateFlow(prefs?.getString("recentSearches", null)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList())
    val recentSearches: StateFlow<List<String>> = _recent.asStateFlow()

    fun addRecentSearch(q: String) {
        val t = q.trim().takeIf { it.isNotEmpty() } ?: return
        _recent.value = (listOf(t) + _recent.value.filterNot { it.equals(t, ignoreCase = true) }).take(8)
        prefs?.edit()?.putString("recentSearches", _recent.value.joinToString("\n"))?.apply()
    }

    fun clearRecentSearches() {
        _recent.value = emptyList()
        prefs?.edit()?.remove("recentSearches")?.apply()
    }
}
