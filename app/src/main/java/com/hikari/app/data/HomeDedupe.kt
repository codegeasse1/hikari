package com.hikari.app.data

object HomeDedupe {

    /** The engines whose catalogues are the same catalogue. */
    private val ENGINES = setOf(ProviderType.NUVIO, ProviderType.STREMIO)

    private val BARE_TMDB_ID = Regex("""^\d+$""")
    private val IMDB_ID = Regex("""^tt(\d+)$""", RegexOption.IGNORE_CASE)
    private val PREFIXED_TMDB_ID = Regex("""^tmdb:(\d+)(?::([a-z]+))?$""")

    /** Whether [type] is an engine this collapse is for. */
    fun isEngine(type: ProviderType?): Boolean = type != null && type in ENGINES

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
