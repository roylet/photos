package com.roylet.photos.editor.save

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.roylet.photos.MainActivity
import com.roylet.photos.PhotosApp
import com.roylet.photos.R
import com.roylet.photos.editor.io.EditExporter
import com.roylet.photos.editor.io.SourceLoader
import com.roylet.photos.editor.recipe.Recipe
import com.roylet.photos.immich.ImmichApi
import com.roylet.photos.immich.ImmichException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

/** Everything a save job needs, persisted to a file so WorkManager can rerun it after process death. */
@Serializable
data class SaveJobSpec(
    val jobId: String,
    val targetId: String,
    val renderSourceId: String,
    /** The downloaded original the recipe applies to. */
    val sourcePath: String,
    val filename: String,
    val recipeJson: String,
    val mode: SaveMode,
    val group: Boolean,
)

/** Live state of one save, for the in-app progress panel. */
sealed interface SaveProgress {
    data class Running(val stage: SaveStage, val fraction: Float) : SaveProgress
    data class Done(val result: SaveResult) : SaveProgress
    data class Failed(val message: String) : SaveProgress
}

/**
 * Starts and tracks edit saves. Each save runs in [EditSaveWorker], an expedited WorkManager job
 * promoted to a foreground service with a progress notification, so it finishes even if the user
 * leaves the editor or the app.
 *
 * For the viewer: after a REPLACE, [lastReplace] holds the result; show
 * "Original moved to Trash · UNDO" and call [undoReplace] on tap or [consumeLastReplace] when the
 * snackbar is dismissed.
 */
class EditSaveManager(context: Context, private val api: () -> ImmichApi?) {
    private val app = context.applicationContext
    private val _jobs = MutableStateFlow<Map<String, SaveProgress>>(emptyMap())
    private val _lastReplace = MutableStateFlow<SaveResult?>(null)

    val jobs: StateFlow<Map<String, SaveProgress>> = _jobs.asStateFlow()
    val lastReplace: StateFlow<SaveResult?> = _lastReplace.asStateFlow()

    fun progress(jobId: String): Flow<SaveProgress?> = jobs.map { it[jobId] }

    fun enqueue(
        targetId: String,
        renderSourceId: String,
        source: File,
        originalFilename: String,
        recipe: Recipe,
        mode: SaveMode,
        group: Boolean,
    ): String {
        val jobId = UUID.randomUUID().toString()
        val spec = SaveJobSpec(jobId, targetId, renderSourceId, source.path, SaveFlow.editedFilename(originalFilename),
            recipe.toJson(), mode, group)
        specFile(jobId).apply { parentFile?.mkdirs() }.writeText(ImmichApi.json.encodeToString(SaveJobSpec.serializer(), spec))
        report(jobId, SaveProgress.Running(SaveStage.QUEUED, 0f))
        val request = OneTimeWorkRequestBuilder<EditSaveWorker>()
            .setInputData(workDataOf(EditSaveWorker.KEY_JOB_ID to jobId))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag("edit-save")
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork("edit-save-$jobId", ExistingWorkPolicy.KEEP, request)
        return jobId
    }

    /** Undo a REPLACE: restores the original from Trash and sends the edit to Trash. */
    suspend fun undoReplace(result: SaveResult) {
        val api = api() ?: throw ImmichException("Not signed in")
        SaveFlow(api).undoReplace(result)
        _lastReplace.compareAndSet(result, null)
    }

    fun consumeLastReplace() { _lastReplace.value = null }

    /** Forget a finished job's state (after the UI has shown it). */
    fun clear(jobId: String) = _jobs.update { it - jobId }

    internal fun readSpec(jobId: String): SaveJobSpec? = runCatching {
        ImmichApi.json.decodeFromString(SaveJobSpec.serializer(), specFile(jobId).readText())
    }.getOrNull()

    internal fun report(jobId: String, progress: SaveProgress) {
        _jobs.update { it + (jobId to progress) }
        if (progress is SaveProgress.Done && progress.result.originalTrashed) _lastReplace.value = progress.result
    }

