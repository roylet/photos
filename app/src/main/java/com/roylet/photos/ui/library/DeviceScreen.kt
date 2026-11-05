package com.roylet.photos.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.roylet.photos.domain.timeline.TimelineAsset
import com.roylet.photos.domain.timeline.TimelineRepository
import com.roylet.photos.ui.components.ScreenHeader
import com.roylet.photos.ui.components.SquareIconButton
import com.roylet.photos.ui.gallery.TimelineScreen
import com.roylet.photos.ui.permissions.DeviceLibraryEmpty
import com.roylet.photos.ui.permissions.LimitedAccessBanner
import com.roylet.photos.ui.permissions.LocalMediaPermissions
import com.roylet.photos.ui.permissions.MediaAccess
import com.roylet.photos.ui.permissions.MediaAccessDenied
import com.roylet.photos.ui.theme.Palette

/**
 * Library → On this device (while the Photos tab shows Immich): the phone's own timeline, with
 * its folders and trash in the header. Gated on the media permission like the phone-only Photos
 * tab: no access shows GRANT ACCESS, partial access the "Limited access" banner.
 */
@Composable
fun DeviceScreen(
    timeline: TimelineRepository,
    onOpen: (index: Int, asset: TimelineAsset) -> Unit,
    onFolders: () -> Unit,
    onTrash: () -> Unit,
    onBack: () -> Unit,
    /** Access changed or the selection was edited: re-read the phone. */
    onAccessChanged: () -> Unit,
    modifier: Modifier = Modifier,
    revealIndex: Int? = null,
    onRevealed: () -> Unit = {},
) {
    val permissions = LocalMediaPermissions.current
    if (permissions.access == MediaAccess.Denied) {
        Column(modifier.fillMaxSize().background(Palette.paper)) {
            ScreenHeader("On this device", onBack = onBack)
            Box(Modifier.weight(1f)) {
                MediaAccessDenied(
                    permanentlyDenied = permissions.permanentlyDenied,
                    onGrant = { permissions.request { onAccessChanged() } },
                    onOpenSettings = permissions::openSettings,
                )
            }
        }
        return
    }
    TimelineScreen(
        timeline = timeline,
        title = "On this device",
        onOpen = onOpen,
        modifier = modifier,
        subtitle = "Photos and videos on this phone",
        onBack = onBack,
        revealIndex = revealIndex,
        onRevealed = onRevealed,
        headerActions = {
            SquareIconButton(Icons.Rounded.Folder, "Folders", onFolders)
            SquareIconButton(Icons.Rounded.Delete, "Phone trash", onTrash)
        },
        banner = if (permissions.access == MediaAccess.Partial) {
            { LimitedAccessBanner(onManage = { permissions.request { onAccessChanged() } }) }
        } else null,
        emptyContent = { DeviceLibraryEmpty(partial = permissions.access == MediaAccess.Partial, onManage = { permissions.request { onAccessChanged() } }) },
    )
}
