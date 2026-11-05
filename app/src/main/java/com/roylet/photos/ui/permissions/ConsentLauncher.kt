package com.roylet.photos.ui.permissions

import android.app.Activity
import android.app.PendingIntent
import android.content.IntentSender
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.CoroutineContext

/**
 * The app's single launcher for system consent dialogs that come as a PendingIntent: MediaStore's
 * `createFavoriteRequest`, `createTrashRequest`, `createDeleteRequest`, `createWriteRequest`, and
 * a RecoverableSecurityException's user action on Android 10.
 *
 * ```
 * val ok = container.consent.requestConsent(MediaStore.createTrashRequest(resolver, uris, true))
 * if (!ok) return  // the user said no; leave the timeline as it was
 * ```
 *
 * Requests are serialised (one dialog at a time). [requestConsent] suspends until the user
 * answers; it returns false right away when no screen is attached within [attachTimeoutMs]
 * (e.g. from a background worker: ask before enqueuing the work instead). The result survives an
 * activity recreation because [ConsentHost] registers through `rememberLauncherForActivityResult`.
 *
 * With MANAGE_MEDIA granted (Android 12+, "Skip confirmation dialogs" in Settings) MediaStore
 * applies favourite/trash requests without a dialog, so check [MediaPermissions.canManageMedia]
 * and call ContentResolver directly where that's allowed.
 */
class ConsentLauncher(
    private val attachTimeoutMs: Long = 5_000,
    private val main: CoroutineContext = Dispatchers.Main.immediate,
) {
    private val launcher = MutableStateFlow<((IntentSenderRequest) -> Unit)?>(null)
    private val mutex = Mutex()
    @Volatile private var pending: CompletableDeferred<Boolean>? = null

    /** Whether a screen is attached to show dialogs. */
    val isAttached: Boolean get() = launcher.value != null

    suspend fun requestConsent(pendingIntent: PendingIntent): Boolean = requestConsent(pendingIntent.intentSender)

    suspend fun requestConsent(intentSender: IntentSender): Boolean = mutex.withLock {
        val launch = withTimeoutOrNull(attachTimeoutMs) { launcher.filterNotNull().first() } ?: return@withLock false
        val result = CompletableDeferred<Boolean>()
        pending = result
        try {
            val launched = withContext(main) {
                runCatching { launch(IntentSenderRequest.Builder(intentSender).build()) }
                    .onFailure { Log.w(TAG, "Couldn't show the consent dialog", it) }
                    .isSuccess
            }
            if (launched) result.await() else false
        } finally {
            pending = null
        }
    }

    /** Called by [ConsentHost]; tests attach a fake. */
    fun attach(launch: (IntentSenderRequest) -> Unit) { launcher.value = launch }

    fun detach(launch: (IntentSenderRequest) -> Unit) { launcher.compareAndSet(launch, null) }

    /** The dialog's result ([Activity.RESULT_OK] means the user allowed it). */
    fun onResult(resultCode: Int) { pending?.complete(resultCode == Activity.RESULT_OK) }

    private companion object { const val TAG = "ConsentLauncher" }
}

/** The app's [ConsentLauncher], for screens that start a MediaStore request themselves. */
val LocalConsentLauncher = staticCompositionLocalOf<ConsentLauncher?> { null }

/** Attaches [consent] to the current activity while this is in the composition (AppNav does it once). */
@Composable
fun ConsentHost(consent: ConsentLauncher) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { consent.onResult(it.resultCode) }
    DisposableEffect(launcher, consent) {
        val launch: (IntentSenderRequest) -> Unit = { launcher.launch(it) }
        consent.attach(launch)
        onDispose { consent.detach(launch) }
    }
}
