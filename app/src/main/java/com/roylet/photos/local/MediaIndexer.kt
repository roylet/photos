package com.roylet.photos.local

import android.util.Log
import com.roylet.photos.ui.permissions.MediaAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId

/** Month keys: `yyyy * 100 + MM` in the index, Immich's `yyyy-MM-01T00:00:00.000Z` on the wire. */
object MonthKeys {
    /** The month [utcMillis] falls in, on the wall clock of [zone]. */
    fun monthOf(utcMillis: Long, zone: ZoneId): Int {
        val d = Instant.ofEpochMilli(utcMillis).atZone(zone)
        return d.year * 100 + d.monthValue
    }

    fun offsetMinutes(utcMillis: Long, zone: ZoneId): Int = zone.rules.getOffset(Instant.ofEpochMilli(utcMillis)).totalSeconds / 60

    /** The timeline bucket key, the same shape Immich uses (its months are local time too). */
    fun key(month: Int): String = "%04d-%02d-01T00:00:00.000Z".format(month / 100, month % 100)

    /** Parses a bucket key back to `yyyy * 100 + MM`; null for anything else. */
    fun parse(key: String): Int? {
        if (key.length < 7 || key[4] != '-') return null
        val y = key.take(4).toIntOrNull() ?: return null
        val m = key.substring(5, 7).toIntOrNull()?.takeIf { it in 1..12 } ?: return null
        return y * 100 + m
    }
}

/**
 * When the item was taken, UTC millis: DATE_TAKEN, else DATE_ADDED, else DATE_MODIFIED (both in
 * seconds). Screenshots and downloads often have no DATE_TAKEN.
 */
fun MediaRow.takenAtMillis(): Long = when {
    (dateTaken ?: 0) > 0 -> dateTaken!!
    (dateAdded ?: 0) > 0 -> dateAdded!! * 1000
    (dateModified ?: 0) > 0 -> dateModified!! * 1000
    else -> 0
}

/**
 * The index row for [this]. [legacyFavorite] is Android 10's app-side favourite flag (MediaStore
 * has none there); null uses IS_FAVORITE.
 */
fun MediaRow.toEntity(zone: ZoneId, legacyFavorite: Boolean? = null): MediaEntity {
    val taken = takenAtMillis()
    return MediaEntity(
        id = id,
        isVideo = isVideo,
        takenAt = taken,
        month = MonthKeys.monthOf(taken, zone),
        offsetMinutes = MonthKeys.offsetMinutes(taken, zone),
        width = width,
        height = height,
        orientation = orientation,
        durationMs = if (isVideo) durationMs else null,
        mimeType = mimeType,
        displayName = displayName,
        sizeBytes = sizeBytes,
        bucketId = bucketId,
        bucketName = bucketName,
        relativePath = relativePath,
        volume = volume,
        favorite = legacyFavorite ?: isFavorite,
        trashed = isTrashed,
        expiresAt = dateExpires?.takeIf { isTrashed && it > 0 }?.let { it * 1000 },
        generation = generationModified ?: dateModified ?: 0,
    )
}

/** What a sync did. */
data class SyncResult(val full: Boolean, val written: Int, val deleted: Int) {
    val changed: Boolean get() = written > 0 || deleted > 0
}

/**
 * Keeps [MediaDao] in step with MediaStore:
 * - a **full** sync the first time, when MediaStore's version changes (its database was rebuilt),
 *   when the access level changes (a partial grant shows a different set), or on Android 10
 *   (no generations): every row is read and compared, unchanged rows aren't rewritten;
 * - otherwise an **incremental** sync: only rows with GENERATION_MODIFIED above the last seen
 *   generation, per volume;
 * - either way, deletions are found by diffing the `_ID` sets.
 *
 * Writes go in batches of [SQL_VARS] rows per transaction, so a 50k library never holds a long
 * write lock and the UI keeps reading. Without access nothing is queried and the index is wiped.
 * Everything runs in [scope] (an IO scope in the app); [requestSync] coalesces bursts (a camera
 * burst fires dozens of ContentObserver calls) into one sync.
 */
