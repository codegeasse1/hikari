package com.hikari.desktop.playback

import com.hikari.desktop.data.AddonEpisode
import com.hikari.desktop.data.AddonTitle

internal fun defaultVideoIdForPlayback(title: AddonTitle, episodes: List<AddonEpisode>): String {
    val isMovie = title.type.equals("movie", ignoreCase = true) || title.type.equals("film", ignoreCase = true)
    if (isMovie) return title.id

    val firstEpisodeId = episodes.minWithOrNull(compareBy<AddonEpisode> { it.season }.thenBy { it.number })
        ?.id
        ?.takeIf(String::isNotBlank)
    return firstEpisodeId ?: "${title.id}:1:1"
}
