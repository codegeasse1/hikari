package com.hikari.app.ui.navigation
import com.hikari.app.i18n.tr

import android.net.Uri
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Search
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
import com.hikari.app.ui.screens.CatalogScreen
import com.hikari.app.ui.screens.CollectionGridScreen
import com.hikari.app.ui.screens.CollectionViewScreen
import com.hikari.app.ui.screens.CollectionsScreen
import com.hikari.app.ui.screens.DetailScreen
import com.hikari.app.ui.screens.DownloadsScreen
import com.hikari.app.ui.screens.ExtensionsScreen
import com.hikari.app.ui.screens.HistoryScreen
import com.hikari.app.ui.screens.HomeScreen
import com.hikari.app.ui.screens.IptvPlaylistScreen
import com.hikari.app.ui.screens.IptvScreen
import com.hikari.app.ui.screens.LibraryScreen
import com.hikari.app.ui.screens.MangaDetailScreen
import com.hikari.app.ui.screens.MangaReaderScreen
import com.hikari.app.ui.screens.MangaScreen
import com.hikari.app.ui.screens.MyStuff
import com.hikari.app.ui.screens.MyStuffScreen
import com.hikari.app.ui.screens.SearchScreen
import com.hikari.app.ui.screens.SettingsScreen
import com.hikari.app.ui.screens.StatsScreen
import com.hikari.app.ui.screens.TmdbGridScreen
import com.hikari.app.ui.theme.HikariThemeMode
import com.hikari.app.ui.theme.rememberGlassTokens
import com.hikari.app.tv.TvMode
import com.hikari.app.tv.TvNavRail
import com.hikari.app.tv.TvUi
import androidx.compose.runtime.collectAsState

object Routes {
    const val HOME = "home"
    const val SEARCH = "search"
    const val EXTENSIONS = "extensions"
    const val SETTINGS = "settings"
    const val HISTORY = "history"
    const val DOWNLOADS = "downloads"
    /** Titles saved with the player's heart (the favourites store). */
    const val LIBRARY = "library"
    /**
     * The IPTV tab. Hidden by default — it appears in the taskbar only once the
     * user switches it on in Settings → Taskbar buttons (see
     * [com.hikari.app.data.AppStore.iptvTabFlow]), because most installs have no
     * playlist at all and an eighth button costs every one of them room.
     */
    const val IPTV = "iptv"
    /** One playlist's own page: its groups, each opening a paged channel grid. */
    const val IPTV_PLAYLIST = "iptv-playlist?pid={pid}"

    /**
     * The Manga tab — the same kind of extra page as [IPTV]: OFF by default and
     * switched on in Settings → Taskbar buttons (see
     * [com.hikari.app.data.AppStore.mangaTabFlow]), because an install with no
     * manga engine has no use for it and a button costs every install room.
     */
    const val MANGA = "manga"
    /** One manga: cover, description, follow button, chapter list. */
    const val MANGA_DETAIL =
        "manga-detail?providerId={providerId}&url={url}&title={title}&poster={poster}"
    /**
     * The reader. A destination of its own rather than a mode of the detail
     * screen, so the system back button returns to the chapter list, the page
     * survives a rotation, and the whole page list travels in the route exactly
     * the way a video's episode does.
     */
    const val MANGA_READER =
        "manga-reader?providerId={providerId}&url={url}&chapter={chapter}&title={title}&poster={poster}"

    /**
     * The Stats tab: what the user watched and read, and for how long (see
     * [com.hikari.app.ui.screens.StatsScreen]). OFF by default and switched on
     * in Settings → Taskbar buttons, like [IPTV] and [MANGA] — it is a page a
     * user goes looking for rather than one every install needs a button for,
     * and the Settings index has its own door to it either way.
     */
    const val STATS = "stats"

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

