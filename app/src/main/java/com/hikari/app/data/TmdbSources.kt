package com.hikari.app.data

import com.hikari.app.nuvio.TmdbResolver
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * The KINDS of TMDB source a folder can hold — the type chips of the reference
 * client's "TMDB Sources" screen: Presets, Public list, Production, Network,
 * Collection, Person, Director, Custom.
 *
 * A preset is a ready-made row (Marvel Studios, HBO) that ships with the app,
 * and everything else is a live TMDB query the user describes with an id, a
 * name or a URL. All of them produce the exact same [MediaItem] shape
 * (`providerId = "tmdb"`, numeric TMDB id, `rawType = "tmdb"`), so the detail
 * page, its shelves and the player resolve a hand-built source through the very
 * path a preset already used.
 */
enum class TmdbSourceType(val key: String, val label: String) {
    PRESET("preset", "Presets"),
    TITLE("title", "Movie or series"),
    LIST("list", "Public list"),
    COMPANY("company", "Production"),
    NETWORK("network", "Network"),
    COLLECTION("collection", "Collection"),
    PERSON("person", "Person"),
    DIRECTOR("director", "Director"),
    DISCOVER("discover", "Custom");

    /** Company/network/collection/list — a TMDB id or a name to search for. */
    val isEntity: Boolean get() = this == COMPANY || this == NETWORK ||
        this == COLLECTION || this == LIST

    /** A person id (cast for [PERSON], crew `job == "Director"` for [DIRECTOR]). */
    val isPerson: Boolean get() = this == PERSON || this == DIRECTOR

    /** A network only ever has series; forcing tv keeps the picker honest. */
    val forcesTv: Boolean get() = this == NETWORK

    companion object {
        fun fromKey(k: String?): TmdbSourceType =
            entries.firstOrNull { it.key == k } ?: PRESET
    }
}

/** One sort order of a TMDB discover query. */
data class TmdbSort(val key: String, val label: String)

/** The sort orders TMDB accepts, split per media kind (the date field differs). */
object TmdbSorts {
    val MOVIE = listOf(
        TmdbSort("popularity.desc", "Popular"),
        TmdbSort("vote_average.desc", "Top rated"),
        TmdbSort("primary_release_date.desc", "Newest"),
        TmdbSort("revenue.desc", "Biggest box office"),
        TmdbSort("title.asc", "A → Z"),
    )
    val SERIES = listOf(
        TmdbSort("popularity.desc", "Popular"),
        TmdbSort("vote_average.desc", "Top rated"),
        TmdbSort("first_air_date.desc", "Newest"),
        TmdbSort("name.asc", "A → Z"),
    )
    fun forMedia(media: String): List<TmdbSort> = if (media == "tv") SERIES else MOVIE
    fun labelOf(media: String, key: String): String =
        forMedia(media).firstOrNull { it.key == key }?.label
            ?: forMedia(media).first().label
}

/** One TMDB genre, used by the "Custom" source's genre filter. */
data class TmdbGenre(val id: Int, val name: String)

/** TMDB's genre ids (movie and tv are different namespaces). */
object TmdbGenres {
    val MOVIE = listOf(
        TmdbGenre(28, "Action"), TmdbGenre(12, "Adventure"), TmdbGenre(16, "Animation"),
        TmdbGenre(35, "Comedy"), TmdbGenre(80, "Crime"), TmdbGenre(99, "Documentary"),
        TmdbGenre(18, "Drama"), TmdbGenre(10751, "Family"), TmdbGenre(14, "Fantasy"),
        TmdbGenre(36, "History"), TmdbGenre(27, "Horror"), TmdbGenre(10402, "Music"),
        TmdbGenre(9648, "Mystery"), TmdbGenre(10749, "Romance"), TmdbGenre(878, "Science Fiction"),
        TmdbGenre(53, "Thriller"), TmdbGenre(10752, "War"), TmdbGenre(37, "Western"),
    )
    val TV = listOf(
        TmdbGenre(10759, "Action & Adventure"), TmdbGenre(16, "Animation"),
        TmdbGenre(35, "Comedy"), TmdbGenre(80, "Crime"), TmdbGenre(99, "Documentary"),
        TmdbGenre(18, "Drama"), TmdbGenre(10751, "Family"), TmdbGenre(10762, "Kids"),
        TmdbGenre(9648, "Mystery"), TmdbGenre(10763, "News"), TmdbGenre(10764, "Reality"),
        TmdbGenre(10765, "Sci-Fi & Fantasy"), TmdbGenre(10766, "Soap"),
        TmdbGenre(10767, "Talk"), TmdbGenre(10768, "War & Politics"), TmdbGenre(37, "Western"),
    )
    fun forMedia(media: String): List<TmdbGenre> = if (media == "tv") TV else MOVIE
    fun nameOf(media: String, id: Int): String? =
        forMedia(media).firstOrNull { it.id == id }?.name
}

