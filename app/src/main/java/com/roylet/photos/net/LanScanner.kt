package com.roylet.photos.net

import com.roylet.photos.immich.ImmichApi
import com.roylet.photos.immich.UserMe
import com.roylet.photos.immich.await
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The device's IPv4 position on the current Wi-Fi/ethernet network. [key] identifies the network
 * (subnet + gateway) well enough to rate-limit scans per network across reconnects.
 */
data class LanInfo(
    val address: String,
    val prefix: Int,
    val gateway: String? = null,
    /** How the device is attached, for diagnostics ("Wi-Fi", "Ethernet", "Wi-Fi (VPN active)"). */
    val label: String = "",
) {
    val key: String get() = "${Subnet.networkOf(address, prefix) ?: address}/$prefix via ${gateway ?: "-"}"
}

/** Pure IPv4 subnet math, kept free of Android types so it can be unit tested. */
object Subnet {
    /** Never scan more than a /24 (254 hosts), however large the real subnet is. */
    const val MAX_SCAN_PREFIX = 24

    fun parse(ip: String): Int? {
        val parts = ip.trim().split('.')
        if (parts.size != 4) return null
        var v = 0
        for (p in parts) {
            val n = p.toIntOrNull()?.takeIf { it in 0..255 && p.isNotEmpty() && p.length <= 3 } ?: return null
            v = (v shl 8) or n
        }
        return v
    }

    fun format(v: Int): String = "${(v ushr 24) and 255}.${(v ushr 16) and 255}.${(v ushr 8) and 255}.${v and 255}"

    private fun mask(prefix: Int): Int = if (prefix <= 0) 0 else (-1 shl (32 - prefix))

    fun networkOf(address: String, prefix: Int): String? = parse(address)?.let { format(it and mask(prefix.coerceIn(0, 32))) }

    /**
     * The host addresses worth probing on [address]/[prefix], in ascending order: the network is
     * narrowed to the /24 around the device when it's bigger, and the network address, broadcast
     * address and the device itself are left out. /31 and /32 links have nothing to scan.
     */
    fun scanHosts(address: String, prefix: Int): List<String> {
        val self = parse(address) ?: return emptyList()
        if (prefix !in 0..30) return emptyList()
        val p = maxOf(prefix, MAX_SCAN_PREFIX)
        val m = mask(p)
        val network = self and m
        val broadcast = network or m.inv()
        // Compare as unsigned longs: addresses above 128.0.0.0 are negative Ints.
        val first = (network.toLong() and 0xFFFFFFFFL) + 1
        val last = (broadcast.toLong() and 0xFFFFFFFFL) - 1
        return (first..last).map { it.toInt() }.filter { it != self }.map(::format)
    }

    /** RFC 1918, loopback, link-local and CGNAT ranges, plus mDNS names: addresses that only work locally. */
    fun isPrivateHost(host: String): Boolean {
        if (host.equals("localhost", ignoreCase = true) || host.endsWith(".local", ignoreCase = true) || host.endsWith(".lan", ignoreCase = true)) return true
        val v = parse(host) ?: return false
        val a = (v ushr 24) and 255
        val b = (v ushr 16) and 255
        return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254) || (a == 100 && b in 64..127)
    }

    /**
     * The only addresses accepted as a local URL or scanned: IPv4 literals in RFC 1918
     * (10/8, 172.16/12, 192.168/16) or link-local (169.254/16). No hostnames, so DNS on a
     * hostile network can't point a "local" name anywhere.
     */
    fun isLanAddress(host: String): Boolean {
        val v = parse(host) ?: return false
        val a = (v ushr 24) and 255
        val b = (v ushr 16) and 255
        return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254)
    }
}

@Serializable private data class Pong(val res: String = "")
@Serializable private data class ServerConfigDto(val externalDomain: String = "")

/** Result of one probe, with a human-readable [detail] for the Settings diagnostics. */
data class Probe(val ok: Boolean, val detail: String)

