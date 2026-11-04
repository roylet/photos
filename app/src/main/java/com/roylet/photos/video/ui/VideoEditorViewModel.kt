package com.roylet.photos.video.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roylet.photos.editor.save.SaveMode
import com.roylet.photos.immich.EditAssetInfo
import com.roylet.photos.immich.ImmichApi
import com.roylet.photos.immich.ImmichException
import com.roylet.photos.immich.TimeBucketAssetsDto
import com.roylet.photos.immich.asset
import com.roylet.photos.immich.assetInfo
import com.roylet.photos.immich.getMetadata
import com.roylet.photos.immich.videoPlaybackUrl
import com.roylet.photos.ui.viewer.VideoFrameStrip
import com.roylet.photos.util.Format
import com.roylet.photos.video.export.EncoderCaps
import com.roylet.photos.video.export.SourceProbe
import com.roylet.photos.video.io.FrameStripProvider
import com.roylet.photos.video.model.VideoEdit
import com.roylet.photos.video.save.VideoEditMetadata
import com.roylet.photos.video.save.VideoSaveManager
import com.roylet.photos.video.save.VideoSaveProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import kotlin.math.abs

/** What the video editor screen is showing. */
sealed interface VideoEditorPhase {
    /** Fetching details or downloading the original; [strip] fills from the server meanwhile. */
    data class Loading(val title: String, val detail: String, val fraction: Float?, val strip: VideoFrameStrip? = null) : VideoEditorPhase
    /** The video was saved by this editor: continue from its source, or edit it as it is. */
    data class Choose(val asset: EditAssetInfo, val sourceId: String, val edit: VideoEdit) : VideoEditorPhase
    data class Ready(val session: VideoEditorSession, val target: EditAssetInfo, val sourceId: String) : VideoEditorPhase
    data class Failed(val message: String, val canEditThisVersion: Boolean = false) : VideoEditorPhase
}

/**
 * Opens [assetId] for video editing: details and `photos.videoEdit` metadata, then the original
 * (shared with the save worker through [manager]'s originals cache); the trim strip starts
 * filling over HTTP while that downloads. Saves go to [manager] and survive leaving the screen.
 */
