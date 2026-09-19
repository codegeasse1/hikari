package com.hikari.app.aniyomi

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
import com.hikari.app.providers.ProviderGate
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Hoster.Companion.toHosterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SAnimeImpl
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.SEpisodeImpl
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Adapts ONE source of an installed **Aniyomi** extension to Hikari's
 * [ContentProvider] contract — the same shape [com.hikari.app.skystream.SkyStreamProvider]
 * gives a SkyStream plugin, but speaking Aniyomi's `AnimeSource` API:
 *
 *   catalogs()             <- "Popular", plus "Latest" when the source supports it
 *   getCatalog(ref, page)  <- getPopularAnime(page) / getLatestUpdates(page)
 *   search(query, page)    <- getSearchAnime(page, query, filters)
 *   getMeta() / getEpisodes() <- getAnimeEpisodeUpdate(...) (with a fallback to
 *                              the older getAnimeDetails()/getEpisodeList())
 *   getStreams()           <- getHosterList → getVideoList(hoster) → resolveVideo
 *                              (or, for an extensions-lib 14 source, straight to
 *                              getVideoList(episode)), exactly the walk the
 *                              Aniyomi player itself does (EpisodeLoader).
 *
 * An Aniyomi source hands out RELATIVE urls — `SAnime.url` and `SEpisode.url` are
 * the site path, and the source's own `animeDetailsRequest` prepends its
 * `baseUrl`. So both are carried through Hikari as an opaque token in
 * [MediaItem.id]/[Episode.id], and the live `SAnime`/`SEpisode` objects are
 * cached so a meta → episodes → streams round trip never rebuilds them by hand.
 *
 * Extensions are third-party code built against several versions of
 * extensions-lib: every single call is wrapped, and an `AbstractMethodError`
 * from a method an older lib never declared surfaces as a readable line in
 * [streamErrors] instead of an unexplained empty server list.
 */
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
        private const val MAX_HOSTERS = 8
        private const val MAX_VIDEOS_PER_HOSTER = 12
        private const val MAX_STREAMS = 60
    }

    /** `aniyomi|<packageName>|<sourceIndex>` → the extension it belongs to. */
    private val pkgName: String get() = AniyomiExtensionManager.packageOf(config)

    private val sourceIndex: Int get() = AniyomiExtensionManager.indexOf(config)

    /**
     * The extension's private `.ext` file. `config.url` holds the absolute path
     * captured at install time; if that no longer resolves (a restored backup, a
     * moved data dir) fall back to the canonical location under `filesDir` —
     * reporting an extension as broken while its file sits right there under the
     * name it was installed with is never the right answer.
     */
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

    private fun fail(msg: String): List<StreamSource> {
        streamErrors[config.id] = msg
        lastOutcome[config.id] = msg.take(80)
        return emptyList()
    }

    private fun missingReason(): String =
        AniyomiExtensionManager.lastError
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

    /**
     * `MediaItem.id -> the time an episode fetch came back EMPTY`.
     *
     * An empty episode list is never cached as an answer ([episodesByAnime] only
     * ever holds a non-empty list), because it is almost always a TRANSIENT
     * failure: the site was slow, the extension's request hit a wall, or our
     * budget ran out. Remembering it for the life of the process is how a series
     * the extension plainly carries sat on "Episodes (0) — no episode list
     * available" until the app was restarted, and (because the episode is what
     * the server lookup maps onto) also produced NO servers from that extension.
     * The short TTL only stops a burst of callers from re-asking in the same
     * second.
     */
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
            return@gate failCatalog("Home failed: ${reason(it)}")
        }
        if (page > 1 && !pageData.hasNextPage) return@gate emptyList()
        catalogErrors.remove(config.id)
        pageData.animes.take(MAX_ITEMS_PER_ROW).map { toItem(it) }
    }

    override suspend fun search(query: String, page: Int): List<MediaItem> = gate {
        if (query.isBlank() || page < 1) return@gate emptyList()
        val src = source() ?: return@gate failCatalog(missingReason())
        val result = runCatching { src.getSearchAnime(page, query, AnimeFilterList()) }
        val pageData = result.getOrElse {
            val why = reason(it)
            catalogErrors[config.id] = "Search failed: $why"
            lastOutcome[config.id] = "✗ $why".take(80)
            return@gate emptyList()
        }
        // It answered — any note left by an earlier failure is stale.
        lastOutcome.remove(config.id)
        if (page > 1 && !pageData.hasNextPage) return@gate emptyList()
        pageData.animes.take(MAX_ITEMS_PER_ROW).map { toItem(it) }
    }

    private fun failCatalog(msg: String): List<MediaItem> {
        catalogErrors[config.id] = msg
        lastOutcome[config.id] = "✗ ${msg.take(72)}"
        return emptyList()
    }

    private fun toItem(anime: SAnime): MediaItem {
        val id = runCatching { anime.url }.getOrDefault("")
        lockedPut(animeCache, id, anime)
        val item = MediaItem(
            providerId = config.id,
            id = id,
            title = runCatching { anime.title }.getOrDefault("").ifBlank { id },
            // ALWAYS a series, never UNKNOWN. An Aniyomi source has no "movie"
            // concept — a film is an anime with one episode — and UNKNOWN is not
            // a neutral value in this app: `ContentRepository.episodesFor`
            // returns null outright for an UNKNOWN item, so every title picked
            // out of an Aniyomi catalogue came up with no episode list at all
            // and (when its meta fetch also timed out, leaving the type
            // untouched) played as though it were a film. Reporting the type the
            // source really is keeps the episode grid, the S1E5 the user picked
            // and the cross-extension episode match all working.
            type = MediaType.SERIES,
            posterUrl = runCatching { anime.thumbnail_url }.getOrNull()?.takeIf { it.isNotBlank() },
            overview = runCatching { anime.description }.getOrNull()?.takeIf { it.isNotBlank() },
            genres = runCatching { anime.getGenres() }.getOrNull().orEmpty(),
            backdropUrl = runCatching { anime.background_url }.getOrNull()?.takeIf { it.isNotBlank() },
            rawType = "aniyomi",
        )
        return item
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

    override suspend fun getMeta(item: MediaItem): MediaItem = gate {
        lockedGet(metaByAnime, item.id) ?: metaLocked(item)
    }

    private suspend fun metaLocked(item: MediaItem): MediaItem {
        val src = source() ?: return item
        val anime = animeFor(item)
        val episodes = episodesLocked(src, anime, item.id)
        val detailed = runCatching {
            src.getAnimeEpisodeUpdate(anime, emptyList(), true, false).anime
        }.getOrNull()
            ?: runCatching { src.getAnimeDetails(anime) }.getOrNull()
            ?: anime
        if (detailed !== anime) lockedPut(animeCache, item.id, detailed)

        // Deliberately NOT "MOVIE when the episode list is empty": an Aniyomi
        // source is an anime source either way, and a slow extension whose
        // episode list did not answer yet is not evidence that the title is a
        // film. Reclassifying it that way is what hid the episode grid, made the
        // Play button skip the episode the user had chosen, and told the cross
        // pass there was no S1E5 to match.
        val type = MediaType.SERIES
        val out = MediaItem(
            providerId = item.providerId,
            id = item.id,
            title = runCatching { detailed.title }.getOrNull()?.takeIf { it.isNotBlank() } ?: item.title,
            type = type,
            posterUrl = runCatching { detailed.thumbnail_url }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: item.posterUrl,
            year = item.year,
            overview = runCatching { detailed.description }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: item.overview,
            genres = runCatching { detailed.getGenres() }.getOrNull()?.takeIf { it.isNotEmpty() }
                ?: item.genres,
            backdropUrl = runCatching { detailed.background_url }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: item.backdropUrl,
            rawType = item.rawType,
        )
        lockedPut(metaByAnime, item.id, out)
        return out
    }

    override suspend fun getEpisodes(item: MediaItem): List<Episode>? = gate {
        lockedGet(episodesByAnime, item.id)?.takeIf { it.isNotEmpty() }?.let { return@gate it }
        val src = source() ?: return@gate null
        val anime = animeFor(item)
        episodesLocked(src, anime, item.id).takeIf { it.isNotEmpty() }
    }

    /**
     * The episode list for [anime] — the extensions-lib 17 combined call first,
     * then the older `getEpisodeList` an extensions-lib 14/16 source implements
     * (the vendored `AnimeHttpSource` is what actually performs that fetch).
     * Returns an empty list (never throws) so a source that can't answer is a
     * blank episode list rather than a crashed detail page.
     *
     * Every shape of the API is tried before giving up, and an EMPTY answer is
     * never kept ([episodesMissAt]): the three call shapes exist on every source
     * (the vendored base class supplies defaults that throw) and only ONE of them
     * is the one the extension actually implements, so a source whose
     * `getEpisodeList` is a stub still answers through the combined call — and a
     * source whose combined call is a stub still answers through
     * `getEpisodeList`. A source that needs its DETAILS fetched before it can
     * list episodes gets that too, because that is what Aniyomi's own
     * `EpisodeLoader` does when a list comes back empty.
     */
    private suspend fun episodesLocked(
        src: AnimeSource,
        anime: SAnime,
        animeId: String,
    ): List<Episode> {
        lockedGet(episodesByAnime, animeId)?.let { return it }
        val missAt = lockedGet(episodesMissAt, animeId)
        if (missAt != null && System.currentTimeMillis() - missAt < EPISODE_MISS_TTL_MS) {
            return emptyList()
        }
        var why: Throwable? = null
        var raw = fetchEpisodeList(src, anime) { why = it }
        if (raw.isEmpty()) {
            // Some sources cannot list a title they have not DETAILED yet: their
            // episode parse reads a field (an id, a slug, a "seasons" block) that
            // only their details call fills in. Ask for the details and try again
            // with the richer object — and keep it, since everything else about
            // this title benefits from the fuller metadata too.
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

    /**
     * The episode list through every call shape the source API offers, in the
     * order most likely to be implemented: the extensions-lib 17 combined call
     * (episodes only), then the plain [AnimeSource.getEpisodeList] a 14/16 source
     * implements, then the combined call with details. A shape the source does
     * not implement throws `UnsupportedOperationException` (the vendored base
     * class's default) and costs nothing; the first non-empty answer wins.
     */
    private suspend fun fetchEpisodeList(
        src: AnimeSource,
        anime: SAnime,
        onFailure: (Throwable) -> Unit,
    ): List<SEpisode> {
        val attempts: List<suspend () -> List<SEpisode>> = listOf(
            { src.getAnimeEpisodeUpdate(anime, emptyList(), false, true).episodes },
            { src.getEpisodeList(anime) },
            { src.getAnimeEpisodeUpdate(anime, emptyList(), true, true).episodes },
        )
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
        gate { streamsLocked(item, episode) }

    private suspend fun streamsLocked(item: MediaItem, episode: Episode?): List<StreamSource> {
        val startedAt = System.currentTimeMillis()
        if (!extFile.exists()) {
            return fail("✗ Extension file missing — reinstall this extension.")
        }
        val src = source() ?: return fail("✗ ${missingReason()}")
        // An Aniyomi source has no "movie" concept — a film is an anime with a
        // single episode — so an item opened with no episode chosen plays its
        // FIRST episode, exactly like the Aniyomi player does.
        val chosen = episode ?: runCatching {
            episodesLocked(src, animeFor(item), item.id).firstOrNull()
        }.getOrNull()
            ?: return fail("✗ This title has no playable episode.")

        val se = episodeFor(chosen)
        val videos = loadVideos(src, se)
        if (videos.isEmpty()) {
            return fail(
                AniyomiExtensionManager.lastError?.let { "✗ $it" }
                    ?: "✗ No playable sources for this episode."
            )
        }
        val out = ArrayList<StreamSource>(videos.size)
        for ((hosterName, video) in videos) {
            if (out.size >= MAX_STREAMS) break
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

    /**
     * Every video the source can produce for [episode], paired with the hoster
     * it came from — the same walk Aniyomi's own `EpisodeLoader` performs:
     *
     *  - a source that declares hoster support (its class overrides
     *    `getHosterList`/`hosterListRequest`/`hosterListParse`) is asked for its
     *    hoster list, and each hoster for its videos;
     *  - an extensions-lib 14 source (no hoster API at all) is asked straight
     *    for its video list, wrapped as one pseudo-hoster.
     *
     * Hoster/video ordering is left to the source's own `sortHosters`/
     * `sortVideos`, which is what Aniyomi does (and what makes VidCloud-style
     * mirrors come first). Those are `protected` in some extensions-lib builds,
     * so a refusal degrades to the unsorted list rather than losing the results.
     */
    private suspend fun loadVideos(src: AnimeSource, episode: SEpisode): List<Pair<String, Video>> {
        val http = src as? AnimeHttpSource
        if (http == null) {
            // Not an HTTP source (a local/other implementation): only the video
            // list API exists for it.
            val videos = runCatching { src.getVideoList(episode) }.getOrElse {
                AniyomiExtensionManager.recordError("getVideoList failed", it)
                return emptyList()
            }
            return videos.take(MAX_VIDEOS_PER_HOSTER).map { "" to it }
        }

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

        val out = ArrayList<Pair<String, Video>>()
        for (hoster in hosters.take(MAX_HOSTERS)) {
            val fromHoster = hoster.videoList
            val videos = if (fromHoster != null) {
                val parsed = if (fromHoster.any { it.videoUrl == "null" }) {
                    parseVideoUrls(http, fromHoster)
                } else fromHoster
                runCatching { with(http) { parsed.sortVideos() } }.getOrDefault(parsed)
            } else {
                runCatching { http.getVideoList(hoster) }.getOrElse {
                    AniyomiExtensionManager.recordError("getVideoList(hoster) failed", it)
                    continue
                }.let { list -> runCatching { with(http) { list.sortVideos() } }.getOrDefault(list) }
                    .let { parseVideoUrls(http, it) }
            }
            val name = hoster.hosterName.takeIf { it.isNotBlank() && it != Hoster.NO_HOSTER_LIST }.orEmpty()
            for (video in videos.take(MAX_VIDEOS_PER_HOSTER)) {
                val resolved = resolve(http, video) ?: continue
                if (resolved.videoUrl.isBlank() || resolved.videoUrl == "null") continue
                out += name to resolved
            }
        }
        return out
    }

    /**
     * `AnimeHttpSource.resolveVideo` when the source resolves lazily (ext-lib 16+;
     * the base implementation simply returns the video). A video whose url is
     * still the literal `"null"` after [parseVideoUrls] could not be resolved and
     * is dropped — but a throw never costs us a link that already works.
     */
    private suspend fun resolve(http: AnimeHttpSource, video: Video): Video? {
        if (video.initialized) return video
        val resolved = runCatching { http.resolveVideo(video) }.getOrNull()
        val out = resolved ?: return if (video.videoUrl.isNotBlank() && video.videoUrl != "null") video else null
        return runCatching { out.copy(initialized = true) }.getOrDefault(out)
    }

    /**
     * extensions-lib 14 hands out videos whose `videoUrl` is the literal
     * `"null"`, to be filled in by a second request (`getVideoUrl`). Same
     * two-step as Aniyomi's `parseVideoUrls`.
     */
    private suspend fun parseVideoUrls(http: AnimeHttpSource, videos: List<Video>): List<Video> =
        videos.map { video ->
            if (video.videoUrl != "null") return@map video
            val url = runCatching { http.getVideoUrl(video) }.getOrNull()
            if (url.isNullOrBlank()) video else video.copy(videoUrl = url)
        }

    /**
     * Whether this source has the extensions-lib 16 hoster API at all — the
     * reflection walk Aniyomi's own `EpisodeLoader.checkHasHosters` performs,
     * stopping at the library base classes (whose `getHosterList` is a
     * self-recursive stub that would recurse forever if ever called).
     */
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

    /** One resolved Aniyomi [Video] → a Hikari [StreamSource]. */
    private fun toStreamSource(
        src: AnimeSource,
        hosterName: String,
        video: Video,
    ): StreamSource? {
        val raw = video.videoUrl
        if (raw.isBlank() || raw == "null") return null

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

    /** Runs [block] inside Hikari's per-provider lock (mutex, NOT re-entrant —
     *  the private `…Locked` helpers are what the public overrides compose). */
    private suspend fun <T> gate(block: suspend () -> T): T =
        ProviderGate.withProvider(config.id) { withContext(Dispatchers.IO) { block() } }
}
