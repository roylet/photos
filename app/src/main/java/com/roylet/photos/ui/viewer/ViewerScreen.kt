package com.roylet.photos.ui.viewer

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MotionPhotosOn
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil3.compose.AsyncImage
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.unit.IntSize
import androidx.compose.material.icons.rounded.Refresh
import coil3.SingletonImageLoader
import coil3.compose.AsyncImagePainter
import coil3.size.Precision
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.roylet.photos.domain.timeline.TimelineAsset
import com.roylet.photos.domain.timeline.TimelineRepository
import com.roylet.photos.domain.library.AssetDetails
import com.roylet.photos.domain.source.Capabilities
import com.roylet.photos.domain.source.Capability
import com.roylet.photos.ui.sources.assetCapabilities
import com.roylet.photos.ui.components.BrutalSpinner
import com.roylet.photos.ui.components.DarkIconButton
import com.roylet.photos.ui.components.brutalDark
import com.roylet.photos.ui.components.reducedMotion
import com.roylet.photos.ui.gallery.AssetActions
import com.roylet.photos.ui.gallery.AssetImage
import com.roylet.photos.domain.source.AssetRef
import com.roylet.photos.ui.gallery.LocalAssetActions
import com.roylet.photos.ui.gallery.LocalMediaUrls
import com.roylet.photos.ui.gallery.MediaUrls
import com.roylet.photos.ui.gallery.ThumbHashCache
import com.roylet.photos.ui.gallery.sharedAsset
import com.roylet.photos.ui.gallery.thumbHashBackground
import com.roylet.photos.ui.theme.BrutalDark
import com.roylet.photos.ui.theme.Type
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.ZoomableContentLocation
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable
import kotlin.math.abs

/**
 * Full-screen viewer over a timeline: swipe between every asset of [timeline] (months load on
 * demand), pinch / double-tap zoom, drag down to dismiss, tap to toggle the chrome.
 *
 * @param startIndex global timeline index to open at; [startId] corrects it if the timeline shifted.
 * @param onIndexChange reports the current index (so the grid can scroll to it for the return transition).
 * @param loadDetails fetches the info sheet's [AssetDetails] (the active LibrarySource's `details`).
 */
