package com.hikari.app.data

enum class ProviderType {
    STREMIO, UNIVERSAL, CS3, HIKARI, NUVIO, SKYSTREAM, ANIYOMI, IPTV,
    VEGA,
    MANGA,
    SORA,
    ANYMEX,
    ANYMEX_MANGA,
    ZANGETSU,
    FORK_VIDEO;

    val groupLabel: String
        get() = when (this) {
            STREMIO -> "Stremio"
            NUVIO -> "Nuvio"
            VEGA -> "Vega"
            CS3 -> "CloudStream"
            SKYSTREAM -> "SkyStream"
            ANIYOMI -> "Aniyomi"
            MANGA -> "Manga"
            SORA -> "Sora"
            ANYMEX -> "Anymex"
            ANYMEX_MANGA -> "Anymex"
            ZANGETSU -> "Zangetsu"
            FORK_VIDEO -> "Fork"
            IPTV -> "IPTV"
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
    val nsfw: Boolean? = null,
    val lang: String? = null,
)

/** A CloudStream-style plugin repository (repo.json → pluginLists → plugin list). */
enum class RepoKind { CS3, HIKARI, NUVIO, SKYSTREAM, ANIYOMI, VEGA, SORA, ANYMEX, LNREADER, ZANGETSU, KOTATSU }

data class ProviderFolder(
    val name: String,
    val ids: List<String>,
)

/** A plugin repository, either CloudStream (.cs3) or Hikari (.hiki) style. */
data class Cs3Repo(
    val url: String,
    val name: String,
    val description: String = "",
    val kind: RepoKind = RepoKind.CS3,
    /** The repo file's own `iconUrl` (CloudStream repo.json carries one — the
     *  Phisher avatar and friends). Blank until a refresh reads it. */
    val iconUrl: String = "",
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
    val iconManifest: String? = null,
    val iconHost: String? = null,
    val contentKind: String = "",
    val pluginLang: String? = null,
    val pkg: String = "",
    val sourceMeta: String = "",
    val nsfw: Boolean = false,
)

/** Per-repo plugin-list loading state shown in the Extensions screen. */
data class RepoLoadState(
    val loading: Boolean = false,
    val error: String? = null,
)

enum class MediaType { MOVIE, SERIES, UNKNOWN }

fun isSiteScraperProviderId(providerId: String): Boolean {
    val p = providerId.lowercase()
    return p.startsWith("cs3|") || p.startsWith("hiki|") || p.startsWith("sky|") ||
        p.startsWith("aniyomi|") || p.startsWith("vega|") || p.startsWith("sora|") ||
        p.startsWith("anymex|") || p.startsWith("uni|") || p.startsWith("forkvideo|")
}

fun screenTypeFor(item: MediaItem, episodeCount: Int?): MediaType {
    if (episodeCount == null || episodeCount <= 0) return item.type
    if (!isSiteScraperProviderId(item.providerId)) return item.type
    return when {
        episodeCount == 1 && item.type != MediaType.MOVIE -> MediaType.MOVIE
        episodeCount >= 2 && item.type != MediaType.SERIES -> MediaType.SERIES
        else -> item.type
    }
}

/** Titles proven to play as one single video although they are typed SERIES.
 *  Noted centrally whenever a SERIES item yields servers with no episode
 *  chosen (see ContentRepository.fetchStreams): a real series is never
 *  played without an episode credential, so servers on a null-episode call
 *  mean the "series" is one video wearing the wrong type. The detail screen
 *  reads this when its episode list comes back empty (see DetailScreen) and
 *  corrects the chip instead of showing an episode UI for a film. Bounded:
 *  playback evidence must never grow with the catalogue. */
object SingleVideoEvidence {
    private const val MAX_IDS = 300
    private val ids = LinkedHashMap<String, Unit>(MAX_IDS, 0.75f, true)
    @Synchronized fun note(uniqueId: String) {
        if (uniqueId.isBlank()) return
        ids[uniqueId] = Unit
        while (ids.size > MAX_IDS) ids.remove(ids.keys.first())
    }
    @Synchronized fun isSingle(uniqueId: String): Boolean = ids.containsKey(uniqueId)
}

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
    /** TMDB's `vote_average` (0–10) when the item came from TMDB — what the
     *  poster's optional rating badge shows. Null for extension items, whose
     *  catalogs never carry a score. */
    val rating: Double? = null,
    val originalTitle: String = "",
    val nsfw: Boolean = false,
    val quality: String? = null,
    val nextEpisodeDate: String? = null,
    val metadataSource: String? = null,
) {
    val uniqueId: String get() = "$providerId|$type|$id"

    val searchTitle: String get() = originalTitle.trim().ifBlank { title.trim() }

    /** Every name this item is known by, display name first and the original
     *  name last. Lookups that can afford to try more than one name walk this. */
    val allTitles: List<String>
        get() = listOf(title.trim(), originalTitle.trim())
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
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
    /** What this episode is about (TMDB/stremio overviews, extension
     *  descriptions) — the detail rows print it under the title so a
     *  mid-list episode can be recognised without playing it. */
    val overview: String? = null,
    /** First-air/release date as the provider printed it ("1999-10-20"). */
    val released: String? = null,
    /** TMDB's own vote average for this episode (the "Rated: 6.0" line the
     *  reference clients print under each episode title). */
    val rating: Double? = null,
    /** Episode runtime in minutes (the "25m" beside the rating). */
    val runtime: Int? = null,
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

data class CompanyRef(
    val id: String,
    val name: String,
    val logoUrl: String? = null,
    val isNetwork: Boolean = false,
)

/** A franchise: TMDB's `belongs_to_collection` plus the parts of that
 *  collection — "Shrek Collection" and its films, in the reference client's own
 *  row on the detail page. */
data class TitleCollection(
    val id: String,
    val name: String,
    val items: List<MediaItem> = emptyList(),
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
    /** TMDB's `release_date`/`first_air_date` as it publishes it ("2001-05-18").
     *  The year alone is in [year]; this is the full date the reference client
     *  prints as "Release Info". */
    val releaseDate: String? = null,
    val director: String? = null,
    val writers: List<String> = emptyList(),
    /** TMDB's `external_ids.imdb_id` — the key the ratings lookup uses for
     *  Wikidata and Letterboxd. Null when TMDB doesn't know one. */
    val imdbId: String? = null,
    /** TMDB's own genre names (in the app's TMDB language). Used only as a
     *  fallback where the provider's own row carried no genres (the TV home
     *  header); the detail page keeps showing the provider's genres. */
    val genres: List<String> = emptyList(),
)

/** Everything the detail page's extra sections need — the details block, the
 *  Cast row and the Trailers row — fetched together in one TMDB call. */
data class TitleExtras(
    val details: TitleDetails? = null,
    val cast: List<CastMember> = emptyList(),
    val trailers: List<Trailer> = emptyList(),
    /** True when [cast] holds the CHARACTERS of an anime (with the Japanese
     *  voice actors as each one's second line) rather than TMDB's voice-actor
     *  credits — the row is then titled "Characters" instead of "Cast". */
    val castIsCharacters: Boolean = false,
    val localizedTitle: String? = null,
    /** TMDB's `original_title`/`original_name` — the name EXTENSIONS index the
     *  title under, which is what a provider lookup has to search for (see
     *  [MediaItem.originalTitle]). */
    val originalTitle: String? = null,
    val overview: String? = null,
    /** The title's production companies and networks — the Production row. */
    val companies: List<CompanyRef> = emptyList(),
    /** The franchise this title belongs to, with its other parts. */
    val collection: TitleCollection? = null,
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
    val episodeNumber: Int = 0,
    val seasonNumber: Int = 0,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val watchedAt: Long = 0L,
) {
    /** Dedup key: one entry per video (movie = mediaId, series = per episode). */
    val uniqueKey: String get() = "$providerId|$type|$mediaId|$episodeId"
}

data class SubtitleSource(
    val lang: String,
    val url: String,
    val name: String = "",
    val headers: Map<String, String> = emptyMap(),
)

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
    val details: String = "",
    val pageUrl: String = "",
    val torrentFileHint: String? = null,
    /** Same row's declared byte size (behaviorHints.videoSize), 0 = unknown. */
    val torrentFileSize: Long = 0L,
    /** Novel text for one chapter part (plain-Source novel extensions): the
     *  image reader ignores rows with a blank url, so text chapters travel
     *  here instead of as images — the novel reader joins them. Blank for
     *  every video/image source. */
    val text: String = "",
) {
    fun identityKey(): String =
        infoHash?.takeIf { it.isNotBlank() }?.let { h ->
            val idx = fileIdx?.let { "i$it" }
                ?: ("n:" + (torrentFileHint?.trim().orEmpty().ifBlank { name.trim() }.take(120) +
                    (if (torrentFileSize > 0) "#$torrentFileSize" else "")))
            "h${h.lowercase()}#$idx"
        } ?: "u$url"
}

