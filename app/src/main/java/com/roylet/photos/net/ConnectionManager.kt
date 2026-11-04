package com.roylet.photos.net

import com.roylet.photos.data.ConnectionMode
import com.roylet.photos.data.ServerConfig
import com.roylet.photos.immich.ImmichApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Which address the app is talking to right now, for the header chip. */
enum class ConnState { Home, Remote, Offline, Checking }

/** Progress of a LAN scan, for Settings. */
sealed interface ScanState {
    data object Idle : ScanState
    data class Running(val done: Int, val total: Int) : ScanState
    data class Found(val url: String) : ScanState
    data object NotFound : ScanState
    /** Not on Wi-Fi or ethernet, so there's no LAN to scan. */
    data object NoLan : ScanState
    /** No server fingerprint yet (the public URL hasn't been reached), so no host can be trusted. */
    data object NeedsPublic : ScanState
}

/** Outcome of saving a hand-entered LAN address. */
enum class LocalUrlResult {
    /** Saved, matched the fingerprint and the account: in use when it answers. */
    Verified,
    /** Saved but not (yet) verified; it gets no API key until it is. */
    Unverified,
    /** Not saved: only RFC 1918 / link-local IPv4 addresses are accepted. */
    NotLanAddress,
    Cleared,
}

/** Where the saved server lives; [com.roylet.photos.data.Settings] in the app, a fake in tests. */
interface ServerStore {
    val server: StateFlow<ServerConfig?>
    fun updateServer(change: (ServerConfig) -> ServerConfig)
}

/** What the connection manager needs to know about the device's network. */
interface NetworkEnvironment {
    /** Any usable network at all (not necessarily validated: a LAN-only Wi-Fi counts). */
    fun online(): Boolean
    /** The Wi-Fi/ethernet network we're on, or null on mobile data / VPN / nothing. */
    fun lan(): LanInfo?
    /** Registers for default-network changes and app foregrounding. */
    fun observe(onNetworkChange: () -> Unit, onForeground: () -> Unit) {}
}

/**
 * Keeps the app pointed at the fastest working address for the signed-in server.
 *
 * - [activeUrl] is the LAN address (`localUrl`) when it answers within ~800 ms *and is trusted on
 *   the current network*, otherwise the public one. Re-evaluated on network changes and app
 *   foreground (see [start]).
 * - Trust: the API key only goes to the public URL, or to a `localUrl` that has passed the full
 *   check once (unauthenticated fingerprint match, then `/users/me` id) and has re-matched the
 *   fingerprint, without the key, since the last network change. Only RFC 1918 / link-local
 *   addresses are scanned or accepted as `localUrl`.
 * - [interceptor] rewrites every request aimed at the server (either address) to [activeUrl],
 *   so Coil, ExoPlayer, uploads and the API switch together. If the LAN address stops answering
 *   mid-request, it flips to public and retries that request once.
 * - When on Wi-Fi without a working `localUrl`, it scans the LAN in the background (at most once
 *   per network per [scanInterval]). If the user signed in with a LAN address, the public one is
 *   learned from the server's `externalDomain`.
 */