@Composable
fun ViewerScreen(
    timeline: TimelineRepository,
    startIndex: Int,
    onBack: () -> Unit,
    onEdit: (assetId: String) -> Unit,
    modifier: Modifier = Modifier,
    /** Edit for videos (the video editor); defaults to [onEdit]. */
    onEditVideo: (assetId: String) -> Unit = onEdit,
    startId: String? = null,
    onIndexChange: (Int) -> Unit = {},
    loadDetails: suspend (String) -> AssetDetails? = { null },
    actions: AssetActions = LocalAssetActions.current,
    initialChrome: Boolean = true,
    initialInfo: Boolean = false,
) {
    val snapshot by timeline.snapshot.collectAsState()
    LaunchedEffect(timeline) { timeline.start() }
    val urls = LocalMediaUrls.current
    val reduced = reducedMotion()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    val inspection = LocalInspectionMode.current
    var loadedPage by remember { mutableIntStateOf(-1) }

    val pager = rememberPagerState(initialPage = startIndex.coerceAtLeast(0)) { snapshot.total }
    // If months were added/removed since the grid computed startIndex, find the asset by id.
    LaunchedEffect(snapshot.total > 0) {
        if (startId == null || snapshot.total == 0) return@LaunchedEffect
        val at = snapshot.assetAt(pager.currentPage)
        if (at == null) timeline.ensureLoadedAt(startIndex)
        if (at != null && at.id != startId) snapshot.indexOf(startId).takeIf { it >= 0 }?.let { pager.scrollToPage(it) }
    }
    val indexChanged by rememberUpdatedState(onIndexChange)
    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage }.distinctUntilChanged().collect { page ->
            timeline.ensureLoadedAt(page)
            indexChanged(page)
        }
    }
    val current = snapshot.assetAt(pager.currentPage)
    // Everything here was deleted or archived away: nothing left to show.
    val status by timeline.status.collectAsState()
    LaunchedEffect(snapshot.total == 0, status) { if (snapshot.total == 0 && status == TimelineRepository.Status.Ready) onBack() }

    var chrome by rememberSaveable { mutableStateOf(initialChrome) }
    var info by rememberSaveable { mutableStateOf(initialInfo) }
    val zoomed = remember { mutableStateMapOf<Int, Boolean>() }
    var liveFor by remember { mutableStateOf<String?>(null) }
    // Shared by the video pages; only the settled page shows controls.
    val scrub = remember { ScrubController() }

    BackHandler(enabled = info) { info = false }
    SystemBars(visible = chrome || info)

    BoxWithConstraints(modifier.fillMaxSize()) {
        val heightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val dismiss = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
        val progress = (dismiss.value.y / heightPx).coerceIn(0f, 1f)
        val dismissThreshold = with(LocalDensity.current) { 1100.dp.toPx() }

        // Preload: the pager composes one page either side; the prefetcher also warms the memory
        // and disk caches with the previews of ±2 pages at their exact on-screen size, once the
        // current page has its image.
        val pageSize = IntSize(constraints.maxWidth, constraints.maxHeight)
        val prefetcher = remember(timeline, urls, pageSize) {
            NeighbourPrefetcher(object : PrefetchTarget {
                override fun ensureLoadedAt(index: Int) = timeline.ensureLoadedAt(index)
                override fun enqueue(index: Int): PrefetchHandle? {
                    if (inspection) return null
                    // Videos too: only their poster (the preview frame), never the media.
                    val a = timeline.snapshot.value.assetAt(index) ?: return null
                    val request = previewRequest(context, urls, a.id, fitPx(a.ratio, pageSize.width, pageSize.height)).build()
                    val job = SingletonImageLoader.get(context).enqueue(request)
                    return PrefetchHandle { job.dispose() }
                }
            })
        }
        DisposableEffect(prefetcher) { onDispose { prefetcher.clear() } }
        LaunchedEffect(prefetcher, pager) {
            snapshotFlow { pager.settledPage }.collectLatest { page ->
                prefetcher.moveTo(page, timeline.snapshot.value.total)
                // Neighbours wait for the current page (bounded, so a stuck load can't block them).
                withTimeoutOrNull(1500) { snapshotFlow { loadedPage }.first { it == page } }
                // Buckets arriving later make more neighbours known: prefetch them as they come.
                timeline.snapshot.collect { prefetcher.prefetch(page, it.total) }
            }
        }

        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { alpha = (1f - progress * 1.8f).coerceIn(0f, 1f) }
                .background(BrutalDark.Canvas),
        )

        Box(
            Modifier.fillMaxSize()
                .dragToDismiss(
                    enabled = { zoomed[pager.currentPage] != true && !info && !scrub.scrubbing },
                    onDrag = { d -> scope.launch { dismiss.snapTo(dismiss.value + d) } },
                    onRelease = { v ->
                        val y = dismiss.value.y
                        if (y > heightPx * 0.16f || v.y > dismissThreshold) {
                            onBack()
                        } else scope.launch {
                            dismiss.animateTo(Offset.Zero, if (reduced) snap() else spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow))
                        }
                    },
                )
                .graphicsLayer {
                    val s = 1f - progress * 0.45f
                    translationX = dismiss.value.x * 0.6f
                    translationY = dismiss.value.y
                    scaleX = s
                    scaleY = s
                },
        ) {
            HorizontalPager(
                state = pager,
                beyondViewportPageCount = 1,
                pageSpacing = 12.dp,
                key = { snapshot.assetAt(it)?.id ?: "i$it" },
                modifier = Modifier.fillMaxSize().testTag("viewer-pager"),
            ) { page ->
                val asset = snapshot.assetAt(page)
                if (asset == null) {
                    // A failed bucket fetch isn't retried by the repository; keep asking (with backoff) until it lands.
                    LaunchedEffect(page) {
                        var attempt = 0
                        while (true) {
                            timeline.ensureLoadedAt(page)
                            delay(retryDelayMs(attempt.coerceAtMost(MAX_AUTO_RETRIES - 1))!!)
                            attempt++
                        }
                    }
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { BrutalSpinner(size = 32.dp) }
                } else {
                    val isCurrent = page == pager.settledPage
                    if (asset.isVideo) {
                        VideoPage(asset, urls, isCurrent, chrome, scrub, onTap = { chrome = !chrome })
                        if (isCurrent) LaunchedEffect(page) { loadedPage = page }
                    } else {
                        PhotoPage(
                            asset, urls, isCurrent,
                            onTap = { chrome = !chrome },
                            onLongPress = {
                                if (asset.isLivePhoto) { haptics.performHapticFeedback(HapticFeedbackType.LongPress); liveFor = asset.id }
                            },
                            onZoomed = { zoomed[page] = it },
                            playingLive = liveFor == asset.id,
                            onLiveEnded = { if (liveFor == asset.id) liveFor = null },
                            onPinchClose = onBack,
                            onLoaded = { if (page == pager.settledPage) loadedPage = page },
                        )
                    }
                }
            }
        }

        val chromeAlpha = (1f - progress * 4f).coerceIn(0f, 1f)
        val enter = if (reduced) fadeIn(snap()) else fadeIn(tween(160)) + slideInVertically(tween(200)) { -it / 2 }
        val exit = if (reduced) fadeOut(snap()) else fadeOut(tween(140)) + slideOutVertically(tween(180)) { -it / 2 }
        // Only what this asset's sources can do (e.g. no archive for a photo that's only on the phone).
        val caps = assetCapabilities(current)
        AnimatedVisibility(chrome && !info, Modifier.align(Alignment.TopCenter).graphicsLayer { alpha = chromeAlpha }, enter = enter, exit = exit) {
            TopBar(
                current, onBack, liveActive = liveFor != null && liveFor == current?.id, onLive = { current?.let { liveFor = it.id } },
                archived = timeline.filter.visibility == com.roylet.photos.domain.timeline.Visibility.ARCHIVE,
                onArchive = if (Capability.ARCHIVE in caps) { { current?.let { actions.archive(listOf(it.id), timeline) } } } else null,
            )
        }
        val enterB = if (reduced) fadeIn(snap()) else fadeIn(tween(160)) + slideInVertically(tween(200)) { it / 2 }
        val exitB = if (reduced) fadeOut(snap()) else fadeOut(tween(140)) + slideOutVertically(tween(180)) { it / 2 }
        AnimatedVisibility(chrome && !info, Modifier.align(Alignment.BottomCenter).graphicsLayer { alpha = chromeAlpha }, enter = enterB, exit = exitB) {
            Column(Modifier.fillMaxWidth()) {
                Filmstrip(pager, timeline, urls)
                ActionBar(
                    asset = current,
                    caps = caps,
                    onFavorite = {
                        val a = current ?: return@ActionBar
                        val fav = !a.isFavorite
                        if (fav) haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                        actions.favorite(listOf(a.id), fav, timeline)
                    },
                    onShare = { current?.let { actions.share(listOf(it.id), timeline) } },
                    onAddToAlbum = { current?.let { actions.addToAlbum(listOf(it.id), timeline) } },
                    onEdit = { current?.let { if (it.isVideo) onEditVideo(it.id) else onEdit(it.id) } },
                    onInfo = { info = true },
                    onDelete = { current?.let { actions.delete(listOf(it.id), timeline) } },
                )
            }
        }

        // Info sheet: scrim + panel sliding up with a little overshoot.
        AnimatedVisibility(info, enter = fadeIn(tween(if (reduced) 0 else 160)), exit = fadeOut(tween(if (reduced) 0 else 160))) {
            Box(Modifier.fillMaxSize().background(BrutalDark.Canvas.copy(alpha = 0.55f)).clickable(remember { MutableInteractionSource() }, null) { info = false })
        }
        AnimatedVisibility(
            info,
            Modifier.align(Alignment.BottomCenter),
            enter = if (reduced) fadeIn(snap()) else slideInVertically(spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow)) { it },
            exit = if (reduced) fadeOut(snap()) else slideOutVertically(tween(200)) { it },
        ) {
            val a = current
            if (a != null) {
                var details by remember(a.id) { mutableStateOf<AssetDetails?>(null) }
                var loading by remember(a.id) { mutableStateOf(true) }
                LaunchedEffect(a.id) { details = runCatching { loadDetails(a.id) }.getOrNull(); loading = false }
                AssetInfoPanel(a, details, loading, onClose = { info = false })
            }
        }

    }
}

