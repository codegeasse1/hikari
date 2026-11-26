package com.hikari.desktop.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogNavigationTest {
    @Test
    fun catalogOpenedFromDirectoryReturnsToDirectory() {
        assertEquals(DesktopPage.CATALOGS, catalogBrowseReturnDestination(DesktopPage.CATALOGS))
    }

    @Test
    fun catalogOpenedFromHomeReturnsToHome() {
        assertEquals(DesktopPage.HOME, catalogBrowseReturnDestination(DesktopPage.HOME))
    }
}
