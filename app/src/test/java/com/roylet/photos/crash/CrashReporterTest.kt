package com.roylet.photos.crash

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import com.roylet.photos.ui.settings.CrashReportSheet
import com.roylet.photos.ui.settings.crashSummary
import com.roylet.photos.ui.theme.PhotosTheme
import com.roylet.photos.util.CrashLog
import com.roylet.photos.util.CrashReporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h880dp-xhdpi")
class CrashReporterTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val tmp = TemporaryFolder()

    private val header = "App: Photos 0.4.2 (42)\nDevice: Google Pixel 7 (panther)\nAndroid: 14 (API 34)\n"

    private fun reporter(log: CrashLog) = CrashReporter(log, header, clock = { 1_762_671_363_000L }).also { log.dir.mkdirs() }

    @Test fun writesTraceVersionDeviceMemoryAndTime() {
        val log = CrashLog(tmp.newFolder("crash"))
        val error = IllegalStateException("outer", OutOfMemoryError("Failed to allocate a 48 MB allocation"))
        reporter(log).record(Thread.currentThread(), error)

        val report = log.readUnseen()!!
        assertTrue(report.startsWith("Photos closed unexpectedly\nApp: Photos 0.4.2 (42)\nDevice: Google Pixel 7"))
        assertTrue(report, "Android: 14 (API 34)" in report)
        assertTrue(report, "Time: 2025-11-09 06:56:03 UTC" in report)
        assertTrue(report, Regex("Memory: Java heap \\d+ MB used of \\d+ MB max").containsMatchIn(report))
        assertTrue(report, "java.lang.IllegalStateException: outer" in report)
        assertTrue(report, "Caused by: java.lang.OutOfMemoryError: Failed to allocate a 48 MB allocation" in report)
        assertTrue(report, "\tat com.roylet.photos.crash.CrashReporterTest.writesTraceVersionDeviceMemoryAndTime(CrashReporterTest.kt:" in report)
        assertEquals("2025-11-09 06:56:03 UTC · java.lang.IllegalStateException", crashSummary(report))

        log.markSeen()
        assertNull(log.readUnseen())
        assertEquals(report, log.read())
    }

    @Test fun hugeTracesAreTruncatedNotGrown() {
        val log = CrashLog(tmp.newFolder("crash"))
        fun deep(n: Int): Nothing = if (n == 0) throw StackOverflowError("x".repeat(5_000)) else deep(n - 1)
        val error = runCatching { deep(3_000) }.exceptionOrNull()!!
        var chain: Throwable = error
        repeat(20) { chain = RuntimeException("level $it ".repeat(200), chain) }
        val bytes = reporter(log).record(Thread.currentThread(), chain)
        assertTrue(bytes <= CrashReporter.MAX_CHARS + 64)
        val report = log.read()!!
        assertTrue(report.endsWith("[truncated]\n") || "more frames" in report)
    }

    @Test fun nonAsciiMessagesAreUtf8() {
        val log = CrashLog(tmp.newFolder("crash"))
        reporter(log).record(Thread.currentThread(), RuntimeException("Café ✓"))
        assertTrue("java.lang.RuntimeException: Café ✓" in log.read()!!)
    }

    @Test fun handlerChainsToThePreviousOne() {
        val log = CrashLog(tmp.newFolder("crash"))
        val before = Thread.getDefaultUncaughtExceptionHandler()
        var passedOn: Throwable? = null
        Thread.setDefaultUncaughtExceptionHandler { _, e -> passedOn = e }
        try {
            reporter(log).install()
            val boom = RuntimeException("boom")
            val t = Thread { throw boom }
            t.start(); t.join()
            assertEquals(boom, passedOn)
            assertTrue("java.lang.RuntimeException: boom" in log.readUnseen()!!)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(before)
        }
    }

    @Test fun nextLaunchSheetShowsTheReportAndDismisses() {
        val report = "Photos closed unexpectedly\nApp: Photos 0.4.2 (42)\n\njava.lang.OutOfMemoryError: boom\n\tat a.b.C.d(C.kt:1)\n"
        var shown by mutableStateOf<String?>(report)
        compose.setContent { PhotosTheme(dark = false) { CrashReportSheet(shown, onDismiss = { shown = null }) } }
        compose.mainClock.advanceTimeBy(1_000) // let the sheet slide in
        compose.onNodeWithText("Photos closed unexpectedly").assertExists()
        compose.onNodeWithText(report).assertExists()
        compose.onNodeWithText("COPY DETAILS").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        assertEquals(report, shown)
        compose.onNodeWithText("COPIED").assertExists()
        compose.onNodeWithText("SHARE").assertExists()
        compose.onNodeWithText("DISMISS").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitForIdle()
        assertNull(shown)
        assertFalse(compose.onAllNodes(androidx.compose.ui.test.hasText("COPIED")).fetchSemanticsNodes().isNotEmpty())
    }
}
