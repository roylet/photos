package com.roylet.photos.merge

import android.util.Log
import com.roylet.photos.domain.source.AssetRef
import com.roylet.photos.domain.timeline.TimeBucket
import com.roylet.photos.domain.timeline.TimelineAsset
import com.roylet.photos.domain.timeline.TimelineFilter
import com.roylet.photos.domain.timeline.TimelineSource
import com.roylet.photos.domain.timeline.Visibility
import com.roylet.photos.local.MonthKeys
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap

/**
 * What [MergedTimelineSource] needs from the matcher ([ImmichMatcher] in the app, a fake in
 * tests): answers for one month, and the cached answers when a month can't be checked whole.
 */
interface MonthMatcher {
    suspend fun matchMonth(bucketKey: String, localIds: List<Long>, remoteIds: Set<String>): Map<Long, Match>
    /** Cached Immich copies of [localIds] (favourites: the other side may not be in the list). */
    suspend fun cachedRemote(localIds: List<Long>): Map<Long, Match>
    /** Cached phone copies of [remoteIds]. */
    suspend fun cachedLocal(remoteIds: List<String>): Map<String, Long>
    /**
     * Cached phone copies of [remoteIds], with whether each is only a guess. Destructive actions
     * act on a pair only when it's confirmed; the default treats every answer as a guess.
     */
    suspend fun cachedLocalMatches(remoteIds: List<String>): Map<String, LocalMatch> =
        cachedLocal(remoteIds).mapValues { LocalMatch(it.value, tentative = true) }
    /** Known pairs per month (bucket key → count), for the bucket counts before a month is loaded. */
    suspend fun pairsPerMonth(): Map<String, Int>
    /** Immich assets that went away (trashed, deleted): drop their answers. */
    suspend fun forgetRemote(remoteIds: Collection<String>) {}
}

/**
 * The main Photos timeline with a server connected: the phone's and Immich's months side by
 * side, one cell per photo (Google Photos style). See docs/SOURCES.md §9.
 *
 * - Timeline: per month, the phone items and the Immich assets are merged; matched pairs become
 *   one [TimelineAsset] with both refs (id = the phone copy, so the viewer opens the local file),
 *   the rest stay [com.roylet.photos.domain.source.SourceBadge.LocalOnly] / RemoteOnly. Months on
 *   one side only are just that side.
 * - Favourites: the union (a favourite on either side); a cached match fills in the other ref.
 * - Trash: both trashes, unmerged, each item labelled by its own source.
 * - Archive, albums, people, tags: Immich only. Phone folders and [TimelineFilter.Device]: phone only.
 * - Immich unreachable: the phone's side alone, so the gallery still works offline.
 *
 * Bucket counts before a month loads are estimates (phone + Immich − known pairs); once loaded,
 * [com.roylet.photos.domain.timeline.TimelineSnapshot.countOf] uses the merged list's size.
 */
