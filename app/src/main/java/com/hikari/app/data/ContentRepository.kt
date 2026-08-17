package com.hikari.app.data

import com.hikari.app.providers.ProviderManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class ContentRepository(private val manager: ProviderManager) {

    /**
     * Loads Home rows. Catalogs inside a provider are fetched IN PARALLEL but
     * through a small semaphore so a slow network can't flood the IO pool with
     * hundreds of simultaneous requests (which froze the UI on weak devices).
     * Each catalog gets its own timeout so one dead catalog never eats the
     * whole provider's budget, and rows carry a stable unique key so addons
     * with several same-named catalogs (e.g. "Streaming Catalogs" → movies +
     * series both called "Netflix") can never crash the LazyColumn.
     */
    suspend fun homeRows(providerId: String? = null): List<CatalogRow> = withContext(Dispatchers.IO) {
        val active = manager.providers.value.filter {
            it.config.enabled && (providerId == null || it.config.id == providerId)
        }
        coroutineScope {
            active.map { p ->
                async {
                    runCatching {
                        withTimeoutOrNull(60_000) {
                            val catalogs = p.catalogs()
                                .distinctBy { it.type to it.id }
                                .take(8)
                            val gate = Semaphore(4)
                            coroutineScope {
                                catalogs.map { c ->
                                    async {
                                        gate.withPermit {
                                            val items = withTimeoutOrNull(25_000) {
                                                runCatching { p.getCatalog(c, 1) }.getOrDefault(emptyList())
                                            }.orEmpty().distinctBy { it.uniqueId }.take(24)
                                            if (items.isEmpty()) null
                                            else CatalogRow(
                                                providerName = p.config.name,
                                                title = c.name,
                                                items = items,
                                                key = "${p.config.id}|${c.type}|${c.id}",
                                            )
                                        }
                                    }
                                }.awaitAll().filterNotNull()
                            }
                        } ?: emptyList()
                    }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten()
        }
    }

    /** Searches across every enabled provider, or only the given subset.
     *  `null`/empty = all providers. */
    suspend fun searchAll(
        query: String,
        page: Int = 1,
        providerIds: Set<String>? = null,
    ): List<MediaItem> = withContext(Dispatchers.IO) {
        val active = manager.providers.value.filter {
            it.config.enabled && (providerIds.isNullOrEmpty() || it.config.id in providerIds)
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
