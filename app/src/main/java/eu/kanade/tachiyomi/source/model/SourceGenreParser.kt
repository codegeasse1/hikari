package eu.kanade.tachiyomi.source.model

internal fun parseSourceGenres(genre: String?): List<String>? {
    if (genre.isNullOrBlank()) return null

    val tokens = genre
        .split(Regex("[,;/|\\n\\r\\t•·]+"))
        .map {
            it.trim()
                .trim('-', '–', '—', ',', ';', '/', '|', '•', '·')
        }
        .filter { it.isNotBlank() }

    if (tokens.isEmpty()) return null

    val seen = LinkedHashSet<String>()
    return buildList {
        tokens.forEach { token ->
            val dedupeKey = token.lowercase()
            if (seen.add(dedupeKey)) {
                add(token)
            }
        }
    }.ifEmpty { null }
}
