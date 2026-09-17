package com.hikari.app.ui.screens
import com.hikari.app.i18n.tr
import com.hikari.app.i18n.I18n

import android.app.Application
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.graphics.Brush
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
import com.hikari.app.data.Collection
import com.hikari.app.data.ContentRepository
import androidx.compose.ui.text.style.TextOverflow
import com.hikari.app.data.MediaItem
import com.hikari.app.data.ProviderType
import com.hikari.app.ui.PosterLoader
import com.hikari.app.ui.components.ContinueWatchingRow
import com.hikari.app.ui.components.EmptyState
import com.hikari.app.ui.components.GlassDialog
import com.hikari.app.ui.components.GlassSearchField
import com.hikari.app.ui.components.HeroBanner
import com.hikari.app.ui.components.MediaRow
import com.hikari.app.ui.components.ShimmerRow
import com.hikari.app.ui.theme.rememberGlassTokens
import com.hikari.app.ui.navigation.Routes
import com.hikari.app.providers.ContentProvider
import com.hikari.app.web.WebViewActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A Home pick is stored as one string in the `homeProvider` preference: either
 * an extension's id, or — with this prefix — the id of a saved collection. One
 * preference (and one picker) therefore carries both kinds of choice.
 */
private const val COLLECTION_PREFIX = "collection:"

class HomeViewModel(app: Application) : AndroidViewModel(app) {    private val manager = (app as HikariApp).providers
    private val store = (app as HikariApp).store
    private val repo = ContentRepository(manager)
    private val collections = com.hikari.app.data.CollectionsRepository(manager)

    private val _rows = MutableStateFlow<List<CatalogRow>>(emptyList())
    val rows: StateFlow<List<CatalogRow>> = _rows.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _selectedProvider = MutableStateFlow<String?>(null)
    val selectedProvider: StateFlow<String?> = _selectedProvider.asStateFlow()

    val providers: StateFlow<List<ContentProvider>> = manager.providers

    private var loadJob: kotlinx.coroutines.Job? = null

