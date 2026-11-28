package com.hikari.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.hikari.desktop.data.AddonCatalog
import com.hikari.desktop.data.AddonDetail
import com.hikari.desktop.data.AddonEpisode
import com.hikari.desktop.data.AddonManifest
import com.hikari.desktop.data.AddonSourceStore
import com.hikari.desktop.data.AddonStream
import com.hikari.desktop.data.AddonTitle
import com.hikari.desktop.data.HomeProviderSelectionStore
import com.hikari.desktop.data.LibraryCategory
import com.hikari.desktop.data.LibraryCategoryStore
import com.hikari.desktop.data.SavedTitleIdentity
import com.hikari.desktop.data.SavedTitlesStore
import com.hikari.desktop.data.SearchHistoryStore
import com.hikari.desktop.data.StreamKind
import com.hikari.desktop.data.savedTitleIdentity
import com.hikari.desktop.data.WatchHistoryEntry
import com.hikari.desktop.data.WatchHistoryStore
import com.hikari.desktop.engine.AnymexEngine
import com.hikari.desktop.engine.EngineKind
import com.hikari.desktop.engine.EngineMedia
import com.hikari.desktop.engine.EngineMediaKind
import com.hikari.desktop.engine.EngineProvider
import com.hikari.desktop.engine.EngineStream
import com.hikari.desktop.engine.EngineSubtitle
import com.hikari.desktop.engine.EngineHttp
import com.hikari.desktop.engine.NuvioEngine
import com.hikari.desktop.engine.ProviderRegistry
import com.hikari.desktop.engine.StremioEngineAdapter
import com.hikari.desktop.engine.engineAddonUrl
import com.hikari.desktop.engine.isInternalPlayerUrl
import com.hikari.desktop.engine.parseEngineRef
import com.hikari.desktop.engine.toAddonEpisode
import com.hikari.desktop.engine.toAddonStream
import com.hikari.desktop.engine.toAddonTitle
import com.hikari.desktop.net.AddonClientException
import com.hikari.desktop.net.StremioClient
import com.hikari.desktop.playback.JavaFxPlayerEngine
import com.hikari.desktop.playback.StreamLauncher
import com.hikari.desktop.playback.defaultVideoIdForPlayback
import com.hikari.desktop.playback.launchAndRecordHistory
import com.hikari.desktop.ui.DesktopPage
import com.hikari.desktop.ui.DesktopSidebar
import com.hikari.desktop.ui.DetailScreen
import com.hikari.desktop.ui.CatalogBrowseScreen
import com.hikari.desktop.ui.CatalogDirectoryScreen
import com.hikari.desktop.ui.EngineRepo
import com.hikari.desktop.ui.EngineRepoEntry
import com.hikari.desktop.ui.ExtensionsScreen
import com.hikari.desktop.ui.JavaFxVideoSurface
import com.hikari.desktop.ui.PlayerScreen
import com.hikari.desktop.ui.catalogBrowseReturnDestination
import com.hikari.desktop.ui.guessEngineKind
import com.hikari.desktop.ui.HomeScreen
import com.hikari.desktop.ui.LibraryScreen
import com.hikari.desktop.ui.WatchHistoryScreen
import com.hikari.desktop.ui.SearchScreen
import com.hikari.desktop.ui.SearchSourceOption
import com.hikari.desktop.ui.selectSearchSourceUrls
import com.hikari.desktop.ui.SourceManagerScreen
import com.hikari.desktop.ui.StreamPickerDialog
import com.hikari.desktop.ui.HikariDesktopTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Hikari for Windows",
        state = rememberWindowState(size = DpSize(1440.dp, 900.dp)),
    ) {
        HikariDesktopTheme {
            HikariDesktopApp()
        }
    }
}

