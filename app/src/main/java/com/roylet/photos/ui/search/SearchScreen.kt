package com.roylet.photos.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.roylet.photos.AppContainer
import com.roylet.photos.ui.components.BrutalTextField
import com.roylet.photos.ui.components.EmptyState
import com.roylet.photos.ui.components.ScreenHeader
import com.roylet.photos.ui.theme.Brutal

/** The "Search" tab. Placeholder until smart search lands. */
@Composable
fun SearchScreen(@Suppress("UNUSED_PARAMETER") container: AppContainer) {
    var query by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(Brutal.Paper)) {
        ScreenHeader("Search", below = {
            BrutalTextField(query, { query = it }, "Beach at sunset, dogs, 2019…", leading = Icons.Rounded.Search, modifier = Modifier.padding(top = 12.dp))
        })
        EmptyState(Icons.Rounded.Search, "Find anything", "Search by what's in the photo, a place, a person or a date.")
    }
}