data class CatalogRef(
    val providerId: String,
    val type: MediaType,
    val id: String,
    val name: String,
    /** Addon's literal catalog type string — used verbatim in Stremio URLs. */
    val rawType: String = "",
)

enum class CatalogSourceKind { TMDB, PROVIDER, ITEMS }

/** One catalog inside a folder: either a TMDB preset or an installed catalog. */
data class CatalogSource(
    val kind: CatalogSourceKind = CatalogSourceKind.TMDB,
    /** Display name of the source ("HBO", "Trending Now"). */
    val title: String = "",
    /** [CatalogSourceKind.PROVIDER] only: the installed extension's id. */
    val providerId: String = "",
    /** [CatalogSourceKind.PROVIDER] only: the catalog's own id + type. */
    val catalogId: String = "",
    val type: MediaType = MediaType.UNKNOWN,
    val rawType: String = "",
    /** [CatalogSourceKind.TMDB] only: the key of a [com.hikari.app.data.TmdbPresets] entry. */
    val tmdbPreset: String = "",
    val tmdbSpec: String = "",
    val itemsJson: String = "",
    val uid: String = "",
) {
    /** The hand-built source behind this entry, or null for a plain preset. */
    val spec: TmdbSpec? get() = if (kind == CatalogSourceKind.TMDB) TmdbSpec.decode(tmdbSpec) else null

    /** How many titles an [CatalogSourceKind.ITEMS] source holds. */
    val itemCount: Int
        get() = if (kind == CatalogSourceKind.ITEMS)
            runCatching { org.json.JSONArray(itemsJson).length() }.getOrDefault(0)
        else 0

    val key: String
        get() = when (kind) {
            CatalogSourceKind.TMDB -> "tmdb|" + tmdbSpec.ifBlank { tmdbPreset }
            CatalogSourceKind.ITEMS -> "items|" + uid.ifBlank { itemsJson.hashCode().toString(16) }
            CatalogSourceKind.PROVIDER -> "prov|$providerId|$type|$catalogId"
        }
}

