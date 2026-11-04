package com.roylet.photos.video.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Undo/redo for a [VideoEdit], with the same rules as the photo editor's
 * [com.roylet.photos.editor.recipe.History]: every [set]/[update] outside a gesture is one step;
 * between [beginGesture] and [endGesture] (a slider or trim-handle drag) changes are live but only
 * the net change becomes a step. Not thread-safe: call from the main thread.
 *
 * (A near copy of `History`, which is typed to `Recipe`; unify them as a generic history later.)
 */
class VideoHistory(initial: VideoEdit, private val maxSteps: Int = 100) {
    private val undoStack = ArrayDeque<VideoEdit>()
    private val redoStack = ArrayDeque<VideoEdit>()
    private var gestureStart: VideoEdit? = null

    private val _current = MutableStateFlow(initial)
    private val _canUndo = MutableStateFlow(false)
    private val _canRedo = MutableStateFlow(false)

    val current: StateFlow<VideoEdit> = _current.asStateFlow()
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    val edit: VideoEdit get() = _current.value
    val inGesture: Boolean get() = gestureStart != null

    fun set(edit: VideoEdit) {
        val old = _current.value
        if (edit == old) return
        if (gestureStart == null) push(old)
        _current.value = edit
    }

    fun update(transform: (VideoEdit) -> VideoEdit) = set(transform(_current.value))

    /** Starts coalescing changes. Nested calls are ignored. */
    fun beginGesture() {
        if (gestureStart == null) gestureStart = _current.value
    }

    /** Ends coalescing; records one step if the edit changed since [beginGesture]. */
    fun endGesture() {
        val start = gestureStart ?: return
        gestureStart = null
        if (start != _current.value) push(start)
    }

    /** Drops the in-flight gesture, restoring the edit from before it. */
    fun cancelGesture() {
        val start = gestureStart ?: return
        gestureStart = null
        _current.value = start
    }

    fun undo(): Boolean {
        endGesture()
        val prev = undoStack.removeLastOrNull() ?: return false
        redoStack.addLast(_current.value)
        _current.value = prev
        refresh()
        return true
    }

    fun redo(): Boolean {
        endGesture()
        val next = redoStack.removeLastOrNull() ?: return false
        undoStack.addLast(_current.value)
        _current.value = next
        refresh()
        return true
    }

    /** Replaces everything (e.g. when another video is opened). */
    fun reset(edit: VideoEdit) {
        undoStack.clear(); redoStack.clear(); gestureStart = null
        _current.value = edit
        refresh()
    }

    private fun push(old: VideoEdit) {
        undoStack.addLast(old)
        while (undoStack.size > maxSteps) undoStack.removeFirst()
        redoStack.clear()
        refresh()
    }

    private fun refresh() {
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
    }
}
