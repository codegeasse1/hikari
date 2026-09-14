package com.hikari.app.net

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Runtime network tuning, driven by the Settings "Slow connection mode" toggle
 * (mobile data / weak Wi-Fi). When it is on, every source-search and
 * stream-probe timeout is scaled up and a provider that comes back empty is
 * retried once, so a slow link gets enough time to answer instead of the app
 * reporting "No playable sources found" purely because a response arrived late.
 *
 * Off by default, so a fast connection keeps its snappy timeouts. The flag is
 * persisted in [com.hikari.app.data.AppStore] and mirrored here at startup and
 * on every change, so the network layer can read it synchronously (the
 * timeouts it feeds are plain constants used all over the search/probe code).
 */
object NetTuning {

    /** How much longer every timeout gets while slow mode is on. */
    private const val SLOW_MULTIPLIER = 3L

    @Volatile
    var slowConnection: Boolean = false
        private set

    /** Callbacks invoked when the mode flips — e.g. StreamProbe rebuilding its
     *  OkHttp client, whose connect/read timeouts are fixed at build time. */
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun setSlowConnection(enabled: Boolean) {
        if (slowConnection == enabled) return
        slowConnection = enabled
        listeners.forEach { runCatching { it() } }
    }

    fun onChange(listener: () -> Unit) {
        listeners.add(listener)
    }

    /** [baseMs] scaled up while slow mode is on. */
    fun timeout(baseMs: Long): Long =
        if (slowConnection) baseMs * SLOW_MULTIPLIER else baseMs

    /** Attempts a timed-out/empty provider gets: 1 normally, 2 in slow mode. */
    fun attempts(): Int = if (slowConnection) 2 else 1
}
