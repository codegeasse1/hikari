package com.hikari.app.music

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import coil.compose.AsyncImage
import com.hikari.app.R
import com.hikari.app.tv.TvDesktop
import com.hikari.app.tv.TvUi
import com.hikari.app.tv.tvTextFieldKeys
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val TvMusicError = Color(0xFFFF8A80)

// ====================================================================== HOME

/**
 * Music Home for the television,
 */
@UnstableApi
@Composable
fun TvMusicHome(
    startPad: Dp,
    tabs: @Composable () -> Unit,
    onOpen: (MItem) -> Unit,
    onPlayed: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val s = TvUi.scale()
    var home by remember { mutableStateOf(MusicCache.home) }
    // The endless feed under the first screen. It lives in MusicFeed so switching pages keeps it.
    var cursor by remember { mutableStateOf(MusicFeed.home) }
    var extra by remember { mutableStateOf(MusicFeed.home?.loaded ?: emptyList()) }
    var feedDone by remember { mutableStateOf(MusicFeed.home?.done ?: false) }
    var loadingMore by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(MusicCache.home == null) }
    var error by remember { mutableStateOf<String?>(null) }
    val plays by MusicStore.plays.collectAsState()
    val listState = rememberLazyListState()

    val load: () -> Unit = {
        scope.launch {
            loading = true
            error = null
            val first = runCatching { MusicApi.home(null) }
            val h = first.getOrNull()
            if (h == null) {
                error = first.exceptionOrNull()?.message ?: "Couldn't load music"
            } else {
                home = h
                val c = FeedCursor(h.cont, null, h.shelves)
                cursor = c
                extra = emptyList()
                feedDone = false
                MusicCache.home = h
                MusicCache.extra = emptyList()
                MusicFeed.home = c
            }
            loading = false
        }
    }
    LaunchedEffect(Unit) { if (MusicCache.home == null) load() }

    // Brings the next batch of shelves; called again and again while the person moves down.
    val loadMore: () -> Unit = {
        val c = cursor
        if (c != null && !loadingMore && !c.done) {
            loadingMore = true
            scope.launch {
                try {
                    c.next()
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
    val nearEnd by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= 0 && last >= info.totalItemsCount - 3
        }
    }
    LaunchedEffect(nearEnd, cursor, loadingMore, feedDone, loading, home) {
        if (nearEnd && home != null && !loading) loadMore()
    }

    val current = home
    val shelves = (current?.shelves.orEmpty() + extra).filter { it.items.isNotEmpty() }
    val recent = remember(plays) {
        plays.sortedByDescending { it.last }.take(30).map { it.song.asItem() }
    }

    var focusItem by remember { mutableStateOf<MItem?>(null) }
    var focusLabel by remember { mutableStateOf("") }
    val onFocusItem: (MItem, String) -> Unit = remember {
        { item: MItem, label: String ->
            focusItem = item
            focusLabel = label
        }
    }
    val firstShelfTitle = if (recent.isNotEmpty()) "Recently played" else shelves.firstOrNull()?.title.orEmpty()
    val heroItem = focusItem ?: recent.firstOrNull() ?: shelves.firstOrNull()?.items?.firstOrNull()
    val heroLabel = if (focusItem != null) focusLabel else firstShelfTitle

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val heroHeight = maxHeight * 0.30f
        TvMusicBackdrop(heroItem?.thumb.orEmpty())
        Column(Modifier.fillMaxSize().padding(top = 20.dp)) {
            Box(Modifier.padding(start = startPad)) { tabs() }
            TvMusicHero(
                heroItem,
                heroLabel,
                Modifier.padding(start = startPad, end = 40.dp).fillMaxWidth().height(heroHeight),
            )
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(top = 6.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (recent.isNotEmpty()) {
                    item(key = "tv_recent") {
                        TvMusicShelf(
                            "Recently played",
                            recent,
                            startPad,
                            onOpen,
                            onFocused = { onFocusItem(it, "Recently played") },
                            onPlayed = onPlayed,
                        )
                    }
                }
                shelves.forEachIndexed { idx, shelf ->
                    item(key = "tv_shelf_$idx") {
                        TvMusicShelf(
                            shelf.title,
                            shelf.items,
                            startPad,
                            onOpen,
                            onFocused = { onFocusItem(it, shelf.title) },
                            onPlayed = onPlayed,
                        )
                    }
                }
                if (loading) {
                    item(key = "tv_loading") {
                        Text(
                            "Loading…",
                            color = TvMusicMuted,
                            fontSize = 18.sp * s,
                            modifier = Modifier.padding(start = startPad, top = 24.dp),
                        )
                    }
                }
                if (loadingMore && !loading) {
                    item(key = "tv_feed_loading") {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(28.dp),
                                strokeWidth = 3.dp,
                                color = TvMusicPrimary,
                            )
                        }
                    }
                }
                val err = error
                if (err != null && current == null) {
                    item(key = "tv_error") {
                        Column(Modifier.padding(start = startPad, top = 16.dp)) {
                            Text("Couldn't load music", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 20.sp * s)
                            Text(err, color = TvMusicMuted, fontSize = 14.sp * s)
                            Spacer(Modifier.height(12.dp))
                            TvMusicPill("Try again", selected = true) { load() }
                        }
                    }
                }
            }
        }
    }
}

