package com.roylet.photos.net

import com.roylet.photos.data.ConnectionMode
import com.roylet.photos.data.ServerConfig
import com.roylet.photos.net.FakeImmich.Companion.KEY
import com.roylet.photos.net.FakeImmich.Companion.USER
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The home-network address must be recognised when it's the same server reached another way
 * (a proxy, a different host name in front, fields that depend on how it's configured), and
 * still never be confused with someone else's server.
 */
class LanVerificationTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val servers = mutableListOf<FakeImmich>()

    private fun server(userId: String = USER, host: String = "127.0.0.1", port: Int = 0) =
        FakeImmich(userId, host = host, port = port).also { servers += it }

    @After fun tearDown() {
        scope.cancel()
        servers.forEach { it.shutdown() }
    }

    private fun cfg(public: String) = ServerConfig(publicUrl = public, apiKey = KEY, userId = USER)

    private fun manager(store: FakeStore, env: FakeEnv = FakeEnv(lan = LanInfo("127.0.0.1", 24, "127.0.0.254", "Wi-Fi")), scanner: LanScanner = testScanner()) =
        ConnectionManager(store, scanner, env, scope, debounceMs = 60_000, reprobeAfterFailureMs = 60_000)

    private fun config(order: List<Pair<String, String>>, pretty: Boolean = false): String {
        val sep = if (pretty) ",\n   " else ","
        return order.joinToString(sep, if (pretty) "{\n   " else "{", if (pretty) "\n}" else "}") { (k, v) -> "\"$k\"${if (pretty) " : " else ":"}$v" }
    }

    private val stable = listOf(
        "loginPageMessage" to "\"Welcome\"", "trashDays" to "30", "userDeleteDelay" to "7",
        "oauthButtonText" to "\"Login with OAuth\"", "publicUsers" to "true", "isInitialized" to "true",
    )

    /** What the public address (behind a proxy) says, and what the same server says on the LAN. */
    private fun publicConfig() = config(
        stable + listOf(
            "externalDomain" to "\"https://photos.royle.au\"", "mapDarkStyleUrl" to "\"https://tiles.example/dark.json\"",
            "isOnboarded" to "true", "oauthAccountManagementUrl" to "\"https://id.example/account\"", "maintenanceMode" to "false",
        ),
    )

    private fun lanConfig() = config(
        stable.reversed() + listOf(
            "externalDomain" to "\"\"", "mapDarkStyleUrl" to "\"http://192.168.8.185:2283/dark.json\"",
            "isOnboarded" to "false", "maintenanceMode" to "true", "someNewField" to "{\"a\":1}",
        ),
        pretty = true,
    )

    @Test fun `a candidate differing only in host-dependent config fields is accepted by the scan`() = runBlocking {
        val public = server().apply { configBody = publicConfig() }
        val ours = server(host = "127.0.0.3").apply { configBody = lanConfig() }
        val scanner = testScanner(port = ours.port)
        val fp = scanner.fingerprint(public.origin)

        val report = scanner.scan(scanner.candidates(LanInfo("127.0.0.1", 24)), fp, KEY, USER)

        assertEquals(ours.origin, report.found)
        assertTrue(ours.drain().any { it.path == "/api/users/me" && it.getHeader("x-api-key") == KEY })
    }

    @Test fun `key order and whitespace in the bodies don't change the fingerprint`() = runBlocking {
        val a = server().apply { configBody = config(stable) }
        val b = server().apply { configBody = config(stable.reversed(), pretty = true) }
        val scanner = testScanner()
        assertNotNull(scanner.fingerprint(a.origin))
        assertEquals(scanner.fingerprint(a.origin), scanner.fingerprint(b.origin))
        assertTrue(scanner.matchesFingerprint(b.origin, scanner.fingerprint(a.origin)))
    }

    @Test fun `an old hash-format fingerprint is replaced, not compared`() = runBlocking {
        val public = server()
        val local = server()
        val store = FakeStore(cfg(public.origin).copy(fingerprint = "a".repeat(64)))
        assertEquals(LocalUrlResult.Verified, manager(store).setLocalUrl(local.origin))
        assertTrue(store.server.value!!.fingerprint!!.startsWith(Fingerprint.PREFIX))
    }

    @Test fun `manual entry without a scheme normalises to http ip port and verifies`() = runBlocking {
        val public = server()
        val local = server()
        val store = FakeStore(cfg(public.origin))
        val cm = manager(store)

        assertEquals(LocalUrlResult.Verified, cm.setLocalUrl("127.0.0.1:${local.port}/api/"))

        val saved = store.server.value!!
        assertEquals("http://127.0.0.1:${local.port}", saved.localUrl)
        assertTrue(saved.localVerified)
        assertEquals("trusted right away", KEY, cm.apiKeyFor("http://127.0.0.1:${local.port}/api/assets".toHttpUrl()))
        val check = cm.check.value!!
        assertFalse(check.running)
        assertEquals(listOf("This device", "Address", "Ping", "Public fingerprint", "Fingerprint", "Account check", "Result"), check.steps.map { it.name })
        assertTrue(check.asText(), check.steps.all { it.status == StepStatus.Pass })
        assertTrue(check.steps.first().detail.contains("127.0.0.1/24"))
        assertTrue(check.steps.first { it.name == "Account check" }.detail.contains("HTTP 200"))
    }

    @Test fun `local candidates for typed addresses`() {
        assertEquals(listOf("http://192.168.8.185:2283"), LanScanner.localCandidates("192.168.8.185:2283"))
        assertEquals(listOf("http://192.168.8.185:2283"), LanScanner.localCandidates(" http://192.168.8.185:2283/api/ "))
        assertEquals(listOf("http://192.168.8.185:2283", "http://192.168.8.185"), LanScanner.localCandidates("192.168.8.185"))
        assertEquals(listOf("https://192.168.8.185:2283", "http://192.168.8.185:2283"), LanScanner.localCandidates("https://192.168.8.185:2283"))
    }

    @Test fun `an https address on an http-only lan server falls back to http`() = runBlocking {
        val public = server()
        val local = server()
        val store = FakeStore(cfg(public.origin))
        val cm = manager(store)

        assertEquals(LocalUrlResult.Verified, cm.setLocalUrl("https://127.0.0.1:${local.port}"))
        assertEquals("http://127.0.0.1:${local.port}", store.server.value!!.localUrl)
    }

    @Test fun `sign-in discovery tries http first for a lan ip without a scheme`() = runBlocking {
        val lan = server()
        val started = System.nanoTime()
        assertEquals("http://127.0.0.1:${lan.port}", LanScanner.discoverOrigin("127.0.0.1:${lan.port}", okhttp3.OkHttpClient()))
        assertTrue("http-only server found without waiting on https", (System.nanoTime() - started) / 1_000_000 < 3000)
        assertTrue(lan.drain().all { it.requestLine.startsWith("GET /api/server/ping") })
    }

    @Test fun `a typed address whose config differs is accepted on a user id match, with a note, and re-checks without the key`() = runBlocking {
        val public = server().apply { configBody = config(stable) }
        val local = server().apply { configBody = config(stable.map { if (it.first == "trashDays") it.first to "7" else it }) }
        val store = FakeStore(cfg(public.origin))
        val env = FakeEnv(lan = LanInfo("127.0.0.1", 24, "127.0.0.254", "Wi-Fi"))
        val cm = manager(store, env)

        assertEquals(LocalUrlResult.VerifiedWithNote, cm.setLocalUrl(local.origin))

        val me = local.drain().filter { it.path == "/api/users/me" }
        assertEquals(1, me.size)
        assertEquals("the key is attached to the candidate's /users/me", KEY, me.single().getHeader("x-api-key"))
        val fpStep = cm.check.value!!.steps.first { it.name == "Fingerprint" }
        assertEquals(StepStatus.Warn, fpStep.status)
        assertTrue(fpStep.detail, fpStep.detail.contains("config.trashDays: 30 (public) vs 7 (here)"))
        assertEquals(StepStatus.Warn, cm.check.value!!.steps.last().status)

        // Next network: trusted again from the stored local fingerprint, without sending the key.
        env.lan = LanInfo("127.0.0.1", 24, "127.0.0.253", "Wi-Fi")
        cm.onNetworkChange()
        store.updateServer { it.copy(mode = ConnectionMode.HOME) }
        cm.evaluate()
        assertEquals(ConnState.Home, cm.state.value)
        assertTrue(local.drain().none { it.getHeader("x-api-key") != null })
    }

    @Test fun `a typed impostor with the wrong user id is rejected and never trusted`() = runBlocking {
        val public = server()
        val impostor = server(userId = "u-someone-else")
        val store = FakeStore(cfg(public.origin))
        val cm = manager(store)

        assertEquals(LocalUrlResult.Unverified, cm.setLocalUrl(impostor.origin))
        assertFalse(store.server.value!!.localVerified)
        assertNull(cm.apiKeyFor("http://127.0.0.1:${impostor.port}/api/assets".toHttpUrl()))
        val user = cm.check.value!!.steps.first { it.name == "Account check" }
        assertEquals(StepStatus.Fail, user.status)
        assertTrue(user.detail, user.detail.contains("different user"))
    }

    @Test fun `a typed address running a different version never gets the key`() = runBlocking {
        val public = server()
        val impostor = server().apply { version = Triple(1, 99, 0) }
        val store = FakeStore(cfg(public.origin))
        val cm = manager(store)

        assertEquals(LocalUrlResult.Unverified, cm.setLocalUrl(impostor.origin))
        assertTrue(impostor.drain().none { it.getHeader("x-api-key") != null || it.path == "/api/users/me" })
        val fp = cm.check.value!!.steps.first { it.name == "Fingerprint" }
        assertEquals(StepStatus.Fail, fp.status)
        assertTrue(fp.detail, fp.detail.contains("version.minor"))
    }

    @Test fun `the scan never sends the key to a host whose fingerprint differs, and says which field`() = runBlocking {
        val public = server().apply { configBody = config(stable) }
        val impostor = server(host = "127.0.0.2").apply { configBody = config(stable.map { if (it.first == "loginPageMessage") it.first to "\"Hi\"" else it }) }
        val scanner = testScanner(port = impostor.port)
        val store = FakeStore(cfg(public.origin))
        val cm = manager(store, scanner = scanner)

        assertNull(cm.scanNow(force = true))

        assertTrue(impostor.drain().none { it.getHeader("x-api-key") != null || it.path == "/api/users/me" })
        val check = cm.check.value!!
        val fp = check.steps.first { it.name == "Fingerprint" }
        assertEquals(StepStatus.Fail, fp.status)
        assertTrue(fp.detail, fp.detail.contains("config.loginPageMessage"))
        assertTrue(check.steps.first { it.name == "Ping" }.detail.contains(impostor.origin))
        assertEquals(StepStatus.Fail, check.steps.last().status)
    }

    @Test fun `a user-started scan reports a missing lan`() = runBlocking {
        val cm = manager(FakeStore(cfg("https://photos.example.invalid")), FakeEnv(lan = null))
        assertNull(cm.scanNow(force = true))
        val check = cm.check.value!!
        assertEquals(StepStatus.Warn, check.steps.first().status)
        assertEquals(StepStatus.Fail, check.steps.last().status)
    }
}
