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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import coil3.compose.LocalAsyncImagePreviewHandler
import com.roylet.photos.domain.library.DeviceFolderStore
import com.roylet.photos.domain.library.FolderPrefs
import com.roylet.photos.domain.source.ActiveSources
import com.roylet.photos.domain.source.Capabilities
import com.roylet.photos.domain.source.MediaSourceKind
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
import com.roylet.photos.local.PhotosDatabase
import com.roylet.photos.merge.Match
import com.roylet.photos.merge.MergedTimelineSource
import com.roylet.photos.merge.MonthMatcher
import com.roylet.photos.ui.Routes
import com.roylet.photos.ui.components.BrutalTabBar
import com.roylet.photos.ui.components.LocalReducedMotion
import com.roylet.photos.ui.gallery.LocalMediaUrls
import com.roylet.photos.ui.gallery.SourceMediaUrls
import com.roylet.photos.ui.library.DeviceFolderScreen
import com.roylet.photos.ui.library.DeviceFoldersScreen
import com.roylet.photos.ui.library.FolderMenuSheet
import com.roylet.photos.ui.library.LibraryScreen
import com.roylet.photos.ui.permissions.LocalMediaPermissions
import com.roylet.photos.ui.permissions.MediaAccess
import com.roylet.photos.ui.permissions.MediaPermissionsState
import com.roylet.photos.ui.sources.LocalActiveSources
import com.roylet.photos.ui.sources.LocalCapabilities
import com.roylet.photos.ui.sources.LocalShowSourceBadges
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
import com.roylet.photos.local.LocalMediaUrls as PhoneUrls

