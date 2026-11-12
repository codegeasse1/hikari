package com.hikari.app.ui.screens

import com.hikari.app.ui.components.LocalHideHelp
import com.hikari.app.ui.components.heroPoster
import com.hikari.app.i18n.tr
import com.hikari.app.i18n.I18n

import android.app.Application
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.hikari.app.HikariApp
import com.hikari.app.data.CatalogRow
import com.hikari.app.data.CatalogRef
import com.hikari.app.data.Collection
import com.hikari.app.data.CollectionFolder
import com.hikari.app.data.ContentRepository
import com.hikari.app.data.CoverKinds
import androidx.compose.ui.text.style.TextOverflow
import com.hikari.app.data.MediaItem
import com.hikari.app.data.MediaType
import com.hikari.app.data.TrackerKind
import com.hikari.app.data.TrackerLibraryRepository
import com.hikari.app.data.ProviderType
import com.hikari.app.data.ProviderFolder
import com.hikari.app.data.RepoProvenance
import com.hikari.app.data.Site
import com.hikari.app.data.TmdbGenres
import com.hikari.app.data.TmdbSourceType
import com.hikari.app.data.TmdbSpec
import com.hikari.app.data.WatchProgress
import com.hikari.app.data.Profiles
import com.hikari.app.tv.TvCinematicHero
import com.hikari.app.tv.TvHomeBackdrop
import com.hikari.app.tv.TvHomeHeaderBar
import com.hikari.app.tv.TvManageFeedDialog
import com.hikari.app.tv.TvMode
import com.hikari.app.tv.TvProfileMenuDialog
import com.hikari.app.tv.TvProviderCard
import com.hikari.app.tv.TvProviderIcon
import com.hikari.app.tv.TvUi
import com.hikari.app.ui.Artwork
import com.hikari.app.ui.SourceLangs
import com.hikari.app.ui.FlagIcon
import com.hikari.app.ui.PosterLoader
import com.hikari.app.ui.ProviderFlags
import com.hikari.app.ui.ProviderPacks
import com.hikari.app.ui.rememberVisibleItems
import com.hikari.app.ui.components.ContinueWatchingRow
import com.hikari.app.ui.components.EmptyState
import com.hikari.app.ui.components.GlassDialog
import com.hikari.app.ui.components.GlassSearchField
import com.hikari.app.ui.components.HeroBanner
import com.hikari.app.ui.components.HeroConfig
import com.hikari.app.ui.components.HeroStyles
import com.hikari.app.ui.components.AutoScrollPosterRow
import com.hikari.app.ui.components.MediaRow
import com.hikari.app.ui.components.ShimmerRow
import com.hikari.app.ui.theme.pageBackground
import com.hikari.app.ui.theme.rememberGlassTokens
import com.hikari.app.ui.navigation.LocalTaskbarInset
import com.hikari.app.ui.navigation.Routes
import com.hikari.app.providers.ContentProvider
import com.hikari.app.web.WebViewActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.hikari.app.tv.tvPress
import com.hikari.app.tv.tvTextFieldKeys

private const val COLLECTION_PREFIX = Routes.COLLECTION_PROVIDER_PREFIX
private const val TRACKER_PREFIX = "tracker:"

class HomeViewModel(app: Application) : AndroidViewModel(app) {    private val manager = (app as HikariApp).providers
    private val store = (app as HikariApp).store
    private val repo = ContentRepository(manager)
    private val collections = com.hikari.app.data.CollectionsRepository(manager)

    private val _rows = MutableStateFlow<List<CatalogRow>>(emptyList())
    val rows: StateFlow<List<CatalogRow>> = _rows.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _selection = MutableStateFlow<List<String>>(emptyList())
    val selection: StateFlow<List<String>> = _selection.asStateFlow()

    /** The single pick, or null while the user is on All or on a multi pick —
     *  what the source pill's label and the header actions act on. */
    private val _selectedProvider = MutableStateFlow<String?>(null)
    val selectedProvider: StateFlow<String?> = _selectedProvider.asStateFlow()

    val providers: StateFlow<List<ContentProvider>> = manager.providers

    /** Sets both views of the same pick from one place, so they can never
     *  disagree about what Home is showing. */
    private fun applySelection(keys: List<String>) {
        val clean = keys.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        _selection.value = clean
        _selectedProvider.value = clean.singleOrNull()
    }

    private var loadJob: kotlinx.coroutines.Job? = null

    /** The collection the current feed was built from — lets the collections
     *  store (edited in Settings) invalidate exactly the affected feed. */
    private var lastLoadedCollection: Collection? = null

    /** The collections the CURRENT selection resolves to, as the store last
     *  reported them. Any difference (a pick, an unpick, an edit, a delete)
     *  rebuilds the feed (see the collectionsFlow watcher). */
    private var watchedCollections: List<Collection> = emptyList()

    private val homeCache get() = HomeFeedCache.rows

    private var loadToken = 0

    private val restored = kotlinx.coroutines.CompletableDeferred<Unit>()

    init {
        viewModelScope.launch {
            // Restore the user's last pick ("All" when never picked). A pick can
            // be an installed extension OR a collection ("collection:<id>") —
            // the same stored string carries both — and there may be several of
            // them (a multi pick lives in its own preference; the single one is
            // the fallback for every install that predates multi-select).
            // A store read that fails must not leave the watchers below waiting
            // forever: the selection simply stays at its default ("All").
            val multi = runCatching { store.homeProviders().toList() }.getOrDefault(emptyList())
            val single = runCatching { store.homeProvider() }.getOrDefault("")
            applySelection(
                if (multi.isNotEmpty()) multi else listOfNotNull(single.ifBlank { null })
            )
            // From here on the selection is real, so the watchers below may act.
            restored.complete(Unit)
            loadInternal()
        }
        viewModelScope.launch {
            restored.await()
            manager.providers.collect { ps ->
                val sel = _selection.value
                if (ps.isNotEmpty()) {
                    // Only EXTENSION picks can be invalidated by the installed
                    // list changing; a collection pick is resolved against the
                    // collections store instead (see loadInternal). One extension
                    // being uninstalled drops just that pick, so the rest of a
                    // multi pick (and the user's other choices) survive it.
                    val valid = sel.filter { key ->
                        isCollectionKey(key) || ps.any { it.config.enabled && it.config.id == key }
                    }
                    val validKept = sel.filter { key ->
                        isCollectionKey(key) || isTrackerKey(key) || ps.any { it.config.id == key }
                    }
                    if (validKept != sel) {
                        com.hikari.app.data.Logs.log(
                            "Home",
                            "dropping pick(s) no longer installed: " +
                                (sel - validKept.toSet()).joinToString(","),
                        )
                        applySelection(validKept)
                        viewModelScope.launch { store.setHomeProviders(validKept.toSet()) }
                    }
                }
                loadInternal()
            }
        }
        viewModelScope.launch {
            restored.await()
            // Collections are edited in Settings; re-picking the same one from
            // the picker would otherwise show the OLD folders from the cache.
            // Watching the store means an edit (or a delete) lands on Home by
            // itself.
            store.collectionsFlow().collect { list ->
                val sel = _selection.value
                val picked = sel.filter { isCollectionKey(it) }
                    .mapNotNull { key -> list.firstOrNull { it.id == collectionIdOf(key) } }
                if (picked != watchedCollections) {
                    watchedCollections = picked
                    loadInternal()
                }
            }
        }
        viewModelScope.launch {
            val app = getApplication<Application>() as HikariApp
            app.contentLanguageRevision.drop(1).collect {
                homeCache.clear()
                loadInternal(forceRefresh = true)
            }
        }
        viewModelScope.launch {
            val app = getApplication<Application>() as HikariApp
            app.store.nsfwEnabledFlow().drop(1).collect {
                homeCache.clear()
                loadInternal(forceRefresh = true)
            }
        }
        viewModelScope.launch {
            restored.await()
            store.homeProvidersFlow().collect { multi ->
                val single = runCatching { store.homeProvider() }.getOrDefault("")
                val want = if (multi.isNotEmpty()) multi.toList()
                else listOfNotNull(single.ifBlank { null })
                if (want != _selection.value) {
                    applySelection(want)
                    loadInternal()
                }
            }
        }
    }

    /** True when a stored Home pick refers to a collection, not an extension. */
    private fun isCollectionKey(key: String): Boolean = key.startsWith(COLLECTION_PREFIX)
    private fun isTrackerKey(key: String): Boolean = key.startsWith(TRACKER_PREFIX)
    private fun trackerKindOf(key: String): TrackerKind? = TrackerKind.of(key.removePrefix(TRACKER_PREFIX))
    private fun collectionIdOf(key: String): String = key.removePrefix(COLLECTION_PREFIX)

    fun selectProvider(id: String?) {
        val want = if (id == null) emptyList() else listOf(id)
        if (want == _selection.value) refreshFeed()
        else setSelection(want)
    }

    fun setSelection(ids: List<String>): Boolean {
        val clean = ids.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (clean == _selection.value) return false
        // One line per applied pick: the next "it shows the old catalogue"
        // report can be told apart from a tap that never arrived.
        com.hikari.app.data.Logs.log(
            "Home",
            "pick=" + (if (clean.isEmpty()) "all" else clean.joinToString(",")),
        )
        applySelection(clean)
        val key = if (clean.isEmpty()) "all" else clean.joinToString(",")
        val cached = homeCache[key]
        if (cached != null) {
            _rows.value = cached
            _loading.value = false
        } else {
            _rows.value = emptyList()
            _loading.value = true
        }
        viewModelScope.launch { store.setHomeProviders(clean.toSet()) }
        viewModelScope.launch { loadInternal() }
        return true
    }

    fun refreshFeed() {
        viewModelScope.launch { loadInternal(forceRefresh = true) }
    }

    private fun rowsFlowFor(
        picks: List<String>,
        saved: List<Collection>,
    ): kotlinx.coroutines.flow.Flow<List<CatalogRow>> {
        val trackerKeys = picks.filter { isTrackerKey(it) }
        val extensionIds = picks.filterNot { isCollectionKey(it) || isTrackerKey(it) }.toSet()
        suspend fun trackerRows(): List<CatalogRow> {
            val app = getApplication<Application>() as HikariApp
            return trackerKeys.flatMap { key ->
                val kind = trackerKindOf(key) ?: return@flatMap emptyList()
                TrackerLibraryRepository.load(app, kind.key).onFailure { e ->
                    com.hikari.app.data.Logs.log("Home", "tracker pick " + key + ": " + (e.message ?: "failed"))
                }.getOrDefault(emptyList()).map { shelf ->
                    CatalogRow(providerId = TRACKER_PREFIX + kind.key, providerName = kind.label, title = shelf.title, items = shelf.items, key = TRACKER_PREFIX + shelf.key, catalogId = shelf.key, type = shelf.items.firstOrNull()?.type ?: MediaType.UNKNOWN, rawType = shelf.items.firstOrNull()?.rawType ?: "anime")
                }
            }
        }
        val trackerFlow = kotlinx.coroutines.flow.flow { emit(trackerRows()) }
        // A manga pick shows that engine's own catalogue (its Popular/Latest
        // rows) instead of an empty "Couldn't load" — see [homeRowsStreamingFor].
        val allowManga = manager.providers.value.any {
            it.config.id in extensionIds && it.config.type == ProviderType.MANGA
        }
        return when {
            // No pick means "All providers" — keep the normal extension feed.
            picks.isEmpty() -> repo.homeRowsStreaming()
            trackerKeys.isNotEmpty() && extensionIds.isNotEmpty() ->
                kotlinx.coroutines.flow.merge(
                    trackerFlow,
                    repo.homeRowsStreamingFor(extensionIds, allowManga),
                )
            trackerKeys.isNotEmpty() -> trackerFlow
            extensionIds.isNotEmpty() -> repo.homeRowsStreamingFor(extensionIds, allowManga)
            else -> kotlinx.coroutines.flow.flowOf(emptyList())
        }
    }

