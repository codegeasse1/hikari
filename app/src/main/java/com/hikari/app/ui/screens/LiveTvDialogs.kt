package com.hikari.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hikari.app.i18n.tr
import com.hikari.app.iptv.LiveTvEngine
import com.hikari.app.tv.tvTextFieldKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Adds a plain M3U link (or file path) as a Live TV source. */
@Composable
fun AddM3uDialog(onDone: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(tr("Add M3U playlist")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    tr("Paste an M3U playlist link — an Xtream panel's get.php link works too, with its guide."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text(tr("Name (optional)")) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().tvTextFieldKeys(name),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = url, onValueChange = { url = it },
                    label = { Text(tr("Playlist link")) }, singleLine = false, maxLines = 3,
                    modifier = Modifier.fillMaxWidth().tvTextFieldKeys(url),
                )
                if (error.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (busy) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            tr("Reading the playlist…"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    if (url.trim().isBlank()) {
                        error = tr("Paste the playlist link first.")
                        return@TextButton
                    }
                    busy = true
                    error = ""
                    scope.launch {
                        val err = withContext(Dispatchers.IO) {
                            runCatching {
                                LiveTvEngine.addM3u(name.trim(), url.trim())
                                null
                            }.getOrElse { it.message ?: "failed" }
                        }
                        busy = false
                        if (err == null) {
                            Toast.makeText(context, tr("Playlist added"), Toast.LENGTH_SHORT).show()
                            onDone()
                        } else {
                            error = err
                        }
                    }
                }
            ) { Text(tr("Add")) }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text(tr("Cancel")) }
        },
    )
}

/** Adds an Xtream Codes login (server + username + password). */
@Composable
fun AddXtreamDialog(onDone: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var streamFormat by remember { mutableStateOf("auto") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(tr("Add Xtream login")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    tr("Paste the server link your provider gave you — it fills in the rest. The guide comes with the login."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text(tr("Name (optional)")) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().tvTextFieldKeys(name),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = server, onValueChange = { server = it },
                    label = { Text(tr("Server (http://host:port)")) }, singleLine = false, maxLines = 2,
                    modifier = Modifier.fillMaxWidth().tvTextFieldKeys(server),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = username, onValueChange = { username = it },
                    label = { Text(tr("Username")) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().tvTextFieldKeys(username),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text(tr("Password")) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().tvTextFieldKeys(password),
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Stream format"), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.width(8.dp))
                    listOf("auto", "m3u8", "ts").forEach { f ->
                        TextButton(onClick = { streamFormat = f }) {
                            Text(
                                if (f == "auto") tr("Auto") else f.uppercase(),
                                color = if (streamFormat == f) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (error.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (busy) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            tr("Checking the login…"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    // A pasted get.php link carries everything (server/user/pass).
                    val link = server.trim()
                    var srv = server.trim()
                    var user = username.trim()
                    var pass = password
                    if (("username=" in link || "password=" in link)) {
                        com.hikari.app.iptv.XtreamApi.credentialsFromLink(link)?.let { (u, p) ->
                            user = u
                            pass = p
                        }
                        if ("/get.php" in link) srv = link.substringBefore("/get.php")
                        else if (link.contains("?")) srv = link.substringBefore("?")
                    }
                    if (srv.isBlank() || user.isBlank() || pass.isBlank()) {
                        error = tr("Fill in the server, username and password.")
                        return@TextButton
                    }
                    busy = true
                    error = ""
                    scope.launch {
                        val err = withContext(Dispatchers.IO) {
                            runCatching {
                                LiveTvEngine.addXtream(name.trim(), srv, user, pass, streamFormat)
                                null
                            }.getOrElse { it.message ?: "failed" }
                        }
                        busy = false
                        if (err == null) {
                            Toast.makeText(context, tr("Xtream login added"), Toast.LENGTH_SHORT).show()
                            onDone()
                        } else {
                            error = err
                        }
                    }
                }
            ) { Text(tr("Add")) }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text(tr("Cancel")) }
        },
    )
}

/** Adds an XMLTV guide source (plain or .gz link). */
@Composable
fun AddEpgDialog(onDone: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(tr("Add guide source")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    tr("An XMLTV guide link (it may end in .gz). Shows are matched to your channels by their tvg-id or name."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text(tr("Name (optional)")) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().tvTextFieldKeys(name),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = url, onValueChange = { url = it },
                    label = { Text(tr("Guide link")) }, singleLine = false, maxLines = 3,
                    modifier = Modifier.fillMaxWidth().tvTextFieldKeys(url),
                )
                if (error.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (busy) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            tr("Reading the guide…"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    if (url.trim().isBlank()) {
                        error = tr("Paste the guide link first.")
                        return@TextButton
                    }
                    busy = true
                    error = ""
                    scope.launch {
                        val err = withContext(Dispatchers.IO) {
                            runCatching {
                                LiveTvEngine.addEpg(name.trim(), url.trim())
                                null
                            }.getOrElse { it.message ?: "failed" }
                        }
                        busy = false
                        if (err == null) {
                            Toast.makeText(context, tr("Guide added"), Toast.LENGTH_SHORT).show()
                            onDone()
                        } else {
                            error = err
                        }
                    }
                }
            ) { Text(tr("Add")) }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text(tr("Cancel")) }
        },
    )
}
