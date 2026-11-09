package com.roylet.photos

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import coil3.compose.LocalAsyncImagePreviewHandler
import com.roylet.photos.cast.CastAvailability
import com.roylet.photos.cast.CastController
import com.roylet.photos.cast.FakeCastDevice
import com.roylet.photos.cast.FakeProxy
import com.roylet.photos.cast.fakeCastController
import com.roylet.photos.domain.timeline.TimelineRepository
import com.roylet.photos.ui.Routes
import com.roylet.photos.ui.Tabs
import com.roylet.photos.ui.cast.CastButton
import com.roylet.photos.ui.cast.CastMiniController
import com.roylet.photos.ui.cast.LocalCastController
import com.roylet.photos.ui.cast.SlideshowSheet
import com.roylet.photos.ui.cast.CastSignInSheet
import com.roylet.photos.ui.settings.CastingSettings
import com.roylet.photos.ui.settings.SettingsContent
import com.roylet.photos.data.ServerConfig
import com.roylet.photos.net.ConnState
import com.roylet.photos.net.ScanState
import com.roylet.photos.net.LocalUrlResult
import com.roylet.photos.ui.components.BrutalTabBar
import com.roylet.photos.ui.components.ConnectionChip
import com.roylet.photos.ui.components.LocalReducedMotion
import com.roylet.photos.ui.gallery.PhotosScreen
import com.roylet.photos.ui.theme.BrutalDark
import com.roylet.photos.ui.theme.Palette
import com.roylet.photos.ui.theme.PhotosTheme
import com.roylet.photos.ui.viewer.ViewerScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
 * Casting UI with a fake Chromecast (docs/SOURCES.md §11):
 *   ./gradlew testDebugUnitTest -Pscreenshots --tests '*CastScreenshotTest*'
 * Output: app/build/screenshots/cast-*.png. The viewer is always dark; the rest in both themes.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h880dp-xhdpi")
class CastScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val app get() = ApplicationProvider.getApplicationContext<PhotosApp>()
    private val outDir = File(System.getProperty("screenshotDir") ?: "build/screenshots")

    @Before fun only() = assumeTrue(System.getProperty("screenshots") == "true")

    private fun shoot(name: String, cast: CastController?, dark: Boolean = false, before: () -> Unit = {}, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(
                LocalInspectionMode provides true,
                LocalAsyncImagePreviewHandler provides FakeGallery.previewHandler,
                LocalReducedMotion provides true,
                LocalCastController provides cast,
            ) { PhotosTheme(dark = dark) { content() } }
        }
        compose.mainClock.advanceTimeBy(1500)
        before()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1500)
        val view = compose.activity.window.decorView.rootView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        outDir.mkdirs()
        File(outDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val scope = CoroutineScope(Dispatchers.Unconfined)

    private fun photoIndex(repo: TimelineRepository): Int {
        val s = repo.snapshot.value
        return (3 until s.total).first { s.assetAt(it)?.let { a -> a.isImage && a.ratio in 1.3f..1.6f && !a.isLivePhoto } == true }
    }

    private fun videoIndex(repo: TimelineRepository): Int {
        val s = repo.snapshot.value
        return (0 until s.total).first { s.assetAt(it)?.let { a -> a.isVideo && (a.durationMs ?: 0) > 60_000 && a.ratio > 1.2f } == true }
    }

    /** Not casting, a Chromecast on the Wi-Fi: the top bar keeps share and ⋯ (casting starts from ⋯). */
    @Test fun viewerCastButton() {
        val repo = FakeGallery.repository(loadAll = true)
        val cast = fakeCastController(scope, FakeCastDevice(CastAvailability.Available))
        shoot("cast-01-viewer-button", cast, dark = true) {
            ViewerScreen(repo, startIndex = photoIndex(repo), onBack = {}, onEdit = {}, modifier = Modifier.background(BrutalDark.Canvas))
        }
    }

    /** Casting a photo: the lit cast button in the top bar and the "Casting to" banner under it. */
    @Test fun viewerCastingPhoto() {
        val repo = FakeGallery.repository(loadAll = true)
        val device = FakeCastDevice(CastAvailability.Connected).apply { connect() }
        val cast = fakeCastController(scope, device)
        shoot("cast-02-viewer-casting-photo", cast, dark = true, before = { cast.dismissNotice() }) {
            ViewerScreen(repo, startIndex = photoIndex(repo), onBack = {}, onEdit = {}, modifier = Modifier.background(BrutalDark.Canvas))
        }
    }

    /** The relay's one-time note (no public HTTPS address) and the chrome hidden. */
    @Test fun viewerCastingNote() {
        val repo = FakeGallery.repository(loadAll = true)
        val device = FakeCastDevice(CastAvailability.Connected).apply { connect() }
        val cast = fakeCastController(scope, device)
        shoot("cast-03-viewer-casting-note", cast, dark = true) {
            ViewerScreen(repo, startIndex = photoIndex(repo), onBack = {}, onEdit = {}, initialChrome = false, modifier = Modifier.background(BrutalDark.Canvas))
        }
    }

    /** The TV couldn't load the item: the error in words. */
    @Test fun viewerCastingError() {
        val repo = FakeGallery.repository(loadAll = true)
        val device = FakeCastDevice(CastAvailability.Connected).apply { connect() }
        val cast = fakeCastController(scope, device, proxy = FakeProxy(unavailable = "Phone and TV must be on the same Wi-Fi to cast phone photos"))
        shoot("cast-04-viewer-casting-error", cast, dark = true) {
            ViewerScreen(repo, startIndex = photoIndex(repo), onBack = {}, onEdit = {}, modifier = Modifier.background(BrutalDark.Canvas))
        }
    }

    /** A video while casting: the remote panel instead of the local player. */
    @Test fun viewerCastingVideo() {
        val repo = FakeGallery.repository(loadAll = true)
        val device = FakeCastDevice(CastAvailability.Connected).apply { connect() }
        val cast = fakeCastController(scope, device)
        shoot("cast-05-viewer-remote", cast, dark = true, before = {
            cast.dismissNotice()
            device.seekTo(47_000)
            device.setVolume(0.6f)
        }) {
            ViewerScreen(repo, startIndex = videoIndex(repo), onBack = {}, onEdit = {}, modifier = Modifier.background(BrutalDark.Canvas))
        }
    }

    @Test fun viewerMenu() = viewerMenu("cast-11-viewer-menu", dark = false)

    @Test fun viewerMenuDark() = viewerMenu("cast-11-viewer-menu-dark", dark = true)

    /** Viewer ⋯ with a Chromecast around: Cast to TV…, Info, Slideshow, Archive (the sheet follows the theme). */
    private fun viewerMenu(name: String, dark: Boolean) {
        val repo = FakeGallery.repository(loadAll = true)
        val cast = fakeCastController(scope, FakeCastDevice(CastAvailability.Available))
        shoot(name, cast, dark = dark, before = {
            compose.onNodeWithContentDescription("More").performClick()
        }) {
            ViewerScreen(repo, startIndex = photoIndex(repo), onBack = {}, onEdit = {}, modifier = Modifier.background(BrutalDark.Canvas))
        }
    }

    @Test fun photosHeader() = photosHeader("cast-12-photos-header", dark = false)

    @Test fun photosHeaderDark() = photosHeader("cast-12-photos-header-dark", dark = true)

    /** The Photos header with a Chromecast around: [REMOTE] [cast] [settings]. */
    private fun photosHeader(name: String, dark: Boolean) {
        val repo = FakeGallery.repository(loadAll = true)
        val cast = fakeCastController(scope, FakeCastDevice(CastAvailability.Available))
        shoot(name, cast, dark = dark) {
            Column(Modifier.fillMaxSize().background(Palette.paper)) {
                PhotosScreen(
                    repo, onOpen = { _, _ -> }, onSettings = {},
                    connectionChip = { ConnectionChip(ConnState.Remote) },
                    castButton = { CastButton(dark = false) },
                    modifier = Modifier.weight(1f),
                )
                BrutalTabBar(Tabs, Routes.TIMELINE, {})
            }
        }
    }

    @Test fun miniController() = mini("cast-06-mini-controller", dark = false)

    @Test fun miniControllerDark() = mini("cast-06-mini-controller-dark", dark = true)

    /** Casting with the viewer closed: the mini controller above the tab bar, the header's cast icon lit. */
    private fun mini(name: String, dark: Boolean) {
        val repo = FakeGallery.repository(loadAll = true)
        val device = FakeCastDevice(CastAvailability.Connected).apply { connect() }
        val cast = fakeCastController(scope, device)
        cast.show(repo.snapshot.value.assetAt(videoIndex(repo))!!)
        cast.dismissNotice()
        shoot(name, cast, dark = dark) {
            val state by cast.state.collectAsState()
            Column(Modifier.fillMaxSize().background(Palette.paper)) {
                PhotosScreen(
                    repo, onOpen = { _, _ -> }, onSettings = {},
                    connectionChip = { ConnectionChip(ConnState.Home) },
                    castButton = { CastButton(dark = false) },
                    modifier = Modifier.weight(1f),
                )
                CastMiniController(state, onTogglePlay = cast::togglePlay, onStop = cast::stop)
                BrutalTabBar(Tabs, Routes.TIMELINE, {})
            }
        }
    }

    @Test fun slideshowSheet() = slideshow("cast-07-slideshow-sheet", dark = false)

    @Test fun slideshowSheetDark() = slideshow("cast-07-slideshow-sheet-dark", dark = true)

    /** Viewer ⋯ → Slideshow on TV: interval and shuffle, over the timeline. */
    private fun slideshow(name: String, dark: Boolean) {
        val repo = FakeGallery.repository(loadAll = true)
        shoot(name, null, dark = dark) {
            PhotosScreen(repo, onOpen = { _, _ -> }, onSettings = {})
            SlideshowSheet(true, "Living Room TV", initialIntervalS = 5, initialShuffle = true, onStart = { _, _ -> }, onDismiss = {})
        }
    }

    /** The phone's own slideshow sheet, in the (always dark) viewer. */
    @Test fun slideshowSheetViewer() {
        val repo = FakeGallery.repository(loadAll = true)
        shoot("cast-08-slideshow-sheet-viewer", fakeCastController(scope), dark = true, before = {
            compose.onNodeWithContentDescription("More").performClick()
            compose.mainClock.advanceTimeBy(600)
            compose.onNodeWithText("Slideshow").performClick()
        }) {
            ViewerScreen(repo, startIndex = photoIndex(repo), onBack = {}, onEdit = {}, modifier = Modifier.background(BrutalDark.Canvas))
        }
    }

    @Test fun signInSheet() = signIn("cast-09-sign-in-sheet", dark = false)

    @Test fun signInSheetDark() = signIn("cast-09-sign-in-sheet-dark", dark = true)

    /** "Sign in once to cast" over the timeline: email prefilled, a wrong password. */
    private fun signIn(name: String, dark: Boolean) {
        val repo = FakeGallery.repository(loadAll = true)
        shoot(name, null, dark = dark) {
            PhotosScreen(repo, onOpen = { _, _ -> }, onSettings = {})
            CastSignInSheet(true, "me@example.com", busy = false, error = "Wrong email or password", onSubmit = { _, _ -> }, onDismiss = {}, initialPassword = "hunter22")
        }
    }

    @Test @Config(qualifiers = "w400dp-h2200dp-xhdpi")
    fun settingsCasting() = settings("cast-10-settings", dark = false)

    @Test @Config(qualifiers = "w400dp-h2200dp-xhdpi")
    fun settingsCastingDark() = settings("cast-10-settings-dark", dark = true)

    /** Settings → Casting after an API-key sign-in. */
    private fun settings(name: String, dark: Boolean) {
        val cfg = ServerConfig(publicUrl = "https://photos.royle.au", apiKey = "k", userId = "u-1", email = "me@example.com")
        // A tall window: the card is on screen without scrolling (a paused clock can't settle a scroll).
        shoot(name, null, dark = dark) {
            SettingsContent(
                server = cfg, state = ConnState.Remote, activeUrl = cfg.publicUrl, scan = ScanState.Idle,
                onBack = {}, onMode = {}, onSavePublic = { true }, onSaveLocal = { LocalUrlResult.Verified }, onScan = {}, onSignOut = {},
                casting = CastingSettings(signedIn = false, email = cfg.email, usePublic = true, publicUrl = cfg.publicUrl, publicUsable = true),
            )
        }
    }
}
