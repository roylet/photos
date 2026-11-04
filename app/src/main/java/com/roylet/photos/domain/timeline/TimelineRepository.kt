package com.roylet.photos.domain.timeline

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/** Where timeline data comes from: Immich in the app, a fake in tests and screenshots. */
interface TimelineSource {
    suspend fun buckets(filter: TimelineFilter): List<TimeBucket>
    suspend fun bucket(filter: TimelineFilter, key: String): List<TimelineAsset>
}

/**
 * One timeline (for one [filter]): the month list up front, each month's assets fetched lazily
 * the first time something needs them, cached for the life of the repository, with the
 * neighbouring months prefetched so scrolling and paging rarely wait.
 */
class TimelineRepository(
    private val source: TimelineSource,
    val filter: TimelineFilter,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /** How many months either side of a requested one are fetched ahead of time. */
    private val prefetch: Int = 1,
) {
    sealed interface Status {
        data object Idle : Status
        data object Loading : Status
        data object Ready : Status
        data class Failed(val message: String) : Status
    }

    private val _snapshot = MutableStateFlow(TimelineSnapshot.Empty)
    val snapshot: StateFlow<TimelineSnapshot> = _snapshot.asStateFlow()

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val inFlight = HashMap<String, Deferred<List<TimelineAsset>?>>()
    private var generation = 0

    /** Fetches the month list if it hasn't been yet. */
    fun start() {
        if (_status.value == Status.Idle) refresh()
    }

    /** Re-fetches the month list and drops cached months (pull-to-refresh, server switch). */
    fun refresh(keepLoaded: Boolean = false) {
        val gen = ++generation
        _status.value = Status.Loading
        scope.launch {
            runCatching { source.buckets(filter) }
                .onSuccess { buckets ->
                    if (gen != generation) return@onSuccess
                    val keep = if (keepLoaded) _snapshot.value.loaded.filterKeys { k -> buckets.any { it.key == k } } else emptyMap()
                    synchronized(inFlight) { inFlight.clear() }
                    _snapshot.value = TimelineSnapshot(buckets, keep)
                    _status.value = Status.Ready
                    // Warm the first months so the grid's first frame has real cells.
                    buckets.take(1 + prefetch).forEach { load(it.key) }
                }
                .onFailure { e -> if (gen == generation) _status.value = Status.Failed(e.message ?: e.javaClass.simpleName) }
        }
    }

    /** Makes sure bucket [bucketIndex] (and its neighbours) is loaded or loading. */
    fun ensureLoaded(bucketIndex: Int) {
        val buckets = _snapshot.value.buckets
        if (bucketIndex !in buckets.indices) return
        load(buckets[bucketIndex].key)
        for (d in 1..prefetch) {
            buckets.getOrNull(bucketIndex - d)?.let { load(it.key) }
            buckets.getOrNull(bucketIndex + d)?.let { load(it.key) }
        }
    }

    /** Loads the bucket holding global asset index [index]. */
    fun ensureLoadedAt(index: Int) = ensureLoaded(_snapshot.value.bucketIndexOf(index))

    /** Suspends until bucket [bucketIndex] is loaded (for scrubber jumps). Returns false on failure. */
    suspend fun awaitBucket(bucketIndex: Int): Boolean {
        val key = _snapshot.value.buckets.getOrNull(bucketIndex)?.key ?: return false
        ensureLoaded(bucketIndex)
        return load(key)?.await() != null
    }

    private fun load(key: String): Deferred<List<TimelineAsset>?>? {
        if (key in _snapshot.value.loaded) return null
        // Bucket loads are short and idempotent; a plain synchronized map keeps this non-suspending.
        synchronized(inFlight) {
            inFlight[key]?.let { return it }
            val gen = generation
            val job = scope.async {
                val assets = runCatching { source.bucket(filter, key) }.getOrNull()
                synchronized(inFlight) { inFlight.remove(key) }
                if (assets != null && gen == generation) {
                    _snapshot.update { s -> TimelineSnapshot(s.buckets, s.loaded + (key to assets)) }
                }
                assets
            }
            // With an immediate dispatcher the fetch may already be done (and have removed itself).
            if (!job.isCompleted) inFlight[key] = job
            return job
        }
    }

    /** Applies [change] to every loaded asset whose id is in [ids] (optimistic updates). */
    fun updateAssets(ids: Collection<String>, change: (TimelineAsset) -> TimelineAsset) {
        val set = ids.toSet()
        _snapshot.update { s ->
            TimelineSnapshot(s.buckets, s.loaded.mapValues { (_, list) -> if (list.none { it.id in set }) list else list.map { if (it.id in set) change(it) else it } })
        }
    }

    fun setFavorite(ids: Collection<String>, favorite: Boolean) = updateAssets(ids) { it.copy(isFavorite = favorite) }

    /** Drops assets from the cached months (after delete / archive / remove-from-album). */
    fun remove(ids: Collection<String>) {
        val set = ids.toSet()
        _snapshot.update { s ->
            val loaded = s.loaded.mapValues { (_, list) -> list.filterNot { it.id in set } }
            val buckets = s.buckets.map { b -> loaded[b.key]?.let { b.copy(count = it.size) } ?: b }.filter { it.count > 0 }
            TimelineSnapshot(buckets, loaded.filterValues { it.isNotEmpty() })
        }
    }
}

/**
 * Keeps one [TimelineRepository] per filter so the grid and the viewer opened from it share the
 * same cached months. Everything is dropped when [session] (e.g. the signed-in user id) changes.
 */
class TimelineRegistry(
    /** Swappable so screenshot tests can render fake timelines through the real screens. */
    var source: TimelineSource,
    private val session: () -> Any? = { null },
) {
    /** Where repositories run their fetches; tests use an immediate dispatcher. */
    var context: CoroutineContext = Dispatchers.Default

    private val repos = HashMap<TimelineFilter, TimelineRepository>()
    private var currentSession: Any? = null

    @Synchronized
    fun get(filter: TimelineFilter): TimelineRepository {
        val s = session()
        if (s != currentSession) { repos.clear(); currentSession = s }
        return repos.getOrPut(filter) { TimelineRepository(source, filter, CoroutineScope(SupervisorJob() + context)) }
    }

    @Synchronized
    fun clear() = repos.clear()
}
