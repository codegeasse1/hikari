package com.hikari.app.data

import com.hikari.app.nuvio.TmdbResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * A browsable TMDB catalog for the extensions that have none of their own.
 *
 * A Stremio STREAM addon (HdHub, Torrentio, Comet…) is a manifest that answers
 * /stream and nothing else: it has no catalog, so Home had nothing to show for
 * one, it was hidden from the provider picker, and it could not be found by a
 * name search — all of which the user reads as "the addon isn't working". The
 * real Stremio client solves that by installing a META addon next to it
 * (Cinemeta, Streaming Catalogs) and browsing that addon's catalog.
 *
 * Hikari already browses TMDB on behalf of every Nuvio provider (their scrapers
 * resolve from a TMDB id and export no catalog either), so an addon with no
 * catalog gets the same treatment here: its Home rows and its search come from
 * TMDB, its ids resolve to an IMDb id for the addon's own /stream call, and no
 * extra "catalog addon" has to be installed to browse.
 *
 * Rows are chosen from a niche inferred from the extension's NAME (an addon
 * called "HdHub" shows Hindi rows, an anime addon shows anime) and picked
 * deterministically from a circular window offset by [offsetSeed], so two such
 * extensions never present identical Home screens.
 *
 * NuvioScraper keeps its own copy of the niche/rows logic on purpose: it is the
 * catalogue for a different engine's provider contract and worked out
 * separately, and this object exists to be shared by the engines that would
 * otherwise have NO catalogue at all (see StremioAddon).
 */
object TmdbBrowse {

    /** Every catalog id this object produces starts with this, so a caller can
     *  tell "browse TMDB for me" from an addon's own catalog id. */
    const val PREFIX = "tmdb-"

    private const val IMG = "https://image.tmdb.org/t/p/w500"
    private const val IMG_L = "https://image.tmdb.org/t/p/w1280"
    private const val MAX_SEASONS = 24

    /** One Home row: a TMDB endpoint plus the query it is asked with. */
    class Row(
        val id: String,
        val type: MediaType,
        val path: String,
        val params: Map<String, String>,
        val title: String,
    )

    private enum class Niche { GENERAL, ANIME, HINDI, KDRAMA, FRENCH, PERSIAN }

    private val GENERAL_ROWS = listOf(
        Row("trending", MediaType.UNKNOWN, "/trending/all/week", emptyMap(), "Trending"),
        Row("movies", MediaType.MOVIE, "/movie/popular", emptyMap(), "Popular Movies"),
        Row("movies-top", MediaType.MOVIE, "/movie/top_rated", emptyMap(), "Top Rated Movies"),
        Row("series", MediaType.SERIES, "/tv/popular", emptyMap(), "Popular Series"),
        Row("series-top", MediaType.SERIES, "/tv/top_rated", emptyMap(), "Top Rated Series"),
        Row("airing", MediaType.SERIES, "/tv/on_the_air", emptyMap(), "On TV"),
    )

    private val ANIME_ROWS = listOf(
        Row("anime", MediaType.SERIES, "/discover/tv", mapOf("with_genres" to "16", "with_origin_country" to "JP", "sort_by" to "popularity.desc"), "Popular Anime"),
        Row("anime-top", MediaType.SERIES, "/discover/tv", mapOf("with_genres" to "16", "with_origin_country" to "JP", "sort_by" to "vote_average.desc", "vote_count.gte" to "100"), "Top Rated Anime"),
        Row("anime-new", MediaType.SERIES, "/discover/tv", mapOf("with_genres" to "16", "with_origin_country" to "JP", "sort_by" to "first_air_date.desc"), "New Anime"),
        Row("anime-movies", MediaType.MOVIE, "/discover/movie", mapOf("with_genres" to "16", "with_origin_country" to "JP", "sort_by" to "popularity.desc"), "Anime Movies"),
    )

    private val HINDI_ROWS = listOf(
        Row("hindi", MediaType.MOVIE, "/discover/movie", mapOf("with_original_language" to "hi", "sort_by" to "popularity.desc"), "Popular Hindi Movies"),
        Row("hindi-series", MediaType.SERIES, "/discover/tv", mapOf("with_original_language" to "hi", "sort_by" to "popularity.desc"), "Popular Hindi Series"),
        Row("hindi-top", MediaType.MOVIE, "/discover/movie", mapOf("with_original_language" to "hi", "sort_by" to "vote_average.desc", "vote_count.gte" to "100"), "Top Rated Hindi Movies"),
        Row("hindi-new", MediaType.MOVIE, "/discover/movie", mapOf("with_original_language" to "hi", "sort_by" to "primary_release_date.desc"), "New Hindi Movies"),
    )

