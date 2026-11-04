package com.roylet.photos.net

import com.roylet.photos.data.ServerConfig
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.net.InetAddress

/**
 * A fake Immich server: answers ping, `/users/me` (for [userId] with [apiKey] only), server
 * config and anything else with "ok". Routed by path so background probes can't steal responses.
 */
class FakeImmich(
    val userId: String,
    val apiKey: String = KEY,
    host: String = "127.0.0.1",
    port: Int = 0,
    var externalDomain: String = "",
    /** Changes the unauthenticated fingerprint: a different "server". */
    var loginPageMessage: String = "Welcome to the family photos",
) {
    val server = MockWebServer()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/server/ping" -> MockResponse().setBody("""{"res":"pong"}""")
                "/api/users/me" ->
                    if (request.getHeader("x-api-key") == apiKey) MockResponse().setBody("""{"id":"$userId","email":"me@example.com"}""")
                    else MockResponse().setResponseCode(401).setBody("""{"message":"Invalid API key"}""")
                "/api/server/config" -> MockResponse().setBody(
                    """{"externalDomain":"$externalDomain","loginPageMessage":"$loginPageMessage","oauthButtonText":"Login with OAuth","trashDays":30,"maintenanceMode":false}""",
                )
                "/api/server/version" -> MockResponse().setBody("""{"major":2,"minor":4,"patch":1,"prerelease":null}""")
                "/api/server/features" -> MockResponse().setBody("""{"trash":true,"map":true,"oauth":false,"passwordLogin":true,"smartSearch":true}""")
                else -> MockResponse().setBody("ok")
            }
        }
        server.start(InetAddress.getByName(host), port)
    }

    val origin: String get() = server.url("/").toString().trimEnd('/')
    val port: Int get() = server.port

    /** Every request this server saw so far (non-blocking). */
    fun drain(): List<RecordedRequest> = generateSequence { server.takeRequest(0, java.util.concurrent.TimeUnit.MILLISECONDS) }.toList()

    fun shutdown() = server.shutdown()

    companion object {
        const val KEY = "k-ours"
        const val USER = "u-1"
    }
}

class FakeStore(initial: ServerConfig?) : ServerStore {
    override val server = MutableStateFlow(initial)
    override fun updateServer(change: (ServerConfig) -> ServerConfig) {
        server.value = server.value?.let(change)
    }
}

class FakeEnv(var online: Boolean = true, var lan: LanInfo? = null) : NetworkEnvironment {
    override fun online() = online
    override fun lan() = lan
}

/** Like the real default, but loopback test servers count as LAN addresses. */
fun testScanner(port: Int = LanScanner.IMMICH_PORT) =
    LanScanner(port = port, allowHost = { Subnet.isLanAddress(it) || it.startsWith("127.") || it == "localhost" })

/** An origin nothing listens on: connections are refused at once. */
const val DEAD = "http://127.0.0.1:1"
