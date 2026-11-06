package com.roylet.photos.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter

/**
 * "The selected tab was tapped again": the tab bar emits the tab's route, and that tab's root
 * screen scrolls its main list back to the top (Google Photos style). Pushed pages don't listen.
 */
class TabReselects {
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<String> = _events.asSharedFlow()

    fun reselect(route: String) { _events.tryEmit(route) }
}

/** The app's [TabReselects]; null outside the app shell (previews, isolated screens). */
val LocalTabReselects = staticCompositionLocalOf<TabReselects?> { null }

/** The tab whose root screen this is; null on pushed pages, which ignore re-taps. */
val LocalTabRoot = compositionLocalOf<String?> { null }

/** Marks [content] as the root screen of the tab [route], so it hears re-taps of that tab. */
@Composable
fun TabRoot(route: String, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalTabRoot provides route, content = content)
}

/** Runs [action] each time this screen's tab is re-tapped (only on a tab's root screen). */
@Composable
fun OnTabReselect(action: suspend () -> Unit) {
    val reselects = LocalTabReselects.current ?: return
    val route = LocalTabRoot.current ?: return
    val latest by rememberUpdatedState(action)
    LaunchedEffect(reselects, route) {
        reselects.events.filter { it == route }.collect { latest() }
    }
}

/** On a re-tap of this screen's tab: [onReselect] (e.g. close the selection), then scroll [state] to the top. */
@Composable
fun ScrollToTopOnReselect(state: LazyListState, onReselect: () -> Unit = {}) {
    val reduced = reducedMotion()
    OnTabReselect { onReselect(); state.scrollToTop(reduced) }
}

@Composable
fun ScrollToTopOnReselect(state: LazyGridState, onReselect: () -> Unit = {}) {
    val reduced = reducedMotion()
    OnTabReselect { onReselect(); state.scrollToTop(reduced) }
}

/** Items the scroll-to-top animates through at most: a longer jump snaps this close first, so it stays quick. */
internal const val ScrollToTopAnimatedItems = 12

/** Back to the first item: animated (snapping close first on a long jump), instant with reduced motion. False if already there. */
suspend fun LazyListState.scrollToTop(reduced: Boolean): Boolean {
    if (firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0) return false
    if (reduced) scrollToItem(0)
    else {
        if (firstVisibleItemIndex > ScrollToTopAnimatedItems) scrollToItem(ScrollToTopAnimatedItems)
        animateScrollToItem(0)
    }
    return true
}

/** As for lists; grid items are smaller, so the animated stretch covers a few more of them. */
suspend fun LazyGridState.scrollToTop(reduced: Boolean): Boolean {
    if (firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0) return false
    val near = ScrollToTopAnimatedItems * 2
    if (reduced) scrollToItem(0)
    else {
        if (firstVisibleItemIndex > near) scrollToItem(near)
        animateScrollToItem(0)
    }
    return true
}
