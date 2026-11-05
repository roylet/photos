package com.roylet.photos.video.ui

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhotoFilter
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.roylet.photos.PhotosApp
import com.roylet.photos.editor.cpu.ReferenceRenderer
import com.roylet.photos.editor.gl.toArgbImage
import com.roylet.photos.editor.recipe.CropRect
import com.roylet.photos.editor.recipe.Recipe
import com.roylet.photos.editor.save.SaveMode
import com.roylet.photos.editor.ui.ChoiceCard
import com.roylet.photos.editor.ui.DarkConfirm
import com.roylet.photos.editor.ui.DarkProgress
import com.roylet.photos.editor.ui.DarkTag
import com.roylet.photos.editor.ui.EditorBackdrop
import com.roylet.photos.editor.ui.SaveSheet
import com.roylet.photos.editor.ui.motion
import com.roylet.photos.editor.ui.rememberHaptics
import com.roylet.photos.editor.ui.rememberReducedMotion
import com.roylet.photos.editor.ui.toBitmap
import com.roylet.photos.ui.components.DarkButton
import com.roylet.photos.ui.components.DarkIconButton
import com.roylet.photos.ui.components.brutalDark
import com.roylet.photos.ui.theme.Brutal
import com.roylet.photos.ui.theme.BrutalDark
import com.roylet.photos.ui.theme.PhotosTheme
import com.roylet.photos.ui.theme.Type
import com.roylet.photos.util.Format
import com.roylet.photos.video.model.OutputResolution
import com.roylet.photos.video.model.VideoEdit
import com.roylet.photos.video.model.VideoGeometry
import com.roylet.photos.video.preview.VideoEditPreview
import com.roylet.photos.video.preview.rememberVideoPreviewController
import com.roylet.photos.video.save.VideoSaveProgress
import com.roylet.photos.video.save.VideoSaveStage

/** The video editor's tools, in rail order. */
enum class VideoTool(val label: String, val icon: ImageVector) {
    TRIM("Trim", Icons.Rounded.ContentCut),
    CROP("Crop", Icons.Rounded.Crop),
    SPEED("Speed", Icons.Rounded.Speed),
    AUDIO("Audio", Icons.AutoMirrored.Rounded.VolumeUp),
    ADJUST("Adjust", Icons.Rounded.Tune),
    FILTERS("Filters", Icons.Rounded.PhotoFilter),
    EXPORT("Export", Icons.Rounded.HighQuality);

    fun isEdited(e: VideoEdit): Boolean = when (this) {
        TRIM -> !e.isFullLength
        CROP -> !e.geometry.isIdentity
        SPEED -> e.speed != 1f
        AUDIO -> !e.audio.isIdentity
        ADJUST -> !e.color.copy(filter = null).isColorIdentity
        FILTERS -> e.color.filter != null
        EXPORT -> e.output.resolution != OutputResolution.ORIGINAL || e.output.quality != com.roylet.photos.video.model.OutputQuality.HIGH
    }
}

private val WideEditor = 600.dp
private val PhonePanelHeight = 252.dp

/**
 * The video editor for one Immich video: downloads the original (the trim strip fills from the
 * server meanwhile), then edits it live in the preview. Saves run as background jobs in
 * `container.videoEdits`; [onSaved] gets the new asset id once one finishes.
 */