/** Ink status/nav bars with light icons while the viewer is up; hidden (swipe to peek) when the chrome is. */
@Composable
private fun SystemBars(visible: Boolean) {
    val view = LocalView.current
    if (LocalInspectionMode.current) return
    val window = (view.context as? Activity)?.window ?: return
    DisposableEffect(window) {
        val c = WindowCompat.getInsetsController(window, view)
        val light = c.isAppearanceLightStatusBars
        c.isAppearanceLightStatusBars = false
        c.isAppearanceLightNavigationBars = false
        onDispose {
            c.isAppearanceLightStatusBars = light
            c.isAppearanceLightNavigationBars = light
            c.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    LaunchedEffect(visible) {
        val c = WindowCompat.getInsetsController(window, view)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (visible) c.show(WindowInsetsCompat.Type.systemBars()) else c.hide(WindowInsetsCompat.Type.systemBars())
    }
}

/** How far a photo page is zoomed in (0 = fitted, 1 = max); exposed for gesture tests. */
internal val ZoomFractionKey = SemanticsPropertyKey<Float>("ZoomFraction")
internal var SemanticsPropertyReceiver.viewerZoom by ZoomFractionKey

@Composable
private fun PhotoPage(
    asset: TimelineAsset,
    urls: MediaUrls,
    isCurrent: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onZoomed: (Boolean) -> Unit,
    playingLive: Boolean,
    onLiveEnded: () -> Unit,
    onPinchClose: () -> Unit = {},
    onLoaded: () -> Unit = {},
) {
    // The zoom is applied by hand (not by `zoomable`) so it never moves the shared-element node: with
    // the grid cell underneath registered as the same shared element, a transformed node is drawn
    // through the shared-transition overlay at its untransformed bounds and the zoom never shows.
    val zoom = rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = 8f), autoApplyTransformations = false)
    val pinchClose by rememberUpdatedState(onPinchClose)
    val zoomFraction = zoom.zoomFraction ?: 0f
    val zoomedIn = zoomFraction > 0.02f
    LaunchedEffect(zoomedIn) { onZoomed(zoomedIn) }
    LaunchedEffect(isCurrent) { if (!isCurrent) zoom.resetZoom(snap()) }
    // Once zoomed in far enough, swap in the full-resolution rendition (and keep it).
    var deep by remember(asset.id) { mutableStateOf(false) }
    val farIn = zoomFraction > 0.3f
    LaunchedEffect(farIn) { if (farIn) deep = true }
    val context = LocalContext.current
    val reduced = reducedMotion()

    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val px = LocalDensity.current
        val fitted = fitPx(asset.ratio, constraints.maxWidth, constraints.maxHeight)
        val w = with(px) { fitted.width.toDp() }
        val h = with(px) { fitted.height.toDp() }
        val screenW = maxWidth
        val screenH = maxHeight
        val loaded by rememberUpdatedState(onLoaded)
        // Preview load state: failures retry on their own with backoff, then on tap.
        var attempt by remember(asset.id) { mutableIntStateOf(0) }
        var autoRetries by remember(asset.id) { mutableIntStateOf(0) }
        var failed by remember(asset.id) { mutableStateOf(false) }
        // A merged item opens its phone file (instant, full resolution); if that's gone or
        // unreadable, the Immich copy takes over.
        var useRemote by remember(asset.id) { mutableStateOf(false) }
        val remoteId = asset.remoteRef?.takeIf { AssetRef.isLocal(asset.id) }?.key
        val shownId = if (useRemote && remoteId != null) remoteId else asset.id
        LaunchedEffect(failed, autoRetries) {
            if (!failed) return@LaunchedEffect
            val wait = retryDelayMs(autoRetries) ?: return@LaunchedEffect
            delay(wait)
            autoRetries++
            failed = false
            attempt++
        }
        LaunchedEffect(w, h) { zoom.setContentLocation(ZoomableContentLocation.scaledInsideAndCenterAligned(with(px) { Size(w.toPx(), h.toPx()) })) }
        Box(
            Modifier.fillMaxSize()
                // Pinching in on an unzoomed photo goes back to the grid. Observed, never consumed,
                // so telephoto still owns every pinch; see [PinchToClose].
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        val pinch = PinchToClose(startedUnzoomed = (zoom.zoomFraction ?: 0f) < 0.02f)
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.count { it.pressed } >= 2) pinch.onZoom(event.calculateZoom())
                        } while (event.changes.any { it.pressed })
                        if (pinch.shouldClose(zoomFractionNow = zoom.zoomFraction ?: 0f)) pinchClose()
                    }
                }
                .zoomable(zoom, onClick = { onTap() }, onLongClick = { onLongPress() })
                .testTag("viewer-page-${asset.id}")
                .semantics { viewerZoom = zoom.zoomFraction ?: 0f },
            contentAlignment = Alignment.Center,
        ) {
            val hash = remember(asset.thumbhash) { ThumbHashCache.get(asset.thumbhash) }
            // Untransformed: the morph's start/end bounds.
            Box(Modifier.size(w, h).sharedAsset(asset.id, inViewer = true)) {
                // Screen-sized and centred, so telephoto's transformation (computed in the zoomable
                // node's coordinates) applies unchanged.
                Box(
                    Modifier.requiredSize(screenW, screenH).graphicsLayer {
                        val t = zoom.contentTransformation
                        if (t.isSpecified) {
                            scaleX = t.scale.scaleX
                            scaleY = t.scale.scaleY
                            rotationZ = t.rotationZ
                            translationX = t.offset.x
                            translationY = t.offset.y
                            transformOrigin = t.transformOrigin
                        }
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(w, h).testTag("viewer-photo-${asset.id}").thumbHashBackground(hash, BrutalDark.Raised)) {
                        // The floor the page never drops below: the grid's thumbnail, straight from the
                        // memory cache when the grid showed it (same plain-URL key), else from disk/network.
                        val thumb = remember(shownId) {
                            val url = urls.thumbnail(shownId)
                            ImageRequest.Builder(context).data(url).memoryCacheKey(url).placeholderMemoryCacheKey(url)
                                .size(THUMB_PX, THUMB_PX).precision(Precision.INEXACT).build()
                        }
                        AsyncImage(thumb, contentDescription = null, modifier = Modifier.fillMaxSize())
                        val preview = remember(shownId, fitted, reduced) {
                            previewRequest(context, urls, shownId, fitted).crossfade(if (reduced) 0 else 200).build()
                        }
                        // A new painter per attempt, so a retry really goes back to the network.
                        key(attempt) {
                            AsyncImage(
                                preview, contentDescription = null, modifier = Modifier.fillMaxSize(),
                                onState = { st ->
                                    when (st) {
                                        is AsyncImagePainter.State.Success -> { failed = false; loaded() }
                                        is AsyncImagePainter.State.Error ->
                                            if (remoteId != null && !useRemote) useRemote = true else { failed = true; loaded() }
                                        else -> {}
                                    }
                                },
                            )
                        }
                        if (deep) {
                            val full = remember(shownId) {
                                ImageRequest.Builder(context).data(urls.fullsize(shownId))
                                    .placeholderMemoryCacheKey(urls.preview(shownId))
                                    .size(4096, 4096)
                                    .crossfade(if (reduced) 0 else 200)
                                    .build()
                            }
                            AsyncImage(full, contentDescription = null, modifier = Modifier.fillMaxSize())
                        }
                        if (playingLive && asset.livePhotoVideoId != null) {
                            LivePhotoMotion(urls.video(asset.livePhotoVideoId), onLiveEnded)
                        }
                    }
                }
            }
            if (failed) {
                LoadError(
                    asset.id,
                    retrying = retryDelayMs(autoRetries) != null,
                    onRetry = { autoRetries = 0; failed = false; attempt++ },
                )
            }
        }
    }
}

