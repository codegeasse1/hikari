package com.hikari.app.providers

import android.content.Context
import com.hikari.app.cs3.Cs3MainApiProvider
import com.hikari.app.data.AppStore
import com.hikari.app.data.ExtensionNsfw
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.ProviderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ProviderManager(private val store: AppStore, private val context: Context) {

    private val _providers = MutableStateFlow<List<ContentProvider>>(emptyList())
    val providers: StateFlow<List<ContentProvider>> = _providers.asStateFlow()

    private val refreshLock = Mutex()

    /** True when a refresh arrived while one was already building (see [refresh]). */
    @Volatile
    private var refreshQueued = false

    /** The configs the current list was built from, so an identical rebuild is
     *  not a rebuild at all (see [refresh]). */
    private var lastConfigs: List<ProviderConfig> = emptyList()

    /** Guards [_providers] appends from the demand path (see [byId]). */
    private val demandLock = Any()

    /** The background warmer (see [refresh]): cancelled + replaced whenever a
     *  new refresh arrives, so a post-install list never warms twice. */
    @Volatile
    private var warmer: kotlinx.coroutines.Job? = null

    /** How many provider configs the last [refresh] resolved (dedupe + NSFW
     *  filter applied). The instantiated [providers] list fills in behind it. */
    fun configCount(): Int = lastConfigs.size

    suspend fun refresh() = withContext(Dispatchers.IO) {
        if (!refreshLock.tryLock()) {
            refreshQueued = true
            return@withContext
        }
        try {
            do {
                refreshQueued = false
                val stored = store.providers()
                // DUPLICATES ARE PRUNED HERE, at the source, and the prune is
                // written back.
                //
                // A provider's id is its identity, and several screens key a
                // lazy list by it (`key = { it.config.id }`) — so two rows
                // carrying one id is not a cosmetic problem but a hard crash:
                // Compose refuses to lay out a list with a repeated key
                // (`IllegalArgumentException: Key "stremio|1617974660" was
                // already used`), which is the crash this repairs. addProvider()
                // filters by id, so this build cannot normally create one — but
                // an install that raced a repo sync on an older build could, and
                // rows are also merged by id when an engine fixes a stale URL,
                // so a duplicate can come from data this build never wrote.
                // Pruning here (rather than only in the two screens that draw
                // them) means every reader of [providers] is safe, and writing it
                // back means the repair survives the next launch.
                var unique = stored.distinctBy { it.id }
                // Corrupt Anymex installs (blank/symbol-only keys like "____")
                // can never load and pollute Home with empty rows — drop them
                // here (the user can reinstall the real extension).
                val junkAnymex = unique.filter {
                    (it.type == ProviderType.ANYMEX || it.type == ProviderType.ANYMEX_MANGA) &&
                        it.id.substringAfter("|").trim { c -> c == '_' || c == '.' || c == '-' }.length < 8
                }
                if (junkAnymex.isNotEmpty()) {
                    com.hikari.app.data.Logs.log(
                        "Providers",
                        "pruned corrupt Anymex row(s): " +
                            junkAnymex.joinToString(",") { it.id },
                    )
                    runCatching { store.saveProviders(unique - junkAnymex.toSet()) }
                    unique = unique - junkAnymex.toSet()
                }
                if (unique.size != stored.size) {
                    com.hikari.app.data.Logs.log(
                        "Providers",
                        "pruned ${stored.size - unique.size} duplicate row(s) from the installed list",
                    )
                    runCatching { store.saveProviders(unique) }
                }
                val configs = runCatching {
                    ExtensionNsfw.filter(context, unique)
                }.onFailure { error ->
                    com.hikari.app.data.Logs.log(
                        "Providers",
                        "NSFW provider filter failed; keeping installed providers: " +
                            (error.message ?: error.javaClass.simpleName),
                    )
                }.getOrElse { unique }
                if (configs == lastConfigs && _providers.value.isNotEmpty()) continue
                lastConfigs = configs
                // Clone-site host map for images + Http (see CloneHosts): kept
                // fresh on every resolve so saves apply without a restart.
                runCatching { com.hikari.app.data.CloneHosts.refreshGlobal(configs) }
                // LAZY: the config phase above is the whole cheap part (read +
                // dedupe + NSFW filter). Instantiating all 785 providers here
                // is what held startup for ~17s, so it no longer happens in
                // this call: providers that vanished are dropped, survivors
                // stay live, and the rest instantiate in the background (see
                // [launchWarmer]) or on first touch (see [byId]). Startup
                // returns as soon as the configs resolve; Home/search rows
                // pop in as the warmer lands them.
                val live = _providers.value
                if (live.isNotEmpty()) {
                    // A toggled provider keeps its OLD live instance otherwise:
                    // ContentProvider.config is immutable, so flipping
                    // enabled in the store changed nothing the pipelines
                    // filter on (`it.config.enabled`) and the `providers`
                    // flow never re-emitted — the switch snapped back and
                    // the addon stayed consulted until an app restart. Swap
                    // every instance whose config changed (usually one —
                    // the toggled row) so the toggle takes effect at once.
                    // The warmer below still owns brand-new ids.
                    val byId = configs.associateBy { it.id }
                    val rebuilt = ArrayList<ContentProvider>(live.size)
                    var changed = false
                    for (p in live) {
                        val nc = byId[p.config.id]
                        if (nc == null) {
                            changed = true
                            continue
                        }
                        if (nc != p.config) {
                            val re = runCatching { instantiate(nc) }.getOrNull()
                            if (re != null) {
                                rebuilt += re
                                changed = true
                            } else {
                                rebuilt += p
                            }
                        } else {
                            rebuilt += p
                        }
                    }
                    if (changed) _providers.value = rebuilt
                }
                launchWarmer(configs)
            } while (refreshQueued)
        } finally {
            refreshLock.unlock()
        }
    }

    /**
     * Background instantiation of every config [refresh] resolved but nothing
     * has touched yet, in stored order. A bad constructor still never takes
     * down the process (same guard the old eager build had) — it is logged
     * and skipped, and a later [byId] demand retries it. Cancelled + replaced
     * on every refresh so only one warmer ever runs.
     */
    private fun launchWarmer(configs: List<ProviderConfig>) {
        warmer?.cancel()
        warmer = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO).launch {
            var done = 0
            for (config in configs) {
                if (!isActive) return@launch
                val known = synchronized(demandLock) {
                    _providers.value.any { it.config.id == config.id }
                }
                if (!known) {
                    // An extension is third-party code. A bad constructor or a
                    // freshly-installed module must never take down the app:
                    // keep the rest of the installed providers alive and
                    // record the offending provider for diagnostics.
                    val loaded = runCatching { instantiate(config) }
                        .onFailure { error ->
                            com.hikari.app.data.Logs.log(
                                "Providers",
                                "failed to load ${config.id}: ${error.message ?: error.javaClass.simpleName}",
                            )
                        }
                        .getOrNull()
                    if (loaded != null) {
                        synchronized(demandLock) {
                            if (_providers.value.none { it.config.id == config.id }) {
                                _providers.value = _providers.value + loaded
                            }
                        }
                    }
                }
                if (++done % 32 == 0) kotlinx.coroutines.yield()
            }
            com.hikari.app.data.Logs.log(
                "Providers",
                "warmer finished: ${_providers.value.size} of ${configs.size} installed",
            )
        }
    }

    suspend fun learnAdultFlags(limit: Int = 40): Boolean = withContext(Dispatchers.IO) {
        if (com.hikari.app.data.NsfwGate.enabled) return@withContext false
        var learnedAdult = false
        var asked = 0
        for (c in store.providers()) {
            if (asked >= limit) break
            if (c.nsfw != null) continue
            if (ExtensionNsfw.decidableFromMetadata(c)) continue
            if (c.url.isBlank() || !java.io.File(c.url).isFile) continue
            val p = instantiate(c) ?: continue
            asked++
            val adult = runCatching { p.adultExtension() }.getOrNull() ?: continue
            ExtensionNsfw.remember(c.url, adult)
            runCatching {
                store.updateProviders { list ->
                    list.map { if (it.id == c.id) it.copy(nsfw = adult) else it }
                }
            }
            if (adult) learnedAdult = true
        }
        learnedAdult
    }

    fun instantiate(c: ProviderConfig): ContentProvider? = when (c.type) {
        ProviderType.STREMIO -> StremioAddon(c)
        ProviderType.UNIVERSAL -> UniversalScraper(c)
        ProviderType.CS3 -> Cs3MainApiProvider(c)
        ProviderType.HIKARI -> HikariProviderAdapter(c)
        ProviderType.NUVIO -> com.hikari.app.nuvio.NuvioScraper(c)
        ProviderType.SKYSTREAM -> com.hikari.app.skystream.SkyStreamProvider(c)
        ProviderType.ANIYOMI -> com.hikari.app.aniyomi.AniyomiProvider(c)
        ProviderType.MANGA -> if (com.hikari.app.lnreader.LnReaderProvider.isLnReaderId(c.id)) com.hikari.app.lnreader.LnReaderProvider(c) else if (com.hikari.app.cs3.Cs3MangaProvider.isCs3MangaId(c.id)) com.hikari.app.cs3.Cs3MangaProvider(c) else if (com.hikari.app.sora.SoraMangaProvider.isSoraMangaProviderId(c.id)) com.hikari.app.sora.SoraMangaProvider(c) else com.hikari.app.manga.MangaProvider(c)
        ProviderType.IPTV -> IptvProvider(c)
        ProviderType.VEGA -> com.hikari.app.providers.vega.VegaProvider(c)
        ProviderType.SORA -> com.hikari.app.sora.SoraProvider(c)
        ProviderType.ANYMEX -> com.hikari.app.anymex.AnymexProvider(c)
        ProviderType.ANYMEX_MANGA -> com.hikari.app.anymex.AnymexMangaProvider(c)
        ProviderType.ZANGETSU -> com.hikari.app.zangetsu.ZangetsuProvider(c)
        ProviderType.FORK_VIDEO -> com.hikari.app.kotatsu.fork.ForkVideoProvider(c)
    }

    /**
     * Demand path: the provider for [id], instantiating it on the spot when
     * the background warmer has not reached it yet (see [launchWarmer]). The
     * constructor cost (~ms for one provider) is paid once, here, instead of
     * ×785 at startup — a first tap on an unwarmed extension waits a beat,
     * every tap after that is instant. Failures are logged and return null,
     * exactly like the old build's per-row guard.
     */
    fun byId(id: String): ContentProvider? {
        _providers.value.firstOrNull { it.config.id == id }?.let { return it }
        val config = lastConfigs.firstOrNull { it.id == id } ?: return null
        val loaded = runCatching { instantiate(config) }
            .onFailure { error ->
                com.hikari.app.data.Logs.log(
                    "Providers",
                    "failed to load ${config.id} on demand: ${error.message ?: error.javaClass.simpleName}",
                )
            }
            .getOrNull() ?: return null
        synchronized(demandLock) {
            _providers.value.firstOrNull { it.config.id == id }?.let { return it }
            _providers.value = _providers.value + loaded
        }
        return loaded
    }
}

