package com.roylet.photos.merge

import com.roylet.photos.immich.AssetDto
import com.roylet.photos.immich.BulkCheckItem
import com.roylet.photos.immich.ImmichApi
import com.roylet.photos.immich.TimeBucketAssetsDto
import com.roylet.photos.immich.assetsTakenBetween
import com.roylet.photos.immich.bulkUploadCheck
import com.roylet.photos.local.MatchDao
import com.roylet.photos.local.MatchEntity
import com.roylet.photos.local.MediaDao
import com.roylet.photos.local.MediaEntity
import com.roylet.photos.local.MonthKeys
import com.roylet.photos.local.SQL_VARS
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/*
 * Which phone item is which Immich asset (docs/SOURCES.md §9).
 *
 * - Confirmed: the phone file's SHA-1 equals the asset's checksum. Found from the month's Immich
 *   details (they carry the checksum), or by `bulk-upload-check` as soon as an item is hashed.
 * - Tentative: no SHA-1 yet, but the file name, the time taken (±2 s) and, when both sides know
 *   it, the size agree. Replaced by a confirmed answer once the hash exists.
 * Answers are cached in Room (`immich_match`) per phone item and generation: an edit on the phone
 * changes the generation and drops the answer; an Immich asset that disappears from its month
 * (trashed, deleted, replaced) is re-checked; "Immich doesn't have it" is re-asked after [negativeTtlMs].
 */

/** What the matcher needs to know about a phone item. */
data class LocalCandidate(val id: Long, val generation: Long, val name: String?, val takenAt: Long, val size: Long?, val sha1: String?)

/** What the matcher needs to know about an Immich asset. */
data class RemoteCandidate(val id: String, val name: String?, val takenAt: Long, val size: Long?, val sha1: String?)

/** A phone item's Immich copy. */
data class Match(val remoteId: String, val tentative: Boolean)

fun MediaEntity.toCandidate() = LocalCandidate(
    id, generation, displayName, takenAt, sizeBytes, sha1?.takeIf { sha1Generation == generation },
)

fun AssetDto.toCandidate() = RemoteCandidate(
    id = id,
    name = originalFileName.ifEmpty { null },
    takenAt = fileCreatedAt?.let(TimeBucketAssetsDto::parseInstantMillis) ?: 0L,
    size = exifInfo?.fileSizeInByte,
    sha1 = checksum?.let(Checksums::base64ToHex),
)

object Checksums {
    /** Immich's base64 checksum as lower-case hex (null if it isn't a SHA-1). */
    fun base64ToHex(b64: String): String? = runCatching { Base64.getDecoder().decode(b64) }.getOrNull()
        ?.takeIf { it.size == 20 }?.joinToString("") { "%02x".format(it) }
}

/** The pure part: pairs phone items with Immich assets of the same month. */
object Matching {
    const val TIME_TOLERANCE_MS = 2_000L

    /** Same name (case-insensitive), taken within ±2 s and, when both sizes are known, the same size. */
    fun heuristic(l: LocalCandidate, r: RemoteCandidate): Boolean {
        val ln = l.name?.trim()?.lowercase() ?: return false
        val rn = r.name?.trim()?.lowercase() ?: return false
        if (ln != rn) return false
        if (kotlin.math.abs(l.takenAt - r.takenAt) > TIME_TOLERANCE_MS) return false
        if (l.size != null && r.size != null && l.size > 0 && r.size > 0 && l.size != r.size) return false
        return true
    }

