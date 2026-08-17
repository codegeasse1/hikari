package com.hikari.app.data

import com.hikari.app.providers.ProviderManager
import com.hikari.app.providers.StremioAddon
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

    /**
     * Fetches streams for a title the way the real Stremio client does: every
     * installed Stremio addon is asked in parallel — a catalog-only addon
     * (Cinemeta, Streaming Catalogs) contributes nothing, while playback
     * addons (Torrentio, Comet, Novastream…) contribute their sources. The
     * origin provider is always included too, so CS3 plugins and universal
     * scrapers keep their own single-provider pipeline.
     */
    suspend fun streamsFor(item: MediaItem, episode: Episode?): List<StreamSource> =
        withContext(Dispatchers.IO) {
            val all = manager.providers.value.filter { it.config.enabled }
            // Every Stremio addon that recognizes this id, plus the origin
            // provider itself (CS3 plugins / universal scrapers keep their own
            // single-provider pipeline, and an origin addon always serves its
            // own titles).
            val targets = all.filter { p ->
                if (p.config.id == item.providerId) true
                else if (p.config.type != ProviderType.STREMIO) false
                else !(p is StremioAddon && !p.acceptsId(item.id))
            }
            coroutineScope {
                targets.map { p ->
                    async {
                        runCatching {
                            withTimeoutOrNull(45_000) { p.getStreams(item, episode) }.orEmpty()
                        }.getOrDefault(emptyList())
                    }
                }.awaitAll().flatten()
                    // Same torrent/video surfaced by several addons = one entry.
                    .distinctBy { it.infoHash ?: it.url }
            }
        }
}
