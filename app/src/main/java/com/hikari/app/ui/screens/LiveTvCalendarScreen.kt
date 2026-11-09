package com.hikari.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.hikari.app.i18n.tr
import com.hikari.app.iptv.EpgProgram
import com.hikari.app.iptv.LiveChannel
import com.hikari.app.iptv.LiveTvEngine
import com.hikari.app.iptv.LiveTvPlayback
import com.hikari.app.iptv.LiveTvReminder
import com.hikari.app.ui.components.GlassCard
import com.hikari.app.ui.components.SettingsPageHeader
import com.hikari.app.ui.navigation.LocalTaskbarInset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Calendar: reminders, scheduled recordings and followed-team games for the
 * coming days, in one list.
 */
@Composable
fun LiveTvCalendarScreen(nav: NavHostController) {
    LiveTvInit()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var entries by remember { mutableStateOf<List<CalEntry>>(emptyList()) }
    var settings by remember { mutableStateOf<com.hikari.app.iptv.LiveTvSettings?>(null) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    LaunchedEffect(tick) {
        loading = true
        val data = withContext(Dispatchers.IO) {
            runCatching { LiveTvEngine.init(context) }
            val now = System.currentTimeMillis()
            val out = ArrayList<CalEntry>()
            // reminders
            com.hikari.app.iptv.LiveTvReminders.all().filter { it.startMs + 10 * 60_000L > now }.forEach { r ->
                out += CalEntry(r.startMs, CalKind.REMINDER, r.title, r.channelName, reminder = r)
            }
            // scheduled recordings
            com.hikari.app.iptv.LiveTvRecordings.schedules().filter { it.stopMs > now }.forEach { s ->
                out += CalEntry(s.startMs, CalKind.RECORD, s.title, s.channelName)
            }
            // followed-team games (already-loaded windows)
            val followed = LiveTvEngine.sports.followedTeams()
            if (followed.isNotEmpty() && LiveTvEngine.store.settings().calendarSports) {
                val leagues = LiveTvEngine.sports.leagues()
                leagues.take(8).forEach { league ->
                    runCatching { LiveTvEngine.sports.refreshLeague(league, 0, 3) }
                }
                leagues.forEach { league ->
                    LiveTvEngine.sports.eventsOf(league.id).filter { it.startMs > now - 3 * 3_600_000L }.forEach { e ->
                        val mine = followed.any { uid ->
                            val parts = uid.split('|')
                            parts.size == 2 && parts[0] == e.leagueId &&
                                (parts[1] == e.homeAbbr || parts[1] == e.awayAbbr)
                        }
                        if (mine) out += CalEntry(e.startMs, CalKind.GAME, e.title, e.leagueName, gameId = e.id)
                    }
                }
            }
            out.sortedBy { it.at } to LiveTvEngine.store.settings()
        }.getOrDefault(emptyList<CalEntry>() to null)
        entries = data.first
        settings = data.second
        loading = false
    }

    val use24h = settings?.use24HourClock == true
    val byDay = remember(entries) { entries.groupBy { dayKeyOf(it.at) } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp, end = 16.dp, top = 16.dp,
            bottom = LocalTaskbarInset.current + 16.dp,
        ),
    ) {
        item {
            SettingsPageHeader(
                title = tr("Calendar"),
                subtitle = tr("Reminders, recordings and games"),
                onBack = { nav.popBackStack() },
            )
        }
        if (loading) {
            item { LiveTvLoading(tr("Reading the calendar…")) }
            return@LazyColumn
        }
        if (entries.isEmpty()) {
            item {
                Text(
                    tr("Nothing coming up. Set a reminder or a recording from the guide, or follow a team in Sports."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            return@LazyColumn
        }
        byDay.entries.sortedBy { it.key }.forEach { (day, list) ->
            item(key = "day:$day") {
                Text(
                    liveTvDay(day),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 16.dp, bottom = 2.dp),
                )
            }
            items(list, key = { it.kind.name + ":" + it.at + ":" + it.title }) { entry ->
                CalRow(
                    entry = entry,
                    use24h = use24h,
                    onDeleteReminder = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                entry.reminder?.let { com.hikari.app.iptv.LiveTvReminders.remove(it.id) }
                            }
                            tick++
                        }
                    },
                )
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

private enum class CalKind { REMINDER, RECORD, GAME }

private data class CalEntry(
    val at: Long,
    val kind: CalKind,
    val title: String,
    val sub: String,
    val reminder: LiveTvReminder? = null,
    val gameId: String? = null,
)

private fun dayKeyOf(ms: Long): Long {
    val cal = java.util.Calendar.getInstance()
    cal.timeInMillis = ms
    cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
    cal.set(java.util.Calendar.MINUTE, 0)
    cal.set(java.util.Calendar.SECOND, 0)
    cal.set(java.util.Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

@Composable
private fun CalRow(entry: CalEntry, use24h: Boolean, onDeleteReminder: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    GlassCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    entry.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    liveTvTime(entry.at, use24h) + " · " + entry.sub + " · " + when (entry.kind) {
                        CalKind.REMINDER -> "reminder"
                        CalKind.RECORD -> "recording"
                        else -> "game"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (entry.kind == CalKind.REMINDER) {
                IconButton(onClick = onDeleteReminder) {
                    Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
