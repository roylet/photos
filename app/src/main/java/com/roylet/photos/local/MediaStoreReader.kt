package com.roylet.photos.local

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns
import android.provider.MediaStore.MediaColumns

/**
 * One image or video row as MediaStore reports it. Times are as stored: [dateTaken] in millis,
 * [dateAdded] / [dateModified] / [dateExpires] in seconds; null when the column is empty.
 */
data class MediaRow(
    val id: Long,
    val isVideo: Boolean,
    val dateTaken: Long? = null,
    val dateAdded: Long? = null,
    val dateModified: Long? = null,
    val width: Int = 0,
    val height: Int = 0,
    val orientation: Int = 0,
    val durationMs: Long? = null,
    val mimeType: String? = null,
    val displayName: String? = null,
    val sizeBytes: Long? = null,
    val bucketId: Long? = null,
    val bucketName: String? = null,
    val relativePath: String? = null,
    val volume: String? = null,
    val isFavorite: Boolean = false,
    val isTrashed: Boolean = false,
    val dateExpires: Long? = null,
    /** GENERATION_MODIFIED (Android 11+); null before. */
    val generationModified: Long? = null,
)

/**
 * What the indexer needs from MediaStore, behind an interface so the sync logic is unit tested
 * with a fake library ([AndroidMediaStoreReader] is the real one). Calls block: run them off the
 * main thread.
 */
interface MediaStoreReader {
    /** Android 11+: GENERATION_MODIFIED, IS_FAVORITE, IS_TRASHED and the trash/favourite requests exist. */
    val hasGenerations: Boolean

    /** Changes when MediaStore's database is rebuilt (generations restart): a full sync is needed then. */
    fun version(): String?

    /** The external volumes (internal storage, SD cards). */
    fun volumes(): Set<String>

    /** The volume's current generation, or null before Android 11. */
    fun generation(volume: String): Long?

    /**
     * Every image and video (trashed ones included on 11+, pending ones never), or only those on
     * [volume] with GENERATION_MODIFIED > [sinceGeneration]. Rows arrive in batches of up to [batch].
     */
    suspend fun rows(volume: String?, sinceGeneration: Long?, batch: Int = SQL_VARS, onBatch: suspend (List<MediaRow>) -> Unit)

    /** The `_ID`s of every image and video (trashed included), for spotting deletions. */
    fun ids(): LongArray
}

