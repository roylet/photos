package com.roylet.photos.merge

import com.roylet.photos.domain.library.Album
import com.roylet.photos.domain.library.AssetDetails
import com.roylet.photos.domain.library.DeleteTarget
import com.roylet.photos.domain.library.ExploreSection
import com.roylet.photos.domain.library.LibrarySource
import com.roylet.photos.domain.library.LocalLibrary
import com.roylet.photos.domain.library.MapMarker
import com.roylet.photos.domain.library.Memory
import com.roylet.photos.domain.library.Person
import com.roylet.photos.domain.library.ShareLinkOptions
import com.roylet.photos.domain.library.SharedUri
import com.roylet.photos.domain.library.TargetedTrash
import com.roylet.photos.domain.library.TrashReceipt
import com.roylet.photos.domain.library.UnsupportedCapabilityException
import com.roylet.photos.domain.source.AssetRef
import com.roylet.photos.domain.source.Capabilities
import com.roylet.photos.domain.timeline.TimelineAsset
import com.roylet.photos.domain.timeline.TimelineFilter
import com.roylet.photos.domain.timeline.Visibility
import java.io.File
import java.time.LocalDate

/**
 * The library with a server connected: the phone and Immich together (docs/SOURCES.md §9).
 * Calls about assets go to the source in each id; a phone item's Immich copy (and the other way
 * round) comes from the matcher's cache, so:
 * - favourite applies to both copies;
 * - delete goes where the user chose ([TargetedTrash]); a plain [trash] only the id's own copy;
 * - server features (albums, share links, archive) use the Immich copy, and skip phone-only items;
 * - albums are Immich's albums followed by the phone's folders (`folder:`);
 * - search is Immich's smart search plus phone file names; details prefer the phone's EXIF and
 *   add Immich's people, place and description.
 */
