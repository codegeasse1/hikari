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

    /**
     * Builds the provider list from the stored configs.
     *
     * The adult-content switch is applied HERE, at the source, rather than in
     * each screen that lists providers (see [com.hikari.app.data.NsfwGate]): a
     * provider that is not in this list is not instantiated, not asked for
     * catalogues, not searched, and not shown — so with the switch off an 18+
     * extension's titles cannot reach Home, a collection, the search queue or
     * the "Continue watching" shelf by any route. Filtering the drawn lists
     * instead would leave every one of those paths intact behind the UI.
     *
     * Flipping the switch is therefore the one thing that makes an adult
     * provider appear or disappear mid-session, and
     * [com.hikari.app.HikariApp] re-runs this when the preference changes.
     */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        // COALESCED. An install asks for a rebuild when it finishes AND the
        // extension manager asks again as its own load lands, and an
        // "Install all" run asks after every single entry — while building the
        // list costs one instantiation per installed provider and gets more
        // expensive as the list grows. A request that arrives while a build is
        // running is therefore answered by ONE more build afterwards instead of
        // a build of its own, which keeps installing fast on a machine that is
        // already busy with a download and a dex load.
        if (!refreshLock.tryLock()) {
            refreshQueued = true
            return@withContext
        }
        try {
            do {
                refreshQueued = false
                val configs = ExtensionNsfw.filter(context, store.providers())
                // Same configs in, same providers out — and assigning the same
                // list again is NOT harmless: it emits on [providers], which
                // re-runs every screen effect that watches it (the extensions
                // screen re-hashes its installed files and re-adopts the 18+
                // flags on every emission), and it throws away the extension
                // instances that are already instantiated. An install-all run
                // ends with a rebuild, the store's own flow usually asks for one
                // right after, and the second one used to be pure work.
                if (configs == lastConfigs && _providers.value.isNotEmpty()) continue
                lastConfigs = configs
                _providers.value = configs.mapNotNull { instantiate(it) }
            } while (refreshQueued)
        } finally {
            refreshLock.unlock()
        }
    }

    /**
     * Asks the installed extensions that nothing else could classify whether they
     * are 18+ ones, and writes the answer onto their rows.
     *
     * [refresh] can only apply what is already KNOWN: the flag the repo listing
     * declared ([ProviderConfig.nsfw]) or the extension metadata the manga/anime
     * engines publish. A CloudStream/Hikari/SkyStream/Nuvio extension declares
     * adult content per TITLE, so for one of those installed before the flag was
     * recorded, the only source is the extension itself
     * ([ContentProvider.adultExtension]) — which means loading it. That is why
     * this is a separate, on-demand pass: it runs only with the switch OFF, it
     * asks each extension at most once EVER (the answer is persisted on the row,
     * so the next launch needs no load at all), and [limit] bounds how much of it
     * one launch pays so a library of a hundred extensions cannot turn switching
     * the filter off into a minute of background plugin loads.
     *
     * Returns true when an extension turned out to be an adult one, i.e. when the
     * caller should [refresh] again so it actually disappears.
     */
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
        ProviderType.MANGA -> com.hikari.app.manga.MangaProvider(c)
        ProviderType.IPTV -> IptvProvider(c)
    }

    fun byId(id: String): ContentProvider? =
        _providers.value.firstOrNull { it.config.id == id }
}

/**
 * Serialises the calls Hikari makes into ONE extension instance.
 *
 * Every extension — a CloudStream `.cs3` plugin, a Hikari `.hiki` bundle, a
 * Nuvio script — is an object we do not control, and most of them keep mutable
 * state while a search runs. Two calls in flight at once (the origin pass and
 * the cross-repo pass, or a meta/episode fetch landing while a stream lookup is
 * still walking the same object) on the same instance is how an extension's own
 * unsynchronised `ArrayList` gets corrupted: the recorded crash
 * `ArrayIndexOutOfBoundsException: length=49; index=49 at java.util.ArrayList.add`
 * inside a bridge provider, thrown from an extension's own coroutine, is exactly
 * that — two `add`s racing on one list.
 *
 * Holding a per-provider lock costs nothing when the calls are already
 * sequential, and it removes the app's contribution to that race: Hikari never
 * has two calls inside the same extension at the same time. A wedged extension
 * cannot block the others (a lock each), and a call that throws simply releases
 * it.
 */