/** Result of `GET /users/me` with the key: [status] is the HTTP status, null if no response at all. */
data class UserCheck(val ok: Boolean, val status: Int?, val detail: String)

/** What a host showed us when fetching its [Fingerprint]: the fields, or why it couldn't be read. */
data class FingerprintRead(val fields: Map<String, String>?, val detail: String) {
    val encoded: String? get() = fields?.let(Fingerprint::encode)
}

/** What a scan saw, for the diagnostics panel. */
data class ScanReport(
    val found: String?,
    val probed: Int,
    /** Hosts that answered `/api/server/ping` with `pong`. */
    val answered: List<String>,
    /** Hosts that answered as Immich but whose fingerprint differs: origin to what differed (or a read error). */
    val mismatched: Map<String, String>,
    /** Hosts that matched the fingerprint but failed the `/users/me` check: origin to reason. */
    val userFailed: Map<String, String>,
)

/**
 * Finds this account's Immich server on the local network, in stages so the API key only ever
 * goes to a host that already looks like our server:
 * 1. ping `/api/server/ping` (short connect timeout for the subnet sweep, a longer one plus a
 *    retry for the addresses most likely to be the server: the saved local URL and the gateway);
 * 2. compare an unauthenticated [Fingerprint] (a few stable, host-independent fields of version,
 *    config and features) with the one cached from the public URL; hosts that don't match never
 *    see the key;
 * 3. only then `GET /api/users/me` with the key, which must return the same user id.
 *
 * Only [allowHost] addresses are probed at all (RFC 1918 / link-local by default).
 *
 * Uses its own clients with their own dispatcher, so a scan never queues behind (or starves) the
 * app's image loading, and with redirects off so the key can't be bounced elsewhere. Only the
 * sweep uses the tight timeout: verifying a host that already answered (fingerprint, `/users/me`)
 * and reading the public fingerprint use ordinary timeouts, since Wi-Fi power saving and ARP
 * warm-up regularly make the first connect to a LAN host take longer than a few hundred ms.
 * [base] must not carry the app's URL-rewriting interceptor.
 */
