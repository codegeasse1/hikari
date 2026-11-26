package com.hikari.desktop

import com.hikari.desktop.data.AddonCatalog

internal enum class HomeMediaFilter(val label: String) {
    ALL("Semua"),
    MOVIES("Film"),
    SERIES("Serial");

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
