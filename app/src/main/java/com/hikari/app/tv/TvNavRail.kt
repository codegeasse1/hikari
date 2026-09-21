package com.hikari.app.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.unit.Dp
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
 *
 * EVERY TAB HAS TO BE REACHABLE, and that is not free. A television box reports
 * a very short screen in dp terms — a 1080p stick at density 2.0 is 540dp tall,
 * and one at density 2.4 is 450dp — while seven tab rows plus the wordmark want
 * ~600dp. The rail used to draw them anyway, centred in a plain Column, so the
 * first and last rows (Extensions, Settings) sat *off* the screen: a user could
 * not scroll down to them, because there was nothing to scroll and the remote
 * had nowhere to walk. The rows now share the height that actually exists (and
 * drop their labels when that leaves them too short to read), and the tab strip
 * is a lazy list, so a box that reports something even shorter still lets the
 * D-pad scroll the list to the focused tab instead of hiding it.
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
    val listState = rememberLazyListState()

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
            listState.scrollToItem(index)
        } catch (t: Throwable) {
            // the strip is too short to scroll — the row is on screen anyway
        }
        try {
            requesters.getOrNull(index)?.requestFocus()
        } catch (t: Throwable) {
            // nothing to focus yet — see above
        }
        initialFocusPending = false
    }

    val primary = MaterialTheme.colorScheme.primary
    val scheme = MaterialTheme.colorScheme

    BoxWithConstraints(
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
    ) {
        // How much height each tab may have: what is left after the wordmark,
        // split evenly. A roomy screen gives every tab its comfortable ~74dp
        // (the guard above caps it at that, so the strip stays a strip instead
        // of stretching down the side of the screen); a short one shrinks them
        // until they all fit.
        val wordmarkAndGap = 30.dp
        val room = (maxHeight - wordmarkAndGap).coerceAtLeast(0.dp)
        val slot = if (tabs.isEmpty()) 74.dp else (room / tabs.size).coerceAtMost(74.dp)
        // Below ~62dp of slot there is no room for an icon AND a readable label,
        // so the strip goes icon-only rather than clipping every label in half.
        val compact = slot < 62.dp

        Column(
            modifier = Modifier.fillMaxSize(),
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
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                // Centred while the tabs fit; once they do not, the list simply
                // scrolls, and the focused tab is brought into view by the
                // lazy list itself.
                verticalArrangement = Arrangement.Center,
            ) {
                itemsIndexed(tabs, key = { _, tab -> tab.route }) { index, tab ->
                    TvNavRailItem(
                        tab = tab,
                        selected = tab.route == currentRoute,
                        requester = requesters[index],
                        slot = slot,
                        compact = compact,
                        onClick = { onNavigate(tab.route) },
                    )
                }
            }
        }
    }
}

/**
 * One rail destination. It is a plain `clickable` column on purpose: that is
 * what makes it focusable for the D-pad and what makes it pick up the focus
 * ring from [TvFocusProvider] — the rail needs no focus handling of its own.
 *
 * [slot] is the height the rail could give this row including the gap below it;
 * the row itself is sized a few dp under that so the stack keeps its rhythm at
 * any screen height.
 */
@Composable
private fun TvNavRailItem(
    tab: BottomTab,
    selected: Boolean,
    requester: FocusRequester,
    slot: Dp,
    compact: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    val rowHeight = (slot - 6.dp).coerceAtLeast(38.dp)
    Column(
        modifier = Modifier
            .padding(vertical = 3.dp)
            .width(TvUi.RAIL_ITEM_WIDTH)
            .height(rowHeight)
            .clip(shape)
            .background(
                if (selected) scheme.primary.copy(alpha = 0.18f) else Color.Transparent
            )
            .clickable(onClick = onClick)
            .focusRequester(requester)
            .padding(vertical = 6.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                tab.icon,
                contentDescription = tr(tab.label),
                tint = if (selected) scheme.primary else scheme.onSurfaceVariant,
                modifier = Modifier.size(if (compact) 22.dp else 26.dp),
            )
        }
        if (!compact) {
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
}
