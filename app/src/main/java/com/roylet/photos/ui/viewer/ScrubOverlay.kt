package com.roylet.photos.ui.viewer

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.roylet.photos.ui.components.brutalDark
import com.roylet.photos.ui.components.motionSpec
import com.roylet.photos.ui.theme.BrutalDark
import com.roylet.photos.ui.theme.Type
import kotlin.math.roundToInt

private val ThumbWidth = 128.dp
private val BubblePad = 5.dp
private val BubbleShadow = 3.dp
private val BubbleWidth = ThumbWidth + BubblePad * 2 + BubbleShadow
private val StripHeight = 44.dp
private val Gap = 10.dp
private val EdgeMargin = 4.dp

/** Height of the scrub preview area that sits above the video controls. */
internal fun scrubPreviewHeight(): Dp = BubblePad * 2 + BubbleShadow + 120.dp + 22.dp + Gap + StripHeight + BubbleShadow + Gap

/**
 * What appears above the video controls while scrubbing: the frame strip across the full width
 * and, above it, a bubble with the nearest strip frame and the time that follows the finger
 * ([anchorX], read at layout time so it tracks without recomposing). Pops in and out with a
 * spring; instant under reduced motion. Draws nothing when idle (and takes no touches).
 */
@Composable
internal fun ScrubPreview(
    fraction: Float?,
    anchorX: () -> Float,
    durationMs: Long,
    strip: VideoFrameStrip?,
    aspect: Float,
    modifier: Modifier = Modifier,
) {
    // Keep showing the last spot while popping out.
    val last = remember { mutableFloatStateOf(0f) }
    if (fraction != null) last.floatValue = fraction
    val shown = fraction ?: last.floatValue
    val appear by animateFloatAsState(
        if (fraction != null) 1f else 0f,
        motionSpec(spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow)),
        label = "scrubAppear",
    )
    Box(modifier.fillMaxWidth().height(scrubPreviewHeight())) {
        if (appear <= 0.001f && fraction == null) return@Box
        val positionMs = scrubPositionMs(shown, durationMs)
        val frame = strip?.nearest(positionMs, durationMs)
        val ratio = frame?.let { it.width.toFloat() / it.height.coerceAtLeast(1) } ?: aspect
        val thumbHeight = (ThumbWidth / ratio.coerceAtLeast(0.1f)).coerceIn(64.dp, 120.dp)

        // The strip.
        Box(
            Modifier.align(Alignment.BottomStart).padding(bottom = Gap).fillMaxWidth()
                .graphicsLayer {
                    alpha = appear.coerceIn(0f, 1f)
                    translationY = (1f - appear) * 14.dp.toPx()
                }
                .brutalDark(shadow = BubbleShadow)
                .height(StripHeight),
        ) {
            FrameStrip(strip, shown)
        }

        // The bubble.
        val density = LocalDensity.current
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val containerPx = constraints.maxWidth.toFloat()
            val widthPx = with(density) { BubbleWidth.toPx() }
            val marginPx = with(density) { EdgeMargin.toPx() }
            Column(
                Modifier.align(Alignment.BottomStart)
                    .padding(bottom = Gap + StripHeight + BubbleShadow + Gap)
                    .offset {
                        val left = bubbleLeft(anchorX(), widthPx, containerPx, marginPx)
                        IntOffset(left.roundToInt(), 0)
                    }
                    .graphicsLayer {
                        val a = appear
                        alpha = a.coerceIn(0f, 1f)
                        val s = 0.55f + 0.45f * a
                        scaleX = s
                        scaleY = s
                        val left = bubbleLeft(anchorX(), widthPx, containerPx, marginPx)
                        transformOrigin = TransformOrigin(((anchorX() - left) / widthPx).coerceIn(0f, 1f), 1f)
                    }
                    .width(BubbleWidth)
                    .brutalDark(shadow = BubbleShadow)
                    .padding(BubblePad),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val shape = RoundedCornerShape(3.dp)
                Box(
                    Modifier.width(ThumbWidth).height(thumbHeight).clip(shape).background(BrutalDark.Raised, shape),
                ) {
                    if (frame != null) Image(frame, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Box(Modifier.fillMaxSize().border(2.dp, BrutalDark.Line, shape))
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    scrubLabel(positionMs, durationMs), style = Type.Label, color = BrutalDark.Text,
                    textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** The frames side by side, the part after the playhead dimmed, with an orange playhead. */
@Composable
private fun FrameStrip(strip: VideoFrameStrip?, fraction: Float) {
    val count = strip?.count ?: 0
    Row(
        Modifier.fillMaxSize().padding(2.dp)
            .drawWithContent {
                drawContent()
                val x = size.width * fraction.coerceIn(0f, 1f)
                drawRect(Color.Black.copy(alpha = 0.5f), topLeft = Offset(x, 0f), size = Size(size.width - x, size.height))
                val w = 4.dp.toPx()
                val left = (x - w / 2).coerceIn(0f, size.width - w)
                drawRect(BrutalDark.Shadow, topLeft = Offset(left - 1.5.dp.toPx(), 0f), size = Size(w + 3.dp.toPx(), size.height))
                drawRect(BrutalDark.Orange, topLeft = Offset(left, 0f), size = Size(w, size.height))
            },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (count == 0) {
            Box(Modifier.fillMaxSize().background(BrutalDark.Raised))
        } else {
            for (i in 0 until count) {
                val f = strip!!.frames[i]
                Box(Modifier.weight(1f).fillMaxHeight().background(BrutalDark.Raised)) {
                    if (f != null) Image(f, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
            }
        }
    }
}
