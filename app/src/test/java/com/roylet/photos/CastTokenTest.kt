package com.roylet.photos

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.roylet.photos.data.AesGcm
import com.roylet.photos.data.ServerConfig
import com.roylet.photos.data.Settings
import com.roylet.photos.data.TokenCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.KeyGenerator

/** The cast session token: encrypted at rest, cleared on sign-out, absent for API-key sign-ins. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CastTokenTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val token = "eyJhbGciOiJIUzI1NiJ9.session-token-123"
    private val config = ServerConfig(publicUrl = "https://photos.example.com", apiKey = "k-1", userId = "u-1", email = "me@example.com")

    /** Same framing as the Keystore cipher, with an in-memory key (Robolectric has no AndroidKeyStore). */
    private val cipher = object : TokenCipher {
        private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        override fun encrypt(plain: String) = AesGcm.seal(key, plain)
        override fun decrypt(sealed: String) = runCatching { AesGcm.open(key, sealed) }.getOrNull()
    }

    /** Everything the app wrote to SharedPreferences, as text. */
    private fun allPrefs(): String = listOf("settings", "secrets").joinToString("\n") { name ->
        context.getSharedPreferences(name, Context.MODE_PRIVATE).all.entries.joinToString("\n") { "${it.key}=${it.value}" }
    }

    @Test fun `stored encrypted and read back`() {
        Settings(context, cipher).saveServer(config.copy(castSessionToken = token))
        assertFalse("the token must never be in plain prefs", allPrefs().contains(token))
        assertTrue("a sealed value is stored", context.getSharedPreferences("secrets", Context.MODE_PRIVATE).getString("castSessionToken", null) != null)
        assertEquals(token, Settings(context, cipher).server.value?.castSessionToken)
        assertFalse("never in logs", config.copy(castSessionToken = token).toString().contains(token))
        assertFalse(config.toString().contains("k-1"))
    }

    @Test fun `cleared on sign out`() {
        val settings = Settings(context, cipher)
        settings.saveServer(config.copy(castSessionToken = token))
        settings.signOut()
        assertNull(settings.server.value)
        assertFalse(allPrefs().contains("castSessionToken"))
        settings.saveServer(config)
        assertNull(Settings(context, cipher).server.value?.castSessionToken)
    }

    @Test fun `api key sign in has no token, and replaces an old one`() {
        val settings = Settings(context, cipher)
        settings.saveServer(config.copy(castSessionToken = token))
        settings.saveServer(config.copy(apiKey = "pasted"))
        assertNull(settings.server.value?.castSessionToken)
        assertNull(Settings(context, cipher).server.value?.castSessionToken)
    }

    @Test fun `without a working cipher the token is dropped, not stored in the clear`() {
        val broken = object : TokenCipher {
            override fun encrypt(plain: String): String? = null
            override fun decrypt(sealed: String): String? = null
        }
        val settings = Settings(context, broken)
        settings.saveServer(config.copy(castSessionToken = token))
        assertNull(settings.server.value?.castSessionToken)
        assertFalse(allPrefs().contains(token))
        assertNotNull(settings.server.value)
    }

    @Test fun `container exposes it`() {
        val container = ApplicationProvider.getApplicationContext<PhotosApp>().container
        assertNull(container.serverSessionToken())
        container.settings.signOut()
    }
}
