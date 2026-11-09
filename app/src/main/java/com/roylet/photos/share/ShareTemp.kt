package com.roylet.photos.share

import java.io.File

/**
 * Temporary files of compressed shares, under `cacheDir/share/compressed` (served by the app's
 * FileProvider). The receiving app may still be reading them after the chooser closes, so they
 * stay until the next compressed share or for [MAX_AGE_MS], whichever comes first.
 */
object ShareTemp {
    const val DIR = "compressed"
    const val MAX_AGE_MS = 24L * 60 * 60 * 1000

    /** Deletes entries of [dir] (top level, recursively) last modified more than [maxAgeMs] before [now]. Returns how many went. */
    fun cleanStale(dir: File, now: Long = System.currentTimeMillis(), maxAgeMs: Long = MAX_AGE_MS): Int {
        var n = 0
        dir.listFiles()?.forEach { f ->
            if (now - newest(f) > maxAgeMs && f.deleteRecursively()) n++
        }
        return n
    }

    /** Empties [dir] (the previous share's outputs) and makes sure it exists. */
    fun clear(dir: File) {
        dir.listFiles()?.forEach { it.deleteRecursively() }
        dir.mkdirs()
    }

    /** The newest modification time inside [f] (a share folder is as old as its newest file). */
    private fun newest(f: File): Long =
        if (f.isDirectory) maxOf(f.lastModified(), f.listFiles()?.maxOfOrNull(::newest) ?: 0L) else f.lastModified()

    /** The compressed copy's name: the original's base name with the new extension (`IMG_1.MOV` → `IMG_1.mp4`). */
    fun outputName(originalName: String, extension: String): String {
        val base = originalName.substringAfterLast('/').substringBeforeLast('.').replace(Regex("[^A-Za-z0-9._ -]"), "_").trim()
        return "${base.ifBlank { "shared" }.take(100)}.$extension"
    }
}