    private val KDRAMA_ROWS = listOf(
        Row("kdrama", MediaType.SERIES, "/discover/tv", mapOf("with_original_language" to "ko", "sort_by" to "popularity.desc"), "Popular Korean Dramas"),
        Row("kdrama-top", MediaType.SERIES, "/discover/tv", mapOf("with_original_language" to "ko", "sort_by" to "vote_average.desc", "vote_count.gte" to "100"), "Top Rated Korean Dramas"),
        Row("kdrama-new", MediaType.SERIES, "/discover/tv", mapOf("with_original_language" to "ko", "sort_by" to "first_air_date.desc"), "New Korean Dramas"),
        Row("korean-movies", MediaType.MOVIE, "/discover/movie", mapOf("with_original_language" to "ko", "sort_by" to "popularity.desc"), "Popular Korean Movies"),
    )

    private val FRENCH_ROWS = listOf(
        Row("french", MediaType.MOVIE, "/discover/movie", mapOf("with_original_language" to "fr", "sort_by" to "popularity.desc"), "Popular French Movies"),
        Row("french-series", MediaType.SERIES, "/discover/tv", mapOf("with_original_language" to "fr", "sort_by" to "popularity.desc"), "Popular French Series"),
        Row("french-top", MediaType.MOVIE, "/discover/movie", mapOf("with_original_language" to "fr", "sort_by" to "vote_average.desc", "vote_count.gte" to "100"), "Top Rated French Movies"),
    )

    private val PERSIAN_ROWS = listOf(
        Row("persian", MediaType.MOVIE, "/discover/movie", mapOf("with_original_language" to "fa", "sort_by" to "popularity.desc"), "Popular Persian Movies"),
        Row("persian-series", MediaType.SERIES, "/discover/tv", mapOf("with_original_language" to "fa", "sort_by" to "popularity.desc"), "Popular Persian Series"),
    )

    private fun nicheOf(name: String): Niche {
        val n = name.lowercase()
        return when {
            listOf("anime", "hianime", "anidb", "anikoto", "kurage", "cartoon", "aniwatch")
                .any { n.contains(it) } -> Niche.ANIME
            listOf(
                "desi", "hindi", "hindmoviez", "einthusan", "hdhub", "hdghar", "4khdhub",
                "moviebox", "vegamovies", "bollywood", "phisher",
            ).any { n.contains(it) } -> Niche.HINDI
            listOf("kdrama", "kisskh", "drama").any { n.contains(it) } -> Niche.KDRAMA
            listOf("movix", "nakios", "wiflix", "vostfr", "french", "streamingvf").any { n.contains(it) } -> Niche.FRENCH
            n.contains("persian") -> Niche.PERSIAN
            else -> Niche.GENERAL
        }
    }

    /** The rows [name] should show: its niche's pool, rotated by [offsetSeed]
     *  so two addons of the same niche start on different rows. */
    fun rowsFor(name: String, offsetSeed: Int, take: Int = 3): List<Row> {
        val pool = when (nicheOf(name)) {
            Niche.GENERAL -> GENERAL_ROWS
            Niche.ANIME -> ANIME_ROWS
            Niche.HINDI -> HINDI_ROWS
            Niche.KDRAMA -> KDRAMA_ROWS
            Niche.FRENCH -> FRENCH_ROWS
            Niche.PERSIAN -> PERSIAN_ROWS
        }
        if (pool.isEmpty()) return emptyList()
        val start = (offsetSeed % pool.size + pool.size) % pool.size
        return List(take.coerceAtMost(pool.size)) { i -> pool[(start + i) % pool.size] }
    }

    /** True when [catalogId] is one of OUR rows (see [PREFIX]). */
    fun isOurCatalog(catalogId: String): Boolean = catalogId.startsWith(PREFIX)

    private fun rowId(row: Row): String = PREFIX + row.id

