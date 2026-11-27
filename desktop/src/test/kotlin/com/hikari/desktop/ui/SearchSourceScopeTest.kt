package com.hikari.desktop.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchSourceScopeTest {
    private val sources = listOf(
        "https://movies.example/manifest.json",
        "https://series.example/manifest.json",
        "https://anime.example/manifest.json",
    )

    @Test
    fun `empty selection keeps every add-on in its current order`() {
        assertEquals(sources, selectSearchSourceUrls(sources, emptySet()))
    }

    @Test
    fun `one selected add-on narrows search to only that URL`() {
        assertEquals(
            listOf(sources[1]),
            selectSearchSourceUrls(sources, setOf(sources[1])),
        )
    }

    @Test
    fun `multiple selected add-ons keep installed source order`() {
        assertEquals(
            listOf(sources[0], sources[2]),
            selectSearchSourceUrls(sources, linkedSetOf(sources[2], sources[0])),
        )
    }

    @Test
    fun `stale selected URLs are ignored when another selected source is installed`() {
        assertEquals(
            listOf(sources[1]),
            selectSearchSourceUrls(sources, setOf(sources[1], "https://removed.example/manifest.json")),
        )
    }

    @Test
    fun `a selection containing only removed sources falls back to all installed sources`() {
        assertEquals(
            sources,
            selectSearchSourceUrls(sources, setOf("https://removed.example/manifest.json")),
        )
    }

    @Test
    fun `empty source list stays empty`() {
        assertEquals(emptyList<String>(), selectSearchSourceUrls(emptyList(), emptySet()))
    }

    @Test
    fun `toggling a source from the all-sources selection narrows to that source`() {
        assertEquals(
            setOf(sources[2]),
            toggleSearchSourceSelection(emptySet(), sources[2]),
        )
    }

    @Test
    fun `toggling a source on adds it to an existing selection`() {
        assertEquals(
            setOf(sources[0], sources[2]),
            toggleSearchSourceSelection(setOf(sources[0]), sources[2]),
        )
    }

    @Test
    fun `toggling the last selected source off restores the all-sources selection`() {
        assertEquals(
            emptySet<String>(),
            toggleSearchSourceSelection(setOf(sources[1]), sources[1]),
        )
    }
}
