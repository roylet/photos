package com.roylet.photos.editor.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.InvertColors
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PhotoFilter
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.roylet.photos.PhotosApp
import com.roylet.photos.editor.recipe.ParamGroup
import com.roylet.photos.editor.recipe.Params
import com.roylet.photos.editor.recipe.Recipe
import com.roylet.photos.editor.save.SaveMode
import com.roylet.photos.editor.save.SaveProgress
import com.roylet.photos.ui.components.BrutalShape
import com.roylet.photos.ui.components.DarkButton
import com.roylet.photos.ui.components.DarkIconButton
import com.roylet.photos.ui.components.brutalDark
import com.roylet.photos.ui.theme.Brutal
import com.roylet.photos.ui.theme.BrutalDark
import com.roylet.photos.ui.theme.PhotosTheme
import com.roylet.photos.ui.theme.Type
import kotlin.math.abs

/** The editor's tools, in rail order. */
enum class EditorTool(val label: String, val icon: ImageVector) {
    CROP("Crop", Icons.Rounded.Crop),
    LIGHT("Light", Icons.Rounded.WbSunny),
    COLOR("Color", Icons.Rounded.Palette),
    CURVE("Curve", Icons.AutoMirrored.Rounded.ShowChart),
    HSL("HSL", Icons.Rounded.InvertColors),
    EFFECTS("Effects", Icons.Rounded.AutoAwesome),
    FILTERS("Filters", Icons.Rounded.PhotoFilter);

    fun isEdited(r: Recipe): Boolean = when (this) {
        CROP -> !r.geometry.isIdentity
        LIGHT -> !r.light.isIdentity
        COLOR -> r.color.temperature != 0f || r.color.tint != 0f || r.color.vibrance != 0f || r.color.saturation != 0f
        CURVE -> !r.curves.isIdentity
        HSL -> !r.color.isHslIdentity
        EFFECTS -> !r.effects.isIdentity
        FILTERS -> r.filter != null
    }
}

/** Width at which the tools move into a side panel. */
private val WideEditor = 600.dp
private val PhonePanelHeight = 252.dp

/**
 * The photo editor for one Immich asset. Downloads the original, then edits it live on the GPU.
 * [onSaved] gets the new asset's id once a save finishes (the save itself runs as a background
 * job and survives leaving the screen). After a REPLACE, `container.edits.lastReplace` holds what
 * the viewer needs for its undo snackbar.
 */
