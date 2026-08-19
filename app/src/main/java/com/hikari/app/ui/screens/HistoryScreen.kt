package com.hikari.app.ui.screens

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
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.hikari.app.ui.navigation.Routes
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun HistoryScreen(nav: NavHostController) {
    val context = LocalContext.current
    val app = context.applicationContext as HikariApp
    val scope = rememberCoroutineScope()

    val entries by app.store.historyFlow().collectAsState(initial = emptyList())
    val paused by app.store.historyPausedFlow().collectAsState(initial = false)

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp)
    ) {
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "History",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "Pause history",
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
                    "History is paused — new videos won't be added.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
        }
        if (entries.isEmpty()) {
            item {
                EmptyState(
                    title = "No watch history yet",
                    subtitle = "Videos you play will show up here so you can pick up where you left off. " +
                        (if (paused) "History is currently paused — flip the switch above to start tracking." else "Tap any entry to resume it."),
                    actionLabel = "Browse",
                    action = { nav.navigate(Routes.HOME) }
                )
            }
        } else {
            item {
                TextButton(
                    onClick = { scope.launch { app.store.clearHistory() } },
                    modifier = Modifier.padding(bottom = 4.dp)
                ) {
                    Text("Clear history", color = MaterialTheme.colorScheme.error)
                }
            }
            items(entries, key = { it.uniqueKey }) { h ->
                HistoryRow(h) {
                    nav.navigate(
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
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun HistoryRow(h: HistoryEntry, onClick: () -> Unit) {
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