/** One hit of the editor's Search button (a company, network, person, list…). */
data class TmdbHit(
    val id: String,
    val name: String,
    val subtitle: String = "",
    /** "movie" or "tv" — only a TITLE search fills this in, where one query
     *  matches both shapes and each result has to say which one it is. */
    val media: String = "",
    /** The hit's TMDB poster, when the endpoint that produced it carried one.
     *  The title search shows its results as artwork, the way a search should
     *  look; the entity searches (a studio, a person) leave it blank. */
    val posterUrl: String = "",
)

/**
 * A saved TMDB source, stored as JSON in [CatalogSource.tmdbSpec].
 *
 * [media] is "movie", "tv" or "all" (both — what a person/director source
 * wants). [id] is a plain TMDB id by the time the source is saved; the editor
 * turns "Marvel Studios", "420" and a themoviedb.org URL into the id.
 */
data class TmdbSpec(
    val type: TmdbSourceType = TmdbSourceType.PRESET,
    val id: String = "",
    val preset: String = "",
    val media: String = "movie",
    val sort: String = "popularity.desc",
    val genre: Int = 0,
    val year: Int = 0,
    val title: String = "",
    // ---------------------------------------------------------------------
    //  The Discover screen's advanced filters, in TMDB's own vocabulary.
    //
    //  The chip-level [genre]/[year] above stay for the quick path; these are
    //  the full set the reference app's custom-source editor exposes, so a
    //  hand-built "90s Japanese horror, well rated, on Netflix in the US" row is
    //  expressible. Every one of them is a STRING that is empty when unset —
    //  empty means "do not send the parameter", which is what makes an unset
    //  filter behave exactly like it did before this existed. Only
    //  [TmdbSourceType.DISCOVER] (and the company/network variants, which are
    //  discover queries too) read them.
    // ---------------------------------------------------------------------
    val genresText: String = "",
    val genresExclude: String = "",
    val dateFrom: String = "",
    val dateTo: String = "",
    val ratingMin: String = "",
    val ratingMax: String = "",
    val votesMin: String = "",
    val language: String = "",
    val country: String = "",
    val keywords: String = "",
    val keywordsExclude: String = "",
    val companies: String = "",
    val companiesExclude: String = "",
    val networks: String = "",
    val providers: String = "",
    val providersExclude: String = "",
    val region: String = "",
) {
    val isMovie: Boolean get() = media == "movie"
    val isTv: Boolean get() = media == "tv"
    val isAll: Boolean get() = media != "movie" && media != "tv"

    /** The MediaType a row built from this source carries (UNKNOWN = mixed). */
    val kind: MediaType get() = when {
        isMovie -> MediaType.MOVIE
        isTv -> MediaType.SERIES
        else -> MediaType.UNKNOWN
    }

    /** Identity for dedupe — [title] is deliberately excluded, since renaming a
     *  source must not let the same source be added twice. Every filter is part
     *  of it: two discover rows that differ only in their filters are two
     *  different rows, and without this they would collide and one of them would
     *  quietly disappear from the folder. */
    val identity: String
        get() = listOf(
            type.key, id, preset, media, sort, genre.toString(), year.toString(),
            genresText, genresExclude, dateFrom, dateTo, ratingMin, ratingMax,
            votesMin, language, country, keywords, keywordsExclude, companies,
            companiesExclude, networks, providers, providersExclude, region,
        ).joinToString("|")

    fun encode(): String = JSONObject()
        .put("t", type.key)
        .put("id", id)
        .put("p", preset)
        .put("m", media)
        .put("s", sort)
        .put("g", genre)
        .put("y", year)
        .put("n", title)
        .put("gx", genresText)
        .put("gd", genresExclude)
        .put("df", dateFrom)
        .put("dt", dateTo)
        .put("rmn", ratingMin)
        .put("rmx", ratingMax)
        .put("vmn", votesMin)
        .put("lg", language)
        .put("ct", country)
        .put("kw", keywords)
        .put("kx", keywordsExclude)
        .put("co", companies)
        .put("cx", companiesExclude)
        .put("nw", networks)
        .put("pv", providers)
        .put("px", providersExclude)
        .put("rg", region)
        .toString()

    companion object {
        fun decode(raw: String?): TmdbSpec? {
            if (raw.isNullOrBlank()) return null
            return runCatching {
                val o = JSONObject(raw)
                TmdbSpec(
                    type = TmdbSourceType.fromKey(o.optString("t")),
                    id = o.optString("id"),
                    preset = o.optString("p"),
                    media = o.optString("m").ifBlank { "movie" },
                    sort = o.optString("s").ifBlank { "popularity.desc" },
                    genre = o.optInt("g", 0),
                    year = o.optInt("y", 0),
                    title = o.optString("n"),
                    genresText = o.optString("gx"),
                    genresExclude = o.optString("gd"),
                    dateFrom = o.optString("df"),
                    dateTo = o.optString("dt"),
                    ratingMin = o.optString("rmn"),
                    ratingMax = o.optString("rmx"),
                    votesMin = o.optString("vmn"),
                    language = o.optString("lg"),
                    country = o.optString("ct"),
                    keywords = o.optString("kw"),
                    keywordsExclude = o.optString("kx"),
                    companies = o.optString("co"),
                    companiesExclude = o.optString("cx"),
                    networks = o.optString("nw"),
                    providers = o.optString("pv"),
                    providersExclude = o.optString("px"),
                    region = o.optString("rg"),
                )
            }.getOrNull()
        }
    }
}

