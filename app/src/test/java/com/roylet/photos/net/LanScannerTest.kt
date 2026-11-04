package com.roylet.photos.net

import com.roylet.photos.net.FakeImmich.Companion.KEY
import com.roylet.photos.net.FakeImmich.Companion.USER
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class LanScannerTest {
    private val servers = mutableListOf<FakeImmich>()
    private fun server(userId: String = USER, host: String = "127.0.0.1", port: Int = 0, message: String = "Welcome to the family photos") =
        FakeImmich(userId, host = host, port = port, loginPageMessage = message).also { servers += it }

    @After fun tearDown() = servers.forEach { it.shutdown() }

    @Test fun `same server only when the user id matches`() = runBlocking {
        val ours = server()
        val neighbour = server("u-someone-else")
        val scanner = testScanner()

        assertTrue(scanner.ping(ours.origin))
        assertTrue(scanner.isSameServer(ours.origin, KEY, USER))
        assertFalse("another account's server must not match", scanner.isSameServer(neighbour.origin, KEY, USER))
        assertFalse("a key the server rejects must not match", scanner.isSameServer(ours.origin, "wrong", USER))
        assertFalse("a blank stored user id never matches", scanner.isSameServer(ours.origin, KEY, ""))
        assertFalse(scanner.ping(DEAD))
        assertFalse(scanner.isSameServer(DEAD, KEY, USER))
    }

    @Test fun `fingerprint is stable, server specific and never carries the key`() = runBlocking {
        val a = server()
        val b = server()
        val other = server(message = "Someone else's server")
        val scanner = testScanner()

        val fp = scanner.fingerprint(a.origin)
        assertNotNull(fp)
        assertEquals(fp, scanner.fingerprint(b.origin))
        assertNotEquals(fp, scanner.fingerprint(other.origin))
        assertNull(scanner.fingerprint(DEAD))
        val seen = a.drain()
        assertEquals(setOf("/api/server/version", "/api/server/config", "/api/server/features"), seen.map { it.path }.toSet())
        assertTrue(seen.none { it.getHeader("x-api-key") != null })
    }

    @Test fun `an impostor answering ping with a different fingerprint never receives the key`() = runBlocking {
        val ours = server()
        val fp = testScanner().fingerprint(ours.origin)
        // Same user id and it would even accept the key: only the fingerprint gives it away.
        val impostor = server(host = "127.0.0.2", message = "Totally your server")
        val scanner = testScanner(port = impostor.port)

        val found = scanner.find(scanner.candidates(LanInfo("127.0.0.1", 24)), fp, KEY, USER)

        assertNull(found)
        val seen = impostor.drain()
        assertTrue("the impostor was probed", seen.any { it.path == "/api/server/ping" })
        assertTrue("x-api-key leaked to an impostor", seen.none { it.getHeader("x-api-key") != null })
        assertTrue(seen.none { it.path == "/api/users/me" })
    }

    @Test fun `a fingerprint-matching host on the subnet is accepted, and a matching neighbour account is not`() = runBlocking {
        val publicServer = server()
        val fp = testScanner().fingerprint(publicServer.origin)
        // Loopback stands in for a /24: the device is 127.0.0.1, a same-fingerprint other account .2, ours .3.
        val neighbour = server("u-someone-else", host = "127.0.0.2")
        val ours = server(host = "127.0.0.3", port = neighbour.port)
        val scanner = testScanner(port = ours.port)
        var progress = 0 to 0

        val found = scanner.find(scanner.candidates(LanInfo("127.0.0.1", 24)), fp, KEY, USER) { done, total -> progress = done to total }

        assertEquals("http://127.0.0.3:${ours.port}", found)
        assertEquals(253, progress.second)
        assertTrue(neighbour.drain().any { it.path == "/api/users/me" })
    }

    @Test fun `no fingerprint, no scan`() = runBlocking {
        val ours = server()
        assertNull(testScanner().find(listOf(ours.origin), null, KEY, USER))
        assertTrue(ours.drain().isEmpty())
    }

    @Test fun `the key is only sent to hosts that answered as immich`() = runBlocking {
        val notImmich = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setBody("<html>router</html>")
            }
            start()
        }
        val ours = server()
        val scanner = testScanner()
        val fp = scanner.fingerprint(ours.origin)
        val origin = notImmich.url("/").toString().trimEnd('/')

        assertEquals(ours.origin, scanner.find(listOf(origin, ours.origin), fp, KEY, USER))
        val seen = generateSequence { notImmich.takeRequest(0, TimeUnit.MILLISECONDS) }.toList()
        assertEquals(listOf("/api/server/ping"), seen.map { it.path })
        notImmich.shutdown()
    }

    @Test fun `only rfc1918 and link-local ip addresses are scanned or accepted`() {
        val scanner = LanScanner()
        for (ok in listOf("http://192.168.1.5:2283", "http://10.0.0.2:2283", "http://172.16.9.9:2283", "http://169.254.3.4:2283")) assertTrue(ok, scanner.allows(ok))
        for (bad in listOf("http://8.8.8.8:2283", "http://nas.local:2283", "https://photos.example.com", "http://127.0.0.1:2283", "http://100.64.1.1:2283", "http://172.32.0.1:2283")) {
            assertFalse(bad, scanner.allows(bad))
        }
        assertTrue(scanner.candidates(LanInfo("8.8.8.8", 24)).isEmpty())
    }

    @Test fun `external domain is read from server config`() = runBlocking {
        val s = server().apply { externalDomain = "https://photos.example.com" }
        assertEquals("https://photos.example.com", testScanner().externalDomain(s.origin))
        s.externalDomain = ""
        assertNull(testScanner().externalDomain(s.origin))
    }
}
