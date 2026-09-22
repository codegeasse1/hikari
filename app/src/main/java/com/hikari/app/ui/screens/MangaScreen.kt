package com.hikari.app.ui.screens

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
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.hikari.app.HikariApp
import com.hikari.app.data.MediaItem
import com.hikari.app.data.MediaType
import com.hikari.app.data.ProviderConfig
import com.hikari.app.i18n.tr
import com.hikari.app.manga.MangaProvider
import com.hikari.app.manga.MangaProgress
import com.hikari.app.manga.MangaRecord
import com.hikari.app.manga.MangaStore
import com.hikari.app.tv.TvUi
import com.hikari.app.ui.ExtensionIcons
import com.hikari.app.ui.PosterLoader
import com.hikari.app.ui.components.EmptyState
import com.hikari.app.ui.components.GlassSearchField
import com.hikari.app.ui.navigation.LocalTaskbarInset
import com.hikari.app.ui.navigation.Routes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The Manga tab.
 *
 * Everything here is one screen deep: the followed titles and what you were
 * reading, one card per installed manga engine to browse (its Popular and
 * Latest lists — the two catalogs every Mihon/keiyoushi source publishes), and a
 * search box that asks every engine at once. Tapping a title opens
 * [MangaDetailScreen]; tapping a "continue reading" card goes straight back into
 * [MangaReaderScreen] at the page you stopped on.
 *
 * The engine list is discovered, not configured: a manga engine is an ordinary
 * provider of [com.hikari.app.data.ProviderType.MANGA], installed through the
 * very same Extensions screen as an Aniyomi extension (the two are the same
 * file format), so this screen shows whatever is installed the moment it is
 * installed. With nothing installed the tab explains how to get one.
 *
 * Like IPTV, the tab's button is OFF by default — see Settings → Taskbar
 * buttons, and [com.hikari.app.data.AppStore.mangaTabFlow].
 */
