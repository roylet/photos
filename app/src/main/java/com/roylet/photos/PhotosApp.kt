package com.roylet.photos

import android.app.Application
import android.os.Build
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.roylet.photos.util.CrashLog
import com.roylet.photos.util.CrashReporter

class PhotosApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    /** The last crash's report (shown on the next launch and in Settings → About). */
    val crashLog: CrashLog by lazy { CrashLog.of(this) }

    override fun onCreate() {
        super.onCreate()
        // First, so a crash while the app starts up is recorded too. Robolectric runs make one app per test.
        if (Build.FINGERPRINT != "robolectric") CrashReporter(crashLog, CrashReporter.header(this)).install()
        container = AppContainer(this)
        // Robolectric tests drive the connection manager themselves; background probes there
        // would race tests for MockWebServer responses and make screenshots nondeterministic.
        if (Build.FINGERPRINT != "robolectric") {
            container.connection.start()
            // Index the phone's photos on every return to the foreground, and watch MediaStore while there.
            container.device.start()
            // Keeps backup's hourly job and new-media trigger in step with its switches.
            runCatching { container.backup.start() }
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = container.imageLoader
}
