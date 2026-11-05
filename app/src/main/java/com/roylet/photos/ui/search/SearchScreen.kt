package com.roylet.photos.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.roylet.photos.domain.library.Person
import com.roylet.photos.domain.timeline.TimelineAsset
import com.roylet.photos.domain.timeline.TimelineFilter
import com.roylet.photos.domain.timeline.TimelineRepository
import com.roylet.photos.ui.components.BrutalButton
import com.roylet.photos.ui.components.BrutalSpinner
import com.roylet.photos.ui.components.Chip
import com.roylet.photos.ui.components.EmptyState
import com.roylet.photos.ui.components.Pressable
import com.roylet.photos.ui.components.ScreenHeader
import com.roylet.photos.ui.components.SectionLabel
import com.roylet.photos.ui.components.brutal
import com.roylet.photos.ui.gallery.AssetImage
import com.roylet.photos.ui.gallery.GridSelection
import com.roylet.photos.ui.gallery.LocalMediaUrls
import com.roylet.photos.ui.gallery.SelectionScope
import com.roylet.photos.ui.gallery.SelectionTopBar
import com.roylet.photos.ui.gallery.defaultSelectionActions
import com.roylet.photos.ui.gallery.TimelineGrid
import androidx.compose.material.icons.rounded.Info
import com.roylet.photos.domain.source.Capability
import com.roylet.photos.ui.components.Notice
import com.roylet.photos.ui.sources.LocalCapabilities
import java.time.LocalDate
import com.roylet.photos.ui.theme.Brutal
import com.roylet.photos.ui.theme.Palette
import com.roylet.photos.ui.theme.Type
import com.roylet.photos.util.Format

