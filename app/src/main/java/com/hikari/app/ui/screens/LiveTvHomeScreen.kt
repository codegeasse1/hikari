package com.hikari.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.OndemandVideo
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarOutline
import androidx.compose.material.icons.filled.TheaterComedy
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.hikari.app.HikariApp
import com.hikari.app.data.Logs
import com.hikari.app.data.NetworkStream
import com.hikari.app.data.ProviderType
import com.hikari.app.providers.IptvProvider
import com.hikari.app.i18n.tr
import com.hikari.app.iptv.LiveChannel
import com.hikari.app.iptv.LiveChannelGroup
import com.hikari.app.iptv.LiveTvEngine
import com.hikari.app.iptv.LiveTvImport
import com.hikari.app.iptv.LiveTvPlayback
import com.hikari.app.ui.components.GlassCard
import com.hikari.app.ui.navigation.LocalTaskbarInset
import com.hikari.app.ui.navigation.Routes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The IPTV tab, rebuilt Nuvio-style: a "Live TV" home with the icon action
 * row (Search / Guide / Calendar / On Demand / Sports / Settings),
 * expandable group cards with channel counts + logo strips, inline channel
 * rows that play on tap, and an overflow menu (Manage groups, Update now,
 * Playlists). The old playlist/Tor/Network tiles live one tap away under
 * "Playlists" instead of being the whole tab.
 */
