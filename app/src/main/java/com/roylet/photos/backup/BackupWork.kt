package com.roylet.photos.backup

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.roylet.photos.MainActivity
import com.roylet.photos.PhotosApp
import com.roylet.photos.R
import com.roylet.photos.local.MediaEntity
import com.roylet.photos.upload.NetworkMonitor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/** WorkManager constraints for [this] options: Wi-Fi only → UNMETERED, charging when asked, and never on a low battery. */
fun BackupOptions.constraints(): Constraints = Constraints.Builder()
    .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
    .setRequiresCharging(whileChargingOnly)
    .setRequiresBatteryNotLow(true)
    .build()

/**
 * The jobs: `backup-periodic` (about hourly), `backup-run` (one-off: "Back up now", new items in a
 * selected folder) and `backup-trigger` (wakes on any MediaStore change while the app is closed).
 * All use [BackupWorker] except the trigger.
 */
class WorkBackupScheduler(context: Context) : BackupScheduler {
    private val app = context.applicationContext
    private val wm get() = WorkManager.getInstance(app)

    override fun schedule(options: BackupOptions) = schedule(options, initialDelayMinutes = 0)

    private fun schedule(options: BackupOptions, initialDelayMinutes: Long) {
        val periodic = PeriodicWorkRequestBuilder<BackupWorker>(PERIOD_MINUTES, TimeUnit.MINUTES)
            .setConstraints(options.constraints())
            .setInitialDelay(initialDelayMinutes, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, periodic)
        BackupTriggerWorker.arm(app)
    }

    override fun runNow(options: BackupOptions) {
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(options.constraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        // KEEP: a running job loops until nothing is left, new items included.
        wm.enqueueUniqueWork(ONE_SHOT, ExistingWorkPolicy.KEEP, request)
    }

    override fun stopRunning(options: BackupOptions) {
        wm.cancelAllWorkByTag(TAG)
        // The hourly job comes back, an hour from now (not straight away, which would undo the cancel).
        schedule(options, initialDelayMinutes = PERIOD_MINUTES)
    }

    override fun cancelAll() {
        wm.cancelAllWorkByTag(TAG)
        wm.cancelUniqueWork(BackupTriggerWorker.NAME)
    }

    companion object {
        const val PERIODIC = "backup-periodic"
        const val ONE_SHOT = "backup-run"
        const val TAG = "backup"
        const val PERIOD_MINUTES = 60L
        const val BACKOFF_SECONDS = 30L
    }
}

/**
 * One backup run: re-reads the index (new photos, trash), then [BackupEngine.run]. It's promoted to
 * a foreground service with a progress notification while there's something to upload; if Android
 * refuses that (a background start on 12+), it runs as normal work and resumes when stopped.
 */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val container get() = (applicationContext as PhotosApp).container

    override suspend fun getForegroundInfo(): ForegroundInfo = BackupNotifications.foregroundInfo(applicationContext, BackupNotifications.progress(applicationContext, null))

    override suspend fun doWork(): Result {
        val backup = container.backup
        if (!backup.shouldRun()) return Result.success()
        // One run at a time (the hourly job and a one-off may both start): the running one loops until nothing is left.
        if (!backup.runLock.tryLock()) return Result.success()
        try {
            return runLocked(backup)
        } finally {
            backup.runLock.unlock()
        }
    }

