package com.hikari.app.tv

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.LaunchedEffect
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

/**
 * The television Home's top bar, laid out like the reference: the brand mark
 * with the clock and date on the left, ONE pill of tab buttons in the middle
 * (the tabs that used to live on the side rail), and the round icon buttons
 * plus the profile avatar on the right.
 *
 * Home is always the lit tab here: this bar is drawn only by the Home screen.
 * Its height is exactly the 60dp the Home layout already reserved for its
 * header, so nothing below it moves.
 */
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
    val ts = TvUi.scale()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(60.dp)
            .padding(horizontal = 24.dp),
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
                    .size(26.dp)
                    .clip(RoundedCornerShape(7.dp)),
            )
            Spacer(Modifier.width(12.dp))
            TvHomeClock(ts)
        }

        // ---- middle: the tab pill ----
        TvHomeTabPill(onNavigate = onNavigate, ts = ts)

        // ---- right: icon buttons + avatar ----
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
private fun TvHomeTabPill(onNavigate: (String) -> Unit, ts: Float) {
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
                selected = tab.route == Routes.HOME,
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
                selected = false,
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
