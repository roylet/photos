package com.roylet.photos.editor

import androidx.test.core.app.ApplicationProvider
import com.roylet.photos.editor.recipe.Recipe
import com.roylet.photos.editor.save.EditSaveManager
import com.roylet.photos.editor.save.SaveMode
import com.roylet.photos.editor.ui.EditorCache
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Pruning the editor's download cache must never delete an original a queued save still needs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditorCacheTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val root get() = File(context.cacheDir, "editor")

    @After fun clean() {
        root.deleteRecursively()
        File(context.filesDir, "edit-save").deleteRecursively()
    }

    private fun original(name: String, age: Long): File {
        val dir = File(root, name).apply { mkdirs() }
        val f = File(dir, "IMG.jpg").apply { writeBytes(ByteArray(8)) }
        dir.setLastModified(1_700_000_000_000L - age * 60_000)
        return f
    }

    @Test fun keepsTheNewestTwoAndTheOneBeingOpened() {
        val files = (1..5).map { original("a$it", age = it.toLong()) }
        val opening = File(root, "new").apply { mkdirs() }
        EditorCache.prune(root, keep = opening, protect = emptySet())
        assertEquals(listOf(true, true, false, false, false), files.map { it.exists() })
        assertTrue(opening.exists())
    }

    @Test fun neverDeletesTheSourceOfAQueuedSave() {
        val manager = EditSaveManager(context, { null }, render = { _, _, _ -> })
        val queued = original("queued", age = 100) // the oldest: first in line for deletion
        val others = (1..4).map { original("b$it", age = it.toLong()) }
        val jobId = manager.createJob("t", "t", queued, "IMG.jpg", Recipe(), SaveMode.COPY, group = false)

        assertEquals(setOf(queued.absoluteFile), manager.pendingSourceFiles())
        EditorCache.prune(root, keep = File(root, "new"), protect = manager.pendingSourceFiles())

        assertTrue("queued save's original survives", queued.exists())
        assertEquals(listOf(true, true, false, false), others.map { it.exists() })

        // Once the job is gone (finished or failed), its original is fair game again.
        File(context.filesDir, "edit-save/$jobId.json").delete()
        EditorCache.prune(root, keep = File(root, "new"), protect = manager.pendingSourceFiles())
        assertFalse(queued.exists())
    }
}
