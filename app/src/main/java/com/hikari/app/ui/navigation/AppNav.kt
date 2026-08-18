package com.hikari.app.ui.navigation

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.hikari.app.data.MediaType
import com.hikari.app.ui.screens.DetailScreen
import com.hikari.app.ui.screens.ExtensionsScreen
import com.hikari.app.ui.screens.HomeScreen
import com.hikari.app.ui.screens.SearchScreen
import com.hikari.app.ui.screens.SettingsScreen
import com.hikari.app.ui.theme.HikariThemeMode

object Routes {
    const val HOME = "home"
    const val SEARCH = "search"
    const val EXTENSIONS = "extensions"
    const val SETTINGS = "settings"
    // All args live in the query string: mediaIds are URLs (slashes would break
    // a path segment) and posters can be megabytes of base64 (see detail()).
    const val DETAIL = "detail?providerId={providerId}&type={type}&mediaId={mediaId}&title={title}&poster={poster}&rawType={rawType}"

    fun detail(
        providerId: String,
        type: MediaType,
        mediaId: String,
        title: String,
        posterUrl: String? = null,
        rawType: String = "",
    ): String {
        var s = "detail?providerId=${Uri.encode(providerId)}&type=${Uri.encode(type.name)}&mediaId=${Uri.encode(mediaId)}&title=${Uri.encode(title)}"
        // MRDS/51CG posters are decrypted into huge data: URIs — dropping them
        // from the route keeps the NavController from exploding on a monster
        // deep link. The detail page re-fetches the poster via /meta anyway.
        val poster = posterUrl?.takeIf { it.isNotBlank() && !it.startsWith("data:") && it.length <= 600 }
        if (poster != null) s += "&poster=${Uri.encode(poster)}"
        if (rawType.isNotBlank()) s += "&rawType=${Uri.encode(rawType)}"
        return s
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
    Tab(Routes.EXTENSIONS, "Extensions", Icons.Filled.Extension),
    Tab(Routes.SETTINGS, "Settings", Icons.Filled.Settings),
)

@Composable
fun AppRoot(themeKey: String = HikariThemeMode.DARK.key) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBar = currentRoute in Tabs.map { it.route }

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
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    Tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) }
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
            composable(Routes.EXTENSIONS) { ExtensionsScreen() }
            composable(Routes.SETTINGS) { SettingsScreen() }
            composable(
                route = Routes.DETAIL,
                arguments = listOf(
                    navArgument("providerId") { type = NavType.StringType },
                    navArgument("type") { type = NavType.StringType },
                    navArgument("mediaId") { type = NavType.StringType },
                    navArgument("title") { type = NavType.StringType; defaultValue = "" },
                    navArgument("poster") { type = NavType.StringType; defaultValue = "" },
                    navArgument("rawType") { type = NavType.StringType; defaultValue = "" },
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
                DetailScreen(nav, providerId, type, mediaId, title, poster, rawType)
            }
        }
        }
    }
}
