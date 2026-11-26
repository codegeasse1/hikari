package com.hikari.desktop

import com.hikari.desktop.data.AddonCatalog
import com.hikari.desktop.data.AddonTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogBrowseStateTest {
    private val target = CatalogBrowseTarget(
        addonUrl = "https://catalog.example/manifest.json",
        addonName = "Catalog source",
        catalog = AddonCatalog("movie", "popular", "Popular"),
    )

    @Test
    fun successfulFullPageAdvancesToNextPageAndAllowsMoreLoading() {
        val firstPage = CatalogBrowseState(target = target, hasMore = true)
            .beginPageLoad()
            .finishPage(listOf(title("tt1"), title("tt2")), pageSize = 2)

        assertEquals(2, firstPage.nextPage)
        assertTrue(firstPage.hasMore)
        assertFalse(firstPage.loading)
        assertTrue(firstPage.canLoadNextPage())
        assertEquals(listOf("tt1", "tt2"), firstPage.titles.map { it.id })
    }

    @Test
    fun failedPageCanBeRetriedWithoutSkippingItsPageNumber() {
        val firstPage = CatalogBrowseState(target = target, hasMore = true)
            .beginPageLoad()
            .finishPage(listOf(title("tt1"), title("tt2")), pageSize = 2)
        val failedSecondPage = firstPage.beginPageLoad().failPage("Provider is offline")

        assertEquals(2, failedSecondPage.nextPage)
        assertEquals(listOf("tt1", "tt2"), failedSecondPage.titles.map { it.id })
        assertEquals("Provider is offline", failedSecondPage.error)
        assertFalse(failedSecondPage.loading)
        assertTrue(failedSecondPage.canLoadNextPage())
    }

    @Test
    fun shortOrEmptyPageMarksEndAndKeepsPreviouslyLoadedTitles() {
        val firstPage = CatalogBrowseState(target = target, hasMore = true)
            .beginPageLoad()
            .finishPage(listOf(title("tt1"), title("tt2")), pageSize = 2)
        val lastPage = firstPage.beginPageLoad().finishPage(listOf(title("tt3")), pageSize = 2)

        assertEquals(3, lastPage.nextPage)
        assertFalse(lastPage.hasMore)
        assertEquals(listOf("tt1", "tt2", "tt3"), lastPage.titles.map { it.id })
        assertFalse(lastPage.canLoadNextPage())
    }

    @Test
    fun repeatedTitlesAcrossPagesAreKeptOnlyOnce() {
        val pageOne = CatalogBrowseState(target = target, hasMore = true)
            .beginPageLoad()
            .finishPage(listOf(title("tt1"), title("tt2")), pageSize = 2)
        val pageTwo = pageOne.beginPageLoad()
            .finishPage(listOf(title("tt2"), title("tt3")), pageSize = 2)

        assertEquals(listOf("tt1", "tt2", "tt3"), pageTwo.titles.map { it.id })
    }

    @Test
    fun manualLoadActionIsAvailableOnlyWhenAnotherPageCanBeRequested() {
        val initial = CatalogBrowseState(target = target, hasMore = true)
        val firstPage = initial.beginPageLoad()
            .finishPage(List(100) { title("tt$it") })
        val loading = firstPage.beginPageLoad()
        val failed = firstPage.beginPageLoad().failPage("Provider is offline")
        val end = firstPage.beginPageLoad().finishPage(listOf(title("tt101")))

        assertFalse(initial.canShowManualLoadAction())
        assertTrue(firstPage.canShowManualLoadAction())
        assertFalse(loading.canShowManualLoadAction())
        assertFalse(failed.canShowManualLoadAction())
        assertFalse(end.canShowManualLoadAction())
    }

    private fun title(id: String) = AddonTitle(
        addonUrl = target.addonUrl,
        id = id,
        type = "movie",
        title = "Film $id",
    )
}
