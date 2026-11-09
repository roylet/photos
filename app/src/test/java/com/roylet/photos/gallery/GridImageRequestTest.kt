package com.roylet.photos.gallery

import coil3.size.Dimension
import com.roylet.photos.domain.source.AssetRef
import com.roylet.photos.imageMemoryCacheBytes
import com.roylet.photos.local.LocalMediaUrls
import com.roylet.photos.local.LocalThumbnailFetcher
import com.roylet.photos.ui.gallery.SourceMediaUrls
import com.roylet.photos.ui.gallery.gridImageUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What each grid cell asks the image loader for: never a phone original, always bounded by the cell. */
class GridImageRequestTest {
    private val urls = SourceMediaUrls(null, LocalMediaUrls)
    private val phone = AssetRef.local(42).id
    private val immich = "6f1c2a9e-0000-4000-8000-000000000001"

    @Test fun phoneItemsUseMediaStoreThumbnailsAtEveryLevel() {
        for ((w, h, sharp) in listOf(Triple(210f, 180f, false), Triple(530f, 1100f, true), Triple(1080f, 810f, true))) {
            val url = gridImageUrl(urls, phone, w, h, sharp)
            assertTrue(url, url.startsWith("localmedia://thumb/42"))
        }
    }

    @Test fun immichItemsUseThePreviewOnlyForBigCells() {
        assertEquals("fake://thumbnail/$immich", gridImageUrl(urls, immich, 210f, 180f, sharp = false))
        assertEquals("fake://preview/$immich", gridImageUrl(urls, immich, 400f, 300f, sharp = false))
        assertEquals("fake://preview/$immich", gridImageUrl(urls, immich, 300f, 200f, sharp = true))
    }

    @Test fun phoneThumbnailsAreBucketedToTheCell() {
        fun side(w: Int, h: Int) = LocalThumbnailFetcher.thumbnailSide(Dimension(w), Dimension(h))
        assertEquals(256, side(210, 180)) // five-up
        assertEquals(512, side(350, 290)) // three-up
        assertEquals(1080, side(530, 1080)) // two-up portrait
        assertEquals(1080, side(1080, 810)) // one-up feed
        assertEquals(2048, side(1080, 2400)) // the viewer's video poster
        assertEquals(2048, side(4000, 3000)) // never more
        assertEquals(512, LocalThumbnailFetcher.thumbnailSide(Dimension.Undefined, Dimension.Undefined))
    }

    @Test fun imageMemoryCacheIsCapped() {
        val mb = 1024L * 1024
        assertEquals((256 * mb * 0.15).toLong(), imageMemoryCacheBytes(256 * mb))
        assertEquals(192 * mb, imageMemoryCacheBytes(2048 * mb))
        assertEquals(16 * mb, imageMemoryCacheBytes(64 * mb))
    }
}
