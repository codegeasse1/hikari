package com.hikari.app.aniyomi

import com.hikari.app.HikariApp
import com.hikari.app.data.CatalogRef
import com.hikari.app.data.Episode
import com.hikari.app.data.MediaItem
import com.hikari.app.data.MediaType
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.StreamSource
import com.hikari.app.data.screenTypeFor
import com.hikari.app.data.SubtitleSource
import com.hikari.app.net.Http
import com.hikari.app.providers.ContentProvider
import com.hikari.app.providers.ProviderGate
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Hoster.Companion.toHosterList
import eu.kanade.tachiyomi.animesource.model.HttpServer
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SAnimeImpl
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.SEpisodeImpl
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class AniyomiProvider(override val config: ProviderConfig) : ContentProvider {

    companion object {
        /** Per-provider last stream failure (shown on the Detail screen). */
        val streamErrors = ConcurrentHashMap<String, String>()

        /** Per-provider last lookup outcome — one short line per extension,
         *  shown in the sources sheet (same contract as SkyStreamProvider). */
        val lastOutcome = ConcurrentHashMap<String, String>()

        /** Per-provider home/search failure (shown on the Home empty state). */
        val catalogErrors = ConcurrentHashMap<String, String>()

        private const val MAX_ITEMS_PER_ROW = 60
        private const val MAX_EPISODES = 2000
        /** How long an EMPTY episode answer is remembered (see [episodesMissAt]).
         *  Pure de-duplication of a burst of callers — never a verdict: an
         *  episode list that failed is asked for again a few seconds later. */
        private const val EPISODE_MISS_TTL_MS = 20_000L
        /** One hoster's video fetch never holds the list hostage past this. */
        private const val HOSTER_TIMEOUT_MS = 30_000L
        /** One tap's resolve (resolveVideo + getVideoUrl fill) is bounded by this. */
        private const val RESOLVE_TIMEOUT_MS = 30_000L

        /** providerId -> live instance, so the player's tap can resolve through
         *  the same provider that listed the servers (Yomi's getResolvedVideo). */
        private val instances = ConcurrentHashMap<String, AniyomiProvider>()

        /** providerId + "\n" + videoUrl -> hoster name + the extension's live
         *  Video, kept UNRESOLVED at list time and resolved once on tap. */
        private val playCache = ConcurrentHashMap<String, Pair<String, Video>>()

        /** packageName -> the extension's started local HTTP server (ext-lib 17
         *  `createHttpServer` — the app starts it and rewrites the
         *  `http://localhost:1…` placeholder to the live port, like Yomi). */
        private val httpServers = ConcurrentHashMap<String, HttpServer>()

        suspend fun resolveForPlay(providerId: String, url: String): StreamSource? =
            withContext(Dispatchers.IO) {
                val inst = instances[providerId] ?: return@withContext null
                inst.resolveVideoForPlay(url)
            }
    }

    /** `aniyomi|<packageName>|<sourceIndex>` → the extension it belongs to. */
    private val pkgName: String get() = AniyomiExtensionManager.packageOf(config)

    private val sourceIndex: Int get() = AniyomiExtensionManager.indexOf(config)

    private val extFile: File get() {
        val stored = File(config.url)
        if (stored.exists()) return stored
        val canonical = AniyomiExtensionManager.extensionFile(HikariApp.instance, pkgName)
        return if (canonical.exists()) canonical else stored
    }

    /** The live source object (blocking — never call on the UI thread). */
    private fun source(): AnimeSource? =
        AniyomiExtensionManager.sourceOf(HikariApp.instance, extFile, sourceIndex)

    /** Warms the extension's class load so the first Home/search does not pay
     *  for it (mirrors `Cs3MainApiProvider.warm`). */
    suspend fun warm() {
        withContext(Dispatchers.IO) { runCatching { source() } }
        runCatching { AniyomiExtensionManager.siteUrlOf(config) }
    }

    init {
        instances[config.id] = this
    }

    private fun playKey(url: String) = config.id + "\n" + url

    /**
     * Starts the extension's local HTTP server when it provides one
     * (ext-lib 17 `createHttpServer`) — the app owns the server's lifecycle,
     * exactly like Yomi/Aniyomi do. Videos served through it carry the
     * `http://localhost:1…` placeholder until [rewriteLocal] swaps in the
     * live port. One server per package, kept for the process: tearing it
     * down between a search and its playback would orphan every localhost
     * link it handed out.
     */
    private fun ensureHttpServer(src: AnimeSource) {
        val http = src as? AnimeHttpSource ?: return
        val pkg = pkgName
        if (httpServers[pkg]?.isRunning() == true) return
        val server = runCatching { http.createHttpServer() }.getOrNull() ?: return
        runCatching { server.start() }
        if (server.isRunning()) httpServers[pkg] = server
    }

    /** Rewrites a local-server video URL to the started server's live port
     *  (their `Video.copyHttpServer`), like Yomi does before handing a video
     *  to its player. Non-local videos pass through untouched. */
    private fun rewriteLocal(video: Video): Video {
        if (!video.usesHttpServer()) return video
        val port = httpServers[pkgName]?.takeIf { it.isRunning() }?.listeningPort ?: return video
        if (port <= 0) return video
        return runCatching { video.copyHttpServer(port) }.getOrDefault(video)
    }

    /** The tap half of list-then-resolve: finishes one cached [Video] into a
     *  playable [StreamSource], or null when it cannot be resolved. */
    private suspend fun resolveVideoForPlay(url: String): StreamSource? {
        val src = source() ?: return null
        val http = src as? AnimeHttpSource ?: return null
        ensureHttpServer(http)
        // Stored keys are providerId + url + hoster + title (same embed URL
        // can sit under several hosters) — the tap matches by its URL prefix.
        val prefix = playKey(url) + "\n"
        val hit = playCache.entries.firstOrNull { it.key.startsWith(prefix) } ?: return null
        val (hosterName, cached) = hit.value
        var video = cached
        if (!video.initialized) {
            val resolved = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
                runCatching { http.resolveVideo(video) }.getOrNull()
            } ?: video
            video = resolved
        }
        if (isUnresolved(video.videoUrl)) {
            val filled = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
                runCatching { http.getVideoUrl(video) }.getOrNull()
            }
            if (!filled.isNullOrBlank() && !isUnresolved(filled)) {
                video = runCatching { video.copy(videoUrl = filled) }.getOrDefault(video)
            }
        }
        video = rewriteLocal(video)
        if (video.videoUrl.isBlank() || isUnresolved(video.videoUrl)) return null
        playCache[hit.key] = hosterName to
            runCatching { video.copy(initialized = true) }.getOrDefault(video)
        return toStreamSource(src, hosterName, video)
    }

    private fun fail(msg: String): List<StreamSource> {
        streamErrors[config.id] = msg
        lastOutcome[config.id] = msg.take(80)
        noteWall(msg)
        return emptyList()
    }

    private fun missingReason(): String =
        AniyomiExtensionManager.loadFailure(extFile)
            ?: AniyomiExtensionManager.lastError
            ?: "Extension file missing — reinstall this extension"

    // ---- Caches ----

    /** The live `SAnime` per [MediaItem.id] — the exact object the source made,
     *  so `animeDetailsRequest(baseUrl + anime.url)` still resolves. */
    private val animeCache = object : LinkedHashMap<String, SAnime>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SAnime>?) = size > 128
    }

    private val episodeCache = object : LinkedHashMap<String, SEpisode>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SEpisode>?) = size > 4096
    }

    /** Episodes per [MediaItem.id]. */
    private val episodesByAnime = object : LinkedHashMap<String, List<Episode>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Episode>>?) = size > 32
    }

    private val episodesMissAt = object : LinkedHashMap<String, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 64
    }

    /** Enriched meta per [MediaItem.id]. */
    private val metaByAnime = object : LinkedHashMap<String, MediaItem>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MediaItem>?) = size > 32
    }

    private fun <T> lockedGet(map: Map<String, T>, key: String): T? = synchronized(map) { map[key] }

    private fun <T> lockedPut(map: MutableMap<String, T>, key: String, value: T) {
        synchronized(map) { map[key] = value }
    }

    private val combinedCalls = ConcurrentHashMap<String, Boolean>()

    // ---- Catalogue ----

    override suspend fun catalogs(): List<CatalogRef> = gate {
        val src = source() ?: run {
            catalogErrors[config.id] = missingReason()
            return@gate emptyList()
        }
        val latest = runCatching { src.supportsLatest }.getOrDefault(false)
        val out = mutableListOf(
            CatalogRef(config.id, MediaType.UNKNOWN, "popular", "Popular", "aniyomi")
        )
        if (latest) out += CatalogRef(config.id, MediaType.UNKNOWN, "latest", "Latest", "aniyomi")
        out
    }

    override suspend fun getCatalog(ref: CatalogRef, page: Int): List<MediaItem> = gate {
        if (page < 1) return@gate emptyList()
        val src = source() ?: return@gate failCatalog(missingReason())
        val result = runCatching {
            when (ref.id) {
                "latest" -> src.getLatestUpdates(page)
                else -> src.getPopularAnime(page)
            }
        }
        val pageData = result.getOrElse {
            return@gate failCatalog("Home failed: ${reason(it)}", it)
        }
        if (page > 1 && !pageData.hasNextPage) return@gate emptyList()
        catalogErrors.remove(config.id)
        repairLazyPosters(src, ref, page, pageData.animes.take(MAX_ITEMS_PER_ROW).map { toItem(it) })
    }

    override suspend fun search(query: String, page: Int): List<MediaItem> = gate {
        if (query.isBlank() || page < 1) return@gate emptyList()
        val src = source() ?: return@gate failCatalog(missingReason())
        // The extension's own default filters, not an empty list: gates the
        // extension ships as defaults (Comix-style content-rating filters and
        // kin) are honoured the way the extension's own search does it.
        // MangaProvider already searches this way (see its getFilterList
        // calls); an empty AnimeFilterList silently dropped those gates.
        val filters = runCatching { src.getFilterList() }.getOrDefault(AnimeFilterList())
        val result = runCatching { src.getSearchAnime(page, query, filters) }
        val pageData = result.getOrElse {
            val why = reason(it)
            catalogErrors[config.id] = "Search failed: $why"
            lastOutcome[config.id] = "✗ $why".take(80)
            noteWall(it)
            return@gate emptyList()
        }
        // It answered — any note left by an earlier failure is stale.
        lastOutcome.remove(config.id)
        if (page > 1 && !pageData.hasNextPage) return@gate emptyList()
        pageData.animes.take(MAX_ITEMS_PER_ROW).map { toItem(it) }
    }

    private fun failCatalog(msg: String, failure: Throwable? = null): List<MediaItem> {
        catalogErrors[config.id] = msg
        lastOutcome[config.id] = "✗ ${msg.take(200)}"
        if (failure != null) noteWall(failure)
        return emptyList()
    }

    private fun repairLazyPosters(
        src: AnimeSource,
        ref: CatalogRef,
        page: Int,
        items: List<MediaItem>,
    ): List<MediaItem> {
        if (items.isEmpty()) return items
        if (items.count { it.posterUrl.isNullOrBlank() } < (items.size + 1) / 2) return items
        val catalogUrl = runCatching {
            val name = if (ref.id == "latest") "latestUpdatesRequest" else "popularAnimeRequest"
            var c: Class<*>? = src.javaClass
            var m: java.lang.reflect.Method? = null
            while (c != null && m == null) {
                m = runCatching {
                    c.getDeclaredMethod(name, Int::class.javaPrimitiveType)
                }.getOrNull()
                c = c.superclass
            }
            m ?: return items
            m.isAccessible = true
            ((m.invoke(src, page) as? okhttp3.Request)?.url?.toString())
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return items
        val html = com.hikari.app.net.Http.getString(catalogUrl) ?: return items
        val doc = runCatching { org.jsoup.Jsoup.parse(html, catalogUrl) }.getOrNull() ?: return items
        val byUrl = HashMap<String, String>()
        for (el in doc.select("article.item, div.items article, div.movies-list article, .items .item")) {
            val img = el.selectFirst(".poster img, img") ?: continue
            var poster = img.attr("abs:data-src").trim()
            if (poster.isBlank() || poster.startsWith("data:")) poster = img.attr("abs:data-lazy-src").trim()
            if (poster.isBlank() || poster.startsWith("data:")) poster = img.attr("abs:data-original").trim()
            if (poster.isBlank() || poster.startsWith("data:")) {
                val srcAttr = img.attr("abs:src").trim()
                poster = if (srcAttr.startsWith("data:")) "" else srcAttr
            }
            if (poster.isBlank() || poster.startsWith("data:")) continue
            val href = (el.selectFirst(".poster a[href]") ?: el.selectFirst(".data h3 a[href]")
                ?: el.selectFirst("a[href]"))?.attr("abs:href")?.trim().orEmpty()
            if (href.isBlank()) continue
            byUrl[href] = poster
            byUrl[href.trimEnd('/')] = poster
        }
        if (byUrl.isEmpty()) return items
        var filled = 0
        val out = items.map { item ->
            if (!item.posterUrl.isNullOrBlank()) return@map item
            val hit = byUrl[item.id] ?: byUrl[item.id.trimEnd('/')]
                ?: byUrl.entries.firstOrNull { (k, _) ->
                    k.endsWith(item.id.trimStart('/')) || item.id.endsWith(k.substringAfter("://").substringAfter("/"))
                }?.value
            if (hit.isNullOrBlank()) item
            else {
                filled++
                recordArt(hit)
                item.copy(posterUrl = hit)
            }
        }
        if (filled > 0) lastOutcome[config.id] = "✓ ${items.size} title(s) ($filled posters repaired)"
        return out
    }

    private fun noteWall(t: Throwable) = noteWall(t.message.orEmpty())

    /** [noteWall] for a failure already reduced to text (the stream path keeps
     *  its reason as a string, not a throwable). */
    private fun noteWall(text: String) {
        if (text.isBlank()) return
        if (!WALL_MESSAGE.containsMatchIn(text)) return
        val site = runCatching {
            com.hikari.app.aniyomi.AniyomiExtensionManager.siteUrlOf(config)
        }.getOrNull()
        if (!site.isNullOrBlank()) com.hikari.app.net.CloudflareVerifier.markBlocked(site)
    }

    /** A wall's own words. Deliberately not "any 4xx": a 404 is a missing page,
     *  not a challenge the user can pass. */
    private val WALL_MESSAGE = Regex(
        "\\b(403|429|503)\\b|cloudflare|just a moment|one moment, please|wsidchk" +
            "|verify you are human|ddos",
        RegexOption.IGNORE_CASE,
    )

    private fun toItem(anime: SAnime): MediaItem {
        val id = runCatching { anime.url }.getOrDefault("")
        lockedPut(animeCache, id, anime)
        val item = MediaItem(
            providerId = config.id,
            id = id,
            title = runCatching { anime.title }.getOrDefault("").ifBlank { id },
            // UNKNOWN until the episode list is known. An Aniyomi source has
            // no "movie" concept at catalog time — a film is an anime with
            // one episode — and claiming SERIES here is what painted the
            // false "Series" chip on every single-video grid row (films,
            // JAV, one-shots) and opened every one of them onto an Episodes
            // page. UNKNOWN shows no chip (see PosterStyle) and the detail
            // page corrects it the moment the list lands (one row = movie,
            // two or more = series — see [screenTypeFor]); the episode
            // lookup treats UNKNOWN exactly like SERIES (see
            // ContentRepository.episodesForInner), so no list is ever lost.
            type = MediaType.UNKNOWN,
            posterUrl = artUrl(runCatching { anime.thumbnail_url }.getOrNull()),
            overview = runCatching { anime.description }.getOrNull()?.takeIf { it.isNotBlank() },
            genres = runCatching { anime.getGenres() }.getOrNull().orEmpty(),
            backdropUrl = artUrl(runCatching { anime.background_url }.getOrNull()),
            rawType = "aniyomi",
        )
        return item
    }

    @Volatile
    private var artSite: String? = null

    private fun artSite(): String? {
        artSite?.let { return it }
        return runCatching { AniyomiExtensionManager.siteUrlOf(config)?.trim()?.trimEnd('/') }
            .getOrNull()?.takeIf { it.isNotBlank() }?.also { artSite = it }
    }

    private fun artUrl(url: String?): String? {
        val u = url?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (u.startsWith("data:")) return null
        if (u.startsWith("http://") || u.startsWith("https://") ||
            u.startsWith("file://") || u.startsWith("content://")
        ) return u.also { recordArt(it) }
        val base = artSite() ?: return u
        return (if (u.startsWith("/")) base + u else base + "/" + u).also { recordArt(it) }
    }

    private fun recordArt(url: String) {
        if (!url.startsWith("http")) return
        val base = artSite() ?: return
        runCatching {
            val host = java.net.URI(url).host?.lowercase() ?: return@runCatching
            com.hikari.app.cs3.Cs3MainApiProvider.imageHeaders[url] =
                mapOf("Referer" to base + "/", "User-Agent" to Http.UA)
            com.hikari.app.cs3.Cs3MainApiProvider.imageHostReferers[host] = base + "/"
        }
    }

    /** A cached `SAnime` for [item], or a minimal stub carrying its url — enough
     *  for the source's own `animeDetailsRequest(baseUrl + url)` to work. */
    private fun animeFor(item: MediaItem): SAnime {
        lockedGet(animeCache, item.id)?.let { return it }
        val anime = SAnimeImpl()
        anime.url = item.id
        anime.title = item.title
        anime.thumbnail_url = item.posterUrl
        anime.description = item.overview
        lockedPut(animeCache, item.id, anime)
        return anime
    }

    // ---- Details + episodes ----

    private fun combinedSupported(src: AnimeSource): Boolean =
        combinedCalls.getOrPut(config.id) {
            runCatching {
                src.javaClass.methods.any { m ->
                    m.name == "getAnimeEpisodeUpdate" &&
                        m.parameterTypes.size == 4 &&
                        m.declaringClass != AnimeSource::class.java
                }
            }.getOrDefault(false)
        }

    override suspend fun getMeta(item: MediaItem): MediaItem = gate {
        lockedGet(metaByAnime, item.id) ?: metaLocked(item)
    }

    private suspend fun metaLocked(item: MediaItem): MediaItem {
        val src = source() ?: return item
        val anime = animeFor(item)
        var detailed: SAnime? = null
        var episodes: List<Episode> = emptyList()
        if (combinedSupported(src)) {
            val update = runCatching {
                src.getAnimeEpisodeUpdate(anime, emptyList(), true, true)
            }.getOrNull()
            if (update != null) {
                detailed = update.anime
                episodes = storeEpisodes(update.episodes, item.id)
            }
        }
        if (detailed == null) {
            detailed = runCatching { src.getAnimeDetails(anime) }.getOrNull()
        }
        val full = detailed ?: anime
        if (full !== anime) lockedPut(animeCache, item.id, full)
        if (episodes.isEmpty()) {
            // Nothing from the combined call: the legacy list (or the combined
            // one, if this source does not implement the old API at all).
            episodes = episodesLocked(src, anime, item.id, full)
        }

        val type = screenTypeFor(item, episodes.size).takeUnless { episodes.isEmpty() }
            ?: item.type
        val out = MediaItem(
            providerId = item.providerId,
            id = item.id,
            title = runCatching { full.title }.getOrNull()?.takeIf { it.isNotBlank() } ?: item.title,
            type = type,
            posterUrl = runCatching { full.thumbnail_url }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: item.posterUrl,
            year = item.year,
            overview = runCatching { full.description }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: item.overview,
            genres = runCatching { full.getGenres() }.getOrNull()?.takeIf { it.isNotEmpty() }
                ?: item.genres,
            backdropUrl = runCatching { full.background_url }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: item.backdropUrl,
            rawType = item.rawType,
        )
        lockedPut(metaByAnime, item.id, out)
        return out
    }

    override suspend fun getEpisodes(item: MediaItem): List<Episode>? = gate {
        if (item.type == MediaType.MOVIE) return@gate null
        lockedGet(episodesByAnime, item.id)?.takeIf { it.isNotEmpty() }?.let { return@gate it }
        val src = source() ?: return@gate null
        val anime = animeFor(item)
        episodesLocked(src, anime, item.id).takeIf { it.isNotEmpty() }
    }

    private suspend fun episodesLocked(
        src: AnimeSource,
        anime: SAnime,
        animeId: String,
        preDetailed: SAnime? = null,
    ): List<Episode> {
        lockedGet(episodesByAnime, animeId)?.let { return it }
        val missAt = lockedGet(episodesMissAt, animeId)
        if (missAt != null && System.currentTimeMillis() - missAt < EPISODE_MISS_TTL_MS) {
            return emptyList()
        }
        var why: Throwable? = null
        var raw = fetchEpisodeList(src, preDetailed ?: anime) { why = it }
        if (raw.isEmpty() && preDetailed == null) {
            val detailed = runCatching { src.getAnimeDetails(anime) }.getOrNull()
            if (detailed != null && detailed !== anime) {
                lockedPut(animeCache, animeId, detailed)
                raw = fetchEpisodeList(src, detailed) { why = it }
            }
        }
        if (raw.isEmpty()) {
            synchronized(episodesMissAt) { episodesMissAt[animeId] = System.currentTimeMillis() }
            AniyomiExtensionManager.recordError(
                "Could not list this title's episodes",
                why,
            )
            return emptyList()
        }
        return storeEpisodes(raw, animeId)
    }

    /** Converts the source's episodes into Hikari's and files them. Shared by
     *  the combined call and the legacy list, so both cache identically. */
    private fun storeEpisodes(raw: List<SEpisode>, animeId: String): List<Episode> {
        if (raw.isEmpty()) return emptyList()
        synchronized(episodesMissAt) { episodesMissAt.remove(animeId) }
        val out = ArrayList<Episode>(minOf(raw.size, MAX_EPISODES))
        raw.take(MAX_EPISODES).forEachIndexed { index, ep ->
            val id = runCatching { ep.url }.getOrDefault("")
            if (id.isBlank()) return@forEachIndexed
            lockedPut(episodeCache, id, ep)
            val number = runCatching { ep.episode_number }.getOrDefault(-1f)
            out += Episode(
                number = if (number > 0f) number.toInt().coerceAtLeast(1) else index + 1,
                id = id,
                name = runCatching { ep.name }.getOrNull()?.takeIf { it.isNotBlank() },
                image = runCatching { ep.preview_url }.getOrNull()?.takeIf { it.isNotBlank() },
                season = 1,
            )
        }
        lockedPut(episodesByAnime, animeId, out)
        return out
    }

    private suspend fun fetchEpisodeList(
        src: AnimeSource,
        anime: SAnime,
        onFailure: (Throwable) -> Unit,
    ): List<SEpisode> {
        val combined: suspend () -> List<SEpisode> =
            { src.getAnimeEpisodeUpdate(anime, emptyList(), false, true).episodes }
        val legacy: suspend () -> List<SEpisode> = { src.getEpisodeList(anime) }
        val attempts = if (combinedSupported(src)) listOf(combined, legacy)
        else listOf(legacy, combined)
        for (attempt in attempts) {
            val got = runCatching { attempt() }.onFailure(onFailure).getOrDefault(emptyList())
            if (got.isNotEmpty()) return got
        }
        return emptyList()
    }

    private fun episodeFor(episode: Episode): SEpisode {
        lockedGet(episodeCache, episode.id)?.let { return it }
        val se = SEpisodeImpl()
        se.url = episode.id
        se.name = episode.name.orEmpty()
        se.episode_number = episode.number.toFloat()
        lockedPut(episodeCache, episode.id, se)
        return se
    }

    // ---- Streams ----

    override suspend fun getStreams(item: MediaItem, episode: Episode?): List<StreamSource> =
        gate(ProviderGate.Lane.BACKGROUND) { streamsLocked(item, episode) }

    private suspend fun streamsLocked(item: MediaItem, episode: Episode?): List<StreamSource> {
        val startedAt = System.currentTimeMillis()
        if (!extFile.exists()) {
            return fail("✗ Extension file missing — reinstall this extension.")
        }
        val src = source() ?: return fail("✗ ${missingReason()}")
        // An Aniyomi source has no "movie" concept — a film is an anime with a
        // single episode — so an item opened with no episode chosen plays its
        // FIRST episode, exactly like the Aniyomi player does. When even that
        // list will not answer (a lone-video page — a film, a JAV release —
        // whose list parser broke, or a runtime that missed), the title page
        // itself is asked for its videos directly (AnymeX parity: a film is
        // just an anime with episodes, and the title id is the last resort).
        // A lone video page answers with its own video; a series page answers
        // nothing useful (its videos live behind per-episode pages), so a
        // genuine series keeps its honest error below.
        val chosen = episode ?: runCatching {
            episodesLocked(src, animeFor(item), item.id).firstOrNull()
        }.getOrNull()
        val se = if (chosen != null) {
            episodeFor(chosen)
        } else {
            SEpisodeImpl().apply {
                url = item.id
                name = item.title
                episode_number = 1f
            }.also { lockedPut(episodeCache, item.id, it) }
        }
        val videos = loadVideos(src, se)
        if (videos.isEmpty()) {
            return fail(
                AniyomiExtensionManager.lastError?.let { "✗ $it" }
                    ?: "✗ No playable sources for this episode."
            )
        }
        val out = ArrayList<StreamSource>(videos.size)
        for ((hosterName, video) in videos) {
            toStreamSource(src, hosterName, video)?.let { out += it }
        }
        if (out.isEmpty()) {
            return fail("✗ Found ${videos.size} links but none playable.")
        }
        streamErrors.remove(config.id)
        lastOutcome[config.id] = "✓ ${out.size} source${if (out.size == 1) "" else "s"} in " +
            "${(System.currentTimeMillis() - startedAt) / 1000}s"
        val distinct = out.distinctBy { it.url }
        com.hikari.app.net.StreamProbe.warmAsync(distinct)
        return distinct
    }

    private suspend fun loadVideos(src: AnimeSource, episode: SEpisode): List<Pair<String, Video>> {
        val http = src as? AnimeHttpSource
        if (http == null) {
            // Not an HTTP source (a local/other implementation): only the video
            // list API exists for it.
            val videos = runCatching { src.getVideoList(episode) }.getOrElse {
                AniyomiExtensionManager.recordError("getVideoList failed", it)
                return emptyList()
            }
            return videos.map { "" to it }
        }
        ensureHttpServer(http)

        val hosters: List<Hoster> = if (hasHosters(http)) {
            runCatching { http.getHosterList(episode) }.getOrElse {
                AniyomiExtensionManager.recordError("getHosterList failed", it)
                return emptyList()
            }.let { list -> runCatching { with(http) { list.sortHosters() } }.getOrDefault(list) }
        } else {
            runCatching { http.getVideoList(episode) }.getOrElse {
                AniyomiExtensionManager.recordError("getVideoList failed", it)
                return emptyList()
            }.let { list -> runCatching { with(http) { list.sortVideos() } }.getOrDefault(list) }
                .toHosterList()
        }
        if (hosters.isEmpty()) return emptyList()

        val perHoster: List<List<Pair<String, Video>>> = supervisorScope {
            hosters.map { hoster ->
                async(Dispatchers.IO) {
                    withTimeoutOrNull(HOSTER_TIMEOUT_MS) {
                        runCatching { videosForHoster(http, hoster) }.getOrDefault(emptyList())
                    } ?: emptyList()
                }
            }.awaitAll()
        }
        val out = ArrayList<Pair<String, Video>>()
        var failed = 0
        for (list in perHoster) {
            if (list.isEmpty()) failed++
            out += list
        }
        if (out.isEmpty()) {
            val direct = runCatching { http.getVideoList(episode) }.getOrElse {
                AniyomiExtensionManager.recordError(
                    if (failed > 0) "all $failed hoster(s) failed and getVideoList(episode) failed"
                    else "getVideoList(episode) fallback failed",
                    it,
                )
                emptyList()
            }.let { list -> runCatching { with(http) { list.sortVideos() } }.getOrDefault(list) }
            for (video in parseVideoUrls(http, direct)) {
                if (video.videoUrl.isBlank()) continue
                out += "" to rewriteLocal(video)
            }
            if (out.isEmpty()) {
                AniyomiExtensionManager.recordError(
                    "no videos from ${hosters.size} hoster(s)",
                    null,
                )
            }
        }
        return out
    }

    private suspend fun videosForHoster(http: AnimeHttpSource, hoster: Hoster): List<Pair<String, Video>> {
        val fromHoster = hoster.videoList
        val videos = if (fromHoster != null) {
            val parsed = if (fromHoster.any { isUnresolved(it.videoUrl) }) {
                parseVideoUrls(http, fromHoster)
            } else fromHoster
            runCatching { with(http) { parsed.sortVideos() } }.getOrDefault(parsed)
        } else {
            runCatching { http.getVideoList(hoster) }.getOrElse { return emptyList() }
                .let { list -> runCatching { with(http) { list.sortVideos() } }.getOrDefault(list) }
                .let { parseVideoUrls(http, it) }
        }
        val name = hoster.hosterName.takeIf { it.isNotBlank() && it != Hoster.NO_HOSTER_LIST }.orEmpty()
        val out = ArrayList<Pair<String, Video>>(videos.size)
        for (video in videos) {
            if (video.videoUrl.isBlank()) continue
            out += name to rewriteLocal(video)
        }
        return out
    }

    private fun isUnresolved(url: String): Boolean {
        val u = url.trim()
        return u.equals("null", true) || u.equals("undefined", true) || u.equals("none", true)
    }

    private suspend fun parseVideoUrls(http: AnimeHttpSource, videos: List<Video>): List<Video> =
        videos.map { video ->
            val current = video.videoUrl.trim()
            if (current.isNotEmpty() && !isUnresolved(current)) return@map video
            val url = runCatching { http.getVideoUrl(video) }.getOrNull()
            if (url.isNullOrBlank() || isUnresolved(url)) video else video.copy(videoUrl = url)
        }

    private fun hasHosters(source: AnimeHttpSource): Boolean = runCatching {
        var current: Class<*>? = source.javaClass
        while (current != null) {
            if (current == ParsedAnimeHttpSource::class.java ||
                current == AnimeHttpSource::class.java ||
                current == AnimeSource::class.java
            ) return@runCatching false
            if (current.declaredMethods.any {
                    it.name == "getHosterList" || it.name == "hosterListRequest" || it.name == "hosterListParse"
                }
            ) return@runCatching true
            current = current.superclass
        }
        false
    }.getOrDefault(false)

    /** One Aniyomi [Video] → a Hikari [StreamSource], cached unresolved for the
     *  tap ([resolveForPlay]). Only truly blank urls are dropped: an
     *  unresolvable-looking video stays on the list and gets its bounded
     *  resolve on tap, like Yomi's QUEUE rows. */
    private fun toStreamSource(
        src: AnimeSource,
        hosterName: String,
        video: Video,
    ): StreamSource? {
        val raw = video.videoUrl
        if (raw.isBlank()) return null
        playCache[playKey(raw) + "\n" + hosterName + "\n" + video.videoTitle] = hosterName to video

        val headers = LinkedHashMap<String, String>()
        video.headers?.toMultimap()?.forEach { (k, values) ->
            val v = values.firstOrNull()?.filter { it.code < 128 }.orEmpty()
            if (v.isNotBlank()) headers.putIfAbsent(k, v)
        }
        headers.putIfAbsent("User-Agent", Http.UA)

        val subs = video.subtitleTracks.mapNotNull { track ->
            track.url.takeIf { it.isNotBlank() }?.let {
                SubtitleSource(track.lang.ifBlank { "Sub" }, it)
            }
        }

        val title = video.videoTitle.ifBlank { video.resolution?.let { "${it}p" }.orEmpty() }
        val name = listOf(hosterName, title)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
            .ifBlank { config.name }

        val isTorrent = raw.startsWith("magnet:", true) || raw.startsWith("torrent:", true)
        val url = if (isTorrent || raw.startsWith("data:")) raw else Http.normalizeDriveUrl(raw)
        return StreamSource(
            name = name,
            url = url,
            headers = headers,
            subtitles = subs,
            isTorrent = isTorrent,
            isM3u8 = raw.contains(".m3u8", true),
            isMpd = raw.contains(".mpd", true),
        )
    }

    /** One short, human-readable line for a failed extension call. */
    private fun reason(t: Throwable): String = when (t) {
        is AbstractMethodError, is NoSuchMethodError, is NoClassDefFoundError ->
            "this extension was built for a different Aniyomi version (${t.javaClass.simpleName})"
        is UnsupportedOperationException, is IllegalStateException ->
            t.message?.take(120)?.takeIf { it.isNotBlank() } ?: "the extension doesn't support this"
        else -> "${t.javaClass.simpleName}: ${t.message}".take(140)
    }

    private suspend fun <T> gate(
        lane: ProviderGate.Lane = ProviderGate.Lane.INTERACTIVE,
        block: suspend () -> T,
    ): T = ProviderGate.withProvider(config.id, lane) {
        withContext(Dispatchers.IO) { block() }
    }
}
