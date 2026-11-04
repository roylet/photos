package com.roylet.photos

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.roylet.photos.data.ServerConfig
import com.roylet.photos.ui.AppNav
import com.roylet.photos.ui.components.DarkButton
import com.roylet.photos.ui.components.DarkChip
import com.roylet.photos.ui.components.DarkIconButton
import com.roylet.photos.ui.components.DarkPanel
import com.roylet.photos.ui.components.brutalDark
import com.roylet.photos.ui.connect.ConnectScreen
import com.roylet.photos.ui.theme.BrutalDark
import com.roylet.photos.ui.theme.PhotosTheme
import com.roylet.photos.ui.theme.Type
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders screens with sample data to PNGs (no device or emulator needed):
 *   ./gradlew testDebugUnitTest -Pscreenshots --tests '*ScreenshotTest*'
 * Output: app/build/screenshots/
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h880dp-xhdpi")
class ScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val app get() = ApplicationProvider.getApplicationContext<PhotosApp>()
    private val outDir = File(System.getProperty("screenshotDir") ?: "build/screenshots")

    @Before fun only() = assumeTrue(System.getProperty("screenshots") == "true")

    @After fun signOut() = app.container.settings.signOut()

    private fun signIn() = app.container.settings.saveServer(
        ServerConfig(publicUrl = "https://photos.example.com", apiKey = "k", userId = "u-1", email = "me@example.com", name = "Roy"),
    )

    private fun shoot(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        // Infinite animations (spinners, progress stripes) would otherwise keep Compose from idling.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) { PhotosTheme(dark = dark) { content() } }
        }
        save(name)
    }

    private fun save(name: String) {
        compose.mainClock.advanceTimeBy(1200)
        val view = compose.activity.window.decorView.rootView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        outDir.mkdirs()
        File(outDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun connect() = shoot("01-connect") { ConnectScreen(app.container, onConnected = {}) }

    @Test fun timeline() {
        signIn()
        shoot("02-timeline") { AppNav(app.container) }
    }

    @Test @Config(qualifiers = "w900dp-h600dp-xhdpi")
    fun timelineTablet() {
        signIn()
        shoot("03-timeline-tablet") { AppNav(app.container) }
    }

    /** The dark brutalist kit the viewer and editor are built from. */
    @Test fun darkKit() = shoot("04-dark-kit", dark = true) {
        Column(Modifier.fillMaxSize().background(BrutalDark.Canvas).statusBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DarkIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", {})
                Spacer(Modifier.weight(1f))
                DarkIconButton(Icons.AutoMirrored.Rounded.Undo, "Undo", {})
                DarkIconButton(Icons.AutoMirrored.Rounded.Redo, "Redo", {}, enabled = false)
                DarkButton("Save", {})
            }
            Box(
                Modifier.fillMaxWidth().aspectRatio(4f / 3f).brutalDark(shadow = 0.dp)
                    .background(Brush.verticalGradient(listOf(Color(0xFFFFB347), Color(0xFF8B4513)))),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DarkChip("Crop", selected = false, onClick = {}, icon = Icons.Rounded.Crop)
                DarkChip("Light", selected = true, onClick = {}, icon = Icons.Rounded.WbSunny)
                DarkChip("Color", selected = false, onClick = {})
            }
            DarkPanel(Modifier.fillMaxWidth()) {
                Text("EXPOSURE", style = Type.Label, color = BrutalDark.MutedText)
                Spacer(Modifier.height(4.dp))
                Text("+0.35", style = Type.Stat, color = BrutalDark.Text)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DarkIconButton(Icons.Rounded.FavoriteBorder, "Favourite", {})
                DarkIconButton(Icons.Rounded.Share, "Share", {})
                DarkIconButton(Icons.Rounded.Edit, "Edit", {}, active = true)
                DarkIconButton(Icons.Rounded.Info, "Info", {})
            }
        }
    }
}