/** The "Search" tab: smart search with people, places, things and recent searches to start from. */
@Composable
fun SearchScreen(
    model: SearchModel,
    timelineFor: (TimelineFilter) -> TimelineRepository,
    onOpen: (TimelineRepository, Int, TimelineAsset) -> Unit,
    onPerson: (Person) -> Unit,
    onAllPeople: () -> Unit,
    modifier: Modifier = Modifier,
    selection: GridSelection = remember { GridSelection() },
) {
    LaunchedEffect(model) { model.loadDiscover() }
    val result = model.result
    BackHandler(enabled = result !is SearchResult.Idle && !selection.active) { model.clear() }
    BackHandler(enabled = selection.active) { selection.clear() }
    val focus = LocalFocusManager.current
    val done = result as? SearchResult.Done
    val timeline = done?.let { remember(it.filter) { timelineFor(it.filter) } }
    val smart = Capability.SMART_SEARCH in LocalCapabilities.current

    Column(modifier.fillMaxSize().background(Palette.paper)) {
        if (selection.active && timeline != null) {
            SelectionTopBar(
                selection.ids.size, onClose = selection::clear,
                actions = defaultSelectionActions(SelectionScope(selection.ids.toList(), timeline, selection::clear)),
            )
        } else {
            ScreenHeader("Search", subtitle = done?.let { "${Format.plural(it.count, "result")} for “${it.query.label}”" }, below = {
                SearchField(
                    model.text, { model.text = it },
                    onSearch = { focus.clearFocus(); model.submit() },
                    onClear = { model.clear() },
                    placeholder = if (smart) "Beach at sunset, red car, birthday cake…" else "File name, folder or year…",
                    modifier = Modifier.padding(top = 12.dp),
                )
            })
        }
        Box(Modifier.weight(1f)) {
            when (result) {
                SearchResult.Idle -> Discover(model, onPerson, onAllPeople, smart)
                is SearchResult.Loading -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    BrutalSpinner(size = 32.dp)
                    Spacer(Modifier.height(12.dp))
                    Text("Looking for “${result.query.label}”…", style = Type.Body, color = Palette.mutedInk)
                }
                is SearchResult.Failed -> EmptyState(Icons.Rounded.CloudOff, "Search failed", result.message) {
                    BrutalButton("Try again", { model.submit(result.query) })
                }
                is SearchResult.Done -> if (result.count == 0) {
                    EmptyState(
                        Icons.Rounded.SearchOff, "Nothing found",
                        if (smart) "Try different words: what's in the photo, a place, or a colour." else "Try part of a file name, a folder, or a year or month like “2024” or “March 2023”.",
                    )
                } else if (timeline != null) {
                    TimelineGrid(timeline, onOpen = { i, a -> onOpen(timeline, i, a) }, selection = selection)
                }
            }
        }
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, onSearch: () -> Unit, onClear: () -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = Type.Body.copy(color = Palette.ink),
        cursorBrush = SolidColor(Brutal.Orange),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        modifier = modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Row(Modifier.fillMaxWidth().brutal(shadow = 3.dp).padding(start = 14.dp, end = 6.dp).height(50.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Search, null, tint = Palette.ink, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(placeholder, style = Type.Body, color = Palette.mutedInk.copy(alpha = 0.7f), maxLines = 1)
                    inner()
                }
                if (value.isNotEmpty()) {
                    Box(Modifier.size(38.dp).clip(CircleShape).clickable(onClick = onClear), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Close, "Clear", tint = Palette.ink, modifier = Modifier.size(18.dp))
                    }
                }
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Discover(model: SearchModel, onPerson: (Person) -> Unit, onAllPeople: () -> Unit, smart: Boolean) {
    val recent by model.prefs.recentSearches.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 16.dp, bottom = 24.dp)) {
        val pad = Modifier.padding(horizontal = 16.dp)
        if (recent.isNotEmpty()) {
            SectionLabel("Recent", pad, trailing = { Text("CLEAR", style = Type.Tiny, color = Brutal.Orange, modifier = Modifier.clickable { model.prefs.clearRecentSearches() }.padding(4.dp)) })
            FlowRow(pad, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                recent.forEach { q -> Chip(q, selected = false, onClick = { model.submit(SearchQuery.Text(q)) }, icon = Icons.Rounded.History) }
            }
            Spacer(Modifier.height(22.dp))
        }
        if (model.people.isNotEmpty()) {
            SectionLabel("People", pad, trailing = { Text("SEE ALL", style = Type.Tiny, color = Brutal.Orange, modifier = Modifier.clickable(onClick = onAllPeople).padding(4.dp)) })
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(model.people.filter { it.name.isNotBlank() }.take(16), key = { it.id }) { p -> PersonBubble(p, 68.dp) { onPerson(p) } }
            }
            Spacer(Modifier.height(22.dp))
        }
        model.explore.forEach { section ->
            SectionLabel(if (section.isPlaces) "Places" else "Things", pad)
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(section.items, key = { it.value }) { item ->
                    PhotoTile(item.asset, item.value, if (section.isPlaces) Icons.Rounded.Place else null) {
                        model.submit(if (section.isPlaces) SearchQuery.Place(item.value, null) else SearchQuery.Text(item.value))
                    }
                }
            }
            Spacer(Modifier.height(22.dp))
        }
        if (model.countries.isNotEmpty()) {
            SectionLabel("Countries", pad)
            FlowRow(pad, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                model.countries.forEach { c -> Chip(c, selected = false, onClick = { model.submit(SearchQuery.Place(null, c)) }, icon = Icons.Rounded.Place) }
            }
            Spacer(Modifier.height(22.dp))
        }
        if (!smart) {
            Notice(
                "Searching this phone by file name, folder or date. Connect Immich to search what's in your photos, by people and by place.",
                icon = Icons.Rounded.Info,
                modifier = pad,
            )
            Spacer(Modifier.height(22.dp))
        }
        SectionLabel("Try", pad)
        FlowRow(pad, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val tries = if (smart) listOf("Sunset", "Dog in the snow", "Birthday cake", "Mountains", "Screenshots", "Night sky")
            else listOf("Screenshots", "Camera", "WhatsApp", "Downloads", LocalDate.now().year.toString(), "IMG_")
            tries.forEach { q -> Chip(q, selected = false, onClick = { model.submit(SearchQuery.Text(q)) }) }
        }
    }
}

/** A round face with the person's name under it. */
@Composable
fun PersonBubble(person: Person, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val urls = LocalMediaUrls.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    Column(modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(size).clip(CircleShape).background(Palette.lilac, CircleShape).border(2.dp, Palette.ink, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Person, null, tint = Palette.ink.copy(alpha = 0.5f), modifier = Modifier.size(size * 0.5f))
            AssetImage(urls.person(person.id), null, px, px, Modifier.matchParentSize(), placeholderColor = Color.Transparent)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            person.name.ifBlank { "Add a name" }, style = Type.Label, color = if (person.name.isBlank()) Palette.mutedInk else Palette.ink,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.width(size + 12.dp),
        )
    }
}

/** A photo tile with a label on an ink scrim, for places and things. */
@Composable
private fun PhotoTile(asset: TimelineAsset, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector?, onClick: () -> Unit) {
    val urls = LocalMediaUrls.current
    val px = with(LocalDensity.current) { 132.dp.roundToPx() }
    Pressable(onClick, shadow = 3.dp) {
        Box(Modifier.size(132.dp)) {
            AssetImage(urls.thumbnail(asset.id), asset.thumbhash, px, px, Modifier.matchParentSize())
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Brutal.Ink.copy(alpha = 0.78f))))
            Row(Modifier.align(Alignment.BottomStart).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) { Icon(icon, null, tint = Brutal.Paper, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)) }
                Text(label, style = Type.Label, color = Brutal.Paper, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
