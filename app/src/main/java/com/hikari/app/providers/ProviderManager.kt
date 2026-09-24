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
    private val locks = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()

    private fun lockFor(id: String) = locks.computeIfAbsent(id) { kotlinx.coroutines.sync.Mutex() }

    suspend fun <T> withProvider(id: String, block: suspend () -> T): T =
        lockFor(id).withLock { block() }
}