object ProviderGate {
    enum class Lane { INTERACTIVE, BACKGROUND }

    private val locks = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()

    /** Per provider: how many INTERACTIVE callers are waiting for its lock. */
    private val interactiveWaiters =
        java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()

    /** How many interactive windows are open app-wide (see [interactive]). */
    private val interactiveWindows = java.util.concurrent.atomic.AtomicInteger(0)

    /** True while any interactive window is open — a page is being loaded. */
    fun interactiveActive(): Boolean = interactiveWindows.get() > 0

    suspend fun <T> interactive(block: suspend () -> T): T {
        interactiveWindows.incrementAndGet()
        try {
            return block()
        } finally {
            interactiveWindows.decrementAndGet()
        }
    }

    /** How long a BACKGROUND caller may be held off by interactive work before
     *  it goes anyway — a page that wedged must not stop the whole app's
     *  searching, and this is the backstop that says so. */
    private const val BACKGROUND_MAX_HOLD_MS = 20_000L

    private const val BACKGROUND_COURTESY_MS = 2_000L

    private const val INTERACTIVE_GATE_MAX_MS = 90_000L

    /** Same for BACKGROUND callers (their poll loop used to spin forever). */
    private const val BACKGROUND_GATE_MAX_MS = 120_000L

    private const val STUCK_GRACE_MS = 5_000L

