package com.hikari.app.data

/**
 * Collapses a title that several ENGINES bring to the same Home feed.
 *
 * With the "All providers" pick, every enabled extension fills Home, and two of
 * them can be looking at the same catalogue of the same thing: a Nuvio engine
 * reads TMDB itself, and a Stremio addon (Cinemeta/TMDB-based catalogues,
 * Torrentio's own rows) hands back the same films and series under its own ids.
 * The feed then carried the same title two or three times — "do that duplicate
 * thing for nuvio and stremio when select all provider" — which is not only
 * noise: the rows below are pushed off the screen and a title the user has
 * already seen looks like a different one.
 *
 * **Why the identity is what it is.** Nuvio items are keyed by TMDB id
 * (`NuvioScraper.fromTmdbRow`), and a Stremio addon keys its items by ITS own id
 * — in practice an IMDb id (`tt…`), and only sometimes a TMDB one. So an id
 * match alone can never join the two, and a fuzzy title match would delete real
 * titles (a remake shares its name with the original; two films in one year do
 * share a name that is a single common word). The rule here is therefore:
 *
 *  * an id key when the item carries a TMDB id or an IMDb id — that one collapses
 *    rows of the SAME engine whose catalogues overlap, and the rare addon that
 *    speaks TMDB ids into a Nuvio row;
 *  * otherwise the normalized name PLUS the year PLUS the kind. The year is
 *    required on both sides: an item with no year is never matched by name, so a
 *    missing `releaseInfo` can only cost a duplicate, never a title. It is also
 *    never matched when [TmdbMeta.normalizeTitle] has nothing left to compare —
 *    a CJK title normalizes away entirely, and "no name" must not mean "any
 *    name".
 *
 * **Who takes part.** Only [ProviderType.NUVIO] and [ProviderType.STREMIO] rows,
 * and only against each other. Scraper engines (Hikari, CloudStream, SkyStream,
 * Aniyomi) index different sites and hand back the same film under a dozen
 * different ids, and their copies are the point of the multi-pick — they are the
 * servers the title is watched through, not duplicates of a catalogue. A manga
 * or IPTV row is never touched either. Rows are walked in the order Home places
 * them ([ContentRepository.homeRowsStreamingWhere]), so the first engine to
 * carry a title keeps it, the row it was repeated in keeps its other cards (and
 * disappears only when it was nothing BUT repeats), and the result is stable:
 * the same placement order always gives the same feed, so nothing jumps when a
 * slow provider's row lands late.
 *
 * **The adult gate.** A key is only claimed by an item that may actually be
 * drawn ([NsfwGate.allows]). Otherwise a hidden copy of a title — one whose
 * provider tagged it adult — would claim the identity and silently delete the
 * visible copy another engine has, and a film the user is allowed to watch would
 * be missing from a feed that never even offered it.
 */
object HomeDedupe {

    /** The engines whose catalogues are the same catalogue. */
    private val ENGINES = setOf(ProviderType.NUVIO, ProviderType.STREMIO)

    private val BARE_TMDB_ID = Regex("""^\d+$""")
    private val IMDB_ID = Regex("""^tt(\d+)$""", RegexOption.IGNORE_CASE)
    private val PREFIXED_TMDB_ID = Regex("""^tmdb:(\d+)(?::([a-z]+))?$""")

    /** Whether [type] is an engine this collapse is for. */
    fun isEngine(type: ProviderType?): Boolean = type != null && type in ENGINES

    /**
     * Every identity [item] may be recognised by, most specific first. Empty for
     * an item that carries no usable id and no year — such an item can never be
     * collapsed, which is the safe answer.
     */
    fun keysOf(item: MediaItem): List<String> {
        val out = ArrayList<String>(4)
        val id = item.id.trim()
        val kind = if (item.type == MediaType.SERIES) "tv" else "movie"
        if (BARE_TMDB_ID.matches(id)) out += "tmdb:$id:$kind"
        IMDB_ID.find(id)?.let { out += "imdb:${it.groupValues[1]}" }
        PREFIXED_TMDB_ID.find(id.lowercase())?.let { m ->
            val raw = m.groupValues.getOrNull(2).orEmpty()
            val kindOfId = when {
                raw.startsWith("tv") || raw.startsWith("series") -> "tv"
                raw.startsWith("movie") -> "movie"
                else -> kind
            }
            out += "tmdb:${m.groupValues[1]}:$kindOfId"
        }
        val year = item.year
        if (year != null) {
            val name = TmdbMeta.normalizeTitle(item.searchTitle)
            if (name.isNotBlank()) out += "t:$name|$year|$kind"
        }
        return out
    }

    /**
     * [rows] with the copies a later engine row repeats removed. [types] maps a
     * provider id to its engine kind, which a [CatalogRow] does not carry (it
     * only knows its provider's id and the catalog's own media type).
     *
     * The input list is returned unchanged when nothing was collapsed, so the
     * feed's own "did this snapshot change?" comparison keeps working.
     */
    fun apply(rows: List<CatalogRow>, types: Map<String, ProviderType>): List<CatalogRow> {
        if (rows.size < 2) return rows
        var collapsed = 0
        val claimed = HashSet<String>()
        val out = ArrayList<CatalogRow>(rows.size)
        for (row in rows) {
            if (!isEngine(types[row.providerId])) {
                out += row
                continue
            }
            val kept = ArrayList<MediaItem>(row.items.size)
            for (item in row.items) {
                val keys = keysOf(item)
                if (keys.any { it in claimed }) {
                    collapsed++
                    continue
                }
                if (NsfwGate.allows(item)) claimed.addAll(keys)
                kept += item
            }
            when {
                // Every card in it was already on screen above: the row goes,
                // rather than being drawn as an empty shelf.
                kept.isEmpty() -> Unit
                kept.size == row.items.size -> out += row
                else -> out += row.copy(items = kept)
            }
        }
        return if (collapsed == 0) rows else out
    }
}
