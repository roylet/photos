package com.roylet.photos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.graphics.toArgb
import com.roylet.photos.ui.AppNav
import com.roylet.photos.ui.theme.Brutal
import com.roylet.photos.ui.theme.PhotosTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Brutal.Paper.toArgb(), Brutal.Ink.toArgb()),
            navigationBarStyle = SystemBarStyle.light(Brutal.Paper.toArgb(), Brutal.Ink.toArgb()),
        )
        val container = (application as PhotosApp).container
        setContent {
            PhotosTheme {
                AppNav(container)
            }
        }
    }
}
