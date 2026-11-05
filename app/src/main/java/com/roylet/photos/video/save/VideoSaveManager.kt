package com.roylet.photos.video.save

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.roylet.photos.MainActivity
import com.roylet.photos.PhotosApp
import com.roylet.photos.R
import com.roylet.photos.editor.save.SaveMode
import com.roylet.photos.editor.save.SaveResult
import com.roylet.photos.immich.ImmichApi
import com.roylet.photos.immich.ImmichException
import com.roylet.photos.video.export.EncoderCaps
import com.roylet.photos.video.export.Media3VideoExporter
import com.roylet.photos.video.export.SourceInfo
import com.roylet.photos.video.export.SourceProbe
import com.roylet.photos.video.export.VideoExportEngine
import com.roylet.photos.video.io.VideoOriginals
import com.roylet.photos.video.model.VideoEdit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Live state of one video save, for the in-app progress panel. */
sealed interface VideoSaveProgress {
    data class Running(val stage: VideoSaveStage, val fraction: Float, val detail: String? = null) : VideoSaveProgress {
        /** One number for one progress bar. */
        val overall: Float get() = VideoSaveStage.overall(stage, fraction)
    }
    data class Done(val result: SaveResult) : VideoSaveProgress
    data class Failed(val message: String, val cancelled: Boolean = false) : VideoSaveProgress
}

/**
 * Starts and tracks video saves; mirrors the photo editor's `EditSaveManager`. Each save runs in
 * [VideoSaveWorker], a WorkManager job that needs a network connection and is promoted to a
 * foreground service (dataSync) with a progress notification, so a long 4K export finishes when
 * the app is backgrounded. Network failures are retried with exponential backoff (unlike photos);
 * the export is kept between attempts.
 *
 * For the UI:
 * - open a video: [originals]`.obtain(api, info)` downloads (or reuses) the local original that
 *   the preview, the frame strip and the save all share;
 * - save: [enqueue] → job id; observe [jobs] / [progress];
 * - after a REPLACE, [lastReplace] holds the result: show "Original moved to Trash · UNDO" and call
 *   [undoReplace] or [consumeLastReplace].
 */
