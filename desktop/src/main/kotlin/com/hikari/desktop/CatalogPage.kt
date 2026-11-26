package com.hikari.desktop

internal enum class CatalogProviderKind {
    STREMIO,
    TMDB,
}

internal data class CatalogIdentity(
    val provider: CatalogProviderKind,
    val sourceId: String,
    val mediaType: String,
    val catalogId: String,
)

internal data class CatalogReference(
    val provider: CatalogProviderKind,
    val sourceId: String,
    val sourceName: String,
    val catalogId: String,
    val mediaType: String,
    val catalogName: String,
) {
    val stableIdentity: CatalogIdentity
        get() = CatalogIdentity(provider, sourceId, mediaType, catalogId)
}

internal data class CatalogPageResult<T>(
    val items: List<T>,
    val hasMore: Boolean,
)

internal interface CatalogPageSource<T> {
    suspend fun loadPage(target: CatalogReference, page: Int): CatalogPageResult<T>
}
