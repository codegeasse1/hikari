package com.hikari.app.tv

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hikari.app.i18n.tr
import com.hikari.app.ui.navigation.Routes

private class TvRailTab(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val TvRailTabs = listOf(
    TvRailTab(Routes.HOME, "Home", Icons.Filled.Home),
    TvRailTab(Routes.SEARCH, "Search", Icons.Filled.Search),
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
    Routes.TELEGRAM,
    Routes.COLLECTIONS -> Routes.SETTINGS
    else -> route
}

@Suppress("UNUSED_PARAMETER")
@Composable
fun TvNavRail(
    currentRoute: String?,
    hidden: Set<String>,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
    isScrolling: Boolean = false,
) {
    val active = railHighlight(currentRoute)
    var railFocused by remember { mutableStateOf(false) }
    val s = TvUi.scale()
    val t = TvUi.textScale()

    val railWidth by animateDpAsState(
        targetValue = if (railFocused) TvUi.RAIL_EXPANDED_WIDTH else TvUi.RAIL_WIDTH,
        animationSpec = tween(durationMillis = 220),
        label = "rail-width",
    )
    val collapsedAlpha by animateFloatAsState(
        targetValue = when {
            railFocused -> 1f
            isScrolling -> 0.50f
            else -> 0.88f
        },
        animationSpec = tween(durationMillis = 200),
        label = "rail-alpha",
    )

    Box(
        modifier = modifier
            .width(railWidth)
            .fillMaxHeight()
            .onFocusChanged { railFocused = it.hasFocus }
            .background(
                brush = if (railFocused) {
                    Brush.horizontalGradient(
                        colors = listOf(
                            Color(0xF50A0B10),
                            Color(0xE60A0B10),
                            Color(0xB30A0B10),
                        )
                    )
                } else {
                    Brush.horizontalGradient(
                        colors = listOf(
                            Color(0x66000000),
                            Color.Transparent,
                        )
                    )
                }
            )
            .then(
                if (railFocused) {
                    Modifier.border(
                        width = 1.dp,
                        color = Color.White.copy(alpha = 0.08f),
                    )
                } else {
                    Modifier
                }
            )
            .alpha(collapsedAlpha)
            .padding(horizontal = if (railFocused) 14.dp else 8.dp, vertical = 24.dp),
    ) {
        // উপরে ভিডিওর মতো ব্র্যান্ড লোগো (যখন সাইডবারে ফোকাস করা হবে তখন দেখাবে)
        AnimatedVisibility(
            visible = railFocused,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(120)),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 8.dp, top = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "hikari",
                    color = Color.White,
                    fontSize = 20.sp * s,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.8.sp,
                    maxLines = 1,
                )
            }
        }

        // মাঝখানে ৫টি নেভিগেশন আইটেম
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.CenterStart),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (tab in TvRailTabs) {
                TvRailItem(
                    tab = tab,
                    selected = tab.route == active,
                    showLabel = railFocused,
                    isScrolling = isScrolling && !railFocused,
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
    isScrolling: Boolean,
    labelScale: Float,
    onClick: () -> Unit,
) {
    val shape = if (showLabel) RoundedCornerShape(50) else CircleShape
    var focused by remember { mutableStateOf(false) }
    val itemScale by animateFloatAsState(
        targetValue = when {
            focused -> 1.03f
            isScrolling -> 0.92f
            else -> 1f
        },
        animationSpec = tween(180),
        label = "rail-item-scale",
    )

    val bgColor = when {
        focused -> Color.White
        selected && showLabel -> Color.White.copy(alpha = 0.14f)
        selected && !showLabel -> Color.White.copy(alpha = 0.12f)
        else -> Color.Transparent
    }
    val contentTint = when {
        focused -> Color(0xFF0A0B10)
        selected -> Color.White
        else -> Color.White.copy(alpha = 0.56f)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .graphicsLayer {
                scaleX = itemScale
                scaleY = itemScale
            }
            .onFocusChanged { focused = it.hasFocus }
            .clip(shape)
            .background(bgColor)
            .tvPress(onClick = onClick)
            .clickable(onClick = onClick)
            .padding(horizontal = if (showLabel) 10.dp else 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(40.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = tab.icon,
                    contentDescription = tr(tab.label),
                    tint = contentTint,
                    modifier = Modifier.size(23.dp),
                )
            }
            AnimatedVisibility(
                visible = showLabel,
                enter = fadeIn(tween(160)),
                exit = fadeOut(tween(100)),
            ) {
                Text(
                    text = tr(tab.label),
                    style = MaterialTheme.typography.labelLarge,
                    fontSize = MaterialTheme.typography.labelLarge.fontSize * labelScale,
                    fontWeight = if (focused || selected) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = contentTint,
                    modifier = Modifier.padding(start = 8.dp, end = 14.dp),
                )
            }
        }
    }
}
