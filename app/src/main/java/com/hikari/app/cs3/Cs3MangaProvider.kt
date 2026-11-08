package com.hikari.app.cs3

import com.hikari.app.HikariApp
import com.hikari.app.data.CatalogRef
import com.hikari.app.data.Episode
import com.hikari.app.data.MediaItem
import com.hikari.app.data.MediaType
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.StreamSource
import com.hikari.app.manga.MangaProvider
import com.hikari.app.net.Http
import com.hikari.app.providers.ContentProvider
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.fixUrl
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class Cs3MangaProvider(override val config: ProviderConfig) : ContentProvider {

    companion object {
        const val MANGA_PREFIX = "manga|cs3|"

        fun isCs3MangaId(id: String): Boolean = id.startsWith(MANGA_PREFIX)

        /** Last chapter/page outcome per provider, read by the detail screen. */
        val lastOutcome = ConcurrentHashMap<String, String>()

        private val MANGA_TYPE_NAMES = setOf(
            "manga", "manhwa", "manhua", "comic", "webtoon", "manhwa18",
            "doujinshi", "doujin",
        )

        /** True when the plugin's MainAPI serves chapters/pages, not video. */
        fun isMangaApi(api: MainAPI): Boolean = runCatching {
            api.supportedTypes?.any { it.name.lowercase() in MANGA_TYPE_NAMES } == true
        }.getOrDefault(false)
    }

    private fun apiIndex(): Int = config.id.substringAfterLast("|").toIntOrNull() ?: 0

    private fun api(): MainAPI? {
        val file = File(config.url)
        if (!file.exists()) return null
        return runCatching {
            Cs3PluginManager.apisFor(HikariApp.instance, file).getOrNull(apiIndex())
        }.getOrNull()
    }

    private fun fail(what: String): Nothing? {
        lastOutcome[config.id] = "✗ " + what.take(200)
        com.hikari.app.data.Logs.log("Manga", "${config.name}: $what")
        return null
    }

    private fun toMangaItem(
        name: String,
        url: String,
        posterUrl: String?,
    ): MediaItem? {
        if (url.isBlank() || name.isBlank()) return null
        return MediaItem(
            providerId = config.id,
            id = url,
            title = name,
            type = MediaType.SERIES,
            posterUrl = posterUrl?.takeIf { it.isNotBlank() },
            rawType = "manga",
        )
    }

    override suspend fun catalogs(): List<CatalogRef> =
        withContext(Dispatchers.IO) {
            val a = api() ?: run {
                fail("extension file is missing — reinstall it in Extensions")
                return@withContext emptyList()
            }
            val pages = runCatching { a.mainPage }.getOrNull().orEmpty()
            if (pages.isEmpty()) return@withContext emptyList()
            pages.distinctBy { it.data }.map { page ->
                CatalogRef(
                    config.id,
                    MediaType.SERIES,
                    page.data.ifBlank { page.name },
                    page.name.ifBlank { page.data },
                )
            }
        }

    override suspend fun getCatalog(ref: CatalogRef, page: Int): List<MediaItem> =
        withContext(Dispatchers.IO) {
            val a = api() ?: return@withContext emptyList()
            val resp = runCatching {
                a.getMainPage(page, MainPageRequest(ref.name, ref.id, false))
            }.getOrElse { e ->
                if (e is CancellationException) throw e
                fail("catalog failed — ${e.message}")
                return@withContext emptyList()
            } ?: return@withContext emptyList()
            val out = ArrayList<MediaItem>()
            for (row in resp.items.orEmpty()) {
                for (s in row.list.orEmpty()) {
                    toMangaItem(
                        s.name,
                        s.url,
                        runCatching { s.posterUrl }.getOrNull(),
                    )?.let { out += it }
                }
            }
            if (out.isEmpty()) fail("no titles parsed from ${ref.name}")
            else lastOutcome.remove(config.id)
            out
        }

    override suspend fun search(query: String, page: Int): List<MediaItem> =
        withContext(Dispatchers.IO) {
            val a = api() ?: return@withContext emptyList()
            if (query.isBlank()) return@withContext emptyList()
            val found = runCatching {
                // Newer plugins take a page; older ones only the query (see
                // Cs3MainApiProvider.searchItems for the same fallback).
                try {
                    a.search(query, page)?.items.orEmpty()
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    a.search(query).orEmpty()
                }
            }.getOrElse { e ->
                if (e is CancellationException) throw e
                return@withContext emptyList()
            }
            found.mapNotNull { s ->
                toMangaItem(
                    s.name,
                    s.url,
                    runCatching { s.posterUrl }.getOrNull(),
                )
            }
        }

    override suspend fun getMeta(item: MediaItem): MediaItem =
        withContext(Dispatchers.IO) {
            val a = api() ?: return@withContext item
            if (item.id.isBlank()) return@withContext item
            val resp = runCatching { a.load(a.fixUrl(item.id)) }.getOrNull()
                ?: return@withContext item
            val canonicalUrl = resp.url.takeIf { it.isNotBlank() } ?: item.id
            item.copy(
                id = canonicalUrl,
                overview = resp.plot ?: item.overview,
                genres = resp.tags ?: item.genres,
                year = resp.year ?: item.year,
                posterUrl = resp.posterUrl ?: item.posterUrl,
                backdropUrl = resp.backgroundPosterUrl ?: item.backdropUrl,
            )
        }

    override suspend fun getEpisodes(item: MediaItem): List<Episode>? =
        withContext(Dispatchers.IO) {
            val a = api() ?: run {
                fail("extension file is missing — reinstall it in Extensions")
                return@withContext null
            }
            if (item.id.isBlank()) return@withContext null
            val resp = runCatching { a.load(a.fixUrl(item.id)) }.getOrElse { e ->
                if (e is CancellationException) throw e
                fail("chapters failed — ${e.message}")
                return@withContext null
            } ?: return@withContext null
            val eps: List<com.lagradost.cloudstream3.Episode> = when (resp) {
                is com.lagradost.cloudstream3.AnimeLoadResponse ->
                    resp.episodes.values.flatten()
                is com.lagradost.cloudstream3.TvSeriesLoadResponse ->
                    resp.episodes
                else -> emptyList()
            }
            if (eps.isEmpty()) {
                fail("this engine returned no chapters — pull refresh to try again")
                return@withContext null
            }
            lastOutcome.remove(config.id)
            eps.mapIndexed { idx, e ->
                val num = e.episode
                    ?: e.data?.substringAfterLast("|")?.toIntOrNull()
                    ?: (idx + 1)
                Episode(
                    number = num,
                    id = e.data ?: num.toString(),
                    name = e.name?.takeIf { it.isNotBlank() } ?: "Chapter $num",
                    image = runCatching { e.posterUrl }.getOrNull(),
                    season = e.season?.takeIf { it > 0 } ?: 1,
                )
            }
        }

    override suspend fun getStreams(item: MediaItem, episode: Episode?): List<StreamSource> =
        withContext(Dispatchers.IO) {
            val a = api() ?: return@withContext emptyList()
            val chapter = episode ?: return@withContext emptyList()
            if (chapter.id.isBlank()) return@withContext emptyList()
            val links = java.util.concurrent.CopyOnWriteArrayList<com.lagradost.cloudstream3.utils.ExtractorLink>()
            runCatching {
                a.loadLinks(chapter.id, false, { }, { links.add(it) })
            }.getOrElse { e ->
                if (e is CancellationException) throw e
                fail("pages failed — ${e.message}")
                return@withContext emptyList()
            }
            val out = ArrayList<StreamSource>()
            for (l in links) {
                val url = l.url.trim()
                if (url.isBlank()) continue
                if (!url.startsWith("http", ignoreCase = true)) continue
                // Image bytes, not a manifest: manga plugins hand out page
                // URLs, never HLS/DASH (see the video path for the reverse).
                if (url.contains(".m3u8", ignoreCase = true) || url.contains(".mpd", ignoreCase = true)) continue
                val headers = LinkedHashMap<String, String>()
                headers["User-Agent"] = Http.UA
                runCatching { l.headers }.getOrNull()?.forEach { (k, v) ->
                    val c = v.filter { it.code < 128 }
                    if (c.isNotBlank()) headers[k] = c
                }
                val ref = runCatching { l.referer }.getOrNull()
                if (!ref.isNullOrBlank()) {
                    val c = ref.filter { it.code < 128 }
                    if (c.isNotBlank()) headers.putIfAbsent("Referer", c)
                }
                out += StreamSource(
                    name = "Page ${out.size + 1}",
                    url = url,
                    headers = headers,
                    pageUrl = chapter.id,
                )
            }
            if (out.isEmpty()) fail("this chapter returned no pages — pull refresh to try again")
            else lastOutcome.remove(config.id)
            out
        }
}
