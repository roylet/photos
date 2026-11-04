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
import com.roylet.photos.immich.assetInfo
import com.roylet.photos.immich.downloadOriginal
import com.roylet.photos.immich.getMetadata
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
    data class Ready(val session: EditorSession, val target: EditAssetInfo, val renderSourceId: String, val sourceFile: File) : EditorPhase
    data class Failed(val message: String, val canEditThisVersion: Boolean = false) : EditorPhase
}

/** Opens [assetId] for editing: metadata check, original download, decode; then hands saves to [edits]. */
class EditorViewModel(
    private val assetId: String,
    private val api: () -> ImmichApi?,
    private val cacheDir: File,
    private val edits: EditSaveManager,
) : ViewModel() {
    private val _phase = MutableStateFlow<EditorPhase>(EditorPhase.Loading("Opening", "Fetching photo details", null))
    val phase: StateFlow<EditorPhase> = _phase.asStateFlow()

    private val _saveJob = MutableStateFlow<String?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val saveProgress: StateFlow<SaveProgress?> = _saveJob
        .flatMapLatest { id -> if (id == null) flowOf(null) else edits.progress(id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private var target: EditAssetInfo? = null
    private var loadJob: Job? = null

    init { start() }

    fun retry() = start()

    private fun start() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _phase.value = EditorPhase.Loading("Opening", "Fetching photo details", null)
            try {
                val api = api() ?: throw ImmichException("Sign in to edit photos.")
                val info = api.assetInfo(assetId)
                if (info.type != "IMAGE") throw ImmichException("Only photos can be edited for now; video editing is coming.")
                target = info
                val meta = runCatching { api.getMetadata(assetId, RecipeMetadata.KEY) }.getOrNull()?.let(RecipeMetadata::decode)
                if (meta != null && meta.first != assetId) {
                    _phase.value = EditorPhase.Choose(info, meta.first, meta.second)
                } else {
                    open(api, info, info, Recipe())
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
                val api = api() ?: throw ImmichException("Sign in to edit photos.")
                _phase.value = EditorPhase.Loading("Opening", "Finding the original", null)
                val source = api.assetInfo(choose.sourceId)
                open(api, source, choose.asset, choose.recipe)
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
                open(api() ?: throw ImmichException("Sign in to edit photos."), info, info, Recipe())
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _phase.value = EditorPhase.Failed(ImmichApi.describe(e))
            }
        }
    }

    private suspend fun open(api: ImmichApi, source: EditAssetInfo, target: EditAssetInfo, recipe: Recipe) {
        val dir = File(cacheDir, "editor/${source.id}-${Integer.toHexString(source.checksum.hashCode())}")
        val file = File(dir, safeName(source.originalFileName))
        if (!file.exists()) {
            pruneCache(keep = dir)
            val name = source.originalFileName
            _phase.value = EditorPhase.Loading("Downloading original", name, 0f)
            api.downloadOriginal(source.id, file, edited = source.isEdited) { done, total ->
                val detail = if (total > 0) "$name · ${Format.bytes(done)} of ${Format.bytes(total)}" else "$name · ${Format.bytes(done)}"
                _phase.value = EditorPhase.Loading("Downloading original", detail, if (total > 0) done.toFloat() / total else null)
            }
        }
        _phase.value = EditorPhase.Loading("Preparing", "Decoding ${source.originalFileName}", null)
        val loaded = withContext(Dispatchers.Default) {
            runCatching { SourceLoader.load(file) }.getOrElse {
                throw ImmichException("This file can't be decoded on this phone (${it.message ?: it.javaClass.simpleName}).")
            }
        }
        val session = EditorSession(loaded.proxy, loaded.width, loaded.height, recipe)
        _phase.value = EditorPhase.Ready(session, target, source.id, file)
        viewModelScope.launch(Dispatchers.Default) { runCatching { session.renderFilterThumbs() } }
    }

    fun save(mode: SaveMode, group: Boolean) {
        val ready = _phase.value as? EditorPhase.Ready ?: return
        if (_saveJob.value != null && saveProgress.value is SaveProgress.Running) return
        ready.session.history.endGesture()
        _saveJob.value = edits.enqueue(
            targetId = ready.target.id,
            renderSourceId = ready.renderSourceId,
            source = ready.sourceFile,
            originalFilename = ready.target.originalFileName,
            recipe = ready.session.history.recipe,
            mode = mode,
            group = group,
        )
    }

    /** Dismisses a failed save so the user can try again. */
    fun clearSave() {
        _saveJob.value?.let(edits::clear)
        _saveJob.value = null
    }

    private fun pruneCache(keep: File) {
        val root = keep.parentFile ?: return
        root.listFiles()?.filter { it != keep }?.sortedByDescending { it.lastModified() }?.drop(2)?.forEach { it.deleteRecursively() }
    }

    private fun safeName(name: String) = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "photo" }
}
