package com.hikari.app.ui.screens
import com.hikari.app.tv.TvMode
import com.hikari.app.tv.TvSelectProviderDialog
import com.hikari.app.tv.TvDesktop
import com.hikari.app.tv.TvUi
import com.hikari.app.i18n.tr
import com.hikari.app.i18n.trTag
import com.hikari.app.i18n.I18n
import com.hikari.app.ui.components.GlassSearchField

import androidx.activity.compose.BackHandler
import android.app.Application
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.hikari.app.HikariApp
import com.hikari.app.data.ContentRepository
import com.hikari.app.data.MediaItem
import com.hikari.app.data.MediaType
import com.hikari.app.data.ProviderType
import com.hikari.app.data.RepoProvenance
import com.hikari.app.providers.ContentProvider
import com.hikari.app.ui.Artwork
import com.hikari.app.ui.PosterLoader
import com.hikari.app.ui.RatingBadge
import com.hikari.app.ui.components.EmptyState
import com.hikari.app.ui.components.GlassSearchField
import com.hikari.app.ui.components.StaggerIn
import com.hikari.app.ui.components.HeroFlight
import com.hikari.app.ui.components.flightSource
import com.hikari.app.ui.navigation.LocalTaskbarInset
import com.hikari.app.ui.navigation.Routes
import com.hikari.app.ui.rememberPosterScore
import com.hikari.app.ui.rememberPosterStyle
import com.hikari.app.ui.rememberVisibleItems
import com.hikari.app.ui.components.ChoiceDialog
import com.hikari.app.ui.components.ChoiceItem
import com.hikari.app.ui.components.MultiChoiceDialog
import com.hikari.app.tv.tvPress
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
class SearchViewModel(
    app: Application,
    private val savedState: SavedStateHandle,
) : AndroidViewModel(app) {
    private val manager = (app as HikariApp).providers
    private val repo = ContentRepository(manager)

    private val hikari: HikariApp = app as HikariApp

    /** The user's own collections — the local half of a search. */
    private val collections = com.hikari.app.data.CollectionsRepository(manager)

    val userCollections: Flow<List<com.hikari.app.data.Collection>> =
        (app as HikariApp).store.collectionsFlow()

    private val _query = MutableStateFlow(savedState.get<String>("query") ?: "")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _submitted = MutableStateFlow((savedState.get<String>("submitted") ?: "").trim())
    val submitted: StateFlow<String> = _submitted.asStateFlow()

    fun submit(q: String) {
        val t = q.trim()
        _submitted.value = t
        savedState["submitted"] = t
    }

    private val _selectedProviders =
        MutableStateFlow(savedState.get<ArrayList<String>>("providers")?.toSet() ?: emptySet())
    val selectedProviders: StateFlow<Set<String>> = _selectedProviders.asStateFlow()

    val providers: StateFlow<List<ContentProvider>> = manager.providers

    val results: StateFlow<List<MediaItem>> = SearchSession.results
    val searching: StateFlow<Boolean> = SearchSession.searching

    private val _collectionHits =
        MutableStateFlow<List<com.hikari.app.data.CollectionsRepository.CollectionHit>>(emptyList())
    val collectionHits: StateFlow<List<com.hikari.app.data.CollectionsRepository.CollectionHit>> =
        _collectionHits.asStateFlow()

    val history: Flow<List<String>> = hikari.store.searchHistoryFlow()

    /** Forgets one remembered search — the ✕ on its chip. */
    fun forgetSearch(query: String) {
        viewModelScope.launch {
            runCatching { hikari.store.removeSearchHistory(query) }
        }
    }

    /** Forgets every remembered search ("Clear all"). */
    fun forgetAllSearches() {
        viewModelScope.launch {
            runCatching { hikari.store.clearSearchHistory() }
        }
    }

    init {
        viewModelScope.launch {
            combine(_submitted, _selectedProviders) { q, sel ->
                q to sel
            }
                .collectLatest { (q, selection) ->
                    if (q.isBlank()) {
                        _collectionHits.value = emptyList()
                        SearchSession.clear()
                        return@collectLatest
                    }
                    viewModelScope.launch {
                        runCatching { hikari.store.addSearchHistory(q) }
                    }
                    // The catalog lookup needs at least two characters (see
                    // [CollectionsRepository.searchIn]); below that it answers
                    // nothing and never calls back, so the previous query's hits
                    // would stay under a one-letter query.
                    if (q.trim().length < 2) _collectionHits.value = emptyList()
                    val extensionIds = selection.filterNot { Routes.isCollectionScope(it) }
                        .toSet()
                    val catalogIds = selection.filter { Routes.isCollectionScope(it) }
                        .map { Routes.collectionIdOfScope(it) }
                        .toSet()
                    val sweepExtensions = selection.isEmpty() || extensionIds.isNotEmpty()
                    kotlinx.coroutines.coroutineScope {
                        launch {
                            val cap = if (catalogIds.isEmpty()) 24 else 60
                            runCatching {
                                collections.searchIn(q, catalogIds, cap) { partial ->
                                    _collectionHits.value = partial
                                }
                            }.onSuccess { _collectionHits.value = it }
                        }
                        launch {
                            if (sweepExtensions) SearchSession.search(repo, q, extensionIds)
                            else SearchSession.clear()
                        }
                    }
                }
        }
    }

    private val _suggestions = MutableStateFlow<List<SearchSuggestion>>(emptyList())
    val suggestions: StateFlow<List<SearchSuggestion>> = _suggestions.asStateFlow()

    init {
        viewModelScope.launch {
            _query.debounce(200).distinctUntilChanged().collectLatest { q ->
                if (q.trim().length < 2) {
                    _suggestions.value = emptyList()
                    return@collectLatest
                }
                // fetch() swallows network errors itself and rethrows only
                // cancellation, so a newer keystroke never blanks the list.
                _suggestions.value = SearchSuggestionsApi.fetch(q)
            }
        }
    }

    fun setQuery(q: String) {
        _query.value = q
        savedState["query"] = q
        // An emptied box also clears the search, so old results never sit
        // under a blank query.
        if (q.isBlank()) submit("")
    }

    /** "All sources" — empty set means every provider. */
    fun selectAll() {
        _selectedProviders.value = emptySet()
        savedState["providers"] = ArrayList<String>()
    }

    /** Selects exactly one provider — used by Home's "Search this extension"
     *  entry point, which scopes the search to the catalog you were browsing. */
    fun selectProvider(id: String) {
        if (id.isBlank()) return
        _selectedProviders.value = setOf(id)
        savedState["providers"] = ArrayList(listOf(id))
    }

    /** The whole selection at once — what the provider picker's Done button
     *  saves (a multi pick). An empty list is "All sources" rather than "search
     *  nothing", which is the same meaning an empty selection has everywhere
     *  else in this screen. */
    fun setProviders(keys: List<String>) {
        val next = keys.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (next.isEmpty()) {
            selectAll()
            return
        }
        _selectedProviders.value = next.toSet()
        savedState["providers"] = ArrayList(next)
    }
}

