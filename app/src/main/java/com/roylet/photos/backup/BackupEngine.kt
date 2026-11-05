package com.roylet.photos.backup

import android.util.Log
import com.roylet.photos.immich.BulkCheckItem
import com.roylet.photos.immich.ImmichApi
import com.roylet.photos.immich.ImmichException
import com.roylet.photos.immich.SourceChangedException
import com.roylet.photos.immich.UploadResult
import com.roylet.photos.immich.UploadSpec
import com.roylet.photos.immich.bulkUploadCheck
import com.roylet.photos.local.MatchDao
import com.roylet.photos.local.MatchEntity
import com.roylet.photos.local.MediaDao
import com.roylet.photos.local.MediaEntity
import com.roylet.photos.local.Sha1Hasher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.time.Instant

/** What Immich holds for one SHA-1. */
data class RemoteCopy(val assetId: String, val trashed: Boolean)

/** The two Immich calls backup makes; [ImmichBackupRemote] in the app, MockWebServer or a fake in tests. */
interface BackupRemote {
    /** SHA-1 hex → Immich's copy, for the hashes Immich already has (in its timeline or its Trash). */
    suspend fun check(hexes: List<String>): Map<String, RemoteCopy>
    suspend fun upload(spec: UploadSpec, onProgress: (Long) -> Unit): UploadResult
}

/** Backup through [api]: `bulk-upload-check` (batches of 500) and the streaming `POST /assets`, on the app's shared client. */
class ImmichBackupRemote(private val api: ImmichApi) : BackupRemote {
    override suspend fun check(hexes: List<String>): Map<String, RemoteCopy> {
        if (hexes.isEmpty()) return emptyMap()
        return api.bulkUploadCheck(hexes.map { BulkCheckItem(it, it) })
            .filter { it.action == "reject" && it.reason == "duplicate" && it.assetId != null }
            .associate { it.id to RemoteCopy(it.assetId!!, it.isTrashed) }
    }

    override suspend fun upload(spec: UploadSpec, onProgress: (Long) -> Unit): UploadResult = api.upload(spec, onProgress)
}

/** Fresh facts about one file, read from MediaStore just before it's uploaded. */
data class LocalFile(val size: Long, val modifiedAt: Long, val trashed: Boolean = false)

/** Which items a run covers. */
data class BackupSelection(val buckets: List<Long>, val includeVideos: Boolean, val since: Long = 0L) {
    companion object {
        fun of(buckets: Collection<Long>, options: BackupOptions) =
            BackupSelection(buckets.sorted(), options.includeVideos, options.newerThan ?: 0L)
    }
}

/** Progress from a run, for the notification and `backup.state`. */
sealed interface BackupProgress {
    data class Scanning(val remaining: Int) : BackupProgress
    data class Uploading(val current: Int, val total: Int, val localId: Long, val name: String?, val fraction: Float) : BackupProgress
}

/** How a run ended. */
sealed interface BackupRun {
    data class Done(val uploaded: Int, val alreadyThere: Int, val failed: Int, val excluded: Int) : BackupRun
    /** WorkManager stopped it (constraints, pause, cancel); everything done so far is kept. */
    data object Stopped : BackupRun
    /** A transient failure (network, 408/429/5xx): try again with backoff. */
    data class Retry(val message: String) : BackupRun
    /** Immich refused the key (401/403): pause until the user signs in again. */
    data class AuthFailed(val message: String) : BackupRun
}

/** Why an upload failed, for the retry rules. */
enum class FailureKind { Transient, Permanent, Auth }

