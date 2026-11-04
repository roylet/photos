package com.roylet.photos.ui.search

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.roylet.photos.data.UiPrefs
import com.roylet.photos.domain.library.ExploreSection
import com.roylet.photos.domain.library.LibrarySource
import com.roylet.photos.domain.library.Person
import com.roylet.photos.domain.timeline.TimelineAsset
import com.roylet.photos.domain.timeline.TimelineFilter
import com.roylet.photos.domain.timeline.TimelineRegistry
import com.roylet.photos.immich.ImmichApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** What a search was for: free text (smart search) or a place. */
sealed interface SearchQuery {
    val label: String
    data class Text(val text: String) : SearchQuery { override val label get() = text }
    data class Place(val city: String?, val country: String?) : SearchQuery { override val label get() = city ?: country.orEmpty() }
}

sealed interface SearchResult {
    data object Idle : SearchResult
    data class Loading(val query: SearchQuery) : SearchResult
    data class Done(val query: SearchQuery, val filter: TimelineFilter, val count: Int) : SearchResult
    data class Failed(val query: SearchQuery, val message: String) : SearchResult
}

/**
 * The Search tab's state, kept for the session so switching tabs doesn't lose results.
 * Results become a list-backed timeline, so the grid and viewer work exactly as elsewhere.
 */
@Stable
class SearchModel(
    private val library: () -> LibrarySource,
    private val timelines: TimelineRegistry,
    val prefs: UiPrefs,
    private val scope: CoroutineScope,
) {
    var text by mutableStateOf("")
    var result by mutableStateOf<SearchResult>(SearchResult.Idle)
        private set
    var people by mutableStateOf<List<Person>>(emptyList())
        private set
    var explore by mutableStateOf<List<ExploreSection>>(emptyList())
        private set
    var countries by mutableStateOf<List<String>>(emptyList())
        private set
    private var discoverLoaded = false
    private var job: Job? = null
    private var counter = 0

    /** Loads people, places, things and country suggestions (once per session). */
    fun loadDiscover() {
        if (discoverLoaded) return
        discoverLoaded = true
        scope.launch { runCatching { library().people() }.onSuccess { people = it } }
        scope.launch { runCatching { library().explore() }.onSuccess { explore = it } }
        scope.launch { runCatching { library().suggestions("country") }.onSuccess { countries = it.take(12) } }
    }

    fun submit(query: SearchQuery = SearchQuery.Text(text)) {
        if (query is SearchQuery.Text && query.text.isBlank()) return
        text = query.label
        if (query is SearchQuery.Text) prefs.addRecentSearch(query.text)
        job?.cancel()
        result = SearchResult.Loading(query)
        job = scope.launch {
            try {
                val assets: List<TimelineAsset> = when (query) {
                    is SearchQuery.Text -> library().smartSearch(query.text.trim())
                    is SearchQuery.Place -> library().placeSearch(query.city, query.country)
                }
                val filter = timelines.putList("search-${++counter}", assets)
                result = SearchResult.Done(query, filter, assets.size)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                result = SearchResult.Failed(query, ImmichApi.describe(e))
            }
        }
    }

    fun clear() {
        job?.cancel()
        text = ""
        result = SearchResult.Idle
    }

    /** Shows ready-made results (screenshots). */
    fun showResults(query: SearchQuery, assets: List<TimelineAsset>) {
        text = query.label
        result = SearchResult.Done(query, timelines.putList("search-${++counter}", assets), assets.size)
    }

    fun reset() { clear(); discoverLoaded = false; people = emptyList(); explore = emptyList(); countries = emptyList() }
}
