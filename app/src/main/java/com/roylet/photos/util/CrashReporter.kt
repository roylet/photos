package com.roylet.photos.util

import android.content.Context
import android.os.Build
import android.os.Debug
import java.io.File
import java.io.FileOutputStream

/**
 * Where the last crash report lives: `files/crash/last_crash.txt`, plus an `unseen` marker that
 * the next launch shows (and clears) as "Photos closed unexpectedly". The report itself stays
 * until the next crash replaces it (Settings → About → Last crash report).
 */
class CrashLog(val dir: File) {
    val report = File(dir, "last_crash.txt")
    val unseen = File(dir, "unseen")

    /** The last report, or null when there's none. */
    fun read(): String? = runCatching { report.takeIf { it.isFile }?.readText() }.getOrNull()?.takeIf { it.isNotBlank() }

    /** The last report if it hasn't been shown yet. */
    fun readUnseen(): String? = if (unseen.exists()) read() else null

    fun markSeen() { runCatching { unseen.delete() } }

    companion object {
        fun of(context: Context) = CrashLog(File(context.filesDir, "crash"))
    }
}

/**
 * Records uncaught exceptions in [log], then hands them on to the handler that was installed
 * before (the system's, which kills the process and logs the trace).
 *
 * It must still work when the crash is an OutOfMemoryError, so nothing big is allocated at crash
 * time: the text is built in a preallocated [StringBuilder] (never grown past [MAX_CHARS]; the
 * trace is cut off there), encoded into a preallocated byte buffer and written in one call. The
 * app/device header is worked out up front, and the time is formatted by hand.
 */
class CrashReporter(private val log: CrashLog, private val header: String, private val clock: () -> Long = System::currentTimeMillis) {
    private val text = StringBuilder(MAX_CHARS + SLACK)
    private val bytes = ByteArray((MAX_CHARS + SLACK) * 3)
    private val seen = arrayOfNulls<Throwable>(MAX_CAUSES)

    fun install() {
        runCatching { log.dir.mkdirs() }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                record(thread, error)
            } catch (_: Throwable) {
                // Never let the report get in the way of the crash itself.
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Builds the report for [error] on [thread] and writes it. Returns the number of bytes written. */
    @Synchronized
    fun record(thread: Thread, error: Throwable): Int {
        val sb = text
        sb.setLength(0)
        sb.append("Photos closed unexpectedly\n").append(header)
        sb.append("Time: ")
        appendUtc(sb, clock())
        sb.append('\n')
        sb.append("Thread: ").append(thread.name).append('\n')
        val rt = Runtime.getRuntime()
        sb.append("Memory: Java heap ").append((rt.totalMemory() - rt.freeMemory()) / MB).append(" MB used of ")
            .append(rt.maxMemory() / MB).append(" MB max (").append(rt.freeMemory() / MB).append(" MB free); native ")
            .append(Debug.getNativeHeapAllocatedSize() / MB).append(" MB\n\n")
        appendTrace(sb, error)
        val n = encode(sb)
        FileOutputStream(log.report).use { it.write(bytes, 0, n) }
        FileOutputStream(log.unseen).close()
        return n
    }

    private fun appendTrace(sb: StringBuilder, error: Throwable) {
        java.util.Arrays.fill(seen, null)
        var e: Throwable? = error
        var depth = 0
        while (e != null && depth < MAX_CAUSES && seen.none { it === e }) {
            seen[depth] = e
            if (depth > 0) appendCapped(sb, "Caused by: ")
            appendCapped(sb, e.javaClass.name)
            e.message?.let { appendCapped(sb, ": "); appendCapped(sb, it, maxLen = 600) }
            appendCapped(sb, "\n")
            // The one copy this makes is small; if even that fails, the header and message are already in.
            val frames = try { e.stackTrace } catch (_: Throwable) { null }
            if (frames != null) {
                val limit = if (depth == 0) 80 else 30
                for (i in 0 until minOf(frames.size, limit)) {
                    if (sb.length >= MAX_CHARS) break
                    val f = frames[i]
                    appendCapped(sb, "\tat "); appendCapped(sb, f.className); appendCapped(sb, "."); appendCapped(sb, f.methodName)
                    appendCapped(sb, "(")
                    appendCapped(sb, f.fileName ?: "Unknown Source")
                    if (f.lineNumber >= 0 && sb.length < MAX_CHARS) sb.append(':').append(f.lineNumber)
                    appendCapped(sb, ")\n")
                }
                if (frames.size > limit) appendCapped(sb, "\t... more frames\n")
            }
            if (sb.length >= MAX_CHARS) { sb.setLength(MAX_CHARS); sb.append("\n[truncated]\n"); return }
            e = e.cause
            depth++
        }
    }

    /** Appends at most what still fits under [MAX_CHARS], so the builder never reallocates. */
    private fun appendCapped(sb: StringBuilder, s: String, maxLen: Int = Int.MAX_VALUE) {
        val room = MAX_CHARS - sb.length
        if (room <= 0) return
        sb.append(s, 0, minOf(s.length, room, maxLen))
    }

    /** UTF-8 into [bytes] by hand (String.toByteArray would allocate a copy). */
    private fun encode(sb: StringBuilder): Int {
        var n = 0
        for (i in 0 until sb.length) {
            val c = sb[i].code
            when {
                c < 0x80 -> bytes[n++] = c.toByte()
                c < 0x800 -> { bytes[n++] = (0xC0 or (c shr 6)).toByte(); bytes[n++] = (0x80 or (c and 0x3F)).toByte() }
                else -> {
                    bytes[n++] = (0xE0 or (c shr 12)).toByte()
                    bytes[n++] = (0x80 or ((c shr 6) and 0x3F)).toByte()
                    bytes[n++] = (0x80 or (c and 0x3F)).toByte()
                }
            }
        }
        return n
    }

    companion object {
        /** Report size cap (chars). Plenty for a trace with a few causes; small enough to preallocate. */
        const val MAX_CHARS = 24_000
        private const val SLACK = 64
        private const val MAX_CAUSES = 8
        private const val MB = 1024 * 1024

        /** App version, device and Android version: worked out once, at install time. */
        fun header(context: Context): String {
            val pkg = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
            @Suppress("DEPRECATION")
            val code = pkg?.let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong() }
            return "App: Photos ${pkg?.versionName ?: "?"} ($code)\n" +
                "Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})\n" +
                "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n"
        }

        /** `2026-11-09 06:49:23 UTC` without allocating (civil-from-days, H. Hinnant). */
        internal fun appendUtc(sb: StringBuilder, millis: Long) {
            val secs = Math.floorDiv(millis, 1000L)
            val days = Math.floorDiv(secs, 86_400L)
            val sod = Math.floorMod(secs, 86_400L).toInt()
            val z = days + 719_468
            val era = Math.floorDiv(z, 146_097L)
            val doe = (z - era * 146_097).toInt()
            val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
            val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
            val mp = (5 * doy + 2) / 153
            val d = doy - (153 * mp + 2) / 5 + 1
            val m = if (mp < 10) mp + 3 else mp - 9
            val y = yoe + era * 400 + if (m <= 2) 1 else 0
            sb.append(y).append('-'); two(sb, m); sb.append('-'); two(sb, d)
            sb.append(' '); two(sb, sod / 3600); sb.append(':'); two(sb, sod / 60 % 60); sb.append(':'); two(sb, sod % 60)
            sb.append(" UTC")
        }

        private fun two(sb: StringBuilder, v: Int) { if (v < 10) sb.append('0'); sb.append(v) }
    }
}
