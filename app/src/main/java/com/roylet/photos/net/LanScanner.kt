package com.roylet.photos.net

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.MessageDigest
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The device's IPv4 position on the current Wi-Fi/ethernet network. [key] identifies the network
 * (subnet + gateway) well enough to rate-limit scans per network across reconnects.
 */
data class LanInfo(val address: String, val prefix: Int, val gateway: String? = null) {
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

/**
 * Reads the active network's IPv4 address, prefix and gateway from [ConnectivityManager]
 * link properties. Only Wi-Fi and ethernet count (never mobile data or a VPN), and this needs no
 * location permission, unlike WifiManager's connection info.
 */
fun ConnectivityManager.lanInfo(): LanInfo? {
    val network = activeNetwork ?: return null
    val caps = getNetworkCapabilities(network) ?: return null
    val lanTransport = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    if (!lanTransport || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return null
    val props = getLinkProperties(network) ?: return null
    val link = props.linkAddresses.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress } ?: return null
    val gateway = props.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
    return LanInfo(link.address.hostAddress ?: return null, link.prefixLength, gateway?.takeIf { it != "0.0.0.0" })
}

@Serializable private data class Pong(val res: String = "")
@Serializable private data class ServerConfigDto(val externalDomain: String = "")

/**
 * Finds this account's Immich server on the local network, in stages so the API key only ever
 * goes to a host that already looks like our server:
 * 1. ping `/api/server/ping` with a tight connect timeout (dead addresses cost ~300 ms);
 * 2. compare an unauthenticated [fingerprint] (version, config, features) with the one cached
 *    from the public URL; hosts that don't match never see the key;
 * 3. only then `GET /api/users/me` with the key, which must return the same user id.
 *
 * Only [allowHost] addresses are probed at all (RFC 1918 / link-local by default).
 *
 * Uses its own short-timeout clients with their own dispatcher, so a scan never queues behind (or
 * starves) the app's image loading, and with redirects off so the key can't be bounced elsewhere.
 * [base] must not carry the app's URL-rewriting interceptor.
 */
class LanScanner(
    base: OkHttpClient = ImmichApi.defaultClient,
    val port: Int = IMMICH_PORT,
    private val parallelism: Int = 32,
    connectTimeoutMs: Long = 300,
    private val allowHost: (String) -> Boolean = Subnet::isLanAddress,
) {
    /** True if [origin] is an address we'd accept as a local URL. */
    fun allows(origin: String): Boolean = ImmichApi.normalizeOrigin(origin).toHttpUrlOrNull()?.host?.let(allowHost) == true

    private val probeHttp = base.newBuilder()
        .dispatcher(Dispatcher().apply { maxRequests = parallelism * 2; maxRequestsPerHost = 4 })
        .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    /** True if [origin] answers as an Immich server within [timeoutMs]. */
    suspend fun ping(origin: String, timeoutMs: Long? = null): Boolean = runCatching {
        val client = if (timeoutMs == null) probeHttp else probeHttp.newBuilder()
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS).callTimeout(timeoutMs * 2, TimeUnit.MILLISECONDS).build()
        client.newCall(Request.Builder().url("${ImmichApi.normalizeOrigin(origin)}/api/server/ping").build()).await().use { res ->
            res.isSuccessful && ImmichApi.json.decodeFromString<Pong>(res.body!!.string()).res == "pong"
        }
    }.getOrDefault(false)

    /** True only if [origin] accepts [apiKey] and says it belongs to [userId]. */
    suspend fun isSameServer(origin: String, apiKey: String, userId: String): Boolean {
        if (userId.isBlank()) return false
        return runCatching {
            val req = Request.Builder().url("${ImmichApi.normalizeOrigin(origin)}/api/users/me").header("x-api-key", apiKey).build()
            probeHttp.newCall(req).await().use { res ->
                res.isSuccessful && ImmichApi.json.decodeFromString<UserMe>(res.body!!.string()).id == userId
            }
        }.getOrDefault(false)
    }

    /**
     * A hash of what the server says about itself without authentication: `/server/version`,
     * `/server/config` and `/server/features`, canonicalised (sorted keys, volatile fields
     * dropped). Null if any of them fails. Not a secret (anyone who can reach the public URL can
     * copy it), but it means a random device on the Wi-Fi has to be impersonating *this* server
     * before it's offered the key.
     */
    suspend fun fingerprint(origin: String): String? = runCatching {
        val base = "${ImmichApi.normalizeOrigin(origin)}/api/server"
        val parts = FINGERPRINT_ENDPOINTS.map { path ->
            probeHttp.newCall(Request.Builder().url("$base/$path").build()).await().use { res ->
                if (!res.isSuccessful) return@runCatching null
                canonical(ImmichApi.json.parseToJsonElement(res.body!!.string()))
            }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(parts.joinToString("\n").toByteArray())
        digest.joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /** True if [origin] answers with exactly [expected] as its fingerprint. Never sends the key. */
    suspend fun matchesFingerprint(origin: String, expected: String?): Boolean = expected != null && fingerprint(origin) == expected

    /** The server's configured public address (Administration → Server → External domain), if any. */
    suspend fun externalDomain(origin: String): String? = runCatching {
        probeHttp.newCall(Request.Builder().url("${ImmichApi.normalizeOrigin(origin)}/api/server/config").build()).await().use { res ->
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
     * probed, since there'd be no safe way to decide who gets the key. [onProgress] gets (done, total).
     */
    suspend fun find(
        candidates: List<String>,
        expectedFingerprint: String?,
        apiKey: String,
        userId: String,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): String? {
        val allowed = candidates.filter(::allows)
        if (allowed.isEmpty() || userId.isBlank() || expectedFingerprint == null) return null
        return findAllowed(allowed, expectedFingerprint, apiKey, userId, onProgress)
    }

    private suspend fun findAllowed(
        candidates: List<String>,
        expectedFingerprint: String,
        apiKey: String,
        userId: String,
        onProgress: (Int, Int) -> Unit,
    ): String? {
        val total = candidates.size
        val done = AtomicInteger()
        val gate = Semaphore(parallelism)
        val found = CompletableDeferred<String?>()
        return coroutineScope {
            val jobs = candidates.map { origin ->
                launch {
                    gate.withPermit {
                        if (!found.isCompleted && ping(origin) && matchesFingerprint(origin, expectedFingerprint) &&
                            isSameServer(origin, apiKey, userId)
                        ) found.complete(origin)
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
    }

    companion object {
        const val IMMICH_PORT = 2283

        /** Unauthenticated endpoints (no `security` in the OpenAPI spec) that describe the server. */
        val FINGERPRINT_ENDPOINTS = listOf("version", "config", "features")

        /** Fields that change without the server changing identity. */
        private val VOLATILE = setOf("maintenanceMode")

        internal fun canonical(e: JsonElement): String = when (e) {
            is JsonObject -> e.entries.filter { it.key !in VOLATILE }.sortedBy { it.key }
                .joinToString(",", "{", "}") { (k, v) -> "\"$k\":${canonical(v)}" }
            is kotlinx.serialization.json.JsonArray -> e.joinToString(",", "[", "]") { canonical(it) }
            else -> e.toString()
        }
    }
}
