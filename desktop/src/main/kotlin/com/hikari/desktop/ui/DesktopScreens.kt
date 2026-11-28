package com.hikari.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.hikari.desktop.CatalogBrowseState
import com.hikari.desktop.CatalogDirectoryContentState
import com.hikari.desktop.HomeCatalogSelection
import com.hikari.desktop.HomeMediaFilter
import com.hikari.desktop.ShelfState
import com.hikari.desktop.SourceState
import com.hikari.desktop.catalogDirectoryErrorTitle
import com.hikari.desktop.catalogDirectoryContentState
import com.hikari.desktop.detailEpisodeState
import com.hikari.desktop.data.AddonEpisode
import com.hikari.desktop.data.AddonStream
import com.hikari.desktop.data.AddonTitle
import com.hikari.desktop.data.LibraryCategory
import com.hikari.desktop.data.SavedTitleIdentity
import com.hikari.desktop.data.filterSavedTitlesByCategory
import com.hikari.desktop.data.savedTitleIdentity
import com.hikari.desktop.data.StreamKind
import com.hikari.desktop.data.WatchHistoryEntry
import kotlinx.coroutines.flow.collectLatest
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal enum class DesktopPage {
    HOME,
    SEARCH,
    LIBRARY,
    HISTORY,
    SOURCES,
    CATALOGS,
    CATALOG,
    DETAIL,
}

internal data class DesktopNavigationDestination(val page: DesktopPage, val label: String)

internal val desktopNavigationDestinations = listOf(
    DesktopNavigationDestination(DesktopPage.HOME, "Home"),
    DesktopNavigationDestination(DesktopPage.SEARCH, "Search"),
    DesktopNavigationDestination(DesktopPage.LIBRARY, "Library"),
    DesktopNavigationDestination(DesktopPage.HISTORY, "History"),
    DesktopNavigationDestination(DesktopPage.SOURCES, "Sources"),
)

internal fun catalogBrowseReturnDestination(origin: DesktopPage): DesktopPage =
    if (origin == DesktopPage.CATALOGS) DesktopPage.CATALOGS else DesktopPage.HOME

