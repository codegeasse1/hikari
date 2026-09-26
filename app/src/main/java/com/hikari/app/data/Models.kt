package com.hikari.app.data

enum class ProviderType {
    STREMIO, UNIVERSAL, CS3, HIKARI, NUVIO, SKYSTREAM, ANIYOMI, IPTV,
    /**
     * A VEGA provider — one folder of CommonJS modules published by
     * `github.com/Zenda-Cross/vega-providers` (AniKoto, 4KHDHub, Showbox,
     * NetflixMirror, …). Unlike a nuvio provider it carries its own catalogue,
     * search, details and episodes as well as streams, so it behaves like a
     * whole site (see com.hikari.app.providers.vega.VegaProvider).
     */
    VEGA,
    /**
     * A MANGA extension — a Mihon/Tachiyomi-format `.apk` (keiyoushi and the
     * other mirrors of that repo). It speaks the same `eu.kanade.tachiyomi.*`
     * API the Aniyomi side does, but its catalogues are titles, its "episodes"
     * are chapters and its "streams" are page images, which is why the reader
     * and the manga detail screen are the screens that open on them (see
     * com.hikari.app.manga).
     */
    MANGA;

    /**
     * Which section of the player's server chooser a source from this engine
     * belongs to. The player divides the servers it found into one group per
     * engine — CloudStream plugins, Hikari's own extensions (and its universal
     * scrapers), Nuvio providers, Vega providers, Stremio addons — so the
     * picker reads like the reference client's grouped source list instead of
     * one undifferentiated column of links.
     */
    val groupLabel: String
        get() = when (this) {
            STREMIO -> "Stremio"
            NUVIO -> "Nuvio"
            VEGA -> "Vega"
            CS3 -> "CloudStream"
            SKYSTREAM -> "SkyStream"
            ANIYOMI -> "Aniyomi"
            MANGA -> "Manga"
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
    /**
     * Whether the extension this row was installed from is tagged 18+, as its
     * repo listing declared it. Null = nobody has said yet.
     *
     * It is recorded here, on the row, because it is the only answer that is
     * available WITHOUT loading the extension: everything else needs the plugin
     * itself (its own `supportedTypes`, its metadata) or the repo it came from —
     * and with the adult-content switch off, an installed 18+ extension whose
     * repo is no longer added would otherwise be looked up by loading it, which
     * is exactly the wrong way round. See
     * [com.hikari.app.data.ExtensionNsfw] for the whole rule and
     * [com.hikari.app.providers.ProviderManager.learnAdultFlags] for how an
     * older install fills it in.
     */
    val nsfw: Boolean? = null,
)

/** A CloudStream-style plugin repository (repo.json → pluginLists → plugin list). */
enum class RepoKind { CS3, HIKARI, NUVIO, SKYSTREAM, ANIYOMI, VEGA }

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
    /**
     * Where to find this entry's icon when the repo listing itself declares
     * none. SkyStream `.sky` entries carry an `addons` array of Stremio
     * manifest URLs, and that manifest's `logo` is the extension's real icon
     * (the `.sky`'s own plugin.json has no icon field at all) — the first
     * addon URL is captured here so the row can resolve a logo lazily.
     */
    val iconManifest: String? = null,
    /**
     * The extension's own site host (`domains[0]`, else the `baseUrl` host),
     * used as the LAST-resort icon via Google's favicon service — the same
     * trick the plugin repos themselves use. Null when the listing only names
     * a placeholder host (`stremio-hub.local` and friends), where a favicon
     * lookup could never resolve.
     */
    val iconHost: String? = null,
    /**
     * What a Mihon/Aniyomi entry SERVES: `"manga"`, `"anime"`, or `""` when the
     * listing does not say.
     *
     * One index can hold both kinds — the two ecosystems publish the same file
     * format out of the same folders — and a reader looking for a manga engine
     * in a 1396-entry list should not have to open the anime rows to find out
     * which is which. See [com.hikari.app.aniyomi.AniyomiExtensionManager
     * .contentKindOf], which decides it from the entry's own shape and from
     * what the app has already installed.
     *
     * Empty is the honest answer for a CloudStream/Hikari/SkyStream/Nuvio
     * entry: those are all video extensions, and the manga/anime split does not
     * exist among them.
     */
    val contentKind: String = "",
    /**
     * The extension's own PACKAGE NAME, when the listing declares one
     * (`packageName`, or the legacy `pkg`).
     *
     * It is the one identifier that survives everything the row goes through: a
     * repo can move its APK between hosts, a mirror can rewrite the download URL
     * and a rebuild changes the version, but the package is what an installed
     * extension is keyed by. The Extensions screen pairs an entry with what is
     * installed through it — which is how a repo's list knows, without
     * downloading anything, which of its entries the user already has as a
     * manga engine and which as an anime one.
     */
    val pkg: String = "",
    /**
     * Whether the extension itself is tagged 18+ by the repo that publishes it —
     * CloudStream's `tvTypes` containing `NSFW`, a Mihon/Aniyomi index entry's
     * `nsfw`/`contentWarning` (see the manager that parses it).
     *
     * The listing is the only place this is knowable BEFORE an install, and it
     * is what the adult-content switch hides from the store lists: with the
     * switch off an 18+ extension is not offered for install in the first place
     * (see [NsfwGate], and [com.hikari.app.data.ExtensionNsfw] for the check on
     * ones that are already installed).
     */
    val nsfw: Boolean = false,
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
    /** TMDB's `vote_average` (0–10) when the item came from TMDB — what the
     *  poster's optional rating badge shows. Null for extension items, whose
     *  catalogs never carry a score. */
    val rating: Double? = null,
    /**
     * The title a PROVIDER knows this item by, when it differs from [title].
     *
     * [title] is what the USER reads, and with a TMDB content language set it is
     * TMDB's localized name ("Vengadores: Endgame"). Every installed extension
     * still indexes the ORIGINAL name ("Avengers: Endgame"), so searching them
     * with the localized one found nothing — the reported "I changed the TMDB
     * language and now there is no extension for this movie", and a large part
     * of the "no playable sources" reports: the whole cross-extension pass was
     * asking 250 repos a name none of them has.
     *
     * TMDB hands us `original_title` / `original_name` in the very same response
     * it localizes the title from, so this is filled in for every TMDB-sourced
     * item (rows, shelves, presets, a one-title source, and a page opened from
     * one). Blank for an extension item, whose own [title] already IS the name
     * its sites use.
     */
    val originalTitle: String = "",
    /**
     * The source says this item IS adult material — TMDB's `adult` flag, or a
     * provider whose own type/metadata says so (CloudStream's `TvType.NSFW`).
     *
     * Only the explicit markers live here; the rest of the rule (18+/adult genre
     * tags, the name itself, an adults-only certificate) is applied by
     * [com.hikari.app.data.NsfwGate], which is also where the setting and the
     * reasoning live. Keeping the flag on the item means a catalogue page, a
     * search result and a cached shelf all carry their own answer.
     */
    val nsfw: Boolean = false,
    /**
     * The best quality the SOURCE ITSELF states for this item, already in the
     * poster badge's own vocabulary ("4K", "1080p", "HDR", …).
     *
     * Only an extension that scrapes a site can fill this in: CloudStream
     * providers carry a `SearchQuality` on every search result, because the site
     * they read knows what it serves (an "1080p WEB-DL" row is not a guess). So
     * for those catalogs the poster's quality badge shows the real answer
     * immediately, without opening the title and without playing it. Addons that
     * resolve streams instead of scraping a catalog (Nuvio, Stremio) leave it
     * null — nothing is known about their quality until a stream is picked, and
     * a badge invented from nothing would be a lie on the poster. See
     * [com.hikari.app.data.TitleQuality].
     */
    val quality: String? = null,
) {
    val uniqueId: String get() = "$providerId|$type|$id"

    /**
     * The name to ASK PROVIDERS with: the original/English title when the item
     * carries one, else the display title. Nothing user-facing should print
     * this — it exists so a title localised for display never becomes the search
     * key (see [originalTitle]).
     */
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

/** A production company or a TV network of a title — one tile of the detail
 *  page's Production row, and the entity its grid is opened with.
 *
 *  [isNetwork] decides which TMDB query the grid runs: a network's shows are
 *  filed under `/discover/tv?with_networks=`, a studio's films and shows under
 *  `/discover/{movie,tv}?with_companies=`. */
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
    /**
     * TMDB's own LOCALIZED name for this title (the `title`/`name` field of the
     * detail response, answered in the app's chosen TMDB language).
     *
     * The player's "artwork while loading" card prints the title it was handed by
     * the screen that opened it, which for an item that came from an EXTENSION is
     * the site's own (English) name — so with a non-English TMDB language set,
     * the page was translated but the loading card was not. This is that same
     * name in the chosen language, and the play intents pass it along.
     */
    val localizedTitle: String? = null,
    /** TMDB's `original_title`/`original_name` — the name EXTENSIONS index the
     *  title under, which is what a provider lookup has to search for (see
     *  [MediaItem.originalTitle]). */
    val originalTitle: String? = null,
    /**
     * TMDB's LOCALIZED plot summary — the `overview` field of the same detail
     * response [localizedTitle] comes from, answered in the app's chosen TMDB
     * language.
     *
     * This is the fix for "the description turns English when it finds no
     * server". For an item whose page was opened from an EXTENSION (or a TMDB
     * row the app remapped onto one), the only text the page ever had for its
     * description came from the provider's own meta — the site's English blurb —
     * and with a non-English TMDB language set that is a page whose title is
     * translated and whose description is not. TMDB localizes both in one
     * response, so this is used in preference whenever it exists, and the
     * provider's text only ever fills a blank.
     */
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
    /**
     * The episode and season number this entry is for (0 = not known).
     *
     * Kept as numbers rather than read back out of [episodeName], because two
     * things need them and both need them exact: "Continue watching" says which
     * episode is next, and the trackers (Settings → Trackers) report progress as
     * a NUMBER ("episode 12"), where a mis-parsed 1 in "Season 1 E12" would mark
     * the wrong episode watched on the user's own list.
     */
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
    /**
     * Which addon offered this track ("OpenSubtitles v3", "SubDL"). Shown as the
     * subtitle row's second line, so two addons' identically-named tracks can be
     * told apart in the player. Blank for a track a provider attached to its own
     * stream.
     */
    val name: String = "",
    /**
     * Headers the DOWNLOAD needs, for the sites that serve a subtitle only to a
     * request that carries the page it was listed on (OpenSubtitles' mirror, for
     * example, is asked with its own site as the Referer — see
     * [com.hikari.app.subtitles.SubtitleSites]). Empty for an addon's track,
     * which needs none.
     */
    val headers: Map<String, String> = emptyMap(),
)

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
    /**
     * For a manga page: the URL of the page ON THE SITE, i.e. the reader's
     * `Page.url` — `url` above is the image. The video player ignores this; the
     * reader needs it because a manga extension builds the image request from
     * the pair (`imageRequest(page)` in the extension reads `page.url` for the
     * Referer/Origin it has to send), so a page fetched with an empty one is the
     * hotlink-refused, scrambled-page report (see
     * [com.hikari.app.reader.source.HikariPageSource]).
     */
    val pageUrl: String = "",
)

