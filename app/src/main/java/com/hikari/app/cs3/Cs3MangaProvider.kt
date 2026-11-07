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
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A CloudStream manga module behind the manga tab's engine contract.
 *
 * CloudStream APK plugins whose `supportedTypes` name pages instead of video
 * ("manga", "manhwa", "manhua", …) are registered as [ProviderType.MANGA]
 * rows (`manga|cs3|<fileHash>|<i>`) instead of video rows, so installing a
 * manga extension from any CloudStream repo lands it in the manga tab —
 * Browse, search and the reader — instead of on Home as a video extension
 * that can never play (see [Cs3ProviderSync] for the install-time routing).
 *
 * Served through the plugin's own MainAPI, the same calls the video path
 * makes ([Cs3MainApiProvider]): catalogs from `mainPage`, chapters from
 * `load().episodes`, pages from `loadLinks` image URLs with the referer the
 * hotlink CDNs demand.
 */
class Cs3MangaProvider(override val config: ProviderConfig) : ContentProvider {

    companion object {
        const val MANGA_PREFIX = "manga|cs3|"

        fun isCs3MangaId(id: String): Boolean = id.startsWith(MANGA_PREFIX)

        /** Last chapter/page outcome per provider, read by the detail screen. */
        val lastOutcome = ConcurrentHashMap<String, String>()

        /**
         * `supportedTypes` entry names that mean chapters/pages instead of
         * video. Name-based on purpose: it compiles whatever the vendored
         * CloudStream enum carries, and a future enum value with one of these
         * names is routed without an app change. Anything else stays video.
         */
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
            // Same fields the video path reads off the response (see
            // Cs3MainApiProvider.getMeta): the canonical url is adopted like
            // CloudStream does, the search-time title stays (a manga search
            // title is already exact, and only AnimeLoadResponse carries a
            // second English name).
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
            // Manga modules serve chapters as episodes: AnimeLoadResponse
            // carries them grouped in a map, TvSeriesLoadResponse flat (see
            // Cs3MainApiProvider.getEpisodes for the same split).
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
                // Hotlink CDNs answer image requests with an anti-hotlink
                // page unless the Referer rides along (see the video path's
                // header merge, same rule). Header values stay ASCII.
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
