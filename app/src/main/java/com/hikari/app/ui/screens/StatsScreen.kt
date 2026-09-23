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
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material3.Icon
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
                )
                StatTile(
                    label = tr("Items consumed"),
                    value = snapshot.totalItems.toString(),
                    icon = Icons.Filled.Movie,
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = tr("Days active"),
                    value = snapshot.daysActive.toString(),
                    icon = Icons.Filled.CalendarMonth,
                    modifier = Modifier.weight(1f),
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
                )
                StatTile(
                    label = tr("Avg chapters"),
                    value = String.format(java.util.Locale.US, "%.1f", snapshot.averageChaptersPerDay) + "/" +
                        tr("day"),
                    icon = Icons.Filled.MenuBook,
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = tr("Streak (Cur/Long)"),
                    value = snapshot.currentStreak(today).toString() + "d / " +
                        snapshot.longestStreak.toString() + "d",
                    icon = Icons.Filled.LocalFireDepartment,
                    modifier = Modifier.weight(1f),
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
                                                    .clickable { pickedDay = cell.dayKey },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                WatchStats.prettyDay(pickedDay),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            val secs = snapshot.secondsOn(pickedDay)
                            Text(
                                if (secs <= 0L) tr("No activity")
                                else WatchStats.durationLabel(secs) + " " + tr("watched"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
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

/** One of the small figures: a label, an icon, and the number under them. */
@Composable
private fun StatTile(
    label: String,
    value: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    GlassCard(modifier) {
        Column(Modifier.padding(horizontal = 11.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
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
                style = MaterialTheme.typography.titleLarge,
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