class MediaIndexer(
    private val dao: MediaDao,
    private val reader: MediaStoreReader,
    private val access: () -> MediaAccess,
    private val scope: CoroutineScope,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    /** Called after a sync that changed the index (refresh the timelines). */
    private val onChanged: (SyncResult) -> Unit = {},
    /** Android 10's app-side favourites ([AppDao.legacyFavorites]). */
    private val legacyFavorites: suspend () -> LongArray = { LongArray(0) },
) {
    private val mutex = Mutex()
    private val _ready = MutableStateFlow(false)
    /** True once the index has been built at least once (or there's nothing to build). */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    @Volatile private var scheduled = false

    /** Asks for a sync after [delayMs]; requests while one is waiting fold into it. */
    fun requestSync(delayMs: Long = DEBOUNCE_MS) {
        synchronized(this) { if (scheduled) return; scheduled = true }
        scope.launch {
            delay(delayMs)
            scheduled = false
            runCatching { sync() }.onFailure { Log.w(TAG, "Index sync failed", it) }
        }
    }

    /** Suspends until the index has been built once (starting a sync if none has run). */
    suspend fun awaitReady() {
        if (_ready.value) return
        if (dao.meta(KEY_COMPLETE) != null) { _ready.value = true; return }
        requestSync(0)
        _ready.first { it }
    }

    /** Syncs now. [force] makes it a full sync. */
    suspend fun sync(force: Boolean = false): SyncResult = mutex.withLock {
        _syncing.value = true
        try {
            val result = syncLocked(force)
            if (result.changed) onChanged(result)
            result
        } finally {
            _syncing.value = false
            _ready.value = true
        }
    }

    private suspend fun syncLocked(force: Boolean): SyncResult {
        val level = access()
        if (level == MediaAccess.Denied) {
            // No permission: never query, and don't keep showing what we saw before.
            val had = dao.count()
            if (had > 0 || dao.meta(KEY_COMPLETE) != null) { dao.clear(); dao.clearMeta() }
            return SyncResult(full = true, written = 0, deleted = had)
        }
        val tz = zone()
        val version = reader.version().orEmpty()
        val full = force ||
            !reader.hasGenerations ||
            dao.meta(KEY_COMPLETE) == null ||
            dao.meta(KEY_VERSION) != version ||
            dao.meta(KEY_ACCESS) != level.name
        // The phone moved time zones: months are wall-clock months, so re-bucket everything.
        if (!full && dao.meta(KEY_ZONE) != tz.id) rezone(tz)

        val volumes = reader.volumes()
        // Read the generations before querying: anything that changes during the query has a
        // higher generation and is picked up next time.
        val generations = volumes.associateWith { reader.generation(it) }
        val legacyFavorites = if (reader.hasGenerations) null else legacyFavorites().toHashSet()

        var written = 0
        val seen: HashSet<Long>? = if (full) HashSet() else null
        if (full) {
            reader.rows(null, null) { batch ->
                batch.forEach { seen!!.add(it.id) }
                written += write(batch, tz, legacyFavorites)
            }
        } else {
            for (volume in volumes) {
                val since = dao.meta(KEY_GENERATION + volume)?.toLongOrNull()
                val now = generations[volume]
                if (since != null && now != null && now <= since) continue
                reader.rows(volume, since ?: -1) { batch -> written += write(batch, tz, legacyFavorites) }
            }
        }

        // Deletions: whatever the index has that MediaStore no longer lists.
        val present: Set<Long> = seen ?: reader.ids().toHashSet()
        val gone = dao.allIds().filterNot { it in present }
        if (gone.isNotEmpty()) dao.applyBatch(emptyList(), gone)

        dao.putMeta(MetaEntity(KEY_VERSION, version))
        dao.putMeta(MetaEntity(KEY_ACCESS, level.name))
        dao.putMeta(MetaEntity(KEY_ZONE, tz.id))
        generations.forEach { (v, g) -> if (g != null) dao.putMeta(MetaEntity(KEY_GENERATION + v, g.toString())) }
        dao.putMeta(MetaEntity(KEY_COMPLETE, "1"))
        return SyncResult(full, written, gone.size)
    }

    /** Writes the rows of [batch] that differ from the index; keeps a still-valid SHA-1. Returns how many were written. */
    private suspend fun write(batch: List<MediaRow>, tz: ZoneId, legacyFavorites: Set<Long>?): Int {
        val existing = dao.byIds(batch.map { it.id }).associateBy { it.id }
        val changed = ArrayList<MediaEntity>(batch.size)
        for (row in batch) {
            var e = row.toEntity(tz, legacyFavorites?.let { row.id in it })
            val old = existing[row.id]
            if (old != null && old.sha1 != null && old.sha1Generation == e.generation) e = e.copy(sha1 = old.sha1, sha1Generation = old.sha1Generation)
            if (old != e) changed += e
        }
        if (changed.isNotEmpty()) dao.applyBatch(changed, emptyList())
        return changed.size
    }

    private suspend fun rezone(tz: ZoneId) {
        dao.timestamps()
            .map { (id, taken) -> IdMonth(id, MonthKeys.monthOf(taken, tz), MonthKeys.offsetMinutes(taken, tz)) }
            .chunked(2_000)
            .forEach { dao.setMonths(it) }
        dao.putMeta(MetaEntity(KEY_ZONE, tz.id))
    }

    companion object {
        private const val TAG = "MediaIndexer"
        /** Quiet period before a ContentObserver burst triggers a sync. */
        const val DEBOUNCE_MS = 800L
        internal const val KEY_VERSION = "version"
        internal const val KEY_ACCESS = "access"
        internal const val KEY_ZONE = "zone"
        internal const val KEY_COMPLETE = "complete"
        internal const val KEY_GENERATION = "generation:"
    }
}
