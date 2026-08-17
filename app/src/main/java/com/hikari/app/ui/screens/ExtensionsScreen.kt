package com.hikari.app.ui.screens

import android.app.Application
import android.content.Intent
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import com.hikari.app.HikariApp
import com.hikari.app.cs3.Cs3PluginManager
import com.hikari.app.data.Cs3Repo
import com.hikari.app.data.Cs3RepoPlugin
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.ProviderType
import com.hikari.app.data.RepoLoadState
import com.hikari.app.data.Site
import com.hikari.app.net.Http
import com.hikari.app.providers.ContentProvider
import com.hikari.app.providers.ProviderManager
import com.hikari.app.ui.components.EmptyState
import com.hikari.app.web.WebViewActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ExtensionsViewModel(app: Application) : AndroidViewModel(app) {
    private val store = (app as HikariApp).store
    private val manager = (app as HikariApp).providers

    val providers: StateFlow<List<ContentProvider>> = manager.providers
    val repos = MutableStateFlow<List<Cs3Repo>>(emptyList())
    val pluginsByRepo = MutableStateFlow<Map<String, List<Cs3RepoPlugin>>>(emptyMap())
    val installedUrls = MutableStateFlow<Set<String>>(emptySet())
    val repoState = MutableStateFlow<Map<String, RepoLoadState>>(emptyMap())
    val sites = MutableStateFlow<List<Site>>(emptyList())

    init {
        viewModelScope.launch {
            manager.refresh()
            repos.value = store.repos()
            sites.value = store.sites()
            reloadInstalled()
        }
    }

    suspend fun addSite(name: String, url: String) {
        store.addSite(Site(name.ifBlank { url }, url))
        sites.value = store.sites()
    }

    suspend fun removeSite(url: String) {
        store.removeSite(url)
        sites.value = store.sites()
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
        installCs3Bytes(bytes, clean.substringAfterLast('/').ifBlank { "plugin.cs3" }, sourceUrl = clean)
    }

    suspend fun installCs3FromUri(uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        val bytes = runCatching {
            getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return@withContext Result.failure(Exception("Could not read the selected file"))
        val name = uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null } ?: "plugin.cs3"
        installCs3Bytes(bytes, name)
    }

    private suspend fun installCs3Bytes(
        bytes: ByteArray,
        rawName: String,
        sourceUrl: String? = null,
        iconUrl: String? = null,
    ): Result<Int> {
        if (bytes.size > 10 * 1024 * 1024) {
            return Result.failure(Exception("File too large (max 10MB)"))
        }
        val clean = rawName.substringAfterLast('/').ifBlank { "plugin.cs3" }
            .let { if (it.endsWith(".cs3", true)) it else "$it.cs3" }
        val dir = File(getApplication<Application>().filesDir, "cs3").apply { mkdirs() }
        val file = File(dir, clean)
        file.setWritable(true)
        file.writeBytes(bytes)

        val apis = Cs3PluginManager.reload(getApplication<Application>(), file)
        if (apis.isEmpty()) {
            file.delete()
            val detail = Cs3PluginManager.lastError?.take(600)
            return Result.failure(
                Exception(
                    if (detail.isNullOrBlank()) "No CloudStream plugin found in this .cs3 file"
                    else "No CloudStream plugin loaded:\n$detail"
                )
            )
        }
        var added = 0
        apis.forEachIndexed { i, api ->
            val name = api.name.ifBlank { clean.removeSuffix(".cs3") }
            val id = "cs3|" + clean.hashCode() + "|" + i
            store.addProvider(
                ProviderConfig(
                    id = id,
                    name = name,
                    type = ProviderType.CS3,
                    url = file.absolutePath,
                    iconUrl = iconUrl,
                    extra = sourceUrl ?: clean,
                )
            )
            added++
        }
        manager.refresh()
        reloadInstalled()
        return Result.success(added)
    }

    suspend fun reloadInstalled() {
        installedUrls.value = store.providers()
            .filter { it.type == ProviderType.CS3 && (it.extra?.startsWith("http") == true) }
            .mapNotNull { it.extra }
            .toSet()
    }

    suspend fun addCs3Repo(rawUrl: String): Result<Cs3Repo> {
        val url = rawUrl.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return Result.failure(Exception("Must start with http(s)://"))
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val text = Http.fetchStringRobust(url).getOrElse { throw it }
                val obj = JSONObject(text)
                val repo = Cs3Repo(
                    url = url,
                    name = obj.optString("name").ifBlank { url },
                    description = obj.optString("description"),
                )
                store.addCs3Repo(repo)
                repos.value = store.repos()
                repo
            }
        }
    }

    suspend fun removeCs3Repo(url: String) {
        store.removeCs3Repo(url)
        repos.value = store.repos()
        pluginsByRepo.value = pluginsByRepo.value - url
        repoState.value = repoState.value - url
    }

    suspend fun refreshRepoPlugins(repo: Cs3Repo) {
        repoState.value = repoState.value + (repo.url to RepoLoadState(loading = true, error = null))
        try {
            val plugins = withContext(Dispatchers.IO) { fetchRepoPlugins(repo.url) }
            pluginsByRepo.value = pluginsByRepo.value + (repo.url to plugins)
            repoState.value = repoState.value + (repo.url to RepoLoadState(loading = false))
        } catch (e: Exception) {
            repoState.value = repoState.value + (repo.url to RepoLoadState(loading = false, error = e.message))
        }
    }

    private fun fetchRepoPlugins(repoUrl: String): List<Cs3RepoPlugin> {
        val text = Http.fetchStringRobust(repoUrl)
            .getOrElse { throw Exception("Could not fetch repo: ${it.message}") }
        val root = JSONObject(text)
        val out = LinkedHashMap<String, Cs3RepoPlugin>()
        root.optJSONArray("plugins")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { parsePlugin(it)?.let { p -> out[p.url] = p } }
            }
        }
        root.optJSONArray("pluginLists")?.let { lists ->
            for (i in 0 until lists.length()) {
                val listUrl = lists.optString(i).ifBlank { null } ?: continue
                val listText = Http.fetchStringRobust(listUrl).getOrNull() ?: continue
                val arr = runCatching { JSONArray(listText) }.getOrNull() ?: continue
                for (j in 0 until arr.length()) {
                    arr.optJSONObject(j)?.let { parsePlugin(it)?.let { p -> out[p.url] = p } }
                }
            }
        }
        return out.values.toList()
    }

    private fun parsePlugin(o: JSONObject): Cs3RepoPlugin? {
        val name = o.optString("name").ifBlank { return null }
        val url = o.optString("url").ifBlank { return null }
        fun strings(key: String): List<String> =
            runCatching { o.getJSONArray(key) }.getOrNull()
                ?.let { a -> (0 until a.length()).mapNotNull { idx -> a.optString(idx).ifBlank { null } } }
                ?: emptyList()
        return Cs3RepoPlugin(
            name = name,
            description = o.optString("description"),
            url = url,
            iconUrl = o.optString("iconUrl").ifBlank { null },
            authors = strings("authors"),
            version = o.optInt("version", 1),
            tvTypes = strings("tvTypes"),
            fileHash = o.optString("fileHash").ifBlank { null },
        )
    }

    suspend fun installCs3Plugin(plugin: Cs3RepoPlugin): Result<Int> = withContext(Dispatchers.IO) {
        val bytes = Http.fetchBytesRobust(plugin.url)
            ?: return@withContext Result.failure(Exception("Download failed — check the URL"))
        val hash = plugin.fileHash
        if (hash != null && hash.startsWith("sha256-")) {
            val expected = hash.removePrefix("sha256-").lowercase()
            val actual = sha256Hex(bytes)
            if (actual != expected) {
                return@withContext Result.failure(
                    Exception("Checksum mismatch — the plugin file is corrupted or modified")
                )
            }
        }
        val fileName = plugin.name.substringBeforeLast('.').takeIf { it.isNotBlank() } ?: "plugin"
        installCs3Bytes(bytes, "$fileName.cs3", sourceUrl = plugin.url, iconUrl = plugin.iconUrl)
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    suspend fun uninstallCs3Plugin(pluginUrl: String) {
        val all = store.providers()
        val paths = all.filter { it.extra == pluginUrl }.map { it.url }.toSet()
        store.saveProviders(all.filter { it.extra != pluginUrl })
        manager.refresh()
        reloadInstalled()
        withContext(Dispatchers.IO) {
            val remaining = store.providers().map { it.url }.toSet()
            val base = getApplication<Application>().filesDir.absolutePath
            paths.forEach { p ->
                if (p.startsWith(base) && p !in remaining) {
                    runCatching { File(p).delete() }
                }
            }
        }
    }
}