@Composable
fun VideoEditorScreen(assetId: String, onClose: () -> Unit, onSaved: (newAssetId: String) -> Unit) {
    val app = LocalContext.current.applicationContext as PhotosApp
    val container = app.container
    val vm: VideoEditorViewModel = viewModel(key = "video-editor-$assetId") {
        VideoEditorViewModel(assetId, container::api, container.videoEdits, container.http)
    }
    val phase by vm.phase.collectAsStateWithLifecycle()
    val save by vm.saveProgress.collectAsStateWithLifecycle()
    LaunchedEffect(save) { (save as? VideoSaveProgress.Done)?.let { onSaved(it.result.newAssetId) } }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    PhotosTheme(dark = true) {
        when (val p = phase) {
            is VideoEditorPhase.Loading -> {
                BackHandler(onBack = onClose)
                VideoEditorLoading(p, onClose)
            }
            is VideoEditorPhase.Choose -> {
                BackHandler(onBack = onClose)
                VideoReEditChoice(onContinue = vm::continueFromOriginal, onThisVersion = vm::editThisVersion, onClose = onClose)
            }
            is VideoEditorPhase.Failed -> {
                BackHandler(onBack = onClose)
                VideoEditorFailed(p, onRetry = vm::retry, onEditThisVersion = vm::editThisVersion, onClose = onClose)
            }
            is VideoEditorPhase.Ready -> {
                val controller = rememberVideoPreviewController()
                val file = p.session.sourceFile
                LaunchedEffect(controller, file) { if (controller != null && file != null) controller.setSource(file) }
                val playback = remember(controller) { controller?.let(::ControllerPlayback) ?: StillPlayback() }
                VideoEditorWorkspace(
                    session = p.session,
                    playback = playback,
                    preview = controller?.let { c -> { m -> VideoEditPreview(c, m) } },
                    onClose = onClose,
                    onSave = { mode, group ->
                        if (needsNotificationPermission(app)) notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        playback.pause()
                        vm.save(mode, group)
                    },
                    saveProgress = save,
                    onCancelSave = vm::cancelSave,
                    onDismissSave = vm::clearSave,
                )
            }
        }
    }
}

private fun needsNotificationPermission(context: android.content.Context): Boolean =
    android.os.Build.VERSION.SDK_INT >= 33 &&
        androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
        android.content.pm.PackageManager.PERMISSION_GRANTED

/**
 * The editor UI for an open [session]. [preview] draws the live video (null in screenshot tests,
 * where a still frame of the session's poster stands in).
 */
@Composable
fun VideoEditorWorkspace(
    session: VideoEditorSession,
    playback: VideoPlayback,
    preview: (@Composable (Modifier) -> Unit)?,
    onClose: () -> Unit,
    onSave: (SaveMode, Boolean) -> Unit,
    saveProgress: VideoSaveProgress?,
    onCancelSave: () -> Unit = {},
    onDismissSave: () -> Unit = {},
    initialTool: VideoTool = VideoTool.TRIM,
    initialSheet: Boolean = false,
    initialAdjustPage: AdjustPage = AdjustPage.LIGHT,
) {
    var tool by rememberSaveable { mutableStateOf(initialTool) }
    var sheet by rememberSaveable { mutableStateOf(initialSheet) }
    var confirmClose by remember { mutableStateOf(false) }
    val edit = session.history.current.collectAsState()
    val cropMode = tool == VideoTool.CROP

    // The player follows the edit; trim-handle drags push on release (the strip shows the frame meanwhile).
    LaunchedEffect(session, playback, cropMode) {
        session.history.current.collect { e ->
            if (session.scrubHandle == null) playback.setEdit(if (cropMode) e.copy(geometry = e.geometry.copy(crop = CropRect())) else e)
        }
    }

    val requestClose = { if (session.isDirty && saveProgress == null) confirmClose = true else onClose() }
    BackHandler { if (sheet) sheet = false else requestClose() }

    val header: @Composable () -> Unit = { VideoEditorHeader(session, onClose = requestClose, onSave = { playback.pause(); sheet = true }) }
    val canvas: @Composable (Modifier) -> Unit = { m -> PreviewArea(session, playback, preview, edit, cropMode, m) }
    val panel: @Composable (Modifier) -> Unit = { m -> VideoToolPanel(session, playback, edit, tool, initialAdjustPage, m) }

    Box(Modifier.fillMaxSize().background(BrutalDark.Canvas)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            if (maxWidth >= WideEditor) {
                Column(Modifier.fillMaxSize()) {
                    header()
                    Row(Modifier.weight(1f).fillMaxWidth().navigationBarsPadding()) {
                        canvas(Modifier.weight(1f).fillMaxHeight())
                        Column(Modifier.width(380.dp).fillMaxHeight().padding(8.dp).brutalDark(shadow = 0.dp).padding(vertical = 12.dp)) {
                            Text(tool.label.uppercase(), style = Type.Title, color = BrutalDark.Text, modifier = Modifier.padding(horizontal = 16.dp))
                            Spacer(Modifier.height(8.dp))
                            panel(Modifier.weight(1f).fillMaxWidth())
                        }
                        VideoToolRail(tool, edit, { tool = it }, vertical = true, modifier = Modifier.fillMaxHeight().padding(end = 8.dp))
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    header()
                    canvas(Modifier.weight(1f).fillMaxWidth())
                    panel(
                        Modifier.fillMaxWidth().height(PhonePanelHeight).drawBehind {
                            drawLine(BrutalDark.Line.copy(alpha = 0.14f), Offset(0f, 0f), Offset(size.width, 0f), 2.dp.toPx())
                        }.padding(top = 6.dp),
                    )
                    VideoToolRail(tool, edit, { tool = it }, vertical = false, modifier = Modifier.fillMaxWidth().navigationBarsPadding())
                }
            }
        }

        SaveSheet(
            visible = sheet && saveProgress == null,
            onDismiss = { sheet = false },
            onSave = { mode, group -> sheet = false; onSave(mode, group) },
            subtitle = "Exports an MP4 and uploads it to Immich",
            extra = { ExportSummaryRow(session, edit) { sheet = false; tool = VideoTool.EXPORT } },
        )
        VideoSaveProgressPanel(saveProgress, onBackground = onClose, onCancel = onCancelSave, onDismiss = onDismissSave)
    }

    if (confirmClose) {
        DarkConfirm(
            title = "Discard edits?",
            text = "Your changes to this video haven't been saved.",
            confirm = "Discard",
            onConfirm = { confirmClose = false; onClose() },
            onDismiss = { confirmClose = false },
        )
    }
}

