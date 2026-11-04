package com.roylet.photos.video.save

import com.roylet.photos.editor.save.SaveFlow
import com.roylet.photos.editor.save.SaveMode
import com.roylet.photos.editor.save.SaveResult
import com.roylet.photos.immich.AssetCopyRequest
import com.roylet.photos.immich.EditAssetInfo
import com.roylet.photos.immich.ImmichApi
import com.roylet.photos.immich.ImmichException
import com.roylet.photos.immich.UploadSpec
import com.roylet.photos.immich.assetInfo
import com.roylet.photos.immich.copyAsset
import com.roylet.photos.immich.createStack
import com.roylet.photos.immich.deleteAssets
import com.roylet.photos.immich.putMetadata
import com.roylet.photos.immich.restoreAssets
import com.roylet.photos.video.model.VideoEdit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException

/** Where a video save is, for the progress UI and the notification. */
enum class VideoSaveStage(val label: String) {
    QUEUED("Waiting to start"),
    WAITING_FOR_NETWORK("Waiting for a connection"),
    DOWNLOADING("Downloading original"),
    EXPORTING("Exporting video"),
    UPLOADING("Uploading"),
    LINKING("Copying albums & details"),
    GROUPING("Grouping with original"),
    TRASHING("Moving original to Trash"),
    RECIPE("Saving edit details"),
    DONE("Saved"),
    ;

    companion object {
        /** Overall progress across stages, for one progress bar. Export dominates for long 4K clips. */
        fun overall(stage: VideoSaveStage, fraction: Float): Float {
            val f = fraction.coerceIn(0f, 1f)
            return when (stage) {
                QUEUED, WAITING_FOR_NETWORK -> 0f
                DOWNLOADING -> 0.2f * f
                EXPORTING -> 0.2f + 0.5f * f
                UPLOADING -> 0.7f + 0.25f * f
                LINKING -> 0.96f
                GROUPING, TRASHING -> 0.97f
                RECIPE -> 0.98f
                DONE -> 1f
            }
        }
    }
}

/** The `photos.videoEdit` asset-metadata value: the asset the frames came from, and the edit. */
object VideoEditMetadata {
    const val KEY = "photos.videoEdit"
    const val VERSION = 1

    fun encode(sourceId: String, edit: VideoEdit): JsonObject = buildJsonObject {
        put("version", VERSION)
        put("sourceId", sourceId)
        put("edit", ImmichApi.json.parseToJsonElement(edit.toJson()))
    }

    /** Null if [value] isn't an edit this app wrote. */
    fun decode(value: JsonObject): Pair<String, VideoEdit>? = runCatching {
        val source = (value["sourceId"] as? JsonPrimitive)?.contentOrNull ?: return null
        source to VideoEdit.fromJson(value["edit"]!!.jsonObject.toString())
    }.getOrNull()
}

/**
 * The network half of saving a video edit, once the MP4 is exported. The same order and safety
 * rules as the photo [SaveFlow] (a near copy of it; unify when the photo flow can take a mime type):
 *
 * 1. streaming upload of the MP4 with the original's `fileCreatedAt`/`fileModifiedAt`;
 * 2. **copy**: `PUT /assets/copy` (albums only), then `POST /stacks [new, target]` when grouping;
 *    **replace**: `PUT /assets/copy` (everything), then `DELETE /assets [target]` (to Trash);
 * 3. `PUT /assets/{new}/metadata` with [VideoEditMetadata].
 *
 * The original is never trashed unless the upload and the copy both succeeded. Upload failures
 * propagate (the worker retries network ones); later failures become [SaveResult.warning].
 */
class VideoSaveFlow(private val api: ImmichApi) {

