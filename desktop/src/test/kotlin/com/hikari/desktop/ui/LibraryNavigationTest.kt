package com.hikari.desktop.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryNavigationTest {
    @Test
    fun libraryIsReachableFromTheDesktopSidebar() {
        val destination = desktopNavigationDestinations.single { it.page == DesktopPage.LIBRARY }

        assertEquals("Library", destination.label)
    }
}
