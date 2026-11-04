package com.roylet.photos.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.roylet.photos.ui.theme.Brutal
import com.roylet.photos.ui.theme.LocalBrutalColors
import com.roylet.photos.ui.theme.Palette
import com.roylet.photos.ui.theme.Type

val BrutalShape = RoundedCornerShape(6.dp)

/**
 * The signature look: solid fill, 2dp ink outline, and a hard ink shadow offset down-right.
 * Space for the shadow is reserved in layout so neighbours never overlap it.
 */
fun Modifier.brutal(
    fill: Color = Color.Unspecified,
    shape: Shape = BrutalShape,
    shadow: Dp = 4.dp,
    border: Color = Color.Unspecified,
    reserve: Dp = shadow,
    shadowColor: Color = Color.Unspecified,
): Modifier =
    if (fill.isSpecified && border.isSpecified && (shadowColor.isSpecified || shadow <= 0.dp)) {
        brutalResolved(fill, shape, shadow, border, reserve, shadowColor)
    } else {
        // Unspecified colours come from the theme: card fill, border outline, shadow.
        composed {
            val c = LocalBrutalColors.current
            brutalResolved(fill.takeOrElse { c.card }, shape, shadow, border.takeOrElse { c.border }, reserve, shadowColor.takeOrElse { c.shadow })
        }
    }

private fun Modifier.brutalResolved(fill: Color, shape: Shape, shadow: Dp, border: Color, reserve: Dp, shadowColor: Color): Modifier = this
    .padding(end = reserve, bottom = reserve)
    .drawBehind {
        if (shadow > 0.dp) {
            val outline = shape.createOutline(size, layoutDirection, this)
            translate(shadow.toPx(), shadow.toPx()) { drawOutline(outline, shadowColor) }
        }
    }
    .clip(shape)
    .background(fill, shape)
    .border(2.dp, border, shape)

@Composable
fun BrutalCard(
    modifier: Modifier = Modifier,
    fill: Color = Palette.card,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    if (onClick == null) {
        Column(modifier.brutal(fill).padding(contentPadding), content = content)
    } else {
        Pressable(onClick = onClick, modifier = modifier, fill = fill) { Column(Modifier.padding(contentPadding), content = content) }
    }
}

/** A surface that sinks into its shadow while pressed, like the original's buttons. */
@Composable
fun Pressable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fill: Color = Palette.card,
    shadow: Dp = 4.dp,
    enabled: Boolean = true,
    shape: Shape = BrutalShape,
    border: Color = Palette.border,
    shadowColor: Color = Palette.shadow,
    /** What a disabled fill fades towards: the theme's paper. */
    disabledBlend: Color = Palette.paper,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val sink by animateDpAsState(if (pressed && enabled) shadow else 0.dp, tween(60), label = "sink")
    Box(
        modifier
            .padding(end = shadow, bottom = shadow)
            .offset { IntOffset(sink.roundToPx(), sink.roundToPx()) }
            .brutal(fill = if (enabled) fill else lerp(fill, disabledBlend, 0.55f), shape = shape, shadow = if (enabled) shadow - sink else 0.dp, border = border, reserve = 0.dp, shadowColor = shadowColor)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick),
        // Lets a fillMaxWidth/weight caller stretch the content while a bare button stays wrap-content.
        propagateMinConstraints = true,
    ) { content() }
}

enum class ButtonStyle { Primary, Outline, Danger, Dark, Ghost }

