package com.roylet.photos.ui

import com.roylet.photos.ui.components.SnackTone

import com.roylet.photos.editor.ui.EditorScreen
import com.roylet.photos.video.ui.VideoEditorScreen

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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import com.roylet.photos.ui.albums.AlbumPhotoPicker
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
import com.roylet.photos.ui.theme.Palette
import com.roylet.photos.ui.theme.PhotosTheme
import com.roylet.photos.domain.source.Capabilities
import com.roylet.photos.domain.source.Capability
import com.roylet.photos.ui.onboarding.OnboardingScreen
import com.roylet.photos.ui.permissions.ConsentHost
import com.roylet.photos.ui.permissions.DeviceLibraryEmpty
import com.roylet.photos.ui.permissions.LimitedAccessBanner
import com.roylet.photos.ui.permissions.LocalConsentLauncher
import com.roylet.photos.ui.permissions.LocalMediaPermissions
import com.roylet.photos.ui.permissions.MediaAccess
import com.roylet.photos.ui.permissions.MediaAccessDenied
import com.roylet.photos.ui.permissions.MediaAccessExplainer
import com.roylet.photos.ui.permissions.rememberMediaPermissions
import com.roylet.photos.ui.sources.LocalActiveSources
import com.roylet.photos.ui.sources.LocalCapabilities

