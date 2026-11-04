package com.roylet.photos.ui.gallery

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.roylet.photos.domain.timeline.ThumbHash
import com.roylet.photos.immich.ImmichApi
import com.roylet.photos.immich.ThumbSize
import com.roylet.photos.immich.originalUrl
import com.roylet.photos.immich.thumbnailUrl
import com.roylet.photos.immich.videoPlaybackUrl
import com.roylet.photos.ui.components.reducedMotion
import okhttp3.OkHttpClient

/** Where an asset's pixels live. Screens read it from [LocalMediaUrls]. */
interface MediaUrls {
    /** Small grid thumbnail (~250px). */
    fun thumbnail(id: String): String
    /** Screen-sized JPEG for the viewer and big grid cells. */
    fun preview(id: String): String
    /** Full-resolution rendition for deep zoom. */
    fun fullsize(id: String): String
    fun original(id: String): String
    fun video(id: String): String
}

class ImmichMediaUrls(private val api: ImmichApi) : MediaUrls {
    override fun thumbnail(id: String) = api.thumbnailUrl(id, ThumbSize.THUMBNAIL)
    override fun preview(id: String) = api.thumbnailUrl(id, ThumbSize.PREVIEW)
    override fun fullsize(id: String) = api.thumbnailUrl(id, ThumbSize.FULLSIZE)
    override fun original(id: String) = api.originalUrl(id)
    override fun video(id: String) = api.videoPlaybackUrl(id)
}

/** Placeholder URLs for previews and signed-out states; screenshot tests answer them with a Coil preview handler. */
object FakeMediaUrls : MediaUrls {
    override fun thumbnail(id: String) = "fake://thumbnail/$id"
    override fun preview(id: String) = "fake://preview/$id"
    override fun fullsize(id: String) = "fake://fullsize/$id"
    override fun original(id: String) = "fake://original/$id"
    override fun video(id: String) = "fake://video/$id"
}

val LocalMediaUrls = staticCompositionLocalOf<MediaUrls> { FakeMediaUrls }

/** The shared OkHttp client (auth header + connection switching) for ExoPlayer. Null in previews. */
val LocalMediaHttpClient = staticCompositionLocalOf<OkHttpClient?> { null }

/** Decoded thumbhashes, shared by the grid, filmstrip and viewer. Each is ≤32×32 px, so 800 is ~3 MB. */
object ThumbHashCache {
    private val cache = LruCache<String, ImageBitmap>(800)

    fun get(hash: String?): ImageBitmap? {
        if (hash.isNullOrEmpty()) return null
        cache.get(hash)?.let { return it }
        val img = ThumbHash.decode(hash) ?: return null
        val bmp = Bitmap.createBitmap(img.argb, img.width, img.height, Bitmap.Config.ARGB_8888).asImageBitmap()
        cache.put(hash, bmp)
        return bmp
    }
}

/** Draws a thumbhash stretched over the whole node (bilinear, so it reads as a soft blur), else a flat tint. */
fun Modifier.thumbHashBackground(hash: ImageBitmap?, fallback: Color): Modifier = drawBehind {
    if (hash == null) drawRect(fallback)
    else drawImage(hash, IntOffset.Zero, IntSize(hash.width, hash.height), dstSize = IntSize(size.width.toInt() + 1, size.height.toInt() + 1), filterQuality = FilterQuality.Low)
}

/**
 * One asset's image, decoded at [widthPx]×[heightPx], cropped to fill. The thumbhash shows
 * immediately and the real image crossfades in over it.
 */
@Composable
fun AssetImage(
    url: String,
    thumbhash: String?,
    widthPx: Int,
    heightPx: Int,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    placeholderColor: Color = Color(0xFFE9DFCC),
    crossfadeMs: Int = 160,
) {
    val context = LocalContext.current
    val reduced = reducedMotion()
    val hash = remember(thumbhash) { ThumbHashCache.get(thumbhash) }
    val request = remember(url, widthPx, heightPx, reduced) {
        ImageRequest.Builder(context)
            .data(url)
            // A plain key, so the viewer can show this exact bitmap as its placeholder.
            .memoryCacheKey(url)
            .size(widthPx.coerceAtLeast(1), heightPx.coerceAtLeast(1))
            .crossfade(if (reduced) 0 else crossfadeMs)
            .build()
    }
    Box(modifier.thumbHashBackground(hash, placeholderColor)) {
        AsyncImage(request, contentDescription = null, contentScale = contentScale, modifier = Modifier.matchParentSize())
    }
}

/* ---- Shared element plumbing (grid cell ↔ viewer) ---- */

@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

/** The nav destination's AnimatedVisibilityScope; provided by AppNav around each route. */
val LocalNavAnimatedScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/** Marks this node as asset [id]'s shared element; a no-op outside a SharedTransitionLayout. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedAsset(id: String): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val anim = LocalNavAnimatedScope.current ?: return this
    return with(shared) { this@sharedAsset.sharedElement(rememberSharedContentState("asset:$id"), anim) }
}