class LanScanner(
    base: OkHttpClient = ImmichApi.defaultClient,
    val port: Int = IMMICH_PORT,
    private val parallelism: Int = 48,
    private val connectTimeoutMs: Long = 500,
    private val knownConnectTimeoutMs: Long = 1500,
    verifyConnectTimeoutMs: Long = 4000,
    private val allowHost: (String) -> Boolean = Subnet::isLanAddress,
) {
    /** True if [origin] is an address we'd accept as a local URL. */
    fun allows(origin: String): Boolean = ImmichApi.normalizeOrigin(origin).toHttpUrlOrNull()?.host?.let(allowHost) == true

    private val probeHttp = base.newBuilder()
        .dispatcher(Dispatcher().apply { maxRequests = parallelism * 2; maxRequestsPerHost = 4 })
        .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .callTimeout(4, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    /**
     * For hosts we already have reason to believe in, and for the public URL. Route retries stay
     * on (a dual-stack public name whose IPv6 route fails must still get to try IPv4).
     */
    private val verifyHttp = probeHttp.newBuilder()
        .connectTimeout(verifyConnectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private fun clientFor(connectMs: Long): OkHttpClient = when (connectMs) {
        connectTimeoutMs -> probeHttp
        else -> probeHttp.newBuilder().connectTimeout(connectMs, TimeUnit.MILLISECONDS)
            .callTimeout(maxOf(4000, connectMs * 2 + 2000), TimeUnit.MILLISECONDS).build()
    }

    /** True if [origin] answers as an Immich server; [timeoutMs] overrides the connect timeout. */
    suspend fun ping(origin: String, timeoutMs: Long? = null, attempts: Int = 1): Boolean =
        pingProbe(origin, timeoutMs ?: connectTimeoutMs, attempts).ok

    /** [ping] with the reason, for diagnostics. Retries a failed connect [attempts] - 1 times. */
    suspend fun pingProbe(origin: String, connectMs: Long = knownConnectTimeoutMs, attempts: Int = 2): Probe {
        val client = clientFor(connectMs)
        var last = Probe(false, "not tried")
        repeat(attempts.coerceAtLeast(1)) {
            val started = System.nanoTime()
            last = try {
                client.newCall(Request.Builder().url("${ImmichApi.normalizeOrigin(origin)}/api/server/ping").build()).await().use { res ->
                    val ms = (System.nanoTime() - started) / 1_000_000
                    val body = res.body?.string().orEmpty()
                    when {
                        !res.isSuccessful -> Probe(false, "HTTP ${res.code} from /api/server/ping")
                        runCatching { ImmichApi.json.decodeFromString<Pong>(body).res }.getOrNull() == "pong" -> Probe(true, "pong in $ms ms")
                        else -> Probe(false, "answered, but not as Immich (${body.take(40).replace('\n', ' ')})")
                    }
                }
            } catch (e: IOException) {
                Probe(false, describe(e))
            } catch (e: IllegalArgumentException) {
                Probe(false, "invalid address")
            }
            // Only a failure to connect is worth retrying; a real answer is final.
            if (last.ok || !last.detail.startsWith(NO_ANSWER)) return last
        }
        return last
    }

    /** True only if [origin] accepts [apiKey] and says it belongs to [userId]. */
    suspend fun isSameServer(origin: String, apiKey: String, userId: String): Boolean = userCheck(origin, apiKey, userId).ok

    /** `GET /users/me` on [origin] with [apiKey] (set explicitly, not by an interceptor). */
    suspend fun userCheck(origin: String, apiKey: String, userId: String): UserCheck {
        if (userId.isBlank()) return UserCheck(false, null, "no saved user id: sign in again")
        return try {
            val req = Request.Builder().url("${ImmichApi.normalizeOrigin(origin)}/api/users/me").header("x-api-key", apiKey).build()
            verifyHttp.newCall(req).await().use { res ->
                val body = res.body?.string().orEmpty()
                if (!res.isSuccessful) return@use UserCheck(false, res.code, "HTTP ${res.code}${if (res.code == 401) ": the key was rejected" else ""}")
                val id = runCatching { ImmichApi.json.decodeFromString<UserMe>(body).id }.getOrNull()
                when (id) {
                    null -> UserCheck(false, res.code, "HTTP ${res.code}, but not a user")
                    userId -> UserCheck(true, res.code, "HTTP ${res.code}, same user")
                    else -> UserCheck(false, res.code, "HTTP ${res.code}, but a different user")
                }
            }
        } catch (e: IOException) {
            UserCheck(false, null, describe(e))
        }
    }

    /** The encoded [Fingerprint] of [origin], or null if it can't be read. Never sends the key. */
    suspend fun fingerprint(origin: String): String? = readFingerprint(origin).encoded

    /** Reads [Fingerprint.FIELDS] from the unauthenticated version, config and features endpoints. */
    suspend fun readFingerprint(origin: String, quick: Boolean = false): FingerprintRead {
        val base = "${ImmichApi.normalizeOrigin(origin)}/api/server"
        val client = if (quick) probeHttp else verifyHttp
        val bodies = HashMap<String, String>()
        for (path in Fingerprint.ENDPOINTS) {
            try {
                client.newCall(Request.Builder().url("$base/$path").build()).await().use { res ->
                    if (!res.isSuccessful) return FingerprintRead(null, "HTTP ${res.code} from /api/server/$path")
                    bodies[path] = res.body?.string().orEmpty()
                }
            } catch (e: IOException) {
                return FingerprintRead(null, "/api/server/$path: ${describe(e)}")
            } catch (e: IllegalArgumentException) {
                return FingerprintRead(null, "invalid address")
            }
        }
        val fields = Fingerprint.extract(bodies) ?: return FingerprintRead(null, "the server's self-description isn't valid JSON")
        if (fields.keys.none { it.startsWith("version.") }) return FingerprintRead(null, "no version in /api/server/version")
        return FingerprintRead(fields, "read ${fields.size} fields")
    }

    /** True if [origin]'s fingerprint has the same fields as [expected]. Never sends the key. */
    suspend fun matchesFingerprint(origin: String, expected: String?): Boolean {
        val want = Fingerprint.decode(expected) ?: return false
        val got = readFingerprint(origin).fields ?: return false
        return Fingerprint.diff(want, got).isEmpty()
    }

    /** The server's configured public address (Administration → Server → External domain), if any. */
    suspend fun externalDomain(origin: String): String? = runCatching {
        verifyHttp.newCall(Request.Builder().url("${ImmichApi.normalizeOrigin(origin)}/api/server/config").build()).await().use { res ->
            if (!res.isSuccessful) return@use null
            ImmichApi.json.decodeFromString<ServerConfigDto>(res.body!!.string()).externalDomain.trim().takeIf { it.isNotEmpty() }
        }
    }.getOrNull()

    /** Origins to try on [lan], best guesses first: [hints] (e.g. the cached local URL), the gateway, then the subnet. */
    fun candidates(lan: LanInfo, hints: List<String> = emptyList()): List<String> {
        val hosts = listOfNotNull(lan.gateway) + Subnet.scanHosts(lan.address, lan.prefix)
        return (hints.map(ImmichApi::normalizeOrigin) + hosts.map { "http://$it:$port" }).distinct().filter(::allows)
    }

    /**
     * Probes [candidates] (~[parallelism] at a time, in order) and returns the first one that is
     * this account's server, cancelling the rest. Without an [expectedFingerprint] nothing is
     * probed, since there'd be no safe way to decide who gets the key. [known] origins (the saved
     * local URL, the gateway) get a longer connect timeout and a retry. [onProgress] gets (done, total).
     */
    suspend fun find(
        candidates: List<String>,
        expectedFingerprint: String?,
        apiKey: String,
        userId: String,
        known: Set<String> = emptySet(),
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): String? = scan(candidates, expectedFingerprint, apiKey, userId, known, onProgress).found

    /** [find], with a [ScanReport] of what answered and why it was rejected. */
    suspend fun scan(
        candidates: List<String>,
        expectedFingerprint: String?,
        apiKey: String,
        userId: String,
        known: Set<String> = emptySet(),
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): ScanReport {
        val allowed = candidates.filter(::allows)
        val expected = Fingerprint.decode(expectedFingerprint)
        if (allowed.isEmpty() || userId.isBlank() || expected == null) return ScanReport(null, 0, emptyList(), emptyMap(), emptyMap())
        val knownNorm = known.map(ImmichApi::normalizeOrigin).toSet()
        val total = allowed.size
        val done = AtomicInteger()
        val gate = Semaphore(parallelism)
        val found = CompletableDeferred<String?>()
        val answered = ConcurrentHashMap<String, Unit>()
        val mismatched = ConcurrentHashMap<String, String>()
        val userFailed = ConcurrentHashMap<String, String>()
        val result = coroutineScope {
            val jobs = allowed.map { origin ->
                launch {
                    gate.withPermit {
                        if (found.isCompleted) return@withPermit
                        val isKnown = origin in knownNorm
                        val up = if (isKnown) pingProbe(origin, knownConnectTimeoutMs, 2).ok else pingProbe(origin, connectTimeoutMs, 1).ok
                        if (!up) return@withPermit
                        answered[origin] = Unit
                        val fp = readFingerprint(origin)
                        val fields = fp.fields
                        if (fields == null) {
                            mismatched[origin] = fp.detail
                            return@withPermit
                        }
                        val diff = Fingerprint.diff(expected, fields)
                        if (diff.isNotEmpty()) {
                            mismatched[origin] = Fingerprint.describeDiff(expected, fields, diff)
                            return@withPermit
                        }
                        val user = userCheck(origin, apiKey, userId)
                        if (user.ok) found.complete(origin) else userFailed[origin] = user.detail
                    }
                    onProgress(done.incrementAndGet(), total)
                }
            }
            launch {
                jobs.joinAll()
                found.complete(null)
            }
            found.await().also { coroutineContext.cancelChildren() }
        }
        return ScanReport(result, total, answered.keys.sorted(), mismatched.toMap(), userFailed.toMap())
    }

    companion object {
        const val IMMICH_PORT = 2283
        internal const val NO_ANSWER = "no answer"

        /** A short reason for an I/O failure, e.g. "no answer: connect timed out after 500 ms". */
        fun describe(e: IOException): String = when (e) {
            is java.net.SocketTimeoutException -> "$NO_ANSWER: timed out (${e.message ?: "timeout"})"
            is java.net.ConnectException -> "$NO_ANSWER: connection refused or unreachable (${e.message ?: "connect failed"})"
            is java.net.NoRouteToHostException -> "$NO_ANSWER: no route to host"
            is java.net.UnknownHostException -> "$NO_ANSWER: unknown host"
            is javax.net.ssl.SSLException -> "TLS failed (is this an http:// address?): ${e.message ?: e.javaClass.simpleName}"
            else -> if (e.message?.contains("timeout", ignoreCase = true) == true || e is java.io.InterruptedIOException) "$NO_ANSWER: timed out (${e.message})"
            else "${e.javaClass.simpleName}: ${e.message ?: "I/O error"}"
        }

        /**
         * The origins to try for a hand-entered local address, best first. No scheme means http
         * (a LAN Immich is plain http on 2283); no port means 2283 first, then the scheme's
         * default; an https address that fails also gets an http try on the same port. Paths,
         * including a trailing `/api`, are dropped.
         */
        fun localCandidates(input: String): List<String> {
            val typed = input.trim().trimEnd('/')
            if (typed.isEmpty()) return emptyList()
            val hasScheme = Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(typed)
            val withScheme = if (hasScheme) typed else "http://$typed"
            val url = withScheme.toHttpUrlOrNull() ?: return listOf(ImmichApi.normalizeOrigin(typed))
            val authority = withScheme.substringAfter("://").substringBefore('/').substringBefore('?')
            val explicitPort = Regex(":\\d+$").containsMatchIn(authority)
            fun origin(scheme: String, port: Int?): String =
                HttpUrl.Builder().scheme(scheme).host(url.host).apply { if (port != null) port(port) }.build().toString().trimEnd('/')
            val out = mutableListOf<String>()
            when {
                explicitPort -> out += origin(url.scheme, url.port)
                hasScheme -> { out += origin(url.scheme, null); out += origin(url.scheme, IMMICH_PORT) }
                else -> { out += origin("http", IMMICH_PORT); out += origin("http", null) }
            }
            if (url.scheme == "https") out += out.map { it.replaceFirst("https://", "http://") }
            return out.distinct()
        }

        /**
         * Sign-in address discovery that doesn't send a LAN IP typed without a scheme through an
         * https attempt first (which on an http-only port at best wastes a TLS round trip and at
         * worst runs into the call timeout): those try http first. Anything else is unchanged.
         */
        suspend fun discoverOrigin(input: String, http: OkHttpClient): String? {
            val typed = input.trim()
            val hasScheme = Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(typed)
            val host = ImmichApi.normalizeOrigin(typed).toHttpUrlOrNull()?.host
            if (!hasScheme && host != null && Subnet.isPrivateHost(host)) {
                ImmichApi.discoverOrigin("http://$typed", http)?.let { return it }
            }
            return ImmichApi.discoverOrigin(typed, http)
        }
    }
}
