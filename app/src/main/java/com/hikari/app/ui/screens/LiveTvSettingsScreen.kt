package com.hikari.app.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import com.hikari.app.HikariApp
import com.hikari.app.i18n.I18n
import com.hikari.app.i18n.tr
import com.hikari.app.data.NetworkStream
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.ProviderType
import com.hikari.app.providers.IptvProvider
import com.hikari.app.iptv.LiveTvEngine
import com.hikari.app.iptv.LiveTvEpgSource
import com.hikari.app.iptv.LiveTvSettings
import com.hikari.app.iptv.LiveTvSource
import com.hikari.app.tv.tvTextFieldKeys
import com.hikari.app.ui.components.ChoiceRow
import com.hikari.app.ui.components.GlassCard
import com.hikari.app.ui.components.SettingsPageHeader
import com.hikari.app.ui.navigation.LocalTaskbarInset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Live TV settings: sources, guides, playback, display, On Demand, recordings, PIN, backup. */
@Composable
fun LiveTvSettingsScreen(nav: NavHostController) {
    LiveTvInit()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableStateOf(0) }
    var settings by remember { mutableStateOf(LiveTvSettings()) }
    var sources by remember { mutableStateOf<List<LiveTvSource>>(emptyList()) }
    var epgs by remember { mutableStateOf<List<LiveTvEpgSource>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var updating by remember { mutableStateOf(false) }
    var showXtream by remember { mutableStateOf(false) }
    var showM3u by remember { mutableStateOf(false) }
    var showEpg by remember { mutableStateOf(false) }
    var removeSource by remember { mutableStateOf<LiveTvSource?>(null) }
    var removeEpg by remember { mutableStateOf<LiveTvEpgSource?>(null) }
    var pinDraft by remember { mutableStateOf("") }
    var lockAdult by remember { mutableStateOf(true) }
    // Torrents & streams (the old Playlists page's other two tabs): provider
    // rows owned here, played from home.
    var showStreamAdd by remember { mutableStateOf(false) }
    var streamStartTorrent by remember { mutableStateOf(false) }
    var removeStream by remember { mutableStateOf<ProviderConfig?>(null) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    LaunchedEffect(tick) {
        loading = true
        val triple = withContext(Dispatchers.IO) {
            runCatching {
                runCatching { LiveTvEngine.init(context) }
                Triple(LiveTvEngine.store.settings(), LiveTvEngine.sources(), LiveTvEngine.epgSources())
            }.getOrDefault(Triple(LiveTvSettings(), emptyList(), emptyList()))
        }
        settings = triple.first
        sources = triple.second
        epgs = triple.third
        lockAdult = runCatching { LiveTvEngine.store.userState().lockAdultContent }.getOrElse { true }
        loading = false
    }

    fun save(next: LiveTvSettings) {
        settings = next
        scope.launch {
            withContext(Dispatchers.IO) { LiveTvEngine.store.saveSettings(next) }
        }
    }

    fun updateNow() {
        updating = true
        scope.launch {
            val err = withContext(Dispatchers.IO) {
                runCatching {
                    LiveTvEngine.sources().filter { it.enabled }.forEach { LiveTvEngine.refreshSource(it.id) }
                    LiveTvEngine.epgSources().filter { it.enabled }.forEach { LiveTvEngine.refreshEpgNow(it.id) }
                    null
                }.getOrElse { it.message }
            }
            updating = false
            if (err == null) {
                toast(I18n.t("Everything is up to date"))
                tick++
            } else {
                toast(err)
                tick++
            }
        }
    }

    val app = context.applicationContext as HikariApp
    val allProviders by remember { app.providers.providers }.collectAsState()
    val torStreams = remember(allProviders) {
        allProviders.filterIsInstance<IptvProvider>().map { it.config }.filter {
            it.type == ProviderType.IPTV &&
                (NetworkStream.isStream(it) || NetworkStream.isTorrentLink(it.url))
        }
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val err = withContext(Dispatchers.IO) {
                runCatching {
                    val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                        ?: throw IllegalStateException("Could not read that file.")
                    LiveTvEngine.store.importAll(org.json.JSONObject(text))
                    LiveTvEngine.resyncProviderRows()
                    null
                }.getOrElse { it.message ?: "Import failed." }
            }
            if (err == null) {
                toast(I18n.t("Backup imported"))
                tick++
            } else {
                toast(err)
            }
        }
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
                title = tr("Live TV settings"),
                subtitle = tr("Playlists, guides, playback and recordings"),
                onBack = { nav.popBackStack() },
            )
        }
        if (loading) {
            item { LiveTvLoading(tr("Reading Live TV…")) }
            return@LazyColumn
        }

        // ---- update now ----
        item {
            GlassCard(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("Update everything now"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(
                            tr("Re-reads every playlist and guide, ignoring the refresh intervals."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (updating) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else IconButton(onClick = { updateNow() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }

        // ---- playlists ----
        item {
            LiveTvSectionTitle(tr("Playlists"))
        }
        items(sources, key = { "src:" + it.id }) { src ->
            GlassCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(src.name.ifBlank { tr("Playlist") }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text(
                                if (src.isXtream) tr("Xtream login") + " · " + src.xtreamServer
                                else src.url.take(80),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = src.enabled,
                            onCheckedChange = { on ->
                                scope.launch {
                                    withContext(Dispatchers.IO) { LiveTvEngine.setSourceEnabled(src.id, on) }
                                    tick++
                                }
                            },
                        )
                    }
                    val status = buildString {
                        if (src.accountStatus.isNotBlank()) append(src.accountStatus).append(" · ")
                        if (src.accountExpiresMs > 0) {
                            append(
                                tr("expires %s").replace(
                                    "%s",
                                    SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(src.accountExpiresMs)),
                                )
                            ).append(" · ")
                        }
                        if (src.accountConnections.isNotBlank()) append(src.accountConnections).append(" · ")
                        append(
                            if (src.channelCount > 0) tr("%s channels").replace("%s", src.channelCount.toString()) + " · " else ""
                        )
                        append(
                            if (src.lastUpdatedMs > 0) tr("updated %s").replace("%s", liveTvDay(src.lastUpdatedMs) + " " + liveTvClock(src.lastUpdatedMs))
                            else tr("never updated")
                        )
                    }
                    Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!src.lastError.isNullOrBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(src.lastError.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    toast(I18n.t("Updating…"))
                                    withContext(Dispatchers.IO) { LiveTvEngine.refreshSource(src.id) }
                                    tick++
                                }
                            }
                        ) { Text(tr("Update")) }
                        if (src.isXtream) {
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        val err = withContext(Dispatchers.IO) {
                                            runCatching {
                                                LiveTvEngine.store.updateSources { list ->
                                                    list.map {
                                                        if (it.id == src.id) it.copy(importVod = !it.importVod) else it
                                                    }
                                                }
                                                if (!src.importVod) LiveTvEngine.vod.refreshSource(
                                                    LiveTvEngine.sourceOf(src.id) ?: src,
                                                )
                                                null
                                            }.getOrElse { it.message }
                                        }
                                        if (err != null) toast(err)
                                        tick++
                                    }
                                }
                            ) { Text(if (src.importVod) tr("On Demand: on") else tr("On Demand: off")) }
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { removeSource = src }) {
                            Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { showXtream = true }) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(tr("Add Xtream login"))
                }
                TextButton(onClick = { showM3u = true }) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(tr("Add M3U"))
                }
            }
        }

        // ---- torrents & streams (the old Playlists page's other two tabs) ----
        item { LiveTvSectionTitle(tr("Torrents & streams")) }
        items(torStreams, key = { "tor:" + it.id }) { p ->
            val torrent = NetworkStream.isTorrentLink(p.url)
            TorrentStreamRow(
                name = p.name.ifBlank { tr("Stream") },
                subtitle = if (torrent) tr("Torrent")
                else runCatching { NetworkStream.hostOf(p.url) }.getOrDefault(p.url).take(80),
                torrent = torrent,
                onOpen = { openTorrentStream(nav, p) },
                onRemove = { removeStream = p },
            )
        }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // HIDDEN_ADD_STREAM_TODO_UNHIDE: "Add stream" hidden per owner request — kept (not removed) so it can be unhidden later by removing this guard.
                if (false) {
                    TextButton(onClick = { streamStartTorrent = false; showStreamAdd = true }) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(tr("Add stream"))
                    }
                }
                TextButton(onClick = { streamStartTorrent = true; showStreamAdd = true }) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(tr("Add torrent"))
                }
            }
        }

        // ---- guides ----
        item { LiveTvSectionTitle(tr("Guides")) }
        items(epgs, key = { "epg:" + it.id }) { src ->
            GlassCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(src.name.ifBlank { tr("Guide") }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(
                            (if (src.programCount > 0) tr("%s shows").replace("%s", src.programCount.toString()) + " · " else "") +
                                if (src.lastUpdatedMs > 0) tr("updated %s").replace(
                                    "%s",
                                    liveTvDay(src.lastUpdatedMs) + " " + liveTvClock(src.lastUpdatedMs),
                                ) else tr("never updated"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!src.lastError.isNullOrBlank()) {
                            Text(src.lastError.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    Switch(
                        checked = src.enabled,
                        onCheckedChange = { on ->
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    LiveTvEngine.store.saveEpgSources(
                                        LiveTvEngine.epgSources().map { if (it.id == src.id) it.copy(enabled = on) else it },
                                    )
                                    if (on) LiveTvEngine.refreshEpgNow(src.id)
                                }
                                tick++
                            }
                        },
                    )
                    IconButton(onClick = { removeEpg = src }) {
                        Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        item {
            TextButton(onClick = { showEpg = true }) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(tr("Add guide source"))
            }
        }
        item {
            GlassCard(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    LiveTvIntRow(tr("Days ahead"), tr("How far into the future the guide keeps."), settings.daysAhead, 1..14) {
                        save(settings.copy(daysAhead = it))
                    }
                    LiveTvIntRow(tr("Hours back"), tr("How far back catch-up and the guide go."), settings.guideHoursBack, 0..72) {
                        save(settings.copy(guideHoursBack = it))
                    }
                    LiveTvIntRow(tr("Playlist refresh (hours)"), tr("How often playlists re-read on their own."), settings.playlistRefreshHours, 1..168) {
                        save(settings.copy(playlistRefreshHours = it))
                    }
                    LiveTvIntRow(tr("Guide refresh (hours)"), tr("How often guides re-read on their own."), settings.epgRefreshHours, 1..168) {
                        save(settings.copy(epgRefreshHours = it))
                    }
                    LiveTvIntRow(tr("Guide offset (minutes)"), tr("Shifts every show earlier or later (a wrong-timezone provider)."), settings.epgOffsetMinutes, -720..720, step = 30) {
                        save(settings.copy(epgOffsetMinutes = it))
                    }
                    LiveTvSwitchRow(tr("24-hour clock"), tr("14:00 instead of 2:00 PM in the guide."), settings.use24HourClock) {
                        save(settings.copy(use24HourClock = it))
                    }
                }
            }
        }

        // ---- playback ----
        item { LiveTvSectionTitle(tr("Playback")) }
        item {
            GlassCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    LiveTvChoiceRow(
                        tr("Stream format"),
                        tr("Xtream logins: which file the channels play."),
                        listOf("auto" to tr("Auto"), "m3u8" to "HLS", "ts" to "MPEG-TS"),
                        settings.streamFormatDefault,
                    ) { save(settings.copy(streamFormatDefault = it)) }
                    LiveTvSwitchRow(tr("Backup channels"), tr("Same-name channels from other playlists appear as extra servers."), settings.backupChannel) {
                        save(settings.copy(backupChannel = it))
                    }
                    LiveTvSwitchRow(tr("Continue Watching"), tr("Played channels gather in a Recently watched row."), settings.continueWatching) {
                        save(settings.copy(continueWatching = it))
                    }
                    LiveTvSwitchRow(tr("Remember audio track"), tr("The guide remembers the audio track picked per channel."), settings.rememberAudioTrack) {
                        save(settings.copy(rememberAudioTrack = it))
                    }
                    LiveTvSwitchRow(tr("Prefer HLS catch-up"), tr("Replays play as HLS (seekable) when the provider offers it."), settings.preferHlsCatchup) {
                        save(settings.copy(preferHlsCatchup = it))
                    }
                    LiveTvIntRow(tr("Catch-up shift (minutes)"), tr("Nudges every replay earlier or later."), settings.catchupShiftMinutes, -120..120, step = 5) {
                        save(settings.copy(catchupShiftMinutes = it))
                    }
                    LiveTvSwitchRow(tr("Auto-reconnect"), tr("A dropped live stream retries on its own."), settings.autoReconnect) {
                        save(settings.copy(autoReconnect = it))
                    }
                    LiveTvIntRow(tr("Reminder lead (minutes)"), tr("How far before a show the reminder fires."), settings.reminderLeadMinutes, 0..120, step = 5) {
                        save(settings.copy(reminderLeadMinutes = it))
                    }
                }
            }
        }

        // ---- channel display ----
        item { LiveTvSectionTitle(tr("Channel display")) }
        item {
            GlassCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    LiveTvSwitchRow(tr("Show logos"), "", settings.showLogos) { save(settings.copy(showLogos = it)) }
                    LiveTvSwitchRow(tr("Show names"), "", settings.showNames) { save(settings.copy(showNames = it)) }
                    LiveTvSwitchRow(tr("Show numbers"), "", settings.showNumbers) { save(settings.copy(showNumbers = it)) }
                    LiveTvSwitchRow(tr("Now-line"), tr("Marks the current time across the guide."), settings.showNowLine) { save(settings.copy(showNowLine = it)) }
                    LiveTvSwitchRow(tr("Show program times"), tr("Start times beside every show in the guide."), settings.showProgramTimes) { save(settings.copy(showProgramTimes = it)) }
                    LiveTvSwitchRow(tr("Show episode numbers"), tr("S01 E02 style tags when the guide carries them."), settings.showEpisodeNumbers) { save(settings.copy(showEpisodeNumbers = it)) }
                    LiveTvSwitchRow(tr("Sequential numbers"), tr("Channels numbered 1, 2, 3… in list order."), settings.sequentialNumbers) { save(settings.copy(sequentialNumbers = it)) }
                    Spacer(Modifier.height(4.dp))
                    Text(tr("Name cleanup"), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        tr("Comma-separated words stripped from the ends of channel names (e.g. %s).").replace("%s", "USA, FHD, 24/7"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    var draft by remember(settings.nameRemovals) { mutableStateOf(settings.nameRemovals) }
                    OutlinedTextField(
                        value = draft, onValueChange = { draft = it },
                        placeholder = { Text("USA, FHD, 24/7") }, singleLine = false, maxLines = 2,
                        modifier = Modifier.fillMaxWidth().tvTextFieldKeys(draft),
                    )
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { save(settings.copy(nameRemovals = draft)) }) { Text(tr("Apply")) }
                }
            }
        }

        // ---- on demand ----
        item { LiveTvSectionTitle(tr("On Demand")) }
        item {
            GlassCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    LiveTvSwitchRow(tr("On Demand in streams"), tr("Movies and episodes appear beside live servers where relevant."), settings.onDemandInStreams) {
                        save(settings.copy(onDemandInStreams = it))
                    }
                    LiveTvSwitchRow(tr("Merge duplicates"), tr("One row per title when several logins carry it."), settings.vodMergeDuplicates) {
                        save(settings.copy(vodMergeDuplicates = it))
                    }
                    LiveTvChoiceRow(
                        tr("Default sort"), "",
                        listOf("DEFAULT" to tr("Default"), "NAME" to tr("Name"), "NEWEST" to tr("Newest"), "RATING" to tr("Rating")),
                        settings.vodDefaultSort,
                    ) { save(settings.copy(vodDefaultSort = it)) }
                }
            }
        }

        // ---- recordings ----
        item { LiveTvSectionTitle(tr("Recordings")) }
        item {
            GlassCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    LiveTvIntRow(tr("Start early (minutes)"), tr("Padding before each scheduled recording."), settings.recordPreMinutes, 0..60) {
                        save(settings.copy(recordPreMinutes = it))
                    }
                    LiveTvIntRow(tr("Run late (minutes)"), tr("Padding after each scheduled recording."), settings.recordPostMinutes, 0..120) {
                        save(settings.copy(recordPostMinutes = it))
                    }
                    LiveTvIntRow(tr("Keep (series rules)"), tr("Newest recordings kept per series rule (0 = all)."), settings.recordSeriesKeep, 0..99) {
                        save(settings.copy(recordSeriesKeep = it))
                    }
                    LiveTvChoiceRow(
                        tr("When space runs out"), "",
                        listOf("STOP" to tr("Stop recording"), "DELETE_OLDEST" to tr("Delete oldest first")),
                        settings.recordSpaceRule,
                    ) { save(settings.copy(recordSpaceRule = it)) }
                }
            }
        }

        // ---- parental ----
        item { LiveTvSectionTitle(tr("Parental")) }
        item {
            GlassCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    LiveTvSwitchRow(tr("Lock adult groups"), tr("Groups with adult names lock automatically once a PIN is set."), lockAdult) {
                        lockAdult = it
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                LiveTvEngine.store.updateUserState { st -> st.copy(lockAdultContent = it) }
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(tr("Parental PIN"), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        tr("Four or more digits. Empty turns the lock off."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = pinDraft, onValueChange = { pinDraft = it.filter { c -> c.isDigit() }.take(8) },
                        placeholder = { Text(if (LiveTvEngine.store.userState().parentalPin.isNotBlank()) tr("PIN is set") else tr("Set a PIN")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().tvTextFieldKeys(pinDraft),
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = {
                                if (pinDraft.trim().length < 4) {
                                    toast(I18n.t("Use at least 4 digits."))
                                    return@TextButton
                                }
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        com.hikari.app.iptv.LiveTvParental.setPin(pinDraft.trim())
                                    }
                                    pinDraft = ""
                                    toast(I18n.t("PIN set"))
                                }
                            }
                        ) { Text(tr("Set PIN")) }
                        TextButton(
                            onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { com.hikari.app.iptv.LiveTvParental.setPin("") }
                                    pinDraft = ""
                                    toast(I18n.t("PIN removed"))
                                }
                            }
                        ) { Text(tr("Remove")) }
                    }
                }
            }
        }

        // ---- backup ----
        item { LiveTvSectionTitle(tr("Backup")) }
        item {
            GlassCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("Export / import"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(
                            tr("Everything on this screen as one file: sources, guides, settings, favorites and PIN."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = {
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    runCatching {
                                        val json = LiveTvEngine.store.exportAll().toString()
                                        val dir = File(context.filesDir, "livetv")
                                        dir.mkdirs()
                                        val f = File(
                                            dir,
                                            "livetv-backup-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".json",
                                        )
                                        f.writeText(json)
                                        f
                                    }.getOrNull()
                                }
                                if (ok != null) {
                                    shareFiles(context, listOf(ok), "Live TV backup")
                                    tick++
                                } else {
                                    toast(I18n.t("Export failed"))
                                }
                            }
                        }
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = { importer.launch(arrayOf("application/json", "*/*")) }) {
                        Icon(Icons.Filled.Download, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    if (showXtream) {
        AddXtreamDialog(onDone = { showXtream = false; tick++ }, onDismiss = { showXtream = false })
    }
    if (showM3u) {
        AddM3uDialog(onDone = { showM3u = false; tick++ }, onDismiss = { showM3u = false })
    }
    if (showEpg) {
        AddEpgDialog(onDone = { showEpg = false; tick++ }, onDismiss = { showEpg = false })
    }
    if (showStreamAdd) {
        TorrentStreamAddDialog(
            nav,
            startAsTorrent = streamStartTorrent,
            onDone = { showStreamAdd = false; tick++ },
            onDismiss = { showStreamAdd = false },
        )
    }
    removeStream?.let { p ->
        AlertDialog(
            onDismissRequest = { removeStream = null },
            title = { Text(I18n.t("Remove %s?").replace("%s", p.name)) },
            text = { Text(tr("It will no longer open from here.")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        removeStream = null
                        scope.launch {
                            removeTorrentStreamProvider(app, p.id)
                            tick++
                        }
                    }
                ) { Text(tr("Remove"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { removeStream = null }) { Text(tr("Cancel")) }
            },
        )
    }
    removeSource?.let { src ->
        AlertDialog(
            onDismissRequest = { removeSource = null },
            title = { Text(tr("Remove %s?").replace("%s", src.name)) },
            text = { Text(tr("Its channels, guide and provider row go away. Favorites pointing at it are forgotten.")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        removeSource = null
                        scope.launch {
                            withContext(Dispatchers.IO) { LiveTvEngine.removeSource(src.id) }
                            tick++
                        }
                    }
                ) { Text(tr("Remove"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { removeSource = null }) { Text(tr("Cancel")) }
            },
        )
    }
    removeEpg?.let { src ->
        AlertDialog(
            onDismissRequest = { removeEpg = null },
            title = { Text(tr("Remove %s?").replace("%s", src.name)) },
            text = { Text(tr("Its downloaded shows go away.")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        removeEpg = null
                        scope.launch {
                            withContext(Dispatchers.IO) { LiveTvEngine.removeEpg(src.id) }
                            tick++
                        }
                    }
                ) { Text(tr("Remove"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { removeEpg = null }) { Text(tr("Cancel")) }
            },
        )
    }
}

@Composable
private fun LiveTvSectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 2.dp),
    )
}

@Composable
private fun LiveTvSwitchRow(title: String, supporting: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (supporting.isNotBlank()) {
                Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun LiveTvIntRow(
    title: String,
    supporting: String,
    value: Int,
    range: IntRange,
    step: Int = 1,
    onChange: (Int) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (supporting.isNotBlank()) {
                Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        TextButton(
            enabled = value - step >= range.first,
            onClick = { onChange((value - step).coerceIn(range)) },
        ) { Text("−") }
        Text(
            value.toString(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(48.dp),
        )
        TextButton(
            enabled = value + step <= range.last,
            onClick = { onChange((value + step).coerceIn(range)) },
        ) { Text("+") }
    }
}

@Composable
private fun LiveTvChoiceRow(
    title: String,
    supporting: String,
    options: List<Pair<String, String>>,
    selected: String,
    onPick: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    ChoiceRow(
        value = title + ": " + (options.firstOrNull { it.first == selected }?.second ?: selected),
        supporting = supporting,
        onClick = { open = true },
    )
    if (open) {
        com.hikari.app.ui.components.ChoiceDialog(
            title = title,
            items = options.map { com.hikari.app.ui.components.ChoiceItem(it.first, it.second) },
            selectedKey = selected,
            onPick = { onPick(it) },
            onDismiss = { open = false },
        )
    }
}
