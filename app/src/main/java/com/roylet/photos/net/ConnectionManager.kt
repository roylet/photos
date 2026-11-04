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
    /** Calls [onChange] whenever the default network changes or the app comes to the foreground. */
    fun observe(onChange: () -> Unit) {}
}

/**
 * Keeps the app pointed at the fastest working address for the signed-in server.
 *
 * - [activeUrl] is the LAN address (`localUrl`) when it answers within ~800 ms, otherwise the
 *   public one. It's re-evaluated on network changes and app foreground (see [start]).
 * - [interceptor] rewrites every request aimed at the server (either address) to [activeUrl],
 *   so Coil, ExoPlayer, uploads and the API switch together. If the LAN address stops answering
 *   mid-request, it flips to public and retries that request once.
 * - When on Wi-Fi without a working `localUrl`, it scans the LAN in the background (at most once
 *   per network per [scanInterval]) and saves what it finds. If the user signed in with a LAN
 *   address, the public one is learned from the server's `externalDomain`.
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

    /** Hooks up network/foreground callbacks and settings changes. Call once, from the app. */
    fun start() {
        env.observe(::refresh)
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

    /** Scans the LAN now, ignoring the rate limit. */
    fun rescan() {
        launchScan(force = true)
    }

    /** Call right after signing in: picks an address, learns the public URL and looks for the LAN server. */
    fun onSignedIn() {
        learnedPublicFor = null
        refresh()
        launchScan(force = true)
    }

    /**
     * Saves a hand-entered LAN address (blank clears it) and returns whether it was verified as
     * this account's server; null when cleared.
     */
    suspend fun setLocalUrl(input: String?): Boolean? {
        val url = input?.trim()?.takeIf { it.isNotEmpty() }?.let(ImmichApi::normalizeOrigin)
        store.updateServer { it.copy(localUrl = url) }
        val cfg = store.server.value ?: return null
        refresh()
        return url?.let { scanner.isSameServer(it, cfg.apiKey, cfg.userId) }
    }

    /** Saves a hand-entered public address and returns whether it was verified as this account's server. */
    suspend fun setPublicUrl(input: String): Boolean {
        val url = ImmichApi.normalizeOrigin(input)
        store.updateServer { it.copy(publicUrl = url) }
        val cfg = store.server.value ?: return false
        refresh()
        return scanner.isSameServer(url, cfg.apiKey, cfg.userId)
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
            set(if (cfg.mode == ConnectionMode.HOME) cfg.localUrl ?: cfg.publicUrl else cfg.publicUrl, ConnState.Offline)
            return
        }
        if (_state.value == ConnState.Offline) _state.value = ConnState.Checking
        val local = cfg.localUrl
        var localWorks = false
        when (cfg.mode) {
            ConnectionMode.REMOTE -> setReachable(cfg.publicUrl, ConnState.Remote)
            ConnectionMode.HOME -> if (local != null) setReachable(local, ConnState.Home) else setReachable(cfg.publicUrl, ConnState.Remote)
            ConnectionMode.AUTO -> {
                localWorks = local != null && scanner.ping(local, localProbeMs)
                if (localWorks) set(local!!, ConnState.Home) else setReachable(cfg.publicUrl, ConnState.Remote)
            }
        }
        maybeLearnPublicUrl(cfg)
        if (cfg.mode == ConnectionMode.AUTO && !localWorks) launchScan(force = false)
    }

    private suspend fun setReachable(url: String, ifUp: ConnState) {
        _activeUrl.value = url
        set(url, if (scanner.ping(url)) ifUp else ConnState.Offline)
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
        store.updateServer { it.copy(publicUrl = external, localUrl = it.localUrl ?: it.publicUrl) }
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

        val candidates = scanner.candidates(lan, listOfNotNull(cfg.localUrl))
        _scan.value = ScanState.Running(0, candidates.size)
        val found = scanner.find(candidates, cfg.apiKey, cfg.userId) { done, total -> _scan.value = ScanState.Running(done, total) }
        if (found == null) {
            _scan.value = ScanState.NotFound
            return null
        }
        _scan.value = ScanState.Found(found)
        // Don't save over a different signed-in server if the user switched accounts mid-scan.
        if (store.server.value?.userId == cfg.userId) {
            store.updateServer { it.copy(localUrl = found) }
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

        // A stale activeUrl (e.g. from before the settings changed) falls back to public.
        val active = _activeUrl.value.toHttpUrlOrNull()?.takeIf { it.sameOrigin(public) || (local != null && it.sameOrigin(local)) } ?: public
        val target = req.url.withOrigin(active)
        val toLocal = local != null && active.sameOrigin(local) && !local.sameOrigin(public)
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

/** True if [url] points at the signed-in server's public or local address. */
fun isServerHost(url: HttpUrl, cfg: ServerConfig?): Boolean {
    if (cfg == null) return false
    return listOfNotNull(cfg.publicUrl, cfg.localUrl).mapNotNull { it.toHttpUrlOrNull() }.any { it.host == url.host && it.port == url.port }
}

/** Adds the API key as `x-api-key` to requests for the signed-in server only. */
class ApiKeyInterceptor(private val config: () -> ServerConfig?) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val cfg = config()
        if (cfg == null || req.header("x-api-key") != null || req.header("Authorization") != null || !isServerHost(req.url, cfg)) return chain.proceed(req)
        return chain.proceed(req.newBuilder().header("x-api-key", cfg.apiKey).build())
    }
}

/**
 * Network interceptor (sees every hop, including redirects, which OkHttp follows with custom
 * headers intact): strips the API key from anything not going to the signed-in server.
 */
class ApiKeyGuard(private val config: () -> ServerConfig?) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val cfg = config()
        if (cfg == null || req.header("x-api-key") == null || isServerHost(req.url, cfg)) return chain.proceed(req)
        return chain.proceed(req.newBuilder().removeHeader("x-api-key").build())
    }
}
