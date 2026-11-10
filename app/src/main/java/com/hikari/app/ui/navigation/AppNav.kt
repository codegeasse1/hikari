package com.hikari.app.ui.navigation
import com.hikari.app.i18n.tr

import android.net.Uri
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.hikari.app.HikariApp
import com.hikari.app.data.MediaType
import com.hikari.app.ui.screens.AnimeCalendarScreen
import com.hikari.app.ui.screens.CatalogScreen
import com.hikari.app.ui.screens.CollectionGridScreen
import com.hikari.app.ui.screens.CollectionViewScreen
import com.hikari.app.ui.screens.CollectionsScreen
import com.hikari.app.ui.screens.DetailScreen
import com.hikari.app.ui.screens.DownloadsScreen
import com.hikari.app.ui.screens.ExtensionsScreen
import com.hikari.app.ui.screens.HistoryScreen
import com.hikari.app.ui.screens.WebSitesScreen
import com.hikari.app.ui.screens.HomeScreen
import com.hikari.app.ui.screens.IptvPlaylistScreen
import com.hikari.app.ui.screens.IptvScreen
import com.hikari.app.ui.screens.LiveTvHomeScreen
import com.hikari.app.ui.screens.LibraryScreen
import com.hikari.app.ui.screens.LiveTvCalendarScreen
import com.hikari.app.ui.screens.LiveTvGuideScreen
import com.hikari.app.ui.screens.LiveTvManageScreen
import com.hikari.app.ui.screens.LiveTvOnDemandScreen
import com.hikari.app.ui.screens.LiveTvRecordingsScreen
import com.hikari.app.ui.screens.LiveTvSettingsScreen
import com.hikari.app.ui.screens.LiveTvSportsScreen
import com.hikari.app.ui.screens.MangaDetailScreen
import com.hikari.app.ui.screens.MangaReaderScreen
import com.hikari.app.ui.screens.NovelReaderScreen
import com.hikari.app.ui.screens.ScraperReaderScreen
import com.hikari.app.ui.screens.MangaScreen
import com.hikari.app.ui.screens.MyStuff
import com.hikari.app.ui.screens.MyStuffScreen
import com.hikari.app.ui.screens.SearchScreen
import com.hikari.app.ui.screens.SettingsScreen
import com.hikari.app.ui.screens.StatsScreen
import com.hikari.app.ui.screens.TelegramScreen
import com.hikari.app.ui.screens.TmdbGridScreen
import com.hikari.app.ui.screens.TrackerShelfScreen
import com.hikari.app.ui.theme.HikariThemeMode
import com.hikari.app.ui.theme.rememberGlassTokens
import com.hikari.app.tv.TvMode
import com.hikari.app.tv.TvMouseCursorOverlay
import com.hikari.app.tv.TvNavRail
import com.hikari.app.tv.TvUi
import androidx.compose.runtime.collectAsState

object Routes {
    const val HOME = "home"
    const val ANIME_CALENDAR = "anime-calendar"
    const val SEARCH = "search"
    const val EXTENSIONS = "extensions"
    const val SETTINGS = "settings"
    const val HISTORY = "history"
    const val DOWNLOADS = "downloads"
    /** Titles saved with the player's heart (the favourites store). */
    const val LIBRARY = "library"
    const val TRACKER_SHELF = "tracker-shelf?source={source}&key={key}&title={title}&filter={filter}"

    /** Opens one tracker shelf as a full grid (see [TRACKER_SHELF]). */
    fun trackerShelf(source: String, shelfKey: String, title: String, filter: String = ""): String =
        "tracker-shelf?source=${Uri.encode(source)}&key=${Uri.encode(shelfKey)}" +
            "&title=${Uri.encode(title.take(200))}&filter=${Uri.encode(filter)}"
    const val IPTV = "iptv"
    /** Playlists, Tor and Network streams in the new Live TV style (stacked group cards). */
    const val IPTV_LISTS = "iptv-lists"
    /** One playlist's own page: its groups, each opening a paged channel grid. */
    const val IPTV_PLAYLIST = "iptv-playlist?pid={pid}"
    /** Live TV engine pages (guide, settings, manage, on-demand, recordings, sports, calendar). */
    const val LIVETV_SETTINGS = "livetv-settings"
    const val LIVETV_GUIDE = "livetv-guide"
    const val LIVETV_MANAGE = "livetv-manage"
    const val LIVETV_ONDEMAND = "livetv-ondemand"
    const val LIVETV_RECORDINGS = "livetv-recordings"
    const val LIVETV_SPORTS = "livetv-sports"
    const val LIVETV_CALENDAR = "livetv-calendar"

    const val MANGA = "manga"
    /** One manga: cover, description, follow button, chapter list. */
    const val MANGA_DETAIL =
        "manga-detail?providerId={providerId}&url={url}&title={title}&poster={poster}"
    const val MANGA_SEARCH = "manga-search?q={q}&genre={genre}&engine={engine}"
    /** Base path of [MANGA_SEARCH] — mapped back onto the Manga tab for the
     *  bottom bar, the same way the scoped video search maps onto Search. */
    const val MANGA_SEARCH_BASE = "manga-search"
    const val MANGA_READER =
        "manga-reader?providerId={providerId}&url={url}&chapter={chapter}&title={title}&poster={poster}"
    const val NOVEL_READER =
        "novel-reader?providerId={providerId}&url={url}&chapter={chapter}&title={title}&poster={poster}"
    const val SCRAPER_READER = "scraper-reader?key={key}&title={title}"

    const val STATS = "stats"

    const val TELEGRAM = "telegram"

    /** Saved websites opened in the ad-free web view (see SitesFolder). */
    const val WEBSITES = "websites"

    /** Opens one manga. [url] is the source's own url for the title (its id). */
    fun mangaDetail(
        providerId: String,
        url: String,
        title: String,
        posterUrl: String? = null,
    ): String {
        // Same sanitizing as detail(): extension-supplied text can carry
        // control characters that crash the route parser.
        val safeTitle = title.replace(Regex("[\\p{Cc}\\u2028\\u2029]"), " ").trim().take(500)
        var s = "manga-detail?providerId=${Uri.encode(providerId)}" +
            "&url=${Uri.encode(url)}&title=${Uri.encode(safeTitle)}"
        val poster = posterUrl?.takeIf { it.isNotBlank() && !it.startsWith("data:") && it.length <= 600 }
        if (poster != null) s += "&poster=${Uri.encode(poster)}"
        return s
    }

    /** Opens the reader on one chapter of a manga. */
    fun mangaReader(
        providerId: String,
        url: String,
        chapterUrl: String,
        title: String,
        posterUrl: String? = null,
    ): String {
        val safeTitle = title.replace(Regex("[\\p{Cc}\\u2028\\u2029]"), " ").trim().take(500)
        var s = "manga-reader?providerId=${Uri.encode(providerId)}&url=${Uri.encode(url)}" +
            "&chapter=${Uri.encode(chapterUrl)}&title=${Uri.encode(safeTitle)}"
        val poster = posterUrl?.takeIf { it.isNotBlank() && !it.startsWith("data:") && it.length <= 600 }
        if (poster != null) s += "&poster=${Uri.encode(poster)}"
        return s
    }

