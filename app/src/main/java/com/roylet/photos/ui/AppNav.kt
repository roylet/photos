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
import com.roylet.photos.ui.albums.AlbumDetailScreen
import androidx.compose.animation.AnimatedContentScope
import com.roylet.photos.domain.timeline.TimelineAsset
import com.roylet.photos.domain.timeline.TimelineRepository
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
import com.roylet.photos.ui.gallery.TimelineScreen
import com.roylet.photos.ui.viewer.ViewerScreen
import com.roylet.photos.ui.viewer.LocalViewer
import com.roylet.photos.ui.viewer.ViewerController
import com.roylet.photos.ui.viewer.ViewerOverlay
import com.roylet.photos.ui.library.LibraryScreen
import com.roylet.photos.ui.memories.MemoriesCarousel
import com.roylet.photos.ui.memories.MemoryStoryScreen
import com.roylet.photos.ui.library.LibraryEntry
import com.roylet.photos.ui.library.MapScreen
import com.roylet.photos.ui.library.PeopleScreen
import com.roylet.photos.ui.library.TrashScreen
import com.roylet.photos.immich.ImmichApi
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
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
    const val ALBUM = "album/{albumId}"
    const val PERSON = "person/{personId}?name={name}"
    const val FAVORITES = "favorites"
    const val ARCHIVE = "archive"
    const val TRASH = "trash"
    const val PEOPLE = "people"
    const val MAP = "map"
    const val LIST = "list/{filter}?title={title}"
    const val MEMORIES = "memories?index={index}"

    /** Opens the viewer on [index] of the timeline described by [filter]. */
    fun viewer(filter: TimelineFilter, index: Int, id: String) = "viewer?filter=${filter.encode()}&index=$index&id=$id"
    fun edit(assetId: String) = "edit/$assetId"
    fun album(id: String) = "album/$id"
    fun memories(index: Int) = "memories?index=$index"
    fun list(filter: TimelineFilter, title: String) = "list/${filter.encode()}?title=${android.net.Uri.encode(title)}"
    fun person(id: String, name: String) = "person/$id?name=${android.net.Uri.encode(name)}"

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

@OptIn(ExperimentalSharedTransitionApi::class)
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
    val viewer = remember { ViewerController() }
    // A new account (or sign-out) starts the library caches afresh.
    LaunchedEffect(server?.publicUrl, server?.userId) { container.albums.clear(); container.memories.clear(); container.search.reset() }
    CompositionLocalProvider(
        LocalMediaUrls provides urls,
        LocalMediaHttpClient provides container.http,
        LocalAssetActions provides container.actions,
        LocalLibraryActions provides container.actions,
        LocalSnackbar provides container.snackbar,
        LocalViewer provides viewer,
    ) {
      SharedTransitionLayout(Modifier.fillMaxSize()) {
      CompositionLocalProvider(LocalSharedTransitionScope provides this) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Brutal.Paper)) {
            val wide = maxWidth >= WideBreakpoint
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    if (showBar) BrutalRail(Tabs, route, selectTab, header = { AppMark() })
                    Host(container, nav, viewer, server != null, Modifier.weight(1f))
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Host(container, nav, viewer, server != null, Modifier.weight(1f))
                    if (showBar) BrutalTabBar(Tabs, route, selectTab)
                }
            }
            ViewerOverlay(
                viewer, entry,
                timelineFor = { container.timelines.get(it) },
                onEdit = { id -> nav.navigate(Routes.edit(id)) },
                loadDetails = { id -> container.api()?.asset(id) },
            )
            ActionSheets(container.actions)
            BrutalSnackbarHost(
                container.snackbar,
                Modifier.align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(start = 16.dp, end = 16.dp, bottom = if (route == Routes.VIEWER || viewer.open) 150.dp else if (showBar && !wide) 84.dp else 16.dp),
            )
        }
      }
      }
    }
}

/** Map clusters open at most this many photos. */
private const val MAX_CLUSTER = 300

/** Opens today's memories in the story viewer, or says there are none. */
private fun openMemories(container: AppContainer, nav: NavHostController) {
    container.memories.start()
    if (container.memories.memories.value.isEmpty()) container.snackbar.show("No memories for today yet")
    else nav.navigate(Routes.memories(0))
}