data class CatalogRef(
    val providerId: String,
    val type: MediaType,
    val id: String,
    val name: String,
    /** Addon's literal catalog type string — used verbatim in Stremio URLs. */
    val rawType: String = "",
)

/**
 * Where one catalog source inside a folder comes from.
 *
 * [TMDB] sources ask TMDB's discovery API for a ready-made slice of its
 * catalogue (a production company's films, a network's series) — no extension
 * has to be installed for those to work. [PROVIDER] sources point at one
 * catalog of an installed extension, so a folder is not limited to TMDB: any
 * catalog an addon exposes can sit next to a preset. [ITEMS] sources are a list
 * of titles the user imported (a Nuvio/Stremio export, a shared JSON file) and
 * stored inside the collection — see [NuvioCatalogImport].
 */
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
    /**
     * [CatalogSourceKind.TMDB] only: a hand-built TMDB source (a public list, a
     * production company, a network, a collection, a person, a director, or a
     * custom discover query) as JSON — see [TmdbSpec]. Blank for a preset, so
     * collections saved before this existed keep working untouched.
     */
    val tmdbSpec: String = "",
    /**
     * [CatalogSourceKind.ITEMS] only: the imported titles, as the compact JSON
     * array [NuvioCatalogImport] reads and writes. Kept INSIDE the collection
     * because an imported list has no server (or extension) behind it — the
     * collection is the whole of its existence.
     */
    val itemsJson: String = "",
    /**
     * [CatalogSourceKind.ITEMS] only: this source's own stable id.
     *
     * Unlike the other kinds an imported list has no natural identity — it has
     * no provider, no catalog id, and the user may rename it or add titles to it
     * at any time — so it is given one when it is created. The key must not be
     * derived from its contents or its name: either would change the row's
     * Compose key mid-edit and remount it under the user's finger.
     */
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

/**
 * One category a Library title is filed under (Movies, Series, Action, Romance,
 * or any name the user invents).
 *
 * [id] is stable and never shown; renaming a category keeps every title filed
 * under it. [builtIn] marks the four categories a fresh install starts with, so
 * Settings can offer to restore them without resurrecting a deleted custom one.
 */
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

/**
 * One folder inside a collection — a named group of catalog sources. The
 * reference client's "folders" are what make a collection useful for a user
 * who only watches one kind of thing: instead of one long mixed feed, each
 * folder answers exactly one question ("Marvel films", "HBO series").
 *
 * A folder can also wear a cover of its own (an emoji, an image URL, or a GIF)
 * and pick the shape of its tile on the collection page — see [TileShapes] and
 * [CoverKinds]. Stored as plain strings so a save written before these existed
 * simply parses back to "no cover, poster shape".
 */
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

/**
 * A user-made collection: a name plus one or more folders of catalog sources.
 *
 * Selecting a collection in Home's extension picker replaces the feed with the
 * collection's folders (one row per folder, each holding that folder's
 * catalogs), so the user sees only what they asked for instead of every
 * installed extension's home page. The collection itself is stored in
 * [AppStore] and is what "abc" in the reference screenshots is.
 */
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