    fun scraperReader(key: String, title: String): String {
        val safeTitle = title.replace(Regex("[\\p{Cc}\\u2028\\u2029]"), " ").trim().take(200)
        return "scraper-reader?key=${Uri.encode(key)}&title=${Uri.encode(safeTitle)}"
    }

    /** Opens the novel reader on one chapter of a novel. */
    fun novelReader(
        providerId: String,
        url: String,
        chapterUrl: String,
        title: String,
        posterUrl: String? = null,
    ): String {
        val safeTitle = title.replace(Regex("[\\p{Cc}\\u2028\\u2029]"), " ").trim().take(500)
        var s = "novel-reader?providerId=${Uri.encode(providerId)}&url=${Uri.encode(url)}" +
            "&chapter=${Uri.encode(chapterUrl)}&title=${Uri.encode(safeTitle)}"
        val poster = posterUrl?.takeIf { it.isNotBlank() && !it.startsWith("data:") && it.length <= 600 }
        if (poster != null) s += "&poster=${Uri.encode(poster)}"
        return s
    }

    fun readingRoute(
        providerId: String,
        url: String,
        chapterUrl: String,
        title: String,
        posterUrl: String? = null,
    ): String {
        val novel = com.hikari.app.manga.MangaExtensionManager.isNovelId(providerId) ||
            com.hikari.app.sora.SoraMangaProvider.isSoraNovelId(providerId) ||
            com.hikari.app.lnreader.LnReaderProvider.isLnReaderId(providerId) ||
            com.hikari.app.kotatsu.fork.ForkRegistry.isForkNovelId(providerId)
        return if (novel) novelReader(providerId, url, chapterUrl, title, posterUrl)
        else mangaReader(providerId, url, chapterUrl, title, posterUrl)
    }

    /** Opens the Manga tab with [query] already searched across every
     *  installed manga engine. Tracker manga shelves land here. */
    /** [genre] arms a TAG search instead (results hold the tag — see
     *  MangaProvider.searchByGenre), [engineId] scopes it to one engine. */
    fun mangaSearch(query: String, genre: String = "", engineId: String = ""): String {
        val q = query.replace(Regex("[\\p{Cc}\\u2028\\u2029]"), " ").trim().take(200)
        val g = genre.trim().take(80).filter { it.code != 0x2028 && it.code != 0x2029 && !it.isISOControl() }
        return "manga-search?q=${Uri.encode(q)}&genre=${Uri.encode(g)}&engine=${Uri.encode(engineId.trim().take(200))}"
    }

    private val TRACKER_MANGA_SOURCES = setOf(
        "anilist", "mal", "kitsu", "shikimori", "simkl", "trakt", "tmdb",
    )
    fun isTrackerMangaSource(providerId: String): Boolean =
        providerId.startsWith("tracker|") || providerId in TRACKER_MANGA_SOURCES

    /** The manga route for an item: tracker manga goes to the Manga tab
     *  search (every installed engine, read from any); engine manga keeps
     *  its own detail page. */
    fun mangaRoute(
        providerId: String,
        url: String,
        title: String,
        posterUrl: String? = null,
    ): String = if (isTrackerMangaSource(providerId)) mangaSearch(title)
    else mangaDetail(providerId, url, title, posterUrl)

    /** Opens one IPTV playlist's group list. */
    fun iptvPlaylist(providerId: String): String =
        "iptv-playlist?pid=${Uri.encode(providerId)}"
    const val SEARCH_QUERY = "provider-search?q={q}&provider={provider}"
    /** Base path of [SEARCH_QUERY] — used to map the scoped route onto the
     *  Search tab for the bottom bar. */
    const val SEARCH_QUERY_BASE = "provider-search"
    // All args live in the query string: mediaIds are URLs (slashes would break
    // a path segment) and posters can be megabytes of base64 (see detail()).
    const val DETAIL = "detail?providerId={providerId}&type={type}&mediaId={mediaId}&title={title}&poster={poster}&rawType={rawType}&episodeId={episodeId}&startPos={startPos}&autoplay={autoplay}"
    // "Show All" catalog browser: every item of one provider catalog, paged.
    const val CATALOG = "catalog?providerId={providerId}&catalogId={catalogId}&title={title}&providerName={providerName}&type={type}&rawType={rawType}"
    const val COLLECTIONS = "collections"
    const val COLLECTION_VIEW = "collection-view?cid={cid}&fid={fid}"
    const val COLLECTION_GRID = "collection-grid?cid={cid}"
    const val TMDB_GRID = "tmdb-grid?preset={preset}&title={title}"
    /** One hand-built TMDB source (a studio, a network, a person, a custom
     *  discover query…) as a full grid. The spec is JSON in the query string,
     *  the same way a detail route carries its poster. */
    const val TMDB_GRID_SPEC = "tmdb-grid-spec?spec={spec}&title={title}"

    fun collectionView(collectionId: String, folderId: String = ""): String =
        "collection-view?cid=${Uri.encode(collectionId)}&fid=${Uri.encode(folderId)}"

    /** The whole collection as one grid: every folder, every catalog. */
    fun collectionGrid(collectionId: String): String =
        "collection-grid?cid=${Uri.encode(collectionId)}"

    fun tmdbGrid(presetKey: String, title: String): String =
        "tmdb-grid?preset=${Uri.encode(presetKey)}&title=${Uri.encode(title)}"

    /** Opens one saved [com.hikari.app.data.TmdbSpec] as a full grid. */
    fun tmdbGridSpec(specJson: String, title: String): String =
        "tmdb-grid-spec?spec=${Uri.encode(specJson)}&title=${Uri.encode(title)}"

    fun catalog(
        providerId: String,
        catalogId: String,
        title: String,
        providerName: String,
        type: MediaType,
        rawType: String = "",
    ): String =
        "catalog?providerId=${Uri.encode(providerId)}&catalogId=${Uri.encode(catalogId)}" +
            "&title=${Uri.encode(title)}&providerName=${Uri.encode(providerName)}" +
            "&type=${Uri.encode(type.name)}&rawType=${Uri.encode(rawType)}"

    fun detail(
        providerId: String,
        type: MediaType,
        mediaId: String,
        title: String,
        posterUrl: String? = null,
        rawType: String = "",
        /** Watch-history resume: target episode id (blank for movies). */
        episodeId: String = "",
        /** Watch-history resume: playback position in milliseconds. */
        startPositionMs: Long = 0L,
        /** Poster hold-menu play: start playback as soon as a server lands. */
        autoplay: Boolean = false,
    ): String {
        val safeTitle = title.replace(Regex("[\\p{Cc}\\u2028\\u2029]"), " ")
            .trim().take(500)
        var s = "detail?providerId=${Uri.encode(providerId)}&type=${Uri.encode(type.name)}&mediaId=${Uri.encode(mediaId)}&title=${Uri.encode(safeTitle)}"
        val poster = posterUrl?.takeIf { it.isNotBlank() && !it.startsWith("data:") && it.length <= 600 }
        if (poster != null) s += "&poster=${Uri.encode(poster)}"
        if (rawType.isNotBlank()) s += "&rawType=${Uri.encode(rawType)}"
        if (episodeId.isNotBlank()) s += "&episodeId=${Uri.encode(episodeId)}"
        if (startPositionMs > 0L) s += "&startPos=$startPositionMs"
        if (autoplay) s += "&autoplay=1"
        return s
    }