@Composable
private fun VideoEditorHeader(session: VideoEditorSession, onClose: () -> Unit, onSave: () -> Unit) {
    val canUndo by session.history.canUndo.collectAsState()
    val canRedo by session.history.canRedo.collectAsState()
    val edit by session.history.current.collectAsState()
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        DarkIconButton(Icons.Rounded.Close, "Close editor", onClose)
        Spacer(Modifier.weight(1f))
        DarkIconButton(Icons.AutoMirrored.Rounded.Undo, "Undo", { session.history.undo() }, enabled = canUndo)
        DarkIconButton(Icons.AutoMirrored.Rounded.Redo, "Redo", { session.history.redo() }, enabled = canRedo)
        Spacer(Modifier.width(4.dp))
        DarkButton("Save", onSave, enabled = !edit.isIdentity)
    }
}

/** The preview: the live video (or a still), tap to play/pause, time, trim-handle frames and the crop overlay. */
@Composable
private fun PreviewArea(
    session: VideoEditorSession,
    playback: VideoPlayback,
    preview: (@Composable (Modifier) -> Unit)?,
    edit: State<VideoEdit>,
    cropMode: Boolean,
    modifier: Modifier,
) {
    val reduced = rememberReducedMotion()
    val playing by playback.isPlaying.collectAsState()
    val position by playback.outputPosition.collectAsState()
    val inset by animateDpAsState(if (cropMode) 28.dp else 8.dp, motion(reduced, spring(dampingRatio = 0.8f, stiffness = 500f)), label = "inset")
    val scrubbing = session.scrubHandle != null
    Box(modifier.clipToBounds()) {
        Box(
            Modifier.fillMaxSize().padding(inset)
                .clickable(remember { MutableInteractionSource() }, indication = null, enabled = !cropMode) { playback.togglePlay() },
        ) {
            if (preview != null) preview(Modifier.fillMaxSize())
            else StillPreview(session, edit, cropMode, Modifier.fillMaxSize())
            if (cropMode) VideoCropOverlay(session, edit, Modifier.fillMaxSize())
            // While a trim handle is dragged: the frame under it.
            if (scrubbing) {
                val frame = session.scrubFrame
                Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                    val img = frame ?: nearestStripFrame(session, session.scrubMs)
                    if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                }
            }
        }
        if (!cropMode) {
            AnimatedVisibility(
                visible = !playing && !scrubbing,
                modifier = Modifier.align(Alignment.Center),
                enter = scaleIn(motion(reduced, spring(dampingRatio = 0.5f, stiffness = 600f)), initialScale = 0.6f) + fadeIn(motion(reduced, tween(120))),
                exit = scaleOut(motion(reduced, tween(140)), targetScale = 0.8f) + fadeOut(motion(reduced, tween(140))),
            ) {
                Box(
                    Modifier.size(76.dp).brutalDark(fill = BrutalDark.Orange, shadow = 5.dp, border = Brutal.Ink, shape = CircleShape)
                        .clickable(remember { MutableInteractionSource() }, indication = null) { playback.togglePlay() }
                        .semantics { contentDescription = "Play" },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.PlayArrow, null, tint = Brutal.Ink, modifier = Modifier.size(44.dp)) }
            }
        }
        val e = edit.value
        Row(
            Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 14.dp)
                .brutalDark(fill = BrutalDark.Panel, shadow = 3.dp).padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!cropMode) {
                Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = BrutalDark.Orange, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
            }
            val tenths by remember(position) { derivedStateOf { position / 100 } }
            Text(
                "${TrimMath.formatTime(tenths * 100)} / ${TrimMath.formatTime(e.outputDurationMs())}",
                style = Type.Label, color = BrutalDark.Text,
            )
        }
        val handle = session.scrubHandle
        if (handle != null) {
            DarkTag(
                "${if (handle.edge == TrimEdge.START) "IN" else "OUT"} ${TrimMath.formatTime(session.scrubMs)}",
                Modifier.align(Alignment.TopCenter).padding(top = 14.dp),
            )
        }
    }
}

