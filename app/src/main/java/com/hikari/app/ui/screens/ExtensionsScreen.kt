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
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.LiveTv
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
import androidx.compose.ui.graphics.Brush
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
import com.hikari.app.net.PromoGuard
import com.hikari.app.providers.ContentProvider
import com.hikari.app.providers.ProviderManager
import com.hikari.app.ui.components.EmptyState
import com.hikari.app.ui.components.GlassCard
import com.hikari.app.ui.components.GlassSearchField
import com.hikari.app.ui.navigation.LocalTaskbarInset
import com.hikari.app.ui.theme.rememberGlassTokens
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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

    /**
     * True while an Install all / Update all run is going, and true again from
     * the moment the user asks it to stop until it has. A bulk run over a
     * hundred-odd extensions is a long job the user must be able to walk away
     * from — and the stop is deliberate about WHERE it lands: the extension
     * being installed right now is finished first (a half-downloaded `.cs3` is
     * not left in storage and not left half-registered), then the loop ends.
     */
    private val _installRunning = MutableStateFlow(false)
    val installRunning: StateFlow<Boolean> = _installRunning.asStateFlow()
    private val _installStopping = MutableStateFlow(false)
    val installStopping: StateFlow<Boolean> = _installStopping.asStateFlow()

    /** Set by [stopBulkInstall]; read at the top of every loop pass. */
    @Volatile
    private var installCancelled = false

    /** The loop generation, so a replaced job's exit cannot clear the flag of
     *  the job that replaced it. */
    private var installGeneration = 0L

    /** Asks the running Install all / Update all to finish the extension it is
     *  on and then stop. Harmless when nothing is running. */
    fun stopBulkInstall() {
        if (!_installRunning.value || installCancelled) return
        installCancelled = true
        _installStopping.value = true
        _busyMsg.value = I18n.t("Stopping after the current extension…")
    }

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
        // Holder first: the token's cancel callback has to reach the job, and the
        // job cannot exist before the token does (see BackgroundWork.cancelAll).
        var jobHolder: Job? = null
        val work = com.hikari.app.work.BackgroundWork.begin("Updating extensions") {
            jobHolder?.cancel()
        }
        val job = viewModelScope.launch {
            val gen = ++backgroundGeneration
            _busy.value = true
            try {
                block()
            } finally {
                if (backgroundGeneration == gen) _busy.value = false
            }
        }
        jobHolder = job
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

    /**
     * Uninstall task whose action reports how many installed things it actually
     * removed. A zero is never dressed up as a success: the reported "I tap
     * Uninstall and it says Uninstalled while the row still shows Uninstall"
     * came from an uninstall that matched nothing (a second copy of the same
     * extension under another URL spelling) and then said it was done. Saying
     * what really happened is what lets the user tell us which of the two it is.
     */
    fun runUninstallCounted(what: String, successMsg: (Int) -> String, action: suspend () -> Int) {
        startBackground {
            _busyMsg.value = what
            clearStatus()
            try {
                cancellableCatching { action() }
                    .onSuccess { n ->
                        if (n > 0) setSuccess(successMsg(n))
                        else setError("Nothing to uninstall — that extension isn't installed any more.")
                    }
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
            val pending = repos.value.filter { repo ->
                pluginsByRepo.value[repo.url] == null &&
                    repoState.value[repo.url]?.loading != true &&
                    repo.url !in reposLoading
            }
            if (pending.isEmpty()) return@launch
            // A few repos at a time, not one after another. The sequential loop
            // spent the whole of a big (Mega-imported) list waiting on its
            // slowest hosts one by one — minutes during which the extension
            // search only knew about the repos already read and sat on
            // "Searching…", which is the reported "stuck while searching
            // extensions". [refreshRepoPlugins] bounds each fetch, so a dead
            // host cannot hold a slot for long.
            val gate = Semaphore(4)
            kotlinx.coroutines.coroutineScope {
                for (repo in pending) {
                    reposLoading.add(repo.url)
                    launch {
                        try {
                            gate.withPermit { refreshRepoPlugins(repo) }
                        } finally {
                            reposLoading.remove(repo.url)
                        }
                    }
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

    // ---------------------------------------------------------------------
    //  IPTV
    //
    //  A playlist is added as a PROVIDER (not a repo): it has no plugin
    //  listing to browse, it just IS the channel list. Everything downstream —
    //  Home rows, the global search, the exception picker, the player's server
    //  sections — already reads the provider contract, so an IPTV provider
    //  shows up in all of them without a line of special-casing.
    // ---------------------------------------------------------------------

    /** Bumped whenever a playlist's channel count or failure text changes, so
     *  open screens recompose and show the new count. */
    val iptvTick = MutableStateFlow(0)

    /** Copies a picked playlist file into the app's own storage (a SAF Uri is
     *  not readable after a restart, and a playlist the user chose should keep
     *  working). Calls [onPicked] with a display name and the stored path. */
    fun pickIptvFile(uri: Uri, onPicked: (String, String) -> Unit) {
        viewModelScope.launch {
            val picked = withContext(Dispatchers.IO) {
                runCatching {
                    val ctx = getApplication<Application>()
                    val raw = queryDisplayName(uri) ?: uri.lastPathSegment?.substringAfterLast('/')
                    val name = raw?.trim().orEmpty().ifBlank { "playlist.m3u" }
                    val safe = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").takeLast(80)
                    val dir = File(ctx.filesDir, "iptv").apply { mkdirs() }
                    val file = File(dir, safe)
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        file.outputStream().use { out -> input.copyTo(out) }
                    } ?: throw Exception("Could not read that file")
                    if (file.length() == 0L) throw Exception("That file is empty")
                    name to file.absolutePath
                }.getOrElse {
                    setError(it.message ?: "Could not read that file")
                    null
                }
            }
            if (picked != null) onPicked(picked.first, picked.second)
        }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        getApplication<Application>().contentResolver
            .query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    /**
     * Adds (or updates) a playlist. [link] is the pasted M3U/M3U8/Xtream URL and
     * [localPath] the stored copy of a file the user picked — one of the two.
     *
     * The playlist is READ before it is saved, so a dead link or a file with no
     * channels is reported as the error it is instead of becoming an empty
     * extension the user has to work out for themselves. Adding the same link
     * again updates the one entry rather than adding a second (the id is derived
     * from the source), which is the same rule repos follow.
     */
    suspend fun addIptvPlaylist(
        link: String,
        localPath: String?,
        name: String,
    ): Result<Int> = withContext(Dispatchers.IO) {
        val url = when {
            !localPath.isNullOrBlank() -> localPath
            else -> link.trim().let {
                if (it.startsWith("http://") || it.startsWith("https://")) it
                else if (it.isBlank()) "" else "https://$it"
            }
        }
        if (url.isBlank()) {
            return@withContext Result.failure(
                Exception("Paste an M3U/M3U8 link, or pick a playlist file"),
            )
        }
        val count = com.hikari.app.providers.IptvProvider.preview(url).getOrElse {
            return@withContext Result.failure(
                Exception(it.message ?: "Could not read that playlist"),
            )
        }
        val display = name.trim().ifBlank {
            if (url.startsWith("http")) {
                url.substringAfter("://").substringBefore('/').ifBlank { "IPTV" }
            } else {
                File(url).name.substringBeforeLast('.').ifBlank { "IPTV" }
            }
        }
        val id = "iptv|" + url.hashCode()
        store.addProvider(
            ProviderConfig(
                id = id,
                name = display,
                type = ProviderType.IPTV,
                url = url,
            )
        )
        manager.refresh()
        reloadInstalled()
        warmIptv()
        Result.success(count)
    }

    /**
     * Reads every installed playlist once so the Extensions rows can say how many
     * channels each holds (and so a broken link is reported here rather than
     * discovered later on Home). Bounded to a few at a time; failures are kept in
     * [com.hikari.app.providers.IptvProvider.iptvErrors] and shown on the row.
     */
    fun warmIptv() {
        viewModelScope.launch {
            val iptv = manager.providers.value.filter { it.config.type == ProviderType.IPTV }
            if (iptv.isEmpty()) return@launch
            withContext(Dispatchers.IO) {
                iptv.chunked(3).forEach { chunk ->
                    coroutineScope {
                        chunk.forEach { p ->
                            launch {
                                runCatching {
                                    (p as? com.hikari.app.providers.IptvProvider)?.channels()
                                }
                            }
                        }
                    }
                }
            }
            iptvTick.value = iptvTick.value + 1
        }
    }

    /** Re-reads one playlist on demand (the IPTV info dialog's Refresh). */
    fun refreshIptv(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val p = manager.providers.value.firstOrNull { it.config.id == id }
                    as? com.hikari.app.providers.IptvProvider ?: return@withContext
                p.invalidate()
                val n = runCatching { p.channels(force = true) }.getOrDefault(emptyList()).size
                if (n > 0) setSuccess("Read $n channels from ${p.displayName}")
            }
            iptvTick.value = iptvTick.value + 1
        }
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
        // An IPTV playlist that was imported from storage is the app's own copy
        // of it — removing the provider removes the file too (a playlist the
        // user pasted as a link has nothing local to clean up).
        if (target != null && target.type == ProviderType.IPTV) {
            val path = target.url
            val base = getApplication<Application>().filesDir.absolutePath + "/iptv/"
            if (path.startsWith(base) && store.providers().none { it.url == path }) {
                withContext(Dispatchers.IO) { runCatching { File(path).delete() } }
            }
        }
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
        // Aniyomi extensions live in `filesDir/aniyomi/exts` and are shared by
        // every source they publish — only the last referencing provider takes
        // the `.ext` with it.
        if (target != null && target.type == ProviderType.ANIYOMI &&
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
        val base = rawName.substringAfterLast('/').ifBlank { "extension" }
            .let { if (it.endsWith(".hiki", true)) it.dropLast(5) else it }
            .trim().ifBlank { "extension" }
            .let { it.replace(Regex("[^A-Za-z0-9._ -]"), "_") }
        // Same rule as installCs3Bytes: the local FILE name decides the provider
        // ids ("hiki|<name.hashCode>|i"), and .hiki files are almost always
        // called `extension.hiki`, so two repos' extensions shared one file —
        // the second install overwrote the first, and uninstalling either one
        // deleted the file the other was still loaded from. Stamp the source
        // URL into the name so a file has a per-source identity.
        val clean = if (sourceUrl.isNullOrBlank()) "$base.hiki"
        else "$base-${shortHash(SourceUrls.canonical(sourceUrl))}.hiki"
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
        // One source URL must never leave two copies behind — same as the CS3
        // path: a re-install that follows a name-only file left by an older
        // build would otherwise keep that old copy (and its file) as a phantom
        // second extension that no longer has an Install/Uninstall of its own.
        if (!sourceUrl.isNullOrBlank()) {
            val stale = store.providers().filter {
                it.type == ProviderType.HIKARI && it.url != file.absolutePath &&
                    sourceMatches(it, sourceUrl)
            }
            if (stale.isNotEmpty()) {
                val staleIds = stale.map { it.id }.toSet()
                val stalePaths = stale.map { it.url }.toSet()
                store.updateProviders { list -> list.filterNot { it.id in staleIds } }
                manager.refresh()
                reloadInstalled()
                withContext(Dispatchers.IO) {
                    val keep = store.providers().map { it.url }.toSet()
                    val root = getApplication<Application>().filesDir.absolutePath
                    stalePaths.forEach { p ->
                        if (p.startsWith(root) && p !in keep) {
                            runCatching { File(p).delete() }
                        }
                    }
                }
            }
        }
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
        val base = rawName.substringAfterLast('/').ifBlank { "plugin" }
            .let { if (it.endsWith(".cs3", true)) it.dropLast(4) else it }
            .trim().ifBlank { "plugin" }
            .let { it.replace(Regex("[^A-Za-z0-9._ -]"), "_") }
        // The local FILE name decides the provider ids ("cs3|<name.hashCode>|i"),
        // so two repos publishing different plugins under the same name shared
        // one file: the second install overwrote the first, and uninstalling
        // either deleted the file the other was loaded from — which is the
        // reported "I uninstalled one extension in a repo and the rest of them
        // showed Install again". The source URL is therefore stamped into the
        // name, exactly like the id scheme already assumed it was unique.
        val clean = if (sourceUrl.isNullOrBlank()) "$base.cs3"
        else "$base-${shortHash(SourceUrls.canonical(sourceUrl))}.cs3"
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
            val name = api.name.ifBlank { base }
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
        // One source URL must never leave two copies behind. Re-installing an
        // extension used to add a second config pointing at the SAME file; with
        // the name now stamped per source URL, an install that follows an older
        // build's name-only file would likewise leave the old copy (and its
        // file) sitting there as a phantom second row. Drop every other CS3
        // config that came from this exact source, plus its file once nothing
        // references it.
        if (!sourceUrl.isNullOrBlank()) {
            val stale = store.providers().filter {
                it.type == ProviderType.CS3 && it.url != file.absolutePath &&
                    sourceKeyMatches(it.extra, sourceUrl)
            }
            if (stale.isNotEmpty()) {
                val stalePaths = stale.map { it.url }.toSet()
                store.updateProviders { list ->
                    list.filterNot { it.type == ProviderType.CS3 && it.url in stalePaths }
                }
                withContext(Dispatchers.IO) {
                    val keep = store.providers().map { it.url }.toSet()
                    val root = getApplication<Application>().filesDir.absolutePath
                    stalePaths.forEach { p ->
                        if (p.startsWith(root) && p !in keep) {
                            runCatching { File(p).delete() }
                        }
                    }
                }
            }
        }
        return Result.success(added)
    }

    suspend fun reloadInstalled() {
        installedUrls.value = buildSet {
            store.providers().forEach { p ->
                val extra = p.extra ?: return@forEach
                val source = when (p.type) {
                    ProviderType.CS3, ProviderType.NUVIO, ProviderType.SKYSTREAM,
                    ProviderType.ANIYOMI -> extra
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

    /** Removes every NUVIO provider that came from [pluginUrl]. Returns how many
     *  installed providers were actually removed (see the manager's
     *  `uninstallScraper` for why the count matters). */
    suspend fun uninstallNuvioPlugin(pluginUrl: String): Int {
        val app = getApplication<Application>()
        // The manager removes by exact source URL, so hand it the spelling each
        // installed provider actually stored (they can differ from the repo's
        // current listing) — otherwise "Uninstalled" would leave the provider
        // in place when the repo moved the file.
        val stored = uninstallTargets(store.providers(), pluginUrl) { p, s ->
            p.type == ProviderType.NUVIO && sourceMatches(p, s)
        }
            .mapNotNull { it.extra }
            .distinct()
            .ifEmpty { listOf(pluginUrl) }
        var removed = 0
        for (source in stored) {
            removed += com.hikari.app.nuvio.NuvioPluginManager.uninstallScraper(app, source)
        }
        reloadInstalled()
        return removed
    }

    /** Registers a SkyStream extension repository (`repo.json`). A bare
     *  shortcode is resolved through [SkyStreamPluginManager.resolveRepoUrl]
     *  first, mirroring the official app's "type myrepo" flow. */
    suspend fun addSkyStreamRepo(rawUrl: String): Result<Cs3Repo> {
        val trimmed = rawUrl.trim()
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            val resolved = com.hikari.app.skystream.SkyStreamPluginManager.resolveRepoUrl(trimmed)
                ?: return Result.failure(
                    Exception(
                        unknownShortName(
                            trimmed,
                            "SkyStream shortcode",
                            "Must start with http(s):// — or a SkyStream shortcode",
                            RepoKind.SKYSTREAM,
                        )
                    )
                )
            return addRepo(resolved, RepoKind.SKYSTREAM)
        }
        return addRepo(trimmed, RepoKind.SKYSTREAM)
    }

    suspend fun installSkyStreamPlugin(plugin: Cs3RepoPlugin): Result<Int> =
        withContext(Dispatchers.IO) {
            val bytes = withTimeoutOrNull(90_000) { Http.fetchBytesRobust(plugin.url) }
                ?: return@withContext Result.failure(Exception("Download timed out — check your connection"))
            com.hikari.app.skystream.SkyStreamPluginManager.install(
                getApplication<Application>(),
                bytes,
                sourceUrl = plugin.url,
                iconUrl = plugin.iconUrl,
            ).also { manager.refresh(); reloadInstalled() }
        }

    /** Removes every SKYSTREAM extension that came from [pluginUrl]. Returns how
     *  many installed extensions were actually removed. */
    suspend fun uninstallSkyStreamPlugin(pluginUrl: String): Int {
        val app = getApplication<Application>()
        val stored = uninstallTargets(store.providers(), pluginUrl) { p, s ->
            p.type == ProviderType.SKYSTREAM && sourceMatches(p, s)
        }
            .mapNotNull { it.extra }
            .distinct()
            .ifEmpty { listOf(pluginUrl) }
        var removed = 0
        for (source in stored) {
            removed += com.hikari.app.skystream.SkyStreamPluginManager.uninstall(app, source)
        }
        reloadInstalled()
        return removed
    }

    suspend fun installSkyStreamFromUrl(url: String): Result<Int> = withContext(Dispatchers.IO) {
        val clean = url.trim()
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
            return@withContext Result.failure(Exception("Must start with http(s)://"))
        }
        val bytes = Http.fetchBytesRobust(clean)
            ?: return@withContext Result.failure(Exception("Download failed — check the URL"))
        com.hikari.app.skystream.SkyStreamPluginManager.install(
            getApplication<Application>(),
            bytes,
            sourceUrl = clean,
        ).also { manager.refresh(); reloadInstalled() }
    }

    suspend fun installSkyStreamFromUri(uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        val bytes = runCatching {
            getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return@withContext Result.failure(Exception("Could not read the selected file"))
        com.hikari.app.skystream.SkyStreamPluginManager.install(
            getApplication<Application>(),
            bytes,
        ).also { manager.refresh(); reloadInstalled() }
    }

    /**
     * Registers an Aniyomi extension repository. This one CANNOT go through
     * [addRepoUrl]: an Aniyomi repo publishes a **bare JSON array** (every other
     * kind nests its entries under a `plugins` key), so `JSONObject(text)` would
     * throw "Invalid index.min.json" on a perfectly good repo. A bare folder,
     * host or shortcode is normalised to `<dir>/index.min.json` first, mirroring
     * Aniyomi's own "add repo" flow.
     */
    suspend fun addAniyomiRepo(rawUrl: String): Result<Cs3Repo> {
        val resolved = com.hikari.app.aniyomi.AniyomiExtensionManager.resolveRepoUrl(rawUrl)
            ?: return Result.failure(
                Exception(
                    unknownShortName(
                        rawUrl,
                        "Aniyomi repo name",
                        "Must be a link to an index.min.json (or the repo folder)",
                        RepoKind.ANIYOMI,
                    )
                )
            )
        return addAniyomiRepoUrl(resolved)
    }

    /** The fetch + validate + store half of [addAniyomiRepo] — shared with the
     *  shortcode aliases, whose URLs are already resolved. */
    private suspend fun addAniyomiRepoUrl(url: String): Result<Cs3Repo> =
        withContext(Dispatchers.IO) {
            runCatching {
                val text = fetchRepoRaw(url, ANIYOMI_INDEX).getOrElse { throw it }
                // Validate BEFORE storing: a wrong URL (or an HTML page) must
                // not end up as a repo whose every open fails.
                val arr = com.hikari.app.aniyomi.AniyomiExtensionManager.indexEntries(text)
                    ?: throw Exception("Invalid $ANIYOMI_INDEX: not a list of extensions")
                if (arr.length() == 0) throw Exception("That $ANIYOMI_INDEX lists no extensions")
                val repo = Cs3Repo(
                    url = lastGoodRepoUrl,
                    name = niceRepoName(url, ""),
                    description = "",
                    kind = RepoKind.ANIYOMI,
                )
                val key = SourceUrls.repoKey(repo.url)
                duplicateRepoAdd = store.repos().any { SourceUrls.repoKey(it.url) == key }
                store.addCs3Repo(repo)
                repos.value = store.repos()
                store.repos().firstOrNull { SourceUrls.repoKey(it.url) == key } ?: repo
            }
        }

    suspend fun installAniyomiPlugin(plugin: Cs3RepoPlugin): Result<Int> =
        withContext(Dispatchers.IO) {
            // Aniyomi extensions are a couple of MB at most, but the biggest
            // ones (allanime, aniwatch) sit behind slow mirrors, so the ceiling
            // is looser than the JS-plugin installs'.
            val bytes = withTimeoutOrNull(120_000) { Http.fetchBytesRobust(plugin.url) }
                ?: return@withContext Result.failure(
                    Exception("Download timed out — check your connection")
                )
            com.hikari.app.aniyomi.AniyomiExtensionManager.install(
                getApplication<Application>(),
                bytes,
                sourceUrl = plugin.url,
                iconUrl = plugin.iconUrl,
            ).also { manager.refresh(); reloadInstalled() }
        }

    /** Removes every ANIYOMI provider that came from [pluginUrl] (and the `.ext`
     *  itself once nothing references it). */
    suspend fun uninstallAniyomiPlugin(pluginUrl: String): Int {
        val app = getApplication<Application>()
        // The manager removes by exact source URL, so hand it the spelling each
        // installed provider actually stored — a repo build can move the file
        // (a new branch, the jsDelivr mirror) between listing and uninstall.
        val stored = uninstallTargets(store.providers(), pluginUrl) { p, s ->
            p.type == ProviderType.ANIYOMI && sourceMatches(p, s)
        }
            .mapNotNull { it.extra }
            .distinct()
            .ifEmpty { listOf(pluginUrl) }
        var removed = 0
        for (source in stored) {
            removed += com.hikari.app.aniyomi.AniyomiExtensionManager.uninstall(app, source)
        }
        manager.refresh()
        reloadInstalled()
        return removed
    }

    suspend fun installAniyomiFromUrl(url: String): Result<Int> = withContext(Dispatchers.IO) {
        val clean = url.trim()
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
            return@withContext Result.failure(Exception("Must start with http(s)://"))
        }
        val bytes = withTimeoutOrNull(120_000) { Http.fetchBytesRobust(clean) }
            ?: return@withContext Result.failure(Exception("Download failed — check the URL"))
        com.hikari.app.aniyomi.AniyomiExtensionManager.install(
            getApplication<Application>(),
            bytes,
            sourceUrl = clean,
        ).also { manager.refresh(); reloadInstalled() }
    }

    /** Installs a local `.apk`/`.ext` the user picked (an Aniyomi extension
     *  downloaded from a browser has no repo URL at all). */
    suspend fun installAniyomiFromUri(uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        val bytes = runCatching {
            getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return@withContext Result.failure(Exception("Could not read the selected file"))
        com.hikari.app.aniyomi.AniyomiExtensionManager.install(
            getApplication<Application>(),
            bytes,
        ).also { manager.refresh(); reloadInstalled() }
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
     *  HTML page would never parse as JSON). Remembers which variant succeeded.
     *
     *  [remember] is false for the callers that run SEVERAL fetches at once
     *  (the repo-list loader, the Mega import's name lookups): they only need
     *  the body, and writing the shared [lastGoodRepoUrl] from parallel
     *  coroutines could hand an add-repo the URL some other repo happened to
     *  resolve to. */
    private fun fetchRepoRaw(
        url: String,
        file: String = "repo.json",
        ua: String? = null,
        remember: Boolean = true,
    ): Result<String> {
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
            if (remember) lastGoodRepoUrl = candidate
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
        fun sky(url: String) = RepoAlias(url, RepoKind.SKYSTREAM)
        val skyOfficial = sky("https://raw.githubusercontent.com/akashdh11/skystream-plugins/main/repo.json")
        val skyRouge = sky("https://raw.githubusercontent.com/rougegz/SkystreamPlugins/main/repo.json")
        // Aniyomi extension repos. These publish `index.min.json` — a BARE JSON
        // ARRAY of extensions (every other kind nests entries under a `plugins`
        // key), which is why this kind has its own repo parser and its own
        // install path. Aniyomi's official repo is the only one seeded; these
        // aliases are the shortcut for re-adding it (or a mirror) by hand.
        fun aniyomiExt(url: String) = RepoAlias(url, RepoKind.ANIYOMI)
        val aniyomiOfficial = aniyomiExt(
            "https://raw.githubusercontent.com/aniyomiorg/aniyomi-extensions/repo/index.min.json"
        )
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
        put("skystream", listOf(skyOfficial))
        put("skystreamplugins", listOf(skyOfficial))
        put("akash", listOf(skyOfficial))
        put("akashdh11", listOf(skyOfficial))
        put("sky", listOf(skyOfficial))
        put("skyourge", listOf(skyRouge))
        put("rougegz", listOf(skyRouge))
        put("aniyomiext", listOf(aniyomiOfficial))
        put("aniyomiextensions", listOf(aniyomiOfficial))
        put("aniyomiindex", listOf(aniyomiOfficial))
        put("aniyomiofficial", listOf(aniyomiOfficial))
        put("aniyomirepo", listOf(aniyomiOfficial))
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

    /**
     * The short name the user probably meant, or null when nothing is close.
     *
     * A swapped pair ("hiakri" for "hikari") is ONE edit by the distance that
     * counts here, not two — that is what a typo is. Answering with the name
     * they meant is worth more than a hint that reads like a rejection of a
     * word they got nearly right.
     *
     * [kind] narrows the search where only one kind of repo can be added at
     * that dialog (a SkyStream shortcode field must not suggest a Nuvio repo).
     */
    private fun suggestRepoAlias(raw: String, kind: RepoKind? = null): String? {
        val typed = raw.trim().lowercase()
            .removePrefix("@")
            .removeSuffix(".json")
            .trimEnd('/')
        // Only a word can be a mistyped short name; a pasted URL never is.
        if (typed.length < 4 || typed.contains('/') || typed.contains(' ') || typed in REPO_ALIASES) {
            return null
        }
        var best: String? = null
        var bestScore = Int.MAX_VALUE
        for ((key, aliases) in REPO_ALIASES) {
            if (kind != null && aliases.none { it.kind == kind }) continue
            val score = editDistance(typed, key)
            if (score < bestScore) {
                bestScore = score
                best = key
            }
        }
        return if (best != null && bestScore <= 2) best else null
    }

    /** Optimal-string-alignment distance: Levenshtein, plus a swapped pair of
     *  neighbouring letters counting as ONE edit (Damerau). Small and bounded —
     *  it only ever runs over the short-name table, on a rejected input. */
    private fun editDistance(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
                }
            }
        }
        return d[a.length][b.length]
    }

    /** What to say when a short name matches nothing: the name we think was
     *  meant when it is a typo away, else [fallback]. */
    private fun unknownShortName(
        typed: String,
        what: String,
        fallback: String,
        kind: RepoKind? = null,
    ): String {
        val guess = suggestRepoAlias(typed, kind) ?: return fallback
        return "Unknown $what \"${typed.trim()}\" — did you mean \"$guess\"?"
    }

    private suspend fun addRepo(rawUrl: String, kind: RepoKind): Result<Cs3Repo> {
        val trimmed = rawUrl.trim()
        resolveRepoAlias(trimmed)?.let { aliases ->
            var first: Cs3Repo? = null
            var lastError: Throwable? = null
            for (alias in aliases) {
                val result = if (alias.kind == RepoKind.ANIYOMI) addAniyomiRepoUrl(alias.url)
                else addRepoUrl(alias.url, alias.kind)
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
                Exception(
                    unknownShortName(
                        trimmed,
                        "short name",
                        "Must start with http(s):// — or a short name (megarepo, hikari, nuvio, …)",
                    )
                )
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
                    description = PromoGuard.cleanText(obj.optString("description")),
                    kind = kind,
                )
                // Adding a repo that is already in the list (same repo — the
                // URL spelling may differ: refs/heads vs plain branch, the
                // jsDelivr mirror) must not grow a second copy: that copy's
                // extensions all looked uninstalled again while the originals
                // kept working on Home. The store merges it into the one entry
                // it belongs to; report which of the two happened so the dialog
                // can say "already added" instead of "added".
                // Repo identity, not file identity: the SAME repository on
                // another branch is still the same repository (see
                // [SourceUrls.repoKey]) — that is the reported "I added the same
                // repo link and it made a second folder".
                val key = SourceUrls.repoKey(repo.url)
                val previous = store.repos().firstOrNull { SourceUrls.repoKey(it.url) == key }
                duplicateRepoAdd = previous != null
                store.addCs3Repo(repo)
                // Adding a repo that is already there must leave its extensions
                // showing the state they have. The stored spelling can be
                // UPGRADED by the merge (a jsDelivr mirror → the origin URL), and
                // the fetched plugin list is keyed by the repo's URL: carry it (and
                // the row's load state) over to the new key, or the repo the user
                // just re-added reads as a brand-new one with every extension
                // uninstalled-looking until it re-fetches.
                val savedUrl = store.repos().firstOrNull { SourceUrls.repoKey(it.url) == key }?.url
                if (previous != null && savedUrl != null && previous.url != savedUrl) {
                    pluginsByRepo.value[previous.url]?.let { list ->
                        pluginsByRepo.value = pluginsByRepo.value - previous.url + (savedUrl to list)
                    }
                    repoState.value[previous.url]?.let { st ->
                        repoState.value = repoState.value - previous.url + (savedUrl to st)
                    }
                }
                // A "Mega"-style bundle repo isn't a plugin repo — its single
                // plugin only exists to add every CloudStream repo from the
                // canonical repos-db.json (and relies on the real CloudStream
                // RepositoryManager, which Hikari doesn't run). Import the
                // repos natively instead so they all show up and install.
                if (isMegaBundle(obj)) {
                    markBundle(repo.url)
                    importMegaRepos()
                }
                val saved = store.repos().firstOrNull { SourceUrls.repoKey(it.url) == key }
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
            // A hard ceiling on ONE repo. The fetch itself already bounds every
            // individual request, but a repo whose manifest lists twenty dead
            // sub-lists can still spend minutes inside it — and a loader that
            // never reports back leaves its row (and the extensions search)
            // saying "loading" forever, which is the "stuck searching
            // extensions" the user kept seeing. Timing out turns it into the
            // row's normal error + Retry instead.
            val (plugins, meta) = withTimeoutOrNull(REPO_LOAD_CEILING_MS) {
                withContext(Dispatchers.IO) { fetchRepoPlugins(repo) }
            } ?: throw Exception("This repo took too long to load — tap refresh to try again")
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
        val file = when (repo.kind) {
            RepoKind.NUVIO -> "manifest.json"
            RepoKind.ANIYOMI -> ANIYOMI_INDEX
            else -> "repo.json"
        }
        val text = fetchRepoRaw(
            repo.url, file,
            ua = if (repo.kind == RepoKind.NUVIO) Http.NUVIO_UA else null,
            // Several of these run at once (see loadReposIfNeeded) — this fetch
            // is only after the body.
            remember = false,
        )
            .getOrElse { throw Exception("Could not fetch repo: ${it.message}") }
        if (repo.kind == RepoKind.ANIYOMI) {
            // An Aniyomi index is a BARE ARRAY (not an object with a `plugins`
            // key), so it can't go through JSONObject below. Each entry's `apk`
            // is a filename served from `<repo root>/apk/`, and the icon from
            // `<repo root>/icon/<pkg>.png` — see AniyomiExtensionManager.repoPlugin.
            val arr = com.hikari.app.aniyomi.AniyomiExtensionManager.indexEntries(text)
                ?: throw Exception("Invalid $file: not a list of extensions")
            val out = LinkedHashMap<String, Cs3RepoPlugin>()
            val trimmed = repo.url.trimEnd('/')
            val baseUrl = if (trimmed.endsWith("/$ANIYOMI_INDEX", ignoreCase = true))
                trimmed.removeSuffix("/$ANIYOMI_INDEX") else trimmed
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { o ->
                    com.hikari.app.aniyomi.AniyomiExtensionManager.repoPlugin(o, baseUrl)
                        ?.let { p -> out[p.url] = p }
                }
            }
            if (out.isEmpty()) throw Exception("No extensions found in $file")
            return out.values.toList() to null
        }
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
            val description = PromoGuard.cleanText(root.optString("description"))
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
        if (repo.kind == RepoKind.SKYSTREAM) {
            // A SkyStream repo lists its extensions either inline (`plugins`),
            // through one or more `pluginLists` files (the official repo's
            // dist/plugins.json), or as nested `repos` (repo.json URLs). Each
            // entry names a `.sky` to download; entries only carry a
            // `packageName` when they have no `name`, which is why they are
            // mapped through the SkyStream manager rather than parsePlugin.
            val sky = LinkedHashMap<String, Cs3RepoPlugin>()
            fun addSky(arr: JSONArray?) {
                if (arr == null) return
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { o ->
                        com.hikari.app.skystream.SkyStreamPluginManager.repoPlugin(o, "")
                            ?.let { p -> sky[p.url] = p }
                    }
                }
            }
            addSky(root.optJSONArray("plugins"))
            root.optJSONArray("pluginLists")?.let { lists ->
                for (i in 0 until lists.length()) {
                    val listUrl = lists.optString(i).ifBlank { null } ?: continue
                    val listText = Http.fetchStringRobust(listUrl).getOrNull() ?: continue
                    addSky(runCatching { JSONArray(listText) }.getOrNull())
                }
            }
            root.optJSONArray("repos")?.let { nested ->
                for (i in 0 until nested.length()) {
                    val nestedUrl = nested.optString(i).ifBlank { null } ?: continue
                    val nestedText = Http.fetchStringRobust(nestedUrl).getOrNull() ?: continue
                    val nestedRoot = runCatching { JSONObject(nestedText) }.getOrNull() ?: continue
                    addSky(nestedRoot.optJSONArray("plugins"))
                    nestedRoot.optJSONArray("pluginLists")?.let { lists ->
                        for (j in 0 until lists.length()) {
                            val listUrl = lists.optString(j).ifBlank { null } ?: continue
                            val listText = Http.fetchStringRobust(listUrl).getOrNull() ?: continue
                            addSky(runCatching { JSONArray(listText) }.getOrNull())
                        }
                    }
                }
            }
            val name = niceRepoName(repo.url, root.optString("name"))
            val description = PromoGuard.cleanText(root.optString("description"))
            val meta = if (name != repo.name || description != repo.description)
                repo.copy(name = name, description = description)
            else null
            return sky.values.toList() to meta
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
        val description = PromoGuard.cleanText(root.optString("description"))
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
            fetchRepoRaw(url, remember = false).getOrNull()?.let { text ->
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
            description = PromoGuard.cleanText(o.optString("description")),
            url = url,
            // Repos spell this field three ways depending on who wrote them
            // (CloudStream → iconUrl, SkyStream/Nuvio → logo, others → icon).
            // Reading only `iconUrl` left most listings icon-less.
            iconUrl = o.optString("iconUrl")
                .ifBlank { o.optString("icon") }
                .ifBlank { o.optString("logo") }
                .ifBlank { null },
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

    /** A short, stable, filename-safe digest of [s] — the stamp that gives an
     *  installed file (and the provider ids derived from its name) a per-SOURCE
     *  identity. */
    private fun shortHash(s: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-1")
        return md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(8)
    }

    /** True when a provider's stored [extra] and [source] name the same source
     *  file, however either is spelled (see [SourceUrls.matchKeys]). */
    private fun sourceKeyMatches(extra: String?, source: String?): Boolean {
        if (extra == null || source == null) return false
        if (!extra.startsWith("http") || !source.startsWith("http")) return false
        val wanted = SourceUrls.matchKeys(source)
        return SourceUrls.matchKeys(extra).any { it in wanted }
    }

    /**
     * True when [p]'s stored source URL is the same file as [source] — however
     * either side spells it (refs/heads vs a plain branch, the jsDelivr mirror,
     * a github.com blob link, %20 vs a space). Install, uninstall and the
     * update check all key on the source URL, so they all have to agree on what
     * "the same file" means.
     */
    private fun sourceMatches(p: ProviderConfig, source: String): Boolean {
        val extra = p.extra ?: return false
        val raw = if (p.type == ProviderType.HIKARI) extra.substringBeforeLast('|') else extra
        return sourceKeyMatches(raw, source)
    }

    /**
     * The providers an uninstall of [pluginUrl] removes: every installed copy
     * that came from THAT file, whatever either side's spelling is.
     *
     * [SourceUrls.matchKeys] already covers the loose cases deliberately — the
     * jsDelivr mirror, `refs/heads/x` vs `x`, a `github.com` blob link, and the
     * same file read from another BRANCH of the same repo (its file key ignores
     * the branch). All of those are the same extension, so all of them go: an
     * install made from the other branch used to survive the uninstall, and
     * because both copies share that file key, every row for the extension kept
     * reading "installed" — the reported "I tap Uninstall and it still shows
     * Uninstall". (The old exact-match-first rule existed to stop ONE uninstall
     * from taking a whole repo with it; that was a different bug — an identity
     * that folded `github.com/o/r/blob/…` onto the repo root — and it is fixed
     * in [SourceUrls], not here.)
     */
    private fun uninstallTargets(
        all: List<ProviderConfig>,
        pluginUrl: String,
        matches: (ProviderConfig, String) -> Boolean,
    ): List<ProviderConfig> = all.filter { matches(it, pluginUrl) }

    suspend fun uninstallCs3Plugin(pluginUrl: String): Int {
        val targets = uninstallTargets(store.providers(), pluginUrl) { p, s ->
            p.type == ProviderType.CS3 && sourceMatches(p, s)
        }
        if (targets.isEmpty()) return 0
        val ids = targets.map { it.id }.toSet()
        val paths = targets.map { it.url }.toSet()
        // Locked read-modify-write: see [AppStore.updateProviders]. Removes the
        // ids resolved above and nothing else — re-running the predicate against
        // the list at write time could match more than the file the user
        // uninstalled.
        store.updateProviders { list -> list.filterNot { it.id in ids } }
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
        return targets.size
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
     *  succeeded and names any that failed.
     *
     *  A hundred-plus extensions take a while, so the run can be stopped
     *  part-way: [stopBulkInstall] ends it after the extension it is on, and
     *  the report says where it got to. */
    fun installAllPlugins(plugins: List<Cs3RepoPlugin>, kind: RepoKind, installedUrls: Set<String>) {
        installJob?.cancel()
        val unit = when (kind) {
            RepoKind.HIKARI -> "extension"
            RepoKind.NUVIO -> "provider"
            RepoKind.SKYSTREAM -> "extension"
            RepoKind.ANIYOMI -> "extension"
            RepoKind.CS3 -> "plugin"
        }
        val pending = plugins.filterNot { SourceUrls.anyKeyIn(it.url, installedUrls) }
        if (pending.isEmpty()) {
            val n = plugins.size
            setSuccess("All $n $unit${if (n == 1) "" else "s"} already installed")
            return
        }
        val gen = ++installGeneration
        installJob = startBackground {
            installCancelled = false
            _installRunning.value = true
            try {
                clearStatus()
                var ok = 0
                val failed = mutableListOf<String>()
                for ((i, p) in pending.withIndex()) {
                    if (installCancelled) break
                    _busyMsg.value = I18n.t("Installing %s (%s/%s)…")
                        .replaceFirst("%s", p.name)
                        .replaceFirst("%s", (i + 1).toString())
                        .replaceFirst("%s", pending.size.toString())
                    val r = runCatching {
                        withTimeoutOrNull(90_000) {
                            when (effectiveRepoKind(kind, p.url)) {
                                RepoKind.CS3 -> installCs3Plugin(p)
                                RepoKind.HIKARI -> installHikiPlugin(p)
                                RepoKind.NUVIO -> installNuvioPlugin(p)
                                RepoKind.SKYSTREAM -> installSkyStreamPlugin(p)
                                RepoKind.ANIYOMI -> installAniyomiPlugin(p)
                            }
                        }
                    }.getOrNull()
                    if (r != null && r.isSuccess) ok++ else failed.add(p.name)
                }
                val n = pending.size
                val plurals = if (n == 1) "" else "s"
                when {
                    installCancelled ->
                        setSuccess("Stopped — installed $ok of $n $unit$plurals")
                    failed.isEmpty() -> setSuccess("Installed $ok of $n $unit$plurals")
                    else -> setSuccess(
                        "Installed $ok of $n — failed: " +
                            failed.take(3).joinToString(", ") +
                            (if (failed.size > 3) "…" else "")
                    )
                }
            } finally {
                if (installGeneration == gen) {
                    _installRunning.value = false
                    _installStopping.value = false
                }
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
                    // The installed file can be indexed under another spelling
                    // of this URL (the repo rewrote it, or it was installed
                    // from the other branch): ask for every key the plugin's
                    // URL answers to, not just the literal string.
                    val keys = SourceUrls.matchKeys(plugin.url)
                    val path = onDisk[plugin.url]
                        ?: keys.firstOrNull { onDisk.containsKey(it) }?.let { onDisk[it] }
                        ?: continue
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
     *
     * Like [installAllPlugins], a run over many extensions can be stopped
     * part-way with [stopBulkInstall].
     */
    fun updatePlugins(items: List<Pair<Cs3RepoPlugin, RepoKind>>) {
        if (items.isEmpty()) return
        installJob?.cancel()
        val gen = ++installGeneration
        installJob = startBackground {
            installCancelled = false
            _installRunning.value = true
            try {
                clearStatus()
                var ok = 0
                val failed = mutableListOf<String>()
                for ((i, item) in items.withIndex()) {
                    if (installCancelled) break
                    val (p, kind) = item
                    _busyMsg.value = I18n.t("Updating %s (%s/%s)…")
                        .replaceFirst("%s", p.name)
                        .replaceFirst("%s", (i + 1).toString())
                        .replaceFirst("%s", items.size.toString())
                    val r = runCatching {
                        withTimeoutOrNull(90_000) {
                            when (effectiveRepoKind(kind, p.url)) {
                                RepoKind.CS3 -> installCs3Plugin(p)
                                RepoKind.HIKARI -> installHikiPlugin(p)
                                RepoKind.NUVIO -> installNuvioPlugin(p)
                                RepoKind.SKYSTREAM -> installSkyStreamPlugin(p)
                                RepoKind.ANIYOMI -> installAniyomiPlugin(p)
                            }
                        }
                    }.getOrNull()
                    if (r != null && r.isSuccess) ok++ else failed.add(p.name)
                }
                // Re-hash: anything that came back clean loses its Update button.
                checkUpdates()
                setSuccess(
                    when {
                        installCancelled ->
                            "Stopped — updated $ok of ${items.size}"
                        failed.isEmpty() -> "Updated $ok extension${if (ok == 1) "" else "s"}"
                        else -> "Updated $ok of ${items.size} — failed: " +
                            failed.take(3).joinToString(", ") +
                            (if (failed.size > 3) "…" else "")
                    }
                )
            } finally {
                if (installGeneration == gen) {
                    _installRunning.value = false
                    _installStopping.value = false
                }
            }
        }
    }

    /** Removes every HIKARI provider that came from [pluginUrl]. Returns how
     *  many installed extensions were actually removed. */
    suspend fun uninstallHikiPlugin(pluginUrl: String): Int {
        fun fromPlugin(p: ProviderConfig) =
            p.type == ProviderType.HIKARI && sourceMatches(p, pluginUrl)
        val targets = uninstallTargets(store.providers(), pluginUrl) { p, s -> fromPlugin(p) }
        if (targets.isEmpty()) return 0
        val ids = targets.map { it.id }.toSet()
        val paths = targets.map { it.url }.toSet()
        // Locked read-modify-write: see [AppStore.updateProviders].
        store.updateProviders { list -> list.filterNot { it.id in ids } }
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
        return targets.size
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
    url.endsWith(".sky", ignoreCase = true) -> RepoKind.SKYSTREAM
    // Aniyomi repos serve plain `.apk` files (saved locally with an `.ext`
    // suffix), so a repo whose own kind was guessed wrong still installs
    // through the Aniyomi path instead of the CloudStream one.
    url.endsWith(".apk", ignoreCase = true) -> RepoKind.ANIYOMI
    url.endsWith(".ext", ignoreCase = true) -> RepoKind.ANIYOMI
    else -> default
}

/** The file an Aniyomi extension repo publishes (a bare JSON array). */
private const val ANIYOMI_INDEX = "index.min.json"

/**
 * How long ONE repo's manifest (and any sub-list it points at) may take before
 * the row is marked failed instead of loading forever. Generous — a repo with a
 * dozen pluginLists on slow mirrors needs a while — but bounded, because a repo
 * that never answers used to hold its own row on "loading" and keep the
 * Extensions search saying "Searching…" for as long as the screen was open.
 */
private const val REPO_LOAD_CEILING_MS = 75_000L

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
    var showIptv by remember { mutableStateOf(false) }
    var iptvUrl by remember { mutableStateOf("") }
    var iptvName by remember { mutableStateOf("") }
    var iptvFileLabel by remember { mutableStateOf("") }
    var iptvLocalPath by remember { mutableStateOf("") }
    var iptvInfoId by remember { mutableStateOf<String?>(null) }
    var showScraper by remember { mutableStateOf(false) }
    var showCs3Url by remember { mutableStateOf(false) }
    var showRepoDialog by remember { mutableStateOf(false) }
    var repoDialogKind by remember { mutableStateOf(RepoKind.CS3) }
    var showHikiUrl by remember { mutableStateOf(false) }
    var showSkyUrl by remember { mutableStateOf(false) }
    var showAniyomiUrl by remember { mutableStateOf(false) }
    var stremioUrl by remember { mutableStateOf("") }
    var scraperJson by remember { mutableStateOf("") }
    var cs3Url by remember { mutableStateOf("") }
    var hikiUrl by remember { mutableStateOf("") }
    var skyUrl by remember { mutableStateOf("") }
    var aniyomiUrl by remember { mutableStateOf("") }
    var repoUrl by remember { mutableStateOf("") }
    val busy by vm.busy.collectAsState()
    val busyMsg by vm.busyMsg.collectAsState()
    // A bulk install/update can be stopped while it runs (see
    // [ExtensionsViewModel.stopBulkInstall]) — the screens that can start one
    // show the Stop button in their progress line.
    val installRunning by vm.installRunning.collectAsState()
    val installStopping by vm.installStopping.collectAsState()
    val errorMsg by vm.errorMsg.collectAsState()
    val successMsg by vm.successMsg.collectAsState()

    val repos by vm.repos.collectAsState()
    val pluginsByRepo by vm.pluginsByRepo.collectAsState()
    val installed by vm.installedUrls.collectAsState()
    val outdated by vm.outdatedUrls.collectAsState()
    // Bumped when a playlist has been read (or re-read): the IPTV rows show their
    // channel count from it, and collecting it here is what makes them repaint.
    val iptvTick by vm.iptvTick.collectAsState()
    // Every outdated plugin together with the kind of repo it came from, so
    // "Update all" can re-install each one the same way its row would.
    val outdatedItems = remember(outdated, pluginsByRepo, repos) {
        repos.flatMap { repo ->
            (pluginsByRepo[repo.url] ?: emptyList()).map { repo.kind to it }
        }.filter { (_, p) -> SourceUrls.anyKeyIn(p.url, outdated) }
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
    var addonInfoProvider by remember { mutableStateOf<ContentProvider?>(null) }

    fun openProviderSettings(p: ContentProvider) {
        when (p.config.type) {
            // An addon is a remote manifest, not a file: the nuvio settings
            // dialog (which reads the provider FILE) answered "Provider file
            // missing" over a healthy addon. Show what the addon is instead.
            ProviderType.STREMIO -> addonInfoProvider = p
            ProviderType.NUVIO -> settingsProvider = p
            // A playlist has no settings either — what it has is a source and a
            // channel count, plus a way to re-read it.
            ProviderType.IPTV -> iptvInfoId = p.config.id
            // CS3 plugins have their own settings screen; nothing else has one.
            else -> openProviderSettingsSafely(p, context, scope) {}
        }
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

    val skyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            vm.runInstall("Installing .sky extension…") { vm.installSkyStreamFromUri(uri) }
        }
    }

    val aniyomiPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                vm.runInstall("Installing Aniyomi extension…") { vm.installAniyomiFromUri(uri) }
            }
        }

    // A playlist is a text file, but plenty of providers serve it as
    // `application/octet-stream` (or a MIME type Android has never heard of), so
    // the picker accepts anything and the parser decides what it is.
    val iptvPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                vm.pickIptvFile(uri) { label, path ->
                    iptvFileLabel = label
                    iptvLocalPath = path
                }
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
                RepoKind.SKYSTREAM -> vm.installSkyStreamPlugin(p)
                RepoKind.ANIYOMI -> vm.installAniyomiPlugin(p)
            }
        }
    }

    fun uninstallPlugin(p: Cs3RepoPlugin, kind: RepoKind) {
        vm.runUninstallCounted(
            "Uninstalling ${p.name}…",
            { n -> "Uninstalled ${p.name}" + if (n > 1) " ($n providers)" else "" },
        ) {
            when (effectiveRepoKind(kind, p.url)) {
                RepoKind.CS3 -> vm.uninstallCs3Plugin(p.url)
                RepoKind.HIKARI -> vm.uninstallHikiPlugin(p.url)
                RepoKind.NUVIO -> vm.uninstallNuvioPlugin(p.url)
                RepoKind.SKYSTREAM -> vm.uninstallSkyStreamPlugin(p.url)
                RepoKind.ANIYOMI -> vm.uninstallAniyomiPlugin(p.url)
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
            installRunning = installRunning,
            installStopping = installStopping,
            onStopInstall = { vm.stopBulkInstall() },
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
            iptvTick = iptvTick,
            busy = busy,
            busyMsg = busyMsg,
            successMsg = successMsg,
            errorMsg = errorMsg,
            installRunning = installRunning,
            installStopping = installStopping,
            onStopInstall = { vm.stopBulkInstall() },
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
                    SourceFolder.SKYSTREAM -> RepoKind.SKYSTREAM
                    SourceFolder.ANIYOMI -> RepoKind.ANIYOMI
                    else -> RepoKind.CS3
                }
                showRepoDialog = true
            },
            onAddStremio = { vm.clearStatus(); showStremio = true },
            onAddIptv = { vm.clearStatus(); showIptv = true },
            onWarmIptv = { vm.warmIptv() },
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
            installRunning = installRunning,
            installStopping = installStopping,
            onStopInstall = { vm.stopBulkInstall() },
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
            installRunning = installRunning,
            installStopping = installStopping,
            onStopInstall = { vm.stopBulkInstall() },
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
            installRunning = installRunning,
            installStopping = installStopping,
            onStopInstall = { vm.stopBulkInstall() },
            onBack = { sourcesOpen = false; vm.clearStatus() },
            onOpenRepo = { repo ->
                openRepoUrl = repo.url
                vm.clearStatus()
                if (pluginsByRepo[repo.url] == null) vm.refreshRepo(repo)
            },
            onAddRepo = { vm.clearStatus(); repoDialogKind = RepoKind.CS3; showRepoDialog = true },
            onAddHikiRepo = { vm.clearStatus(); repoDialogKind = RepoKind.HIKARI; showRepoDialog = true },
            onAddNuvioRepo = { vm.clearStatus(); repoDialogKind = RepoKind.NUVIO; showRepoDialog = true },
            onAddSkyStreamRepo = { vm.clearStatus(); repoDialogKind = RepoKind.SKYSTREAM; showRepoDialog = true },
            onAddAniyomiRepo = { vm.clearStatus(); repoDialogKind = RepoKind.ANIYOMI; showRepoDialog = true },
            onAddStremio = { vm.clearStatus(); showStremio = true },
            onAddIptv = { vm.clearStatus(); showIptv = true },
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
            installRunning = installRunning,
            installStopping = installStopping,
            onStopInstall = { vm.stopBulkInstall() },
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
            onAddSkyStreamUrl = { vm.clearStatus(); showSkyUrl = true },
            onPickSkyStreamFile = {
                vm.clearStatus()
                skyPicker.launch(arrayOf("application/octet-stream", "*/*"))
            },
            onAddAniyomiUrl = { vm.clearStatus(); showAniyomiUrl = true },
            onPickAniyomiFile = {
                vm.clearStatus()
                aniyomiPicker.launch(arrayOf("application/vnd.android.package-archive", "*/*"))
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
        val isSky = repoDialogKind == RepoKind.SKYSTREAM
        val isAniyomi = repoDialogKind == RepoKind.ANIYOMI
        AlertDialog(
            onDismissRequest = { showRepoDialog = false },
            title = {
                Text(
                    when (repoDialogKind) {
                        RepoKind.HIKARI -> "Add Hikari repo"
                        RepoKind.NUVIO -> "Add Nuvio repo"
                        RepoKind.SKYSTREAM -> "Add SkyStream repo"
                        RepoKind.ANIYOMI -> "Add Aniyomi repo"
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
                            isSky ->
                                "Paste a SkyStream repo URL (a repo.json), or just its short " +
                                    "code. For example:\n" +
                                    "https://raw.githubusercontent.com/akashdh11/skystream-plugins/main/repo.json"
                            isAniyomi ->
                                "Paste an Aniyomi repo URL (an index.min.json). For example:\n" +
                                    "https://raw.githubusercontent.com/aniyomiorg/aniyomi-extensions/repo/index.min.json"
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
                                "mooncrown, kennethjys, eclipsia. SkyStream repos: skystream, " +
                                "akash, skyourge, rougegz. Aniyomi repos: aniyomiext " +
                                "(the official Aniyomi extensions repo)."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = repoUrl,
                        onValueChange = { repoUrl = it },
                        placeholder = {
                            Text(
                                tr(if (isAniyomi) "https://…/index.min.json" else "https://…/repo.json")
                            )
                        },
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
                    enabled = repoUrl.isNotBlank(),
                    onClick = {
                        // Close at once: the task runs on the ViewModel scope
                        // and reports on the screen, so nothing here waits for
                        // a download (the old "disabled while busy" made Add,
                        // Cancel and back all dead during a mass install).
                        showRepoDialog = false
                        vm.runTask(
                            "Fetching repo…",
                            {
                                when (repoDialogKind) {
                                    RepoKind.HIKARI -> vm.addHikiRepo(repoUrl)
                                    RepoKind.NUVIO -> vm.addNuvioRepo(repoUrl)
                                    RepoKind.SKYSTREAM -> vm.addSkyStreamRepo(repoUrl)
                                    RepoKind.ANIYOMI -> vm.addAniyomiRepo(repoUrl)
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
                TextButton(onClick = { showRepoDialog = false }) { Text(tr("Cancel")) }
            }
        )
    }

    if (showStremio) {
        AlertDialog(
            onDismissRequest = { showStremio = false },
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
                    enabled = stremioUrl.isNotBlank(),
                    onClick = {
                        // Close at once: the task runs on the ViewModel scope
                        // and reports on the screen, so nothing here waits for
                        // a download (the old "disabled while busy" made Add,
                        // Cancel and back all dead during a mass install).
                        showStremio = false
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
                TextButton(onClick = { showStremio = false }) { Text(tr("Cancel")) }
            }
        )
    }

    if (showIptv) {
        AlertDialog(
            onDismissRequest = { showIptv = false },
            title = { Text(tr("Add IPTV playlist")) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        tr(
                            "Paste an M3U/M3U8 link — an Xtream panel's " +
                                "get.php?username=…&password=…&type=m3u_plus link works, and so " +
                                "does a single m3u8 stream. Or pick a playlist file from storage."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = iptvUrl,
                        onValueChange = { iptvUrl = it },
                        placeholder = { Text(tr("https://…/playlist.m3u")) },
                        singleLine = false,
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { iptvPicker.launch(arrayOf("*/*")) }) {
                            Icon(
                                Icons.Filled.FolderOpen,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(tr("Pick a file"))
                        }
                        if (iptvFileLabel.isNotBlank()) {
                            Spacer(Modifier.width(10.dp))
                            Text(
                                iptvFileLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = iptvName,
                        onValueChange = { iptvName = it },
                        placeholder = { Text(tr("Name (optional)")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
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
                    enabled = iptvUrl.isNotBlank() || iptvLocalPath.isNotBlank(),
                    onClick = {
                        // Same rule as every other Add dialog: the work runs on
                        // the ViewModel scope (so Cancel/back stay live) and the
                        // result is reported on the screen behind the dialog.
                        showIptv = false
                        val link = iptvUrl
                        val local = iptvLocalPath.takeIf { it.isNotBlank() }
                        val name = iptvName
                        iptvUrl = ""
                        iptvName = ""
                        iptvFileLabel = ""
                        iptvLocalPath = ""
                        vm.runTask(
                            "Reading playlist…",
                            { vm.addIptvPlaylist(link, local, name) },
                            successMsg = null,
                            onSuccess = { n ->
                                vm.setSuccess(
                                    "Added IPTV playlist ($n channel${if (n == 1) "" else "s"})"
                                )
                            },
                        )
                    }
                ) { Text(tr("Add")) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showIptv = false
                    iptvUrl = ""
                    iptvName = ""
                    iptvFileLabel = ""
                    iptvLocalPath = ""
                }) { Text(tr("Cancel")) }
            }
        )
    }

    iptvInfoId?.let { id ->
        val provider = providers.firstOrNull { it.config.id == id }
        if (provider != null) {
            IptvInfoDialog(
                provider = provider,
                onRefresh = { vm.refreshIptv(id) },
                onDismiss = { iptvInfoId = null },
            )
        }
    }

    if (showScraper) {
        AlertDialog(
            onDismissRequest = { showScraper = false },
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
                    enabled = scraperJson.isNotBlank(),
                    onClick = {
                        // Close at once: the task runs on the ViewModel scope
                        // and reports on the screen, so nothing here waits for
                        // a download (the old "disabled while busy" made Add,
                        // Cancel and back all dead during a mass install).
                        showScraper = false
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
                TextButton(onClick = { showScraper = false }) { Text(tr("Cancel")) }
            }
        )
    }

    if (showCs3Url) {
        AlertDialog(
            onDismissRequest = { showCs3Url = false },
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
                    enabled = cs3Url.isNotBlank(),
                    onClick = {
                        // Close at once: the task runs on the ViewModel scope
                        // and reports on the screen, so nothing here waits for
                        // a download (the old "disabled while busy" made Add,
                        // Cancel and back all dead during a mass install).
                        showCs3Url = false
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
                TextButton(onClick = { showCs3Url = false }) { Text(tr("Cancel")) }
            }
        )
    }

    if (showHikiUrl) {
        AlertDialog(
            onDismissRequest = { showHikiUrl = false },
            title = { Text(tr("Install .hiki extension")) },
            text = {
                Column {
                    Text(
                        tr(
                            "Paste a direct link to a compiled Hikari extension (.hiki). " +
                                "Extensions run against Hikari's own SDK — no CloudStream " +
                                "dependencies, no separate solver add-ons, with WebView " +
                                "stream capture built in. See docs/HIKARI_EXTENSIONS.md."
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
                    enabled = hikiUrl.isNotBlank(),
                    onClick = {
                        // Close at once: the task runs on the ViewModel scope
                        // and reports on the screen, so nothing here waits for
                        // a download (the old "disabled while busy" made Add,
                        // Cancel and back all dead during a mass install).
                        showHikiUrl = false
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
                TextButton(onClick = { showHikiUrl = false }) { Text(tr("Cancel")) }
            }
        )
    }

    if (showSkyUrl) {
        AlertDialog(
            onDismissRequest = { showSkyUrl = false },
            title = { Text(tr("Install .sky extension")) },
            text = {
                Column {
                    Text(
                        tr(
                            "Paste a direct link to a SkyStream extension (.sky) — the " +
                                "zip that contains plugin.js + plugin.json. Look for a " +
                                "\"SkyStream repo\" in the list above instead if you want " +
                                "to browse a whole repository."
                        )
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = skyUrl,
                        onValueChange = { skyUrl = it },
                        placeholder = { Text(tr("https://…/dev.akash.stars.yts.sky")) },
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
                    enabled = skyUrl.isNotBlank(),
                    onClick = {
                        // Close at once: the task runs on the ViewModel scope
                        // and reports on the screen, so nothing here waits for
                        // a download (the old "disabled while busy" made Add,
                        // Cancel and back all dead during a mass install).
                        showSkyUrl = false
                        vm.runInstall(
                            "Downloading and installing…",
                            onSuccess = {
                                showSkyUrl = false
                                skyUrl = ""
                            },
                        ) { vm.installSkyStreamFromUrl(skyUrl) }
                    }
                ) { Text(tr("Install")) }
            },
            dismissButton = {
                TextButton(onClick = { showSkyUrl = false }) { Text(tr("Cancel")) }
            }
        )
    }

    if (showAniyomiUrl) {
        AlertDialog(
            onDismissRequest = { showAniyomiUrl = false },
            title = { Text(tr("Install Aniyomi extension (.apk)")) },
            text = {
                Column {
                    Text(
                        tr(
                            "Paste a direct link to an Aniyomi extension (.apk) — the " +
                                "same file the Aniyomi app installs. Look for an " +
                                "\"Aniyomi repo\" in the list above instead if you want " +
                                "to browse a whole repository."
                        )
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = aniyomiUrl,
                        onValueChange = { aniyomiUrl = it },
                        placeholder = {
                            Text(
                                tr(
                                    "https://…/repo/apk/aniyomi-all.jellyfin-v14.17.apk"
                                )
                            )
                        },
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
                    enabled = aniyomiUrl.isNotBlank(),
                    onClick = {
                        showAniyomiUrl = false
                        vm.runInstall(
                            "Downloading and installing…",
                            onSuccess = {
                                showAniyomiUrl = false
                                aniyomiUrl = ""
                            },
                        ) { vm.installAniyomiFromUrl(aniyomiUrl) }
                    }
                ) { Text(tr("Install")) }
            },
            dismissButton = {
                TextButton(onClick = { showAniyomiUrl = false }) { Text(tr("Cancel")) }
            }
        )
    }

    if (showSite) {
        AlertDialog(
            onDismissRequest = { showSite = false },
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
                    enabled = siteUrl.isNotBlank() || siteName.isNotBlank(),
                    onClick = {
                        showSite = false
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
                TextButton(onClick = { showSite = false }) { Text(tr("Cancel")) }
            }
        )
    }

    settingsProvider?.takeIf { it.config.type == ProviderType.NUVIO }?.let { provider ->
        NuvioSettingsDialog(
            provider = provider,
            onDismiss = { settingsProvider = null },
        )
    }

    addonInfoProvider?.let { provider ->
        StremioAddonInfoDialog(
            provider = provider,
            onDismiss = { addonInfoProvider = null },
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
    /** True while a bulk install/update runs, and true again while it is
     *  finishing the extension it is on (see [ExtensionsViewModel.stopBulkInstall]). */
    installRunning: Boolean = false,
    installStopping: Boolean = false,
    onStopInstall: () -> Unit = {},
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
    onAddSkyStreamUrl: () -> Unit,
    onPickSkyStreamFile: () -> Unit,
    onAddAniyomiUrl: () -> Unit,
    onPickAniyomiFile: () -> Unit,
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
        contentPadding = PaddingValues(
            // Clear of the floating taskbar (0 when there is no bar).
            bottom = LocalTaskbarInset.current + 24.dp,
        )
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
                                I18n.t(
                                        if (outdatedUrls.size == 1) "%s installed extension can be updated"
                                        else "%s installed extensions can be updated"
                                    ).replace("%s", outdatedUrls.size.toString()),
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
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        busyMsg,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    if (installRunning) {
                        Spacer(Modifier.width(12.dp))
                        OutlinedButton(onClick = onStopInstall, enabled = !installStopping) {
                            Text(if (installStopping) tr("Stopping…") else tr("Stop"))
                        }
                    }
                }
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
                            I18n.t(if (providers.size == 1) "%s extension installed" else "%s extensions installed").replace("%s", providers.size.toString()),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            I18n.t(if (repos.size == 1) "%s repo" else "%s repos").replace("%s", repos.size.toString()) + " · " + I18n.t("%s enabled").replace("%s", enabledCount.toString()),
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
                        icon = Icons.Filled.Extension,
                        title = tr("SkyStream repos"),
                        subtitle = tr("repo.json · SkyStream extensions"),
                        onClick = { onOpenFolder(SourceFolder.SKYSTREAM) }
                    )
                    SourceDivider()
                    SourceActionRow(
                        icon = Icons.Filled.Extension,
                        title = tr("Aniyomi repos"),
                        subtitle = tr("index.min.json · Aniyomi extensions"),
                        onClick = { onOpenFolder(SourceFolder.ANIYOMI) }
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
                        icon = Icons.Filled.LiveTv,
                        title = tr("IPTV playlists"),
                        subtitle = tr("M3U / M3U8 links and files · live channels"),
                        onClick = { onOpenFolder(SourceFolder.IPTV) }
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
                        icon = Icons.Filled.Add,
                        title = tr("Install .sky extension"),
                        subtitle = tr("SkyStream plugin · from a URL or a local file"),
                        onClick = onAddSkyStreamUrl,
                        trailingIcon = Icons.Filled.FolderOpen,
                        onTrailing = onPickSkyStreamFile
                    )
                    SourceDivider()
                    SourceActionRow(
                        icon = Icons.Filled.Add,
                        title = tr("Install Aniyomi extension (.apk)"),
                        subtitle = tr("Aniyomi extension · from a URL or a local file"),
                        onClick = onAddAniyomiUrl,
                        trailingIcon = Icons.Filled.FolderOpen,
                        onTrailing = onPickAniyomiFile
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
    val awaiting = repos.filter { pluginsByRepo[it.url] == null }
    val failedRepos = awaiting.filter { repoState[it.url]?.error != null }
    // "still loading" counts ONLY repos that have neither data nor an error: a
    // repo whose fetch already failed was counted as loading too, so a search
    // with one dead repo in the list sat on "Searching…" forever — the reported
    // "stuck on searching extensions". A failed repo is reported as failed (its
    // row with Retry, and the button below) and no longer pretends to be coming.
    val pendingRepos = awaiting.filter { repoState[it.url]?.error == null }
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
        if (awaiting.isNotEmpty()) repoStatusItems(awaiting, repoState, onRefreshRepo)
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
                    installed = SourceUrls.anyKeyIn(p.url, installedUrls),
                    onInstall = { onInstallPlugin(p, repo.kind) },
                    onUninstall = { onUninstallPlugin(p, repo.kind) },
                    onSettings = repoPluginSettingsTarget(p, providers, cs3SettingsIds)
                        ?.let { target -> { onOpenSettings(target) } },
                    updateAvailable = SourceUrls.anyKeyIn(p.url, outdatedUrls),
                    onUpdate = { onUpdatePlugin(p, repo.kind) },
                    kind = repo.kind,
                    repoUrl = repo.url,
                )
            }
        }
    }

    if (awaiting.isNotEmpty()) repoStatusItems(awaiting, repoState, onRefreshRepo)
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
    /** True while a bulk install/update runs, and true again while it is
     *  finishing the extension it is on (see [ExtensionsViewModel.stopBulkInstall]). */
    installRunning: Boolean = false,
    installStopping: Boolean = false,
    onStopInstall: () -> Unit = {},
    onOpenSettings: (ContentProvider) -> Unit,
    onInstallAll: () -> Unit,
) {
    val cs3SettingsIds = rememberCs3SettingsIds(providers)
    Column(Modifier.fillMaxSize()) {
        val unit = when (repo.kind) {
            RepoKind.HIKARI -> "extension"
            RepoKind.NUVIO -> "provider"
            RepoKind.SKYSTREAM -> "extension"
            RepoKind.ANIYOMI -> "extension"
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
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        busyMsg,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    if (installRunning) {
                        Spacer(Modifier.width(12.dp))
                        OutlinedButton(onClick = onStopInstall, enabled = !installStopping) {
                            Text(if (installStopping) tr("Stopping…") else tr("Stop"))
                        }
                    }
                }
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
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                // Clear of the floating taskbar (0 when there is no bar).
                bottom = LocalTaskbarInset.current + 24.dp,
            )
        ) {
            val uninstalled = plugins.count { !SourceUrls.anyKeyIn(it.url, installedUrls) }
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
                            I18n.t(
                                "This is a bundle repo — it holds no %s of its own. Its repos were " +
                                    "added to your repo list: open Phisher, CNC, CSX… and install %s from there."
                            ).replace("%s", "${unit}s"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                }
                plugins.isEmpty() -> item {
                    Text(
                        I18n.t("No %s found in this repo.").replace("%s", "${unit}s"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                }
                else -> items(plugins, key = { it.url }) { p ->
                    PluginRow(
                        p = p,
                        installed = SourceUrls.anyKeyIn(p.url, installedUrls),
                        onInstall = { onInstall(p) },
                        onUninstall = { onUninstall(p) },
                        onSettings = repoPluginSettingsTarget(p, providers, cs3SettingsIds)
                            ?.let { target -> { onOpenSettings(target) } },
                        updateAvailable = SourceUrls.anyKeyIn(p.url, outdatedUrls),
                        onUpdate = { onUpdate(p) },
                        kind = repo.kind,
                        repoUrl = repo.url,
                    )
                }
            }
        }
    }
}

@Composable
private fun ExtensionIcon(url: String?, modifier: Modifier = Modifier) {
    // Repo icon URLs are templates in the wild: CloudStream repos serve
    // `…/icon.png?size=%size%` (and the `%exact_size%` variant), and plenty of
    // listings use protocol-relative `//host/icon.png`. Neither survives being
    // handed straight to Coil from an Android app, so normalize first — an
    // unfetchable URL is exactly how a perfectly good icon degrades into the
    // placeholder glyph.
    val safe = url
        ?.replace("%exact_size%", "48")
        ?.replace("%size%", "48")
        ?.let { com.hikari.app.ui.ExtensionIcons.absolute(it, null) }
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
private fun RepoPluginIcon(
    p: Cs3RepoPlugin,
    kind: RepoKind,
    repoUrl: String,
    modifier: Modifier = Modifier,
) {
    // A repo listing is resolved before anything is installed, so the row only
    // has what the listing declared — and most SkyStream listings declare no
    // icon at all (1 of 25 entries in the community repo carries an `iconUrl`,
    // and 20 of 25 carry a placeholder `baseUrl`), which is why every row fell
    // back to the puzzle-piece glyph. Resolve the rest lazily off the main
    // thread: the entry's own image fields, else its first addon manifest's
    // logo, else the site favicon. ExtensionIcons caches per entry, so
    // scrolling the listing never refetches.
    var icon by remember(p.url, kind) { mutableStateOf(p.iconUrl) }
    LaunchedEffect(p.url, kind) {
        if (icon.isNullOrBlank()) {
            icon = withContext(Dispatchers.IO) {
                com.hikari.app.ui.ExtensionIcons.forRepoPlugin(p, kind, repoUrl)
            }
        }
    }
    ExtensionIcon(url = icon, modifier = modifier)
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
        Toast.makeText(context, I18n.t("Loading %s…").replace("%s", p.config.name), Toast.LENGTH_SHORT).show()
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
        // The commonest real cause is a STALE activity: the plugin's
        // `openSettings` closure captured an activity that has since been
        // destroyed and then throws "FragmentManager has been destroyed" the
        // moment it shows its DialogFragment. Retrying the same instance just
        // re-runs that closure, so the plugin is REBUILT here (dropping the
        // cached instance) and then retried against a live activity.
        if (!opened) {
            kotlinx.coroutines.delay(250)
            withContext(Dispatchers.IO) {
                runCatching { p.rebuildSettings(context) }
            }
            kotlinx.coroutines.delay(250)
            ready = withContext(Dispatchers.IO) {
                runCatching { p.prepareSettings() }.getOrDefault(false)
            }
            opened = ready && withContext(Dispatchers.Main) {
                runCatching {
                    val live = HikariApp.mainActivity
                        ?.takeIf { !it.isFinishing && !it.isDestroyed }
                    p.openSettings(live)
                }.getOrDefault(false)
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
) {
    val glass = rememberGlassTokens()
    val tileShape = RoundedCornerShape(12.dp)
    GlassCard(Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(tileShape)
                    .background(Brush.verticalGradient(listOf(glass.fillTop, glass.fillBottom)))
                    .border(1.dp, glass.border, tileShape),
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
        title = { Text(I18n.t("%s settings").replace("%s", provider.config.name)) },
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

/**
 * What a Stremio addon IS.
 *
 * The gear on an addon used to open [NuvioSettingsDialog], which reads the
 * provider's FILE (`File(provider.config.url)`) — an addon is a remote manifest
 * URL, so it read nothing and answered "Provider file missing — reinstall this
 * extension" over a perfectly healthy addon (the reported gear-on-HdHub error).
 * A Stremio addon has no settings to edit; it has a manifest URL and a list of
 * what it can serve, so that is what this shows.
 */
@Composable
private fun StremioAddonInfoDialog(provider: ContentProvider, onDismiss: () -> Unit) {
    val id = provider.config.id
    val summary = com.hikari.app.providers.StremioAddon.resourceSummary[id]
    val streamOnly = com.hikari.app.providers.StremioAddon.streamOnlyAddons[id] == true
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(provider.config.name.ifBlank { tr("Stremio addon") }) },
        text = {
            Column {
                Text(
                    tr("Stremio addon"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    provider.config.url,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    tr(
                        when {
                            summary != null -> "What it provides: %s".replace("%s", summary)
                            streamOnly -> "It has no catalog of its own — it adds playback servers to titles you open from any extension."
                            else -> "It adds playback servers to titles you open anywhere in the app."
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    tr(
                        "Addons have no settings of their own — use the switch to turn this one off " +
                            "without uninstalling it."
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(tr("Close")) }
        },
    )
}

/**
 * What an IPTV playlist IS: where it was read from, how many channels it holds,
 * and a way to read it again (a panel that added channels, or a link that was
 * briefly down). A playlist has no settings of its own, so a Nuvio-style
 * settings screen would only ever report a missing file — this is the honest
 * version of that screen for a playlist.
 */
@Composable
private fun IptvInfoDialog(
    provider: ContentProvider,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    val id = provider.config.id
    val error = com.hikari.app.providers.IptvProvider.iptvErrors[id]
    val count = com.hikari.app.providers.IptvProvider.channelCounts[id]
    val local = !provider.config.url.startsWith("http")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(provider.config.name.ifBlank { tr("IPTV playlist") }) },
        text = {
            Column {
                Text(
                    tr("IPTV playlist"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    provider.config.url,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    when {
                        error != null -> error
                        count != null -> I18n.t(
                            "Channels found: %s — they appear on Home, in search and in the " +
                                "player's server list."
                        ).replace("%s", count.toString())
                        else -> tr("Reading this playlist…")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (error != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (local) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        tr("Stored inside the app — removing this playlist deletes the file too."),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onRefresh() }) { Text(tr("Refresh")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("Close")) }
        },
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
    val rawLabel = el.optString("label")
    val rawDescription = el.optString("description").ifBlank { null }
    // Extensions write this screen's rows themselves, and a "header"/"info"
    // row is a favourite place for a funding ask ("Support us on Patreon", a
    // ko-fi link). Nothing about MONEY belongs in an extension's settings, so
    // a promo-only row is dropped outright and every other string is cleaned
    // (markup and donation links stripped) before it is drawn.
    if ((type == "header" || type == "info") && PromoGuard.isPromoText(rawLabel)) return
    val label = PromoGuard.cleanText(rawLabel).ifBlank { rawLabel }
    val description = rawDescription?.let { PromoGuard.cleanText(it).ifBlank { null } }
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
                            RepoKind.SKYSTREAM -> "SkyStream"
                            RepoKind.ANIYOMI -> "Aniyomi"
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
                            val unit = when (repo.kind) {
                                RepoKind.NUVIO -> "provider"
                                RepoKind.SKYSTREAM -> "extension"
                                RepoKind.ANIYOMI -> "extension"
                                else -> "plugin"
                            }
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
    kind: RepoKind = RepoKind.CS3,
    repoUrl: String = "",
) {
    val glass = rememberGlassTokens()
    val tileShape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(tileShape)
                .background(Brush.verticalGradient(listOf(glass.fillTop, glass.fillBottom)))
                .border(1.dp, glass.border, tileShape),
            contentAlignment = Alignment.Center
        ) {
            RepoPluginIcon(
                p = p,
                kind = kind,
                repoUrl = repoUrl,
                modifier = Modifier.size(28.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
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

private fun pluginStatus(p: ContentProvider, iptvTick: Int = 0): String? {
    if (p.config.type == ProviderType.IPTV) {
        val err = com.hikari.app.providers.IptvProvider.iptvErrors[p.config.id]
        if (err != null) return err.take(200)
        val n = com.hikari.app.providers.IptvProvider.channelCounts[p.config.id]
        return when {
            n != null && n > 0 -> "$n channels"
            else -> "Reading playlist…"
        }
    }
    if (p.config.type == ProviderType.NUVIO) {
        if (com.hikari.app.nuvio.NuvioPluginManager.fileMissing(p.config)) {
            return "Provider file missing — reinstall this extension"
        }
        return null
    }
    if (p.config.type == ProviderType.SKYSTREAM) {
        if (com.hikari.app.skystream.SkyStreamPluginManager.fileMissing(p.config)) {
            return "Extension file missing — reinstall this extension"
        }
        val err = com.hikari.app.skystream.SkyStreamProvider.catalogErrors[p.config.id]
        // No verification story here: an extension whose site answers with a
        // browser check is simply left out of searches (see
        // ContentRepository.crossCfSkip), so naming the wall only adds a scary,
        // unactionable line about a different site.
        if (err != null &&
            com.hikari.app.net.CloudflareVerifier.isVerificationMessage(err)
        ) return null
        return err?.take(200)
    }
    if (p.config.type == ProviderType.ANIYOMI) {
        if (com.hikari.app.aniyomi.AniyomiExtensionManager.fileMissing(p.config)) {
            return "Extension file missing — reinstall this extension"
        }
        val err = com.hikari.app.aniyomi.AniyomiProvider.catalogErrors[p.config.id]
        // Same rule as below: a browser check on the scraped site is never
        // reported as a scary, unactionable line.
        if (err != null &&
            com.hikari.app.net.CloudflareVerifier.isVerificationMessage(err)
        ) return null
        return err?.take(200)
    }
    if (p.config.type != ProviderType.CS3) return null
    val err = com.hikari.app.cs3.Cs3MainApiProvider.catalogErrors[p.config.id]
    if (err != null) {
        // Same rule as above: a verification wall is never reported.
        if (com.hikari.app.net.CloudflareVerifier.isVerificationMessage(err)) return null
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
                        if (sites.isEmpty()) I18n.t("No sites added yet — tap to expand")
                        else I18n.t(if (sites.size == 1) "%s site" else "%s sites").replace("%s", sites.size.toString()),
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

enum class SourceFolder { CLOUDSTREAM, HIKARI, NUVIO, SKYSTREAM, ANIYOMI, STREMIO, IPTV }

@Composable
private fun SourceFolderView(
    folder: SourceFolder,
    repos: List<Cs3Repo>,
    providers: List<ContentProvider>,
    pluginsByRepo: Map<String, List<Cs3RepoPlugin>>,
    repoState: Map<String, RepoLoadState>,
    iptvTick: Int = 0,
    busy: Boolean,
    busyMsg: String,
    successMsg: String?,
    errorMsg: String?,
    /** True while a bulk install/update runs, and true again while it is
     *  finishing the extension it is on (see [ExtensionsViewModel.stopBulkInstall]). */
    installRunning: Boolean = false,
    installStopping: Boolean = false,
    onStopInstall: () -> Unit = {},
    onBack: () -> Unit,
    onOpenRepo: (Cs3Repo) -> Unit,
    onAddRepo: () -> Unit,
    onAddStremio: () -> Unit,
    onAddIptv: () -> Unit,
    onWarmIptv: () -> Unit = {},
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
        SourceFolder.SKYSTREAM -> RepoKind.SKYSTREAM
        SourceFolder.ANIYOMI -> RepoKind.ANIYOMI
        SourceFolder.STREMIO, SourceFolder.IPTV -> null
    }
    val (title, subtitle) = when (folder) {
        SourceFolder.CLOUDSTREAM -> "CloudStream repos" to "repo.json · CloudStream extensions"
        SourceFolder.HIKARI -> "Hikari repos" to "repo.json · Hikari extensions"
        SourceFolder.NUVIO -> "Nuvio repos" to "manifest.json · Nuvio providers"
        SourceFolder.SKYSTREAM -> "SkyStream repos" to "repo.json · SkyStream extensions"
        SourceFolder.ANIYOMI -> "Aniyomi repos" to "index.min.json · Aniyomi extensions"
        SourceFolder.STREMIO -> "Stremio addons" to "manifest.json · Stremio addons"
        SourceFolder.IPTV -> "IPTV playlists" to "M3U / M3U8 links and files · your channels"
    }
    val kindLabel = when (folder) {
        SourceFolder.CLOUDSTREAM -> "CloudStream"
        SourceFolder.HIKARI -> "Hikari"
        SourceFolder.NUVIO -> "Nuvio"
        SourceFolder.SKYSTREAM -> "SkyStream"
        SourceFolder.ANIYOMI -> "Aniyomi"
        SourceFolder.STREMIO -> "Stremio"
        SourceFolder.IPTV -> "IPTV"
    }
    val folderRepos = if (kind != null) repos.filter { it.kind == kind } else emptyList()
    val stremioProviders = if (folder == SourceFolder.STREMIO)
        providers.filter { it.config.type == ProviderType.STREMIO }
    else emptyList()
    // IPTV playlists are not repos: each one IS a provider (the playlist), and
    // its "add" action takes a link or a file rather than a repo URL.
    val iptvProviders = if (folder == SourceFolder.IPTV)
        providers.filter { it.config.type == ProviderType.IPTV }
    else emptyList()

    // Opening the IPTV folder reads each playlist once, so the rows can say how
    // many channels they hold instead of waiting for Home to do it.
    LaunchedEffect(folder, iptvProviders.map { it.config.id }) {
        if (folder == SourceFolder.IPTV) onWarmIptv()
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
                when (folder) {
                    SourceFolder.STREMIO ->
                        I18n.t(if (stremioProviders.size == 1) "%s addon" else "%s addons")
                            .replace("%s", stremioProviders.size.toString())
                    SourceFolder.IPTV ->
                        I18n.t(if (iptvProviders.size == 1) "%s playlist" else "%s playlists")
                            .replace("%s", iptvProviders.size.toString())
                    else ->
                        I18n.t(if (folderRepos.size == 1) "%s repo" else "%s repos")
                            .replace("%s", folderRepos.size.toString())
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        HorizontalDivider()
        if (busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    busyMsg,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                if (installRunning) {
                    Spacer(Modifier.width(12.dp))
                    OutlinedButton(onClick = onStopInstall, enabled = !installStopping) {
                        Text(if (installStopping) tr("Stopping…") else tr("Stop"))
                    }
                }
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
            contentPadding = PaddingValues(
                // Clear of the floating taskbar (0 when there is no bar).
                bottom = LocalTaskbarInset.current + 8.dp,
            )
        ) {
            if (kind != null) {
                if (folderRepos.isEmpty()) {
                    item {
                        EmptyState(
                            title = I18n.t("No %s yet").replace("%s", title),
                            subtitle = I18n.t("Tap \"Add repo\" below to add your first %s repo.").replace("%s", kindLabel),
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
            } else if (folder == SourceFolder.IPTV) {
                if (iptvProviders.isEmpty()) {
                    item {
                        EmptyState(
                            title = tr("No IPTV playlists yet"),
                            subtitle = tr(
                                "Tap \"Add IPTV playlist\" below and paste an M3U/M3U8 link (an " +
                                    "Xtream panel's get.php link works), or pick a playlist file " +
                                    "from storage. Its channels then appear on Home, in search, " +
                                    "and in the player's server list like any other extension."
                            ),
                            actionLabel = null,
                            action = null
                        )
                    }
                }
                items(iptvProviders.distinctBy { it.config.id }, key = { it.config.id }) { p ->
                    ProviderCard(
                        p = p,
                        status = pluginStatus(p, iptvTick),
                        onToggle = { enabled -> onToggleProvider(p.config.id, enabled) },
                        onDelete = { onDeleteProvider(p.config.id) },
                        onSettings = { onOpenSettings(p) }
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
        // The same repo can be installed twice, and two identical Lazy keys are a
        // crash in Compose rather than a warning.
        items(stremioProviders.distinctBy { it.config.id }, key = { it.config.id }) { p ->
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
            label = when (folder) {
                SourceFolder.STREMIO -> "Add Stremio addon"
                SourceFolder.IPTV -> "Add IPTV playlist"
                else -> "Add repo"
            },
            onClick = when (folder) {
                SourceFolder.STREMIO -> onAddStremio
                SourceFolder.IPTV -> onAddIptv
                else -> onAddRepo
            }
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
    /** True while a bulk install/update runs, and true again while it is
     *  finishing the extension it is on (see [ExtensionsViewModel.stopBulkInstall]). */
    installRunning: Boolean = false,
    installStopping: Boolean = false,
    onStopInstall: () -> Unit = {},
    onBack: () -> Unit,
    onOpenRepo: (Cs3Repo) -> Unit,
    onAddRepo: () -> Unit,
    onAddHikiRepo: () -> Unit,
    onAddNuvioRepo: () -> Unit,
    onAddSkyStreamRepo: () -> Unit,
    onAddAniyomiRepo: () -> Unit,
    onAddStremio: () -> Unit,
    onAddIptv: () -> Unit,
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
    val skyGroupTitle = tr("SkyStream")
    val aniyomiGroupTitle = tr("Aniyomi")
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
                    I18n.t("%s extensions").replace("%s", providers.size.toString()) + " · " + I18n.t("%s repos").replace("%s", repos.size.toString()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        HorizontalDivider()
        if (busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    busyMsg,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                if (installRunning) {
                    Spacer(Modifier.width(12.dp))
                    OutlinedButton(onClick = onStopInstall, enabled = !installStopping) {
                        Text(if (installStopping) tr("Stopping…") else tr("Stop"))
                    }
                }
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
            contentPadding = PaddingValues(
                // Clear of the floating taskbar (0 when there is no bar).
                bottom = LocalTaskbarInset.current + 24.dp,
            )
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
            repoGroup(
                title = skyGroupTitle,
                groupRepos = repos.filter { it.kind == RepoKind.SKYSTREAM },
                pluginsByRepo = pluginsByRepo,
                repoState = repoState,
                onAdd = onAddSkyStreamRepo,
                onOpenRepo = onOpenRepo,
                onRefreshRepo = onRefreshRepo,
                onRemoveRepo = onRemoveRepo,
            )
            repoGroup(
                title = aniyomiGroupTitle,
                groupRepos = repos.filter { it.kind == RepoKind.ANIYOMI },
                pluginsByRepo = pluginsByRepo,
                repoState = repoState,
                onAdd = onAddAniyomiRepo,
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
            items(filteredProviders.distinctBy { it.config.id }, key = { it.config.id }) { p ->
                ProviderCard(
                    p = p,
                    status = pluginStatus(p),
                    onToggle = { enabled -> onToggleProvider(p.config.id, enabled) },
                    onDelete = { onDeleteProvider(p.config.id) },
                    onSettings = when {
                        p.config.type == ProviderType.NUVIO -> { { onOpenSettings(p) } }
                        // A playlist has no settings screen — the gear opens what
                        // it IS instead: where it was read from, how many channels
                        // it holds, and a way to read it again.
                        p.config.type == ProviderType.IPTV -> { { onOpenSettings(p) } }
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
                I18n.t("No %s repos yet").replace("%s", title),
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
    // The button is pinned under the list, so on a tab that shows the floating
    // taskbar it would otherwise sit behind it and be untappable. Lift it by the
    // bar's height (0.dp on screens with no bar).
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = 16.dp,
                end = 16.dp,
                top = 12.dp,
                bottom = 12.dp + LocalTaskbarInset.current,
            )
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
    /** True while a bulk install/update runs, and true again while it is
     *  finishing the extension it is on (see [ExtensionsViewModel.stopBulkInstall]). */
    installRunning: Boolean = false,
    installStopping: Boolean = false,
    onStopInstall: () -> Unit = {},
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
                    I18n.t(if (repos.size == 1) "%s repo added" else "%s repos added").replace("%s", repos.size.toString()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        HorizontalDivider()
        if (busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    busyMsg,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                if (installRunning) {
                    Spacer(Modifier.width(12.dp))
                    OutlinedButton(onClick = onStopInstall, enabled = !installStopping) {
                        Text(if (installStopping) tr("Stopping…") else tr("Stop"))
                    }
                }
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
            contentPadding = PaddingValues(
                // Clear of the floating taskbar (0 when there is no bar).
                bottom = LocalTaskbarInset.current + 8.dp,
            )
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
    /** A bulk install/update started elsewhere is still running — see the
     *  Stop button below (and [ExtensionsViewModel.stopBulkInstall]). */
    installRunning: Boolean = false,
    installStopping: Boolean = false,
    onStopInstall: () -> Unit = {},
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
                    I18n.t(if (providers.size == 1) "%s extension installed" else "%s extensions installed").replace("%s", providers.size.toString()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        HorizontalDivider()
        if (busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    busyMsg,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                if (installRunning) {
                    Spacer(Modifier.width(12.dp))
                    OutlinedButton(onClick = onStopInstall, enabled = !installStopping) {
                        Text(if (installStopping) tr("Stopping…") else tr("Stop"))
                    }
                }
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
            contentPadding = PaddingValues(
                // Clear of the floating taskbar (0 when there is no bar).
                bottom = LocalTaskbarInset.current + 24.dp,
            )
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
            items(filteredProviders.distinctBy { it.config.id }, key = { it.config.id }) { p ->
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
                    updateAvailable = providerSource(p)?.let { SourceUrls.anyKeyIn(it, outdatedUrls) } == true,
                    onUpdate = { onUpdateProvider(p) },
                )
            }
        }
    }

    settingsProvider?.takeIf { it.config.type == ProviderType.NUVIO }?.let { provider ->
        NuvioSettingsDialog(
            provider = provider,
            onDismiss = { settingsProvider = null },
        )
    }
}
