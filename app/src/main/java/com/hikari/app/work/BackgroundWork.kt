package com.hikari.app.work

import android.os.Handler
import android.os.Looper
import com.hikari.app.HikariApp

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

    @Volatile
    private var closed = false

    private val handler = Handler(Looper.getMainLooper())
    private val stopRunnable = Runnable { stopIfIdle() }

    private val sweepRunnable = object : Runnable {
        override fun run() {
            if (sweep()) handler.postDelayed(this, SWEEP_INTERVAL_MS)
            else synchronized(lock) { sweeping = false }
        }
    }

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
