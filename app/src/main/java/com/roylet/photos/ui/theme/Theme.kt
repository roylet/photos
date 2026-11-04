package com.roylet.photos.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
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

/**
 * The semantic colours every component and screen draws with, read through [Palette] (or
 * [LocalBrutalColors]). [LightBrutalColors] is exactly the original cream-paper look;
 * [DarkBrutalColors] is the same brand on near-black paper with cream outlines and type.
 * Fixed brand colours (orange, the ink that sits on orange) stay on [Brutal].
 */
@Immutable
data class BrutalColors(
    /** Screen background. */
    val paper: Color,
    /** Cards, buttons, fields. */
    val card: Color,
    /** Recessed/secondary fills: progress tracks, switch tracks, inset panels. */
    val sand: Color,
    /** Primary text, icons, and the fill of inverted elements (selected chips, snackbars). */
    val ink: Color,
    /** 2dp outlines. */
    val border: Color,
    /** Hard drop shadows. */
    val shadow: Color,
    val mutedInk: Color,
    val accent: Color,
    /** Text and icons on [accent]. */
    val onAccent: Color,
    // Tag and tile fills; text on them is [ink].
    val peach: Color,
    val apricot: Color,
    val mint: Color,
    val lilac: Color,
    val yellow: Color,
    val blue: Color,
    /** Danger fills (delete buttons, the OFFLINE chip). */
    val red: Color,
    /** Text/icons on the saturated fills [blue] and [red]. */
    val onStrong: Color,
    /** Red for error text and icons on [paper]/[card]. */
    val danger: Color,
    /** Green for success text on [paper]/[card]. */
    val success: Color,
    val noticeWarning: Color,
    val noticeError: Color,
    val noticeSuccess: Color,
    /** Behind sheets and dialogs. */
    val scrim: Color,
    /** Empty image cells and thumbhash fallbacks. */
    val placeholder: Color,
    /** Success icon on the (inverted, [ink]-filled) snackbar. */
    val snackSuccess: Color,
    val dark: Boolean,
)

val LightBrutalColors = BrutalColors(
    paper = Brutal.Paper, card = Brutal.Card, sand = Brutal.Sand, ink = Brutal.Ink, border = Brutal.Ink, shadow = Brutal.Ink,
    mutedInk = Brutal.MutedInk, accent = Brutal.Orange, onAccent = Brutal.Ink,
    peach = Brutal.Peach, apricot = Brutal.Apricot, mint = Brutal.Mint, lilac = Brutal.Lilac, yellow = Brutal.Yellow, blue = Brutal.Blue,
    red = Brutal.Red, onStrong = Brutal.Card, danger = Brutal.Red, success = Brutal.Green,
    noticeWarning = Color(0xFFFFF0C2), noticeError = Color(0xFFFFE1E3), noticeSuccess = Brutal.Mint,
    scrim = Brutal.Ink.copy(alpha = 0.45f), placeholder = Color(0xFFE9DFCC), snackSuccess = Brutal.Mint, dark = false,
)

/** Cream used for type and outlines on the dark paper (15.8:1 on paper). */
private val Cream = Color(0xFFF3EADB)

val DarkBrutalColors = BrutalColors(
    paper = Color(0xFF121110), card = Color(0xFF1C1A17), sand = Color(0xFF2A2622), ink = Cream, border = Cream, shadow = Cream,
    mutedInk = Color(0xFFA79F92), accent = Brutal.Orange, onAccent = Brutal.Ink,
    peach = Color(0xFF5A3A24), apricot = Color(0xFF6A3A1E), mint = Color(0xFF1F5236), lilac = Color(0xFF4A3C80), yellow = Color(0xFF6B5410), blue = Color(0xFF2E4FC7),
    red = Color(0xFFB3262F), onStrong = Cream, danger = Color(0xFFFF6B75), success = Color(0xFF5CD68A),
    noticeWarning = Color(0xFF3A3015), noticeError = Color(0xFF3D1C1F), noticeSuccess = Color(0xFF1D3A2B),
    scrim = Color.Black.copy(alpha = 0.6f), placeholder = Color(0xFF26231F), snackSuccess = Brutal.Green, dark = true,
)

val LocalBrutalColors = staticCompositionLocalOf { LightBrutalColors }

