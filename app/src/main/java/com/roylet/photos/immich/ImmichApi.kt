package com.roylet.photos.immich

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Serializable data class LoginResponse(val accessToken: String, val userId: String = "", val userEmail: String = "", val name: String = "")
@Serializable data class ApiKeySecret(val secret: String)
@Serializable data class UserMe(val id: String, val email: String = "", val name: String = "")
@Serializable data class UploadResult(val id: String, val status: String)
@Serializable private data class LoginRequest(val email: String, val password: String)
@Serializable private data class ApiKeyRequest(val name: String, val permissions: List<String>)
@Serializable private data class Ping(@SerialName("res") val res: String)
@Serializable private data class Version(val major: Int, val minor: Int, val patch: Int)

/**
 * A failed Immich request. [retryable] separates transient failures (timeouts, 5xx, proxy
 * hiccups) from ones that will fail identically every time (bad key, file too large for the proxy).
 */
class ImmichException(message: String, val status: Int? = null, val retryable: Boolean = false) : IOException(message)

/** The file changed size while it was being streamed (the caller may try again later). */
class SourceChangedException(message: String) : IOException(message)

/**
 * Everything Immich needs to create one asset. [open] must yield exactly [size] bytes.
 *
 * [deviceAssetId] (with `deviceId`) is only sent when set: Immich's current `POST /assets` schema
 * (AssetMediaCreateDto) no longer lists them, and backup leaves them out. Edit saves still send
 * one, since older servers echo it back to tell their own retried upload from a real duplicate.
 */
class UploadSpec(
    val filename: String,
    val mime: String,
    val size: Long,
    val checksum: String,
    val deviceAssetId: String?,
    val createdAt: String,
    val modifiedAt: String,
    val isFavorite: Boolean = false,
    val open: () -> InputStream,
)

/**
 * Immich REST client. [apiKey] is sent as `x-api-key` on every call made through this class.
 * Pass the app's shared [http] client so the connection manager's interceptor (LAN/public URL
 * switching) applies to API calls exactly as it does to Coil and ExoPlayer.
 */
class ImmichApi(serverUrl: String, private val apiKey: String?, private val http: OkHttpClient = defaultClient) {
    val origin: String = normalizeOrigin(serverUrl)
    private val api = "$origin/api"

    /**
     * Uploads get their own timeouts: write is per-write idle time (a stalled socket), and read is
     * generous because Immich only replies once the whole file is written and checksummed.
     * OkHttp's silent connection retry is off: re-sending a multi-GB body behind the caller's back
     * would also double-count progress.
     */
    private val uploadHttp by lazy {
        http.newBuilder()
            .writeTimeout(2, TimeUnit.MINUTES)
            .readTimeout(15, TimeUnit.MINUTES)
            .retryOnConnectionFailure(false)
            .build()
    }

    suspend fun ping(): Boolean = runCatching {
        val res = http.newBuilder().callTimeout(4, TimeUnit.SECONDS).build().newCall(get("/server/ping")).await()
        res.use { it.isSuccessful && json.decodeFromString<Ping>(it.body!!.string()).res == "pong" }
    }.getOrDefault(false)

    suspend fun version(): String {
        val v = call<Version>(get("/server/version"))
        return "${v.major}.${v.minor}.${v.patch}"
    }

    suspend fun login(email: String, password: String): LoginResponse =
        call(post("/auth/login", LoginRequest(email, password), auth = false))

    /** Mints a scoped API key with only the permissions this app needs, so the password is never stored. */
    suspend fun createApiKey(bearer: String, name: String = KEY_NAME): String {
        val body = json.encodeToString(ApiKeyRequest(name, PERMISSIONS)).toRequestBody(JSON)
        val req = Request.Builder().url("$api/api-keys").post(body).header("Authorization", "Bearer $bearer").build()
        return call<ApiKeySecret>(req).secret
    }

    /** The signed-in user. Its [UserMe.id] is how two addresses are proven to be the same server. */
    suspend fun me(): UserMe = call(get("/users/me"))

