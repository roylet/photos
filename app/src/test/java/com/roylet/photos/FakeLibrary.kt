package com.roylet.photos

import com.roylet.photos.domain.library.Album
import com.roylet.photos.domain.library.ExploreItem
import com.roylet.photos.domain.library.ExploreSection
import com.roylet.photos.domain.library.LibrarySource
import com.roylet.photos.domain.library.MapMarker
import com.roylet.photos.domain.library.Memory
import com.roylet.photos.domain.library.Person
import com.roylet.photos.domain.library.ShareLinkOptions
import com.roylet.photos.domain.timeline.TimelineAsset
import com.roylet.photos.domain.timeline.Visibility
import java.io.File
import java.time.LocalDate
import java.util.Collections

/**
 * In-memory library for tests and screenshots: a dozen albums with covers from [FakeGallery],
 * people, places and memories. Every call is recorded in [calls]; set [fail] to make the next
 * calls throw.
 */
class FakeLibrary : LibrarySource {
    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
    var fail: Throwable? = null
    private val day = 86_400_000L
    private val now = 1_793_750_400_000L // 2026-11-04

    private fun asset(bucket: Int, i: Int): TimelineAsset = FakeGallery.assetsOf(FakeGallery.buckets[bucket].key)[i]

    val albums = mutableListOf(
        Album("al-1", "Tasmania road trip", "Hobart, Freycinet and the long way round", 214, asset(1, 3).id, updatedAt = now - 2 * day, ownerName = "Roy", startDate = now - 40 * day, endDate = now - 30 * day),
        Album("al-2", "Kyoto", "Autumn leaves", 96, asset(2, 5).id, shared = true, hasSharedLink = true, updatedAt = now - 9 * day, ownerName = "Roy"),
        Album("al-3", "Family", "", 1_408, asset(0, 1).id, shared = true, updatedAt = now - day, ownerName = "Sam", ownedByMe = false),
        Album("al-4", "Lisbon 2025", "", 162, asset(5, 2).id, updatedAt = now - 120 * day),
        Album("al-5", "Sunsets", "The good ones", 58, asset(3, 7).id, updatedAt = now - 20 * day),
        Album("al-6", "Dog", "", 731, asset(4, 4).id, updatedAt = now - 4 * day, shared = true),
        Album("al-7", "Wedding", "", 389, asset(6, 0).id, updatedAt = now - 300 * day),
        Album("al-8", "Empty idea", "", 0, null, updatedAt = now - 3 * day),
    )

    var members = mutableMapOf("al-1" to setOf(asset(0, 0).id))

    private fun record(s: String) { calls += s; fail?.let { throw it } }

    override suspend fun albums(): List<Album> { record("albums"); return albums.toList() }
    override suspend fun albumsContaining(assetIds: List<String>): Set<String> =
        members.filterValues { it.containsAll(assetIds) }.keys
    override suspend fun album(id: String): Album = albums.first { it.id == id }
    override suspend fun createAlbum(name: String, assetIds: List<String>): Album {
        record("createAlbum $name ${assetIds.joinToString(",")}")
        return Album("al-new-${albums.size}", name, assetCount = assetIds.size, coverAssetId = assetIds.firstOrNull(), updatedAt = now).also { albums.add(0, it) }
    }
    override suspend fun updateAlbum(id: String, name: String?, description: String?, coverAssetId: String?): Album {
        record("updateAlbum $id $name $description $coverAssetId")
        val i = albums.indexOfFirst { it.id == id }
        albums[i] = albums[i].copy(name = name ?: albums[i].name, description = description ?: albums[i].description, coverAssetId = coverAssetId ?: albums[i].coverAssetId)
        return albums[i]
    }
    override suspend fun deleteAlbum(id: String) { record("deleteAlbum $id"); albums.removeAll { it.id == id } }
    override suspend fun addToAlbum(albumId: String, assetIds: List<String>): Int { record("addToAlbum $albumId ${assetIds.joinToString(",")}"); return assetIds.size }
    override suspend fun removeFromAlbum(albumId: String, assetIds: List<String>): Int { record("removeFromAlbum $albumId ${assetIds.joinToString(",")}"); return assetIds.size }
    override suspend fun createSharedLink(assetIds: List<String>?, albumId: String?, options: ShareLinkOptions): String {
        record("createSharedLink ${assetIds?.joinToString(",")} $albumId ${options.expiresInDays}")
        return "https://photos.example.com/share/Xy7Lq2mNw0pR4sTuV9aB"
    }
    override suspend fun setVisibility(ids: List<String>, visibility: Visibility) = record("setVisibility ${ids.joinToString(",")} ${visibility.wire}")
    override suspend fun trash(ids: List<String>) = record("trash ${ids.joinToString(",")}")
    override suspend fun restore(ids: List<String>) = record("restore ${ids.joinToString(",")}")
    override suspend fun deleteForever(ids: List<String>) = record("deleteForever ${ids.joinToString(",")}")
    override suspend fun restoreAll(): Int { record("restoreAll"); return 3 }
    override suspend fun emptyTrash(): Int { record("emptyTrash"); return 3 }
    override suspend fun trashDays(): Int = 30

    override suspend fun smartSearch(query: String): List<TimelineAsset> {
        record("smartSearch $query")
        return (0 until 4).flatMap { b -> FakeGallery.assetsOf(FakeGallery.buckets[b].key).filterIndexed { i, _ -> (i + b) % 3 == 0 } }
    }
    override suspend fun placeSearch(city: String?, country: String?): List<TimelineAsset> =
        FakeGallery.buckets.take(6).flatMap { FakeGallery.assetsOf(it.key) }.filter { it.city == city || (city == null && it.country == country) }
    override suspend fun explore(): List<ExploreSection> = listOf(
        ExploreSection("exifInfo.city", listOf("Melbourne", "Hobart", "Kyoto", "Lisbon", "Byron Bay", "Queenstown").mapIndexed { i, c -> ExploreItem(c, asset(i % 5, i + 2)) }),
        ExploreSection("smartInfo.tags", listOf("Beach", "Mountains", "Dog", "Food", "Snow", "Night").mapIndexed { i, c -> ExploreItem(c, asset((i + 2) % 6, i)) }),
    )
    override suspend fun suggestions(type: String): List<String> = listOf("Australia", "Japan", "Portugal", "New Zealand")
    override suspend fun people(): List<Person> = listOf("Sam", "Alex", "Mum", "Jordan", "Priya", "Grandpa", "", "", "").mapIndexed { i, n -> Person("p-$i", n, isFavorite = i == 0) }
    override suspend fun mapMarkers(): List<MapMarker> = listOf(MapMarker("m-1", -42.88, 147.32, "Hobart"), MapMarker("m-2", 35.01, 135.76, "Kyoto"))
    override suspend fun assets(ids: List<String>): List<TimelineAsset> = ids.mapNotNull { id -> FakeGallery.buckets.flatMap { FakeGallery.assetsOf(it.key) }.firstOrNull { it.id == id } }
    override suspend fun memories(day: LocalDate): List<Memory> = listOf(1, 3, 6).mapIndexed { i, ago ->
        Memory("mem-$i", day.year - ago, FakeGallery.assetsOf(FakeGallery.buckets[i + 2].key).take(6))
    }
    override suspend fun download(id: String, dir: File, onProgress: (Long, Long) -> Unit): File {
        record("download $id")
        onProgress(10, 10)
        return File(dir, "$id.jpg").apply { parentFile?.mkdirs(); writeBytes(ByteArray(10)) }
    }
}
