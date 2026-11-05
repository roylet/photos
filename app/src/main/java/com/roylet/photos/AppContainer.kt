package com.roylet.photos

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import okio.Path.Companion.toOkioPath
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.video.VideoFrameDecoder
import com.roylet.photos.data.Settings
import com.roylet.photos.domain.library.LocalLibrary
import com.roylet.photos.domain.library.LocalTimelineSource
import com.roylet.photos.domain.timeline.TimelineRepository
import com.roylet.photos.local.DeviceMedia
import com.roylet.photos.local.LocalMediaUrls
import com.roylet.photos.local.LocalThumbnailFetcher
import com.roylet.photos.merge.MergedLibrary
import com.roylet.photos.domain.source.ActiveSources
import com.roylet.photos.domain.source.MediaSourceKind
import com.roylet.photos.domain.timeline.TimelineFilter
import com.roylet.photos.domain.timeline.TimelineRegistry
import com.roylet.photos.domain.timeline.TimelineSource
import com.roylet.photos.ui.gallery.ImmichMediaUrls
import com.roylet.photos.ui.gallery.MediaUrls
import com.roylet.photos.ui.gallery.SourceMediaUrls
import com.roylet.photos.ui.permissions.ConsentLauncher
import com.roylet.photos.domain.library.AlbumStore
import com.roylet.photos.domain.library.LibrarySource
import com.roylet.photos.domain.library.MemoryStore
import com.roylet.photos.editor.save.EditSaveManager
import com.roylet.photos.video.save.VideoSaveManager
import com.roylet.photos.immich.ImmichApi
import com.roylet.photos.immich.ImmichLibrary
import com.roylet.photos.immich.ImmichTimelineSource
import com.roylet.photos.net.AndroidNetworkEnvironment
import com.roylet.photos.net.ApiKeyGuard
import com.roylet.photos.net.ApiKeyInterceptor
import com.roylet.photos.net.ConnectionManager
import com.roylet.photos.net.LanScanner
import com.roylet.photos.upload.NetworkMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.roylet.photos.merge.ImmichMatcher
import com.roylet.photos.merge.ImmichRemoteLookup
import com.roylet.photos.merge.MergedTimelineSource
import com.roylet.photos.merge.MonthMatcher
import com.roylet.photos.data.UiPrefs
import com.roylet.photos.ui.actions.AndroidShareLauncher
import com.roylet.photos.ui.actions.LibraryActions
import com.roylet.photos.ui.components.SnackbarController
import java.io.File
import com.roylet.photos.ui.search.SearchModel
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Hand-rolled dependency container; one per process. */
class AppContainer(context: Context) {
    private val app = context.applicationContext
    val settings = Settings(app)
    val network = NetworkMonitor(app)

    /** Process-wide scope for background work that outlives any screen. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Short-timeout probes and LAN discovery. Deliberately not built on [http] (no rewriting, no key interceptor). */
    val scanner = LanScanner(ImmichApi.defaultClient)

    /**
     * Picks the LAN or public address. Observe `connection.state` (for the header chip) and
     * `connection.activeUrl`; call `connection.refresh()`/`rescan()` to re-check. Started from
     * [PhotosApp.onCreate].
     */
    val connection = ConnectionManager(settings, scanner, AndroidNetworkEnvironment(app), appScope)

    /** The one OkHttp client shared by the API, Coil, ExoPlayer and uploads. See [serverClient]. */
    val http: OkHttpClient = serverClient(ImmichApi.defaultClient, connection)

    val imageLoader: ImageLoader = ImageLoader.Builder(app)
        // A quarter of the app's memory holds roughly a dozen full-screen viewer previews next to
        // the grid's thumbnails. The disk cache keeps swiped-past and prefetched previews across sessions.
        .memoryCache { MemoryCache.Builder().maxSizePercent(app, 0.25).build() }
        .diskCache { DiskCache.Builder().directory(app.cacheDir.resolve("image_cache").toOkioPath()).maxSizeBytes(512L * 1024 * 1024).build() }
        .components {
            add(OkHttpNetworkFetcherFactory(callFactory = { http }))
            add(VideoFrameDecoder.Factory())
            // Phone thumbnails (localmedia://): ContentResolver.loadThumbnail, cancelled when the cell leaves.
            add(LocalThumbnailFetcher.Factory(app.contentResolver))
        }
        .build()

    /* ---------------- Sources (see docs/SOURCES.md) ---------------- */

    /** True while an Immich server is configured. Without one the app is a phone gallery. */
    val hasServer: Boolean get() = settings.server.value != null

    /** The Immich timeline (bucket API). */
    val immichTimeline: TimelineSource = ImmichTimelineSource { api() }

