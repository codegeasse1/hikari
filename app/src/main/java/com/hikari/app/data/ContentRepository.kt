package com.hikari.app.data

import com.hikari.app.providers.ProviderManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class ContentRepository(private val manager: ProviderManager) {

    suspend fun homeRows(providerId: String? = null): List<CatalogRow> = withContext(Dispatchers.IO) {
        val active = manager.providers.value.filter {
            it.config.enabled && (providerId == null || it.config.id == providerId)
        }
        coroutineScope {
            active.map { p ->
                async {
                    runCatching {
                        withTimeoutOrNull(60_000) {
                            // Fetch each catalog IN PARALLEL. Sequential fetches
                            // let a slow addon with many catalogs (e.g. Streaming
                            // Catalogs) blow the whole 60s budget, so the provider
                            // looks "down" on Home while the same addon works in
                            // Stremio. Each catalog gets its own budget instead.
                            val catalogs = p.catalogs().take(8)
                            coroutineScope {
                                catalogs.map { c ->
                                    async {
                                        val items = runCatching { p.getCatalog(c, 1) }
                                            .getOrDefault(emptyList())
                                            .take(24)
                                        if (items.isEmpty()) null
                                        else CatalogRow(p.config.name, c.name, items)
                                    }
                                }.awaitAll().filterNotNull()
                            }
                        } ?: emptyList()
                    }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten()
        }
    }

    suspend fun searchAll(query: String, page: Int = 1, providerId: String? = null): List<MediaItem> = withContext(Dispatchers.IO) {
        val active = manager.providers.value.filter {
            it.config.enabled && (providerId == null || it.config.id == providerId)
        }
        coroutineScope {
            active.map { p ->
                async {
                    runCatching {
                        withTimeoutOrNull(45_000) { p.search(query, page) } ?: emptyList()
                    }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten().distinctBy { it.uniqueId }
        }
    }
}
