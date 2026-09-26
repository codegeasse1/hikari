package com.hikari.app.data

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide cache of extracted server lists, keyed by provider|id|episode.
 *
 * WHY this is not just a field on the detail screen's ViewModel: that screen is
 * recreated every time the user leaves it — backing out of the player, opening
 * the same title again from History or the Home feed — and a per-ViewModel cache
 * died with it. Every return therefore re-ran the whole multi-provider
 * extraction (the "tap Play and it says Finding the best server… for a minute"
 * report), while the *next* tap hit the freshly filled cache and started
 * instantly: exactly the "first time slow, second time instant" pattern users
 * described. Living here, the list survives the screen (and the activity).
 *
 * Entries are timestamped because providers hand out SIGNED, time-limited links
 * (4KHDHub/hubcloud's `<token>::<sig>` workers.dev URLs rotate), so a stale list
 * is only ever a hint — the detail screen serves it to the UI and re-extracts.
 *
 * The in-flight map lives here too, so a re-created screen JOINS an extraction
 * that is still running instead of launching a second one (two concurrent
 * loadLinks runs on the same plugin can corrupt its state).
 */
object StreamCache {

    data class Entry(val at: Long, val list: List<StreamSource>)

    /** Upper bound on remembered titles. A long session browsed through dozens
     *  of pages should not hold every server list it ever saw in memory. */
    private const val MAX_ENTRIES = 40

    private val entries = ConcurrentHashMap<String, Entry>()

    /** The extraction running for a key, if any. It completes with a
     *  [ContentRepository.StreamLookup] rather than a bare list so a joiner can
     *  tell a finished empty answer apart from a pass that was cut short (see
     *  [ContentRepository.StreamLookup]) — the latter must be retried, not
     *  believed. */
    private val inflight = ConcurrentHashMap<String, CompletableDeferred<ContentRepository.StreamLookup>>()

    fun get(key: String): Entry? = entries[key]

    fun put(key: String, list: List<StreamSource>) {
        // An EMPTY result is never cached. It says nothing about the next lookup
        // (a provider that timed out is simply asked again — see
        // ContentRepository.searchBestMatch), and serving that emptiness back as
        // a hit for the whole TTL is what made the second and third Play taps of
        // the same title answer "no playable source" without a single provider
        // being asked. A dead list must never be remembered as an answer.
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
    fun joined(key: String): CompletableDeferred<ContentRepository.StreamLookup>? = inflight[key]

    /** Claims the extraction slot for [key]; false when someone else holds it. */
    fun claim(key: String, deferred: CompletableDeferred<ContentRepository.StreamLookup>): Boolean =
        inflight.putIfAbsent(key, deferred) == null

    fun release(key: String) {
        inflight.remove(key)
    }
}
