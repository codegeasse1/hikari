package com.hikari.desktop.data

import java.util.UUID
import java.util.prefs.Preferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedTitlesStoreTest {
    @Test
    fun savesNewestFirstAndRefreshesAnExistingTitleWithoutDuplicates() {
        val node = testNode()
        try {
            val store = SavedTitlesStore(node)
            val first = AddonTitle("https://one.example/manifest.json", "one", "movie", "First")
            val second = AddonTitle("https://one.example/manifest.json", "two", "series", "Second")

            store.save(first)
            store.save(second)
            store.save(first.copy(title = "First updated"))

            assertEquals(listOf(first.copy(title = "First updated"), second), store.all())
        } finally {
            node.removeNode()
        }
    }

    @Test
    fun distinguishesTitlesByAddonUrlTypeAndId() {
        val node = testNode()
        try {
            val store = SavedTitlesStore(node)
            val original = AddonTitle("https://one.example/manifest.json", "same", "movie", "Original")
            val otherAddon = AddonTitle("https://two.example/manifest.json", "same", "movie", "Other add-on")
            val otherType = AddonTitle("https://one.example/manifest.json", "same", "series", "Other type")

            store.save(original)
            store.save(otherAddon)
            store.save(otherType)
            store.remove(otherAddon)

            assertEquals(listOf(otherType, original), store.all())
        } finally {
            node.removeNode()
        }
    }

    @Test
    fun savedTitlesSurviveReloadAndRemovalIsDurable() {
        val node = testNode()
        try {
            val title = AddonTitle("https://one.example/manifest.json", "dune", "movie", "Dune")
            val firstStore = SavedTitlesStore(node)
            firstStore.save(title)

            val reopenedStore = SavedTitlesStore(node)
            assertEquals(listOf(title), reopenedStore.all())
            assertTrue(reopenedStore.isSaved(title))

            reopenedStore.remove(title)

            val nextStore = SavedTitlesStore(node)
            assertEquals(emptyList<AddonTitle>(), nextStore.all())
            assertFalse(nextStore.isSaved(title))
        } finally {
            node.removeNode()
        }
    }

    @Test
    fun keepsLargerLibrariesAcrossReloads() {
        val node = testNode()
        try {
            val titles = (1..48).map { index ->
                AddonTitle(
                    addonUrl = "https://source.example/manifest.json",
                    id = "title-$index",
                    type = "series",
                    title = "Title $index ${"x".repeat(160)}",
                    posterUrl = "https://images.example/${"p".repeat(100)}",
                    description = "Summary ${"d".repeat(240)}",
                    releaseInfo = "2025",
                    genres = listOf("Drama", "Mystery"),
                )
            }
            val firstStore = SavedTitlesStore(node)

            titles.forEach(firstStore::save)

            val reloaded = SavedTitlesStore(node).all()
            assertEquals(titles.size, reloaded.size)
            assertEquals("title-48", reloaded.first().id)
        } finally {
            node.removeNode()
        }
    }

    @Test
    fun storesOnlyTheTitleFieldsNeededForLibraryCardsAndOpening() {
        val node = testNode()
        try {
            val title = AddonTitle(
                addonUrl = "https://one.example/manifest.json",
                id = "dune",
                type = "movie",
                title = "Dune",
                posterUrl = "https://images.example/dune.jpg",
                backgroundUrl = "https://images.example/dune-background.jpg",
                description = "Full detail text",
                releaseInfo = "2024",
                genres = listOf("Adventure", "Sci-Fi"),
            )

            SavedTitlesStore(node).save(title)

            assertEquals(
                title.copy(backgroundUrl = null, description = null, genres = emptyList()),
                SavedTitlesStore(node).all().single(),
            )
        } finally {
            node.removeNode()
        }
    }

    private fun testNode(): Preferences =
        Preferences.userRoot().node("com/hikari/desktop/saved-title-test/${UUID.randomUUID()}")
}
