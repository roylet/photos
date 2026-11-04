package com.roylet.photos.editor.recipe

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Undo/redo for a [Recipe].
 *
 * Every [set]/[update] outside a gesture is one undo step. Between [beginGesture] and
 * [endGesture] (a slider drag, a crop-handle drag) changes update [current] live but only the
 * net change becomes a single step when the gesture ends. Not thread-safe: call from one thread
 * (normally main).
 */
class History(initial: Recipe = Recipe(), private val maxSteps: Int = 100) {
    private val undoStack = ArrayDeque<Recipe>()
    private val redoStack = ArrayDeque<Recipe>()
    private var gestureStart: Recipe? = null

    private val _current = MutableStateFlow(initial)
    private val _canUndo = MutableStateFlow(false)
    private val _canRedo = MutableStateFlow(false)
    private val _gesturing = MutableStateFlow(false)

    val current: StateFlow<Recipe> = _current.asStateFlow()
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()
    /** True between [beginGesture] and [endGesture]/[cancelGesture] (the preview drafts meanwhile). */
    val gesturing: StateFlow<Boolean> = _gesturing.asStateFlow()

    val recipe: Recipe get() = _current.value
    val inGesture: Boolean get() = gestureStart != null

    fun set(recipe: Recipe) {
        val old = _current.value
        if (recipe == old) return
        if (gestureStart == null) push(old)
        _current.value = recipe
    }

    fun update(transform: (Recipe) -> Recipe) = set(transform(_current.value))

    /** Starts coalescing changes. Nested calls are ignored. */
    fun beginGesture() {
        if (gestureStart == null) gestureStart = _current.value
        _gesturing.value = true
    }

    /** Ends coalescing; records one step if the recipe changed since [beginGesture]. */
    fun endGesture() {
        val start = gestureStart ?: return
        gestureStart = null
        _gesturing.value = false
        if (start != _current.value) push(start)
    }

    /** Drops the in-flight gesture, restoring the recipe from before it. */
    fun cancelGesture() {
        val start = gestureStart ?: return
        gestureStart = null
        _gesturing.value = false
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

    /** Replaces everything (e.g. when a new photo is opened). */
    fun reset(recipe: Recipe = Recipe()) {
        undoStack.clear(); redoStack.clear(); gestureStart = null
        _gesturing.value = false
        _current.value = recipe
        refresh()
    }

    private fun push(old: Recipe) {
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
