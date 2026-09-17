package com.hikari.app.ui.navigation
import com.hikari.app.i18n.tr

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import com.hikari.app.ui.screens.DetailScreen
import com.hikari.app.ui.screens.DownloadsScreen
import com.hikari.app.ui.screens.ExtensionsScreen
import com.hikari.app.ui.screens.HistoryScreen
import com.hikari.app.ui.screens.HomeScreen
import com.hikari.app.ui.screens.LibraryScreen
import com.hikari.app.ui.screens.SearchScreen
import com.hikari.app.ui.screens.SettingsScreen
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


@Composable
private fun AppBottomBar(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    // Equal slots are not much room, and the labels ("Downloads", "Extensions")
    // are the longest text in the app. On a phone whose accessibility Font size
    // AND/OR Display size is turned up, the labels grew past their slot and were
    // hard-clipped mid-word ("Downloa"). Size the label so it always renders at
    // the SAME physical size — dividing out the font scale — and the worst case
    // is then a full word in a slightly tight slot. (When "In-app UI scale" is
    // on, fontScale is 1 here and the scale rides on the density, so the labels
    // still scale with that setting.) With the Library tab there are seven
    // slots, so the base size drops a notch to keep every label whole.
    val labelScale = LocalDensity.current.fontScale.coerceAtLeast(0.5f)
    val labelSp = if (Tabs.size > 6) 8f else 9f
    // The bar is a floating glass pill: semi-transparent so the page (and its
    // top glow) shows through, ringed by the same hairline every card uses.
    val glass = rememberGlassTokens()
    Box(
        Modifier
            .fillMaxWidth()
            // Keep the floating bar clear of the gesture/navigation bar when the
            // system bars are visible (they are hidden while immersive, so this
            // is 0 in the normal case and simply lifts the bar when they show).
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = if (glass.dark) {
                MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
            } else {
                MaterialTheme.colorScheme.surface
            },
            border = BorderStroke(1.dp, glass.border),
            shadowElevation = if (glass.dark) 0.dp else 8.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .padding(horizontal = 2.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Tabs.forEach { tab ->
                    val selected = currentRoute == tab.route
                    Column(
                        Modifier
                            .weight(1f)
                            .height(48.dp)
                            // A fully round pill, not a rounded square — that is
                            // what marks the active tab in the reference design.
                            .clip(RoundedCornerShape(50))
                            .background(if (selected) primary.copy(alpha = 0.18f) else Color.Transparent)
                            .clickable { onNavigate(tab.route) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            tab.icon,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = if (selected) primary else muted
                        )
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
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

private data class Tab(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val Tabs = listOf(
    Tab(Routes.HOME, "Home", Icons.Filled.Home),
    Tab(Routes.SEARCH, "Search", Icons.Filled.Search),
    Tab(Routes.LIBRARY, "Library", Icons.Filled.Favorite),
    Tab(Routes.HISTORY, "History", Icons.Filled.History),
    Tab(Routes.DOWNLOADS, "Downloads", Icons.Filled.Download),
    Tab(Routes.EXTENSIONS, "Extensions", Icons.Filled.Extension),
    Tab(Routes.SETTINGS, "Settings", Icons.Filled.Settings),
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
    val showBar = tabRoute in Tabs.map { it.route }

    // The WebView's "Go to app home" menu item bumps this — landing on the
    // app's own Home tab (not the website's home page).
    val context = LocalContext.current
    val homeRequest by (context.applicationContext as HikariApp).homeTabRequest.collectAsState()
    LaunchedEffect(homeRequest) {
        if (homeRequest > 0) Routes.navigateTab(nav, Routes.HOME)
    }

    Box(Modifier.fillMaxSize()) {
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
            // The app is edge-to-edge/immersive (MainActivity hides the system
            // bars), so the Scaffold must NOT pad the content down by the status
            // bar inset. It used to: on any device where the bars were showing,
            // every screen started ~a status-bar lower with an empty band above
            // it (the reported "blank bar in the status bar area" on Home and on
            // "Show All"). Screens now draw from y=0; the floating bottom bar
            // lifts itself above the navigation bar instead.
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBar) {
                AppBottomBar(
                    currentRoute = tabRoute,
                    onNavigate = { route -> Routes.navigateTab(nav, route) }
                )
            }
        }
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(padding)
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