@Composable
fun BrutalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ButtonStyle = ButtonStyle.Primary,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    large: Boolean = false,
) {
    val (fill, ink) = when (style) {
        ButtonStyle.Primary -> Brutal.Orange to Brutal.Ink
        ButtonStyle.Outline -> Palette.card to Palette.ink
        ButtonStyle.Danger -> Palette.red to Palette.onStrong
        ButtonStyle.Dark -> Palette.ink to Palette.paper
        ButtonStyle.Ghost -> Color.Transparent to Palette.ink
    }
    val inner: @Composable () -> Unit = {
        Row(
            Modifier.padding(horizontal = if (large) 20.dp else 14.dp).height(if (large) 54.dp else 42.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (icon != null) {
                Icon(icon, null, tint = ink, modifier = Modifier.size(if (large) 22.dp else 18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text.uppercase(),
                style = if (large) Type.Label.copy(fontSize = Type.Label.fontSize * 1.15f) else Type.Label,
                color = ink.copy(alpha = if (enabled) 1f else 0.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    if (style == ButtonStyle.Ghost) {
        Box(modifier.clip(BrutalShape).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) { inner() }
    } else {
        Pressable(onClick, modifier, fill = fill, enabled = enabled) {
            Box(contentAlignment = Alignment.Center) { inner() }
        }
    }
}

@Composable
fun SquareIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, fill: Color = Palette.card, modifier: Modifier = Modifier) {
    Pressable(onClick, modifier, fill = fill, shadow = 3.dp) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription, tint = if (fill == Brutal.Orange) Brutal.Ink else Palette.ink, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Pressable(onClick, modifier, fill = if (selected) Palette.ink else Palette.card, shadow = if (selected) 0.dp else 3.dp, shape = RoundedCornerShape(50)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, tint = if (selected) Palette.paper else Palette.ink, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(text, style = Type.Label, color = if (selected) Palette.paper else Palette.ink, maxLines = 1)
        }
    }
}

@Composable
fun Tag(text: String, fill: Color = Palette.yellow, ink: Color = Palette.ink, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = Type.Tiny,
        color = ink,
        maxLines = 1,
        modifier = modifier
            .background(fill, RoundedCornerShape(3.dp))
            .border(1.5.dp, Palette.border, RoundedCornerShape(3.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp),
    )
}

@Composable
fun BrutalCheck(checked: Boolean, modifier: Modifier = Modifier, size: Dp = 24.dp, onClick: (() -> Unit)? = null) {
    Box(
        modifier
            .size(size)
            .background(if (checked) Brutal.Orange else Palette.card, RoundedCornerShape(4.dp))
            .border(2.dp, Palette.border, RoundedCornerShape(4.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Icon(Icons.Rounded.Check, null, tint = Brutal.Ink, modifier = Modifier.size(size * 0.75f))
    }
}

/**
 * Outlined progress bar. While [moving], diagonal stripes scroll across the fill so a slow
 * multi-gigabyte upload still visibly looks alive.
 */
@Composable
fun BrutalProgress(fraction: Float, modifier: Modifier = Modifier, height: Dp = 18.dp, fill: Color = Brutal.Orange, moving: Boolean = false) {
    val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(400), label = "progress")
    val phase = if (moving) {
        val t = rememberInfiniteTransition(label = "stripes")
        t.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "phase").value
    } else 0f
    val border = Palette.border
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(4.dp))
            .background(Palette.sand)
            .border(2.dp, border, RoundedCornerShape(4.dp))
            .drawBehind {
                val w = size.width * animated
                if (w <= 0f) return@drawBehind
                drawRect(fill, size = size.copy(width = w))
                if (moving) {
                    val stripe = 14.dp.toPx()
                    clipRect(right = w) {
                        var x = -2 * stripe + phase * 2 * stripe
                        while (x < w + size.height) {
                            val p = Path().apply {
                                moveTo(x, size.height); lineTo(x + stripe, size.height)
                                lineTo(x + stripe + size.height, 0f); lineTo(x + size.height, 0f); close()
                            }
                            drawPath(p, Color.Black.copy(alpha = 0.12f))
                            x += 2 * stripe
                        }
                    }
                }
                drawLine(border, Offset(w, 0f), Offset(w, size.height), 2.dp.toPx())
            },
    )
}

@Composable
fun BrutalTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    password: Boolean = false,
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    leading: ImageVector? = null,
) {
    Column(modifier) {
        if (label != null) {
            Text(label.uppercase(), style = Type.Tiny, color = Palette.mutedInk)
            Spacer(Modifier.height(6.dp))
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = Type.Body.copy(color = Palette.ink),
            cursorBrush = SolidColor(Brutal.Orange),
            keyboardOptions = keyboard,
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Row(
                    Modifier.fillMaxWidth().brutal(shadow = 3.dp).padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (leading != null) {
                        Icon(leading, null, tint = Palette.mutedInk, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                    }
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty()) Text(placeholder, style = Type.Body, color = Palette.mutedInk.copy(alpha = 0.7f), maxLines = 1)
                        inner()
                    }
                }
            },
        )
    }
}

