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
import com.roylet.photos.data.ConnectionMode
import com.roylet.photos.data.ServerConfig
import com.roylet.photos.data.UiPrefs
import com.roylet.photos.domain.source.ActiveSources
import com.roylet.photos.domain.source.Capabilities
import com.roylet.photos.domain.timeline.TimelineFilter
import com.roylet.photos.domain.timeline.TimelineRegistry
import com.roylet.photos.domain.timeline.TimelineRepository
import com.roylet.photos.domain.timeline.TimelineSource
import com.roylet.photos.net.ConnState
import com.roylet.photos.net.ScanState
import com.roylet.photos.ui.AppNav
import com.roylet.photos.ui.Routes
import com.roylet.photos.ui.albums.AlbumsScreen
import com.roylet.photos.ui.components.BrutalTabBar
import com.roylet.photos.ui.components.LocalReducedMotion
import com.roylet.photos.ui.gallery.PhotosScreen
import com.roylet.photos.ui.library.LibraryScreen
import com.roylet.photos.ui.onboarding.OnboardingScreen
import com.roylet.photos.ui.permissions.DeviceLibraryEmpty
import com.roylet.photos.ui.permissions.LimitedAccessBanner
import com.roylet.photos.ui.permissions.LocalMediaPermissions
import com.roylet.photos.ui.permissions.MediaAccess
import com.roylet.photos.ui.permissions.MediaAccessDenied
import com.roylet.photos.ui.permissions.MediaAccessExplainer
import com.roylet.photos.ui.permissions.MediaPermissionsState
import com.roylet.photos.ui.search.SearchModel
import com.roylet.photos.ui.search.SearchScreen
import com.roylet.photos.ui.settings.SettingsContent
import com.roylet.photos.ui.sources.LocalActiveSources
import com.roylet.photos.ui.sources.LocalCapabilities
import com.roylet.photos.ui.tabsFor
import com.roylet.photos.ui.theme.Palette
import com.roylet.photos.ui.theme.PhotosTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