private fun nearestStripFrame(session: VideoEditorSession, ms: Long): ImageBitmap? {
    val times = session.strip.timesMs
    var best: ImageBitmap? = null
    var bestD = Long.MAX_VALUE
    for (i in times.indices) {
        val f = session.strip.frames[i] ?: continue
        val d = kotlin.math.abs(times[i] - ms)
        if (d < bestD) { bestD = d; best = f }
    }
    for ((t, f) in session.zoomFrames) {
        val d = kotlin.math.abs(t - ms)
        if (d < bestD) { bestD = d; best = f }
    }
    return best
}

/** A still of the poster with the edit's geometry and colour (CPU), where the live player can't run. */
@Composable
private fun StillPreview(session: VideoEditorSession, edit: State<VideoEdit>, cropMode: Boolean, modifier: Modifier) {
    val poster = session.poster
    val geometry by remember(edit) { derivedStateOf { edit.value.geometry } }
    val color by remember(edit) { derivedStateOf { edit.value.color } }
    val image = remember(poster, geometry, color, cropMode) { poster?.let { renderStill(it, geometry, color, cropMode) } }
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        if (image != null) Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
    }
}

internal fun renderStill(poster: Bitmap, g: VideoGeometry, color: Recipe, cropMode: Boolean): ImageBitmap {
    val m = Matrix()
    m.postRotate(90f * g.rotate90.mod(4))
    m.postScale(if (g.flipH) -1f else 1f, if (g.flipV) -1f else 1f)
    var b = Bitmap.createBitmap(poster, 0, 0, poster.width, poster.height, m, true)
    if (!cropMode && !g.crop.isFull) {
        val c = g.crop.normalized()
        val x = (c.left * b.width).toInt().coerceIn(0, b.width - 1)
        val y = (c.top * b.height).toInt().coerceIn(0, b.height - 1)
        val w = (c.width * b.width).toInt().coerceIn(1, b.width - x)
        val h = (c.height * b.height).toInt().coerceIn(1, b.height - y)
        b = Bitmap.createBitmap(b, x, y, w, h)
    }
    if (color.isColorIdentity) return b.asImageBitmap()
    return ReferenceRenderer.render(b.toArgbImage(), color).toBitmap().asImageBitmap()
}

