package com.hikari.app.ui.screens
import com.hikari.app.ui.components.QuickAddToLibrarySheet
import com.hikari.app.ui.components.QuickMenuOverlay
import com.hikari.app.ui.components.quickMenuGesture
import com.hikari.app.ui.components.rememberQuickMenuState
import com.hikari.app.tv.TvUi
import com.hikari.app.i18n.tr

import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.hikari.app.HikariApp
import com.hikari.app.data.CatalogRef
import com.hikari.app.data.MediaItem
import com.hikari.app.data.MetaCache
import com.hikari.app.data.TrackerLibraryRepository
import com.hikari.app.data.MediaType
import com.hikari.app.data.ProviderType
import com.hikari.app.i18n.I18n
import com.hikari.app.manga.MangaProvider
import com.hikari.app.manga.MangaReadState
import com.hikari.app.manga.MangaStore
import com.hikari.app.providers.ContentProvider
import com.hikari.app.providers.IptvProvider
import com.hikari.app.ui.Artwork
import com.hikari.app.ui.PosterLoader
import com.hikari.app.ui.PosterStyle
import com.hikari.app.ui.PosterTypes
import com.hikari.app.ui.RatingBadge
import com.hikari.app.ui.rememberPosterScore
import com.hikari.app.ui.rememberPosterStyle
import com.hikari.app.ui.components.EmptyState
import com.hikari.app.ui.components.GlassSearchField
import com.hikari.app.ui.components.ProviderSettingsDialog
import com.hikari.app.ui.components.VerificationNudge
import com.hikari.app.ui.navigation.Routes
import com.hikari.app.ui.rememberVisibleItems
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A wall's own words (see verifyWanted): only these offer the WebView. */
private val WALL_HINT = Regex(
    "\\b(403|429|503)\\b|cloudflare|just a moment|one moment, please|wsidchk" +
        "|verify you are human|ddos",
    RegexOption.IGNORE_CASE,
)

