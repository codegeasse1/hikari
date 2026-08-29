package com.hikari.app.data

import com.hikari.app.HikariApp
import com.hikari.app.cs3.Cs3MainApiProvider
import com.hikari.app.cs3.YtDlpResolver
import com.hikari.app.providers.HikariProviderAdapter
import com.hikari.app.providers.ProviderManager
import com.hikari.app.providers.StremioAddon
import com.hikari.app.providers.UniversalScraper
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
            val primaryTargets = if (origin?.config?.type == ProviderType.STREMIO) {
                // Like the real client: ask every Stremio addon plus the origin.
                all.filter { p ->
                    p.config.id == item.providerId || p.config.type == ProviderType.STREMIO
                }
            } else {
                // CS3 plugin / universal scraper: only the origin can resolve
                // its own ids, so asking the Stremio addons just adds latency.
                listOfNotNull(origin)
            }
            // Nuvio providers resolve purely from a TMDB id, so they can be
            // asked about ANY item we can map to TMDB — they add independent
            // source servers alongside the origin. Cheap pre-filter first.
            val nuvioTargets = if (com.hikari.app.nuvio.TmdbResolver.isLikelyResolvable(item)) {
                all.filter { it.config.type == ProviderType.NUVIO }
            } else {
                emptyList()
            }
            val targets = primaryTargets + nuvioTargets
            if (targets.isEmpty()) return@withContext emptyList()

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            var result: List<StreamSource> = emptyList()
            try {
                val jobs = targets.mapIndexed { i, p ->
                    scope.async {
                        cancellableCatching {
                            // Nuvio providers get a shorter budget — their
                            // pipeline is several sequential fetches per source.
                            val budget = if (i < primaryTargets.size) 45_000L else 25_000L
                            withTimeoutOrNull(budget) { p.getStreams(item, episode) }.orEmpty()
                        }.getOrDefault(emptyList())
                    }
                }
                val started = System.currentTimeMillis()
                val deadline = started + 30_000L
                // Merge EVERY provider's sources (deduped by url/infoHash). The
                // old first-non-empty-wins behaviour is kept for the primary
                // targets so a fast Stremio/CS3 answer still opens instantly;
                // nuvio results trickle in during a short grace window.
                val merged = LinkedHashMap<String, StreamSource>()
                fun merge(job: kotlinx.coroutines.Deferred<List<StreamSource>>) {
                    if (job.isCompleted) {
                        runCatching { job.getCompleted() }.getOrDefault(emptyList())
                            .forEach { s -> merged.putIfAbsent(s.infoHash ?: s.url, s) }
                    }
                }
                while (true) {
                    jobs.forEach { merge(it) }
                    val primaryDone = primaryTargets.isNotEmpty() &&
                        primaryTargets.indices.any { i ->
                            jobs[i].isCompleted && runCatching { jobs[i].getCompleted() }
                                .getOrDefault(emptyList()).isNotEmpty()
                        }
                    val nuvioDone = (primaryTargets.size until targets.size).any { i ->
                        jobs[i].isCompleted && runCatching { jobs[i].getCompleted() }
                            .getOrDefault(emptyList()).isNotEmpty()
                    }
                    val allDone = jobs.all { it.isCompleted }
                    if (allDone) break
                    val now = System.currentTimeMillis()
                    // Open early: a primary provider already answered (fast
                    // path), or a nuvio answer has waited its grace window.
                    if (merged.isNotEmpty() && (primaryDone || (nuvioDone && now - started > 1_500))) break
                    if (now > deadline) break
                    kotlinx.coroutines.delay(80)
                }
                jobs.forEach { it.cancel() }
                result = merged.values.toList()
            } finally {
                scope.cancel()
            }
            // Same torrent/video surfaced by several addons = one entry.
            var finalResult = result.distinctBy { it.infoHash ?: it.url }
            // App-wide universal last resort: every provider type funnels
            // through here, so when they ALL come up empty the bundled yt-dlp
            // extractor still gets one shot at the page (see the helper).
            if (finalResult.isEmpty()) finalResult = ytdlpUniversalFallback(item, episode)
            finalResult
        }

    /**
     * App-wide universal last resort: native .hiki providers, the .cs3 bridge,
     * universal scrapers and even URL-id Stremio addons all funnel through
     * [streamsFor], so when every one of them came up empty on a real page URL
     * the bundled yt-dlp extractor gets a shot at it - "no playable sources" is
     * never the final word just because a provider's own parser missed the
     * player. CS3 plugins are excluded: Cs3MainApiProvider already runs its own
     * yt-dlp pass (with richer per-plugin error text), and running it again here
     * would only double the wait. Gated behind the same "Universal extraction"
     * setting as that path.
     */
    private suspend fun ytdlpUniversalFallback(item: MediaItem, episode: Episode?): List<StreamSource> {
        val origin = manager.byId(item.providerId) ?: return emptyList()
        if (origin.config.type == ProviderType.CS3) return emptyList()
        val pageUrl = episode?.id ?: item.id
        if (!pageUrl.startsWith("http://") && !pageUrl.startsWith("https://")) return emptyList()
        val enabled = runCatching { HikariApp.instance.store.ytdlpEnabled() }.getOrDefault(true)
        if (!enabled) return emptyList()

        recordStreamMessage(origin, "Standard extractors found nothing - trying yt-dlp...")
        var timedOut = false
        val got = runCatching {
            withTimeoutOrNull(45_000) { YtDlpResolver.resolve(pageUrl) }
                ?: run { timedOut = true; emptyList() }
        }.getOrDefault(emptyList())
        if (got.isEmpty()) {
            val why = YtDlpResolver.initFailure
            val detail = YtDlpResolver.lastExtractError
            recordStreamMessage(
                origin,
                when {
                    why != null -> "Universal extractor (yt-dlp) unavailable: $why"
                    timedOut -> "yt-dlp timed out after 45s extracting this page."
                    detail != null -> "yt-dlp couldn't extract a playable stream from this page: $detail"
                    else -> "yt-dlp couldn't extract a playable stream from this page either."
                }
            )
        } else {
            recordStreamMessage(origin, null)
        }
        return got
    }

    /** Routes a provider's stream message into the right per-provider error map
     *  so the Detail screen's "no sources" panel can explain what happened. */
    private fun recordStreamMessage(origin: com.hikari.app.providers.ContentProvider, message: String?) {
        val id = origin.config.id
        val map = when (origin.config.type) {
            ProviderType.STREMIO -> StremioAddon.streamErrors
            ProviderType.CS3 -> Cs3MainApiProvider.streamErrors
            ProviderType.HIKARI -> HikariProviderAdapter.streamErrors
            ProviderType.UNIVERSAL -> UniversalScraper.streamErrors
            ProviderType.NUVIO -> com.hikari.app.nuvio.NuvioScraper.streamErrors
        }
        if (message == null) map.remove(id) else map[id] = message
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
        if (item.type != MediaType.SERIES && item.type != MediaType.MOVIE) return@withContext null
        val others = manager.providers.value.filter {
            it.config.enabled && it.config.id != item.providerId && it.config.type == ProviderType.STREMIO
        }
        val ordered = listOfNotNull(manager.byId(item.providerId)) +
            (if (item.type == MediaType.SERIES) others else emptyList())
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
