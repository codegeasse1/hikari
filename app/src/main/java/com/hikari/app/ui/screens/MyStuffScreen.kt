package com.hikari.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.hikari.app.HikariApp
import com.hikari.app.i18n.tr
import kotlinx.coroutines.launch

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
 * Switching a section off while it is the one on screen moves the page to the
 * first section that is still kept, so a hidden section loses its pill and its
 * page in the same frame. Hiding a section only hides it HERE: the page itself
 * stays reachable from the rest of the app (History from the player's "Continue
 * watching", Downloads from a download button, Library from the heart), the same
 * rule a hidden tab obeys.
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
    // The section on screen is always one the user keeps. Switching a section off
    // in Settings has to take its pill away AND take its page with it in the same
    // frame — that is what "as soon as I toggle it, it shows and hides" means —
    // so a section that is no longer kept is left behind for the first one that
    // is. (The old rule kept the hidden section in the strip until the user
    // tapped another pill, which is the "it still shows until I tap Downloads"
    // the user reported.)
    LaunchedEffect(visible) {
        if (visible.isNotEmpty() && section !in visible) section = visible.first()
    }
    val strip = if (visible.isEmpty()) listOf(section) else visible

    // ---- Swipe between the sections, not just tap the pill ----
    //
    // The three are pages of one screen, so the gesture that reaches the next
    // one is the same one every other page of this app answers: swipe. The pill
    // the user taps and the page the swipe lands on are the SAME state, kept in
    // step in both directions — tapping a pill animates the pager over (so the
    // move reads as a page turning rather than a jump), and a swipe that settles
    // on a page selects that section, which is what redraws the pill. The two
    // effects each check before they write, so neither can spin the other.
    val pager = androidx.compose.foundation.pager.rememberPagerState(
        initialPage = strip.indexOf(section).coerceAtLeast(0),
        pageCount = { strip.size },
    )
    val scope = rememberCoroutineScope()
    // Keyed on the sections as text, not on the list instance: `visible` is
    // rebuilt by filter() on every recomposition, and an effect keyed on the
    // list itself would re-run (and re-animate) on every pass.
    val stripKey = strip.joinToString(",")
    LaunchedEffect(section, stripKey) {
        val target = strip.indexOf(section)
        if (target >= 0 && target != pager.currentPage) pager.animateScrollToPage(target)
    }
    LaunchedEffect(pager.currentPage, stripKey) {
        val shown = strip.getOrNull(pager.currentPage)
        if (shown != null && shown != section) section = shown
    }

    Column(Modifier.fillMaxSize()) {
        // One section left means the strip is a single pill: hide it rather than
        // draw a lone button that does nothing but take a row of the screen.
        if (strip.size > 1) {
            MyStuffStrip(strip, section) { picked ->
                section = picked
                scope.launch {
                    val target = strip.indexOf(picked)
                    if (target >= 0) pager.animateScrollToPage(target)
                }
            }
        }
        androidx.compose.foundation.pager.HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            // With one section there is nothing to swipe to, and a pager that
            // still slides is a page the user can drag into empty space.
            userScrollEnabled = strip.size > 1,
        ) { page ->
            when (strip.getOrNull(page)) {
                MyStuff.HISTORY -> HistoryScreen(nav, embedded = true)
                MyStuff.DOWNLOADS -> DownloadsScreen(nav, embedded = true)
                else -> LibraryScreen(nav, embedded = true)
            }
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

/**
 * One section button of the strip.
 *
 * Its label is SIZED TO FIT rather than ellipsized. "Downloads" is the longest
 * label the strip carries and it is drawn at `labelLarge` beside a 16dp icon
 * inside a third of a phone's row — which came out as "Downloa…" (and, on a
 * narrow screen or a large font scale, as a word sliced across two lines), the
 * user's report: "on downloading button it only shows downloa". Guessing a
 * smaller font would just move the failure to someone else's phone, so the label
 * is MEASURED: one measurement at a 100sp reference gives the size that fits the
 * room the icon leaves (text width is linear in font size), and that size is
 * used — the same trick the taskbar itself uses on its own labels. When even a
 * readable size does not fit, the ICON goes instead of the word, because the
 * word is what tells the user where the button goes.
 */
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
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    // The style the label is drawn with. Bold is the wider weight (a selected
    // pill), so it is the one measured — and letter spacing is pinned to zero
    // for the labels, as in the taskbar, so what is measured is what is drawn.
    val labelStyle = MaterialTheme.typography.labelLarge
    val measuredStyle = remember(labelStyle, density.fontScale) {
        labelStyle.copy(fontSize = 100.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.sp)
    }
    val reference = remember(label, measuredStyle, density.density) {
        measurer.measure(
            text = AnnotatedString(label),
            style = measuredStyle,
            maxLines = 1,
            softWrap = false,
            constraints = androidx.compose.ui.unit.Constraints(maxWidth = 100000),
        ).size.width
    }

    BoxWithConstraints(modifier.height(38.dp)) {
        // The pill's own padding (8dp a side), the 16dp icon and the 6dp gap —
        // the room a label has when the icon is drawn.
        val withIcon = with(density) { (maxWidth - 16.dp - 16.dp - 6.dp).toPx() }
        // …and what is left of the pill once the icon is gone.
        val withoutIcon = with(density) { (maxWidth - 16.dp).toPx() }
        fun sizeFor(room: Float): Float {
            if (reference <= 0 || room <= 0f) return labelStyle.fontSize.value
            val fontScale = density.fontScale.coerceAtLeast(0.5f)
            return (room * 0.98f * 100f * fontScale / reference)
                .coerceAtMost(labelStyle.fontSize.value)
        }
        val sizedWithIcon = sizeFor(withIcon)
        // Below 11sp a side-by-side icon and word are no longer both legible, so
        // the icon yields: the label is the part that carries the meaning, and a
        // dropped icon costs nothing (the pill is still a labelled button).
        val showIcon = sizedWithIcon >= 11f
        val sizeSp = if (showIcon) sizedWithIcon else sizeFor(withoutIcon)
        val scale = density.fontScale.coerceAtLeast(0.5f)
        Surface(
            onClick = onClick,
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(50),
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showIcon) {
                    Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    label,
                    style = labelStyle,
                    fontSize = (sizeSp / scale).sp,
                    letterSpacing = 0.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = fg,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                )
            }
        }
    }
}