    suspend fun run(
        target: EditAssetInfo,
        /** The asset whose frames were edited (differs from [target] when re-editing from the original). */
        renderSourceId: String,
        file: File,
        filename: String,
        edit: VideoEdit,
        mode: SaveMode,
        group: Boolean,
        /** An earlier attempt of this job may have uploaded this file already (its response lost). */
        uploadAttemptedBefore: Boolean = false,
        onProgress: (VideoSaveStage, Float) -> Unit = { _, _ -> },
    ): SaveResult {
        val targetId = target.id
        onProgress(VideoSaveStage.UPLOADING, 0f)
        val size = file.length()
        if (size <= 0) throw IOException("The exported video is empty")
        val spec = UploadSpec(
            filename = filename,
            mime = "video/mp4",
            size = size,
            checksum = SaveFlow.sha1Hex(file),
            deviceAssetId = "photos-video-edit-$targetId-${file.lastModified()}",
            createdAt = target.fileCreatedAt,
            modifiedAt = target.fileModifiedAt,
            open = { file.inputStream() },
        )
        val uploaded = api.upload(spec) { sent -> onProgress(VideoSaveStage.UPLOADING, if (size > 0) sent.toFloat() / size else 1f) }
        val newId = uploaded.id
        val duplicate = uploaded.status == "duplicate"
        if (newId == targetId) throw ImmichException("This edit is identical to the video you opened, so there's nothing new to save.")

        val warnings = mutableListOf<String>()
        // A duplicate may be in Trash (an earlier save of the same file, undone): restore it, or a
        // REPLACE would leave only trashed assets. A duplicate of our own lost-response upload
        // isn't "preexisting": undo may trash it.
        var preexisting = false
        if (duplicate) {
            val existing = api.assetInfo(newId)
            if (existing.isTrashed) api.restoreAssets(listOf(newId)) else preexisting = !uploadAttemptedBefore
        }
        onProgress(VideoSaveStage.LINKING, 0f)
        val replace = mode == SaveMode.REPLACE
        val copied = runCatching {
            api.copyAsset(AssetCopyRequest(
                sourceId = targetId, targetId = newId,
                albums = true, favorite = replace, sharedLinks = replace, stack = replace, sidecar = replace,
            ))
        }
        var grouped = false
        var trashed = false
        if (copied.isFailure) {
            val why = ImmichApi.describe(copied.exceptionOrNull()!!)
            warnings += if (replace) "Saved as a copy instead: the original was kept because its albums and details couldn't be copied ($why)."
            else "Saved, but it couldn't be added to the original's albums ($why)."
        }
        if (!replace && group) {
            onProgress(VideoSaveStage.GROUPING, 0f)
            runCatching { api.createStack(listOf(newId, targetId)) }
                .onSuccess { grouped = true }
                .onFailure { warnings += "Saved, but it couldn't be grouped with the original (${ImmichApi.describe(it)})." }
        }
        if (replace && copied.isSuccess) {
            onProgress(VideoSaveStage.TRASHING, 0f)
            runCatching { api.deleteAssets(listOf(targetId), force = false) }
                .onSuccess { trashed = true }
                .onFailure { warnings += "Saved, but the original couldn't be moved to Trash (${ImmichApi.describe(it)})." }
        }
        onProgress(VideoSaveStage.RECIPE, 0f)
        runCatching { api.putMetadata(newId, VideoEditMetadata.KEY, VideoEditMetadata.encode(renderSourceId, edit)) }
            .onFailure { warnings += "Saved, but the edit can't be reopened later (${ImmichApi.describe(it)})." }
        onProgress(VideoSaveStage.DONE, 1f)
        return SaveResult(
            newAssetId = newId,
            targetId = targetId,
            mode = if (replace && copied.isFailure) SaveMode.COPY else mode,
            grouped = grouped,
            originalTrashed = trashed,
            duplicate = duplicate,
            preexisting = preexisting,
            warning = warnings.firstOrNull(),
        )
    }

    /** Undo a REPLACE: original back from Trash, then the edit to Trash (unless it predated the save). */
    suspend fun undoReplace(result: SaveResult) {
        api.restoreAssets(listOf(result.targetId))
        if (!result.preexisting) api.deleteAssets(listOf(result.newAssetId), force = false)
    }

    companion object {
        /** `IMG_0001.MOV` → `IMG_0001-edited.mp4` (without stacking up `-edited-edited`). */
        fun editedFilename(original: String): String {
            val base = original.substringBeforeLast('.', original).ifBlank { "video" }
            return (if (base.endsWith("-edited")) base else "$base-edited") + ".mp4"
        }
    }
}
