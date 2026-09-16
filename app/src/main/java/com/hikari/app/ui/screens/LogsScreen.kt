package com.hikari.app.ui.screens
import com.hikari.app.i18n.tr

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.hikari.app.BuildConfig
import com.hikari.app.HikariApp
import com.hikari.app.data.Logs
import com.hikari.app.ui.components.GlassCard
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings → Logs & diagnostics: the two rolling app logs and the crash log,
 * each with a Share button and a Save-to-storage button, plus a "Share all".
 *
 * The point is that a bug report never needs a screenshot again: the user opens
 * this page, taps Share on whatever the developer asked for, and Android's share
 * sheet hands over the real text.
 */
@Composable
fun LogsPage(app: HikariApp, onBack: () -> Unit) {
    val context = LocalContext.current
    var refreshTick by remember { mutableStateOf(0) }
    var showClearDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    BackHandler { onBack() }

    val files = remember(refreshTick) { Logs.logFiles(context) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
    ) {
        item {
            Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
                            .clickable(onClick = onBack),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = tr("Back to settings"),
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            tr("Logs & diagnostics"),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            tr("Share what the app recorded"),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    tr("Hikari keeps two rolling app logs and one crash log on this " + "device. If something goes wrong, share these files here ") +
                        "instead of a screenshot — they contain the exact error and " +
                        "what happened just before it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
            }
        }

        item {
            GlassCard(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        tr("All logs"),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        tr("Share or save the app logs and the crash log together."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionPill(
                            icon = Icons.Filled.Share,
                            label = tr("Share all"),
                            primary = true,
                        ) {
                            val existing = Logs.existingFiles(context)
                            if (existing.isEmpty()) {
                                toast("No logs to share yet")
                            } else {
                                shareFiles(context, existing.map { it.file }, "Hikari logs")
                            }
                        }
                        ActionPill(
                            icon = Icons.Filled.Save,
                            label = tr("Save all"),
                            primary = false,
                        ) {
                            val existing = Logs.existingFiles(context)
                            if (existing.isEmpty()) {
                                toast("No logs to save yet")
                            } else {
                                var ok = 0
                                existing.forEach { ok += if (saveToDownloads(context, it.file) != null) 1 else 0 }
                                toast(
                                    if (ok > 0) "Saved $ok file(s) to Downloads"
                                    else "Could not save the logs"
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            Column(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 6.dp)) {
                Text(
                    tr("Log files"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    tr("Tap Share to send a file, or Save to keep it in Downloads."),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        for (entry in files) {
            item(key = entry.key) {
                val exists = entry.file.exists() && entry.file.length() > 0L
                GlassCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (entry.isCrash) Icons.Filled.BugReport else Icons.Filled.Description,
                                contentDescription = null,
                                tint = if (entry.isCrash && exists) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    entry.title,
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    if (exists) {
                                        "${formatSize(entry.file.length())} · updated ${formatTime(entry.file.lastModified())}"
                                    } else {
                                        "Empty — nothing recorded yet"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            entry.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (exists) {
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ActionPill(
                                    icon = Icons.Filled.Share,
                                    label = tr("Share"),
                                    primary = true,
                                ) {
                                    shareFiles(context, listOf(entry.file), "Hikari ${entry.title}")
                                }
                                ActionPill(
                                    icon = Icons.Filled.Save,
                                    label = tr("Save"),
                                    primary = false,
                                ) {
                                    val saved = saveToDownloads(context, entry.file)
                                    toast(
                                        if (saved != null) "Saved to Downloads/$saved"
                                        else "Could not save the file"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
                Text(
                    tr("Logs live only on this device and are never uploaded " + "automatically — nothing leaves your phone until you tap ") +
                        "Share or Save.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = { showClearDialog = true }) {
                    Icon(
                        Icons.Filled.DeleteSweep,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(tr("Clear all logs"), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(tr("Clear all logs?")) },
            text = { Text(tr("The two app logs and the crash log will be deleted from this device.")) },
            confirmButton = {
                TextButton(onClick = {
                    Logs.clear(context)
                    showClearDialog = false
                    refreshTick++
                    toast("Logs cleared")
                }) { Text(tr("Clear"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text(tr("Cancel")) }
            },
        )
    }
}

@Composable
private fun ActionPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    primary: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (primary) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                else MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Hand one or more files to Android's share sheet (FileProvider URIs only). */
internal fun shareFiles(context: Context, files: List<File>, subject: String) {
    val uris = ArrayList<Uri>()
    files.forEach { file ->
        runCatching {
            uris.add(
                FileProvider.getUriForFile(
                    context,
                    context.packageName + ".fileprovider",
                    file,
                )
            )
        }
    }
    if (uris.isEmpty()) {
        Toast.makeText(context, "Nothing to share", Toast.LENGTH_SHORT).show()
        return
    }
    val intent = Intent(
        if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE
    ).apply {
        type = "text/plain"
        if (uris.size == 1) {
            putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(
            Intent.EXTRA_TEXT,
            "Hikari diagnostics · ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})"
        )
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, "Share logs"))
    }.onFailure {
        Toast.makeText(context, "No app available to share", Toast.LENGTH_SHORT).show()
    }
}

/** Copy a log into the public Downloads folder; returns the saved file name. */
private fun saveToDownloads(context: Context, file: File): String? = runCatching {
    val name = "hikari-" + file.name
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
        }
        val uri = context.contentResolver
            .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return@runCatching null
        context.contentResolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { it.copyTo(out) }
        }
        name
    } else {
        @Suppress("DEPRECATION")
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.exists()) dir.mkdirs()
        val out = File(dir, name)
        file.inputStream().use { input -> out.outputStream().use { input.copyTo(it) } }
        name
    }
}.getOrNull()

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

private fun formatTime(millis: Long): String =
    runCatching { SimpleDateFormat("d MMM, HH:mm", Locale.US).format(Date(millis)) }
        .getOrDefault("—")