    /** The collection the current feed was built from — lets the collections
     *  store (edited in Settings) invalidate exactly the affected feed. */
    private var lastLoadedCollection: Collection? = null

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
            // Restore the user's last pick ("All" when never picked). A pick can
            // be an installed extension OR a collection ("collection:<id>") —
            // the same stored preference carries both.
            _selectedProvider.value = store.homeProvider().ifBlank { null }
            loadInternal()
        }
        viewModelScope.launch {
            manager.providers.collect { ps ->
                val sel = _selectedProvider.value
                // Only an EXTENSION pick can be invalidated by the installed
                // list changing; a collection pick is resolved against the
                // collections store instead (see loadInternal).
                if (sel != null && !isCollectionKey(sel) &&
                    ps.none { it.config.enabled && it.config.id == sel }
                ) {
                    _selectedProvider.value = null
                    store.setHomeProvider("")
                }
                loadInternal()
            }
        }
        viewModelScope.launch {
            // Collections are edited in Settings; re-picking the same one from
            // the picker would otherwise show the OLD folders from the cache.
            // Watching the store means an edit (or a delete) lands on Home by
            // itself.
            store.collectionsFlow().collect { list ->
                val sel = _selectedProvider.value
                if (sel == null || !isCollectionKey(sel)) return@collect
                val current = list.firstOrNull { it.id == collectionIdOf(sel) }
                if (current != lastLoadedCollection) loadInternal()
            }
        }
    }

    /** True when a stored Home pick refers to a collection, not an extension. */
    private fun isCollectionKey(key: String): Boolean = key.startsWith(COLLECTION_PREFIX)

    private fun collectionIdOf(key: String): String = key.removePrefix(COLLECTION_PREFIX)

    fun selectProvider(id: String?) {
        if (_selectedProvider.value == id) return
        _selectedProvider.value = id
        viewModelScope.launch { store.setHomeProvider(id ?: "") }
        viewModelScope.launch { loadInternal() }
    }

    private suspend fun loadInternal() {
        loadJob?.cancel()
        val pick = _selectedProvider.value
        // A collection pick resolves to a saved collection; when it has been
        // deleted (or its id is stale) fall back to All instead of leaving the
        // user on an empty screen.
        var collection: Collection? = null
        if (pick != null && isCollectionKey(pick)) {
            collection = runCatching { store.collection(collectionIdOf(pick)) }.getOrNull()
            if (collection == null) {
                _selectedProvider.value = null
                viewModelScope.launch { store.setHomeProvider("") }
            }
        }
        val pickedCollection = collection
        val key = if (pickedCollection != null) COLLECTION_PREFIX + pickedCollection.id
        else (_selectedProvider.value ?: "all")
        lastLoadedCollection = pickedCollection
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
            when {
                pickedCollection != null -> "Loading " + pickedCollection.name
                key == "all" -> "Loading Home catalogs"
                else -> "Loading " + (manager.byId(key)?.config?.name ?: "catalog")
            }
        )
        val loadedCollection = pickedCollection
        loadJob = viewModelScope.launch {
            // Row key -> poster-tokenized copy, so a partial update only
            // tokenizes the rows that just arrived. MRDS/51CG catalogs carry
            // full-size base64 data: posters; the Home feed keeps hundreds alive
            // at once and OOMs on a stock heap, so each is collapsed into a tiny
            // disk-cache token ([PosterLoader.model] resolves it back to bytes).
            val tokenCache = HashMap<String, CatalogRow>()
            var latest: List<CatalogRow> = emptyList()
            val rowFlow = if (loadedCollection != null) {
                // A collection pick: one shelf per CATALOG when the collection
                // has a single folder (the user grouped sources, not shelves),
                // one shelf per folder when it has several. See
                // [CollectionsRepository.pickRows].
                collections.pickRows(loadedCollection)
            } else {
                repo.homeRowsStreaming(_selectedProvider.value)
            }
            rowFlow.collect { rows ->
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
    // The picker's engine filter ("All", "CloudStream", "Hikari", "Nuvio",
    // "Stremio"). Purely a narrowing device: it never changes what Home shows.
    var providerFilter by remember { mutableStateOf<com.hikari.app.data.ProviderType?>(null) }
    // Shown only if the last crash hasn't been announced yet (see
    // HikariApp.markCrashNoticeShown): a crash that was already reported never
    // interrupts the user twice.
    var showCrash by remember {
        mutableStateOf(HikariApp.lastCrash != null && !HikariApp.crashNoticeShown)
    }
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
    // User-made collections: offered in the same picker as the extensions, and
    // a collection pick ("collection:<id>") swaps the feed for that
    // collection's folders.
    val collectionsFlow = remember { app.store.collectionsFlow() }
    val collections by collectionsFlow.collectAsState(initial = emptyList())
    val selectedCollection = collections.firstOrNull { selected == "$COLLECTION_PREFIX${it.id}" }
    // The picker's label for the current pick: the extension's name, the
    // collection's name, or nothing (All).
    val selectedName = providers.firstOrNull { it.config.id == selected }?.config?.name
        ?: selectedCollection?.name
    // The header's per-extension actions (translate, Cloudflare verify) and the
    // "search inside this extension?" prompt only make sense for an extension,
    // so a collection pick leaves the header in its plain "All" shape.
    val headerSelection = if (selectedCollection != null) null else selected
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
        if (headerSelection != null) showSearchDialog = true else openGlobalSearch()
    }
    val openVerify: () -> Unit = {
        scope.launch {
            // The globe sits in the SELECTED extension's header, so it opens the
            // SELECTED extension's own site. It used to prefer the most recently
            // challenged host in the whole app, which meant picking one
            // extension and landing on an unrelated site (and, with a stale
            // record around, on the same wrong site every time). The challenged
            // host is the fallback, for the case where the selected extension
            // declares no site of its own.
            val own = withContext(Dispatchers.IO) {
                providers.firstOrNull { it.config.id == selected }?.let { webUrlFor(it) }
            }
            val blocked = com.hikari.app.net.CloudflareVerifier.blockedHost()
            val url = own ?: blocked?.let { "https://$it/" }
            val host = own?.let { runCatching { java.net.URI(it).host?.lowercase() }.getOrNull() }
                ?: blocked
            if (url != null) {
                verifyLauncher.launch(
                    Intent(context, WebViewActivity::class.java).apply {
                        putExtra("url", url)
                        putExtra("title", "Verify: " + (host ?: selectedName ?: "site"))
                        putExtra("providerId", selected)
                        putExtra("autoCloseWhenCloudflarePassed", true)
                        if (host != null) putExtra("verifyHost", host)
                    }
                )
            } else {
                Toast.makeText(
                    context,
                    I18n.t("Couldn't determine this extension's site"),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // Settings is reachable through the bottom bar unless the user switched that
    // button off (Settings → Taskbar buttons); when it is off, Home's top bar
    // carries a gear instead so the screen can never become unreachable.
    val hiddenTabs by remember { app.store.hiddenTabsFlow() }.collectAsState(initial = emptySet())
    val openSettings: (() -> Unit)? = if (Routes.SETTINGS in hiddenTabs) {
        { Routes.safeNavigate(nav, Routes.SETTINGS) }
    } else {
        null
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 72.dp)
        ) {
            // Crash report: NOT inline any more. A stack trace dumped into the
            // feed made the feed look broken; the one-shot warning panel below
            // (see the GlassDialog after the Box) says what happened and points
            // at Settings → Logs, then stays out of the way.
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
                            selected = headerSelection,
                            onSearch = openSearch,
                            onTranslate = { showTranslate = true },
                            onVerify = openVerify,
                            overlay = true,
                            onSettings = openSettings,
                            modifier = Modifier.align(Alignment.TopCenter),
                        )
                    }
                } else {
                    HomeHeader(
                        selected = headerSelection,
                        onSearch = openSearch,
                        onTranslate = { showTranslate = true },
                        onVerify = openVerify,
                        overlay = false,
                        onSettings = openSettings,
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
                            // "Show all" while browsing a collection shows the
                            // WHOLE collection: every folder and every catalog
                            // in it, as one scrollable grid (a folder row used
                            // to open just that folder, which left the user
                            // unable to see the rest of the collection).
                            val collection = selectedCollection
                            if (collection != null) {
                                Routes.safeNavigate(nav, Routes.collectionGrid(collection.id))
                            } else {
                                Routes.safeNavigate(
                                    nav,
                                    Routes.catalog(
                                        row.providerId, row.catalogId, row.title,
                                        row.providerName, row.type, row.rawType
                                    )
                                )
                            }
                        }
                    )
                }
            }
            if (rows.isEmpty() && !loading) {
                item {
                    val collection = selectedCollection
                    if (collection != null) {
                        val noFolders = collection.folders.isEmpty()
                        EmptyState(
                            title = if (noFolders) tr("This collection has no folders")
                            else tr("Nothing loaded from this collection"),
                            subtitle = if (noFolders) {
                                tr("Add a folder in Settings → Appearance → Collections.")
                            } else {
                                tr(
                                    "Its folders came back empty. Check the extension " +
                                        "sites, or add another catalog to a folder."
                                )
                            },
                            actionLabel = tr("Collections"),
                            action = { Routes.safeNavigate(nav, Routes.COLLECTIONS) },
                        )
                    } else if (selected != null) {
                        val reason =
                            com.hikari.app.cs3.Cs3MainApiProvider.catalogErrors[selected]
                                ?: com.hikari.app.providers.StremioAddon.catalogErrors[selected]
                                ?: com.hikari.app.nuvio.NuvioScraper.catalogErrors[selected]
                                ?: com.hikari.app.skystream.SkyStreamProvider.catalogErrors[selected]
                        // A verification wall is the one failure worth naming on
                        // screen: the user picked this provider, so this is where
                        // telling them what to do actually helps. Everywhere else
                        // (per-extension diagnostics, the server chooser, the
                        // player) the raw extension wording is dropped — see
                        // CloudflareVerifier.isVerificationMessage.
                        val needsVerify = isVerificationWall(
                            reason, providers.firstOrNull { it.config.id == selected }
                        )
                        val streamOnly =
                            com.hikari.app.providers.StremioAddon.streamOnlyAddons[selected] == true
                        if (streamOnly) {
                            EmptyState(
                                title = I18n.t("No catalog from %s").replace("%s", selectedName ?: "this addon"),
                                subtitle = tr("This addon doesn't provide a catalog to browse — it only " + "adds playback sources to titles opened from other addons. ") +
                                    "Pick any movie or series and its streams will show up.",
                                actionLabel = tr("Browse all"),
                                action = { vm.selectProvider(null) }
                            )
                        } else {
                            EmptyState(
                                title = if (needsVerify) tr("Verification needed")
                                    else "Couldn't load ${selectedName ?: "this extension"}",
                                subtitle = if (needsVerify) tr(VERIFY_NEEDED_HELP)
                                    else reason
                                        ?: "Nothing came back from this extension. Retry, or open its " +
                                            "site in the WebView to check whether it is up — otherwise " +
                                            "browse another extension.",
                                actionLabel = if (needsVerify) tr("Open WebView") else tr("Retry"),
                                action = if (needsVerify) openVerify else vm::refresh
                            )
                        }
                    } else {
                        EmptyState(
                            title = tr("No content yet"),
                            subtitle = tr("Add a Stremio addon or a universal scraper to start watching."),
                            actionLabel = tr("Add extensions"),
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
                Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.List,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    "  " + (selectedName ?: tr("All providers")),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // Crash warning: one glass panel, shown once per crash. It says only what
        // the user needs (it crashed, the log is saved, share it if it keeps
        // happening) — the stack trace itself lives in Settings → Logs. It closes
        // by itself after a few seconds, on a tap anywhere, or on the X, and
        // either way this crash is never announced again.
        if (showCrash) {
            val dismissCrash: () -> Unit = {
                showCrash = false
                // Keeps the log file (unlike clearCrash) and remembers the
                // fingerprint, so the panel doesn't come back on the next launch.
                HikariApp.instance.markCrashNoticeShown()
            }
            LaunchedEffect(Unit) {
                delay(5000)
                dismissCrash()
            }
            GlassDialog(
                onDismiss = dismissCrash,
                title = tr("Hikari crashed last time"),
            ) {
                Text(
                    tr(
                        "The crash log has been saved. You can view it in Settings, " +
                            "and share it with the developer if the issue continues."
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(onClick = { dismissCrash() }) { Text(tr("OK")) }
                }
            }
        }
    }

    if (showPicker) {
        ProviderPickerSheet(
            providers = activeProviders,
            collections = collections,
            selectedId = selected,
            filter = providerFilter,
            onFilter = { providerFilter = it },
            onManageCollections = {
                showPicker = false
                Routes.safeNavigate(nav, Routes.COLLECTIONS)
            },
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
            title = { Text(if (isOn) tr("Turn off translation?") else tr("Translate to English?")) },
            text = {
                Text(
                    if (isOn) {
                        tr("Translation is ON for %s — its titles and text are shown in English.")
                            .replace("%s", pname)
                    } else {
                        tr("%s shows content in its original language. Turn it into English? " +
                            "Only this extension is affected — every other extension stays as it is.")
                            .replace("%s", pname)
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
                    Text(if (isOn) tr("Turn off") else tr("Always translate"))
                }
            },
            dismissButton = {
                TextButton(onClick = { showTranslate = false }) { Text(tr("Cancel")) }
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
            title = { Text(tr("Search")) },
            text = { Text(tr("Search across every provider, or only inside %s?").replace("%s", pname)) },
            confirmButton = {
                TextButton(onClick = {
                    showSearchDialog = false
                    openGlobalSearch()
                }) {
                    Text(tr("Global search"))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showSearchDialog = false
                    Routes.safeNavigate(nav, Routes.searchInProvider(searchSel))
                }) {
                    Text(tr("In %s").replace("%s", pname))
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderPickerSheet(
    providers: List<ContentProvider>,
    collections: List<Collection>,
    selectedId: String?,
    filter: ProviderType?,
    onFilter: (ProviderType?) -> Unit,
    onManageCollections: () -> Unit,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    // Engine filter: every kind that has at least one installed extension, in a
    // stable order, so a user with dozens of installs can narrow the list to
    // just their CloudStream plugins, just their Nuvio providers, and so on.
    val kinds = remember(providers) {
        providers.map { it.config.type }.distinct().sortedBy { it.groupLabel }
    }
    // Alphabetical (by extension name), so the picker isn't "install order".
    val filtered = remember(providers, query, filter) {
        val narrowed = providers.filter { filter == null || it.config.type == filter }
        val sorted = narrowed.sortedBy { it.config.name.lowercase() }
        if (query.isBlank()) sorted
        else sorted.filter { it.config.name.contains(query, ignoreCase = true) }
    }
    val shownCollections = remember(collections, query) {
        if (query.isBlank()) collections
        else collections.filter { it.name.contains(query, ignoreCase = true) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(
                tr("Choose an extension"),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                tr("Only the selected extension's catalog is shown on Home."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
            )
            GlassSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = tr("Search extensions…"),
                modifier = Modifier.fillMaxWidth(),
            )
            // Categories: All first, then one chip per engine that is actually
            // installed. Picking one only NARROWS the list below.
            if (kinds.isNotEmpty()) {
                LazyRow(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        FilterChipLine(
                            label = tr("All"),
                            selected = filter == null,
                            onClick = { onFilter(null) },
                        )
                    }
                    items(kinds, key = { it.name }) { kind ->
                        FilterChipLine(
                            label = kind.groupLabel,
                            selected = filter == kind,
                            onClick = { onFilter(if (filter == kind) null else kind) },
                        )
                    }
                }
            }
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 24.dp),
            ) {
                if (shownCollections.isNotEmpty()) {
                    item {
                        Text(
                            tr("Collections").uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 2.dp, bottom = 4.dp)
                        )
                    }
                    items(shownCollections, key = { "collection|${it.id}" }) { c ->
                        PickerRow(
                            label = c.name,
                            isSelected = selectedId == "$COLLECTION_PREFIX${c.id}",
                            supporting = if (c.folders.isEmpty()) tr("No folders yet")
                            else c.folders.joinToString(" · ") { it.name },
                        ) {
                            onPick("$COLLECTION_PREFIX${c.id}")
                        }
                    }
                    item {
                        PickerRow(
                            label = tr("Manage collections"),
                            isSelected = false,
                            onClick = onManageCollections,
                        )
                    }
                    item { HorizontalDivider(Modifier.padding(vertical = 6.dp)) }
                }
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
                            I18n.t("No extension matches \"$query\""),
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

/** One engine chip in the picker ("All", "CloudStream", "Nuvio", …) — a glass
 *  pill, so the filter row reads as part of the same roundy material as the
 *  rows below it instead of flat grey blocks. */
@Composable
private fun FilterChipLine(label: String, selected: Boolean, onClick: () -> Unit) {
    val glass = rememberGlassTokens()
    val shape = RoundedCornerShape(50)
    Surface(
        onClick = onClick,
        shape = shape,
        color = Color.Transparent,
        modifier = Modifier
            .padding(vertical = 2.dp)
            .clip(shape)
            .background(
                if (selected) {
                    Brush.horizontalGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = if (glass.dark) 0.34f else 0.20f),
                            MaterialTheme.colorScheme.primary.copy(alpha = if (glass.dark) 0.20f else 0.12f),
                        )
                    )
                } else {
                    Brush.verticalGradient(listOf(glass.fillTop, glass.fillBottom))
                },
                shape,
            )
            .border(1.dp, if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f) else glass.border, shape),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
}

/**
 * One row of the extension picker. Glassy by design: the same translucent
 * top-to-bottom fill and 1px hairline the settings cards use (see
 * [rememberGlassTokens]), with a 14dp radius and a small vertical gap between
 * rows — so the list reads as separate round cards floating over the sheet
 * rather than a wall of flat charcoal rows.
 */
@Composable
private fun PickerRow(
    label: String,
    isSelected: Boolean,
    supporting: String? = null,
    onClick: () -> Unit,
) {
    val glass = rememberGlassTokens()
    val shape = RoundedCornerShape(14.dp)
    Surface(
        onClick = onClick,
        shape = shape,
        color = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(shape)
            .background(
                if (isSelected) {
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = if (glass.dark) 0.28f else 0.16f),
                            MaterialTheme.colorScheme.primary.copy(alpha = if (glass.dark) 0.16f else 0.09f),
                        )
                    )
                } else {
                    Brush.verticalGradient(listOf(glass.fillTop, glass.fillBottom))
                },
                shape,
            )
            .border(1.dp, if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f) else glass.border, shape),
    ) {
        Row(
            Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!supporting.isNullOrBlank()) {
                    Text(
                        supporting,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
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
    // A SkyStream extension's `url` is the LOCAL plugin.js path, so the site it
    // reads lives in its plugin.json (`domains[0]`, else `baseUrl`). Without
    // this the globe button had no target at all for these extensions.
    ProviderType.SKYSTREAM ->
        com.hikari.app.skystream.SkyStreamPluginManager.siteUrlOf(p.config)
    ProviderType.CS3 -> runCatching {
        val file = java.io.File(p.config.url)
        if (!file.exists()) return@runCatching null
        val apis = com.hikari.app.cs3.Cs3PluginManager.apisFor(com.hikari.app.HikariApp.instance, file)
        apis.getOrNull(p.config.id.substringAfterLast("|").toIntOrNull() ?: 0)?.mainUrl
            ?.takeIf { it.startsWith("http") }
    }.getOrNull()
    else -> null
}

/** What Hikari says when a provider's own error is a Cloudflare / "verify you
 *  are human" wall. Shown ONLY here on Home (and in the extensions list), and
 *  deliberately in Hikari's words rather than the extension's: the raw text
 *  ("Cloudflare blocked. Go to Settings 'n Bypass Cloudflare.") is a message
 *  about a different app's settings screen and means nothing here. */
private const val VERIFY_NEEDED_HELP =
    "This provider's site is behind a Cloudflare human-verification page, so it can't be loaded " +
        "directly. Tap the WebView (globe) icon and complete the verification — the catalog " +
        "reloads by itself once you're through."

/** True when a provider's catalog failed because of a Cloudflare verification
 *  wall: either the extension said so in its own words, or the request came
 *  back challenged for the very host this provider's site lives on. */
private fun isVerificationWall(raw: String?, provider: ContentProvider?): Boolean {
    if (com.hikari.app.net.CloudflareVerifier.isVerificationMessage(raw)) return true
    val host = provider?.let { webUrlFor(it) }
        ?.let { runCatching { java.net.URI(it).host?.lowercase() }.getOrNull() }
        ?: return false
    return com.hikari.app.net.CloudflareVerifier.isBlockedHost(host)
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
    /** Non-null only while the Settings tab is switched off in the bottom bar
     *  (Settings → Taskbar buttons): the bar then has no way into Settings, so
     *  this gear keeps the screen reachable instead of locking the user out. */
    onSettings: (() -> Unit)? = null,
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
                tr("Hikari"),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = accent,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            Text(
                tr("Every stream, one place."),
                style = MaterialTheme.typography.bodyMedium,
                color = subtitleColor,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }
        IconButton(onClick = onSearch) {
            Icon(Icons.Filled.Search, contentDescription = tr("Search"), tint = iconTint)
        }
        // Translate: per-extension toggle — turns this extension's titles/text
        // into English inside the app. Shown whenever a provider is selected.
        selected?.let { pid ->
            val translateOn = com.hikari.app.data.Translator.isOn(pid)
            IconButton(onClick = onTranslate) {
                Text(
                    tr("A\u3042"),
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
        // WebView: opens this extension's own site in a WebView — for reading
        // it directly, and for passing a WAF check once if it does present one.
        if (selected != null) {
            IconButton(onClick = onVerify) {
                Icon(
                    Icons.Filled.Public,
                    contentDescription = tr("Open this extension's site in a web view"),
                    tint = iconTint
                )
            }
        }
        // Only shown while the Settings tab is hidden from the bottom bar — see
        // the parameter comment.
        onSettings?.let { open ->
            IconButton(onClick = open) {
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = tr("Settings"),
                    tint = iconTint
                )
            }
        }
    }
}