/**
 * Library → On this device through the real index and folder query (a fake MediaStore behind
 * them): the Library tab's section with and without Immich, VIEW ALL's grid (phone and tablet,
 * with a hidden folder), a folder's menu, a folder's page with backup badges, and no access.
 *   ./gradlew testDebugUnitTest -Pscreenshots --tests '*DeviceFoldersScreenshotTest*'
 * Output: app/build/screenshots/folders-*.png (and -dark).
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h880dp-xhdpi")
class DeviceFoldersScreenshotTest(private val darkTheme: Boolean) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0}")
        fun themes(): List<Array<Any>> = listOf(arrayOf(false), arrayOf(true))

        private val Merged = ActiveSources(setOf(MediaSourceKind.Local, MediaSourceKind.Immich), Capabilities.Local union Capabilities.Immich)
    }

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("screenshotDir") ?: "build/screenshots")
    private val db = MediaDatabase.inMemory(ApplicationProvider.getApplicationContext())
    private val appDb = PhotosDatabase.inMemory(ApplicationProvider.getApplicationContext())
    private val phone = FakeMediaStore()
    private val zone = ZoneId.of("Australia/Melbourne")
    private val unconfined = CoroutineScope(Dispatchers.Unconfined)
    private val indexer = MediaIndexer(db.media(), phone, { MediaAccess.Full }, unconfined, { zone })
    private val source = MediaStoreTimelineSource(db.media(), appDb.app(), indexer, { MediaAccess.Full }, mediaStoreTrash = true)
    private val library = MediaStoreLibrary(db.media(), appDb.app(), indexer, true, MediaRequestRunner { true }, null, object : com.roylet.photos.local.LocalIo {
        override suspend fun details(item: com.roylet.photos.local.MediaEntity) = throw UnsupportedOperationException()
        override suspend fun copy(item: com.roylet.photos.local.MediaEntity, dest: File, onProgress: (Long, Long) -> Unit) = Unit
    })
    private val day = 86_400_000L

    @Before fun only() = assumeTrue(System.getProperty("screenshots") == "true")

    @Before fun fill() {
        if (System.getProperty("screenshots") != "true") return
        val now = System.currentTimeMillis()
        // (bucket, name, path, items, days since the newest)
        val folders = listOf(
            Folder(10, "Camera", "DCIM/Camera/", 64, 0),
            Folder(20, "Screenshots", "Pictures/Screenshots/", 23, 2),
            Folder(30, "WhatsApp Images", "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/", 41, 1),
            Folder(40, "Download", "Download/", 9, 6),
            Folder(50, "Instagram", "Pictures/Instagram/", 12, 4),
            Folder(60, "ScreenRecords", "DCIM/ScreenRecords/", 4, 9, video = true),
            Folder(70, "Photos Edited", "Pictures/Photos Edited/", 7, 3),
            Folder(80, "Camera", "Pictures/Camera/", 5, 30),
            Folder(90, "Telegram Images", "Pictures/Telegram/", 6, 12),
            Folder(100, "Memes", "Pictures/Memes/", 18, 5),
        )
        val shapes = listOf(Triple(4000, 3000, 0), Triple(3000, 4000, 0), Triple(4000, 3000, 90), Triple(3000, 3000, 0), Triple(1080, 2340, 0))
        var id = 1L
        for (f in folders) {
            for (i in 0 until f.items) {
                val taken = now - (f.newestDaysAgo + i * 2L) * day - i * 3_600_000L
                val (w, h, o) = if (f.bucket == 20L) Triple(1080, 2340, 0) else shapes[(id % shapes.size).toInt()]
                val base = if (f.video || id % 13 == 0L) FakeMediaStore.video(id, taken) else FakeMediaStore.photo(id, taken)
                phone.put(base.copy(width = w, height = h, orientation = o, bucketId = f.bucket, bucketName = f.name, relativePath = f.path))
                id++
            }
        }
        runBlocking { indexer.sync() }
    }

    private data class Folder(val bucket: Long, val name: String, val path: String, val items: Int, val newestDaysAgo: Int, val video: Boolean = false)

    @After fun close() { db.close(); appDb.close() }

    private fun store(prefs: FolderPrefs = FolderPrefs()) = DeviceFolderStore({ library.deviceFolders() }, prefs, scope = unconfined).apply { start() }

    /** The real source's answers for [filter] (through [via] when given), served without suspending. */
    private fun repo(filter: TimelineFilter, via: TimelineSource = source): TimelineRepository {
        val buckets = runBlocking { via.buckets(filter) }
        val assets = buckets.associate { b -> b.key to runBlocking { via.bucket(filter, b.key) } }
        val pre = object : TimelineSource {
            override suspend fun buckets(filter: TimelineFilter): List<TimeBucket> = buckets
            override suspend fun bucket(filter: TimelineFilter, key: String): List<TimelineAsset> = assets[key].orEmpty()
        }
        return TimelineRepository(pre, filter, unconfined, prefetch = 3).apply { start(); buckets.indices.forEach { ensureLoaded(it) } }
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
                LocalShowSourceBadges provides (sources.hasLocal && sources.hasImmich),
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
    private fun WithTabs(caps: Capabilities, content: @Composable () -> Unit) {
        Column(Modifier.fillMaxSize().background(Palette.paper)) {
            Box(Modifier.weight(1f)) { content() }
            BrutalTabBar(tabsFor(caps), Routes.LIBRARY, {})
        }
    }

    @Test fun library() {
        val s = store()
        shoot("folders-01-library") { WithTabs(Capabilities.Local) { LibraryScreen(onOpen = {}, folders = s) } }
    }

    @Test fun libraryWithImmich() {
        val s = store()
        shoot("folders-02-library-immich", sources = Merged) { WithTabs(Merged.capabilities) { LibraryScreen(onOpen = {}, folders = s) } }
    }

    @Test fun allFolders() {
        val s = store(FolderPrefs(hidden = setOf(100L, 90L)))
        shoot("folders-03-all") { DeviceFoldersScreen(s, onFolder = {}, onBack = {}, onAccessChanged = {}) }
    }

    @Test @Config(qualifiers = "w900dp-h600dp-xhdpi")
    fun allFoldersTablet() {
        val s = store()
        shoot("folders-04-all-tablet", sources = Merged) {
            DeviceFoldersScreen(s, onFolder = {}, onBack = {}, onAccessChanged = {}, onPhoneTimeline = {}, onPhoneTrash = {})
        }
    }

    @Test fun folderMenu() {
        val s = store(FolderPrefs(notInPhotos = setOf(100L)))
        shoot("folders-05-menu") {
            val prefs by s.prefs.collectAsState()
            Box(Modifier.fillMaxSize()) {
                DeviceFoldersScreen(s, onFolder = {}, onBack = {}, onAccessChanged = {})
                FolderMenuSheet(s.find(100), prefs, onDismiss = {}, onHidden = { _, _ -> }, onShowInPhotos = { _, _ -> })
            }
        }
    }

    @Test fun folderWithBackupBadges() {
        val s = store()
        // Two in three camera photos are already in Immich (a cached SHA-1 match).
        val matcher = object : MonthMatcher {
            override suspend fun matchMonth(bucketKey: String, localIds: List<Long>, remoteIds: Set<String>) = emptyMap<Long, Match>()
            override suspend fun cachedRemote(localIds: List<Long>) = localIds.filter { it % 3 != 0L }.associateWith { Match("remote-$it", false) }
            override suspend fun cachedLocal(remoteIds: List<String>) = emptyMap<String, Long>()
            override suspend fun pairsPerMonth() = emptyMap<String, Int>()
        }
        val merged = MergedTimelineSource(source, source) { matcher }
        val t = repo(TimelineFilter.album("folder:10"), merged)
        shoot("folders-06-folder", sources = Merged) { DeviceFolderScreen(10, s, t, { _, _ -> }, onBack = {}) }
    }

    @Test fun folderNotInPhotos() {
        val s = store(FolderPrefs(notInPhotos = setOf(30L), hidden = setOf(30L)))
        val t = repo(TimelineFilter.album("folder:30"))
        shoot("folders-07-folder-not-in-photos") { DeviceFolderScreen(30, s, t, { _, _ -> }, onBack = {}) }
    }

    @Test fun noAccess() {
        val s = store()
        shoot("folders-08-library-no-access", permissions = MediaPermissionsState(MediaAccess.Denied)) {
            WithTabs(Capabilities.Local) { LibraryScreen(onOpen = {}, folders = s) }
        }
    }

    @Test fun limitedAccess() {
        val s = store()
        shoot("folders-09-library-limited", sources = Merged, permissions = MediaPermissionsState(MediaAccess.Partial)) {
            WithTabs(Merged.capabilities) { LibraryScreen(onOpen = {}, folders = s) }
        }
    }
}
