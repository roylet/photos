package com.roylet.photos.local

import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.roylet.photos.PhotosApp
import com.roylet.photos.ui.permissions.MediaPermissions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.security.MessageDigest

/*
 * SHA-1 of every phone item, for matching it with its Immich copy (Immich's asset checksum is the
 * SHA-1 of the original file). Hashing reads every byte, so it runs in a low-priority WorkManager
 * job that stops when the battery runs low and resumes where it left off: each hash is stored as
 * soon as it's computed, with the generation it was computed at (see MediaDao.setSha1).
 */

/** Hashing progress: [done] of [total] live items have a current SHA-1. */
data class Sha1Progress(val done: Int, val total: Int) {
    val complete: Boolean get() = done >= total
}

/**
 * The hashing loop, free of WorkManager so it can be tested: take the newest [batchSize] items
 * without a current hash, stream each through SHA-1 and store it, until none are left or
 * [shouldStop] says so. Items that can't be read are skipped for the rest of this run (they're
 * retried next run); a hash whose item changed meanwhile isn't stored (setSha1 returns 0) and the
 * item comes round again at its new generation.
 */
class Sha1Hasher(
    private val dao: MediaDao,
    /** Opens an item's original bytes, or null when it can't be read. */
    private val open: (MediaEntity) -> InputStream?,
    private val batchSize: Int = 25,
    /** After each batch: the ids hashed in it (matching picks them up). */
    private val onBatch: suspend (List<Long>) -> Unit = {},
) {
    data class Run(val hashed: Int, val stale: Int, val failed: Int, val stopped: Boolean)

    suspend fun run(shouldStop: () -> Boolean = { false }, onProgress: suspend (Sha1Progress) -> Unit = {}): Run {
        val skipped = HashSet<Long>()
        var hashed = 0
        var stale = 0
        while (true) {
            if (shouldStop()) return Run(hashed, stale, skipped.size, stopped = true)
            val batch = dao.needingSha1(batchSize + skipped.size).filter { it.id !in skipped }.take(batchSize)
            if (batch.isEmpty()) return Run(hashed, stale, skipped.size, stopped = false)
            val done = ArrayList<Long>(batch.size)
            for (item in batch) {
                if (shouldStop()) break
                val hex = try {
                    withContext(Dispatchers.IO) { open(item)?.use(::sha1Hex) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Couldn't hash ${item.id}", e)
                    null
                }
                if (hex == null) { skipped += item.id; continue }
                if (dao.setSha1(item.id, hex, item.generation) > 0) { hashed++; done += item.id } else stale++
            }
            if (done.isNotEmpty()) onBatch(done)
            onProgress(dao.sha1Counts().let { Sha1Progress(it.done, it.total) })
        }
    }

    companion object {
        private const val TAG = "Sha1Hasher"

        /** Lower-case hex SHA-1 of everything in [input], read in 64 KiB chunks. */
        fun sha1Hex(input: InputStream): String {
            val md = MessageDigest.getInstance("SHA-1")
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * The WorkManager side of [Sha1Hasher]: one unique, low-priority job (`sha1-index`). It needs no
 * network; it requires the battery not to be low, so WorkManager stops it when it gets low and
 * starts it again later (hashes already stored are kept).
 */
class Sha1Worker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val device = (applicationContext as PhotosApp).container.device
        val run = device.hasher.run(shouldStop = { isStopped }) { device.publishMatching(it) }
        device.refreshMatching()
        return if (run.stopped) Result.retry() else Result.success()
    }

    companion object {
        const val NAME = "sha1-index"

        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<Sha1Worker>()
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            // KEEP: a running job already loops until nothing is left, new items included.
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) { WorkManager.getInstance(context).cancelUniqueWork(NAME) }

        /**
         * The original bytes, as Immich received them: with ACCESS_MEDIA_LOCATION granted,
         * `setRequireOriginal` stops MediaStore from redacting the GPS tags (which would change
         * the hash). Without it, photos with a location can't match until the user grants it.
         */
        fun openOriginal(context: Context, item: MediaEntity): InputStream? {
            var uri = mediaUri(item.id, item.isVideo)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && MediaPermissions.hasMediaLocation(context)) {
                uri = MediaStore.setRequireOriginal(uri)
            }
            return context.contentResolver.openInputStream(uri)
        }
    }
}