@Composable
fun EditorScreen(assetId: String, onClose: () -> Unit, onSaved: (newAssetId: String) -> Unit) {
    val app = LocalContext.current.applicationContext as PhotosApp
    val container = app.container
    val vm: EditorViewModel = viewModel(key = "editor-$assetId") {
        EditorViewModel(assetId, container::api, app.cacheDir, container.edits)
    }
    val phase by vm.phase.collectAsStateWithLifecycle()
    val save by vm.saveProgress.collectAsStateWithLifecycle()

    LaunchedEffect(save) {
        (save as? SaveProgress.Done)?.let { onSaved(it.result.newAssetId) }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    PhotosTheme(dark = true) {
        when (val p = phase) {
            is EditorPhase.Loading -> {
                BackHandler(onBack = onClose)
                EditorLoading(p, onClose)
            }
            is EditorPhase.Choose -> {
                BackHandler(onBack = onClose)
                ReEditChoice(p, onContinue = vm::continueFromOriginal, onThisVersion = vm::editThisVersion, onClose = onClose)
            }
            is EditorPhase.Failed -> {
                BackHandler(onBack = onClose)
                EditorFailed(p, onRetry = vm::retry, onEditThisVersion = vm::editThisVersion, onClose = onClose)
            }
            is EditorPhase.Ready -> EditorWorkspace(
                session = p.session,
                onClose = onClose,
                onSave = { mode, group ->
                    // Android 13+: without this the progress and "saved/failed" notifications are hidden.
                    if (needsNotificationPermission(app)) notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    vm.save(mode, group)
                },
                saveProgress = save,
                onDismissSaveError = vm::clearSave,
            )
        }
    }
}

private fun needsNotificationPermission(context: android.content.Context): Boolean =
    android.os.Build.VERSION.SDK_INT >= 33 &&
        androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
        android.content.pm.PackageManager.PERMISSION_GRANTED

/**
 * The editor UI for an open [session]. Stateless apart from UI choices, so screenshot tests can
 * drive it with a fake proxy (no GL: [StaticPreview] stands in for the canvas).
 */
@Composable
fun EditorWorkspace(
    session: EditorSession,
    onClose: () -> Unit,
    onSave: (SaveMode, Boolean) -> Unit,
    saveProgress: SaveProgress?,
    onDismissSaveError: () -> Unit = {},
    initialTool: EditorTool = EditorTool.LIGHT,
    initialSheet: Boolean = false,
) {
    var tool by rememberSaveable { mutableStateOf(initialTool) }
    var sheet by rememberSaveable { mutableStateOf(initialSheet) }
    var confirmClose by remember { mutableStateOf(false) }
    var comparing by remember { mutableStateOf(false) }
    val recipe = session.history.current.collectAsState()

    LaunchedEffect(session) { session.history.current.collect { session.push(it) } }
    LaunchedEffect(session, tool) { session.setCropMode(tool == EditorTool.CROP) }
    LaunchedEffect(session, comparing) { session.setShowOriginal(comparing) }

    val requestClose = { if (session.isDirty && saveProgress == null) confirmClose = true else onClose() }
    BackHandler {
        when {
            sheet -> sheet = false
            else -> requestClose()
        }
    }

    val canvas: @Composable (Modifier) -> Unit = { m ->
        EditorCanvasArea(session, recipe, tool, comparing, onCompare = { comparing = it }, modifier = m)
    }
    val header: @Composable () -> Unit = {
        EditorHeader(session, onClose = requestClose, onSave = { sheet = true })
    }
    val panel: @Composable (Modifier) -> Unit = { m -> ToolPanel(session, recipe, tool, m) }

    Box(Modifier.fillMaxSize().background(BrutalDark.Canvas)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            if (maxWidth >= WideEditor) {
                Column(Modifier.fillMaxSize()) {
                    header()
                    Row(Modifier.weight(1f).fillMaxWidth().navigationBarsPadding()) {
                        canvas(Modifier.weight(1f).fillMaxHeight())
                        Column(
                            Modifier.width(360.dp).fillMaxHeight().padding(8.dp).brutalDark(shadow = 0.dp).padding(vertical = 12.dp),
                        ) {
                            Text(tool.label.uppercase(), style = Type.Title, color = BrutalDark.Text, modifier = Modifier.padding(horizontal = 16.dp))
                            Spacer(Modifier.height(8.dp))
                            panel(Modifier.weight(1f).fillMaxWidth())
                        }
                        ToolRail(tool, recipe, onSelect = { tool = it }, vertical = true, modifier = Modifier.fillMaxHeight().padding(end = 8.dp))
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
                    ToolRail(tool, recipe, onSelect = { tool = it }, vertical = false, modifier = Modifier.fillMaxWidth().navigationBarsPadding())
                }
            }
        }

        SaveSheet(
            visible = sheet && saveProgress == null,
            onDismiss = { sheet = false },
            onSave = { mode, group -> sheet = false; onSave(mode, group) },
        )
        SaveProgressPanel(saveProgress, onBackground = onClose, onDismissError = onDismissSaveError)
    }

    if (confirmClose) {
        DarkConfirm(
            title = "Discard edits?",
            text = "Your changes to this photo haven't been saved.",
            confirm = "Discard",
            onConfirm = { confirmClose = false; onClose() },
            onDismiss = { confirmClose = false },
        )
    }
}

@Composable
private fun EditorHeader(session: EditorSession, onClose: () -> Unit, onSave: () -> Unit) {
    val canUndo by session.history.canUndo.collectAsState()
    val canRedo by session.history.canRedo.collectAsState()
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        DarkIconButton(Icons.Rounded.Close, "Close editor", onClose)
        Spacer(Modifier.weight(1f))
        DarkIconButton(Icons.AutoMirrored.Rounded.Undo, "Undo", { session.history.undo() }, enabled = canUndo)
        DarkIconButton(Icons.AutoMirrored.Rounded.Redo, "Redo", { session.history.redo() }, enabled = canRedo)
        Box {
            DarkIconButton(Icons.Rounded.MoreVert, "More", { menu = true })
            DropdownMenu(
                expanded = menu,
                onDismissRequest = { menu = false },
                shape = BrutalShape,
                containerColor = BrutalDark.Panel,
                border = BorderStroke(2.dp, BrutalDark.Line),
            ) {
                DropdownMenuItem(
                    text = { Text("RESET ALL EDITS", style = Type.Label) },
                    leadingIcon = { Icon(Icons.Rounded.RestartAlt, null) },
                    onClick = { menu = false; session.history.set(Recipe()) },
                    colors = MenuDefaults.itemColors(textColor = BrutalDark.Text, leadingIconColor = BrutalDark.Text),
                )
                DropdownMenuItem(
                    text = { Text("HOLD THE PHOTO TO COMPARE", style = Type.Tiny) },
                    leadingIcon = { Icon(Icons.Rounded.TouchApp, null) },
                    onClick = { menu = false },
                    enabled = false,
                    colors = MenuDefaults.itemColors(disabledTextColor = BrutalDark.MutedText, disabledLeadingIconColor = BrutalDark.MutedText),
                )
            }
        }
        Spacer(Modifier.width(4.dp))
        DarkButton("Save", onSave)
    }
}

