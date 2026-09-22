package com.hikari.app.data

/**
 * The one place the app decides whether adult material may be shown.
 *
 * **What the switch means.** Settings → Content → NSFW. ON (the default) is the
 * app exactly as it has always been: every installed extension is listed and every
 * title a provider returns is shown. OFF, the app stops being a way to run into
 * that material at all: extensions whose own metadata is tagged 18+ (Tachiyomi's
 * `tachiyomi.extension.nsfw` / `tachiyomi.animeextension.nsfw`, and CloudStream's
 * `CONTENT_WARNING_NSFW`) disappear from every list the user picks from, and
 * titles that are themselves adult are filtered out of every catalogue, shelf,
 * search result and grid before they are drawn — including the ones the user
 * already has in Library or History.
 *
 * **Why the filter runs at DRAW time instead of in the stores.** A title can carry
 * its adult marker in any of half a dozen places (the provider's own response, a
 * TMDB `adult` flag, a genre tag, the name itself), and those places disagree. The
 * one thing every screen has in common is the moment it turns a list of items into
 * cards — so the gate is applied there, as the last step before drawing. That also
 * makes the switch instant and complete: nothing has to be re-fetched or
 * re-cached, and no screen can "remember" a row of adult titles from before the
 * switch was flipped (a stale cache is exactly how a filtered app still shows the
 * thing the user asked it not to).
 *
 * **What counts as adult.** Deliberately specific, because an over-eager rule
 * silently deletes a library:
 *
 *  * an explicit marker from the source — TMDB's `adult`, or a provider whose own
 *    type/metadata says so ([MediaItem.nsfw]);
 *  * an 18+/adult GENRE or tag (see [ADULT_WORDS]) — how manga and extension
 *    catalogues actually mark this material;
 *  * an 18+/adults-only age rating where the app knows one (see [ADULT_RATINGS]).
 *
 * The user named "adult R-rated" as what they want hidden, so the film certificates
 * the trade calls adults-only are in the rating set — `R`, `NC-17`, `X`, `18`,
 * `R18+` and their spellings. It is intentionally NOT "anything a rating body
 * called unsuitable for children": TV-MA is the television equivalent of an R and
 * covers most prestige drama, and hiding every TV-MA show would empty the shelves
 * of someone who only meant to turn off adult material.
 *
 * This object holds the flag as a plain field that the UI reads synchronously
 * (drawing cannot wait for a Flow), kept in step with the stored preference by a
 * single collect launched from the app's own start-up in [com.hikari.app.HikariApp]
 * — the same shape the other app-wide caches use.
 */
object NsfwGate {

    /**
     * Words that mark an item as adult when they appear as a GENRE or TAG.
     *
     * Kept to terms that mean the material itself is adult. Borderline genres are
     * deliberately absent: "Ecchi" is fanservice in a mainstream shonen title as
     * often as it is adult material, and hiding a whole genre on an ambiguous word
     * is how a filter turns into a bug report.
     */
    private val ADULT_WORDS = setOf(
        "adult", "18+", "r-18", "r18", "hentai", "porn", "pornography", "xxx",
        "erotica", "erotic", "jav", "smut", "nsfw", "18 plus",
    )

    /**
     * Age ratings that mean adults only. Normalized (uppercase, no spaces, no
     * "+"/"-") before lookup, so "NC-17", "NC17", "R-18", "R18+" and "18+" all
     * land. See the class doc for why this is not simply "not for children".
     */
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

    /**
     * True when the given title/genres LOOK adult (see [ADULT_WORDS]).
     *
     * A pure predicate about the text — it deliberately ignores the switch, so
     * callers that already know the switch's state (a row that filters its own
     * list against it, and [filter]) can combine the two without the rule
     * silently answering "no" for the very state it is being asked about. The
     * switch-aware form is [isAdult].
     */
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

    /**
     * True when the age rating [certification] ("R", "NC-17", "TV-MA", "FSK 16",
     * "12A"…) means adults only (see [ADULT_RATINGS]).
     *
     * Called from the detail screen, where a title's real certificate is known —
     * it is the only place that has one (a catalogue row carries no rating). Like
     * [isAdult] it answers "would the gate hide this?", so it is false whenever
     * the switch is on.
     */
    fun isAdultRating(certification: String?): Boolean {
        if (enabled) return false
        val raw = certification?.trim()?.uppercase() ?: return false
        if (raw.isEmpty()) return false
        val compact = raw.filter { it.isLetterOrDigit() }
        return ADULT_RATINGS.contains(compact)
    }

    /**
     * The items of a list that may be shown, in order.
     *
     * The whole rule for one item, in one expression, and it is deliberately
     * spelled out here rather than routed through [isAdult]: that method answers
     * "is this HIDDEN?", which is false whenever the switch is on, so using it as
     * the per-item test inside the switch-off branch would keep everything.
     */
    fun filter(items: List<MediaItem>): List<MediaItem> =
        if (enabled) items
        else items.filter {
            !it.nsfw &&
                !isAdultText(it.title, it.genres) &&
                !isAdultText(it.originalTitle, emptyList())
        }

    /**
     * The rows of a shelf that may be shown, in order.
     *
     * A row whose every item was removed goes too: an empty shelf titled "New
     * releases" is worse than no shelf, and the rows are drawn as a header plus a
     * strip, so a row with no cards under it reads as a loading bug.
     */
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

    /**
     * The certificate a FILM catalogue query may return while the switch is off.
     *
     * Asked of TMDB itself, not applied to the answer, because a catalogue row
     * carries no certificate to apply it to: a /discover/movie item is a title, a
     * poster and a year, and the rating lives behind a second request per title
     * that no row can afford. TMDB's own `certification.lte` filter is therefore
     * the only way to keep an R-rated film out of a row — and every row, browse
     * grid, preset and Production/Network/Person catalogue in this app IS a
     * discover query (see [com.hikari.app.data.TmdbSources] and
     * [com.hikari.app.data.TmdbBrowse]).
     *
     * PG-13, and not `R`: the filter is "less than or equal", so asking for R would
     * keep exactly what the user asked to hide. TV is deliberately NOT capped — see
     * the class doc for why TV-MA is not in [ADULT_RATINGS], and a cap that removed
     * TV-MA would empty the shelves of someone who only meant to switch off adult
     * material.
     *
     * The cost is real and is the reason this is the LAST resort rather than the
     * first line: TMDB will not match a film it has no US certificate for, so a
     * niche row (a small country's cinema, a straight-to-streaming release) comes
     * back shorter than it would with the switch on. That is a shelf with fewer
     * cards on it, which is the trade the switch was asking for.
     */
    private const val DISCOVER_MOVIE_CEILING = "PG-13"

    /**
     * Adds TMDB's certification ceiling to a discover query while the switch is
     * off, so an R-rated film cannot reach a row at all (see
     * [DISCOVER_MOVIE_CEILING]).
     *
     * Called from the one place every TMDB request passes through
     * ([com.hikari.app.nuvio.TmdbResolver.apiGet]), so no caller has to remember
     * it — including callers written later. A query that names its own
     * certification is left alone: that is the user stating what they want, and
     * the switch is not a licence to override it.
     */
    fun capDiscoverCertification(path: String, params: MutableMap<String, String>) {
        if (enabled) return
        if (!path.startsWith("/discover/movie")) return
        if (params.containsKey("certification.lte") || params.containsKey("certification")) return
        params["certification_country"] = "US"
        params["certification.lte"] = DISCOVER_MOVIE_CEILING
    }
}
