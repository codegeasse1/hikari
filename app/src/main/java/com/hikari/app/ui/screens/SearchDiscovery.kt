package com.hikari.app.ui.screens

import com.hikari.app.data.MediaItem
import com.hikari.app.data.MediaType
import com.hikari.app.nuvio.TmdbResolver
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

private const val DISCOVER_IMG = "https://image.tmdb.org/t/p/w500"
private const val DISCOVER_IMG_WIDE = "https://image.tmdb.org/t/p/w1280"

/** TMDB-backed browsing for the search tab: trending rail, genre explore
 *  cards, production-company grid and the filtered discover results behind
 *  them. Everything is memory-cached for the process; resolve/discover
 *  misses never throw (null/emptyList) so the tab degrades to plain
 *  provider search offline. */
object SearchDiscovery {

    /** Static "Try searching" chips on the idle search tab: offline fallback
     *  for [trySearching], which serves the same chips fresh from TMDB. */
    val TRY_SEARCHING: List<String> = listOf(
        "The Odyssey",
        "Avengers: Doomsday",
        "The Beast",
        "VisionQuest",
        "Allen",
        "Heartland",
        "The Body",
    )

    /** Genre cards on the idle tab ("Explore genres"). */
    val EXPLORE_GENRES: List<String> = listOf("Action", "Comedy", "Drama", "Sci-Fi")

    /** Production grid names, resolved to TMDB company ids + logos at
     *  runtime (one /search/company each, cached). */
    val PRODUCTIONS: List<String> = listOf(
        "Netflix",
        "Disney+",
        "Apple TV+",
        "Prime Video",
        "HBO",
        "Hulu",
        "Paramount+",
        "Peacock",
        "Warner Bros. Pictures",
        "Universal Pictures",
        "Walt Disney Pictures",
        "Pixar",
        "Marvel Studios",
        "Lucasfilm",
        "20th Century Studios",
        "Sony Pictures",
        "Columbia Pictures",
        "MGM",
        "Lionsgate",
        "A24",
        "DreamWorks Animation",
        "Studio Ghibli",
        "BBC",
        "Canal+",
    )

    /** Original-language dropdown (TMDB codes). */
    val LANGUAGES: List<Pair<String, String>> = listOf(
        "" to "Any language",
        "en" to "English",
        "ja" to "Japanese",
        "ko" to "Korean",
        "es" to "Spanish",
        "fr" to "French",
        "hi" to "Hindi",
        "zh" to "Chinese",
        "it" to "Italian",
        "de" to "German",
        "pt" to "Portuguese",
        "ar" to "Arabic",
        "tr" to "Turkish",
        "ru" to "Russian",
    )

    data class ResolvedCompany(val id: Int, val name: String, val logoUrl: String?)

    private val companyCache = ConcurrentHashMap<String, ResolvedCompany?>()
    private var trendingCache: Triple<String, Long, List<MediaItem>>? = null
    private const val TRENDING_TTL_MS = 30 * 60 * 1000L

    /** Calendar day (yyyy-MM-dd): the trending rail and try-searching chips
     *  refresh from TMDB whenever the day rolls over. */
    private fun dayKey(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
    private val genreArtCache = ConcurrentHashMap<String, Pair<Long, List<MediaItem>>>()
    private const val GENRE_ART_TTL_MS = 60 * 60 * 1000L

    private val genreNameById: Map<Int, String> = run {
        val m = LinkedHashMap<Int, String>()
        for (g in com.hikari.app.data.TmdbGenres.MOVIE) m.putIfAbsent(g.id, g.name)
        for (g in com.hikari.app.data.TmdbGenres.TV) m.putIfAbsent(g.id, g.name)
        m
    }

    /** Resolves a studio/streamer name to its TMDB company id + logo. */
    suspend fun resolveCompany(name: String): ResolvedCompany? {
        val key = name.trim().lowercase()
        if (key.isEmpty()) return null
        companyCache[key]?.let { return it }
        val hit = runCatching {
            val d = TmdbResolver.apiGet("/search/company", mapOf("query" to name.trim())) ?: return@runCatching null
            val arr = d.optJSONArray("results") ?: return@runCatching null
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optInt("id", -1)
                val nm = o.optString("name").trim()
                if (id <= 0 || nm.isBlank() || nm == "null") continue
                val logo = o.optString("logo_path").takeIf { it.isNotBlank() && it != "null" }
                    ?.let { DISCOVER_IMG + it }
                return@runCatching ResolvedCompany(id, nm, logo)
            }
            null
        }.getOrNull()
        // Cache hits only: a miss retries next open (network may be back).
        if (hit != null) companyCache[key] = hit
        return hit
    }

