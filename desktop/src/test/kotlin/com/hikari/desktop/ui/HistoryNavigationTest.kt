package com.hikari.desktop.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryNavigationTest {
    @Test
    fun watchHistoryIsReachableFromTheDesktopSidebar() {
        val destination = desktopNavigationDestinations.single { it.page == DesktopPage.HISTORY }

        assertEquals("History", destination.label)
    }
}
