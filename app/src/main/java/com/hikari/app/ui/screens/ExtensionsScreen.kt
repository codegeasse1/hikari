package com.hikari.app.ui.screens

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.hikari.app.HikariApp
import com.hikari.app.cs3.Cs3PluginManager
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.ProviderType
import com.hikari.app.net.Http
import com.hikari.app.providers.ContentProvider
import com.hikari.app.providers.ProviderManager
import com.hikari.app.ui.components.EmptyState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class ExtensionsViewModel(app: Application) : AndroidViewModel(app) {
    private val store = (app as HikariApp).store
    private val manager = (app as HikariApp).providers

    val providers: StateFlow<List<ContentProvider>> = manager.providers

    init {
        viewModelScope.launch { manager.refresh() }
    }

    suspend fun addStremio(url: String): Result<String> = withContext(Dispatchers.IO) {
        val clean = url.trim().trimEnd('/')
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
            return@withContext Result.failure(Exception("Must start with http(s)://"))
        }
        val manifest = Http.getString("$clean/manifest.json")
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return@withContext Result.failure(Exception("Not a Stremio addon (no manifest.json found)"))
        val name = manifest.optString("name").ifBlank {
            clean.removePrefix("https://").removePrefix("http://")
        }
        val id = "stremio|" + (clean.hashCode().toLong().let { if (it < 0) -it else it })
        store.addProvider(ProviderConfig(id, name, ProviderType.STREMIO, clean))
        manager.refresh()
        Result.success(name)
    }

    suspend fun addUniversal(json: String): Result<String> = withContext(Dispatchers.IO) {
        val obj = runCatching { JSONObject(json) }.getOrElse {
            return@withContext Result.failure(Exception("Invalid JSON: ${it.message}"))
        }
        val name = obj.optString("name").ifBlank {
            return@withContext Result.failure(Exception("Config needs a \"name\""))
        }
        val base = obj.optString("baseUrl").ifBlank {
            return@withContext Result.failure(Exception("Config needs a \"baseUrl\""))
        }
        val id = "uni|" + (base.hashCode().toLong().let { if (it < 0) -it else it })
        store.addProvider(ProviderConfig(id, name, ProviderType.UNIVERSAL, base, extra = json))
        manager.refresh()
        Result.success(name)
    }

    suspend fun toggle(id: String, enabled: Boolean) {
        store.setEnabled(id, enabled)
    }

    suspend fun remove(id: String) {
        store.removeProvider(id)
        manager.refresh()
    }

    suspend fun installCs3FromUrl(url: String): Result<Int> = withContext(Dispatchers.IO) {
        val clean = url.trim()
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
            return@withContext Result.failure(Exception("Must start with http(s)://"))
        }
        val bytes = Http.getBytes(clean)
            ?: return@withContext Result.failure(Exception("Download failed — check the URL"))
        installCs3Bytes(bytes, clean.substringAfterLast('/').ifBlank { "plugin.cs3" })
    }

    suspend fun installCs3FromUri(uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        val bytes = runCatching {
            application.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return@withContext Result.failure(Exception("Could not read the selected file"))
        val name = uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null } ?: "plugin.cs3"
        installCs3Bytes(bytes, name)
    }

    private suspend fun installCs3Bytes(bytes: ByteArray, rawName: String): Result<Int> {
        if (bytes.size > 10 * 1024 * 1024) {
            return Result.failure(Exception("File too large (max 10MB)"))
        }
        val clean = rawName.substringAfterLast('/').ifBlank { "plugin.cs3" }
            .let { if (it.endsWith(".cs3", true)) it else "$it.cs3" }
        val dir = File(application.filesDir, "cs3").apply { mkdirs() }
        val file = File(dir, clean)
        file.writeBytes(bytes)

        val apis = Cs3PluginManager.reload(application, file)
        if (apis.isEmpty()) {
            file.delete()
            return Result.failure(Exception("No CloudStream plugin found in this .cs3 file"))
        }
        var added = 0
        apis.forEachIndexed { i, api ->
            val name = api.name.ifBlank { clean.removeSuffix(".cs3") }
            val id = "cs3|" + clean.hashCode() + "|" + i
            store.addProvider(ProviderConfig(id, name, ProviderType.CS3, file.absolutePath, extra = clean))
            added++
        }
        manager.refresh()
        return Result.success(added)
    }
}