    /**
     * The phone's photos: the MediaStore index (Room), its sync, the timeline source and the
     * library. Lazy, so nothing touches the database until something asks.
     */
    val device: DeviceMedia by lazy {
        DeviceMedia(
            app, appScope, consent, snackbar,
            offeredManageMedia = { settings.offeredManageMedia },
            markOfferedManageMedia = { settings.offeredManageMedia = true },
            onChanged = ::onDeviceChanged,
            wantHashes = { hasServer },
        )
    }

    private var localTimelineOverride: LocalTimelineSource? = null

    /** The phone's timeline (MediaStore index); tests can pin another. */
    var localTimeline: LocalTimelineSource
        get() = localTimelineOverride ?: device.timeline
        set(value) { localTimelineOverride = value }

    /** The Immich library (albums, search, people, sharing, trash). */
    val immichLibrary: LibrarySource = ImmichLibrary({ api() }, userId = { settings.server.value?.userId })

    private var localLibraryOverride: LocalLibrary? = null

    /** The phone's library (folders, favourite, trash, search, details); tests can pin another. */
    var localLibrary: LocalLibrary
        get() = localLibraryOverride ?: device.library
        set(value) { localLibraryOverride = value }

    /**
     * URLs for phone assets (the bare MediaStore id in, `localmedia://` thumbnails and
     * `content://` originals out); [mediaUrls] routes `local:` ids here.
     */
    var localMediaUrls: MediaUrls = LocalMediaUrls

    /** With a server: the phone and Immich together (favourite both copies, delete where asked, albums + folders). */
    private val serverLibrary: LibrarySource by lazy { MergedLibrary(immichLibrary, localLibrary) { monthMatcher } }

    /** Screenshots and tests pin a fake library here; null follows the active configuration. */
    private var libraryOverride: LibrarySource? = null

    /**
     * Albums, search, people, map, memories, sharing, favourite and trash for what the app shows
     * right now: the merged library (phone + Immich) with a server, else the phone. Setting it pins a library (screenshots use fake data).
     */
    var library: LibrarySource
        get() = libraryOverride ?: if (hasServer) serverLibrary else localLibrary
        set(value) { libraryOverride = value }

    /** Pairs phone items with their Immich copies (SHA-1, else a filename/date/size guess); cached in the index. */
    val matcher: ImmichMatcher by lazy {
        ImmichMatcher(device.db.matches(), device.dao, ImmichRemoteLookup { api() }).also { m ->
            device.onHashed = { ids -> if (hasServer) m.onHashed(ids) }
        }
    }

    private var monthMatcherOverride: MonthMatcher? = null

    /** What the merged timeline asks for matches; tests and screenshots pin a fake. */
    var monthMatcher: MonthMatcher
        get() = monthMatcherOverride ?: matcher
        set(value) { monthMatcherOverride = value }

    /** The phone's timeline as it is right now ([localTimeline] can be re-pinned). */
    private val currentLocalTimeline = object : TimelineSource {
        override suspend fun buckets(filter: TimelineFilter) = localTimeline.buckets(filter)
        override suspend fun bucket(filter: TimelineFilter, key: String) = localTimeline.bucket(filter, key)
    }

    /** With a server: the phone and Immich in one timeline (docs/SOURCES.md §9). */
    val mergedTimeline = MergedTimelineSource(currentLocalTimeline, immichTimeline) { monthMatcher }

    init {
        // Another server or account: old matches don't apply; hashing only runs while there's a server.
        appScope.launch {
            settings.server.map { it?.let { s -> s.publicUrl + "|" + s.userId } }.distinctUntilChanged().drop(1).collect {
                runCatching { matcher.clear() }
                runCatching { device.scheduleHashing() }
            }
        }
    }

    /** The timeline source for the active configuration; [TimelineRegistry.source] can still pin one in tests. */
    private val activeTimeline = object : TimelineSource {
        // Phone folders and timelines pinned to the phone (Library → On this device) read the index even with a server.
        private fun current(filter: TimelineFilter) = if (hasServer && !filter.isLocal) mergedTimeline else localTimeline
        override suspend fun buckets(filter: TimelineFilter) = current(filter).buckets(filter)
        override suspend fun bucket(filter: TimelineFilter, key: String) = current(filter).bucket(filter, key)
    }

    /** Which sources are showing and what they can do together (drives every capability check in the UI). */
    fun sources(): ActiveSources = ActiveSources(
        kinds = if (hasServer) setOf(MediaSourceKind.Local, MediaSourceKind.Immich) else setOf(MediaSourceKind.Local),
        capabilities = library.capabilities,
    )

    /** Asset URLs for the grid, viewer and editors: each id goes to its own source. */
    fun mediaUrls(): MediaUrls = SourceMediaUrls(immich = api()?.let(::ImmichMediaUrls), local = localMediaUrls)

    /**
     * The one app-wide launcher for MediaStore consent dialogs (favourite, trash, write requests).
     * AppNav attaches it to the activity; call [ConsentLauncher.requestConsent] from UI actions.
     */
    val consent = ConsentLauncher()

    /** Cached timelines (main, favourites, albums, ...) shared by the grid and the viewer. */
    val timelines = TimelineRegistry(activeTimeline, session = { sessionKey() })

