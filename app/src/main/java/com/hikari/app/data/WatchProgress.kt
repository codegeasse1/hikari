package com.hikari.app.data

object WatchProgress {
    /** Even a second of playback counts as started (the "1 second watched"
     *  marks it Watching" promise). */
    const val STARTED_MS = 1_000L

    /** The credits window: reaching this close to the end counts as watched. */
    const val CREDIT_SKIP_MS = 5 * 60_000L

    /** Cap on that window as a share of the video, so shorts behave. */
    const val CREDIT_SKIP_FRAC = 0.20

    /** How close to the end counts as watched for this length. */
    fun creditsAllowanceMs(durationMs: Long): Long {
        if (durationMs <= 0L) return 0L
        val frac = (durationMs * CREDIT_SKIP_FRAC).toLong()
        return minOf(CREDIT_SKIP_MS, frac).coerceAtLeast(10_000L)
    }

    /** True once playback has reached the credits window (see above). */
    fun isWatched(positionMs: Long, durationMs: Long): Boolean {
        if (durationMs <= 0L || positionMs < STARTED_MS) return false
        return positionMs >= durationMs - creditsAllowanceMs(durationMs)
    }

    /** True once playback has started but not reached the credits window. */
    fun isStarted(positionMs: Long, durationMs: Long): Boolean {
        if (positionMs < STARTED_MS) return false
        return !isWatched(positionMs, durationMs)
    }
}