    /** Opens one IPTV playlist's group list. */
    fun iptvPlaylist(providerId: String): String =
        "iptv-playlist?pid=${Uri.encode(providerId)}"
    /**
     * Same Search screen, but pre-filled with a query (genre tags, "show all",
     * search suggestions…) and/or scoped to one provider (Home's "Search this
     * extension" entry point).
     *
     * It deliberately uses a DIFFERENT base path ("provider-search", not
     * "search?q="). With the shared "search" base the NavController treated the
     * scoped destination as the same route family as the Search tab, and the
     * bottom bar's save/restore-tab navigation stopped working after landing
     * here — tapping Home did nothing. The tab bar maps this base back to the
     * Search tab via [tabBaseOf] so the bar stays visible and highlights
     * correctly.
     */
    const val SEARCH_QUERY = "provider-search?q={q}&provider={provider}"
    /** Base path of [SEARCH_QUERY] — used to map the scoped route onto the
     *  Search tab for the bottom bar. */
    const val SEARCH_QUERY_BASE = "provider-search"
    // All args live in the query string: mediaIds are URLs (slashes would break
    // a path segment) and posters can be megabytes of base64 (see detail()).
    const val DETAIL = "detail?providerId={providerId}&type={type}&mediaId={mediaId}&title={title}&poster={poster}&rawType={rawType}&episodeId={episodeId}&startPos={startPos}"
    // "Show All" catalog browser: every item of one provider catalog, paged.
    const val CATALOG = "catalog?providerId={providerId}&catalogId={catalogId}&title={title}&providerName={providerName}&type={type}&rawType={rawType}"
    /**
     * Collections: the manager (Settings → Personal Catalog creator), one
     * collection's page (folders, or one folder's catalogs when `fid` is set),
     * and one TMDB preset as a full grid. Kept as real destinations so the
     * system back button walks the folder hierarchy and the player can open on
     * top of any of them.
     */
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
    ): String {
        // Free-text titles are sanitized: some extensions return junk (control
        // chars, the literal "null") that can trip up the route parser and
        // crash navigation with "Wrong argument type for 'title'".
        val safeTitle = title.replace(Regex("[\\p{Cc}\\u2028\\u2029]"), " ")
            .trim().take(500)
        var s = "detail?providerId=${Uri.encode(providerId)}&type=${Uri.encode(type.name)}&mediaId=${Uri.encode(mediaId)}&title=${Uri.encode(safeTitle)}"
        // MRDS/51CG posters are decrypted into huge data: URIs — dropping them
        // from the route keeps the NavController from exploding on a monster
        // deep link. The detail page re-fetches the poster via /meta anyway.
        val poster = posterUrl?.takeIf { it.isNotBlank() && !it.startsWith("data:") && it.length <= 600 }
        if (poster != null) s += "&poster=${Uri.encode(poster)}"
        if (rawType.isNotBlank()) s += "&rawType=${Uri.encode(rawType)}"
        if (episodeId.isNotBlank()) s += "&episodeId=${Uri.encode(episodeId)}"
        if (startPositionMs > 0L) s += "&startPos=$startPositionMs"
        return s
    }

    /** Opens the Search tab with a pre-filled query (e.g. a genre tag). */
    fun searchQuery(q: String): String = "$SEARCH_QUERY_BASE?q=${Uri.encode(q)}&provider="

    /** Opens the Search tab with the query scoped to one provider — Home's
     *  "Search this extension" entry point. */
    fun searchInProvider(providerId: String, q: String = ""): String =
        "$SEARCH_QUERY_BASE?q=${Uri.encode(q)}&provider=${Uri.encode(providerId)}"

    /**
     * Marks a PERSONAL CATALOG (a saved collection) in a provider-scoped search
     * — the same "collection:<id>" key Home stores its picks under, so one
     * string carries either kind of scope through the Search route.
     *
     * The Search tab's provider row lists these beside the extensions (a
     * collection is a place a title can be looked for, which is exactly what
     * that row means), and both entry points use it: Home's header search while
     * a collection is picked, and the magnifier on a catalog page.
     */
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
            // History and Downloads are sections of the ONE merged taskbar
            // button ("My Stuff"), so they highlight it and keep the bar on
            // screen exactly as the scoped search route highlights Search.
            HISTORY, DOWNLOADS -> LIBRARY
            else -> base
        }
    }

    /** Navigate the bottom bar reliably. A real back-stack pop is tried first,
     *  so tapping a tab from a scoped/query route always lands on that tab
     *  (the save/restore-tab navigate used to silently no-op after arrival at
     *  the scoped search destination). Falls back to a normal tab switch. */
    fun navigateTab(nav: NavHostController, route: String) {
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


/**
 * The bottom bar's four looks (Settings → App Layout → Taskbar & navigation).
 *
 *  * [ANIMATED] — a small floating pill; as the user scrolls back up towards the
 *    top of a page it draws itself in — narrower, shorter, and with the button
 *    names set aside so only the icons are left — and settles back out on any
 *    downward scroll (or a tab change). This is the default layout.
 *  * [FLOATING] — the detached glass pill, always the same size.
 *  * [COMPACT] — the drawn-in pill, permanently: the animated layout's small
 *    state without the animation. For anyone who wants the taskbar out of the
 *    way for good, with the whole screen taller.
 *  * [CLASSIC] — the seamless edge-to-edge plate: opaque, flush with the bottom
 *    of the screen, closed off by a hairline along its top edge.
 *
 * All of them float over the page (except the seamless plate, which IS the bottom
 * of the page); the three floating ones are deliberately SMALL — a compact pill
 * with room around it, not a full navigation bar stretched across the screen —
 * and they are nearly opaque, because a bar over artwork cannot rely on what is
 * behind it being dim.
 * The looks used to be indistinguishable in practice: "classic" and the old
 * "borderless" both drew radius 0, no border and no elevation, and differed
 * only in a plate colour that matches the page background on the dark and AMOLED
 * themes — so picking either looked like nothing had changed. Classic is now an
 * opaque tonal plate with its own top divider, and the third layout is the
 * animated one the reference client uses instead of a flat borderless bar.
 */
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

/**
 * The bar's numbers in one place, because two of them have to agree: the size
 * the bar draws itself at, and the room a page leaves at the bottom for it
 * (see [BarMetrics.inset]).
 */
object BarMetrics {
    /** The seamless layout's plate. */
    val classicHeight = 50.dp
    /** The floating bars' resting height. The bar is very nearly as wide as
     *  the screen, so its height is what decides whether it reads as a capsule
     *  or as a sliver: at 46dp over a 360dp screen it was a 1:8 strip and the
     *  icons and their names had almost no room above or below them. 54dp gives
     *  the same proportion the reference bar wears — measured off a screenshot
     *  of it, its height is a seventh of its length — while staying clearly
     *  shorter than a full navigation bar, so it still reads as a small pill
     *  floating over the artwork rather than as a band across the page. */
    val fullHeight = 54.dp
    /** The animation layout's size once the user scrolls back up. This is a real
     *  step down, not a nudge: at the resting size the drawn-in bar was the same
     *  width, the same height and wore the same labels as the plain floating
     *  bar, so the animation could not be seen at all. Here the bar also drops
     *  its button names and pulls its ends in (see AppBottomBar), leaving the
     *  compact icon pill the reference client scrolls with — still a stadium
     *  capsule with the same icons, so it is plainly the same bar. It sits a
     *  step under the resting pill rather than half its size, so the two states
     *  are the same bar seen smaller — not two different bars. */
    val midHeight = 46.dp
    /** Gap between the bar and the bottom of the screen — and, with [hPad], the
     *  room around the pill on every side, which is what makes it float over
     *  the page instead of spanning it. */
    val margin = 8.dp
    val midMargin = 6.dp

    /**
     * What a tab page must leave clear at the bottom of its scrolling content,
     * so the last row can be scrolled out from under the bar. The bar itself
     * floats OVER the page (it reserves no strip of its own, which is what made
     * the bottom of every screen look like a black band with a bar sitting in
     * it), so this is padding inside the scroll container, not screen layout.
     *
     * The resting size is used for both animation states so the page does not
     * re-pad itself every time the bar grows a few dp while scrolling.
     */
    fun inset(style: String): Dp = when (style) {
        NavStyles.CLASSIC -> classicHeight
        NavStyles.COMPACT -> midHeight + midMargin * 2
        else -> fullHeight + margin * 2
    }
}

/**
 * The room the bottom taskbar covers on a tab page. Screens add it to the
 * bottom padding of their scrolling content; it is 0 on every page that has no
 * bar (a detail page, a grid, a settings sub-page outside the tabs).
 */
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
    // Only the buttons the user kept, in their fixed order (Settings → App
    // Layout → Taskbar & navigation). Hiding one re-flows the remaining slots
    // instead of leaving a gap. The bar can never end up empty: if the stored
    // set somehow covers every tab, the full list comes back.
    val tabs = BottomTabs.filter { it.route !in hidden }.ifEmpty { BottomTabs }
    val glass = rememberGlassTokens()
    val style = NavStyles.normalize(navStyle)
    // The animated layout is the same bar in two states: the full, labelled bar
    // the app rests on, and a smaller floating pill it draws back into as the
    // user scrolls up towards the top of a page (see [AppRoot], which tracks
    // the scroll direction). Everything below is hoisted so the two states
    // animate between rather than swap.
    //
    // The difference between the states is a step the eye can follow: a smaller
    // pill, drawn in on every side, that keeps the icons and lays the names
    // aside. It stays a stadium capsule of the same bar — the sliver this used
    // to shrink to lost its ends, its names and most of its size, which is why
    // it stopped reading as the taskbar at all.
    val animatedShrunk = (style == NavStyles.ANIMATED && !expanded) || style == NavStyles.COMPACT
    // Classic is the only edge-to-edge layout; the other two float over the
    // page. A floating bar is TRANSLUCENT and rounded on every edge, so the
    // page (and the artwork scrolling behind it) still shows through — and it
    // never sits in a strip of its own.
    val floating = style != NavStyles.CLASSIC
    // The labels can be switched off wholesale (Settings → App Layout → "Show
    // text on taskbar buttons"), and the drawn-in animated bar lays them aside
    // by itself: dropping the names is most of what makes the scrolled state
    // read as small, and the label height animates to zero with them, so the
    // pill really is shorter rather than just emptier. [labelHeight] and
    // [labelAlpha] below both follow this one flag.
    val withLabels = showLabels && !animatedShrunk
    val edgeToEdge = style == NavStyles.CLASSIC
    // One cached text measurer, used below to size the labels to the width
    // they actually have (see the comment on the Row).
    val measurer = rememberTextMeasurer()
    val hPad by animateDpAsState(
        when {
            edgeToEdge -> 0.dp
            // The floating styles keep a real margin either side: that gap is
            // what makes the bar read as a pill floating over the page rather
            // than a band across it.
            style == NavStyles.FLOATING -> 14.dp
            // Shrunk, the pill pulls its edges in hard — this, with the smaller
            // height and the dropped labels, is what makes the bar read as a
            // small capsule floating over the page rather than as the full bar.
            animatedShrunk -> 32.dp
            else -> 12.dp
        },
        label = "barHPad",
    )
    // The gap between the bar and the edge of the screen (and the content). A
    // floating bar keeps only a small margin: the bigger it got, the more the
    // bar looked like it was sitting in a band of its own rather than floating
    // over the page.
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
            // One icon size for both floating states: the drawn-in bar is the
            // same bar smaller, and it shrinks by dropping the names and pulling
            // its frame in (see [hPad] and [labelHeight]) — the icons themselves
            // stay the size the user is used to, so the taskbar never looks like
            // a different control after a scroll.
            20.dp
        },
        label = "barIcon",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            // Keep the floating bar clear of the gesture/navigation bar when the
            // system bars are visible (they are hidden while immersive, so this
            // is 0 in the normal case and simply lifts the bar when they show).
            // The seamless layout only keeps that inset — its plate is flush
            // with the screen edges on purpose.
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = hPad, vertical = vPad)
    ) {
        Column {
            // Classic's plate is opaque and tonally lighter than the page, so
            // its top edge needs the hairline that separates a toolbar from the
            // content scrolling under it (the plate colour alone disappeared
            // against the dark and AMOLED page backgrounds).
            if (edgeToEdge) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(glass.border))
            }
            Surface(
                shape = RoundedCornerShape(radius),
                // A floating bar is GLASS — but it is the one panel in the app
                // that floats over ARTWORK, so it cannot be a whisper: the page
                // behind it may be a bright poster, and the old white-whisper
                // fill let whatever it happened to be sitting on decide whether
                // the icons and their names could be read at all. This is the
                // theme's own surface colour at nearly full strength — a dark
                // frosted panel on the dark themes, a light one on the light
                // theme — with the same hairline edge and soft top-light every
                // other glass panel in the app carries, so it still reads as
                // glass rather than as paint. Only the seamless layout is
                // opaque, because there the plate IS the bottom of the screen.
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
                        // The glass sheen (see [rememberGlassTokens]): a touch
                        // more light along the top edge than the bottom. On the
                        // light theme the tokens are opaque white — that is the
                        // panel colour there — so the sheen is only drawn on
                        // the dark side, where it is the subtle falloff that
                        // keeps the pill from looking like flat paint.
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
                    // Equal slots are not much room, and the labels
                    // ("Downloads", "Extensions") are the longest text in the
                    // app: on a narrow screen, or with the accessibility Font
                    // size / Display size turned up, they overran their slots
                    // and butted straight up against each other ("Extensions"
                    // running into "Downloads", tails cut off). Rather than
                    // guess a size, MEASURE the widest label and scale the font
                    // so it fits its slot with a gutter either side. The result
                    // is divided by the font scale when it is drawn, so the
                    // label always renders at this computed size no matter what
                    // the phone's font-size setting is — it can never grow out
                    // of its slot. (With "In-app UI scale" on, fontScale is 1
                    // and the scale rides on the density, so the labels still
                    // scale with that setting.)
                    val slotDp = maxWidth / tabs.size
                    val availDp = (slotDp - 8.dp).coerceAtLeast(12.dp)
                    val densityNow = LocalDensity.current
                    val availPx = with(densityNow) { availDp.toPx() }
                    // The style the label is actually drawn with, taken from
                    // the ambient typography: the app font (Settings → Appearance & Theme → App font)
                    // and the theme's own metrics are part of the width, and
                    // measuring with a bare TextStyle missed them — which is
                    // exactly how a label "fit" on paper and still came out
                    // clipped on the device.
                    //
                    // Letter spacing is pinned to zero for the labels: the
                    // incoming body style carries half a point per character,
                    // and over nine characters that is most of the budget we
                    // are trying to fit into.
                    val labelStyle = LocalTextStyle.current.copy(
                        letterSpacing = 0.sp,
                        // The incoming body style's line height would make a
                        // 10sp label occupy an 18dp line box and push the icon
                        // off centre; the font's own metrics are what we want.
                        lineHeight = TextUnit.Unspecified,
                    )
                    // Text width is linear in font size, so one measurement of
                    // the widest label at a 100sp reference gives the size that
                    // just fits the slot. Bold is the wider weight (the active
                    // tab), so measuring with it is the safe case.
                    //
                    // The translated labels are read here, in composition,
                    // because tr() is @Composable and cannot be called from the
                    // remember lambda that does the measuring.
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
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(barHeight)
                            .padding(horizontal = 2.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        tabs.forEach { tab ->
                            val selected = currentRoute == tab.route
                            Column(
                                Modifier
                                    .weight(1f)
                                    .height(tabHeight)
                                    // A fully round pill, not a rounded square — that
                                    // is what marks the active tab in the reference
                                    // design.
                                    .clip(RoundedCornerShape(50))
                                    .background(if (selected) primary.copy(alpha = 0.18f) else Color.Transparent)
                                    .clickable { onNavigate(tab.route) },
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    tab.icon,
                                    contentDescription = if (withLabels) null else tr(tab.label),
                                    modifier = Modifier.size(iconSize),
                                    tint = if (selected) primary else muted
                                )
                                if (labelHeight > 0.dp) {
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        tr(tab.label),
                                        // The very style the size above was
                                        // measured from, so what fits on paper
                                        // is what is drawn.
                                        style = labelStyle,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis,
                                        fontSize = (labelSp / labelScale).sp,
                                        letterSpacing = 0.sp,
                                        textAlign = TextAlign.Center,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (selected) primary else muted,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .alpha(labelAlpha)
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
    BottomTab(Routes.SEARCH, "Search", Icons.Filled.Search),
    // Library + History + Downloads are ONE slot: they are all "my stuff"
    // (saved titles, what you played, what you saved offline) rather than
    // "find something to watch", and three buttons for them left the taskbar
    // with no room for the manga reader. The three are a small segmented strip
    // at the top of this page (see [com.hikari.app.ui.screens.MyStuffScreen]),
    // and each keeps its own route so every existing link still lands right.
    BottomTab(Routes.LIBRARY, "My Stuff", Icons.Filled.VideoLibrary),
    // Manga sits beside it as its own tab, OFF by default (Settings → Taskbar
    // buttons) — the same deal as IPTV. It is a whole reading surface (browse,
    // follow, read) rather than "something to watch", so it does not belong in
    // the merged My Stuff slot even though the two were designed together.
    BottomTab(Routes.MANGA, "Manga", Icons.Filled.AutoStories),
    BottomTab(Routes.IPTV, "IPTV", Icons.Filled.LiveTv),
    // Stats is the other off-by-default tab (Settings → Taskbar buttons): a
    // page about what you have already watched, not something to browse to.
    BottomTab(Routes.STATS, "Stats", Icons.Filled.BarChart),
    BottomTab(Routes.EXTENSIONS, "Extensions", Icons.Filled.Extension),
    BottomTab(Routes.SETTINGS, "Settings", Icons.Filled.Settings),
)

@Composable
fun AppRoot(themeKey: String = HikariThemeMode.DARK.key) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    // Scoped search lives on "provider-search?q=…" but belongs to the Search
    // tab (so the bar shows and Search highlights); everything else matches on
    // its base path.
    val tabRoute = Routes.tabBaseOf(currentRoute)
    // The bar still shows on a tab whose button the user hid (they can be
    // standing on it via an in-app link, and the bar is how they leave), so
    // this stays keyed on every tab, not just the visible ones.
    val showBar = tabRoute in BottomTabs.map { it.route }

    // The WebView's "Go to app home" menu item bumps this — landing on the
    // app's own Home tab (not the website's home page).
    val context = LocalContext.current
    val app = context.applicationContext as HikariApp
    val homeRequest by app.homeTabRequest.collectAsState()
    // Which taskbar buttons to draw (Settings → App Layout → Taskbar buttons).
    val hiddenTabsFlow = remember { app.store.hiddenTabsFlow() }
    val hiddenTabs by hiddenTabsFlow.collectAsState(initial = emptySet())
    // The IPTV button has its OWN switch and is OFF by default (see
    // AppStore.iptvTabFlow): an install with no playlist should not carry an
    // eighth button, and "off unless asked for" cannot be expressed as an entry
    // in the hidden-tabs list, which only the user can write. Feeding it into
    // the same set the bar filters on keeps every other part of the taskbar (the
    // rail on a TV, the "never empty" rule) working unchanged.
    val iptvTabFlow = remember { app.store.iptvTabFlow() }
    val iptvTabOn by iptvTabFlow.collectAsState(initial = false)
    // ...and neither is Manga (AppStore.mangaTabFlow) — most installs have no
    // manga engine, so its button is off until the user asks for it.
    val mangaTabFlow = remember { app.store.mangaTabFlow() }
    val mangaTabOn by mangaTabFlow.collectAsState(initial = false)
    // ...and neither is Stats (AppStore.statsTabFlow): it is a page about what
    // the user has already watched, and the taskbar is for getting somewhere.
    val statsTabFlow = remember { app.store.statsTabFlow() }
    val statsTabOn by statsTabFlow.collectAsState(initial = false)
    val visibleTabs: Set<String> = buildSet {
        addAll(hiddenTabs)
        if (!iptvTabOn) add(Routes.IPTV)
        if (!mangaTabOn) add(Routes.MANGA)
        if (!statsTabOn) add(Routes.STATS)
    }
    // How the bar itself is drawn (Settings → App Layout → Taskbar & navigation).
    val navStyleFlow = remember { app.store.navStyleFlow() }
    val navStyle by navStyleFlow.collectAsState(initial = NavStyles.ANIMATED)
    // Whether the bar writes each button's name under its icon (Settings → App
    // Layout → "Show text on taskbar buttons").
    val tabLabelsFlow = remember { app.store.tabLabelsFlow() }
    val showTabLabels by tabLabelsFlow.collectAsState(initial = true)
    // Whether the phone's own status/navigation bars are visible (Settings →
    // App Layout → "Turn off full screen app mode"). The window stays
    // edge-to-edge either way, so with the bars shown the pages must pad
    // themselves by the reported insets (MainActivity.show(systemBars()) is
    // what actually brings the bars back).
    val fullscreenOffFlow = remember { app.store.fullscreenOffFlow() }
    val fullscreenOff by fullscreenOffFlow.collectAsState(initial = false)
    // ---- Television (one APK, two layouts — see com.hikari.app.tv.TvMode) ----
    //
    // Subscribes to the user's choice, so switching "This device is a TV" in
    // Settings re-lays-out the app on the spot. What changes on a television:
    // the navigation rail instead of the taskbar, a wider poster, everything
    // padded in from the screen edges (see TvUi), and the focus ring every
    // clickable inherits from MainActivity's TvFocusProvider.
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
    LaunchedEffect(homeRequest) {
        if (homeRequest > 0) Routes.navigateTab(nav, Routes.HOME)
    }

    Box(Modifier.fillMaxSize().nestedScroll(barScroll).padding(tvEdge)) {
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
        Scaffold(
            // Transparent: the backdrop above is the page, so the cards' glass
            // has something to sit on (see the comment on the backdrop).
            containerColor = Color.Transparent,
            // Material's Scaffold wraps its body in a Surface carrying
            // `contentColorFor(containerColor)` — for a transparent container
            // that resolves to `Color.Unspecified`, which let every Text with no
            // explicit colour fall back to black (invisible on the dark, glass
            // and AMOLED themes: settings folder names, catalog headings, ...).
            // Pinning it to the theme's on-background colour fixes them all.
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
                // The bar's own footprint (see [BarMetrics.inset]) PLUS the room
                // it keeps clear of the system navigation bar when those bars
                // are on screen: the bar pads itself by that inset too, so a
                // page that padded only by the bar's height would still end up
                // under it. In immersive mode — the default, and what the whole
                // design assumes — that inset is 0 and this is just the bar.
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
                        // On a television every page starts to the right of the
                        // navigation rail, which is what the rail is: the room
                        // the tab strip takes from the page (the same job the
                        // taskbar inset does at the bottom on a phone).
                        .then(
                            if (isTv) Modifier.padding(start = TvUi.RAIL_WIDTH) else Modifier
                        )
                ) {
            composable(Routes.HOME) { HomeScreen(nav) }
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
            // The three sections of the ONE merged taskbar button: each route
            // opens the same screen on its own section (see MyStuffScreen), so
            // every existing link to History/Downloads still lands correctly.
            composable(Routes.LIBRARY) { MyStuffScreen(nav, MyStuff.LIBRARY) }
            composable(Routes.HISTORY) { MyStuffScreen(nav, MyStuff.HISTORY) }
            composable(Routes.DOWNLOADS) { MyStuffScreen(nav, MyStuff.DOWNLOADS) }
            composable(Routes.IPTV) { IptvScreen(nav) }
            composable(
                route = Routes.IPTV_PLAYLIST,
                arguments = listOf(
                    navArgument("pid") { type = NavType.StringType },
                )
            ) { entry ->
                val pid = Uri.decode(entry.arguments?.getString("pid").orEmpty())
                IptvPlaylistScreen(nav, pid)
            }
            composable(Routes.EXTENSIONS) { ExtensionsScreen() }
            // ---- Manga (the off-by-default tab, its detail page and reader) ----
            composable(Routes.MANGA) { MangaScreen(nav) }
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
            composable(Routes.SETTINGS) { SettingsScreen(nav) }
            composable(Routes.STATS) { StatsScreen(app) }
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
                DetailScreen(nav, providerId, type, mediaId, title, poster, rawType, episodeId, startPos)
            }
                }
            }
        }
        // The bar, drawn over the page rather than in a reserved slot (see the
        // comment on the Scaffold). It is the last child of the Box, so it sits
        // on top of every screen, and it is transparent wherever the pill is not
        // — nothing around it is painted.
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
        // On a television the tabs live in a rail down the left edge instead
        // (see TvNavRail for why the platform does it that way), and it is
        // where the remote's focus starts.
        if (showBar && isTv) {
            TvNavRail(
                currentRoute = tabRoute,
                hidden = visibleTabs,
                onNavigate = { route -> Routes.navigateTab(nav, route) },
                modifier = Modifier.align(Alignment.CenterStart),
            )
        }
    }
}
