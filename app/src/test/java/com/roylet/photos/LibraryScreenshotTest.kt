package com.roylet.photos

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import coil3.compose.LocalAsyncImagePreviewHandler
import com.roylet.photos.data.ServerConfig
import com.roylet.photos.data.UiPrefs
import com.roylet.photos.domain.library.AlbumStore
import com.roylet.photos.domain.library.Memory
import com.roylet.photos.domain.timeline.TimelineFilter
import com.roylet.photos.domain.timeline.TimelineRegistry
import com.roylet.photos.ui.AppNav
import com.roylet.photos.ui.Routes
import com.roylet.photos.ui.Tabs
import com.roylet.photos.ui.actions.ActionSheets
import com.roylet.photos.ui.actions.LibraryActions
import com.roylet.photos.ui.actions.LocalLibraryActions
import com.roylet.photos.ui.actions.ShareLauncher
import com.roylet.photos.ui.albums.AlbumDetailScreen
import com.roylet.photos.ui.albums.AlbumsScreen
import com.roylet.photos.ui.components.BrutalRail
import com.roylet.photos.ui.components.BrutalSnackbarHost
import com.roylet.photos.ui.components.BrutalTabBar
import com.roylet.photos.ui.components.LocalReducedMotion
import com.roylet.photos.ui.components.LocalSnackbar
import com.roylet.photos.ui.components.SnackbarController
import com.roylet.photos.ui.components.AppMark
import com.roylet.photos.ui.gallery.LocalAssetActions
import com.roylet.photos.ui.gallery.PhotosScreen
import com.roylet.photos.ui.library.LibraryScreen
import com.roylet.photos.ui.library.TrashScreen
import com.roylet.photos.ui.memories.MemoriesCarousel
import com.roylet.photos.ui.memories.MemoryStoryScreen
import com.roylet.photos.ui.search.SearchModel
import com.roylet.photos.ui.search.SearchQuery
import com.roylet.photos.ui.search.SearchScreen
import com.roylet.photos.ui.theme.Brutal
import com.roylet.photos.ui.theme.Palette
import com.roylet.photos.ui.theme.PhotosTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate

