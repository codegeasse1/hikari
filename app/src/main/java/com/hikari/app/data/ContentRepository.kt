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
            // Every Stremio addon that declares streams is asked — like the real
            // client. The origin provider is always included too, so CS3
            // plugins / universal scrapers keep their own pipeline. No
            // idPrefixes filtering: titles come from many catalogs with many
            // id schemes, and the addons themselves only resolve ids they know,
            // so the worst case of asking is a fast empty response.
            val targets = all.filter { p ->
                p.config.id == item.providerId || p.config.type == ProviderType.STREMIO
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

    /** Enriches an item with the origin addon's full meta (backdrop, overview,
     *  genres, year). If that addon's meta is thin, the next addon that knows
     *  the title fills in the gaps — so a banner/detail never stay blank just
     *  because one catalog addon serves minimal metadata. */
    suspend fun metaFor(item: MediaItem): MediaItem = withContext(Dispatchers.IO) {
        var result = manager.byId(item.providerId)
            ?.let { withTimeoutOrNull(15_000) { runCatching { it.getMeta(item) }.getOrDefault(item) } }
            ?: item
        if (result.backdropUrl != null && result.overview != null) return@withContext result
        val others = manager.providers.value.filter {
            it.config.enabled && it.config.id != item.providerId && it.config.type == ProviderType.STREMIO
        }
        for (alt in others) {
            val r = withTimeoutOrNull(8_000) { runCatching { alt.getMeta(result) }.getOrDefault(result) }
                ?: continue
            if (result.backdropUrl == null && r.backdropUrl != null) {
                result = result.copy(backdropUrl = r.backdropUrl)
            }
            if (result.overview == null && r.overview != null) result = result.copy(overview = r.overview)
            if (result.genres.isEmpty() && r.genres.isNotEmpty()) result = result.copy(genres = r.genres)
            if (result.year == null && r.year != null) result = result.copy(year = r.year)
            if (result.backdropUrl != null && result.overview != null) break
        }
        result
    }

    /** Episodes from the origin addon, falling back to the first other addon
     *  that can list them (some catalog addons serve videos for series via a
     *  different addon, e.g. Cinemeta-backed ids). */
    suspend fun episodesFor(item: MediaItem): List<Episode>? = withContext(Dispatchers.IO) {
        if (item.type != MediaType.SERIES) return@withContext null
        val others = manager.providers.value.filter {
            it.config.enabled && it.config.id != item.providerId && it.config.type == ProviderType.STREMIO
        }
        val ordered = listOfNotNull(manager.byId(item.providerId)) + others
        for (p in ordered) {
            val eps = (withTimeoutOrNull(12_000) {
                runCatching { p.getEpisodes(item) }.getOrNull() ?: emptyList()
            }) ?: emptyList()
            if (eps.isNotEmpty()) return@withContext eps
        }
        null
    }
}
