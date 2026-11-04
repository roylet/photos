package com.roylet.photos.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.roylet.photos.ui.theme.Brutal
import com.roylet.photos.ui.theme.Type

/** One destination in the bottom bar / rail. */
data class NavTab(val route: String, val label: String, val icon: ImageVector)

private val PillShape = RoundedCornerShape(50)

/**
 * The phone tab bar: paper strip with a 2dp ink rule on top. The selected tab becomes an orange
 * pill with an ink outline and a hard shadow, like a pressed-out brutal button.
 */
@Composable
fun BrutalTabBar(tabs: List<NavTab>, selected: String?, onSelect: (NavTab) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(Brutal.Paper)
            .drawBehind { drawLine(Brutal.Ink, Offset.Zero, Offset(size.width, 0f), 2.dp.toPx()) }
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { tab ->
            TabItem(tab, tab.route == selected, onClick = { onSelect(tab) }, modifier = Modifier.weight(1f))
        }
    }
}

/** The tablet / landscape rail: a paper column with a 2dp ink rule on its right edge. */
@Composable
fun BrutalRail(tabs: List<NavTab>, selected: String?, onSelect: (NavTab) -> Unit, modifier: Modifier = Modifier, header: (@Composable () -> Unit)? = null) {
    Column(
        modifier
            .fillMaxHeight()
            .width(96.dp)
            .background(Brutal.Paper)
            .drawBehind { drawLine(Brutal.Ink, Offset(size.width, 0f), Offset(size.width, size.height), 2.dp.toPx()) }
            .windowInsetsPadding(
                WindowInsets.statusBars.union(WindowInsets.navigationBars).union(WindowInsets.displayCutout)
                    .only(WindowInsetsSides.Vertical + WindowInsetsSides.Start),
            )
            .padding(vertical = 16.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        header?.invoke()
        if (header != null) Spacer(Modifier.height(10.dp))
        tabs.forEach { tab -> TabItem(tab, tab.route == selected, onClick = { onSelect(tab) }, modifier = Modifier.fillMaxWidth()) }
    }
}

/** The square orange "P" mark shown at the top of the rail. */
@Composable
fun AppMark(modifier: Modifier = Modifier) {
    Box(modifier.brutal(Brutal.Orange, shadow = 3.dp).size(48.dp), contentAlignment = Alignment.Center) {
        Text("P", style = Type.Headline, color = Brutal.Ink)
    }
}

@Composable
private fun TabItem(tab: NavTab, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val fill by animateColorAsState(if (selected) Brutal.Orange else Color.Transparent, tween(160), label = "tabFill")
    Column(
        modifier
            .selectable(selected, interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .then(if (selected) Modifier.brutal(fill, shape = PillShape, shadow = 3.dp) else Modifier.padding(end = 3.dp, bottom = 3.dp))
                .width(60.dp)
                .height(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(tab.icon, null, tint = Brutal.Ink, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(2.dp))
        Text(
            tab.label.uppercase(),
            style = Type.Tiny,
            color = if (selected) Brutal.Ink else Brutal.MutedInk,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