class MergedLibrary(
    private val immich: LibrarySource,
    private val local: LocalLibrary,
    private val pairs: () -> MonthMatcher,
) : LibrarySource, TargetedTrash {

    override val capabilities: Capabilities = local.capabilities union immich.capabilities

    /* ---------------- Ids ---------------- */

    private data class Split(val local: List<String>, val remote: List<String>)

    private fun split(ids: List<String>): Split {
        val (l, r) = ids.partition(AssetRef::isLocal)
        return Split(l, r)
    }

    private fun localKey(id: String): Long? = AssetRef.parse(id)?.mediaStoreId

    /** Phone id → its Immich copy, for the ones that have one. */
    private suspend fun remoteCopies(localIds: List<String>): Map<String, String> {
        val keys = localIds.mapNotNull { id -> localKey(id)?.let { it to id } }.toMap()
        if (keys.isEmpty()) return emptyMap()
        return runCatching { pairs().cachedRemote(keys.keys.toList()) }.getOrDefault(emptyMap())
            .mapNotNull { (k, m) -> keys[k]?.let { it to m.remoteId } }.toMap()
    }

    /** Immich id → its phone copy (`local:` id). */
    private suspend fun localCopies(remoteIds: List<String>): Map<String, String> {
        if (remoteIds.isEmpty()) return emptyMap()
        return runCatching { pairs().cachedLocal(remoteIds) }.getOrDefault(emptyMap()).mapValues { AssetRef.local(it.value).id }
    }

    /** Immich ids for [ids]: Immich ones as they are, phone ones through their copy; phone-only items are left out. */
    private suspend fun remoteIdsOf(ids: List<String>): List<String> {
        val s = split(ids)
        val copies = remoteCopies(s.local)
        return (s.remote + s.local.mapNotNull { copies[it] }).distinct()
    }

    /**
     * The pairs a delete may act on both sides of: only SHA-1-confirmed ones. A guessed pair
     * (same name, time and size) might be two different photos, so it never drives a delete of
     * the other copy. Returns (confirmed phone → Immich, confirmed Immich → phone, guessed ids).
     */
    private suspend fun confirmedPairs(s: Split): Triple<Map<String, String>, Map<String, String>, Set<String>> {
        val keys = s.local.mapNotNull { id -> localKey(id)?.let { it to id } }.toMap()
        val remote = if (keys.isEmpty()) emptyMap() else runCatching { pairs().cachedRemote(keys.keys.toList()) }.getOrDefault(emptyMap())
        val local = if (s.remote.isEmpty()) emptyMap() else runCatching { pairs().cachedLocalMatches(s.remote) }.getOrDefault(emptyMap())
        val guessed = HashSet<String>()
        val ofLocal = HashMap<String, String>()
        for ((k, m) in remote) { val id = keys[k] ?: continue; if (m.tentative) guessed += id else ofLocal[id] = m.remoteId }
        val ofRemote = HashMap<String, String>()
        for ((r, m) in local) if (m.tentative) guessed += r else ofRemote[r] = AssetRef.local(m.localId).id
        return Triple(ofLocal, ofRemote, guessed)
    }

    /** Where a phone item should be read from: itself, or for an Immich id its phone copy when known. */
    private suspend fun preferLocal(id: String): String = if (AssetRef.isLocal(id)) id else localCopies(listOf(id))[id] ?: id

    /* ---------------- Favourite ---------------- */

    override suspend fun setFavorite(ids: List<String>, favorite: Boolean) {
        val s = split(ids)
        val toRemote = remoteCopies(s.local)
        val toLocal = localCopies(s.remote)
        val phone = (s.local + toLocal.values).distinct()
        val server = (s.remote + toRemote.values).distinct()
        // The phone first: it may ask for consent, and a "no" should leave Immich untouched.
        if (phone.isNotEmpty()) local.setFavorite(phone, favorite)
        if (server.isNotEmpty()) immich.setFavorite(server, favorite)
    }

    /* ---------------- Trash ---------------- */

    /** Items whose other copy is confirmed (SHA-1): only these get the "delete from" choice. */
    override suspend fun pairedIds(ids: List<String>): Set<String> {
        val (ofLocal, ofRemote, _) = confirmedPairs(split(ids))
        return ofLocal.keys + ofRemote.keys
    }

    override suspend fun trash(ids: List<String>, target: DeleteTarget): TrashReceipt {
        val s = split(ids)
        val (ofLocal, ofRemote, guessed) = confirmedPairs(s)
        val plan = plan(s.local, s.remote, ofLocal, ofRemote, target, guessed)
        if (plan.local.isNotEmpty()) local.trash(plan.local)
        if (plan.remote.isNotEmpty()) {
            try {
                immich.trash(plan.remote)
            } catch (e: Exception) {
                // Keep the two sides in step: undo the phone half before reporting the failure.
                if (plan.local.isNotEmpty()) runCatching { local.restore(plan.local) }
                throw e
            }
        }
        if (plan.remote.isNotEmpty()) runCatching { pairs().forgetRemote(plan.remote) }
        return plan
    }

    override suspend fun restore(receipt: TrashReceipt) {
        if (receipt.local.isNotEmpty()) local.restore(receipt.local)
        if (receipt.remote.isNotEmpty()) immich.restore(receipt.remote)
    }

    /** A plain trash (no choice asked): each id's own copy. */
    override suspend fun trash(ids: List<String>) {
        val s = split(ids)
        if (s.local.isNotEmpty()) local.trash(s.local)
        if (s.remote.isNotEmpty()) immich.trash(s.remote)
    }

    override suspend fun restore(ids: List<String>) {
        val s = split(ids)
        if (s.local.isNotEmpty()) local.restore(s.local)
        if (s.remote.isNotEmpty()) immich.restore(s.remote)
    }

    override suspend fun deleteForever(ids: List<String>) {
        val s = split(ids)
        if (s.local.isNotEmpty()) local.deleteForever(s.local)
        if (s.remote.isNotEmpty()) immich.deleteForever(s.remote)
    }

    override suspend fun restoreAll(): Int = local.restoreAll() + immich.restoreAll()
    override suspend fun emptyTrash(): Int = local.emptyTrash() + immich.emptyTrash()
    override suspend fun trashDays(): Int? = immich.trashDays()

    /* ---------------- Albums ---------------- */

    /** Immich albums, then the phone's folders. */
    override suspend fun albums(): List<Album> = immich.albums() + runCatching { local.albums() }.getOrDefault(emptyList())

    override suspend fun album(id: String): Album = if (id.startsWith(TimelineFilter.FOLDER_PREFIX)) local.album(id) else immich.album(id)

    override suspend fun albumsContaining(assetIds: List<String>): Set<String> =
        remoteIdsOf(assetIds).takeIf { it.size == assetIds.size }?.let { immich.albumsContaining(it) }.orEmpty()

    override suspend fun createAlbum(name: String, assetIds: List<String>): Album = immich.createAlbum(name, remoteIdsOf(assetIds))
    override suspend fun updateAlbum(id: String, name: String?, description: String?, coverAssetId: String?): Album =
        immich.updateAlbum(id, name, description, coverAssetId?.let { remoteIdsOf(listOf(it)).firstOrNull() ?: throw UnsupportedCapabilityException("A cover from this phone") })
    override suspend fun deleteAlbum(id: String) = immich.deleteAlbum(id)
    override suspend fun addToAlbum(albumId: String, assetIds: List<String>): Int {
        val ids = remoteIdsOf(assetIds)
        if (ids.isEmpty()) throw UnsupportedCapabilityException("Adding photos that are only on this phone")
        return immich.addToAlbum(albumId, ids)
    }
    override suspend fun removeFromAlbum(albumId: String, assetIds: List<String>): Int = immich.removeFromAlbum(albumId, remoteIdsOf(assetIds))

    override suspend fun createSharedLink(assetIds: List<String>?, albumId: String?, options: ShareLinkOptions): String =
        immich.createSharedLink(assetIds?.let { remoteIdsOf(it) }, albumId, options)

    override suspend fun setVisibility(ids: List<String>, visibility: Visibility) = immich.setVisibility(remoteIdsOf(ids), visibility)

    /* ---------------- Search ---------------- */

    /** Immich's smart search, then phone items whose file name matches and that aren't already shown. */
    override suspend fun smartSearch(query: String): List<TimelineAsset> {
        val server = immich.smartSearch(query)
        val phone = runCatching { local.basicSearch(query) }.getOrDefault(emptyList())
        return dedupe(server, phone)
    }

    override suspend fun basicSearch(query: String): List<TimelineAsset> =
        dedupe(runCatching { immich.basicSearch(query) }.getOrDefault(emptyList()), local.basicSearch(query))

    private suspend fun dedupe(server: List<TimelineAsset>, phone: List<TimelineAsset>): List<TimelineAsset> {
        if (phone.isEmpty()) return server
        val shown = localCopies(server.map { it.id }).values.toHashSet()
        return server + phone.filter { it.id !in shown }
    }

    override suspend fun placeSearch(city: String?, country: String?): List<TimelineAsset> = immich.placeSearch(city, country)
    override suspend fun explore(): List<ExploreSection> = immich.explore()
    override suspend fun suggestions(type: String): List<String> = immich.suggestions(type)
    override suspend fun people(): List<Person> = immich.people()
    override suspend fun mapMarkers(): List<MapMarker> = immich.mapMarkers()
    override suspend fun memories(day: LocalDate): List<Memory> = immich.memories(day)

    /* ---------------- One asset ---------------- */

    /** The phone's details (EXIF straight from the file) when there's a phone copy, filled in with Immich's people, place and description. */
    override suspend fun details(id: String): AssetDetails? {
        val phoneId = preferLocal(id)
        val remoteId = if (AssetRef.isLocal(id)) remoteCopies(listOf(id))[id] else id
        val phone = if (AssetRef.isLocal(phoneId)) runCatching { local.details(phoneId) }.getOrNull() else null
        val server = remoteId?.let { runCatching { immich.details(it) }.getOrNull() }
        return combineDetails(phone, server)
    }

    override suspend fun assets(ids: List<String>): List<TimelineAsset> {
        val s = split(ids)
        val found = (local.assets(s.local) + immich.assets(s.remote)).associateBy { it.id }
        return ids.mapNotNull { found[it] }
    }

    /** Shares the phone file when there is one (no download). */
    override suspend fun shareUri(id: String): SharedUri? {
        val phone = preferLocal(id)
        return if (AssetRef.isLocal(phone)) local.shareUri(phone) else immich.shareUri(id)
    }

    override suspend fun download(id: String, dir: File, onProgress: (Long, Long) -> Unit): File {
        val phone = preferLocal(id)
        if (AssetRef.isLocal(phone)) runCatching { return local.download(phone, dir, onProgress) }
        return immich.download(id, dir, onProgress)
    }

    companion object {
        /**
         * Which copies a delete trashes. Confirmed pairs ([remoteOfLocal], [localOfRemote]) follow
         * [target]; one-sided items always go from the side they're on. [guessed] items (a
         * tentative pair: the other copy is only a guess) only ever lose their own copy, and only
         * when [target] includes their side: "Immich" never trashes a phone file that only looks
         * backed up, and the guessed Immich asset is never touched from a phone item.
         */
        fun plan(
            localIds: List<String>,
            remoteIds: List<String>,
            remoteOfLocal: Map<String, String>,
            localOfRemote: Map<String, String>,
            target: DeleteTarget,
            guessed: Set<String> = emptySet(),
        ): TrashReceipt {
            val l = LinkedHashSet<String>()
            val r = LinkedHashSet<String>()
            var partial = false
            fun pairOf(phone: String, server: String) {
                when (target) {
                    DeleteTarget.Device -> { l += phone; partial = true }
                    DeleteTarget.Immich -> { r += server; partial = true }
                    DeleteTarget.Both -> { l += phone; r += server }
                }
            }
            for (id in localIds) {
                val other = remoteOfLocal[id]
                when {
                    other != null -> pairOf(id, other)
                    id in guessed -> if (target != DeleteTarget.Immich) { l += id; partial = true } else partial = true
                    else -> l += id
                }
            }
            for (id in remoteIds) {
                val other = localOfRemote[id]
                when {
                    other != null -> pairOf(other, id)
                    id in guessed -> if (target != DeleteTarget.Device) { r += id; partial = true } else partial = true
                    else -> r += id
                }
            }
            return TrashReceipt(l.toList(), r.toList(), partial)
        }

        /** The phone's fields win (EXIF from the file itself); Immich adds what the phone doesn't know. */
        fun combineDetails(phone: AssetDetails?, server: AssetDetails?): AssetDetails? {
            if (phone == null) return server
            if (server == null) return phone
            return phone.copy(
                description = phone.description ?: server.description,
                camera = phone.camera ?: server.camera,
                location = when {
                    phone.location == null -> server.location
                    server.location == null -> phone.location
                    else -> phone.location.copy(
                        city = phone.location.city ?: server.location.city,
                        state = phone.location.state ?: server.location.state,
                        country = phone.location.country ?: server.location.country,
                        latitude = phone.location.latitude ?: server.location.latitude,
                        longitude = phone.location.longitude ?: server.location.longitude,
                    )
                },
                people = phone.people.ifEmpty { server.people },
                timeZone = phone.timeZone ?: server.timeZone,
            )
        }
    }
}
