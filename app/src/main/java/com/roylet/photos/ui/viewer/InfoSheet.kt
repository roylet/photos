package com.roylet.photos.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Camera
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.roylet.photos.domain.timeline.TimelineAsset
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.People
import com.roylet.photos.domain.library.AssetDetails
import com.roylet.photos.ui.components.BrutalSpinner
import com.roylet.photos.ui.components.DarkIconButton
import com.roylet.photos.ui.components.brutalDark
import com.roylet.photos.ui.theme.BrutalDark
import com.roylet.photos.ui.theme.Type
import com.roylet.photos.util.Format
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/** Wall-clock date/time of an asset, in the photographer's local time. */
internal fun takenDate(asset: TimelineAsset, pattern: String): String =
    SimpleDateFormat(pattern, Locale.getDefault()).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(asset.localTakenAt))

/**
 * The info sheet's content: when, what file, which camera and settings, where, who. [details]
 * comes from the asset's source (Immich or the phone) and is null while loading; the timeline's
 * own fields fill in until then.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AssetInfoPanel(asset: TimelineAsset, details: AssetDetails?, loading: Boolean, onClose: () -> Unit, modifier: Modifier = Modifier) {
    var drag by remember { mutableFloatStateOf(0f) }
    val camera = details?.camera
    val location = details?.location
    Column(
        modifier
            .fillMaxWidth()
            .offset { IntOffset(0, drag.roundToInt().coerceAtLeast(0)) }
            .brutalDark(fill = BrutalDark.Panel, shape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp), shadow = 0.dp)
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        // Grab handle: drag down to close.
        Box(
            Modifier.fillMaxWidth().height(28.dp).pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = { if (drag > 120f) onClose(); drag = 0f },
                    onDragCancel = { drag = 0f },
                ) { c, dy -> c.consume(); drag += dy }
            },
            contentAlignment = Alignment.Center,
        ) { Box(Modifier.size(44.dp, 5.dp).background(BrutalDark.MutedText, RoundedCornerShape(50))) }

        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 12.dp, bottom = 18.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(takenDate(asset, "EEEE d MMMM yyyy").uppercase(), style = Type.Label, color = BrutalDark.Orange)
                    Spacer(Modifier.height(2.dp))
                    Text(takenDate(asset, "HH:mm"), style = Type.Huge, color = BrutalDark.Text)
                    val tz = details?.timeZone ?: offsetLabel(asset.localOffsetHours)
                    Text(tz, style = Type.Small, color = BrutalDark.MutedText)
                }
                DarkIconButton(Icons.Rounded.Close, "Close info", onClose)
            }
            details?.description?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, style = Type.Body, color = BrutalDark.Text)
            }
            Spacer(Modifier.height(14.dp))

            val name = details?.fileName?.takeIf { it.isNotBlank() }
            val w = details?.width
            val h = details?.height
            val dims = details?.megapixels?.let { mp -> "$w × $h · ${"%.1f".format(mp)} MP" }
            val size = details?.sizeBytes?.let { Format.bytes(it) }
            val duration = (details?.durationMs ?: asset.durationMs)?.let { Format.durationMillis(it) }
            InfoRow(Icons.Rounded.Image, name ?: if (loading) "Loading…" else "Unknown file", listOfNotNull(dims, size, duration).joinToString(" · ").ifEmpty { null })
            details?.folder?.takeIf { it.isNotBlank() }?.let { InfoRow(Icons.Rounded.Folder, it.trimEnd('/'), "On this device") }

            val cameraLabel = listOfNotNull(camera?.make, camera?.model).joinToString(" ").trim().ifEmpty { null }
            if (cameraLabel != null) {
                InfoRow(Icons.Rounded.CameraAlt, cameraName(camera?.make, camera?.model) ?: cameraLabel, null)
            }
            camera?.lens?.takeIf { it.isNotBlank() }?.let { InfoRow(Icons.Rounded.Camera, it, null) }
            val settings = listOfNotNull(
                camera?.fNumber?.let { "ƒ/${trim(it)}" },
                camera?.exposureTime?.let { "${it}s" },
                camera?.iso?.let { "ISO $it" },
                camera?.focalLengthMm?.let { "${trim(it)} mm" },
            )
            if (settings.isNotEmpty()) {
                FlowRow(Modifier.padding(start = 46.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    settings.forEach { s ->
                        Text(
                            s, style = Type.Label, color = BrutalDark.Text,
                            modifier = Modifier.background(BrutalDark.Raised, RoundedCornerShape(4.dp)).border(2.dp, BrutalDark.Line, RoundedCornerShape(4.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
            }

            val city = location?.city ?: asset.city
            val country = location?.country ?: asset.country
            val lat = location?.latitude
            val lon = location?.longitude
            val coords = if (lat != null && lon != null) "%.4f, %.4f".format(lat, lon) else null
            val place = listOfNotNull(city, location?.state, country).distinct().joinToString(", ").ifEmpty { null }
            if (place != null || coords != null) InfoRow(Icons.Rounded.Place, place ?: coords!!, if (place != null) coords else null)
            details?.people?.takeIf { it.isNotEmpty() }?.let { InfoRow(Icons.Rounded.People, it.joinToString(", "), null) }
            if (loading && details == null) {
                Spacer(Modifier.height(6.dp))
                BrutalSpinner(Modifier.padding(start = 46.dp))
            }
        }
    }
}

@Composable
private fun InfoRow(icon: ImageVector, title: String, subtitle: String?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(34.dp).background(BrutalDark.Raised, RoundedCornerShape(6.dp)).border(2.dp, BrutalDark.Line, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = BrutalDark.Text, modifier = Modifier.size(18.dp)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.Title, color = BrutalDark.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = Type.Small, color = BrutalDark.MutedText, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** "Apple" + "iPhone 15 Pro" -> "iPhone 15 Pro"; avoids "Canon Canon EOS R5". */
internal fun cameraName(make: String?, model: String?): String? {
    if (model.isNullOrBlank()) return make?.takeIf { it.isNotBlank() }
    if (make.isNullOrBlank() || model.startsWith(make, ignoreCase = true) || make.equals("Apple", true)) return model
    return "$make $model"
}

private fun trim(d: Double): String = if (d % 1.0 == 0.0) d.toInt().toString() else "%.1f".format(d)

private fun offsetLabel(hours: Double): String {
    val total = (hours * 60).roundToInt()
    val sign = if (total < 0) "−" else "+"
    val a = kotlin.math.abs(total)
    return "UTC$sign${a / 60}${if (a % 60 != 0) ":%02d".format(a % 60) else ""}"
}