    private suspend fun loadInternal(forceRefresh: Boolean = false) {
        loadJob?.cancel()
        val token = ++loadToken
        if (manager.providers.value.isEmpty()) {
            val ready = kotlinx.coroutines.withTimeoutOrNull(20_000L) {
                manager.providers.first { it.isNotEmpty() }
            }
            if (ready == null) {
                com.hikari.app.data.Logs.log("Home", "providers never became ready — loading anyway")
            }
            if (token != loadToken) return
        }
        val picks = _selection.value
        // A collection pick resolves to a saved collection; when it has been
        // deleted (or its id is stale) fall back to All instead of leaving the
        // user on an empty screen.
        val known = runCatching { store.collections() }.getOrDefault(emptyList())
        if (token != loadToken) {
            com.hikari.app.data.Logs.log(
                "Home",
                "load superseded before it started (pick=" +
                    (if (picks.isEmpty()) "all" else picks.joinToString(",")) + ")",
            )
            return
        }
        val kept = picks.filter { key ->
            !isCollectionKey(key) || known.any { it.id == collectionIdOf(key) }
        }
        if (kept != picks) {
            applySelection(kept)
            viewModelScope.launch { store.setHomeProviders(kept.toSet()) }
        }
        // A collection picked ON ITS OWN: Home draws its folder tiles (below),
        // not a feed, so no rows are loaded at all.
        val folderCollection = kept.singleOrNull()
            ?.takeIf { isCollectionKey(it) }
            ?.let { key -> known.firstOrNull { it.id == collectionIdOf(key) } }
        val key = if (kept.isEmpty()) "all" else kept.joinToString(",")
        // The ONE extension this pick is about, when the pick is a single
        // extension — [rows] below is then held to that extension's rows only.
        val soloPick = kept.singleOrNull()?.takeIf { !isCollectionKey(it) && !isTrackerKey(it) }
        lastLoadedCollection = folderCollection
        val cached = homeCache[key]
        if (folderCollection != null) {
            // Folders are already in memory (the collections store), so the
            // folder strip paints on the first frame; there is nothing to fetch.
            _rows.value = emptyList()
            _loading.value = false
            return
        }
        if (cached != null && !forceRefresh) {
            // Stale-while-revalidate: show the previous feed immediately (no
            // spinner) and refresh underneath.
            _rows.value = cached
            _loading.value = false
        } else if (cached == null) {
            if (forceRefresh) {
                _loading.value = false
            } else {
                _loading.value = true
                _rows.value = emptyList()
            }
        }
        val work = com.hikari.app.work.BackgroundWork.begin(
            when {
                key == "all" -> "Loading Home catalogs"
                kept.size == 1 -> "Loading " + (manager.byId(kept[0])?.config?.name ?: "catalog")
                else -> "Loading " + kept.size + " sources"
            }
        ) { loadJob?.cancel() }
        loadJob = viewModelScope.launch {
            // Row key -> poster-tokenized copy, so a partial update only
            // tokenizes the rows that just arrived. MRDS/51CG catalogs carry
            // full-size base64 data: posters; the Home feed keeps hundreds alive
            // at once and OOMs on a stock heap, so each is collapsed into a tiny
            // disk-cache token ([PosterLoader.model] resolves it back to bytes).
            val tokenCache = HashMap<String, CatalogRow>()
            var latest: List<CatalogRow> = emptyList()
            val rowFlow = rowsFlowFor(kept, known)
            rowFlow.collect { incoming ->
                if (token != loadToken) return@collect
                val rows = if (soloPick == null) incoming else incoming.filter {
                    it.providerId == soloPick
                }
                if (rows.size != incoming.size) {
                    com.hikari.app.data.Logs.log(
                        "Home",
                        "pick=$soloPick dropped ${incoming.size - rows.size} row(s) that were not" +
                            " its own (from " +
                            incoming.map { it.providerName }.distinct().take(6).joinToString(",") +
                            ")",
                    )
                }
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
                if (cached == null) {
                    if (token != loadToken) return@collect
                    _rows.value = tokenized
                    _loading.value = false
                }
            }
            if (token != loadToken) {
                com.hikari.app.data.Logs.log(
                    "Home",
                    "pick=" + (if (key == "all") "all" else key) +
                        " superseded by a newer load — its rows were discarded",
                )
                return@launch
            }
            if (latest.isNotEmpty()) {
                homeCache[key] = latest
                _rows.value = latest
                _loading.value = false
                com.hikari.app.data.Logs.log(
                    "Home",
                    "pick=" + (if (key == "all") "all" else key) +
                        " rows=" + latest.size +
                        " from=" + latest.map { it.providerName }.distinct().take(8)
                            .joinToString(","),
                )
            } else {
                _rows.value = homeCache[key].orEmpty()
                _loading.value = false
                val why = if (key.startsWith(TRACKER_PREFIX)) {
                    TrackerLibraryRepository.errors[key]
                } else null
                com.hikari.app.data.Logs.log(
                    "Home",
                    "pick=" + (if (key == "all") "all" else key) + " produced no rows" +
                        (why?.let { " — $it" } ?: ""),
                )
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
    val selection by vm.selection.collectAsState()
    val providers by vm.providers.collectAsState()
    // Every ENABLED provider is offered here, including Stremio addons whose
    // manifest declares no catalogs of its own. Those used to be filtered out
    // ("like in Stremio, they don't appear here at all"), which meant an addon
    // the user had just installed was missing from the picker, could not be
    // found by name, and left the picker without a "Stremio" chip at all — the
    // reported "I installed HdHub but there is no Stremio category and
    // searching hdHub finds nothing". Such an addon browses TMDB (see
    // com.hikari.app.data.TmdbBrowse), so it has rows to show like any other
    // extension; if TMDB is unreachable it says so in its own empty state.
    val activeProviders = providers.filter { it.config.enabled }
    var providerFilter by remember { mutableStateOf<com.hikari.app.data.ProviderType?>(null) }
    var showCrash by remember {
        mutableStateOf(HikariApp.lastCrash != null && !HikariApp.crashNoticeShown)
    }
    var showPicker by remember { mutableStateOf(false) }
    var showTranslate by remember { mutableStateOf(false) }
    // Television: the CloudStream-desktop-style provider box and profile menu,
    // opened from the top-right pills (see [TvHomeTopBar]).
    var showTvManageFeed by remember { mutableStateOf(false) }
    var showTvProfileMenu by remember { mutableStateOf(false) }
    var showSearchDialog by remember { mutableStateOf(false) }
    var showHomeSearch by rememberSaveable { mutableStateOf(false) }
    // A genre tap made while extensions are picked: WHERE should it look —
    // those extensions alone, or everything (see the genre strip call site).
    var genreScope by remember { mutableStateOf<GenreScopePick?>(null) }
    // The genre an in-place overlay is narrowed to ("" = no narrowing).
    var overlayGenre by rememberSaveable { mutableStateOf("") }
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
    val collectionsFlow = remember { app.store.collectionsFlow() }
    val collections by collectionsFlow.collectAsState(initial = emptyList())
    // Television top-right pills: the profile list + active id for the profile
    // pill menu, and the favourites for the hero's bookmark toggle.
    // [Profiles.all] is a stable object-level flow (same as in ProfilesScreen),
    // so it needs no remember; the store flows below do (see historyFlow).
    val profiles by Profiles.all.collectAsState()
    val activeProfileId by Profiles.activeId.collectAsState()
    val favoritesFlow = remember { app.store.favoritesFlow() }
    val favorites by favoritesFlow.collectAsState(initial = emptyList())
    val favoriteIds = remember(favorites) { favorites.map { it.uniqueId }.toSet() }
    val reposFlow = remember { app.store.reposFlow() }
    val repos by reposFlow.collectAsState(initial = emptyList())
    val repoNameByProvider = remember(providers, repos) {
        RepoProvenance.nameMap(providers.map { it.config }, repos)
    }
    val selectedCollection = collections.firstOrNull { selected == "$COLLECTION_PREFIX${it.id}" }
    val pickedCollections = selection
        .filter { it.startsWith(COLLECTION_PREFIX) }
        .mapNotNull { key -> collections.firstOrNull { it.id == key.removePrefix(COLLECTION_PREFIX) } }
    val pinnedCollections = if (selection.isEmpty()) collections.filter { it.pinToTop } else emptyList()
    val collectionFolderRows = (pickedCollections + pinnedCollections)
        .distinctBy { it.id }
        // A folder-less collection has no tiles to draw inside its row; on a pick
        // the empty state below still explains it.
        .filter { it.folders.isNotEmpty() }
    // What the empty state talks about when nothing loaded: the single pick, or
    // the first picked collection that turned out to have no folders at all.
    val emptyTalkCollection = selectedCollection
        ?: pickedCollections.firstOrNull { it.folders.isEmpty() }
    // The picker's label for the current pick: the extension's name, the
    // collection's name, or nothing (All). Several picks are counted instead.
    val selectedName = when {
        selection.size > 1 -> I18n.t("%s sources").replace("%s", selection.size.toString())
        else -> providers.firstOrNull { it.config.id == selected }?.config?.name
            ?: selectedCollection?.name
            ?: selected?.takeIf { it.startsWith(TRACKER_PREFIX) }?.let { TrackerKind.of(it.removePrefix(TRACKER_PREFIX))?.label }
    }
    val headerSelection = if (selectedCollection != null || selected?.startsWith(TRACKER_PREFIX) == true) null else selected
    val historyFlow = remember { app.store.historyFlow() }
    val hideContinueFlow = remember { app.store.hideContinueFlow() }
    val history by historyFlow.collectAsState(initial = emptyList())
    // Settings → "Continue Watching": lets the user hide the shelf entirely.
    val hideContinue by hideContinueFlow.collectAsState(initial = false)
    val heroStyleFlow = remember { app.store.heroStyleFlow() }
    val heroOverviewFlow = remember { app.store.heroOverviewFlow() }
    val heroRatingFlow = remember { app.store.heroRatingFlow() }
    val heroMetaFlow = remember { app.store.heroMetaFlow() }
    val heroStyle by heroStyleFlow.collectAsState(initial = HeroStyles.CAROUSEL)
    val heroOverview by heroOverviewFlow.collectAsState(initial = true)
    val heroRating by heroRatingFlow.collectAsState(initial = true)
    val heroMeta by heroMetaFlow.collectAsState(initial = true)
    val heroScaleFlow = remember { app.store.heroScaleFlow() }
    val heroScale by heroScaleFlow.collectAsState(initial = 100)
    val heroConfig = remember(heroStyle, heroOverview, heroRating, heroMeta, heroScale) {
        HeroConfig(
            style = heroStyle,
            showOverview = heroOverview,
            showRating = heroRating,
            showMeta = heroMeta,
            scale = (heroScale.coerceIn(50, 150)) / 100f,
        )
    }
    val continueEntries = remember(history) {
        history.asSequence()
            .filter {
                it.positionMs >= WatchProgress.STARTED_MS &&
                    !WatchProgress.isWatched(it.positionMs, it.durationMs)
            }
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
    val featuredLive = remember(rows) {
        val first = rows.firstOrNull()?.items.orEmpty()
        (first.filter { !it.backdropUrl.isNullOrBlank() }.ifEmpty { first }).take(8)
    }
    // The TV hero latches the first rows so it never flashes while shelves
    // stream in — but it must latch PER provider pick, or the header keeps
    // showing the previous extension's art after a switch (the "header image
    // is not updating" report). Keying the latch on the selection drops the
    // stale art the instant the pick changes; the new rows latch when they
    // land. Left/Right on the hero itself steps through every latched title
    // (see TvCinematicHero) — nothing is fixed to one.
    var tvHero by remember(selection) { mutableStateOf(emptyList<MediaItem>()) }
    if (TvMode.current()) {
        if (tvHero.isEmpty() && featuredLive.isNotEmpty()) tvHero = featuredLive
    } else if (tvHero.isNotEmpty()) {
        tvHero = emptyList()
    }
    val featured = if (TvMode.current()) tvHero.ifEmpty { featuredLive } else featuredLive
    LaunchedEffect(featured) {
        featured.take(8).forEach { Artwork.prefetch(it); Artwork.retryMiss(it) }
    }
    val openGlobalSearch: () -> Unit = {
        Routes.navigateTab(nav, Routes.SEARCH)
    }
    var overlayIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val openSearch: () -> Unit = {
        when {
            selectedCollection != null -> showSearchDialog = true
            else -> {
                val ext = selection.filter { !it.startsWith(COLLECTION_PREFIX) }.toSet()
                overlayIds = ext.ifEmpty {
                    activeProviders.map { it.config.id }.toSet()
                }.toList()
                overlayGenre = ""
                if (overlayIds.isEmpty()) openGlobalSearch()
                else showHomeSearch = true
            }
        }
    }
    // One genre page: the TMDB combined film+series grid for it.
    val openTmdbGenre: (String, String, String) -> Unit = { name, genresText, keywordsText ->
        Routes.safeNavigate(
            nav,
            Routes.tmdbGridSpec(
                TmdbSpec(
                    type = TmdbSourceType.DISCOVER,
                    media = "all",
                    genresText = genresText,
                    keywords = keywordsText,
                    sort = "popularity.desc",
                    title = name,
                ).encode(),
                name,
                name,
            )
        )
    }
    // One genre tap, wherever it comes from (the home strip, the Genres
    // screen): with extensions picked it asks WHERE (this extension or
    // everything), otherwise it opens the TMDB genre grid straight away.
    val onGenrePick: (String, String, String) -> Unit = { name, genresText, keywordsText ->
        val ext = selection.filter { !it.startsWith(COLLECTION_PREFIX) }.toSet()
        if (ext.isEmpty()) {
            openTmdbGenre(name, genresText, keywordsText)
        } else {
            genreScope = GenreScopePick(name, genresText, keywordsText, ext)
        }
    }
    var showGenres by rememberSaveable { mutableStateOf(false) }
    val openVerify: () -> Unit = {        scope.launch {
            val own = withContext(Dispatchers.IO) {
                providers.firstOrNull { it.config.id == selected }?.let { webUrlChainAsync(it) }
            }
            val url = own
            val host = own?.let {
                runCatching { java.net.URI(it).host?.lowercase() }.getOrNull()
            }
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

    val uniqueRows = remember(rows) {
        rows.distinctBy { it.key.ifBlank { "${it.providerName}|${it.title}" } }
    }
    // Infinite auto-scroll showcase: anime sections get their own marquee
    // rows (see AutoScrollPosterRow), each moving at its own speed and
    // alternating direction, instead of another static shelf. Phone only —
    // a TV box cannot afford several always-on repaint loops.
    val animeMarqueeRows = remember(uniqueRows) {
        uniqueRows.filter { row -> row.items.any { it.looksAnime() } }.take(3)
    }
    // ScrollKeeper mirror of the feed below (see ScrollKeeper): the exact key
    // of every item in Lazy order, so Back lands on the same ROW even when
    // shelves stream in differently ordered, shimmer placeholders come and go,
    // or Continue Watching changes. The order and conditions must stay the
    // same as the feed itself.
    // Television: the genre strip moves to the END of the feed (the Nuvio-style
    // Home starts straight on the first shelf), so it is one more key here.
    val tvHome = TvMode.current()
    val feedKeys = remember(uniqueRows, continueEntries, collectionFolderRows, loading, hideContinue, rows.isEmpty(), tvHome) {
        buildList {
            add("home-header")
            if (!tvHome && animeMarqueeRows.isNotEmpty()) add("anime-marquee")
            if (!hideContinue && continueEntries.isNotEmpty()) add("continue-watching")
            collectionFolderRows.forEach { c -> add("collection-folders|${c.id}") }
            if (loading && collectionFolderRows.isEmpty()) repeat(4) { i -> add("home-shimmer|$i") }
            uniqueRows.forEach { row -> add(row.key.ifBlank { "${row.providerName}|${row.title}" }) }
            if (rows.isEmpty() && !loading && collectionFolderRows.isEmpty()) add("home-empty")
            if (tvHome) add("tv-genres")
        }
    }
    val feedState = com.hikari.app.ui.components.keptListState("home", itemKeys = feedKeys)

    // ---- Television Home (Nuvio style) ----
    // The card the remote is on drives the fixed backdrop + info block, and
    // the shelf it sits in is snapped to the top of the rows area.
    val tvFocus = remember { com.hikari.app.tv.TvHomeFocus() }
    LaunchedEffect(selection) { tvFocus.item = null }
    val tvShown = com.hikari.app.tv.rememberTvSettled(
        item = tvFocus.item ?: featured.firstOrNull(),
        resetKey = selection,
    )
    val tvOnFocus: (String, MediaItem) -> Unit = { rowKey, picked ->
        tvFocus.item = picked
        scope.launch {
            val at = feedKeys.indexOf(rowKey)
            if (at >= 0) runCatching { feedState.animateScrollToItem(at) }
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (TvMode.current()) {
            com.hikari.app.tv.TvHomeBackdropLayer(item = tvShown)
        }
        val feedContent: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {
            // Crash report: NOT inline any more. A stack trace dumped into the
            // feed made the feed look broken; the one-shot warning panel below
            // (see the GlassDialog after the Box) says what happened and points
            // at Settings → Logs, then stays out of the way.
            item {
                Column(Modifier.fillMaxWidth()) {
                    if (!tvHome) {
                        HomeHeader(
                            selected = headerSelection,
                            onSearch = openSearch,
                            onTranslate = { showTranslate = true },
                            onVerify = openVerify,
                            overlay = false,
                            onSettings = openSettings,
                            onMouse = { com.hikari.app.tv.MouseMode.toggle() },
                            mouseOn = com.hikari.app.tv.MouseMode.enabled,
                        )
                    }
                    if (featured.isNotEmpty() && !tvHome) {
                        Spacer(Modifier.height(2.dp))
                        // Television gets the cinematic hero (static backdrop,
                        // title, meta, Play / View Details — no auto-advance
                        // carousel, which a 1GB box cannot afford to repaint);
                        // the phone keeps its own banner styles.
                        if (TvMode.current()) {
                            TvCinematicHero(
                                items = featured,
                                scale = heroConfig.scale,
                                bookmarkedIds = favoriteIds,
                                onToggleBookmark = { item ->
                                    scope.launch {
                                        runCatching {
                                            if (item.uniqueId in favoriteIds) {
                                                app.store.removeFavorite(item.uniqueId)
                                            } else {
                                                app.store.addFavorite(item)
                                            }
                                        }
                                    }
                                },
                                onOpen = { item ->                                    Routes.safeNavigate(
                                        nav,
                                        Routes.detail(
                                            item.providerId, item.type, item.id,
                                            item.title, item.posterUrl, item.rawType
                                        )
                                    )
                                },
                            )
                        } else {
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
                                config = heroConfig,
                            )
                        }
                    }
                    if (!tvHome) {
                        HomeGenresButton(onClick = { showGenres = true })
                    }
                }
            }
            // Infinite auto-scrolling showcase, one marquee row per anime
            // section — each moves at its own speed and direction (see
            // AutoScrollPosterRow). Phone only; TV keeps its static shelves.
            if (!tvHome && animeMarqueeRows.isNotEmpty()) {
                item(key = "anime-marquee") {
                    Column(Modifier.fillMaxWidth()) {
                        animeMarqueeRows.forEachIndexed { index, row ->
                            AutoScrollPosterRow(
                                title = row.title,
                                providerName = row.providerName,
                                items = row.items,
                                speedDpPerSec = 30f + index * 14f,
                                reverse = index % 2 == 1,
                                onClick = { item ->
                                    Routes.safeNavigate(
                                        nav,
                                        if (item.rawType == "manga") {
                                            Routes.mangaRoute(item.providerId, item.id, item.title, item.posterUrl)
                                        } else {
                                            Routes.detail(item.providerId, item.type, item.id, item.title, item.posterUrl, item.rawType)
                                        }
                                    )
                                },
                            )
                        }
                    }
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
                        onRemove = { h -> scope.launch { app.store.removeHistory(h.uniqueKey) } },
                        // TV also gets the reference's Clear History action in
                        // the shelf header — same shared store, whole shelf.
                        onClearAll = if (TvMode.current()) {
                            { scope.launch { app.store.clearHistory() } }
                        } else null,
                        onItemFocus = if (TvMode.current()) {
                            { h2: com.hikari.app.data.HistoryEntry ->
                                tvOnFocus(
                                    "continue-watching",
                                    MediaItem(
                                        providerId = h2.providerId,
                                        id = h2.mediaId,
                                        title = h2.title,
                                        type = h2.type,
                                        posterUrl = h2.posterUrl,
                                        backdropUrl = backdropByKey["${h2.providerId}|${h2.type}|${h2.mediaId}"],
                                    ),
                                )
                            }
                        } else null,
                    )
                }
            }
            collectionFolderRows.forEach { c ->
                item(key = "collection-folders|${c.id}") {
                    CollectionFoldersOnHome(
                        collection = c,
                        onOpenFolder = { folder ->
                            Routes.safeNavigate(
                                nav,
                                Routes.collectionView(c.id, folder.id),
                            )
                        },
                        onShowAll = {
                            Routes.safeNavigate(nav, Routes.collectionGrid(c.id))
                        },
                    )
                }
            }
            if (loading && collectionFolderRows.isEmpty()) {
                items(4) { ShimmerRow() }
            }
            uniqueRows.forEach { row ->
                item(
                    key = row.key.ifBlank { "${row.providerName}|${row.title}" },
                    contentType = "media-row",
                ) {
                    com.hikari.app.tv.TvExpandingMediaRow(
                        title = row.title,
                        providerName = row.providerName,
                        items = row.items,
                        keeperKey = "homeRow:" + row.key.ifBlank { "${row.providerName}|${row.title}" },
                        onItemFocus = if (TvMode.current()) {
                            { m: MediaItem ->
                                tvOnFocus(row.key.ifBlank { "${row.providerName}|${row.title}" }, m)
                            }
                        } else null,
                        // Every shelf carries Show All into its full catalogue
                        // page, which pages without end as it is scrolled.
                        showViewAll = true,
                        onClick = { item ->
                            Routes.safeNavigate(
                                nav,
                                if (item.rawType == "manga") {
                                    Routes.mangaRoute(item.providerId, item.id, item.title, item.posterUrl)
                                } else {
                                    Routes.detail(item.providerId, item.type, item.id, item.title, item.posterUrl, item.rawType)
                                }
                            )
                        },
                        onQuickPlay = { item ->
                            Routes.safeNavigate(
                                nav,
                                if (item.rawType == "manga") {
                                    Routes.mangaRoute(item.providerId, item.id, item.title, item.posterUrl)
                                } else {
                                    Routes.detail(item.providerId, item.type, item.id, item.title, item.posterUrl, item.rawType, autoplay = true)
                                }
                            )
                        },
                        onShowAll = {
                            val collectionId = if (row.key.startsWith("coll|")) {
                                selectedCollection?.id ?: row.key.split('|').getOrNull(1)
                            } else {
                                null
                            }
                            if (collectionId != null) {
                                Routes.safeNavigate(nav, Routes.collectionGrid(collectionId))
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
            if (rows.isEmpty() && !loading && collectionFolderRows.isEmpty()) {
                item {
                    val collection = emptyTalkCollection
                    if (collection != null) {
                        val noFolders = collection.folders.isEmpty()
                        EmptyState(
                            title = if (noFolders) tr("This collection has no folders")
                            else tr("Nothing loaded from this collection"),
                            subtitle = if (noFolders) {
                                tr("Add a folder in Settings → Personal Catalog creator.")
                            } else {
                                tr(
                                    "Its folders came back empty. Check the extension " +
                                        "sites, or add another catalog to a folder."
                                )
                            },
                            actionLabel = tr("Collections"),
                            action = { Routes.safeNavigate(nav, Routes.COLLECTIONS) },
                        )
                    } else if (selected != null || selection.any { !it.startsWith(COLLECTION_PREFIX) }) {
                        val single = selected != null
                        val selectedKey = selected
                            ?: selection.firstOrNull { !it.startsWith(COLLECTION_PREFIX) } ?: ""
                        val reason = if (single) engineFailureReason(selectedKey) else null
                        var autoRetried by remember(selectedKey, selection.size) { mutableStateOf(false) }
                        LaunchedEffect(selectedKey, selection.size) {
                            if (!autoRetried) {
                                autoRetried = true
                                delay(1500)
                                vm.refresh()
                            }
                        }
                        // An extension whose site answers with a wall (403/503/429,
                        // a Cloudflare body, a "One moment, please" interstitial)
                        // is the one failure the user can actually do something
                        // about: the globe button opens THAT extension's own site,
                        // and the clearance the verification earns lands in the
                        // shared cookie jar the extension's client reads — so the
                        // Retry right after it succeeds. Hikari clears the
                        // challenge by itself first (see CloudflareSolver); this
                        // action is what remains for the cases it could not.
                        val wallFailure = reason?.let { r ->
                            r.contains("403") || r.contains("503") || r.contains("429") ||
                                com.hikari.app.net.CloudflareVerifier.isVerificationMessage(r)
                        } == true
                        val streamOnly = single &&
                            com.hikari.app.providers.StremioAddon.streamOnlyAddons[selectedKey] == true
                        val searchOnlySource = single &&
                            com.hikari.app.HikariApp.instance.providers.byId(selectedKey)?.searchOnly == true
                        if (searchOnlySource) {
                            EmptyState(
                                title = I18n.t("No catalog from %s")
                                    .replace("%s", selectedName ?: I18n.t("This extension")),
                                subtitle = tr(
                                    "This source is search-only — it publishes no home catalog. " +
                                        "Search it to find things to watch."
                                ),
                            )
                        } else if (streamOnly) {
                            EmptyState(
                                title = I18n.t("No catalog from %s").replace("%s", selectedName ?: "this addon"),
                                subtitle = tr(
                                    "This addon has no catalog of its own, so Home shows TMDB for it — " +
                                        "and that came back empty just now. Retry, or check your connection."
                                ),
                                actionLabel = tr("Retry"),
                                action = vm::refresh,
                            )
                        } else {
                            // Batch 137: a kept-but-disabled pick names itself
                            // instead of the generic "couldn't load".
                            val pickedDisabled = selectedKey.let { key ->
                                providers.any { it.config.id == key } &&
                                    providers.none { it.config.id == key && it.config.enabled }
                            }
                            EmptyState(
                                title = I18n.t("Couldn't load %s")
                                    .replace("%s", selectedName ?: I18n.t("This extension")),
                                subtitle = if (pickedDisabled) {
                                    tr("This extension is disabled — enable it in Extensions, or pick another provider.")
                                } else reason?.takeIf {
                                    !com.hikari.app.net.CloudflareVerifier.isVerificationMessage(it)
                                }
                                    ?: I18n.t(
                                        "Nothing came back from this extension. Retry, or open its " +
                                            "site in the WebView to check whether it is up — otherwise " +
                                            "browse another extension."
                                    ),
                                actionLabel = tr("Retry"),
                                action = vm::refresh,
                                action2Label = if (wallFailure) tr("Verify site") else null,
                                action2 = if (wallFailure) openVerify else null,
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
            // Television: genres live behind the same button (see the phone
            // feed); the strip at the end of the feed is gone.
            if (tvHome) {
                item(key = "tv-genres-button") {
                    HomeGenresButton(onClick = { showGenres = true })
                }
            }
        }
        if (TvMode.current()) {
            // Nuvio-style layout: header bar (provider pill) on top, the fixed
            // info block under it, and only the shelves scroll below that.
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(start = com.hikari.app.tv.TvUi.railWidth())
            ) {
                val pillProvider = providers.firstOrNull { it.config.id == selected }
                val pillProfileName = profiles.firstOrNull { it.id == activeProfileId }?.name
                TvHomeHeaderBar(
                    providerName = when {
                        selection.size > 1 -> selectedName
                        else -> pillProvider?.config?.name ?: selectedName
                    },
                    providerIconUrl = pillProvider?.config?.iconUrl,
                    profileName = pillProfileName,
                    onProviderClick = { showTvManageFeed = true },
                    onProfileClick = { showTvProfileMenu = true },
                    selected = headerSelection,
                    onSearch = openSearch,
                    onTranslate = { showTranslate = true },
                    onVerify = openVerify,
                    onMouse = { com.hikari.app.tv.MouseMode.toggle() },
                    mouseOn = com.hikari.app.tv.MouseMode.enabled,
                    onSettings = openSettings,
                    compact = true,
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(com.hikari.app.tv.TvHomeMetrics.infoHeight())
                ) {
                    com.hikari.app.tv.TvHomeHeroInfo(
                        item = tvShown,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                LazyColumn(
                    state = feedState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    content = feedContent,
                )
            }
            com.hikari.app.tv.TvHomeBottomFade(Modifier.align(Alignment.BottomCenter))
        } else {
            LazyColumn(
                state = feedState,
                modifier = Modifier.fillMaxSize(),
                // The taskbar floats over the feed, so the feed has to end below it
                // rather than above a reserved strip (see LocalTaskbarInset).
                contentPadding = PaddingValues(bottom = LocalTaskbarInset.current + 16.dp),
                content = feedContent,
            )
        }

        if (!TvMode.current()) {
        Surface(
            onClick = { showPicker = true },
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(
                    start = 16.dp,
                    top = 14.dp,
                    end = 16.dp,
                    bottom = 14.dp + LocalTaskbarInset.current,
                )
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
        }

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

        if (showHomeSearch) {
            // One name for one extension, the counted label for several, All
            // for everything — the same rule the header pill itself uses.
            val overlayName = when {
                overlayIds.size == 1 -> providers.firstOrNull { it.config.id == overlayIds.first() }
                    ?.config?.name ?: selectedName
                selection.isNotEmpty() -> selectedName
                else -> tr("All providers")
            }
            Box(
                if (TvMode.current()) Modifier.padding(start = com.hikari.app.tv.TvUi.railWidth())
                else Modifier
            ) {
            HomeSearchOverlay(
                providerIds = remember(overlayIds) { overlayIds.toSet() },
                providerName = overlayName,
                feedItems = remember(rows) { rows.flatMap { it.items } },
                genre = overlayGenre,
                onClearGenre = { overlayGenre = "" },
                onClose = { showHomeSearch = false; overlayGenre = "" },
                onOpen = { item ->
                    Routes.safeNavigate(
                        nav,
                        if (item.rawType == "manga") {
                            Routes.mangaRoute(item.providerId, item.id, item.title, item.posterUrl)
                        } else {
                            Routes.detail(
                                item.providerId, item.type, item.id,
                                item.title, item.posterUrl, item.rawType
                            )
                        }
                    )
                },
            )
            }
        }
    }

    if (showPicker) {
        ProviderPickerSheet(
            providers = activeProviders,
            collections = collections,
            selection = selection,
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
            onDone = { ids ->
                showPicker = false
                vm.setSelection(ids)
            },
            onDismiss = { showPicker = false },
            repoNameByProvider = repoNameByProvider,
        )
    }

    // Television: the Home Screen Feed mixer — active providers with remove,
    // available plugins with add, Save & Close applying the mix.
    if (showTvManageFeed) {
        TvManageFeedDialog(
            providers = activeProviders,
            selection = selection,
            onSave = { ids ->
                showTvManageFeed = false
                if (!vm.setSelection(ids)) vm.refreshFeed()
            },
            onDismiss = { showTvManageFeed = false },
        )
    }

    if (showTvProfileMenu) {
        TvProfileMenuDialog(
            profiles = profiles,
            activeId = activeProfileId,
            onSwitch = { id ->
                showTvProfileMenu = false
                scope.launch {
                    val ok = runCatching { Profiles.switchTo(app, id) }.getOrDefault(false)
                    if (!ok) {
                        Toast.makeText(
                            app.applicationContext,
                            I18n.t("That profile is locked — unlock it in Settings, Profiles."),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            },
            onAdd = { name ->
                showTvProfileMenu = false
                scope.launch { runCatching { Profiles.createEmpty(app, name) } }
            },
            onManage = {
                showTvProfileMenu = false
                Routes.safeNavigate(nav, Routes.SETTINGS)
            },
            onDismiss = { showTvProfileMenu = false },
        )
    }

    // A genre tap made while extensions are picked: this extension alone, or
    // everything. "Only here" browses the picked extensions' own catalogues
    // narrowed to the genre (see [homeGenreKeep]); "Everything" is the TMDB
    // genre grid as before.
    genreScope?.let { pick ->
        val scopeName = if (pick.extensionIds.size == 1) {
            providers.firstOrNull { it.config.id == pick.extensionIds.first() }
                ?.config?.name ?: selectedName ?: tr("this extension")
        } else {
            selectedName ?: I18n.t("%s sources").replace("%s", pick.extensionIds.size.toString())
        }
        AlertDialog(
            onDismissRequest = { genreScope = null },
            title = { Text(pick.name) },
            text = {
                Text(
                    tr("Only this extension's titles, or everything out there?") + " " +
                        I18n.t("Genre: %s.").replace("%s", pick.name)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    overlayIds = pick.extensionIds.toList()
                    overlayGenre = pick.name
                    genreScope = null
                    showHomeSearch = true
                }) { Text(I18n.t("Only %s").replace("%s", scopeName)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    genreScope = null
                    openTmdbGenre(pick.name, pick.genresText, pick.keywordsText)
                }) { Text(tr("Everything")) }
            },
        )
    }

    // Full-screen genre browser (see GenresOverlay): image cards for every
    // genre, with its own search. A genre tap closes the browser first so the
    // scope dialog (extensions picked) or the TMDB grid lands on top.
    if (showGenres) {
        GenresOverlay(
            onClose = { showGenres = false },
            onPick = { name, genresText, keywordsText ->
                showGenres = false
                onGenrePick(name, genresText, keywordsText)
            },
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

    if (showSearchDialog) {
        val coll = selectedCollection
        // "collection:<id>" for a catalog, the extension id otherwise — the key
        // the Search tab's provider row uses for the same thing.
        val scopeKey = if (coll != null) Routes.COLLECTION_PROVIDER_PREFIX + coll.id else selected
        val scopeName = if (coll != null) coll.name else selectedName
        if (scopeKey != null && scopeName != null) {
            AlertDialog(
                onDismissRequest = { showSearchDialog = false },
                title = { Text(tr("Search")) },
                text = {
                    Text(
                        tr("Search across every provider, or only inside %s?")
                            .replace("%s", scopeName)
                    )
                },
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
                        Routes.safeNavigate(nav, Routes.searchInProvider(scopeKey))
                    }) {
                        Text(tr("In %s").replace("%s", scopeName))
                    }
                },
            )
        }
    }
}

@Composable
private fun CollectionFoldersOnHome(
    collection: Collection,
    onOpenFolder: (CollectionFolder) -> Unit,
    onShowAll: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 10.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                collection.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (com.hikari.app.tv.TvMode.current()) com.hikari.app.tv.TvDesktop.TextPrimary
                else Color.Unspecified,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onShowAll) { Text(tr("Show all")) }
        }
        if (collection.folders.isEmpty()) {
            Text(
                tr("No folders yet — add one in Settings → Personal Catalog creator."),
                style = MaterialTheme.typography.bodySmall,
                color = if (com.hikari.app.tv.TvMode.current()) com.hikari.app.tv.TvDesktop.TextMuted
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
            )
            return@Column
        }
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(collection.folders, key = { it.id }) { f ->
                // The shape the tile will actually wear: its own when it has a
                // cover, otherwise the collection's (see FolderTile).
                val ownCover = CoverKinds.normalize(f.coverKind) != CoverKinds.NONE &&
                    f.coverValue.isNotBlank()
                FolderTile(
                    folder = f,
                    inheritedKind = collection.coverKind,
                    inheritedValue = collection.coverValue,
                    inheritedShape = collection.tileShape,
                    width = folderTileWidth(if (ownCover) f.tileShape else collection.tileShape),
                    onClick = { onOpenFolder(f) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProviderPickerSheet(
    providers: List<ContentProvider>,
    collections: List<Collection>,
    /** Every key currently picked (empty = All, one = the usual single pick,
     *  several = a multi pick). */
    selection: List<String>,
    filter: ProviderType?,
    onFilter: (ProviderType?) -> Unit,
    onManageCollections: () -> Unit,
    /** A plain tap in single-select mode: this is now the only source. */
    onPick: (String?) -> Unit,
    /** Multi-select's Done button: the keys to save. */
    onDone: (List<String>) -> Unit,
    onDismiss: () -> Unit,
    repoNameByProvider: Map<String, String>? = null,
) {
    var query by remember { mutableStateOf("") }
    var multi by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(selection) }
    val app = LocalContext.current.applicationContext as HikariApp
    val scope = rememberCoroutineScope()
    val pinned by remember { app.store.pinnedProvidersFlow() }
        .collectAsState(initial = emptyList<String>())
    val folders by remember { app.store.providerFoldersFlow() }
        .collectAsState(initial = emptyList<ProviderFolder>())
    val connectedTrackers by remember { app.store.trackersFlow() }.collectAsState(initial = emptyList())
    val sites by remember { app.store.sitesFlow() }
        .collectAsState(initial = emptyList<Site>())
    val ctx = LocalContext.current
    var sitesOpen by remember { mutableStateOf(false) }
    fun openSite(site: Site) {
        onDismiss()
        runCatching {
            ctx.startActivity(
                Intent(ctx, WebViewActivity::class.java).apply {
                    putExtra("url", site.url)
                    putExtra("title", site.name)
                }
            )
        }
    }
    var showFolderDialog by remember { mutableStateOf(false) }
    var folderName by remember { mutableStateOf("") }
    var folderToDelete by remember { mutableStateOf<ProviderFolder?>(null) }
    // What "Save as folder" would save: the ticked rows that ARE extensions —
    // a collection key is not a provider and has no place in a provider folder.
    val saveable = remember(working) {
        working.filter { !it.startsWith(COLLECTION_PREFIX) }
    }
    val kinds = remember(providers) {
        providers.map { it.config.type }.distinct().sortedBy { it.groupLabel }
    }
    val pinOrder = remember(pinned) { pinned.withIndex().associate { (i, id) -> id to i } }
    // Engine + repository for the plain rows below. The sheet's rows say
    // nothing but the extension's name, and an install list with four "AniKoto"
    // entries from four different repos gives the user nothing to choose by —
    // the repo is what tells them apart (see [RepoProvenance]).
    // Repo provenance for the plain rows below. When the caller hoisted the map
    // we use it; otherwise (Search) it is computed here from the same inputs.
    // `remember` is called unconditionally — with the provided map the computed
    // half is an empty map — because a conditional `remember` would change the
    // sheet's composition structure between its two callers.
    val repos by remember { app.store.reposFlow() }.collectAsState(initial = emptyList())
    val computedRepoNames = remember(providers, repos) {
        if (repoNameByProvider != null) emptyMap()
        else RepoProvenance.nameMap(providers.map { it.config }, repos)
    }
    val repoLabels = repoNameByProvider ?: computedRepoNames
    // One row per EXTENSION. An Aniyomi/manga pack publishes many sources under
    // one name (AnimeWorld India is nine: a generic feed plus
    // Bengali/English/Hindi/Japanese/Malayalam/Marathi/Tamil/Telugu), and one
    // row per source is what made one installed extension look like nine
    // installs. [ProviderPacks] folds them into the extension's own row; its
    // sources are a caret away and can still be picked one by one.
    val packs = remember(providers, query, filter, pinOrder) {
        val narrowed = providers.filter { filter == null || it.config.type == filter }
        val matches = if (query.isBlank()) narrowed
        else narrowed.filter { it.config.name.contains(query, ignoreCase = true) }
        val ordered = matches.sortedWith(
            compareBy({ pinOrder[it.config.id] ?: Int.MAX_VALUE }, { it.config.name.lowercase() })
        )
        ProviderPacks.rows(ordered)
    }
    // Which extension rows are opened to show their sources. Keyed by the row's
    // own key, so the state survives the list re-sorting under it.
    var expanded by remember { mutableStateOf(emptySet<String>()) }
    var cataloguesOpen by remember { mutableStateOf(false) }
    val shownCollections = remember(collections, query) {
        if (query.isBlank()) collections
        else collections.filter { it.name.contains(query, ignoreCase = true) }
    }
    if (showFolderDialog) {
        AlertDialog(
            onDismissRequest = { showFolderDialog = false },
            title = { Text(tr("Save as folder")) },
            text = {
                Column {
                    Text(
                        I18n.t(
                            if (saveable.size == 1) "%s extension will be saved in this folder"
                            else "%s extensions will be saved in this folder"
                        ).replace("%s", saveable.size.toString()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = folderName,
                        onValueChange = { folderName = it },
                        placeholder = { Text(tr("e.g. Daily")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = folderName.isNotBlank(),
                    onClick = {
                        val name = folderName.trim()
                        showFolderDialog = false
                        if (name.isNotBlank()) {
                            scope.launch {
                                runCatching { app.store.saveProviderFolder(name, saveable) }
                            }
                        }
                    },
                ) { Text(tr("Save")) }
            },
            dismissButton = {
                TextButton(onClick = { showFolderDialog = false }) { Text(tr("Cancel")) }
            },
        )
    }
    folderToDelete?.let { victim ->
        AlertDialog(
            onDismissRequest = { folderToDelete = null },
            title = { Text(tr("Delete this folder?")) },
            text = {
                Text(
                    I18n.t("%s is only a shortcut — its extensions stay installed.")
                        .replace("%s", victim.name)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    folderToDelete = null
                    scope.launch { runCatching { app.store.removeProviderFolder(victim.name) } }
                }) { Text(tr("Delete")) }
            },
            dismissButton = {
                TextButton(onClick = { folderToDelete = null }) { Text(tr("Cancel")) }
            },
        )
    }

    // Reference Select-Provider shape (see the desktop client's
    // ProviderSelectionDialog): a WIDE dialog — title + subtitle on the left,
    // the "All Plugins (Global)" pill top-right — a full-width filter below,
    // then the providers SIDE BY SIDE in a grid of icon+name+type cards, the
    // pick tinted purple. A tap picks and closes; a hold ticks into the
    // multi-pick without closing. The user's own folders, catalogues and
    // trackers stay: the same picker, drawn full-width above the grid.
    // (The Manage-feed mixer box is TV-only — TV opens TvManageFeedDialog
    // from its own header. The phone mixes with hold-to-multi + Done.)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            modifier = Modifier
                .fillMaxWidth(0.97f)
                .widthIn(max = 800.dp)
                .fillMaxHeight(0.92f)
                .heightIn(min = 240.dp, max = 780.dp),
        ) {
            Box(Modifier.fillMaxWidth()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            tr("Select Provider"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (multi) {
                                if (working.isEmpty()) tr("Tap the sources to show on Home, then Done.")
                                else I18n.t("%s sources picked — tap more, then Done.")
                                    .replace("%s", working.size.toString())
                            } else {
                                tr("Choose a dedicated provider or search across all installed plugins")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(
                        onClick = { if (multi) onDone(emptyList()) else onPick(null) },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(
                            tr("All Plugins (Global)"),
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    placeholder = {
                        Text(
                            tr("Filter providers..."),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .tvTextFieldKeys(query),
                )
                val shownSites = remember(sites, query) {
                    if (query.isBlank()) sites
                    else sites.filter {
                        it.name.contains(query, ignoreCase = true) ||
                            it.url.contains(query, ignoreCase = true)
                    }
                }
                if (kinds.isNotEmpty() || sites.isNotEmpty()) {
                    LazyRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
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
                        if (sites.isNotEmpty()) {
                            item {
                                FilterChipLine(
                                    label = tr("Websites"),
                                    selected = sitesOpen,
                                    onClick = { sitesOpen = !sitesOpen },
                                )
                            }
                        }
                    }
                    if (sitesOpen) {
                        Column(
                            Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (shownSites.isEmpty()) {
                                Text(
                                    tr("No websites saved yet — add them in Extensions → Webview sites."),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                )
                            }
                            shownSites.forEach { site ->
                                Surface(
                                    onClick = { openSite(site) },
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                ) {
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 9.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(
                                            Icons.Filled.Public,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                site.name,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                site.url,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                val packByKey = remember(packs) { packs.associateBy { it.key } }
                val packCells: List<Pair<String, Int>> = remember(packs, expanded) {
                    packs.flatMap { pack ->
                        val self = listOf(pack.key to -1)
                        if (pack.isPack && pack.key in expanded) {
                            self + pack.members.mapIndexed { i, _ -> pack.key to i }
                        } else self
                    }
                }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 160.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    // Bottom clearance for the floating pills below: the last
                    // row scrolls fully clear of them, like a footer pad.
                    contentPadding = PaddingValues(bottom = 84.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .heightIn(min = 60.dp, max = 560.dp),
                ) {
                if (shownCollections.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        PickerSectionLabel(tr("Personal catalogues"))
                    }
                    item(key = "personal-catalogues", span = { GridItemSpan(maxLineSpan) }) {
                        PickerRow(
                            label = tr("Personal catalogues"),
                            isSelected = false,
                            multi = false,
                            leadingIcon = Icons.Filled.Folder,
                            supporting = I18n.t("%s catalogues").replace(
                                "%s", shownCollections.size.toString()
                            ),
                            expandable = true,
                            expanded = cataloguesOpen,
                            onToggleExpand = { cataloguesOpen = !cataloguesOpen },
                            onClick = { cataloguesOpen = !cataloguesOpen },
                        )
                    }
                    if (cataloguesOpen) {
                    items(shownCollections, key = { "collection|${it.id}" }, span = { GridItemSpan(maxLineSpan) }) { c ->
                        val key = "$COLLECTION_PREFIX${c.id}"
                        val ticked = key in working
                        PickerRow(
                            label = c.name,
                            isSelected = if (multi) ticked else selection.contains(key),
                            multi = multi,
                            supporting = if (c.folders.isEmpty()) tr("No folders yet")
                            else c.folders.joinToString(" · ") { it.name },
                            onLongClick = {
                                if (!multi) {
                                    multi = true
                                    working = (selection + key).distinct()
                                }
                            },
                            onClick = {
                                if (multi) {
                                    working = if (ticked) working - key
                                    else working + key
                                } else {
                                    onPick(key)
                                }
                            },
                        )
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        PickerRow(
                            label = tr("Manage collections"),
                            isSelected = false,
                            leadingIcon = Icons.Filled.Tune,
                            showDivider = false,
                            onClick = onManageCollections,
                        )
                    }
                    } // cataloguesOpen
                }
                if (folders.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        PickerSectionLabel(tr("Your folders"))
                    }
                    items(folders, key = { "pfolder|${it.name}" }, span = { GridItemSpan(maxLineSpan) }) { folder ->
                        val ids = folder.ids
                        val allOn = ids.isNotEmpty() && ids.all { it in working }
                        val anyOn = ids.any { it in working }
                        PickerRow(
                            label = folder.name,
                            isSelected = if (multi) allOn
                            else ids.isNotEmpty() && selection.containsAll(ids),
                            multi = multi,
                            leadingIcon = Icons.Filled.Folder,
                            supporting = I18n.t(
                                if (ids.size == 1) "%s extension — tap to show it on Home"
                                else "%s extensions — tap to show them all on Home"
                            ).replace("%s", ids.size.toString()),
                            onDelete = { folderToDelete = folder },
                            onClick = {
                                if (multi) {
                                    working = if (anyOn) (working - ids.toSet()).distinct()
                                    else (working + ids).distinct()
                                } else {
                                    onDone(ids)
                                }
                            },
                        )
                    }
                }
                if (connectedTrackers.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { PickerSectionLabel(tr("Trackers")) }
                    item(key = "trackers-parent", span = { GridItemSpan(maxLineSpan) }) {
                        PickerRow(label = tr("Trackers"), isSelected = false, multi = false, leadingIcon = Icons.Filled.List,
                            supporting = I18n.t("%s connected").replace("%s", connectedTrackers.size.toString()),
                            expandable = true, expanded = "__trackers__" in expanded,
                            onToggleExpand = { expanded = if ("__trackers__" in expanded) expanded - "__trackers__" else expanded + "__trackers__" },
                            onClick = { expanded = if ("__trackers__" in expanded) expanded - "__trackers__" else expanded + "__trackers__" })
                    }
                    if ("__trackers__" in expanded) {
                        items(connectedTrackers, key = { "tracker:" + it.kind.key }, span = { GridItemSpan(maxLineSpan) }) { account ->
                            val key = TRACKER_PREFIX + account.kind.key
                            PickerRow(label = account.kind.label, isSelected = if (multi) key in working else selection.contains(key), multi = multi,
                                supporting = account.user.ifBlank { tr("Connected") },
                                onLongClick = { if (!multi) { multi = true; working = (selection + key).distinct() } },
                                onClick = { if (multi) working = if (key in working) working - key else (working + key).distinct() else onPick(key) })
                        }
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) { PickerSectionLabel(tr("Providers")) }
                item(key = "providers-hold-hint", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        tr("Hold any provider for half a second to select more than one."),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 10.dp, top = 2.dp, bottom = 4.dp),
                    )
                }
                items(
                    packCells,
                    key = { (k, i) ->
                        if (i < 0) "pack|$k"
                        else "packmember|${packByKey[k]?.members?.getOrNull(i)?.config?.id ?: "$k|$i"}"
                    }
                ) { (k, i) ->
                    val pack = packByKey[k] ?: return@items
                    val member = if (i >= 0) pack.members.getOrNull(i) else null
                    if (member != null) {
                        val mkey = member.config.id
                        val mticked = mkey in working
                        TvProviderCard(
                            name = if (pack.cross) pack.memberEngine(i).ifBlank { pack.memberLabel(i) } else pack.memberLabel(i),
                            supporting = listOf(
                                member.config.type.groupLabel,
                                repoLabels[mkey]?.takeIf { it.isNotBlank() },
                            ).filterNotNull().filter { it.isNotBlank() }
                                .distinct().joinToString(" · ")
                                .ifBlank { null },
                            selected = if (multi) mticked else selection.contains(mkey),
                            // The plugin's own banked language first (CloudStream
                            // MainAPI.lang — exact, e.g. Anime4i → GB); the
                            // language-named pack detail only as fallback.
                            flag = member.config.lang?.let { ProviderFlags.flagFor(it) }
                                ?: ProviderFlags.flagFor(pack.memberLabel(i)),
                            langCode = SourceLangs.codeOf(member),
                            icon = {
                                TvProviderIcon(
                                    iconUrl = member.config.iconUrl,
                                    name = member.config.name,
                                    size = 28.dp,
                                )
                            },
                            onClick = {
                                if (multi) {
                                    working = if (mticked) working - mkey
                                    else working + mkey
                                } else {
                                    onPick(mkey)
                                }
                            },
                            onHold = {
                                if (!multi) {
                                    multi = true
                                    working = (selection + mkey).distinct()
                                }
                            },
                            trailing = {
                                if (pinOrder.containsKey(mkey) || multi) {
                                    IconButton(
                                        onClick = { scope.launch { app.store.togglePinnedProvider(mkey) } },
                                        modifier = Modifier.size(32.dp),
                                    ) {
                                        Icon(
                                            Icons.Filled.PushPin,
                                            contentDescription = tr("Pin to the top of this list"),
                                            tint = if (pinOrder.containsKey(mkey)) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                            },
                        )
                    } else {
                    val key = pack.key
                    val streamOnly =
                        com.hikari.app.providers.StremioAddon.streamOnlyAddons[key] == true
                    val ids = pack.members.map { it.config.id }
                    val tickedIds = ids.filter { it in working }
                    val ticked = tickedIds.isNotEmpty()
                    val allTicked = tickedIds.size == ids.size
                    val isPinned = pinOrder.containsKey(key)
                    val open = key in expanded
                    // Same-name installs from different repos are told apart by
                    // the repo tag after the name, like the reference ("(repo)").
                    val dupTag = if (packs.count { it.label == pack.label } > 1) {
                        repoLabels[pack.primary.config.id]?.takeIf { it.isNotBlank() }?.let { "($it)" }
                    } else null
                    TvProviderCard(
                        name = pack.label,
                        supporting = when {
                            streamOnly -> I18n.t("%s addon · browses TMDB").replace(
                                "%s",
                                pack.primary.config.type.groupLabel,
                            )
                            !pack.isPack -> repoLabels[pack.primary.config.id]
                                ?.let { "${pack.primary.config.type.groupLabel} · $it" }
                            // In multi-select the card is a checkbox for a whole
                            // extension, so it says how much of the pack is on.
                            multi && ticked && !allTicked -> I18n.t("%s of %s picked")
                                .replaceFirst("%s", tickedIds.size.toString())
                                .replaceFirst("%s", ids.size.toString())
                            else -> {
                                val base = pack.countLabel +
                                    (pack.detailLabel.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
                                val packRepos = pack.members
                                    .mapNotNull { repoLabels[it.config.id]?.takeIf { r -> r.isNotBlank() } }
                                    .distinct()
                                if (packRepos.isEmpty()) base
                                else if (packRepos.size == 1) "$base · ${packRepos[0]}"
                                else "$base · ${packRepos[0]} · +${packRepos.size - 1}"
                            }
                        },
                        tag = dupTag,
                        selected = if (multi) ticked else selection.contains(key),
                        flag = pack.primary.config.lang?.let { ProviderFlags.flagFor(it) }
                            ?: ProviderFlags.flagForPack(pack),
                        langCode = SourceLangs.codeOf(pack.primary),
                        icon = {
                            TvProviderIcon(
                                iconUrl = pack.primary.config.iconUrl,
                                name = pack.label,
                                size = 28.dp,
                            )
                        },
                        onClick = {
                            if (multi) {
                                working = if (ticked) working - ids.toSet()
                                else (working + ids).distinct()
                            } else {
                                if (pack.cross && !open) expanded = expanded + key
                                else onPick(key)
                            }
                        },
                        onHold = {
                            if (!multi) {
                                multi = true
                                working = (selection + key).distinct()
                            }
                        },
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isPinned || multi) {
                                    IconButton(
                                        onClick = { scope.launch { app.store.togglePinnedProvider(key) } },
                                        modifier = Modifier.size(32.dp),
                                    ) {
                                        Icon(
                                            Icons.Filled.PushPin,
                                            contentDescription = tr(
                                                if (isPinned) "Unpin this extension"
                                                else "Pin to the top of this list"
                                            ),
                                            tint = if (isPinned) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                                if (pack.isPack) {
                                    IconButton(
                                        onClick = { expanded = if (open) expanded - key else expanded + key },
                                        modifier = Modifier.size(32.dp),
                                    ) {
                                        Icon(
                                            if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                            contentDescription = tr(
                                                if (open) "Hide this extension's sources"
                                                else "Show this extension's sources"
                                            ),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                            }
                        },
                    )
                    } // else: the pack's own card is above; open members are cells above too
                }
                if (packs.isEmpty() && query.isNotBlank()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            I18n.t("No extension matches \"$query\""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 16.dp)
                        )
                    }
                }
                }
                } // Column: the grid's own height ends here
                Row(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 20.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (multi && saveable.isNotEmpty()) {
                        FilledTonalButton(
                            onClick = { folderName = ""; showFolderDialog = true },
                            shape = RoundedCornerShape(50),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                            modifier = Modifier.shadow(6.dp, RoundedCornerShape(50)),
                        ) {
                            Text(tr("Save as folder"))
                        }
                    }
                    if (multi) {
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { onDone(working) },
                            shape = RoundedCornerShape(50),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            modifier = Modifier.shadow(6.dp, RoundedCornerShape(50)),
                        ) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(tr("Done"))
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(50),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        modifier = Modifier.shadow(6.dp, RoundedCornerShape(50)),
                    ) {
                        Text(tr("Close"))
                    }
                }
            } // Box
        } // Surface
    } // Dialog
}

@Composable
private fun FilterChipLine(label: String, selected: Boolean, onClick: () -> Unit) {
    // Television chips read muted-on-black with a hairline edge (desktop
    // language); phones keep the outline chip.
    val tvChip = com.hikari.app.tv.TvMode.current()
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        border = if (selected) null else BorderStroke(
            1.dp,
            if (tvChip) com.hikari.app.tv.TvDesktop.GlassEdge
            else MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
        ),
        modifier = Modifier.padding(vertical = 2.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
            else if (tvChip) com.hikari.app.tv.TvDesktop.TextMuted
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

/** An all-caps section heading inside the picker ("Collections", "Providers"). */
@Composable
private fun PickerSectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp),
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
    )
}

@Composable
private fun TvProviderStrip(
    providers: List<ContentProvider>,
    selection: List<String>,
    onPickSingle: (String) -> Unit,
    onPickAll: () -> Unit,
    onToggleMulti: (String) -> Unit,
    onOpenPicker: () -> Unit,
) {
    LazyRow(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item(key = "tv-prov-all") {
            TvProviderChip(
                label = tr("All"),
                selected = selection.isEmpty(),
                onClick = onPickAll,
                onHold = null,
            )
        }
        items(providers, key = { "tv-prov-" + it.config.id }) { p ->
            val id = p.config.id
            TvProviderChip(
                label = p.config.name,
                selected = id in selection,
                onClick = { onPickSingle(id) },
                onHold = { onToggleMulti(id) },
            )
        }
        item(key = "tv-prov-more") {
            TvProviderChip(
                label = "▦",
                selected = false,
                onClick = onOpenPicker,
                onHold = null,
            )
        }
    }
}

@Composable
private fun TvProviderChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    onHold: (() -> Unit)?,
) {
    val latestClick by rememberUpdatedState(onClick)
    val latestHold by rememberUpdatedState(onHold)
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
            )
            .then(
                if (latestHold == null) Modifier.clickable(onClick = onClick)
                else Modifier.tvPress(onClick = { latestClick() }, onHold = { latestHold?.invoke() })
            )
            .padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun PickerRow(
    label: String,
    isSelected: Boolean,
    supporting: String? = null,
    leadingIcon: ImageVector? = null,
    /** ISO country code for the real flag image in front of the name (the
     *  reference provider list's flags) — null draws no flag. */
    flagCode: String? = null,
    showDivider: Boolean = true,
    multi: Boolean = false,
    pinned: Boolean = false,
    /** Draw the caret that opens this row's own sources (an Aniyomi/manga
     *  extension's pack — see [com.hikari.app.ui.ProviderPacks]). */
    expandable: Boolean = false,
    /** Whether those sources are on screen right now. */
    expanded: Boolean = false,
    onToggleExpand: (() -> Unit)? = null,
    onTogglePin: (() -> Unit)? = null,
    /** A folder's own delete, drawn like the pin: its own tap target, so
     *  removing a folder can never be mistaken for choosing it. */
    onDelete: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val latestClick by rememberUpdatedState(onClick)
    val latestHold by rememberUpdatedState(onLongClick)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(
                    if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    else Color.Transparent
                )
                .then(
                    if (onLongClick == null) Modifier.clickable(onClick = onClick)
                    else Modifier
                        // `pointerInput` is invisible to the focus system, so a
                        // remote could not enter this list at all; `tvPress`
                        // adds the focus target and the centre press the pointer
                        // gesture never had (see the report in its own doc).
                        // `previewPass = false`: the row contains its own
                        // controls — the pin and the pack caret are clickables
                        // at its right end — and a press aimed at one of those
                        // must be theirs, not the row's (the row would otherwise
                        // pick the provider while the user was pressing Pin).
                        // `onHold` is the remote's half-second hold: without it
                        // a TV user could never multi-pick, because the press
                        // used to fire on KeyDown and a hold was just a tap.
                        .tvPress(previewPass = false, onClick = { latestClick() }, onHold = { latestHold?.invoke() })
                        .pointerInput(label, multi) {
                            holdOrTap({ latestHold?.invoke() }, { latestClick() })
                        }
                )
                .padding(horizontal = 10.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (flagCode != null) {
                FlagIcon(code = flagCode, size = 22.dp)
                Spacer(Modifier.width(10.dp))
            }
            if (leadingIcon != null) {
                Icon(
                    leadingIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
            }
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
            if (multi && !isSelected) {
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .border(
                            1.5.dp,
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
                            CircleShape,
                        ),
                )
            }
            if (expandable && onToggleExpand != null) {
                Spacer(Modifier.width(4.dp))
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onToggleExpand),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = tr(
                            if (expanded) "Hide this extension's sources"
                            else "Show this extension's sources"
                        ),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
            if (isSelected) {
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
            // A folder's delete, in the same place the pin sits on a provider
            // row: a control on the row, not part of what the row is.
            if (onDelete != null) {
                Spacer(Modifier.width(4.dp))
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onDelete),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.DeleteOutline,
                        contentDescription = tr("Delete this folder"),
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
            if (onTogglePin != null) {
                Spacer(Modifier.width(4.dp))
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onTogglePin),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.PushPin,
                        contentDescription = tr(if (pinned) "Unpin" else "Pin to the top"),
                        tint = if (pinned) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
        }
        if (showDivider) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                modifier = Modifier.padding(start = if (leadingIcon != null) 38.dp else 10.dp),
            )
        }
    }
}

internal const val HOLD_MS = 500L

internal suspend fun PointerInputScope.holdOrTap(
    onHold: () -> Unit,
    onTap: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var tapped = false
        val completed = withTimeoutOrNull(HOLD_MS) {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (change.isConsumed) break
                if (!change.pressed) {
                    tapped = true
                    break
                }
                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                    break
                }
            }
            true
        }
        when {
            completed == null -> onHold()
            tapped -> onTap()
        }
    }
}

/** The website URL a provider's content actually lives on (for the Cloudflare
 *  verification WebView button). HIKARI providers expose it through their SDK
 *  mainUrl; Stremio/universal use the configured URL; CS3 plugins load theirs
 *  from the plugin dex. Null when unknown — the button is hidden then. */
private fun sourceSiteUrl(file: java.io.File, name: String): String? {
    if (!file.isFile) return null
    val text = runCatching { file.readText() }.getOrNull() ?: return null
    val blocked = setOf("github.com", "raw.githubusercontent.com", "image.tmdb.org", "api.themoviedb.org", "anilist.co", "simkl.com", "trakt.tv", "imdb.com", "google.com", "youtube.com")
    val tokens = name.lowercase().split(Regex("[^a-z0-9]+"))
    // Never return an arbitrary zero-score URL from a Nuvio source file:
    // unrelated catalog URLs such as Cinemeta must not open for another extension.
    val explicit = Regex(
        """(?i)(?:mainUrl|baseUrl|siteUrl|homeUrl|website|domain)\s*[:=]\s*["'](https?://[^"'\s]+)["']"""
    ).findAll(text).mapNotNull { m ->
        val raw = m.groupValues.getOrNull(1) ?: return@mapNotNull null
        val host = runCatching { java.net.URI(raw).host?.lowercase()?.removePrefix("www.") }.getOrNull() ?: return@mapNotNull null
        if (host in blocked) return@mapNotNull null
        "https://" + host + "/"
    }.firstOrNull()
    val scored = Regex("https?://[A-Za-z0-9.-]+").findAll(text).mapNotNull { m ->
        val host = runCatching { java.net.URI(m.value).host?.lowercase()?.removePrefix("www.") }.getOrNull() ?: return@mapNotNull null
        if (host in blocked) return@mapNotNull null
        val score = tokens.count { it.length >= 3 && host.contains(it) }
        if (score <= 0) return@mapNotNull null
        score to "https://" + host + "/"
    }.sortedByDescending { it.first }.map { it.second }.firstOrNull()
    return scored ?: explicit
}

internal fun webUrlFor(p: ContentProvider): String? = when (p.config.type) {
    ProviderType.STREMIO, ProviderType.UNIVERSAL -> webUrlRootOf(p.config.url)
    ProviderType.HIKARI -> com.hikari.app.hiki.HikariRuntime.providerFor(p.config)?.mainUrl
    ProviderType.SKYSTREAM -> com.hikari.app.skystream.SkyStreamPluginManager.siteUrlOf(p.config)
    ProviderType.ANIYOMI -> com.hikari.app.aniyomi.AniyomiExtensionManager.siteUrlOf(p.config)
    ProviderType.MANGA -> if (com.hikari.app.lnreader.LnReaderProvider.isLnReaderId(p.config.id)) {
        // Novel sources carry no extension to load a site from — the install
        // captured the plugin's own site (see LnReaderPluginManager).
        com.hikari.app.lnreader.LnReaderPluginManager.siteUrlOf(p.config)
    } else {
        com.hikari.app.manga.MangaExtensionManager.siteUrlOf(p.config)
    }
    ProviderType.ANYMEX, ProviderType.ANYMEX_MANGA -> com.hikari.app.anymex.AnymexPluginManager.siteUrlOf(p.config)
    ProviderType.SORA -> p.config.extra?.takeIf { it.startsWith("http") }?.let { src -> runCatching { val u = java.net.URI(src); u.scheme + "://" + u.host + "/" }.getOrNull() }
    ProviderType.NUVIO -> sourceSiteUrl(java.io.File(p.config.url), p.config.name)
    ProviderType.VEGA -> runCatching {
        val dir = com.hikari.app.providers.vega.VegaPluginManager.dirOf(p.config)
        sourceSiteUrl(java.io.File(dir, "meta.js"), p.config.name)
            ?: sourceSiteUrl(java.io.File(dir, "posts.js"), p.config.name)
            ?: sourceSiteUrl(java.io.File(dir, "stream.js"), p.config.name)
    }.getOrNull()
    ProviderType.ZANGETSU -> com.hikari.app.zangetsu.ZangetsuPluginManager.siteUrlOf(p.config)
    ProviderType.FORK_VIDEO -> runCatching {
        val owner = com.hikari.app.kotatsu.fork.ForkVideoProvider.ownerOf(p.config.id)
        val enum = com.hikari.app.kotatsu.fork.ForkVideoProvider.enumNameOf(p.config.id)
        val domain = if (owner == com.hikari.app.kotatsu.fork.ForkVideoProvider.OWNER_SKEPSUN) {
            com.hikari.app.kotatsu.fork.SkepsunBridge.forSource(enum)?.parserDomain()
        } else {
            com.hikari.app.kotatsu.fork.Hany18hBridge.forSource(enum)?.parserDomain()
        }
        domain?.trim()?.takeIf { it.isNotBlank() }?.let { d ->
            val host = d.trim().removePrefix("https://").removePrefix("http://").substringBefore('/').lowercase()
            if (host.contains(".")) "https://$host/" else null
        }
    }.getOrNull()
    ProviderType.CS3 -> runCatching {
        val file = java.io.File(p.config.url); if (!file.exists()) return@runCatching null
        val apis = com.hikari.app.cs3.Cs3PluginManager.apisFor(com.hikari.app.HikariApp.instance, file)
        apis.getOrNull(p.config.id.substringAfterLast("|").toIntOrNull() ?: 0)?.mainUrl?.takeIf { it.startsWith("http") }
    }.getOrNull()
    else -> null
}

/**
 * The site root of any http(s) URL (a manifest.json tail is stripped, so a
 * Stremio manifest URL yields its addon); null for local paths and blanks.
 */
internal fun webUrlRootOf(raw: String?): String? =
    raw?.takeIf { it.startsWith("http://") || it.startsWith("https://") }?.let {
        runCatching {
            val u = java.net.URI(it)
            val path = u.path.orEmpty().removeSuffix("/")
            val base = if (path.endsWith("/manifest.json", true)) path.dropLast("/manifest.json".length) else path
            u.scheme + "://" + u.authority + if (base.isBlank()) "/" else base
        }.getOrNull()
    }

internal fun webUrlChain(p: ContentProvider, vararg itemUrls: String?): String? {
    for (u in itemUrls) {
        u?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }?.let { return it }
    }
    runCatching { webUrlFor(p) }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
    webUrlRootOf(p.config.url)?.let { return it }
    webUrlRootOf(p.config.extra)?.takeIf { !isScriptArtifactUrl(it) }?.let { return it }
    return null
}

/** An install artifact, not a browsable site page: never a globe target. */
private fun isScriptArtifactUrl(url: String): Boolean {
    val lower = url.lowercase()
    if (lower.endsWith(".js")) return true
    if (lower.contains("/javascript/")) return true
    return false
}

internal suspend fun webUrlChainAsync(p: ContentProvider, vararg itemUrls: String?): String? {
    webUrlChain(p, *itemUrls)?.let { return it }
    if (com.hikari.app.lnreader.LnReaderProvider.isLnReaderId(p.config.id)) {
        runCatching { com.hikari.app.lnreader.LnReaderPluginManager.resolveSite(p.config) }
            .getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
    }
    return null
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
    onMouse: () -> Unit = {},
    mouseOn: Boolean = false,
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
        // Hamburger: opens the "Select Provider" drawer (hikari / HIKari music). Not on TV.
        if (!TvMode.current()) {
            IconButton(onClick = { com.hikari.app.music.MusicMode.drawerOpen = true }) {
                Icon(Icons.Filled.Menu, contentDescription = "Select provider", tint = iconTint)
            }
        }
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
        if (TvMode.current()) {
            IconButton(onClick = onMouse) {
                Icon(
                    Icons.Filled.Mouse,
                    contentDescription = tr("Mouse cursor"),
                    tint = if (mouseOn) accent else iconTint,
                    modifier = Modifier.size(if (mouseOn) 26.dp else 24.dp),
                )
            }
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
/** First-run seed rows live in the repository (see HomeFeedCache below) —
 *  scroll position itself is held by ScrollKeeper ("home" key). */

private object HomeFeedCache {
    val rows = object : LinkedHashMap<String, List<CatalogRow>>() {
        override fun removeEldestEntry(eldest: Map.Entry<String, List<CatalogRow>>): Boolean = size > 80
    }
}


/** Collapse an oversized (base64) poster into a tiny disk-cache token — the
 *  same treatment the feed's own rows get, so a search result whose artwork is
 *  a data: URI cannot hold a megabyte of string in memory per cell. */
private fun MediaItem.shrinkPoster(): MediaItem {
    val p = PosterLoader.tokenize(posterUrl)
    val b = PosterLoader.tokenize(backdropUrl)
    return if (p == posterUrl && b == backdropUrl) this
    else copy(posterUrl = p, backdropUrl = b)
}

private fun engineSearchFailureReason(providerId: String): String? =
    com.hikari.app.cs3.Cs3MainApiProvider.searchErrors[providerId]

private fun engineFailureReason(providerId: String): String? =
    com.hikari.app.data.TrackerLibraryRepository.errors[providerId]
        ?: com.hikari.app.providers.HikariProviderAdapter.catalogErrors[providerId]
        ?: com.hikari.app.cs3.Cs3MainApiProvider.catalogErrors[providerId]
        ?: com.hikari.app.providers.StremioAddon.catalogErrors[providerId]
        ?: com.hikari.app.nuvio.NuvioScraper.catalogErrors[providerId]
        ?: com.hikari.app.skystream.SkyStreamProvider.catalogErrors[providerId]
        ?: com.hikari.app.aniyomi.AniyomiProvider.catalogErrors[providerId]
        ?: com.hikari.app.anymex.AnymexProvider.catalogErrors[providerId]
        ?: com.hikari.app.providers.vega.VegaProvider.catalogErrors[providerId]
        ?: com.hikari.app.sora.SoraProvider.catalogErrors[providerId]
        ?: com.hikari.app.manga.MangaProvider.lastOutcome[providerId]
            ?.takeIf { !it.startsWith("✓") && !it.startsWith("✔") }

/** The kind chips inside the Home search overlay — the same split the Search
 *  tab and the TMDB catalog page offer, over the overlay's own results (and
 *  over the loaded feed when the query is empty). Movies and Series exclude
 *  anime, so an animation never leaks into the film wall again. */
private const val HOME_KIND_ALL = "all"
private const val HOME_KIND_MOVIE = "movie"
private const val HOME_KIND_SERIES = "series"
private const val HOME_KIND_ANIME = "anime"
private const val HOME_KIND_MOVIE_SERIES = "movie_series"

private val HOME_KIND_CHIPS: List<Pair<String, String>> = listOf(
    HOME_KIND_ALL to "All",
    HOME_KIND_MOVIE to "Movies",
    HOME_KIND_SERIES to "Series",
    HOME_KIND_ANIME to "Anime",
    HOME_KIND_MOVIE_SERIES to "Movies & series",
)

private fun homeKindKeep(item: MediaItem, kind: String): Boolean = when (kind) {
    HOME_KIND_MOVIE -> (item.type == MediaType.MOVIE || item.type == MediaType.UNKNOWN) && !item.looksAnime()
    HOME_KIND_SERIES -> (item.type == MediaType.SERIES || item.type == MediaType.UNKNOWN) && !item.looksAnime()
    HOME_KIND_ANIME -> item.looksAnime()
    HOME_KIND_MOVIE_SERIES ->
        (item.type == MediaType.MOVIE || item.type == MediaType.SERIES || item.type == MediaType.UNKNOWN) && !item.looksAnime()
    else -> true
}

/** A genre tap held for the scope question: what was tapped, and which
 *  extensions Home had picked when it was tapped. */
private data class GenreScopePick(
    val name: String,
    val genresText: String,
    val keywordsText: String,
    val extensionIds: Set<String>,
)

private class BrowseCursor(
    val providerId: String,
    val ref: CatalogRef,
    val isAnimeCat: Boolean,
    var nextPage: Int = 1,
    var exhausted: Boolean = false,
)

/** Hard ceiling for one kind/genre browse session: the shelves drain until
 *  they are dry or this many titles are in, whichever comes first. */
private const val BROWSE_ITEM_CAP = 3000

private fun homeGenreKeep(item: MediaItem, name: String): Boolean {
    if (name.trim().isEmpty()) return true
    if (item.genres.any { g -> com.hikari.app.data.Genres.matches(g, name) }) return true
    return com.hikari.app.data.Genres.matches(item.title, name)
}

@Composable
private fun HomeSearchKindRow(kindKey: String, onPick: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = 12.dp, end = 12.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for ((key, label) in HOME_KIND_CHIPS) {
            FilterChip(
                selected = kindKey == key,
                onClick = { onPick(key) },
                label = { Text(tr(label)) },
                shape = RoundedCornerShape(24.dp),
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    selectedLabelColor = MaterialTheme.colorScheme.primary,
                ),
            )
        }
    }
}

@Composable
private fun HomeSearchOverlay(
    providerIds: Set<String>,
    providerName: String?,
    /** The titles Home already has loaded: picking a kind with an empty query
     *  browses THESE instead of the network, so Anime/Movies/... show what the
     *  picked extensions actually hold. */
    feedItems: List<MediaItem>,
    /** A genre the overlay is narrowed to ("" = no narrowing): set when a Home
     *  genre chip chose "only this extension" (see [GenreScopePick]). */
    genre: String = "",
    onClearGenre: () -> Unit = {},
    onClose: () -> Unit,
    onOpen: (MediaItem) -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as HikariApp
    val repo = remember { ContentRepository(app.providers) }
    val label = providerName?.takeIf { it.isNotBlank() } ?: tr("these extensions")
    var kindKey by rememberSaveable { mutableStateOf(HOME_KIND_ALL) }
    var typed by rememberSaveable { mutableStateOf("") }
    var applied by rememberSaveable { mutableStateOf("") }
    val results by HomeSearchSession.results.collectAsState()
    val searching by HomeSearchSession.searching.collectAsState()
    val focusRequester = remember { FocusRequester() }

    // Search-bar translator: the same control the Search tab carries on its
    // search bar (tap = EN <-> target language, long-press = pick the target
    // language). A translated query is applied straight away so the sweep
    // re-runs on it; typing clears the "show original" state.
    var translatedFrom by remember { mutableStateOf<String?>(null) }
    var targetLang by rememberSaveable { mutableStateOf("zh-CN") }
    var langMenu by remember { mutableStateOf(false) }
    var translating by remember { mutableStateOf(false) }
    val translateScope = rememberCoroutineScope()
    fun toggleTranslate() {
        if (translating) return
        val restore = translatedFrom
        if (restore != null) {
            translatedFrom = null
            typed = restore
            applied = restore.trim()
            return
        }
        val src = typed.trim()
        if (src.isEmpty()) return
        translateScope.launch {
            translating = true
            val out = runCatching {
                com.hikari.app.data.Translator.translateTo(src, targetLang)
            }.getOrDefault("")
            translating = false
            if (out.isNotBlank() && out != src) {
                translatedFrom = src
                typed = out
                applied = out.trim()
            }
        }
    }

    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    LaunchedEffect(applied, providerIds) {
        if (applied.isBlank() || providerIds.isEmpty()) {
            HomeSearchSession.clear()
        } else {
            HomeSearchSession.search(repo, applied, providerIds)
        }
    }

    LaunchedEffect(typed) {
        delay(400)
        applied = typed.trim()
    }

    BackHandler { onClose() }

    Surface(Modifier.fillMaxSize(), color = pageBackground()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 2.dp, end = 12.dp, top = 6.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = tr("Close"),
                    )
                }
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it; translatedFrom = null },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    placeholder = {
                        Text(
                            I18n.t("Search %s…").replace("%s", label),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    trailingIcon = {
                        // Same translator the Search tab has: tap toggles the
                        // translation, long-press picks the target language.
                        Box {
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(50))
                                    .tvPress { toggleTranslate() }
                                    .pointerInput(Unit) {
                                        detectTapGestures(
                                            onTap = { toggleTranslate() },
                                            onLongPress = { langMenu = true },
                                        )
                                    }
                                    .padding(10.dp)
                            ) {
                                if (translating) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(
                                        Icons.Filled.Translate,
                                        contentDescription = if (translatedFrom != null)
                                            "Show original" else "Translate to $targetLang",
                                        tint = if (translatedFrom != null) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            DropdownMenu(
                                expanded = langMenu,
                                onDismissRequest = { langMenu = false },
                            ) {
                                com.hikari.app.data.Translator.LANGUAGES.forEach { (code, langLabel) ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(if (code == targetLang) "$langLabel  ✓" else langLabel)
                                        },
                                        onClick = {
                                            targetLang = code
                                            langMenu = false
                                            translatedFrom = null
                                        },
                                    )
                                }
                            }
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester)
                        .tvTextFieldKeys(typed),
                )
            }
            HomeSearchKindRow(kindKey) { kindKey = it }
            // A genre narrowing from the Home strip ("only this extension"):
            // removable, so one tap returns to the un-narrowed search.
            if (genre.isNotBlank()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = true,
                        onClick = onClearGenre,
                        label = { Text(genre) },
                        trailingIcon = {
                            Text(
                                "✕",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        },
                        shape = RoundedCornerShape(24.dp),
                    )
                }
            }
            // One tap narrows what is already here: the typed results AND the
            // loaded feed behind them are cut by the same rule, so the chips
            // never disagree with the grid below them.
            //
            // Genre enrichment state first (used below): catalogue cards carry
            // no genre tags (only each title's own detail page names them), so
            // the genre chip above can never match an extension title on tags
            // alone. Untagged titles are checked against their own details a
            // few at a time; matches join the grid as their genres land, and
            // every verdict is remembered for the session so scrolling never
            // re-asks.
            var enrichedGenres by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
            var checkedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
            var checkingCount by remember { mutableStateOf(0) }
            var checksUsed by remember { mutableStateOf(0) }
            val triageMutex = remember { Mutex() }
            fun withTags(item: MediaItem): MediaItem {
                val extra = enrichedGenres[item.uniqueId]
                if (extra.isNullOrEmpty()) return item
                return item.copy(genres = (item.genres + extra).distinct())
            }
            val shownResults = remember(results, enrichedGenres, kindKey, genre, applied) {
                results.map { withTags(it) }
                    .filter {
                        (homeKindKeep(it, kindKey) || (applied.isNotBlank() && it.title.contains(applied, ignoreCase = true))) &&
                            homeGenreKeep(it, genre)
                    }
                    .distinctBy { it.uniqueId }
                    .let { list ->
                        if (applied.isBlank()) list
                        else list.sortedWith(
                            compareBy(
                                { if (it.title.equals(applied, ignoreCase = true)) 0 else 1 },
                                { if (it.title.startsWith(applied, ignoreCase = true)) 0 else 1 },
                            ),
                        )
                    }
            }
            // An empty query with a kind browses the provider's CATALOGUES, not
            // just the rows Home already loaded: the feed only holds the first
            // screenful (7 series, 0 anime in the report), while the catalogue
            // holds everything. The loaded rows paint instantly; the catalogue
            // scan below merges in as it lands — page 1 first, then further
            // pages as the grid is scrolled (see [loadMoreBrowse]), until every
            // catalogue answers empty. Items from a catalogue whose id/name/
            // rawType says anime count as anime even when the item itself
            // carries no genre tag (site scrapers tag nothing per item).
            var browseItems by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
            var browseAnimeIds by remember { mutableStateOf<Set<String>>(emptySet()) }
            var browseLoading by remember { mutableStateOf(false) }
            var browseCursors by remember { mutableStateOf<List<BrowseCursor>>(emptyList()) }
            var browseMoreLoading by remember { mutableStateOf(false) }
            var browseDone by remember { mutableStateOf(false) }
            val browseScope = rememberCoroutineScope()
            LaunchedEffect(kindKey, genre, providerIds) {
                if ((kindKey == HOME_KIND_ALL && genre.isBlank()) || providerIds.isEmpty()) {
                    browseItems = emptyList()
                    browseAnimeIds = emptySet()
                    browseCursors = emptyList()
                    browseDone = false
                    browseLoading = false
                    return@LaunchedEffect
                }
                browseLoading = true
                browseDone = false
                try {
                    val mgr = app.providers
                    val targets = withContext(Dispatchers.IO) {
                        mgr.providers.value
                            .filter { it.config.enabled && it.config.id in providerIds }
                            .take(8)
                    }
                    val cursors = withContext(Dispatchers.IO) {
                        val all = ArrayList<BrowseCursor>()
                        for (p in targets) {
                            val cats = runCatching { p.catalogs() }.getOrDefault(emptyList())
                            for (ref in cats) {
                                val isAnimeCat = ref.id.contains("anime", true) ||
                                    ref.name.contains("anime", true) ||
                                    ref.rawType.equals("anime", true)
                                all += BrowseCursor(p.config.id, ref, isAnimeCat)
                            }
                        }
                        all.toList()
                    }
                    browseCursors = cursors
                    withContext(Dispatchers.IO) {
                        val acc = ArrayList<MediaItem>()
                        val animeIds = HashSet<String>()
                        for (c in cursors.take(2)) {
                            val p = targets.firstOrNull { it.config.id == c.providerId }
                                ?: continue
                            val page = runCatching {
                                ContentRepository.loadCatalogPage(p, c.ref, 1)
                            }.getOrDefault(emptyList())
                            if (page.isEmpty()) {
                                c.exhausted = true
                                continue
                            }
                            c.nextPage = 2
                            for (m in page) {
                                val small = m.shrinkPoster()
                                if (acc.none { it.uniqueId == small.uniqueId }) acc.add(small)
                                if (c.isAnimeCat) animeIds.add(small.uniqueId)
                                if (acc.size >= 60) break
                            }
                            if (acc.size >= 60) break
                        }
                        browseItems = acc.toList()
                        browseAnimeIds = animeIds.toSet()
                    }
                    browseLoading = false
                    withContext(Dispatchers.IO) {
                        for (c in cursors.drop(2)) {
                            if (browseItems.size >= 600) break
                            // Already read (scroll paging got here first) or
                            // retired: page 1 must be read exactly once.
                            if (c.exhausted || c.nextPage != 1) continue
                            val p = targets.firstOrNull { it.config.id == c.providerId }
                                ?: continue
                            val page = runCatching {
                                ContentRepository.loadCatalogPage(p, c.ref, 1)
                            }.getOrDefault(emptyList())
                            if (page.isEmpty()) {
                                c.exhausted = true
                                continue
                            }
                            c.nextPage = 2
                            val seen = browseItems.map { it.uniqueId }.toHashSet()
                            val add = page.map { it.shrinkPoster() }
                                .filter { seen.add(it.uniqueId) }
                            if (add.isNotEmpty()) {
                                browseItems = browseItems + add
                                if (c.isAnimeCat) {
                                    browseAnimeIds = browseAnimeIds +
                                        add.map { it.uniqueId }.toSet()
                                }
                            }
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Throwable) {
                } finally {
                    browseLoading = false
                }
            }
            LaunchedEffect(feedItems, browseItems, results, genre, providerIds) {
                if (genre.isBlank() || providerIds.isEmpty()) {
                    checkingCount = 0
                    return@LaunchedEffect
                }
                val pool = (feedItems + browseItems + results).distinctBy { it.uniqueId }
                val budget = (1500 - checksUsed).coerceAtLeast(0)
                val todo = if (budget <= 0) emptyList()
                else pool.filter { it.genres.isEmpty() && it.uniqueId !in checkedIds }.take(budget)
                if (todo.isEmpty()) {
                    checkingCount = 0
                    return@LaunchedEffect
                }
                triageMutex.withLock {
                    try {
                        checkingCount = todo.size
                        val byProvider = app.providers.providers.value
                            .filter { it.config.enabled && it.config.id in providerIds }
                            .associateBy { it.config.id }
                        val gate = Semaphore(8)
                        var rest = todo
                        while (rest.isNotEmpty()) {
                            val batch = rest.take(30)
                            rest = rest.drop(30)
                            val verdicts = coroutineScope {
                                batch.map { item ->
                                    async(Dispatchers.IO) {
                                        gate.withPermit {
                                            val p = byProvider[item.providerId]
                                            val tags = if (p == null) emptyList()
                                            else runCatching { p.getMeta(item) }.getOrNull()?.genres.orEmpty()
                                            item.uniqueId to tags
                                        }
                                    }
                                }.awaitAll()
                            }
                            val fresh = verdicts.filter { it.second.isNotEmpty() }.toMap()
                            if (fresh.isNotEmpty()) enrichedGenres = enrichedGenres + fresh
                            checkedIds = checkedIds + verdicts.map { it.first }
                            checksUsed += verdicts.size
                            checkingCount = rest.size
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                    } finally {
                        checkingCount = 0
                    }
                }
            }
            fun loadMoreBrowse() {
                if (browseMoreLoading || browseDone) return
                if (kindKey == HOME_KIND_ALL && genre.isBlank() || providerIds.isEmpty()) return
                val pending = browseCursors.filterNot { it.exhausted }
                if (pending.isEmpty()) {
                    browseDone = true
                    return
                }
                browseMoreLoading = true
                val current = browseItems
                val currentAnime = browseAnimeIds
                browseScope.launch(Dispatchers.IO) {
                    try {
                        val byId = app.providers.providers.value
                            .filter { it.config.enabled && it.config.id in providerIds }
                            .associateBy { it.config.id }
                        val fresh = ArrayList<MediaItem>()
                        val freshAnime = HashSet<String>()
                        for (c in pending.take(6)) {
                            val p = byId[c.providerId]
                            if (p == null) {
                                c.exhausted = true
                                continue
                            }
                            val page = runCatching {
                                ContentRepository.loadCatalogPage(p, c.ref, c.nextPage)
                            }.getOrDefault(emptyList())
                            if (page.isEmpty()) {
                                c.exhausted = true
                                continue
                            }
                            c.nextPage++
                            for (m in page) {
                                val small = m.shrinkPoster()
                                fresh.add(small)
                                if (c.isAnimeCat) freshAnime.add(small.uniqueId)
                            }
                        }
                        val seen = current.map { it.uniqueId }.toHashSet()
                        val add = fresh.filter { seen.add(it.uniqueId) }
                        if (add.isNotEmpty()) browseItems = current + add
                        if (freshAnime.isNotEmpty()) browseAnimeIds = currentAnime + freshAnime
                        if (browseCursors.all { it.exhausted }) browseDone = true
                    } catch (_: Throwable) {
                    } finally {
                        browseMoreLoading = false
                    }
                    // Hard ceiling for one session: the shelves page on scroll
                    // until they are dry or this many titles are in.
                    if (!browseDone && browseItems.size >= BROWSE_ITEM_CAP) {
                        browseDone = true
                    }
                }
            }
            val blankShown = remember(feedItems, browseItems, browseAnimeIds, enrichedGenres, kindKey, genre) {
                if (kindKey == HOME_KIND_ALL && genre.isBlank()) emptyList()
                else (feedItems + browseItems).map { withTags(it) }.filter { item ->
                    val kindOk = when (kindKey) {
                        HOME_KIND_ALL -> true
                        HOME_KIND_ANIME -> item.looksAnime() || item.uniqueId in browseAnimeIds
                        else -> homeKindKeep(item, kindKey)
                    }
                    kindOk && homeGenreKeep(item, genre)
                }.distinctBy { it.uniqueId }
            }
            val poolHits = remember(blankShown, applied) {
                if (applied.isBlank()) emptyList()
                else blankShown.filter { it.title.contains(applied, ignoreCase = true) }
            }
            val combinedResults = remember(poolHits, shownResults) {
                (poolHits + shownResults).distinctBy { it.uniqueId }
            }
            if (!LocalHideHelp.current) {
                Text(
                    I18n.t("Searching %s only — a result opens straight from here.")
                        .replace("%s", label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
            when {
                applied.isBlank() && kindKey == HOME_KIND_ALL && genre.isBlank() -> EmptyState(
                    title = I18n.t("Search %s").replace("%s", label),
                    subtitle = tr("Type a title — or pick a kind above to browse its catalogue."),
                    actionLabel = null,
                    action = null,
                )
                applied.isBlank() -> {
                    if (blankShown.isEmpty() && (browseLoading || (genre.isNotBlank() && checkingCount > 0))) {
                        Box(
                            Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                val waitLine = when {
                                    genre.isNotBlank() && checkingCount > 0 ->
                                        I18n.t("Checking %s titles…").replace("%s", checkingCount.toString())
                                    browseLoading ->
                                        tr("Loading its catalogue…")
                                    else -> null
                                }
                                if (waitLine != null) {
                                    Text(
                                        waitLine,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 10.dp),
                                    )
                                }
                            }
                        }
                    } else if (blankShown.isEmpty()) {
                        EmptyState(
                            title = tr("Nothing of that kind here"),
                            subtitle = if (genre.isNotBlank()) {
                                tr("This extension tags no titles with this genre — try a search instead.")
                            } else {
                                tr("Its catalogue holds no such titles — try a search instead.")
                            },
                            actionLabel = null,
                            action = null,
                        )
                    } else {
                        Column(Modifier.fillMaxSize()) {
                            Text(
                                I18n.t("%s titles").replace("%s", blankShown.size.toString()) +
                                    if (browseLoading || browseMoreLoading || !browseDone ||
                                        (genre.isNotBlank() && checkingCount > 0)
                                    ) {
                                        "…" + if (genre.isNotBlank() && checkingCount > 0) {
                                            " · " + checkingCount.toString()
                                        } else ""
                                    } else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                            )
                            val browseIdsKey = remember(providerIds) { providerIds.sorted().joinToString(",") }
                            val browseGridKey = remember(browseIdsKey, applied, genre, kindKey) { "homeBrowse:$browseIdsKey:$applied:$genre:$kindKey" }
                            val browseKeys = remember(blankShown) { blankShown.map { it.uniqueId } }
                            val browseGridState = com.hikari.app.ui.components.keptGridState(browseGridKey, itemKeys = browseKeys)
                            val tailIndex = browseGridState.layoutInfo.visibleItemsInfo
                                .lastOrNull()?.index ?: 0
                            LaunchedEffect(tailIndex, blankShown.size, browseDone, browseMoreLoading) {
                                if (blankShown.isNotEmpty() && tailIndex >= blankShown.size - 12) {
                                    loadMoreBrowse()
                                }
                            }
                            LazyVerticalGrid(
                                state = browseGridState,
                                columns = GridCells.Adaptive(minSize = TvUi.gridMinFor(96)),
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(
                                    start = 10.dp,
                                    end = 10.dp,
                                    top = 8.dp,
                                    bottom = LocalTaskbarInset.current + 16.dp,
                                ),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                items(blankShown, key = { it.uniqueId }) { item ->
                                    HomeResultCard(item) { onOpen(item) }
                                }
                            }
                        }
                    }
                }
                combinedResults.isEmpty() && searching -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
                combinedResults.isEmpty() && !searching -> EmptyState(
                    title = tr("No matches"),
                    subtitle = I18n.t("%s has no \"%s\" of that kind — or its site is not answering.")
                        .replace("%s", label).replace("%s", applied),
                    actionLabel = null,
                    action = null,
                    detail = providerIds.firstOrNull()?.let { engineSearchFailureReason(it) },
                )
                else -> {
                    val unique = rememberVisibleItems(combinedResults)
                    val searchIdsKey = remember(providerIds) { providerIds.sorted().joinToString(",") }
                    val searchGridKey = remember(searchIdsKey, applied, kindKey, genre) { "homeSearch:$searchIdsKey:$applied:$kindKey:$genre" }
                    val searchGridKeys = remember(unique) { unique.map { it.uniqueId } }
                    val searchGridState = com.hikari.app.ui.components.keptGridState(searchGridKey, itemKeys = searchGridKeys)
                    LazyVerticalGrid(
                        state = searchGridState,
                        columns = GridCells.Adaptive(minSize = TvUi.gridMinFor(96)),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 10.dp,
                            end = 10.dp,
                            top = 8.dp,
                            // Clear of the floating taskbar (0 when there is no
                            // bar), exactly as the feed's own grid is.
                            bottom = LocalTaskbarInset.current + 16.dp,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(unique, key = { it.uniqueId }) { item ->
                            HomeResultCard(item) { onOpen(item) }
                        }
                    }
                }
            }
        }
    }
}

/** One search result: a 2:3 poster with the title under it — the same cell the
 *  catalog page draws, so results look like a catalog rather than a list. */
@Composable
private fun HomeResultCard(item: MediaItem, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .heroPoster(item.uniqueId),
        ) {
            AsyncImage(
                model = Artwork.model(item),
                contentDescription = item.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        Text(
            item.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
        )
    }
}


internal data class HomeGenre(
    val name: String,
    val genresText: String,
    val keywordsText: String,
)

/** Full-width Genres entry below the hero: an artwork-backed card (like the
 *  reference app's Schedule/Genres pair) opening every genre as image cards
 *  (see GenresOverlay). */
@Composable
private fun HomeGenresButton(onClick: () -> Unit) {
    var art by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val ids = com.hikari.app.data.Genres.tmdbGenreFilter("Action")
        art = GenreArt.artFor("Action", ids, "")
    }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 10.dp)
            .height(64.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (art != null) {
            coil.compose.AsyncImage(
                model = art,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to Color.Black.copy(alpha = 0.72f),
                        0.6f to Color.Black.copy(alpha = 0.45f),
                        1f to Color.Black.copy(alpha = 0.25f),
                    )
                ),
        )
        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.List,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                tr("Genres"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
        }
    }
}

/** The strip is gone: genres live only behind the Genres button now. */