data class SourceState(
    val url: String,
    val manifest: AddonManifest? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

data class EnginePlayerRequest(
    val title: String,
    val streams: List<EngineStream>,
    val initial: EngineStream,
)

data class ShelfState(
    val addonUrl: String,
    val addonName: String,
    val catalog: AddonCatalog,
    val titles: List<AddonTitle> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

@Composable
private fun HikariDesktopApp() {
    val client = remember { StremioClient() }
    val catalogPageSource = remember { StremioCatalogPageSource(client) }
    val sourceStore = remember { AddonSourceStore() }
    val homeProviderSelectionStore = remember { HomeProviderSelectionStore() }
    val savedTitlesStore = remember { SavedTitlesStore() }
    val libraryCategoryStore = remember { LibraryCategoryStore() }
    val searchHistoryStore = remember { SearchHistoryStore() }
    val watchHistoryStore = remember { WatchHistoryStore() }
    val scope = rememberCoroutineScope()

    var page by remember { mutableStateOf(DesktopPage.HOME) }
    var catalogReturnPage by remember { mutableStateOf(DesktopPage.HOME) }
    var previousPage by remember { mutableStateOf(DesktopPage.HOME) }
    var sourceStates by remember { mutableStateOf(emptyList<SourceState>()) }
    var selectedProviderUrl by remember {
        mutableStateOf(runCatching { homeProviderSelectionStore.selectedUrl() }.getOrNull())
    }
    var selectedMediaFilter by remember { mutableStateOf(HomeMediaFilter.ALL) }
    var shelves by remember { mutableStateOf(emptyList<ShelfState>()) }
    var catalogBrowse by remember { mutableStateOf(CatalogBrowseState()) }
    var homeCatalogsLoading by remember { mutableStateOf(false) }
    var sourceUrlInput by remember { mutableStateOf("") }
    var addSourceLoading by remember { mutableStateOf(false) }
    var addSourceError by remember { mutableStateOf<String?>(null) }
    var selectedTitle by remember { mutableStateOf<AddonTitle?>(null) }
    var titleDetail by remember { mutableStateOf<AddonDetail?>(null) }
    var detailLoading by remember { mutableStateOf(false) }
    var detailError by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf(emptyList<AddonTitle>()) }
    var searchLoading by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var searchPartialFailure by remember { mutableStateOf(false) }
    var selectedSearchSourceUrls by remember { mutableStateOf(emptySet<String>()) }
    var searchHistory by remember {
        mutableStateOf(runCatching { searchHistoryStore.all() }.getOrDefault(emptyList()))
    }
    var searchHistoryError by remember { mutableStateOf<String?>(null) }
    var watchHistory by remember { mutableStateOf(emptyList<WatchHistoryEntry>()) }
    var watchHistoryLoading by remember { mutableStateOf(true) }
    var watchHistoryError by remember { mutableStateOf<String?>(null) }
    var savedTitles by remember { mutableStateOf(emptyList<AddonTitle>()) }
    var savedTitlesLoading by remember { mutableStateOf(true) }
    var savedTitlesError by remember { mutableStateOf<String?>(null) }
    var libraryCategories by remember { mutableStateOf(emptyList<LibraryCategory>()) }
    var libraryCategoryAssignments by remember { mutableStateOf(emptyMap<SavedTitleIdentity, Set<String>>()) }
    var libraryCategoryError by remember { mutableStateOf<String?>(null) }
    var streamDialogOpen by remember { mutableStateOf(false) }
    var streamDialogTitle by remember { mutableStateOf("") }
    var streamDialogTitleEntry by remember { mutableStateOf<AddonTitle?>(null) }
    var streamDialogEpisodeEntry by remember { mutableStateOf<AddonEpisode?>(null) }
    var streams by remember { mutableStateOf(emptyList<AddonStream>()) }
    var streamLoading by remember { mutableStateOf(false) }
    var streamError by remember { mutableStateOf<String?>(null) }
    var playbackMessage by remember { mutableStateOf<String?>(null) }
    val engineRegistry = remember { ProviderRegistry() }
    val playerEngine = remember { JavaFxPlayerEngine(scope) }
    var engineProviders by remember { mutableStateOf(emptyList<EngineProvider>()) }
    var engineShelves by remember { mutableStateOf(emptyList<ShelfState>()) }
    var engineShelvesLoading by remember { mutableStateOf(false) }
    var engineStreamOptions by remember { mutableStateOf(emptyList<EngineStream>()) }
    var engineTmdbKeyInput by remember { mutableStateOf(engineRegistry.tmdbKey()) }
    var engineAddUrl by remember { mutableStateOf("") }
    var engineAddKind by remember { mutableStateOf(EngineKind.STREMIO) }
    var engineAdding by remember { mutableStateOf(false) }
    var engineAddError by remember { mutableStateOf<String?>(null) }
    var engineRepoKind by remember { mutableStateOf(EngineKind.NUVIO) }
    var engineRepoName by remember { mutableStateOf("") }
    var engineRepoEntries by remember { mutableStateOf(emptyList<EngineRepoEntry>()) }
    var engineReposLoading by remember { mutableStateOf(false) }
    var engineRepoError by remember { mutableStateOf<String?>(null) }
    var openedRepoUrl by remember { mutableStateOf<String?>(null) }
    var multiFeedEnabled by remember {
        mutableStateOf(runCatching { homeProviderSelectionStore.multiFeedEnabled() }.getOrDefault(false))
    }
    var activeFeedUrls by remember {
        mutableStateOf(runCatching { homeProviderSelectionStore.activeFeedUrls() }.getOrDefault(emptyList()))
    }
    var playerRequest by remember { mutableStateOf<EnginePlayerRequest?>(null) }
    var playerSubtitles by remember { mutableStateOf(emptyList<EngineSubtitle>()) }
    val catalogInventory = homeCatalogInventory(sourceStates, selectedProviderUrl, selectedMediaFilter)

    suspend fun loadSources(urls: List<String>) {
        sourceStates = urls.map { SourceState(it, loading = true) }
        val results = coroutineScope {
            urls.map { url -> async { url to runCatching { client.loadManifest(url) } } }.awaitAll()
        }
        sourceStates = results.map { (url, result) ->
            result.fold(
                onSuccess = { SourceState(url = it.addonUrl, manifest = it) },
                onFailure = { SourceState(url = url, error = it.userMessage()) },
            )
        }
        selectedProviderUrl = runCatching {
            homeProviderSelectionStore.reconcileInstalledSources(sourceStates.map { it.url })
        }.getOrNull()
        selectedSearchSourceUrls = selectedSearchSourceUrls.intersect(sourceStates.map { it.url }.toSet())
    }

    fun reloadSavedTitles() {
        savedTitlesLoading = true
        savedTitlesError = null
        try {
            savedTitles = savedTitlesStore.all()
        } catch (error: Exception) {
            savedTitlesError = error.userMessage()
        } finally {
            savedTitlesLoading = false
        }
    }

    fun reloadWatchHistory() {
        watchHistoryLoading = true
        watchHistoryError = null
        try {
            watchHistory = watchHistoryStore.all()
        } catch (error: Exception) {
            watchHistoryError = error.userMessage()
        } finally {
            watchHistoryLoading = false
        }
    }

    fun removeWatchHistoryEntry(entry: WatchHistoryEntry) {
        try {
            watchHistory = watchHistoryStore.remove(entry)
            watchHistoryError = null
        } catch (error: Exception) {
            watchHistoryError = error.userMessage()
        }
    }

    fun clearWatchHistory() {
        try {
            watchHistory = watchHistoryStore.clear()
            watchHistoryError = null
        } catch (error: Exception) {
            watchHistoryError = error.userMessage()
        }
    }

    fun reloadLibraryCategoryState() {
        try {
            libraryCategories = libraryCategoryStore.categories()
            libraryCategoryAssignments = libraryCategoryStore.assignments()
            libraryCategoryError = null
        } catch (error: Exception) {
            libraryCategoryError = error.userMessage()
        }
    }

    fun createLibraryCategory(name: String): LibraryCategory {
        try {
            val created = libraryCategoryStore.create(name)
            reloadLibraryCategoryState()
            return created
        } catch (error: Exception) {
            libraryCategoryError = error.userMessage()
            throw error
        }
    }

    fun updateLibraryCategory(operation: () -> Unit) {
        try {
            operation()
            reloadLibraryCategoryState()
        } catch (error: Exception) {
            libraryCategoryError = error.userMessage()
        }
    }

    fun removeLibraryCategoryAssignments(title: AddonTitle) {
        try {
            libraryCategoryStore.removeTitle(title)
            libraryCategoryAssignments = libraryCategoryStore.assignments()
            libraryCategoryError = null
        } catch (error: Exception) {
            libraryCategoryError = error.userMessage()
        }
    }

    fun updateSavedTitles(operation: () -> List<AddonTitle>): Boolean {
        try {
            savedTitles = operation()
            savedTitlesError = null
            return true
        } catch (error: Exception) {
            savedTitlesError = error.userMessage()
            return false
        }
    }

    fun toggleSavedTitle(title: AddonTitle) {
        val isSaved = savedTitles.any { it.savedTitleIdentity() == title.savedTitleIdentity() }
        if (isSaved) {
            if (updateSavedTitles { savedTitlesStore.remove(title) }) {
                removeLibraryCategoryAssignments(title)
            }
        } else {
            updateSavedTitles { savedTitlesStore.save(title) }
        }
    }

    fun removeSavedTitle(title: AddonTitle) {
        if (updateSavedTitles { savedTitlesStore.remove(title) }) {
            removeLibraryCategoryAssignments(title)
        }
    }

    // ---- Extension engines (Stremio + JS + IPTV) ----

    fun reloadEngineProviders() {
        engineProviders = engineRegistry.all()
        engineTmdbKeyInput = engineRegistry.tmdbKey()
    }

    fun refreshEngineShelves() {
        scope.launch(Dispatchers.IO) {
            val providers = engineRegistry.enabled()
            if (providers.isEmpty()) {
                engineShelves = emptyList()
                engineShelvesLoading = false
                return@launch
            }
            engineShelvesLoading = true
            try {
                val fresh = engineRegistry.homeShelves()
                engineShelves = fresh.map { shelf ->
                    ShelfState(
                        addonUrl = engineAddonUrl(shelf.provider),
                        addonName = shelf.provider.name,
                        catalog = AddonCatalog(type = "*", id = shelf.catalog.id, name = shelf.catalog.title),
                        titles = shelf.items.map { it.toAddonTitle(shelf.provider) },
                    )
                }
            } finally {
                engineShelvesLoading = false
            }
        }
    }

    fun installEngineProvider(kind: EngineKind, raw: String, nameOverride: String? = null) {
        val url = raw.trim()
        if (url.isEmpty()) {
            engineAddError = "Enter an extension URL or file path first."
            return
        }
        // Stremio manifests are catalog sources, not engine extensions:
        // install them as Home sources so catalogues load immediately.
        if (kind == EngineKind.STREMIO && (url.endsWith("manifest.json") || url.contains("/manifest.json"))) {
            engineAdding = true
            engineAddError = null
            scope.launch {
                try {
                    val manifest = client.loadManifest(url)
                    if (!sourceStore.add(manifest.addonUrl)) {
                        withContext(Dispatchers.Main) { engineAddError = "This source is already in the list." }
                    } else {
                        val fresh = SourceState(manifest.addonUrl, manifest = manifest)
                        withContext(Dispatchers.Main) {
                            sourceStates = sourceStates.filterNot { it.url == manifest.addonUrl } + fresh
                            engineAddUrl = ""
                        }
                    }
                } catch (error: Exception) {
                    withContext(Dispatchers.Main) { engineAddError = (error.message ?: "could not add this source").take(300) }
                } finally {
                    withContext(Dispatchers.Main) { engineAdding = false }
                }
            }
            return
        }
        engineAdding = true
        engineAddError = null
        scope.launch(Dispatchers.IO) {
            try {
                val provider = buildEngineProvider(engineRegistry, kind, url, nameOverride)
                engineRegistry.upsert(provider)
                withContext(Dispatchers.Main) {
                    reloadEngineProviders()
                    engineAddUrl = ""
                    refreshEngineShelves()
                }
            } catch (error: Exception) {
                withContext(Dispatchers.Main) {
                    engineAddError = (error.message ?: "could not add this extension").take(300)
                }
            } finally {
                withContext(Dispatchers.Main) { engineAdding = false }
            }
        }
    }

    fun installEngineRepoFile(kind: EngineKind, entry: EngineRepoEntry) {
        installEngineProvider(kind, entry.url, entry.name)
    }

    fun validateEngineProvider(id: String) {
        scope.launch(Dispatchers.IO) {
            val provider = engineRegistry.all().firstOrNull { it.id == id } ?: return@launch
            val engine = engineRegistry.engineFor(provider.kind) ?: return@launch
            val problem = runCatching { engine.validate(provider) }.getOrNull()
            withContext(Dispatchers.Main) {
                engineRegistry.setNote(id, problem)
                reloadEngineProviders()
            }
        }
    }

    fun loadEngineRepo(kind: EngineKind, repoUrl: String, repoName: String) {
        engineReposLoading = true
        engineRepoError = null
        engineRepoName = repoName
        engineRepoEntries = emptyList()
        scope.launch(Dispatchers.IO) {
            try {
                val entries = fetchEngineRepoEntries(repoUrl)
                withContext(Dispatchers.Main) {
                    engineRepoEntries = entries
                    if (entries.isEmpty()) engineRepoError = "No installable files in this repository."
                }
            } catch (error: Exception) {
                withContext(Dispatchers.Main) {
                    engineRepoError = (error.message ?: "could not load this repository").take(300)
                }
            } finally {
                withContext(Dispatchers.Main) { engineReposLoading = false }
            }
        }
    }

    LaunchedEffect(Unit) {
        reloadSavedTitles()
        reloadWatchHistory()
        reloadLibraryCategoryState()
        val stored = sourceStore.all()
        // First run: ship with Cinemeta so Home shows catalogues immediately.
        if (stored.isEmpty()) {
            val cinemeta = "https://v3-cinemeta.strem.io/manifest.json"
            runCatching { sourceStore.add(cinemeta) }
            loadSources(listOf(cinemeta))
        } else {
            loadSources(stored)
        }
        reloadEngineProviders()
        refreshEngineShelves()
    }

    LaunchedEffect(sourceStates, selectedProviderUrl, selectedMediaFilter) {
        val visibleSources = if (selectedProviderUrl == null) {
            sourceStates
        } else {
            sourceStates.filter { it.url == selectedProviderUrl }
        }
        if (visibleSources.any { it.loading }) {
            homeCatalogsLoading = true
            shelves = emptyList()
            return@LaunchedEffect
        }
        val catalogs = catalogInventory.homeShelves
        if (catalogs.isEmpty()) {
            shelves = emptyList()
            homeCatalogsLoading = false
            return@LaunchedEffect
        }
        homeCatalogsLoading = true
        shelves = catalogs.map { selection ->
            ShelfState(
                addonUrl = selection.addonUrl,
                addonName = selection.addonName,
                catalog = selection.catalog,
                loading = true,
            )
        }
        val loaded = coroutineScope {
            catalogs.map { selection ->
                async {
                    selection to runCatching { client.catalog(selection.addonUrl, selection.catalog) }
                }
            }.awaitAll()
        }
        shelves = loaded.map { (selection, result) ->
            result.fold(
                onSuccess = {
                    ShelfState(selection.addonUrl, selection.addonName, selection.catalog, titles = it)
                },
                onFailure = {
                    ShelfState(selection.addonUrl, selection.addonName, selection.catalog, error = it.userMessage())
                },
            )
        }
        homeCatalogsLoading = false
    }

    fun reloadSources() {
        scope.launch { loadSources(sourceStore.all()) }
    }

    fun selectHomeProvider(addonUrl: String?) {
        homeProviderSelectionStore.setSelectedUrl(addonUrl)
        selectedProviderUrl = addonUrl
    }

    fun toggleMultiFeed(enabled: Boolean) {
        homeProviderSelectionStore.setMultiFeedEnabled(enabled)
        multiFeedEnabled = enabled
        if (enabled && activeFeedUrls.isEmpty()) {
            val all = sourceStates.map { it.url }
            homeProviderSelectionStore.setActiveFeedUrls(all)
            activeFeedUrls = all
        }
    }

    fun moveFeed(from: Int, to: Int) {
        homeProviderSelectionStore.moveInFeed(from, to)
        activeFeedUrls = runCatching { homeProviderSelectionStore.activeFeedUrls() }.getOrDefault(activeFeedUrls)
    }

    fun loadNextCatalogPage() {
        val current = catalogBrowse
        val target = current.target ?: return
        if (!current.canLoadNextPage()) return
        val requestedPage = current.nextPage
        val requestedSearchQuery = current.appliedSearchQuery
        catalogBrowse = current.beginPageLoad()
        scope.launch {
            try {
                val pageResult = catalogPageSource.loadPage(
                    target,
                    page = requestedPage,
                    search = requestedSearchQuery,
                )
                val latest = catalogBrowse
                if (latest.acceptsResponse(target, requestedPage, requestedSearchQuery)) {
                    catalogBrowse = latest.finishPage(pageResult)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val latest = catalogBrowse
                if (latest.acceptsResponse(target, requestedPage, requestedSearchQuery)) {
                    catalogBrowse = latest.failPage(error.userMessage())
                }
            }
        }
    }

    fun submitCatalogSearch() {
        val current = catalogBrowse
        if (current.loading || current.target?.supportsSearch != true) return
        val searched = current.applySearchText()
        if (searched == current) return
        catalogBrowse = searched
        loadNextCatalogPage()
    }

    fun openCatalog(selection: HomeCatalogSelection) {
        val target = selection.toCatalogReference()
        catalogReturnPage = catalogBrowseReturnDestination(page)
        catalogBrowse = CatalogBrowseState(
            target = target,
            hasMore = true,
        )
        page = DesktopPage.CATALOG
        loadNextCatalogPage()
    }

    fun addSource() {
        val rawUrl = sourceUrlInput.trim()
        if (rawUrl.isBlank()) {
            addSourceError = "Enter an add-on URL first."
            return
        }
        scope.launch {
            addSourceLoading = true
            addSourceError = null
            try {
                val manifest = client.loadManifest(rawUrl)
                if (!sourceStore.add(manifest.addonUrl)) {
                    addSourceError = "This source is already in the list."
                } else {
                    sourceStates = sourceStates.filterNot { it.url == manifest.addonUrl } +
                        SourceState(manifest.addonUrl, manifest = manifest)
                    sourceUrlInput = ""
                    page = DesktopPage.HOME
                }
            } catch (error: Exception) {
                addSourceError = error.userMessage()
            } finally {
                addSourceLoading = false
            }
        }
    }

    fun removeSource(url: String) {
        try {
            sourceStore.remove(url)
            sourceStates = sourceStates.filterNot { it.url == url }
            if (selectedProviderUrl == url) selectHomeProvider(null)
            selectedSearchSourceUrls = selectedSearchSourceUrls - url
        } catch (error: Exception) {
            addSourceError = error.userMessage()
            page = DesktopPage.SOURCES
        }
    }


    fun engineMediaOf(provider: EngineProvider, title: AddonTitle): EngineMedia =
        EngineMedia(
            providerId = provider.id,
            id = title.id,
            title = title.title,
            kind = if (title.type.equals("movie", ignoreCase = true)) EngineMediaKind.MOVIE else EngineMediaKind.SERIES,
            posterUrl = title.posterUrl,
            backdropUrl = title.backgroundUrl,
            overview = title.description,
            year = title.year,
            rating = title.rating,
            genres = title.genres,
        )

    fun loadEngineDetail(providerId: String, title: AddonTitle) {
        detailLoading = true
        detailError = null
        titleDetail = null
        scope.launch {
            try {
                val provider = engineRegistry.all().firstOrNull { it.id == providerId && it.enabled }
                    ?: throw IllegalStateException("This extension is not installed or is disabled.")
                val engine = engineRegistry.engineFor(provider.kind)
                    ?: throw IllegalStateException("This extension's engine is unavailable.")
                val media = withContext(Dispatchers.IO) { engine.meta(provider, engineMediaOf(provider, title)) }
                val episodes = withContext(Dispatchers.IO) {
                    if (media.kind == EngineMediaKind.SERIES) engine.episodes(provider, media) else emptyList()
                }
                titleDetail = AddonDetail(
                    media.toAddonTitle(provider),
                    episodes.map { it.toAddonEpisode() },
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                detailError = error.userMessage()
            } finally {
                detailLoading = false
            }
        }
    }

    fun openTitle(title: AddonTitle) {
        previousPage = if (page == DesktopPage.DETAIL) previousPage else page
        selectedTitle = title
        titleDetail = null
        detailError = null
        detailLoading = true
        playbackMessage = null
        page = DesktopPage.DETAIL
        val engineRef = parseEngineRef(title.addonUrl)
        if (engineRef != null) {
            loadEngineDetail(engineRef.second, title)
            return
        }
        scope.launch {
            try {
                titleDetail = client.detail(title)
                if (titleDetail == null) detailError = "This add-on doesn't provide full metadata for this title yet."
            } catch (error: Exception) {
                detailError = error.userMessage()
            } finally {
                detailLoading = false
            }
        }
    }

    fun requestStreams(
        title: AddonTitle,
        episode: AddonEpisode? = null,
        availableEpisodes: List<AddonEpisode> = emptyList(),
    ) {
        streamDialogTitle = if (episode == null) title.title else "${title.title} · ${episode.title}"
        streamDialogTitleEntry = title
        streamDialogEpisodeEntry = episode
        streams = emptyList()
        streamError = null
        streamLoading = true
        streamDialogOpen = true
        val videoId = when {
            episode != null -> episode.id
            else -> defaultVideoIdForPlayback(title, availableEpisodes)
        }
        scope.launch {
            try {
                streams = client.streamsForTitle(
                    title = title,
                    videoId = videoId,
                    installedAddons = sourceStates.mapNotNull { it.manifest },
                )
                val engineStreams = runCatching {
                    val media = EngineMedia(
                        providerId = "",
                        id = videoId,
                        title = title.title,
                        kind = if (title.type.equals("movie", ignoreCase = true)) {
                            EngineMediaKind.MOVIE
                        } else {
                            EngineMediaKind.SERIES
                        },
                        posterUrl = title.posterUrl,
                        year = title.year,
                    )
                    val engineEpisode = episode?.let {
                        com.hikari.desktop.engine.EngineEpisode("", media.id, it.id, it.season, it.number, it.title)
                    }
                    engineRegistry.streamsFor(media, engineEpisode)
                }.getOrDefault(emptyList())
                engineStreamOptions = engineStreams.flatMap { it.second }.distinctBy { it.url }
                val converted = engineStreams.flatMap { (provider, list) ->
                    list.map { it.toAddonStream() }
                }
                streams = (streams + converted).distinctBy { it.url }
                if (streams.isEmpty()) {
                    streamError = "No supported HTTP streams from matching add-ons or extensions."
                }
            } catch (error: Exception) {
                streamError = error.userMessage()
            } finally {
                streamLoading = false
            }
        }
    }

    fun openStream(stream: AddonStream) {
        scope.launch {
            try {
                val titleEntry = streamDialogTitleEntry
                if (titleEntry == null) {
                    streamError = "Title unavailable, history wasn't saved."
                    return@launch
                }
                // Files the internal player can render open in-app; the rest
                // keeps the previous external-launch behavior.
                if (isInternalPlayerUrl(stream.url)) {
                    val engineStream = engineStreamOptions.firstOrNull { it.url == stream.url }
                        ?: EngineStream("external", stream.name, stream.url)
                    val playable = (
                        listOf(engineStream) +
                            engineStreamOptions.filter { it.url != stream.url && isInternalPlayerUrl(it.url) }
                        ).distinctBy { it.url }
                    val subtitles = engineStream.subtitles
                    val outcome = launchAndRecordHistory(
                        store = watchHistoryStore,
                        title = titleEntry,
                        episode = streamDialogEpisodeEntry,
                    ) {
                        withContext(Dispatchers.Main) {
                            playerSubtitles = subtitles
                            playerRequest = EnginePlayerRequest(
                                title = streamDialogTitle,
                                streams = playable,
                                initial = engineStream,
                            )
                        }
                        "Hikari player"
                    }
                    outcome.entries?.let { watchHistory = it }
                    watchHistoryError = outcome.historyError?.userMessage()
                    playbackMessage = "Playing in Hikari player."
                    streamDialogOpen = false
                    streamDialogTitleEntry = null
                    streamDialogEpisodeEntry = null
                    return@launch
                }
                val outcome = launchAndRecordHistory(
                    store = watchHistoryStore,
                    title = titleEntry,
                    episode = streamDialogEpisodeEntry,
                ) {
                    withContext(Dispatchers.IO) { StreamLauncher.open(stream) }
                }
                outcome.entries?.let { watchHistory = it }
                watchHistoryError = outcome.historyError?.userMessage()
                playbackMessage = if (outcome.destination == "browser") {
                    "Link opened in the browser."
                } else {
                    "Link sent to ${outcome.destination}."
                }
                streamDialogOpen = false
                streamDialogTitleEntry = null
                streamDialogEpisodeEntry = null
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                streamError = error.userMessage()
            }
        }
    }

    fun updateSearchHistory(update: () -> List<String>) {
        runCatching(update)
            .onSuccess {
                searchHistory = it
                searchHistoryError = null
            }
            .onFailure {
                searchHistoryError = "Couldn't update search history."
            }
    }

    fun removeSearchHistory(query: String) {
        updateSearchHistory { searchHistoryStore.remove(query) }
    }

    fun clearSearchHistory() {
        updateSearchHistory { searchHistoryStore.clear() }
    }

    fun search(queryOverride: String? = null) {
        val query = (queryOverride ?: searchQuery).trim()
        if (query.isBlank()) {
            searchError = "Type a title to search for."
            searchPartialFailure = false
            return
        }
        val urls = selectSearchSourceUrls(sourceStates.map { it.url }, selectedSearchSourceUrls)
        if (urls.isEmpty() && engineRegistry.enabled().isEmpty()) {
            searchError = "Add Stremio sources or extensions before searching."
            searchPartialFailure = false
            return
        }
        searchQuery = query
        updateSearchHistory { searchHistoryStore.record(query) }
        scope.launch {
            searchLoading = true
            searchError = null
            searchPartialFailure = false
            searchResults = emptyList()
            try {
                val batches = coroutineScope {
                    urls.map { url -> async { runCatching { client.search(url, query) } } }.awaitAll()
                }
                val stremioHits = batches.mapNotNull { it.getOrNull() }
                    .flatten()
                    .distinctBy { "${it.addonUrl}:${it.type}:${it.id}" }
                val engineHits = runCatching { engineRegistry.searchAll(query) }.getOrDefault(emptyList())
                val engineTitles = engineHits.flatMap { (provider, items) ->
                    items.map { it.toAddonTitle(provider) }
                }
                searchResults = (stremioHits + engineTitles)
                    .distinctBy { "${it.addonUrl}:${it.type}:${it.id}" }
                if (batches.all { it.isFailure } && engineHits.isEmpty()) {
                    searchError = batches.firstOrNull()?.exceptionOrNull()?.userMessage()
                        ?: "Search failed on every source."
                } else if (batches.any { it.isFailure }) {
                    searchError = "Some sources didn't respond; showing the results that arrived."
                    searchPartialFailure = true
                }
            } finally {
                searchLoading = false
            }
        }
    }

    val title = when (page) {
        DesktopPage.HOME -> "Home"
        DesktopPage.SEARCH -> "Search titles"
        DesktopPage.LIBRARY -> "Library"
        DesktopPage.HISTORY -> "History"
        DesktopPage.SOURCES -> "Sources"
        DesktopPage.EXTENSIONS -> "Extensions"
        DesktopPage.CATALOGS -> "All catalogs"
        DesktopPage.CATALOG -> catalogBrowse.target?.catalogName ?: "Catalog"
        DesktopPage.DETAIL -> "Details"
    }
    val subtitle = when (page) {
        DesktopPage.HOME -> "Catalogs from your installed Stremio add-ons"
        DesktopPage.SEARCH -> "Search across catalogs that support it"
        DesktopPage.LIBRARY -> "Titles you saved from detail pages"
        DesktopPage.HISTORY -> "Titles and episodes you launched"
        DesktopPage.SOURCES -> "Add and manage Stremio catalog sources"
        DesktopPage.EXTENSIONS -> "Stremio, JS and IPTV extensions in one place"
        DesktopPage.CATALOGS -> "Catalogs from every installed Stremio add-on"
        DesktopPage.CATALOG -> catalogBrowse.target?.sourceName ?: "Every title in the catalog"
        DesktopPage.DETAIL -> "Title info and playback options"
    }

    Row(Modifier.fillMaxSize()) {
        DesktopSidebar(
            selectedPage = when (page) {
                DesktopPage.CATALOG, DesktopPage.CATALOGS -> DesktopPage.HOME
                DesktopPage.DETAIL -> if (previousPage == DesktopPage.CATALOG) DesktopPage.HOME else previousPage
                else -> page
            },
            onNavigate = { page = it },
        )
        Column(
            modifier = Modifier.fillMaxHeight().weight(1f),
        ) {
            androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize()) {
                    PageHeader(title = title, subtitle = subtitle)
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    Box(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 22.dp),
                    ) {
                        when (page) {
                            DesktopPage.HOME -> HomeScreen(
                                sources = sourceStates,
                                selectedProviderUrl = selectedProviderUrl,
                                selectedMediaFilter = selectedMediaFilter,
                                catalogs = catalogInventory.allCatalogs,
                                shelves = shelves + engineShelves,
                                loading = homeCatalogsLoading || engineShelvesLoading,
                                onSelectProvider = ::selectHomeProvider,
                                onSelectMediaFilter = { selectedMediaFilter = it },
                                onOpenTitle = ::openTitle,
                                onOpenCatalog = {
                                    // Engine shelves open titles directly; only
                                    // Stremio shelves have a browsable catalog.
                                    if (!it.addonUrl.startsWith("engine:")) openCatalog(it)
                                },
                                onOpenCatalogDirectory = { page = DesktopPage.CATALOGS },
                                onOpenSources = { page = DesktopPage.SOURCES },
                                onRetry = ::reloadSources,
                                multiFeedEnabled = multiFeedEnabled,
                                onToggleMultiFeed = ::toggleMultiFeed,
                                activeFeedUrls = activeFeedUrls,
                                onMoveFeed = ::moveFeed,
                                onRemoveFeed = { url ->
                                    homeProviderSelectionStore.removeFromFeed(url)
                                    activeFeedUrls = runCatching { homeProviderSelectionStore.activeFeedUrls() }.getOrDefault(activeFeedUrls)
                                },
                                onAddFeed = { url ->
                                    homeProviderSelectionStore.addToFeed(url)
                                    activeFeedUrls = runCatching { homeProviderSelectionStore.activeFeedUrls() }.getOrDefault(activeFeedUrls)
                                },
                                onToggleSaveTitle = ::toggleSavedTitle,
                            )
                            DesktopPage.CATALOGS -> CatalogDirectoryScreen(
                                catalogs = catalogInventory.allCatalogs,
                                sources = sourceStates,
                                loading = sourceStates.any { it.loading },
                                onBack = { page = DesktopPage.HOME },
                                onRetry = ::reloadSources,
                                onOpenSources = { page = DesktopPage.SOURCES },
                                onOpenCatalog = ::openCatalog,
                            )
                            DesktopPage.CATALOG -> CatalogBrowseScreen(
                                state = catalogBrowse,
                                onBack = { page = catalogReturnPage },
                                onLoadNextPage = ::loadNextCatalogPage,
                                onSearchTextChange = { text -> catalogBrowse = catalogBrowse.copy(searchText = text) },
                                onSearch = ::submitCatalogSearch,
                                onOpenTitle = ::openTitle,
                            )
                            DesktopPage.LIBRARY -> LibraryScreen(
                                titles = savedTitles,
                                loading = savedTitlesLoading,
                                error = savedTitlesError,
                                categories = libraryCategories,
                                categoryAssignments = libraryCategoryAssignments,
                                categoryError = libraryCategoryError,
                                onRetry = ::reloadSavedTitles,
                                onBrowseHome = { page = DesktopPage.HOME },
                                onOpenTitle = ::openTitle,
                                onRemoveTitle = ::removeSavedTitle,
                                onCreateCategory = ::createLibraryCategory,
                                onRenameCategory = { id, name ->
                                    updateLibraryCategory { libraryCategoryStore.rename(id, name) }
                                },
                                onDeleteCategory = { id ->
                                    updateLibraryCategory { libraryCategoryStore.delete(id) }
                                },
                                onAssignCategories = { title, ids ->
                                    updateLibraryCategory { libraryCategoryStore.assign(title, ids) }
                                },
                                onMoveCategory = { id, delta ->
                                    updateLibraryCategory { libraryCategoryStore.move(id, delta) }
                                },
                            )
                            DesktopPage.HISTORY -> WatchHistoryScreen(
                                entries = watchHistory,
                                loading = watchHistoryLoading,
                                error = watchHistoryError,
                                onRetry = ::reloadWatchHistory,
                                onBrowseHome = { page = DesktopPage.HOME },
                                onOpenTitle = ::openTitle,
                                onPlayAgain = { entry -> requestStreams(entry.title, entry.episode) },
                                onRemove = ::removeWatchHistoryEntry,
                                onClear = ::clearWatchHistory,
                            )
                            DesktopPage.SEARCH -> SearchScreen(
                                query = searchQuery,
                                onQueryChange = {
                                    searchQuery = it
                                    if (it.isBlank()) {
                                        searchResults = emptyList()
                                        searchError = null
                                        searchPartialFailure = false
                                    }
                                },
                                loading = searchLoading,
                                error = searchError,
                                partialFailure = searchPartialFailure,
                                results = searchResults,
                                recentSearches = searchHistory,
                                historyError = searchHistoryError,
                                hasSources = sourceStates.isNotEmpty(),
                                onSearch = { search() },
                                onRunRecentSearch = { search(it) },
                                onRemoveRecentSearch = ::removeSearchHistory,
                                onClearRecentSearches = ::clearSearchHistory,
                                onOpenTitle = ::openTitle,
                                onOpenSources = { page = DesktopPage.SOURCES },
                                searchSources = sourceStates.map { source ->
                                    SearchSourceOption(
                                        url = source.url,
                                        name = source.manifest?.name ?: source.url,
                                    )
                                },
                                selectedSearchSourceUrls = selectedSearchSourceUrls,
                                onSearchSourcesSelected = { sourceUrls ->
                                    if (selectedSearchSourceUrls != sourceUrls) {
                                        selectedSearchSourceUrls = sourceUrls
                                        if (searchQuery.isNotBlank()) search(searchQuery)
                                    }
                                },
                            )
                            DesktopPage.SOURCES -> SourceManagerScreen(
                                sources = sourceStates,
                                url = sourceUrlInput,
                                onUrlChange = { sourceUrlInput = it; addSourceError = null },
                                adding = addSourceLoading,
                                error = addSourceError,
                                onAdd = ::addSource,
                                onRetry = ::reloadSources,
                                onRemove = ::removeSource,
                                onUse = {
                                    selectHomeProvider(it)
                                    page = DesktopPage.HOME
                                },
                            )
                            DesktopPage.EXTENSIONS -> ExtensionsScreen(
                                providers = engineProviders,
                                tmdbKey = engineTmdbKeyInput,
                                onTmdbKeyChange = { engineTmdbKeyInput = it },
                                onSaveTmdbKey = {
                                    engineRegistry.setTmdbKey(engineTmdbKeyInput)
                                    engineTmdbKeyInput = engineRegistry.tmdbKey()
                                },
                                addUrl = engineAddUrl,
                                onAddUrlChange = {
                                    engineAddUrl = it
                                    engineAddKind = guessEngineKind(it)
                                    engineAddError = null
                                },
                                addKind = engineAddKind,
                                onAddKindChange = { engineAddKind = it },
                                adding = engineAdding,
                                addError = engineAddError,
                                onAddProvider = { installEngineProvider(engineAddKind, engineAddUrl) },
                                repoKind = engineRepoKind,
                                onRepoKindChange = {
                                    engineRepoKind = it
                                    engineRepoEntries = emptyList()
                                    engineRepoError = null
                                    engineRepoName = ""
                                },
                                repoOptions = repoOptionsFor(engineRepoKind),
                                repoEntries = engineRepoEntries,
                                repoName = engineRepoName,
                                reposLoading = engineReposLoading,
                                repoError = engineRepoError,
                                onLoadRepo = { url, name -> openedRepoUrl = url; loadEngineRepo(engineRepoKind, url, name) },
                                onInstallRepoEntry = { installEngineRepoFile(engineRepoKind, it) },
                                onToggleEnabled = { id, enabled ->
                                    engineRegistry.setEnabled(id, enabled)
                                    reloadEngineProviders()
                                    refreshEngineShelves()
                                },
                                onRemove = { id ->
                                    engineRegistry.remove(id)
                                    reloadEngineProviders()
                                    refreshEngineShelves()
                                },
                                onValidate = ::validateEngineProvider,
                                openedRepoUrl = openedRepoUrl,
                                onCloseRepo = { openedRepoUrl = null },
                                onCopyRepo = { },
                                onRefreshRepo = { url -> loadEngineRepo(engineRepoKind, url, engineRepoName) },
                                onDeleteRepo = { openedRepoUrl = null },
                                installedEntryNames = engineProviders.map { it.name }.toSet(),
                                multiFeedEnabled = multiFeedEnabled,
                                onAddToFeed = { name ->
                                    val stremioMatch = sourceStates.firstOrNull { (it.manifest?.name ?: it.url) == name }
                                    if (stremioMatch != null) {
                                        homeProviderSelectionStore.addToFeed(stremioMatch.url)
                                        activeFeedUrls = runCatching { homeProviderSelectionStore.activeFeedUrls() }.getOrDefault(activeFeedUrls)
                                    } else {
                                        // Engine providers feed Home automatically; make sure it is enabled.
                                        engineProviders.firstOrNull { it.name == name }?.let { provider ->
                                            if (!provider.enabled) {
                                                engineRegistry.setEnabled(provider.id, true)
                                                reloadEngineProviders()
                                                refreshEngineShelves()
                                            }
                                        }
                                    }
                                },
                            )
                            DesktopPage.DETAIL -> selectedTitle?.let { selected ->
                                val detailTitle = titleDetail?.title ?: selected
                                val detailEpisodes = titleDetail?.episodes.orEmpty()
                                DetailScreen(
                                    title = detailTitle,
                                    episodes = detailEpisodes,
                                    loading = detailLoading,
                                    error = detailError,
                                    playbackMessage = playbackMessage,
                                    isSaved = savedTitles.any { it.savedTitleIdentity() == detailTitle.savedTitleIdentity() },
                                    libraryError = savedTitlesError,
                                    onBack = { page = previousPage },
                                    onPlay = { requestStreams(selected, availableEpisodes = detailEpisodes) },
                                    onToggleSaved = { toggleSavedTitle(detailTitle) },
                                    onPlayEpisode = { requestStreams(selected, it) },
                                    providerName = sourceStates.firstOrNull { it.url == detailTitle.addonUrl }?.manifest?.name
                                        ?: engineProviders.firstOrNull { engineAddonUrl(it) == detailTitle.addonUrl }?.name
                                        ?: "",
                                )
                            } ?: EmptyDetailScreen(onBack = { page = DesktopPage.HOME })
                        }
                    }
                }
            }
        }
    }

    if (streamDialogOpen) {
        StreamPickerDialog(
            title = streamDialogTitle,
            streams = streams,
            loading = streamLoading,
            error = streamError,
            onChoose = ::openStream,
            onDismiss = {
                streamDialogOpen = false
                streamDialogTitleEntry = null
                streamDialogEpisodeEntry = null
            },
        )
    }

    val request = playerRequest
    if (request != null) {
        Window(
            onCloseRequest = {
                playerRequest = null
                playerEngine.close()
            },
            title = "Hikari Player",
            state = rememberWindowState(width = 1100.dp, height = 700.dp),
        ) {
            HikariDesktopTheme {
                PlayerScreen(
                    title = request.title,
                    streams = request.streams,
                    initial = request.initial,
                    subtitles = playerSubtitles,
                    engine = playerEngine,
                    videoSurface = { JavaFxVideoSurface(playerEngine) },
                    onClose = {
                        playerRequest = null
                        playerEngine.close()
                    },
                    onOpenExternal = { external ->
                        playerRequest = null
                        playerEngine.close()
                        openStream(AddonStream(external.name, external.url, StreamKind.BROWSER))
                    },
                )
            }
        }
    }
}