    /** Opens the Search tab with a pre-filled query (e.g. a genre tag). */
    fun searchQuery(q: String): String = "$SEARCH_QUERY_BASE?q=${Uri.encode(q)}&provider="

    fun fromStatsKey(key: String, title: String, posterUrl: String? = null): String? {
        val k = key.trim()
        if (k.startsWith("manga:", ignoreCase = true)) {
            val rest = k.substring(6)
            val sep = rest.indexOf('|')
            if (sep > 0) {
                val providerId = rest.substring(0, sep)
                val url = rest.substring(sep + 1)
                if (providerId.isNotBlank() && url.isNotBlank()) {
                    return mangaDetail(providerId, url, title.ifBlank { url }, posterUrl)
                }
            }
        } else {
            val parts = k.split('|')
            if (parts.size >= 3) {
                val providerId = parts[0]
                val type = runCatching { MediaType.valueOf(parts[1].uppercase()) }.getOrNull()
                val mediaId = parts[2]
                if (providerId.isNotBlank() && type != null && mediaId.isNotBlank()) {
                    return detail(
                        providerId = providerId,
                        type = type,
                        mediaId = mediaId,
                        title = title,
                        posterUrl = posterUrl,
                        episodeId = parts.getOrNull(3).orEmpty(),
                    )
                }
            }
        }
        val q = title.trim()
        return if (q.isBlank()) null else searchQuery(q)
    }

    /** Opens the Search tab with the query scoped to one provider — Home's
     *  "Search this extension" entry point. */
    fun searchInProvider(providerId: String, q: String = ""): String =
        "$SEARCH_QUERY_BASE?q=${Uri.encode(q)}&provider=${Uri.encode(providerId)}"

    const val COLLECTION_PROVIDER_PREFIX = "collection:"

    /** True when [key] names a personal catalog rather than an extension. */
    fun isCollectionScope(key: String): Boolean =
        key.startsWith(COLLECTION_PROVIDER_PREFIX)

    /** The collection id inside a [COLLECTION_PROVIDER_PREFIX] key. */
    fun collectionIdOfScope(key: String): String =
        key.removePrefix(COLLECTION_PROVIDER_PREFIX)

    /** Opens the Search tab scoped to one personal catalog — "search inside
     *  this catalog", the magnifier on a collection page and Home's "In abc"
     *  option. The query box starts empty: the point is to search the catalog,
     *  not to re-run the two words the user happened to have on Home. */
    fun searchInCollection(collectionId: String, q: String = ""): String =
        searchInProvider(COLLECTION_PROVIDER_PREFIX + collectionId, q)

    /** Maps a full Compose-Navigation route onto the bottom-bar tab it belongs
     *  to (strips the query string; folds the scoped search route back onto the
     *  Search tab). Returns null when the route isn't a tab. */
    fun tabBaseOf(route: String?): String? {
        val base = route?.substringBefore('?') ?: return null
        return when (base) {
            SEARCH_QUERY_BASE -> SEARCH
            MANGA_SEARCH_BASE -> MANGA
            HISTORY, DOWNLOADS -> LIBRARY
            else -> base
        }
    }

