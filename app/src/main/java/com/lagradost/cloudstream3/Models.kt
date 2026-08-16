package com.lagradost.cloudstream3

enum class TvType {
    Movie,
    AnimeMovie,
    TvSeries,
    Cartoon,
    Anime,
    OVA,
    Torrent,
    Documentary,
    AsianDrama,
    Live,
    NSFW,
    Others,
    Music,
    AudioBook,
    CustomMedia,
    Audio,
    Podcast,
    Video,
}

enum class DubStatus(val id: Int) {
    None(0),
    Dubbed(1),
    Subbed(2),
}

class ErrorLoadingException(message: String) : RuntimeException(message)

data class Score(val score: Double?, val votes: Int? = null)

enum class SearchQuality {
    Unknown,
    VeryLow,
    Low,
    Medium,
    High,
    VeryHigh,
}

enum class ShowStatus {
    Upcoming,
    Airing,
    Completed,
    Cancelled,
}

data class NextAiring(val episode: Int?, val airingAt: Long?)