/**
 * Common plumbing for a destination that shows a timeline: the shared timeline repository, the
 * viewer opener and the index to reveal when coming back from the viewer.
 */
@Composable
private fun AnimatedContentScope.TimelineDestination(
    container: AppContainer,
    viewer: ViewerController,
    entry: NavBackStackEntry,
    filter: TimelineFilter,
    content: @Composable (TimelineRepository, (Int, TimelineAsset) -> Unit, Int?, () -> Unit) -> Unit,
) {
    CompositionLocalProvider(LocalNavAnimatedScope provides this) {
        val timeline = remember(filter) { container.timelines.get(filter) }
        val reveal by entry.savedStateHandle.getStateFlow<Int?>(Routes.REVEAL_INDEX, null).collectAsState()
        content(
            timeline,
            { index, asset -> viewer.open(timeline.filter, index, asset.id, entry) },
            reveal,
            { entry.savedStateHandle.remove<Int>(Routes.REVEAL_INDEX) },
        )
    }
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.isTabSwitch() =
    initialState.destination.route in tabRoutes && targetState.destination.route in tabRoutes

/** Opening or closing the viewer: the shared-element morph does the work, so the screens just cross-fade. */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.isViewer() =
    initialState.destination.route == Routes.VIEWER || targetState.destination.route == Routes.VIEWER

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun Host(container: AppContainer, nav: NavHostController, viewer: ViewerController, signedIn: Boolean, modifier: Modifier) {
    NavHost(
        navController = nav,
        startDestination = if (signedIn) Routes.TIMELINE else Routes.CONNECT,
        modifier = modifier.background(Brutal.Paper),
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
                LaunchedEffect(Unit) { container.memories.start() }
                val memories by container.memories.memories.collectAsState()
                val reveal by entry.savedStateHandle.getStateFlow<Int?>(Routes.REVEAL_INDEX, null).collectAsState()
                PhotosScreen(
                    timeline = timeline,
                    onOpen = { index, asset -> viewer.open(timeline.filter, index, asset.id, entry) },
                    onSettings = { nav.navigate(Routes.SETTINGS) },
                    revealIndex = reveal,
                    onRevealed = { entry.savedStateHandle.remove<Int>(Routes.REVEAL_INDEX) },
                    topContent = memories.takeIf { it.isNotEmpty() }?.let { list -> { MemoriesCarousel(list, onOpen = { nav.navigate(Routes.memories(it)) }) } },
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
            // Kept for deep links; the app opens the viewer as an overlay (ViewerOverlay).
            CompositionLocalProvider(LocalNavAnimatedScope provides this, LocalViewer provides null) {
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
        composable(Routes.ALBUMS) {
            AlbumsScreen(container.albums, container.actions, container.uiPrefs, onOpen = { nav.navigate(Routes.album(it.id)) })
        }
        composable(Routes.ALBUM, arguments = listOf(navArgument("albumId") { type = NavType.StringType })) { entry ->
            val id = entry.arguments?.getString("albumId").orEmpty()
            TimelineDestination(container, viewer, entry, TimelineFilter.album(id)) { timeline, open, reveal, revealed ->
                AlbumDetailScreen(id, container.albums, timeline, container.actions, open, onBack = { nav.popBackStack() }, revealIndex = reveal, onRevealed = revealed)
            }
        }
        composable(Routes.SEARCH) { entry ->
            CompositionLocalProvider(LocalNavAnimatedScope provides this) {
                SearchScreen(
                    container.search,
                    timelineFor = { container.timelines.get(it) },
                    onOpen = { timeline, index, asset -> viewer.open(timeline.filter, index, asset.id, entry) },
                    onPerson = { nav.navigate(Routes.person(it.id, it.name)) },
                    onAllPeople = { nav.navigate(Routes.PEOPLE) },
                )
            }
        }
        composable(
            Routes.PERSON,
            arguments = listOf(navArgument("personId") { type = NavType.StringType }, navArgument("name") { type = NavType.StringType; defaultValue = "" }),
        ) { entry ->
            val id = entry.arguments?.getString("personId").orEmpty()
            val name = entry.arguments?.getString("name").orEmpty()
            TimelineDestination(container, viewer, entry, TimelineFilter.person(id)) { timeline, open, reveal, revealed ->
                TimelineScreen(timeline, name.ifBlank { "Person" }, open, onBack = { nav.popBackStack() }, revealIndex = reveal, onRevealed = revealed)
            }
        }
        composable(Routes.LIBRARY) {
            LibraryScreen(onOpen = { e ->
                when (e) {
                    LibraryEntry.Favorites -> nav.navigate(Routes.FAVORITES)
                    LibraryEntry.People -> nav.navigate(Routes.PEOPLE)
                    LibraryEntry.Places -> nav.navigate(Routes.MAP)
                    LibraryEntry.Memories -> openMemories(container, nav)
                    LibraryEntry.Archive -> nav.navigate(Routes.ARCHIVE)
                    LibraryEntry.Trash -> nav.navigate(Routes.TRASH)
                    LibraryEntry.Settings -> nav.navigate(Routes.SETTINGS)
                }
            })
        }
        composable(Routes.MEMORIES, arguments = listOf(navArgument("index") { type = NavType.IntType; defaultValue = 0 })) { entry ->
            val memories by container.memories.memories.collectAsState()
            PhotosTheme(dark = true) {
                MemoryStoryScreen(memories, entry.arguments?.getInt("index") ?: 0, onClose = { nav.popBackStack() })
            }
        }
        composable(Routes.FAVORITES) { entry ->
            TimelineDestination(container, viewer, entry, TimelineFilter.Favorites) { timeline, open, reveal, revealed ->
                TimelineScreen(
                    timeline, "Favorites", open, onBack = { nav.popBackStack() }, revealIndex = reveal, onRevealed = revealed,
                    emptyTitle = "No favourites yet", emptyText = "Tap the heart on a photo to keep it here.",
                )
            }
        }
        composable(Routes.ARCHIVE) { entry ->
            TimelineDestination(container, viewer, entry, TimelineFilter.Archive) { timeline, open, reveal, revealed ->
                TimelineScreen(
                    timeline, "Archive", open, onBack = { nav.popBackStack() }, revealIndex = reveal, onRevealed = revealed,
                    emptyTitle = "Nothing archived", emptyText = "Archive photos to hide them from Photos without deleting them.",
                )
            }
        }
        composable(Routes.TRASH) { entry ->
            TimelineDestination(container, viewer, entry, TimelineFilter.Trash) { timeline, open, reveal, revealed ->
                TrashScreen(timeline, { container.library }, container.actions, open, onBack = { nav.popBackStack() }, revealIndex = reveal, onRevealed = revealed)
            }
        }
        composable(Routes.PEOPLE) {
            PeopleScreen({ container.library }, onPerson = { nav.navigate(Routes.person(it.id, it.name)) }, onBack = { nav.popBackStack() })
        }
        composable(Routes.MAP) {
            val scope = rememberCoroutineScope()
            MapScreen({ container.library }, onPick = { ids, title ->
                scope.launch {
                    runCatching { container.library.assets(ids.take(MAX_CLUSTER)) }
                        .onSuccess { assets -> nav.navigate(Routes.list(container.timelines.putList("map-${System.nanoTime()}", assets), title)) }
                        .onFailure { container.snackbar.error("Couldn't load those photos: ${ImmichApi.describe(it)}") }
                }
            }, onBack = { nav.popBackStack() })
        }
        composable(
            Routes.LIST,
            arguments = listOf(navArgument("filter") { type = NavType.StringType }, navArgument("title") { type = NavType.StringType; defaultValue = "" }),
        ) { entry ->
            val filter = remember { TimelineFilter.decode(entry.arguments?.getString("filter").orEmpty()) }
            TimelineDestination(container, viewer, entry, filter) { timeline, open, reveal, revealed ->
                TimelineScreen(timeline, entry.arguments?.getString("title").orEmpty().ifBlank { "Photos" }, open, onBack = { nav.popBackStack() }, revealIndex = reveal, onRevealed = revealed)
            }
        }
        composable(Routes.SETTINGS) { SettingsScreen(container, onBack = { nav.popBackStack() }) }
    }
}
