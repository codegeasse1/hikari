package com.hikari.app.ui.screens

import com.hikari.app.ui.components.LocalHideHelp
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import com.hikari.app.data.TmdbGenres
import com.hikari.app.data.TmdbSourceType
import com.hikari.app.data.TmdbSpec
import com.hikari.app.tv.TvCinematicHero
import com.hikari.app.tv.TvMode
import com.hikari.app.tv.TvUi
import com.hikari.app.ui.Artwork
import com.hikari.app.ui.PosterLoader
import com.hikari.app.ui.ProviderPacks
import com.hikari.app.ui.rememberVisibleItems
import com.hikari.app.ui.components.ContinueWatchingRow
import com.hikari.app.ui.components.EmptyState
import com.hikari.app.ui.components.GlassDialog
import com.hikari.app.ui.components.GlassSearchField
import com.hikari.app.ui.components.HeroBanner
import com.hikari.app.ui.components.HeroConfig
import com.hikari.app.ui.components.HeroStyles
import com.hikari.app.ui.components.MediaRow
import com.hikari.app.ui.components.ShimmerRow
import com.hikari.app.ui.theme.pageBackground
import com.hikari.app.ui.theme.rememberGlassTokens
import com.hikari.app.ui.navigation.LocalTaskbarInset
import com.hikari.app.ui.navigation.Routes
import com.hikari.app.providers.ContentProvider
import com.hikari.app.web.WebViewActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.hikari.app.tv.tvPress
import com.hikari.app.tv.tvTextFieldKeys

