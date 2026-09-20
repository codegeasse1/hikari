package com.hikari.app.tv

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hikari.app.i18n.tr
import com.hikari.app.ui.navigation.BottomTabs
import com.hikari.app.ui.navigation.BottomTab

/**
 * The television navigation rail: the left-hand strip of tabs that stands in
 * for the phone's bottom taskbar.
 *
 * A taskbar is a *touch* idea — it lives at the bottom edge, which is where a
 * thumb reaches, and it is hidden or smaller on most phones. A television has
 * neither a thumb nor a bottom edge that is easier to reach than the side: the
 * convention every streaming app on the platform follows is a vertical column
 * of destinations on the left, walked with Up/Down, with the content to the
 * right of it.
 *
 * It draws exactly the same [BottomTabs] list the phone's bar does, in the same
 * order, honouring the same "hide this tab" preference — so the two layouts
 * cannot drift apart, and a tab hidden on the phone is hidden here too.
 *
 * It is also where the D-pad *starts*: [AppRoot] does not request focus on any
 * page content, so the first arrow press after launch lands on the rail (the
 * selected tab), and Right walks into the page from there.
 */
@Composable
fun TvNavRail(
    currentRoute: String?,
    hidden: Set<String>,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Same rule as the phone's bar: a tab can be hidden, but the rail can never
    // be emptied (see TaskbarCard), so a stored set that covers every tab falls
    // back to the full list.
    val tabs = remember(hidden) {
        BottomTabs.filter { it.route !in hidden }.ifEmpty { BottomTabs }
    }
    val requesters = remember(tabs) { tabs.map { FocusRequester() } }
    var initialFocusPending by remember(tabs) { mutableStateOf(true) }

    // Anchor the remote on the tab the user is actually on. Done once per
    // composition of the rail (not on every route change): after that, focus
    // belongs to the user — re-grabbing it every time they open a screen would
    // fight the D-pad.
    LaunchedEffect(tabs, currentRoute) {
        if (!initialFocusPending) return@LaunchedEffect
        val index = tabs.indexOfFirst { it.route == currentRoute }.takeIf { it >= 0 } ?: 0
        // One frame so the nodes are laid out before we point at one, and a
        // requestFocus() that lands too early is an error rather than a no-op —
        // so it is guarded, and the rail simply starts with no focus if it
        // cannot take it (the first arrow press still enters the rail).
        withFrameNanos { }
        try {
            requesters.getOrNull(index)?.requestFocus()
        } catch (t: Throwable) {
            // nothing to focus yet — see above
        }
        initialFocusPending = false
    }

    val primary = MaterialTheme.colorScheme.primary
    val scheme = MaterialTheme.colorScheme

    Column(
        modifier = modifier
            .width(TvUi.RAIL_WIDTH)
            .fillMaxHeight()
            // A touch of its own surface behind the rail, so the tabs do not
            // float over a bright poster collage. Deliberately faint: the page
            // backdrop must still show through it.
            .background(
                Brush.horizontalGradient(
                    listOf(
                        scheme.surface.copy(alpha = 0.55f),
                        scheme.surface.copy(alpha = 0.12f),
                    )
                )
            )
            .padding(vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "HIKARI",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
            color = primary,
        )
        Spacer(Modifier.height(14.dp))
        Column(
            modifier = Modifier.fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            tabs.forEachIndexed { index, tab ->
                TvNavRailItem(
                    tab = tab,
                    selected = tab.route == currentRoute,
                    requester = requesters[index],
                    onClick = { onNavigate(tab.route) },
                )
            }
        }
    }
}

/**
 * One rail destination. It is a plain `clickable` column on purpose: that is
 * what makes it focusable for the D-pad and what makes it pick up the focus
 * ring from [TvFocusProvider] — the rail needs no focus handling of its own.
 */
@Composable
private fun TvNavRailItem(
    tab: BottomTab,
    selected: Boolean,
    requester: FocusRequester,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = Modifier
            .padding(vertical = 3.dp)
            .width(TvUi.RAIL_ITEM_WIDTH)
            .clip(shape)
            .background(
                if (selected) scheme.primary.copy(alpha = 0.18f) else Color.Transparent
            )
            .clickable(onClick = onClick)
            .focusRequester(requester)
            .padding(vertical = 12.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                tab.icon,
                contentDescription = tr(tab.label),
                tint = if (selected) scheme.primary else scheme.onSurfaceVariant,
                modifier = Modifier.size(26.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            tr(tab.label),
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (selected) scheme.primary else scheme.onSurfaceVariant,
        )
    }
}
