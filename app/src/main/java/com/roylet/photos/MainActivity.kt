package com.roylet.photos

import android.app.UiModeManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import com.roylet.photos.data.ThemeMode
import com.roylet.photos.ui.AppNav
import com.roylet.photos.ui.theme.DarkBrutalColors
import com.roylet.photos.ui.theme.LightBrutalColors
import com.roylet.photos.ui.theme.PhotosTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as PhotosApp).container
        applySystemBars(container.uiPrefs.themeMode.value)
        setContent {
            val mode by container.uiPrefs.themeMode.collectAsState()
            val dark = when (mode) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
            LaunchedEffect(mode, dark) {
                applySystemBars(mode)
                rememberNightMode(mode)
            }
            PhotosTheme(dark = dark, animate = true) {
                // Test tags double as resource ids so the baseline-profile generator (UiAutomator) can find the grid and pager.
                Box(Modifier.semantics { testTagsAsResourceId = true }) { AppNav(container) }
            }
        }
    }

    /** Status/nav bar icons follow the theme: dark icons on cream paper, light icons on the dark paper. */
    private fun applySystemBars(mode: ThemeMode) {
        val light = LightBrutalColors.paper.toArgb()
        val dark = DarkBrutalColors.paper.toArgb()
        val style = when (mode) {
            ThemeMode.System -> SystemBarStyle.auto(light, dark)
            ThemeMode.Light -> SystemBarStyle.light(light, LightBrutalColors.ink.toArgb())
            ThemeMode.Dark -> SystemBarStyle.dark(dark)
        }
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }

    /**
     * Tells the system about a forced Light/Dark choice (Android 12+), so the next cold start's
     * splash and window background already use the right values/values-night theme. The activity
     * handles uiMode changes itself, so this doesn't restart it.
     */
    private fun rememberNightMode(mode: ThemeMode) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val ui = getSystemService(UiModeManager::class.java) ?: return
        val night = when (mode) {
            ThemeMode.System -> UiModeManager.MODE_NIGHT_AUTO
            ThemeMode.Light -> UiModeManager.MODE_NIGHT_NO
            ThemeMode.Dark -> UiModeManager.MODE_NIGHT_YES
        }
        runCatching { ui.setApplicationNightMode(night) }
    }
}
