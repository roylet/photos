package com.roylet.photos.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.roylet.photos.backup.BackupDao
import com.roylet.photos.backup.BackupExclusionEntity
import com.roylet.photos.backup.BackupFailureEntity
import com.roylet.photos.backup.BackupFolderEntity

/*
 * The app's own data about phone photos that can't be recomputed from MediaStore or Immich:
 * Android 10's private trash list, its favourites and the edit recipes of locally saved edits.
 * Unlike the index (MediaDatabase, a cache) this database never falls back to a destructive
 * rebuild: every version bump needs a Migration in [PhotosDatabase.MIGRATIONS] and a test.
 */

/**
 * Android 10's own trash: MediaStore has no trash there, so the original is copied into the app's
 * private `files/trash` folder and deleted from MediaStore. Restore puts it back where it was.
 */
@Entity(tableName = "legacy_trash")
data class LegacyTrashEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    /** The private copy. */
    val path: String,
    val originalId: Long,
    val isVideo: Boolean,
    val takenAt: Long,
    val month: Int,
    val offsetMinutes: Int,
    val width: Int,
    val height: Int,
    val orientation: Int,
    val durationMs: Long?,
    val mimeType: String?,
    val displayName: String?,
    val sizeBytes: Long?,
    val bucketName: String?,
    val relativePath: String?,
    val favorite: Boolean,
    val trashedAt: Long,
    val expiresAt: Long,
)

/** Android 10's favourites (no IS_FAVORITE column there). */
@Entity(tableName = "legacy_favorite")
data class LegacyFavoriteEntity(@PrimaryKey val id: Long)

/**
 * The edit behind a phone item this app saved: re-opening it in the editor offers "continue
 * editing" from [sourceId] (the `local:` or Immich id whose pixels were edited) with [json]
 * (a photo Recipe or a VideoEdit, by [kind]).
 */
@Entity(tableName = "local_edit")
data class LocalEditEntity(
    /** MediaStore `_ID` of the saved file. */
    @PrimaryKey val mediaStoreId: Long,
    val sourceId: String,
    val kind: String,
    val json: String,
    val savedAt: Long,
) {
    companion object {
        const val PHOTO = "photo"
        const val VIDEO = "video"
    }
}

@Dao
interface AppDao {
    /* ---- Android 10 trash and favourites ---- */

    @Query("SELECT id FROM legacy_favorite")
    suspend fun legacyFavorites(): LongArray

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addLegacyFavorites(rows: List<LegacyFavoriteEntity>)

    @Query("DELETE FROM legacy_favorite WHERE id IN (:ids)")
    suspend fun removeLegacyFavorites(ids: List<Long>)

    @Insert
    suspend fun addLegacyTrash(row: LegacyTrashEntity): Long

    @Query("SELECT * FROM legacy_trash ORDER BY takenAt DESC")
    suspend fun legacyTrash(): List<LegacyTrashEntity>

    @Query("SELECT * FROM legacy_trash WHERE rowId IN (:rowIds)")
    suspend fun legacyTrashByIds(rowIds: List<Long>): List<LegacyTrashEntity>

    /** Trashed copies of these (former) MediaStore ids: an undo names the original, not `local:-<rowId>`. */
    @Query("SELECT * FROM legacy_trash WHERE originalId IN (:originalIds)")
    suspend fun legacyTrashByOriginalIds(originalIds: List<Long>): List<LegacyTrashEntity>

    @Query("SELECT month, COUNT(*) AS count FROM legacy_trash GROUP BY month ORDER BY month DESC")
    suspend fun legacyTrashMonths(): List<MonthCount>

    @Query("SELECT * FROM legacy_trash WHERE month = :month ORDER BY takenAt DESC, rowId DESC")
    suspend fun legacyTrashInMonth(month: Int): List<LegacyTrashEntity>

    @Query("SELECT * FROM legacy_trash WHERE expiresAt < :now")
    suspend fun expiredLegacyTrash(now: Long): List<LegacyTrashEntity>

    @Query("DELETE FROM legacy_trash WHERE rowId IN (:rowIds)")
    suspend fun removeLegacyTrash(rowIds: List<Long>)

    /* ---- Carried over from a v1 index (blocking: called from inside its migration) ---- */

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun importLegacyTrash(rows: List<LegacyTrashEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun importLegacyFavorites(rows: List<LegacyFavoriteEntity>)

    /* ---- Local edit recipes ---- */

    @Upsert
    suspend fun putEdit(row: LocalEditEntity)

    @Query("SELECT * FROM local_edit WHERE mediaStoreId = :id")
    suspend fun edit(id: Long): LocalEditEntity?

    @Query("DELETE FROM local_edit WHERE mediaStoreId = :id")
    suspend fun deleteEdit(id: Long)
}

@Database(
    entities = [
        LegacyTrashEntity::class, LegacyFavoriteEntity::class, LocalEditEntity::class,
        BackupFolderEntity::class, BackupFailureEntity::class, BackupExclusionEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class PhotosDatabase : RoomDatabase() {
    abstract fun app(): AppDao
    /** Backup's folder choices, failures and exclusions (v2, docs/SOURCES.md §10). */
    abstract fun backup(): BackupDao

    /** Stores a v1 index's Android 10 rows, keeping their row ids (so `local:-<rowId>` stays the same). */
    fun importLegacy(rows: LegacyRows) = runInTransaction {
        app().importLegacyTrash(rows.trash)
        app().importLegacyFavorites(rows.favorites.map(::LegacyFavoriteEntity))
    }

    companion object {
        const val NAME = "photos-app.db"

        /** Every schema change adds one here (and a test in PhotosDatabaseTest). No destructive fallback. */
        val MIGRATIONS: Array<Migration> = arrayOf(AppMigration1To2)

        fun open(context: Context): PhotosDatabase =
            Room.databaseBuilder(context.applicationContext, PhotosDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .build()

        fun inMemory(context: Context): PhotosDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, PhotosDatabase::class.java).allowMainThreadQueries().build()
    }
}

/** v1 → v2: backup's tables (selected folders, failures, exclusions). Nothing existing changes. */
object AppMigration1To2 : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `backup_folder` (`bucketId` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, `changedAt` INTEGER NOT NULL, PRIMARY KEY(`bucketId`))")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `backup_failure` (`localId` INTEGER NOT NULL, `generation` INTEGER NOT NULL, `displayName` TEXT, " +
                "`bucketId` INTEGER, `reason` TEXT NOT NULL, `status` INTEGER, `failedAt` INTEGER NOT NULL, PRIMARY KEY(`localId`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `backup_exclusion` (`localId` INTEGER NOT NULL, `sha1` TEXT, `remoteId` TEXT, `displayName` TEXT, " +
                "`bucketId` INTEGER, `reason` TEXT NOT NULL, `excludedAt` INTEGER NOT NULL, PRIMARY KEY(`localId`))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_backup_exclusion_remoteId` ON `backup_exclusion` (`remoteId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_backup_exclusion_sha1` ON `backup_exclusion` (`sha1`)")
    }
}
