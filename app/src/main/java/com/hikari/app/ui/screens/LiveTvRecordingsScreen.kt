package com.hikari.app.ui.screens

import android.widget.Toast
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.hikari.app.i18n.I18n
import com.hikari.app.i18n.tr
import com.hikari.app.iptv.LiveTvEngine
import com.hikari.app.iptv.LiveTvPlayback
import com.hikari.app.iptv.LiveTvRecording
import com.hikari.app.iptv.LiveTvRecordings
import com.hikari.app.iptv.RecordingSchedule
import com.hikari.app.iptv.SeriesRule
import com.hikari.app.ui.components.GlassCard
import com.hikari.app.ui.components.SettingsPageHeader
import com.hikari.app.ui.navigation.LocalTaskbarInset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Recordings: finished files (play/delete), the schedule queue and series
 * rules. Scheduled items record even when the tab is closed (foreground
 * service + alarms).
 */
@Composable
fun LiveTvRecordingsScreen(nav: NavHostController) {
    LiveTvInit()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableStateOf(0) }
    var recordings by remember { mutableStateOf<List<LiveTvRecording>>(emptyList()) }
    var schedules by remember { mutableStateOf<List<RecordingSchedule>>(emptyList()) }
    var rules by remember { mutableStateOf<List<SeriesRule>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var deleteTarget by remember { mutableStateOf<LiveTvRecording?>(null) }
    var seriesDraft by remember { mutableStateOf<SeriesRule?>(null) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    LaunchedEffect(tick) {
        loading = true
        val data = withContext(Dispatchers.IO) {
            runCatching {
                runCatching { LiveTvEngine.init(context) }
                Triple(LiveTvRecordings.recordings(), LiveTvRecordings.schedules(), LiveTvRecordings.seriesRules())
            }.getOrDefault(Triple(emptyList(), emptyList(), emptyList()))
        }
        recordings = data.first
        schedules = data.second
        rules = data.third
        loading = false
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
                title = tr("Recordings"),
                subtitle = if (recordings.isEmpty()) tr("Nothing recorded yet")
                else tr("%s file(s)").replace("%s", recordings.size.toString()),
                onBack = { nav.popBackStack() },
            )
        }
        if (loading) {
            item { LiveTvLoading(tr("Reading recordings…")) }
            return@LazyColumn
        }
        if (recordings.isNotEmpty()) {
            item {
                Text(
                    tr("Files"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            items(recordings, key = { it.id }) { rec ->
                GlassCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(rec.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                rec.channelName + " · " + liveTvDay(rec.startedAtMs) + " " +
                                    liveTvClock(rec.startedAtMs) + " · " +
                                    when {
                                        rec.isRecording -> tr("Recording…")
                                        rec.status.name == "DONE" -> tr("%s MB").replace(
                                            "%s",
                                            (rec.sizeBytes / (1024 * 1024)).toString(),
                                        )
                                        else -> rec.problem ?: tr("Failed")
                                    },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (!rec.isRecording && rec.status.name == "DONE") {
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) { LiveTvPlayback.playRecording(context, rec) }
                                    }
                                }
                            ) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        IconButton(onClick = { deleteTarget = rec }) {
                            Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
        if (schedules.isNotEmpty()) {
            item {
                Text(
                    tr("Scheduled"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
            items(schedules, key = { it.id }) { s ->
                GlassCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(s.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                s.channelName + " · " + liveTvDay(s.startMs) + " " +
                                    liveTvClock(s.startMs) + " – " + liveTvClock(s.stopMs),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(
                            onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { LiveTvRecordings.cancelSchedule(s.id) }
                                    tick++
                                }
                            }
                        ) { Text(tr("Cancel")) }
                    }
                }
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    tr("Series rules"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { seriesDraft = SeriesRule(id = "", channelKey = "", title = "") }) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(tr("Rule"))
                }
            }
            Text(
                tr("Records every new airing of a show on a channel."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(rules, key = { it.id }) { rule ->
            GlassCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(rule.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            rule.channelKey.substringAfter('|').take(60),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    LiveTvRecordings.saveSeriesRules(rules.filterNot { it.id == rule.id })
                                }
                                tick++
                            }
                        }
                    ) { Text(tr("Delete")) }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    deleteTarget?.let { rec ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(tr("Delete recording?")) },
            text = { Text(tr("“%s” leaves the device.").replace("%s", rec.title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTarget = null
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                runCatching { java.io.File(rec.dir).deleteRecursively() }
                            }
                            tick++
                        }
                    }
                ) { Text(tr("Delete"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(tr("Cancel")) }
            },
        )
    }

    seriesDraft?.let {
        SeriesRuleSheet(
            onDone = { title, channelKey ->
                seriesDraft = null
                if (title.isBlank() || channelKey.isBlank()) return@SeriesRuleSheet
                scope.launch {
                    withContext(Dispatchers.IO) {
                        val rule = SeriesRule(
                            id = LiveTvEngine.store.newId(),
                            channelKey = channelKey,
                            title = title,
                        )
                        LiveTvRecordings.saveSeriesRules(LiveTvRecordings.seriesRules() + rule)
                        LiveTvRecordings.applySeriesRules()
                    }
                    toast(I18n.t("Rule added"))
                    tick++
                }
            },
            onDismiss = { seriesDraft = null },
        )
    }
}

/** Picks a channel + show title for a new series rule. */
@Composable
private fun SeriesRuleSheet(onDone: (String, String) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var channels by remember { mutableStateOf<List<com.hikari.app.iptv.LiveChannel>>(emptyList()) }
    LaunchedEffect(Unit) {
        channels = withContext(Dispatchers.IO) { LiveTvEngine.visibleChannels(null).take(1500) }
    }
    val shown = remember(channels, query) {
        val q = query.trim()
        if (q.isBlank()) channels.take(60)
        else channels.filter { it.name.contains(q, ignoreCase = true) }.take(60)
    }
    var picked by remember { mutableStateOf<com.hikari.app.iptv.LiveChannel?>(null) }
    var title by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("New series rule")) },
        text = {
            Column {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    label = { Text(tr("Find channel")) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(modifier = Modifier.height(220.dp)) {
                    items(shown, key = { it.key }) { c ->
                        Text(
                            (if (c.key == picked?.key) "● " else "") + c.name + " · " + c.group,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (c.key == picked?.key) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.fillMaxWidth().clickable {
                                picked = c
                                if (title.isBlank()) title = c.name
                            }.padding(vertical = 6.dp),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = title, onValueChange = { title = it },
                    label = { Text(tr("Show title contains…")) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val c = picked
                    if (c == null || title.trim().isBlank()) return@TextButton
                    onDone(title.trim(), c.key)
                }
            ) { Text(tr("Add")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("Cancel")) }
        },
    )
}
