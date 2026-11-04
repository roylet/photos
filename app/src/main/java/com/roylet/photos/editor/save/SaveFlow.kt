package com.roylet.photos.editor.save

import com.roylet.photos.editor.recipe.Recipe
import com.roylet.photos.immich.AssetCopyRequest
import com.roylet.photos.immich.ImmichApi
import com.roylet.photos.immich.ImmichException
import com.roylet.photos.immich.UploadSpec
import com.roylet.photos.immich.assetInfo
import com.roylet.photos.immich.copyAsset
import com.roylet.photos.immich.createStack
import com.roylet.photos.immich.deleteAssets
import com.roylet.photos.immich.putMetadata
import com.roylet.photos.immich.restoreFromTrash
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest

/** How an edit is saved. */
enum class SaveMode { COPY, REPLACE }

/** Where a save is, for the progress UI and the notification. */
enum class SaveStage(val label: String) {
    QUEUED("Waiting to start"),
    RENDERING("Rendering full resolution"),
    UPLOADING("Uploading"),
    LINKING("Copying albums & details"),
    GROUPING("Grouping with original"),
    TRASHING("Moving original to Trash"),
    RECIPE("Saving edit recipe"),
    DONE("Saved"),
}

/** The outcome of a save, also what the viewer needs for its "Original moved to Trash · UNDO" snackbar. */
@Serializable
data class SaveResult(
    val newAssetId: String,
    /** The asset the user opened (copied from, or replaced). */
    val targetId: String,
    val mode: SaveMode,
    val grouped: Boolean = false,
    /** True when the original really went to Trash (REPLACE only). */
    val originalTrashed: Boolean = false,
    /** Immich already had this exact file. */
    val duplicate: Boolean = false,
    /** [duplicate], and that asset existed (outside Trash) before this save, so undo must leave it alone. */
    val preexisting: Boolean = false,
    /** A non-fatal problem worth telling the user about. */
    val warning: String? = null,
)

/** The `photos.recipe` asset-metadata value: which asset the pixels came from, and the recipe. */
object RecipeMetadata {
    const val KEY = "photos.recipe"
    const val VERSION = 1

    fun encode(sourceId: String, recipe: Recipe): JsonObject = buildJsonObject {
        put("version", VERSION)
        put("sourceId", sourceId)
        put("recipe", ImmichApi.json.parseToJsonElement(recipe.toJson()))
    }

    /** Null if [value] isn't a recipe this app wrote. */
    fun decode(value: JsonObject): Pair<String, Recipe>? = runCatching {
        val source = (value["sourceId"] as? JsonPrimitive)?.contentOrNull ?: return null
        val recipe = Recipe.fromJson(value["recipe"]!!.jsonObject.toString())
        source to recipe
    }.getOrNull()
}

/**
 * The network half of saving an edit, after the JPEG is rendered:
 *
 * 1. `GET /assets/{target}` for the original's dates;
 * 2. streaming upload of the JPEG with those dates;
 * 3. **copy**: `PUT /assets/copy` (albums only), then `POST /stacks [new, target]` when grouping;
 *    **replace**: `PUT /assets/copy` (everything), then `DELETE /assets [target]` (to Trash);
 * 4. `PUT /assets/{new}/metadata` with [RecipeMetadata].
 *
 * Nothing is deleted unless the upload and the copy both succeeded. Later failures are reported
 * as [SaveResult.warning] since the edit itself is safely in Immich by then.
 */
class SaveFlow(private val api: ImmichApi) {

    suspend fun run(
        targetId: String,
        /** The asset whose pixels were edited (differs from [targetId] when re-editing from the original). */
        renderSourceId: String,
        file: File,
        filename: String,
        recipe: Recipe,
        mode: SaveMode,
        group: Boolean,
        onProgress: (SaveStage, Float) -> Unit = { _, _ -> },
    ): SaveResult {
        val target = api.assetInfo(targetId)
        onProgress(SaveStage.UPLOADING, 0f)
        val size = file.length()
        val spec = UploadSpec(
            filename = filename,
            mime = "image/jpeg",
            size = size,
            checksum = sha1Hex(file),
            deviceAssetId = "photos-edit-$targetId-${file.lastModified()}",
            createdAt = target.fileCreatedAt,
            modifiedAt = target.fileModifiedAt,
            open = { file.inputStream() },
        )
        val uploaded = api.upload(spec) { sent -> onProgress(SaveStage.UPLOADING, if (size > 0) sent.toFloat() / size else 1f) }
        val newId = uploaded.id
        val duplicate = uploaded.status == "duplicate"
        if (newId == targetId) throw ImmichException("This edit is identical to the photo you opened, so there's nothing new to save.")

        val warnings = mutableListOf<String>()
        // Immich answers "duplicate" even when its copy of this file is in Trash (e.g. the same edit
        // saved before and undone). Bring it back, or a REPLACE would leave only trashed assets.
        var preexisting = false
        if (duplicate) {
            val existing = api.assetInfo(newId)
            if (existing.isTrashed) api.restoreFromTrash(listOf(newId)) else preexisting = true
        }
        onProgress(SaveStage.LINKING, 0f)
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
            onProgress(SaveStage.GROUPING, 0f)
            runCatching { api.createStack(listOf(newId, targetId)) }
                .onSuccess { grouped = true }
                .onFailure { warnings += "Saved, but it couldn't be grouped with the original (${ImmichApi.describe(it)})." }
        }
        if (replace && copied.isSuccess) {
            onProgress(SaveStage.TRASHING, 0f)
            runCatching { api.deleteAssets(listOf(targetId), force = false) }
                .onSuccess { trashed = true }
                .onFailure { warnings += "Saved, but the original couldn't be moved to Trash (${ImmichApi.describe(it)})." }
        }
        onProgress(SaveStage.RECIPE, 0f)
        runCatching { api.putMetadata(newId, RecipeMetadata.KEY, RecipeMetadata.encode(renderSourceId, recipe)) }
            .onFailure { warnings += "Saved, but the edit can't be reopened later (${ImmichApi.describe(it)})." }
        onProgress(SaveStage.DONE, 1f)
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

    /**
     * Undo for a REPLACE: bring the original back from Trash, then send the edit to Trash (unless
     * the "edit" was a photo the user already had, which the save didn't create).
     */
    suspend fun undoReplace(result: SaveResult) {
        api.restoreFromTrash(listOf(result.targetId))
        if (!result.preexisting) api.deleteAssets(listOf(result.newAssetId), force = false)
    }

    companion object {
        fun sha1Hex(file: File): String {
            val md = MessageDigest.getInstance("SHA-1")
            file.inputStream().use { input ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        /** `IMG_0001.HEIC` → `IMG_0001-edited.jpg` (without stacking up `-edited-edited`). */
        fun editedFilename(original: String): String {
            val base = original.substringBeforeLast('.', original).ifBlank { "photo" }
            return (if (base.endsWith("-edited")) base else "$base-edited") + ".jpg"
        }
    }
}

/** Reads a recipe metadata value's `sourceId` without decoding the recipe. */
internal fun JsonObject.recipeSourceId(): String? = this["sourceId"]?.jsonPrimitive?.contentOrNull
