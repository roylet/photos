package com.roylet.photos.editor.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.roylet.photos.editor.recipe.HslBand
import com.roylet.photos.editor.recipe.Params
import com.roylet.photos.editor.recipe.Presets
import com.roylet.photos.editor.recipe.Recipe
import com.roylet.photos.editor.recipe.RecipeEdits
import com.roylet.photos.ui.components.brutalDark
import com.roylet.photos.ui.theme.Brutal
import com.roylet.photos.ui.theme.BrutalDark
import com.roylet.photos.ui.theme.Type

private fun hue(deg: Float, s: Float = 0.78f, v: Float = 0.95f) = Color.hsv(((deg % 360f) + 360f) % 360f, s, v)

/** HSL: eight colour-band swatches, then Hue/Saturation/Luminance for the chosen band. */
@Composable
fun HslPanel(session: EditorSession, recipe: State<Recipe>, modifier: Modifier) = HslPanel(session.history, recipe, modifier)

@Composable
fun HslPanel(edits: RecipeEdits, recipe: State<Recipe>, modifier: Modifier) {
    var bandIndex by rememberSaveable { mutableIntStateOf(0) }
    val band = HslBand.entries[bandIndex]
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            for ((i, b) in HslBand.entries.withIndex()) {
                val edited by remember(b) { derivedStateOf { !recipe.value.color[b].isIdentity } }
                Swatch(b, i == bandIndex, edited) { bandIndex = i }
            }
        }
        Spacer(Modifier.height(6.dp))
        val h = band.hueDegrees
        val c = hue(h)
        val tracks = listOf(
            Brush.horizontalGradient(listOf(hue(h - 30f), c, hue(h + 30f))),
            Brush.horizontalGradient(listOf(Color(0xFF8A8580), c)),
            Brush.horizontalGradient(listOf(Color.hsv(((h % 360f) + 360f) % 360f, 0.8f, 0.25f), c, Color.hsv(((h % 360f) + 360f) % 360f, 0.25f, 1f))),
        )
        androidx.compose.runtime.key(band) {
            for ((p, track) in Params.hsl(band).zip(tracks)) SessionSlider(edits, recipe, p, track)
        }
    }
}

@Composable
private fun Swatch(band: HslBand, selected: Boolean, edited: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(2.dp)
            .semantics { contentDescription = band.label; this.selected = selected },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(34.dp)
                    .then(if (selected) Modifier.border(3.dp, BrutalDark.Orange, CircleShape).padding(5.dp) else Modifier.padding(5.dp))
                    .clip(CircleShape).background(hue(band.hueDegrees)).border(2.dp, Color(0xFF111111), CircleShape),
            )
            if (edited) Box(Modifier.align(Alignment.TopEnd).size(8.dp).clip(CircleShape).background(BrutalDark.Text).border(1.5.dp, Color(0xFF111111), CircleShape))
        }
        Text(band.label.take(3).uppercase(), style = Type.Tiny, color = if (selected) BrutalDark.Orange else BrutalDark.MutedText)
    }
}

/** Filters: a strip of preset thumbnails rendered from the proxy, plus the amount slider. */
@Composable
fun FiltersPanel(session: EditorSession, recipe: State<Recipe>, modifier: Modifier) {
    val thumbs by session.filterThumbs.collectAsState()
    FiltersPanel(session.history, thumbs, recipe, modifier)
}

/** The filter strip over any [edits]; [thumbs] are keyed by preset id ("" = none). */
@Composable
fun FiltersPanel(edits: RecipeEdits, thumbs: Map<String, androidx.compose.ui.graphics.ImageBitmap>, recipe: State<Recipe>, modifier: Modifier) {
    val current by remember(recipe) { derivedStateOf { recipe.value.filter?.lutId.orEmpty() } }
    val entries = remember { listOf("" to "None") + Presets.all.map { it.id to it.name } }
    Column(modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = (entries.indexOfFirst { it.first == current } - 1).coerceAtLeast(0))
        LazyRow(state = listState, contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(entries, key = { it.first }) { (id, name) ->
                FilterThumb(name, thumbs[id], selected = id == current) {
                    edits.update { r ->
                        if (id.isEmpty()) r.copy(filter = null)
                        else r.copy(filter = Presets.byId(id)!!.filter(r.filter?.amount ?: 100f))
                    }
                }
            }
        }
        if (current.isNotEmpty()) {
            Box(Modifier.padding(horizontal = 8.dp)) { SessionSlider(edits, recipe, Params.filterAmount) }
        } else {
            Text("Pick a look, then fine-tune its strength.", style = Type.Small, color = BrutalDark.MutedText, modifier = Modifier.padding(horizontal = 16.dp))
        }
    }
}

@Composable
private fun FilterThumb(name: String, image: androidx.compose.ui.graphics.ImageBitmap?, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.width(84.dp).semantics { contentDescription = "$name filter"; this.selected = selected },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(78.dp)
                .brutalDark(fill = BrutalDark.Raised, shadow = if (selected) 0.dp else 4.dp, border = if (selected) BrutalDark.Orange else Brutal.Ink, reserve = 4.dp)
                .then(if (selected) Modifier.border(3.dp, BrutalDark.Orange, RoundedCornerShape(6.dp)) else Modifier)
                .clickable(onClick = onClick),
        ) {
            if (image != null) Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Spacer(Modifier.height(2.dp))
        Text(name.uppercase(), style = Type.Tiny, color = if (selected) BrutalDark.Orange else BrutalDark.Text, maxLines = 1)
    }
}
