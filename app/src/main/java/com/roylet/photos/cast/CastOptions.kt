package com.roylet.photos.cast

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions

/**
 * Cast options, named in the manifest (`OPTIONS_PROVIDER_CLASS_NAME`; kept by R8 through the
 * Cast SDK's consumer rules and our own in proguard-rules.pro).
 *
 * Google's Default Media Receiver (`CC1AD845`), like Immich: no custom receiver to host or
 * register. The Cast SDK's own media session and notification are off: [CastService]'s Media3
 * session drives the lock screen, notification and Output Switcher instead (two sessions would
 * show two notifications).
 */
class PhotosCastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(RECEIVER_APP_ID)
            .setCastMediaOptions(
                CastMediaOptions.Builder()
                    .setMediaSessionEnabled(false)
                    .setNotificationOptions(null)
                    .build(),
            )
            // Output Switcher: the system's media panel can move the session back to the phone.
            .setRemoteToLocalEnabled(true)
            // Ending the session (STOP CASTING) closes the receiver on the TV too.
            .setStopReceiverApplicationWhenEndingSession(true)
            .setResumeSavedSession(true)
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null

    companion object {
        /** Google's Default Media Receiver: photos, progressive video, no registration. */
        val RECEIVER_APP_ID: String = CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
    }
}
