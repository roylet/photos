package com.roylet.photos.viewer

import com.roylet.photos.ui.viewer.RangeMediaDataSource
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException

/** [RangeMediaDataSource] against a MockWebServer that serves [file] with (or without) Range support. */
class RangeMediaDataSourceTest {
    private val file = ByteArray(10_000) { (it * 7 + 3).toByte() }
    private lateinit var server: MockWebServer
    private val ranges = mutableListOf<String?>()
    private var honourRange = true
    private val client = OkHttpClient()

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range")
                synchronized(ranges) { ranges += range }
                if (!honourRange || range == null) return MockResponse().setBody(Buffer().write(file))
                val (a, b) = range.removePrefix("bytes=").split('-').map { it.toLong() }
                if (a >= file.size) return MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */${file.size}")
                val end = minOf(b, file.size - 1L)
                return MockResponse().setResponseCode(206)
                    .setHeader("Content-Range", "bytes $a-$end/${file.size}")
                    .setBody(Buffer().write(file.copyOfRange(a.toInt(), end.toInt() + 1)))
            }
        }
        server.start()
    }

    @After fun tearDown() = server.shutdown()

    private fun source(chunk: Int = 4096) = RangeMediaDataSource(client, server.url("/api/assets/v1/video/playback").toString(), chunkSize = chunk)

    @Test fun readsAtOffsetsWithAlignedRangeRequests() {
        val src = source()
        val buf = ByteArray(100)
        assertEquals(100, src.readAt(5_000, buf, 0, 100))
        assertArrayEquals(file.copyOfRange(5_000, 5_100), buf)
        assertEquals("bytes=4096-8191", ranges.single())
        assertEquals(10_000L, src.knownSize)
        // Same block: served from memory.
        assertEquals(50, src.readAt(4_096, buf, 10, 50))
        assertArrayEquals(file.copyOfRange(4_096, 4_146), buf.copyOfRange(10, 60))
        assertEquals(1, src.requests)
    }

    @Test fun readsNeverCrossABlockSoTheyMayBePartial() {
        val src = source()
        val buf = ByteArray(1_000)
        // 4000..4095 is the tail of block 0: only 96 bytes come back.
        assertEquals(96, src.readAt(4_000, buf, 0, 1_000))
        assertArrayEquals(file.copyOfRange(4_000, 4_096), buf.copyOfRange(0, 96))
        // The caller continues from there and gets the next block.
        assertEquals(1_000, src.readAt(4_096, buf, 0, 1_000))
        assertArrayEquals(file.copyOfRange(4_096, 5_096), buf)
    }

    @Test fun endOfFile() {
        val src = source()
        val buf = ByteArray(500)
        // The last block is short.
        assertEquals(400, src.readAt(9_600, buf, 0, 500))
        assertArrayEquals(file.copyOfRange(9_600, 10_000), buf.copyOfRange(0, 400))
        // At or past the end: -1 without asking the server again.
        val before = src.requests
        assertEquals(-1, src.readAt(10_000, buf, 0, 10))
        assertEquals(-1, src.readAt(12_345, buf, 0, 10))
        assertEquals(before, src.requests)
        assertEquals(0, src.readAt(0, buf, 0, 0))
    }

    @Test fun pastTheEndBeforeTheSizeIsKnown() {
        val src = source()
        assertEquals(-1, src.readAt(20_000, ByteArray(10), 0, 10))
        assertEquals(10_000L, src.knownSize)
    }

    @Test fun sizeComesFromContentRange() {
        val src = source()
        assertEquals(10_000L, src.size)
        assertEquals("bytes=0-4095", ranges.single())
        // That first block is reused.
        val buf = ByteArray(16)
        assertEquals(16, src.readAt(0, buf, 0, 16))
        assertEquals(1, src.requests)
    }

    @Test fun serverWithoutRangeSupport() {
        honourRange = false
        val src = source()
        val buf = ByteArray(200)
        assertEquals(200, src.readAt(8_500, buf, 0, 200))
        assertArrayEquals(file.copyOfRange(8_500, 8_700), buf)
        assertEquals(10_000L, src.knownSize)
    }

    @Test fun closedSourceFails() {
        val src = source()
        src.close()
        try {
            src.readAt(0, ByteArray(4), 0, 4)
            fail("expected IOException")
        } catch (_: IOException) { }
        assertTrue(ranges.isEmpty())
    }

    @Test fun httpErrorsFail() {
        val src = RangeMediaDataSource(client, server.url("/missing").toString())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(404)
        }
        try {
            src.readAt(0, ByteArray(4), 0, 4)
            fail("expected IOException")
        } catch (_: IOException) { }
    }

    @Test fun contentRangeParsing() {
        assertEquals(4096L, RangeMediaDataSource.contentRangeTotal("bytes 0-1023/4096"))
        assertEquals(4096L, RangeMediaDataSource.contentRangeTotal("bytes */4096"))
        assertNull(RangeMediaDataSource.contentRangeTotal("bytes 0-1023/*"))
        assertNull(RangeMediaDataSource.contentRangeTotal(null))
    }
}