/**
 * The network half of a TMDB source: id/URL parsing, name search, the display
 * name of a saved source, and one page of items.
 *
 * Every request goes through [TmdbResolver.apiGet], which means the app's
 * chosen title language rides along automatically — a Spanish install gets
 * Spanish titles out of a TMDB source with no work here.
 */
object TmdbSources {

    private const val IMG = "https://image.tmdb.org/t/p/w500"
    private const val IMG_WIDE = "https://image.tmdb.org/t/p/w780"

    /** Display names of named entities, so a saved source can title its row
     *  without the user having to type a Display title. */
    private val names = ConcurrentHashMap<String, String>()

    /**
     * Pulls a numeric id out of whatever the user typed: a bare id, or any
     * themoviedb.org URL ("https://www.themoviedb.org/company/420" → "420").
     * Null when there is no id to find (i.e. the text is a name to search for).
     */
    fun numericId(raw: String): String? {
        val t = raw.trim()
        if (t.isBlank()) return null
        if (t.all { it.isDigit() }) return t
        val m = Regex("(\\d{1,9})(?:[/?#]|$)").find(t) ?: Regex("(\\d{1,9})").find(t)
        return m?.groupValues?.get(1)
    }

    /** The row title of a saved source: the user's own Display title first, a
     *  name looked up from TMDB second, a readable fallback last. */
    suspend fun displayName(spec: TmdbSpec): String {
        if (spec.title.isNotBlank()) return spec.title
        if (spec.type == TmdbSourceType.PRESET) return TmdbPresets.nameOf(spec.preset)
        val id = numericId(spec.id)
        if (id != null) {
            // A TITLE source is one movie OR one show and the spec's media
            // decides which — but a source saved before its shape was known
            // (media "" / "all") is asked as both and answers with whichever
            // one exists.
            val endpoints = when (spec.type) {
                TmdbSourceType.TITLE -> if (spec.isTv) listOf("/tv/$id")
                    else if (spec.isMovie) listOf("/movie/$id")
                    else listOf("/movie/$id", "/tv/$id")
                else -> listOfNotNull(endpointFor(spec.type, id))
            }
            for (ep in endpoints) {
                names[ep]?.let { return it }
                val d = TmdbResolver.apiGet(ep, emptyMap())
                val n = d?.optString("name").orEmpty().ifBlank { d?.optString("title").orEmpty() }
                if (n.isNotBlank() && n != "null") {
                    names[ep] = n
                    return n
                }
            }
        }
        return fallbackName(spec)
    }

    /** Forget the looked-up row titles. They are localized by TMDB, so a change
     *  to the language TMDB answers in has to drop them or a saved source keeps
     *  its old-language name. See [com.hikari.app.HikariApp.onContentLanguageChanged]. */
    fun clearLocalizedNames() {
        names.clear()
    }

    /** A readable stand-in when TMDB has not (yet) answered with a name. */
    fun fallbackName(spec: TmdbSpec): String = when (spec.type) {
        TmdbSourceType.PRESET -> TmdbPresets.nameOf(spec.preset)
        TmdbSourceType.TITLE -> "Title"
        TmdbSourceType.LIST -> "TMDB list"
        TmdbSourceType.COLLECTION -> "TMDB collection"
        TmdbSourceType.COMPANY -> "Studio"
        TmdbSourceType.NETWORK -> "Network"
        TmdbSourceType.PERSON -> "Starring"
        TmdbSourceType.DIRECTOR -> "Directed by"
        TmdbSourceType.DISCOVER ->
            if (spec.isTv) "Series" else if (spec.isAll) "Movies & series" else "Movies"
    }

    /** The one-line caption of a saved source ("Network · Series", the way a
     *  preset already reads "Production · Movies · Popular"). */
    fun detail(spec: TmdbSpec): String {
        if (spec.type == TmdbSourceType.PRESET) {
            return TmdbPresets.byKey(spec.preset)?.detail ?: "Production · Movies · Popular"
        }
        // A one-title row has no "kind" worth printing twice — "Movie" or
        // "Series" is already the whole caption.
        if (spec.type == TmdbSourceType.TITLE) {
            return when {
                spec.isTv -> "Series"
                spec.isMovie -> "Movie"
                else -> "Movie or series"
            }
        }
        val kind = when (spec.type) {
            TmdbSourceType.PRESET -> "Preset"
            TmdbSourceType.TITLE -> "Title"
            TmdbSourceType.LIST -> "Public list"
            TmdbSourceType.COMPANY -> "Production"
            TmdbSourceType.NETWORK -> "Network"
            TmdbSourceType.COLLECTION -> "Collection"
            TmdbSourceType.PERSON -> "Person"
            TmdbSourceType.DIRECTOR -> "Director"
            TmdbSourceType.DISCOVER ->
                // A discover query that carries a genre IS a genre browse ("Custom
                // · Movies & series" told the viewer nothing); everything else is
                // a hand-built filter query.
                if (spec.genre > 0 || spec.genresText.isNotBlank()) "Genre" else "Custom"
        }
        val media = when {
            spec.isMovie -> "Movies"
            spec.isTv -> "Series"
            else -> "Movies & series"
        }
        return "$kind · $media"
    }

