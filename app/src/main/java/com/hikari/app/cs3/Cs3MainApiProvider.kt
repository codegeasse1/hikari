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
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Adapts a loaded CloudStream MainAPI to Hikari's ContentProvider contract so
 * .cs3 plugins appear in Home/Search/Detail/Player like any other provider.
 */
class Cs3MainApiProvider(override val config: ProviderConfig) : ContentProvider {

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

    private val loadCache = ConcurrentHashMap<String, LoadResponse>()

    override suspend fun catalogs(): List<CatalogRef> = withContext(Dispatchers.IO) {
        api?.mainPage?.map { (url, label) ->
            CatalogRef(config.id, catalogType(), url, label.ifBlank { url })
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
            val a = api ?: return@withContext emptyList()
            val resp = try {
                a.getMainPage(page, MainPageRequest(ref.name, ref.id, false))
            } catch (e: Throwable) {
                return@withContext emptyList()
            }
            resp.items.flatMap { list -> list.list.mapNotNull { it.toMediaItem() } }
        }

    override suspend fun search(query: String, page: Int): List<MediaItem> =
        withContext(Dispatchers.IO) {
            val a = api ?: return@withContext emptyList()
            try {
                a.search(query)
            } catch (e: Throwable) {
                emptyList()
            }.mapNotNull { it.toMediaItem() }
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
            try {
                a.loadLinks(data, false, { subs.add(it) }, { links.add(it) })
            } catch (e: Throwable) {
            }
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

    private suspend fun loadResponse(id: String): LoadResponse? {
        loadCache[id]?.let { return it }
        val a = api ?: return null
        val r = try {
            a.load(id)
        } catch (e: Throwable) {
            return null
        }
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