class MergedTimelineSource(
    private val local: TimelineSource,
    private val remote: TimelineSource,
    private val matcher: () -> MonthMatcher?,
) : TimelineSource {
    /** Merged sizes of months already loaded, so later bucket lists agree with what was shown. */
    private val mergedCounts = ConcurrentHashMap<Pair<TimelineFilter, String>, Int>()

    private enum class Mode { Merge, Favorites, Trash, LocalOnly, RemoteOnly }

    private fun modeOf(f: TimelineFilter): Mode = when {
        f.isLocal -> Mode.LocalOnly
        f.listId != null -> Mode.RemoteOnly // never reaches a source (the registry serves lists)
        f.isTrashed == true -> Mode.Trash
        f.albumId != null || f.personId != null || f.tagId != null -> Mode.RemoteOnly
        f.visibility != null && f.visibility != Visibility.TIMELINE -> Mode.RemoteOnly
        f.isFavorite == true -> Mode.Favorites
        else -> Mode.Merge
    }

    /** Whether [filter]'s timeline shows phone items (so a change on the phone should refresh it). */
    fun includesPhone(filter: TimelineFilter): Boolean = modeOf(filter) != Mode.RemoteOnly

    /** Another server or account: merged sizes from before don't apply. */
    fun reset() { mergedCounts.clear() }

    override suspend fun buckets(filter: TimelineFilter): List<TimeBucket> {
        val mode = modeOf(filter)
        if (mode == Mode.LocalOnly) return local.buckets(filter)
        if (mode == Mode.RemoteOnly) return remote.buckets(filter)
        val (l, r) = coroutineScope {
            val l = async { safely("phone months") { local.buckets(filter) } }
            val r = async { safely("Immich months") { remote.buckets(filter) } }
            l.await() to r.await()
        }
        if (r == null) return l ?: throw IllegalStateException("Neither the phone nor Immich could be read")
        // The same month can be spelt differently on each side (the phone's `2026-10-01T00:00:00.000Z`,
        // newer Immich servers' `2026-10-01`), so the two lists are joined by month, not by key.
        val lm = l.orEmpty().associateBy { monthId(it.key) }
        val rm = r.associateBy { monthId(it.key) }
        val pairs = if (mode == Mode.Merge) runCatching { matcher()?.pairsPerMonth() }.getOrNull().orEmpty().mapKeys { monthId(it.key) } else emptyMap()
        val merged = (lm.keys + rm.keys).map { m ->
            val a = lm[m]?.count ?: 0
            val b = rm[m]?.count ?: 0
            // Immich's spelling when it has the month, so its bucket call gets the key it gave.
            val k = rm[m]?.key ?: lm.getValue(m).key
            TimeBucket(k, mergedCounts[filter to k] ?: (a + b - (pairs[m] ?: 0).coerceIn(0, minOf(a, b))))
        }
        return if (filter.newestFirst) merged.sortedByDescending { it.key } else merged.sortedBy { it.key }
    }

    override suspend fun bucket(filter: TimelineFilter, key: String): List<TimelineAsset> {
        val mode = modeOf(filter)
        if (mode == Mode.LocalOnly) return local.bucket(filter, key)
        if (mode == Mode.RemoteOnly) return remote.bucket(filter, key)
        val (l, r) = coroutineScope {
            // The phone's spelling of the month (the key may be Immich's).
            val l = async { safely("phone month") { local.bucket(filter, MonthKeys.parse(key)?.let(MonthKeys::key) ?: key) } }
            val r = async { safely("Immich month") { remote.bucket(filter, key) } }
            l.await() to r.await()
        }
        if (l == null && r == null) throw IllegalStateException("Neither the phone nor Immich could be read")
        val phone = l.orEmpty()
        val server = r ?: return phone
        val merged = when (mode) {
            Mode.Trash -> sort(phone + server, filter.newestFirst)
            Mode.Favorites -> favorites(phone, server, filter.newestFirst)
            else -> merge(key, phone, server, filter.newestFirst)
        }
        mergedCounts[filter to key] = merged.size
        return merged
    }

    private suspend fun merge(key: String, phone: List<TimelineAsset>, server: List<TimelineAsset>, newestFirst: Boolean): List<TimelineAsset> {
        if (phone.isEmpty() || server.isEmpty()) return sort(phone + server, newestFirst)
        val localIds = phone.mapNotNull { it.ref?.mediaStoreId }
        val matches = runCatching { matcher()?.matchMonth(key, localIds, server.mapTo(HashSet()) { it.id }) }
            .onFailure { if (it is CancellationException) throw it; Log.w(TAG, "Matching $key failed", it) }
            .getOrNull().orEmpty()
        return pair(phone, server, matches, newestFirst)
    }

    private suspend fun favorites(phone: List<TimelineAsset>, server: List<TimelineAsset>, newestFirst: Boolean): List<TimelineAsset> {
        val m = matcher() ?: return sort(phone + server, newestFirst)
        val toRemote = runCatching { m.cachedRemote(phone.mapNotNull { it.ref?.mediaStoreId }) }.getOrNull().orEmpty()
        val serverIds = server.mapTo(HashSet()) { it.id }
        // Favourite only on the phone, but its Immich copy is known: still badged as on both.
        val paired = pair(phone, server, toRemote, newestFirst).map { a ->
            val lid = a.ref?.mediaStoreId
            val rid = lid?.let { toRemote[it] }?.remoteId
            if (a.remoteRef == null && rid != null && rid !in serverIds) a.copy(localRef = AssetRef.local(lid), remoteRef = AssetRef.immich(rid)) else a
        }
        // Favourite only on Immich, but its phone copy is known: still one item, opened from Immich.
        val unpaired = paired.filter { it.localRef == null && it.remoteRef == null && !AssetRef.isLocal(it.id) }.map { it.id }
        val toLocal = runCatching { m.cachedLocal(unpaired) }.getOrNull().orEmpty()
        if (toLocal.isEmpty()) return paired
        val phoneIds = phone.mapTo(HashSet()) { it.id }
        return paired.map { a ->
            val lid = toLocal[a.id]?.takeIf { AssetRef.local(it).id !in phoneIds } ?: return@map a
            a.copy(localRef = AssetRef.local(lid), remoteRef = AssetRef.immich(a.id))
        }
    }

    companion object {
        private const val TAG = "MergedTimeline"

        /** A bucket key's month (`yyyy * 100 + MM`) whatever its spelling; the key itself if it isn't one. */
        private fun monthId(key: String): Any = MonthKeys.parse(key) ?: key

        /**
         * Collapses each matched phone item and Immich asset into one cell: the phone's id (the
         * viewer opens the local file), both refs, favourite if either is, and Immich's extras
         * (thumbhash, place, stack). A pair whose Immich asset isn't in [server] isn't merged.
         */
        fun pair(phone: List<TimelineAsset>, server: List<TimelineAsset>, matches: Map<Long, Match>, newestFirst: Boolean): List<TimelineAsset> {
            val byId = server.associateBy { it.id }
            val used = HashSet<String>()
            val out = ArrayList<TimelineAsset>(phone.size + server.size)
            for (a in phone) {
                val lid = a.ref?.mediaStoreId
                val r = lid?.let { matches[it] }?.remoteId?.let(byId::get)?.takeIf { it.id !in used }
                if (r == null) { out += a; continue }
                used += r.id
                out += a.copy(
                    localRef = AssetRef.local(lid),
                    remoteRef = AssetRef.immich(r.id),
                    isFavorite = a.isFavorite || r.isFavorite,
                    thumbhash = a.thumbhash ?: r.thumbhash,
                    city = r.city,
                    country = r.country,
                    stackCount = r.stackCount,
                    visibility = r.visibility,
                )
            }
            server.filterTo(out) { it.id !in used }
            return sort(out, newestFirst)
        }

        fun sort(list: List<TimelineAsset>, newestFirst: Boolean): List<TimelineAsset> =
            if (newestFirst) list.sortedWith(compareByDescending<TimelineAsset> { it.fileCreatedAt }.thenBy { it.id })
            else list.sortedWith(compareBy<TimelineAsset> { it.fileCreatedAt }.thenBy { it.id })

        private suspend fun <T> safely(what: String, block: suspend () -> T): T? = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't read $what", e)
            null
        }
    }
}

/** An Immich asset's phone copy; [tentative] = a file name + time + size guess, not a SHA-1. */
data class LocalMatch(val localId: Long, val tentative: Boolean)

/** A matcher that knows no pairs (tests, screenshots, and before anything is matched). */
object NoPairs : MonthMatcher {
    override suspend fun matchMonth(bucketKey: String, localIds: List<Long>, remoteIds: Set<String>): Map<Long, Match> = emptyMap()
    override suspend fun cachedRemote(localIds: List<Long>): Map<Long, Match> = emptyMap()
    override suspend fun cachedLocal(remoteIds: List<String>): Map<String, Long> = emptyMap()
    override suspend fun pairsPerMonth(): Map<String, Int> = emptyMap()
}
