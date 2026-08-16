package com.lagradost.cloudstream3

abstract class SearchResponse {
    abstract val name: String
    abstract val url: String
    abstract var apiName: String?
    abstract var type: TvType
    abstract var posterUrl: String?
    abstract var posterHeaders: Map<String, String>
    abstract var id: Int?
    abstract var quality: SearchQuality
    abstract var score: Score?
}

open class MovieSearchResponse(
    override val name: String,
    override val url: String,
    override var apiName: String?,
    override var type: TvType,
    override var posterUrl: String? = null,
    var year: Int? = null,
    override var id: Int? = null,
    override var quality: SearchQuality = SearchQuality.Unknown,
    override var posterHeaders: Map<String, String> = emptyMap(),
    override var score: Score? = null,
) : SearchResponse()

open class AnimeSearchResponse(
    override val name: String,
    override val url: String,
    override var apiName: String?,
    override var type: TvType,
    override var posterUrl: String? = null,
    var year: Int? = null,
    var dubStatus: Set<DubStatus> = emptySet(),
    var otherName: String? = null,
    var episodes: Map<DubStatus, Int?> = emptyMap(),
    override var id: Int? = null,
    override var quality: SearchQuality = SearchQuality.Unknown,
    override var posterHeaders: Map<String, String> = emptyMap(),
    override var score: Score? = null,
) : SearchResponse()

open class TvSeriesSearchResponse(
    override val name: String,
    override val url: String,
    override var apiName: String?,
    override var type: TvType,
    override var posterUrl: String? = null,
    var year: Int? = null,
    var episodes: Int? = null,
    override var id: Int? = null,
    override var quality: SearchQuality = SearchQuality.Unknown,
    override var posterHeaders: Map<String, String> = emptyMap(),
    override var score: Score? = null,
) : SearchResponse()
