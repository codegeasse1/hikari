package com.hikari.app.ui.navigation

import android.net.Uri
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

object Routes {
    const val HOME = "home"
    const val SEARCH = "search"
    const val EXTENSIONS = "extensions"
    const val SETTINGS = "settings"
    const val DETAIL = "detail/{providerId}/{type}/{mediaId}?title={title}&poster={poster}&rawType={rawType}"

    fun detail(
        providerId: String,
        type: MediaType,
        mediaId: String,
        title: String,
        posterUrl: String? = null,
        rawType: String = "",
    ): String {
        var s = "detail/$providerId/${type.name}/${Uri.encode(mediaId)}?title=${Uri.encode(title)}"
        if (!posterUrl.isNullOrBlank()) s += "&poster=${Uri.encode(posterUrl)}"
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
fun AppRoot() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBar = currentRoute in Tabs.map { it.route }

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