/** The preview, plus hold-to-compare and pinch zoom (or the crop overlay in the Crop tool). */
@Composable
private fun EditorCanvasArea(
    session: EditorSession,
    recipe: State<Recipe>,
    tool: EditorTool,
    comparing: Boolean,
    onCompare: (Boolean) -> Unit,
    modifier: Modifier,
) {
    val reduced = rememberReducedMotion()
    val haptics = rememberHaptics()
    val crop = tool == EditorTool.CROP
    Box(modifier.clipToBounds()) {
        if (session.renderer != null) GlPreview(session, Modifier.fillMaxSize())
        else StaticPreview(session, Modifier.fillMaxSize(), cropMode = crop, showOriginal = comparing)

        if (crop) {
            CropOverlay(session, recipe, Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize().compareAndZoom(session, onCompare = { on -> if (on) haptics.press(); onCompare(on) }))
        }
        AnimatedVisibility(
            visible = comparing,
            enter = fadeIn(motion(reduced, tween(140))),
            exit = fadeOut(motion(reduced, tween(180))),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
        ) { DarkTag("ORIGINAL") }
    }
}

/**
 * Hold (still, ~200 ms) shows the original until release; pinch zooms 1–8× around the fingers;
 * one finger pans while zoomed; double tap returns to fit.
 */