@Composable
fun LiveTvHomeScreen(nav: NavHostController) {
    LiveTvInit()
    val context = LocalContext.current
    val app = context.applicationContext as HikariApp
    val scope = rememberCoroutineScope()
    // Torrents & network streams live in the provider store (never in the
    // engine): home plays them straight from these rows, settings owns them.
    val allProviders by remember { app.providers.providers }.collectAsState()
    val torProviders = remember(allProviders) {
        allProviders.filterIsInstance<IptvProvider>().map { it.config }.filter {
            it.type == ProviderType.IPTV && NetworkStream.isTorrentLink(it.url)
        }
    }
    val streamProviders = remember(allProviders) {
        allProviders.filterIsInstance<IptvProvider>().map { it.config }.filter {
            it.type == ProviderType.IPTV && NetworkStream.isStream(it) && !NetworkStream.isTorrentLink(it.url)
        }
    }
    var tick by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var groups by remember { mutableStateOf<List<LiveChannelGroup>>(emptyList()) }
    var previews by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var favKeys by remember { mutableStateOf<List<String>>(emptyList()) }
    var pinnedFirst by remember { mutableStateOf<String?>(null) }
    var sourceCount by remember { mutableStateOf(0) }
    var expanded by remember { mutableStateOf<Set<String>>(emptySet()) }
    var channelsOf by remember { mutableStateOf<Map<String, List<LiveChannel>>>(emptyMap()) }
    var loadingGroup by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var updating by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<LiveChannel>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var warmed by remember { mutableStateOf(false) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    val nothingPlayable = tr("Nothing playable here yet")

    LaunchedEffect(tick) {
        loading = true
        val app = context.applicationContext as HikariApp
        val data = withContext(Dispatchers.IO) {
            runCatching {
                // Self-sufficient load: init the engine AND import the stored
                // playlists AND force-parse every enabled source that has no
                // rows yet (the Bridge pattern — see LiveTvBridge.catalogs):
                // a fresh process starts with bare in-memory channels while
                // the refresh interval still says "not stale", so without
                // this groupsOf answers empty until a playlist is opened.
                LiveTvEngine.init(context)
                LiveTvImport.importExistingProviders(app)
                LiveTvEngine.sources()
                    .filter { it.enabled && LiveTvEngine.channelsOf(it.id).isEmpty() }
                    .forEach { src -> runCatching { LiveTvEngine.refreshSource(src.id) } }
                val gs = LiveTvEngine.groupsOf(null)
                val st = LiveTvEngine.store.userState()
                val prev = if (gs.isEmpty()) emptyMap() else runCatching {
                    LiveTvEngine.visibleChannels(null)
                        .filter { !it.logo.isNullOrBlank() }
                        .groupBy { it.groupId }
                        .mapValues { (_, list) -> list.take(6).mapNotNull { it.logo } }
                }.getOrDefault(emptyMap())
                HomeData(gs, prev, st.favorites, st.groupOrder.firstOrNull(), LiveTvEngine.sources().size)
            }.getOrElse {
                Logs.logError("LiveTv", "home load failed", it)
                HomeData(emptyList(), emptyMap(), emptyList<String>(), null, 0)
            }
        }
        groups = data.groups
        previews = data.previews
        favKeys = data.favs
        pinnedFirst = data.pinned
        sourceCount = data.sources
        loading = false
        if (!warmed) {
            warmed = true
            scope.launch {
                withContext(Dispatchers.IO) { runCatching { LiveTvEngine.ensureFresh() } }
                tick++
            }
        }
    }

    LaunchedEffect(query, searchOpen) {
        if (!searchOpen) {
            results = null
            searching = false
            return@LaunchedEffect
        }
        val q = query.trim()
        if (q.length < 2) {
            results = null
            searching = false
            return@LaunchedEffect
        }
        searching = true
        kotlinx.coroutines.delay(350)
        val found = withContext(Dispatchers.IO) {
            runCatching { LiveTvEngine.search(q) }.getOrDefault(emptyList())
        }
        if (query.trim() != q) return@LaunchedEffect
        results = found
        searching = false
    }

    fun play(channel: LiveChannel) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { LiveTvPlayback.playChannel(context, channel) }.getOrDefault(false)
            }
            if (!ok) toast(nothingPlayable)
        }
    }

    fun toggleExpand(group: LiveChannelGroup) {
        if (group.id in expanded) {
            expanded = expanded - group.id
            return
        }
        expanded = expanded + group.id
        if (channelsOf.containsKey(group.id)) return
        loadingGroup = group.id
        scope.launch {
            val list = withContext(Dispatchers.IO) {
                runCatching { LiveTvEngine.channelsInGroup(group, null) }.getOrElse {
                    Logs.logError("LiveTv", "channels of ${group.title} failed", it)
                    emptyList()
                }
            }
            channelsOf = channelsOf + (group.id to list)
            loadingGroup = null
        }
    }

    fun toggleFav(channel: LiveChannel) {
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { LiveTvEngine.toggleFavorite(channel.key) }
                    .onFailure { Logs.logError("LiveTv", "favorite toggle failed", it) }
            }
            favKeys = withContext(Dispatchers.IO) {
                runCatching { LiveTvEngine.store.userState().favorites }.getOrDefault(favKeys)
            }
        }
    }

    fun togglePin(group: LiveChannelGroup) {
        scope.launch {
            val first = withContext(Dispatchers.IO) {
                val st = runCatching {
                    LiveTvEngine.store.updateUserState { cur ->
                        val o = cur.groupOrder
                        cur.copy(
                            groupOrder = if (o.firstOrNull() == group.id) o.drop(1)
                            else listOf(group.id) + (o - group.id)
                        )
                    }
                }.getOrNull()
                (st ?: LiveTvEngine.store.userState()).groupOrder.firstOrNull()
            }
            pinnedFirst = first
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp, top = 8.dp,
            bottom = LocalTaskbarInset.current + 24.dp,
        ),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    tr("Live TV"),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = tr("More"))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(tr("Manage groups and channels")) },
                        onClick = {
                            menuOpen = false
                            nav.navigate(Routes.LIVETV_MANAGE)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(if (updating) tr("Updating…") else tr("Update now")) },
                        onClick = {
                            menuOpen = false
                            if (updating) return@DropdownMenuItem
                            updating = true
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    runCatching { LiveTvEngine.ensureFresh() }
                                        .onFailure { Logs.logError("LiveTv", "update now failed", it) }
                                }
                                updating = false
                                tick++
                            }
                        },
                    )
                }
            }
        }

        item {
            LiveTvActionRow(
                onSearch = { searchOpen = !searchOpen; if (!searchOpen) query = "" },
                onGuide = { nav.navigate(Routes.LIVETV_GUIDE) },
                onCalendar = { nav.navigate(Routes.LIVETV_CALENDAR) },
                onDemand = { nav.navigate(Routes.LIVETV_ONDEMAND) },
                onSports = { nav.navigate(Routes.LIVETV_SPORTS) },
                onSettings = { nav.navigate(Routes.LIVETV_SETTINGS) },
            )
        }

        if (searchOpen) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(tr("Search channels")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
            if (searching) {
                item {
                    Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                }
            } else if (results != null) {
                val res = results.orEmpty()
                if (res.isEmpty()) {
                    item {
                        Text(
                            tr("No channels match that search."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                } else {
                    items(res, key = { "sr:" + it.key }) { ch ->
                        LiveTvHomeChannelRow(
                            channel = ch,
                            isFav = ch.key in favKeys,
                            onPlay = { play(ch) },
                            onFav = { toggleFav(ch) },
                        )
                    }
                }
            }
        }

        if (loading) {
            item {
                Box(Modifier.fillMaxWidth().padding(top = 32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            return@LazyColumn
        }

        if (groups.isEmpty()) {
            item {
                GlassCard(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Column(Modifier.padding(18.dp)) {
                        Text(
                            tr("No channels yet"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (sourceCount > 0) tr("Your playlists are still loading — pull to retry with Update now.")
                            else tr("Add an M3U playlist or an Xtream login to fill this screen with live channels."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { nav.navigate(Routes.LIVETV_SETTINGS) }) {
                                Text(tr("Add playlist"))
                            }
                        }
                    }
                }
            }
            return@LazyColumn
        }

        items(groups, key = { it.id }) { group ->
            val isOpen = group.id in expanded
            GlassCard(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Column {
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { toggleExpand(group) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                groupIcon(group),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(26.dp),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                group.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                tr("%s channel(s)").replace("%s", group.count.toString()),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            val strip = previews[group.id].orEmpty()
                            if (strip.isNotEmpty() && !isOpen) {
                                Spacer(Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    strip.take(5).forEach { logo ->
                                        AsyncImage(
                                            model = logo,
                                            contentDescription = null,
                                            contentScale = ContentScale.Fit,
                                            modifier = Modifier
                                                .size(30.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                                .padding(2.dp),
                                        )
                                    }
                                    if (group.count > strip.size) {
                                        Box(
                                            Modifier.size(30.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(MaterialTheme.colorScheme.surfaceVariant),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                "+" + (group.count - strip.size).coerceAtMost(999),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        IconButton(onClick = { togglePin(group) }) {
                            Icon(
                                if (pinnedFirst == group.id) Icons.Filled.Star else Icons.Filled.StarOutline,
                                contentDescription = tr("Pin to top"),
                                tint = if (pinnedFirst == group.id) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            if (isOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (isOpen) {
                        val list = channelsOf[group.id]
                        if (list == null || loadingGroup == group.id) {
                            Box(
                                Modifier.fillMaxWidth().padding(16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            }
                        } else if (list.isEmpty()) {
                            Text(
                                tr("Nothing here — the group is hidden or empty."),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp),
                            )
                        } else {
                            val shown = list.take(500)
                            shown.forEach { ch ->
                                LiveTvHomeChannelRow(
                                    channel = ch,
                                    isFav = ch.key in favKeys,
                                    onPlay = { play(ch) },
                                    onFav = { toggleFav(ch) },
                                )
                            }
                            if (list.size > shown.size) {
                                Text(
                                    tr("Showing %s of %s — narrow it from Search.")
                                        .replaceFirst("%s", shown.size.toString())
                                        .replaceFirst("%s", list.size.toString()),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(12.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
        if (torProviders.isNotEmpty()) {
            item {
                TorrentStreamHomeHeader(title = tr("Torrents"))
            }
            items(torProviders, key = { "tor:" + it.id }) { p ->
                TorrentStreamRow(
                    name = p.name.ifBlank { tr("Torrent") },
                    subtitle = tr("Torrent"),
                    torrent = true,
                    onOpen = { openTorrentStream(nav, p) },
                )
            }
        }
        if (streamProviders.isNotEmpty()) {
            item {
                TorrentStreamHomeHeader(title = tr("Network streams"))
            }
            items(streamProviders, key = { "stream:" + it.id }) { p ->
                TorrentStreamRow(
                    name = p.name.ifBlank { tr("Stream") },
                    subtitle = runCatching { NetworkStream.hostOf(p.url) }.getOrDefault(p.url).take(80),
                    torrent = false,
                    onOpen = { openTorrentStream(nav, p) },
                )
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

/** Small section header for home's torrents / network streams rows. */
@Composable
private fun TorrentStreamHomeHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 6.dp),
    )
}

private data class HomeData(
    val groups: List<LiveChannelGroup>,
    val previews: Map<String, List<String>>,
    val favs: List<String>,
    val pinned: String?,
    val sources: Int,
)

/** Nuvio's icon action row: Search / Guide / Calendar / On Demand / Sports /
 *  Settings — one tap each, horizontally scrollable on narrow phones. The
 *  labels resolve here (composable body), because LazyRow's content lambda
 *  is LazyListScope, not composable, and tr() may not run inside it. */
@Composable
private fun LiveTvActionRow(
    onSearch: () -> Unit,
    onGuide: () -> Unit,
    onCalendar: () -> Unit,
    onDemand: () -> Unit,
    onSports: () -> Unit,
    onSettings: () -> Unit,
) {
    val searchLabel = tr("Search")
    val guideLabel = tr("Guide")
    val calendarLabel = tr("Calendar")
    val onDemandLabel = tr("On Demand")
    val sportsLabel = tr("Sports")
    val settingsLabel = tr("Settings")
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // NOTE: this lambda is LazyListScope (NOT @Composable), so the tr()
        // labels resolve above in the composable body, not here.
        val actions = listOf(
            Triple(searchLabel, Icons.Filled.Search, onSearch),
            Triple(guideLabel, Icons.Filled.DateRange, onGuide),
            Triple(calendarLabel, Icons.Filled.CalendarMonth, onCalendar),
            Triple(onDemandLabel, Icons.Filled.OndemandVideo, onDemand),
            Triple(sportsLabel, Icons.Filled.SportsSoccer, onSports),
            Triple(settingsLabel, Icons.Filled.Settings, onSettings),
        )
        items(actions) { (label, icon, fn) ->
            Column(
                Modifier.clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = fn)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(icon, contentDescription = label, modifier = Modifier.size(24.dp))
                Spacer(Modifier.height(2.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** One channel row: logo, number, name, group — plays on tap, star for
 *  favorites. The same row serves group expansions and search results. */
@Composable
private fun LiveTvHomeChannelRow(
    channel: LiveChannel,
    isFav: Boolean,
    onPlay: () -> Unit,
    onFav: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = onPlay)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!channel.logo.isNullOrBlank()) {
            AsyncImage(
                model = channel.logo,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(3.dp),
            )
            Spacer(Modifier.width(10.dp))
        } else {
            Box(
                Modifier.size(44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Tv,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (channel.number > 0) {
                    Text(
                        channel.number.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    channel.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (channel.group.isNotBlank()) {
                Text(
                    channel.group,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onFav, modifier = Modifier.size(36.dp)) {
            Icon(
                if (isFav) Icons.Filled.Star else Icons.Filled.StarOutline,
                contentDescription = tr("Favorite"),
                tint = if (isFav) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

private fun groupIcon(group: LiveChannelGroup): androidx.compose.ui.graphics.vector.ImageVector {
    if (group.id == LiveChannelGroup.RECENT) return Icons.Filled.History
    if (group.id == LiveChannelGroup.FAVORITES) return Icons.Filled.Star
    if (group.id == LiveChannelGroup.ALL) return Icons.Filled.LiveTv
    if (group.id.startsWith(LiveChannelGroup.CUSTOM_PREFIX)) return Icons.AutoMirrored.Filled.PlaylistPlay
    val t = group.title.lowercase()
    return when {
        "movie" in t || "film" in t || "vod" in t -> Icons.Filled.Movie
        "music" in t || "radio" in t -> Icons.Filled.MusicNote
        "news" in t -> Icons.Filled.Newspaper
        "sport" in t -> Icons.Filled.SportsSoccer
        "kid" in t || "cartoon" in t || "animation" in t || "anime" in t -> Icons.Filled.TheaterComedy
        else -> Icons.Filled.Tv
    }
}
