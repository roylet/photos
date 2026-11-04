package com.roylet.photos.ui.viewer

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.Velocity
import kotlin.math.abs

/**
 * Vertical "drag down to dismiss". Watches the Initial pass so it can claim a mostly-downward
 * single-finger drag before the pager or the zoomable see it; horizontal swipes, pinches and
 * anything while [enabled] is false (e.g. zoomed in) are left alone.
 */
internal fun Modifier.dragToDismiss(
    enabled: () -> Boolean,
    onDrag: (Offset) -> Unit,
    onRelease: (Velocity) -> Unit,
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (!enabled()) return@awaitEachGesture
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        var dragging = false
        var released = false
        var total = Offset.Zero
        val slop = viewConfiguration.touchSlop
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (!dragging && event.changes.count { it.pressed } > 1) break
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) {
                if (dragging) { onRelease(tracker.calculateVelocity()); released = true }
                break
            }
            tracker.addPosition(change.uptimeMillis, change.position)
            val delta = change.positionChange()
            if (!dragging) {
                total += delta
                if (total.getDistance() > slop) {
                    if (total.y > 0 && abs(total.y) > abs(total.x) * 1.4f) {
                        dragging = true
                        onDrag(total)
                        change.consume()
                    } else break
                }
            } else {
                onDrag(delta)
                event.changes.forEach { it.consume() }
            }
        }
        if (dragging && !released) onRelease(Velocity.Zero)
    }
}
