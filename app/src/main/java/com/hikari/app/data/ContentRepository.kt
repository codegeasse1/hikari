package com.hikari.app.data

import com.hikari.app.HikariApp
import com.hikari.app.cs3.Cs3MainApiProvider
import com.hikari.app.cs3.YtDlpResolver
import com.hikari.app.net.CloudflareVerifier
import com.hikari.app.net.NetTuning
import com.hikari.app.nuvio.EpisodeTitles
import com.hikari.app.nuvio.NuvioScraper
import com.hikari.app.providers.ContentProvider
import com.hikari.app.providers.HikariProviderAdapter
import com.hikari.app.providers.ProviderManager
import com.hikari.app.providers.StremioAddon
import com.hikari.app.providers.UniversalScraper
import com.hikari.app.skystream.SkyStreamProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
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
     * One pass's diagnostic tally, for the player's "Select server" sheet and
     * the "no playable server found" note: [CrossTally.running] holds
     * `provider id -> repo name` for every extension still being searched,
     * [CrossTally.verdict] holds `provider id -> "Repo name — why it found
     * nothing"` for the ones that came back empty, [CrossTally.asked] /
     * [CrossTally.found] say which extensions were actually asked and which
     * produced servers, and [CrossTally.installed] counts the repos of each
     * engine that exist.
     *
     * WHY this is an object per pass instead of process-wide maps: a pass can
     * OVERLAP with the next one (a Play tap while the prefetch's sweep is still
     * running, a retry after a pass was cut short, two screens for the same
     * title). Every pass used to clear and then write the SAME maps, so the
     * counters on screen belonged to no single search at all — "asked 93" with
     * "164 no such title" (more verdicts than asks) and "186 no such title" one
     * card, "18" the next. Whoever asks now gets ITS OWN pass's numbers.
     *
     * [crossStatusVersion] bumps on every change so the UI can poll cheaply.
     */
    companion object {

        /** The live diagnostic state of ONE pass (see the class note above). */
        class CrossTally {
            val running = ConcurrentHashMap<String, String>()
            val verdict = ConcurrentHashMap<String, String>()
            val asked = ConcurrentHashMap<String, String>()
            val found = ConcurrentHashMap<String, String>()
            val installed = ConcurrentHashMap<String, Int>()
        }

        /** The tally of the newest pass — the one a summary should describe. */
        @Volatile
        var crossTally = CrossTally()
            private set

        /** Starts a fresh tally and makes it the current one. */
        fun newCrossTally(): CrossTally = CrossTally().also { crossTally = it }

        /**
         * Session-scoped NEGATIVE cache for the cross-extension pass:
         * `providerId|query` → the time that extension answered "no such title".
         *
         * A pass asks EVERY installed extension, and the same title is looked up
         * several times in a session (re-open the sheet, pick another server,
         * jump to the next episode). Re-running 200+ identical searches each time
         * is a large part of why the chooser's hint sat on "still searching" for
         * minutes — so a genuine empty-page answer is remembered briefly.
         *
         * ONLY that case is cached. A search that timed out or a plugin that
         * failed to load says nothing about the repo's catalogue and must be
         * retried (see [searchBestMatch]).
         */
        val crossEmpty = ConcurrentHashMap<String, Long>()

        const val CROSS_EMPTY_TTL_MS = 5 * 60 * 1000L

        /**
         * The POSITIVE counterpart of [crossEmpty]: `providerId|query` → the
         * entry that extension MATCHED for that query, with the time it did.
         *
         * A title is normally looked up several times in one session — open the
         * server sheet, pick another server, replay, jump to the next episode —
         * and each lookup used to re-ask every installed extension from
         * scratch. For a .cs3/.hiki repo that means re-loading its dex archive,
         * the single most expensive step of the whole pass, which is why the
         * second and third lookups crawled as much as the first. Remembering the
         * match lets a repeat lookup skip both the search and the cold load.
         *
         * Only a genuine, scored match is stored (never a failure, and never an
         * empty page — that is [crossEmpty]'s job), and only for a short window,
         * because a repo's catalogue does change.
         */
        class CrossMatch(val item: MediaItem, val at: Long)

        val crossMatch = ConcurrentHashMap<String, CrossMatch>()

        const val CROSS_MATCH_TTL_MS = 10 * 60 * 1000L

        /**
         * Repos that have handed this session a playable server at least once
         * (see [crossExtensionExtract]). They are asked FIRST on every later
         * lookup: they are the ones most likely to still carry the title, and
         * their plugin is usually still loaded, so they answer in a fraction of
         * the time a cold repo needs. Never cleared — it is session knowledge,
         * and a stale entry only costs one cheap search at the front of the
         * queue.
         */
        val crossProven: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /**
         * Extensions the session found sitting behind a Cloudflare verification
         * wall: `provider id -> the time we noticed`. They are dropped from the
         * cross-extension pass SILENTLY — not queued, not searched, not counted
         * in the chooser's progress line, and given no verdict — because that is
         * the requested behaviour: an extension that needs a verification is not
         * worth searching, and the fact that it is blocked is not something to
         * read in the server list. The record expires on its own (a verification
         * the user later completes brings the extension back for the asking).
         */
        val crossCfSkip = ConcurrentHashMap<String, Long>()

        const val CROSS_CF_SKIP_TTL_MS = 10 * 60 * 1000L

        /** True while [providerId] should be left out of the pass entirely. */
        fun isCfSkipped(providerId: String): Boolean {
            val at = crossCfSkip[providerId] ?: return false
            if (System.currentTimeMillis() - at < CROSS_CF_SKIP_TTL_MS) return true
            crossCfSkip.remove(providerId)
            return false
        }

        /** Verdict sentinel meaning "this extension was skipped — say nothing
         *  about it anywhere". Never shown, never counted. */
        const val CROSS_VERDICT_SKIPPED = "\u0000skipped"

        /** Buckets that are never surfaced in a summary the user reads: a
         *  verification wall is not something to put in a server list, and a
         *  skipped extension has nothing to say at all. How far the pass itself
         *  got ("no answer in time") is deliberately NOT in here — it is the
         *  honest explanation for an empty result (see [crossSummary]). */
        val CROSS_QUIET_BUCKETS = setOf(
            "cloudflare check",
            "skipped",
        )

        /**
         * `title|episode -> the servers a pass actually produced`, kept briefly.
         *
         * A title that JUST played is normally looked up again (replay, another
         * server, back out and in) and the second pass is a fresh, cold,
         * time-bounded sweep: if it is slower — or simply gets unlucky with the
         * sites it asks — the user saw a full server list a minute ago and now
         * gets "no playable sources", which reads as the app being broken.
         * The remembered list is MERGED INTO the fresh one (never instead of
         * it), so it can only ever add servers back; anything the new pass found
         * still wins, and a fresh non-empty result replaces the record.
         */
        class RememberedStreams(val list: List<StreamSource>, val at: Long)

        val streamsRemembered = ConcurrentHashMap<String, RememberedStreams>()

        const val REMEMBERED_STREAMS_TTL_MS = 15 * 60 * 1000L

        fun streamsRememberedKey(item: MediaItem, episode: Episode?): String =
            item.uniqueId + "|" + (episode?.id ?: "")

        /**
         * One background continuation of a cross-extension pass.
         *
         * A pass asks EVERY installed extension, and a large install simply
         * cannot be finished inside one pass's budget (180+ .hiki repos and 57
         * CloudStream repos against 96 search slots). When the pass runs out of
         * time with repos it never reached, those repos are handed to a sweep
         * that keeps searching on the application scope — after the pass has
         * returned, after the player has opened, while the video plays.
         *
         * [sinks] are the live-progress callbacks of every pass that has joined
         * this sweep (the pass that started it, plus any later one for the same
         * title+episode): each find is pushed to all of them, so the newest
         * screen and the player both see it. [current] is everything found so
         * far, so a joining pass can show those servers immediately instead of
         * waiting for the next find.
         */
        class Sweep {
            val sinks = java.util.concurrent.CopyOnWriteArrayList<suspend (List<StreamSource>) -> Unit>()
            @Volatile
            var current: List<StreamSource> = emptyList()
            @Volatile
            var job: kotlinx.coroutines.Job? = null
        }

        /** The background sweeps currently running, keyed by
         *  [streamsRememberedKey] (title+episode). See [startSweepIfNeeded]. */
        val sweeps = ConcurrentHashMap<String, Sweep>()

        /** Provider ids a running sweep still owns: their search (or
         *  extraction) is in flight or still queued. A pass tearing down must
         *  not write a verdict for one of these — they are neither "never
         *  reached" nor "still searching when the pass ended", they are being
         *  searched right now — and must leave their entry on the live status
         *  line, because that line is telling the truth. */
        val sweepOwned: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /** Is a background sweep STILL asking the repos this title's last pass
         *  never reached? While one is alive the search is genuinely not over,
         *  so the screen must not announce "no playable server found" (the sweep
         *  may be about to hand the player the server it is looking for), and
         *  the player's cover must keep saying that it is still searching
         *  instead of failing fast on a verdict that has not been reached yet.
         *  Safe to call from anywhere: it is a plain map read. */
        fun sweepBusyFor(item: MediaItem, episode: Episode?): Boolean {
            val sweep = sweeps[streamsRememberedKey(item, episode)] ?: return false
            return sweep.job?.isActive == true
        }

        fun crossEmptyKey(providerId: String, query: String): String =
            providerId + "|" + query.trim().lowercase()

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
            verdict == CROSS_VERDICT_SKIPPED -> "skipped"
            verdict.contains("never reached") -> "no answer in time"
            verdict.contains("still searching when the pass ended") -> "no answer in time"
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

        /**
         * One short line summarising the last cross-extension pass, for the
         * "no playable server found" note: how many extensions were asked, how
         * many came back with servers, and the dominant reasons the rest were
         * empty ("asked 253 · 2 with servers · 180 no such title · 40 could not
         * load"). Null when no pass has run yet, so callers can just append it.
         */
        fun crossSummary(limit: Int = 3): String? {
            // The NEWEST pass's tally: a summary has to describe ONE search, not
            // a merge of every search that happens to be running (see
            // [CrossTally] — a retry, or a Play tap during the prefetch's sweep,
            // used to make these numbers impossible: "asked 93 · 164 no such
            // title", i.e. more verdicts than asks).
            val tally = crossTally
            if (tally.verdict.isEmpty() && tally.found.isEmpty()) return null
            val counts = tally.verdict.values
                .groupingBy { crossReasonBucket(it) }
                .eachCount()
                .entries
                // Buckets the user asked never to read (a verification wall —
                // see [CROSS_QUIET_BUCKETS]). How far the pass itself got is
                // NOT hidden any more: "N no answer in time" is the honest
                // reason a pass ended with nothing, and hiding it is what made
                // an empty result look like "every extension said no such
                // title" when in fact hundreds were never reached.
                .filterNot { it.key in CROSS_QUIET_BUCKETS }
                .sortedByDescending { it.value }
                .take(limit)
                .joinToString(" · ") { "${it.value} ${it.key}" }
            return buildString {
                append("asked ${tally.asked.size}")
                if (tally.found.isNotEmpty()) append(" · ${tally.found.size} with servers")
                if (counts.isNotEmpty()) append(" · ").append(counts)
            }
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
    // NOTE on every wall-clock budget below: slow-connection mode scales a
    // PER-REQUEST timeout (a single slow response deserves more time), but it
    // must never scale a WALL-CLOCK CEILING on a whole pass. Multiplying the
    // ceilings by 3 turned the cross-extension pass into a 450-second wait — the
    // "it just sits on Finding server and never searches anything else" report —
    // so each ceiling is now clamped with `minOf`. Per-call timeouts still get
    // the full slow-mode multiplier.
    private val SEARCH_PAGE_TIMEOUT_MS get() = minOf(NetTuning.timeout(25_000L), 30_000L)
    private val SEARCH_PROVIDER_BUDGET_MS get() = minOf(NetTuning.timeout(90_000L), 90_000L)
    private val SEARCH_TOTAL_BUDGET_MS get() = minOf(NetTuning.timeout(140_000L), 140_000L)

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
    private val CROSS_EXT_BUDGET_MS get() = minOf(NetTuning.timeout(150_000L), 150_000L)

    /** Verdicts a background sweep is allowed to retry (see
     *  [startSweepIfNeeded]). Everything here means "the repo was never really
     *  asked", as opposed to "the repo answered, and the answer was no". */
    private val SWEEP_RETRY_BUCKETS =
        setOf("no answer in time", "timed out", "search error", "could not load")

    /** How long a BACKGROUND SWEEP keeps working after the pass that started it
     *  has ended. Deliberately long: this is the "keep searching every installed
     *  extension while the video plays" half of the pass, and everything it
     *  produces is only ever ADDED to a list the user is not blocked on. Still a
     *  ceiling, and the per-repo semaphores bound how hard it hits the phone. */
    private val SWEEP_BUDGET_MS get() = minOf(NetTuning.timeout(10 * 60 * 1000L), 10 * 60 * 1000L)

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
    private val CROSS_EXT_SEARCH_PHASE_MS get() = minOf(NetTuning.timeout(45_000L), 45_000L)

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
    private val CROSS_EXT_SEARCH_TIMEOUT_MS get() = minOf(NetTuning.timeout(20_000L), 25_000L)
    private val CROSS_EXT_EPISODES_TIMEOUT_MS get() = minOf(NetTuning.timeout(20_000L), 25_000L)
    private val CROSS_EXT_META_TIMEOUT_MS get() = minOf(NetTuning.timeout(15_000L), 20_000L)
    private val CROSS_EXT_STREAMS_TIMEOUT_MS get() = minOf(NetTuning.timeout(45_000L), 50_000L)

    // ---- Aniyomi gets a wider clock -------------------------------------
    // An Aniyomi extension is an APK: the first call into one pays a cold class
    // load (its dex plus its whole dependency graph through the child-first
    // loader) on top of the site's own latency and its own OkHttp session.
    // The generic budgets above were sized for a .cs3/JS plugin and cut a
    // perfectly healthy Aniyomi source off mid-answer — the recorded line
    // "Provider: Anichi [ANIYOMI]: Has the title, but its episode list timed
    // out." is exactly that, and it is why an anime the user could see sitting
    // in the extension's own catalogue contributed no servers at all. The
    // ceiling is still clamped, so a dead extension cannot hold the pass.
    private fun isAniyomi(p: ContentProvider) = p.config.type == ProviderType.ANIYOMI
    private val ANIYOMI_SEARCH_TIMEOUT_MS get() = minOf(NetTuning.timeout(45_000L), 60_000L)
    private val ANIYOMI_META_TIMEOUT_MS get() = minOf(NetTuning.timeout(45_000L), 60_000L)
    private val ANIYOMI_EPISODES_TIMEOUT_MS get() = minOf(NetTuning.timeout(75_000L), 90_000L)
    private val ANIYOMI_STREAMS_TIMEOUT_MS get() = minOf(NetTuning.timeout(110_000L), 150_000L)

    private fun searchTimeoutMs(p: ContentProvider) =
        if (isAniyomi(p)) ANIYOMI_SEARCH_TIMEOUT_MS else CROSS_EXT_SEARCH_TIMEOUT_MS

    private fun metaTimeoutMs(p: ContentProvider) =
        if (isAniyomi(p)) ANIYOMI_META_TIMEOUT_MS else CROSS_EXT_META_TIMEOUT_MS

    private fun episodesTimeoutMs(p: ContentProvider) =
        if (isAniyomi(p)) ANIYOMI_EPISODES_TIMEOUT_MS else CROSS_EXT_EPISODES_TIMEOUT_MS

    private fun streamsTimeoutMs(p: ContentProvider) =
        if (isAniyomi(p)) ANIYOMI_STREAMS_TIMEOUT_MS else CROSS_EXT_STREAMS_TIMEOUT_MS

    /** The Detail screen's meta fetch. A manifest-backed addon answers in
     *  milliseconds; an Aniyomi extension is an APK that has to be class-loaded
     *  first, and timing THAT out is what left an Aniyomi item with no meta —
     *  and so with the raw catalogue type (see [AniyomiProvider.toItem]). */
    private fun metaForTimeoutMs(p: ContentProvider) =
        if (isAniyomi(p)) ANIYOMI_META_TIMEOUT_MS else 15_000L

    /** The Detail screen's episode fetch — the same story as [metaForTimeoutMs]:
     *  an Aniyomi extension's episode list is a real scrape, and at 12s a cold
     *  one simply never answered. */
    private fun episodesForTimeoutMs(p: ContentProvider) =
        if (isAniyomi(p)) ANIYOMI_EPISODES_TIMEOUT_MS else 12_000L

    /** How many installed extensions may be asked for an episode list when the
     *  origin's own list came back empty (see [episodesFromExtensions]). */
    private val EPISODES_FALLBACK_TARGETS = 12

    /** Wall-clock ceiling for that whole fallback sweep. Generous, because it
     *  is only entered when the detail page otherwise has NO episodes at all —
     *  but still bounded, so a page of dead extensions cannot hang the screen. */
    private val EPISODES_FALLBACK_BUDGET_MS get() = minOf(NetTuning.timeout(50_000L), 50_000L)

    /** After the first extension answers with a usable list, how much longer the
     *  others get to contribute a longer one before the detail page moves on.
     *  Without this the first 2-episode stub to answer would win over the repo
     *  that carries the whole show. */
    private val EPISODES_FALLBACK_SETTLE_MS = 4_000L

    /** Home's per-provider ceiling, and the per-catalog one under it. Same
     *  reasoning again: 20s is generous for a plugin manifest and tight for a
     *  cold Aniyomi dex load plus a catalogue scrape. */
    private val ANIYOMI_HOME_PROVIDER_CEILING_MS get() = minOf(NetTuning.timeout(90_000L), 100_000L)
    private val ANIYOMI_HOME_CATALOG_CEILING_MS get() = minOf(NetTuning.timeout(45_000L), 60_000L)
    private fun homeProviderCeilingMs(p: ContentProvider) =
        if (isAniyomi(p)) ANIYOMI_HOME_PROVIDER_CEILING_MS else HOME_PROVIDER_CEILING_MS
    private fun homeCatalogCeilingMs(p: ContentProvider) =
        if (isAniyomi(p)) ANIYOMI_HOME_CATALOG_CEILING_MS else HOME_CATALOG_CEILING_MS

    /** Searching a title is cheap; extracting links is not, so they get their
     *  own caps. The wider one lets every installed extension be SEARCHED in
     *  parallel (a title search across dozens of repos then still finishes in a
     *  few seconds), while the narrow one keeps only a handful of extractors
     *  running at once — so one slow extractor can never stop the other
     *  extensions' searches from even being attempted. */
    private val CROSS_EXT_SEARCH_CONCURRENCY = 96
    /** How many extensions may extract at the same time. Six was low enough
     *  that, on a phone with a dozen installed repos, most targets queued behind
     *  the budget and never ran at all; with ~50 installed repos the searches
     *  alone used to take the better part of a minute, which is why the one
     *  repo that DOES carry the title (MovieBox) only landed its servers after
     *  playback had already started. */
    private val CROSS_EXT_EXTRACT_CONCURRENCY = 20
    /** How many matched extensions may fetch their meta / episode list at once.
     *  A SEPARATE cap from the search semaphore: fetching one repo's episode
     *  list must never take a slot that another repo still needs just to be
     *  SEARCHED (that sharing is what starved the tail of the queue). */
    private val CROSS_EXT_DETAIL_CONCURRENCY = 32
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
        // Aniyomi extensions pay a cold APK class load before their first
        // answer (see the Aniyomi budgets above) — 45s cut them off.
        val timeoutMs =
            if (isAniyomi(p)) minOf(NetTuning.timeout(90_000L), 120_000L)
            else NetTuning.timeout(45_000L)
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
     * Ceiling on ONE provider's whole Home job: its `catalogs()` plus the first
     * page of every row. A SkyStream extension has to boot an entire JS engine
     * and then scrape a dozen-plus pages before it can answer (its own engine
     * budget is 75s — see SkyStreamRuntime.CATALOG_TIMEOUT_MS), so the old 55s
     * ceiling reported a healthy-but-slow extension as "no catalog" on exactly
     * the same sites that a shorter-booting provider loaded fine.
     */
    private val HOME_PROVIDER_CEILING_MS get() = minOf(NetTuning.timeout(80_000L), 80_000L)

    /** Ceiling on one catalog's first page (served from the provider's own home
     *  cache for SkyStream extensions, so it is only reached by the scrapers). */
    private val HOME_CATALOG_CEILING_MS get() = minOf(NetTuning.timeout(20_000L), 20_000L)

    /**
     * Providers reordered so every engine FAMILY gets a turn early.
     *
     * Home ("All providers") and search-all walk `manager.providers` in INSTALL
     * order through a small semaphore ([Semaphore] 5 there, 4 here). With a few
     * hundred enabled installs that means the extensions installed LAST — every
     * SkyStream extension, since those are installed after the bulk CloudStream/
     * Hikari repos — are at position ~240 and are simply never reached before
     * the feed's own ceiling: their rows never appear and it reads as "this
     * extension has no catalog". Round-robin over the families (first of each,
     * then second of each, …) asks every one of them within the first few slots,
     * keeping install order INSIDE a family so the user's oldest/first install
     * still sorts first among its own kind. Same trick as
     * [crossExtensionTargets]'s target ordering.
     */
    private fun interleaveByProviderType(providers: List<ContentProvider>): List<ContentProvider> {
        if (providers.size < 3) return providers
        val families = LinkedHashMap<ProviderType, MutableList<ContentProvider>>()
        providers.forEach { families.getOrPut(it.config.type) { mutableListOf() }.add(it) }
        if (families.size < 2) return providers
        val out = ArrayList<ContentProvider>(providers.size)
        var index = 0
        while (true) {
            var added = false
            families.values.forEach { list ->
                if (index < list.size) {
                    out.add(list[index])
                    added = true
                }
            }
            if (!added) break
            index++
        }
        return out
    }

    /**
     * Records *why* a provider's catalog came up empty when the whole provider
     * job hit the ceiling below. Without this the provider produced no entry in
     * any `catalogErrors` map, so Home fell back to its generic "it returned no
     * content … if the site is stuck behind a Cloudflare check" text — which
     * sent people chasing a verification wall when the real cause was simply
     * that the extension ran out of time.
     */
    private fun noteCatalogTimeout(p: ContentProvider, ms: Long) {
        val msg = "Timed out after ${ms / 1000}s loading this extension's catalog."
        when (p) {
            is SkyStreamProvider -> SkyStreamProvider.catalogErrors[p.config.id] = msg
            is NuvioScraper -> NuvioScraper.catalogErrors[p.config.id] = msg
            is StremioAddon -> StremioAddon.catalogErrors[p.config.id] = msg
            is Cs3MainApiProvider -> Cs3MainApiProvider.catalogErrors[p.config.id] = msg
            is com.hikari.app.aniyomi.AniyomiProvider ->
                com.hikari.app.aniyomi.AniyomiProvider.catalogErrors[p.config.id] = msg
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
        // The provider list can hold the same repo twice (installed from two
        // repos, or a stale entry an update left behind). Every row such a
        // provider produced would then be built twice, carry the SAME Lazy key,
        // and take Home down with it — a duplicated key is a crash in Compose,
        // not a warning — while its catalog was fetched twice for nothing.
        val active = interleaveByProviderType(
            manager.providers.value
                .filter { it.config.enabled && (providerId == null || it.config.id == providerId) }
                .distinctBy { it.config.id }
        )
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
                            // seconds, so the provider/catalog ceilings above
                            // keep one dead host from stalling the whole home
                            // feed for two minutes while still tolerating slow
                            // provider manifest loads (a SkyStream extension
                            // boots a whole JS engine before its first byte,
                            // and its own home page can fetch a dozen sections).
                            val loaded = withTimeoutOrNull(homeProviderCeilingMs(p)) {
                                val catalogs = p.catalogs()
                                    .distinctBy { it.type to it.id }
                                    .take(24)
                                coroutineScope {
                                    catalogs.map { c ->
                                        async {
                                            catalogGate.withPermit {
                                                val items = withTimeoutOrNull(homeCatalogCeilingMs(p)) {
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
                            }
                            if (loaded == null) {
                                noteCatalogTimeout(p, homeProviderCeilingMs(p))
                                emptyList()
                            } else loaded
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
        val active = interleaveByProviderType(
            manager.providers.value.filter {
                it.config.enabled && (providerId == null || it.config.id == providerId)
            }
        )
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
                            val settled = withTimeoutOrNull(homeProviderCeilingMs(p)) {
                                val catalogs = p.catalogs()
                                    .distinctBy { it.type to it.id }
                                    .take(24)
                                coroutineScope {
                                    catalogs.mapIndexed { ci, c ->
                                        async {
                                            try {
                                                catalogGate.withPermit {
                                                    val items = withTimeoutOrNull(homeCatalogCeilingMs(p)) {
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
                            if (settled == null) noteCatalogTimeout(p, homeProviderCeilingMs(p))
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
                // Must cover the longest provider ceiling above, or rows that
                // landed after this would be thrown away with the flow: the
                // slowest extension still gets its full ceiling (an Aniyomi
                // extension's is the widest — see the Aniyomi budgets), plus the
                // row-level scramble on top.
                if (System.currentTimeMillis() - started > 150_000L) break
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
        val active = interleaveByProviderType(
            manager.providers.value.filter {
                it.config.enabled && (providerIds.isNullOrEmpty() || it.config.id in providerIds)
            }
        )
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
    /**
     * The outcome of one lookup: the servers it produced, and whether the lookup
     * actually FINISHED (as opposed to being cut short).
     *
     * That distinction is the whole point. A pass that ends normally has asked
     * what it could and its (possibly empty) answer is a real answer — the user
     * may be told "no extension has this title". A pass that was cut short says
     * nothing about the title at all (a provider blew up and took the pass with
     * it, a collector was cancelled, the device starved the threads), and
     * presenting it as "no playable server found after searching 254 extensions"
     * is a lie — it is what ended playback ~9 seconds into a search that was
     * still running, and made a working title need four Play taps. An incomplete
     * lookup must be RETRIED, never used as the verdict.
     */
    class StreamLookup(val servers: List<StreamSource>, val complete: Boolean)

    suspend fun streamsFor(
        item: MediaItem,
        episode: Episode?,
        /** Called with the merged server list each time a provider adds new
         *  results, so callers can show servers progressively (Stremio-style)
         *  while the slower providers are still searching. */
        onProgress: (suspend (List<StreamSource>) -> Unit)? = null,
    ): List<StreamSource> = streamsForOutcome(item, episode, onProgress).servers

    /** [streamsFor] plus "did this lookup actually finish?" — see [StreamLookup].
     *  Never throws: a lookup that dies is reported as `complete = false`, so the
     *  caller can ask again instead of telling the user there is nothing. */
    suspend fun streamsForOutcome(
        item: MediaItem,
        episode: Episode?,
        onProgress: (suspend (List<StreamSource>) -> Unit)? = null,
    ): StreamLookup {
        // A source scan across many providers can take a minute; keep it alive
        // if the user leaves the app (see [com.hikari.app.work.BackgroundWork]).
        val work = com.hikari.app.work.BackgroundWork.begin(
            "Finding servers for \"${item.title.take(60)}\""
        )
        try {
            return StreamLookup(streamsForInner(item, episode, onProgress), complete = true)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // OUR job being cancelled means the caller went away — propagate it.
            // A cancellation from INSIDE the pass is a different story: the pass
            // is over, nobody found out why, and that must never read as
            // "asked everything, nothing there".
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            com.hikari.app.data.Logs.log(
                "Search",
                "pass for \"${item.title.take(60)}\" was cut short (cancelled) — not an answer",
            )
            return StreamLookup(emptyList(), complete = false)
        } catch (e: Throwable) {
            // This used to be swallowed a level up, where an empty list meant the
            // same as a finished empty search. The reason is logged now, so a
            // pass that dies is diagnosable, and the caller is told plainly that
            // it was not an answer.
            com.hikari.app.data.Logs.log(
                "Search",
                "pass for \"${item.title.take(60)}\" ended early (" +
                    e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "") +
                    ") — not an answer",
            )
            return StreamLookup(emptyList(), complete = false)
        } finally {
            com.hikari.app.work.BackgroundWork.end(work)
        }
    }

    /**
     * Servers a RECENT pass produced for this exact title+episode, while they are
     * still inside the remember window ([REMEMBERED_STREAMS_TTL_MS]).
     *
     * A hint for instant play, never a substitute for the fresh pass: the caller
     * puts these on the live feed so a Play tap can start on the server that
     * worked minutes ago, and the extraction that is already running replaces
     * them as soon as it answers (see [streamsRemembered], which only ever holds
     * NON-empty results — a lookup that found nothing remembers nothing). Empty
     * when there is no recent success, or when the record has aged out, because
     * these servers hand out signed links that rotate.
     */
    fun recentlyFoundStreams(item: MediaItem, episode: Episode?): List<StreamSource> {
        val record = streamsRemembered[streamsRememberedKey(item, episode)] ?: return emptyList()
        if (System.currentTimeMillis() - record.at >= REMEMBERED_STREAMS_TTL_MS) return emptyList()
        return record.list
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

            // This pass's OWN diagnostic state — a fresh object, and the local
            // names below deliberately shadow nothing process-wide: passes
            // overlap (a Play tap during the prefetch's sweep, a retry after a
            // pass was cut short, two screens for the same title), and a shared
            // tally meant the numbers on screen belonged to no single search
            // ("asked 93" with "164 no such title" — more verdicts than asks).
            // Everything below, and every helper this pass calls, writes into
            // THIS tally.
            val tally = newCrossTally()
            val crossRunning = tally.running
            val crossVerdict = tally.verdict
            val crossAsked = tally.asked
            val crossFound = tally.found
            val crossInstalled = tally.installed
            // Drop expired "no such title" answers (see [crossEmpty]); the live
            // ones are what make the NEXT pass over the same title cheap.
            val emptyNow = System.currentTimeMillis()
            crossEmpty.entries.removeAll { emptyNow - it.value >= CROSS_EMPTY_TTL_MS }
            // Same for the remembered matches ([crossMatch]): expired ones are
            // dropped so a changed catalogue is re-searched instead of trusted
            // forever.
            crossMatch.entries.removeAll { emptyNow - it.value.at >= CROSS_MATCH_TTL_MS }
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
            com.hikari.app.skystream.SkyStreamProvider.lastOutcome.clear()
            com.hikari.app.skystream.SkyStreamRuntime.resetFetchLog()

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            var result: List<StreamSource> = emptyList()
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
                val deadline = started + minOf(NetTuning.timeout(55_000L), 60_000L)
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
                            cancellableCatching { crossExtensionSearch(p, item, episode, tally) }
                                .getOrElse {
                                    null to ("search threw ${it.javaClass.simpleName}: " +
                                        (it.message ?: "no message"))
                                }
                        val hit = outcome.first
                        val verdict = outcome.second
                        if (hit == null) {
                            val repo = p.config.name.ifBlank { p.config.id }
                            if (verdict == CROSS_VERDICT_SKIPPED) {
                                // Skipped on purpose (a verification wall — see
                                // [crossCfSkip]): not asked, not counted, and
                                // nothing about it is shown anywhere.
                                crossVerdict.remove(p.config.id)
                                crossAsked.remove(p.config.id)
                                crossRunning.remove(p.config.id)
                                bumpCrossStatus()
                                com.hikari.app.data.Logs.log(
                                    "Search",
                                    "cross \"${item.title}\" → $repo: skipped (verification needed)",
                                )
                            } else {
                                crossVerdict[p.config.id] = "$repo — ${verdict ?: "no matching title"}"
                                crossRunning.remove(p.config.id)
                                bumpCrossStatus()
                                com.hikari.app.data.Logs.log(
                                    "Search",
                                    "cross \"${item.title}\" → $repo: nothing ($verdict)",
                                )
                            }
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
                            // A verification wall is not a verdict. An extension
                            // that answered with one is dropped SILENTLY — no
                            // entry in the progress line, no verdict, and no
                            // further search for it this session (see
                            // [crossCfSkip]). `verdict == null` with nothing
                            // found is the same story: the extractor recorded
                            // the block as the provider's own error.
                            if (found.isEmpty()) {
                                val said = verdict?.takeIf { it == CROSS_VERDICT_SKIPPED }
                                    ?: providerStreamMessage(hit.provider)
                                if (said == CROSS_VERDICT_SKIPPED ||
                                    com.hikari.app.net.CloudflareVerifier
                                        .isVerificationMessage(said)
                                ) {
                                    crossCfSkip[id] = System.currentTimeMillis()
                                    crossAsked.remove(id)
                                    crossVerdict.remove(id)
                                    crossRunning.remove(id)
                                    bumpCrossStatus()
                                    com.hikari.app.data.Logs.log(
                                        "Search",
                                        "cross \"${item.title}\" → ${hit.repo}: " +
                                            "skipped (verification needed)",
                                    )
                                    return@async emptyList<StreamSource>()
                                }
                            }
                            if (verdict == null && found.isNotEmpty()) {
                                crossVerdict.remove(id)
                                crossFound[id] = hit.provider.config.type.groupLabel
                                // This repo actually produced playable servers:
                                // ask it first on every later lookup of any
                                // title (see [crossProven]).
                                crossProven.add(id)
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
                    if (now > maxOf(deadline, started + CROSS_EXT_BUDGET_MS)) {
                        // Out of time with repos still unasked. A large install
                        // cannot be swept inside one pass's budget — 180+ .hiki
                        // repos and 57 CloudStream repos against 96 search
                        // slots, each search costing up to 20s — and the old
                        // code simply cancelled the rest and wrote "was still
                        // searching when the pass ended", which is the reported
                        // "the search stopped at 13 of hikari, 5 of cloudstream
                        // — why isn't it searching all of them".
                        //
                        // So the repos this pass never got to are handed to a
                        // sweep on the APPLICATION scope (the pass's own scope
                        // is cancelled in a moment): it keeps searching and
                        // extracting in the background, while the player is
                        // already up and playing, and pushes every server it
                        // finds through the same sink the pass used — so it
                        // lands in the live feed and the player's server list
                        // exactly like a server that had arrived in time.
                        val remaining = crossTargets.filter { p ->
                            val id = p.config.id
                            if (isCfSkipped(id) || crossFound.containsKey(id)) {
                                return@filter false
                            }
                            val verdict = crossVerdict[id]
                                ?: return@filter true // never answered at all
                            // "No such title" is an ANSWER: the repo's own search
                            // page really came back without this title, and
                            // re-asking it is exactly the pointless work that made
                            // the pass crawl. A repo that could not be ASKED —
                            // a search that timed out, a plugin load that had to
                            // wait behind the loader's six process-wide slots —
                            // said nothing about its catalogue, and its plugin is
                            // loaded and cached by now, so the sweep's retry is
                            // both cheap and likely to succeed.
                            crossReasonBucket(verdict) in SWEEP_RETRY_BUCKETS
                        }
                        startSweepIfNeeded(
                            item,
                            episode,
                            remaining,
                            merged.values.toList(),
                            onProgress,
                        )
                        break
                    }
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
                    // Skipped extensions are left out of the record entirely:
                    // nothing to count, nothing to show (see [crossCfSkip]).
                    if (isCfSkipped(id)) return@forEach
                    // A background SWEEP owns this repo: its search is running
                    // right now, so it is neither "never reached" nor "still
                    // searching when the pass ended" — see [startSweepIfNeeded].
                    if (id in sweepOwned) return@forEach
                    if (crossVerdict.containsKey(id) || crossFound.containsKey(id)) return@forEach
                    val repo = p.config.name.ifBlank { id }
                    crossVerdict[id] = if (crossAsked.containsKey(id))
                        "$repo — was still searching when the pass ended"
                    else
                        "$repo — never reached (the pass ended before asking it)"
                }
                // Only the entries a running sweep still owns stay on the live
                // status line: for those, "N still searching" is literally true
                // (the background sweep is working on them), and clearing them
                // is what made the search look like it had given up.
                crossRunning.clear()
                sweepOwned.forEach { id ->
                    crossTargets.firstOrNull { it.config.id == id }?.let { p ->
                        crossRunning[id] = p.config.name.ifBlank { id }
                    }
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
                scope.cancel()
                // Teardown that must happen even when the pass above threw or
                // was cancelled before its own reporting block ran: the chooser
                // reads [crossRunning] for its "N still searching" line, and a
                // pass that died early would otherwise leave those entries in
                // place forever — nothing ever clears them again, so the search
                // looked permanently stuck ("it found servers, then just sat
                // there"). Idempotent: on the normal path every id is already in
                // [crossVerdict]/[crossFound] and nothing is written twice.
                crossTargets.forEach { p ->
                    val id = p.config.id
                    // Skipped extensions are left out of the record entirely:
                    // nothing to count, nothing to show (see [crossCfSkip]).
                    if (isCfSkipped(id)) return@forEach
                    // A background SWEEP owns this repo: its search is running
                    // right now, so it is neither "never reached" nor "still
                    // searching when the pass ended" — see [startSweepIfNeeded].
                    if (id in sweepOwned) return@forEach
                    if (crossVerdict.containsKey(id) || crossFound.containsKey(id)) return@forEach
                    val repo = p.config.name.ifBlank { id }
                    crossVerdict[id] = if (crossAsked.containsKey(id))
                        "$repo — was still searching when the pass ended"
                    else
                        "$repo — never reached (the pass ended before asking it)"
                }
                // Only the entries a running sweep still owns stay on the live
                // status line: for those, "N still searching" is literally true
                // (the background sweep is working on them), and clearing them
                // is what made the search look like it had given up.
                crossRunning.clear()
                sweepOwned.forEach { id ->
                    crossTargets.firstOrNull { it.config.id == id }?.let { p ->
                        crossRunning[id] = p.config.name.ifBlank { id }
                    }
                }
                bumpCrossStatus()
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
            // A title that JUST played is usually looked up again (replay,
            // picking another server, backing out and in), and the second pass
            // is a fresh, cold, time-bounded sweep — it can be slower, hit
            // different sites, or simply get unlucky, and the user who had a
            // full server list a minute ago then reads "no playable sources",
            // which looks like the app broke. Servers a recent pass actually
            // produced for this exact title+episode are remembered briefly and
            // stand in when the new pass comes back with nothing, so a repeat
            // lookup never empties a list it just had. The record is replaced by
            // any fresh non-empty result, and it expires on its own.
            val rememberKey = streamsRememberedKey(item, episode)
            val remembered = streamsRemembered[rememberKey]?.takeIf {
                System.currentTimeMillis() - it.at < REMEMBERED_STREAMS_TTL_MS
            }
            // A repeat lookup must never come back with FEWER servers than an
            // earlier one did. The cross pass is a fresh, time-bounded sweep of
            // 250+ repos and is not deterministic: whichever extensions happen
            // to answer inside the budget decide the list, so the same episode
            // replayed could show nuvio only, then hikari + nuvio, then fewer
            // nuvio and no hikari — the reported "every attempt shows a
            // different result". Servers this title+episode actually produced
            // recently are therefore merged IN, not merely used as a fallback
            // for an empty pass: what the user sees is the union, so a server
            // that played a minute ago cannot disappear by being unlucky.
            if (remembered != null) {
                val have = finalResult.mapTo(HashSet<String>()) { it.infoHash ?: it.url }
                val extra = remembered.list.filterNot { (it.infoHash ?: it.url) in have }
                if (extra.isNotEmpty()) {
                    val fresh = finalResult.size
                    finalResult = finalResult + extra
                    com.hikari.app.data.Logs.log(
                        "Search",
                        "done \"${item.title}\" → kept ${extra.size} server(s) from the " +
                            "previous lookup (this pass found $fresh)",
                    )
                }
            }
            if (finalResult.isNotEmpty()) {
                streamsRemembered[rememberKey] = RememberedStreams(finalResult, System.currentTimeMillis())
                if (streamsRemembered.size > 64) {
                    val cutoff = System.currentTimeMillis() - REMEMBERED_STREAMS_TTL_MS
                    streamsRemembered.entries.removeAll { it.value.at < cutoff }
                }
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

    /**
     * Hands the extensions a pass never reached to a BACKGROUND SWEEP.
     *
     * Why this exists: the pass has a wall-clock budget ([CROSS_EXT_BUDGET_MS])
     * and a single pass cannot ask 250+ installed extensions within it. The old
     * code cancelled whatever was left and reported it as "still searching when
     * the pass ended" — the search effectively STOPPED at "13 of 181 Hikari, 5
     * of 57 CloudStream", which is exactly what the user reported. Here the
     * leftovers are instead searched on the application scope, so the sweep
     * survives this pass returning, survives the player opening, and keeps
     * producing servers while the video plays.
     *
     * Every find is pushed through [sink] — the same live-progress callback the
     * pass itself used — so it lands in the detail screen's live feed, in
     * [StreamsLive], and in the player's "Select server" list exactly like a
     * server that had arrived in time. Nothing is ever removed, so playback can
     * only gain servers.
     *
     * Idempotent per title+episode: a later pass for the same video JOINS the
     * running sweep (registering its own sink and immediately receiving what the
     * sweep has found so far) rather than launching a rival one, so tapping Play
     * twice cannot double the network work.
     */
    private fun startSweepIfNeeded(
        item: MediaItem,
        episode: Episode?,
        targets: List<ContentProvider>,
        snapshot: List<StreamSource>,
        sink: (suspend (List<StreamSource>) -> Unit)?,
    ) {
        if (sink == null || targets.isEmpty()) return
        val key = streamsRememberedKey(item, episode)
        synchronized(sweeps) {
            val running = sweeps[key]
            if (running != null && running.job?.isActive == true) {
                running.sinks += sink
                val have = running.current
                if (have.isNotEmpty()) {
                    HikariApp.instance.appScope.launch {
                        cancellableCatching { sink(have) }
                    }
                }
                return
            }
            val sweep = Sweep()
            sweep.sinks += sink
            targets.forEach { sweepOwned.add(it.config.id) }
            val job = HikariApp.instance.appScope.launch {
                try {
                    runSweep(item, episode, targets, sweep, snapshot)
                } catch (e: Throwable) {
                    com.hikari.app.data.Logs.log(
                        "Search",
                        "sweep \"${item.title}\" ended early (" +
                            e.javaClass.simpleName +
                            (e.message?.let { ": $it" } ?: "") + ")",
                    )
                } finally {
                    targets.forEach { sweepOwned.remove(it.config.id) }
                    synchronized(sweeps) { if (sweeps[key] === sweep) sweeps.remove(key) }
                    bumpCrossStatus()
                    com.hikari.app.data.Logs.log(
                        "Search",
                        "sweep \"${item.title}\" finished — ${sweep.current.size} server(s) on the list",
                    )
                }
            }
            sweep.job = job
            sweeps[key] = sweep
            // A sweep that died before this line ran (its `finally` found nothing
            // to remove) must not be left in the map as a corpse: a later pass
            // would find a dead entry, which is mostly harmless, but
            // [sweepBusyFor] is what tells the detail screen whether the search
            // is REALLY over, so the map has to mean exactly what it says.
            if (!job.isActive) synchronized(sweeps) { if (sweeps[key] === sweep) sweeps.remove(key) }
            com.hikari.app.data.Logs.log(
                "Search",
                "sweep \"${item.title}\" → ${targets.size} repo(s) the pass never reached: " +
                    "searching them in the background",
            )
        }
    }

    /**
     * The sweep's own worker loop: search → match → extract, one repo at a time
     * per coroutine, with the SAME per-repo semaphores the pass uses (96 search,
     * 32 detail, 20 extract), so it can never starve the pass that is still
     * running for another title — and never hammers the phone. Results are
     * merged into a local accumulator seeded with [snapshot] (what the pass had
     * already found), pushed to every registered sink, and recorded in
     * [streamsRemembered] so a later lookup of the same video starts with them.
     */
    private suspend fun runSweep(
        item: MediaItem,
        episode: Episode?,
        targets: List<ContentProvider>,
        sweep: Sweep,
        snapshot: List<StreamSource>,
    ) {
        val key = streamsRememberedKey(item, episode)
        val acc = LinkedHashMap<String, StreamSource>()
        snapshot.forEach { acc[it.infoHash ?: it.url] = it }
        sweep.current = acc.values.toList()
        var lastEmitted = acc.size
        val budget = SWEEP_BUDGET_MS
        val started = System.currentTimeMillis()

        suspend fun publish() {
            val list = synchronized(acc) { acc.values.toList() }
            if (list.isEmpty() || list.size == lastEmitted) return
            lastEmitted = list.size
            sweep.current = list
            streamsRemembered[key] = RememberedStreams(list, System.currentTimeMillis())
            for (s in sweep.sinks) cancellableCatching { s(list) }
        }

        kotlinx.coroutines.coroutineScope {
            for (p in targets) {
                launch {
                    // Past the ceiling: stop starting new work. Whatever is
                    // already in flight still lands (and is published).
                    if (System.currentTimeMillis() - started >= budget) return@launch
                    val id = p.config.id
                    val repo = p.config.name.ifBlank { id }
                    // The live tally (not a captured one): a later pass for this
                    // video replaces [crossTally], and the sweep's progress must
                    // show up on whichever tally the chooser is reading.
                    val tally = crossTally
                    val outcome =
                        cancellableCatching { crossExtensionSearch(p, item, episode, tally) }
                            .getOrElse {
                                null to ("search threw ${it.javaClass.simpleName}: " +
                                    (it.message ?: "no message"))
                            }
                    val hit = outcome.first
                    val verdict = outcome.second
                    try {
                        if (hit == null) {
                            if (verdict == CROSS_VERDICT_SKIPPED) {
                                // A verification wall: dropped silently, like
                                // the pass does (see [crossCfSkip]).
                                tally.verdict.remove(id)
                                tally.asked.remove(id)
                            } else {
                                tally.verdict[id] = "$repo — ${verdict ?: "no matching title"}"
                                com.hikari.app.data.Logs.log(
                                    "Search",
                                    "sweep \"${item.title}\" → $repo: nothing ($verdict)",
                                )
                            }
                            return@launch
                        }
                        com.hikari.app.data.Logs.log(
                            "Search",
                            "sweep \"${item.title}\" → $repo: found \"${hit.candidate.title}\" " +
                                "— getting servers…",
                        )
                        val out = cancellableCatching { crossExtensionExtract(hit, item, episode) }
                            .getOrElse {
                                emptyList<StreamSource>() to
                                    ("extraction threw ${it.javaClass.simpleName}")
                            }
                        val found = out.first
                        val why = out.second
                        if (found.isEmpty()) {
                            tally.verdict[id] = "$repo — ${why ?: "no playable links"}"
                            com.hikari.app.data.Logs.log(
                                "Search",
                                "sweep \"${item.title}\" → $repo: nothing ($why)",
                            )
                        } else {
                            tally.verdict.remove(id)
                            tally.found[id] = p.config.type.groupLabel
                            // Proven: asked first on every later lookup.
                            crossProven.add(id)
                            synchronized(acc) {
                                found.forEach { s -> acc.putIfAbsent(s.infoHash ?: s.url, s) }
                            }
                            com.hikari.app.data.Logs.log(
                                "Search",
                                "sweep \"${item.title}\" → $repo: ${found.size} servers " +
                                    "(background, while playing)",
                            )
                        }
                    } finally {
                        tally.running.remove(id)
                        bumpCrossStatus()
                    }
                    publish()
                }
            }
        }
        publish()
    }

    /** The other installed extensions worth asking by title: .cs3 / .hiki /
     *  universal providers all expose search + load() + loadLinks(), and a
     *  Stremio addon does too (search → meta → stream) — so a title opened from
     *  a CloudStream plugin can also be rescued by a Stremio addon. Some groups
     *  are deliberately left out:
     *   - the origin itself;
     *   - Stremio addons when the origin IS a Stremio addon, because the main
     *     pass already asked every addon in that case;
     *   - nuvio providers entirely, since the main pass already searched them
     *     by TMDB id (they resolve without the title at all);
     *   - extensions known to sit behind a verification wall ([crossCfSkip]),
     *     which are skipped before anything is queued for them. */
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
            t == ProviderType.SKYSTREAM -> 3
            t == ProviderType.ANIYOMI -> 3
            else -> 4
        }
        val families = manager.providers.value
            .filter { p ->
                if (!p.config.enabled || p.config.id == item.providerId) return@filter false
                // An extension that answered with a Cloudflare verification wall
                // is dropped BEFORE anything is queued for it — no search slot,
                // no cold plugin load, no verdict, nothing in the progress line.
                // Skipping it here (rather than searching it and then reporting
                // the block) is the point: it is not worth searching, and its
                // block is not something to read in the server list.
                if (isCfSkipped(p.config.id)) return@filter false
                // SkyStream extensions declare their site in their manifest, so
                // a host already known to answer with a challenge can be ruled
                // out BEFORE the extension is queued — its search would go to
                // that same site. Reading the manifest is cheap (no plugin boot),
                // unlike the cold plugin load a search would cost. Other engine
                // types have no cheap "which site does this talk to" answer, so
                // for them the record is learned from the pass itself (see
                // [crossCfSkip]).
                if (p.config.type == ProviderType.SKYSTREAM) {
                    val host = runCatching {
                        com.hikari.app.skystream.SkyStreamPluginManager.siteHostOf(
                            com.hikari.app.skystream.SkyStreamPluginManager.scriptFile(
                                com.hikari.app.HikariApp.instance,
                                p.config.id.removePrefix("sky|"),
                            )
                        )
                    }.getOrNull()
                    if (host != null &&
                        com.hikari.app.net.CloudflareVerifier.isBlockedHost(host)
                    ) {
                        crossCfSkip[p.config.id] = System.currentTimeMillis()
                        return@filter false
                    }
                }
                when (p.config.type) {
                    ProviderType.CS3,
                    ProviderType.HIKARI,
                    ProviderType.UNIVERSAL,
                    // SkyStream plugins carry their OWN search + details, so a
                    // title opened anywhere else can still be found by name and
                    // its streams extracted through the same
                    // search → load → loadStreams path. They are asked like any
                    // other site-scraper family; the plugin's own opaque token
                    // stays inside the provider (see SkyStreamProvider).
                    ProviderType.SKYSTREAM -> true
                    // Aniyomi extensions carry their OWN search too (an Aniyomi
                    // source can search by title), so they are asked exactly
                    // like the other site-scraper families.
                    ProviderType.ANIYOMI -> true
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
        // Repos that have already handed this session a playable server go
        // next, ahead of the round-robin (see [crossProven]): they are the ones
        // most likely to carry the title too, and their plugin is usually still
        // loaded from the earlier lookup, so they answer in a fraction of the
        // time a cold repo needs — which is exactly what makes the SECOND
        // lookup of a title feel fast instead of like the first one again.
        for (family in families) {
            if (originType != null && family.key == originType) continue
            family.value.filter { crossProven.contains(it.config.id) }.forEach { out += it }
        }
        var round = 0
        while (out.size < CROSS_EXT_MAX_TARGETS) {
            var added = false
            for (family in families) {
                if (originType != null && family.key == originType) continue
                val p = family.value.getOrNull(round) ?: continue
                // Already put in front of the queue as a proven repo.
                if (crossProven.contains(p.config.id)) continue
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
    private fun markCrossSearchStarted(
        p: ContentProvider,
        repo: String,
        title: String,
        tally: CrossTally,
        fromCache: Boolean = false,
    ) {
        tally.asked[p.config.id] = p.config.type.groupLabel
        if (fromCache) {
            // Answered out of this session's own record (see [crossEmpty] /
            // [crossMatch]): the repo WAS consulted, so it must count as asked
            // rather than as "never reached", but nothing is searching for it,
            // so it gets no running entry and no "searching…" line.
            bumpCrossStatus()
            return
        }
        tally.running[p.config.id] = repo
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
        episode: Episode?,
        tally: CrossTally,
    ): Pair<CrossHit?, String?> {
        val repo = p.config.name.ifBlank { p.config.id }
        // Already known to need a verification wall this session: skipped
        // silently — no search slot, no "still searching" entry, no verdict.
        if (isCfSkipped(p.config.id)) return null to CROSS_VERDICT_SKIPPED
        val title = item.title.trim()
        if (title.isBlank()) return null to "no title to search for"
        // What this extension had to say BEFORE we asked it anything: if its own
        // words change while we search (a plugin that failed to load, a search
        // that blew up), that change is the reason it produced nothing — and it
        // is a very different story from "this repo does not carry the show".
        // A verification wall ALREADY on record says the same thing before we
        // start: leave this extension out of the pass entirely.
        val saidBefore = providerStreamMessage(p)
        if (saidBefore != null &&
            com.hikari.app.net.CloudflareVerifier.isVerificationMessage(saidBefore)
        ) {
            crossCfSkip[p.config.id] = System.currentTimeMillis()
            return null to CROSS_VERDICT_SKIPPED
        }
        // Queued: counted as "still searching" until the verdict lands.
        tally.running[p.config.id] = repo
        bumpCrossStatus()
        var attempt = searchBestMatch(
            p,
            title,
            item,
            CROSS_EXT_MIN_MATCH,
            episode = episode,
            onStart = { markCrossSearchStarted(p, repo, title, tally) },
            onCached = { markCrossSearchStarted(p, repo, title, tally, fromCache = true) },
        )
        // A search that THREW or TIMED OUT says nothing about the repo's
        // catalog — a cold plugin load ("the first call has to spin up its
        // runtime") is the usual cause, and the old code wrote that off as "no
        // matching title". Ask once more before giving up on this repo.
        if (attempt.best == null && attempt.why != null) {
            attempt = searchBestMatch(p, title, item, CROSS_EXT_MIN_MATCH, episode = episode)
        }
        var best = attempt.best
        if (best == null && attempt.why == null && !attempt.cachedEmpty) {
            // Repos name titles their own way ("Foo: Bar Baz", "Foo - Season 2"),
            // and searching the full string can come back empty even though the
            // repo DOES carry the show. One retry with the shortest meaningful
            // segment rescues those, instead of reporting "this repo has
            // nothing" and leaving the user without its servers.
            val variant = titleVariant(title)
            if (variant != null && !variant.equals(title, ignoreCase = true)) {
                val second = searchBestMatch(
                    p,
                    variant,
                    item,
                    CROSS_EXT_MATCH_VARIANT,
                    episode = episode,
                )
                best = second.best
                // Keep whichever attempt has something to say: a failure from
                // the retry is more informative than "the full title missed".
                if (second.why != null) attempt = second
            }
        }
        if (best == null && attempt.cachedEmpty) {
            // Answered (and remembered) "no such title" moments ago — report it
            // without re-recording a note or re-asking this repo.
            return null to "no matching title for \"$title\""
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
                if (com.hikari.app.net.CloudflareVerifier.isVerificationMessage(said)) {
                    // A verification wall, not a search result. Remember the
                    // extension and drop it silently — no note, no verdict, and
                    // no further search for it this session (see [crossCfSkip]).
                    crossCfSkip[id] = System.currentTimeMillis()
                    tally.verdict.remove(id)
                    tally.asked.remove(id)
                    return null to CROSS_VERDICT_SKIPPED
                }
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
            withTimeoutOrNull(metaTimeoutMs(p)) {
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
                withTimeoutOrNull(episodesTimeoutMs(p)) {
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
        val streamsBudget = streamsTimeoutMs(p)
        val got: List<StreamSource> = CROSS_EXT_EXTRACT_SEMAPHORE.withPermit {
            withTimeoutOrNull(streamsBudget) {
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
                    "extraction timed out after ${streamsBudget / 1000}s"
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
        eps.firstOrNull { it.number == wanted.number }?.let { return it }
        // The extension may number its rows its OWN way while naming them by the
        // show's numbering (or the other way round): a row called "Renegade
        // Immortal Ep 148" whose `number` field is a flat counter, or a row
        // called simply "148". Read the number out of the NAME and match on
        // that, so an extension that plainly carries episode 148 is not
        // reported as "has the title, but not S1E148" — the same episode the
        // user asked for, found by what the site itself calls it.
        eps.firstOrNull { episodicNumber(it.name) == wanted.number }?.let { return it }
        val wantedInName = episodicNumber(wanted.name)
        if (wantedInName != null && wantedInName != wanted.number) {
            eps.firstOrNull { episodicNumber(it.name) == wantedInName }?.let { return it }
        }
        return null
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
        ProviderType.SKYSTREAM -> com.hikari.app.skystream.SkyStreamProvider.streamErrors[p.config.id]
        ProviderType.ANIYOMI -> com.hikari.app.aniyomi.AniyomiProvider.streamErrors[p.config.id]
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
    private class SearchAttempt(val best: MediaItem?, val why: String?, val cachedEmpty: Boolean = false)

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
        /** The episode being played, when there is one. A repo entry that is a
         *  MOVIE can never be the right answer for an episode of a SERIES, and
         *  a name-only match against a different show must never be trusted —
         *  see [confidentTitleMatch]. Null for a movie lookup. */
        episode: Episode? = null,
        onStart: (() -> Unit)? = null,
        onCached: (() -> Unit)? = null,
    ): SearchAttempt = CROSS_EXT_SEARCH_SEMAPHORE.withPermit {
        // Already answered "no such title" for this exact query a moment ago
        // (see [crossEmpty]): don't spend a slot — or a cold plugin load — on
        // the same question again.
        val cacheKey = crossEmptyKey(p.config.id, query)
        val cachedAt = crossEmpty[cacheKey]
        if (cachedAt != null) {
            if (System.currentTimeMillis() - cachedAt < CROSS_EMPTY_TTL_MS) {
                // Answered out of this session's own record — still CONSULTED,
                // so the pass's progress line counts it (otherwise a pass that
                // resolved ten of eleven repos from cache read as "asked 1 of
                // 11 … done", which looks exactly like the search gave up).
                onCached?.invoke()
                return@withPermit SearchAttempt(null, null, cachedEmpty = true)
            }
            crossEmpty.remove(cacheKey)
        }
        // This extension already MATCHED this exact query minutes ago (see
        // [crossMatch]): hand the remembered entry straight back instead of
        // spending a search slot — and, for a .cs3/.hiki repo, a cold plugin
        // load — on the same question again. Extraction still runs normally, so
        // the servers are freshly resolved; only the lookup is skipped.
        crossMatch[cacheKey]?.let { hit ->
            if (System.currentTimeMillis() - hit.at < CROSS_MATCH_TTL_MS) {
                onCached?.invoke()
                return@withPermit SearchAttempt(hit.item, null)
            }
            crossMatch.remove(cacheKey)
        }
        // The search is about to RUN (a slot has been acquired). Reporting
        // "asked" any earlier counted merely-queued repos as searched (see
        // [markCrossSearchStarted]).
        onStart?.invoke()
        var timedOut = false
        var failure: String? = null
        val searchBudget = searchTimeoutMs(p)
        val results: List<MediaItem> = withTimeoutOrNull(searchBudget) {
            cancellableCatching { p.search(query, 1) }
                .onFailure { e -> failure = e.javaClass.simpleName + ": " + (e.message ?: "no message") }
                .getOrDefault(emptyList())
        } ?: run { timedOut = true; emptyList<MediaItem>() }
        if (timedOut) {
            SearchAttempt(null, "search timed out after ${searchBudget / 1000}s")
        } else if (failure != null) {
            SearchAttempt(null, "search failed: $failure")
        } else if (results.isEmpty()) {
            // The repo answered, and the answer was an empty page: it genuinely
            // has no title remotely like this one. Zero results and a FAILED
            // search used to look identical in the log (see
            // [crossExtensionSearch]). Remember the answer so the next pass over
            // the same title skips this repo entirely.
            //
            // ONLY a clean empty is remembered. A page that parsed to zero items
            // because the site handed back a challenge page, a bot wall or a
            // 5xx is not evidence that the repo lacks the title — and
            // remembering it for five minutes is how a repo that plainly DOES
            // carry the show dropped out of the next lookup's server list (the
            // reported "every attempt shows different servers"). When the
            // extension has something to say about itself, that is the tell.
            if (providerStreamMessage(p) == null) {
                crossEmpty[cacheKey] = System.currentTimeMillis()
            }
            SearchAttempt(null, null)
        } else {
            // Scored against the REAL title, never against the shortened query,
            // so a variant can only ever confirm a genuine match.
            val scored = results.map { it to titleScore(item.title, item.year, it) }
            // A score alone is NOT enough to decide that a repo's entry is the
            // title the user asked to play: see [confidentTitleMatch].
            val best = scored
                .filter { it.second >= minMatch && confidentTitleMatch(item, it.first, episode) }
                .maxByOrNull { it.second }?.first
            if (best != null) {
                // Remember the match for the rest of the session ([crossMatch]),
                // and drop any "no such title" note from an earlier pass — the
                // repo's answer has changed.
                crossMatch[cacheKey] = CrossMatch(best, System.currentTimeMillis())
                crossEmpty.remove(cacheKey)
                SearchAttempt(best, null)
            } else SearchAttempt(
                null,
                "${results.size} search result(s), none of them \"$query\" " +
                    "(best match ${scored.maxOf { it.second }}/$minMatch)",
            )
        }
    }

    /**
     * Is [candidate] confidently the SAME title as [wanted]?
     *
     * [titleScore] answers "how similar are these two strings", which is the
     * right question for ORDERING a repo's own fuzzy search page but the wrong
     * one for deciding that a repo's entry IS the title the user asked to play.
     * A token-overlap score could clear [CROSS_EXT_MIN_MATCH] between two
     * completely different shows that happen to share a word ("Renegade
     * Immortal" vs "Immortal Samsara", a donghua vs a 2024 Indian serial), and
     * the player then started a different film off that repo — the reported "I
     * asked for Renegade Immortal episode 148 and it played some Bollywood
     * movie from a different server".
     *
     * The rule here is deliberately strict and structural:
     *  - when the two entries are known to be different media KINDS (a MOVIE
     *    entry while an episode of a SERIES is being played), reject — a film
     *    does not have an episode 148;
     *  - one title's significant words must CONTAIN the other's, so "renegade
     *    immortal" matches "Renegade Immortal (Xian Ni)" and "Renegade Immortal
     *    Season 1", but never "Immortal Samsara";
     *  - the FIRST significant word of one title must appear in the other, so
     *    "One Piece" cannot match "Piece of Cake";
     *  - when both years are known and differ by more than one, only an exact
     *    normalised match is accepted (a remake sharing a title is not the same
     *    show).
     *
     * Over-strict is the intended direction: a repo that is wrongly rejected
     * only costs one missing server, while a repo wrongly accepted plays the
     * wrong video — which is what the user reported and asked to be prevented.
     * Only the cross-extension pass consults this; the ORIGIN provider's own
     * search results are never filtered by it.
     */
    private fun confidentTitleMatch(
        wanted: MediaItem,
        candidate: MediaItem,
        episode: Episode?,
    ): Boolean {
        if (wanted.type != MediaType.UNKNOWN && candidate.type != MediaType.UNKNOWN &&
            wanted.type != candidate.type
        ) {
            // An episode of a series can never live on a MOVIE entry, and a
            // movie can never be a SERIES entry.
            return false
        }
        if (episode != null && wanted.type == MediaType.SERIES &&
            candidate.type == MediaType.MOVIE
        ) {
            return false
        }
        val a = normalizeTitle(wanted.title)
        val b = normalizeTitle(candidate.title)
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        val ta = a.split(' ').filter { it.length > 2 }
        val tb = b.split(' ').filter { it.length > 2 }
        if (ta.isEmpty() || tb.isEmpty()) return false
        val sa = ta.toSet()
        val sb = tb.toSet()
        // One set must contain the other: "renegade immortal" ⊂ "renegade
        // immortal xian ni". A mere intersection ("immortal" shared by
        // "Renegade Immortal" and "Immortal Samsara") is NOT a match.
        if (!(sa.containsAll(sb) || sb.containsAll(sa))) return false
        // …and the first significant word must survive, so the match is anchored
        // to the head of the title rather than to a shared tail.
        if (!sb.contains(ta.first()) && !sa.contains(tb.first())) return false
        val yb = candidate.year
        if (wanted.year != null && yb != null && kotlin.math.abs(wanted.year - yb) > 1) {
            return false
        }
        return true
    }

    /** The episode number a row's own NAME carries ("Ep 148", "Episode 148",
     *  "E148", "第148集"), or null when the name says nothing about numbering.
     *  Used to rescue an extension that numbers its rows its own way (see
     *  [matchCrossEpisode]). */
    private fun episodicNumber(name: String?): Int? {
        val n = name?.trim().orEmpty()
        if (n.isEmpty()) return null
        EPISODE_IN_NAME.find(n)?.let { return it.groupValues[1].toIntOrNull() }
        CJK_EPISODE_IN_NAME.find(n)?.let { return it.groupValues[1].toIntOrNull() }
        // A row whose name is nothing but a number IS that episode number.
        if (n.length <= 4 && n.all { it.isDigit() }) return n.toIntOrNull()
        // A row that ENDS in a bare number ("Renegade Immortal 148"): on an
        // episode row, that number can only be the episode.
        val tail = n.split(Regex("[\\s_\\-–—]+")).lastOrNull()?.trim()
        if (tail != null && tail.length in 1..4 && tail.all { it.isDigit() }) {
            return tail.toIntOrNull()
        }
        return null
    }

    private val EPISODE_IN_NAME =
        Regex("(?i)\\b(?:ep|episode|e)\\s*\\.?\\s*(\\d{1,4})\\b")
    private val CJK_EPISODE_IN_NAME = Regex("第\\s*(\\d{1,4})\\s*[集话話]")

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
            ProviderType.SKYSTREAM -> com.hikari.app.skystream.SkyStreamProvider.streamErrors
            ProviderType.ANIYOMI -> com.hikari.app.aniyomi.AniyomiProvider.streamErrors
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
        val originProvider = manager.byId(item.providerId)
        var result = originProvider
            ?.let {
                withTimeoutOrNull(metaForTimeoutMs(it)) {
                    cancellableCatching { it.getMeta(item) }.getOrDefault(item)
                }
            }
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
            val eps = (withTimeoutOrNull(episodesForTimeoutMs(p)) {
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
        // Last resort, for ANY series whose own list came back empty: borrow the
        // episode list from an installed extension that scrapes it from its
        // site. Those lists come straight from the source site, so they are the
        // ground truth when the databases disagree about a donghua's episode
        // count.
        //
        // This used to run ONLY when the item's origin was a Nuvio/TMDB provider,
        // so an item opened from a site-scraper whose own episode list failed —
        // a cold Aniyomi APK, a plugin that answered an empty page, a repo whose
        // detail page layout changed — showed "Episodes (0) — No episode list
        // available" while a dozen other installed extensions carried the show.
        // That is the reported "some aniyomi extension shows no episode on
        // series".
        if (item.type == MediaType.SERIES) {
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
     * this title and borrow the richest episode list one of them returns.
     *
     * Candidates are run in PARALLEL and in trust order (the origin's own engine
     * family first, then extensions that have already produced servers this
     * session — see [crossProven]), each with the real per-provider budgets the
     * cross pass uses ([searchTimeoutMs] / [episodesTimeoutMs]) rather than a
     * flat 12s that a cold Aniyomi APK class load can never meet. As soon as one
     * of them answers, the rest get [EPISODES_FALLBACK_SETTLE_MS] to contribute
     * a longer list, so the detail page is never held for the whole sweep just
     * because a good answer arrived first.
     *
     * The title match is [confidentTitleMatch]: an extension carrying a
     * DIFFERENT show whose name merely starts with this one's must never donate
     * its episode list to this title.
     */
    private suspend fun episodesFromExtensions(item: MediaItem): List<Episode>? {
        if (item.type != MediaType.SERIES) return null
        val originType = manager.byId(item.providerId)?.config?.type
        val candidates = manager.providers.value
            .filter { p ->
                p.config.enabled &&
                    p.config.id != item.providerId &&
                    !isCfSkipped(p.config.id) &&
                    when (p.config.type) {
                        ProviderType.CS3,
                        ProviderType.HIKARI,
                        ProviderType.UNIVERSAL,
                        ProviderType.SKYSTREAM,
                        ProviderType.ANIYOMI -> true
                        else -> false
                    }
            }
            .sortedWith(
                compareBy(
                    { p: ContentProvider ->
                        when {
                            originType != null && p.config.type == originType -> 0
                            crossProven.contains(p.config.id) -> 1
                            else -> 2
                        }
                    },
                    { p: ContentProvider -> if (crossProven.contains(p.config.id)) 0 else 1 },
                )
            )
            .take(EPISODES_FALLBACK_TARGETS)
        if (candidates.isEmpty()) return null

        val budget = EPISODES_FALLBACK_BUDGET_MS
        val started = System.currentTimeMillis()
        val found = java.util.concurrent.atomic.AtomicReference<List<Episode>?>(null)
        val remaining = java.util.concurrent.atomic.AtomicInteger(candidates.size)
        val sweep = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var settleUntil = 0L
        try {
            for (p in candidates) {
                sweep.launch {
                    try {
                        val list = runCatching { episodesFromOneExtension(p, item) }.getOrNull()
                        if (list != null) {
                            // Keep the RICHEST list seen: a repo that carries the
                            // whole show beats one that only mirrors the season
                            // currently airing, whichever answers first.
                            while (true) {
                                val cur = found.get()
                                if (cur != null && cur.size >= list.size) break
                                if (found.compareAndSet(cur, list)) break
                            }
                        }
                    } finally {
                        remaining.decrementAndGet()
                    }
                }
            }
            while (true) {
                val now = System.currentTimeMillis()
                if (remaining.get() <= 0) break
                if (found.get() != null) {
                    if (settleUntil == 0L) settleUntil = now + EPISODES_FALLBACK_SETTLE_MS
                    if (now >= settleUntil) break
                }
                if (now - started >= budget) break
                kotlinx.coroutines.delay(100)
            }
        } finally {
            sweep.cancel()
        }
        return found.get()
    }

    /** One extension's contribution to [episodesFromExtensions]: find the entry
     *  it holds for this title (strictly — see [confidentTitleMatch]) and return
     *  its episode list when it has a real one. Never throws, never reports a
     *  one-episode stub. */
    private suspend fun episodesFromOneExtension(
        p: ContentProvider,
        item: MediaItem,
    ): List<Episode>? {
        val hits = withTimeoutOrNull(searchTimeoutMs(p)) {
            cancellableCatching { p.search(item.title, 1) }.getOrDefault(emptyList())
        } ?: return null
        val match = hits
            .filter { confidentTitleMatch(item, it, null) }
            .maxByOrNull { titleScore(item.title, item.year, it) }
            ?: return null
        val eps = withTimeoutOrNull(episodesTimeoutMs(p)) {
            cancellableCatching { p.getEpisodes(match) }.getOrNull()
        } ?: return null
        if (eps.size < 2) return null
        return eps.sortedWith(compareBy({ it.season }, { it.number }))
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

    /** Drops every cached result — used when the language TMDB metadata is
     *  fetched in changes, since the titles in here were localized under the old
     *  one. See [com.hikari.app.HikariApp.onContentLanguageChanged]. */
    fun clear() {
        synchronized(map) { map.clear() }
    }
}
