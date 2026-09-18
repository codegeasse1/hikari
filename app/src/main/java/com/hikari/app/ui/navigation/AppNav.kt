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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.hikari.app.ui.screens.LibraryScreen
import com.hikari.app.ui.screens.SearchScreen
import com.hikari.app.ui.screens.SettingsScreen
import com.hikari.app.ui.screens.TmdbGridScreen
import com.hikari.app.ui.theme.HikariThemeMode
import com.hikari.app.ui.theme.rememberGlassTokens
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
     * Collections: the manager (Settings → Appearance → Collections), one
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

    /** Maps a full Compose-Navigation route onto the bottom-bar tab it belongs
     *  to (strips the query string; folds the scoped search route back onto the
     *  Search tab). Returns null when the route isn't a tab. */
    fun tabBaseOf(route: String?): String? {
        val base = route?.substringBefore('?') ?: return null
        return if (base == SEARCH_QUERY_BASE) SEARCH else base
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
 * The bottom bar's three looks (Settings → App Layout → Taskbar & navigation).
 *
 *  * [ANIMATED] — the full-width, labelled bar the app rests on; it shrinks
 *    into a small icon-only pill as the user scrolls back up towards the top
 *    of a page, and swells back to the full bar on any downward scroll (or a
 *    tab change). This is the default layout.
 *  * [FLOATING] — the detached glass pill, always the same size.
 *  * [CLASSIC] — the seamless edge-to-edge plate: opaque, flush with the bottom
 *    of the screen, closed off by a hairline along its top edge.
 *
 * The three used to be indistinguishable in practice: "classic" and the old
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

    data class Option(val key: String, val label: String, val blurb: String)

    val ALL = listOf(
        Option(
            ANIMATED,
            "Floating animation",
            "A full bar at the top of a page that shrinks into a floating pill as you scroll."
        ),
        Option(FLOATING, "Floating", "A rounded glass pill that hovers above the page."),
        Option(CLASSIC, "Classic", "Edge-to-edge, flush with the bottom of the screen."),
    )

    /** Maps a stored preference onto a layout this build can draw. The old
     *  "borderless" value is upgraded to the animated bar rather than dropped,
     *  so anyone who had picked it gets the new look instead of being reset. */
    fun normalize(key: String): String = when (key) {
        CLASSIC, FLOATING, ANIMATED -> key
        "borderless" -> ANIMATED
        else -> ANIMATED
    }

    fun labelOf(key: String): String =
        ALL.firstOrNull { it.key == normalize(key) }?.label ?: ALL.first().label
}

