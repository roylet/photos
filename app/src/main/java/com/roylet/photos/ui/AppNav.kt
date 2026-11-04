package com.roylet.photos.ui

import com.roylet.photos.ui.components.ConnectionChip

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LocalLibrary
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.PhotoAlbum
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.roylet.photos.AppContainer
import com.roylet.photos.ui.albums.AlbumsScreen
import com.roylet.photos.ui.components.AppMark
import com.roylet.photos.ui.components.BrutalRail
import com.roylet.photos.ui.components.BrutalTabBar
import com.roylet.photos.ui.components.NavTab
import com.roylet.photos.ui.connect.ConnectScreen
import com.roylet.photos.domain.timeline.TimelineFilter
import com.roylet.photos.immich.asset
import com.roylet.photos.ui.components.BrutalButton
import com.roylet.photos.ui.components.EmptyState
import com.roylet.photos.ui.gallery.FakeMediaUrls
import com.roylet.photos.ui.actions.ActionSheets
import com.roylet.photos.ui.actions.LocalLibraryActions
import com.roylet.photos.ui.components.BrutalSnackbarHost
import com.roylet.photos.ui.components.LocalSnackbar
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Alignment
import com.roylet.photos.ui.gallery.ImmichMediaUrls
import com.roylet.photos.ui.gallery.LocalAssetActions
import com.roylet.photos.ui.gallery.LocalMediaHttpClient
import com.roylet.photos.ui.gallery.LocalMediaUrls
import com.roylet.photos.ui.gallery.LocalNavAnimatedScope
import com.roylet.photos.ui.gallery.LocalSharedTransitionScope
import com.roylet.photos.ui.gallery.PhotosScreen
import com.roylet.photos.ui.viewer.ViewerScreen
import com.roylet.photos.ui.library.LibraryScreen
import com.roylet.photos.ui.search.SearchScreen
import com.roylet.photos.ui.settings.SettingsScreen
import com.roylet.photos.ui.theme.Brutal
import com.roylet.photos.ui.theme.PhotosTheme

object Routes {
    const val CONNECT = "connect"
    const val TIMELINE = "timeline"
    const val ALBUMS = "albums"
    const val SEARCH = "search"
    const val LIBRARY = "library"
    const val SETTINGS = "settings"
    const val VIEWER = "viewer?filter={filter}&index={index}&id={id}"
    const val EDIT = "edit/{assetId}"

    /** Opens the viewer on [index] of the timeline described by [filter]. */
    fun viewer(filter: TimelineFilter, index: Int, id: String) = "viewer?filter=${filter.encode()}&index=$index&id=$id"
    fun edit(assetId: String) = "edit/$assetId"

    /** Saved-state key the viewer sets on the screen below it: the index it ended on, so the grid can reveal it. */
    const val REVEAL_INDEX = "revealIndex"
}

val Tabs = listOf(
    NavTab(Routes.TIMELINE, "Photos", Icons.Rounded.Photo),
    NavTab(Routes.ALBUMS, "Albums", Icons.Rounded.PhotoAlbum),
    NavTab(Routes.SEARCH, "Search", Icons.Rounded.Search),
    NavTab(Routes.LIBRARY, "Library", Icons.Rounded.LocalLibrary),
)

private val tabRoutes = Tabs.map { it.route }.toSet()

/** Width at which the bottom bar becomes a side rail (tablets, foldables, landscape). */
val WideBreakpoint = 600.dp

@Composable
fun AppNav(container: AppContainer, nav: NavHostController = rememberNavController()) {
    val server by container.settings.server.collectAsState()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route

    LaunchedEffect(server == null) {
        if (server == null && nav.currentDestination?.route != null && nav.currentDestination?.route != Routes.CONNECT) {
            nav.navigate(Routes.CONNECT) { popUpTo(0) }
        }
    }

    val selectTab: (NavTab) -> Unit = { tab ->
        nav.navigate(tab.route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    val showBar = route in tabRoutes

    val urls = remember(server) { container.api()?.let(::ImmichMediaUrls) ?: FakeMediaUrls }
    // A new account (or sign-out) starts the library caches afresh.
    LaunchedEffect(server?.publicUrl, server?.userId) { container.albums.clear(); container.memories.clear() }
    CompositionLocalProvider(
        LocalMediaUrls provides urls,
        LocalMediaHttpClient provides container.http,
        LocalAssetActions provides container.actions,
        LocalLibraryActions provides container.actions,
        LocalSnackbar provides container.snackbar,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Brutal.Paper)) {
            val wide = maxWidth >= WideBreakpoint
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    if (showBar) BrutalRail(Tabs, route, selectTab, header = { AppMark() })
                    Host(container, nav, server != null, Modifier.weight(1f))
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Host(container, nav, server != null, Modifier.weight(1f))
                    if (showBar) BrutalTabBar(Tabs, route, selectTab)
                }
            }
            ActionSheets(container.actions)
            BrutalSnackbarHost(
                container.snackbar,
                Modifier.align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(start = 16.dp, end = 16.dp, bottom = if (route == Routes.VIEWER) 150.dp else if (showBar && !wide) 84.dp else 16.dp),
            )
        }
    }
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.isTabSwitch() =
    initialState.destination.route in tabRoutes && targetState.destination.route in tabRoutes

