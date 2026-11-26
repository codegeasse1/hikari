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

    @Test
    fun catalogSearchPassesTheQueryAndPageOffsetToTheStremioClient() = runBlocking {
        val requestedUrls = mutableListOf<String>()
        val source = StremioCatalogPageSource(StremioClient { url ->
            requestedUrls += url
            """{"metas":[{"id":"tt1","type":"movie","name":"One Piece"}]}"""
        })

        val page = source.loadPage(
            target.copy(supportsSearch = true),
            page = 2,
            search = "one piece",
        )

        assertEquals(
            listOf("https://catalog.example/catalog/movie/popular/search=one%20piece&skip=100.json"),
            requestedUrls,
        )
        assertEquals("One Piece", page.items.single().title)
        assertFalse(page.hasMore)
    }

    @Test
    fun catalogSearchIsRejectedWhenTheManifestDoesNotAdvertiseIt() = runBlocking {
        var requested = false
        val source = StremioCatalogPageSource(StremioClient {
            requested = true
            responseWith(0)
        })

        val error = runCatching { source.loadPage(target, page = 1, search = "one piece") }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertFalse(requested)
    }

    private fun responseWith(count: Int): String {
        val metas = (1..count).joinToString(",") { index ->
            """{"id":"tt$index","type":"movie","name":"Film $index"}"""
        }
        return """{"metas":[$metas]}"""
    }
}