    /** The one-entity endpoint that carries a name for a source kind. */
    private fun endpointFor(type: TmdbSourceType, id: String): String? = when (type) {
        TmdbSourceType.COMPANY -> "/company/$id"
        TmdbSourceType.NETWORK -> "/network/$id"
        TmdbSourceType.COLLECTION -> "/collection/$id"
        TmdbSourceType.LIST -> "/list/$id"
        TmdbSourceType.PERSON, TmdbSourceType.DIRECTOR -> "/person/$id"
        else -> null
    }

    /** The URL of a saved source's own TMDB page, for the editor's "open". */
    fun webUrl(spec: TmdbSpec): String? {
        val id = numericId(spec.id) ?: return null
        val path = when (spec.type) {
            TmdbSourceType.COMPANY -> "company"
            TmdbSourceType.NETWORK -> "network"
            TmdbSourceType.COLLECTION -> "collection"
            TmdbSourceType.PERSON, TmdbSourceType.DIRECTOR -> "person"
            TmdbSourceType.LIST -> "list"
            TmdbSourceType.TITLE -> if (spec.isTv) "tv" else "movie"
            else -> return null
        }
        return "https://www.themoviedb.org/$path/$id"
    }

    /** The editor's Search button: a name → the ids it could mean. */
    suspend fun search(type: TmdbSourceType, query: String): List<TmdbHit> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        numericId(q)?.let { id ->
            // A one-title source: the typed id IS the title, and TMDB is asked
            // for both shapes so the hit can say which one it is (an id is
            // only unique per shape — 550 is Fight Club, 1399 is a show).
            if (type == TmdbSourceType.TITLE) {
                val hits = ArrayList<TmdbHit>()
                for (m in listOf("movie", "tv")) {
                    val d = TmdbResolver.apiGet("/$m/$id", emptyMap()) ?: continue
                    val n = d.optString("title").ifBlank { d.optString("name") }
                    if (n.isBlank() || n == "null") continue
                    hits.add(
                        TmdbHit(
                            id,
                            n,
                            if (m == "tv") "Series · ID $id" else "Movie · ID $id",
                            m,
                            posterUrl = path(d, "poster_path")?.let { IMG + it }.orEmpty(),
                        )
                    )
                }
                if (hits.isEmpty()) hits.add(TmdbHit(id, q, "ID $id"))
                return hits
            }
            val ep = endpointFor(type, id)
            val n = if (ep != null) {
                val d = TmdbResolver.apiGet(ep, emptyMap())
                d?.optString("name").orEmpty().ifBlank { d?.optString("title").orEmpty() }
            } else ""
            return listOf(TmdbHit(id, n.takeIf { it.isNotBlank() && it != "null" } ?: q, "ID $id"))
        }
        val path = when (type) {
            TmdbSourceType.COMPANY, TmdbSourceType.NETWORK -> "/search/company"
            TmdbSourceType.COLLECTION -> "/search/collection"
            TmdbSourceType.PERSON, TmdbSourceType.DIRECTOR -> "/search/person"
            TmdbSourceType.LIST -> "/search/list"
            // One query, both shapes: /search/multi answers with movies and
            // shows at once, which is exactly the "which one do you mean?"
            // question a one-title source is asking.
            TmdbSourceType.TITLE -> "/search/multi"
            else -> return emptyList()
        }
        val data = TmdbResolver.apiGet(path, mapOf("query" to q)) ?: return emptyList()
        val arr = data.optJSONArray("results") ?: return emptyList()
        val out = ArrayList<TmdbHit>()
        val multi = type == TmdbSourceType.PERSON || type == TmdbSourceType.DIRECTOR
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank() || id == "null") continue
            val name = o.optString("name").ifBlank { o.optString("title") }.trim()
            if (name.isBlank() || name == "null") continue
            val mediaType = o.optString("media_type")
            if (type == TmdbSourceType.TITLE && mediaType != "movie" && mediaType != "tv") continue
            val sub = when {
                type == TmdbSourceType.TITLE ->
                    if (mediaType == "tv") "Series" else "Movie"
                multi -> o.optString("known_for_department")
                else -> o.optString("origin_country")
            }
            out.add(
                TmdbHit(
                    id,
                    name,
                    sub.takeIf { it.isNotBlank() && it != "null" }.orEmpty(),
                    media = if (type == TmdbSourceType.TITLE) mediaType else "",
                    posterUrl = path(o, "poster_path")?.let { IMG + it }.orEmpty(),
                )
            )
        }
        return out
    }

    /** One page (20 items) of a saved source. Empty list on any failure — a
     *  source that cannot load simply leaves out its row. */
    suspend fun page(spec: TmdbSpec, page: Int = 1): List<MediaItem> {
        val p = page.coerceAtLeast(1)
        return when (spec.type) {
            TmdbSourceType.PRESET ->
                TmdbPresets.byKey(spec.preset)?.let { TmdbPresets.page(it, p) }.orEmpty()
            TmdbSourceType.TITLE -> title(spec)
            TmdbSourceType.LIST -> list(spec, p)
            TmdbSourceType.COLLECTION -> collection(spec)
            TmdbSourceType.PERSON -> person(spec, director = false)
            TmdbSourceType.DIRECTOR -> person(spec, director = true)
            TmdbSourceType.COMPANY -> if (spec.isAll) {
                // A studio makes films AND series, and TMDB has no endpoint that
                // answers for both — so the two discover pages are merged into
                // one, alternating, and neither kind can bury the other. This is
                // what "tap a production and see everything it made" needs.
                mergeMedia(
                    discover(spec, p, "movie", "with_companies"),
                    discover(spec, p, "tv", "with_companies"),
                )
            } else discover(spec, p, spec.media.ifBlank { "movie" }, "with_companies")
            TmdbSourceType.NETWORK -> discover(spec, p, "tv", "with_networks")
            TmdbSourceType.DISCOVER -> if (spec.isAll) {
                // "Everything tagged with this" is BOTH kinds: TMDB has no
                // endpoint that answers for films and series at once, so the two
                // discover pages are merged, alternating, exactly as a studio's
                // are. Sending the same genre filter to both is safe because a
                // genre carries the id EACH namespace knows it by (see Home's
                // genre strip) — TMDB ignores the id that belongs to the other
                // namespace rather than erroring, verified against the live API.
                mergeMedia(
                    discover(spec, p, "movie", null),
                    discover(spec, p, "tv", null),
                )
            } else discover(spec, p, spec.media.ifBlank { "movie" }, null)
        }
    }

    /**
     * A one-title source: the user picked THIS movie or THIS show, so the row
     * is that title and nothing else. The spec's media says which shape to ask
     * for; a spec with no shape yet is asked as a movie first and then as a
     * series, so an old or hand-written source still resolves.
     */
    private suspend fun title(spec: TmdbSpec): List<MediaItem> {
        val id = numericId(spec.id) ?: return emptyList()
        val order = when {
            spec.isTv -> listOf("tv")
            spec.isMovie -> listOf("movie")
            else -> listOf("movie", "tv")
        }
        for (m in order) {
            val data = TmdbResolver.apiGet("/$m/$id", emptyMap()) ?: continue
            val kind = if (m == "tv") MediaType.SERIES else MediaType.MOVIE
            item(data, kind)?.let { return listOf(it) }
        }
        return emptyList()
    }

    /**
     * Two media kinds' pages as one row, alternating so neither kind buries the
     * other. A company's own catalogue is the case: TMDB answers films and
     * series from two different endpoints, and one page of the grid has to carry
     * both.
     */
    private fun mergeMedia(a: List<MediaItem>, b: List<MediaItem>): List<MediaItem> {
        if (a.isEmpty()) return b
        if (b.isEmpty()) return a
        val out = ArrayList<MediaItem>(a.size + b.size)
        var i = 0
        var j = 0
        while (i < a.size || j < b.size) {
            if (i < a.size) out.add(a[i++])
            if (j < b.size) out.add(b[j++])
        }
        return out
    }

    private suspend fun list(spec: TmdbSpec, page: Int): List<MediaItem> {
        val id = numericId(spec.id) ?: return emptyList()
        val data = TmdbResolver.apiGet("/list/$id", mapOf("page" to page.toString()))
            ?: return emptyList()
        val arr = data.optJSONArray("items") ?: return emptyList()
        return collect(arr) { o ->
            val kind = if (o.optString("media_type") == "tv") MediaType.SERIES else MediaType.MOVIE
            if (!spec.matches(kind)) null else item(o, kind)
        }
    }

    private suspend fun collection(spec: TmdbSpec): List<MediaItem> {
        val id = numericId(spec.id) ?: return emptyList()
        val data = TmdbResolver.apiGet("/collection/$id", emptyMap()) ?: return emptyList()
        val arr = data.optJSONArray("parts") ?: return emptyList()
        // TMDB lists a collection's parts in release order; newest first reads
        // better as a shelf, and it is what a "series" of films looks like.
        return collect(arr) { item(it, MediaType.MOVIE) }.sortedByDescending { it.year ?: 0 }
    }

    private suspend fun person(spec: TmdbSpec, director: Boolean): List<MediaItem> {
        val id = numericId(spec.id) ?: return emptyList()
        val data = TmdbResolver.apiGet("/person/$id/combined_credits", emptyMap())
            ?: return emptyList()
        val arr = data.optJSONArray(if (director) "crew" else "cast") ?: return emptyList()
        val out = collect(arr) { o ->
            if (director && !o.optString("job").equals("Director", true)) return@collect null
            val mt = o.optString("media_type")
            if (mt != "movie" && mt != "tv") return@collect null
            val kind = if (mt == "tv") MediaType.SERIES else MediaType.MOVIE
            if (!spec.matches(kind)) return@collect null
            item(o, kind)
        }
        return out.sortedByDescending { it.year ?: 0 }
    }

    private suspend fun discover(
        spec: TmdbSpec,
        page: Int,
        seg: String,
        filterKey: String?,
    ): List<MediaItem> {
        val query = LinkedHashMap<String, String>()
        if (filterKey != null) {
            val id = numericId(spec.id) ?: return emptyList()
            query[filterKey] = id
        }
        // The genre FILTER text wins over the single-genre chip when both are
        // set: typed text can express what a chip cannot (several genres ANDed
        // with "," or ORed with "|", and an exclude list).
        if (spec.genresText.isNotBlank()) query["with_genres"] = spec.genresText.trim()
        else if (spec.genre > 0) query["with_genres"] = spec.genre.toString()
        if (spec.genresExclude.isNotBlank()) query["without_genres"] = spec.genresExclude.trim()
        // The date fields differ per endpoint — TMDB rejects a movie's release
        // field on /discover/tv — so the right pair is chosen for the segment
        // being asked.
        val dateGte = if (seg == "tv") "first_air_date.gte" else "primary_release_date.gte"
        val dateLte = if (seg == "tv") "first_air_date.lte" else "primary_release_date.lte"
        if (spec.dateFrom.isNotBlank()) query[dateGte] = spec.dateFrom.trim()
        if (spec.dateTo.isNotBlank()) query[dateLte] = spec.dateTo.trim()
        if (spec.ratingMin.isNotBlank()) query["vote_average.gte"] = spec.ratingMin.trim()
        if (spec.ratingMax.isNotBlank()) query["vote_average.lte"] = spec.ratingMax.trim()
        val votes = spec.votesMin.trim()
        if (votes.isNotBlank()) query["vote_count.gte"] = votes
        if (spec.language.isNotBlank()) {
            query["with_original_language"] = spec.language.trim().lowercase()
        }
        if (spec.country.isNotBlank()) {
            query["with_origin_country"] = spec.country.trim().uppercase()
        }
        // Keywords are IDs to TMDB, but a genre Home's strip has no genre id for
        // (an anime tag — see [Genres.keywordCandidates]) is expressible only as
        // a keyword NAME. [keywordsAsIds] resolves a name through TMDB's own
        // keyword search and answers the id list discover needs; a value that is
        // already a list of ids passes straight through, so the advanced
        // filter's numeric form is unchanged.
        //
        // A name that resolves to NOTHING stops the query instead of being
        // dropped: without the parameter the grid would come back as "every
        // title, by popularity", which reads exactly like a filter that was
        // applied. An empty row is honest; a wrong row is not. (Every tag in
        // [Genres.ANIME_TAGS] was checked against the live API, so this is the
        // belt to that pair of braces.)
        if (spec.keywords.isNotBlank()) {
            val ids = keywordsAsIds(spec.keywords) ?: return emptyList()
            query["with_keywords"] = ids
        }
        if (spec.keywordsExclude.isNotBlank()) {
            keywordsAsIds(spec.keywordsExclude)?.let { query["without_keywords"] = it }
        }
        // with_companies / with_networks are ALSO how a Production or Network
        // source names its own entity (see [filterKey]); an extra filter that
        // would overwrite that id is ignored rather than silently replacing the
        // studio with something else.
        if (spec.companies.isNotBlank() && filterKey != "with_companies") {
            query["with_companies"] = spec.companies.trim()
        }
        if (spec.companiesExclude.isNotBlank()) {
            query["without_companies"] = spec.companiesExclude.trim()
        }
        if (spec.networks.isNotBlank() && filterKey != "with_networks") {
            query["with_networks"] = spec.networks.trim()
        }
        if (spec.providers.isNotBlank()) {
            query["with_watch_providers"] = spec.providers.trim()
            if (spec.region.isNotBlank()) query["watch_region"] = spec.region.trim().uppercase()
        }
        if (spec.providersExclude.isNotBlank()) {
            query["without_watch_providers"] = spec.providersExclude.trim()
            if (spec.region.isNotBlank()) query["watch_region"] = spec.region.trim().uppercase()
        }
        if (spec.year > 0) {
            if (seg == "tv") query["first_air_date_year"] = spec.year.toString()
            else query["primary_release_year"] = spec.year.toString()
        }
        val sort = spec.sort.ifBlank { "popularity.desc" }
        query["sort_by"] = sort
        // TMDB's average-vote sort is meaningless without a floor on the number
        // of votes — without it the row fills with 10.0-rated films nobody saw.
        // A user-set floor is respected: it is their filter, not this guard.
        if (sort.startsWith("vote_average") && votes.isBlank()) query["vote_count.gte"] = "200"
        query["page"] = page.toString()
        val data = TmdbResolver.apiGet("/discover/$seg", query) ?: return emptyList()
        val arr = data.optJSONArray("results") ?: return emptyList()
        val kind = if (seg == "tv") MediaType.SERIES else MediaType.MOVIE
        return collect(arr) { item(it, kind) }
    }

    /** True when [kind] survives the source's own movie/tv filter. */
    private fun TmdbSpec.matches(kind: MediaType): Boolean = when {
        isMovie -> kind == MediaType.MOVIE
        isTv -> kind == MediaType.SERIES
        else -> true
    }

    /** Resolved keyword name (lowercased) → TMDB keyword id. */
    private val keywordIds = ConcurrentHashMap<String, String>()

    /** Keyword names TMDB had no id for, so a miss is not re-asked per page. */
    private val keywordMisses = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * A `with_keywords` value as the id list TMDB needs: numeric tokens are
     * passed through, a NAME is resolved through TMDB's `/search/keyword`
     * (`"isekai" → 210024`, `"high school" → …`) and the ids are OR-ed
     * together. Null when nothing resolved, so the caller sends no parameter
     * instead of an empty one.
     *
     * The resolution is cached for the process (hits and misses), because it is
     * asked once per page of a grid — a keyword search per page for a chip the
     * user is scrolling would be a request per scroll.
     */
    private suspend fun keywordsAsIds(raw: String): String? {
        val tokens = raw.split('|', ',', '+', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        val out = LinkedHashSet<String>()
        for (token in tokens) {
            if (token.all { it.isDigit() }) {
                out += token
                continue
            }
            resolveKeywordId(token)?.let { out += it }
        }
        return out.joinToString(",").takeIf { it.isNotBlank() }
    }

    /** One keyword name → its TMDB id, or null when TMDB has no such keyword. */
    private suspend fun resolveKeywordId(name: String): String? {
        val key = name.trim().lowercase()
        if (key.isBlank()) return null
        keywordIds[key]?.let { return it }
        if (key in keywordMisses) return null
        val data = TmdbResolver.apiGet("/search/keyword", mapOf("query" to key)) ?: return null
        val arr = data.optJSONArray("results") ?: return null
        var exact: String? = null
        var loose: String? = null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank()) continue
            val hit = o.optString("name").trim().lowercase()
            if (hit == key) {
                exact = id
                break
            }
            // TMDB's keyword search is fuzzy ("school life" also answers
            // "school"), so a non-exact hit is only used when it CONTAINS the
            // whole query — never a partial-word match.
            if (loose == null && hit.contains(key)) loose = id
        }
        val found = exact ?: loose
        if (found == null) {
            // Bound the miss set: a burst of odd names must not grow it forever.
            if (keywordMisses.size > 256) keywordMisses.clear()
            keywordMisses += key
            return null
        }
        keywordIds[key] = found
        return found
    }

    private fun collect(
        arr: org.json.JSONArray,
        build: (JSONObject) -> MediaItem?,
    ): List<MediaItem> {
        val out = ArrayList<MediaItem>(arr.length())
        val seen = HashSet<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val built = runCatching { build(o) }.getOrNull() ?: continue
            if (seen.add(built.uniqueId)) out.add(built)
        }
        return out
    }

    /** Builds the app's item shape from a TMDB object. Entries with no art are
     *  dropped: an artless cell reads as a hole in the grid. */
    private fun item(o: JSONObject, type: MediaType): MediaItem? {
        val id = o.optString("id")
        if (id.isBlank() || id == "null") return null
        val title = o.optString("title").ifBlank { o.optString("name") }.trim()
        if (title.isBlank() || title == "null") return null
        val poster = path(o, "poster_path")?.let { IMG + it }
        val backdrop = path(o, "backdrop_path")?.let { IMG_WIDE + it }
        if (poster == null && backdrop == null) return null
        return MediaItem(
            providerId = "tmdb",
            id = id,
            title = title,
            type = type,
            posterUrl = poster,
            year = year(o),
            overview = o.optString("overview").takeIf { it.isNotBlank() && it != "null" },
            backdropUrl = backdrop,
            rawType = "tmdb",
            rating = o.optDouble("vote_average", 0.0).takeIf { it > 0.0 },
            // The name the extensions index this title under, when the app's
            // TMDB language has renamed it (see MediaItem.originalTitle).
            originalTitle = originalOf(o),
            // TMDB's own adult marker (see [MediaItem.nsfw]) — the one signal a
            // catalogue response carries, and what the NSFW gate hides when the
            // user has turned adult material off.
            nsfw = o.optBoolean("adult", false),
        )
    }

    /** TMDB's own `original_title` / `original_name` — present in every
     *  response whatever language the title came back in. */
    private fun originalOf(o: JSONObject): String {
        val t = o.optString("original_title").ifBlank { o.optString("original_name") }.trim()
        return if (t.isBlank() || t == "null") "" else t
    }

    private fun path(o: JSONObject, key: String): String? =
        o.optString(key).takeIf { it.isNotBlank() && it != "null" }

    private fun year(o: JSONObject): Int? {
        val raw = o.optString("release_date").ifBlank { o.optString("first_air_date") }
        return raw.take(4).toIntOrNull()?.takeIf { it > 1800 }
    }
}

