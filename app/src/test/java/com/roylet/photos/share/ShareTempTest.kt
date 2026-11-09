package com.roylet.photos.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ShareTempTest {
    @get:Rule val tmp = TemporaryFolder()

    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L

    private fun file(dir: File, path: String, age: Long) = File(dir, path).apply {
        parentFile?.mkdirs(); writeText("x"); setLastModified(now - age)
    }

    @Test fun `files and folders older than a day go, newer ones stay`() {
        val share = tmp.newFolder("share")
        val old = file(share, "abc/IMG_1.jpg", 25 * hour).parentFile!!.apply { setLastModified(now - 25 * hour) }
        val fresh = file(share, "def/IMG_2.jpg", 2 * hour).parentFile!!.apply { setLastModified(now - 25 * hour) }
        val oldLoose = file(share, "loose.mp4", 30 * hour)
        val compressed = File(share, ShareTemp.DIR)
        file(compressed, "0/VID.mp4", 26 * hour)
        compressed.setLastModified(now - 26 * hour)
        File(compressed, "0").setLastModified(now - 26 * hour)

        assertEquals(3, ShareTemp.cleanStale(share, now))
        assertFalse(old.exists())
        assertFalse(oldLoose.exists())
        assertFalse(compressed.exists())
        assertTrue(fresh.exists()) // a folder is as young as its newest file
    }

    @Test fun `clear empties the previous share and keeps the folder`() {
        val dir = File(tmp.root, "share/compressed")
        file(dir, "0/a.mp4", 0)
        file(dir, "1/b.jpg", 0)
        ShareTemp.clear(dir)
        assertTrue(dir.isDirectory)
        assertEquals(0, dir.listFiles()!!.size)
        ShareTemp.clear(File(tmp.root, "missing"))
        assertTrue(File(tmp.root, "missing").isDirectory)
    }

    @Test fun `output names keep the base name`() {
        assertEquals("IMG_1234.mp4", ShareTemp.outputName("IMG_1234.MOV", "mp4"))
        assertEquals("holiday clip.jpg", ShareTemp.outputName("holiday clip.HEIC", "jpg"))
        assertEquals("a_b.jpg", ShareTemp.outputName("a:b.png", "jpg"))
        assertEquals("shared.mp4", ShareTemp.outputName("", "mp4"))
    }
}
