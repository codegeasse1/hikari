package com.hikari.app.core

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * Bounds and de-serialises the heavy "load a plugin archive" work.
 *
 * Hikari ships two plugin runtimes — CloudStream `.cs3` dex archives
 * ([com.hikari.app.cs3.Cs3PluginManager]) and Hikari `.hiki` archives
 * ([com.hikari.app.hiki.HikariPluginManager]) — and each used to guard itself
 * with ONE global lock (a `ReentrantLock`, and `@Synchronized` respectively).
 * On a device with a few hundred installed extensions that meant every archive
 * loaded strictly one at a time: a cold Home, a cross-extension search and an
 * install all queued behind whichever load happened to be running, and a single
 * plugin that does network work in `load()` could hold the queue for its whole
 * 45-second budget. That is what left Home showing "none found / still
 * searching" with 240 extensions installed.
 *
 * This object replaces both guards with three properties:
 *
 *  - [lockFor] hands out a lock PER PLUGIN PATH, so different plugins load
 *    concurrently while the same plugin is never loaded twice at once (and a
 *    re-entrant call from inside a plugin's own `load()` is the only case that
 *    can see its own path busy);
 *  - [withSlot] caps how many archives the process dex-loads at the same time.
 *    Each `PathClassLoader` commit costs memory the GC cannot reclaim until the
 *    load settles, so "one thread per extension, all 240 at once" OOMs on weak
 *    devices;
 *  - both are RE-ENTRANT PER THREAD: a plugin whose `load()` ends up asking for
 *    another plugin must not deadlock against a slot it already holds.
 */
object LoadGate {

    /** How many plugin archives may be mid-load across the whole process. */
    private const val MAX_CONCURRENT_LOADS = 6

    /** How long a caller waits for another thread to finish the same plugin. */
    private const val LOCK_WAIT_MS = 120_000L

    private val slots = Semaphore(MAX_CONCURRENT_LOADS)

    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    /** How many slots this thread currently holds (nesting depth). */
    private val depth = ThreadLocal.withInitial { 0 }

    /** The lock that serialises loading of ONE plugin path. */
    fun lockFor(path: String): ReentrantLock = locks.computeIfAbsent(path) { ReentrantLock() }

    /**
     * Waits for the per-path lock. Returns false instead of blocking forever:
     * two plugins whose `load()`s ask for each other from two different threads
     * would otherwise cycle, and the caller can just retry later.
     */
    fun acquire(lock: ReentrantLock): Boolean = try {
        lock.tryLock(LOCK_WAIT_MS, TimeUnit.MILLISECONDS)
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    /** Runs [block] while holding one of the process-wide load slots. */
    fun <T> withSlot(block: () -> T): T {
        val held = depth.get() ?: 0
        if (held > 0) {
            // Already inside a slot on this thread — take a nested one for
            // free, otherwise six plugins that each load another one deadlock.
            depth.set(held + 1)
            try {
                return block()
            } finally {
                depth.set(held)
            }
        }
        slots.acquireUninterruptibly()
        depth.set(1)
        try {
            return block()
        } finally {
            depth.set(0)
            slots.release()
        }
    }
}
