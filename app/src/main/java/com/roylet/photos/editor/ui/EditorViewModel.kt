package com.roylet.photos.editor.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roylet.photos.editor.io.SourceLoader
import com.roylet.photos.editor.recipe.Recipe
import com.roylet.photos.editor.save.EditSaveManager
import com.roylet.photos.editor.save.RecipeMetadata
import com.roylet.photos.editor.save.SaveMode
import com.roylet.photos.editor.save.SaveProgress
import com.roylet.photos.immich.EditAssetInfo
import com.roylet.photos.immich.ImmichApi
import com.roylet.photos.immich.ImmichException
import com.roylet.photos.domain.source.AssetRef
import com.roylet.photos.editor.save.EditSource
import com.roylet.photos.editor.save.SaveDestination
import com.roylet.photos.editor.save.SaveRequest
import com.roylet.photos.editor.save.SaveStarter
import com.roylet.photos.util.Format
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
import java.io.File

/** What the editor screen is showing. */
sealed interface EditorPhase {
    data class Loading(val title: String, val detail: String, val fraction: Float?) : EditorPhase
    /** The photo was made by this editor: continue from its source, or edit the pixels as they are. */
    data class Choose(val asset: EditAssetInfo, val sourceId: String, val recipe: Recipe) : EditorPhase
    data class Ready(
        val session: EditorSession,
        val target: EditAssetInfo,
        val renderSourceId: String,
        val sourceFile: File,
        /** Where the save sheet can save to; the first is the default (where the item lives). */
        val destinations: List<SaveDestination> = listOf(SaveDestination.IMMICH),
        /** The Immich copy of a phone item, for saves that include Immich. */
        val remoteTargetId: String? = null,
    ) : EditorPhase
    data class Failed(val message: String, val canEditThisVersion: Boolean = false) : EditorPhase
}

/**
 * Opens [assetId] for editing: details and the saved recipe from [source] (Immich or this phone),
 * the original, decode; then hands saves to [edits]. A phone item with an Immich copy
 * ([remoteCopy]) can be saved to this device, Immich or both; a Replace on the phone asks for
 * consent first ([starter]).
 */
