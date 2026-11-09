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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import com.hikari.app.iptv.ChannelMatch
import com.hikari.app.iptv.LiveTvEngine
import com.hikari.app.iptv.LiveTvPlayback
import com.hikari.app.iptv.SportsEvent
import com.hikari.app.iptv.SportsLeague
import com.hikari.app.tv.tvTextFieldKeys
import com.hikari.app.ui.components.GlassCard
import com.hikari.app.ui.components.SettingsPageHeader
import com.hikari.app.ui.navigation.LocalTaskbarInset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sports hub: leagues, live/finished games from ESPN, followed teams, and
 * which of your channels shows each game.
 */
@Composable
fun LiveTvSportsScreen(nav: NavHostController) {
    LiveTvInit()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableStateOf(0) }
    var leagues by remember { mutableStateOf<List<SportsLeague>>(emptyList()) }
    var leagueId by remember { mutableStateOf<String?>(null) }
    var events by remember { mutableStateOf<List<SportsEvent>>(emptyList()) }
    var followed by remember { mutableStateOf<Set<String>>(emptySet()) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var matchTarget by remember { mutableStateOf<SportsEvent?>(null) }
    var onlyFollowed by remember { mutableStateOf(false) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    LaunchedEffect(tick) {
        loading = true
        val data = withContext(Dispatchers.IO) {
            runCatching { LiveTvEngine.init(context) }
            val ls = LiveTvEngine.sports.leagues()
            val lid = leagueId?.takeIf { id -> ls.any { it.id == id } } ?: ls.firstOrNull()?.id
            Triple(ls, lid, LiveTvEngine.sports.followedTeams())
        }.getOrDefault(Triple(emptyList(), null, emptySet()))
        leagues = data.first
        if (leagueId == null) leagueId = data.second
        followed = data.third
        loading = false
    }

    LaunchedEffect(leagueId, tick) {
        val lid = leagueId ?: return@LaunchedEffect
        refreshing = true
        val league = leagues.firstOrNull { it.id == lid } ?: LiveTvEngine.sports.leagues().firstOrNull { it.id == lid }
        if (league != null) {
            events = withContext(Dispatchers.IO) {
                runCatching { LiveTvEngine.sports.refreshLeague(league) }.getOrNull()
                LiveTvEngine.sports.eventsOf(lid)
            }
        }
        refreshing = false
    }

    val shown = remember(events, followed, onlyFollowed) {
        var list = events
        if (onlyFollowed && followed.isNotEmpty()) {
            list = list.filter { e ->
                followed.any { uid ->
                    val parts = uid.split('|')
                    parts.size == 2 && parts[0] == e.leagueId &&
                        (parts[1] == e.homeAbbr || parts[1] == e.awayAbbr ||
                            e.homeName.contains(parts[1], ignoreCase = true) ||
                            e.awayName.contains(parts[1], ignoreCase = true))
                }
            }
        }
        val now = System.currentTimeMillis()
        list.sortedWith(
            compareByDescending<SportsEvent> { it.live }
                .thenBy { if (it.startMs >= now) 0 else 1 }
                .thenBy { kotlin.math.abs(it.startMs - now) }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp, end = 16.dp, top = 16.dp,
            bottom = LocalTaskbarInset.current + 16.dp,
        ),
    ) {
        item {
            SettingsPageHeader(
                title = tr("Sports"),
                subtitle = tr("Scores, teams and where to watch"),
                onBack = { nav.popBackStack() },
            )
        }
        if (loading) {
            item { LiveTvLoading(tr("Reading leagues…")) }
            return@LazyColumn
        }
        item {
            LazyRow(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                items(leagues, key = { it.id }) { l ->
                    val on = l.id == leagueId
                    Text(
                        l.short.ifBlank { l.name },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                        color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .clip(RoundedCornerShape(50))
                            .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { leagueId = l.id }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    tr("Followed only"),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = onlyFollowed, onCheckedChange = { onlyFollowed = it })
            }
        }
        if (refreshing && events.isEmpty()) {
            item { LiveTvLoading(tr("Reading scores…")) }
            return@LazyColumn
        }
        if (shown.isEmpty()) {
            item {
                Text(
                    tr("No games in this window."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            return@LazyColumn
        }
        items(shown, key = { it.id }) { event ->
            SportsEventRow(
                event = event,
                followed = followed,
                onFollow = { teamUid ->
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            LiveTvEngine.sports.followTeam(teamUid, teamUid !in followed)
                        }
                        tick++
                    }
                },
                onMatch = { matchTarget = event },
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    matchTarget?.let { event ->
        MatchSheet(event = event, onDismiss = { matchTarget = null })
    }
}

@Composable
private fun SportsEventRow(
    event: SportsEvent,
    followed: Set<String>,
    onFollow: (String) -> Unit,
    onMatch: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var teams by remember(event.leagueId) { mutableStateOf(LiveTvEngine.sports.teamsOf(event.leagueId)) }
    LaunchedEffect(event.leagueId) {
        teams = withContext(Dispatchers.IO) { LiveTvEngine.sports.teamsOf(event.leagueId) }
    }
    GlassCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TeamCrest(event.awayLogo, event.awayAbbr)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        event.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        liveTvDay(event.startMs) + " " + liveTvClock(event.startMs) +
                            (if (event.network.isNotBlank()) " · " + event.network else "") +
                            (if (event.clock.isNotBlank()) " · " + event.clock else "") +
                            (if (event.final) " · " + tr("Final") else "") +
                            (if (event.live) " · " + tr("LIVE") else ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (event.live) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TeamCrest(event.homeLogo, event.homeAbbr)
            }
            if (event.homeScore.isNotBlank() || event.awayScore.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "${event.awayName} ${event.awayScore} — ${event.homeScore} ${event.homeName}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButtonFollow(event.leagueId, event.awayAbbr, event.awayName, followed, onFollow)
                Spacer(Modifier.width(4.dp))
                TextButtonFollow(event.leagueId, event.homeAbbr, event.homeName, followed, onFollow)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onMatch) { Text(tr("Find channel")) }
            }
        }
    }
}

@Composable
private fun TeamCrest(logo: String?, abbr: String) {
    if (logo.isNullOrBlank()) {
        Text(
            abbr.take(3),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(4.dp),
        )
    } else {
        AsyncImage(
            model = logo,
            contentDescription = null,
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
    }
}

@Composable
private fun TextButtonFollow(
    leagueId: String,
    abbr: String,
    name: String,
    followed: Set<String>,
    onFollow: (String) -> Unit,
) {
    if (abbr.isBlank()) return
    val uid = "$leagueId|$abbr"
    val on = uid in followed
    Row(
        Modifier.clickable { onFollow(uid) }.padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (on) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(2.dp))
        }
        Text(
            abbr,
            style = MaterialTheme.typography.labelMedium,
            color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Which of your channels shows this game (guide → name → network). */
@Composable
private fun MatchSheet(event: SportsEvent, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var matches by remember(event.id) { mutableStateOf<List<ChannelMatch>?>(null) }

    LaunchedEffect(event.id) {
        matches = withContext(Dispatchers.IO) {
            runCatching { LiveTvEngine.sports.matchEvent(event) }.getOrDefault(emptyList())
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(event.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                val list = matches
                if (list == null) {
                    Text(tr("Looking through your channels…"), style = MaterialTheme.typography.bodySmall)
                } else if (list.isEmpty()) {
                    Text(
                        tr("None of your channels looks like it carries this game."),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    LazyColumn(modifier = Modifier.height(300.dp)) {
                        items(list, key = { it.channelKey }) { m ->
                            val channel = LiveTvEngine.channelOf(m.channelKey)
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(m.channelName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        when (m.how) {
                                            "guide" -> tr("In the guide") + (m.program?.let { " · " + it.title } ?: "")
                                            "name" -> tr("Channel name")
                                            else -> tr("Network")
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (channel != null) {
                                    IconButton(
                                        onClick = {
                                            onDismiss()
                                            scope.launch {
                                                withContext(Dispatchers.IO) {
                                                    LiveTvPlayback.playChannel(context, channel)
                                                }
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("Close")) }
        },
    )
}

/** Team picker: follow teams so their games surface in Sports and the calendar. */
@Composable
fun TeamFollowSheet(league: SportsLeague, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var teams by remember(league.id) { mutableStateOf(LiveTvEngine.sports.teamsOf(league.id)) }
    var followed by remember { mutableStateOf(LiveTvEngine.sports.followedTeams()) }

    LaunchedEffect(league.id) {
        withContext(Dispatchers.IO) {
            runCatching { LiveTvEngine.sports.refreshLeague(league) }
            teams = LiveTvEngine.sports.teamsOf(league.id)
        }
    }

    val shown = remember(teams, query) {
        val q = query.trim().lowercase()
        if (q.isBlank()) teams
        else teams.filter { it.name.lowercase().contains(q) || it.abbr.lowercase().contains(q) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Follow %s teams").replace("%s", league.name)) },
        text = {
            Column {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    label = { Text(tr("Search teams")) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().tvTextFieldKeys(query),
                )
                Spacer(Modifier.height(4.dp))
                LazyColumn(modifier = Modifier.height(320.dp)) {
                    items(shown, key = { it.id }) { t ->
                        val uid = LiveTvEngine.sports.teamUid(league.id, t.id)
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        LiveTvEngine.sports.followTeam(uid, uid !in followed)
                                        followed = LiveTvEngine.sports.followedTeams()
                                    }
                                }
                            }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TeamCrest(t.logo, t.abbr)
                            Spacer(Modifier.width(8.dp))
                            Text(t.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            if (uid in followed) {
                                Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("Done")) }
        },
    )
}
