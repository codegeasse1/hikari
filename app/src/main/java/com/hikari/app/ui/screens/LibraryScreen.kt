package com.hikari.app.ui.screens
import com.hikari.app.tv.TvUi
import com.hikari.app.i18n.tr
import com.hikari.app.i18n.I18n

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.hikari.app.HikariApp
import com.hikari.app.data.LibraryCategory
import com.hikari.app.data.MediaType
import com.hikari.app.data.MediaItem
import com.hikari.app.data.TrackerLibraryRepository
import com.hikari.app.ui.Artwork
import com.hikari.app.ui.PosterArt
import com.hikari.app.ui.PosterStyle
import com.hikari.app.ui.components.CategoryManagerSheet
import com.hikari.app.ui.components.CategoryPickerSheet
import com.hikari.app.ui.components.EmptyState
import com.hikari.app.ui.navigation.LocalTaskbarInset
import com.hikari.app.ui.navigation.Routes
import com.hikari.app.ui.rememberPosterScore
import com.hikari.app.ui.rememberPosterStyle
import com.hikari.app.ui.shape
import com.hikari.app.ui.rememberVisibleItems
import kotlinx.coroutines.launch

@Composable
fun LibraryScreen(nav: NavHostController, embedded: Boolean = false) {
    val context = LocalContext.current
    val app = context.applicationContext as HikariApp
    val scope = rememberCoroutineScope()

    val favoritesFlow = remember { app.store.favoritesFlow() }
    val favorites by favoritesFlow.collectAsState(initial = emptyList())
    // The store appends a newly-saved title at the END of the list, so the
    // newest is first here (and legacy duplicates collapse to one card).
    val saved = remember(favorites) { favorites.asReversed().distinctBy { it.uniqueId } }

    val categoriesFlow = remember { app.store.libraryCategoriesFlow() }
    val categories by categoriesFlow.collectAsState(initial = LibraryCategory.DEFAULTS)
    val filedFlow = remember { app.store.favoriteCategoriesFlow() }
    val filed by filedFlow.collectAsState(initial = emptyMap())

    var activeCategory by remember { mutableStateOf<String?>(null) }
    var activeType by remember { mutableStateOf<String?>(null) }
    var moving by remember { mutableStateOf<MediaItem?>(null) }
    var managing by remember { mutableStateOf(false) }
    val style = rememberPosterStyle()
    // Followed manga share this page (see the shelf below). Read through the
    // store's revision so following/unfollowing a title anywhere updates it.
    val mangaRev = rememberMangaRevision()
    val hasManga = remember(mangaRev) { com.hikari.app.manga.MangaStore.library().isNotEmpty() }

    // A category that was just deleted must not keep filtering the grid.
    LaunchedEffect(categories) {
        val id = activeCategory
        if (id != null && categories.none { it.id == id }) activeCategory = null
    }

    // Anime = Aniyomi extensions (no movie/series concept at catalog time);
    // Movies/Series = the saved video type; Manga lives in MangaStore on its
    // own shelf below, not in this grid.
    val typeOf: (MediaItem) -> String = { item ->
        when {
            item.providerId.startsWith("aniyomi|") || item.rawType == "aniyomi" || item.rawType == "anime" -> "anime"
            item.rawType == "manga" -> "manga"
            item.type == MediaType.MOVIE -> "movie"
            item.type == MediaType.SERIES -> "series"
            else -> "other"
        }
    }
    val mangaCount = remember(mangaRev) { com.hikari.app.manga.MangaStore.library().size }
    // Labels are read HERE (composable position): tr() cannot run inside a
    // LazyRow/LazyVerticalGrid item scope.
    val labelAll = tr("All")
    val labelAnime = tr("Anime")
    val labelMovies = tr("Movies")
    val labelSeries = tr("Series")
    val labelManga = tr("Manga")
    val shown = remember(saved, activeCategory, filed, activeType) {
        val id = activeCategory
        val base = if (id == null) saved else saved.filter { filed[it.uniqueId].orEmpty().contains(id) }
        when (activeType) {
            "anime", "movie", "series", "manga" -> base.filter { typeOf(it) == activeType }
            else -> base
        }
    }
    val trackerSource by app.store.trackerLibrarySourceFlow().collectAsState(initial = "nuvio")
    var remoteShelves by remember { mutableStateOf<List<TrackerLibraryRepository.Shelf>>(emptyList()) }
    var remoteError by remember { mutableStateOf<String?>(null) }
    var remoteLoading by remember { mutableStateOf(false) }
    // Tapping the tracker error retries the load in place — a transient
    // rate-limit no longer needs an app restart to clear.
    var trackerRetry by remember { mutableStateOf(0) }
    LaunchedEffect(trackerSource, trackerRetry) {
        if (trackerSource == "nuvio") {
            remoteShelves = emptyList()
            remoteError = null
        } else {
            remoteLoading = true
            val result = TrackerLibraryRepository.load(app)
            remoteShelves = result.getOrDefault(emptyList())
            remoteError = result.exceptionOrNull()?.message
            remoteLoading = false
        }
    }
    // Type pills filter BOTH the saved grid and the tracker shelves (an AniList
    // Watching shelf is anime, its Reading shelf is manga). Counts cover both.
    val trackerItems = remember(remoteShelves) { remoteShelves.flatMap { it.items } }
    val visibleShelves = remember(remoteShelves, activeType) {
        if (activeType == null) remoteShelves
        else remoteShelves.mapNotNull { shelf ->
            val items = shelf.items.filter { typeOf(it) == activeType }
            if (items.isEmpty()) null else shelf.copy(items = items)
        }
    }

    val visibleSaved = rememberVisibleItems(shown)

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = TvUi.gridMinFor(104)),
        // Library position held per category (see ScrollKeeper).
        state = com.hikari.app.ui.components.keptGridState("library:$activeCategory:$activeType"),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 16.dp,
            // Clear of the floating taskbar (LocalTaskbarInset is 0 on a page
            // with no bar).
            bottom = LocalTaskbarInset.current + 16.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "library-header", span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.padding(bottom = 2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        if (!embedded) {
                            Text(
                                tr("Library"),
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Text(
                            if (saved.isEmpty()) I18n.t("Titles you save from the player show up here.")
                            else I18n.t(if (saved.size == 1) "%s title saved" else "%s titles saved").replace("%s", saved.size.toString()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { managing = true }) {
                        Icon(
                            Icons.Filled.Category,
                            contentDescription = tr("Library categories"),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
        if (saved.isNotEmpty() || hasManga || remoteShelves.isNotEmpty()) {
            item(key = "library-type", span = { GridItemSpan(maxLineSpan) }) {
                LazyRow(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item(key = "type-all") {
                        LibraryChip(
                            label = labelAll,
                            count = saved.size + mangaCount + trackerItems.size,
                            selected = activeType == null,
                        ) { activeType = null }
                    }
                    val types = listOf(
                        Triple("anime", labelAnime, saved.count { typeOf(it) == "anime" } + trackerItems.count { typeOf(it) == "anime" }),
                        Triple("movie", labelMovies, saved.count { typeOf(it) == "movie" } + trackerItems.count { typeOf(it) == "movie" }),
                        Triple("series", labelSeries, saved.count { typeOf(it) == "series" } + trackerItems.count { typeOf(it) == "series" }),
                        Triple("manga", labelManga, mangaCount + trackerItems.count { typeOf(it) == "manga" }),
                    )
                    items(types, key = { ti -> "type-" + ti.first }) { t ->
                        LibraryChip(
                            label = t.second,
                            count = t.third,
                            selected = activeType == t.first,
                        ) { activeType = if (activeType == t.first) null else t.first }
                    }
                }
            }
        }
        if (trackerSource != "nuvio") {
            item(key = "tracker-library-header", span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(top = 4.dp, bottom = 2.dp)) {
                    Text(tr("Tracker library"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(tr("Watchlists and personal lists from your connected tracker"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (remoteLoading) Text(tr("Loading…"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    else if (!remoteError.isNullOrBlank()) Text(
                        remoteError.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.clickable { trackerRetry++ },
                    )
                }
            }
            visibleShelves.forEachIndexed { shelfIndex, shelf ->
                item(key = "tracker-shelf-${shelf.key}-$shelfIndex", span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                shelf.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                shelf.items.size.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(4.dp))
                            TextButton(onClick = {
                                Routes.safeNavigate(
                                    nav,
                                    Routes.trackerShelf(trackerSource, shelf.key, shelf.title, activeType.orEmpty()),
                                )
                            }) {
                                Text(tr("Show all"))
                            }
                        }
                        LazyRow(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(shelf.items.take(12), key = { it.uniqueId }) { item ->
                                TrackerLibraryCard(item = item, onClick = { openTrackerItem(nav, item) })
                            }
                            if (shelf.items.size > 12) {
                                item(key = "${shelf.key}-showall") {
                                    ShowAllTile(
                                        remaining = shelf.items.size - 12,
                                        onClick = {
                                            Routes.safeNavigate(
                                                nav,
                                                Routes.trackerShelf(trackerSource, shelf.key, shelf.title, activeType.orEmpty()),
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (hasManga && (activeType == null || activeType == "manga")) {
            item(key = "library-manga", span = { GridItemSpan(maxLineSpan) }) {
                MangaLibraryShelf(nav)
            }
        }
        if (saved.isNotEmpty()) {
            item(key = "library-chips", span = { GridItemSpan(maxLineSpan) }) {
                LazyRow(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item(key = "chip-all") {
                        LibraryChip(
                            label = tr("All"),
                            count = saved.size,
                            selected = activeCategory == null,
                        ) { activeCategory = null }
                    }
                    items(categories, key = { "chip-${it.id}" }) { c ->
                        LibraryChip(
                            label = c.name,
                            count = saved.count { filed[it.uniqueId].orEmpty().contains(c.id) },
                            selected = activeCategory == c.id,
                        ) { activeCategory = if (activeCategory == c.id) null else c.id }
                    }
                }
            }
        }
        if (activeType == "manga" && !hasManga) {
            item(key = "library-manga-empty", span = { GridItemSpan(maxLineSpan) }) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        tr("No manga followed yet."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else if (saved.isEmpty()) {
            item(key = "library-empty", span = { GridItemSpan(maxLineSpan) }) {
                EmptyState(
                    title = tr("Your library is empty"),
                    subtitle = tr("Tap the heart in the player while watching a movie or series " + "and it will be saved here for later."),
                    actionLabel = tr("Browse"),
                    action = { Routes.navigateTab(nav, Routes.HOME) },
                )
            }
        } else if (visibleSaved.isEmpty()) {
            item(key = "library-none", span = { GridItemSpan(maxLineSpan) }) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        tr("Nothing filed under this category yet."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            items(visibleSaved, key = { it.uniqueId }) { item ->
                LibraryCard(
                    item = item,
                    style = style,
                    categories = categories.filter { it.id in filed[item.uniqueId].orEmpty() },
                    onClick = {
                        Routes.safeNavigate(
                            nav,
                            Routes.detail(
                                providerId = item.providerId,
                                type = item.type,
                                mediaId = item.id,
                                title = item.title,
                                posterUrl = item.posterUrl,
                                rawType = item.rawType,
                            )
                        )
                    },
                    onMove = { moving = item },
                    onRemove = { scope.launch { app.store.removeFavorite(item.uniqueId) } },
                )
            }
        }
    }

    val movingItem = moving
    if (movingItem != null) {
        CategoryPickerSheet(
            title = tr("Move to"),
            subtitle = movingItem.title,
            categories = categories,
            selected = filed[movingItem.uniqueId].orEmpty(),
            confirmLabel = tr("Done"),
            onConfirm = { picked ->
                scope.launch { app.store.setFavoriteCategories(movingItem.uniqueId, picked) }
                moving = null
            },
            onCreateCategory = { name ->
                val c = app.store.addLibraryCategory(name)
                app.store.addFavoriteCategories(movingItem.uniqueId, setOf(c.id))
                c
            },
            onDismiss = { moving = null },
        )
    }

    if (managing) {
        CategoryManagerSheet(
            categories = categories,
            onRename = { id, name -> scope.launch { app.store.renameLibraryCategory(id, name) } },
            onDelete = { id -> scope.launch { app.store.removeLibraryCategory(id) } },
            onCreate = { name -> scope.launch { app.store.addLibraryCategory(name) } },
                onMove = { id, delta -> scope.launch { app.store.moveLibraryCategory(id, delta) } },
            onDismiss = { managing = false },
        )
    }
}

/** One filter pill above the grid. */
@Composable
private fun LibraryChip(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (count > 0) {
                Spacer(Modifier.width(6.dp))
                Text(
                    count.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LibraryCard(
    item: MediaItem,
    style: PosterStyle,
    categories: List<LibraryCategory>,
    onClick: () -> Unit,
    onMove: () -> Unit,
    onRemove: () -> Unit,
) {
    val badge = rememberPosterScore(item, style)
    Column(
        Modifier
            .clickable(onClick = onClick)
    ) {
        PosterArt(
            model = if (com.hikari.app.tv.TvMode.current()) Artwork.backdropModel(item) else Artwork.model(item),
            contentDescription = item.title,
            style = style,
            rating = item.rating,
            imdb = badge,
            item = item,
            // The top-right corner is this card's own remove button and the
            // bottom-right its "Move to", so the score takes the free corner.
            ratingAlignment = Alignment.BottomStart,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(if (com.hikari.app.tv.TvMode.current()) 16f / 9f else 2f / 3f),
        ) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(28.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.62f))
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Favorite,
                    contentDescription = tr("Remove from library"),
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
            }
            // "Move to": the same picker the detail page uses, pre-ticked with
            // where the title is filed right now.
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .size(28.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.62f))
                    .clickable(onClick = onMove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.LibraryAdd,
                    contentDescription = tr("Move to"),
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        if (style.showTitles) {
            Text(
                item.title,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (com.hikari.app.tv.TvMode.current()) com.hikari.app.tv.TvDesktop.TextPrimary
                else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp)
            )
            if (categories.isNotEmpty()) {
                Text(
                    categories.joinToString(" · ") { it.name },
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 2.dp, start = 2.dp, end = 2.dp)
                )
            }
        }
    }
}


@Composable
private fun TrackerLibraryCard(item: MediaItem, onClick: () -> Unit) {
    val style = rememberPosterStyle()
    val tvLib = com.hikari.app.tv.TvMode.current()
    Column(modifier = Modifier.width(if (tvLib) 280.dp else 112.dp).clickable(onClick = onClick)) {
        PosterArt(
            model = if (com.hikari.app.tv.TvMode.current()) Artwork.backdropModel(item) else Artwork.model(item),
            contentDescription = item.title,
            style = style,
            modifier = Modifier.fillMaxWidth().aspectRatio(if (tvLib) 16f / 9f else 0.67f)
                .clip(RoundedCornerShape(if (tvLib) 8.dp else 12.dp)),
        )
        Text(item.title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium,
            color = if (tvLib) com.hikari.app.tv.TvDesktop.TextPrimary else Color.Unspecified,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
    }
}

/** One tracker row's door: manga rows search the manga engines, everything else opens the video detail. */
private fun openTrackerItem(nav: NavHostController, item: MediaItem) {
    if (item.rawType == "manga") {
        Routes.safeNavigate(nav, Routes.mangaSearch(item.title))
    } else {
        Routes.safeNavigate(
            nav,
            Routes.detail(
                providerId = item.providerId, type = item.type, mediaId = item.id,
                title = item.title, posterUrl = item.posterUrl, rawType = item.rawType,
            ),
        )
    }
}

/** Trailing tile of a tracker row: opens the whole shelf as a catalogue grid. */
@Composable
private fun ShowAllTile(remaining: Int, onClick: () -> Unit) {
    val tvLib = com.hikari.app.tv.TvMode.current()
    Column(modifier = Modifier.width(if (tvLib) 280.dp else 112.dp)) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(if (tvLib) 8.dp else 12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.fillMaxWidth().aspectRatio(if (tvLib) 16f / 9f else 0.67f),
        ) {
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    tr("Show all"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    if (remaining > 0) "+$remaining" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            tr("Show all"),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
fun TrackerShelfScreen(nav: NavHostController, source: String, shelfKey: String, title: String, filter: String = "") {
    val context = LocalContext.current
    val app = context.applicationContext as HikariApp
    val style = rememberPosterStyle()
    val tvLib = com.hikari.app.tv.TvMode.current()
    var items by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    // Same tap-to-retry as the library header: a transient tracker failure
    // reloads in place instead of needing a reopen.
    var shelfRetry by remember { mutableStateOf(0) }
    // tr() is @Composable: read every string HERE, never inside LaunchedEffect.
    val shelfNotFound = tr("Shelf not found.")
    // Instant find inside a big shelf (80+ synced manga): narrows the grid
    // as you type, so the title is one word away instead of a long scroll.
    var query by remember { mutableStateOf("") }
    val searchPlaceholder = tr("Search this shelf…")
    LaunchedEffect(source, shelfKey, shelfRetry) {
        loading = true
        error = null
        val result = TrackerLibraryRepository.load(app, source)
        val shelf = result.getOrNull()?.firstOrNull { it.key == shelfKey }
        val all = shelf?.items.orEmpty()
        items = if (filter.isBlank()) all else all.filter {
            when (filter) {
                "anime" -> it.providerId.startsWith("aniyomi|") || it.rawType == "aniyomi" || it.rawType == "anime"
                "manga" -> it.rawType == "manga"
                "movie" -> it.type == MediaType.MOVIE
                "series" -> it.type == MediaType.SERIES
                else -> true
            }
        }
        if (result.isFailure && shelf == null) error = result.exceptionOrNull()?.message
        else if (shelf == null) error = shelfNotFound
        loading = false
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { nav.popBackStack() }) {
                Icon(
                    Icons.Filled.ArrowBack,
                    contentDescription = tr("Back"),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    if (title.isNotBlank()) title else tr("Tracker library"),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (items.size == 1) I18n.t("%s title").replace("%s", "1")
                    else I18n.t("%s titles").replace("%s", items.size.toString()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            placeholder = {
                Text(
                    searchPlaceholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            leadingIcon = {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            },
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )
        val shown = if (query.isBlank()) items
        else items.filter { it.title.contains(query, ignoreCase = true) }
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(tr("Loading…"), style = MaterialTheme.typography.bodyMedium)
            }
            !error.isNullOrBlank() -> Box(
                Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    error.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.clickable { shelfRetry++ },
                )
            }
            items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(tr("Nothing here."), style = MaterialTheme.typography.bodyMedium)
            }
            shown.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    I18n.t("No title matches \"%s\".").replace("%s", query),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp, top = 8.dp,
                    bottom = LocalTaskbarInset.current + 16.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(shown, key = { it.uniqueId }) { item ->
                    Column(Modifier.clickable(onClick = { openTrackerItem(nav, item) })) {
                        PosterArt(
                            model = if (tvLib) Artwork.backdropModel(item) else Artwork.model(item),
                            contentDescription = item.title,
                            style = style,
                            modifier = Modifier.fillMaxWidth()
                                .aspectRatio(if (tvLib) 16f / 9f else 0.67f)
                                .clip(RoundedCornerShape(if (tvLib) 8.dp else 12.dp)),
                        )
                        Text(
                            item.title,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = if (tvLib) com.hikari.app.tv.TvDesktop.TextPrimary else Color.Unspecified,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
    }
}
