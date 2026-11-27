package com.hikari.desktop.data

import java.util.UUID
import java.util.prefs.Preferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchHistoryStoreTest {
    @Test
    fun recordsNewestFirstAndRefreshesAnExistingEpisodeWithoutDuplicates() {
        val node = testNode()
        try {
            val store = WatchHistoryStore(node)
            val movie = AddonTitle("https://one.example/manifest.json", "dune", "movie", "Dune")
            val series = AddonTitle("https://one.example/manifest.json", "show", "series", "Show")
            val episode = AddonEpisode("show:1:1", "Pilot", 1, 1)

            store.record(movie, launchedAtMillis = 10L)
            store.record(series, episode, launchedAtMillis = 20L)
            store.record(series, episode.copy(title = "Pilot updated"), launchedAtMillis = 30L)

            assertEquals(
                listOf(
                    WatchHistoryEntry(series, episode.copy(title = "Pilot updated"), 30L),
                    WatchHistoryEntry(movie, null, 10L),
                ),
                store.all(),
            )
        } finally {
            node.removeNode()
        }
    }

    @Test
    fun keepsEpisodesAndSourcesDistinctAndSurvivesReload() {
        val node = testNode()
        try {
            val show = AddonTitle("https://one.example/manifest.json", "show", "series", "Show")
            val pilot = AddonEpisode("show:1:1", "Pilot", 1, 1)
            val finale = AddonEpisode("show:1:2", "Finale", 1, 2)
            val otherSource = show.copy(addonUrl = "https://two.example/manifest.json")

            WatchHistoryStore(node).apply {
                record(show, pilot, launchedAtMillis = 1L)
                record(show, finale, launchedAtMillis = 2L)
                record(otherSource, pilot, launchedAtMillis = 3L)
            }

            val reloaded = WatchHistoryStore(node).all()

            assertEquals(3, reloaded.size)
            assertEquals(listOf("show:1:1", "show:1:2", "show:1:1"), reloaded.map { it.episode?.id })
            assertEquals("https://two.example/manifest.json", reloaded.first().title.addonUrl)
        } finally {
            node.removeNode()
        }
    }

    @Test
    fun retainsOnlyTheNewestTwoHundredEntries() {
        val node = testNode()
        try {
            val store = WatchHistoryStore(node)
            for (index in 1..201) {
                store.record(
                    AddonTitle("https://one.example/manifest.json", "movie-$index", "movie", "Movie $index"),
                    launchedAtMillis = index.toLong(),
                )
            }

            val entries = store.all()

            assertEquals(200, entries.size)
            assertEquals("movie-201", entries.first().title.id)
            assertEquals("movie-2", entries.last().title.id)
        } finally {
            node.removeNode()
        }
    }

    @Test
    fun removesOneEntryAndClearsHistoryDurably() {
        val node = testNode()
        try {
            val store = WatchHistoryStore(node)
            val first = AddonTitle("https://one.example/manifest.json", "one", "movie", "One")
            val second = AddonTitle("https://one.example/manifest.json", "two", "movie", "Two")
            store.record(first, launchedAtMillis = 1L)
            store.record(second, launchedAtMillis = 2L)
            val secondEntry = store.all().first()

            WatchHistoryStore(node).remove(secondEntry)

            assertEquals(listOf(WatchHistoryEntry(first, null, 1L)), WatchHistoryStore(node).all())
            WatchHistoryStore(node).clear()
            assertTrue(WatchHistoryStore(node).all().isEmpty())
            assertEquals(0, node.node("watchHistory").childrenNames().size)
        } finally {
            node.removeNode()
        }
    }

    private fun testNode(): Preferences =
        Preferences.userRoot().node("com/hikari/desktop/watch-history-test/${UUID.randomUUID()}")
}