@Composable
fun SearchScreen(
    nav: NavHostController,
    initialQuery: String = "",
    initialProvider: String = "",
) {
    val vm: SearchViewModel = viewModel()
    val query by vm.query.collectAsState()
    val results by vm.results.collectAsState()
    val searching by vm.searching.collectAsState()
    val collectionHits by vm.collectionHits.collectAsState()
    val selected by vm.selectedProviders.collectAsState()
    val providers by vm.providers.collectAsState()
    val history by vm.history.collectAsState(initial = emptyList())
    val suggestions by vm.suggestions.collectAsState()
    val submitted by vm.submitted.collectAsState()
    // `initial` is required for a plain Flow (a StateFlow carries its own), and
    // it doubles as "no catalogs yet" for the first frame.
    val collections by vm.userCollections.collectAsState(initial = emptyList())

    var translatedFrom by remember { mutableStateOf<String?>(null) }
    var targetLang by rememberSaveable { mutableStateOf("zh-CN") }
    var langMenu by remember { mutableStateOf(false) }
    var translating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // True while the user is typing: the suggestion list is shown. Picking a
    // suggestion or pressing the keyboard's search key closes it.
    var suggestOpen by remember { mutableStateOf(false) }

    // Television overscan: sets that crop the frame (~5-10% on every edge)
    // cut whatever the results grid draws against it — the "posters are cut
    // ~10% and scrolling only trades one cut row for the next" report. The
    // grid keeps this clear of every edge on TV only (see [TvUi] overscan);
    // phones are untouched, and a set that shows the full frame keeps the
    // user's 0.
    val context = LocalContext.current
    val searchFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val searchIsTv = TvMode.current()
    // Keyboard on EVERY visit: the old one-shot flag survived tab switches
    // (saved state), so the keyboard only ever opened the first time. The
    // lifecycle observer re-runs on each return to this tab instead.
    val searchLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(searchLifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && !searchIsTv) {
                runCatching {
                    searchFocus.requestFocus()
                    keyboard?.show()
                }
            }
        }
        searchLifecycle.lifecycle.addObserver(observer)
        onDispose { searchLifecycle.lifecycle.removeObserver(observer) }
    }
    // Results show only for a SUBMITTED search (the box text equals what was
    // submitted): phone and television alike. While typing, the suggestion list
    // is what shows; the keyboard's search key or a picked suggestion submits.
    val resultsMode = query.isNotBlank() && query.trim() == submitted
    val overscanFlow = remember(context) {
        (context.applicationContext as HikariApp).store.tvOverscanFlow()
    }
    val overscanDp by overscanFlow.collectAsState(initial = TvUi.DEFAULT_OVERSCAN_DP)
    val tvEdge = if (TvMode.current()) overscanDp.dp else 0.dp

    var kindFilterKey by rememberSaveable { mutableStateOf("all") }
    val kindFilter = SearchKindFilter.fromKey(kindFilterKey)

    var yearsKey by rememberSaveable { mutableStateOf("") }
    val yearsFilter: Set<Int> = remember(yearsKey) {
        yearsKey.split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it > 1800 }.toSet()
    }
    fun setYears(next: Set<Int>) {
        yearsKey = next.sortedDescending().joinToString(",")
    }
    fun toggleYear(year: Int) {
        setYears(if (year in yearsFilter) yearsFilter - year else yearsFilter + year)
    }
    var genreKey by rememberSaveable { mutableStateOf("") }
    val genreFilter: Set<String> = remember(genreKey) {
        genreKey.split(',').mapNotNull { it.trim().takeIf { g -> g.isNotEmpty() } }.toSet()
    }
    fun setGenres(next: Set<String>) {
        genreKey = next.sorted().joinToString(",")
    }
    fun toggleGenre(genre: String) {
        val key = genre.trim().lowercase()
        if (key.isEmpty()) return
        setGenres(if (key in genreFilter) genreFilter - key else genreFilter + key)
    }
    // ---- Discover-style filter state (lives in the filter sheet now) ----
    var showFilterSheet by remember { mutableStateOf(false) }
    var showGenreDialog by remember { mutableStateOf(false) }
    var upcomingKey by rememberSaveable { mutableStateOf(true) }
    var minVoteKey by rememberSaveable { mutableFloatStateOf(0f) }
    var minVotesKey by rememberSaveable { mutableStateOf(0) }
    var langKey by rememberSaveable { mutableStateOf("") }
    // Production-company browse (TMDB with_companies) + genre explore browse.
    // Either one switches the results area to TMDB-backed browsing.
    var companyKey by rememberSaveable { mutableStateOf("") }
    var exploreGenreKey by rememberSaveable { mutableStateOf("") }
    val tmdbMode = companyKey.isNotBlank() || exploreGenreKey.isNotBlank()
    var companyResolved by remember { mutableStateOf<Pair<String, SearchDiscovery.ResolvedCompany?>?>(null) }
    LaunchedEffect(companyKey) {
        companyResolved = if (companyKey.isBlank()) null
        else companyKey to SearchDiscovery.resolveCompany(companyKey)
    }
    // Typing a query leaves TMDB browsing and returns to provider search.
    LaunchedEffect(query) {
        if (query.isNotBlank() && (companyKey.isNotBlank() || exploreGenreKey.isNotBlank())) {
            companyKey = ""
            exploreGenreKey = ""
        }
    }
    // A filter is only "on" when it can actually hide something.
    val filterOn = kindFilter != SearchKindFilter.ALL ||
        yearsFilter.isNotEmpty() || genreFilter.isNotEmpty() ||
        !upcomingKey || minVoteKey > 0f || minVotesKey > 0 || langKey.isNotBlank() ||
        companyKey.isNotBlank() || exploreGenreKey.isNotBlank()
    val filtered = remember(results, kindFilter, yearsKey, genreKey, upcomingKey, minVoteKey) {
        if (!filterOn) results
        else results.filter {
            it.passesSearchFilter(kindFilter, yearsFilter, genreFilter, upcomingKey) &&
                (minVoteKey <= 0f || (it.rating ?: Double.MAX_VALUE) >= minVoteKey.toDouble())
        }
    }
    val keptUnknownKind = remember(results, kindFilter, yearsKey, genreKey) {
        if (!filterOn) 0
        else results.count { it.unknownKindKept(kindFilter, yearsFilter, genreFilter) }
    }
    val hiddenNoYear = remember(results, kindFilter, yearsKey, genreKey) {
        // Only the ones the KIND filter would have shown: an item dropped by
        // "Movies" is not hidden because it has no year.
        if (yearsFilter.isEmpty()) 0 else results.count {
            it.year == null && it.passesSearchFilter(kindFilter, emptySet(), genreFilter)
        }
    }
    val hiddenNoGenre = remember(results, kindFilter, yearsKey, genreKey) {
        // The same rule, for the genre strip: a result whose source said no
        // genre at all cannot be presented as one of the picked genres.
        if (genreFilter.isEmpty()) 0 else results.count {
            it.genres.isEmpty() && it.passesSearchFilter(kindFilter, yearsFilter, emptySet())
        }
    }

    val engineOf = remember(providers) {
        providers.associate { it.config.id to it.config.type.groupLabel }
    }
    // The engine picked, by its label; empty = every engine.
    var engineKey by rememberSaveable { mutableStateOf("") }
    val engineCounts = remember(filtered, engineOf) {
        val counts = HashMap<String, Int>()
        for (item in filtered) {
            val label = engineOf[item.providerId] ?: continue
            counts[label] = (counts[label] ?: 0) + 1
        }
        // Most hits first, so the strip leads with where the results are.
        counts.entries.sortedByDescending { it.value }.associate { it.key to it.value }
    }
    val activeEngine = engineKey.takeIf { engineCounts.containsKey(it) }.orEmpty()
    val visible = remember(filtered, activeEngine, engineOf) {
        if (activeEngine.isBlank()) filtered
        else filtered.filter { engineOf[it.providerId] == activeEngine }
    }
    LaunchedEffect(engineCounts, engineKey) {
        if (engineKey.isNotBlank() && !engineCounts.containsKey(engineKey)) engineKey = ""
    }

    // The name of the one selected source, when exactly one is picked — an
    // extension, or one of the user's catalogs. It is what makes the search box
    // say "Search in abc…" instead of "Search in 1 selected provider", so a
    // scoped search announces its scope by NAME (the report was that a catalog
    // could not be picked here at all; saying which one is picked is half of
    // making that legible).
    val soleName = remember(selected, providers, collections) {
        if (selected.size != 1) return@remember null
        val key = selected.first()
        if (Routes.isCollectionScope(key)) {
            collections.firstOrNull { it.id == Routes.collectionIdOfScope(key) }?.name
        } else {
            providers.firstOrNull { it.config.id == key }?.config?.name
        }
    }

    LaunchedEffect(Unit) {
        if (initialQuery.isNotBlank()) { vm.setQuery(initialQuery); vm.submit(initialQuery) }
        if (initialProvider.isNotBlank()) vm.selectProvider(initialProvider)
    }

    // ---- TMDB-backed browsing (production grid + genre explore cards) ----
    // Pages 1-3 load first; "Load more" below appends further pages until an
    // endpoint comes back short (TMDB caps discover at page 500), so a big
    // studio like Netflix is never cut off after the first pages.
    var tmdbItems by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var tmdbExtra by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var tmdbLoading by remember { mutableStateOf(false) }
    var tmdbLoadingMore by remember { mutableStateOf(false) }
    var tmdbNextPage by remember { mutableStateOf(4) }
    var tmdbMaybeMore by remember { mutableStateOf(true) }
    fun currentBrowseQuery(): BrowseQuery? {
        if (companyKey.isBlank() && exploreGenreKey.isBlank()) return null
        if (companyKey.isNotBlank() && companyResolved?.first != companyKey) return null
        val genreIds = when {
            kindFilter == SearchKindFilter.ANIME -> "16"
            exploreGenreKey.isNotBlank() ->
                com.hikari.app.data.Genres.tmdbGenreFilter(exploreGenreKey)
            else -> ""
        }
        val movie = kindFilter == SearchKindFilter.ALL || kindFilter == SearchKindFilter.MOVIES
        val tv = kindFilter == SearchKindFilter.ALL || kindFilter == SearchKindFilter.SERIES ||
            kindFilter == SearchKindFilter.ANIME
        val cid = if (companyKey.isNotBlank()) (companyResolved?.second?.id ?: -1) else 0
        return BrowseQuery(genreIds, cid, movie, tv)
    }
    fun loadMoreBrowse() {
        val q = currentBrowseQuery() ?: return
        if (tmdbLoading || tmdbLoadingMore || !tmdbMaybeMore || q.cid < 0) return
        tmdbLoadingMore = true
        scope.launch {
            val pages = (tmdbNextPage..minOf(tmdbNextPage + 1, 500)).toList()
            val res = SearchDiscovery.browsePages(
                q.genreIds, q.cid, q.movie, q.tv,
                minVoteKey.toDouble(), minVotesKey, langKey, pages,
            )
            val seen = HashSet<String>()
            (tmdbItems + tmdbExtra).forEach { seen.add(it.type.name + "|" + it.id) }
            tmdbExtra = tmdbExtra + res.items.filter { seen.add(it.type.name + "|" + it.id) }
            tmdbMaybeMore = res.maybeMore && tmdbNextPage + 1 < 500
            tmdbNextPage += 2
            tmdbLoadingMore = false
        }
    }
    LaunchedEffect(companyResolved, exploreGenreKey, kindFilter, minVoteKey, minVotesKey, langKey) {
        tmdbExtra = emptyList()
        tmdbNextPage = 4
        tmdbMaybeMore = true
        tmdbLoadingMore = false
        if (companyKey.isBlank() && exploreGenreKey.isBlank()) {
            tmdbItems = emptyList()
            tmdbLoading = false
            return@LaunchedEffect
        }
        if (companyKey.isNotBlank() && companyResolved?.first != companyKey) {
            // Company id still resolving; keep the old list up meanwhile.
            tmdbLoading = true
            return@LaunchedEffect
        }
        tmdbLoading = true
        val q = currentBrowseQuery()
        if (q == null || (companyKey.isNotBlank() && q.cid <= 0)) {
            tmdbItems = emptyList()
            tmdbMaybeMore = false
        } else {
            val res = SearchDiscovery.browsePages(
                q.genreIds, q.cid, q.movie, q.tv,
                minVoteKey.toDouble(), minVotesKey, langKey, listOf(1, 2, 3),
            )
            tmdbItems = res.items
            tmdbMaybeMore = res.maybeMore
        }
        tmdbLoading = false
    }
    val tmdbAll = remember(tmdbItems, tmdbExtra) { tmdbItems + tmdbExtra }
    val tmdbVisible = remember(tmdbAll, kindFilter, yearsKey, genreKey, upcomingKey, minVoteKey) {
        tmdbAll.filter {
            it.passesSearchFilter(kindFilter, yearsFilter, genreFilter, upcomingKey) &&
                (minVoteKey <= 0f || (it.rating ?: Double.MAX_VALUE) >= minVoteKey.toDouble())
        }
    }

    // ---- Idle landing rails (trending + genre art, session-cached) ----
    var trending by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var trySearchingChips by remember { mutableStateOf(SearchDiscovery.TRY_SEARCHING) }
    val genreArt = remember { mutableStateMapOf<String, List<MediaItem>>() }
    LaunchedEffect(Unit) {
        trending = SearchDiscovery.trending()
        trySearchingChips = SearchDiscovery.trySearching()
        for (g in SearchDiscovery.EXPLORE_GENRES) {
            genreArt[g] = SearchDiscovery.genreArt(g)
        }
    }

    // Labels the filter sheet shows (were pills on the slim row before).
    val scopeSheetLabel = when {
        selected.isEmpty() -> tr("All providers")
        soleName != null -> soleName
        else -> I18n.t("%s sources").replace("%s", selected.size.toString())
    }
    val genreSheetLabel = tr("Genre") + ": " + when {
        genreFilter.isEmpty() -> tr("Any")
        genreFilter.size == 1 -> trTag(genreFilter.first())
        else -> I18n.t("%s picked").replace("%s", genreFilter.size.toString())
    }

    // Opens a result the same way everywhere (provider rows, TMDB rails).
    val openSearchItem: (MediaItem) -> Unit = { item ->
        Routes.safeNavigate(
            nav,
            if (item.rawType == "manga") {
                Routes.mangaRoute(item.providerId, item.id, item.title, item.posterUrl)
            } else {
                Routes.detail(item.providerId, item.type, item.id, item.title, item.posterUrl, item.rawType)
            },
        )
    }

    // One tap EN -> target language (Chinese by default), tap again to restore
    // the original English the user typed. The source language is whatever the
    // user wrote, auto-detected by the translator, so the same button also
    // turns a Chinese title back into English when it was already translated.
    fun toggleTranslate() {
        if (translating) return
        suggestOpen = false
        val restore = translatedFrom
        if (restore != null) {
            vm.setQuery(restore)
            vm.submit(restore)
            translatedFrom = null
            return
        }
        val src = query.trim()
        if (src.isEmpty()) return
        scope.launch {
            translating = true
            val out = com.hikari.app.data.Translator.translateTo(src, targetLang)
            translating = false
            if (out.isNotBlank() && out != src) {
                translatedFrom = src
                vm.setQuery(out)
                vm.submit(out)
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding()
    ) {
        if (TvMode.current()) {
            com.hikari.app.tv.TvScreenHeader(title = tr("Search"), subtitle = if (selected.isEmpty()) tr("All providers") else if (soleName != null) soleName else tr("%s sources").replace("%s", selected.size.toString()))
        }
    var showProviders by remember { mutableStateOf(false) }
    var providerKind by remember { mutableStateOf<ProviderType?>(null) }
        GlassSearchField(
            value = query,
            onValueChange = { vm.setQuery(it); translatedFrom = null; suggestOpen = true },
            placeholder = when {
                selected.isEmpty() -> "Search across all providers…"
                soleName != null -> "Search in $soleName…"
                else -> "Search in ${selected.size} selected sources…"
            },
            focusRequester = searchFocus,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                imeAction = androidx.compose.ui.text.input.ImeAction.Search,
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                onSearch = {
                    suggestOpen = false
                    vm.submit(vm.query.value)
                    keyboard?.hide()
                },
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = if (com.hikari.app.tv.TvMode.current()) 20.dp else 16.dp,
                    vertical = if (com.hikari.app.tv.TvMode.current()) 10.dp else 8.dp
                ),
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
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
                                    "Show original English" else "Translate to $targetLang",
                                tint = if (translatedFrom != null) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    // Discover-style filters, one tap from the search bar.
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .tvPress { showFilterSheet = true }
                            .pointerInput(Unit) {
                                detectTapGestures(onTap = { showFilterSheet = true })
                            }
                            .padding(10.dp)
                    ) {
                        Box {
                            Icon(
                                Icons.Filled.Tune,
                                contentDescription = tr("Filters"),
                                tint = if (filterOn) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                            if (filterOn) {
                                Box(
                                    Modifier
                                        .align(Alignment.TopEnd)
                                        .size(8.dp)
                                        .background(
                                            MaterialTheme.colorScheme.primary,
                                            CircleShape,
                                        )
                                )
                            }
                        }
                    }
                    DropdownMenu(
                        expanded = langMenu,
                        onDismissRequest = { langMenu = false },
                    ) {
                        com.hikari.app.data.Translator.LANGUAGES.forEach { (code, label) ->
                            DropdownMenuItem(
                                text = {
                                    Text(if (code == targetLang) "$label  ✓" else label)
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
            }
        )
        if (!searchIsTv && query.isBlank() && history.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    tr("Recent searches"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { vm.forgetAllSearches() }) {
                    Text(tr("Clear all"), style = MaterialTheme.typography.labelSmall)
                }
            }
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 2.dp),
            ) {
                items(history.distinct(), key = { it }) { past ->
                    HistoryChip(
                        text = past,
                        onPick = {
                            translatedFrom = null
                            suggestOpen = false
                            vm.setQuery(past)
                            vm.submit(past)
                        },
                        onDelete = { vm.forgetSearch(past) },
                    )
                }
            }
        }
        // Provider-picker state, hoisted above the two blocks that use it: the
        // dialogs above AND the one-line filter row below both open it.
        if (providers.isNotEmpty() || collections.isNotEmpty()) {
            if (showProviders) {
                if (searchIsTv) {
                    TvSelectProviderDialog(
                        providers = providers,
                        collections = collections,
                        selection = selected.toList(),
                        onPickAll = { showProviders = false; vm.selectAll() },
                        onPick = { key -> showProviders = false; vm.selectProvider(key) },
                        onDismiss = { showProviders = false },
                        onSave = { keys -> showProviders = false; vm.setProviders(keys) },
                    )
                } else {
                    ProviderPickerSheet(
                        providers = providers,
                        collections = collections,
                        selection = selected.toList(),
                        filter = providerKind,
                        onFilter = { providerKind = it },
                        onManageCollections = {
                            showProviders = false
                            Routes.safeNavigate(nav, Routes.COLLECTIONS)
                        },
                        // A plain tap picks exactly one source (the usual case: "search
                        // inside this extension"); the sheet's hold-for-multi mode and
                        // its Done button save the whole set.
                        onPick = { key ->
                            showProviders = false
                            if (key == null) vm.selectAll() else vm.selectProvider(key)
                        },
                        onDone = { keys ->
                            showProviders = false
                            vm.setProviders(keys)
                        },
                        onDismiss = { showProviders = false },
                    )
                }
            }
        }
        // ---- Discover-style filters: everything lives in the sheet now ----
        if (showFilterSheet) {
            SearchFilterSheet(
                scopeLabel = scopeSheetLabel,
                onOpenProviders = { showProviders = true },
                kindKey = kindFilterKey,
                onKindChange = { kindFilterKey = it },
                genreLabel = genreSheetLabel,
                genreActive = genreFilter.isNotEmpty(),
                onOpenGenres = { showFilterSheet = false; showGenreDialog = true },
                pickedGenreKeys = genreFilter,
                onToggleGenre = { toggleGenre(it) },
                years = yearsFilter,
                currentYear = CURRENT_YEAR,
                onSetYears = { setYears(it) },
                upcoming = upcomingKey,
                onUpcomingChange = { upcomingKey = it },
                minVote = minVoteKey,
                onMinVoteChange = { minVoteKey = it },
                minVotes = minVotesKey,
                onMinVotesChange = { minVotesKey = it },
                lang = langKey,
                onLangChange = { langKey = it },
                companyName = companyKey.ifBlank { null },
                onSelectCompany = { name ->
                    companyKey = name.orEmpty()
                    if (!name.isNullOrBlank()) {
                        exploreGenreKey = ""
                        vm.setQuery("")
                        vm.submit("")
                    }
                },
                resultCount = if (tmdbMode) {
                    if (tmdbLoading && tmdbAll.isEmpty()) null else tmdbVisible.size
                } else if (resultsMode) visible.size else null,
                onClearAll = {
                    kindFilterKey = "all"
                    setYears(emptySet())
                    setGenres(emptySet())
                    upcomingKey = true
                    minVoteKey = 0f
                    minVotesKey = 0
                    langKey = ""
                    companyKey = ""
                    exploreGenreKey = ""
                },
                onDismiss = { showFilterSheet = false },
            )
        }
        if (showGenreDialog) {
            MultiChoiceDialog(
                title = tr("Genre"),
                items = SEARCH_GENRES.map { ChoiceItem(key = it.trim().lowercase(), label = trTag(it)) },
                selectedKeys = genreFilter,
                onToggle = { key -> toggleGenre(key) },
                onDismiss = { showGenreDialog = false },
                searchable = true,
                searchPlaceholder = tr("Search genres"),
                headerContent = {
                    TextButton(onClick = { setGenres(emptySet()) }) { Text(tr("Clear")) }
                },
            )
        }
        if ((filterOn || activeEngine.isNotBlank()) && results.isNotEmpty() && resultsMode) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
                    Text(
                        if (visible.isEmpty()) {
                            tr("Nothing in these results matches the filter.")
                        } else {
                            tr("%s shown").replace("%s", visible.size.toString()) +
                                " · " +
                                tr("%s found").replace("%s", results.size.toString())
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (visible.isNotEmpty() && keptUnknownKind > 0) {
                        Text(
                            tr("%s have no year or kind, so they are kept")
                                .replace("%s", keptUnknownKind.toString()),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (hiddenNoYear > 0) {
                        Text(
                            tr("%s have no year, so they are hidden")
                                .replace("%s", hiddenNoYear.toString()),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (hiddenNoGenre > 0) {
                        Text(
                            tr("%s have no genre, so they are hidden")
                                .replace("%s", hiddenNoGenre.toString()),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        if (searching) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        // Type-ahead suggestions replace the results area while typing (phones
        // only); a tap on one, or the keyboard's search key, brings the results back.
        val showSuggestions = suggestOpen && query.trim().length >= 2
        if (showSuggestions) {
            val typedText = query.trim()
            val pickSuggestion: (String) -> Unit = { title ->
                translatedFrom = null
                suggestOpen = false
                vm.setQuery(title)
                vm.submit(title)
                keyboard?.hide()
            }
            if (searchIsTv) {
                // The typed text always leads, so one press of OK runs exactly
                // what was typed; the title suggestions follow it.
                TvSearchSuggestionList(
                    suggestions = listOf(
                        SearchSuggestion(typedText, isSeries = false, isTypedText = true),
                    ) + suggestions.filter { !it.title.equals(typedText, ignoreCase = true) },
                    onPick = pickSuggestion,
                    modifier = Modifier.weight(1f),
                )
            } else {
                SearchSuggestionList(
                    // Nothing found for what is typed (a typo, a half-word): offer the
                    // typed text itself as the one row, so the list is never blank.
                    suggestions = if (suggestions.isEmpty()) {
                        listOf(SearchSuggestion(typedText, isSeries = false, isTypedText = true))
                    } else {
                        suggestions
                    },
                    onPick = pickSuggestion,
                    onFill = { title ->
                        translatedFrom = null
                        vm.setQuery(title)
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        } else if (tmdbMode) {
            // TMDB-backed browsing: production grid picks and genre explore
            // cards land here, filtered by the sheet. Pages 1-3 load first;
            // the footer keeps appending further pages until TMDB runs dry.
            val browseTitle = if (companyKey.isNotBlank()) companyKey
            else exploreGenreKey + " " + tr("movies & shows")
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            browseTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!tmdbLoading) {
                            Text(
                                I18n.t("%s titles").replace("%s", tmdbVisible.size.toString()) +
                                    if (tmdbMaybeMore && tmdbVisible.isNotEmpty()) " · " + tr("more available") else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (tmdbLoading) {
                        CircularProgressIndicator(
                            Modifier
                                .padding(end = 8.dp)
                                .size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    TextButton(onClick = { companyKey = ""; exploreGenreKey = "" }) {
                        Text(tr("Clear"))
                    }
                }
                if (!tmdbLoading && tmdbVisible.isEmpty()) {
                    EmptyState(
                        title = tr("No results"),
                        subtitle = tr("Nothing matched these filters. Loosen the rating, year or language filter."),
                        actionLabel = null,
                        action = null,
                    )
                } else {
                    val browseCols = if (TvMode.current()) TvUi.resultsColumns() else 3
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(browseCols),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentPadding = PaddingValues(
                            start = 10.dp + tvEdge,
                            end = 10.dp + tvEdge,
                            top = 8.dp + tvEdge,
                            bottom = LocalTaskbarInset.current + 12.dp + tvEdge,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(tmdbVisible, key = { it.uniqueId }) { item ->
                            PhoneSearchCell(
                                item = item,
                                onOpen = { openSearchItem(item) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        if (!tmdbLoading && tmdbMaybeMore && tmdbVisible.isNotEmpty()) {
                            item(span = { GridItemSpan(browseCols) }) {
                                BrowseLoadMoreFooter(
                                    loading = tmdbLoadingMore,
                                    shown = tmdbVisible.size,
                                    onLoad = { loadMoreBrowse() },
                                )
                            }
                        }
                    }
                }
            }
        } else if (!resultsMode || (query.isBlank() && results.isEmpty() && collectionHits.isEmpty())) {
            IdleSearchLanding(
                trending = trending,
                trySearching = trySearchingChips,
                genreArt = genreArt,
                onTrySearch = { title ->
                    translatedFrom = null
                    suggestOpen = false
                    vm.setQuery(title)
                    vm.submit(title)
                    keyboard?.hide()
                },
                onOpenItem = openSearchItem,
                onExploreGenre = { genre ->
                    exploreGenreKey = genre
                    vm.setQuery("")
                    vm.submit("")
                },
            )
        } else if (!searching && results.isEmpty() && collectionHits.isEmpty()) {
            EmptyState(
                title = tr("No results"),
                subtitle = I18n.t("Nothing matched \"$query\". Try a different title, or deselect providers in the row above."),
                actionLabel = null,
                action = null
            )
        } else {
            val namesById = remember(providers) {
                providers.associateBy({ it.config.id }, { it.config.name })
            }
            Column(Modifier.fillMaxSize()) {
                if (engineCounts.size >= 2 || activeEngine.isNotBlank()) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(bottom = 4.dp),
                    ) {
                        item {
                            EngineChip(
                                label = tr("All") + " · " + filtered.size,
                                selected = activeEngine.isBlank(),
                                onClick = { engineKey = "" },
                            )
                        }
                        items(engineCounts.entries.toList(), key = { it.key }) { entry ->
                            EngineChip(
                                label = entry.key + " · " + entry.value,
                                selected = activeEngine == entry.key,
                                // Tapping the one in force clears it, like the
                                // picker's own engine chips.
                                onClick = {
                                    engineKey = if (activeEngine == entry.key) "" else entry.key
                                },
                            )
                        }
                    }
                }
                if (collectionHits.isNotEmpty()) {
                    CollectionHitsRow(
                        if (filterOn) {
                            collectionHits.filter {
                                it.item.passesSearchFilter(kindFilter, yearsFilter, genreFilter)
                            }
                        } else {
                            collectionHits
                        }
                    ) { hit ->
                        Routes.safeNavigate(
                            nav,
                            Routes.detail(
                                hit.item.providerId,
                                hit.item.type,
                                hit.item.id,
                                hit.item.title,
                                hit.item.posterUrl,
                                hit.item.rawType,
                            )
                        )
                    }
                }
                val style = rememberPosterStyle()
                val tvSearch = TvMode.current()
                val searchCols = if (tvSearch) {
                    // Settings -> Customize -> Poster cards: bigger cards, fewer columns.
                    com.hikari.app.ui.PosterSizing.scaledColumns(6, com.hikari.app.ui.PosterSizing.factor())
                } else TvUi.resultsColumns()
                val tvCell = if (tvSearch) {
                    val sw = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp
                    val avail = sw - com.hikari.app.tv.TvUi.RAIL_WIDTH - 20.dp - tvEdge * 2
                    ((avail - 12.dp * (searchCols - 1)) / searchCols).coerceAtLeast(96.dp)
                } else 0.dp
                val typesById = remember(providers) {
                    providers.associateBy({ it.config.id }, { it.config.type.groupLabel })
                }
                val searchApp = LocalContext.current.applicationContext as HikariApp
                val searchRepos by remember { searchApp.store.reposFlow() }
                    .collectAsState(initial = emptyList())
                val repoTagById = remember(providers, searchRepos) {
                    RepoProvenance.nameMap(providers.map { it.config }, searchRepos)
                }
                val groupedVisible = remember(visible) { visible.groupBy { it.providerId } }
                var expandedProvider by rememberSaveable { mutableStateOf<String?>(null) }
                val effectiveExpanded = if (tvSearch) null
                    else expandedProvider?.takeIf { groupedVisible.containsKey(it) }
                BackHandler(enabled = !tvSearch && effectiveExpanded != null) {
                    expandedProvider = null
                }
                val gridSource = if (effectiveExpanded != null) groupedVisible.getValue(effectiveExpanded) else visible
                val gridItems = rememberVisibleItems(gridSource)
                val searchKeys = remember(gridItems) { gridItems.map { it.uniqueId } }
                val gridStateKey = if (tvSearch) "search:$query|$activeEngine"
                    else "search:$query|$activeEngine|$effectiveExpanded"
                if (!tvSearch && effectiveExpanded == null) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentPadding = PaddingValues(
                            bottom = LocalTaskbarInset.current + 12.dp,
                        ),
                    ) {
                        groupedVisible.forEach { (pid, hits) ->
                            val groupName = namesById[pid] ?: pid
                            val groupTag = listOf(typesById[pid], repoTagById[pid])
                                .filterNotNull().filter { it.isNotBlank() }
                                .distinct().joinToString(" · ")
                            item(key = "grp|$pid") {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(start = 16.dp, end = 8.dp, top = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            groupName,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (groupTag.isNotBlank()) {
                                            Text(
                                                groupTag,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                    TextButton(onClick = { expandedProvider = pid }) {
                                        Text(tr("See all") + " (${hits.size})")
                                    }
                                }
                            }
                            item(key = "row|$pid") {
                                // Settings -> Customize -> Poster cards: 112dp at "Default".
                                val searchCellWidth = (112f * com.hikari.app.ui.PosterSizing.factor()).dp
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    items(hits, key = { it.uniqueId }) { item ->
                                        PhoneSearchCell(
                                            item = item,
                                            onOpen = { openSearchItem(item) },
                                            modifier = Modifier.width(searchCellWidth),
                                            heroNamespace = "searchRow:$pid",
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    if (!tvSearch && effectiveExpanded != null) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 4.dp, end = 16.dp, top = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(onClick = { expandedProvider = null }) {
                                Text("‹ " + tr("All providers"))
                            }
                            Text(
                                namesById[effectiveExpanded] ?: effectiveExpanded,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                groupedVisible.getValue(effectiveExpanded).size.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    LazyVerticalGrid(
                    columns = GridCells.Fixed(searchCols),
                    state = com.hikari.app.ui.components.keptGridState(
                        gridStateKey,
                        itemKeys = searchKeys,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                contentPadding = PaddingValues(
                    start = 10.dp + tvEdge,
                    end = 10.dp + tvEdge,
                    top = 8.dp + tvEdge,
                    bottom = LocalTaskbarInset.current + 12.dp + tvEdge +
                        if (TvMode.current()) 28.dp else 0.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(if (tvSearch) 12.dp else 8.dp),
                verticalArrangement = Arrangement.spacedBy(if (tvSearch) 16.dp else 10.dp)
            ) {
                itemsIndexed(gridItems, key = { _, it -> it.uniqueId }) { index, item ->
                    StaggerIn(index = index) {
                    if (tvSearch) {
                        val go = {
                            Routes.safeNavigate(
                                nav,
                                if (item.rawType == "manga") Routes.mangaRoute(item.providerId, item.id, item.title, item.posterUrl)
                                else Routes.detail(item.providerId, item.type, item.id, item.title, item.posterUrl, item.rawType)
                            )
                        }
                        if (style.isCinema(true)) {
                            com.hikari.app.tv.TvCinemaCard(item = item, onClick = go, cardWidth = tvCell)
                        } else {
                            com.hikari.app.tv.TvVerticalCard(item = item, onClick = go, cardWidth = tvCell)
                        }
                    } else {
                    val badge = rememberPosterScore(item, style)
                    Column(
                        Modifier
                            .clickable {
                                Routes.safeNavigate(
                                    nav,
                                    if (item.rawType == "manga") {
                                        Routes.mangaRoute(
                                            item.providerId, item.id, item.title, item.posterUrl
                                        )
                                    } else {
                                        Routes.detail(item.providerId, item.type, item.id, item.title, item.posterUrl, item.rawType)
                                    }
                                )
                            }
                    ) {
                        val phoneCinema = style.isCinema(false)
                        val hero = if (phoneCinema) Artwork.heroModel(item) else null
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(if (phoneCinema) 16f / 9f else 2f / 3f)
                                .clip(RoundedCornerShape(10.dp))
                        ) {
                            if (phoneCinema && hero != null && !hero.second) {
                                AsyncImage(
                                    model = hero.first,
                                    contentDescription = item.title,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop,
                                    alignment = com.hikari.app.ui.Artwork.CINEMA_ALIGNMENT
                                )
                            } else {
                                AsyncImage(
                                    model = if (phoneCinema) hero?.first else Artwork.model(item),
                                    contentDescription = item.title,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop,
                                    alignment = if (phoneCinema) com.hikari.app.ui.Artwork.CINEMA_ALIGNMENT else Alignment.Center
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
                        }
                        Text(
                            item.title,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = if (TvMode.current()) 1 else 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        if (!TvMode.current()) namesById[item.providerId]?.let { name ->
                            Text(
                                name,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    }
                    }
                }
            }
            }
            }
        }
    }
}

@Composable
private fun CollectionHitsRow(
    hits: List<com.hikari.app.data.CollectionsRepository.CollectionHit>,
    onOpen: (com.hikari.app.data.CollectionsRepository.CollectionHit) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            tr("From your collections"),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 6.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(hits.distinctBy { it.item.uniqueId + "|" + it.label }, key = { it.item.uniqueId + "|" + it.label }) { hit ->
                Column(
                    Modifier
                        .width(104.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onOpen(hit) }
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(10.dp))
                    ) {
                        AsyncImage(
                            model = Artwork.model(hit.item),
                            contentDescription = hit.item.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    }
                    Text(
                        hit.item.title,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        hit.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** One TMDB browse query (production and/or genre): the first pages load in the
 *  effect, further pages append through [loadMoreBrowse] in the grid footer. */
private data class BrowseQuery(
    val genreIds: String,
    val cid: Int,
    val movie: Boolean,
    val tv: Boolean,
)

/** Which kind of result the grid keeps: everything, only films, only shows, or
 *  only anime. */
private enum class SearchKindFilter(val key: String) {
    ALL("all"),
    MOVIES("movies"),
    SERIES("series"),
    ANIME("anime");

    companion object {
        fun fromKey(key: String?): SearchKindFilter =
            entries.firstOrNull { it.key == key } ?: ALL
    }
}

private val SEARCH_GENRES: List<String> = com.hikari.app.data.Genres.ALL

fun MediaItem.looksAnime(): Boolean {
    if (rawType.equals("anime", true)) return true
    if (genres.any { g ->
        val t = g.trim().lowercase()
        t == "anime" || t == "animation" || t == "animated" || t == "cartoon" ||
            t.contains("anime") || t.contains("cartoon")
    }) return true
    val p = providerId.trim().lowercase()
    if (p.startsWith("aniyomi|")) return true
    if (p.contains("anime")) return true
    return p.contains("/anime/") || p.contains("|anime|") || p.endsWith("|anime")
}

private val SEARCH_YEARS: List<Int> = run {
    val thisYear = runCatching {
        java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
    }.getOrDefault(2025)
    ((thisYear + 1) downTo 1950).toList()
}

/**
 * One year in the search filter strip: small, thin and tappable, drawn like the
 * provider chips around it so the strip reads as one control.
 */
@Composable
private fun FilterSelectPill(
    label: String,
    active: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
        else MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Tune,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(5.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
@Composable
private fun YearChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
        else MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.75f))
        else null,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
        )
    }
}

/** One genre in the genre strip — the same chip as [YearChip], so the two
 *  strips read as one control (which is how the kind chips and the year strip
 *  already read). */
@Composable
private fun GenreChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
        else MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.75f))
        else null,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
        )
    }
}

/** One engine in the result strip: the same chip as [YearChip], so the strip
 *  reads as part of the same control family as the filters above it. */
@Composable
private fun EngineChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
        else MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.75f))
        else null,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun HistoryChip(
    text: String,
    onPick: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        onClick = onPick,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 170.dp),
            )
            Box(
                Modifier
                    .padding(start = 2.dp)
                    .size(26.dp)
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onDelete),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = I18n.t("Forget \"%s\"").replace("%s", text),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

@Composable
private fun PhoneSearchCell(
    item: MediaItem,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    /** Provider-row namespace for the shared-element key (see PosterCard):
     *  the same title can sit under several providers at once. */
    heroNamespace: String = "",
) {
    val style = rememberPosterStyle()
    val badge = rememberPosterScore(item, style)
    // Snapshot flight (see HeroFlight): same key shape as PosterCard.
    val flightKey = if (heroNamespace.isBlank()) item.uniqueId else heroNamespace + ":" + item.uniqueId
    Column(modifier.clickable { HeroFlight.arm(flightKey, Artwork.model(item)); onOpen() }) {
        val phoneCinema = style.isCinema(false)
        val hero = if (phoneCinema) Artwork.heroModel(item) else null
        // Lookahead-crash guard: the aspect sizer stays outer; the shared hero
        // travels on an inner full-size node (see PosterCard).
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(if (phoneCinema) 16f / 9f else 2f / 3f)
                .clip(RoundedCornerShape(10.dp))
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .flightSource(flightKey)
            ) {
            if (phoneCinema && hero != null && !hero.second) {
                AsyncImage(
                    model = com.hikari.app.ui.PosterLoader.cellModel(hero.first as? String, 640, 360) ?: hero.first,
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    alignment = com.hikari.app.ui.Artwork.CINEMA_ALIGNMENT
                )
            } else {
                AsyncImage(
                    model = if (phoneCinema) {
                        val u = hero?.first as? String
                        com.hikari.app.ui.PosterLoader.cellModel(u, 640, 360) ?: hero?.first
                    } else {
                        val u = Artwork.model(item) as? String
                        com.hikari.app.ui.PosterLoader.cellModel(u, 360, 540) ?: Artwork.model(item)
                    },
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    alignment = if (phoneCinema) com.hikari.app.ui.Artwork.CINEMA_ALIGNMENT else Alignment.Center
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
            }
        }
        // Shrink-to-fit: the name shows in full, never cut mid-word.
        var cellTitleSize by remember(item.title) { mutableFloatStateOf(12f) }
        Text(
            item.title,
            style = MaterialTheme.typography.bodySmall,
            fontSize = cellTitleSize.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { r ->
                if (r.hasVisualOverflow && cellTitleSize > 8f) cellTitleSize -= 1f
            },
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

private val CURRENT_YEAR: Int = runCatching {
    java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
}.getOrDefault(2025)

private fun MediaItem.passesSearchFilter(
    kind: SearchKindFilter,
    years: Set<Int>,
    genres: Set<String>,
    upcoming: Boolean = true,
): Boolean {
    if (years.isNotEmpty() && (year == null || year !in years)) return false
    if (!upcoming && year != null && year > CURRENT_YEAR) return false
    if (genres.isNotEmpty() && genres.none { q -> this.genres.any { g -> com.hikari.app.data.Genres.matches(g, q) } }) return false
    return when (kind) {
        SearchKindFilter.ALL -> true
        SearchKindFilter.MOVIES -> type != MediaType.SERIES && !looksAnime()
        SearchKindFilter.SERIES -> type != MediaType.MOVIE && !looksAnime()
        SearchKindFilter.ANIME -> looksAnime()
    }
}

/** True when this result is SHOWN only because the provider did not say what it
 *  is (an unknown kind under a Movie/Series filter — see
 *  [MediaItem.passesSearchFilter]). */
private fun MediaItem.unknownKindKept(
    kind: SearchKindFilter,
    years: Set<Int>,
    genres: Set<String>,
    upcoming: Boolean = true,
): Boolean {
    if (kind == SearchKindFilter.ALL) return false
    return type == MediaType.UNKNOWN && passesSearchFilter(kind, years, genres, upcoming)
}

/** Landing content on an empty search box: "Try searching" chips, the
 *  trending-today rail and genre explore cards (all TMDB-backed). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IdleSearchLanding(
    trending: List<MediaItem>,
    trySearching: List<String>,
    genreArt: Map<String, List<MediaItem>>,
    onTrySearch: (String) -> Unit,
    onOpenItem: (MediaItem) -> Unit,
    onExploreGenre: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            bottom = LocalTaskbarInset.current + 12.dp,
        ),
    ) {
        item {
            LandingSectionTitle(tr("Try searching"))
            FlowRow(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (s in trySearching) {
                    Surface(
                        onClick = { onTrySearch(s) },
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                    ) {
                        Text(
                            s,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
        if (trending.isNotEmpty()) {
            item {
                LandingSectionTitle(tr("Trending today"))
                val cellWidth = (112f * com.hikari.app.ui.PosterSizing.factor()).dp
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(trending, key = { it.uniqueId }) { item ->
                        PhoneSearchCell(
                            item = item,
                            onOpen = { onOpenItem(item) },
                            modifier = Modifier.width(cellWidth),
                            heroNamespace = "trending",
                        )
                    }
                }
            }
        }
        item {
            LandingSectionTitle(tr("Explore genres"))
            Column(
                Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                for (row in SearchDiscovery.EXPLORE_GENRES.chunked(2)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        for (g in row) {
                            GenreExploreCard(
                                genre = g,
                                art = genreArt[g],
                                onOpenItem = onOpenItem,
                                onExplore = { onExploreGenre(g) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        repeat(2 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun LandingSectionTitle(text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(4.dp)
                .height(18.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Browse-grid footer: keeps appending TMDB pages until an endpoint runs dry. */
@Composable
private fun BrowseLoadMoreFooter(loading: Boolean, shown: Int, onLoad: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.5.dp)
        } else {
            OutlinedButton(onClick = onLoad, shape = RoundedCornerShape(50)) {
                Text(I18n.t("Load more (%s so far)").replace("%s", shown.toString()))
            }
        }
    }
}

/** One genre card: fanned posters over a tinted tile, name below the art. */
@Composable
private fun GenreExploreCard(
    genre: String,
    /** Null while still loading (spinner); empty when loaded-but-bare, which
     *  draws a monogram tile instead of spinning forever. */
    art: List<MediaItem>?,
    onOpenItem: (MediaItem) -> Unit,
    onExplore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.clickable { onExplore() }) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 10f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
        ) {
            val posters = art.orEmpty().take(3)
            if (art == null) {
                CircularProgressIndicator(
                    Modifier
                        .align(Alignment.Center)
                        .size(24.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                if (posters.isNotEmpty()) {
                    Row(Modifier.fillMaxSize()) {
                        for ((i, p) in posters.withIndex()) {
                            Box(
                                Modifier
                                    .weight(1f)
                                    .fillMaxSize()
                                    .padding(
                                        start = if (i == 0) 0.dp else 3.dp,
                                        top = (i * 7).dp,
                                        bottom = ((posters.size - 1 - i) * 7).dp,
                                    )
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onOpenItem(p) },
                            ) {
                                AsyncImage(
                                    model = p.backdropUrl ?: p.posterUrl,
                                    contentDescription = p.title,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop,
                                )
                            }
                        }
                        if (posters.size < 3) {
                            repeat(3 - posters.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                } else {
                    Text(
                        genre.take(1).uppercase(),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.45f to androidx.compose.ui.graphics.Color.Transparent,
                            1f to androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f),
                        ),
                    ),
            )
            Text(
                genre,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = androidx.compose.ui.graphics.Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 10.dp, bottom = 8.dp, end = 10.dp),
            )
        }
    }
}
