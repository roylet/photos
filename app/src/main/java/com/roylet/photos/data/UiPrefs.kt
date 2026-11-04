package com.roylet.photos.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Small UI memories: the last share option, album sort, recent searches. In-memory when [context] is null (tests). */
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
