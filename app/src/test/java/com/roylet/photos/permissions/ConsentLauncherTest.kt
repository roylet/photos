package com.roylet.photos.permissions

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import androidx.activity.result.IntentSenderRequest
import androidx.test.core.app.ApplicationProvider
import com.roylet.photos.ui.permissions.ConsentLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConsentLauncherTest {
    private val pending: PendingIntent = PendingIntent.getActivity(
        ApplicationProvider.getApplicationContext(), 0, Intent("test"), PendingIntent.FLAG_IMMUTABLE,
    )

    private suspend fun waitFor(cond: () -> Boolean) { repeat(500) { if (cond()) return; yield() }; assertTrue("timed out", cond()) }

    @Test fun `launches the intent sender and returns the user's answer`() = runBlocking {
        val consent = ConsentLauncher(main = Dispatchers.Unconfined)
        val launched = mutableListOf<IntentSenderRequest>()
        consent.attach { launched += it }

        val allowed = async { consent.requestConsent(pending) }
        waitFor { launched.size == 1 }
        assertEquals(pending.intentSender, launched.single().intentSender)
        consent.onResult(Activity.RESULT_OK)
        assertTrue(allowed.await())

        val denied = async { consent.requestConsent(pending) }
        waitFor { launched.size == 2 }
        consent.onResult(Activity.RESULT_CANCELED)
        assertFalse(denied.await())
    }

    @Test fun `requests queue up, one dialog at a time`() = runBlocking {
        val consent = ConsentLauncher(main = Dispatchers.Unconfined)
        val launched = mutableListOf<IntentSenderRequest>()
        consent.attach { launched += it }
        val first = async { consent.requestConsent(pending) }
        val second = async { consent.requestConsent(pending) }
        waitFor { launched.size == 1 }
        repeat(20) { yield() }
        assertEquals("the second waits for the first", 1, launched.size)
        consent.onResult(Activity.RESULT_OK)
        waitFor { launched.size == 2 }
        consent.onResult(Activity.RESULT_CANCELED)
        assertTrue(first.await())
        assertFalse(second.await())
    }

    @Test fun `without a screen it gives up instead of hanging`() = runBlocking {
        val consent = ConsentLauncher(attachTimeoutMs = 50, main = Dispatchers.Unconfined)
        assertFalse(consent.isAttached)
        assertFalse(consent.requestConsent(pending))
    }

    @Test fun `a launch that throws counts as no, and detaching only removes the same launcher`() = runBlocking {
        val consent = ConsentLauncher(attachTimeoutMs = 50, main = Dispatchers.Unconfined)
        val broken: (IntentSenderRequest) -> Unit = { throw IllegalStateException("no activity") }
        consent.attach(broken)
        assertFalse(consent.requestConsent(pending))
        consent.detach {}
        assertTrue(consent.isAttached)
        consent.detach(broken)
        assertFalse(consent.isAttached)
    }
}