    /**
     * Matches each of [locals] to at most one of [remotes], and each remote to at most one local:
     * by SHA-1 when the phone item has one (confirmed, or no match), else by [heuristic]
     * (tentative). Locals without an answer are left out.
     */
    fun pair(locals: List<LocalCandidate>, remotes: List<RemoteCandidate>): Map<Long, Match?> {
        val bySha = remotes.filter { it.sha1 != null }.groupBy { it.sha1!! }
        val used = HashSet<String>()
        val out = LinkedHashMap<Long, Match?>()
        // Hashes first, so a guess never takes an asset a hash proves belongs to another item.
        for (l in locals) {
            val sha = l.sha1 ?: continue
            val r = bySha[sha]?.firstOrNull { it.id !in used }
            if (r != null) used += r.id
            out[l.id] = r?.let { Match(it.id, tentative = false) }
        }
        for (l in locals) {
            if (l.sha1 != null) continue
            val r = remotes.firstOrNull { it.id !in used && heuristic(l, it) }
            if (r != null) { used += r.id; out[l.id] = Match(r.id, tentative = true) } else out[l.id] = null
        }
        return out
    }
}

/** Immich lookups the matcher makes; [ImmichRemoteLookup] in the app, a fake in tests. */
interface RemoteLookup {
    /** Immich assets of the month [bucketKey] (`yyyy-MM-01T00:00:00.000Z`) with name, size and checksum. */
    suspend fun month(bucketKey: String): List<RemoteCandidate>
    /** SHA-1 hex → Immich asset id, for the ones Immich has (bulk-upload-check). */
    suspend fun bySha1(hexes: List<String>): Map<String, String>
}

class ImmichRemoteLookup(private val api: () -> ImmichApi?) : RemoteLookup {
    override suspend fun month(bucketKey: String): List<RemoteCandidate> {
        val api = api() ?: return emptyList()
        val ym = runCatching { YearMonth.parse(bucketKey.take(7)) }.getOrNull() ?: return emptyList()
        // Buckets are local wall-clock months; a day either side covers every time zone.
        val after = ym.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC).minusSeconds(DAY_S)
        val before = ym.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC).plusSeconds(DAY_S)
        return api.assetsTakenBetween(after.toString(), before.toString()).map { it.toCandidate() }
    }

    override suspend fun bySha1(hexes: List<String>): Map<String, String> {
        val api = api() ?: return emptyMap()
        return api.bulkUploadCheck(hexes.map { BulkCheckItem(it, it) })
            .mapNotNull { r -> r.existingAssetId?.let { r.id to it } }.toMap()
    }

    private companion object { const val DAY_S = 86_400L }
}

/**
 * Matches phone items with Immich assets and keeps the answers in [cache]. The merged timeline
 * asks [matchMonth] for each month it loads; the hashing job calls [onHashed].
 */