@Composable
internal fun DesktopSidebar(
    selectedPage: DesktopPage,
    onNavigate: (DesktopPage) -> Unit,
) {
    Surface(
        modifier = Modifier.width(224.dp).fillMaxHeight(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier.fillMaxHeight().padding(horizontal = 16.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "HIKARI",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 12.dp),
            )
            Text(
                text = "Windows desktop",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, bottom = 24.dp),
            )
            Text(
                text = "Menu",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, bottom = 4.dp),
            )
            desktopNavigationDestinations.forEach { destination ->
                NavigationItem(destination.label, selectedPage == destination.page) { onNavigate(destination.page) }
            }
            Spacer(Modifier.weight(1f))
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            Text(
                text = "Catalogs from Stremio add-ons",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun NavigationItem(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(9.dp)
    if (selected) {
        Button(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
            shape = shape,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
        ) {
            Text(label, modifier = Modifier.fillMaxWidth(), fontWeight = FontWeight.SemiBold)
        }
    } else {
        TextButton(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
            shape = shape,
        ) {
            Text(label, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
internal fun HomeScreen(
    sources: List<SourceState>,
    selectedProviderUrl: String?,
    selectedMediaFilter: HomeMediaFilter,
    catalogs: List<HomeCatalogSelection>,
    shelves: List<ShelfState>,
    loading: Boolean,
    onSelectProvider: (String?) -> Unit,
    onSelectMediaFilter: (HomeMediaFilter) -> Unit,
    onOpenTitle: (AddonTitle) -> Unit,
    onOpenCatalog: (HomeCatalogSelection) -> Unit,
    onOpenCatalogDirectory: () -> Unit,
    onOpenSources: () -> Unit,
    onRetry: () -> Unit,
) {
    if (sources.isEmpty()) {
        EmptyState(
            title = "No catalog sources yet",
            message = "Add a Stremio add-on URL to start loading catalogs.",
            actionLabel = "Manage sources",
            onAction = onOpenSources,
        )
        return
    }

    val selectedProvider = sources.firstOrNull { it.url == selectedProviderUrl }
    val visibleSources = if (selectedProviderUrl == null) sources else sources.filter { it.url == selectedProviderUrl }
    val sourceErrors = visibleSources.filter { it.error != null }
    val visibleManifests = visibleSources.mapNotNull { it.manifest }
    var providerMenuOpen by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Provider", style = MaterialTheme.typography.titleMedium)
                Box {
                    OutlinedButton(onClick = { providerMenuOpen = true }) {
                        Text(
                            selectedProvider?.manifest?.name ?: if (selectedProviderUrl == null) {
                                "All providers"
                            } else {
                                selectedProvider?.let { sourceHost(it.url) } ?: "Provider unavailable"
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    DropdownMenu(
                        expanded = providerMenuOpen,
                        onDismissRequest = { providerMenuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("All providers") },
                            onClick = {
                                onSelectProvider(null)
                                providerMenuOpen = false
                            },
                        )
                        sources.forEach { source ->
                            DropdownMenuItem(
                                text = {
                                    Text(source.manifest?.name ?: sourceHost(source.url), maxLines = 1)
                                },
                                onClick = {
                                    onSelectProvider(source.url)
                                    providerMenuOpen = false
                                },
                            )
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = onOpenCatalogDirectory) { Text("All catalogs (${catalogs.size})") }
                TextButton(onClick = onOpenSources) { Text("Manage sources") }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HomeMediaFilter.values().forEach { filter ->
                if (selectedMediaFilter == filter) {
                    Button(onClick = { onSelectMediaFilter(filter) }) { Text(filter.label) }
                } else {
                    OutlinedButton(onClick = { onSelectMediaFilter(filter) }) { Text(filter.label) }
                }
            }
        }

        when {
            loading -> ProgressMessage("Loading catalogs from the selected provider…")
            selectedProvider?.error != null -> MessagePanel(
                title = "Couldn't load sources",
                message = selectedProvider.error,
                actionLabel = "Try again",
                onAction = onRetry,
                isError = true,
            )
            shelves.isEmpty() -> {
                val selectedManifest = selectedProvider?.manifest
                val selectedStreamOnly = selectedManifest?.catalogs?.isEmpty() == true &&
                    selectedManifest.resources.any { it.name.equals("stream", ignoreCase = true) }
                val allStreamOnly = visibleManifests.isNotEmpty() && visibleManifests.all { manifest ->
                    manifest.catalogs.isEmpty() &&
                        manifest.resources.any { it.name.equals("stream", ignoreCase = true) }
                }
                val hasCatalogs = visibleManifests.any { it.catalogs.isNotEmpty() }
                if (visibleManifests.isEmpty() && sourceErrors.isNotEmpty()) {
                    MessagePanel(
                        title = "Couldn't load sources",
                        message = sourceErrors.first().error.orEmpty(),
                        actionLabel = "Try again",
                        onAction = onRetry,
                        isError = true,
                    )
                    return@Column
                }
                EmptyState(
                    title = when {
                        selectedStreamOnly || allStreamOnly -> "No catalogs available"
                        hasCatalogs && selectedMediaFilter != HomeMediaFilter.ALL ->
                            "No ${selectedMediaFilter.label.lowercase()} catalogs"
                        else -> "No catalogs yet"
                    },
                    message = when {
                        selectedStreamOnly ->
                            "This add-on provides streams, not catalogs. Add a catalog add-on to discover titles. When you play a title, Hikari will try streams from matching add-ons."
                        allStreamOnly ->
                            "Installed add-ons provide streams, not catalogs. Add a catalog add-on to discover titles. When you play a title, Hikari will try streams from matching providers."
                        hasCatalogs && selectedMediaFilter != HomeMediaFilter.ALL ->
                            "No ${selectedMediaFilter.label.lowercase()} catalogs from this provider. Try All or switch providers."
                        else ->
                            "Installed add-ons don't provide any catalogs yet. Add a catalog add-on to browse titles."
                    },
                    actionLabel = "Manage sources",
                    onAction = onOpenSources,
                )
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(26.dp),
            ) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Catalogs", style = MaterialTheme.typography.headlineSmall)
                            Text(
                                if (selectedProvider == null) {
                                    "Titles from all installed catalog add-ons"
                                } else {
                                    "Titles from ${selectedProvider.manifest?.name ?: sourceHost(selectedProvider.url)}"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (sourceErrors.isNotEmpty()) {
                        item {
                            MessagePanel(
                                title = "Some providers couldn't be loaded",
                                message = sourceErrors.first().error.orEmpty(),
                                isError = true,
                            )
                        }
                    }
                    items(
                        items = shelves,
                        key = { "${it.addonUrl}:${it.catalog.type}:${it.catalog.id}" },
                    ) { shelf ->
                        CatalogShelf(shelf, onOpenTitle, onRetry, onOpenCatalog)
                }
            }
        }
    }
}

@Composable
internal fun CatalogDirectoryScreen(
    catalogs: List<HomeCatalogSelection>,
    sources: List<SourceState>,
    loading: Boolean,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onOpenSources: () -> Unit,
    onOpenCatalog: (HomeCatalogSelection) -> Unit,
) {
    val sourceErrors = sources.filter { it.error != null }
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("Back") }
            if (!loading && catalogs.isNotEmpty()) {
                Text("${catalogs.size} catalogs", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (sourceErrors.isNotEmpty()) {
            MessagePanel(
                title = catalogDirectoryErrorTitle(sources.size, sourceErrors.size),
                message = sourceErrors.first().error.orEmpty(),
                actionLabel = "Try again",
                onAction = onRetry,
                isError = true,
            )
        }
        when (catalogDirectoryContentState(catalogs.size, loading, sourceErrors.isNotEmpty())) {
            CatalogDirectoryContentState.LOADING -> ProgressMessage("Loading catalog sources…")
            CatalogDirectoryContentState.SOURCE_ERROR -> Unit
            CatalogDirectoryContentState.EMPTY -> EmptyState(
                title = if (sources.isEmpty()) "No catalog sources yet" else "No catalogs yet",
                message = if (sources.isEmpty()) {
                    "Add a Stremio add-on to start browsing catalogs."
                } else {
                    "Installed sources haven't declared any catalogs to browse."
                },
                actionLabel = "Manage sources",
                onAction = onOpenSources,
            )
            CatalogDirectoryContentState.CONTENT -> LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 20.dp),
            ) {
                items(catalogs, key = { "${it.addonUrl}:${it.catalog.type}:${it.catalog.id}" }) { selection ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(selection.catalog.name, style = MaterialTheme.typography.titleMedium)
                                Text(selection.addonName, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    catalogMediaLabel(selection.catalog.type),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            OutlinedButton(onClick = { onOpenCatalog(selection) }) { Text("Open") }
                        }
                    }
                }
            }
        }
    }
}

private fun catalogMediaLabel(type: String): String = when (type.lowercase()) {
    "movie", "film" -> "Movie"
    "series", "tv" -> "Series"
    else -> type
}

@Composable
internal fun LibraryScreen(
    titles: List<AddonTitle>,
    loading: Boolean,
    error: String?,
    categories: List<LibraryCategory>,
    categoryAssignments: Map<SavedTitleIdentity, Set<String>>,
    categoryError: String?,
    onRetry: () -> Unit,
    onBrowseHome: () -> Unit,
    onOpenTitle: (AddonTitle) -> Unit,
    onRemoveTitle: (AddonTitle) -> Unit,
    onCreateCategory: (String) -> LibraryCategory,
    onRenameCategory: (String, String) -> Unit,
    onDeleteCategory: (String) -> Unit,
    onAssignCategories: (AddonTitle, Set<String>) -> Unit,
    onMoveCategory: (String, Int) -> Unit,
) {
    var selectedCategoryId by remember { mutableStateOf<String?>(null) }
    var categoryManagerOpen by remember { mutableStateOf(false) }
    var categoryTitle by remember { mutableStateOf<AddonTitle?>(null) }
    LaunchedEffect(categories) {
        if (selectedCategoryId != null && categories.none { it.id == selectedCategoryId }) {
            selectedCategoryId = null
        }
    }
    val visibleTitles = remember(titles, selectedCategoryId, categoryAssignments) {
        filterSavedTitlesByCategory(titles, selectedCategoryId, categoryAssignments)
    }

    when {
        loading -> ProgressMessage("Loading library…")
        error != null -> MessagePanel(
            title = "Couldn't load your library",
            message = error,
            actionLabel = "Try again",
            onAction = onRetry,
            isError = true,
        )
        titles.isEmpty() -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            EmptyState(
                title = "Your library is empty",
                message = "Titles you save from detail pages will appear here.",
                actionLabel = "Browse home",
                onAction = onBrowseHome,
            )
            TextButton(onClick = { categoryManagerOpen = true }) { Text("Manage categories") }
        }
        else -> Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (selectedCategoryId == null) "${titles.size} titles" else "${visibleTitles.size} of ${titles.size} titles",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { categoryManagerOpen = true }) { Text("Manage categories") }
            }
            if (categoryError != null) {
                MessagePanel(
                    title = "Couldn't update categories",
                    message = categoryError,
                    isError = true,
                )
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item(key = "library-category-all") {
                    FilterChip(
                        selected = selectedCategoryId == null,
                        onClick = { selectedCategoryId = null },
                        label = { Text("All") },
                    )
                }
                items(categories, key = LibraryCategory::id) { category ->
                    FilterChip(
                        selected = selectedCategoryId == category.id,
                        onClick = { selectedCategoryId = category.id },
                        label = { Text(category.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
            if (visibleTitles.isEmpty()) {
                val activeCategory = categories.firstOrNull { it.id == selectedCategoryId }
                EmptyState(
                    title = "No titles in this category",
                    message = activeCategory?.let { "Add titles to ${it.name} from a library card." }
                        ?: "Pick another category or show all titles.",
                    actionLabel = "Show all titles",
                    onAction = { selectedCategoryId = null },
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(152.dp),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(bottom = 20.dp),
                ) {
                    items(visibleTitles, key = { it.savedTitleIdentity() }) { title ->
                        val assignedNames = categories
                            .filter { it.id in categoryAssignments[title.savedTitleIdentity()].orEmpty() }
                            .joinToString(" · ") { it.name }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            TitleCard(title = title, modifier = Modifier.fillMaxWidth()) { onOpenTitle(title) }
                            if (assignedNames.isNotBlank()) {
                                Text(
                                    assignedNames,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 8.dp),
                                )
                            }
                            TextButton(onClick = { categoryTitle = title }) { Text("Set categories") }
                            TextButton(onClick = { onRemoveTitle(title) }) { Text("Remove from library") }
                        }
                    }
                }
            }
        }
    }

    categoryTitle?.let { title ->
        LibraryCategoryAssignmentDialog(
            title = title,
            categories = categories,
            selectedCategoryIds = categoryAssignments[title.savedTitleIdentity()].orEmpty(),
            onCreateCategory = onCreateCategory,
            onConfirm = { selected ->
                onAssignCategories(title, selected)
                categoryTitle = null
            },
            onDismiss = { categoryTitle = null },
        )
    }
    if (categoryManagerOpen) {
        LibraryCategoryManagerDialog(
            categories = categories,
            onCreateCategory = onCreateCategory,
            onRenameCategory = onRenameCategory,
            onDeleteCategory = onDeleteCategory,
            onMoveCategory = onMoveCategory,
            onDismiss = { categoryManagerOpen = false },
        )
    }
}

@Composable
internal fun WatchHistoryScreen(
    entries: List<WatchHistoryEntry>,
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onBrowseHome: () -> Unit,
    onOpenTitle: (AddonTitle) -> Unit,
    onPlayAgain: (WatchHistoryEntry) -> Unit,
    onRemove: (WatchHistoryEntry) -> Unit,
    onClear: () -> Unit,
) {
    var confirmClear by remember { mutableStateOf(false) }

    when {
        loading -> ProgressMessage("Loading history…")
        error != null -> MessagePanel(
            title = "Couldn't load history",
            message = error,
            actionLabel = "Try again",
            onAction = onRetry,
            isError = true,
        )
        entries.isEmpty() -> EmptyState(
            title = "No history yet",
            message = "Titles and episodes you open will appear here. Hikari can't read playback progress from external players.",
            actionLabel = "Browse home",
            onAction = onBrowseHome,
        )
        else -> Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (entries.size == 1) "1 launch" else "${entries.size} launches", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { confirmClear = true }) { Text("Clear all") }
            }
            Text(
                "Only successful launch times are recorded; playback progress in external apps stays unavailable.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 20.dp),
            ) {
                items(entries, key = WatchHistoryEntry::uniqueKey) { entry ->
                    WatchHistoryRow(
                        entry = entry,
                        onOpenTitle = { onOpenTitle(entry.title) },
                        onPlayAgain = { onPlayAgain(entry) },
                        onRemove = { onRemove(entry) },
                    )
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear all history?") },
            text = { Text("Every launch record on this device will be deleted.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClear()
                        confirmClear = false
                    },
                ) { Text("Clear all") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun WatchHistoryRow(
    entry: WatchHistoryEntry,
    onOpenTitle: () -> Unit,
    onPlayAgain: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArtworkPoster(
                url = posterArtworkUrl(entry.title.posterUrl, entry.title.backgroundUrl),
                title = entry.title.title,
                modifier = Modifier.width(72.dp).height(104.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    entry.title.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    entry.episode?.let { "S${it.season} · E${it.number} · ${it.title}" }
                        ?: typeLabel(entry.title.type),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "Opened ${historyTimestampLabel(entry.launchedAtMillis)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onPlayAgain) { Text("Play again") }
                    TextButton(onClick = onOpenTitle) { Text("Open details") }
                    TextButton(onClick = onRemove) { Text("Delete") }
                }
            }
        }
    }
}

private fun historyTimestampLabel(timestampMillis: Long): String = runCatching {
    Instant.ofEpochMilli(timestampMillis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH))
}.getOrDefault("unknown time")

@Composable
private fun LibraryCategoryAssignmentDialog(
    title: AddonTitle,
    categories: List<LibraryCategory>,
    selectedCategoryIds: Set<String>,
    onCreateCategory: (String) -> LibraryCategory,
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember(title.savedTitleIdentity(), selectedCategoryIds) {
        mutableStateOf(selectedCategoryIds)
    }
    var newName by remember { mutableStateOf("") }
    var inputError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Library categories") },
        text = {
            Column(
                modifier = Modifier.widthIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(title.title, style = MaterialTheme.typography.bodyMedium)
                if (categories.isEmpty()) {
                    Text("No categories yet. Create one below.")
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 240.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(categories, key = LibraryCategory::id) { category ->
                            val checked = category.id in selected
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .toggleable(
                                        value = checked,
                                        role = Role.Checkbox,
                                        onValueChange = {
                                            selected = if (checked) selected - category.id else selected + category.id
                                        },
                                    )
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = checked, onCheckedChange = null)
                                Text(category.name)
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = {
                            newName = it
                            inputError = null
                        },
                        singleLine = true,
                        label = { Text("New category") },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        enabled = newName.isNotBlank(),
                        onClick = {
                            val nameError = categoryNameError(newName, categories)
                            if (nameError != null) {
                                inputError = nameError
                            } else {
                                runCatching { onCreateCategory(newName.trim()) }
                                    .onSuccess { created ->
                                        selected = selected + created.id
                                        newName = ""
                                        inputError = null
                                    }
                                    .onFailure { inputError = "Couldn't create the category." }
                            }
                        },
                    ) {
                        Text("Add")
                    }
                }
                inputError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun LibraryCategoryManagerDialog(
    categories: List<LibraryCategory>,
    onCreateCategory: (String) -> LibraryCategory,
    onRenameCategory: (String, String) -> Unit,
    onDeleteCategory: (String) -> Unit,
    onMoveCategory: (String, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    var editingId by remember { mutableStateOf<String?>(null) }
    var editingName by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<LibraryCategory?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manage categories") },
        text = {
            Column(
                modifier = Modifier.widthIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Categories group titles together across your library.")
                LazyColumn(
                    modifier = Modifier.heightIn(max = 300.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(categories, key = LibraryCategory::id) { category ->
                        if (editingId == category.id) {
                            val nameError = categoryNameError(editingName, categories, excludingId = category.id)
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(
                                        value = editingName,
                                        onValueChange = {
                                            editingName = it
                                            message = null
                                        },
                                        singleLine = true,
                                        label = { Text("Category name") },
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(
                                        enabled = nameError == null,
                                        onClick = {
                                            runCatching { onRenameCategory(category.id, editingName.trim()) }
                                                .onSuccess {
                                                    editingId = null
                                                    message = null
                                                }
                                                .onFailure { message = "Couldn't update the category." }
                                        },
                                    ) { Text("Save") }
                                    TextButton(onClick = { editingId = null }) { Text("Cancel") }
                                }
                                if (nameError != null) Text(nameError, color = MaterialTheme.colorScheme.error)
                            }
                        } else {
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(category.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (category.builtIn) {
                                            Text(
                                                "Built-in",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    TextButton(
                                        enabled = categories.firstOrNull()?.id != category.id,
                                        onClick = { onMoveCategory(category.id, -1) },
                                    ) { Text("Move up") }
                                    TextButton(
                                        enabled = categories.lastOrNull()?.id != category.id,
                                        onClick = { onMoveCategory(category.id, 1) },
                                    ) { Text("Move down") }
                                    TextButton(
                                        onClick = {
                                            editingId = category.id
                                            editingName = category.name
                                            message = null
                                        },
                                    ) { Text("Rename") }
                                    TextButton(onClick = { pendingDelete = category }) { Text("Delete") }
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = {
                            newName = it
                            message = null
                        },
                        singleLine = true,
                        label = { Text("New category") },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        enabled = categoryNameError(newName, categories) == null,
                        onClick = {
                            runCatching { onCreateCategory(newName.trim()) }
                                .onSuccess {
                                    newName = ""
                                    message = null
                                }
                                .onFailure { message = "Couldn't create the category." }
                        },
                    ) { Text("Add") }
                }
                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )

    pendingDelete?.let { category ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete category ${category.name}?") },
            text = {
                Text("Saved titles stay in your library but leave this category.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        runCatching { onDeleteCategory(category.id) }
                            .onSuccess { message = null }
                            .onFailure { message = "Couldn't delete the category." }
                        pendingDelete = null
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

private fun categoryNameError(
    name: String,
    categories: List<LibraryCategory>,
    excludingId: String? = null,
): String? = when {
    name.isBlank() -> "Enter a category name."
    categories.any { it.id != excludingId && it.name.equals(name.trim(), ignoreCase = true) } ->
        "That category name is already used."
    else -> null
}

@Composable
private fun CatalogShelf(
    shelf: ShelfState,
    onOpenTitle: (AddonTitle) -> Unit,
    onRetry: () -> Unit,
    onOpenCatalog: (HomeCatalogSelection) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(shelf.catalog.name, style = MaterialTheme.typography.titleLarge)
                Text(
                    shelf.addonName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (shelf.loading) CircularProgressIndicator(modifier = Modifier.size(18.dp))
            TextButton(
                onClick = {
                    onOpenCatalog(HomeCatalogSelection(shelf.addonUrl, shelf.addonName, shelf.catalog))
                },
            ) { Text("See all") }
        }
        when {
            shelf.loading -> Text(
                "Loading titles…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            shelf.error != null -> MessagePanel(
                title = "Couldn't load the catalog",
                message = shelf.error,
                actionLabel = "Reload sources",
                onAction = onRetry,
                isError = true,
            )
            shelf.titles.isEmpty() -> Text(
                "No titles in this catalog yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(
                    items = shelf.titles,
                    key = { "${it.addonUrl}:${it.type}:${it.id}" },
                ) { title ->
                    TitleCard(
                        title = title,
                        modifier = Modifier.width(164.dp),
                        onClick = { onOpenTitle(title) },
                    )
                }
            }
        }
    }
}

private const val CATALOG_PREFETCH_THRESHOLD = 6

@Composable
internal fun CatalogBrowseScreen(
    state: CatalogBrowseState,
    onBack: () -> Unit,
    onLoadNextPage: () -> Unit,
    onSearchTextChange: (String) -> Unit,
    onSearch: () -> Unit,
    onOpenTitle: (AddonTitle) -> Unit,
) {
    val target = state.target
    if (target == null) {
        EmptyState(
            title = "Catalog unavailable",
            message = "Pick a catalog from Home to browse titles.",
            actionLabel = "Back to Home",
            onAction = onBack,
        )
        return
    }

    val gridState = rememberLazyGridState()
    LaunchedEffect(target, state.appliedSearchQuery) {
        gridState.scrollToItem(0)
    }
    LaunchedEffect(gridState, state.titles.size, state.loading, state.hasMore, state.error) {
        if (state.titles.isEmpty() || state.loading || !state.hasMore || state.error != null) {
            return@LaunchedEffect
        }
        snapshotFlow {
            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible to gridState.layoutInfo.totalItemsCount
        }.collectLatest { (lastVisible, totalItems) ->
            if (totalItems > 0 && lastVisible >= totalItems - CATALOG_PREFETCH_THRESHOLD) {
                onLoadNextPage()
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("Back") }
            if (state.canShowManualLoadAction()) {
                OutlinedButton(onClick = onLoadNextPage) { Text("Load more") }
            }
        }
        if (target.supportsSearch) {
            val requestedSearchQuery = state.searchText.trim().ifBlank { null }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = state.searchText,
                    onValueChange = onSearchTextChange,
                    modifier = Modifier.weight(1f),
                    label = { Text("Search this catalog") },
                    singleLine = true,
                    enabled = !state.loading,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { if (!state.loading) onSearch() }),
                )
                Button(
                    onClick = onSearch,
                    enabled = !state.loading && requestedSearchQuery != state.appliedSearchQuery,
                ) {
                    Text(if (requestedSearchQuery == null && state.appliedSearchQuery != null) "Show all" else "Search")
                }
            }
        } else {
            Text(
                "This add-on doesn't advertise search for this catalog.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when {
            state.loading && state.titles.isEmpty() -> ProgressMessage(
                if (state.appliedSearchQuery == null) "Loading catalog titles…" else "Searching this catalog…",
            )
            state.error != null && state.titles.isEmpty() -> MessagePanel(
                title = if (state.appliedSearchQuery == null) "Couldn't load the catalog" else "Catalog search failed",
                message = state.error,
                actionLabel = "Try again",
                onAction = onLoadNextPage,
                isError = true,
            )
            state.titles.isEmpty() && state.appliedSearchQuery != null -> EmptyState(
                title = "No results for this search",
                message = "Try different words, or clear the search field and choose Show all.",
            )
            state.titles.isEmpty() -> EmptyState(
                title = "This catalog has no titles yet",
                message = "The add-on didn't return any titles for this catalog.",
                actionLabel = "Back to Home",
                onAction = onBack,
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(164.dp),
                state = gridState,
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                items(
                    items = state.titles,
                    key = { "${it.addonUrl}:${it.type}:${it.id}" },
                ) { title ->
                    TitleCard(
                        title = title,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onOpenTitle(title) },
                    )
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    when {
                        state.loading -> Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp))
                            Text(
                                if (state.appliedSearchQuery == null) "  Loading more titles…" else "  Loading more results…",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        state.error != null -> MessagePanel(
                            title = if (state.appliedSearchQuery == null) {
                                "Couldn't load the next page"
                            } else {
                                "Couldn't load the next results page"
                            },
                            message = state.error,
                            actionLabel = "Try again",
                            onAction = onLoadNextPage,
                            isError = true,
                        )
                        state.hasMore -> Spacer(Modifier.height(8.dp))
                        else -> Text(
                            if (state.appliedSearchQuery == null) "You've reached the end of the catalog." else "You've reached the end of the search results.",
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun SearchScreen(
    query: String,
    onQueryChange: (String) -> Unit,
    loading: Boolean,
    error: String?,
    partialFailure: Boolean,
    results: List<AddonTitle>,
    recentSearches: List<String>,
    historyError: String?,
    hasSources: Boolean,
    onSearch: () -> Unit,
    onRunRecentSearch: (String) -> Unit,
    onRemoveRecentSearch: (String) -> Unit,
    onClearRecentSearches: () -> Unit,
    onOpenTitle: (AddonTitle) -> Unit,
    onOpenSources: () -> Unit,
    searchSources: List<SearchSourceOption>,
    selectedSearchSourceUrls: Set<String>,
    onSearchSourcesSelected: (Set<String>) -> Unit,
) {
    var searchFilters by remember { mutableStateOf(SearchFilters()) }
    val visibleResults = remember(results, searchFilters) {
        filterSearchResults(results, searchFilters)
    }
    var sourceMenuOpen by remember { mutableStateOf(false) }
    var draftSelectedSearchSourceUrls by remember { mutableStateOf(emptySet<String>()) }
    val selectedSources = searchSources.filter { it.url in selectedSearchSourceUrls }
    val sourceScopeLabel = when {
        selectedSearchSourceUrls.isEmpty() || selectedSources.isEmpty() -> "All sources"
        selectedSources.size == 1 -> selectedSources.single().name
        else -> "${selectedSources.size} sources"
    }
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                label = { Text("Movie or series title") },
                singleLine = true,
                enabled = !loading && hasSources,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (!loading && hasSources) onSearch() }),
            )
            Button(onClick = onSearch, enabled = !loading && hasSources && query.isNotBlank()) {
                if (loading) CircularProgressIndicator(modifier = Modifier.size(18.dp)) else Text("Search")
            }
        }

        if (searchSources.size > 1) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Sources",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box {
                    OutlinedButton(
                        onClick = {
                            draftSelectedSearchSourceUrls = selectedSearchSourceUrls
                            sourceMenuOpen = true
                        },
                        enabled = !loading,
                    ) {
                        Text(
                            sourceScopeLabel,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    DropdownMenu(
                        expanded = sourceMenuOpen,
                        onDismissRequest = { sourceMenuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = draftSelectedSearchSourceUrls.isEmpty(), onClick = null)
                                    Text("All sources")
                                }
                            },
                            onClick = {
                                draftSelectedSearchSourceUrls = emptySet()
                            },
                        )
                        searchSources.forEach { source ->
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(checked = source.url in draftSelectedSearchSourceUrls, onCheckedChange = null)
                                        Text(source.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                },
                                onClick = {
                                    draftSelectedSearchSourceUrls = toggleSearchSourceSelection(
                                        draftSelectedSearchSourceUrls,
                                        source.url,
                                    )
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Apply") },
                            onClick = {
                                onSearchSourcesSelected(draftSelectedSearchSourceUrls)
                                sourceMenuOpen = false
                            },
                            enabled = draftSelectedSearchSourceUrls != selectedSearchSourceUrls,
                        )
                    }
                }
            }
        }

        if (query.isBlank() && !loading) {
            RecentSearches(
                queries = recentSearches,
                error = historyError,
                onSelect = onRunRecentSearch,
                onRemove = onRemoveRecentSearch,
                onClear = onClearRecentSearches,
            )
        }

        if (!hasSources) {
            EmptyState(
                title = "Add sources before searching",
                message = "Search uses the catalogs of your installed Stremio add-ons.",
                actionLabel = "Manage sources",
                onAction = onOpenSources,
            )
        } else if (loading) {
            ProgressMessage("Searching catalogs…")
        } else {
            if (error != null) MessagePanel(
                title = if (partialFailure) "Search is incomplete" else "Search failed",
                message = error,
                isError = true,
            )
            if (results.isNotEmpty()) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Search results (${visibleResults.size})", style = MaterialTheme.typography.titleLarge)
                    SearchResultFilters(results, searchFilters) { searchFilters = it }
                    if (visibleResults.isEmpty()) {
                        EmptyState(
                            title = "No results match the filters",
                            message = "Adjust the filters or reset to see more results.",
                            actionLabel = "Reset filters",
                            onAction = { searchFilters = SearchFilters() },
                        )
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 168.dp),
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalArrangement = Arrangement.spacedBy(18.dp),
                            contentPadding = PaddingValues(bottom = 18.dp),
                        ) {
                            items(
                                items = visibleResults,
                                key = { "${it.addonUrl}:${it.type}:${it.id}" },
                            ) { title ->
                                TitleCard(
                                    title = title,
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = { onOpenTitle(title) },
                                )
                            }
                        }
                    }
                }
            } else when (searchResultsPresentation(query, results, error, partialFailure)) {
                SearchResultsPresentation.PARTIAL_FAILURE_WITHOUT_MATCH -> EmptyState(
                    title = "No matching results yet",
                    message = "Responding sources didn't find this title. Check the spelling, or select a single source to isolate the problem add-on.",
                )
                SearchResultsPresentation.SOURCE_FAILURE_WITHOUT_MATCH -> EmptyState(
                    title = "Search results unavailable",
                    message = "See the error above, check your connection or the source endpoint, then try again.",
                )
                SearchResultsPresentation.NO_MATCH -> EmptyState(
                    title = "No matching titles",
                    message = "Try different keywords, or check whether the source supports search.",
                )
                SearchResultsPresentation.QUERY_REQUIRED -> Text(
                    "Enter a title, then press Search or Enter.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SearchResultsPresentation.RESULTS -> Unit
            }
        }
    }
}

@Composable
private fun SearchResultFilters(
    results: List<AddonTitle>,
    filters: SearchFilters,
    onFiltersChange: (SearchFilters) -> Unit,
) {
    val genres = remember(results) { availableSearchGenres(results) }
    val years = remember(results) { availableSearchYears(results) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Type",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SearchTypeFilter.values().forEach { filter ->
                FilterChip(
                    selected = filters.type == filter,
                    onClick = { onFiltersChange(filters.copy(type = filter)) },
                    label = { Text(filter.label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MultiSelectSearchFilterChip(
                label = "Genre",
                options = genres,
                selected = filters.genres,
                onToggle = { genre ->
                    val next = if (genre in filters.genres) filters.genres - genre else filters.genres + genre
                    onFiltersChange(filters.copy(genres = next))
                },
            )
            MultiSelectSearchFilterChip(
                label = "Year",
                options = years,
                selected = filters.years,
                optionLabel = Int::toString,
                onToggle = { year ->
                    val next = if (year in filters.years) filters.years - year else filters.years + year
                    onFiltersChange(filters.copy(years = next))
                },
            )
            RatingSearchFilterChip(filters.minimumRating) { rating ->
                onFiltersChange(filters.copy(minimumRating = rating))
            }
            if (filters.isActive) {
                TextButton(onClick = { onFiltersChange(SearchFilters()) }) { Text("Reset filters") }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Include upcoming releases",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Switch(
                checked = filters.includeUpcoming,
                onCheckedChange = { onFiltersChange(filters.copy(includeUpcoming = it)) },
                modifier = Modifier.semantics { contentDescription = "Include upcoming releases" },
            )
        }
    }
}

@Composable
private fun <T> MultiSelectSearchFilterChip(
    label: String,
    options: List<T>,
    selected: Set<T>,
    optionLabel: (T) -> String = { it.toString() },
    onToggle: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selected.isNotEmpty(),
            onClick = { expanded = true },
            enabled = options.isNotEmpty(),
            label = { Text(if (selected.isEmpty()) label else "$label (${selected.size})") },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Checkbox(checked = option in selected, onCheckedChange = null)
                            Text(optionLabel(option))
                        }
                    },
                    onClick = { onToggle(option) },
                )
            }
        }
    }
}

@Composable
private fun RatingSearchFilterChip(
    minimumRating: Double?,
    onRatingSelected: (Double?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = minimumRating != null,
            onClick = { expanded = true },
            label = { Text(minimumRating?.let { "Rating ${it.toInt()}+" } ?: "Rating") },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("All ratings") },
                onClick = {
                    onRatingSelected(null)
                    expanded = false
                },
            )
            listOf(6.0, 7.0, 8.0, 9.0).forEach { rating ->
                DropdownMenuItem(
                    text = { Text("${rating.toInt()} and up") },
                    onClick = {
                        onRatingSelected(rating)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun RecentSearches(
    queries: List<String>,
    error: String?,
    onSelect: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
) {
    if (queries.isEmpty() && error == null) return

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (queries.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Recent searches", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onClear) { Text("Clear all") }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(queries, key = { it.lowercase() }) { query ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = { onSelect(query) },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text(
                                query,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 170.dp),
                            )
                        }
                        TextButton(
                            onClick = { onRemove(query) },
                            modifier = Modifier.semantics { contentDescription = "Remove search: $query" },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        ) {
                            Text("Delete")
                        }
                    }
                }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
internal fun SourceManagerScreen(
    sources: List<SourceState>,
    url: String,
    onUrlChange: (String) -> Unit,
    adding: Boolean,
    error: String?,
    onAdd: () -> Unit,
    onRetry: () -> Unit,
    onRemove: (String) -> Unit,
    onUse: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Add an add-on", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Paste a manifest URL or Stremio add-on address. Hikari checks the manifest before saving it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = onUrlChange,
                        modifier = Modifier.weight(1f),
                        label = { Text("Source URL") },
                        placeholder = { Text("https://example.addon/manifest.json") },
                        singleLine = true,
                        enabled = !adding,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (!adding) onAdd() }),
                    )
                    Button(onClick = onAdd, enabled = !adding && url.isNotBlank()) {
                        if (adding) CircularProgressIndicator(modifier = Modifier.size(18.dp)) else Text("Add")
                    }
                }
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Installed sources", style = MaterialTheme.typography.titleLarge)
            if (sources.any { it.error != null }) TextButton(onClick = onRetry) { Text("Recheck") }
        }

        if (sources.isEmpty()) {
            EmptyState(
                title = "No sources yet",
                message = "Sources you add will appear here.",
            )
        } else {
            sources.forEach { source ->
                SourceCard(
                    source = source,
                    onRetry = onRetry,
                    onRemove = { onRemove(source.url) },
                    onUse = { onUse(source.url) },
                )
            }
        }
    }
}