@Composable
fun ExtensionsScreen() {
    val vm: ExtensionsViewModel = viewModel()
    val providers by vm.providers.collectAsState()
    val scope = rememberCoroutineScope()

    var openRepoUrl by remember { mutableStateOf<String?>(null) }
    var showStremio by remember { mutableStateOf(false) }
    var showScraper by remember { mutableStateOf(false) }
    var showCs3Url by remember { mutableStateOf(false) }
    var showRepoDialog by remember { mutableStateOf(false) }
    var stremioUrl by remember { mutableStateOf("") }
    var scraperJson by remember { mutableStateOf("") }
    var cs3Url by remember { mutableStateOf("") }
    var repoUrl by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var busyMsg by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var successMsg by remember { mutableStateOf<String?>(null) }

    val repos by vm.repos.collectAsState()
    val pluginsByRepo by vm.pluginsByRepo.collectAsState()
    val installed by vm.installedUrls.collectAsState()
    val repoState by vm.repoState.collectAsState()
    val sites by vm.sites.collectAsState()
    val openRepo = repos.firstOrNull { it.url == openRepoUrl }
    val context = LocalContext.current

    var showSite by remember { mutableStateOf(false) }
    var siteName by remember { mutableStateOf("") }
    var siteUrl by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        vm.repos.value.forEach { repo -> vm.refreshRepoPlugins(repo) }
    }

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

    fun installPlugin(p: Cs3RepoPlugin) {
        scope.launch {
            busy = true
            busyMsg = "Installing ${p.name}…"
            errorMsg = null
            successMsg = null
            val r = vm.installCs3Plugin(p)
            busy = false
            r.onSuccess { n ->
                successMsg = "Installed ${p.name} ($n provider${if (n == 1) "" else "s"})"
            }
            r.onFailure { errorMsg = it.message }
        }
    }

    fun uninstallPlugin(p: Cs3RepoPlugin) {
        scope.launch {
            busy = true
            busyMsg = "Uninstalling ${p.name}…"
            errorMsg = null
            successMsg = null
            vm.uninstallCs3Plugin(p.url)
            busy = false
            successMsg = "Uninstalled ${p.name}"
        }
    }

    if (openRepo != null) {
        RepoPluginsView(
            repo = openRepo,
            plugins = pluginsByRepo[openRepo.url] ?: emptyList(),
            state = repoState[openRepo.url] ?: RepoLoadState(loading = true),
            installedUrls = installed,
            busy = busy,
            busyMsg = busyMsg,
            successMsg = successMsg,
            errorMsg = errorMsg,
            onBack = { openRepoUrl = null; errorMsg = null; successMsg = null },
            onInstall = ::installPlugin,
            onUninstall = ::uninstallPlugin,
        )
    } else {
        RepoBrowserView(
            repos = repos,
            pluginsByRepo = pluginsByRepo,
            repoState = repoState,
            providers = providers,
            sites = sites,
            busy = busy,
            busyMsg = busyMsg,
            successMsg = successMsg,
            errorMsg = errorMsg,
            onOpenRepo = { repo ->
                openRepoUrl = repo.url
                errorMsg = null
                successMsg = null
                if (repoState[repo.url] == null) scope.launch { vm.refreshRepoPlugins(repo) }
            },
            onAddRepo = { errorMsg = null; successMsg = null; showRepoDialog = true },
            onAddStremio = { errorMsg = null; showStremio = true },
            onAddScraper = { errorMsg = null; showScraper = true },
            onAddCs3Url = { errorMsg = null; showCs3Url = true },
            onAddSite = { errorMsg = null; showSite = true },
            onOpenSite = { site ->
                context.startActivity(
                    Intent(context, WebViewActivity::class.java).apply {
                        putExtra("url", site.url)
                        putExtra("title", site.name)
                    }
                )
            },
            onRemoveSite = { url ->
                scope.launch {
                    vm.removeSite(url)
                    successMsg = "Website removed"
                }
            },
            onPickCs3File = {
                errorMsg = null
                successMsg = null
                filePicker.launch(arrayOf("application/octet-stream", "*/*"))
            },
            onRemoveRepo = { url ->
                scope.launch {
                    vm.removeCs3Repo(url)
                    if (openRepoUrl == url) openRepoUrl = null
                    successMsg = "Removed repo"
                }
            },
            onToggleProvider = { id, enabled -> scope.launch { vm.toggle(id, enabled) } },
            onDeleteProvider = { id -> scope.launch { vm.remove(id) } },
        )
    }

    if (showRepoDialog) {
        AlertDialog(
            onDismissRequest = { if (!busy) showRepoDialog = false },
            title = { Text("Add plugin repo") },
            text = {
                Column {
                    Text(
                        "Paste a CloudStream-style repo URL (a repo.json). For example:\n" +
                            "https://raw.githubusercontent.com/codegeasse1/codegeasse-cloudstream-repos/builds/repo.json"
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = repoUrl,
                        onValueChange = { repoUrl = it },
                        placeholder = { Text("https://…/repo.json") },
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
                            busyMsg = "Fetching repo…"
                            errorMsg = null
                            successMsg = null
                            val r = vm.addCs3Repo(repoUrl)
                            busy = false
                            r.onSuccess { repo ->
                                showRepoDialog = false
                                repoUrl = ""
                                successMsg = "Added repo: ${repo.name}"
                                vm.refreshRepoPlugins(repo)
                            }
                            r.onFailure { errorMsg = it.message }
                        }
                    }
                ) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { if (!busy) showRepoDialog = false }) { Text("Cancel") }
            }
        )
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

    if (showSite) {
        AlertDialog(
            onDismissRequest = { if (!busy) showSite = false },
            title = { Text("Add website") },
            text = {
                Column {
                    Text(
                        "Paste the URL of any movie/streaming website. It opens in an " +
                            "ad-free web view — ads, trackers and popups are blocked, " +
                            "and videos can be handed to the built-in player."
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = siteName,
                        onValueChange = { siteName = it },
                        placeholder = { Text("Name (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = siteUrl,
                        onValueChange = { siteUrl = it },
                        placeholder = { Text("https://example.com") },
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
                        val clean = siteUrl.trim()
                        val withScheme = if (clean.startsWith("http://") || clean.startsWith("https://"))
                            clean
                        else
                            "https://$clean"
                        if (withScheme.isBlank() || withScheme == "https://") {
                            errorMsg = "Enter a valid URL"
                        } else {
                            scope.launch {
                                busy = true
                                busyMsg = "Adding website…"
                                errorMsg = null
                                successMsg = null
                                vm.addSite(siteName.trim(), withScheme)
                                busy = false
                                showSite = false
                                siteUrl = ""
                                siteName = ""
                                successMsg = "Website added"
                            }
                        }
                    }
                ) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { if (!busy) showSite = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp)
    )
}

@Composable
private fun RepoBrowserView(
    repos: List<Cs3Repo>,
    pluginsByRepo: Map<String, List<Cs3RepoPlugin>>,
    repoState: Map<String, RepoLoadState>,
    providers: List<ContentProvider>,
    sites: List<Site>,
    busy: Boolean,
    busyMsg: String,
    successMsg: String?,
    errorMsg: String?,
    onOpenRepo: (Cs3Repo) -> Unit,
    onAddRepo: () -> Unit,
    onAddStremio: () -> Unit,
    onAddScraper: () -> Unit,
    onAddCs3Url: () -> Unit,
    onPickCs3File: () -> Unit,
    onRemoveRepo: (String) -> Unit,
    onToggleProvider: (String, Boolean) -> Unit,
    onDeleteProvider: (String) -> Unit,
    onAddSite: () -> Unit,
    onOpenSite: (Site) -> Unit,
    onRemoveSite: (String) -> Unit,
) {
    var extFilter by remember { mutableStateOf("") }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            Button(
                onClick = onAddRepo,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Add plugin repo")
            }
        }
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onAddStremio,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Add Stremio addon")
                }
                OutlinedButton(
                    onClick = onAddScraper,
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
                    onClick = onAddCs3Url,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Install .cs3 from URL")
                }
                OutlinedButton(
                    onClick = onPickCs3File,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Build, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Pick .cs3 file")
                }
            }
        }
        item {
            Button(
                onClick = onAddSite,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Add website (ad-free web view)")
            }
        }
        item { SectionHeader("Websites") }
        if (sites.isEmpty()) {
            item {
                EmptyState(
                    title = "No websites yet",
                    subtitle = "Add any movie/streaming website and it opens in an ad-free web view — ads, trackers and popups blocked, with one-tap video playback in the player.",
                    actionLabel = null,
                    action = null
                )
            }
        }
        items(sites, key = { it.url }) { site ->
            SiteRow(
                site = site,
                onOpen = { onOpenSite(site) },
                onRemove = { onRemoveSite(site.url) }
            )
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

        item { SectionHeader("Plugin repos") }
        if (repos.isEmpty()) {
            item {
                EmptyState(
                    title = "No plugin repos yet",
                    subtitle = "Add a CloudStream-style repo to browse and install extensions.",
                    actionLabel = null,
                    action = null
                )
            }
        }
        items(repos, key = { it.url }) { repo ->
            RepoCard(
                repo = repo,
                pluginCount = (pluginsByRepo[repo.url] ?: emptyList()).size,
                state = repoState[repo.url],
                onClick = { onOpenRepo(repo) },
                onRemoveRepo = { onRemoveRepo(repo.url) }
            )
        }

        item { SectionHeader("Installed extensions") }
        item {
            OutlinedTextField(
                value = extFilter,
                onValueChange = { extFilter = it },
                placeholder = { Text("Search installed extensions…") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
        val filteredProviders = providers.filter {
            extFilter.isBlank() || it.config.name.contains(extFilter, ignoreCase = true)
        }
        if (filteredProviders.isEmpty()) {
            item {
                EmptyState(
                    title = if (providers.isEmpty()) "No extensions yet" else "No matches",
                    subtitle = if (providers.isEmpty())
                        "Add a plugin repo (CloudStream-style), a Stremio addon, a universal scraper, or a single .cs3 file."
                    else
                        "No installed extension matches \"$extFilter\".",
                    actionLabel = null,
                    action = null
                )
            }
        }
        items(filteredProviders, key = { it.config.id }) { p ->
            ProviderCard(
                p = p,
                status = pluginStatus(p),
                onToggle = { enabled -> onToggleProvider(p.config.id, enabled) },
                onDelete = { onDeleteProvider(p.config.id) }
            )
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun RepoPluginsView(
    repo: Cs3Repo,
    plugins: List<Cs3RepoPlugin>,
    state: RepoLoadState,
    installedUrls: Set<String>,
    busy: Boolean,
    busyMsg: String,
    successMsg: String?,
    errorMsg: String?,
    onBack: () -> Unit,
    onInstall: (Cs3RepoPlugin) -> Unit,
    onUninstall: (Cs3RepoPlugin) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Column(Modifier.weight(1f)) {
                Text(
                    repo.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (repo.description.isNotBlank()) {
                    Text(
                        repo.description,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Text(
                "${plugins.size} plugin${if (plugins.size == 1) "" else "s"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        HorizontalDivider()
        if (busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(
                busyMsg,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(16.dp)
            )
        }
        successMsg?.let { msg ->
            Text(
                msg,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
        errorMsg?.let { msg ->
            Text(
                msg,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
        LazyColumn(
            Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)
        ) {
            when {
                state.loading && plugins.isEmpty() -> item {
                    Text(
                        "Loading plugins…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                }
                state.error != null -> item {
                    Text(
                        state.error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                }
                plugins.isEmpty() -> item {
                    Text(
                        "No plugins found in this repo.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                }
                else -> items(plugins, key = { it.url }) { p ->
                    PluginRow(
                        p = p,
                        installed = p.url in installedUrls,
                        onInstall = { onInstall(p) },
                        onUninstall = { onUninstall(p) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ExtensionIcon(url: String?, modifier: Modifier = Modifier) {
    val safe = url
        ?.replace("%size%", "48")
        ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    if (safe == null) {
        Icon(
            Icons.Filled.Extension,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = modifier
        )
        return
    }
    SubcomposeAsyncImage(
        model = safe,
        contentDescription = null,
        modifier = modifier
    ) {
        when (painter.state) {
            is AsyncImagePainter.State.Error, is AsyncImagePainter.State.Loading ->
                Icon(
                    Icons.Filled.Extension,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = modifier
                )
            else -> SubcomposeAsyncImageContent()
        }
    }
}

@Composable
private fun ProviderCard(
    p: ContentProvider,
    status: String?,
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
                ExtensionIcon(
                    url = p.config.iconUrl,
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
                if (status != null) {
                    Text(
                        status,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
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

@Composable
private fun RepoCard(
    repo: Cs3Repo,
    pluginCount: Int,
    state: RepoLoadState?,
    onClick: () -> Unit,
    onRemoveRepo: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.FolderOpen,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    repo.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    when {
                        state?.loading == true -> "Loading plugins…"
                        pluginCount > 0 -> "${pluginCount} plugin${if (pluginCount == 1) "" else "s"}"
                        else -> repo.description.ifBlank { "No plugins found" }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            IconButton(onClick = onRemoveRepo) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Remove repo",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun PluginRow(
    p: Cs3RepoPlugin,
    installed: Boolean,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            ExtensionIcon(
                url = p.iconUrl,
                modifier = Modifier.size(28.dp)
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                p.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val meta = listOfNotNull(
                p.description.ifBlank { null },
                p.authors.joinToString(", ").ifBlank { null },
                if (p.version > 0) "v${p.version}" else null,
                p.tvTypes.joinToString(", ").ifBlank { null },
            ).joinToString(" · ")
            Text(
                meta,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        if (installed) {
            TextButton(onClick = onUninstall) {
                Text("Uninstall", color = MaterialTheme.colorScheme.error)
            }
        } else {
            Button(onClick = onInstall) {
                Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Install")
            }
        }
    }
}

private fun pluginStatus(p: ContentProvider): String? {
    if (p.config.type != ProviderType.CS3) return null
    val err = com.hikari.app.cs3.Cs3MainApiProvider.catalogErrors[p.config.id]
    if (err != null) return err.take(200)
    if (!File(p.config.url).exists()) return "Plugin file missing — reinstall this extension"
    return null
}

@Composable
private fun SiteRow(
    site: Site,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
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
                Icon(
                    Icons.Filled.Public,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    site.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    site.url,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onOpen) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Open")
            }
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Remove website",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
