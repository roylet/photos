package com.roylet.photos.ui.permissions

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** How much of the phone's photo library the app may read. */
enum class MediaAccess {
    /** Every photo and video. */
    Full,
    /** Only the photos the user picked (Android 14+ "Select photos and videos"). */
    Partial,
    /** None. */
    Denied,
}

/**
 * The media permission rules, as pure functions where possible so they can be unit tested.
 * Nothing is cached: ask [access] whenever it matters (the app re-checks on every resume), since
 * the user can change it in system settings at any time, and a partial grant changes whenever
 * they pick more photos.
 */
object MediaPermissions {
    /** What to ask for on [sdk]: the photo/video permissions (plus "user selected" on 14+) and the EXIF location. */
    fun requested(sdk: Int = Build.VERSION.SDK_INT): Array<String> = buildList {
        if (sdk >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.READ_MEDIA_IMAGES)
            add(Manifest.permission.READ_MEDIA_VIDEO)
            // Asking for it alongside the two above is what makes Android 14 offer "Select photos".
            if (sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        } else {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        add(Manifest.permission.ACCESS_MEDIA_LOCATION)
    }.toTypedArray()

    /** The permission whose rationale flag tells "denied once" from "don't ask again". */
    fun primary(sdk: Int = Build.VERSION.SDK_INT): String =
        if (sdk >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

    /** Maps granted permissions to an access level on [sdk]. */
    fun accessFrom(sdk: Int, granted: (String) -> Boolean): MediaAccess = when {
        sdk >= Build.VERSION_CODES.TIRAMISU -> when {
            granted(Manifest.permission.READ_MEDIA_IMAGES) || granted(Manifest.permission.READ_MEDIA_VIDEO) -> MediaAccess.Full
            sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> MediaAccess.Partial
            else -> MediaAccess.Denied
        }
        granted(Manifest.permission.READ_EXTERNAL_STORAGE) -> MediaAccess.Full
        else -> MediaAccess.Denied
    }

    /** The current access level. */
    fun access(context: Context): MediaAccess =
        accessFrom(Build.VERSION.SDK_INT) { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    /**
     * After a denial Android stops showing the prompt ("don't ask again", or the second denial on
     * 11+): only app settings can grant it then. That's when we've asked before and the system
     * no longer wants a rationale.
     */
    fun isPermanentlyDenied(access: MediaAccess, askedBefore: Boolean, shouldShowRationale: Boolean): Boolean =
        access == MediaAccess.Denied && askedBefore && !shouldShowRationale

    /** Android 12+: whether the user let the app skip MediaStore's favourite/trash confirmation dialogs. */
    fun canManageMedia(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && MediaStore.canManageMedia(context)

    /** Whether "Skip confirmation dialogs" can be offered at all (Android 12+). */
    val manageMediaSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /** The system page for MANAGE_MEDIA ("Media management apps"), or null before Android 12. */
    fun manageMediaIntent(context: Context): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, Uri.parse("package:${context.packageName}")) else null

    /** This app's page in system settings (for a permanent denial). */
    fun appSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/**
 * The media permission state for the UI: [access] etc. are re-read on every resume and after each
 * request, never remembered across those. Screenshots build one with fixed values; the app gets a
 * live one from [rememberMediaPermissions] via [LocalMediaPermissions].
 */
@Stable
class MediaPermissionsState(
    access: MediaAccess = MediaAccess.Full,
    permanentlyDenied: Boolean = false,
    canManageMedia: Boolean = false,
    /** Shows the system prompt (or, on 14+ with partial access, the photo picker to change the selection). */
    onRequest: (onResult: (MediaAccess) -> Unit) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onManageMedia: (() -> Unit)? = null,
) {
    var access by mutableStateOf(access)
        internal set
    var permanentlyDenied by mutableStateOf(permanentlyDenied)
        internal set
    var canManageMedia by mutableStateOf(canManageMedia)
        internal set

    internal var onRequest = onRequest
    internal var onOpenSettings = onOpenSettings
    internal var onManageMedia = onManageMedia

    /** Asks for access with the system UI; [onResult] gets the new level. */
    fun request(onResult: (MediaAccess) -> Unit = {}) = onRequest(onResult)
    /** Opens this app's system settings page. */
    fun openSettings() = onOpenSettings()
    /** Opens "Media management apps" (Android 12+); null when unsupported. */
    val manageMedia: (() -> Unit)? get() = onManageMedia
}

/** The live [MediaPermissionsState]; defaults to full access for previews. */
val LocalMediaPermissions = staticCompositionLocalOf { MediaPermissionsState() }

/**
 * A live [MediaPermissionsState] for the current activity: re-checked on every ON_RESUME (the user
 * may have changed it in Settings or picked more photos) and after each prompt.
 */
@Composable
fun rememberMediaPermissions(settings: com.roylet.photos.data.Settings): MediaPermissionsState {
    val context = LocalContext.current
    val state = remember { MediaPermissionsState(access = MediaPermissions.access(context)) }
    val refresh: () -> Unit = remember(context, state) {
        val activity = context.findActivity()
        val check: () -> Unit = {
            val access = MediaPermissions.access(context)
            state.access = access
            state.permanentlyDenied = MediaPermissions.isPermanentlyDenied(
                access, settings.askedMediaPermission,
                activity?.shouldShowRequestPermissionRationale(MediaPermissions.primary()) == true,
            )
            state.canManageMedia = MediaPermissions.canManageMedia(context)
        }
        check
    }
    var callback by remember { mutableStateOf<((MediaAccess) -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        settings.askedMediaPermission = true
        refresh()
        callback?.invoke(state.access)
        callback = null
    }
    remember(launcher, context) {
        state.onRequest = { onResult ->
            callback = onResult
            runCatching { launcher.launch(MediaPermissions.requested()) }.onFailure { callback = null; refresh(); onResult(state.access) }
        }
        state.onOpenSettings = { runCatching { context.startActivity(MediaPermissions.appSettingsIntent(context)) } }
        state.onManageMedia = MediaPermissions.manageMediaIntent(context)?.let { intent -> { runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } } }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, refresh) {
        refresh()
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return state
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