object ProviderGate {
    /**
     * Which kind of caller wants the provider — and therefore who goes first.
     *
     * The gate serialises calls into one extension so Hikari never contributes
     * to a race inside it. That is about *mutual exclusion*, not about *order*,
     * and order is what this enum is for: a plain FIFO mutex made a screen the
     * user is waiting on queue behind whatever background work happened to ask
     * first. The reported shape of that was "Aniyomi and SkyStream take almost
     * 15 seconds to load the episode list and everything on the detail page":
     * the page's own source prefetch (a stream pass, tens of seconds inside one
     * Aniyomi extension) ran on the same provider, and the metadata/episode
     * calls the page was actually waiting on were queued behind it.
     *
     *  * [INTERACTIVE] — something the user is watching: meta, episodes, a
     *    catalog page, a search they asked for. These queue in FIFO order among
     *    themselves and are never overtaken.
     *  * [BACKGROUND] — the cross pass, a background sweep, a stream prefetch.
     *    These never START while interactive work is waiting or active (see
     *    [withProvider]) so they can no longer hold a page up.
     */
    enum class Lane { INTERACTIVE, BACKGROUND }

    private val locks = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()

    /** Per provider: how many INTERACTIVE callers are waiting for its lock. */
    private val interactiveWaiters =
        java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()

    /** How many interactive windows are open app-wide (see [interactive]). */
    private val interactiveWindows = java.util.concurrent.atomic.AtomicInteger(0)

    /** True while any interactive window is open — a page is being loaded. */
    fun interactiveActive(): Boolean = interactiveWindows.get() > 0

    /**
     * Marks [block] as a window in which the user is waiting on provider work
     * (a detail page loading its meta and episodes). Inside it every provider
     * call is INTERACTIVE, and — the part that matters — no BACKGROUND pass
     * will START a provider call for the duration, so the page is not queued
     * behind work nobody is watching.
     *
     * Bounded by nature: a window is one page load. Background work is never
     * starved indefinitely (see [BACKGROUND_MAX_HOLD_MS]).
     */
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

    /**
     * How long a BACKGROUND caller gives a page that is loading SOMEBODY ELSE a
     * head start.
     *
     * The app-wide "is a page loading" check used to be the same 20-second hold
     * the same-provider one gets, and that is a delay with nothing on the other
     * side of it: a stream lookup could spend its first twenty seconds parked
     * because an unrelated screen was loading an unrelated extension — no lock
     * of its own was contended at all. A couple of seconds still lets a page get
     * its own requests in first, which is the etiquette the check was for; the
     * case that was ever REPORTED as a page being held up is the same-provider
     * one, and that keeps its full hold.
     */
    private const val BACKGROUND_COURTESY_MS = 2_000L

    /** How long a background call may have waited before its wait is written to
     *  the log, with the reason. This is the line that answers "where did the
     *  minute before playback go" (see [withProvider]). */
    private const val WAIT_LOG_MS = 750L

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
                return lockFor(id).withLock { block() }
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
        val mutex = lockFor(id)
        val waiters = waitersFor(id)
        val startedAt = System.currentTimeMillis()
        while (true) {
            val elapsed = System.currentTimeMillis() - startedAt
            val pageWantsThis = waiters.get() > 0 && elapsed < BACKGROUND_MAX_HOLD_MS
            val somePageLoading = waiters.get() == 0 && interactiveWindows.get() > 0 &&
                elapsed < BACKGROUND_COURTESY_MS
            if (!pageWantsThis && !somePageLoading && mutex.tryLock()) {
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
            kotlinx.coroutines.delay(25L)
        }
    }
}
