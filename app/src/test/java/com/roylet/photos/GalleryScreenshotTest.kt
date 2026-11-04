package com.roylet.photos

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import coil3.compose.LocalAsyncImagePreviewHandler
import com.roylet.photos.data.ServerConfig
import com.roylet.photos.ui.AppNav
import com.roylet.photos.ui.Routes
import com.roylet.photos.ui.Tabs
import com.roylet.photos.ui.components.BrutalTabBar
import com.roylet.photos.ui.components.LocalReducedMotion
import com.roylet.photos.ui.gallery.GridDensity
import com.roylet.photos.ui.gallery.GridSelection
import com.roylet.photos.ui.gallery.TimelineGrid
import com.roylet.photos.ui.components.ScreenHeader
import com.roylet.photos.ui.gallery.PhotosScreen
import com.roylet.photos.ui.theme.Brutal
import com.roylet.photos.ui.theme.BrutalDark
import com.roylet.photos.ui.theme.PhotosTheme
import com.roylet.photos.ui.viewer.ViewerScreen
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Timeline grid and viewer with a fake library (see [FakeGallery]):
 *   ./gradlew testDebugUnitTest -Pscreenshots --tests '*GalleryScreenshotTest*'
 * Output: app/build/screenshots/gallery-*.png
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h880dp-xhdpi")
class GalleryScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val app get() = ApplicationProvider.getApplicationContext<PhotosApp>()
    private val outDir = File(System.getProperty("screenshotDir") ?: "build/screenshots")

    @Before fun only() = assumeTrue(System.getProperty("screenshots") == "true")

    @After fun signOut() = app.container.settings.signOut()

    private fun shoot(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            // Reduced motion: Coil's crossfade runs on wall-clock time, which the paused test clock can't advance.
            CompositionLocalProvider(LocalInspectionMode provides true, LocalAsyncImagePreviewHandler provides FakeGallery.previewHandler, LocalReducedMotion provides true) {
                PhotosTheme(dark = dark) { content() }
            }
        }
        compose.mainClock.advanceTimeBy(1500)
        val view = compose.activity.window.decorView.rootView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        outDir.mkdirs()
        File(outDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun timelinePhone() {
        app.container.settings.saveServer(ServerConfig(publicUrl = "https://photos.example.com", apiKey = "k", userId = "u-1", email = "me@example.com"))
        FakeGallery.install(app.container)
        shoot("gallery-01-timeline") { AppNav(app.container) }
    }

    @Test fun timelineSelecting() {
        val repo = FakeGallery.repository(loadAll = true)
        val first = FakeGallery.assetsOf(FakeGallery.buckets[0].key)
        val selection = GridSelection().apply { set(first.take(5).map { it.id }.toSet() + first[8].id) }
        shoot("gallery-02-selecting") {
            Column(Modifier.fillMaxSize().background(Brutal.Paper)) {
                PhotosScreen(repo, onOpen = { _, _ -> }, onSettings = {}, selection = selection, modifier = Modifier.weight(1f))
                BrutalTabBar(Tabs, Routes.TIMELINE, {})
            }
        }
    }

    /** The compact month overview (pinched all the way out). */
    @Test fun timelineMonths() {
        val repo = FakeGallery.repository(loadAll = true)
        shoot("gallery-07-months") {
            Column(Modifier.fillMaxSize().background(Brutal.Paper)) {
                ScreenHeader("Photos")
                TimelineGrid(repo, onOpen = { _, _ -> }, initialDensity = GridDensity.Months, modifier = Modifier.weight(1f))
            }
        }
    }

    /** Pinched in to two per row: portrait pairs fill the width, sharp preview renditions. */
    @Test fun timelineTwoUp() = closeUp("gallery-08-two-up", GridDensity.Duo)

    /** Pinched all the way in: the one-up feed, tall portraits capped and centred. */
    @Test fun timelineOneUp() = closeUp("gallery-09-one-up", GridDensity.Feed)

    /** The feed scrolled to a 9:16 portrait: capped at 70% of the viewport and centred. */
    @Test fun timelineOneUpPortrait() = closeUp("gallery-11-one-up-portrait", GridDensity.Feed) { s ->
        (0 until s.total).first { s.assetAt(it)?.let { a -> a.ratio < 0.6f } == true }
    }

    @Test @Config(qualifiers = "w900dp-h600dp-xhdpi")
    fun timelineTwoUpTablet() = closeUp("gallery-10-two-up-tablet", GridDensity.Duo)

    private fun closeUp(name: String, density: GridDensity, reveal: ((com.roylet.photos.domain.timeline.TimelineSnapshot) -> Int)? = null) {
        val repo = FakeGallery.repository(loadAll = true)
        val revealIndex = reveal?.invoke(repo.snapshot.value)
        shoot(name) {
            Column(Modifier.fillMaxSize().background(Brutal.Paper)) {
                ScreenHeader("Photos")
                TimelineGrid(repo, onOpen = { _, _ -> }, initialDensity = density, revealIndex = revealIndex, modifier = Modifier.weight(1f))
            }
        }
    }

    @Test @Config(qualifiers = "w900dp-h600dp-xhdpi")
    fun timelineTablet() {
        app.container.settings.saveServer(ServerConfig(publicUrl = "https://photos.example.com", apiKey = "k", userId = "u-1", email = "me@example.com"))
        FakeGallery.install(app.container)
        shoot("gallery-03-timeline-tablet") { AppNav(app.container) }
    }

    @Test fun viewer() {
        val repo = FakeGallery.repository(loadAll = true)
        val index = landscapeIndex(repo)
        shoot("gallery-04-viewer", dark = true) {
            ViewerScreen(repo, startIndex = index, onBack = {}, onEdit = {}, modifier = Modifier.background(BrutalDark.Canvas))
        }
    }

    @Test fun viewerInfo() {
        val repo = FakeGallery.repository(loadAll = true)
        val index = landscapeIndex(repo)
        shoot("gallery-05-viewer-info", dark = true) {
            ViewerScreen(
                repo, startIndex = index, onBack = {}, onEdit = {}, initialInfo = true,
                loadDetails = { id -> FakeGallery.details(repo.snapshot.value.assetAt(repo.snapshot.value.indexOf(id))!!) },
            )
        }
    }

    @Test fun viewerVideo() {
        val repo = FakeGallery.repository(loadAll = true)
        val s = repo.snapshot.value
        val index = (0 until s.total).first { s.assetAt(it)?.isVideo == true }
        shoot("gallery-06-viewer-video", dark = true) { ViewerScreen(repo, startIndex = index, onBack = {}, onEdit = {}) }
    }

    private fun landscapeIndex(repo: com.roylet.photos.domain.timeline.TimelineRepository): Int {
        val s = repo.snapshot.value
        return (3 until s.total).first { s.assetAt(it)?.let { a -> a.isImage && a.ratio in 1.3f..1.6f && a.isLivePhoto.not() } == true }
    }

}
