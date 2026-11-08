package com.hikari.app.tv

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hikari.app.i18n.tr
import com.hikari.app.ui.navigation.Routes

/**
 * The television navigation rail — the slim, floating icon strip down the left
 * edge (the Nuvio / Apple-TV look): ONLY five destinations.
 *
 *   Search · Home · Schedule · My Stuff · Settings
 *
 * Everything else (Extensions, Manga, IPTV, Telegram, Stats ...) is no longer a
 * rail button on a television. Those pages still exist and still work; they are
 * opened from Settings (see the extra rows in SettingsScreen's television left
 * pane), and while one of them is open the Settings icon stays highlighted so
 * the rail never looks "lost".
 *
 * The rail is a translucent glass pill. Collapsed it shows icons only; while
 * the D-pad is anywhere on it the whole pill widens over the page and every row
 * shows its label (the page keeps its collapsed inset, the panel overlays it).
 */
private class TvRailTab(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val TvRailTabs = listOf(
    TvRailTab(Routes.SEARCH, "Search", Icons.Filled.Search),
    TvRailTab(Routes.HOME, "Home", Icons.Filled.Home),
    // The anime airing calendar. The phone taskbar calls it "Anime"; on the TV
    // rail it reads as "Schedule".
    TvRailTab(Routes.ANIME_CALENDAR, "Schedule", Icons.Filled.CalendarMonth),
    TvRailTab(Routes.LIBRARY, "My Stuff", Icons.Filled.VideoLibrary),
    TvRailTab(Routes.SETTINGS, "Settings", Icons.Filled.Settings),
)

/** Pages that live inside Settings on a television keep Settings lit. */
private fun railHighlight(route: String?): String? = when (route) {
    Routes.EXTENSIONS,
    Routes.MANGA,
    Routes.IPTV,
    Routes.STATS,
    Routes.TELEGRAM -> Routes.SETTINGS
    else -> route
}

@Suppress("UNUSED_PARAMETER")
@Composable
fun TvNavRail(
    currentRoute: String?,
    hidden: Set<String>,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = railHighlight(currentRoute)
    // The whole pill is "focused" while the D-pad sits on ANY of its rows;
    // hasFocus covers the node and all its children, so moving between rows
    // never flickers the panel.
    var railFocused by remember { mutableStateOf(false) }
    val s = TvUi.scale()
    val t = TvUi.textScale()
    val railWidth by animateDpAsState(
        targetValue = if (railFocused) TvUi.RAIL_EXPANDED_WIDTH else TvUi.RAIL_WIDTH,
        animationSpec = tween(durationMillis = 200),
        label = "rail-width",
    )
    val pill = RoundedCornerShape(28.dp)
    Box(
        modifier = modifier
            .width(railWidth)
            .fillMaxHeight()
            .padding(start = 10.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { railFocused = it.hasFocus }
                .clip(pill)
                .background(Color(0xFF0C0C16).copy(alpha = 0.78f))
                .border(1.dp, TvDesktop.GlassEdge, pill)
                .padding(horizontal = 6.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (tab in TvRailTabs) {
                TvRailItem(
                    tab = tab,
                    selected = tab.route == active,
                    showLabel = railFocused,
                    labelScale = s * t,
                    onClick = { onNavigate(tab.route) },
                )
            }
        }
    }
}

@Composable
private fun TvRailItem(
    tab: TvRailTab,
    selected: Boolean,
    showLabel: Boolean,
    labelScale: Float,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            // The focus observer sits BEFORE the focus targets below it in the
            // chain: that is the order Compose reports their state in.
            .onFocusChanged { focused = it.hasFocus }
            .clip(shape)
            .background(
                when {
                    focused -> Color.White.copy(alpha = 0.24f)
                    selected -> Color.White.copy(alpha = 0.12f)
                    else -> Color.Transparent
                }
            )
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) Color.White.copy(alpha = 0.95f) else Color.Transparent,
                shape = shape,
            )
            // D-pad fires on Down (see tvPress): a page opened on Down must not
            // see the same press's Up land on the rail and bounce back.
            .tvPress(onClick = onClick)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(44.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    tab.icon,
                    contentDescription = tr(tab.label),
                    tint = if (focused || selected) Color.White else Color.White.copy(alpha = 0.62f),
                    modifier = Modifier.size(26.dp),
                )
            }
            if (showLabel) {
                Text(
                    tr(tab.label),
                    style = MaterialTheme.typography.labelLarge,
                    fontSize = MaterialTheme.typography.labelLarge.fontSize * labelScale,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = Color.White,
                    modifier = Modifier.padding(start = 6.dp, end = 12.dp),
                )
            }
        }
    }
}
