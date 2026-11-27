package com.hikari.desktop.playback

import com.hikari.desktop.data.AddonEpisode
import com.hikari.desktop.data.AddonTitle
import com.hikari.desktop.data.WatchHistoryEntry
import com.hikari.desktop.data.WatchHistoryStore
import java.util.UUID
import java.util.prefs.Preferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamLaunchHistoryTest {
    @Test
    fun recordsTitleAndEpisodeAfterSuccessfulLaunch() = runBlocking {
        val node = testNode()
        try {
            val store = WatchHistoryStore(node)
            val title = AddonTitle("https://one.example/manifest.json", "show", "series", "Show")
            val episode = AddonEpisode("show:2:4", "The Return", 2, 4)

            val result = launchAndRecordHistory(store, title, episode, clock = { 42L }) { "mpv" }

            assertEquals("mpv", result.destination)
            assertNull(result.historyError)
            assertEquals(listOf(WatchHistoryEntry(title, episode, 42L)), store.all())
        } finally {
            node.removeNode()
        }
    }

    @Test
    fun failedExternalLaunchDoesNotCreateHistory() = runBlocking {
        val node = testNode()
        try {
            val store = WatchHistoryStore(node)
            val title = AddonTitle("https://one.example/manifest.json", "dune", "movie", "Dune")
            val launchFailure = IllegalStateException("player unavailable")

            try {
                launchAndRecordHistory(store, title, clock = { 42L }) { throw launchFailure }
                throw AssertionError("Expected the external launch failure to propagate")
            } catch (error: IllegalStateException) {
                assertSame(launchFailure, error)
            }

            assertTrue(store.all().isEmpty())
        } finally {
            node.removeNode()
        }
    }

    private fun testNode(): Preferences =
        Preferences.userRoot().node("com/hikari/desktop/launch-history-test/${UUID.randomUUID()}")
}
