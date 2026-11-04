package com.roylet.photos.ui.memories

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.roylet.photos.domain.library.Memory
import com.roylet.photos.ui.components.DarkIconButton
import com.roylet.photos.ui.components.Pressable
import com.roylet.photos.ui.components.reducedMotion
import com.roylet.photos.ui.gallery.AssetImage
import com.roylet.photos.ui.gallery.LocalMediaUrls
import com.roylet.photos.ui.theme.Brutal
import com.roylet.photos.ui.theme.Palette
import com.roylet.photos.ui.theme.BrutalDark
import com.roylet.photos.ui.theme.Type
import com.roylet.photos.util.Format
import java.time.LocalDate

/** "On this day" cards above the Photos timeline. */
@Composable
fun MemoriesCarousel(memories: List<Memory>, onOpen: (Int) -> Unit, modifier: Modifier = Modifier, today: LocalDate = LocalDate.now()) {
    val urls = LocalMediaUrls.current
    val px = LocalDensity.current
    Column(modifier.fillMaxWidth().background(Palette.paper).padding(top = 14.dp, bottom = 10.dp)) {
        Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.AutoAwesome, null, tint = Brutal.Orange, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("ON THIS DAY", style = Type.Label, color = Palette.ink)
        }
        Spacer(Modifier.height(10.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(memories, key = { _, m -> m.id }) { i, m ->
                val cover = m.assets.first()
                Pressable({ onOpen(i) }, fill = Brutal.Ink, shadow = 4.dp) {
                    Box(Modifier.size(118.dp, 160.dp)) {
                        AssetImage(urls.preview(cover.id), cover.thumbhash, with(px) { 118.dp.roundToPx() }, with(px) { 160.dp.roundToPx() }, Modifier.matchParentSize())
                        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Brutal.Ink.copy(alpha = 0.85f))))
                        Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
                            Text(m.title(today), style = Type.Title.copy(fontSize = Type.Body.fontSize), color = Brutal.Paper, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${m.year} · ${Format.plural(m.assets.size, "photo")}".uppercase(), style = Type.Tiny, color = Brutal.Paper.copy(alpha = 0.8f))
                        }
                    }
                }
            }
        }
    }
}

private const val PHOTO_MS = 5_000L

/**
 * Full-screen story viewer: progress bars per photo, auto-advance, tap right/left for next and
 * previous, hold to pause. Runs on through every memory, then closes.
 */
@Composable
fun MemoryStoryScreen(memories: List<Memory>, startIndex: Int, onClose: () -> Unit, modifier: Modifier = Modifier, today: LocalDate = LocalDate.now()) {
    if (memories.isEmpty()) { LaunchedEffect(Unit) { onClose() }; return }
    val urls = LocalMediaUrls.current
    val reduced = reducedMotion()
    var m by rememberSaveable { mutableIntStateOf(startIndex.coerceIn(0, memories.lastIndex)) }
    var i by rememberSaveable { mutableIntStateOf(0) }
    var paused by remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }
    val close by rememberUpdatedState(onClose)
    BackHandler(onBack = onClose)

    val memory = memories[m.coerceIn(0, memories.lastIndex)]
    val asset = memory.assets[i.coerceIn(0, memory.assets.lastIndex)]

    fun next() {
        if (i + 1 < memory.assets.size) i++ else if (m + 1 < memories.size) { m++; i = 0 } else close()
    }
    fun prev() {
        if (i > 0) i-- else if (m > 0) { m--; i = 0 }
    }

    LaunchedEffect(m, i) { progress.snapTo(0f) }
    LaunchedEffect(m, i, paused) {
        if (paused) return@LaunchedEffect
        val total = (asset.durationMs ?: PHOTO_MS).coerceIn(3_000, 15_000)
        val left = ((1f - progress.value) * total).toLong().coerceAtLeast(1)
        progress.animateTo(1f, tween(left.toInt(), easing = LinearEasing))
        next()
    }

    BoxWithConstraints(
        modifier.fillMaxSize().background(BrutalDark.Canvas).pointerInput(memories) {
            detectTapGestures(
                onPress = { paused = true; tryAwaitRelease(); paused = false },
                onTap = { pos -> if (pos.x < size.width / 3f) prev() else next() },
            )
        },
    ) {
        val px = LocalDensity.current
        val w = with(px) { maxWidth.roundToPx() }
        val h = with(px) { maxHeight.roundToPx() }
        AnimatedContent(asset.id, transitionSpec = { fadeIn(tween(if (reduced) 0 else 260)) togetherWith fadeOut(tween(if (reduced) 0 else 260)) }, label = "story") { id ->
            val a = memory.assets.firstOrNull { it.id == id } ?: asset
            Box(Modifier.fillMaxSize().graphicsLayer {
                // A slow push-in while the photo is up.
                val s = if (reduced) 1f else 1f + 0.06f * progress.value
                scaleX = s; scaleY = s
            }) {
                AssetImage(urls.preview(a.id), a.thumbhash, w, h, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, placeholderColor = BrutalDark.Raised)
            }
        }
        Box(Modifier.fillMaxWidth().height(180.dp).background(Brush.verticalGradient(listOf(BrutalDark.Canvas.copy(alpha = 0.75f), Color.Transparent))))
        Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                memory.assets.indices.forEach { k ->
                    val f = when { k < i -> 1f; k == i -> progress.value; else -> 0f }
                    Box(
                        Modifier.weight(1f).height(5.dp).clip(RoundedCornerShape(3.dp)).drawBehind {
                            drawRoundRect(BrutalDark.Text.copy(alpha = 0.3f), cornerRadius = CornerRadius(3.dp.toPx()))
                            drawRoundRect(Brutal.Orange, size = Size(size.width * f, size.height), cornerRadius = CornerRadius(3.dp.toPx()))
                        },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(memory.title(today), style = Type.Headline, color = BrutalDark.Text)
                    Text(
                        listOfNotNull(Format.day(asset.localTakenAt), asset.city).joinToString(" · "),
                        style = Type.Small, color = BrutalDark.MutedText, maxLines = 1,
                    )
                }
                if (paused) Icon(Icons.Rounded.Pause, "Paused", tint = BrutalDark.Text, modifier = Modifier.padding(end = 10.dp).size(22.dp))
                DarkIconButton(Icons.Rounded.Close, "Close", onClose)
            }
        }
        Text(
            "${m + 1} / ${memories.size}", style = Type.Tiny, color = BrutalDark.MutedText,
            modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 18.dp),
        )
    }
}