    /**
     * Streams one file to POST /api/assets. The body has a known length, so it goes out with a
     * Content-Length header rather than chunked encoding: some reverse proxies buffer or reject
     * chunked uploads.
     */
    suspend fun upload(spec: UploadSpec, onProgress: (Long) -> Unit = {}): UploadResult {
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
        if (spec.deviceAssetId != null) form.addFormDataPart("deviceAssetId", spec.deviceAssetId).addFormDataPart("deviceId", DEVICE_ID)
        val body = form
            .addFormDataPart("fileCreatedAt", spec.createdAt)
            .addFormDataPart("fileModifiedAt", spec.modifiedAt)
            .addFormDataPart("filename", spec.filename)
            .addFormDataPart("isFavorite", spec.isFavorite.toString())
            .addFormDataPart("assetData", spec.filename, StreamBody(spec, onProgress))
            .build()
        val req = authed(
            Request.Builder()
                .url("$api/assets")
                .post(body)
                // Lets Immich short-circuit with "duplicate" if it already has this exact file.
                .header("x-immich-checksum", spec.checksum),
        )
        uploadHttp.newCall(req).await().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw httpError(res.code, text)
            return json.decodeFromString(text)
        }
    }

    private class StreamBody(private val spec: UploadSpec, private val onProgress: (Long) -> Unit) : RequestBody() {
        override fun contentType() = spec.mime.toMediaTypeOrNull()
        override fun contentLength() = spec.size
        override fun isOneShot() = true
        override fun writeTo(sink: BufferedSink) {
            spec.open().use { input ->
                val buffer = ByteArray(256 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (total + n > spec.size) throw SourceChangedException("${spec.filename} grew while it was being uploaded")
                    sink.write(buffer, 0, n)
                    total += n
                    onProgress(total)
                }
                if (total != spec.size) throw SourceChangedException("${spec.filename} ended early (read $total of ${spec.size} bytes)")
            }
        }
    }

    // Request helpers. Other parts of the app add endpoints alongside these (in this file or as
    // extension functions in this package) using the same builders.

    fun get(path: String): Request = authed(Request.Builder().url("$api$path").get())

    inline fun <reified T> post(path: String, payload: T, auth: Boolean = true): Request {
        val b = Request.Builder().url(url(path)).post(json.encodeToString(payload).toRequestBody(JSON))
        return if (auth) authed(b) else b.build()
    }

    inline fun <reified T> put(path: String, payload: T): Request =
        authed(Request.Builder().url(url(path)).put(json.encodeToString(payload).toRequestBody(JSON)))

    inline fun <reified T> delete(path: String, payload: T): Request =
        authed(Request.Builder().url(url(path)).delete(json.encodeToString(payload).toRequestBody(JSON)))

    fun url(path: String): String = "$api$path"

    fun authed(b: Request.Builder): Request = (if (apiKey != null) b.header("x-api-key", apiKey) else b).build()

    suspend inline fun <reified T> call(req: Request): T = client().newCall(req).await().use { res ->
        val text = res.body?.string().orEmpty()
        if (!res.isSuccessful) throw httpError(res.code, text)
        json.decodeFromString<T>(text)
    }

    /** For endpoints that return 204 / an ignorable body. */
    suspend fun send(req: Request) {
        http.newCall(req).await().use { res -> if (!res.isSuccessful) throw httpError(res.code, res.body?.string().orEmpty()) }
    }

    @PublishedApi internal fun client(): OkHttpClient = http

    companion object {
        const val DEVICE_ID = "photos"

        /** Name of the API key minted on sign-in; shows up in Immich under Account Settings → API Keys. */
        const val KEY_NAME = "Photos app"

        val PERMISSIONS = listOf(
            // Assets
            "asset.read", "asset.view", "asset.download", "asset.upload", "asset.update", "asset.delete", "asset.copy", "asset.edit.get",
            // Albums
            "album.read", "album.create", "album.update", "album.delete", "albumAsset.create", "albumAsset.delete",
            // Stacks
            "stack.read", "stack.create", "stack.update", "stack.delete",
            // Sharing
            "sharedLink.create", "sharedLink.read",
            // Other
            "person.read", "map.read", "memory.read", "memory.update", "user.read", "server.about", "tag.read",
        )

        /**
         * Tolerant decoding: unknown keys are skipped, and a JSON `null` for a non-null field that
         * has a default (Immich sends nulls for fields it documents as plain strings) takes the
         * default instead of throwing.
         */
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }
        @PublishedApi internal val JSON = "application/json".toMediaTypeOrNull()

        val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        /**
         * Turns what the user typed into a working origin. Without a scheme, https is tried first
         * (falling back to http). If the server redirects (typically http -> https) the final
         * address is used, since OkHttp would otherwise silently turn later POSTs (like login)
         * into GETs when following the redirect. Returns null if no candidate answers as Immich.
         */
        suspend fun discoverOrigin(input: String, http: OkHttpClient = defaultClient): String? {
            val typed = input.trim()
            val hasScheme = Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(typed)
            val candidates = if (hasScheme) listOf(normalizeOrigin(typed)) else listOf("https://", "http://").map { normalizeOrigin(it + typed) }
            val client = http.newBuilder().callTimeout(6, TimeUnit.SECONDS).build()
            for (origin in candidates) {
                val final = runCatching {
                    client.newCall(Request.Builder().url("$origin/api/server/ping").get().build()).await().use { res ->
                        if (!res.isSuccessful || json.decodeFromString<Ping>(res.body!!.string()).res != "pong") return@use null
                        // Where we actually ended up after any redirects.
                        res.request.url.toString().removeSuffix("/api/server/ping")
                    }
                }.getOrNull()
                if (final != null) return final
            }
            return null
        }

        /** "192.168.1.5:2283/" -> "http://192.168.1.5:2283"; also strips a trailing /api. */
        fun normalizeOrigin(url: String): String {
            val trimmed = url.trim().trimEnd('/')
            val withScheme = if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)) trimmed else "http://$trimmed"
            return withScheme.removeSuffix("/api").trimEnd('/')
        }

        fun httpError(code: Int, body: String): ImmichException {
            val serverMessage = runCatching {
                json.parseToJsonElement(body).let { it as JsonObject }["message"]?.jsonPrimitive?.content
            }.getOrNull()
            return when {
                // 401 covers both bad passwords and bad keys; Immich's message says which.
                code == 401 && (serverMessage == null || serverMessage.contains("API key", ignoreCase = true)) ->
                    ImmichException("Immich rejected the API key (${serverMessage ?: "unauthorized"}). Sign in again from Settings.", code)
                code == 401 -> ImmichException(serverMessage ?: "Unauthorized", code)
                code == 403 -> ImmichException("This API key isn't allowed to do that (${serverMessage ?: "forbidden"}).", code)
                code == 413 -> ImmichException(
                    "Too large for the server or a proxy in front of it (HTTP 413). Cloudflare blocks uploads over 100 MB: " +
                        "use your home network, or raise the proxy's body size limit.",
                    code,
                )
                code == 408 || code == 429 || code >= 500 ->
                    ImmichException("Server error ${serverMessage?.let { "($it)" } ?: "HTTP $code"}", code, retryable = true)
                else -> ImmichException(serverMessage ?: "HTTP $code", code)
            }
        }

        /** A short, human sentence for any failure from this client. */
        fun describe(e: Throwable): String = when (e) {
            is ImmichException -> e.message ?: "Server error"
            is java.net.SocketTimeoutException -> "Timed out talking to the server"
            is java.net.UnknownHostException -> "Couldn't find the server (${e.message})"
            is java.net.ConnectException -> "Couldn't connect to the server"
            is javax.net.ssl.SSLException -> "Secure connection failed: ${e.message}"
            else -> e.message ?: e.javaClass.simpleName
        }
    }
}

suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            cont.resume(response) { _, _, _ -> response.close() }
        }
    })
}
