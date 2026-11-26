package com.hikari.desktop.ui

internal data class SearchSourceOption(
    val url: String,
    val name: String,
)

internal fun selectSearchSourceUrls(
    sourceUrls: List<String>,
    selectedSourceUrl: String?,
): List<String> {
    if (selectedSourceUrl == null || selectedSourceUrl !in sourceUrls) {
        return sourceUrls
    }
    return listOf(selectedSourceUrl)
}
