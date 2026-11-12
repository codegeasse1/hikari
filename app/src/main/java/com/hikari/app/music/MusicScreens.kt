package com.hikari.app.music

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ====================================================================== HOME

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MusicHomeScreen(state: LazyListState, onOpen: (MItem) -> Unit, onHistory: () -> Unit, onMenu: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var home by remember { mutableStateOf(MusicCache.home) }
    // The endless feed under the first screen. It lives in MusicFeed so switching tabs keeps it.
    var cursor by remember { mutableStateOf(MusicFeed.home) }
    var extra by remember { mutableStateOf(MusicFeed.home?.loaded ?: emptyList()) }
    var feedDone by remember { mutableStateOf(MusicFeed.home?.done ?: false) }
    var loadingMore by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(MusicCache.home == null) }
    var error by remember { mutableStateOf<String?>(null) }
    var chip by remember { mutableStateOf<String?>(null) }
    val plays by MusicStore.plays.collectAsState()

    val load: (String?) -> Unit = { params ->
        scope.launch {
            loading = true
            error = null
            val first = runCatching { MusicApi.home(params) }
            val h = first.getOrNull()
            if (h == null) {
                error = first.exceptionOrNull()?.message ?: "Couldn't load music"
            } else {
                home = h
                // A mood chip gets a feed of its own; no chip = the normal, cached feed.
                val chipTitle = if (params == null) null else {
                    MusicCache.home?.chips.orEmpty().ifEmpty { h.chips }.firstOrNull { it.params == params }?.title
                }
                val c = FeedCursor(h.cont, chipTitle, h.shelves)
                cursor = c
                extra = emptyList()
                feedDone = false
                if (params == null) {
                    MusicCache.home = h
                    MusicCache.extra = emptyList()
                    MusicFeed.home = c
                }
            }
            loading = false
        }
    }
    LaunchedEffect(Unit) { if (MusicCache.home == null) load(null) }

    // Brings the next batch of shelves; called again and again while the person scrolls.
    val loadMore: () -> Unit = {
        val c = cursor
        if (c != null && !loadingMore && !c.done) {
            loadingMore = true
            scope.launch {
                try {
                    c.next()
                    // Ignore the answer if a different chip / refresh replaced this feed meanwhile.
                    if (c === cursor) {
                        extra = c.loaded
                        feedDone = c.done
                    }
                } finally {
                    loadingMore = false
                }
            }
        }
    }

    // Near the bottom of the list -> load more. Re-runs after every batch, so a short page keeps filling.
    val nearEnd by remember(state) {
        derivedStateOf {
            val info = state.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= 0 && last >= info.totalItemsCount - 4
        }
    }
    LaunchedEffect(nearEnd, cursor, loadingMore, feedDone, loading, home) {
        if (nearEnd && home != null && !loading) loadMore()
    }

    val openItem: (MItem) -> Unit = { item ->
        if (item.kind == MKind.SONG) {
            val s = item.song
            if (s != null) MusicPlayer.playSong(ctx, s)
        } else onOpen(item)
    }

    val current = home
    val chips = (MusicCache.home ?: current)?.chips.orEmpty()
    val shelves = current?.shelves.orEmpty()
    val heroShelf = shelves.firstOrNull { it.items.size >= 3 }
    val rest = shelves.filter { it !== heroShelf } + extra
    val recent = remember(plays) { plays.sortedByDescending { it.last }.map { it.song } }

    LazyColumn(state = state, contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            MusicTopBar("HIKari music", onMenu) {
                IconButton(onClick = onHistory) {
                    Icon(Icons.Filled.History, contentDescription = "History", tint = cs.onBackground)
                }
            }
        }
        if (chips.isNotEmpty()) {
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(chips) { c ->
                        MusicPill(c.title, selected = chip == c.params) {
                            chip = if (chip == c.params) null else c.params
                            load(chip)
                        }
                    }
                }
            }
        }
        if (heroShelf != null) {
            item {
                val heroState = rememberLazyListState()
                LazyRow(
                    state = heroState,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    flingBehavior = rememberSnapFlingBehavior(heroState),
                ) {
                    items(heroShelf.items.take(10)) { it2 ->
                        MusicHeroCard(it2, Modifier.width(300.dp)) { openItem(it2) }
                    }
                }
            }
        }
        if (recent.isNotEmpty()) {
            // Every played song, as lazy rows: the more you scroll the more it shows.
            item(key = "recent_header") {
                MusicSectionHeader("Recently played") { MusicPlayer.playQueue(ctx, recent, 0) }
            }
            itemsIndexed(recent, key = { i, s -> "recent_" + i + "_" + s.id }) { i, song ->
                MusicSongRow(
                    song,
                    modifier = musicGroupRow(i, recent.size),
                    background = cs.surfaceVariant.copy(alpha = 0.55f),
                ) { MusicPlayer.playQueue(ctx, recent, i) }
            }
        }
        rest.forEachIndexed { idx, shelf -> musicShelfItems(idx, shelf, openItem) }
        if (loading) {
            item {
                Text(
                    "Loading…",
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }
        if (loadingMore && !loading) {
            item(key = "feed_loading") {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp, color = cs.primary)
                }
            }
        }
        val err = error
        if (err != null && current == null) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Couldn't load music", color = cs.onSurface, fontWeight = FontWeight.SemiBold)
                    Text(err, color = cs.onSurfaceVariant, fontSize = 13.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    MusicPill("Try again", selected = true) { load(chip) }
                }
            }
        }
    }
}

