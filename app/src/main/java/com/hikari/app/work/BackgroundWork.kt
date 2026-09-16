package com.hikari.app.work

import android.os.Handler
import android.os.Looper
import com.hikari.app.HikariApp

/**
 * Refcounted registry of "Hikari must keep working" tasks.
 *
 * Why this exists: an Android process with no visible UI drops into the
 * *cached* state the moment the user presses Home, and the platform then
 * FREEZES it (the Android 11+ app freezer) and lets the CPU/radio sleep. Every
 * coroutine in the process is stopped mid-sentence and only continues when the
 * user comes back — which is exactly the reported behaviour: a catalog stops
 * loading, a title search stops finding results, and everything resumes the
 * moment the app is reopened.
 *
 * A foreground service moves the process into the *perceptible* state, which
 * is exempt from the freezer, and a partial wakelock keeps the CPU running
 * when the screen goes off. Registering a piece of work here (a search, a
 * catalog fetch, a provider scan, an extension install) therefore keeps Hikari
 * alive in the background for exactly as long as that work runs; when the last
 * token is ended the service shuts itself down — after a short grace window, so
 * a burst of tiny tasks doesn't thrash it.
 *
 * Tokens are reference counted, so overlapping searches/catalogs simply keep
 * one service running. A safety sweep force-expires a token that was never
 * ended (a bug, or a task that somehow lost its `finally`) so the notification
 * can never get stuck on screen forever.
 */
object BackgroundWork {

    /** A token that was never ended is dropped after this long. */
    private const val MAX_TOKEN_MS = 60L * 60 * 1000

    /** How often the safety sweep (and the wakelock renewal) runs. */
    private const val SWEEP_INTERVAL_MS = 60L * 1000

    /** How long the service lingers after the last token, so back-to-back work
     *  doesn't stop and immediately restart it. */
    private const val STOP_GRACE_MS = 6L * 1000

    /** Handle for one registered piece of work. */
    class Token internal constructor(internal val id: Long)

    private class Entry(val label: String, val startedAt: Long)

    private val lock = Any()
    private val active = LinkedHashMap<Long, Entry>()
    private var nextId = 1L
    private var stopScheduled = false
    private var sweeping = false

    private val handler = Handler(Looper.getMainLooper())
    private val stopRunnable = Runnable { stopIfIdle() }
    private val sweepRunnable = object : Runnable {
        override fun run() {
            sweep()
            handler.postDelayed(this, SWEEP_INTERVAL_MS)
        }
    }

    /**
     * Registers [label] as running work and makes sure the process keeps
     * running in the background. Always pair with [end] (a `finally`, or a
     * job's `invokeOnCompletion`) so the service can stop.
     */
    fun begin(label: String): Token {
        val id = synchronized(lock) {
            val i = nextId++
            active[i] = Entry(label.trim(), System.currentTimeMillis())
            i
        }
        cancelScheduledStop()
        ensureSweeper()
        WorkService.start()
        return Token(id)
    }

    /** Ends a token. Idempotent — ending the same token twice is harmless. */
    fun end(token: Token) {
        val remaining = synchronized(lock) {
            active.remove(token.id)
            active.size
        }
        if (remaining <= 0) scheduleStop() else WorkService.refresh()
    }

    fun isActive(): Boolean = synchronized(lock) { active.isNotEmpty() }

    /** One line describing everything running, e.g. `Searching "scam" (+2)`. */
    fun label(): String? = synchronized(lock) {
        val first = active.values.firstOrNull() ?: return null
        if (active.size == 1) first.label else "${first.label}  (+${active.size - 1})"
    }

    private fun scheduleStop() {
        val post = synchronized(lock) {
            if (stopScheduled) false else {
                stopScheduled = true
                true
            }
        }
        if (post) handler.postDelayed(stopRunnable, STOP_GRACE_MS)
    }

    private fun cancelScheduledStop() {
        synchronized(lock) { stopScheduled = false }
        handler.removeCallbacks(stopRunnable)
    }

    private fun stopIfIdle() {
        synchronized(lock) { stopScheduled = false }
        if (!isActive()) WorkService.stop()
    }

    private fun ensureSweeper() {
        val start = synchronized(lock) {
            if (sweeping) false else {
                sweeping = true
                true
            }
        }
        if (start) handler.postDelayed(sweepRunnable, SWEEP_INTERVAL_MS)
    }

    /** Drops stale tokens and keeps the service's wakelock fresh while work is
     *  genuinely running (the wakelock is taken with a timeout as a safety net,
     *  so a long search has to renew it). */
    private fun sweep() {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val expired = active.filterValues { now - it.startedAt > MAX_TOKEN_MS }.keys
            expired.forEach { active.remove(it) }
        }
        if (isActive()) WorkService.renewWakeLock() else WorkService.stop()
    }

    /** Convenience: the current Application, or null very early in startup. */
    internal fun context(): HikariApp? = runCatching { HikariApp.instance }.getOrNull()
}