private fun Modifier.compareAndZoom(session: EditorSession, onCompare: (Boolean) -> Unit): Modifier = pointerInput(session) {
    var lastTapUp = 0L
    var zoom = 1f
    var pan = Offset.Zero
    fun apply() = session.setView(zoom, pan.x, pan.y)
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (down.uptimeMillis - lastTapUp < 280) {
            zoom = 1f; pan = Offset.Zero; apply()
            lastTapUp = 0L
            do { val e = awaitPointerEvent() } while (e.changes.any { it.pressed })
            return@awaitEachGesture
        }
        val slop = viewConfiguration.touchSlop
        // Undecided: wait for a hold, a move, a second finger or a release.
        val decided = withTimeoutOrNull(200) {
            while (true) {
                val e = awaitPointerEvent()
                if (e.changes.none { it.pressed }) return@withTimeoutOrNull "up"
                if (e.changes.size > 1) return@withTimeoutOrNull "move"
                val c = e.changes.first()
                if ((c.position - down.position).getDistance() > slop) return@withTimeoutOrNull "move"
            }
            @Suppress("UNREACHABLE_CODE") ""
        }
        when (decided) {
            null -> {
                onCompare(true)
                do { val e = awaitPointerEvent() } while (e.changes.any { it.pressed })
                onCompare(false)
            }
            "up" -> lastTapUp = down.uptimeMillis
            else -> {
                do {
                    val e = awaitPointerEvent()
                    val z = e.calculateZoom()
                    val p = e.calculatePan()
                    val centroid = e.calculateCentroid(useCurrent = true)
                    if (z != 1f || (zoom > 1f && p != Offset.Zero)) {
                        val newZoom = (zoom * z).coerceIn(1f, 8f)
                        val c = if (centroid.isSpecified()) centroid - Offset(size.width / 2f, size.height / 2f) else Offset.Zero
                        var np = (pan - c) * (newZoom / zoom) + c + p
                        val maxX = size.width * (newZoom - 1f) / 2f
                        val maxY = size.height * (newZoom - 1f) / 2f
                        np = Offset(np.x.coerceIn(-maxX, maxX), np.y.coerceIn(-maxY, maxY))
                        zoom = newZoom; pan = np
                        apply()
                        e.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                } while (e.changes.any { it.pressed })
            }
        }
    }
}

private fun Offset.isSpecified() = x.isFinite() && y.isFinite()

