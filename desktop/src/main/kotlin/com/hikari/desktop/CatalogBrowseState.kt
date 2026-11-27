package com.hikari.desktop

import com.hikari.desktop.data.AddonTitle

internal data class CatalogBrowseState(
    val target: CatalogReference? = null,
    val titles: List<AddonTitle> = emptyList(),
    val nextPage: Int = 1,
    val loading: Boolean = false,
    val hasMore: Boolean = false,
    val error: String? = null,
    val searchText: String = "",
    val appliedSearchQuery: String? = null,
) {
    fun canLoadNextPage(): Boolean = target != null && hasMore && !loading

    fun canShowManualLoadAction(): Boolean =
        titles.isNotEmpty() && canLoadNextPage() && error == null

    fun acceptsResponse(
        responseTarget: CatalogReference,
        requestedPage: Int,
        requestedSearchQuery: String? = appliedSearchQuery,
    ): Boolean = loading && target == responseTarget && nextPage == requestedPage &&
        appliedSearchQuery == requestedSearchQuery

    fun applySearchText(): CatalogBrowseState {
        val normalizedQuery = searchText.trim().ifBlank { null }
        if (normalizedQuery == appliedSearchQuery) return this
        return copy(
            searchText = normalizedQuery.orEmpty(),
            appliedSearchQuery = normalizedQuery,
            titles = emptyList(),
            nextPage = 1,
            loading = false,
            hasMore = true,
            error = null,
        )
    }

    fun beginPageLoad(): CatalogBrowseState =
        if (canLoadNextPage()) copy(loading = true, error = null) else this

    fun finishPage(result: CatalogPageResult<AddonTitle>): CatalogBrowseState {
        return copy(
            titles = (titles + result.items).distinctBy { Triple(it.addonUrl, it.type, it.id) },
            nextPage = nextPage + 1,
            loading = false,
            hasMore = result.hasMore,
            error = null,
        )
    }

    fun failPage(message: String): CatalogBrowseState = copy(
        loading = false,
        error = message,
    )
}
