package com.roylet.photos

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.roylet.photos.data.ConnectionMode
import com.roylet.photos.data.ServerConfig
import com.roylet.photos.data.ThemeMode
import com.roylet.photos.net.CheckStep
import com.roylet.photos.net.ConnState
import com.roylet.photos.net.ConnectionCheck
import com.roylet.photos.net.StepStatus
import com.roylet.photos.net.ScanState
import com.roylet.photos.ui.components.ConnectionChip
import com.roylet.photos.ui.settings.SettingsContent
import com.roylet.photos.ui.theme.Palette
import com.roylet.photos.ui.theme.PhotosTheme
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Settings and the connection chip, rendered like [ScreenshotTest] (`-Pscreenshots`). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h880dp-xhdpi")
class SettingsScreenshotTest(private val darkTheme: Boolean) {
    companion object {
        /** Every screen is shot in the light theme and again in dark (as name-dark.png). */
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0}")
        fun themes(): List<Array<Any>> = listOf(arrayOf(false), arrayOf(true))
    }

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("screenshotDir") ?: "build/screenshots")

    @Before fun only() = assumeTrue(System.getProperty("screenshots") == "true")

    private val sample = ServerConfig(
        publicUrl = "https://photos.example.com",
        localUrl = "http://192.168.1.20:2283",
        apiKey = "k",
        userId = "u-1",
        email = "me@example.com",
        name = "Roy",
        mode = ConnectionMode.AUTO,
    )

    private fun shoot(name: String, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent { CompositionLocalProvider(LocalInspectionMode provides true) { PhotosTheme(dark = darkTheme) { content() } } }
        compose.mainClock.advanceTimeBy(1200)
        val view = compose.activity.window.decorView.rootView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        outDir.mkdirs()
        File(outDir, if (darkTheme) "$name-dark.png" else "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Composable
    private fun Settings(server: ServerConfig, state: ConnState, active: String, scan: ScanState, check: ConnectionCheck? = null, theme: ThemeMode = ThemeMode.System) = SettingsContent(
        server = server, state = state, activeUrl = active, scan = scan, check = check, theme = theme,
        onBack = {}, onMode = {}, onSavePublic = { true }, onSaveLocal = { com.roylet.photos.net.LocalUrlResult.Verified }, onScan = {}, onSignOut = {},
    )

    @Test fun settingsHome() = shoot("10-settings-home") {
        Settings(sample, ConnState.Home, sample.localUrl!!, ScanState.Found(sample.localUrl!!))
    }

    @Test fun settingsScanning() = shoot("11-settings-scanning") {
        val away = sample.copy(localUrl = null, mode = ConnectionMode.REMOTE)
        Settings(away, ConnState.Remote, away.publicUrl, ScanState.Running(96, 253))
    }

    /** Scrolled to the Connection check panel and the Appearance chips. */
    @Test @Config(qualifiers = "w400dp-h1500dp-xhdpi")
    fun settingsCheck() = shoot("13-settings-check") {
        val check = ConnectionCheck(
            "Checking http://192.168.1.20:2283",
            listOf(
                CheckStep("Network", StepStatus.Pass, "Wi-Fi, 192.168.1.37"),
                CheckStep("Ping", StepStatus.Pass, "Answered in 14 ms"),
                CheckStep("Fingerprint", StepStatus.Warn, "Server version differs from the public address"),
                CheckStep("Account", StepStatus.Fail, "API key rejected (401)"),
                CheckStep("Result", StepStatus.Skipped, "Not used"),
            ),
        )
        Settings(sample, ConnState.Remote, sample.publicUrl, ScanState.NotFound, check, theme = if (darkTheme) ThemeMode.Dark else ThemeMode.Light)
    }

    @Test fun connectionChips() = shoot("12-connection-chips") {
        Column(Modifier.fillMaxSize().background(Palette.paper).statusBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ConnState.entries.forEach { ConnectionChip(it) }
        }
    }
}
