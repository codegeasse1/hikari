package com.hikari.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.hikari.app.HikariApp
import com.hikari.app.data.MediaItem
import com.hikari.app.data.MangaMetadataRepository
import com.hikari.app.data.MediaType
import com.hikari.app.i18n.I18n
import com.hikari.app.i18n.tr
import com.hikari.app.manga.MangaChapter
import com.hikari.app.manga.MangaMark
import com.hikari.app.manga.MangaProvider
import com.hikari.app.manga.MangaRecord
import com.hikari.app.manga.MangaStore
import com.hikari.app.ui.PosterLoader
import com.hikari.app.ui.components.VerificationNudge
import com.hikari.app.ui.navigation.LocalTaskbarInset
import com.hikari.app.ui.navigation.Routes
import com.hikari.app.web.WebViewActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MangaDetailScreen(
    nav: NavHostController,
    providerId: String,
    mangaUrl: String,
    title: String,
    posterUrl: String,
) {
    val context = LocalContext.current
    val app = context.applicationContext as HikariApp
    val scope = rememberCoroutineScope()

    // The store key is provider + the source's own url — the same key the
    // chapter cache and the reading progress use.
    val key = "$providerId|$mangaUrl"

    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var meta by remember { mutableStateOf<MediaItem?>(null) }
    var chapters by remember { mutableStateOf<List<MangaChapter>>(MangaStore.chaptersFor(key).orEmpty()) }
    var followed by remember { mutableStateOf(MangaStore.isFollowed(key)) }
    var newestFirst by remember { mutableStateOf(true) }
    // Chapters | Comments tab below the characters row — tappable AND
    // swipable (a horizontal drag anywhere on the list flips it).
    var tab by remember { mutableIntStateOf(0) }
    var characters by remember { mutableStateOf<List<MangaMetadataRepository.MangaCharacter>>(emptyList()) }
    var threads by remember { mutableStateOf<List<MangaMetadataRepository.MangaThread>>(emptyList()) }
    var reviews by remember { mutableStateOf<List<MangaMetadataRepository.MangaReview>>(emptyList()) }
    var chapterHints by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    var extAnilistId by remember { mutableIntStateOf(0) }
    var commentsLoading by remember { mutableStateOf(true) }
    // Bumped by the refresh button (and by the follow toggle, which changes what
    // the header draws) so the load effect runs again on demand.
    var reload by remember { mutableIntStateOf(0) }
    var expanded by remember { mutableStateOf(false) }
    // Pending "open this title's site" question (see SiteOpenDialog): the
    // globe asks first, then opens the WebView or the device browser.
    var verifySiteAsk by remember { mutableStateOf<SiteAsk?>(null) }

    val rev = rememberMangaRevision()
    val progress = remember(rev) { MangaStore.progressFor(key) }
    val readMarks = remember(rev, chapters) {
        chapters.associate { it.url to MangaStore.chapterReadState(key, it.url) }
    }
    val mangaRead = remember(readMarks) {
        MangaStore.mangaReadState(key, chapters.map { it.url })
    }
    LaunchedEffect(rev) { followed = MangaStore.isFollowed(key) }

    val verifyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        error = null
        reload++
    }
    val openVerify: () -> Unit = {
        scope.launch {
            val provider = app.providers.byId(providerId)
            // The chain ends at the manga's own page: even an engine whose
            // extension will not load still opens its site from the title URL.
            val site = withContext(Dispatchers.IO) {
                runCatching {
                    provider?.let { webUrlChainAsync(it, mangaUrl) }
                }.getOrNull()
            }
            if (site.isNullOrBlank()) {
                android.widget.Toast.makeText(
                    context,
                    I18n.t("Couldn't determine this extension's site"),
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            } else {
                val host = runCatching { java.net.URI(site).host?.lowercase() }.getOrNull()
                verifySiteAsk = SiteAsk(site, host, title, providerId)
            }
        }
    }
    verifySiteAsk?.let { ask ->
        SiteOpenDialog(
            ask = ask,
            onWebView = { a ->
                verifyLauncher.launch(
                    Intent(context, WebViewActivity::class.java).apply {
                        putExtra("url", a.site)
                        putExtra("title", "Verify: " + (a.host ?: a.title))
                        putExtra("providerId", a.providerId)
                        putExtra("autoCloseWhenCloudflarePassed", true)
                        if (a.host != null) putExtra("verifyHost", a.host)
                    }
                )
            },
            // Through the same launcher, so coming back reloads the chapters
            // (see verifyLauncher above) exactly like the WebView path.
            onBrowser = { a ->
                verifyLauncher.launch(
                    Intent(Intent.ACTION_VIEW, Uri.parse(a.site)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            },
            onClose = { verifySiteAsk = null },
        )
    }

    LaunchedEffect(providerId, mangaUrl, reload) {
        loading = true
        error = null
        val provider = app.providers.byId(providerId)
        if (provider == null) {
            error = I18n.t("This manga engine is not installed.")
            loading = false
            return@LaunchedEffect
        }
        val base = MediaItem(
            providerId = providerId,
            id = mangaUrl,
            title = title.ifBlank { mangaUrl },
            type = MediaType.SERIES,
            posterUrl = posterUrl.takeIf { it.isNotBlank() },
            rawType = "manga",
        )
        // Extension metadata paints FIRST: the header and chapter list never
        // wait on the tracker chain (AniList/Jikan/Kitsu/MangaDex), which
        // enriches the header in the background and repaints when it lands.
        // A tracker miss simply leaves the extension's own metadata in place.
        var baseMeta = withContext(Dispatchers.IO) {
            runCatching { provider.getMeta(base) }.getOrDefault(base)
        }
        meta = baseMeta
        val externalDeferred = async(Dispatchers.IO) {
            runCatching { MangaMetadataRepository.fetchExternal(app, title) }.getOrNull()
        }
        scope.launch {
            val ext = runCatching { externalDeferred.await() }.getOrNull()
            if (ext != null) {
                // Merge onto the CURRENT header: the chapter retry below may
                // have refreshed the extension meta while this was in flight.
                val cur = meta
                if (cur != null) {
                    meta = MangaMetadataRepository.mergeExternalFirst(cur, ext)
                    extAnilistId = ext.anilistId
                }
            }
        }
        val extTitle = title
        scope.launch {
            val charsD = async(Dispatchers.IO) {
                runCatching { MangaMetadataRepository.fetchCharacters(app, extTitle) }.getOrDefault(emptyList())
            }
            val threadsD = async(Dispatchers.IO) {
                runCatching { MangaMetadataRepository.fetchThreads(app, extTitle) }.getOrDefault(emptyList())
            }
            val reviewsD = async(Dispatchers.IO) {
                runCatching { MangaMetadataRepository.fetchReviews(app, extTitle) }.getOrDefault(emptyList())
            }
            val hintsD = async(Dispatchers.IO) {
                runCatching { MangaMetadataRepository.fetchChapterHints(app, extTitle) }.getOrDefault(emptyMap())
            }
            characters = runCatching { charsD.await() }.getOrDefault(emptyList())
            threads = runCatching { threadsD.await() }.getOrDefault(emptyList())
            reviews = runCatching { reviewsD.await() }.getOrDefault(emptyList())
            chapterHints = runCatching { hintsD.await() }.getOrDefault(emptyMap())
            // extAnilistId already came from the shared enrichment above —
            // no second tracker chain here.
            commentsLoading = false
        }
        val cached = MangaStore.chaptersFor(key)
        if (cached.isNullOrEmpty()) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val eps = provider.getEpisodes(baseMeta)
                    // AnymexMangaProvider returns Episode list; bridge into MangaStore
                    // so the chapter UI (which only reads MangaStore) fills.
                    if (!eps.isNullOrEmpty() && MangaStore.chaptersFor(key).isNullOrEmpty()) {
                        MangaStore.putChapters(
                            key,
                            eps.map { e ->
                                MangaChapter(
                                    url = e.id,
                                    name = e.name ?: "Chapter ${e.number}",
                                    number = e.number.toFloat(),
                                )
                            },
                        )
                    }
                }
            }
        }
        var list = MangaStore.chaptersFor(key).orEmpty()
        if (list.isEmpty()) {
            runCatching {
                val retryMeta = withContext(Dispatchers.IO) {
                    runCatching { provider.getMeta(base) }.getOrDefault(base)
                }
                // Keep any tracker enrichment that already landed: the
                // retry must never wipe the header back to extension-only.
                baseMeta = retryMeta
                meta = MangaMetadataRepository.mergeExternalFirst(
                    retryMeta,
                    if (externalDeferred.isCompleted) {
                        runCatching { externalDeferred.getCompleted() }.getOrNull()
                    } else null,
                )
                withContext(Dispatchers.IO) { runCatching { provider.getEpisodes(retryMeta) } }
            }
            list = MangaStore.chaptersFor(key).orEmpty()
        }
        chapters = list
        if (list.isEmpty()) {
            // A CATALOG outcome ("✓ 24 titles (js)") is about the listing,
            // not the chapters — showing it under "Chapters" is what put
            // "24 titles (js)" where the chapter list belongs. Only a
            // chapter-shaped outcome is shown; otherwise the honest generic.
            val outcome = MangaProvider.lastOutcome[providerId]
                ?: com.hikari.app.anymex.AnymexMangaProvider.lastOutcome[providerId]
                ?: com.hikari.app.cs3.Cs3MangaProvider.lastOutcome[providerId]
                ?: com.hikari.app.lnreader.LnReaderProvider.lastOutcome[providerId]
                ?: com.hikari.app.sora.SoraMangaProvider.lastOutcome[providerId]
            error = if (outcome != null && outcome.contains("chapter", ignoreCase = true)) outcome
            else I18n.t("This engine returned no chapters. Pull refresh to try again.")
        }
        loading = false
    }

    val shown = remember(chapters, newestFirst) {
        val ordered = if (newestFirst) chapters.asReversed() else chapters
        ordered.distinctBy { it.url }
    }

    // A Box, not a plain Column, purely so the verification nudge can float over
    // the chapter list without taking a row of it (see the nudge below).
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        // ---- Top bar ----
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { nav.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Back"))
            }
            Text(
                title.ifBlank { meta?.title.orEmpty() },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = openVerify) {
                Icon(
                    Icons.Filled.Public,
                    contentDescription = tr("Open the site to pass its Cloudflare check"),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            IconButton(
                onClick = {
                    MangaStore.dropChapters(key)
                    chapters = emptyList()
                    reload++
                }
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = tr("Refresh chapters"))
            }
        }

        LazyColumn(
            // Chapter list position held per title (see ScrollKeeper): Back
            // from a chapter lands where the list was.
            state = com.hikari.app.ui.components.keptListState("mangaDetail:$key"),
            modifier = Modifier.fillMaxSize().mangaTabSwipe(
                onLeft = { tab = 1 },
                onRight = { tab = 0 },
            ),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 4.dp,
                bottom = LocalTaskbarInset.current + 24.dp,
            ),
        ) {
            item(key = "detail-hero") {
                Row {
                    Box(
                        Modifier
                            .width(116.dp)
                            .aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.AutoStories,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(30.dp),
                        )
                        AsyncImage(
                            model = PosterLoader.coverModel(meta?.posterUrl ?: posterUrl, providerId),
                            contentDescription = title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        if (mangaRead != com.hikari.app.manga.MangaReadState.NONE) {
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = if (mangaRead == com.hikari.app.manga.MangaReadState.COMPLETED)
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                            ) {
                                Text(
                                    if (mangaRead == com.hikari.app.manga.MangaReadState.COMPLETED) tr("Completed") else tr("Reading"),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                        Text(
                            meta?.title?.takeIf { it.isNotBlank() } ?: title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            app.providers.byId(providerId)?.config?.name.orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        val status = meta?.let { MangaMark.statusLabel(it) }
                        val facts = listOfNotNull(status, "${chapters.size} " + tr("chapters"))
                        Text(
                            facts.joinToString("  ·  "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = {
                                val rec = recordFor(meta, providerId, mangaUrl, title, posterUrl)
                                followed = MangaStore.toggleFollow(rec)
                            }) {
                                Icon(
                                    if (followed) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(if (followed) tr("Following") else tr("Follow"))
                            }
                        }
                    }
                }
            }

            if (chapters.isNotEmpty()) {
                item(key = "detail-start") {
                    // The reading button knows where the user left off: it starts
                    // that chapter (the reader itself resumes on the exact page).
                    val target = readingTarget(chapters, progress?.chapterUrl)
                    Button(
                        onClick = { openChapter(nav, providerId, mangaUrl, title, posterUrl, target) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp),
                    ) {
                        Text(
                            if (progress != null && target.url == progress.chapterUrl)
                                tr("Continue — %s").replace("%s", target.label)
                            else tr("Start reading — %s").replace("%s", target.label),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            val description = meta?.overview?.takeIf { it.isNotBlank() }
            if (description != null) {
                item(key = "detail-desc") {
                    Column(Modifier.padding(top = 14.dp)) {
                        Text(
                            description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (expanded) Int.MAX_VALUE else 5,
                            overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(onClick = { expanded = !expanded }) {
                            Text(if (expanded) tr("Show less") else tr("Show more"))
                        }
                    }
                }
            }

            val genres = meta?.genres.orEmpty()
            if (genres.isNotEmpty()) {
                item(key = "detail-genres") {
                    LazyRow(
                        Modifier.padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(genres.size, key = { i -> "g-" + genres[i] + "#" + i }) { i ->
                            val g = genres[i]
                            GenreChip(
                                genre = g,
                                onSearchHere = {
                                    Routes.safeNavigate(nav, Routes.mangaSearch("", genre = g, engineId = providerId))
                                },
                                onSearchEverywhere = {
                                    Routes.safeNavigate(nav, Routes.mangaSearch("", genre = g))
                                },
                            )
                        }
                    }
                }
            }

            if (characters.isNotEmpty()) {
                item(key = "detail-chars") {
                    Column(Modifier.padding(top = 14.dp)) {
                        Text(
                            tr("Characters"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(10.dp))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            items(characters.size, key = { i -> "mc-" + i }) { i ->
                                CharacterCircle(characters[i])
                            }
                        }
                    }
                }
            }

            item(key = "detail-tabs") {
                TabRow(
                    selectedTabIndex = tab,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp),
                ) {
                    Tab(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        text = { Text(tr("Chapters")) },
                    )
                    Tab(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        text = { Text(tr("Comments")) },
                    )
                }
            }

            if (tab == 0) {
            item(key = "detail-chapters-head") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 18.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        tr("Chapters"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    if (chapters.size > 1) {
                        TextButton(onClick = { newestFirst = !newestFirst }) {
                            Text(if (newestFirst) tr("Newest first") else tr("Oldest first"))
                        }
                    }
                }
            }

            if (loading && chapters.isEmpty()) {
                item(key = "detail-loading") {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 28.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(Modifier.size(28.dp))
                    }
                }
            } else if (error != null && chapters.isEmpty()) {
                item(key = "detail-error") {
                    Column(Modifier.padding(vertical = 20.dp)) {
                        Text(
                            error.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        if (app.providers.byId(providerId) == null) {
                            Button(
                                onClick = {
                                    Routes.safeNavigate(nav, Routes.mangaSearch(title))
                                },
                                modifier = Modifier.padding(top = 12.dp),
                            ) {
                                Text(tr("Search installed manga extensions"))
                            }
                        }
                    }
                }
            }

            items(shown.size, key = { i -> "ch-" + shown[i].url + "#" + i }) { i ->
                val chapter = shown[i]
                ChapterRow(
                    chapter = chapter,
                    isCurrent = progress?.chapterUrl == chapter.url,
                    pageLabel = progress?.takeIf { it.chapterUrl == chapter.url }?.pageLabel,
                    readState = readMarks[chapter.url],
                    hint = chapterHintFor(chapter, chapterHints),
                    onClick = {
                        openChapter(nav, providerId, mangaUrl, title, posterUrl, chapter)
                    },
                )
            }

            if (chapters.size > 24) {
                item(key = "detail-tail") {
                    Text(
                        tr("%s chapters").replace("%s", chapters.size.toString()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 18.dp),
                    )
                }
            }
            } // if (tab == 0): chapters

            if (tab == 1) {
                item(key = "detail-comments") {
                    CommentsTab(
                        nav = nav,
                        providerId = providerId,
                        title = title,
                        threads = threads,
                        reviews = reviews,
                        anilistId = extAnilistId,
                        loading = commentsLoading,
                        onThreadsChanged = { threads = it },
                        onLoginNeeded = {
                            Routes.safeNavigate(nav, Routes.SETTINGS)
                        },
                    )
                }
            }
        }
    }
    VerificationNudge(
        waiting = loading && chapters.isEmpty(),
        onOpenWebView = openVerify,
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 56.dp),
    )
    }
}

@Composable
private fun GenreChip(
    genre: String,
    onSearchHere: () -> Unit,
    onSearchEverywhere: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Surface(
            onClick = { open = true },
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        ) {
            Text(
                genre,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(tr("Search")) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                onClick = {
                    open = false
                    onSearchHere()
                },
            )
            DropdownMenuItem(
                text = { Text(tr("Global search")) },
                leadingIcon = { Icon(Icons.Filled.Public, contentDescription = null) },
                onClick = {
                    open = false
                    onSearchEverywhere()
                },
            )
        }
    }
}

/** A horizontal drag anywhere on the list flips the Chapters | Comments tab:
 *  left reaches Comments, right returns to Chapters. Inner horizontal rows
 *  (genres, characters) keep their own scrolling — a swipe that starts on
 *  them still scrolls them, exactly like every nested-scroll app. */
private fun Modifier.mangaTabSwipe(onLeft: () -> Unit, onRight: () -> Unit): Modifier =
    pointerInput(onLeft, onRight) {
        var total = 0f
        detectHorizontalDragGestures(
            onDragStart = { total = 0f },
            onHorizontalDrag = { _, amount -> total += amount },
            onDragEnd = {
                if (total <= -90f) onLeft()
                else if (total >= 90f) onRight()
            },
        )
    }

/** The external subtitle for [chapter], if the adult DB describes that number. */
private fun chapterHintFor(chapter: MangaChapter, hints: Map<Int, String>): String? {
    if (hints.isEmpty()) return null
    val n = if (chapter.number >= 0f) chapter.number.toInt()
    else Regex("""\d+""").find(chapter.name)?.value?.toIntOrNull() ?: -1
    if (n <= 0) return null
    return hints[n]?.takeIf { it.isNotBlank() }
}

/** "3 months ago" from an AniList epoch-seconds timestamp. */
private fun fmtAgo(sec: Long): String {
    if (sec <= 0L) return ""
    val mins = (System.currentTimeMillis() / 1000L - sec) / 60L
    if (mins < 1L) return "just now"
    if (mins < 60L) return "$mins min ago"
    val hours = mins / 60L
    if (hours < 24L) return "$hours h ago"
    val days = hours / 24L
    if (days < 30L) return "$days d ago"
    val months = days / 30L
    if (months < 12L) return "$months mo ago"
    return "${months / 12L} y ago"
}

/** One character: circular portrait with the name underneath. */
@Composable
private fun CharacterCircle(c: MangaMetadataRepository.MangaCharacter) {
    Column(
        Modifier.width(72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                c.name.firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AsyncImage(
                model = c.imageUrl,
                contentDescription = c.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            c.name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun CommentsTab(
    nav: NavHostController,
    providerId: String,
    title: String,
    threads: List<MangaMetadataRepository.MangaThread>,
    reviews: List<MangaMetadataRepository.MangaReview>,
    anilistId: Int,
    loading: Boolean,
    onThreadsChanged: (List<MangaMetadataRepository.MangaThread>) -> Unit,
    onLoginNeeded: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as HikariApp
    val scope = rememberCoroutineScope()
    var showNew by remember { mutableStateOf(false) }
    var showLogin by remember { mutableStateOf(false) }
    var newTitle by remember { mutableStateOf("") }
    var newBody by remember { mutableStateOf("") }
    var posting by remember { mutableStateOf(false) }
    var postError by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                tr("Comments"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (anilistId > 0) {
                TextButton(onClick = {
                    newTitle = ""
                    newBody = ""
                    postError = null
                    showNew = true
                }) {
                    Icon(Icons.Filled.Forum, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(tr("New thread"))
                }
            }
        }
        if (loading && threads.isEmpty() && reviews.isEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(Modifier.size(24.dp))
            }
        }
        threads.forEach { t ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        context.startActivity(
                            Intent(context, WebViewActivity::class.java).apply {
                                putExtra("url", t.url)
                                putExtra("title", t.title)
                                putExtra("providerId", providerId)
                            }
                        )
                    }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        t.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val sub = listOfNotNull(
                        (if (t.replyCount > 0) tr("%s replies").replace("%s", t.replyCount.toString()) else null),
                        (if (t.viewCount > 0) tr("%s views").replace("%s", t.viewCount.toString()) else null),
                        t.replyUser,
                        fmtAgo(t.repliedAtSec).takeIf { it.isNotBlank() },
                    ).joinToString("  ·  ")
                    if (sub.isNotBlank()) {
                        Text(
                            sub,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }
        reviews.forEach { r ->
            Column(Modifier.padding(vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        r.user,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    if (r.rating != null) {
                        Text(
                            r.rating.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (r.createdAt.isNotBlank()) {
                        Text(
                            "  ·  " + r.createdAt,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    r.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        if (!loading && threads.isEmpty() && reviews.isEmpty()) {
            Text(
                if (anilistId > 0) tr("No threads yet — start the first one.")
                else tr("No comments found for this title."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 16.dp),
            )
        }
    }

    if (showNew) {
        AlertDialog(
            onDismissRequest = { if (!posting) showNew = false },
            title = { Text(tr("New thread")) },
            text = {
                Column {
                    OutlinedTextField(
                        value = newTitle,
                        onValueChange = { newTitle = it },
                        label = { Text(tr("Title")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newBody,
                        onValueChange = { newBody = it },
                        label = { Text(tr("Message")) },
                        minLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (!postError.isNullOrBlank()) {
                        Text(
                            postError.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !posting && newTitle.isNotBlank() && newBody.isNotBlank(),
                    onClick = {
                        posting = true
                        postError = null
                        scope.launch {
                            val res = withContext(Dispatchers.IO) {
                                MangaMetadataRepository.postThread(app, title, newTitle, newBody)
                            }
                            posting = false
                            res.onSuccess {
                                showNew = false
                                android.widget.Toast.makeText(
                                    context,
                                    I18n.t("Thread posted"),
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                                onThreadsChanged(
                                    runCatching {
                                        withContext(Dispatchers.IO) {
                                            MangaMetadataRepository.fetchThreads(app, title)
                                        }
                                    }.getOrDefault(threads)
                                )
                            }.onFailure { e ->
                                if (e.message == "NOT_SIGNED_IN") {
                                    showNew = false
                                    showLogin = true
                                } else {
                                    postError = e.message
                                }
                            }
                        }
                    },
                ) {
                    Text(if (posting) tr("Posting…") else tr("Post"))
                }
            },
            dismissButton = {
                TextButton(onClick = { if (!posting) showNew = false }) {
                    Text(tr("Cancel"))
                }
            },
        )
    }

    if (showLogin) {
        AlertDialog(
            onDismissRequest = { showLogin = false },
            title = { Text(tr("AniList sign-in needed")) },
            text = { Text(tr("Posting a comment needs your AniList login — the same one under Settings → Trackers.")) },
            confirmButton = {
                Button(onClick = {
                    showLogin = false
                    onLoginNeeded()
                }) {
                    Text(tr("Open Settings"))
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogin = false }) {
                    Text(tr("Cancel"))
                }
            },
        )
    }
}

/** One chapter: its name, its scanlator/date, where the reader is in it, and its read mark. */
@Composable
private fun ChapterRow(
    chapter: MangaChapter,
    isCurrent: Boolean,
    pageLabel: String?,
    readState: com.hikari.app.manga.MangaChapterRead?,
    hint: String? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A dot in the margin marks the chapter in progress — the same idiom the
        // reference readers use, and it costs no extra text.
        Box(
            Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(50))
                .background(
                    if (isCurrent) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                )
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                chapter.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isCurrent) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = listOfNotNull(
                hint?.takeIf { it.isNotBlank() },
                chapter.scanlator?.takeIf { it.isNotBlank() },
                fmtDate(chapter.dateUpload),
                pageLabel?.let { tr("page %s").replace("%s", it) },
            ).joinToString("  ·  ")
            if (sub.isNotBlank()) {
                Text(
                    sub,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // The chapter's own read mark, like the video rows' Watched/Watching
        // pills: finished chapters read Completed, opened ones Reading.
        if (readState != null) {
            Spacer(Modifier.width(8.dp))
            Surface(
                shape = RoundedCornerShape(50),
                color = if (readState.finished)
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            ) {
                Text(
                    if (readState.finished) tr("Completed") else tr("Reading"),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
    }
}

/** The chapter the reading button opens: the one in progress, else the FIRST in
 *  reading order (chapters arrive oldest-first from the provider). */
private fun readingTarget(chapters: List<MangaChapter>, currentUrl: String?): MangaChapter {
    if (currentUrl != null) {
        chapters.firstOrNull { it.url == currentUrl }?.let { return it }
    }
    return chapters.first()
}

private fun openChapter(
    nav: NavHostController,
    providerId: String,
    mangaUrl: String,
    title: String,
    posterUrl: String,
    chapter: MangaChapter,
) {
    // Novel rows open the text reader; manga rows the image reader (see
    // Routes.readingRoute — every opener uses the same rule).
    val route = Routes.readingRoute(
        providerId = providerId,
        url = mangaUrl,
        chapterUrl = chapter.url,
        title = title,
        posterUrl = posterUrl,
    )
    Routes.safeNavigate(nav, route)
}

/** The library record for this title, as complete as it can be made here. */
private fun recordFor(
    meta: MediaItem?,
    providerId: String,
    mangaUrl: String,
    title: String,
    posterUrl: String,
): MangaRecord {
    val m = meta
    return MangaRecord(
        providerId = providerId,
        providerName = HikariApp.instance.providers.byId(providerId)?.config?.name.orEmpty(),
        url = mangaUrl,
        title = m?.title?.takeIf { it.isNotBlank() } ?: title.ifBlank { mangaUrl },
        description = m?.overview,
        genres = m?.genres.orEmpty(),
        status = m?.let { MangaMark.statusLabel(it) },
        posterUrl = m?.posterUrl?.takeIf { it.isNotBlank() } ?: posterUrl.takeIf { it.isNotBlank() },
    )
}

private fun fmtDate(ms: Long): String {
    if (ms <= 0L) return ""
    return runCatching {
        SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(ms))
    }.getOrDefault("")
}