/**
 * A Home pick is stored as one string in the `homeProvider` preference: either
 * an extension's id, or — with this prefix — the id of a saved collection. One
 * preference (and one picker) therefore carries both kinds of choice.
 *
 * Shared with the Search tab ([Routes.COLLECTION_PROVIDER_PREFIX]) so the same
 * key means the same thing in a Home pick, in the Search tab's provider row, and
 * in the scoped-search route.
 */
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

    /**
     * The saved Home pick(s), in the order they were picked.
     *
     * EMPTY means "All providers" (the default), ONE entry is the ordinary
     * single pick, and SEVERAL entries are a MULTI pick — made by holding a row
     * in the picker for 0.5s and ticking others (see [setSelection]). Every
     * entry is the same string the picker stores a single pick under: an
     * extension's id, or `collection:<id>` for a personal catalog, so one list
     * carries both kinds of choice.
     */
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

    // Last successful home feed per selected-provider key ("all" when the user
    // is on the combined feed). Returning to Home, or re-picking the same
    // provider, paints this INSTANTLY and refreshes in the background instead
    // of blanking the screen to a spinner and re-fetching every catalog.
    //
    // Remembers EVERY feed the user has viewed (no eviction) so switching back
    // to any provider is always instant. Each row holds poster-cache tokens
    // rather than full images ([tokenizePoster] below), so the whole map stays
    // cheap no matter how many extensions were browsed.
    //
    // It lives on [HomeFeedCache] — the WHOLE PROCESS, not this view model —
    // because returning from the player can recreate the activity and with it
    // this view model, and a fresh empty map would mean a spinner plus a full
    // re-fetch of the picked provider every single time the user came back.
    private val homeCache get() = HomeFeedCache.rows

    /**
     * Every [loadInternal] call takes a ticket, and only the NEWEST ticket may
     * paint rows or write the cache.
     *
     * Cancelling the previous job is not enough on its own: `loadJob` holds the
     * LAST job ASSIGNED, and a load interrupted between reading the selection and
     * assigning its job can end up assigned after — and therefore outliving — a
     * load that started later. That is how an all-providers feed could paint
     * itself over a picked provider's rows.
     */
    private var loadToken = 0

    /**
     * Completes once the saved pick has been read back from the store.
     *
     * Everything else in [init] waits on it. A feed must never be loaded — and a
     * pick must never be judged "gone" — while the selection is still the empty
     * default. The new HomeViewModel a returning activity creates used to start
     * its provider watcher before the restore coroutine had read the preference:
     * the watcher then saw an empty selection, started an ALL-providers load, and
     * that feed painted itself over the picked provider's rows. That is the
     * reported "I pick 1Shows, play a movie, load a subtitle from the internet,
     * close the player and come back — and Home is showing every provider's
     * catalog again", with the pill still reading "1Shows", because the pick
     * itself was never lost.
     */
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
                // An EMPTY installed list means the list has not been built yet
                // (the first seconds of a process, or a refresh in flight). It is
                // not evidence that the picked extension is gone, so nothing is
                // dropped and no feed is rebuilt from it — otherwise a pick could
                // be forgotten in the instant before the extensions load.
                if (ps.isNotEmpty()) {
                    // Only EXTENSION picks can be invalidated by the installed
                    // list changing; a collection pick is resolved against the
                    // collections store instead (see loadInternal). One extension
                    // being uninstalled drops just that pick, so the rest of a
                    // multi pick (and the user's other choices) survive it.
                    val valid = sel.filter { key ->
                        isCollectionKey(key) || ps.any { it.config.enabled && it.config.id == key }
                    }
                    if (valid != sel) {
                        applySelection(valid)
                        viewModelScope.launch { store.setHomeProviders(valid.toSet()) }
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
                    // A collection was picked, unpicked, edited or deleted: a
                    // personal catalog picked on its own IS its folder tiles, and
                    // inside a multi pick its shelves are baked into the feed, so
                    // either way the screen has to be rebuilt from the store.
                    watchedCollections = picked
                    loadInternal()
                }
            }
        }
        viewModelScope.launch {
            // The language TMDB metadata is fetched in changed: every row built
            // under the old one is stale, so the feed is thrown away and built
            // again. Without this the new language only appeared after a
            // restart, because this cache is what Home actually draws from.
            val app = getApplication<Application>() as HikariApp
            app.contentLanguageRevision.drop(1).collect {
                homeCache.clear()
                loadInternal(forceRefresh = true)
            }
        }
        viewModelScope.launch {
            // The adult-content switch was turned off (or back on): the rows already
            // in hand were fetched under the old answer, and a catalogue row cannot
            // be re-checked afterwards — a /discover answer carries no certificate,
            // so the ceiling is applied by the REQUEST (see
            // [com.hikari.app.data.NsfwGate.capDiscoverCertification]). Dropping the
            // cache and rebuilding is what makes the switch change the feed the user
            // is looking at, instead of only the next app launch's.
            val app = getApplication<Application>() as HikariApp
            app.store.nsfwEnabledFlow().drop(1).collect {
                homeCache.clear()
                loadInternal(forceRefresh = true)
            }
        }
    }

    /** True when a stored Home pick refers to a collection, not an extension. */
    private fun isCollectionKey(key: String): Boolean = key.startsWith(COLLECTION_PREFIX)
    private fun isTrackerKey(key: String): Boolean = key.startsWith(TRACKER_PREFIX)
    private fun trackerKindOf(key: String): TrackerKind? = TrackerKind.of(key.removePrefix(TRACKER_PREFIX))
    private fun collectionIdOf(key: String): String = key.removePrefix(COLLECTION_PREFIX)

    fun selectProvider(id: String?) {
        setSelection(if (id == null) emptyList() else listOf(id))
    }

    /**
     * Saves a pick of one or more sources (the picker's Done button), and
     * rebuilds the feed from it. An EMPTY list is "All providers".
     *
     * The selection is what Home draws from, and it survives leaving the screen
     * (and a restart) through [com.hikari.app.data.AppStore.setHomeProviders].
     */
    fun setSelection(ids: List<String>) {
        val clean = ids.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (clean == _selection.value) return
        applySelection(clean)
        viewModelScope.launch { store.setHomeProviders(clean.toSet()) }
        viewModelScope.launch { loadInternal() }
    }

    /**
     * The row stream for the current pick(s) — EXTENSION catalogs only.
     *
     * Collections are deliberately NOT flattened into shelves here any more.
     * Home draws each picked (or pinned) collection as its own titled row of
     * FOLDER TILES (see the collection folder rows in HomeScreen), which is the
     * shape the reference app uses and the one the user built when they made the
     * catalogs: "see in home it showing like folder netflix, amazon, and clicking
     * it show inside the catalog poster and content … but in our hikari it not
     * creating the folder like that on home". Merging a collection's folders into
     * one shelf per folder produced exactly the poster rows that read as "it
     * shows a normal poster catalog instead of folders".
     *
     * A pick made ONLY of collections therefore has no extension feed at all:
     * falling through to `homeRowsStreamingFor(emptySet())` would quietly stack
     * every installed extension's home page under the user's own catalogs.
     */
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
                TrackerLibraryRepository.load(app, kind.key).getOrDefault(emptyList()).map { shelf ->
                    CatalogRow(providerId = TRACKER_PREFIX + kind.key, providerName = kind.label, title = shelf.title, items = shelf.items, key = TRACKER_PREFIX + shelf.key, catalogId = shelf.key, type = shelf.items.firstOrNull()?.type ?: MediaType.UNKNOWN, rawType = shelf.items.firstOrNull()?.rawType ?: "anime")
                }
            }
        }
        val trackerFlow = kotlinx.coroutines.flow.flow { emit(trackerRows()) }
        return when {
            // No pick means "All providers" — keep the normal extension feed.
            picks.isEmpty() -> repo.homeRowsStreaming()
            // A tracker pick must actually become a Home feed. Previously
            // trackerRows() existed but this switch never returned it, so
            // selecting AniList/Simkl produced an empty Home catalog.
            trackerKeys.isNotEmpty() && extensionIds.isNotEmpty() ->
                kotlinx.coroutines.flow.merge(
                    trackerFlow,
                    repo.homeRowsStreamingFor(extensionIds),
                )
            trackerKeys.isNotEmpty() -> trackerFlow
            extensionIds.isNotEmpty() -> repo.homeRowsStreamingFor(extensionIds)
            else -> kotlinx.coroutines.flow.flowOf(emptyList())
        }
    }

    /**
     * [forceRefresh] rebuilds the feed even when a cached one exists — used when
     * something outside the feed invalidates it (the TMDB title language), and
     * the cached copy is dropped by the caller first. The rows already on screen
     * stay put until the new ones arrive, so the page never blanks.
     */
    private suspend fun loadInternal(forceRefresh: Boolean = false) {
        loadJob?.cancel()
        // Only the newest load may touch the screen or the cache — see
        // [loadToken]. The check below is repeated after the one suspension
        // point that precedes the first write, because a load that started
        // earlier can be resumed after a newer one and would otherwise paint
        // rows the current pick never asked for.
        val token = ++loadToken
        val picks = _selection.value
        // A collection pick resolves to a saved collection; when it has been
        // deleted (or its id is stale) fall back to All instead of leaving the
        // user on an empty screen.
        val known = runCatching { store.collections() }.getOrDefault(emptyList())
        if (token != loadToken) {
            // A load that started earlier (possibly with an out-of-date, even
            // empty, selection) must not paint anything. Logged so a report can
            // tell this apart from "the pick really was dropped".
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
        val soloName = soloPick?.let { manager.byId(it)?.config?.name }
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
                // A rebuild for a reason the user did not ask for from here (the
                // TMDB title language moved): leave the rows already on screen
                // until the new feed arrives rather than blanking the page.
                _loading.value = false
            } else {
                _loading.value = true
                _rows.value = emptyList()
            }
        }
        // Keep the process alive (and awake) for the whole load: pressing Home
        // mid-load used to freeze the app and stop every catalog dead. See
        // [com.hikari.app.work.BackgroundWork].
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
            // One stream per source of the pick(s): a plain tap gives one
            // extension's feed, a multi pick gives every chosen extension's feed
            // plus a personal catalog's shelves — see [rowsFlowFor].
            val rowFlow = rowsFlowFor(kept, known)
            rowFlow.collect { incoming ->
                if (token != loadToken) return@collect
                // A pick of exactly ONE extension shows that extension's
                // catalog rows and NOTHING else. This is the promise the
                // provider pill makes ("4K HDHUB" over a screen that really is
                // 4K HDHUB's shelves), and it is enforced here instead of being
                // trusted from the flow: a provider list that holds the same id
                // twice (a stale entry an update left behind, one repo
                // registered through two engines) would otherwise let another
                // provider's rows through the id filter — which is exactly the
                // reported "no matter what provider I am selecting, it loads all
                // providers' catalogs".
                val rows = if (soloPick == null || soloName == null) incoming else incoming.filter {
                    it.providerId == soloPick && it.providerName == soloName
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
                // First load (nothing cached yet): paint each catalog the moment
                // it lands, so the first rows show in seconds instead of after
                // EVERY provider finished (the 20-25s wait). A refresh keeps the
                // cached feed on screen and swaps it in one go at the end.
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
                // Stream returned nothing (all providers slow / offline): fall
                // back to THIS pick's cached feed if there is one, otherwise
                // empty — never to whatever happened to be on screen before,
                // which is how another provider's feed could outlive the pick
                // that produced it (the pill said one provider, the rows said
                // another).
                _rows.value = homeCache[key].orEmpty()
                _loading.value = false
                com.hikari.app.data.Logs.log(
                    "Home",
                    "pick=" + (if (key == "all") "all" else key) + " produced no rows",
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
    // The in-place search overlay for a PICKED extension (see [openSearch]).
    //
    // SAVEABLE, and deliberately left TRUE while a result is opened: the overlay
    // is a place the user went to (they typed a query and picked from its
    // results), so coming back from a title has to land on the search they left,
    // not on the feed. As a plain `remember` the flag was destroyed with the
    // composable the moment the detail page was pushed, so Back returned to
    // Home and the query was gone — and it is not cleared when a result opens,
    // for the same reason.
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
    // User-made collections: offered in the same picker as the extensions, and
    // a collection pick ("collection:<id>") swaps the feed for that
    // collection's folders.
    val collectionsFlow = remember { app.store.collectionsFlow() }
    val collections by collectionsFlow.collectAsState(initial = emptyList())
    // Which repository each installed extension came from, for the picker's
    // rows ("Cs3 · CNC Verse"). Built HERE — while Home's feed is loading —
    // rather than inside the picker sheet: computing it in the sheet charged the
    // whole 500-provider × repo-list match to the moment the user tapped the
    // provider pill, which is the ~1s the picker used to take to appear. The
    // flow instance is remembered for the same reason as historyFlow below.
    val reposFlow = remember { app.store.reposFlow() }
    val repos by reposFlow.collectAsState(initial = emptyList())
    val repoNameByProvider = remember(providers, repos) {
        RepoProvenance.nameMap(providers.map { it.config }, repos)
    }
    val selectedCollection = collections.firstOrNull { selected == "$COLLECTION_PREFIX${it.id}" }
    // Which collections Home draws as FOLDER rows — a row titled with the
    // collection's name whose tiles are the folders inside it, so a tap ENTERS
    // that folder instead of flattening every folder's contents into one shelf
    // (the reference app's shape, and what the user asked for: "in home it
    // showing like folder netflix, amazon, and clicking it show inside the
    // catalog poster and content"). It is:
    //   • every collection the user picked — one or several (a multi pick used
    //     to flatten them all into poster shelves), and
    //   • every PINNED collection while Home is on "All", which is what pinning
    //     a catalog means: it shows up on Home by itself, without being picked.
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
    // The header's per-extension actions (translate, Cloudflare verify) and the
    // "search inside this extension?" prompt only make sense for an extension,
    // so a collection pick leaves the header in its plain "All" shape.
    val headerSelection = if (selectedCollection != null || selected?.startsWith(TRACKER_PREFIX) == true) null else selected
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
    // Settings → App Layout → Featured banner: which shape the banner takes and
    // which of its optional lines are drawn. Read here (not inside the banner)
    // so the whole feed recomposes to the new shape the moment it is picked.
    val heroStyleFlow = remember { app.store.heroStyleFlow() }
    val heroOverviewFlow = remember { app.store.heroOverviewFlow() }
    val heroRatingFlow = remember { app.store.heroRatingFlow() }
    val heroMetaFlow = remember { app.store.heroMetaFlow() }
    val heroStyle by heroStyleFlow.collectAsState(initial = HeroStyles.CAROUSEL)
    val heroOverview by heroOverviewFlow.collectAsState(initial = true)
    val heroRating by heroRatingFlow.collectAsState(initial = true)
    val heroMeta by heroMetaFlow.collectAsState(initial = true)
    val heroConfig = remember(heroStyle, heroOverview, heroRating, heroMeta) {
        HeroConfig(
            style = heroStyle,
            showOverview = heroOverview,
            showRating = heroRating,
            showMeta = heroMeta,
        )
    }
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
    val featuredLive = remember(rows) {
        val first = rows.firstOrNull()?.items.orEmpty()
        (first.filter { !it.backdropUrl.isNullOrBlank() }.ifEmpty { first }).take(8)
    }
    var tvHero by remember { mutableStateOf(emptyList<MediaItem>()) }
    if (TvMode.current()) {
        if (tvHero.isEmpty() && featuredLive.isNotEmpty()) tvHero = featuredLive
    } else if (tvHero.isNotEmpty()) {
        tvHero = emptyList()
    }
    val featured = if (TvMode.current()) tvHero.ifEmpty { featuredLive } else featuredLive
    val openGlobalSearch: () -> Unit = {
        Routes.navigateTab(nav, Routes.SEARCH)
    }
    // Tapping the header search icon asks HOW to search when a specific
    // extension's catalog is being browsed: globally across every provider, or
    // scoped to the extension you're looking at. With no extension selected
    // there's only one sensible answer, so it goes straight to global search.
    // A COLLECTION counts as a scope too: browsing "abc" and tapping the
    // magnifier must be able to search inside abc and not only everywhere.
    // The in-place overlay's scope: the picked extensions' ids, or every enabled
    // extension when the pick is All. Set on the same tap that opens it.
    var overlayIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val openSearch: () -> Unit = {
        // With ONE extension picked, the magnifier searches that extension IN
        // PLACE, here on Home: the button sits inside the picked extension's own
        // header and that pick is what Home is browsing, so sending the user to
        // the Search tab to pick the same extension again was a detour — and,
        // with four identically-named "AniKoto" installs, an obscure one.
        //
        // A collection still asks HOW to search (across everything, or inside
        // it), and "All" goes straight to the Search tab, where the provider row
        // is the only thing that can narrow a search that has no pick.
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
            )
        )
    }
    val openVerify: () -> Unit = {        scope.launch {
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
            // The WebView button belongs to the selected extension. Never
            // fall back to the last Cloudflare-blocked host from another
            // extension: that stale global value was exactly how tapping the
            // button for extension B could open extension A's page.
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

    // Two rows can carry the same key when an extension offers the same catalog
    // twice (or two catalogs under one name): a duplicated Lazy key is a crash
    // in Compose, not a warning, so repeats are dropped before the feed is
    // built. Done ONCE per change of [rows] rather than inline in the
    // LazyColumn's scope — that built a fresh list and a fresh key string for
    // every row on every recomposition of the feed, i.e. during every scroll
    // step.
    val uniqueRows = remember(rows) {
        rows.distinctBy { it.key.ifBlank { "${it.providerName}|${it.title}" } }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            // The taskbar floats over the feed, so the feed has to end below it
            // rather than above a reserved strip (see LocalTaskbarInset).
            contentPadding = PaddingValues(bottom = LocalTaskbarInset.current + 16.dp)
        ) {
            // Crash report: NOT inline any more. A stack trace dumped into the
            // feed made the feed look broken; the one-shot warning panel below
            // (see the GlassDialog after the Box) says what happened and points
            // at Settings → Logs, then stays out of the way.
            item {
                // The header is its OWN strip above the featured banner, never
                // painted over it. It used to be an overlay inside the banner's
                // Box (see the old `overlay = true` branch): the app name, the
                // tagline and the search/translate/web-view buttons were drawn
                // directly on the artwork, and with the short "Compact strip"
                // banner — 148dp tall — the header covered most of it and the
                // banner's own title/meta lines ended up jammed right under the
                // tagline. That is the overlap the user reported ("it's
                // overlapping the Hikari name and the app line and search icon,
                // web-view and translate button — make the header down so it
                // won't overlap"), and the fix is to stop layering them at all:
                // the header keeps its own row of the feed, the banner starts
                // below it, and no hero style can collide with it again.
                Column(Modifier.fillMaxWidth()) {
                    HomeHeader(
                        selected = headerSelection,
                        onSearch = openSearch,
                        onTranslate = { showTranslate = true },
                        onVerify = openVerify,
                        overlay = false,
                        onSettings = openSettings,
                    )
                    // Television: the provider strip sits under the header like
                    // CloudStream's — every extension one OK-press away, hold
                    // OK to multi-pick, instead of the phone's floating pill
                    // and sheet (which stay as the overflow via ▦).
                    if (TvMode.current()) {
                        var tvProvQuery by rememberSaveable { mutableStateOf("") }
                        val stripProviders = if (tvProvQuery.isBlank()) activeProviders
                        else activeProviders.filter {
                            it.config.name.contains(tvProvQuery.trim(), ignoreCase = true)
                        }
                        OutlinedTextField(
                            value = tvProvQuery,
                            onValueChange = { tvProvQuery = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium,
                            placeholder = {
                                Text(
                                    tr("Search providers…"),
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
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 12.dp, end = 12.dp, top = 8.dp)
                                .tvTextFieldKeys(tvProvQuery),
                        )
                        TvProviderStrip(
                            providers = stripProviders,
                            selection = selection,
                            onPickSingle = { vm.selectProvider(it) },
                            onPickAll = { vm.setSelection(emptyList()) },
                            onToggleMulti = { id ->
                                vm.setSelection(
                                    if (id in selection) selection - id
                                    else (selection + id).distinct()
                                )
                            },
                            onOpenPicker = { showPicker = true },
                        )
                    }
                    if (featured.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        // Television gets the cinematic hero (static backdrop,
                        // title, meta, Play / View Details — no auto-advance
                        // carousel, which a 1GB box cannot afford to repaint);
                        // the phone keeps its own banner styles.
                        if (TvMode.current()) {
                            TvCinematicHero(
                                items = featured,
                                onOpen = { item ->
                                    Routes.safeNavigate(
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
                    // Genre strip: sits UNDER the hero artwork, which is where a
                    // viewer who has not decided what to watch looks next. A
                    // genre pick opens everything tagged with it — films AND
                    // series (see [HomeGenreStrip]) — so nothing has to be
                    // searched for before something can be watched. With an
                    // extension picked, the tap first asks WHERE: that
                    // extension alone, or everything (see [genreScope]).
                    HomeGenreStrip { name, genresText, keywordsText ->
                        val ext = selection.filter { !it.startsWith(COLLECTION_PREFIX) }.toSet()
                        if (ext.isEmpty()) {
                            openTmdbGenre(name, genresText, keywordsText)
                        } else {
                            genreScope = GenreScopePick(name, genresText, keywordsText, ext)
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
                        // The ✕ on a card drops just that entry from the shared
                        // watch-history store, so it leaves the shelf and the
                        // History tab at the same time.
                        onRemove = { h -> scope.launch { app.store.removeHistory(h.uniqueKey) } },
                    )
                }
            }
            // A personal catalog shows its OWN FOLDERS — the same tiles its page
            // draws in Settings ("Your own folders of catalogs, shown on Home").
            // Tapping a folder enters it, which is the hierarchy the user built:
            // "in home it show same folder … i can click animation to enter in
            // that animation box and see all catalog". One row PER collection, so
            // a multi pick shows each collection's folders under its own name
            // instead of merging them into poster shelves.
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
            // Two rows can carry the same key when an extension offers the same
            // catalog twice (or two catalogs under one name): a duplicated Lazy
            // key is a crash in Compose, not a warning, so repeats are dropped
            // before the feed is built (see [uniqueRows]).
            //
            // No "and only when no collection is picked" guard any more: a pick
            // made solely of collections yields NO extension rows at all (see
            // HomeViewModel.rowsFlowFor), while a multi pick that contains both
            // shows the collections' folder rows AND the extensions' shelves —
            // which is what picking several sources means.
            uniqueRows.forEach { row ->
                item(
                    key = row.key.ifBlank { "${row.providerName}|${row.title}" },
                    // One content type for every shelf, so the LazyColumn can
                    // REUSE the subtree (and its remembered poster style) between
                    // rows instead of composing a fresh one when a row scrolls
                    // off and another on.
                    contentType = "media-row",
                ) {
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
                            // A row of the picked collection's OWN shelves shows
                            // the WHOLE collection (every folder and every
                            // catalog in it, as one scrollable grid) — a folder
                            // row used to open just that folder, which left the
                            // user unable to see the rest of the collection.
                            // An EXTENSION row keeps its own catalog, even with
                            // a collection picked: it used to be sent to the
                            // collection grid as well, so "Show all" on an
                            // extension's shelf opened an unrelated page (and,
                            // for a collection holding nothing, a page that only
                            // said so). The row's key is what tells them apart —
                            // the collection's rows are keyed "coll|…" by
                            // CollectionsRepository.
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
                        // A `by remember` property cannot be smart-cast, so the
                        // non-null answer is taken once into a local. A multi
                        // pick has no single id: the first extension stands in.
                        val single = selected != null
                        val selectedKey = selected
                            ?: selection.firstOrNull { !it.startsWith(COLLECTION_PREFIX) } ?: ""
                        val reason = if (single) engineFailureReason(selectedKey) else null
                        // First paint failed (a cold plugin, DNS, a host that
                        // woke up late): retry once by itself after a beat
                        // instead of parking the user on Retry for a transient.
                        // Once per pick — a genuinely empty extension keeps its
                        // message after the one extra attempt.
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
                        // Null-safe by construction: a multi pick has selected ==
                        // null, and a ConcurrentHashMap.get(null) throws — the
                        // old branch could never see null, this one can.
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
                            EmptyState(
                                title = I18n.t("Couldn't load %s")
                                    .replace("%s", selectedName ?: I18n.t("This extension")),
                                subtitle = reason?.takeIf {
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
        }

        // Floating source pill (Anikoto-style): shows the current provider and
        // opens the picker sheet. It has to clear the taskbar, which is drawn
        // OVER the page rather than in a strip of its own — pinned to the
        // bottom-right corner as it was, it ended up underneath the bar's own
        // buttons and could not be tapped at all. [LocalTaskbarInset] is exactly
        // the room the bar covers, so the pill lifts itself by that and then
        // keeps its own 14dp of air above the bar.
        Surface(
            onClick = { showPicker = true },
            shape = RoundedCornerShape(50),
            // The pill's own frosted panel, not the theme's `surfaceVariant`:
            // that variant is a nearly-transparent overlay, and raising its
            // alpha turned it into a bright slab the white label vanished into
            // (the Dark Glass UI report). The surface is the same panel every
            // other glass card in the app is cut from.
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

        // In-place search for the picked extension — drawn LAST inside the Box so
        // it covers the feed. Drawn over the feed rather than as a screen of its
        // own because it answers a question about the feed that is already
        // loaded: "which of this extension's titles did you mean?".
        if (showHomeSearch) {
            // One name for one extension, the counted label for several, All
            // for everything — the same rule the header pill itself uses.
            val overlayName = when {
                overlayIds.size == 1 -> providers.firstOrNull { it.config.id == overlayIds.first() }
                    ?.config?.name ?: selectedName
                selection.isNotEmpty() -> selectedName
                else -> tr("All providers")
            }
            HomeSearchOverlay(
                providerIds = remember(overlayIds) { overlayIds.toSet() },
                providerName = overlayName,
                feedItems = remember(rows) { rows.flatMap { it.items } },
                genre = overlayGenre,
                onClearGenre = { overlayGenre = "" },
                onClose = { showHomeSearch = false; overlayGenre = "" },
                onOpen = { item ->
                    // The overlay is NOT closed here: it stays open so that Back
                    // from the title returns to the search the user was reading
                    // (see the note on `showHomeSearch`). The overlay's own ✕ and
                    // BackHandler are what close it.
                    Routes.safeNavigate(
                        nav,
                        if (item.rawType == "manga") {
                            Routes.mangaDetail(item.providerId, item.id, item.title, item.posterUrl)
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
    // narrow it) or scoped to what is on screen — the extension whose catalog is
    // being browsed, or the PERSONAL CATALOG being browsed. A collection is a
    // real scope: "In abc" searches only what abc holds (see
    // [Routes.searchInCollection]), which is what the user asked for when they
    // built the catalog and then wondered where its name was.
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

/**
 * The folder tiles of one personal catalog, drawn on Home.
 *
 * A personal catalog IS a tree: collections hold folders, folders hold catalogs.
 * Home used to flatten that tree into one shelf per folder (every title the
 * folder holds, all mixed together), so a catalog the user had organised by
 * hand — Animation, Anime, Netflix, Amazon — reached Home as its contents and
 * the folders themselves were nowhere. This draws the folders instead, using the
 * very same tiles the catalog's own page draws (see [FolderTile]), and a tap
 * ENTERS the folder: the same hierarchy, one level at a time, which is what the
 * user asked for ("if i select it in home then show the folder … and i can click
 * animation to enter in that animation box and see all catalog").
 *
 * "Show all" keeps the old flat view one tap away (every folder, every catalog,
 * as one grid — see [CollectionGridScreen]).
 */
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    /**
     * Repo provenance for [providers], hoisted by the caller when it already has
     * the map — Home computes it while idle so tapping a provider pill opens the
     * sheet with no work on the tap path. Null (Search) means "compute it here"
     * from the same two inputs. See [RepoProvenance.nameMap].
     */
    repoNameByProvider: Map<String, String>? = null,
) {
    var query by remember { mutableStateOf("") }
    // Multi-select is OFF until a row is HELD: that is the gesture the user
    // asked for ("if i press one provider for more than 1.5 second it give me
    // option to multi select like i can select more provider with it"). While it
    // is on, a tap ticks a row into [working] instead of leaving the sheet, and
    // the Done button beside the title saves the lot — dismissing the sheet
    // saves nothing, so an accidental mode change can never change Home.
    var multi by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(selection) }
    // The sources pinned to the top of this list, newest first. Read HERE rather
    // than passed in by the caller: the sheet is the only thing that cares about
    // them, and both of its callers (Home and Search) get the pins for free
    // instead of each having to thread the same two arguments through.
    val app = LocalContext.current.applicationContext as HikariApp
    val scope = rememberCoroutineScope()
    val pinned by remember { app.store.pinnedProvidersFlow() }
        .collectAsState(initial = emptyList<String>())
    // The user's own provider FOLDERS — a saved multi-pick, one tap to apply
    // (see AppStore.providerFoldersFlow). Read HERE, like the pins, so both of
    // the sheet's callers get them without threading two more arguments through.
    val folders by remember { app.store.providerFoldersFlow() }
        .collectAsState(initial = emptyList<ProviderFolder>())
    val connectedTrackers by remember { app.store.trackersFlow() }.collectAsState(initial = emptyList())
    var showFolderDialog by remember { mutableStateOf(false) }
    var folderName by remember { mutableStateOf("") }
    var folderToDelete by remember { mutableStateOf<ProviderFolder?>(null) }
    // What "Save as folder" would save: the ticked rows that ARE extensions —
    // a collection key is not a provider and has no place in a provider folder.
    val saveable = remember(working) {
        working.filter { !it.startsWith(COLLECTION_PREFIX) }
    }
    // Engine filter: every kind that has at least one installed extension, in a
    // stable order, so a user with dozens of installs can narrow the list to
    // just their CloudStream plugins, just their Nuvio providers, and so on.
    val kinds = remember(providers) {
        providers.map { it.config.type }.distinct().sortedBy { it.groupLabel }
    }
    // Pinned first — in the order they were pinned, so the row just pinned is
    // the first one in the list — then everything else alphabetically. The pin
    // is a promise that this source will be the first thing the user sees next
    // time they open the sheet, and an alphabetical list would break it for
    // every source whose name sorts low.
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
    // The personal catalogues sit under ONE parent row (collapsed by default),
    // not scattered among the providers: every catalogue the user builds lands
    // in the same place, and the provider list stays a provider list.
    var cataloguesOpen by remember { mutableStateOf(false) }
    val shownCollections = remember(collections, query) {
        if (query.isBlank()) collections
        else collections.filter { it.name.contains(query, ignoreCase = true) }
    }
    // ---- Save as folder / delete a folder --------------------------------
    // Both dialogs sit OUTSIDE the sheet's own content: a folder is a thing the
    // user keeps, not a mode of the picker. Saving does NOT dismiss the sheet —
    // the user asked for a folder, not to leave the picker.
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

    // Minimal, list-first: a heading, a flat search field, the chip row, then
    // plain rows separated by hairlines (the glass cards are gone — a long list
    // of nearly identical names read as a wall of glass). Opens fully expanded
    // so the whole list is reachable without a drag nobody knows about.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    tr("Choose an extension"),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // The Done button sits ABOVE the list ("add done button above
                // in provider selection box"), which is where a thumb expects it
                // and the only place a long list cannot hide it. "Save as folder"
                // sits beside it: a multi pick the user reaches for daily becomes
                // a folder they can apply in one tap next time (see the folders
                // section below).
                if (multi && saveable.isNotEmpty()) {
                    TextButton(onClick = { folderName = ""; showFolderDialog = true }) {
                        Text(tr("Save as folder"))
                    }
                }
                if (multi) {
                    Button(
                        onClick = { onDone(working) },
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
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
            }
            if (!LocalHideHelp.current) {
            Text(
                if (multi) {
                    if (working.isEmpty()) tr("Tap the sources to show on Home, then Done.")
                    else I18n.t("%s sources picked — tap more, then Done.")
                        .replace("%s", working.size.toString())
                } else {
                    tr(
                        "Only the selected extension's catalog is shown on Home. " +
                            "Hold a source for half a second to pick several, or tap " +
                            "its pin to keep it at the top of this list."
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 14.dp)
            )
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                placeholder = {
                    Text(
                        tr("Search extensions…"),
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
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().tvTextFieldKeys(query),
            )
            // Categories: All first, then one chip per engine that is actually
            // installed. Picking one only NARROWS the list below.
            if (kinds.isNotEmpty()) {
                LazyRow(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
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
                }
            }
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, bottom = 24.dp),
            ) {
                if (shownCollections.isNotEmpty()) {
                    item {
                        PickerSectionLabel(tr("Personal catalogues"))
                    }
                    item(key = "personal-catalogues") {
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
                    items(shownCollections, key = { "collection|${it.id}" }) { c ->
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
                    item {
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
                    item {
                        PickerSectionLabel(tr("Your folders"))
                    }
                    items(folders, key = { "pfolder|${it.name}" }) { folder ->
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
                                    // A folder is a saved pick: tapping it ticks
                                    // its extensions, so the Done button saves
                                    // them with anything else that is ticked.
                                    working = if (anyOn) (working - ids.toSet()).distinct()
                                    else (working + ids).distinct()
                                } else {
                                    // One tap is the whole point of a folder: Home
                                    // switches to exactly these extensions, with no
                                    // multi-select round trip — and Search, the
                                    // player's server list and everything else that
                                    // reads the current pick follow, because the
                                    // pick IS these provider ids.
                                    onDone(ids)
                                }
                            },
                        )
                    }
                }
                if (connectedTrackers.isNotEmpty()) {
                    item { PickerSectionLabel(tr("Trackers")) }
                    item(key = "trackers-parent") {
                        PickerRow(label = tr("Trackers"), isSelected = false, multi = false, leadingIcon = Icons.Filled.List,
                            supporting = I18n.t("%s connected").replace("%s", connectedTrackers.size.toString()),
                            expandable = true, expanded = "__trackers__" in expanded,
                            onToggleExpand = { expanded = if ("__trackers__" in expanded) expanded - "__trackers__" else expanded + "__trackers__" },
                            onClick = { expanded = if ("__trackers__" in expanded) expanded - "__trackers__" else expanded + "__trackers__" })
                    }
                    if ("__trackers__" in expanded) {
                        items(connectedTrackers, key = { "tracker:" + it.kind.key }) { account ->
                            val key = TRACKER_PREFIX + account.kind.key
                            PickerRow(label = account.kind.label, isSelected = if (multi) key in working else selection.contains(key), multi = multi,
                                supporting = account.user.ifBlank { tr("Connected") },
                                onLongClick = { if (!multi) { multi = true; working = (selection + key).distinct() } },
                                onClick = { if (multi) working = if (key in working) working - key else (working + key).distinct() else onPick(key) })
                        }
                    }
                }
                item { PickerSectionLabel(tr("Providers")) }
                // The gesture, said where the rows are. A user who has never
                // multi-selected has no way to guess that a HOLD is what does it
                // (the gesture exists because it was asked for by name), and a
                // row that looks like every other row does not say it either.
                //
                // Deliberately NOT behind the hide-explanations switch: the row
                // that would be hidden is the only place the gesture is
                // documented, so hiding it with the other explanations made
                // multi-select undiscoverable exactly for the user who had
                // tidied the interface.
                item(key = "providers-hold-hint") {
                    Text(
                        tr("Hold any provider for half a second to select more than one."),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 10.dp, top = 2.dp, bottom = 4.dp),
                    )
                }
                item {
                    PickerRow(
                        label = "All providers",
                        isSelected = if (multi) working.isEmpty() else selection.isEmpty(),
                        multi = multi,
                        onLongClick = {
                            if (!multi) {
                                multi = true
                                working = emptyList()
                            }
                        },
                        onClick = { if (multi) working = emptyList() else onPick(null) },
                    )
                }
                items(packs, key = { it.key }) { pack ->
                    // A stream-only addon is named with its engine so the row
                    // explains itself: it adds servers, its browsing comes from
                    // TMDB (see TmdbBrowse).
                    val key = pack.key
                    val streamOnly =
                        com.hikari.app.providers.StremioAddon.streamOnlyAddons[key] == true
                    val ids = pack.members.map { it.config.id }
                    val tickedIds = ids.filter { it in working }
                    val ticked = tickedIds.isNotEmpty()
                    val allTicked = tickedIds.size == ids.size
                    val isPinned = pinOrder.containsKey(key)
                    val open = key in expanded
                    PickerRow(
                        label = pack.label,
                        isSelected = if (multi) ticked else selection.contains(key),
                        multi = multi,
                        // The pin is on EVERY row, not revealed by a hold: with
                        // hundreds of installed extensions the user is looking
                        // for the pin itself, and a control they have to know
                        // about first cannot be found.
                        pinned = isPinned,
                        onTogglePin = { scope.launch { app.store.togglePinnedProvider(key) } },
                        expandable = pack.isPack,
                        expanded = open,
                        onToggleExpand = {
                            expanded = if (open) expanded - key else expanded + key
                        },
                        supporting = when {
                            streamOnly -> I18n.t("%s addon · browses TMDB").replace(
                                "%s",
                                pack.primary.config.type.groupLabel,
                            )
                            !pack.isPack -> repoLabels[pack.primary.config.id]
                                ?.let { "${pack.primary.config.type.groupLabel} · $it" }
                            // In multi-select the row is a checkbox for a whole
                            // extension, so it says how much of the pack is on.
                            multi && ticked && !allTicked -> I18n.t("%s of %s picked")
                                .replaceFirst("%s", tickedIds.size.toString())
                                .replaceFirst("%s", ids.size.toString())
                            else -> pack.countLabel +
                                (pack.detailLabel.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
                        },
                        onLongClick = {
                            if (!multi) {
                                multi = true
                                working = (selection + key).distinct()
                            }
                        },
                        onClick = {
                            if (multi) {
                                // Ticking a COLLAPSED extension row ticks every
                                // source it publishes — that is what the row is.
                                // Its sources are one caret away for picking one.
                                //
                                // ...and UNticking it takes back every source,
                                // which is the half that was broken: the test
                                // used to be "are ALL of them on?", so a row that
                                // was only PARTLY on — the normal state of the
                                // extension you are already watching, since the
                                // stored selection is one of its sources — went
                                // to "all on" on the first tap instead of off.
                                // The tick is a tick: a tap on a row that has any
                                // of its sources on turns them all off, and a tap
                                // on a row with none of them on turns them all on
                                // (reported as "unselecting a provider ... it not
                                // unticking"). A row left partly on stays pickable
                                // that way: untick, tick, or use the caret.
                                working = if (ticked) working - ids.toSet()
                                else (working + ids).distinct()
                            } else {
                                // A plain pick still means the ONE source the row
                                // stands for (the extension's first), exactly as
                                // tapping that source's own row always did.
                                onPick(key)
                            }
                        },
                    )
                    if (pack.isPack && open) {
                        pack.members.forEachIndexed { i, member ->
                            val mkey = member.config.id
                            val mticked = mkey in working
                            Row(Modifier.fillMaxWidth()) {
                                Spacer(Modifier.width(18.dp))
                                Box(Modifier.weight(1f)) {
                                    PickerRow(
                                        label = pack.memberLabel(i),
                                        isSelected = if (multi) mticked else selection.contains(mkey),
                                        multi = multi,
                                        pinned = pinOrder.containsKey(mkey),
                                        onTogglePin = {
                                            scope.launch { app.store.togglePinnedProvider(mkey) }
                                        },
                                        onLongClick = {
                                            if (!multi) {
                                                multi = true
                                                working = (selection + mkey).distinct()
                                            }
                                        },
                                        onClick = {
                                            if (multi) {
                                                working = if (mticked) working - mkey
                                                else working + mkey
                                            } else {
                                                onPick(mkey)
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                if (packs.isEmpty() && query.isNotBlank()) {
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

/**
 * One engine chip in the picker ("All", "CloudStream", "Nuvio", …).
 *
 * The selected chip is SOLID accent with contrast text; the rest are flat and
 * outlined. That is the whole signal — no gradients, no glass: on a row of
 * pills the filled one reads instantly as "this is the filter in force", and
 * the outlined ones read as the alternatives.
 */
@Composable
private fun FilterChipLine(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        border = if (selected) null else BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
        ),
        modifier = Modifier.padding(vertical = 2.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
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

/**
 * Television provider strip, CloudStream-style: every enabled extension as a
 * chip in one sideways row above the feed, so a remote reaches providers
 * without opening the phone-shaped picker sheet at all. Tap = watch that
 * extension alone; hold OK half a second = add it to a multi pick (the same
 * hold the picker sheet uses); "All" = everything; the trailing tile opens
 * the full sheet for search, pins and folders.
 */
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
            .padding(start = 12.dp, end = 12.dp, top = 8.dp),
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

/**
 * One row of the extension picker. Deliberately FLAT: a plain row with a
 * hairline under it, not a floating glass card. The picker is a long list of
 * nearly identical names, and a card per name turned it into a wall of glass —
 * the flat list (with the tinted selected row and its filled accent
 * circle-check) is what makes the current choice readable at a glance.
 *
 * [leadingIcon] is for the one row that does something rather than selects
 * ("Manage collections"); [showDivider] is turned off on the last row of a
 * section so the heading below it is not fenced off by two lines.
 *
 * [onTogglePin] draws the pin button on the right of the row and is what the
 * user taps to float this source to the top of the list; [pinned] is its state
 * (an accent pin on a floatable row, a muted one on the rest). The pin is its
 * own tap target inside the row and consumes its own taps, so tapping it never
 * also picks the row — see the note on the gesture above.
 *
 * In [multi] mode the tick box is drawn on EVERY row (empty ring when it is not
 * picked), so a row says "I can be ticked" rather than only the ticked ones
 * looking different.
 *
 * The row's own tap handling is [holdOrTap] rather than `clickable`, because the
 * multi-select gesture is a deliberate HOLD (0.5s — see [HOLD_MS]) and
 * `clickable`/`combinedClickable` would fire at the platform's own timeout or
 * swallow the press the list needs to scroll. The price
 * is the touch ripple, which a bottom-sheet row can do without.
 */
@Composable
private fun PickerRow(
    label: String,
    isSelected: Boolean,
    supporting: String? = null,
    leadingIcon: ImageVector? = null,
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
    // The gesture handler below is a `pointerInput` keyed on (label, multi), so
    // it does NOT restart when the tick state changes — and the lambdas it was
    // given would then be the FIRST composition's, closing over a stale
    // `working` list. Every tap after the first recomposition computed from
    // that dead snapshot: deselecting removed an already-removed key (a no-op)
    // and reselecting re-added into the old list (silently lost). These refs
    // always point at the CURRENT lambdas, so the gesture can never go stale.
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
            // The caret that opens an extension row's own sources. It is its own
            // tap target (like the pin), so opening a pack never means picking
            // it — and a row with one source draws no caret at all.
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
            // The pin sits last, after the tick: it is a control on the row, not
            // part of what the row is telling the user. It keeps a 34dp touch
            // target on a 13dp-tall row, and being its own clickable is what
            // makes a tap on it pin the source instead of choosing it.
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

/**
 * How long a picker row must be held before multi-select starts.
 *
 * Half a second, on the user's own instruction ("make it 1.5second to 0.5 second
 * for multi select to enable"): the hold was deliberately long when it was the
 * only thing standing between a tap and a mode change, but half a second is
 * still far longer than a tap and it makes ticking four extensions a gesture
 * instead of a wait. It is worth being explicit about what it is NOT: this is
 * still not the platform's long-press timeout (~500ms, which a slow deliberate
 * tap can trip) — it is OUR measurement of a press, and a drag cancels it.
 */
internal const val HOLD_MS = 500L

/**
 * "Tap, or HOLD for a moment".
 *
 * Shared: the Manga tab's Browse list uses the same gesture to reveal an
 * engine's pin (see [com.hikari.app.ui.screens.MangaScreen]), so the two holds
 * in the app are the same length and behave the same way around a scroll.
 *
 * `combinedClickable` uses the platform's long-press timeout, which fires on a
 * press that is merely unhurried, and the gesture also has to survive the list
 * being scrolled. So the press is timed here: released before [HOLD_MS] it is a
 * tap, still down after it fires [onHold], and a drag (past the touch slop, or a
 * change the enclosing scroller has already consumed) is left completely alone
 * so the list still scrolls normally.
 */
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
    ProviderType.STREMIO, ProviderType.UNIVERSAL -> p.config.url.takeIf { it.startsWith("http://") || it.startsWith("https://") }?.let { raw ->
        runCatching { val u = java.net.URI(raw); val path = u.path.orEmpty().removeSuffix("/"); val base = if (path.endsWith("/manifest.json", true)) path.dropLast("/manifest.json".length) else path; u.scheme + "://" + u.authority + if (base.isBlank()) "/" else base }.getOrNull()
    }
    ProviderType.HIKARI -> com.hikari.app.hiki.HikariRuntime.providerFor(p.config)?.mainUrl
    ProviderType.SKYSTREAM -> com.hikari.app.skystream.SkyStreamPluginManager.siteUrlOf(p.config)
    ProviderType.ANIYOMI -> com.hikari.app.aniyomi.AniyomiExtensionManager.siteUrlOf(p.config)
    ProviderType.MANGA -> com.hikari.app.manga.MangaExtensionManager.siteUrlOf(p.config)
    ProviderType.ANYMEX, ProviderType.ANYMEX_MANGA -> com.hikari.app.anymex.AnymexPluginManager.siteUrlOf(p.config)
    ProviderType.SORA -> p.config.extra?.takeIf { it.startsWith("http") }?.let { src -> runCatching { val u = java.net.URI(src); u.scheme + "://" + u.host + "/" }.getOrNull() }
    ProviderType.NUVIO -> sourceSiteUrl(java.io.File(p.config.url), p.config.name)
    ProviderType.VEGA -> runCatching {
        val dir = com.hikari.app.providers.vega.VegaPluginManager.dirOf(p.config)
        sourceSiteUrl(java.io.File(dir, "meta.js"), p.config.name)
            ?: sourceSiteUrl(java.io.File(dir, "posts.js"), p.config.name)
            ?: sourceSiteUrl(java.io.File(dir, "stream.js"), p.config.name)
    }.getOrNull()
    ProviderType.CS3 -> runCatching {
        val file = java.io.File(p.config.url); if (!file.exists()) return@runCatching null
        val apis = com.hikari.app.cs3.Cs3PluginManager.apisFor(com.hikari.app.HikariApp.instance, file)
        apis.getOrNull(p.config.id.substringAfterLast("|").toIntOrNull() ?: 0)?.mainUrl?.takeIf { it.startsWith("http") }
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
/**
 * The Home feed cache: one entry per pick key ("all" for the combined feed, the
 * joined pick keys otherwise), holding rows whose posters have already been
 * collapsed into disk-cache tokens.
 *
 * It is deliberately a PROCESS-WIDE object rather than a field of
 * [HomeViewModel]. Returning from the player can recreate the activity, and with
 * it the view model; a per-instance map would then be empty on every return and
 * Home would fall back to a spinner and a full re-fetch of the provider the user
 * is already on. Every access happens on the main thread (the loader's own
 * dispatcher), so it needs no locking.
 */
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

/**
 * The engine's own record of why the last call to [providerId] failed, when it
 * failed — the chain Home's empty state reads, and now the in-place search's too.
 *
 * Aniyomi was missing from this chain once, so an Aniyomi catalog that failed
 * fell through to the generic "check the WebView / it may be down" line — which
 * sent users looking for a Cloudflare verification that was never the problem.
 * An Aniyomi extension that failed to load has a real, specific reason ("none of
 * its sources could be loaded", a class-load failure, an unsupported extension
 * library) and it is reported here now. Manga engines were missing for the same
 * reason: a manga source that fails to LINK or load (an OkHttp class it needs
 * absent from the app, a source whose own assertions refuse our client) fell
 * through to the "the site may be down" line. Its own record is
 * `MangaProvider.lastOutcome`, and the SUCCESS lines in that map ("✓ 40 titles")
 * are filtered out so only a real failure shows — an empty result has to be told
 * apart from a failed one.
 *
 * `.hiki` extensions were the last engine missing here, and the omission hurt
 * them most: the bridge answered an empty catalog list for every LOCAL failure
 * it had (the archive has no bundled plugin, the plugin's load() threw, it
 * registered nothing, its home page came back empty), so with no entry in this
 * chain the user was told to open the extension's WEBSITE and check it for a
 * Cloudflare verification page that was never involved. The extension now names
 * its own reason and the adapter records it in
 * [com.hikari.app.providers.HikariProviderAdapter.catalogErrors].
 */
private fun engineFailureReason(providerId: String): String? =
    com.hikari.app.providers.HikariProviderAdapter.catalogErrors[providerId]
        ?: com.hikari.app.cs3.Cs3MainApiProvider.catalogErrors[providerId]
        ?: com.hikari.app.providers.StremioAddon.catalogErrors[providerId]
        ?: com.hikari.app.nuvio.NuvioScraper.catalogErrors[providerId]
        ?: com.hikari.app.skystream.SkyStreamProvider.catalogErrors[providerId]
        ?: com.hikari.app.aniyomi.AniyomiProvider.catalogErrors[providerId]
        ?: com.hikari.app.manga.MangaProvider.lastOutcome[providerId]
            ?.takeIf { !it.startsWith("✓") && !it.startsWith("✔") }

/**
 * The in-place Home search: one picked extension's own search, drawn over the
 * feed.
 *
 * It runs the SAME sweep the Search tab runs —
 * [ContentRepository.searchStreaming], scoped to the one picked provider — so
 * results are paged, deduplicated and STREAMED IN as each page lands, exactly as
 * they are there. The differences are only that this one is already scoped (the
 * pick IS the scope) and that it never leaves Home. A tapped result opens the
 * detail page the way the Search tab's grid does (a manga result opens the manga
 * page).
 *
 * The scan belongs to this composable's own effect, so searching on Home can
 * never disturb what the Search tab is showing.
 */
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
    HOME_KIND_MOVIE -> item.type == MediaType.MOVIE && !item.looksAnime()
    HOME_KIND_SERIES -> item.type == MediaType.SERIES && !item.looksAnime()
    HOME_KIND_ANIME -> item.looksAnime()
    HOME_KIND_MOVIE_SERIES ->
        (item.type == MediaType.MOVIE || item.type == MediaType.SERIES) && !item.looksAnime()
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

/**
 * One catalogue the overlay's kind browser is paging through: which provider,
 * which catalogue, whether that catalogue reads as anime (see the scan), the
 * next page to ask for, and whether it has answered empty (its end).
 */
private class BrowseCursor(
    val providerId: String,
    val ref: CatalogRef,
    val isAnimeCat: Boolean,
    var nextPage: Int = 2,
    var exhausted: Boolean = false,
)

/** True when [item] belongs to the genre [name]: a genre tag containing the
 *  name (either direction — "Science Fiction" matches a "Sci-Fi" ask poorly,
 *  but a tag match in either direction covers the common spellings), or a
 *  title carrying the word itself. Items with no tags at all never match: a
 *  site scraper that tags nothing cannot answer a genre question, and saying
 *  so (the overlay's empty state) beats a wall of unfiltered posters. */
private fun homeGenreKeep(item: MediaItem, name: String): Boolean {
    val q = name.trim().lowercase()
    if (q.isEmpty()) return true
    if (item.genres.any { g ->
        val t = g.trim().lowercase()
        t.isNotEmpty() && (t.contains(q) || q.contains(t))
    }) return true
    return false
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
    var kindKey by remember { mutableStateOf(HOME_KIND_ALL) }
    var typed by rememberSaveable { mutableStateOf("") }
    var applied by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    // The keypad is what the user came for: open with the caret already in the
    // box. (On a television focus has to be given explicitly for the same
    // reason — a remote has no pointer to tap the field with.)
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    // One request per typed word. A new query replaces the previous effect, which
    // cancels the old scan with it — so a stale page can never land under a newer
    // query.
    LaunchedEffect(applied, providerIds) {
        if (applied.isBlank() || providerIds.isEmpty()) {
            results = emptyList()
            searching = false
            return@LaunchedEffect
        }
        searching = true
        try {
            repo.searchStreaming(applied, providerIds = providerIds).collect { raw ->
                results = withContext(Dispatchers.IO) { raw.map { it.shrinkPoster() } }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Whatever pages already landed stay on screen; the empty state below
            // says the rest did not arrive (see [engineFailureReason]).
        } finally {
            searching = false
        }
    }

    LaunchedEffect(typed) {
        delay(400)
        applied = typed.trim()
    }

    BackHandler { onClose() }

    // A full-screen overlay has to actually COVER what is behind it, and the
    // theme cannot be trusted to hand it a colour that does: the Dark Glass
    // theme's `background` is TRANSPARENT by design (that theme's page colour is
    // a gradient drawn behind the whole app), so a Surface painting the scheme's
    // background painted nothing and the feed — hero banner and all — showed
    // straight through this overlay. [pageBackground] is the scheme's own
    // background when it has one, and the solid page colour when it does not.
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
                    onValueChange = { typed = it },
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
            val shownResults = remember(results, kindKey, genre) {
                results.filter { homeKindKeep(it, kindKey) && homeGenreKeep(it, genre) }
                    .distinctBy { it.uniqueId }
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
            // The catalogues the scan below actually read, with the next page
            // each still owes: scrolled to the end, the grid asks for more
            // (see [loadMoreBrowse]) until every one answers empty. That is
            // what turns "Series shows 6, Anime shows nothing" into the whole
            // catalogue — page 1 alone is only ever its head.
            var browseCursors by remember { mutableStateOf<List<BrowseCursor>>(emptyList()) }
            var browseMoreLoading by remember { mutableStateOf(false) }
            var browseDone by remember { mutableStateOf(false) }
            val browseScope = rememberCoroutineScope()
            LaunchedEffect(kindKey, providerIds) {
                if (kindKey == HOME_KIND_ALL || providerIds.isEmpty()) {
                    browseItems = emptyList()
                    browseAnimeIds = emptySet()
                    browseCursors = emptyList()
                    browseDone = false
                    browseLoading = false
                    return@LaunchedEffect
                }
                browseLoading = true
                try {
                    val loaded = withContext(Dispatchers.IO) {
                        val mgr = app.providers
                        val targets = mgr.providers.value
                            .filter { it.config.enabled && it.config.id in providerIds }
                            .take(8)
                        val acc = ArrayList<MediaItem>()
                        val animeIds = HashSet<String>()
                        val cursors = ArrayList<BrowseCursor>()
                        for (p in targets) {
                            val cats = runCatching { p.homeCatalogs() }.getOrDefault(emptyList()).take(8)
                            for (ref in cats) {
                                val isAnimeCat = ref.id.contains("anime", true) ||
                                    ref.name.contains("anime", true) ||
                                    ref.rawType.equals("anime", true)
                                val page = runCatching {
                                    ContentRepository.loadCatalogPage(p, ref, 1)
                                }.getOrDefault(emptyList())
                                for (m in page) {
                                    val small = m.shrinkPoster()
                                    if (acc.none { it.uniqueId == small.uniqueId }) acc.add(small)
                                    if (isAnimeCat) animeIds.add(small.uniqueId)
                                    if (acc.size >= 400) break
                                }
                                // Page 1 landed (or answered empty): either way
                                // this catalogue's cursor starts at page 2 — an
                                // empty first page still gets its cursor, and the
                                // first load-more round retires it.
                                cursors.add(BrowseCursor(p.config.id, ref, isAnimeCat))
                                if (acc.size >= 400) break
                            }
                            if (acc.size >= 400) break
                        }
                        Triple(acc.toList(), animeIds.toSet(), cursors.toList())
                    }
                    browseItems = loaded.first
                    browseAnimeIds = loaded.second
                    browseCursors = loaded.third
                    browseDone = false
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Throwable) {
                } finally {
                    browseLoading = false
                }
            }
            /**
             * The next pages of the kind browser: a few catalogues per round,
             * appended under what is already on screen. A catalogue that
             * answers empty is retired; when every cursor is retired the list
             * is genuinely finished ([browseDone]) and the grid stops asking.
             * Guarded, so scroll events cannot stack rounds.
             */
            fun loadMoreBrowse() {
                if (browseMoreLoading || browseDone) return
                if (kindKey == HOME_KIND_ALL || providerIds.isEmpty()) return
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
                        for (c in pending.take(4)) {
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
                }
            }
            val blankShown = remember(feedItems, browseItems, browseAnimeIds, kindKey, genre) {
                if (kindKey == HOME_KIND_ALL && genre.isBlank()) emptyList()
                else (feedItems + browseItems).filter { item ->
                    val kindOk = when (kindKey) {
                        HOME_KIND_ALL -> true
                        HOME_KIND_ANIME -> item.looksAnime() || item.uniqueId in browseAnimeIds
                        else -> homeKindKeep(item, kindKey)
                    }
                    kindOk && homeGenreKeep(item, genre)
                }.distinctBy { it.uniqueId }
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
                    if (blankShown.isEmpty() && browseLoading) {
                        Box(
                            Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) { CircularProgressIndicator() }
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
                                    if (browseLoading || browseMoreLoading || !browseDone) "…" else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                            )
                            // Scrolling to the tail pages the catalogues further
                            // (see [loadMoreBrowse]): the count above keeps its
                            // "…" until every catalogue has answered empty, so
                            // "keep scroll and keep loading until all really
                            // ends" is what the list actually does.
                            val browseGridState = rememberLazyGridState()
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
                results.isEmpty() && searching -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
                shownResults.isEmpty() && !searching -> EmptyState(
                    title = tr("No matches"),
                    subtitle = I18n.t("%s has no \"%s\" of that kind — or its site is not answering.")
                        .replace("%s", label).replace("%s", applied),
                    actionLabel = null,
                    action = null,
                    detail = providerIds.firstOrNull()?.let { engineFailureReason(it) },
                )
                else -> {
                    // The engine can answer the same title twice (and one engine
                    // can answer once per mirror): a repeated Lazy key is a hard
                    // crash in Compose, so repeats are dropped before the grid is
                    // built — the same treatment the feed and the catalog page
                    // give their own lists.
                    val unique = rememberVisibleItems(shownResults)
                    LazyVerticalGrid(
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
                .background(MaterialTheme.colorScheme.surfaceVariant),
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


/**
 * One chip of [HomeGenreStrip]: what it says, and what it asks TMDB for.
 *
 * [genresText] is TMDB's `with_genres` OR-list built from BOTH namespaces (see
 * [com.hikari.app.data.Genres.tmdbGenreFilter]), so the page a chip opens
 * carries films AND series instead of making the viewer choose a shape first.
 * [keywordsText] is the fallback for a genre TMDB has no genre id for at all —
 * the anime vocabulary (Isekai, Harem, School Life, …), which is expressible
 * only as a keyword name; `TmdbSources` resolves it through TMDB's own keyword
 * search and sends `with_keywords`.
 */
private data class HomeGenre(
    val name: String,
    val genresText: String,
    val keywordsText: String,
)

/**
 * Home's genre strip — the "no need to search for anything" way in.
 *
 * The list IS [com.hikari.app.data.Genres.ALL], the same vocabulary the Search
 * tab's strip offers, so the two screens can never disagree again: Home used to
 * show only TMDB's FILM genre names, which left out both the television-only
 * names (Reality, Soap, Talk, Sci-Fi & Fantasy, …) and the whole anime
 * vocabulary (Isekai, Harem, School Life, …) that Search offered.
 *
 * Every chip leads somewhere real: a genre TMDB names opens the combined
 * film+series grid for it, and an anime tag with no TMDB genre opens the grid
 * for its TMDB keyword (resolved by name at load — see [TmdbSources]).
 */
@Composable
private fun HomeGenreStrip(onPick: (name: String, genresText: String, keywordsText: String) -> Unit) {
    val genres = remember {
        com.hikari.app.data.Genres.ALL.map { name ->
            val ids = com.hikari.app.data.Genres.tmdbGenreFilter(name)
            HomeGenre(
                name = name,
                genresText = ids,
                keywordsText = if (ids.isNotEmpty()) ""
                else com.hikari.app.data.Genres.keywordCandidates(name).joinToString("|"),
            )
        }
    }
    // The strip is the whole genre vocabulary — every TMDB film and television
    // genre plus all 99 anime tags — which is far more than fits on a screen, so
    // reaching "isekai" or "comedy" meant scrolling a strip that never ends
    // ("there are too many genre so user can just search like isekai or comedy,
    // and can select fastly instead of scrolling and finding it"). The field
    // beside the heading filters the chips AS THEY ARE TYPED, and it matches
    // against both the name a chip prints and the name the vocabulary files it
    // under, so a translated chip is still found by its English word.
    var query by remember { mutableStateOf("") }
    val shown = remember(genres, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) genres
        else genres.filter { g ->
            g.name.lowercase().contains(q) || I18n.t(g.name).lowercase().contains(q)
        }
    }
    // A new filter starts at the top of the strip: a match that happens to sort
    // late would otherwise be filtered in BEHIND the scrolled-away cards and the
    // strip would look like it had found nothing.
    val listState = rememberLazyListState()
    LaunchedEffect(query) { if (shown.isNotEmpty()) listState.scrollToItem(0) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 14.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                tr("Browse by genre"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            GlassSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = tr("Search genres"),
                height = 38.dp,
                modifier = Modifier.width(156.dp),
            )
        }
        if (shown.isEmpty()) {
            Text(
                tr("No genre matches") + " \u201c${query.trim()}\u201d",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
            )
        } else {
            LazyRow(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                state = listState,
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(shown, key = { it.name }) { g ->
                    FilterChipLine(
                        label = tr(g.name),
                        selected = false,
                        onClick = {
                            // The pick leaves the screen (it opens the grid), so
                            // the field is emptied on the way out and the strip
                            // is whole again on the way back.
                            query = ""
                            onPick(g.name, g.genresText, g.keywordsText)
                        },
                    )
                }
            }
        }
    }
}
