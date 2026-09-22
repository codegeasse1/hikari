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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.hikari.app.HikariApp
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
 *
 * Each section can also be switched OFF (Settings → Taskbar buttons → My Stuff),
 * and the strip then draws only the ones that are left, sharing the row between
 * them — no reserved gap where the hidden one was, and a single remaining section
 * hides the strip entirely rather than drawing one button that can do nothing.
 * Hiding a section only hides its PILL: the page itself stays reachable from the
 * rest of the app (History from the player's "Continue watching", Downloads from
 * a download button, Library from the heart), the same rule a hidden tab obeys.
 */
object MyStuff {
    const val LIBRARY = com.hikari.app.data.MyStuffSection.LIBRARY
    const val HISTORY = com.hikari.app.data.MyStuffSection.HISTORY
    const val DOWNLOADS = com.hikari.app.data.MyStuffSection.DOWNLOADS

    /** The sections, in the order the strip draws them. */
    val ALL = com.hikari.app.data.MyStuffSection.ALL
}

@Composable
fun MyStuffScreen(nav: NavHostController, initial: String = MyStuff.LIBRARY) {
    // The section is remembered across leaving and returning to the tab, and a
    // route that names one (history / downloads) wins on arrival.
    var section by rememberSaveable { mutableStateOf(initial) }
    // Arriving on a different route (e.g. the player's "Continue watching"
    // link) must move the strip, not leave the previous section showing.
    androidx.compose.runtime.LaunchedEffect(initial) { section = initial }

    // Which sections the user keeps in the strip (Settings → Taskbar buttons →
    // My Stuff). Read here — the one place that owns the choice — and handed to
    // the strip, so switching a section off re-lays the row out in the same
    // frame instead of leaving a gap.
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as HikariApp
    // One flow and one collect per section (three preferences, three
    // subscriptions): the screen redraws when a section is switched, and nothing
    // else, which is what keeps a toggle in Settings instant here.
    val libraryFlow = remember { app.store.myStuffSectionFlow(MyStuff.LIBRARY) }
    val historyFlow = remember { app.store.myStuffSectionFlow(MyStuff.HISTORY) }
    val downloadsFlow = remember { app.store.myStuffSectionFlow(MyStuff.DOWNLOADS) }
    val libraryOn by libraryFlow.collectAsState(initial = true)
    val historyOn by historyFlow.collectAsState(initial = true)
    val downloadsOn by downloadsFlow.collectAsState(initial = true)
    val visible = MyStuff.ALL.filter { section ->
        when (section) {
            MyStuff.HISTORY -> historyOn
            MyStuff.DOWNLOADS -> downloadsOn
            else -> libraryOn
        }
    }
    // Whatever is left must include the section on screen, or the strip would
    // show nothing selected while its page is drawn: a section can only be
    // switched off while ANOTHER one is on (the settings screen enforces that),
    // but a route can also name a section that is hidden — a "Continue
    // watching" link, a download button — and then the page must still be
    // reachable and one pill must say so.
    val strip = if (section in visible) visible else visible + section

    Column(Modifier.fillMaxSize()) {
        // One section left means the strip is a single pill: hide it rather than
        // draw a lone button that does nothing but take a row of the screen.
        if (strip.size > 1) MyStuffStrip(strip, section) { section = it }
        when (section) {
            MyStuff.HISTORY -> HistoryScreen(nav, embedded = true)
            MyStuff.DOWNLOADS -> DownloadsScreen(nav, embedded = true)
            else -> LibraryScreen(nav, embedded = true)
        }
    }
}

/** The small buttons, one per section the user keeps. Equal width, so however
 *  many are left they fill a phone's edge-to-edge row without ever wrapping (a
 *  hidden section leaves no gap — the others share its room) — and each is a real
 *  clickable, so a television's D-pad can walk across them and press one (the
 *  focus ring comes from MainActivity's TvFocusProvider, like every other
 *  clickable in the app). */
@Composable
private fun MyStuffStrip(sections: List<String>, current: String, onPick: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The labels are read HERE (a composable position) because `tr` is
        // itself composable and cannot be called from inside a click lambda.
        sections.forEach { section ->
            MyStuffPill(
                label = when (section) {
                    MyStuff.HISTORY -> tr("History")
                    MyStuff.DOWNLOADS -> tr("Downloads")
                    else -> tr("Library")
                },
                icon = when (section) {
                    MyStuff.HISTORY -> Icons.Filled.History
                    MyStuff.DOWNLOADS -> Icons.Filled.Download
                    else -> Icons.Filled.Favorite
                },
                selected = current == section,
                modifier = Modifier.weight(1f),
            ) { onPick(section) }
        }
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
