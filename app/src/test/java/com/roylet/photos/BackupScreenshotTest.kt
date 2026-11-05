package com.roylet.photos

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import coil3.compose.LocalAsyncImagePreviewHandler
import com.roylet.photos.backup.BackupExclusion
import com.roylet.photos.backup.BackupFailure
import com.roylet.photos.backup.BackupFolder
import com.roylet.photos.backup.BackupOptions
import com.roylet.photos.backup.BackupState
import com.roylet.photos.backup.FolderBackupStatus
import com.roylet.photos.domain.library.DeviceFolders
import com.roylet.photos.domain.library.FolderPrefs
import com.roylet.photos.ui.actions.ActionSheet
import com.roylet.photos.ui.actions.DeleteChoiceContent
import com.roylet.photos.ui.components.LocalReducedMotion
import com.roylet.photos.ui.gallery.LocalMediaUrls
import com.roylet.photos.ui.gallery.SourceMediaUrls
import com.roylet.photos.ui.library.FolderMenuSheet
import com.roylet.photos.ui.settings.BackupContent
import com.roylet.photos.ui.settings.FolderBackupUi
import com.roylet.photos.ui.settings.LocalFolderBackup
import com.roylet.photos.ui.theme.Palette
import com.roylet.photos.ui.theme.PhotosTheme
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import com.roylet.photos.local.LocalMediaUrls as PhoneUrls

/**
 * Settings → Backup with sample data: idle, uploading, waiting with failures and exclusions, no
 * server; plus the folder menu's Back up row and the delete sheet's backup warning.
 *   ./gradlew testDebugUnitTest -Pscreenshots --tests '*BackupScreenshotTest*'
 * Output: app/build/screenshots/backup-*.png (and -dark).
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h880dp-xhdpi")
class BackupScreenshotTest(private val darkTheme: Boolean) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0}")
        fun themes(): List<Array<Any>> = listOf(arrayOf(false), arrayOf(true))
    }

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val outDir = File(System.getProperty("screenshotDir") ?: "build/screenshots")

    @Before fun only() = assumeTrue(System.getProperty("screenshots") == "true")

    private fun folder(id: Long, name: String, count: Int, enabled: Boolean, backedUp: Int, failed: Int = 0) = BackupFolder(
        id, name, null, count, coverId = id * 10,
        status = FolderBackupStatus(enabled, eligible = count, backedUp = backedUp, pending = (count - backedUp - failed).coerceAtLeast(0), failed = failed),
    )

    private val folders = listOf(
        folder(1, "Camera", 1_284, true, 1_164, failed = 2),
        folder(2, "Screenshots", 212, false, 0),
        folder(3, "WhatsApp Images", 530, true, 530),
        folder(4, "Downloads", 41, false, 12),
        folder(5, "Instagram", 18, false, 0),
    )

    private fun shoot(name: String, interact: () -> Unit = {}, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(
                LocalInspectionMode provides true,
                LocalAsyncImagePreviewHandler provides FakeGallery.previewHandler,
                LocalReducedMotion provides true,
                LocalMediaUrls provides SourceMediaUrls(immich = null, local = PhoneUrls),
            ) { PhotosTheme(dark = darkTheme) { content() } }
        }
        compose.mainClock.advanceTimeBy(1200)
        interact()
        compose.mainClock.advanceTimeBy(1200)
        val view = compose.activity.window.decorView.rootView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        outDir.mkdirs()
        File(outDir, if (darkTheme) "$name-dark.png" else "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun idle() = shoot("backup-01-idle") {
        BackupContent(true, BackupState.Idle(0), BackupOptions(enabled = true), folders.map { it.copy(status = it.status.copy(pending = 0, failed = 0)) }, emptyList(), emptyList(), onBack = {})
    }

    @Test fun uploading() = shoot("backup-02-uploading") {
        BackupContent(true, BackupState.Uploading(3, 120, "PXL_20261104_181203.jpg", 0.6f), BackupOptions(enabled = true), folders, emptyList(), emptyList(), onBack = {})
    }

    /** Scrolled down: options, "Couldn't back up" and "Not backing up". */
    @Test @Config(qualifiers = "w400dp-h1700dp-xhdpi")
    fun lists() = shoot("backup-03-waiting-lists") {
        BackupContent(
            true, BackupState.WaitingForWifi(122), BackupOptions(enabled = true, whileChargingOnly = true, newerThan = 1_793_750_400_000L), folders,
            failures = listOf(
                BackupFailure(101, "IMG_20260101_120000.heic", "Unsupported file type (HTTP 415)", 1),
                BackupFailure(102, "VID_20251224_090000.mp4", "Couldn't read this file", 1),
            ),
            exclusions = listOf(BackupExclusion(103, "PXL_20261002_101010.jpg", "Deleted from Immich", 1)),
            onBack = {},
        )
    }

    @Test fun off() = shoot("backup-04-off") {
        BackupContent(true, BackupState.Off, BackupOptions(), folders, emptyList(), emptyList(), onBack = {})
    }

    @Test fun noServer() = shoot("backup-05-no-server") {
        BackupContent(false, BackupState.NoServer, BackupOptions(enabled = true), null, emptyList(), emptyList(), onBack = {})
    }

    @Test fun folderMenu() = shoot("backup-06-folder-menu") {
        val ui = FolderBackupUi(mapOf(1L to folders[0].status), active = true, onToggle = { _, _ -> }, onOpenBackup = {})
        CompositionLocalProvider(LocalFolderBackup provides ui) {
            Column(Modifier.fillMaxSize().background(Palette.paper)) {}
            FolderMenuSheet(DeviceFolders.of(1, "Camera", "DCIM/Camera/", null, 1_284, 0, "local:10"), FolderPrefs(), {}, { _, _ -> }, { _, _ -> })
        }
    }

    @Test fun deleteWarning() = shoot("backup-07-delete-warning", interact = { compose.onNodeWithText("IMMICH").performClick() }) {
        Column(Modifier.fillMaxSize().background(Palette.paper).statusBarsPadding().padding(16.dp)) {
            DeleteChoiceContent(ActionSheet.DeleteChoice(listOf("local:1"), null, paired = 1, backedUp = true)) {}
        }
    }
}
