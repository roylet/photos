package com.roylet.photos.net

import com.roylet.photos.immich.ImmichApi
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * What an Immich server says about itself without authentication, reduced to a few fields that
 * identify the install and don't depend on how it was reached.
 *
 * The first version hashed the whole canonicalised bodies of `/server/version`, `/server/config`
 * and `/server/features`. Any one differing byte (a field a proxy or a newer server adds, the
 * map style URLs, `externalDomain`, onboarding state, an OAuth account URL) made the server's own
 * LAN address look like an impostor, and the hash couldn't say which field. Now only [FIELDS] are
 * compared, by value, and [diff] names the ones that differ.
 *
 * Not a secret: anyone who can reach the public URL can copy it. It only means a random device on
 * the Wi-Fi has to be impersonating *this* server before the scan offers it the key. The real
 * proof is the `/users/me` id check that follows. A hand-entered address only needs the
 * [isHard] (version) fields to match before that check, since the user chose that host.
 */
object Fingerprint {
    /** Stored form: [PREFIX] + compact JSON of the fields. Anything else (an old hash) reads as "none". */
    const val PREFIX = "fp2:"

    val ENDPOINTS = listOf("version", "config", "features")

    /** endpoint to stable fields. Version is "hard"; config and features are deployment settings. */
    val FIELDS: Map<String, List<String>> = mapOf(
        "version" to listOf("major", "minor", "patch"),
        "config" to listOf("loginPageMessage", "trashDays", "userDeleteDelay", "oauthButtonText", "publicUsers", "isInitialized"),
        "features" to listOf("trash", "map", "oauth", "passwordLogin", "smartSearch", "facialRecognition", "duplicateDetection", "reverseGeocoding", "sidecar", "search", "email"),
    )

    /** A difference in these means a different server build: never the same install. */
    fun isHard(field: String): Boolean = field.startsWith("version.")

    /** endpoint to raw JSON body; null if any isn't a JSON object. */
    fun extract(bodies: Map<String, String>): Map<String, String>? {
        val out = sortedMapOf<String, String>()
        for ((endpoint, keys) in FIELDS) {
            val obj = runCatching { ImmichApi.json.parseToJsonElement(bodies[endpoint] ?: return null).jsonObject }.getOrNull() ?: return null
            for (k in keys) obj[k]?.let { v -> value(v)?.let { out["$endpoint.$k"] = it } }
        }
        return out
    }

    private fun value(e: JsonElement): String? = when (e) {
        JsonNull -> null
        is JsonPrimitive -> e.content.trim()
        else -> e.toString()
    }

    fun encode(fields: Map<String, String>): String =
        PREFIX + JsonObject(fields.toSortedMap().mapValues { JsonPrimitive(it.value) }).toString()

    fun decode(stored: String?): Map<String, String>? {
        if (stored == null || !stored.startsWith(PREFIX)) return null
        return runCatching {
            ImmichApi.json.parseToJsonElement(stored.removePrefix(PREFIX)).jsonObject.mapValues { it.value.jsonPrimitive.content }
        }.getOrNull()
    }

    /** Field names whose values differ (or are present on one side only), sorted. */
    fun diff(expected: Map<String, String>, actual: Map<String, String>): List<String> =
        (expected.keys + actual.keys).filter { expected[it] != actual[it] }.sorted()

    /** "config.trashDays: 30 vs 7" for the diagnostics panel. */
    fun describeDiff(expected: Map<String, String>, actual: Map<String, String>, fields: List<String>): String =
        fields.joinToString("; ") { "$it: ${expected[it] ?: "(missing)"} (public) vs ${actual[it] ?: "(missing)"} (here)" }
}