    /** How long a background call may have waited before its wait is written to
     *  the log, with the reason. This is the line that answers "where did the
     *  minute before playback go" (see [withProvider]). */
    private const val WAIT_LOG_MS = 750L

    class ProviderStalledException(
        val providerId: String,
        val waitedMs: Long,
    ) : Exception("stalled behind an earlier call into this extension (${waitedMs / 1000}s)")

    private fun lockFor(id: String) = locks.computeIfAbsent(id) { kotlinx.coroutines.sync.Mutex() }

    private fun waitersFor(id: String) =
        interactiveWaiters.computeIfAbsent(id) { java.util.concurrent.atomic.AtomicInteger(0) }

    suspend fun <T> withProvider(
        id: String,
        lane: Lane = Lane.INTERACTIVE,
        block: suspend () -> T,
    ): T {
        if (lane == Lane.INTERACTIVE) {
            val waiters = waitersFor(id)
            waiters.incrementAndGet()
            try {
                var mutex = lockFor(id)
                val start = System.currentTimeMillis()
                var contendedSince = -1L
                while (true) {
                    if (mutex.tryLock()) {
                        try {
                            return block()
                        } finally {
                            mutex.unlock()
                        }
                    }
                    val now = System.currentTimeMillis()
                    if (contendedSince < 0L) contendedSince = now
                    if (now - contendedSince >= STUCK_GRACE_MS) {
                        locks[id] = kotlinx.coroutines.sync.Mutex()
                        mutex = lockFor(id)
                        contendedSince = now
                        com.hikari.app.data.Logs.log(
                            "Provider",
                            "$id: waiter bypassed a stuck holder after ${STUCK_GRACE_MS / 1000}s — fresh lane taken, stuck call continues in background",
                        )
                        continue
                    }
                    if (now - start >= INTERACTIVE_GATE_MAX_MS) {
                        throw ProviderStalledException(id, now - start)
                    }
                    kotlinx.coroutines.delay(25L)
                }
            } finally {
                waiters.decrementAndGet()
            }
        }
        // BACKGROUND: never queue in front of interactive work ON THIS PROVIDER
        // — a page waiting for this very extension goes first, up to the cap
        // below. Work that belongs to some other page buys only a courtesy
        // window (see [BACKGROUND_COURTESY_MS]).
        //
        // Polling rather than a fairness queue because the condition is "is the
        // user waiting", which changes while we wait; the poll is 25 ms, which
        // is nothing next to the calls this is protecting.
        var mutex = lockFor(id)
        val waiters = waitersFor(id)
        val startedAt = System.currentTimeMillis()
        var contendedSince = -1L
        while (true) {
            val elapsed = System.currentTimeMillis() - startedAt
            // Bounded like the interactive lane: a corpse must not hold the
            // poll spinning forever either (see [BACKGROUND_GATE_MAX_MS]).
            if (elapsed >= BACKGROUND_GATE_MAX_MS) {
                throw ProviderStalledException(id, elapsed)
            }
            val pageWantsThis = waiters.get() > 0 && elapsed < BACKGROUND_MAX_HOLD_MS
            val somePageLoading = waiters.get() == 0 && interactiveWindows.get() > 0 &&
                elapsed < BACKGROUND_COURTESY_MS
            if (!pageWantsThis && !somePageLoading) {
                if (mutex.tryLock()) {
                    // WHY the call started late, when it did. Without this line a
                    // slow search is one anonymous wait in a log full of them; with
                    // it, a wait is attributed to the page that caused it, to an
                    // earlier call on the same extension that never let go, or to
                    // nothing at all.
                    if (elapsed >= WAIT_LOG_MS) {
                        com.hikari.app.data.Logs.log(
                            "Provider",
                            "$id: $lane call started after waiting ${elapsed / 1000.0}s — " + when {
                                waiters.get() > 0 -> "a page is waiting for this extension"
                                interactiveWindows.get() > 0 -> "a page was loading"
                                else -> "this extension was still finishing an earlier call"
                            },
                        )
                    }
                    try {
                        return block()
                    } finally {
                        mutex.unlock()
                    }
                }
                val now = System.currentTimeMillis()
                if (contendedSince < 0L) contendedSince = now
                if (now - contendedSince >= STUCK_GRACE_MS) {
                    locks[id] = kotlinx.coroutines.sync.Mutex()
                    mutex = lockFor(id)
                    contendedSince = now
                    com.hikari.app.data.Logs.log(
                        "Provider",
                        "$id: background waiter bypassed a stuck holder after ${STUCK_GRACE_MS / 1000}s — fresh lane taken, stuck call continues in background",
                    )
                }
            } else {
                contendedSince = -1L
            }
            kotlinx.coroutines.delay(25L)
        }
    }

    fun breaker(id: String) {
        locks[id] = kotlinx.coroutines.sync.Mutex()
        interactiveWaiters.remove(id)
        com.hikari.app.data.Logs.log(
            "Provider",
            "$id: stall breaker pulled — lock replaced, later calls no longer queue behind the stuck one",
        )
    }
}
