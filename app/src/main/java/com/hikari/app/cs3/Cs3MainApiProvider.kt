package com.hikari.app.cs3

import com.hikari.app.HikariApp
import com.hikari.app.data.CatalogRef
import com.hikari.app.data.Episode
import com.hikari.app.data.MediaItem
import com.hikari.app.data.MediaType
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.StreamSource
import com.hikari.app.data.SubtitleSource
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
import kotlinx.coroutines.Dispatchers
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
            it == TvType.Movie || it == TvType.AnimeMovie
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
                // A brand-new plugin instance can fail its very first network
                // call while the runtime/session initializes — retry once.
                try {
                    a.getMainPage(page, MainPageRequest(ref.name, ref.id, false))
                } catch (e2: Throwable) {
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
        if (item.overview != null && item.genres.isNotEmpty()) return item
        val resp = loadResponse(item.id) ?: return item
        return when (resp) {
            is MovieLoadResponse -> item.copy(
                overview = resp.plot ?: item.overview,
                genres = resp.tags ?: item.genres,
                year = resp.year ?: item.year,
                backdropUrl = resp.backgroundPosterUrl ?: item.backdropUrl,
            )
            is AnimeLoadResponse -> item.copy(
                title = resp.engName?.takeIf { it.isNotBlank() } ?: item.title,
                overview = resp.plot ?: item.overview,
                genres = resp.tags ?: item.genres,
                year = resp.year ?: item.year,
                backdropUrl = resp.backgroundPosterUrl ?: item.backdropUrl,
            )
            is TvSeriesLoadResponse -> item.copy(
                overview = resp.plot ?: item.overview,
                genres = resp.tags ?: item.genres,
                year = resp.year ?: item.year,
                backdropUrl = resp.backgroundPosterUrl ?: item.backdropUrl,
            )
            else -> item
        }
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
                    .map { it.toHikari() }
            }
            is TvSeriesLoadResponse -> {
                if (resp.episodes.isEmpty()) null
                else resp.episodes.map { it.toHikari() }
            }
            else -> null
        }
    }

    override suspend fun getStreams(item: MediaItem, episode: Episode?): List<StreamSource> =
        withContext(Dispatchers.IO) {
            val a = api ?: return@withContext emptyList()
            val links = mutableListOf<com.lagradost.cloudstream3.utils.ExtractorLink>()
            val subs = mutableListOf<SubtitleFile>()
            val data = episode?.id ?: item.id
            val started = System.currentTimeMillis()
            // The CloudStream runtime resolves embeds through several network
            // calls that can each take ~10s; give it a hard cap so the UI can
            // never silently hang. On timeout, dump the stuck thread's stack so
            // the red error text tells us EXACTLY which call is blocking.
            val worker = Thread.currentThread()
            try {
                val completed = withTimeoutOrNull(90_000) {
                    a.loadLinks(data, false, { subs.add(it) }, { links.add(it) })
                }
                if (completed == null) {
                    lastStreamsError =
                        "Timed out after 90s — the provider hung while resolving sources.\n" +
                            "Stuck on thread '${worker.name}':\n" +
                            worker.stackTrace.take(25).joinToString("\n") { "    at $it" }
                } else {
                    lastStreamsError = null
                }
            } catch (e: Throwable) {
                // NoClassDefFoundError/NoSuchMethodError from extractor machinery
                // escapes loadExtractor's Exception-catch; surface the full cause
                // chain in the UI.
                lastStreamsError = fullCause(e)
                android.util.Log.e("Cs3Streams", "loadLinks failed for $data", e)
            }
            lastStreamsTimeMs = System.currentTimeMillis() - started
            val subSources = subs.map { SubtitleSource(it.lang.ifBlank { "Sub" }, it.url) }
            links
                .filter { it.url.isNotBlank() && it.url != a.mainUrl }
                .map { l ->
                    StreamSource(
                        name = l.name.ifBlank { "Stream" },
                        url = l.url,
                        headers = l.headers,
                        subtitles = subSources,
                    )
                }
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
        val r = try {
            a.load(id)
        } catch (e: Throwable) {
            null
        } ?: return null
        loadCache[id] = r
        return r
    }

    private fun SearchResponse.toMediaItem(): MediaItem? {
        if (url.isBlank() || name.isBlank()) return null
        val mt = when (type) {
            TvType.Movie, TvType.AnimeMovie -> MediaType.MOVIE
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
        )
    }

    private fun com.lagradost.cloudstream3.Episode.toHikari(): Episode {
        val num = episode ?: data?.substringAfterLast("|")?.toIntOrNull() ?: 1
        return Episode(
            number = num,
            id = data ?: num.toString(),
            name = name ?: "Episode $num",
            image = posterUrl,
        )
    }
}