@Composable
private fun AppBottomBar(
    currentRoute: String?,
    hidden: Set<String>,
    navStyle: String = NavStyles.ANIMATED,
    expanded: Boolean = true,
    showLabels: Boolean = true,
    onNavigate: (String) -> Unit,
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
    // The animated layout is the same bar in two states: the full-width,
    // labelled bar the app rests on, and a smaller icon-only pill it shrinks
    // into as the user scrolls back up towards the top of a page (see
    // [AppRoot], which tracks the scroll direction). Everything below is
    // hoisted so the two states animate between rather than swap.
    val animatedCollapsed = style == NavStyles.ANIMATED && !expanded
    val pill = style == NavStyles.FLOATING || animatedCollapsed
    // The labels can be switched off wholesale (Settings → App Layout → "Show
    // text on taskbar buttons"); the animated bar also drops them on its own
    // while it is shrunk, whichever way that setting points.
    val withLabels = showLabels && !animatedCollapsed
    val edgeToEdge = style == NavStyles.CLASSIC
    // One cached text measurer, used below to size the labels to the width
    // they actually have (see the comment on the Row).
    val measurer = rememberTextMeasurer()
    val hPad by animateDpAsState(
        when {
            edgeToEdge -> 0.dp
            style == NavStyles.FLOATING -> 12.dp
            // Shrunk, the pill pulls its own edges in hard so it reads as a
            // small floating control rather than a half-empty bar.
            animatedCollapsed -> 64.dp
            else -> 8.dp
        },
        label = "barHPad",
    )
    val vPad by animateDpAsState(
        when {
            animatedCollapsed -> 6.dp
            pill -> 8.dp
            else -> 0.dp
        },
        label = "barVPad",
    )
    val radius by animateDpAsState(
        if (animatedCollapsed) 20.dp else if (pill) 26.dp else 0.dp,
        label = "barRadius",
    )
    val barHeight by animateDpAsState(
        when {
            withLabels -> 60.dp
            animatedCollapsed -> 38.dp
            else -> 50.dp
        },
        label = "barHeight",
    )
    val labelHeight by animateDpAsState(if (withLabels) 16.dp else 0.dp, label = "barLabelH")
    val labelAlpha by animateFloatAsState(if (withLabels) 1f else 0f, label = "barLabelA")
    val iconSize by animateDpAsState(
        when {
            withLabels -> 20.dp
            animatedCollapsed -> 16.dp
            else -> 22.dp
        },
        label = "barIcon",
    )
    val tabHeight = when {
        withLabels -> 48.dp
        animatedCollapsed -> 26.dp
        else -> 42.dp
    }
    Box(
        Modifier
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
                color = if (edgeToEdge) {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                } else if (glass.dark) {
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
                } else {
                    MaterialTheme.colorScheme.surface
                },
                border = if (pill) BorderStroke(1.dp, glass.border) else null,
                shadowElevation = if (pill && !glass.dark) 8.dp else 0.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
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
                    val availDp = (slotDp - 6.dp).coerceAtLeast(12.dp)
                    val densityNow = LocalDensity.current
                    val availPx = with(densityNow) { availDp.toPx() }
                    // Text width is linear in font size, so one measurement of
                    // the widest label at a 100sp reference gives the size that
                    // just fits the slot. Bold is the wider weight (the active
                    // tab), so measuring with it is the safe case.
                    // The translated labels are read here, in composition, because
                    // tr() is @Composable and cannot be called from the remember
                    // lambda that does the measuring.
                    val tabLabels = tabs.map { tr(it.label) }
                    val widestRef = remember(tabLabels, densityNow.density, densityNow.fontScale) {
                        val reference = TextStyle(fontSize = 100.sp, fontWeight = FontWeight.Bold)
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
                        (availPx * 100f * fontScale / widestRef).coerceIn(6f, 11.5f)
                    }
                    val labelScale = densityNow.fontScale.coerceAtLeast(0.5f)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(barHeight)
                            .padding(horizontal = 2.dp, vertical = 6.dp),
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
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis,
                                        fontSize = (labelSp / labelScale).sp,
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
 *  switch per tab (Settings → Appearance → Taskbar buttons) from the very same
 *  list the bar draws, instead of a copy that could drift out of step. */
data class BottomTab(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

val BottomTabs = listOf(
    BottomTab(Routes.HOME, "Home", Icons.Filled.Home),
    BottomTab(Routes.SEARCH, "Search", Icons.Filled.Search),
    BottomTab(Routes.LIBRARY, "Library", Icons.Filled.Favorite),
    BottomTab(Routes.HISTORY, "History", Icons.Filled.History),
    BottomTab(Routes.DOWNLOADS, "Downloads", Icons.Filled.Download),
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
    // Which taskbar buttons to draw (Settings → Appearance → Taskbar buttons).
    val hiddenTabsFlow = remember { app.store.hiddenTabsFlow() }
    val hiddenTabs by hiddenTabsFlow.collectAsState(initial = emptySet())
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
    // The animated layout's two states. A page's own scrolling drives it: the
    // bar rests at its full, labelled size and shrinks to a small icon-only pill
    // as the user scrolls back up towards the top of a page (swiping up is the
    // gesture that "pulls the page down" over the bar); scrolling down into the
    // content — or opening another tab — swells it back to full size. The
    // connection sits on the Box that wraps the whole app, so every screen's
    // list feeds it without a single screen having to pass its scroll state up.
    var barExpanded by remember { mutableStateOf(true) }
    val barScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val dy = available.y
                // A few dp of slack: a fling's first event can be tiny, and the
                // bar flipping on a one-pixel jitter looks broken.
                if (dy > 8f) barExpanded = false
                else if (dy < -8f) barExpanded = true
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(tabRoute) { barExpanded = true }
    LaunchedEffect(homeRequest) {
        if (homeRequest > 0) Routes.navigateTab(nav, Routes.HOME)
    }

    Box(Modifier.fillMaxSize().nestedScroll(barScroll)) {
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
            // (see the navigationBars padding on the NavHost below).
            contentWindowInsets = if (fullscreenOff) {
                WindowInsets.statusBars
            } else {
                WindowInsets(0, 0, 0, 0)
            },
        bottomBar = {
            if (showBar) {
                AppBottomBar(
                    currentRoute = tabRoute,
                    hidden = hiddenTabs,
                    navStyle = navStyle,
                    expanded = barExpanded,
                    showLabels = showTabLabels,
                    onNavigate = { route -> Routes.navigateTab(nav, route) }
                )
            }
        }
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Routes.HOME,
            modifier = Modifier
                .padding(padding)
                // A page with no bottom bar (a detail page, a grid) would
                // otherwise end under the three buttons; the bar itself
                // already lifts above them.
                .then(
                    if (fullscreenOff && !showBar) {
                        Modifier.windowInsetsPadding(WindowInsets.navigationBars)
                    } else {
                        Modifier
                    }
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
            composable(Routes.HISTORY) { HistoryScreen(nav) }
            composable(Routes.LIBRARY) { LibraryScreen(nav) }
            composable(Routes.DOWNLOADS) { DownloadsScreen(nav) }
            composable(Routes.EXTENSIONS) { ExtensionsScreen() }
            composable(Routes.SETTINGS) { SettingsScreen(nav) }
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
}
