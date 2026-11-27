package com.hikari.desktop

import com.hikari.desktop.data.AddonEpisode

internal data class DetailEpisodeState(
    val seasons: List<Int>,
    val activeSeason: Int?,
    val visibleEpisodes: List<AddonEpisode>,
) {
    val showsSeasonSelector: Boolean
        get() = seasons.size > 1
}

internal fun detailEpisodeState(
    episodes: List<AddonEpisode>,
    selectedSeason: Int? = null,
): DetailEpisodeState {
    val orderedEpisodes = episodes.sortedWith(compareBy({ it.season }, { it.number }))
    val seasons = orderedEpisodes.map { it.season }
        .distinct()
        .sortedWith(compareBy { season -> if (season <= 0) Int.MAX_VALUE else season })
    val activeSeason = selectedSeason?.takeIf { it in seasons }
        ?: seasons.firstOrNull { it > 0 }
        ?: seasons.firstOrNull()
    val visibleEpisodes = if (seasons.size <= 1) {
        orderedEpisodes
    } else {
        orderedEpisodes.filter { it.season == activeSeason }
    }

    return DetailEpisodeState(
        seasons = seasons,
        activeSeason = activeSeason,
        visibleEpisodes = visibleEpisodes,
    )
}
