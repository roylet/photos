package com.roylet.photos

import android.content.Context
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.video.VideoFrameDecoder
import com.roylet.photos.data.Settings
import com.roylet.photos.immich.ImmichApi
import com.roylet.photos.net.AndroidNetworkEnvironment
import com.roylet.photos.net.ApiKeyGuard
import com.roylet.photos.net.ApiKeyInterceptor
import com.roylet.photos.net.ConnectionManager
import com.roylet.photos.net.LanScanner
import com.roylet.photos.net.ServerStore
import com.roylet.photos.upload.NetworkMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    val http: OkHttpClient = serverClient(ImmichApi.defaultClient, connection, settings)

    val imageLoader: ImageLoader = ImageLoader.Builder(app)
        .components {
            add(OkHttpNetworkFetcherFactory(callFactory = { http }))
            add(VideoFrameDecoder.Factory())
        }
        .build()

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
         * 2. the API key is added as `x-api-key` for the server's hosts only (public or local), so
         *    keys never appear in URLs and never go to third parties such as map tile hosts;
         * 3. a network interceptor strips the key from any hop to another host, e.g. a redirect.
         */
        fun serverClient(base: OkHttpClient, connection: ConnectionManager, settings: ServerStore): OkHttpClient =
            base.newBuilder()
                .addInterceptor(connection.interceptor)
                .addInterceptor(ApiKeyInterceptor { settings.server.value })
                .addNetworkInterceptor(ApiKeyGuard { settings.server.value })
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
    }
}
