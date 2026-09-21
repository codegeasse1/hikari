package com.hikari.app.ui.screens

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

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

    fun addNow() {
        val link = addLink
        val local = addFilePath.takeIf { it.isNotBlank() }
        val name = addName
        adding = true
        scope.launch {
            val result = addIptvPlaylist(app, link, local, name)
            adding = false
            result.fold(
                onSuccess = { n ->
                    Toast.makeText(
                        uiContext,
                        I18n.t("Added IPTV playlist (%s channels)").replace("%s", n.toString()),
                        Toast.LENGTH_LONG,
                    ).show()
                    showAdd = false
                    addLink = ""
                    addName = ""
                    addFileLabel = ""
                    addFilePath = ""
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

    var cards by remember { mutableStateOf<List<IptvCard>>(emptyList()) }
    var reading by remember { mutableStateOf(false) }
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
                            card.channels == 0 -> tr("Empty playlist")
                            else -> I18n.t("%s channels · %s groups")
                                .replace("%s", card.channels.toString())
                                .replace("%s", card.groups.toString())
                        },
                        shape = shape,
                        badge = if (card.error != null) tr("Didn't load") else null,
                    ) {
                        Routes.safeNavigate(nav, Routes.iptvPlaylist(card.id))
                    }
                }
            }
        }
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { if (!adding) showAdd = false },
            title = { Text(tr("Add IPTV playlist")) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        tr(
                            "Paste an M3U/M3U8 link — an Xtream panel's " +
                                "get.php?username=…&password=…&type=m3u_plus link works, and so " +
                                "does a single m3u8 stream. Or pick a playlist file from storage."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = addLink,
                        onValueChange = { addLink = it },
                        placeholder = { Text(tr("https://…/playlist.m3u")) },
                        singleLine = false,
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
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
                        label = { Text(tr("Name (optional)")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (adding) {
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                tr("Reading playlist…"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !adding && (addLink.isNotBlank() || addFilePath.isNotBlank()),
                    onClick = { addNow() },
                ) { Text(tr("Add")) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAdd = false
                    addLink = ""
                    addName = ""
                    addFileLabel = ""
                    addFilePath = ""
                }) { Text(tr("Cancel")) }
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
    val scope = rememberCoroutineScope()

    var groups by remember(providerId) { mutableStateOf<List<IptvGroupTile>?>(null) }
    LaunchedEffect(providerId) {
        if (playlist == null) {
            groups = emptyList()
            return@LaunchedEffect
        }
        groups = withContext(Dispatchers.IO) { readGroups(playlist) }
    }

    val loaded = groups
    Column(Modifier.fillMaxSize()) {
        IptvHeader(
            title = playlist?.displayName ?: tr("IPTV"),
            subtitle = when {
                loaded == null -> tr("Reading the playlist…")
                else -> I18n.t("%s channels in %s groups")
                    .replace("%s", loaded.sumOf { it.count }.toString())
                    .replace("%s", loaded.size.toString())
            },
            onBack = { nav.popBackStack() },
            shape = shape,
            onCycleShape = { scope.launch { app.store.setIptvShape(nextShape(shape)) } },
            onSearch = playlist?.let { p -> { Routes.safeNavigate(nav, Routes.searchInProvider(p.config.id)) } },
            onAdd = null,
        )
        if (loaded == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
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
    val channels: Int,
    val groups: Int,
    val cover: String?,
    val error: String?,
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
        channels = list.size,
        groups = list.map { IptvPlaylist.groupOf(it) }.distinct().size,
        // The first channel that really has a logo: a playlist tile wearing one
        // of its own channel logos reads as "this is what is inside" far better
        // than a generic glyph.
        cover = list.firstOrNull { !it.logo.isNullOrBlank() }?.logo,
        error = IptvProvider.iptvErrors[p.config.id],
    )
}

/**
 * A playlist's groups, biggest first, with "All channels" as the first tile.
 *
 * The ids are the provider's own catalog ids ([IptvProvider.CATALOG_ALL] and
 * [IptvProvider.catalogIdForGroup]), so a tile links straight to the same paged
 * catalog the provider would hand Home — no second code path to keep in step.
 */
private suspend fun readGroups(p: IptvProvider): List<IptvGroupTile> {
    val list = runCatching {
        withTimeoutOrNull(60_000L) { p.channels() }
    }.getOrNull().orEmpty()
    if (list.isEmpty()) return emptyList()
    val out = ArrayList<IptvGroupTile>()
    out += IptvGroupTile(
        name = I18n.t("All channels"),
        catalogId = IptvProvider.CATALOG_ALL,
        count = list.size,
    )
    list.groupBy { IptvPlaylist.groupOf(it) }
        .entries
        .sortedWith(
            compareByDescending<Map.Entry<String, List<com.hikari.app.data.IptvChannel>>> { it.value.size }
                .thenBy { it.key.lowercase() },
        )
        .forEach { (group, channels) ->
            out += IptvGroupTile(
                name = group,
                catalogId = IptvProvider.catalogIdForGroup(group),
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
            .padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 2.dp),
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
                style = MaterialTheme.typography.titleLarge,
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
