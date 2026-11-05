package com.roylet.photos.video.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roylet.photos.editor.save.SaveMode
import com.roylet.photos.domain.source.AssetRef
import com.roylet.photos.editor.save.EditSource
import com.roylet.photos.editor.save.ImmichEditSource
import com.roylet.photos.editor.save.SaveDestination
import com.roylet.photos.editor.save.SaveRequest
import com.roylet.photos.editor.save.SaveStarter
import com.roylet.photos.immich.EditAssetInfo
import com.roylet.photos.immich.ImmichApi
import com.roylet.photos.immich.ImmichException
import com.roylet.photos.immich.TimeBucketAssetsDto
import com.roylet.photos.immich.asset
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
import java.io.Closeable
import kotlin.math.abs

/** What the video editor screen is showing. */
sealed interface VideoEditorPhase {
    /** Fetching details or downloading the original; [strip] fills from the server meanwhile. */
    data class Loading(val title: String, val detail: String, val fraction: Float?, val strip: VideoFrameStrip? = null) : VideoEditorPhase
    /** The video was saved by this editor: continue from its source, or edit it as it is. */
    data class Choose(val asset: EditAssetInfo, val sourceId: String, val edit: VideoEdit) : VideoEditorPhase
    data class Ready(
        val session: VideoEditorSession,
        val target: EditAssetInfo,
        val sourceId: String,
        /** Where the save sheet can save to; the first is the default (where the video lives). */
        val destinations: List<SaveDestination> = listOf(SaveDestination.IMMICH),
        /** The Immich copy of a phone video, for saves that include Immich. */
        val remoteTargetId: String? = null,
    ) : VideoEditorPhase
    data class Failed(val message: String, val canEditThisVersion: Boolean = false) : VideoEditorPhase
}

/**
 * Opens [assetId] for video editing: details and `photos.videoEdit` metadata, then the original
 * (shared with the save worker through [manager]'s originals cache); the trim strip starts
 * filling over HTTP while that downloads. Saves go to [manager] and survive leaving the screen.
 * Leaving the screen clears the view model, which cancels a download in progress.
 */
class VideoEditorViewModel(
    private val assetId: String,
    private val api: () -> ImmichApi?,
    private val manager: VideoSaveManager,
    private val http: OkHttpClient?,
    private val frames: FrameStripProvider = FrameStripProvider(),
    /** Where the original comes from: Immich or this phone. */
    private val source: () -> EditSource = { ImmichEditSource(api) },
    private val remoteCopy: suspend (String) -> String? = { null },
    private val starter: SaveStarter = SaveStarter { true },
) : ViewModel() {
    private val _destination = MutableStateFlow<SaveDestination?>(null)
    /** The save sheet's destination choice. */
    val destination: StateFlow<SaveDestination?> = _destination.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    /** A one-off message (e.g. the user declined the consent dialog). */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    fun setDestination(d: SaveDestination) { _destination.value = d }
    fun consumeNotice() { _notice.value = null }
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
    /** Keeps the open original in the cache while this editor uses it (see [com.roylet.photos.video.io.VideoOriginals.hold]). */
    private var held: Closeable? = null

    init { start() }

    fun retry() = start()

    private fun start() = load {
        _phase.value = VideoEditorPhase.Loading("Opening", "Fetching video details", null)
        val src = source()
        val info = src.info(assetId)
        if (!info.type.equals("VIDEO", ignoreCase = true)) throw ImmichException("This isn't a video.")
        target = info
        val meta = src.metadata(assetId, VideoEditMetadata.KEY)?.let(VideoEditMetadata::decode)
        if (meta != null && meta.first != assetId) _phase.value = VideoEditorPhase.Choose(info, meta.first, meta.second)
        else open(info, info, null)
    }

    /** Re-edit: open the original the edit was made from, with that edit. */
    fun continueFromOriginal() {
        val choose = _phase.value as? VideoEditorPhase.Choose ?: return
        load(onError = { "The original couldn't be opened (${ImmichApi.describe(it)})." to true }) {
            _phase.value = VideoEditorPhase.Loading("Opening", "Finding the original", null)
            val original = source().info(choose.sourceId)
            open(original, choose.asset, choose.edit)
        }
    }

    fun editThisVersion() {
        val info = target ?: run { start(); return }
        load { open(info, info, null) }
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

    private suspend fun open(source: EditAssetInfo, target: EditAssetInfo, edit: VideoEdit?) {
        // An Immich video's strip can start from the server while the original downloads.
        val onPhone = AssetRef.isLocal(source.id)
        val api = if (onPhone) null else api()
        val hintMs = edit?.sourceDurationMs ?: api?.let { a -> runCatching { TimeBucketAssetsDto.parseDurationMs(a.asset(source.id).duration) }.getOrNull() } ?: 0L
        val remoteStrip = if (hintMs > 0) VideoFrameStrip(FrameStripProvider.sampleTimes(hintMs, VideoEditorSession.STRIP_FRAMES)) else null
        val remoteJob = if (api != null && remoteStrip != null && http != null && manager.originals.cached(source) == null) {
            viewModelScope.launch { runCatching { frames.fillRemote(remoteStrip, http, api.videoPlaybackUrl(source.id)) } }
        } else null

        val name = source.originalFileName
        val title = if (onPhone) "Opening original" else "Downloading original"
        _phase.value = VideoEditorPhase.Loading(title, name, 0f, remoteStrip)
        val file = try {
            manager.originals.obtain(source(), source) { done, total ->
                val detail = if (total > 0) "$name · ${Format.bytes(done)} of ${Format.bytes(total)}" else "$name · ${Format.bytes(done)}"
                _phase.value = VideoEditorPhase.Loading(title, detail, if (total > 0) done.toFloat() / total else null, remoteStrip)
            }
        } finally {
            remoteJob?.cancel()
        }
        held?.close()
        held = manager.originals.hold(file)

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
        val targetOnPhone = AssetRef.isLocal(target.id)
        val remote = if (targetOnPhone) runCatching { remoteCopy(target.id) }.getOrNull() else null
        val destinations = SaveDestination.choicesFor(targetOnPhone, inImmich = !targetOnPhone || remote != null)
        if (_destination.value !in destinations) _destination.value = destinations.first()
        _phase.value = VideoEditorPhase.Ready(session, target, source.id, destinations, remote)
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
        val destination = _destination.value ?: ready.destinations.first()
        val edit = ready.session.history.edit
        viewModelScope.launch {
            val job = starter.start(SaveRequest(ready.target.id, mode, group, destination, ready.remoteTargetId)) { r ->
                manager.enqueue(r.targetId, ready.sourceId, edit, r.mode, r.group, r.destination, r.remoteTargetId)
            }
            if (job == null) _notice.value = "Not saved: Android didn't allow replacing the original. Save as a copy instead?"
            else _saveJob.value = job
        }
    }

    /** Stops the running save (nothing on the server is touched). */
    fun cancelSave() {
        val id = _saveJob.value ?: return
        manager.cancel(id)
    }

    override fun onCleared() {
        held?.close()
        held = null
        frames.clear()
    }

    /** Dismisses a failed or cancelled save so the user can try again. */
    fun clearSave() {
        _saveJob.value?.let(manager::clear)
        _saveJob.value = null
    }
}
