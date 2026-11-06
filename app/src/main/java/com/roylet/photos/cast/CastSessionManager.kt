package com.roylet.photos.cast

import com.roylet.photos.immich.awaitUse

import com.roylet.photos.immich.await
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Instant

/** A short-lived Immich session for the TV: its token goes in `?sessionKey=` URLs. */
data class CastKey(val id: String, val token: String, val expiresAt: Long) {
    override fun toString() = "CastKey(id=$id, expiresAt=$expiresAt, token=<redacted>)"
}

/**
 * The ids of child sessions that may still be live on the server, kept across process death so
 * an app killed mid-cast can still delete them ([CastSessionManager.cleanUpStale]). Ids only:
 * they aren't credentials (the token never leaves memory).
 */
interface CastKeyLedger {
    fun ids(): Set<String>
    fun replace(ids: Set<String>)

    /** In memory only (tests, previews). */
    class InMemory : CastKeyLedger {
        private var held: Set<String> = emptySet()
        @Synchronized override fun ids() = held
        @Synchronized override fun replace(ids: Set<String>) { held = ids }
    }
}

sealed interface KeyResult {
    data class Ok(val key: CastKey) : KeyResult
    /** No session token (API-key sign-in) or the server refused it: "Sign in once to cast". */
    data object NeedsSignIn : KeyResult
    data class Failed(val message: String) : KeyResult
}

/**
 * Child sessions for casting, the way Immich's own apps do it (mobile `gcast.service.dart`, web
 * `cast-manager.svelte.ts`): `POST /api/sessions` with the stored login session as the Bearer
 * token creates a session that expires on its own; the Chromecast fetches
 * `...?sessionKey=<token>` URLs with it, straight from the server.
 *
 * - One key is reused until fewer than [refreshBeforeMs] remain, then a new one is made for the
 *   next item (a video already playing keeps reading with its own key until that expires).
 * - [end] deletes it when the cast session ends (Immich's apps leave theirs to expire). Every id
 *   made is written to [ledger] first, so one left behind by a killed app is deleted by
 *   [cleanUpStale] at the next start (or the next [end]).
 * - A 401/403 means the stored login session was revoked or expired: [needsSignIn] turns on and
 *   the UI offers "Sign in once to cast".
 *
 * The token is a full-account credential while it lives: never log it ([CastKey.toString] hides it).
 */
class CastSessionManager(
    private val http: OkHttpClient,
    /** The server's base URL for API calls (the saved public URL; the shared client may route it over the LAN). */
    private val baseUrl: () -> String?,
    /** The stored login session token ([com.roylet.photos.AppContainer.serverSessionToken]). */
    private val parentToken: () -> String?,
    private val now: () -> Long = System::currentTimeMillis,
    private val durationSeconds: Int = DEFAULT_DURATION_S,
    private val refreshBeforeMs: Long = REFRESH_BEFORE_MS,
    private val ledger: CastKeyLedger = CastKeyLedger.InMemory(),
) {
    private val lock = Mutex()
    private var current: CastKey? = null

    private val _needsSignIn = MutableStateFlow(false)
    val needsSignIn: StateFlow<Boolean> = _needsSignIn.asStateFlow()

    /** A key with more than [refreshBeforeMs] left: the current one, or a new child session. */
    suspend fun key(): KeyResult = lock.withLock {
        val held = current
        if (held != null && held.expiresAt - now() > refreshBeforeMs) return KeyResult.Ok(held)
        val base = baseUrl() ?: return KeyResult.Failed("No Immich server")
        val parent = parentToken()
        if (parent == null) {
            _needsSignIn.value = true
            return KeyResult.NeedsSignIn
        }
        val result = create(base, parent)
        if (result is KeyResult.Ok) {
            current = result.key
            ledger.replace((ledger.ids() + result.key.id).toList().takeLast(MAX_LEDGER).toSet())
            _needsSignIn.value = false
            // The one it replaces expires within seconds on its own; deleting it early could cut
            // off a stream the TV is still reading with it.
        }
        result
    }

    /** The cast session ended: delete the child session (and any a killed app left behind). */
    suspend fun end() = lock.withLock {
        val held = current
        current = null
        deleteAll(listOfNotNull(held?.id) + ledger.ids())
    }

    /**
     * At start-up, with no cast session: delete child sessions a killed app left in [ledger].
     * Not while a key is held (a session is live).
     */
    suspend fun cleanUpStale() = lock.withLock {
        if (current != null) return@withLock
        deleteAll(ledger.ids())
    }

    private suspend fun deleteAll(ids: Collection<String>) {
        val distinct = ids.distinct()
        if (distinct.isEmpty()) return
        // They expire on their own within [durationSeconds] even if these fail.
        ledger.replace(emptySet())
        val base = baseUrl() ?: return
        val parent = parentToken() ?: return
        for (id in distinct) runCatching { delete(base, parent, id) }
    }

    /** After "Sign in once to cast" stored a new login session. */
    fun onSignedIn() {
        _needsSignIn.value = false
    }

    private suspend fun create(base: String, parent: String): KeyResult {
        val body = json.encodeToString(CreateRequest.serializer(), CreateRequest(durationSeconds, DEVICE_TYPE, DEVICE_OS))
        val req = Request.Builder().url("$base/api/sessions")
            .header("Authorization", "Bearer $parent")
            .post(body.toRequestBody(JSON))
            .build()
        return try {
            http.newCall(req).awaitUse { res ->
                when {
                    res.code == 401 || res.code == 403 -> {
                        _needsSignIn.value = true
                        KeyResult.NeedsSignIn
                    }
                    !res.isSuccessful -> KeyResult.Failed("The server said ${res.code} when starting a cast session")
                    else -> {
                        val dto = json.decodeFromString(CreateResponse.serializer(), res.body?.string().orEmpty())
                        val expires = dto.expiresAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
                            ?: (now() + durationSeconds * 1000L)
                        KeyResult.Ok(CastKey(dto.id, dto.token, expires))
                    }
                }
            }
        } catch (e: IOException) {
            KeyResult.Failed("Couldn't reach your Immich server to start casting")
        } catch (e: kotlinx.serialization.SerializationException) {
            KeyResult.Failed("Unexpected reply from your Immich server")
        }
    }

    private suspend fun delete(base: String, parent: String, id: String) {
        val req = Request.Builder().url("$base/api/sessions/$id").header("Authorization", "Bearer $parent").delete().build()
        http.newCall(req).await().close()
    }

    @Serializable private data class CreateRequest(val duration: Int, val deviceType: String, val deviceOS: String)
    @Serializable private data class CreateResponse(val id: String, val token: String, val expiresAt: String? = null)

    companion object {
        /** 30 minutes, the top of the 15–30 minute range: long enough for most videos on one key. */
        const val DEFAULT_DURATION_S = 30 * 60
        /** Re-create when fewer than 10 s remain, as Immich does. */
        const val REFRESH_BEFORE_MS = 10_000L
        const val DEVICE_TYPE = "Cast"
        const val DEVICE_OS = "Google Cast"
        private const val MAX_LEDGER = 16
        private val JSON = "application/json".toMediaType()
        private val json = Json { ignoreUnknownKeys = true }
    }
}
