package com.hikari.app.ui.screens

import com.hikari.app.ui.components.LocalHideHelp

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.hikari.app.HikariApp
import com.hikari.app.data.IptvPlaylist
import com.hikari.app.data.MediaItem
import com.hikari.app.data.MediaType
import com.hikari.app.data.NetworkStream
import com.hikari.app.data.Profiles
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.ProviderType
import com.hikari.app.data.TileShapes
import com.hikari.app.i18n.I18n
import com.hikari.app.i18n.tr
import com.hikari.app.providers.IptvProvider
import com.hikari.app.ui.IptvArt
import com.hikari.app.ui.navigation.LocalTaskbarInset
import com.hikari.app.ui.navigation.Routes
import com.hikari.app.ui.theme.rememberGlassTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import com.hikari.app.tv.tvTextFieldKeys

/**
 * The IPTV tab: the user's playlists as tiles, and the channels inside them.
 *
 * The app already plays IPTV — a playlist is a provider, so its channels appear
 * in Home's source picker, in the global search and in the player's server list.
 * What it had no home for was *browsing* a playlist: the only place a playlist
 * was listed at all was a row in Extensions, and its groups were buried in
 * Home's catalog picker. This is the IPTV-shaped surface the user asked for
 * ("add in taskbar IPTV button … all added iptv link it shows here as Poster box
 * like in personal catalog creator, and clicking it open that iptv link
 * catalog").
 *
 * The hierarchy is the playlist's own:
 *   IPTV tab → one tile per playlist → one tile per group ("Sports", "India",
 *   "Movies 24/7") → the group's channels, as a paged poster grid
 *   ([CatalogScreen], which every IPTV group already answers through the
 *   provider contract).
 *
 * Nothing here reaches TMDB: a live channel has no entry there, and the artwork
 * lookup knows it (see [com.hikari.app.data.IptvMark]). A playlist's tile wears
 * the first real channel logo it has, and every channel without a logo gets a
 * tile drawn from its own name ([IptvArt]).
 *
 * The tab's button is OFF by default — see Settings → Taskbar buttons, where it
 * can be switched on like any other tab (see [com.hikari.app.data.AppStore.iptvTabFlow]).
 */
