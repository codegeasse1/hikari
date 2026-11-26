package com.hikari.desktop.ui

import com.hikari.desktop.data.AddonTitle

internal enum class SearchTypeFilter(val label: String) {
    ALL("Semua"),
    MOVIES("Film"),
    SERIES("Serial"),
}

internal fun filterSearchResults(
    results: List<AddonTitle>,
    filter: SearchTypeFilter,
): List<AddonTitle> = when (filter) {
    SearchTypeFilter.ALL -> results
    SearchTypeFilter.MOVIES -> results.filter { it.type.equals("movie", ignoreCase = true) }
    SearchTypeFilter.SERIES -> results.filter { it.type.equals("series", ignoreCase = true) }
}
