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
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import coil3.compose.LocalAsyncImagePreviewHandler
import com.roylet.photos.data.UiPrefs
import com.roylet.photos.domain.library.Album
import com.roylet.photos.domain.library.AlbumStore
import com.roylet.photos.domain.library.EmptyLocalLibrary
import com.roylet.photos.domain.source.ActiveSources
import com.roylet.photos.domain.source.Capabilities
import com.roylet.photos.domain.timeline.TimeBucket
import com.roylet.photos.domain.timeline.TimelineAsset
import com.roylet.photos.domain.timeline.TimelineFilter
import com.roylet.photos.domain.timeline.TimelineRepository
import com.roylet.photos.domain.timeline.TimelineSource
import com.roylet.photos.local.FakeMediaStore
import com.roylet.photos.local.MediaDatabase
import com.roylet.photos.local.MediaIndexer
import com.roylet.photos.local.MediaRequestRunner
import com.roylet.photos.local.MediaStoreLibrary
import com.roylet.photos.local.MediaStoreTimelineSource
import com.roylet.photos.local.LocalMediaUrls as PhoneUrls
import com.roylet.photos.ui.Routes
import com.roylet.photos.ui.albums.AlbumDetailScreen
import com.roylet.photos.ui.albums.AlbumsScreen
import com.roylet.photos.ui.components.BrutalTabBar
import com.roylet.photos.ui.components.LocalReducedMotion
import com.roylet.photos.ui.gallery.LocalMediaUrls
import com.roylet.photos.ui.gallery.PhotosScreen
import com.roylet.photos.ui.gallery.SourceMediaUrls
import com.roylet.photos.ui.library.DeviceScreen
import com.roylet.photos.ui.library.LibraryScreen
import com.roylet.photos.ui.library.TrashScreen
import com.roylet.photos.ui.permissions.DeviceLibraryEmpty
import com.roylet.photos.ui.permissions.LimitedAccessBanner
import com.roylet.photos.ui.permissions.LocalMediaPermissions
import com.roylet.photos.ui.permissions.MediaAccess
import com.roylet.photos.ui.permissions.MediaPermissionsState
import com.roylet.photos.ui.sources.LocalActiveSources
import com.roylet.photos.ui.sources.LocalCapabilities
import com.roylet.photos.ui.tabsFor
import com.roylet.photos.ui.theme.Palette
import com.roylet.photos.ui.theme.PhotosTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.ZoneId

