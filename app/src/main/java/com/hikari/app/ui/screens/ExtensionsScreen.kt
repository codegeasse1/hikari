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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
import com.hikari.app.ui.components.GlassCard
import com.hikari.app.ui.components.GlassSearchField
import com.hikari.app.web.WebViewActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    // Install/uninstall/busy status lives in the ViewModel (not the
    // composition) so it survives tab switches: the old screen-local state was
    // cancelled the moment the user navigated away, which silently killed
    // installs mid-download. Now a job keeps running when the screen is left
    // and the result is shown when the user comes back.
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _busyMsg = MutableStateFlow("")
    val busyMsg: StateFlow<String> = _busyMsg.asStateFlow()
    private val _successMsg = MutableStateFlow<String?>(null)
    val successMsg: StateFlow<String?> = _successMsg.asStateFlow()
    private val _errorMsg = MutableStateFlow<String?>(null)
    val errorMsg: StateFlow<String?> = _errorMsg.asStateFlow()

    private var installJob: Job? = null
    private var backgroundGeneration = 0L
    private var reposLoadStarted = false

    private inline fun <T> cancellableCatching(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            Result.failure(t)
        }

    /** Runs [block] on the ViewModel scope so tab switches never cancel it,
     *  and shows the busy indicator while it runs. Only the most recent task
     *  may clear the busy flag when it finishes — an older task that is
     *  superseded must not turn the spinner off while a newer one still runs. */
    private fun startBackground(block: suspend () -> Unit): Job = viewModelScope.launch {
        val gen = ++backgroundGeneration
        _busy.value = true
        try {
            block()
        } finally {
            if (backgroundGeneration == gen) _busy.value = false
        }
    }

    fun setSuccess(msg: String) {
        _successMsg.value = msg
        _errorMsg.value = null
    }

    fun setError(msg: String?) {
        _errorMsg.value = msg
        _successMsg.value = null
    }

    fun clearStatus() {
        _successMsg.value = null
        _errorMsg.value = null
    }

    /** Extension installs are hard-capped at 20 seconds (per app spec): a
     *  download that takes longer is a dead/blocked server, not worth waiting
     *  on — stop it and tell the user to tap Install again. Runs in the VM
     *  scope, so going back to another tab mid-install does NOT stop it, and
     *  tapping Install again cancels the stale attempt and restarts cleanly. */
    fun runInstall(
        what: String,
        success: (Int) -> String = { n -> "Installed ($n provider${if (n == 1) "" else "s"})" },
        onSuccess: (Int) -> Unit = {},
        action: suspend () -> Result<Int>,
    ) {
        installJob?.cancel()
        installJob = startBackground {
            _busyMsg.value = what
            clearStatus()
            try {
                val r = withTimeoutOrNull(20_000) { action() }
                    ?: Result.failure(
                        Exception("Installation took too long (over 20 seconds) — please tap Install again")
                    )
                r.onSuccess { n ->
                    onSuccess(n)
                    setSuccess(success(n))
                }
                r.onFailure { setError(it.message ?: "Installation failed") }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                setError(e.message ?: "Installation failed")
            }
        }
    }

    /** Generic background task with the same VM-scope survival guarantees as
     *  installs (add repo/addon/scraper/site, …). */
    fun <T> runTask(
        what: String,
        action: suspend () -> Result<T>,
        onSuccess: (T) -> Unit = {},
        successMsg: String? = null,
    ) {
        startBackground {
            _busyMsg.value = what
            clearStatus()
            try {
                val r = cancellableCatching { action() }.getOrElse { Result.failure(it) }
                r.onSuccess { v ->
                    onSuccess(v)
                    if (successMsg != null) setSuccess(successMsg)
                }
                r.onFailure { setError(it.message ?: "Failed") }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            }
        }
    }

    /** Uninstall / removal task (unit-returning, may throw). */
    fun runUninstall(what: String, successMsg: String, action: suspend () -> Unit) {
        startBackground {
            _busyMsg.value = what
            clearStatus()
            try {
                cancellableCatching { action() }
                    .onSuccess { setSuccess(successMsg) }
                    .onFailure { setError(it.message ?: "Failed") }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            }
        }
    }

    /** Refreshes a repo's plugin list in the VM scope (survives tab switches —
     *  the old screen-scope launch died with the screen and left the repo
     *  stuck on "Loading plugins…" forever). */
    fun refreshRepo(repo: Cs3Repo) {
        viewModelScope.launch { refreshRepoPlugins(repo) }
    }

    /** Loads every repo's plugin list once (and only once) — re-entering the
     *  screen used to re-download every repo from scratch every time, which
     *  re-flagged everything as loading and made a slow load look like a crash. */
    fun loadReposIfNeeded() {
        if (reposLoadStarted) return
        reposLoadStarted = true
        viewModelScope.launch {
            for (repo in repos.value) {
                if (pluginsByRepo.value[repo.url] == null) refreshRepoPlugins(repo)
            }
        }
    }

    init {
        viewModelScope.launch {
            manager.refresh()
            repos.value = store.repos()
            sites.value = store.sites()
            reloadInstalled()
            loadReposIfNeeded()
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
        // Nuvio scraper files are one provider per file — delete when removed.
        if (target != null && target.type == ProviderType.NUVIO &&
            target.url.startsWith(getApplication<Application>().filesDir.absolutePath)
        ) {
            val stillUsed = store.providers().any { it.url == target.url }
            if (!stillUsed) {
                withContext(Dispatchers.IO) { runCatching { File(target.url).delete() } }
            }
            withContext(Dispatchers.IO) {
                runCatching {
                    com.hikari.app.nuvio.NuvioRuntime.settingsFile(id).delete()
                }
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
                    ProviderType.NUVIO -> if (extra.startsWith("http")) add(extra)
                    else -> {}
                }
            }
        }
    }

    suspend fun addNuvioRepo(rawUrl: String): Result<Cs3Repo> = addRepo(rawUrl, RepoKind.NUVIO)

    suspend fun installNuvioPlugin(plugin: Cs3RepoPlugin): Result<Int> =
        withContext(Dispatchers.IO) {
            val bytes = withTimeoutOrNull(90_000) {
                Http.fetchBytesRobust(plugin.url, mapOf("User-Agent" to Http.NUVIO_UA))
            } ?: return@withContext Result.failure(Exception("Download timed out — check your connection"))
            val hash = plugin.fileHash
            if (hash != null && hash.startsWith("sha256-")) {
                val expected = hash.removePrefix("sha256-").lowercase()
                val actual = sha256Hex(bytes)
                if (actual != expected) {
                    return@withContext Result.failure(
                        Exception("Checksum mismatch — the provider file is corrupted or modified")
                    )
                }
            }
            val fileName = plugin.name.substringBeforeLast('.').takeIf { it.isNotBlank() } ?: "provider"
            com.hikari.app.nuvio.NuvioPluginManager.installScraper(
                getApplication<Application>(),
                bytes,
                "$fileName.js",
                sourceUrl = plugin.url,
                iconUrl = plugin.iconUrl,
            ).also { manager.refresh(); reloadInstalled() }
        }

    /** Removes every NUVIO provider that came from [pluginUrl]. */
    suspend fun uninstallNuvioPlugin(pluginUrl: String) {
        com.hikari.app.nuvio.NuvioPluginManager.uninstallScraper(
            getApplication<Application>(),
            pluginUrl,
        )
        reloadInstalled()
    }

    suspend fun addCs3Repo(rawUrl: String): Result<Cs3Repo> = addRepo(rawUrl, RepoKind.CS3)

    suspend fun addHikiRepo(rawUrl: String): Result<Cs3Repo> = addRepo(rawUrl, RepoKind.HIKARI)

    /** The URL that actually served the last [fetchRepoRaw] — repos are stored
     *  under their raw form so refreshing works with the plain http client. */
    @Volatile
    private var lastGoodRepoUrl: String = ""

    /** Fetches a repo manifest — repo.json for CloudStream/Hikari repos,
     *  manifest.json for Nuvio repos — trying the pasted URL first and then the
     *  raw-GitHub variants for `github.com/o/r` links users commonly paste (the
     *  HTML page would never parse as JSON). Remembers which variant succeeded. */
    private fun fetchRepoRaw(url: String, file: String = "repo.json", ua: String? = null): Result<String> {
        // Nuvio manifests/scrapers live on Codeberg, which 403s the shared
        // desktop-Chrome UA but serves the nuvio app's own UA fine — override
        // for nuvio repos (mirrors the real nuvio app's client).
        val headers = if (ua != null) mapOf("User-Agent" to ua) else emptyMap()
        val variants = repoUrlVariants(url, file)
        if (variants.isEmpty()) {
            lastGoodRepoUrl = url
            return Http.fetchStringRobust(url, headers).map { text ->
                if (looksLikeHtml(text)) throw friendlyRepoError(file) else text
            }
        }
        for (candidate in variants) {
            val r = Http.fetchStringRobust(candidate, headers)
            if (r.isSuccess) {
                val text = r.getOrNull() ?: continue
                if (looksLikeHtml(text)) continue
                lastGoodRepoUrl = candidate
                return r
            }
        }
        // last resort: the pasted URL as-is (a non-main/mixed-branch manifest)
        lastGoodRepoUrl = url
        return Http.fetchStringRobust(url, headers).map { text ->
            if (looksLikeHtml(text)) throw friendlyRepoError(file) else text
        }
    }

    private fun looksLikeHtml(text: String): Boolean {
        val t = text.trimStart().take(64).lowercase()
        return t.startsWith("<!doctype") || t.startsWith("<html") || t.startsWith("<head")
    }

    private fun friendlyRepoError(file: String): Exception = Exception(
        "That URL returned an HTML page instead of a $file. Paste a direct " +
            "raw link: github.com/o/r → https://raw.githubusercontent.com/o/r/main/$file, " +
            "or on a Gitea/Forgejo host (git.disroot.org, codeberg…) → " +
            "https://host/o/r/raw/branch/main/$file"
    )

    private fun repoUrlVariants(raw: String, file: String = "repo.json"): List<String> {
        val t = raw.trim().trimEnd('/')
        if (!t.startsWith("http://") && !t.startsWith("https://")) return emptyList()
        // Already a direct raw URL — fetch as-is, no variant guessing.
        if (t.contains("raw.githubusercontent.com") || t.endsWith("/$file")) return emptyList()
        val gh = Regex("https?://(?:www\\.)?github\\.com/([^/]+)/([^/]+)").find(t)
        if (gh != null) {
            val owner = gh.groupValues[1]
            val repo = gh.groupValues[2]
            return listOf(
                "https://raw.githubusercontent.com/$owner/$repo/main/$file",
                "https://raw.githubusercontent.com/$owner/$repo/master/$file",
                "https://raw.githubusercontent.com/$owner/$repo/builds/$file",
            )
        }
        // Gitea/Forgejo instances (git.disroot.org, codeberg.org, …) serve raw
        // files at /owner/repo/raw/branch/<branch>/repo.json — the plain web
        // page would only come back as HTML.
        val gi = Regex("https?://([^/]+)/([^/]+)/([^/]+)").find(t)
        if (gi != null) {
            val host = gi.groupValues[1]
            val owner = gi.groupValues[2]
            val repo = gi.groupValues[3]
            return listOf(
                "https://$host/$owner/$repo/raw/branch/main/$file",
                "https://$host/$owner/$repo/raw/branch/master/$file",
                "https://$host/$owner/$repo/raw/main/$file",
                "https://$host/$owner/$repo/raw/master/$file",
            )
        }
        return emptyList()
    }

    private suspend fun addRepo(rawUrl: String, kind: RepoKind): Result<Cs3Repo> {
        val url = rawUrl.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return Result.failure(Exception("Must start with http(s)://"))
        }
        val file = if (kind == RepoKind.NUVIO) "manifest.json" else "repo.json"
        return withContext(Dispatchers.IO) {
            runCatching {
                val text = fetchRepoRaw(url, file, ua = if (kind == RepoKind.NUVIO) Http.NUVIO_UA else null)
                    .getOrElse { throw it }
                val obj = runCatching { JSONObject(text) }.getOrElse {
                    throw Exception("Invalid $file: ${it.message}")
                }
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
     *  the URL (works for github.com, raw.githubusercontent.com, Gitea/GitLab),
     *  else the bare host. Never a full URL. */
    private fun niceRepoName(url: String, fromJson: String): String {
        if (fromJson.isNotBlank()) return fromJson
        val m = Regex("^https?://([^/]+)/(.*)$").find(url.trim())
        if (m != null) {
            val segs = m.groupValues[2].split('?', '#')[0]
                .trimEnd('/')
                .split('/')
                .filter { it.isNotBlank() }
            if (segs.size >= 2) return "${segs[0]}/${segs[1]}"
        }
        return url.removePrefix("https://").removePrefix("http://").trimEnd('/')
    }

    private suspend fun fetchRepoPlugins(repo: Cs3Repo): Pair<List<Cs3RepoPlugin>, Cs3Repo?> {
        val file = if (repo.kind == RepoKind.NUVIO) "manifest.json" else "repo.json"
        val text = fetchRepoRaw(
            repo.url, file, ua = if (repo.kind == RepoKind.NUVIO) Http.NUVIO_UA else null
        )
            .getOrElse { throw Exception("Could not fetch repo: ${it.message}") }
        val root = runCatching { JSONObject(text) }.getOrElse {
            throw Exception("Invalid $file: ${it.message}")
        }
        if (repo.kind == RepoKind.NUVIO) {
            // A nuvio manifest lists providers under `scrapers`, each served at
            // baseUrl/filename where baseUrl = manifest URL minus /manifest.json.
            val out = LinkedHashMap<String, Cs3RepoPlugin>()
            val baseUrl = repo.url.substringBeforeLast('/')
            root.optJSONArray("scrapers")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let {
                        com.hikari.app.nuvio.NuvioPluginManager.repoPlugin(it, baseUrl)
                            ?.let { p -> out[p.url] = p }
                    }
                }
            }
            val name = niceRepoName(repo.url, root.optString("name"))
            val description = root.optString("description")
            val meta = if (name != repo.name || description != repo.description)
                repo.copy(name = name, description = description)
            else null
            return out.values.toList() to meta
        }
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

    /** Imports every repo URL from the canonical CS repos-db.json (deduped),
     *  naming each folder with its repo.json's own name when it can be fetched
     *  (bounded + parallel) so the list reads "Phisher", "MRDS", "CNC" …
     *  instead of a raw URL — the URL-only derivation the old code produced
     *  for every mega-imported repo. */
    private suspend fun importMegaRepos(): Int {
        val text = Http.fetchStringRobust(MEGA_REPOS_DB).getOrNull() ?: return 0
        val arr = runCatching { JSONArray(text) }.getOrNull() ?: return 0
        data class Entry(val url: String, val name: String)
        val entries = buildList {
            for (i in 0 until arr.length()) {
                val entry = arr.opt(i)
                val repoUrl = when (entry) {
                    is String -> entry
                    is JSONObject -> entry.optString("url")
                    else -> null
                } ?: continue
                if (!repoUrl.startsWith("http")) continue
                val entryName = (entry as? JSONObject)?.optString("name")
                    ?.takeIf { it.isNotBlank() } ?: ""
                add(Entry(repoUrl, entryName))
            }
        }
        val existing = store.repos().map { it.url }.toSet()
        val fresh = entries.filter { it.url !in existing }
        val names = coroutineScope {
            fresh.map { e ->
                async(Dispatchers.IO) {
                    if (e.name.isNotBlank()) e.name else fetchRepoDisplayName(e.url)
                }
            }.awaitAll()
        }
        var added = 0
        for ((entry, name) in fresh.zip(names)) {
            runCatching {
                store.addCs3Repo(Cs3Repo(url = entry.url, name = name, kind = RepoKind.CS3))
            }
            added++
        }
        return added
    }

    /** The repo.json's own name, else an owner/repo label derived from the URL. */
    private suspend fun fetchRepoDisplayName(url: String): String = withTimeoutOrNull(8_000) {
        withContext(Dispatchers.IO) {
            fetchRepoRaw(url).getOrNull()?.let { text ->
                runCatching { JSONObject(text).optString("name").ifBlank { null } }.getOrNull()
            }
        }
    } ?: niceRepoName(url, "")

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

    /** Installs every not-yet-installed plugin/extension/provider in [plugins]
     *  one after another in the background (survives tab switches like the
     *  single installs), showing progress in the busy message. Reports how many
     *  succeeded and names any that failed. */
    fun installAllPlugins(plugins: List<Cs3RepoPlugin>, kind: RepoKind, installedUrls: Set<String>) {
        installJob?.cancel()
        val unit = when (kind) {
            RepoKind.HIKARI -> "extension"
            RepoKind.NUVIO -> "provider"
            RepoKind.CS3 -> "plugin"
        }
        val pending = plugins.filter { it.url !in installedUrls }
        if (pending.isEmpty()) {
            val n = plugins.size
            setSuccess("All $n $unit${if (n == 1) "" else "s"} already installed")
            return
        }
        installJob = startBackground {
            _busyMsg.value = "Installing ${pending.first().name} (1/${pending.size})…"
            clearStatus()
            var ok = 0
            val failed = mutableListOf<String>()
            for ((i, p) in pending.withIndex()) {
                _busyMsg.value = "Installing ${p.name} (${i + 1}/${pending.size})…"
                val r = runCatching {
                    withTimeoutOrNull(90_000) {
                        when (kind) {
                            RepoKind.CS3 -> installCs3Plugin(p)
                            RepoKind.HIKARI -> installHikiPlugin(p)
                            RepoKind.NUVIO -> installNuvioPlugin(p)
                        }
                    }
                }.getOrNull()
                if (r != null && r.isSuccess) ok++ else failed.add(p.name)
            }
            if (failed.isEmpty()) {
                val n = pending.size
                setSuccess("Installed $ok of $n $unit${if (n == 1) "" else "s"}")
            } else {
                setSuccess(
                    "Installed $ok of ${pending.size} — failed: " +
                        failed.take(3).joinToString(", ") + (if (failed.size > 3) "…" else "")
                )
            }
        }
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
    var sourcesOpen by remember { mutableStateOf(false) }
    var openFolder by remember { mutableStateOf<SourceFolder?>(null) }
    var allReposOpen by remember { mutableStateOf(false) }
    var installedOpen by remember { mutableStateOf(false) }
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
    val busy by vm.busy.collectAsState()
    val busyMsg by vm.busyMsg.collectAsState()
    val errorMsg by vm.errorMsg.collectAsState()
    val successMsg by vm.successMsg.collectAsState()

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
    var settingsProvider by remember { mutableStateOf<ContentProvider?>(null) }

    LaunchedEffect(Unit) {
        vm.loadReposIfNeeded()
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            vm.runInstall("Installing .cs3 plugin…") { vm.installCs3FromUri(uri) }
        }
    }

    val hikiPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            vm.runInstall("Installing .hiki extension…") { vm.installHikiFromUri(uri) }
        }
    }

    fun installPlugin(p: Cs3RepoPlugin, kind: RepoKind) {
        vm.runInstall(
            "Installing ${p.name}…",
            success = { n -> "Installed ${p.name} ($n provider${if (n == 1) "" else "s"})" },
        ) {
            when (kind) {
                RepoKind.CS3 -> vm.installCs3Plugin(p)
                RepoKind.HIKARI -> vm.installHikiPlugin(p)
                RepoKind.NUVIO -> vm.installNuvioPlugin(p)
            }
        }
    }

    fun uninstallPlugin(p: Cs3RepoPlugin, kind: RepoKind) {
        vm.runUninstall("Uninstalling ${p.name}…", "Uninstalled ${p.name}") {
            when (kind) {
                RepoKind.CS3 -> vm.uninstallCs3Plugin(p.url)
                RepoKind.HIKARI -> vm.uninstallHikiPlugin(p.url)
                RepoKind.NUVIO -> vm.uninstallNuvioPlugin(p.url)
            }
        }
    }

    val folder = openFolder
    when {
        openRepo != null -> RepoPluginsView(
            repo = openRepo,
            plugins = pluginsByRepo[openRepo.url] ?: emptyList(),
            state = repoState[openRepo.url] ?: RepoLoadState(loading = true),
            installedUrls = installed,
            busy = busy,
            busyMsg = busyMsg,
            successMsg = successMsg,
            errorMsg = errorMsg,
            onBack = { openRepoUrl = null; vm.clearStatus() },
            onRefresh = { vm.refreshRepo(openRepo) },
            onInstall = { installPlugin(it, openRepo.kind) },
            onUninstall = { uninstallPlugin(it, openRepo.kind) },
            onInstallAll = {
                vm.installAllPlugins(
                    pluginsByRepo[openRepo.url] ?: emptyList(),
                    openRepo.kind,
                    installed,
                )
            },
        )
        folder != null -> SourceFolderView(
            folder = folder,
            repos = repos,
            providers = providers,
            pluginsByRepo = pluginsByRepo,
            repoState = repoState,
            busy = busy,
            busyMsg = busyMsg,
            successMsg = successMsg,
            errorMsg = errorMsg,
            onBack = { openFolder = null; vm.clearStatus() },
            onOpenRepo = { repo ->
                openRepoUrl = repo.url
                vm.clearStatus()
                if (pluginsByRepo[repo.url] == null) vm.refreshRepo(repo)
            },
            onAddRepo = {
                vm.clearStatus()
                repoDialogKind = when (folder) {
                    SourceFolder.HIKARI -> RepoKind.HIKARI
                    SourceFolder.NUVIO -> RepoKind.NUVIO
                    else -> RepoKind.CS3
                }
                showRepoDialog = true
            },
            onAddStremio = { vm.clearStatus(); showStremio = true },
            onToggleProvider = { id, enabled -> scope.launch { vm.toggle(id, enabled) } },
            onDeleteProvider = { id -> scope.launch { vm.remove(id) } },
            onRefreshRepo = { repo -> vm.clearStatus(); vm.refreshRepo(repo) },
            onRemoveRepo = { url ->
                if (openRepoUrl == url) openRepoUrl = null
                vm.runUninstall("Removing repo…", "Removed repo") { vm.removeCs3Repo(url) }
            },
            onOpenSettings = { settingsProvider = it },
        )
        allReposOpen -> AllReposView(
            repos = repos,
            pluginsByRepo = pluginsByRepo,
            repoState = repoState,
            busy = busy,
            busyMsg = busyMsg,
            successMsg = successMsg,
            errorMsg = errorMsg,
            onBack = { allReposOpen = false; vm.clearStatus() },
            onOpenRepo = { repo ->
                openRepoUrl = repo.url
                vm.clearStatus()
                if (pluginsByRepo[repo.url] == null) vm.refreshRepo(repo)
            },
            onAddRepo = { vm.clearStatus(); repoDialogKind = RepoKind.CS3; showRepoDialog = true },
            onRemoveRepo = { url ->
                if (openRepoUrl == url) openRepoUrl = null
                vm.runUninstall("Removing repo…", "Removed repo") { vm.removeCs3Repo(url) }
            },
            onRefreshRepo = { repo -> vm.clearStatus(); vm.refreshRepo(repo) },
        )
        installedOpen -> InstalledExtensionsView(
            providers = providers,
            busy = busy,
            busyMsg = busyMsg,
            successMsg = successMsg,
            errorMsg = errorMsg,
            onBack = { installedOpen = false; vm.clearStatus() },
            onToggleProvider = { id, enabled -> scope.launch { vm.toggle(id, enabled) } },
            onDeleteProvider = { id -> scope.launch { vm.remove(id) } },
        )
        sourcesOpen -> SourcesOverviewView(
            repos = repos,
            pluginsByRepo = pluginsByRepo,
            repoState = repoState,
            providers = providers,
            busy = busy,
            busyMsg = busyMsg,
            successMsg = successMsg,
            errorMsg = errorMsg,
            onBack = { sourcesOpen = false; vm.clearStatus() },
            onOpenRepo = { repo ->
                openRepoUrl = repo.url
                vm.clearStatus()
                if (pluginsByRepo[repo.url] == null) vm.refreshRepo(repo)
            },
            onAddRepo = { vm.clearStatus(); repoDialogKind = RepoKind.CS3; showRepoDialog = true },
            onAddHikiRepo = { vm.clearStatus(); repoDialogKind = RepoKind.HIKARI; showRepoDialog = true },
            onAddNuvioRepo = { vm.clearStatus(); repoDialogKind = RepoKind.NUVIO; showRepoDialog = true },
            onAddStremio = { vm.clearStatus(); showStremio = true },
            onToggleProvider = { id, enabled -> scope.launch { vm.toggle(id, enabled) } },
            onDeleteProvider = { id -> scope.launch { vm.remove(id) } },
            onRefreshRepo = { repo -> vm.clearStatus(); vm.refreshRepo(repo) },
            onRemoveRepo = { url ->
                if (openRepoUrl == url) openRepoUrl = null
                vm.runUninstall("Removing repo…", "Removed repo") { vm.removeCs3Repo(url) }
            },
            onOpenSettings = { settingsProvider = it },
        )
        else -> RepoBrowserView(
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
                vm.clearStatus()
                if (pluginsByRepo[repo.url] == null) vm.refreshRepo(repo)
            },
            onOpenSources = { sourcesOpen = true; vm.clearStatus() },
            onOpenFolder = { f -> openFolder = f; vm.clearStatus() },
            onOpenAllRepos = { allReposOpen = true; vm.clearStatus() },
            onOpenInstalled = { installedOpen = true; vm.clearStatus() },
            onAddScraper = { vm.clearStatus(); showScraper = true },
            onAddCs3Url = { vm.clearStatus(); showCs3Url = true },
            onAddHikiUrl = { vm.clearStatus(); showHikiUrl = true },
            onPickHikiFile = {
                vm.clearStatus()
                hikiPicker.launch(arrayOf("application/octet-stream", "*/*"))
            },
            onAddSite = { vm.clearStatus(); showSite = true },
            onOpenSite = { site ->
                context.startActivity(
                    Intent(context, WebViewActivity::class.java).apply {
                        putExtra("url", site.url)
                        putExtra("title", site.name)
                    }
                )
            },
            onRemoveSite = { url ->
                vm.runUninstall("Removing website…", "Website removed") { vm.removeSite(url) }
            },
            onPickCs3File = {
                vm.clearStatus()
                filePicker.launch(arrayOf("application/octet-stream", "*/*"))
            },
            onRemoveRepo = { url ->
                if (openRepoUrl == url) openRepoUrl = null
                vm.runUninstall("Removing repo…", "Removed repo") { vm.removeCs3Repo(url) }
            },
            onRefreshRepo = { repo ->
                vm.clearStatus()
                vm.refreshRepo(repo)
            },
        )
    }

    if (showRepoDialog) {
        val isHikari = repoDialogKind == RepoKind.HIKARI
        val isNuvio = repoDialogKind == RepoKind.NUVIO
        AlertDialog(
            onDismissRequest = { if (!busy) showRepoDialog = false },
            title = {
                Text(
                    when (repoDialogKind) {
                        RepoKind.HIKARI -> "Add Hikari repo"
                        RepoKind.NUVIO -> "Add Nuvio repo"
                        RepoKind.CS3 -> "Add CloudStream repo"
                    }
                )
            },
            text = {
                Column {
                    Text(
                        when {
                            isHikari ->
                                "Paste a Hikari-style repo URL (a repo.json). For example:\n" +
                                    "https://raw.githubusercontent.com/codegeasse1/hikari-extensions/builds/repo.json"
                            isNuvio ->
                                "Paste a Nuvio provider repo URL (a manifest.json). For example:\n" +
                                    "https://raw.githubusercontent.com/tapframe/nuvio-providers/main/manifest.json"
                            else ->
                                "Paste a CloudStream-style repo URL (a repo.json). For example:\n" +
                                    "https://raw.githubusercontent.com/codegeasse1/codegeasse-cloudstream-repos/builds/repo.json"
                        }
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
                        vm.runTask(
                            "Fetching repo…",
                            {
                                when (repoDialogKind) {
                                    RepoKind.HIKARI -> vm.addHikiRepo(repoUrl)
                                    RepoKind.NUVIO -> vm.addNuvioRepo(repoUrl)
                                    RepoKind.CS3 -> vm.addCs3Repo(repoUrl)
                                }
                            },
                            onSuccess = { repo ->
                                showRepoDialog = false
                                repoUrl = ""
                                vm.setSuccess("Added repo: ${repo.name}")
                                vm.refreshRepo(repo)
                            },
                        )
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
                        vm.runTask(
                            "Fetching addon manifest…",
                            { vm.addStremio(stremioUrl) },
                            onSuccess = {
                                showStremio = false
                                stremioUrl = ""
                            },
                            successMsg = "Added Stremio addon",
                        )
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
                        vm.runTask(
                            "Adding scraper…",
                            { vm.addUniversal(scraperJson) },
                            onSuccess = {
                                showScraper = false
                                scraperJson = ""
                            },
                            successMsg = "Added scraper",
                        )
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
                        vm.runInstall(
                            "Downloading and installing…",
                            onSuccess = {
                                showCs3Url = false
                                cs3Url = ""
                            },
                        ) { vm.installCs3FromUrl(cs3Url) }
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
                        vm.runInstall(
                            "Downloading and installing…",
                            onSuccess = {
                                showHikiUrl = false
                                hikiUrl = ""
                            },
                        ) { vm.installHikiFromUrl(hikiUrl) }
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
                            vm.setError("Enter a valid URL")
                        } else {
                            vm.runTask(
                                "Adding website…",
                                { runCatching { vm.addSite(siteName.trim(), withScheme) } },
                                onSuccess = {
                                    showSite = false
                                    siteUrl = ""
                                    siteName = ""
                                },
                                successMsg = "Website added",
                            )
                        }
                    }
                ) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { if (!busy) showSite = false }) { Text("Cancel") }
            }
        )
    }

    settingsProvider?.let { provider ->
        NuvioSettingsDialog(
            provider = provider,
            onDismiss = { settingsProvider = null },
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.2.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp)
    )
}

