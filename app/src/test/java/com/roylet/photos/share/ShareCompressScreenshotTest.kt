package com.roylet.photos.share

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import coil3.compose.LocalAsyncImagePreviewHandler
import com.roylet.photos.FakeGallery
import com.roylet.photos.FakeLibrary
import com.roylet.photos.data.UiPrefs
import com.roylet.photos.domain.library.AlbumStore
import com.roylet.photos.domain.timeline.TimelineFilter
import com.roylet.photos.domain.timeline.TimelineRegistry
import com.roylet.photos.ui.actions.ActionSheets
import com.roylet.photos.ui.actions.LibraryActions
import com.roylet.photos.ui.actions.ShareLauncher
import com.roylet.photos.ui.components.LocalReducedMotion
import com.roylet.photos.ui.components.SnackbarController
import com.roylet.photos.ui.gallery.PhotosScreen
import com.roylet.photos.ui.theme.Palette
import com.roylet.photos.ui.theme.PhotosTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
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
 * The share sheet's quality choice and the compress progress sheet:
 *   ./gradlew testDebugUnitTest -Pscreenshots --tests '*ShareCompressScreenshotTest*'
 * Output: app/build/screenshots/share-*.png
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h880dp-xhdpi")
class ShareCompressScreenshotTest(private val darkTheme: Boolean) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0}")
        fun themes(): List<Array<Any>> = listOf(arrayOf(false), arrayOf(true))
    }

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("screenshotDir") ?: "build/screenshots")
    private val library = FakeLibrary()
    private val unconfined = CoroutineScope(Dispatchers.Unconfined)
    private val registry = TimelineRegistry(FakeGallery.source).apply { context = Dispatchers.Unconfined }
    private val timeline = registry.get(TimelineFilter.Timeline).apply { start(); FakeGallery.buckets.indices.forEach { ensureLoaded(it) } }
    private val ids = timeline.snapshot.value.assetsOf(0)!!.take(3).map { it.id }

    /** A 4K clip, a HEIC photo and a 1080p clip; or one small clip that can't get smaller. */
    private var infos: (List<String>) -> List<ShareItemInfo> = { ids ->
        listOf(
            ShareItemInfo(ids[0], "PXL_20260714_beach.mp4", "video/mp4", 312_000_000, 3840, 2160, 48_000),
            ShareItemInfo(ids[1], "IMG_4021.HEIC", "image/heic", 3_400_000, 4032, 3024, null),
            ShareItemInfo(ids[2], "PXL_20260714_waves.mp4", "video/mp4", 41_000_000, 1920, 1080, 20_000),
        )
    }

    private val fake = object : ShareCompression {
        override suspend fun measure(ids: List<String>) = infos(ids)
        override suspend fun compress(items: List<ShareItemInfo>, quality: ShareQuality, onProgress: (ShareProgress) -> Unit): ShareBundle {
            onProgress(ShareProgress(1, 3, SharePhase.Compressing, 0.35f, 0.45f, "IMG_4021.HEIC"))
            awaitCancellation()
        }
        override fun cleanStale() {}
    }

    private val actions = LibraryActions(
        { library }, AlbumStore({ library }, unconfined), SnackbarController(), unconfined,
        launcher = object : ShareLauncher {
            override fun sendFiles(files: List<File>) {}
            override fun sendText(text: String) {}
            override fun copy(text: String) {}
        },
        prefs = UiPrefs(null), shareDir = File(System.getProperty("java.io.tmpdir"), "share-shot"), timelines = registry,
        compression = fake,
    )

    @Before fun only() = assumeTrue(System.getProperty("screenshots") == "true")

    private fun shoot(name: String, before: () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(
                LocalInspectionMode provides true,
                LocalAsyncImagePreviewHandler provides FakeGallery.previewHandler,
                LocalReducedMotion provides true,
            ) {
                PhotosTheme(dark = darkTheme) {
                    Box(Modifier.fillMaxSize().background(Palette.paper)) {
                        PhotosScreen(timeline, onOpen = { _, _ -> }, onSettings = {})
                        ActionSheets(actions)
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(300)
        before()
        compose.mainClock.advanceTimeBy(1500)
        val view = compose.activity.window.decorView.rootView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        outDir.mkdirs()
        File(outDir, "${if (darkTheme) "$name-dark" else name}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun qualityChoice() {
        actions.prefs.shareMode = "file"
        actions.prefs.shareQuality = "SMALLER"
        shoot("share-01-quality") { actions.share(ids, timeline) }
    }

    @Test fun qualityGreyed() {
        infos = { ids -> listOf(ShareItemInfo(ids[0], "clip.mp4", "video/mp4", 1_000_000, 640, 360, 10_000)) }
        actions.prefs.shareMode = "file"
        actions.prefs.shareQuality = "SMALLEST"
        shoot("share-02-quality-greyed") { actions.share(ids.take(1), timeline) }
    }

    @Test fun compressing() {
        shoot("share-03-compressing") {
            actions.sendFiles(ids, ShareQuality.SMALLER, infos(ids))
        }
    }
}
