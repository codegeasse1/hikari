package com.hikari.app.ui.screens
import com.hikari.app.i18n.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.hikari.app.HikariApp
import com.hikari.app.data.WatchStats
import com.hikari.app.ui.components.GlassCard
import com.hikari.app.ui.components.SettingsPageHeader
import com.hikari.app.ui.navigation.LocalTaskbarInset
import kotlinx.coroutines.launch

/** Which drill-down the headline figures have open (null = none). */
private const val PANEL_TIME = "time"
private const val PANEL_ITEMS = "items"
private const val PANEL_DAYS = "days"

/**
 * The Stats page: what the user watched and read, and for how long.
 *
 * It is one screen with two doors — the Stats tab (off by default, switched on
 * in Settings → Taskbar buttons) and the Stats row on the Settings index. Both
 * render this composable; [onBack] is what tells them apart, so the header is a
 * page header with a back button when Settings opened it and a plain title when
 * the tab did.
 *
 * Everything on it comes from [WatchStats], which the player and the manga
 * reader write to as they go (see AppStore.recordWatchSeconds and friends). The
 * page itself computes nothing it does not have to: the rank ladder, the
 * streaks, the daily averages and the heatmap are all derived in one pass from
 * the decoded document.
 */
@Composable
fun StatsScreen(app: HikariApp, onBack: (() -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    // A remembered Flow: an inline store call would be a NEW Flow on every
    // recomposition, so collectAsState would re-subscribe and reset to initial.
    val statsFlow = remember { app.store.watchStatsFlow() }
    val json by statsFlow.collectAsState(initial = "")
    val today = WatchStats.dayKey()
    val snapshot = remember(json, today) { WatchStats.decode(json) }
    // The day the heatmap has highlighted. It follows "today" until the user
    // taps a square, so the caption under the grid is never blank.
    var pickedDay by remember(today) { mutableStateOf(today) }
    // True once the user has picked a day ON PURPOSE (a square, or a row in the
    // "Days active" sheet).
    var dayChosen by remember(today) { mutableStateOf(false) }
    // Which drill-down panel is open under the headline figures (null = closed).
    var openPanel by remember { mutableStateOf<String?>(null) }
    // The day the headline figures and their sheets drill into.
    //
    // Today is the DEFAULT selection, and while it is only the default, "today
    // so far" and "all time" are the same question for a log this young — so the
    // figures start unscoped. Once a day is picked on purpose it scopes them,
    // today included: tapping today's square and then "Time spent" means "what
    // did I watch today", and a list that answered with the whole log instead
    // would be answering a question nobody asked.
    val scopeDay = if (dayChosen) pickedDay else null
    val scopeTitles = remember(snapshot, scopeDay) {
        if (scopeDay == null) snapshot.allTitles else snapshot.titlesOn(scopeDay)
    }

    val rank = remember(snapshot) { WatchStats.rankFor(snapshot.totalSeconds) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 16.dp,
            bottom = LocalTaskbarInset.current + 16.dp,
        ),
    ) {
        item {
            val resetTrailing: (@Composable () -> Unit)? = if (snapshot.daysActive > 0) {
                {
                    TextButton(onClick = {
                        scope.launch { runCatching { app.store.clearWatchStats() } }
                    }) { Text(tr("Reset")) }
                }
            } else null
            if (onBack != null) {
                SettingsPageHeader(
                    title = tr("Stats"),
                    subtitle = tr("What you watched and read, and for how long"),
                    icon = Icons.Filled.BarChart,
                    onBack = onBack,
                    trailing = resetTrailing,
                )
            } else {
                Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                    Text(
                        tr("Stats"),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        tr("What you watched and read, and for how long"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }
        }

        // ---- The three headline figures ----
        //
        // Each is a DOOR: tapping one opens the list behind the number — what was
        // watched and for how long, which items were consumed, which days were
        // active. Whichever day the heatmap has picked scopes the first two, so
        // "tap the 19th, then tap Time spent" answers "what did I watch on the
        // 19th" instead of repeating the all-time figure.
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StatTile(
                    label = tr("Time spent"),
                    value = WatchStats.durationLabel(snapshot.totalSeconds),
                    icon = Icons.Filled.Schedule,
                    modifier = Modifier.weight(1f),
                    onClick = { openPanel = if (openPanel == PANEL_TIME) null else PANEL_TIME },
                )
                StatTile(
                    label = tr("Items consumed"),
                    value = snapshot.totalItems.toString(),
                    icon = Icons.Filled.Movie,
                    modifier = Modifier.weight(1f),
                    onClick = { openPanel = if (openPanel == PANEL_ITEMS) null else PANEL_ITEMS },
                )
                StatTile(
                    label = tr("Days active"),
                    value = snapshot.daysActive.toString(),
                    icon = Icons.Filled.CalendarMonth,
                    modifier = Modifier.weight(1f),
                    onClick = { openPanel = if (openPanel == PANEL_DAYS) null else PANEL_DAYS },
                )
            }
        }

        // The list behind the tapped figure, in place (no second screen, and the
        // tiles stay on screen so the choice can be changed without going back).
        val open = openPanel
        if (open != null) {
            item {
                PanelCard(
                    title = when (open) {
                        PANEL_TIME -> tr("Time spent")
                        PANEL_ITEMS -> tr("Items consumed")
                        else -> tr("Days active")
                    },
                    scopeDay = if (open == PANEL_DAYS) null else scopeDay,
                    onClose = { openPanel = null },
                    onScopeAllTime = {
                        pickedDay = today
                        dayChosen = false
                    },
                    rows = when (open) {
                        PANEL_TIME -> scopeTitles
                        PANEL_ITEMS -> scopeTitles.sortedByDescending { it.videos + it.chapters }
                        else -> emptyList()
                    },
                    dayRows = if (open == PANEL_DAYS) snapshot.activeDays else emptyList(),
                    onPickDay = { day ->
                        pickedDay = day
                        dayChosen = true
                        // Tapping a day in the "Days active" list asks the
                        // question the list implies — "what was that day?" — so
                        // the sheet turns into that day's own item list instead
                        // of leaving the reader to find the right tile again.
                        if (open == PANEL_DAYS) openPanel = PANEL_ITEMS
                    },
                    emptyText = if (scopeDay != null) {
                        tr("Nothing was watched or read on this day.")
                    } else {
                        tr("Nothing logged yet.")
                    },
                )
            }
        }

        // ---- Rank and level progress ----
        item {
            GlassCard(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(50))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.Spa,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(19.dp),
                            )
                        }
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                rank.name.uppercase(),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                tr("Otaku Rank Level"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(50))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text(
                                rank.xp.toString() + " XP",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            tr("Level Progress"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            WatchStats.hoursLabel(rank.hours) + " / " +
                                WatchStats.hoursLabel(rank.targetHours),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    // The bar is drawn rather than using LinearProgressIndicator:
                    // a determinate Material bar animates its own track colour
                    // and gap, and this one has to sit inside a glass card.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(rank.progress.coerceIn(0.02f, 1f))
                                .height(8.dp)
                                .clip(RoundedCornerShape(50))
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                    if (rank.isTop) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            tr("Top rank reached — it is all downhill from here."),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // ---- Daily averages ----
        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StatTile(
                    label = tr("Avg episodes"),
                    value = String.format(java.util.Locale.US, "%.1f", snapshot.averageEpisodesPerDay) + "/" +
                        tr("day"),
                    icon = Icons.Filled.Schedule,
                    modifier = Modifier.weight(1f),
                    compact = true,
                )
                StatTile(
                    label = tr("Avg chapters"),
                    value = String.format(java.util.Locale.US, "%.1f", snapshot.averageChaptersPerDay) + "/" +
                        tr("day"),
                    icon = Icons.AutoMirrored.Filled.MenuBook,
                    modifier = Modifier.weight(1f),
                    compact = true,
                )
                StatTile(
                    label = tr("Streak (Cur/Long)"),
                    value = snapshot.currentStreak(today).toString() + "d / " +
                        snapshot.longestStreak.toString() + "d",
                    icon = Icons.Filled.LocalFireDepartment,
                    modifier = Modifier.weight(1f),
                    compact = true,
                )
            }
        }

        // ---- Favourite title ----
        val favourite = snapshot.favourite
        if (favourite != null) {
            item {
                Text(
                    tr("Favorite Title"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 18.dp, bottom = 6.dp),
                )
            }
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Favorite,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                tr("FAVORITE") + " · " +
                                    WatchStats.durationLabel(favourite.seconds) + " " + tr("spent"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                favourite.title.ifBlank { tr("Untitled") },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                when (favourite.kind) {
                                    WatchStats.KIND_MANGA -> tr("Manga")
                                    WatchStats.KIND_SERIES -> tr("Series")
                                    else -> tr("Movie")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (!favourite.posterUrl.isNullOrBlank()) {
                            Spacer(Modifier.width(10.dp))
                            AsyncImage(
                                model = favourite.posterUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .width(52.dp)
                                    .height(78.dp)
                                    .clip(RoundedCornerShape(10.dp)),
                            )
                        }
                    }
                }
            }
        }

        // ---- Activity heatmap ----
        item {
            Text(
                tr("Activity Heatmap"),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 18.dp, bottom = 6.dp),
            )
        }
        item {
            val weeks = remember(snapshot, today) { snapshot.heatmap(today = today) }
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        monthLabelOf(weeks),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(5.dp))
                    Row(verticalAlignment = Alignment.Top) {
                        // Weekday letters, one per row of the grid beside them.
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            listOf("S", "M", "T", "W", "T", "F", "S").forEach { letter ->
                                Box(
                                    Modifier.size(13.dp),
                                    contentAlignment = Alignment.CenterStart,
                                ) {
                                    Text(
                                        letter,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            weeks.forEach { column ->
                                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    column.forEach { cell ->
                                        if (cell == null) {
                                            Box(Modifier.size(13.dp))
                                        } else {
                                            val selected = cell.dayKey == pickedDay
                                            Box(
                                                Modifier
                                                    .size(13.dp)
                                                    .clip(RoundedCornerShape(3.dp))
                                                    .background(heatColor(cell.seconds))
                                                    .then(
                                                        if (selected) Modifier.border(
                                                            1.dp,
                                                            MaterialTheme.colorScheme.primary,
                                                            RoundedCornerShape(3.dp),
                                                        ) else Modifier
                                                    )
                                                    .clickable {
                                                        pickedDay = cell.dayKey
                                                        dayChosen = true
                                                    },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    // What the picked square actually contains: the date, the
                    // day's own figures, and the titles that made them up. Every
                    // day answers for itself — the whole page used to read out the
                    // same number whichever day was tapped, because a day's
                    // bucket held a time and nothing else.
                    val dayTitles = remember(snapshot, pickedDay) { snapshot.titlesOn(pickedDay) }
                    Text(
                        WatchStats.prettyDay(pickedDay),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        daySummary(snapshot.days[pickedDay]),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (dayTitles.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        dayTitles.take(3).forEach { t -> StatsTitleRow(t) }
                        if (dayTitles.size > 3) {
                            TextButton(onClick = { openPanel = PANEL_ITEMS }) {
                                Text(
                                    tr("See all %s items")
                                        .replace("%s", dayTitles.size.toString())
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            Text(
                if (snapshot.daysActive == 0) {
                    tr("No insights available yet. Add items to your library!")
                } else {
                    tr("Time is counted while something is playing or a chapter is open.")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 14.dp, start = 4.dp),
            )
        }
    }
}

/** One of the small figures: a label, an icon, and the number under them.
 *  [compact] shrinks the number for the tiles whose value carries a unit
 *  ("0.0/day", "0d / 1d"), which is what keeps a third of a phone's width wide
 *  enough for them. */
@Composable
private fun StatTile(
    label: String,
    value: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    /** Opens the list behind this figure. A tile with no list (the averages and
     *  the streak) passes nothing and stays a plain card. */
    onClick: (() -> Unit)? = null,
) {
    GlassCard(modifier = modifier, onClick = onClick) {
        Column(Modifier.padding(horizontal = 11.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(3.dp))
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                value,
                style = if (compact) MaterialTheme.typography.titleMedium
                else MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** How strongly a day is drawn: nothing, then four steps of the accent. */
@Composable
private fun heatColor(seconds: Long): Color {
    val accent = MaterialTheme.colorScheme.primary
    val minutes = seconds / 60L
    return when {
        minutes <= 0L -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.13f)
        minutes < 10L -> accent.copy(alpha = 0.32f)
        minutes < 30L -> accent.copy(alpha = 0.55f)
        minutes < 60L -> accent.copy(alpha = 0.78f)
        else -> accent
    }
}

/** "Sep" (or "Sep – Nov" when the twelve weeks span more than one month). */
private fun monthLabelOf(weeks: List<List<WatchStats.HeatCell?>>): String {
    val first = weeks.firstOrNull()?.firstOrNull { it != null }?.dayKey ?: return ""
    val last = weeks.lastOrNull()?.lastOrNull { it != null }?.dayKey ?: return ""
    val tag = com.hikari.app.i18n.I18n.currentTag
    val locale = if (tag.isBlank()) java.util.Locale.getDefault()
    else java.util.Locale.forLanguageTag(tag)
    fun label(key: String): String {
        val cal = WatchStats.parseDay(key) ?: return key
        return runCatching {
            java.text.SimpleDateFormat("MMM", locale).format(cal.time)
        }.getOrDefault(key)
    }
    val a = label(first)
    val b = label(last)
    return if (a == b) a else "$a – $b"
}

/** One line under a picked day: what that day added up to. A day with nothing
 *  on it says so instead of a bare "0m". */
@Composable
private fun daySummary(day: WatchStats.Day?): String {
    if (day == null || day.isEmpty) return tr("No activity")
    val parts = ArrayList<String>(3)
    if (day.seconds > 0L) parts += WatchStats.durationLabel(day.seconds) + " " + tr("watched")
    if (day.videos > 0) {
        parts += day.videos.toString() + " " + tr(if (day.videos == 1) "Episode" else "Episodes")
    }
    if (day.chapters > 0) {
        parts += day.chapters.toString() + " " + tr(if (day.chapters == 1) "Chapter" else "Chapters")
    }
    return parts.joinToString(" · ")
}

/** "Manga" / "Series" / "Movie" for a stored [WatchStats.TitleTotal.kind]. */
@Composable
private fun kindLabel(kind: String): String = when (kind) {
    WatchStats.KIND_MANGA -> tr("Manga")
    WatchStats.KIND_SERIES -> tr("Series")
    else -> tr("Movie")
}

/** What was consumed of a title — "3 Episodes", "12 Chapters", or both when a
 *  log holds the two halves of the same name. Falls back to "watched" when the
 *  row only knows a duration (a build predating the per-event rows). */
@Composable
private fun rowDetail(t: WatchStats.TitleTotal): String {
    val parts = ArrayList<String>(2)
    if (t.videos > 0) {
        parts += t.videos.toString() + " " + tr(if (t.videos == 1) "Episode" else "Episodes")
    }
    if (t.chapters > 0) {
        parts += t.chapters.toString() + " " + tr(if (t.chapters == 1) "Chapter" else "Chapters")
    }
    if (parts.isEmpty()) parts += tr("watched")
    return parts.joinToString(" · ")
}

/** One title inside a drill-down: poster, name, what it is and what was taken
 *  from it, and the time it accounts for. */
@Composable
private fun StatsTitleRow(t: WatchStats.TitleTotal) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!t.posterUrl.isNullOrBlank()) {
            AsyncImage(
                model = t.posterUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(34.dp)
                    .height(51.dp)
                    .clip(RoundedCornerShape(6.dp)),
            )
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                t.title.ifBlank { tr("Untitled") },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                kindLabel(t.kind) + " · " + rowDetail(t),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (t.seconds > 0L) {
            Spacer(Modifier.width(8.dp))
            Text(
                WatchStats.durationLabel(t.seconds),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * The sheet that opens under the headline figures when one of them is tapped:
 * every title that made the figure up, or every active day, with the day the
 * heatmap has picked scoping the first two. It renders IN the page rather than
 * on a second screen, so the figure that opened it stays on screen (tapping it
 * again closes the sheet) and a day can be picked from inside it.
 */
@Composable
private fun PanelCard(
    title: String,
    scopeDay: String?,
    onClose: () -> Unit,
    onScopeAllTime: () -> Unit,
    rows: List<WatchStats.TitleTotal>,
    dayRows: List<Pair<String, WatchStats.Day>>,
    onPickDay: (String) -> Unit,
    emptyText: String,
) {
    GlassCard(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.BarChart,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (scopeDay == null) tr("All time") else WatchStats.prettyDay(scopeDay),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // Only offered when a day is actually scoping the sheet: from
                // "all time" there is nothing to go back to.
                if (scopeDay != null) {
                    TextButton(onClick = onScopeAllTime) { Text(tr("All time")) }
                }
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = tr("Close"),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))
            Spacer(Modifier.height(6.dp))
            if (dayRows.isNotEmpty()) {
                dayRows.forEach { (key, day) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onPickDay(key) }
                            .padding(horizontal = 4.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                WatchStats.prettyDay(key),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                daySummary(day),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            WatchStats.durationLabel(day.seconds),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            } else if (rows.isNotEmpty()) {
                rows.forEach { t -> StatsTitleRow(t) }
            } else {
                Text(
                    emptyText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }
        }
    }
}
