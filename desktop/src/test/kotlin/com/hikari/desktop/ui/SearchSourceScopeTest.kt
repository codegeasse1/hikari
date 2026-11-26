package com.hikari.desktop.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchSourceScopeTest {
    private val sources = listOf(
        "https://movies.example/manifest.json",
        "https://series.example/manifest.json",
    )

    @Test
    fun `no source selection keeps every add-on in its current order`() {
        assertEquals(sources, selectSearchSourceUrls(sources, null))
    }

    @Test
    fun `a selected add-on narrows search to only that URL`() {
        assertEquals(
            listOf(sources[1]),
            selectSearchSourceUrls(sources, sources[1]),
        )
    }

    @Test
    fun `a removed selection falls back to all currently installed sources`() {
        assertEquals(
            sources,
            selectSearchSourceUrls(sources, "https://removed.example/manifest.json"),
        )
    }

    @Test
    fun `empty source list stays empty`() {
        assertEquals(emptyList<String>(), selectSearchSourceUrls(emptyList(), null))
    }
}
