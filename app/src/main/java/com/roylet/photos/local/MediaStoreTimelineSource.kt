package com.roylet.photos.local

import com.roylet.photos.domain.library.LocalTimelineSource
import com.roylet.photos.domain.timeline.TimeBucket
import com.roylet.photos.domain.timeline.TimelineAsset
import com.roylet.photos.domain.timeline.TimelineFilter
import com.roylet.photos.ui.permissions.MediaAccess

/**
 * The phone's timeline, read from the MediaStore index ([MediaDao]) one month at a time.
 * - [TimelineFilter.Timeline]: everything not trashed (`visibility` is ignored: no archive here);
 * - `isFavorite`: favourites; `isTrashed`: the trash (Android 10: the app's own trash folder);
 * - `albumId = folder:<BUCKET_ID>`: one folder; any other album, person or tag has nothing local.
 * Without media access it shows nothing (and the index is never queried).
 */
class MediaStoreTimelineSource(
    private val dao: MediaDao,
    /** Android 10's trash list. */
    private val appDao: AppDao,
    private val indexer: MediaIndexer,
    private val access: () -> MediaAccess,
    /** Android 11+: MediaStore's own trash. Android 10 lists the app-private trash instead. */
    private val mediaStoreTrash: Boolean,
    /**
     * Folders (bucket ids) the Photos timeline leaves out: hidden ones, and those turned off in
     * Settings → device folders in Photos. Only [TimelineFilter.Timeline] uses it: folders and
     * Library → On this device show everything.
     */
    private val excludedFromPhotos: suspend () -> Set<Long> = { emptySet() },
    /** Hidden folders (bucket ids): [TimelineFilter.Favorites] leaves them out too. */
    private val hiddenFolders: suspend () -> Set<Long> = { emptySet() },
) : LocalTimelineSource {

    /** The index query for [filter], or null when nothing local can match it. */
    internal data class Query(val trashed: Boolean, val favorite: Boolean, val bucketId: Long?, val photos: Boolean = false)

    internal fun queryFor(filter: TimelineFilter): Query? {
        if (filter.personId != null || filter.tagId != null) return null
        val bucket = filter.albumId?.let { id ->
            if (!id.startsWith(TimelineFilter.FOLDER_PREFIX)) return null
            id.removePrefix(TimelineFilter.FOLDER_PREFIX).toLongOrNull() ?: return null
        }
        val trashed = filter.isTrashed == true
        val favorite = filter.isFavorite == true
        // The main Photos timeline and Favorites (not pinned to the phone, not a folder): device-folder choices apply.
        val photos = !trashed && bucket == null && filter.source == null
        return Query(trashed = trashed, favorite = favorite, bucketId = bucket, photos = photos)
    }

    /**
     * Folders [q] leaves out (sorted, for a stable query): for the Photos timeline the hidden and
     * turned-off ones, for Favorites the hidden ones; nothing for the rest.
     */
    private suspend fun excluded(q: Query): List<Long> = when {
        !q.photos -> emptyList()
        q.favorite -> hiddenFolders().sorted()
        else -> excludedFromPhotos().sorted()
    }

    private suspend fun ready(): Boolean {
        if (access() == MediaAccess.Denied) return false
        indexer.awaitReady()
        return true
    }

    private fun legacyTrash(q: Query) = q.trashed && !mediaStoreTrash

    override suspend fun buckets(filter: TimelineFilter): List<TimeBucket> {
        val q = queryFor(filter) ?: return emptyList()
        if (!ready()) return emptyList()
        val months = if (legacyTrash(q)) {
            if (q.favorite || q.bucketId != null) emptyList() else appDao.legacyTrashMonths()
        } else excluded(q).let { ex -> if (ex.isNotEmpty()) dao.monthsExcluding(ex, q.favorite) else dao.months(q.trashed, q.favorite, q.bucketId) }
        val ordered = if (filter.newestFirst) months else months.reversed()
        return ordered.map { TimeBucket(MonthKeys.key(it.month), it.count) }
    }

    override suspend fun bucket(filter: TimelineFilter, key: String): List<TimelineAsset> {
        val q = queryFor(filter) ?: return emptyList()
        val month = MonthKeys.parse(key) ?: return emptyList()
        if (!ready()) return emptyList()
        val assets = if (legacyTrash(q)) {
            if (q.favorite || q.bucketId != null) emptyList() else appDao.legacyTrashInMonth(month).map { it.toAsset() }
        } else {
            val ex = excluded(q)
            (if (ex.isNotEmpty()) dao.inMonthExcluding(month, ex, q.favorite) else dao.inMonth(month, q.trashed, q.favorite, q.bucketId)).map { it.toAsset() }
        }
        return if (filter.newestFirst) assets else assets.reversed()
    }
}
