package com.hikari.app.nuvio

import com.hikari.app.HikariApp
import com.hikari.app.data.CatalogRef
import com.hikari.app.data.Episode
import com.hikari.app.data.MediaItem
import com.hikari.app.data.MediaType
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.ProviderType
import com.hikari.app.data.StreamSource
import com.hikari.app.data.SubtitleSource
import com.hikari.app.net.Http
import com.hikari.app.providers.ContentProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class NuvioScraper(override val config: ProviderConfig) : ContentProvider {

    companion object {
        /** Per-provider last failure (shown on the Detail screen). */
        val streamErrors = ConcurrentHashMap<String, String>()
        /** Per-provider last lookup outcome — one short line per extension
         *  ("✓ 3 sources in 18s" or "✗ …"). Shown in the sources sheet so the
         *  user can see which extension actually failed and why, instead of
         *  one global "no sources found". */
        val lastOutcome = ConcurrentHashMap<String, String>()
        /** Per-provider catalog failure (shown on the Home empty state). */
        val catalogErrors = ConcurrentHashMap<String, String>()

        private const val IMG = "https://image.tmdb.org/t/p/w500"
        private const val IMG_L = "https://image.tmdb.org/t/p/w1280"
        private const val MAX_SEASONS = 24

        private enum class Niche { ANIME, HINDI, KDRAMA, FRENCH, PERSIAN, GENERAL }

        private class RowDef(
            val id: String,
            val type: MediaType,
            val path: String,
            val params: Map<String, String>,
            val title: String,
        )

        private val GENERAL_ROWS = listOf(
            RowDef("g0", MediaType.UNKNOWN, "/trending/all/week", emptyMap(), "Trending"),
            RowDef("g1", MediaType.MOVIE, "/movie/popular", emptyMap(), "Popular Movies"),
            RowDef("g2", MediaType.MOVIE, "/movie/top_rated", emptyMap(), "Top Rated Movies"),
            RowDef("g3", MediaType.MOVIE, "/movie/now_playing", emptyMap(), "In Cinemas"),
            RowDef("g4", MediaType.MOVIE, "/movie/upcoming", emptyMap(), "Coming Soon"),
            RowDef("g5", MediaType.SERIES, "/tv/popular", emptyMap(), "Popular Series"),
            RowDef("g6", MediaType.SERIES, "/tv/top_rated", emptyMap(), "Top Rated Series"),
            RowDef("g7", MediaType.SERIES, "/tv/airing_today", emptyMap(), "Airing Today"),
            RowDef("g8", MediaType.SERIES, "/tv/on_the_air", emptyMap(), "On TV"),
        )

        private val ANIME_ROWS = listOf(
            RowDef("a0", MediaType.SERIES, "/discover/tv", mapOf("with_genres" to "16", "with_origin_country" to "JP", "sort_by" to "popularity.desc"), "Popular Anime"),
            RowDef("a1", MediaType.SERIES, "/discover/tv", mapOf("with_genres" to "16", "with_origin_country" to "JP", "sort_by" to "vote_average.desc", "vote_count.gte" to "100"), "Top Rated Anime"),
            RowDef("a2", MediaType.SERIES, "/discover/tv", mapOf("with_genres" to "16", "with_origin_country" to "JP", "sort_by" to "first_air_date.desc"), "New Anime"),
            RowDef("a3", MediaType.MOVIE, "/discover/movie", mapOf("with_genres" to "16", "with_origin_country" to "JP", "sort_by" to "popularity.desc"), "Popular Anime Movies"),
            RowDef("a4", MediaType.MOVIE, "/discover/movie", mapOf("with_genres" to "16", "with_origin_country" to "JP", "sort_by" to "vote_average.desc", "vote_count.gte" to "100"), "Top Rated Anime Movies"),
            RowDef("a5", MediaType.MOVIE, "/discover/movie", mapOf("with_genres" to "16", "with_origin_country" to "JP", "sort_by" to "primary_release_date.desc"), "New Anime Movies"),
            // Anime movies often lack the JP origin country tag but still have
            // the ja original language — these catch the stragglers.
            RowDef("a6", MediaType.MOVIE, "/discover/movie", mapOf("with_genres" to "16", "with_original_language" to "ja", "sort_by" to "popularity.desc"), "Japanese Anime Movies"),
            RowDef("a7", MediaType.SERIES, "/discover/tv", mapOf("with_genres" to "16", "with_original_language" to "ja", "sort_by" to "popularity.desc"), "Japanese Anime Series"),
            RowDef("a8", MediaType.SERIES, "/discover/tv", mapOf("with_genres" to "16", "with_origin_country" to "JP", "sort_by" to "vote_count.desc"), "Most Discussed Anime"),
        )

        private val HINDI_ROWS = listOf(
            RowDef("h0", MediaType.MOVIE, "/discover/movie", mapOf("with_original_language" to "hi", "sort_by" to "popularity.desc"), "Popular Hindi Movies"),
            RowDef("h1", MediaType.SERIES, "/discover/tv", mapOf("with_original_language" to "hi", "sort_by" to "popularity.desc"), "Popular Hindi Series"),
            RowDef("h2", MediaType.MOVIE, "/discover/movie", mapOf("with_original_language" to "hi", "sort_by" to "vote_average.desc", "vote_count.gte" to "100"), "Top Rated Hindi Movies"),
            RowDef("h3", MediaType.SERIES, "/discover/tv", mapOf("with_original_language" to "hi", "sort_by" to "vote_average.desc", "vote_count.gte" to "100"), "Top Rated Hindi Series"),
            RowDef("h4", MediaType.MOVIE, "/discover/movie", mapOf("with_original_language" to "hi", "sort_by" to "primary_release_date.desc"), "New Hindi Movies"),
        )

        private val KDRAMA_ROWS = listOf(
            RowDef("k0", MediaType.SERIES, "/discover/tv", mapOf("with_original_language" to "ko", "sort_by" to "popularity.desc"), "Popular Korean Dramas"),
            RowDef("k1", MediaType.SERIES, "/discover/tv", mapOf("with_original_language" to "ko", "sort_by" to "vote_average.desc", "vote_count.gte" to "100"), "Top Rated Korean Dramas"),
            RowDef("k2", MediaType.SERIES, "/discover/tv", mapOf("with_original_language" to "ko", "sort_by" to "first_air_date.desc"), "New Korean Dramas"),
            RowDef("k3", MediaType.MOVIE, "/discover/movie", mapOf("with_original_language" to "ko", "sort_by" to "popularity.desc"), "Popular Korean Movies"),
        )

        private val FRENCH_ROWS = listOf(
            RowDef("f0", MediaType.MOVIE, "/discover/movie", mapOf("with_original_language" to "fr", "sort_by" to "popularity.desc"), "Popular French Movies"),
            RowDef("f1", MediaType.SERIES, "/discover/tv", mapOf("with_original_language" to "fr", "sort_by" to "popularity.desc"), "Popular French Series"),
            RowDef("f2", MediaType.MOVIE, "/discover/movie", mapOf("with_original_language" to "fr", "sort_by" to "vote_average.desc", "vote_count.gte" to "100"), "Top Rated French Movies"),
            RowDef("f3", MediaType.SERIES, "/discover/tv", mapOf("with_original_language" to "fr", "sort_by" to "vote_average.desc", "vote_count.gte" to "100"), "Top Rated French Series"),
        )

        private val PERSIAN_ROWS = listOf(
            RowDef("p0", MediaType.MOVIE, "/discover/movie", mapOf("with_original_language" to "fa", "sort_by" to "popularity.desc"), "Popular Persian Movies"),
            RowDef("p1", MediaType.SERIES, "/discover/tv", mapOf("with_original_language" to "fa", "sort_by" to "popularity.desc"), "Popular Persian Series"),
            RowDef("p2", MediaType.MOVIE, "/discover/movie", mapOf("with_original_language" to "fa", "sort_by" to "vote_average.desc", "vote_count.gte" to "100"), "Top Rated Persian Movies"),
            RowDef("p3", MediaType.SERIES, "/discover/tv", mapOf("with_original_language" to "fa", "sort_by" to "vote_average.desc", "vote_count.gte" to "100"), "Top Rated Persian Series"),
        )

        private fun nicheOf(name: String): Niche {
            val n = name.lowercase()
            return when {
                listOf("anime", "hianime", "anidb", "anikoto", "kurage", "cartoon").any { n.contains(it) } -> Niche.ANIME
                listOf(
                    "desi", "hindi", "hindmoviez", "einthusan", "gramcinema", "moonflix", "ctgmovies",
                    "hdghar", "hdhub", "moviebox", "movieblast", "movieshunt", "movies4u",
                    "vegamovies", "zinkmovie", "bollywood",
                ).any { n.contains(it) } -> Niche.HINDI
                listOf("kdrama", "kisskh").any { n.contains(it) } -> Niche.KDRAMA
                listOf("movix", "nakios", "purstream", "wiflix", "vostfr", "frenchstream", "streamingvf", "papadustream").any { n.contains(it) } -> Niche.FRENCH
                n.contains("persian") -> Niche.PERSIAN
                else -> Niche.GENERAL
            }
        }

        private fun window(pool: List<RowDef>, take: Int, offset: Int): List<RowDef> {
            val n = pool.size
            if (n <= 0) return emptyList()
            val start = (offset % n + n) % n
            return List(take) { i -> pool[(start + i) % n] }
        }
    }

    private val rows: List<RowDef> by lazy {
        val h = config.name.hashCode() and 0x7fffffff
        val niche = nicheOf(config.name)
        val ordinal = runCatching {
            runBlocking {
                HikariApp.instance.store.providers()
                    .filter { it.type == ProviderType.NUVIO && nicheOf(it.name) == niche }
                    .sortedBy { it.name.lowercase() }
                    .indexOfFirst { it.id == config.id }
            }
        }.getOrDefault(-1)
        val offset = if (ordinal >= 0) ordinal else h
        when (niche) {
            Niche.GENERAL -> window(GENERAL_ROWS, 5, offset)
            Niche.ANIME -> window(ANIME_ROWS, 4, offset)
            Niche.HINDI -> window(HINDI_ROWS, 3, offset)
            Niche.KDRAMA -> window(KDRAMA_ROWS, 3, offset)
            Niche.FRENCH -> window(FRENCH_ROWS, 3, offset)
            Niche.PERSIAN -> window(PERSIAN_ROWS, 3, offset)
        }
    }

    override suspend fun catalogs(): List<CatalogRef> = rows.map { r ->
        CatalogRef(config.id, r.type, r.id, r.title)
    }

    override suspend fun getCatalog(ref: CatalogRef, page: Int): List<MediaItem> = withContext(Dispatchers.IO) {
        val def = rows.firstOrNull { it.id == ref.id } ?: return@withContext emptyList()
        val params = LinkedHashMap(def.params)
        params["page"] = page.coerceAtLeast(1).toString()
        val data = TmdbResolver.apiGet(def.path, params)
        if (data == null) {
            catalogErrors[config.id] = "TMDB catalog unavailable right now (network or API key)."
            return@withContext emptyList()
        }
        catalogErrors.remove(config.id)
        val arr = data.optJSONArray("results") ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            fromTmdbRow(o)
        }
    }

    override suspend fun search(query: String, page: Int): List<MediaItem> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val data = TmdbResolver.apiGet(
            "/search/multi", mapOf("query" to query, "page" to page.coerceAtLeast(1).toString())
        ) ?: return@withContext emptyList()
        val arr = data.optJSONArray("results") ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val mt = o.optString("media_type")
            if (mt != "movie" && mt != "tv") return@mapNotNull null
            fromTmdbRow(o)
        }
    }

    override suspend fun getMeta(item: MediaItem): MediaItem {
        if (item.id.isBlank() || !item.id.all { it.isDigit() }) return item
        if (com.hikari.app.data.TrackerAnimeResolver.isTrackerAnime(item)) return item
        val type = if (item.type == MediaType.SERIES) "tv" else "movie"
        val d = TmdbResolver.apiGet("/$type/${item.id}", emptyMap()) ?: return item
        val year = listOf("release_date", "first_air_date")
            .firstNotNullOfOrNull { k ->
                d.optString(k).takeIf { it.isNotBlank() }?.take(4)?.toIntOrNull()
            }
        return MediaItem(
            providerId = item.providerId,
            id = item.id,
            title = d.optString("title").ifBlank { d.optString("name") }.ifBlank { item.title },
            type = item.type,
            posterUrl = d.tmdbPath("poster_path")?.let { IMG + it } ?: item.posterUrl,
            year = year ?: item.year,
            overview = d.optString("overview").ifBlank { item.overview.orEmpty() }.ifBlank { null },
            genres = (0 until (d.optJSONArray("genres")?.length() ?: 0)).mapNotNull { i ->
                d.optJSONArray("genres")?.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }
            },
            backdropUrl = d.tmdbPath("backdrop_path")?.let { IMG_L + it } ?: item.backdropUrl,
            rawType = item.rawType,
        )
    }

    override suspend fun getEpisodes(item: MediaItem): List<Episode>? = withContext(Dispatchers.IO) {
        // Movies never show Loading episodes.
        if (item.type == MediaType.MOVIE) return@withContext null
        if (item.type != MediaType.SERIES) return@withContext null
        val id = item.id
        if (id.isBlank() || !id.all { it.isDigit() }) return@withContext null
        if (com.hikari.app.data.TrackerAnimeResolver.isTrackerAnime(item)) return@withContext null
        val tv = TmdbResolver.apiGet("/tv/$id", emptyMap()) ?: return@withContext null
        val seasons = tv.optJSONArray("seasons") ?: return@withContext null
        val nums = (0 until seasons.length()).mapNotNull { i ->
            val s = seasons.optJSONObject(i) ?: return@mapNotNull null
            val n = s.optInt("season_number")
            if (n > 0 && s.optInt("episode_count") > 0) n else null
        }.take(MAX_SEASONS)
        // TMDB season endpoints are independent; fetch them concurrently so
        // long-running shows do not consume the entire detail timeout serially.
        val rows = coroutineScope {
            nums.map { sn ->
                async(Dispatchers.IO) {
                    val sd = TmdbResolver.apiGet("/tv/$id/season/$sn", emptyMap())
                        ?: return@async emptyList<TmdbEp>()
                    val eps = sd.optJSONArray("episodes")
                        ?: return@async emptyList<TmdbEp>()
                    buildList {
                        for (i in 0 until eps.length()) {
                            val e = eps.optJSONObject(i) ?: continue
                            val en = e.optInt("episode_number")
                            if (en <= 0) continue
                            add(TmdbEp(
                                season = sn,
                                number = en,
                                name = e.optString("name").takeIf { it.isNotBlank() && it != "null" },
                                image = e.tmdbPath("still_path")?.let { IMG + it },
                                air = e.optString("air_date").trim().takeIf { it.length == 10 },
                            ))
                        }
                    }
                }
            }.awaitAll().flatten()
        }
        if (rows.isEmpty()) return@withContext null

        val today = isoDay(0)
        var list = rows.filter { it.air == null || it.air <= today }
        if (list.isEmpty()) return@withContext null

        // Donghua rescue: TMDB's long-runner coverage stalls on some titles
        // (Battle Through the Heavens is frozen at its 2018 season). Safe only
        // for the single continuous season these shows use, where the row
        // numbers are the absolute episode numbers the sites actually serve.
        if (nums.size == 1 && nums[0] == 1) {
            list = mergeBangumi(item, tv, list)
        }

        list.sortedWith(compareBy({ it.season }, { it.number })).map {
            Episode(
                number = it.number,
                id = "S${it.season}E${it.number}",
                name = it.name,
                image = it.image,
                season = it.season,
            )
        }
    }

    /** One episode row as read from TMDB, plus the air date the released-only
     *  filter (and the Bangumi merge) work on. */
    private data class TmdbEp(
        val season: Int,
        val number: Int,
        val name: String?,
        val image: String?,
        val air: String?,
    )

    /** Today, shifted by [offsetDays], as the ISO date TMDB uses for `air_date`. */
    private fun isoDay(offsetDays: Int): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date(System.currentTimeMillis() + offsetDays * 86_400_000L))

    private suspend fun mergeBangumi(
        item: MediaItem,
        tv: JSONObject,
        tmdb: List<TmdbEp>,
    ): List<TmdbEp> {
        val original = tv.optString("original_name").trim().takeIf { it.isNotBlank() && it != "null" }
        val year = tv.optString("first_air_date").take(4).toIntOrNull()
        val bgm = withTimeoutOrNull(9_000) {
            runCatching { BangumiMeta.episodes(item.title, original, year) }.getOrNull()
        } ?: return tmdb
        if (bgm.isEmpty()) return tmdb
        val byNumber = bgm.associateBy { it.number }
        val grace = isoDay(1)
        val maxTmdb = tmdb.maxOfOrNull { it.number } ?: 0
        val maxBgm = bgm.filter { it.airDate != null && it.airDate <= grace }
            .maxOfOrNull { it.number } ?: 0

        var out = tmdb
        // TMDB behind (or missing a whole season) → take Bangumi's tail. The
        // cap keeps a bad match from inventing hundreds of episodes.
        if (maxBgm > maxTmdb && maxBgm - maxTmdb <= 400) {
            val have = out.mapTo(HashSet()) { it.number }
            val extra = (maxTmdb + 1..maxBgm).mapNotNull { n ->
                if (n in have) return@mapNotNull null
                val b = byNumber[n] ?: return@mapNotNull null
                TmdbEp(season = 1, number = n, name = b.name, image = null, air = b.airDate)
            }
            if (extra.isNotEmpty()) out = out + extra
        }
        return out
    }

    /** Reads a TMDB image path, treating JSON null / "" / "null" as absent.
     *  (org.json's optString returns the literal "null" for a JSON null, which
     *  would otherwise produce broken URLs like "…/w500null" → HTTP 404.) */
    private fun JSONObject.tmdbPath(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).trim().takeIf { it.isNotBlank() && it != "null" }
    }

    /** Maps a TMDB result object (movie/tv/trending rows) to a MediaItem. */
    private fun fromTmdbRow(o: JSONObject): MediaItem? {
        val id = o.optString("id")
        if (id.isBlank()) return null
        val title = o.optString("title").ifBlank { o.optString("name") }
        if (title.isBlank()) return null
        val isTv = o.optString("media_type") == "tv" || o.has("name") || o.has("first_air_date")
        val t = if (isTv) MediaType.SERIES else MediaType.MOVIE
        val year = listOf("release_date", "first_air_date")
            .firstNotNullOfOrNull { k -> o.optString(k).takeIf { it.isNotBlank() }?.take(4)?.toIntOrNull() }
        return MediaItem(
            providerId = config.id,
            id = id,
            title = title,
            type = t,
            posterUrl = o.tmdbPath("poster_path")?.let { IMG + it },
            year = year,
            overview = o.optString("overview").takeIf { it.isNotBlank() },
            backdropUrl = o.tmdbPath("backdrop_path")?.let { IMG_L + it },
            rawType = if (t == MediaType.SERIES) "tv" else "movie",
        )
    }

    override suspend fun getStreams(item: MediaItem, episode: Episode?): List<StreamSource> =
        withContext(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            val resolved = TmdbResolver.resolve(item)
            if (resolved == null) {
                val msg = "✗ provider failed: couldn't resolve a TMDB id for this title"
                streamErrors[config.id] = msg
                lastOutcome[config.id] = msg
                return@withContext emptyList()
            }
            val source = runCatching { File(config.url).readText() }.getOrNull()
            if (source.isNullOrBlank()) {
                val msg = "✗ scraper file missing — reinstall this extension"
                streamErrors[config.id] = msg
                lastOutcome[config.id] = msg
                return@withContext emptyList()
            }
            val season: Int? = if (episode == null) {
                if (item.type == MediaType.SERIES) 1 else null
            } else seasonOf(episode)
            val epNum = if (episode == null) 1 else epNumberInSeason(episode)
            // TMDB has exactly two namespaces, and every nuvio provider is
            // written for those two: the whole ecosystem tests
            // `mediaType === "tv" ? "tv" : "movie"` (or builds
            // `${mediaType}/${tmdbId}` URL segments with it), so a series asked
            // as anything else is asked as a MOVIE — the provider fetches
            // /movie/<tvId>, gets a 404, and returns [] without a single
            // network request to its own site.
            //
            // [TmdbResolver] carries one extra value, its own "anime" hint for a
            // series (see [TmdbResolver.Resolved] — Ratings, TmdbMeta and the
            // rest map it back through the same test), and this call used to
            // pass that value straight into the engines. Every anime title
            // therefore came back from every nuvio engine as "no sources" while
            // the very same engines found servers in the real NuvioMobile app,
            // which only ever sends "tv"/"movie". Map the hint here, at the one
            // place that hands a media type to a foreign engine.
            val mediaType = if (resolved.mediaType.equals("movie", true)) "movie" else "tv"
            val asked = "tmdb=${resolved.tmdbId} $mediaType" +
                (if (season != null) " S$season" else "") +
                (if (season != null) "E$epNum" else "")
            val fetchMark = NuvioRuntime.fetchLogMark()
            val payload = NuvioRuntime.getStreams(
                HikariApp.instance,
                source,
                config.id,
                resolved.tmdbId,
                mediaType,
                season,
                epNum,
            )
            val parsed = runCatching { JSONObject(payload) }.getOrNull()
            if (parsed == null) {
                val msg = "✗ provider failed: unreadable result"
                streamErrors[config.id] = msg
                lastOutcome[config.id] = msg
                logNuvioOutcome(config, asked, msg, fetchMark)
                return@withContext emptyList()
            }
            if (!parsed.optBoolean("ok", false)) {
                val err = parsed.optString("error").ifBlank { "no sources found" }
                val msg = if (err == "timeout") "✗ provider failed: timed out after 60s"
                else "✗ provider failed: $err"
                streamErrors[config.id] = msg.take(400)
                lastOutcome[config.id] = msg.take(80)
                logNuvioOutcome(config, asked, msg, fetchMark)
                return@withContext emptyList()
            }
            val data = parsed.optJSONArray("data")
            if (data == null || data.length() == 0) {
                val msg = "no sources for this title"
                streamErrors[config.id] = msg
                lastOutcome[config.id] = "no sources"
                logNuvioOutcome(config, asked, msg, fetchMark)
                return@withContext emptyList()
            }
            val out = mutableListOf<StreamSource>()
            for (i in 0 until data.length()) {
                val s = data.optJSONObject(i) ?: continue
                toStreamSource(s)?.let { out.add(it) }
            }
            if (out.isNotEmpty()) {
                streamErrors.remove(config.id)
                lastOutcome[config.id] = "✓ ${out.size} source${if (out.size == 1) "" else "s"} in ${(System.currentTimeMillis() - startedAt) / 1000}s"
            } else {
                val msg = "✗ returned ${data.length()} rows but none playable"
                streamErrors[config.id] = msg
                lastOutcome[config.id] = msg
                logNuvioOutcome(config, asked, msg, fetchMark)
            }
            val distinct = out.distinctBy { it.url }
            com.hikari.app.net.StreamProbe.warmAsync(distinct)
            distinct
        }

    private fun logNuvioOutcome(
        config: ProviderConfig,
        asked: String,
        verdict: String,
        mark: Int,
    ) {
        val trail = runCatching { NuvioRuntime.fetchLogSince(mark, 6) }.getOrDefault(emptyList())
        com.hikari.app.data.Logs.log(
            "Nuvio",
            "${config.name}: $asked → $verdict" +
                (if (trail.isEmpty()) " · no HTTP request at all" else " · " + trail.joinToString(" | ")),
        )
    }

    private fun toStreamSource(s: JSONObject): StreamSource? {
        // Some providers wrap url+headers in a nested object.
        var url = s.optString("url")
        var headers = s.optJSONObject("headers")
        if (url.isBlank() && s.has("url")) {
            val nested = s.optJSONObject("url")
            if (nested != null) {
                url = nested.optString("url")
                headers = nested.optJSONObject("headers") ?: headers
            }
        }
        if (url.isBlank() && s.optString("externalUrl").isBlank() && s.optString("ytId").isBlank()) {
            return null
        }
        // Generic providers sometimes capture non-content links — the classic
        // being the SVG xmlns namespace (http://www.w3.org/2000/svg), which
        // must never become a playable source (or open a w3.org page in the
        // web view). Drop such streams outright.
        if (isGarbageUrl(url) || isGarbageUrl(s.optString("externalUrl"))) return null
        val name = s.optString("name").ifBlank { s.optString("title") }.ifBlank { config.name }
        val quality = s.optString("quality").ifBlank { "" }
        val displayName = if (quality.isNotBlank() && !name.contains(quality, true)) "$name $quality" else name

        val h = LinkedHashMap<String, String>()
        fun putHeaders(obj: JSONObject?) {
            if (obj == null) return
            obj.keys().forEach { k ->
                val v = obj.optString(k).filter { it.code < 128 }
                if (v.isNotBlank()) h[k] = v
            }
        }
        val hints = s.optJSONObject("behaviorHints")
        putHeaders(hints?.optJSONObject("proxyHeaders")?.optJSONObject("request"))
        putHeaders(hints?.optJSONObject("headers"))
        // Explicit headers win over the hint block when both are present.
        putHeaders(headers)
        // Always send a browser UA unless the provider explicitly set one.
        h.putIfAbsent("User-Agent", Http.UA)

        val subs = mutableListOf<SubtitleSource>()
        val subArr = runCatching { s.getJSONArray("subtitles") }.getOrNull()
        if (subArr != null) {
            for (i in 0 until subArr.length()) {
                val st = subArr.optJSONObject(i) ?: continue
                val su = st.optString("url")
                if (su.isBlank()) continue
                subs.add(
                    SubtitleSource(
                        st.optString("lang").ifBlank { st.optString("language") }.ifBlank { "Sub" },
                        su,
                    )
                )
            }
        }

        val isTorrent = s.optBoolean("isTorrent", false) ||
            url.startsWith("magnet:", true) || url.startsWith("torrent:", true)
        val infoHash = s.optString("infoHash").ifBlank { null }
            ?: infoHashOf(url)
        val isM3u8 = s.optBoolean("isM3u8", false) || url.contains(".m3u8", true)
        val isMpd = s.optBoolean("isMpd", false) || url.contains(".mpd", true)

        val details = buildStreamDetails(s, quality)
        return StreamSource(
            name = displayName,
            url = if (isTorrent) url else Http.normalizeDriveUrl(url),
            headers = h,
            subtitles = subs,
            isTorrent = isTorrent,
            infoHash = infoHash,
            isM3u8 = isM3u8,
            isMpd = isMpd,
            fileIdx = if (s.has("fileIdx")) s.optInt("fileIdx", -1).takeIf { it >= 0 } else null,
            trackers = runCatching {
                val a = s.getJSONArray("trackers")
                (0 until a.length()).mapNotNull { i -> a.optString(i).ifBlank { null } }
            }.getOrDefault(emptyList()),
            ytId = s.optString("ytId").ifBlank { null },
            externalUrl = s.optBoolean("externalUrl", false),
            details = details,
            provider = "Nuvio",
            providerId = config.id,
            providerName = config.name,
        )
    }

    /** Size / audio / codec / container line for the server chooser — matches
     *  the rich cards users expect from Nuvio-style source sheets. */
    private fun buildStreamDetails(s: JSONObject, quality: String): String {
        val bits = ArrayList<String>()
        fun add(v: String?) {
            val t = v?.trim().orEmpty()
            if (t.isNotBlank() && bits.none { it.equals(t, true) }) bits += t
        }
        add(quality.ifBlank { null })
        add(s.optString("size").ifBlank { s.optString("filesize") }.ifBlank { null })
        // description often already packs "2160p • 19.5 GB • HDR • H.265…"
        val desc = s.optString("description").trim()
        if (desc.isNotBlank()) {
            // Prefer the structured description when present; still append unique
            // quality/size bits that aren't already inside it.
            val keep = bits.filter { !desc.contains(it, true) }
            return (listOf(desc) + keep).joinToString(" • ").take(400)
        }
        add(s.optString("language").ifBlank { s.optString("lang") }.ifBlank { null })
        add(s.optString("audio").ifBlank { s.optString("audioCodec") }.ifBlank { null })
        add(s.optString("codec").ifBlank { s.optString("videoCodec") }.ifBlank { null })
        add(s.optString("container").ifBlank { s.optString("format") }.ifBlank { null })
        add(s.optString("source").ifBlank { s.optString("releaseGroup") }.ifBlank { null })
        if (s.optBoolean("hdr", false) || s.optString("hdr").isNotBlank()) add("HDR")
        if (s.optBoolean("dv", false) || s.optString("dv").equals("true", true)) add("DV")
        return bits.joinToString(" • ").take(400)
    }

    private fun infoHashOf(url: String): String? {
        Regex("""[?&]xt=urn:btih:([a-zA-Z0-9]{32,40})""").find(url)?.let { return it.groupValues[1] }
        Regex("""urn:btih:([a-zA-Z0-9]{32,40})""").find(url)?.let { return it.groupValues[1] }
        return null
    }

    /** True for URLs that point at non-content scaffolding (e.g. the SVG
     *  xmlns namespace http://www.w3.org/2000/svg). Such links must never be
     *  handed to the player, which would otherwise open them in the web view. */
    private fun isGarbageUrl(url: String): Boolean {
        if (url.isBlank()) return false
        if (url.contains("w3.org/2000/svg", ignoreCase = true)) return true
        val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull() ?: return false
        return host == "w3.org" || host.endsWith(".w3.org")
    }

    private fun seasonOf(ep: Episode): Int {
        if (ep.season > 0) return ep.season
        val text = listOfNotNull(ep.id, ep.name).firstOrNull { it.isNotBlank() } ?: return 1
        Regex("""(?i)[sS]\s*(\d+)\s*[eE]\s*(\d+)""").find(text)?.let { m ->
            m.groupValues[1].toIntOrNull()?.let { return it }
        }
        Regex("""(?i)season\s+(\d+)""").find(text)?.let { m ->
            m.groupValues[1].toIntOrNull()?.let { return it }
        }
        Regex("""(?:^|[^\d])(\d+)\s*[xX:.\-]\s*\d+(?:$|[^\d])""").find(text)?.let { m ->
            m.groupValues[1].toIntOrNull()?.let { return it }
        }
        return 1
    }

    /** In-season episode number for the engine (e.g. "S2E5" → 5): the id/name
     *  text first (it names the credential when present), then the curated
     *  struct field — never below 1, which no engine answers for. */
    private fun epNumberInSeason(ep: Episode): Int {
        val text = listOfNotNull(ep.id, ep.name).firstOrNull { it.isNotBlank() } ?: return ep.number.coerceAtLeast(1)
        Regex("""(?i)[sS]\s*(\d+)\s*[eE]\s*(\d+)""").find(text)?.let { m ->
            m.groupValues[2].toIntOrNull()?.let { return it }
        }
        Regex("""(?:^|[^\d])(\d+)\s*[xX:.\-]\s*(\d+)(?:$|[^\d])""").find(text)?.let { m ->
            m.groupValues[2].toIntOrNull()?.let { return it }
        }
        return ep.number.coerceAtLeast(1)
    }
}
