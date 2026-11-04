package com.roylet.photos.viewer

import com.roylet.photos.ui.viewer.PinchToClose
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinchToCloseTest {
    private fun pinch(unzoomed: Boolean, vararg steps: Float) = PinchToClose(unzoomed).apply { steps.forEach { onZoom(it) } }

    @Test fun pinchInOnUnzoomedPhotoCloses() = assertTrue(pinch(true, 0.9f, 0.8f, 0.85f).shouldClose(0f))
    @Test fun smallPinchInDoesNotClose() = assertFalse(pinch(true, 0.95f, 0.9f).shouldClose(0f))
    @Test fun pinchOutNeverCloses() = assertFalse(pinch(true, 1.3f, 1.5f).shouldClose(0f))
    @Test fun outThenInIsAZoomNotAClose() = assertFalse(pinch(true, 1.4f, 0.6f, 0.7f).shouldClose(0f))
    @Test fun pinchInWhileZoomedOnlyZoomsOut() = assertFalse(pinch(false, 0.5f).shouldClose(0f))
    @Test fun stillZoomedAtEndDoesNotClose() = assertFalse(pinch(true, 0.5f).shouldClose(0.4f))
    @Test fun ignoresBogusFactors() = assertFalse(pinch(true, Float.NaN, 0f, -1f).shouldClose(0f))
}