/** Decode size for the thumbnail floor under a photo; about what the grid loads. */
private const val THUMB_PX = 320

/** Shown over the thumbnail when the preview couldn't load. Tapping retries now. */
@Composable
private fun LoadError(id: String, retrying: Boolean, onRetry: () -> Unit) {
    Row(
        Modifier.testTag("viewer-load-error-$id")
            .brutalDark(BrutalDark.Panel, shape = RoundedCornerShape(50), shadow = 3.dp)
            .clickable(onClick = onRetry)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Refresh, null, tint = BrutalDark.Text, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(if (retrying) "Couldn't load · retrying" else "Couldn't load · tap to retry", style = Type.Label, color = BrutalDark.Text)
    }
}

/** Plays a live photo's motion once over the still, then fades back. */
@Composable
private fun LivePhotoMotion(url: String, onEnded: () -> Unit) {
    val player = rememberPlayer(url, autoPlay = true) ?: return
    val ended by rememberUpdatedState(onEnded)
    DisposableEffect(player) {
        val l = object : androidx.media3.common.Player.Listener {
            override fun onPlaybackStateChanged(state: Int) { if (state == androidx.media3.common.Player.STATE_ENDED) ended() }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }
    VideoSurface(player, Modifier.fillMaxSize())
}

@Composable
private fun VideoPage(asset: TimelineAsset, urls: MediaUrls, isCurrent: Boolean, chrome: Boolean, scrub: ScrubController, onTap: () -> Unit) {
    BoxWithConstraints(
        Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null, onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        val px = LocalDensity.current
        val fitted = fitPx(asset.ratio, constraints.maxWidth, constraints.maxHeight)
        val w = with(px) { fitted.width.toDp() }
        val h = with(px) { fitted.height.toDp() }
        val url = urls.video(asset.id)
        val player = if (isCurrent) rememberPlayer(url) else null
        val playback = rememberPlaybackState(player, asset.durationMs ?: 0)
        // Frames for the scrub strip: only for the settled page, and only once it plays or the bar is touched.
        var wantFrames by remember(asset.id) { mutableStateOf(false) }
        LaunchedEffect(playback.playing) { if (playback.playing) wantFrames = true }
        val frames = rememberVideoFrames(asset.id, url, playback.duration, active = isCurrent && wantFrames)
        Box(Modifier.size(w, h).sharedAsset(asset.id, inViewer = true)) {
            AssetImage(urls.preview(asset.id), asset.thumbhash, fitted.width, fitted.height, Modifier.fillMaxSize())
            if (player != null) VideoSurface(player, Modifier.fillMaxSize())
        }
        if (player == null && !scrub.scrubbing) {
            Box(Modifier.brutalDark(BrutalDark.Orange, shape = RoundedCornerShape(50), shadow = 4.dp).size(64.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.PlayArrow, "Play", tint = BrutalDark.Shadow, modifier = Modifier.size(34.dp))
            }
        }
        AnimatedVisibility(chrome && isCurrent, Modifier.align(Alignment.BottomCenter), enter = fadeIn(), exit = fadeOut()) {
            VideoControls(
                player, playback,
                Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(start = 12.dp, end = 12.dp, bottom = 150.dp).fillMaxWidth(),
                frames = frames,
                aspect = asset.ratio,
                scrub = scrub,
                onBarTouched = { wantFrames = true },
            )
        }
    }
}

@Composable
private fun TopBar(asset: TimelineAsset?, onBack: () -> Unit, liveActive: Boolean, onLive: () -> Unit, archived: Boolean, onArchive: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().background(BrutalDark.Canvas)
            .drawBehind { drawLine(BrutalDark.Raised, Offset(0f, size.height), Offset(size.width, size.height), 2.dp.toPx()) }
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DarkIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack)
        Spacer(Modifier.width(12.dp))
        if (asset != null) {
            Column(Modifier.weight(1f)) {
                Text(takenDate(asset, "d MMMM yyyy"), style = Type.Title, color = BrutalDark.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = listOfNotNull(takenDate(asset, "EEE HH:mm"), asset.city ?: asset.country).joinToString(" · ")
                Text(sub, style = Type.Small, color = BrutalDark.MutedText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (asset.isLivePhoto) {
                Row(
                    Modifier.brutalDark(if (liveActive) BrutalDark.Orange else BrutalDark.Panel, shape = RoundedCornerShape(50), shadow = 3.dp)
                        .clickable(onClick = onLive).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val ink = if (liveActive) BrutalDark.Shadow else BrutalDark.Text
                    Icon(Icons.Rounded.MotionPhotosOn, null, tint = ink, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("LIVE", style = Type.Label, color = ink)
                }
                Spacer(Modifier.width(8.dp))
            }
            if (onArchive != null) {
                DarkIconButton(
                    if (archived) Icons.Rounded.Unarchive else Icons.Rounded.Archive,
                    if (archived) "Unarchive" else "Archive",
                    onArchive,
                )
            }
        } else Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun ActionBar(
    asset: TimelineAsset?,
    caps: Capabilities,
    onFavorite: () -> Unit,
    onShare: () -> Unit,
    onAddToAlbum: () -> Unit,
    onEdit: () -> Unit,
    onInfo: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(BrutalDark.Canvas)
            .drawBehind { drawLine(BrutalDark.Raised, Offset.Zero, Offset(size.width, 0f), 2.dp.toPx()) }
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (Capability.FAVORITE in caps) FavoriteButton(asset?.isFavorite == true, onFavorite, enabled = asset != null)
        DarkIconButton(Icons.Rounded.Share, "Share", onShare, enabled = asset != null)
        if (Capability.SERVER_ALBUMS in caps) DarkIconButton(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to album", onAddToAlbum, enabled = asset != null)
        if (Capability.EDIT_SAVE in caps) DarkIconButton(Icons.Rounded.Edit, "Edit", onEdit, active = true, enabled = asset != null)
        DarkIconButton(Icons.Rounded.Info, "Info", onInfo, enabled = asset != null)
        if (Capability.TRASH in caps) DarkIconButton(Icons.Rounded.DeleteOutline, "Delete", onDelete, enabled = asset != null)
    }
}

/** Heart that pops (overshoot scale + a burst ring) when turned on. */
@Composable
private fun FavoriteButton(favorite: Boolean, onClick: () -> Unit, enabled: Boolean) {
    val reduced = reducedMotion()
    val pop = remember { Animatable(1f) }
    val ring = remember { Animatable(1f) }
    var last by remember { mutableStateOf(favorite) }
    LaunchedEffect(favorite) {
        if (favorite != last && favorite && !reduced) {
            launch { pop.snapTo(0.55f); pop.animateTo(1f, spring(dampingRatio = 0.32f, stiffness = 700f)) }
            launch { ring.snapTo(0f); ring.animateTo(1f, tween(420)) }
        }
        last = favorite
    }
    Box(contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(44.dp).drawBehind {
                val t = ring.value
                if (t < 1f) drawCircle(BrutalDark.Orange.copy(alpha = 1f - t), radius = size.minDimension * (0.4f + 0.6f * t), style = Stroke(3.dp.toPx() * (1f - t) + 1f))
            },
        )
        Box(Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value }) {
            DarkIconButton(
                if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                if (favorite) "Unfavourite" else "Favourite",
                onClick, active = favorite, enabled = enabled,
            )
        }
    }
}

/** Thumbnail strip under the photo, kept centred on the pager's page; tap to jump. */
@Composable
private fun Filmstrip(pager: PagerState, timeline: TimelineRepository, urls: MediaUrls) {
    val snapshot by timeline.snapshot.collectAsState()
    val list = rememberLazyListState(initialFirstVisibleItemIndex = pager.currentPage)
    val scope = rememberCoroutineScope()
    val px = LocalDensity.current
    val cell = 40.dp
    val reduced = reducedMotion()
    BoxWithConstraints(Modifier.fillMaxWidth().background(BrutalDark.Canvas).padding(vertical = 8.dp)) {
        val side = (maxWidth - cell) / 2
        val target by remember { derivedStateOf { pager.currentPage } }
        LaunchedEffect(target) {
            if (reduced || abs(list.firstVisibleItemIndex - target) > 40) list.scrollToItem(target) else list.animateScrollToItem(target)
        }
        LazyRow(
            state = list,
            contentPadding = PaddingValues(horizontal = side),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            items(count = snapshot.total, key = { snapshot.assetAt(it)?.id ?: "f$it" }) { i ->
                val a = snapshot.assetAt(i)
                val selected = i == pager.currentPage
                val wantW = if (selected && a != null) (52.dp * a.ratio.coerceIn(0.6f, 1.6f)) else cell
                val width by animateFloatAsState(wantW.value, if (reduced) snap() else spring(stiffness = Spring.StiffnessMediumLow), label = "film")
                val shape = RoundedCornerShape(4.dp)
                Box(
                    Modifier.width(width.dp).height(if (selected) 52.dp else 44.dp)
                        .border(2.dp, if (selected) BrutalDark.Orange else BrutalDark.Raised, shape)
                        .padding(2.dp)
                        .clickable { scope.launch { if (reduced) pager.scrollToPage(i) else pager.animateScrollToPage(i) } },
                ) {
                    if (a == null) {
                        LaunchedEffect(i) { timeline.ensureLoadedAt(i) }
                        Box(Modifier.fillMaxSize().background(BrutalDark.Raised))
                    } else {
                        AssetImage(urls.thumbnail(a.id), a.thumbhash, with(px) { 52.dp.roundToPx() }, with(px) { 52.dp.roundToPx() }, Modifier.fillMaxSize(), placeholderColor = BrutalDark.Raised)
                    }
                }
            }
        }
    }
}

