package com.hikari.app.ui.screens

import android.app.Application
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import com.hikari.app.HikariApp
import com.hikari.app.data.CatalogRow
import com.hikari.app.data.ContentRepository
import com.hikari.app.data.Logs
import com.hikari.app.data.MediaItem
import com.hikari.app.data.ProviderType
import com.hikari.app.ui.PosterLoader
import com.hikari.app.ui.components.ContinueWatchingRow
import com.hikari.app.ui.components.EmptyState
import com.hikari.app.ui.components.HeroBanner
import com.hikari.app.ui.components.MediaRow
import com.hikari.app.ui.components.ShimmerRow
import com.hikari.app.ui.navigation.Routes
import com.hikari.app.providers.ContentProvider
import com.hikari.app.web.WebViewActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val manager = (app as HikariApp).providers
    private val store = (app as HikariApp).store
    private val repo = ContentRepository(manager)

    private val _rows = MutableStateFlow<List<CatalogRow>>(emptyList())
    val rows: StateFlow<List<CatalogRow>> = _rows.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _selectedProvider = MutableStateFlow<String?>(null)
    val selectedProvider: StateFlow<String?> = _selectedProvider.asStateFlow()

    val providers: StateFlow<List<ContentProvider>> = manager.providers

    private var loadJob: kotlinx.coroutines.Job? = null

    // Last successful home feed per selected-provider key ("all" when the user
    // is on the combined feed). Returning to Home, or re-picking the same
    // provider, paints this INSTANTLY and refreshes in the background instead
    // of blanking the screen to a spinner and re-fetching every catalog.
    //
    // Remembers EVERY feed the user has viewed (no eviction) so switching back
    // to any provider is always instant. Each row holds poster-cache tokens
    // rather than full images ([tokenizePoster] below), so the whole map stays
    // cheap no matter how many extensions were browsed.
    private val homeCache = LinkedHashMap<String, List<CatalogRow>>()

    init {
        viewModelScope.launch {
            // Restore the user's last pick ("All" when never picked).
            _selectedProvider.value = store.homeProvider().ifBlank { null }
            loadInternal()
        }
        viewModelScope.launch {
            manager.providers.collect { ps ->
                val sel = _selectedProvider.value
                if (sel != null && ps.none { it.config.enabled && it.config.id == sel }) {
                    _selectedProvider.value = null
                    store.setHomeProvider("")
                }
                loadInternal()
            }
        }
    }

    fun selectProvider(id: String?) {
        if (_selectedProvider.value == id) return
        _selectedProvider.value = id
        viewModelScope.launch { store.setHomeProvider(id ?: "") }
        viewModelScope.launch { loadInternal() }
    }

    private suspend fun loadInternal() {
        loadJob?.cancel()
        val key = _selectedProvider.value ?: "all"
        val cached = homeCache[key]
        if (cached != null) {
            // Stale-while-revalidate: show the previous feed immediately (no
            // spinner) and refresh underneath.
            _rows.value = cached
            _loading.value = false
        } else {
            _loading.value = true
            _rows.value = emptyList()
        }
        // Keep the process alive (and awake) for the whole load: pressing Home
        // mid-load used to freeze the app and stop every catalog dead. See
        // [com.hikari.app.work.BackgroundWork].
        val work = com.hikari.app.work.BackgroundWork.begin(
            if (key == "all") "Loading Home catalogs"
            else "Loading " + (manager.byId(key)?.config?.name ?: "catalog")
        )
        loadJob = viewModelScope.launch {
            // Row key -> poster-tokenized copy, so a partial update only
            // tokenizes the rows that just arrived. MRDS/51CG catalogs carry
            // full-size base64 data: posters; the Home feed keeps hundreds alive
            // at once and OOMs on a stock heap, so each is collapsed into a tiny
            // disk-cache token ([PosterLoader.model] resolves it back to bytes).
            val tokenCache = HashMap<String, CatalogRow>()
            var latest: List<CatalogRow> = emptyList()
            repo.homeRowsStreaming(_selectedProvider.value).collect { rows ->
                val tokenized = withContext(Dispatchers.IO) {
                    rows.map { row ->
                        val ck = row.key.ifBlank { "${row.providerId}|${row.catalogId}|${row.title}" }
                        tokenCache.getOrPut(ck) {
                            row.copy(items = row.items.map { it.tokenizePoster() })
                        }
                    }
                }
                latest = tokenized
                if (tokenized.isEmpty()) return@collect
                // First load (nothing cached yet): paint each catalog the moment
                // it lands, so the first rows show in seconds instead of after
                // EVERY provider finished (the 20-25s wait). A refresh keeps the
                // cached feed on screen and swaps it in one go at the end.
                if (cached == null) {
                    _rows.value = tokenized
                    _loading.value = false
                }
            }
            if (latest.isNotEmpty()) {
                homeCache[key] = latest
                _rows.value = latest
                _loading.value = false
            } else if (cached == null) {
                // Stream returned nothing (all providers slow / offline): keep
                // the cached feed if we had one, otherwise don't leave the
                // spinner up forever.
                _rows.value = emptyList()
                _loading.value = false
            }
        }
        loadJob?.invokeOnCompletion { com.hikari.app.work.BackgroundWork.end(work) }
        loadJob?.join()
    }

    private fun MediaItem.tokenizePoster(): MediaItem {
        val p = PosterLoader.tokenize(posterUrl)
        val b = PosterLoader.tokenize(backdropUrl)
        return if (p == posterUrl && b == backdropUrl) this
        else copy(posterUrl = p, backdropUrl = b)
    }

    fun refresh() {
        viewModelScope.launch { loadInternal() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(nav: NavHostController) {
    val vm: HomeViewModel = viewModel()
    val rows by vm.rows.collectAsState()
    val loading by vm.loading.collectAsState()
    val selected by vm.selectedProvider.collectAsState()
    val providers by vm.providers.collectAsState()
    // Stream-only Stremio addons (Torrentio, NovaStream…) have no catalog to
    // browse, so like in Stremio they don't appear here at all — only addons
    // that can fill the home screen do. CS3 plugins / universal scrapers are
    // always shown (their catalogs are dynamic).
    val activeProviders = providers.filter {
        it.config.enabled && (it.config.type != com.hikari.app.data.ProviderType.STREMIO ||
            com.hikari.app.providers.StremioAddon.streamOnlyAddons[it.config.id] != true)
    }
    val selectedName = providers.firstOrNull { it.config.id == selected }?.config?.name
    var showCrash by remember { mutableStateOf(HikariApp.lastCrash != null) }
    var showPicker by remember { mutableStateOf(false) }
    var showTranslate by remember { mutableStateOf(false) }
    var showSearchDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Cloudflare verification: when the selected extension's site is blocked
    // by a WAF check, this globe button opens the site in the WebView so the
    // user can verify once; the WebView auto-closes once the challenge passes
    // and the catalog reloads (the extension's cookie jar is now cleared).
    // The button shows for EVERY selected extension — the site URL is resolved
    // lazily on tap, off the main thread (for CS3 plugins that loads the plugin
    // dex to read its mainUrl, which can take seconds and must never block the
    // UI thread — this is why the old version hid the button whenever that
    // lookup hadn't finished or transiently failed).
    val context = LocalContext.current
    val verifyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        vm.refresh()
    }

    val app = context.applicationContext as HikariApp
    // Continue Watching: history entries that were meaningfully started and
    // aren't within a minute of the end (those read as finished), newest first.
    // IMPORTANT: remember the Flow instances. Building `store.historyFlow()`
    // inline creates a NEW Flow object on every recomposition, so
    // collectAsState re-subscribes from scratch each time and resets to its
    // `initial` value (emptyList) — which is exactly why the Continue Watching
    // shelf stayed blank no matter how much was watched.
    val historyFlow = remember { app.store.historyFlow() }
    val hideContinueFlow = remember { app.store.hideContinueFlow() }
    val history by historyFlow.collectAsState(initial = emptyList())
    // Settings → "Continue Watching": lets the user hide the shelf entirely.
    val hideContinue by hideContinueFlow.collectAsState(initial = false)
    val continueEntries = remember(history) {
        history.asSequence()
            .filter {
                it.positionMs > 1_000L &&
                    (it.durationMs <= 0L || it.positionMs < it.durationMs - 10_000L)
            }
            // Newest first, one card per video/episode. The store dedupes, but a
            // legacy/racing write could leave a duplicate — and a duplicate
            // Compose key in the row would crash the whole Home screen.
            .sortedByDescending { it.watchedAt }
            .distinctBy { it.uniqueKey }
            .take(12)
            .toList()
    }
    // History only stores a poster; backdrops live on the catalog items, so map
    // them by provider + id to give the Continue cards landscape art.
    val backdropByKey = remember(rows) {
        val m = HashMap<String, String?>()
        rows.forEach { row ->
            row.items.forEach { item ->
                m["${item.providerId}|${item.type}|${item.id}"] = item.backdropUrl
            }
        }
        m
    }
    // Featured hero: the first catalog's title-artful entries (falling back to
    // its first entries when nothing carries a backdrop).
    val featured = remember(rows) {
        val first = rows.firstOrNull()?.items.orEmpty()
        (first.filter { !it.backdropUrl.isNullOrBlank() }.ifEmpty { first }).take(8)
    }
    val openGlobalSearch: () -> Unit = {
        Routes.navigateTab(nav, Routes.SEARCH)
    }
    // Tapping the header search icon asks HOW to search when a specific
    // extension's catalog is being browsed: globally across every provider, or
    // scoped to the extension you're looking at. With no extension selected
    // there's only one sensible answer, so it goes straight to global search.
    val openSearch: () -> Unit = {
        if (selected != null) showSearchDialog = true else openGlobalSearch()
    }
    val openVerify: () -> Unit = {
        scope.launch {
            val url = withContext(Dispatchers.IO) {
                providers.firstOrNull { it.config.id == selected }?.let { webUrlFor(it) }
            }
            if (url != null) {
                verifyLauncher.launch(
                    Intent(context, WebViewActivity::class.java).apply {
                        putExtra("url", url)
                        putExtra("title", "Verify: ${selectedName ?: "site"}")
                        putExtra("providerId", selected)
                        putExtra("autoCloseWhenCloudflarePassed", true)
                    }
                )
            } else {
                Toast.makeText(
                    context,
                    "Couldn't determine this extension's site",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 72.dp)
        ) {
            // Crash report FIRST: an uncaught OOM/exception from the previous
            // launch is the most important thing on this screen. It used to be
            // rendered after the hero + Continue Watching row, so it appeared in
            // the middle of the page; as the leading item it now sits at the top.
            if (showCrash && HikariApp.lastCrash != null) {
                item(key = "crash-banner") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.errorContainer)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "The app crashed on a previous launch:\n${HikariApp.lastCrash}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            // The banner is the moment the user is most willing to
                            // send us the report: hand over the crash log itself
                            // (Settings → Logs & diagnostics serves the full set)
                            // instead of asking for a screenshot.
                            val files = Logs.existingFiles(context)
                            if (files.isEmpty()) {
                                Toast.makeText(
                                    context,
                                    "No log file found",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                shareFiles(context, files.map { it.file }, "Hikari crash log")
                            }
                        }) {
                            Text("Share log")
                        }
                        TextButton(onClick = {
                            showCrash = false
                            HikariApp.instance.clearCrash()
                        }) {
                            Text("Dismiss")
                        }
                    }
                }
            }
            item {
                if (featured.isNotEmpty()) {
                    Box(Modifier.fillMaxWidth()) {
                        HeroBanner(
                            items = featured,
                            onClick = { item ->
                                Routes.safeNavigate(
                                    nav,
                                    Routes.detail(
                                        item.providerId, item.type, item.id,
                                        item.title, item.posterUrl, item.rawType
                                    )
                                )
                            },
                        )
                        HomeHeader(
                            selected = selected,
                            onSearch = openSearch,
                            onTranslate = { showTranslate = true },
                            onVerify = openVerify,
                            overlay = true,
                            modifier = Modifier.align(Alignment.TopCenter),
                        )
                    }
                } else {
                    HomeHeader(
                        selected = selected,
                        onSearch = openSearch,
                        onTranslate = { showTranslate = true },
                        onVerify = openVerify,
                        overlay = false,
                    )
                }
            }
            if (!hideContinue && continueEntries.isNotEmpty()) {
                item(key = "continue-watching") {
                    ContinueWatchingRow(
                        entries = continueEntries,
                        backdropOf = { h -> backdropByKey["${h.providerId}|${h.type}|${h.mediaId}"] },
                        onClick = { h ->
                            Routes.safeNavigate(
                                nav,
                                Routes.detail(
                                    h.providerId, h.type, h.mediaId, h.title, h.posterUrl, "",
                                    episodeId = h.episodeId,
                                    startPositionMs = h.positionMs,
                                )
                            )
                        },
                        // The ✕ on a card drops just that entry from the shared
                        // watch-history store, so it leaves the shelf and the
                        // History tab at the same time.
                        onRemove = { h -> scope.launch { app.store.removeHistory(h.uniqueKey) } },
                    )
                }
            }
            if (loading) {
                items(4) { ShimmerRow() }
            }
            rows.forEach { row ->
                item(key = row.key.ifBlank { "${row.providerName}|${row.title}" }) {
                    MediaRow(
                        title = row.title,
                        providerName = row.providerName,
                        items = row.items,
                        onClick = { item ->
                            Routes.safeNavigate(
                                nav,
                                Routes.detail(item.providerId, item.type, item.id, item.title, item.posterUrl, item.rawType)
                            )
                        },
                        onShowAll = {
                            Routes.safeNavigate(
                                nav,
                                Routes.catalog(
                                    row.providerId, row.catalogId, row.title,
                                    row.providerName, row.type, row.rawType
                                )
                            )
                        }
                    )
                }
            }
            if (rows.isEmpty() && !loading) {
                item {
                    if (selected != null) {
                        val reason =
                            com.hikari.app.cs3.Cs3MainApiProvider.catalogErrors[selected]
                                ?: com.hikari.app.providers.StremioAddon.catalogErrors[selected]
                                ?: com.hikari.app.nuvio.NuvioScraper.catalogErrors[selected]
                        val streamOnly =
                            com.hikari.app.providers.StremioAddon.streamOnlyAddons[selected] == true
                        if (streamOnly) {
                            EmptyState(
                                title = "No catalog from ${selectedName ?: "this addon"}",
                                subtitle = "This addon doesn't provide a catalog to browse — it only " +
                                    "adds playback sources to titles opened from other addons. " +
                                    "Pick any movie or series and its streams will show up.",
                                actionLabel = "Browse all",
                                action = { vm.selectProvider(null) }
                            )
                        } else {
                            EmptyState(
                                title = "Couldn't load ${selectedName ?: "this extension"}",
                                subtitle = reason
                                    ?: "It returned no content right now. If the site is stuck behind " +
                                        "a Cloudflare check, tap the globe button at the top to verify — " +
                                        "the catalog reloads by itself when you're done. Otherwise the " +
                                        "site may be down — retry or browse another extension.",
                                actionLabel = "Retry",
                                action = { vm.refresh() }
                            )
                        }
                    } else {
                        EmptyState(
                            title = "No content yet",
                            subtitle = "Add a Stremio addon or a universal scraper to start watching.",
                            actionLabel = "Add extensions",
                            action = { Routes.navigateTab(nav, Routes.EXTENSIONS) }
                        )
                    }
                }
            }
        }

        // Floating source pill (Anikoto-style): shows the current provider and
        // opens the picker sheet. Sits above the bottom nav bar.
        Surface(
            onClick = { showPicker = true },
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.List,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    "  ${selectedName ?: "All providers"}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }

    if (showPicker) {
        ProviderPickerSheet(
            providers = activeProviders,
            selectedId = selected,
            onPick = { id ->
                showPicker = false
                vm.selectProvider(id)
            },
            onDismiss = { showPicker = false },
        )
    }

    val selId = selected
    if (showTranslate && selId != null) {
        val pid = selId
        val pname = selectedName ?: "this extension"
        val isOn = com.hikari.app.data.Translator.isOn(pid)
        AlertDialog(
            onDismissRequest = { showTranslate = false },
            title = { Text(if (isOn) "Turn off translation?" else "Translate to English?") },
            text = {
                Text(
                    if (isOn) {
                        "Translation is ON for $pname — its titles and text are shown in English."
                    } else {
                        "$pname shows content in its original language. Turn it into English? " +
                            "Only this extension is affected — every other extension stays as it is."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showTranslate = false
                    scope.launch {
                        com.hikari.app.data.Translator.enable(pid, !isOn)
                        vm.refresh()
                    }
                }) {
                    Text(if (isOn) "Turn off" else "Always translate")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTranslate = false }) { Text("Cancel") }
            },
        )
    }

    // Search scope chooser: global (every provider, with the provider chips to
    // narrow it) or scoped to the extension whose catalog is on screen.
    val searchSel = selected
    if (showSearchDialog && searchSel != null) {
        val pname = selectedName ?: "this extension"
        AlertDialog(
            onDismissRequest = { showSearchDialog = false },
            title = { Text("Search") },
            text = { Text("Search across every provider, or only inside $pname?") },
            confirmButton = {
                TextButton(onClick = {
                    showSearchDialog = false
                    openGlobalSearch()
                }) {
                    Text("Global search")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showSearchDialog = false
                    Routes.safeNavigate(nav, Routes.searchInProvider(searchSel))
                }) {
                    Text("In $pname")
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderPickerSheet(
    providers: List<ContentProvider>,
    selectedId: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    // Alphabetical (by extension name), so the picker isn't "install order".
    val filtered = remember(query) {
        val sorted = providers.sortedBy { it.config.name.lowercase() }
        if (query.isBlank()) sorted
        else sorted.filter { it.config.name.contains(query, ignoreCase = true) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(
                "Choose an extension",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Only the selected extension's catalog is shown on Home.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("Search extensions…") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth()
            )
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 24.dp),
            ) {
                item {
                    PickerRow("All providers", isSelected = selectedId == null) {
                        onPick(null)
                    }
                }
                if (filtered.isNotEmpty()) {
                    item { HorizontalDivider(Modifier.padding(vertical = 6.dp)) }
                }
                items(filtered, key = { it.config.id }) { p ->
                    PickerRow(p.config.name, isSelected = selectedId == p.config.id) {
                        onPick(p.config.id)
                    }
                }
                if (filtered.isEmpty() && query.isNotBlank()) {
                    item {
                        Text(
                            "No extension matches \"$query\"",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerRow(label: String, isSelected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
        else MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.weight(1f)
            )
            if (isSelected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/** The website URL a provider's content actually lives on (for the Cloudflare
 *  verification WebView button). HIKARI providers expose it through their SDK
 *  mainUrl; Stremio/universal use the configured URL; CS3 plugins load theirs
 *  from the plugin dex. Null when unknown — the button is hidden then. */
private fun webUrlFor(p: ContentProvider): String? = when (p.config.type) {
    ProviderType.STREMIO, ProviderType.UNIVERSAL ->
        p.config.url.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    ProviderType.HIKARI ->
        com.hikari.app.hiki.HikariRuntime.providerFor(p.config)?.mainUrl
    ProviderType.CS3 -> runCatching {
        val file = java.io.File(p.config.url)
        if (!file.exists()) return@runCatching null
        val apis = com.hikari.app.cs3.Cs3PluginManager.apisFor(com.hikari.app.HikariApp.instance, file)
        apis.getOrNull(p.config.id.substringAfterLast("|").toIntOrNull() ?: 0)?.mainUrl
            ?.takeIf { it.startsWith("http") }
    }.getOrNull()
    else -> null
}

/** The Home top bar. In [overlay] mode it is drawn on top of the hero banner
 *  (white text/icons so it reads over the backdrop art); otherwise it is a
 *  normal, opaque header above the rows. */
@Composable
private fun HomeHeader(
    selected: String?,
    onSearch: () -> Unit,
    onTranslate: () -> Unit,
    onVerify: () -> Unit,
    overlay: Boolean,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    val iconTint = if (overlay) Color.White else accent
    val subtitleColor =
        if (overlay) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "Hikari",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = accent,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            Text(
                "Every stream, one place.",
                style = MaterialTheme.typography.bodyMedium,
                color = subtitleColor,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }
        IconButton(onClick = onSearch) {
            Icon(Icons.Filled.Search, contentDescription = "Search", tint = iconTint)
        }
        // Translate: per-extension toggle — turns this extension's titles/text
        // into English inside the app. Shown whenever a provider is selected.
        selected?.let { pid ->
            val translateOn = com.hikari.app.data.Translator.isOn(pid)
            IconButton(onClick = onTranslate) {
                Text(
                    "A\u3042",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        translateOn -> accent
                        overlay -> Color.White.copy(alpha = 0.7f)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }
        // Cloudflare/verify: opens the extension's site in the WebView so the
        // user can pass a WAF check once; the catalog reloads by itself after.
        if (selected != null) {
            IconButton(onClick = onVerify) {
                Icon(
                    Icons.Filled.Public,
                    contentDescription = "Open site in web view (Cloudflare verification)",
                    tint = iconTint
                )
            }
        }
    }
}
