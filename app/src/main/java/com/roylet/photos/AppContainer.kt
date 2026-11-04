package com.roylet.photos

import android.content.Context
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.video.VideoFrameDecoder
import com.roylet.photos.data.Settings
import com.roylet.photos.immich.ImmichApi
import com.roylet.photos.upload.NetworkMonitor
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Hand-rolled dependency container; one per process. */
class AppContainer(context: Context) {
    private val app = context.applicationContext
    val settings = Settings(app)
    val network = NetworkMonitor(app)

    /**
     * The one OkHttp client shared by the API, Coil, ExoPlayer and uploads. It adds the API key
     * as an `x-api-key` header to requests for the configured server only, so keys never appear
     * in URLs and are never sent to third parties (e.g. map tile hosts). The connection manager
     * will add its URL-rewriting interceptor here.
     */
    val http: OkHttpClient = ImmichApi.defaultClient.newBuilder()
        .addInterceptor(apiKeyInterceptor())
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val imageLoader: ImageLoader = ImageLoader.Builder(app)
        .components {
            add(OkHttpNetworkFetcherFactory(callFactory = { http }))
            add(VideoFrameDecoder.Factory())
        }
        .build()

    /** The API for the saved server, or null when signed out. */
    fun api(): ImmichApi? = settings.server.value?.let { ImmichApi(it.publicUrl, it.apiKey, http) }

    private fun apiKeyInterceptor() = Interceptor { chain ->
        val req = chain.request()
        val config = settings.server.value
        if (config == null || req.header("x-api-key") != null || req.header("Authorization") != null) return@Interceptor chain.proceed(req)
        val ours = listOfNotNull(config.publicUrl, config.localUrl).mapNotNull { it.toHttpUrlOrNull() }
            .any { it.host == req.url.host && it.port == req.url.port }
        chain.proceed(if (ours) req.newBuilder().header("x-api-key", config.apiKey).build() else req)
    }
}
