package com.hikari.desktop.playback

import com.hikari.desktop.data.AddonEpisode
import com.hikari.desktop.data.AddonTitle
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackSelectionTest {
    private val series = AddonTitle(
        addonUrl = "https://catalog.example/manifest.json",
        id = "show1",
        type = "series",
        title = "Show One",
    )

    @Test
    fun `series primary play uses the actual earliest episode id`() {
        val episodes = listOf(
            AddonEpisode("show1:2:1", "Season Two", season = 2, number = 1),
            AddonEpisode("show1:1:3", "Third Episode", season = 1, number = 3),
            AddonEpisode("show1:1:1", "First Episode", season = 1, number = 1),
        )

        assertEquals("show1:1:1", defaultVideoIdForPlayback(series, episodes))
    }

    @Test
    fun `movie primary play uses the title id`() {
        val movie = series.copy(type = "movie", id = "movie1")
        val episodes = listOf(AddonEpisode("movie1:1:1", "Unexpected", season = 1, number = 1))

        assertEquals("movie1", defaultVideoIdForPlayback(movie, episodes))
    }

    @Test
    fun `series without loaded episodes keeps the canonical fallback`() {
        assertEquals("show1:1:1", defaultVideoIdForPlayback(series, emptyList()))
    }
}