@Composable
private fun ToolPanel(session: EditorSession, recipe: State<Recipe>, tool: EditorTool, modifier: Modifier) {
    val reduced = rememberReducedMotion()
    AnimatedContent(
        tool,
        modifier = modifier,
        transitionSpec = { fadeIn(motion(reduced, tween(160))) togetherWith fadeOut(motion(reduced, tween(100))) },
        label = "tool",
    ) { t ->
        when (t) {
            EditorTool.CROP -> CropPanel(session, recipe, Modifier.fillMaxSize())
            EditorTool.LIGHT -> SliderPanel(session, recipe, Params.group(ParamGroup.LIGHT), Modifier.fillMaxSize())
            EditorTool.COLOR -> SliderPanel(session, recipe, Params.group(ParamGroup.COLOR), Modifier.fillMaxSize())
            EditorTool.CURVE -> CurvePanel(session, recipe, Modifier.fillMaxSize())
            EditorTool.HSL -> HslPanel(session, recipe, Modifier.fillMaxSize())
            EditorTool.EFFECTS -> SliderPanel(session, recipe, Params.group(ParamGroup.EFFECTS), Modifier.fillMaxSize())
            EditorTool.FILTERS -> FiltersPanel(session, recipe, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun ToolRail(current: EditorTool, recipe: State<Recipe>, onSelect: (EditorTool) -> Unit, vertical: Boolean, modifier: Modifier) {
    val items: @Composable (Modifier) -> Unit = { m ->
        for (t in EditorTool.entries) {
            val edited by remember(t) { derivedStateOf { t.isEdited(recipe.value) } }
            RailItem(t, t == current, edited, { onSelect(t) }, m)
        }
    }
    if (vertical) {
        Column(modifier.width(76.dp).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { items(Modifier.fillMaxWidth()) }
    } else {
        Row(
            modifier.background(BrutalDark.Panel).border(BorderStroke(2.dp, BrutalDark.Line.copy(alpha = 0.12f))).padding(horizontal = 4.dp, vertical = 6.dp),
        ) { items(Modifier.weight(1f)) }
    }
}

@Composable
private fun RailItem(tool: EditorTool, selected: Boolean, edited: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .pointerInput(tool) { detectTap(onClick) }
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            Box(
                Modifier.size(40.dp)
                    .then(if (selected) Modifier.brutalDark(fill = BrutalDark.Orange, shadow = 3.dp, border = Brutal.Ink) else Modifier.padding(end = 3.dp, bottom = 3.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(tool.icon, tool.label, tint = if (selected) Brutal.Ink else BrutalDark.Text, modifier = Modifier.size(22.dp))
            }
            if (edited && !selected) {
                Box(Modifier.align(Alignment.TopEnd).size(8.dp).clip(CircleShape).background(BrutalDark.Orange))
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(tool.label.uppercase(), style = Type.Tiny, color = if (selected) BrutalDark.Orange else BrutalDark.MutedText, maxLines = 1)
    }
}

private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectTap(onTap: () -> Unit) =
    detectTapGestures(onTap = { onTap() })

// --- Non-ready phases ---

@Composable
internal fun EditorLoading(p: EditorPhase.Loading, onClose: () -> Unit) {
    EditorBackdrop {
        Column(Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(24.dp)) {
            Text(p.title.uppercase(), style = Type.Label, color = BrutalDark.Orange)
            Spacer(Modifier.height(6.dp))
            Text(
                p.fraction?.let { "${(it * 100).toInt()}%" } ?: "…",
                style = Type.Huge, color = BrutalDark.Text,
            )
            Spacer(Modifier.height(16.dp))
            DarkProgress(p.fraction, height = 18.dp, moving = p.fraction == null)
            Spacer(Modifier.height(10.dp))
            Text(p.detail, style = Type.Small, color = BrutalDark.MutedText, maxLines = 2)
            Spacer(Modifier.height(28.dp))
            DarkButton("Cancel", onClose, primary = false)
        }
    }
}

@Composable
internal fun ReEditChoice(p: EditorPhase.Choose, onContinue: () -> Unit, onThisVersion: () -> Unit, onClose: () -> Unit) {
    EditorBackdrop {
        Column(Modifier.widthIn(max = 460.dp).fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("EDITED IN PHOTOS", style = Type.Label, color = BrutalDark.Orange)
            Text("Pick up where you left off?", style = Type.Display, color = BrutalDark.Text)
            Text("This photo was saved from an edit. Its original and every slider are still available.", style = Type.Body, color = BrutalDark.MutedText)
            ChoiceCard(
                title = "Continue editing from original",
                body = "Reopens the original with your previous edits, so nothing is applied twice.",
                primary = true, onClick = onContinue,
            )
            ChoiceCard(
                title = "Edit this version",
                body = "Starts fresh on top of the edited photo as it looks now.",
                primary = false, onClick = onThisVersion,
            )
            DarkButton("Cancel", onClose, primary = false)
        }
    }
}

@Composable
internal fun ChoiceCard(title: String, body: String, primary: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, extra: (@Composable ColumnScope.() -> Unit)? = null) {
    val ink = if (primary) Brutal.Ink else BrutalDark.Text
    com.roylet.photos.ui.components.Pressable(
        onClick, modifier.fillMaxWidth(),
        fill = if (primary) BrutalDark.Orange else BrutalDark.Raised,
        shadow = 5.dp, border = if (primary) Brutal.Ink else BrutalDark.Line, shadowColor = Color.Black,
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title.uppercase(), style = Type.Title, color = ink)
            Spacer(Modifier.height(4.dp))
            Text(body, style = Type.Small, color = ink.copy(alpha = 0.8f))
            extra?.invoke(this)
        }
    }
}

@Composable
private fun EditorFailed(p: EditorPhase.Failed, onRetry: () -> Unit, onEditThisVersion: () -> Unit, onClose: () -> Unit) {
    EditorBackdrop {
        Column(Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DarkTag("CAN'T OPEN", fill = BrutalDark.Red)
            Text(p.message, style = Type.Title, color = BrutalDark.Text, textAlign = TextAlign.Start)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DarkButton("Close", onClose, primary = false)
                if (p.canEditThisVersion) DarkButton("Edit this version", onEditThisVersion)
                else DarkButton("Retry", onRetry)
            }
        }
    }
}

/** Absolute difference helper for gesture code. */
internal fun near(a: Float, b: Float, eps: Float) = abs(a - b) < eps