data class LibraryCategory(
    val id: String,
    val name: String,
    val builtIn: Boolean = false,
) {
    companion object {
        const val MOVIES = "cat-movies"
        const val SERIES = "cat-series"
        const val ACTION = "cat-action"
        const val ROMANCE = "cat-romance"

        /** What a brand-new install starts with. */
        val DEFAULTS: List<LibraryCategory> = listOf(
            LibraryCategory(MOVIES, "Movies", builtIn = true),
            LibraryCategory(SERIES, "Series", builtIn = true),
            LibraryCategory(ACTION, "Action", builtIn = true),
            LibraryCategory(ROMANCE, "Romance", builtIn = true),
        )
    }
}

data class CollectionFolder(
    val id: String,
    val name: String,
    val sources: List<CatalogSource> = emptyList(),
    val coverKind: String = CoverKinds.NONE,
    val coverValue: String = "",
    val tileShape: String = TileShapes.POSTER,
    /** Draw the folder's tile with no name under it — just the cover. The name
     *  still appears in the editor, in search and in the folder's own header;
     *  a wall of tiles whose artwork already says "Netflix" reads better
     *  without the caption. */
    val hideTitle: Boolean = false,
    /** Keep an animated GIF playing even when the tile is not focused. Off by
     *  default: a screen of twenty animating GIFs is a battery fire, so [CoverKinds.GIF]
     *  only plays while its tile has focus unless this is on (the user can also
     *  force it per device — see AppStore's gifAnimFlow). */
    val gifAlways: Boolean = false,
    /** The folder's own wide backdrop, shown behind its page header the way the
     *  reference app shows a folder's hero. Blank = the cover stands in. */
    val heroBackdropUrl: String = "",
    /** A transparent title-logo image for the folder's page hero. Blank = the
     *  folder's name is drawn as text. */
    val titleLogoUrl: String = "",
)

