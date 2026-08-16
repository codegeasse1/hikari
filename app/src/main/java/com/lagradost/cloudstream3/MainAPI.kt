package com.lagradost.cloudstream3

import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.serialization.json.Json
import okhttp3.Interceptor

/** Shared JSON helpers the compiled plugins reference via `MainAPIKt.getJson()/getMapper()`. */
val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

val mapper: JsonMapper = JsonMapper.builder()
    .addModule(KotlinModule.Builder().build())
    .build()

/**
 * Reimplementation of CloudStream3's MainAPI — the class every compiled .cs3
 * provider extends. Members and signatures mirror the exact bytecode the
 * plugins link against.
 */
abstract class MainAPI {

    open var name: String = ""
    open var mainUrl: String = ""
    open var lang: String = "en"
    open val hasMainPage: Boolean = false
    open val hasDownloadSupport: Boolean = false
    open val usesWebView: Boolean = false
    open val hasQuickSearch: Boolean = false
    open val supportedTypes: Set<TvType> = emptySet()
    open val mainPage: List<Pair<String, String>> = emptyList()
    open var sourcePlugin: String = ""
    open var canBeOverridden: Boolean = true

    open fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? = null

    open suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse =
        newHomePageResponse(emptyList<HomePageList>())

    open suspend fun search(query: String): List<SearchResponse> = emptyList()

    open suspend fun quickSearch(query: String): List<SearchResponse> = emptyList()

    open suspend fun load(url: String): LoadResponse =
        throw RuntimeException("load() not implemented by provider '$name'")

    open suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean = false
}

fun mainPageOf(vararg pages: Pair<String, String>): List<Pair<String, String>> = pages.toList()

fun MainAPI.fixUrl(url: String): String =
    if (url.startsWith("http://") || url.startsWith("https://")) url
    else mainUrl.trimEnd('/') + "/" + url.trimStart('/')

fun MainAPI.fixUrlNull(url: String): String? =
    if (url.isBlank()) null else fixUrl(url)

fun newHomePageResponse(list: List<HomePageList>, hasNextPage: Boolean = false): HomePageResponse =
    HomePageResponse(list, hasNextPage)

fun newHomePageResponse(homePageList: HomePageList, hasNextPage: Boolean = false): HomePageResponse =
    HomePageResponse(listOf(homePageList), hasNextPage)

fun newHomePageResponse(
    name: String?,
    list: List<SearchResponse>,
    hasNextPage: Boolean = false,
): HomePageResponse = HomePageResponse(listOf(HomePageList(name, list)), hasNextPage)

fun MainAPI.newMovieSearchResponse(
    name: String,
    url: String,
    type: TvType,
    fix: Boolean = false,
    onLoad: (MovieSearchResponse.() -> Unit)? = null,
): MovieSearchResponse {
    val cleanUrl = if (fix) fixUrlNull(url) ?: url else url
    return MovieSearchResponse(name = name, url = cleanUrl, apiName = this.name, type = type).apply {
        onLoad?.invoke(this)
    }
}

fun MainAPI.newAnimeSearchResponse(
    name: String,
    url: String,
    type: TvType,
    fix: Boolean = false,
    onLoad: (AnimeSearchResponse.() -> Unit)? = null,
): AnimeSearchResponse {
    val cleanUrl = if (fix) fixUrlNull(url) ?: url else url
    return AnimeSearchResponse(name = name, url = cleanUrl, apiName = this.name, type = type).apply {
        onLoad?.invoke(this)
    }
}

fun MainAPI.newTvSeriesSearchResponse(
    name: String,
    url: String,
    type: TvType,
    fix: Boolean = false,
    onLoad: (TvSeriesSearchResponse.() -> Unit)? = null,
): TvSeriesSearchResponse {
    val cleanUrl = if (fix) fixUrlNull(url) ?: url else url
    return TvSeriesSearchResponse(name = name, url = cleanUrl, apiName = this.name, type = type).apply {
        onLoad?.invoke(this)
    }
}

suspend fun MainAPI.newMovieLoadResponse(
    name: String,
    url: String,
    type: TvType,
    dataUrl: String,
    onLoad: suspend MainAPI.(MovieLoadResponse) -> Unit = {},
): MovieLoadResponse {
    val response = MovieLoadResponse(
        name = name,
        url = url,
        apiName = this.name,
        type = type,
        dataUrl = dataUrl,
    )
    onLoad(this, response)
    return response
}

suspend fun MainAPI.newAnimeLoadResponse(
    name: String,
    url: String,
    type: TvType,
    fix: Boolean = false,
    onLoad: suspend MainAPI.(AnimeLoadResponse) -> Unit = {},
): AnimeLoadResponse {
    val cleanUrl = if (fix) fixUrlNull(url) ?: url else url
    val response = AnimeLoadResponse(
        engName = name,
        name = name,
        url = cleanUrl,
        apiName = this.name,
        type = type,
    )
    onLoad(this, response)
    return response
}

suspend fun MainAPI.newTvSeriesLoadResponse(
    name: String,
    url: String,
    type: TvType,
    episodes: List<Episode>,
    onLoad: suspend MainAPI.(TvSeriesLoadResponse) -> Unit = {},
): TvSeriesLoadResponse {
    val response = TvSeriesLoadResponse(
        name = name,
        url = url,
        apiName = this.name,
        type = type,
        episodes = episodes,
    )
    onLoad(this, response)
    return response
}

fun <T> MainAPI.newEpisode(name: T, onLoad: (Episode.() -> Unit)? = null): Episode {
    val episode = Episode(data = name as? String)
    onLoad?.invoke(episode)
    return episode
}

fun MainAPI.newEpisode(
    name: String,
    onLoad: (Episode.() -> Unit)? = null,
    addToShowNameMap: Boolean = true,
): Episode {
    val episode = Episode(data = name)
    onLoad?.invoke(episode)
    return episode
}

fun AnimeLoadResponse.addEpisodes(status: DubStatus, episodes: List<Episode>) {
    this.episodes = this.episodes + (status to episodes)
}

fun AnimeSearchResponse.addSub(episode: Int? = null) {
    dubStatus = dubStatus + DubStatus.Subbed
    if (episode != null) episodes = episodes + (DubStatus.Subbed to episode)
}

fun AnimeSearchResponse.addDub(episode: Int? = null) {
    dubStatus = dubStatus + DubStatus.Dubbed
    if (episode != null) episodes = episodes + (DubStatus.Dubbed to episode)
}

suspend fun newSubtitleFile(
    lang: String,
    url: String,
    onLoad: suspend (SubtitleFile) -> Unit = {},
): SubtitleFile {
    val file = SubtitleFile(lang, url)
    onLoad(file)
    return file
}
