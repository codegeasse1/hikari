package com.hikari.app.core

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

object LoadGate {

    /** How many plugin archives may be mid-load across the whole process. */
    private const val MAX_CONCURRENT_LOADS = 6

    /** How long a caller waits for another thread to finish the same plugin. */
    private const val LOCK_WAIT_MS = 120_000L

    private const val SLOT_WAIT_MS = 45_000L

    /** Thrown when no load slot could be acquired within [SLOT_WAIT_MS]. */
    class LoadQueueBusyException : IllegalStateException(
        "The plugin loader is busy (no free load slot after ${SLOT_WAIT_MS / 1000}s)"
    )

    private val slots = Semaphore(MAX_CONCURRENT_LOADS)

    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    /** How many slots this thread currently holds (nesting depth). */
    private val depth = ThreadLocal.withInitial { 0 }

    /** The lock that serialises loading of ONE plugin path. */
    fun lockFor(path: String): ReentrantLock = locks.computeIfAbsent(path) { ReentrantLock() }

    fun acquire(lock: ReentrantLock): Boolean = try {
        lock.tryLock(LOCK_WAIT_MS, TimeUnit.MILLISECONDS)
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    /** Runs [block] while holding one of the process-wide load slots. Throws
     *  [LoadQueueBusyException] rather than blocking forever (see
     *  [SLOT_WAIT_MS]) so one jammed load can never freeze every later search. */
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
        val got = try {
            slots.tryAcquire(SLOT_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!got) throw LoadQueueBusyException()
        depth.set(1)
        try {
            return block()
        } finally {
            depth.set(0)
            slots.release()
        }
    }
}