@Composable
private fun VideoToolPanel(session: VideoEditorSession, playback: VideoPlayback, edit: State<VideoEdit>, tool: VideoTool, adjustPage: AdjustPage, modifier: Modifier) {
    val reduced = rememberReducedMotion()
    AnimatedContent(
        tool,
        modifier = modifier,
        transitionSpec = { fadeIn(motion(reduced, tween(160))) togetherWith fadeOut(motion(reduced, tween(100))) },
        label = "tool",
    ) { t ->
        val m = Modifier.fillMaxSize()
        when (t) {
            VideoTool.TRIM -> TrimPanel(session, playback, edit, m)
            VideoTool.CROP -> VideoCropPanel(session, edit, m)
            VideoTool.SPEED -> SpeedPanel(session, edit, m)
            VideoTool.AUDIO -> AudioPanel(session, edit, m)
            VideoTool.ADJUST -> AdjustPanel(session, edit, m, adjustPage)
            VideoTool.FILTERS -> VideoFiltersPanel(session, edit, m)
            VideoTool.EXPORT -> ExportPanel(session, edit, m)
        }
    }
}

@Composable
private fun VideoToolRail(current: VideoTool, edit: State<VideoEdit>, onSelect: (VideoTool) -> Unit, vertical: Boolean, modifier: Modifier) {
    val haptics = rememberHaptics()
    val items: @Composable (Modifier) -> Unit = { m ->
        for (t in VideoTool.entries) {
            val edited by remember(t) { derivedStateOf { t.isEdited(edit.value) } }
            RailItem(t, t == current, edited, { if (t != current) haptics.tick(); onSelect(t) }, m)
        }
    }
    if (vertical) {
        Column(modifier.width(76.dp).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { items(Modifier.fillMaxWidth()) }
    } else {
        Row(
            modifier.background(BrutalDark.Panel).border(BorderStroke(2.dp, BrutalDark.Line.copy(alpha = 0.12f))).padding(horizontal = 2.dp, vertical = 6.dp),
        ) { items(Modifier.weight(1f)) }
    }
}

/** A rail item in the photo editor's style. */
@Composable
private fun RailItem(tool: VideoTool, selected: Boolean, edited: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .semantics { this.selected = selected }
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            Box(
                Modifier.size(40.dp)
                    .then(if (selected) Modifier.brutalDark(fill = BrutalDark.Orange, shadow = 3.dp, border = Brutal.Ink) else Modifier.padding(end = 3.dp, bottom = 3.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(tool.icon, tool.label, tint = if (selected) Brutal.Ink else BrutalDark.Text, modifier = Modifier.size(22.dp)) }
            if (edited && !selected) Box(Modifier.align(Alignment.TopEnd).size(8.dp).clip(CircleShape).background(BrutalDark.Orange))
        }
        Spacer(Modifier.height(2.dp))
        Text(tool.label.uppercase(), style = Type.Tiny, color = if (selected) BrutalDark.Orange else BrutalDark.MutedText, maxLines = 1)
    }
}

// ---- Save ------------------------------------------------------------------------------------

/** In the save sheet: what will be exported, with a way to change it. */
@Composable
private fun ExportSummaryRow(session: VideoEditorSession, edit: State<VideoEdit>, onChange: () -> Unit) {
    val e = edit.value
    val est = remember(e) { ExportEstimates.estimate(e, session.source, session.caps) }
    Row(
        Modifier.fillMaxWidth().brutalDark(fill = BrutalDark.Raised, shadow = 3.dp).clickable(onClick = onChange).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("${e.output.resolution.label.uppercase()} · ${e.output.quality.label.uppercase()} · ${est.width}×${est.height}", style = Type.Label, color = BrutalDark.Text)
            Text("≈ ${Format.bytes(est.bytes)} · ${TrimMath.formatDuration(est.durationMs)}", style = Type.Small, color = BrutalDark.MutedText)
        }
        Text("CHANGE", style = Type.Label, color = BrutalDark.Orange)
    }
}