/** Opening or closing the viewer: the shared-element morph does the work, so the screens just cross-fade. */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.isViewer() =
    initialState.destination.route == Routes.VIEWER || targetState.destination.route == Routes.VIEWER

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun Host(container: AppContainer, nav: NavHostController, signedIn: Boolean, modifier: Modifier) {
    SharedTransitionLayout(modifier) {
    CompositionLocalProvider(LocalSharedTransitionScope provides this) {
    NavHost(
        navController = nav,
        startDestination = if (signedIn) Routes.TIMELINE else Routes.CONNECT,
        modifier = Modifier.background(Brutal.Paper),
        // immichup's push/pop motion for screens; a quick crossfade between tabs.
        enterTransition = { if (isViewer()) fadeIn(tween(220)) else if (isTabSwitch()) fadeIn(tween(160)) else slideInHorizontally { it / 3 } + fadeIn() },
        exitTransition = { if (isViewer()) ExitTransition.KeepUntilTransitionsFinished else if (isTabSwitch()) fadeOut(tween(120)) else fadeOut() },
        popEnterTransition = { if (isViewer()) EnterTransition.None else if (isTabSwitch()) fadeIn(tween(160)) else fadeIn() },
        popExitTransition = { if (isViewer()) fadeOut(tween(260)) else if (isTabSwitch()) fadeOut(tween(120)) else slideOutHorizontally { it / 3 } + fadeOut() },
    ) {
        composable(Routes.CONNECT) {
            ConnectScreen(container, onConnected = { nav.navigate(Routes.TIMELINE) { popUpTo(0) } })
        }
        composable(Routes.TIMELINE) { entry ->
            CompositionLocalProvider(LocalNavAnimatedScope provides this) {
                val timeline = remember { container.timelines.get(TimelineFilter.Timeline) }
                val reveal by entry.savedStateHandle.getStateFlow<Int?>(Routes.REVEAL_INDEX, null).collectAsState()
                PhotosScreen(
                    timeline = timeline,
                    onOpen = { index, asset -> nav.navigate(Routes.viewer(timeline.filter, index, asset.id)) },
                    onSettings = { nav.navigate(Routes.SETTINGS) },
                    revealIndex = reveal,
                    onRevealed = { entry.savedStateHandle.remove<Int>(Routes.REVEAL_INDEX) },
                    connectionChip = {
                        val conn by container.connection.state.collectAsState()
                        ConnectionChip(conn, onClick = { nav.navigate(Routes.SETTINGS) })
                    },
                )
            }
        }
        composable(
            Routes.VIEWER,
            arguments = listOf(
                navArgument("filter") { type = NavType.StringType; defaultValue = "" },
                navArgument("index") { type = NavType.IntType; defaultValue = 0 },
                navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { entry ->
            CompositionLocalProvider(LocalNavAnimatedScope provides this) {
                val args = entry.arguments
                val filter = remember { TimelineFilter.decode(args?.getString("filter").orEmpty()) }
                val timeline = remember { container.timelines.get(filter) }
                PhotosTheme(dark = true) {
                    ViewerScreen(
                        timeline = timeline,
                        startIndex = args?.getInt("index") ?: 0,
                        startId = args?.getString("id"),
                        onBack = { nav.popBackStack() },
                        onEdit = { id -> nav.navigate(Routes.edit(id)) },
                        onIndexChange = { nav.previousBackStackEntry?.savedStateHandle?.set(Routes.REVEAL_INDEX, it) },
                        loadDetails = { id -> container.api()?.asset(id) },
                    )
                }
            }
        }
        composable(Routes.EDIT, arguments = listOf(navArgument("assetId") { type = NavType.StringType })) {
            // TODO(editor): replace with the photo editor.
            PhotosTheme(dark = true) {
                EmptyState(Icons.Rounded.Tune, "Editor", "The photo editor opens here.") {
                    BrutalButton("Back", { nav.popBackStack() })
                }
            }
        }
        composable(Routes.ALBUMS) { AlbumsScreen(container) }
        composable(Routes.SEARCH) { SearchScreen(container) }
        composable(Routes.LIBRARY) { LibraryScreen(container, onSettings = { nav.navigate(Routes.SETTINGS) }) }
        composable(Routes.SETTINGS) { SettingsScreen(container, onBack = { nav.popBackStack() }) }
    }
    }
    }
}
