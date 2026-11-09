package com.roylet.photos.viewer

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import coil3.compose.LocalAsyncImagePreviewHandler
import com.roylet.photos.FakeGallery
import com.roylet.photos.domain.timeline.TimelineAsset
import com.roylet.photos.ui.components.LocalReducedMotion
import com.roylet.photos.ui.theme.PhotosTheme
import com.roylet.photos.ui.viewer.ViewerScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Photos open with the chrome, videos full-bleed; a tap on a video brings the chrome back. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h880dp-xhdpi")
class ViewerChromeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun open(pick: (TimelineAsset) -> Boolean): String {
        val repo = FakeGallery.repository(loadAll = true)
        val s = repo.snapshot.value
        val index = (0 until s.total).first { s.assetAt(it)?.let(pick) == true }
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true, LocalAsyncImagePreviewHandler provides FakeGallery.previewHandler, LocalReducedMotion provides true) {
                PhotosTheme(dark = true) { ViewerScreen(repo, startIndex = index, onBack = {}, onEdit = {}) }
            }
        }
        compose.waitForIdle()
        return s.assetAt(index)!!.id
    }

    private fun assertChrome(shown: Boolean) {
        listOf(
            compose.onNodeWithContentDescription("Back"),
            compose.onNodeWithContentDescription("Share"),
            compose.onNodeWithContentDescription("More"),
            compose.onNodeWithTag("viewer-actions"),
        ).forEach { if (shown) it.assertExists() else it.assertDoesNotExist() }
    }

    @Test fun photoOpensWithChrome() {
        open { it.isImage && !it.isLivePhoto }
        assertChrome(shown = true)
        // The grouped action bar: favourite and album on the left, EDIT and delete on the right.
        compose.onNodeWithText("EDIT").assertExists()
        compose.onNodeWithContentDescription("Info").assertDoesNotExist()
    }

    @Test fun videoOpensWithoutChromeAndATapShowsIt() {
        val id = open { it.isVideo }
        assertChrome(shown = false)
        compose.onNodeWithTag("video-seekbar", useUnmergedTree = true).assertDoesNotExist()

        compose.onNodeWithTag("viewer-video-$id").performClick()
        compose.waitForIdle()
        assertChrome(shown = true)
        compose.onNodeWithTag("video-seekbar", useUnmergedTree = true).assertExists()

        compose.onNodeWithTag("viewer-video-$id").performClick()
        compose.waitForIdle()
        assertChrome(shown = false)
    }

    @Test fun moreMenuHoldsInfo() {
        open { it.isImage && !it.isLivePhoto }
        compose.onNodeWithContentDescription("More").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Info").assertExists()
        compose.onNodeWithText("Slideshow").assertExists()
    }
}
