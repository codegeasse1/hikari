package com.hikari.app.providers

import com.hikari.app.HikariApp
import com.hikari.app.data.Logs
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.ProviderType
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Implemented by provider adapters that keep a cached extension instance, so
 * a wedged runtime can be dropped and lazily rebuilt on the next call.
 */
interface StallResettable {
    fun resetForStall()
}

/**
 * Universal stall recovery: what happens when an extension stops answering.
 *
 * The shape it cures (proven by log: origin asked every pass, "0 of 1
 * answered", sweep "✗ the call never came back", restart plays instantly):
 * one call into an extension never returns — an internal deadlock, an
 * unbounded read, a wedged runtime — and every later call queues behind the
 * corpse (the [ProviderGate] lock it holds, the cached instance it owns), so
 * every tap burns its full budget and answers "No playable sources" until the
 * process dies and takes the corpse with it.
 *
 * Recovery runs per provider, engine-agnostic:
 *  1. [ProviderGate.breaker] replaces the provider's lock, so new calls stop
 *     queueing behind the stuck one (works for EVERY engine, whatever hung).
 *  2. The engine's cached extension instance is evicted, so the next call
 *     meets a freshly loaded runtime with fresh locks instead of the wedged
 *     object (HIKARI / CS3 / ANIYOMI / MANGA; stateless bridges need none).
 *  3. The provider's "stopped responding" skip is cleared, so the very next
 *     lookup asks it again instead of skipping it for two minutes.
 *
 * It fires only after CONSECUTIVE timeouts ([STALLS_TO_RECOVER]) — one slow
 * answer is just slowness — and at most once per [RECOVER_COOLDOWN_MS] per
 * provider, so a truly dead extension costs one reload per half minute, not
 * one per tap. Callers grant one fresh full-budget attempt right after it
 * (see ContentRepository.fetchStreams), which is what turns the user's
 * CURRENT tap from "No playable sources" into playback.
 */
object ExtensionRecovery {

    /** Consecutive timed-out calls before a provider is declared wedged. */
    private const val STALLS_TO_RECOVER = 2

    /** Minimum gap between two recoveries of the same provider. */
    private const val RECOVER_COOLDOWN_MS = 30_000L

    private val consecutiveStalls = ConcurrentHashMap<String, AtomicInteger>()
    private val lastRecoverAt = ConcurrentHashMap<String, Long>()

    /** Records a timed-out (or gate-stalled) call; returns the consecutive count. */
    fun noteTimeout(providerId: String): Int =
        consecutiveStalls.computeIfAbsent(providerId) { AtomicInteger(0) }.incrementAndGet()

    /** Records any real answer (servers OR an honest empty): the provider lives. */
    fun noteSuccess(providerId: String) {
        consecutiveStalls.remove(providerId)
        com.hikari.app.data.ContentRepository.clearHung(providerId)
    }

    /** True when the provider may be recovered right now (count + cooldown). */
    fun shouldRecover(providerId: String): Boolean {
        if ((consecutiveStalls[providerId]?.get() ?: 0) < STALLS_TO_RECOVER) return false
        val last = lastRecoverAt[providerId] ?: 0L
        return System.currentTimeMillis() - last >= RECOVER_COOLDOWN_MS
    }

    /**
     * Breaks the wedge for [config]: fresh lock, fresh extension instance,
     * skip-list cleared. Never throws — recovery failing must not fail the
     * lookup it runs inside of.
     */
    suspend fun recover(app: HikariApp, config: ProviderConfig) {
        lastRecoverAt[config.id] = System.currentTimeMillis()
        runCatching {
            Logs.log(
                "Provider",
                (config.name.ifBlank { config.id }) +
                    " [${config.type.groupLabel}]: wedged ${consecutiveStalls[config.id]?.get() ?: 0}x " +
                    "in a row — breaking the stall (fresh lock + fresh runtime)",
            )
            // 1. New calls stop queueing behind the stuck one (all engines).
            ProviderGate.breaker(config.id)
            // 2. The wedged object itself goes (engines with instance caches).
            when (config.type) {
                ProviderType.HIKARI -> {
                    (app.providers.byId(config.id) as? StallResettable)?.resetForStall()
                    runCatching {
                        val f = java.io.File(config.url)
                        if (f.isFile) com.hikari.app.hiki.HikariPluginManager.evict(config.url)
                    }
                }
                ProviderType.CS3 -> {
                    runCatching {
                        val f = java.io.File(config.url)
                        if (f.isFile) {
                            com.hikari.app.cs3.Cs3PluginManager.reload(app, f)
                        }
                    }
                }
                ProviderType.ANIYOMI -> {
                    runCatching {
                        com.hikari.app.aniyomi.AniyomiExtensionManager.evict(config.url)
                    }
                }
                ProviderType.MANGA -> {
                    runCatching {
                        com.hikari.app.manga.MangaExtensionManager.evict(config.url)
                    }
                }
                else -> Unit
            }
            // 3. Ask it again right away — the skip-list would otherwise keep
            // it out of the search for two minutes after we just fixed it.
            com.hikari.app.data.ContentRepository.clearHung(config.id)
        }
    }
}