/**
 * App language → TMDB language, for translating titles and overviews.
 *
 * The user's app language is an Android tag; TMDB wants its own region-tagged
 * code, and the two disagree exactly where it matters (the app's `zh-CN` is
 * TMDB's `zh-CN`, but its `pt-BR` is TMDB's `pt-BR` while its plain `es` is
 * TMDB's `es-ES`). A tag with no entry simply leaves TMDB on its default.
 */
object TmdbLang {

    /** App language → TMDB code. */
    private val APP_TO_TMDB = mapOf(
        "es" to "es-ES",
        "pt-BR" to "pt-BR",
        "fr" to "fr-FR",
        "de" to "de-DE",
        "it" to "it-IT",
        "ru" to "ru-RU",
        "uk" to "uk-UA",
        "tr" to "tr-TR",
        "ar" to "ar-SA",
        "hi" to "hi-IN",
        "id" to "id-ID",
        "vi" to "vi-VN",
        "th" to "th-TH",
        "ko" to "ko-KR",
        "ja" to "ja-JP",
        "zh-CN" to "zh-CN",
        "pl" to "pl-PL",
        "nl" to "nl-NL",
        "el" to "el-GR",
        "he" to "he-IL",
        "fa" to "fa-IR",
        "bn" to "bn-BD",
        "ta" to "ta-IN",
        "te" to "te-IN",
        "ml" to "ml-IN",
        "ms" to "ms-MY",
        "fil" to "fil-PH",
        "sv" to "sv-SE",
        "no" to "no-NO",
        "da" to "da-DK",
        "fi" to "fi-FI",
        "cs" to "cs-CZ",
        "hu" to "hu-HU",
        "ro" to "ro-RO",
        "bg" to "bg-BG",
        "sr" to "sr-RS",
        "hr" to "hr-HR",
        "sk" to "sk-SK",
        "sl" to "sl-SI",
        "lt" to "lt-LT",
        "lv" to "lv-LV",
        "et" to "et-EE",
        "ca" to "ca-ES",
        "eu" to "eu-ES",
        "gl" to "gl-ES",
        "is" to "is-IS",
        "sq" to "sq-AL",
        "mk" to "mk-MK",
        "ka" to "ka-GE",
        "hy" to "hy-AM",
        "az" to "az-AZ",
        "kk" to "kk-KZ",
        "uz" to "uz-UZ",
        "sw" to "sw-KE",
        "am" to "am-ET",
        "zu" to "zu-ZA",
        "af" to "af-ZA",
        "ne" to "ne-NP",
        "si" to "si-LK",
        "km" to "km-KH",
        "lo" to "lo-LA",
        "my" to "my-MM",
        "mn" to "mn-MN",
        "ur" to "ur-PK",
        "mr" to "mr-IN",
        "gu" to "gu-IN",
        "kn" to "kn-IN",
        "pa" to "pa-IN",
        "or" to "or-IN",
        "as" to "as-IN",
    )

