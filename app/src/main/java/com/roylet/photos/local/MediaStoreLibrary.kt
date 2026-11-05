package com.roylet.photos.local

import com.roylet.photos.domain.library.Album
import com.roylet.photos.domain.library.AssetDetails
import com.roylet.photos.domain.library.ConsentDeniedException
import com.roylet.photos.domain.library.LocalLibrary
import com.roylet.photos.domain.library.SharedUri
import com.roylet.photos.domain.source.AssetRef
import com.roylet.photos.domain.source.Capabilities
import com.roylet.photos.domain.timeline.TimelineAsset
import com.roylet.photos.domain.timeline.TimelineFilter
import java.io.File
import java.util.Locale

/** One MediaStore write request, before it becomes a PendingIntent (Android 11+). */
sealed interface MediaRequest {
    val uris: List<String>

    /** `createFavoriteRequest(uris, value)`. */
    data class Favorite(override val uris: List<String>, val value: Boolean) : MediaRequest
    /** `createTrashRequest(uris, value)`: true moves to Trash, false restores. */
    data class Trash(override val uris: List<String>, val value: Boolean) : MediaRequest
    /** `createDeleteRequest(uris)`: gone for good. */
    data class Delete(override val uris: List<String>) : MediaRequest

    companion object {
        /** URIs per request: one dialog for a normal selection, and well under the binder's transaction limit. */
        const val MAX_URIS = 500

        private fun uris(items: List<MediaEntity>) = items.map { mediaUriString(it.id, it.isVideo) }.chunked(MAX_URIS)

        fun favorite(items: List<MediaEntity>, value: Boolean): List<MediaRequest> = uris(items).map { Favorite(it, value) }
        fun trash(items: List<MediaEntity>, value: Boolean): List<MediaRequest> = uris(items).map { Trash(it, value) }
        fun delete(items: List<MediaEntity>): List<MediaRequest> = uris(items).map { Delete(it) }
    }
}

/**
 * Runs a [MediaRequest]: builds the PendingIntent and shows it through the app's ConsentLauncher
 * (MediaStore skips the dialog when the app may manage media). True when it was allowed.
 */
fun interface MediaRequestRunner {
    suspend fun run(request: MediaRequest): Boolean
}

/**
 * Android 10's stand-ins for MediaStore's trash: copy the original into the app's private trash
 * folder, delete it from MediaStore (with a RecoverableSecurityException consent for photos other
 * apps made), and put it back on restore.
 */
interface LegacyFiles {
    /** Copies [item]'s bytes into the private trash folder; returns the copy's path. */
    suspend fun copyToTrash(item: MediaEntity): String
    /** Deletes the MediaStore original; false if the user said no. */
    suspend fun deleteOriginal(item: MediaEntity): Boolean
    /** Inserts the copy back into MediaStore at its old folder; returns the new `_ID`. */
    suspend fun restore(row: LegacyTrashEntity): Long
    fun deleteFile(path: String)
}

/** File and EXIF access for one item (the Android one is [AndroidLocalIo]). */
interface LocalIo {
    /** The info sheet: MediaStore columns plus EXIF (camera, GPS when ACCESS_MEDIA_LOCATION is granted). */
    suspend fun details(item: MediaEntity): AssetDetails
    /** Copies the original to [dest]. */
    suspend fun copy(item: MediaEntity, dest: File, onProgress: (Long, Long) -> Unit)
}

/**
 * The phone's library over the MediaStore index:
 * - folders (BUCKET_ID) as read-only albums `folder:<id>`, newest first, with a cover and count;
 * - basic search by file name, folder or date;
 * - favourite, trash, restore and delete through MediaStore requests on Android 11+ (one consent
 *   dialog each, none with MANAGE_MEDIA), and on Android 10 the app's own trash folder and
 *   favourites table;
 * - share by content URI.
 * The index is updated right after each change so the UI needn't wait for the next sync.
 */
