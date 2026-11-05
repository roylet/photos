package com.roylet.photos.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The index's migrations (built from the exported schemas in app/schemas, so the v1 database is
 * exactly what agent B shipped) and the strict app database.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DatabaseMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val opened = ArrayList<androidx.room.RoomDatabase>()

    @Before @After fun wipe() {
        opened.forEach { it.close() }
        opened.clear()
        context.deleteDatabase(MediaDatabase.NAME)
        context.deleteDatabase(PhotosDatabase.NAME)
    }

    /** Creates [name] at schema [version] from its exported JSON, the way Room would have. */
    private fun createFromSchema(dbClass: String, version: Int, name: String): SQLiteDatabase {
        val schema = Json.parseToJsonElement(File("schemas/$dbClass/$version.json").readText()).jsonObject["database"]!!.jsonObject
        val file = context.getDatabasePath(name).also { it.parentFile?.mkdirs() }
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        for (e in schema["entities"]!!.jsonArray) {
            val table = e.jsonObject["tableName"]!!.jsonPrimitive.content
            db.execSQL(e.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            e.jsonObject["indices"]?.jsonArray?.forEach { i -> db.execSQL(i.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
        }
        schema["setupQueries"]!!.jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
        db.version = version
        return db
    }

    private fun insertV1Media(db: SQLiteDatabase, id: Long) = db.execSQL(
        "INSERT INTO media (id, isVideo, takenAt, month, offsetMinutes, width, height, orientation, mimeType, displayName, favorite, trashed, generation, sha1, sha1Generation) " +
            "VALUES ($id, 0, 1000, 202611, 600, 4000, 3000, 0, 'image/jpeg', 'IMG_$id.jpg', 0, 0, 5, 'abc', 5)",
    )

    @Test fun `v1 to v2 keeps the index and moves Android 10 trash and favourites to the app database`() {
        createFromSchema("com.roylet.photos.local.MediaDatabase", 1, MediaDatabase.NAME).apply {
            insertV1Media(this, 1)
            insertV1Media(this, 2)
            execSQL(
                "INSERT INTO legacy_trash (rowId, path, originalId, isVideo, takenAt, month, offsetMinutes, width, height, orientation, displayName, favorite, trashedAt, expiresAt) " +
                    "VALUES (7, '/files/trash/a.jpg', 99, 0, 500, 202610, 600, 10, 20, 90, 'a.jpg', 1, 600, 700)",
            )
            execSQL("INSERT INTO legacy_favorite (id) VALUES (1)")
            close()
        }
        val app = PhotosDatabase.open(context).also(opened::add)
        val index = MediaDatabase.open(context) { app.importLegacy(it) }.also(opened::add)

        val media = runBlocking { index.media().byId(1) }
        assertNotNull(media)
        assertEquals("abc", media!!.sha1)
        assertEquals(2, runBlocking { index.media().count() })
        assertEquals(0, runBlocking { index.matches().count() })

        val trash = runBlocking { app.app().legacyTrash() }
        assertEquals(1, trash.size)
        assertEquals(7L, trash[0].rowId) // `local:-7` keeps pointing at the same copy
        assertEquals("/files/trash/a.jpg", trash[0].path)
        assertEquals(90, trash[0].orientation)
        assertTrue(trash[0].favorite)
        assertEquals(listOf(1L), runBlocking { app.app().legacyFavorites() }.toList())

        // The old tables are gone from the index.
        index.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE name LIKE 'legacy_%'").use { assertEquals(0, it.count) }
    }

    @Test fun `a failed hand-over keeps the v1 rows`() {
        createFromSchema("com.roylet.photos.local.MediaDatabase", 1, MediaDatabase.NAME).apply {
            execSQL(
                "INSERT INTO legacy_trash (rowId, path, originalId, isVideo, takenAt, month, offsetMinutes, width, height, orientation, favorite, trashedAt, expiresAt) " +
                    "VALUES (3, '/t', 1, 0, 0, 202601, 0, 1, 1, 0, 0, 0, 0)",
            )
            close()
        }
        val index = MediaDatabase.open(context) { throw IllegalStateException("disk full") }.also(opened::add)
        val failed = runCatching { runBlocking { index.media().count() } }
        assertTrue(failed.isFailure)
        index.close()
        val raw = SQLiteDatabase.openDatabase(context.getDatabasePath(MediaDatabase.NAME).path, null, SQLiteDatabase.OPEN_READONLY)
        raw.rawQuery("SELECT COUNT(*) FROM legacy_trash", null).use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        assertEquals(1, raw.version)
        raw.close()
    }

    @Test fun `an empty v1 index migrates without touching the app database`() {
        createFromSchema("com.roylet.photos.local.MediaDatabase", 1, MediaDatabase.NAME).apply { insertV1Media(this, 4); close() }
        var called = false
        val index = MediaDatabase.open(context) { called = true }.also(opened::add)
        assertEquals(1, runBlocking { index.media().count() })
        assertTrue(!called)
    }

    @Test fun `the app database keeps trash and recipes across reopening`() {
        val first = PhotosDatabase.open(context)
        runBlocking {
            first.app().addLegacyTrash(LegacyTrashEntity(path = "/p", originalId = 1, isVideo = false, takenAt = 1, month = 202601, offsetMinutes = 0,
                width = 1, height = 1, orientation = 0, durationMs = null, mimeType = null, displayName = null, sizeBytes = null,
                bucketName = null, relativePath = null, favorite = false, trashedAt = 1, expiresAt = 2))
            first.app().putEdit(LocalEditEntity(42, "local:7", LocalEditEntity.PHOTO, "{}", 1))
        }
        first.close()
        val second = PhotosDatabase.open(context).also(opened::add)
        assertEquals(1, runBlocking { second.app().legacyTrash() }.size)
        assertEquals("local:7", runBlocking { second.app().edit(42) }?.sourceId)
        assertNull(runBlocking { second.app().edit(43) })
    }

    @Test fun `the app database matches its exported schema`() {
        // Opening the exported v1 schema with Room validates the identity hash and every table.
        createFromSchema("com.roylet.photos.local.PhotosDatabase", 1, PhotosDatabase.NAME).close()
        val db = PhotosDatabase.open(context).also(opened::add)
        assertEquals(0, runBlocking { db.app().legacyTrash() }.size)
    }
}
