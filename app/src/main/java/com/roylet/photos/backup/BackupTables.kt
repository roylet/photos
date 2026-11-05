package com.roylet.photos.backup

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import com.roylet.photos.local.FolderRow
import kotlinx.coroutines.flow.Flow

/*
 * Backup's own rows. The user's choices and what can't be recomputed (selected folders, failures,
 * "never back this up again") live in the app database (`photos-app.db`, v2); what's already in
 * Immich is the matcher's cache (`immich_match` in the index), which an upload writes to.
 */

/** A folder the user switched on or off. No row: the default (Camera on, everything else off). */
@Entity(tableName = "backup_folder")
data class BackupFolderEntity(@PrimaryKey val bucketId: Long, val enabled: Boolean, val changedAt: Long)

/** An item that failed for good (415, unreadable) at [generation]; a new version is tried again. */
@Entity(tableName = "backup_failure")
data class BackupFailureEntity(
    @PrimaryKey val localId: Long,
    val generation: Long,
    val displayName: String?,
    val bucketId: Long?,
    val reason: String,
    /** HTTP status, when Immich answered. */
    val status: Int?,
    val failedAt: Long,
)

/**
 * A phone item whose Immich copy the user deleted: backup never uploads it again (matched by
 * [localId] or by [sha1], should the file come back under another id). Removed when the Immich
 * copy is restored, or with "Allow backup again".
 */
@Entity(tableName = "backup_exclusion", indices = [Index(value = ["remoteId"]), Index(value = ["sha1"])])
data class BackupExclusionEntity(
    @PrimaryKey val localId: Long,
    val sha1: String?,
    val remoteId: String?,
    val displayName: String?,
    val bucketId: Long?,
    val reason: String,
    val excludedAt: Long,
)

@Dao
interface BackupDao {
    @Query("SELECT * FROM backup_folder")
    fun foldersFlow(): Flow<List<BackupFolderEntity>>

    @Query("SELECT * FROM backup_folder")
    suspend fun folders(): List<BackupFolderEntity>

    @Upsert
    suspend fun putFolder(row: BackupFolderEntity)

    @Query("SELECT * FROM backup_failure ORDER BY failedAt DESC")
    fun failuresFlow(): Flow<List<BackupFailureEntity>>

    @Query("SELECT * FROM backup_failure")
    suspend fun failures(): List<BackupFailureEntity>

    @Upsert
    suspend fun putFailure(row: BackupFailureEntity)

    @Query("DELETE FROM backup_failure WHERE localId IN (:ids)")
    suspend fun clearFailures(ids: List<Long>)

    @Query("DELETE FROM backup_failure")
    suspend fun clearAllFailures()

    @Query("SELECT * FROM backup_exclusion ORDER BY excludedAt DESC")
    fun exclusionsFlow(): Flow<List<BackupExclusionEntity>>

    @Query("SELECT * FROM backup_exclusion")
    suspend fun exclusions(): List<BackupExclusionEntity>

    @Upsert
    suspend fun putExclusions(rows: List<BackupExclusionEntity>)

    @Query("DELETE FROM backup_exclusion WHERE remoteId IN (:remoteIds)")
    suspend fun removeExclusionsByRemote(remoteIds: List<String>): Int

    @Query("DELETE FROM backup_exclusion WHERE localId IN (:localIds)")
    suspend fun removeExclusions(localIds: List<Long>): Int
}

/** One folder's counts from the index: [total] live items the options cover, [backedUp] of them with a confirmed Immich copy. */
data class BucketBackupCount(val bucketId: Long, val total: Int, val backedUp: Int)

data class IdGeneration(val id: Long, val generation: Long)

data class IdBucket(val id: Long, val bucketId: Long?)

/**
 * Backup's queries over the index (`media` joined with `immich_match`, both in `media-index.db`).
 * "Backed up" means a confirmed (SHA-1) Immich copy at the item's current generation.
 */
@Dao
interface BackupMediaDao {
    /**
     * Live items in [buckets] that the options cover and that have no confirmed Immich copy, newest
     * first: the upload queue. Trashed items are never in it.
     */
    @Query(
        """SELECT m.* FROM media m
           WHERE m.trashed = 0 AND m.bucketId IN (:buckets) AND (:videos = 1 OR m.isVideo = 0) AND m.takenAt >= :since
             AND NOT EXISTS (SELECT 1 FROM immich_match x WHERE x.localId = m.id AND x.generation = m.generation AND x.remoteId IS NOT NULL AND x.tentative = 0)
           ORDER BY m.takenAt DESC, m.id DESC LIMIT :limit""",
    )
    suspend fun pending(buckets: List<Long>, videos: Boolean, since: Long, limit: Int): List<com.roylet.photos.local.MediaEntity>

    @Query(
        """SELECT m.id AS id, m.generation AS generation FROM media m
           WHERE m.trashed = 0 AND m.bucketId IN (:buckets) AND (:videos = 1 OR m.isVideo = 0) AND m.takenAt >= :since
             AND NOT EXISTS (SELECT 1 FROM immich_match x WHERE x.localId = m.id AND x.generation = m.generation AND x.remoteId IS NOT NULL AND x.tentative = 0)""",
    )
    suspend fun pendingKeys(buckets: List<Long>, videos: Boolean, since: Long): List<IdGeneration>

    @Query(
        """SELECT COUNT(*) FROM media m
           WHERE m.trashed = 0 AND m.bucketId IN (:buckets) AND (:videos = 1 OR m.isVideo = 0) AND m.takenAt >= :since
             AND NOT EXISTS (SELECT 1 FROM immich_match x WHERE x.localId = m.id AND x.generation = m.generation AND x.remoteId IS NOT NULL AND x.tentative = 0)""",
    )
    suspend fun pendingCount(buckets: List<Long>, videos: Boolean, since: Long): Int

    /** Per folder: items the options cover, and how many of them are backed up. Re-emits when the index or the match cache changes. */
    @Query(
        """SELECT m.bucketId AS bucketId, COUNT(*) AS total,
                  COALESCE(SUM(CASE WHEN EXISTS (SELECT 1 FROM immich_match x WHERE x.localId = m.id AND x.generation = m.generation
                                                 AND x.remoteId IS NOT NULL AND x.tentative = 0) THEN 1 ELSE 0 END), 0) AS backedUp
           FROM media m WHERE m.trashed = 0 AND m.bucketId IS NOT NULL AND (:videos = 1 OR m.isVideo = 0) AND m.takenAt >= :since
           GROUP BY m.bucketId""",
    )
    fun countsFlow(videos: Boolean, since: Long): Flow<List<BucketBackupCount>>

    /** The phone's folders, newest first (as MediaDao.folders), re-emitted on index changes. */
    @Query(
        """SELECT bucketId, bucketName, relativePath, COUNT(*) AS count, MAX(takenAt) AS latest,
                  (SELECT m2.id FROM media m2 WHERE m2.bucketId = m.bucketId AND m2.trashed = 0 ORDER BY m2.takenAt DESC, m2.id DESC LIMIT 1) AS coverId
           FROM media m WHERE trashed = 0 AND bucketId IS NOT NULL
           GROUP BY bucketId ORDER BY latest DESC""",
    )
    fun foldersFlow(): Flow<List<FolderRow>>

    @Query("SELECT id, bucketId FROM media WHERE id IN (:ids)")
    suspend fun bucketsOf(ids: List<Long>): List<IdBucket>

    @Query("SELECT id FROM media WHERE isVideo = 1 AND id IN (:ids)")
    suspend fun videoIds(ids: List<Long>): List<Long>
}
