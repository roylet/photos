package com.roylet.photos.editor.gl

import android.opengl.GLSurfaceView
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * The GPU preview of an [EditRenderer] (a `GLSurfaceView` in an `AndroidView`). It follows the
 * lifecycle (pauses the GL thread when the screen stops).
 *
 * With [zoomable], pinch zooms (1×–8×) around the fingers, drag pans while zoomed and double tap
 * resets. Pass `zoomable = false` to handle gestures yourself (e.g. crop handles,
 * hold-to-compare via [EditRenderer.setShowOriginal]) and drive [EditRenderer.setViewTransform].
 * Use [EditRenderer.imageBounds] to place overlays over the drawn image.
 */
@Composable
fun EditorCanvas(
    renderer: EditRenderer,
    modifier: Modifier = Modifier,
    zoomable: Boolean = true,
    maxZoom: Float = 8f,
) {
    var surface by remember { mutableStateOf<GLSurfaceView?>(null) }
    var zoom by remember(renderer) { mutableFloatStateOf(1f) }
    var pan by remember(renderer) { mutableStateOf(Offset.Zero) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, surface) {
        val view = surface
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> view?.onResume()
                Lifecycle.Event.ON_PAUSE -> view?.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val gestures = if (!zoomable) Modifier else Modifier
        .pointerInput(renderer) {
            detectTransformGestures { centroid, panChange, zoomChange, _ ->
                val newZoom = (zoom * zoomChange).coerceIn(1f, maxZoom)
                val c = centroid - Offset(size.width / 2f, size.height / 2f)
                val k = newZoom / zoom
                var p = (pan - c) * k + c + panChange
                val maxX = size.width * (newZoom - 1f) / 2f
                val maxY = size.height * (newZoom - 1f) / 2f
                p = Offset(p.x.coerceIn(-maxX, maxX), p.y.coerceIn(-maxY, maxY))
                zoom = newZoom
                pan = p
                renderer.setViewTransform(zoom, pan.x, pan.y)
            }
        }
        .pointerInput(renderer) {
            detectTapGestures(onDoubleTap = {
                zoom = 1f; pan = Offset.Zero
                renderer.setViewTransform(1f, 0f, 0f)
            })
        }

    Box(modifier.then(gestures)) {
        AndroidView(
            factory = { ctx ->
                GLSurfaceView(ctx).apply {
                    setEGLContextClientVersion(3)
                    preserveEGLContextOnPause = true
                    setRenderer(renderer)
                    renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                    renderer.attach(this)
                    surface = this
                }
            },
            modifier = Modifier.fillMaxSize(),
            onRelease = { view ->
                renderer.detach(view)
                view.onPause()
                if (surface === view) surface = null
            },
        )
    }
}
