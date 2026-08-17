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
        /** Last loadLinks failure (shown in the UI so users see the real reason). */
        @Volatile
        var lastStreamsError: String? = null

        /** How long the last loadLinks attempt took (ms) — proof the UI did something. */
        @Volatile
        var lastStreamsTimeMs: Long = 0L

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
    }

    private val api: MainAPI? by lazy {
        val file = File(config.url)
        if (!file.exists()) {
            null
        } else {
            val apis = Cs3PluginManager.apisFor(HikariApp.instance, file)
            val index = config.id.substringAfterLast("|").toIntOrNull() ?: 0
            apis.getOrNull(index)
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
                a.search(query)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                emptyList()
            }
            found.orEmpty().mapNotNull { it.toMediaItem() }
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
        val mt = when (resp) {
            is MovieLoadResponse -> MediaType.MOVIE
            is TvSeriesLoadResponse, is AnimeLoadResponse -> MediaType.SERIES
            else -> item.type
        }
        return when (resp) {
            is MovieLoadResponse -> item.copy(
                type = mt,
                overview = resp.plot ?: item.overview,
                genres = resp.tags ?: item.genres,
                year = resp.year ?: item.year,
                posterUrl = resp.posterUrl ?: item.posterUrl,
                backdropUrl = resp.backgroundPosterUrl ?: item.backdropUrl,
            ).also { recordRespHeaders(resp) }
            is AnimeLoadResponse -> item.copy(
                type = mt,
                title = resp.engName?.takeIf { it.isNotBlank() } ?: item.title,
                overview = resp.plot ?: item.overview,
                genres = resp.tags ?: item.genres,
                year = resp.year ?: item.year,
                posterUrl = resp.posterUrl ?: item.posterUrl,
                backdropUrl = resp.backgroundPosterUrl ?: item.backdropUrl,
            ).also { recordRespHeaders(resp) }
            is TvSeriesLoadResponse -> item.copy(
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
            val data = episode?.id ?: item.id
            val started = System.currentTimeMillis()

            // The plugin's own extraction and our universal engine (jar
            // extractor registry + Rumble/Dood scraping + packed unpacking)
            // run IN PARALLEL and their results are MERGED — so a host the
            // plugin resolves and a host it misses (Rumble, ok.ru, …) both
            // appear, just like CloudStream's source picker. Neither engine is
            // allowed to block the other: once one finishes, the other gets a
            // short grace window and then we play what we have.
            val links = mutableListOf<com.lagradost.cloudstream3.utils.ExtractorLink>()
            val subs = mutableListOf<SubtitleFile>()
            val worker = Thread.currentThread()

            // Deliberately NOT coroutineScope: it waits for children to finish
            // cancelling, which would block here while a hung plugin drains its
            // native network call. A detached scope returns the moment we have
            // sources; the abandoned engine keeps running on IO in the background.
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val result = try {
                val pluginJob = scope.async {
                    try {
                        val completed = withTimeoutOrNull(25_000) {
                            a.loadLinks(data, false, { subs.add(it) }, { links.add(it) })
                        }
                        if (completed == null) {
                            lastStreamsError =
                                "Timed out after 25s — the provider hung while resolving sources.\n" +
                                    "Stuck on thread '${worker.name}':\n" +
                                    worker.stackTrace.take(25).joinToString("\n") { "    at $it" }
                        } else {
                            lastStreamsError = when {
                                !completed -> "The provider could not resolve any sources for this title " +
                                    "(it found no stream links on the page)."
                                links.isEmpty() -> "The provider resolved links, but every one of them " +
                                    "was unusable (blank, or a dead type)."
                                else -> null
                            }
                        }
                        links.toList()
                    } catch (e: Throwable) {
                        if (e is CancellationException) throw e
                        lastStreamsError = fullCause(e)
                        android.util.Log.e("Cs3Streams", "loadLinks failed for $data", e)
                        emptyList()
                    }
                }
                val fallbackJob = scope.async {
                    try {
                        withTimeoutOrNull(25_000) { FallbackResolver.resolve(data) } ?: emptyList()
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

                val merged = LinkedHashMap<String, StreamSource>()
                val deadline = started + 28_000
                var firstSourceAt = -1L
                while (true) {
                    for (s in pluginSources()) merged.putIfAbsent(s.url, s)
                    for (s in fallbackSources()) merged.putIfAbsent(s.url, s)
                    val pluginDone = pluginJob.isCompleted
                    val fallbackDone = fallbackJob.isCompleted
                    if (pluginDone && fallbackDone) break
                    val now = System.currentTimeMillis()
                    if (merged.isNotEmpty()) {
                        if (firstSourceAt < 0) firstSourceAt = now
                        // Fallback done but plugin still going: the universal
                        // engine already gave us playable servers — stop
                        // waiting for a hung plugin after a short grace.
                        // Plugin done but fallback still going: wait a little
                        // longer so the fallback's servers (Rumble…) join.
                        val grace = if (fallbackDone) 3_000L else 5_000L
                        if ((pluginDone || fallbackDone) && now - firstSourceAt >= grace) break
                    }
                    if (now > deadline) break
                    kotlinx.coroutines.delay(80)
                }
                if (!pluginJob.isCompleted) pluginJob.cancel()
                if (!fallbackJob.isCompleted) fallbackJob.cancel()
                merged.values.toList()
            } finally {
                scope.cancel()
            }

            if (result.isNotEmpty()) lastStreamsError = null
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
                val headers = LinkedHashMap<String, String>()
                l.headers?.forEach { (k, v) -> headers[k] = v }
                val ref = l.referer
                if (!ref.isNullOrBlank()) {
                    headers.putIfAbsent("Referer", ref)
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

    private suspend fun loadResponse(id: String): LoadResponse? {
        loadCache[id]?.let { return it }
        val a = api ?: return null
        // Some providers' load() walks many pages (e.g. PimpBunny model pages
        // paginate up to 50) — cap it so the detail screen can never hang.
        val r = try {
            withTimeoutOrNull(45_000) { a.load(id) }
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