    /** The [CatalogRef]s [rowsFor] would build for [providerId]. */
    fun catalogRefs(name: String, providerId: String, offsetSeed: Int): List<CatalogRef> =
        rowsFor(name, offsetSeed).map { row ->
            CatalogRef(providerId, row.type, rowId(row), row.title, row.type.name.lowercase())
        }

    /** One page of [catalogId]'s row, as items owned by [providerId]. The row id
     *  fully determines the TMDB endpoint, so no offset is needed to find it. */
    suspend fun items(providerId: String, catalogId: String, page: Int): List<MediaItem> =
        withContext(Dispatchers.IO) {
            val row = allRows().firstOrNull { rowId(it) == catalogId }
                ?: return@withContext emptyList()
            val params = LinkedHashMap(row.params)
            params["page"] = page.coerceAtLeast(1).toString()
            val data = TmdbResolver.apiGet(row.path, params) ?: return@withContext emptyList()
            val results = data.optJSONArray("results") ?: return@withContext emptyList()
            // A mixed row (`/trending/all/*`) is answered by discover while the
            // adult-content switch is off (see [NsfwGate.restrictRequest]), and a
            // discover item carries no `media_type` of its own — so the row's own
            // "whatever is in there" type has to come out as the movie the answer
            // actually is, or every item is dropped and Trending comes back empty.
            val fallback = if (NsfwGate.rewritesToMovies(row.path)) MediaType.MOVIE else row.type
            val out = ArrayList<MediaItem>()
            for (i in 0 until results.length()) {
                val o = results.optJSONObject(i) ?: continue
                toItem(o, providerId, fallback)?.let { out += it }
            }
            out.distinctBy { it.uniqueId }.take(40)
        }

    private fun allRows(): List<Row> =
        GENERAL_ROWS + ANIME_ROWS + HINDI_ROWS + KDRAMA_ROWS + FRENCH_ROWS + PERSIAN_ROWS