@Composable
private fun PageHeader(title: String, subtitle: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            androidx.compose.material3.Text(title, style = MaterialTheme.typography.headlineMedium)
            androidx.compose.material3.Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyDetailScreen(onBack: () -> Unit) {
    Column {
        androidx.compose.material3.TextButton(onClick = onBack) { androidx.compose.material3.Text("Back") }
        androidx.compose.material3.Text("Title unavailable.")
    }
}

private fun Throwable.userMessage(): String = when (this) {
    is AddonClientException -> message ?: "Add-on request failed."
    else -> message?.takeIf(String::isNotBlank) ?: "Something went wrong. Try again."
}

private const val MAX_HOME_CATALOGS = 8

// ---- Extension installer helpers (top-level for testability) ----

internal fun sanitizeEngineKey(raw: String): String {
    val base = raw.substringAfterLast('/').substringAfterLast('\\').substringBefore('?').trim()
    val clean = base.ifBlank { raw.take(48) }
    return clean.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(80).trim('_').ifBlank { "ext" }
}

internal fun engineFileFor(kind: EngineKind, id: String, ext: String): java.io.File {
    val safe = id.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ext
    return NuvioEngine.homeDir().resolve("engines/${kind.prefix}").apply { mkdirs() }.resolve(safe)
}

internal suspend fun buildEngineProvider(
    registry: ProviderRegistry,
    kind: EngineKind,
    raw: String,
    nameOverride: String? = null,
): EngineProvider {
    val url = raw.trim()
    if (url.isEmpty()) error("Enter an extension URL or file path first.")
    if (kind == EngineKind.STREMIO) {
        val host = runCatching { java.net.URI(url).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: url.take(48)
        val provider = EngineProvider(
            id = "${kind.prefix}|${sanitizeEngineKey(host)}",
            kind = kind,
            name = nameOverride?.takeIf { it.isNotBlank() } ?: host,
            source = url,
        )
        val adapter = registry.engineFor(EngineKind.STREMIO) as? StremioEngineAdapter
            ?: error("Stremio engine is unavailable.")
        adapter.validate(provider)?.let { error(it) }
        return provider
    }
    if (kind == EngineKind.IPTV) {
        val parts = url.split("|")
        if (parts.size == 3) {
            val host = parts[0].trim().trimEnd('/')
            if (host.isBlank() || parts[1].isBlank()) error("Use host|user|pass for Xtream logins.")
            return EngineProvider(
                id = "${kind.prefix}|${sanitizeEngineKey(host)}",
                kind = kind,
                name = nameOverride?.takeIf { it.isNotBlank() } ?: host,
                source = "$host|${parts[1].trim()}|${parts[2]}",
            )
        }
        val text = if (url.startsWith("http", true)) {
            EngineHttp.fetchText(url, 8 * 1024 * 1024) ?: error("Could not download that playlist.")
        } else {
            val local = java.io.File(url)
            if (!local.exists()) error("File not found: $url")
            local.readText()
        }
        if (!text.contains("#EXTINF", true) && !text.contains("http", true)) {
            error("That file is not an M3U playlist.")
        }
        val file = engineFileFor(kind, "${kind.prefix}|${sanitizeEngineKey(url)}", ".m3u")
        file.writeText(text)
        return EngineProvider(
            id = "${kind.prefix}|${sanitizeEngineKey(url)}",
            kind = kind,
            name = nameOverride?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension,
            source = file.absolutePath,
        )
    }
    // JS families: resolve to one provider .js (direct .js, Sora manifest.json,
    // .sky zip, or Vega dist folder), save it, then validate in V8.
    // Repo installs carry a URL hash so same-named entries from different
    // repos never overwrite each other; direct adds keep the plain key.
    val keyBase = if (!nameOverride.isNullOrBlank()) {
        "${sanitizeEngineKey(nameOverride)}-${shortUrlHash(url)}"
    } else {
        sanitizeEngineKey(url)
    }
    val id = "${kind.prefix}|$keyBase"
    val jsFile = engineFileFor(kind, id, ".js")
    when (kind) {
        EngineKind.SORA -> {
            val script = resolveSoraScript(url) ?: error("Could not get that module's scriptUrl.")
            jsFile.writeText(script)
        }
        EngineKind.SKYSTREAM -> {
            val (js, manifest) = resolveSkyPlugin(url)
                ?: error("Not a SkyStream extension (needs plugin.js + plugin.json).")
            jsFile.writeBytes(js)
            val manifestFile = java.io.File(jsFile.parentFile, jsFile.nameWithoutExtension + ".manifest.json")
            if (!manifest.isNullOrBlank()) runCatching { manifestFile.writeText(manifest) }
        }
        EngineKind.VEGA -> {
            val script = resolveVegaScript(url) ?: error("Not a Vega provider (no posts/stream/meta module).")
            jsFile.writeText(script)
        }
        else -> {
            val text = if (url.startsWith("http", true)) {
                EngineHttp.fetchText(url, 5 * 1024 * 1024) ?: error("Could not download that script.")
            } else {
                val local = java.io.File(url)
                if (!local.exists()) error("File not found: $url")
                local.readText()
            }
            if (text.isBlank()) error("That script is empty.")
            jsFile.writeText(text)
        }
    }
    val provider = EngineProvider(
        id = id,
        kind = kind,
        name = nameOverride?.takeIf { it.isNotBlank() } ?: jsFile.nameWithoutExtension,
        source = jsFile.absolutePath,
    )
    val engine = registry.engineFor(kind) ?: error("${kind.title} engine is unavailable.")
    val problem = engine.validate(provider)
    if (problem != null) {
        runCatching { jsFile.delete() }
        error(problem)
    }
    return provider
}

/** Sora manifest.json -> its scriptUrl's .js (or the .js itself when given one). */
internal fun resolveSoraScript(url: String): String? {
    val text = if (url.startsWith("http", true)) {
        EngineHttp.fetchText(url, 2 * 1024 * 1024)
    } else {
        runCatching { java.io.File(url).takeIf { it.exists() }?.readText() }.getOrNull()
    } ?: return null
    if (!text.contains("scriptUrl")) return text.takeIf { it.isNotBlank() }
    val manifest = runCatching {
        com.google.gson.Gson().fromJson(text, com.google.gson.JsonObject::class.java)
    }.getOrNull() ?: return null
    val scriptUrl = manifest.get("scriptUrl")?.takeIf { it.isJsonPrimitive }?.asString?.trim()
        ?.takeIf { it.isNotBlank() } ?: return null
    if (scriptUrl.startsWith("http", true)) {
        return EngineHttp.fetchText(scriptUrl, 5 * 1024 * 1024)?.takeIf { it.isNotBlank() }
    }
    val base = url.substringBeforeLast('/').trimEnd('/')
    if (url.startsWith("http", true)) {
        return EngineHttp.fetchText("$base/${scriptUrl.trimStart('/')}", 5 * 1024 * 1024)
            ?.takeIf { it.isNotBlank() }
    }
    return runCatching { java.io.File(base, scriptUrl).takeIf { it.exists() }?.readText() }
        .getOrNull()?.takeIf { it.isNotBlank() }
}

/** .sky zip (URL or local file) -> (plugin.js bytes, plugin.json text). */
internal fun resolveSkyPlugin(url: String): Pair<ByteArray, String?>? {
    val bytes = if (url.startsWith("http", true)) {
        EngineHttp.fetchBytes(url, 8 * 1024 * 1024)
    } else {
        runCatching { java.io.File(url).takeIf { it.exists() }?.readBytes() }.getOrNull()
    } ?: return null
    if (bytes.size < 4) return null
    // Not a zip: a bare plugin.js pasted directly.
    if (!(bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte())) {
        val text = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull()
        return if (text.isNullOrBlank()) null else text.toByteArray(Charsets.UTF_8) to null
    }
    return unzipSky(bytes)
}

internal fun unzipSky(bytes: ByteArray): Pair<ByteArray, String?>? {
    return runCatching {
        var js: ByteArray? = null
        var manifest: String? = null
        java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
            var entries = 0
            var entry = zip.nextEntry
            while (entry != null && entries < 100) {
                entries++
                if (!entry.isDirectory) {
                    val leaf = entry.name.substringAfterLast('/').lowercase()
                    if (js == null && leaf == "plugin.js") {
                        val data = zip.readBytes()
                        if (data.size in 1..(8 * 1024 * 1024)) js = data
                    } else if (manifest == null && leaf == "plugin.json") {
                        val data = zip.readBytes()
                        if (data.size in 1..(2 * 1024 * 1024)) {
                            manifest = runCatching { String(data, Charsets.UTF_8) }.getOrNull()
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        if (js == null) null else js to manifest
    }.getOrNull()
}

/** Vega dist folder (or single .js) -> one concatenated provider script. */
internal fun resolveVegaScript(url: String): String? {
    val dist = url.trim().trimEnd('/')
    if (dist.endsWith(".js", ignoreCase = true) || java.io.File(dist).isFile) {
        val single = if (dist.startsWith("http", true)) EngineHttp.fetchText(dist, 5 * 1024 * 1024)
        else runCatching { java.io.File(dist).takeIf { it.exists() }?.readText() }.getOrNull()
        return single?.takeIf { it.isNotBlank() }
    }
    val parts = vegaModuleNames.mapNotNull { name ->
        val body = EngineHttp.fetchText("$dist/$name.js", 2 * 1024 * 1024) ?: return@mapNotNull null
        name to vegaCompatPatch(name, body)
    }.toMap()
    if (parts.keys.none { it == "stream" || it == "posts" || it == "meta" }) return null
    return vegaModuleNames.filter { parts.containsKey(it) }
        .joinToString("\n") { name -> "\n;//-module:$name\n" + parts.getValue(name) }
}

internal val vegaModuleNames = listOf("catalog", "posts", "meta", "stream", "episodes", "settings")

/** Port of VegaPluginManager.compatPatch (broken sourceHeading helper fix). */
internal fun vegaCompatPatch(fileName: String, text: String): String {
    if (fileName != "meta" || !text.contains("function sourceHeading(")) return text
    var out = text
    out = out.replace(vegaDistBuggy, vegaDistFixed)
    out = out.replace(vegaTsBuggy, vegaTsFixed)
    return out
}

internal const val vegaDistBuggy =
    "function sourceHeading(\$,anchor){let heading=anchor.closest(\"center, p, div\").prevAll(\"h4, h3, h2\").first();return heading.length||(heading=anchor.prevAll(\"h4, h3, h2\").first()),heading.text().replace(/\\s+/g,\" \").trim()}"
internal const val vegaDistFixed =
    "function sourceHeading(\$,anchor){var node=anchor;for(var d=0;d<4&&node&&node.length;d++){var hdg=node.prevAll(\"h4, h3, h2, h5\").first();if(hdg.length)return hdg.text().replace(/\\s+/g,\" \").trim();node=node.parent()}return \"\"}"
internal const val vegaTsBuggy =
    "function sourceHeading(\$: any, anchor: any): string {\n  const container = anchor.closest(\"center, p, div\");\n  let heading = container.prevAll(\"h4, h3, h2\").first();\n  if (!heading.length) heading = anchor.prevAll(\"h4, h3, h2\").first();\n  return heading.text().replace(/\\s+/g, \" \").trim();\n}"
internal const val vegaTsFixed =
    "function sourceHeading(\$: any, anchor: any): string {\n  let node: any = anchor;\n  for (let d = 0; d < 4 && node && node.length; d++) {\n    const hdg = node.prevAll(\"h4, h3, h2, h5\").first();\n    if (hdg.length) return hdg.text().replace(/\\s+/g, \" \").trim();\n    node = node.parent();\n  }\n  return \"\";\n}"

internal fun fetchEngineRepoEntries(repoUrl: String): List<EngineRepoEntry> {
    val text = EngineHttp.fetchText(repoUrl, 4 * 1024 * 1024) ?: error("Could not download repository index.")
    var root = runCatching {
        com.google.gson.Gson().fromJson(text, com.google.gson.JsonElement::class.java)
    }.getOrNull() ?: error("Not a repository index.")
    var base = repoUrl.substringBeforeLast('/')
    // SkyStream repo.json points at its plugin list — follow it (one hop).
    if (root.isJsonObject) {
        val first = root.asJsonObject.get("pluginLists")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.firstOrNull()?.takeIf { it.isJsonPrimitive }?.asString?.trim()
            ?.takeIf { it.isNotBlank() }
        if (first != null) {
            val listText = EngineHttp.fetchText(first, 4 * 1024 * 1024)
                ?: error("Could not download the plugin list.")
            root = runCatching {
                com.google.gson.Gson().fromJson(listText, com.google.gson.JsonElement::class.java)
            }.getOrNull() ?: error("Not a repository index.")
            base = first.substringBeforeLast('/')
        }
    }
    return parseRepoEntries(root, base)
}

/**
 * Pure repo-index parser shared by every JS family (unit-tested in
 * RepoParserTest against the real index shapes):
 * - Nuvio manifest.json: {"scrapers": [{name, filename: "providers/x.js"}]}
 * - Anymex index.json: bare array [{name, sourceCodeUrl: "https://…/x.js"}]
 * - Sora modules.json: {"modules": [{name, manifestUrl}]} (installer then
 *   resolves the manifest's scriptUrl)
 * - SkyStream plugins.json: bare array [{name, url: "https://…/x.sky"}]
 * - Zangetsu index.json: {"sources": [{name, file: "providers/x.js"}]}
 * - Vega manifest.json: bare array [{display_name, value}] (installer then
 *   fetches dist/<value>/*.js)
 */
internal fun parseRepoEntries(
    root: com.google.gson.JsonElement,
    base: String,
): List<EngineRepoEntry> {
    val out = LinkedHashMap<String, String>()
    fun addEntry(name: String?, url: String?) {
        if (url.isNullOrBlank()) return
        val absolute = if (url.startsWith("http", true)) url.trim() else "$base/${url.trimStart('/')}"
        val label = name?.takeIf { it.isNotBlank() }
            ?: absolute.substringAfterLast('/').substringBefore('?').ifBlank { absolute }
        if (absolute.startsWith("http", true)) out[label] = absolute
    }
    fun str(o: com.google.gson.JsonObject, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { k ->
            o.get(k)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
        }
    fun entryFrom(o: com.google.gson.JsonObject) {
        // Sora: manifest URL; the installer resolves scriptUrl from it.
        str(o, "manifestUrl")?.let { addEntry(str(o, "name", "sourceName"), it); return }
        // Vega: dist folder; the installer fetches its module files.
        str(o, "value")?.let { v ->
            addEntry(str(o, "display_name", "displayName", "name") ?: v, "$base/dist/$v")
            return
        }
        // Nuvio / Zangetsu / generic relative script files.
        str(o, "filename", "file", "path")?.let { rel ->
            if (rel.endsWith(".js", ignoreCase = true)) {
                addEntry(str(o, "name", "display_name", "displayName", "title"), rel)
                return
            }
        }
        // Absolute script / archive URLs (Anymex sourceCodeUrl, SkyStream .sky).
        addEntry(
            str(o, "name", "display_name", "displayName", "sourceName", "title", "packageName"),
            str(o, "sourceCodeUrl", "url", "downloadUrl", "href", "download_url", "fileUrl"),
        )
    }
    if (root.isJsonArray) {
        for (el in root.asJsonArray) {
            if (el.isJsonObject) entryFrom(el.asJsonObject)
        }
    } else if (root.isJsonObject) {
        val o = root.asJsonObject
        // One bare Sora manifest IS one module.
        if (o.has("scriptUrl") && o.get("scriptUrl")?.takeIf { it.isJsonPrimitive }?.asString?.isNotBlank() == true) {
            entryFrom(o)
        }
        for (key in listOf("modules", "scrapers", "sources", "files", "plugins", "providers", "extensions", "items", "list", "entries")) {
            val arr = o.get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: continue
            for (el in arr) {
                if (el.isJsonObject) entryFrom(el.asJsonObject)
            }
        }
        if (out.isEmpty()) {
            // Plain {name: url} maps.
            for ((k, v) in o.entrySet()) {
                if (!v.isJsonPrimitive) continue
                val value = v.asString
                if (value.startsWith("http", true) || value.endsWith(".js")) {
                    addEntry(k, value)
                }
            }
        }
    }
    return out.map { (name, url) -> EngineRepoEntry(name, url) }.take(500)
}

internal fun repoOptionsFor(kind: EngineKind): List<EngineRepo> = when (kind) {
    EngineKind.STREMIO -> listOf(
        EngineRepo(
            "https://v3-cinemeta.strem.io/manifest.json",
            "Cinemeta",
            "The official add-on for movie and series catalogs",
            pluginCount = 2,
            badge = "STREMIO",
            iconText = "C",
        ),
    )
    EngineKind.NUVIO -> listOf(
        EngineRepo(
            "https://raw.githubusercontent.com/D3adlyRocket/All-in-One-Nuvio/main/manifest.json",
            "All-in-One-Nuvio",
            "https://raw.githubusercontent.com/D3adlyRocket/All-in-One-Nuvio/main/manifest.json",
            pluginCount = 47,
            badge = "NUVIO",
            iconText = "A",
        ),
        EngineRepo(
            "https://raw.githubusercontent.com/tapframe/nuvio-providers/main/manifest.json",
            "Yoru's Repo",
            "https://raw.githubusercontent.com/tapframe/nuvio-providers/main/manifest.json",
            pluginCount = 29,
            badge = "NUVIO",
            iconText = "Y",
        ),
        EngineRepo(
            "https://raw.githubusercontent.com/Gowaru/gowaru-nuvio-providers/main/manifest.json",
            "Gowaru's Repo",
            "https://raw.githubusercontent.com/Gowaru/gowaru-nuvio-providers/main/manifest.json",
            pluginCount = 27,
            badge = "NUVIO",
            iconText = "G",
        ),
    ) + NuvioEngine.DEFAULT_REPOS.map { EngineRepo(it.first, it.second, it.third, badge = "NUVIO") }
    EngineKind.ANYMEX -> listOf(
        EngineRepo(
            "https://raw.githubusercontent.com/codegeasse1/codegeasse-mihon-extension/repo/index.json",
            "codegeasse1/codegeasse-mihon-extension",
            "https://raw.githubusercontent.com/codegeasse1/codegeasse-mihon-extension/repo/index.json",
            pluginCount = 89,
            badge = "ANIYOMI",
            iconText = "H",
        ),
    ) + AnymexEngine.DEFAULT_REPOS.map { EngineRepo(it.first, it.second, it.third, badge = "ANYMEX") }
    EngineKind.SORA -> listOf(
        EngineRepo(
            "https://raw.githubusercontent.com/xdfkenny/Sora-Modules/main/modules.json",
            "Sora Modules",
            "Community Sora video modules (AllAnime, HydraHD, …)",
            badge = "SORA",
            iconText = "S",
        ),
        EngineRepo(
            "https://raw.githubusercontent.com/justbbcr/streamex/main/streamex.json",
            "StreamEx Sora",
            "StreamEx Sora module collection",
            badge = "SORA",
            iconText = "S",
        ),
        EngineRepo(
            "https://raw.githubusercontent.com/CloudStream/cloudstream-extensions/master/repo.json",
            "CNC Repo (All Language)",
            "All Language Contents",
            pluginCount = 47,
            badge = "CLOUDSTREAM",
            iconText = "C",
        ),
    )
    EngineKind.SKYSTREAM -> listOf(
        EngineRepo(
            "https://raw.githubusercontent.com/akashdh11/skystream-plugins/main/dist/plugins.json",
            "Stars Plugins",
            "SkyStream .sky plugins (4K HD, HDHub4u, MoviesDrive, …)",
            badge = "SKYSTREAM",
            iconText = "S",
        ),
    )
    EngineKind.ZANGETSU -> listOf(
        EngineRepo(
            "https://raw.githubusercontent.com/Spyou/zangetsu-providers/main/index.json",
            "Zangetsu Providers",
            "Zangetsu JS providers (AniKoto, HiAnime, VegaMovies, …)",
            badge = "NUVIO",
            iconText = "Z",
        ),
    )
    EngineKind.VEGA -> listOf(
        EngineRepo(
            "https://raw.githubusercontent.com/Zenda-Cross/vega-providers/main/manifest.json",
            "Vega Providers",
            "The official Vega repo (VMovies, MultiStream, MoviesDrive, …)",
            badge = "VEGA",
            iconText = "V",
        ),
        EngineRepo(
            "https://raw.githubusercontent.com/DHR-Store/vega-providers/main/manifest.json",
            "Vega Providers (Vega-Next)",
            "The Vega-Next fork's provider repo",
            badge = "VEGA",
            iconText = "V",
        ),
    )
    else -> emptyList()
}

internal fun shortUrlHash(url: String): String {
    val digest = runCatching {
        java.security.MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
    }.getOrNull()
    val bytes = digest?.take(4)?.toByteArray() ?: url.hashCode().toString().toByteArray()
    return bytes.joinToString("") { "%02x".format(it) }
}
