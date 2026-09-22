package com.hikari.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.hikari.app.i18n.tr

/**
 * The three "things that are yours" screens, in one taskbar slot.
 *
 * Library (titles saved with the player's heart), History (what you played) and
 * Downloads (what you saved for offline) were three buttons of the taskbar —
 * eight icons with no room left for anything else, and both the manga reader
 * and the IPTV tab were competing for the same space. They are all "my stuff"
 * rather than "find something to watch", so they now share one button and a
 * small segmented strip at the top of the page (the History | Updates pill the
 * user asked for, from their own Nekoread).
 *
 * Each of the three is still its own ROUTE ([com.hikari.app.ui.navigation.Routes]
 * .LIBRARY/.HISTORY/.DOWNLOADS), so every existing entry point (an empty state's
 * "Browse", a Continue-watching row's "See all", a deep link) lands on the right
 * section instead of breaking — those routes now render this screen with the
 * section preselected, and the taskbar shows exactly one button for all three.
 */
object MyStuff {
    const val LIBRARY = "library"
    const val HISTORY = "history"
    const val DOWNLOADS = "downloads"
}

@Composable
fun MyStuffScreen(nav: NavHostController, initial: String = MyStuff.LIBRARY) {
    // The section is remembered across leaving and returning to the tab, and a
    // route that names one (history / downloads) wins on arrival.
    var section by rememberSaveable { mutableStateOf(initial) }
    // Arriving on a different route (e.g. the player's "Continue watching"
    // link) must move the strip, not leave the previous section showing.
    androidx.compose.runtime.LaunchedEffect(initial) { section = initial }

    Column(Modifier.fillMaxSize()) {
        MyStuffStrip(section) { section = it }
        when (section) {
            MyStuff.HISTORY -> HistoryScreen(nav, embedded = true)
            MyStuff.DOWNLOADS -> DownloadsScreen(nav, embedded = true)
            else -> LibraryScreen(nav, embedded = true)
        }
    }
}

/** The three small buttons. Equal width, so all three fit a phone's edge-to-edge
 *  row without ever wrapping — and each is a real clickable, so a television's
 *  D-pad can walk across them and press one (the focus ring comes from
 *  MainActivity's TvFocusProvider, like every other clickable in the app). */
@Composable
private fun MyStuffStrip(current: String, onPick: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MyStuffPill(
            label = tr("Library"),
            icon = Icons.Filled.Favorite,
            selected = current == MyStuff.LIBRARY,
            modifier = Modifier.weight(1f),
        ) { onPick(MyStuff.LIBRARY) }
        MyStuffPill(
            label = tr("History"),
            icon = Icons.Filled.History,
            selected = current == MyStuff.HISTORY,
            modifier = Modifier.weight(1f),
        ) { onPick(MyStuff.HISTORY) }
        MyStuffPill(
            label = tr("Downloads"),
            icon = Icons.Filled.Download,
            selected = current == MyStuff.DOWNLOADS,
            modifier = Modifier.weight(1f),
        ) { onPick(MyStuff.DOWNLOADS) }
    }
}

@Composable
private fun MyStuffPill(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary
    else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        onClick = onClick,
        modifier = modifier.height(38.dp),
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
