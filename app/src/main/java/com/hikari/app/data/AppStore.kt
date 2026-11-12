package com.hikari.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hikari.app.net.AdBlocker
import com.hikari.app.net.DnsProviders
import com.hikari.app.net.ExtensionVerifyGuard
import com.hikari.app.player.EnhancePreset
import com.hikari.app.ui.AccentStore
import com.hikari.app.ui.UiScale
import com.hikari.app.ui.theme.HikariAccent
import com.hikari.app.ui.theme.HikariThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

private val Context.hkDataStore by preferencesDataStore(name = "hikari")

/** The stream server a video was last played with, remembered so a replay
 *  starts on the same server — instantly, with the same header variant that
 *  worked last time (0 = full headers, 1 = no Referer, 2 = none). */
data class LastSource(
    val url: String,
    val name: String,
    val headerVariant: Int = 0,
)

data class PrefRecord(
    val key: String,
    /** "s" string · "b" boolean · "i" int · "l" long · "f" float · "d" double ·
     *  "ss" set of strings · "bin" base64 bytes. */
    val type: String,
    val value: Any?,
)

object MyStuffSection {
    const val LIBRARY = "library"
    const val HISTORY = "history"
    const val DOWNLOADS = "downloads"

    /** The three, in the order the strip draws them. */
    val ALL = listOf(LIBRARY, HISTORY, DOWNLOADS)
}

/** Unit separator: joins a provider folder's name to its ids inside the one
 *  stored string (see [AppStore.providerFoldersFlow]). It cannot appear in a
 *  typed name, and [AppStore.saveProviderFolder] strips it regardless. */
private const val FOLDER_SEP = "\u001F"

class AppStore(private val ctx: Context) {

    private val store get() = ctx.hkDataStore

    private object K {
        val PROVIDERS = stringPreferencesKey("providers")
        val FAVORITES = stringPreferencesKey("favorites")
        val CS3_REPOS = stringPreferencesKey("cs3Repos")
        val CS3_REMOVED_REPOS = stringPreferencesKey("cs3RemovedRepos")
        /** Repo identities the user has explicitly removed (see [removedRepoKeys]).
         *  First-run seeding consults this before re-adding a bundled default,
         *  so a default repo the user deletes stays deleted instead of coming
         *  back on the next launch. */
        /** User-made collections (name + folders of catalog sources), stored as
         *  one JSON array. See [Collection]. */
        val COLLECTIONS = stringPreferencesKey("collections")
        val SITES = stringPreferencesKey("sites")
        val USERS = stringPreferencesKey("userscripts")
        val THEME = stringPreferencesKey("theme")
        val APP_ACCENT = stringPreferencesKey("appAccent")
        val PLAYER_ACCENT = stringPreferencesKey("playerAccent")
        val THEME_LINKED = booleanPreferencesKey("themeLinked")
        val PLAYER_CONTROLS = stringPreferencesKey("playerControls")
        /** The viewer's own look for the player's buttons, as
         *  [com.hikari.app.player.PlayerUiStyle] JSON (Settings -> Customize ->
         *  Player UI style). Blank means "not customised". */
        val PLAYER_UI_STYLE = stringPreferencesKey("playerUiStyle")
        val PLAYER_ENHANCE = stringPreferencesKey("playerEnhance")
        /** True once the user has worked the video-enhance picker themselves, so
         *  the television layout stops forcing its own (light) preset. See
         *  [com.hikari.app.HikariApp] and [com.hikari.app.ui.screens.SettingsScreen]. */
        val PLAYER_ENHANCE_CHOSEN = booleanPreferencesKey("playerEnhanceChosen")
        val PLAYER_ENHANCE_UNSUPPORTED = booleanPreferencesKey("playerEnhanceUnsupported")
    /** The user's own enhance presets, as a JSON array (see
     *  [com.hikari.app.player.CustomEnhancePreset]). Deliberately NOT in
     *  [DeviceLocal]: these are the user's grading looks, not a device
     *  capability, so they travel with the setup like every other look choice. */
    val PLAYER_ENHANCE_CUSTOMS = stringPreferencesKey("playerEnhanceCustoms")
        val UI_SCALE_ENABLED = booleanPreferencesKey("uiScaleEnabled")
        val UI_SCALE_PERCENT = intPreferencesKey("uiScalePercent")
        val HISTORY = stringPreferencesKey("history")
        val HISTORY_PAUSED = booleanPreferencesKey("historyPaused")
        val HIDE_CONTINUE = booleanPreferencesKey("hideContinue")
        /** Animate animated covers on tiles (see [gifAnimFlow]). */
        val GIF_ANIM = booleanPreferencesKey("gifAnim")
        val HIDE_HELP = booleanPreferencesKey("hideHelp")
        val PROFILE_TAG = stringPreferencesKey("profileTag")
        val LAST_SOURCE = stringPreferencesKey("lastSource")
        val ELEMENT_BLOCKS = stringPreferencesKey("elementBlocks")
        val AD_ENABLED = booleanPreferencesKey("adEnabled")
        val AD_LISTS = stringPreferencesKey("adLists")
        val AD_BLOCK = stringPreferencesKey("adBlock")
        val AD_WHITE = stringPreferencesKey("adWhite")
        val WEBVIEW_REDIRECT = booleanPreferencesKey("webviewRedirect")
        val WEBVIEW_POPUP = booleanPreferencesKey("webviewPopup")
        val CF_AUTO_SOLVE = booleanPreferencesKey("cfAutoSolve")
        val EXT_VERIFY_WEBVIEW = booleanPreferencesKey("extVerifyWebview")
        val WEBVIEW_REDIRECT_ALLOW = stringPreferencesKey("webviewRedirectAllow")
        val WEBVIEW_POPUP_ALLOW = stringPreferencesKey("webviewPopupAllow")
        val WEBVIEW_POPUP_BLOCK = stringPreferencesKey("webviewPopupBlock")
        val WEBVIEW_DEFAULT_UA = booleanPreferencesKey("webviewDefaultUa")
        val WEBVIEW_CUSTOM_UA = stringPreferencesKey("webviewCustomUa")
        val LANGUAGE = stringPreferencesKey("appLanguage")
        val HOME_PROVIDER = stringPreferencesKey("homeProvider")
        val HOME_PROVIDERS = stringPreferencesKey("homeProviders")
        val TRANSLATE_PROVIDERS = stringPreferencesKey("translateProviders")
        val TRANSLATE_CACHE = stringPreferencesKey("translateCache")
        val SEEDED_REPOS = booleanPreferencesKey("seededRepos")
        /** True once the bundled default Stremio addon (SubDL's subtitle addon —
         *  see [com.hikari.app.providers.StremioAddon.DEFAULT_ADDON_URL]) has
         *  been installed. One-way, for the same reason as [SEEDED_REPOS]: a
         *  user who removes it keeps it removed. */
        val STREMIO_SEEDED = booleanPreferencesKey("stremioAddonSeeded")
        /** True once the providers earlier builds pre-installed (the Nuvio
         *  "starter" scrapers) have been removed from an install that already
         *  seeded them. See [com.hikari.app.nuvio.NuvioPluginManager]. */
        val NUVIO_SEED_CLEANED = booleanPreferencesKey("nuvioSeedCleaned")
        /** True once the bundled YTS extension earlier builds registered on first
         *  run has been removed from this install. See
         *  [com.hikari.app.HikariApp] — the file that used to register it. */
        val YTS_CLEANED = booleanPreferencesKey("ytsCleaned")
        /** True once the 0.11.69 Kotatsu auto-seed (1200+ rows) has been pruned
         *  from this install. One-way, like [YTS_CLEANED]: sources installed
         *  afterwards from the Kotatsu repos folder are never swept. */
        val KOTATSU_PRUNED = booleanPreferencesKey("kotatsuPruned")
        val DOWNLOAD_CONCURRENCY = intPreferencesKey("downloadConcurrency")
        val SLOW_CONNECTION = booleanPreferencesKey("slowConnection")

        /** Resolver chosen in Settings → Network and Internet → DNS mode
         *  ([com.hikari.app.net.DnsProviders] keys), and the address a Custom
         *  choice points at. */
        val DNS_PROVIDER = stringPreferencesKey("dnsProvider")
        val CUSTOM_DNS = stringPreferencesKey("customDns")
        val PLAY_WAIT_SERVERS = booleanPreferencesKey("playWaitServers")
        val PLAY_MIN_SERVERS = intPreferencesKey("playMinServers")
        val ASK_SERVER = booleanPreferencesKey("askServerOnPlay")
        val FAILOVER_ASK = booleanPreferencesKey("failoverAskOnFailure")
        val SHOW_LOADING_BANNER = booleanPreferencesKey("showLoadingBanner")
        val SLOW_TIP_ENABLED = booleanPreferencesKey("slowTipEnabled")
        val SLOW_TIP_DONT_ASK = booleanPreferencesKey("slowTipDontAsk")
        val SLOW_TIP_LAST_DISMISS = longPreferencesKey("slowTipLastDismiss")
        /** Last silent CloudStream plugin auto-update pass, epoch millis (see Cs3AutoUpdate). */
        val CS3_AUTO_UPDATE_AT = longPreferencesKey("cs3AutoUpdateAt")
        /** Last silent Nuvio scraper auto-update pass, epoch millis (see NuvioAutoUpdate). */
        val NUVIO_AUTO_UPDATE_AT = longPreferencesKey("nuvioAutoUpdateAt")
        val TELEGRAM_DONT_SHOW = booleanPreferencesKey("telegramDontShow")
        val COMMUNITY_SEEN_VERSION = stringPreferencesKey("communitySeenVersion")
        /** The app version "Don't show this again" was ticked on — the tick
         *  holds for that version only (see [AppStore.communityDontShowVersion]). */
        val COMMUNITY_DONT_SHOW_VERSION = stringPreferencesKey("communityDontShowVersion")
        /** `list` / `tile` / `poster` — how a Telegram chat's videos are drawn
         *  (see AppStore.telegramView and TgView in the Telegram screen). */
        val TELEGRAM_VIEW = stringPreferencesKey("telegramViewStyle")
        val HIDDEN_TABS = stringPreferencesKey("hiddenTabs")
        val SEARCH_HISTORY = stringPreferencesKey("searchHistory")
        val IPTV_TAB = booleanPreferencesKey("showIptvTab")
        val MANGA_TAB = booleanPreferencesKey("showMangaTab")
        val STATS_TAB = booleanPreferencesKey("showStatsTab")
        val TELEGRAM_TAB = booleanPreferencesKey("showTelegramTab")
        /** The Telegram channels the Telegram tab browses, as JSON. */
        val TELEGRAM_CHANNELS = stringPreferencesKey("telegramChannels")
        val TELEGRAM_LINKS = stringPreferencesKey("telegramLinks")

        /** The user's own Telegram API credentials (see telegramApiIdFlow). */
        val TELEGRAM_API_ID = stringPreferencesKey("telegramApiId")
        val TELEGRAM_API_HASH = stringPreferencesKey("telegramApiHash")
        /** App lock — the switch itself (Settings → Privacy & Browsing). */
        val APP_LOCK = booleanPreferencesKey("appLock")
        /** The lock's secret as `algo:salt:hash` (PBKDF2); blank = never set. */
        val APP_LOCK_SECRET = stringPreferencesKey("appLockSecret")
        val APP_LOCK_LEN = intPreferencesKey("appLockLength")
        /** Unlock with the device's fingerprint/face as well as the password. */
        val APP_LOCK_BIO = booleanPreferencesKey("appLockBiometric")
        val APP_LOCK_SCREEN_OFF = booleanPreferencesKey("appLockScreenOff")
        val APP_LOCK_DELAY_MIN = intPreferencesKey("appLockDelayMin")
        val APP_LOCK_LEAVE = booleanPreferencesKey("appLockLeave")
        val APP_LOCK_SESSION_OPEN = booleanPreferencesKey("appLockSessionOpen")
        val APP_LOCK_SESSION_AT = longPreferencesKey("appLockSessionAt")
        /** My Stuff lock — the switch itself (Settings → Privacy & Browsing).
         *  A second, independent password guarding only the My Stuff tab
         *  (library, history, downloads), so the app can stay open while that
         *  section stays shut. */
        val MYSTUFF_LOCK = booleanPreferencesKey("myStuffLock")
        /** The My Stuff lock's secret as `algo:salt:hash` (PBKDF2); blank =
         *  never set. A DIFFERENT secret from the app lock's, and — like it —
         *  device-local (see [DeviceLocal]): it must never travel in a backup
         *  file or a pairing payload. */
        val MYSTUFF_LOCK_SECRET = stringPreferencesKey("myStuffLockSecret")
        val TRACKERS = stringPreferencesKey("trackers")
        /** The app id/secret the user registered with each service (JSON). */
        val TRACKER_CLIENTS = stringPreferencesKey("trackerClients")
        /** Services whose stored app id was typed by the user and must keep
         *  shadowing the shipped one (comma-joined [TrackerKind] keys). */
        val TRACKER_CLIENT_OVERRIDES = stringPreferencesKey("trackerClientOverrides")
        /** The sign-in waiting for its redirect — see [trackerPending]. */
        val TRACKER_PENDING = stringPreferencesKey("trackerPending")
        /** Whether a finished episode is reported to the trackers at all. */
        val TRACKER_SYNC = booleanPreferencesKey("trackerSync")
        /** Title → service id, once a search has resolved it (JSON). */
        val TRACKER_MATCH = stringPreferencesKey("trackerMatches")
        /** What has already been pushed, so nothing is uploaded twice (JSON). */
        val TRACKER_DONE = stringPreferencesKey("trackerDone")
        val TMDB_API_KEY = stringPreferencesKey("tmdbApiKey")
        val MDBLIST_API_KEY = stringPreferencesKey("mdblistApiKey")
        val PROVIDER_CATALOGS = booleanPreferencesKey("providerCatalogsEnabled")
        /** The last thing the trackers said, as a sentence, plus when. */
        val TRACKER_LAST = stringPreferencesKey("trackerLast")
        val TRACKER_LAST_AT = stringPreferencesKey("trackerLastAt")
        /** The per-day/per-title totals behind the Stats page — see
         *  [com.hikari.app.data.WatchStats]. */
        val WATCH_STATS = stringPreferencesKey("watchStats")
        /** Brightness/volume swipes on the player's video surface (ON by
         *  default; Settings → Player → Player controls). */
        val PLAYER_SWIPES = booleanPreferencesKey("playerSwipes")
        /** Horizontal slide-to-seek on the player's video surface (ON by
         *  default; Settings → Player → Player controls → Gestures). Off means
         *  a sideways drag does nothing — the surface only plays — while the
         *  up/down brightness & volume swipes keep their own switch. */
        val PLAYER_SLIDE_SEEK = booleanPreferencesKey("playerSlideSeek")
        /** Two-finger pinch-to-zoom on the player's video surface (OFF by
         *  default; Settings → Player → Player controls → Gestures). On means
         *  a pinch scales the picture 1x–4x about its center; the zoom resets
         *  whenever the server changes. */
        val PLAYER_PINCH_ZOOM = booleanPreferencesKey("playerPinchZoom")

