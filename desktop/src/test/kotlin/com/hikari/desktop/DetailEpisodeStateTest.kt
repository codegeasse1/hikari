package com.hikari.desktop

import com.hikari.desktop.data.AddonEpisode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailEpisodeStateTest {
    @Test
    fun multiSeasonEpisodesDefaultToTheFirstRealSeasonAndOnlyShowItsEpisodes() {
        val state = detailEpisodeState(
            episodes = listOf(
                episode(season = 2, number = 1),
                episode(season = 1, number = 2),
                episode(season = 1, number = 1),
                episode(season = 0, number = 1),
            ),
        )

        assertEquals(listOf(1, 2, 0), state.seasons)
        assertEquals(1, state.activeSeason)
        assertTrue(state.showsSeasonSelector)
        assertEquals(listOf("s1e1", "s1e2"), state.visibleEpisodes.map { it.id })
    }

    @Test
    fun selectingAnotherSeasonShowsOnlyThatSeasonsEpisodesInEpisodeOrder() {
        val state = detailEpisodeState(
            episodes = listOf(
                episode(season = 2, number = 3),
                episode(season = 1, number = 1),
                episode(season = 2, number = 1),
            ),
            selectedSeason = 2,
        )

        assertEquals(2, state.activeSeason)
        assertEquals(listOf("s2e1", "s2e3"), state.visibleEpisodes.map { it.id })
    }

    @Test
    fun unavailableSelectedSeasonFallsBackToTheFirstAvailableRealSeason() {
        val state = detailEpisodeState(
            episodes = listOf(episode(season = 3, number = 1), episode(season = 1, number = 1)),
            selectedSeason = 2,
        )

        assertEquals(1, state.activeSeason)
        assertEquals(listOf("s1e1"), state.visibleEpisodes.map { it.id })
    }

    @Test
    fun oneSeasonKeepsTheFlatEpisodeListAndDoesNotShowASelector() {
        val state = detailEpisodeState(
            episodes = listOf(episode(season = 4, number = 2), episode(season = 4, number = 1)),
        )

        assertEquals(listOf(4), state.seasons)
        assertEquals(4, state.activeSeason)
        assertFalse(state.showsSeasonSelector)
        assertEquals(listOf("s4e1", "s4e2"), state.visibleEpisodes.map { it.id })
    }

    @Test
    fun specialsAreLastInTheSeasonChoicesAndOnlyBecomeDefaultWhenNoRealSeasonExists() {
        val state = detailEpisodeState(
            episodes = listOf(episode(season = 0, number = 1), episode(season = 0, number = 2)),
        )

        assertEquals(listOf(0), state.seasons)
        assertEquals(0, state.activeSeason)
        assertEquals(listOf("s0e1", "s0e2"), state.visibleEpisodes.map { it.id })
    }

    @Test
    fun emptyEpisodeListHasNoSeasonOrActiveSelection() {
        val state = detailEpisodeState(emptyList())

        assertTrue(state.seasons.isEmpty())
        assertNull(state.activeSeason)
        assertFalse(state.showsSeasonSelector)
        assertTrue(state.visibleEpisodes.isEmpty())
    }

    private fun episode(season: Int, number: Int) = AddonEpisode(
        id = "s${season}e$number",
        title = "Season $season episode $number",
        season = season,
        number = number,
    )
}
