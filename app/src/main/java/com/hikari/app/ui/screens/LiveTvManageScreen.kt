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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.hikari.app.i18n.tr
import com.hikari.app.iptv.LiveChannel
import com.hikari.app.iptv.LiveChannelGroup
import com.hikari.app.iptv.LiveTvEngine
import com.hikari.app.iptv.LiveTvUserStateAccess
import com.hikari.app.tv.tvTextFieldKeys
import com.hikari.app.ui.components.GlassCard
import com.hikari.app.ui.components.SettingsPageHeader
import com.hikari.app.ui.navigation.LocalTaskbarInset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manages channels and groups: hide/show, reorder, rename, channel numbers,
 * custom groups, EPG reassignment and group locks.
 */
@Composable
fun LiveTvManageScreen(nav: NavHostController) {
    LiveTvInit()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableStateOf(0) }
    var groups by remember { mutableStateOf<List<LiveChannelGroup>>(emptyList()) }
    var groupId by remember { mutableStateOf<String?>(null) }
    var channels by remember { mutableStateOf<List<LiveChannel>>(emptyList()) }
    var hiddenChannels by remember { mutableStateOf<Set<String>>(emptySet()) }
    var hiddenGroups by remember { mutableStateOf<Set<String>>(emptySet()) }
    var lockedGroups by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pinSet by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<LiveChannel?>(null) }
    var numberTarget by remember { mutableStateOf<LiveChannel?>(null) }
    var epgTarget by remember { mutableStateOf<LiveChannel?>(null) }
    var showCustom by remember { mutableStateOf(false) }
    var showGroups by remember { mutableStateOf(false) }
    var warmed by remember { mutableStateOf(false) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    suspend fun reload() {
        val data = withContext(Dispatchers.IO) {
            runCatching { LiveTvEngine.init(context) }
            val g = LiveTvEngine.groupsOf(null).filter { it.id != LiveChannelGroup.FAVORITES && it.id != LiveChannelGroup.RECENT }
            val gid = groupId?.takeIf { id -> g.any { it.id == id } } ?: g.firstOrNull()?.id
            val st = LiveTvEngine.store.userState()
            ManageData(g, gid, st.hiddenChannels, st.hiddenGroups, st.lockedGroups, st.parentalPin.length >= 4)
        }.getOrDefault(ManageData(emptyList(), null, emptySet(), emptySet(), emptySet(), false))
        groups = data.groups
        if (groupId == null) groupId = data.groupId
        hiddenChannels = data.hiddenChannels
        hiddenGroups = data.hiddenGroups
        lockedGroups = data.lockedGroups
        pinSet = data.pinSet
        val gid = groupId
        channels = if (gid == null) emptyList() else withContext(Dispatchers.IO) {
            val g = LiveTvEngine.groupsOf(null).firstOrNull { it.id == gid } ?: return@withContext emptyList()
            LiveTvEngine.channelsInGroup(g, null)
        }
    }

    LaunchedEffect(tick) {
        loading = true
        reload()
        loading = false
        // Cold start paints the cache, then refreshes stale sources once in
        // the background and repaints (see the guide screen's note).
        if (!warmed) {
            warmed = true
            scope.launch {
                withContext(Dispatchers.IO) { runCatching { LiveTvEngine.ensureFresh() } }
                tick++
            }
        }
    }

    val shown = remember(channels, query, hiddenChannels) {
        val q = query.trim()
        val list = if (q.isBlank()) channels
        else channels.filter { it.name.contains(q, ignoreCase = true) }
        list.sortedBy { it.key in hiddenChannels }
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
                title = tr("Manage channels"),
                subtitle = tr("Hide, reorder, rename and regroup"),
                onBack = { nav.popBackStack() },
            )
        }
        if (loading) {
            item { LiveTvLoading(tr("Reading channels…")) }
            return@LazyColumn
        }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                TextButton(onClick = { showGroups = true }) { Text(tr("Groups")) }
                TextButton(onClick = { showCustom = true }) { Text(tr("Custom groups")) }
            }
        }
        item {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                label = { Text(tr("Filter channels")) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp).tvTextFieldKeys(query),
            )
        }
        item {
            androidx.compose.foundation.lazy.LazyRow(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                items(groups, key = { it.id }) { g ->
                    val on = g.id == groupId
                    Text(
                        (if (g.id in hiddenGroups) "⊘ " else "") + g.title + " (" + g.count + ")",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                        color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .clickable {
                                groupId = g.id
                                scope.launch {
                                    channels = withContext(Dispatchers.IO) {
                                        LiveTvEngine.channelsInGroup(g, null)
                                    }
                                }
                            }
                            .padding(horizontal = 4.dp, vertical = 6.dp),
                    )
                }
            }
        }
        items(shown, key = { it.key }) { channel ->
            ManageChannelRow(
                channel = channel,
                hidden = channel.key in hiddenChannels,
                onHide = { hide ->
                    scope.launch {
                        withContext(Dispatchers.IO) { LiveTvEngine.setHiddenChannel(channel.key, hide) }
                        tick++
                    }
                },
                onMove = { dir ->
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            LiveTvUserStateAccess.moveChannel(groupId.orEmpty(), channel.key, dir)
                        }
                        tick++
                    }
                },
                onRename = { renameTarget = channel },
                onNumber = { numberTarget = channel },
                onEpg = { epgTarget = channel },
                onFav = {
                    scope.launch {
                        withContext(Dispatchers.IO) { LiveTvEngine.toggleFavorite(channel.key) }
                        toast(tr("Favorite updated"))
                    }
                },
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    renameTarget?.let { channel ->
        var draft by remember(channel.key) {
            mutableStateOf(LiveTvEngine.store.userState().channelNames[channel.key] ?: channel.name)
        }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(tr("Rename channel")) },
            text = {
                OutlinedTextField(
                    value = draft, onValueChange = { draft = it }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().tvTextFieldKeys(draft),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        renameTarget = null
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                LiveTvEngine.store.updateUserState { st ->
                                    st.copy(channelNames = st.channelNames + (channel.key to draft.trim()))
                                }
                            }
                            tick++
                        }
                    }
                ) { Text(tr("Save")) }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text(tr("Cancel")) }
            },
        )
    }

    numberTarget?.let { channel ->
        var draft by remember(channel.key) {
            mutableStateOf(
                (LiveTvEngine.store.userState().channelNumbers[channel.key] ?: channel.number)
                    .takeIf { it > 0 }?.toString().orEmpty()
            )
        }
        AlertDialog(
            onDismissRequest = { numberTarget = null },
            title = { Text(tr("Channel number")) },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.filter { c -> c.isDigit() }.take(6) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().tvTextFieldKeys(draft),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        numberTarget = null
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                val n = draft.toIntOrNull()
                                LiveTvEngine.store.updateUserState { st ->
                                    st.copy(
                                        channelNumbers = if (n == null || n <= 0) st.channelNumbers - channel.key
                                        else st.channelNumbers + (channel.key to n)
                                    )
                                }
                            }
                            tick++
                        }
                    }
                ) { Text(tr("Save")) }
            },
            dismissButton = {
                TextButton(onClick = { numberTarget = null }) { Text(tr("Cancel")) }
            },
        )
    }

    epgTarget?.let { channel ->
        EpgAssignDialog(channel = channel, onDone = { epgTarget = null; tick++ }, onDismiss = { epgTarget = null })
    }

    if (showGroups) {
        GroupManagerSheet(
            groups = groups,
            hiddenGroups = hiddenGroups,
            lockedGroups = lockedGroups,
            pinSet = pinSet,
            onChanged = { tick++ },
            onDismiss = { showGroups = false },
        )
    }

    if (showCustom) {
        CustomGroupSheet(onChanged = { tick++ }, onDismiss = { showCustom = false })
    }
}