@Composable
private fun SourceCard(
    source: SourceState,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
    onUse: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        source.manifest?.name ?: sourceHost(source.url),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        source.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                when {
                    source.loading -> CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    source.error != null -> Text("Needs attention", color = MaterialTheme.colorScheme.error)
                    source.manifest != null -> Text("Ready", color = MaterialTheme.colorScheme.primary)
                }
            }
            source.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (source.manifest != null) OutlinedButton(onClick = onUse) { Text("Use") }
                if (source.error != null) OutlinedButton(onClick = onRetry) { Text("Try again") }
                TextButton(onClick = onRemove) { Text("Delete") }
            }
        }
    }
}

@Composable
internal fun DetailScreen(
    title: AddonTitle,
    episodes: List<AddonEpisode>,
    loading: Boolean,
    error: String?,
    playbackMessage: String?,
    isSaved: Boolean,
    libraryError: String?,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onToggleSaved: () -> Unit,
    onPlayEpisode: (AddonEpisode) -> Unit,
) {
    var selectedSeason by remember(title.addonUrl, title.id) { mutableStateOf<Int?>(null) }
    var seasonMenuOpen by remember(title.addonUrl, title.id) { mutableStateOf(false) }
    val episodeState = remember(episodes, selectedSeason) {
        detailEpisodeState(episodes, selectedSeason)
    }
    val seasonLabel = { season: Int -> if (season > 0) "Season $season" else "Specials" }
    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        ArtworkPoster(
            url = posterArtworkUrl(title.posterUrl, title.backgroundUrl),
            title = title.title,
            modifier = Modifier.width(252.dp).aspectRatio(0.68f).clip(RoundedCornerShape(16.dp)),
        )
        LazyColumn(
            modifier = Modifier.fillMaxHeight().weight(1f),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)) {
                    Text("Back")
                }
                Text(title.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    buildString {
                        append(typeLabel(title.type))
                        title.releaseInfo?.takeIf(String::isNotBlank)?.let { append(" · ").append(it) }
                        title.rating?.let { append(" · ★ ").append(it) }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (title.genres.isNotEmpty()) {
                    Text(
                        title.genres.joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onPlay) {
                        Text(if (title.type.equals("movie", ignoreCase = true)) "Choose a stream" else "Play the first episode")
                    }
                    OutlinedButton(onClick = onToggleSaved) {
                        Text(if (isSaved) "Remove from library" else "Save to library")
                    }
                }
                libraryError?.let { Text("Library: $it", color = MaterialTheme.colorScheme.error) }
                if (playbackMessage != null) {
                    Text(playbackMessage, color = MaterialTheme.colorScheme.primary)
                }
                if (loading) ProgressMessage("Loading title details…")
                if (error != null) MessagePanel(
                    title = "Couldn't load details",
                    message = error,
                    actionLabel = "Back to the catalog",
                    onAction = onBack,
                    isError = true,
                )
                title.description?.takeIf(String::isNotBlank)?.let { description ->
                    Text(description, style = MaterialTheme.typography.bodyLarge)
                }
            }
            if (!loading && episodes.isEmpty() && title.type.equals("series", ignoreCase = true)) {
                item {
                    Text(
                        "This add-on doesn't provide an episode list.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (episodes.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Episodes (${episodes.size})", style = MaterialTheme.typography.titleLarge)
                        if (episodeState.showsSeasonSelector) {
                            Box {
                                OutlinedButton(onClick = { seasonMenuOpen = true }) {
                                    Text(episodeState.activeSeason?.let(seasonLabel) ?: "Choose a season")
                                }
                                DropdownMenu(
                                    expanded = seasonMenuOpen,
                                    onDismissRequest = { seasonMenuOpen = false },
                                ) {
                                    episodeState.seasons.forEach { season ->
                                        DropdownMenuItem(
                                            text = { Text(seasonLabel(season)) },
                                            onClick = {
                                                selectedSeason = season
                                                seasonMenuOpen = false
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                items(items = episodeState.visibleEpisodes, key = { it.id }) { episode ->
                    EpisodeRow(episode = episode, onPlay = { onPlayEpisode(episode) })
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(episode: AddonEpisode, onPlay: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("S${episode.season} · E${episode.number}", color = MaterialTheme.colorScheme.primary)
                Text(episode.title, style = MaterialTheme.typography.titleMedium)
            }
            OutlinedButton(onClick = onPlay) { Text("Choose a stream") }
        }
    }
}

@Composable
private fun TitleCard(title: AddonTitle, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column {
            Box {
                ArtworkPoster(
                    url = posterArtworkUrl(title.posterUrl, title.backgroundUrl),
                    title = title.title,
                    modifier = Modifier.fillMaxWidth().aspectRatio(0.7f),
                )
                Surface(
                    color = Color(0xCC0B0E1A),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                ) {
                    Text(
                        typeLabel(title.type),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    title.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(
                        title.releaseInfo?.takeIf(String::isNotBlank),
                        title.rating?.let { "★ $it" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun StreamPickerDialog(
    title: String,
    streams: List<AddonStream>,
    loading: Boolean,
    error: String?,
    onChoose: (AddonStream) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.widthIn(min = 400.dp, max = 620.dp).fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(14.dp),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("Choose a stream", style = MaterialTheme.typography.titleLarge)
                Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                when {
                    loading -> ProgressMessage("Requesting streams from the add-on…")
                    error != null -> MessagePanel(
                        title = "Stream unavailable",
                        message = error,
                        isError = true,
                    )
                    streams.isEmpty() -> Text(
                        "The add-on didn't return any stream options.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> LazyColumn(
                        modifier = Modifier.height(280.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(items = streams, key = { it.url }) { stream ->
                            Button(
                                onClick = { onChoose(stream) },
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            ) {
                                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(stream.name.ifBlank { "Stream" }, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        if (stream.kind == StreamKind.DIRECT) {
                                            "Direct video · VLC/MPV when available, otherwise browser"
                                        } else {
                                            "Opens in the browser"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(
    title: String,
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (actionLabel != null && onAction != null) {
                Button(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

@Composable
private fun MessagePanel(
    title: String,
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    isError: Boolean = false,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isError) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

@Composable
private fun ProgressMessage(message: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp))
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun sourceHost(url: String): String =
    runCatching { URI(url).host }.getOrNull()?.takeIf(String::isNotBlank) ?: url

private fun typeLabel(type: String): String = when (type.lowercase()) {
    "movie" -> "Movie"
    "series" -> "Series"
    else -> type.replaceFirstChar { it.uppercase() }
}
