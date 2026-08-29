package com.hikari.app.nuvio

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Adapts a Nuvio JS provider to Hikari's ContentProvider contract. Nuvio
 * providers have no catalogs/search of their own — they resolve sources purely
 * from a TMDB id + mediaType (+ season/episode for series), which is exactly
 * how the official NuvioMobile app calls them. Hikari resolves the TMDB id via
 * [TmdbResolver] and plays whatever the provider returns.
 */
class NuvioScraper(override val config: ProviderConfig) : ContentProvider {

    companion object {
        /** Per-provider last failure (shown on the Detail screen). */
        val streamErrors = ConcurrentHashMap<String, String>()
        /** Per-provider catalog failure (shown on the Home empty state). */
        val catalogErrors = ConcurrentHashMap<String, String>()

        private const val IMG = "https://image.tmdb.org/t/p/w500"
        private const val IMG_L = "https://image.tmdb.org/t/p/w1280"
        private const val MAX_SEASONS = 24

        /** What a nuvio site actually hosts, inferred from its scraper file
         *  name. Nuvio providers export no catalog/search API of their own, so
         *  Hikari browses TMDB on their behalf — but the catalog must match the
         *  site's niche (an anime provider showing live-action movies is
         *  useless), and it must differ between providers so installing three
         *  extensions doesn't show three identical home screens. */
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

        // TMDB genre 16 = Animation, restricted to Japanese origin so anime
        // sites actually show anime (not Family Guy). vote_count.gte=100 keeps
        // "Top Rated" rows free of obscure 1-vote junk.
        private val ANIME_ROWS = listOf(
            RowDef("a0", MediaType.SERIES, "/discover/tv", mapOf("with_genres" to "16", "with_origin_country" to "JP", "sort_by" to "popularity.desc"), "Popular Anime"),
            RowDef("a1", MediaType.SERIES, "/discover/tv", mapOf("with_genres" to "16", "with_origin_country" to "JP", "sort_by" to "vote_average.desc", "vote_count.gte" to "100"), "Top Rated Anime"),
            RowDef("a2", MediaType.SERIES, "/discover/tv", mapOf("with_genres" to "16", "with_origin_country" to "JP", "sort_by" to "first_air_date.desc"), "New Anime"),
            RowDef("a3", MediaType.MOVIE, "/discover/movie", mapOf("with_genres" to "16", "with_origin_country" to "JP", "sort_by" to "popularity.desc"), "Popular Anime Movies"),
            RowDef("a4", MediaType.MOVIE, "/discover/movie", mapOf("with_genres" to "16", "with_origin_country" to "JP", "sort_by" to "vote_average.desc", "vote_count.gte" to "100"), "Top Rated Anime Movies"),
            RowDef("a5", MediaType.MOVIE, "/discover/movie", mapOf("with_genres" to "16", "with_origin_country" to "JP", "sort_by" to "primary_release_date.desc"), "New Anime Movies"),
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

        /** Deterministic slice of [pool] so different providers of the same
         *  niche still show different rows (seeded by the provider's name). */
        private fun window(pool: List<RowDef>, take: Int, hash: Int): List<RowDef> {
            val maxStart = pool.size - take
            val start = if (maxStart <= 0) 0 else hash % (maxStart + 1)
            return pool.subList(start, start + take)
        }
    }

    // Nuvio providers resolve sources purely from a TMDB id, and export no
    // catalog/search API of their own — so Hikari browses TMDB on their
    // behalf (same as the official NuvioMobile app). But instead of one shared
    // catalog for every extension, each provider gets its own niche-matched
    // catalog: anime sites show anime, Hindi sites show Hindi content, Korean
    // drama sites show K-dramas, and general sites get the usual rows. Rows are
    // picked deterministically from the niche's pool, so no two extensions end
    // up with identical home screens.
    private val rows: List<RowDef> by lazy {
        val h = config.name.hashCode() and 0x7fffffff
        when (nicheOf(config.name)) {
            Niche.GENERAL -> window(GENERAL_ROWS, 5, h)
            Niche.ANIME -> window(ANIME_ROWS, 4, h)
            Niche.HINDI -> window(HINDI_ROWS, 3, h)
            Niche.KDRAMA -> KDRAMA_ROWS
            Niche.FRENCH -> FRENCH_ROWS
            Niche.PERSIAN -> PERSIAN_ROWS
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
            posterUrl = d.optString("poster_path").takeIf { it.isNotBlank() }?.let { IMG + it } ?: item.posterUrl,
            year = year ?: item.year,
            overview = d.optString("overview").ifBlank { item.overview.orEmpty() }.ifBlank { null },
            genres = (0 until (d.optJSONArray("genres")?.length() ?: 0)).mapNotNull { i ->
                d.optJSONArray("genres")?.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }
            },
            backdropUrl = d.optString("backdrop_path").takeIf { it.isNotBlank() }?.let { IMG_L + it } ?: item.backdropUrl,
            rawType = item.rawType,
        )
    }

