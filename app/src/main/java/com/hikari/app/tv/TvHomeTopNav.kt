package com.hikari.app.tv

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hikari.app.R
import com.hikari.app.i18n.tr
import com.hikari.app.ui.navigation.Routes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The television top bar, laid out like the reference: the brand mark with the
 * clock and date on the left, ONE pill of tab buttons in the middle, and the
 * round icon buttons plus the profile avatar on the right.
 *
 * TV FIX: this ONE bar now serves every main tab. The Home screen draws it with
 * its own right-hand buttons ([TvHomeTopNav]); every other tab (Schedule, Music,
 * My Stuff, Search, Settings...) gets it from AppRoot ([TvTabTopBar]) — so the
 * old side rail never comes back after a tab is chosen from the bar.
 *
 * The whole bar is drawn through [TvUi.chromeScale], so it is the same share of
 * the screen as the reference on any TV resolution (the old fixed 60dp bar looked
 * tiny and cramped on 720p/4K panels).
 */
@Composable
private fun TvTopBarFrame(
    activeRoute: String?,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
    right: @Composable RowScope.() -> Unit,
) {
    val base = LocalDensity.current
    val bs = TvUi.chromeScale()
    // Scale dp AND sp together: every size below is written for a 540dp-tall screen.
    CompositionLocalProvider(LocalDensity provides Density(base.density * bs, base.fontScale)) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .height(TvUi.TOP_BAR_BASE_DP.dp)
                .padding(horizontal = 32.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // ---- left: brand mark + clock ----
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painter = painterResource(id = R.drawable.hikari_logo),
                    contentDescription = "hikari",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
                Spacer(Modifier.width(12.dp))
                TvHomeClock(1f)
            }

            // ---- middle: the tab pill ----
            TvHomeTabPill(activeRoute = tvTopBarHighlight(activeRoute), onNavigate = onNavigate, ts = 1f)

            // ---- right: icon buttons + avatar ----
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
                content = right,
            )
        }
    }
}

/** Pages that live inside Settings on a television keep Settings lit. */
private fun tvTopBarHighlight(route: String?): String? = when (route) {
    Routes.EXTENSIONS,
    Routes.MANGA,
    Routes.IPTV,
    Routes.STATS,
    Routes.TELEGRAM,
    Routes.COLLECTIONS -> Routes.SETTINGS
    else -> route
}

/** The Home screen's flavour of the bar (provider picker, translate, ...). */
@Composable
fun TvHomeTopNav(
    onNavigate: (String) -> Unit,
    providerName: String?,
    providerIconUrl: String?,
    profileName: String?,
    onProviderClick: () -> Unit,
    onProfileClick: () -> Unit,
    selected: String?,
    onSearch: () -> Unit,
    onTranslate: () -> Unit,
    onVerify: () -> Unit,
    onMouse: () -> Unit,
    mouseOn: Boolean,
    modifier: Modifier = Modifier,
) {
    TvTopBarFrame(activeRoute = Routes.HOME, onNavigate = onNavigate, modifier = modifier) {
        TvNavCircle(onClick = onMouse, description = tr("Mouse cursor")) { tint ->
            Icon(
                Icons.Filled.Mouse,
                contentDescription = null,
                tint = if (mouseOn && tint == Color.White) Cs3.accent else tint,
                modifier = Modifier.size(19.dp),
            )
        }
        if (selected != null) {
            val translateOn = com.hikari.app.data.Translator.isOn(selected)
            TvNavCircle(onClick = onTranslate, description = tr("Translate")) { tint ->
                Text(
                    tr("A\u3042"),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (translateOn && tint == Color.White) Cs3.accent else tint,
                )
            }
            TvNavCircle(
                onClick = onVerify,
                description = tr("Open this extension's site in a web view"),
            ) { tint ->
                Icon(
                    Icons.Filled.Public,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(19.dp),
                )
            }
        }
        TvNavCircle(onClick = onSearch, description = tr("Search")) { tint ->
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
        }
        // The provider / feed picker, as one round button.
        TvNavCircle(onClick = onProviderClick, description = providerName ?: tr("All providers")) { _ ->
            TvProviderIcon(iconUrl = providerIconUrl, name = providerName, size = 24.dp)
        }
        Spacer(Modifier.width(4.dp))
        TvNavAvatar(name = profileName, onClick = onProfileClick)
    }
}

/**
 * The same bar for every OTHER main tab (Schedule, Search, Music, My Stuff,
 * Settings, ...). AppRoot draws it over the page instead of the old side rail.
 * [activeRoute] is the lit tab (use [TV_MUSIC_ROUTE] while the music layer is up).
 */
