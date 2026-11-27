package com.hikari.desktop

import com.hikari.desktop.data.AddonCatalog
import com.hikari.desktop.data.AddonTitle
import com.hikari.desktop.net.STREMIO_PAGE_SIZE
import com.hikari.desktop.net.StremioClient

internal class StremioCatalogPageSource(
    private val client: StremioClient,
) : CatalogPageSource<AddonTitle> {
    override suspend fun loadPage(
        target: CatalogReference,
        page: Int,
        search: String?,
    ): CatalogPageResult<AddonTitle> {
        require(target.provider == CatalogProviderKind.STREMIO) {
            "StremioCatalogPageSource cannot load another provider kind."
        }
        require(page > 0) { "page must be greater than zero" }
        val normalizedSearch = search?.trim()?.takeIf(String::isNotEmpty)
        require(normalizedSearch == null || target.supportsSearch) {
            "This Stremio catalog does not advertise search support."
        }

        val catalog = AddonCatalog(
            type = target.mediaType,
            id = target.catalogId,
            name = target.catalogName,
        )
        val items = client.catalog(target.sourceId, catalog, page = page, search = normalizedSearch)
        return CatalogPageResult(items, hasMore = items.size >= STREMIO_PAGE_SIZE)
    }
}