/** Rounded "card group" look for one row of a list that is built from separate lazy items. */
private fun musicGroupRow(index: Int, count: Int): Modifier {
    val round = 24.dp
    val shape = RoundedCornerShape(
        topStart = if (index == 0) round else 0.dp,
        topEnd = if (index == 0) round else 0.dp,
        bottomStart = if (index == count - 1) round else 0.dp,
        bottomEnd = if (index == count - 1) round else 0.dp,
    )
    return Modifier
        .padding(horizontal = 16.dp)
        .padding(bottom = if (index == count - 1) 0.dp else 2.dp)
        .clip(shape)
}

/**
 * One shelf of Home / a detail page: a header, then either an Echo-style list (every song is its own
 * lazy row, so a long list scrolls forever) or a row of cards.
 */
fun LazyListScope.musicShelfItems(shelfIndex: Int, shelf: MShelf, onOpen: (MItem) -> Unit) {
    val songs = shelf.items.mapNotNull { it.song }
    val allSongs = shelf.items.isNotEmpty() && shelf.items.all { it.kind == MKind.SONG }
    val listMode = shelf.asList && allSongs && shelf.items.size >= 3
    val prefix = "shelf" + shelfIndex + "_"
    item(key = prefix + "header") {
        val ctx = LocalContext.current
        val playAll: (() -> Unit)? = if (allSongs && songs.isNotEmpty()) {
            { MusicPlayer.playQueue(ctx, songs, 0) }
        } else null
        MusicSectionHeader(shelf.title.ifBlank { "More" }, playAll)
    }
    if (listMode) {
        itemsIndexed(shelf.items, key = { i, it2 -> prefix + i + "_" + it2.id }) { i, item ->
            val s = item.song
            if (s != null) {
                val ctx = LocalContext.current
                MusicSongRow(
                    s,
                    modifier = musicGroupRow(i, shelf.items.size),
                    background = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                ) { MusicPlayer.playQueue(ctx, songs, i) }
            }
        }
    } else {
        item(key = prefix + "cards") {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(shelf.items) { item -> MusicItemCard(item) { onOpen(item) } }
            }
        }
    }
}

// ====================================================================== SEARCH

private val SEARCH_FILTERS: List<Pair<String, String?>> = listOf(
    "All" to null,
    "Songs" to MusicApi.FILTER_SONG,
    "Albums" to MusicApi.FILTER_ALBUM,
    "Artists" to MusicApi.FILTER_ARTIST,
    "Playlists" to MusicApi.FILTER_PLAYLIST,
)

private val MOODS = listOf(
    "Pop hits", "Hip-hop", "Bollywood", "Romance", "Chill", "Workout",
    "Party", "Sad songs", "Focus", "Sleep", "Rock", "Classical",
)