/**
 * The phone's own library through the real index, timeline source and library (a fake MediaStore
 * behind them): the Photos tab, limited access, folders, a folder, the phone's Trash with days
 * left, and Library → On this device with a server.
 *   ./gradlew testDebugUnitTest -Pscreenshots --tests '*DeviceScreenshotTest*'
 * Output: app/build/screenshots/device-*.png (and -dark).
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h880dp-xhdpi")
class DeviceScreenshotTest(private val darkTheme: Boolean) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0}")
        fun themes(): List<Array<Any>> = listOf(arrayOf(false), arrayOf(true))
    }

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("screenshotDir") ?: "build/screenshots")
    private val db = MediaDatabase.inMemory(ApplicationProvider.getApplicationContext())
    private val appDb = com.roylet.photos.local.PhotosDatabase.inMemory(ApplicationProvider.getApplicationContext())
    private val phone = FakeMediaStore()
    private val zone = ZoneId.of("Australia/Melbourne")
    private val indexer = MediaIndexer(db.media(), phone, { MediaAccess.Full }, CoroutineScope(Dispatchers.Unconfined), { zone })
    private val source = MediaStoreTimelineSource(db.media(), appDb.app(), indexer, { MediaAccess.Full }, mediaStoreTrash = true)
    private val library = MediaStoreLibrary(db.media(), appDb.app(), indexer, true, MediaRequestRunner { true }, null, object : com.roylet.photos.local.LocalIo {
        override suspend fun details(item: com.roylet.photos.local.MediaEntity) = throw UnsupportedOperationException()
        override suspend fun copy(item: com.roylet.photos.local.MediaEntity, dest: File, onProgress: (Long, Long) -> Unit) = Unit
    })
    private val unconfined = CoroutineScope(Dispatchers.Unconfined)
    private val day = 86_400_000L

    @Before fun only() = assumeTrue(System.getProperty("screenshots") == "true")

    @Before fun fill() {
        if (System.getProperty("screenshots") != "true") return
        val now = System.currentTimeMillis()
        val folders = listOf(10L to "Camera", 20L to "Screenshots", 30L to "WhatsApp Images", 40L to "Downloads", 50L to "Instagram")
        // Width/height/orientation combos: landscape, portrait, rotated (stored landscape shown portrait), square, panorama.
        val shapes = listOf(Triple(4000, 3000, 0), Triple(3000, 4000, 0), Triple(4000, 3000, 90), Triple(3000, 3000, 0), Triple(6000, 2500, 0), Triple(1080, 2340, 0))
        var id = 1L
        for (m in 0 until 7) {
            val count = intArrayOf(19, 27, 11, 33, 16, 24, 9)[m]
            for (i in 0 until count) {
                val taken = now - (m * 31L + i) * day - i * 3_600_000L
                val (bucket, name) = folders[if (id % 3 == 0L) 0 else (id % folders.size).toInt()]
                val (w, h, o) = shapes[(id % shapes.size).toInt()]
                val video = id % 9 == 0L
                val row = (if (video) FakeMediaStore.video(id, taken) else FakeMediaStore.photo(id, taken, bucket, name))
                    .copy(width = w, height = h, orientation = o, bucketId = bucket, bucketName = name, relativePath = if (name == "Camera") "DCIM/Camera/" else "Pictures/$name/", isFavorite = id % 11 == 0L)
                phone.put(row)
                id++
            }
        }
        // The phone's Trash: deleted over the last month, so some are about to go for good.
        for (i in 0 until 14) {
            val left = intArrayOf(29, 27, 26, 21, 18, 15, 12, 9, 7, 5, 3, 2, 1, 1)[i]
            phone.put(FakeMediaStore.photo(id++, now - (40 + i * 13) * day, 10, "Camera").copy(isTrashed = true, dateExpires = (now + left * day - 3_600_000L) / 1000))
        }
        runBlocking { indexer.sync() }
    }

    @After fun close() = db.close()

    /** The real source's answers for [filter], served without suspending so the first frame has them. */
    private fun repo(filter: TimelineFilter): TimelineRepository {
        val buckets = runBlocking { source.buckets(filter) }
        val assets = buckets.associate { b -> b.key to runBlocking { source.bucket(filter, b.key) } }
        val pre = object : TimelineSource {
            override suspend fun buckets(filter: TimelineFilter): List<TimeBucket> = buckets
            override suspend fun bucket(filter: TimelineFilter, key: String): List<TimelineAsset> = assets[key].orEmpty()
        }
        return TimelineRepository(pre, filter, unconfined, prefetch = 3).apply { start(); buckets.indices.forEach { ensureLoaded(it) } }
    }

    private fun folderStore(): AlbumStore {
        val albums = runBlocking { library.albums() }
        val fixed = object : EmptyLocalLibrary() { override suspend fun albums(): List<Album> = albums }
        return AlbumStore({ fixed }, unconfined).apply { refresh() }
    }

    private fun shoot(
        name: String,
        sources: ActiveSources = ActiveSources.LocalOnly,
        permissions: MediaPermissionsState = MediaPermissionsState(),
        content: @Composable () -> Unit,
    ) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(
                LocalInspectionMode provides true,
                LocalAsyncImagePreviewHandler provides FakeGallery.previewHandler,
                LocalReducedMotion provides true,
                LocalActiveSources provides sources,
                LocalCapabilities provides sources.capabilities,
                LocalMediaPermissions provides permissions,
                LocalMediaUrls provides SourceMediaUrls(immich = null, local = PhoneUrls),
            ) { PhotosTheme(dark = darkTheme) { content() } }
        }
        compose.mainClock.advanceTimeBy(1500)
        val view = compose.activity.window.decorView.rootView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        outDir.mkdirs()
        File(outDir, "${if (darkTheme) "$name-dark" else name}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Composable
    private fun WithTabs(route: String, caps: Capabilities = Capabilities.Local, content: @Composable () -> Unit) {
        Column(Modifier.fillMaxSize().background(Palette.paper)) {
            Box(Modifier.weight(1f)) { content() }
            BrutalTabBar(tabsFor(caps), route, {})
        }
    }

    @Test fun timeline() {
        val t = repo(TimelineFilter.Timeline)
        shoot("device-01-timeline") {
            WithTabs(Routes.TIMELINE) { PhotosScreen(t, onOpen = { _, _ -> }, onSettings = {}, emptyContent = { DeviceLibraryEmpty(false, {}) }) }
        }
    }

    @Test fun limited() {
        val t = repo(TimelineFilter.Timeline)
        shoot("device-02-limited-access", permissions = MediaPermissionsState(MediaAccess.Partial)) {
            WithTabs(Routes.TIMELINE) { PhotosScreen(t, onOpen = { _, _ -> }, onSettings = {}, banner = { LimitedAccessBanner(onManage = {}) }) }
        }
    }

    @Test fun folders() {
        val store = folderStore()
        shoot("device-03-folders") { WithTabs(Routes.ALBUMS) { AlbumsScreen(store, null, UiPrefs(null), onOpen = {}, folders = true) } }
    }

    @Test fun folder() {
        val store = folderStore()
        val t = repo(TimelineFilter.album("folder:20"))
        shoot("device-04-folder") { AlbumDetailScreen("folder:20", store, t, null, { _, _ -> }, onBack = {}) }
    }

    @Test fun trash() {
        val t = repo(TimelineFilter.Trash)
        shoot("device-05-trash") { TrashScreen(t, { library }, null, { _, _ -> }, onBack = {}) }
    }

    @Test fun libraryWithServer() = shoot("device-06-library-with-server", sources = ActiveSources.ImmichOnly) {
        WithTabs(Routes.LIBRARY, Capabilities.Immich) { LibraryScreen(onOpen = {}) }
    }

    @Test fun onThisDevice() {
        val t = repo(TimelineFilter.Device)
        shoot("device-07-on-this-device", sources = ActiveSources.ImmichOnly) {
            DeviceScreen(t, { _, _ -> }, onFolders = {}, onTrash = {}, onBack = {}, onAccessChanged = {})
        }
    }

    @Test fun deviceFoldersWithServer() {
        val store = folderStore()
        shoot("device-08-folders-with-server", sources = ActiveSources.ImmichOnly) {
            AlbumsScreen(store, null, UiPrefs(null), onOpen = {}, folders = true, onBack = {})
        }
    }
}
