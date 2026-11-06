package com.roylet.photos

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import okio.Path.Companion.toOkioPath
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.video.VideoFrameDecoder
import com.roylet.photos.data.Settings
import com.roylet.photos.cast.AndroidCastServiceLauncher
import com.roylet.photos.cast.AndroidJpegTranscoder
import com.roylet.photos.cast.AndroidPhoneFiles
import com.roylet.photos.cast.CastController
import com.roylet.photos.cast.CastResolver
import com.roylet.photos.cast.CastSessionManager
import com.roylet.photos.cast.CastSignIn
import com.roylet.photos.cast.CastUrls
import com.roylet.photos.cast.HttpImmichRelay
import com.roylet.photos.cast.Media3CastDevice
import com.roylet.photos.cast.PhoneMediaProxy
import com.roylet.photos.cast.RelayLifecycle
import com.roylet.photos.immich.await
import com.roylet.photos.domain.library.LocalLibrary
import com.roylet.photos.domain.library.LocalTimelineSource
import com.roylet.photos.domain.timeline.TimelineRepository
import com.roylet.photos.local.DeviceMedia
import com.roylet.photos.local.LocalMediaUrls
import com.roylet.photos.local.LocalThumbnailFetcher
import com.roylet.photos.merge.MergedLibrary
import com.roylet.photos.domain.source.AssetRef
import com.roylet.photos.editor.save.EditSource
import com.roylet.photos.editor.save.ImmichEditSource
import com.roylet.photos.editor.save.LocalEditSource
import com.roylet.photos.editor.save.RoutingEditSource
import com.roylet.photos.editor.save.SaveStarter
import com.roylet.photos.local.AndroidLocalTrasher
import com.roylet.photos.local.Sha1Worker
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
import com.roylet.photos.domain.library.DeviceFolder
import com.roylet.photos.domain.library.DeviceFolderStore
import com.roylet.photos.domain.library.DeviceFolders
import com.roylet.photos.domain.library.PhotosFolderMode
import com.roylet.photos.local.MediaStoreLibrary
import com.roylet.photos.ui.permissions.MediaAccess
import com.roylet.photos.ui.permissions.MediaPermissions
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
import com.roylet.photos.backup.AndroidBackupConditions
import com.roylet.photos.backup.AndroidBackupFiles
import com.roylet.photos.backup.BackupEngine
import com.roylet.photos.backup.BackupManager
import com.roylet.photos.backup.BackupPrefs
import com.roylet.photos.backup.ImmichBackupRemote
import com.roylet.photos.backup.WorkBackupScheduler
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
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
            excludedFromPhotos = { phoneFolders.excludedFromPhotos() },
            hiddenFolders = { phoneFolders.hiddenFolders() },
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
    private val serverLibrary: LibrarySource by lazy { MergedLibrary(immichLibrary, localLibrary, remoteDeletions = backup) { monthMatcher } }

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
                mergedTimeline.reset()
                runCatching { device.scheduleHashing() }
                runCatching { backup.onServerChanged(it != null) }
                // Signed out: the account's thumbnails and previews don't stay on the phone.
                if (it == null) runCatching { imageLoader.memoryCache?.clear(); imageLoader.diskCache?.clear() }
            }
        }
    }

    /** The timeline source for the active configuration; [TimelineRegistry.source] can still pin one in tests. */
    private val activeTimeline = object : TimelineSource {
        // With a server everything goes through the merged source: phone folders and timelines pinned to
        // the phone (Library → On this device) still read only the index, with Immich's copies marked.
        private fun current(filter: TimelineFilter) = if (hasServer) mergedTimeline else localTimeline
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
            // With a server the main timeline, favourites and trash are merged: they show phone items too.
            val showsPhone = r.filter.isLocal || !hasServer || mergedTimeline.includesPhone(r.filter)
            if (showsPhone && r.filter.listId == null && r.status.value != TimelineRepository.Status.Idle) r.refresh(keepLoaded = true)
        }
        if (deviceFolders.albums.value != null) deviceFolders.refresh()
        if (!hasServer && albums.albums.value != null) albums.refresh()
        // Covers and counts follow the index; with "Camera + selected" a new folder must be known
        // before the Photos timeline reloads, or it would show there until the next change.
        if (phoneFolders.folders.value != null || phoneFolders.prefs.value.mode != PhotosFolderMode.All) {
            appScope.launch {
                phoneFolders.reload()
                if (phoneFolders.prefs.value.mode != PhotosFolderMode.All) refreshPhotosTimeline()
            }
        }
        runCatching { backup.onIndexChanged() }
    }

    /**
     * Library → On this device: the phone's folders, named and ordered Google Photos style, with
     * the user's hidden folders and which ones show in Photos (kept in [uiPrefs]).
     */
    val phoneFolders: DeviceFolderStore by lazy {
        DeviceFolderStore(::loadPhoneFolders, uiPrefs.folderPrefs, { uiPrefs.folderPrefs = it }, appScope).also { store ->
            store.onPhotosChanged = { mergedTimeline.reset(); refreshPhotosTimeline() }
        }
    }

    private suspend fun loadPhoneFolders(): List<DeviceFolder> {
        val lib = localLibrary
        if (lib is MediaStoreLibrary) {
            if (MediaPermissions.access(app) == MediaAccess.Denied) return emptyList()
            device.indexer.awaitReady()
            return lib.deviceFolders()
        }
        // A pinned library (tests, screenshots): its folders as albums.
        return lib.albums().mapNotNull { a ->
            val bucket = a.id.removePrefix(TimelineFilter.FOLDER_PREFIX).takeIf { a.id.startsWith(TimelineFilter.FOLDER_PREFIX) }?.toLongOrNull() ?: return@mapNotNull null
            DeviceFolders.of(bucket, a.name, a.description, null, a.assetCount, a.updatedAt, a.coverAssetId)
        }
    }

    /**
     * The Photos timeline and Favorites, after what they show from the phone's folders changed (a
     * folder hidden or shown, Show in Photos, the mode): counts, months and the scrubber follow.
     */
    private fun refreshPhotosTimeline() {
        timelines.forEachRepo { r ->
            if (r.filter in PHONE_FOLDER_FILTERED && r.status.value != TimelineRepository.Status.Idle) r.refresh(keepLoaded = true)
        }
    }

    /* ---------------- Backup (docs/SOURCES.md §10) ---------------- */

    val backupPrefs = BackupPrefs(app)

    /**
     * Phone → Immich backup: per-folder choices, options, state and runs. Folder cards use
     * `setFolderEnabled` / `isFolderEnabled` / `folderStatus`.
     */
    val backup: BackupManager by lazy {
        BackupManager(
            store = device.appDb.backup(),
            queue = device.db.backup(),
            media = device.dao,
            prefs = backupPrefs,
            scheduler = WorkBackupScheduler(app),
            conditions = AndroidBackupConditions(app, network),
            server = settings.server.map { it != null }.stateIn(appScope, SharingStarted.Eagerly, hasServer),
            scope = appScope,
        )
    }

    /** One backup run's loop: hashes, bulk-upload-check, streaming uploads on the shared client, results into the match cache. */
    val backupEngine: BackupEngine by lazy {
        BackupEngine(
            media = device.dao,
            queue = device.db.backup(),
            matches = device.db.matches(),
            store = device.appDb.backup(),
            remote = { api()?.let(::ImmichBackupRemote) },
            open = { Sha1Worker.openOriginal(app, it) },
            fileInfo = { AndroidBackupFiles.info(app, it) },
            onRecorded = {
                runCatching { matcher.noteUploaded() }
                // The merged timeline shows the new copies as on both sides.
                uiScope.launch { onDeviceChanged() }
            },
        )
    }

    /** Whether any of [ids] (phone or Immich ids) is in a folder backup covers: the delete sheet warns then. */
    suspend fun inBackedUpFolder(ids: List<String>): Boolean {
        if (!hasServer) return false
        val phone = ids.mapNotNull { AssetRef.parse(it)?.mediaStoreId }
        val remote = ids.filterNot(AssetRef::isLocal)
        val viaCopies = if (remote.isEmpty()) emptyList() else runCatching { monthMatcher.cachedLocal(remote).values.toList() }.getOrDefault(emptyList())
        return runCatching { backup.inBackedUpFolder(phone + viaCopies) }.getOrDefault(false)
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
            inBackedUpFolder = ::inBackedUpFolder,
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

    /* ---------------- Casting (docs/SOURCES.md §11) ---------------- */

    /** The Cast SDK and Media3's remote player; [Media3CastDevice.init] runs from MainActivity (main thread). */
    val castDevice: Media3CastDevice by lazy { Media3CastDevice(app) }

    /** Immich child sessions for the TV's `?sessionKey=` URLs; null without a server. */
    private var castSessionsHeld: CastSessionManager? = null
    private var castSessionsFor: String? = null

    @Synchronized
    fun castSessions(): CastSessionManager? {
        val s = settings.server.value ?: return null
        val key = s.publicUrl + "|" + s.userId
        if (castSessionsFor != key) {
            castSessionsFor = key
            castSessionsHeld = CastSessionManager(http, { settings.server.value?.publicUrl }, ::serverSessionToken)
        }
        return castSessionsHeld
    }

    /** Where direct cast URLs point: the public HTTPS address, if it qualifies and the setting is on. */
    fun castDirectBase(): String? = CastUrls.directBase(settings.server.value?.publicUrl, uiPrefs.castUsePublic.value)

    /** The phone-side media server the TV reads phone-only items from (started by [CastService]). */
    val castRelay: PhoneMediaProxy by lazy {
        val env = AndroidNetworkEnvironment(app)
        PhoneMediaProxy(
            wifiAddress = { env.lan()?.address },
            phone = AndroidPhoneFiles(app) { device.dao },
            immich = { settings.server.value?.let { HttpImmichRelay(http, it.publicUrl) } },
            transcoder = AndroidJpegTranscoder(),
        )
    }

    private var castOverride: CastController? = null

    /** Casting: what's on the TV, the slideshow, and the session's clean-up (docs/SOURCES.md §11); tests can pin a fake-backed one. */
    var cast: CastController
        get() = castOverride ?: defaultCast
        set(value) { castOverride = value }

    private val defaultCast: CastController by lazy {
        val resolver = CastResolver(
            directBase = ::castDirectBase,
            keys = ::castSessions,
            probe = { url -> castProbe(url) },
            proxy = castRelay,
            relayNote = { if (uiPrefs.castUsePublic.value) CastResolver.NOTE_NO_PUBLIC else CastResolver.NOTE_PUBLIC_OFF },
        )
        val relay = object : RelayLifecycle {
            override fun start() = castRelay.start()
            override fun stop() = castRelay.stop()
        }
        CastController(castDevice, resolver, ::castSessions, relay, AndroidCastServiceLauncher(app), uiScope)
    }

    /** "Sign in once to cast": a login session for an API-key sign-in (or a revoked one); stored encrypted. */
    val castSignIn: CastSignIn by lazy {
        CastSignIn(
            http,
            server = { settings.server.value },
            store = { token -> settings.updateServer { it.copy(castSessionToken = token) } },
            stored = { serverSessionToken() != null },
            onSignedIn = { cast.onSignedIn() },
        )
    }

    /** HEAD of a cast URL through the shared client: the Content-Type the TV will get. */
    private suspend fun castProbe(url: String): String? = runCatching {
        http.newCall(okhttp3.Request.Builder().url(url).head().build()).await().use { if (it.isSuccessful) it.header("Content-Type") else null }
    }.getOrNull()

    fun api(): ImmichApi? = settings.server.value?.let { ImmichApi(it.publicUrl, it.apiKey, http) }

    /** Photo-editor saves (WorkManager jobs) and the viewer's "Original moved to Trash · UNDO". */
    val edits: EditSaveManager by lazy {
        EditSaveManager(app, { api() }, onSaved = { timelines.invalidateAll() }, localSink = { cp, onCp -> device.saveSink(cp, onCp) })
    }

    /** Where the editors read originals: `local:` ids from the phone, the rest from Immich. */
    val editSource: EditSource by lazy {
        RoutingEditSource(ImmichEditSource { api() }, LocalEditSource(device.dao, device.appDao) { Sha1Worker.openOriginal(app, it) })
    }

    /**
     * The Immich copy of a phone item (merged timeline), or null. Only a SHA-1-confirmed copy:
     * the editors' Both / Immich saves can trash it (Replace), so a guessed pair doesn't count.
     */
    suspend fun remoteCopyOf(id: String): String? {
        if (!hasServer) return null
        val key = AssetRef.parse(id)?.mediaStoreId ?: return null
        return runCatching { monthMatcher.cachedRemote(listOf(key))[key]?.takeIf { !it.tentative }?.remoteId }.getOrNull()
    }

    /** Asks for consent before a phone Replace is queued (the worker can't show the dialog). */
    val saveStarter = SaveStarter { id ->
        val key = AssetRef.parse(id)?.mediaStoreId ?: return@SaveStarter false
        val video = runCatching { device.dao.byId(key)?.isVideo }.getOrNull() ?: false
        AndroidLocalTrasher.requestEditConsent(app, consent, key, video)
    }

    /** Video-editor saves (download → export → upload WorkManager jobs), originals cache and REPLACE undo. */
    val videoEdits: VideoSaveManager by lazy {
        VideoSaveManager(app, { api() }, onSaved = { timelines.invalidateAll() }, sources = { editSource }, localSink = { cp, onCp -> device.saveSink(cp, onCp) })
    }

    companion object {
        /** [sessionKey] for the phone-only configuration. */
        const val DEVICE_SESSION = "device"

        /** Timelines that leave out phone folders (hidden, or Show in Photos off): reloaded when those choices change. */
        val PHONE_FOLDER_FILTERED = setOf(TimelineFilter.Timeline, TimelineFilter.Favorites)

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
