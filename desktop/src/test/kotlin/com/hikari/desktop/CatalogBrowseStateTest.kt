package com.hikari.desktop

import com.hikari.desktop.data.AddonTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogBrowseStateTest {
    private val target = CatalogReference(
        provider = CatalogProviderKind.STREMIO,
        sourceId = "https://catalog.example/manifest.json",
        sourceName = "Catalog source",
        catalogId = "popular",
        mediaType = "movie",
        catalogName = "Popular",
    )

    @Test
    fun explicitPageResultControlsWhetherAnotherPageCanLoad() {
        val firstPage = CatalogBrowseState(target = target, hasMore = true)
            .beginPageLoad()
            .finishPage(CatalogPageResult(listOf(title("tt1")), hasMore = true))

        assertEquals(2, firstPage.nextPage)
        assertTrue(firstPage.hasMore)
        assertFalse(firstPage.loading)
        assertTrue(firstPage.canLoadNextPage())
        assertEquals(listOf("tt1"), firstPage.titles.map { it.id })
    }

    @Test
    fun failedPageCanBeRetriedWithoutSkippingItsPageNumber() {
        val firstPage = CatalogBrowseState(target = target, hasMore = true)
            .beginPageLoad()
            .finishPage(CatalogPageResult(listOf(title("tt1")), hasMore = true))
        val failedSecondPage = firstPage.beginPageLoad().failPage("Provider is offline")

        assertEquals(2, failedSecondPage.nextPage)
        assertEquals(listOf("tt1"), failedSecondPage.titles.map { it.id })
        assertEquals("Provider is offline", failedSecondPage.error)
        assertFalse(failedSecondPage.loading)
        assertTrue(failedSecondPage.canLoadNextPage())
    }

    @Test
    fun anEmptyPageEndsPaginationAndKeepsPreviouslyLoadedTitles() {
        val firstPage = CatalogBrowseState(target = target, hasMore = true)
            .beginPageLoad()
            .finishPage(CatalogPageResult(listOf(title("tt1")), hasMore = true))
        val lastPage = firstPage.beginPageLoad()
            .finishPage(CatalogPageResult(emptyList(), hasMore = false))

        assertEquals(3, lastPage.nextPage)
        assertFalse(lastPage.hasMore)
        assertEquals(listOf("tt1"), lastPage.titles.map { it.id })
        assertFalse(lastPage.canLoadNextPage())
    }

    @Test
    fun aShortPageCanStillHaveMoreWhenTheProviderReportsAnotherPage() {
        val page = CatalogBrowseState(target = target, hasMore = true)
            .beginPageLoad()
            .finishPage(CatalogPageResult(listOf(title("tt1"), title("tt2")), hasMore = true))

        assertTrue(page.hasMore)
        assertTrue(page.canLoadNextPage())
    }

    @Test
    fun repeatedTitlesAcrossPagesAreKeptOnlyOnceByStableProviderIdentity() {
        val pageOne = CatalogBrowseState(target = target, hasMore = true)
            .beginPageLoad()
            .finishPage(CatalogPageResult(listOf(title("tt1"), title("tt2")), hasMore = true))
        val pageTwo = pageOne.beginPageLoad().finishPage(
            CatalogPageResult(
                listOf(title("tt2"), title("tt3"), title("tt1", sourceId = "https://other.example/manifest.json")),
                hasMore = false,
            ),
        )

        assertEquals(listOf("tt1", "tt2", "tt3", "tt1"), pageTwo.titles.map { it.id })
        assertEquals(2, pageTwo.titles.map { it.addonUrl }.distinct().size)
    }

    @Test
    fun staleAndDuplicatePageResponsesAreRejected() {
        val requestingFirstPage = CatalogBrowseState(target = target, hasMore = true).beginPageLoad()
        val completedFirstPage = requestingFirstPage.finishPage(
            CatalogPageResult(listOf(title("tt1")), hasMore = true),
        )
        val requestingSecondPage = completedFirstPage.beginPageLoad()
        val otherTarget = target.copy(sourceId = "https://other.example/manifest.json")

        assertTrue(requestingFirstPage.acceptsResponse(target, requestedPage = 1))
        assertFalse(completedFirstPage.acceptsResponse(target, requestedPage = 1))
        assertTrue(requestingSecondPage.acceptsResponse(target, requestedPage = 2))
        assertFalse(requestingSecondPage.acceptsResponse(otherTarget, requestedPage = 2))
    }

    @Test
    fun manualLoadActionIsAvailableOnlyWhenAnotherPageCanBeRequested() {
        val initial = CatalogBrowseState(target = target, hasMore = true)
        val firstPage = initial.beginPageLoad()
            .finishPage(CatalogPageResult(listOf(title("tt1")), hasMore = true))
        val loading = firstPage.beginPageLoad()
        val failed = firstPage.beginPageLoad().failPage("Provider is offline")
        val end = firstPage.beginPageLoad()
            .finishPage(CatalogPageResult(listOf(title("tt2")), hasMore = false))

        assertFalse(initial.canShowManualLoadAction())
        assertTrue(firstPage.canShowManualLoadAction())
        assertFalse(loading.canShowManualLoadAction())
        assertFalse(failed.canShowManualLoadAction())
        assertFalse(end.canShowManualLoadAction())
    }

    @Test
    fun catalogIdentityIncludesProviderAndSourceSoSameIdsDoNotCollide() {
        val tmdbTarget = target.copy(provider = CatalogProviderKind.TMDB, sourceId = "tmdb:discover:movie")

        assertNotEquals(target.stableIdentity, tmdbTarget.stableIdentity)
        assertNotEquals(target.stableIdentity, target.copy(sourceId = "https://other.example/manifest.json").stableIdentity)
    }

    private fun title(id: String, sourceId: String = target.sourceId) = AddonTitle(
        addonUrl = sourceId,
        id = id,
        type = "movie",
        title = "Film $id",
    )
}
