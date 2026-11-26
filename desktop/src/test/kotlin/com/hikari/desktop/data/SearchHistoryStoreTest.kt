package com.hikari.desktop.data

import java.util.UUID
import java.util.prefs.Preferences
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchHistoryStoreTest {
    @Test
    fun recordsTrimmedQueriesNewestFirstAndPersistsAcrossStoreInstances() {
        val node = testNode()
        try {
            SearchHistoryStore(node).apply {
                record("  Dune  ")
                record("Avatar")
            }

            assertEquals(listOf("Avatar", "Dune"), SearchHistoryStore(node).all())
        } finally {
            node.removeNode()
        }
    }

    @Test
    fun ignoresQueriesShorterThanTwoCharacters() {
        val node = testNode()
        try {
            val store = SearchHistoryStore(node)

            store.record(" ")
            store.record("x")

            assertEquals(emptyList<String>(), store.all())
        } finally {
            node.removeNode()
        }
    }

    @Test
    fun movesCaseInsensitiveDuplicatesToTheNewestPosition() {
        val node = testNode()
        try {
            val store = SearchHistoryStore(node)
            store.record("Dune")
            store.record("Avatar")

            store.record(" dune ")

            assertEquals(listOf("dune", "Avatar"), store.all())
        } finally {
            node.removeNode()
        }
    }

    @Test
    fun keepsOnlyTheTwentyMostRecentQueries() {
        val node = testNode()
        try {
            val store = SearchHistoryStore(node)
            (1..25).forEach { store.record("Title $it") }

            assertEquals((25 downTo 6).map { "Title $it" }, store.all())
        } finally {
            node.removeNode()
        }
    }

    @Test
    fun removesOneQueryWithoutClearingTheOthersAndCanClearAll() {
        val node = testNode()
        try {
            val store = SearchHistoryStore(node)
            store.record("Dune")
            store.record("Avatar")

            store.remove("dUnE")

            assertEquals(listOf("Avatar"), store.all())

            store.clear()

            assertEquals(emptyList<String>(), store.all())
        } finally {
            node.removeNode()
        }
    }

    private fun testNode(): Preferences = Preferences.userRoot().node("com/hikari/desktop/test/${UUID.randomUUID()}")
}
