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
        // A source that failed to LOAD is not a wall, but a site answering
        // 403/503 on the episode or video call is — and that is the case the
        // user can clear with the globe button (the extension's own client
        // reuses whatever clearance the verification earns).
        noteWall(msg)
        return emptyList()
    }

    /** Why this extension's sources are unavailable, in this extension's own
     *  words.
     *
     *  The per-extension reason ([AniyomiExtensionManager.loadFailure]) comes
     *  FIRST: it is the only one that describes THIS row, and it is a real cause
     *  ("none of its sources could be loaded", "built against extensions-lib
     *  19", a class-load failure) rather than a guess about the site. The global
     *  lastError is the fallback for a reason recorded outside a load — a
     *  hoster/video call that threw later. */
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

    /**
     * Per provider: does this extension implement the extensions-lib 17
     * combined call? Asked once per process (see [combinedSupported]) — the
     * answer is a property of the installed class, not of a title.
     */
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
        lastOutcome[config.id] = "✗ ${msg.take(72)}"
        if (failure != null) noteWall(failure)
        return emptyList()
    }

    /**
     * Records the extension's OWN site as walled when a failure is a bot wall
     * rather than a broken extension — HTTP 403/429/503, or a Cloudflare /
     * "One moment, please" challenge body.
     *
     * The extension client clears these by itself where it can (see
     * [com.hikari.app.net.CloudflareSolver], wired in on
     * [eu.kanade.tachiyomi.network.NetworkHelper]'s client), and this is what
     * happens when even that did not work: the host lands in
     * [com.hikari.app.net.CloudflareVerifier], which is what lets Home offer the
     * globe ("Verify site") for exactly this extension instead of reporting it
     * as broken, and what keeps the cross-extension search from asking the same
     * walled site on every lookup.
     */
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

    /**
     * Whether this source implements the extensions-lib 17 combined call
     * (`getAnimeEpisodeUpdate`), which fetches the details AND the episodes in
     * ONE request.
     *
     * Decided by DECLARATION, never by calling it: the method is abstract in
     * [AnimeSource], so an extension built against extensions-lib 14/16 does not
     * implement it and calling it throws `AbstractMethodError` — free, but
     * indistinguishable from a real failure, and the old code compensated by
     * trying every call shape every time. A source that answered empty on the
     * first shape then walked the other two, and the "details first, then list"
     * pass walked them all again: up to seven serial requests for one episode
     * list, which on a slow extension is the reported "almost 15 seconds to load
     * the episode and everything on the detail page".
     *
     * Reflection answers it exactly, and without naming the parameter types:
     * look for a 4-arg `getAnimeEpisodeUpdate` whose [java.lang.reflect.Method.getDeclaringClass]
     * is NOT [AnimeSource] itself. A method declared by the interface means "the
     * extension never overrode it" (a 14/16 source); a method declared by the
     * extension (or a base it extends) means it is implemented. Matching by name
     * and arity rather than by exact parameter types matters: the boolean
     * parameters are `boolean` or `java.lang.Boolean` depending on whether the
     * source's Kotlin signature used `Boolean` or `Boolean?`, and a mismatch
     * would silently report "unsupported" for a source that does implement it.
     */
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
        // ONE round trip for the details AND the episodes whenever the source
        // speaks the combined API: `fetchDetails = true, fetchEpisodes = true`
        // IS "give me everything about this title". Asking for the episodes
        // first and the details after — two calls, the first of them through the
        // three-shape walk below — is exactly what the combined call exists to
        // replace, and it is what a slow Aniyomi extension was paying.
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
        lockedGet(episodesByAnime, item.id)?.takeIf { it.isNotEmpty() }?.let { return@gate it }
        val src = source() ?: return@gate null
        val anime = animeFor(item)
        episodesLocked(src, anime, item.id).takeIf { it.isNotEmpty() }
    }

    /**
     * The episode list for [anime] — the extensions-lib 17 combined call when
     * the source implements it, otherwise the older `getEpisodeList` (the
     * vendored `AnimeHttpSource` is what actually performs that fetch).
     * Returns an empty list (never throws) so a source that can't answer is a
     * blank episode list rather than a crashed detail page.
     *
     * [preDetailed] is a [SAnime] the CALLER has already enriched (the combined
     * call, or a details fetch). Some sources cannot list episodes for a title
     * they have not detailed yet — their episode parse reads a field only their
     * details call fills in — and handing the richer object straight in is what
     * removes the "empty, so fetch the details, so try again" double pass. It is
     * only used as the first attempt; the details-then-retry path below stays
     * for callers that have nothing enriched.
     */
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

    /**
     * The episode list through the call shapes this source can actually answer:
     * the shape it IMPLEMENTS first (see [combinedSupported]), the other one as
     * the single fallback. A shape the source does not implement throws
     * `UnsupportedOperationException`/`AbstractMethodError` (the vendored base
     * class's default) and costs nothing; the first non-empty answer wins and
     * ends the walk, so a source that answers costs ONE request.
     */
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
     *  the private `…Locked` helpers are what the public overrides compose).
     *
     *  [lane] is the PRIORITY: metadata and catalogs are what the user is
     *  looking at ([ProviderGate.Lane.INTERACTIVE]), a stream search is
     *  background work that must never queue in front of them
     *  ([ProviderGate.Lane.BACKGROUND]) — a stream search inside one Aniyomi
     *  extension walks up to eight hosters, so a detail page queued behind one
     *  is the reported "~15 seconds to load the episode list". */
    private suspend fun <T> gate(
        lane: ProviderGate.Lane = ProviderGate.Lane.INTERACTIVE,
        block: suspend () -> T,
    ): T = ProviderGate.withProvider(config.id, lane) {
        withContext(Dispatchers.IO) { block() }
    }
}