class EditorViewModel(
    private val assetId: String,
    private val source: () -> EditSource,
    private val cacheDir: File,
    private val edits: EditSaveManager,
    private val remoteCopy: suspend (String) -> String? = { null },
    private val starter: SaveStarter = SaveStarter { true },
) : ViewModel() {
    private val _phase = MutableStateFlow<EditorPhase>(EditorPhase.Loading("Opening", "Fetching photo details", null))
    val phase: StateFlow<EditorPhase> = _phase.asStateFlow()

    private val _saveJob = MutableStateFlow<String?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val saveProgress: StateFlow<SaveProgress?> = _saveJob
        .flatMapLatest { id -> if (id == null) flowOf(null) else edits.progress(id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _destination = MutableStateFlow<SaveDestination?>(null)
    /** The save sheet's destination choice (null until the photo is open). */
    val destination: StateFlow<SaveDestination?> = _destination.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    /** A one-off message for the editor (e.g. the user declined the consent dialog). */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private var target: EditAssetInfo? = null
    private var loadJob: Job? = null

    init { start() }

    fun retry() = start()

    private fun start() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _phase.value = EditorPhase.Loading("Opening", "Fetching photo details", null)
            try {
                val src = source()
                val info = src.info(assetId)
                if (info.type != "IMAGE") throw ImmichException("Only photos can be edited here; videos open in the video editor.")
                target = info
                val meta = src.metadata(assetId, RecipeMetadata.KEY)?.let(RecipeMetadata::decode)
                if (meta != null && meta.first != assetId) {
                    _phase.value = EditorPhase.Choose(info, meta.first, meta.second)
                } else {
                    open(info, info, Recipe())
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _phase.value = EditorPhase.Failed(ImmichApi.describe(e))
            }
        }
    }

    /** Re-edit: load the original the recipe was made from, with the recipe. */
    fun continueFromOriginal() {
        val choose = _phase.value as? EditorPhase.Choose ?: return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                _phase.value = EditorPhase.Loading("Opening", "Finding the original", null)
                val original = source().info(choose.sourceId)
                open(original, choose.asset, choose.recipe)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _phase.value = EditorPhase.Failed("The original couldn't be opened (${ImmichApi.describe(e)}).", canEditThisVersion = true)
            }
        }
    }

    fun editThisVersion() {
        val info = target ?: return start()
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                open(info, info, Recipe())
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _phase.value = EditorPhase.Failed(ImmichApi.describe(e))
            }
        }
    }

    private suspend fun open(original: EditAssetInfo, target: EditAssetInfo, recipe: Recipe) {
        val dir = File(cacheDir, "editor/${original.id.replace(':', '_')}-${Integer.toHexString(original.checksum.hashCode())}")
        val file = File(dir, safeName(original.originalFileName))
        if (!file.exists()) {
            withContext(Dispatchers.IO) { EditorCache.prune(dir.parentFile ?: cacheDir, keep = dir, protect = edits.pendingSourceFiles()) }
            val name = original.originalFileName
            val phone = AssetRef.isLocal(original.id)
            val title = if (phone) "Opening original" else "Downloading original"
            _phase.value = EditorPhase.Loading(title, name, 0f)
            source().obtain(original, file) { done, total ->
                val detail = if (total > 0) "$name · ${Format.bytes(done)} of ${Format.bytes(total)}" else "$name · ${Format.bytes(done)}"
                _phase.value = EditorPhase.Loading(title, detail, if (total > 0) done.toFloat() / total else null)
            }
        }
        _phase.value = EditorPhase.Loading("Preparing", "Decoding ${original.originalFileName}", null)
        val loaded = withContext(Dispatchers.Default) {
            runCatching { SourceLoader.load(file) }.getOrElse {
                throw ImmichException("This file can't be decoded on this phone (${it.message ?: it.javaClass.simpleName}).")
            }
        }
        val onPhone = AssetRef.isLocal(target.id)
        val remote = if (onPhone) runCatching { remoteCopy(target.id) }.getOrNull() else null
        val destinations = SaveDestination.choicesFor(onPhone, inImmich = !onPhone || remote != null)
        if (_destination.value !in destinations) _destination.value = destinations.first()
        val session = EditorSession(loaded.proxy, loaded.width, loaded.height, recipe)
        _phase.value = EditorPhase.Ready(session, target, original.id, file, destinations, remote)
        viewModelScope.launch(Dispatchers.Default) { runCatching { session.renderFilterThumbs() } }
    }

    fun setDestination(d: SaveDestination) { _destination.value = d }

    fun consumeNotice() { _notice.value = null }

    fun save(mode: SaveMode, group: Boolean) {
        val ready = _phase.value as? EditorPhase.Ready ?: return
        if (_saveJob.value != null && saveProgress.value is SaveProgress.Running) return
        ready.session.history.endGesture()
        val destination = _destination.value ?: ready.destinations.first()
        val request = SaveRequest(ready.target.id, mode, group, destination, ready.remoteTargetId)
        val recipe = ready.session.history.recipe
        viewModelScope.launch {
            val job = starter.start(request) { r ->
                edits.enqueue(
                    targetId = r.targetId,
                    renderSourceId = ready.renderSourceId,
                    source = ready.sourceFile,
                    originalFilename = ready.target.originalFileName,
                    recipe = recipe,
                    mode = r.mode,
                    group = r.group,
                    destination = r.destination,
                    remoteTargetId = r.remoteTargetId,
                )
            }
            if (job == null) _notice.value = "Not saved: Android didn't allow replacing the original. Save as a copy instead?"
            else _saveJob.value = job
        }
    }

    /** Dismisses a failed save so the user can try again. */
    fun clearSave() {
        _saveJob.value?.let(edits::clear)
        _saveJob.value = null
    }

    private fun safeName(name: String) = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "photo" }
}

/** The editor's download cache: one folder per opened original, under `cacheDir/editor`. */
internal object EditorCache {
    /**
     * Deletes all but the [keepRecent] newest folders in [root] besides [keep] — except folders
     * holding a file in [protect] (the originals that queued or running saves still have to
     * render from; see `EditSaveManager.pendingSourceFiles`).
     */
    fun prune(root: File, keep: File, protect: Set<File>, keepRecent: Int = 2) {
        val busy = protect.map { it.absoluteFile }
        fun inUse(dir: File): Boolean {
            val d = dir.absoluteFile
            return busy.any { f -> generateSequence(f.parentFile) { it.parentFile }.any { it == d } || f == d }
        }
        root.listFiles()?.filter { it != keep && !inUse(it) }?.sortedByDescending { it.lastModified() }?.drop(keepRecent)
            ?.forEach { it.deleteRecursively() }
    }
}