/** Shorthand for the current [BrutalColors]: `Palette.ink`, `Palette.card`, ... */
object Palette {
    val current: BrutalColors @Composable @ReadOnlyComposable get() = LocalBrutalColors.current
    val paper: Color @Composable @ReadOnlyComposable get() = LocalBrutalColors.current.paper
    val card: Color @Composable @ReadOnlyComposable get() = LocalBrutalColors.current.card
    val sand: Color @Composable @ReadOnlyComposable get() = LocalBrutalColors.current.sand
    val ink: Color @Composable @ReadOnlyComposable get() = LocalBrutalColors.current.ink
    val border: Color @Composable @ReadOnlyComposable get() = LocalBrutalColors.current.border
    val shadow: Color @Composable @ReadOnlyComposable get() = LocalBrutalColors.current.shadow
    val mutedInk: Color @Composable @ReadOnlyComposable get() = LocalBrutalColors.current.mutedInk
    val peach: Color @Composable @ReadOnlyComposable get() = LocalBrutalColors.current.peach
    val apricot: Color @Composable @ReadOnlyComposable get() = LocalBrutalColors.current.apricot
    val mint: Color @Composable @ReadOnlyComposable get() = LocalBrutalColors.current.mint
    val lilac: Color @Composable @ReadOnlyComposable get() = LocalBrutalColors.current.lilac
    val yellow: Color @Composable @ReadOnlyComposable get() = LocalBrutalColors.current.yellow
    val blue: Color @Composable @ReadOnlyComposable get() = LocalBrutalColors.current.blue
    val red: Color @Composable @ReadOnlyComposable get() = LocalBrutalColors.current.red
    val onStrong: Color @Composable @ReadOnlyComposable get() = LocalBrutalColors.current.onStrong
    val danger: Color @Composable @ReadOnlyComposable get() = LocalBrutalColors.current.danger
    val success: Color @Composable @ReadOnlyComposable get() = LocalBrutalColors.current.success
    val scrim: Color @Composable @ReadOnlyComposable get() = LocalBrutalColors.current.scrim
    val placeholder: Color @Composable @ReadOnlyComposable get() = LocalBrutalColors.current.placeholder
    val dark: Boolean @Composable @ReadOnlyComposable get() = LocalBrutalColors.current.dark
}

/** Tweens every colour of the palette towards [target], so a theme switch cross-fades instead of snapping. */
@Composable
fun animateBrutalColors(target: BrutalColors, durationMs: Int = 350): BrutalColors {
    val spec = tween<Color>(durationMs)
    @Composable fun a(c: Color, label: String) = animateColorAsState(c, spec, label = label).value
    return BrutalColors(
        paper = a(target.paper, "paper"), card = a(target.card, "card"), sand = a(target.sand, "sand"), ink = a(target.ink, "ink"),
        border = a(target.border, "border"), shadow = a(target.shadow, "shadow"), mutedInk = a(target.mutedInk, "mutedInk"),
        accent = target.accent, onAccent = target.onAccent,
        peach = a(target.peach, "peach"), apricot = a(target.apricot, "apricot"), mint = a(target.mint, "mint"), lilac = a(target.lilac, "lilac"),
        yellow = a(target.yellow, "yellow"), blue = a(target.blue, "blue"), red = a(target.red, "red"), onStrong = a(target.onStrong, "onStrong"),
        danger = a(target.danger, "danger"), success = a(target.success, "success"),
        noticeWarning = a(target.noticeWarning, "noticeWarning"), noticeError = a(target.noticeError, "noticeError"), noticeSuccess = a(target.noticeSuccess, "noticeSuccess"),
        scrim = a(target.scrim, "scrim"), placeholder = a(target.placeholder, "placeholder"), snackSuccess = a(target.snackSuccess, "snackSuccess"),
        dark = target.dark,
    )
}

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
 * App theme. [dark] switches to the dark palette (the whole app in dark mode, and always the
 * viewer and editor). With [animate], a change of [dark] cross-fades every colour.
 */
@Composable
fun PhotosTheme(dark: Boolean = false, animate: Boolean = false, content: @Composable () -> Unit) {
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
    val target = if (dark) DarkBrutalColors else LightBrutalColors
    val colors = if (animate) animateBrutalColors(target) else target
    CompositionLocalProvider(LocalBrutalColors provides colors) {
        MaterialTheme(
            colorScheme = scheme,
            typography = Typography(bodyLarge = Type.Body, bodyMedium = Type.Small),
            content = content,
        )
    }
}
