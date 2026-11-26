package com.hikari.desktop

import com.hikari.desktop.data.AddonCatalog
import com.hikari.desktop.data.AddonTitle
import com.hikari.desktop.net.STREMIO_PAGE_SIZE

internal data class CatalogBrowseTarget(
    val addonUrl: String,
    val addonName: String,
    val catalog: AddonCatalog,
)

internal data class CatalogBrowseState(
    val target: CatalogBrowseTarget? = null,
    val titles: List<AddonTitle> = emptyList(),
    val nextPage: Int = 1,
    val loading: Boolean = false,
    val hasMore: Boolean = false,
    val error: String? = null,
) {
    fun canLoadNextPage(): Boolean = target != null && hasMore && !loading

    fun canShowManualLoadAction(): Boolean =
        titles.isNotEmpty() && canLoadNextPage() && error == null

    fun beginPageLoad(): CatalogBrowseState =
        if (canLoadNextPage()) copy(loading = true, error = null) else this

    fun finishPage(pageTitles: List<AddonTitle>, pageSize: Int = STREMIO_PAGE_SIZE): CatalogBrowseState {
        require(pageSize > 0) { "pageSize must be greater than zero" }
        return copy(
            titles = (titles + pageTitles).distinctBy { "${it.addonUrl}:${it.type}:${it.id}" },
            nextPage = nextPage + 1,
            loading = false,
            hasMore = pageTitles.size >= pageSize,
            error = null,
        )
    }

    fun failPage(message: String): CatalogBrowseState = copy(
        loading = false,
        error = message,
    )
}