private enum class SaveStep(val label: String) { DOWNLOAD("Download"), EXPORT("Export"), UPLOAD("Upload") }

private fun VideoSaveStage.step(): Int = when (this) {
    VideoSaveStage.QUEUED, VideoSaveStage.WAITING_FOR_NETWORK -> -1
    VideoSaveStage.DOWNLOADING -> 0
    VideoSaveStage.EXPORTING -> 1
    VideoSaveStage.UPLOADING, VideoSaveStage.LINKING, VideoSaveStage.GROUPING, VideoSaveStage.TRASHING, VideoSaveStage.RECIPE -> 2
    VideoSaveStage.DONE -> 3
}

/** In-app progress for a running video save: stages, overall progress, cancel, or leave. */
@Composable
fun VideoSaveProgressPanel(progress: VideoSaveProgress?, onBackground: () -> Unit, onCancel: () -> Unit, onDismiss: () -> Unit) {
    val reduced = rememberReducedMotion()
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(progress != null, enter = fadeIn(motion(reduced, tween(160))), exit = fadeOut(motion(reduced, tween(160)))) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(remember { MutableInteractionSource() }, indication = null) {})
        }
        AnimatedVisibility(
            progress != null,
            modifier = Modifier.align(Alignment.Center),
            enter = androidx.compose.animation.slideInVertically(motion(reduced, spring(dampingRatio = 0.7f, stiffness = 500f))) { it / 3 } + fadeIn(motion(reduced, tween(120))),
            exit = fadeOut(motion(reduced, tween(150))),
        ) {
            val p = progress ?: return@AnimatedVisibility
            Column(
                Modifier.widthIn(max = 440.dp).fillMaxWidth().padding(20.dp).brutalDark(shadow = 6.dp).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (p) {
                    is VideoSaveProgress.Running -> {
                        Text("SAVING VIDEO", style = Type.Label, color = BrutalDark.Orange)
                        Text("${(p.overall * 100).toInt()}%", style = Type.Huge, color = BrutalDark.Text)
                        val waiting = p.stage == VideoSaveStage.QUEUED || p.stage == VideoSaveStage.WAITING_FOR_NETWORK
                        DarkProgress(p.overall, height = 18.dp, moving = waiting)
                        StageSteps(p.stage.step(), p.fraction)
                        Text(p.detail?.let { "${p.stage.label} · $it" } ?: p.stage.label, style = Type.Small, color = BrutalDark.MutedText, maxLines = 2)
                        Text("You can leave; the save keeps going with a notification.", style = Type.Small, color = BrutalDark.MutedText)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                            DarkButton("Cancel", onCancel, primary = false)
                            DarkButton("Continue in background", onBackground, primary = false)
                        }
                    }
                    is VideoSaveProgress.Failed -> {
                        DarkTag(if (p.cancelled) "SAVE CANCELLED" else "SAVE FAILED", fill = if (p.cancelled) BrutalDark.Yellow else BrutalDark.Red)
                        Text(if (p.cancelled) "The save was stopped." else p.message, style = Type.Title, color = BrutalDark.Text)
                        Text("Nothing was deleted. Your edits are still here.", style = Type.Small, color = BrutalDark.MutedText)
                        DarkButton("Back to editing", onDismiss, modifier = Modifier.align(Alignment.End))
                    }
                    is VideoSaveProgress.Done -> {
                        DarkTag("SAVED", fill = BrutalDark.Mint)
                        Text(p.result.warning ?: "Your video is in Immich.", style = Type.Title, color = BrutalDark.Text)
                    }
                }
            }
        }
    }
}

