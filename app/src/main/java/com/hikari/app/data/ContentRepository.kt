package com.hikari.app.data

import com.hikari.app.providers.ProviderManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

class ContentRepository(private val manager: ProviderManager) {

    suspend fun homeRows(): List<CatalogRow> = withContext(Dispatchers.IO) {
        val active = manager.providers.value.filter { it.config.enabled }
        coroutineScope {
            active.map { p ->
                async {
                    runCatching {
                        p.catalogs().take(8).map { c ->
                            val items = runCatching { p.getCatalog(c, 1).take(24) }
                                .getOrDefault(emptyList())
                            CatalogRow(p.config.name, c.name, items)
                        }
                    }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten().filter { it.items.isNotEmpty() }
        }
    }

    suspend fun searchAll(query: String, page: Int = 1): List<MediaItem> = withContext(Dispatchers.IO) {
        val active = manager.providers.value.filter { it.config.enabled }
        coroutineScope {
            active.map { p ->
                async {
                    runCatching { p.search(query, page) }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten().distinctBy { it.uniqueId }
        }
    }
}
