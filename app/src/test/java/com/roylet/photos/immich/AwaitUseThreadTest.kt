package com.roylet.photos.immich

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Regression for NetworkOnMainThreadException on search (v0.4.1): response bodies were read on whatever
 * dispatcher called the API, which is the main thread for screens that call it from the UI.
 * [awaitUse] must always read on an IO thread, even when the caller is on a single "UI-like" thread.
 */
class AwaitUseThreadTest {
    private val server = MockWebServer().apply { start() }

    @After fun tearDown() = server.shutdown()

    @Test fun bodyIsReadOffTheCallersThread() = runBlocking {
        server.enqueue(MockResponse().setBody("hello"))
        val caller = Thread.currentThread()
        // Unconfined resumes on the caller thread, like the main dispatcher would.
        val (text, readOn) = withContext(Dispatchers.Unconfined) {
            OkHttpClient().newCall(Request.Builder().url(server.url("/")).build())
                .awaitUse { it.body!!.string() to Thread.currentThread() }
        }
        assertEquals("hello", text)
        assertNotEquals(caller, readOn)
    }
}
