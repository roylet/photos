package com.roylet.photos.ui.components

import android.provider.Settings
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Forces reduced motion on (true) or off (false) for a subtree; null (the default) follows the
 * system "Remove animations" setting (animator duration scale 0).
 */
val LocalReducedMotion = compositionLocalOf<Boolean?> { null }

/** True when animations should be skipped or made instant. */
@Composable
fun reducedMotion(): Boolean {
    LocalReducedMotion.current?.let { return it }
    val context = LocalContext.current
    return remember(context) {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
}

/** [spec], or an instant snap when motion is reduced. */
@Composable
fun <T> motionSpec(spec: AnimationSpec<T>): AnimationSpec<T> = if (reducedMotion()) snap() else spec
