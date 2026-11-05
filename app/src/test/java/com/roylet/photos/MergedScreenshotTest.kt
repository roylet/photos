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
import coil3.compose.LocalAsyncImagePreviewHandler
import com.roylet.photos.data.ConnectionMode
import com.roylet.photos.data.ServerConfig
import com.roylet.photos.data.UiPrefs
import com.roylet.photos.domain.library.AlbumStore
import com.roylet.photos.domain.library.DeleteTarget
import com.roylet.photos.domain.library.LibrarySource
import com.roylet.photos.domain.library.TargetedTrash
import com.roylet.photos.domain.library.TrashReceipt
import com.roylet.photos.domain.source.ActiveSources
import com.roylet.photos.domain.source.AssetRef
import com.roylet.photos.domain.source.Capabilities
import com.roylet.photos.domain.source.MediaSourceKind
import com.roylet.photos.domain.timeline.TimelineAsset
import com.roylet.photos.domain.timeline.TimelineFilter
import com.roylet.photos.domain.timeline.TimelineRepository
import com.roylet.photos.domain.timeline.TimelineSource
import com.roylet.photos.local.Sha1Progress
import com.roylet.photos.net.ConnState
import com.roylet.photos.net.ScanState
import com.roylet.photos.ui.Routes
import com.roylet.photos.ui.actions.ActionSheets
import com.roylet.photos.ui.actions.LibraryActions
import com.roylet.photos.ui.actions.ShareLauncher
import com.roylet.photos.ui.albums.AlbumsScreen
import com.roylet.photos.ui.components.BrutalTabBar
import com.roylet.photos.ui.components.LocalReducedMotion
import com.roylet.photos.ui.components.SnackbarController
import com.roylet.photos.ui.gallery.FakeMediaUrls
import com.roylet.photos.ui.gallery.LocalMediaUrls
import com.roylet.photos.ui.gallery.MediaUrls
import com.roylet.photos.ui.gallery.PhotosScreen
import com.roylet.photos.ui.permissions.LocalMediaPermissions
import com.roylet.photos.ui.permissions.MediaAccess
import com.roylet.photos.ui.permissions.MediaPermissionsState
import com.roylet.photos.ui.settings.SettingsContent
import com.roylet.photos.ui.sources.LocalActiveSources
import com.roylet.photos.ui.sources.LocalCapabilities
import com.roylet.photos.ui.sources.LocalShowSourceBadges
import com.roylet.photos.ui.tabsFor
import com.roylet.photos.ui.theme.Palette
import com.roylet.photos.ui.theme.PhotosTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The phone and Immich in one app: the merged timeline with backup badges, the delete choice,
 * the albums + folders tab and Settings' matching progress.
 *   ./gradlew testDebugUnitTest -Pscreenshots --tests '*MergedScreenshotTest*'
 * Output: app/build/screenshots/merged-*.png (and -dark).
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h880dp-xhdpi")
class MergedScreenshotTest(private val darkTheme: Boolean) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0}")
        fun themes(): List<Array<Any>> = listOf(arrayOf(false), arrayOf(true))

        /**
         * FakeGallery's assets as a merged timeline: most are on both sides (phone id, both refs),
         * some only on the phone (not backed up yet), some only in Immich (older, cleared from the phone).
         */
        fun merged(a: TimelineAsset, i: Int): TimelineAsset = when {
            i % 7 == 2 || i % 11 == 5 -> a.copy(id = "local:${a.id}") // only on this phone
            i % 6 == 4 -> a // only in Immich
            else -> a.copy(id = "local:${a.id}", localRef = AssetRef(MediaSourceKind.Local, a.id), remoteRef = AssetRef.immich(a.id))
        }

        /** The fake pictures, whatever the prefix. */
        val urls = object : MediaUrls {
            private fun k(id: String) = id.removePrefix("local:")
            override fun thumbnail(id: String) = FakeMediaUrls.thumbnail(k(id))
            override fun preview(id: String) = FakeMediaUrls.preview(k(id))
            override fun fullsize(id: String) = FakeMediaUrls.fullsize(k(id))
            override fun original(id: String) = FakeMediaUrls.original(k(id))
            override fun video(id: String) = FakeMediaUrls.video(k(id))
        }

        val source = object : TimelineSource {
            override suspend fun buckets(filter: TimelineFilter) = FakeGallery.buckets
            override suspend fun bucket(filter: TimelineFilter, key: String) = FakeGallery.assetsOf(key).mapIndexed { i, a -> merged(a, i) }
        }
    }

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("screenshotDir") ?: "build/screenshots")
    private val both = ActiveSources(setOf(MediaSourceKind.Local, MediaSourceKind.Immich), Capabilities.Immich union Capabilities.Local)

    @Before fun only() = assumeTrue(System.getProperty("screenshots") == "true")

    private fun shoot(name: String, badges: Boolean = true, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(
                LocalInspectionMode provides true,
                LocalAsyncImagePreviewHandler provides FakeGallery.previewHandler,
                LocalReducedMotion provides true,
                LocalActiveSources provides both,
                LocalCapabilities provides both.capabilities,
                LocalShowSourceBadges provides badges,
                LocalMediaUrls provides urls,
                LocalMediaPermissions provides MediaPermissionsState(MediaAccess.Full, canManageMedia = false, onManageMedia = {}),
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
    private fun WithTabs(route: String, content: @Composable () -> Unit) {
        Column(Modifier.fillMaxSize().background(Palette.paper)) {
            Box(Modifier.weight(1f)) { content() }
            BrutalTabBar(tabsFor(both.capabilities), route, {})
        }
    }

    private fun repo() = TimelineRepository(source, TimelineFilter.Timeline, CoroutineScope(Dispatchers.Unconfined)).apply {
        start(); FakeGallery.buckets.indices.take(3).forEach { ensureLoaded(it) }
    }

    @Test fun timeline() = shoot("merged-01-timeline") {
        WithTabs(Routes.TIMELINE) { PhotosScreen(repo(), onOpen = { _, _ -> }, onSettings = {}) }
    }

    @Test fun timelineWithoutBadges() = shoot("merged-02-timeline-no-badges", badges = false) {
        WithTabs(Routes.TIMELINE) { PhotosScreen(repo(), onOpen = { _, _ -> }, onSettings = {}) }
    }

    @Test fun deleteChoice() {
        val repo = repo()
        val first = repo.snapshot.value.assetsOf(0)!!
        val base = FakeLibrary()
        val lib = object : LibrarySource by base, TargetedTrash {
            override suspend fun pairedIds(ids: List<String>) = ids.filter { a -> first.any { it.id == a && it.remoteRef != null } }.toSet()
            override suspend fun trash(ids: List<String>, target: DeleteTarget) = TrashReceipt(ids, emptyList())
            override suspend fun restore(receipt: TrashReceipt) {}
        }
        val actions = LibraryActions(
            library = { lib },
            albums = AlbumStore({ lib }, CoroutineScope(Dispatchers.Unconfined)),
            snackbar = SnackbarController(),
            scope = CoroutineScope(Dispatchers.Unconfined),
            launcher = object : ShareLauncher {
                override fun sendFiles(files: List<File>) {}
                override fun sendText(text: String) {}
                override fun copy(text: String) {}
            },
            prefs = UiPrefs(null),
            shareDir = File("build/tmp"),
        )
        actions.delete(first.take(3).map { it.id }, repo)
        shoot("merged-03-delete-choice") {
            Box(Modifier.fillMaxSize()) {
                WithTabs(Routes.TIMELINE) { PhotosScreen(repo, onOpen = { _, _ -> }, onSettings = {}) }
                ActionSheets(actions)
            }
        }
    }

    @Test fun albumsAndFolders() {
        val phone = object : com.roylet.photos.domain.library.EmptyLocalLibrary() {
            override suspend fun albums() = listOf(
                com.roylet.photos.domain.library.Album("folder:1", "Camera", assetCount = 4_212, coverAssetId = FakeGallery.assetsOf(FakeGallery.buckets[0].key)[2].id),
                com.roylet.photos.domain.library.Album("folder:2", "Screenshots", assetCount = 318, coverAssetId = FakeGallery.assetsOf(FakeGallery.buckets[1].key)[4].id),
                com.roylet.photos.domain.library.Album("folder:3", "WhatsApp Images", assetCount = 96, coverAssetId = FakeGallery.assetsOf(FakeGallery.buckets[2].key)[1].id),
            )
        }
        val lib = com.roylet.photos.merge.MergedLibrary(FakeLibrary(), phone) { com.roylet.photos.merge.NoPairs }
        val store = AlbumStore({ lib }, CoroutineScope(Dispatchers.Unconfined)).apply { refresh() }
        shoot("merged-04-albums-and-folders") {
            WithTabs(Routes.ALBUMS) { AlbumsScreen(store, null, UiPrefs(null), onOpen = {}) }
        }
    }

    private val sample = ServerConfig(
        publicUrl = "https://photos.example.com", localUrl = "http://192.168.1.20:2283", apiKey = "k",
        userId = "u-1", email = "me@example.com", name = "Roy", mode = ConnectionMode.AUTO,
    )

    @Composable
    private fun Settings(matching: Sha1Progress?) = SettingsContent(
        server = sample, state = ConnState.Home, activeUrl = sample.localUrl.orEmpty(),
        scan = ScanState.Idle, onBack = {}, onMode = {}, onSavePublic = { true }, onSaveLocal = { com.roylet.photos.net.LocalUrlResult.Cleared },
        onScan = {}, onSignOut = {}, matching = matching,
    )

    @Test fun settingsMatching() = shoot("merged-05-settings-matching") { Settings(Sha1Progress(1_234, 5_000)) }

    @Test fun settingsMatched() = shoot("merged-06-settings-matched") { Settings(Sha1Progress(5_000, 5_000)) }
}
