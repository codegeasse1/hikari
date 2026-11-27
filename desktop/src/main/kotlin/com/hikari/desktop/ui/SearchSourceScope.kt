package com.hikari.desktop.ui

internal data class SearchSourceOption(
    val url: String,
    val name: String,
)

internal fun selectSearchSourceUrls(
    sourceUrls: List<String>,
    selectedSourceUrls: Set<String>,
): List<String> {
    if (selectedSourceUrls.isEmpty()) return sourceUrls

    val selected = sourceUrls.filter { it in selectedSourceUrls }
    return selected.ifEmpty { sourceUrls }
}

internal fun toggleSearchSourceSelection(
    selectedSourceUrls: Set<String>,
    sourceUrl: String,
): Set<String> = if (sourceUrl in selectedSourceUrls) {
    selectedSourceUrls - sourceUrl
} else {
    selectedSourceUrls + sourceUrl
}
