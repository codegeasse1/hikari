package com.hikari.desktop

import com.hikari.desktop.data.AddonCatalog

internal enum class HomeMediaFilter(val label: String) {
    ALL("All"),
    MOVIES("Movies"),
    SERIES("Series");

    fun includes(type: String): Boolean = when (this) {
        ALL -> true
        MOVIES -> type.equals("movie", ignoreCase = true) || type.equals("film", ignoreCase = true)
        SERIES -> type.equals("series", ignoreCase = true)
    }
}

internal data class HomeCatalogSelection(
    val addonUrl: String,
    val addonName: String,
    val catalog: AddonCatalog,
)

internal fun selectHomeCatalogs(
    sources: List<SourceState>,
    selectedProviderUrl: String?,
    mediaFilter: HomeMediaFilter,
): List<HomeCatalogSelection> = sources.asSequence()
    .filter { selectedProviderUrl == null || it.url == selectedProviderUrl }
    .mapNotNull { source -> source.manifest?.let { source to it } }
    .flatMap { (_, manifest) ->
        manifest.catalogs.asSequence()
            .filter { mediaFilter.includes(it.type) }
            .map { catalog -> HomeCatalogSelection(manifest.addonUrl, manifest.name, catalog) }
    }
    .toList()

internal enum class CatalogDirectoryContentState {
    LOADING,
    SOURCE_ERROR,
    EMPTY,
    CONTENT,
}

internal fun catalogDirectoryContentState(
    catalogCount: Int,
    loading: Boolean,
    hasSourceErrors: Boolean,
): CatalogDirectoryContentState = when {
    loading -> CatalogDirectoryContentState.LOADING
    catalogCount > 0 -> CatalogDirectoryContentState.CONTENT
    hasSourceErrors -> CatalogDirectoryContentState.SOURCE_ERROR
    else -> CatalogDirectoryContentState.EMPTY
}

internal fun catalogDirectoryErrorTitle(sourceCount: Int, failedSourceCount: Int): String {
    require(sourceCount > 0) { "sourceCount must be greater than zero" }
    require(failedSourceCount in 1..sourceCount) { "failedSourceCount must be between one and sourceCount" }
    return if (failedSourceCount == sourceCount) {
        "Couldn't load sources"
    } else {
        "Some sources couldn't be loaded"
    }
}

internal data class HomeCatalogInventory(
    val allCatalogs: List<HomeCatalogSelection>,
    val homeShelves: List<HomeCatalogSelection>,
)

internal fun homeCatalogInventory(
    sources: List<SourceState>,
    selectedProviderUrl: String?,
    mediaFilter: HomeMediaFilter,
    shelfLimit: Int = HOME_SHELF_PREVIEW_LIMIT,
): HomeCatalogInventory {
    require(shelfLimit >= 0) { "shelfLimit must not be negative" }
    val allCatalogs = selectHomeCatalogs(sources, selectedProviderUrl = null, mediaFilter = HomeMediaFilter.ALL)
    val homeShelves = selectHomeCatalogs(sources, selectedProviderUrl, mediaFilter).take(shelfLimit)
    return HomeCatalogInventory(allCatalogs, homeShelves)
}

internal fun HomeCatalogSelection.toCatalogReference(): CatalogReference = CatalogReference(
    provider = CatalogProviderKind.STREMIO,
    sourceId = addonUrl,
    sourceName = addonName,
    catalogId = catalog.id,
    mediaType = catalog.type,
    catalogName = catalog.name,
    supportsSearch = catalog.extraNames.any { it.equals("search", ignoreCase = true) },
)

internal const val HOME_SHELF_PREVIEW_LIMIT = 8
