package com.roylet.photos.net

import com.roylet.photos.AppContainer
import com.roylet.photos.data.ConnectionMode
import com.roylet.photos.data.ServerConfig
import com.roylet.photos.immich.ImmichApi
import com.roylet.photos.net.FakeImmich.Companion.KEY
import com.roylet.photos.net.FakeImmich.Companion.USER
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class ConnectionManagerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val servers = mutableListOf<FakeImmich>()
    private var now = 1_000_000L

    private fun server(userId: String = USER, host: String = "127.0.0.1", port: Int = 0) = FakeImmich(userId, host = host, port = port).also { servers += it }

    @After fun tearDown() {
        scope.cancel()
        servers.forEach { it.shutdown() }
    }

    private fun cfg(public: String, local: String? = null, mode: ConnectionMode = ConnectionMode.AUTO) =
        ServerConfig(publicUrl = public, localUrl = local, apiKey = KEY, userId = USER, mode = mode)

    private fun manager(store: FakeStore, env: FakeEnv = FakeEnv(), scanner: LanScanner = LanScanner()) =
        // A long debounce keeps background refreshes out of the way of the explicit evaluate() calls.
        ConnectionManager(store, scanner, env, scope, clock = { now }, debounceMs = 60_000, reprobeAfterFailureMs = 60_000)

    private fun client(cm: ConnectionManager, store: FakeStore): OkHttpClient = AppContainer.serverClient(ImmichApi.defaultClient, cm, store)

    private fun OkHttpClient.get(url: String, headers: Map<String, String> = emptyMap()): String =
        newCall(Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()).execute().use { it.body!!.string() }

    // URL choice and fallback

    @Test fun `auto uses the local address when it answers`() = runBlocking {
        val public = server()
        val local = server()
        val cm = manager(FakeStore(cfg(public.origin, local.origin)))
        cm.evaluate()
        assertEquals(ConnState.Home, cm.state.value)
        assertEquals(local.origin, cm.activeUrl.value)
    }

    @Test fun `auto falls back to public when the local address is dead`() = runBlocking {
        val public = server()
        val cm = manager(FakeStore(cfg(public.origin, DEAD)))
        cm.evaluate()
        assertEquals(ConnState.Remote, cm.state.value)
        assertEquals(public.origin, cm.activeUrl.value)
    }

    @Test fun `offline when nothing answers or there is no network`() = runBlocking {
        val env = FakeEnv()
        val cm = manager(FakeStore(cfg("http://127.0.0.1:2", DEAD)), env)
        cm.evaluate()
        assertEquals(ConnState.Offline, cm.state.value)

        val public = server()
        val cm2 = manager(FakeStore(cfg(public.origin)), FakeEnv(online = false))
        cm2.evaluate()
        assertEquals(ConnState.Offline, cm2.state.value)
        assertEquals(public.origin, cm2.activeUrl.value)
    }

    @Test fun `mode override beats auto`() = runBlocking {
        val public = server()
        val local = server()
        val store = FakeStore(cfg(public.origin, local.origin, ConnectionMode.REMOTE))
        val cm = manager(store)
        cm.evaluate()
        assertEquals(ConnState.Remote, cm.state.value)
        assertEquals(public.origin, cm.activeUrl.value)

        store.updateServer { it.copy(mode = ConnectionMode.HOME) }
        cm.evaluate()
        assertEquals(ConnState.Home, cm.state.value)
        assertEquals(local.origin, cm.activeUrl.value)
    }

    @Test fun `signed out means offline with no url`() = runBlocking {
        val cm = manager(FakeStore(null))
        cm.evaluate()
        assertEquals(ConnState.Offline, cm.state.value)
        assertEquals("", cm.activeUrl.value)
    }

    @Test fun `signing in by lan ip learns the public url from externalDomain`() = runBlocking {
        val lan = server().apply { externalDomain = "https://photos.example.com/" }
        val store = FakeStore(cfg(lan.origin))
        manager(store).evaluate()
        assertEquals("https://photos.example.com", store.server.value!!.publicUrl)
        assertEquals(lan.origin, store.server.value!!.localUrl)
    }

    @Test fun `an empty externalDomain changes nothing`() = runBlocking {
        val lan = server()
        val store = FakeStore(cfg(lan.origin))
        manager(store).evaluate()
        assertEquals(lan.origin, store.server.value!!.publicUrl)
        assertNull(store.server.value!!.localUrl)
    }

    // LAN scan

    @Test fun `scan saves the found server, goes home, and is rate limited per network`() = runBlocking {
        val ours = server(host = "127.0.0.2")
        val store = FakeStore(cfg("https://photos.example.invalid"))
        val env = FakeEnv(lan = LanInfo("127.0.0.1", 24, gateway = "127.0.0.1"))
        val cm = manager(store, env, LanScanner(port = ours.port))

        assertEquals(ours.origin, cm.scanNow(force = false))
        assertEquals(ours.origin, store.server.value!!.localUrl)
        assertEquals(ConnState.Home, cm.state.value)
        assertEquals(ScanState.Found(ours.origin), cm.scan.value)

        assertNull("a second scan on the same network within 10 minutes is skipped", cm.scanNow(force = false))
        assertEquals(ours.origin, cm.scanNow(force = true))
        now += TimeUnit.MINUTES.toMillis(11)
        assertEquals(ours.origin, cm.scanNow(force = false))
    }

    @Test fun `no scan off wifi`() = runBlocking {
        val cm = manager(FakeStore(cfg("https://photos.example.invalid")), FakeEnv(lan = null))
        assertNull(cm.scanNow(force = true))
        assertEquals(ScanState.NoLan, cm.scan.value)
    }

    // Interceptor

    @Test fun `requests for the server are rewritten to the active address with the key`() = runBlocking {
        val public = server()
        val local = server()
        val store = FakeStore(cfg(public.origin, local.origin))
        val cm = manager(store)
        cm.evaluate()
        local.drain()
        public.drain()

        client(cm, store).get("${public.origin}/api/assets/a1/thumbnail?size=preview")

        val req = local.drain().single()
        assertEquals("/api/assets/a1/thumbnail?size=preview", req.path)
        assertEquals(KEY, req.getHeader("x-api-key"))
        assertTrue(public.drain().isEmpty())
    }

    @Test fun `a dead local address flips to public and retries once`() = runBlocking {
        val public = server()
        val local = server()
        val store = FakeStore(cfg(public.origin, local.origin))
        val cm = manager(store)
        cm.evaluate()
        assertEquals(ConnState.Home, cm.state.value)
        public.drain()
        local.shutdown()

        assertEquals("ok", client(cm, store).get("${public.origin}/api/timeline/buckets"))

        assertEquals(ConnState.Remote, cm.state.value)
        assertEquals(public.origin, cm.activeUrl.value)
        val req = public.drain().single()
        assertEquals("/api/timeline/buckets", req.path)
        assertEquals(KEY, req.getHeader("x-api-key"))
    }

    @Test fun `requests for the lan address are sent to public when away`() = runBlocking {
        val public = server()
        val store = FakeStore(cfg(public.origin, DEAD))
        val cm = manager(store)
        cm.evaluate()
        public.drain()
        client(cm, store).get("$DEAD/api/assets/x/original")
        assertEquals("/api/assets/x/original", public.drain().single().path)
    }

    @Test fun `the api key never leaks to other hosts`() = runBlocking {
        val public = server()
        val other = MockWebServer().apply { start() }
        val store = FakeStore(cfg(public.origin))
        val cm = manager(store)
        val http = client(cm, store)
        repeat(3) { other.enqueue(MockResponse().setBody("tile")) }

        http.get(other.url("/tiles/1/2/3.png").toString())
        http.get(other.url("/explicit").toString(), mapOf("x-api-key" to KEY))
        // Our server redirecting elsewhere: OkHttp keeps custom headers across hosts, the guard doesn't.
        val redirecting = MockWebServer().apply {
            enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/after-redirect")))
            start()
        }
        store.updateServer { it.copy(publicUrl = redirecting.url("/").toString().trimEnd('/')) }
        http.get(redirecting.url("/api/assets/x/original").toString())

        assertEquals(KEY, redirecting.takeRequest().getHeader("x-api-key"))
        repeat(3) { assertNull("key leaked to ${other.hostName}", other.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("x-api-key")) }
        other.shutdown()
        redirecting.shutdown()
    }

    @Test fun `nothing is rewritten or keyed when signed out`() = runBlocking {
        val s = server()
        val store = FakeStore(null)
        client(manager(store), store).get("${s.origin}/api/server/ping")
        assertNull(s.drain().single().getHeader("x-api-key"))
    }
}