@Composable
fun MangaScreen(nav: NavHostController) {
    val context = LocalContext.current
    val app = context.applicationContext as HikariApp
    val all by app.providers.providers.collectAsState()
    // One provider per SOURCE the engine publishes (an extension usually has
    // one), which is exactly the granularity a reader wants: each is one site.
    val engines = remember(all) { all.filterIsInstance<MangaProvider>() }
    val engineKey = remember(engines) { engines.joinToString(",") { it.config.id } }

    val rev = rememberMangaRevision()
    val library = remember(rev) { MangaStore.library() }
    val progress = remember(rev) { MangaStore.progress() }

    var query by rememberSaveable { mutableStateOf("") }
    // Which engine's own lists to browse / search: "" means every installed one.
    var enginePick by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val typed = query.trim()

    // Debounced cross-engine search. Runs on the text itself rather than on a
    // keyboard action because the field is a plain BasicTextField (the app's
    // shared glass search box) with no IME action to hook.
    LaunchedEffect(typed, enginePick, engineKey) {
        if (typed.isBlank()) {
            results = emptyList()
            searching = false
            return@LaunchedEffect
        }
        delay(450)
        val targets = engines.filter { enginePick.isBlank() || it.config.id == enginePick }
        if (targets.isEmpty()) {
            results = emptyList()
            searching = false
            return@LaunchedEffect
        }
        searching = true
        // Every engine at once — each one is a separate site and they answer in
        // parallel; the app's own gate keeps a big install from flooding the
        // network (see ProviderGate). One engine throwing must not empty the
        // whole result list, so each call is caught on its own.
        val found = coroutineScope {
            targets.map { p ->
                async { runCatching { p.search(typed, 1) }.getOrDefault(emptyList()) }
            }.awaitAll().flatten()
        }
        results = found.distinctBy { it.uniqueId }
        searching = false
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = TvUi.gridMinFor(104)),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 16.dp,
            bottom = LocalTaskbarInset.current + 16.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "manga-header", span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Text(
                    tr("Manga"),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    if (engines.isEmpty()) tr("No manga engines installed yet.")
                    else if (progress.isEmpty() && library.isEmpty())
                        tr("Follow a title to keep it here, and pick up where you left off.")
                    else tr("Continue reading, your library, and your engines' own lists."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Spacer(Modifier.height(12.dp))
                GlassSearchField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = tr("Search every manga engine"),
                )
            }
        }

        if (engines.isEmpty()) {
            item(key = "manga-no-engines", span = { GridItemSpan(maxLineSpan) }) {
                EmptyState(
                    title = tr("No manga engines yet"),
                    subtitle = tr(
                        "Add a manga repo in Extensions (the Keiyoushi index works out of " +
                            "the box) and its extensions install like any other — then " +
                            "their titles show up here."
                    ),
                    actionLabel = tr("Open Extensions"),
                    action = { Routes.navigateTab(nav, Routes.EXTENSIONS) },
                )
            }
            return@LazyVerticalGrid
        }

        if (typed.isNotBlank()) {
            item(key = "manga-engines", span = { GridItemSpan(maxLineSpan) }) {
                EngineChips(
                    engines = engines.map { it.config.id to it.config.name },
                    picked = enginePick,
                    onPick = { enginePick = it },
                )
            }
            if (searching && results.isEmpty()) {
                item(key = "manga-searching", span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(Modifier.size(28.dp))
                            Spacer(Modifier.height(10.dp))
                            Text(
                                tr("Asking your engines…"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else if (results.isEmpty()) {
                item(key = "manga-no-results", span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            tr("Nothing found — the site may be blocking or down."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                items(results, key = { "res-" + it.uniqueId }) { item ->
                    MangaPosterCard(
                        posterUrl = item.posterUrl,
                        title = item.title,
                        subtitle = engineName(engines, item.providerId),
                        onClick = {
                            Routes.safeNavigate(
                                nav,
                                Routes.mangaDetail(item.providerId, item.id, item.title, item.posterUrl),
                            )
                        },
                    )
                }
            }
            return@LazyVerticalGrid
        }

        if (progress.isNotEmpty()) {
            item(key = "manga-continue", span = { GridItemSpan(maxLineSpan) }) {
                MangaSection(
                    title = tr("Continue reading"),
                    count = progress.size,
                ) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(progress, key = { "prog-" + it.mangaKey }) { p ->
                            ContinueCard(p) {
                                Routes.safeNavigate(
                                    nav,
                                    Routes.mangaReader(
                                        p.providerId, p.mangaUrl, p.chapterUrl, p.title, p.posterUrl,
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        if (library.isNotEmpty()) {
            item(key = "manga-lib-head", span = { GridItemSpan(maxLineSpan) }) {
                MangaSection(
                    title = tr("In your library"),
                    count = library.size,
                ) { Spacer(Modifier.height(4.dp)) }
            }
            items(library, key = { "lib-" + it.key }) { rec ->
                MangaPosterCard(
                    posterUrl = rec.posterUrl,
                    title = rec.title,
                    subtitle = rec.providerName,
                    badge = {
                        val p = MangaStore.progressFor(rec.key)
                        if (p != null && p.pages > 0) {
                            Text(
                                p.pageLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                    onClick = {
                        Routes.safeNavigate(
                            nav,
                            Routes.mangaDetail(rec.providerId, rec.url, rec.title, rec.posterUrl),
                        )
                    },
                )
            }
        }

        item(key = "manga-browse", span = { GridItemSpan(maxLineSpan) }) {
            MangaSection(title = tr("Browse"), count = engines.size) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    engines.forEach { p -> EngineRow(p, nav) }
                }
            }
        }
    }
}

/** One engine's card: its icon and name, plus a shortcut into each of the two
 *  lists every manga source publishes. */
@Composable
private fun EngineRow(engine: MangaProvider, nav: NavHostController) {
    val providerId = engine.config.id
    val name = engine.config.name
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MangaIcon(engine.config, size = 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // The labels are read HERE (a composable position) and passed
                    // into the click lambdas: `tr` is itself composable, so it
                    // cannot be called from inside an onClick.
                    val popular = tr("Popular")
                    val latest = tr("Latest")
                    EngineAction(popular) {
                        openCatalog(nav, providerId, name, MangaProvider.CATALOG_POPULAR, popular)
                    }
                    EngineAction(latest) {
                        openCatalog(nav, providerId, name, MangaProvider.CATALOG_LATEST, latest)
                    }
                }
            }
        }
    }
}

private fun openCatalog(
    nav: NavHostController,
    providerId: String,
    providerName: String,
    catalogId: String,
    title: String,
) {
    // rawType "manga" is what makes CatalogScreen open the manga detail page for
    // its items instead of the video one (see CatalogScreen). The engine itself
    // tags its catalogs that way; here it is passed explicitly because this
    // route is built by hand.
    Routes.safeNavigate(
        nav,
        Routes.catalog(
            providerId = providerId,
            catalogId = catalogId,
            title = title,
            providerName = providerName,
            type = MediaType.SERIES,
            rawType = "manga",
        ),
    )
}

@Composable
private fun EngineAction(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** The "All / <engine> / <engine>" row that scopes a search. */
@Composable
private fun EngineChips(
    engines: List<Pair<String, String>>,
    picked: String,
    onPick: (String) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "chip-all") {
            EngineChip(tr("All"), picked.isEmpty()) { onPick("") }
        }
        items(engines, key = { it.first }) { (id, name) ->
            EngineChip(name, picked == id) { onPick(if (picked == id) "" else id) }
        }
    }
}

@Composable
private fun EngineChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

/** A shelf heading — the title, the count, and whatever the shelf draws. */
@Composable
private fun MangaSection(
    title: String,
    count: Int,
    content: @Composable () -> Unit,
) {
    Column(Modifier.padding(top = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        content()
    }
}

/** One "continue reading" card: the cover, the chapter, and the page pair. */
@Composable
private fun ContinueCard(progress: MangaProgress, onClick: () -> Unit) {
    Column(
        Modifier
            .width(116.dp)
            .clickable(onClick = onClick)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            MangaFallback(modifier = Modifier.size(28.dp))
            AsyncImage(
                model = PosterLoader.model(progress.posterUrl),
                contentDescription = progress.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.66f)),
            ) {
                Row(
                    Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(
                        progress.pageLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        maxLines = 1,
                    )
                }
            }
        }
        Text(
            progress.title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
        )
        Text(
            progress.chapterName,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 2.dp, end = 2.dp),
        )
    }
}

/** A cover in a grid cell: artwork, title, engine, and an optional trailing
 *  line (the reading position, on a library card). */
@Composable
private fun MangaPosterCard(
    posterUrl: String?,
    title: String,
    subtitle: String,
    badge: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Column(Modifier.clickable(onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            MangaFallback(modifier = Modifier.size(28.dp))
            AsyncImage(
                model = PosterLoader.model(posterUrl),
                contentDescription = title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            if (trailing != null) {
                Box(Modifier.align(Alignment.TopEnd).padding(4.dp)) { trailing() }
            }
        }
        Text(
            title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
        )
        badge?.invoke()
        if (subtitle.isNotBlank()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 2.dp, end = 2.dp),
            )
        }
    }
}

/** The glyph behind a cover that has not loaded (or never will). */
@Composable
private fun MangaFallback(modifier: Modifier = Modifier) {
    Icon(
        Icons.Filled.AutoStories,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.40f),
        modifier = modifier,
    )
}

/** An engine's own icon, resolved lazily (a manga extension ships no artwork —
 *  the site's favicon is its icon; see MangaExtensionManager.iconFallback, which
 *  is blocking and cached, hence the IO dispatch). */
@Composable
private fun MangaIcon(config: ProviderConfig, size: Dp) {
    var icon by remember(config.id) { mutableStateOf(config.iconUrl) }
    LaunchedEffect(config.id, config.iconUrl) {
        if (icon.isNullOrBlank()) {
            icon = withContext(Dispatchers.IO) {
                runCatching { ExtensionIcons.forConfig(config) }.getOrNull()
            }
        }
    }
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        MangaFallback(modifier = Modifier.size(size / 2))
        if (!icon.isNullOrBlank()) {
            AsyncImage(
                model = PosterLoader.model(icon),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(10.dp)),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

private fun engineName(engines: List<MangaProvider>, providerId: String): String =
    engines.firstOrNull { it.config.id == providerId }?.config?.name.orEmpty()

/**
 * Re-reads the manga store whenever it changes (a follow, a page, a chapter
 * list), so every screen showing manga redraws without a poll. Returns a
 * revision counter to key the reads on.
 *
 * `internal`, not private: the detail screen and the reader use it too — the
 * whole point is that ONE change anywhere repaints every manga surface.
 */
@Composable
internal fun rememberMangaRevision(): Int {
    val rev = remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val listener: () -> Unit = { rev.intValue = rev.intValue + 1 }
        MangaStore.addListener(listener)
        onDispose { MangaStore.removeListener(listener) }
    }
    return rev.intValue
}

/**
 * The "Continue reading" shelf on its own, for the My Stuff tab's History
 * section — the place the user already looks for "what was I doing". Draws
 * nothing at all (not even a heading) when no manga has been read.
 */
@Composable
fun MangaContinueShelf(nav: NavHostController) {
    val rev = rememberMangaRevision()
    val progress = remember(rev) { MangaStore.progress() }
    if (progress.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(
            tr("Manga — continue reading"),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(progress, key = { "my-prog-" + it.mangaKey }) { p ->
                ContinueCard(p) {
                    Routes.safeNavigate(
                        nav,
                        Routes.mangaReader(p.providerId, p.mangaUrl, p.chapterUrl, p.title, p.posterUrl),
                    )
                }
            }
        }
    }
}

/**
 * The followed manga, for the My Stuff tab's Library section — a manga belongs
 * in the library the user already browses, not only in the manga tab. Draws
 * nothing when no title is followed.
 */
@Composable
fun MangaLibraryShelf(nav: NavHostController) {
    val rev = rememberMangaRevision()
    val library = remember(rev) { MangaStore.library() }
    if (library.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        Text(
            tr("Manga"),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(library, key = { "my-lib-" + it.key }) { rec ->
                LibraryMangaCard(rec) {
                    Routes.safeNavigate(
                        nav,
                        Routes.mangaDetail(rec.providerId, rec.url, rec.title, rec.posterUrl),
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryMangaCard(rec: MangaRecord, onClick: () -> Unit) {
    // One read of the store per card (the JSON behind it is cached), rather than
    // one per field. With no progress yet the card simply states the engine.
    val p = MangaStore.progressFor(rec.key)
    ContinueCard(
        progress = MangaProgress(
            mangaKey = rec.key,
            providerId = rec.providerId,
            providerName = rec.providerName,
            mangaUrl = rec.url,
            title = rec.title,
            posterUrl = rec.posterUrl,
            chapterUrl = p?.chapterUrl.orEmpty(),
            chapterName = p?.chapterName ?: rec.providerName,
            page = p?.page ?: 0,
            pages = p?.pages ?: 0,
            at = p?.at ?: 0L,
        ),
        onClick = onClick,
    )
}
