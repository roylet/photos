package com.roylet.photos.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.roylet.photos.R

/** The neobrutalist palette carried over from immichup: cream paper, ink outlines, safety orange. */
object Brutal {
    val Paper = Color(0xFFFFF7EA)
    val Card = Color(0xFFFFFFFF)
    val Ink = Color(0xFF111111)
    val Orange = Color(0xFFFF5A1F)
    val Peach = Color(0xFFFFE3CC)
    val Sand = Color(0xFFF2E9D8)
    val Apricot = Color(0xFFFFD9BE)
    val MutedInk = Color(0xFF6B6255)
    val Red = Color(0xFFE63946)
    val Green = Color(0xFF2E9E58)
    val Yellow = Color(0xFFFFC53D)
    val Blue = Color(0xFF3D6BFF)
    val Lilac = Color(0xFFC9B8FF)
    val Mint = Color(0xFFB8F2CF)
}

/**
 * The dark brutalist variant for the photo viewer and editor: an ink canvas so photos pop,
 * raised panels a step lighter, light (paper-coloured) outlines, and the same orange accent.
 * Shadows stay hard but are pure black so they still read against the ink background.
 */
object BrutalDark {
    val Canvas = Color(0xFF111111)
    val Panel = Color(0xFF1C1B19)
    val Raised = Color(0xFF2A2825)
    val Line = Color(0xFFFFF7EA)
    val Shadow = Color(0xFF000000)
    val Text = Color(0xFFFFF7EA)
    val MutedText = Color(0xFFB7AE9F)
    val Orange = Brutal.Orange
    val Red = Color(0xFFFF5C68)
    val Mint = Brutal.Mint
    val Yellow = Brutal.Yellow
}

/** Colours a screen should draw with, so shared widgets can work in both the light and dark variants. */
@Immutable
data class BrutalColors(
    val background: Color,
    val surface: Color,
    val raised: Color,
    val border: Color,
    val shadow: Color,
    val text: Color,
    val mutedText: Color,
    val accent: Color,
    val onAccent: Color,
    val dark: Boolean,
)

val LightBrutalColors = BrutalColors(
    background = Brutal.Paper, surface = Brutal.Card, raised = Brutal.Sand, border = Brutal.Ink, shadow = Brutal.Ink,
    text = Brutal.Ink, mutedText = Brutal.MutedInk, accent = Brutal.Orange, onAccent = Brutal.Ink, dark = false,
)

val DarkBrutalColors = BrutalColors(
    background = BrutalDark.Canvas, surface = BrutalDark.Panel, raised = BrutalDark.Raised, border = BrutalDark.Line, shadow = BrutalDark.Shadow,
    text = BrutalDark.Text, mutedText = BrutalDark.MutedText, accent = BrutalDark.Orange, onAccent = Brutal.Ink, dark = true,
)

val LocalBrutalColors = staticCompositionLocalOf { LightBrutalColors }

val Archivo = FontFamily(
    Font(R.font.archivo_500, FontWeight.Medium),
    Font(R.font.archivo_700, FontWeight.Bold),
    Font(R.font.archivo_800, FontWeight.ExtraBold),
    Font(R.font.archivo_900, FontWeight.Black),
)

object Type {
    val Display = TextStyle(fontFamily = Archivo, fontWeight = FontWeight.Black, fontSize = 32.sp, lineHeight = 34.sp, letterSpacing = (-0.02).em)
    val Huge = TextStyle(fontFamily = Archivo, fontWeight = FontWeight.Black, fontSize = 56.sp, lineHeight = 56.sp, letterSpacing = (-0.03).em)
    val Headline = TextStyle(fontFamily = Archivo, fontWeight = FontWeight.Black, fontSize = 24.sp, lineHeight = 28.sp, letterSpacing = (-0.01).em)
    val Title = TextStyle(fontFamily = Archivo, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, lineHeight = 22.sp)
    val Stat = TextStyle(fontFamily = Archivo, fontWeight = FontWeight.Black, fontSize = 28.sp, lineHeight = 30.sp)
    val Label = TextStyle(fontFamily = Archivo, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 0.06.em)
    val Tiny = TextStyle(fontFamily = Archivo, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 0.08.em)
    val Body = TextStyle(fontSize = 15.sp, lineHeight = 21.sp)
    val Small = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
}

/**
 * App theme. [dark] switches to the ink-canvas variant used by the viewer and editor; the rest
 * of the app is always the light cream-paper look.
 */
@Composable
fun PhotosTheme(dark: Boolean = false, content: @Composable () -> Unit) {
    val scheme = if (dark) {
        darkColorScheme(
            primary = BrutalDark.Orange,
            onPrimary = Brutal.Ink,
            secondary = BrutalDark.Raised,
            onSecondary = BrutalDark.Text,
            background = BrutalDark.Canvas,
            onBackground = BrutalDark.Text,
            surface = BrutalDark.Panel,
            onSurface = BrutalDark.Text,
            surfaceVariant = BrutalDark.Raised,
            onSurfaceVariant = BrutalDark.MutedText,
            outline = BrutalDark.Line,
            error = BrutalDark.Red,
        )
    } else {
        lightColorScheme(
            primary = Brutal.Orange,
            onPrimary = Brutal.Ink,
            secondary = Brutal.Peach,
            onSecondary = Brutal.Ink,
            background = Brutal.Paper,
            onBackground = Brutal.Ink,
            surface = Brutal.Card,
            onSurface = Brutal.Ink,
            surfaceVariant = Brutal.Sand,
            onSurfaceVariant = Brutal.MutedInk,
            outline = Brutal.Ink,
            error = Brutal.Red,
        )
    }
    CompositionLocalProvider(LocalBrutalColors provides if (dark) DarkBrutalColors else LightBrutalColors) {
        MaterialTheme(
            colorScheme = scheme,
            typography = Typography(bodyLarge = Type.Body, bodyMedium = Type.Small),
            content = content,
        )
    }
}
