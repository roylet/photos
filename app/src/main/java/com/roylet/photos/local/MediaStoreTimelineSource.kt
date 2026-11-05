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
) : LocalTimelineSource {

    /** The index query for [filter], or null when nothing local can match it. */
    internal data class Query(val trashed: Boolean, val favorite: Boolean, val bucketId: Long?)

    internal fun queryFor(filter: TimelineFilter): Query? {
        if (filter.personId != null || filter.tagId != null) return null
        val bucket = filter.albumId?.let { id ->
            if (!id.startsWith(TimelineFilter.FOLDER_PREFIX)) return null
            id.removePrefix(TimelineFilter.FOLDER_PREFIX).toLongOrNull() ?: return null
        }
        return Query(trashed = filter.isTrashed == true, favorite = filter.isFavorite == true, bucketId = bucket)
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
        } else dao.months(q.trashed, q.favorite, q.bucketId)
        val ordered = if (filter.newestFirst) months else months.reversed()
        return ordered.map { TimeBucket(MonthKeys.key(it.month), it.count) }
    }

    override suspend fun bucket(filter: TimelineFilter, key: String): List<TimelineAsset> {
        val q = queryFor(filter) ?: return emptyList()
        val month = MonthKeys.parse(key) ?: return emptyList()
        if (!ready()) return emptyList()
        val assets = if (legacyTrash(q)) {
            if (q.favorite || q.bucketId != null) emptyList() else appDao.legacyTrashInMonth(month).map { it.toAsset() }
        } else dao.inMonth(month, q.trashed, q.favorite, q.bucketId).map { it.toAsset() }
        return if (filter.newestFirst) assets else assets.reversed()
    }
}
