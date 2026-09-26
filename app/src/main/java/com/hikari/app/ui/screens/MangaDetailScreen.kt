package com.hikari.app.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.hikari.app.HikariApp
import com.hikari.app.data.MediaItem
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One manga: its cover and description, a follow button, and its chapters.
 *
 * The chapters are the point of this screen — they are what the reader navigates
 * by — so they are fetched once and CACHED in [MangaStore]: opening a followed
 * title again shows the list instantly, and only the refresh button re-asks the
 * site. The list is newest-first by default (what every reader expects) with a
 * toggle, and the chapter the reader is currently on is marked, so "where was I"
 * is answered by looking rather than remembering.
 *
 * Every load failure is a line on screen, never a crash: a manga source is
 * third-party code and can throw anything (see
 * [com.hikari.app.manga.MangaProvider], which turns every throw into a short
 * message).
 */
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
    // The verification launcher below is started from a click, and the site URL
    // has to be derived off the main thread, so the screen needs a scope of its
    // own (the same one the refresh path uses).
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
    // Bumped by the refresh button (and by the follow toggle, which changes what
    // the header draws) so the load effect runs again on demand.
    var reload by remember { mutableIntStateOf(0) }
    var expanded by remember { mutableStateOf(false) }

    val rev = rememberMangaRevision()
    val progress = remember(rev) { MangaStore.progressFor(key) }
    LaunchedEffect(rev) { followed = MangaStore.isFollowed(key) }

    // ---- Verification, right where the chapters are -------------------------
    //
    // The Manga tab's globe is per ENGINE, which is the right place to fix a
    // blocked catalog — but a site can put its Cloudflare challenge in front of
    // the CHAPTER LIST, and then the title page is the one that comes back empty
    // ("some manga site also put verification on chapter loading page, so add
    // there a webview"). So the page the user is staring at carries its own way
    // in, and coming back re-asks the source: between opening the view and
    // closing it, the answer goes from a challenge to the real chapter list.
    val verifyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        error = null
        reload++
    }
    val openVerify: () -> Unit = {
        scope.launch {
            val provider = app.providers.byId(providerId)
            val site = withContext(Dispatchers.IO) {
                runCatching {
                    provider?.config?.let {
                        com.hikari.app.manga.MangaExtensionManager.siteUrlOf(it)
                    }
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
                verifyLauncher.launch(
                    Intent(context, WebViewActivity::class.java).apply {
                        putExtra("url", site)
                        putExtra("title", "Verify: " + (host ?: title))
                        putExtra("providerId", providerId)
                        putExtra("autoCloseWhenCloudflarePassed", true)
                        if (host != null) putExtra("verifyHost", host)
                    }
                )
            }
        }
    }

    LaunchedEffect(providerId, mangaUrl, reload) {
        loading = true
        error = null
        val provider = app.providers.byId(providerId) as? MangaProvider
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
        val fresh = withContext(Dispatchers.IO) {
            runCatching { provider.getMeta(base) }.getOrDefault(base)
        }
        meta = fresh
        // The chapter list: the cache first (a second visit is instant), then the
        // source. getEpisodes writes whatever it gets into MangaStore itself.
        val cached = MangaStore.chaptersFor(key)
        if (cached.isNullOrEmpty()) {
            withContext(Dispatchers.IO) {
                runCatching { provider.getEpisodes(fresh) }
            }
        }
        val list = MangaStore.chaptersFor(key).orEmpty()
        chapters = list
        if (list.isEmpty()) {
            error = MangaProvider.lastOutcome[providerId]
                ?: I18n.t("This engine returned no chapters. Pull refresh to try again.")
        }
        loading = false
    }

    val shown = remember(chapters, newestFirst) {
        if (newestFirst) chapters.asReversed() else chapters
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
            // The same globe the Manga tab's engine headers carry: this title's
            // site, in the verification WebView. Kept beside the refresh button
            // because a chapter list that will not load is either stale (refresh)
            // or blocked (this).
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
            Modifier.fillMaxSize(),
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
                        items(genres, key = { "g-" + it }) { g ->
                            // A tag is a search, not a decoration. Two scopes are
                            // offered because both are wanted and only the user
                            // knows which: the tag belongs to THIS site's
                            // vocabulary (only this extension knows what it means
                            // by "Manhwa"), while the same story is usually carried
                            // by several engines and a reader hunting for more of
                            // it does not care which one answers.
                            GenreChip(
                                genre = g,
                                onSearchHere = {
                                    Routes.safeNavigate(nav, Routes.searchInProvider(providerId, g))
                                },
                                onSearchEverywhere = {
                                    Routes.safeNavigate(nav, Routes.searchQuery(g))
                                },
                            )
                        }
                    }
                }
            }

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
                    Text(
                        error.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 20.dp),
                    )
                }
            }

            items(shown, key = { "ch-" + it.url }) { chapter ->
                ChapterRow(
                    chapter = chapter,
                    isCurrent = progress?.chapterUrl == chapter.url,
                    pageLabel = progress?.takeIf { it.chapterUrl == chapter.url }?.pageLabel,
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
        }
    }
    // Ten seconds of an empty chapter list is the one moment this page can
    // explain itself: the site is almost certainly asking for the verification
    // it asks a browser for (see [VerificationNudge]). It floats under the bar,
    // says its piece once, and is tappable straight into the WebView.
    VerificationNudge(
        waiting = loading && chapters.isEmpty(),
        onOpenWebView = openVerify,
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 56.dp),
    )
    }
}

/**
 * One tag of a manga, as a chip that opens the two ways to search for it.
 *
 * A plain `Surface` pair rather than a `FilterChip`: the app's chips are glass
 * pills everywhere else (see the engine chips on the Manga tab), and a tag that
 * looks clickable has to behave clickable — tapping it used to do nothing at
 * all, which is worse than not offering it.
 *
 * The menu is anchored to the chip, so it opens under the tag the user actually
 * pressed, and the two entries say exactly what they search: [onSearchHere] is
 * the engine this title came from, [onSearchEverywhere] every installed engine.
 */
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

/** One chapter: its name, its scanlator/date, and where the reader is in it. */
@Composable
private fun ChapterRow(
    chapter: MangaChapter,
    isCurrent: Boolean,
    pageLabel: String?,
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
    Routes.safeNavigate(
        nav,
        Routes.mangaReader(
            providerId = providerId,
            url = mangaUrl,
            chapterUrl = chapter.url,
            title = title,
            posterUrl = posterUrl,
        ),
    )
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
