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

    private class Entry(
        val label: String,
        val startedAt: Long,
        /** Cancels the work behind this token. Optional: work that is a flow
         *  being collected by somebody else's coroutine cannot be cancelled from
         *  here, and once the service stops the platform freezes the process
         *  anyway (which is all the user's "I closed the app" needs). */
        val onCancel: (() -> Unit)?,
    )

    private val lock = Any()
    private val active = LinkedHashMap<Long, Entry>()
    private var nextId = 1L
    private var stopScheduled = false
    private var sweeping = false

    /**
     * True between [cancelAll] and the next Activity starting ([reopen]).
     *
     * While the app is closed, work that is still unwinding must not be able to
     * bring the service back up — an app-scope search that has not noticed yet
     * would otherwise register a fresh token a second after the user closed the
     * app, restart the foreground service and put that "Hikari keeps running
     * while you use other apps" notification straight back on screen.
     */
    @Volatile
    private var closed = false

    private val handler = Handler(Looper.getMainLooper())
    private val stopRunnable = Runnable { stopIfIdle() }

    private val sweepRunnable = object : Runnable {
        override fun run() {
            // The sweeper keeps its own life as long as there is work to sweep —
            // and retires when there is none, so an idle app is not woken every
            // minute by a handler that stopped mattering.
            if (sweep()) handler.postDelayed(this, SWEEP_INTERVAL_MS)
            else synchronized(lock) { sweeping = false }
        }
    }

    /**
     * Registers [label] as running work and makes sure the process keeps
     * running in the background. Always pair with [end] (a `finally`, or a
     * job's `invokeOnCompletion`) so the service can stop.
     */
    fun begin(label: String, onCancel: (() -> Unit)? = null): Token {
        val id = synchronized(lock) {
            val i = nextId++
            active[i] = Entry(label.trim(), System.currentTimeMillis(), onCancel)
            i
        }
        // The app is closed (see [closed]): the work is still registered — so it
        // reports and ends normally — but it may not revive the service.
        if (closed) return Token(id)
        cancelScheduledStop()
        ensureSweeper()
        WorkService.start()
        return Token(id)
    }

    /**
     * An Activity is up again, so background work may hold the process once more.
     * Called from the application's activity lifecycle (see HikariApp), which is
     * also what makes [cancelAll] a per-session thing rather than a permanent
     * switch.
     */
    fun reopen() {
        closed = false
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

    /**
     * Drops EVERYTHING and cancels the work behind it — "the user closed the
     * app, nothing of ours should still be running".
     *
     * This is the counterpart to [begin]. The registry exists so work survives
     * the user leaving the app (Home, another app); it must NOT survive the user
     * closing it, which is what this is for. Without it, a long multi-extension
     * search kept the process in the foreground-service state after the app was
     * closed: a permanent "Hikari keeps running while you use other apps"
     * notification, hundreds of live network calls, and — because the process
     * stayed warm and busy — the next launch crawling through its splash screen
     * (reported as "it just stays stuck on the Hikari logo and won't open").
     *
     * Called when the last Activity is destroyed because it is FINISHING (Back
     * out of the app), and from [WorkService.onTaskRemoved] (swiped off the
     * recents list). Deliberately not from onStop: pressing Home also stops every
     * activity, and continuing there is the feature.
     */
    fun cancelAll(reason: String) {
        closed = true
        val dropped = synchronized(lock) {
            val all = active.values.toList()
            active.clear()
            stopScheduled = false
            all
        }
        handler.removeCallbacks(stopRunnable)
        if (dropped.isEmpty()) {
            // Nothing registered, but the service may still be up (a token that
            // ended milliseconds ago): stopping is idempotent and cheap.
            WorkService.stop()
            return
        }
        com.hikari.app.data.Logs.log(
            "Work",
            "cancelling ${dropped.size} background task(s) (" +
                dropped.joinToString(", ") { "\"" + it.label + "\"" } + ") — " + reason,
        )
        dropped.forEach { entry -> runCatching { entry.onCancel?.invoke() } }
        WorkService.stop()
    }

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
     *  so a long search has to renew it). Returns true when there is still work
     *  registered — i.e. whether the sweeper should keep running. */
    private fun sweep(): Boolean {
        val now = System.currentTimeMillis()
        val expired = synchronized(lock) {
            val stale = active.filterValues { now - it.startedAt > MAX_TOKEN_MS }
            stale.forEach { (id, entry) ->
                active.remove(id)
                // A token that was never ended is a bug somewhere, but the work
                // behind it is real and must be stopped, not just forgotten.
                runCatching { entry.onCancel?.invoke() }
            }
            stale.size
        }
        if (expired > 0) {
            com.hikari.app.data.Logs.log(
                "Work",
                "dropped $expired stale background task(s) that never reported finishing",
            )
        }
        val left = isActive()
        if (left) WorkService.renewWakeLock() else WorkService.stop()
        return left
    }

    /** Convenience: the current Application, or null very early in startup. */
    internal fun context(): HikariApp? = runCatching { HikariApp.instance }.getOrNull()
}