/** A read from the phone's file failed (not the network). */
class LocalReadException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * The backup loop, free of WorkManager so it can be tested. Per batch of [batchSize] items still
 * missing from Immich (newest first, from [BackupMediaDao.pending], which never lists trashed items):
 *
 * 1. ensure the SHA-1 (hashed as the matcher's hasher does, and stored the same way);
 * 2. skip what Immich already has: `bulk-upload-check` for the batch. A copy in Immich's timeline is
 *    recorded as a match; one in Immich's Trash is excluded (the user deleted it there);
 * 3. stream the rest, one at a time, through [BackupRemote.upload] with the x-immich-checksum header;
 *    "created" and "duplicate" both count as done;
 * 4. record each result in the `immich_match` cache straight away, so the merged timeline shows the
 *    item as on both sides, and a restart resumes with the next item.
 *
 * Items in [BackupDao.exclusions] (by id or SHA-1) are never uploaded. Permanent per-item failures
 * (415, unreadable files) are stored and skipped until the item changes; transient ones end the
 * run with [BackupRun.Retry]; 401/403 with [BackupRun.AuthFailed]. Nothing is ever deleted.
 */
class BackupEngine(
    private val media: MediaDao,
    private val queue: BackupMediaDao,
    private val matches: MatchDao,
    private val store: BackupDao,
    private val remote: () -> BackupRemote?,
    /** The original bytes (as hashed: `setRequireOriginal` when allowed), or null when unreadable. */
    private val open: (MediaEntity) -> InputStream?,
    /** Size, DATE_MODIFIED and trash state now; null when the item is gone. */
    private val fileInfo: suspend (MediaEntity) -> LocalFile?,
    private val now: () -> Long = System::currentTimeMillis,
    private val batchSize: Int = 50,
    /** After uploads were recorded (the matcher and timelines refresh). */
    private val onRecorded: suspend (List<Long>) -> Unit = {},
) {
    suspend fun run(selection: BackupSelection, shouldStop: () -> Boolean = { false }, onProgress: (BackupProgress) -> Unit = {}): BackupRun {
        if (selection.buckets.isEmpty()) return BackupRun.Done(0, 0, 0, 0)
        val remote = remote() ?: return BackupRun.Retry("Not connected to Immich")
        val skip = HashSet<Long>()
        var uploaded = 0
        var already = 0
        var failed = 0
        var excludedNow = 0
        var processed = 0
        var total = -1
        while (true) {
            if (shouldStop()) return BackupRun.Stopped
            val exclusions = store.exclusions()
            val excludedIds = exclusions.mapTo(HashSet()) { it.localId }
            val excludedSha = exclusions.mapNotNullTo(HashSet()) { it.sha1 }
            val failures = store.failures().associate { it.localId to it.generation }
            fun skipped(e: MediaEntity) = e.id in skip || e.id in excludedIds || failures[e.id] == e.generation
            val extra = skip.size + excludedIds.size + failures.size
            val batch = queue.pending(selection.buckets, selection.includeVideos, selection.since, batchSize + extra)
                .filterNot(::skipped).take(batchSize)
            if (batch.isEmpty()) return BackupRun.Done(uploaded, already, failed, excludedNow)
            // "n of m": counted once per run (ids only); new items found on the way raise m.
            if (total < 0) {
                total = queue.pendingKeys(selection.buckets, selection.includeVideos, selection.since)
                    .count { it.id !in skip && it.id !in excludedIds && failures[it.id] != it.generation }
            }
            total = maxOf(total, processed + batch.size)
            onProgress(BackupProgress.Scanning(total - processed))

            // 1. Hashes.
            val ready = ArrayList<Pair<MediaEntity, String>>(batch.size)
            for (item in batch) {
                if (shouldStop()) return BackupRun.Stopped
                var sha = item.sha1?.takeIf { item.sha1Generation == item.generation }
                if (sha == null) {
                    sha = try {
                        withContext(Dispatchers.IO) { open(item)?.use { Sha1Hasher.sha1Hex(it, shouldStop) } }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Couldn't hash ${item.id}", e)
                        null
                    }
                    if (sha == null) {
                        if (shouldStop()) return BackupRun.Stopped
                        fail(item, "Couldn't read this file", null); failed++; processed++; skip += item.id
                        continue
                    }
                    // The item changed while it was read: it comes round again at its new version.
                    if (media.setSha1(item.id, sha, item.generation) == 0) { skip += item.id; continue }
                }
                if (sha in excludedSha) { skip += item.id; processed++; continue }
                ready += item to sha
            }

            // 2. What Immich already has.
            val answers = try {
                remote.check(ready.map { it.second }.distinct())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                when (classify(e)) {
                    FailureKind.Auth -> return BackupRun.AuthFailed(ImmichApi.describe(e))
                    FailureKind.Transient -> return BackupRun.Retry(ImmichApi.describe(e))
                    // An older server without the call: the upload's checksum header still dedupes.
                    FailureKind.Permanent -> emptyMap()
                }
            }
            val recorded = ArrayList<Long>()
            val toUpload = ArrayList<Pair<MediaEntity, String>>()
            for ((item, sha) in ready) {
                val copy = answers[sha]
                when {
                    copy == null -> toUpload += item to sha
                    copy.trashed -> {
                        // Deleted in Immich (it's in Immich's Trash): respect that, never re-upload.
                        exclude(item, sha, copy.assetId, BackupReasons.IN_IMMICH_TRASH)
                        excludedNow++; processed++; skip += item.id
                    }
                    else -> {
                        record(item, copy.assetId); recorded += item.id; already++; processed++
                    }
                }
            }
            if (recorded.isNotEmpty()) onRecorded(recorded.toList()).also { recorded.clear() }

            // 3. Uploads, one at a time.
            for ((item, sha) in toUpload) {
                if (shouldStop()) { if (recorded.isNotEmpty()) onRecorded(recorded); return BackupRun.Stopped }
                val file = fileInfo(item)
                if (file == null || file.trashed) { skip += item.id; continue } // gone, or moved to the phone's Trash since the last sync
                val current = processed + 1
                onProgress(BackupProgress.Uploading(current, total, item.id, item.displayName, 0f))
                val spec = UploadSpec(
                    filename = item.displayName?.takeIf { it.isNotBlank() } ?: fallbackName(item),
                    mime = item.mimeType ?: if (item.isVideo) "video/mp4" else "image/jpeg",
                    size = file.size,
                    checksum = sha,
                    deviceAssetId = null,
                    createdAt = iso(item.takenAt),
                    modifiedAt = iso(if (file.modifiedAt > 0) file.modifiedAt else item.takenAt),
                    isFavorite = item.favorite,
                    open = { LocalStream(open(item) ?: throw LocalReadException("Couldn't open ${item.displayName}")) },
                )
                var lastFraction = -1f
                try {
                    val result = withContext(Dispatchers.IO) {
                        remote.upload(spec) { sent ->
                            val f = if (file.size > 0) sent.toFloat() / file.size else 1f
                            if (f - lastFraction >= 0.01f || f >= 1f) {
                                lastFraction = f
                                onProgress(BackupProgress.Uploading(current, total, item.id, item.displayName, f))
                            }
                        }
                    }
                    record(item, result.id)
                    recorded += item.id
                    if (result.status == "duplicate") already++ else uploaded++
                    processed++
                } catch (e: CancellationException) {
                    if (recorded.isNotEmpty()) onRecorded(recorded)
                    throw e
                } catch (e: SourceChangedException) {
                    skip += item.id // still being written: next run
                } catch (e: LocalReadException) {
                    fail(item, "Couldn't read this file", null); failed++; processed++; skip += item.id
                } catch (e: Exception) {
                    when (classify(e)) {
                        FailureKind.Auth -> { if (recorded.isNotEmpty()) onRecorded(recorded); return BackupRun.AuthFailed(ImmichApi.describe(e)) }
                        FailureKind.Transient -> { if (recorded.isNotEmpty()) onRecorded(recorded); return BackupRun.Retry(ImmichApi.describe(e)) }
                        FailureKind.Permanent -> {
                            fail(item, ImmichApi.describe(e), (e as? ImmichException)?.status); failed++; processed++; skip += item.id
                        }
                    }
                }
            }
            if (recorded.isNotEmpty()) onRecorded(recorded.toList())
        }
    }

    /** The upload's Immich asset is this item's copy: confirmed (same SHA-1), at its current version. */
    private suspend fun record(item: MediaEntity, remoteId: String) {
        matches.upsert(listOf(MatchEntity(item.id, item.generation, remoteId, tentative = false, checkedAt = now())))
        store.clearFailures(listOf(item.id))
    }

    private suspend fun fail(item: MediaEntity, reason: String, status: Int?) {
        store.putFailure(BackupFailureEntity(item.id, item.generation, item.displayName, item.bucketId, reason, status, now()))
    }

    private suspend fun exclude(item: MediaEntity, sha: String, remoteId: String?, reason: String) {
        store.putExclusions(listOf(BackupExclusionEntity(item.id, sha, remoteId, item.displayName, item.bucketId, reason, now())))
    }

    /** Wraps read errors from the phone's file so they aren't mistaken for network trouble. */
    private class LocalStream(input: InputStream) : FilterInputStream(input) {
        override fun read(): Int = try { super.read() } catch (e: IOException) { throw LocalReadException("Couldn't read the file", e) }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            try { super.read(b, off, len) } catch (e: IOException) { throw LocalReadException("Couldn't read the file", e) }
    }

    companion object {
        private const val TAG = "BackupEngine"

        fun iso(ms: Long): String = Instant.ofEpochMilli(ms).toString()

        private fun fallbackName(item: MediaEntity) = (if (item.isVideo) "VID_" else "IMG_") + item.id + (if (item.isVideo) ".mp4" else ".jpg")

        /** 401/403 → sign in again; 408/429/5xx and network errors → retry later; other 4xx → this item, for good. */
        fun classify(e: Throwable): FailureKind = when (e) {
            is ImmichException -> when {
                e.status == 401 || e.status == 403 -> FailureKind.Auth
                e.retryable || e.status == null -> FailureKind.Transient
                else -> FailureKind.Permanent
            }
            is LocalReadException -> FailureKind.Permanent
            is IOException -> FailureKind.Transient
            is kotlinx.serialization.SerializationException -> FailureKind.Transient
            else -> FailureKind.Permanent
        }
    }
}
