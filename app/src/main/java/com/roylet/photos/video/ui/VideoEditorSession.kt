package com.roylet.photos.video.ui

import android.graphics.Bitmap
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.roylet.photos.editor.cpu.ReferenceRenderer
import com.roylet.photos.editor.gl.toArgbImage
import com.roylet.photos.editor.recipe.Presets
import com.roylet.photos.editor.recipe.Recipe
import com.roylet.photos.editor.recipe.RecipeEdits
import com.roylet.photos.editor.ui.AspectPreset
import com.roylet.photos.editor.ui.toBitmap
import com.roylet.photos.ui.viewer.VideoFrameStrip
import com.roylet.photos.video.export.EncoderCaps
import com.roylet.photos.video.export.SourceInfo
import com.roylet.photos.video.io.FrameStripProvider
import com.roylet.photos.video.model.VideoEdit
import com.roylet.photos.video.model.VideoHistory
import com.roylet.photos.video.preview.VideoPreviewController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * What the editor needs from the player: the live preview ([VideoPreviewController]) in the app,
 * a fake in screenshot tests. Positions are output time (ms).
 */
interface VideoPlayback {
    val outputPosition: StateFlow<Long>
    val isPlaying: StateFlow<Boolean>
    fun setEdit(edit: VideoEdit)
    fun togglePlay()
    fun pause()
    fun seekTo(outputMs: Long)
}

/** [VideoPlayback] over the real preview controller. */
class ControllerPlayback(val controller: VideoPreviewController) : VideoPlayback {
    override val outputPosition get() = controller.outputPosition
    override val isPlaying get() = controller.isPlaying
    override fun setEdit(edit: VideoEdit) = controller.setEdit(edit)
    override fun togglePlay() = controller.togglePlay()
    override fun pause() = controller.pause()
    override fun seekTo(outputMs: Long) = controller.seekToOutputTime(outputMs)
}

/** A still player for previews and tests: holds a position, never plays. */
class StillPlayback(position: Long = 0L, playing: Boolean = false) : VideoPlayback {
    override val outputPosition = MutableStateFlow(position)
    override val isPlaying = MutableStateFlow(playing)
    private var duration = Long.MAX_VALUE
    override fun setEdit(edit: VideoEdit) {
        duration = edit.outputDurationMs()
        outputPosition.value = outputPosition.value.coerceIn(0, duration)
    }
    override fun togglePlay() { isPlaying.value = !isPlaying.value }
    override fun pause() { isPlaying.value = false }
    override fun seekTo(outputMs: Long) { outputPosition.value = outputMs.coerceIn(0, duration) }
}

/**
 * One open video: the undo [history] of its [VideoEdit], the source facts, the trim strip's
 * frames and the UI state shared between tools (selection, crop aspect, the scrub frame).
 */
@Stable
class VideoEditorSession(
    /** The local original (null in screenshot tests). */
    val sourceFile: File?,
    /** What the source is (upright size, length, codec, bitrate, audio). */
    val source: SourceInfo,
    /** This phone's encoders, for the export estimate. */
    val caps: EncoderCaps = EncoderCaps(),
    /** The edit the session opened with; anything else is unsaved. */
    val initial: VideoEdit,
    /** Frames sampled across the whole source for the trim strip. */
    val strip: VideoFrameStrip,
    /** Decodes more frames when zoomed and the frame under a dragged handle; null in tests. */
    val frames: FrameStripProvider? = null,
    /** A representative frame (filter thumbnails, the static preview). */
    poster: Bitmap? = null,
) {
    val history = VideoHistory(initial)

    val durationMs: Long get() = initial.sourceDurationMs

    /** Upright source size (container rotation applied). */
    val width: Int get() = source.width.coerceAtLeast(1)
    val height: Int get() = source.height.coerceAtLeast(1)

    var poster by mutableStateOf(poster)
        private set

    /** The colour part of the edit, as the photo panels expect it. One undo history for everything. */
    val colorEdits: RecipeEdits = object : RecipeEdits {
        override val recipe: Recipe get() = history.edit.color
        override fun set(recipe: Recipe) = history.update { it.copy(color = recipe) }
        override fun beginGesture() = history.beginGesture()
        override fun endGesture() = history.endGesture()
    }

    /** The selected kept segment on the trim strip, or null. */
    var selected by mutableStateOf<Int?>(null)

    /** Crop aspect lock. */
    var cropAspect by mutableStateOf(AspectPreset.FREE)

    /** While a trim handle is dragged: the exact frame under it (shown over the preview) and which edge. */
    var scrubFrame by mutableStateOf<ImageBitmap?>(null)
    var scrubHandle by mutableStateOf<TrimHandle?>(null)
    var scrubMs by mutableStateOf(0L)

    /** Extra frames decoded for zoomed-in strips, keyed by source time. */
    val zoomFrames = mutableStateMapOf<Long, ImageBitmap>()

    /** Small previews of each built-in filter, keyed by preset id ("" = none). */
    val filterThumbs = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())

    val isDirty: Boolean get() = history.edit != initial

    /** Saving makes sense for anything but an untouched source. */
    val canSave: Boolean get() = !history.edit.isIdentity

    fun updatePoster(bitmap: Bitmap) { poster = bitmap }

    /** Renders [filterThumbs] from [poster] on the CPU. Blocking; call off the main thread. */
    fun renderFilterThumbs(edge: Int = 168) {
        val p = poster ?: return
        val side = minOf(p.width, p.height)
        val sq = Bitmap.createBitmap(p, (p.width - side) / 2, (p.height - side) / 2, side, side)
        val small = Bitmap.createScaledBitmap(sq, edge, edge, true)
        val src = small.toArgbImage()
        val out = LinkedHashMap<String, ImageBitmap>()
        out[""] = small.asImageBitmap()
        for (preset in Presets.all) out[preset.id] = ReferenceRenderer.render(src, Recipe(filter = preset.filter())).toBitmap().asImageBitmap()
        filterThumbs.value = out
    }

    companion object {
        /** Frames in the base strip (zoomed strips decode more on demand). */
        const val STRIP_FRAMES = 32
    }
}