class VideoEditorViewModel(
    private val assetId: String,
    private val api: () -> ImmichApi?,
    private val manager: VideoSaveManager,
    private val http: OkHttpClient?,
    private val frames: FrameStripProvider = FrameStripProvider(),
) : ViewModel() {
    private val _phase = MutableStateFlow<VideoEditorPhase>(VideoEditorPhase.Loading("Opening", "Fetching video details", null))
    val phase: StateFlow<VideoEditorPhase> = _phase.asStateFlow()

    private val _saveJob = MutableStateFlow<String?>(null)
    val saveJob: StateFlow<String?> = _saveJob.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val saveProgress: StateFlow<VideoSaveProgress?> = _saveJob
        .flatMapLatest { id -> if (id == null) flowOf(null) else manager.progress(id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private var target: EditAssetInfo? = null
    private var loadJob: Job? = null

    init { start() }

    fun retry() = start()

    private fun start() = load {
        _phase.value = VideoEditorPhase.Loading("Opening", "Fetching video details", null)
        val api = api() ?: throw ImmichException("Sign in to edit videos.")
        val info = api.assetInfo(assetId)
        if (!info.type.equals("VIDEO", ignoreCase = true)) throw ImmichException("This isn't a video.")
        target = info
        val meta = runCatching { api.getMetadata(assetId, VideoEditMetadata.KEY) }.getOrNull()?.let(VideoEditMetadata::decode)
        if (meta != null && meta.first != assetId) _phase.value = VideoEditorPhase.Choose(info, meta.first, meta.second)
        else open(api, info, info, null)
    }

    /** Re-edit: open the original the edit was made from, with that edit. */
    fun continueFromOriginal() {
        val choose = _phase.value as? VideoEditorPhase.Choose ?: return
        load(onError = { "The original couldn't be opened (${ImmichApi.describe(it)})." to true }) {
            val api = api() ?: throw ImmichException("Sign in to edit videos.")
            _phase.value = VideoEditorPhase.Loading("Opening", "Finding the original", null)
            val source = api.assetInfo(choose.sourceId)
            open(api, source, choose.asset, choose.edit)
        }
    }

    fun editThisVersion() {
        val info = target ?: run { start(); return }
        load { open(api() ?: throw ImmichException("Sign in to edit videos."), info, info, null) }
    }

    private fun load(onError: (Exception) -> Pair<String, Boolean> = { ImmichApi.describe(it) to false }, block: suspend () -> Unit) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val (msg, canEdit) = onError(e)
                _phase.value = VideoEditorPhase.Failed(msg, canEdit)
            }
        }
    }

    private suspend fun open(api: ImmichApi, source: EditAssetInfo, target: EditAssetInfo, edit: VideoEdit?) {
        // The strip can start from the server while the original downloads.
        val hintMs = edit?.sourceDurationMs ?: runCatching { TimeBucketAssetsDto.parseDurationMs(api.asset(source.id).duration) }.getOrNull() ?: 0L
        val remoteStrip = if (hintMs > 0) VideoFrameStrip(FrameStripProvider.sampleTimes(hintMs, VideoEditorSession.STRIP_FRAMES)) else null
        val remoteJob = if (remoteStrip != null && http != null && manager.originals.cached(source) == null) {
            viewModelScope.launch { runCatching { frames.fillRemote(remoteStrip, http, api.videoPlaybackUrl(source.id)) } }
        } else null

        val name = source.originalFileName
        _phase.value = VideoEditorPhase.Loading("Downloading original", name, 0f, remoteStrip)
        val file = try {
            manager.originals.obtain(api, source) { done, total ->
                val detail = if (total > 0) "$name · ${Format.bytes(done)} of ${Format.bytes(total)}" else "$name · ${Format.bytes(done)}"
                _phase.value = VideoEditorPhase.Loading("Downloading original", detail, if (total > 0) done.toFloat() / total else null, remoteStrip)
            }
        } finally {
            remoteJob?.cancel()
        }

        _phase.value = VideoEditorPhase.Loading("Preparing", "Reading $name", null, remoteStrip)
        val info = withContext(Dispatchers.IO) {
            runCatching { SourceProbe.probe(file) }.getOrElse {
                throw ImmichException("This video can't be read on this phone (${it.message ?: it.javaClass.simpleName}).")
            }
        }
        val duration = info.durationMs.takeIf { it > 0 } ?: hintMs
        if (duration <= 0) throw ImmichException("This video has no length.")
        // Keep the server frames when the strip is sampled close enough to the real length.
        val strip = remoteStrip?.takeIf { abs(hintMs - duration) <= maxOf(200L, duration / 100) }
            ?: VideoFrameStrip(FrameStripProvider.sampleTimes(duration, VideoEditorSession.STRIP_FRAMES))
        val initial = (edit?.copy(sourceDurationMs = duration) ?: VideoEdit.initial(duration)).normalized()
        val caps = withContext(Dispatchers.Default) { runCatching { SourceProbe.encoderCaps(info) }.getOrDefault(EncoderCaps()) }
        val session = VideoEditorSession(
            sourceFile = file, source = info.copy(durationMs = duration), caps = caps, initial = initial, strip = strip, frames = frames,
        )
        _phase.value = VideoEditorPhase.Ready(session, target, source.id)
        viewModelScope.launch {
            frames.fill(strip, file)
        }
        viewModelScope.launch {
            val poster = frames.frame(file, initial.segments.first().startMs + minOf(1000L, initial.segments.first().durationMs / 2), maxPx = 480)
            if (poster != null) {
                session.updatePoster(poster)
                withContext(Dispatchers.Default) { runCatching { session.renderFilterThumbs() } }
            }
        }
    }

    fun save(mode: SaveMode, group: Boolean) {
        val ready = _phase.value as? VideoEditorPhase.Ready ?: return
        if (_saveJob.value != null && saveProgress.value is VideoSaveProgress.Running) return
        ready.session.history.endGesture()
        _saveJob.value = manager.enqueue(ready.target.id, ready.sourceId, ready.session.history.edit, mode, group)
    }

    /** Stops the running save (nothing on the server is touched). */
    fun cancelSave() {
        val id = _saveJob.value ?: return
        manager.cancel(id)
    }

    /** Dismisses a failed or cancelled save so the user can try again. */
    fun clearSave() {
        _saveJob.value?.let(manager::clear)
        _saveJob.value = null
    }
}
