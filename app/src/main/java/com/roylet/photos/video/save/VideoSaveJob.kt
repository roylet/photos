package com.roylet.photos.video.save

import com.roylet.photos.editor.save.SaveMode
import com.roylet.photos.editor.save.SaveResult
import com.roylet.photos.immich.ImmichApi
import com.roylet.photos.immich.ImmichException
import com.roylet.photos.immich.assetInfo
import com.roylet.photos.util.Format
import com.roylet.photos.video.export.CompositionPlanner
import com.roylet.photos.video.export.EncoderCaps
import com.roylet.photos.video.export.SourceInfo
import com.roylet.photos.video.export.VideoExportEngine
import com.roylet.photos.video.export.VideoExportException
import com.roylet.photos.video.io.VideoOriginals
import com.roylet.photos.video.model.VideoEdit
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException
import java.time.OffsetDateTime

/** Everything a video save needs, persisted so WorkManager can rerun it (retry, process death). */
@Serializable
data class VideoSaveJobSpec(
    val jobId: String,
    /** The asset the user opened: copied from, or replaced. */
    val targetId: String,
    /** The asset whose original is downloaded and edited (the target, or its original when re-editing). */
    val sourceId: String,
    val editJson: String,
    val mode: SaveMode,
    val group: Boolean,
)

/**
 * One save, start to finish: download the original (cached, shared with the editor) → export
 * with [engine] → upload → copy → stack | trash → metadata ([VideoSaveFlow]).
 *
 * Re-runnable: [workDir] keeps the exported MP4 and a marker once the export finished, so a retry
 * after a network failure goes straight back to the upload; another marker records that an upload
 * was attempted (see [VideoSaveFlow.run]'s `uploadAttemptedBefore`).
 */
class VideoSaveJob(
    private val api: ImmichApi,
    private val originals: VideoOriginals,
    private val engine: VideoExportEngine,
    private val workDir: File,
    private val probe: (File) -> SourceInfo,
    private val caps: (SourceInfo) -> EncoderCaps,
) {
    suspend fun run(spec: VideoSaveJobSpec, onProgress: (VideoSaveStage, Float) -> Unit): SaveResult {
        val edit = VideoEdit.fromJson(spec.editJson)
        if (edit.isIdentity) throw ImmichException("There are no changes to save.")
        val target = api.assetInfo(spec.targetId)
        val source = if (spec.sourceId == spec.targetId) target else api.assetInfo(spec.sourceId)
        if (!source.type.equals("VIDEO", ignoreCase = true)) throw ImmichException("Only videos can be saved with the video editor.")

        val filename = VideoSaveFlow.editedFilename(target.originalFileName)
        workDir.mkdirs()
        val out = File(workDir, filename)
        val exported = File(workDir, EXPORT_DONE)
        if (!(exported.isFile && out.isFile && out.length() > 0)) {
            exported.delete()
            onProgress(VideoSaveStage.DOWNLOADING, 0f)
            val original = originals.obtain(api, source) { done, total ->
                onProgress(VideoSaveStage.DOWNLOADING, if (total > 0) done.toFloat() / total else 0f)
            }
            onProgress(VideoSaveStage.EXPORTING, 0f)
            val info = probe(original)
            val plan = CompositionPlanner.plan(edit, info, caps(info), parseInstantMs(target.fileCreatedAt))
            val estimate = plan.bitrate.toLong() * plan.outputDurationMs / 8_000 * 11 / 10 + SPACE_MARGIN
            val free = originals.freeSpace()
            if (free < estimate) {
                throw VideoExportException("Not enough free space to export: about ${Format.bytes(estimate)} is needed and ${Format.bytes(free)} is free.")
            }
            val summary = engine.export(original, plan, out) { onProgress(VideoSaveStage.EXPORTING, it) }
            if (!out.isFile || out.length() == 0L) throw VideoExportException("The export produced no file.")
            exported.writeText(summary.warning.orEmpty())
        }
        val exportWarning = exported.readText().ifBlank { null }

        val uploadMarker = File(workDir, UPLOAD_STARTED)
        val attemptedBefore = uploadMarker.exists()
        uploadMarker.writeText("1")
        val result = VideoSaveFlow(api).run(target, source.id, out, filename, edit, spec.mode, spec.group, attemptedBefore, onProgress)
        return if (result.warning == null && exportWarning != null) result.copy(warning = exportWarning) else result
    }

    companion object {
        const val EXPORT_DONE = "export.done"
        const val UPLOAD_STARTED = "upload.started"
        const val SPACE_MARGIN = 64L * 1024 * 1024

        fun parseInstantMs(s: String?): Long? = s?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }

        /**
         * Worth retrying later (connection dropped, timeout, 5xx/429): plain I/O errors from the
         * network and retryable [ImmichException]s. Export failures and 4xx are permanent.
         */
        fun isTransient(e: Throwable): Boolean = when (e) {
            is ImmichException -> e.retryable
            is VideoExportException -> false
            is IOException -> true
            else -> false
        }
    }
}