/** The big picture behind the Home hero: top right, fading into the page on the left and below. */
@Composable
private fun TvMusicBackdrop(thumb: String) {
    val base = TvDesktop.Background
    Box(Modifier.fillMaxSize()) {
        Crossfade(targetState = thumb, animationSpec = tween(450), label = "tvMusicBackdrop") { url ->
            Box(Modifier.fillMaxSize()) {
                if (url.isNotBlank()) {
                    AsyncImage(
                        model = MusicApi.hq(url, 900),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .fillMaxHeight(0.82f)
                            .aspectRatio(1f),
                    )
                }
            }
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to base,
                    0.46f to base,
                    0.86f to Color.Transparent,
                ),
            ),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0.30f to Color.Transparent,
                    0.62f to base.copy(alpha = 0.92f),
                    1f to base,
                ),
            ),
        )
    }
}

@Composable
private fun TvMusicHero(item: MItem?, label: String, modifier: Modifier) {
    val s = TvUi.scale()
    Column(modifier, verticalArrangement = Arrangement.Bottom) {
        if (item == null) {
            Text(
                "HIKari music",
                color = Color.White,
                fontSize = 40.sp * s,
                fontWeight = FontWeight.ExtraBold,
            )
        } else {
            if (label.isNotBlank()) {
                Text(
                    label,
                    color = Color.White.copy(alpha = 0.78f),
                    fontSize = 18.sp * s,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                item.title,
                color = Color.White,
                fontSize = 40.sp * s,
                lineHeight = 44.sp * s,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.subtitle.isNotBlank()) {
                Text(
                    item.subtitle,
                    color = Color.White.copy(alpha = 0.82f),
                    fontSize = 16.sp * s,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ====================================================================== SEARCH

private val TV_SEARCH_FILTERS: List<Pair<String, String?>> = listOf(
    "All" to null,
    "Songs" to MusicApi.FILTER_SONG,
    "Albums" to MusicApi.FILTER_ALBUM,
    "Artists" to MusicApi.FILTER_ARTIST,
    "Playlists" to MusicApi.FILTER_PLAYLIST,
)

private class TvMood(
    val name: String,
    val cover: Int? = null,
    val tint: List<Color> = listOf(Color(0xFF3A3F5C), Color(0xFF1C1F33)),
)

private val TV_MOODS = listOf(
    TvMood("Pop hits", R.drawable.music_genre_pop),
    TvMood("Hip-hop", R.drawable.music_genre_hiphop),
    TvMood("Bollywood", R.drawable.music_genre_bollywood),
    TvMood("Romance", R.drawable.music_genre_romance),
    TvMood("Chill", R.drawable.music_genre_chill),
    TvMood("Workout", R.drawable.music_genre_workout),
    TvMood("Party", R.drawable.music_genre_party),
    TvMood("Sad songs", R.drawable.music_genre_sad),
    TvMood("Focus", tint = listOf(Color(0xFF1E5F74), Color(0xFF0B2530))),
    TvMood("Sleep", tint = listOf(Color(0xFF3B3A7A), Color(0xFF12122E))),
    TvMood("Rock", tint = listOf(Color(0xFF8A2A2A), Color(0xFF2A0D0D))),
    TvMood("Classical", tint = listOf(Color(0xFF7A5A2C), Color(0xFF2A1E0E))),
)

@Composable
private fun TvMoodTile(mood: TvMood, modifier: Modifier, onClick: () -> Unit) {
    val s = TvUi.scale()
    Box(
        modifier
            .aspectRatio(1.8f)
            .tvMusicFocus(RoundedCornerShape(16.dp), onClick = onClick)
            .background(Color.White.copy(alpha = 0.10f)),
        contentAlignment = Alignment.Center,
    ) {
        val cover = mood.cover
        if (cover != null) {
            Image(
                painter = painterResource(cover),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(mood.tint)))
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = 0.22f), Color.Black.copy(alpha = 0.58f)),
                ),
            ),
        )
        Text(
            mood.name,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp * s,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp),
        )
    }
}