@Composable
fun MusicSearchScreen(state: LazyListState, onOpen: (MItem) -> Unit, onMenu: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val recent by MusicStore.searches.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(0) }
    var suggestions by remember { mutableStateOf(emptyList<String>()) }
    var results by remember { mutableStateOf<List<MShelf>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val doSearch: (String, Int) -> Unit = { q, f ->
        val text = q.trim()
        if (text.isNotEmpty()) {
            submitted = text
            query = text
            filter = f
            suggestions = emptyList()
            MusicStore.addSearch(text)
            focus.clearFocus()
            loading = true
            error = null
            scope.launch {
                val r = runCatching { MusicApi.search(text, SEARCH_FILTERS[f].second) }
                results = r.getOrNull()
                if (r.isFailure) error = r.exceptionOrNull()?.message ?: "Search failed"
                loading = false
            }
        }
    }

    LaunchedEffect(query) {
        if (query.isBlank() || query == submitted) {
            suggestions = emptyList()
        } else {
            delay(250)
            suggestions = runCatching { MusicApi.suggestions(query) }.getOrDefault(emptyList())
        }
    }

    LazyColumn(state = state, contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onMenu) {
                    Icon(Icons.Filled.Menu, contentDescription = "Select provider", tint = cs.onBackground)
                }
                Row(
                    Modifier
                        .weight(1f)
                        .padding(end = 16.dp)
                        .height(52.dp)
                        .clip(RoundedCornerShape(26.dp))
                        .background(cs.surfaceVariant),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = null,
                        tint = cs.onSurfaceVariant,
                        modifier = Modifier.padding(start = 14.dp, end = 8.dp),
                    )
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) {
                            Text("Search YouTube Music…", color = cs.onSurfaceVariant, fontSize = 16.sp)
                        }
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            textStyle = TextStyle(color = cs.onSurface, fontSize = 16.sp),
                            cursorBrush = SolidColor(cs.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { doSearch(query, filter) }),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (query.isNotEmpty()) {
                        IconButton(onClick = {
                            query = ""
                            submitted = ""
                            results = null
                            error = null
                        }) { Icon(Icons.Filled.Close, contentDescription = "Clear", tint = cs.onSurfaceVariant) }
                    }
                }
            }
        }

        val shown = results
        if (suggestions.isNotEmpty() && query != submitted) {
            items(suggestions) { s ->
                Row(
                    Modifier.fillMaxWidth().clickable { doSearch(s, filter) }.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(s, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        } else if (shown == null && !loading) {
            if (recent.isNotEmpty()) {
                item { MusicSectionHeader("Recent searches") }
                items(recent) { s ->
                    Row(
                        Modifier.fillMaxWidth().clickable { doSearch(s, 0) }.padding(start = 20.dp, top = 2.dp, bottom = 2.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.History, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(16.dp))
                        Text(s, color = cs.onSurface, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = { MusicStore.removeSearch(s) }) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove", tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
            item { MusicSectionHeader("Browse") }
            MOODS.chunked(2).forEach { pair ->
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        pair.forEach { mood ->
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(64.dp)
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(cs.surfaceVariant.copy(alpha = 0.7f))
                                    .clickable { doSearch(mood, 0) }
                                    .padding(horizontal = 16.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                Text(mood, color = cs.onSurface, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        } else {
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(SEARCH_FILTERS) { i, f ->
                        MusicPill(f.first, selected = filter == i) { if (filter != i) doSearch(submitted, i) }
                    }
                }
            }
            if (loading) {
                item {
                    Text(
                        "Searching…",
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            }
            val err = error
            if (err != null) {
                item {
                    Text(err, color = cs.error, modifier = Modifier.fillMaxWidth().padding(24.dp), textAlign = TextAlign.Center)
                }
            }
            if (shown != null && shown.isEmpty() && !loading && err == null) {
                item {
                    Text(
                        "No results",
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            }
            shown?.forEach { shelf ->
                if (shelf.title.isNotBlank()) item { MusicSectionHeader(shelf.title) }
                items(shelf.items) { item ->
                    val song = item.song
                    if (song != null) {
                        MusicSongRow(song) { MusicPlayer.playSong(ctx, song) }
                    } else {
                        MusicItemRow(item) { onOpen(item) }
                    }
                }
            }
        }
    }
}

// ====================================================================== LIBRARY

@Composable
fun MusicLibraryScreen(state: LazyListState, onOpen: (MRoute) -> Unit, onMenu: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val liked by MusicStore.liked.collectAsState()
    val playlists by MusicStore.playlists.collectAsState()
    val saved by MusicStore.saved.collectAsState()
    var tab by rememberSaveable { mutableStateOf(0) }
    var creating by remember { mutableStateOf(false) }
    val tabs = listOf("Playlists", "Songs", "Albums", "Artists")
    val tiles: List<Triple<ImageVector, String, String>> = listOf(
        Triple(Icons.Filled.Favorite, "Liked", "liked"),
        Triple(Icons.Filled.History, "Recently played", "history"),
        Triple(Icons.Filled.TrendingUp, "My top 50", "top"),
        Triple(Icons.Filled.TrendingDown, "My bottom 50", "bottom"),
    )

    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = state, contentPadding = PaddingValues(bottom = 96.dp)) {
            item { MusicTopBar("Library", onMenu) }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(tabs) { i, t -> MusicPill(t, selected = tab == i) { tab = i } }
                }
            }
            when (tab) {
                0 -> {
                    tiles.chunked(2).forEach { pair ->
                        item {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                pair.forEach { t ->
                                    Row(
                                        Modifier
                                            .weight(1f)
                                            .height(60.dp)
                                            .clip(RoundedCornerShape(16.dp))
                                            .background(cs.surfaceVariant.copy(alpha = 0.7f))
                                            .clickable { onOpen(MRoute.Local(t.third, t.second)) }
                                            .padding(horizontal = 16.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(t.first, contentDescription = null, tint = cs.onSurface)
                                        Spacer(Modifier.width(12.dp))
                                        Text(t.second, color = cs.onSurface, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                    item { MusicSectionHeader("Playlists") }
                    if (playlists.isEmpty()) {
                        item {
                            Text(
                                "No playlists yet. Tap + to make one.",
                                color = cs.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                            )
                        }
                    }
                    playlists.chunked(2).forEach { pair ->
                        item {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                pair.forEach { pl ->
                                    Column(
                                        Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(14.dp))
                                            .clickable { onOpen(MRoute.Local("pl:" + pl.id, pl.name)) },
                                    ) {
                                        MusicCover(
                                            pl.songs.firstOrNull()?.thumb.orEmpty(),
                                            Modifier.fillMaxWidth().height(150.dp),
                                            RoundedCornerShape(14.dp),
                                        )
                                        Text(pl.name, color = cs.onSurface, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(pl.songs.size.toString() + " songs", color = cs.onSurfaceVariant, fontSize = 13.sp)
                                    }
                                }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
                1 -> {
                    if (liked.isEmpty()) {
                        item {
                            Text(
                                "Songs you like (♥ in the player or ⋮ menu) show up here.",
                                color = cs.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                            )
                        }
                    }
                    itemsIndexed(liked) { i, s -> MusicSongRow(s) { MusicPlayer.playQueue(ctx, liked, i) } }
                }
                else -> {
                    val want = if (tab == 2) setOf(MKind.ALBUM, MKind.PLAYLIST) else setOf(MKind.ARTIST)
                    val list = saved.filter { it.kind in want }
                    if (list.isEmpty()) {
                        item {
                            Text(
                                "Tap Save on an album, playlist or artist page to keep it here.",
                                color = cs.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                            )
                        }
                    }
                    items(list) { it2 ->
                        MusicItemRow(it2) { onOpen(MRoute.Remote(it2.kind, it2.id, it2.title, it2.thumb)) }
                    }
                }
            }
        }
        if (tab == 0) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .size(56.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(cs.primary)
                    .clickable { creating = true },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Add, contentDescription = "New playlist", tint = cs.onPrimary) }
        }
    }
    if (creating) {
        MusicNameDialog("New playlist", "", "Create", onDismiss = { creating = false }) { name ->
            MusicStore.createPlaylist(name)
        }
    }
}

// ====================================================================== DETAIL PAGES

@Composable
private fun MusicDetailHeader(
    title: String,
    subtitle: String,
    thumb: String,
    circle: Boolean,
    songs: List<MSong>,
    onBack: () -> Unit,
    extra: @Composable () -> Unit,
) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = cs.onBackground)
            }
        }
        MusicCover(
            thumb,
            Modifier.size(220.dp),
            if (circle) CircleShape else RoundedCornerShape(20.dp),
            700,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            title,
            color = cs.onBackground,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        if (subtitle.isNotBlank()) {
            Text(
                subtitle,
                color = cs.onSurfaceVariant,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (songs.isNotEmpty()) {
                MusicActionPill(Icons.Filled.PlayArrow, "Play", filled = true) { MusicPlayer.playQueue(ctx, songs, 0) }
                MusicActionPill(Icons.Filled.Shuffle, "Shuffle", filled = false) {
                    MusicPlayer.playQueue(ctx, songs, (songs.indices).random(), shuffle = true)
                }
            }
            extra()
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun MusicDetailScreen(route: MRoute.Remote, onBack: () -> Unit, onOpen: (MItem) -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var detail by remember(route.id) { mutableStateOf<MDetail?>(null) }
    var error by remember(route.id) { mutableStateOf<String?>(null) }
    val saved by MusicStore.saved.collectAsState()
    LaunchedEffect(route.id) {
        val r = runCatching { MusicApi.detail(route.id, route.title, route.thumb) }
        detail = r.getOrNull()
        if (r.isFailure) error = r.exceptionOrNull()?.message ?: "Couldn't load this page"
    }
    val d = detail
    val isSaved = saved.any { it.id == route.id }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            MusicDetailHeader(
                title = d?.title ?: route.title,
                subtitle = d?.subtitle.orEmpty(),
                thumb = d?.thumb ?: route.thumb,
                circle = route.kind == MKind.ARTIST,
                songs = d?.songs.orEmpty(),
                onBack = onBack,
            ) {
                MusicActionPill(
                    if (isSaved) Icons.Filled.Check else Icons.Filled.Add,
                    if (isSaved) "Saved" else "Save",
                    filled = false,
                ) {
                    MusicStore.toggleSaved(
                        MItem(route.kind, route.id, d?.title ?: route.title, d?.subtitle.orEmpty(), d?.thumb ?: route.thumb),
                    )
                }
            }
        }
        if (d == null && error == null) {
            item {
                Text("Loading…", color = cs.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(24.dp), textAlign = TextAlign.Center)
            }
        }
        val err = error
        if (err != null) {
            item {
                Text(err, color = cs.error, modifier = Modifier.fillMaxWidth().padding(24.dp), textAlign = TextAlign.Center)
            }
        }
        if (d != null) {
            itemsIndexed(d.songs, key = { i, s -> i.toString() + s.id }) { i, s ->
                MusicSongRow(s) { MusicPlayer.playQueue(ctx, d.songs, i) }
            }
            d.shelves.forEachIndexed { idx, shelf -> musicShelfItems(idx, shelf, onOpen) }
        }
    }
}

@Composable
fun MusicLocalScreen(route: MRoute.Local, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val liked by MusicStore.liked.collectAsState()
    val plays by MusicStore.plays.collectAsState()
    val playlists by MusicStore.playlists.collectAsState()
    var rename by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val key = route.key
    val playlistId = if (key.startsWith("pl:")) key.removePrefix("pl:") else null
    val playlist = playlists.firstOrNull { it.id == playlistId }
    val songs: List<MSong> = when {
        key == "liked" -> liked
        key == "history" -> plays.sortedByDescending { it.last }.map { it.song }
        key == "top" -> plays.sortedByDescending { it.count }.take(50).map { it.song }
        key == "bottom" -> plays.sortedBy { it.count }.take(50).map { it.song }
        playlistId != null -> playlist?.songs.orEmpty()
        else -> emptyList()
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            MusicDetailHeader(
                title = playlist?.name ?: route.title,
                subtitle = songs.size.toString() + " songs",
                thumb = songs.firstOrNull()?.thumb.orEmpty(),
                circle = false,
                songs = songs,
                onBack = onBack,
            ) {
                if (playlistId != null) {
                    IconButton(onClick = { rename = true }) { Icon(Icons.Filled.Edit, contentDescription = "Rename", tint = cs.onSurface) }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = cs.onSurface) }
                }
                if (key == "history") {
                    TextButton(onClick = { MusicStore.clearHistory() }) { Text("Clear") }
                }
            }
        }
        if (songs.isEmpty()) {
            item {
                Text(
                    "Nothing here yet.",
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }
        itemsIndexed(songs, key = { i, s -> i.toString() + s.id }) { i, s ->
            MusicSongRow(s, playlistId = playlistId) { MusicPlayer.playQueue(ctx, songs, i) }
        }
    }
    if (rename && playlist != null) {
        MusicNameDialog("Rename playlist", playlist.name, "Save", onDismiss = { rename = false }) { name ->
            MusicStore.renamePlaylist(playlist.id, name)
        }
    }
    if (confirmDelete && playlistId != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete playlist?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    MusicStore.deletePlaylist(playlistId)
                    onBack()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
