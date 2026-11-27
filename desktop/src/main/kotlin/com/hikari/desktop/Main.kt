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
import com.hikari.desktop.data.LibraryCategory
import com.hikari.desktop.data.LibraryCategoryStore
import com.hikari.desktop.data.SavedTitleIdentity
import com.hikari.desktop.data.SavedTitlesStore
import com.hikari.desktop.data.SearchHistoryStore
import com.hikari.desktop.data.StreamKind
import com.hikari.desktop.data.savedTitleIdentity
import com.hikari.desktop.data.WatchHistoryEntry
import com.hikari.desktop.data.WatchHistoryStore
import com.hikari.desktop.net.AddonClientException
import com.hikari.desktop.net.StremioClient
import com.hikari.desktop.playback.StreamLauncher
import com.hikari.desktop.playback.launchAndRecordHistory
import com.hikari.desktop.ui.DesktopPage
import com.hikari.desktop.ui.DesktopSidebar
import com.hikari.desktop.ui.DetailScreen
import com.hikari.desktop.ui.CatalogBrowseScreen
import com.hikari.desktop.ui.CatalogDirectoryScreen
import com.hikari.desktop.ui.catalogBrowseReturnDestination
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
    val savedTitlesStore = remember { SavedTitlesStore() }
    val libraryCategoryStore = remember { LibraryCategoryStore() }
    val searchHistoryStore = remember { SearchHistoryStore() }
    val watchHistoryStore = remember { WatchHistoryStore() }
    val scope = rememberCoroutineScope()

    var page by remember { mutableStateOf(DesktopPage.HOME) }
    var catalogReturnPage by remember { mutableStateOf(DesktopPage.HOME) }
    var previousPage by remember { mutableStateOf(DesktopPage.HOME) }
    var sourceStates by remember { mutableStateOf(emptyList<SourceState>()) }
    var selectedProviderUrl by remember { mutableStateOf<String?>(null) }
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
        if (selectedProviderUrl != null && sourceStates.none { it.url == selectedProviderUrl }) {
            selectedProviderUrl = null
        }
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

    LaunchedEffect(Unit) {
        reloadSavedTitles()
        reloadWatchHistory()
        reloadLibraryCategoryState()
        loadSources(sourceStore.all())
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
            addSourceError = "Masukkan URL add-on terlebih dahulu."
            return
        }
        scope.launch {
            addSourceLoading = true
            addSourceError = null
            try {
                val manifest = client.loadManifest(rawUrl)
                if (!sourceStore.add(manifest.addonUrl)) {
                    addSourceError = "Sumber ini sudah ada di daftar."
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
            if (selectedProviderUrl == url) selectedProviderUrl = null
            selectedSearchSourceUrls = selectedSearchSourceUrls - url
        } catch (error: Exception) {
            addSourceError = error.userMessage()
            page = DesktopPage.SOURCES
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
        scope.launch {
            try {
                titleDetail = client.detail(title)
                if (titleDetail == null) detailError = "Add-on ini belum menyediakan metadata lengkap untuk judul ini."
            } catch (error: Exception) {
                detailError = error.userMessage()
            } finally {
                detailLoading = false
            }
        }
    }

    fun requestStreams(title: AddonTitle, episode: AddonEpisode? = null) {
        streamDialogTitle = if (episode == null) title.title else "${title.title} · ${episode.title}"
        streamDialogTitleEntry = title
        streamDialogEpisodeEntry = episode
        streams = emptyList()
        streamError = null
        streamLoading = true
        streamDialogOpen = true
        val movie = title.type.equals("movie", ignoreCase = true) || title.type.equals("film", ignoreCase = true)
        val videoId = when {
            episode != null -> episode.id
            movie -> title.id
            else -> "${title.id}:1:1"
        }
        scope.launch {
            try {
                streams = client.streamsForTitle(
                    title = title,
                    videoId = videoId,
                    installedAddons = sourceStates.mapNotNull { it.manifest },
                )
                if (streams.isEmpty()) {
                    streamError = "Tidak ada stream HTTP yang didukung dari add-on yang cocok. Pemutaran torrent belum tersedia di MVP ini."
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
                    streamError = "Judul tidak tersedia untuk menyimpan riwayat."
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
                    "Tautan dibuka di browser."
                } else {
                    "Tautan dikirim ke ${outcome.destination}."
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
                searchHistoryError = "Riwayat pencarian tidak dapat diperbarui."
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
            searchError = "Ketik judul yang ingin dicari."
            searchPartialFailure = false
            return
        }
        val urls = selectSearchSourceUrls(sourceStates.map { it.url }, selectedSearchSourceUrls)
        if (urls.isEmpty()) {
            searchError = "Tambahkan sumber Stremio sebelum mencari."
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
                searchResults = batches.mapNotNull { it.getOrNull() }
                    .flatten()
                    .distinctBy { "${it.addonUrl}:${it.type}:${it.id}" }
                if (batches.all { it.isFailure }) {
                    searchError = batches.firstOrNull()?.exceptionOrNull()?.userMessage()
                        ?: "Pencarian dari sumber ini gagal."
                } else if (batches.any { it.isFailure }) {
                    searchError = "Sebagian sumber tidak merespons; hasil yang berhasil tetap ditampilkan."
                    searchPartialFailure = true
                }
            } finally {
                searchLoading = false
            }
        }
    }

    val title = when (page) {
        DesktopPage.HOME -> "Beranda"
        DesktopPage.SEARCH -> "Cari judul"
        DesktopPage.LIBRARY -> "Pustaka"
        DesktopPage.HISTORY -> "Riwayat"
        DesktopPage.SOURCES -> "Sumber"
        DesktopPage.CATALOGS -> "Semua katalog"
        DesktopPage.CATALOG -> catalogBrowse.target?.catalogName ?: "Katalog"
        DesktopPage.DETAIL -> "Detail"
    }
    val subtitle = when (page) {
        DesktopPage.HOME -> "Katalog dari add-on Stremio yang sudah ditambahkan"
        DesktopPage.SEARCH -> "Cari lintas katalog yang menyediakan pencarian"
        DesktopPage.LIBRARY -> "Judul yang disimpan dari halaman detail"
        DesktopPage.HISTORY -> "Judul dan episode yang berhasil diluncurkan"
        DesktopPage.SOURCES -> "Tambah dan kelola sumber katalog Stremio"
        DesktopPage.CATALOGS -> "Katalog dari semua add-on Stremio yang dipasang"
        DesktopPage.CATALOG -> catalogBrowse.target?.sourceName ?: "Semua judul dalam katalog"
        DesktopPage.DETAIL -> "Informasi judul dan pilihan pemutaran"
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
                                shelves = shelves,
                                loading = homeCatalogsLoading,
                                onSelectProvider = { selectedProviderUrl = it },
                                onSelectMediaFilter = { selectedMediaFilter = it },
                                onOpenTitle = ::openTitle,
                                onOpenCatalog = ::openCatalog,
                                onOpenCatalogDirectory = { page = DesktopPage.CATALOGS },
                                onOpenSources = { page = DesktopPage.SOURCES },
                                onRetry = ::reloadSources,
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
                                    selectedProviderUrl = it
                                    page = DesktopPage.HOME
                                },
                            )
                            DesktopPage.DETAIL -> selectedTitle?.let { selected ->
                                val detailTitle = titleDetail?.title ?: selected
                                DetailScreen(
                                    title = detailTitle,
                                    episodes = titleDetail?.episodes.orEmpty(),
                                    loading = detailLoading,
                                    error = detailError,
                                    playbackMessage = playbackMessage,
                                    isSaved = savedTitles.any { it.savedTitleIdentity() == detailTitle.savedTitleIdentity() },
                                    libraryError = savedTitlesError,
                                    onBack = { page = previousPage },
                                    onPlay = { requestStreams(selected) },
                                    onToggleSaved = { toggleSavedTitle(detailTitle) },
                                    onPlayEpisode = { requestStreams(selected, it) },
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
        androidx.compose.material3.TextButton(onClick = onBack) { androidx.compose.material3.Text("Kembali") }
        androidx.compose.material3.Text("Judul tidak tersedia.")
    }
}

private fun Throwable.userMessage(): String = when (this) {
    is AddonClientException -> message ?: "Permintaan ke add-on gagal."
    else -> message?.takeIf(String::isNotBlank) ?: "Terjadi kesalahan. Coba lagi."
}

private const val MAX_HOME_CATALOGS = 8