@Composable
fun ExtensionsScreen() {
    val vm: ExtensionsViewModel = viewModel()
    val providers by vm.providers.collectAsState()
    val scope = rememberCoroutineScope()

    var showStremio by remember { mutableStateOf(false) }
    var showScraper by remember { mutableStateOf(false) }
    var showCs3Url by remember { mutableStateOf(false) }
    var stremioUrl by remember { mutableStateOf("") }
    var scraperJson by remember { mutableStateOf("") }
    var cs3Url by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var busyMsg by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var successMsg by remember { mutableStateOf<String?>(null) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                busyMsg = "Installing .cs3 plugin…"
                errorMsg = null
                successMsg = null
                val r = vm.installCs3FromUri(uri)
                busy = false
                r.onSuccess { n -> successMsg = "Installed $n provider(s)" }
                r.onFailure { errorMsg = it.message }
            }
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { errorMsg = null; showStremio = true },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Add Stremio addon")
                }
                OutlinedButton(
                    onClick = { errorMsg = null; showScraper = true },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Build, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Add scraper")
                }
            }
        }
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { errorMsg = null; showCs3Url = true },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Install .cs3 from URL")
                }
                OutlinedButton(
                    onClick = {
                        errorMsg = null
                        successMsg = null
                        filePicker.launch(arrayOf("application/octet-stream", "*/*"))
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Build, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Pick .cs3 file")
                }
            }
        }
        item {
            Card(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "CloudStream .cs3 plugins",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Compiled CloudStream extensions run natively in Hikari — install any .cs3 file from your provider repo (e.g. JustAnimeProvider.cs3).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (busy) {
            item {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    busyMsg,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
        successMsg?.let { msg ->
            item {
                Text(
                    msg,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
        }
        errorMsg?.let { msg ->
            item {
                Text(
                    msg,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
        }
        if (providers.isEmpty() && !busy) {
            item {
                EmptyState(
                    title = "No extensions yet",
                    subtitle = "Add a Stremio addon, a universal scraper, or install a CloudStream .cs3 plugin.",
                    actionLabel = null,
                    action = null
                )
            }
        }
        items(providers, key = { it.config.id }) { p ->
            ProviderCard(
                p = p,
                onToggle = { enabled -> scope.launch { vm.toggle(p.config.id, enabled) } },
                onDelete = { scope.launch { vm.remove(p.config.id) } }
            )
        }
        item { Spacer(Modifier.height(8.dp)) }
    }

    if (showStremio) {
        AlertDialog(
            onDismissRequest = { if (!busy) showStremio = false },
            title = { Text("Add Stremio addon") },
            text = {
                Column {
                    Text("Paste the addon URL — it must serve a manifest.json.")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = stremioUrl,
                        onValueChange = { stremioUrl = it },
                        placeholder = { Text("https://addon.example.com") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    errorMsg?.let {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            busyMsg = "Fetching addon manifest…"
                            errorMsg = null
                            val r = vm.addStremio(stremioUrl)
                            busy = false
                            r.onSuccess {
                                showStremio = false
                                stremioUrl = ""
                            }
                            r.onFailure { errorMsg = it.message }
                        }
                    }
                ) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { if (!busy) showStremio = false }) { Text("Cancel") }
            }
        )
    }

    if (showScraper) {
        AlertDialog(
            onDismissRequest = { if (!busy) showScraper = false },
            title = { Text("Add universal scraper") },
            text = {
                Column {
                    Text("Paste the JSON config (name + baseUrl + search/episodes/streams rules).")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = scraperJson,
                        onValueChange = { scraperJson = it },
                        placeholder = { Text("{\n  \"name\": \"MySite\",\n  \"baseUrl\": \"https://…\",\n  …\n}") },
                        minLines = 6,
                        maxLines = 12,
                        modifier = Modifier.fillMaxWidth()
                    )
                    errorMsg?.let {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            busyMsg = "Adding scraper…"
                            errorMsg = null
                            val r = vm.addUniversal(scraperJson)
                            busy = false
                            r.onSuccess {
                                showScraper = false
                                scraperJson = ""
                            }
                            r.onFailure { errorMsg = it.message }
                        }
                    }
                ) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { if (!busy) showScraper = false }) { Text("Cancel") }
            }
        )
    }

    if (showCs3Url) {
        AlertDialog(
            onDismissRequest = { if (!busy) showCs3Url = false },
            title = { Text("Install .cs3 plugin") },
            text = {
                Column {
                    Text("Paste a direct link to a compiled CloudStream .cs3 file.")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = cs3Url,
                        onValueChange = { cs3Url = it },
                        placeholder = { Text("https://…/JustAnimeProvider.cs3") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    errorMsg?.let {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            busyMsg = "Downloading and installing…"
                            errorMsg = null
                            successMsg = null
                            val r = vm.installCs3FromUrl(cs3Url)
                            busy = false
                            r.onSuccess { n ->
                                showCs3Url = false
                                cs3Url = ""
                                successMsg = "Installed $n provider(s)"
                            }
                            r.onFailure { errorMsg = it.message }
                        }
                    }
                ) { Text("Install") }
            },
            dismissButton = {
                TextButton(onClick = { if (!busy) showCs3Url = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun ProviderCard(
    p: ContentProvider,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = p.config.iconUrl,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    p.config.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    p.config.type.name.lowercase().replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Switch(checked = p.config.enabled, onCheckedChange = onToggle)
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Remove",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
