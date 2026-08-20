package com.hikari.app.data

import com.hikari.app.providers.ProviderManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class ContentRepository(private val manager: ProviderManager) {

    /** Like runCatching but re-throws CancellationException — a coroutine that
     *  gets cancelled (e.g. the user switches tabs while Home is loading every
     *  provider) must stop its work instead of swallowing the cancellation and
     *  keeping the network busy in the background. */
    private inline fun <T> cancellableCatching(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            Result.failure(t)
        }

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
        // GLOBAL gates shared by ALL providers (not per-provider): with dozens
        // of installed extensions, per-provider limits multiplied into hundreds
        // of concurrent network requests which saturated the IO pool and froze
        // the UI (ANR). 3 providers run their catalogs in parallel, and at most
        // 8 catalog fetches exist across the whole app at once.
        val providerGate = Semaphore(3)
        val catalogGate = Semaphore(8)
        val rows = coroutineScope {
            active.map { p ->
                async {
                    cancellableCatching {
                        providerGate.withPermit {
                            withTimeoutOrNull(120_000) {
                                val catalogs = p.catalogs()
                                    .distinctBy { it.type to it.id }
                                    .take(14)
                                coroutineScope {
                                    catalogs.map { c ->
                                        async {
                                            catalogGate.withPermit {
                                                val items = withTimeoutOrNull(60_000) {
                                                    cancellableCatching { p.getCatalog(c, 1) }.getOrDefault(emptyList())
                                                }.orEmpty().distinctBy { it.uniqueId }.take(40)
                                                if (items.isEmpty()) null
                                                else CatalogRow(
                                                    providerId = p.config.id,
                                                    providerName = p.config.name,
                                                    title = c.name,
                                                    items = items,
                                                    key = "${p.config.id}|${c.type}|${c.id}",
                                                    catalogId = c.id,
                                                    type = c.type,
                                                    rawType = c.rawType,
                                                )
                                            }
                                    }
                                }.awaitAll().filterNotNull()
                            }
                        } ?: emptyList()
                        }
                    }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten()
        }
        translateRows(rows)
    }

    /** Searches across every enabled provider, or only the given subset.
     *  `null`/empty = all providers.
     *
     *  Results STREAM IN as each provider finishes instead of waiting for ALL
     *  of them: a fast provider's hits appear immediately, and one dead/slow
     *  provider can no longer blank the whole screen or delay everything. The
     *  final emission is the full deduplicated aggregate. */
    fun searchStreaming(
        query: String,
        page: Int = 1,
        providerIds: Set<String>? = null,
    ): Flow<List<MediaItem>> = flow {
        val active = manager.providers.value.filter {
            it.config.enabled && (providerIds.isNullOrEmpty() || it.config.id in providerIds)
        }
        if (active.isEmpty()) {
            emit(emptyList())
            return@flow
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val aggregate = MutableStateFlow<List<MediaItem>>(emptyList())
            // Searching across MANY providers at once (search-all runs every
            // installed extension) would fire hundreds of requests at the same
            // time and starve the IO pool — same ANR class as Home loading.
            // At most 4 providers search concurrently; the rest queue up.
            val gate = Semaphore(4)
            val jobs = active.map { p ->
                scope.async {
                    gate.withPermit {
                        val items = cancellableCatching {
                            // Generous per-provider budget — heavy scrapers (e.g.
                            // MRDS) fetch several pages AND download/decrypt every
                            // poster into a data: URI before returning, which can
                            // take 1-3 minutes on a slow network. CloudStream has
                            // no such cap, so it shows those results while a short
                            // cap here used to blank them ("Nothing matched").
                            withTimeoutOrNull(240_000) { p.search(query, page) } ?: emptyList()
                        }.getOrDefault(emptyList())
                        aggregate.value = (aggregate.value + items).distinctBy { it.uniqueId }
                    }
                }
            }
            // Poll-and-emit the running aggregate so the UI shows each
            // provider's hits the moment they land.
            val started = System.currentTimeMillis()
            var lastEmitted: List<MediaItem>? = null
            while (true) {
                val allDone = jobs.all { it.isCompleted }
                val timedOut = System.currentTimeMillis() - started > 250_000
                if (allDone || timedOut) {
                    emit(translateItems(aggregate.value))
                    break
                }
                val snapshot = aggregate.value
                if (snapshot !== lastEmitted) {
                    emit(snapshot)
                    lastEmitted = snapshot
                }
                delay(120)
            }
        } finally {
            scope.cancel()
        }
    }

    /**
     * Fetches streams the way the real Stremio client does: every installed
     * Stremio addon is asked in parallel — a catalog-only addon contributes
     * nothing, while playback addons (Torrentio, Comet…) contribute their
     * sources. The origin provider is always included too, so CS3 plugins /
     * universal scrapers keep their own single-provider pipeline.
     *
     * Two speed rules (this is why CloudStream starts in seconds while a
     * multi-addon Stremio lookup used to take 25-45s):
     *  - a CS3/universal origin is queried ALONE — the other addons don't know
     *    its ids and only waste time timing out;
     *  - Stremio results use FIRST-NON-EMPTY-WINS: as soon as any addon
     *    returns sources, the rest are cancelled and playback starts. Only if
     *    every addon comes up empty do we wait for all of them.
     */
    suspend fun streamsFor(item: MediaItem, episode: Episode?): List<StreamSource> =
        withContext(Dispatchers.IO) {
            val all = manager.providers.value.filter { it.config.enabled }
            val origin = manager.byId(item.providerId)
            val targets = if (origin?.config?.type == ProviderType.STREMIO) {
                // Like the real client: ask every Stremio addon plus the origin.
                all.filter { p ->
                    p.config.id == item.providerId || p.config.type == ProviderType.STREMIO
                }
            } else {
                // CS3 plugin / universal scraper: only the origin can resolve
                // its own ids, so asking the Stremio addons just adds latency.
                listOfNotNull(origin)
            }
            if (targets.isEmpty()) return@withContext emptyList()

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val jobs = targets.map { p ->
                    scope.async {
                        cancellableCatching {
                            withTimeoutOrNull(45_000) { p.getStreams(item, episode) }.orEmpty()
                        }.getOrDefault(emptyList())
                    }
                }
                var result: List<StreamSource> = emptyList()
                val started = System.currentTimeMillis()
                while (true) {
                    for (j in jobs) {
                        if (j.isCompleted) {
                            val r = runCatching { j.getCompleted() }.getOrDefault(emptyList())
                            if (r.isNotEmpty()) {
                                result = r
                                break
                            }
                        }
                    }
                    if (result.isNotEmpty() || jobs.all { it.isCompleted }) break
                    if (System.currentTimeMillis() - started > 45_000) break
                    kotlinx.coroutines.delay(80)
                }
                jobs.forEach { it.cancel() }
                // Same torrent/video surfaced by several addons = one entry.
                result.distinctBy { it.infoHash ?: it.url }
            } finally {
                scope.cancel()
            }
        }

    /** Enriches an item with the origin addon's full meta (backdrop, overview,
     *  genres, year). If that addon's meta is thin, the next addon that knows
     *  the title fills in the gaps — so a banner/detail never stay blank just
     *  because one catalog addon serves minimal metadata. */
    suspend fun metaFor(item: MediaItem): MediaItem = withContext(Dispatchers.IO) {
        var result = manager.byId(item.providerId)
            ?.let { withTimeoutOrNull(15_000) { cancellableCatching { it.getMeta(item) }.getOrDefault(item) } }
            ?: item
        if (result.backdropUrl != null && result.overview != null) return@withContext translateItem(result)
        val others = manager.providers.value.filter {
            it.config.enabled && it.config.id != item.providerId && it.config.type == ProviderType.STREMIO
        }
        for (alt in others) {
            val r = withTimeoutOrNull(8_000) { cancellableCatching { alt.getMeta(result) }.getOrDefault(result) }
                ?: continue
            if (result.backdropUrl == null && r.backdropUrl != null) {
                result = result.copy(backdropUrl = r.backdropUrl)
            }
            if (result.overview == null && r.overview != null) result = result.copy(overview = r.overview)
            if (result.genres.isEmpty() && r.genres.isNotEmpty()) result = result.copy(genres = r.genres)
            if (result.year == null && r.year != null) result = result.copy(year = r.year)
            if (result.backdropUrl != null && result.overview != null) break
        }
        translateItem(result)
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
                cancellableCatching { p.getEpisodes(item) }.getOrNull() ?: emptyList()
            }) ?: emptyList()
            if (eps.isNotEmpty()) return@withContext translateEpisodes(item.providerId, eps)
        }
        null
    }

    // ---- Per-extension auto-translate (app content → English) ----
    // Only extensions with "always translate" on are touched; every other
    // provider's titles pass through untouched.

    private suspend fun translateRows(rows: List<CatalogRow>): List<CatalogRow> {
        val on = Translator.enabledIds()
        if (on.isEmpty()) return rows
        return rows.map { row ->
            if (row.providerId !in on) return@map row
            val newTitle = Translator.translate(row.title)
            val items = translateItems(row.items)
            if (newTitle == row.title && items === row.items) row
            else row.copy(title = newTitle, items = items)
        }
    }

    private suspend fun translateItems(items: List<MediaItem>): List<MediaItem> {
        val on = Translator.enabledIds()
        if (on.isEmpty()) return items
        val toTranslate = items.filter { it.providerId in on }
        if (toTranslate.isEmpty()) return items
        val translations = Translator.translateAll(toTranslate.map { it.title })
        var anyChanged = false
        val changed = toTranslate.mapIndexed { i, it ->
            val t = translations[i]
            if (t != it.title) {
                anyChanged = true
                it.copy(title = t)
            } else it
        }
        if (!anyChanged) return items
        val byId = changed.associateBy { it.uniqueId }
        return items.map { byId[it.uniqueId] ?: it }
    }

    private suspend fun translateItem(item: MediaItem): MediaItem {
        if (item.providerId !in Translator.enabledIds()) return item
        val title = Translator.translate(item.title)
        val overview = item.overview?.let { Translator.translate(it) }
        if (title == item.title && overview == item.overview) return item
        return item.copy(title = title, overview = overview)
    }

    private suspend fun translateEpisodes(providerId: String, eps: List<Episode>): List<Episode> {
        if (providerId !in Translator.enabledIds()) return eps
        val names = eps.map { it.name ?: "" }
        val translations = Translator.translateAll(names)
        var anyChanged = false
        val out = eps.mapIndexed { i, e ->
            val t = translations[i]
            if (e.name != null && t.isNotEmpty() && t != e.name) {
                anyChanged = true
                e.copy(name = t)
            } else e
        }
        return if (anyChanged) out else eps
    }
}
