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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import com.hikari.app.i18n.tr
import com.hikari.app.manga.MangaChapter
import com.hikari.app.manga.MangaMark
import com.hikari.app.manga.MangaProvider
import com.hikari.app.manga.MangaRecord
import com.hikari.app.manga.MangaStore
import com.hikari.app.ui.PosterLoader
import com.hikari.app.ui.navigation.LocalTaskbarInset
import com.hikari.app.ui.navigation.Routes
import kotlinx.coroutines.Dispatchers
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

    LaunchedEffect(providerId, mangaUrl, reload) {
        loading = true
        error = null
        val provider = app.providers.byId(providerId) as? MangaProvider
        if (provider == null) {
            error = tr("This manga engine is not installed.")
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
                ?: tr("This engine returned no chapters. Pull refresh to try again.")
        }
        loading = false
    }

    val shown = remember(chapters, newestFirst) {
        if (newestFirst) chapters.asReversed() else chapters
    }

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
                            model = PosterLoader.model(meta?.posterUrl ?: posterUrl),
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
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            ) {
                                Text(
                                    g,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                )
                            }
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