@Composable
private fun SourceDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 62.dp),
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    )
}

@Composable
private fun SourceActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    trailingIcon: ImageVector? = null,
    onTrailing: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (trailingIcon != null && onTrailing != null) {
            IconButton(onClick = onTrailing) {
                Icon(
                    trailingIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
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
    onOpenSources: () -> Unit,
    onOpenFolder: (SourceFolder) -> Unit,
    onOpenAllRepos: () -> Unit,
    onOpenInstalled: () -> Unit,
    onAddScraper: () -> Unit,
    onAddCs3Url: () -> Unit,
    onPickCs3File: () -> Unit,
    onAddHikiUrl: () -> Unit,
    onPickHikiFile: () -> Unit,
    onRemoveRepo: (String) -> Unit,
    onAddSite: () -> Unit,
    onOpenSite: (Site) -> Unit,
    onRemoveSite: (String) -> Unit,
    onRefreshRepo: (Cs3Repo) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp)) {
                Text(
                    "Extensions",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Sources, repos & providers",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item {
            val enabledCount = providers.count { it.config.enabled }
            GlassCard(
                onClick = onOpenSources,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Extension,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            providers.size.toString() + " extension" + (if (providers.size == 1) "" else "s") + " installed",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            repos.size.toString() + " repo" + (if (repos.size == 1) "" else "s") + " · " + enabledCount + " enabled",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        item { SectionHeader("Add a source") }
        item {
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Column {
                    SourceActionRow(
                        icon = Icons.Filled.Public,
                        title = "CloudStream repos",
                        subtitle = "repo.json · CloudStream extensions",
                        onClick = { onOpenFolder(SourceFolder.CLOUDSTREAM) }
                    )
                    SourceDivider()
                    SourceActionRow(
                        icon = Icons.Filled.Extension,
                        title = "Hikari repos",
                        subtitle = "repo.json · Hikari extensions",
                        onClick = { onOpenFolder(SourceFolder.HIKARI) }
                    )
                    SourceDivider()
                    SourceActionRow(
                        icon = Icons.Filled.FolderOpen,
                        title = "Nuvio repos",
                        subtitle = "manifest.json · Nuvio providers",
                        onClick = { onOpenFolder(SourceFolder.NUVIO) }
                    )
                    SourceDivider()
                    SourceActionRow(
                        icon = Icons.Filled.PlayArrow,
                        title = "Stremio addons",
                        subtitle = "manifest.json · Stremio addons",
                        onClick = { onOpenFolder(SourceFolder.STREMIO) }
                    )
                    SourceDivider()
                    SourceActionRow(
                        icon = Icons.Filled.Build,
                        title = "Add universal scraper",
                        subtitle = "JSON config · scriptable scraper",
                        onClick = onAddScraper
                    )
                    SourceDivider()
                    SourceActionRow(
                        icon = Icons.Filled.Download,
                        title = "Install .cs3 plugin",
                        subtitle = "From a URL or a local file",
                        onClick = onAddCs3Url,
                        trailingIcon = Icons.Filled.FolderOpen,
                        onTrailing = onPickCs3File
                    )
                    SourceDivider()
                    SourceActionRow(
                        icon = Icons.Filled.Add,
                        title = "Install .hiki extension",
                        subtitle = "From a URL or a local file",
                        onClick = onAddHikiUrl,
                        trailingIcon = Icons.Filled.FolderOpen,
                        onTrailing = onPickHikiFile
                    )
                    SourceDivider()
                    SourceActionRow(
                        icon = Icons.Filled.Public,
                        title = "Add website",
                        subtitle = "Opens in the ad-free web view",
                        onClick = onAddSite
                    )
                    SourceDivider()
                    SourceActionRow(
                        icon = Icons.Filled.Folder,
                        title = "All installed repos",
                        subtitle = "All repos you've added · " + repos.size + " total",
                        onClick = onOpenAllRepos
                    )
                    SourceDivider()
                    SourceActionRow(
                        icon = Icons.Filled.Extension,
                        title = "Installed extensions",
                        subtitle = "Manage, toggle & uninstall · " + providers.size + " installed",
                        onClick = onOpenInstalled
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

        item { SectionHeader("Webview sites") }
        item {
            SitesFolder(
                sites = sites,
                onOpen = { onOpenSite(it) },
                onRemove = { onRemoveSite(it) }
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
    onInstallAll: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        val unit = when (repo.kind) {
            RepoKind.HIKARI -> "extension"
            RepoKind.NUVIO -> "provider"
            RepoKind.CS3 -> "plugin"
        }
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
        if (state.loading || busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            if (busy) {
                Text(
                    busyMsg,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp)
                )
            }
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
            val uninstalled = plugins.count { it.url !in installedUrls }
            if (plugins.isNotEmpty() && uninstalled > 0) {
                item {
                    Button(
                        onClick = onInstallAll,
                        enabled = !busy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp, bottom = 4.dp)
                            .height(50.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(
                            Icons.Filled.Download,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Install all $uninstalled ${unit}s")
                    }
                }
            }
            when {
                state.loading && plugins.isEmpty() -> item {
                    Text(
                        "Loading ${unit}s…",
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
                        "No ${unit}s found in this repo.",
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
    onSettings: (() -> Unit)? = null,
) {
    GlassCard(Modifier
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
            if (onSettings != null) {
                IconButton(onClick = onSettings) {
                    Icon(
                        Icons.Filled.Settings,
                        contentDescription = "Provider settings",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
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

/** Settings editor for a Nuvio provider, driven by the provider's own
 *  `onSettings()` layout (header/info/toggle/text/select elements). Values are
 *  merged from each element's defaultValue and the saved settings file. */
@Composable
private fun NuvioSettingsDialog(
    provider: ContentProvider,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var loading by remember(provider.config.id) { mutableStateOf(true) }
    var loadError by remember(provider.config.id) { mutableStateOf<String?>(null) }
    var layout by remember(provider.config.id) { mutableStateOf<JSONArray?>(null) }
    var values by remember(provider.config.id) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var toggles by remember(provider.config.id) { mutableStateOf<Map<String, Boolean>>(emptyMap()) }

    LaunchedEffect(provider.config.id) {
        val source = runCatching { File(provider.config.url).readText() }.getOrNull()
        if (source.isNullOrBlank()) {
            loadError = "Provider file missing — reinstall this extension"
            loading = false
            return@LaunchedEffect
        }
        val payload = com.hikari.app.nuvio.NuvioRuntime.getSettingsLayout(
            context, source, provider.config.id,
        )
        val parsed = runCatching { JSONObject(payload) }.getOrNull()
        val data = parsed?.takeIf { it.optBoolean("ok", false) }?.opt("data")
        val elements = when (data) {
            is JSONArray -> data
            is JSONObject -> data.optJSONArray("items") ?: data.optJSONArray("elements")
            else -> null
        }
        if (elements == null) {
            loadError = parsed?.optString("error")?.takeIf { it.isNotBlank() }
                ?: "This provider exposes no settings"
            loading = false
            return@LaunchedEffect
        }
        val saved = runCatching {
            JSONObject(com.hikari.app.nuvio.NuvioRuntime.loadSettings(provider.config.id))
        }.getOrNull()
        val v = LinkedHashMap<String, String>()
        val t = LinkedHashMap<String, Boolean>()
        for (i in 0 until elements.length()) {
            val el = elements.optJSONObject(i) ?: continue
            val key = el.optString("key").ifBlank { continue }
            if (el.optString("type") == "toggle") {
                val def = el.optBoolean("defaultValue", false)
                t[key] = saved?.optBoolean(key, def) ?: def
            } else {
                val def = el.optString("defaultValue")
                v[key] = saved?.optString(key, def) ?: def
            }
        }
        values = v
        toggles = t
        layout = elements
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${provider.config.name} settings") },
        text = {
            when {
                loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Loading settings…")
                }
                loadError != null -> Text(
                    loadError!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
                layout != null -> Column(
                    Modifier.verticalScroll(rememberScrollState())
                ) {
                    for (i in 0 until layout!!.length()) {
                        val el = layout!!.optJSONObject(i) ?: continue
                        SettingsElementRow(
                            el = el,
                            values = values,
                            toggles = toggles,
                            onValue = { key, v -> values = values + (key to v) },
                            onToggle = { key, b -> toggles = toggles + (key to b) },
                        )
                    }
                }
                else -> Text("No settings available")
            }
        },
        confirmButton = {
            TextButton(
                enabled = !loading && layout != null,
                onClick = {
                    val out = JSONObject()
                    layout?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val el = arr.optJSONObject(i) ?: continue
                            val key = el.optString("key").ifBlank { continue }
                            if (el.optString("type") == "toggle") {
                                out.put(key, toggles[key] ?: el.optBoolean("defaultValue", false))
                            } else {
                                out.put(key, values[key] ?: el.optString("defaultValue"))
                            }
                        }
                    }
                    com.hikari.app.nuvio.NuvioRuntime.saveSettings(provider.config.id, out.toString())
                    onDismiss()
                }
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun SettingsElementRow(
    el: JSONObject,
    values: Map<String, String>,
    toggles: Map<String, Boolean>,
    onValue: (String, String) -> Unit,
    onToggle: (String, Boolean) -> Unit,
) {
    val type = el.optString("type")
    val label = el.optString("label")
    val description = el.optString("description").ifBlank { null }
    when (type) {
        "header" -> Column(Modifier.padding(top = 12.dp, bottom = 4.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            description?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        "info" -> Column(Modifier.padding(vertical = 6.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            description?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        "toggle" -> {
            val key = el.optString("key")
            val checked = toggles[key] ?: el.optBoolean("defaultValue", false)
            Column(Modifier.padding(vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                        description?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Switch(checked = checked, onCheckedChange = { onToggle(key, it) })
                }
            }
        }
        "select" -> {
            val key = el.optString("key")
            val options = runCatching { el.getJSONArray("options") }.getOrNull()
                ?: return
            val selected = values[key] ?: el.optString("defaultValue")
            Column(Modifier.padding(vertical = 4.dp)) {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                description?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                for (i in 0 until options.length()) {
                    val opt = options.optJSONObject(i) ?: continue
                    val optValue = opt.optString("value")
                    val optLabel = opt.optString("label").ifBlank { optValue }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onValue(key, optValue) }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selected == optValue,
                            onClick = { onValue(key, optValue) }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(optLabel, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        else -> {
            val key = el.optString("key")
            val isPassword = el.optBoolean("isPassword", false)
            val value = values[key] ?: el.optString("defaultValue")
            Column(Modifier.padding(vertical = 4.dp)) {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                description?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { onValue(key, it) },
                    placeholder = { Text(el.optString("placeholder")) },
                    singleLine = true,
                    visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = if (isPassword)
                        KeyboardOptions(keyboardType = KeyboardType.Password)
                    else KeyboardOptions.Default,
                    modifier = Modifier.fillMaxWidth()
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
    GlassCard(
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
                            RepoKind.NUVIO -> "Nuvio"
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
                        state == null -> repo.description.ifBlank { "Not loaded yet — tap to open" }
                        state?.loading == true -> "Loading plugins…"
                        state?.error != null -> "Load failed — tap refresh to retry"
                        pluginCount > 0 -> {
                            val unit = if (repo.kind == RepoKind.NUVIO) "provider" else "plugin"
                            "$pluginCount $unit${if (pluginCount == 1) "" else "s"}"
                        }
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
    if (p.config.type == ProviderType.NUVIO) {
        if (com.hikari.app.nuvio.NuvioPluginManager.fileMissing(p.config)) {
            return "Provider file missing — reinstall this extension"
        }
        return null
    }
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
    GlassCard(
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
    GlassCard(Modifier
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

enum class SourceFolder { CLOUDSTREAM, HIKARI, NUVIO, STREMIO }

@Composable
private fun SourceFolderView(
    folder: SourceFolder,
    repos: List<Cs3Repo>,
    providers: List<ContentProvider>,
    pluginsByRepo: Map<String, List<Cs3RepoPlugin>>,
    repoState: Map<String, RepoLoadState>,
    busy: Boolean,
    busyMsg: String,
    successMsg: String?,
    errorMsg: String?,
    onBack: () -> Unit,
    onOpenRepo: (Cs3Repo) -> Unit,
    onAddRepo: () -> Unit,
    onAddStremio: () -> Unit,
    onToggleProvider: (String, Boolean) -> Unit,
    onDeleteProvider: (String) -> Unit,
    onRefreshRepo: (Cs3Repo) -> Unit,
    onRemoveRepo: (String) -> Unit,
    onOpenSettings: (ContentProvider) -> Unit,
) {
    val kind = when (folder) {
        SourceFolder.CLOUDSTREAM -> RepoKind.CS3
        SourceFolder.HIKARI -> RepoKind.HIKARI
        SourceFolder.NUVIO -> RepoKind.NUVIO
        SourceFolder.STREMIO -> null
    }
    val (title, subtitle) = when (folder) {
        SourceFolder.CLOUDSTREAM -> "CloudStream repos" to "repo.json · CloudStream extensions"
        SourceFolder.HIKARI -> "Hikari repos" to "repo.json · Hikari extensions"
        SourceFolder.NUVIO -> "Nuvio repos" to "manifest.json · Nuvio providers"
        SourceFolder.STREMIO -> "Stremio addons" to "manifest.json · Stremio addons"
    }
    val kindLabel = when (folder) {
        SourceFolder.CLOUDSTREAM -> "CloudStream"
        SourceFolder.HIKARI -> "Hikari"
        SourceFolder.NUVIO -> "Nuvio"
        SourceFolder.STREMIO -> "Stremio"
    }
    val folderRepos = if (kind != null) repos.filter { it.kind == kind } else emptyList()
    val stremioProviders = if (folder == SourceFolder.STREMIO)
        providers.filter { it.config.type == ProviderType.STREMIO }
    else emptyList()

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
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                if (folder == SourceFolder.STREMIO)
                    "${stremioProviders.size} addon${if (stremioProviders.size == 1) "" else "s"}"
                else
                    "${folderRepos.size} repo${if (folderRepos.size == 1) "" else "s"}",
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
            contentPadding = PaddingValues(bottom = 8.dp)
        ) {
            if (kind != null) {
                if (folderRepos.isEmpty()) {
                    item {
                        EmptyState(
                            title = "No $title yet",
                            subtitle = "Tap \"Add repo\" below to add your first $kindLabel repo.",
                            actionLabel = null,
                            action = null
                        )
                    }
                }
                items(folderRepos, key = { it.url }) { repo ->
                    RepoCard(
                        repo = repo,
                        pluginCount = (pluginsByRepo[repo.url] ?: emptyList()).size,
                        state = repoState[repo.url],
                        onClick = { onOpenRepo(repo) },
                        onRefresh = { onRefreshRepo(repo) },
                        onRemoveRepo = { onRemoveRepo(repo.url) }
                    )
                }
            } else {
                if (stremioProviders.isEmpty()) {
                    item {
                        EmptyState(
                            title = "No Stremio addons yet",
                            subtitle = "Tap \"Add Stremio addon\" below to add your first addon.",
                            actionLabel = null,
                            action = null
                        )
                    }
                }
                items(stremioProviders, key = { it.config.id }) { p ->
                    ProviderCard(
                        p = p,
                        status = null,
                        onToggle = { enabled -> onToggleProvider(p.config.id, enabled) },
                        onDelete = { onDeleteProvider(p.config.id) },
                        onSettings = { onOpenSettings(p) }
                    )
                }
            }
        }
        AddRepoButton(
            label = if (folder == SourceFolder.STREMIO) "Add Stremio addon" else "Add repo",
            onClick = if (folder == SourceFolder.STREMIO) onAddStremio else onAddRepo
        )
    }
}

@Composable
private fun SourcesOverviewView(
    repos: List<Cs3Repo>,
    pluginsByRepo: Map<String, List<Cs3RepoPlugin>>,
    repoState: Map<String, RepoLoadState>,
    providers: List<ContentProvider>,
    busy: Boolean,
    busyMsg: String,
    successMsg: String?,
    errorMsg: String?,
    onBack: () -> Unit,
    onOpenRepo: (Cs3Repo) -> Unit,
    onAddRepo: () -> Unit,
    onAddHikiRepo: () -> Unit,
    onAddNuvioRepo: () -> Unit,
    onAddStremio: () -> Unit,
    onToggleProvider: (String, Boolean) -> Unit,
    onDeleteProvider: (String) -> Unit,
    onRefreshRepo: (Cs3Repo) -> Unit,
    onRemoveRepo: (String) -> Unit,
    onOpenSettings: (ContentProvider) -> Unit,
) {
    var extFilter by remember { mutableStateOf("") }
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
                Text("All sources", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${providers.size} extensions · ${repos.size} repos",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            repoGroup(
                title = "CloudStream",
                groupRepos = repos.filter { it.kind == RepoKind.CS3 },
                pluginsByRepo = pluginsByRepo,
                repoState = repoState,
                onAdd = onAddRepo,
                onOpenRepo = onOpenRepo,
                onRefreshRepo = onRefreshRepo,
                onRemoveRepo = onRemoveRepo,
            )
            repoGroup(
                title = "Hikari",
                groupRepos = repos.filter { it.kind == RepoKind.HIKARI },
                pluginsByRepo = pluginsByRepo,
                repoState = repoState,
                onAdd = onAddHikiRepo,
                onOpenRepo = onOpenRepo,
                onRefreshRepo = onRefreshRepo,
                onRemoveRepo = onRemoveRepo,
            )
            repoGroup(
                title = "Nuvio",
                groupRepos = repos.filter { it.kind == RepoKind.NUVIO },
                pluginsByRepo = pluginsByRepo,
                repoState = repoState,
                onAdd = onAddNuvioRepo,
                onOpenRepo = onOpenRepo,
                onRefreshRepo = onRefreshRepo,
                onRemoveRepo = onRemoveRepo,
            )
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "STREMIO ADDONS",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.2.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onAddStremio) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add")
                    }
                }
            }
            val stremioProviders = providers.filter { it.config.type == ProviderType.STREMIO }
            if (stremioProviders.isEmpty()) {
                item {
                    Text(
                        "No Stremio addons yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                    )
                }
            }
            items(stremioProviders, key = { it.config.id }) { p ->
                ProviderCard(
                    p = p,
                    status = null,
                    onToggle = { enabled -> onToggleProvider(p.config.id, enabled) },
                    onDelete = { onDeleteProvider(p.config.id) },
                    onSettings = { onOpenSettings(p) }
                )
            }
            item { SectionHeader("Installed extensions") }
            item {
                GlassSearchField(
                    value = extFilter,
                    onValueChange = { extFilter = it },
                    placeholder = "Search installed extensions…",
                    height = 48.dp,
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
                    onDelete = { onDeleteProvider(p.config.id) },
                    onSettings = if (p.config.type == ProviderType.NUVIO)
                        { { onOpenSettings(p) } } else null,
                )
            }
        }
    }
}

private fun LazyListScope.repoGroup(
    title: String,
    groupRepos: List<Cs3Repo>,
    pluginsByRepo: Map<String, List<Cs3RepoPlugin>>,
    repoState: Map<String, RepoLoadState>,
    onAdd: () -> Unit,
    onOpenRepo: (Cs3Repo) -> Unit,
    onRefreshRepo: (Cs3Repo) -> Unit,
    onRemoveRepo: (String) -> Unit,
) {
    item {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.2.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onAdd) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Add")
            }
        }
    }
    if (groupRepos.isEmpty()) {
        item {
            Text(
                "No $title repos yet",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )
        }
    }
    items(groupRepos, key = { it.url }) { repo ->
        RepoCard(
            repo = repo,
            pluginCount = (pluginsByRepo[repo.url] ?: emptyList()).size,
            state = repoState[repo.url],
            onClick = { onOpenRepo(repo) },
            onRefresh = { onRefreshRepo(repo) },
            onRemoveRepo = { onRemoveRepo(repo.url) }
        )
    }
}

@Composable
private fun AddRepoButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .height(52.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Icon(Icons.Filled.Add, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun AllReposView(
    repos: List<Cs3Repo>,
    pluginsByRepo: Map<String, List<Cs3RepoPlugin>>,
    repoState: Map<String, RepoLoadState>,
    busy: Boolean,
    busyMsg: String,
    successMsg: String?,
    errorMsg: String?,
    onBack: () -> Unit,
    onOpenRepo: (Cs3Repo) -> Unit,
    onAddRepo: () -> Unit,
    onRemoveRepo: (String) -> Unit,
    onRefreshRepo: (Cs3Repo) -> Unit,
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
                Text("All installed repos", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${repos.size} repo${if (repos.size == 1) "" else "s"} added",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
            contentPadding = PaddingValues(bottom = 8.dp)
        ) {
            if (repos.isEmpty()) {
                item {
                    EmptyState(
                        title = "No repos added yet",
                        subtitle = "Tap \"Add repo\" below to add a CloudStream, Hikari or Nuvio repo.",
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
        }
        AddRepoButton(label = "Add repo", onClick = onAddRepo)
    }
}

@Composable
private fun InstalledExtensionsView(
    providers: List<ContentProvider>,
    busy: Boolean,
    busyMsg: String,
    successMsg: String?,
    errorMsg: String?,
    onBack: () -> Unit,
    onToggleProvider: (String, Boolean) -> Unit,
    onDeleteProvider: (String) -> Unit,
) {
    var extFilter by remember { mutableStateOf("") }
    var settingsProvider by remember { mutableStateOf<ContentProvider?>(null) }
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
                Text("Installed extensions", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${providers.size} extension${if (providers.size == 1) "" else "s"} installed",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                GlassSearchField(
                    value = extFilter,
                    onValueChange = { extFilter = it },
                    placeholder = "Search installed extensions…",
                    height = 48.dp,
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
                    onDelete = { onDeleteProvider(p.config.id) },
                    onSettings = if (p.config.type == ProviderType.NUVIO)
                        { { settingsProvider = p } } else null,
                )
            }
        }
    }

    settingsProvider?.let { provider ->
        NuvioSettingsDialog(
            provider = provider,
            onDismiss = { settingsProvider = null },
        )
    }
}
