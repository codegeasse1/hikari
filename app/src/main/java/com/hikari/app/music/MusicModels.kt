package com.hikari.app.music

/** One playable track (a YouTube Music "videoId"). */
data class MSong(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val durationSec: Int = 0,
    val thumb: String = "",
)

enum class MKind { SONG, ALBUM, PLAYLIST, ARTIST }

/** Anything that can sit in a shelf: a song, an album, a playlist or an artist. */
data class MItem(
    val kind: MKind,
    /** videoId for a song, browseId for an album / playlist / artist. */
    val id: String,
    val title: String,
    val subtitle: String,
    val thumb: String,
    val song: MSong? = null,
)

/** [asList] = the shelf came from "list rows" (Quick picks, Songs...) rather than cards. */
data class MShelf(val title: String, val items: List<MItem>, val asList: Boolean)

data class MChip(val title: String, val params: String)

data class MHome(val chips: List<MChip>, val shelves: List<MShelf>, val cont: String?)

data class MDetail(
    val title: String,
    val subtitle: String,
    val thumb: String,
    val songs: List<MSong>,
    val shelves: List<MShelf>,
)

data class MLine(val ms: Long, val text: String)

data class MLyrics(val synced: List<MLine>, val plain: String)

data class MPlaylist(val id: String, val name: String, val songs: List<MSong>)

data class MPlayStat(val song: MSong, val count: Int, val last: Long)

/** Where the music UI is navigated to (on top of the three tabs). */
sealed class MRoute {
    data class Remote(val kind: MKind, val id: String, val title: String, val thumb: String) : MRoute()
    data class Local(val key: String, val title: String) : MRoute()
}

/** In-memory cache so switching tabs does not reload the Home feed. */
object MusicCache {
    var home: MHome? = null
    var extra: List<MShelf> = emptyList()
}
