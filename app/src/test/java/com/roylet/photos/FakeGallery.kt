package com.roylet.photos

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import com.roylet.photos.domain.timeline.TimeBucket
import com.roylet.photos.domain.timeline.TimelineAsset
import com.roylet.photos.domain.timeline.TimelineFilter
import com.roylet.photos.domain.timeline.TimelineRepository
import com.roylet.photos.domain.timeline.TimelineSource
import com.roylet.photos.immich.AssetDto
import com.roylet.photos.immich.ExifDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Deterministic fake library for screenshots: 14 months of photos with realistic aspect ratios,
 * some videos, favourites and live photos, and a Coil preview handler that paints each asset as a
 * little procedural landscape so the grid reads like a real photo library.
 */
object FakeGallery {
    private val ratios = floatArrayOf(4f / 3f, 3f / 4f, 3f / 2f, 2f / 3f, 1f, 16f / 9f, 9f / 16f, 4f / 3f, 3f / 4f, 2.4f)
    private val cities = listOf("Melbourne" to "Australia", "Lisbon" to "Portugal", "Kyoto" to "Japan", null, "Hobart" to "Australia", null)
    private val counts = intArrayOf(23, 41, 17, 58, 34, 12, 46, 29, 62, 21, 38, 15, 50, 27)

    val buckets: List<TimeBucket> = counts.mapIndexed { i, n ->
        val month = 11 - i // Nov 2026 backwards
        val y = 2026 + Math.floorDiv(month - 1, 12)
        val m = Math.floorMod(month - 1, 12) + 1
        TimeBucket("%04d-%02d-01T00:00:00.000Z".format(y, m), n)
    }

    private val assets = ConcurrentHashMap<String, List<TimelineAsset>>()

    fun assetsOf(key: String): List<TimelineAsset> = assets.getOrPut(key) {
        val b = buckets.first { it.key == key }
        val rnd = Random(key.hashCode())
        val monthStart = java.time.LocalDate.of(b.year, b.month, 1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        List(b.count) { i ->
            val id = "${key.take(7)}-$i"
            val video = rnd.nextInt(9) == 0
            val place = cities[rnd.nextInt(cities.size)]
            TimelineAsset(
                id = id,
                ratio = ratios[rnd.nextInt(ratios.size)],
                isImage = !video,
                isFavorite = rnd.nextInt(7) == 0,
                durationMs = if (video) rnd.nextLong(4_000, 190_000) else null,
                fileCreatedAt = monthStart + (27 - i * 27 / b.count) * 86_400_000L + rnd.nextLong(30_000_000, 70_000_000),
                localOffsetHours = 10.0,
                livePhotoVideoId = if (!video && rnd.nextInt(6) == 0) "live-$id" else null,
                city = place?.first,
                country = place?.second,
            )
        }
    }

    val source = object : TimelineSource {
        override suspend fun buckets(filter: TimelineFilter) = buckets
        override suspend fun bucket(filter: TimelineFilter, key: String) = assetsOf(key)
    }

    /** A repository that fetches synchronously, optionally with every month already loaded. */
    fun repository(loadAll: Boolean = false): TimelineRepository =
        TimelineRepository(source, TimelineFilter.Timeline, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)).apply {
            start()
            if (loadAll) buckets.indices.forEach { ensureLoaded(it) }
        }

    fun install(container: AppContainer) {
        container.timelines.source = source
        container.timelines.context = Dispatchers.Unconfined
        container.timelines.clear()
    }

    fun details(asset: TimelineAsset) = AssetDto(
        id = asset.id,
        originalFileName = "IMG_${(asset.id.hashCode() and 0xFFFF).toString().padStart(4, '0')}.HEIC",
        width = 5712, height = 4284,
        exifInfo = ExifDto(
            make = "Apple", model = "iPhone 16 Pro", lensModel = "iPhone 16 Pro back triple camera 6.765mm f/1.78",
            fNumber = 1.78, focalLength = 24.0, iso = 80, exposureTime = "1/1200",
            exifImageWidth = 5712, exifImageHeight = 4284, fileSizeInByte = 3_482_112,
            timeZone = "Australia/Melbourne", city = asset.city ?: "Melbourne", state = "Victoria", country = asset.country ?: "Australia",
            latitude = -37.8136, longitude = 144.9631,
        ),
    )

    private val idPattern = Regex("""(?:/assets/|fake://\w+/)([^/?]+)""")
    private val byId: Map<String, TimelineAsset> by lazy { buckets.flatMap { assetsOf(it.key) }.associateBy { it.id } }
    private val bitmaps = ConcurrentHashMap<String, Bitmap>()

    val previewHandler = AsyncImagePreviewHandler { request ->
        val data = request.data.toString()
        if (data.startsWith("fake://person/")) return@AsyncImagePreviewHandler bitmaps.getOrPut(data) { face(data) }.asImage()
        val id = idPattern.find(data)?.groupValues?.get(1) ?: "x"
        val ratio = byId[id]?.ratio ?: 1.33f
        bitmaps.getOrPut(id) { paint(id, ratio) }.asImage()
    }

    private val skins = intArrayOf(0xFFF1C27D.toInt(), 0xFFE0AC69.toInt(), 0xFFC68642.toInt(), 0xFF8D5524.toInt(), 0xFFFFDBAC.toInt())
    private val backdrops = intArrayOf(0xFF9CC5A1.toInt(), 0xFFF4B393.toInt(), 0xFF8FB8DE.toInt(), 0xFFE8C547.toInt(), 0xFFC9B8FF.toInt(), 0xFFF7A1A1.toInt())
    private val hairs = intArrayOf(0xFF2B1B10.toInt(), 0xFF5A3825.toInt(), 0xFFB5651D.toInt(), 0xFF1A1A1A.toInt(), 0xFFD9C27A.toInt(), 0xFF9E9E9E.toInt())

