package com.roylet.photos.ui.viewer

/**
 * Decides whether one pinch gesture should close the viewer. Only a pinch that starts on an
 * unzoomed photo, never spreads the fingers, and ends clearly below 1× (with telephoto still at its
 * minimum zoom) closes; any pinch-out is left to telephoto as a zoom.
 */
internal class PinchToClose(private val startedUnzoomed: Boolean) {
    /** Accumulated scale of the fingers relative to where the gesture started. */
    var total = 1f
        private set
    private var peak = 1f

    fun onZoom(factor: Float) {
        if (!factor.isFinite() || factor <= 0f) return
        total *= factor
        if (total > peak) peak = total
    }

    fun shouldClose(zoomFractionNow: Float): Boolean =
        startedUnzoomed && peak < SPREAD_LIMIT && total < CLOSE_BELOW && zoomFractionNow < 0.02f

    companion object {
        /** The fingers must end up this much closer together than they started. */
        const val CLOSE_BELOW = 0.72f
        /** Spreading past this at any point makes the gesture a zoom, not a close. */
        const val SPREAD_LIMIT = 1.08f
    }
}