    /** Explicit choices the Settings picker offers. */
    val CHOICES: List<Pair<String, String>> = listOf(
        "es-ES" to "Español (Spanish)",
        "pt-BR" to "Português (Brasil)",
        "pt-PT" to "Português (Portugal)",
        "fr-FR" to "Français",
        "de-DE" to "Deutsch",
        "it-IT" to "Italiano",
        "ru-RU" to "Русский",
        "uk-UA" to "Українська",
        "tr-TR" to "Türkçe",
        "pl-PL" to "Polski",
        "nl-NL" to "Nederlands",
        "sv-SE" to "Svenska",
        "cs-CZ" to "Čeština",
        "el-GR" to "Ελληνικά",
        "he-IL" to "עברית",
        "ar-SA" to "العربية",
        "fa-IR" to "فارسی",
        "hi-IN" to "हिन्दी",
        "ta-IN" to "தமிழ்",
        "te-IN" to "తెలుగు",
        "bn-BD" to "বাংলা",
        "id-ID" to "Bahasa Indonesia",
        "ms-MY" to "Bahasa Melayu",
        "vi-VN" to "Tiếng Việt",
        "th-TH" to "ไทย",
        "ko-KR" to "한국어",
        "ja-JP" to "日本語",
        "zh-CN" to "简体中文",
        "zh-TW" to "繁體中文",
        "en-US" to "English",
    )

    /** The TMDB code for an app language tag, or "" when there is no mapping. */
    fun forAppLanguage(tag: String?): String {
        val t = tag.orEmpty().trim()
        if (t.isBlank()) return ""
        APP_TO_TMDB[t]?.let { return it }
        val base = t.substringBefore('-')
        val region = t.substringAfter('-', "")
        return APP_TO_TMDB[base]
            ?: APP_TO_TMDB.entries.firstOrNull {
                it.key.substringBefore('-') == base &&
                    (region.isBlank() || it.key.endsWith("-$region", true))
            }?.value
            ?: ""
    }
}