    /** The album list shared by the Albums tab, album pages and the add-to-album sheet. */
    val albums = AlbumStore({ library })

    /** The phone's folders, for Library → On this device while a server is connected (the Albums tab has server albums then). */
    val deviceFolders = AlbumStore({ localLibrary })

    /**
     * The index changed (new photos, edits, deletions, access changed): refresh the phone
     * timelines that have been shown, keeping what's on screen while they reload.
     */
    private fun onDeviceChanged() {
        timelines.forEachRepo { r ->
            if ((r.filter.isLocal || !hasServer) && r.filter.listId == null && r.status.value != TimelineRepository.Status.Idle) r.refresh(keepLoaded = true)
        }
        if (deviceFolders.albums.value != null) deviceFolders.refresh()
        if (!hasServer && albums.albums.value != null) albums.refresh()
    }

    /** Today's memories for the carousel above the timeline. */
    val memories = MemoryStore({ library })

    /** Small UI memories (last share option, album sort, recent searches). */
    val uiPrefs = UiPrefs(app)

    /** The app-wide snackbar (see LocalSnackbar). */
    val snackbar = SnackbarController()

    /** Main-thread scope for user actions that should finish even if their screen goes away. */
    val uiScope: CoroutineScope = MainScope()

    /** What the viewer and selection bars do (favourite, albums, share, archive, trash). */
    val actions: LibraryActions by lazy {
        LibraryActions(
            library = { library },
            albums = albums,
            snackbar = snackbar,
            scope = uiScope,
            launcher = AndroidShareLauncher(app),
            prefs = uiPrefs,
            shareDir = File(app.cacheDir, "share"),
            timelines = timelines,
            session = ::sessionKey,
        )
    }

    /** Actions for pages that only show the phone (its Trash under On this device): restore all / empty go to the phone. */
    val deviceActions: LibraryActions by lazy {
        LibraryActions(
            library = { localLibrary },
            albums = deviceFolders,
            snackbar = snackbar,
            scope = uiScope,
            launcher = AndroidShareLauncher(app),
            prefs = uiPrefs,
            shareDir = File(app.cacheDir, "share"),
            timelines = timelines,
        )
    }

    /** The Search tab's state, kept while switching tabs. */
    val search by lazy { SearchModel({ library }, timelines, uiPrefs, uiScope) }

    /**
     * Who is signed in, for dropping per-account caches. The user id alone: the public URL can
     * change mid-session (learned from `externalDomain` after a LAN sign-in) for the same account,
     * and dropping the timelines then would orphan the instances the screens hold. Without a
     * server it's [DEVICE_SESSION], so connecting or disconnecting starts the caches afresh.
     */
    fun sessionKey(): String = settings.server.value?.let { "immich:" + it.userId.ifEmpty { it.publicUrl } } ?: DEVICE_SESSION

    /**
     * The API for the saved server, or null when signed out. It's built on the public URL (the
     * "logical" address); [http] rewrites each request to whichever address is active.
     */
    /**
     * The Immich session (bearer) token from an email+password sign-in, for casting: create
     * short-lived child sessions with `POST /api/sessions` (which refuses API keys) and hand the
     * Chromecast `?sessionKey=` URLs. Null without a server or after an API-key sign-in; never log it.
     */
    fun serverSessionToken(): String? = settings.server.value?.castSessionToken

    fun api(): ImmichApi? = settings.server.value?.let { ImmichApi(it.publicUrl, it.apiKey, http) }

    /** Photo-editor saves (WorkManager jobs) and the viewer's "Original moved to Trash · UNDO". */
    val edits: EditSaveManager by lazy { EditSaveManager(app, { api() }, onSaved = { timelines.invalidateAll() }) }

    /** Video-editor saves (download → export → upload WorkManager jobs), originals cache and REPLACE undo. */
    val videoEdits: VideoSaveManager by lazy { VideoSaveManager(app, { api() }, onSaved = { timelines.invalidateAll() }) }

    companion object {
        /** [sessionKey] for the phone-only configuration. */
        const val DEVICE_SESSION = "device"

        /**
         * Builds the shared client. Order matters:
         * 1. the connection manager rewrites requests for the server to the active address (and
         *    retries once on public if the LAN address drops);
         * 2. the API key is added as `x-api-key` only for the public address, or the local one
         *    while it's verified on the current network, so keys never appear in URLs and never go
         *    to third parties (map tile hosts, other devices on the Wi-Fi);
         * 3. a network interceptor strips the key from any hop to another host, e.g. a redirect.
         */
        fun serverClient(base: OkHttpClient, connection: ConnectionManager): OkHttpClient =
            base.newBuilder()
                .addInterceptor(connection.interceptor)
                .addInterceptor(ApiKeyInterceptor(connection))
                .addNetworkInterceptor(ApiKeyGuard(connection))
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
    }
}