class MediaStoreLibrary(
    private val dao: MediaDao,
    private val indexer: MediaIndexer,
    /** Android 11+ (MediaStore trash and favourites); false uses [legacy]. */
    private val mediaStoreWrites: Boolean,
    private val runner: MediaRequestRunner,
    private val legacy: LegacyFiles?,
    private val io: LocalIo,
    private val now: () -> Long = System::currentTimeMillis,
) : LocalLibrary {

    /** FOLDERS, TRASH, FAVORITE. Agent C adds EDIT_SAVE when phone saves land. */
    override val capabilities: Capabilities = Capabilities.Local

    private fun keys(ids: List<String>): List<Long> = ids.mapNotNull { AssetRef.parse(it)?.takeIf { r -> r.isLocal }?.mediaStoreId }

    /** MediaStore items among [ids] (Android 10's trashed copies have negative ids and aren't in MediaStore). */
    private suspend fun items(ids: List<String>): List<MediaEntity> =
        keys(ids).filter { it > 0 }.chunked(SQL_VARS).flatMap { dao.byIds(it) }

    private suspend fun legacyRows(ids: List<String>): List<LegacyTrashEntity> =
        keys(ids).filter { it < 0 }.map { -it }.chunked(SQL_VARS).flatMap { dao.legacyTrashByIds(it) }

    private suspend fun runAll(requests: List<MediaRequest>) {
        for (r in requests) if (!runner.run(r)) throw ConsentDeniedException()
    }

    /* ---------------- Favourite ---------------- */

    override suspend fun setFavorite(ids: List<String>, favorite: Boolean) {
        val list = items(ids)
        if (list.isEmpty()) return
        val keys = list.map { it.id }
        if (mediaStoreWrites) runAll(MediaRequest.favorite(list, favorite))
        else if (favorite) dao.addLegacyFavorites(keys.map(::LegacyFavoriteEntity))
        else keys.chunked(SQL_VARS).forEach { dao.removeLegacyFavorites(it) }
        keys.chunked(SQL_VARS).forEach { dao.setFavorite(it, favorite) }
    }

    /* ---------------- Trash ---------------- */

    override suspend fun trash(ids: List<String>) {
        val list = items(ids)
        if (list.isEmpty()) return
        if (mediaStoreWrites) {
            runAll(MediaRequest.trash(list, true))
            // MediaStore keeps trashed items for 30 days; the next sync reads the real DATE_EXPIRES.
            list.map { it.id }.chunked(SQL_VARS).forEach { dao.setTrashed(it, true, now() + TRASH_DAYS * DAY_MS) }
            return
        }
        val files = legacy ?: error("No trash on this Android version")
        val moved = ArrayList<Long>()
        try {
            for (item in list) {
                val path = files.copyToTrash(item)
                if (!files.deleteOriginal(item)) {
                    files.deleteFile(path)
                    throw ConsentDeniedException()
                }
                dao.addLegacyTrash(item.toLegacyTrash(path, now()))
                moved += item.id
            }
        } finally {
            if (moved.isNotEmpty()) dao.applyBatch(emptyList(), moved)
        }
    }

    override suspend fun restore(ids: List<String>) {
        if (mediaStoreWrites) {
            val list = items(ids)
            if (list.isEmpty()) return
            runAll(MediaRequest.trash(list, false))
            list.map { it.id }.chunked(SQL_VARS).forEach { dao.setTrashed(it, false, null) }
            return
        }
        restoreLegacy(legacyRows(ids))
    }

    private suspend fun restoreLegacy(rows: List<LegacyTrashEntity>): Int {
        val files = legacy ?: return 0
        for (row in rows) {
            val newId = files.restore(row)
            if (row.favorite) dao.addLegacyFavorites(listOf(LegacyFavoriteEntity(newId)))
            files.deleteFile(row.path)
            dao.removeLegacyTrash(listOf(row.rowId))
        }
        if (rows.isNotEmpty()) indexer.requestSync(0)
        return rows.size
    }

    override suspend fun deleteForever(ids: List<String>) {
        if (mediaStoreWrites) {
            val list = items(ids)
            if (list.isEmpty()) return
            runAll(MediaRequest.delete(list))
            dao.applyBatch(emptyList(), list.map { it.id })
            return
        }
        deleteLegacy(legacyRows(ids))
    }

    private suspend fun deleteLegacy(rows: List<LegacyTrashEntity>): Int {
        rows.forEach { legacy?.deleteFile(it.path) }
        rows.map { it.rowId }.chunked(SQL_VARS).forEach { dao.removeLegacyTrash(it) }
        return rows.size
    }

    override suspend fun restoreAll(): Int {
        if (!mediaStoreWrites) return restoreLegacy(dao.legacyTrash())
        val ids = dao.trashedIds()
        if (ids.isEmpty()) return 0
        restore(ids.map { AssetRef.local(it).id })
        return ids.size
    }

    override suspend fun emptyTrash(): Int {
        if (!mediaStoreWrites) return deleteLegacy(dao.legacyTrash())
        val ids = dao.trashedIds()
        if (ids.isEmpty()) return 0
        deleteForever(ids.map { AssetRef.local(it).id })
        return ids.size
    }

    /** Android 10: drops trashed copies past their 30 days. */
    suspend fun purgeExpiredLegacyTrash() {
        if (mediaStoreWrites) return
        deleteLegacy(dao.expiredLegacyTrash(now()))
    }

    /* ---------------- Folders ---------------- */

    override suspend fun albums(): List<Album> {
        val folders = dao.folders()
        // Covers render through LocalMediaUrls, which needs to know image or video.
        folders.map { it.coverId }.chunked(SQL_VARS).forEach { chunk -> dao.byIds(chunk).forEach(LocalAssetInfo::remember) }
        return folders.map { f ->
            Album(
                id = TimelineFilter.FOLDER_PREFIX + f.bucketId,
                name = f.bucketName?.takeIf { it.isNotBlank() } ?: f.relativePath?.trimEnd('/')?.substringAfterLast('/') ?: "Folder",
                description = f.relativePath?.trimEnd('/').orEmpty(),
                assetCount = f.count,
                coverAssetId = AssetRef.local(f.coverId).id,
                updatedAt = f.latest,
            )
        }
    }

    override suspend fun album(id: String): Album = albums().firstOrNull { it.id == id } ?: throw NoSuchElementException("No folder $id")

    /* ---------------- Lookups ---------------- */

    override suspend fun assets(ids: List<String>): List<TimelineAsset> {
        val byId = items(ids).associateBy { it.id }
        val legacyById = legacyRows(ids).associateBy { legacyTrashId(it.rowId) }
        return keys(ids).mapNotNull { k -> byId[k]?.toAsset() ?: legacyById[k]?.toAsset() }
    }

    override suspend fun details(id: String): AssetDetails? {
        val key = keys(listOf(id)).firstOrNull() ?: return null
        val item = dao.byId(key) ?: return null
        return io.details(item)
    }

    override suspend fun basicSearch(query: String): List<TimelineAsset> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val out = LinkedHashMap<Long, MediaEntity>()
        when (val d = DateQuery.parse(q)) {
            is DateQuery.Months -> dao.inMonths(d.from, d.to, SEARCH_LIMIT)
            is DateQuery.MonthOfYear -> dao.inMonthOfYear(d.month, SEARCH_LIMIT)
            null -> emptyList()
        }.forEach { out[it.id] = it }
        dao.searchText("%" + likeEscape(q) + "%", SEARCH_LIMIT).forEach { out.putIfAbsent(it.id, it) }
        return out.values.sortedByDescending { it.takenAt }.take(SEARCH_LIMIT).map { it.toAsset() }
    }

    /* ---------------- Share ---------------- */

    override suspend fun shareUri(id: String): SharedUri? {
        val key = keys(listOf(id)).firstOrNull()?.takeIf { it > 0 } ?: return null
        val item = dao.byId(key) ?: return null
        return SharedUri(mediaUriString(item.id, item.isVideo), item.mimeType)
    }

    override suspend fun download(id: String, dir: File, onProgress: (Long, Long) -> Unit): File {
        val key = keys(listOf(id)).firstOrNull() ?: throw NoSuchElementException("No such photo")
        val item = dao.byId(key) ?: throw NoSuchElementException("No such photo")
        dir.mkdirs()
        val dest = File(dir, item.displayName?.replace('/', '_')?.takeIf { it.isNotBlank() } ?: "photo-${item.id}")
        io.copy(item, dest, onProgress)
        return dest
    }

    companion object {
        const val TRASH_DAYS = 30L
        const val DAY_MS = 86_400_000L
        const val SEARCH_LIMIT = 500

        /** Escapes LIKE's wildcards (the DAO uses `ESCAPE '\'`). */
        fun likeEscape(s: String): String = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    }
}

