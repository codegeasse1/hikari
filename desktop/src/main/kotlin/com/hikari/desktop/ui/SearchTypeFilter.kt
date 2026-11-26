package com.hikari.desktop.ui

import com.hikari.desktop.data.AddonTitle
import java.net.URI

internal enum class SearchTypeFilter(val label: String) {
    ALL("Semua"),
    MOVIES("Film"),
    SERIES("Serial"),
    ANIME("Anime"),
}

internal data class SearchFilters(
    val type: SearchTypeFilter = SearchTypeFilter.ALL,
    val genres: Set<String> = emptySet(),
    val years: Set<Int> = emptySet(),
    val minimumRating: Double? = null,
) {
    val isActive: Boolean
        get() = type != SearchTypeFilter.ALL || genres.isNotEmpty() || years.isNotEmpty() || minimumRating != null
}

internal enum class SearchResultsPresentation {
    QUERY_REQUIRED,
    RESULTS,
    NO_MATCH,
    PARTIAL_FAILURE_WITHOUT_MATCH,
    SOURCE_FAILURE_WITHOUT_MATCH,
}

internal fun searchResultsPresentation(
    query: String,
    results: List<AddonTitle>,
    error: String?,
    partialFailure: Boolean,
): SearchResultsPresentation = when {
    query.isBlank() -> SearchResultsPresentation.QUERY_REQUIRED
    results.isNotEmpty() -> SearchResultsPresentation.RESULTS
    error != null && partialFailure -> SearchResultsPresentation.PARTIAL_FAILURE_WITHOUT_MATCH
    error != null -> SearchResultsPresentation.SOURCE_FAILURE_WITHOUT_MATCH
    else -> SearchResultsPresentation.NO_MATCH
}

internal fun filterSearchResults(
    results: List<AddonTitle>,
    filters: SearchFilters,
): List<AddonTitle> {
    if (!filters.isActive) return results

    val selectedGenres = filters.genres.mapNotNull(::normalizeGenre).toSet()
    return results.filter { title ->
        val anime = title.looksLikeAnime()
        val matchesType = when (filters.type) {
            SearchTypeFilter.ALL -> true
            SearchTypeFilter.MOVIES -> title.type.isMovieType() && !anime
            SearchTypeFilter.SERIES -> title.type.isSeriesType() && !anime
            SearchTypeFilter.ANIME -> anime
        }
        val matchesYear = filters.years.isEmpty() || title.yearForFiltering() in filters.years
        val matchesGenre = selectedGenres.isEmpty() || title.genres.any { genre ->
            normalizeGenre(genre) in selectedGenres
        }
        val matchesRating = filters.minimumRating == null || title.rating == null ||
            title.rating >= filters.minimumRating

        matchesType && matchesYear && matchesGenre && matchesRating
    }
}

internal fun availableSearchGenres(results: List<AddonTitle>): List<String> =
    results.flatMap { it.genres }
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinctBy(String::lowercase)
        .sortedBy(String::lowercase)

internal fun availableSearchYears(results: List<AddonTitle>): List<Int> =
    results.mapNotNull { it.yearForFiltering() }.distinct().sortedDescending()

private fun AddonTitle.looksLikeAnime(): Boolean {
    if (type.equals("anime", ignoreCase = true)) return true
    if (genres.any { genre ->
            val normalized = normalizeGenre(genre).orEmpty()
            normalized.contains("anime")
        }) return true

    val source = runCatching {
        val uri = URI(addonUrl)
        "${uri.host.orEmpty()} ${uri.path.orEmpty()}"
    }.getOrDefault(addonUrl).lowercase()
    return source.contains("anime")
}

private fun AddonTitle.yearForFiltering(): Int? = year ?: Regex("(19|20)\\d{2}")
    .find(releaseInfo.orEmpty())?.value?.toIntOrNull()

private fun String.isMovieType(): Boolean = equals("movie", ignoreCase = true) || equals("film", ignoreCase = true)

private fun String.isSeriesType(): Boolean =
    equals("series", ignoreCase = true) || equals("tv", ignoreCase = true) || equals("show", ignoreCase = true)

private fun normalizeGenre(value: String): String? = value.trim().lowercase().takeIf(String::isNotEmpty)
