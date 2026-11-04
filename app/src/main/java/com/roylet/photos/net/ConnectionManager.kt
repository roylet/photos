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
    /**
     * Saved and in use: the account check passed, but some unauthenticated config field differs
     * from the public address's (see [ConnectionManager.check] for which). Only for hand-entered
     * addresses, which the user chose; the scan never sends the key to such a host.
     */
    VerifiedWithNote,
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
    /** The default network in words ("Mobile data", "Wi-Fi + VPN"), for diagnostics. */
    fun describe(): String = lan()?.label?.ifEmpty { "Wi-Fi" } ?: "No Wi-Fi or ethernet"
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

    private val _check = MutableStateFlow<ConnectionCheck?>(null)
    /** Step-by-step result of the last hand-entered local address check or user-started scan. */
    val check: StateFlow<ConnectionCheck?> = _check.asStateFlow()

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

    /**
     * Saves a hand-entered LAN address (blank clears it) and verifies it as this account's server,
     * recording every step in [check]. A missing scheme means http, a missing port 2283.
     *
     * Unlike the automatic scan, a typed address that answers as Immich with the same *version*
     * is sent the key for the `/users/me` check even if a config/feature field differs from the
     * public address's: the user picked this host and it's an RFC 1918 address, and the id check
     * is what proves it's the same account. The result is then [LocalUrlResult.VerifiedWithNote].
     * A different version (or no Immich at all) never gets the key.
     */
    suspend fun setLocalUrl(input: String?): LocalUrlResult {
        val typed = input?.trim()?.takeIf { it.isNotEmpty() }
        if (typed == null) {
            untrust()
            store.updateServer { it.copy(localUrl = null, localVerified = false, localFingerprint = null) }
            _check.value = null
            refresh()
            return LocalUrlResult.Cleared
        }
        val rec = CheckRecorder("Home network address: $typed") { _check.value = it }
        deviceStep(rec)
        val candidates = LanScanner.localCandidates(typed).filter(scanner::allows)
        if (candidates.isEmpty()) {
            rec.step(STEP_ADDRESS, StepStatus.Fail, "${ImmichApi.normalizeOrigin(typed)} isn't a home-network IP address (192.168.x.x, 10.x.x.x or 172.16-31.x.x)")
            rec.finish(StepStatus.Fail, "Not saved.")
            return LocalUrlResult.NotLanAddress
        }
        rec.step(STEP_ADDRESS, StepStatus.Pass, candidates.first() + if (candidates.size > 1) " (then ${candidates.drop(1).joinToString()})" else "")

        rec.running(STEP_PING, "Waiting for /api/server/ping…")
        var url: String? = null
        val misses = mutableListOf<String>()
        for (c in candidates) {
            val p = scanner.pingProbe(c)
            if (p.ok) {
                url = c
                rec.step(STEP_PING, StepStatus.Pass, "$c: ${p.detail}")
                break
            }
            misses += "$c: ${p.detail}"
        }
        val saved = url ?: candidates.first()
        store.updateServer { if (it.localUrl == saved) it else it.copy(localUrl = saved, localVerified = false, localFingerprint = null) }
        if (url == null) {
            rec.step(STEP_PING, StepStatus.Fail, misses.joinToString("; "))
            rec.finish(StepStatus.Fail, "Saved, not verified: nothing answered as Immich. Check the IP and port, and that the phone can reach it.")
            refresh()
            return LocalUrlResult.Unverified
        }
        val cfg = store.server.value ?: return LocalUrlResult.Unverified.also { rec.finish(StepStatus.Fail, "Signed out.") }

        rec.running(STEP_PUBLIC_FP, "Reading ${cfg.publicUrl}…")
        val fresh = scanner.readFingerprint(cfg.publicUrl)
        fresh.encoded?.let { fp -> store.updateServer { if (it.publicUrl == cfg.publicUrl && it.fingerprint != fp) it.copy(fingerprint = fp) else it } }
        val publicFp = fresh.fields ?: Fingerprint.decode(cfg.fingerprint)
        when {
            fresh.fields != null -> rec.step(STEP_PUBLIC_FP, StepStatus.Pass, "${cfg.publicUrl}: ${describeFp(fresh.fields)}")
            publicFp != null -> rec.step(STEP_PUBLIC_FP, StepStatus.Warn, "Couldn't read it now (${fresh.detail}); using the saved one: ${describeFp(publicFp)}")
            else -> rec.step(STEP_PUBLIC_FP, StepStatus.Warn, "Couldn't read it (${fresh.detail}) and none is saved")
        }

        rec.running(STEP_FP, "Reading $url…")
        val localRead = scanner.readFingerprint(url)
        val localFp = localRead.fields
        if (localFp == null) {
            rec.step(STEP_FP, StepStatus.Fail, "$url: ${localRead.detail}")
            rec.finish(StepStatus.Fail, "Saved, not verified, and not sent your key: it answers ping but not like Immich.")
            refresh()
            return LocalUrlResult.Unverified
        }
        val diff = publicFp?.let { Fingerprint.diff(it, localFp) }
        var note: String? = null
        when {
            publicFp == null -> {
                rec.step(STEP_FP, StepStatus.Warn, "Nothing to compare with (${describeFp(localFp)}). Checking your account because you entered this address.")
                note = "the public address's fingerprint couldn't be read"
            }
            diff!!.isEmpty() -> rec.step(STEP_FP, StepStatus.Pass, "Matches the public address (${localFp.size} fields)")
            diff.any(Fingerprint::isHard) -> {
                rec.step(STEP_FP, StepStatus.Fail, "Different: ${Fingerprint.describeDiff(publicFp, localFp, diff)}")
                rec.finish(StepStatus.Fail, "Saved, not verified, and not sent your key: it runs a different Immich version than your server.")
                refresh()
                return LocalUrlResult.Unverified
            }
            else -> {
                rec.step(STEP_FP, StepStatus.Warn, "Different: ${Fingerprint.describeDiff(publicFp, localFp, diff)}. Checking your account because you entered this address.")
                note = "its settings differ from the public address's (${diff.joinToString()})"
            }
        }

        rec.running(STEP_USER, "GET /api/users/me with your key…")
        val user = scanner.userCheck(url, cfg.apiKey, cfg.userId)
        rec.step(STEP_USER, if (user.ok) StepStatus.Pass else StepStatus.Fail, "GET /api/users/me: ${user.detail}")
        if (!user.ok || store.server.value?.userId != cfg.userId) {
            rec.finish(StepStatus.Fail, "Saved, not verified: this isn't your account on this server.")
            refresh()
            return LocalUrlResult.Unverified
        }
        store.updateServer { if (it.localUrl == url) it.copy(localVerified = true, localFingerprint = localRead.encoded) else it }
        trust(url, netKey())
        refresh()
        return if (note == null) {
            rec.finish(StepStatus.Pass, "Verified: same server and account. In use whenever it answers.")
            LocalUrlResult.Verified
        } else {
            rec.finish(StepStatus.Warn, "Verified by your account, in use whenever it answers. Note: $note.")
            LocalUrlResult.VerifiedWithNote
        }
    }

    private fun deviceStep(rec: CheckRecorder): LanInfo? {
        val lan = env.lan()
        if (lan == null) rec.step(STEP_DEVICE, StepStatus.Warn, "${env.describe()}: no Wi-Fi/ethernet IPv4 address found")
        else rec.step(STEP_DEVICE, StepStatus.Pass, "${lan.label.ifEmpty { "LAN" }}: ${lan.address}/${lan.prefix}, gateway ${lan.gateway ?: "unknown"}")
        return lan
    }

    private fun describeFp(f: Map<String, String>): String {
        val v = listOf("major", "minor", "patch").mapNotNull { f["version.$it"] }.joinToString(".")
        return "Immich v$v, ${f.size} fields"
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

    /** The cached public fingerprint; one in an old format (a hash) counts as none and is re-read. */
    private suspend fun fingerprintOf(cfg: ServerConfig): String? =
        (store.server.value?.fingerprint ?: cfg.fingerprint)?.takeIf { Fingerprint.decode(it) != null } ?: captureFingerprint(cfg.publicUrl)

    /** Is [local] answering and trusted on network [net]? Re-checks the fingerprint (no key) when not yet trusted here. */
    private suspend fun localUsable(cfg: ServerConfig, local: String, net: String): Boolean {
        // On a LAN, one retry: the first packet after Wi-Fi power saving or a cold ARP cache is slow.
        if (!scanner.allows(local) || !scanner.ping(local, localProbeMs, attempts = if (env.lan() != null) 2 else 1)) return false
        if (trustedLocal == local && trustedNet == net) return true
        return verifyLocal(cfg, local, net)
    }

    /**
     * Fingerprint first (no key): it must match the public one, or the one this address showed
     * when it was verified. Only if it matches, and only if this address has never passed
     * before, the key-bearing `/users/me` check (which then needs the public fingerprint). On
     * success, [local] is trusted on [net].
     */
    private suspend fun verifyLocal(cfg: ServerConfig, local: String, net: String): Boolean {
        if (!scanner.allows(local)) return false
        val publicFp = fingerprintOf(cfg)?.let(Fingerprint::decode)
        val verified = cfg.localVerified && cfg.localUrl == local
        val ownFp = cfg.localFingerprint?.takeIf { verified }?.let(Fingerprint::decode)
        if (publicFp == null && ownFp == null) return false
        val got = scanner.readFingerprint(local).fields ?: return false
        val matchesPublic = publicFp != null && Fingerprint.diff(publicFp, got).isEmpty()
        val matchesOwn = ownFp != null && Fingerprint.diff(ownFp, got).isEmpty()
        if (!matchesPublic && !matchesOwn) return false
        if (!verified) {
            if (!matchesPublic || !scanner.isSameServer(local, cfg.apiKey, cfg.userId)) return false
            store.updateServer { if (it.localUrl == local) it.copy(localVerified = true, localFingerprint = Fingerprint.encode(got)) else it }
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

    /**
     * Runs a LAN scan if allowed; returns the URL it found and saved. A [force]d (user-started)
     * scan records its steps in [check]. Public for tests.
     */
    suspend fun scanNow(force: Boolean): String? {
        val cfg = store.server.value ?: return null
        val rec = CheckRecorder("Network scan") { if (force) _check.value = it }
        val lan = deviceStep(rec)
        if (lan == null) {
            if (force) {
                _scan.value = ScanState.NoLan
                rec.finish(StepStatus.Fail, "Not on Wi-Fi or ethernet, so there's no network to scan.")
            }
            return null
        }
        // Signed in by LAN address with no public one learned yet: the "public" URL already is the LAN.
        val publicHost = cfg.publicUrl.toHttpUrlOrNull()?.host
        if (!force && publicHost != null && Subnet.isPrivateHost(publicHost) && cfg.localUrl == null) return null
        val now = clock()
        if (!force && lastScan[lan.key]?.let { now - it < scanInterval } == true) return null
        lastScan[lan.key] = now

        val fp = if (force) captureFingerprint(cfg.publicUrl) ?: fingerprintOf(cfg) else fingerprintOf(cfg)
        val expected = Fingerprint.decode(fp)
        if (fp == null || expected == null) {
            _scan.value = ScanState.NeedsPublic
            rec.step(STEP_PUBLIC_FP, StepStatus.Fail, "Couldn't read it from ${cfg.publicUrl}, and none is saved")
            rec.finish(StepStatus.Fail, "Connect once via the public address first.")
            return null
        }
        rec.step(STEP_PUBLIC_FP, StepStatus.Pass, describeFp(expected))
        val gatewayOrigin = lan.gateway?.let { "http://$it:${scanner.port}" }
        val candidates = scanner.candidates(lan, listOfNotNull(cfg.localUrl))
        val known = listOfNotNull(cfg.localUrl, gatewayOrigin).toSet()
        _scan.value = ScanState.Running(0, candidates.size)
        rec.running(STEP_PING, "Probing ${candidates.size} addresses on port ${scanner.port}…")
        val report = scanner.scan(candidates, fp, cfg.apiKey, cfg.userId, known) { done, total -> _scan.value = ScanState.Running(done, total) }
        recordScan(rec, report, candidates.size)
        val found = report.found
        if (found == null) {
            _scan.value = ScanState.NotFound
            val hint = if (report.mismatched.isNotEmpty()) " If one of the Immich hosts above is yours, enter it as the home network address to check it with your account." else ""
            rec.finish(StepStatus.Fail, "Didn't find your server.$hint")
            return null
        }
        _scan.value = ScanState.Found(found)
        rec.finish(StepStatus.Pass, "Found $found.")
        // Don't save over a different signed-in server if the user switched accounts mid-scan.
        if (store.server.value?.userId == cfg.userId) {
            store.updateServer { it.copy(localUrl = found, localVerified = true, localFingerprint = fp) }
            trust(found, lan.key)
            if (cfg.mode != ConnectionMode.REMOTE) set(found, ConnState.Home)
        }
        return found
    }

    private fun recordScan(rec: CheckRecorder, report: ScanReport, total: Int) {
        val answered = report.answered
        rec.step(
            STEP_PING,
            if (answered.isEmpty()) StepStatus.Fail else StepStatus.Pass,
            if (answered.isEmpty()) "None of $total addresses answered as Immich on port ${scanner.port}"
            else "${answered.size} of $total answered as Immich: ${answered.joinToString()}",
        )
        if (answered.isEmpty()) return
        val fpOk = answered.filter { it !in report.mismatched }
        rec.step(
            STEP_FP,
            if (fpOk.isEmpty()) StepStatus.Fail else StepStatus.Pass,
            buildList {
                if (fpOk.isNotEmpty()) add("Matches: ${fpOk.joinToString()}")
                report.mismatched.forEach { (o, why) -> add("$o differs (not sent your key): $why") }
            }.joinToString("; "),
        )
        val tried = fpOk
        if (tried.isEmpty()) return
        rec.step(
            STEP_USER,
            if (report.found != null) StepStatus.Pass else StepStatus.Fail,
            buildList {
                report.found?.let { add("$it: same user") }
                report.userFailed.forEach { (o, why) -> add("$o: $why") }
            }.joinToString("; ").ifEmpty { "Not checked" },
        )
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
            // A failed connect means no byte of the body was sent, so even a one-shot body (uploads,
            // edit saves) can go to the public address instead of failing the whole transfer.
            val notSent = e is java.net.ConnectException || e is java.net.NoRouteToHostException
            val canRetry = cfg.mode != ConnectionMode.HOME && (req.body?.isOneShot() != true || notSent) && !chain.call().isCanceled()
            if (!canRetry) throw e
            onLocalFailure(cfg.localUrl!!)
            chain.proceed(req.newBuilder().url(req.url.withOrigin(public)).build())
        }
    }

    private companion object {
        const val LOCAL_CONNECT_TIMEOUT_S = 2
        const val STEP_DEVICE = "This device"
        const val STEP_ADDRESS = "Address"
        const val STEP_PING = "Ping"
        const val STEP_PUBLIC_FP = "Public fingerprint"
        const val STEP_FP = "Fingerprint"
        const val STEP_USER = "Account check"
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
