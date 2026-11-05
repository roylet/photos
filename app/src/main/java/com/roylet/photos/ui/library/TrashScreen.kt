package com.roylet.photos.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.roylet.photos.domain.library.LibrarySource
import com.roylet.photos.domain.timeline.TimelineAsset
import com.roylet.photos.domain.timeline.TimelineRepository
import com.roylet.photos.ui.actions.LibraryActions
import com.roylet.photos.ui.components.BrutalDialog
import com.roylet.photos.ui.components.DialogButtons
import com.roylet.photos.ui.components.Notice
import com.roylet.photos.ui.components.SquareIconButton
import com.roylet.photos.ui.sources.LocalActiveSources
import com.roylet.photos.ui.gallery.AssetActions
import com.roylet.photos.ui.gallery.GridSelection
import com.roylet.photos.ui.gallery.LocalAssetActions
import com.roylet.photos.ui.gallery.SelectionScope
import com.roylet.photos.ui.gallery.SelectionAction
import com.roylet.photos.ui.gallery.TimelineScreen
import com.roylet.photos.ui.theme.Brutal
import com.roylet.photos.ui.theme.Palette
import com.roylet.photos.util.Format

/** Trash: restore some or all, or delete for good (with a confirmation). */
@Composable
fun TrashScreen(
    timeline: TimelineRepository,
    library: () -> LibrarySource,
    actions: LibraryActions?,
    onOpen: (index: Int, asset: TimelineAsset) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    revealIndex: Int? = null,
    onRevealed: () -> Unit = {},
    selection: GridSelection = remember { GridSelection() },
) {
    var days by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) { days = runCatching { library().trashDays() }.getOrNull() }
    var confirmEmpty by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<SelectionScope?>(null) }
    val snapshot by timeline.snapshot.collectAsState()
    val count = snapshot.total

    Box(modifier.fillMaxSize()) {
        TimelineScreen(
            timeline = timeline,
            title = "Trash",
            subtitle = if (count > 0) Format.plural(count, "item") else null,
            onOpen = onOpen,
            onBack = onBack,
            selection = selection,
            revealIndex = revealIndex,
            onRevealed = onRevealed,
            emptyTitle = "Trash is empty",
            emptyText = days?.let { "Things you delete wait here for $it days before they're gone for good." } ?: "Things you delete wait here for a while before they're gone for good.",
            headerActions = {
                if (actions != null && count > 0) {
                    SquareIconButton(Icons.Rounded.RestoreFromTrash, "Restore all", { actions.restoreAll(timeline) })
                    SquareIconButton(Icons.Rounded.DeleteForever, "Empty trash", { confirmEmpty = true }, fill = Palette.red)
                }
            },
            selectionActions = { scope -> trashSelectionActions(scope) { confirmDelete = scope } },
            // Only when the source says how long it keeps things (the phone's period varies by Android version).
            topContent = days?.let { d ->
                {
                    Notice(
                        "Items in Trash are deleted for good after $d days.",
                        icon = Icons.Rounded.Info,
                        modifier = Modifier.padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 4.dp),
                    )
                }
            },
        )
        BrutalDialog(
            confirmEmpty, "Empty Trash?", onDismiss = { confirmEmpty = false },
            text = "${Format.plural(count, "item")} will be deleted for good. This can't be undone.",
        ) { DialogButtons("Empty Trash", { confirmEmpty = false; actions?.emptyTrash(timeline) }, { confirmEmpty = false }, danger = true) }
        val pending = confirmDelete
        BrutalDialog(
            pending != null, "Delete for good?", onDismiss = { confirmDelete = null },
            text = "${Format.plural(pending?.ids?.size ?: 0, "item")} will be gone from ${if (LocalActiveSources.current.hasImmich) "your server" else "this phone"}. This can't be undone.",
        ) {
            DialogButtons("Delete", {
                pending?.let { actions?.deleteForever(it.ids, it.timeline); it.clear() }
                confirmDelete = null
            }, { confirmDelete = null }, danger = true)
        }
    }
}

@Composable
private fun trashSelectionActions(scope: SelectionScope, actions: AssetActions = LocalAssetActions.current, onDelete: () -> Unit): List<SelectionAction> = listOf(
    SelectionAction(Icons.Rounded.RestoreFromTrash, "Restore", { actions.restore(scope.ids, scope.timeline); scope.clear() }),
    SelectionAction(Icons.Rounded.DeleteForever, "Delete for good", onDelete, fill = Palette.red),
)
