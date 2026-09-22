package com.hikari.app.ui.screens
import com.hikari.app.i18n.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.hikari.app.data.HistoryEntry
import com.hikari.app.ui.PosterLoader
import com.hikari.app.ui.components.EmptyState
import com.hikari.app.ui.navigation.LocalTaskbarInset
import com.hikari.app.ui.navigation.Routes
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun HistoryScreen(nav: NavHostController, embedded: Boolean = false) {
    val context = LocalContext.current
    val app = context.applicationContext as HikariApp
    val scope = rememberCoroutineScope()

    // Flows must be remembered, not rebuilt inline — a fresh Flow per
    // recomposition makes collectAsState reset to `initial` every time.
    val historyFlow = remember { app.store.historyFlow() }
    val pausedFlow = remember { app.store.historyPausedFlow() }
    val entries by historyFlow.collectAsState(initial = emptyList())
    // Defensive dedupe — a legacy/racing write could leave a duplicate
    // uniqueKey, which would crash this LazyColumn on a duplicate Compose key.
    val shownEntries = remember(entries) { entries.distinctBy { it.uniqueKey } }
    val paused by pausedFlow.collectAsState(initial = false)
    // Manga reading counts as history too (see the shelf below). Read through
    // the manga store's revision so a chapter read elsewhere appears instantly.
    val mangaRev = rememberMangaRevision()
    val hasMangaProgress = remember(mangaRev) { com.hikari.app.manga.MangaStore.progress().isNotEmpty() }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 16.dp,
            // Clear of the floating taskbar (0 when there is no bar).
            bottom = LocalTaskbarInset.current + 16.dp,
        )
    ) {
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Inside the merged My Stuff tab the strip already says
                // "History", so only the pause switch stays on this row.
                if (!embedded) {
                    Text(
                        tr("History"),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                Text(
                    tr("Pause history"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Switch(
                    checked = paused,
                    onCheckedChange = { scope.launch { app.store.setHistoryPaused(it) } },
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            if (paused) {
                Text(
                    tr("History is paused — new videos won't be added."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
        }
        if (hasMangaProgress) {
            // What you were READING is history in the same sense as what you
            // were watching, so it sits at the top of this list (see
            // MangaContinueShelf). The shelf draws nothing when empty, and the
            // item is only added when it will draw something.
            item(key = "history-manga") {
                MangaContinueShelf(nav)
                Spacer(Modifier.height(12.dp))
            }
        }
        if (shownEntries.isEmpty()) {
            item {
                EmptyState(
                    title = tr("No watch history yet"),
                    subtitle = tr("Videos you play will show up here so you can pick up where you left off. ") +
                        (if (paused) "History is currently paused — flip the switch above to start tracking." else "Tap any entry to resume it."),
                    actionLabel = tr("Browse"),
                    action = { Routes.navigateTab(nav, Routes.HOME) }
                )
            }
        } else {
            item {
                TextButton(
                    onClick = { scope.launch { app.store.clearHistory() } },
                    modifier = Modifier.padding(bottom = 4.dp)
                ) {
                    Text(tr("Clear history"), color = MaterialTheme.colorScheme.error)
                }
            }
            items(shownEntries, key = { it.uniqueKey }) { h ->
                HistoryRow(
                    h = h,
                    onClick = {
                        Routes.safeNavigate(
                            nav,
                            Routes.detail(
                                providerId = h.providerId,
                                type = h.type,
                                mediaId = h.mediaId,
                                title = h.title,
                                posterUrl = h.posterUrl,
                                episodeId = h.episodeId,
                                startPositionMs = h.positionMs,
                            )
                        )
                    },
                    // Deletes this one entry (a single movie, or one episode of
                    // a series) without touching the rest of the history.
                    onDelete = { scope.launch { app.store.removeHistory(h.uniqueKey) } },
                )
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun HistoryRow(h: HistoryEntry, onClick: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val poster = PosterLoader.model(h.posterUrl)
        if (poster != null) {
            AsyncImage(
                model = poster,
                contentDescription = null,
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                h.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val subtitle = buildString {
                if (h.episodeName.isNotBlank() || h.episodeId.isNotBlank()) {
                    append(h.episodeName.ifBlank { "Episode" })
                    append("  ·  ")
                }
                append(progressLine(h))
            }
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                relativeTime(h.watchedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = tr("Delete from history"),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp)
            )
        }
        Icon(
            Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
    }
}

private fun progressLine(h: HistoryEntry): String {
    if (h.durationMs <= 0L) return "Watched"
    val pct = (h.positionMs * 100 / h.durationMs).coerceIn(0, 100)
    return "${fmtMs(h.positionMs)} / ${fmtMs(h.durationMs)}  ·  $pct%"
}

private fun fmtMs(ms: Long): String {
    val s = ms / 1000
    return String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
}

private fun relativeTime(at: Long): String {
    if (at <= 0L) return ""
    val diff = System.currentTimeMillis() - at
    return when {
        diff < 60_000L -> "just now"
        diff < 3_600_000L -> "${diff / 60_000L} min ago"
        diff < 86_400_000L -> "${diff / 3_600_000L} hr ago"
        else -> "${diff / 86_400_000L} d ago"
    }
}
