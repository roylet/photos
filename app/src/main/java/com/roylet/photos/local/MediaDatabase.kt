package com.roylet.photos.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/*
 * The app's mirror of MediaStore's images and videos (see docs/SOURCES.md, "Phone index").
 * MediaIndexer keeps it in step; the timeline, folders, search and trash read only from here, so
 * a 50k-photo library pages by month without touching MediaStore on the main path.
 */

/** One MediaStore image or video. */
@Entity(
    tableName = "media",
    indices = [
        Index(value = ["trashed", "month", "takenAt"]),
        Index(value = ["bucketId", "trashed"]),
        Index(value = ["favorite", "trashed"]),
    ],
)
data class MediaEntity(
    /** MediaStore `_ID`; the app's id is `local:<id>`. */
    @PrimaryKey val id: Long,
    val isVideo: Boolean,
    /** When it was taken: DATE_TAKEN, else DATE_ADDED or DATE_MODIFIED (seconds × 1000). UTC millis. */
    val takenAt: Long,
    /** The month of [takenAt] in the device's time zone, as `yyyy * 100 + MM` (e.g. 202611). */
    val month: Int,
    /** Device zone offset at [takenAt], in minutes (for the local wall-clock time). */
    val offsetMinutes: Int,
    /** Stored pixel size, before [orientation]. */
    val width: Int,
    val height: Int,
    /** 0, 90, 180 or 270. */
    val orientation: Int,
    val durationMs: Long?,
    val mimeType: String?,
    val displayName: String?,
    val sizeBytes: Long?,
    val bucketId: Long?,
    val bucketName: String?,
    val relativePath: String?,
    val volume: String?,
    val favorite: Boolean,
    val trashed: Boolean,
    /** When MediaStore deletes a trashed item for good (DATE_EXPIRES × 1000), UTC millis. */
    val expiresAt: Long?,
    /** GENERATION_MODIFIED (Android 11+), else DATE_MODIFIED. Changes whenever the item does. */
    val generation: Long,
    /**
     * Immich-compatible SHA-1 of the file (hex), computed lazily for matching (agent C). Valid only
     * while [sha1Generation] == [generation]; the indexer clears it when the item changes.
     */
    val sha1: String? = null,
    val sha1Generation: Long? = null,
) {
    /** Width / height as displayed (orientation applied). */
    val ratio: Float get() = displayRatio(width, height, orientation)
}

/** Small key/value state for the indexer: MediaStore version, generations, zone, access level. */
@Entity(tableName = "index_meta")
data class MetaEntity(@PrimaryKey val key: String, val value: String)

/** A month and how many items it holds. */
data class MonthCount(val month: Int, val count: Int)

/** A folder (BUCKET_ID) with its newest item as the cover. */
data class FolderRow(val bucketId: Long, val bucketName: String?, val relativePath: String?, val count: Int, val latest: Long, val coverId: Long)

@Dao
interface MediaDao {
    /* ---- Timeline ---- */

    @Query(
        """SELECT month, COUNT(*) AS count FROM media
           WHERE trashed = :trashed AND (:favorite = 0 OR favorite = 1) AND (:bucketId IS NULL OR bucketId = :bucketId)
           GROUP BY month ORDER BY month DESC""",
    )
    suspend fun months(trashed: Boolean, favorite: Boolean, bucketId: Long?): List<MonthCount>

    @Query(
        """SELECT * FROM media
           WHERE month = :month AND trashed = :trashed AND (:favorite = 0 OR favorite = 1) AND (:bucketId IS NULL OR bucketId = :bucketId)
           ORDER BY takenAt DESC, id DESC""",
    )
    suspend fun inMonth(month: Int, trashed: Boolean, favorite: Boolean, bucketId: Long?): List<MediaEntity>

