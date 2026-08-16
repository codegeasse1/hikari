package com.lagradost.cloudstream3

abstract class LoadResponse {
    abstract var name: String
    abstract var url: String
    abstract var apiName: String?
    abstract var type: TvType
    abstract var posterUrl: String?
    abstract var year: Int?
    abstract var plot: String?
    abstract var score: Score?
    abstract var tags: List<String>?
    abstract var duration: Int?
    abstract var trailers: List<Any>?
    abstract var recommendations: List<Any>?
    abstract var actors: List<String>?
    abstract var comingSoon: Boolean
    abstract var syncData: Map<String, String>?
    abstract var posterHeaders: Map<String, String>?
    abstract var backgroundPosterUrl: String?
    abstract var logoUrl: String?
    abstract var contentRating: String?
    abstract var uniqueUrl: String?

    var rating: Int? = null
}

open class MovieLoadResponse(
    override var name: String,
    override var url: String,
    override var apiName: String?,
    override var type: TvType,
    var dataUrl: String?,
    override var posterUrl: String? = null,
    override var year: Int? = null,
    override var plot: String? = null,
    override var score: Score? = null,
    override var tags: List<String>? = null,
    override var duration: Int? = null,
    override var trailers: List<Any>? = null,
    override var recommendations: List<Any>? = null,
    override var actors: List<String>? = null,
    override var comingSoon: Boolean = false,
    override var syncData: Map<String, String>? = null,
    override var posterHeaders: Map<String, String>? = null,
    override var backgroundPosterUrl: String? = null,
    override var logoUrl: String? = null,
    override var contentRating: String? = null,
    override var uniqueUrl: String? = null,
) : LoadResponse()

open class AnimeLoadResponse(
    var engName: String? = null,
    var japName: String? = null,
    override var name: String,
    override var url: String,
    override var apiName: String?,
    override var type: TvType,
    override var posterUrl: String? = null,
    override var year: Int? = null,
    var episodes: Map<DubStatus, List<Episode>> = emptyMap(),
    var showStatus: ShowStatus = ShowStatus.Upcoming,
    override var plot: String? = null,
    override var tags: List<String>? = null,
    var synonyms: List<String>? = null,
    override var score: Score? = null,
    override var duration: Int? = null,
    override var trailers: List<Any>? = null,
    override var recommendations: List<Any>? = null,
    override var actors: List<String>? = null,
    override var comingSoon: Boolean = false,
    override var syncData: Map<String, String>? = null,
    override var posterHeaders: Map<String, String>? = null,
    var nextAiring: NextAiring? = null,
    var seasonNames: List<String>? = null,
    override var backgroundPosterUrl: String? = null,
    override var logoUrl: String? = null,
    override var contentRating: String? = null,
    override var uniqueUrl: String? = null,
) : LoadResponse() {

    val latestEpisodes: Map<DubStatus, Episode?>
        get() = episodes.mapValues { (_, eps) -> eps.maxByOrNull { it.episode ?: 0 } }

    fun totalEpisodeIndex(episode: Int, season: Int): Int = episode
}

open class TvSeriesLoadResponse(
    override var name: String,
    override var url: String,
    override var apiName: String?,
    override var type: TvType,
    var episodes: List<Episode>,
    override var posterUrl: String? = null,
    override var year: Int? = null,
    override var plot: String? = null,
    var showStatus: ShowStatus = ShowStatus.Upcoming,
    override var score: Score? = null,
    override var tags: List<String>? = null,
    override var duration: Int? = null,
    override var trailers: List<Any>? = null,
    override var recommendations: List<Any>? = null,
    override var actors: List<String>? = null,
    override var comingSoon: Boolean = false,
    override var syncData: Map<String, String>? = null,
    override var posterHeaders: Map<String, String>? = null,
    var nextAiring: NextAiring? = null,
    var seasonNames: List<String>? = null,
    override var backgroundPosterUrl: String? = null,
    override var logoUrl: String? = null,
    override var contentRating: String? = null,
    override var uniqueUrl: String? = null,
) : LoadResponse()