@Composable
private fun StageSteps(current: Int, fraction: Float) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (s in SaveStep.entries) {
            val done = s.ordinal < current
            val now = s.ordinal == current
            val fill = when { done -> BrutalDark.Mint; now -> BrutalDark.Orange; else -> BrutalDark.Raised }
            val ink = if (done || now) Brutal.Ink else BrutalDark.MutedText
            Row(
                Modifier.weight(1f).brutalDark(fill = fill, shadow = if (now) 3.dp else 0.dp, border = if (done || now) Brutal.Ink else BrutalDark.Line.copy(alpha = 0.4f))
                    .padding(horizontal = 8.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                if (done) {
                    Icon(Icons.Rounded.Check, null, tint = ink, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    if (now && fraction > 0f) "${s.label.uppercase()} ${(fraction * 100).toInt()}%" else s.label.uppercase(),
                    style = Type.Tiny, color = ink, maxLines = 1,
                )
            }
        }
    }
}

// ---- Non-ready phases ------------------------------------------------------------------------

@Composable
fun VideoEditorLoading(p: VideoEditorPhase.Loading, onClose: () -> Unit) {
    EditorBackdrop {
        Column(Modifier.widthIn(max = 440.dp).fillMaxWidth().padding(24.dp)) {
            Text(p.title.uppercase(), style = Type.Label, color = BrutalDark.Orange)
            Spacer(Modifier.height(6.dp))
            Text(p.fraction?.let { "${(it * 100).toInt()}%" } ?: "…", style = Type.Huge, color = BrutalDark.Text)
            Spacer(Modifier.height(16.dp))
            DarkProgress(p.fraction, height = 18.dp, moving = p.fraction == null)
            Spacer(Modifier.height(10.dp))
            Text(p.detail, style = Type.Small, color = BrutalDark.MutedText, maxLines = 2)
            val strip = p.strip
            if (strip != null) {
                Spacer(Modifier.height(18.dp))
                Text("PREPARING THE TIMELINE", style = Type.Tiny, color = BrutalDark.MutedText)
                Spacer(Modifier.height(6.dp))
                // The trim strip fills from the server while the original downloads.
                Row(Modifier.fillMaxWidth().height(44.dp).brutalDark(fill = BrutalDark.Raised, shadow = 3.dp).clip(RoundedCornerShape(6.dp))) {
                    val shown = 8
                    for (i in 0 until shown) {
                        val idx = (i * strip.count / shown).coerceIn(0, strip.count - 1)
                        val f = strip.frames.getOrNull(idx)
                        Box(Modifier.weight(1f).fillMaxHeight().background(BrutalDark.Raised)) {
                            if (f != null) Image(f, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        }
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
            DarkButton("Cancel", onClose, primary = false)
        }
    }
}

@Composable
fun VideoReEditChoice(onContinue: () -> Unit, onThisVersion: () -> Unit, onClose: () -> Unit) {
    EditorBackdrop {
        Column(Modifier.widthIn(max = 460.dp).fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("EDITED IN PHOTOS", style = Type.Label, color = BrutalDark.Orange)
            Text("Pick up where you left off?", style = Type.Display, color = BrutalDark.Text)
            Text("This video was saved from an edit. Its original, every cut and every slider are still available.", style = Type.Body, color = BrutalDark.MutedText)
            ChoiceCard(
                title = "Continue editing from original",
                body = "Reopens the original with your previous trims and looks, so nothing is applied twice.",
                primary = true, onClick = onContinue,
            )
            ChoiceCard(title = "Edit this version", body = "Starts fresh on top of the edited video as it is now.", primary = false, onClick = onThisVersion)
            DarkButton("Cancel", onClose, primary = false)
        }
    }
}

@Composable
private fun VideoEditorFailed(p: VideoEditorPhase.Failed, onRetry: () -> Unit, onEditThisVersion: () -> Unit, onClose: () -> Unit) {
    EditorBackdrop {
        Column(Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DarkTag("CAN'T OPEN", fill = BrutalDark.Red)
            Text(p.message, style = Type.Title, color = BrutalDark.Text)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DarkButton("Close", onClose, primary = false)
                if (p.canEditThisVersion) DarkButton("Edit this version", onEditThisVersion) else DarkButton("Retry", onRetry)
            }
        }
    }
}