object Routes {
    const val ONBOARDING = "onboarding"
    /** The explainer shown before the system's media permission prompt. */
    const val MEDIA_ACCESS = "media-access"
    const val CONNECT = "connect"
    const val TIMELINE = "timeline"
    const val ALBUMS = "albums"
    const val SEARCH = "search"
    const val LIBRARY = "library"
    const val SETTINGS = "settings"
    const val VIEWER = "viewer?filter={filter}&index={index}&id={id}"
    const val EDIT = "edit/{assetId}"
    const val EDIT_VIDEO = "editVideo/{assetId}"
    const val ALBUM = "album/{albumId}"
    const val NEW_ALBUM = "album-new?name={name}"
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
    fun editVideo(assetId: String) = "editVideo/$assetId"
    fun album(id: String) = "album/$id"
    fun newAlbum(name: String) = "album-new?name=${android.net.Uri.encode(name)}"
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

/** The tabs the active sources support: Albums needs server albums or phone folders. */
fun tabsFor(capabilities: Capabilities): List<NavTab> = Tabs.filter {
    it.route != Routes.ALBUMS || Capability.SERVER_ALBUMS in capabilities || Capability.FOLDERS in capabilities
}

/** Width at which the bottom bar becomes a side rail (tablets, foldables, landscape). */
val WideBreakpoint = 600.dp

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AppNav(container: AppContainer, nav: NavHostController = rememberNavController()) {
    val server by container.settings.server.collectAsState()
    val onboarded by container.settings.onboarded.collectAsState()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    // Which sources are showing (phone alone without a server) and what they can do together.
    val sources = remember(server) { container.sources() }
    val tabs = remember(sources.capabilities) { tabsFor(sources.capabilities) }
    val permissions = rememberMediaPermissions(container.settings)
    ConsentHost(container.consent)

    val selectTab: (NavTab) -> Unit = { tab ->
        nav.navigate(tab.route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    val showBar = route in tabRoutes

    val urls = remember(server) { container.mediaUrls() }
    val viewer = rememberSaveable(saver = ViewerController.Saver) { ViewerController() }
    // A new account, connecting or disconnecting starts the library caches afresh, and the
    // screens (which hold the old timelines) start again from Photos.
    val session = remember(server) { container.sessionKey() }
    var shownSession by rememberSaveable { mutableStateOf(session) }
    LaunchedEffect(session) {
        container.albums.clear(); container.memories.clear(); container.search.reset()
        if (session != shownSession) {
            shownSession = session
            val current = nav.currentDestination?.route
            if (current != null && current !in setOf(Routes.ONBOARDING, Routes.CONNECT) && (current != Routes.TIMELINE || nav.previousBackStackEntry != null)) {
                viewer.close()
                nav.navigate(Routes.TIMELINE) { popUpTo(0) }
            }
        }
    }
    // After "Replace original", offer to undo: restores the original from Trash and trashes the edit.
    ReplaceUndoSnackbar(container, container.edits.lastReplace, container.edits::consumeLastReplace) { container.edits.undoReplace(it) }
    // The same for videos saved with "Replace original" (the save may finish after the editor closed).
    ReplaceUndoSnackbar(container, container.videoEdits.lastReplace, container.videoEdits::consumeLastReplace) { container.videoEdits.undoReplace(it) }
    CompositionLocalProvider(
        LocalMediaUrls provides urls,
        LocalMediaHttpClient provides container.http,
        LocalAssetActions provides container.actions,
        LocalLibraryActions provides container.actions,
        LocalSnackbar provides container.snackbar,
        LocalViewer provides viewer,
        LocalActiveSources provides sources,
        LocalCapabilities provides sources.capabilities,
        LocalMediaPermissions provides permissions,
        LocalConsentLauncher provides container.consent,
    ) {
      SharedTransitionLayout(Modifier.fillMaxSize()) {
      CompositionLocalProvider(LocalSharedTransitionScope provides this) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Palette.paper)) {
            val wide = maxWidth >= WideBreakpoint
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    if (showBar) BrutalRail(tabs, route, selectTab, header = { AppMark() })
                    Host(container, nav, viewer, onboarded, Modifier.weight(1f))
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Host(container, nav, viewer, onboarded, Modifier.weight(1f))
                    if (showBar) BrutalTabBar(tabs, route, selectTab)
                }
            }
            ViewerOverlay(
                viewer, entry,
                timelineFor = { container.timelines.get(it) },
                onEdit = { id -> nav.navigate(Routes.edit(id)) },
                onEditVideo = { id -> nav.navigate(Routes.editVideo(id)) },
                loadDetails = { id -> container.library.details(id) },
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
private fun Host(container: AppContainer, nav: NavHostController, viewer: ViewerController, onboarded: Boolean, modifier: Modifier) {
    val caps = LocalCapabilities.current
    val sources = LocalActiveSources.current
    val permissions = LocalMediaPermissions.current
    NavHost(
        navController = nav,
        // No server needed: without one the app is the phone's gallery.
        startDestination = if (onboarded) Routes.TIMELINE else Routes.ONBOARDING,
        modifier = modifier.background(Palette.paper),
        // immichup's push/pop motion for screens; a quick crossfade between tabs.
        enterTransition = { if (isViewer()) fadeIn(tween(220)) else if (isTabSwitch()) fadeIn(tween(160)) else slideInHorizontally { it / 3 } + fadeIn() },
        exitTransition = { if (isViewer()) ExitTransition.KeepUntilTransitionsFinished else if (isTabSwitch()) fadeOut(tween(120)) else fadeOut() },
        popEnterTransition = { if (isViewer()) EnterTransition.None else if (isTabSwitch()) fadeIn(tween(160)) else fadeIn() },
        popExitTransition = { if (isViewer()) fadeOut(tween(260)) else if (isTabSwitch()) fadeOut(tween(120)) else slideOutHorizontally { it / 3 } + fadeOut() },
    ) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                onUseDevice = {
                    container.settings.completeOnboarding()
                    nav.navigate(if (permissions.access == MediaAccess.Denied) Routes.MEDIA_ACCESS else Routes.TIMELINE) { popUpTo(0) }
                },
                onConnectImmich = {
                    container.settings.completeOnboarding()
                    nav.navigate(Routes.TIMELINE) { popUpTo(0) }
                    nav.navigate(Routes.CONNECT)
                },
            )
        }
        composable(Routes.MEDIA_ACCESS) {
            val done = { if (!nav.popBackStack()) nav.navigate(Routes.TIMELINE) { popUpTo(0) } }
            MediaAccessExplainer(
                permanentlyDenied = permissions.permanentlyDenied,
                onAllow = { permissions.request { done() } },
                onOpenSettings = { permissions.openSettings(); done() },
                onNotNow = { done() },
            )
        }
        composable(Routes.CONNECT) {
            ConnectScreen(
                container,
                onConnected = { nav.navigate(Routes.TIMELINE) { popUpTo(0) } },
                onBack = nav.previousBackStackEntry?.let { { nav.popBackStack() } },
            )
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
                        if (sources.hasImmich) {
                            val conn by container.connection.state.collectAsState()
                            ConnectionChip(conn, onClick = { nav.navigate(Routes.SETTINGS) })
                        }
                    },
                    // The phone's photos need the media permission (re-checked on every resume).
                    blocked = if (sources.hasLocal && permissions.access == MediaAccess.Denied) {
                        {
                            MediaAccessDenied(
                                permanentlyDenied = permissions.permanentlyDenied,
                                onGrant = { permissions.request { if (it != MediaAccess.Denied) timeline.refresh() } },
                                onOpenSettings = permissions::openSettings,
                                onConnectImmich = if (sources.hasImmich) null else { { nav.navigate(Routes.CONNECT) } },
                            )
                        }
                    } else null,
                    banner = if (sources.hasLocal && permissions.access == MediaAccess.Partial) {
                        { LimitedAccessBanner(onManage = { permissions.request { timeline.refresh() } }) }
                    } else null,
                    emptyContent = if (sources.hasLocal && !sources.hasImmich) {
                        { DeviceLibraryEmpty(partial = permissions.access == MediaAccess.Partial, onManage = { permissions.request { timeline.refresh() } }) }
                    } else null,
                )
                // Access changed (granted in the prompt or in system settings): load what's now visible.
                val access = permissions.access
                var seenAccess by remember { mutableStateOf(access) }
                LaunchedEffect(access) {
                    if (access != seenAccess) { seenAccess = access; if (sources.hasLocal) timeline.refresh(keepLoaded = true) }
                }
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
                        onEditVideo = { id -> nav.navigate(Routes.editVideo(id)) },
                        onIndexChange = { nav.previousBackStackEntry?.savedStateHandle?.set(Routes.REVEAL_INDEX, it) },
                        loadDetails = { id -> container.library.details(id) },
                    )
                }
            }
        }
        composable(Routes.EDIT, arguments = listOf(navArgument("assetId") { type = NavType.StringType })) { entry ->
            val assetId = entry.arguments?.getString("assetId").orEmpty()
            EditorScreen(
                assetId = assetId,
                onClose = { nav.popBackStack() },
                onSaved = {
                    // EditSaveManager already refreshed the cached timelines (new asset, maybe a trashed original).
                    nav.popBackStack()
                },
            )
        }
        composable(Routes.EDIT_VIDEO, arguments = listOf(navArgument("assetId") { type = NavType.StringType })) { entry ->
            val assetId = entry.arguments?.getString("assetId").orEmpty()
            VideoEditorScreen(
                assetId = assetId,
                onClose = { nav.popBackStack() },
                // VideoSaveManager refreshes the cached timelines when the save finishes.
                onSaved = { nav.popBackStack() },
            )
        }
        composable(Routes.ALBUMS) {
            val serverAlbums = Capability.SERVER_ALBUMS in caps
            AlbumsScreen(
                container.albums, if (serverAlbums) container.actions else null, container.uiPrefs,
                onOpen = { nav.navigate(Routes.album(it.id)) },
                onNewAlbum = if (serverAlbums) { name -> nav.navigate(Routes.newAlbum(name)) } else null,
                folders = !serverAlbums,
            )
        }
        composable(Routes.NEW_ALBUM, arguments = listOf(navArgument("name") { type = NavType.StringType; defaultValue = "" })) { entry ->
            val name = entry.arguments?.getString("name").orEmpty()
            val timeline = remember { container.timelines.get(TimelineFilter.Timeline) }
            // Created in one request once the photos are chosen; then the album replaces the picker.
            var busy by remember { mutableStateOf(false) }
            val create: (List<String>) -> Unit = { ids ->
                if (!busy) {
                    busy = true
                    container.actions.createAlbum(name, ids, onFailed = { busy = false }) { album ->
                        nav.navigate(Routes.album(album.id)) { popUpTo(Routes.ALBUMS) }
                    }
                }
            }
            AlbumPhotoPicker(name, timeline, onAdd = create, onSkip = { create(emptyList()) }, onBack = { nav.popBackStack() })
        }
        composable(Routes.ALBUM, arguments = listOf(navArgument("albumId") { type = NavType.StringType })) { entry ->
            val id = entry.arguments?.getString("albumId").orEmpty()
            TimelineDestination(container, viewer, entry, TimelineFilter.album(id)) { timeline, open, reveal, revealed ->
                AlbumDetailScreen(
                    id, container.albums, timeline, if (Capability.SERVER_ALBUMS in caps) container.actions else null, open,
                    onBack = { nav.popBackStack() }, revealIndex = reveal, onRevealed = revealed,
                )
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
                    LibraryEntry.ConnectImmich -> nav.navigate(Routes.CONNECT)
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
        composable(Routes.SETTINGS) {
            SettingsScreen(container, onBack = { nav.popBackStack() }, onConnect = { nav.navigate(Routes.CONNECT) })
        }
    }
}

/**
 * After a "Replace original" save, offers to undo it: restores the original from Trash and
 * trashes the edit. [undo] runs in the app scope, so it finishes even if the screen goes away.
 */
@Composable
private fun ReplaceUndoSnackbar(
    container: AppContainer,
    lastReplace: kotlinx.coroutines.flow.StateFlow<com.roylet.photos.editor.save.SaveResult?>,
    consume: () -> Unit,
    undo: suspend (com.roylet.photos.editor.save.SaveResult) -> Unit,
) {
    val replaced by lastReplace.collectAsState()
    LaunchedEffect(replaced) {
        val result = replaced ?: return@LaunchedEffect
        consume()
        container.snackbar.show("Original moved to Trash", actionLabel = "Undo", tone = SnackTone.Success) {
            container.uiScope.launch {
                try {
                    undo(result)
                    container.timelines.invalidateAll()
                    container.snackbar.show("Original restored", tone = SnackTone.Success)
                } catch (e: Exception) {
                    container.snackbar.error("Couldn't undo: ${e.message ?: "unknown error"}")
                }
            }
        }
    }
}
