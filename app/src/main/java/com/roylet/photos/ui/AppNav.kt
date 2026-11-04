package com.roylet.photos.ui

import androidx.compose.animation.AnimatedContentTransitionScope
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
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
import com.roylet.photos.ui.gallery.TimelineScreen
import com.roylet.photos.ui.library.LibraryScreen
import com.roylet.photos.ui.search.SearchScreen
import com.roylet.photos.ui.settings.SettingsScreen
import com.roylet.photos.ui.theme.Brutal

object Routes {
    const val CONNECT = "connect"
    const val TIMELINE = "timeline"
    const val ALBUMS = "albums"
    const val SEARCH = "search"
    const val LIBRARY = "library"
    const val SETTINGS = "settings"
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
    }
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.isTabSwitch() =
    initialState.destination.route in tabRoutes && targetState.destination.route in tabRoutes

@Composable
private fun Host(container: AppContainer, nav: NavHostController, signedIn: Boolean, modifier: Modifier) {
    NavHost(
        navController = nav,
        startDestination = if (signedIn) Routes.TIMELINE else Routes.CONNECT,
        modifier = modifier.background(Brutal.Paper),
        // immichup's push/pop motion for screens; a quick crossfade between tabs.
        enterTransition = { if (isTabSwitch()) fadeIn(tween(160)) else slideInHorizontally { it / 3 } + fadeIn() },
        exitTransition = { if (isTabSwitch()) fadeOut(tween(120)) else fadeOut() },
        popEnterTransition = { if (isTabSwitch()) fadeIn(tween(160)) else fadeIn() },
        popExitTransition = { if (isTabSwitch()) fadeOut(tween(120)) else slideOutHorizontally { it / 3 } + fadeOut() },
    ) {
        composable(Routes.CONNECT) {
            ConnectScreen(container, onConnected = { nav.navigate(Routes.TIMELINE) { popUpTo(0) } })
        }
        composable(Routes.TIMELINE) { TimelineScreen(container, onSettings = { nav.navigate(Routes.SETTINGS) }) }
        composable(Routes.ALBUMS) { AlbumsScreen(container) }
        composable(Routes.SEARCH) { SearchScreen(container) }
        composable(Routes.LIBRARY) { LibraryScreen(container, onSettings = { nav.navigate(Routes.SETTINGS) }) }
        composable(Routes.SETTINGS) { SettingsScreen(container, onBack = { nav.popBackStack() }) }
    }
}
