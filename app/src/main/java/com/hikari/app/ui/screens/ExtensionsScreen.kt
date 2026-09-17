package com.hikari.app.ui.screens
import com.hikari.app.i18n.I18n
import com.hikari.app.i18n.tr

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.hikari.app.cs3.Cs3MainApiProvider
import com.hikari.app.cs3.Cs3PluginManager
import com.hikari.app.hiki.HikariPluginManager
import com.hikari.app.data.Cs3Repo
import com.hikari.app.data.Cs3RepoPlugin
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.ProviderType
import com.hikari.app.data.RepoKind
import com.hikari.app.data.RepoLoadState
import com.hikari.app.data.Site
import com.hikari.app.data.SourceUrls
import com.hikari.app.net.Http
import com.hikari.app.providers.ContentProvider
import com.hikari.app.providers.ProviderManager
import com.hikari.app.ui.components.EmptyState
import com.hikari.app.ui.components.GlassCard
import com.hikari.app.ui.components.GlassSearchField
import com.hikari.app.web.WebViewActivity
import kotlinx.coroutines.CoroutineScope
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
    /** Source URLs of installed extensions whose published `fileHash` no
     *  longer matches the file on disk — i.e. the repo has a newer build.
     *  Filled by [checkUpdates], shown as an Update button on the row. */
    val outdatedUrls = MutableStateFlow<Set<String>>(emptySet())
    val repoState = MutableStateFlow<Map<String, RepoLoadState>>(emptyMap())
    /** Repo URLs that turned out to be "bundle" repos (the Mega repo and
     *  friends): they hold no plugins of their own, they only list other
     *  repos, which get imported into the store. The UI shows a short
     *  explanation instead of a bare "0 plugins". */
    val bundleRepos = MutableStateFlow<Set<String>>(emptySet())
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

    /** Repos whose plugin list is being fetched right now — guards the window
     *  between a load starting and `repoState` reporting it as loading. */
    private val reposLoading = mutableSetOf<String>()

    /** True when the last [addRepo] hit a repo that was already in the list
     *  (same repo, possibly a different URL spelling) — the dialog says so
     *  instead of claiming it was added a second time. */
    var duplicateRepoAdd: Boolean = false
        private set

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
    private fun startBackground(block: suspend () -> Unit): Job {
        // Extension installs/updates are long downloads+dex loads; register
        // them so a background trip doesn't freeze the process mid-install
        // (see [com.hikari.app.work.BackgroundWork]).
        val work = com.hikari.app.work.BackgroundWork.begin("Updating extensions")
        val job = viewModelScope.launch {
            val gen = ++backgroundGeneration
            _busy.value = true
            try {
                block()
            } finally {
                if (backgroundGeneration == gen) _busy.value = false
            }
        }
        job.invokeOnCompletion { com.hikari.app.work.BackgroundWork.end(work) }
        return job
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

    /** Ensures every repo has its plugin list loaded, skipping the ones that
     *  already have data or are mid-load. Safe to call any number of times from
     *  any lifecycle point: the old one-shot flag could be consumed by an early
     *  call made before `repos` had been read from the store, which then left
     *  every repo permanently unloaded — so the extensions search only ever
     *  found already-installed providers, never the installable repo entries. */
    fun loadReposIfNeeded() {
        viewModelScope.launch {
            for (repo in repos.value) {
                if (pluginsByRepo.value[repo.url] != null) continue
                if (repoState.value[repo.url]?.loading == true) continue
                if (repo.url in reposLoading) continue
                reposLoading.add(repo.url)
                try {
                    refreshRepoPlugins(repo)
                } finally {
                    reposLoading.remove(repo.url)
                }
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
        viewModelScope.launch {
            // Keep the repo list LIVE instead of a one-shot read: the app seeds
            // its bundled Hikari + CloudStream repos on first run from a
            // background coroutine, which can land AFTER this screen has read
            // the store — so the screen kept showing "No repos yet" even though
            // the repos existed. Observing the store also means a repo added by
            // any other path (bundled seeding, Mega-import) shows up at once.
            store.reposFlow().collect { list ->
                repos.value = list
                // And load the plugin lists for any repo that arrived this way
                // (idempotent — already-loaded/loading repos are skipped).
                loadReposIfNeeded()
            }
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
                val source = when (p.type) {
                    ProviderType.CS3, ProviderType.NUVIO -> extra
                    ProviderType.HIKARI -> extra.substringBeforeLast('|')
                    else -> return@forEach
                }
                if (!source.startsWith("http")) return@forEach
                // Remember every spelling of the source URL, not just the one
                // the repo served at install time: a repo build can move a file
                // (a new branch, `refs/heads/x` vs `x`, the jsDelivr mirror),
                // and a literal comparison used to greet the extension the user
                // already installed with an Install button again.
                addAll(SourceUrls.matchKeys(source))
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
        val app = getApplication<Application>()
        // The manager removes by exact source URL, so hand it the spelling each
        // installed provider actually stored (they can differ from the repo's
        // current listing) — otherwise "Uninstalled" would leave the provider
        // in place when the repo moved the file.
        val stored = store.providers()
            .filter { it.type == ProviderType.NUVIO && sourceMatches(it, pluginUrl) }
            .mapNotNull { it.extra }
            .distinct()
            .ifEmpty { listOf(pluginUrl) }
        for (source in stored) {
            com.hikari.app.nuvio.NuvioPluginManager.uninstallScraper(app, source)
        }
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
        val variants = repoUrlVariants(url, file).ifEmpty { listOf(url) }
        // Try every variant, then the jsDelivr CDN mirror of each (a different
        // host, so it survives an ISP/DNS block on raw.githubusercontent.com),
        // and finally the pasted URL as-is. A candidate whose body is an HTML
        // page (an ISP "blocked" notice served with HTTP 200, a GitHub web
        // page, …) is skipped rather than treated as a repo — that 200-with-HTML
        // case is what produced the bogus "returned an HTML page instead of a
        // repo.json" error and made the Mega bundle add 0 repos.
        val candidates = LinkedHashSet<String>()
        for (v in variants) {
            candidates += v
            jsDelivrMirror(v)?.let { candidates += it }
        }
        candidates += url
        var lastError: Throwable? = null
        for (candidate in candidates) {
            val r = Http.fetchStringRobust(candidate, headers)
            val text = r.getOrNull()
            if (text == null) {
                lastError = r.exceptionOrNull() ?: lastError
                continue
            }
            if (looksLikeHtml(text)) {
                lastError = friendlyRepoError(file)
                continue
            }
            lastGoodRepoUrl = candidate
            return Result.success(text)
        }
        return Result.failure(lastError ?: friendlyRepoError(file))
    }

    /** The jsDelivr CDN equivalent of a raw.githubusercontent.com URL, or null
     *  when [url] isn't one. jsDelivr is a separate domain/IP from GitHub, so it
     *  still answers when raw.githubusercontent.com is blocked or rate-limited. */
    private fun jsDelivrMirror(url: String): String? {
        val m = Regex("^https://raw\\.githubusercontent\\.com/([^/]+)/([^/]+)/([^/]+)/(.+)$")
            .find(url.trim()) ?: return null
        return "https://cdn.jsdelivr.net/gh/${m.groupValues[1]}/${m.groupValues[2]}@${m.groupValues[3]}/${m.groupValues[4]}"
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
        // Already a direct raw URL — fetch as-is, no variant guessing. A raw
        // link pasted with GitHub's web-style "/refs/heads/<branch>/" segment
        // is normalised to the canonical "/<branch>/" form and tried FIRST:
        // some repos' build branches answer the web-style path with a 404 HTML
        // page (phisher98's "builds", several entries in the official
        // CloudStream repos-db.json), which used to surface as "returned an
        // HTML page instead of a repo.json". Both forms are tried so either
        // paste shape works.
        if (t.contains("raw.githubusercontent.com")) {
            val refsHeads = t.replace("/refs/heads/", "/")
            return if (refsHeads != t) listOf(refsHeads, t) else emptyList()
        }
        if (t.endsWith("/$file")) return emptyList()
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

    /**
     * Short names the "Add repository" dialog accepts in place of a full URL —
     * e.g. "megarepo" (every CloudStream repo), "hikari", or the Nuvio repos
     * listed on nuvioplugin.com. Each name maps to one or more real repo URLs,
     * and the alias carries its own kind so the name works from any tab.
     */
    private data class RepoAlias(val url: String, val kind: RepoKind)

    private val REPO_ALIASES: Map<String, List<RepoAlias>> = buildMap<String, List<RepoAlias>> {
        val mega = RepoAlias(
            "https://raw.githubusercontent.com/self-similarity/MegaRepo/builds/repo.json",
            RepoKind.CS3,
        )
        val hikari = RepoAlias(
            "https://raw.githubusercontent.com/codegeasse1/hikari-extensions/builds/repo.json",
            RepoKind.HIKARI,
        )
        fun cs3(url: String) = RepoAlias(url, RepoKind.CS3)
        // CloudStream extension repos, mirroring the official repos-db.json
        // (https://github.com/recloudstream/cs-repos) plus the popular ones.
        // Each short name adds ONE repo; the mnemonic is usually the maintainer.
        val csOfficial = cs3("https://raw.githubusercontent.com/recloudstream/extensions/master/repo.json")
        val phisherCs3 = cs3("https://raw.githubusercontent.com/phisher98/cloudstream-extensions-phisher/builds/repo.json")
        val hexated = cs3("https://raw.githubusercontent.com/hexated/cloudstream-extensions-hexated/master/repo.json")
        val csx = cs3("https://raw.githubusercontent.com/SaurabhKaperwan/CSX/builds/CS.json")
        val cnc = cs3("https://raw.githubusercontent.com/NivinCNC/CNCVerse-Cloud-Stream-Extension/refs/heads/builds/CNC.json")
        val aniyomi = cs3("https://raw.githubusercontent.com/CranberrySoup/AniyomiCompatExtension/master/repo.json")
        val uk = cs3("https://raw.githubusercontent.com/CakesTwix/cloudstream-extensions-uk/master/repo.json")
        val italian = cs3("https://raw.githubusercontent.com/Gian-Fr/ItalianProvider/builds/repo.json")
        val italiaInStreaming = cs3("https://raw.githubusercontent.com/DieGon7771/ItaliaInStreaming/builds/repo.json")
        val german = cs3("https://raw.githubusercontent.com/Bnyro/GermanProviders/refs/heads/master/repo.json")
        val turkish = cs3("https://raw.githubusercontent.com/keyiflerolsun/Kekik-cloudstream/master/repo.json")
        val indo = cs3("https://raw.githubusercontent.com/TeKuma25/IndoStream/builds/repo.json")
        val skillshare = cs3("https://raw.githubusercontent.com/techtanic/SkillShare-Repo/builds/repo.json")
        val luna = cs3("https://raw.githubusercontent.com/Luna712/Luna712-CloudStream-Extensions/master/repo.json")
        val redowan = cs3("https://raw.githubusercontent.com/redowan99/Redowan-CloudStream/master/repo.json")
        val dogior = cs3("https://raw.githubusercontent.com/doGior/doGiorsHadEnough/refs/heads/builds/repo.json")
        val karma = cs3("https://raw.githubusercontent.com/Kraptor123/cs-Karma/refs/heads/master/repo.json")
        val storm = cs3("https://raw.githubusercontent.com/redblacker8/storm-ext/refs/heads/builds/repo.json")
        val cinephile = cs3("https://raw.githubusercontent.com/rockhero1234/cinephile/refs/heads/builds/repo.json")
        val saimuelRepo = cs3("https://raw.githubusercontent.com/saimuelbr/saimuelrepo/refs/heads/main/builds/repo.json")
        val fstream = cs3("https://git.disroot.org/ayza/FStream/raw/branch/main/repo.json")
        fun nuvio(url: String) = RepoAlias(url, RepoKind.NUVIO)
        val yoru = nuvio("https://raw.githubusercontent.com/tapframe/nuvio-providers/main/manifest.json")
        val gowaru = nuvio("https://raw.githubusercontent.com/Gowaru/gowaru-nuvio-providers/main/manifest.json")
        val phisher = nuvio("https://raw.githubusercontent.com/phisher98/phisher-nuvio-providers/main/manifest.json")
        val allInOne = nuvio("https://raw.githubusercontent.com/D3adlyRocket/All-in-One-Nuvio/refs/heads/main/manifest.json")
        val michat = nuvio("https://raw.githubusercontent.com/michat88/nuvio-providers/refs/heads/main/manifest.json")
        val spidey = nuvio("https://raw.githubusercontent.com/Abinanthankv/NuvioRepo/refs/heads/master/manifest.json")
        val saimuel = nuvio("https://raw.githubusercontent.com/saimuelbr/saimuel-nuvio-repo/refs/heads/main/manifest.json")
        val mooncrown = nuvio("https://raw.githubusercontent.com/mooncrown04/nuviotr/refs/heads/main/manifest.json")
        val kenneth = nuvio("https://raw.githubusercontent.com/KennethJYS/Nuvio-Providers-Latino/refs/heads/main/manifest.json")
        val eclipsia = nuvio("https://plugin.eclipsia.dpdns.org/manifest.json")
        val everyNuvio = listOf(yoru, gowaru, phisher, allInOne, michat, spidey, saimuel, mooncrown, kenneth, eclipsia)
        put("megarepo", listOf(mega))
        put("mega", listOf(mega))
        put("csrepos", listOf(mega))
        put("hikari", listOf(hikari))
        put("hikariextensions", listOf(hikari))
        put("csofficial", listOf(csOfficial))
        put("official", listOf(csOfficial))
        put("recloudstream", listOf(csOfficial))
        put("cloudstream", listOf(csOfficial))
        put("phisher", listOf(phisherCs3))
        put("phisherrepo", listOf(phisherCs3))
        put("phisher98", listOf(phisherCs3))
        put("phishercs3", listOf(phisherCs3))
        put("hexated", listOf(hexated))
        put("csx", listOf(csx))
        put("megix", listOf(csx))
        put("cnc", listOf(cnc))
        put("cncverse", listOf(cnc))
        put("aniyomi", listOf(aniyomi))
        put("aniyomicompat", listOf(aniyomi))
        put("uk", listOf(uk))
        put("italian", listOf(italian))
        put("italianprovider", listOf(italian))
        put("italiainstreaming", listOf(italiaInStreaming))
        put("diegon", listOf(italiaInStreaming))
        put("german", listOf(german))
        put("germanproviders", listOf(german))
        put("bnyro", listOf(german))
        put("turkish", listOf(turkish))
        put("kekik", listOf(turkish))
        put("indostream", listOf(indo))
        put("indo", listOf(indo))
        put("skillshare", listOf(skillshare))
        put("luna", listOf(luna))
        put("luna712", listOf(luna))
        put("redowan", listOf(redowan))
        put("bdix", listOf(redowan))
        put("dogior", listOf(dogior))
        put("karma", listOf(karma))
        put("cskarma", listOf(karma))
        put("storm", listOf(storm))
        put("stormext", listOf(storm))
        put("cinephile", listOf(cinephile))
        put("saimuelrepo", listOf(saimuelRepo))
        put("saimuelcs3", listOf(saimuelRepo))
        put("fstream", listOf(fstream))
        put("ayza", listOf(fstream))
        put("nuvio", everyNuvio)
        put("nuvioall", everyNuvio)
        put("yoru", listOf(yoru))
        put("yoruix", listOf(yoru))
        put("gowaru", listOf(gowaru))
        put("phishernuvio", listOf(phisher))
        put("allinone", listOf(allInOne))
        put("d3adlyrocket", listOf(allInOne))
        put("michat", listOf(michat))
        put("michat88", listOf(michat))
        put("spidey", listOf(spidey))
        put("saimuel", listOf(saimuel))
        put("saimuelbr", listOf(saimuel))
        put("mooncrown", listOf(mooncrown))
        put("kenneth", listOf(kenneth))
        put("kennethjys", listOf(kenneth))
        put("latino", listOf(kenneth))
        put("eclipsia", listOf(eclipsia))
    }

    /** A pasted short name (case-insensitive) resolved to its repo(s), or null
     *  when the input is a real URL / unknown name. */
    private fun resolveRepoAlias(raw: String): List<RepoAlias>? {
        val key = raw.trim().lowercase()
            .removePrefix("@")
            .removeSuffix(".json")
            .trimEnd('/')
        return REPO_ALIASES[key]
    }

    private suspend fun addRepo(rawUrl: String, kind: RepoKind): Result<Cs3Repo> {
        val trimmed = rawUrl.trim()
        resolveRepoAlias(trimmed)?.let { aliases ->
            var first: Cs3Repo? = null
            var lastError: Throwable? = null
            for (alias in aliases) {
                val result = addRepoUrl(alias.url, alias.kind)
                val added = result.getOrNull()
                if (added != null) {
                    if (first == null) first = added
                } else {
                    lastError = result.exceptionOrNull()
                }
            }
            return first?.let { Result.success(it) }
                ?: Result.failure(lastError ?: Exception("Could not add \"$trimmed\""))
        }
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return Result.failure(
                Exception("Must start with http(s):// — or a short name (megarepo, hikari, nuvio, …)")
            )
        }
        return addRepoUrl(trimmed, kind)
    }

    private suspend fun addRepoUrl(url: String, kind: RepoKind): Result<Cs3Repo> {
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
                // Adding a repo that is already in the list (same repo — the
                // URL spelling may differ: refs/heads vs plain branch, the
                // jsDelivr mirror) must not grow a second copy: that copy's
                // extensions all looked uninstalled again while the originals
                // kept working on Home. The store merges it into the one entry
                // it belongs to; report which of the two happened so the dialog
                // can say "already added" instead of "added".
                val key = SourceUrls.canonical(repo.url)
                duplicateRepoAdd = store.repos().any { SourceUrls.canonical(it.url) == key }
                store.addCs3Repo(repo)
                // A "Mega"-style bundle repo isn't a plugin repo — its single
                // plugin only exists to add every CloudStream repo from the
                // canonical repos-db.json (and relies on the real CloudStream
                // RepositoryManager, which Hikari doesn't run). Import the
                // repos natively instead so they all show up and install.
                if (isMegaBundle(obj)) {
                    markBundle(repo.url)
                    importMegaRepos()
                }
                val saved = store.repos().firstOrNull { SourceUrls.canonical(it.url) == key }
                repos.value = store.repos()
                saved ?: repo
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
            markBundle(repo.url)
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

    /** Fired when [url] is discovered to be a bundle repo (see [isMegaBundle]). */
    private fun markBundle(url: String) {
        if (url.isBlank()) return
        bundleRepos.value = bundleRepos.value + url
    }

    /** True for the self-similarity/MegaRepo style "add every repo" bundle. */
    private fun isMegaBundle(root: JSONObject): Boolean {
        val name = root.optString("name")
        return name.contains("mega", true) && name.contains("repo", true)
    }

    /** The repos the "Mega repo" bundles — a mirror of recloudstream's
     *  repos-db.json, used only when that database can't be fetched at all
     *  (blocked network, an ISP HTML notice served as HTTP 200, …). Without a
     *  fallback a blocked fetch left the bundle import at exactly 0 repos,
     *  which is the other half of the "Mega repo shows nothing" bug. */
    private val MEGA_FALLBACK_REPOS: List<String> = listOf(
        "https://raw.githubusercontent.com/recloudstream/extensions/master/repo.json",
        "https://raw.githubusercontent.com/CranberrySoup/AniyomiCompatExtension/master/repo.json",
        "https://raw.githubusercontent.com/Gian-Fr/ItalianProvider/builds/repo.json",
        "https://raw.githubusercontent.com/CakesTwix/cloudstream-extensions-uk/master/repo.json",
        "https://raw.githubusercontent.com/techtanic/SkillShare-Repo/builds/repo.json",
        "https://raw.githubusercontent.com/SaurabhKaperwan/CSX/builds/CS.json",
        "https://git.disroot.org/ayza/FStream/raw/branch/main/repo.json",
        "https://raw.githubusercontent.com/phisher98/cloudstream-extensions-phisher/refs/heads/builds/repo.json",
        "https://raw.githubusercontent.com/NivinCNC/CNCVerse-Cloud-Stream-Extension/refs/heads/builds/CNC.json",
        "https://raw.githubusercontent.com/Luna712/Luna712-CloudStream-Extensions/master/repo.json",
        "https://raw.githubusercontent.com/redowan99/Redowan-CloudStream/master/repo.json",
        "https://raw.githubusercontent.com/Abodabodd/re-3arabi/refs/heads/main/repo",
        "https://raw.githubusercontent.com/doGior/doGiorsHadEnough/refs/heads/builds/repo.json",
        "https://raw.githubusercontent.com/DieGon7771/ItaliaInStreaming/builds/repo.json",
        "https://gitlab.com/tearrs/cloudstream-vietnamese/-/raw/main/repo.json",
        "https://raw.githubusercontent.com/Bnyro/GermanProviders/refs/heads/master/repo.json",
        "https://raw.githubusercontent.com/TeKuma25/IndoStream/builds/repo.json",
        "https://raw.githubusercontent.com/saimuelbr/saimuelrepo/refs/heads/main/builds/repo.json",
        "https://raw.githubusercontent.com/Kraptor123/cs-Karma/refs/heads/master/repo.json",
        "https://raw.githubusercontent.com/redblacker8/storm-ext/refs/heads/builds/repo.json",
        "https://raw.githubusercontent.com/rockhero1234/cinephile/refs/heads/builds/repo.json",
        "https://raw.githubusercontent.com/med1245/cartoonyrepo/builds/repo.json",
        "https://raw.githubusercontent.com/Reflex755/ReflexRepo/refs/heads/builds/repo.json",
        "https://raw.githubusercontent.com/KSHITIJ8473/raghav/builds/repo.json",
        "https://raw.githubusercontent.com/mouradchaouche/cloudstream-frenchrepo/main/repo.json",
        "https://raw.githubusercontent.com/yorik100/Cloudstream/refs/heads/builds/repo.json",
        "https://raw.githubusercontent.com/RVRBEAST76/allforu-repo/builds/repo.json",
    )

    /** Parses repos-db.json (or an equivalent array of URL strings / {url}
     *  objects) into (url, name) pairs. */
    private fun parseMegaRepoEntries(text: String): List<Pair<String, String>> {
        val arr = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        val out = ArrayList<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val entry = arr.opt(i)
            val repoUrl = when (entry) {
                is String -> entry
                is JSONObject -> entry.optString("url")
                else -> null
            } ?: continue
            if (!repoUrl.startsWith("http")) continue
            val name = (entry as? JSONObject)?.optString("name")
                ?.takeIf { it.isNotBlank() } ?: ""
            out += repoUrl to name
        }
        return out
    }

    /** True when [url] is a bundle repo rather than a real plugin repo — used
     *  so a bundle listing another bundle never re-imports itself. */
    private fun isMegaBundleUrl(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("megarepo") || u.contains("mega-repo") ||
            (u.contains("mega") && u.contains("repo"))
    }

    /** Imports every repo URL a bundle lists (deduped, bundles skipped), naming
     *  each folder with its repo.json's own name when it can be fetched
     *  (bounded + parallel) so the list reads "Phisher", "CNC", "CSX" … instead
     *  of a raw URL. Falls back to the bundled list above when the canonical
     *  repos-db.json can't be fetched. Returns the number of repos added. */
    private suspend fun importMegaRepos(): Int {
        val dbText = listOfNotNull(
            Http.fetchStringRobust(MEGA_REPOS_DB).getOrNull(),
            jsDelivrMirror(MEGA_REPOS_DB)?.let { Http.fetchStringRobust(it).getOrNull() },
        ).firstOrNull { it.isNotBlank() && !looksLikeHtml(it) }
        val entries = (dbText?.let { parseMegaRepoEntries(it) } ?: emptyList())
            .ifEmpty { MEGA_FALLBACK_REPOS.map { it to "" } }
            .filterNot { (url, _) -> isMegaBundleUrl(url) }
        val existing = store.repos().map { it.url }.toSet()
        val fresh = entries.filter { (url, _) -> url !in existing }
        val names = coroutineScope {
            fresh.map { (url, name) ->
                async(Dispatchers.IO) {
                    if (name.isNotBlank()) name else fetchRepoDisplayName(url)
                }
            }.awaitAll()
        }
        var added = 0
        for (i in fresh.indices) {
            val (url, _) = fresh[i]
            val name = names.getOrElse(i) { url }
            val ok = runCatching {
                store.addCs3Repo(Cs3Repo(url = url, name = name, kind = RepoKind.CS3))
            }.isSuccess
            if (ok) added++
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

    /**
     * True when [p]'s stored source URL is the same file as [source] — however
     * either side spells it (refs/heads vs a plain branch, the jsDelivr mirror,
     * %20 vs a space). Install, uninstall and the update check all key on the
     * source URL, so they all have to agree on what "the same file" means.
     */
    private fun sourceMatches(p: ProviderConfig, source: String): Boolean {
        val extra = p.extra ?: return false
        if (!extra.startsWith("http")) return false
        val raw = if (p.type == ProviderType.HIKARI) extra.substringBeforeLast('|') else extra
        val wanted = SourceUrls.matchKeys(source)
        return SourceUrls.matchKeys(raw).any { it in wanted }
    }

    suspend fun uninstallCs3Plugin(pluginUrl: String) {
        val all = store.providers()
        val paths = all.filter { sourceMatches(it, pluginUrl) }.map { it.url }.toSet()
        store.saveProviders(all.filterNot { sourceMatches(it, pluginUrl) })
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
                        when (effectiveRepoKind(kind, p.url)) {
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

    /**
     * Compares the file each installed extension was saved as against the
     * `fileHash` its repo publishes. A mismatch IS the update signal: it needs
     * no version bookkeeping of our own, and it also catches a re-released
     * build that kept the same version number. Repos that publish no `fileHash`
     * can't be checked this way, and are simply never flagged.
     */
    suspend fun checkUpdates() = withContext(Dispatchers.IO) {
        // installed source URL -> the .cs3/.hiki/.js file it was stored as
        val onDisk = HashMap<String, String>()
        // saved file -> the source URL it was installed from (the raw spelling)
        val sourceOfPath = HashMap<String, String>()
        for (p in store.providers()) {
            val extra = p.extra ?: continue
            if (!extra.startsWith("http")) continue
            if (p.url.isBlank()) continue
            val source = if (p.type == ProviderType.HIKARI) extra.substringBeforeLast('|') else extra
            // A repo build can move a file without changing it (a new branch,
            // `refs/heads/x` vs `x`, the jsDelivr mirror), so index the
            // installed file under every spelling of its source URL — else the
            // update check can't find the file and never offers the Update.
            for (key in SourceUrls.matchKeys(source)) onDisk.putIfAbsent(key, p.url)
            sourceOfPath.putIfAbsent(p.url, source)
        }
        val outdated = HashSet<String>()
        if (onDisk.isNotEmpty()) {
            for (plugins in pluginsByRepo.value.values) {
                for (plugin in plugins) {
                    val hash = plugin.fileHash ?: continue
                    if (!hash.startsWith("sha256-")) continue
                    val path = onDisk[plugin.url] ?: continue
                    val expected = hash.removePrefix("sha256-").lowercase()
                    val actual = runCatching { sha256Hex(File(path).readBytes()) }.getOrNull()
                        ?: continue
                    if (actual == expected) continue
                    outdated += plugin.url
                    // Light the Update button up on the installed-provider row
                    // too, whatever spelling that row's stored source has.
                    sourceOfPath[path]?.let { s -> outdated += SourceUrls.matchKeys(s) }
                }
            }
        }
        outdatedUrls.value = outdated
    }

    /**
     * Re-installs already-installed extensions from their repo's current file —
     * the Update button on a row, and Update all. [items] are run one after
     * another inside ONE background job (an install cancels the previous job,
     * so a loop of individual installs would cancel itself).
     */
    fun updatePlugins(items: List<Pair<Cs3RepoPlugin, RepoKind>>) {
        if (items.isEmpty()) return
        installJob?.cancel()
        installJob = startBackground {
            clearStatus()
            var ok = 0
            val failed = mutableListOf<String>()
            for ((i, item) in items.withIndex()) {
                val (p, kind) = item
                _busyMsg.value = "Updating ${p.name} (${i + 1}/${items.size})…"
                val r = runCatching {
                    withTimeoutOrNull(90_000) {
                        when (effectiveRepoKind(kind, p.url)) {
                            RepoKind.CS3 -> installCs3Plugin(p)
                            RepoKind.HIKARI -> installHikiPlugin(p)
                            RepoKind.NUVIO -> installNuvioPlugin(p)
                        }
                    }
                }.getOrNull()
                if (r != null && r.isSuccess) ok++ else failed.add(p.name)
            }
            // Re-hash: anything that came back clean loses its Update button.
            checkUpdates()
            setSuccess(
                if (failed.isEmpty()) "Updated $ok extension${if (ok == 1) "" else "s"}"
                else "Updated $ok of ${items.size} — failed: " +
                    failed.take(3).joinToString(", ") + (if (failed.size > 3) "…" else "")
            )
        }
    }

    /** Removes every HIKARI provider that came from [pluginUrl]. */
    suspend fun uninstallHikiPlugin(pluginUrl: String) {
        fun fromPlugin(p: ProviderConfig) =
            p.type == ProviderType.HIKARI && sourceMatches(p, pluginUrl)
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

/** A repo's [RepoKind] is only a default: a Hikari repo can also list native
 *  CloudStream `.cs3` plugins (and a CloudStream repo can list `.hiki` ones), so
 *  the file's own extension decides how it is installed. Installing a `.cs3`
 *  through the `.hiki` path fails with "manifest.json has no mainClass", because
 *  a CloudStream plugin's manifest has no `mainClass` entry. */
private fun effectiveRepoKind(default: RepoKind, url: String): RepoKind = when {
    url.endsWith(".cs3", ignoreCase = true) -> RepoKind.CS3
    url.endsWith(".hiki", ignoreCase = true) -> RepoKind.HIKARI
    else -> default
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
    val outdated by vm.outdatedUrls.collectAsState()
    // Every outdated plugin together with the kind of repo it came from, so
    // "Update all" can re-install each one the same way its row would.
    val outdatedItems = remember(outdated, pluginsByRepo, repos) {
        repos.flatMap { repo ->
            (pluginsByRepo[repo.url] ?: emptyList()).map { repo.kind to it }
        }.filter { (_, p) -> p.url in outdated }
    }
    val repoState by vm.repoState.collectAsState()
    val bundleRepos by vm.bundleRepos.collectAsState()
    val sites by vm.sites.collectAsState()
    val openRepo = repos.firstOrNull { it.url == openRepoUrl }
    val context = LocalContext.current

    var showSite by remember { mutableStateOf(false) }
    var siteName by remember { mutableStateOf("") }
    var siteUrl by remember { mutableStateOf("") }
    var settingsProvider by remember { mutableStateOf<ContentProvider?>(null) }

    fun openProviderSettings(p: ContentProvider) {
        openProviderSettingsSafely(p, context, scope) { settingsProvider = p }
    }

    LaunchedEffect(Unit) {
        vm.loadReposIfNeeded()
    }

    // Re-hash installed extensions whenever a plugin list or the installed set
    // changes: a completed install/update clears its own Update button, and a
    // refreshed repo reveals a new one.
    LaunchedEffect(pluginsByRepo, installed, providers) {
        vm.checkUpdates()
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
            when (effectiveRepoKind(kind, p.url)) {
                RepoKind.CS3 -> vm.installCs3Plugin(p)
                RepoKind.HIKARI -> vm.installHikiPlugin(p)
                RepoKind.NUVIO -> vm.installNuvioPlugin(p)
            }
        }
    }

    fun uninstallPlugin(p: Cs3RepoPlugin, kind: RepoKind) {
        vm.runUninstall("Uninstalling ${p.name}…", "Uninstalled ${p.name}") {
            when (effectiveRepoKind(kind, p.url)) {
                RepoKind.CS3 -> vm.uninstallCs3Plugin(p.url)
                RepoKind.HIKARI -> vm.uninstallHikiPlugin(p.url)
                RepoKind.NUVIO -> vm.uninstallNuvioPlugin(p.url)
            }
        }
    }

    /** Update from the Installed list: the row only knows the provider, so the
     *  matching repo entry (which carries the file + hash to download) is looked
     *  up in the update list. */
    fun updateProvider(prov: ContentProvider) {
        val source = providerSource(prov) ?: return
        // The installed row stores the spelling that was live at install time;
        // the repo entry may spell the same file differently by now.
        val wanted = SourceUrls.matchKeys(source)
        val match = outdatedItems.firstOrNull { (_, plugin) ->
            plugin.url == source || SourceUrls.matchKeys(plugin.url).any { it in wanted }
        }
        if (match == null) {
            vm.setError("This extension's repo isn't loaded — refresh the repo in Extensions.")
            return
        }
        vm.updatePlugins(listOf(match.second to match.first))
    }

    val folder = openFolder

    BackHandler(
        enabled = openRepo != null || folder != null || allReposOpen || installedOpen || sourcesOpen,
    ) {
        when {
            openRepo != null -> {
                openRepoUrl = null
                vm.clearStatus()
            }
            folder != null -> {
                openFolder = null
                vm.clearStatus()
            }
            allReposOpen -> allReposOpen = false
            installedOpen -> installedOpen = false
            sourcesOpen -> sourcesOpen = false
        }
    }

    when {
        openRepo != null -> RepoPluginsView(
            repo = openRepo,
            plugins = pluginsByRepo[openRepo.url] ?: emptyList(),
            state = repoState[openRepo.url] ?: RepoLoadState(loading = true),
            isBundle = openRepo.url in bundleRepos,
            providers = providers,
            installedUrls = installed,
            outdatedUrls = outdated,
            busy = busy,
            busyMsg = busyMsg,
            successMsg = successMsg,
            errorMsg = errorMsg,
            onBack = { openRepoUrl = null; vm.clearStatus() },
            onRefresh = { vm.refreshRepo(openRepo) },
            onInstall = { installPlugin(it, openRepo.kind) },
            onUninstall = { uninstallPlugin(it, openRepo.kind) },
            onUpdate = { vm.updatePlugins(listOf(it to openRepo.kind)) },
            onOpenSettings = { openProviderSettings(it) },
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
            onOpenSettings = { openProviderSettings(it) },
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
            outdatedUrls = outdated,
            onUpdateProvider = { prov -> updateProvider(prov) },
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
            onOpenSettings = { openProviderSettings(it) },
        )
        else -> RepoBrowserView(
            repos = repos,
            pluginsByRepo = pluginsByRepo,
            repoState = repoState,
            providers = providers,
            sites = sites,
            busy = busy,
            onEnsureReposLoaded = { vm.loadReposIfNeeded() },
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
            installedUrls = installed,
            outdatedUrls = outdated,
            onUpdateAll = { vm.updatePlugins(outdatedItems.map { (kind, p) -> p to kind }) },
            onInstallPlugin = { p, kind -> installPlugin(p, kind) },
            onUninstallPlugin = { p, kind -> uninstallPlugin(p, kind) },
            onUpdatePlugin = { p, kind -> vm.updatePlugins(listOf(p to kind)) },
            onDeleteProvider = { id -> scope.launch { vm.remove(id) } },
            onToggleProvider = { id, enabled -> scope.launch { vm.toggle(id, enabled) } },
            onOpenSettings = { openProviderSettings(it) },
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
                    Spacer(Modifier.height(6.dp))
                    Text(
                        tr(
                            "Short names work too — CloudStream repos: megarepo (every " +
                                "CloudStream repo), csofficial, phisher, hexated, csx, cnc, " +
                                "aniyomi, uk, italian, italiaInStreaming, german, turkish, " +
                                "indostream, skillshare, luna712, redowan, dogior, cskarma, " +
                                "storm, cinephile, fstream, hikari. Nuvio repos: nuvio, yoru, " +
                                "gowaru, phishernuvio, allinone, michat88, spidey, saimuel, " +
                                "mooncrown, kennethjys, eclipsia."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = repoUrl,
                        onValueChange = { repoUrl = it },
                        placeholder = { Text(tr("https://…/repo.json")) },
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
                                vm.setSuccess(
                                    if (vm.duplicateRepoAdd) "Repo already added: ${repo.name}"
                                    else "Added repo: ${repo.name}"
                                )
                                vm.refreshRepo(repo)
                            },
                        )
                    }
                ) { Text(tr("Add")) }
            },
            dismissButton = {
                TextButton(onClick = { if (!busy) showRepoDialog = false }) { Text(tr("Cancel")) }
            }
        )
    }

    if (showStremio) {
        AlertDialog(
            onDismissRequest = { if (!busy) showStremio = false },
            title = { Text(tr("Add Stremio addon")) },
            text = {
                Column {
                    Text(tr("Paste the addon URL — it must serve a manifest.json."))
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = stremioUrl,
                        onValueChange = { stremioUrl = it },
                        placeholder = { Text(tr("https://addon.example.com")) },
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
                            successMsg = I18n.t("Added Stremio addon"),
                        )
                    }
                ) { Text(tr("Add")) }
            },
            dismissButton = {
                TextButton(onClick = { if (!busy) showStremio = false }) { Text(tr("Cancel")) }
            }
        )
    }

    if (showScraper) {
        AlertDialog(
            onDismissRequest = { if (!busy) showScraper = false },
            title = { Text(tr("Add universal scraper")) },
            text = {
                Column {
                    Text(tr("Paste the JSON config (name + baseUrl + search/episodes/streams rules)."))
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = scraperJson,
                        onValueChange = { scraperJson = it },
                        placeholder = { Text(tr("{\n  \"name\": \"MySite\",\n  \"baseUrl\": \"https://…\",\n  …\n}")) },
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
                            successMsg = I18n.t("Added scraper"),
                        )
                    }
                ) { Text(tr("Add")) }
            },
            dismissButton = {
                TextButton(onClick = { if (!busy) showScraper = false }) { Text(tr("Cancel")) }
            }
        )
    }

    if (showCs3Url) {
        AlertDialog(
            onDismissRequest = { if (!busy) showCs3Url = false },
            title = { Text(tr("Install .cs3 plugin")) },
            text = {
                Column {
                    Text(tr("Paste a direct link to a compiled CloudStream .cs3 file."))
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = cs3Url,
                        onValueChange = { cs3Url = it },
                        placeholder = { Text(tr("https://…/JustAnimeProvider.cs3")) },
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
                ) { Text(tr("Install")) }
            },
            dismissButton = {
                TextButton(onClick = { if (!busy) showCs3Url = false }) { Text(tr("Cancel")) }
            }
        )
    }

    if (showHikiUrl) {
        AlertDialog(
            onDismissRequest = { if (!busy) showHikiUrl = false },
            title = { Text(tr("Install .hiki extension")) },
            text = {
                Column {
                    Text(
                        tr(
                            "Paste a direct link to a compiled Hikari extension (.hiki). " +
                                "Extensions run against Hikari's own SDK — no CloudStream " +
                                "dependencies, Cloudflare solvers and WebView stream capture " +
                                "built in. See docs/HIKARI_EXTENSIONS.md."
                        )
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = hikiUrl,
                        onValueChange = { hikiUrl = it },
                        placeholder = { Text(tr("https://…/MyExtension.hiki")) },
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
                ) { Text(tr("Install")) }
            },
            dismissButton = {
                TextButton(onClick = { if (!busy) showHikiUrl = false }) { Text(tr("Cancel")) }
            }
        )
    }

    if (showSite) {
        AlertDialog(
            onDismissRequest = { if (!busy) showSite = false },
            title = { Text(tr("Add website")) },
            text = {
                Column {
                    Text(
                        tr(
                            "Paste the URL of any movie/streaming website. It opens in an " +
                                "ad-free web view — ads, trackers and popups are blocked, " +
                                "and videos can be handed to the built-in player."
                        )
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = siteName,
                        onValueChange = { siteName = it },
                        placeholder = { Text(tr("Name (optional)")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = siteUrl,
                        onValueChange = { siteUrl = it },
                        placeholder = { Text(tr("https://example.com")) },
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
                                successMsg = I18n.t("Website added"),
                            )
                        }
                    }
                ) { Text(tr("Add")) }
            },
            dismissButton = {
                TextButton(onClick = { if (!busy) showSite = false }) { Text(tr("Cancel")) }
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
    onEnsureReposLoaded: () -> Unit,
    installedUrls: Set<String>,
    outdatedUrls: Set<String> = emptySet(),
    onUpdateAll: () -> Unit = {},
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
    onInstallPlugin: (Cs3RepoPlugin, RepoKind) -> Unit,
    onUninstallPlugin: (Cs3RepoPlugin, RepoKind) -> Unit,
    onUpdatePlugin: (Cs3RepoPlugin, RepoKind) -> Unit = { _, _ -> },
    onDeleteProvider: (String) -> Unit,
    onToggleProvider: (String, Boolean) -> Unit,
    onOpenSettings: (ContentProvider) -> Unit,
) {
    // Search across EVERYTHING on this screen: installed extensions (with
    // uninstall/toggle) and every added repo's plugin list (with instant
    // install/uninstall) — so a user with hundreds of extensions can find and
    // act on one by typing its name instead of scrolling. Typing also kicks the
    // repos that haven't loaded yet, so a fresh install still finds the
    // not-yet-installed entries instead of only the installed ones.
    var query by rememberSaveable { mutableStateOf("") }
    val cs3SettingsIds = rememberCs3SettingsIds(providers)
    LaunchedEffect(query) {
        if (query.isNotBlank()) onEnsureReposLoaded()
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp)) {
                Text(
                    tr("Extensions"),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    tr("Sources, repos & providers"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item {
            GlassSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = tr("Search extensions & sources…"),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        // Updates found by re-hashing every installed extension against its
        // repo's published fileHash (see ExtensionsViewModel.checkUpdates).
        if (query.isBlank() && outdatedUrls.isNotEmpty()) {
            item {
                GlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                tr("Extension updates available"),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                "${outdatedUrls.size} installed " +
                                    "extension${if (outdatedUrls.size == 1) "" else "s"} " +
                                    "can be updated",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Button(onClick = onUpdateAll, enabled = !busy) {
                            Text(tr("Update all"))
                        }
                    }
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
        if (query.isNotBlank()) {
            extensionsSearchItems(
                query = query,
                repos = repos,
                pluginsByRepo = pluginsByRepo,
                repoState = repoState,
                providers = providers,
                installedUrls = installedUrls,
                outdatedUrls = outdatedUrls,
                busy = busy,
                onOpenRepo = onOpenRepo,
                onRefreshRepo = onRefreshRepo,
                onInstallPlugin = onInstallPlugin,
                onUninstallPlugin = onUninstallPlugin,
                onUpdatePlugin = { p, kind -> onUpdatePlugin(p, kind) },
                onDeleteProvider = onDeleteProvider,
                onToggleProvider = onToggleProvider,
                cs3SettingsIds = cs3SettingsIds,
                onOpenSettings = onOpenSettings,
            )
            return@LazyColumn
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
                        title = tr("CloudStream repos"),
                        subtitle = tr("repo.json · CloudStream extensions"),
                        onClick = { onOpenFolder(SourceFolder.CLOUDSTREAM) }
                    )
                    SourceDivider()
                    SourceActionRow(
                        icon = Icons.Filled.Extension,
                        title = tr("Hikari repos"),
                        subtitle = tr("repo.json · Hikari extensions"),
                        onClick = { onOpenFolder(SourceFolder.HIKARI) }
                    )
                    SourceDivider()
                    SourceActionRow(
                        icon = Icons.Filled.FolderOpen,
                        title = tr("Nuvio repos"),
                        subtitle = tr("manifest.json · Nuvio providers"),
                        onClick = { onOpenFolder(SourceFolder.NUVIO) }
                    )
                    SourceDivider()
                    SourceActionRow(
                        icon = Icons.Filled.PlayArrow,
                        title = tr("Stremio addons"),
                        subtitle = tr("manifest.json · Stremio addons"),
                        onClick = { onOpenFolder(SourceFolder.STREMIO) }
                    )
                    SourceDivider()
                    SourceActionRow(
                        icon = Icons.Filled.Build,
                        title = tr("Add universal scraper"),
                        subtitle = tr("JSON config · scriptable scraper"),
                        onClick = onAddScraper
                    )
                    SourceDivider()
                    SourceActionRow(
                        icon = Icons.Filled.Download,
                        title = tr("Install .cs3 plugin"),
                        subtitle = tr("From a URL or a local file"),
                        onClick = onAddCs3Url,
                        trailingIcon = Icons.Filled.FolderOpen,
                        onTrailing = onPickCs3File
                    )
                    SourceDivider()
                    SourceActionRow(
                        icon = Icons.Filled.Add,
                        title = tr("Install .hiki extension"),
                        subtitle = tr("From a URL or a local file"),
                        onClick = onAddHikiUrl,
                        trailingIcon = Icons.Filled.FolderOpen,
                        onTrailing = onPickHikiFile
                    )
                    SourceDivider()
                    SourceActionRow(
                        icon = Icons.Filled.Public,
                        title = tr("Add website"),
                        subtitle = tr("Opens in the ad-free web view"),
                        onClick = onAddSite
                    )
                    SourceDivider()
                    SourceActionRow(
                        icon = Icons.Filled.Folder,
                        title = tr("All installed repos"),
                        subtitle = tr("All repos you've added · ") + repos.size + " total",
                        onClick = onOpenAllRepos
                    )
                    SourceDivider()
                    SourceActionRow(
                        icon = Icons.Filled.Extension,
                        title = tr("Installed extensions"),
                        subtitle = tr("Manage, toggle & uninstall · ") + providers.size + " installed",
                        onClick = onOpenInstalled
                    )
                }
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

/**
 * Search results for the Extensions hub: filters BOTH the installed
 * extensions and every plugin listed by every loaded repo, so a user with
 * hundreds of sources can type a name and act on the match immediately
 * (install / uninstall / toggle / delete) without navigating into each repo.
 */
private fun LazyListScope.extensionsSearchItems(
    query: String,
    repos: List<Cs3Repo>,
    pluginsByRepo: Map<String, List<Cs3RepoPlugin>>,
    repoState: Map<String, RepoLoadState>,
    providers: List<ContentProvider>,
    installedUrls: Set<String>,
    outdatedUrls: Set<String>,
    busy: Boolean,
    onOpenRepo: (Cs3Repo) -> Unit,
    onRefreshRepo: (Cs3Repo) -> Unit,
    onInstallPlugin: (Cs3RepoPlugin, RepoKind) -> Unit,
    onUninstallPlugin: (Cs3RepoPlugin, RepoKind) -> Unit,
    onUpdatePlugin: (Cs3RepoPlugin, RepoKind) -> Unit,
    onDeleteProvider: (String) -> Unit,
    onToggleProvider: (String, Boolean) -> Unit,
    cs3SettingsIds: Set<String>,
    onOpenSettings: (ContentProvider) -> Unit,
) {
    val q = query.trim()
    val installedMatches = providers.filter { it.config.name.contains(q, ignoreCase = true) }
    val pluginMatches = repos.flatMap { repo ->
        (pluginsByRepo[repo.url] ?: emptyList())
            .filter { it.name.contains(q, ignoreCase = true) || it.url.contains(q, ignoreCase = true) }
            .map { repo to it }
    }
    // Repos we added but whose plugin list hasn't arrived yet (still loading, or
    // the fetch failed). Surfaced below so a search never silently hides the
    // installable entries of a repo that's slow/failing.
    val pendingRepos = repos.filter { pluginsByRepo[it.url] == null }
    val failedRepos = pendingRepos.filter { repoState[it.url]?.error != null }
    val stillLoading = pendingRepos.isNotEmpty()

    if (installedMatches.isEmpty() && pluginMatches.isEmpty()) {
        item {
            EmptyState(
                title = if (stillLoading) "Searching…" else "No matches",
                subtitle = if (stillLoading)
                    "Repos are still loading — results will appear as they arrive."
                else
                    "Nothing matches \"$q\". Try a different name.",
                actionLabel = if (failedRepos.isNotEmpty()) "Retry failed repos" else null,
                action = if (failedRepos.isNotEmpty()) {
                    { failedRepos.forEach { onRefreshRepo(it) } }
                } else null
            )
        }
        if (pendingRepos.isNotEmpty()) repoStatusItems(pendingRepos, repoState, onRefreshRepo)
        return
    }

    if (installedMatches.isNotEmpty()) {
        item { SectionHeader("Installed · ${installedMatches.size}") }
        items(installedMatches, key = { "inst-" + it.config.id }) { p ->
            ProviderCard(
                p = p,
                status = pluginStatus(p),
                onToggle = { enabled -> onToggleProvider(p.config.id, enabled) },
                onDelete = { onDeleteProvider(p.config.id) },
                onSettings = when {
                    p.config.type == ProviderType.NUVIO -> { { onOpenSettings(p) } }
                    p.config.id in cs3SettingsIds -> { { onOpenSettings(p) } }
                    else -> null
                },
            )
        }
    }

    if (pluginMatches.isNotEmpty()) {
        item { SectionHeader("Repos · ${pluginMatches.size}") }
        items(pluginMatches, key = { "plug-" + it.first.url + "|" + it.second.url }) { match ->
            val repo = match.first
            val p = match.second
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                TextButton(
                    onClick = { onOpenRepo(repo) },
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                ) {
                    Text(
                        repo.name.ifBlank { repo.url },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                PluginRow(
                    p = p,
                    installed = p.url in installedUrls,
                    onInstall = { onInstallPlugin(p, repo.kind) },
                    onUninstall = { onUninstallPlugin(p, repo.kind) },
                    onSettings = repoPluginSettingsTarget(p, providers, cs3SettingsIds)
                        ?.let { target -> { onOpenSettings(target) } },
                    updateAvailable = p.url in outdatedUrls,
                    onUpdate = { onUpdatePlugin(p, repo.kind) },
                )
            }
        }
    }

    if (pendingRepos.isNotEmpty()) repoStatusItems(pendingRepos, repoState, onRefreshRepo)
}

/** Rows for repos whose plugin list isn't loaded yet — a spinner while it's on
 *  its way, or the error plus a Retry button when the fetch failed. Keeps the
 *  search results honest instead of quietly omitting those repos' entries. */
private fun LazyListScope.repoStatusItems(
    repos: List<Cs3Repo>,
    repoState: Map<String, RepoLoadState>,
    onRefreshRepo: (Cs3Repo) -> Unit,
) {
    item { SectionHeader("Repos loading · ${repos.size}") }
    items(repos, key = { "pending-" + it.url }) { repo ->
        val state = repoState[repo.url]
        val err = state?.error
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    repo.name.ifBlank { repo.url },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    when {
                        err != null -> err
                        state?.loading == true -> "Loading plugins…"
                        else -> "Not loaded yet"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (err != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (err != null) {
                TextButton(onClick = { onRefreshRepo(repo) }) { Text(tr("Retry")) }
            } else {
                CircularProgressIndicator(
                    Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
            }
        }
    }
}

@Composable
private fun RepoPluginsView(
    repo: Cs3Repo,
    plugins: List<Cs3RepoPlugin>,
    state: RepoLoadState,
    isBundle: Boolean = false,
    providers: List<ContentProvider>,
    installedUrls: Set<String>,
    outdatedUrls: Set<String>,
    busy: Boolean,
    busyMsg: String,
    successMsg: String?,
    errorMsg: String?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onInstall: (Cs3RepoPlugin) -> Unit,
    onUninstall: (Cs3RepoPlugin) -> Unit,
    onUpdate: (Cs3RepoPlugin) -> Unit,
    onOpenSettings: (ContentProvider) -> Unit,
    onInstallAll: () -> Unit,
) {
    val cs3SettingsIds = rememberCs3SettingsIds(providers)
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
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Back"))
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
                    contentDescription = tr("Refresh repo"),
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
                        Text(
                            tr("Install all (%s)").replace("%s", uninstalled.toString())
                        )
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
                    TextButton(onClick = onRefresh) { Text(tr("Retry")) }
                }
                plugins.isEmpty() && isBundle -> item {
                    Text(
                        "This is a bundle repo — it holds no ${unit}s of its own. " +
                            "Its repos were added to your repo list: open Phisher, CNC, CSX… " +
                            "and install ${unit}s from there.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
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
                        onUninstall = { onUninstall(p) },
                        onSettings = repoPluginSettingsTarget(p, providers, cs3SettingsIds)
                            ?.let { target -> { onOpenSettings(target) } },
                        updateAvailable = p.url in outdatedUrls,
                        onUpdate = { onUpdate(p) },
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

/**
 * Provider ids whose CloudStream plugin exposes its own settings screen
 * (`Plugin.openSettings`, e.g. SKTech's sub-provider picker). Resolved off the
 * main thread because loading a plugin can block; until the answer arrives the
 * card simply shows no settings button, which is exactly CloudStream's rule.
 */
@Composable
private fun rememberCs3SettingsIds(providers: List<ContentProvider>): Set<String> {
    var ids by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(providers) {
        ids = withContext(Dispatchers.IO) {
            providers.mapNotNull { p ->
                if (p.config.type != ProviderType.CS3) return@mapNotNull null
                if (runCatching { p.settingsAvailable }.getOrDefault(false)) p.config.id else null
            }.toSet()
        }
    }
    return ids
}

/**
 * Opens a provider's own settings screen (the gear on an extension card).
 *
 * A plugin's settings screen is arbitrary third-party code (an Activity, a
 * BottomSheetDialogFragment, …) and it only exists once the plugin has been
 * loaded — but the gear is drawn from the stored provider list, which outlives
 * the in-memory plugin instance. A tap could therefore land before the plugin
 * was loaded (or while a reload was still running) and be reported as "has no
 * settings screen", then work on a second try. Load the plugin first (off the
 * main thread), then invoke the plugin's own screen on the main thread — and
 * surface the real reason when it still cannot open, instead of letting the tap
 * look like a dead no-op.
 */
private fun openProviderSettingsSafely(
    p: ContentProvider,
    context: Context,
    scope: CoroutineScope,
    nonCs3: () -> Unit,
) {
    if (p !is Cs3MainApiProvider) {
        nonCs3()
        return
    }
    if (!p.settingsReady) {
        Toast.makeText(context, "Loading ${p.config.name}…", Toast.LENGTH_SHORT).show()
    }
    scope.launch {
        var ready = withContext(Dispatchers.IO) {
            runCatching { p.prepareSettings() }.getOrDefault(false)
        }
        var opened = ready && withContext(Dispatchers.Main) {
            runCatching { p.openSettings(HikariApp.mainActivity) }.getOrDefault(false)
        }
        // A plugin's settings screen is third-party code, and some of them bail
        // out on a transient condition (an activity that was briefly stopped
        // while the plugin was being loaded, a sheet that was left behind).
        // Reload the plugin and try once more before reporting a failure.
        if (!opened) {
            kotlinx.coroutines.delay(300)
            ready = withContext(Dispatchers.IO) {
                runCatching { p.prepareSettings() }.getOrDefault(false)
            }
            opened = ready && withContext(Dispatchers.Main) {
                runCatching { p.openSettings(HikariApp.mainActivity) }.getOrDefault(false)
            }
        }
        if (opened) return@launch
        val detail = Cs3PluginManager.lastError.orEmpty()
        if (detail.isBlank()) {
            Toast.makeText(
                context,
                I18n.t("%s has no settings screen").replace("%s", p.config.name),
                Toast.LENGTH_LONG,
            ).show()
            return@launch
        }
        // The reason a plugin's own screen refused to open is a full exception
        // description (the plugin class and line now included) — far too long
        // for a toast, which only ever showed "… threw: Il…". Show it in a
        // dialog the user can read and copy, so a broken extension settings
        // screen can actually be reported instead of guessed at.
        showSettingsFailureDialog(context, p.config.name, detail)
    }
}

/** Full, copyable report of why an extension's own settings screen didn't
 *  open. The text is whatever [Cs3PluginManager.lastError] recorded — the
 *  plugin's exception class, message and first stack frames — plus a line
 *  telling the user what to do with it. */
private fun showSettingsFailureDialog(context: Context, name: String, detail: String) {
    val activity = context as? android.app.Activity
        ?: HikariApp.mainActivity
        ?: return
    runCatching {
        val report = "${name}: $detail"
        android.app.AlertDialog.Builder(activity)
            .setTitle(I18n.t("Couldn't open %s settings").replace("%s", name))
            .setMessage(report)
            .setPositiveButton(I18n.t("Copy report")) { _, _ ->
                runCatching {
                    val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    cm.setPrimaryClip(
                        android.content.ClipData.newPlainText("Hikari settings error", report)
                    )
                    Toast.makeText(activity, I18n.t("Report copied"), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(I18n.t("Close"), null)
            .show()
    }
}

@Composable
private fun ProviderCard(
    p: ContentProvider,
    status: String?,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onSettings: (() -> Unit)? = null,
    updateAvailable: Boolean = false,
    onUpdate: (() -> Unit)? = null,
) {    GlassCard(Modifier
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
                if (updateAvailable) {
                    Text(
                        tr("Update available"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            Switch(checked = p.config.enabled, onCheckedChange = onToggle)
            if (updateAvailable && onUpdate != null) {
                IconButton(onClick = onUpdate) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = tr("Update"),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            if (onSettings != null) {
                IconButton(onClick = onSettings) {
                    Icon(
                        Icons.Filled.Settings,
                        contentDescription = tr("Provider settings"),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = tr("Remove"),
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
            loadError = I18n.t("Provider file missing — reinstall this extension")
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
        title = { Text("${provider.config.name} " + tr("Settings")) },
        text = {
            when {
                loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(tr("Loading settings…"))
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
                else -> Text(tr("No settings available"))
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
            ) { Text(tr("Save")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("Cancel")) }
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
                    contentDescription = tr("Refresh repo"),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onRemoveRepo) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = tr("Remove repo"),
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
    onSettings: (() -> Unit)? = null,
    updateAvailable: Boolean = false,
    onUpdate: (() -> Unit)? = null,
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
        if (installed && onSettings != null) {
            IconButton(onClick = onSettings) {
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = tr("Plugin settings"),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        if (installed) {
            if (updateAvailable && onUpdate != null) {
                TextButton(onClick = onUninstall) {
                    Text(tr("Uninstall"), color = MaterialTheme.colorScheme.error)
                }
                Button(
                    onClick = onUpdate,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(tr("Update"))
                }
            } else {
                TextButton(onClick = onUninstall) {
                    Text(tr("Uninstall"), color = MaterialTheme.colorScheme.error)
                }
            }
        } else {
            Button(onClick = onInstall) {
                Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(tr("Install"))
            }
        }
    }
}

/**
 * The installed provider behind a repo listing, when it exposes its own
 * settings screen (CloudStream's tune button). CS3 plugins are matched by the
 * source URL stored in [ProviderConfig.extra] — the same key uninstall uses —
 * and gated on [cs3SettingsIds]; Nuvio providers always offer their
 * permissions/settings screen; Hikari extensions append a "|index" suffix to
 * their source URL.
 */
/** The repo URL an installed provider was installed from — the key both
 *  uninstall and the update check use. Hikari extensions append a "|index"
 *  suffix (one file can hold several providers); a provider installed from a
 *  local file has no repo URL at all. */
private fun providerSource(p: ContentProvider): String? {
    val extra = p.config.extra ?: return null
    if (!extra.startsWith("http")) return null
    return if (p.config.type == ProviderType.HIKARI) extra.substringBeforeLast('|') else extra
}

private fun repoPluginSettingsTarget(
    plugin: Cs3RepoPlugin,
    providers: List<ContentProvider>,
    cs3SettingsIds: Set<String>,
): ContentProvider? {
    val wanted = SourceUrls.matchKeys(plugin.url)
    return providers.firstOrNull { p ->
        val extra = p.config.extra ?: return@firstOrNull false
        val source = if (p.config.type == ProviderType.HIKARI) extra.substringBeforeLast('|') else extra
        if (SourceUrls.matchKeys(source).none { it in wanted }) return@firstOrNull false
        p.config.type == ProviderType.NUVIO || p.config.id in cs3SettingsIds
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
    if (err != null) {
        // A verification wall is reported in Hikari's own words: the
        // extension's raw text is about CloudStream's settings screen and is
        // meaningless here (see CloudflareVerifier.isVerificationMessage).
        if (com.hikari.app.net.CloudflareVerifier.isVerificationMessage(err)) {
            return "Site needs a Cloudflare verification — open this extension on Home " +
                "and tap the WebView (globe) icon."
        }
        return err.take(200)
    }
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
                        tr("Webview sites"),
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
                        tr("Add any movie/streaming website and it opens in an ad-free web view — ads, trackers and popups blocked, with one-tap video playback in the player."),
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
                Text(tr("Open"))
            }
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = tr("Remove website"),
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
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Back"))
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
                            title = tr("No Stremio addons yet"),
                            subtitle = tr("Tap \"Add Stremio addon\" below to add your first addon."),
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
    val cs3SettingsIds = rememberCs3SettingsIds(providers)
    val cs3GroupTitle = tr("CloudStream")
    val hikiGroupTitle = tr("Hikari")
    val nuvioGroupTitle = tr("Nuvio")
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Back"))
            }
            Column(Modifier.weight(1f)) {
                Text(tr("All sources"), style = MaterialTheme.typography.titleMedium)
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
                title = cs3GroupTitle,
                groupRepos = repos.filter { it.kind == RepoKind.CS3 },
                pluginsByRepo = pluginsByRepo,
                repoState = repoState,
                onAdd = onAddRepo,
                onOpenRepo = onOpenRepo,
                onRefreshRepo = onRefreshRepo,
                onRemoveRepo = onRemoveRepo,
            )
            repoGroup(
                title = hikiGroupTitle,
                groupRepos = repos.filter { it.kind == RepoKind.HIKARI },
                pluginsByRepo = pluginsByRepo,
                repoState = repoState,
                onAdd = onAddHikiRepo,
                onOpenRepo = onOpenRepo,
                onRefreshRepo = onRefreshRepo,
                onRemoveRepo = onRemoveRepo,
            )
            repoGroup(
                title = nuvioGroupTitle,
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
                        tr("STREMIO ADDONS"),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.2.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onAddStremio) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(tr("Add"))
                    }
                }
            }
            val stremioProviders = providers.filter { it.config.type == ProviderType.STREMIO }
            if (stremioProviders.isEmpty()) {
                item {
                    Text(
                        tr("No Stremio addons yet"),
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
                    placeholder = tr("Search installed extensions…"),
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
                    onSettings = when {
                        p.config.type == ProviderType.NUVIO -> { { onOpenSettings(p) } }
                        p.config.id in cs3SettingsIds -> { { onOpenSettings(p) } }
                        else -> null
                    },
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
                Text(tr("Add"))
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
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Back"))
            }
            Column(Modifier.weight(1f)) {
                Text(tr("All installed repos"), style = MaterialTheme.typography.titleMedium)
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
                        title = tr("No repos added yet"),
                        subtitle = tr("Tap \"Add repo\" below to add a CloudStream, Hikari or Nuvio repo."),
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
        AddRepoButton(label = tr("Add repo"), onClick = onAddRepo)
    }
}

@Composable
private fun InstalledExtensionsView(
    providers: List<ContentProvider>,
    busy: Boolean,
    busyMsg: String,
    successMsg: String?,
    errorMsg: String?,
    outdatedUrls: Set<String> = emptySet(),
    onUpdateProvider: (ContentProvider) -> Unit = {},
    onBack: () -> Unit,
    onToggleProvider: (String, Boolean) -> Unit,
    onDeleteProvider: (String) -> Unit,
) {
    var extFilter by remember { mutableStateOf("") }
    var settingsProvider by remember { mutableStateOf<ContentProvider?>(null) }
    val cs3SettingsIds = rememberCs3SettingsIds(providers)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun openCs3Settings(p: ContentProvider) {
        openProviderSettingsSafely(p, context, scope) {}
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Back"))
            }
            Column(Modifier.weight(1f)) {
                Text(tr("Installed extensions"), style = MaterialTheme.typography.titleMedium)
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
                    placeholder = tr("Search installed extensions…"),
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
                    onSettings = when {
                        p.config.type == ProviderType.NUVIO -> { { settingsProvider = p } }
                        p.config.id in cs3SettingsIds -> { { openCs3Settings(p) } }
                        else -> null
                    },
                    updateAvailable = providerSource(p) in outdatedUrls,
                    onUpdate = { onUpdateProvider(p) },
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
