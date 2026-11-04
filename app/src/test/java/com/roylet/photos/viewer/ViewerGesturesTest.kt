package com.roylet.photos.viewer

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import coil3.compose.LocalAsyncImagePreviewHandler
import com.roylet.photos.FakeGallery
import com.roylet.photos.domain.timeline.TimelineRepository
import com.roylet.photos.ui.components.LocalReducedMotion
import com.roylet.photos.ui.gallery.LocalSharedTransitionScope
import com.roylet.photos.ui.gallery.sharedAsset
import com.roylet.photos.ui.theme.BrutalDark
import com.roylet.photos.ui.theme.PhotosTheme
import com.roylet.photos.ui.viewer.LocalViewer
import com.roylet.photos.ui.viewer.ViewerController
import com.roylet.photos.ui.viewer.ViewerScreen
import com.roylet.photos.ui.viewer.ZoomFractionKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pinch, double-tap and swipe-down on a viewer page, set up like the real app: the viewer open
 * inside a SharedTransitionLayout with the grid cell underneath registered as the same shared element.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h880dp-xhdpi")
class ViewerGesturesTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private var closed = 0
    private lateinit var id: String

    private fun open(): TimelineRepository {
        val repo = FakeGallery.repository(loadAll = true)
        val s = repo.snapshot.value
        val index = (3 until s.total).first { s.assetAt(it)?.let { a -> a.isImage && a.ratio in 1.3f..1.6f && !a.isLivePhoto } == true }
        id = s.assetAt(index)!!.id
        val controller = ViewerController().apply { open(repo.filter, index, id, null); showNow() }
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true, LocalAsyncImagePreviewHandler provides FakeGallery.previewHandler, LocalReducedMotion provides true) {
                PhotosTheme(dark = true) {
                    SharedTransitionLayout {
                        CompositionLocalProvider(LocalSharedTransitionScope provides this, LocalViewer provides controller) {
                            Box(Modifier.size(60.dp).sharedAsset(id))
                            ViewerScreen(repo, startIndex = index, onBack = { closed++ }, onEdit = {})
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        return repo
    }

    private val page get() = compose.onNodeWithTag("viewer-page-$id", useUnmergedTree = true)
    private fun zoom() = page.fetchSemanticsNode().config[ZoomFractionKey]

    private fun pinchOut() = page.performTouchInput {
        pinch(center - Offset(20f, 0f), center - Offset(250f, 0f), center + Offset(20f, 0f), center + Offset(250f, 0f), durationMillis = 400)
    }

    private fun shot(): Bitmap {
        val view = compose.activity.window.decorView.rootView
        return Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
    }

    @Test fun pinchOutZoomsAndTheZoomIsDrawn() {
        open()
        val before = shot()
        assertEquals(0f, zoom())
        pinchOut()
        compose.waitForIdle()
        assertTrue("pinch-out should zoom in", zoom() > 0.5f)
        val after = shot()
        // The zoomed photo fills the screen (not the empty canvas) and stays under the top bar.
        val canvas = BrutalDark.Canvas.toArgb()
        assertNotEquals(canvas, after.getPixel(after.width / 2, after.height / 3))
        assertEquals(before.getPixel(after.width / 2, 8), after.getPixel(after.width / 2, 8))
        assertEquals(0, closed)
    }

    @Test fun doubleTapZooms() {
        open()
        page.performTouchInput { doubleClick(center) }
        compose.waitForIdle()
        assertTrue("double-tap should zoom in", zoom() > 0.5f)
    }

    @Test fun pinchInOnUnzoomedPhotoCloses() {
        open()
        page.performTouchInput {
            pinch(center - Offset(300f, 0f), center - Offset(40f, 0f), center + Offset(300f, 0f), center + Offset(40f, 0f), durationMillis = 400)
        }
        compose.waitForIdle()
        assertEquals(1, closed)
    }

    @Test fun swipeDownDismissesOnlyWhenUnzoomed() {
        open()
        pinchOut()
        compose.waitForIdle()
        page.performTouchInput { swipeDown(centerY - 200f, centerY + 500f, durationMillis = 200) }
        compose.waitForIdle()
        assertEquals("zoomed in: a downward drag pans", 0, closed)

        page.performTouchInput { doubleClick(center) } // back to fitted
        compose.waitForIdle()
        assertEquals(0f, zoom())
        page.performTouchInput { swipeDown(centerY - 200f, centerY + 500f, durationMillis = 200) }
        compose.waitForIdle()
        assertEquals(1, closed)
    }
}
