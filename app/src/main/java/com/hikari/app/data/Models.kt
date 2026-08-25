package com.hikari.app.data

enum class ProviderType { STREMIO, UNIVERSAL, CS3, HIKARI }

data class ProviderConfig(
    val id: String,
    val name: String,
    val type: ProviderType,
    val url: String = "",
    val iconUrl: String? = null,
    val enabled: Boolean = true,
    val extra: String? = null,
)

/** A CloudStream-style plugin repository (repo.json → pluginLists → plugin list). */
enum class RepoKind { CS3, HIKARI }

/** A plugin repository, either CloudStream (.cs3) or Hikari (.hiki) style. */
data class Cs3Repo(
    val url: String,
    val name: String,
    val description: String = "",
    val kind: RepoKind = RepoKind.CS3,
)

/** A single installable plugin entry from a CloudStream repository. */
data class Cs3RepoPlugin(
    val name: String,
    val description: String = "",
    val url: String,
    val iconUrl: String? = null,
    val authors: List<String> = emptyList(),
    val version: Int = 1,
    val tvTypes: List<String> = emptyList(),
    val fileHash: String? = null,
)

/** Per-repo plugin-list loading state shown in the Extensions screen. */
data class RepoLoadState(
    val loading: Boolean = false,
    val error: String? = null,
)

enum class MediaType { MOVIE, SERIES, UNKNOWN }

/** A user-added website opened in the ad-free web view. */
data class Site(
    val name: String,
    val url: String,
)

/** A Tampermonkey-style userscript that runs inside the WebView only. */
data class Userscript(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val code: String,
)

data class MediaItem(
    val providerId: String,
    val id: String,
    val title: String,
    val type: MediaType,
    val posterUrl: String? = null,
    val year: Int? = null,
    val overview: String? = null,
    val genres: List<String> = emptyList(),
    val backdropUrl: String? = null,
    /** The addon's OWN type string (e.g. "tv", "anime", "channel"). The Stremio
     *  protocol puts this literal string in /catalog /meta /stream URLs, and
     *  many addons refuse requests sent with a different type segment. */
    val rawType: String = "",
) {
    val uniqueId: String get() = "$providerId|$type|$id"
}

data class Episode(
    val number: Int,
    val id: String,
    val name: String? = null,
    val image: String? = null,
)

/** A single watch-history entry — what the user played and where they left off. */
data class HistoryEntry(
    val providerId: String,
    val mediaId: String,
    val type: MediaType,
    val title: String,
    val posterUrl: String? = null,
    val episodeId: String = "",
    val episodeName: String = "",
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val watchedAt: Long = 0L,
) {
    /** Dedup key: one entry per video (movie = mediaId, series = per episode). */
    val uniqueKey: String get() = "$providerId|$type|$mediaId|$episodeId"
}

data class SubtitleSource(val lang: String, val url: String)

data class StreamSource(
    val name: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val subtitles: List<SubtitleSource> = emptyList(),
    val isTorrent: Boolean = false,
    val infoHash: String? = null,
    val isM3u8: Boolean = false,
    val isMpd: Boolean = false,
    /** Torrent file index (from Stremio stream.fileIdx) — which file inside
     *  the torrent to play. */
    val fileIdx: Int? = null,
    /** Extra peer sources: tracker URLs / DHT nodes (from Stremio stream.sources). */
    val trackers: List<String> = emptyList(),
    /** YouTube video id (from Stremio stream.ytId) — played in the web view. */
    val ytId: String? = null,
    /** True when the URL should be opened in a browser (externalUrl), not the player. */
    val externalUrl: Boolean = false,
)

data class CatalogRef(
    val providerId: String,
    val type: MediaType,
    val id: String,
    val name: String,
    /** Addon's literal catalog type string — used verbatim in Stremio URLs. */
    val rawType: String = "",
)

data class CatalogRow(
    val providerId: String = "",
    val providerName: String,
    val title: String,
    val items: List<MediaItem>,
    /** Stable unique key for LazyColumn rows — must never collide, even when an
     *  addon exposes several catalogs with the same display name (e.g.
     *  "Streaming Catalogs" has both a movies and a series catalog named
     *  "Netflix"). */
    val key: String = "",
    /** The originating catalog's own id/type — lets "Show All" re-fetch the
     *  whole catalog with paging instead of only the home row's first page. */
    val catalogId: String = "",
    val type: MediaType = MediaType.UNKNOWN,
    val rawType: String = "",
)