/** The content URI of a MediaStore item (the Images or Video collection: MediaStore's write requests need those). */
fun mediaUri(id: Long, isVideo: Boolean): Uri =
    ContentUris.withAppendedId(if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

/** [mediaUri] as a string, without android.net.Uri (for pure code and tests). */
fun mediaUriString(id: Long, isVideo: Boolean): String =
    "content://media/external/${if (isVideo) "video" else "images"}/media/$id"

/** MediaStore through the app's ContentResolver. */
class AndroidMediaStoreReader(private val context: Context) : MediaStoreReader {
    private val resolver = context.contentResolver

    override val hasGenerations: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    override fun version(): String? = runCatching { MediaStore.getVersion(context) }.getOrNull()

    override fun volumes(): Set<String> = runCatching { MediaStore.getExternalVolumeNames(context) }.getOrDefault(setOf(MediaStore.VOLUME_EXTERNAL_PRIMARY))

    override fun generation(volume: String): Long? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) runCatching { MediaStore.getGeneration(context, volume) }.getOrNull() else null

    private val projection: Array<String> = buildList {
        add(MediaColumns._ID); add(FileColumns.MEDIA_TYPE)
        add(MediaColumns.DATE_TAKEN); add(MediaColumns.DATE_ADDED); add(MediaColumns.DATE_MODIFIED)
        add(MediaColumns.WIDTH); add(MediaColumns.HEIGHT); add(MediaColumns.ORIENTATION); add(MediaColumns.DURATION)
        add(MediaColumns.MIME_TYPE); add(MediaColumns.DISPLAY_NAME); add(MediaColumns.SIZE)
        add(MediaColumns.BUCKET_ID); add(MediaColumns.BUCKET_DISPLAY_NAME); add(MediaColumns.RELATIVE_PATH); add(MediaColumns.VOLUME_NAME)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            add(MediaColumns.IS_FAVORITE); add(MediaColumns.IS_TRASHED); add(MediaColumns.DATE_EXPIRES); add(MediaColumns.GENERATION_MODIFIED)
        }
    }.toTypedArray()

    private fun args(sinceGeneration: Long?): Bundle = Bundle().apply {
        val where = StringBuilder("${FileColumns.MEDIA_TYPE} IN (${FileColumns.MEDIA_TYPE_IMAGE}, ${FileColumns.MEDIA_TYPE_VIDEO})")
        val whereArgs = ArrayList<String>()
        if (sinceGeneration != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            where.append(" AND ${MediaColumns.GENERATION_MODIFIED} > ?")
            whereArgs += sinceGeneration.toString()
        }
        putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, where.toString())
        if (whereArgs.isNotEmpty()) putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, whereArgs.toTypedArray())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
    }

    private fun collection(volume: String?): Uri = MediaStore.Files.getContentUri(volume ?: MediaStore.VOLUME_EXTERNAL)

    override suspend fun rows(volume: String?, sinceGeneration: Long?, batch: Int, onBatch: suspend (List<MediaRow>) -> Unit) {
        val cursor = resolver.query(collection(volume), projection, args(sinceGeneration), null) ?: return
        cursor.use { c ->
            val col = Columns(c)
            var buffer = ArrayList<MediaRow>(batch)
            while (c.moveToNext()) {
                buffer += col.read(c)
                if (buffer.size >= batch) { onBatch(buffer); buffer = ArrayList(batch) }
            }
            if (buffer.isNotEmpty()) onBatch(buffer)
        }
    }

    override fun ids(): LongArray {
        val cursor = resolver.query(collection(null), arrayOf(MediaColumns._ID), args(null), null) ?: return LongArray(0)
        return cursor.use { c ->
            val out = LongArray(c.count)
            var i = 0
            while (c.moveToNext() && i < out.size) out[i++] = c.getLong(0)
            if (i == out.size) out else out.copyOf(i)
        }
    }

    /** Column indices, looked up once per cursor. */
    private class Columns(c: Cursor) {
        val id = c.getColumnIndexOrThrow(MediaColumns._ID)
        val type = c.getColumnIndexOrThrow(FileColumns.MEDIA_TYPE)
        val taken = c.getColumnIndex(MediaColumns.DATE_TAKEN)
        val added = c.getColumnIndex(MediaColumns.DATE_ADDED)
        val modified = c.getColumnIndex(MediaColumns.DATE_MODIFIED)
        val width = c.getColumnIndex(MediaColumns.WIDTH)
        val height = c.getColumnIndex(MediaColumns.HEIGHT)
        val orientation = c.getColumnIndex(MediaColumns.ORIENTATION)
        val duration = c.getColumnIndex(MediaColumns.DURATION)
        val mime = c.getColumnIndex(MediaColumns.MIME_TYPE)
        val name = c.getColumnIndex(MediaColumns.DISPLAY_NAME)
        val size = c.getColumnIndex(MediaColumns.SIZE)
        val bucket = c.getColumnIndex(MediaColumns.BUCKET_ID)
        val bucketName = c.getColumnIndex(MediaColumns.BUCKET_DISPLAY_NAME)
        val path = c.getColumnIndex(MediaColumns.RELATIVE_PATH)
        val volume = c.getColumnIndex(MediaColumns.VOLUME_NAME)
        val favorite = c.getColumnIndex("is_favorite")
        val trashed = c.getColumnIndex("is_trashed")
        val expires = c.getColumnIndex("date_expires")
        val generation = c.getColumnIndex("generation_modified")

        private fun Cursor.long(i: Int): Long? = if (i < 0 || isNull(i)) null else getLong(i)
        private fun Cursor.int(i: Int): Int = if (i < 0 || isNull(i)) 0 else getInt(i)
        private fun Cursor.str(i: Int): String? = if (i < 0 || isNull(i)) null else getString(i)

        fun read(c: Cursor) = MediaRow(
            id = c.getLong(id),
            isVideo = c.getInt(type) == FileColumns.MEDIA_TYPE_VIDEO,
            dateTaken = c.long(taken),
            dateAdded = c.long(added),
            dateModified = c.long(modified),
            width = c.int(width),
            height = c.int(height),
            orientation = c.int(orientation),
            durationMs = c.long(duration),
            mimeType = c.str(mime),
            displayName = c.str(name),
            sizeBytes = c.long(size),
            bucketId = c.long(bucket),
            bucketName = c.str(bucketName),
            relativePath = c.str(path),
            volume = c.str(volume),
            isFavorite = c.int(favorite) == 1,
            isTrashed = c.int(trashed) == 1,
            dateExpires = c.long(expires),
            generationModified = c.long(generation),
        )
    }
}
