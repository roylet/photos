package com.roylet.photos.ui.viewer

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry
import com.roylet.photos.domain.timeline.TimelineFilter
import com.roylet.photos.domain.timeline.TimelineRepository
import com.roylet.photos.immich.AssetDto
import com.roylet.photos.ui.components.reducedMotion
import com.roylet.photos.ui.theme.PhotosTheme

/**
 * One opening of the viewer: what to show and the screen that opened it. The screen is kept by
 * its back-stack entry id (stable across activity recreation), not the entry object.
 */
class ViewerRequest(val filter: TimelineFilter, val startIndex: Int, val startId: String, val ownerId: String?)

/**
 * The full-screen viewer runs as an overlay *above* the screen that opened it rather than as a
 * navigation destination, so that screen's grid stays composed underneath: dragging a photo down
 * reveals the real grid, not a blank page.
 *
 * The grid ↔ viewer morph uses caller-managed shared elements keyed on [focusId] (the asset the
 * viewer is on): the grid cell is the visible element while closed, the viewer's page while open.
 * Opening happens in two frames (focus first, then [open]) so the grid cell is registered as the
 * starting point before the viewer appears.
 */
@Stable
class ViewerController {
    var request by mutableStateOf<ViewerRequest?>(null)
        private set
    /** True while the viewer is (or is becoming) visible. */
    var open by mutableStateOf(false)
        private set
    /** The asset taking part in the morph: the one opened, then whichever page the viewer is on. */
    var focusId by mutableStateOf<String?>(null)
        private set
    /** The page the viewer is on (so it reopens there after e.g. the editor). */
    var index by mutableIntStateOf(0)
        private set
    internal var pendingOpen by mutableStateOf(false)

    fun open(filter: TimelineFilter, index: Int, id: String, owner: NavBackStackEntry?) {
        if (open) return
        request = ViewerRequest(filter, index, id, owner?.id)
        this.index = index
        focusId = id
        pendingOpen = true
    }

    fun close() { open = false; pendingOpen = false }

    internal fun moveTo(index: Int, id: String?) {
        this.index = index
        if (id != null) focusId = id
    }

    internal fun showNow() { if (pendingOpen) { pendingOpen = false; open = true } }

    internal fun finished() { if (!open && !pendingOpen) request = null }

    companion object {
        /** Keeps an open viewer open across rotation and other activity recreation. */
        val Saver: Saver<ViewerController, Any> = listSaver<ViewerController, Any>(
            save = { c ->
                val r = c.request
                if (r == null || !(c.open || c.pendingOpen)) emptyList()
                else listOf(r.filter.encode(), r.startIndex, r.startId, r.ownerId.orEmpty(), c.index, c.focusId ?: r.startId)
            },
            restore = { v ->
                ViewerController().apply {
                    if (v.size == 6) {
                        val ownerId = (v[3] as String).ifEmpty { null }
                        request = ViewerRequest(TimelineFilter.decode(v[0] as String), v[1] as Int, v[2] as String, ownerId)
                        index = v[4] as Int
                        focusId = v[5] as String
                        open = true
                    }
                }
            },
        )
    }
}

/** The app's viewer controller; null outside AppNav (the grid then falls back to nav-based transitions). */
val LocalViewer = staticCompositionLocalOf<ViewerController?> { null }

/**
 * Draws the viewer for [controller]'s request while the screen that opened it is on top of the
 * back stack (it hides while e.g. the editor is open, and comes back on return).
 */
@Composable
fun ViewerOverlay(
    controller: ViewerController,
    currentEntry: NavBackStackEntry?,
    timelineFor: (TimelineFilter) -> TimelineRepository,
    onEdit: (String) -> Unit,
    onEditVideo: (String) -> Unit = onEdit,
    loadDetails: suspend (String) -> AssetDto?,
    modifier: Modifier = Modifier,
) {
    val req = controller.request ?: return
    val reduced = reducedMotion()
    LaunchedEffect(req) {
        // Let the grid cell register as the shared element's start before the viewer appears.
        withFrameNanos { }
        controller.showNow()
    }
    if (req.ownerId != null && currentEntry?.id != req.ownerId) return
    val visible = remember(req) { MutableTransitionState(false) }
    visible.targetState = controller.open
    LaunchedEffect(visible.currentState, visible.isIdle, controller.open) {
        if (visible.isIdle && !visible.currentState) controller.finished()
    }
    BackHandler(enabled = controller.open) { controller.close() }
    val timeline = remember(req) { timelineFor(req.filter) }
    AnimatedVisibility(visible, modifier, enter = fadeIn(tween(if (reduced) 0 else 200)), exit = fadeOut(tween(if (reduced) 0 else 280))) {
        PhotosTheme(dark = true) {
            ViewerScreen(
                timeline = timeline,
                startIndex = controller.index,
                startId = controller.focusId ?: req.startId,
                onBack = { controller.close() },
                onEdit = onEdit,
                onEditVideo = onEditVideo,
                onIndexChange = { i ->
                    controller.moveTo(i, timeline.snapshot.value.assetAt(i)?.id)
                    // The grid underneath scrolls along, so the close morph lands on the right cell.
                    if (req.ownerId != null) currentEntry?.savedStateHandle?.set(REVEAL_KEY, i)
                },
                loadDetails = loadDetails,
            )
        }
    }
}

/** Must match `Routes.REVEAL_INDEX`. */
internal const val REVEAL_KEY = "revealIndex"
