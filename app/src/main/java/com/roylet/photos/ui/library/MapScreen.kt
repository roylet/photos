package com.roylet.photos.ui.library

import android.graphics.PointF
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.roylet.photos.domain.library.LibrarySource
import com.roylet.photos.domain.library.MapMarker
import com.roylet.photos.immich.ImmichApi
import com.roylet.photos.ui.components.BrutalButton
import com.roylet.photos.ui.components.BrutalSpinner
import com.roylet.photos.ui.components.EmptyState
import com.roylet.photos.ui.components.ScreenHeader
import com.roylet.photos.ui.components.brutal
import com.roylet.photos.ui.theme.Brutal
import com.roylet.photos.ui.theme.Palette
import com.roylet.photos.ui.theme.Type
import com.roylet.photos.util.Format
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/**
 * A free OSM-based vector style. Tiles are fetched by MapLibre's own HTTP stack, never the
 * app's shared client, so the Immich API key can't reach the tile host (the shared client's
 * ApiKeyGuard would strip it anyway: it only keys requests for the server's own addresses).
 */
const val MAP_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

/** OpenFreeMap's dark style (same host, same tiles), used in dark mode. */
const val MAP_STYLE_URL_DARK = "https://tiles.openfreemap.org/styles/dark"

/** Places: every geotagged photo on a clustered map; tap a cluster or pin to see its photos. */
@Composable
fun MapScreen(library: () -> LibrarySource, onPick: (ids: List<String>, title: String) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    var markers by remember { mutableStateOf<List<MapMarker>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(attempt) {
        error = null
        runCatching { library().mapMarkers() }.onSuccess { markers = it }.onFailure { error = ImmichApi.describe(it) }
    }
    Column(modifier.fillMaxSize().background(Palette.paper)) {
        ScreenHeader("Places", subtitle = markers?.let { Format.plural(it.size, "geotagged photo") }, onBack = onBack)
        val list = markers
        Box(Modifier.weight(1f)) {
            when {
                list == null && error != null -> EmptyState(Icons.Rounded.CloudOff, "Couldn't load the map", error.orEmpty()) { BrutalButton("Try again", { attempt++ }) }
                list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { BrutalSpinner(size = 32.dp) }
                list.isEmpty() -> EmptyState(Icons.Rounded.Map, "No places yet", "Photos with GPS locations show up here.")
                // Robolectric and previews can't load MapLibre's native renderer.
                LocalInspectionMode.current || Build.FINGERPRINT == "robolectric" -> EmptyState(Icons.Rounded.Map, "Map", Format.plural(list.size, "place"))
                else -> {
                    ClusterMap(list, onPick)
                    Text(
                        "TAP A CLUSTER TO SEE ITS PHOTOS", style = Type.Tiny, color = Palette.ink,
                        modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp)
                            .brutal(Palette.card, shadow = 3.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ClusterMap(markers: List<MapMarker>, onPick: (List<String>, String) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val pick by rememberUpdatedState(onPick)
    val scope = rememberCoroutineScope()
    val colors = Palette.current
    val dark = colors.dark
    val look = MarkerLook(
        clusterStroke = colors.border.toArgb(),
        pointFill = (if (dark) colors.ink else colors.paper).toArgb(),
        pointStroke = (if (dark) colors.paper else colors.ink).toArgb(),
    )
    // The source of the style currently shown, for the click listener (registered once).
    val source = remember { arrayOfNulls<GeoJsonSource>(1) }
    var framed by remember { mutableStateOf<List<MapMarker>?>(null) }
    val view = remember {
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(null) }
    }
    DisposableEffect(lifecycle, view) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> view.onStart()
                Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                Lifecycle.Event.ON_STOP -> view.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); view.onStop(); view.onDestroy() }
    }
    LaunchedEffect(Unit) {
        view.getMapAsync { map -> addClickListener(map, { source[0] }) { ids, title -> scope.launch { pick(ids, title) } } }
    }
    LaunchedEffect(markers, dark) {
        view.getMapAsync { map ->
            map.setStyle(Style.Builder().fromUri(if (dark) MAP_STYLE_URL_DARK else MAP_STYLE_URL)) { style ->
                source[0] = addMarkers(map, style, markers, look, frame = framed != markers)
                framed = markers
            }
        }
    }
    AndroidView({ view }, Modifier.fillMaxSize())
}

private const val SOURCE = "photos"

/** Marker colours for the current theme (clusters stay orange with ink counts). */
private class MarkerLook(val clusterStroke: Int, val pointFill: Int, val pointStroke: Int)

private fun addMarkers(map: MapLibreMap, style: Style, markers: List<MapMarker>, look: MarkerLook, frame: Boolean): GeoJsonSource {
    val features = markers.map { m ->
        Feature.fromGeometry(Point.fromLngLat(m.lon, m.lat)).apply {
            addStringProperty("id", m.id)
            addStringProperty("place", m.city ?: m.country ?: "")
        }
    }
    val source = GeoJsonSource(SOURCE, FeatureCollection.fromFeatures(features), GeoJsonOptions().withCluster(true).withClusterMaxZoom(15).withClusterRadius(48))
    style.addSource(source)
    val orange = Brutal.Orange.toArgb()
    val ink = Brutal.Ink.toArgb()
    style.addLayer(
        CircleLayer("clusters", SOURCE).withFilter(Expression.has("point_count")).withProperties(
            PropertyFactory.circleColor(orange),
            PropertyFactory.circleStrokeColor(look.clusterStroke),
            PropertyFactory.circleStrokeWidth(2.5f),
            PropertyFactory.circleRadius(Expression.step(Expression.get("point_count"), Expression.literal(16), Expression.stop(10, 20), Expression.stop(100, 26), Expression.stop(1000, 32))),
        ),
    )
    style.addLayer(
        SymbolLayer("counts", SOURCE).withFilter(Expression.has("point_count")).withProperties(
            PropertyFactory.textField(Expression.toString(Expression.get("point_count_abbreviated"))),
            PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
            PropertyFactory.textSize(13f),
            PropertyFactory.textColor(ink),
            PropertyFactory.textAllowOverlap(true),
        ),
    )
    style.addLayer(
        CircleLayer("points", SOURCE).withFilter(Expression.not(Expression.has("point_count"))).withProperties(
            PropertyFactory.circleColor(look.pointFill),
            PropertyFactory.circleStrokeColor(look.pointStroke),
            PropertyFactory.circleStrokeWidth(2.5f),
            PropertyFactory.circleRadius(8f),
        ),
    )
    if (!frame) {
        // A theme switch only restyles; keep the user's pan and zoom.
    } else if (markers.size > 1) {
        val bounds = LatLngBounds.Builder().includes(markers.map { LatLng(it.lat, it.lon) }).build()
        runCatching { map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 80)) }
    } else markers.firstOrNull()?.let { map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(it.lat, it.lon), 11.0)) }
    return source
}

private fun addClickListener(map: MapLibreMap, source: () -> GeoJsonSource?, onPick: (List<String>, String) -> Unit) {
    map.addOnMapClickListener { latLng ->
        val source = source() ?: return@addOnMapClickListener false
        val p: PointF = map.projection.toScreenLocation(latLng)
        val hit = map.queryRenderedFeatures(p, "clusters", "points").firstOrNull() ?: return@addOnMapClickListener false
        if (hit.hasProperty("point_count")) {
            val leaves = source.getClusterLeaves(hit, 500, 0).features().orEmpty()
            val ids = leaves.mapNotNull { it.getStringProperty("id") }
            val place = leaves.mapNotNull { it.getStringProperty("place")?.takeIf { s -> s.isNotBlank() } }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            onPick(ids, place ?: "${ids.size} photos")
        } else {
            onPick(listOfNotNull(hit.getStringProperty("id")), hit.getStringProperty("place")?.ifBlank { null } ?: "Photo")
        }
        true
    }
}
