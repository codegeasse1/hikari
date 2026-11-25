package com.hikari.app.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private sealed interface ImportUi {
    data object Input : ImportUi
    data class Working(val status: String, val done: Int, val total: Int) : ImportUi
    data class Preview(val preview: ImportPreview) : ImportUi
    data class Failed(val message: String) : ImportUi
}

/**
 * Paste-a-link playlist import. Reads a public YouTube / Spotify playlist,
 * matches Spotify tracks to YouTube, saves the hits as a normal Hikari
 * playlist and opens it.
 */
@Composable
fun PlaylistImportDialog(onDone: (id: String, name: String) -> Unit, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf("") }
    var ui by remember { mutableStateOf<ImportUi>(ImportUi.Input) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun start() {
        val parsed = parsePlaylistLink(link)
        if (parsed == null) {
            ui = ImportUi.Failed("That link is not a YouTube or Spotify playlist link. Paste the full share link.")
            return
        }
        job?.cancel()
        job = scope.launch {
            ui = ImportUi.Working("Reading playlist…", 0, 1)
            try {
                val preview = fetchPlaylistPreview(parsed) { done, total ->
                    ui = ImportUi.Working("Matching songs on YouTube…", done, total)
                }
                ui = ImportUi.Preview(preview)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                ui = ImportUi.Failed(e.message ?: "Import failed.")
            }
        }
    }

    Dialog(onDismissRequest = { job?.cancel(); onDismiss() }) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(8.dp),
        ) {
            androidx.compose.material3.Card(
                Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text("Import playlist", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Spacer(Modifier.height(8.dp))
                    when (val s = ui) {
                        is ImportUi.Input -> {
                            Text(
                                "Paste a public YouTube or Spotify playlist link. Spotify songs are matched to the same song on YouTube.",
                                color = cs.onSurfaceVariant,
                                fontSize = 13.sp,
                            )
                            Spacer(Modifier.height(12.dp))
                            OutlinedTextField(
                                value = link,
                                onValueChange = { link = it },
                                singleLine = true,
                                label = { Text("Playlist link") },
                                placeholder = { Text("https://…") },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { job?.cancel(); onDismiss() }) { Text("Cancel") }
                                Spacer(Modifier.width(8.dp))
                                Button(onClick = ::start, enabled = link.isNotBlank()) { Text("Fetch") }
                            }
                        }
                        is ImportUi.Working -> {
                            Text(s.status, color = cs.onSurfaceVariant, fontSize = 13.sp)
                            Spacer(Modifier.height(12.dp))
                            if (s.total > 1) {
                                LinearProgressIndicator(
                                    progress = s.done.toFloat() / s.total.toFloat(),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Spacer(Modifier.height(8.dp))
                                Text("${s.done} of ${s.total}", color = cs.onSurfaceVariant, fontSize = 12.sp)
                            } else {
                                CircularProgressIndicator()
                            }
                            Spacer(Modifier.height(12.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { job?.cancel(); ui = ImportUi.Input }) { Text("Cancel") }
                            }
                        }
                        is ImportUi.Failed -> {
                            Text(s.message, color = cs.error, fontSize = 14.sp)
                            Spacer(Modifier.height(12.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { ui = ImportUi.Input }) { Text("Back") }
                            }
                        }
                        is ImportUi.Preview -> {
                            val hits = s.preview.tracks.count { it.song != null }
                            val total = s.preview.tracks.size
                            Text(
                                s.preview.name + " • " + s.preview.source,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                if (hits == total) "$total songs, all playable."
                                else "$hits of $total songs found on YouTube.",
                                color = cs.onSurfaceVariant,
                                fontSize = 13.sp,
                            )
                            Spacer(Modifier.height(8.dp))
                            LazyColumn(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 320.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                items(s.preview.tracks) { t ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            if (t.song != null) Icons.Filled.Check else Icons.Filled.Close,
                                            contentDescription = null,
                                            tint = if (t.song != null) cs.primary else cs.error,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                                            Text(
                                                t.artist,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                fontSize = 12.sp,
                                                color = cs.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { ui = ImportUi.Input }) { Text("Back") }
                                Spacer(Modifier.width(8.dp))
                                Button(onClick = {
                                    val songs = s.preview.tracks.mapNotNull { it.song }
                                    if (songs.isNotEmpty()) {
                                        val id = MusicStore.createPlaylistWithSongs(s.preview.name, songs)
                                        onDone(id, s.preview.name)
                                    } else {
                                        onDismiss()
                                    }
                                }) { Text("Import ($hits)") }
                            }
                        }
                    }
                }
            }
        }
    }
}