        val PLAYER_DECODER_MODE = stringPreferencesKey("playerDecoderMode")
        /** Speed used while the viewer keeps a finger down on the video. */
        val PLAYER_HOLD_SPEED = intPreferencesKey("playerHoldSpeed")
        /** Show the small speed badge while press-and-hold fast-play is active. */
        val PLAYER_SHOW_HOLD_SPEED = booleanPreferencesKey("playerShowHoldSpeed")
        /** Show player pills as icons without their text labels. */
        val PLAYER_ICON_ONLY = booleanPreferencesKey("playerIconOnly")
        /** The "Play in external player" row in the player's options menu
         *  (ON by default; Settings → Player hides it). */
        val PLAYER_EXTERNAL_BUTTON = booleanPreferencesKey("playerExternalButton")
        val PLAYER_DUAL_AUDIO = booleanPreferencesKey("playerDualAudio")
        /** Autoplay the next episode when one ends inside the player (ON by
         *  default, like the reference clients; the player's own pill toggles
         *  it). */
        val PLAYER_AUTOPLAY = booleanPreferencesKey("playerAutoplayNext")
        /** Size of player icons when the text labels are hidden, in dp. */
        val PLAYER_ICON_SIZE = intPreferencesKey("playerIconSize")
        val PLAYER_TOP_ICON_SIZE = intPreferencesKey("playerTopIconSize")
        /** Use the large left-side TV menu for player panels. */
        val PLAYER_TV_PANELS = booleanPreferencesKey("playerTvPanels")
        /** Default video quality in pixels of height (0 = Auto / adaptive).
         *  Settings -> Player -> Default quality & audio. */
        val PLAYER_DEFAULT_QUALITY = intPreferencesKey("playerDefaultQuality")
        /** Default audio language (2-letter code, blank = the stream's own default)
         *  and the language to try when the first one is not found. */
        val PLAYER_DEFAULT_AUDIO = stringPreferencesKey("playerDefaultAudio")
        val PLAYER_DEFAULT_AUDIO2 = stringPreferencesKey("playerDefaultAudio2")
        /** Size of TV episode rows/cards as a percentage of the default size. */
        val TV_EPISODE_SIZE = intPreferencesKey("tvEpisodeSize")
        /** TV text size as a percentage of the shipped sizes (50–150, 100 is
         *  the shipped small/medium look; Settings → TV & Remote). */
        val TV_TEXT_SCALE = intPreferencesKey("tvTextScale")
        /** Default episode layout: -1 = Auto (TV grid, phone list), 0 = list,
         *  1 = poster grid, 2 = cinematic big cards (Settings → App Layout →
         *  Episode UI). The detail header button cycles the same three and
         *  writes back here, so the choice sticks across titles. */
        val EPISODE_VIEW_MODE = intPreferencesKey("episodeViewMode")
        /** Show the episode description under the episode title (ON by
         *  default; Settings → TV). Off shows titles only, on phone and TV. */
        val EPISODE_DESCRIPTION = booleanPreferencesKey("episodeDescription")
        /** CloudStream-style frosted-glass episode cards (OFF by default;
         *  Settings → App Layout → Episode UI). Applies to list, grid and big
         *  cards alike. */
        val EPISODE_GLASS = booleanPreferencesKey("episodeGlass")
        val VOLUME_BOOST = booleanPreferencesKey("volumeBoost")
        /** Subtitles selected automatically when a video opens (ON by default;
         *  Settings → Player → Subtitles). Off means every video starts with
         *  captions hidden — the user still turns them on per video from the
         *  player's own Subtitles menu, which keeps working exactly as now. */
        val SUBTITLES_DEFAULT_ON = booleanPreferencesKey("subtitlesDefaultOn")
        /** Reading mode of the manga reader ([com.hikari.app.manga.MangaReadMode]:
         *  paged left-to-right, paged right-to-left, or vertical webtoon). */
        val MANGA_READ_MODE = stringPreferencesKey("mangaReadMode")
        /** How a page is fitted in the reader ([com.hikari.app.manga.MangaFit]):
         *  width / height / whole page. */
        val MANGA_FIT = stringPreferencesKey("mangaFit")
        /** The reader's backdrop: "black", "grey" or "white". */
        val MANGA_READER_BG = stringPreferencesKey("mangaReaderBg")
        /** Keep the screen on while reading, and print "12 / 40" over the page. */
        val MANGA_KEEP_AWAKE = booleanPreferencesKey("mangaKeepAwake")
        val MANGA_SHOW_PAGE_NUMBER = booleanPreferencesKey("mangaShowPageNumber")
        val MANGA_ENHANCE = booleanPreferencesKey("mangaEnhance")
    val MANGA_READER_SETTINGS = stringPreferencesKey("mangaReaderSettings")
    val MANGA_SERIES_MODES = stringPreferencesKey("mangaSeriesModes")
    val MANGA_TRANSLATE_SETTINGS = stringPreferencesKey("mangaTranslateSettings")
    val MANGA_TRANSLATE_SERIES = stringPreferencesKey("mangaTranslateSeries")
    val NOVEL_TRANSLATE_SETTINGS = stringPreferencesKey("novelTranslateSettings")
    val NOVEL_TRANSLATE_SERIES = stringPreferencesKey("novelTranslateSeries")
    /** Engine ids the reader pinned to the top of the Manga tab's Browse list. */
    val PINNED_MANGA_ENGINES = stringSetPreferencesKey("pinnedMangaEngines")
    val PINNED_PROVIDERS = stringPreferencesKey("pinnedProviders")
    val PROVIDER_FOLDERS = stringPreferencesKey("providerFolders")
    /**
     * Per-provider disabled Home catalogs: provider id → JSON
     * `{"id": ["catalog", …]}` (the feed mixer's switches).
     */
    val DISABLED_CATALOGS = stringPreferencesKey("disabledCatalogs")
    /**
     * Per-provider custom catalog order: provider id → JSON
     * `{"id": ["catalog", …]}` in the user's order (mixer reorder + reset).
     */
    val CATALOG_ORDER = stringPreferencesKey("catalogOrder")
        val NSFW_ENABLED = booleanPreferencesKey("nsfwEnabled")
        val MYSTUFF_LIBRARY = booleanPreferencesKey("mystuffLibrary")
        val MYSTUFF_HISTORY = booleanPreferencesKey("mystuffHistory")
        val MYSTUFF_DOWNLOADS = booleanPreferencesKey("mystuffDownloads")
        /** Shape of the tiles on the IPTV tab's playlist/group grids (see
         *  [TileShapes]: poster / square / wide). */
        val IPTV_SHAPE = stringPreferencesKey("iptvTileShape")
        /** Per-section tile shapes for the IPTV tab's Tor and Network stream
         *  pages (the playlist page keeps IPTV_SHAPE above): each of the three
         *  sections remembers its own look. */
        val IPTV_TOR_SHAPE = stringPreferencesKey("iptvTorTileShape")
        val IPTV_STREAM_SHAPE = stringPreferencesKey("iptvStreamTileShape")
        val APP_ICON = stringPreferencesKey("appIcon")
        /** Language TMDB titles are shown in. "" = follow the app language,
         *  "none" = leave TMDB on English, otherwise a TMDB code ("es-ES"). */
        val TMDB_LANGUAGE = stringPreferencesKey("tmdbLanguage")
        val TMDB_ENABLED = booleanPreferencesKey("tmdbEnabled")
        val TMDB_MODULE_TRAILERS = booleanPreferencesKey("tmdbModuleTrailers")
        val TMDB_MODULE_ARTWORK = booleanPreferencesKey("tmdbModuleArtwork")
        val TMDB_MODULE_BASIC = booleanPreferencesKey("tmdbModuleBasic")
        val TMDB_MODULE_DETAILS = booleanPreferencesKey("tmdbModuleDetails")
        val TMDB_MODULE_CREDITS = booleanPreferencesKey("tmdbModuleCredits")
        val TMDB_MODULE_PRODUCTIONS = booleanPreferencesKey("tmdbModuleProductions")
        val TMDB_MODULE_NETWORKS = booleanPreferencesKey("tmdbModuleNetworks")
        val TMDB_MODULE_EPISODES = booleanPreferencesKey("tmdbModuleEpisodes")
        val TMDB_MODULE_SEASON_POSTERS = booleanPreferencesKey("tmdbModuleSeasonPosters")
        val TMDB_MODULE_MORE_LIKE = booleanPreferencesKey("tmdbModuleMoreLike")
        val TMDB_MODULE_COLLECTIONS = booleanPreferencesKey("tmdbModuleCollections")
        val MDB_ENABLED = booleanPreferencesKey("mdbEnabled")
        val MDB_IMDB = booleanPreferencesKey("mdbImdb")
        val MDB_TMDB = booleanPreferencesKey("mdbTmdb")
        val MDB_TOMATOES = booleanPreferencesKey("mdbTomatoes")
        val MDB_METACRITIC = booleanPreferencesKey("mdbMetacritic")
        val MDB_TRAKT = booleanPreferencesKey("mdbTrakt")
        val MDB_LETTERBOXD = booleanPreferencesKey("mdbLetterboxd")
        val MDB_AUDIENCE = booleanPreferencesKey("mdbAudience")
        val MDB_MAL = booleanPreferencesKey("mdbMal")
        val TRACKER_LIBRARY_SOURCE = stringPreferencesKey("trackerLibrarySource")
        val ANIME_METADATA_ENABLED = booleanPreferencesKey("animeMetadataEnabled")
        val ANIME_METADATA_SOURCE = stringPreferencesKey("animeMetadataSource")
        /** Anime metadata chain: each source fetches on its own, no login.
         *  AniList is keyless GraphQL, TMDB uses the bundled keys, Simkl needs
         *  an app ID (tracker login's, or the free one pasted below). Fixed
         *  priority Simkl -> AniList -> TMDB: first hit wins per field. */
        val ANIME_SIMKL = booleanPreferencesKey("animeMetaSimkl")
        val ANIME_ANILIST = booleanPreferencesKey("animeMetaAnilist")
        val ANIME_TMDB = booleanPreferencesKey("animeMetaTmdb")
        /** Optional Simkl app ID (free at simkl.com/developer, no login) so
         *  Simkl metadata works without connecting the Simkl tracker. */
        val ANIME_SIMKL_APP_ID = stringPreferencesKey("animeMetaSimklAppId")
        /** Manga metadata: keyless AniList manga records for every title
         *  (blurbs, genres, covers). On unless the user switches it off. */
        val MANGA_ANILIST = booleanPreferencesKey("mangaMetaAnilist")
        /** Adult manhwa metadata from PornHwaDB, with the user's own key
         *  below. Off until a key is saved. */
        val ADULT_MANHWA = booleanPreferencesKey("adultManhwaMeta")
        /** The user's own PornHwaDB API key (free at pornhwadb.com — Settings
         *  → Integrations → Metadata sources says where; never bundled). */
        val PORNHWA_API_KEY = stringPreferencesKey("pornhwaApiKey")
        /** Movies & series metadata from TMDB (no login, bundled keys). */
        val VIDEO_TMDB = booleanPreferencesKey("videoMetaTmdb")
        /** Provider ids shown with extension-own metadata only: no TMDB /
         *  anime blank-filling, no episode renaming, no TMDB shelves. */
        val NATIVE_PROVIDERS = stringSetPreferencesKey("nativeMetadataProviders")
        val TRACKER_PROGRESS_SOURCE = stringPreferencesKey("trackerProgressSource")
        val TRACKER_RECOMMENDATIONS_SOURCE = stringPreferencesKey("trackerRecommendationsSource")
        val CS3_RECOMMENDATIONS_SOURCE = stringPreferencesKey("cs3RecommendationsSource")
        val TRACKER_CONTINUE_DAYS = intPreferencesKey("trackerContinueDays")
        val TRACKER_COMMENTS = booleanPreferencesKey("trackerComments")
        /** Key of the app-wide font (see [com.hikari.app.ui.AppFonts]). */
        val APP_FONT = stringPreferencesKey("appFont")
        /** File name (inside filesDir/fonts) of a font the user imported. */
        val APP_FONT_FILE = stringPreferencesKey("appFontFile")
        /** The imported font's own display name, for Settings. */
        val APP_FONT_LABEL = stringPreferencesKey("appFontLabel")
        /** The user's Library categories ([LibraryCategory] JSON array). */
        val LIBRARY_CATEGORIES = stringPreferencesKey("libraryCategories")
        /** uniqueId → category ids ([favoriteCategories]). */
        val FAVORITE_CATEGORIES = stringPreferencesKey("favoriteCategories")
        /** Poster & icon styling (see [com.hikari.app.ui.PosterStyle]). */
        val POSTER_BLUR = intPreferencesKey("posterBlur")
        val POSTER_CORNER = intPreferencesKey("posterCorner")
        val POSTER_SHOW_TITLES = booleanPreferencesKey("posterShowTitles")
        val POSTER_SHOW_RATINGS = booleanPreferencesKey("posterShowRatings")
        /** The movie/series tag in the poster's top-left corner. */
        val POSTER_SHOW_TYPE = booleanPreferencesKey("posterShowType")
        /** The quality tag above it ([com.hikari.app.data.TitleQuality]). */
        val POSTER_SHOW_QUALITY = booleanPreferencesKey("posterShowQuality")
        /** The Dub/Sub + latest-episode pills in the poster's top-center
         *  (CloudStream-style; shown once the title's episodes are known). */
        val POSTER_SHOW_EP_BADGES = booleanPreferencesKey("posterShowEpBadges")
        val POSTER_GLASS = booleanPreferencesKey("posterGlass")
        /** The animated/visual treatment drawn over every poster card — see
         *  [com.hikari.app.ui.PosterEffects]. A SET, so a card can wear several
         *  treatments at once. The old single-key preference ([POSTER_EFFECT])
         *  is still read as the starting set, so nobody's choice is lost. */
        val POSTER_EFFECTS = stringSetPreferencesKey("posterEffects")
        /** A single treatment stored by a build before multi-select existed. */
        val POSTER_EFFECT = stringPreferencesKey("posterEffect")
        /** Shape of Home's featured banner — see
         *  [com.hikari.app.ui.components.HeroStyles]. */
        val HERO_STYLE = stringPreferencesKey("heroStyle")
        /** Extra lines the featured banner may draw over its artwork. */
        val HERO_OVERVIEW = booleanPreferencesKey("heroOverview")
        val HERO_RATING = booleanPreferencesKey("heroRating")
        val HERO_META = booleanPreferencesKey("heroMeta")
        /** How big Home's featured banner is drawn, as a percentage of its
         *  default size (see [DEFAULT_HERO_SCALE]) — one slider for the whole
         *  header, smaller or bigger. */
        val HERO_SCALE = intPreferencesKey("heroScale")
        /** How the detail page's header art is laid out — see
         *  [com.hikari.app.ui.screens.DetailHeroStyles]. */
        val DETAIL_HERO_STYLE = stringPreferencesKey("detailHeroStyle")
        /** How big the title LOGO is drawn on the detail page's header, as a
         *  percentage of its default size (see
         *  [DEFAULT_DETAIL_LOGO_SIZE]) — the wordmark art TMDB provides. */
        val DETAIL_LOGO_SIZE = intPreferencesKey("detailLogoSize")
        /** Which player control shell the player wears — see
         *  [com.hikari.app.player.PlayerSkins]. */
        val PLAYER_SKIN = stringPreferencesKey("playerSkin")
        /** Look of the "finding your server" card — see
         *  [com.hikari.app.ui.LoadingStyles]. */
        val LOADING_STYLE = stringPreferencesKey("loadingStyle")
        val LOADING_EFFECTS = stringSetPreferencesKey("loadingEffects")
        /** A single treatment stored by a build before multi-select existed. */
        val LOADING_EFFECT = stringPreferencesKey("loadingEffect")
        /** Draw the title as its TMDB wordmark on the loading cover too, the way
         *  the detail page's header does — instead of the plain text title. */
        val LOADING_LOGO = booleanPreferencesKey("loadingLogo")
        /** How big that wordmark is drawn on the loading cover, as a percentage
         *  of its default size (see [DEFAULT_LOADING_LOGO_SIZE]). Kept apart
         *  from [DETAIL_LOGO_SIZE] so the two screens are sized independently. */
        val LOADING_LOGO_SIZE = intPreferencesKey("loadingLogoSize")
        /** The colour a poster card's "Aura ring" is drawn in — see
         *  [com.hikari.app.ui.AuraColors]. "theme" (the default) follows the app
         *  accent, which is what the ring always drew. */
        val POSTER_AURA_COLOR = stringPreferencesKey("posterAuraColor")
        /** Which shape catalogue cards take — see
         *  [com.hikari.app.ui.PosterTypes]. */
        val POSTER_TYPE = stringPreferencesKey("posterType")
    /** Where a poster's edge light stands, as fractions of the card — see
     *  [com.hikari.app.ui.PosterEffects.LIT]. */
    val POSTER_GLOW_X = floatPreferencesKey("posterGlowX")
    val POSTER_GLOW_Y = floatPreferencesKey("posterGlowY")
    /** How hard that light burns, 0-100. */
    val POSTER_GLOW_STRENGTH = intPreferencesKey("posterGlowStrength")
        /** The same choice for the loading screen's own aura ring. Kept apart
         *  from the poster's so the two screens can differ. */
        val LOADING_AURA_COLOR = stringPreferencesKey("loadingAuraColor")
        val SEARCH_ALL_EXTENSIONS = booleanPreferencesKey("searchAllExtensions")
        /** "Search every Nuvio provider" (Settings → Playback & Servers → Server
         *  search): a title opened FROM a nuvio provider also asks every other
         *  installed nuvio provider, whatever [SEARCH_ALL_EXTENSIONS] says. On by
         *  default — see [nuvioSearchAllFlow]. */
        val NUVIO_SEARCH_ALL = booleanPreferencesKey("nuvioSearchAllProviders")
        val STREMIO_SEARCH_ALL = booleanPreferencesKey("stremioSearchAllAddons")
        val SEARCH_FAMILY_TYPES = stringSetPreferencesKey("searchFamilyTypes")
        val SEARCH_NUVIO_IDS = stringSetPreferencesKey("searchNuvioIds")
        val SEARCH_STREMIO_IDS = stringSetPreferencesKey("searchStremioIds")
        val SEARCH_FAMILY_IDS = stringSetPreferencesKey("searchFamilyIds")
        val PLAY_SCOPE_ASK = booleanPreferencesKey("playScopeAsk")
        val IPTV_GROUP_MODE = stringPreferencesKey("iptvGroupMode")
         val TRACKER_SERVER_SEARCH_ALL = booleanPreferencesKey("trackerServerSearchAll")
         val TRACKER_NUVIO_SEARCH_ALL = booleanPreferencesKey("trackerNuvioSearchAll")
         val TRACKER_STREMIO_SEARCH_ALL = booleanPreferencesKey("trackerStremioSearchAll")
         val TRACKER_FAMILY_TYPES = stringSetPreferencesKey("trackerFamilyTypes")
         val TRACKER_PROVIDER_IDS = stringSetPreferencesKey("trackerProviderIds")
        val SEARCH_EXCEPTION_ON = booleanPreferencesKey("searchExceptionOn")
        val SEARCH_EXCEPTION_IDS = stringSetPreferencesKey("searchExceptionIds")
        /** ENTIRE ENGINES marked as exceptions, by [ProviderType] name
         *  ("CS3", "HIKARI", …). See [searchExceptionTypesFlow]. */
        val SEARCH_EXCEPTION_TYPES = stringSetPreferencesKey("searchExceptionTypes")
       /** Extensions left OUT of a marked engine ("all of CloudStream, except
        *  these"). See [searchExceptionExcludesFlow]. */
       val SEARCH_EXCEPTION_EXCLUDES = stringSetPreferencesKey("searchExceptionExcludes")
        /** Per-extension server-search time, in seconds (Settings → Playback &
         *  Servers → Server search time). 0 or absent = that extension keeps
         *  the built-in budget; 20–100 overrides it. See [serverTimeoutAllSecs]
         *  for the "same time for every extension" master switch. */
        val SERVER_TIMEOUT_ALL = intPreferencesKey("serverTimeoutAllSecs")
        /** "horizontal" (backdrop rows, like now) | "vertical" (compact
         *  cards + portrait art). See [com.hikari.app.player.ServerLayouts]. */
        val SERVER_LAYOUT = stringPreferencesKey("serverLayout")
        /** "classic" (the app's own server page, default) | "streams" (hero
         *  banner + extension chips). See [com.hikari.app.player.ServerScreenStyles]. */
        val SERVER_SCREEN_STYLE = stringPreferencesKey("serverScreenStyle")
        /** providerId → seconds (20–100), as JSON. Only the extensions the user
         *  touched are in here; everything else reads the built-in budget. */
        val SERVER_TIMEOUTS = stringPreferencesKey("serverTimeouts")
        val PROFILE_LOCKS = stringPreferencesKey("profileLocks")
        /** Bottom navigation bar layout — see [com.hikari.app.ui.navigation.NavStyles]:
         *  "classic" | "floating" | "animated" (an old stored "borderless" is
         *  upgraded to "animated" when read). */
        val NAV_STYLE = stringPreferencesKey("navBarStyle")
        /** Draw the icon labels ("Home", "Library", …) under the taskbar's
         *  buttons. On by default; off leaves icon-only buttons. */
        val TAB_LABELS = booleanPreferencesKey("tabLabels")
        /** Draw the rating strip on the detail page (IMDb, RT, …). On by
         *  default: a title's score is part of what the page is for. */
        val SHOW_DETAIL_RATING = booleanPreferencesKey("showDetailRating")
        /** "Turn off full screen app mode": keep the system status bar and the
         *  three-button navigation bar visible everywhere instead of hiding
         *  them behind an immersive, swipe-to-reveal fullscreen. Off = the app
         *  is immersive (the way it ships); on = normal windowed layout. */
        val FULLSCREEN_OFF = booleanPreferencesKey("fullscreenOff")
        /** "auto" (follow the device) | "tv" (always the television layout) |
         *  "phone" (never). Some boxes report themselves wrongly, and being
         *  stuck with an interface the remote cannot work is not acceptable. */
        val TV_MODE = stringPreferencesKey("tvMode")
        /** Screen-edge padding in dp on a television ("overscan"). Televisions
         *  crop a few percent of the picture off, so content drawn hard against
         *  the edge is not visible on many sets. */
        val TV_OVERSCAN = intPreferencesKey("tvOverscan")
        /** Lighter visuals on a television: drops the poster treatments and the
         *  backdrop effects that a cheap TV stick's GPU cannot afford while it
         *  is also decoding video. */
        val TV_PERF = booleanPreferencesKey("tvPerf")
        val TV_PERF_CHOSEN = booleanPreferencesKey("tvPerfChosen")
        /** Whether the first-run television defaults have been applied to this
         *  install already (they must be applied once, not on every launch). */
        val TV_SEEDED = booleanPreferencesKey("tvSeeded")
        /** One-time migration for the new player UI defaults. User choices are preserved. */
        val PLAYER_UI_DEFAULTS_SEEDED = booleanPreferencesKey("playerUiDefaultsSeeded")
        val TV_POSTER_SCALE = intPreferencesKey("tvPosterScale")
        val TV_DETAIL_BIG = booleanPreferencesKey("tvDetailBig")
        val TV_DETAIL_BIG_CHOSEN = booleanPreferencesKey("tvDetailBigChosen")
        /** TV detail blur-merge art (Settings → TV & Remote): fit image above,
         *  blurred merge below. Off by default (a static full-bleed blur is
         *  one more decode a weak box pays on every open). */
        val TV_BLUR_DETAIL = booleanPreferencesKey("tvBlurDetail")
        /** TV's own episode layout (-1 = Auto = cinema grid; set the first time
         *  the TV header button cycles, so a phone-side List choice can never
         *  drag the TV back to rows). */
        val TV_EPISODE_VIEW_MODE = intPreferencesKey("tvEpisodeViewMode")
        val PERF_MODE = booleanPreferencesKey("perfMode")
    }

    object DeviceLocal {

        val KEYS: Set<String> = setOf(
            K.APP_LOCK.name,
            K.APP_LOCK_SECRET.name,
            K.APP_LOCK_LEN.name,
            K.APP_LOCK_BIO.name,
            K.APP_LOCK_SCREEN_OFF.name,
            K.APP_LOCK_DELAY_MIN.name,
            K.APP_LOCK_LEAVE.name,
            K.APP_LOCK_SESSION_OPEN.name,
            K.APP_LOCK_SESSION_AT.name,
            K.MYSTUFF_LOCK.name,
            K.MYSTUFF_LOCK_SECRET.name,
            // A profile's own password, same rule as the app lock's: the
            // receiving device keeps its own locks, whatever a file says.
            K.PROFILE_LOCKS.name,
            K.TV_MODE.name,
            K.TV_OVERSCAN.name,
            K.TV_PERF.name,
            K.TV_PERF_CHOSEN.name,
            K.PLAYER_ENHANCE.name,
            K.PLAYER_ENHANCE_CHOSEN.name,
            K.PLAYER_ENHANCE_UNSUPPORTED.name,
            K.TV_SEEDED.name,
            K.PLAYER_UI_DEFAULTS_SEEDED.name,
            K.TV_POSTER_SCALE.name,
            K.TV_TEXT_SCALE.name,
            K.TV_DETAIL_BIG.name,
            K.TV_BLUR_DETAIL.name,
            K.TV_EPISODE_VIEW_MODE.name,
            K.UI_SCALE_ENABLED.name,
            K.UI_SCALE_PERCENT.name,
            K.FULLSCREEN_OFF.name,
            K.APP_ICON.name,
            K.PERF_MODE.name,
        )

        /** True when [key] (a stored preference's name) must not leave the
         *  device it was written on, or arrive on another one. */
        fun contains(key: String): Boolean = key in KEYS
    }

    // ---- Settings writes ----
    //
    // Every write a setting makes goes through [write] below rather than
    // `store.edit` directly. Two reasons, both from the "I pick an option and it
    // stays on the old one" report:
    //
    //  - a write can FAIL. DataStore writes the whole preferences file, so a
    //    full disk, a momentarily busy file system or a wedged read leaves the
    //    user's choice unsaved, the live flow keeps emitting the old value, and
    //    the toggle in front of them flips back — silently, because every call
    //    site wrapped it in `runCatching`. Retrying and then SAYING SO is the
    //    difference between a diagnosable bug and a haunted app;
    //  - a failure needs to be visible in the shared log (and, once, on screen),
    //    or the only evidence is a setting that will not stay put.

    /** Attempts made at one settings write before giving up and telling the
     *  user. A failed DataStore write is almost always transient. */
    private val WRITE_TRIES = 3

    /** At most one "couldn't save" toast a minute, so broken storage cannot put
     *  a stream of them on screen. */
    private val WRITE_WARNING_COOLDOWN_MS = 60_000L

    /** How many recent searches the Search tab keeps — see [addSearchHistory].
     *  Deep enough to hold what someone searches across a session, short enough
     *  that the chips stay a convenience rather than a page. */
    private val SEARCH_HISTORY_MAX = 20

    @Volatile
    private var lastWriteWarningAt = 0L

    private suspend fun write(
        what: String,
        transform: suspend (androidx.datastore.preferences.core.MutablePreferences) -> Unit,
    ) {
        var last: Throwable? = null
        for (attempt in 1..WRITE_TRIES) {
            try {
                store.edit(transform)
                if (attempt > 1) Logs.log("Store", "saved $what on attempt $attempt")
                return
            } catch (c: kotlinx.coroutines.CancellationException) {
                // The caller went away (the screen was left mid-save): not a
                // failure, and retrying a cancelled write is meaningless.
                throw c
            } catch (t: Throwable) {
                last = t
                Logs.log(
                    "Store",
                    "✗ could not save $what (${t.javaClass.simpleName}: ${t.message}) — " +
                        "attempt $attempt of $WRITE_TRIES",
                )
                kotlinx.coroutines.delay(250L * attempt)
            }
        }
        Logs.log(
            "Store",
            "✗✗ GAVE UP saving $what — the choice will revert. Last error: " +
                "${last?.javaClass?.simpleName}: ${last?.message}. " +
                "If this repeats, the device is out of space or the app's data is unreadable.",
        )
        warnWriteFailure(what)
    }