/**
 * The phone-only app (no Immich): onboarding, media access, Accounts, the capability-gated tabs.
 *   ./gradlew testDebugUnitTest -Pscreenshots --tests '*SourcesScreenshotTest*'
 * Output: app/build/screenshots/sources-*.png (and -dark).
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h880dp-xhdpi")
class SourcesScreenshotTest(private val darkTheme: Boolean) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0}")
        fun themes(): List<Array<Any>> = listOf(arrayOf(false), arrayOf(true))
    }

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val app get() = ApplicationProvider.getApplicationContext<PhotosApp>()
    private val outDir = File(System.getProperty("screenshotDir") ?: "build/screenshots")
    private val phone = ActiveSources.LocalOnly
    private val phoneLibrary = FakeLibrary().apply { capabilities = Capabilities.Local }

    @Before fun only() = assumeTrue(System.getProperty("screenshots") == "true")

    @After fun signOut() = app.container.settings.signOut()

    private fun shoot(
        name: String,
        sources: ActiveSources = phone,
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
            ) { PhotosTheme(dark = darkTheme) { content() } }
        }
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
            BrutalTabBar(tabsFor(Capabilities.Local), route, {})
        }
    }

    private val emptySource = object : TimelineSource {
        override suspend fun buckets(filter: TimelineFilter) = emptyList<com.roylet.photos.domain.timeline.TimeBucket>()
        override suspend fun bucket(filter: TimelineFilter, key: String) = emptyList<com.roylet.photos.domain.timeline.TimelineAsset>()
    }

    private fun emptyRepo() = TimelineRepository(emptySource, TimelineFilter.Timeline, CoroutineScope(Dispatchers.Unconfined)).apply { start() }

    private val sample = ServerConfig(
        publicUrl = "https://photos.example.com", localUrl = "http://192.168.1.20:2283", apiKey = "k",
        userId = "u-1", email = "me@example.com", name = "Roy", mode = ConnectionMode.AUTO,
    )

    @Test fun onboarding() = shoot("sources-01-onboarding") { OnboardingScreen(onUseDevice = {}, onConnectImmich = {}) }

    @Test fun explainer() = shoot("sources-02-permission-explainer") {
        MediaAccessExplainer(permanentlyDenied = false, onAllow = {}, onOpenSettings = {}, onNotNow = {})
    }

    @Test fun explainerBlocked() = shoot("sources-03-permission-explainer-settings") {
        MediaAccessExplainer(permanentlyDenied = true, onAllow = {}, onOpenSettings = {}, onNotNow = {})
    }

    @Test fun denied() = shoot("sources-04-denied") {
        WithTabs(Routes.TIMELINE) {
            PhotosScreen(emptyRepo(), onOpen = { _, _ -> }, onSettings = {}, blocked = {
                MediaAccessDenied(permanentlyDenied = false, onGrant = {}, onOpenSettings = {}, onConnectImmich = {})
            })
        }
    }

    @Test fun deniedForGood() = shoot("sources-05-denied-settings") {
        WithTabs(Routes.TIMELINE) {
            PhotosScreen(emptyRepo(), onOpen = { _, _ -> }, onSettings = {}, blocked = {
                MediaAccessDenied(permanentlyDenied = true, onGrant = {}, onOpenSettings = {}, onConnectImmich = {})
            })
        }
    }

    @Test fun limited() = shoot("sources-06-limited-banner", permissions = MediaPermissionsState(MediaAccess.Partial)) {
        WithTabs(Routes.TIMELINE) {
            PhotosScreen(FakeGallery.repository(loadAll = true), onOpen = { _, _ -> }, onSettings = {}, banner = { LimitedAccessBanner(onManage = {}) })
        }
    }

    @Test fun emptyPhone() = shoot("sources-07-empty-phone") {
        WithTabs(Routes.TIMELINE) {
            PhotosScreen(emptyRepo(), onOpen = { _, _ -> }, onSettings = {}, emptyContent = { DeviceLibraryEmpty(partial = false, onManage = {}) })
        }
    }

    @Composable
    private fun Settings(server: ServerConfig?) = SettingsContent(
        server = server, state = if (server != null) ConnState.Home else ConnState.Offline, activeUrl = server?.localUrl.orEmpty(),
        scan = ScanState.Idle, onBack = {}, onMode = {}, onSavePublic = { true }, onSaveLocal = { com.roylet.photos.net.LocalUrlResult.Cleared },
        onScan = {}, onSignOut = {},
    )

    @Test fun accountsWithoutImmich() = shoot(
        "sources-08-settings-accounts",
        permissions = MediaPermissionsState(MediaAccess.Partial, canManageMedia = false, onManageMedia = {}),
    ) { Settings(null) }

    @Test fun accountsWithImmich() = shoot(
        "sources-09-settings-accounts-immich",
        sources = ActiveSources.ImmichOnly,
        permissions = MediaPermissionsState(MediaAccess.Full, canManageMedia = true, onManageMedia = {}),
    ) { Settings(sample) }

    @Test fun libraryNoServer() = shoot("sources-10-library-phone") {
        WithTabs(Routes.LIBRARY) { LibraryScreen(onOpen = {}) }
    }

    @Test fun foldersTab() {
        val store = com.roylet.photos.domain.library.AlbumStore({ phoneLibrary }, CoroutineScope(Dispatchers.Unconfined)).apply { refresh() }
        shoot("sources-11-folders") {
            WithTabs(Routes.ALBUMS) { AlbumsScreen(store, null, UiPrefs(null), onOpen = {}, folders = true) }
        }
    }

    @Test fun searchNoServer() {
        val registry = TimelineRegistry(FakeGallery.source).apply { context = Dispatchers.Unconfined }
        val model = SearchModel({ phoneLibrary }, registry, UiPrefs(null), CoroutineScope(Dispatchers.Unconfined))
        shoot("sources-12-search-phone") {
            WithTabs(Routes.SEARCH) { SearchScreen(model, timelineFor = { registry.get(it) }, onOpen = { _, _, _ -> }, onPerson = {}, onAllPeople = {}) }
        }
    }

    /** The real app on first launch: onboarding, no server. */
    @Test fun firstLaunch() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true, LocalReducedMotion provides true) {
                PhotosTheme(dark = darkTheme) { AppNav(app.container) }
            }
        }
        save(if (darkTheme) "sources-13-app-first-launch-dark" else "sources-13-app-first-launch")
    }

    /** The real app after "Use on this device", before access is granted (Robolectric denies it). */
    @Test fun phoneOnlyApp() {
        app.container.settings.completeOnboarding()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true, LocalReducedMotion provides true) {
                PhotosTheme(dark = darkTheme) { AppNav(app.container) }
            }
        }
        save(if (darkTheme) "sources-14-app-phone-only-dark" else "sources-14-app-phone-only")
    }
}
