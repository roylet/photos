package com.roylet.photos

import com.roylet.photos.data.UiPrefs
import com.roylet.photos.domain.library.AlbumStore
import com.roylet.photos.domain.library.DeleteTarget
import com.roylet.photos.domain.library.LibrarySource
import com.roylet.photos.domain.library.TargetedTrash
import com.roylet.photos.domain.library.TrashReceipt
import com.roylet.photos.domain.timeline.TimelineFilter
import com.roylet.photos.domain.timeline.TimelineRegistry
import com.roylet.photos.merge.MergedLibrary
import com.roylet.photos.ui.actions.ActionSheet
import com.roylet.photos.ui.actions.LibraryActions
import com.roylet.photos.ui.actions.ShareLauncher
import com.roylet.photos.ui.components.SnackbarController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** "Delete from: this device / Immich / both" for merged items, remembered for the session. */
class DeleteChoiceTest {
    /** Ids 0 and 1 of the fake timeline's first month are "on both". */
    private inner class Merged(private val base: FakeLibrary) : LibrarySource by base, TargetedTrash {
        val trashed = mutableListOf<Pair<List<String>, DeleteTarget>>()
        val restored = mutableListOf<TrashReceipt>()
        override suspend fun pairedIds(ids: List<String>): Set<String> = ids.filter { it in paired }.toSet()
        override suspend fun trash(ids: List<String>, target: DeleteTarget): TrashReceipt {
            trashed += ids to target
            val pairedOnes = ids.filter { it in paired }
            return MergedLibrary.plan(ids, emptyList(), pairedOnes.associateWith { "remote-$it" }, emptyMap(), target)
        }
        override suspend fun restore(receipt: TrashReceipt) { restored += receipt }
    }

    private val registry = TimelineRegistry(FakeGallery.source).apply { context = Dispatchers.Unconfined }
    private val repo = registry.get(TimelineFilter.Timeline).apply { start(); ensureLoaded(0) }
    private val first = repo.snapshot.value.assetsOf(0)!!
    private val paired = setOf(first[0].id, first[1].id)
    private val lib = Merged(FakeLibrary())
    private val snackbar = SnackbarController()
    private var session: Any = "immich:u1"
    private val actions = LibraryActions(
        library = { lib },
        albums = AlbumStore({ lib }, CoroutineScope(Dispatchers.Unconfined)),
        snackbar = snackbar,
        scope = CoroutineScope(Dispatchers.Unconfined),
        launcher = object : ShareLauncher {
            override fun sendFiles(files: List<File>) {}
            override fun sendText(text: String) {}
            override fun copy(text: String) {}
        },
        prefs = UiPrefs(null),
        shareDir = File("build/tmp"),
        timelines = registry,
        session = { session },
        inBackedUpFolder = { ids -> ids.any { it in backedUp } },
    )
    /** Items in a folder that backup covers. */
    private var backedUp = emptySet<String>()

    @Test fun `deleting an item on both sides asks where from`() {
        actions.delete(listOf(first[0].id, first[2].id), repo)
        val sheet = actions.sheet as ActionSheet.DeleteChoice
        assertEquals(1, sheet.paired)
        assertTrue(lib.trashed.isEmpty()) // nothing happens until the user answers
        actions.chooseDelete(DeleteTarget.Device)
        assertNull(actions.sheet)
        assertEquals(listOf(listOf(first[0].id, first[2].id) to DeleteTarget.Device), lib.trashed)
        assertTrue(snackbar.current!!.message.contains("on this device"))
    }

    @Test fun `the answer is remembered for the session, then asked again`() {
        actions.delete(listOf(first[0].id), repo)
        actions.chooseDelete(DeleteTarget.Immich)
        actions.delete(listOf(first[1].id), repo)
        assertNull(actions.sheet)
        assertEquals(DeleteTarget.Immich, lib.trashed.last().second)
        session = "immich:u2"
        actions.delete(listOf(first[3].id, first[1].id), repo)
        assertTrue(actions.sheet is ActionSheet.DeleteChoice)
    }

    @Test fun `one-sided items are deleted without asking`() {
        actions.delete(listOf(first[2].id, first[3].id), repo)
        assertNull(actions.sheet)
        assertEquals(DeleteTarget.Both, lib.trashed.single().second)
    }

    @Test fun `undo restores what went to each trash`() {
        actions.delete(listOf(first[0].id), repo)
        actions.chooseDelete(DeleteTarget.Both)
        assertTrue(repo.snapshot.value.indexOf(first[0].id) < 0)
        snackbar.current!!.onAction!!()
        assertEquals(TrashReceipt(listOf(first[0].id), listOf("remote-${first[0].id}")), lib.restored.single())
        assertTrue(repo.snapshot.value.indexOf(first[0].id) >= 0)
    }

    @Test fun `in a backed-up folder the sheet says so, and a remembered "Immich" asks again`() {
        backedUp = setOf(first[0].id)
        actions.delete(listOf(first[0].id), repo)
        assertTrue((actions.sheet as ActionSheet.DeleteChoice).backedUp)
        actions.chooseDelete(DeleteTarget.Immich)
        // Remembered "Immich": not in a backed-up folder → used as is.
        actions.delete(listOf(first[1].id), repo)
        assertNull(actions.sheet)
        assertEquals(listOf(first[1].id) to DeleteTarget.Immich, lib.trashed.last())
        // In a backed-up folder → asked again, with the warning.
        lib.trashed.clear()
        backedUp = setOf(first[1].id)
        actions.delete(listOf(first[1].id), repo)
        assertTrue((actions.sheet as ActionSheet.DeleteChoice).backedUp)
        assertTrue(lib.trashed.isEmpty())
    }
}
