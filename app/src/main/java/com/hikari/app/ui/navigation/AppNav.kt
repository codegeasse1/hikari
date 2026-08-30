package com.hikari.app.ui.navigation

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
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
import com.hikari.app.ui.screens.ExtensionsScreen
import com.hikari.app.ui.screens.HistoryScreen
import com.hikari.app.ui.screens.HomeScreen
import com.hikari.app.ui.screens.SearchScreen
import com.hikari.app.ui.screens.SettingsScreen
import com.hikari.app.ui.theme.HikariThemeMode
import androidx.compose.runtime.collectAsState

object Routes {
    const val HOME = "home"
    const val SEARCH = "search"
    const val EXTENSIONS = "extensions"
    const val SETTINGS = "settings"
    const val HISTORY = "history"
    // Same Search screen, but pre-filled with a query (genre tags, "show all",
    // search suggestions…). A separate route (not "search?q=" on the tab route)
    // so the query string carries through nav without clobbering the tab's own
    // remembered state; the tab bar matches it by stripping the query.
    const val SEARCH_QUERY = "search?q={q}"
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
    fun searchQuery(q: String): String = "search?q=${Uri.encode(q)}"

    /** navigate() that can never crash the app on a malformed route — some
     *  extensions return titles/ids that trip up the route parser, and one
     *  junk item must not be able to kill the whole app. */
    fun safeNavigate(nav: NavHostController, route: String) {
        runCatching { nav.navigate(route) }
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
    Tab(Routes.HISTORY, "History", Icons.Filled.History),
    Tab(Routes.EXTENSIONS, "Extensions", Icons.Filled.Extension),
    Tab(Routes.SETTINGS, "Settings", Icons.Filled.Settings),
)

@Composable
fun AppRoot(themeKey: String = HikariThemeMode.DARK.key) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    // SEARCH_QUERY is "search?q=…" — strip the query so the tab still matches.
    val tabRoute = currentRoute?.substringBefore('?')
    val showBar = tabRoute in Tabs.map { it.route }

    // The WebView's "Go to app home" menu item bumps this — landing on the
    // app's own Home tab (not the website's home page).
    val context = LocalContext.current
    val homeRequest by (context.applicationContext as HikariApp).homeTabRequest.collectAsState()
    LaunchedEffect(homeRequest) {
        if (homeRequest > 0) {
            nav.navigate(Routes.HOME) {
                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        // Dark Glass UI backdrop — a vivid gradient sits behind the translucent
        // surfaces so cards/nav bar read as frosted glass. Solid themes draw
        // nothing (the Scaffold's background color covers it).
        if (themeKey == HikariThemeMode.GLASS.key) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                Color(0xFF2A1440),
                                Color(0xFF1B2A4A),
                                Color(0xFF0B0E1A),
                            ),
                            start = Offset.Zero,
                            end = Offset.Infinite,
                        )
                    )
            )
        }
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showBar) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    // Slimmer bar so the five tabs fit comfortably and every
                    // label shows in full on narrow phones.
                    modifier = Modifier.height(62.dp),
                ) {
                    Tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = tabRoute == tab.route,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = {
                                Text(
                                    tab.label,
                                    // Force the label onto a single line with no
                                    // ellipsis — the default wraps long tab
                                    // names (e.g. "Extensions") onto a second
                                    // line on narrow phones.
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Clip,
                                    fontSize = 9.sp,
                                )
                            }
                        )
                    }
                }
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
                arguments = listOf(navArgument("q") { type = NavType.StringType; defaultValue = "" })
            ) { entry ->
                val q = Uri.decode(entry.arguments?.getString("q").orEmpty())
                SearchScreen(nav, initialQuery = q)
            }
            composable(Routes.HISTORY) { HistoryScreen(nav) }
            composable(Routes.EXTENSIONS) { ExtensionsScreen() }
            composable(Routes.SETTINGS) { SettingsScreen() }
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
