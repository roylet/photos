package com.roylet.photos.util

/**
 * Whether the user is busy scrolling a timeline right now, so background work that competes for
 * the disk (SHA-1 hashing) can wait: the grid calls [scrolling] as scrolls start and stop, and
 * [busy] stays true for [QUIET_MS] after the last one ends.
 */
object UserActivity {
    const val QUIET_MS = 1_500L

    @Volatile private var active = 0
    @Volatile private var lastEndedAt = 0L

    fun scrolling(inProgress: Boolean, now: Long = System.currentTimeMillis()) {
        synchronized(this) {
            if (inProgress) active++ else { active = (active - 1).coerceAtLeast(0); lastEndedAt = now }
        }
    }

    fun busy(now: Long = System.currentTimeMillis()): Boolean = active > 0 || now - lastEndedAt < QUIET_MS
}
