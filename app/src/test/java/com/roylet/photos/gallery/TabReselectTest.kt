package com.roylet.photos.gallery

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import coil3.compose.LocalAsyncImagePreviewHandler
import com.roylet.photos.FakeGallery
import com.roylet.photos.ui.Routes
import com.roylet.photos.ui.Tabs
import com.roylet.photos.ui.components.BrutalTabBar
import com.roylet.photos.ui.components.LocalTabReselects
import com.roylet.photos.ui.components.ScrollToTopOnReselect
import com.roylet.photos.ui.components.TabReselects
import com.roylet.photos.ui.components.TabRoot
import com.roylet.photos.ui.gallery.GridSelection
import com.roylet.photos.ui.gallery.PhotosScreen
import com.roylet.photos.ui.tapTab
import com.roylet.photos.ui.theme.PhotosTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Re-tapping the selected tab (Google Photos style): the tab's root list scrolls back to the top;
 * on a page pushed within the tab the first tap pops back to the root and the next one scrolls.
 * The shell here mirrors AppNav's: the same [tapTab], [TabReselects] and [TabRoot] wiring.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h880dp-xhdpi")
class TabReselectTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var nav: NavHostController
    private val selection = GridSelection()

    private fun launch() {
        val repo = FakeGallery.repository(loadAll = true)
        compose.setContent {
            nav = rememberNavController()
            val reselects = remember { TabReselects() }
            val entry by nav.currentBackStackEntryAsState()
            CompositionLocalProvider(
                LocalInspectionMode provides true,
                LocalAsyncImagePreviewHandler provides FakeGallery.previewHandler,
                LocalTabReselects provides reselects,
            ) {
                PhotosTheme(dark = false) {
                    Column(Modifier.fillMaxSize()) {
                        NavHost(nav, startDestination = Routes.TIMELINE, modifier = Modifier.weight(1f)) {
                            composable(Routes.TIMELINE) {
                                TabRoot(Routes.TIMELINE) { PhotosScreen(repo, onOpen = { _, _ -> }, onSettings = {}, selection = selection) }
                            }
                            composable(Routes.ALBUMS) {
                                TabRoot(Routes.ALBUMS) {
                                    val list = rememberLazyListState()
                                    ScrollToTopOnReselect(list)
                                    LazyColumn(state = list, modifier = Modifier.fillMaxSize().testTag("albums")) {
                                        items(200) { Text("Album $it", Modifier.height(80.dp)) }
                                    }
                                }
                            }
                            composable(Routes.ALBUM) { Text("An album") }
                        }
                        // Always shown here, so the test can tap it on the pushed page too.
                        BrutalTabBar(Tabs, entry?.destination?.route, { tapTab(nav, it, reselects) })
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** The list's scroll position as its semantics report it: 0 at the very top. */
    private fun position(tag: String): Float =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun tap(label: String) {
        compose.onNodeWithText(label.uppercase()).performClick()
        compose.waitForIdle()
    }

    private val route get() = nav.currentDestination?.route

    @Test fun retappingPhotosScrollsTheGridToTheTopAndClosesTheSelection() {
        launch()
        compose.onNodeWithTag("timeline-grid").performScrollToIndex(80)
        compose.waitForIdle()
        assertTrue(position("timeline-grid") > 0f)
        selection.set(setOf(FakeGallery.assetsOf(FakeGallery.buckets[0].key)[0].id))
        compose.waitForIdle()

        tap("Photos")

        assertEquals(0f, position("timeline-grid"))
        assertFalse(selection.active)
        compose.onNodeWithText("Photos").assertIsDisplayed()
        assertEquals(Routes.TIMELINE, route)
    }

    @Test fun retappingAtTheTopDoesNothingHarmful() {
        launch()
        tap("Photos")
        tap("Photos")
        assertEquals(0f, position("timeline-grid"))
        assertEquals(Routes.TIMELINE, route)
    }

    @Test fun switchingTabsKeepsEachTabsScrollPosition() {
        launch()
        compose.onNodeWithTag("timeline-grid").performScrollToIndex(80)
        compose.waitForIdle()
        val scrolled = position("timeline-grid")
        tap("Albums")
        assertEquals(Routes.ALBUMS, route)
        tap("Photos")
        assertEquals(Routes.TIMELINE, route)
        assertEquals(scrolled, position("timeline-grid"))
    }

    @Test fun onAPushedPageTheFirstTapPopsAndTheSecondScrolls() {
        launch()
        tap("Albums")
        compose.onNodeWithTag("albums").performScrollToIndex(120)
        compose.waitForIdle()
        val scrolled = position("albums")
        assertTrue(scrolled > 0f)
        compose.runOnUiThread { nav.navigate(Routes.album("a1")) }
        compose.waitForIdle()
        compose.onNodeWithText("An album").assertIsDisplayed()

        tap("Albums")
        assertEquals(Routes.ALBUMS, route)
        assertEquals(scrolled, position("albums"))

        tap("Albums")
        assertEquals(Routes.ALBUMS, route)
        assertEquals(0f, position("albums"))
    }
}