@Composable
fun IptvScreen(nav: NavHostController) {
    val app = LocalContext.current.applicationContext as HikariApp
    val all by app.providers.providers.collectAsState()
    val playlists = remember(all) { all.filterIsInstance<IptvProvider>() }
    val shapeFlow = remember { app.store.iptvShapeFlow() }
    val shape by shapeFlow.collectAsState(initial = TileShapes.POSTER)
    val scope = rememberCoroutineScope()
    val uiContext = LocalContext.current

    // "+" opens the add-playlist dialog right here, with the link field already
    // in front of the user — the tab used to bounce to Extensions and leave them
    // to find "Add IPTV playlist" themselves ("make clicking it directly open
    // m3u8 entering link so user can add directly from there too").
    var showAdd by remember { mutableStateOf(false) }
    var addLink by remember { mutableStateOf("") }
    var addName by remember { mutableStateOf("") }
    // Whether the dialog is adding a PLAYLIST or a single NETWORK STREAM. Both
    // are IPTV providers and both end up as a tile in this tab; the difference
    // is what the link IS (a list of channels, or one file on some host that has
    // to be resolved before it plays) — see [NetworkStream].
    var addStream by remember { mutableStateOf(false) }
    var addTorrent by remember { mutableStateOf(false) }
    var addFileLabel by remember { mutableStateOf("") }
    var addFilePath by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val picked = copyPlaylistInto(uiContext, uri)
                if (picked == null) {
                    Toast.makeText(uiContext, I18n.t("Could not read that file"), Toast.LENGTH_LONG).show()
                } else {
                    addFileLabel = picked.first
                    addFilePath = picked.second
                }
            }
        }
    }

    fun addNow(playAfter: Boolean = false) {
        val link = addLink.trim()
        val local = addFilePath.takeIf { it.isNotBlank() }
        val name = addName
        val wasStream = addStream
        val wantTorrent = addTorrent
        adding = true
        scope.launch {
            val result = if (wasStream) {
                addNetworkStream(app, link, name)
            } else {
                addIptvPlaylist(app, link, local, name)
            }
            adding = false
            result.fold(
                onSuccess = { n ->
                    val msg = when {
                        wantTorrent -> I18n.t("Added torrent stream")
                        wasStream -> I18n.t("Added network stream")
                        else -> I18n.t("Added IPTV playlist (%s channels)").replace("%s", n.toString())
                    }
                    Toast.makeText(uiContext, msg, Toast.LENGTH_LONG).show()
                    showAdd = false
                    addStream = false
                    addTorrent = false
                    addLink = ""
                    addName = ""
                    addFileLabel = ""
                    addFilePath = ""
                    if (playAfter && wasStream) {
                        val saved = app.store.providers().firstOrNull { p ->
                            p.type == com.hikari.app.data.ProviderType.IPTV &&
                                (p.url == link || p.extra == link)
                        }
                        if (saved != null) {
                            // Play means the detail page now, not the folder list: a
                            // network stream is one file and opens straight on Play.
                            if (NetworkStream.isTorrentLink(saved.url)) {
                                Routes.safeNavigate(
                                    nav,
                                    Routes.catalog(
                                        providerId = saved.id,
                                        catalogId = IptvProvider.CATALOG_ALL,
                                        title = saved.name,
                                        providerName = saved.name,
                                        type = MediaType.MOVIE,
                                        rawType = "torrent",
                                    ),
                                )
                            } else {
                                Routes.safeNavigate(
                                    nav,
                                    Routes.detail(
                                        providerId = saved.id,
                                        type = MediaType.MOVIE,
                                        mediaId = saved.url,
                                        title = saved.name,
                                        posterUrl = null,
                                        rawType = "stream",
                                    ),
                                )
                            }
                        }
                    }
                },
                onFailure = { t ->
                    Toast.makeText(
                        uiContext,
                        t.message ?: I18n.t("Could not read that playlist"),
                        Toast.LENGTH_LONG,
                    ).show()
                },
            )
        }
    }

    // Removes one playlist from the tab. The provider is dropped first, so the
    // tile goes away immediately (the grid is keyed on the provider list), and
    // then the app's own copy of the file is deleted — but only when it really
    // is the app's copy (a playlist picked from storage, under filesDir/iptv/)
    // and nothing else still lists it: a linked playlist has nothing local, and
    // a file another profile (or another provider) still uses must stay (the
    // same rule the Extensions screen's remove follows).
    fun removePlaylist(card: IptvCard) {
        scope.launch {
            val target = app.store.providers().firstOrNull { it.id == card.id }
            app.store.removeProvider(card.id)
            app.providers.refresh()
            // Forget the cached read: this playlist's channel count and any
            // error belong to a row that no longer exists. Adding the same link
            // again reuses its id (see addIptvPlaylist), and must not inherit a
            // stale count or a stale "didn't load" badge.
            IptvProvider.forget(card.id)
            val path = target?.url.orEmpty()
            val base = app.filesDir.absolutePath + "/iptv/"
            if (path.startsWith(base) &&
                app.store.providers().none { it.url == path } &&
                !Profiles.otherProfilesReference(app, path)
            ) {
                withContext(Dispatchers.IO) { runCatching { File(path).delete() } }
            }
        }
    }

    var cards by remember { mutableStateOf<List<IptvCard>>(emptyList()) }
    var reading by remember { mutableStateOf(false) }
    // The playlist whose tile was tapped on its trash button. Removal is
    // confirmed in a dialog first: it takes the playlist's channels out of
    // Home, search and the player's server list, and deletes the app's own
    // copy when the playlist was imported from storage.
    var removeTarget by remember { mutableStateOf<IptvCard?>(null) }
    // One re-read of every playlist, keyed on which playlists exist: adding or
    // removing one is what has to re-run this, not a recomposition.
    val ids = remember(playlists) { playlists.map { it.config.id } }
    LaunchedEffect(ids) {
        if (playlists.isEmpty()) {
            cards = emptyList()
            return@LaunchedEffect
        }
        reading = true
        val out = ArrayList<IptvCard>(playlists.size)
        // Three at a time: a playlist is a whole M3U download, and a user with a
        // dozen of them should not fire a dozen at once.
        playlists.chunked(3).forEach { chunk ->
            out += withContext(Dispatchers.IO) { chunk.map { readCard(it) } }
            cards = out.toList()
        }
        reading = false
    }

    Column(Modifier.fillMaxSize()) {
        IptvHeader(
            title = tr("IPTV"),
            subtitle = if (playlists.isEmpty()) tr("No playlists yet")
            else I18n.t("%s playlists · %s channels")
                .replace("%s", playlists.size.toString())
                .replace("%s", cards.sumOf { it.channels }.toString()),
            onBack = null,
            shape = shape,
            onCycleShape = { scope.launch { app.store.setIptvShape(nextShape(shape)) } },
            onSearch = null,
            onAdd = { showAdd = true },
        )
        when {
            playlists.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.LiveTv,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(40.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        tr("No IPTV playlist yet"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        tr(
                            "Paste an M3U/M3U8 link (or pick a playlist file) and it " +
                                "appears here as a tile."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 6.dp),
                    )
                    Surface(
                        onClick = { showAdd = true },
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(tr("Add playlist"))
                        }
                    }
                }
            }

            cards.isEmpty() && reading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = tileMinFor(shape)),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    top = 4.dp,
                    bottom = LocalTaskbarInset.current + 24.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(cards, key = { it.id }) { card ->
                    IptvTile(
                        cover = card.cover,
                        name = card.name,
                        subtitle = when {
                            card.error != null -> card.error
                            card.torrent -> tr("Torrent")
                            card.stream -> tr("Network stream")
                            card.channels == 0 -> tr("Empty playlist")
                            else -> I18n.t("%s channels · %s groups")
                                .replace("%s", card.channels.toString())
                                .replace("%s", card.groups.toString())
                        },
                        shape = shape,
                        badge = if (card.error != null) tr("Didn't load") else null,
                        onRemove = { removeTarget = card },
                    ) {
                        if (card.torrent) {
                            Routes.safeNavigate(
                                nav,
                                Routes.catalog(
                                    providerId = card.id,
                                    catalogId = IptvProvider.CATALOG_ALL,
                                    title = card.name,
                                    providerName = card.name,
                                    type = MediaType.MOVIE,
                                    rawType = "torrent",
                                ),
                            )
                        } else if (card.stream && !card.torrent) {
                            // A network stream is one file, not a channel list: it opens
                            // straight on its detail page (Play), with no All-channels /
                            // Ungrouped folders in between — and plays as VOD, not live.
                            Routes.safeNavigate(
                                nav,
                                Routes.detail(
                                    providerId = card.id,
                                    type = MediaType.MOVIE,
                                    mediaId = card.url,
                                    title = card.name,
                                    posterUrl = card.cover,
                                    rawType = "stream",
                                ),
                            )
                        } else {
                            Routes.safeNavigate(nav, Routes.iptvPlaylist(card.id))
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { if (!adding) showAdd = false },
            title = { Text(if (addStream) tr("Add network stream") else tr("Add IPTV playlist")) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    // Playlist or single link. Kept in front of the fields
                    // because it changes what they mean: a playlist link is
                    // read and its channels listed, a stream link is kept as it
                    // is and worked out when it is played.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AddModePill(
                            label = tr("Playlist"),
                            selected = !addStream,
                            onClick = { addStream = false; addTorrent = false },
                        )
                        Spacer(Modifier.width(8.dp))
                        AddModePill(
                            label = tr("Network stream"),
                            selected = addStream && !addTorrent,
                            onClick = { addStream = true; addTorrent = false },
                        )
                        Spacer(Modifier.width(8.dp))
                        AddModePill(
                            label = tr("Torrent"),
                            selected = addTorrent,
                            onClick = { addStream = true; addTorrent = true },
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    if (!LocalHideHelp.current) {
                    Text(
                        if (addTorrent) {
                            tr(
                                "Paste a magnet link or .torrent URL. Add saves it for later; " +
                                    "Play starts it now through the torrent engine."
                            )
                        } else if (addStream) {
                            tr(
                                "Paste a link to a stream: an m3u8 or mp4, a Terabox/Telebox " +
                                    "share, an MDisk link, or a download page — anything an " +
                                    "installed extension can open. It is resolved when you " +
                                    "press play."
                            )
                        } else {
                            tr(
                                "Paste an M3U playlist link — an Xtream panel's " +
                                    "get.php?username=…&password=…&type=m3u_plus link works. " +
                                    "Or pick a playlist file from storage. A single video " +
                                    "link (m3u8, mp4, and friends) is not a playlist — add it with " +
                                    "Network stream instead so it plays directly."
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = addLink,
                        onValueChange = { addLink = it },
                        placeholder = {
                            Text(
                                if (addStream) tr("https://…/m3u8, terabox, mdisk…")
                                else tr("https://…/playlist.m3u")
                            )
                        },
                        singleLine = false,
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth().tvTextFieldKeys(addLink),
                    )
                    Spacer(Modifier.height(8.dp))
                    if (!addStream) Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { filePicker.launch(arrayOf("*/*")) }) {
                            Icon(
                                Icons.Filled.FolderOpen,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(tr("Pick a file"))
                        }
                        if (addFileLabel.isNotBlank()) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                addFileLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = addName,
                        onValueChange = { addName = it },
                        label = {
                            Text(if (addStream) tr("Name") else tr("Name (optional)"))
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().tvTextFieldKeys(addName),
                    )
                    if (adding) {
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (addStream) tr("Adding…") else tr("Reading playlist…"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Row {
                    if (addStream && addLink.isNotBlank()) {
                        TextButton(
                            enabled = !adding,
                            onClick = {
                                // Play now: save then navigate into the stream card
                                addNow(playAfter = true)
                            },
                        ) { Text(tr("Play")) }
                        Spacer(Modifier.width(8.dp))
                    }
                    Button(
                        enabled = !adding && (addLink.isNotBlank() || addFilePath.isNotBlank()),
                        onClick = { addNow(playAfter = false) },
                    ) { Text(tr("Add")) }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAdd = false
                    addStream = false; addTorrent = false
                    addLink = ""
                    addName = ""
                    addFileLabel = ""
                    addFilePath = ""
                }) { Text(tr("Cancel")) }
            },
        )
    }

    // Removing a playlist is destructive in two ways (its channels leave Home
    // and search, and a stored copy is deleted), so it is confirmed here rather
    // than done on the tap.
    removeTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text(I18n.t("Remove %s?").replace("%s", target.name)) },
            text = {
                Column {
                    Text(
                        tr(
                            "Its channels disappear from Home, search and the player's " +
                                "server list."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (target.local) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            tr("The playlist file stored in the app is deleted too."),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    removePlaylist(target)
                    removeTarget = null
                }) {
                    Text(tr("Remove"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { removeTarget = null }) { Text(tr("Cancel")) }
            },
        )
    }
}

/**
 * Copies a picked playlist file into the app's own storage, because a SAF Uri
 * is not readable after a restart and a playlist the user chose should keep
 * working. Returns (display name, stored path), or null when the file could not
 * be read — the same rule the Extensions screen's picker follows, kept here so
 * the IPTV tab can add a playlist without leaving the tab.
 */
private suspend fun copyPlaylistInto(
    context: android.content.Context,
    uri: Uri,
): Pair<String, String>? = withContext(Dispatchers.IO) {
    runCatching {
        val raw = runCatching {
            context.contentResolver
                .query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')
        val name = raw?.trim().orEmpty().ifBlank { "playlist.m3u" }
        val safe = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").takeLast(80)
        val dir = File(context.filesDir, "iptv").apply { mkdirs() }
        val file = File(dir, safe)
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { out -> input.copyTo(out) }
        } ?: throw Exception("Could not read that file")
        if (file.length() == 0L) throw Exception("That file is empty")
        name to file.absolutePath
    }.getOrNull()
}

/**
 * Adds (or updates) an IPTV playlist from the tab's own + button. [link] is the
 * pasted M3U/M3U8/Xtream URL and [localPath] the stored copy of a picked file —
 * one of the two. The playlist is READ before it is saved, so a dead link or a
 * file with no channels is reported instead of becoming an empty tile, and the
 * id is derived from the source so adding the same link twice updates the one
 * entry (exactly what the Extensions screen does, see its addIptvPlaylist).
 */
private suspend fun addIptvPlaylist(
    app: HikariApp,
    link: String,
    localPath: String?,
    name: String,
): Result<Int> = withContext(Dispatchers.IO) {
    val url = when {
        !localPath.isNullOrBlank() -> localPath
        // A bare info hash is a torrent, not a host: keep it verbatim so the
        // torrent guard below sees it instead of an "https://" lookalike.
        NetworkStream.isBareInfoHash(link.trim()) -> link.trim()
        else -> link.trim().let {
            if (it.startsWith("http://") || it.startsWith("https://")) it
            else if (it.isBlank()) "" else "https://$it"
        }
    }
    if (url.isBlank()) {
        return@withContext Result.failure(
            Exception(I18n.t("Paste an M3U/M3U8 link, or pick a playlist file")),
        )
    }
    // A torrent is not a playlist: saving one here lists it as an IPTV folder
    // that plays as live TV. Tor mode is the path that engines it.
    if (localPath.isNullOrBlank() && NetworkStream.isTorrentLink(url)) {
        return@withContext Result.failure(
            Exception(I18n.t("That looks like a torrent, not a playlist — add it with Tor instead")),
        )
    }
    // A single video/stream address is not a playlist: downloading it here would fetch
    // video bytes as playlist text, and saving it would list it as a one-channel
    // IPTV folder (All channels into Ungrouped) that plays as live TV. Network stream
    // mode is the path that resolves such a link at play time and plays it as a file.
    if (localPath.isNullOrBlank() && NetworkStream.isDirectMediaLink(url)) {
        return@withContext Result.failure(
            Exception(I18n.t("That looks like a single video link, not a playlist — add it with Network stream instead")),
        )
    }
    val count = IptvProvider.preview(url).getOrElse {
        return@withContext Result.failure(
            Exception(it.message ?: I18n.t("Could not read that playlist")),
        )
    }
    val display = name.trim().ifBlank {
        if (url.startsWith("http")) {
            url.substringAfter("://").substringBefore('/').ifBlank { "IPTV" }
        } else {
            File(url).name.substringBeforeLast('.').ifBlank { "IPTV" }
        }
    }
    app.store.addProvider(
        ProviderConfig(
            id = "iptv|" + url.hashCode(),
            name = display,
            type = ProviderType.IPTV,
            url = url,
        )
    )
    app.providers.refresh()
    Result.success(count)
}

/**
 * Adds a NETWORK STREAM: one pasted link, kept exactly as the user wrote it and
 * resolved when it is played (see [NetworkStream]).
 *
 * Deliberately NOT read before it is saved, unlike [addIptvPlaylist]. There is
 * nothing to count — a Terabox share or an m3u8 is not a channel list — and
 * fetching it here would both make adding slow and fail honest links that the
 * app can only work out at play time (the host needs the player's own headers,
 * or asks for a session the resolver sets up then). The entry is saved with
 * [NetworkStream.MARKER] on its config, which is what makes the tab, the tile
 * and the play path treat it as a stream rather than a one-channel playlist.
 */
private suspend fun addNetworkStream(
    app: HikariApp,
    link: String,
    name: String,
): Result<Int> = withContext(Dispatchers.IO) {
    val trimmed = link.trim()
    // A magnet link is complete as-is: prefixing "https://" (as before) mangles
    // it into an unplayable https URL that then lists and plays like an IPTV
    // channel instead of going to the torrent engine. A bare info hash is a
    // magnet with no scheme yet (see [NetworkStream.magnetize]).
    val url = if (trimmed.startsWith("magnet:", ignoreCase = true)) {
        trimmed
    } else if (NetworkStream.isBareInfoHash(trimmed)) {
        NetworkStream.magnetize(trimmed)
    } else if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
        trimmed
    } else if (trimmed.isBlank()) {
        ""
    } else {
        "https://$trimmed"
    }
    if (url.isBlank()) {
        return@withContext Result.failure(Exception(I18n.t("Paste a link to the stream")))
    }
    if (!NetworkStream.isStreamLink(url)) {
        return@withContext Result.failure(
            Exception(I18n.t("That is not a link — paste a full http(s) URL or magnet link")),
        )
    }
    // A stream's name cannot come from the link (a share key is not a name), so
    // the host stands in when the user did not type one.
    val display = name.trim().ifBlank {
        when {
            url.startsWith("magnet:", ignoreCase = true) -> torrentDisplayName(url)
            NetworkStream.isTorrentFile(url) ->
                url.substringBefore('?').trimEnd('/').substringAfterLast('/')
                    .substringBeforeLast('.').ifBlank { "Torrent" }
            else -> NetworkStream.hostOf(url).removePrefix("www.").substringBefore('.').ifBlank { "Stream" }
        }
    }
    app.store.addProvider(
        ProviderConfig(
            id = "iptv|" + url.hashCode(),
            name = display,
            type = ProviderType.IPTV,
            url = url,
            extra = NetworkStream.MARKER,
        )
    )
    app.providers.refresh()
    Result.success(1)
}

/** Display name for a pasted torrent: the magnet's own dn= name, the
 *  .torrent file's name, or Torrent + short hash — never a URL shard like
 *  "magnet:". */
private fun torrentDisplayName(link: String): String {
    if (NetworkStream.isBareInfoHash(link)) {
        return "Torrent · " + link.take(8).uppercase()
    }
    Regex("[?&]dn=([^&]+)").find(link)?.let { m ->
        runCatching { java.net.URLDecoder.decode(m.groupValues[1], "UTF-8") }.getOrNull()
            ?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
    }
    if (!link.startsWith("magnet:", ignoreCase = true)) {
        link.substringBefore('?').trimEnd('/').substringAfterLast('/')
            .substringBeforeLast('.').ifBlank { null }?.let { return it }
    }
    val hash = Regex("btih:([a-zA-Z0-9]+)").find(link)?.groupValues?.getOrNull(1)
    return if (hash.isNullOrBlank()) "Torrent" else "Torrent · " + hash.take(8).uppercase()
}

/** One of the add dialog's two modes: Playlist, or a single Network stream. */
@Composable
private fun AddModePill(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

/**
 * One playlist's own page: its groups as tiles, exactly the way a personal
 * catalog shows its folders. A tap opens that group's channels in the paged
 * catalog grid (which is how every other catalog in the app is browsed).
 */
@Composable
fun IptvPlaylistScreen(nav: NavHostController, providerId: String) {
    val app = LocalContext.current.applicationContext as HikariApp
    val all by app.providers.providers.collectAsState()
    val playlist = remember(all, providerId) {
        all.filterIsInstance<IptvProvider>().firstOrNull { it.config.id == providerId }
    }
    val shapeFlow = remember { app.store.iptvShapeFlow() }
    val shape by shapeFlow.collectAsState(initial = TileShapes.POSTER)
    val groupModeFlow = remember { app.store.iptvGroupModeFlow() }
    val groupMode by groupModeFlow.collectAsState(initial = "groups")
    val scope = rememberCoroutineScope()

    var groups by remember(providerId) { mutableStateOf<List<IptvGroupTile>?>(null) }
    LaunchedEffect(providerId, playlist, groupMode) {
        if (playlist == null) {
            groups = emptyList()
            return@LaunchedEffect
        }
        groups = withContext(Dispatchers.IO) { readGroups(playlist, groupMode) }
    }
    val isTorrent = playlist != null && NetworkStream.isTorrentLink(playlist.config.url)

    // ---- In-folder search -------------------------------------------------
    //
    // The header's search icon used to jump to the global Search tab scoped to
    // this playlist — leaving the folder to search it. It now searches right
    // here: the field filters this playlist's channels in place (same backend
    // as the scoped search, `IptvProvider.search`), and a tap plays from the
    // results without ever leaving the folder.
    var folderSearchOpen by remember(providerId) { mutableStateOf(false) }
    var folderQuery by remember(providerId) { mutableStateOf("") }
    var folderResults by remember(providerId) { mutableStateOf<List<MediaItem>?>(null) }
    var folderSearching by remember(providerId) { mutableStateOf(false) }
    var folderPage by remember(providerId) { mutableStateOf(1) }
    var folderHasMore by remember(providerId) { mutableStateOf(false) }
    LaunchedEffect(providerId, playlist, folderQuery) {
        val p = playlist
        val q = folderQuery.trim()
        folderPage = 1
        if (p == null || q.length < 2) {
            folderResults = null
            folderSearching = false
            folderHasMore = false
            return@LaunchedEffect
        }
        folderSearching = true
        delay(400)
        val first = withContext(Dispatchers.IO) {
            runCatching { p.search(q, 1) }.getOrDefault(emptyList())
        }
        // A newer keystroke superseded this run (query changed mid-flight).
        if (folderQuery.trim() != q) return@LaunchedEffect
        folderResults = first
        folderHasMore = first.size >= 240
        folderSearching = false
    }
    fun folderMore() {
        val p = playlist ?: return
        val q = folderQuery.trim()
        if (q.length < 2 || folderSearching) return
        val next = folderPage + 1
        folderSearching = true
        scope.launch {
            val more = withContext(Dispatchers.IO) {
                runCatching { p.search(q, next) }.getOrDefault(emptyList())
            }
            if (folderQuery.trim() != q) {
                folderSearching = false
                return@launch
            }
            folderResults = (folderResults.orEmpty() + more).distinctBy { it.id }
            folderPage = next
            folderHasMore = more.size >= 240
            folderSearching = false
        }
    }

    val loaded = groups
    Column(Modifier.fillMaxSize()) {
        IptvHeader(
            title = playlist?.displayName ?: tr("IPTV"),
            subtitle = when {
                loaded == null -> tr("Reading the playlist…")
                else -> I18n.t("%s channels in %s %s")
                    .replaceFirst("%s", loaded.sumOf { it.count }.toString())
                    .replaceFirst("%s", loaded.size.toString())
                    .replaceFirst("%s", when (IptvPlaylist.normalizeGroupMode(groupMode)) {
                        "language" -> tr("languages")
                        "category" -> tr("categories")
                        "country" -> tr("countries")
                        else -> tr("groups")
                    })
            },
            onBack = { nav.popBackStack() },
            shape = shape,
            onCycleShape = { scope.launch { app.store.setIptvShape(nextShape(shape)) } },
            onSearch = playlist?.let {
                {
                    folderSearchOpen = !folderSearchOpen
                    if (!folderSearchOpen) folderQuery = ""
                }
            },
            onAdd = null,
        )
        if (folderSearchOpen && !isTorrent) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = folderQuery,
                    onValueChange = { folderQuery = it },
                    label = { Text(tr("Search channels")) },
                    singleLine = true,
                    modifier = Modifier.weight(1f).tvTextFieldKeys(folderQuery),
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = {
                    folderSearchOpen = false
                    folderQuery = ""
                }) { Text(tr("Close")) }
            }
        }
        if (!isTorrent) {
            com.hikari.app.ui.components.ChoiceRow(
                value = tr("Grouped by: %s").replace("%s", when (IptvPlaylist.normalizeGroupMode(groupMode)) {
                    "language" -> tr("Language")
                    "category" -> tr("Category")
                    "country" -> tr("Country")
                    else -> tr("Groups")
                }),
                supporting = tr("Group this playlist by its sections, language, category or country"),
                leadingIcon = Icons.Filled.FolderOpen,
                onClick = {
                    val next = when (IptvPlaylist.normalizeGroupMode(groupMode)) {
                        "groups" -> "language"
                        "language" -> "category"
                        "category" -> "country"
                        else -> "groups"
                    }
                    scope.launch { app.store.setIptvGroupMode(next) }
                },
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
        if (loaded == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }
        // In-folder search results replace the group grid while a query is
        // typed — the folder is never left to search it.
        if (folderSearchOpen && !isTorrent && folderQuery.trim().length >= 2) {
            val res = folderResults
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    top = 4.dp,
                    bottom = LocalTaskbarInset.current + 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (res == null || (folderSearching && res.isEmpty())) {
                    item(key = "iptv-search-loading") {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
                if (!folderSearching && res != null && res.isEmpty()) {
                    item(key = "iptv-search-empty") {
                        Text(
                            tr("No channels match that search."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                items(res.orEmpty(), key = { it.id }) { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                Routes.safeNavigate(
                                    nav,
                                    Routes.detail(
                                        providerId,
                                        item.type,
                                        item.id,
                                        item.title,
                                        item.posterUrl,
                                        item.rawType,
                                    ),
                                )
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                item.title,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (item.overview?.isNotBlank() == true) {
                                Text(
                                    item.overview.orEmpty(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                if (folderHasMore) {
                    item(key = "iptv-search-more") {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            TextButton(
                                onClick = { folderMore() },
                                enabled = !folderSearching,
                            ) {
                                if (folderSearching) {
                                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                } else {
                                    Text(tr("Show more"))
                                }
                            }
                        }
                    }
                }
            }
            return@Column
        }
        if (isTorrent && playlist != null) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = tileMinFor(shape)),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    top = 4.dp,
                    bottom = LocalTaskbarInset.current + 24.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    IptvTile(
                        cover = null,
                        name = playlist.displayName,
                        subtitle = tr("Torrent — plays directly"),
                        shape = shape,
                    ) {
                        Routes.safeNavigate(
                            nav,
                            Routes.catalog(
                                providerId = providerId,
                                catalogId = IptvProvider.CATALOG_ALL,
                                title = playlist.displayName,
                                providerName = playlist.displayName,
                                type = MediaType.MOVIE,
                                rawType = "torrent",
                            ),
                        )
                    }
                }
            }
            return@Column
        }
        val name = playlist?.displayName ?: providerId
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = tileMinFor(shape)),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = 4.dp,
                bottom = LocalTaskbarInset.current + 24.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(loaded, key = { it.catalogId }) { g ->
                // Drawn off the main thread: a group has no artwork of its own,
                // so its tile is generated from its name (once — see IptvArt).
                val art by produceState<String?>(initialValue = null, providerId, g.catalogId) {
                    value = withContext(Dispatchers.IO) {
                        IptvArt.tile(
                            MediaItem(
                                providerId = providerId,
                                id = g.catalogId,
                                title = g.name,
                                type = MediaType.MOVIE,
                                overview = name,
                            )
                        )
                    }
                }
                IptvTile(
                    cover = null,
                    art = art,
                    name = g.name,
                    subtitle = I18n.t("%s channels").replace("%s", g.count.toString()),
                    shape = shape,
                ) {
                    Routes.safeNavigate(
                        nav,
                        Routes.catalog(
                            providerId = providerId,
                            catalogId = g.catalogId,
                            title = g.name,
                            providerName = name,
                            type = MediaType.MOVIE,
                            rawType = "channel",
                        ),
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ building --

/** A playlist as the tab draws it. */
private data class IptvCard(
    val id: String,
    val name: String,
    /** The playlist/stream link itself: a network stream opens straight on its
     *  detail page, which plays this URL (no folders in between). */
    val url: String = "",
    val channels: Int,
    val groups: Int,
    val cover: String?,
    val error: String?,
    /** True for a link the user added as a single NETWORK STREAM rather than a
     *  playlist: one channel, and no groups to count. */
    val stream: Boolean = false,
    /** True when that single stream is a torrent (magnet/.torrent): shown and
     *  played as a torrent, never grouped or badged like IPTV. */
    val torrent: Boolean = false,
    /** True when the playlist is the app's own copy of a file the user picked
     *  from storage (under `filesDir/iptv/`) rather than a pasted link — the one
     *  case where removing the tile also deletes something. */
    val local: Boolean = false,
)

/** One group inside a playlist. */
private data class IptvGroupTile(
    val name: String,
    val catalogId: String,
    val count: Int,
)

/** Reads one playlist (its channel count, its groups, and a logo to wear). */
private suspend fun readCard(p: IptvProvider): IptvCard {
    val list = runCatching {
        withTimeoutOrNull(60_000L) { p.channels() }
    }.getOrNull().orEmpty()
    return IptvCard(
        id = p.config.id,
        name = p.displayName,
        url = p.config.url,
        channels = list.size,
        groups = list.map { IptvPlaylist.groupOf(it) }.distinct().size,
        // The first channel that really has a logo: a playlist tile wearing one
        // of its own channel logos reads as "this is what is inside" far better
        // than a generic glyph.
        cover = list.firstOrNull { !it.logo.isNullOrBlank() }?.logo,
        error = IptvProvider.iptvErrors[p.config.id],
        stream = NetworkStream.isStream(p.config),
        torrent = NetworkStream.isTorrentLink(p.config.url),
        local = !p.config.url.startsWith("http"),
    )
}

/**
 * A playlist's groups, biggest first, with "All channels" as the first tile.
 *
 * The ids are the provider's own catalog ids ([IptvProvider.CATALOG_ALL] and
 * [IptvProvider.catalogIdForGroup]), so a tile links straight to the same paged
 * catalog the provider would hand Home — no second code path to keep in step.
 */
private suspend fun readGroups(p: IptvProvider, mode: String): List<IptvGroupTile> {
    val list = runCatching {
        withTimeoutOrNull(60_000L) { p.channels() }
    }.getOrNull().orEmpty()
    if (list.isEmpty()) return emptyList()
    val m = IptvPlaylist.normalizeGroupMode(mode)
    val out = ArrayList<IptvGroupTile>()
    if (m == "groups") {
        out += IptvGroupTile(
            name = I18n.t("All channels"),
            catalogId = IptvProvider.CATALOG_ALL,
            count = list.size,
        )
    }
    list.groupBy { IptvPlaylist.groupKey(it, m) }
        .entries
        .sortedWith(
            compareByDescending<Map.Entry<String, List<com.hikari.app.data.IptvChannel>>> { it.value.size }
                .thenBy { it.key.lowercase() },
        )
        .forEach { (group, channels) ->
            out += IptvGroupTile(
                name = group,
                catalogId = when (m) {
                    "language" -> IptvProvider.catalogIdForLanguage(group)
                    "category" -> IptvProvider.catalogIdForCategory(group)
                    "country" -> IptvProvider.catalogIdForCountry(group)
                    else -> IptvProvider.catalogIdForGroup(group)
                },
                count = channels.size,
            )
        }
    return out
}

// -------------------------------------------------------------------- pieces --

/** The tab's own title bar (no back button on the root tab). */
@Composable
private fun IptvHeader(
    title: String,
    subtitle: String,
    onBack: (() -> Unit)?,
    shape: String,
    onCycleShape: () -> Unit,
    onSearch: (() -> Unit)?,
    onAdd: (() -> Unit)?,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(
                start = if (com.hikari.app.tv.TvMode.current()) 20.dp else 6.dp,
                end = if (com.hikari.app.tv.TvMode.current()) 20.dp else 6.dp,
                top = if (com.hikari.app.tv.TvMode.current()) 12.dp else 6.dp,
                bottom = 2.dp
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(
                    androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = tr("Back"),
                )
            }
        } else {
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                tr(title),
                style = if (com.hikari.app.tv.TvMode.current()) MaterialTheme.typography.headlineSmall
                else MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // Shape: poster → square → wide → poster. One button, three looks, and
        // the choice is remembered (Settings-free: it belongs to this tab).
        IconButton(onClick = onCycleShape) {
            Icon(
                Icons.Filled.GridView,
                contentDescription = I18n.t("Tile shape: %s").replace("%s", shapeLabel(shape)),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        if (onSearch != null) {
            IconButton(onClick = onSearch) {
                Icon(Icons.Filled.Search, contentDescription = tr("Search these channels"))
            }
        }
        if (onAdd != null) {
            IconButton(onClick = onAdd) {
                Icon(Icons.Filled.Add, contentDescription = tr("Add playlist"))
            }
        }
    }
}

/**
 * One tile of the IPTV tab: a cover (a real logo, a drawn channel tile, or the
 * playlist's own generated one) with the name under it, shaped by [shape].
 */
@Composable
private fun IptvTile(
    name: String,
    subtitle: String,
    shape: String,
    cover: String? = null,
    /** A `file://` URL of a locally drawn tile (see [IptvArt]) — used when there
     *  is no real artwork at all, which is the common case for a group. */
    art: String? = null,
    badge: String? = null,
    /** Shown as a trash button on the cover's top-left when non-null (the
     *  badge already owns the top-right). Only the tab's own tiles pass it —
     *  a group inside a playlist has nothing to delete. */
    onRemove: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val tokens = rememberGlassTokens()
    val model = cover ?: art
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(tokens.fillTop)
            .border(1.dp, tokens.border, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(7.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(TileShapes.aspect(shape))
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.LiveTv,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                modifier = Modifier.size(26.dp),
            )
            AsyncImage(
                model = model,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (badge != null) {
                Surface(
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(50),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp),
                ) {
                    Text(
                        badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onError,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
            if (onRemove != null) {
                Surface(
                    onClick = onRemove,
                    color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.62f),
                    shape = RoundedCornerShape(50),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp),
                ) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = I18n.t("Remove %s").replace("%s", name),
                        tint = MaterialTheme.colorScheme.onError,
                        modifier = Modifier.padding(4.dp).size(15.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            name,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 3.dp),
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 3.dp),
        )
    }
}

/** The smallest a tile may be for [shape] — the grid's own column width. */
private fun tileMinFor(shape: String): Dp = when (TileShapes.normalize(shape)) {
    TileShapes.WIDE -> 168.dp
    TileShapes.SQUARE -> 118.dp
    else -> 104.dp
}

/** poster → square → wide → poster. */
private fun nextShape(shape: String): String = when (TileShapes.normalize(shape)) {
    TileShapes.POSTER -> TileShapes.SQUARE
    TileShapes.SQUARE -> TileShapes.WIDE
    else -> TileShapes.POSTER
}

private fun shapeLabel(shape: String): String = when (TileShapes.normalize(shape)) {
    TileShapes.WIDE -> I18n.t("Wide")
    TileShapes.SQUARE -> I18n.t("Square")
    else -> I18n.t("Poster")
}