class VideoSaveManager(
    context: Context,
    private val api: () -> ImmichApi?,
    /** Called once per finished save (from the worker's thread), e.g. to refresh cached timelines. */
    private val onSaved: (SaveResult) -> Unit = {},
    private val engine: (Context) -> VideoExportEngine = { Media3VideoExporter(it) },
    private val probe: (File) -> SourceInfo = SourceProbe::probe,
    private val caps: (SourceInfo) -> EncoderCaps = { SourceProbe.encoderCaps(it) },
) {
    private val app = context.applicationContext
    private val _jobs = MutableStateFlow<Map<String, VideoSaveProgress>>(emptyMap())
    private val _lastReplace = MutableStateFlow<SaveResult?>(null)
    private val cancelled: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    val jobs: StateFlow<Map<String, VideoSaveProgress>> = _jobs.asStateFlow()
    val lastReplace: StateFlow<SaveResult?> = _lastReplace.asStateFlow()

    /** Downloaded originals, shared by the editor screen and the save worker. Pruning spares those queued saves still need. */
    val originals = VideoOriginals(File(app.cacheDir, "video-edit"), pendingSourceIds = ::pendingSourceIds)

    fun progress(jobId: String): Flow<VideoSaveProgress?> = jobs.map { it[jobId] }

    /**
     * Queues a save of [edit] (made on [sourceId]'s original) for the asset [targetId]. Copy keeps
     * the original ([group] stacks the two); replace copies everything over and trashes it.
     */
    fun enqueue(targetId: String, sourceId: String = targetId, edit: VideoEdit, mode: SaveMode, group: Boolean): String {
        val jobId = UUID.randomUUID().toString()
        val spec = VideoSaveJobSpec(jobId, targetId, sourceId, edit.normalized().toJson(), mode, group)
        specFile(jobId).apply { parentFile?.mkdirs() }.writeText(ImmichApi.json.encodeToString(VideoSaveJobSpec.serializer(), spec))
        report(jobId, VideoSaveProgress.Running(VideoSaveStage.QUEUED, 0f))
        val request = OneTimeWorkRequestBuilder<VideoSaveWorker>()
            .setInputData(workDataOf(VideoSaveWorker.KEY_JOB_ID to jobId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(TAG)
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork(workName(jobId), ExistingWorkPolicy.KEEP, request)
        return jobId
    }

    /** Stops a save; its files are cleaned up and it reports as cancelled. Nothing on the server is touched. */
    fun cancel(jobId: String) {
        cancelled += jobId
        WorkManager.getInstance(app).cancelUniqueWork(workName(jobId))
        finish(jobId)
        report(jobId, VideoSaveProgress.Failed("Cancelled", cancelled = true))
    }

    /** Undo a REPLACE: restores the original from Trash and sends the edit to Trash. */
    suspend fun undoReplace(result: SaveResult) {
        val api = api() ?: throw ImmichException("Not signed in")
        VideoSaveFlow(api).undoReplace(result)
        _lastReplace.compareAndSet(result, null)
        runCatching { onSaved(result) }
    }

    internal fun isCancelled(jobId: String) = jobId in cancelled

    fun consumeLastReplace() { _lastReplace.value = null }

    /** Forget a finished job's state (after the UI has shown it). */
    fun clear(jobId: String) = _jobs.update { it - jobId }

    /** Source asset ids of saved-but-unfinished jobs that haven't exported yet (they still read the original). */
    internal fun pendingSourceIds(): Set<String> =
        File(app.filesDir, "video-save").listFiles { f -> f.name.endsWith(".json") }.orEmpty().mapNotNullTo(HashSet()) { f ->
            val jobId = f.name.removeSuffix(".json")
            if (File(workDir(jobId), VideoSaveJob.EXPORT_DONE).isFile) null else readSpec(jobId)?.sourceId
        }

    internal fun readSpec(jobId: String): VideoSaveJobSpec? = runCatching {
        ImmichApi.json.decodeFromString(VideoSaveJobSpec.serializer(), specFile(jobId).readText())
    }.getOrNull()

    internal fun report(jobId: String, progress: VideoSaveProgress) {
        _jobs.update { it + (jobId to progress) }
        if (progress is VideoSaveProgress.Done) {
            runCatching { onSaved(progress.result) }
            if (progress.result.anyOriginalTrashed) _lastReplace.value = progress.result
        }
    }

    /** Runs one attempt. Keeps the spec and work files when it throws, for a retry; see [finish]. */
    internal suspend fun run(jobId: String, onProgress: (VideoSaveStage, Float) -> Unit): SaveResult {
        val spec = readSpec(jobId) ?: throw ImmichException("This save was lost; please save again.")
        val api = api() ?: throw ImmichException("Not signed in")
        return VideoSaveJob(api, originals, engine(app), workDir(jobId), probe, caps).run(spec, onProgress)
    }

    /** Deletes a job's spec and work files (after success, a permanent failure or a cancel). */
    internal fun finish(jobId: String) {
        workDir(jobId).deleteRecursively()
        specFile(jobId).delete()
    }

    private fun specFile(jobId: String) = File(app.filesDir, "video-save/$jobId.json")
    private fun workDir(jobId: String) = File(app.cacheDir, "video-save/$jobId")

    companion object {
        const val TAG = "video-save"
        fun workName(jobId: String) = "video-save-$jobId"
    }
}

/**
 * Hosts a video save in a foreground service. Transient network errors → [Result.retry] (with the
 * request's exponential backoff and network constraint), up to [MAX_ATTEMPTS]; anything else fails
 * the job. The spec survives a stop by the system so WorkManager's rerun can continue.
 */
class VideoSaveWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val jobId get() = inputData.getString(KEY_JOB_ID).orEmpty()
    private val manager get() = (applicationContext as PhotosApp).container.videoEdits

    override suspend fun getForegroundInfo(): ForegroundInfo =
        VideoSaveNotifications.foregroundInfo(jobId, VideoSaveNotifications.progress(applicationContext, jobId, VideoSaveStage.QUEUED, 0f))

    override suspend fun doWork(): Result {
        if (jobId.isEmpty()) return Result.failure()
        if (manager.readSpec(jobId) == null) return Result.failure()
        runCatching { setForeground(getForegroundInfo()) }
        var lastPost = 0L
        return try {
            val result = manager.run(jobId) { stage, f ->
                // After a cancel the panel keeps saying so (unless the save completes anyway, see VideoSaveFlow).
                if (!manager.isCancelled(jobId)) manager.report(jobId, VideoSaveProgress.Running(stage, f))
                val now = System.currentTimeMillis()
                if (now - lastPost > 700) {
                    lastPost = now
                    VideoSaveNotifications.update(applicationContext, jobId, stage, f)
                }
            }
            manager.finish(jobId)
            manager.report(jobId, VideoSaveProgress.Done(result))
            VideoSaveNotifications.finished(applicationContext, jobId, result, null)
            Result.success(workDataOf(KEY_NEW_ID to result.newAssetId))
        } catch (e: CancellationException) {
            if (manager.isCancelled(jobId)) {
                manager.finish(jobId)
                VideoSaveNotifications.cancel(applicationContext, jobId)
            }
            throw e
        } catch (e: Exception) {
            if (manager.isCancelled(jobId)) {
                // A cancelled download/upload can surface as an I/O error rather than a CancellationException.
                manager.finish(jobId)
                VideoSaveNotifications.cancel(applicationContext, jobId)
                return Result.failure()
            }
            val message = ImmichApi.describe(e)
            if (VideoSaveJob.isTransient(e) && runAttemptCount + 1 < MAX_ATTEMPTS && !manager.isCancelled(jobId)) {
                manager.report(jobId, VideoSaveProgress.Running(VideoSaveStage.WAITING_FOR_NETWORK, 0f, message))
                VideoSaveNotifications.update(applicationContext, jobId, VideoSaveStage.WAITING_FOR_NETWORK, 0f)
                return Result.retry()
            }
            manager.finish(jobId)
            manager.report(jobId, VideoSaveProgress.Failed(message))
            VideoSaveNotifications.finished(applicationContext, jobId, null, message)
            Result.failure()
        }
    }

    companion object {
        const val KEY_JOB_ID = "jobId"
        const val KEY_NEW_ID = "newAssetId"
        const val MAX_ATTEMPTS = 6
    }
}

/** The video save notifications (own channel; a copy of the photo SaveNotifications with a Cancel action). */
object VideoSaveNotifications {
    private const val CHANNEL = "video-saves"

    private fun channel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Saving videos", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress while an edited video is exported and uploaded"
            },
        )
    }

    fun foregroundInfo(jobId: String, n: Notification): ForegroundInfo =
        ForegroundInfo(id(jobId), n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    fun progress(context: Context, jobId: String, stage: VideoSaveStage, fraction: Float): Notification {
        channel(context)
        val pct = (VideoSaveStage.overall(stage, fraction) * 100).toInt()
        val waiting = stage == VideoSaveStage.QUEUED || stage == VideoSaveStage.WAITING_FOR_NETWORK
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_edit)
            .setContentTitle("Saving video")
            .setContentText(stage.label)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setProgress(100, pct, waiting)
            .setSubText("$pct%")
            .setContentIntent(open(context))
            // Through the manager (not WorkManager's own cancel intent), so the job's files are
            // deleted and the editor's progress panel learns it was cancelled.
            .addAction(0, "Cancel", VideoSaveCancelReceiver.intent(context, jobId))
            .build()
    }

    fun update(context: Context, jobId: String, stage: VideoSaveStage, fraction: Float) {
        runCatching { context.getSystemService(NotificationManager::class.java).notify(id(jobId), progress(context, jobId, stage, fraction)) }
    }

    fun cancel(context: Context, jobId: String) {
        runCatching { context.getSystemService(NotificationManager::class.java).cancel(id(jobId)) }
    }

    fun finished(context: Context, jobId: String, result: SaveResult?, error: String?) {
        channel(context)
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.cancel(id(jobId))
        val title = when {
            error != null -> "Couldn't save video"
            result?.anyOriginalTrashed == true -> "Video saved · original in Trash"
            else -> "Video saved as a copy"
        }
        val text = error ?: result?.warning ?: "Your edited video is in Immich"
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_edit)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open(context))
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(id(jobId) + 1, n) }
    }

    private fun open(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 8,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun id(jobId: String) = 40_000 + (jobId.hashCode() and 0x7FFF) * 2
}

/** The progress notification's Cancel action: cancels the save through [VideoSaveManager.cancel]. */
class VideoSaveCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val jobId = intent.getStringExtra(VideoSaveWorker.KEY_JOB_ID) ?: return
        (context.applicationContext as PhotosApp).container.videoEdits.cancel(jobId)
        VideoSaveNotifications.cancel(context, jobId)
    }

    companion object {
        fun intent(context: Context, jobId: String): PendingIntent = PendingIntent.getBroadcast(
            context,
            jobId.hashCode(),
            Intent(context, VideoSaveCancelReceiver::class.java).putExtra(VideoSaveWorker.KEY_JOB_ID, jobId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