    /** Says, once in a while, that a choice could not be saved. Silence is what
     *  made this look like the app ignoring its own settings. */
    private fun warnWriteFailure(what: String) {
        val now = System.currentTimeMillis()
        if (now - lastWriteWarningAt < WRITE_WARNING_COOLDOWN_MS) return
        lastWriteWarningAt = now
        runCatching {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching {
                    android.widget.Toast.makeText(
                        ctx,
                        "Hikari couldn't save that setting ($what). Check the device's free space.",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    // ---- The launcher icon the user picked (see AppIconManager) ----
    /** Key of the launcher-icon variant in use — one of
     *  [com.hikari.app.ui.AppIconVariants]. The choice lives in the manifest as
     *  the enabled `activity-alias`, so this mirror is what lets the app put the
     *  manifest back in sync after a backup restore drops the setting. */
    fun appIconFlow(): Flow<String> =
        store.data.map { it[K.APP_ICON] ?: com.hikari.app.ui.AppIconManager.DEFAULT_KEY }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun appIcon(): String = appIconFlow().first()

    suspend fun setAppIcon(key: String) {
        write("APP_ICON") { it[K.APP_ICON] = key }
    }

    // ---- Title language for TMDB metadata ----
    // The app language already localises Hikari's own interface; this decides
    // whether TMDB's titles and descriptions follow it too. "" = follow the app
    // language, "none" = stay on English, otherwise an explicit TMDB code.

    fun tmdbLanguageFlow(): Flow<String> =
        store.data.map { it[K.TMDB_LANGUAGE] ?: "" }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun tmdbLanguage(): String = tmdbLanguageFlow().first()

    fun tmdbEnabledFlow(): Flow<Boolean> = store.data.map { it[K.TMDB_ENABLED] ?: true }.distinctUntilChanged().flowOn(Dispatchers.Default)
    suspend fun tmdbEnabled(): Boolean = tmdbEnabledFlow().first()
    suspend fun setTmdbEnabled(value: Boolean) { write("TMDB_ENABLED") { it[K.TMDB_ENABLED] = value } }

    fun tmdbModuleFlow(name: String): Flow<Boolean> = store.data.map { prefs ->
        when (name) {
            "trailers" -> prefs[K.TMDB_MODULE_TRAILERS] ?: true
            "artwork" -> prefs[K.TMDB_MODULE_ARTWORK] ?: true
            "basic" -> prefs[K.TMDB_MODULE_BASIC] ?: true
            "details" -> prefs[K.TMDB_MODULE_DETAILS] ?: true
            "credits" -> prefs[K.TMDB_MODULE_CREDITS] ?: true
            "productions" -> prefs[K.TMDB_MODULE_PRODUCTIONS] ?: true
            "networks" -> prefs[K.TMDB_MODULE_NETWORKS] ?: true
            "episodes" -> prefs[K.TMDB_MODULE_EPISODES] ?: true
            "season_posters" -> prefs[K.TMDB_MODULE_SEASON_POSTERS] ?: true
            "more_like" -> prefs[K.TMDB_MODULE_MORE_LIKE] ?: true
            "collections" -> prefs[K.TMDB_MODULE_COLLECTIONS] ?: true
            else -> true
        }
    }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun tmdbModule(name: String): Boolean = tmdbModuleFlow(name).first()
    suspend fun setTmdbModule(name: String, value: Boolean) {
        write("TMDB_MODULE_" + name) { prefs ->
            when (name) {
                "trailers" -> prefs[K.TMDB_MODULE_TRAILERS] = value
                "artwork" -> prefs[K.TMDB_MODULE_ARTWORK] = value
                "basic" -> prefs[K.TMDB_MODULE_BASIC] = value
                "details" -> prefs[K.TMDB_MODULE_DETAILS] = value
                "credits" -> prefs[K.TMDB_MODULE_CREDITS] = value
                "productions" -> prefs[K.TMDB_MODULE_PRODUCTIONS] = value
                "networks" -> prefs[K.TMDB_MODULE_NETWORKS] = value
                "episodes" -> prefs[K.TMDB_MODULE_EPISODES] = value
                "season_posters" -> prefs[K.TMDB_MODULE_SEASON_POSTERS] = value
                "more_like" -> prefs[K.TMDB_MODULE_MORE_LIKE] = value
                "collections" -> prefs[K.TMDB_MODULE_COLLECTIONS] = value
            }
        }
    }

    fun mdbEnabledFlow(): Flow<Boolean> = store.data.map { it[K.MDB_ENABLED] ?: false }.distinctUntilChanged().flowOn(Dispatchers.Default)
    suspend fun mdbEnabled(): Boolean = mdbEnabledFlow().first()
    suspend fun setMdbEnabled(value: Boolean) { write("MDB_ENABLED") { it[K.MDB_ENABLED] = value } }
    fun mdbProviderFlow(name: String): Flow<Boolean> = store.data.map { p ->
        when (name) {
            "imdb" -> p[K.MDB_IMDB] ?: true
            "tmdb" -> p[K.MDB_TMDB] ?: true
            "tomatoes" -> p[K.MDB_TOMATOES] ?: true
            "metacritic" -> p[K.MDB_METACRITIC] ?: true
            "trakt" -> p[K.MDB_TRAKT] ?: true
            "letterboxd" -> p[K.MDB_LETTERBOXD] ?: true
            "audience" -> p[K.MDB_AUDIENCE] ?: true
            "mal" -> p[K.MDB_MAL] ?: true
            else -> true
        }
    }.distinctUntilChanged().flowOn(Dispatchers.Default)
    suspend fun mdbProvider(name: String): Boolean = mdbProviderFlow(name).first()
    suspend fun setMdbProvider(name: String, value: Boolean) {
        write("MDB_PROVIDER_" + name) { p ->
            when (name) {
                "imdb" -> p[K.MDB_IMDB] = value
                "tmdb" -> p[K.MDB_TMDB] = value
                "tomatoes" -> p[K.MDB_TOMATOES] = value
                "metacritic" -> p[K.MDB_METACRITIC] = value
                "trakt" -> p[K.MDB_TRAKT] = value
                "letterboxd" -> p[K.MDB_LETTERBOXD] = value
                "audience" -> p[K.MDB_AUDIENCE] = value
                "mal" -> p[K.MDB_MAL] = value
            }
        }
    }

    fun animeMetadataEnabledFlow(): Flow<Boolean> = store.data.map { it[K.ANIME_METADATA_ENABLED] ?: false }.distinctUntilChanged().flowOn(Dispatchers.Default)
    suspend fun animeMetadataEnabled(): Boolean = animeMetadataEnabledFlow().first()
    suspend fun setAnimeMetadataEnabled(value: Boolean) { write("ANIME_METADATA_ENABLED") { it[K.ANIME_METADATA_ENABLED] = value } }
    fun animeMetadataSourceFlow(): Flow<String> = store.data.map { it[K.ANIME_METADATA_SOURCE] ?: "anilist_simkl" }.distinctUntilChanged().flowOn(Dispatchers.Default)
    suspend fun animeMetadataSource(): String = animeMetadataSourceFlow().first()
    suspend fun setAnimeMetadataSource(value: String) { write("ANIME_METADATA_SOURCE") { it[K.ANIME_METADATA_SOURCE] = value } }
    fun animeSimklFlow(): Flow<Boolean> = store.data.map { it[K.ANIME_SIMKL] ?: true }.distinctUntilChanged().flowOn(Dispatchers.Default)
    suspend fun animeSimkl(): Boolean = animeSimklFlow().first()
    suspend fun setAnimeSimkl(value: Boolean) { write("ANIME_SIMKL") { it[K.ANIME_SIMKL] = value } }
    fun animeAnilistFlow(): Flow<Boolean> = store.data.map { it[K.ANIME_ANILIST] ?: true }.distinctUntilChanged().flowOn(Dispatchers.Default)
    suspend fun animeAnilist(): Boolean = animeAnilistFlow().first()
    suspend fun setAnimeAnilist(value: Boolean) { write("ANIME_ANILIST") { it[K.ANIME_ANILIST] = value } }
    fun animeTmdbFlow(): Flow<Boolean> = store.data.map { it[K.ANIME_TMDB] ?: true }.distinctUntilChanged().flowOn(Dispatchers.Default)
    suspend fun animeTmdb(): Boolean = animeTmdbFlow().first()
    suspend fun setAnimeTmdb(value: Boolean) { write("ANIME_TMDB") { it[K.ANIME_TMDB] = value } }
    fun animeSimklAppIdFlow(): Flow<String> = store.data.map { it[K.ANIME_SIMKL_APP_ID] ?: "" }.distinctUntilChanged().flowOn(Dispatchers.Default)
    suspend fun animeSimklAppId(): String = animeSimklAppIdFlow().first()
    suspend fun setAnimeSimklAppId(value: String) { write("ANIME_SIMKL_APP_ID") { it[K.ANIME_SIMKL_APP_ID] = value } }
    fun mangaAnilistFlow(): Flow<Boolean> = store.data.map { it[K.MANGA_ANILIST] ?: true }.distinctUntilChanged().flowOn(Dispatchers.Default)
    suspend fun mangaAnilist(): Boolean = mangaAnilistFlow().first()
    suspend fun setMangaAnilist(value: Boolean) { write("MANGA_ANILIST") { it[K.MANGA_ANILIST] = value } }
    fun adultManhwaFlow(): Flow<Boolean> = store.data.map { it[K.ADULT_MANHWA] ?: false }.distinctUntilChanged().flowOn(Dispatchers.Default)
    suspend fun adultManhwa(): Boolean = adultManhwaFlow().first()
    suspend fun setAdultManhwa(value: Boolean) { write("ADULT_MANHWA") { it[K.ADULT_MANHWA] = value } }
    fun pornhwaApiKeyFlow(): Flow<String> = store.data.map { it[K.PORNHWA_API_KEY] ?: "" }.distinctUntilChanged().flowOn(Dispatchers.Default)
    suspend fun pornhwaApiKey(): String = pornhwaApiKeyFlow().first()
    suspend fun setPornhwaApiKey(value: String) { write("PORNHWA_API_KEY") { it[K.PORNHWA_API_KEY] = value } }
    fun videoTmdbFlow(): Flow<Boolean> = store.data.map { it[K.VIDEO_TMDB] ?: true }.distinctUntilChanged().flowOn(Dispatchers.Default)
    suspend fun videoTmdb(): Boolean = videoTmdbFlow().first()
    suspend fun setVideoTmdb(value: Boolean) { write("VIDEO_TMDB") { it[K.VIDEO_TMDB] = value } }
    fun nativeProvidersFlow(): Flow<Set<String>> =
        store.data.map { it[K.NATIVE_PROVIDERS] ?: emptySet() }.distinctUntilChanged().flowOn(Dispatchers.Default)
    suspend fun nativeProviders(): Set<String> = nativeProvidersFlow().first()
    suspend fun setNativeProviders(ids: Set<String>) { write("NATIVE_PROVIDERS") { it[K.NATIVE_PROVIDERS] = ids.toSet() } }

    fun trackerLibrarySourceFlow(): Flow<String> = store.data.map { it[K.TRACKER_LIBRARY_SOURCE] ?: "nuvio" }.distinctUntilChanged().flowOn(Dispatchers.Default)
    suspend fun trackerLibrarySource(): String = trackerLibrarySourceFlow().first()
    suspend fun setTrackerLibrarySource(value: String) { write("TRACKER_LIBRARY_SOURCE") { it[K.TRACKER_LIBRARY_SOURCE] = value } }
    fun trackerProgressSourceFlow(): Flow<String> = store.data.map { it[K.TRACKER_PROGRESS_SOURCE] ?: "nuvio" }.distinctUntilChanged().flowOn(Dispatchers.Default)
    suspend fun trackerProgressSource(): String = trackerProgressSourceFlow().first()
    suspend fun setTrackerProgressSource(value: String) { write("TRACKER_PROGRESS_SOURCE") { it[K.TRACKER_PROGRESS_SOURCE] = value } }
    fun trackerRecommendationsSourceFlow(): Flow<String> = store.data.map { it[K.TRACKER_RECOMMENDATIONS_SOURCE] ?: "tmdb" }.distinctUntilChanged().flowOn(Dispatchers.Default)
    suspend fun trackerRecommendationsSource(): String = trackerRecommendationsSourceFlow().first()
    suspend fun setTrackerRecommendationsSource(value: String) { write("TRACKER_RECOMMENDATIONS_SOURCE") { it[K.TRACKER_RECOMMENDATIONS_SOURCE] = value } }

    /** Where the detail page's recommendations come from for a CloudStream (.cs3)
     *  title (Settings -> Customize): "plugin" = the plugin's own list (what the
     *  CloudStream app shows), falling back to TMDB only when the plugin returns
     *  none; "tmdb" = always TMDB, as before. Default: plugin. */
    fun cs3RecommendationsSourceFlow(): Flow<String> =
        store.data.map { if (it[K.CS3_RECOMMENDATIONS_SOURCE] == "tmdb") "tmdb" else "plugin" }
            .distinctUntilChanged().flowOn(Dispatchers.Default)
    suspend fun cs3RecommendationsSource(): String = cs3RecommendationsSourceFlow().first()
    suspend fun setCs3RecommendationsSource(value: String) {
        write("CS3_RECOMMENDATIONS_SOURCE") { it[K.CS3_RECOMMENDATIONS_SOURCE] = if (value == "tmdb") "tmdb" else "plugin" }
    }
    fun trackerContinueDaysFlow(): Flow<Int> = store.data.map { it[K.TRACKER_CONTINUE_DAYS] ?: 60 }.distinctUntilChanged().flowOn(Dispatchers.Default)
    suspend fun trackerContinueDays(): Int = trackerContinueDaysFlow().first()
    suspend fun setTrackerContinueDays(value: Int) { write("TRACKER_CONTINUE_DAYS") { it[K.TRACKER_CONTINUE_DAYS] = value.coerceIn(7, 3650) } }
    fun trackerCommentsFlow(): Flow<Boolean> = store.data.map { it[K.TRACKER_COMMENTS] ?: false }.distinctUntilChanged().flowOn(Dispatchers.Default)
    suspend fun trackerComments(): Boolean = trackerCommentsFlow().first()
    suspend fun setTrackerComments(value: Boolean) { write("TRACKER_COMMENTS") { it[K.TRACKER_COMMENTS] = value } }

    fun tmdbApiKeyFlow(): Flow<String> = store.data.map { it[K.TMDB_API_KEY].orEmpty() }.distinctUntilChanged().flowOn(Dispatchers.Default)
    suspend fun tmdbApiKey(): String = store.data.first()[K.TMDB_API_KEY].orEmpty()
    suspend fun setTmdbApiKey(value: String) { write("TMDB_API_KEY") { it[K.TMDB_API_KEY] = value.trim() } }
    fun mdbListApiKeyFlow(): Flow<String> = store.data.map { it[K.MDBLIST_API_KEY].orEmpty() }.distinctUntilChanged().flowOn(Dispatchers.Default)
    suspend fun mdbListApiKey(): String = store.data.first()[K.MDBLIST_API_KEY].orEmpty()
    suspend fun setMdbListApiKey(value: String) { write("MDBLIST_API_KEY") { it[K.MDBLIST_API_KEY] = value.trim() } }
    fun providerCatalogsEnabledFlow(): Flow<Boolean> = store.data.map { it[K.PROVIDER_CATALOGS] ?: true }.distinctUntilChanged().flowOn(Dispatchers.Default)
    suspend fun providerCatalogsEnabled(): Boolean = store.data.first()[K.PROVIDER_CATALOGS] ?: true
    suspend fun setProviderCatalogsEnabled(value: Boolean) { write("PROVIDER_CATALOGS") { it[K.PROVIDER_CATALOGS] = value } }

    suspend fun setTmdbLanguage(mode: String) {
        write("TMDB_LANGUAGE") { it[K.TMDB_LANGUAGE] = mode.trim() }
    }

    // ---- App-wide font (Settings → Appearance & Theme → App font) ----

    /** Key of the chosen font in [com.hikari.app.ui.AppFonts.CHOICES]. */
    fun appFontFlow(): Flow<String> =
        store.data.map { it[K.APP_FONT] ?: com.hikari.app.ui.AppFonts.DEFAULT }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun appFont(): String = appFontFlow().first()

    suspend fun setAppFont(key: String) {
        write("APP_FONT") { it[K.APP_FONT] = key }
        syncFont()
    }

    /** File name (inside `filesDir/fonts`) of a font the user imported, or "".
     *  Only meaningful while [appFontFlow] is [com.hikari.app.ui.AppFonts.IMPORTED]. */
    fun appFontFileFlow(): Flow<String> =
        store.data.map { it[K.APP_FONT_FILE] ?: "" }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun appFontFile(): String = appFontFileFlow().first()

    /** The imported font's own name (the file's `familyName`), for Settings. */
    fun appFontLabelFlow(): Flow<String> =
        store.data.map { it[K.APP_FONT_LABEL] ?: "" }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun appFontLabel(): String = appFontLabelFlow().first()

    suspend fun setImportedFont(fileName: String, label: String) {
        write("APP_FONT_FILE") {
            it[K.APP_FONT_FILE] = fileName
            it[K.APP_FONT_LABEL] = label
        }
        syncFont()
    }

    /** Mirror the font choice into [com.hikari.app.ui.AppFonts] — the same
     *  treatment the accent and the UI scale get, and for the same reason: the
     *  player and the WebView are View-based and read it while their Activity is
     *  being created, before DataStore has answered. */
    private suspend fun syncFont() {
        runCatching { com.hikari.app.ui.AppFonts.sync(ctx, appFont(), appFontFile()) }
    }

    // ---- Library categories ----

    /** The categories that exist right now (seeded with four on first read). */
    fun libraryCategoriesFlow(): Flow<List<LibraryCategory>> =
        store.data.map { parseCategories(it[K.LIBRARY_CATEGORIES]) }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun libraryCategories(): List<LibraryCategory> = libraryCategoriesFlow().first()

    suspend fun saveLibraryCategories(list: List<LibraryCategory>) {
        write("LIBRARY_CATEGORIES") { it[K.LIBRARY_CATEGORIES] = encodeCategories(list) }
    }

    suspend fun addLibraryCategory(name: String): LibraryCategory {
        val c = LibraryCategory(newId("cat"), name.trim())
        saveLibraryCategories(libraryCategories() + c)
        return c
    }

    /** Renames a category in place — every title filed under it follows. */
    suspend fun renameLibraryCategory(id: String, name: String) {
        saveLibraryCategories(
            libraryCategories().map { if (it.id == id) it.copy(name = name.trim()) else it }
        )
    }

    /** Moves a category one step up (delta = -1) or down (+1), clamping at the ends. */
    suspend fun moveLibraryCategory(id: String, delta: Int) {
        val list = libraryCategories().toMutableList()
        val from = list.indexOfFirst { it.id == id }
        if (from < 0) return
        val to = (from + delta).coerceIn(0, list.size - 1)
        if (to == from) return
        val c = list.removeAt(from)
        list.add(to, c)
        saveLibraryCategories(list)
    }

    /** Deletes a category and unfiles every title that was in it. */
    suspend fun removeLibraryCategory(id: String) {
        saveLibraryCategories(libraryCategories().filter { it.id != id })
        write("FAVORITE_CATEGORIES") { prefs ->
            val cur = parseCategoryMap(prefs[K.FAVORITE_CATEGORIES])
            prefs[K.FAVORITE_CATEGORIES] = encodeCategoryMap(
                cur.mapValues { (_, set) -> set - id }.filterValues { it.isNotEmpty() }
            )
        }
    }

    /** Title uniqueId → the category ids it is filed under. */
    fun favoriteCategoriesFlow(): Flow<Map<String, Set<String>>> =
        store.data.map { parseCategoryMap(it[K.FAVORITE_CATEGORIES]) }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun favoriteCategories(): Map<String, Set<String>> = favoriteCategoriesFlow().first()

    /** Replaces one title's filing. An empty set simply forgets the title. */
    suspend fun setFavoriteCategories(uniqueId: String, categories: Set<String>) {
        if (uniqueId.isBlank()) return
        write("FAVORITE_CATEGORIES") { prefs ->
            val cur = parseCategoryMap(prefs[K.FAVORITE_CATEGORIES]).toMutableMap()
            if (categories.isEmpty()) cur.remove(uniqueId) else cur[uniqueId] = categories
            prefs[K.FAVORITE_CATEGORIES] = encodeCategoryMap(cur)
        }
    }

    /** Adds categories to whatever a title is already filed under (the
     *  "Add to library" prompt must never silently unfile something). */
    suspend fun addFavoriteCategories(uniqueId: String, categories: Set<String>) {
        if (uniqueId.isBlank() || categories.isEmpty()) return
        val cur = favoriteCategories()[uniqueId].orEmpty()
        setFavoriteCategories(uniqueId, cur + categories)
    }

    private companion object ShipDefaults {
        /** dp of halo behind each poster. */
        const val DEFAULT_POSTER_BLUR = 15

        /** dp of corner rounding on each poster. */
        const val DEFAULT_POSTER_CORNER = 28

        /** Where the poster edge light stands by default: top-centre, a little
         *  in from the top edge — the reference client's look, and the point the
         *  picker in Settings starts from. */
        const val DEFAULT_POSTER_GLOW_X = 0.5f
        const val DEFAULT_POSTER_GLOW_Y = 0.14f

        /** How hard it burns by default: bright enough to read as a light on a
         *  dark poster, weak enough not to wash out a bright one. */
        const val DEFAULT_POSTER_GLOW_STRENGTH = 55

        val DEFAULT_POSTER_EFFECT = com.hikari.app.ui.PosterEffects.NONE

        /** The signature card treatmentS — empty, i.e. nothing drawn over the
         *  art (see above; [com.hikari.app.ui.PosterEffects.normalizeSet] maps a
         *  stored NONE to "no layer" the same way). */
        val DEFAULT_POSTER_EFFECTS: Set<String> = emptySet()

        /** Home's featured banner shape ([HeroStyles]). */
        const val DEFAULT_HERO_STYLE = com.hikari.app.ui.components.HeroStyles.SHOWCASE
        /** 100% = the size the featured banner has always been drawn at. */
        const val DEFAULT_HERO_SCALE = 100

    /** The detail page's header art ([DetailHeroStyles]). */
    const val DEFAULT_DETAIL_HERO_STYLE = com.hikari.app.ui.screens.DetailHeroStyles.SIDE
    /** 100% = the size the logo has always been drawn at. */
    const val DEFAULT_DETAIL_LOGO_SIZE = 100

    /** The same for the loading cover's wordmark — its own slider, so the two
     *  screens can be sized apart. See [DEFAULT_DETAIL_LOGO_SIZE] for the scale. */
    const val DEFAULT_LOADING_LOGO_SIZE = 100

        /** The player's default shell. TV Player is the new default for both layouts. */
        const val DEFAULT_PLAYER_SKIN = com.hikari.app.player.PlayerSkins.TV

        /** The look of the "finding your server" card ([LoadingStyles]). Poster
         *  card: the title's own poster on a glass card, so the first thing a
         *  new user sees while a server is found is the film's artwork. */
        const val DEFAULT_LOADING_STYLE = com.hikari.app.ui.LoadingStyles.POSTER

        val DEFAULT_LOADING_EFFECTS: Set<String> = emptySet()

        const val DEFAULT_THEME = HikariThemeMode.DEFAULT_KEY
    }

    // ---- Poster & icon styling ----

    /** Backdrop blur radius in dp behind a poster (0 = the plain art). */
    fun posterBlurFlow(): Flow<Int> =
        store.data.map { (it[K.POSTER_BLUR] ?: DEFAULT_POSTER_BLUR).coerceIn(0, 24) }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun posterBlur(): Int = posterBlurFlow().first()

    suspend fun setPosterBlur(value: Int) {
        write("POSTER_BLUR") { it[K.POSTER_BLUR] = value.coerceIn(0, 24) }
    }

    /** Poster corner rounding in dp. */
    fun posterCornerFlow(): Flow<Int> =
        store.data.map { (it[K.POSTER_CORNER] ?: DEFAULT_POSTER_CORNER).coerceIn(0, 28) }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun posterCorner(): Int = posterCornerFlow().first()

    suspend fun setPosterCorner(value: Int) {
        write("POSTER_CORNER") { it[K.POSTER_CORNER] = value.coerceIn(0, 28) }
    }

    fun posterShowTitlesFlow(): Flow<Boolean> =
        store.data.map { it[K.POSTER_SHOW_TITLES] ?: true }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun posterShowTitles(): Boolean = posterShowTitlesFlow().first()

    suspend fun setPosterShowTitles(show: Boolean) {
        write("POSTER_SHOW_TITLES") { it[K.POSTER_SHOW_TITLES] = show }
    }

    fun posterShowRatingsFlow(): Flow<Boolean> =
        store.data.map { it[K.POSTER_SHOW_RATINGS] ?: true }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun posterShowRatings(): Boolean = posterShowRatingsFlow().first()

    suspend fun setPosterShowRatings(show: Boolean) {
        write("POSTER_SHOW_RATINGS") { it[K.POSTER_SHOW_RATINGS] = show }
    }

    /** The movie/series tag in a poster's top-left corner. On by default. */
    fun posterShowTypeFlow(): Flow<Boolean> =
        store.data.map { it[K.POSTER_SHOW_TYPE] ?: true }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun posterShowType(): Boolean = posterShowTypeFlow().first()

    suspend fun setPosterShowType(show: Boolean) {
        write("POSTER_SHOW_TYPE") { it[K.POSTER_SHOW_TYPE] = show }
    }

    /** The quality tag (4K/1080p/…) above the type tag. OFF by default: it can
     *  only be drawn for titles Hikari has actually seen the quality of, so it
     *  starts absent and is switched on deliberately. */
    fun posterShowQualityFlow(): Flow<Boolean> =
        store.data.map { it[K.POSTER_SHOW_QUALITY] ?: false }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun posterShowQuality(): Boolean = posterShowQualityFlow().first()

    suspend fun setPosterShowQuality(show: Boolean) {
        write("POSTER_SHOW_QUALITY") { it[K.POSTER_SHOW_QUALITY] = show }
    }

    /** The Dub/Sub + latest-episode pills (ON by default, like the reference
     *  clients). They print only for series whose episode list the app has
     *  already seen — a title with no cached episodes shows nothing rather
     *  than a guess. */
    fun posterShowEpBadgesFlow(): Flow<Boolean> =
        store.data.map { it[K.POSTER_SHOW_EP_BADGES] ?: true }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun posterShowEpBadges(): Boolean = posterShowEpBadgesFlow().first()

    suspend fun setPosterShowEpBadges(show: Boolean) {
        write("POSTER_SHOW_EP_BADGES") { it[K.POSTER_SHOW_EP_BADGES] = show }
    }

    /** The glass hairline + soft sheen over every poster. */
    fun posterGlassFlow(): Flow<Boolean> =
        store.data.map { it[K.POSTER_GLASS] ?: true }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun posterGlass(): Boolean = posterGlassFlow().first()

    suspend fun setPosterGlass(on: Boolean) {
        write("POSTER_GLASS") { it[K.POSTER_GLASS] = on }
    }

    /** The visual treatment(s) drawn over every poster card. */
    fun posterEffectsFlow(): Flow<Set<String>> =
        store.data.map { prefs ->
            com.hikari.app.ui.PosterEffects.normalizeSet(
                prefs[K.POSTER_EFFECTS]
                    // A choice made before multi-select: the single stored key
                    // becomes a one-member set, so nobody's setting is lost.
                    ?: com.hikari.app.ui.PosterEffects.parse(prefs[K.POSTER_EFFECT])
                        .ifEmpty { DEFAULT_POSTER_EFFECTS },
            )
        }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun posterEffects(): Set<String> = posterEffectsFlow().first()

    suspend fun setPosterEffects(keys: kotlin.collections.Collection<String>) {
        write("POSTER_EFFECTS") {
            it[K.POSTER_EFFECTS] = com.hikari.app.ui.PosterEffects.normalizeSet(keys)
            it.remove(K.POSTER_EFFECT)
        }
    }

    // ---- Home's featured banner ----

    /** How the featured banner is shaped: the carousel, a full-width spotlight,
     *  a compact strip or the side-by-side showcase. */
    fun heroStyleFlow(): Flow<String> =
        store.data.map {
            com.hikari.app.ui.components.HeroStyles.normalize(
                it[K.HERO_STYLE] ?: DEFAULT_HERO_STYLE,
            )
        }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun heroStyle(): String = heroStyleFlow().first()

    /** The Sources sheet's page layout (Settings → Playback & Servers).
     *  Fresh installs open vertical on phones (cards + portrait art) and
     *  horizontal on televisions — a stored choice always wins. */
    fun serverLayoutFlow(): Flow<String> =
        store.data.map {
            com.hikari.app.player.ServerLayouts.namespace(
                it[K.SERVER_LAYOUT]
                    ?: if (com.hikari.app.tv.TvMode.isTv) com.hikari.app.player.ServerLayouts.HORIZONTAL
                    else com.hikari.app.player.ServerLayouts.VERTICAL
            )
        }
            .distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun serverLayout(): String = serverLayoutFlow().first()

    /** Look of the server list page (Settings -> Customize). Default: classic. */
    fun serverScreenStyleFlow(): Flow<String> =
        store.data.map {
            com.hikari.app.player.ServerScreenStyles.namespace(it[K.SERVER_SCREEN_STYLE])
        }
            .distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun serverScreenStyle(): String = serverScreenStyleFlow().first()

    suspend fun setServerScreenStyle(key: String) {
        write("SERVER_SCREEN_STYLE") { it[K.SERVER_SCREEN_STYLE] = com.hikari.app.player.ServerScreenStyles.namespace(key) }
    }

    suspend fun setServerLayout(key: String) {
        write("SERVER_LAYOUT") { it[K.SERVER_LAYOUT] = com.hikari.app.player.ServerLayouts.namespace(key) }
    }

    suspend fun setHeroStyle(key: String) {
        write("HERO_STYLE") { it[K.HERO_STYLE] = com.hikari.app.ui.components.HeroStyles.normalize(key) }
    }

    fun heroOverviewFlow(): Flow<Boolean> = store.data.map { it[K.HERO_OVERVIEW] ?: true }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun heroOverview(): Boolean = heroOverviewFlow().first()

    suspend fun setHeroOverview(on: Boolean) {
        write("HERO_OVERVIEW") { it[K.HERO_OVERVIEW] = on }
    }

    fun heroRatingFlow(): Flow<Boolean> = store.data.map { it[K.HERO_RATING] ?: true }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun heroRating(): Boolean = heroRatingFlow().first()

    suspend fun setHeroRating(on: Boolean) {
        write("HERO_RATING") { it[K.HERO_RATING] = on }
    }

    fun heroMetaFlow(): Flow<Boolean> = store.data.map { it[K.HERO_META] ?: true }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun heroMeta(): Boolean = heroMetaFlow().first()

    suspend fun setHeroMeta(on: Boolean) {
        write("HERO_META") { it[K.HERO_META] = on }
    }

    /** Home's featured banner size, in percent of its default (see
     *  [DEFAULT_HERO_SCALE]). Clamped, because it scales the card's width and
     *  height together: past the top of the range the card would outgrow the
     *  screen it sits on. */
    fun heroScaleFlow(): Flow<Int> =
        store.data.map { (it[K.HERO_SCALE] ?: DEFAULT_HERO_SCALE).coerceIn(50, 150) }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun heroScale(): Int = heroScaleFlow().first()

    suspend fun setHeroScale(percent: Int) {
        write("HERO_SCALE") { it[K.HERO_SCALE] = percent.coerceIn(50, 150) }
    }

    /** How the detail page's header art is laid out. */
    fun detailHeroStyleFlow(): Flow<String> =
        store.data.map {
            com.hikari.app.ui.screens.DetailHeroStyles.normalize(
                it[K.DETAIL_HERO_STYLE] ?: DEFAULT_DETAIL_HERO_STYLE,
            )
        }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun detailHeroStyle(): String = detailHeroStyleFlow().first()

    suspend fun setDetailHeroStyle(key: String) {
        write("DETAIL_HERO_STYLE") { it[K.DETAIL_HERO_STYLE] = com.hikari.app.ui.screens.DetailHeroStyles.normalize(key) }
    }

    /** The detail page's title-logo size, in percent of its default (see
     *  [DEFAULT_DETAIL_LOGO_SIZE]). Clamped, because it multiplies the width the
     *  logo is drawn at: past the top of the range the art would be wider than
     *  the screen it is fitted into. */
    fun detailLogoSizeFlow(): Flow<Int> =
        store.data.map { (it[K.DETAIL_LOGO_SIZE] ?: DEFAULT_DETAIL_LOGO_SIZE).coerceIn(50, 160) }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun detailLogoSize(): Int = detailLogoSizeFlow().first()

    suspend fun setDetailLogoSize(percent: Int) {
        write("DETAIL_LOGO_SIZE") { it[K.DETAIL_LOGO_SIZE] = percent.coerceIn(50, 160) }
    }

    /** Which player control shell the player wears. */
    fun playerSkinFlow(): Flow<String> =
        store.data.map {
            com.hikari.app.player.PlayerSkins.normalize(
                it[K.PLAYER_SKIN] ?: DEFAULT_PLAYER_SKIN,
            )
        }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun playerSkin(): String = playerSkinFlow().first()

    suspend fun setPlayerSkin(key: String) {
        write("PLAYER_SKIN") { it[K.PLAYER_SKIN] = com.hikari.app.player.PlayerSkins.normalize(key) }
    }

    // ---- The "finding your server" card (Settings → App Layout) ----

    /** Which look the loading card wears — see
     *  [com.hikari.app.ui.LoadingStyles]. One choice, two screens: the detail
     *  page shows the card from the tap, and the player continues with the same
     *  look until the video is on screen. */
    fun loadingStyleFlow(): Flow<String> =
        store.data.map {
            com.hikari.app.ui.LoadingStyles.normalize(it[K.LOADING_STYLE] ?: DEFAULT_LOADING_STYLE)
        }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun loadingStyle(): String = loadingStyleFlow().first()

    suspend fun setLoadingStyle(key: String) {
        write("LOADING_STYLE") { it[K.LOADING_STYLE] = com.hikari.app.ui.LoadingStyles.normalize(key) }
    }

    /** The treatment(s) drawn over the loading card — see
     *  [com.hikari.app.ui.LoadingEffects]. Works with every style, so the
     *  quietest card can wear the same kind of signature detail a poster does,
     *  and several treatments can be on at once. */
    fun loadingEffectsFlow(): Flow<Set<String>> =
        store.data.map { prefs ->
            com.hikari.app.ui.LoadingEffects.normalizeSet(
                prefs[K.LOADING_EFFECTS]
                    ?: com.hikari.app.ui.LoadingEffects.parse(prefs[K.LOADING_EFFECT])
                        .ifEmpty { DEFAULT_LOADING_EFFECTS },
            )
        }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun loadingEffects(): Set<String> = loadingEffectsFlow().first()

    suspend fun setLoadingEffects(keys: kotlin.collections.Collection<String>) {
        write("LOADING_EFFECTS") {
            it[K.LOADING_EFFECTS] = com.hikari.app.ui.LoadingEffects.normalizeSet(keys)
            it.remove(K.LOADING_EFFECT)
        }
    }

    /** The colour a poster card's aura ring is drawn in ([AuraColors.THEME] =
     *  follow the app accent). */
    fun posterAuraColorFlow(): Flow<String> =
        store.data.map {
            com.hikari.app.ui.AuraColors.normalize(it[K.POSTER_AURA_COLOR])
        }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun posterAuraColor(): String = posterAuraColorFlow().first()

    suspend fun setPosterAuraColor(key: String) {
        write("POSTER_AURA_COLOR") {
            it[K.POSTER_AURA_COLOR] = com.hikari.app.ui.AuraColors.normalize(key)
        }
    }

    /** Which shape catalogue cards take (see [com.hikari.app.ui.PosterTypes]). */
    fun posterTypeFlow(): Flow<String> =
        store.data.map {
            com.hikari.app.ui.PosterTypes.normalize(it[K.POSTER_TYPE] ?: com.hikari.app.ui.PosterTypes.VERTICAL).also { v -> posterTypeMemory = v }
        }.distinctUntilChanged().flowOn(Dispatchers.Default)

    @Volatile
    var posterTypeMemory: String = com.hikari.app.ui.PosterTypes.VERTICAL

    suspend fun posterType(): String = posterTypeFlow().first()

    suspend fun setPosterType(key: String) {
        posterTypeMemory = com.hikari.app.ui.PosterTypes.normalize(key)
        write("POSTER_TYPE") {
            it[K.POSTER_TYPE] = com.hikari.app.ui.PosterTypes.normalize(key)
        }
    }

    fun posterGlowPointFlow(): Flow<Pair<Float, Float>> =
        store.data.map {
            val x = it[K.POSTER_GLOW_X] ?: DEFAULT_POSTER_GLOW_X
            val y = it[K.POSTER_GLOW_Y] ?: DEFAULT_POSTER_GLOW_Y
            x.coerceIn(0f, 1f) to y.coerceIn(0f, 1f)
        }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun posterGlowPoint(): Pair<Float, Float> = posterGlowPointFlow().first()

    suspend fun setPosterGlowPoint(x: Float, y: Float) {
        write("POSTER_GLOW_POINT") {
            it[K.POSTER_GLOW_X] = x.coerceIn(0f, 1f)
            it[K.POSTER_GLOW_Y] = y.coerceIn(0f, 1f)
        }
    }

    /** How hard that light burns, 0-100 (100 = a hard key light). */
    fun posterGlowStrengthFlow(): Flow<Int> =
        store.data.map {
            (it[K.POSTER_GLOW_STRENGTH] ?: DEFAULT_POSTER_GLOW_STRENGTH).coerceIn(0, 100)
        }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun posterGlowStrength(): Int = posterGlowStrengthFlow().first()

    suspend fun setPosterGlowStrength(value: Int) {
        write("POSTER_GLOW_STRENGTH") { it[K.POSTER_GLOW_STRENGTH] = value.coerceIn(0, 100) }
    }

    /** The colour the loading screen's own aura ring is drawn in. */
    fun loadingAuraColorFlow(): Flow<String> =
        store.data.map {
            com.hikari.app.ui.AuraColors.normalize(it[K.LOADING_AURA_COLOR])
        }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun loadingAuraColor(): String = loadingAuraColorFlow().first()

    suspend fun setLoadingAuraColor(key: String) {
        write("LOADING_AURA_COLOR") {
            it[K.LOADING_AURA_COLOR] = com.hikari.app.ui.AuraColors.normalize(key)
        }
    }

    // ---- The title wordmark on the loading cover ----

    fun loadingLogoFlow(): Flow<Boolean> =
        store.data.map { it[K.LOADING_LOGO] ?: true }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun loadingLogo(): Boolean = loadingLogoFlow().first()

    suspend fun setLoadingLogo(on: Boolean) {
        write("LOADING_LOGO") { it[K.LOADING_LOGO] = on }
    }

    /** How big the loading cover's wordmark is drawn, in percent of its default
     *  width (see [DEFAULT_LOADING_LOGO_SIZE]). Its own setting, independent of
     *  the detail page's ([detailLogoSizeFlow]). */
    fun loadingLogoSizeFlow(): Flow<Int> =
        store.data.map { (it[K.LOADING_LOGO_SIZE] ?: DEFAULT_LOADING_LOGO_SIZE).coerceIn(50, 160) }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun loadingLogoSize(): Int = loadingLogoSizeFlow().first()

    suspend fun setLoadingLogoSize(percent: Int) {
        write("LOADING_LOGO_SIZE") { it[K.LOADING_LOGO_SIZE] = percent.coerceIn(50, 160) }
    }

    // ---- Performance booster (Settings → Performance) ----

    fun perfModeFlow(): Flow<Boolean> =
        store.data.map { it[K.PERF_MODE] ?: false }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun perfMode(): Boolean = perfModeFlow().first()

    suspend fun setPerfMode(on: Boolean) {
        write("PERF_MODE") { it[K.PERF_MODE] = on }
    }

    // ---- Server search (Settings → Playback) ----

    fun searchAllExtensionsFlow(): Flow<Boolean> =
        store.data.map { it[K.SEARCH_ALL_EXTENSIONS] ?: false }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun searchAllExtensions(): Boolean = searchAllExtensionsFlow().first()

    suspend fun setSearchAllExtensions(all: Boolean) {
        write("SEARCH_ALL_EXTENSIONS") { it[K.SEARCH_ALL_EXTENSIONS] = all }
    }

    fun nuvioSearchAllFlow(): Flow<Boolean> =
        store.data.map { it[K.NUVIO_SEARCH_ALL] ?: true }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun nuvioSearchAll(): Boolean = nuvioSearchAllFlow().first()

    suspend fun setNuvioSearchAll(on: Boolean) {
        write("NUVIO_SEARCH_ALL") { it[K.NUVIO_SEARCH_ALL] = on }
    }

    fun stremioSearchAllFlow(): Flow<Boolean> =
        store.data.map { it[K.STREMIO_SEARCH_ALL] ?: true }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun stremioSearchAll(): Boolean = stremioSearchAllFlow().first()

    suspend fun setStremioSearchAll(on: Boolean) {
        write("STREMIO_SEARCH_ALL") { it[K.STREMIO_SEARCH_ALL] = on }
    }

    fun engineFamiliesFlow(): Flow<Set<String>> = store.data.map { prefs ->
        val out = HashSet<String>(8)
        if (prefs[K.NUVIO_SEARCH_ALL] != false) out += ProviderType.NUVIO.name
        if (prefs[K.STREMIO_SEARCH_ALL] != false) out += ProviderType.STREMIO.name
        out += prefs[K.SEARCH_FAMILY_TYPES].orEmpty()
        out
    }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun engineFamilies(): Set<String> = engineFamiliesFlow().first()

    /** Turns one engine's family search on or off. Nuvio and Stremio write their
     *  own keys so the two switches that already existed keep their meaning (and
     *  so an older build of the app still reads them). */
    suspend fun setEngineFamily(type: ProviderType, on: Boolean) {
        when (type) {
            ProviderType.NUVIO -> setNuvioSearchAll(on)
            ProviderType.STREMIO -> setStremioSearchAll(on)
            else -> write("SEARCH_FAMILY_TYPES") { prefs ->
                val current = prefs[K.SEARCH_FAMILY_TYPES].orEmpty()
                prefs[K.SEARCH_FAMILY_TYPES] =
                    if (on) current + type.name else current - type.name
            }
        }
    }

    fun searchNuvioIdsFlow(): Flow<Set<String>> =
        store.data.map { it[K.SEARCH_NUVIO_IDS] ?: emptySet() }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun searchNuvioIds(): Set<String> = searchNuvioIdsFlow().first()

    suspend fun setSearchNuvioIds(ids: kotlin.collections.Collection<String>) {
        write("SEARCH_NUVIO_IDS") { it[K.SEARCH_NUVIO_IDS] = ids.toSet() }
    }

    fun searchStremioIdsFlow(): Flow<Set<String>> =
        store.data.map { it[K.SEARCH_STREMIO_IDS] ?: emptySet() }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun searchStremioIds(): Set<String> = searchStremioIdsFlow().first()

    suspend fun setSearchStremioIds(ids: kotlin.collections.Collection<String>) {
        write("SEARCH_STREMIO_IDS") { it[K.SEARCH_STREMIO_IDS] = ids.toSet() }
    }

    fun searchFamilyIdsFlow(): Flow<Set<String>> =
        store.data.map { it[K.SEARCH_FAMILY_IDS] ?: emptySet() }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun searchFamilyIds(): Set<String> = searchFamilyIdsFlow().first()

    suspend fun setSearchFamilyIds(ids: kotlin.collections.Collection<String>) {
        write("SEARCH_FAMILY_IDS") { it[K.SEARCH_FAMILY_IDS] = ids.toSet() }
    }

    fun playScopeAskFlow(): Flow<Boolean> =
        store.data.map { it[K.PLAY_SCOPE_ASK] ?: false }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun playScopeAsk(): Boolean = playScopeAskFlow().first()

    suspend fun setPlayScopeAsk(ask: Boolean) {
        write("PLAY_SCOPE_ASK") { it[K.PLAY_SCOPE_ASK] = ask }
    }

    // ---- Server search time (Settings → Playback & Servers) ----

    fun serverTimeoutAllFlow(): Flow<Int> =
        store.data.map { (it[K.SERVER_TIMEOUT_ALL] ?: 0).coerceIn(0, 100) }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun serverTimeoutAll(): Int = serverTimeoutAllFlow().first()

    suspend fun setServerTimeoutAll(secs: Int) {
        write("SERVER_TIMEOUT_ALL") { it[K.SERVER_TIMEOUT_ALL] = secs.coerceIn(0, 100) }
    }

    /** providerId → seconds for the extensions the user overrode. */
    fun serverTimeoutsFlow(): Flow<Map<String, Int>> =
        store.data.map { parseServerTimeouts(it[K.SERVER_TIMEOUTS]) }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun serverTimeouts(): Map<String, Int> = serverTimeoutsFlow().first()

    suspend fun serverTimeoutFor(providerId: String): Int? {
        val all = serverTimeoutAll()
        if (all in 20..100) return all
        return serverTimeouts()[providerId]?.coerceIn(20, 100)
    }

    /** Override one extension ([secs] null or out of range removes the entry). */
    suspend fun setServerTimeout(providerId: String, secs: Int?) {
        val map = serverTimeouts().toMutableMap()
        if (secs == null || secs !in 20..100) map.remove(providerId)
        else map[providerId] = secs
        val json = JSONObject()
        for ((k, v) in map) json.put(k, v)
        write("SERVER_TIMEOUTS") { it[K.SERVER_TIMEOUTS] = json.toString() }
    }

    private fun parseServerTimeouts(raw: String?): Map<String, Int> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val o = JSONObject(raw)
            val out = LinkedHashMap<String, Int>()
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next() as String
                val v = o.optInt(k, -1)
                if (k.isNotBlank() && v in 20..100) out[k] = v
            }
            out
        }.getOrDefault(emptyMap())
    }

    // ---- Per-profile locks (Profiles screen; separate from the app lock) ----

    /** profileId → `algo:salt:hash` for the profiles that have their own
     *  password. Absent = that profile is not locked. */
    fun profileLocksFlow(): Flow<Map<String, String>> =
        store.data.map { parseProfileLocks(it[K.PROFILE_LOCKS]) }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun profileLockSecret(profileId: String): String? =
        profileLocksFlow().first()[profileId]

    /** Sets ([secret] non-blank) or removes (blank) one profile's password. */
    suspend fun setProfileLockSecret(profileId: String, secret: String) {
        val map = profileLocksFlow().first().toMutableMap()
        if (secret.isBlank()) map.remove(profileId) else map[profileId] = secret
        val json = JSONObject()
        for ((k, v) in map) json.put(k, v)
        write("PROFILE_LOCKS") { it[K.PROFILE_LOCKS] = json.toString() }
    }

    private fun parseProfileLocks(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val o = JSONObject(raw)
            val out = LinkedHashMap<String, String>()
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next() as String
                val v = o.optString(k)
                if (k.isNotBlank() && v.isNotBlank()) out[k] = v
            }
            out
        }.getOrDefault(emptyMap())
    }

    fun iptvGroupModeFlow(): Flow<String> =
        store.data.map { normalizeIptvGroupMode(it[K.IPTV_GROUP_MODE]) }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun iptvGroupMode(): String = iptvGroupModeFlow().first()

    suspend fun setIptvGroupMode(mode: String) {
        write("IPTV_GROUP_MODE") { it[K.IPTV_GROUP_MODE] = normalizeIptvGroupMode(mode) }
    }

         // ---- Tracker-only server routing ------------------------------------
     fun trackerServerSearchAllFlow(): Flow<Boolean> = store.data.map { it[K.TRACKER_SERVER_SEARCH_ALL] ?: true }.distinctUntilChanged().flowOn(Dispatchers.Default)
     suspend fun trackerServerSearchAll(): Boolean = trackerServerSearchAllFlow().first()
     suspend fun setTrackerServerSearchAll(on: Boolean) { write("TRACKER_SERVER_SEARCH_ALL") { it[K.TRACKER_SERVER_SEARCH_ALL] = on } }
     fun trackerNuvioSearchAllFlow(): Flow<Boolean> = store.data.map { it[K.TRACKER_NUVIO_SEARCH_ALL] ?: true }.distinctUntilChanged().flowOn(Dispatchers.Default)
     suspend fun trackerNuvioSearchAll(): Boolean = trackerNuvioSearchAllFlow().first()
     suspend fun setTrackerNuvioSearchAll(on: Boolean) { write("TRACKER_NUVIO_SEARCH_ALL") { it[K.TRACKER_NUVIO_SEARCH_ALL] = on } }
     fun trackerStremioSearchAllFlow(): Flow<Boolean> = store.data.map { it[K.TRACKER_STREMIO_SEARCH_ALL] ?: true }.distinctUntilChanged().flowOn(Dispatchers.Default)
     suspend fun trackerStremioSearchAll(): Boolean = trackerStremioSearchAllFlow().first()
     suspend fun setTrackerStremioSearchAll(on: Boolean) { write("TRACKER_STREMIO_SEARCH_ALL") { it[K.TRACKER_STREMIO_SEARCH_ALL] = on } }
     fun trackerEngineFamiliesFlow(): Flow<Set<String>> = store.data.map { prefs ->
         val out = HashSet<String>(8)
         if (prefs[K.TRACKER_NUVIO_SEARCH_ALL] != false) out += ProviderType.NUVIO.name
         if (prefs[K.TRACKER_STREMIO_SEARCH_ALL] != false) out += ProviderType.STREMIO.name
         out += prefs[K.TRACKER_FAMILY_TYPES].orEmpty()
         out
     }.distinctUntilChanged().flowOn(Dispatchers.Default)
     suspend fun trackerEngineFamilies(): Set<String> = trackerEngineFamiliesFlow().first()
     suspend fun setTrackerEngineFamily(type: ProviderType, on: Boolean) {
         when (type) {
             ProviderType.NUVIO -> setTrackerNuvioSearchAll(on)
             ProviderType.STREMIO -> setTrackerStremioSearchAll(on)
             else -> write("TRACKER_FAMILY_TYPES") { prefs ->
                 val current = prefs[K.TRACKER_FAMILY_TYPES].orEmpty()
                 prefs[K.TRACKER_FAMILY_TYPES] = if (on) current + type.name else current - type.name
             }
         }
     }
     fun trackerProviderIdsFlow(): Flow<Set<String>> = store.data.map { it[K.TRACKER_PROVIDER_IDS] ?: emptySet() }.distinctUntilChanged().flowOn(Dispatchers.Default)
     suspend fun trackerProviderIds(): Set<String> = trackerProviderIdsFlow().first()
     suspend fun setTrackerProviderIds(ids: kotlin.collections.Collection<String>) { write("TRACKER_PROVIDER_IDS") { it[K.TRACKER_PROVIDER_IDS] = ids.toSet() } }

