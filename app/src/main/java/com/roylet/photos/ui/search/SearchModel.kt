package com.roylet.photos.ui.search

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.roylet.photos.data.UiPrefs
import com.roylet.photos.domain.library.ExploreSection
import com.roylet.photos.domain.library.LibrarySource
import com.roylet.photos.domain.library.Person
import com.roylet.photos.domain.source.Capability
import com.roylet.photos.domain.timeline.TimelineAsset
import com.roylet.photos.domain.timeline.TimelineFilter
import com.roylet.photos.domain.timeline.TimelineRegistry
import com.roylet.photos.domain.library.UnsupportedCapabilityException
import com.roylet.photos.immich.ImmichApi
import com.roylet.photos.immich.ImmichException
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
    /** [details] is the technical side (exception, status, server message) for "Copy details". */
    data class Failed(val query: SearchQuery, val message: String, val details: String = "") : SearchResult
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
        val caps = library().capabilities
        // Each is optional: a failure just leaves its row out (never crashes the tab).
        if (Capability.PEOPLE in caps) scope.launch { quietly { library().people() }?.let { people = it.distinctBy { p -> p.id } } }
        if (Capability.SMART_SEARCH in caps) scope.launch {
            quietly { library().explore() }?.let { sections -> explore = sections.map { it.copy(items = it.items.distinctBy { i -> i.value }) }.filter { it.items.isNotEmpty() } }
        }
        if (Capability.MAP in caps) scope.launch { quietly { library().suggestions("country") }?.let { countries = it.filter(String::isNotBlank).distinct().take(12) } }
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
                    // Without smart search (the phone alone): file name, folder or date.
                    is SearchQuery.Text -> library().let { lib ->
                        if (Capability.SMART_SEARCH in lib.capabilities) lib.smartSearch(query.text.trim()) else lib.basicSearch(query.text.trim())
                    }
                    is SearchQuery.Place -> library().placeSearch(query.city, query.country)
                }
                // One cell per asset: the grid tolerates repeats, but the viewer's pager keys by id.
                val unique = assets.distinctBy { it.id }
                val filter = timelines.putList("search-${++counter}", unique)
                result = SearchResult.Done(query, filter, unique.size)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                result = failure(query, e)
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
        val unique = assets.distinctBy { it.id }
        result = SearchResult.Done(query, timelines.putList("search-${++counter}", unique), unique.size)
    }

    private suspend fun <T> quietly(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }

    fun reset() { clear(); discoverLoaded = false; people = emptyList(); explore = emptyList(); countries = emptyList() }
}

/** A friendly message plus the technical details to copy. */
internal fun failure(query: SearchQuery, e: Throwable): SearchResult.Failed {
    val message = when {
        e is UnsupportedCapabilityException -> e.message ?: "That search isn't available here"
        e is kotlinx.serialization.SerializationException -> "The server sent results this app couldn't read"
        else -> ImmichApi.describe(e)
    }
    return SearchResult.Failed(query, message, errorDetails(query, e))
}

internal fun errorDetails(query: SearchQuery, e: Throwable): String = buildString {
    append("Search: ").append(query.label).append('\n')
    (e as? ImmichException)?.status?.let { append("HTTP ").append(it).append('\n') }
    var t: Throwable? = e
    var depth = 0
    while (t != null && depth < 4) {
        append(if (depth == 0) "" else "Caused by: ").append(t.javaClass.name).append(": ").append(t.message.orEmpty().take(600)).append('\n')
        t.stackTrace.take(if (depth == 0) 6 else 3).forEach { append("  at ").append(it).append('\n') }
        t = t.cause?.takeIf { it !== t }
        depth++
    }
}.trimEnd()