    private var lastTabNavAt = 0L
    private var lastTabNavRoute: String? = null
    fun navigateTab(nav: NavHostController, route: String) {
        if (nav.currentDestination?.route == route) return
        val now = android.os.SystemClock.uptimeMillis()
        if (route == lastTabNavRoute && now - lastTabNavAt < 500L) return
        lastTabNavRoute = route
        lastTabNavAt = now
        val popped = runCatching {
            nav.popBackStack(route, /* inclusive = */ false, /* saveState = */ true)
        }.getOrDefault(false)
        if (popped) return
        runCatching {
            nav.navigate(route) {
                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    /** navigate() that can never crash the app on a malformed route — some
     *  extensions return titles/ids that trip up the route parser, and one
     *  junk item must not be able to kill the whole app. */
    fun safeNavigate(nav: NavHostController, route: String) {
        runCatching { nav.navigate(route) }
    }
}


object NavStyles {
    const val CLASSIC = "classic"
    const val FLOATING = "floating"
    const val ANIMATED = "animated"
    const val COMPACT = "compact"

    data class Option(val key: String, val label: String, val blurb: String)

    val ALL = listOf(
        Option(
            ANIMATED,
            "Floating animation",
            "A small floating pill that draws itself in as you scroll up."
        ),
        Option(FLOATING, "Floating", "A rounded glass pill that hovers above the page."),
        Option(
            COMPACT,
            "Always compact",
            "The small icon-only pill, kept small — it never expands."
        ),
        Option(CLASSIC, "Classic", "Edge-to-edge, flush with the bottom of the screen."),
    )

    /** Maps a stored preference onto a layout this build can draw. The old
     *  "borderless" value is upgraded to the animated bar rather than dropped,
     *  so anyone who had picked it gets the new look instead of being reset. */
    fun normalize(key: String): String = when (key) {
        CLASSIC, FLOATING, ANIMATED, COMPACT -> key
        "borderless" -> ANIMATED
        else -> ANIMATED
    }

    fun labelOf(key: String): String =
        ALL.firstOrNull { it.key == normalize(key) }?.label ?: ALL.first().label
}

object BarMetrics {
    /** The seamless layout's plate. */
    val classicHeight = 50.dp
    val fullHeight = 54.dp
    val midHeight = 46.dp
    /** Gap between the bar and the bottom of the screen — and, with [hPad], the
     *  room around the pill on every side, which is what makes it float over
     *  the page instead of spanning it. */
    val margin = 8.dp
    val midMargin = 6.dp

    fun inset(style: String): Dp = when (style) {
        NavStyles.CLASSIC -> classicHeight
        NavStyles.COMPACT -> midHeight + midMargin * 2
        else -> fullHeight + margin * 2
    }
}

val LocalTaskbarInset = compositionLocalOf { 0.dp }

@Composable
private fun AppBottomBar(
    currentRoute: String?,
    hidden: Set<String>,
    navStyle: String = NavStyles.ANIMATED,
    expanded: Boolean = true,
    showLabels: Boolean = true,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val tabs = BottomTabs.filter { it.route !in hidden }.ifEmpty { BottomTabs }
    val glass = rememberGlassTokens()
    val style = NavStyles.normalize(navStyle)
    val animatedShrunk = (style == NavStyles.ANIMATED && !expanded) || style == NavStyles.COMPACT
    // Classic is the only edge-to-edge layout; the other two float over the
    // page. A floating bar is TRANSLUCENT and rounded on every edge, so the
    // page (and the artwork scrolling behind it) still shows through — and it
    // never sits in a strip of its own.
    val floating = style != NavStyles.CLASSIC
    val withLabels = showLabels && !animatedShrunk
    val edgeToEdge = style == NavStyles.CLASSIC
    // One cached text measurer, used below to size the labels to the width
    // they actually have (see the comment on the Row).
    val measurer = rememberTextMeasurer()
    val hPad by animateDpAsState(
        when {
            edgeToEdge -> 0.dp
            style == NavStyles.FLOATING -> 14.dp
            animatedShrunk -> 32.dp
            else -> 12.dp
        },
        label = "barHPad",
    )
    val vPad by animateDpAsState(
        when {
            edgeToEdge -> 0.dp
            animatedShrunk -> BarMetrics.midMargin
            else -> BarMetrics.margin
        },
        label = "barVPad",
    )
    val radius by animateDpAsState(
        // Fully round ends: [BarMetrics.fullHeight] and [BarMetrics.midHeight]
        // halved, so the pill is a stadium at both sizes.
        when {
            edgeToEdge -> 0.dp
            animatedShrunk -> BarMetrics.midHeight / 2
            else -> BarMetrics.fullHeight / 2
        },
        label = "barRadius",
    )
    val barHeight by animateDpAsState(
        when {
            edgeToEdge -> BarMetrics.classicHeight
            animatedShrunk -> BarMetrics.midHeight
            else -> BarMetrics.fullHeight
        },
        label = "barHeight",
    )
    val labelHeight by animateDpAsState(if (withLabels) 15.dp else 0.dp, label = "barLabelH")
    val labelAlpha by animateFloatAsState(if (withLabels) 1f else 0f, label = "barLabelA")
    // The button cell fills the bar's inner height (the Row below insets itself
    // by 5dp top and bottom, and the label is drawn inside this cell), so the
    // icon and its name always have the room the bar itself claims. The 5dp
    // frame is the same one the bar has always worn; at [BarMetrics.fullHeight]
    // it now leaves the cell real space above and below its contents, which is
    // what stops the icons from looking squeezed against the pill's edges.
    val tabHeight = (barHeight - 10.dp)
    val iconSize by animateDpAsState(
        if (edgeToEdge) {
            22.dp
        } else {
            20.dp
        },
        label = "barIcon",
    )
    val selectedIndex = tabs.indexOfFirst { it.route == currentRoute }
    var dragPos by remember { mutableStateOf<Float?>(null) }
    var settleIdx by remember { mutableStateOf<Int?>(null) }
    var pressed by remember { mutableStateOf(false) }
    LaunchedEffect(selectedIndex) { settleIdx = null }
    LaunchedEffect(settleIdx) {
        if (settleIdx != null) {
            delay(700)
            settleIdx = null
        }
    }
    val lensTarget = (dragPos ?: settleIdx?.toFloat() ?: selectedIndex.toFloat()).coerceAtLeast(0f)
    val lensIdx by animateFloatAsState(
        targetValue = lensTarget,
        animationSpec = if (dragPos != null) {
            spring(dampingRatio = 1f, stiffness = Spring.StiffnessHigh)
        } else {
            spring(dampingRatio = 0.62f, stiffness = 380f)
        },
        label = "barLensIdx",
    )
    val lensVisible = selectedIndex >= 0 || dragPos != null || settleIdx != null
    val lensAlpha by animateFloatAsState(if (lensVisible) 1f else 0f, label = "barLensAlpha")
    val press by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium),
        label = "barLensPress",
    )
    val tabsNow by rememberUpdatedState(tabs)
    val selectedNow by rememberUpdatedState(selectedIndex)
    val navigateNow by rememberUpdatedState(onNavigate)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = hPad, vertical = vPad)
    ) {
        Column {
            if (edgeToEdge) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(glass.border))
            }
            Surface(
                shape = RoundedCornerShape(radius),
                color = if (edgeToEdge) {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                } else {
                    MaterialTheme.colorScheme.surface.copy(alpha = if (glass.dark) 0.92f else 0.95f)
                },
                border = if (floating) BorderStroke(1.dp, glass.border) else null,
                shadowElevation = if (floating && !glass.dark) 8.dp else 0.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                BoxWithConstraints(
                    Modifier
                        .fillMaxWidth()
                        .then(
                            if (floating && glass.dark) {
                                Modifier.background(
                                    Brush.verticalGradient(listOf(glass.fillTop, glass.fillBottom))
                                )
                            } else {
                                Modifier
                            }
                        )
                ) {
                    val slotDp = maxWidth / tabs.size
                    val availDp = (slotDp - 8.dp).coerceAtLeast(12.dp)
                    val densityNow = LocalDensity.current
                    val availPx = with(densityNow) { availDp.toPx() }
                    val labelStyle = LocalTextStyle.current.copy(
                        letterSpacing = 0.sp,
                        // The incoming body style's line height would make a
                        // 10sp label occupy an 18dp line box and push the icon
                        // off centre; the font's own metrics are what we want.
                        lineHeight = TextUnit.Unspecified,
                    )
                    val tabLabels = tabs.map { tr(it.label) }
                    val widestRef = remember(tabLabels, labelStyle, densityNow.density, densityNow.fontScale) {
                        val reference = labelStyle.copy(fontSize = 100.sp, fontWeight = FontWeight.Bold)
                        tabLabels.maxOfOrNull { label ->
                            measurer.measure(
                                text = AnnotatedString(label),
                                style = reference,
                                maxLines = 1,
                                softWrap = false,
                                constraints = Constraints(maxWidth = 100000),
                            ).size.width
                        } ?: 0
                    }
                    val labelSp = if (widestRef <= 0) {
                        9f
                    } else {
                        val fontScale = densityNow.fontScale.coerceAtLeast(0.5f)
                        // 0.97 leaves a hair of slack in the slot, so the text
                        // never sits edge to edge in its cell.
                        (availPx * 0.97f * 100f * fontScale / widestRef).coerceIn(6f, 11.5f)
                    }
                    val labelScale = densityNow.fontScale.coerceAtLeast(0.5f)
                    // The glass lens, drawn BEHIND the buttons. Its lane is the
                    // same width as one equal-weight button slot of the Row below.
                    val laneDp = (maxWidth - 4.dp) / tabs.size
                    val lanePx = with(densityNow) { laneDp.toPx() }
                    val lensPadPx = with(densityNow) { 2.dp.toPx() }
                    val lensTopPx = with(densityNow) { 5.dp.toPx() }
                    val lensShape = RoundedCornerShape(50)
                    val lensTravel = abs(lensTarget - lensIdx).coerceAtMost(1.5f)
                    val lensSheen = if (glass.dark) Color.White else primary
                    val lensFillTop = lensSheen.copy(alpha = 0.14f + 0.12f * press)
                    val lensFillBottom = primary.copy(alpha = 0.10f + 0.10f * press)
                    val lensEdgeTop = lensSheen.copy(alpha = 0.40f)
                    val lensEdgeBottom = lensSheen.copy(alpha = 0.06f)
                    Box(
                        Modifier
                            .offset {
                                IntOffset(
                                    (lensPadPx + lanePx * lensIdx).roundToInt(),
                                    lensTopPx.roundToInt()
                                )
                            }
                            .size(laneDp, tabHeight)
                            .graphicsLayer {
                                scaleX = (1f + 0.16f * press) * (1f + 0.10f * lensTravel)
                                scaleY = (1f + 0.14f * press) * (1f - 0.03f * lensTravel)
                                alpha = lensAlpha
                            }
                            .clip(lensShape)
                            .background(Brush.verticalGradient(listOf(lensFillTop, lensFillBottom)))
                            .border(
                                1.dp,
                                Brush.verticalGradient(listOf(lensEdgeTop, lensEdgeBottom)),
                                lensShape
                            )
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(barHeight)
                            // Observes the touch in the Initial pass WITHOUT
                            // consuming it, so a plain tap still reaches the
                            // button's own click; only a drag is handled here.
                            .pointerInput(tabs.size) {
                                val padPx = 2.dp.toPx()
                                val slop = viewConfiguration.touchSlop
                                awaitEachGesture {
                                    val down = awaitFirstDown(
                                        requireUnconsumed = false,
                                        pass = PointerEventPass.Initial
                                    )
                                    val count = tabsNow.size
                                    val slotPx = ((size.width - padPx * 2f) / count).coerceAtLeast(1f)
                                    val maxIdx = (count - 1).toFloat()
                                    var dragged = false
                                    pressed = true
                                    dragPos = (((down.position.x - padPx) / slotPx) - 0.5f).coerceIn(0f, maxIdx)
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!change.pressed) break
                                        if (!dragged && (change.position - down.position).getDistance() > slop) {
                                            dragged = true
                                        }
                                        dragPos = (((change.position.x - padPx) / slotPx) - 0.5f).coerceIn(0f, maxIdx)
                                    }
                                    val target = (dragPos ?: 0f).roundToInt().coerceIn(0, count - 1)
                                    dragPos = null
                                    pressed = false
                                    settleIdx = target
                                    if (dragged && target != selectedNow) {
                                        navigateNow(tabsNow[target].route)
                                    }
                                }
                            }
                            .padding(horizontal = 2.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        tabs.forEachIndexed { index, tab ->
                            // 1 when the lens is right on this button, 0 once it
                            // is a whole slot away. Drives colour and magnify.
                            val near = (1f - abs(lensIdx - index)).coerceIn(0f, 1f) * lensAlpha
                            val selected = near > 0.5f
                            val tabTint = androidx.compose.ui.graphics.lerp(muted, primary, near)
                            val iconZoom = 1f + near * (0.06f + 0.22f * press)
                            val textZoom = 1f + near * (0.03f + 0.10f * press)
                            Column(
                                Modifier
                                    .weight(1f)
                                    .height(tabHeight)
                                    .clickable(
                                        interactionSource = remember(tab.route) { MutableInteractionSource() },
                                        indication = null
                                    ) { onNavigate(tab.route) },
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    tab.icon,
                                    contentDescription = if (withLabels) null else tr(tab.label),
                                    modifier = Modifier
                                        .size(iconSize)
                                        .graphicsLayer {
                                            scaleX = iconZoom
                                            scaleY = iconZoom
                                        },
                                    tint = tabTint
                                )
                                if (labelHeight > 0.dp) {
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        tr(tab.label),
                                        style = labelStyle,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis,
                                        fontSize = (labelSp / labelScale).sp,
                                        letterSpacing = 0.sp,
                                        textAlign = TextAlign.Center,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                        color = tabTint,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .alpha(labelAlpha)
                                            .graphicsLayer {
                                                scaleX = textZoom
                                                scaleY = textZoom
                                            }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One button on the floating bottom bar. Public so Settings can render a
 *  switch per tab (Settings → App Layout → Taskbar buttons) from the very same
 *  list the bar draws, instead of a copy that could drift out of step. */
data class BottomTab(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

val BottomTabs = listOf(
    BottomTab(Routes.HOME, "Home", Icons.Filled.Home),
    BottomTab(Routes.ANIME_CALENDAR, "Anime", Icons.Filled.CalendarMonth),
    BottomTab(Routes.SEARCH, "Search", Icons.Filled.Search),
    BottomTab(Routes.LIBRARY, "My Stuff", Icons.Filled.VideoLibrary),
    BottomTab(Routes.MANGA, "Manga", Icons.Filled.AutoStories),
    BottomTab(Routes.IPTV, "IPTV", Icons.Filled.LiveTv),
    // Stats is the other off-by-default tab (Settings → Taskbar buttons): a
    // page about what you have already watched, not something to browse to.
    BottomTab(Routes.STATS, "Stats", Icons.Filled.BarChart),
    BottomTab(Routes.TELEGRAM, "Telegram", Icons.Filled.Send),
    BottomTab(Routes.EXTENSIONS, "Extensions", Icons.Filled.Extension),
    BottomTab(Routes.SETTINGS, "Settings", Icons.Filled.Settings),
)

@Composable
fun AppRoot(themeKey: String = HikariThemeMode.DEFAULT.key) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    // Scoped search lives on "provider-search?q=…" but belongs to the Search
    // tab (so the bar shows and Search highlights); everything else matches on
    // its base path.
    val tabRoute = Routes.tabBaseOf(currentRoute)
    val showBar = tabRoute in BottomTabs.map { it.route }

    // The WebView's "Go to app home" menu item bumps this — landing on the
    // app's own Home tab (not the website's home page).
    val context = LocalContext.current
    val app = context.applicationContext as HikariApp
    val homeRequest by app.homeTabRequest.collectAsState()
    val repoLinkRequest by app.repoLinkRequest.collectAsState()
    // Which taskbar buttons to draw (Settings → App Layout → Taskbar buttons).
    val hiddenTabsFlow = remember { app.store.hiddenTabsFlow() }
    val hiddenTabs by hiddenTabsFlow.collectAsState(initial = emptySet())
    val iptvTabFlow = remember { app.store.iptvTabFlow() }
    val iptvTabOn by iptvTabFlow.collectAsState(initial = false)
    val mangaTabFlow = remember { app.store.mangaTabFlow() }
    val mangaTabOn by mangaTabFlow.collectAsState(initial = true)
    // ...and neither is Stats (AppStore.statsTabFlow): it is a page about what
    // the user has already watched, and the taskbar is for getting somewhere.
    val statsTabFlow = remember { app.store.statsTabFlow() }
    val statsTabOn by statsTabFlow.collectAsState(initial = false)
    val telegramTabFlow = remember { app.store.telegramTabFlow() }
    val telegramTabOn by telegramTabFlow.collectAsState(initial = true)
    val visibleTabs: Set<String> = buildSet {
        addAll(hiddenTabs)
        if (!iptvTabOn) add(Routes.IPTV)
        if (!mangaTabOn) add(Routes.MANGA)
        if (!statsTabOn) add(Routes.STATS)
        if (!telegramTabOn) add(Routes.TELEGRAM)
    }
    // How the bar itself is drawn (Settings → App Layout → Taskbar & navigation).
    val navStyleFlow = remember { app.store.navStyleFlow() }
    val navStyle by navStyleFlow.collectAsState(initial = NavStyles.ANIMATED)
    // Whether the bar writes each button's name under its icon (Settings → App
    // Layout → "Show text on taskbar buttons").
    val tabLabelsFlow = remember { app.store.tabLabelsFlow() }
    val showTabLabels by tabLabelsFlow.collectAsState(initial = true)
    val fullscreenOffFlow = remember { app.store.fullscreenOffFlow() }
    val fullscreenOff by fullscreenOffFlow.collectAsState(initial = false)
    val isTv = TvMode.current()
    // The gap kept clear of the screen's edges on a television ("overscan" —
    // see TvUi.DEFAULT_OVERSCAN_DP for why televisions need one at all).
    val overscanFlow = remember { app.store.tvOverscanFlow() }
    val overscanDp by overscanFlow.collectAsState(initial = TvUi.DEFAULT_OVERSCAN_DP)
    val tvEdge = if (isTv) overscanDp.dp else 0.dp
    // The animated layout's two states. A page's own scrolling drives it: the
    // bar rests at its full, labelled size and eases into a smaller floating
    // pill as the user scrolls back up towards the top of a page (swiping up is
    // the gesture that "pulls the page down" over the bar); scrolling down into
    // the content — or opening another tab — swells it back to full size. The
    // connection sits on the Box that wraps the whole app, so every screen's
    // list feeds it without a single screen having to pass its scroll state up.
    var barExpanded by remember { mutableStateOf(true) }
    val barScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val dy = available.y
                // A few dp of slack: a fling's first event can be tiny, and the
                // bar flipping on a one-pixel jitter looks broken.
                //
                // Scrolling DOWN into the page (dy > 0) is what fills the bar
                // out to its full, labelled size; scrolling back UP (dy < 0)
                // lets it draw in to the floating pill. That is the reference
                // client's feel: the bar gets out of the way of the content you
                // are moving towards, and comes back as a full bar the moment
                // you head further down.
                if (dy > 8f) barExpanded = true
                else if (dy < -8f) barExpanded = false
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(tabRoute) { barExpanded = true }
    // Consume-once: keyed ONLY on the request counter, never on the tab.
    // The old (homeRequest, tabRoute) keys re-fired on every tab switch, so
    // a stale request bounced a later Manga/any-tab tap straight back Home;
    // each counter value is now handled at most once.
    var homeHandled by remember { mutableStateOf(0) }
    LaunchedEffect(homeRequest) {
        // The home request is emitted by WebViewActivity's explicit "Go to app
        // home" action. Extension installation can briefly create/finish a
        // WebView-backed activity on some plugin paths; never let that global
        // request tear the user out of the Extensions tab while an extension
        // repo/install flow is active. Consumed after handling so a stale
        // request can never bounce a later repo tap back to Home.
        if (homeRequest > 0 && homeRequest != homeHandled) {
            homeHandled = homeRequest
            app.homeTabRequest.value = 0
            if (tabRoute != Routes.EXTENSIONS) {
                Routes.navigateTab(nav, Routes.HOME)
            }
        }
    }
    LaunchedEffect(repoLinkRequest, tabRoute) {
        if (!repoLinkRequest.isNullOrBlank() && tabRoute != Routes.EXTENSIONS) {
            Routes.navigateTab(nav, Routes.EXTENSIONS)
        }
    }

    val mouseView = LocalView.current
    val mouseDrive = Modifier.onPreviewKeyEvent { event ->
        if (!com.hikari.app.tv.MouseMode.enabled) return@onPreviewKeyEvent false
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val step = com.hikari.app.tv.MouseMode.STEP
        when (event.key) {
            Key.DirectionUp -> { com.hikari.app.tv.MouseMode.move(0f, -step); true }
            Key.DirectionDown -> { com.hikari.app.tv.MouseMode.move(0f, step); true }
            Key.DirectionLeft -> { com.hikari.app.tv.MouseMode.move(-step, 0f); true }
            Key.DirectionRight -> { com.hikari.app.tv.MouseMode.move(step, 0f); true }
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                if (event.nativeKeyEvent.repeatCount != 0) return@onPreviewKeyEvent true
                com.hikari.app.tv.MouseMode.clickAt(mouseView)
                true
            }
            else -> false
        }
    }

    val tvRepeatGuard = Modifier.onPreviewKeyEvent { event ->
        if (!TvMode.isTv) return@onPreviewKeyEvent false
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        if (event.nativeKeyEvent.repeatCount == 0) return@onPreviewKeyEvent false
        when (event.key) {
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> true
            else -> false
        }
    }

    Box(Modifier.fillMaxSize().nestedScroll(barScroll).padding(tvEdge).then(mouseDrive).then(tvRepeatGuard)) {
        // The page backdrop, drawn here rather than by the Scaffold so the
        // translucent cards have something to be glass OVER (the Scaffold below
        // is transparent for exactly that reason).
        //
        //  * Dark Glass UI — the accent-tinted gradient that the theme is named
        //    for, behind every frosted panel.
        //  * Hikari Dark — the near-black page plus one soft accent glow across
        //    the top third. A flat page made the translucent cards look like
        //    grey boxes; a lit top is what makes the same cards read as glass,
        //    and it keeps the page dim where the eye actually reads.
        //  * AMOLED — nothing. The theme's whole point is a pixel that is off.
        //  * Light — nothing; the flat paper background is the design.
        val scheme = MaterialTheme.colorScheme
        val accent = scheme.primary
        Box(Modifier.fillMaxSize().background(scheme.background))
        when (HikariThemeMode.fromKey(themeKey)) {
            HikariThemeMode.GLASS -> {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    androidx.compose.ui.graphics.lerp(
                                        Color(0xFF120E1F), accent, 0.38f
                                    ),
                                    androidx.compose.ui.graphics.lerp(
                                        Color(0xFF151A33), accent, 0.16f
                                    ),
                                    Color(0xFF0B0E1A),
                                ),
                                start = Offset.Zero,
                                end = Offset.Infinite,
                            )
                        )
                )
            }
            HikariThemeMode.DARK -> {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to androidx.compose.ui.graphics.lerp(
                                    scheme.background, accent, 0.20f
                                ),
                                0.34f to androidx.compose.ui.graphics.lerp(
                                    scheme.background, accent, 0.06f
                                ),
                                0.75f to scheme.background,
                                1f to scheme.background,
                            )
                        )
                )
            }
            else -> Unit
        }
        // The user's own picture (Settings -> Customize -> Custom background):
        // drawn over the theme backdrop, under the transparent Scaffold. Draws
        // nothing until a picture is chosen.
        com.hikari.app.ui.CustomBackgroundLayer()
        Scaffold(
            // Transparent: the backdrop above is the page, so the cards' glass
            // has something to sit on (see the comment on the backdrop).
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onBackground,
            // The app is edge-to-edge/immersive (MainActivity hides the system
            // bars), so the Scaffold must NOT pad the content down by the status
            // bar inset. It used to: on any device where the bars were showing,
            // every screen started ~a status-bar lower with an empty band above
            // it (the reported "blank bar in the status bar area" on Home and on
            // "Show All"). Screens now draw from y=0; the floating bottom bar
            // lifts itself above the navigation bar instead.
            //
            // With "Turn off full screen app mode" the bars ARE on screen, so
            // the top inset comes back; the bottom is left to the bar itself
            // (see the navigationBars padding on the NavHost below). A
            // television is always immersive, so that preference is ignored
            // there (its card is hidden on a TV too — see MainActivity).
            contentWindowInsets = if (fullscreenOff && !isTv) {
                WindowInsets.statusBars
            } else {
                WindowInsets(0, 0, 0, 0)
            },
            // No `bottomBar` slot on purpose. A slot RESERVES the bar's height,
            // so every page ended in a strip of the page background with the bar
            // drawn inside it — on the dark themes that strip is black, which is
            // what made the bottom of the screen look like a black band around
            // the bar in all three layouts. The bar is drawn over the page
            // instead (see below), so content scrolls behind it and the glass
            // has something to be glass over; screens keep their last row clear
            // of it with [LocalTaskbarInset].
        ) { padding ->
            CompositionLocalProvider(
                LocalTaskbarInset provides if (showBar && !isTv) {
                    val navBarBottom = with(LocalDensity.current) {
                        WindowInsets.navigationBars.getBottom(this).toDp()
                    }
                    BarMetrics.inset(navStyle) + navBarBottom
                } else 0.dp
            ) {
                NavHost(
                    navController = nav,
                    startDestination = Routes.HOME,
                    modifier = Modifier
                        .padding(padding)
                        // A page with no bottom bar (a detail page, a grid)
                        // would otherwise end under the three buttons; the bar
                        // itself already lifts above them.
                        .then(
                            if (fullscreenOff && !showBar && !isTv) {
                                Modifier.windowInsetsPadding(WindowInsets.navigationBars)
                            } else {
                                Modifier
                            }
                        )
                        .then(
                            if (isTv && showBar && tabRoute != Routes.HOME) Modifier.padding(start = TvUi.railWidth()) else Modifier
                        )
                ) {
            composable(Routes.HOME) { HomeScreen(nav) }
            composable(Routes.ANIME_CALENDAR) { AnimeCalendarScreen(nav) }
            composable(Routes.SEARCH) { SearchScreen(nav) }
            composable(
                route = Routes.SEARCH_QUERY,
                arguments = listOf(
                    navArgument("q") { type = NavType.StringType; defaultValue = "" },
                    navArgument("provider") { type = NavType.StringType; defaultValue = "" },
                )
            ) { entry ->
                val q = Uri.decode(entry.arguments?.getString("q").orEmpty())
                val provider = Uri.decode(entry.arguments?.getString("provider").orEmpty())
                SearchScreen(nav, initialQuery = q, initialProvider = provider)
            }
            composable(Routes.LIBRARY) { MyStuffScreen(nav, MyStuff.LIBRARY) }
            composable(
                route = Routes.TRACKER_SHELF,
                arguments = listOf(
                    navArgument("source") { type = NavType.StringType; defaultValue = "" },
                    navArgument("key") { type = NavType.StringType; defaultValue = "" },
                    navArgument("title") { type = NavType.StringType; defaultValue = "" },
                    navArgument("filter") { type = NavType.StringType; defaultValue = "" },
                )
            ) { entry ->
                val tSource = Uri.decode(entry.arguments?.getString("source").orEmpty())
                val tKey = Uri.decode(entry.arguments?.getString("key").orEmpty())
                val tTitle = Uri.decode(entry.arguments?.getString("title").orEmpty())
                val tFilter = Uri.decode(entry.arguments?.getString("filter").orEmpty())
                TrackerShelfScreen(nav, tSource, tKey, tTitle, tFilter)
            }
            composable(Routes.HISTORY) { MyStuffScreen(nav, MyStuff.HISTORY) }
            composable(Routes.DOWNLOADS) { MyStuffScreen(nav, MyStuff.DOWNLOADS) }
            composable(Routes.IPTV) { LiveTvHomeScreen(nav) }
            composable(Routes.IPTV_LISTS) { IptvScreen(nav) }
            composable(Routes.LIVETV_SETTINGS) { LiveTvSettingsScreen(nav) }
            composable(Routes.LIVETV_GUIDE) { LiveTvGuideScreen(nav) }
            composable(Routes.LIVETV_MANAGE) { LiveTvManageScreen(nav) }
            composable(Routes.LIVETV_ONDEMAND) { LiveTvOnDemandScreen(nav) }
            composable(Routes.LIVETV_RECORDINGS) { LiveTvRecordingsScreen(nav) }
            composable(Routes.LIVETV_SPORTS) { LiveTvSportsScreen(nav) }
            composable(Routes.LIVETV_CALENDAR) { LiveTvCalendarScreen(nav) }
            composable(
                route = Routes.IPTV_PLAYLIST,
                arguments = listOf(
                    navArgument("pid") { type = NavType.StringType },
                )
            ) { entry ->
                val pid = Uri.decode(entry.arguments?.getString("pid").orEmpty())
                IptvPlaylistScreen(nav, pid)
            }
            composable(Routes.EXTENSIONS) { ExtensionsScreen(nav) }
            composable(Routes.WEBSITES) { WebSitesScreen(nav) }
            // The Telegram tab (off by default — see Routes.TELEGRAM): the public
            // channels the user added, played in this app's own player.
            composable(Routes.TELEGRAM) { TelegramScreen(nav) }
            // ---- Manga (the off-by-default tab, its detail page and reader) ----
            composable(Routes.MANGA) { MangaScreen(nav) }
            composable(
                route = Routes.MANGA_SEARCH,
                arguments = listOf(
                    navArgument("q") { type = NavType.StringType; defaultValue = "" },
                    navArgument("genre") { type = NavType.StringType; defaultValue = "" },
                    navArgument("engine") { type = NavType.StringType; defaultValue = "" },
                )
            ) { entry ->
                val q = Uri.decode(entry.arguments?.getString("q").orEmpty())
                val g = Uri.decode(entry.arguments?.getString("genre").orEmpty())
                val e = Uri.decode(entry.arguments?.getString("engine").orEmpty())
                MangaScreen(nav, initialQuery = q, initialGenre = g, initialEngine = e)
            }
            composable(
                route = Routes.MANGA_DETAIL,
                arguments = listOf(
                    navArgument("providerId") { type = NavType.StringType },
                    navArgument("url") { type = NavType.StringType },
                    navArgument("title") { type = NavType.StringType; defaultValue = "" },
                    navArgument("poster") { type = NavType.StringType; defaultValue = "" },
                )
            ) { entry ->
                val providerId = entry.arguments?.getString("providerId").orEmpty()
                val url = Uri.decode(entry.arguments?.getString("url").orEmpty())
                val title = Uri.decode(entry.arguments?.getString("title").orEmpty())
                val poster = Uri.decode(entry.arguments?.getString("poster").orEmpty())
                MangaDetailScreen(nav, providerId, url, title, poster)
            }
            composable(
                route = Routes.MANGA_READER,
                arguments = listOf(
                    navArgument("providerId") { type = NavType.StringType },
                    navArgument("url") { type = NavType.StringType },
                    navArgument("chapter") { type = NavType.StringType },
                    navArgument("title") { type = NavType.StringType; defaultValue = "" },
                    navArgument("poster") { type = NavType.StringType; defaultValue = "" },
                )
            ) { entry ->
                val providerId = entry.arguments?.getString("providerId").orEmpty()
                val url = Uri.decode(entry.arguments?.getString("url").orEmpty())
                val chapter = Uri.decode(entry.arguments?.getString("chapter").orEmpty())
                val title = Uri.decode(entry.arguments?.getString("title").orEmpty())
                val poster = Uri.decode(entry.arguments?.getString("poster").orEmpty())
                MangaReaderScreen(nav, providerId, url, chapter, title, poster)
            }
            composable(
                route = Routes.NOVEL_READER,
                arguments = listOf(
                    navArgument("providerId") { type = NavType.StringType },
                    navArgument("url") { type = NavType.StringType },
                    navArgument("chapter") { type = NavType.StringType },
                    navArgument("title") { type = NavType.StringType; defaultValue = "" },
                    navArgument("poster") { type = NavType.StringType; defaultValue = "" },
                )
            ) { entry ->
                val providerId = entry.arguments?.getString("providerId").orEmpty()
                val url = Uri.decode(entry.arguments?.getString("url").orEmpty())
                val chapter = Uri.decode(entry.arguments?.getString("chapter").orEmpty())
                val title = Uri.decode(entry.arguments?.getString("title").orEmpty())
                val poster = Uri.decode(entry.arguments?.getString("poster").orEmpty())
                NovelReaderScreen(nav, providerId, url, chapter, title, poster)
            }
            composable(
                route = Routes.SCRAPER_READER,
                arguments = listOf(
                    navArgument("key") { type = NavType.StringType },
                    navArgument("title") { type = NavType.StringType; defaultValue = "" },
                )
            ) { entry ->
                val key = entry.arguments?.getString("key").orEmpty()
                val title = Uri.decode(entry.arguments?.getString("title").orEmpty())
                ScraperReaderScreen(nav, key, title)
            }
            composable(Routes.SETTINGS) { SettingsScreen(nav) }
            composable(Routes.STATS) {
                StatsScreen(
                    app,
                    onOpenTitle = { t ->
                        Routes.fromStatsKey(t.key, t.title, t.posterUrl)?.let { nav.navigate(it) }
                    },
                )
            }
            composable(Routes.COLLECTIONS) {
                CollectionsScreen(nav, onBack = { nav.popBackStack() })
            }
            composable(
                route = Routes.COLLECTION_VIEW,
                arguments = listOf(
                    navArgument("cid") { type = NavType.StringType },
                    navArgument("fid") { type = NavType.StringType; defaultValue = "" },
                )
            ) { entry ->
                val cid = Uri.decode(entry.arguments?.getString("cid").orEmpty())
                val fid = Uri.decode(entry.arguments?.getString("fid").orEmpty())
                CollectionViewScreen(nav, cid, fid)
            }
            composable(
                route = Routes.COLLECTION_GRID,
                arguments = listOf(
                    navArgument("cid") { type = NavType.StringType },
                )
            ) { entry ->
                val cid = Uri.decode(entry.arguments?.getString("cid").orEmpty())
                CollectionGridScreen(nav, cid)
            }
            composable(
                route = Routes.TMDB_GRID,
                arguments = listOf(
                    navArgument("preset") { type = NavType.StringType },
                    navArgument("title") { type = NavType.StringType; defaultValue = "" },
                )
            ) { entry ->
                val preset = Uri.decode(entry.arguments?.getString("preset").orEmpty())
                val title = Uri.decode(entry.arguments?.getString("title").orEmpty())
                TmdbGridScreen(nav, preset, title)
            }
            composable(
                route = Routes.TMDB_GRID_SPEC,
                arguments = listOf(
                    navArgument("spec") { type = NavType.StringType; defaultValue = "" },
                    navArgument("title") { type = NavType.StringType; defaultValue = "" },
                )
            ) { entry ->
                val spec = Uri.decode(entry.arguments?.getString("spec").orEmpty())
                val title = Uri.decode(entry.arguments?.getString("title").orEmpty())
                TmdbGridScreen(nav, "", title, spec)
            }
            composable(
                route = Routes.CATALOG,
                arguments = listOf(
                    navArgument("providerId") { type = NavType.StringType },
                    navArgument("catalogId") { type = NavType.StringType },
                    navArgument("title") { type = NavType.StringType; defaultValue = "" },
                    navArgument("providerName") { type = NavType.StringType; defaultValue = "" },
                    navArgument("type") { type = NavType.StringType; defaultValue = "UNKNOWN" },
                    navArgument("rawType") { type = NavType.StringType; defaultValue = "" },
                )
            ) { entry ->
                val providerId = Uri.decode(entry.arguments?.getString("providerId").orEmpty())
                val catalogId = Uri.decode(entry.arguments?.getString("catalogId").orEmpty())
                val title = Uri.decode(entry.arguments?.getString("title").orEmpty())
                val providerName = Uri.decode(entry.arguments?.getString("providerName").orEmpty())
                val type = runCatching {
                    MediaType.valueOf(entry.arguments?.getString("type").orEmpty())
                }.getOrDefault(MediaType.UNKNOWN)
                val rawType = Uri.decode(entry.arguments?.getString("rawType").orEmpty())
                CatalogScreen(nav, providerId, catalogId, title, providerName, type, rawType)
            }
            composable(
                route = Routes.DETAIL,
                arguments = listOf(
                    navArgument("providerId") { type = NavType.StringType },
                    navArgument("type") { type = NavType.StringType },
                    navArgument("mediaId") { type = NavType.StringType },
                    navArgument("title") { type = NavType.StringType; defaultValue = "" },
                    navArgument("poster") { type = NavType.StringType; defaultValue = "" },
                    navArgument("rawType") { type = NavType.StringType; defaultValue = "" },
                    navArgument("episodeId") { type = NavType.StringType; defaultValue = "" },
                    navArgument("startPos") { type = NavType.StringType; defaultValue = "0" },
                    navArgument("autoplay") { type = NavType.StringType; defaultValue = "0" },
                )
            ) { entry ->
                val providerId = entry.arguments?.getString("providerId").orEmpty()
                val type = runCatching {
                    MediaType.valueOf(entry.arguments?.getString("type").orEmpty())
                }.getOrDefault(MediaType.UNKNOWN)
                val mediaId = Uri.decode(entry.arguments?.getString("mediaId").orEmpty())
                val title = Uri.decode(entry.arguments?.getString("title").orEmpty())
                val poster = Uri.decode(entry.arguments?.getString("poster").orEmpty()).ifBlank { null }
                val rawType = Uri.decode(entry.arguments?.getString("rawType").orEmpty())
                val episodeId = Uri.decode(entry.arguments?.getString("episodeId").orEmpty())
                val startPos = entry.arguments?.getString("startPos")?.toLongOrNull() ?: 0L
                val autoplay = entry.arguments?.getString("autoplay") == "1"
                DetailScreen(nav, providerId, type, mediaId, title, poster, rawType, episodeId, startPos, autoplay)
            }
                }
            }
        }
        if (showBar && !isTv) {
            AppBottomBar(
                currentRoute = tabRoute,
                hidden = visibleTabs,
                navStyle = navStyle,
                expanded = barExpanded,
                showLabels = showTabLabels,
                onNavigate = { route -> Routes.navigateTab(nav, route) },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        if (showBar && isTv) {
            TvNavRail(
                currentRoute = tabRoute,
                hidden = visibleTabs,
                onNavigate = { route -> Routes.navigateTab(nav, route) },
                modifier = Modifier.align(Alignment.CenterStart),
                isScrolling = !barExpanded,
            )
        }
        TvMouseCursorOverlay()
    }
}
