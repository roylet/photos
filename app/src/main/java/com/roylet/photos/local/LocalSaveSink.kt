package com.roylet.photos.local

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.roylet.photos.domain.library.LocalLibrary
import com.roylet.photos.domain.source.AssetRef
import com.roylet.photos.editor.save.LocalSaveException
import com.roylet.photos.editor.save.SaveMode
import com.roylet.photos.editor.save.SaveResult
import com.roylet.photos.editor.save.SaveSink
import com.roylet.photos.editor.save.SinkInput
import com.roylet.photos.editor.save.SinkStage
import com.roylet.photos.ui.permissions.ConsentLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

/*
 * Saving edits on this phone (docs/SOURCES.md §9): a MediaStore insert next to the original with
 * IS_PENDING, then, for Replace, the original to Trash.
 */

/** What a new MediaStore item is created with. */
data class NewMediaItem(
    val displayName: String,
    val mimeType: String,
    val isVideo: Boolean,
    /** e.g. `DCIM/Camera/`. */
    val relativePath: String,
    /** DATE_TAKEN, UTC millis (copied from the original so the edit sorts next to it). */
    val dateTakenMs: Long,
    val favorite: Boolean,
)

/** MediaStore writes, behind an interface so the save flow can be tested with a fake. */
interface LocalMediaWriter {
    /** Inserts a pending (invisible) item and returns its `_ID`. Throws IllegalArgumentException when [NewMediaItem.relativePath] isn't allowed. */
    fun insertPending(item: NewMediaItem): Long
    /** Copies [from] into the item's file. */
    fun write(id: Long, isVideo: Boolean, from: File)
    /** IS_PENDING = 0: the item appears in every gallery. */
    fun publish(id: Long, isVideo: Boolean)
    /**
     * Reads the published item back: true only when it holds [expectedBytes] bytes (> 0) and
     * decodes (an image's bounds, a video's duration). Replace trashes the original only after this.
     */
    fun verify(id: Long, isVideo: Boolean, expectedBytes: Long): Boolean
    /** Removes an item this app inserted (a failed save). */
    fun delete(id: Long, isVideo: Boolean)
}

/** Moving phone items to Trash and back for saves and their undo. */
interface LocalTrasher {
    /** True when [id] went to Trash; false when Android refused (no write access, consent denied). */
    suspend fun trash(id: Long, isVideo: Boolean): Boolean
    suspend fun restore(id: Long, isVideo: Boolean): Boolean
}

/** How far a phone save got, persisted with the job so a retry doesn't insert twice. */
@Serializable
data class LocalSaveCheckpoint(
    val newId: Long? = null,
    val published: Boolean = false,
    /** REPLACE: null = not tried yet. */
    val trashed: Boolean? = null,
    val recipeSaved: Boolean = false,
    val done: Boolean = false,
    val warning: String? = null,
)

/**
 * Saves an edit next to its original on this phone.
 *
 * 1. Insert with IS_PENDING into the original's RELATIVE_PATH (or `Pictures/Photos Edited`,
 *    `Movies/Photos Edited` when Android doesn't allow it there), named `<name>-edited.jpg|mp4`,
 *    with the original's DATE_TAKEN and IS_FAVORITE. The file's own EXIF was already transplanted
 *    from the original when it was rendered.
 * 2. Write the bytes, then IS_PENDING = 0. A failure deletes the pending item.
 * 3. **Replace**: the original to Trash (the user allowed this before the save was queued). If
 *    Android refuses, it's a copy with a warning.
 * 4. The edit (recipe / video edit) goes in [AppDao] under the new id, for re-editing.
 */
