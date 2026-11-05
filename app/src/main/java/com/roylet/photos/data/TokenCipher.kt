package com.roylet.photos.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts small secrets (the Immich session token) before they go into SharedPreferences.
 * [encrypt]/[decrypt] return null when the platform can't do it (no Keystore), so callers simply
 * don't store the secret rather than storing it in the clear.
 */
interface TokenCipher {
    fun encrypt(plain: String): String?
    fun decrypt(sealed: String): String?
}

/** AES-256-GCM with a non-exportable key in the Android Keystore; output is base64(iv ‖ ciphertext). */
class KeystoreTokenCipher(private val alias: String = "photos.session-token") : TokenCipher {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    override fun encrypt(plain: String): String? = runCatching { AesGcm.seal(key(), plain) }.getOrNull()
    override fun decrypt(sealed: String): String? = runCatching { AesGcm.open(key(), sealed) }.getOrNull()

    private companion object { const val KEYSTORE = "AndroidKeyStore" }
}

/** The AES-GCM framing shared by [KeystoreTokenCipher] and tests. */
object AesGcm {
    fun seal(key: SecretKey, plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        val iv = c.iv
        val body = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        val out = ByteBuffer.allocate(1 + iv.size + body.size).put(iv.size.toByte()).put(iv).put(body).array()
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun open(key: SecretKey, sealed: String): String {
        val buf = ByteBuffer.wrap(Base64.decode(sealed, Base64.NO_WRAP))
        val iv = ByteArray(buf.get().toInt()).also { buf.get(it) }
        val body = ByteArray(buf.remaining()).also { buf.get(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        return String(c.doFinal(body), Charsets.UTF_8)
    }
}
