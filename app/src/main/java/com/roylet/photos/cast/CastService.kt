package com.roylet.photos.cast

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.roylet.photos.MainActivity
import com.roylet.photos.PhotosApp

/**
 * Runs only while a cast session is connected (started and stopped by the cast controller). It
 * holds the Media3 [MediaSession] around the remote player, so the lock screen, the media
 * notification and the system Output Switcher show and control what's on the TV, and keeps the
 * process alive for the phone-side media server ([LocalMediaServer]) the TV reads phone-only
 * photos from.
 */
class CastService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val container = (application as PhotosApp).container
        val player = container.castDevice.player
        if (player == null) {
            stopSelf()
            return
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player).setId("cast").setSessionActivity(open).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away doesn't stop what's on the TV; the notification keeps controlling it.
        if (session == null) stopSelf()
    }

    override fun onDestroy() {
        // The player belongs to the process-wide cast device; only the session goes.
        session?.release()
        session = null
        super.onDestroy()
    }
}