    override suspend fun getEpisodes(item: MediaItem): List<Episode>? = withContext(Dispatchers.IO) {
        if (item.type != MediaType.SERIES) return@withContext null
        val id = item.id
        if (id.isBlank() || !id.all { it.isDigit() }) return@withContext null
        val tv = TmdbResolver.apiGet("/tv/$id", emptyMap()) ?: return@withContext null
        val seasons = tv.optJSONArray("seasons") ?: return@withContext null
        val nums = (0 until seasons.length()).mapNotNull { i ->
            val s = seasons.optJSONObject(i) ?: return@mapNotNull null
            val n = s.optInt("season_number")
            if (n > 0 && s.optInt("episode_count") > 0) n else null
        }.take(MAX_SEASONS)
        val out = mutableListOf<Episode>()
        var global = 0
        for (sn in nums) {
            val sd = TmdbResolver.apiGet("/tv/$id/season/$sn", emptyMap()) ?: continue
            val eps = sd.optJSONArray("episodes") ?: continue
            for (i in 0 until eps.length()) {
                val e = eps.optJSONObject(i) ?: continue
                val en = e.optInt("episode_number")
                if (en <= 0) continue
                global++
                out += Episode(
                    number = global,
                    id = "S${sn}E$en",
                    name = e.optString("name").takeIf { it.isNotBlank() },
                    image = e.optString("still_path").takeIf { it.isNotBlank() }?.let { IMG + it },
                )
            }
        }
        out
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
            posterUrl = o.optString("poster_path").takeIf { it.isNotBlank() }?.let { IMG + it },
            year = year,
            overview = o.optString("overview").takeIf { it.isNotBlank() },
            backdropUrl = o.optString("backdrop_path").takeIf { it.isNotBlank() }?.let { IMG_L + it },
            rawType = if (t == MediaType.SERIES) "tv" else "movie",
        )
    }

    override suspend fun getStreams(item: MediaItem, episode: Episode?): List<StreamSource> =
        withContext(Dispatchers.IO) {
            val resolved = TmdbResolver.resolve(item)
            if (resolved == null) {
                streamErrors[config.id] =
                    "Couldn't resolve a TMDB id for this title (needed by nuvio providers)."
                return@withContext emptyList()
            }
            val source = runCatching { File(config.url).readText() }.getOrNull()
            if (source.isNullOrBlank()) {
                streamErrors[config.id] = "Scraper file missing — reinstall this extension."
                return@withContext emptyList()
            }
            // Series: nuvio needs the season + episode numbers. The episode's
            // id/name usually carries them ("S2E5", "2x3", …).
            val season = if (episode == null) null else seasonOf(episode)
            val epNum = if (episode == null) 1 else epNumberInSeason(episode)
            val payload = NuvioRuntime.getStreams(
                HikariApp.instance,
                source,
                config.id,
                resolved.tmdbId,
                resolved.mediaType,
                season,
                epNum,
            )
            val parsed = runCatching { JSONObject(payload) }.getOrNull()
            if (parsed == null) {
                streamErrors[config.id] = "Nuvio runtime returned an unreadable result."
                return@withContext emptyList()
            }
            if (!parsed.optBoolean("ok", false)) {
                val err = parsed.optString("error").ifBlank { "no sources found" }
                streamErrors[config.id] = if (err == "timeout")
                    "Nuvio provider timed out after 60s."
                else
                    err.take(400)
                return@withContext emptyList()
            }
            val data = parsed.optJSONArray("data")
            if (data == null || data.length() == 0) {
                streamErrors[config.id] = "Provider returned no sources for this title."
                return@withContext emptyList()
            }
            val out = mutableListOf<StreamSource>()
            for (i in 0 until data.length()) {
                val s = data.optJSONObject(i) ?: continue
                toStreamSource(s)?.let { out.add(it) }
            }
            if (out.isNotEmpty()) streamErrors.remove(config.id)
            out.distinctBy { it.url }
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
        if (headers != null) {
            headers.keys().forEach { k ->
                val v = headers.optString(k).filter { it.code < 128 }
                if (v.isNotBlank()) h[k] = v
            }
        }
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
        )
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

    /** Best-effort season number from the episode's id or name. */
    private fun seasonOf(ep: Episode): Int {
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

    /** Best-effort in-season episode number from the episode's id or name
     *  (e.g. "S2E5" → 5). Falls back to [Episode.number]. */
    private fun epNumberInSeason(ep: Episode): Int {
        val text = listOfNotNull(ep.id, ep.name).firstOrNull { it.isNotBlank() } ?: return ep.number
        Regex("""(?i)[sS]\s*(\d+)\s*[eE]\s*(\d+)""").find(text)?.let { m ->
            m.groupValues[2].toIntOrNull()?.let { return it }
        }
        Regex("""(?:^|[^\d])(\d+)\s*[xX:.\-]\s*(\d+)(?:$|[^\d])""").find(text)?.let { m ->
            m.groupValues[2].toIntOrNull()?.let { return it }
        }
        return ep.number
    }
}