    /** Trending movies + shows today, for the idle tab rail. Served from
     *  TMDB's daily trending feed and re-fetched whenever the day changes,
     *  so the rail is different every day (30-min memory cache within a day). */
    suspend fun trending(): List<MediaItem> {
        val day = dayKey()
        trendingCache?.let { (d, at, list) ->
            if (d == day && System.currentTimeMillis() - at < TRENDING_TTL_MS) return list
        }
        val list = runCatching {
            val d = TmdbResolver.apiGet("/trending/all/day", emptyMap()) ?: return@runCatching emptyList()
            val arr = d.optJSONArray("results") ?: return@runCatching emptyList()
            val out = ArrayList<MediaItem>(12)
            for (i in 0 until arr.length()) {
                if (out.size >= 12) break
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("media_type") == "person") continue
                tmdbItem(o, o.optString("media_type"))?.let { out += it }
            }
            out
        }.getOrDefault(emptyList())
        if (list.isNotEmpty()) trendingCache = Triple(day, System.currentTimeMillis(), list)
        return list
    }

    /** "Try searching" chips, fresh every day: today's TMDB trending titles,
     *  rotated by day-of-year so the picked set changes daily. Falls back to
     *  the static list when the network is down or trending is bare. */
    suspend fun trySearching(): List<String> {
        val names = runCatching {
            trending().map { it.searchTitle.trim() }
                .filter { it.isNotBlank() }
                .distinct()
        }.getOrDefault(emptyList())
        if (names.size < 3) return TRY_SEARCHING
        val day = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR)
        val n = minOf(7, names.size)
        val start = day % names.size
        return (0 until n).map { names[(start + it) % names.size] }
    }

    /** Popular posters for a genre card (movie + tv discover, top 3).
     *  Anime-tag genres have no TMDB genre id, so they fall back to a
     *  title search for the tag name (which always returns posters), and
     *  finally to trending — a genre tile must never sit as a bare
     *  monogram when the network is up. */
    suspend fun genreArt(genreName: String): List<MediaItem> {
        val key = genreName.trim().lowercase()
        genreArtCache[key]?.let { (at, list) ->
            if (System.currentTimeMillis() - at < GENRE_ART_TTL_MS) return list
        }
        val ids = com.hikari.app.data.Genres.tmdbGenreFilter(genreName)
        var list: List<MediaItem> = if (ids.isNotEmpty()) runCatching {
            coroutineScope {
                val m = async { discoverRaw("movie", ids, 0, 0.0, 0, "", 1) }
                val t = async { discoverRaw("tv", ids, 0, 0.0, 0, "", 1) }
                (m.await() + t.await()).take(3)
            }
        }.getOrDefault(emptyList()) else emptyList()
        if (list.isEmpty()) {
            list = runCatching {
                coroutineScope {
                    val m = async { searchRaw(genreName, "movie") }
                    val t = async { searchRaw(genreName, "tv") }
                    (m.await() + t.await()).take(3)
                }
            }.getOrDefault(emptyList())
        }
        if (list.isEmpty()) {
            list = runCatching { trending().take(3) }.getOrDefault(emptyList())
        }
        if (list.isNotEmpty()) genreArtCache[key] = System.currentTimeMillis() to list
        return list
    }

    private suspend fun searchRaw(query: String, kind: String): List<MediaItem> {
        val d = TmdbResolver.apiGet(
            "/search/$kind",
            mapOf("query" to query.trim(), "include_adult" to "false", "page" to "1"),
        ) ?: return emptyList()
        val arr = d.optJSONArray("results") ?: return emptyList()
        val out = ArrayList<MediaItem>(arr.length())
        for (i in 0 until arr.length()) {
            if (out.size >= 3) break
            val o = arr.optJSONObject(i) ?: continue
            tmdbItem(o, kind)?.let { out += it }
        }
        return out
    }

    /**
     * Filtered TMDB browsing behind the filter sheet's company grid and the
     * genre explore cards. [genreIds] is a TMDB with_genres value ("28" or
     * "28|12"); [companyId] a with_companies id. [movie]/[tv] pick the
     * endpoints ("both" merges by popularity). Client-side [years]/upcoming
     * filtering happens on the returned items via the shared search filter.
     */
    suspend fun browse(
        genreIds: String,
        companyId: Int,
        movie: Boolean,
        tv: Boolean,
        minVote: Double,
        minVotes: Int,
        lang: String,
    ): List<MediaItem> =
        browsePages(genreIds, companyId, movie, tv, minVote, minVotes, lang, listOf(1, 2, 3)).items

    /**
     * One browse window: [pages] TMDB discover pages per enabled endpoint,
     * merged and deduped. Returns the items plus [BrowsePage.maybeMore],
     * which is true when any endpoint filled a whole page (so a further page
     * may still hold titles — the UI keeps offering "Load more" until a page
     * comes back short). TMDB caps discover at page 500.
     */
    data class BrowsePage(val items: List<MediaItem>, val maybeMore: Boolean)

    suspend fun browsePages(
        genreIds: String,
        companyId: Int,
        movie: Boolean,
        tv: Boolean,
        minVote: Double,
        minVotes: Int,
        lang: String,
        pages: List<Int>,
    ): BrowsePage = runCatching {
        coroutineScope {
            val jobs = ArrayList<kotlinx.coroutines.Deferred<List<MediaItem>>>(pages.size * 2)
            if (movie) {
                for (page in pages) {
                    jobs += async { discoverRaw("movie", genreIds, companyId, minVote, minVotes, lang, page) }
                }
            }
            if (tv) {
                for (page in pages) {
                    jobs += async { discoverRaw("tv", genreIds, companyId, minVote, minVotes, lang, page) }
                }
            }
            val seen = HashSet<String>()
            val out = ArrayList<MediaItem>(20 * jobs.size)
            var full = false
            for (job in jobs) {
                val list = job.await()
                if (list.size >= 20) full = true
                for (m in list) {
                    if (seen.add(m.type.name + "|" + m.id)) out += m
                }
            }
            BrowsePage(out.sortedByDescending { it.rating ?: -1.0 }, full)
        }
    }.getOrDefault(BrowsePage(emptyList(), false))

    private suspend fun discoverRaw(
        kind: String,
        genreIds: String,
        companyId: Int,
        minVote: Double,
        minVotes: Int,
        lang: String,
        page: Int,
        sort: String = "popularity.desc",
    ): List<MediaItem> {
        val q = LinkedHashMap<String, String>()
        if (genreIds.isNotBlank()) q["with_genres"] = genreIds
        if (companyId > 0) q["with_companies"] = companyId.toString()
        if (minVote > 0) q["vote_average.gte"] = minVote.toString()
        if (minVotes > 0) q["vote_count.gte"] = minVotes.toString()
        if (lang.isNotBlank()) q["with_original_language"] = lang
        q["sort_by"] = sort
        q["include_adult"] = "false"
        q["page"] = page.coerceIn(1, 500).toString()
        val d = TmdbResolver.apiGet("/discover/$kind", q) ?: return emptyList()
        val arr = d.optJSONArray("results") ?: return emptyList()
        val out = ArrayList<MediaItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            tmdbItem(o, kind)?.let { out += it }
        }
        return out
    }

    /** Flexible discover for the Home Discovery rows: any media, genres,
     *  language, sort and vote floor. One page per endpoint, merged. */
    suspend fun discover(
        kinds: List<String>,
        genreIds: String,
        lang: String,
        sort: String,
        minVotes: Int = 0,
    ): List<MediaItem> = runCatching {
        coroutineScope {
            val jobs = kinds.map { k ->
                async { discoverRaw(k, genreIds, 0, 0.0, minVotes, lang, 1, sort) }
            }
            val seen = HashSet<String>()
            val out = ArrayList<MediaItem>(40)
            for (job in jobs) {
                for (m in job.await()) {
                    if (seen.add(m.type.name + "|" + m.id)) out += m
                }
            }
            if (sort.startsWith("vote_average")) out.sortedByDescending { it.rating ?: -1.0 }
            else out
        }
    }.getOrDefault(emptyList())

    /** Movies in cinemas right now. */
    suspend fun nowPlaying(): List<MediaItem> = runCatching {
        val d = TmdbResolver.apiGet("/movie/now_playing", mapOf("region" to "US")) ?: return@runCatching emptyList()
        val arr = d.optJSONArray("results") ?: return@runCatching emptyList()
        val out = ArrayList<MediaItem>(arr.length())
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { tmdbItem(it, "movie") }?.let { out += it }
        }
        out
    }.getOrDefault(emptyList())

    /** Movies arriving soon — only truly unreleased (release_date > today).
     *  TMDB /movie/upcoming can still list titles whose date already passed,
     *  which is why released movies leaked into the Coming soon row. */
    suspend fun comingSoon(): List<MediaItem> = runCatching {
        val d = TmdbResolver.apiGet("/movie/upcoming", mapOf("region" to "US")) ?: return@runCatching emptyList()
        val arr = d.optJSONArray("results") ?: return@runCatching emptyList()
        val today = try {
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        } catch (_: Exception) { "" }
        val out = ArrayList<MediaItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val rel = o.optString("release_date").trim()
            if (rel.isBlank() || rel == "null") continue
            if (today.isNotBlank() && rel <= today) continue
            o.let { tmdbItem(it, "movie") }?.let { out += it }
        }
        out
    }.getOrDefault(emptyList())

    /** Series on the air this week (the "cinemas" equivalent for animation). */
    suspend fun airingNow(genreIds: String = "", lang: String = ""): List<MediaItem> = runCatching {
        val q = LinkedHashMap<String, String>()
        if (genreIds.isNotBlank()) q["with_genres"] = genreIds
        if (lang.isNotBlank()) q["with_original_language"] = lang
        q["sort_by"] = "popularity.desc"
        val d = TmdbResolver.apiGet("/tv/on_the_air", q) ?: return@runCatching emptyList()
        val arr = d.optJSONArray("results") ?: return@runCatching emptyList()
        val out = ArrayList<MediaItem>(arr.length())
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { tmdbItem(it, "tv") }?.let { out += it }
        }
        out
    }.getOrDefault(emptyList())

    /** Titles like [item] (TMDB similar), for the "If you like" row. */
    suspend fun similarFor(item: MediaItem): List<MediaItem> = runCatching {
        val resolved = TmdbResolver.resolve(item) ?: return@runCatching emptyList()
        val d = TmdbResolver.apiGet("/${resolved.mediaType}/${resolved.tmdbId}/similar", emptyMap<String, String>())
            ?: return@runCatching emptyList()
        val arr = d.optJSONArray("results") ?: return@runCatching emptyList()
        val out = ArrayList<MediaItem>(arr.length())
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { tmdbItem(it, resolved.mediaType) }?.let { out += it }
        }
        out.take(20)
    }.getOrDefault(emptyList())

    /** One TMDB movie/tv object -> a detail-openable [MediaItem]. */
    fun tmdbItem(o: JSONObject, kind: String): MediaItem? {
        val seg = if (kind == "movie") "movie" else "tv"
        val id = o.optString("id").trim()
        if (id.isBlank() || id == "null") return null
        val title = o.optString("title").ifBlank { o.optString("name") }.trim()
        if (title.isBlank() || title == "null") return null
        val poster = o.optString("poster_path").takeIf { it.isNotBlank() && it != "null" }
            ?.let { DISCOVER_IMG + it }
        val backdrop = o.optString("backdrop_path").takeIf { it.isNotBlank() && it != "null" }
            ?.let { DISCOVER_IMG_WIDE + it }
        if (poster == null && backdrop == null) return null
        val date = o.optString("release_date").ifBlank { o.optString("first_air_date") }.trim()
        val year = date.take(4).toIntOrNull()?.takeIf { it > 1800 }
        val genreNames = o.optJSONArray("genre_ids")?.let { arr ->
            (0 until arr.length()).mapNotNull { genreNameById[arr.optInt(it, -1)] }
        }.orEmpty()
        return MediaItem(
            providerId = "tmdb",
            id = id,
            title = title,
            type = if (seg == "movie") MediaType.MOVIE else MediaType.SERIES,
            posterUrl = poster,
            year = year,
            overview = o.optString("overview").takeIf { it.isNotBlank() && it != "null" },
            genres = genreNames,
            backdropUrl = backdrop,
            rawType = "tmdb",
            rating = o.optDouble("vote_average", Double.NaN).takeIf { !it.isNaN() && it > 0 },
            originalTitle = o.optString("original_title").ifBlank { o.optString("original_name") }
                .trim().takeIf { it.isNotBlank() && it != "null" }.orEmpty(),
            nsfw = o.optBoolean("adult", false),
        )
    }
}