class ImmichMatcher(
    private val cache: MatchDao,
    private val media: MediaDao,
    private val remote: RemoteLookup,
    private val now: () -> Long = System::currentTimeMillis,
    /** How long "Immich doesn't have it" holds before asking again (a backup may have run). */
    private val negativeTtlMs: Long = 6 * 3_600_000L,
) : MonthMatcher {
    private val monthCache = ConcurrentHashMap<String, List<RemoteCandidate>>()
    private val mutex = Mutex()

    /** Bumped whenever answers change, so the merged timeline can re-merge what's on screen. */
    @Volatile var version: Long = 0
        private set

    /**
     * The Immich copy of each of [localIds] (phone items of month [bucketKey]), given the ids of
     * the Immich assets in that month ([remoteIds]). Cached answers whose asset is still there are
     * used as is; the rest are worked out from the month's Immich details (fetched once per session
     * per month, and only when something needs it).
     */
    override suspend fun matchMonth(bucketKey: String, localIds: List<Long>, remoteIds: Set<String>): Map<Long, Match> = mutex.withLock {
        if (localIds.isEmpty() || remoteIds.isEmpty()) return emptyMap()
        val locals = localIds.chunked(SQL_VARS).flatMap { media.byIds(it) }.map { it.toCandidate() }
        val cached = localIds.chunked(SQL_VARS).flatMap { cache.byLocalIds(it) }.associateBy { it.localId }
        val out = HashMap<Long, Match>()
        val todo = ArrayList<LocalCandidate>()
        val t = now()
        for (l in locals) {
            val row = cached[l.id]
            val fresh = row != null && row.generation == l.generation && when {
                row.remoteId != null -> row.remoteId in remoteIds && !(row.tentative && l.sha1 != null)
                else -> !row.tentative && t - row.checkedAt < negativeTtlMs
            }
            if (fresh) row!!.remoteId?.let { out[l.id] = Match(it, row.tentative) } else todo += l
        }
        if (todo.isEmpty()) return out
        val month = monthCache[bucketKey] ?: runCatching { remote.month(bucketKey) }.getOrNull()?.also { monthCache[bucketKey] = it }
            ?: return out // offline: no answers yet, nothing cached
        // Assets already paired by a fresh answer aren't up for grabs.
        val taken = out.values.mapTo(HashSet()) { it.remoteId }
        val candidates = month.filter { it.id in remoteIds && it.id !in taken }
        val found = Matching.pair(todo, candidates)
        val rows = todo.map { l ->
            val m = found[l.id]
            if (m != null) out[l.id] = m
            MatchEntity(l.id, l.generation, m?.remoteId, tentative = m?.tentative ?: (l.sha1 == null), checkedAt = t)
        }
        cache.upsert(rows)
        version++
        out
    }

    /**
     * Freshly hashed phone items: ask Immich (bulk-upload-check) which ones it has, so their
     * answers are confirmed before their month is next shown.
     */
    suspend fun onHashed(ids: List<Long>): Unit = mutex.withLock {
        val hashed = ids.chunked(SQL_VARS).flatMap { media.sha1Of(it) }
        if (hashed.isEmpty()) return@withLock
        val answers = remote.bySha1(hashed.map { it.sha1 }.distinct())
        val t = now()
        cache.upsert(hashed.map { MatchEntity(it.id, it.generation, answers[it.sha1], tentative = false, checkedAt = t) })
        version++
    }

    override suspend fun cachedRemote(localIds: List<Long>): Map<Long, Match> {
        if (localIds.isEmpty()) return emptyMap()
        val gens = localIds.chunked(SQL_VARS).flatMap { media.byIds(it) }.associate { it.id to it.generation }
        return localIds.chunked(SQL_VARS).flatMap { cache.byLocalIds(it) }
            .filter { it.remoteId != null && gens[it.localId] == it.generation }
            .associate { it.localId to Match(it.remoteId!!, it.tentative) }
    }

    override suspend fun cachedLocal(remoteIds: List<String>): Map<String, Long> =
        if (remoteIds.isEmpty()) emptyMap() else remoteIds.chunked(SQL_VARS).flatMap { cache.byRemoteIds(it) }.associate { it.remoteId!! to it.localId }

    override suspend fun pairsPerMonth(): Map<String, Int> = cache.pairsPerMonth().associate { MonthKeys.key(it.month) to it.count }

    /** Cached Immich copy of a phone item (current generation only). */
    suspend fun remoteOf(localId: Long): Match? {
        val row = cache.byLocalIds(listOf(localId)).firstOrNull() ?: return null
        val gen = media.byId(localId)?.generation ?: return null
        return row.takeIf { it.generation == gen }?.remoteId?.let { Match(it, row.tentative) }
    }

    /** Cached phone copy of an Immich asset. */
    suspend fun localOf(remoteId: String): Long? = cache.byRemoteIds(listOf(remoteId)).firstOrNull()?.localId

    /** Immich assets that went away (trashed, deleted): their answers are dropped. */
    suspend fun forgetRemote(remoteIds: Collection<String>) {
        if (remoteIds.isEmpty()) return
        remoteIds.chunked(SQL_VARS).forEach { cache.deleteRemote(it) }
        monthCache.clear()
        version++
    }

    /** Phone items that went away or changed. */
    suspend fun forgetLocal(localIds: Collection<Long>) {
        if (localIds.isEmpty()) return
        localIds.chunked(SQL_VARS).forEach { cache.deleteLocal(it.toList()) }
        version++
    }

    /** Drops the per-session month details (pull to refresh, Immich changed). */
    fun refresh() { monthCache.clear() }

    /** Another server or account: nothing cached applies. */
    suspend fun clear() { monthCache.clear(); cache.clear(); version++ }
}
