package com.roylet.photos.editor.save

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.io.File

/*
 * Where an edit is saved (docs/SOURCES.md §9). The photo and video save managers render or export
 * the file, then hand it to one or two sinks: Immich (upload, copy albums, stack or trash: the
 * existing flows) and this phone (a MediaStore insert next to the original, then trash it for
 * Replace). A "Both" save runs the phone sink first, then Immich.
 */

/** Where the save sheet sends the edit. */
@Serializable
enum class SaveDestination(val label: String) {
    DEVICE("This device"),
    IMMICH("Immich"),
    BOTH("Both"),
    ;

    val local: Boolean get() = this != IMMICH
    val immich: Boolean get() = this != DEVICE

    companion object {
        /** The choices for an item on [onPhone] / [inImmich]; the first one is the default (where it lives). */
        fun choicesFor(onPhone: Boolean, inImmich: Boolean): List<SaveDestination> = when {
            onPhone && inImmich -> listOf(BOTH, DEVICE, IMMICH)
            onPhone -> listOf(DEVICE)
            else -> listOf(IMMICH)
        }
    }
}

/** A finished edit to save. */
data class SinkInput(
    /** The item the user opened (copied from, or replaced), in this sink's own id form. */
    val targetId: String,
    /** The item whose pixels were edited (the target, or its original when re-editing). */
    val renderSourceId: String,
    /** The rendered JPEG or exported MP4. */
    val file: File,
    /** e.g. `IMG_0001-edited.jpg`. */
    val filename: String,
    val mimeType: String,
    val isVideo: Boolean,
    /** [RecipeMetadata.KEY] or the video editor's key, and its value, for re-editing later. */
    val editKey: String,
    val editValue: JsonObject,
    val mode: SaveMode,
    val group: Boolean,
)

/** Where a sink is, mapped onto each editor's own stages for the progress UI. */
enum class SinkStage { WRITING, UPLOADING, LINKING, GROUPING, TRASHING, RECIPE, DONE }

/** One place an edit can be saved to. */
interface SaveSink {
    suspend fun save(input: SinkInput, onProgress: (SinkStage, Float) -> Unit = { _, _ -> }): SaveResult

    /** Undo for a REPLACE: the original back from Trash, the edit to Trash. */
    suspend fun undoReplace(result: SaveResult)
}

/** A save that can't go on (the original vanished, the phone refused the file); not worth retrying. */
class LocalSaveException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Joins a phone result and an Immich result of a "Both" save (the phone's is the primary). */
fun combineResults(local: SaveResult?, remote: SaveResult?): SaveResult = when {
    local != null && remote != null -> local.copy(companion = remote, warning = local.warning ?: remote.warning)
    local != null -> local
    remote != null -> remote
    else -> throw IllegalStateException("Nothing was saved")
}

/** What the save sheet asked for. */
data class SaveRequest(
    val targetId: String,
    val mode: SaveMode,
    val group: Boolean,
    val destination: SaveDestination,
    /** The Immich copy of a phone target (Both / Immich saves of a merged item). */
    val remoteTargetId: String? = null,
)

/**
 * Starts saves in the right order: a Replace on this phone needs the user's go-ahead to change
 * the original (a MediaStore consent dialog, skipped with MANAGE_MEDIA) *before* the job is
 * queued, because a background job can't show one. A "no" queues nothing.
 */
class SaveStarter(private val consent: suspend (localTargetId: String) -> Boolean) {
    /** Whether [request] needs [consent] first. */
    fun needsConsent(request: SaveRequest): Boolean =
        request.mode == SaveMode.REPLACE && request.destination.local && com.roylet.photos.domain.source.AssetRef.isLocal(request.targetId)

    /** Runs [enqueue] (returning its job id) unless the consent was refused; null then. */
    suspend fun start(request: SaveRequest, enqueue: (SaveRequest) -> String): String? {
        if (needsConsent(request) && !consent(request.targetId)) return null
        return enqueue(request)
    }
}