    /** Runs one job: render, then [SaveFlow]. Called by the worker. */
    internal suspend fun run(jobId: String, onProgress: (SaveStage, Float) -> Unit): SaveResult {
        val spec = readSpec(jobId) ?: throw ImmichException("This save was lost; please save again.")
        val api = api() ?: throw ImmichException("Not signed in")
        val sourceFile = File(spec.sourcePath)
        if (!sourceFile.exists()) throw ImmichException("The downloaded original was cleared from the cache; open the editor again.")
        onProgress(SaveStage.RENDERING, 0f)
        val out = File(app.cacheDir, "edit-save/$jobId/${spec.filename}")
        val recipe = Recipe.fromJson(spec.recipeJson)
        try {
            val source = SourceLoader.load(sourceFile, proxyLongEdge = 1024)
            try {
                EditExporter.export(source, recipe, out)
            } finally {
                source.proxy.recycle()
            }
            onProgress(SaveStage.RENDERING, 1f)
            return SaveFlow(api).run(spec.targetId, spec.renderSourceId, out, spec.filename, recipe, spec.mode, spec.group, onProgress)
        } finally {
            out.delete()
            specFile(jobId).delete()
        }
    }

    private fun specFile(jobId: String) = File(app.filesDir, "edit-save/$jobId.json")

    companion object {
        /** Overall progress across stages, for one progress bar. */
        fun overall(stage: SaveStage, fraction: Float): Float = when (stage) {
            SaveStage.QUEUED -> 0f
            SaveStage.RENDERING -> 0.25f * fraction
            SaveStage.UPLOADING -> 0.25f + 0.6f * fraction
            SaveStage.LINKING -> 0.87f
            SaveStage.GROUPING, SaveStage.TRASHING -> 0.93f
            SaveStage.RECIPE -> 0.97f
            SaveStage.DONE -> 1f
        }
    }
}

/** Hosts a save in a foreground service (immichup's UploadWorker pattern). */
class EditSaveWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val jobId get() = inputData.getString(KEY_JOB_ID).orEmpty()
    private val manager get() = (applicationContext as PhotosApp).container.edits

    override suspend fun getForegroundInfo(): ForegroundInfo =
        SaveNotifications.foregroundInfo(applicationContext, jobId, SaveNotifications.progress(applicationContext, SaveStage.QUEUED, 0f))

    override suspend fun doWork(): Result {
        if (jobId.isEmpty()) return Result.failure()
        runCatching { setForeground(getForegroundInfo()) }
        var lastPost = 0L
        return try {
            val result = manager.run(jobId) { stage, f ->
                manager.report(jobId, SaveProgress.Running(stage, f))
                val now = System.currentTimeMillis()
                if (now - lastPost > 700) {
                    lastPost = now
                    SaveNotifications.update(applicationContext, jobId, stage, f)
                }
            }
            manager.report(jobId, SaveProgress.Done(result))
            SaveNotifications.finished(applicationContext, jobId, result, null)
            Result.success(workDataOf(KEY_NEW_ID to result.newAssetId))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            val message = ImmichApi.describe(e)
            manager.report(jobId, SaveProgress.Failed(message))
            SaveNotifications.finished(applicationContext, jobId, null, message)
            Result.failure()
        }
    }

    companion object {
        const val KEY_JOB_ID = "jobId"
        const val KEY_NEW_ID = "newAssetId"
    }
}

object SaveNotifications {
    private const val CHANNEL = "edit-saves"

    private fun channel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Saving edits", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress while an edited photo is rendered and uploaded"
            },
        )
    }

    fun foregroundInfo(context: Context, jobId: String, n: Notification): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(id(jobId), n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(id(jobId), n)

    fun progress(context: Context, stage: SaveStage, fraction: Float): Notification {
        channel(context)
        val pct = (EditSaveManager.overall(stage, fraction) * 100).toInt()
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_edit)
            .setContentTitle("Saving edit")
            .setContentText(stage.label)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setProgress(100, pct, stage == SaveStage.QUEUED)
            .setSubText("$pct%")
            .setContentIntent(open(context))
            .build()
    }

    fun update(context: Context, jobId: String, stage: SaveStage, fraction: Float) {
        runCatching { context.getSystemService(NotificationManager::class.java).notify(id(jobId), progress(context, stage, fraction)) }
    }

    fun finished(context: Context, jobId: String, result: SaveResult?, error: String?) {
        channel(context)
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.cancel(id(jobId))
        val title = when {
            error != null -> "Couldn't save edit"
            result?.originalTrashed == true -> "Edit saved · original in Trash"
            else -> "Edit saved as a copy"
        }
        val text = error ?: result?.warning ?: "Your edited photo is in Immich"
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
        context, 7,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun id(jobId: String) = 20_000 + (jobId.hashCode() and 0x7FFF) * 2
}