private data class ManageData(
    val groups: List<LiveChannelGroup>,
    val groupId: String?,
    val hiddenChannels: Set<String>,
    val hiddenGroups: Set<String>,
    val lockedGroups: Set<String>,
    val pinSet: Boolean,
)

@Composable
private fun ManageChannelRow(
    channel: LiveChannel,
    hidden: Boolean,
    onHide: (Boolean) -> Unit,
    onMove: (Int) -> Unit,
    onRename: () -> Unit,
    onNumber: () -> Unit,
    onEpg: () -> Unit,
    onFav: () -> Unit,
) {
    GlassCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        (if (hidden) "⊘ " else "") + channel.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (hidden) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        channel.group + (if (channel.number > 0) " · #" + channel.number else "") +
                            (if (!channel.tvgId.isNullOrBlank()) " · " + channel.tvgId else ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { onMove(-1) }, modifier = androidx.compose.ui.Modifier.size(32.dp)) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null)
                }
                IconButton(onClick = { onMove(1) }, modifier = androidx.compose.ui.Modifier.size(32.dp)) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null)
                }
                IconButton(onClick = { onHide(!hidden) }, modifier = androidx.compose.ui.Modifier.size(32.dp)) {
                    Icon(
                        if (hidden) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = null,
                        tint = if (hidden) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row {
                TextButton(onClick = onRename) { Text(tr("Rename")) }
                TextButton(onClick = onNumber) { Text(tr("Number")) }
                TextButton(onClick = onEpg) { Text(tr("EPG")) }
                TextButton(onClick = onFav) { Text("★") }
            }
        }
    }
}

