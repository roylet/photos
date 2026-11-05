package com.roylet.photos.domain.source

/*
 * Pure-Kotlin source model: where an asset lives (this phone's MediaStore or an Immich server),
 * how its id says so, and what each source can do. See docs/SOURCES.md.
 */

/** Where an asset's bytes come from. */
enum class MediaSourceKind(val prefix: String) {
    /** This phone's MediaStore. The key is the MediaStore `_ID` (a Long, as a string). */
    Local("local"),
    /** An Immich server. The key is the asset UUID. */
    Immich("immich"),
}

/**
 * A source-tagged asset id: `local:123` or `immich:<uuid>`. Plain ids without a prefix are Immich
 * UUIDs (everything the app stored before 3a: deep links, nav arguments, tests), so the Immich
 * code keeps working unchanged and can move to tagged ids gradually.
 */
data class AssetRef(val kind: MediaSourceKind, val key: String) {
    init { require(key.isNotEmpty()) { "empty asset key" } }

    /** The tagged form, e.g. `local:123`. */
    fun encode(): String = "${kind.prefix}:$key"

    /**
     * The id screens and timelines use for this asset. Immich keeps the bare UUID (so its URLs,
     * API calls and caches are untouched); local ids are always tagged.
     */
    val id: String get() = if (kind == MediaSourceKind.Immich) key else encode()

    val isLocal: Boolean get() = kind == MediaSourceKind.Local
    val isImmich: Boolean get() = kind == MediaSourceKind.Immich

    /** The MediaStore `_ID`, for local refs. */
    val mediaStoreId: Long? get() = if (isLocal) key.toLongOrNull() else null

    override fun toString(): String = encode()

    companion object {
        fun local(mediaStoreId: Long) = AssetRef(MediaSourceKind.Local, mediaStoreId.toString())
        fun immich(uuid: String) = AssetRef(MediaSourceKind.Immich, uuid)

        /**
         * Parses any asset id. `local:` and `immich:` prefixes select the source; anything else
         * (a bare UUID) is Immich. Never throws for a non-empty id; returns null for an empty one
         * or a tagged id with nothing after the colon.
         */
        fun parse(id: String): AssetRef? {
            if (id.isEmpty()) return null
            val colon = id.indexOf(':')
            if (colon > 0) {
                val kind = MediaSourceKind.entries.firstOrNull { it.prefix == id.substring(0, colon) }
                if (kind != null) return id.substring(colon + 1).takeIf { it.isNotEmpty() }?.let { AssetRef(kind, it) }
            }
            return AssetRef(MediaSourceKind.Immich, id)
        }

        /** The source of [id] (Immich for bare UUIDs). */
        fun kindOf(id: String): MediaSourceKind = parse(id)?.kind ?: MediaSourceKind.Immich

        fun isLocal(id: String): Boolean = id.startsWith("${MediaSourceKind.Local.prefix}:")

        /** The Immich UUID behind [id], or null when it's a local asset. */
        fun immichIdOrNull(id: String): String? = parse(id)?.takeIf { it.isImmich }?.key
    }
}

/** Which sources hold an asset. A merged timeline (agent C) shows this as a badge on the cell. */
enum class SourceBadge { LocalOnly, RemoteOnly, Both }

/** What a source (or the active combination of sources) can do; the UI hides what's missing. */
enum class Capability {
    SHARE_LINKS, PEOPLE, MAP, SMART_SEARCH, ARCHIVE, STACKS, SERVER_ALBUMS, FOLDERS, TRASH, FAVORITE, MEMORIES, EDIT_SAVE,
}

/** An immutable set of [Capability] flags. */
@JvmInline
value class Capabilities(private val bits: Int) {
    operator fun contains(c: Capability): Boolean = bits and (1 shl c.ordinal) != 0
    fun has(c: Capability): Boolean = c in this

    operator fun plus(c: Capability) = Capabilities(bits or (1 shl c.ordinal))
    operator fun minus(c: Capability) = Capabilities(bits and (1 shl c.ordinal).inv())
    operator fun minus(other: Capabilities) = Capabilities(bits and other.bits.inv())
    /** What either side can do (e.g. phone + Immich together). */
    infix fun union(other: Capabilities) = Capabilities(bits or other.bits)
    /** What both sides can do. */
    infix fun intersect(other: Capabilities) = Capabilities(bits and other.bits)

    fun toSet(): Set<Capability> = Capability.entries.filterTo(LinkedHashSet()) { it in this }
    val isEmpty: Boolean get() = bits == 0

    override fun toString(): String = toSet().joinToString(prefix = "Capabilities(", postfix = ")")

    companion object {
        fun of(vararg c: Capability) = Capabilities(c.fold(0) { acc, x -> acc or (1 shl x.ordinal) })
        fun of(c: Collection<Capability>) = Capabilities(c.fold(0) { acc, x -> acc or (1 shl x.ordinal) })

        val None = Capabilities(0)
        val All = of(Capability.entries)

        /** Immich: everything except phone folders. */
        val Immich = All - Capability.FOLDERS

        /** The phone's MediaStore: folders, favourite and trash (Android 11+). Agents B/C add more as they land. */
        val Local = of(Capability.FOLDERS, Capability.TRASH, Capability.FAVORITE)

        /**
         * What only works on an asset Immich has (adding a local-only photo to a server album or
         * a share link needs an upload first). [forAsset] drops these for local-only assets.
         */
        val NeedsRemoteAsset = of(Capability.SHARE_LINKS, Capability.ARCHIVE, Capability.STACKS, Capability.SERVER_ALBUMS, Capability.PEOPLE)

        /** What only works on an asset that's on this phone. */
        val NeedsLocalAsset = of(Capability.FOLDERS)
    }
}

/**
 * What the app can do with one asset: the active [capabilities] minus what its sources lack.
 * In today's single-source configurations this is just [capabilities]; it matters once the merged
 * timeline mixes phone-only and Immich-only items.
 */
fun Capabilities.forAsset(id: String, localRef: AssetRef? = null, remoteRef: AssetRef? = null): Capabilities {
    val primary = AssetRef.parse(id)
    val hasLocal = localRef != null || primary?.isLocal == true
    val hasRemote = remoteRef != null || primary?.isImmich == true
    var c = this
    if (!hasRemote) c -= Capabilities.NeedsRemoteAsset
    if (!hasLocal) c -= Capabilities.NeedsLocalAsset
    return c
}

/**
 * The sources the app is showing right now and what they can do together. Today that's either
 * the phone alone (no server) or Immich alone; the merged timeline (agent C) makes it both.
 */
data class ActiveSources(val kinds: Set<MediaSourceKind>, val capabilities: Capabilities) {
    val hasLocal: Boolean get() = MediaSourceKind.Local in kinds
    val hasImmich: Boolean get() = MediaSourceKind.Immich in kinds

    companion object {
        val LocalOnly = ActiveSources(setOf(MediaSourceKind.Local), Capabilities.Local)
        val ImmichOnly = ActiveSources(setOf(MediaSourceKind.Immich), Capabilities.Immich)
    }
}
