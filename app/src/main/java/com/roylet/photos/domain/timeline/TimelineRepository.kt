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

    /** What [remove] took out, so an undo can put it back exactly where it was. */
    class Removal internal constructor(
        internal val buckets: List<TimeBucket>,
        internal val removed: Map<String, List<Pair<Int, TimelineAsset>>>,
    ) {
        val count: Int get() = removed.values.sumOf { it.size }
    }

    /** Drops assets from the cached months (after delete / archive / remove-from-album). */
    fun remove(ids: Collection<String>): Removal {
        val set = ids.toSet()
        var removal = Removal(emptyList(), emptyMap())
        _snapshot.update { s ->
            val taken = HashMap<String, List<Pair<Int, TimelineAsset>>>()
            val loaded = s.loaded.mapValues { (key, list) ->
                if (list.none { it.id in set }) list else {
                    taken[key] = list.withIndex().filter { it.value.id in set }.map { it.index to it.value }
                    list.filterNot { it.id in set }
                }
            }
            removal = Removal(s.buckets, taken)
            val buckets = s.buckets.map { b -> loaded[b.key]?.let { b.copy(count = it.size) } ?: b }.filter { it.count > 0 }
            TimelineSnapshot(buckets, loaded.filterValues { it.isNotEmpty() })
        }
        return removal
    }

    /** Undoes a [remove]: the assets go back to their months at their old positions. */
    fun reinsert(removal: Removal) {
        if (removal.removed.isEmpty()) return
        _snapshot.update { s ->
            val loaded = s.loaded.toMutableMap()
            removal.removed.forEach { (key, items) ->
                val list = loaded[key].orEmpty().toMutableList()
                items.sortedBy { it.first }.forEach { (i, a) -> if (list.none { it.id == a.id }) list.add(i.coerceAtMost(list.size), a) }
                loaded[key] = list
            }
            val known = (s.buckets + removal.buckets).associateBy { it.key }
            val keys = known.keys.filter { it in loaded || s.buckets.any { b -> b.key == it } }
            val ordered = if (filter.newestFirst) keys.sortedDescending() else keys.sorted()
            TimelineSnapshot(ordered.map { k -> known.getValue(k).let { b -> loaded[k]?.let { b.copy(count = it.size) } ?: b } }, loaded)
        }
    }

    @Volatile private var stale = false

    /** Something changed elsewhere (e.g. an asset was archived from another timeline): re-fetch next time this is shown. */
    fun markStale() { if (_status.value != Status.Idle) stale = true }

    /** [start], but also re-fetches if [markStale] was called since the last load. */
    fun startOrRefresh() {
        if (stale) { stale = false; refresh() } else start()
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
        if (s != currentSession) { repos.clear(); lists.clear(); currentSession = s }
        return repos.getOrPut(filter) {
            TimelineRepository(if (filter.listId != null) listSource() else source, filter, CoroutineScope(SupervisorJob() + context))
        }
    }

    @Synchronized
    fun clear() = repos.clear()

    /** Marks every cached timeline except [except] as stale (they re-fetch when next shown). */
    @Synchronized
    fun markStale(except: TimelineFilter? = null) = repos.forEach { (f, r) -> if (f != except && f.listId == null) r.markStale() }

    /** Runs [action] on every cached timeline (for cross-timeline optimistic updates). */
    fun forEachRepo(action: (TimelineRepository) -> Unit) = synchronized(this) { repos.values.toList() }.forEach(action)

    private val lists = HashMap<String, List<TimelineAsset>>()

    /**
     * Registers a fixed list of assets (search results, a map cluster) as a timeline, so the grid
     * and viewer can page through it like any other. Returns its filter.
     */
    @Synchronized
    fun putList(id: String, assets: List<TimelineAsset>): TimelineFilter {
        val filter = TimelineFilter.list(id)
        lists[id] = assets
        repos.remove(filter)
        return filter
    }

    internal fun listSource(): TimelineSource = object : TimelineSource {
        override suspend fun buckets(filter: TimelineFilter): List<TimeBucket> = StaticTimeline.buckets(assetsFor(filter))
        override suspend fun bucket(filter: TimelineFilter, key: String): List<TimelineAsset> = StaticTimeline.bucket(assetsFor(filter), key)
        private fun assetsFor(f: TimelineFilter) = synchronized(this@TimelineRegistry) { lists[f.listId].orEmpty() }
    }
}

/** Groups a flat asset list into months, newest first, for list-backed timelines. */
object StaticTimeline {
    fun keyOf(a: TimelineAsset): String {
        val d = java.time.Instant.ofEpochMilli(a.localTakenAt).atZone(java.time.ZoneOffset.UTC)
        return "%04d-%02d-01T00:00:00.000Z".format(d.year, d.monthValue)
    }

    fun buckets(assets: List<TimelineAsset>): List<TimeBucket> =
        assets.groupBy(::keyOf).map { (k, v) -> TimeBucket(k, v.size) }.sortedByDescending { it.key }

    fun bucket(assets: List<TimelineAsset>, key: String): List<TimelineAsset> =
        assets.filter { keyOf(it) == key }.sortedByDescending { it.localTakenAt }
}
