package com.hikari.desktop

import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogDirectoryContentStateTest {
    @Test
    fun loadingTakesPrecedenceOverEmptyAndErrors() {
        assertEquals(
            CatalogDirectoryContentState.LOADING,
            catalogDirectoryContentState(catalogCount = 0, loading = true, hasSourceErrors = true),
        )
    }

    @Test
    fun emptyStateRequiresNoCatalogsAndNoSourceErrors() {
        assertEquals(
            CatalogDirectoryContentState.EMPTY,
            catalogDirectoryContentState(catalogCount = 0, loading = false, hasSourceErrors = false),
        )
    }

    @Test
    fun sourceFailureIsNotReportedAsAnEmptyCatalogList() {
        assertEquals(
            CatalogDirectoryContentState.SOURCE_ERROR,
            catalogDirectoryContentState(catalogCount = 0, loading = false, hasSourceErrors = true),
        )
    }

    @Test
    fun availableCatalogsRemainVisibleWhenAnotherSourceFails() {
        assertEquals(
            CatalogDirectoryContentState.CONTENT,
            catalogDirectoryContentState(catalogCount = 2, loading = false, hasSourceErrors = true),
        )
    }

    @Test
    fun totalSourceFailureDoesNotClaimThatOnlySomeSourcesFailed() {
        assertEquals("Couldn't load sources", catalogDirectoryErrorTitle(sourceCount = 1, failedSourceCount = 1))
    }

    @Test
    fun partialSourceFailureIsClearlyMarkedAsPartial() {
        assertEquals("Some sources couldn't be loaded", catalogDirectoryErrorTitle(sourceCount = 3, failedSourceCount = 1))
    }
}