class LocalSaveSink(
    private val dao: MediaDao,
    private val appDao: AppDao,
    private val writer: LocalMediaWriter,
    private val trasher: LocalTrasher,
    private val checkpoint: LocalSaveCheckpoint = LocalSaveCheckpoint(),
    private val onCheckpoint: (LocalSaveCheckpoint) -> Unit = {},
    /** After the item is published: re-sync the index so it shows up at once. */
    private val onWritten: () -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) : SaveSink {

    override suspend fun save(input: SinkInput, onProgress: (SinkStage, Float) -> Unit): SaveResult {
        var cp = checkpoint
        fun commit(next: SaveCheckpointUpdate) { cp = next(cp); onCheckpoint(cp) }
        val targetKey = AssetRef.parse(input.targetId)?.mediaStoreId ?: throw LocalSaveException("This isn't a photo on this phone.")
        val replace = input.mode == SaveMode.REPLACE
        if (!cp.done) {
            val original = dao.byId(targetKey) ?: throw LocalSaveException("The original is no longer on this phone.")
            if (!cp.published) {
                onProgress(SinkStage.WRITING, 0f)
                val id = cp.newId ?: insert(input, original).also { id -> commit { it.copy(newId = id) } }
                try {
                    withContext(Dispatchers.IO) {
                        writer.write(id, input.isVideo, input.file)
                        writer.publish(id, input.isVideo)
                        // Nothing is trashed (Replace) until the new file is complete and readable.
                        if (!writer.verify(id, input.isVideo, input.file.length())) throw java.io.IOException("the saved file is empty or unreadable")
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    runCatching { writer.delete(id, input.isVideo) }
                    commit { it.copy(newId = null) }
                    throw LocalSaveException("Couldn't save the edit on this phone (${e.message ?: e.javaClass.simpleName}).", e)
                }
                commit { it.copy(published = true) }
                runCatching(onWritten)
            }
            val newId = cp.newId!!
            if (replace && cp.trashed == null) {
                onProgress(SinkStage.TRASHING, 0f)
                val ok = runCatching { trasher.trash(original.id, original.isVideo) }.getOrDefault(false)
                commit {
                    it.copy(trashed = ok, warning = it.warning ?: if (ok) null else "Saved as a copy: Android didn't allow moving the original to Trash.")
                }
            }
            if (!cp.recipeSaved) {
                onProgress(SinkStage.RECIPE, 0f)
                val saved = runCatching {
                    appDao.putEdit(LocalEditEntity(newId, input.renderSourceId, kindOf(input), input.editValue.toString(), now()))
                }.isSuccess
                commit { it.copy(recipeSaved = true, warning = it.warning ?: if (saved) null else "Saved, but the edit can't be reopened later.") }
            }
            commit { it.copy(done = true) }
        }
        onProgress(SinkStage.DONE, 1f)
        val trashed = cp.trashed == true
        return SaveResult(
            newAssetId = AssetRef.local(cp.newId!!).id,
            targetId = AssetRef.local(targetKey).id,
            mode = if (replace && trashed) SaveMode.REPLACE else SaveMode.COPY,
            originalTrashed = trashed,
            warning = cp.warning,
            local = true,
        )
    }

    private fun insert(input: SinkInput, original: MediaEntity): Long {
        val item = NewMediaItem(
            displayName = input.filename,
            mimeType = input.mimeType,
            isVideo = input.isVideo,
            relativePath = original.relativePath?.takeIf { it.isNotBlank() } ?: fallbackPath(input.isVideo),
            dateTakenMs = original.takenAt,
            favorite = original.favorite,
        )
        return try {
            writer.insertPending(item)
        } catch (e: IllegalArgumentException) {
            // Not an allowed folder for this kind of file (e.g. a photo in Download/).
            writer.insertPending(item.copy(relativePath = fallbackPath(input.isVideo)))
        }
    }

    override suspend fun undoReplace(result: SaveResult) {
        val original = AssetRef.parse(result.targetId)?.mediaStoreId ?: return
        val edit = AssetRef.parse(result.newAssetId)?.mediaStoreId ?: return
        val video = dao.byId(edit)?.isVideo ?: result.newAssetId.endsWith(".mp4")
        if (!trasher.restore(original, video)) throw LocalSaveException("Android didn't allow restoring the original.")
        trasher.trash(edit, video)
    }

    private fun interface SaveCheckpointUpdate { operator fun invoke(cp: LocalSaveCheckpoint): LocalSaveCheckpoint }

    companion object {
        const val PHOTOS_FOLDER = "Pictures/Photos Edited/"
        const val VIDEOS_FOLDER = "Movies/Photos Edited/"

        fun fallbackPath(isVideo: Boolean) = if (isVideo) VIDEOS_FOLDER else PHOTOS_FOLDER

        fun kindOf(input: SinkInput) = if (input.isVideo) LocalEditEntity.VIDEO else LocalEditEntity.PHOTO
    }
}

/** [LocalMediaWriter] over the ContentResolver (Android 10+, scoped storage). */
class AndroidMediaWriter(private val context: Context) : LocalMediaWriter {
    private val resolver: ContentResolver = context.contentResolver

    private fun collection(isVideo: Boolean): Uri =
        if (isVideo) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    override fun insertPending(item: NewMediaItem): Long {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, item.displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, item.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, item.relativePath)
            put(MediaStore.MediaColumns.DATE_TAKEN, item.dateTakenMs)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && item.favorite) put(MediaStore.MediaColumns.IS_FAVORITE, 1)
        }
        val uri = resolver.insert(collection(item.isVideo), values) ?: throw LocalSaveException("Android didn't create the file.")
        return android.content.ContentUris.parseId(uri)
    }

    override fun write(id: Long, isVideo: Boolean, from: File) {
        // "wt": truncate, so a retry into the same item never leaves the tail of an earlier attempt.
        val out = resolver.openOutputStream(mediaUri(id, isVideo), "wt") ?: throw LocalSaveException("Couldn't open the new file.")
        out.use { o -> from.inputStream().use { it.copyTo(o, 64 * 1024) } }
    }

    override fun publish(id: Long, isVideo: Boolean) {
        resolver.update(mediaUri(id, isVideo), ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    }

    override fun delete(id: Long, isVideo: Boolean) {
        resolver.delete(mediaUri(id, isVideo), null, null)
    }

    override fun verify(id: Long, isVideo: Boolean, expectedBytes: Long): Boolean = runCatching {
        val uri = mediaUri(id, isVideo)
        val size = resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: return false
        if (size <= 0 || (expectedBytes > 0 && size != expectedBytes)) return false
        if (isVideo) {
            val r = android.media.MediaMetadataRetriever()
            try {
                r.setDataSource(context, uri)
                (r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) > 0
            } finally { r.release() }
        } else {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
            bounds.outWidth > 0 && bounds.outHeight > 0
        }
    }.getOrDefault(false)
}

