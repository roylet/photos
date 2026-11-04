package com.roylet.photos

import android.content.Context
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.video.VideoFrameDecoder
import com.roylet.photos.data.Settings
import com.roylet.photos.domain.timeline.TimelineRegistry
import com.roylet.photos.domain.library.AlbumStore
import com.roylet.photos.domain.library.LibrarySource
import com.roylet.photos.domain.library.MemoryStore
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
import com.roylet.photos.data.UiPrefs
import com.roylet.photos.immich.ImmichException
import com.roylet.photos.immich.setFavorite
import com.roylet.photos.ui.actions.AndroidShareLauncher
import com.roylet.photos.ui.actions.LibraryActions
import com.roylet.photos.ui.components.SnackbarController
import java.io.File
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
        .components {
            add(OkHttpNetworkFetcherFactory(callFactory = { http }))
            add(VideoFrameDecoder.Factory())
        }
        .build()

    /** Cached timelines (main, favourites, albums, ...) shared by the grid and the viewer. */
    val timelines = TimelineRegistry(ImmichTimelineSource { api() }, session = { settings.server.value?.let { it.publicUrl + it.userId } })

    /** Albums, search, people, map, memories and sharing. Swappable so screenshots can use fake data. */
    var library: LibrarySource = ImmichLibrary({ api() }, userId = { settings.server.value?.userId })

    /** The album list shared by the Albums tab, album pages and the add-to-album sheet. */
    val albums = AlbumStore({ library })

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
            favoriteCall = { ids, fav -> (api() ?: throw ImmichException("Not signed in")).setFavorite(ids, fav) },
            launcher = AndroidShareLauncher(app),
            prefs = uiPrefs,
            shareDir = File(app.cacheDir, "share"),
            timelines = timelines,
        )
    }

    /**
     * The API for the saved server, or null when signed out. It's built on the public URL (the
     * "logical" address); [http] rewrites each request to whichever address is active.
     */
    fun api(): ImmichApi? = settings.server.value?.let { ImmichApi(it.publicUrl, it.apiKey, http) }

    companion object {
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
