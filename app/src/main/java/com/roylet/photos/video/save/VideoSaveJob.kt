package com.roylet.photos.video.save

import com.roylet.photos.editor.save.SaveMode
import com.roylet.photos.editor.save.SaveResult
import com.roylet.photos.editor.save.EditSource
import com.roylet.photos.editor.save.ImmichEditSource
import com.roylet.photos.editor.save.LocalSaveException
import com.roylet.photos.editor.save.SaveDestination
import com.roylet.photos.editor.save.SaveSink
import com.roylet.photos.editor.save.SinkInput
import com.roylet.photos.editor.save.SinkStage
import com.roylet.photos.editor.save.combineResults
import com.roylet.photos.local.LocalSaveCheckpoint
import com.roylet.photos.immich.EditAssetInfo
import com.roylet.photos.immich.ImmichApi
import com.roylet.photos.immich.ImmichException
import com.roylet.photos.immich.assetInfo
import com.roylet.photos.util.Format
import com.roylet.photos.video.export.CompositionPlanner
import com.roylet.photos.video.export.EncoderCaps
import com.roylet.photos.video.export.ExportPlan
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
    /** This phone, Immich or both (docs/SOURCES.md §9). */
    val destination: SaveDestination = SaveDestination.IMMICH,
    /** The Immich copy of a phone [targetId], for a save to Immich. */
    val remoteTargetId: String? = null,
    /** The phone half's progress (rewritten as it goes). */
    val localCheckpoint: LocalSaveCheckpoint = LocalSaveCheckpoint(),
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
    /** Null when the save doesn't go to Immich. */
    private val api: ImmichApi?,
    private val originals: VideoOriginals,
    private val engine: VideoExportEngine,
    private val workDir: File,
    private val probe: (File) -> SourceInfo,
    private val caps: (SourceInfo) -> EncoderCaps,
    /** Where the original comes from (Immich or this phone). */
    private val sources: EditSource = ImmichEditSource { api },
    /** The phone's sink for this job, resuming from its checkpoint. */
    private val localSink: ((LocalSaveCheckpoint, (LocalSaveCheckpoint) -> Unit) -> SaveSink)? = null,
    /** Persists the spec when the phone half's checkpoint moves. */
    private val onSpec: (VideoSaveJobSpec) -> Unit = {},
) {
    suspend fun run(spec0: VideoSaveJobSpec, onProgress: (VideoSaveStage, Float) -> Unit): SaveResult {
        var spec = spec0
        val edit = VideoEdit.fromJson(spec.editJson)
        if (edit.isIdentity) throw ImmichException("There are no changes to save.")
        val target = sources.info(spec.targetId)
        val source = if (spec.sourceId == spec.targetId) target else sources.info(spec.sourceId)
        if (!source.type.equals("VIDEO", ignoreCase = true)) throw ImmichException("Only videos can be saved with the video editor.")

        val filename = VideoSaveFlow.editedFilename(target.originalFileName)
        workDir.mkdirs()
        val out = File(workDir, filename)
        val exported = File(workDir, EXPORT_DONE)
        if (!(exported.isFile && out.isFile && out.length() > 0)) {
            exported.delete()
            onProgress(VideoSaveStage.DOWNLOADING, 0f)
            val original = originals.obtain(sources, source) { done, total ->
                onProgress(VideoSaveStage.DOWNLOADING, if (total > 0) done.toFloat() / total else 0f)
            }
            // The editor opening another video mustn't prune this original mid-export.
            originals.hold(original).use { export(edit, target, original, out, exported, onProgress) }
        }
        val exportWarning = exported.readText().ifBlank { null }

        // The phone first (no network), then Immich.
        val local = if (spec.destination.local) {
            val sink = (localSink ?: throw LocalSaveException("Saving on this phone isn't available."))(spec.localCheckpoint) { cp ->
                spec = spec.copy(localCheckpoint = cp); onSpec(spec)
            }
            sink.save(
                SinkInput(spec.targetId, source.id, out, filename, "video/mp4", isVideo = true,
                    VideoEditMetadata.KEY, VideoEditMetadata.encode(source.id, edit), spec.mode, spec.group),
            ) { stage, f -> onProgress(stage.toVideoStage(), f) }
        } else null
        val remote = if (spec.destination.immich) {
            val api = api ?: throw ImmichException("Not signed in")
            val remoteTarget = if (spec.remoteTargetId != null || com.roylet.photos.domain.source.AssetRef.isLocal(target.id)) {
                api.assetInfo(spec.remoteTargetId ?: throw ImmichException("This video isn't in Immich."))
            } else target
            // A phone item's edit points at its Immich copy there (Immich can't open `local:` ids).
            val renderSource = if (com.roylet.photos.domain.source.AssetRef.isLocal(source.id)) remoteTarget.id else source.id
            val uploadMarker = File(workDir, UPLOAD_STARTED)
            val attemptedBefore = uploadMarker.exists()
            uploadMarker.writeText("1")
            VideoSaveFlow(api).run(remoteTarget, renderSource, out, filename, edit, spec.mode, spec.group, attemptedBefore, onProgress)
        } else null
        val result = combineResults(local, remote)
        return if (result.warning == null && exportWarning != null) result.copy(warning = exportWarning) else result
    }

    private suspend fun export(
        edit: VideoEdit,
        target: EditAssetInfo,
        original: File,
        out: File,
        exported: File,
        onProgress: (VideoSaveStage, Float) -> Unit,
    ) {
        onProgress(VideoSaveStage.EXPORTING, 0f)
        val info = probe(original)
        val plan = CompositionPlanner.plan(edit, info, caps(info), parseInstantMs(target.fileCreatedAt))
        val estimate = exportSpaceEstimate(plan, info)
        val free = originals.freeSpace()
        if (free < estimate) {
            throw VideoExportException("Not enough free space to export: about ${Format.bytes(estimate)} is needed and ${Format.bytes(free)} is free.")
        }
        val summary = engine.export(original, plan, out) { onProgress(VideoSaveStage.EXPORTING, it) }
        if (!out.isFile || out.length() == 0L) throw VideoExportException("The export produced no file.")
        exported.writeText(summary.warning.orEmpty())
    }

    companion object {
        /** A sink's stage as the video editor shows it. */
        fun SinkStage.toVideoStage(): VideoSaveStage = when (this) {
            SinkStage.WRITING, SinkStage.UPLOADING -> VideoSaveStage.UPLOADING
            SinkStage.LINKING -> VideoSaveStage.LINKING
            SinkStage.GROUPING -> VideoSaveStage.GROUPING
            SinkStage.TRASHING -> VideoSaveStage.TRASHING
            SinkStage.RECIPE -> VideoSaveStage.RECIPE
            SinkStage.DONE -> VideoSaveStage.DONE
        }

        const val EXPORT_DONE = "export.done"
        const val UPLOAD_STARTED = "upload.started"
        const val SPACE_MARGIN = 64L * 1024 * 1024

        /**
         * Bytes the exported MP4 may take, plus a margin. A trim-only plan copies most of the
         * stream as it is, so it is as large as the source's bitrate says, not the plan's.
         */
        fun exportSpaceEstimate(plan: ExportPlan, source: SourceInfo): Long {
            val bps = if (plan.trimOptimization) maxOf(plan.bitrate, source.bitrate ?: 0) else plan.bitrate
            return bps.toLong() * plan.outputDurationMs / 8_000 * 11 / 10 + SPACE_MARGIN
        }

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
