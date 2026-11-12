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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.hikari.app.ui.components.heroPoster
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
    // A filter is only "on" when it can actually hide something.
    val filterOn = kindFilter != SearchKindFilter.ALL ||
        yearsFilter.isNotEmpty() || genreFilter.isNotEmpty()
    val filtered = remember(results, kindFilter, yearsKey, genreKey) {
        if (!filterOn) results
        else results.filter { it.passesSearchFilter(kindFilter, yearsFilter, genreFilter) }
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

    Column(Modifier.fillMaxSize()) {
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
                                    "Show original English" else "Translate to $targetLang",
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
        run {
            // ---- Every filter in ONE slim row ----
            // Type/year/genre are compact selector pills — NOT one pill per
            // option. Each opens its own box (like the provider pill opens
            // the provider box); the endless horizontal option strip is gone.
            var showTypeFilter by remember { mutableStateOf(false) }
            var showYearFilter by remember { mutableStateOf(false) }
            var showGenreFilter by remember { mutableStateOf(false) }
            val typeLabel = tr("Type") + ": " + when (kindFilter) {
                SearchKindFilter.MOVIES -> tr("Movies")
                SearchKindFilter.SERIES -> tr("Series")
                SearchKindFilter.ANIME -> tr("Anime")
                else -> tr("Both")
            }
            val yearLabel = tr("Year") + ": " + when {
                yearsFilter.isEmpty() -> tr("Any")
                yearsFilter.size <= 2 -> yearsFilter.sortedDescending().joinToString(", ")
                else -> tr("%s years").replace("%s", yearsFilter.size.toString())
            }
            val genreLabel = tr("Genre") + ": " + when {
                genreFilter.isEmpty() -> tr("Any")
                genreFilter.size == 1 -> trTag(genreFilter.first())
                else -> I18n.t("%s picked").replace("%s", genreFilter.size.toString())
            }
            if (!searchIsTv) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                item {
                    // The provider scope as one pill: a tap opens the same
                    // picker the old button opened.
                    val scopeLabel = when {
                        selected.isEmpty() -> tr("All providers")
                        soleName != null -> soleName
                        else -> I18n.t("%s sources").replace("%s", selected.size.toString())
                    }
                    Surface(
                        onClick = { showProviders = true },
                        shape = RoundedCornerShape(50),
                        color = if (selected.isNotEmpty()) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
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
                                scopeLabel,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                            )
                            Icon(
                                Icons.Filled.KeyboardArrowDown,
                                contentDescription = tr("Choose providers"),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
                item {
                    FilterSelectPill(
                        label = typeLabel,
                        active = kindFilter != SearchKindFilter.ALL,
                        contentDescription = tr("Choose type"),
                        onClick = { showTypeFilter = true },
                    )
                }
                item {
                    FilterSelectPill(
                        label = yearLabel,
                        active = yearsFilter.isNotEmpty(),
                        contentDescription = tr("Choose years"),
                        onClick = { showYearFilter = true },
                    )
                }
                item {
                    FilterSelectPill(
                        label = genreLabel,
                        active = genreFilter.isNotEmpty(),
                        contentDescription = tr("Choose genres"),
                        onClick = { showGenreFilter = true },
                    )
                }
            }
            } else {
                // Television: the same four pills in one line, so the poster
                // grid below gets the room. Each opens the same picker box.
                val tvScopeLabel = when {
                    selected.isEmpty() -> tr("All providers")
                    soleName != null -> soleName
                    else -> I18n.t("%s sources").replace("%s", selected.size.toString())
                }
                TvSearchFilterRow(
                    showScope = providers.isNotEmpty() || collections.isNotEmpty(),
                    scopeLabel = tvScopeLabel,
                    scopeActive = selected.isNotEmpty(),
                    onScope = { showProviders = true },
                    typeLabel = typeLabel,
                    typeActive = kindFilter != SearchKindFilter.ALL,
                    onType = { showTypeFilter = true },
                    yearLabel = yearLabel,
                    yearActive = yearsFilter.isNotEmpty(),
                    onYear = { showYearFilter = true },
                    genreLabel = genreLabel,
                    genreActive = genreFilter.isNotEmpty(),
                    onGenre = { showGenreFilter = true },
                )
            }
            if (showTypeFilter) {
                ChoiceDialog(
                    title = tr("Type"),
                    items = listOf(
                        ChoiceItem(key = SearchKindFilter.ALL.key, label = tr("Both")),
                        ChoiceItem(key = SearchKindFilter.MOVIES.key, label = tr("Movies")),
                        ChoiceItem(key = SearchKindFilter.SERIES.key, label = tr("Series")),
                        ChoiceItem(key = SearchKindFilter.ANIME.key, label = tr("Anime")),
                    ),
                    selectedKey = kindFilterKey,
                    onPick = { key ->
                        kindFilterKey = key
                        showTypeFilter = false
                    },
                    onDismiss = { showTypeFilter = false },
                )
            }
            if (showYearFilter) {
                MultiChoiceDialog(
                    title = tr("Year"),
                    items = SEARCH_YEARS.map { ChoiceItem(key = it.toString(), label = it.toString()) },
                    selectedKeys = yearsFilter.map { it.toString() }.toSet(),
                    onToggle = { key -> key.toIntOrNull()?.let { toggleYear(it) } },
                    onDismiss = { showYearFilter = false },
                    searchable = true,
                    searchPlaceholder = tr("Search years"),
                    headerContent = {
                        TextButton(onClick = { setYears(emptySet()) }) { Text(tr("Clear")) }
                    },
                )
            }
            if (showGenreFilter) {
                MultiChoiceDialog(
                    title = tr("Genre"),
                    items = SEARCH_GENRES.map { ChoiceItem(key = it.trim().lowercase(), label = trTag(it)) },
                    selectedKeys = genreFilter,
                    onToggle = { key -> toggleGenre(key) },
                    onDismiss = { showGenreFilter = false },
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
        } else if (!resultsMode || (query.isBlank() && results.isEmpty() && collectionHits.isEmpty())) {
            EmptyState(
                title = tr("Search"),
                subtitle = tr("Type something to search across every provider."),
                actionLabel = null,
                action = null
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
                val searchCols = if (tvSearch) 6 else TvUi.resultsColumns()
                val tvCell = if (tvSearch) {
                    val sw = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp
                    val avail = sw - com.hikari.app.tv.TvUi.RAIL_WIDTH - 20.dp - tvEdge * 2
                    ((avail - 12.dp * (searchCols - 1)) / searchCols).coerceAtLeast(96.dp)
                } else 0.dp
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
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    items(hits, key = { it.uniqueId }) { item ->
                                        PhoneSearchCell(
                                            item = item,
                                            onOpen = { openSearchItem(item) },
                                            modifier = Modifier.width(112.dp),
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
) {
    val style = rememberPosterStyle()
    val badge = rememberPosterScore(item, style)
    Column(modifier.clickable { onOpen() }) {
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
                    .heroPoster(item.uniqueId)
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

private fun MediaItem.passesSearchFilter(
    kind: SearchKindFilter,
    years: Set<Int>,
    genres: Set<String>,
): Boolean {
    if (years.isNotEmpty() && (year == null || year !in years)) return false
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
): Boolean {
    if (kind == SearchKindFilter.ALL) return false
    return type == MediaType.UNKNOWN && passesSearchFilter(kind, years, genres)
}
