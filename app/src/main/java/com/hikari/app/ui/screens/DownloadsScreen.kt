package com.hikari.app.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.hikari.app.data.MediaType
import com.hikari.app.download.DownloadKind
import com.hikari.app.download.DownloadStatus
import com.hikari.app.download.DownloadTask
import com.hikari.app.download.DownloadsRepository
import com.hikari.app.player.PlayerActivity
import com.hikari.app.ui.PosterLoader
import com.hikari.app.ui.components.EmptyState
import com.hikari.app.ui.navigation.Routes
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun DownloadsScreen(nav: NavHostController) {
    val context = LocalContext.current
    val tasks by DownloadsRepository.tasks.collectAsState()
    LaunchedEffect(Unit) { DownloadsRepository.ensureLoaded(context) }

    val active = tasks.filter { it.status != DownloadStatus.DONE }
    val done = tasks.filter { it.status == DownloadStatus.DONE }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp)
    ) {
        item {
            Text(
                "Downloads",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }
        if (tasks.isEmpty()) {
            item {
                EmptyState(
                    title = "No downloads yet",
                    subtitle = "Tap the Download button while watching a video to save it for " +
                        "offline viewing inside Hikari, or into your phone's Downloads folder.",
                    actionLabel = "Browse",
                    action = { Routes.navigateTab(nav, Routes.HOME) }
                )
            }
        } else {
            if (active.isNotEmpty()) {
                item {
                    Text(
                        "In progress",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                items(active, key = { it.id }) { t ->
                    DownloadRow(t, onDelete = { DownloadsRepository.remove(context, t.id) })
                }
            }
            if (done.isNotEmpty()) {
                item {
                    Text(
                        "Completed",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(
                            top = if (active.isEmpty()) 0.dp else 16.dp,
                            bottom = 4.dp
                        )
                    )
                }
                items(done, key = { it.id }) { t ->
                    DownloadRow(t, onDelete = { DownloadsRepository.remove(context, t.id) })
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun DownloadRow(t: DownloadTask, onDelete: () -> Unit) {
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val poster = PosterLoader.model(t.poster)
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
                    Icons.Filled.Download,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                t.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val subtitle = buildString {
                if (t.episodeLabel.isNotBlank()) append(t.episodeLabel).append("  ·  ")
                append(t.sourceName.ifBlank { "Server" })
            }
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                statusLine(t),
                style = MaterialTheme.typography.labelSmall,
                color = when (t.status) {
                    DownloadStatus.FAILED -> MaterialTheme.colorScheme.error
                    DownloadStatus.DONE -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (t.status == DownloadStatus.RUNNING ||
                t.status == DownloadStatus.QUEUED ||
                t.status == DownloadStatus.PAUSED
            ) {
                Spacer(Modifier.height(6.dp))
                if (t.progress > 0f) {
                    LinearProgressIndicator(
                        progress = { t.progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (t.status) {
                DownloadStatus.RUNNING, DownloadStatus.QUEUED -> {
                    IconButton(onClick = { DownloadsRepository.pause(context, t.id) }) {
                        Icon(Icons.Filled.Pause, contentDescription = "Pause")
                    }
                }
                DownloadStatus.PAUSED -> {
                    IconButton(onClick = { DownloadsRepository.resume(context, t.id) }) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Resume")
                    }
                }
                DownloadStatus.FAILED -> {
                    IconButton(onClick = { DownloadsRepository.resume(context, t.id) }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Retry")
                    }
                }
                DownloadStatus.DONE -> {
                    if (t.playableOffline) {
                        IconButton(onClick = { playOffline(context, t) }) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = "Play offline",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    } else if (t.playableSaved) {
                        IconButton(onClick = { playSaved(context, t) }) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = "Play",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    } else {
                        Icon(
                            Icons.Filled.DownloadDone,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

private fun statusLine(t: DownloadTask): String {
    val pct = (t.progress * 100).toInt()
    return when (t.status) {
        DownloadStatus.QUEUED -> "Queued"
        DownloadStatus.RUNNING -> buildString {
            append("Downloading")
            if (t.progress > 0f) append(" · $pct%")
            if (t.bytesDone > 0 && t.bytesTotal > 0) {
                append(" · ").append(fmtBytes(t.bytesDone)).append(" / ").append(fmtBytes(t.bytesTotal))
            }
            if (t.kind == DownloadKind.EXPORT) append("  →  phone storage")
        }
        DownloadStatus.PAUSED -> "Paused" + if (t.progress > 0f) " · $pct%" else ""
        DownloadStatus.FAILED -> "Failed" + (t.error?.let { " · $it" } ?: "")
        DownloadStatus.DONE -> when {
            t.kind == DownloadKind.EXPORT && t.playableOffline -> "Saved to Downloads · also available offline"
            t.kind == DownloadKind.EXPORT ->
                "Saved to phone storage (Downloads/Hikari) — tap ▶ to play"
            else -> "Available offline"
        }
    }
}

private fun fmtBytes(b: Long): String {
    if (b <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var v = b.toDouble()
    var i = 0
    while (v >= 1024 && i < units.size - 1) {
        v /= 1024
        i++
    }
    return if (i == 0) "${b} ${units[0]}"
    else String.format(java.util.Locale.US, "%.1f %s", v, units[i])
}

private fun playOffline(context: Context, t: DownloadTask) {
    val path = t.localPath ?: return
    val file = java.io.File(path)
    if (!file.exists()) return
    val src = JSONObject()
        .put("name", t.sourceName.ifBlank { "Download" })
        .put("url", Uri.fromFile(file).toString())
        .put("headers", JSONObject())
        .put("isM3u8", path.endsWith(".m3u8", ignoreCase = true))
        .put("isMpd", false)
        .put("isTorrent", false)
        .put("local", true)
        .put("subtitles", JSONArray())
    val payload = JSONArray().put(src).toString()
    val intent = Intent(context, PlayerActivity::class.java).apply {
        putExtra("title", t.title)
        putExtra("sources", payload)
        putExtra("showLoadingBanner", false)
        if (t.providerId.isNotBlank() && t.mediaId.isNotBlank()) {
            putExtra("histTitle", t.title)
            putExtra("histProviderId", t.providerId)
            putExtra("histMediaId", t.mediaId)
            putExtra("histType", MediaType.UNKNOWN.name)
            putExtra("histPoster", PosterLoader.tokenize(t.poster).orEmpty())
            putExtra("histEpisodeId", t.episodeId)
            putExtra("histEpisodeName", t.episodeLabel)
        }
    }
    runCatching { context.startActivity(intent) }
}

/** Plays an EXPORTED file (a MediaStore content:// URI, or a plain path on
 *  pre-Q) through Hikari's own player, which ships software audio decoders. */
private fun playSaved(context: Context, t: DownloadTask) {
    val saved = t.savedUri ?: return
    if (saved.isBlank()) return
    val uri = when {
        saved.startsWith("content:") || saved.startsWith("file:") -> Uri.parse(saved)
        else -> Uri.fromFile(java.io.File(saved))
    }
    val src = JSONObject()
        .put("name", t.sourceName.ifBlank { "Download" })
        .put("url", uri.toString())
        .put("headers", JSONObject())
        .put("isM3u8", false)
        .put("isMpd", false)
        .put("isTorrent", false)
        .put("local", true)
        .put("subtitles", JSONArray())
    val payload = JSONArray().put(src).toString()
    val intent = Intent(context, PlayerActivity::class.java).apply {
        putExtra("title", t.title)
        putExtra("sources", payload)
        putExtra("showLoadingBanner", false)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(intent) }
}
