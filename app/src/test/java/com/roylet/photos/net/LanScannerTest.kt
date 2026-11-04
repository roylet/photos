package com.roylet.photos.net

import com.roylet.photos.net.FakeImmich.Companion.KEY
import com.roylet.photos.net.FakeImmich.Companion.USER
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanScannerTest {
    private val servers = mutableListOf<FakeImmich>()
    private fun server(userId: String, host: String = "127.0.0.1", port: Int = 0) = FakeImmich(userId, host = host, port = port).also { servers += it }

    @After fun tearDown() = servers.forEach { it.shutdown() }

    @Test fun `same server only when the user id matches`() = runBlocking {
        val ours = server(USER)
        val neighbour = server("u-someone-else")
        val scanner = LanScanner()

        assertTrue(scanner.ping(ours.origin))
        assertTrue(scanner.isSameServer(ours.origin, KEY, USER))
        assertFalse("another account's server must not match", scanner.isSameServer(neighbour.origin, KEY, USER))
        assertFalse("a key the server rejects must not match", scanner.isSameServer(ours.origin, "wrong", USER))
        assertFalse("a blank stored user id never matches", scanner.isSameServer(ours.origin, KEY, ""))
        assertFalse(scanner.ping(DEAD))
        assertFalse(scanner.isSameServer(DEAD, KEY, USER))
    }

    @Test fun `scan finds our server on the subnet and skips a neighbour's immich`() = runBlocking {
        // Loopback stands in for a /24: the device is 127.0.0.1, the neighbour .2, ours .3.
        val neighbour = server("u-someone-else", host = "127.0.0.2")
        val ours = server(USER, host = "127.0.0.3", port = neighbour.port)
        val scanner = LanScanner(port = ours.port)
        var progress = 0 to 0

        val candidates = scanner.candidates(LanInfo("127.0.0.1", 24, gateway = null))
        val found = scanner.find(candidates, KEY, USER) { done, total -> progress = done to total }

        assertEquals("http://127.0.0.3:${ours.port}", found)
        assertTrue(progress.second == 253)
        assertTrue("the neighbour was asked who we are, and rejected", neighbour.drain().any { it.path == "/api/users/me" })
    }

    @Test fun `scan returns null when only other accounts answer`() = runBlocking {
        val neighbour = server("u-someone-else")
        assertNull(LanScanner().find(listOf(DEAD, neighbour.origin), KEY, USER))
    }

    @Test fun `the key is only sent to hosts that answered as immich`() = runBlocking {
        val notImmich = okhttp3.mockwebserver.MockWebServer().apply {
            dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = okhttp3.mockwebserver.MockResponse().setBody("<html>router</html>")
            }
            start()
        }
        val ours = server(USER)
        val origin = notImmich.url("/").toString().trimEnd('/')

        assertEquals(ours.origin, LanScanner().find(listOf(origin, ours.origin), KEY, USER))
        val seen = generateSequence { notImmich.takeRequest(0, java.util.concurrent.TimeUnit.MILLISECONDS) }.toList()
        assertEquals(listOf("/api/server/ping"), seen.map { it.path })
        assertTrue(seen.all { it.getHeader("x-api-key") == null })
        notImmich.shutdown()
    }

    @Test fun `external domain is read from server config`() = runBlocking {
        val s = server(USER).apply { externalDomain = "https://photos.example.com" }
        assertEquals("https://photos.example.com", LanScanner().externalDomain(s.origin))
        s.externalDomain = ""
        assertNull(LanScanner().externalDomain(s.origin))
    }
}
