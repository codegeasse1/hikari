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
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class ContentRepository(private val manager: ProviderManager) {

    // Nuvio providers share a 3-WebView pool, so only the first few to
    // acquire a view actually get to run within the search deadline. Order
    // the queue by trust: a provider the user has seen work (4KHDHub) must
    // grab a view before the ones that just burn the pool timing out on
    // Cloudflare challenges. Ordered by NAME because nuvio config ids are
    // hash-based ("nuvio|<hash>"); unknown providers follow in install order.
    private val NUVIO_PRIORITY = listOf(
        "4khdhub",
        "vidlink",
        "moviesdrive",
        "vixsrc",
    )

    // Search paging: scan a provider's search results page by page (no
    // arbitrary cap) until the site stops returning results, so the app's
    // count matches the website. The budgets keep a dead/hung provider from
    // stalling the whole search forever.
    private val MAX_SEARCH_PAGES = 30
    // Search streams page 1 to the UI immediately and keeps scanning in the
    // background, so these budgets only cap how long we wait for SLOW extra
    // pages. Trimmed hard (was 90s/240s/260s) so a single dead provider can't
    // make a search feel like it never finishes.
    private val SEARCH_PAGE_TIMEOUT_MS = 25_000L
    private val SEARCH_PROVIDER_BUDGET_MS = 90_000L
    private val SEARCH_TOTAL_BUDGET_MS = 100_000L

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
        val providerGate = Semaphore(5)
        val catalogGate = Semaphore(12)
        val rows = coroutineScope {
            active.map { p ->
                async {
                    cancellableCatching {
                        providerGate.withPermit {
                            // Tight budgets: a healthy catalog answers in a few
                            // seconds, so a 40s provider / 15s catalog ceiling
                            // keeps one dead host from stalling the whole home
                            // feed for two minutes while still tolerating slow
                            // provider manifest loads.
                            withTimeoutOrNull(40_000) {
                                val catalogs = p.catalogs()
                                    .distinctBy { it.type to it.id }
                                    .take(14)
                                coroutineScope {
                                    catalogs.map { c ->
                                        async {
                                            catalogGate.withPermit {
                                                val items = withTimeoutOrNull(15_000) {
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

    /**
     * Streaming Home feed: same work as [homeRows], but rows are handed to the
     * UI the moment EACH catalog lands instead of after every provider has
     * finished. With several installs, waiting for all of them used to leave
     * Home on a bare spinner for 20-25s; now the first fast provider paints in
     * a few seconds and the rest fill in underneath.
     *
     * Rows are keyed by (providerIndex, catalogIndex) and emitted in that
     * curated order, so late arrivals slot into place instead of jumping to the
     * end of the list. The concurrency gates/timeouts match [homeRows] so a
     * weak device still can't be flooded with requests.
     */
    fun homeRowsStreaming(providerId: String? = null): Flow<List<CatalogRow>> = flow {
        val active = manager.providers.value.filter {
            it.config.enabled && (providerId == null || it.config.id == providerId)
        }
        if (active.isEmpty()) {
            emit(emptyList())
            return@flow
        }
        val providerGate = Semaphore(5)
        val catalogGate = Semaphore(12)
        val placed = ConcurrentHashMap<Int, CatalogRow>()
        val version = AtomicInteger(0)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val jobs = active.mapIndexed { pi, p ->
                scope.async {
                    try {
                        providerGate.withPermit {
                            withTimeoutOrNull(40_000) {
                                val catalogs = p.catalogs()
                                    .distinctBy { it.type to it.id }
                                    .take(14)
                                coroutineScope {
                                    catalogs.mapIndexed { ci, c ->
                                        async {
                                            try {
                                                catalogGate.withPermit {
                                                    val items = withTimeoutOrNull(15_000) {
                                                        cancellableCatching { p.getCatalog(c, 1) }.getOrDefault(emptyList())
                                                    }.orEmpty().distinctBy { it.uniqueId }.take(40)
                                                    if (items.isNotEmpty()) {
                                                        var row = CatalogRow(
                                                            providerId = p.config.id,
                                                            providerName = p.config.name,
                                                            title = c.name,
                                                            items = items,
                                                            key = "${p.config.id}|${c.type}|${c.id}",
                                                            catalogId = c.id,
                                                            type = c.type,
                                                            rawType = c.rawType,
                                                        )
                                                        row = translateRows(listOf(row)).firstOrNull() ?: row
                                                        placed[pi * 100 + ci] = row
                                                        version.incrementAndGet()
                                                    }
                                                }
                                            } catch (e: kotlinx.coroutines.CancellationException) {
                                                throw e
                                            } catch (_: Throwable) {
                                            }
                                        }
                                    }
                                }.awaitAll()
                            }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                    }
                }
            }
            val started = System.currentTimeMillis()
            var lastVersion = -1
            var lastSnapshot: List<CatalogRow>? = null
            while (true) {
                if (version.get() != lastVersion) {
                    lastVersion = version.get()
                    val snapshot = placed.entries.sortedBy { it.key }.map { it.value }
                    lastSnapshot = snapshot
                    emit(snapshot)
                }
                if (jobs.all { it.isCompleted }) break
                if (System.currentTimeMillis() - started > 70_000L) break
                delay(100)
            }
            val finalSnapshot = placed.entries.sortedBy { it.key }.map { it.value }
            if (finalSnapshot != lastSnapshot) emit(finalSnapshot)
        } finally {
            scope.cancel()
        }
    }.flowOn(Dispatchers.IO)

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
                        // Page through the provider's search (page 1, 2, …) and
                        // fold each page into the aggregate AS IT LANDS, so the
                        // UI shows page 1 immediately and then grows page by page
                        // instead of freezing for the whole scan. Scanning
                        // continues until the provider returns an empty page (the
                        // site has no more results) — that's what lets the app's
                        // result count match the website instead of an arbitrary
                        // page cap.
                        var pageNo = page.coerceAtLeast(1)
                        val deadline = System.currentTimeMillis() + SEARCH_PROVIDER_BUDGET_MS
                        while (pageNo <= MAX_SEARCH_PAGES && System.currentTimeMillis() < deadline) {
                            val items = cancellableCatching {
                                // Generous per-page budget — heavy scrapers (e.g.
                                // MRDS) also download+decrypt every poster into a
                                // data: URI on the page, which is slow on a weak
                                // network.
                                withTimeoutOrNull(SEARCH_PAGE_TIMEOUT_MS) { p.search(query, pageNo) } ?: emptyList()
                            }.getOrDefault(emptyList())
                            val seen = aggregate.value
                            val fresh = items.filter { i -> seen.none { it.uniqueId == i.uniqueId } }
                            // Empty page = end of results; a page that adds no
                            // NEW items also means done (old-style plugins return
                            // every page at once).
                            if (fresh.isEmpty()) break
                            aggregate.update { (it + fresh).distinctBy { m -> m.uniqueId } }
                            pageNo++
                        }
                    }
                }
            }
            // Poll-and-emit the running aggregate so the UI shows each page of
            // every provider's results the moment they land.
            val started = System.currentTimeMillis()
            var lastEmitted: List<MediaItem>? = null
            while (true) {
                val allDone = jobs.all { it.isCompleted }
                val timedOut = System.currentTimeMillis() - started > SEARCH_TOTAL_BUDGET_MS
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
    suspend fun streamsFor(
        item: MediaItem,
        episode: Episode?,
        /** Called with the merged server list each time a provider adds new
         *  results, so callers can show servers progressively (Stremio-style)
         *  while the slower providers are still searching. */
        onProgress: (suspend (List<StreamSource>) -> Unit)? = null,
    ): List<StreamSource> =
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
            // source servers alongside the origin. Cheap pre-filter first,
            // then sorted so the historically-fast providers get first shot
            // at the parallel engine slots (NUVIO_PRIORITY order).
            val nuvioTargets = if (com.hikari.app.nuvio.TmdbResolver.isLikelyResolvable(item)) {
                all.filter { it.config.type == ProviderType.NUVIO }
                    .sortedBy { p ->
                        val idx = NUVIO_PRIORITY.indexOf(p.config.name.lowercase())
                        if (idx >= 0) idx else NUVIO_PRIORITY.size
                    }
            } else {
                emptyList()
            }
            val targets = primaryTargets + nuvioTargets
            if (targets.isEmpty()) return@withContext emptyList()

            // Fresh diagnostic state for this lookup.
            com.hikari.app.nuvio.NuvioScraper.lastOutcome.clear()
            com.hikari.app.nuvio.NuvioRuntime.resetFetchLog()
            com.hikari.app.nuvio.NuvioRuntime.resetRunTracking()

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            var result: List<StreamSource> = emptyList()
            try {
                val jobs = targets.mapIndexed { i, p ->
                    scope.async {
                        val isNuvio = p.config.type == ProviderType.NUVIO
                        val startedJob = System.currentTimeMillis()
                        try {
                            if (isNuvio) {
                                // Nuvio providers run in fresh QuickJS engines
                                // and share a small concurrency cap, so some
                                // queue behind the slots instead of running
                                // instantly. The runtime applies its own 45s
                                // per-provider timeout AFTER a slot is acquired
                                // — the queue wait must not eat a provider's
                                // budget. Bound these jobs by the overall
                                // deadline; the runtime's CALL budget bounds
                                // real work.
                                withTimeoutOrNull(45_000L) { p.getStreams(item, episode) }.orEmpty()
                            } else {
                                withTimeoutOrNull(45_000L) { p.getStreams(item, episode) }.orEmpty()
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            if (isNuvio) {
                                // Deadline cancelled us. Say whether we actually
                                // got an engine slot and ran.
                                val ran = com.hikari.app.nuvio.NuvioRuntime.providerStartedAt(p.config.id)
                                com.hikari.app.nuvio.NuvioScraper.lastOutcome[p.config.id] =
                                    if (ran != null)
                                        "✗ cut off after ${(System.currentTimeMillis() - startedJob) / 1000}s (still searching)"
                                    else
                                        "✗ search ended before it could run (still waiting for an engine slot)"
                            }
                            throw e
                        }
                    }
                }
                val started = System.currentTimeMillis()
                // Ceiling for the whole lookup. It only binds when providers are
                // slow/failing — normally they finish → allDone well before it.
                // 55s lets the trusted nuvio providers (priority order) pass
                // through the concurrency cap plus a few fallbacks. Results are
                // emitted progressively via onProgress, so the UI never sits on
                // an empty spinner while this runs.
                val deadline = started + 55_000L
                // Merge EVERY provider's sources (deduped by url/infoHash): a
                // fast Stremio/CS3 answer no longer cuts the wait short — every
                // installed nuvio provider gets its chance to add servers.
                val merged = LinkedHashMap<String, StreamSource>()
                fun merge(job: kotlinx.coroutines.Deferred<List<StreamSource>>) {
                    if (job.isCompleted) {
                        runCatching { job.getCompleted() }.getOrDefault(emptyList())
                            .forEach { s -> merged.putIfAbsent(s.infoHash ?: s.url, s) }
                    }
                }
                var lastEmitted = -1
                while (true) {
                    jobs.forEach { merge(it) }
                    // Progressive emission: hand over every newly-found server
                    // so the UI can show them while the rest keep searching.
                    if (onProgress != null && merged.size != lastEmitted) {
                        lastEmitted = merged.size
                        onProgress(merged.values.toList())
                    }
                    val allDone = jobs.all { it.isCompleted }
                    if (allDone) break
                    val now = System.currentTimeMillis()
                    // Wait for EVERY provider (like Stremio aggregating every
                    // addon): each installed nuvio provider is independent
                    // (it resolves from the TMDB id alone), so each one that
                    // finds streams adds selectable servers to the list. The
                    // old first-non-empty early-close cancelled every provider
                    // that hadn't answered within ~1.5s, which is why only one
                    // provider's servers ever showed up in the player.
                    if (now > deadline) break
                    kotlinx.coroutines.delay(80)
                }
                jobs.forEach { it.cancel() }
                result = merged.values.toList()
            } finally {
                scope.cancel()
            }
            // Same torrent/video surfaced by several addons = one entry.
            // Some scrapers/extensions also capture non-content scaffolding —
            // the classic being the SVG xmlns namespace (www.w3.org/2000/svg),
            // which must never become a playable source (it would open a
            // w3.org page in the web view instead of playing).
            var finalResult = result.filterNot { isGarbageUrl(it.url) }
                .distinctBy { it.infoHash ?: it.url }
            // App-wide universal last resort: every provider type funnels
            // through here, so when they ALL come up empty the bundled yt-dlp
            // extractor still gets one shot at the page (see the helper).
            if (finalResult.isEmpty()) {
                finalResult = ytdlpUniversalFallback(item, episode)
                    .filterNot { isGarbageUrl(it.url) }
            }
            finalResult
        }

    /** True for URLs that point at non-content scaffolding (e.g. the SVG
     *  xmlns namespace http://www.w3.org/2000/svg). Such links must never be
     *  handed to the player, which would otherwise open them in the web view. */
    private fun isGarbageUrl(url: String): Boolean {
        if (url.isBlank()) return false
        // The w3.org/2000/svg namespace is the classic junk a broken scraper
        // captures; catch it even when the link arrived scheme-less or
        // url-encoded, since java.net.URI can't parse those into a host.
        if (url.contains("w3.org/2000/svg", ignoreCase = true)) return true
        val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull() ?: return false
        return host == "w3.org" || host.endsWith(".w3.org")
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

    /** Bounded LRU caches so revisiting a detail page (back from the player,
     *  re-opening from history/search) is instant instead of re-hitting every
     *  provider. Keyed by uniqueId; guarded because several coroutines can
     *  touch them concurrently. */
    private val metaCache = object : LinkedHashMap<String, MediaItem>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MediaItem>?) = size > 64
    }
    private val episodeCache = object : LinkedHashMap<String, List<Episode>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Episode>>?) = size > 64
    }

    /** Enriches an item with the origin addon's full meta (backdrop, overview,
     *  genres, year). If that addon's meta is thin, the next addon that knows
     *  the title fills in the gaps — so a banner/detail never stay blank just
     *  because one catalog addon serves minimal metadata. */
    suspend fun metaFor(item: MediaItem): MediaItem = withContext(Dispatchers.IO) {
        synchronized(metaCache) { metaCache[item.uniqueId] }?.let { return@withContext it }
        var result = manager.byId(item.providerId)
            ?.let { withTimeoutOrNull(15_000) { cancellableCatching { it.getMeta(item) }.getOrDefault(item) } }
            ?: item
        if (result.backdropUrl != null && result.overview != null) {
            val t = translateItem(result)
            synchronized(metaCache) { metaCache[item.uniqueId] = t }
            return@withContext t
        }
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
        val translated = translateItem(result)
        synchronized(metaCache) { metaCache[item.uniqueId] = translated }
        translated
    }

    /** Episodes from the origin addon, falling back to the first other addon
     *  that can list them (some catalog addons serve videos for series via a
     *  different addon, e.g. Cinemeta-backed ids). Non-empty results are cached
     *  so re-opening a detail page doesn't repeat the whole lookup. */
    suspend fun episodesFor(item: MediaItem): List<Episode>? = withContext(Dispatchers.IO) {
        if (item.type == MediaType.UNKNOWN) return@withContext null
        synchronized(episodeCache) { episodeCache[item.uniqueId] }?.let { return@withContext it }
        val others = manager.providers.value.filter {
            it.config.enabled && it.config.id != item.providerId && it.config.type == ProviderType.STREMIO
        }
        // Probe the origin first for both movies and series (it owns the item's
        // ids), then the other addons when this is a series — the origin's own
        // Stremio meta can still reclassify a mislabelled row, so the origin is
        // always asked and its non-empty result wins.
        val ordered = listOfNotNull(manager.byId(item.providerId)) +
            (if (item.type == MediaType.SERIES) others else emptyList())
        for (p in ordered) {
            val eps = (withTimeoutOrNull(12_000) {
                cancellableCatching { p.getEpisodes(item) }.getOrNull() ?: emptyList()
            }) ?: emptyList()
            if (eps.isNotEmpty()) {
                val sorted = eps.sortedWith(compareBy({ it.season }, { it.number }))
                val translated = translateEpisodes(item.providerId, sorted)
                synchronized(episodeCache) { episodeCache[item.uniqueId] = translated }
                return@withContext translated
            }
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

/** Process-wide LRU of finished search results, keyed by query + the selected
 *  provider set. Items are stored already tokenized (tiny disk-cache tokens),
 *  so the cache is cheap — and it lets Search restore the grid instantly when
 *  the user returns from the player instead of re-running the whole multi-page
 *  search from scratch. Entries are replaced whenever a search completes, and
 *  evicted LRU-style to stay bounded. */
object SearchResultsCache {
    private const val MAX_ENTRIES = 16
    private val map = object : LinkedHashMap<String, List<MediaItem>>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, List<MediaItem>>?,
        ): Boolean = size > MAX_ENTRIES
    }

    fun get(key: String): List<MediaItem>? = synchronized(map) { map[key] }
    fun put(key: String, value: List<MediaItem>) {
        synchronized(map) { map[key] = value }
    }
}
