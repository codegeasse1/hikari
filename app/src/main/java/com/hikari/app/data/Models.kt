package com.hikari.app.data

enum class ProviderType {
    STREMIO, UNIVERSAL, CS3, HIKARI, NUVIO;

    /**
     * Which section of the player's server chooser a source from this engine
     * belongs to. The player divides the servers it found into one group per
     * engine — CloudStream plugins, Hikari's own extensions (and its universal
     * scrapers), Nuvio providers, Stremio addons — so the picker reads like the
     * reference client's grouped source list instead of one undifferentiated
     * column of links.
     */
    val groupLabel: String
        get() = when (this) {
            STREMIO -> "Stremio"
            NUVIO -> "Nuvio"
            CS3 -> "CloudStream"
            HIKARI, UNIVERSAL -> "Hikari"
        }
}

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
enum class RepoKind { CS3, HIKARI, NUVIO }

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
    /** Season this episode belongs to (1 when a provider has no season info).
     *  Lets the detail screen group a multi-season show into a season picker
     *  instead of dumping every episode of every season into one flat list. */
    val season: Int = 1,
)

/** One cast member from TMDB's `credits` — the detail page's Cast row. */
data class CastMember(
    val name: String,
    val character: String? = null,
    val profileUrl: String? = null,
)

/** One trailer/teaser from TMDB's `videos` — the detail page's Trailers row. */
data class Trailer(
    val youtubeKey: String,
    val name: String,
    val type: String = "Trailer",
    val thumbnailUrl: String? = null,
)

/** The "Show Details" metadata block on the detail page, from a TMDB
 *  `/movie/{id}` or `/tv/{id}` response. Every field is optional: TMDB omits
 *  plenty of them, and a missing field simply drops out of the UI. */
data class TitleDetails(
    val status: String? = null,
    val runtimeMinutes: Int? = null,
    val year: Int? = null,
    val rating: Double? = null,
    val voteCount: Int? = null,
    val certification: String? = null,
    val country: String? = null,
    val language: String? = null,
    val director: String? = null,
    val writers: List<String> = emptyList(),
    /** TMDB's `external_ids.imdb_id` — the key the ratings lookup uses for
     *  Wikidata and Letterboxd. Null when TMDB doesn't know one. */
    val imdbId: String? = null,
)

/** Everything the detail page's extra sections need — the details block, the
 *  Cast row and the Trailers row — fetched together in one TMDB call. */
data class TitleExtras(
    val details: TitleDetails? = null,
    val cast: List<CastMember> = emptyList(),
    val trailers: List<Trailer> = emptyList(),
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

/** DRM info for a protected stream, carried from the extracting extension so
 *  the player can open a matching media3 DRM session instead of showing a black
 *  screen on a protected manifest. Mirrors CloudStream's `DrmExtractorLink`.
 *
 *  ClearKey streams carry [kid]+[key] (played from a local key, no network);
 *  Widevine/PlayReady streams carry [licenseUrl] (the player asks the license
 *  server for keys, attaching [keyRequestParameters]). */
data class DrmSpec(
    val kid: String? = null,
    val key: String? = null,
    /** DRM scheme UUID (string form); null = infer from key/licenseUrl. */
    val uuid: String? = null,
    /** ClearKey key type — defaults to "oct" (symmetric key). */
    val kty: String? = null,
    val licenseUrl: String? = null,
    val keyRequestParameters: Map<String, String> = emptyMap()
)

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
    /** DRM protection info (ClearKey/Widevine) — null for ordinary streams. */
    val drm: DrmSpec? = null,
    /** Which engine produced this source ("CloudStream", "Hikari", "Nuvio",
     *  "Stremio"). The player's server chooser groups by this, so each engine's
     *  servers sit under their own heading; blank when the origin is unknown
     *  (the chooser then falls back to an "Other" section). */
    val provider: String = "",
    /** The installed provider's own id (`cs3|…`, `hiki|…`, `nuvio|…`) — what
     *  the player uses to put the provider a title was opened from at the FRONT
     *  of the server list (its own servers are the ones the user expects). */
    val providerId: String = "",
    /** The installed provider's display name. The player's server chooser uses
     *  it for the heading of that provider's own section. */
    val providerName: String = "",
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