/**
 * Albums, sheets, search, library, trash and memories with fake data:
 *   ./gradlew testDebugUnitTest -Pscreenshots --tests '*LibraryScreenshotTest*'
 * Output: app/build/screenshots/library-*.png
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h880dp-xhdpi")
class LibraryScreenshotTest(private val darkTheme: Boolean) {
    companion object {
        /** Every screen is shot in the light theme and again in dark (as name-dark.png). */
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0}")
        fun themes(): List<Array<Any>> = listOf(arrayOf(false), arrayOf(true))
    }

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val app get() = ApplicationProvider.getApplicationContext<PhotosApp>()
    private val outDir = File(System.getProperty("screenshotDir") ?: "build/screenshots")
    private val library = FakeLibrary()
    private val snackbar = SnackbarController()
    private val unconfined = CoroutineScope(Dispatchers.Unconfined)
    private val registry = TimelineRegistry(FakeGallery.source).apply { context = Dispatchers.Unconfined }
    private val store = AlbumStore({ library }, unconfined).apply { refresh() }
    private val actions by lazy {
        LibraryActions(
            { library }, store, snackbar, unconfined, favoriteCall = { _, _ -> },
            launcher = object : ShareLauncher {
                override fun sendFiles(files: List<File>) {}
                override fun sendText(text: String) {}
                override fun copy(text: String) {}
            },
            prefs = UiPrefs(null), shareDir = File(System.getProperty("java.io.tmpdir"), "share-test"), timelines = registry,
        )
    }

    @Before fun only() = assumeTrue(System.getProperty("screenshots") == "true")

    @After fun signOut() = app.container.settings.signOut()

    private fun shoot(name: String, dark: Boolean = darkTheme, before: () -> Unit = {}, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(
                LocalInspectionMode provides true,
                LocalAsyncImagePreviewHandler provides FakeGallery.previewHandler,
                LocalReducedMotion provides true,
                LocalAssetActions provides actions,
                LocalLibraryActions provides actions,
                LocalSnackbar provides snackbar,
            ) {
                PhotosTheme(dark = dark) {
                    Box(Modifier.fillMaxSize()) {
                        content()
                        ActionSheets(actions)
                        BrutalSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 96.dp))
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(300)
        before()
        save(if (darkTheme) "$name-dark" else name)
    }

    private fun save(name: String) {
        compose.mainClock.advanceTimeBy(1500)
        val view = compose.activity.window.decorView.rootView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        outDir.mkdirs()
        File(outDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Composable
    private fun WithTabs(route: String, content: @Composable () -> Unit) {
        Column(Modifier.fillMaxSize().background(Palette.paper)) {
            Box(Modifier.weight(1f)) { content() }
            BrutalTabBar(Tabs, route, {})
        }
    }

    private fun repo(filter: TimelineFilter = TimelineFilter.Timeline) = registry.get(filter).apply {
        start(); FakeGallery.buckets.indices.forEach { ensureLoaded(it) }
    }

    @Test fun albumsTab() = shoot("library-01-albums") {
        WithTabs(Routes.ALBUMS) { AlbumsScreen(store, actions, UiPrefs(null), onOpen = {}) }
    }

    @Test @Config(qualifiers = "w900dp-h600dp-xhdpi")
    fun albumsTablet() = shoot("library-02-albums-tablet") {
        Row(Modifier.fillMaxSize()) {
            BrutalRail(Tabs, Routes.ALBUMS, {}, header = { AppMark() })
            AlbumsScreen(store, actions, UiPrefs(null), onOpen = {}, modifier = Modifier.weight(1f))
        }
    }

    @Test fun albumDetail() {
        val timeline = repo(TimelineFilter.album("al-1"))
        shoot("library-03-album-detail") { AlbumDetailScreen("al-1", store, timeline, actions, onOpen = { _, _ -> }, onBack = {}) }
    }

    @Test fun addToAlbumSheet() {
        val timeline = repo()
        val ids = timeline.snapshot.value.assetsOf(0)!!.take(3).map { it.id }
        shoot("library-04-add-to-album", before = { actions.addToAlbum(ids, timeline) }) {
            WithTabs(Routes.TIMELINE) { PhotosScreen(timeline, onOpen = { _, _ -> }, onSettings = {}) }
        }
    }

    @Test fun shareSheet() {
        val timeline = repo()
        val ids = timeline.snapshot.value.assetsOf(0)!!.take(2).map { it.id }
        actions.prefs.shareMode = "link"
        shoot("library-05-share-link", before = { actions.share(ids, timeline) }) {
            WithTabs(Routes.TIMELINE) { PhotosScreen(timeline, onOpen = { _, _ -> }, onSettings = {}) }
        }
    }

    @Test fun shareSheetFile() {
        val timeline = repo()
        val ids = timeline.snapshot.value.assetsOf(0)!!.take(4).map { it.id }
        actions.prefs.shareMode = "file"
        shoot("library-06-share-file", before = { actions.share(ids, timeline) }) {
            WithTabs(Routes.TIMELINE) { PhotosScreen(timeline, onOpen = { _, _ -> }, onSettings = {}) }
        }
    }

    @Test fun searchDiscover() {
        val prefs = UiPrefs(null).apply { addRecentSearch("Snowy mountains"); addRecentSearch("Birthday cake"); addRecentSearch("Dog at the beach") }
        val model = SearchModel({ library }, registry, prefs, unconfined)
        shoot("library-07-search") {
            WithTabs(Routes.SEARCH) { SearchScreen(model, { registry.get(it) }, { _, _, _ -> }, {}, {}) }
        }
    }

    @Test fun searchResults() {
        val model = SearchModel({ library }, registry, UiPrefs(null), unconfined)
        val results = runBlocking { library.smartSearch("sunset at the beach") }
        model.showResults(SearchQuery.Text("sunset at the beach"), results)
        shoot("library-08-search-results") {
            WithTabs(Routes.SEARCH) { SearchScreen(model, { registry.get(it) }, { _, _, _ -> }, {}, {}) }
        }
    }

    @Test fun libraryTab() = shoot("library-09-library") { WithTabs(Routes.LIBRARY) { LibraryScreen(onOpen = {}) } }

    @Test fun trash() {
        val timeline = repo(TimelineFilter.Trash)
        shoot("library-10-trash") { TrashScreen(timeline, { library }, actions, onOpen = { _, _ -> }, onBack = {}) }
    }

    @Test fun memoriesCarousel() {
        val timeline = repo()
        val memories = runBlocking { library.memories(LocalDate.of(2026, 11, 4)) }
        shoot("library-11-memories-carousel") {
            WithTabs(Routes.TIMELINE) {
                PhotosScreen(timeline, onOpen = { _, _ -> }, onSettings = {}, topContent = { MemoriesCarousel(memories, onOpen = {}, today = LocalDate.of(2026, 11, 4)) })
            }
        }
    }

    @Test fun memoryStory() {
        assumeFalse(darkTheme) // Always dark.
        val memories: List<Memory> = runBlocking { library.memories(LocalDate.of(2026, 11, 4)) }
        shoot("library-12-memory-story", dark = true) {
            MemoryStoryScreen(memories, 1, onClose = {}, today = LocalDate.of(2026, 11, 4))
        }
    }

    @Test fun snackbar() {
        val timeline = repo()
        shoot("library-13-snackbar", before = { snackbar.show("Moved 3 items to Trash", actionLabel = "Undo") }) {
            WithTabs(Routes.TIMELINE) { PhotosScreen(timeline, onOpen = { _, _ -> }, onSettings = {}) }
        }
    }

    @Test fun sharedAlbum() {
        val timeline = repo(TimelineFilter.album("al-2"))
        shoot("library-14-album-shared") { AlbumDetailScreen("al-2", store, timeline, actions, onOpen = { _, _ -> }, onBack = {}) }
    }

    /** Through the real app: open a photo, then drag it halfway down. The grid must show behind it. */
    @Test fun viewerDismissRevealsGrid() {
        assumeFalse(darkTheme)
        app.container.settings.saveServer(ServerConfig(publicUrl = "https://photos.example.com", apiKey = "k", userId = "u-1", email = "me@example.com"))
        FakeGallery.install(app.container)
        app.container.library = library
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true, LocalAsyncImagePreviewHandler provides FakeGallery.previewHandler, LocalReducedMotion provides true) {
                PhotosTheme { AppNav(app.container) }
            }
        }
        compose.mainClock.advanceTimeBy(1500)
        compose.onRoot().performTouchInput { click(Offset(width * 0.3f, height * 0.42f)) }
        compose.mainClock.advanceTimeBy(800)
        save("library-15-viewer-open")
        compose.onRoot().performTouchInput {
            down(Offset(width * 0.5f, height * 0.4f))
            repeat(12) { moveBy(Offset(4f, 28f)); advanceEventTime(16) }
        }
        compose.mainClock.advanceTimeBy(200)
        save("library-16-viewer-dismissing")
        compose.onRoot().performTouchInput { up() }
    }

}
