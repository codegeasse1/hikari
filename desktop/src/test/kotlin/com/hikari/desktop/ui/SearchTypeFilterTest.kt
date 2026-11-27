package com.hikari.desktop.ui

import com.hikari.desktop.data.AddonTitle
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchTypeFilterTest {
    private val addon = "https://catalog.example/manifest.json"
    private val movie = title(id = "film", type = "movie", year = 2024, rating = 7.4)
    private val series = title(id = "show", type = "series", year = 2021, genres = listOf("Drama"), rating = 8.2)
    private val anime = title(id = "anime", type = "series", genres = listOf(" Anime "), rating = 8.8)
    private val explicitAnime = title(id = "anime-type", type = "anime", year = 2023)
    private val unscored = title(id = "unscored", type = "series", genres = listOf("Comedy"))
    private val results = listOf(movie, series, anime, explicitAnime, unscored)

    @Test
    fun `all keeps results from every Stremio media type in their original order`() {
        assertEquals(results, filterSearchResults(results, SearchFilters()))
    }

    @Test
    fun `movies excludes anime even when an add-on reports it as a movie`() {
        val animeMovie = title(id = "anime-film", type = "movie", genres = listOf("Anime"))
        assertEquals(listOf(movie), filterSearchResults(listOf(movie, animeMovie), SearchFilters(type = SearchTypeFilter.MOVIES)))
    }

    @Test
    fun `series excludes anime`() {
        assertEquals(listOf(series, unscored), filterSearchResults(results, SearchFilters(type = SearchTypeFilter.SERIES)))
    }

    @Test
    fun `anime includes declared anime types and anime genres`() {
        assertEquals(listOf(anime, explicitAnime), filterSearchResults(results, SearchFilters(type = SearchTypeFilter.ANIME)))
    }

    @Test
    fun `general animation genre is not enough to classify a title as anime`() {
        val animatedMovie = title(id = "animation", type = "movie", genres = listOf("Animation"))

        assertEquals(emptyList<AddonTitle>(), filterSearchResults(listOf(animatedMovie), SearchFilters(type = SearchTypeFilter.ANIME)))
    }

    @Test
    fun `genre filtering matches selected genres case insensitively`() {
        assertEquals(
            listOf(series, unscored),
            filterSearchResults(results, SearchFilters(genres = setOf(" drama ", "COMEDY"))),
        )
    }

    @Test
    fun `year filtering keeps only selected years`() {
        assertEquals(listOf(movie, series), filterSearchResults(results, SearchFilters(years = setOf(2021, 2024))))
    }

    @Test
    fun `upcoming releases are included by default`() {
        val upcoming = title(id = "upcoming", type = "movie", year = 2027)

        assertEquals(
            listOf(upcoming),
            filterSearchResults(listOf(upcoming), SearchFilters(), currentYear = 2026),
        )
    }

    @Test
    fun `disabling upcoming hides only known years after the current year`() {
        val upcoming = title(id = "upcoming", type = "movie", year = 2027)
        val currentYearTitle = title(id = "current", type = "movie", year = 2026)
        val unknownYearTitle = title(id = "unknown", type = "movie")

        assertEquals(
            listOf(currentYearTitle, unknownYearTitle),
            filterSearchResults(
                listOf(upcoming, currentYearTitle, unknownYearTitle),
                SearchFilters(includeUpcoming = false),
                currentYear = 2026,
            ),
        )
    }

    @Test
    fun `upcoming filter reads a year from releaseInfo when year is absent`() {
        val upcoming = title(id = "upcoming", type = "movie").copy(releaseInfo = "2030-04-10")

        assertEquals(
            emptyList<AddonTitle>(),
            filterSearchResults(listOf(upcoming), SearchFilters(includeUpcoming = false), currentYear = 2026),
        )
    }

    @Test
    fun `excluding upcoming releases counts as an active filter`() {
        assertEquals(true, SearchFilters(includeUpcoming = false).isActive)
    }

    @Test
    fun `filter choices come from result metadata and are sorted without duplicates`() {
        val titles = listOf(
            title(id = "first", type = "movie", year = 2024, genres = listOf("Drama", "Comedy")),
            title(id = "second", type = "movie", genres = listOf("drama", "Action")).copy(releaseInfo = "2019–2020"),
        )

        assertEquals(listOf("Action", "Comedy", "Drama"), availableSearchGenres(titles))
        assertEquals(listOf(2024, 2019), availableSearchYears(titles))
    }

    @Test
    fun `minimum rating keeps qualifying and unscored results`() {
        assertEquals(
            listOf(series, anime, explicitAnime, unscored),
            filterSearchResults(results, SearchFilters(minimumRating = 8.0)),
        )
    }

    @Test
    fun `partial source failure with no successful matches has a distinct presentation`() {
        assertEquals(
            SearchResultsPresentation.PARTIAL_FAILURE_WITHOUT_MATCH,
            searchResultsPresentation("sunshine throught the rain", emptyList(), "Some sources failed", partialFailure = true),
        )
    }

    @Test
    fun `successful no-match and empty query are distinct from partial failure`() {
        assertEquals(SearchResultsPresentation.NO_MATCH, searchResultsPresentation("unknown title", emptyList(), null, false))
        assertEquals(SearchResultsPresentation.SOURCE_FAILURE_WITHOUT_MATCH, searchResultsPresentation("title", emptyList(), "All sources failed", false))
        assertEquals(SearchResultsPresentation.QUERY_REQUIRED, searchResultsPresentation("  ", emptyList(), null, false))
    }

    private fun title(
        id: String,
        type: String,
        year: Int? = null,
        genres: List<String> = emptyList(),
        rating: Double? = null,
    ) = AddonTitle(
        addonUrl = addon,
        id = id,
        type = type,
        title = id,
        genres = genres,
        year = year,
        rating = rating,
    )
}
