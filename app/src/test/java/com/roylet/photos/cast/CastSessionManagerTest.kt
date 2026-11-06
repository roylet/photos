package com.roylet.photos.cast

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.concurrent.TimeUnit

class CastSessionManagerTest {
    private lateinit var server: MockWebServer
    private var now = Instant.parse("2026-11-06T10:00:00Z").toEpochMilli()
    private var parent: String? = "parent-token"

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() = server.shutdown()

    private fun manager(ledger: CastKeyLedger = CastKeyLedger.InMemory()) =
        CastSessionManager(OkHttpClient(), { server.url("/").toString().trimEnd('/') }, { parent }, now = { now }, ledger = ledger)

    private fun created(id: String, token: String, expires: String = "2026-11-06T10:30:00.000Z") = MockResponse().setResponseCode(201)
        .setBody("""{"id":"$id","token":"$token","expiresAt":"$expires","createdAt":"x","updatedAt":"x","current":false,"deviceOS":"Google Cast","deviceType":"Cast","appVersion":null,"isPendingSyncReset":false}""")

    @Test fun createsAChildSessionWithTheLoginSession() = runBlocking {
        server.enqueue(created("s-1", "child-1"))
        val r = manager().key()
        assertEquals("child-1", (r as KeyResult.Ok).key.token)
        assertEquals(Instant.parse("2026-11-06T10:30:00Z").toEpochMilli(), r.key.expiresAt)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/sessions", req.path)
        assertEquals("Bearer parent-token", req.getHeader("Authorization"))
        assertNull(req.getHeader("x-api-key"))
        val body = req.body.readUtf8()
        assertTrue(body, body.contains("\"duration\":1800") && body.contains("\"deviceType\":\"Cast\"") && body.contains("\"deviceOS\":\"Google Cast\""))
    }

    @Test fun reusesTheKeyUntilTenSecondsRemain() = runBlocking {
        server.enqueue(created("s-1", "child-1"))
        server.enqueue(created("s-2", "child-2", "2026-11-06T11:00:00.000Z"))
        val m = manager()
        assertEquals("child-1", (m.key() as KeyResult.Ok).key.token)
        now += 29 * 60_000L // 60 s left
        assertEquals("child-1", (m.key() as KeyResult.Ok).key.token)
        assertEquals(1, server.requestCount)
        now += 51_000L // 9 s left
        assertEquals("child-2", (m.key() as KeyResult.Ok).key.token)
        assertEquals(2, server.requestCount)
    }

    @Test fun deletesTheChildSessionWhenCastingEnds() = runBlocking {
        server.enqueue(created("s-1", "child-1"))
        server.enqueue(MockResponse().setResponseCode(204))
        val m = manager()
        m.key()
        m.end()
        server.takeRequest()
        val del = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("DELETE", del.method)
        assertEquals("/api/sessions/s-1", del.path)
        assertEquals("Bearer parent-token", del.getHeader("Authorization"))
        // Nothing left to delete.
        m.end()
        assertEquals(2, server.requestCount)
    }

    @Test fun unauthorizedTurnsOnTheSignInPrompt() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"Invalid user token"}"""))
        val m = manager()
        assertEquals(KeyResult.NeedsSignIn, m.key())
        assertTrue(m.needsSignIn.value)
        m.onSignedIn()
        assertFalse(m.needsSignIn.value)
    }

    @Test fun apiKeySignInHasNoSessionToMakeChildrenFrom() = runBlocking {
        parent = null
        val m = manager()
        assertEquals(KeyResult.NeedsSignIn, m.key())
        assertTrue(m.needsSignIn.value)
        assertEquals(0, server.requestCount)
    }

    @Test fun serverErrorsAreFailuresNotSignIns() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        val r = manager().key()
        assertTrue(r is KeyResult.Failed)
    }

    @Test fun aKilledAppsChildSessionIsDeletedAtTheNextStart() = runBlocking {
        val ledger = CastKeyLedger.InMemory()
        server.enqueue(created("s-1", "child-1"))
        manager(ledger).key()
        server.takeRequest()
        assertEquals(setOf("s-1"), ledger.ids())
        // The process dies here; the next one starts with only the ledger.
        server.enqueue(MockResponse().setResponseCode(204))
        val next = manager(ledger)
        next.cleanUpStale()
        val del = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("DELETE /api/sessions/s-1", "${del.method} ${del.path}")
        assertEquals("Bearer parent-token", del.getHeader("Authorization"))
        assertTrue(ledger.ids().isEmpty())
        next.cleanUpStale()
        assertEquals(2, server.requestCount)
    }

    @Test fun cleanUpLeavesALiveSessionAlone() = runBlocking {
        val ledger = CastKeyLedger.InMemory()
        server.enqueue(created("s-1", "child-1"))
        val m = manager(ledger)
        m.key()
        m.cleanUpStale()
        assertEquals(1, server.requestCount)
        assertEquals(setOf("s-1"), ledger.ids())
        // Ending deletes it and empties the ledger.
        server.enqueue(MockResponse().setResponseCode(204))
        m.end()
        assertEquals(2, server.requestCount)
        assertTrue(ledger.ids().isEmpty())
    }

    @Test fun keyNeverPrintsItsToken() {
        assertFalse(CastKey("id", "secret-token", 0).toString().contains("secret-token"))
    }
}
