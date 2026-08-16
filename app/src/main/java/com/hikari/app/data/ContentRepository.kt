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
                        withTimeoutOrNull(20_000) {
                            p.catalogs().take(8).map { c ->
                                val items = runCatching {
                                    withTimeoutOrNull(15_000) { p.getCatalog(c, 1) }?.take(24) ?: emptyList()
                                }.getOrDefault(emptyList())
                                CatalogRow(p.config.name, c.name, items)
                            }
                        } ?: emptyList()
                    }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten().filter { it.items.isNotEmpty() }
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
                        withTimeoutOrNull(30_000) { p.search(query, page) } ?: emptyList()
                    }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten().distinctBy { it.uniqueId }
        }
    }
}
