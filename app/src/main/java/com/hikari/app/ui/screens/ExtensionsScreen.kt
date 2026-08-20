package com.hikari.app.ui.screens

import android.app.Application
import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.layout.ContentScale
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
import com.hikari.app.hiki.HikariPluginManager
import com.hikari.app.data.Cs3Repo
import com.hikari.app.data.Cs3RepoPlugin
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.ProviderType
import com.hikari.app.data.RepoKind
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
import kotlinx.coroutines.withTimeoutOrNull
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
        var clean = url.trim().trimEnd('/')
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
            clean = "https://$clean"
        }
        if (clean.length < 10 || !clean.contains(".")) {
            return@withContext Result.failure(Exception("That doesn't look like a URL"))
        }
        // Accept the URL with or without the /manifest.json suffix (users often
        // paste the full manifest URL), and fall back to plain http if the
        // https fetch fails.
        val manifestUrl = if (clean.lowercase().endsWith("/manifest.json")) clean
        else "$clean/manifest.json"
        val text = listOf(manifestUrl, manifestUrl.replaceFirst("https://", "http://"))
            .firstNotNullOfOrNull { Http.getString(it) }
            ?: return@withContext Result.failure(
                Exception("Could not fetch $manifestUrl — check the address or try again")
            )
        val manifest = runCatching { JSONObject(text) }.getOrElse {
            return@withContext Result.failure(
                Exception("Not a Stremio addon — the response from $manifestUrl isn't JSON: ${text.take(80)}")
            )
        }
        val name = manifest.optString("name").ifBlank {
            clean.removePrefix("https://").removePrefix("http://")
        }
        val iconUrl = manifest.optString("icon").ifBlank { null }
        val id = "stremio|" + (clean.hashCode().toLong().let { if (it < 0) -it else it })
        store.addProvider(ProviderConfig(id, name, ProviderType.STREMIO, clean, iconUrl = iconUrl))
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
        // The providers StateFlow is the source of truth for the switch state —
        // without a refresh the toggle saves but the UI never moves.
        manager.refresh()
    }

    suspend fun remove(id: String) {
        val target = store.providers().firstOrNull { it.id == id }
        store.removeProvider(id)
        manager.refresh()
        if (target != null && target.type == ProviderType.HIKARI &&
            target.url.startsWith(getApplication<Application>().filesDir.absolutePath)
        ) {
            val stillUsed = store.providers().any { it.url == target.url }
            if (!stillUsed) {
                withContext(Dispatchers.IO) { runCatching { File(target.url).delete() } }
            }
        }
    }

    suspend fun installHikiFromUrl(url: String): Result<Int> = withContext(Dispatchers.IO) {
        val clean = url.trim()
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
            return@withContext Result.failure(Exception("Must start with http(s)://"))
        }
        val bytes = Http.getBytes(clean)
            ?: return@withContext Result.failure(Exception("Download failed — check the URL"))
        installHikiBytes(bytes, clean.substringAfterLast('/').ifBlank { "extension.hiki" }, sourceUrl = clean)
    }

    suspend fun installHikiFromUri(uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        val bytes = runCatching {
            getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return@withContext Result.failure(Exception("Could not read the selected file"))
        val name = uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null } ?: "extension.hiki"
        installHikiBytes(bytes, name)
    }

    private suspend fun installHikiBytes(
        bytes: ByteArray,
        rawName: String,
        sourceUrl: String? = null,
    ): Result<Int> {
        if (bytes.size > 10 * 1024 * 1024) {
            return Result.failure(Exception("File too large (max 10MB)"))
        }
        val clean = rawName.substringAfterLast('/').ifBlank { "extension.hiki" }
            .let { if (it.endsWith(".hiki", true)) it else "$it.hiki" }
        val dir = File(getApplication<Application>().filesDir, "hiki").apply { mkdirs() }
        val file = File(dir, clean)
        file.setWritable(true)
        val wrote = runCatching { file.writeBytes(bytes) }
        if (wrote.isFailure) {
            return Result.failure(Exception("Could not write extension file: ${wrote.exceptionOrNull()?.message}"))
        }

        val providers = HikariPluginManager.reload(getApplication<Application>(), file)
        if (providers.isEmpty()) {
            file.delete()
            val detail = HikariPluginManager.lastError?.take(600)
            return Result.failure(
                Exception(
                    if (detail.isNullOrBlank()) "No Hikari extension found in this .hiki file"
                    else "No Hikari extension loaded:\n$detail"
                )
            )
        }
        var added = 0
        providers.forEachIndexed { i, p ->
            val id = "hiki|" + clean.hashCode() + "|" + i
            val extra = (sourceUrl ?: "") + "|" + i
            store.addProvider(
                ProviderConfig(
                    id = id,
                    name = p.name,
                    type = ProviderType.HIKARI,
                    url = file.absolutePath,
                    iconUrl = p.iconUrl,
                    extra = extra,
                )
            )
            added++
        }
        manager.refresh()
        reloadInstalled()
        return Result.success(added)
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
        val wrote = runCatching { file.writeBytes(bytes) }
        if (wrote.isFailure) {
            return Result.failure(Exception("Could not write plugin file: ${wrote.exceptionOrNull()?.message}"))
        }

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
        installedUrls.value = buildSet {
            store.providers().forEach { p ->
                val extra = p.extra ?: return@forEach
                when (p.type) {
                    ProviderType.CS3 -> if (extra.startsWith("http")) add(extra)
                    ProviderType.HIKARI -> if (extra.startsWith("http")) add(extra.substringBeforeLast('|'))
                    else -> {}
                }
            }
        }
    }

    suspend fun addCs3Repo(rawUrl: String): Result<Cs3Repo> = addRepo(rawUrl, RepoKind.CS3)

    suspend fun addHikiRepo(rawUrl: String): Result<Cs3Repo> = addRepo(rawUrl, RepoKind.HIKARI)

    /** The URL that actually served the last [fetchRepoRaw] — repos are stored
     *  under their raw form so refreshing works with the plain http client. */
    @Volatile
    private var lastGoodRepoUrl: String = ""

    /** Fetches a repo.json, trying the pasted URL first and then the raw-GitHub
     *  variants for `github.com/o/r` links users commonly paste (the HTML page
     *  would never parse as JSON). Remembers which variant succeeded. */
    private fun fetchRepoRaw(url: String): Result<String> {
        val variants = repoUrlVariants(url)
        if (variants.isEmpty()) {
            lastGoodRepoUrl = url
            return Http.fetchStringRobust(url)
        }
        for (candidate in variants) {
            val r = Http.fetchStringRobust(candidate)
            if (r.isSuccess) {
                lastGoodRepoUrl = candidate
                return r
            }
        }
        // last resort: the pasted URL as-is (a non-main/mixed-branch repo.json)
        lastGoodRepoUrl = url
        return Http.fetchStringRobust(url)
    }

    private fun repoUrlVariants(raw: String): List<String> {
        val t = raw.trim().trimEnd('/')
        if (!t.startsWith("http://") && !t.startsWith("https://")) return emptyList()
        val m = Regex("https?://(?:www\\.)?github\\.com/([^/]+)/([^/]+)").find(t) ?: return emptyList()
        val owner = m.groupValues[1]
        val repo = m.groupValues[2]
        return listOf(
            "https://raw.githubusercontent.com/$owner/$repo/main/repo.json",
            "https://raw.githubusercontent.com/$owner/$repo/master/repo.json",
            "https://raw.githubusercontent.com/$owner/$repo/builds/repo.json",
        )
    }

    private suspend fun addRepo(rawUrl: String, kind: RepoKind): Result<Cs3Repo> {
        val url = rawUrl.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return Result.failure(Exception("Must start with http(s)://"))
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val text = fetchRepoRaw(url).getOrElse { throw it }
                val obj = JSONObject(text)
                val repo = Cs3Repo(
                    url = lastGoodRepoUrl,
                    name = niceRepoName(url, obj.optString("name")),
                    description = obj.optString("description"),
                    kind = kind,
                )
                store.addCs3Repo(repo)
                // A "Mega"-style bundle repo isn't a plugin repo — its single
                // plugin only exists to add every CloudStream repo from the
                // canonical repos-db.json (and relies on the real CloudStream
                // RepositoryManager, which Hikari doesn't run). Import the
                // repos natively instead so they all show up and install.
                if (isMegaBundle(obj)) importMegaRepos()
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
            val (plugins, meta) = withContext(Dispatchers.IO) { fetchRepoPlugins(repo) }
            // Repos imported from a mega-bundle land with a URL-ish name; once
            // its repo.json is actually fetched, replace that with the real
            // name/description so the list shows "owner/repo" instead of a URL.
            val refreshed = meta ?: repo
            if (refreshed != repo) store.addCs3Repo(refreshed)
            pluginsByRepo.value = pluginsByRepo.value + (repo.url to plugins)
            repoState.value = repoState.value + (repo.url to RepoLoadState(loading = false))
            // A Mega-style bundle import may have added repos to the store.
            repos.value = store.repos()
        } catch (e: Exception) {
            repoState.value = repoState.value + (repo.url to RepoLoadState(loading = false, error = e.message))
        }
    }

    /** A readable repo label: the repo.json's own name, else "owner/repo" from
     *  the URL, else the bare host. Never a full URL. */
    private fun niceRepoName(url: String, fromJson: String): String {
        if (fromJson.isNotBlank()) return fromJson
        val m = Regex("github\\.com/([^/]+)/([^/]+)").find(url)
        if (m != null) return "${m.groupValues[1]}/${m.groupValues[2]}"
        return url.removePrefix("https://").removePrefix("http://").trimEnd('/')
    }

    private suspend fun fetchRepoPlugins(repo: Cs3Repo): Pair<List<Cs3RepoPlugin>, Cs3Repo?> {
        val text = fetchRepoRaw(repo.url)
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
        // "Mega"-style bundle: the repo's one plugin (MegaProvider) exists only
        // to add every CloudStream repo from repos-db.json via the real
        // CloudStream RepositoryManager, which Hikari never runs. Import the
        // repos natively (they land in the repo list, installable as usual)
        // and hide the useless bundle plugin instead of offering it.
        if (isMegaBundle(root) || out.values.any { it.name == "MegaProvider" }) {
            importMegaRepos()
            return emptyList<Cs3RepoPlugin>() to null
        }
        val name = niceRepoName(repo.url, root.optString("name"))
        val description = root.optString("description")
        val meta = if (name != repo.name || description != repo.description)
            repo.copy(name = name, description = description)
        else null
        return out.values.toList() to meta
    }

    private val MEGA_REPOS_DB =
        "https://raw.githubusercontent.com/recloudstream/cs-repos/master/repos-db.json"

    /** True for the self-similarity/MegaRepo style "add every repo" bundle. */
    private fun isMegaBundle(root: JSONObject): Boolean {
        val name = root.optString("name")
        return name.contains("mega", true) && name.contains("repo", true)
    }

    /** Imports every repo URL from the canonical CS repos-db.json (deduped). */
    private suspend fun importMegaRepos(): Int {
        val text = Http.fetchStringRobust(MEGA_REPOS_DB).getOrNull() ?: return 0
        val arr = runCatching { JSONArray(text) }.getOrNull() ?: return 0
        var added = 0
        for (i in 0 until arr.length()) {
            val entry = arr.opt(i)
            val repoUrl = when (entry) {
                is String -> entry
                is JSONObject -> entry.optString("url")
                else -> null
            } ?: continue
            if (!repoUrl.startsWith("http")) continue
            val name = niceRepoName(repoUrl, "")
            runCatching {
                store.addCs3Repo(Cs3Repo(url = repoUrl, name = name, kind = RepoKind.CS3))
            }
            added++
        }
        return added
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
        val bytes = withTimeoutOrNull(90_000) { Http.fetchBytesRobust(plugin.url) }
            ?: return@withContext Result.failure(Exception("Download timed out — check your connection"))
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

    /** Installs a .hiki extension listed in a Hikari repo. */
    suspend fun installHikiPlugin(plugin: Cs3RepoPlugin): Result<Int> = withContext(Dispatchers.IO) {
        val bytes = withTimeoutOrNull(90_000) { Http.fetchBytesRobust(plugin.url) }
            ?: return@withContext Result.failure(Exception("Download timed out — check your connection"))
        val hash = plugin.fileHash
        if (hash != null && hash.startsWith("sha256-")) {
            val expected = hash.removePrefix("sha256-").lowercase()
            val actual = sha256Hex(bytes)
            if (actual != expected) {
                return@withContext Result.failure(
                    Exception("Checksum mismatch — the extension file is corrupted or modified")
                )
            }
        }
        val fileName = plugin.name.substringBeforeLast('.').takeIf { it.isNotBlank() } ?: "extension"
        installHikiBytes(bytes, "$fileName.hiki", sourceUrl = plugin.url)
    }

    /** Removes every HIKARI provider that came from [pluginUrl]. */
    suspend fun uninstallHikiPlugin(pluginUrl: String) {
        fun fromPlugin(p: ProviderConfig) =
            p.type == ProviderType.HIKARI &&
                (p.extra == pluginUrl || p.extra?.startsWith("$pluginUrl|") == true)
        val all = store.providers()
        val paths = all.filter { fromPlugin(it) }.map { it.url }.toSet()
        store.saveProviders(all.filter { !fromPlugin(it) })
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
    var repoDialogKind by remember { mutableStateOf(RepoKind.CS3) }
    var showHikiUrl by remember { mutableStateOf(false) }
    var stremioUrl by remember { mutableStateOf("") }
    var scraperJson by remember { mutableStateOf("") }
    var cs3Url by remember { mutableStateOf("") }
    var hikiUrl by remember { mutableStateOf("") }
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
                // try/catch/finally so `busy` ALWAYS resets — a thrown error
                // (bad file, disk write, plugin load crash) must never leave
                // the button stuck on "Installing…" forever.
                try {
                    val r = withTimeoutOrNull(120_000) { vm.installCs3FromUri(uri) }
                        ?: Result.failure(Exception("Installation timed out"))
                    r.onSuccess { n -> successMsg = "Installed $n provider(s)" }
                    r.onFailure { errorMsg = it.message }
                } catch (e: Exception) {
                    errorMsg = e.message ?: "Installation failed"
                } finally {
                    busy = false
                }
            }
        }
    }

    val hikiPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                busyMsg = "Installing .hiki extension…"
                errorMsg = null
                successMsg = null
                try {
                    val r = withTimeoutOrNull(120_000) { vm.installHikiFromUri(uri) }
                        ?: Result.failure(Exception("Installation timed out"))
                    r.onSuccess { n -> successMsg = "Installed $n provider(s)" }
                    r.onFailure { errorMsg = it.message }
                } catch (e: Exception) {
                    errorMsg = e.message ?: "Installation failed"
                } finally {
                    busy = false
                }
            }
        }
    }

    fun installPlugin(p: Cs3RepoPlugin, kind: RepoKind) {
        scope.launch {
            busy = true
            busyMsg = "Installing ${p.name}…"
            errorMsg = null
            successMsg = null
            try {
                val r = withTimeoutOrNull(120_000) {
                    when (kind) {
                        RepoKind.CS3 -> vm.installCs3Plugin(p)
                        RepoKind.HIKARI -> vm.installHikiPlugin(p)
                    }
                } ?: Result.failure(Exception("Installation timed out"))
                r.onSuccess { n ->
                    successMsg = "Installed ${p.name} ($n provider${if (n == 1) "" else "s"})"
                }
                r.onFailure { errorMsg = it.message }
            } catch (e: Exception) {
                errorMsg = e.message ?: "Installation failed"
            } finally {
                busy = false
            }
        }
    }

    fun uninstallPlugin(p: Cs3RepoPlugin, kind: RepoKind) {
        scope.launch {
            busy = true
            busyMsg = "Uninstalling ${p.name}…"
            errorMsg = null
            successMsg = null
            try {
                when (kind) {
                    RepoKind.CS3 -> vm.uninstallCs3Plugin(p.url)
                    RepoKind.HIKARI -> vm.uninstallHikiPlugin(p.url)
                }
                successMsg = "Uninstalled ${p.name}"
            } catch (e: Exception) {
                errorMsg = e.message ?: "Uninstall failed"
            } finally {
                busy = false
            }
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
            onRefresh = { scope.launch { vm.refreshRepoPlugins(openRepo) } },
            onInstall = { installPlugin(it, openRepo.kind) },
            onUninstall = { uninstallPlugin(it, openRepo.kind) },
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
            onAddRepo = { errorMsg = null; successMsg = null; repoDialogKind = RepoKind.CS3; showRepoDialog = true },
            onAddHikiRepo = { errorMsg = null; successMsg = null; repoDialogKind = RepoKind.HIKARI; showRepoDialog = true },
            onAddStremio = { errorMsg = null; showStremio = true },
            onAddScraper = { errorMsg = null; showScraper = true },
            onAddCs3Url = { errorMsg = null; showCs3Url = true },
            onAddHikiUrl = { errorMsg = null; showHikiUrl = true },
            onPickHikiFile = {
                errorMsg = null
                successMsg = null
                hikiPicker.launch(arrayOf("application/octet-stream", "*/*"))
            },
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
            onRefreshRepo = { repo ->
                scope.launch {
                    errorMsg = null
                    vm.refreshRepoPlugins(repo)
                }
            },
            onToggleProvider = { id, enabled -> scope.launch { vm.toggle(id, enabled) } },
            onDeleteProvider = { id -> scope.launch { vm.remove(id) } },
        )
    }

    if (showRepoDialog) {
        val isHikari = repoDialogKind == RepoKind.HIKARI
        AlertDialog(
            onDismissRequest = { if (!busy) showRepoDialog = false },
            title = { Text(if (isHikari) "Add Hikari repo" else "Add CloudStream repo") },
            text = {
                Column {
                    Text(
                        if (isHikari)
                            "Paste a Hikari-style repo URL (a repo.json). For example:\n" +
                                "https://raw.githubusercontent.com/codegeasse1/hikari-extensions/builds/repo.json"
                        else
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
                            val r = if (isHikari) vm.addHikiRepo(repoUrl) else vm.addCs3Repo(repoUrl)
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

    if (showHikiUrl) {
        AlertDialog(
            onDismissRequest = { if (!busy) showHikiUrl = false },
            title = { Text("Install .hiki extension") },
            text = {
                Column {
                    Text(
                        "Paste a direct link to a compiled Hikari extension (.hiki). " +
                            "Extensions run against Hikari's own SDK — no CloudStream " +
                            "dependencies, Cloudflare solvers and WebView stream capture " +
                            "built in. See docs/HIKARI_EXTENSIONS.md."
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = hikiUrl,
                        onValueChange = { hikiUrl = it },
                        placeholder = { Text("https://…/MyExtension.hiki") },
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
                            val r = vm.installHikiFromUrl(hikiUrl)
                            busy = false
                            r.onSuccess { n ->
                                showHikiUrl = false
                                hikiUrl = ""
                                successMsg = "Installed $n provider(s)"
                            }
                            r.onFailure { errorMsg = it.message }
                        }
                    }
                ) { Text("Install") }
            },
            dismissButton = {
                TextButton(onClick = { if (!busy) showHikiUrl = false }) { Text("Cancel") }
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
    onAddHikiRepo: () -> Unit,
    onAddStremio: () -> Unit,
    onAddScraper: () -> Unit,
    onAddCs3Url: () -> Unit,
    onPickCs3File: () -> Unit,
    onAddHikiUrl: () -> Unit,
    onPickHikiFile: () -> Unit,
    onRemoveRepo: (String) -> Unit,
    onToggleProvider: (String, Boolean) -> Unit,
    onDeleteProvider: (String) -> Unit,
    onAddSite: () -> Unit,
    onOpenSite: (Site) -> Unit,
    onRemoveSite: (String) -> Unit,
    onRefreshRepo: (Cs3Repo) -> Unit,
) {
    var extFilter by remember { mutableStateOf("") }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onAddRepo,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Add CloudStream repo")
                }
                Button(
                    onClick = onAddHikiRepo,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Extension, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Add Hikari repo")
                }
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
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onAddHikiUrl,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Install .hiki from URL")
                }
                OutlinedButton(
                    onClick = onPickHikiFile,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Extension, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Pick .hiki file")
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
        item {
            SitesFolder(
                sites = sites,
                onOpen = { onOpenSite(it) },
                onRemove = { onRemoveSite(it) }
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

        item { SectionHeader("Extension repos") }
        if (repos.isEmpty()) {
            item {
                EmptyState(
                    title = "No extension repos yet",
                    subtitle = "Add a CloudStream or Hikari repo to browse and install extensions.",
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
                onRefresh = { onRefreshRepo(repo) },
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
                        "Add a CloudStream repo, a Stremio addon, a universal scraper, a .cs3 plugin, or a .hiki extension."
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
    onRefresh: () -> Unit,
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
            val unit = if (repo.kind == RepoKind.HIKARI) "extension" else "plugin"
            Text(
                "${plugins.size} ${unit}${if (plugins.size == 1) "" else "s"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            IconButton(onClick = onRefresh) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = "Refresh repo",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
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
                    TextButton(onClick = onRefresh) { Text("Retry") }
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
    // ContentScale.Crop makes real logos fill their box edge-to-edge, so a
    // Stremio/CS3 addon logo and a glyph-only extension render at the same
    // visual weight instead of "big round logo vs tiny icon".
    SubcomposeAsyncImage(
        model = safe,
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Crop
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
private fun ProviderIcon(p: ContentProvider, modifier: Modifier = Modifier) {
    // Lazily resolve icons for providers that shipped without one (Stremio
    // addons installed before the manifest icon was saved, CS3 plugins whose
    // repo lists no icon). CS3 falls back to the site's favicon synchronously;
    // Stremio needs one manifest fetch, so resolve it off the main thread.
    var icon by remember(p.config.id) { mutableStateOf(p.config.iconUrl) }
    LaunchedEffect(p.config.id) {
        if (icon == null) {
            icon = withContext(Dispatchers.IO) {
                com.hikari.app.ui.ExtensionIcons.forConfig(p.config)
            }
        }
    }
    ExtensionIcon(url = icon, modifier = modifier)
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
                ProviderIcon(
                    p = p,
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
    onRefresh: () -> Unit,
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        repo.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when (repo.kind) {
                            RepoKind.CS3 -> "CloudStream"
                            RepoKind.HIKARI -> "Hikari"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Text(
                    when {
                        state?.loading == true -> "Loading plugins…"
                        state?.error != null -> "Load failed — tap refresh to retry"
                        pluginCount > 0 -> "${pluginCount} plugin${if (pluginCount == 1) "" else "s"}"
                        else -> repo.description.ifBlank { "No plugins found" }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state?.error != null)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
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
            IconButton(onClick = onRefresh) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = "Refresh repo",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
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
private fun SitesFolder(
    sites: List<Site>,
    onOpen: (Site) -> Unit,
    onRemove: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
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
                        "Webview sites",
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        if (sites.isEmpty()) "No sites added yet — tap to expand"
                        else "${sites.size} site${if (sites.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                if (sites.isEmpty()) {
                    Text(
                        "Add any movie/streaming website and it opens in an ad-free web view — ads, trackers and popups blocked, with one-tap video playback in the player.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                } else {
                    sites.forEach { site ->
                        SiteRow(
                            site = site,
                            onOpen = { onOpen(site) },
                            onRemove = { onRemove(site.url) }
                        )
                    }
                }
            }
        }
    }
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
