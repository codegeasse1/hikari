package com.hikari.app.tv

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hikari.app.i18n.tr
import com.hikari.app.ui.navigation.BottomTab
import com.hikari.app.ui.navigation.BottomTabs
import kotlinx.coroutines.launch

/** Sizes of the Nuvio-style top navigation bar. */
object TvTopNavMetrics {
    /** The bar's height, which is also the top inset every tab page keeps
     *  clear of it (see [com.hikari.app.ui.navigation.AppRoot]). */
    val HEIGHT = 64.dp
}

/**
 * The Nuvio-style television navigation: a slim bar along the TOP edge instead
 * of the side rail ([TvNavRail]). Chosen in Settings → Customize → TV interface
 * ([TvUiStyle.NUVIO]).
 *
 * It draws exactly the same [BottomTabs] list the rail and the phone taskbar
 * do, in the same order, so the layouts cannot drift apart. The tab the user is
 * on and the tab the remote is on show their label inside a rounded pill; the
 * other tabs are icon-only so every destination fits across the screen.
 *
 * Like the rail, it is where the D-pad starts: the current tab takes focus the
 * first time the bar appears, Left/Right walk the tabs, Down walks into the
 * page and Up from the top of the page comes back here.
 */
@Composable
fun TvTopNav(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabs = remember { BottomTabs }
    val requesters = remember(tabs) { tabs.map { FocusRequester() } }
    var initialFocusPending by remember(tabs) { mutableStateOf(true) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val bringIntoView: (Int) -> Unit = { index ->
        scope.launch {
            runCatching { listState.animateScrollToItem(index.coerceAtLeast(0)) }
        }
    }

    // Anchor the remote on the tab the user is on, once per composition of the
    // bar (not on every route change) — after that, focus belongs to the user.
    LaunchedEffect(tabs, currentRoute) {
        if (!initialFocusPending) return@LaunchedEffect
        val index = tabs.indexOfFirst { it.route == currentRoute }.takeIf { it >= 0 } ?: 0
        withFrameNanos { }
        try {
            listState.scrollToItem(index)
        } catch (t: Throwable) {
            // the strip fits on screen — nothing to scroll
        }
        try {
            requesters.getOrNull(index)?.requestFocus()
        } catch (t: Throwable) {
            // nothing to focus yet — the first arrow press still enters the bar
        }
        initialFocusPending = false
    }

    val accent = Cs3.accent
    val labelScale = TvUi.scale() * TvUi.textScale()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(TvTopNavMetrics.HEIGHT)
            .background(TvDesktop.Background.copy(alpha = 0.96f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(TvTopNavMetrics.HEIGHT)
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "HIKARI",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
                maxLines = 1,
                color = accent,
            )
            Spacer(Modifier.width(28.dp))
            LazyRow(
                state = listState,
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                itemsIndexed(tabs, key = { _, tab -> tab.route }) { index, tab ->
                    TvTopNavItem(
                        tab = tab,
                        selected = tab.route == currentRoute,
                        requester = requesters[index],
                        labelScale = labelScale,
                        onClick = { onNavigate(tab.route) },
                        onFocus = { bringIntoView(index) },
                    )
                }
            }
        }
        // Hairline under the bar, like the rest of the TV surfaces.
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(1.dp)
                .background(TvDesktop.Divider),
        )
    }
}

/**
 * One tab of the top bar: a plain `clickable` pill (that is what makes it
 * focusable for the D-pad and what lets it pick up the focus ring from
 * [TvFocusProvider]), with the same press handling as the rail's rows.
 */
@Composable
private fun TvTopNavItem(
    tab: BottomTab,
    selected: Boolean,
    requester: FocusRequester,
    labelScale: Float,
    onClick: () -> Unit,
    onFocus: () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    val accent = Cs3.accent
    var focused by remember { mutableStateOf(false) }
    val lit = focused || selected
    Row(
        modifier = Modifier
            .height(44.dp)
            .clip(shape)
            .background(
                when {
                    focused -> accent.copy(alpha = 0.30f)
                    selected -> Color.White.copy(alpha = 0.12f)
                    else -> Color.Transparent
                }
            )
            .border(
                1.dp,
                when {
                    focused -> accent
                    selected -> Color.White.copy(alpha = 0.22f)
                    else -> Color.Transparent
                },
                shape,
            )
            // D-pad fires on Down (see tvPress): same reasoning as TvNavRail —
            // a screen opened on the key-up must not bounce the press back.
            .tvPress(onClick = onClick)
            .clickable(onClick = onClick)
            .onFocusChanged { state ->
                focused = state.isFocused
                if (state.isFocused) onFocus()
            }
            .focusRequester(requester)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            tab.icon,
            contentDescription = tr(tab.label),
            tint = when {
                focused -> accent
                selected -> TvDesktop.TextPrimary
                else -> TvDesktop.TextMuted
            },
            modifier = Modifier.size(24.dp),
        )
        if (lit) {
            Spacer(Modifier.width(8.dp))
            Text(
                tr(tab.label),
                style = MaterialTheme.typography.labelLarge,
                fontSize = MaterialTheme.typography.labelLarge.fontSize * labelScale,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (focused) accent else TvDesktop.TextPrimary,
            )
        }
    }
}
