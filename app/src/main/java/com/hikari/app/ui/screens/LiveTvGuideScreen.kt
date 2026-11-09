package com.hikari.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.Schedule
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.hikari.app.i18n.tr
import com.hikari.app.iptv.EpgProgram
import com.hikari.app.iptv.LiveChannel
import com.hikari.app.iptv.LiveChannelGroup
import com.hikari.app.iptv.LiveTvEngine
import com.hikari.app.iptv.LiveTvPlayback
import com.hikari.app.iptv.LiveTvSettings
import com.hikari.app.ui.components.GlassCard
import com.hikari.app.ui.components.SettingsPageHeader
import com.hikari.app.ui.navigation.LocalTaskbarInset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The TV guide: one group at a time, every channel with its shows for the day.
 * Playing a live show tunes the channel; a finished show with catch-up plays
 * its replay; an upcoming show can set a reminder or a recording.
 */
@Composable
fun LiveTvGuideScreen(nav: NavHostController) {
    LiveTvInit()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableStateOf(0) }
    var settings by remember { mutableStateOf(LiveTvSettings()) }
    var groups by remember { mutableStateOf<List<LiveChannelGroup>>(emptyList()) }
    var groupId by remember { mutableStateOf<String?>(null) }
    var channels by remember { mutableStateOf<List<LiveChannel>>(emptyList()) }
    var dayStart by remember { mutableStateOf(startOfToday()) }
    var loading by remember { mutableStateOf(true) }
    var programAction by remember { mutableStateOf<Triple<LiveChannel, EpgProgram, Boolean>?>(null) }
    var warmed by remember { mutableStateOf(false) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    LaunchedEffect(tick) {
        loading = true
        val data = withContext(Dispatchers.IO) {
            runCatching { LiveTvEngine.init(context) }
            val s = LiveTvEngine.store.settings()
            val g = LiveTvEngine.groupsOf(null)
            val gid = groupId?.takeIf { id -> g.any { it.id == id } }
                ?: g.firstOrNull { it.id == LiveChannelGroup.ALL }?.id
                ?: g.firstOrNull()?.id
            val chans = if (gid == null) emptyList()
            else LiveTvEngine.channelsInGroup(g.first { it.id == gid }, null)
            Triple(s, g, chans to gid)
        }.getOrDefault(Triple(LiveTvSettings(), emptyList(), emptyList<LiveChannel>() to null))
        settings = data.first
        groups = data.second
        channels = data.third.first
        if (groupId == null) groupId = data.third.second
        loading = false
        // Cold start paints what is cached, then refreshes stale sources once
        // in the background and repaints (a dead source must not hold the
        // first paint hostage — refresh has a long timeout).
        if (!warmed) {
            warmed = true
            scope.launch {
                withContext(Dispatchers.IO) { runCatching { LiveTvEngine.ensureFresh() } }
                tick++
            }
        }
    }

    val group = groups.firstOrNull { it.id == groupId }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp, end = 16.dp, top = 16.dp,
            bottom = LocalTaskbarInset.current + 16.dp,
        ),
    ) {
        item {
            SettingsPageHeader(
                title = tr("Guide"),
                subtitle = group?.let {
                    tr("%s channels").replace("%s", channels.size.toString())
                } ?: tr("TV guide"),
                onBack = { nav.popBackStack() },
            )
        }
        if (loading) {
            item { LiveTvLoading(tr("Reading the guide…")) }
            return@LazyColumn
        }
        if (groups.isEmpty() || channels.isEmpty()) {
            item {
                Text(
                    tr("No channels yet — add a playlist or an Xtream login in Live TV settings."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            return@LazyColumn
        }
        // group picker
        item {
            LazyRow(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                items(groups, key = { it.id }) { g ->
                    val on = g.id == groupId
                    Text(
                        g.title + " (" + g.count + ")",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                        color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .clip(RoundedCornerShape(50))
                            .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                            .clickable {
                                groupId = g.id
                                scope.launch {
                                    channels = withContext(Dispatchers.IO) {
                                        LiveTvEngine.channelsInGroup(g, null)
                                    }
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
        // day picker (yesterday → daysAhead)
        item {
            val days = remember(settings) {
                val out = ArrayList<Long>()
                var d = startOfToday() - 86_400_000L
                val end = startOfToday() + settings.daysAhead * 86_400_000L
                var guard = 0
                while (d <= end && guard++ < 16) {
                    out += d
                    d += 86_400_000L
                }
                out
            }
            LazyRow(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                items(days, key = { it }) { d ->
                    val on = d == dayStart
                    Text(
                        liveTvDay(d),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                        color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .clip(RoundedCornerShape(50))
                            .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { dayStart = d }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
        items(channels, key = { it.key }) { channel ->
            GuideChannelRow(
                channel = channel,
                dayStart = dayStart,
                settings = settings,
                onPlay = {
                    if (LiveTvEngine.isLockedGroup(channel.groupId, channel.group)) {
                        toast(tr("That group is locked."))
                        return@GuideChannelRow
                    }
                    scope.launch {
                        withContext(Dispatchers.IO) { LiveTvPlayback.playChannel(context, channel) }
                        tick++
                    }
                },
                onProgram = { program, live ->
                    programAction = Triple(channel, program, live)
                },
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    programAction?.let { (channel, program, live) ->
        GuideProgramSheet(
            channel = channel,
            program = program,
            live = live,
            settings = settings,
            onDismiss = { programAction = null },
            onChanged = { tick++ },
        )
    }
}

private fun startOfToday(): Long {
    val cal = java.util.Calendar.getInstance()
    cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
    cal.set(java.util.Calendar.MINUTE, 0)
    cal.set(java.util.Calendar.SECOND, 0)
    cal.set(java.util.Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

@Composable
private fun GuideChannelRow(
    channel: LiveChannel,
    dayStart: Long,
    settings: LiveTvSettings,
    onPlay: () -> Unit,
    onProgram: (EpgProgram, Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var programs by remember(channel.key, dayStart) { mutableStateOf<List<EpgProgram>?>(null) }
    LaunchedEffect(channel.key, dayStart) {
        programs = withContext(Dispatchers.IO) {
            runCatching {
                LiveTvEngine.guideWindow(channel, dayStart, dayStart + 86_400_000L)
            }.getOrDefault(emptyList())
        }
    }
    val now = System.currentTimeMillis()
    GlassCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (settings.showLogos && !channel.logo.isNullOrBlank()) {
                    AsyncImage(
                        model = channel.logo,
                        contentDescription = null,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                if (settings.showNumbers && channel.number > 0) {
                    Text(
                        channel.number.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    channel.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).clickable { onPlay() },
                )
                IconButton(onClick = onPlay, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }
            val list = programs
            if (list == null) {
                Text(
                    tr("Reading…"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else if (list.isEmpty()) {
                Text(
                    tr("No guide for this channel today."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else {
                Spacer(Modifier.height(6.dp))
                LazyRow {
                    items(list, key = { it.startMs }) { p ->
                        val live = now in p.startMs until p.stopMs
                        GuideProgramChip(
                            program = p,
                            live = live,
                            use24h = settings.use24HourClock,
                            showTimes = settings.showProgramTimes,
                            showEpisode = settings.showEpisodeNumbers,
                            showNowLine = settings.showNowLine,
                        ) { onProgram(p, live) }
                    }
                }
            }
        }
    }
    // Keep the row honest while a show ends: one refresh, a minute out.
    LaunchedEffect(channel.key, dayStart) {
        scope.launch {
            kotlinx.coroutines.delay(60_000L)
        }
    }
}

@Composable
private fun GuideProgramChip(
    program: EpgProgram,
    live: Boolean,
    use24h: Boolean,
    showTimes: Boolean,
    showEpisode: Boolean,
    showNowLine: Boolean,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .width(168.dp)
            .padding(end = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (live) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            )
            .clickable { onClick() }
            .padding(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (live && showNowLine) {
                Icon(Icons.Filled.FiberManualRecord, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(10.dp))
                Spacer(Modifier.width(4.dp))
            }
            if (showTimes || live) {
                Text(
                    liveTvTime(program.startMs, use24h),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (live) FontWeight.Bold else FontWeight.Normal,
                    color = if (live) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                program.title,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (live) FontWeight.Bold else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        if (showEpisode && !program.episode.isNullOrBlank()) {
            Text(
                program.episode.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!program.description.isNullOrBlank()) {
            Text(
                program.description.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** What a tap on a show offers: play / replay / remind / record. */
@Composable
fun GuideProgramSheet(
    channel: LiveChannel,
    program: EpgProgram,
    live: Boolean,
    settings: LiveTvSettings,
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val now = System.currentTimeMillis()
    val past = program.stopMs <= now

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(program.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        text = {
            Column {
                Text(
                    channel.name + " · " +
                        liveTvDay(program.startMs) + " " +
                        liveTvTime(program.startMs, settings.use24HourClock) + " – " +
                        liveTvTime(program.stopMs, settings.use24HourClock),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!program.description.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(program.description.orEmpty(), style = MaterialTheme.typography.bodySmall, maxLines = 5, overflow = TextOverflow.Ellipsis)
                }
                if (!program.category.isNullOrBlank() || !program.episode.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        listOfNotNull(program.category, program.episode).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (live || (!past && channel.catchup == null)) {
                    TextButton(
                        onClick = {
                            onDismiss()
                            scope.launch {
                                withContext(Dispatchers.IO) { LiveTvPlayback.playChannel(context, channel) }
                                onChanged()
                            }
                        }
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(tr(if (live) "Watch now" else "Tune channel"))
                    }
                }
                if (past && channel.catchup != null) {
                    TextButton(
                        onClick = {
                            onDismiss()
                            scope.launch {
                                withContext(Dispatchers.IO) { LiveTvPlayback.playReplay(context, channel, program) }
                                onChanged()
                            }
                        }
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(tr("Play replay"))
                    }
                }
                if (!past) {
                    TextButton(
                        onClick = {
                            onDismiss()
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    com.hikari.app.iptv.LiveTvReminders.add(channel, program)
                                }
                                toast(tr("Reminder set"))
                                onChanged()
                            }
                        }
                    ) {
                        Icon(Icons.Filled.Schedule, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(tr("Remind me"))
                    }
                    TextButton(
                        onClick = {
                            onDismiss()
                            scope.launch {
                                val err = withContext(Dispatchers.IO) {
                                    runCatching {
                                        com.hikari.app.iptv.LiveTvRecordings.schedule(
                                            channel, program.title, program.startMs, program.stopMs,
                                        )
                                        com.hikari.app.iptv.LiveTvRecordScheduler.rescan()
                                        null
                                    }.getOrElse { it.message }
                                }
                                toast(err ?: tr("Recording scheduled"))
                                onChanged()
                            }
                        }
                    ) {
                        Icon(Icons.Filled.RadioButtonChecked, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(tr("Record"))
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("Close")) }
        },
    )
}
