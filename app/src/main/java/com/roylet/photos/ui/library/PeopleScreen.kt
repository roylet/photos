package com.roylet.photos.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.People
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.roylet.photos.domain.library.LibrarySource
import com.roylet.photos.domain.library.Person
import com.roylet.photos.immich.ImmichApi
import com.roylet.photos.ui.components.BrutalButton
import com.roylet.photos.ui.components.BrutalSpinner
import com.roylet.photos.ui.components.EmptyState
import com.roylet.photos.ui.components.ScreenHeader
import com.roylet.photos.ui.search.PersonBubble
import com.roylet.photos.ui.theme.Brutal
import com.roylet.photos.ui.theme.Palette
import com.roylet.photos.util.Format

/** Faces Immich recognised, named ones first; tap for that person's timeline. */
@Composable
fun PeopleScreen(library: () -> LibrarySource, onPerson: (Person) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    var people by remember { mutableStateOf<List<Person>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(attempt) {
        error = null
        runCatching { library().people() }.onSuccess { people = it }.onFailure { error = ImmichApi.describe(it) }
    }
    Column(modifier.fillMaxSize().background(Palette.paper)) {
        ScreenHeader("People", subtitle = people?.let { list -> Format.plural(list.count { it.name.isNotBlank() }, "named person", "named people") }, onBack = onBack)
        val list = people
        when {
            list == null && error != null -> EmptyState(Icons.Rounded.CloudOff, "Couldn't load people", error.orEmpty()) { BrutalButton("Try again", { attempt++ }) }
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { BrutalSpinner(size = 32.dp) }
            list.isEmpty() -> EmptyState(Icons.Rounded.People, "No people yet", "Immich groups faces once face recognition has run on your library.")
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(96.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(list, key = { it.id }) { p -> PersonBubble(p, 84.dp) { onPerson(p) } }
            }
        }
    }
}
