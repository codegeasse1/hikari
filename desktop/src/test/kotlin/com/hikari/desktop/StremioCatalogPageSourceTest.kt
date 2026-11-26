package com.hikari.desktop

import com.hikari.desktop.net.STREMIO_PAGE_SIZE
import com.hikari.desktop.net.StremioClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StremioCatalogPageSourceTest {
    private val target = CatalogReference(
        provider = CatalogProviderKind.STREMIO,
        sourceId = "https://catalog.example/manifest.json",
        sourceName = "Catalog source",
        catalogId = "popular",
        mediaType = "movie",
        catalogName = "Popular",
    )

    @Test
    fun fullFixturePageLeavesTheNextPageAvailable() = runBlocking {
        val source = StremioCatalogPageSource(StremioClient { responseWith(STREMIO_PAGE_SIZE) })

        val page = source.loadPage(target, page = 1)

        assertEquals(STREMIO_PAGE_SIZE, page.items.size)
        assertTrue(page.hasMore)
    }

    @Test
    fun shortFixturePageMarksTheEnd() = runBlocking {
        val source = StremioCatalogPageSource(StremioClient { responseWith(2) })

        val page = source.loadPage(target, page = 1)

        assertEquals(2, page.items.size)
        assertFalse(page.hasMore)
    }

    @Test
    fun stremioAdapterDoesNotAcceptAnotherProviderKind() = runBlocking {
        val source = StremioCatalogPageSource(StremioClient { responseWith(0) })
        val otherProvider = target.copy(provider = CatalogProviderKind.TMDB)

        val error = runCatching { source.loadPage(otherProvider, page = 1) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    private fun responseWith(count: Int): String {
        val metas = (1..count).joinToString(",") { index ->
            """{"id":"tt$index","type":"movie","name":"Film $index"}"""
        }
        return """{"metas":[$metas]}"""
    }
}