class ConnectionManager(
    private val store: ServerStore,
    private val scanner: LanScanner,
    private val env: NetworkEnvironment,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val localProbeMs: Long = 800,
    private val scanInterval: Long = TimeUnit.MINUTES.toMillis(10),
    private val debounceMs: Long = 300,
    private val reprobeAfterFailureMs: Long = TimeUnit.SECONDS.toMillis(30),
) {
    private val _activeUrl = MutableStateFlow(store.server.value?.publicUrl.orEmpty())
    val activeUrl: StateFlow<String> = _activeUrl.asStateFlow()

    private val _state = MutableStateFlow(if (store.server.value == null) ConnState.Offline else ConnState.Checking)
    val state: StateFlow<ConnState> = _state.asStateFlow()

    private val _scan = MutableStateFlow<ScanState>(ScanState.Idle)
    val scan: StateFlow<ScanState> = _scan.asStateFlow()

    private val lock = Any()
    private var evalJob: Job? = null
    private var scanJob: Job? = null
    private var reprobeJob: Job? = null
    private val lastScan = ConcurrentHashMap<String, Long>()
    private var learnedPublicFor: String? = null

    /** The local origin trusted with the key on network [trustedNet]; cleared on any network change. */
    @Volatile private var trustedLocal: String? = null
    @Volatile private var trustedNet: String? = null

    /** Hooks up network/foreground callbacks and settings changes. Call once, from the app. */
    fun start() {
        env.observe(onNetworkChange = ::onNetworkChange, onForeground = ::refresh)
        scope.launch {
            store.server.map { it?.let { c -> Triple(c.publicUrl, c.localUrl, c.mode) } }
                .distinctUntilChanged()
                .drop(1)
                .collect { refresh() }
        }
        refresh()
    }

    /** Re-evaluates which address to use (debounced; cancels an evaluation in flight). */
    fun refresh() {
        synchronized(lock) {
            evalJob?.cancel()
            evalJob = scope.launch {
                delay(debounceMs)
                evaluate()
            }
        }
    }

    /**
     * The default network changed: stop trusting the LAN address at once (a different network
     * can have a different device at the same IP) and re-check it, without the key, before reuse.
     */
    fun onNetworkChange() {
        untrust()
        refresh()
    }

    private fun untrust() {
        synchronized(lock) {
            val wasLocal = trustedLocal != null && _activeUrl.value == trustedLocal
            trustedLocal = null
            trustedNet = null
            val cfg = store.server.value
            if (wasLocal && cfg != null) set(cfg.publicUrl, ConnState.Checking)
        }
    }

    private fun trust(local: String, net: String) {
        trustedLocal = local
        trustedNet = net
    }

    private fun netKey(): String = env.lan()?.key ?: "no-lan"

    /** Scans the LAN now, ignoring the rate limit. */
    fun rescan() {
        launchScan(force = true)
    }

    /** Call right after signing in: picks an address, learns the public URL and looks for the LAN server. */
    fun onSignedIn() {
        learnedPublicFor = null
        untrust()
        refresh()
        launchScan(force = true)
    }

    /** Saves a hand-entered LAN address (blank clears it) and verifies it as this account's server. */
    suspend fun setLocalUrl(input: String?): LocalUrlResult {
        val url = input?.trim()?.takeIf { it.isNotEmpty() }?.let(ImmichApi::normalizeOrigin)
        if (url == null) {
            untrust()
            store.updateServer { it.copy(localUrl = null, localVerified = false) }
            refresh()
            return LocalUrlResult.Cleared
        }
        if (!scanner.allows(url)) return LocalUrlResult.NotLanAddress
        store.updateServer { it.copy(localUrl = url, localVerified = it.localVerified && it.localUrl == url) }
        val cfg = store.server.value ?: return LocalUrlResult.Unverified
        val ok = verifyLocal(cfg.copy(localVerified = false), url, netKey())
        refresh()
        return if (ok) LocalUrlResult.Verified else LocalUrlResult.Unverified
    }

    /** Saves a hand-entered public address and returns whether it was verified as this account's server. */
    suspend fun setPublicUrl(input: String): Boolean {
        val url = ImmichApi.normalizeOrigin(input)
        store.updateServer { it.copy(publicUrl = url) }
        val cfg = store.server.value ?: return false
        refresh()
        val ok = scanner.isSameServer(url, cfg.apiKey, cfg.userId)
        if (ok) captureFingerprint(url)
        return ok
    }

    fun setMode(mode: ConnectionMode) {
        store.updateServer { it.copy(mode = mode) }
        refresh()
    }

    /** Decides [activeUrl] and [state] now. Public for tests; the app goes through [refresh]. */
    suspend fun evaluate() {
        val cfg = store.server.value
        if (cfg == null) {
            set("", ConnState.Offline)
            return
        }
        if (!env.online()) {
            set(cfg.publicUrl, ConnState.Offline)
            return
        }
        val net = netKey()
        if (trustedNet != net) untrust()
        if (_state.value == ConnState.Offline) _state.value = ConnState.Checking
        val local = cfg.localUrl
        var localWorks = false
        when (cfg.mode) {
            ConnectionMode.REMOTE -> useRemote(cfg)
            ConnectionMode.HOME -> {
                localWorks = local != null && localUsable(cfg, local, net)
                // Forced home, but an unverified LAN address never gets the key: show offline.
                if (localWorks) set(local!!, ConnState.Home) else set(cfg.publicUrl, ConnState.Offline)
            }
            ConnectionMode.AUTO -> {
                localWorks = local != null && localUsable(cfg, local, net)
                if (localWorks) set(local!!, ConnState.Home) else useRemote(cfg)
            }
        }
        maybeLearnPublicUrl(cfg)
        if (cfg.mode == ConnectionMode.AUTO && !localWorks) launchScan(force = false)
    }

    private suspend fun useRemote(cfg: ServerConfig) {
        _activeUrl.value = cfg.publicUrl
        if (scanner.ping(cfg.publicUrl)) {
            set(cfg.publicUrl, ConnState.Remote)
            captureFingerprint(cfg.publicUrl)
        } else {
            set(cfg.publicUrl, ConnState.Offline)
        }
    }

    /** Refreshes the cached fingerprint from the public URL (it changes when the server is upgraded). */
    private suspend fun captureFingerprint(publicUrl: String): String? {
        val fp = scanner.fingerprint(publicUrl) ?: return null
        store.updateServer { if (it.publicUrl == publicUrl && it.fingerprint != fp) it.copy(fingerprint = fp) else it }
        return fp
    }

    private suspend fun fingerprintOf(cfg: ServerConfig): String? =
        store.server.value?.fingerprint ?: cfg.fingerprint ?: captureFingerprint(cfg.publicUrl)

    /** Is [local] answering and trusted on network [net]? Re-checks the fingerprint (no key) when not yet trusted here. */
    private suspend fun localUsable(cfg: ServerConfig, local: String, net: String): Boolean {
        if (!scanner.allows(local) || !scanner.ping(local, localProbeMs)) return false
        if (trustedLocal == local && trustedNet == net) return true
        return verifyLocal(cfg, local, net)
    }

    /**
     * Fingerprint first (no key). Only if it matches, and only if this address has never passed
     * before, the key-bearing `/users/me` check. On success, [local] is trusted on [net].
     */
    private suspend fun verifyLocal(cfg: ServerConfig, local: String, net: String): Boolean {
        if (!scanner.allows(local)) return false
        val fp = fingerprintOf(cfg) ?: return false
        if (!scanner.matchesFingerprint(local, fp)) return false
        if (!cfg.localVerified) {
            if (!scanner.isSameServer(local, cfg.apiKey, cfg.userId)) return false
            store.updateServer { if (it.localUrl == local) it.copy(localVerified = true) else it }
        }
        trust(local, net)
        return true
    }

    private fun set(url: String, state: ConnState) {
        _activeUrl.value = url
        _state.value = state
    }

    /** Signed in with a LAN address? Ask the server for its public one. */
    private suspend fun maybeLearnPublicUrl(cfg: ServerConfig) {
        val host = cfg.publicUrl.toHttpUrlOrNull()?.host ?: return
        if (!Subnet.isPrivateHost(host) || learnedPublicFor == cfg.publicUrl || _state.value == ConnState.Offline) return
        learnedPublicFor = cfg.publicUrl
        val external = scanner.externalDomain(cfg.publicUrl)?.let(ImmichApi::normalizeOrigin) ?: return
        if (external == cfg.publicUrl || external.toHttpUrlOrNull() == null) return
        val oldPublic = cfg.publicUrl
        store.updateServer {
            // The address the user signed in with is proven (the key came from it), so it can
            // become the local URL, as long as it's an address we'd accept as one.
            if (it.localUrl == null && scanner.allows(oldPublic)) it.copy(publicUrl = external, localUrl = oldPublic, localVerified = true)
            else it.copy(publicUrl = external)
        }
        refresh()
    }

    private fun launchScan(force: Boolean) {
        synchronized(lock) {
            if (scanJob?.isActive == true) return
            scanJob = scope.launch { scanNow(force) }
        }
    }

    /** Runs a LAN scan if allowed; returns the URL it found and saved. Public for tests. */
    suspend fun scanNow(force: Boolean): String? {
        val cfg = store.server.value ?: return null
        val lan = env.lan()
        if (lan == null) {
            if (force) _scan.value = ScanState.NoLan
            return null
        }
        // Signed in by LAN address with no public one learned yet: the "public" URL already is the LAN.
        val publicHost = cfg.publicUrl.toHttpUrlOrNull()?.host
        if (!force && publicHost != null && Subnet.isPrivateHost(publicHost) && cfg.localUrl == null) return null
        val now = clock()
        if (!force && lastScan[lan.key]?.let { now - it < scanInterval } == true) return null
        lastScan[lan.key] = now

        val fp = fingerprintOf(cfg)
        if (fp == null) {
            _scan.value = ScanState.NeedsPublic
            return null
        }
        val candidates = scanner.candidates(lan, listOfNotNull(cfg.localUrl))
        _scan.value = ScanState.Running(0, candidates.size)
        val found = scanner.find(candidates, fp, cfg.apiKey, cfg.userId) { done, total -> _scan.value = ScanState.Running(done, total) }
        if (found == null) {
            _scan.value = ScanState.NotFound
            return null
        }
        _scan.value = ScanState.Found(found)
        // Don't save over a different signed-in server if the user switched accounts mid-scan.
        if (store.server.value?.userId == cfg.userId) {
            store.updateServer { it.copy(localUrl = found, localVerified = true) }
            trust(found, lan.key)
            if (cfg.mode != ConnectionMode.REMOTE) set(found, ConnState.Home)
        }
        return found
    }

    /** The LAN address failed mid-request: use public until the next re-probe. */
    internal fun onLocalFailure(localUrl: String) {
        val cfg = store.server.value ?: return
        if (_activeUrl.value == localUrl) set(cfg.publicUrl, ConnState.Remote)
        synchronized(lock) {
            if (reprobeJob?.isActive == true) return
            reprobeJob = scope.launch {
                delay(reprobeAfterFailureMs)
                refresh()
            }
        }
    }

    val signedIn: Boolean get() = store.server.value != null

    /**
     * The API key to send to [url], or null if it must not get one: only the public address, or
     * the local address while it's trusted on the current network.
     */
    fun apiKeyFor(url: HttpUrl): String? {
        val cfg = store.server.value ?: return null
        val public = cfg.publicUrl.toHttpUrlOrNull()
        if (public != null && public.host == url.host && public.port == url.port) return cfg.apiKey
        val local = trustedLocal?.takeIf { it == cfg.localUrl && cfg.localVerified }?.toHttpUrlOrNull() ?: return null
        return if (local.host == url.host && local.port == url.port) cfg.apiKey else null
    }

    /**
     * Application interceptor for the shared client: rewrites scheme/host/port of requests aimed
     * at the server (its public or local address) to [activeUrl]. Must run before the API key
     * interceptor, which then sees the final host. On an I/O failure against the LAN address it
     * switches to public and retries once (unless the body can't be replayed, or HOME is forced).
     */
    val interceptor = Interceptor { chain ->
        val req = chain.request()
        val cfg = store.server.value ?: return@Interceptor chain.proceed(req)
        val public = cfg.publicUrl.toHttpUrlOrNull() ?: return@Interceptor chain.proceed(req)
        val local = cfg.localUrl?.toHttpUrlOrNull()
        if (!req.url.sameOrigin(public) && (local == null || !req.url.sameOrigin(local))) return@Interceptor chain.proceed(req)

        // Only a trusted local address is ever a target; anything else (stale, untrusted) goes public.
        val trusted = trustedLocal?.takeIf { it == cfg.localUrl }?.toHttpUrlOrNull()
        val active = _activeUrl.value.toHttpUrlOrNull()?.takeIf { it.sameOrigin(public) || (trusted != null && it.sameOrigin(trusted)) } ?: public
        val target = req.url.withOrigin(active)
        val toLocal = trusted != null && active.sameOrigin(trusted) && !trusted.sameOrigin(public)
        if (!toLocal) return@Interceptor chain.proceed(req.newBuilder().url(target).build())

        try {
            chain.withConnectTimeout(LOCAL_CONNECT_TIMEOUT_S, TimeUnit.SECONDS).proceed(req.newBuilder().url(target).build())
        } catch (e: IOException) {
            val canRetry = cfg.mode != ConnectionMode.HOME && req.body?.isOneShot() != true && !chain.call().isCanceled()
            if (!canRetry) throw e
            onLocalFailure(cfg.localUrl!!)
            chain.proceed(req.newBuilder().url(req.url.withOrigin(public)).build())
        }
    }

    private companion object {
        const val LOCAL_CONNECT_TIMEOUT_S = 2
    }
}

private fun HttpUrl.sameOrigin(other: HttpUrl) = host == other.host && port == other.port && scheme == other.scheme

private fun HttpUrl.withOrigin(origin: HttpUrl): HttpUrl =
    if (sameOrigin(origin)) this else newBuilder().scheme(origin.scheme).host(origin.host).port(origin.port).build()

/** Adds the API key as `x-api-key` to requests for the server's trusted addresses only. */
class ApiKeyInterceptor(private val connection: ConnectionManager) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        if (req.header("x-api-key") != null || req.header("Authorization") != null) return chain.proceed(req)
        val key = connection.apiKeyFor(req.url) ?: return chain.proceed(req)
        return chain.proceed(req.newBuilder().header("x-api-key", key).build())
    }
}

/**
 * Network interceptor (sees every hop, including redirects, which OkHttp follows with custom
 * headers intact): strips the API key from anything that isn't a trusted server address.
 */
class ApiKeyGuard(private val connection: ConnectionManager) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        if (!connection.signedIn || req.header("x-api-key") == null || connection.apiKeyFor(req.url) != null) return chain.proceed(req)
        return chain.proceed(req.newBuilder().removeHeader("x-api-key").build())
    }
}