    @Query("SELECT * FROM media WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<MediaEntity>

    @Query("SELECT * FROM media WHERE id = :id")
    suspend fun byId(id: Long): MediaEntity?

    @Query("SELECT id FROM media WHERE trashed = 1")
    suspend fun trashedIds(): List<Long>

    /* ---- Folders ---- */

    @Query(
        """SELECT bucketId, bucketName, relativePath, COUNT(*) AS count, MAX(takenAt) AS latest,
                  (SELECT m2.id FROM media m2 WHERE m2.bucketId = m.bucketId AND m2.trashed = 0 ORDER BY m2.takenAt DESC, m2.id DESC LIMIT 1) AS coverId
           FROM media m WHERE trashed = 0 AND bucketId IS NOT NULL
           GROUP BY bucketId ORDER BY latest DESC""",
    )
    suspend fun folders(): List<FolderRow>

    /* ---- Search ---- */

    @Query(
        """SELECT * FROM media WHERE trashed = 0 AND (displayName LIKE :pattern ESCAPE '\' OR bucketName LIKE :pattern ESCAPE '\' OR relativePath LIKE :pattern ESCAPE '\')
           ORDER BY takenAt DESC LIMIT :limit""",
    )
    suspend fun searchText(pattern: String, limit: Int): List<MediaEntity>

    @Query("SELECT * FROM media WHERE trashed = 0 AND month BETWEEN :from AND :to ORDER BY takenAt DESC LIMIT :limit")
    suspend fun inMonths(from: Int, to: Int, limit: Int): List<MediaEntity>

    /** Same calendar month in any year (a month name without a year). */
    @Query("SELECT * FROM media WHERE trashed = 0 AND month % 100 = :monthOfYear ORDER BY takenAt DESC LIMIT :limit")
    suspend fun inMonthOfYear(monthOfYear: Int, limit: Int): List<MediaEntity>

    /* ---- Sync ---- */

    @Query("SELECT id FROM media")
    suspend fun allIds(): LongArray

    @Query("SELECT COUNT(*) FROM media")
    suspend fun count(): Int

    @Upsert
    suspend fun upsert(rows: List<MediaEntity>)

    @Query("DELETE FROM media WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query("DELETE FROM media")
    suspend fun clear()

    @Query("SELECT id, takenAt FROM media")
    suspend fun timestamps(): List<IdTime>

    @Query("UPDATE media SET month = :month, offsetMinutes = :offsetMinutes WHERE id = :id")
    suspend fun setMonth(id: Long, month: Int, offsetMinutes: Int)

    /* ---- Local edits (applied right after a MediaStore request so the UI needn't wait for a re-sync) ---- */

    @Query("UPDATE media SET favorite = :favorite WHERE id IN (:ids)")
    suspend fun setFavorite(ids: List<Long>, favorite: Boolean)

    @Query("UPDATE media SET trashed = :trashed, expiresAt = :expiresAt WHERE id IN (:ids)")
    suspend fun setTrashed(ids: List<Long>, trashed: Boolean, expiresAt: Long?)

    /* ---- SHA-1 (agent C) ---- */

    /** Stores [sha1] for [id] if it's still at [generation] (a later edit makes it stale). Returns rows updated. */
    @Query("UPDATE media SET sha1 = :sha1, sha1Generation = :generation WHERE id = :id AND generation = :generation")
    suspend fun setSha1(id: Long, sha1: String, generation: Long): Int

    /** Items with no current SHA-1, newest first: the hashing job's queue. */
    @Query("SELECT * FROM media WHERE trashed = 0 AND (sha1 IS NULL OR sha1Generation IS NOT generation) ORDER BY takenAt DESC LIMIT :limit")
    suspend fun needingSha1(limit: Int): List<MediaEntity>

    /* ---- Meta ---- */

    @Query("SELECT value FROM index_meta WHERE `key` = :key")
    suspend fun meta(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putMeta(meta: MetaEntity)

    @Query("DELETE FROM index_meta")
    suspend fun clearMeta()

    /** Re-buckets [rows] (a time-zone change) in one transaction. */
    @Transaction
    suspend fun setMonths(rows: List<IdMonth>) { rows.forEach { setMonth(it.id, it.month, it.offsetMinutes) } }

    /** Writes a batch in one transaction (one fsync per batch, not per row). */
    @Transaction
    suspend fun applyBatch(upserts: List<MediaEntity>, deletes: List<Long>) {
        if (upserts.isNotEmpty()) upsert(upserts)
        if (deletes.isNotEmpty()) deletes.chunked(SQL_VARS).forEach { delete(it) }
    }
}

data class IdTime(val id: Long, val takenAt: Long)
data class IdMonth(val id: Long, val month: Int, val offsetMinutes: Int)

/** SQLite's bound-parameter limit is 999 on older Androids; stay well under it. */
internal const val SQL_VARS = 500

@Database(
    entities = [MediaEntity::class, MetaEntity::class, MatchEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class MediaDatabase : RoomDatabase() {
    abstract fun media(): MediaDao
    abstract fun matches(): MatchDao

    companion object {
        const val NAME = "media-index.db"

        /**
         * Opens the index. Every known version has a real migration ([migrations]); only an
         * unknown one (a downgrade, a dev build) falls back to a rebuild, which is safe since v2:
         * the index holds nothing that can't be recomputed from MediaStore and Immich. What can't
         * be (Android 10's trash list, local edit recipes) lives in [PhotosDatabase].
         *
         * [onLegacyRows] receives v1's Android 10 trash and favourites during the 1 → 2 migration,
         * before their tables are dropped; it must store them (in [PhotosDatabase]) or throw.
         */
        fun open(context: Context, onLegacyRows: (LegacyRows) -> Unit): MediaDatabase =
            Room.databaseBuilder(context.applicationContext, MediaDatabase::class.java, NAME)
                .addMigrations(*migrations(onLegacyRows))
                .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                .build()

        fun migrations(onLegacyRows: (LegacyRows) -> Unit): Array<Migration> = arrayOf(Migration1To2(onLegacyRows))

        fun inMemory(context: Context): MediaDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, MediaDatabase::class.java).allowMainThreadQueries().build()
    }
}

/**
 * v1 → v2: Android 10's trash and favourites move to [PhotosDatabase] (handed to [onLegacyRows]
 * first, so a failure keeps them here), then the `immich_match` cache is added.
 */
class Migration1To2(private val onLegacyRows: (LegacyRows) -> Unit) : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val trash = ArrayList<LegacyTrashEntity>()
        db.query("SELECT * FROM legacy_trash").use { c ->
            fun col(n: String) = c.getColumnIndexOrThrow(n)
            fun long(n: String) = c.getLong(col(n))
            fun longOrNull(n: String) = col(n).let { if (c.isNull(it)) null else c.getLong(it) }
            fun str(n: String) = col(n).let { if (c.isNull(it)) null else c.getString(it) }
            while (c.moveToNext()) trash += LegacyTrashEntity(
                rowId = long("rowId"), path = str("path")!!, originalId = long("originalId"), isVideo = long("isVideo") != 0L,
                takenAt = long("takenAt"), month = long("month").toInt(), offsetMinutes = long("offsetMinutes").toInt(),
                width = long("width").toInt(), height = long("height").toInt(), orientation = long("orientation").toInt(),
                durationMs = longOrNull("durationMs"), mimeType = str("mimeType"), displayName = str("displayName"),
                sizeBytes = longOrNull("sizeBytes"), bucketName = str("bucketName"), relativePath = str("relativePath"),
                favorite = long("favorite") != 0L, trashedAt = long("trashedAt"), expiresAt = long("expiresAt"),
            )
        }
        val favorites = ArrayList<Long>()
        db.query("SELECT id FROM legacy_favorite").use { c -> while (c.moveToNext()) favorites += c.getLong(0) }
        if (trash.isNotEmpty() || favorites.isNotEmpty()) onLegacyRows(LegacyRows(trash, favorites))
        db.execSQL("DROP TABLE IF EXISTS `legacy_trash`")
        db.execSQL("DROP TABLE IF EXISTS `legacy_favorite`")
        db.execSQL(MatchEntity.CREATE_SQL)
        db.execSQL(MatchEntity.INDEX_SQL)
    }
}

/** Android 10's rows carried out of a v1 index. */
data class LegacyRows(val trash: List<LegacyTrashEntity>, val favorites: List<Long>)

/**
 * The Immich copy of a phone item, cached (see docs/SOURCES.md §9). Valid while [generation] is
 * the item's current generation. [remoteId] null means "checked, Immich doesn't have it".
 * [tentative] marks a filename + date + size guess, made before the SHA-1 was known.
 */
@Entity(tableName = "immich_match", indices = [Index(value = ["remoteId"])])
data class MatchEntity(
    @PrimaryKey val localId: Long,
    val generation: Long,
    val remoteId: String?,
    val tentative: Boolean,
    val checkedAt: Long,
) {
    companion object {
        internal const val CREATE_SQL =
            "CREATE TABLE IF NOT EXISTS `immich_match` (`localId` INTEGER NOT NULL, `generation` INTEGER NOT NULL, `remoteId` TEXT, " +
                "`tentative` INTEGER NOT NULL, `checkedAt` INTEGER NOT NULL, PRIMARY KEY(`localId`))"
        internal const val INDEX_SQL = "CREATE INDEX IF NOT EXISTS `index_immich_match_remoteId` ON `immich_match` (`remoteId`)"
    }
}

@Dao
interface MatchDao {
    @Query("SELECT * FROM immich_match WHERE localId IN (:ids)")
    suspend fun byLocalIds(ids: List<Long>): List<MatchEntity>

    @Query("SELECT * FROM immich_match WHERE remoteId IN (:remoteIds)")
    suspend fun byRemoteIds(remoteIds: List<String>): List<MatchEntity>

    @Upsert
    suspend fun upsert(rows: List<MatchEntity>)

    @Query("DELETE FROM immich_match WHERE localId IN (:ids)")
    suspend fun deleteLocal(ids: List<Long>)

    @Query("DELETE FROM immich_match WHERE remoteId IN (:remoteIds)")
    suspend fun deleteRemote(remoteIds: List<String>)

    @Query("DELETE FROM immich_match")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM immich_match")
    suspend fun count(): Int
}

/** Width / height with [orientation] applied (90/270 swap the sides); 1 when unknown. */
fun displayRatio(width: Int, height: Int, orientation: Int): Float {
    if (width <= 0 || height <= 0) return 1f
    val turned = Math.floorMod(orientation, 180) == 90
    return if (turned) height.toFloat() / width else width.toFloat() / height
}