/** Android 10: the trash row for [this] once its bytes are at [path]. */
fun MediaEntity.toLegacyTrash(path: String, now: Long) = LegacyTrashEntity(
    path = path, originalId = id, isVideo = isVideo, takenAt = takenAt, month = month, offsetMinutes = offsetMinutes,
    width = width, height = height, orientation = orientation, durationMs = durationMs, mimeType = mimeType,
    displayName = displayName, sizeBytes = sizeBytes, bucketName = bucketName, relativePath = relativePath,
    favorite = favorite, trashedAt = now, expiresAt = now + MediaStoreLibrary.TRASH_DAYS * MediaStoreLibrary.DAY_MS,
)

/** A date typed into search: "2024", "March 2023", "2023-03", "mar 23" or just "March". */
sealed interface DateQuery {
    /** `yyyyMM` months, inclusive. */
    data class Months(val from: Int, val to: Int) : DateQuery
    /** The same month in every year (1-12). */
    data class MonthOfYear(val month: Int) : DateQuery

    companion object {
        private val names = listOf("january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december")

        private fun month(word: String): Int? {
            val w = word.lowercase(Locale.ROOT).trimEnd('.')
            if (w.length < 3) return null
            return names.indexOfFirst { it.startsWith(w) }.takeIf { it >= 0 }?.plus(1)
        }

        private fun year(word: String): Int? = word.toIntOrNull()?.let { if (word.length == 2) 2000 + it else it }?.takeIf { it in 1900..2100 && (word.length == 4 || word.length == 2) }

        fun parse(query: String): DateQuery? {
            val q = query.trim()
            Regex("""^(\d{4})-(\d{1,2})$""").find(q)?.let { m ->
                val y = m.groupValues[1].toInt(); val mo = m.groupValues[2].toInt()
                if (mo in 1..12) return Months(y * 100 + mo, y * 100 + mo)
            }
            val words = q.split(Regex("""[\s,]+""")).filter { it.isNotEmpty() }
            when (words.size) {
                1 -> {
                    if (words[0].length == 4) year(words[0])?.let { return Months(it * 100 + 1, it * 100 + 12) }
                    month(words[0])?.let { return MonthOfYear(it) }
                }
                2 -> {
                    val m = month(words[0]) ?: month(words[1])
                    val y = year(words[1]) ?: year(words[0])
                    if (m != null && y != null) return Months(y * 100 + m, y * 100 + m)
                }
            }
            return null
        }
    }
}
