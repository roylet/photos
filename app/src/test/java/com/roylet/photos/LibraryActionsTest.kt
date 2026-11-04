package com.roylet.photos

import com.roylet.photos.data.UiPrefs
import com.roylet.photos.domain.library.AlbumStore
import com.roylet.photos.domain.timeline.TimelineFilter
import com.roylet.photos.domain.timeline.TimelineRegistry
import com.roylet.photos.immich.ImmichApi
import com.roylet.photos.immich.setFavorite
import com.roylet.photos.ui.actions.ActionSheet
import com.roylet.photos.ui.actions.LibraryActions
import com.roylet.photos.ui.actions.ShareLauncher
import com.roylet.photos.ui.components.SnackTone
import com.roylet.photos.ui.components.SnackbarController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LibraryActionsTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var api: ImmichApi
    private val library = FakeLibrary()
    private val snackbar = SnackbarController()
    private val registry = TimelineRegistry(FakeGallery.source).apply { context = Dispatchers.Unconfined }
    private val shared = mutableListOf<String>()
    private lateinit var actions: LibraryActions

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        api = ImmichApi(server.url("/").toString(), "k")
        actions = LibraryActions(
            library = { library },
            albums = AlbumStore({ library }, CoroutineScope(Dispatchers.Unconfined)),
            snackbar = snackbar,
            scope = CoroutineScope(Dispatchers.Unconfined),
            favoriteCall = { ids, fav -> api.setFavorite(ids, fav) },
            launcher = object : ShareLauncher {
                override fun sendFiles(files: List<File>) { shared += files.map { it.name } }
                override fun sendText(text: String) { shared += text }
                override fun copy(text: String) { shared += "copy:$text" }
            },
            prefs = UiPrefs(null),
            shareDir = tmp.root,
            timelines = registry,
        )
    }

    @After fun tearDown() = server.shutdown()

    private fun loadedTimeline() = registry.get(TimelineFilter.Timeline).apply { start(); FakeGallery.buckets.indices.forEach { ensureLoaded(it) } }

    /** Waits for the (OkHttp-thread) favourite call to finish and the rollback to land. */
    private fun waitFor(cond: () -> Boolean) { repeat(200) { if (cond()) return; Thread.sleep(10) }; assertTrue("timed out", cond()) }

    @Test fun `favourite fails, rolls back to each asset's old state and shows an error`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"message":"db down"}"""))
        val repo = loadedTimeline()
        val first = repo.snapshot.value.assetsOf(0)!!
        val ids = first.take(4).map { it.id }
        val before = first.take(4).associate { it.id to it.isFavorite }

        actions.favorite(ids, true, repo)
        assertTrue("optimistic", repo.snapshot.value.assetsOf(0)!!.take(4).all { it.isFavorite })

        waitFor { snackbar.current != null }
        assertEquals(before, repo.snapshot.value.assetsOf(0)!!.take(4).associate { it.id to it.isFavorite })
        assertEquals(SnackTone.Error, snackbar.current!!.tone)
        val req = server.takeRequest()
        assertEquals("PUT /api/assets", "${req.method} ${req.path}")
        assertTrue(req.body.readUtf8().contains("\"isFavorite\":true"))
    }

    @Test fun `favourite success keeps the new state`() {
        server.enqueue(MockResponse().setResponseCode(204))
        val repo = loadedTimeline()
        val id = repo.snapshot.value.assetsOf(0)!!.first { !it.isFavorite }.id
        actions.favorite(listOf(id), true, repo)
        server.takeRequest()
        Thread.sleep(100)
        assertNull(snackbar.current)
        assertTrue(repo.snapshot.value.assetsOf(0)!!.first { it.id == id }.isFavorite)
    }

    @Test fun `delete removes optimistically and undo restores in place`() = runBlocking {
        val repo = loadedTimeline()
        val total = repo.snapshot.value.total
        val ids = repo.snapshot.value.assetsOf(1)!!.drop(2).take(3).map { it.id }

        actions.delete(ids, repo)
        assertEquals(total - 3, repo.snapshot.value.total)
        assertEquals("trash ${ids.joinToString(",")}", library.calls.last())
        val snack = snackbar.current!!
        assertEquals("Undo", snack.actionLabel)

        snack.onAction!!()
        assertEquals("restore ${ids.joinToString(",")}", library.calls.last())
        assertEquals(total, repo.snapshot.value.total)
        assertEquals(ids, repo.snapshot.value.assetsOf(1)!!.drop(2).take(3).map { it.id })
    }

    @Test fun `failed delete puts the assets back`() {
        val repo = loadedTimeline()
        val total = repo.snapshot.value.total
        library.fail = RuntimeException("nope")
        actions.delete(listOf(repo.snapshot.value.assetAt(0)!!.id), repo)
        assertEquals(total, repo.snapshot.value.total)
        assertEquals(SnackTone.Error, snackbar.current!!.tone)
    }

    @Test fun `archive from the archive unarchives`() {
        val archive = registry.get(TimelineFilter.Archive).apply { start(); ensureLoaded(0) }
        val id = archive.snapshot.value.assetAt(0)!!.id
        actions.archive(listOf(id), archive)
        assertEquals("setVisibility $id timeline", library.calls.last())
        assertEquals(-1, archive.snapshot.value.indexOf(id))
    }

    @Test fun `new album from the sheet is created with the assets and closes it`() {
        actions.addToAlbum(listOf("a", "b"), null)
        assertTrue(actions.sheet is ActionSheet.AddToAlbum)
        var created: String? = null
        actions.createAlbum("Beach day", listOf("a", "b")) { created = it.name; actions.closeSheet() }
        assertEquals("createAlbum Beach day a,b", library.calls.last())
        assertEquals("Beach day", created)
        assertNull(actions.sheet)
        assertEquals("Beach day", actions.albums.albums.value!!.first().name)
    }

    @Test fun `send file downloads each original then shares them together`() {
        actions.share(listOf("x", "y"), null)
        actions.sendFiles(listOf("x", "y"))
        assertEquals(listOf("download x", "download y"), library.calls.takeLast(2))
        assertEquals(listOf("x.jpg", "y.jpg"), shared)
        assertNull(actions.sheet)
        assertEquals("file", actions.prefs.shareMode)
    }

    @Test fun `share link is created then copied`() {
        actions.shareAlbum("al-2")
        actions.createLink(emptyList(), "al-2", com.roylet.photos.domain.library.ShareLinkOptions(expiresInDays = 7))
        val ready = actions.link as com.roylet.photos.ui.actions.LinkState.Ready
        actions.copyLink(ready.url)
        assertEquals("copy:https://photos.example.com/share/Xy7Lq2mNw0pR4sTuV9aB", shared.single())
        assertEquals("link", actions.prefs.shareMode)
        assertFalse(actions.sheet != null)
    }
}