    private suspend fun runLocked(backup: BackupManager): Result {
        runCatching { container.device.indexer.sync() }.onFailure { Log.w(TAG, "Index sync failed", it) }
        val selection = backup.selection()
        backup.reportStarted()
        var foreground = false
        var lastPost = 0L
        val result = try {
            container.backupEngine.run(selection, shouldStop = { isStopped || !backup.shouldRun() }) { p ->
                backup.report(p)
                if (p is BackupProgress.Uploading) {
                    if (!foreground) {
                        foreground = true
                        // From a coroutine: setForeground suspends; a refusal just keeps it as normal work.
                        container.appScope.launch { runCatching { setForeground(getForegroundInfo()) } }
                    }
                    val t = System.currentTimeMillis()
                    if (t - lastPost > 700 || p.fraction >= 1f) {
                        lastPost = t
                        BackupNotifications.update(applicationContext, p)
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            backup.reportFinished(BackupRun.Stopped)
            BackupNotifications.clearProgress(applicationContext)
            throw e
        }
        backup.reportFinished(result)
        BackupNotifications.clearProgress(applicationContext)
        return when (result) {
            is BackupRun.Done -> Result.success()
            BackupRun.Stopped -> Result.retry()
            is BackupRun.Retry -> if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
            is BackupRun.AuthFailed -> { BackupNotifications.signIn(applicationContext, result.message); Result.success() }
        }
    }

    companion object {
        private const val TAG = "BackupWorker"
        /** Transient failures retried with backoff (30 s, 1 min, 2 min, ...) before waiting for the next hourly run. */
        const val MAX_ATTEMPTS = 8
    }
}

/**
 * Re-armed after every MediaStore change: WorkManager's content-URI trigger fires even while the
 * app is closed, and starts a backup run (its worker syncs the index first).
 */
class BackupTriggerWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val backup = (applicationContext as PhotosApp).container.backup
        if (backup.shouldRun()) {
            backup.onIndexChanged()
            arm(applicationContext)
        }
        return Result.success()
    }

    companion object {
        const val NAME = "backup-trigger"

        fun arm(context: Context) {
            val constraints = Constraints.Builder()
                .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
                .addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
                .setTriggerContentUpdateDelay(10, TimeUnit.SECONDS)
                .setTriggerContentMaxDelay(2, TimeUnit.MINUTES)
                .build()
            val request = OneTimeWorkRequestBuilder<BackupTriggerWorker>().setConstraints(constraints).build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}

/** Wi-Fi from the app's [NetworkMonitor]; charging from the battery broadcasts. */
class AndroidBackupConditions(context: Context, network: NetworkMonitor) : BackupConditions {
    private val app = context.applicationContext
    private val _charging = MutableStateFlow(isCharging())

    init {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { _charging.value = i.action == Intent.ACTION_POWER_CONNECTED }
        }
        // System broadcasts arrive either way; nothing else may send these to the app.
        androidx.core.content.ContextCompat.registerReceiver(app, receiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override val unmetered: Flow<Boolean> = network.status.map { it.online && it.unmetered }
    override val charging: Flow<Boolean> = _charging

    private fun isCharging(): Boolean = runCatching { app.getSystemService(BatteryManager::class.java).isCharging }.getOrDefault(false)
}

/** Size, DATE_MODIFIED and IS_TRASHED of one phone item, from MediaStore right now. */
object AndroidBackupFiles {
    fun info(context: Context, item: MediaEntity): LocalFile? {
        val uri = ContentUris.withAppendedId(
            if (item.isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, item.id,
        )
        val cols = buildList {
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.DATE_MODIFIED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) add(MediaStore.MediaColumns.IS_TRASHED)
        }.toTypedArray()
        val args = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) android.os.Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
        } else null
        return runCatching {
            context.contentResolver.query(uri, cols, args, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                var size = c.getLong(0)
                if (size <= 0) size = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1
                if (size <= 0) return@use null
                LocalFile(
                    size = size,
                    modifiedAt = c.getLong(1) * 1000,
                    trashed = cols.size > 2 && c.getInt(2) != 0,
                )
            }
        }.getOrNull()
    }
}

object BackupNotifications {
    private const val CHANNEL = "backup"
    private const val PROGRESS_ID = 31_000
    private const val SIGN_IN_ID = 31_001
    const val ACTION_PAUSE = "com.roylet.photos.backup.PAUSE"
    const val ACTION_CANCEL = "com.roylet.photos.backup.CANCEL"

    private fun channel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Backup", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress while photos are backed up to Immich"
            },
        )
    }

    fun foregroundInfo(context: Context, n: Notification): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(PROGRESS_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(PROGRESS_ID, n)

    /** "Backing up 3 of 120", with Pause and Cancel. */
    fun progress(context: Context, p: BackupProgress.Uploading?): Notification {
        channel(context)
        val b = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_backup)
            .setContentTitle(if (p == null) "Backing up" else "Backing up ${p.current} of ${p.total}")
            .setContentText(p?.name ?: "Getting ready")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(open(context))
            .addAction(0, "Pause", action(context, ACTION_PAUSE))
            .addAction(0, "Cancel", action(context, ACTION_CANCEL))
        if (p == null) b.setProgress(0, 0, true)
        else b.setProgress(p.total.coerceAtLeast(1) * 100, ((p.current - 1) * 100 + (p.fraction * 100).toInt()).coerceAtLeast(0), false)
        return b.build()
    }

    fun update(context: Context, p: BackupProgress.Uploading) {
        runCatching { context.getSystemService(NotificationManager::class.java).notify(PROGRESS_ID, progress(context, p)) }
    }

    fun clearProgress(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java).cancel(PROGRESS_ID) }
    }

    /** Immich refused the key: backup is paused until the user signs in again. */
    fun signIn(context: Context, message: String) {
        channel(context)
        val text = "Backup is paused. Sign in to Immich again from Settings. ($message)"
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_backup)
            .setContentTitle("Sign in to keep backing up")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open(context))
            .setAutoCancel(true)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(SIGN_IN_ID, n) }
    }

    private fun action(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
        context, action.hashCode(),
        Intent(context, BackupActionReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun open(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 8,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** The progress notification's Pause and Cancel. */
class BackupActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val backup = (context.applicationContext as PhotosApp).container.backup
        when (intent.action) {
            BackupNotifications.ACTION_PAUSE -> backup.pause()
            BackupNotifications.ACTION_CANCEL -> backup.cancelRun()
        }
        BackupNotifications.clearProgress(context)
    }
}
