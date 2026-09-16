package com.hikari.app.data

import com.hikari.app.HikariApp
import com.hikari.app.cs3.Cs3MainApiProvider
import com.hikari.app.cs3.YtDlpResolver
import com.hikari.app.net.CloudflareVerifier
import com.hikari.app.net.NetTuning
import com.hikari.app.nuvio.EpisodeTitles
import com.hikari.app.providers.ContentProvider
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

    /**
     * Live status of the cross-extension pass, for the player's "Select server"
     * sheet: [crossRunning] holds `provider id -> repo name` for every extension
     * still being searched, and [crossVerdict] holds
     * `provider id -> "Repo name — why it found nothing"` for the ones that came
     * back empty. The sheet shows this above the panel, so "my other repo's
     * servers never showed up" has an answer on screen (still searching / no
     * matching title / the plugin failed to load / no playable links) instead of
     * being indistinguishable from "that repo is simply still loading".
     * [crossStatusVersion] bumps on every change so the UI can poll cheaply.
     */
    companion object {
        val crossRunning = ConcurrentHashMap<String, String>()
        val crossVerdict = ConcurrentHashMap<String, String>()

        /**
         * `provider id -> engine label` for every extension the CURRENT pass has
         * asked, `provider id -> engine label` for the ones that produced
         * servers, and `engine label -> how many repos of that engine are
         * installed`. With [crossRunning]/[crossVerdict] these let the chooser's
         * hint report PROGRESS ("asked 48 of 96 · 4 with servers · 12 still
         * searching") instead of one repo's verdict on its own — the old line
         * was a " · "-joined list of failures, and the two-line hint cut it off
         * after the first repo, which read as if the whole search had stopped
         * there while the rest were still running.
         */
        val crossAsked = ConcurrentHashMap<String, String>()
        val crossFound = ConcurrentHashMap<String, String>()
        val crossInstalled = ConcurrentHashMap<String, Int>()

        /** A single pass-level note for the chooser's hint — currently the host
         *  that needed a Cloudflare verification (see CloudflareVerifier). Kept
         *  separate from the per-repo verdicts on purpose: the block belongs to
         *  the SITE, not to one repo's catalog, and attributing it to whichever
         *  repo happened to be searching would repeat the mistake that made a
         *  Cloudflare block read as "this repo has no such title". */
        @Volatile
        var crossNote: String? = null

        @Volatile
        var crossStatusVersion: Long = 0L
            private set

        fun bumpCrossStatus() {
            crossStatusVersion++
        }

        /** Classifies one repo's verdict into a short bucket name, so the
         *  chooser's hint and the end-of-pass log line can say what happened
         *  ACROSS the whole pass ("40 could not load, 180 no such title")
         *  instead of listing repos one at a time. "No server came back" has
         *  very different fixes depending on which bucket dominates: nothing
         *  found by any repo is a matcher/catalog story, while a pass where most
         *  repos could not load is a broken-extension story. */
        fun crossReasonBucket(verdict: String): String = when {
            verdict.contains("never reached") -> "not reached (pass ended)"
            verdict.contains("still searching when the pass ended") -> "unfinished"
            verdict.contains("cloudflare", ignoreCase = true) -> "cloudflare check"
            verdict.contains("failed to load") ||
                verdict.contains("file is missing") ||
                verdict.contains("did not register") ||
                verdict.contains("Reinstall") -> "could not load"
            verdict.contains("extractor-only") -> "no search (extractor)"
            verdict.contains("timed out") -> "timed out"
            verdict.contains("search failed") -> "search error"
            verdict.contains("no matching title") ||
                verdict.contains("search result(s)") -> "no such title"
            verdict.contains("has the title") -> "title found, no links"
            else -> "other"
        }
    }

    /** Messages THIS app wrote into a provider's error map (see
     *  [recordStreamMessage]), so [crossExtensionSearch] can tell our own
     *  "no matching title" note apart from the provider's own words. */
    private val selfNote = ConcurrentHashMap<String, String>()

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
    private val SEARCH_PAGE_TIMEOUT_MS get() = NetTuning.timeout(25_000L)
    private val SEARCH_PROVIDER_BUDGET_MS get() = NetTuning.timeout(90_000L)
    private val SEARCH_TOTAL_BUDGET_MS get() = NetTuning.timeout(100_000L)

    // ---- Cross-extension fallback ----
    // The SAME title is asked of the other installed extensions (search → best
    // match → same episode → their servers) and whatever they find is merged
    // into the same source list as the origin's own servers. CloudStream/.hiki/
    // universal extensions each keep their own site-specific ids, so the title
    // is the only thing two extensions share — this works by title, not by id.
    //
    // This pass runs on EVERY lookup, not only when the origin comes up empty.
    // A repo handing back links says nothing about whether those links actually
    // play (expired signed URLs, region locks, an extractor this build can't
    // run, a provider that needs its own WebView flow), and "this repo can't
    // play it" is not evidence that no repo can. The origin's own servers still
    // land FIRST in the list (this pass waits out [CROSS_EXT_GRACE_MS] before
    // starting), and every later server is streamed to an already-open player
    // as it arrives, so playback is never delayed — the extra servers are for
    // automatic failover and for the "Select server" list.

    /** How long the origin (plus the nuvio/Stremio passes) gets a head start
     *  before the other extensions are asked, so a working repo's servers are
     *  still the first ones the player sees. */
    private val CROSS_EXT_GRACE_MS = 2_500L

    /** Head start for the provider the title was opened FROM.
     *
     *  With ~60 installed extensions, starting every search at t=0 saturates the
     *  phone's network and CPU, and the origin's own servers — the ones the user
     *  expects first ("on MovieBox, play MovieBox"), and the ones a "choose a
     *  server" sheet is waiting for before it can show anything — were landing
     *  tens of seconds late, behind the other engines. Non-origin PRIMARY
     *  targets (the nuvio engines) wait [ORIGIN_HEAD_START_MS]; the origin's own
     *  engine family (the other CloudStream/… repos, which start beside it) waits
     *  [SAME_ENGINE_HEAD_START_MS]. Their servers still stream in right after,
     *  so this only reorders who answers first, never removes anyone. */
    private val ORIGIN_HEAD_START_MS = 1_200L
    private val SAME_ENGINE_HEAD_START_MS = 2_000L

    /** Ceiling on how long the other repos are held back while the provider the
     *  title was opened from is still working (see [awaitOriginHeadStart]). The
     *  point is that the origin gets the engines and the network to itself for
     *  as long as it genuinely needs, without a slow/failing origin stalling
     *  the whole list: whichever comes first — the origin finishing, or this
     *  cap — releases the rest. */
    private val ORIGIN_SETTLE_MAX_MS = 8_000L

    /** Total wall-clock budget for the whole cross-extension pass, measured
     *  from when it starts. Comfortably under the player's live-wait timeout so
     *  servers found here still reach a player that is already open and
     *  waiting. */
    /** How long the cross-extension pass may keep working after it starts.
     *  Extraction is the slow half (a site-specific parse per extension) and
     *  each target can legitimately take most of its 40s budget, so a short
     *  ceiling meant the extensions late in the list never got their turn —
     *  which is how servers from a repo the user KNEW had them (MovieBox,
     *  4KHDHub's mirrors, …) stayed missing from the list. Results stream to the
     *  player as they land, so a longer tail costs nothing at play time. */
    private val CROSS_EXT_BUDGET_MS get() = NetTuning.timeout(150_000L)

    /** Ceiling for PHASE 1 of the pass — asking every installed extension for
     *  the title. The phase ends the moment the last extension has answered, so
     *  this only binds when a long tail of repos is slow or dead. Everything
     *  else (the matched extension's meta, its episode list, extraction) runs in
     *  PHASE 2 deliberately: a repo that MATCHED used to keep holding a search
     *  slot while it fetched its episode list, so with the .hiki family alone at
     *  180+ repos and 18 search slots the repos at the back of the queue were
     *  never asked at all — and the pass still reported "all done, none with
     *  servers", which is exactly how a repo the user KNOWS carries the title
     *  went missing. */
    private val CROSS_EXT_SEARCH_PHASE_MS get() = NetTuning.timeout(60_000L)

    /** How many searches may still be pending when phase 2 (extraction) is
     *  allowed to start anyway. Searching is cheap next to extracting, so once
     *  only this many are left the first servers may start landing while the
     *  last few searches finish. */
    private val CROSS_EXT_SEARCH_TAIL = 12

    // 20s for search/episodes: a CloudStream/native plugin's first call has to
    // spin up its QuickJS runtime (and, for a .hiki, load a whole dex archive —
    // the loads serialise, so the last repo in a long queue starts late) plus
    // its own HTTP session. The old 10s cap timed that cold start out and the
    // pass then reported it as "this repo has no matching title", i.e. exactly
    // the case where a repo the user knows carries the show contributed
    // nothing. A search that still times out is retried once (see
    // [crossExtensionSearch]) and, if it fails again, is now reported as a
    // TIMEOUT rather than as "no matching title".
    private val CROSS_EXT_SEARCH_TIMEOUT_MS get() = NetTuning.timeout(20_000L)
    private val CROSS_EXT_EPISODES_TIMEOUT_MS get() = NetTuning.timeout(20_000L)
    private val CROSS_EXT_META_TIMEOUT_MS get() = NetTuning.timeout(15_000L)
    private val CROSS_EXT_STREAMS_TIMEOUT_MS get() = NetTuning.timeout(45_000L)

    /** Searching a title is cheap; extracting links is not, so they get their
     *  own caps. The wider one lets every installed extension be SEARCHED in
     *  parallel (a title search across dozens of repos then still finishes in a
     *  few seconds), while the narrow one keeps only a handful of extractors
     *  running at once — so one slow extractor can never stop the other
     *  extensions' searches from even being attempted. */
    private val CROSS_EXT_SEARCH_CONCURRENCY = 28
    /** How many extensions may extract at the same time. Six was low enough
     *  that, on a phone with a dozen installed repos, most targets queued behind
     *  the budget and never ran at all; with ~50 installed repos the searches
     *  alone used to take the better part of a minute, which is why the one
     *  repo that DOES carry the title (MovieBox) only landed its servers after
     *  playback had already started. */
    private val CROSS_EXT_EXTRACT_CONCURRENCY = 12
    /** How many matched extensions may fetch their meta / episode list at once.
     *  A SEPARATE cap from the search semaphore: fetching one repo's episode
     *  list must never take a slot that another repo still needs just to be
     *  SEARCHED (that sharing is what starved the tail of the queue). */
    private val CROSS_EXT_DETAIL_CONCURRENCY = 16
    private val CROSS_EXT_SEARCH_SEMAPHORE = Semaphore(CROSS_EXT_SEARCH_CONCURRENCY)
    private val CROSS_EXT_EXTRACT_SEMAPHORE = Semaphore(CROSS_EXT_EXTRACT_CONCURRENCY)
    private val CROSS_EXT_DETAIL_SEMAPHORE = Semaphore(CROSS_EXT_DETAIL_CONCURRENCY)

    /** Effectively "every installed extension": the whole point of the pass is
     *  to find the repo that CAN play the title, so nothing is skipped up
     *  front. The budget above — not this cap — bounds the work.
     *
     *  This was 64, and that WAS the bug behind "no CloudStream server shows
     *  up": the list is ordered origin-family-first, and the native .hiki family
     *  alone is 64+ repos, so a plain `take(64)` handed every slot to it and an
     *  installed CloudStream repo was never asked at all — its servers could not
     *  appear no matter how long you waited. A shared log proved it exactly:
     *  64 distinct .hiki repos searched and ZERO CloudStream. The cap is now
     *  high enough that every installed repo is in the pass; the concurrency
     *  semaphore and the budget keep the phone from being hammered. */
    private val CROSS_EXT_MAX_TARGETS = 1_024

    /** Minimum title-match score (see [titleScore]) before a search hit is
     *  trusted as "the same title on that extension". */
    private val CROSS_EXT_MIN_MATCH = 40

    /** Score a hit from a shortened title has to reach. A variant only covers
     *  part of the title, so its token overlap is naturally lower — but it must
     *  still be clearly the same show, not just any repo entry. */
    private val CROSS_EXT_MATCH_VARIANT = 55

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

    /** One provider's stream lookup, with the slow-connection retry: while
     *  [NetTuning] slow mode is on, a provider that times out or throws is
     *  asked again (up to [NetTuning.attempts]) instead of being written off
     *  for the rest of the search — the usual cause of "No playable sources
     *  found" on mobile data, where a single late response used to end it. */
    private suspend fun fetchStreams(
        p: ContentProvider,
        item: MediaItem,
        episode: Episode?,
    ): List<StreamSource> {
        val timeoutMs = NetTuning.timeout(45_000L)
        val maxAttempts = NetTuning.attempts()
        var attempt = 0
        while (true) {
            val got = cancellableCatching {
                withTimeoutOrNull(timeoutMs) { p.getStreams(item, episode) }.orEmpty()
            }.getOrDefault(emptyList())
            if (got.isNotEmpty() || ++attempt >= maxAttempts) return got
        }
    }

    /**
     * Stamps every source with the section of the player's server chooser it
     * belongs to, from the ENGINE that produced it — never from the source's own
     * name, which for a cross-extension hit is a "Repo · Server" prefix and for
     * a plugin's own extractor is just the mirror's name. A provider that
     * already set the field keeps it (the origin API knows better than we do).
     */
    private fun tagGroup(
        list: List<StreamSource>,
        p: ContentProvider,
    ): List<StreamSource> {
        if (list.isEmpty()) return list
        val label = p.config.type.groupLabel
        return list.map { s ->
            // The engine label is only filled in when blank (an origin API
            // knows its own grouping better than we do). The provider's id and
            // NAME are always recorded when missing: they are what lets the
            // player put the provider the user opened this from at the front of
            // the server list and label its section with the repo's own name.
            s.copy(
                provider = s.provider.ifBlank { label },
                providerId = s.providerId.ifBlank { p.config.id },
                providerName = s.providerName.ifBlank { p.config.name },
            )
        }
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
                                    .take(24)
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
        // Keeps the feed loading while the user is in another app — Android
        // freezes a backgrounded process, which used to stop every catalog
        // mid-fetch (see [com.hikari.app.work.BackgroundWork]).
        val work = com.hikari.app.work.BackgroundWork.begin(
            active.firstOrNull()?.let { "Loading " + it.config.name } ?: "Loading Home catalogs"
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val jobs = active.mapIndexed { pi, p ->
                scope.async {
                    try {
                        providerGate.withPermit {
                            withTimeoutOrNull(40_000) {
                                val catalogs = p.catalogs()
                                    .distinctBy { it.type to it.id }
                                    .take(24)
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
            com.hikari.app.work.BackgroundWork.end(work)
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
        // Keeps the multi-page scan running while the user is in another app
        // (see [com.hikari.app.work.BackgroundWork]).
        val work = com.hikari.app.work.BackgroundWork.begin(
            "Searching \"${query.trim().take(60)}\""
        )
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
            com.hikari.app.work.BackgroundWork.end(work)
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
    ): List<StreamSource> {
        // A source scan across many providers can take a minute; keep it alive
        // if the user leaves the app (see [com.hikari.app.work.BackgroundWork]).
        val work = com.hikari.app.work.BackgroundWork.begin(
            "Finding servers for \"${item.title.take(60)}\""
        )
        try {
            return streamsForInner(item, episode, onProgress)
        } finally {
            com.hikari.app.work.BackgroundWork.end(work)
        }
    }

    private suspend fun streamsForInner(
        item: MediaItem,
        episode: Episode?,
        onProgress: (suspend (List<StreamSource>) -> Unit)?,
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
                    .sortedWith(
                        compareBy(
                            // The provider the user opened this title from goes
                            // FIRST: its own servers are the ones they expect at
                            // the top, and it gets an engine slot before the
                            // rest of the pool (a Nuvio origin used to be sorted
                            // purely by the priority list, so it could be last).
                            { p: ContentProvider -> if (p.config.id == item.providerId) 0 else 1 },
                            { p: ContentProvider ->
                                val idx = NUVIO_PRIORITY.indexOf(p.config.name.lowercase())
                                if (idx >= 0) idx else NUVIO_PRIORITY.size
                            },
                        )
                    )
            } else {
                emptyList()
            }
            // A Nuvio origin appears in BOTH lists above, which used to launch
            // two identical engines for the same provider — doubling its CPU
            // and network work and stealing a concurrency slot from the other
            // providers, which measurably delayed the first server. Query each
            // provider exactly once.
            val targets = (primaryTargets + nuvioTargets).distinctBy { it.config.id }
            // The other installed extensions that get their turn on EVERY
            // lookup (see crossExtensionSearch). Resolved up front so both the
            // merge loop and the deadline below can use the list.
        val crossTargets = crossExtensionTargets(item, origin)
        // Other repos of the SAME engine as the origin (e.g. the user's other
        // CloudStream repos when the title was opened from one) are pulled out
        // and searched in the FIRST pass, right beside the origin: they search
        // by the same kind of id, and they are the closest thing to "my
        // provider". Waiting out the grace window for them is what buried them
        // under forty Hikari servers.
        val sameEngine = if (origin == null) {
            emptyList()
        } else {
            crossTargets.filter { it.config.type == origin.config.type }
        }
        val lateTargets = crossTargets.filter { it !in sameEngine }
        if (targets.isEmpty() && crossTargets.isEmpty()) return@withContext emptyList()

        com.hikari.app.data.Logs.log(
            "Search",
            // The app version leads the line so a shared log identifies the
            // build it came from without having to guess (the session-start
            // banner can be trimmed off a shared file).
            "start \"${item.title}\" (${item.type}) v=${com.hikari.app.BuildConfig.VERSION_NAME} " +
                "origin=${origin?.config?.name ?: "?"} " +
                "primary=${targets.size} nuvio=${nuvioTargets.size} " +
                "cross=${crossTargets.size} same=${sameEngine.size} late=${lateTargets.size} " +
                // Which ENGINES the cross pass is about to ask, and how many
                // repos of each: "the CloudStream servers never show up" is
                // answered here — whether that family was searched at all, and
                // whether the repo the user has in mind even got a slot.
                "families=" + crossTargets
                    .groupingBy { it.config.type.groupLabel }
                    .eachCount()
                    .entries
                    .sortedBy { it.key }
                    .joinToString(",") { "${it.key}=${it.value}" } +
                // …and how many of each are INSTALLED. `families=` says whether
                // the CloudStream repos were asked; `installed=` says whether
                // they existed to ask in the first place — which is the
                // difference between "not installed" and "silently skipped".
                " installed=" + all
                    .groupingBy { it.config.type.groupLabel }
                    .eachCount()
                    .entries
                    .sortedBy { it.key }
                    .joinToString(",") { "${it.key}=${it.value}" },
        )

            // Fresh diagnostic state for this lookup.
            // Same for the cross-extension status the chooser's hint shows:
            // every repo of this lookup starts out "searching".
            crossRunning.clear()
            crossVerdict.clear()
            crossAsked.clear()
            crossFound.clear()
            crossInstalled.clear()
            // How many repos of each engine are installed, so the hint can say
            // "asked 32 of 48 CloudStream" — the difference between "not
            // installed" and "silently skipped" at a glance.
            all.groupingBy { it.config.type.groupLabel }
                .eachCount()
                .forEach { (label, n) -> crossInstalled[label] = n }
            bumpCrossStatus()
            com.hikari.app.nuvio.NuvioScraper.lastOutcome.clear()
            com.hikari.app.nuvio.NuvioRuntime.resetFetchLog()
            com.hikari.app.nuvio.NuvioRuntime.resetRunTracking()

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            var result: List<StreamSource> = emptyList()
            // A Cloudflare-blocked repo must not eat the pass: while these
            // searches run, each hidden solve gets a short budget and only a
            // couple run at once (see CloudflareVerifier). The block is recorded
            // and reported instead, so one challenged host cannot hold a search
            // slot for its full 20s and starve the repos still waiting to be
            // asked. The flag also stops a search from throwing verification
            // windows at the user.
            val solveBudgetBefore = CloudflareVerifier.hiddenSolveBudgetMs
            CloudflareVerifier.bulkSearchActive = true
            CloudflareVerifier.hiddenSolveBudgetMs = NetTuning.timeout(6_000L)
            crossNote = null
            try {
                val jobs = targets.mapIndexed { i, p ->
                    scope.async {
                        val isNuvio = p.config.type == ProviderType.NUVIO
                        // "First search your own provider": the origin's job
                        // starts immediately; every other primary target (the
                        // nuvio engines) waits a short head start, so the
                        // origin's own requests are not queued behind a dozen
                        // QuickJS engines on a busy phone. Servers from the
                        // others still stream in a moment later.
                        if (p.config.id != item.providerId) kotlinx.coroutines.delay(ORIGIN_HEAD_START_MS)
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
                                tagGroup(fetchStreams(p, item, episode), p)
                            } else {
                                tagGroup(fetchStreams(p, item, episode), p)
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
                // The job that belongs to the provider the user opened the title
                // FROM — the one whose servers the chooser/player is actually
                // waiting for. The other repos do not get turned loose while it
                // is still working (see [awaitOriginHeadStart]).
                val originJob = targets.indexOfFirst { it.config.id == item.providerId }
                    .takeIf { it >= 0 }?.let { jobs[it] }
                // Ceiling for the whole lookup. It only binds when providers are
                // slow/failing — normally they finish → allDone well before it.
                // 55s lets the trusted nuvio providers (priority order) pass
                // through the concurrency cap plus a few fallbacks. Results are
                // emitted progressively via onProgress, so the UI never sits on
                // an empty spinner while this runs.
                val deadline = started + NetTuning.timeout(55_000L)
                // With no main targets at all (e.g. a title opened from a repo
                // that has since been uninstalled) there is nothing to wait
                // for — start the other extensions immediately instead of
                // after the grace window.
                val crossGrace = if (targets.isEmpty()) 0L else CROSS_EXT_GRACE_MS
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
                // ---- PHASE 1: SEARCH every installed extension (nothing else) --
                // Each search job RETURNS the entry it matched; the wait loop
                // below drains finished jobs (whether they finished before or
                // after phase 2 opened), so a hit that lands late is never
                // dropped on the floor.
                val searchJobs = ArrayList<kotlinx.coroutines.Deferred<CrossHit?>>()
                fun launchSearch(p: ContentProvider, waitForOrigin: Boolean) {
                    searchJobs += scope.async {
                        // The origin's own engine family waits behind the origin
                        // (see [awaitOriginHeadStart]): with ~50 CloudStream repos
                        // installed, starting them all at t=0 competed with the
                        // origin for the same sites and network and buried its
                        // servers — the ones the user expects first — under the
                        // rest. The jobs are still created here, so the wait loop
                        // below still waits for them; only their work is deferred.
                        if (waitForOrigin) awaitOriginHeadStart(originJob, SAME_ENGINE_HEAD_START_MS)
                        val outcome: Pair<CrossHit?, String?> =
                            cancellableCatching { crossExtensionSearch(p, item) }
                                .getOrElse {
                                    null to ("search threw ${it.javaClass.simpleName}: " +
                                        (it.message ?: "no message"))
                                }
                        val hit = outcome.first
                        val verdict = outcome.second
                        if (hit == null) {
                            val repo = p.config.name.ifBlank { p.config.id }
                            crossVerdict[p.config.id] = "$repo — ${verdict ?: "no matching title"}"
                            crossRunning.remove(p.config.id)
                            bumpCrossStatus()
                            com.hikari.app.data.Logs.log(
                                "Search",
                                "cross \"${item.title}\" → $repo: nothing ($verdict)",
                            )
                        }
                        hit
                    }
                }
                // Same-engine repos start with the main pass (see `sameEngine`).
                sameEngine.forEach { launchSearch(it, waitForOrigin = true) }
                var lateStartedAt = 0L
                var searchPhaseClosed = false
                val hits = ArrayList<CrossHit>()
                val extractJobs = ArrayList<kotlinx.coroutines.Deferred<List<StreamSource>>>()
                var extractionCursor = 0
                fun launchPendingExtractions() {
                    while (extractionCursor < hits.size) {
                        val hit = hits[extractionCursor++]
                        extractJobs += scope.async {
                            val out: Pair<List<StreamSource>, String?> =
                                cancellableCatching {
                                    crossExtensionExtract(hit, item, episode)
                                }.getOrElse {
                                    emptyList<StreamSource>() to
                                        ("extraction threw ${it.javaClass.simpleName}")
                                }
                            val found = out.first
                            val verdict = out.second
                            val id = hit.provider.config.id
                            if (verdict == null && found.isNotEmpty()) {
                                crossVerdict.remove(id)
                                crossFound[id] = hit.provider.config.type.groupLabel
                            } else {
                                crossVerdict[id] = "${hit.repo} — " +
                                    (verdict ?: "no playable links")
                            }
                            crossRunning.remove(id)
                            bumpCrossStatus()
                            com.hikari.app.data.Logs.log(
                                "Search",
                                "cross \"${item.title}\" → ${hit.repo}: " +
                                    (verdict?.let { "nothing ($it)" } ?: "${found.size} servers"),
                            )
                            found
                        }
                    }
                }
                while (true) {
                    jobs.forEach { merge(it) }
                    extractJobs.forEach { merge(it) }
                    // Progressive emission: hand over every newly-found server
                    // so the UI can show them while the rest keep searching.
                    if (onProgress != null && merged.size != lastEmitted) {
                        lastEmitted = merged.size
                        onProgress(merged.values.toList())
                    }
                    val now = System.currentTimeMillis()
                    // The origin has had its head start — and if it is still
                    // working, the other repos wait a little longer for it
                    // (bounded by ORIGIN_SETTLE_MAX_MS). Ask EVERY other
                    // installed extension for the same title (phase 1) and merge
                    // what they find into this same list (the progressive
                    // emission above hands each new server to the UI/player as
                    // it lands). This is what makes "play from any server from
                    // any repo" true for extensions, not just Stremio/Nuvio —
                    // and it runs even when the origin DID return servers,
                    // because those may all be dead while another repo's are not.
                    val lateGrace = if (originJob == null || originJob.isCompleted) crossGrace
                    else ORIGIN_SETTLE_MAX_MS
                    if (lateStartedAt == 0L && lateTargets.isNotEmpty() &&
                        now - started >= lateGrace
                    ) {
                        lateStartedAt = now
                        lateTargets.forEach { launchSearch(it, waitForOrigin = false) }
                    }
                    // Pick up everything the searches matched so far — from every
                    // job that has completed, so a hit that arrives after phase 2
                    // opened still gets its servers.
                    val jobIt = searchJobs.iterator()
                    while (jobIt.hasNext()) {
                        val j = jobIt.next()
                        if (j.isCompleted) {
                            runCatching { j.getCompleted() }.getOrNull()?.let { hits.add(it) }
                            jobIt.remove()
                        }
                    }
                    // ---- PHASE 2: extract what phase 1 matched ----
                    // Phase 2 opens once EVERY extension has answered (or the
                    // search ceiling is reached), and never before the late pass
                    // has started. Extraction is the slow half (a site-specific
                    // parse per repo) and is deliberately not allowed to compete
                    // with the searches: a matched repo used to keep holding a
                    // search slot while it fetched its episode list, which is
                    // what starved the tail of the 180-repo .hiki family — those
                    // searches never ran, and the pass still announced
                    // "all done, none with servers".
                    if (!searchPhaseClosed) {
                        val lateLaunched = lateTargets.isEmpty() || lateStartedAt != 0L
                        val phaseFrom = if (lateStartedAt != 0L) lateStartedAt else started
                        val allSearched = searchJobs.isEmpty()
                        // Also open once only a small TAIL of searches is left:
                        // searching is far cheaper than extracting, so letting the
                        // last few searches run while extraction has already begun
                        // puts the first servers on screen sooner — without
                        // re-creating the starvation the gate exists to prevent.
                        // (A small install is all "tail", so it behaves exactly as
                        // it always did: extract as soon as something matched.)
                        val smallTail = searchJobs.size <= CROSS_EXT_SEARCH_TAIL
                        if (lateLaunched &&
                            (allSearched || smallTail ||
                                now - phaseFrom >= CROSS_EXT_SEARCH_PHASE_MS)
                        ) {
                            searchPhaseClosed = true
                        }
                    }
                    if (searchPhaseClosed) launchPendingExtractions()
                    val allDone = jobs.all { it.isCompleted } && searchPhaseClosed &&
                        searchJobs.isEmpty() && extractJobs.all { it.isCompleted }
                    if (allDone) break
                    // Wait for EVERY provider (like Stremio aggregating every
                    // addon): each installed nuvio provider is independent
                    // (it resolves from the TMDB id alone), so each one that
                    // finds streams adds selectable servers to the list. The old
                    // first-non-empty early-close cancelled every provider that
                    // hadn't answered within ~1.5s, which is why only one
                    // provider's servers ever showed up in the player.
                    if (now > maxOf(deadline, started + CROSS_EXT_BUDGET_MS)) break
                    kotlinx.coroutines.delay(80)
                }
                jobs.forEach { it.cancel() }
                searchJobs.forEach { it.cancel() }
                extractJobs.forEach { it.cancel() }
                result = merged.values.toList()
                // Name the repos the pass did not actually get to. The old pass
                // wrote "asked" for every QUEUED repo, so with 180+ .hiki repos
                // behind a handful of slots it could announce "Asked 231 other
                // repos … all done, none with servers" while the tail had never
                // been searched at all — the report that made the search look
                // like it had silently stopped.
                val neverReached = crossTargets.count { !crossAsked.containsKey(it.config.id) }
                crossTargets.forEach { p ->
                    val id = p.config.id
                    if (crossVerdict.containsKey(id) || crossFound.containsKey(id)) return@forEach
                    val repo = p.config.name.ifBlank { id }
                    crossVerdict[id] = if (crossAsked.containsKey(id))
                        "$repo — was still searching when the pass ended"
                    else
                        "$repo — never reached (the pass ended before asking it)"
                }
                crossRunning.clear()
                // A Cloudflare block belongs to the SITE, not to one repo: keep
                // it as a single pass-level note so the hint can say what really
                // happened instead of the repos it touched looking like empty
                // catalogs.
                val blockedHost = CloudflareVerifier.blockedHost()
                crossNote = blockedHost?.let {
                    "Cloudflare check needed on $it — open the globe (verify) on Home, then search again"
                }
                bumpCrossStatus()
                // One line that answers "were the other engines even asked,
                // and if so what happened?" — without scrolling through one
                // line per repo. `emptyPage` counts repos that answered with
                // nothing; `timedOut`/`failed` count repos that could not be
                // asked at all (a cold plugin load, a dead site), which used to
                // be indistinguishable from "no matching title".
                val crossVerdicts = crossVerdict.values.toList()
                val reasons = crossVerdicts.map { it.substringAfter(" — ", it) }
                val buckets = LinkedHashMap<String, Int>()
                for (r in reasons) {
                    val b = crossReasonBucket(r)
                    buckets[b] = (buckets[b] ?: 0) + 1
                }
                val breakdown = buckets.entries
                    .sortedByDescending { it.value }
                    .joinToString(", ") { "${it.value} ${it.key}" }
                // One real example per KIND of failure (not one per repo): the
                // pass's shape in a single line, with the actual plugin text.
                val examples = reasons
                    .distinctBy { crossReasonBucket(it) }
                    .take(3)
                    .joinToString(" · ") { it.take(90) }
                com.hikari.app.data.Logs.log(
                    "Search",
                    "cross done \"${item.title}\": asked=${crossAsked.size} of " +
                        "${crossTargets.size} (neverReached=$neverReached) " +
                        "withServers=${crossFound.size} empty=${crossVerdicts.size}" +
                        (if (breakdown.isBlank()) "" else " · $breakdown") +
                        (if (examples.isBlank()) "" else " · e.g. $examples"),
                )
            } finally {
                CloudflareVerifier.bulkSearchActive = false
                CloudflareVerifier.hiddenSolveBudgetMs = solveBudgetBefore
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
            com.hikari.app.data.Logs.log(
                "Search",
                "done \"${item.title}\" → ${finalResult.size} servers " +
                    finalResult.groupingBy { it.providerName.ifBlank { it.provider.ifBlank { "?" } } }
                        .eachCount(),
            )
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

    /** The other installed extensions worth asking by title: .cs3 / .hiki /
     *  universal providers all expose search + load() + loadLinks(), and a
     *  Stremio addon does too (search → meta → stream) — so a title opened from
     *  a CloudStream plugin can also be rescued by a Stremio addon. Two groups
     *  are deliberately left out:
     *   - the origin itself;
     *   - Stremio addons when the origin IS a Stremio addon, because the main
     *     pass already asked every addon in that case;
     *   - nuvio providers entirely, since the main pass already searched them
     *     by TMDB id (they resolve without the title at all). */
    private fun crossExtensionTargets(item: MediaItem, origin: ContentProvider?): List<ContentProvider> {
        val originType = origin?.config?.type
        val originIsStremio = originType == ProviderType.STREMIO
        // Trust rank: the origin's OWN engine first (other repos of the same
        // plugin family are the ones the user expects right after the origin —
        // the CloudStream repos next to the CloudStream title), then native
        // .hiki extensions, then the rest of the CloudStream plugins, then
        // universal scrapers, then Stremio addons.
        fun rankOf(t: ProviderType): Int = when {
            originType != null && t == originType -> 0
            t == ProviderType.HIKARI -> 1
            t == ProviderType.CS3 -> 2
            t == ProviderType.UNIVERSAL -> 3
            else -> 4
        }
        val families = manager.providers.value
            .filter { p ->
                if (!p.config.enabled || p.config.id == item.providerId) return@filter false
                when (p.config.type) {
                    ProviderType.CS3,
                    ProviderType.HIKARI,
                    ProviderType.UNIVERSAL -> true
                    ProviderType.STREMIO -> !originIsStremio
                    ProviderType.NUVIO -> false
                    else -> false
                }
            }
            .groupBy { it.config.type }
            .entries
            .sortedBy { rankOf(it.key) }
        // The origin's own family goes in FIRST and in FULL: if a title was
        // opened from a CloudStream repo, every installed CloudStream repo is
        // the most likely home of the next server (and the one the user has in
        // mind), so every one of them is asked before any slot is spent
        // elsewhere. Then the remaining families are cycled ONE per family per
        // round — the native .hiki family alone is 64+ repos, so a straight
        // prefix of the trust-sorted list handed every slot to it and an
        // installed CloudStream repo was never even asked. Round-robin keeps
        // every family (and so every installed repo) in the pass, while each
        // family keeps its install order.
        val out = ArrayList<ContentProvider>(CROSS_EXT_MAX_TARGETS)
        for (family in families) {
            if (originType == null || family.key != originType) continue
            out += family.value
        }
        var round = 0
        while (out.size < CROSS_EXT_MAX_TARGETS) {
            var added = false
            for (family in families) {
                if (originType != null && family.key == originType) continue
                val p = family.value.getOrNull(round) ?: continue
                out += p
                added = true
                if (out.size >= CROSS_EXT_MAX_TARGETS) break
            }
            if (!added) break
            round++
        }
        // A duplicate entry in the provider list (the same repo installed twice)
        // used to get TWO concurrent lookups against the same provider, both
        // writing the same diagnostic map — so one of them read the other's note
        // back and reported the repo as "couldn't be searched" when it had in
        // fact answered normally.
        return out.distinctBy { it.config.id }
    }

    /** One extension that MATCHED the title during phase 1, waiting for phase 2
     *  to resolve its meta / episode list and extract its servers. */
    private class CrossHit(
        val provider: ContentProvider,
        val candidate: MediaItem,
        val repo: String,
    )

    /** Marks a repo's search as genuinely STARTED. The status maps must never
     *  claim a repo was asked before its search actually ran: with 180+ .hiki
     *  repos queued behind a handful of slots, the old code wrote "asked" for
     *  every QUEUED repo, so the chooser said "Asked 231 other repos … all done,
     *  none with servers" while the tail had never even been reached — the exact
     *  report that made the search look like it had silently stopped. */
    private fun markCrossSearchStarted(p: ContentProvider, repo: String, title: String) {
        crossAsked[p.config.id] = p.config.type.groupLabel
        crossRunning[p.config.id] = repo
        bumpCrossStatus()
        com.hikari.app.data.Logs.log("Search", "cross \"$title\" → $repo: searching…")
    }

    /** PHASE 1 of the cross-extension pass: ask ONE extension for the title and
     *  pick the best matching entry — nothing more. Extraction (and the meta /
     *  episode fetch it needs) is deliberately left to phase 2, so a repo that
     *  matched cannot hold a search slot while it works on its own servers.
     *  Publishes live status for the chooser's hint. Returns the matched entry
     *  (with its repo name) or — when it produced none — a one-line reason. */
    private suspend fun crossExtensionSearch(
        p: ContentProvider,
        item: MediaItem,
    ): Pair<CrossHit?, String?> {
        val repo = p.config.name.ifBlank { p.config.id }
        // Queued: counted as "still searching" until the verdict lands.
        crossRunning[p.config.id] = repo
        bumpCrossStatus()
        val title = item.title.trim()
        if (title.isBlank()) return null to "no title to search for"
        // What this extension had to say BEFORE we asked it anything: if its own
        // words change while we search (a plugin that failed to load, a search
        // that blew up), that change is the reason it produced nothing — and it
        // is a very different story from "this repo does not carry the show".
        val saidBefore = providerStreamMessage(p)
        var attempt = searchBestMatch(p, title, item, CROSS_EXT_MIN_MATCH) {
            markCrossSearchStarted(p, repo, title)
        }
        // A search that THREW or TIMED OUT says nothing about the repo's
        // catalog — a cold plugin load ("the first call has to spin up its
        // runtime") is the usual cause, and the old code wrote that off as "no
        // matching title". Ask once more before giving up on this repo.
        if (attempt.best == null && attempt.why != null) {
            attempt = searchBestMatch(p, title, item, CROSS_EXT_MIN_MATCH)
        }
        var best = attempt.best
        if (best == null && attempt.why == null) {
            // Repos name titles their own way ("Foo: Bar Baz", "Foo - Season 2"),
            // and searching the full string can come back empty even though the
            // repo DOES carry the show. One retry with the shortest meaningful
            // segment rescues those, instead of reporting "this repo has
            // nothing" and leaving the user without its servers.
            val variant = titleVariant(title)
            if (variant != null && !variant.equals(title, ignoreCase = true)) {
                val second = searchBestMatch(p, variant, item, CROSS_EXT_MATCH_VARIANT)
                best = second.best
                // Keep whichever attempt has something to say: a failure from
                // the retry is more informative than "the full title missed".
                if (second.why != null) attempt = second
            }
        }
        if (best == null) {
            // What the extension itself said while we searched (a plugin that
            // failed to load, a search that blew up) is the reason it produced
            // nothing — a very different story from "this repo does not carry
            // the show". Never mistake OUR OWN wording for the provider's: a
            // note written here by an earlier pass made a plain "no matching
            // title" read back as "couldn't be searched".
            val id = p.config.id
            val said = providerStreamMessage(p)?.takeIf { it != saidBefore && selfNote[id] != it }
            if (said != null) {
                recordStreamMessage(p, said)
                return null to "couldn't be searched ($said)"
            }
            val why = attempt.why
            if (why != null) {
                recordStreamMessage(p, "Searched \"$title\" — $why.")
                return null to why
            }
            recordStreamMessage(p, "Searched \"$title\" — no matching title in this repo.")
            return null to "no matching title for \"$title\""
        }
        com.hikari.app.data.Logs.log(
            "Search",
            "cross \"${item.title}\" → $repo: found \"${best.title}\" — getting servers…",
        )
        return CrossHit(p, best, repo) to null
    }

    /** PHASE 2 of the cross-extension pass: an extension that MATCHED the title
     *  in phase 1 — resolve its meta, map the played episode onto ITS episode
     *  list, extract, and tag each server with the repo it came from. Returns
     *  the servers it produced plus — when it produced none — a one-line reason,
     *  which the chooser's hint and the log both show. */
    private suspend fun crossExtensionExtract(
        hit: CrossHit,
        item: MediaItem,
        episode: Episode?,
    ): Pair<List<StreamSource>, String?> {
        val p = hit.provider
        val best = hit.candidate
        // The provider's own load() also rewrites the id to its canonical form,
        // which is what its loadLinks() expects.
        val meta = CROSS_EXT_DETAIL_SEMAPHORE.withPermit {
            withTimeoutOrNull(CROSS_EXT_META_TIMEOUT_MS) {
                cancellableCatching { p.getMeta(best) }.getOrDefault(best)
            } ?: best
        }
        var ep = episode
        if (episode != null) {
            // "No episode S1E5 for this title" and "its episode list never
            // answered" are very different stories: the first means the repo
            // carries the show but not this episode, the second means it may
            // carry both and could not be asked. Say which one it was.
            var epFailure: String? = null
            var epTimedOut = false
            val eps: List<Episode> = CROSS_EXT_DETAIL_SEMAPHORE.withPermit {
                withTimeoutOrNull(CROSS_EXT_EPISODES_TIMEOUT_MS) {
                    cancellableCatching { p.getEpisodes(best) }
                        .onFailure { e ->
                            epFailure = e.javaClass.simpleName + ": " + (e.message ?: "no message")
                        }
                        .getOrNull()
                } ?: run { epTimedOut = true; null }
            }.orEmpty()
            if (epTimedOut) {
                recordStreamMessage(p, "Has the title, but its episode list timed out.")
                return emptyList<StreamSource>() to "episode list timed out"
            }
            if (epFailure != null) {
                recordStreamMessage(p, "Has the title, but its episode list failed: $epFailure")
                return emptyList<StreamSource>() to "episode list failed: $epFailure"
            }
            val match = matchCrossEpisode(eps, episode)
            if (match == null) {
                recordStreamMessage(p, "Has the title, but not S${episode.season}E${episode.number}.")
                return emptyList<StreamSource>() to
                    "has the title, but not S${episode.season}E${episode.number}"
            }
            ep = match
        }
        // Same for extraction: a dead extractor and a page with no playable
        // links look identical in a server list, but only one of them means the
        // repo can be written off.
        var gotFailure: String? = null
        var gotTimedOut = false
        val got: List<StreamSource> = CROSS_EXT_EXTRACT_SEMAPHORE.withPermit {
            withTimeoutOrNull(CROSS_EXT_STREAMS_TIMEOUT_MS) {
                cancellableCatching { p.getStreams(meta, ep) }
                    .onFailure { e ->
                        gotFailure = e.javaClass.simpleName + ": " + (e.message ?: "no message")
                    }
                    .getOrDefault(emptyList())
            } ?: run { gotTimedOut = true; emptyList<StreamSource>() }
        }
        if (got.isEmpty()) {
            val why = when {
                gotTimedOut ->
                    "extraction timed out after ${CROSS_EXT_STREAMS_TIMEOUT_MS / 1000}s"
                gotFailure != null -> "extraction failed: $gotFailure"
                else -> "no playable links"
            }
            recordStreamMessage(p, "Has the title and episode, but $why.")
            return emptyList<StreamSource>() to "has the title and episode, but $why"
        }
        // Found here: clear this repo's diagnostic, and tag each server with the
        // repo it came from so the player's server list shows its origin.
        recordStreamMessage(p, null)
        return tagGroup(
            got.map { s ->
                if (p.config.name.isBlank() || s.name.startsWith(p.config.name)) s
                else s.copy(name = "${p.config.name} · ${s.name}")
            },
            p,
        ) to null
    }

    /**
     * Maps the episode being played onto the extension's own episode list.
     *
     * Extensions number their seasons their own way: several leave the season
     * unset (so every episode lands in "season 1"), some label a season by a
     * year or start at 0, some keep one flat list for the whole show. The old
     * exact `(season, number)` match — with a bare `number` retry — therefore
     * reported a repo that plainly HAS the episode as "has the title, but not
     * S2E2", which is why a repo the user knew carried the title contributed no
     * servers to the list at all. Tried in order: the same episode of the same
     * season; the same episode number in a repo that keeps only one season; the
     * season in the wanted POSITION (its 2nd season is our S2 even if it is
     * labelled otherwise); the episode at the flat position it would occupy in a
     * single list of the whole show; and finally that number in any season.
     */
    private fun matchCrossEpisode(eps: List<Episode>, wanted: Episode): Episode? {
        if (eps.isEmpty()) return null
        eps.firstOrNull { it.season == wanted.season && it.number == wanted.number }
            ?.let { return it }
        if (wanted.number <= 0) return null
        val seasons = eps.map { it.season }.distinct().sorted()
        // One season only: its "episode N" IS the wanted episode (the extension
        // either has a single season or never labels them at all).
        if (seasons.size <= 1) return eps.firstOrNull { it.number == wanted.number }
        // Same season by position — an extension that labels seasons 0-based, by
        // year, or "Season 1"/"Season 2" as a name.
        seasons.getOrNull(wanted.season - 1)?.let { season ->
            eps.firstOrNull { it.season == season && it.number == wanted.number }?.let { return it }
        }
        // Flat/absolute numbering: the episode sitting where ours would if the
        // whole show were numbered straight through.
        var before = 0
        for (season in seasons) {
            if (season == wanted.season) break
            before += eps.count { it.season == season }
        }
        eps.getOrNull(before + wanted.number - 1)?.let { return it }
        // Last resort: that number in whatever season carries it, rather than
        // telling the user this repo has nothing for the episode.
        return eps.firstOrNull { it.number == wanted.number }
    }

    /** Whatever this provider last said about itself — its plugin failing to
     *  load, a search error, a yt-dlp retry — or null when it has nothing on
     *  record. Read by the cross pass to tell "this repo does not have the show"
     *  apart from "this repo could not be asked at all". */
    private fun providerStreamMessage(p: ContentProvider): String? = when (p.config.type) {
        ProviderType.STREMIO -> StremioAddon.streamErrors[p.config.id]
        ProviderType.CS3 -> Cs3MainApiProvider.streamErrors[p.config.id]
        ProviderType.HIKARI -> HikariProviderAdapter.streamErrors[p.config.id]
        ProviderType.UNIVERSAL -> UniversalScraper.streamErrors[p.config.id]
        ProviderType.NUVIO -> com.hikari.app.nuvio.NuvioScraper.streamErrors[p.config.id]
    }

    /** Minimal head start for the repos of the origin's own family — extended
     *  while the origin is still working. It waits [minMs], then keeps waiting
     *  while [originJob] is unfinished, up to [ORIGIN_SETTLE_MAX_MS] in total.
     *  With ~180 native .hiki repos installed, the family's searches used to
     *  start right beside the origin and saturate the same engines and network,
     *  so the origin's own servers — the ones the player is actually waiting
     *  for — landed late or not at all. Returns immediately when there is no
     *  origin job to wait for. */
    private suspend fun awaitOriginHeadStart(
        originJob: kotlinx.coroutines.Deferred<List<StreamSource>>?,
        minMs: Long,
    ) {
        kotlinx.coroutines.delay(minMs)
        if (originJob == null) return
        val began = System.currentTimeMillis()
        while (!originJob.isCompleted &&
            System.currentTimeMillis() - began < ORIGIN_SETTLE_MAX_MS
        ) {
            kotlinx.coroutines.delay(150)
        }
    }

    /**
     * One extension search's outcome: the best matching entry when it found
     * one, or — when it did not — WHY it did not. The reason is what separates
     * "this repo does not carry the show" (an empty page, or a page whose
     * entries are all a different title) from "this repo could not be asked at
     * all" (a search that threw or timed out — typically a cold .cs3/.hiki
     * plugin load). Both used to be reported as one indistinguishable "no
     * matching title", which is exactly how a repo that DOES carry the title
     * could look like a repo that does not.
     */
    private class SearchAttempt(val best: MediaItem?, val why: String?)

    /**
     * Searches one extension and returns its best matching entry, or null when
     * nothing clears [minMatch]. Searches are the cheap half of the
     * cross-extension pass — the wider semaphore lets every installed extension
     * be searched at once; only extraction is throttled down.
     */
    private suspend fun searchBestMatch(
        p: ContentProvider,
        query: String,
        item: MediaItem,
        minMatch: Int,
        onStart: (() -> Unit)? = null,
    ): SearchAttempt = CROSS_EXT_SEARCH_SEMAPHORE.withPermit {
        // The search is about to RUN (a slot has been acquired). Reporting
        // "asked" any earlier counted merely-queued repos as searched (see
        // [markCrossSearchStarted]).
        onStart?.invoke()
        var timedOut = false
        var failure: String? = null
        val results: List<MediaItem> = withTimeoutOrNull(CROSS_EXT_SEARCH_TIMEOUT_MS) {
            cancellableCatching { p.search(query, 1) }
                .onFailure { e -> failure = e.javaClass.simpleName + ": " + (e.message ?: "no message") }
                .getOrDefault(emptyList())
        } ?: run { timedOut = true; emptyList<MediaItem>() }
        if (timedOut) {
            SearchAttempt(null, "search timed out after ${CROSS_EXT_SEARCH_TIMEOUT_MS / 1000}s")
        } else if (failure != null) {
            SearchAttempt(null, "search failed: $failure")
        } else if (results.isEmpty()) {
            // The repo answered, and the answer was an empty page: it genuinely
            // has no title remotely like this one. Zero results and a FAILED
            // search used to look identical in the log (see
            // [crossExtensionSearch]).
            SearchAttempt(null, null)
        } else {
            // Scored against the REAL title, never against the shortened query,
            // so a variant can only ever confirm a genuine match.
            val scored = results.map { it to titleScore(item.title, item.year, it) }
            val best = scored.filter { it.second >= minMatch }.maxByOrNull { it.second }?.first
            if (best != null) SearchAttempt(best, null)
            else SearchAttempt(
                null,
                "${results.size} search result(s), none of them \"$query\" " +
                    "(best match ${scored.maxOf { it.second }}/$minMatch)",
            )
        }
    }

    /**
     * A shorter, still-specific search phrase for a title that carries a
     * separator: "Foo: Bar Baz (2023)" → "Foo". Null when there is nothing to
     * shorten (the retry then never fires).
     */
    private fun titleVariant(title: String): String? {
        val cleaned = title
            .replace(Regex("\\[[^\\]]*]"), " ")
            .replace(Regex("\\([^)]*\\)"), " ")
            .trim()
        return cleaned.split(Regex("[:\\-–—]"))
            .map { it.trim() }
            .firstOrNull { it.length in 3 until cleaned.length }
    }

    /** How well a search hit matches the title we're looking for, so the
     *  cross-extension fallback picks the right entry off a fuzzy search page
     *  instead of whatever happened to come first. */
    private fun titleScore(wanted: String, wantedYear: Int?, candidate: MediaItem): Int {
        val a = normalizeTitle(wanted)
        val b = normalizeTitle(candidate.title)
        if (a.isEmpty() || b.isEmpty()) return 0
        val base = when {
            a == b -> 100
            b.startsWith(a) || a.startsWith(b) -> 70
            b.contains(a) || a.contains(b) -> 55
            else -> {
                val ta = a.split(' ').filter { it.length > 2 }.toSet()
                val tb = b.split(' ').filter { it.length > 2 }.toSet()
                if (ta.isEmpty() || tb.isEmpty()) 0
                else (ta.intersect(tb).size * 100) / maxOf(ta.size, tb.size)
            }
        }
        if (base == 0) return 0
        val yearInTitle = Regex("\\((19|20)(\\d{2})\\)").find(candidate.title)
        val yb = candidate.year
            ?: yearInTitle?.let { (it.groupValues[1] + it.groupValues[2]).toIntOrNull() }
        return if (wantedYear != null && yb != null) {
            base + when {
                wantedYear == yb -> 20
                kotlin.math.abs(wantedYear - yb) <= 1 -> 5
                else -> -25
            }
        } else base
    }

    /** Lowercases and strips bracketed/parenthesised noise so
     *  "India's Got Latent (2024) [S2]" and "indias got latent" compare equal. */
    private fun normalizeTitle(s: String): String =
        s.lowercase()
            .replace(Regex("\\[[^\\]]*]"), " ")
            .replace(Regex("\\([^)]*\\)"), " ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

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
            withTimeoutOrNull(NetTuning.timeout(45_000)) { YtDlpResolver.resolve(pageUrl) }
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
        return tagGroup(got, origin)
    }

    /** Routes a provider's stream message into the right per-provider error map
     *  so the Detail screen's "no sources" panel can explain what happened.
     *  [origin.config.id] is remembered in [selfNote] when the message is one of
     *  ours, so a later pass never reads our own "no matching title" note back
     *  as the provider's own words (see [crossExtensionSearch]). */
    private fun recordStreamMessage(origin: com.hikari.app.providers.ContentProvider, message: String?) {
        val id = origin.config.id
        if (message == null) selfNote.remove(id) else selfNote[id] = message
        val map = when (origin.config.type) {
            ProviderType.STREMIO -> StremioAddon.streamErrors
            ProviderType.CS3 -> Cs3MainApiProvider.streamErrors
            ProviderType.HIKARI -> HikariProviderAdapter.streamErrors
            ProviderType.UNIVERSAL -> UniversalScraper.streamErrors
            ProviderType.NUVIO -> com.hikari.app.nuvio.NuvioScraper.streamErrors
        }
        if (message == null) map.remove(id) else map[id] = message
        // Mirrored into the on-device log: a "why were this repo's servers
        // missing?" report can then be answered from the shared log file
        // instead of guessed at (see Logs / Settings → Logs & diagnostics).
        com.hikari.app.data.Logs.log(
            "Provider",
            "${origin.config.name} [${origin.config.type}]: " +
                (message ?: "servers found"),
        )
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
                val named = withRealEpisodeNames(item, sorted)
                val translated = translateEpisodes(item.providerId, named)
                synchronized(episodeCache) { episodeCache[item.uniqueId] = translated }
                return@withContext translated
            }
        }
        // Last resort for a metadata-only provider (Nuvio/TMDB): when the
        // metadata sources have nothing usable — TMDB stalled behind and
        // Bangumi with no match — borrow the episode list from an installed
        // extension that scrapes it from its site. Those lists come straight
        // from the source site, so they are the ground truth when the
        // databases disagree about a donghua's episode count.
        if (item.type == MediaType.SERIES &&
            manager.byId(item.providerId)?.config?.type == ProviderType.NUVIO
        ) {
            episodesFromExtensions(item)?.let { list ->
                val named = withRealEpisodeNames(item, list)
                val translated = translateEpisodes(item.providerId, named)
                synchronized(episodeCache) { episodeCache[item.uniqueId] = translated }
                return@withContext translated
            }
        }
        null
    }

    /**
     * Episode-list fallback: search the installed site-scraping extensions for
     * this title and use the first real episode list they return. Time-boxed
     * per provider and capped at a handful of providers, so one dead extension
     * cannot stall the detail page.
     */
    private suspend fun episodesFromExtensions(item: MediaItem): List<Episode>? {
        val want = TmdbMeta.normalizeTitle(item.title)
        if (want.length < 2) return null
        val candidates = manager.providers.value.filter {
            it.config.enabled &&
                it.config.id != item.providerId &&
                it.config.type != ProviderType.NUVIO
        }.take(6)
        for (p in candidates) {
            val hits = withTimeoutOrNull(12_000) {
                cancellableCatching { p.search(item.title, 1) }.getOrDefault(emptyList())
            } ?: continue
            val match = hits.firstOrNull { TmdbMeta.normalizeTitle(it.title) == want }
                ?: hits.firstOrNull {
                    val n = TmdbMeta.normalizeTitle(it.title)
                    want.length >= 5 && n.startsWith(want)
                }
                ?: continue
            val eps = withTimeoutOrNull(12_000) {
                cancellableCatching { p.getEpisodes(match) }.getOrNull()
            } ?: continue
            if (eps.size >= 2) return eps.sortedWith(compareBy({ it.season }, { it.number }))
        }
        return null
    }

    /**
     * Upgrades episode names to English where TMDB has an English title for
     * that episode, leaving the extension's own list — count, order and
     * numbering — exactly as it is, and never reordering or shortening it.
     *
     * The priority the app promises is: an English title if one exists;
     * otherwise the row keeps whatever its source called it — the site's own
     * label for an extension item, TMDB's own name for a Nuvio item (which has
     * no site behind it). A source label that is pure noise ("Swallowed Star
     * Episode 33 English Sub") is the one exception: it carries no title, so
     * TMDB's plain "Episode 33" is used instead.
     *
     * Runs only when some name actually needs it (foreign script, mechanical
     * label or missing), when the numbering is unambiguous (no per-season
     * restart, which would make number → title mapping wrong), and quietly
     * gives up on any failure — names are a nicety, never a gate.
     */
    private suspend fun withRealEpisodeNames(item: MediaItem, eps: List<Episode>): List<Episode> {
        if (eps.size < 3) return eps
        val numbers = eps.map { it.number }
        if (numbers.size != numbers.toSet().size) return eps
        if (eps.none { EpisodeTitles.needsEnglish(it.name, item.title) }) return eps
        val names = withTimeoutOrNull(12_000) {
            EpisodeTitles.lookup(item.title, item.year, numbers.toSet())
        } ?: return eps
        if (names.isEmpty()) return eps
        var changed = false
        val out = eps.map { e ->
            val raw = e.name
            val replacement = when {
                names.english[e.number] != null -> names.english[e.number]
                raw.isNullOrBlank() -> names.generic[e.number]
                EpisodeTitles.looksMechanical(raw, item.title) -> names.generic[e.number]
                else -> null
            }
            if (replacement != null && replacement != raw) {
                changed = true
                e.copy(name = replacement)
            } else {
                e
            }
        }
        return if (changed) out else eps
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