/** How a collection's folders are browsed on the collection's own page. */
object CollectionViewModes {
    /** One shelf per folder, stacked (the app's original shape). */
    const val ROWS = "rows"
    /** A tab strip: one tab per folder, the picked folder's catalogs below it. */
    const val TABS = "tabs"
    val ALL = listOf(ROWS, TABS)
    fun normalize(key: String?): String = if (key in ALL) key as String else ROWS
}

/** How a collection/folder tile is shaped. The cover is drawn into it. */
object TileShapes {
    /** 2:3 — a poster, the default (what a grid of posters looks like). */
    const val POSTER = "poster"
    /** 1:1 — a square tile. */
    const val SQUARE = "square"
    /** 16:9 — a wide/backdrop tile. */
    const val WIDE = "wide"
    val ALL = listOf(POSTER, SQUARE, WIDE)
    fun normalize(key: String?): String = if (key in ALL) key as String else POSTER
    /** width / height for [key]. */
    fun aspect(key: String?): Float = when (normalize(key)) {
        SQUARE -> 1f
        WIDE -> 16f / 9f
        else -> 2f / 3f
    }
}

/** What a collection/folder tile shows behind its name. */
object CoverKinds {
    const val NONE = "none"
    const val EMOJI = "emoji"
    /** A still image: an https:// URL, or a file:// path to a copy the app
     *  made from the user's gallery (see [com.hikari.app.ui.CollectionCovers]). */
    const val URL = "url"
    /** An animated GIF URL — the same field, played only while the tile has
     *  focus (a wall of animating GIFs is a battery fire). */
    const val GIF = "gif"
    val ALL = listOf(NONE, EMOJI, URL, GIF)
    fun normalize(kind: String?): String = if (kind in ALL) kind as String else NONE
}

data class Collection(
    val id: String,
    val name: String,
    val folders: List<CollectionFolder> = emptyList(),
    val coverKind: String = CoverKinds.NONE,
    val coverValue: String = "",
    /** The shape of this collection's own tile in the collections grid. */
    val tileShape: String = TileShapes.POSTER,
    /** Pinned to the top of Home: a pinned collection's folders are drawn on
     *  Home even when nothing is picked, which is what makes a personal catalog
     *  behave like a folder row you own rather than something you have to
     *  remember to select. */
    val pinToTop: Boolean = false,
    /** Rows (the default) or a tab strip per folder — see [CollectionViewModes]. */
    val viewMode: String = CollectionViewModes.ROWS,
    /** Give the Tabs view an extra "All" tab holding every folder's catalogs. */
    val showAllTab: Boolean = true,
    /** A wide backdrop for the collection's own header, like a folder's hero. */
    val backdropUrl: String = "",
) {
    val isEmpty: Boolean get() = folders.isEmpty()
    /** Every source of every folder, deduped — the collection's whole diet. */
    val allSources: List<CatalogSource>
        get() = folders.flatMap { it.sources }.distinctBy { it.key }
}

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