class CatalogViewModel(
    app: Application,
    private val providerId: String,
    catalogId: String,
    private val catalogName: String,
    private val type: MediaType,
    private val rawType: String,
) : AndroidViewModel(app) {
    private val manager = (app as HikariApp).providers

    private val _catalog = MutableStateFlow(catalogId)
    val catalog: StateFlow<String> = _catalog.asStateFlow()

    /** Shows another of the engine's lists, from the top, with the old list's
     *  items cleared first so a stale card cannot be tapped under the new list. */
    fun switchCatalog(id: String) {
        if (id == _catalog.value) return
        _catalog.value = id
        refresh()
    }

    private val _items = MutableStateFlow<List<MediaItem>>(emptyList())
    val items: StateFlow<List<MediaItem>> = _items.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _done = MutableStateFlow(false)
    val done: StateFlow<Boolean> = _done.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** The query text that produced what is on screen right now. "Typing" and
     *  "searched" have to be told apart, or the grid says "nothing found" while
     *  the debounce is still running. */
    private val _appliedQuery = MutableStateFlow("")
    val appliedQuery: StateFlow<String> = _appliedQuery.asStateFlow()

    private val _reason = MutableStateFlow<String?>(null)
    val reason: StateFlow<String?> = _reason.asStateFlow()

    private var page = 1
    private var loadJob: kotlinx.coroutines.Job? = null
    private var emptyStreak = 0

    /** True on an IPTV channel shelf: it gets cinema cards and its own
     *  in-catalog search (see [IptvProvider.searchInCatalog]) — manga keeps
     *  its engine search, everything else is unchanged. */
    val isIptv: Boolean = manager.byId(providerId) is IptvProvider

    private var trackerAll: List<MediaItem>? = null
    private val TRACKER_PAGE = 60

    private fun providerReason(): String? {
        val p = manager.byId(providerId) ?: return null
        val raw = when (p.config.type) {
            ProviderType.MANGA -> com.hikari.app.manga.MangaProvider.lastOutcome[p.config.id]
            ProviderType.ANIYOMI -> com.hikari.app.aniyomi.AniyomiProvider.catalogErrors[p.config.id]
            ProviderType.SKYSTREAM -> com.hikari.app.skystream.SkyStreamProvider.catalogErrors[p.config.id]
            ProviderType.NUVIO -> com.hikari.app.nuvio.NuvioScraper.catalogErrors[p.config.id]
            ProviderType.STREMIO -> com.hikari.app.providers.StremioAddon.catalogErrors[p.config.id]
            ProviderType.CS3 -> com.hikari.app.cs3.Cs3MainApiProvider.catalogErrors[p.config.id]
            ProviderType.VEGA -> com.hikari.app.providers.vega.VegaProvider.catalogErrors[p.config.id]
            ProviderType.ZANGETSU -> com.hikari.app.zangetsu.ZangetsuProvider.catalogErrors[p.config.id]
            ProviderType.HIKARI -> com.hikari.app.providers.HikariProviderAdapter.catalogErrors[p.config.id]
            ProviderType.ANYMEX -> com.hikari.app.anymex.AnymexProvider.catalogErrors[p.config.id]
                ?: com.hikari.app.anymex.AnymexProvider.lastOutcome[p.config.id]
            ProviderType.ANYMEX_MANGA -> com.hikari.app.anymex.AnymexMangaProvider.lastOutcome[p.config.id]
            ProviderType.SORA -> com.hikari.app.sora.SoraProvider.catalogErrors[p.config.id]
                ?: com.hikari.app.sora.SoraProvider.lastOutcome[p.config.id]
            ProviderType.FORK_VIDEO -> com.hikari.app.kotatsu.fork.ForkVideoProvider.lastOutcome[p.config.id]
            else -> null
        }
        return raw?.takeIf { it.isNotBlank() && !it.startsWith("✓") && !it.startsWith("✔") }
    }

    init {
        loadNext()
    }

    fun setQuery(q: String) {
        if (q == _query.value) return
        _query.value = q
        loadJob?.cancel()
        page = 1
        emptyStreak = 0
        _items.value = emptyList()
        _done.value = false
        _reason.value = null
        _appliedQuery.value = q.trim()
        _loading.value = false
        loadNext()
    }

    /** Loads the next page. Returns true when more pages may exist. */
    fun loadNext() {
        if (_loading.value || _done.value) return
        loadJob?.cancel()
        if (providerId.startsWith("tracker:")) {
            loadTrackerNext()
            return
        }
        _loading.value = true
        val work = com.hikari.app.work.BackgroundWork.begin("Loading $catalogName") {
            loadJob?.cancel()
        }
        loadJob = viewModelScope.launch {
            val provider: ContentProvider? = manager.byId(providerId)
            val ref = CatalogRef(providerId, type, _catalog.value, catalogName, rawType)
            var failed: Throwable? = null
            val fresh = try {
                // A search is paged exactly like the catalog is: the same
                // infinite-scroll effect asks for page 2, and a source with 400
                // matches streams in the way the grid expects. Manga searches
                // the engine; an IPTV shelf searches INSIDE its own tile (see
                // [IptvProvider.searchInCatalog]) — same name/group match as
                // the playlist's in-folder search, scoped to this shelf.
                val iptv = provider as? IptvProvider
                val raw = if (_query.value.isNotBlank() && (rawType == "manga" || iptv != null)) {
                    val q = _query.value.trim()
                    iptv?.searchInCatalog(_catalog.value, q, page)
                        ?: provider?.search(q, page) ?: emptyList()
                } else {
                    com.hikari.app.data.ContentRepository.loadCatalogPage(
                        provider,
                        ref,
                        page,
                        retryEmpty = page == 1,
                        onCached = { cached ->
                            if (page == 1 && cached.isNotEmpty() && _items.value.isEmpty()) {
                                _items.value = cached.map { it.tokenizePoster() }
                                _loading.value = false
                            }
                        },
                    )
                }
                withContext(Dispatchers.IO) { raw.map { it.tokenizePoster() } }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                failed = t
                emptyList()
            }
            if (failed != null) {
                _reason.value = describeFailure(failed!!)
                _loading.value = false
                return@launch
            }
            val translated = if (providerId in com.hikari.app.data.Translator.enabledIds()) {
                com.hikari.app.data.Translator.translateAll(fresh.map { it.title })
                    .mapIndexed { i, t -> if (t != fresh[i].title) fresh[i].copy(title = t) else fresh[i] }
            } else {
                fresh
            }
            if (translated.isEmpty()) {
                if (page > 1 && emptyStreak < 3) {
                    emptyStreak++
                    page++
                    _loading.value = false
                    loadNext()
                    return@launch
                }
                if (page == 1 && _items.value.isEmpty()) {
                    val diskKey = MetaCache.catalogKey(providerId, ref, page)
                    val disk = MetaCache.cachedCatalog(diskKey, Long.MAX_VALUE).orEmpty()
                    if (disk.isNotEmpty()) {
                        _items.value = withContext(Dispatchers.IO) { disk.map { it.tokenizePoster() } }
                        _reason.value = null
                        page++
                        _loading.value = false
                        return@launch
                    }
                }
                _reason.value = providerReason() ?: _reason.value
                _done.value = true
            } else {
                _reason.value = null
                emptyStreak = 0
                val seen = _items.value.map { it.uniqueId }.toMutableSet()
                val merged = _items.value + translated.filter { seen.add(it.uniqueId) }
                _items.value = merged
                page++
            }
            _loading.value = false
        }
        loadJob?.invokeOnCompletion { com.hikari.app.work.BackgroundWork.end(work) }
    }

    private fun loadTrackerNext() {
        if (_loading.value || _done.value) return
        _loading.value = true
        val work = com.hikari.app.work.BackgroundWork.begin("Loading $catalogName") {
            loadJob?.cancel()
        }
        loadJob = viewModelScope.launch {
            try {
                var all = trackerAll
                if (all == null) {
                    val kindKey = providerId.removePrefix("tracker:")
                    val app = getApplication<Application>() as HikariApp
                    val loaded = TrackerLibraryRepository.load(app, kindKey)
                    val shelves = loaded.getOrDefault(emptyList())
                    val shelf = shelves.firstOrNull { it.key == _catalog.value }
                        ?: shelves.firstOrNull { it.title == catalogName }
                    if (shelf == null) {
                        _reason.value = loaded.exceptionOrNull()?.message
                        _done.value = true
                        _loading.value = false
                        return@launch
                    }
                    all = shelf.items
                    trackerAll = all
                }
                val q = _query.value.trim()
                val filtered = if (q.isBlank()) all
                else all.filter {
                    it.title.contains(q, ignoreCase = true) ||
                        it.searchTitle.contains(q, ignoreCase = true)
                }
                val from = (page - 1) * TRACKER_PAGE
                val slice = filtered.drop(from).take(TRACKER_PAGE)
                if (slice.isEmpty()) {
                    if (page == 1) {
                        _reason.value = null
                    }
                    _done.value = true
                } else {
                    val tokenized = withContext(Dispatchers.IO) { slice.map { it.tokenizePoster() } }
                    _items.value = _items.value + tokenized
                    page++
                    _reason.value = null
                    if (from + slice.size >= filtered.size) _done.value = true
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _reason.value = describeFailure(t)
                _done.value = true
            }
            _loading.value = false
        }
        loadJob?.invokeOnCompletion { com.hikari.app.work.BackgroundWork.end(work) }
    }

    /** "ClassName: message" from the ROOT cause of [t] — what the empty state
     *  shows when a load failed. See [_reason]. */
    private fun describeFailure(t: Throwable): String {
        var c: Throwable = t
        while (c.cause != null && c.cause !== c) c = c.cause!!
        return c::class.java.simpleName + (c.message?.let { ": $it" } ?: "")
    }

    fun refresh() {
        page = 1
        emptyStreak = 0
        trackerAll = null
        _items.value = emptyList()
        _done.value = false
        _reason.value = null
        loadNext()
    }
}

private fun MediaItem.tokenizePoster(): MediaItem {
    val p = PosterLoader.tokenize(posterUrl)
    val b = PosterLoader.tokenize(backdropUrl)
    return if (p == posterUrl && b == backdropUrl) this
    else copy(posterUrl = p, backdropUrl = b)
}

@Composable
fun CatalogScreen(
    nav: NavHostController,
    providerId: String,
    catalogId: String,
    catalogName: String,
    providerName: String,
    type: MediaType,
    rawType: String,
) {
    val app = LocalContext.current.applicationContext as Application
    val vm: CatalogViewModel = viewModel(
        key = "$providerId|$catalogId|$type",
        factory = viewModelFactory {
            initializer {
                CatalogViewModel(
                    app,
                    providerId, catalogId, catalogName, type, rawType
                )
            }
        }
    )
    val items by vm.items.collectAsState()
    val loading by vm.loading.collectAsState()
    val done by vm.done.collectAsState()
    val searchable = rawType == "manga"
    val iptvShelfSearch = vm.isIptv && rawType == "channel"
    val catalogSearch = searchable || iptvShelfSearch
    val appliedQuery by vm.appliedQuery.collectAsState()
    val catalogReason by vm.reason.collectAsState()
    val selectedCatalog by vm.catalog.collectAsState()
    val popularLabel = tr("Popular")
    val latestLabel = tr("Latest")
    val mangaPair = searchable &&
        (selectedCatalog == MangaProvider.CATALOG_POPULAR ||
            selectedCatalog == MangaProvider.CATALOG_LATEST)
    // The name of the list actually on screen — which is not the one the page was
    // opened with once a tab has been tapped.
    val shownName = when (selectedCatalog) {
        MangaProvider.CATALOG_POPULAR -> popularLabel
        MangaProvider.CATALOG_LATEST -> latestLabel
        else -> catalogName
    }
    // The box's text is local and debounced (a search clears the grid, so
    // asking the source on every keystroke would blank it while typing).
    var typedQuery by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(typedQuery) {
        if (!catalogSearch) return@LaunchedEffect
        // Long enough that a typed word is one request, short enough that the
        // grid feels like it follows the keyboard.
        delay(400)
        vm.setQuery(typedQuery)
    }
    val hikari = app as HikariApp
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val verifyWanted = remember(providerId, catalogReason) {
        val prov = hikari.providers.byId(providerId)
        if (prov?.config?.type == ProviderType.MANGA) {
            com.hikari.app.manga.MangaProvider.isWallOutcome(providerId)
        } else {
            val r = catalogReason
            r == null || WALL_HINT.containsMatchIn(r)
        }
    }
    val verifyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        vm.refresh()
    }
    // One way in for both the header button and the empty state's, so they can
    // never drift apart (and so the reasoning below is written once).
    val openVerify: () -> Unit = {
        scope.launch {
            val site = withContext(Dispatchers.IO) {
                hikari.providers.byId(providerId)?.let { webUrlChainAsync(it) }
            }
            if (site.isNullOrBlank()) {
                android.widget.Toast.makeText(
                    context,
                    I18n.t("Couldn't determine this extension's site"),
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            } else {
                val host = runCatching { java.net.URI(site).host?.lowercase() }.getOrNull()
                verifyLauncher.launch(
                    android.content.Intent(context, com.hikari.app.web.WebViewActivity::class.java)
                        .apply {
                            putExtra("url", site)
                            putExtra("title", "Verify: " + (host ?: providerName))
                            putExtra("providerId", providerId)
                            putExtra("autoCloseWhenCloudflarePassed", true)
                            if (host != null) putExtra("verifyHost", host)
                        }
                )
            }
        }
    }

    val catalogKeys = remember(items) { items.distinctBy { it.uniqueId }.map { it.uniqueId } }
    val gridState = com.hikari.app.ui.components.keptGridState(
        "catalog:$providerId|$catalogId|$type|$selectedCatalog|$appliedQuery",
        itemKeys = catalogKeys,
        onNeedMore = { if (!done && !loading) vm.loadNext() },
    )
    val isTvCatalog = com.hikari.app.tv.TvMode.current()
    val iptvCinema = vm.isIptv
    // Phone cinema cards are 16:9 and need room: two columns, not three.
    val phoneCinema = !isTvCatalog && (iptvCinema || rememberPosterStyle().isCinema(false))
    val columns = if (isTvCatalog) { val w = LocalConfiguration.current.screenWidthDp; if (w >= 1400) 4 else 3 } else if (phoneCinema) 2 else TvUi.gridColumns(catalogColumnsFor(LocalConfiguration.current.screenWidthDp))
    LaunchedEffect(gridState) {
        snapshotFlow {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val total = gridState.layoutInfo.totalItemsCount
            last to total
        }.collect { (last, total) ->
            if (last >= total - 3 && !done && !loading) {
                vm.loadNext()
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { nav.popBackStack() }) {
                Icon(Icons.Filled.ArrowBack, contentDescription = tr("Back"))
            }
            Spacer(Modifier.weight(1f))
            val settingsProvider = remember(providerId) { hikari.providers.byId(providerId) }
            val settingsType = settingsProvider?.config?.type
            if (settingsType == ProviderType.NUVIO || settingsType == ProviderType.VEGA ||
                settingsType == ProviderType.ANIYOMI || settingsType == ProviderType.MANGA ||
                settingsType == ProviderType.ANYMEX || settingsType == ProviderType.ANYMEX_MANGA ||
                settingsType == ProviderType.SORA || settingsType == ProviderType.SKYSTREAM
            ) {
                var showProviderSettings by remember { mutableStateOf(false) }
                IconButton(onClick = { showProviderSettings = true }) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = tr("Extension settings"),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (showProviderSettings && settingsProvider != null) {
                    ProviderSettingsDialog(
                        provider = settingsProvider,
                        onDismiss = { showProviderSettings = false },
                    )
                }
            }
            if (searchable && verifyWanted) {
                IconButton(onClick = openVerify) {
                    Icon(
                        Icons.Filled.Public,
                        contentDescription = tr("Open the site to pass its Cloudflare check"),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        // Television opens on the shared cinematic header; the phone keeps
        // its own title block.
        if (com.hikari.app.tv.TvMode.current()) {
            com.hikari.app.tv.TvScreenHeader(
                title = tr(shownName),
                subtitle = providerName.takeIf { it.isNotBlank() },
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 6.dp)
        ) {
            if (!com.hikari.app.tv.TvMode.current()) {
            Text(
                tr(shownName),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            }
            if (!com.hikari.app.tv.TvMode.current() && providerName.isNotBlank()) {
                Text(
                    providerName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (mangaPair) {
                Row(
                    Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CatalogTab(popularLabel, selectedCatalog == MangaProvider.CATALOG_POPULAR) {
                        vm.switchCatalog(MangaProvider.CATALOG_POPULAR)
                    }
                    CatalogTab(latestLabel, selectedCatalog == MangaProvider.CATALOG_LATEST) {
                        vm.switchCatalog(MangaProvider.CATALOG_LATEST)
                    }
                }
            }
        }
        if (catalogSearch) {
            GlassSearchField(
                value = typedQuery,
                onValueChange = { typedQuery = it },
                placeholder = if (iptvShelfSearch) I18n.t("Search channels…")
                else I18n.t("Search %s…").replace("%s", tr(shownName)),
                height = 46.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
            )
        }
        if (items.isEmpty() && loading) {
            Box(Modifier.fillMaxSize()) {
                CatalogSkeletonGrid(columns = columns, cinema = iptvCinema || rememberPosterStyle().isCinema(com.hikari.app.tv.TvMode.current()))
                VerificationNudge(
                    waiting = true,
                    onOpenWebView = openVerify,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 32.dp),
                )
            }
        } else if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    title = if (appliedQuery.isNotBlank()) tr("No matches")
                    else tr("Nothing here right now"),
                    subtitle = if (appliedQuery.isNotBlank())
                        if (iptvShelfSearch) tr("No channels here match that search.")
                        else I18n.t("This engine has no \"%s\" — it may also be blocking Hikari.").replace("%s", appliedQuery)
                    else if (searchable && verifyWanted) tr("The site may be blocking or down.") +
                        " " + tr("If the site shows a Cloudflare check, open it and pass it once.")
                    else tr("The site answered with an error — see below."),
                    actionLabel = if (searchable && verifyWanted) tr("Verify site") else null,
                    action = if (searchable && verifyWanted) openVerify else null,
                    detail = catalogReason,
                )
            }
        } else {
            val uniqueItems = rememberVisibleItems(items)
            val style = rememberPosterStyle().let {
                if (iptvCinema) it.copy(posterType = PosterTypes.CINEMA) else it
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    horizontal = if (com.hikari.app.tv.TvMode.current()) 20.dp else 16.dp,
                    vertical = 12.dp
                ),
                horizontalArrangement = Arrangement.spacedBy(if (isTvCatalog) 14.dp else 12.dp),
                verticalArrangement = Arrangement.spacedBy(if (isTvCatalog) 20.dp else 18.dp),
            ) {
                items(uniqueItems, key = { it.uniqueId }) { item ->
                    if (isTvCatalog) {
                        if (style.isCinema(true)) {
                            com.hikari.app.tv.TvCinemaCard(item = item, onClick = {
                                Routes.safeNavigate(
                                    nav,
                                    if (rawType == "manga") Routes.mangaRoute(item.providerId, item.id, item.title, item.posterUrl)
                                    else Routes.detail(item.providerId, item.type, item.id, item.title, item.posterUrl, item.rawType)
                                )
                            })
                        } else {
                            com.hikari.app.tv.TvVerticalCard(item = item, onClick = {
                                Routes.safeNavigate(
                                    nav,
                                    if (rawType == "manga") Routes.mangaRoute(item.providerId, item.id, item.title, item.posterUrl)
                                    else Routes.detail(item.providerId, item.type, item.id, item.title, item.posterUrl, item.rawType)
                                )
                            })
                        }
                    } else {
                    CatalogCard(
                        item,
                        style,
                        onClick = {
                            Routes.safeNavigate(
                                nav,
                                if (rawType == "manga") {
                                    Routes.mangaRoute(
                                        item.providerId, item.id, item.title, item.posterUrl
                                    )
                                } else {
                                    Routes.detail(
                                        item.providerId, item.type, item.id,
                                        item.title, item.posterUrl, item.rawType
                                    )
                                }
                            )
                        },
                        onQuickPlay = { quick ->
                            // Manga rows open the reader's detail (no video to
                            // autoplay); video rows arrive already wanting
                            // playback (see DetailScreen autoplay).
                            Routes.safeNavigate(
                                nav,
                                if (rawType == "manga") {
                                    Routes.mangaRoute(
                                        quick.providerId, quick.id, quick.title, quick.posterUrl
                                    )
                                } else {
                                    Routes.detail(
                                        quick.providerId, quick.type, quick.id,
                                        quick.title, quick.posterUrl, quick.rawType,
                                        autoplay = true,
                                    )
                                }
                            )
                        },
                    )
                    }
                }
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (loading) CircularProgressIndicator(Modifier.width(28.dp))
                        else if (done && items.isNotEmpty()) {
                            Text(
                                tr("That's everything"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else if (!done && items.isNotEmpty() && catalogReason != null) {
                            TextButton(onClick = { vm.loadNext() }) {
                                Text(
                                    tr("Couldn't load more — retry"),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CatalogCard(
    item: MediaItem,
    style: PosterStyle,
    onClick: () -> Unit,
    /** Hold-menu play action (detail autoplay for video); plain open when null. */
    onQuickPlay: ((MediaItem) -> Unit)? = null,
) {
    val badge = rememberPosterScore(item, style)
    val shape = RoundedCornerShape(style.corner.dp)
    val cinema = style.isCinema(com.hikari.app.tv.TvMode.current())
    val hero = if (cinema) Artwork.heroModel(item) else null
    // 1-second-hold quick menu (info / play / library / close) — see PosterQuickMenu.
    val quickMenu = rememberQuickMenuState()
    val latestClick by rememberUpdatedState(onClick)
    val latestPlay by rememberUpdatedState(onQuickPlay)
    val latestItem by rememberUpdatedState(item)
    // Hold-menu Library pick: the title the sheet files (null = no sheet).
    var quickLibraryItem by remember { mutableStateOf<MediaItem?>(null) }
    Column(
        Modifier
            .clickable(onClick = onClick)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(if (cinema) 16f / 9f else 2f / 3f)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .quickMenuGesture(quickMenu) { slot ->
                    when (slot) {
                        0 -> latestClick()
                        1 -> {
                            val play = latestPlay
                            if (play != null) play(item) else latestClick()
                        }
                        // Slot 2 opens the "which categories?" sheet (the same
                        // one the detail screen's heart opens); slot 3 (Close)
                        // dismisses with no action.
                        2 -> quickLibraryItem = latestItem
                        else -> {}
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Movie,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.40f),
                modifier = Modifier.size(26.dp),
            )
            if (cinema && hero != null && !hero.second) {
                AsyncImage(
                    model = hero.first,
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    alignment = com.hikari.app.ui.Artwork.CINEMA_ALIGNMENT
                )
            } else {
                AsyncImage(
                    model = if (cinema) hero?.first else Artwork.model(item),
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    alignment = if (cinema) com.hikari.app.ui.Artwork.CINEMA_ALIGNMENT else Alignment.Center
                )
            }
            if (badge != null) {
                RatingBadge(
                    text = badge,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp),
                )
            }
            if (quickMenu.visible) QuickMenuOverlay(quickMenu.selected)
        }
        quickLibraryItem?.let { picked ->
            QuickAddToLibrarySheet(item = picked, onDismiss = { quickLibraryItem = null })
        }
        if (style.showTitles) {
            Text(
                item.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp)
            )
            val detail = buildList {
                item.year?.let { add(it.toString()) }
                if (item.genres.isNotEmpty()) add(item.genres.first())
            }.joinToString(" • ")
            Text(
                detail.ifBlank { " " },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp, start = 2.dp, end = 2.dp)
            )
            if (item.rawType == "manga" || item.rawType == "novel") {
                val mangaRev = remember { mutableIntStateOf(0) }
                DisposableEffect(item.providerId, item.id) {
                    val l: () -> Unit = { mangaRev.intValue++ }
                    MangaStore.addListener(l)
                    onDispose { MangaStore.removeListener(l) }
                }
                val readState = remember(mangaRev.intValue, item.providerId, item.id) {
                    MangaStore.catalogReadState(item.providerId + "|" + item.id)
                }
                if (readState != MangaReadState.NONE) {
                    val done = readState == MangaReadState.COMPLETED
                    Text(
                        if (done) tr("Completed") else tr("Reading"),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (done) androidx.compose.ui.graphics.Color(0xFF81C784)
                        else MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp, start = 2.dp, end = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CatalogSkeletonGrid(columns: Int, modifier: Modifier = Modifier, cinema: Boolean = com.hikari.app.tv.TvMode.current()) {
    val transition = rememberInfiniteTransition(label = "catalog-skeleton")
    val pulse by transition.animateFloat(
        initialValue = 0.30f,
        targetValue = 0.65f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "catalog-skeleton-pulse",
    )
    // Television skeleton boxes read elevated-black (desktop language).
    val tvSkel = com.hikari.app.tv.TvMode.current()
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        userScrollEnabled = false,
    ) {
        // Television skeleton boxes read elevated-black (desktop language).
        items(columns * 3) {
            Column(Modifier.alpha(pulse)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(if (cinema) 16f / 9f else 2f / 3f)
                        .clip(RoundedCornerShape(if (tvSkel) 8.dp else 14.dp))
                        .background(
                            if (tvSkel) com.hikari.app.tv.TvDesktop.SurfaceElevated
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                )
                Box(
                    Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth(0.85f)
                        .height(12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (tvSkel) com.hikari.app.tv.TvDesktop.SurfaceElevated
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                )
            }
        }
    }
}

private fun catalogColumnsFor(widthDp: Int): Int = when {
    widthDp >= 1400 -> 7
    widthDp >= 1200 -> 6
    widthDp >= 1000 -> 5
    widthDp >= 840 -> 4
    else -> 3
}
