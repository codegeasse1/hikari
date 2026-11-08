package com.hikari.app.data

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

object StreamCache {

    data class Entry(val at: Long, val list: List<StreamSource>)

    /** Upper bound on remembered titles. A long session browsed through dozens
     *  of pages should not hold every server list it ever saw in memory. */
    private const val MAX_ENTRIES = 40

    private val entries = ConcurrentHashMap<String, Entry>()

    private data class Pending(
        val deferred: CompletableDeferred<ContentRepository.StreamLookup>,
        val startedAt: Long,
    )

    /** A provider extraction that has made no hand-off for this long is no longer
     * allowed to hold every later Play tap hostage. The old coroutine is left
     * alive and may still publish a result; its deferred is simply detached from
     * the join slot so a fresh lookup can take over. */
    private const val INFLIGHT_STALE_MS = 60_000L

    private val inflight = ConcurrentHashMap<String, Pending>()

    fun get(key: String): Entry? = entries[key]

    fun put(key: String, list: List<StreamSource>) {
        if (list.isEmpty()) return
        entries[key] = Entry(System.currentTimeMillis(), list)
        if (entries.size > MAX_ENTRIES) {
            entries.entries
                .sortedBy { it.value.at }
                .take(entries.size - MAX_ENTRIES)
                .forEach { entries.remove(it.key) }
        }
    }

    /** Drops one entry (the player proved every server in it is dead). */
    fun invalidate(key: String) {
        entries.remove(key)
    }

    /** The extraction already running for [key], or null when there is none. */
    fun joined(key: String): CompletableDeferred<ContentRepository.StreamLookup>? {
        val pending = inflight[key] ?: return null
        if (pending.deferred.isCompleted) {
            inflight.remove(key, pending)
            return null
        }
        if (System.currentTimeMillis() - pending.startedAt >= INFLIGHT_STALE_MS) {
            if (inflight.remove(key, pending)) {
                Logs.log(
                    "Search",
                    "discarding stale shared extraction for $key after " +
                        (INFLIGHT_STALE_MS / 1000) +
                        "s so a new Play can retry",
                )
            }
            return null
        }
        return pending.deferred
    }

    /** Claims the extraction slot for [key]; false when someone else holds it. */
    fun claim(key: String, deferred: CompletableDeferred<ContentRepository.StreamLookup>): Boolean =
        inflight.putIfAbsent(key, Pending(deferred, System.currentTimeMillis())) == null

    /** Release only the owner that still occupies the slot. An older extraction
     * may finish after a stale takeover and must never remove the new owner's slot. */
    fun release(
        key: String,
        owner: CompletableDeferred<ContentRepository.StreamLookup>? = null,
    ) {
        val pending = inflight[key] ?: return
        if (owner == null || pending.deferred === owner) {
            inflight.remove(key, pending)
        }
    }
}