/** Reassigns the XMLTV channel a channel's guide comes from. */
@Composable
fun EpgAssignDialog(channel: LiveChannel, onDone: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var candidates by remember { mutableStateOf<List<com.hikari.app.iptv.XmltvChannel>>(emptyList()) }
    var searching by remember { mutableStateOf(true) }
    var current by remember { mutableStateOf(LiveTvEngine.epgIdOf(channel)) }

    LaunchedEffect(channel.key) {
        candidates = withContext(Dispatchers.IO) {
            LiveTvEngine.epg.channelsOf(channel.sourceId)
        }
        searching = false
    }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    val shown = remember(candidates, query) {
        val q = query.trim().lowercase()
        val list = if (q.isBlank()) candidates.take(200)
        else candidates.filter { c ->
            c.id.lowercase().contains(q) || c.displayNames.any { it.lowercase().contains(q) }
        }.take(200)
        list.sortedBy { it.displayNames.firstOrNull()?.lowercase() ?: it.id.lowercase() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Guide for %s").replace("%s", channel.name)) },
        text = {
            Column {
                Text(
                    tr("Now: %s").replace("%s", current ?: tr("auto (tvg-id)")),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    label = { Text(tr("Search guide channels")) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().tvTextFieldKeys(query),
                )
                Spacer(Modifier.height(4.dp))
                if (searching) {
                    Text(tr("Reading…"), style = MaterialTheme.typography.bodySmall)
                } else {
                    LazyColumn(modifier = Modifier.height(280.dp)) {
                        item {
                            Text(
                                tr("Auto (tvg-id)"),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            LiveTvEngine.store.updateUserState { st ->
                                                st.copy(epgOverrides = st.epgOverrides - channel.key)
                                            }
                                        }
                                        onDone()
                                    }
                                }.padding(vertical = 8.dp),
                            )
                        }
                        items(shown, key = { it.id }) { c ->
                            Column(
                                Modifier.fillMaxWidth().clickable {
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            LiveTvEngine.store.updateUserState { st ->
                                                st.copy(epgOverrides = st.epgOverrides + (channel.key to c.id))
                                            }
                                        }
                                        toast(tr("Guide assigned"))
                                        onDone()
                                    }
                                }.padding(vertical = 8.dp),
                            ) {
                                Text(
                                    c.displayNames.firstOrNull() ?: c.id,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (c.id == current) FontWeight.Bold else FontWeight.Normal,
                                )
                                Text(
                                    c.id,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
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

/** Group order, renames, hide/show and locks. */
@Composable
private fun GroupManagerSheet(
    groups: List<LiveChannelGroup>,
    hiddenGroups: Set<String>,
    lockedGroups: Set<String>,
    pinSet: Boolean,
    onChanged: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var renameTarget by remember { mutableStateOf<LiveChannelGroup?>(null) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Groups")) },
        text = {
            LazyColumn(modifier = Modifier.height(420.dp)) {
                items(groups, key = { it.id }) { g ->
                    if (g.special) return@items
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    (if (g.id in hiddenGroups) "⊘ " else "") +
                                        (LiveTvEngine.store.userState().groupNames[g.id] ?: g.title) +
                                        " (" + g.count + ")",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            LiveTvUserStateAccess.moveGroup(g.id, -1)
                                        }
                                        onChanged()
                                    }
                                },
                                modifier = Modifier.size(32.dp),
                            ) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null) }
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            LiveTvUserStateAccess.moveGroup(g.id, 1)
                                        }
                                        onChanged()
                                    }
                                },
                                modifier = Modifier.size(32.dp),
                            ) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null) }
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            LiveTvEngine.setHiddenGroup(g.id, g.id !in hiddenGroups)
                                        }
                                        onChanged()
                                    }
                                },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    if (g.id in hiddenGroups) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = null,
                                )
                            }
                        }
                        Row {
                            TextButton(onClick = { renameTarget = g }) { Text(tr("Rename")) }
                            if (pinSet) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(tr("Lock"), style = MaterialTheme.typography.bodySmall)
                                    Switch(
                                        checked = g.id in lockedGroups,
                                        onCheckedChange = { on ->
                                            scope.launch {
                                                withContext(Dispatchers.IO) {
                                                    LiveTvEngine.store.updateUserState { st ->
                                                        st.copy(
                                                            lockedGroups = if (on) st.lockedGroups + g.id
                                                            else st.lockedGroups - g.id
                                                        )
                                                    }
                                                }
                                                onChanged()
                                            }
                                        },
                                    )
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

    renameTarget?.let { g ->
        var draft by remember(g.id) {
            mutableStateOf(LiveTvEngine.store.userState().groupNames[g.id] ?: g.title)
        }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(tr("Rename group")) },
            text = {
                OutlinedTextField(
                    value = draft, onValueChange = { draft = it }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().tvTextFieldKeys(draft),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        renameTarget = null
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                LiveTvEngine.store.updateUserState { st ->
                                    st.copy(groupNames = st.groupNames + (g.id to draft.trim()))
                                }
                            }
                            onChanged()
                        }
                    }
                ) { Text(tr("Save")) },
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text(tr("Cancel")) }
            },
        )
    }
}

