package com.hikari.app.data

object NsfwGate {

    private val ADULT_WORDS = setOf(
        "adult", "18+", "r-18", "r18", "hentai", "porn", "pornography", "xxx",
        "erotica", "erotic", "jav", "smut", "nsfw", "18 plus",
    )

    private val ADULT_RATINGS = setOf(
        "R", "NC17", "X", "XXX", "18", "R18", "X18", "A18", "MA18", "ADULTSONLY",
    )

    /** Show adult material? ON unless the user turned it off (see the class doc). */
    @Volatile
    private var on: Boolean = true

    /** The switch, read synchronously — this is on the draw path. */
    val enabled: Boolean get() = on

    /** Mirrors the stored preference into [on]; called from the app's start-up
     *  collect (see the class doc). */
    fun setEnabled(value: Boolean) {
        on = value
    }

    /** True when [item] is adult material the gate hides — i.e. only while NSFW
     *  is off. With the switch on this is always false: nothing is hidden. */
    fun isAdult(item: MediaItem): Boolean {
        if (enabled) return false
        return item.nsfw || isAdultText(item.title, item.genres) ||
            isAdultText(item.originalTitle, emptyList())
    }

    fun isAdultText(title: String?, genres: List<String>): Boolean {
        for (g in genres) if (ADULT_WORDS.contains(g.trim().lowercase())) return true
        // The NAME is checked too, and only as a whole word: an extension catalogue
        // for this material almost always says so in the title ("… Hentai", "JAV …",
        // "XXX …"), and a substring test would eat innocent titles ("Adult Swim",
        // "Teenage Mutant Ninja Turtles" contains "ninja"? no — but "Adaptation"
        // contains "adult").
        val words = title?.lowercase()?.split(' ', '-', '_', ':', '|', '/', '(', ')', '[', ']')
        if (words != null) {
            for (w in words) {
                val t = w.trim().trim(',', '.', '\'', '"')
                if (t.isNotEmpty() && ADULT_WORDS.contains(t)) return true
            }
        }
        return false
    }

    fun isAdultRating(certification: String?): Boolean {
        if (enabled) return false
        val raw = certification?.trim()?.uppercase() ?: return false
        if (raw.isEmpty()) return false
        val compact = raw.filter { it.isLetterOrDigit() }
        return ADULT_RATINGS.contains(compact)
    }

    fun filter(items: List<MediaItem>): List<MediaItem> =
        if (enabled) items else items.filter { allows(it) }

    fun allows(item: MediaItem): Boolean =
        enabled ||
            (!item.nsfw &&
                !isAdultText(item.title, item.genres) &&
                !isAdultText(item.originalTitle, emptyList()))

    fun filterRows(rows: List<CatalogRow>): List<CatalogRow> {
        if (enabled) return rows
        val out = ArrayList<CatalogRow>(rows.size)
        for (row in rows) {
            val kept = filter(row.items)
            when {
                kept.isEmpty() -> Unit
                kept.size == row.items.size -> out.add(row)
                else -> out.add(row.copy(items = kept))
            }
        }
        return out
    }

    private const val DISCOVER_MOVIE_CEILING = "PG-13"

    private val MOVIE_LIST_AS_DISCOVER = mapOf(
        "/movie/popular" to "popularity.desc",
        "/movie/top_rated" to "vote_average.desc",
        "/movie/now_playing" to "primary_release_date.desc",
        "/movie/upcoming" to "primary_release_date.desc",
        "/trending/all/day" to "popularity.desc",
        "/trending/all/week" to "popularity.desc",
        "/trending/movie/day" to "popularity.desc",
        "/trending/movie/week" to "popularity.desc",
    )

    fun restrictRequest(path: String, params: MutableMap<String, String>): String {
        if (enabled) return path
        if (path.startsWith("/discover/movie")) {
            if (params.containsKey("certification.lte") || params.containsKey("certification")) {
                return path
            }
            params["certification_country"] = "US"
            params["certification.lte"] = DISCOVER_MOVIE_CEILING
            return path
        }
        val sort = MOVIE_LIST_AS_DISCOVER[path] ?: return path
        params["sort_by"] = sort
        params["certification_country"] = "US"
        params["certification.lte"] = DISCOVER_MOVIE_CEILING
        // A "top rated" list is only meaningful with a floor under it: discover's
        // own vote-average ordering without one is a wall of one-vote titles.
        if (sort.startsWith("vote_average")) params["vote_count.gte"] = "300"
        return "/discover/movie"
    }

    fun rewritesToMovies(path: String): Boolean =
        !enabled && MOVIE_LIST_AS_DISCOVER.containsKey(path)
}
