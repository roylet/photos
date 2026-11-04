package com.roylet.photos

import android.app.Application
import android.os.Build
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader

class PhotosApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Robolectric tests drive the connection manager themselves; background probes there
        // would race tests for MockWebServer responses and make screenshots nondeterministic.
        if (Build.FINGERPRINT != "robolectric") container.connection.start()
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = container.imageLoader
}
