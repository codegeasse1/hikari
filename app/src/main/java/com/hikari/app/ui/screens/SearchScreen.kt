package com.hikari.app.ui.screens
import com.hikari.app.tv.TvMode
import com.hikari.app.tv.TvSelectProviderDialog
import com.hikari.app.tv.TvDesktop
import com.hikari.app.tv.TvUi
import com.hikari.app.i18n.tr
import com.hikari.app.i18n.trTag
import com.hikari.app.i18n.I18n
import com.hikari.app.ui.components.GlassSearchField

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
import androidx.lifecycle.SavedStateHandle
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

    /**
     * The application as the app class.
     *
     * [app] is a plain constructor PARAMETER, which is in scope in the property
     * initializers and the `init` block below but not in the methods (which is
     * what the history buttons are), so the cast is kept here once and every
     * method goes through it.
     */
    private val hikari: HikariApp = app as HikariApp

    /** The user's own collections — the local half of a search. */
    private val collections = com.hikari.app.data.CollectionsRepository(manager)

    /**
     * The saved personal catalogs, so the provider row can offer them beside the
     * extensions. A catalog IS a place a title can be looked for ("search inside
     * abc"), which is exactly what that row means, and the user asked for it
     * twice: the names simply were not there to pick.
     */
    val userCollections: Flow<List<com.hikari.app.data.Collection>> =
        (app as HikariApp).store.collectionsFlow()

    // Query + selection live in SavedStateHandle so they survive the activity
    // being recreated while the video player runs. Without this, watching a
    // stream from the search results and coming back found an empty screen (the
    // ViewModel was recreated and the query lost) — forcing a re-search after
    // every video. On recreation the saved query is restored, so the search
    // re-runs automatically and the results come straight back.
    private val _query = MutableStateFlow(savedState.get<String>("query") ?: "")
    val query: StateFlow<String> = _query.asStateFlow()

    /** Selected provider ids to search in. EMPTY = search ALL providers.
     *
     *  A key is either an extension's id or a personal catalog's
     *  ("collection:<id>" — see [Routes.COLLECTION_PROVIDER_PREFIX]), so the one
     *  selection carries both kinds of scope through the Search route. */
    private val _selectedProviders =
        MutableStateFlow(savedState.get<ArrayList<String>>("providers")?.toSet() ?: emptySet())
    val selectedProviders: StateFlow<Set<String>> = _selectedProviders.asStateFlow()

    val providers: StateFlow<List<ContentProvider>> = manager.providers

    /**
     * Results + status live in the process-wide [SearchSession], NOT here, so
     * the multi-page scan survives this ViewModel being recreated (it is, every
     * time the user watches something and comes back). Returning to Search then
     * shows the results already collected and resumes the same scan — instead
     * of restarting from page 1.
     */
    val results: StateFlow<List<MediaItem>> = SearchSession.results
    val searching: StateFlow<Boolean> = SearchSession.searching

    /**
     * Titles matched inside the user's OWN collections ("From your
     * collections"): an imported list or a hand-built TMDB source is theirs,
     * not an extension's, so nothing else would ever surface it in Search.
     */
    private val _collectionHits =
        MutableStateFlow<List<com.hikari.app.data.CollectionsRepository.CollectionHit>>(emptyList())
    val collectionHits: StateFlow<List<com.hikari.app.data.CollectionsRepository.CollectionHit>> =
        _collectionHits.asStateFlow()

    /**
     * The recent searches, newest first — the chips under the search box (see
     * [AppStore.searchHistory]). They are recorded by [init], the one place a
     * query that actually RAN is known.
     */
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
            combine(_query.debounce(400).distinctUntilChanged(), _selectedProviders) { q, sel ->
                q to sel
            }
                .collectLatest { (q, selection) ->
                    if (q.isBlank()) {
                        _collectionHits.value = emptyList()
                        SearchSession.clear()
                        return@collectLatest
                    }
                    // Remember what was searched for: this is the DEBOUNCED
                    // query (the box itself changes on every keystroke), so what
                    // lands in the history is a query the user stopped typing on
                    // — and the store drops anything under two characters. On
                    // the view model's own scope, so a store failure can never
                    // kill the search flow.
                    viewModelScope.launch {
                        runCatching { hikari.store.addSearchHistory(q) }
                    }
                    // The catalog lookup needs at least two characters (see
                    // [CollectionsRepository.searchIn]); below that it answers
                    // nothing and never calls back, so the previous query's hits
                    // would stay under a one-letter query.
                    if (q.trim().length < 2) _collectionHits.value = emptyList()
                    // The selection can name extensions, personal catalogs, or
                    // both. Catalogs are searched locally (their titles live in
                    // the collection, or come from a catalog we can ask) and
                    // NEVER by the extension sweep — asking an extension "do you
                    // have abc" is a different question from "what is inside abc".
                    val extensionIds = selection.filterNot { Routes.isCollectionScope(it) }
                        .toSet()
                    val catalogIds = selection.filter { Routes.isCollectionScope(it) }
                        .map { Routes.collectionIdOfScope(it) }
                        .toSet()
                    // "All" is the EMPTY selection. A selection made of catalogs
                    // only means "only those catalogs", so the extension sweep is
                    // skipped rather than silently widened to everywhere — that
                    // is the whole point of picking one.
                    val sweepExtensions = selection.isEmpty() || extensionIds.isNotEmpty()
                    // Both halves run together, and a newer query cancels both:
                    // the extension sweep and the local catalog lookup can't
                    // disagree about which query they are answering.
                    kotlinx.coroutines.coroutineScope {
                        launch {
                            // Published as they are found: an imported list is
                            // instant, an extension catalog in a personal
                            // catalog is a network fetch, and waiting for the
                            // slowest source would hold the row back for
                            // seconds. A cancelled run (the user typed on) keeps
                            // whatever it had published — the newer query's list
                            // replaces it — instead of blanking the row on every
                            // keystroke.
                            //
                            // A scoped search (a catalog is picked) gets a bigger
                            // cap: the user is asking "what is in here", so the
                            // answer is the whole shelf, not the first screenful.
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

    fun setQuery(q: String) {
        _query.value = q
        savedState["query"] = q
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
    // `initial` is required for a plain Flow (a StateFlow carries its own), and
    // it doubles as "no catalogs yet" for the first frame.
    val collections by vm.userCollections.collectAsState(initial = emptyList())

    // Search-bar translator state: the text the user typed before translating
    // (null while showing English), the current target language, the language
    // menu, and whether a translation is in flight.
    var translatedFrom by remember { mutableStateOf<String?>(null) }
    var targetLang by rememberSaveable { mutableStateOf("zh-CN") }
    var langMenu by remember { mutableStateOf(false) }
    var translating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Television overscan: sets that crop the frame (~5-10% on every edge)
    // cut whatever the results grid draws against it — the "posters are cut
    // ~10% and scrolling only trades one cut row for the next" report. The
    // grid keeps this clear of every edge on TV only (see [TvUi] overscan);
    // phones are untouched, and a set that shows the full frame keeps the
    // user's 0.
    val context = LocalContext.current
    // Auto-focus the search box (phones only): opening the Search tab puts
    // the cursor in the bar and raises the keyboard, so the user types
    // straight away instead of tapping the bar a second time. One-shot per
    // visit — results updates must never steal focus back. TV never raises
    // a software keyboard, so it only takes focus when a query is preset.
    val searchFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    var searchAutoFocused by rememberSaveable { mutableStateOf(false) }
    val searchIsTv = TvMode.current()
    LaunchedEffect(Unit) {
        if (searchAutoFocused) return@LaunchedEffect
        searchAutoFocused = true
        if (searchIsTv) return@LaunchedEffect
        kotlinx.coroutines.delay(250)
        runCatching {
            searchFocus.requestFocus()
            keyboard?.show()
        }
    }
    val overscanFlow = remember(context) {
        (context.applicationContext as HikariApp).store.tvOverscanFlow()
    }
    val overscanDp by overscanFlow.collectAsState(initial = TvUi.DEFAULT_OVERSCAN_DP)
    val tvEdge = if (TvMode.current()) overscanDp.dp else 0.dp

    // ---- Result filters (kind + year) -------------------------------------
    //
    // These narrow what has ALREADY been found rather than being added to the
    // query, and that is deliberate: searching every installed extension is a
    // long multi-page scan, so a filter that re-ran it would make picking a
    // year feel like a new search. Here a tap is instant, and the answer is the
    // same list, filtered — "search moana, pick the year, keep only the movie"
    // without paying for the sweep again.
    var kindFilterKey by rememberSaveable { mutableStateOf("all") }
    val kindFilter = SearchKindFilter.fromKey(kindFilterKey)

    // The years to keep, chosen from a FIXED list of every year there is —
    // never from the results — and holding as many as the user picks
    // ("2001,2002,2004" keeps exactly those three). The old control offered
    // only the years the results so far happened to carry, which meant a year
    // could not be picked until a title from it had already been found: the
    // filter was chosen after the fact instead of before the search. Held as a
    // sorted, comma-joined string so it survives the ViewModel/activity being
    // recreated (watching something and coming back).
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
    // The GENRES to keep, chosen from a FIXED list of every genre there is —
    // never from the results — and holding as many as the user picks ("action,
    // comedy" keeps both). Held exactly the way the years are (a sorted,
    // comma-joined string of lower-cased names, so it survives the player
    // recreating the activity). The names are compared against what the item's
    // own source said (see [MediaItem.passesSearchFilter]).
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
    // What the KIND filter kept although the provider never said what it is
    // (those are simply shown), and what the YEAR and GENRE filters dropped for
    // the same reason — the numbers the grid explains itself with, so it can
    // never look like it silently ignored a filter.
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

    // ---- Engine filter (which of the searched engines' hits to show) -----
    //
    // This only means something when more than one source is in play, so the
    // strip is drawn only when the current results actually carry hits from two
    // or more ENGINES (CloudStream plugins, Hikari extensions, Nuvio providers,
    // Vega providers, …). It narrows the grid to one of them — "search black
    // clover everywhere, then read just the Hikari extensions' hits" — and it
    // does that WITHOUT re-running the scan, because it filters what has already
    // landed. The counts are taken from the results as the kind/year/genre
    // strips left them, so picking a chip can never change the chip row itself:
    // the way back ("All") stays put, first, with the same number next to it.
    // An item whose provider is not installed any more (or a collection hit) has
    // no engine, so it is simply never counted and never shows under a chip.
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
    // A pick whose engine has no hits left under the current kind/year/genre
    // strips filters nothing, and would leave the grid empty with a chip that is
    // no longer on the strip to undo it: such a pick is ignored here, and the
    // effect below drops it from the saved state so it cannot come back on its
    // own when the strips change again.
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
        if (initialQuery.isNotBlank()) vm.setQuery(initialQuery)
        if (initialProvider.isNotBlank()) vm.selectProvider(initialProvider)
    }

    // One tap EN -> target language (Chinese by default), tap again to restore
    // the original English the user typed. The source language is whatever the
    // user wrote, auto-detected by the translator, so the same button also
    // turns a Chinese title back into English when it was already translated.
    fun toggleTranslate() {
        if (translating) return
        val restore = translatedFrom
        if (restore != null) {
            vm.setQuery(restore)
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
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (TvMode.current()) {
            com.hikari.app.tv.TvScreenHeader(title = tr("Search"), subtitle = if (selected.isEmpty()) tr("All providers") else if (soleName != null) soleName else tr("%s sources").replace("%s", selected.size.toString()))
        }
    // Provider-picker state, hoisted above the search field on TV: the
    // television search tab must show its own provider box trigger (the same
    // exact Select Provider dialog Home uses), not just the header subtitle.
    var showProviders by remember { mutableStateOf(false) }
    var providerKind by remember { mutableStateOf<ProviderType?>(null) }
        GlassSearchField(
            value = query,
            onValueChange = { vm.setQuery(it); translatedFrom = null },
            placeholder = when {
                selected.isEmpty() -> "Search across all providers…"
                soleName != null -> "Search in $soleName…"
                else -> "Search in ${selected.size} selected sources…"
            },
            focusRequester = searchFocus,
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
                            // The D-pad half: a raw pointerInput is invisible to
                            // the focus system, so a remote could not reach the
                            // translate button at all — see [Modifier.tvPress].
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
        // Television's provider box trigger: the same exact Select
        // Provider dialog as Home — the box only, no feed mixer here.
        if (searchIsTv && (providers.isNotEmpty() || collections.isNotEmpty())) {
            val tvScopeLabel = when {
                selected.isEmpty() -> tr("All providers")
                soleName != null -> soleName
                else -> I18n.t("%s sources").replace("%s", selected.size.toString())
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    onClick = { showProviders = true },
                    shape = RoundedCornerShape(50),
                    color = TvDesktop.SurfaceCard,
                    border = BorderStroke(1.dp, TvDesktop.GlassEdge),
                    modifier = Modifier.tvPress { showProviders = true },
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Tune,
                            contentDescription = null,
                            tint = TvDesktop.TextMuted,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            tvScopeLabel,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = TvDesktop.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = tr("Choose providers"),
                            tint = TvDesktop.TextMuted,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
        // ---- Recent searches ----
        //
        // Shown only while the box is EMPTY: once a search is running or has
        // results, the screen's job is the results and a strip of old queries
        // above them is chrome to scroll past. With an empty box it is exactly
        // what the empty state below is for — the answer to "what do I search
        // for?" — so the two live together, and a chip re-runs the search.
        // Television hides the strip entirely: the TV screen is provider
        // selection + the search box only.
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
                        // Picking one is a new search, not a restored one: the
                        // translator's "show the original" state does not apply
                        // to a query the user did not type this time.
                        onPick = {
                            translatedFrom = null
                            vm.setQuery(past)
                        },
                        onDelete = { vm.forgetSearch(past) },
                    )
                }
            }
        }
        // Provider-picker state, hoisted above the two blocks that use it: the
        // dialogs above AND the one-line filter row below both open it.
        if (providers.isNotEmpty() || collections.isNotEmpty()) {
            // ---- Where to search: a button, and the picker it opens ----
            //
            // This used to be a HORIZONTAL row of chips (one per extension, plus
            // one per personal catalog) with a "Filter providers…" box above it,
            // and with a few dozen extensions installed it was unusable: a name
            // you could not see was a provider you could not pick. The user asked
            // for the picker Home's own header uses instead — a button that opens
            // a full-height list with a search box, the engine categories, and
            // multi-select — and for the outer filter box to go away with it,
            // because the picker carries its own search. Doing that removed the
            // chips entirely, so what is left here is one line that says WHAT
            // will be searched and one tap to change it.
            // Television gets the provider box ONLY — no Manage entry here
            // (the mixer lives in Home). The scope pill lives in the filter
            // row above (it opens the same picker), so there is no button
            // left here — just the state and the dialog it opens.
            if (showProviders) {
                if (searchIsTv) {
                    // Television gets the same big reference-style Select
                    // Provider card as Home (title + All Plugins (Global)
                    // + filter + provider grid), with this screen's own
                    // collections on top. A tap picks and closes.
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
        // ---- Result filters: kind + year (see the state at the top) ---------
        //
        // Same chip row as the providers above, so the two read as one control
        // strip: what to search, then what to keep. The kind chips and the year
        // strip are shown from the moment the screen is up — NOT only once
        // results exist — because the filter is meant to be chosen BEFORE (or
        // while) the scan runs.
        //
        // Television hides the whole block: the TV screen is provider
        // selection + the search box only.
        if (!searchIsTv) {
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
            //
            // Provider pill + kind + years + genres ride a single horizontal
            // scroller of small wrap-content pills: every name shows in full
            // (no truncation, no collapsing second line), and the results get
            // all the room below. Television keeps its own screen (provider
            // selection + search box only).
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
            if ((filterOn || activeEngine.isNotBlank()) && results.isNotEmpty()) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
                    Text(
                        if (visible.isEmpty()) {
                            tr("Nothing in these results matches the filter.")
                        } else {
                            // Two single-placeholder phrases joined the way the
                            // rest of the app joins counted labels (see
                            // ExtensionsScreen's "N repos · M enabled"), so each
                            // half is a key the dictionaries can hold.
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
        if (query.isBlank() && results.isEmpty() && collectionHits.isEmpty()) {
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
            // Built once per provider list, not once per recomposition: this
            // screen recomposes on every keystroke and on every batch of results
            // arriving, and the map has one entry per installed extension.
            val namesById = remember(providers) {
                providers.associateBy({ it.config.id }, { it.config.name })
            }
            Column(Modifier.fillMaxSize()) {
                // One chip per engine that answered, plus "All" — see the
                // engine-filter state at the top. Drawn only when there are two
                // or more engines to choose between, so a scoped search (one
                // source, one engine) never sees a strip that can do nothing —
                // or, at any count, while one engine's chip is in force: that is
                // the only way to undo a pick whose competitors the other strips
                // have narrowed away.
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
                // The user's own collections first: an imported list or a
                // hand-built TMDB source belongs to them, and no extension
                // would ever hand it back.
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
                // Two providers can answer with the same title and the same id
                // (and one provider can answer twice): a repeated Lazy key is a
                // hard crash, so repeats are dropped before the grid is built.
                //
                // Both the dedupe and the poster style are hoisted out of the
                // CELL: per cell this allocated a fresh list on every
                // recomposition and opened a DataStore collection per poster on
                // screen, which is a lot of subscriptions for one grid (see
                // [com.hikari.app.ui.components.MediaRow]).
                val style = rememberPosterStyle()
                val tvSearch = TvMode.current()
                // Television's medium grid: 6 across reads at a glance from the
                // sofa without the 3-across billboards the screen had (one row
                // filled the whole display). Phones keep their adaptive count.
                val searchCols = if (tvSearch) 6 else TvUi.resultsColumns()
                // Cell-sized cards: the TV cards below carry a fixed shelf
                // width by default (made for sideways rows), which would
                // overflow a 6-across grid. Size them to the cell instead,
                // from the viewport minus the rail and the grid's own padding.
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
                // Phone groups the hits by provider — first hit sets the order —
                // so every source reads as one shelf: a big name, a tiny
                // "engine · repo" tag, and a sideways row of its posters. "See
                // all (N)" drills into that provider's own vertical grid.
                // Television keeps its single reference grid, untouched.
                val groupedVisible = remember(visible) { visible.groupBy { it.providerId } }
                var expandedProvider by rememberSaveable { mutableStateOf<String?>(null) }
                val effectiveExpanded = if (tvSearch) null
                    else expandedProvider?.takeIf { groupedVisible.containsKey(it) }
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
                    // Results position held per query+engine (see
                    // ScrollKeeper): Back from a title lands where the grid
                    // was, and a new query starts at its own slot.
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
                    // Clear of the floating taskbar (0 when there is no bar),
                    // plus the overscan edge on TV so the last row can scroll
                    // fully into view instead of parking cut off.
                    bottom = LocalTaskbarInset.current + 12.dp + tvEdge +
                        if (TvMode.current()) 28.dp else 0.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(if (tvSearch) 12.dp else 8.dp),
                verticalArrangement = Arrangement.spacedBy(if (tvSearch) 16.dp else 10.dp)
            ) {
                items(gridItems, key = { it.uniqueId }) { item ->
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
                    // Show scores (Settings → App Layout) draws here too — the
                    // badge warms the ratings cache for the title and prints
                    // whatever is known. Null when the switch is off.
                    val badge = rememberPosterScore(item, style)
                    // Not clipped to the card rounding: the curve cut into
                    // the title's edges at high corner values (see
                    // CatalogCard). Only the artwork Box below is rounded.
                    Column(
                        Modifier
                            .clickable {
                                Routes.safeNavigate(
                                    nav,
                                    // A manga engine's results are MANGA: they open
                                    // the manga detail page (chapters, follow,
                                    // reader), not the video one, which would ask
                                    // the extension for servers it cannot serve.
                                    // This is what makes a tag's "Global search"
                                    // land somewhere sensible — and what makes any
                                    // manga search work, not just that one.
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
                                // Portrait art in a cinema frame: a full-bleed
                                // 16:9 crop anchored exactly like every other
                                // cinema frame (see Artwork.CINEMA_ALIGNMENT).
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

/**
 * "From your collections": the titles a search matched inside the user's own
 * catalogs, each labelled with the collection it came from. A row of its own
 * above the provider grid, because these hits answer a different question —
 * "is this already in something I built?" — and no extension will ever return
 * them.
 */
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

/**
 * Every genre the genre strip offers, alphabetically — the ONE vocabulary the
 * Search tab and Home's own strip both use (see
 * [com.hikari.app.data.Genres.ALL], which also knows how to turn a name into a
 * TMDB query: a genre id for the names TMDB has one for, a keyword for the
 * anime tags it does not).
 *
 * A FIXED list on purpose, exactly like [SEARCH_YEARS]: the genres come from the
 * catalogue's own vocabulary, not from what this search happens to have found,
 * so a genre is pickable before a single result is in — and a filter that can
 * only offer what it has already let through is a filter that cannot narrow
 * anything.
 */
private val SEARCH_GENRES: List<String> = com.hikari.app.data.Genres.ALL

/**
 * Whether a result looks like anime.
 *
 * A provider almost never says "anime" in so many words, so this is the union of
 * the three ways one can say it: the addon's own type string ([MediaItem.rawType]
 * — a Stremio anime catalogue is the type `anime`), a genre that IS anime, and
 * TMDB's "Animation" (which is what every TMDB-sourced anime item carries, since
 * TMDB has no anime genre of its own — an anime title is an animated Japanese
 * show). An extension that tags its results "Anime" is matched by the first
 * genre test.
 *
 * Lives here, with the model, because more than one screen draws an "Anime"
 * filter over a mixed list: the Search tab's kind chips and a TMDB catalog
 * page's kind chips (see `TmdbGridScreen`), and two copies of this rule would
 * eventually disagree about what anime is.
 */
fun MediaItem.looksAnime(): Boolean {
    if (rawType.equals("anime", true)) return true
    if (genres.any { g ->
        val t = g.trim().lowercase()
        t == "anime" || t == "animation" || t == "animated" || t == "cartoon" ||
            t.contains("anime") || t.contains("cartoon")
    }) return true
    // Provider-level signal: anime-only engines carry no per-item genres at
    // all (a site scraper returns titles, not tags), so the provider itself is
    // the answer — Aniyomi streaming IS the AnimeSource API, and CloudStream
    // anime plugins live under an anime path segment.
    val p = providerId.trim().lowercase()
    if (p.startsWith("aniyomi|")) return true
    if (p.contains("anime")) return true
    return p.contains("/anime/") || p.contains("|anime|") || p.endsWith("|anime")
}

/**
 * Every year the year strip offers: next year (a title can already be dated
 * to it) back to 1950, newest first, so the strip opens on the years people
 * actually search for and scrolls into the back catalogue.
 *
 * A FIXED list on purpose: the years come from the calendar, not from what the
 * search has found so far, so a year is pickable before a single result is in.
 */
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

/**
 * One remembered search: the query, and the way to forget that one.
 *
 * The chip itself re-runs the search — that is what a history chip is for — and
 * the ✕ is its OWN click target inside it: a tap on the ✕ must not also run the
 * search, and a press of the remote's centre button on the ✕ must not run it
 * either, which is why the ✕ is a clickable of its own rather than part of the
 * chip's gesture. Both are focus targets, so a D-pad can reach the query and the
 * ✕ separately.
 */
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

/**
 * One phone search poster: art + title. The provider shelf's own header names
 * the source, so the cell does not repeat it under every poster (the old flat
 * grid printed it there because no header said it).
 */
@Composable
private fun PhoneSearchCell(
    item: MediaItem,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Show scores (Settings → App Layout) draws here too — the badge warms the
    // ratings cache for the title and prints whatever is known. Null when the
    // switch is off.
    val style = rememberPosterStyle()
    val badge = rememberPosterScore(item, style)
    // Not clipped to the card rounding: the curve cut into the title's edges at
    // high corner values (see CatalogCard). Only the artwork Box below is
    // rounded.
    Column(modifier.clickable { onOpen() }) {
        val phoneCinema = style.isCinema(false)
        val hero = if (phoneCinema) Artwork.heroModel(item) else null
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(if (phoneCinema) 16f / 9f else 2f / 3f)
                .clip(RoundedCornerShape(10.dp))
        ) {
            if (phoneCinema && hero != null && !hero.second) {
                // Portrait art in a cinema frame: a full-bleed 16:9 crop
                // anchored exactly like every other cinema frame (see
                // Artwork.CINEMA_ALIGNMENT).
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

/**
 * Whether one search result survives the filter row.
 *
 * The KIND half keeps a result whose provider never said what it is (type
 * UNKNOWN): whole extensions label their results that way, and hiding all of
 * them would turn picking Movie or Series into an empty screen — which reads as
 * a broken search, not a narrower one. [MediaItem.unknownKindKept] is that same
 * question the other way round, so the count can be shown next to "N shown ·
 * M found" instead of the grid silently disagreeing with its own total.
 *
 * The YEAR and GENRE halves are STRICT: both are picked BEFORE the search, so a
 * result whose source never said when it came out, or what it is (extension
 * catalogues usually carry no genre at all), cannot be presented as one of them
 * — it is counted into "…have no year / no genre, so they are hidden" instead.
 * Anything else would make the two strongest filters look like they did nothing.
 */
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