    /** A title search over TMDB, for an extension with no search of its own. */
    suspend fun search(providerId: String, query: String, page: Int): List<MediaItem> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            val data = TmdbResolver.apiGet(
                "/search/multi",
                mapOf("query" to query, "page" to page.coerceAtLeast(1).toString(), "include_adult" to "false"),
            ) ?: return@withContext emptyList()
            val results = data.optJSONArray("results") ?: return@withContext emptyList()
            val out = ArrayList<MediaItem>()
            for (i in 0 until results.length()) {
                val o = results.optJSONObject(i) ?: continue
                // `multi` also answers people; a person has no media_type of
                // movie/tv and is skipped by toItem's type check.
                toItem(o, providerId, null)?.let { out += it }
            }
            out.distinctBy { it.uniqueId }
        }

    private fun toItem(o: JSONObject, providerId: String, fallback: MediaType?): MediaItem? {
        val id = o.optString("id").trim()
        if (id.isBlank() || id == "null") return null
        val rawType = o.optString("media_type").ifBlank {
            when (fallback) {
                MediaType.MOVIE -> "movie"
                MediaType.SERIES -> "tv"
                else -> ""
            }
        }
        if (rawType.isBlank()) return null
        val series = rawType == "tv"
        val title = o.optString(if (series) "name" else "title")
            .ifBlank { o.optString("title").ifBlank { o.optString("name") } }
        if (title.isBlank()) return null
        val date = o.optString(if (series) "first_air_date" else "release_date")
        return MediaItem(
            providerId = providerId,
            id = id,
            title = title,
            type = if (series) MediaType.SERIES else MediaType.MOVIE,
            posterUrl = image(o.optString("poster_path"), IMG),
            backdropUrl = image(o.optString("backdrop_path"), IMG_L),
            year = date.take(4).toIntOrNull(),
            overview = o.optString("overview").takeIf { it.isNotBlank() },
            rating = o.optDouble("vote_average", 0.0).takeIf { it > 0.0 },
            rawType = rawType,
            originalTitle = o.optString(if (series) "original_name" else "original_title")
                .takeIf { it.isNotBlank() && it != title }
                ?: "",
            // TMDB's own adult marker (see MediaItem.nsfw).
            nsfw = o.optBoolean("adult", false),
        )
    }

    private fun image(path: String, base: String): String? =
        path.takeIf { it.isNotBlank() && it != "null" }?.let { base + it }

    /** The full details behind a [MediaItem] that came from here (a row, a
     *  search, or the same title opened from an extension). */
    suspend fun meta(item: MediaItem): MediaItem = withContext(Dispatchers.IO) {
        val kind = if (item.type == MediaType.SERIES) "tv" else "movie"
        val d = TmdbResolver.apiGet("/$kind/${item.id}", emptyMap()) ?: return@withContext item
        val title = d.optString(if (kind == "tv") "name" else "title").ifBlank { item.title }
        val date = d.optString(if (kind == "tv") "first_air_date" else "release_date")
        val genres = d.optJSONArray("genres")?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name")?.ifBlank { null } }
        } ?: emptyList()
        item.copy(
            title = title,
            posterUrl = image(d.optString("poster_path"), IMG) ?: item.posterUrl,
            backdropUrl = image(d.optString("backdrop_path"), IMG_L) ?: item.backdropUrl,
            overview = d.optString("overview").ifBlank { item.overview ?: "" }.ifBlank { null },
            year = date.take(4).toIntOrNull() ?: item.year,
            rating = d.optDouble("vote_average", 0.0).takeIf { it > 0.0 } ?: item.rating,
            genres = genres.ifEmpty { item.genres },
        )
    }

    /**
     * The episode list of a series from TMDB. Episodes that have not aired yet
     * are left out (TMDB lists a show's whole planned run), which is what the
     * Nuvio path does and what keeps a "next season" from appearing as rows the
     * user cannot play.
     */
    suspend fun episodes(item: MediaItem): List<Episode>? = withContext(Dispatchers.IO) {
        if (item.type != MediaType.SERIES) return@withContext null
        // A whole-show lookup costs one request per season, so it is capped as a
        // whole (the detail screen has its own budget for this and falls back to
        // another extension's episode list when nothing arrives in time).
        withTimeoutOrNull(25_000) {
            val show = TmdbResolver.apiGet("/tv/${item.id}", emptyMap()) ?: return@withTimeoutOrNull null
            val seasons = show.optJSONArray("seasons") ?: return@withTimeoutOrNull null
            val numbers = ArrayList<Int>()
            for (i in 0 until seasons.length()) {
                val s = seasons.optJSONObject(i) ?: continue
                val n = s.optInt("season_number", -1)
                // Season 0 is TMDB's "Specials" — kept, since some shows put
                // their only real content there, but a season with no episodes
                // at all is skipped.
                if (n < 0 || s.optInt("episode_count", 0) <= 0) continue
                numbers += n
                if (numbers.size >= MAX_SEASONS) break
            }
            if (numbers.isEmpty()) return@withTimeoutOrNull null
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(java.util.Date())
            // Seasons in parallel: a 20-season show used to be 20 round trips
            // one after another.
            val loaded = coroutineScope {
                numbers.map { n ->
                    async {
                        n to withTimeoutOrNull(8_000) {
                            TmdbResolver.apiGet("/tv/${item.id}/season/$n", emptyMap())
                        }
                    }
                }.awaitAll()
            }
            val out = ArrayList<Episode>()
            for ((n, season) in loaded) {
                val eps = season?.optJSONArray("episodes") ?: continue
                for (i in 0 until eps.length()) {
                    val e = eps.optJSONObject(i) ?: continue
                    val number = e.optInt("episode_number", -1)
                    if (number < 0) continue
                    val air = e.optString("air_date")
                    if (air.length >= 10 && air > today) continue
                    out += Episode(
                        number = number,
                        id = "${item.id}:$n:$number",
                        name = e.optString("name").ifBlank { "Episode $number" },
                        image = image(e.optString("still_path"), IMG),
                        season = n,
                    )
                }
            }
            out.sortedWith(compareBy({ it.season }, { it.number })).ifEmpty { null }
        }
    }

    /**
     * The IMDb id behind a TMDB id — what a Stremio addon's /stream call needs
     * (addons declare `idPrefixes: ["tt"]`, and the real client only asks them
     * about `tt…` ids). Null when TMDB does not know one.
     */
    suspend fun imdbId(tmdbId: String, kind: String): String? = withContext(Dispatchers.IO) {
        val d = TmdbResolver.apiGet("/$kind/$tmdbId/external_ids", emptyMap()) ?: return@withContext null
        d.optString("imdb_id").takeIf { it.startsWith("tt") && it.length > 3 }
    }
}