/**
 * [LocalTrasher] on the phone. Android 11+: IS_TRASHED set directly, which works for the app's own
 * files and for originals the user gave write access to before the save (see
 * [requestEditConsent]); otherwise the library's trash, which asks through [ConsentLauncher] when
 * a screen is showing. Android 10: the library's app-private trash.
 */
class AndroidLocalTrasher(
    context: Context,
    private val dao: MediaDao,
    private val library: () -> LocalLibrary,
) : LocalTrasher {
    private val resolver = context.contentResolver
    private val direct = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    private suspend fun set(id: Long, isVideo: Boolean, trashed: Boolean): Boolean = withContext(Dispatchers.IO) {
        if (!direct) return@withContext false
        runCatching {
            resolver.update(mediaUri(id, isVideo), ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, if (trashed) 1 else 0) }, null, null) > 0
        }.getOrDefault(false).also { ok ->
            if (ok) dao.setTrashed(listOf(id), trashed, if (trashed) System.currentTimeMillis() + 30 * 86_400_000L else null)
        }
    }

    override suspend fun trash(id: Long, isVideo: Boolean): Boolean =
        set(id, isVideo, true) || runCatching { library().trash(listOf(AssetRef.local(id).id)); true }.getOrDefault(false)

    override suspend fun restore(id: Long, isVideo: Boolean): Boolean =
        set(id, isVideo, false) || runCatching { library().restore(listOf(AssetRef.local(id).id)); true }.getOrDefault(false)

    companion object {
        /**
         * Asks, before a Replace is queued, for write access to the original so the save can move
         * it to Trash later without a screen (MANAGE_MEDIA approves it without a dialog). Android
         * 10 has no such request: its trash asks when the save gets there. False = the user said no.
         */
        suspend fun requestEditConsent(context: Context, consent: ConsentLauncher, id: Long, isVideo: Boolean): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true
            val request = MediaStore.createWriteRequest(context.contentResolver, listOf(mediaUri(id, isVideo)))
            return consent.requestConsent(request)
        }
    }
}
