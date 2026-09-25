package com.hikari.app.ui.screens
import com.hikari.app.i18n.tr
import com.hikari.app.i18n.I18n

import android.app.Application
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
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
import com.hikari.app.data.CollectionFolder
import com.hikari.app.data.ContentRepository
import com.hikari.app.data.CoverKinds
import androidx.compose.ui.text.style.TextOverflow
import com.hikari.app.data.MediaItem
import com.hikari.app.data.ProviderType
import com.hikari.app.ui.PosterLoader
import com.hikari.app.ui.ProviderPacks
import com.hikari.app.ui.components.ContinueWatchingRow
import com.hikari.app.ui.components.EmptyState
import com.hikari.app.ui.components.GlassDialog
import com.hikari.app.ui.components.GlassSearchField
import com.hikari.app.ui.components.HeroBanner
import com.hikari.app.ui.components.HeroConfig
import com.hikari.app.ui.components.HeroStyles
import com.hikari.app.ui.components.MediaRow
import com.hikari.app.ui.components.ShimmerRow
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
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
        val extensionIds = picks.filterNot { isCollectionKey(it) }.toSet()
        return when {
            // The ordinary case, and the one that must never move: NO pick at
            // all is "All providers", which is the feed that stacked every
            // installed extension's home page. It has to ask for that feed
            // (`homeRowsStreaming()` — no id filter) rather than for
            // `homeRowsStreamingFor(emptySet())`, which reads as "rows from
            // NONE of the providers" and painted an empty Home for anyone on
            // All — the reported "in home clicking all provider … not all show
            // like it earlier used to show".
            picks.isEmpty() -> repo.homeRowsStreaming()
            extensionIds.isNotEmpty() -> repo.homeRowsStreamingFor(extensionIds)
            // A pick made ONLY of collections has no extension feed at all:
            // falling through to the combined feed here would quietly stack
            // every installed extension's home page under the user's own
            // catalogs.
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
        val soloPick = kept.singleOrNull()?.takeIf { !isCollectionKey(it) }
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
    }
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
    // A COLLECTION counts as a scope too: browsing "abc" and tapping the
    // magnifier must be able to search inside abc and not only everywhere.
    val openSearch: () -> Unit = {
        if (headerSelection != null || selectedCollection != null) showSearchDialog = true
        else openGlobalSearch()
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
                    if (featured.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
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
                    } else if (selected != null) {
                        val reason =
                            com.hikari.app.cs3.Cs3MainApiProvider.catalogErrors[selected]
                                ?: com.hikari.app.providers.StremioAddon.catalogErrors[selected]
                                ?: com.hikari.app.nuvio.NuvioScraper.catalogErrors[selected]
                                ?: com.hikari.app.skystream.SkyStreamProvider.catalogErrors[selected]
                                // Aniyomi was missing from this chain, so an
                                // Aniyomi catalog that failed fell through to the
                                // generic "check the WebView / it may be down"
                                // line — which sent users looking for a
                                // Cloudflare verification that was never the
                                // problem. An Aniyomi extension that failed to
                                // load has a real, specific reason ("none of its
                                // sources could be loaded", a class-load failure,
                                // an unsupported extension library) and it is
                                // reported here now.
                                ?: com.hikari.app.aniyomi.AniyomiProvider.catalogErrors[selected]
                                // Manga engines were missing from this chain for
                                // the same reason Aniyomi was: a manga source
                                // that fails to LINK or load (an OkHttp class it
                                // needs absent from the app, a source whose own
                                // assertions refuse our client) fell through to
                                // the generic "the site may be down" line. Its
                                // own record is `MangaProvider.lastOutcome`, and
                                // the success lines in that map ("✓ 40 titles")
                                // are filtered out so only a real failure shows.
                                ?: com.hikari.app.manga.MangaProvider.lastOutcome[selected]
                                    ?.takeIf { !it.startsWith("✓") && !it.startsWith("✔") }
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
                        val streamOnly =
                            com.hikari.app.providers.StremioAddon.streamOnlyAddons[selected] == true
                        if (streamOnly) {
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
    val shownCollections = remember(collections, query) {
        if (query.isBlank()) collections
        else collections.filter { it.name.contains(query, ignoreCase = true) }
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
                // and the only place a long list cannot hide it.
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
            Text(
                if (multi) {
                    if (working.isEmpty()) tr("Tap the sources to show on Home, then Done.")
                    else I18n.t("%s sources picked — tap more, then Done.")
                        .replace("%s", working.size.toString())
                } else {
                    tr(
                        "Only the selected extension's catalog is shown on Home. " +
                            "Hold a source for a second to pick several, or tap its " +
                            "pin to keep it at the top of this list."
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 14.dp)
            )
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
                        PickerSectionLabel(tr("Collections"))
                    }
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
                }
                item {
                    PickerSectionLabel(tr("Providers"))
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
                            !pack.isPack -> null
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
                                working = if (allTicked) working - ids.toSet()
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
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
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
                    else Modifier.pointerInput(label, multi) { holdOrTap(onLongClick, onClick) }
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
internal fun webUrlFor(p: ContentProvider): String? = when (p.config.type) {
    ProviderType.STREMIO, ProviderType.UNIVERSAL ->
        p.config.url.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    ProviderType.HIKARI ->
        com.hikari.app.hiki.HikariRuntime.providerFor(p.config)?.mainUrl
    // A SkyStream extension's `url` is the LOCAL plugin.js path, so the site it
    // reads lives in its plugin.json (`domains[0]`, else `baseUrl`). Without
    // this the globe button had no target at all for these extensions.
    ProviderType.SKYSTREAM ->
        com.hikari.app.skystream.SkyStreamPluginManager.siteUrlOf(p.config)
    // Same for Aniyomi: `url` is the local .ext path, so the site comes from the
    // extension's own source (`baseUrl`/`siteUrl`, else its source class name).
    ProviderType.ANIYOMI ->
        com.hikari.app.aniyomi.AniyomiExtensionManager.siteUrlOf(p.config)
    // And a manga extension's `url` is its local .ext path too.
    ProviderType.MANGA ->
        com.hikari.app.manga.MangaExtensionManager.siteUrlOf(p.config)
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
    val rows = LinkedHashMap<String, List<CatalogRow>>()
}