@Composable
fun TvTabTopBar(
    activeRoute: String?,
    onNavigate: (String) -> Unit,
    onProfileSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val profiles by com.hikari.app.data.Profiles.all.collectAsState()
    val activeProfileId by com.hikari.app.data.Profiles.activeId.collectAsState()
    val profileName = profiles.firstOrNull { it.id == activeProfileId }?.name
    var showProfileMenu by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val context = LocalContext.current

    TvTopBarFrame(activeRoute = activeRoute, onNavigate = onNavigate, modifier = modifier) {
        val mouseOn = com.hikari.app.tv.MouseMode.enabled
        TvNavCircle(
            onClick = { com.hikari.app.tv.MouseMode.toggle() },
            description = tr("Mouse cursor"),
        ) { tint ->
            Icon(
                Icons.Filled.Mouse,
                contentDescription = null,
                tint = if (mouseOn && tint == Color.White) Cs3.accent else tint,
                modifier = Modifier.size(19.dp),
            )
        }
        Spacer(Modifier.width(4.dp))
        TvNavAvatar(name = profileName, onClick = { showProfileMenu = true })
    }

    if (showProfileMenu) {
        val app = com.hikari.app.HikariApp.instance
        TvProfileMenuDialog(
            profiles = profiles,
            activeId = activeProfileId,
            onSwitch = { id ->
                showProfileMenu = false
                scope.launch {
                    val ok = runCatching { com.hikari.app.data.Profiles.switchTo(app, id) }
                        .getOrDefault(false)
                    if (!ok) {
                        android.widget.Toast.makeText(
                            context,
                            com.hikari.app.i18n.I18n.t(
                                "That profile is locked — unlock it in Settings, Profiles."
                            ),
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            },
            onAdd = { name ->
                showProfileMenu = false
                scope.launch { runCatching { com.hikari.app.data.Profiles.createEmpty(app, name) } }
            },
            onManage = {
                showProfileMenu = false
                onProfileSettings()
            },
            onDismiss = { showProfileMenu = false },
        )
    }
}

// ---------------------------------------------------------------------------
// Clock
// ---------------------------------------------------------------------------

@Composable
private fun TvHomeClock(ts: Float) {
    val context = LocalContext.current
    var now by remember { mutableStateOf(java.util.Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = java.util.Date()
            val ms = System.currentTimeMillis()
            delay(60_000L - (ms % 60_000L) + 50L)
        }
    }
    val timeFormat = remember(context) { android.text.format.DateFormat.getTimeFormat(context) }
    val dateFormat = remember { java.text.SimpleDateFormat("EEE, d MMM", java.util.Locale.getDefault()) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = timeFormat.format(now),
            color = Color.White,
            fontSize = 13.sp * ts,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = dateFormat.format(now),
            color = Color.White.copy(alpha = 0.62f),
            fontSize = 12.sp * ts,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------------------------------------------------------------------------
// Tab pill
// ---------------------------------------------------------------------------

private class TvTopTab(
    val route: String,
    val label: String? = null,
    val icon: ImageVector? = null,
)

// Same destinations the side rail had: the text tabs first, then (after a
// hairline, like the reference) the two icon tabs.
private val TvTopTextTabs = listOf(
    TvTopTab(Routes.HOME, label = "Home"),
    TvTopTab(Routes.ANIME_CALENDAR, label = "Schedule"),
    TvTopTab(TV_MUSIC_ROUTE, label = "Music"),
    TvTopTab(Routes.LIBRARY, label = "My Stuff"),
)

private val TvTopIconTabs = listOf(
    TvTopTab(Routes.SEARCH, icon = Icons.Filled.Search),
    TvTopTab(Routes.SETTINGS, icon = Icons.Filled.Settings),
)

@Composable
private fun TvHomeTabPill(activeRoute: String?, onNavigate: (String) -> Unit, ts: Float) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(Color.Black.copy(alpha = 0.38f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), shape)
            .padding(horizontal = 4.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (tab in TvTopTextTabs) {
            TvTopTabItem(
                tab = tab,
                selected = tab.route == activeRoute,
                ts = ts,
                onClick = { onNavigate(tab.route) },
            )
        }
        Box(
            Modifier
                .padding(horizontal = 6.dp)
                .width(1.dp)
                .height(16.dp)
                .background(Color.White.copy(alpha = 0.22f))
        )
        for (tab in TvTopIconTabs) {
            TvTopTabItem(
                tab = tab,
                selected = tab.route == activeRoute,
                ts = ts,
                onClick = { onNavigate(tab.route) },
            )
        }
    }
}

@Composable
private fun TvTopTabItem(
    tab: TvTopTab,
    selected: Boolean,
    ts: Float,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    val bg = when {
        focused -> Color.White
        selected -> Color.White.copy(alpha = 0.20f)
        else -> Color.Transparent
    }
    val fg = when {
        focused -> Color(0xFF0A0B10)
        selected -> Color.White
        else -> Color.White.copy(alpha = 0.78f)
    }
    Box(
        modifier = Modifier
            .height(30.dp)
            .onFocusChanged { focused = it.hasFocus }
            .clip(shape)
            .background(bg)
            .tvPress(onClick = onClick)
            .clickable(onClick = onClick)
            .padding(horizontal = if (tab.label != null) 14.dp else 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (tab.label != null) {
            Text(
                text = tr(tab.label),
                color = fg,
                fontSize = 12.5.sp * ts,
                fontWeight = if (focused || selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
            )
        } else if (tab.icon != null) {
            Icon(
                imageVector = tab.icon,
                contentDescription = tr(
                    if (tab.route == Routes.SEARCH) "Search" else "Settings"
                ),
                tint = fg,
                modifier = Modifier.size(17.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Right-hand buttons
// ---------------------------------------------------------------------------

/** One round 36dp icon button; [content] receives the tint to draw with. */
@Composable
private fun TvNavCircle(
    onClick: () -> Unit,
    description: String,
    content: @Composable (tint: Color) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .size(36.dp)
            .semantics { contentDescription = description }
            .onFocusChanged { focused = it.hasFocus }
            .clip(CircleShape)
            .background(if (focused) Color.White else Color.Transparent)
            .tvPress(onClick = onClick)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content(if (focused) Color(0xFF0A0B10) else Color.White)
    }
}

@Composable
private fun TvNavAvatar(name: String?, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val initial = name?.trim()?.firstOrNull()?.uppercase() ?: "M"
    Box(
        modifier = Modifier
            .size(34.dp)
            .onFocusChanged { focused = it.hasFocus }
            .clip(CircleShape)
            .border(
                width = 2.dp,
                color = if (focused) Color.White else Color.Transparent,
                shape = CircleShape,
            )
            .padding(2.dp)
            .clip(CircleShape)
            .background(Cs3.accent)
            .tvPress(onClick = onClick)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}