    /** A simple portrait (backdrop, shoulders, head, hair) for fake people. */
    private fun face(key: String): Bitmap {
        val rnd = Random(key.hashCode())
        val s = 200
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        c.drawColor(backdrops[rnd.nextInt(backdrops.size)])
        paint.color = listOf(0xFF3D6BFF, 0xFF2E9E58, 0xFFFF5A1F, 0xFF111111, 0xFFFFFFFF).map { it.toInt() }[rnd.nextInt(5)]
        c.drawOval(30f, 150f, 170f, 290f, paint)
        val skin = skins[rnd.nextInt(skins.size)]
        paint.color = skin
        c.drawRect(85f, 120f, 115f, 160f, paint)
        c.drawOval(58f, 48f, 142f, 148f, paint)
        paint.color = hairs[rnd.nextInt(hairs.size)]
        val long = rnd.nextBoolean()
        c.drawArc(54f, 36f, 146f, 120f, 180f, 180f, true, paint)
        if (long) { c.drawRect(54f, 78f, 70f, 150f, paint); c.drawRect(130f, 78f, 146f, 150f, paint) }
        paint.color = 0xFF222222.toInt()
        c.drawCircle(84f, 100f, 4f, paint); c.drawCircle(116f, 100f, 4f, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f
        c.drawArc(86f, 108f, 114f, 130f, 20f, 140f, false, paint)
        return bmp
    }

    private class Palette(val skyTop: Int, val skyBottom: Int, val sun: Int, val far: Int, val near: Int)

    private val palettes = listOf(
        Palette(0xFF2B2E83.toInt(), 0xFFFF8A5B.toInt(), 0xFFFFE08A.toInt(), 0xFF6B3A6E.toInt(), 0xFF2A1B3D.toInt()), // dusk
        Palette(0xFF3E8EDE.toInt(), 0xFFBFE6FF.toInt(), 0xFFFFFFFF.toInt(), 0xFF2F8F83.toInt(), 0xFFE9D8A6.toInt()), // beach
        Palette(0xFF8EC5FC.toInt(), 0xFFE0F2FF.toInt(), 0xFFFFF6D5.toInt(), 0xFF5A8F5A.toInt(), 0xFF2F5D3A.toInt()), // hills
        Palette(0xFF0F1A3A.toInt(), 0xFF3B4C7A.toInt(), 0xFFF5F0D0.toInt(), 0xFF1D2540.toInt(), 0xFF0B0F1E.toInt()), // night
        Palette(0xFFF7B267.toInt(), 0xFFFCE5C0.toInt(), 0xFFFFFFFF.toInt(), 0xFFD9895B.toInt(), 0xFFA8553A.toInt()), // desert
        Palette(0xFFB8C6DB.toInt(), 0xFFF5F7FA.toInt(), 0xFFFFFFFF.toInt(), 0xFF8D9BB0.toInt(), 0xFFE8EEF4.toInt()), // snow
        Palette(0xFFFF6F61.toInt(), 0xFFFFD3B6.toInt(), 0xFFFFF1C1.toInt(), 0xFF9C4F6B.toInt(), 0xFF3D2C4E.toInt()), // sunset
        Palette(0xFF5EC6C2.toInt(), 0xFFD7F5EE.toInt(), 0xFFFFFFFF.toInt(), 0xFF2E7D74.toInt(), 0xFF164E4A.toInt()), // lagoon
    )

    /** A tiny landscape: gradient sky, a sun, two layers of hills, a little noise for texture. */
    private fun paint(id: String, ratio: Float): Bitmap {
        val w = if (ratio >= 1f) 360 else (360 * ratio).toInt()
        val h = if (ratio >= 1f) (360 / ratio).toInt() else 360
        val rnd = Random(id.hashCode())
        val p = palettes[rnd.nextInt(palettes.size)]
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val horizon = h * (0.45f + rnd.nextFloat() * 0.25f)
        paint.shader = LinearGradient(0f, 0f, 0f, horizon, p.skyTop, p.skyBottom, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        val sx = w * (0.2f + rnd.nextFloat() * 0.6f)
        val sy = horizon * (0.35f + rnd.nextFloat() * 0.45f)
        val sr = minOf(w, h) * (0.07f + rnd.nextFloat() * 0.07f)
        paint.shader = RadialGradient(sx, sy, sr * 3.2f, (p.sun and 0x00FFFFFF) or 0x66000000, p.sun and 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawCircle(sx, sy, sr * 3.2f, paint)
        paint.shader = null
        paint.color = p.sun
        c.drawCircle(sx, sy, sr, paint)
        fun hills(base: Float, amp: Float, color: Int, freq: Float) {
            val path = Path().apply {
                moveTo(0f, h.toFloat())
                val phase = rnd.nextFloat() * 6f
                var x = 0f
                while (x <= w) {
                    lineTo(x, base - amp * (0.6f * sin(x / w * freq * PI.toFloat() + phase) + 0.4f * sin(x / w * freq * 2.7f * PI.toFloat() + phase * 1.7f)))
                    x += 6f
                }
                lineTo(w.toFloat(), h.toFloat()); close()
            }
            paint.color = color
            c.drawPath(path, paint)
        }
        hills(horizon, h * 0.08f, p.far, 1.5f + rnd.nextFloat() * 2f)
        hills(horizon + h * 0.14f, h * 0.06f, p.near, 1f + rnd.nextFloat() * 2f)
        // Grain.
        paint.color = 0x10FFFFFF
        repeat(w * h / 90) { c.drawPoint(rnd.nextFloat() * w, rnd.nextFloat() * h, paint) }
        return bmp
    }
}
