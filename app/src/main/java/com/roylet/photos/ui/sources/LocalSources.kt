package com.roylet.photos.ui.sources

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import com.roylet.photos.domain.source.ActiveSources
import com.roylet.photos.domain.source.Capabilities
import com.roylet.photos.domain.source.Capability
import com.roylet.photos.domain.source.forAsset
import com.roylet.photos.domain.timeline.TimelineAsset

/**
 * Which sources the app is showing (phone, Immich, later both). AppNav provides
 * `AppContainer.sources()`; previews and screenshots default to Immich alone, as before 3a.
 */
val LocalActiveSources = staticCompositionLocalOf { ActiveSources.ImmichOnly }

/**
 * What the active sources can do together. Screens hide or disable features that aren't in it;
 * per-asset actions use [assetCapabilities]. Provided by AppNav alongside [LocalActiveSources].
 */
val LocalCapabilities = staticCompositionLocalOf { Capabilities.Immich }

/** Shorthand for `capability in LocalCapabilities.current`. */
@Composable
@ReadOnlyComposable
fun can(capability: Capability): Boolean = capability in LocalCapabilities.current

/** What the app can do with [asset] (null: just the active capabilities). */
@Composable
@ReadOnlyComposable
fun assetCapabilities(asset: TimelineAsset?): Capabilities {
    val all = LocalCapabilities.current
    return asset?.let { all.forAsset(it.id, it.localRef, it.remoteRef) } ?: all
}

/** What the app can do with all of [ids] (a selection): the intersection of each asset's capabilities. */
fun Capabilities.forIds(ids: Collection<String>): Capabilities =
    ids.fold(this) { acc, id -> acc intersect this.forAsset(id) }