fun searchExceptionOnFlow(): Flow<Boolean> =
        store.data.map { it[K.SEARCH_EXCEPTION_ON] ?: false }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun searchExceptionOn(): Boolean = searchExceptionOnFlow().first()

    suspend fun setSearchExceptionOn(on: Boolean) {
        write("SEARCH_EXCEPTION_ON") { it[K.SEARCH_EXCEPTION_ON] = on }
    }

    /** The ids of the extensions chosen as exceptions (see [searchExceptionOnFlow]). */
    fun searchExceptionIdsFlow(): Flow<Set<String>> =
        store.data.map { it[K.SEARCH_EXCEPTION_IDS] ?: emptySet() }.distinctUntilChanged().flowOn(Dispatchers.Default)
    suspend fun searchExceptionIds(): Set<String> = searchExceptionIdsFlow().first()

    suspend fun setSearchExceptionIds(ids: kotlin.collections.Collection<String>) {
        write("SEARCH_EXCEPTION_IDS") { it[K.SEARCH_EXCEPTION_IDS] = ids.toSet() }
    }

    fun searchExceptionTypesFlow(): Flow<Set<String>> =
        store.data.map { it[K.SEARCH_EXCEPTION_TYPES] ?: emptySet() }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun searchExceptionTypes(): Set<String> = searchExceptionTypesFlow().first()

    suspend fun setSearchExceptionTypes(types: kotlin.collections.Collection<String>) {
        write("SEARCH_EXCEPTION_TYPES") { it[K.SEARCH_EXCEPTION_TYPES] = types.toSet() }
    }

    fun searchExceptionExcludesFlow(): Flow<Set<String>> =
        store.data.map { it[K.SEARCH_EXCEPTION_EXCLUDES] ?: emptySet() }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun searchExceptionExcludes(): Set<String> = searchExceptionExcludesFlow().first()

    suspend fun setSearchExceptionExcludes(ids: kotlin.collections.Collection<String>) {
        write("SEARCH_EXCEPTION_EXCLUDES") { it[K.SEARCH_EXCEPTION_EXCLUDES] = ids.toSet() }
    }

    fun activeSearchExceptionsFlow(): Flow<Set<String>> =
        store.data.map { prefs ->
            if (prefs[K.SEARCH_EXCEPTION_ON] != true) return@map emptySet<String>()
            val ids = prefs[K.SEARCH_EXCEPTION_IDS] ?: emptySet()
            val types = prefs[K.SEARCH_EXCEPTION_TYPES] ?: emptySet()
            val excluded = prefs[K.SEARCH_EXCEPTION_EXCLUDES] ?: emptySet()
            if (types.isEmpty()) return@map ids - excluded
            val byEngine = parseProviders(prefs[K.PROVIDERS])
                .filter { it.type.name in types }
                .map { it.id }
            (ids + byEngine) - excluded
        }.distinctUntilChanged().flowOn(Dispatchers.Default)

    // ---- Bottom navigation bar layout ----

    /** "classic" (the seamless tonal plate), "floating" (a detached glass pill)
     *  or "animated" (full bar at the top of a page, floating pill once you
     *  scroll — see [com.hikari.app.ui.navigation.NavStyles]). */
    fun navStyleFlow(): Flow<String> =
        store.data.map {
            com.hikari.app.ui.navigation.NavStyles.normalize(
                it[K.NAV_STYLE] ?: com.hikari.app.ui.navigation.NavStyles.ANIMATED
            )
        }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun navStyle(): String = navStyleFlow().first()

    suspend fun setNavStyle(style: String) {
        write("NAV_STYLE") { it[K.NAV_STYLE] = style }
    }

    // ---- The taskbar's icon labels (Settings → App Layout) ----

    /** Whether the bottom bar writes each button's name under its icon. On by
     *  default — that is how the bar ships. */
    fun tabLabelsFlow(): Flow<Boolean> =
        store.data.map { it[K.TAB_LABELS] ?: true }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun tabLabels(): Boolean = tabLabelsFlow().first()

    suspend fun setTabLabels(show: Boolean) {
        write("TAB_LABELS") { it[K.TAB_LABELS] = show }
    }

    // ---- The detail page's rating strip (Settings → App Layout) ----

    /** Whether the detail page draws the IMDb/RT/… badges. On unless the user
     *  turned it off (see [setShowDetailRating]). */
    fun showDetailRatingFlow(): Flow<Boolean> =
        store.data.map { it[K.SHOW_DETAIL_RATING] ?: true }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun showDetailRating(): Boolean = showDetailRatingFlow().first()

    suspend fun setShowDetailRating(show: Boolean) {
        write("SHOW_DETAIL_RATING") { it[K.SHOW_DETAIL_RATING] = show }
    }

    // ---- Full screen app mode (Settings → App Layout) ----

    /** True when the user asked for the normal, windowed layout: system status
     *  bar and the phone's own navigation bar visible on every screen. */
    fun fullscreenOffFlow(): Flow<Boolean> =
        store.data.map { it[K.FULLSCREEN_OFF] ?: false }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun fullscreenOff(): Boolean = fullscreenOffFlow().first()

    suspend fun setFullscreenOff(off: Boolean) {
        write("FULLSCREEN_OFF") { it[K.FULLSCREEN_OFF] = off }
    }

    // ---- Television (one APK, two layouts — see com.hikari.app.tv.TvMode) ----

    /** Which layout to draw: "auto" (follow the device), "tv" or "phone". */
    fun tvModeFlow(): Flow<String> =
        store.data.map { com.hikari.app.tv.TvMode.normalize(it[K.TV_MODE]) }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun tvMode(): String = tvModeFlow().first()

    suspend fun setTvMode(mode: String) {
        write("TV_MODE") { it[K.TV_MODE] = com.hikari.app.tv.TvMode.normalize(mode) }
    }

    /** Screen-edge padding in dp on a television — see [K.TV_OVERSCAN]. */
    fun tvOverscanFlow(): Flow<Int> =
        store.data.map {
            (it[K.TV_OVERSCAN] ?: com.hikari.app.tv.TvUi.DEFAULT_OVERSCAN_DP)
                .coerceIn(0, com.hikari.app.tv.TvUi.MAX_OVERSCAN_DP)
        }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun tvOverscan(): Int = tvOverscanFlow().first()

    suspend fun setTvOverscan(dp: Int) {
        write("TV_OVERSCAN") {
            it[K.TV_OVERSCAN] = dp.coerceIn(0, com.hikari.app.tv.TvUi.MAX_OVERSCAN_DP)
        }
    }

    /** Lighter visuals on a television — see [K.TV_PERF]. Read by
     *  [com.hikari.app.ui.rememberPosterStyle], which drops the poster
     *  treatments while it is on. */
    fun tvPerfFlow(): Flow<Boolean> =
        store.data.map { it[K.TV_PERF] ?: false }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun tvPerf(): Boolean = tvPerfFlow().first()

    suspend fun setTvPerf(on: Boolean) {
        write("TV_PERF") { it[K.TV_PERF] = on }
    }

    /** True once the user has worked the performance-mode switch themselves. */
    fun tvPerfChosenFlow(): Flow<Boolean> =
        store.data.map { it[K.TV_PERF_CHOSEN] ?: false }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun tvPerfChosen(): Boolean = tvPerfChosenFlow().first()

    /** Records that the performance mode is now the USER's choice, not the
     *  layout's — [com.hikari.app.HikariApp] stops keeping it in step with the
     *  layout from here on. */
    suspend fun setTvPerfChosen(chosen: Boolean) {
        write("TV_PERF_CHOSEN") { it[K.TV_PERF_CHOSEN] = chosen }
    }

    /** True once the first-run television defaults have been applied. */
    fun tvSeededFlow(): Flow<Boolean> =
        store.data.map { it[K.TV_SEEDED] ?: false }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun tvSeeded(): Boolean = tvSeededFlow().first()

    fun playerUiDefaultsSeededFlow(): Flow<Boolean> =
        store.data.map { it[K.PLAYER_UI_DEFAULTS_SEEDED] ?: false }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    suspend fun playerUiDefaultsSeeded(): Boolean = playerUiDefaultsSeededFlow().first()

    suspend fun setPlayerUiDefaultsSeeded(seeded: Boolean) {
        write("PLAYER_UI_DEFAULTS_SEEDED") { it[K.PLAYER_UI_DEFAULTS_SEEDED] = seeded }
    }

    suspend fun setTvSeeded(seeded: Boolean) {
        write("TV_SEEDED") { it[K.TV_SEEDED] = seeded }
    }

    fun tvPosterScaleFlow(): Flow<Int> =
        store.data.map {
            (it[K.TV_POSTER_SCALE] ?: 100).coerceIn(70, 150)
        }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun tvPosterScale(): Int = tvPosterScaleFlow().first()

    suspend fun setTvPosterScale(scale: Int) {
        write("TV_POSTER_SCALE") { it[K.TV_POSTER_SCALE] = scale.coerceIn(70, 150) }
    }

    fun tvDetailBigFlow(): Flow<Boolean> =
        store.data.map { it[K.TV_DETAIL_BIG] ?: false }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun tvDetailBig(): Boolean = tvDetailBigFlow().first()

    suspend fun setTvDetailBig(big: Boolean) {
        write("TV_DETAIL_BIG") { it[K.TV_DETAIL_BIG] = big }
    }

    fun tvDetailBigChosenFlow(): Flow<Boolean> =
        store.data.map { it[K.TV_DETAIL_BIG_CHOSEN] ?: false }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun tvDetailBigChosen(): Boolean = tvDetailBigChosenFlow().first()

    suspend fun setTvDetailBigChosen(chosen: Boolean) {
        write("TV_DETAIL_BIG_CHOSEN") { it[K.TV_DETAIL_BIG_CHOSEN] = chosen }
    }

    fun tvBlurDetailFlow(): Flow<Boolean> =
        store.data.map { it[K.TV_BLUR_DETAIL] ?: false }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun tvBlurDetail(): Boolean = tvBlurDetailFlow().first()

    suspend fun setTvBlurDetail(on: Boolean) {
        write("TV_BLUR_DETAIL") { it[K.TV_BLUR_DETAIL] = on }
    }

    /** -1 = Auto (cinema grid); 0/1/2 = the header button's choice on TV. */
    fun tvEpisodeViewModeFlow(): Flow<Int> =
        store.data.map { (it[K.TV_EPISODE_VIEW_MODE] ?: -1).coerceIn(-1, 2) }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun tvEpisodeViewMode(): Int = tvEpisodeViewModeFlow().first()

    suspend fun setTvEpisodeViewMode(mode: Int) {
        write("TV_EPISODE_VIEW_MODE") { it[K.TV_EPISODE_VIEW_MODE] = mode.coerceIn(-1, 2) }
    }

    // ---- The floating bottom bar: which tab buttons the user keeps ----

    /** Routes of the bottom-bar tabs the user has switched off (see
     *  [com.hikari.app.ui.navigation.BottomTabs]). Hiding one only removes its
     *  button — the screen itself stays reachable from inside the app. */
    fun hiddenTabsFlow(): Flow<Set<String>> =
        store.data.map { parseStringList(it[K.HIDDEN_TABS]).toSet() }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun hiddenTabs(): Set<String> = hiddenTabsFlow().first()

    suspend fun setTabHidden(route: String, hidden: Boolean) {
        val cur = hiddenTabs()
        val next = if (hidden) cur + route else cur - route
        write("HIDDEN_TABS") { it[K.HIDDEN_TABS] = encodeStringList(next.toList()) }
    }

    // ---- Recent searches (the Search tab) ----

    fun searchHistoryFlow(): Flow<List<String>> =
        store.data.map { parseStringList(it[K.SEARCH_HISTORY]) }
            .distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun searchHistory(): List<String> = searchHistoryFlow().first()

    suspend fun addSearchHistory(query: String) {
        val q = query.trim()
        if (q.length < 2) return
        val next = (listOf(q) + searchHistory().filterNot { it.equals(q, ignoreCase = true) })
            .take(SEARCH_HISTORY_MAX)
        write("SEARCH_HISTORY") { it[K.SEARCH_HISTORY] = encodeStringList(next) }
    }

    /** Forgets one remembered search — the ✕ on its chip. */
    suspend fun removeSearchHistory(query: String) {
        val q = query.trim()
        val next = searchHistory().filterNot { it.equals(q, ignoreCase = true) }
        write("SEARCH_HISTORY") { it[K.SEARCH_HISTORY] = encodeStringList(next) }
    }

    /** Forgets every remembered search ("Clear all"). */
    suspend fun clearSearchHistory() {
        write("SEARCH_HISTORY") { it[K.SEARCH_HISTORY] = encodeStringList(emptyList()) }
    }

    /** Slow / mobile-data mode: raise the source-search and stream-probe
     *  timeouts and retry providers that time out, so a weak connection doesn't
     *  end in "No playable sources found". Off by default so fast connections
     *  keep their snappy timeouts. */
    fun slowConnectionFlow(): Flow<Boolean> =
        store.data.map { it[K.SLOW_CONNECTION] ?: false }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun slowConnection(): Boolean = slowConnectionFlow().first()

    suspend fun setSlowConnection(enabled: Boolean) {
        write("SLOW_CONNECTION") { it[K.SLOW_CONNECTION] = enabled }
    }

    /** The chosen resolver ([com.hikari.app.net.DnsProviders] key). Default is
     *  "system" — the phone's own DNS, with Hikari's encrypted fallback, i.e.
     *  exactly the app's behaviour before this setting existed. */
    fun dnsProviderFlow(): Flow<String> =
        store.data.map { it[K.DNS_PROVIDER] ?: DnsProviders.SYSTEM }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun dnsProvider(): String = dnsProviderFlow().first()

    suspend fun setDnsProvider(key: String) {
        write("DNS_PROVIDER") { it[K.DNS_PROVIDER] = key }
    }

    /** What a Custom DNS choice points at, as the user typed it (normalised to
     *  an endpoint by [DnsProviders.customEndpoint] when it is used). */
    fun customDnsFlow(): Flow<String> =
        store.data.map { it[K.CUSTOM_DNS] ?: "" }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun customDns(): String = customDnsFlow().first()

    suspend fun setCustomDns(url: String) {
        write("CUSTOM_DNS") { it[K.CUSTOM_DNS] = url }
    }

    fun playWaitServersFlow(): Flow<Boolean> =
        store.data.map { it[K.PLAY_WAIT_SERVERS] ?: false }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun playWaitServers(): Boolean = playWaitServersFlow().first()

    suspend fun setPlayWaitServers(wait: Boolean) {
        write("PLAY_WAIT_SERVERS") { it[K.PLAY_WAIT_SERVERS] = wait }
    }

    /** How many servers to wait for when [playWaitServersFlow] is on (1–5). */
    fun playMinServersFlow(): Flow<Int> =
        store.data.map { (it[K.PLAY_MIN_SERVERS] ?: 2).coerceIn(1, 5) }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun playMinServers(): Int = playMinServersFlow().first()

    suspend fun setPlayMinServers(n: Int) {
        write("PLAY_MIN_SERVERS") { it[K.PLAY_MIN_SERVERS] = n.coerceIn(1, 5) }
    }

    fun askServerOnPlayFlow(): Flow<Boolean> =
        store.data.map { it[K.ASK_SERVER] ?: false }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun askServerOnPlay(): Boolean = askServerOnPlayFlow().first()

    suspend fun setAskServerOnPlay(ask: Boolean) {
        write("ASK_SERVER") { it[K.ASK_SERVER] = ask }
    }

    fun failoverAskOnFailureFlow(): Flow<Boolean> =
        store.data.map { it[K.FAILOVER_ASK] ?: true }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun failoverAskOnFailure(): Boolean = failoverAskOnFailureFlow().first()

    suspend fun setFailoverAskOnFailure(ask: Boolean) {
        write("FAILOVER_ASK") { it[K.FAILOVER_ASK] = ask }
    }

    /** Show the full-screen title card (backdrop + breathing name) from Play
     *  until the first frame of video. Off = the player opens straight away
     *  with just a round loading spinner. On by default. */
    fun showLoadingBannerFlow(): Flow<Boolean> =
        store.data.map { it[K.SHOW_LOADING_BANNER] ?: true }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun showLoadingBanner(): Boolean = showLoadingBannerFlow().first()

    suspend fun setShowLoadingBanner(show: Boolean) {
        write("SHOW_LOADING_BANNER") { it[K.SHOW_LOADING_BANNER] = show }
    }

    /** Whether the player may suggest turning on Slow connection mode when a
     *  play looks like it is struggling on a weak connection. On by default —
     *  a user who keeps getting a wrong "your connection looks slow" verdict
     *  turns it off here and never sees the dialog again. */
    fun slowTipEnabledFlow(): Flow<Boolean> =
        store.data.map { it[K.SLOW_TIP_ENABLED] ?: true }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun slowTipEnabled(): Boolean = slowTipEnabledFlow().first()

    suspend fun setSlowTipEnabled(enabled: Boolean) {
        write("SLOW_TIP_ENABLED") { it[K.SLOW_TIP_ENABLED] = enabled }
    }

    /** Set by the dialog's "Don't ask again" — permanent, unlike the timed
     *  cooldown of a plain dismissal. */
    fun slowTipDontAskFlow(): Flow<Boolean> =
        store.data.map { it[K.SLOW_TIP_DONT_ASK] ?: false }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun slowTipDontAsk(): Boolean = slowTipDontAskFlow().first()

    suspend fun setSlowTipDontAsk(dontAsk: Boolean) {
        write("SLOW_TIP_DONT_ASK") { it[K.SLOW_TIP_DONT_ASK] = dontAsk }
    }

    /** When the tip was last dismissed with "Not now" (0 = never). Keeps the
     *  dialog from reappearing on every single play. */
    fun slowTipLastDismissFlow(): Flow<Long> =
        store.data.map { it[K.SLOW_TIP_LAST_DISMISS] ?: 0L }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun slowTipLastDismiss(): Long = slowTipLastDismissFlow().first()

    suspend fun setSlowTipLastDismiss(atMs: Long) {
        write("SLOW_TIP_LAST_DISMISS") { it[K.SLOW_TIP_LAST_DISMISS] = atMs }
    }

    /** Set by the launch Telegram invitation's "Don't show this again" checkbox,
     *  so the dialog never comes back. */
    fun telegramDontShowFlow(): Flow<Boolean> =
        store.data.map { it[K.TELEGRAM_DONT_SHOW] ?: false }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun telegramDontShow(): Boolean = telegramDontShowFlow().first()

    suspend fun setTelegramDontShow(dontShow: Boolean) {
        write("TELEGRAM_DONT_SHOW") { it[K.TELEGRAM_DONT_SHOW] = dontShow }
    }

    /** The app version the community dialog was last shown (or dismissed) for,
     *  so it comes back once after every update — and never nags in between. */
    suspend fun communitySeenVersion(): String = store.data.map { it[K.COMMUNITY_SEEN_VERSION].orEmpty() }
        .distinctUntilChanged().flowOn(Dispatchers.Default).first()

    suspend fun setCommunitySeenVersion(version: String) {
        write("COMMUNITY_SEEN_VERSION") { it[K.COMMUNITY_SEEN_VERSION] = version }
    }

    suspend fun communityDontShowVersion(): String = store.data.map { it[K.COMMUNITY_DONT_SHOW_VERSION].orEmpty() }
        .distinctUntilChanged().flowOn(Dispatchers.Default).first()

    suspend fun setCommunityDontShowVersion(version: String) {
        write("COMMUNITY_DONT_SHOW_VERSION") { it[K.COMMUNITY_DONT_SHOW_VERSION] = version }
    }

    suspend fun telegramView(): String = store.data.map { it[K.TELEGRAM_VIEW].orEmpty() }
        .distinctUntilChanged().flowOn(Dispatchers.Default).first()

    suspend fun setTelegramView(view: String) {
        write("TELEGRAM_VIEW") { it[K.TELEGRAM_VIEW] = view }
    }

    /** How many downloads may run simultaneously (1–10). */
    fun downloadConcurrencyFlow(): Flow<Int> =
        store.data.map { (it[K.DOWNLOAD_CONCURRENCY] ?: 3).coerceIn(1, 10) }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun downloadConcurrency(): Int = downloadConcurrencyFlow().first()

    suspend fun setDownloadConcurrency(n: Int) {
        write("DOWNLOAD_CONCURRENCY") { it[K.DOWNLOAD_CONCURRENCY] = n.coerceIn(1, 10) }
    }

    /** Which provider the Home screen is currently showing (empty = All). */
    fun homeProviderFlow(): Flow<String> =
        store.data.map { it[K.HOME_PROVIDER] ?: "" }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun homeProvider(): String = homeProviderFlow().first()

    suspend fun setHomeProvider(id: String) {
        write("HOME_PROVIDER") { it[K.HOME_PROVIDER] = id }
    }

    /**
     * The MULTI pick — every source key Home should draw when the user has
     * selected more than one in the picker (see [setHomeProviders]).
     */
    fun homeProvidersFlow(): Flow<Set<String>> =
        store.data.map { parseStringList(it[K.HOME_PROVIDERS]).toSet() }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun homeProviders(): Set<String> = homeProvidersFlow().first()

    suspend fun setHomeProviders(ids: Set<String>) {
        val list = ids.filter { it.isNotBlank() }.toList()
        write("HOME_PROVIDERS") {
            it[K.HOME_PROVIDERS] = encodeStringList(list)
            if (list.size <= 1) {
                it[K.HOME_PROVIDER] = list.firstOrNull().orEmpty()
            }
        }
    }

    // ---- The IPTV tab (off by default; Settings → Taskbar buttons) ----

    /** True when the IPTV button has been switched on in the taskbar settings. */
    fun iptvTabFlow(): Flow<Boolean> =
        store.data.map { it[K.IPTV_TAB] ?: false }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun iptvTab(): Boolean = iptvTabFlow().first()

    suspend fun setIptvTab(shown: Boolean) {
        write("IPTV_TAB") { it[K.IPTV_TAB] = shown }
    }

    /** Shape of the IPTV tab's tiles ([TileShapes] key; poster by default). */
    fun iptvShapeFlow(): Flow<String> =
        store.data.map { TileShapes.normalize(it[K.IPTV_SHAPE]) }.distinctUntilChanged().flowOn(Dispatchers.Default)

    fun normalizeIptvGroupMode(mode: String?): String =
        if (mode == "language" || mode == "category" || mode == "country") mode else "groups"

    suspend fun setIptvShape(shape: String) {
        write("IPTV_SHAPE") { it[K.IPTV_SHAPE] = TileShapes.normalize(shape) }
    }

    /** Shape of the IPTV tab's Tor page tiles ([TileShapes] key; poster by default). */
    fun iptvTorShapeFlow(): Flow<String> =
        store.data.map { TileShapes.normalize(it[K.IPTV_TOR_SHAPE]) }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun setIptvTorShape(shape: String) {
        write("IPTV_TOR_SHAPE") { it[K.IPTV_TOR_SHAPE] = TileShapes.normalize(shape) }
    }

    /** Shape of the IPTV tab's Network stream page tiles ([TileShapes] key; poster by default). */
    fun iptvStreamShapeFlow(): Flow<String> =
        store.data.map { TileShapes.normalize(it[K.IPTV_STREAM_SHAPE]) }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun setIptvStreamShape(shape: String) {
        write("IPTV_STREAM_SHAPE") { it[K.IPTV_STREAM_SHAPE] = TileShapes.normalize(shape) }
    }

    // ---- The Manga tab (ON by default; Settings → Taskbar buttons) ----

    fun mangaTabFlow(): Flow<Boolean> =
        store.data.map { it[K.MANGA_TAB] ?: true }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun mangaTab(): Boolean = mangaTabFlow().first()

    suspend fun setMangaTab(shown: Boolean) {
        write("MANGA_TAB") { it[K.MANGA_TAB] = shown }
    }

    // ---- The Stats tab (off by default; Settings → Taskbar buttons) ----

    /** True when the Stats button has been switched on in the taskbar settings. */
    fun statsTabFlow(): Flow<Boolean> =
        store.data.map { it[K.STATS_TAB] ?: false }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun statsTab(): Boolean = statsTabFlow().first()

    suspend fun setStatsTab(shown: Boolean) {
        write("STATS_TAB") { it[K.STATS_TAB] = shown }
    }

    // ---- The Telegram tab (ON by default; Settings → Taskbar buttons) ----

    fun telegramTabFlow(): Flow<Boolean> =
        store.data.map { it[K.TELEGRAM_TAB] ?: true }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun telegramTab(): Boolean = telegramTabFlow().first()

    suspend fun setTelegramTab(shown: Boolean) {
        write("TELEGRAM_TAB") { it[K.TELEGRAM_TAB] = shown }
    }

    fun telegramChannelsFlow(): Flow<String> =
        store.data.map { it[K.TELEGRAM_CHANNELS] ?: "" }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun telegramChannels(): String = telegramChannelsFlow().first()

    suspend fun setTelegramChannels(json: String) {
        write("TELEGRAM_CHANNELS") { it[K.TELEGRAM_CHANNELS] = json }
    }

    /**
     * The individual Telegram video links the tab lists, as JSON:
     * `[{"url":"https://t.me/<channel>/<id>","channel":"@channel","id":123,…}]`.
     *
     * A channel is a feed to browse; a link is ONE video post — the thing someone
     * sends you when they send a video. The two are stored apart for that reason
     * (see [com.hikari.app.telegram.TelegramLinks]).
     */
    fun telegramLinksFlow(): Flow<String> =
        store.data.map { it[K.TELEGRAM_LINKS] ?: "" }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun telegramLinks(): String = telegramLinksFlow().first()

    suspend fun setTelegramLinks(json: String) {
        write("TELEGRAM_LINKS") { it[K.TELEGRAM_LINKS] = json }
    }

    // ---- App lock (Settings → Privacy & Browsing) -------------------------

    /** True when the app asks for the password (or a fingerprint) on launch. */
    fun appLockFlow(): Flow<Boolean> =
        store.data.map { it[K.APP_LOCK] ?: false }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun appLock(): Boolean = appLockFlow().first()

    suspend fun setAppLock(on: Boolean) {
        write("APP_LOCK") { it[K.APP_LOCK] = on }
        if (on) {
            write("APP_LOCK_SESSION_OPEN") { it[K.APP_LOCK_SESSION_OPEN] = false }
        }
    }

    /**
     * The lock's secret as `salt:hash`, or blank when no password was ever set.
     * The password itself is never stored — see [com.hikari.app.lock.AppLock].
     */
    fun appLockSecretFlow(): Flow<String> =
        store.data.map { it[K.APP_LOCK_SECRET] ?: "" }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun appLockSecret(): String = appLockSecretFlow().first()

    suspend fun setAppLockSecret(value: String) {
        write("APP_LOCK_SECRET") { it[K.APP_LOCK_SECRET] = value }
    }


    /** True when opening the My Stuff tab asks for its password first. */
    fun myStuffLockFlow(): Flow<Boolean> =
        store.data.map { it[K.MYSTUFF_LOCK] ?: false }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun myStuffLock(): Boolean = myStuffLockFlow().first()

    suspend fun setMyStuffLock(on: Boolean) {
        write("MYSTUFF_LOCK") { it[K.MYSTUFF_LOCK] = on }
        com.hikari.app.ui.screens.MyStuffUnlock.unlocked = !on
    }

    fun myStuffLockSecretFlow(): Flow<String> =
        store.data.map { it[K.MYSTUFF_LOCK_SECRET] ?: "" }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun myStuffLockSecret(): String = myStuffLockSecretFlow().first()

    suspend fun setMyStuffLockSecret(value: String) {
        write("MYSTUFF_LOCK_SECRET") { it[K.MYSTUFF_LOCK_SECRET] = value }
    }

    fun appLockLenFlow(): Flow<Int> =
        store.data.map { (it[K.APP_LOCK_LEN] ?: 0).coerceIn(0, 128) }
            .distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun appLockLen(): Int = appLockLenFlow().first()

    suspend fun setAppLockLen(value: Int) {
        write("APP_LOCK_LEN") { it[K.APP_LOCK_LEN] = value.coerceIn(0, 128) }
    }

    fun appLockBioFlow(): Flow<Boolean> =
        store.data.map { it[K.APP_LOCK_BIO] ?: true }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun appLockBio(): Boolean = appLockBioFlow().first()

    suspend fun setAppLockBio(on: Boolean) {
        write("APP_LOCK_BIO") { it[K.APP_LOCK_BIO] = on }
    }

    fun appLockScreenOffFlow(): Flow<Boolean> =
        store.data.map { it[K.APP_LOCK_SCREEN_OFF] ?: true }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun appLockScreenOff(): Boolean = appLockScreenOffFlow().first()

    suspend fun setAppLockScreenOff(on: Boolean) {
        write("APP_LOCK_SCREEN_OFF") { it[K.APP_LOCK_SCREEN_OFF] = on }
    }

    fun appLockDelayFlow(): Flow<Int> =
        store.data.map { (it[K.APP_LOCK_DELAY_MIN] ?: 0).coerceIn(0, 60) }
            .distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun appLockDelay(): Int = appLockDelayFlow().first()

    suspend fun setAppLockDelay(minutes: Int) {
        write("APP_LOCK_DELAY_MIN") { it[K.APP_LOCK_DELAY_MIN] = minutes.coerceIn(0, 60) }
    }

    fun appLockLeaveFlow(): Flow<Boolean> =
        store.data.map { it[K.APP_LOCK_LEAVE] ?: true }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun appLockLeave(): Boolean = appLockLeaveFlow().first()

    suspend fun setAppLockLeave(on: Boolean) {
        write("APP_LOCK_LEAVE") { it[K.APP_LOCK_LEAVE] = on }
    }

    suspend fun appLockSession(): Pair<Boolean, Long> {
        val prefs = store.data.first()
        return (prefs[K.APP_LOCK_SESSION_OPEN] ?: false) to (prefs[K.APP_LOCK_SESSION_AT] ?: 0L)
    }

    /** The user has just unlocked (or the lock is off): keep this unlock across
     *  a process restart. */
    suspend fun setAppLockSessionOpen(at: Long = System.currentTimeMillis()) {
        write("APP_LOCK_SESSION_OPEN") { it[K.APP_LOCK_SESSION_OPEN] = true }
        write("APP_LOCK_SESSION_AT") { it[K.APP_LOCK_SESSION_AT] = at }
    }

    /** The app has locked (or asks again for another reason): a fresh process
     *  must ask for the password. */
    suspend fun setAppLockSessionClosed() {
        write("APP_LOCK_SESSION_OPEN") { it[K.APP_LOCK_SESSION_OPEN] = false }
    }


    fun trackersFlow(): Flow<List<TrackerAccount>> =
        store.data.map { TrackerStore.parseAccounts(it[K.TRACKERS]) }
            .distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun trackers(): List<TrackerAccount> = trackersFlow().first()

    /** Adds or replaces one service's account (one row per [TrackerAccount.kind]). */
    suspend fun setTrackerAccount(account: TrackerAccount) {
        write("TRACKERS") { prefs ->
            val next = TrackerStore.parseAccounts(prefs[K.TRACKERS])
                .filterNot { it.kind == account.kind } + account
            prefs[K.TRACKERS] = TrackerStore.encodeAccounts(next)
        }
    }

    suspend fun removeTrackerAccount(kind: TrackerKind) {
        write("TRACKERS") { prefs ->
            val next = TrackerStore.parseAccounts(prefs[K.TRACKERS]).filterNot { it.kind == kind }
            prefs[K.TRACKERS] = TrackerStore.encodeAccounts(next)
        }
    }

    fun trackerClientsFlow(): Flow<List<TrackerClient>> =
        store.data.map { TrackerStore.parseClients(it[K.TRACKER_CLIENTS]) }
            .distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun trackerClients(): List<TrackerClient> {
        val stored = trackerClientsFlow().first()
        val missing = TrackerKind.entries
            .filter { k -> stored.none { it.kind == k } }
            .mapNotNull { TrackerBuiltIn.clientFor(it) }
        return stored + missing
    }

    suspend fun trackerClient(kind: TrackerKind): TrackerClient =
        trackerClients().firstOrNull { it.kind == kind } ?: TrackerClient(kind)

    suspend fun setTrackerClient(client: TrackerClient) {
        write("TRACKER_CLIENTS") { prefs ->
            val next = TrackerStore.parseClients(prefs[K.TRACKER_CLIENTS])
                .filterNot { it.kind == client.kind } + client
            prefs[K.TRACKER_CLIENTS] = TrackerStore.encodeClients(next)
        }
    }

    suspend fun trackerClientOverride(kind: TrackerKind): Boolean =
        store.data.first()[K.TRACKER_CLIENT_OVERRIDES]
            ?.split(",").orEmpty().any { it.trim() == kind.key } == true

    suspend fun setTrackerClientOverride(kind: TrackerKind, override: Boolean) {
        write("TRACKER_CLIENT_OVERRIDES") { prefs ->
            val next = prefs[K.TRACKER_CLIENT_OVERRIDES]
                ?.split(",").orEmpty().map { it.trim() }
                .filter { it.isNotBlank() && it != kind.key }
                .toMutableList()
            if (override) next.add(kind.key)
            prefs[K.TRACKER_CLIENT_OVERRIDES] = next.joinToString(",")
        }
    }

    suspend fun trackerPending(): com.hikari.app.tracker.TrackerApi.Pending? =
        com.hikari.app.tracker.TrackerApi.Pending.decode(store.data.first()[K.TRACKER_PENDING])

    suspend fun setTrackerPending(pending: com.hikari.app.tracker.TrackerApi.Pending?) {
        write("TRACKER_PENDING") { prefs ->
            if (pending == null) prefs.remove(K.TRACKER_PENDING)
            else prefs[K.TRACKER_PENDING] = pending.encode()
        }
    }

    fun trackerSyncFlow(): Flow<Boolean> =
        store.data.map { it[K.TRACKER_SYNC] ?: true }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun trackerSync(): Boolean = trackerSyncFlow().first()

    suspend fun setTrackerSync(on: Boolean) {
        write("TRACKER_SYNC") { prefs -> prefs[K.TRACKER_SYNC] = on }
    }

    /** The titles each service matched, keyed by [TrackerStore.matchKey]. */
    suspend fun trackerMatches(): Map<String, TrackerMatch> =
        TrackerStore.parseMatches(store.data.first()[K.TRACKER_MATCH])

    suspend fun putTrackerMatch(key: String, match: TrackerMatch) {
        write("TRACKER_MATCH") { prefs ->
            val next = LinkedHashMap(TrackerStore.parseMatches(prefs[K.TRACKER_MATCH]))
            next.remove(key)
            next[key] = match
            prefs[K.TRACKER_MATCH] = TrackerStore.encodeMatches(next)
        }
    }

    /** Forgets one cached match — used when a push is refused for that title. */
    suspend fun dropTrackerMatch(key: String) {
        write("TRACKER_MATCH") { prefs ->
            val next = LinkedHashMap(TrackerStore.parseMatches(prefs[K.TRACKER_MATCH]))
            if (next.remove(key) != null) prefs[K.TRACKER_MATCH] = TrackerStore.encodeMatches(next)
        }
    }

    /** What has already been reported, so nothing is uploaded twice. */
    suspend fun trackerDone(): Map<String, Long> =
        TrackerStore.parseDone(store.data.first()[K.TRACKER_DONE])

    suspend fun markTrackerDone(key: String) {
        write("TRACKER_DONE") { prefs ->
            val next = LinkedHashMap(TrackerStore.parseDone(prefs[K.TRACKER_DONE]))
            next[key] = System.currentTimeMillis()
            prefs[K.TRACKER_DONE] = TrackerStore.encodeDone(next)
        }
    }

    /** The last thing the trackers said, as a sentence, and when (0 = never). */
    fun trackerLastFlow(): Flow<String> =
        store.data.map { it[K.TRACKER_LAST] ?: "" }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun trackerLast(): String = trackerLastFlow().first()

    suspend fun trackerLastAt(): Long =
        store.data.first()[K.TRACKER_LAST_AT]?.toLongOrNull() ?: 0L

    suspend fun setTrackerLast(text: String) {
        write("TRACKER_LAST") { prefs ->
            prefs[K.TRACKER_LAST] = text
            prefs[K.TRACKER_LAST_AT] = System.currentTimeMillis().toString()
        }
    }

    // ---- Telegram (the TDLib client, see com.hikari.app.telegram.Td) ------

    fun telegramApiIdFlow(): Flow<Int> =
        store.data.map { (it[K.TELEGRAM_API_ID] ?: "").toIntOrNull() ?: 0 }
            .distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun telegramApiId(): Int = telegramApiIdFlow().first()

    suspend fun setTelegramApiId(value: Int) {
        write("TELEGRAM_API_ID") { it[K.TELEGRAM_API_ID] = value.toString() }
    }

    fun telegramApiHashFlow(): Flow<String> =
        store.data.map { it[K.TELEGRAM_API_HASH] ?: "" }.distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    suspend fun telegramApiHash(): String = telegramApiHashFlow().first()

    suspend fun setTelegramApiHash(value: String) {
        write("TELEGRAM_API_HASH") { it[K.TELEGRAM_API_HASH] = value.trim() }
    }


    /** The whole statistics document (JSON; blank = nothing logged yet). */
    fun watchStatsFlow(): Flow<String> =
        store.data.map { it[K.WATCH_STATS] ?: "" }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun watchStats(): String = watchStatsFlow().first()

    /** Adds [seconds] of playback to today's bucket and to the title's total. */
    suspend fun recordWatchSeconds(
        seconds: Long,
        key: String?,
        title: String?,
        posterUrl: String?,
        kind: String?,
    ) {
        if (seconds <= 0L) return
        val at = System.currentTimeMillis()
        write("WATCH_STATS") { prefs ->
            prefs[K.WATCH_STATS] = WatchStats.addSeconds(
                prefs[K.WATCH_STATS].orEmpty(), at, seconds, key, title, posterUrl, kind,
            )
        }
    }

    /** Counts one video as consumed. Called once per playback, on the first
     *  frame that actually plays. */
    suspend fun recordVideoStarted(key: String?, title: String?, posterUrl: String?, kind: String?) {
        val at = System.currentTimeMillis()
        write("WATCH_STATS") { prefs ->
            prefs[K.WATCH_STATS] = WatchStats.addVideo(
                prefs[K.WATCH_STATS].orEmpty(), at, key, title, posterUrl, kind,
            )
        }
    }

    /** Counts one manga chapter as consumed. */
    suspend fun recordChapterRead(key: String?, title: String?, posterUrl: String?) {
        val at = System.currentTimeMillis()
        write("WATCH_STATS") { prefs ->
            prefs[K.WATCH_STATS] = WatchStats.addChapter(
                prefs[K.WATCH_STATS].orEmpty(), at, key, title, posterUrl,
            )
        }
    }

    /** Forgets everything the Stats page counts (the page's own Reset). */
    suspend fun clearWatchStats() {
        write("WATCH_STATS") { it[K.WATCH_STATS] = "" }
    }


    /** How pages advance: paged left-to-right, paged right-to-left, or one long
     *  vertical strip (see [com.hikari.app.manga.MangaReadMode]). */
    fun mangaReadModeFlow(): Flow<String> =
        store.data.map { com.hikari.app.manga.MangaReadMode.normalize(it[K.MANGA_READ_MODE]) }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun setMangaReadMode(mode: String) {
        write("MANGA_READ_MODE") { it[K.MANGA_READ_MODE] = com.hikari.app.manga.MangaReadMode.normalize(mode) }
    }

    /** How a page is fitted to the screen (see
     *  [com.hikari.app.manga.MangaFit]). */
    fun mangaFitFlow(): Flow<String> =
        store.data.map { com.hikari.app.manga.MangaFit.normalize(it[K.MANGA_FIT]) }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun setMangaFit(fit: String) {
        write("MANGA_FIT") { it[K.MANGA_FIT] = com.hikari.app.manga.MangaFit.normalize(fit) }
    }

    /** The reader's backdrop colour, so a bright page or a dark room both read
     *  well. Stored as a word, not a colour — the reader maps it to a theme. */
    fun mangaReaderBgFlow(): Flow<String> =
        store.data.map { normalizeReaderBg(it[K.MANGA_READER_BG]) }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun setMangaReaderBg(bg: String) {
        write("MANGA_READER_BG") { it[K.MANGA_READER_BG] = normalizeReaderBg(bg) }
    }

    /** Keep the screen awake while reading (default ON — a reader is looked at,
     *  not tapped, and the OS timeout blanks the page mid-chapter otherwise). */
    fun mangaKeepAwakeFlow(): Flow<Boolean> =
        store.data.map { it[K.MANGA_KEEP_AWAKE] ?: true }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun setMangaKeepAwake(on: Boolean) {
        write("MANGA_KEEP_AWAKE") { it[K.MANGA_KEEP_AWAKE] = on }
    }

    /** Print "12 / 40" over the page while reading (default OFF: the chrome
     *  already shows it, and an always-on label over artwork is unwanted). */
    fun mangaShowPageNumberFlow(): Flow<Boolean> =
        store.data.map { it[K.MANGA_SHOW_PAGE_NUMBER] ?: false }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun setMangaShowPageNumber(on: Boolean) {
        write("MANGA_SHOW_PAGE_NUMBER") { it[K.MANGA_SHOW_PAGE_NUMBER] = on }
    }

    /** Colour-enhance the pages while reading (default OFF — see
     *  [K.MANGA_ENHANCE]). Applied as a GPU colour matrix, so flipping it is
     *  instant and costs nothing per page. */
    fun mangaEnhanceFlow(): Flow<Boolean> =
        store.data.map { it[K.MANGA_ENHANCE] ?: false }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun setMangaEnhance(on: Boolean) {
        write("MANGA_ENHANCE") { it[K.MANGA_ENHANCE] = on }
    }

    fun mangaReaderSettingsJsonFlow(): Flow<String?> =
        store.data.map { it[K.MANGA_READER_SETTINGS] }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun setMangaReaderSettingsJson(json: String) {
        write("MANGA_READER_SETTINGS") { it[K.MANGA_READER_SETTINGS] = json }
    }

    fun mangaSeriesModesFlow(): Flow<String?> =
        store.data.map { it[K.MANGA_SERIES_MODES] }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun setMangaSeriesModesJson(json: String) {
        write("MANGA_SERIES_MODES") { it[K.MANGA_SERIES_MODES] = json }
    }

    fun mangaTranslateSettingsFlow(): Flow<String?> =
        store.data.map { it[K.MANGA_TRANSLATE_SETTINGS] }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun setMangaTranslateSettingsJson(json: String) {
        write("MANGA_TRANSLATE_SETTINGS") { it[K.MANGA_TRANSLATE_SETTINGS] = json }
    }

    fun mangaTranslateSeriesFlow(): Flow<String?> =
        store.data.map { it[K.MANGA_TRANSLATE_SERIES] }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun setMangaTranslateSeriesJson(json: String) {
        write("MANGA_TRANSLATE_SERIES") { it[K.MANGA_TRANSLATE_SERIES] = json }
    }

    fun novelTranslateSettingsFlow(): Flow<String?> =
        store.data.map { it[K.NOVEL_TRANSLATE_SETTINGS] }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun setNovelTranslateSettingsJson(json: String) {
        write("NOVEL_TRANSLATE_SETTINGS") { it[K.NOVEL_TRANSLATE_SETTINGS] = json }
    }

    fun novelTranslateSeriesFlow(): Flow<String?> =
        store.data.map { it[K.NOVEL_TRANSLATE_SERIES] }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun setNovelTranslateSeriesJson(json: String) {
        write("NOVEL_TRANSLATE_SERIES") { it[K.NOVEL_TRANSLATE_SERIES] = json }
    }

    fun pinnedMangaEnginesFlow(): Flow<Set<String>> =
        store.data.map { it[K.PINNED_MANGA_ENGINES] ?: emptySet() }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun pinnedMangaEngines(): Set<String> = pinnedMangaEnginesFlow().first()

    suspend fun setPinnedMangaEngines(ids: Set<String>) {
        write("PINNED_MANGA_ENGINES") { it[K.PINNED_MANGA_ENGINES] = ids }
    }

    // ---- Pinned sources (Home → Choose an extension) ----

    fun pinnedProvidersFlow(): Flow<List<String>> =
        store.data.map { parseStringList(it[K.PINNED_PROVIDERS]) }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun pinnedProviders(): List<String> = pinnedProvidersFlow().first()

    suspend fun togglePinnedProvider(id: String) {
        if (id.isBlank()) return
        write("PINNED_PROVIDERS") { prefs ->
            val current = parseStringList(prefs[K.PINNED_PROVIDERS])
            val next = if (current.contains(id)) current - id else listOf(id) + current
            prefs[K.PINNED_PROVIDERS] = encodeStringList(next)
        }
    }

    // ---- Provider folders (Home → Choose an extension → Save as folder) ----

    fun providerFoldersFlow(): Flow<List<ProviderFolder>> =
        store.data.map { parseProviderFolders(it[K.PROVIDER_FOLDERS]) }
            .distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun providerFolders(): List<ProviderFolder> = providerFoldersFlow().first()

    suspend fun saveProviderFolder(name: String, ids: List<String>) {
        val clean = name.trim().replace(FOLDER_SEP, " ").take(48)
        val members = ids.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (clean.isBlank() || members.isEmpty()) return
        write("PROVIDER_FOLDERS") { prefs ->
            val current = parseProviderFolders(prefs[K.PROVIDER_FOLDERS])
            val next = current.filterNot { it.name.equals(clean, ignoreCase = true) } +
                ProviderFolder(clean, members)
            prefs[K.PROVIDER_FOLDERS] = encodeProviderFolders(next)
        }
    }

    suspend fun removeProviderFolder(name: String) {
        write("PROVIDER_FOLDERS") { prefs ->
            val current = parseProviderFolders(prefs[K.PROVIDER_FOLDERS])
            prefs[K.PROVIDER_FOLDERS] = encodeProviderFolders(current.filterNot { it.name == name })
        }
    }

    // ---- Home feed catalog mixer (Manage Home Screen Feed) ----

    fun disabledCatalogsFlow(): Flow<Map<String, Set<String>>> =
        store.data.map { parseStringSetMap(it[K.DISABLED_CATALOGS]) }
            .distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun setCatalogEnabled(providerId: String, catalog: String, enabled: Boolean) {
        if (providerId.isBlank() || catalog.isBlank()) return
        write("DISABLED_CATALOGS") { prefs ->
            val current = parseStringSetMap(prefs[K.DISABLED_CATALOGS]).toMutableMap()
            val set = current[providerId]?.toMutableSet() ?: mutableSetOf()
            if (enabled) set.remove(catalog) else set.add(catalog)
            if (set.isEmpty()) current.remove(providerId) else current[providerId] = set
            prefs[K.DISABLED_CATALOGS] = encodeStringSetMap(current)
        }
    }

    fun catalogOrderFlow(): Flow<Map<String, List<String>>> =
        store.data.map { parseStringListMap(it[K.CATALOG_ORDER]) }
            .distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun moveCatalog(providerId: String, from: Int, to: Int, current: List<String>) {
        if (providerId.isBlank() || from == to) return
        if (from !in current.indices || to !in current.indices) return
        val next = current.toMutableList()
        val moved = next.removeAt(from)
        next.add(to, moved)
        write("CATALOG_ORDER") { prefs ->
            val map = parseStringListMap(prefs[K.CATALOG_ORDER]).toMutableMap()
            map[providerId] = next.toList()
            prefs[K.CATALOG_ORDER] = encodeStringListMap(map)
        }
    }

    suspend fun resetCatalogOrder(providerId: String) {
        if (providerId.isBlank()) return
        write("CATALOG_ORDER") { prefs ->
            val map = parseStringListMap(prefs[K.CATALOG_ORDER]).toMutableMap()
            map.remove(providerId)
            prefs[K.CATALOG_ORDER] = encodeStringListMap(map)
        }
    }

    private fun encodeStringSetMap(map: Map<String, Set<String>>): String {
        val obj = JSONObject()
        for ((k, v) in map) {
            val arr = JSONArray()
            for (s in v) arr.put(s)
            obj.put(k, arr)
        }
        return obj.toString()
    }

    private fun parseStringSetMap(s: String?): Map<String, Set<String>> {
        if (s.isNullOrBlank()) return emptyMap()
        return try {
            val obj = JSONObject(s)
            val out = HashMap<String, Set<String>>()
            for (k in obj.keys()) {
                val arr = obj.optJSONArray(k) ?: continue
                out[k] = (0 until arr.length()).mapNotNull { arr.optString(it).ifBlank { null } }.toSet()
            }
            out
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun encodeStringListMap(map: Map<String, List<String>>): String {
        val obj = JSONObject()
        for ((k, v) in map) {
            val arr = JSONArray()
            for (s in v) arr.put(s)
            obj.put(k, arr)
        }
        return obj.toString()
    }

    private fun parseStringListMap(s: String?): Map<String, List<String>> {
        if (s.isNullOrBlank()) return emptyMap()
        return try {
            val obj = JSONObject(s)
            val out = HashMap<String, List<String>>()
            for (k in obj.keys()) {
                val arr = obj.optJSONArray(k) ?: continue
                out[k] = (0 until arr.length()).mapNotNull { arr.optString(it).ifBlank { null } }
            }
            out
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun encodeProviderFolders(list: List<ProviderFolder>): String {
        val arr = JSONArray()
        for (f in list) arr.put((listOf(f.name) + f.ids).joinToString(FOLDER_SEP))
        return arr.toString()
    }

    private fun parseProviderFolders(s: String?): List<ProviderFolder> {
        val out = ArrayList<ProviderFolder>()
        for (raw in parseStringList(s)) {
            val parts = raw.split(FOLDER_SEP).filter { it.isNotBlank() }
            if (parts.size < 2) continue
            out += ProviderFolder(parts.first(), parts.drop(1))
        }
        return out
    }

    // ---- Adult content (Settings → Content) ----

    fun nsfwEnabledFlow(): Flow<Boolean> =
        store.data.map { it[K.NSFW_ENABLED] ?: true }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun nsfwEnabled(): Boolean = nsfwEnabledFlow().first()

    suspend fun setNsfwEnabled(on: Boolean) {
        write("NSFW_ENABLED") { it[K.NSFW_ENABLED] = on }
    }

    /** One of the reader's three backdrops; anything unknown means black. */
    fun normalizeReaderBg(raw: String?): String = when (raw?.lowercase()) {
        "grey", "gray" -> "grey"
        "white" -> "white"
        else -> "black"
    }


    /** Whether a section's pill is drawn in the My Stuff strip. */
    fun myStuffSectionFlow(section: String): Flow<Boolean> =
        store.data.map { it[keyForSection(section)] ?: true }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun setMyStuffSection(section: String, shown: Boolean) {
        write("MYSTUFF_" + section.uppercase()) { it[keyForSection(section)] = shown }
    }

    private fun keyForSection(section: String): Preferences.Key<Boolean> = when (section) {
        MyStuffSection.HISTORY -> K.MYSTUFF_HISTORY
        MyStuffSection.DOWNLOADS -> K.MYSTUFF_DOWNLOADS
        else -> K.MYSTUFF_LIBRARY
    }

    // ---- Animated covers on tiles ----

    fun gifAnimFlow(): Flow<Boolean> = store.data.map { it[K.GIF_ANIM] ?: true }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun gifAnim(): Boolean = gifAnimFlow().first()

    suspend fun setGifAnim(on: Boolean) {
        write("GIF_ANIM") { it[K.GIF_ANIM] = on }
    }

    fun hideHelpFlow(): Flow<Boolean> =
        store.data.map { it[K.HIDE_HELP] ?: false }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun hideHelp(): Boolean = hideHelpFlow().first()

    suspend fun setHideHelp(on: Boolean) {
        write("HIDE_HELP") { it[K.HIDE_HELP] = on }
    }

    /** Which profile the store currently holds — see [K.PROFILE_TAG] and [Profiles]. */
    suspend fun profileTag(): String = store.data.first()[K.PROFILE_TAG] ?: ""

    suspend fun setProfileTag(id: String) {
        write("PROFILE_TAG") { it[K.PROFILE_TAG] = id }
    }

    /** Last silent CloudStream plugin auto-update pass, epoch millis (0 = never). */
    suspend fun cs3AutoUpdateAt(): Long = store.data.first()[K.CS3_AUTO_UPDATE_AT] ?: 0L

    suspend fun setCs3AutoUpdateAt(at: Long) {
        write("CS3_AUTO_UPDATE_AT") { it[K.CS3_AUTO_UPDATE_AT] = at }
    }

    /** Last silent Nuvio scraper auto-update pass, epoch millis (0 = never). */
    suspend fun nuvioAutoUpdateAt(): Long = store.data.first()[K.NUVIO_AUTO_UPDATE_AT] ?: 0L

    suspend fun setNuvioAutoUpdateAt(at: Long) {
        write("NUVIO_AUTO_UPDATE_AT") { it[K.NUVIO_AUTO_UPDATE_AT] = at }
    }

    // ---- Per-extension auto-translate (WebView pages → English) ----

    /** Provider ids whose web pages are always translated to English. */
    fun translateProvidersFlow(): Flow<Set<String>> =
        store.data.map { parseStringList(it[K.TRANSLATE_PROVIDERS]).toSet() }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun translateProviders(): Set<String> = translateProvidersFlow().first()

    suspend fun setTranslateProvider(id: String, enabled: Boolean) {
        val cur = translateProviders()
        val next = if (enabled) cur + id else cur - id
        write("TRANSLATE_PROVIDERS") { it[K.TRANSLATE_PROVIDERS] = encodeStringList(next.toList()) }
    }

    /** Persisted original→English translation pairs (title cache). */
    suspend fun translateCache(): List<Pair<String, String>> =
        store.data.map { parsePairs(it[K.TRANSLATE_CACHE]) }.distinctUntilChanged().flowOn(Dispatchers.Default).first()

    suspend fun setTranslateCache(list: List<Pair<String, String>>) {
        write("TRANSLATE_CACHE") { it[K.TRANSLATE_CACHE] = encodePairs(list) }
    }

    private fun encodePairs(list: List<Pair<String, String>>): String {
        val arr = JSONArray()
        for ((k, v) in list) arr.put(JSONArray().put(k).put(v))
        return arr.toString()
    }

    private fun parsePairs(s: String?): List<Pair<String, String>> {
        if (s.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).mapNotNull { i ->
                val pair = arr.optJSONArray(i) ?: return@mapNotNull null
                val k = pair.optString(0)
                val v = pair.optString(1)
                if (k.isBlank() || v.isBlank()) null else k to v
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun themeFlow(): Flow<String> =
        store.data.map { it[K.THEME] ?: DEFAULT_THEME }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun theme(): String = themeFlow().first()

    suspend fun setTheme(key: String) {
        write("THEME") { it[K.THEME] = key }
        syncAccents()
    }

    // ---- Accent colours (app + player) ----

    /** The app UI's accent colour (Settings → Appearance & Theme → Accent colour). Defaults to
     *  the amber/gold the app has always used, so an existing install looks
     *  identical until the user picks something else. */
    fun appAccentFlow(): Flow<String> =
        store.data.map { it[K.APP_ACCENT] ?: HikariAccent.DEFAULT_APP.key }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun appAccent(): String = appAccentFlow().first()

    suspend fun setAppAccent(key: String) {
        write("APP_ACCENT") { it[K.APP_ACCENT] = key }
        syncAccents()
    }

    /** The player's own accent (used only while the app and the player are NOT
     *  linked). Defaults to the cyan→violet glow the player has always had. */
    fun playerAccentFlow(): Flow<String> =
        store.data.map { it[K.PLAYER_ACCENT] ?: HikariAccent.DEFAULT_PLAYER.key }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun playerAccent(): String = playerAccentFlow().first()

    suspend fun setPlayerAccent(key: String) {
        write("PLAYER_ACCENT") { it[K.PLAYER_ACCENT] = key }
        syncAccents()
    }

    /** "Match app & player theme": while ON the player follows the app accent
     *  and picking a colour in either place recolours both. */
    fun themeLinkedFlow(): Flow<Boolean> =
        store.data.map { it[K.THEME_LINKED] ?: false }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun themeLinked(): Boolean = themeLinkedFlow().first()

    suspend fun setThemeLinked(linked: Boolean) {
        write("THEME_LINKED") { it[K.THEME_LINKED] = linked }
        syncAccents()
    }

    /** The accent the player should actually use right now (app accent while
     *  linked, otherwise its own). */
    suspend fun effectivePlayerAccent(): String =
        if (themeLinked()) appAccent() else playerAccent()

    /** Mirror the accent preferences into [AccentStore] so the View-based
     *  player can read them synchronously during Activity creation. */
    private suspend fun syncAccents() {
        runCatching {
            AccentStore.sync(ctx, appAccent(), playerAccent(), themeLinked(), theme())
        }
    }

    // ---- Player overlay layout & video enhance ----

    /** The player's control layout, as [com.hikari.app.player.PlayerControlsConfig]
     *  JSON (control key → slot key). Blank means "the default layout". */
    fun playerControlsFlow(): Flow<String> =
        store.data.map { it[K.PLAYER_CONTROLS] ?: "" }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun playerControls(): String = playerControlsFlow().first()

    suspend fun setPlayerControls(json: String) {
        write("PLAYER_CONTROLS") { it[K.PLAYER_CONTROLS] = json }
    }

    /** The player's custom button look, as [com.hikari.app.player.PlayerUiStyle]
     *  JSON (Settings -> Customize -> Player UI style). Blank = untouched. */
    fun playerUiStyleFlow(): Flow<String> =
        store.data.map { it[K.PLAYER_UI_STYLE] ?: "" }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun playerUiStyle(): String = playerUiStyleFlow().first()

    suspend fun setPlayerUiStyle(json: String) {
        write("PLAYER_UI_STYLE") { it[K.PLAYER_UI_STYLE] = json }
    }

    fun playerSwipesFlow(): Flow<Boolean> =
        store.data.map { it[K.PLAYER_SWIPES] ?: true }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun playerSwipes(): Boolean = playerSwipesFlow().first()

    suspend fun setPlayerSwipes(on: Boolean) {
        write("PLAYER_SWIPES") { it[K.PLAYER_SWIPES] = on }
    }

    fun playerSlideSeekFlow(): Flow<Boolean> =
        store.data.map { it[K.PLAYER_SLIDE_SEEK] ?: true }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun playerSlideSeek(): Boolean = playerSlideSeekFlow().first()

    suspend fun setPlayerSlideSeek(on: Boolean) {
        write("PLAYER_SLIDE_SEEK") { it[K.PLAYER_SLIDE_SEEK] = on }
    }

    fun playerPinchZoomFlow(): Flow<Boolean> =
        store.data.map { it[K.PLAYER_PINCH_ZOOM] ?: false }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun playerPinchZoom(): Boolean = playerPinchZoomFlow().first()

    suspend fun setPlayerPinchZoom(on: Boolean) {
        write("PLAYER_PINCH_ZOOM") { it[K.PLAYER_PINCH_ZOOM] = on }
    }

    fun playerDecoderModeFlow(): Flow<String> =
        store.data.map { DecoderModes.normalize(it[K.PLAYER_DECODER_MODE]) }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun playerDecoderMode(): String = playerDecoderModeFlow().first()

    suspend fun setPlayerDecoderMode(mode: String) {
        val clean = DecoderModes.normalize(mode)
        write("PLAYER_DECODER_MODE") { it[K.PLAYER_DECODER_MODE] = clean }
    }

    object DecoderModes {
        /** The only decoder modes the player understands; anything else is Best. */
        fun normalize(mode: String?): String = when (mode) {
            "hw", "sw" -> mode
            else -> "auto"
        }

        fun label(mode: String): String = when (normalize(mode)) {
            "hw" -> "Hardware only"
            "sw" -> "Software only"
            else -> "Best (auto)"
        }
    }

    /** Playback speed used by the press-and-hold fast-play gesture (2.00x–4.00x). */
    fun playerHoldSpeedFlow(): Flow<Float> =
        store.data.map { ((it[K.PLAYER_HOLD_SPEED] ?: 200).coerceIn(200, 400) / 100f) }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    suspend fun playerHoldSpeed(): Float = playerHoldSpeedFlow().first()

    suspend fun setPlayerHoldSpeed(speed: Float) {
        val stored = (speed.coerceIn(2f, 4f) * 100f).toInt()
        write("PLAYER_HOLD_SPEED") { it[K.PLAYER_HOLD_SPEED] = stored }
    }

    /** Whether the small speed badge shows while press-and-hold fast-play is
     *  active (ON by default; Settings → Player → Press-and-hold speed). */
    fun playerShowHoldSpeedFlow(): Flow<Boolean> =
        store.data.map { it[K.PLAYER_SHOW_HOLD_SPEED] ?: true }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun playerShowHoldSpeed(): Boolean = playerShowHoldSpeedFlow().first()

    suspend fun setPlayerShowHoldSpeed(on: Boolean) {
        write("PLAYER_SHOW_HOLD_SPEED") { it[K.PLAYER_SHOW_HOLD_SPEED] = on }
    }

    /** Whether the playback pills should show icons only. */
    fun playerIconOnlyFlow(): Flow<Boolean> =
        store.data.map { it[K.PLAYER_ICON_ONLY] ?: true }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun playerIconOnly(): Boolean = playerIconOnlyFlow().first()

    suspend fun setPlayerIconOnly(on: Boolean) {
        write("PLAYER_ICON_ONLY") { it[K.PLAYER_ICON_ONLY] = on }
    }

    /** Whether the player's options menu offers "Play in external player". */
    fun playerExternalButtonFlow(): Flow<Boolean> =
        store.data.map { it[K.PLAYER_EXTERNAL_BUTTON] ?: true }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun playerExternalButton(): Boolean = playerExternalButtonFlow().first()

    suspend fun setPlayerExternalButton(on: Boolean) {
        write("PLAYER_EXTERNAL_BUTTON") { it[K.PLAYER_EXTERNAL_BUTTON] = on }
    }

    /** Whether the player's Audio sheet offers cross-server audio (video from
     *  one server, audio from another). Toggled inside the player itself. */
    fun dualAudioFlow(): Flow<Boolean> =
        store.data.map { it[K.PLAYER_DUAL_AUDIO] ?: false }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun dualAudio(): Boolean = dualAudioFlow().first()

    suspend fun setDualAudio(on: Boolean) {
        write("PLAYER_DUAL_AUDIO") { it[K.PLAYER_DUAL_AUDIO] = on }
    }

    /** Whether the player advances to the next episode by itself when one ends
     *  (the Autoplay pill toggles it). */
    fun autoplayNextFlow(): Flow<Boolean> =
        store.data.map { it[K.PLAYER_AUTOPLAY] ?: true }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun autoplayNext(): Boolean = autoplayNextFlow().first()

    suspend fun setAutoplayNext(on: Boolean) {
        write("PLAYER_AUTOPLAY") { it[K.PLAYER_AUTOPLAY] = on }
    }

    /** Size of player icons when the text labels are hidden (20–44dp). */
    fun playerIconSizeFlow(): Flow<Int> =
        store.data.map { (it[K.PLAYER_ICON_SIZE] ?: 24).coerceIn(20, 44) }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    suspend fun playerIconSize(): Int = playerIconSizeFlow().first()

    suspend fun setPlayerIconSize(size: Int) {
        write("PLAYER_ICON_SIZE") { it[K.PLAYER_ICON_SIZE] = size.coerceIn(20, 44) }
    }

    /** Size of the player TOP-bar icons (back, favourite, download, PiP,
     *  options, lock — 20–44dp). The bottom pills have their own size above. */
    fun playerTopIconSizeFlow(): Flow<Int> =
        store.data.map { (it[K.PLAYER_TOP_ICON_SIZE] ?: 24).coerceIn(20, 44) }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    suspend fun playerTopIconSize(): Int = playerTopIconSizeFlow().first()

    suspend fun setPlayerTopIconSize(size: Int) {
        write("PLAYER_TOP_ICON_SIZE") { it[K.PLAYER_TOP_ICON_SIZE] = size.coerceIn(20, 44) }
    }

    /** Whether player panels use the large left-side TV layout. */
    fun playerTvPanelsFlow(): Flow<Boolean> =
        store.data.map { it[K.PLAYER_TV_PANELS] ?: true }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun playerTvPanels(): Boolean = playerTvPanelsFlow().first()

    suspend fun setPlayerTvPanels(on: Boolean) {
        write("PLAYER_TV_PANELS") { it[K.PLAYER_TV_PANELS] = on }
    }

    /** Default video quality in pixels of height; 0 = Auto (adaptive). */
    fun playerDefaultQualityFlow(): Flow<Int> =
        store.data.map { (it[K.PLAYER_DEFAULT_QUALITY] ?: 0).coerceIn(0, 4320) }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    suspend fun playerDefaultQuality(): Int = playerDefaultQualityFlow().first()

    suspend fun setPlayerDefaultQuality(height: Int) {
        write("PLAYER_DEFAULT_QUALITY") { it[K.PLAYER_DEFAULT_QUALITY] = height.coerceIn(0, 4320) }
    }

    /** Default audio language code (blank = the stream's own default). */
    fun playerDefaultAudioFlow(): Flow<String> =
        store.data.map { it[K.PLAYER_DEFAULT_AUDIO] ?: "" }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun playerDefaultAudio(): String = playerDefaultAudioFlow().first()

    suspend fun setPlayerDefaultAudio(code: String) {
        write("PLAYER_DEFAULT_AUDIO") { it[K.PLAYER_DEFAULT_AUDIO] = code }
    }

    /** The audio language to try when the default one is not found. */
    fun playerDefaultAudio2Flow(): Flow<String> =
        store.data.map { it[K.PLAYER_DEFAULT_AUDIO2] ?: "" }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun playerDefaultAudio2(): String = playerDefaultAudio2Flow().first()

    suspend fun setPlayerDefaultAudio2(code: String) {
        write("PLAYER_DEFAULT_AUDIO2") { it[K.PLAYER_DEFAULT_AUDIO2] = code }
    }

    /** Size of the TV detail screen's episode rows/cards (80–160%). */
    fun tvEpisodeSizeFlow(): Flow<Int> =
        store.data.map { (it[K.TV_EPISODE_SIZE] ?: 100).coerceIn(80, 160) }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    suspend fun tvEpisodeSize(): Int = tvEpisodeSizeFlow().first()

    suspend fun setTvEpisodeSize(size: Int) {
        write("TV_EPISODE_SIZE") { it[K.TV_EPISODE_SIZE] = size.coerceIn(80, 160) }
    }

    /** TV text size as a percentage of the shipped sizes (50–150, 100 is the
     *  shipped small/medium look; Settings → TV & Remote). */
    fun tvTextScaleFlow(): Flow<Int> =
        store.data.map { (it[K.TV_TEXT_SCALE] ?: 100).coerceIn(50, 150) }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    suspend fun tvTextScale(): Int = tvTextScaleFlow().first()

    suspend fun setTvTextScale(scale: Int) {
        write("TV_TEXT_SCALE") { it[K.TV_TEXT_SCALE] = scale.coerceIn(50, 150) }
    }

    /** Default episode layout for every title (-1 = Auto). */
    fun episodeViewModeFlow(): Flow<Int> =
        store.data.map { (it[K.EPISODE_VIEW_MODE] ?: -1).coerceIn(-1, 2) }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    suspend fun episodeViewMode(): Int = episodeViewModeFlow().first()

    suspend fun setEpisodeViewMode(mode: Int) {
        write("EPISODE_VIEW_MODE") { it[K.EPISODE_VIEW_MODE] = mode.coerceIn(-1, 2) }
    }

    /** Whether episode rows print the description under the title (ON by
     *  default — an episode row without its description is just a number). */
    fun episodeDescriptionFlow(): Flow<Boolean> =
        store.data.map { it[K.EPISODE_DESCRIPTION] ?: true }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun episodeDescription(): Boolean = episodeDescriptionFlow().first()

    suspend fun setEpisodeDescription(on: Boolean) {
        write("EPISODE_DESCRIPTION") { it[K.EPISODE_DESCRIPTION] = on }
    }

    /** Whether episodes draw as frosted-glass cards (ON by default). One
     *  switch for all three layouts — list, poster grid and cinematic big
     *  cards each have a glass variant. */
    fun episodeGlassFlow(): Flow<Boolean> =
        store.data.map { it[K.EPISODE_GLASS] ?: true }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun episodeGlass(): Boolean = episodeGlassFlow().first()

    suspend fun setEpisodeGlass(on: Boolean) {
        write("EPISODE_GLASS") { it[K.EPISODE_GLASS] = on }
    }

    fun volumeBoostFlow(): Flow<Boolean> =
        store.data.map { it[K.VOLUME_BOOST] ?: false }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun volumeBoost(): Boolean = volumeBoostFlow().first()

    suspend fun setVolumeBoost(on: Boolean) {
        write("VOLUME_BOOST") { it[K.VOLUME_BOOST] = on }
    }

    fun subsDefaultOnFlow(): Flow<Boolean> =
        store.data.map { it[K.SUBTITLES_DEFAULT_ON] ?: true }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun subsDefaultOn(): Boolean = subsDefaultOnFlow().first()

    suspend fun setSubsDefaultOn(on: Boolean) {
        write("SUBTITLES_DEFAULT_ON") { it[K.SUBTITLES_DEFAULT_ON] = on }
    }

    /** Video enhance preset key (see [com.hikari.app.player.EnhancePreset]). */
    fun enhancePresetFlow(): Flow<String> =
        store.data.map { it[K.PLAYER_ENHANCE] ?: EnhancePreset.DEFAULT.key }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun enhancePreset(): String = enhancePresetFlow().first()

    suspend fun setEnhancePreset(key: String) {
        write("PLAYER_ENHANCE") { it[K.PLAYER_ENHANCE] = key }
    }

    /** The user's own enhance presets (see
     *  [com.hikari.app.player.CustomEnhancePreset]). */
    fun customEnhanceFlow(): Flow<List<com.hikari.app.player.CustomEnhancePreset>> =
        store.data.map { prefs ->
            com.hikari.app.player.CustomEnhancePreset.listFromJson(prefs[K.PLAYER_ENHANCE_CUSTOMS])
        }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun customEnhanceList(): List<com.hikari.app.player.CustomEnhancePreset> =
        customEnhanceFlow().first()

    suspend fun setCustomEnhanceList(list: List<com.hikari.app.player.CustomEnhancePreset>) {
        val raw = com.hikari.app.player.CustomEnhancePreset.listToJson(list)
        write("PLAYER_ENHANCE_CUSTOMS") { it[K.PLAYER_ENHANCE_CUSTOMS] = raw }
    }

    /** True once the user has picked a video-enhance preset themselves. The
     *  television layout only forces its own preset while this is false — the
     *  same rule as [tvPerfChosen]. */
    fun enhanceChosenFlow(): Flow<Boolean> =
        store.data.map { it[K.PLAYER_ENHANCE_CHOSEN] ?: false }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun enhanceChosen(): Boolean = enhanceChosenFlow().first()

    suspend fun setEnhanceChosen(chosen: Boolean) {
        write("PLAYER_ENHANCE_CHOSEN") { it[K.PLAYER_ENHANCE_CHOSEN] = chosen }
    }

    fun enhanceUnsupportedFlow(): Flow<Boolean> =
        store.data.map { it[K.PLAYER_ENHANCE_UNSUPPORTED] ?: false }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun enhanceUnsupported(): Boolean = enhanceUnsupportedFlow().first()

    suspend fun setEnhanceUnsupported(value: Boolean) {
        write("PLAYER_ENHANCE_UNSUPPORTED") { it[K.PLAYER_ENHANCE_UNSUPPORTED] = value }
    }

    // ---- In-app UI scale ----

    /** When ON the app ignores the phone's Font size AND Display size settings
     *  and scales its interface with [uiScaleFlow] instead — so it looks the
     *  same on every phone. OFF (default) follows the system settings. */
    fun uiScaleEnabledFlow(): Flow<Boolean> =
        store.data.map { it[K.UI_SCALE_ENABLED] ?: false }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun uiScaleEnabled(): Boolean = uiScaleEnabledFlow().first()

    suspend fun setUiScaleEnabled(enabled: Boolean) {
        write("UI_SCALE_ENABLED") { it[K.UI_SCALE_ENABLED] = enabled }
        // Mirror into the synchronous cache so View-based screens (player,
        // WebView) and the next cold start pick the change up immediately.
        runCatching { UiScale.sync(ctx, enabled, uiScale()) }
    }

    /** The in-app scale (0.7f–1.3f) used while [uiScaleEnabledFlow] is on. */
    fun uiScaleFlow(): Flow<Float> =
        store.data.map { (it[K.UI_SCALE_PERCENT] ?: 100).coerceIn(70, 130) / 100f }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun uiScale(): Float = uiScaleFlow().first()

    suspend fun setUiScale(percent: Int) {
        write("UI_SCALE_PERCENT") { it[K.UI_SCALE_PERCENT] = percent.coerceIn(70, 130) }
        runCatching { UiScale.sync(ctx, uiScaleEnabled(), uiScale()) }
    }

    // ---- Ad blocking (WebView only) ----

    fun adEnabledFlow(): Flow<Boolean> =
        store.data.map { it[K.AD_ENABLED] ?: true }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun adEnabled(): Boolean = adEnabledFlow().first()

    suspend fun setAdEnabled(enabled: Boolean) {
        write("AD_ENABLED") { it[K.AD_ENABLED] = enabled }
    }

    fun adListsFlow(): Flow<List<AdBlocker.HostList>> =
        store.data.map { parseHostLists(it[K.AD_LISTS]) }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun adLists(): List<AdBlocker.HostList> = adListsFlow().first()

    suspend fun setAdLists(list: List<AdBlocker.HostList>) {
        write("AD_LISTS") { it[K.AD_LISTS] = encodeHostLists(list) }
    }

    fun adBlockFlow(): Flow<List<String>> =
        store.data.map { parseStringList(it[K.AD_BLOCK]) }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun adBlock(): List<String> = adBlockFlow().first()

    suspend fun setAdBlock(list: List<String>) {
        write("AD_BLOCK") { it[K.AD_BLOCK] = encodeStringList(list) }
    }

    fun adWhiteFlow(): Flow<List<String>> =
        store.data.map { parseStringList(it[K.AD_WHITE]) }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun adWhite(): List<String> = adWhiteFlow().first()

    suspend fun setAdWhite(list: List<String>) {
        write("AD_WHITE") { it[K.AD_WHITE] = encodeStringList(list) }
    }

    // ---- WebView safety (redirect + popup protection; default ON) ----

    fun webviewRedirectFlow(): Flow<Boolean> =
        store.data.map { it[K.WEBVIEW_REDIRECT] ?: true }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun webviewRedirect(): Boolean = webviewRedirectFlow().first()

    suspend fun setWebviewRedirect(enabled: Boolean) {
        write("WEBVIEW_REDIRECT") { it[K.WEBVIEW_REDIRECT] = enabled }
    }

    fun webviewPopupFlow(): Flow<Boolean> =
        store.data.map { it[K.WEBVIEW_POPUP] ?: true }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun webviewPopup(): Boolean = webviewPopupFlow().first()

    suspend fun setWebviewPopup(enabled: Boolean) {
        write("WEBVIEW_POPUP") { it[K.WEBVIEW_POPUP] = enabled }
    }

    fun cfAutoSolveFlow(): Flow<Boolean> =
        store.data.map { it[K.CF_AUTO_SOLVE] ?: false }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun cfAutoSolve(): Boolean = cfAutoSolveFlow().first()

    suspend fun setCfAutoSolve(enabled: Boolean) {
        write("CF_AUTO_SOLVE") { it[K.CF_AUTO_SOLVE] = enabled }
    }

    fun extensionVerifyWebviewFlow(): Flow<Boolean> =
        store.data.map { it[K.EXT_VERIFY_WEBVIEW] ?: false }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun extensionVerifyWebview(): Boolean = extensionVerifyWebviewFlow().first()

    suspend fun setExtensionVerifyWebview(allowed: Boolean) {
        write("EXT_VERIFY_WEBVIEW") { it[K.EXT_VERIFY_WEBVIEW] = allowed }
        // Apply immediately (not just on the next launch) so the choice takes
        // effect for the very next source search.
        runCatching { ExtensionVerifyGuard.apply(ctx, allowed) }
    }

    /** Hosts the user allowed redirects to (blocked-elsewhere hosts allowed
     *  through). */
    fun webviewRedirectAllowFlow(): Flow<List<String>> =
        store.data.map { parseStringList(it[K.WEBVIEW_REDIRECT_ALLOW]) }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun webviewRedirectAllow(): List<String> =
        webviewRedirectAllowFlow().first().also { RedirectAllow.set(it) }

    suspend fun setWebviewRedirectAllow(list: List<String>) {
        RedirectAllow.set(list)
        write("WEBVIEW_REDIRECT_ALLOW") { it[K.WEBVIEW_REDIRECT_ALLOW] = encodeStringList(list) }
    }

    /** Popup hosts the user chose "Always allow" for (WebView popup dialog). */
    fun webviewPopupAllowFlow(): Flow<List<String>> =
        store.data.map { parseStringList(it[K.WEBVIEW_POPUP_ALLOW]) }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun webviewPopupAllow(): List<String> = webviewPopupAllowFlow().first()

    suspend fun setWebviewPopupAllow(list: List<String>) {
        write("WEBVIEW_POPUP_ALLOW") { it[K.WEBVIEW_POPUP_ALLOW] = encodeStringList(list) }
    }

    /** Popup hosts the user chose "Block always" for (never ask again). */
    fun webviewPopupBlockFlow(): Flow<List<String>> =
        store.data.map { parseStringList(it[K.WEBVIEW_POPUP_BLOCK]) }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun webviewPopupBlock(): List<String> = webviewPopupBlockFlow().first()

    suspend fun setWebviewPopupBlock(list: List<String>) {
        write("WEBVIEW_POPUP_BLOCK") { it[K.WEBVIEW_POPUP_BLOCK] = encodeStringList(list) }
    }

    // ---- WebView user agent (stock Android default vs custom) ----

    fun webviewUseDefaultUaFlow(): Flow<Boolean> =
        store.data.map { it[K.WEBVIEW_DEFAULT_UA] ?: true }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun webviewUseDefaultUa(): Boolean = webviewUseDefaultUaFlow().first()

    fun webviewCustomUaFlow(): Flow<String> =
        store.data.map { it[K.WEBVIEW_CUSTOM_UA] ?: "" }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun webviewCustomUa(): String = webviewCustomUaFlow().first()

    suspend fun setWebViewUa(useDefault: Boolean, customUa: String) {
        write("WEBVIEW_DEFAULT_UA") {
            it[K.WEBVIEW_DEFAULT_UA] = useDefault
            it[K.WEBVIEW_CUSTOM_UA] = customUa
        }
    }

    // ---- App language ----

    /** The app language as a BCP-47 tag ("" = follow the device). Applied to
     *  the whole app — and therefore to the player's overlay words, which are
     *  resource strings — via AppCompatDelegate.setApplicationLocales (see
     *  com.hikari.app.ui.LanguageManager). */
    fun languageFlow(): Flow<String> =
        store.data.map { it[K.LANGUAGE] ?: "" }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun language(): String = languageFlow().first()

    suspend fun setLanguage(tag: String) {
        write("LANGUAGE") { it[K.LANGUAGE] = tag }
        runCatching { com.hikari.app.ui.LanguageManager.rememberStored(ctx, tag) }
    }


    private fun encodeHostLists(list: List<AdBlocker.HostList>): String {
        val arr = JSONArray()
        for (l in list) {
            arr.put(JSONObject().put("name", l.name).put("url", l.url))
        }
        return arr.toString()
    }

    private fun parseHostLists(s: String?): List<AdBlocker.HostList> {
        if (s.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val url = o.optString("url")
                if (url.isBlank()) null
                else AdBlocker.HostList(o.optString("name").ifBlank { url }, url)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun encodeStringList(list: List<String>): String {
        val arr = JSONArray()
        for (s in list) arr.put(s)
        return arr.toString()
    }

    private fun parseStringList(s: String?): List<String> {
        if (s.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).mapNotNull { arr.optString(it).ifBlank { null } }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ---- Library categories (encode/parse) ----

    private fun encodeCategories(list: List<LibraryCategory>): String {
        val arr = JSONArray()
        for (c in list) {
            arr.put(
                JSONObject()
                    .put("id", c.id)
                    .put("name", c.name)
                    .put("built", c.builtIn)
            )
        }
        return arr.toString()
    }

    /** A missing value means "never configured" and yields the four defaults;
     *  an explicitly empty array means the user deleted them all. */
    private fun parseCategories(s: String?): List<LibraryCategory> {
        if (s == null) return LibraryCategory.DEFAULTS
        if (s.isBlank()) return LibraryCategory.DEFAULTS
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id")
                val name = o.optString("name")
                if (id.isBlank() || name.isBlank()) null
                else LibraryCategory(id, name, o.optBoolean("built", false))
            }
        } catch (e: Exception) {
            LibraryCategory.DEFAULTS
        }
    }

    private fun encodeCategoryMap(map: Map<String, Set<String>>): String {
        val obj = JSONObject()
        for ((k, v) in map) {
            if (v.isEmpty()) continue
            val arr = JSONArray()
            for (c in v) arr.put(c)
            obj.put(k, arr)
        }
        return obj.toString()
    }

    private fun parseCategoryMap(s: String?): Map<String, Set<String>> {
        if (s.isNullOrBlank()) return emptyMap()
        return try {
            val obj = JSONObject(s)
            val out = LinkedHashMap<String, Set<String>>()
            obj.keys().forEach { k ->
                val arr = obj.optJSONArray(k) ?: return@forEach
                val set = HashSet<String>()
                for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let { set.add(it) }
                if (set.isNotEmpty()) out[k] = set
            }
            out
        } catch (e: Exception) {
            emptyMap()
        }
    }

    // ---- Userscripts (run inside the WebView only) ----

    fun userscriptsFlow(): Flow<List<Userscript>> =
        store.data.map { parseUserscripts(it[K.USERS]) }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun userscripts(): List<Userscript> = userscriptsFlow().first()

    suspend fun setUserscripts(list: List<Userscript>) {
        write("USERS") { it[K.USERS] = encodeUserscripts(list) }
    }

    private fun parseUserscripts(s: String?): List<Userscript> {
        if (s.isNullOrBlank()) return emptyList()
        val out = mutableListOf<Userscript>()
        try {
            val arr = JSONArray(s)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val code = o.optString("code")
                if (code.isBlank()) continue
                out += Userscript(
                    id = o.optString("id"),
                    name = o.optString("name").ifBlank { "Userscript" },
                    enabled = o.optBoolean("enabled", true),
                    code = code,
                )
            }
        } catch (e: Exception) {
            return emptyList()
        }
        return out
    }

    private fun encodeUserscripts(list: List<Userscript>): String {
        val arr = JSONArray()
        for (u in list) {
            arr.put(
                JSONObject()
                    .put("id", u.id)
                    .put("name", u.name)
                    .put("enabled", u.enabled)
                    .put("code", u.code)
            )
        }
        return arr.toString()
    }

    fun providersFlow(): Flow<List<ProviderConfig>> =
        store.data.map { parseProviders(it[K.PROVIDERS]) }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun providers(): List<ProviderConfig> {
        val persisted = providersFlow().first()
        val burst = providerWrites.withLock {
            if (burstDepth == 0) null
            else burstRemoved.toSet() to burstAdded.values.toList()
        } ?: return persisted
        val (removed, added) = burst
        if (removed.isEmpty() && added.isEmpty()) return persisted
        val addIds = added.map { it.id }.toSet()
        return persisted.filterNot { it.id in removed || it.id in addIds } + added
    }

    suspend fun saveProviders(list: List<ProviderConfig>) = providerWrites.withLock {
        write("PROVIDERS") { it[K.PROVIDERS] = encodeProviders(list) }
    }

    private val providerWrites = Mutex()

    /** Burst support for Install-all / Update-all (see
     *  [com.hikari.app.ui.screens.ExtensionsViewModel.installAllPlugins]):
     *  a 96-row batch used to rewrite the whole 600+ row provider blob on
     *  EVERY row — 96 full parse/encode/write cycles plus 96 observer
     *  storms — which is what made batch installs lag. Inside a burst every
     *  [addProvider]/[updateProviders] call only records its intent in
     *  memory ([burstAdded]/[burstRemoved]); [endProviderBurst] applies the
     *  net result in ONE read-modify-write. Outside a burst everything
     *  writes immediately, exactly as before. Nestable; the outermost
     *  [endProviderBurst] flushes. */
    private var burstDepth = 0
    private val burstAdded = LinkedHashMap<String, ProviderConfig>()
    private val burstRemoved = HashSet<String>()

    suspend fun beginProviderBurst() = providerWrites.withLock { burstDepth++ }

    suspend fun endProviderBurst() = providerWrites.withLock {
        if (burstDepth > 0) burstDepth--
        if (burstDepth > 0) return@withLock
        if (burstAdded.isEmpty() && burstRemoved.isEmpty()) return@withLock
        val added = burstAdded.values.toList()
        val removed = burstRemoved.toSet()
        burstAdded.clear()
        burstRemoved.clear()
        write("PROVIDERS") { prefs ->
            val before = parseProviders(prefs[K.PROVIDERS]).filterNot { it.id in removed }
            val addIds = added.map { it.id }.toSet()
            prefs[K.PROVIDERS] = encodeProviders(before.filterNot { it.id in addIds } + added)
        }
    }

    suspend fun updateProviders(
        transform: (List<ProviderConfig>) -> List<ProviderConfig>,
    ): List<ProviderConfig> = providerWrites.withLock {
        if (burstDepth > 0) {
            val persisted = parseProviders(store.data.first()[K.PROVIDERS])
            val view = persisted.filterNot { it.id in burstRemoved || it.id in burstAdded } +
                burstAdded.values
            val result = transform(view)
            val newIds = result.map { it.id }.toSet()
            val oldIds = persisted.map { it.id }.toSet() + burstAdded.keys
            burstRemoved.clear()
            burstRemoved.addAll(oldIds - newIds)
            val per = persisted.associateBy { it.id }
            burstAdded.clear()
            for (r in result) if (per[r.id] != r) burstAdded[r.id] = r
            return@withLock result
        }
        var result: List<ProviderConfig> = emptyList()
        write("PROVIDERS") { prefs ->
            val before = parseProviders(prefs[K.PROVIDERS])
            result = transform(before)
            if (result != before) prefs[K.PROVIDERS] = encodeProviders(result)
        }
        result
    }

    suspend fun addProvider(c: ProviderConfig) {
        providerWrites.withLock {
            if (burstDepth > 0) {
                burstRemoved.remove(c.id)
                burstAdded[c.id] = c
                return@withLock
            }
            write("PROVIDERS") { prefs ->
                prefs[K.PROVIDERS] =
                    encodeProviders(parseProviders(prefs[K.PROVIDERS]).filter { it.id != c.id } + c)
            }
        }
    }

    /** Adds a whole batch of providers in ONE read-modify-write. Install-all
     *  used to call [addProvider] per row — 96 full parse/encode/write cycles
     *  of a 600+ row JSON blob plus 96 observer storms — which is what made
     *  batch installs lag. One transaction, same result. */
    suspend fun addProviders(rows: List<ProviderConfig>) {
        if (rows.isEmpty()) return
        providerWrites.withLock {
            write("PROVIDERS") { prefs ->
                val ids = rows.map { it.id }.toSet()
                prefs[K.PROVIDERS] =
                    encodeProviders(parseProviders(prefs[K.PROVIDERS]).filter { it.id !in ids } + rows)
            }
        }
    }

    suspend fun markProvidersAdult(items: List<Pair<String, Boolean>>): Boolean {
        val byKey = HashMap<String, Boolean>()
        for ((url, adult) in items) {
            if (url.isBlank()) continue
            for (key in SourceUrls.matchKeys(url)) byKey.putIfAbsent(key, adult)
        }
        if (byKey.isEmpty()) return false
        var changed = false
        updateProviders { list ->
            list.map { c ->
                val source = sourceUrlOf(c)
                val known = if (source.isEmpty()) null else byKey[source]
                    ?: SourceUrls.matchKeys(source).firstNotNullOfOrNull { byKey[it] }
                if (known != null && known != c.nsfw) {
                    changed = true
                    c.copy(nsfw = known)
                } else {
                    c
                }
            }
        }
        return changed
    }

    fun sourceUrlOf(c: ProviderConfig): String {
        val extra = c.extra ?: return ""
        if (!extra.startsWith("http")) return ""
        return if (c.type == ProviderType.HIKARI) extra.substringBeforeLast('|') else extra
    }

    suspend fun removeProvider(id: String) {
        providerWrites.withLock {
            write("PROVIDERS") { prefs ->
                prefs[K.PROVIDERS] =
                    encodeProviders(parseProviders(prefs[K.PROVIDERS]).filter { it.id != id })
            }
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        providerWrites.withLock {
            write("PROVIDERS") { prefs ->
                prefs[K.PROVIDERS] = encodeProviders(
                    parseProviders(prefs[K.PROVIDERS])
                        .map { if (it.id == id) it.copy(enabled = enabled) else it },
                )
            }
        }
    }

    fun reposFlow(): Flow<List<Cs3Repo>> =
        store.data.map { parseRepos(it[K.CS3_REPOS]) }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun repos(): List<Cs3Repo> = reposFlow().first()

    /** The identity of a repo URL, never null: [SourceUrls.repoKey] answers null
     *  only for an input with nothing usable in it, and such an entry is better
     *  compared by its own text than folded together with every other one. */
    private fun repoId(url: String): String =
        SourceUrls.repoKey(url) ?: SourceUrls.canonical(url).ifBlank { url.trim() }

    suspend fun addCs3Repo(r: Cs3Repo) {
        upsertRepo(r)
        clearRemovedRepoKey(repoId(r.url))
    }

    suspend fun seedCs3Repo(r: Cs3Repo): Boolean {
        if (repoId(r.url) in removedRepoKeys()) return false
        upsertRepo(r)
        return true
    }

    private suspend fun upsertRepo(r: Cs3Repo) {
        val key = repoId(r.url)
        val existing = repos().firstOrNull { repoId(it.url) == key }
        val merged = if (existing == null || existing.url == r.url) r
        else existing.copy(
            name = existing.name.ifBlank { r.name },
            description = r.description.ifBlank { existing.description },
            iconUrl = r.iconUrl.ifBlank { existing.iconUrl },
            url = if (SourceUrls.isMirror(existing.url)) r.url else existing.url,
        )
        saveRepos(repos().filter { repoId(it.url) != key } + merged)
    }

    suspend fun removeCs3Repo(url: String) {
        val key = repoId(url)
        saveRepos(repos().filter { repoId(it.url) != key })
        addRemovedRepoKey(key)
    }

    /** Identities of every repo the user has explicitly removed. */
    private suspend fun removedRepoKeys(): Set<String> {
        val raw = store.data.map { it[K.CS3_REMOVED_REPOS] }.first()
        if (raw.isNullOrBlank()) return emptySet()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optString(it).ifBlank { null } }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    private suspend fun addRemovedRepoKey(key: String) {
        if (key.isBlank()) return
        val next = removedRepoKeys() + key
        write("CS3_REMOVED_REPOS") { prefs ->
            val arr = JSONArray()
            for (k in next) arr.put(k)
            prefs[K.CS3_REMOVED_REPOS] = arr.toString()
        }
    }

    private suspend fun clearRemovedRepoKey(key: String) {
        if (key.isBlank()) return
        val before = removedRepoKeys()
        val next = before - key
        if (next.size == before.size) return
        write("CS3_REMOVED_REPOS") { prefs ->
            if (next.isEmpty()) prefs.remove(K.CS3_REMOVED_REPOS)
            else {
                val arr = JSONArray()
                for (k in next) arr.put(k)
                prefs[K.CS3_REMOVED_REPOS] = arr.toString()
            }
        }
    }

    suspend fun dedupeStoredRepos() {
        val before = repos()
        val after = dedupeRepos(before)
        if (after != before) saveRepos(after)
    }

    private suspend fun saveRepos(list: List<Cs3Repo>) {
        write("CS3_REPOS") { it[K.CS3_REPOS] = encodeRepos(dedupeRepos(list)) }
    }

    private fun dedupeRepos(list: List<Cs3Repo>): List<Cs3Repo> {
        val out = ArrayList<Cs3Repo>(list.size)
        val index = HashMap<String, Int>()
        for (r in list) {
            if (r.url.isBlank()) continue
            val key = repoId(r.url)
            val at = index[key]
            if (at == null) {
                index[key] = out.size
                out.add(r)
            } else if (SourceUrls.isMirror(out[at].url) && !SourceUrls.isMirror(r.url)) {
                out[at] = r
            } else if (out[at].name.isBlank() && r.name.isNotBlank()) {
                out[at] = out[at].copy(name = r.name, description = out[at].description.ifBlank { r.description })
            }
        }
        return out
    }

    // ---- Collections (name → folders → catalog sources) ----

    /** Every user-made collection, in creation order. */
    fun collectionsFlow(): Flow<List<Collection>> =
        store.data.map { parseCollections(it[K.COLLECTIONS]) }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun collections(): List<Collection> = collectionsFlow().first()

    suspend fun collection(id: String): Collection? = collections().firstOrNull { it.id == id }

    /** Replaces the whole list — the editor always hands back the full set, so
     *  a create/rename/reorder is one atomic write. */
    suspend fun saveCollections(list: List<Collection>) {
        write("COLLECTIONS") { it[K.COLLECTIONS] = encodeCollections(list) }
    }

    /** Adds [c] (or replaces the same-id entry) and returns the saved list. */
    suspend fun upsertCollection(c: Collection): List<Collection> {
        val next = collections().filter { it.id != c.id } + c
        saveCollections(next)
        return next
    }

    suspend fun removeCollection(id: String) {
        saveCollections(collections().filter { it.id != id })
    }

    /** A stable, URL/JSON-safe id for a new collection or folder. */
    fun newId(prefix: String): String =
        prefix + "-" + System.currentTimeMillis().toString(36) +
            "-" + (1000 + (Math.random() * 8999).toInt())

    // ---- First-run extension-repo seeding ----

    /** True once the bundled default extension repos have been added. Kept so a
     *  user who deliberately removes a default repo doesn't have it pushed back
     *  on the next launch. */
    suspend fun seededRepos(): Boolean =
        store.data.map { it[K.SEEDED_REPOS] ?: false }.distinctUntilChanged().flowOn(Dispatchers.Default).first()

    suspend fun markReposSeeded() {
        write("SEEDED_REPOS") { it[K.SEEDED_REPOS] = true }
    }

    /** True once the bundled default Stremio addon has been installed (see
     *  [com.hikari.app.providers.StremioAddon.DEFAULT_ADDON_URL]). One-way, like
     *  [seededRepos]: a user who removes the addon keeps it removed. */
    suspend fun stremioAddonSeeded(): Boolean =
        store.data.map { it[K.STREMIO_SEEDED] ?: false }.distinctUntilChanged().flowOn(Dispatchers.Default).first()

    /** Ensure keyless Stremio Cinemeta exists when provider catalogs are disabled. */
    suspend fun ensureCinemetaAddon() {
        val url = "https://v3-cinemeta.strem.io/manifest.json"
        val id = com.hikari.app.providers.StremioAddon.providerIdFor(url)
        if (providers().none { it.type == ProviderType.STREMIO && com.hikari.app.providers.StremioAddon.providerIdFor(it.url) == id }) {
            addProvider(ProviderConfig(id = id, name = "Cinemeta", type = ProviderType.STREMIO, url = url))
        }
    }

    suspend fun markStremioAddonSeeded() {
        write("STREMIO_SEEDED") { it[K.STREMIO_SEEDED] = true }
    }

    /** True once the providers earlier builds bundled as pre-installed scrapers
     *  have been removed from this install. One-way: it is set after the sweep
     *  and never cleared, so a user who installs one of those scrapers again
     *  afterwards keeps it (see [com.hikari.app.nuvio.NuvioPluginManager]). */
    suspend fun nuvioSeedCleaned(): Boolean =
        store.data.map { it[K.NUVIO_SEED_CLEANED] ?: false }.distinctUntilChanged().flowOn(Dispatchers.Default).first()

    suspend fun markNuvioSeedCleaned() {
        write("NUVIO_SEED_CLEANED") { it[K.NUVIO_SEED_CLEANED] = true }
    }

    /** True once the bundled YTS extension earlier builds registered on first
     *  run has been removed. One-way, so a row the user somehow re-added by hand
     *  is not deleted again on every launch. */
    suspend fun ytsCleaned(): Boolean =
        store.data.map { it[K.YTS_CLEANED] ?: false }.distinctUntilChanged().flowOn(Dispatchers.Default).first()

    suspend fun markYtsCleaned() {
        write("YTS_CLEANED") { it[K.YTS_CLEANED] = true }
    }

    /** True once the 0.11.69 Kotatsu auto-seed has been pruned (see
     *  [com.hikari.app.kotatsu.KotatsuRegistry.pruneSeeded]). One-way. */
    suspend fun kotatsuPruned(): Boolean =
        store.data.map { it[K.KOTATSU_PRUNED] ?: false }.distinctUntilChanged().flowOn(Dispatchers.Default).first()

    suspend fun markKotatsuPruned() {
        write("KOTATSU_PRUNED") { it[K.KOTATSU_PRUNED] = true }
    }

    fun favoritesFlow(): Flow<List<MediaItem>> =
        store.data.map { parseMedia(it[K.FAVORITES]) }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun favorites(): List<MediaItem> = favoritesFlow().first()

    suspend fun addFavorite(m: MediaItem) {
        val list = favorites().filter { it.uniqueId != m.uniqueId } + m
        write("FAVORITES") { it[K.FAVORITES] = encodeMedia(list) }
    }

    suspend fun removeFavorite(id: String) {
        write("FAVORITES") { prefs ->
            prefs[K.FAVORITES] = encodeMedia(parseMedia(prefs[K.FAVORITES]).filter { f -> f.uniqueId != id })
            // Unfiling the title is part of removing it: leaving the mapping
            // behind would file the NEXT title saved under the same id.
            val cats = parseCategoryMap(prefs[K.FAVORITE_CATEGORIES]).toMutableMap()
            if (cats.remove(id) != null) prefs[K.FAVORITE_CATEGORIES] = encodeCategoryMap(cats)
        }
    }

    fun sitesFlow(): Flow<List<Site>> =
        store.data.map { parseSites(it[K.SITES]) }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun sites(): List<Site> = sitesFlow().first()

    suspend fun addSite(s: Site) {
        val list = sites().filter { it.url != s.url } + s
        write("SITES") { it[K.SITES] = encodeSites(list) }
    }

    suspend fun removeSite(url: String) {
        val list = sites().filter { it.url != url }
        write("SITES") { it[K.SITES] = encodeSites(list) }
    }

    private fun encodeSites(list: List<Site>): String {
        val arr = JSONArray()
        for (s in list) {
            arr.put(JSONObject().put("name", s.name).put("url", s.url))
        }
        return arr.toString()
    }

    private fun parseSites(s: String?): List<Site> {
        if (s.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val url = o.optString("url")
                if (url.isBlank()) null
                else Site(name = o.optString("name").ifBlank { url }, url = url)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun clearAll() {
        write("every setting") { it.clear() }
    }

    // ---- Backup & restore (Settings → Backup & Restore) ----

    suspend fun snapshotPreferences(): List<PrefRecord> =
        store.data.first().asMap()
            .mapNotNull { (key, value) -> recordOf(key.name, value) }
            .filterNot { DeviceLocal.contains(it.key) }

    suspend fun restorePreferences(records: List<PrefRecord>): Int {
        val travelling = records.filterNot { DeviceLocal.contains(it.key) }
        if (travelling.isEmpty()) return 0
        var applied = 0
        write("the restored backup") { prefs ->
            for (r in travelling) {
                runCatching { applyRecord(prefs, r) }.onSuccess { applied++ }
            }
        }
        return applied
    }

    suspend fun replacePreferences(records: List<PrefRecord>): Int {
        val keep = store.data.first().asMap()
            .filterKeys { DeviceLocal.contains(it.name) }
            .mapNotNull { (key, value) -> recordOf(key.name, value) }
        val incoming = records.filterNot { DeviceLocal.contains(it.key) }
        write("the profile switch") { prefs ->
            prefs.clear()
            for (r in keep) applyRecord(prefs, r)
            for (r in incoming) applyRecord(prefs, r)
        }
        return incoming.size
    }

    /** [value] in the backup file's own terms, or null for a type the file
     *  format has no code for (nothing in this store uses one today). */
    private fun recordOf(name: String, value: Any): PrefRecord? = when (value) {
        is String -> PrefRecord(name, "s", value)
        is Boolean -> PrefRecord(name, "b", value)
        is Int -> PrefRecord(name, "i", value)
        is Long -> PrefRecord(name, "l", value)
        is Float -> PrefRecord(name, "f", value)
        is Double -> PrefRecord(name, "d", value)
        is Set<*> -> PrefRecord(name, "ss", value.filterIsInstance<String>())
        is ByteArray -> PrefRecord(
            name, "bin",
            android.util.Base64.encodeToString(value, android.util.Base64.NO_WRAP),
        )
        else -> null
    }

    private fun applyRecord(prefs: MutablePreferences, r: PrefRecord) {
        when (r.type) {
            "s" -> (r.value as? String)?.let { prefs[stringPreferencesKey(r.key)] = it }
            "b" -> (r.value as? Boolean)?.let { prefs[booleanPreferencesKey(r.key)] = it }
            "i" -> (r.value as? Number)?.let { prefs[intPreferencesKey(r.key)] = it.toInt() }
            "l" -> (r.value as? Number)?.let { prefs[longPreferencesKey(r.key)] = it.toLong() }
            "f" -> (r.value as? Number)?.let { prefs[floatPreferencesKey(r.key)] = it.toFloat() }
            "d" -> (r.value as? Number)?.let { prefs[doublePreferencesKey(r.key)] = it.toDouble() }
            "ss" -> {
                val list: List<*>? = when (val v = r.value) {
                    is List<*> -> v
                    is JSONArray -> (0 until v.length()).map { v.optString(it) }
                    else -> null
                }
                list?.let { prefs[stringSetPreferencesKey(r.key)] = it.filterIsInstance<String>().toSet() }
            }
            "bin" -> (r.value as? String)?.let { b64 ->
                runCatching { android.util.Base64.decode(b64, android.util.Base64.NO_WRAP) }
                    .getOrNull()
                    ?.let { prefs[byteArrayPreferencesKey(r.key)] = it }
            }
        }
    }

    // ---- Watch history ----

    fun historyFlow(): Flow<List<HistoryEntry>> =
        store.data.map { parseHistory(it[K.HISTORY]) }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun history(): List<HistoryEntry> = historyFlow().first()

    /** Insert/update one entry (deduped by [HistoryEntry.uniqueKey], newest
     *  first, capped at 200 entries). The read-modify-write happens INSIDE a
     *  single DataStore edit so the 5-second save tick and the onStop/onDestroy
     *  write can't race and drop one of two different entries. */
    suspend fun addHistory(e: HistoryEntry) {
        write("HISTORY") { prefs ->
            val cur = parseHistory(prefs[K.HISTORY])
            val next = (listOf(e) + cur.filter { it.uniqueKey != e.uniqueKey }).take(200)
            prefs[K.HISTORY] = encodeHistory(next)
        }
    }

    suspend fun clearHistory() {
        write("HISTORY") { it[K.HISTORY] = "[]" }
    }

    /** Remove ONE entry — a single movie, or a single episode of a series
     *  (episodes of one title share a mediaId, so the key is per-video). */
    suspend fun removeHistory(uniqueKey: String) {
        write("HISTORY") { prefs ->
            val cur = parseHistory(prefs[K.HISTORY])
            prefs[K.HISTORY] = encodeHistory(cur.filter { it.uniqueKey != uniqueKey })
        }
    }

    fun historyPausedFlow(): Flow<Boolean> =
        store.data.map { it[K.HISTORY_PAUSED] ?: false }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun historyPaused(): Boolean = historyPausedFlow().first()

    suspend fun setHistoryPaused(paused: Boolean) {
        write("HISTORY_PAUSED") { it[K.HISTORY_PAUSED] = paused }
    }

    /** When true, the Home screen hides its "Continue Watching" row entirely
     *  (history keeps being recorded — this only hides the shelf). */
    fun hideContinueFlow(): Flow<Boolean> =
        store.data.map { it[K.HIDE_CONTINUE] ?: false }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun hideContinue(): Boolean = hideContinueFlow().first()

    suspend fun setHideContinue(hide: Boolean) {
        write("HIDE_CONTINUE") { it[K.HIDE_CONTINUE] = hide }
    }


    fun lastSourcesFlow(): Flow<Map<String, LastSource>> =
        store.data.map { parseLastSources(it[K.LAST_SOURCE]) }.distinctUntilChanged().flowOn(Dispatchers.Default)

    /** The server this video was last played with (URL + name + the header
     *  variant that actually worked), or null. */
    suspend fun lastSource(key: String): LastSource? =
        lastSourcesFlow().first()[key]

    suspend fun setLastSource(key: String, url: String, name: String, headerVariant: Int = 0) {
        if (key.isBlank()) return
        val cur = lastSourcesFlow().first().toMutableMap()
        cur[key] = LastSource(url, name, headerVariant)
        write("LAST_SOURCE") { it[K.LAST_SOURCE] = encodeLastSources(cur) }
    }

    private fun encodeLastSources(map: Map<String, LastSource>): String {
        val obj = JSONObject()
        map.entries.toList().takeLast(300).forEach { (k, v) ->
            obj.put(
                k,
                JSONObject()
                    .put("u", v.url)
                    .put("n", v.name)
                    .put("h", v.headerVariant)
            )
        }
        return obj.toString()
    }

    private fun parseLastSources(s: String?): Map<String, LastSource> {
        if (s.isNullOrBlank()) return emptyMap()
        return try {
            val obj = JSONObject(s)
            val out = LinkedHashMap<String, LastSource>()
            obj.keys().forEach { k ->
                val o = obj.optJSONObject(k) ?: return@forEach
                val u = o.optString("u")
                val n = o.optString("n")
                val h = o.optInt("h", 0)
                if (u.isNotBlank() || n.isNotBlank()) out[k] = LastSource(u, n, h)
            }
            out
        } catch (e: Exception) {
            emptyMap()
        }
    }

    // ---- WebView element blocker (persistent CSS selectors) ----

    fun elementBlocksFlow(): Flow<List<String>> =
        store.data.map { parseStringList(it[K.ELEMENT_BLOCKS]) }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun elementBlocks(): List<String> = elementBlocksFlow().first()

    suspend fun addElementBlock(selector: String) {
        val cur = elementBlocks()
        if (selector in cur) return
        write("ELEMENT_BLOCKS") { it[K.ELEMENT_BLOCKS] = encodeStringList((cur + selector).take(200)) }
    }

    /** Removes and returns the most recently blocked selector (null if none). */
    suspend fun removeLastElementBlock(): String? {
        val cur = elementBlocks()
        if (cur.isEmpty()) return null
        val last = cur.last()
        write("ELEMENT_BLOCKS") { it[K.ELEMENT_BLOCKS] = encodeStringList(cur.dropLast(1)) }
        return last
    }

    suspend fun clearElementBlocks() {
        write("ELEMENT_BLOCKS") { it[K.ELEMENT_BLOCKS] = "[]" }
    }

    private fun encodeHistory(list: List<HistoryEntry>): String {
        val arr = JSONArray()
        for (h in list) {
            arr.put(
                JSONObject()
                    .put("pid", h.providerId)
                    .put("id", h.mediaId)
                    .put("type", h.type.name)
                    .put("title", h.title)
                    .put("poster", h.posterUrl ?: "")
                    .put("eid", h.episodeId)
                    .put("ename", h.episodeName)
                    .put("ep", h.episodeNumber)
                    .put("sea", h.seasonNumber)
                    .put("pos", h.positionMs)
                    .put("dur", h.durationMs)
                    .put("at", h.watchedAt)
            )
        }
        return arr.toString()
    }

    private fun parseHistory(s: String?): List<HistoryEntry> {
        if (s.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id")
                if (id.isBlank()) null
                else HistoryEntry(
                    providerId = o.optString("pid"),
                    mediaId = id,
                    type = runCatching { MediaType.valueOf(o.optString("type")) }
                        .getOrDefault(MediaType.UNKNOWN),
                    title = o.optString("title"),
                    posterUrl = o.optString("poster").ifBlank { null },
                    episodeId = o.optString("eid"),
                    episodeName = o.optString("ename"),
                    episodeNumber = o.optInt("ep", 0),
                    seasonNumber = o.optInt("sea", 0),
                    positionMs = o.optLong("pos", 0L),
                    durationMs = o.optLong("dur", 0L),
                    watchedAt = o.optLong("at", 0L),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun encodeProviders(list: List<ProviderConfig>): String {
        val arr = JSONArray()
        for (c in list) {
            val o = JSONObject()
                .put("id", c.id)
                .put("name", c.name)
                .put("type", c.type.name)
                .put("url", c.url)
                .put("iconUrl", c.iconUrl ?: "")
                .put("enabled", c.enabled)
                .put("extra", c.extra ?: "")
            // Written only when known, so existing rows (and older app versions
            // reading the same file) are untouched until something learns it.
            if (c.nsfw != null) o.put("nsfw", c.nsfw)
            if (!c.lang.isNullOrBlank()) o.put("lang", c.lang)
            arr.put(o)
        }
        return arr.toString()
    }

    private fun parseProviders(s: String?): List<ProviderConfig> {
        if (s.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                ProviderConfig(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    type = runCatching { ProviderType.valueOf(o.optString("type")) }
                        .getOrDefault(ProviderType.STREMIO),
                    url = o.optString("url"),
                    iconUrl = o.optString("iconUrl").ifBlank { null },
                    enabled = o.optBoolean("enabled", true),
                    extra = o.optString("extra").ifBlank { null },
                    nsfw = if (o.has("nsfw")) o.optBoolean("nsfw") else null,
                    lang = o.optString("lang").ifBlank { null },
                )
            }.filter { it.id.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun encodeRepos(list: List<Cs3Repo>): String {
        val arr = JSONArray()
        for (r in list) {
            arr.put(
                JSONObject()
                    .put("url", r.url)
                    .put("name", r.name)
                    .put("description", r.description)
                    .put("kind", r.kind.name)
                    .put("icon", r.iconUrl)
            )
        }
        return arr.toString()
    }

    private fun parseRepos(s: String?): List<Cs3Repo> {
        if (s.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Cs3Repo(
                    url = o.optString("url"),
                    name = o.optString("name").ifBlank { o.optString("url") },
                    description = o.optString("description"),
                    kind = runCatching { RepoKind.valueOf(o.optString("kind", "CS3")) }
                        .getOrDefault(RepoKind.CS3),
                    iconUrl = o.optString("icon").ifBlank { o.optString("iconUrl") },
                )
            }.filter { it.url.isNotBlank() }
                .let { dedupeRepos(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun encodeCollections(list: List<Collection>): String {
        val arr = JSONArray()
        for (c in list) {
            val folders = JSONArray()
            for (f in c.folders) {
                val sources = JSONArray()
                for (s in f.sources) {
                    sources.put(
                        JSONObject()
                            .put("kind", s.kind.name)
                            .put("title", s.title)
                            .put("pid", s.providerId)
                            .put("cid", s.catalogId)
                            .put("type", s.type.name)
                            .put("raw", s.rawType)
                            .put("preset", s.tmdbPreset)
                            .put("spec", s.tmdbSpec)
                            .put("items", s.itemsJson)
                            .put("uid", s.uid)
                    )
                }
                folders.put(
                    JSONObject()
                        .put("id", f.id)
                        .put("name", f.name)
                        .put("coverKind", f.coverKind)
                        .put("coverValue", f.coverValue)
                        .put("tileShape", f.tileShape)
                        .put("hideTitle", f.hideTitle)
                        .put("gifAlways", f.gifAlways)
                        .put("heroBackdrop", f.heroBackdropUrl)
                        .put("titleLogo", f.titleLogoUrl)
                        .put("sources", sources)
                )
            }
            arr.put(
                JSONObject()
                    .put("id", c.id)
                    .put("name", c.name)
                    .put("coverKind", c.coverKind)
                    .put("coverValue", c.coverValue)
                    .put("tileShape", c.tileShape)
                    .put("pinToTop", c.pinToTop)
                    .put("viewMode", c.viewMode)
                    .put("showAllTab", c.showAllTab)
                    .put("backdrop", c.backdropUrl)
                    .put("folders", folders)
            )
        }
        return arr.toString()
    }

    private fun parseCollections(s: String?): List<Collection> {
        if (s.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id")
                val name = o.optString("name")
                if (id.isBlank() || name.isBlank()) return@mapNotNull null
                val foldersArr = o.optJSONArray("folders")
                val folders = ArrayList<CollectionFolder>()
                if (foldersArr != null) {
                    for (j in 0 until foldersArr.length()) {
                        val fo = foldersArr.optJSONObject(j) ?: continue
                        val fid = fo.optString("id")
                        val fname = fo.optString("name")
                        if (fid.isBlank() || fname.isBlank()) continue
                        val sourcesArr = fo.optJSONArray("sources")
                        val sources = ArrayList<CatalogSource>()
                        if (sourcesArr != null) {
                            for (k in 0 until sourcesArr.length()) {
                                val so = sourcesArr.optJSONObject(k) ?: continue
                                val kind = runCatching {
                                    CatalogSourceKind.valueOf(so.optString("kind", "TMDB"))
                                }.getOrDefault(CatalogSourceKind.TMDB)
                                val source = CatalogSource(
                                    kind = kind,
                                    title = so.optString("title"),
                                    providerId = so.optString("pid"),
                                    catalogId = so.optString("cid"),
                                    type = runCatching { MediaType.valueOf(so.optString("type")) }
                                        .getOrDefault(MediaType.UNKNOWN),
                                    rawType = so.optString("raw"),
                                    tmdbPreset = so.optString("preset"),
                                    tmdbSpec = so.optString("spec"),
                                    itemsJson = so.optString("items"),
                                    uid = so.optString("uid"),
                                )
                                val usable = when (kind) {
                                    CatalogSourceKind.TMDB ->
                                        TmdbPresets.byKey(source.tmdbPreset) != null ||
                                            (source.spec?.type != null)
                                    CatalogSourceKind.ITEMS -> source.itemCount > 0
                                    CatalogSourceKind.PROVIDER ->
                                        source.providerId.isNotBlank() &&
                                            source.catalogId.isNotBlank()
                                }
                                if (usable) sources.add(source)
                            }
                        }
                        folders.add(
                            CollectionFolder(
                                id = fid,
                                name = fname,
                                sources = sources.distinctBy { it.key },
                                coverKind = CoverKinds.normalize(fo.optString("coverKind")),
                                coverValue = fo.optString("coverValue"),
                                tileShape = TileShapes.normalize(fo.optString("tileShape")),
                                hideTitle = fo.optBoolean("hideTitle", false),
                                gifAlways = fo.optBoolean("gifAlways", false),
                                heroBackdropUrl = fo.optString("heroBackdrop"),
                                titleLogoUrl = fo.optString("titleLogo"),
                            )
                        )
                    }
                }
                Collection(
                    id = id,
                    name = name,
                    folders = folders,
                    coverKind = CoverKinds.normalize(o.optString("coverKind")),
                    coverValue = o.optString("coverValue"),
                    tileShape = TileShapes.normalize(o.optString("tileShape")),
                    pinToTop = o.optBoolean("pinToTop", false),
                    viewMode = CollectionViewModes.normalize(o.optString("viewMode")),
                    showAllTab = o.optBoolean("showAllTab", true),
                    backdropUrl = o.optString("backdrop"),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun encodeMedia(list: List<MediaItem>): String {
        val arr = JSONArray()
        for (m in list) {
            arr.put(
                JSONObject()
                    .put("pid", m.providerId)
                    .put("id", m.id)
                    .put("title", m.title)
                    .put("type", m.type.name)
                    .put("poster", m.posterUrl ?: "")
                    .put("year", m.year ?: 0)
                    .put("overview", m.overview ?: "")
                    .put("rating", m.rating ?: 0.0)
            )
        }
        return arr.toString()
    }

    private fun parseMedia(s: String?): List<MediaItem> {
        if (s.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                MediaItem(
                    providerId = o.optString("pid"),
                    id = o.optString("id"),
                    title = o.optString("title"),
                    type = runCatching { MediaType.valueOf(o.optString("type")) }
                        .getOrDefault(MediaType.UNKNOWN),
                    posterUrl = o.optString("poster").ifBlank { null },
                    year = o.optInt("year", 0).takeIf { it > 0 },
                    overview = o.optString("overview").ifBlank { null },
                    rating = o.optDouble("rating", 0.0).takeIf { it > 0.0 },
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
