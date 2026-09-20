package com.hikari.app.ui.screens
import com.hikari.app.i18n.tr
import com.hikari.app.i18n.I18n

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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

    /** Toggle one provider in/out of the multi-select. Refuses to empty the
     *  selection (which would silently become "All"); use selectAll() for that. */
    fun toggleProvider(id: String) {
        val cur = _selectedProviders.value
        val next = if (id in cur) cur - id else cur + id
        if (next.isNotEmpty()) {
            _selectedProviders.value = next
            savedState["providers"] = ArrayList(next)
        }
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
            Column {
                var providerFilter by remember { mutableStateOf("") }
                // The row is one list of "places a title can be looked for":
                // every installed extension, plus every personal catalog the
                // user built in Settings → Personal Catalog creator. The
                // catalogs lead, because a user who built one is looking for it
                // — and its absence from this row was the report ("there isn't
                // the catalog we created name in provider in search bar to
                // select to search from it").
                val visibleProviders = remember(providers, providerFilter) {
                    val f = providerFilter.trim()
                    if (f.isEmpty()) providers
                    else providers.filter { it.config.name.contains(f, ignoreCase = true) }
                }
                val visibleCollections = remember(collections, providerFilter) {
                    val f = providerFilter.trim()
                    if (f.isEmpty()) collections
                    else collections.filter { it.name.contains(f, ignoreCase = true) }
                }
                if (providers.size + collections.size > 5) {
                    // With many extensions installed the chip row is unusable —
                    // a mini search box narrows it to the ones you mean.
                    GlassSearchField(
                        value = providerFilter,
                        onValueChange = { providerFilter = it },
                        placeholder = tr("Filter providers…"),
                        height = 44.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 2.dp)
                    )
                }
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        FilterChip(
                            selected = selected.isEmpty(),
                            onClick = { vm.selectAll() },
                            label = { Text(tr("All")) },
                            shape = RoundedCornerShape(24.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                                selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                                labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                selectedLabelColor = MaterialTheme.colorScheme.primary,
                            )
                        )
                    }
                    items(visibleCollections.distinctBy { it.id }, key = { "coll|" + it.id }) { c ->
                        val key = Routes.COLLECTION_PROVIDER_PREFIX + c.id
                        FilterChip(
                            selected = key in selected,
                            onClick = { vm.toggleProvider(key) },
                            label = { Text(c.name) },
                            shape = RoundedCornerShape(24.dp),
                            // A catalog chip wears the tertiary accent instead of
                            // the primary one the extensions use: two chips can
                            // share a name (a collection called "HBO" beside an
                            // HBO extension), and the colour is what says which
                            // is which.
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                                selectedContainerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.20f),
                                labelColor = MaterialTheme.colorScheme.tertiary,
                                selectedLabelColor = MaterialTheme.colorScheme.tertiary,
                            )
                        )
                    }
                    items(visibleProviders.distinctBy { it.config.id }, key = { it.config.id }) { p ->
                        FilterChip(
                            selected = p.config.id in selected,
                            onClick = { vm.toggleProvider(p.config.id) },
                            label = { Text(p.config.name) },
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
                Text(
                    when {
                        selected.isEmpty() -> I18n.t("Searching every source")
                        soleName != null -> I18n.t("Searching in %s").replace("%s", soleName)
                        else -> I18n.t("%s sources selected")
                            .replace("%s", selected.size.toString())
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                )
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
                    CollectionHitsRow(collectionHits) { hit ->
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
                val gridItems = remember(results) { results.distinctBy { it.uniqueId } }
                val style = rememberPosterStyle()
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
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
                                    Routes.detail(item.providerId, item.type, item.id, item.title, item.posterUrl, item.rawType)
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
