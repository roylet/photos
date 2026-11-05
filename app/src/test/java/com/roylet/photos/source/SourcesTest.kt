package com.roylet.photos.source

import com.roylet.photos.domain.source.ActiveSources
import com.roylet.photos.domain.source.AssetRef
import com.roylet.photos.domain.source.Capabilities
import com.roylet.photos.domain.source.Capability
import com.roylet.photos.domain.source.MediaSourceKind
import com.roylet.photos.domain.source.SourceBadge
import com.roylet.photos.domain.source.forAsset
import com.roylet.photos.domain.timeline.TimelineAsset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcesTest {
    private val uuid = "3f2b8c1e-9a4d-4e57-b1f0-2c6d8e9a7b10"

    @Test fun `plain uuids are immich, so old ids and deep links keep working`() {
        val ref = AssetRef.parse(uuid)!!
        assertEquals(MediaSourceKind.Immich, ref.kind)
        assertEquals(uuid, ref.key)
        assertEquals(uuid, ref.id)
        assertEquals("immich:$uuid", ref.encode())
        assertEquals(uuid, AssetRef.immichIdOrNull(uuid))
    }

    @Test fun `tagged ids round trip`() {
        val local = AssetRef.local(1234)
        assertEquals("local:1234", local.encode())
        assertEquals("local:1234", local.id)
        assertEquals(local, AssetRef.parse("local:1234"))
        assertEquals(1234L, local.mediaStoreId)
        assertTrue(AssetRef.isLocal("local:1234"))
        assertNull(AssetRef.immichIdOrNull("local:1234"))

        val remote = AssetRef.parse("immich:$uuid")!!
        assertEquals(AssetRef.immich(uuid), remote)
        assertEquals(uuid, remote.id) // Immich keeps the bare id in the app.
        assertEquals(uuid, AssetRef.immichIdOrNull("immich:$uuid"))
        assertNull(remote.mediaStoreId)
    }

    @Test fun `odd ids`() {
        assertNull(AssetRef.parse(""))
        assertNull(AssetRef.parse("local:"))
        // An unknown prefix is just part of an (Immich) id, e.g. fake ids in screenshots.
        assertEquals(AssetRef(MediaSourceKind.Immich, "2026-11-3"), AssetRef.parse("2026-11-3"))
        assertEquals(AssetRef(MediaSourceKind.Immich, "fake:x"), AssetRef.parse("fake:x"))
        assertEquals(MediaSourceKind.Immich, AssetRef.kindOf(""))
        assertNull(AssetRef.parse("local:abc")!!.mediaStoreId)
    }

    @Test fun `source badges`() {
        val base = TimelineAsset(id = uuid, ratio = 1f, isImage = true, isFavorite = false)
        assertEquals(SourceBadge.RemoteOnly, base.sourceBadge)
        assertEquals(SourceBadge.LocalOnly, base.copy(id = "local:9").sourceBadge)
        assertEquals(SourceBadge.Both, base.copy(id = "local:9", remoteRef = AssetRef.immich(uuid)).sourceBadge)
        assertEquals(SourceBadge.Both, base.copy(localRef = AssetRef.local(9)).sourceBadge)
        assertEquals(AssetRef.local(9), base.copy(id = "local:9").ref)
    }

    @Test fun `capability sets`() {
        val c = Capabilities.of(Capability.TRASH, Capability.MAP)
        assertTrue(Capability.TRASH in c)
        assertFalse(Capability.PEOPLE in c)
        assertEquals(setOf(Capability.MAP, Capability.TRASH), c.toSet())
        assertEquals(c, Capabilities.of(listOf(Capability.MAP, Capability.TRASH)))
        assertTrue((c - Capability.TRASH + Capability.PEOPLE).let { Capability.PEOPLE in it && Capability.TRASH !in it })
        assertEquals(Capabilities.All, Capabilities.Immich union Capabilities.Local)
        assertEquals(Capabilities.of(Capability.TRASH, Capability.FAVORITE), Capabilities.Immich intersect Capabilities.Local)
        assertTrue(Capabilities.None.isEmpty)
        assertEquals(Capability.entries.size, Capabilities.All.toSet().size)
    }

    @Test fun `immich has every server feature, the phone only its own`() {
        val serverOnly = listOf(
            Capability.SHARE_LINKS, Capability.PEOPLE, Capability.MAP, Capability.SMART_SEARCH,
            Capability.ARCHIVE, Capability.STACKS, Capability.SERVER_ALBUMS, Capability.MEMORIES, Capability.EDIT_SAVE,
        )
        serverOnly.forEach { assertTrue("$it", it in Capabilities.Immich); assertFalse("$it", it in Capabilities.Local) }
        assertTrue(Capability.FOLDERS in Capabilities.Local)
        assertFalse(Capability.FOLDERS in Capabilities.Immich)
        listOf(Capability.TRASH, Capability.FAVORITE).forEach { assertTrue(it in Capabilities.Local && it in Capabilities.Immich) }
        assertEquals(Capabilities.Local, ActiveSources.LocalOnly.capabilities)
        assertTrue(ActiveSources.LocalOnly.hasLocal && !ActiveSources.LocalOnly.hasImmich)
    }

    @Test fun `per-asset gating drops server actions for phone-only items`() {
        val both = Capabilities.All
        val localOnly = both.forAsset("local:5")
        assertFalse(Capability.SHARE_LINKS in localOnly)
        assertFalse(Capability.ARCHIVE in localOnly)
        assertFalse(Capability.SERVER_ALBUMS in localOnly)
        assertTrue(Capability.TRASH in localOnly && Capability.FAVORITE in localOnly && Capability.FOLDERS in localOnly)

        val remoteOnly = both.forAsset(uuid)
        assertTrue(Capability.ARCHIVE in remoteOnly)
        assertFalse(Capability.FOLDERS in remoteOnly)

        // A merged item can do everything either copy can.
        assertEquals(both, both.forAsset("local:5", remoteRef = AssetRef.immich(uuid)))
        // Never adds what the active sources lack.
        assertEquals(Capabilities.Local - Capability.FOLDERS, Capabilities.Local.forAsset(uuid))
    }
}
