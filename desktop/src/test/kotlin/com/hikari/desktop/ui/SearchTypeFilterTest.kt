package com.hikari.desktop.ui

import com.hikari.desktop.data.AddonTitle
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchTypeFilterTest {
    private val movie = title(id = "film", type = "movie")
    private val series = title(id = "show", type = "series")
    private val other = title(id = "other", type = "anime")
    private val results = listOf(movie, series, other)

    @Test
    fun `all keeps results from every Stremio media type in their original order`() {
        assertEquals(results, filterSearchResults(results, SearchTypeFilter.ALL))
    }

    @Test
    fun `movies keeps only movie results without changing their order`() {
        assertEquals(listOf(movie), filterSearchResults(results, SearchTypeFilter.MOVIES))
    }

    @Test
    fun `series matches its Stremio type without changing the result`() {
        assertEquals(listOf(series), filterSearchResults(results, SearchTypeFilter.SERIES))
    }

    private fun title(id: String, type: String) = AddonTitle(
        addonUrl = "https://catalog.example/manifest.json",
        id = id,
        type = type,
        title = id,
    )
}