enum class NoticeTone { Info, Warning, Error, Success }

@Composable
fun Notice(text: String, tone: NoticeTone = NoticeTone.Info, icon: ImageVector? = null, modifier: Modifier = Modifier, actions: (@Composable RowScope.() -> Unit)? = null) {
    val c = Palette.current
    val fill = when (tone) {
        NoticeTone.Info -> c.card
        NoticeTone.Warning -> c.noticeWarning
        NoticeTone.Error -> c.noticeError
        NoticeTone.Success -> c.noticeSuccess
    }
    Column(modifier.fillMaxWidth().brutal(fill, shadow = 3.dp).padding(14.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            if (icon != null) {
                Icon(icon, null, tint = if (tone == NoticeTone.Error) c.danger else c.ink, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
            }
            Text(text, style = Type.Small, color = c.ink, modifier = Modifier.weight(1f))
        }
        if (actions != null) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions)
        }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = Type.Label, color = Palette.mutedInk, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** Top app bar: big black title on paper with a 2dp rule underneath. */
@Composable
fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    below: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val rule = Palette.border
    Column(
        Modifier
            .fillMaxWidth()
            .background(Palette.paper)
            .windowInsetsPadding(WindowInsets.statusBars)
            .drawBehind { drawLine(rule, Offset(0f, size.height), Offset(size.width, size.height), 2.dp.toPx()) }
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                SquareIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack)
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = if (onBack != null) Type.Headline else Type.Display, color = Palette.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, style = Type.Small, color = Palette.mutedInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
        }
        below?.invoke(this)
    }
}

/** Sticky footer for a screen's main action, with a 2dp rule on top. */
@Composable
fun BottomBar(content: @Composable ColumnScope.() -> Unit) {
    val rule = Palette.border
    Column(
        Modifier
            .fillMaxWidth()
            .background(Palette.paper)
            .drawBehind { drawLine(rule, Offset.Zero, Offset(size.width, 0f), 2.dp.toPx()) }
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 12.dp),
        content = content,
    )
}

/** Little square badge holding an icon, used for device and status glyphs. */
@Composable
fun IconBadge(icon: ImageVector, fill: Color = Palette.peach, size: Dp = 48.dp, tint: Color = Palette.ink, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).background(fill, RoundedCornerShape(6.dp)).border(2.dp, Palette.border, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.5f)) }
}

/** Thin ink border helper for things that shouldn't get a shadow. */
val InkBorder: BorderStroke @Composable @ReadOnlyComposable get() = BorderStroke(2.dp, Palette.border)

/** Ring spinner drawn in ink/orange, for indeterminate states. */
@Composable
fun BrutalSpinner(modifier: Modifier = Modifier, size: Dp = 22.dp) {
    val t = rememberInfiniteTransition(label = "spin")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(800, easing = LinearEasing)), label = "angle")
    val track = Palette.sand
    val ring = Palette.border
    Box(
        modifier.size(size).drawBehind {
            val stroke = 3.dp.toPx()
            drawCircle(track, style = Stroke(stroke))
            drawArc(Brutal.Orange, angle, 100f, false, style = Stroke(stroke))
            drawCircle(ring, radius = this.size.minDimension / 2, style = Stroke(1f))
        },
    )
}
