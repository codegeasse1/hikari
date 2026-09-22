package com.hikari.app.ui.screens
import com.hikari.app.tv.TvUi
import com.hikari.app.i18n.tr
import com.hikari.app.i18n.I18n

import android.app.Application
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    // A filter is only "on" when it can actually hide something.
    val filterOn = kindFilter != SearchKindFilter.ALL || yearsFilter.isNotEmpty()
    val filtered = remember(results, kindFilter, yearsKey) {
        if (!filterOn) results else results.filter { it.passesSearchFilter(kindFilter, yearsFilter) }
    }
    // What the KIND filter kept although the provider never said what it is
    // (those are simply shown), and what the YEAR filter dropped for the same
    // reason — the two numbers the grid explains itself with, so it can never
    // look like it silently ignored a filter.
    val keptUnknownKind = remember(results, kindFilter, yearsKey) {
        if (!filterOn) 0 else results.count { it.unknownKindKept(kindFilter, yearsFilter) }
    }
    val hiddenNoYear = remember(results, kindFilter, yearsKey) {
        // Only the ones the KIND filter would have shown: an item dropped by
        // "Movies" is not hidden because it has no year.
        if (yearsFilter.isEmpty()) 0 else results.count {
            it.year == null && it.passesSearchFilter(kindFilter, emptySet())
        }
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
        GlassSearchField(
            value = query,
            onValueChange = { vm.setQuery(it); translatedFrom = null },
            placeholder = when {
                selected.isEmpty() -> "Search across all providers…"
                soleName != null -> "Search in $soleName…"
                else -> "Search in ${selected.size} selected sources…"
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            trailing = {
                Box {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(50))
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
            var showProviders by remember { mutableStateOf(false) }
            var providerKind by remember { mutableStateOf<ProviderType?>(null) }
            val scopeLabel = when {
                selected.isEmpty() -> tr("All providers")
                soleName != null -> soleName
                else -> I18n.t("%s sources").replace("%s", selected.size.toString())
            }
            // The same sentence the old status line carried, now the button's
            // second line: a scoped search announces its scope by NAME.
            val scopeNote = when {
                selected.isEmpty() -> tr("Searching every source")
                soleName != null -> I18n.t("Searching in %s").replace("%s", soleName)
                else -> I18n.t("%s sources selected").replace("%s", selected.size.toString())
            }
            ProviderScopeButton(
                label = scopeLabel,
                supporting = scopeNote,
                onClick = { showProviders = true },
            )
            if (showProviders) {
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
        // ---- Result filters: kind + year (see the state at the top) ---------
        //
        // Same chip row as the providers above, so the two read as one control
        // strip: what to search, then what to keep. The kind chips and the year
        // strip are shown from the moment the screen is up — NOT only once
        // results exist — because the filter is meant to be chosen BEFORE (or
        // while) the scan runs.
        run {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 2.dp),
            ) {
                item {
                    FilterChip(
                        selected = kindFilter == SearchKindFilter.ALL,
                        onClick = { kindFilterKey = SearchKindFilter.ALL.key },
                        label = { Text(tr("Both")) },
                        shape = RoundedCornerShape(24.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            selectedLabelColor = MaterialTheme.colorScheme.primary,
                        )
                    )
                }
                item {
                    FilterChip(
                        selected = kindFilter == SearchKindFilter.MOVIES,
                        onClick = { kindFilterKey = SearchKindFilter.MOVIES.key },
                        label = { Text(tr("Movies")) },
                        shape = RoundedCornerShape(24.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            selectedLabelColor = MaterialTheme.colorScheme.primary,
                        )
                    )
                }
                item {
                    FilterChip(
                        selected = kindFilter == SearchKindFilter.SERIES,
                        onClick = { kindFilterKey = SearchKindFilter.SERIES.key },
                        label = { Text(tr("Series")) },
                        shape = RoundedCornerShape(24.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            selectedLabelColor = MaterialTheme.colorScheme.primary,
                        )
                    )
                }
                item {
                    // The filter's own status, and the way to clear it: it lists
                    // what is picked (with a ✕ to drop it) and nothing else — the
                    // years themselves are the strip below, which is where they
                    // are chosen. It reads "Any year" and stays a no-op while
                    // nothing is picked.
                    val picked = when {
                        yearsFilter.isEmpty() -> tr("Any year")
                        yearsFilter.size <= 3 ->
                            yearsFilter.sortedDescending().joinToString(", ")
                        else -> tr("%s years").replace("%s", yearsFilter.size.toString())
                    }
                    FilterChip(
                        selected = yearsFilter.isNotEmpty(),
                        onClick = { setYears(emptySet()) },
                        label = { Text(if (yearsFilter.isEmpty()) picked else "$picked ✕") },
                        shape = RoundedCornerShape(24.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            selectedLabelColor = MaterialTheme.colorScheme.primary,
                        )
                    )
                }
            }
            // The YEAR strip: every year there is, in one slim scroller of its
            // own, tapped to toggle (multi-select — 2001 · 2002 · 2004 keeps
            // all three at once). It is deliberately a short, thin box: a year
            // is four digits, so the control needs no more height than a chip
            // row, and a fixed list means a year can be picked before the
            // search has found a single title.
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.35f),
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.22f),
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 6.dp)
                    .height(34.dp),
            ) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    item {
                        YearChip(
                            label = tr("Any"),
                            selected = yearsFilter.isEmpty(),
                            onClick = { setYears(emptySet()) },
                        )
                    }
                    items(SEARCH_YEARS) { year ->
                        YearChip(
                            label = year.toString(),
                            selected = year in yearsFilter,
                            onClick = { toggleYear(year) },
                        )
                    }
                }
            }
            if (filterOn && results.isNotEmpty()) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
                    Text(
                        if (filtered.isEmpty()) {
                            tr("Nothing in these results matches the filter.")
                        } else {
                            // Two single-placeholder phrases joined the way the
                            // rest of the app joins counted labels (see
                            // ExtensionsScreen's "N repos · M enabled"), so each
                            // half is a key the dictionaries can hold.
                            tr("%s shown").replace("%s", filtered.size.toString()) +
                                " · " +
                                tr("%s found").replace("%s", results.size.toString())
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (filtered.isNotEmpty() && keptUnknownKind > 0) {
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
                // The user's own collections first: an imported list or a
                // hand-built TMDB source belongs to them, and no extension
                // would ever hand it back.
                if (collectionHits.isNotEmpty()) {
                    CollectionHitsRow(
                        if (filterOn) {
                            collectionHits.filter {
                                it.item.passesSearchFilter(kindFilter, yearsFilter)
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
                val gridItems = remember(filtered) { filtered.distinctBy { it.uniqueId } }
                val style = rememberPosterStyle()
                LazyVerticalGrid(
                    columns = GridCells.Fixed(TvUi.gridColumns(4)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                contentPadding = PaddingValues(
                    start = 10.dp,
                    end = 10.dp,
                    top = 8.dp,
                    // Clear of the floating taskbar (0 when there is no bar).
                    bottom = LocalTaskbarInset.current + 12.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(gridItems, key = { it.uniqueId }) { item ->
                    // Show scores (Settings → App Layout) draws here too — the
                    // badge warms the ratings cache for the title and prints
                    // whatever is known. Null when the switch is off.
                    val badge = rememberPosterScore(item, style)
                    Column(
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
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
                                        Routes.mangaDetail(
                                            item.providerId, item.id, item.title, item.posterUrl
                                        )
                                    } else {
                                        Routes.detail(item.providerId, item.type, item.id, item.title, item.posterUrl, item.rawType)
                                    }
                                )
                            }
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(2f / 3f)
                                .clip(RoundedCornerShape(10.dp))
                        ) {
                            AsyncImage(
                                model = Artwork.model(item),
                                contentDescription = item.title,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
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
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        namesById[item.providerId]?.let { name ->
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
            items(hits, key = { it.item.uniqueId + "|" + it.label }) { hit ->
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

/** Which kind of result the grid keeps: everything, only films, only shows. */
private enum class SearchKindFilter(val key: String) {
    ALL("all"),
    MOVIES("movies"),
    SERIES("series");

    companion object {
        fun fromKey(key: String?): SearchKindFilter =
            entries.firstOrNull { it.key == key } ?: ALL
    }
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

/**
 * The scope button: what a search will look through, and the way to change it.
 *
 * The row of provider chips this replaces could only ever show what fitted on
 * screen, and a provider whose chip had scrolled off could not be picked at all —
 * the user's report was "make the all provider showing from horizontal scrolling
 * to vertical … with multi select … and remove the search provider we have
 * outside, as now we will get it in the scrollable vertical bar". So this is a
 * BUTTON in the same shape as the app's other pickers (Home's own provider
 * button): it names the current scope on one line and what the search will do on
 * the next, and tapping it opens the picker — a full-height, searchable,
 * multi-selectable list of every place a title can be looked for.
 */
@Composable
private fun ProviderScopeButton(
    label: String,
    supporting: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Tune,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    supporting,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = tr("Choose providers"),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
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
 * The YEAR half is STRICT: the years are picked BEFORE the search, so a result
 * whose source never said when it came out cannot be presented as one of them
 * (it is counted into "…have no year, so they are hidden" instead).
 */
private fun MediaItem.passesSearchFilter(kind: SearchKindFilter, years: Set<Int>): Boolean {
    if (years.isNotEmpty() && (year == null || year !in years)) return false
    return when (kind) {
        SearchKindFilter.ALL -> true
        SearchKindFilter.MOVIES -> type != MediaType.SERIES
        SearchKindFilter.SERIES -> type != MediaType.MOVIE
    }
}

/** True when this result is SHOWN only because the provider did not say what it
 *  is (an unknown kind under a Movie/Series filter — see
 *  [MediaItem.passesSearchFilter]). */
private fun MediaItem.unknownKindKept(kind: SearchKindFilter, years: Set<Int>): Boolean {
    if (kind == SearchKindFilter.ALL) return false
    return type == MediaType.UNKNOWN && passesSearchFilter(kind, years)
}
