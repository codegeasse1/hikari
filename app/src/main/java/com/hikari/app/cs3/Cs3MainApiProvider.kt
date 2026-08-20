package com.hikari.app.cs3

import com.hikari.app.HikariApp
import com.hikari.app.data.CatalogRef
import com.hikari.app.data.Episode
import com.hikari.app.data.MediaItem
import com.hikari.app.data.MediaType
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.StreamSource
import com.hikari.app.data.SubtitleSource
import com.hikari.app.net.Http
import com.hikari.app.providers.ContentProvider
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Adapts a loaded CloudStream MainAPI to Hikari's ContentProvider contract so
 * .cs3 plugins appear in Home/Search/Detail/Player like any other provider.
 */
class Cs3MainApiProvider(override val config: ProviderConfig) : ContentProvider {

    companion object {
        /**
         * Per-provider last loadLinks failure (shown in the UI so users see the
         * real reason). Keyed by provider id so one provider's error can never
         * leak into another title's error panel — that's what made an
         * iStreamFlare/istreamcdn failure from one video appear on every other
         * extension's "no sources" message.
         */
        val streamErrors = java.util.concurrent.ConcurrentHashMap<String, String>()

        /** How long the last loadLinks attempt took (ms) — proof the UI did something. */
        @Volatile
        var lastStreamsTimeMs: Long = 0L

        /** Budget for the direct MovieBlast movie fallback. */
        private const val MOVIEBLAST_CAP_MS = 20_000L

        /** Budget for the universal extraction engine (StreamHG sign-dance
         *  needs several requests, so it's deliberately roomy). */
        private const val FALLBACK_CAP_MS = 20_000L

        /** Matches a provider payload whose stream URL came back empty
         *  (iStreamFlare: `{"id":"…","url": null}`) — the load() API lookup
         *  failed, so loadLinks has nothing to resolve. */
        private val HOLLOW_URL_RE = Regex("\"url\"\\s*:\\s*(null|\"\")", RegexOption.IGNORE_CASE)

        /** Per-provider reason why its home catalog failed (empty = it works). */
        val catalogErrors = java.util.concurrent.ConcurrentHashMap<String, String>()

        /**
         * CloudStream plugins set `posterHeaders` (e.g. LeakPorner demands
         * `Referer: https://leakporner.org/` for its 58img.top thumbnails), but
         * the app's image loader never saw them, so posters 403'd into blank
         * placeholders. This map lets the global Coil loader apply the exact
         * headers the provider declared for a poster URL.
         */
        val imageHeaders = ConcurrentHashMap<String, Map<String, String>>()

        /** Per image-host Referer fallback: records `Referer` for a host so a
         *  poster whose URL differs slightly (query params, www, scheme) still
         *  gets the right referer. */
        val imageHostReferers = ConcurrentHashMap<String, String>()

        init {
            // MRDS/51CG encrypt their pic.xustgq.cn posters; the plugins
            // usually decrypt them into data: URIs, but when that fails the
            // raw URL is hotlink-protected and needs the origin site as
            // Referer. (recordPosterHeaders may override per-plugin later.)
            imageHostReferers["pic.xustgq.cn"] = "https://51cg1.com/"
            imageHostReferers["www.pic.xustgq.cn"] = "https://51cg1.com/"
        }

        private fun recordPosterHeaders(url: String?, headers: Map<String, String>?) {
            if (url.isNullOrBlank() || headers.isNullOrEmpty()) return
            val trimmed = url.trim()
            imageHeaders[trimmed] = headers
            headers["Referer"]?.let { ref ->
                runCatching {
                    val host = java.net.URI(trimmed).host ?: return@runCatching
                    imageHostReferers[host.lowercase()] = ref
                    // Also cover the scheme-relative/wrapped variants
                    imageHostReferers["www." + host.lowercase()] = ref
                }
            }
        }

        /** Extracts the btih info hash from a magnet/URL (hex or base32). */
        fun infoHashOf(url: String): String? {
            Regex("""[?&]xt=urn:btih:([a-zA-Z0-9]{32,40})""").find(url)?.let { return it.groupValues[1] }
            Regex("""urn:btih:([a-zA-Z0-9]{32,40})""").find(url)?.let { return it.groupValues[1] }
            return null
        }

        /** Torrent file index from a magnet `index=` param, if any. */
        fun magnetIndex(url: String): Int? =
            Regex("""[?&]index=(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull()

        /** Tracker URLs embedded in a magnet link. */
        fun magnetTrackers(url: String): List<String> =
            Regex("""[?&]tr=([^&]+)""").findAll(url)
                .mapNotNull { m ->
                    runCatching { java.net.URLDecoder.decode(m.groupValues[1], "UTF-8") }.getOrNull()
                }
                .toList()

        private val iconFallbackCache = ConcurrentHashMap<String, String?>()

        /** Extensions without a repo icon still get one: the provider's own
         *  mainUrl favicon via Google's favicon service (the same trick the
         *  plugin repos themselves use). Never throws. */
        fun iconFallback(config: ProviderConfig): String? {
            iconFallbackCache[config.id]?.let { return it }
            val result = runCatching {
                val file = File(config.url)
                if (!file.exists()) return@runCatching null
                val apis = Cs3PluginManager.apisFor(HikariApp.instance, file)
                val index = config.id.substringAfterLast("|").toIntOrNull() ?: 0
                val mainUrl = apis.getOrNull(index)?.mainUrl?.takeIf { it.startsWith("http") }
                    ?: return@runCatching null
                val host = java.net.URI(mainUrl).host ?: return@runCatching null
                "https://www.google.com/s2/favicons?domain=$host&sz=64"
            }.getOrNull()
            // ConcurrentHashMap forbids null values — only cache hits.
            if (result != null) iconFallbackCache[config.id] = result
            return result
        }
    }

    @Volatile
    private var loadedApi: MainAPI? = null
    @Volatile
    private var apiResolved = false

    /** The plugin's MainAPI, resolved on first access. Only a SUCCESS is
     *  cached — a transient failure (the plugin still being loaded elsewhere,
     *  the load lock busy, a one-off network hiccup) is retried on the next
     *  access instead of poisoning every catalog/stream call with a
     *  permanently-null api ("no catalog found" until a force-stop). */
    private val api: MainAPI?
        get() {
            if (apiResolved) return loadedApi
            synchronized(this) {
                if (apiResolved) return loadedApi
                val file = File(config.url)
                if (!file.exists()) {
                    apiResolved = true
                    return null
                }
                val apis = Cs3PluginManager.apisFor(HikariApp.instance, file)
                val index = config.id.substringAfterLast("|").toIntOrNull() ?: 0
                val a = apis.getOrNull(index)?.also { normalizeMainUrl(it) }
                if (a != null) {
                    loadedApi = a
                    apiResolved = true
                }
                return a
            }
        }

    /** Some repo builds ship Castle TV with a redirect-id base
     *  (`https://api.hlowb.com/9919952593151901`) — every endpoint 404s under
     *  that, while the plain base works. Strip the id segment when present. */
    private fun normalizeMainUrl(a: MainAPI) {
        val m = a.mainUrl
        if (!m.startsWith("https://api.hlowb.com/")) return
        val rest = m.removePrefix("https://api.hlowb.com/").trimEnd('/')
        if (rest.isEmpty() || !rest.all { it.isDigit() }) return
        val fixed = "https://api.hlowb.com"
        // Prefer the public Kotlin `var` setter; fall back to the backing
        // field (walking the hierarchy in case it lives on the base class).
        val ok = runCatching {
            a.javaClass.methods.firstOrNull { it.name == "setMainUrl" }
                ?.invoke(a, fixed) != null
        }.getOrDefault(false)
        if (!ok) {
            var c: Class<*>? = a.javaClass
            while (c != null) {
                try {
                    c.getDeclaredField("mainUrl").also { f ->
                        f.isAccessible = true
                        f.set(a, fixed)
                    }
                    break
                } catch (e: NoSuchFieldException) {
                    c = c.superclass
                }
            }
        }
    }

    /** Force the plugin dex to load now (called at app startup so the first
     *  home/catalog request doesn't race with class loading). */
    fun warm() {
        runCatching { api }
    }

    private val loadCache = ConcurrentHashMap<String, LoadResponse>()

    override suspend fun catalogs(): List<CatalogRef> = withContext(Dispatchers.IO) {
        // CloudStream's MainPageData field order is (name, data, horizontalImages),
        // so use the fields explicitly — destructuring (url, label) would swap them
        // and getMainPage would then try to fetch the catalog's *name* as the URL.
        api?.mainPage?.map { page ->
            CatalogRef(config.id, catalogType(), page.data, page.name.ifBlank { page.data })
        } ?: emptyList()
    }

    private fun catalogType(): MediaType {
        val types = api?.supportedTypes ?: return MediaType.SERIES
        val movieOnly = types.isNotEmpty() && types.all {
            it == TvType.Movie || it == TvType.AnimeMovie || it == TvType.NSFW
        }
        return if (movieOnly) MediaType.MOVIE else MediaType.SERIES
    }

    override suspend fun getCatalog(ref: CatalogRef, page: Int): List<MediaItem> =
        withContext(Dispatchers.IO) {
            val a = api
            if (a == null) {
                catalogErrors[config.id] = apiFailureReason()
                return@withContext emptyList()
            }
            val resp = try {
                a.getMainPage(page, MainPageRequest(ref.name, ref.id, false))
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                // A brand-new plugin instance can fail its very first network
                // call while the runtime/session initializes — retry once.
                try {
                    a.getMainPage(page, MainPageRequest(ref.name, ref.id, false))
                } catch (e2: Throwable) {
                    if (e2 is CancellationException) throw e2
                    catalogErrors[config.id] = fullCause(e2)
                    return@withContext emptyList()
                }
            }
            if (resp == null) {
                catalogErrors[config.id] = "getMainPage returned null for ${ref.id}"
                return@withContext emptyList()
            }
            val items = resp.items.orEmpty().flatMap { row ->
                row.list.orEmpty().mapNotNull { it.toMediaItem() }
            }
            if (items.isEmpty()) {
                catalogErrors[config.id] = "Page fetched but no items parsed from ${ref.id}"
            } else {
                catalogErrors.remove(config.id)
            }
            items
        }

    override suspend fun search(query: String, page: Int): List<MediaItem> =
        withContext(Dispatchers.IO) {
            val a = api ?: return@withContext emptyList()
            val found = try {
                searchItems(a, query, page)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                // A brand-new plugin instance can fail its very first network
                // call while the runtime/session initializes — retry once.
                try {
                    searchItems(a, query, page)
                } catch (e2: Throwable) {
                    if (e2 is CancellationException) throw e2
                    emptyList()
                }
            }
            found.mapNotNull { it.toMediaItem() }
        }

    /** Modern plugins override the paginated `search(query, page)` — for those
     *  the plain `search(query)` overload throws NotImplementedError, which
     *  made Hikari search return nothing for every query on new-style plugins
     *  (they worked in CloudStream). Old-style plugins override
     *  `search(query)`; the base `search(query, page)` delegates to it, so
     *  calling the paginated form first is correct for BOTH generations. */
    private suspend fun searchItems(a: MainAPI, query: String, page: Int): List<SearchResponse> {
        return try {
            a.search(query, page)?.items.orEmpty()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            a.search(query).orEmpty()
        }
    }

    /** Human-readable reason why this provider's API object is unavailable. */
    private fun apiFailureReason(): String {
        val file = File(config.url)
        if (!file.exists()) {
            return "Plugin file is missing — open Extensions and reinstall this one."
        }
        val apis = Cs3PluginManager.apisFor(HikariApp.instance, file)
        val index = config.id.substringAfterLast("|").toIntOrNull() ?: 0
        if (index >= apis.size) {
            return "Plugin loaded ${apis.size} provider(s), but this entry needs #$index. Reinstall the plugin."
        }
        val detail = Cs3PluginManager.lastError
        return if (detail.isNullOrBlank()) "Plugin did not register this provider. Reinstall the plugin."
        else "Plugin failed to load:\n$detail"
    }

    override suspend fun getMeta(item: MediaItem): MediaItem {
        // Always run the provider's load() and correct the type from the actual
        // LoadResponse — many plugins report a broad/odd TvType on their search
        // results (e.g. NSFW) that would otherwise leave the detail screen with
        // neither a Play button nor an episode list.
        val resp = loadResponse(item.id) ?: return item
        // Providers frequently rewrite the URL during load() (short-links,
        // redirects, per-title API endpoints). CloudStream plays a movie via
        // the LoadResponse's FINAL url — feeding the pre-load search url to
        // loadLinks makes MovieBlast & friends report "no playable sources"
        // instantly while their episodes (whose ids come from the LoadResponse)
        // play fine. Adopt the response url as the canonical id, exactly like
        // CloudStream does.
        val canonicalUrl = resp.url.takeIf { it.isNotBlank() } ?: item.id
        if (canonicalUrl != item.id) {
            loadCache[canonicalUrl] = resp
        }
        val mt = when (resp) {
            is MovieLoadResponse -> MediaType.MOVIE
            is TvSeriesLoadResponse, is AnimeLoadResponse -> MediaType.SERIES
            else -> item.type
        }
        return when (resp) {
            is MovieLoadResponse -> item.copy(
                id = canonicalUrl,
                type = mt,
                overview = resp.plot ?: item.overview,
                genres = resp.tags ?: item.genres,
                year = resp.year ?: item.year,
                posterUrl = resp.posterUrl ?: item.posterUrl,
                backdropUrl = resp.backgroundPosterUrl ?: item.backdropUrl,
            ).also { recordRespHeaders(resp) }
            is AnimeLoadResponse -> item.copy(
                id = canonicalUrl,
                type = mt,
                title = resp.engName?.takeIf { it.isNotBlank() } ?: item.title,
                overview = resp.plot ?: item.overview,
                genres = resp.tags ?: item.genres,
                year = resp.year ?: item.year,
                posterUrl = resp.posterUrl ?: item.posterUrl,
                backdropUrl = resp.backgroundPosterUrl ?: item.backdropUrl,
            ).also { recordRespHeaders(resp) }
            is TvSeriesLoadResponse -> item.copy(
                id = canonicalUrl,
                type = mt,
                overview = resp.plot ?: item.overview,
                genres = resp.tags ?: item.genres,
                year = resp.year ?: item.year,
                posterUrl = resp.posterUrl ?: item.posterUrl,
                backdropUrl = resp.backgroundPosterUrl ?: item.backdropUrl,
            ).also { recordRespHeaders(resp) }
            else -> item
        }
    }

    private fun recordRespHeaders(resp: LoadResponse) {
        recordPosterHeaders(resp.posterUrl, resp.posterHeaders)
        recordPosterHeaders(resp.backgroundPosterUrl, resp.posterHeaders)
    }

    override suspend fun getEpisodes(item: MediaItem): List<Episode>? = withContext(Dispatchers.IO) {
        val resp = loadResponse(item.id) ?: return@withContext null
        when (resp) {
            is AnimeLoadResponse -> {
                val eps = resp.episodes.values.flatten()
                if (eps.isEmpty()) null
                else eps
                    .sortedBy { it.episode ?: Int.MAX_VALUE }
                    .distinctBy { it.data ?: it.episode ?: 0 }
                    .map { it.toHikari(resp.posterHeaders) }
            }
            is TvSeriesLoadResponse -> {
                if (resp.episodes.isEmpty()) null
                else resp.episodes
                    .distinctBy { it.data ?: it.episode ?: 0 }
                    .map { it.toHikari(resp.posterHeaders) }
            }
            else -> null
        }
    }

    override suspend fun getStreams(item: MediaItem, episode: Episode?): List<StreamSource> =
        withContext(Dispatchers.IO) {
            val a = api ?: return@withContext emptyList()
            // For SERIES, the plugin's per-episode data string lives on the
            // episode (it's a URL or a JSON payload the provider re-parses in
            // loadLinks) and is already in episode.id. For MOVIES there is no
            // episode: the provider serialized its source list into
            // MovieLoadResponse.dataUrl during load() (e.g. MoviesMod and
            // VegaMovies build `[{"source":"…"}]` and loadLinks does
            // parseJson<ArrayList<EpisodeLink>>(data)). Feeding the plain page
            // URL to loadLinks makes those providers throw Jackson's
            // "Unrecognized token 'https'" — so hand the plugin the response's
            // data string, exactly like CloudStream does.
            val movieData = if (episode == null) {
                (loadResponse(item.id) as? MovieLoadResponse)
                    ?.dataUrl
                    ?.takeIf { it.isNotBlank() }
            } else null
            val data = if (episode != null) episode.id else movieData ?: item.id
            // The universal fallback engine and the MovieBlast resolver scrape
            // a real page URL, never a serialized payload, so they keep using
            // the original item url.
            val fallbackTarget = if (episode != null) data else item.id
            val started = System.currentTimeMillis()

            // The plugin's own extraction and our universal engine (jar
            // extractor registry + Rumble/Dood scraping + packed unpacking)
            // run IN PARALLEL and their results are MERGED — so a host the
            // plugin resolves and a host it misses (Rumble, ok.ru, …) both
            // appear, just like CloudStream's source picker. Neither engine is
            // allowed to block the other: once one finishes, the other gets a
            // short grace window and then we play what we have.
            // CloudStream lets each plugin declare its own loadLinks budget
            // (MainAPI.loadLinksTimeoutMs, default 30s). Hikari used to hard-cap
            // this at 12s — and a provider that signs requests / walks several
            // API pages (MovieBlast, AllMovieLand, …) routinely blows past that
            // on a phone network, so Hikari declared "no playable sources" while
            // CloudStream happily waited. Respect the plugin's own budget now.
            val links = mutableListOf<com.lagradost.cloudstream3.utils.ExtractorLink>()
            val subs = mutableListOf<SubtitleFile>()
            val worker = Thread.currentThread()
            val rawTimeout = a.loadLinksTimeoutMs
            val pluginTimeout = if (rawTimeout != null && rawTimeout in 1..120_000L) rawTimeout else 30_000L
            // How long the merge loop waits in total. Extended if a fast-empty
            // plugin run triggers its one retry (below), so the retry is never
            // cut off by a deadline that assumed a single attempt.
            var deadline =
                started + maxOf(pluginTimeout, MOVIEBLAST_CAP_MS, FALLBACK_CAP_MS) + 2_000

            // Deliberately NOT coroutineScope: it waits for children to finish
            // cancelling, which would block here while a hung plugin drains its
            // native network call. A detached scope returns the moment we have
            // sources; the abandoned engine keeps running on IO in the background.
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val result = try {
                val pluginJob = scope.async {
                    try {
                        // One loadLinks run (returns null on timeout). A
                        // "success" that takes suspiciously little time and
                        // yields zero links is usually the provider's first
                        // network lookup failing on a cold start (iStreamFlare
                        // and friends build their pages from a JSON API that
                        // flakes under load) — give it ONE bounded retry so a
                        // transient miss can never sink the whole source list.
                        var completed: Boolean? = null
                        var elapsedMs = 0L
                        suspend fun runOnce(budget: Long, url: String) {
                            val s = System.currentTimeMillis()
                            completed = withTimeoutOrNull(budget) {
                                a.loadLinks(url, false, { subs.add(it) }, { links.add(it) })
                            }
                            elapsedMs += System.currentTimeMillis() - s
                        }
                        runOnce(pluginTimeout, data)
                        if ((completed != true || links.isEmpty()) && elapsedMs < 15_000) {
                            links.clear()
                            val retryBudget = minOf(pluginTimeout, 20_000L)
                            deadline = maxOf(deadline, System.currentTimeMillis() + retryBudget + 1_000)
                            runOnce(retryBudget, data)
                        }
                        // iStreamFlare-style providers hand loadLinks a JSON
                        // video-id payload whose "url" is null whenever their
                        // load() couldn't reach its API. A MOVIE then reports
                        // "no stream links" even though the same title's series
                        // episodes (real per-episode urls) play fine — the whole
                        // movie depends on that one lookup. Give load() one more
                        // chance here (at stream time, where the original cold
                        // start has long worn off) and retry with the fresh
                        // payload before declaring the movie unplayable.
                        if ((completed != true || links.isEmpty()) && isHollowPayload(data)) {
                            // Resolve the ORIGINAL search/page id BEFORE
                            // invalidateHollow — it finds the cache entry
                            // (key → payload) that invalidate is about to
                            // delete; after the wipe, originalIdOf comes up
                            // empty and the retry would just re-load the dead
                            // payload string instead of the real page.
                            val orig = originalIdOf(data) ?: item.id
                            invalidateHollow(data)
                            val freshBudget = minOf(pluginTimeout, 25_000L)
                            // Covers BOTH the fresh load() and the retried
                            // loadLinks, so the merge loop can't cut them off.
                            deadline = maxOf(deadline, System.currentTimeMillis() + freshBudget * 2 + 1_000)
                            val fresh = withTimeoutOrNull(freshBudget) { loadResponse(orig) }
                            val freshData = fresh?.url?.takeIf {
                                it.isNotBlank() && !isHollowPayload(it)
                            } ?: data
                            if (freshData != data) {
                                links.clear()
                                runOnce(freshBudget, freshData)
                            }
                        }
                        if (completed == null) {
                            streamErrors[config.id] =
                                "${a.name} timed out after ${pluginTimeout / 1000}s — the provider hung while resolving sources.\n" +
                                    "Stuck on thread '${worker.name}':\n" +
                                    worker.stackTrace.take(25).joinToString("\n") { "    at $it" }
                        } else {
                            val dataLabel = if (data.length > 96) data.take(96) + "…" else data
                            val who = "${a.name} [$dataLabel]"
                            streamErrors[config.id] = when {
                                completed != true -> "$who: no stream links found on the page."
                                links.isEmpty() -> "$who: page parsed OK, but produced no stream links."
                                else -> null
                            } ?: ""
                        }
                        links.toList()
                    } catch (e: Throwable) {
                        if (e is CancellationException) throw e
                        streamErrors[config.id] = fullCause(e)
                        android.util.Log.e("Cs3Streams", "loadLinks failed for $data", e)
                        emptyList()
                    }
                }
                val fallbackJob = scope.async {
                    try {
                        // Roomier than the plugin budget by design: the
                        // StreamHG/StreamGG sign-dance (page -> player -> token
                        // API) takes several requests; 12s was too tight for
                        // the last-resort pass.
                        withTimeoutOrNull(FALLBACK_CAP_MS) { FallbackResolver.resolve(fallbackTarget) } ?: emptyList()
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        emptyList()
                    }
                }
                // MovieBlast MOVIES: the plugin's movie loader returns an empty
                // but "completed" result (its series path works — episodes
                // play), so resolve movies straight from the MovieBlast API:
                // search the title -> media detail -> signed CDN links. Runs in
                // parallel with the plugin; its sources are merged in the same
                // pass below and deduped by URL.
                val movieblastJob = scope.async {
                    try {
                        // Provider name is normally "MovieBlast"; also accept
                        // the plugin's own page URL as a signal in case a
                        // rebuilt plugin names itself differently.
                        val isMb = a.name.contains("MovieBlast", ignoreCase = true) ||
                            config.name.contains("MovieBlast", ignoreCase = true) ||
                            item.id.contains("movieblast", ignoreCase = true) ||
                            item.id.contains("cloud-mb", ignoreCase = true)
                        if (!isMb || episode != null) return@async emptyList()
                        withTimeoutOrNull(MOVIEBLAST_CAP_MS) { MovieBlastResolver.resolve(item, episode) }
                            ?: emptyList()
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        emptyList()
                    }
                }

                fun pluginSources(): List<StreamSource> =
                    if (pluginJob.isCompleted) {
                        runCatching { toStreamSources(links.toList(), subs.toList()) }
                            .getOrDefault(emptyList())
                    } else emptyList()

                fun fallbackSources(): List<StreamSource> =
                    if (fallbackJob.isCompleted) {
                        runCatching { fallbackJob.getCompleted() }.getOrDefault(emptyList())
                    } else emptyList()

                fun movieblastSources(): List<StreamSource> =
                    if (movieblastJob.isCompleted) {
                        runCatching { movieblastJob.getCompleted() }.getOrDefault(emptyList())
                    } else emptyList()

                val merged = LinkedHashMap<String, StreamSource>()
                var firstSourceAt = -1L
                // True once the plugin's loadLinks has emitted at least one
                // usable link (it streams them in as it goes).
                var sawPluginSource = false
                while (true) {
                    val ps = pluginSources()
                    if (ps.isNotEmpty()) sawPluginSource = true
                    for (s in ps) merged.putIfAbsent(s.url, s)
                    for (s in fallbackSources()) merged.putIfAbsent(s.url, s)
                    for (s in movieblastSources()) merged.putIfAbsent(s.url, s)
                    val pluginDone = pluginJob.isCompleted
                    val fallbackDone = fallbackJob.isCompleted
                    val movieblastDone = movieblastJob.isCompleted
                    if (pluginDone && fallbackDone && movieblastDone) break
                    val now = System.currentTimeMillis()
                    if (merged.isNotEmpty()) {
                        if (firstSourceAt < 0) firstSourceAt = now
                        // The PLUGIN's loadLinks is the authoritative extractor
                        // — CloudStream plays exactly the servers its loadLinks
                        // produces, waiting out the plugin's own declared
                        // budget. The fallback engine is only a safety net for
                        // when the plugin comes up empty, so it must never be
                        // allowed to cancel the plugin mid-extraction: that
                        // raced the user into a raw-scanned "Hikari Auto" URL
                        // (403-prone) while the plugin's properly-signed
                        // StreamHG link was still one request away.
                        val waited = now - firstSourceAt >= 2_000L
                        val openNow = when {
                            // Plugin still working → open fast only if it has
                            // already handed us servers (its later servers are
                            // a nice-to-have; playback speed is the point).
                            !pluginDone && sawPluginSource -> waited
                            // Plugin finished WITH servers → open fast.
                            pluginDone && sawPluginSource -> waited
                            // Plugin finished EMPTY → the fallback is the only
                            // hope; don't cut it off, wait for its full budget
                            // (it's what finds the StreamHG/VidHidePro server
                            // when the plugin's own resolver dies).
                            pluginDone -> fallbackDone && waited
                            else -> false
                        }
                        if (openNow) break
                    }
                    if (now > deadline) break
                    kotlinx.coroutines.delay(80)
                }
                if (!pluginJob.isCompleted) pluginJob.cancel()
                if (!fallbackJob.isCompleted) fallbackJob.cancel()
                if (!movieblastJob.isCompleted) movieblastJob.cancel()
                merged.values.toList()
            } finally {
                scope.cancel()
            }

            if (result.isNotEmpty()) streamErrors.remove(config.id)
            lastStreamsTimeMs = System.currentTimeMillis() - started
            result
        }

    /** Maps the plugin's raw ExtractorLinks into Hikari StreamSources with
     *  CloudStream-style names ("OkRuSSL 1080p") and referer/header merging. */
    private fun toStreamSources(
        rawLinks: List<com.lagradost.cloudstream3.utils.ExtractorLink>,
        rawSubs: List<SubtitleFile>,
    ): List<StreamSource> {
        val a = api
        return rawLinks
            .filter { it.url.isNotBlank() && it.url != a?.mainUrl && it.type.name != "ERROR" }
            .map { l ->
                // CloudStream keeps the Referer OUT of ExtractorLink.headers —
                // without it most anime CDNs answer with an anti-hotlink HTML
                // page and ExoPlayer reports PARSING_CONTAINER_UNSUPPORTED.
                // Merge referer in (keeping any Referer the extractor set),
                // and carry the container type so the player can pick HLS/DASH.
                // Header values are sanitized to ASCII: addons occasionally ship
                // a User-Agent with Cyrillic look-alike characters, and OkHttp
                // rejects any header value containing chars > 127 (the player
                // ALSO sanitizes as a second line of defense).
                val headers = LinkedHashMap<String, String>()
                l.headers?.forEach { (k, v) ->
                    val c = v.filter { it.code < 128 }
                    if (c.isNotBlank()) headers[k] = c
                }
                val ref = l.referer
                if (!ref.isNullOrBlank()) {
                    val c = ref.filter { it.code < 128 }
                    if (c.isNotBlank()) headers.putIfAbsent("Referer", c)
                }
                val subSources = rawSubs.map { SubtitleSource(it.lang.ifBlank { "Sub" }, it.url) }
                // Magnet / .torrent links go through the same TorrServer
                // engine as Stremio infoHash streams.
                val isTorrent = l.type.name == "MAGNET" || l.type.name == "TORRENT" ||
                    l.url.startsWith("magnet:", true) || l.url.startsWith("torrent:", true)
                val qualityLabel = com.lagradost.cloudstream3.utils.Qualities.getStringByInt(l.quality)
                val baseName = l.name.ifBlank { "Stream" }
                StreamSource(
                    name = if (qualityLabel.isNotBlank() && !baseName.contains(qualityLabel, ignoreCase = true)) {
                        "$baseName $qualityLabel"
                    } else {
                        baseName
                    },
                    // Google Drive share/download links answer with an HTML
                    // virus-scan page, not video bytes — normalize them to
                    // the direct drive.usercontent download form so the
                    // player gets raw HLS/MP4 (MoviesMod & friends).
                    url = if (isTorrent) l.url else Http.normalizeDriveUrl(l.url),
                    headers = headers,
                    subtitles = subSources,
                    isM3u8 = l.isM3u8,
                    isMpd = l.isDash,
                    isTorrent = isTorrent,
                    infoHash = if (isTorrent) infoHashOf(l.url) else null,
                    fileIdx = magnetIndex(l.url),
                    trackers = magnetTrackers(l.url),
                )
            }
            .distinctBy { it.url }
    }

    private fun fullCause(e: Throwable): String {
        val sb = StringBuilder()
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth < 4) {
            if (depth > 0) sb.append("\nCaused by: ")
            sb.append("${t.javaClass.simpleName}: ${t.message}\n")
            sb.append(t.stackTrace.take(5).joinToString("\n") { "    at $it" })
            t = t.cause
            depth++
        }
        return sb.toString()
    }

    /** iStreamFlare-style providers put a JSON video-id list in LoadResponse.url
     *  with `"url": null` when their API lookup failed. */
    private fun isHollowPayload(url: String): Boolean = HOLLOW_URL_RE.containsMatchIn(url)

    /** Drops the cached load for a (possibly rewritten) url so a fresh load()
     *  runs — the hollow movie payload is cached under BOTH the original id
     *  and the payload itself, so clear both. */
    private fun invalidateHollow(url: String) {
        loadCache.remove(url)
        val it = loadCache.entries.iterator()
        while (it.hasNext()) {
            val (k, v) = it.next()
            if (v.url == url) it.remove()
        }
    }

    /** Finds the original search/page id that produced a (rewritten) url. */
    private fun originalIdOf(url: String): String? {
        for ((k, v) in loadCache) if (v.url == url) return k
        return null
    }

    /** One load() attempt, treating a plugin crash as a miss (never throwing). */
    private suspend fun tryLoad(a: MainAPI, id: String): LoadResponse? = try {
        a.load(id)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        null
    }

    private suspend fun loadResponse(id: String): LoadResponse? {
        loadCache[id]?.let { return it }
        val a = api ?: return null
        // Some providers' load() walks many pages (e.g. PimpBunny model pages
        // paginate up to 50) — cap it so the detail screen can never hang.
        val r = try {
            withTimeoutOrNull(45_000) {
                val first = tryLoad(a, id)
                // Providers like iStreamFlare build a JSON video-id string and
                // fill its `url` field during load(); a hollow one (url:null,
                // or blank) usually means the very first network lookup after a
                // cold start failed, so give load() one retry before caching a
                // dead response forever.
                val hollow = first == null || first.url.isBlank() || isHollowPayload(first.url)
                if (!hollow) first else tryLoad(a, id)
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            null
        } ?: return null
        loadCache[id] = r
        return r
    }

    private fun SearchResponse.toMediaItem(): MediaItem? {
        if (url.isBlank() || name.isBlank()) return null
        val mt = when (type) {
            // NSFW providers (LeakPorner, KanAV, …) label their single-video
            // results NSFW — treat as movies; getMeta later corrects actor
            // pages to SERIES from the LoadResponse type.
            TvType.Movie, TvType.AnimeMovie, TvType.NSFW -> MediaType.MOVIE
            TvType.TvSeries, TvType.Anime, TvType.Cartoon, TvType.OVA, TvType.AsianDrama -> MediaType.SERIES
            else -> MediaType.UNKNOWN
        }
        val year = when (this) {
            is com.lagradost.cloudstream3.MovieSearchResponse -> this.year
            is com.lagradost.cloudstream3.AnimeSearchResponse -> this.year
            is com.lagradost.cloudstream3.TvSeriesSearchResponse -> this.year
            else -> null
        }
        return MediaItem(
            providerId = config.id,
            id = url,
            title = name,
            type = mt,
            posterUrl = posterUrl,
            year = year,
        ).also { recordPosterHeaders(posterUrl, posterHeaders) }
    }

    private fun com.lagradost.cloudstream3.Episode.toHikari(respHeaders: Map<String, String>?): Episode {
        val num = episode ?: data?.substringAfterLast("|")?.toIntOrNull() ?: 1
        return Episode(
            number = num,
            id = data ?: num.toString(),
            name = name ?: "Episode $num",
            image = posterUrl,
        ).also { recordPosterHeaders(posterUrl, respHeaders) }
    }
}