/** Custom groups: build a group from favorite channels. */
@Composable
private fun CustomGroupSheet(onChanged: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var groups by remember { mutableStateOf(LiveTvEngine.store.userState().customGroups) }
    var name by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Custom groups")) },
        text = {
            Column {
                LazyColumn(modifier = Modifier.height(200.dp)) {
                    items(groups, key = { it.id }) { g ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(g.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    tr("%s channels").replace("%s", g.channelKeys.size.toString()),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            LiveTvEngine.store.updateUserState { st ->
                                                st.copy(customGroups = st.customGroups.filterNot { it.id == g.id })
                                            }
                                        }
                                        groups = LiveTvEngine.store.userState().customGroups
                                        onChanged()
                                    }
                                }
                            ) { Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text(tr("New group name")) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().tvTextFieldKeys(name),
                )
                Spacer(Modifier.height(4.dp))
                if (!picking) {
                    TextButton(
                        onClick = {
                            if (name.trim().isBlank()) {
                                toast(tr("Name the group first."))
                                return@TextButton
                            }
                            picking = true
                        }
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(tr("Pick channels"))
                    }
                } else {
                    ChannelPickList(
                        onDone = { keys ->
                            picking = false
                            if (keys.isEmpty()) return@ChannelPickList
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    val g = com.hikari.app.iptv.LiveCustomGroup(
                                        id = LiveTvEngine.store.newId(),
                                        name = name.trim(),
                                        channelKeys = keys,
                                    )
                                    LiveTvEngine.store.updateUserState { st ->
                                        st.copy(customGroups = st.customGroups + g)
                                    }
                                }
                                name = ""
                                groups = LiveTvEngine.store.userState().customGroups
                                onChanged()
                            }
                        },
                        onCancel = { picking = false },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("Close")) }
        },
    )
}

/** A simple channel multi-picker used by custom groups. */
@Composable
private fun ChannelPickList(onDone: (List<String>) -> Unit, onCancel: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var all by remember { mutableStateOf<List<LiveChannel>>(emptyList()) }
    var picked by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(Unit) {
        all = withContext(Dispatchers.IO) { LiveTvEngine.visibleChannels(null).take(2000) }
    }
    val shown = remember(all, query) {
        val q = query.trim()
        if (q.isBlank()) all.take(100)
        else all.filter { it.name.contains(q, ignoreCase = true) }.take(100)
    }
    Column {
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            label = { Text(tr("Search")) }, singleLine = true,
            modifier = Modifier.fillMaxWidth().tvTextFieldKeys(query),
        )
        LazyColumn(modifier = Modifier.height(240.dp)) {
            items(shown, key = { it.key }) { c ->
                Row(
                    Modifier.fillMaxWidth().clickable {
                        picked = if (c.key in picked) picked - c.key else picked + c.key
                    }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.Checkbox(
                        checked = c.key in picked,
                        onCheckedChange = null,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(c.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
            }
        }
        Row {
            TextButton(onClick = { onDone(picked.toList()) }) {
                Text(tr("Add %s").replace("%s", picked.size.toString()))
            }
            TextButton(onClick = onCancel) { Text(tr("Cancel")) }
        }
    }
}
