package com.hikari.desktop.data

import java.util.UUID
import java.util.prefs.Preferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LibraryCategoryStoreTest {
    @Test
    fun seedsTheSameFourBuiltInCategoriesAsAndroid() {
        val node = testNode()
        try {
            val categories = LibraryCategoryStore(node).categories()

            assertEquals(listOf("Movies", "Series", "Action", "Romance"), categories.map { it.name })
            assertTrue(categories.all { it.builtIn })
        } finally {
            node.removeNode()
        }
    }

    @Test
    fun createsTrimmedCategoriesAndKeepsThemAcrossReloads() {
        val node = testNode()
        try {
            val firstStore = LibraryCategoryStore(node)

            val created = firstStore.create("  Comfort watches  ")
            val reloaded = LibraryCategoryStore(node).categories()

            assertEquals("Comfort watches", created.name)
            assertEquals(created, reloaded.last())
        } finally {
            node.removeNode()
        }
    }

    @Test
    fun rejectsBlankAndDuplicateCategoryNamesWithoutChangingTheList() {
        val node = testNode()
        try {
            val store = LibraryCategoryStore(node)
            val before = store.categories()

            assertIllegalArgument { store.create("   ") }
            assertIllegalArgument { store.create(" movies ") }

            assertEquals(before, store.categories())
        } finally {
            node.removeNode()
        }
    }

    @Test
    fun persistsMultipleAssignmentsAndFiltersTitlesByCategory() {
        val node = testNode()
        try {
            val store = LibraryCategoryStore(node)
            val movies = AddonTitle("https://one.example/manifest.json", "movie-1", "movie", "Movie")
            val series = AddonTitle("https://one.example/manifest.json", "series-1", "series", "Series")
            val unfiled = AddonTitle("https://one.example/manifest.json", "movie-2", "movie", "Unfiled")
            val favorites = store.create("Favorites")

            store.assign(movies, setOf(LibraryCategory.MOVIES, favorites.id))
            store.assign(series, setOf(LibraryCategory.SERIES, favorites.id))

            val reloaded = LibraryCategoryStore(node)
            val assignments = reloaded.assignments()
            val allTitles = listOf(movies, series, unfiled)

            assertEquals(setOf(LibraryCategory.MOVIES, favorites.id), assignments[movies.savedTitleIdentity()])
            assertEquals(listOf(movies, series), filterSavedTitlesByCategory(allTitles, favorites.id, assignments))
            assertEquals(listOf(movies), filterSavedTitlesByCategory(allTitles, LibraryCategory.MOVIES, assignments))
            assertEquals(allTitles, filterSavedTitlesByCategory(allTitles, null, assignments))
        } finally {
            node.removeNode()
        }
    }

    @Test
    fun renamingKeepsAssignmentsAndDeletingUnfilesTitles() {
        val node = testNode()
        try {
            val store = LibraryCategoryStore(node)
            val title = AddonTitle("https://one.example/manifest.json", "movie-1", "movie", "Movie")
            val category = store.create("Queue")
            store.assign(title, setOf(category.id))

            store.rename(category.id, "Watch later")

            assertEquals("Watch later", store.categories().last().name)
            assertEquals(setOf(category.id), store.assignments()[title.savedTitleIdentity()])

            store.delete(category.id)

            assertTrue(store.categories().none { it.id == category.id })
            assertTrue(store.assignments()[title.savedTitleIdentity()].isNullOrEmpty())
        } finally {
            node.removeNode()
        }
    }

    @Test
    fun removingATitleClearsItsCategoryAssignments() {
        val node = testNode()
        try {
            val store = LibraryCategoryStore(node)
            val title = AddonTitle("https://one.example/manifest.json", "movie-1", "movie", "Movie")
            val category = store.create("Favorites")
            store.assign(title, setOf(category.id))

            store.removeTitle(title)

            assertTrue(store.assignments()[title.savedTitleIdentity()].isNullOrEmpty())
        } finally {
            node.removeNode()
        }
    }

    private fun assertIllegalArgument(action: () -> Unit) {
        try {
            action()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected validation failure.
        }
    }

    private fun testNode(): Preferences =
        Preferences.userRoot().node("com/hikari/desktop/library-category-test/${UUID.randomUUID()}")
}
