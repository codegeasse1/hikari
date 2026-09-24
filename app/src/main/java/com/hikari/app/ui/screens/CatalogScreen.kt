package com.hikari.app.ui.screens
import com.hikari.app.tv.TvUi
import com.hikari.app.i18n.tr

import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
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
import com.hikari.app.data.MediaType
import com.hikari.app.data.ProviderType
import com.hikari.app.i18n.I18n
import com.hikari.app.manga.MangaProvider
import com.hikari.app.providers.ContentProvider
import com.hikari.app.ui.Artwork
import com.hikari.app.ui.PosterLoader
import com.hikari.app.ui.PosterStyle
import com.hikari.app.ui.RatingBadge
import com.hikari.app.ui.rememberPosterScore
import com.hikari.app.ui.rememberPosterStyle
import com.hikari.app.ui.components.EmptyState
import com.hikari.app.ui.components.GlassSearchField
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

class CatalogViewModel(
    app: Application,
    private val providerId: String,
    catalogId: String,
    private val catalogName: String,
    private val type: MediaType,
    private val rawType: String,
) : AndroidViewModel(app) {
    private val manager = (app as HikariApp).providers

    /**
     * Which of the engine's OWN lists this page is showing.
     *
     * Every manga engine publishes exactly two — Popular and Latest (see
     * [MangaProvider.catalogs]) — and the two pills on the engine's row in the
     * Manga tab are how a reader reaches them. Once they are IN one of the two,
     * switching to the other must not mean going back a screen and pressing the
     * second pill: this page is the page that can do it (see the tabs in
     * [CatalogScreen]), and the pills stay where they are for the reader who is
     * still choosing which engine to read in.
     */
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

    /**
     * The title being searched for INSIDE this engine, "" while its own list is
     * being browsed.
     *
     * Only a manga catalog page offers this (see [CatalogScreen]): the engine is
     * the only thing that knows the site's own catalogue, walking it page by page
     * is fine for a "Popular" shelf but useless for "where is <title> on this
     * site" — and with a hundred extensions installed, the extension the reader
     * wants may not be the one Home searches by default.
     */
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** The query text that produced what is on screen right now. "Typing" and
     *  "searched" have to be told apart, or the grid says "nothing found" while
     *  the debounce is still running. */
    private val _appliedQuery = MutableStateFlow("")
    val appliedQuery: StateFlow<String> = _appliedQuery.asStateFlow()

    /**
     * WHY this page is empty, when it is empty because the engine failed rather
     * than because the site had nothing to give.
     *
     * Nothing used to be here, and the load's `catch` threw the exception away —
     * so every failure rendered as the same "The site may be blocking or down"
     * line and sent the reader off to pass a Cloudflare check that was never the
     * problem. The real reasons (an extension that fails to LINK, a source whose
     * `client` assertion refuses the app's OkHttp stack, an HTTP 403, a DNS
     * failure) are already recorded by the engine — [MangaProvider.lastOutcome] /
     * `AniyomiProvider.catalogErrors` — and are read back here and shown under
     * the empty state, verbatim. This is the difference between a report the user
     * can act on and a mystery.
     */
    private val _reason = MutableStateFlow<String?>(null)
    val reason: StateFlow<String?> = _reason.asStateFlow()

    private var page = 1
    private var loadJob: kotlinx.coroutines.Job? = null

    /**
     * The engine's own record of the last call it made to this provider, when
     * that call FAILED. Success lines ("✓ 40 title(s)") are not errors and are
     * filtered out — only the reason an empty result may be shown to the reader.
     */
    private fun providerReason(): String? {
        val p = manager.byId(providerId) ?: return null
        val raw = when (p.config.type) {
            ProviderType.MANGA -> com.hikari.app.manga.MangaProvider.lastOutcome[p.config.id]
            ProviderType.ANIYOMI -> com.hikari.app.aniyomi.AniyomiProvider.catalogErrors[p.config.id]
            ProviderType.SKYSTREAM -> com.hikari.app.skystream.SkyStreamProvider.catalogErrors[p.config.id]
            ProviderType.NUVIO -> com.hikari.app.nuvio.NuvioScraper.catalogErrors[p.config.id]
            ProviderType.STREMIO -> com.hikari.app.providers.StremioAddon.catalogErrors[p.config.id]
            ProviderType.CS3 -> com.hikari.app.cs3.Cs3MainApiProvider.catalogErrors[p.config.id]
            else -> null
        }
        return raw?.takeIf { it.isNotBlank() && !it.startsWith("✓") && !it.startsWith("✔") }
    }

    init {
        loadNext()
    }

    /**
     * Starts (or clears) an in-engine search. The list is emptied immediately so
     * the grid cannot show the previous list's items under the new query — a tap
     * during the debounce would otherwise open the wrong title.
     */
    fun setQuery(q: String) {
        if (q == _query.value) return
        _query.value = q
        loadJob?.cancel()
        page = 1
        _items.value = emptyList()
        _done.value = false
        _reason.value = null
        _appliedQuery.value = q.trim()
        // Always re-ask: a blank query goes back to the catalog's own list, and
        // loadNext pages whichever of the two is in force (see it, and note that
        // it flips the spinner on synchronously — the grid must never be shown
        // empty, which is what "no matches" flashing before every search was).
        // The flag is cleared first: the cancelled job above died with it set, and
        // loadNext refuses to start while it is up.
        _loading.value = false
        loadNext()
    }

    /** Loads the next page. Returns true when more pages may exist. */
    fun loadNext() {
        if (_loading.value || _done.value) return
        loadJob?.cancel()
        // Set BEFORE the coroutine is launched, not inside it: the grid reads
        // this to decide between the spinner and its empty state, and the frame
        // between "the list was emptied" and "the coroutine ran" was long enough
        // to show the empty state for a beat.
        _loading.value = true
        // Keeps this page load running while the user is in another app (see
        // [com.hikari.app.work.BackgroundWork]) — otherwise the OS freezes the
        // process and the grid stops filling in until the app is reopened.
        val work = com.hikari.app.work.BackgroundWork.begin("Loading $catalogName") {
            loadJob?.cancel()
        }
        loadJob = viewModelScope.launch {
            val provider: ContentProvider? = manager.byId(providerId)
            val ref = CatalogRef(providerId, type, _catalog.value, catalogName, rawType)
            val fresh = try {
                // A search is paged exactly like the catalog is: the same
                // infinite-scroll effect asks for page 2, and a source with 400
                // matches streams in the way the grid expects.
                val raw = if (rawType == "manga" && _query.value.isNotBlank()) {
                    provider?.search(_query.value.trim(), page) ?: emptyList()
                } else {
                    provider?.getCatalog(ref, page) ?: emptyList()
                }
                withContext(Dispatchers.IO) { raw.map { it.tokenizePoster() } }
            } catch (t: Throwable) {
                // NOT swallowed any more. Two reasons: the message is what the
                // empty state now shows (so a failure is diagnosable from the
                // screen — see [_reason]), and a CANCELLATION must not be
                // mistaken for an answer. The previous version turned every
                // cancel into an empty page, and `loadNext` cancels the job it
                // is replacing — so a cancelled load came back to mark the list
                // "done" and clear the spinner while the load that replaced it
                // was still running (pagination stopped dead after a search, and
                // the grid looked like the engine had returned nothing).
                if (t is kotlinx.coroutines.CancellationException) throw t
                _reason.value = describeFailure(t)
                emptyList()
            }
            val translated = if (providerId in com.hikari.app.data.Translator.enabledIds()) {
                com.hikari.app.data.Translator.translateAll(fresh.map { it.title })
                    .mapIndexed { i, t -> if (t != fresh[i].title) fresh[i].copy(title = t) else fresh[i] }
            } else {
                fresh
            }
            if (translated.isEmpty()) {
                // The engine's own record of the call wins over the generic
                // message the caller caught: it is the one that names the real
                // cause (a class-load failure, an assertion about our OkHttp
                // stack, a 403). Cleared again the moment anything arrives.
                _reason.value = providerReason() ?: _reason.value
                _done.value = true
            } else {
                _reason.value = null
                val seen = _items.value.map { it.uniqueId }.toMutableSet()
                val merged = _items.value + translated.filter { seen.add(it.uniqueId) }
                _items.value = merged
                page++
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
        _items.value = emptyList()
        _done.value = false
        _reason.value = null
        loadNext()
    }
}

/** 51CG/MRDS/Porna91-style posters arrive as huge base64 data: URIs. A big
 * catalog holding thousands of those raw strings blows the heap the moment the
 * user scrolls — but simply dropping them left every grid cell blank. Collapse
 * each oversized poster into a tiny disk-cache token instead (decoded +
 * persisted once, off the main thread): the grid still shows the image, and
 * memory stays bounded. */
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
    // The engine's own search. Only a MANGA catalog gets the box: its items are
    // manga and this page is reached from the Manga tab, where nothing else can
    // ask one specific engine for a title. A video extension already has Home's
    // "search this extension" magnifier, and Search's own scope row.
    val searchable = rawType == "manga"
    val appliedQuery by vm.appliedQuery.collectAsState()
    val catalogReason by vm.reason.collectAsState()
    val selectedCatalog by vm.catalog.collectAsState()
    // The engine's two own lists, as tabs (see [CatalogViewModel.catalog]).
    // Offered only where they are the whole story: the page is a MANGA catalog
    // (that is what the two labels mean here) and it is showing one of the two.
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
        if (!searchable) return@LaunchedEffect
        // Long enough that a typed word is one request, short enough that the
        // grid feels like it follows the keyboard.
        delay(400)
        vm.setQuery(typedQuery)
    }
    val hikari = app as HikariApp
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // A verification WebView earns a cf_clearance cookie for the site. Coming
    // back from it the grid MUST re-ask the source: the page that answered
    // "nothing here" a moment ago answers properly now, and leaving the old
    // empty grid up would make the whole exercise look like it failed.
    val verifyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        vm.refresh()
    }
    // One way in for both the header button and the empty state's, so they can
    // never drift apart (and so the reasoning below is written once).
    val openVerify: () -> Unit = {
        scope.launch {
            // Deriving the site loads the extension (its source's baseUrl), so it
            // cannot run on the UI thread — and an extension that declares no
            // site has nothing to open, which is a sentence, not a crash.
            val site = withContext(Dispatchers.IO) {
                hikari.providers.byId(providerId)?.let { webUrlFor(it) }
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
                            // Closes itself as soon as the clearance is in the
                            // cookie jar, so the user does not have to know when
                            // they are "done".
                            putExtra("autoCloseWhenCloudflarePassed", true)
                            if (host != null) putExtra("verifyHost", host)
                        }
                )
            }
        }
    }

    val gridState = rememberLazyGridState()
    // Infinite scroll: fetch the next page when the user scrolls close to the
    // bottom. (A LaunchedEffect keyed on gridState alone never re-fires on
    // scroll — gridState is a stable object — so this watches the scroll
    // position via snapshotFlow instead.)
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
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { nav.popBackStack() }) {
                Icon(Icons.Filled.ArrowBack, contentDescription = tr("Back"))
            }
            Column(Modifier.weight(1f)) {
            Text(
                tr(shownName),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (providerName.isNotBlank()) {
                    Text(
                        providerName.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (mangaPair) {
                    Row(
                        Modifier.padding(top = 6.dp),
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
            // The Cloudflare-verification WebView. A manga site behind a bot wall
            // answers every request — including the extension's own — with a
            // challenge until a browser has passed it, and no extension can open
            // a browser for itself. Without this button the ONLY way through was
            // Home's globe, which does not exist for a manga engine's own page.
            if (searchable) {
                IconButton(onClick = openVerify) {
                    Icon(
                        Icons.Filled.Public,
                        contentDescription = tr("Open the site to pass its Cloudflare check"),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        if (searchable) {
            GlassSearchField(
                value = typedQuery,
                onValueChange = { typedQuery = it },
                placeholder = I18n.t("Search %s…").replace("%s", tr(shownName)),
                height = 46.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
            )
        }
        if (items.isEmpty() && loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
                // The spinner, on its own, cannot say WHY nothing is arriving —
                // and ten seconds in, the usual answer is a Cloudflare check the
                // site wants a browser to pass (see [VerificationNudge]). The
                // chip floats under the spinner, says so once, and is itself the
                // tap that opens the verification view.
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
                // A search that matched nothing and a site that answered nothing
                // are two different problems, and the second one has a fix the
                // user can apply (the verification WebView) — so the empty state
                // says which one it is and offers that button.
                EmptyState(
                    title = if (appliedQuery.isNotBlank()) tr("No matches")
                    else tr("Nothing here right now"),
                    subtitle = if (appliedQuery.isNotBlank())
                        I18n.t("This engine has no \"%s\" — it may also be blocking Hikari.").replace("%s", appliedQuery)
                    else tr("The site may be blocking or down.") +
                        if (searchable) " " + tr("If the site shows a Cloudflare check, open it and pass it once.") else "",
                    actionLabel = if (searchable) tr("Verify site") else null,
                    action = if (searchable) openVerify else null,
                    // What the engine actually said, when it said anything —
                    // see CatalogViewModel.reason. Without it a source that
                    // fails to LINK (an OkHttp class it needs missing from the
                    // app, a source whose own assertions refuse our client) read
                    // exactly like a site that was merely down.
                    detail = catalogReason,
                )
            }
        } else {
            // Extension catalogs repeat themselves (a scraped page can list the
            // same title twice): keying on the identity WITHOUT dropping repeats
            // crashes the screen, because Compose throws on a duplicated key.
            // Deduped and styled once for the whole grid rather than once per
            // cell — a cell doing its own read opened one DataStore collection
            // per poster on screen (see MediaRow).
            val uniqueItems = rememberVisibleItems(items)
            val style = rememberPosterStyle()
            LazyVerticalGrid(
                // Same small-tile, clearly-gapped look as the search results
                // grid: smaller posters than before, each in its own cell with
                // a real gap, so the "Show All" wall never reads as one
                // continuous sheet of artwork. On a television the same grid is
                // laid out around a living-room cell size instead (see TvUi) —
                // a phone's 84dp minimum on a 1920dp screen would be twenty-two
                // columns of thumbnails.
                columns = GridCells.Adaptive(minSize = TvUi.gridMinFor(84)),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(uniqueItems, key = { it.uniqueId }) { item ->
                    CatalogCard(item, style) {
                        // A manga engine's "catalog" is its Popular/Latest list
                        // and its items are manga, not video: those open the
                        // manga detail page (the provider tags its catalogs with
                        // rawType "manga" — see MangaProvider.catalogs).
                        Routes.safeNavigate(
                            nav,
                            if (rawType == "manga") {
                                Routes.mangaDetail(
                                    item.providerId, item.id, item.title, item.posterUrl
                                )
                            } else {
                                Routes.detail(
                                    item.providerId, item.type, item.id,
                                    item.title, item.posterUrl, item.rawType
                                )
                            }
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
                        }
                    }
                }
            }
        }
    }
}

/**
 * One of an engine's two own lists, as a tab (see [CatalogViewModel.catalog]).
 *
 * A tab rather than a pair of buttons: the two lists are the same page with a
 * different question asked of the site, and a tab is what that relationship
 * looks like. Tapping the one already selected does nothing (the view model
 * ignores it), so a double tap cannot reload the list under the reader.
 */
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
private fun CatalogCard(item: MediaItem, style: PosterStyle, onClick: () -> Unit) {
    // The score badge, when Settings → App Layout has it on: warm the ratings
    // cache for this title and print whatever is known (see
    // rememberPosterScore). Null when the switch is off, so an old device that
    // never wanted badges pays nothing.
    val badge = rememberPosterScore(item, style)
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            // Behind the artwork: a poster that 403s/404s (or an item with no
            // poster at all) keeps a deliberate-looking slot instead of a
            // blank dark rectangle.
            Icon(
                Icons.Filled.Movie,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.40f),
                modifier = Modifier.size(26.dp),
            )
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
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp)
        )
    }
}
