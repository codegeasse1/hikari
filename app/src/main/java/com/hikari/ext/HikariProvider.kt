package com.hikari.ext

/** Media type used across the Hikari extension API. */
enum class HikariMediaType { MOVIE, SERIES, UNKNOWN }

/** A searchable/browsable catalog row shown on the Home screen. */
data class HikariCatalog(
    val id: String,
    val name: String,
    val type: HikariMediaType,
    /** Optional literal type string (e.g. "tv", "anime") passed through for
     *  providers that need their own type segment. */
    val rawType: String = "",
)

/** A title in a list (search results, catalogs, home rows). */
data class HikariMedia(
    val id: String,
    val title: String,
    val type: HikariMediaType,
    val posterUrl: String? = null,
    val year: Int? = null,
    val overview: String? = null,
    val genres: List<String> = emptyList(),
    val backdropUrl: String? = null,
    val rawType: String = "",
)

data class HikariEpisode(
    val number: Int,
    val id: String,
    val name: String? = null,
    val image: String? = null,
    val season: Int = 1,
)

data class HikariSubtitle(val lang: String, val url: String)

/** A playable source. Exactly one playback mode is set per stream:
 *  - HLS/MP4/MKV: [url] (optionally with [headers] the CDN requires)
 *  - torrent: [infoHash] + [trackers] (played by the built-in TorrServer)
 *  - YouTube: [ytId]
 *  - external: [externalUrl] (opened in the browser) */
data class HikariStream(
    val name: String,
    val url: String = "",
    val headers: Map<String, String> = emptyMap(),
    val subtitles: List<HikariSubtitle> = emptyList(),
    val isM3u8: Boolean = false,
    val isMpd: Boolean = false,
    val isTorrent: Boolean = false,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val trackers: List<String> = emptyList(),
    val ytId: String? = null,
    val externalUrl: Boolean = false,
)

/**
 * A Hikari extension provider.
 *
 * Every method has a safe default, so a minimal extension only implements
 * [search] + [getStreams], or [catalogs] + [getCatalog] for a catalog-only
 * source. All [HikariNet] helpers (HTTP, JSON, WebView m3u8 capture) work
 * inside these methods, and the app's own libraries (org.json, jsoup) are on
 * the classpath too.
 *
 * Distribution: compile against these classes (they ship in the Hikari APK,
 * so the app resolves them at runtime), dex the jar with `d8`, add a
 * `manifest.json` with a `"mainClass"` entry naming a class implementing this
 * interface, and install the `.hiki` file (Extensions → Install .hiki). See
 * docs/HIKARI_EXTENSIONS.md in the repo for the full guide.
 */
interface HikariProvider {
    /** Stable unique slug, e.g. "yts". */
    val id: String
    val name: String
    val mainUrl: String
    val description: String get() = ""
    val version: Int get() = 1
    val iconUrl: String? get() = null
    /** Which media types this provider serves. */
    val tvTypes: Set<HikariMediaType>
        get() = setOf(HikariMediaType.MOVIE, HikariMediaType.SERIES)

    fun catalogs(): List<HikariCatalog> = emptyList()
    suspend fun getCatalog(catalog: HikariCatalog, page: Int): List<HikariMedia> = emptyList()
    suspend fun search(query: String, page: Int): List<HikariMedia> = emptyList()
    suspend fun getMeta(media: HikariMedia): HikariMedia = media
    suspend fun getEpisodes(media: HikariMedia): List<HikariEpisode>? = null
    suspend fun getStreams(media: HikariMedia, episode: HikariEpisode?): List<HikariStream>
}