@UnstableApi
@Composable
fun TvMusicSearch(
    startPad: Dp,
    tabs: @Composable () -> Unit,
    onOpen: (MItem) -> Unit,
    onPlayed: () -> Unit,
) {
    val s = TvUi.scale()
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
    var fieldFocused by remember { mutableStateOf(false) }

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
                val r = runCatching { MusicApi.search(text, TV_SEARCH_FILTERS[f].second) }
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

    Column(Modifier.fillMaxSize().padding(top = 20.dp)) {
        Box(Modifier.padding(start = startPad)) { tabs() }
        Spacer(Modifier.height(14.dp))
        val fieldShape = RoundedCornerShape(28.dp)
        Row(
            Modifier
                .padding(start = startPad, end = 40.dp)
                .fillMaxWidth()
                .height(56.dp * s)
                .onFocusChanged { fieldFocused = it.hasFocus }
                .clip(fieldShape)
                .background(Color.White.copy(alpha = 0.12f))
                .border(2.dp, if (fieldFocused) Color.White else Color.Transparent, fieldShape),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = TvMusicMuted,
                modifier = Modifier.padding(start = 18.dp, end = 10.dp),
            )
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text("Search YouTube Music…", color = TvMusicMuted, fontSize = 18.sp * s)
                }
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 18.sp * s),
                    cursorBrush = SolidColor(Color.White),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { doSearch(query, filter) }),
                    modifier = Modifier.fillMaxWidth().tvTextFieldKeys(query),
                )
            }
            if (query.isNotEmpty()) {
                TvMusicIconButton(Icons.Filled.Close, "Clear", size = 40.dp) {
                    query = ""
                    submitted = ""
                    results = null
                    error = null
                }
                Spacer(Modifier.width(8.dp))
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(top = 14.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val shown = results
            if (suggestions.isNotEmpty() && query != submitted) {
                items(suggestions) { sug ->
                    TvMusicTextRow(sug, Icons.Filled.Search, Modifier.padding(start = startPad, end = 40.dp)) {
                        doSearch(sug, filter)
                    }
                }
            } else if (shown == null && !loading) {
                if (recent.isNotEmpty()) {
                    item { TvMusicSectionTitle("Recent searches", Modifier.padding(start = startPad)) }
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(start = startPad, end = 40.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(recent) { r ->
                                TvMusicPill(r, icon = Icons.Filled.History) { doSearch(r, 0) }
                            }
                        }
                    }
                }
                item { TvMusicSectionTitle("Browse", Modifier.padding(start = startPad)) }
                TV_MOODS.chunked(4).forEach { rowMoods ->
                    item {
                        Row(
                            Modifier.padding(start = startPad, end = 40.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            rowMoods.forEach { mood ->
                                TvMoodTile(mood, Modifier.weight(1f)) { doSearch(mood.name, 0) }
                            }
                            repeat(4 - rowMoods.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            } else {
                item {
                    LazyRow(
                        contentPadding = PaddingValues(start = startPad, end = 40.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        itemsIndexed(TV_SEARCH_FILTERS) { i, f ->
                            TvMusicPill(f.first, selected = filter == i) {
                                if (filter != i) doSearch(submitted, i)
                            }
                        }
                    }
                }
                if (loading) {
                    item {
                        Text(
                            "Searching…",
                            color = TvMusicMuted,
                            fontSize = 18.sp * s,
                            modifier = Modifier.padding(start = startPad, top = 16.dp),
                        )
                    }
                }
                val err = error
                if (err != null) {
                    item {
                        Text(
                            err,
                            color = TvMusicError,
                            fontSize = 16.sp * s,
                            modifier = Modifier.padding(start = startPad, top = 16.dp),
                        )
                    }
                }
                if (shown != null && shown.isEmpty() && !loading && err == null) {
                    item {
                        Text(
                            "No results",
                            color = TvMusicMuted,
                            fontSize = 18.sp * s,
                            modifier = Modifier.padding(start = startPad, top = 16.dp),
                        )
                    }
                }
                shown?.forEachIndexed { idx, shelf ->
                    item(key = "tv_search_shelf_$idx") {
                        TvMusicShelf(
                            shelf.title,
                            shelf.items,
                            startPad,
                            onOpen,
                            onFocused = {},
                            onPlayed = onPlayed,
                        )
                    }
                }
            }
        }
    }
}

// ====================================================================== LIBRARY

@Composable
private fun TvLibraryTile(icon: ImageVector, label: String, onClick: () -> Unit) {
    val s = TvUi.scale()
    Row(
        Modifier
            .height(60.dp * s)
            .tvMusicFocus(RoundedCornerShape(16.dp), onClick = onClick)
            .background(Color.White.copy(alpha = 0.12f))
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp * s))
        Spacer(Modifier.width(12.dp))
        Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp * s, maxLines = 1)
    }
}

@UnstableApi
@Composable
fun TvMusicLibrary(
    startPad: Dp,
    tabs: @Composable () -> Unit,
    onOpen: (MRoute) -> Unit,
    onPlayed: () -> Unit,
) {
    val ctx = LocalContext.current
    val s = TvUi.scale()
    val liked by MusicStore.liked.collectAsState()
    val playlists by MusicStore.playlists.collectAsState()
    val saved by MusicStore.saved.collectAsState()
    var tab by rememberSaveable { mutableStateOf(0) }
    var creating by remember { mutableStateOf(false) }
    val tabNames = listOf("Playlists", "Songs", "Albums", "Artists")
    val tiles: List<Triple<ImageVector, String, String>> = listOf(
        Triple(Icons.Filled.Favorite, "Liked", "liked"),
        Triple(Icons.Filled.History, "Recently played", "history"),
        Triple(Icons.Filled.TrendingUp, "My top 50", "top"),
        Triple(Icons.Filled.TrendingDown, "My bottom 50", "bottom"),
    )
    val cardWidth = 166.dp * s
    val fullSpan: LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }

    Column(Modifier.fillMaxSize().padding(top = 20.dp)) {
        Box(Modifier.padding(start = startPad)) { tabs() }
        Spacer(Modifier.height(14.dp))
        LazyRow(
            contentPadding = PaddingValues(start = startPad, end = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(tabNames) { i, t -> TvMusicPill(t, selected = tab == i) { tab = i } }
        }
        Spacer(Modifier.height(12.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(cardWidth),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = maxOf(startPad - 8.dp, 0.dp), end = 32.dp, bottom = 120.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            when (tab) {
                0 -> {
                    item(span = fullSpan) {
                        LazyRow(
                            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(tiles) { t ->
                                TvLibraryTile(t.first, t.second) { onOpen(MRoute.Local(t.third, t.second)) }
                            }
                            item { TvLibraryTile(Icons.Filled.Add, "New playlist") { creating = true } }
                        }
                    }
                    item(span = fullSpan) { TvMusicSectionTitle("Playlists", Modifier.padding(start = 8.dp)) }
                    if (playlists.isEmpty()) {
                        item(span = fullSpan) {
                            Text(
                                "No playlists yet. Choose New playlist to make one.",
                                color = TvMusicMuted,
                                fontSize = 16.sp * s,
                                modifier = Modifier.padding(start = 8.dp, top = 4.dp),
                            )
                        }
                    }
                    items(playlists) { pl ->
                        TvMusicCard(
                            thumb = pl.songs.firstOrNull()?.thumb.orEmpty(),
                            title = pl.name,
                            subtitle = pl.songs.size.toString() + " songs",
                            circle = false,
                            width = cardWidth,
                        ) { onOpen(MRoute.Local("pl:" + pl.id, pl.name)) }
                    }
                }
                1 -> {
                    if (liked.isEmpty()) {
                        item(span = fullSpan) {
                            Text(
                                "Songs you like (the heart in the player or the ⋮ menu) show up here.",
                                color = TvMusicMuted,
                                fontSize = 16.sp * s,
                                modifier = Modifier.padding(start = 8.dp, top = 8.dp),
                            )
                        }
                    }
                    itemsIndexed(liked, span = { _, _ -> GridItemSpan(maxLineSpan) }) { i, song ->
                        TvMusicSongRow(song, Modifier.padding(start = 8.dp), index = i) {
                            MusicPlayer.playQueue(ctx, liked, i)
                            onPlayed()
                        }
                    }
                }
                else -> {
                    val want = if (tab == 2) setOf(MKind.ALBUM, MKind.PLAYLIST) else setOf(MKind.ARTIST)
                    val list = saved.filter { it.kind in want }
                    if (list.isEmpty()) {
                        item(span = fullSpan) {
                            Text(
                                "Choose Save on an album, playlist or artist page to keep it here.",
                                color = TvMusicMuted,
                                fontSize = 16.sp * s,
                                modifier = Modifier.padding(start = 8.dp, top = 8.dp),
                            )
                        }
                    }
                    items(list) { saved1 ->
                        TvMusicItemCard(saved1, cardWidth) {
                            onOpen(MRoute.Remote(saved1.kind, saved1.id, saved1.title, saved1.thumb))
                        }
                    }
                }
            }
        }
    }
    if (creating) {
        MusicNameDialog("New playlist", "", "Create", onDismiss = { creating = false }) { name ->
            MusicStore.createPlaylist(name)
        }
    }
}

// ====================================================================== DETAIL PAGES

/** Cover, name and the Play / Shuffle buttons on the left; the songs on the right. */
@UnstableApi
@Composable
private fun TvMusicDetailLayout(
    startPad: Dp,
    title: String,
    subtitle: String,
    thumb: String,
    circle: Boolean,
    songs: List<MSong>,
    onPlayed: () -> Unit,
    actions: @Composable () -> Unit,
    content: LazyListScope.() -> Unit,
) {
    val ctx = LocalContext.current
    val s = TvUi.scale()
    Row(Modifier.fillMaxSize().padding(top = 28.dp)) {
        Column(
            Modifier
                .padding(start = startPad)
                .width(280.dp * s)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MusicCover(
                thumb,
                Modifier.size(240.dp * s),
                if (circle) CircleShape else RoundedCornerShape(18.dp),
                700,
            )
            Text(
                title,
                color = Color.White,
                fontSize = 28.sp * s,
                fontWeight = FontWeight.Bold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    color = TvMusicMuted,
                    fontSize = 15.sp * s,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (songs.isNotEmpty()) {
                TvMusicPill("Play", selected = true, icon = Icons.Filled.PlayArrow, modifier = Modifier.fillMaxWidth()) {
                    MusicPlayer.playQueue(ctx, songs, 0)
                    onPlayed()
                }
                TvMusicPill("Shuffle", icon = Icons.Filled.Shuffle, modifier = Modifier.fillMaxWidth()) {
                    MusicPlayer.playQueue(ctx, songs, songs.indices.random(), shuffle = true)
                    onPlayed()
                }
            }
            actions()
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            contentPadding = PaddingValues(start = 28.dp, end = 32.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = content,
        )
    }
}

@UnstableApi
@Composable
fun TvMusicDetail(route: MRoute.Remote, startPad: Dp, onOpen: (MItem) -> Unit, onPlayed: () -> Unit) {
    val ctx = LocalContext.current
    val s = TvUi.scale()
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
    TvMusicDetailLayout(
        startPad = startPad,
        title = d?.title ?: route.title,
        subtitle = d?.subtitle.orEmpty(),
        thumb = d?.thumb ?: route.thumb,
        circle = route.kind == MKind.ARTIST,
        songs = d?.songs.orEmpty(),
        onPlayed = onPlayed,
        actions = {
            TvMusicPill(
                if (isSaved) "Saved" else "Save",
                icon = if (isSaved) Icons.Filled.Check else Icons.Filled.Add,
                modifier = Modifier.fillMaxWidth(),
            ) {
                MusicStore.toggleSaved(
                    MItem(
                        route.kind,
                        route.id,
                        d?.title ?: route.title,
                        d?.subtitle.orEmpty(),
                        d?.thumb ?: route.thumb,
                    ),
                )
            }
        },
    ) {
        val err = error
        if (d == null && err == null) {
            item { Text("Loading…", color = TvMusicMuted, fontSize = 18.sp * s) }
        }
        if (err != null) {
            item { Text(err, color = TvMusicError, fontSize = 16.sp * s) }
        }
        if (d != null) {
            itemsIndexed(d.songs, key = { i, song -> i.toString() + song.id }) { i, song ->
                TvMusicSongRow(song, index = i) {
                    MusicPlayer.playQueue(ctx, d.songs, i)
                    onPlayed()
                }
            }
            d.shelves.forEachIndexed { idx, shelf ->
                item(key = "tv_detail_shelf_$idx") {
                    TvMusicShelf(shelf.title, shelf.items, 8.dp, onOpen, onFocused = {}, onPlayed = onPlayed)
                }
            }
        }
    }
}

@UnstableApi
@Composable
fun TvMusicLocal(route: MRoute.Local, startPad: Dp, onBack: () -> Unit, onPlayed: () -> Unit) {
    val ctx = LocalContext.current
    val s = TvUi.scale()
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
    TvMusicDetailLayout(
        startPad = startPad,
        title = playlist?.name ?: route.title,
        subtitle = songs.size.toString() + " songs",
        thumb = songs.firstOrNull()?.thumb.orEmpty(),
        circle = false,
        songs = songs,
        onPlayed = onPlayed,
        actions = {
            if (playlistId != null) {
                TvMusicPill("Rename", icon = Icons.Filled.Edit, modifier = Modifier.fillMaxWidth()) { rename = true }
                TvMusicPill("Delete", icon = Icons.Filled.Delete, modifier = Modifier.fillMaxWidth()) {
                    confirmDelete = true
                }
            }
            if (key == "history") {
                TvMusicPill("Clear history", icon = Icons.Filled.Delete, modifier = Modifier.fillMaxWidth()) {
                    MusicStore.clearHistory()
                }
            }
        },
    ) {
        if (songs.isEmpty()) {
            item { Text("Nothing here yet.", color = TvMusicMuted, fontSize = 18.sp * s) }
        }
        itemsIndexed(songs, key = { i, song -> i.toString() + song.id }) { i, song ->
            TvMusicSongRow(song, index = i, playlistId = playlistId) {
                MusicPlayer.playQueue(ctx, songs, i)
                onPlayed()
            }
        }
    }
    if (rename && playlist != null) {
        MusicNameDialog("Rename playlist", playlist.name, "Save", onDismiss = { rename = false }) { name ->
            MusicStore.renamePlaylist(playlist.id, name)
        }
    }
    if (confirmDelete && playlistId != null) {
        AlertDialog(
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
