package com.hikari.app.data

import com.hikari.app.HikariApp
import com.hikari.app.cs3.Cs3MainApiProvider
import com.hikari.app.net.CloudflareVerifier
import com.hikari.app.net.NetTuning
import com.hikari.app.nuvio.EpisodeTitles
import com.hikari.app.nuvio.NuvioScraper
import com.hikari.app.providers.ContentProvider
import com.hikari.app.providers.HikariProviderAdapter
import com.hikari.app.providers.IptvProvider
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

/**
 * How wide a lookup is allowed to search — Settings → Playback → Server search.
 *
 * [allExtensions] is the app's long-standing behaviour and the default: a title
 * is searched across every installed extension at once, so whichever repo has a
 * working server wins, and the player's "Select server" list gathers what all of
 * them found.
 *
 * Off, a lookup is confined to the ONE extension the title was opened from —
 * the CloudStream model, where a film plays from the repo you picked and its own
 * hosts, and nothing else is contacted: no cross-extension search, no background
 * sweep, no episode list borrowed from another site. For someone who installed a
 * hundred extensions and wants a title to play from the one they chose (and not
 * spend a minute asking the other ninety-nine), that is the honest switch.
 *
 * A process-wide flag rather than a parameter because the pass, the sweep, the
 * episode fallback and the player's status line all have to agree on it within
 * one lookup, and only the store knows the user's choice. Mirrored from the
 * store at startup and on every change (see HikariApp), so the search never has
 * to await DataStore mid-pass.
 *
 * [exceptions] is the third state between those two, for the person who wants
 * "only this extension" almost all of the time but one or two repos always
 * asked. It holds the ids of the extensions picked as exceptions (empty when the
 * switch is off), and the rule is:
 *
 *  - a title opened anywhere ELSE is searched through its own extension AND the
 *    exception extensions, even though [allExtensions] is off. So playing a film
 *    from 1Shows also asks the two repos the user marked, and their servers
 *    appear in the same list;
 *  - a title opened FROM an exception extension plays from THAT extension alone,
 *    even though it is an exception. This is the point of marking it: the repo
 *    keeps its own catalogue to itself and is never mixed into a search started
 *    inside it.
 */
object SearchScope {
    @Volatile
    var allExtensions: Boolean = true

    /** Extension ids that are always asked for servers (see the class note). */
    @Volatile
    var exceptions: Set<String> = emptySet()

    /** True when [id] is one of the extension ids marked as an exception. */
    fun isException(id: String?): Boolean = id != null && id in exceptions
}

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
            /**
             * The pass's PRIMARY targets — the extension the title was opened
             * from and every nuvio engine — kept apart from the cross-repo maps
             * above, because the two are counted for different things: the pass's
             * own "cross done" line must stay a count of the OTHER repos, while
             * everything the user READS has to include the primary work too.
             *
             * That distinction was the bug behind "it says the search is done and
             * the nuvio servers (or the whole nuvio tab) are missing": a nuvio
             * engine booting its QuickJS runtime is not a cross repo, so it was
             * in none of these maps — the chooser's line counted 140 cross repos
             * to zero, announced "done", and the nuvio engines answered (or were
             * cut off) afterwards, invisibly. Now every provider whose answer the
             * player is waiting for moves these maps, so "still searching" is
             * true while ANY of them is still working.
             */
            val primaryRunning = ConcurrentHashMap<String, String>()
            val primaryAsked = ConcurrentHashMap<String, String>()
            val primaryFound = ConcurrentHashMap<String, String>()
            /** Why each installed extension was left out of THIS pass's target
             *  list (see [crossExtensionTargets]). Held on the tally rather than
             *  in one shared field: two passes for two titles overlap all the
             *  time, and a shared field let one pass print the other's reasons —
             *  which is how a target list that had lost 100 repos could report a
             *  reason list that accounted for none of them. */
            @Volatile
            var filterReasons: Map<String, Int> = emptyMap()
            /** When this tally last changed at all — see
             *  [crossStatusQuietForMs]. */
            @Volatile
            var changedAt: Long = System.currentTimeMillis()
        }

        /** The tally of the newest pass — the one a summary should describe. */
        @Volatile
        var crossTally = CrossTally()
            private set

        /** Makes [tally] the one a summary should describe. Used by a pass that
         *  has to build its tally BEFORE it knows whether it will run at all
         *  (the target list records into it — see [CrossTally.filterReasons]),
         *  so a lookup that returns early cannot blank the numbers the chooser
         *  is reading. */
        fun publishCrossTally(tally: CrossTally) {
            crossTally = tally
        }

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

        /**
         * How long such an answer is trusted. Deliberately short: it exists to
         * make the SECOND lookup of the same title within a minute or two cheap,
         * not to be a lasting verdict on a repo's catalogue. A blank page that
         * said nothing about itself was enough to create one of these, and every
         * minute it lives is a minute a repo that DOES carry the title is
         * silently missing from the server list — the reported "the same film
         * finds a handful of servers one time and a dozen the next". A pass that
         * comes back empty re-asks these repos regardless (see the empty-result
         * block in [streamsForInner]).
         */
        const val CROSS_EMPTY_TTL_MS = 3 * 60 * 1000L

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

        /**
         * How the last target-list build split the installed extensions, one
         * count per exclusion reason ("disabled", "origin", "cf-skip", "hung",
         * "family(Nuvio)", …) — read from the PASS's own tally ([CrossTally.
         * filterReasons]), never from a shared field.
         *
         * Logged at the start of every pass next to the target count, because
         * "the cross pass went from 245 targets to 137 between two taps and the
         * second one found a fraction of the servers" has exactly one honest
         * answer — WHICH filter dropped those repos — and without this line it
         * can only be guessed at from a shared log.
         */
        fun providerCounts(list: List<ContentProvider>): String = list
            .groupingBy { it.config.type.groupLabel }
            .eachCount()
            .entries
            .sortedBy { it.key }
            .joinToString(",") { "${it.key}=${it.value}" }

        /** Verdict sentinel meaning "this extension was skipped — say nothing
         *  about it anywhere". Never shown, never counted. */
        const val CROSS_VERDICT_SKIPPED = "\u0000skipped"

        /**
         * Extensions whose plugin has STOPPED COMING BACK this session:
         * `provider id -> the time we noticed`.
         *
         * A .cs3/.hiki/SkyStream/Aniyomi plugin is arbitrary third-party code,
         * and one of them occasionally wedges — a deadlock in its own
         * `synchronized` block, a JS engine that never returns, a socket that
         * ignores every timeout. The call's coroutine can then never be
         * cancelled, because cancellation is only observed at a suspension
         * point and it is parked inside a synchronous call.
         *
         * That is survivable on its own (it costs one coroutine), but it used to
         * be fatal for the whole search: the wedged call also held a slot in one
         * of the provider [RefundableGate]s, and slots are what the pass, the
         * sweep and every later lookup share. Each hang permanently consumed
         * one, so a session's server list got smaller and smaller — "sometimes
         * it only finds nuvio", "it stopped at 87 of 159", "it just sits there
         * with no sources".
         *
         * Two things are done about it now, both of them here:
         *  (1) the gate slot is REFUNDED the moment a hang is detected (see
         *      [RefundableGate.hang]), so the rest of the queue keeps moving;
         *  (2) the extension is dropped from every later pass and sweep this
         *      session (it is not asked, not counted as "no such title", and
         *      reported as skipped once).
         */
        val crossHung = ConcurrentHashMap<String, Long>()

        /** How long a provider call may be in flight before we call it wedged.
         *  Comfortably past the widest provider timeout there is (an Aniyomi
         *  extraction is allowed 150s), so a merely slow extension is never
         *  mistaken for a dead one. */
        const val HANG_AFTER_MS = 200_000L

        /** The same idea for a SEARCH, where the call's own budget is ~20s: a
         *  search still in flight after this is not slow, it is stuck, and it
         *  must not sit in the "N still searching" line forever (see
         *  [InFlight.hangAfterMs]). */
        const val SEARCH_HANG_AFTER_MS = 90_000L

        /** How long an extension that stopped answering is left out of the
         *  search. It used to be "for the whole session", which is why the ONE
         *  thing that reliably recovered a wedged search — asking the same repos
         *  again (the user's own workaround: back out, press Play) — could not
         *  happen by itself: the repos the pass had given up on were blacklisted
         *  before the retry could reach them. A wedge is a transient state of a
         *  plugin runtime, and the user's own log shows the same repos answering
         *  perfectly minutes later, so the blacklist now expires. */
        const val HUNG_TTL_MS = 120_000L

        /** …and for a meta/episode fetch, whose own budget is 20-40s. */
        const val DETAIL_HANG_AFTER_MS = 120_000L

        /**
         * What a provider's LAST call actually did: `"no servers"` when it
         * answered and the answer was an empty list, a `"✗ …"` reason when it
         * timed out, threw, or never came back at all (absent once it hands over
         * servers, and absent when it never got the chance to run).
         *
         *  Recorded by [fetchStreams] for EVERY provider, not only the origin.
         *  "This repo has nothing for this episode" and "this call never came
         *  back" read identically in every server list and every summary, and
         *  the difference is the whole of the reported "the first tap shows
         *  every engine except nuvio, the second tap shows nuvio": a nuvio
         *  engine whose cold QuickJS boot ran past the pass's deadline came back
         *  with an empty list, which used to be recorded as a plain answer and
         *  never asked again. It decides what the pass's teardown re-asks in the
         *  background — a cold or timed-out provider is worth asking again, a
         *  provider that plainly answered "nothing" is not.
         */
        val providerOutcome = ConcurrentHashMap<String, String>()

        /** True while [providerId] should be left out of the search entirely —
         *  i.e. until its wedge expires (see [HUNG_TTL_MS]). After that it is
         *  asked again like any other extension. */
        fun isHung(providerId: String): Boolean {
            val at = crossHung[providerId] ?: return false
            if (System.currentTimeMillis() - at < HUNG_TTL_MS) return true
            crossHung.remove(providerId, at)
            return false
        }

        /**
         * A counting gate for one class of provider work (search / detail /
         * extract) that can have a slot REFUNDED when its holder is wedged.
         *
         * A plain [Semaphore] is the wrong tool here: `acquire()` hands out a
         * permit that is released in a `finally`, and a `finally` cannot run
         * while the thread is parked inside a plugin that will never return — so
         * one wedged extension permanently shrank the gate for the rest of the
         * session. [hang] adds a permit back for that case. The refund is capped
         * at [limit] so a pathological run can never turn the gate into an
         * unbounded stampede.
         */
        class RefundableGate(val limit: Int) {
            private val sem = Semaphore(limit)
            private val held = java.util.concurrent.atomic.AtomicInteger(0)
            private val refunded = java.util.concurrent.atomic.AtomicInteger(0)

            suspend fun acquire() {
                sem.acquire()
                held.incrementAndGet()
            }

            fun release() {
                held.decrementAndGet()
                sem.release()
            }

            /** Give the gate back a slot whose holder will never release it. */
            fun hang() {
                if (refunded.incrementAndGet() > limit) {
                    refunded.decrementAndGet()
                    return
                }
                sem.release()
            }

            /** Slots handed out but not yet returned (hung ones included) —
             *  for the diagnostic log line. */
            fun heldCount(): Int = held.get()
        }

        /**
         * One provider call in flight, for the hang watchdog:
         * `per-call token -> (the repo it belongs to, the gate it holds, when it
         * started)`.
         *
         * The KEY is a unique token per CALL, not the provider id. Keying by
         * provider — as this did — meant a repo asked again (a fresh pass, the
         * sweep, a re-extraction) left its wedged call's entry OVERWRITTEN and
         * therefore un-refundable: the gate lost that slot permanently, and
         * after enough wedges it lost so many that searches stopped starting at
         * all. The repo is still carried inside the entry, so the progress line
         * and the "stopped responding" note are unchanged.
         */
        class InFlight(
            /** The repo this call belongs to, for the progress line and the
             *  "stopped responding" note. The MAP is keyed by a per-call token
             *  instead (see [gated]). */
            val providerId: String,
            val gate: RefundableGate,
            val at: Long,
            /** How long this KIND of call may be in flight before it is called
             *  wedged. A search is given minutes less than an extraction: a
             *  search's own budget is ~20s, so a search still in flight after
             *  [SEARCH_HANG_AFTER_MS] is not slow, it is stuck — and it holds a
             *  slot AND a line in the chooser's "N still searching" count for as
             *  long as nobody says so (the reported "it stuck on 16 still
             *  searching and never went further"). An Aniyomi EXTRACTION, on the
             *  other hand, may legitimately take 150s. */
            val hangAfterMs: Long,
        ) {
            /** Set by the watchdog when it gives this call's slot back, so the
             *  call's own `finally` does not refund it a second time if it ever
             *  does come back. */
            val refunded = java.util.concurrent.atomic.AtomicBoolean(false)
        }

        val inFlight = ConcurrentHashMap<String, InFlight>()

        /** Hands every provider call its own in-flight token (see [gated]). */
        private val callSeq = java.util.concurrent.atomic.AtomicLong()

        @Volatile
        private var hangWatchdog: kotlinx.coroutines.Job? = null

        /**
         * Starts the (single) hang watchdog. It is the only thing that can
         * notice a wedged plugin, because nothing inside such a call ever runs
         * again: every [HANG_AFTER_MS] it looks at [inFlight] and refunds the
         * slot of anything older than that.
         */
        fun ensureHangWatchdog() {
            if (hangWatchdog?.isActive == true) return
            hangWatchdog = HikariApp.instance.appScope.launch {
                while (true) {
                    kotlinx.coroutines.delay(15_000L)
                    val now = System.currentTimeMillis()
                    for ((token, call) in inFlight) {
                        if (now - call.at < call.hangAfterMs) continue
                        // Take the entry first: this is what makes the refund
                        // happen exactly once per wedged call.
                        if (call.refunded.compareAndSet(false, true)) {
                            call.gate.hang()
                            inFlight.remove(token, call)
                            val id = call.providerId
                            // Out of the LIVE progress line as well as out of
                            // the queue. A wedged call used to keep its place in
                            // the pass's running map until the pass itself ended,
                            // so the chooser read "16 still searching" and could
                            // not move — the count was waiting on a plugin that
                            // was never coming back. The repo is reported as
                            // skipped instead (see [crossHung]).
                            val tally = crossTally
                            if (tally.running.remove(id) != null) {
                                tally.verdict[id] = (tally.verdict[id]?.substringBefore(" — ")
                                    ?: id) + " — stopped responding"
                                bumpCrossStatus()
                            }
                            if (crossHung.putIfAbsent(id, now) == null) {
                                com.hikari.app.data.Logs.log(
                                    "Search",
                                    "extension '" + id + "' has not answered in " +
                                        (call.hangAfterMs / 1000) + "s — its slot has been " +
                                        "given back, it is off the progress line, and it is " +
                                        "left out of the search for a while " +
                                        "(see HUNG_TTL_MS)",
                                )
                            }
                        }
                    }
                }
            }
        }

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

        /** How long a video's known servers stay on the ledger. Deliberately
         *  long: within a watching session the user replays, picks another
         *  server, jumps episodes and comes back, and every one of those
         *  lookups must find at least the servers the first one did. A signed
         *  URL that has gone stale only costs one failed failover, while a
         *  server that vanishes from the list makes the app look broken. */
        const val REMEMBERED_STREAMS_TTL_MS = 60 * 60 * 1000L

        /**
         * A whole pass that found fewer than this many servers, while repos were
         * answered out of the session's "no such title" record, is treated as
         * UNFINISHED rather than as an answer: those repos are asked for real in
         * the background and their servers stream into the same list (see the
         * end of [streamsForInner]).
         *
         * Six, not one: the report this comes from is "it said 2 servers and the
         * search finished, then on the fourth tap it found 36" — a list that
         * small across 250+ repos is a memory answer, not a catalogue. A title
         * that genuinely has four servers still grows to at most the same set,
         * so the cost of the extra background pass is one sweep of the handful
         * of repos that answered from memory.
         */
        const val CROSS_THIN_RESULT = 6

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
            /** How many budget-rounds this sweep has run (see [runSweep]): a
             *  round that ran out of time hands its unasked repos to the next
             *  one, and this is what bounds that chain. */
            @Volatile
            var rounds: Int = 0
            /** Ask for real, ignoring the session's "no such title" record
             *  (see [startSweepIfNeeded]). */
            @Volatile
            var ignoreEmptyRecord: Boolean = false
            /** When this sweep last actually got somewhere — a repo answered, a
             *  server landed, a round started. The status line and the player's
             *  "still searching" state are derived from a sweep that is
             *  genuinely working, not merely from a job that still exists, so a
             *  sweep that has gone quiet (its repos all parked in calls that
             *  cannot come back) can no longer hold "still searching" on screen
             *  forever. See [SWEEP_STALE_MS]. */
            @Volatile
            var lastProgressAt: Long = System.currentTimeMillis()
            /** Provider ids this sweep has finished with — answered, failed,
             *  skipped, or consulted out of the session's own record. Whatever
             *  is NOT in here when the sweep ends goes back on the unfinished
             *  ledger (see [PendingWork]), which is what makes "no work is ever
             *  dropped" true even when the sweep is cancelled or cut short. */
            val answered: MutableSet<String> = ConcurrentHashMap.newKeySet()
        }

        /** One unit of background work: a provider to SEARCH by title (the
         *  cross-extension kind) or one to ASK DIRECTLY (a pass's primary
         *  targets — the title's own extension and the nuvio engines, which
         *  resolve from an id and need no title search at all). Keeping both on
         *  one queue is what lets ONE sweep finish a pass's leftovers whichever
         *  family they came from: hikari, cloudstream, skystream, aniyomi,
         *  stremio or nuvio. */
        class SweepUnit(val provider: ContentProvider, val byTitle: Boolean)

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

        /**
         * Work a pass could not finish, kept until it has really been asked —
         * `title+episode key -> the provider ids still owing an answer`.
         *
         * This is the "nothing is ever dropped" ledger. Before it existed, the
         * ONLY thing a pass could do with unfinished work was hand it to a sweep
         * right there and then, and if that sweep was refused (the ceiling is
         * [MAX_PARALLEL_SWEEPS]) the work simply evaporated: the
         * repos were never asked, no verdict was written for them, and the only
         * way to get their servers was for the user to leave and press Play
         * again. Now every hand-off is recorded here first, a refused sweep
         * leaves the record in place, and the next sweep that gets a free slot
         * (see [ContentRepository.drainSweeps] — called whenever one ends) picks
         * it up. A later lookup of the same video resets the entry's try count,
         * so a user-initiated retry always re-asks everything that never
         * answered.
         */
        class PendingWork(
            val item: MediaItem,
            val episode: Episode?,
            /** Provider ids to SEARCH by title (the cross-extension kind). */
            val byTitle: MutableSet<String> = ConcurrentHashMap.newKeySet(),
            /** Provider ids to ASK DIRECTLY (the pass's primary targets: the
             *  title's own extension and the nuvio engines, which resolve from
             *  an id and need no title search — see [streamsForInner]). */
            val direct: MutableSet<String> = ConcurrentHashMap.newKeySet(),
            /** Ask these for real, ignoring the session's "no such title"
             *  record (see [startSweepIfNeeded]). */
            @Volatile var ignoreEmptyRecord: Boolean = false,
            /** How many sweeps have already picked this entry up. Automatic
             *  retries stop at [SWEEP_MAX_TRIES]; a fresh pass resets it. */
            @Volatile var tries: Int = 0,
            @Volatile var at: Long = System.currentTimeMillis(),
        )

        val pendingWork = ConcurrentHashMap<String, PendingWork>()

        /**
         * Videos whose background sweep is being HELD by the player — the sweep
         * may not start until the video is actually playing.
         *
         * The sweep is the "keep asking every installed extension" half of a
         * pass, and it is deliberately loud: it keeps the picker's status line
         * alive and it cold-starts plugin runtimes. Starting it the instant a
         * pass ended meant every play was accompanied by a background extension
         * search racing the buffering video — the user sees "searching
         * extensions…" on the LOADING screen, where what they are waiting for is
         * the video, not a search. The servers it finds are only ever added to a
         * list that is already playing from, so nothing is lost by waiting.
         *
         * Held from the moment the player is launched ([holdSweepFor], called by
         * [StreamsLive.holdSweep]) and released on the first frame that really
         * renders ([releaseHeldSweepKey]). A wall-clock backstop releases it
         * regardless, so a video that never starts (no servers at all, a dead
         * link, a player torn down early) cannot strand its unfinished work.
         */
        private val heldSweepKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /** How long a held sweep waits for playback to begin before it starts
         *  anyway. Generous: it only has to cover the load of a video the user
         *  is actually waiting to watch. */
        const val SWEEP_HOLD_MAX_MS = 30_000L

        /** What a held sweep needs in order to run once it is released: the
         *  video, the snapshot the pass had already found, and everyone who
         *  asked to hear about new results. Declared at file level (see
         *  [HeldSweep]) so both this object and the repository can name it. */
        private val heldSweeps = ConcurrentHashMap<String, HeldSweep>()

        /** Hold this video's background sweep until the player releases it (see
         *  [heldSweepKeys]). Returns the key to release it with. */
        fun holdSweepFor(item: MediaItem, episode: Episode?): String {
            val key = streamsRememberedKey(item, episode)
            if (!heldSweepKeys.add(key)) return key
            HikariApp.instance.appScope.launch {
                delay(SWEEP_HOLD_MAX_MS)
                releaseHeldSweepKey(key)
            }
            return key
        }

        /** Stop this video's background sweep for now AND hold whatever it has
         *  not finished. Called when the user leaves the player: a nuvio search
         *  cold-starts a QuickJS engine per provider, and a title the user has
         *  stopped watching must not keep doing that while they browse the rest
         *  of the app (the reported "it stays laggy for a few seconds after I
         *  come back from the player").
         *
         *  Nothing is lost by stopping it: every provider the cancelled sweep had
         *  not really asked goes back on the unfinished-work ledger (see
         *  [notePendingWork]), and the hold is released by the next play of the
         *  same title — or by [SWEEP_HOLD_MAX_MS] — which re-asks them. */
        fun pauseSweepFor(item: MediaItem, episode: Episode?): String {
            val key = holdSweepFor(item, episode)
            sweeps[key]?.job?.cancel()
            // …and hold every OTHER background search off too, for the moment
            // the app needs to put its own screens back together (the player's
            // activity is gone, the previous screen is being re-created, Home is
            // reloading). Cancelling this title's sweep alone left the phone
            // running everything else while it did that.
            quietFor(POST_PLAYER_QUIET_MS)
            return key
        }

        /** Start whatever [holdSweepFor] held back for this key. Safe to call
         *  twice, and safe to call for a key that was never actually held. */
        fun releaseHeldSweepKey(key: String?) {
            if (key == null) return
            if (!heldSweepKeys.remove(key)) return
            val held = heldSweeps.remove(key) ?: return
            // Any instance will do: every sweep's state is process-wide (the
            // maps above) and a ContentRepository holds nothing but its manager.
            ContentRepository(HikariApp.instance.providers).releaseHeldSweep(held)
        }

        // ---- The foreground/background gate ------------------------------
        //
        // A source PASS is the search the user is waiting on. A SWEEP is
        // background work — "keep asking every installed extension" while the
        // video plays. The two are not equal, and until this existed they
        // competed for the same engines: the user's own log shows one title's
        // pass running beside the sweeps of two others, so a nuvio engine that
        // answers in a second or two in the reference client spent its whole
        // budget QUEUED behind background work ("NetMirror: no answer in 92s",
        // three times over, from three different searches).
        //
        // So a sweep never starts a provider while a pass is running, and it
        // also stands down for a short moment after the player closes, while the
        // app rebuilds its own screens — which is what "it stays laggy for a few
        // seconds after I come back from the player" is made of.
        //
        // Nothing is lost by waiting: the sweep's own budget clock is paused
        // with it (see [runSweep]) and every provider it never got to stays on
        // the unfinished-work ledger for the next slot.
        private val activePasses = java.util.concurrent.atomic.AtomicInteger(0)

        /** Wall-clock instant before which background sweeps stay parked. */
        private val quietUntilMs = java.util.concurrent.atomic.AtomicLong(0L)

        /** How long the background searches stand down while the app puts its
         *  own screens back together after the player closes. */
        const val POST_PLAYER_QUIET_MS = 8_000L

        /** One more pass is searching: background work stands down. */
        fun enterForegroundPass() {
            activePasses.incrementAndGet()
        }

        /** The pass is over (finished, thrown or cancelled): background work may
         *  go again. */
        fun exitForegroundPass() {
            activePasses.decrementAndGet()
        }

        /** Park every background sweep for [ms] from now (see [pauseSweepFor]). */
        fun quietFor(ms: Long) {
            val until = System.currentTimeMillis() + ms
            if (quietUntilMs.get() < until) quietUntilMs.set(until)
        }

        /** True while a pass is searching, or while the quiet window is open. */
        private fun foregroundBusy(): Boolean =
            activePasses.get() > 0 || System.currentTimeMillis() < quietUntilMs.get()

        /**
         * Waits until no pass is running and the quiet window has passed;
         * returns how long that took (0 when it never had to wait), so a caller
         * with a clock of its own can keep it honest.
         *
         * Called by the sweep's worker loop before each provider, with [onWait]
         * refreshing the caller's own "still making progress" timestamp, so a
         * sweep that is deliberately waiting is never mistaken for a wedged one.
         */
        private suspend fun awaitBackgroundClearance(onWait: () -> Unit = {}): Long {
            if (!foregroundBusy()) return 0L
            val started = System.currentTimeMillis()
            while (foregroundBusy()) {
                onWait()
                kotlinx.coroutines.delay(150)
            }
            return System.currentTimeMillis() - started
        }

        /** How many automatic sweeps may retry one video's unfinished work
         *  before it is left for the next real lookup. Each try spends its own
         *  per-provider budget, so this is what stops a provider that is wedged
         *  (never answers, ever) from keeping a sweep — and a battery — busy in
         *  a loop. A lookup the USER triggers always starts the count again. */
        const val SWEEP_MAX_TRIES = 3

        /** How long an unfinished-work record is kept. Past this the title has
         *  been left alone long enough that the servers it might find are stale
         *  (they are signed links), and the next lookup will rebuild it. */
        const val SWEEP_PENDING_TTL_MS = 10 * 60 * 1000L

        /** Record unfinished work for this video. Never a no-op: whatever lands
         *  here is re-asked by the next sweep for the video, or by the drain a
         *  finished sweep runs — see [PendingWork]. */
        fun notePendingWork(
            item: MediaItem,
            episode: Episode?,
            byTitle: kotlin.collections.Collection<String>,
            direct: kotlin.collections.Collection<String>,
            ignoreEmptyRecord: Boolean,
            /** True for a hand-off from a pass the user actually started: it
             *  clears the retry count, because "the user asked again" is exactly
             *  the case an automatic-retry ceiling must not block. */
            resetTries: Boolean,
        ) {
            if (byTitle.isEmpty() && direct.isEmpty()) return
            val key = streamsRememberedKey(item, episode)
            val now = System.currentTimeMillis()
            pendingWork.entries.removeAll { now - it.value.at >= SWEEP_PENDING_TTL_MS }
            val entry = pendingWork.computeIfAbsent(key) { PendingWork(item, episode) }
            entry.byTitle.addAll(byTitle)
            entry.direct.addAll(direct)
            if (ignoreEmptyRecord) entry.ignoreEmptyRecord = true
            if (resetTries) entry.tries = 0
            entry.at = now
        }

        /** Is a background sweep STILL asking the repos this title's last pass
         *  never reached? While one is alive the search is genuinely not over,
         *  so the screen must not announce "no playable server found" (the sweep
         *  may be about to hand the player the server it is looking for), and
         *  the player's cover must keep saying that it is still searching
         *  instead of failing fast on a verdict that has not been reached yet.
         *  Safe to call from anywhere: it is a plain map read. */
        fun sweepBusyFor(item: MediaItem, episode: Episode?): Boolean {
            val key = streamsRememberedKey(item, episode)
            // HELD: the sweep is not running and is not about to — the player is
            // deliberately keeping it off the loading screen (see [holdSweepFor]).
            // Reporting "still searching" here would put "searching the remaining
            // extensions in the background…" back on the cover the hold exists to
            // clear, and it would be untrue: nothing is being asked right now.
            if (key in heldSweepKeys) return false
            val sweep = sweeps[key]
            if (sweep != null && sweep.job?.isActive == true && sweepIsFresh(sweep)) return true
            // Nothing is running for this video right now, but work for it is on
            // the unfinished-work ledger with retries left — the search is not
            // over, it is waiting for a free sweep slot (see [drainSweeps]). This
            // is the same promise as above, one step earlier in time: the screen
            // must not declare "no playable server found" over a repo that has
            // not been asked yet.
            val pending = pendingWork[key] ?: return false
            val waiting = pending.byTitle.isNotEmpty() || pending.direct.isNotEmpty()
            return waiting && pending.tries < SWEEP_MAX_TRIES
        }

        /** How long a sweep may report NOTHING before it is treated as no longer
         *  searching. It has its own budget ([SWEEP_BUDGET_MS] a round, and its
         *  per-repo waits are bounded), so a sweep that has produced nothing at
         *  all for this long is not "still working" in any sense the user cares
         *  about — it is a set of parked plugin calls, and holding "still
         *  searching" on screen for them is the stuck line being reported. It is
         *  still left running (the calls may yet come back), it just stops being
         *  counted as an active search. */
        const val SWEEP_STALE_MS = 90_000L

        private fun sweepIsFresh(sweep: Sweep): Boolean =
            System.currentTimeMillis() - sweep.lastProgressAt < SWEEP_STALE_MS

        /** Is ANY background sweep still asking repos a pass never reached?
         *
         *  [sweepBusyFor] answers this for one video, which is what the detail
         *  page needs. The player's server picker has only the pass's tallies to
         *  go on, and it has to distinguish "the pass is over" from "the search
         *  is over" — the repos the pass could not fit in its budget are being
         *  asked right now, on the application scope, and the user reads a line
         *  that says "done" over a list missing their servers as a broken
         *  search. Plain map read, safe from anywhere. */
        fun anySweepBusy(): Boolean = sweeps.values.any { it.job?.isActive == true && sweepIsFresh(it) }

        fun crossEmptyKey(providerId: String, query: String): String =
            providerId + "|" + query.trim().lowercase()

        /**
         * The repos of [targets] that a lookup is currently being answered for
         * OUT OF MEMORY: this session recorded "no such title" for them, and the
         * record is still fresh. They are the only repos a pass can come back
         * empty from without having actually asked them, which makes them the
         * ones worth re-asking when it does (see [streamsForInner]).
         */
        fun cachedEmptyTargets(
            targets: List<ContentProvider>,
            query: String,
        ): List<ContentProvider> {
            if (query.isBlank()) return emptyList()
            val now = System.currentTimeMillis()
            return targets.filter { p ->
                val at = crossEmpty[crossEmptyKey(p.config.id, query)] ?: return@filter false
                now - at < CROSS_EMPTY_TTL_MS
            }
        }

        @Volatile
        var crossStatusVersion: Long = 0L
            private set

        fun bumpCrossStatus() {
            crossStatusVersion++
            crossTally.changedAt = System.currentTimeMillis()
        }

        /** How long the LIVE progress tally has been completely unchanged — no
         *  repo starting, no repo finishing, no server landing.
         *
         *  The player's Sources panel used to derive "N still searching" purely
         *  from the number of entries in the running map, which is honest while
         *  work is happening and a permanent lie once it has stopped: a wedged
         *  run of plugins left the same count on screen indefinitely, and the
         *  only way out was to leave the player (the user's own workaround). A
         *  count that has not moved for this long is reported as what it is —
         *  the search is over — instead of being frozen on screen. */
        fun crossStatusQuietForMs(): Long =
            System.currentTimeMillis() - crossTally.changedAt

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
            // A plugin that stopped answering (see [crossHung]): counted apart
            // from "no such title", because it says nothing about the repo's
            // catalogue — and it is the one bucket the user can do something
            // about (a reinstall / an update).
            verdict.contains("stopped responding") -> "stuck, skipped"
            else -> "other"
        }

        /**
         * How a bucket reads ON SCREEN, in the user's words.
         *
         * The bucket names above are log keys — [SWEEP_RETRY_BUCKETS] and
         * [CROSS_QUIET_BUCKETS] match on them exactly, and the end-of-pass log
         * line is grepped by them — so they must not change. But "82 no answer in
         * time" inside a server picker reads as an error report about something
         * the user cannot act on, and it was reported as exactly that (the
         * question was "what is this?"). This maps each key to a plain-English
         * phrase; anything unknown is passed through unchanged, so a new bucket
         * shows up rather than vanishing.
         */
        fun crossBucketLabel(bucket: String): String = when (bucket) {
            "no answer in time" -> "didn't answer in time"
            "no such title" -> "don't carry it"
            "could not load" -> "couldn't load"
            "no search (extractor)" -> "not searchable"
            "title found, no links" -> "have it, no links"
            "stuck, skipped" -> "stopped responding"
            "search error" -> "search failed"
            "cloudflare check" -> "blocked"
            else -> bucket
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
                .joinToString(" · ") { "${it.value} ${crossBucketLabel(it.key)}" }
            return buildString {
                // Every engine, including the pass's primary targets (the
                // title's own extension and the nuvio engines) — see
                // [CrossTally.primaryAsked]. A summary that counted only the
                // cross repos said "asked 140" while 13 more providers were being
                // asked and the nuvio tab was still empty.
                append("asked ${tally.asked.size + tally.primaryAsked.size}")
                val withServers = tally.found.size + tally.primaryFound.size
                if (withServers > 0) append(" · $withServers with servers")
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

    /** How long the FIRST stream call to an .hiki/.cs3 origin is given before it
     *  is retried with the full budget. Short on purpose: the first call is the
     *  one that pays the plugin's cold start (its runtime, its session), and a
     *  probe that comes back empty-handed costs almost nothing — the retry then
     *  runs against a plugin that is already loaded. See [fetchStreams]. */
    private val ORIGIN_PROBE_MS = 12_000L

    /** How long a background re-ask of a pass's PRIMARY targets waits before it
     *  runs: just enough for the pass's own cancelled calls to let go of the
     *  plugins (and of the nuvio engine slots), so the retry meets a warm
     *  runtime instead of a queue. See the hand-off in the pass's teardown. */
    private val SWEEP_DIRECT_HEAD_START_MS = 3_000L

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

    /** [crossReasonBucket]'s bucket for "the repo HAS this title but no link of
     *  its resolved in time" — also swept, and the likeliest of all to produce
     *  servers, because the repo is a proven match and its plugin is warm by
     *  then. (The pass's own extraction is cancelled the moment it ends, so a
     *  match found in the last seconds of the pass never gets its links.) */
    private val SWEEP_MATCHED_NO_LINKS_BUCKET = "title found, no links"

    /** How many unfinished repos make a background sweep worth starting even
     *  when the pass came back with a healthy server list. Below this, the few
     *  stragglers are left alone: keeping the cover on "still searching the
     *  remaining extensions…" for two repos is worse than the two servers they
     *  might have added. */
    private val CROSS_SWEEP_MIN_TARGETS = 10

    /** How many background sweeps may run at the same time.
     *
     *  Every title the user opens now hands its unfinished tail over (see the
     *  hand-off in the pass's `finally`), and on a big install that tail is real
     *  work — 250 extensions to search and extract from. Without a ceiling,
     *  browsing ten titles quickly would leave ten sweeps doing that at once on
     *  a phone. Two is enough that the title being watched and the one just left
     *  both keep filling in; past that a new sweep is not started, and the pass's
     *  own list stands — which is where it was before the sweep existed. (It was
     *  three, which with 8 workers each meant 24 extensions being searched at
     *  once beside whichever pass was running: the phone was the bottleneck, and
     *  the reported "laggy for a few seconds" after leaving the player came
     *  partly from exactly that.) */
    private val MAX_PARALLEL_SWEEPS = 2

    /** How long a BACKGROUND SWEEP keeps working after the pass that started it
     *  has ended. Deliberately long: this is the "keep searching every installed
     *  extension while the video plays" half of the pass, and everything it
     *  produces is only ever ADDED to a list the user is not blocked on. Still a
     *  ceiling, and the per-repo semaphores bound how hard it hits the phone. */
    private val SWEEP_BUDGET_MS get() = minOf(NetTuning.timeout(120_000L), 120_000L)

    /** How many budget-rounds one background sweep may run. A round hands the
     *  repos it never reached to the next one (see [runSweep]), so this is what
     *  stops a big install from keeping a sweep — and its "still searching" line
     *  — alive indefinitely while the per-round semaphores keep the phone
     *  comfortable.
     *
     *  Deliberately short now. The old six rounds of ten minutes meant the
     *  Sources panel could honestly read "still searching" for the better part
     *  of an hour while the film played, which the user reads — correctly — as
     *  a stuck search. Two rounds of two minutes is the whole background
     *  re-ask: every extension still gets its turn, and the line can only ever
     *  stay alive for a bounded few minutes. */
    private val SWEEP_MAX_ROUNDS = 2

    /** How long ONE repo may take inside a sweep before its work is abandoned
     *  and the repo is left for a later attempt.
     *
     *  [SWEEP_BUDGET_MS] bounds a round, but a provider call is a plain blocking
     *  call: no timeout can interrupt it. A plugin whose runtime locked up
     *  while cold-starting therefore used to hold its round — and every round
     *  after it, because a round cannot end while a child of it is parked —
     *  open forever, which is the "stuck on 30 still searching and it never
     *  moved" report. The work is run DETACHED (see [detached]) and only waited
     *  for this long; a call that outlives it keeps running on its own without
     *  holding the search up, and the repo is re-asked on the next attempt. */
    private val SWEEP_REPO_BUDGET_MS get() = minOf(NetTuning.timeout(45_000L), 50_000L)

    /** How many repos a sweep works on at once. Small on purpose: this is the
     *  background half of the search, running while the phone is already
     *  decoding video, and cold-loading thirty plugin runtimes at once is what
     *  wedges them. */
    private val SWEEP_WORKERS = 8

    /** ---- Wave fan-out -------------------------------------------------
     *  Every installed extension is asked (that is the whole point of the
     *  pass), but NOT all at once. A cold .hiki/.cs3/Aniyomi plugin pays a
     *  whole runtime or class load on its very first call, and firing ~96 of
     *  those simultaneously is what wedges plugin runtimes on a phone: the pass
     *  then sits on the same "N still searching" with nothing ever completing
     *  (the user's report, whose own workaround — back out and press Play again
     *  — asks the same repos with fresh calls and works). So the queue is
     *  walked in waves: a few repos first (the origin's own family, which is
     *  also the likeliest home of the next server), then wider waves while
     *  answers are actually coming back. [crossExtensionTargets] already orders
     *  the queue by trust (origin's family, proven repos, then the rest), so
     *  the waves never cost a repo its turn — they only stagger its start. */
    private val CROSS_EXT_WAVE_START = 5
    /** Ceiling on one wave of repo searches — see [deviceFanOut]. A getter, not
     *  a value: the performance booster can be switched on while the app is
     *  running, and the NEXT lookup has to see it. */
    private val CROSS_EXT_WAVE_MAX: Int get() = deviceFanOut()
    private val CROSS_EXT_WAVE_GAP_MS = 1_200L
    private val CROSS_EXT_WAVE_SLOW_MS = 2_000L

    /** How long the pass may go with NOTHING completing before it is called
     *  wedged and ends early, handing every repo it has no answer from to the
     *  background re-ask (see [streamsForInner] and [startSweepIfNeeded]).
     *
     *  This is the fix for the genuinely-stuck search. A provider call is a
     *  plain blocking call, so a plugin that locked up while cold-starting
     *  parks its coroutine forever: the watchdog refunds the slot, but nothing
     *  inside that call ever runs again, so no job completes, no server
     *  arrives, and the pass waits out its whole budget on work that is not
     *  coming back. There is no way to interrupt such a call — the only honest
     *  response is to stop WAITING on it, re-ask those repos with new calls,
     *  and let the count and the status line resolve. */
    private val CROSS_EXT_STALL_MS = 25_000L

    /**
     * How much longer the pass waits for an in-flight NUVIO engine once its
     * ceiling is up.
     *
     * The ceiling exists so the other extensions cannot eat the whole budget.
     * A nuvio engine is a different animal: it is the provider that answers
     * purely from the TMDB id, it boots a VM, and it is routinely the slowest
     * single call in the pass (a site behind a challenge). Cancelling it here
     * does not produce its answer — the teardown hands it to the background
     * sweep, which boots a SECOND VM and repeats every fetch — so the tail is
     * both faster and more complete than the cancel. The engine's own call
     * budget ([com.hikari.app.nuvio.NuvioRuntime.CALL_TIMEOUT_MS]) is the real
     * bound.
     */
    private val NUVIO_TAIL_MS = 25_000L

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
     *  that carries the whole show.
     *
     *  Kept SHORT (it used to be 4s) because the page now paints every list as
     *  it lands (see [ContentRepository.episodesFor]'s `onPartial`): the extra
     *  window is only there to let a longer list replace a shorter one, and the
     *  user is already looking at the episodes while it runs — waiting 4s before
     *  showing anything was the reported "it says 0 episodes, then shows them
     *  all 6-7 seconds later". */
    private val EPISODES_FALLBACK_SETTLE_MS = 1_200L

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
    private val CROSS_EXT_SEARCH_CONCURRENCY: Int = deviceFanOut()
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
    // Refundable gates rather than plain semaphores: a plugin that never comes
    // back holds its slot for good, and a plain semaphore would then hand out
    // fewer slots on every lookup for the rest of the session (see
    // [RefundableGate] and [crossHung]).
    private val CROSS_EXT_SEARCH_GATE = RefundableGate(CROSS_EXT_SEARCH_CONCURRENCY)
    private val CROSS_EXT_EXTRACT_GATE = RefundableGate(CROSS_EXT_EXTRACT_CONCURRENCY)
    private val CROSS_EXT_DETAIL_GATE = RefundableGate(CROSS_EXT_DETAIL_CONCURRENCY)

    /**
     * How many provider requests this DEVICE is asked to run at once.
     *
     * Every device used to be given the same 96-way fan-out. On a phone with
     * four cores that is the freeze the user reports — the searches, the HTML
     * and JSON parsing they do on return, the dozen QuickJS engines booting
     * beside them and the UI's own drawing all land on the same handful of
     * cores, so nothing has a core left and the app looks hung ("clicking the
     * source button freezes it, and sometimes it almost crashes"). Scaling the
     * fan-out to the device keeps the same result list — nothing is ever
     * skipped, the queue still drains in waves ([launchWave]) — at a peak the
     * device can actually service, which is also FASTER end to end: the tail
     * finishes sooner when the head is not thrashing.
     *
     * Floored at 24 so even a small device keeps the "everything at once"
     * character that makes a search feel like nuvio's, and capped at 96 (the
     * old value) for a big tablet.
     *
     * With the PERFORMANCE BOOSTER on (Settings → Performance) the whole scale
     * drops by half: this is the single biggest load a lookup puts on a slow
     * device — dozens of extension calls at once, each holding an OkHttp
     * connection and its own CPU — and the pass still walks every repo, just in
     * more waves (see [launchWave]). Nothing is skipped, only staggered.
     */
    private fun deviceFanOut(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        if (com.hikari.app.data.PerfMode.active) return (cores * 3).coerceIn(12, 36)
        return (cores * 6).coerceIn(24, 96)
    }

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

    /**
     * Runs one provider call inside [gate], registering it with the hang
     * watchdog for as long as it is in flight (see [RefundableGate]).
     *
     * Every provider API call the cross pass, the sweep and the origin path make
     * goes through here, so a plugin that never returns costs its own coroutine
     * and nothing else: the watchdog refunds the slot after [HANG_AFTER_MS] and
     * the queue keeps moving, instead of the gate shrinking for the rest of the
     * session (which is what made a big install's server list decay — "sometimes
     * only nuvio", "stuck at 87 of 159").
     */
    private suspend fun <T> gated(
        gate: RefundableGate,
        providerId: String,
        /** How long this call may be in flight before the watchdog calls it
         *  wedged and gives its slot back (see [InFlight.hangAfterMs]). */
        hangAfterMs: Long = HANG_AFTER_MS,
        block: suspend () -> T,
    ): T {
        ensureHangWatchdog()
        gate.acquire()
        // A UNIQUE token per call, deliberately not the provider id. A repo is
        // asked again and again over a session (a fresh pass, the background
        // sweep, a re-extraction), and keying by provider meant a WEDGED call's
        // entry was overwritten by the next call for the same repo — so the
        // watchdog could never refund it, and every wedge quietly shrank the
        // gate for the rest of the session until searches stopped starting at
        // all. That is one half of the "stuck at 30 still searching, nothing
        // new ever arrives" report; with a token the wedged call keeps its own
        // entry, is refunded on time, and the repo can be asked again.
        val call = InFlight(providerId, gate, System.currentTimeMillis(), hangAfterMs)
        val token = "call:" + callSeq.incrementAndGet() + ":" + providerId
        inFlight[token] = call
        try {
            return block()
        } finally {
            inFlight.remove(token, call)
            // The slot is always given back here — it was taken above — UNLESS
            // the watchdog has already refunded it (which it does only for a
            // call that had stopped coming back). Releasing twice for one
            // acquisition would quietly widen the gate, so the flag decides.
            if (!call.refunded.get()) gate.release()
        }
    }

    /**
     * Runs [block] DETACHED from the caller, and only waits for it up to
     * [budgetMs]. Returns null when the work outlived its budget.
     *
     * This exists because a provider call cannot be interrupted. Every plugin
     * call here is a plain blocking call running inside a coroutine, so
     * `withTimeoutOrNull` gives up logically while the thread stays parked
     * forever — and anything that WAITS on that coroutine (a parent scope, a
     * `coroutineScope`, a sweep round) waits forever too. That is the mechanism
     * behind the genuinely-stuck search: not a wrong count, but work nobody can
     * cancel holding the search open indefinitely.
     *
     * Running the work on the application scope breaks that chain: the caller
     * is not its parent, so when the budget runs out the caller simply moves on
     * (the call is left to finish on its own; the watchdog refunds its slot, and
     * the repo is re-asked later). Nothing that waits on [detached] can be
     * hung, which is what makes "never stuck" a property of the design rather
     * than a hope.
     */
    private suspend fun <T : Any> detached(budgetMs: Long, block: suspend () -> T): T? {
        val scope = HikariApp.instance.appScope
        val job = scope.async<T?> {
            try {
                block()
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                null
            }
        }
        return try {
            withTimeoutOrNull(budgetMs) { job.await() }
        } catch (t: Throwable) {
            null
        }
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
        // Is this the provider the user OPENED the title from? Its answer is the
        // one the player waits for before it starts on anybody else's server
        // (see StreamsLive.settleOrigin and the pass's onOriginSettled), so its
        // fetch is treated differently from the other providers': a deliberately
        // SHORT first probe, then a real retry.
        val isOrigin = p.config.id == item.providerId
        // Aniyomi extensions pay a cold APK class load before their first
        // answer (see the Aniyomi budgets above) — 45s cut them off.
        val fullTimeoutMs =
            if (isAniyomi(p)) minOf(NetTuning.timeout(90_000L), 120_000L)
            else NetTuning.timeout(45_000L)
        // A .hiki/.cs3 plugin's FIRST call has to spin up its runtime and open
        // the site's session before it can answer anything. Inside one 45s
        // budget that cold start is not always over, the call times out having
        // proved NOTHING — and the user's own extension then looked like it had
        // nothing for the title it plainly has, while another extension's
        // server got played instead ("it selected XFree and played the wrong
        // video … on the second attempt it showed the MRDS server"). So the
        // origin gets a short PROBE first — a cold path answers or fails fast —
        // and then the full budget, which by then runs against a warm plugin.
        // Nothing is given up on either way: the long attempt always follows.
        val probeMs = minOf(fullTimeoutMs, ORIGIN_PROBE_MS)
        val maxAttempts = if (isOrigin) maxOf(NetTuning.attempts(), 2) else NetTuning.attempts()
        var attempt = 0
        var lastWhy: String? = null
        while (true) {
            val budget = if (isOrigin && attempt == 0) probeMs else fullTimeoutMs
            var timedOut = false
            val at = System.currentTimeMillis()
            val got = cancellableCatching {
                val r = withTimeoutOrNull(budget) { p.getStreams(item, episode) }
                if (r == null) timedOut = true
                r.orEmpty()
            }.getOrElse { t ->
                lastWhy = t.javaClass.simpleName + (t.message?.let { ": ${it.take(80)}" } ?: "")
                emptyList()
            }
            if (got.isNotEmpty()) {
                providerOutcome.remove(p.config.id)
                return got
            }
            attempt++
            val took = (System.currentTimeMillis() - at) / 1000
            if (timedOut) lastWhy = "no answer in ${took}s"
            if (attempt >= maxAttempts) {
                // Say WHY, on the provider's own log line. "This repo has no
                // servers for this episode" and "this repo never answered" look
                // identical in every server list and every summary line, and
                // that difference is the whole question whenever a title plays
                // from the wrong repo.
                val why = lastWhy ?: "no servers for this episode"
                // An EMPTY list is not automatically an answer. A provider that
                // timed out, threw, or whose own engine reported a timeout has
                // told us nothing about its catalogue — and that is exactly how
                // the nuvio engines "disappeared" from the first tap of Play: a
                // cold QuickJS boot ran past the pass's deadline, the call came
                // back empty, it was recorded as "no servers" and never asked
                // again, while the SAME provider answered in seconds two minutes
                // later. So the outcome is recorded for EVERY provider (not just
                // the origin) as either the answer "no servers" or the reason it
                // never really answered — and the pass teardown hands every
                // provider in the second group to the background re-ask (see the
                // primary hand-off there).
                val said = providerStreamMessage(p)
                val noAnswer = lastWhy != null || isNoAnswer(said)
                providerOutcome[p.config.id] = when {
                    noAnswer -> "✗ " + (lastWhy ?: said ?: "the call never came back")
                    else -> "no servers"
                }
                com.hikari.app.data.Logs.log(
                    "Provider",
                    p.config.name.ifBlank { p.config.id } +
                        " [${p.config.type.groupLabel}]" +
                        (if (isOrigin) " (this title's own extension)" else "") +
                        ": " + when {
                            timedOut -> "✗ $why"
                            noAnswer -> "✗ " + (lastWhy ?: said ?: "the call never came back")
                            // The provider's OWN answer, when it gave one:
                            // "no sources for this title" and "couldn't resolve
                            // a TMDB id" both read as a blanket "no servers"
                            // before this, and telling those two apart from a
                            // crash or a wall is the whole question whenever a
                            // search comes back empty.
                            said != null -> said
                            else -> "no servers"
                        },
                )
                return got
            }
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
                .filter {
                    // Manga engines are left out of the Home feed entirely: they
                    // have their own tab and their own browse screens, and a
                    // manga row here would open the VIDEO detail page, which has
                    // nothing to play (see com.hikari.app.manga.MangaProvider).
                    it.config.enabled &&
                        it.config.type != ProviderType.MANGA &&
                        (providerId == null || it.config.id == providerId)
                }
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
    fun homeRowsStreaming(providerId: String? = null): Flow<List<CatalogRow>> =
        homeRowsStreamingWhere { providerId == null || it.config.id == providerId }

    /**
     * The same feed for a MULTI pick: every selected provider's catalogs, in one
     * feed (Home's provider pill long-press → several sources at once). Rows
     * from different providers keep their own keys, and the curation order is
     * the same [interleaveByProviderType] the single pick uses — so the feed
     * reads exactly like a single-source one, only wider.
     */
    fun homeRowsStreamingFor(ids: Set<String>): Flow<List<CatalogRow>> =
        homeRowsStreamingWhere { it.config.id in ids }

    /**
     * The one implementation behind [homeRowsStreaming] and
     * [homeRowsStreamingFor]: rows are handed to the UI the moment EACH catalog
     * lands instead of after every provider has finished. With several installs,
     * waiting for all of them used to leave Home on a bare spinner for 20-25s;
     * now the first fast provider paints in a few seconds and the rest fill in
     * underneath.
     *
     * Rows are keyed by (providerIndex, catalogIndex) and emitted in that
     * curated order, so late arrivals slot into place instead of jumping to the
     * end of the list. The concurrency gates/timeouts match [homeRows] so a
     * weak device still can't be flooded with requests.
     */
    private fun homeRowsStreamingWhere(
        match: (ContentProvider) -> Boolean,
    ): Flow<List<CatalogRow>> = flow {
        val active = interleaveByProviderType(
            // Manga engines have their own tab, so they are not part of the Home
            // feed (see the note in [homeRows]).
            manager.providers.value.filter {
                it.config.enabled && it.config.type != ProviderType.MANGA && match(it)
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
        /** Passed straight through to [streamsForInner] — fires when the title's
         *  OWN provider has answered, or without ever firing when the caller
         *  does not care (the sweep, the prefetch, a background re-ask). */
        onOriginSettled: (() -> Unit)? = null,
    ): StreamLookup {
        // A source scan across many providers can take a minute; keep it alive
        // if the user leaves the app (see [com.hikari.app.work.BackgroundWork]).
        val work = com.hikari.app.work.BackgroundWork.begin(
            "Finding servers for \"${item.title.take(60)}\""
        )
        // The only thing that can notice a plugin which has stopped coming back,
        // and give its concurrency slot to the rest of the queue (see [crossHung]).
        ensureHangWatchdog()
        try {
            return StreamLookup(
                streamsForInner(item, episode, onProgress, onOriginSettled),
                complete = true,
            )
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

    /**
     * Register [sink] on the background sweep already running for this video, so
     * the caller receives its finds as they land (and, straight away, whatever
     * the sweep has found so far). Returns false when there was no live sweep to
     * join, in which case the caller must do the work itself.
     *
     * This is what makes a tap that arrives while a sweep is running CONTINUE
     * that search instead of merely snapshotting it: a sweep's sinks belong to
     * the pass that started it, and that pass's live session is not the new
     * caller's — so without this, the second tap would be handed the servers
     * found so far and then sit on them while the sweep quietly went on finding
     * servers for a session that had already closed. See [startSweepIfNeeded].
     */
    fun attachToRunningSweep(
        item: MediaItem,
        episode: Episode?,
        sink: (suspend (List<StreamSource>) -> Unit)?,
    ): Boolean {
        if (sink == null) return false
        val sweep = synchronized(sweeps) {
            sweeps[streamsRememberedKey(item, episode)]
        } ?: return false
        if (sweep.job?.isActive != true) return false
        sweep.sinks += sink
        val have = sweep.current
        if (have.isNotEmpty()) {
            HikariApp.instance.appScope.launch {
                cancellableCatching { sink(have) }
            }
        }
        return true
    }

    private suspend fun streamsForInner(
        item: MediaItem,
        episode: Episode?,
        onProgress: (suspend (List<StreamSource>) -> Unit)?,
        /** Called once, when the provider the title came FROM has answered (see
         *  the origin job in the pass below). The player holds its auto-start
         *  until it fires, so this is what ends that hold — early, the moment
         *  the origin has really spoken. */
        onOriginSettled: (() -> Unit)? = null,
    ): List<StreamSource> =
        withContext(Dispatchers.IO) {
            val all = manager.providers.value.filter { it.config.enabled }
            val origin = manager.byId(item.providerId)
            // Settings → Playback → Server search. Off ("only this extension",
            // the CloudStream model) means the lookup never leaves the repo the
            // title was opened from: no sibling repos of the same engine, no
            // stremio addons, no nuvio engines, no cross pass, no sweep. The
            // origin is the only thing asked — see [SearchScope].
            //
            // "Exception extensions" (see [SearchScope]) is the one thing that
            // widens that: a title opened anywhere else is ALSO searched through
            // the repos the user marked. A title opened FROM a marked repo is the
            // exception to the exception: that repo keeps its own catalogue to
            // itself, so the pass collapses to "only this extension" whatever
            // the two switches say.
            // An IPTV channel is a LIVE CHANNEL of one playlist: no other repo
            // can possibly hold it, whatever the two switches say, so its
            // lookup behaves exactly like a repo that keeps its catalogue to
            // itself (the rule a marked exception repo already gets). Without
            // this, playing a channel swept every installed extension — hundreds
            // of movie/series repos searching a channel NAME — which is the
            // reported "any IPTV link should only search its own links, not
            // every other provider's".
            val originIsIptv = IptvMark.of(item)
            val originIsException = originIsIptv || SearchScope.isException(item.providerId)
            val scopeAll = if (originIsException) false else SearchScope.allExtensions
            val exceptions = if (originIsException) {
                emptySet()
            } else {
                SearchScope.exceptions
            }
            val primaryTargets = if (!scopeAll) {
                // The title's own extension, and nothing else. It is still asked
                // FIRST (and re-asked in the background if it does not answer),
                // because it is now the only source this lookup has.
                listOfNotNull(origin)
            } else if (origin?.config?.type == ProviderType.STREMIO) {
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
            val nuvioTargets = if (!scopeAll) {
                // "Only this extension": a nuvio engine is another source, so
                // none of them are asked (the origin, if it IS one, is already
                // in [primaryTargets]) — except the ones marked as exceptions,
                // which the user asked to always include.
                if (exceptions.isEmpty()) emptyList()
                else all.filter {
                    it.config.type == ProviderType.NUVIO && it.config.id in exceptions
                }
            } else if (com.hikari.app.nuvio.TmdbResolver.isLikelyResolvable(item)) {
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
            // This pass's OWN diagnostic state — created BEFORE the target list
            // is built, because the target list records into it (see
            // [CrossTally.filterReasons]), but published only once the pass is
            // known to be running at all (a lookup that returns early below must
            // not blank the numbers the chooser is reading).
            //
            // A fresh object, and the local names below deliberately shadow
            // nothing process-wide: passes overlap (a Play tap during the
            // prefetch's sweep, a retry after a pass was cut short, two screens
            // for the same title), and a shared tally meant the numbers on
            // screen belonged to no single search ("asked 93" with "164 no such
            // title" — more verdicts than asks). Everything below, and every
            // helper this pass calls, writes into THIS tally.
            val tally = CrossTally()
            // The other installed extensions that get their turn on EVERY
            // lookup (see crossExtensionSearch). Built from the SAME snapshot of
            // the provider list that `all` (and so `installed=` below) came
            // from — one read, used everywhere in this pass.
            //
            // This used to re-read `manager.providers.value`, and the two reads
            // could disagree: the user's own log has a pass whose target list
            // held 140 repos while the same line reported 181 Hikari installed
            // and a skip-reason list accounting for none of the missing ~100.
            // Whatever changes the provider list mid-pass (an extension install
            // or repo sync refreshing it, a plugin registering more sources),
            // the pass must ask EXACTLY what it reports — and everything that
            // was installed when the lookup started, so a provider can no longer
            // be left out of a pass by a list that moved under it.
            val crossTargets = if (scopeAll) {
                crossExtensionTargets(item, origin, all, tally)
            } else if (exceptions.isEmpty()) {
                // "Server search: only this extension" — there is nothing else
                // to search, so the cross pass and every sweep below are skipped
                // rather than launched and left to find nothing.
                emptyList()
            } else {
                // Exception extensions: these ARE the only other repos this pass
                // may ask, and they go through the same title-search → extract
                // machinery as any cross extension (their ids mean nothing to
                // each other). Restricted by id, which keeps every filter, the
                // trust order and the tally identical to the normal pass.
                crossExtensionTargets(item, origin, all, tally, onlyIds = exceptions)
            }
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
            publishCrossTally(tally)

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
                "families=" + providerCounts(crossTargets) +
                // …and how many of each are INSTALLED. `families=` says whether
                // the CloudStream repos were asked; `installed=` says whether
                // they existed to ask in the first place — which is the
                // difference between "not installed" and "silently skipped".
                //
                // Both counts come from the SAME snapshot and are printed
                // together on purpose: `families` + the reasons below must add
                // up to `installed`, and if they ever do not, the pass itself is
                // reading two different provider lists (the bug this log line
                // was added to catch).
                " installed=" + providerCounts(all) +
                // …and WHY the rest of the installed extensions were left out,
                // one count per reason ("disabled", "origin", "cf-skip",
                // "hung", "family(Nuvio)", …). The three numbers above can only
                // say that the target list shrank; this says what removed them,
                // which is the difference between "the user's repo is not
                // installed", "it is installed but sitting behind a Cloudflare
                // wall", and "it is installed and healthy but starved of a slot
                // by the budget". Read from THIS pass's tally, so the reasons
                // always belong to the pass that prints them (see
                // [CrossTally.filterReasons]).
                " skipped=" + tally.filterReasons.entries
                    .sortedBy { it.key }
                    .joinToString(",") { "${it.key}=${it.value}" },
        )

            val crossRunning = tally.running
            val crossVerdict = tally.verdict
            val crossAsked = tally.asked
            val crossFound = tally.found
            val crossInstalled = tally.installed
            val primaryRunning = tally.primaryRunning
            val primaryAsked = tally.primaryAsked
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
            // installed" and "silently skipped" at a glance. The NUVIO engine is
            // included: its providers are asked by the primary pass, and the
            // chooser's line has to be able to say "Nuvio 13 of 13".
            all.groupBy { it.config.type.groupLabel }
                .forEach { (label, list) -> crossInstalled[label] = list.size }
            // Extensions dropped because their plugin stopped coming back
            // earlier in this session: written into the tally BEFORE anything is
            // queued, so the chooser's summary says "3 stopped responding" out
            // loud instead of quietly asking three fewer repos than the user has
            // installed (see [crossHung]).
            all.filter { isHung(it.config.id) }.forEach { p ->
                crossVerdict[p.config.id] =
                    (p.config.name.ifBlank { p.config.id }) + " — stopped responding earlier"
            }
            // The primary targets are counted as asked from the moment their
            // jobs are created (just below), and as "still searching" until
            // each one's call comes back — see [CrossTally.primaryRunning].
            targets.forEach { p ->
                val label = p.config.type.groupLabel
                primaryAsked[p.config.id] = label
                primaryRunning[p.config.id] = p.config.name.ifBlank { p.config.id }
                // Start from a clean slate: a job that is cancelled BEFORE it
                // ever runs leaves no outcome of its own, and a stale one from an
                // earlier lookup ("no servers") would read as an answer and hide
                // the fact that this pass never asked it. See the primary hand-off
                // in the teardown, which uses exactly this map to decide what to
                // re-ask.
                providerOutcome.remove(p.config.id)
            }
            bumpCrossStatus()
            com.hikari.app.nuvio.NuvioScraper.lastOutcome.clear()
            com.hikari.app.nuvio.NuvioRuntime.resetFetchLog()
            com.hikari.app.nuvio.NuvioRuntime.resetRunTracking()
            com.hikari.app.skystream.SkyStreamProvider.lastOutcome.clear()
            com.hikari.app.skystream.SkyStreamRuntime.resetFetchLog()

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            var result: List<StreamSource> = emptyList()
            // Mirrors of the pass's own clock and result, hoisted OUT of the
            // pass body so its teardown can still reason about the repos it
            // never finished with even when the body was CANCELLED before it
            // could report anything — which is the case the user's own log is
            // full of (no pass ever reached its "done" line). See the hand-off
            // at the top of the `finally`.
            var passStartedAt = 0L
            var passDeadlineAt = 0L
            var passFound: List<StreamSource> = emptyList()
            /** Set at the very end of the pass body — clear of the `finally`
             *  below — so a teardown that runs without it knows the pass was
             *  cut off (cancelled or thrown) rather than finished. */
            var passCompleted = false
            /** Set when the pass ended EARLY because nothing at all was
             *  completing — a plugin whose runtime locked up while cold-starting
             *  (see [CROSS_EXT_STALL_MS]). The repos it never got an answer from
             *  are re-asked in the background like any other unfinished tail,
             *  and the log says this is why. */
            var passStalled = false
            try {
                // The user is waiting on this search, so background sweeps stand
                // down until it is over (see the foreground/background gate in
                // the companion). Renewed on every exit path, including a
                // cancellation — which is the common case in practice.
                enterForegroundPass()
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
                            // Deadline cancelled us. Say whether we actually got
                            // an engine slot and ran, and record it as an
                            // UNFINISHED call so the teardown hands this provider
                            // to the background re-ask instead of dropping it
                            // (see the primary hand-off there).
                            val ran = if (isNuvio) {
                                com.hikari.app.nuvio.NuvioRuntime.providerStartedAt(p.config.id)
                            } else {
                                startedJob
                            }
                            val why = if (ran != null)
                                "✗ cut off after ${(System.currentTimeMillis() - startedJob) / 1000}s (still searching)"
                            else
                                "✗ search ended before it could run (still waiting for an engine slot)"
                            if (isNuvio) com.hikari.app.nuvio.NuvioScraper.lastOutcome[p.config.id] = why
                            providerOutcome[p.config.id] = why
                            throw e
                        } finally {
                            // Off the live "still searching" line the moment this
                            // provider has answered OR been cut off. Guarded
                            // against the teardown's own sweep: if a background
                            // re-ask has already taken this provider over, the
                            // sweep's entry is the truth, not this one's.
                            if (p.config.id !in sweepOwned) primaryRunning.remove(p.config.id)
                            bumpCrossStatus()
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
                // Tell the player the moment that provider has ANSWERED — with
                // servers or without. It holds its auto-start until then, so a
                // title opened inside an extension plays that extension's link
                // rather than whichever other extension answered first; and the
                // hold must end as soon as the origin has really spoken, or the
                // wait becomes the wait it was meant to avoid. Completion is the
                // right signal (not "found servers"): a repo that plainly has
                // nothing for this episode is an answer too, and waiting longer
                // for it would only delay playback. Fires on cancellation as
                // well, which is what keeps a pass that died from leaving the
                // player holding a session nobody is working on.
                if (originJob != null) {
                    originJob.invokeOnCompletion { onOriginSettled?.invoke() }
                } else {
                    // Nothing to wait for: the origin is not installed, not
                    // enabled, or this title did not come from one at all.
                    onOriginSettled?.invoke()
                }
                // Ceiling for the whole lookup. It only binds when providers are
                // slow/failing — normally they finish → allDone well before it.
                // 55s lets the trusted nuvio providers (priority order) pass
                // through the concurrency cap plus a few fallbacks. Results are
                // emitted progressively via onProgress, so the UI never sits on
                // an empty spinner while this runs.
                //
                // Nuvio engines get a LONGER ceiling, because their own call
                // budget is 60s (nuvio's per-plugin timeout, see
                // NuvioRuntime.CALL_TIMEOUT_MS) — a pass that gave up at 55s
                // cancelled the tail of a 12-engine install MID-RUN, and the
                // pass's scope cancellation kills the engine outright, so the
                // provider had to be re-asked from scratch in the background
                // sweep (a second VM boot plus a second round of network work
                // for the same answer). The ceiling only binds while engines are
                // still working, and results stream in as they land, so waiting
                // for them is strictly cheaper than redoing them.
                val deadline = started + if (nuvioTargets.isNotEmpty()) {
                    minOf(NetTuning.timeout(70_000L), 75_000L)
                } else {
                    minOf(NetTuning.timeout(55_000L), 60_000L)
                }
                passStartedAt = started
                passDeadlineAt = deadline
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
                        // Kept up to date on every merge, not only at the end of
                        // the pass: the teardown in the `finally` uses this as the
                        // snapshot a background sweep starts from, and a pass that
                        // is CANCELLED (the common case — see there) never reaches
                        // the line that would have recorded it otherwise.
                        passFound = merged.values.toList()
                    }
                }
                var lastEmitted = -1
                // When the pass last made ANY progress (a search or an extraction
                // completing, a hit landing). The wedge watchdog reads it — see
                // [CROSS_EXT_STALL_MS].
                var lastProgressAt = started
                var mergedCount = 0
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
                // ---- WAVE FAN-OUT (see [CROSS_EXT_WAVE_START]) --------------
                // The queue is the trust-ordered target list: the origin's own
                // engine family, then the repos that have already produced
                // servers this session, then everyone else. Waves only stagger
                // WHEN each repo is asked, never whether — a phone must not
                // cold-load ~96 plugin runtimes at the same instant, which is
                // what wedges them.
                val sameEngineIds = sameEngine.map { it.config.id }.toHashSet()
                val searchQueue = java.util.ArrayDeque<ContentProvider>()
                sameEngine.forEach { searchQueue.add(it) }
                lateTargets.forEach { searchQueue.add(it) }
                var waveSize = CROSS_EXT_WAVE_START
                var lastWaveAt = started
                var waveDone = 0
                var searchDone = 0
                var nextWaveAt = started + (if (targets.isEmpty()) 0L else crossGrace)
                var lateStartedAt = 0L
                fun launchWave(now: Long) {
                    if (searchQueue.isEmpty() || now < nextWaveAt) return
                    var launched = 0
                    while (launched < waveSize && searchQueue.isNotEmpty()) {
                        val p = searchQueue.poll() ?: break
                        if (lateStartedAt == 0L && !sameEngineIds.contains(p.config.id)) {
                            lateStartedAt = now
                        }
                        launchSearch(p, waitForOrigin = sameEngineIds.contains(p.config.id))
                        launched++
                    }
                    // Widen only when the previous wave is producing answers: a
                    // wave of cold plugins that has not come back yet must not be
                    // followed by four more just like it.
                    val progressed = searchDone > waveDone ||
                        now - lastWaveAt >= CROSS_EXT_WAVE_SLOW_MS
                    if (progressed) waveSize = minOf(CROSS_EXT_WAVE_MAX, waveSize * 2)
                    waveDone = searchDone
                    lastWaveAt = now
                    nextWaveAt = now + CROSS_EXT_WAVE_GAP_MS
                }
                val hits = ArrayList<CrossHit>()
                val extractJobs = ArrayList<kotlinx.coroutines.Deferred<List<StreamSource>>>()
                val extractQueued = HashSet<Int>()
                fun launchPendingExtractions() {
                    while (true) {
                        // Pick the highest-priority repo that has MATCHED and has
                        // not been launched yet: the ORIGIN's own entry first,
                        // then a repo this session has already got servers from,
                        // then the origin's engine family, then the rest. This
                        // is what makes "the extension you opened it in plays
                        // it" hold even when another repo answered first — the
                        // extraction queue is ordered by trust, not by arrival.
                        var pick = -1
                        var pickScore = Int.MAX_VALUE
                        hits.forEachIndexed { i, h ->
                            if (i in extractQueued) return@forEachIndexed
                            val score = when {
                                h.provider.config.id == item.providerId -> 0
                                crossProven.contains(h.provider.config.id) -> 1
                                sameEngineIds.contains(h.provider.config.id) -> 2
                                else -> 3
                            }
                            if (score < pickScore) {
                                pickScore = score
                                pick = i
                            }
                        }
                        if (pick < 0) return
                        extractQueued += pick
                        val hit = hits[pick]
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
                    // New servers landing IS progress: a pass that is producing
                    // servers (however slowly) must never be called wedged.
                    if (merged.size != mergedCount) {
                        mergedCount = merged.size
                        lastProgressAt = now
                    }
                    // One wave of the trust-ordered queue (see [launchWave]): the
                    // origin's own repo family gets asked first, everything else
                    // follows in widening waves — never all 258 cold plugins at
                    // once, which is what wedges them.
                    launchWave(now)
                    // Pick up everything the searches matched so far — from every
                    // job that has completed, so a hit that lands late still gets
                    // its servers.
                    val jobIt = searchJobs.iterator()
                    while (jobIt.hasNext()) {
                        val j = jobIt.next()
                        if (j.isCompleted) {
                            runCatching { j.getCompleted() }.getOrNull()?.let { hits.add(it) }
                            jobIt.remove()
                            searchDone++
                            lastProgressAt = now
                        }
                    }
                    // ---- PHASE 2: extract what matched ------------------------
                    // Runs CONTINUOUSLY, over the hits that have arrived so far,
                    // in trust order (the origin's entry first — see
                    // [launchPendingExtractions]). It used to be gated behind
                    // "every extension has answered, or 45s have passed", which
                    // is why a cold first play sat on the finding-server card for
                    // a minute: nothing could start resolving links until the
                    // whole 250-repo search phase was over. Extraction is still
                    // bounded by its own gate ([CROSS_EXT_EXTRACT_CONCURRENCY]),
                    // so it cannot starve the searches that are still queued.
                    launchPendingExtractions()
                    // Count the extractions that land, and for the wedge watchdog:
                    // anything completing anywhere is progress.
                    val exIt = extractJobs.iterator()
                    while (exIt.hasNext()) {
                        val j = exIt.next()
                        if (j.isCompleted) {
                            // Merge BEFORE dropping it: an extraction that
                            // finished between the merge pass at the top of this
                            // iteration and here must still hand over its servers.
                            merge(j)
                            exIt.remove()
                            lastProgressAt = now
                        }
                    }
                    // ---- WEDGE WATCHDOG --------------------------------------
                    // A pass can stop making progress and never say so: every
                    // provider call is a plain blocking call, so a plugin whose
                    // runtime locked up while cold-starting leaves coroutines
                    // parked forever — no timeout interrupts them, no job ever
                    // completes, and the pass sits on the same "N still
                    // searching" for as long as the user stares at it. That is
                    // the genuinely-stuck search, and the user's own workaround
                    // was to back out and press Play again, which asks the same
                    // repos with FRESH calls and works. So: if nothing at all has
                    // completed for [CROSS_EXT_STALL_MS] while work is still
                    // outstanding, the pass is called wedged. It ends here, and
                    // the teardown hands every repo it has no answer from to the
                    // background re-ask — the same retry the user performs by
                    // hand, done automatically, while the video plays.
                    val outstanding = searchQueue.size + searchJobs.size + extractJobs.size
                    if (outstanding > 0 && now - lastProgressAt >= CROSS_EXT_STALL_MS) {
                        passStalled = true
                        com.hikari.app.data.Logs.log(
                            "Search",
                            "pass over \"${item.title}\": nothing has answered in " +
                                "${CROSS_EXT_STALL_MS / 1000}s with $outstanding repo(s) " +
                                "still outstanding — abandoning the wedged work and " +
                                "re-asking them in the background",
                        )
                        break
                    }
                    val allDone = jobs.all { it.isCompleted } && searchQueue.isEmpty() &&
                        searchJobs.isEmpty() && extractJobs.all { it.isCompleted }
                    if (allDone) break
                    // Wait for EVERY provider (like Stremio aggregating every
                    // addon): each installed nuvio provider is independent
                    // (it resolves from the TMDB id alone), so each one that
                    // finds streams adds selectable servers to the list. The old
                    // first-non-empty early-close cancelled every provider that
                    // hadn't answered within ~1.5s, which is why only one
                    // provider's servers ever showed up in the player.
                    // A nuvio engine still running is the one thing the ceiling
                    // is allowed to wait for — see [NUVIO_TAIL_MS]: cancelling
                    // it here just re-asks it from scratch in the sweep.
                    val nuvioStillRunning = targets.indices.any { i ->
                        targets[i].config.type == ProviderType.NUVIO && !jobs[i].isCompleted
                    }
                    val overCeiling = now > maxOf(deadline, started + CROSS_EXT_BUDGET_MS)
                    if (overCeiling && !(nuvioStillRunning && now <= deadline + NUVIO_TAIL_MS)) {
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
                        break
                    }
                    kotlinx.coroutines.delay(80)
                }
                jobs.forEach { it.cancel() }
                searchJobs.forEach { it.cancel() }
                extractJobs.forEach { it.cancel() }
                // What this pass has to show for itself, recorded for the
                // teardown below. The leftover-repos → background-sweep hand-off
                // lives THERE, not here, and that is the whole point: a
                // cancellation skips everything from this line onwards, and a
                // pass is at least as likely to be cancelled mid-flight (the
                // detail screen re-created: back out of the player, a rotation, a
                // fresh navigation — its viewModelScope dies with it) as it is to
                // reach its end. In the user's own log not one pass ever reached
                // its "done" line, so a hand-off that only ran on a clean exit
                // was never going to run at all.
                passFound = merged.values.toList()
                result = passFound
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
                // The primary half of the same idea. A primary job whose
                // coroutine was cancelled before it ever ran leaves no entry of
                // its own behind, so what is left is cleared here and re-created
                // by the background re-ask (see the hand-off above) — otherwise a
                // nuvio engine that never started would hold "N still searching"
                // on screen for the rest of the session.
                primaryRunning.keys.retainAll(sweepOwned)
                sweepOwned.forEach { id ->
                    targets.firstOrNull { it.config.id == id }?.let { p ->
                        primaryRunning[id] = p.config.name.ifBlank { id }
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
                passCompleted = true
            } finally {
                scope.cancel()
                // Background work may go again — BEFORE the hand-off below, so
                // the sweep this teardown is about to start can actually run
                // instead of parking itself on the way out.
                exitForegroundPass()
                // ---- Hand the leftovers to the background sweep ----
                //
                // This is the pass's ONLY hand-off, and it is here — in the
                // `finally` — rather than anywhere in the body above, because
                // this block runs on EVERY exit: a normal return, a throw, and a
                // cancellation. The pass body is skipped from the first
                // suspension point after a cancel, and cancellation is the
                // COMMON case in practice (the detail screen is re-created when
                // the user backs out of the player, rotates, or re-opens the
                // title, and its viewModelScope — which owns the prefetch pass —
                // dies with it). Ran from the line above, the hand-off ran only
                // for a pass that finished cleanly, so in the user's own log
                // (four passes, no "done" line in any of them) it never ran at
                // all, and the servers the tail still held never arrived: "it
                // says 5 servers and the search finished, then on the second tap
                // it shows all the servers". The sweep itself runs on the
                // APPLICATION scope, so it outlives the screen that started it.
                //
                // What counts as a leftover: a repo the pass never asked, one
                // that timed out or could not load, and one that MATCHED the
                // title but had its extraction cut off when the pass ended (the
                // warmest candidates of all — their plugin is already loaded).
                // "No such title" is deliberately NOT swept: the repo's own
                // search page really did come back without this title, and
                // re-asking it is exactly the pointless work that made the pass
                // crawl.
                val tail = sweepableTail(crossTargets, crossFound, crossVerdict)
                val servable = passFound.size
                // A pass that ended because it was WEDGED (nothing completing for
                // [CROSS_EXT_STALL_MS]) always hands its unanswered repos over:
                // re-asking them with fresh calls is the only thing that gets an
                // answer out of a plugin whose runtime locked up, and it is
                // exactly what the user's own "back out and press Play again"
                // does — except the user does not have to do it.
                val ranOutOfTime = passStalled ||
                    System.currentTimeMillis() >=
                    maxOf(passDeadlineAt, passStartedAt + CROSS_EXT_BUDGET_MS)
                // …and the repos this pass answered OUT OF MEMORY. They are not
                // in the tail (each has a verdict, it just came from the
                // session's own "no such title" record rather than from the
                // repo), but they are the least trustworthy answers in the whole
                // search: ONE blank page is enough to create that record, and it
                // then hides the repo from every lookup for minutes. When the
                // pass came back thin, they are re-asked for real — merged into
                // the SAME sweep as the tail, because a second sweep for the same
                // video would only join the first one and drop its own targets.
                val fromMemory = cachedEmptyTargets(crossTargets, item.searchTitle)
                val reAsk = if (fromMemory.isNotEmpty() && servable < CROSS_THIN_RESULT) {
                    fromMemory
                } else {
                    emptyList()
                }
                val sweepTargets = (tail + reAsk).distinctBy { it.config.id }
                // ---- NOTHING IS EVER DROPPED: the PRIMARY targets too ----
                //
                // Everything above is about the OTHER repos. The pass's primary
                // targets — the extension the title was opened FROM, and every
                // nuvio engine — are the ones whose servers the player is
                // actually waiting for, and they are the slowest single piece of
                // work in a whole lookup: a nuvio provider boots a fresh QuickJS
                // VM and parses a 450KB cheerio bundle before it can even ask the
                // site, six of them at a time. They were the ONE family with no
                // hand-off at all: when the pass's clock ran out (or the screen
                // was re-created and the pass's scope died with it), whatever had
                // not answered was cancelled and forgotten, and its servers only
                // ever showed up on the NEXT tap of Play, against engines that
                // were warm by then — the reported "the first time it searched
                // everything except nuvio, the second and third time nuvio was
                // there".
                //
                // So they now get exactly what the cross repos get: everything
                // that did not really answer is re-asked in the background, on
                // the application scope, while the video plays. The re-ask is a
                // DIRECT call (no title search — nuvio resolves from the TMDB id
                // alone), pushed through the same sink, remembered the same way,
                // and counted on the same live line.
                //
                // "no servers" is an ANSWER (the repo was asked and has nothing
                // for this episode, so asking again only spends its time); a "✗"
                // outcome (a timeout, a thrown failure, a call cancelled
                // mid-flight) is not an answer and IS re-asked. Nothing here
                // depends on the provider's ENGINE — hikari, cloudstream,
                // skystream, aniyomi, stremio and nuvio all reach this the same
                // way, so a starved or cut-off provider is re-asked whichever
                // family it belongs to.
                val primaryLeft = targets.filter { p ->
                    val id = p.config.id
                    passFound.none { it.providerId == id } &&
                        providerOutcome[id] != "no servers" &&
                        !isHung(id)
                }
                // A sweep is worth starting when a real tail of work is left,
                // when the pass came back with almost nothing (a short list
                // across 250+ repos is exactly the case the user watched go from
                // 5 servers to 43 on the next tap), when it was cut off by the
                // clock, or when repos answered from memory and need asking for
                // real. A pass that found a full list with only a couple of
                // stragglers left is NOT swept: that would keep the "still
                // searching" line up for no gain.
                val worthSweeping = sweepTargets.isNotEmpty() &&
                    (tail.size >= CROSS_SWEEP_MIN_TARGETS ||
                        reAsk.isNotEmpty() ||
                        servable < CROSS_THIN_RESULT ||
                        ranOutOfTime)
                com.hikari.app.data.Logs.log(
                    "Search",
                    "cross \"${item.title}\" pass over: ${crossTargets.size} target(s), " +
                        "${crossFound.size} with servers, $servable server(s) on the list, " +
                        "${tail.size} repo(s) unfinished" +
                        (if (reAsk.isEmpty()) "" else " + ${reAsk.size} answered from memory") +
                        (if (passCompleted) "" else " (pass was CUT OFF early)") +
                        (if (passStalled) " (pass STALLED — nothing was answering)" else "") +
                        " (time=$ranOutOfTime) → " +
                        (if (worthSweeping) "sweeping them in the background" else "not sweeping") +
                        // What the pass cost the process. The line above says
                        // what the search DID; this says what it took to do it,
                        // which is the other half of "it gets laggy and almost
                        // crashes while the servers load" (see [MemoryReport]).
                        " · " + com.hikari.app.data.MemoryReport.short(),
                )
                if (primaryLeft.isNotEmpty()) {
                    com.hikari.app.data.Logs.log(
                        "Search",
                        "primary \"" + item.title + "\": " +
                            "${targets.size - primaryLeft.size} of ${targets.size} answered, " +
                            "${primaryLeft.size} unfinished (" + providerCounts(primaryLeft) +
                            ") — re-asking them in the background",
                    )
                }
                if (worthSweeping || primaryLeft.isNotEmpty()) {
                    startSweepIfNeeded(
                        item,
                        episode,
                        sweepTargets,
                        primaryLeft,
                        passFound,
                        onProgress,
                        ignoreEmptyRecord = reAsk.isNotEmpty(),
                    )
                }
                // ---- The title's OWN extension, and every nuvio engine ----
                //
                // The origin used to be re-asked by a block right here: it is
                // the one provider whose server is unambiguously right, it pays
                // the plugin's cold start, and the user's own workaround for a
                // repo that "only shows up on the second attempt" was to press
                // Play again. That block has been GENERALISED rather than kept:
                // the same "did this call really answer?" rule, against the same
                // [providerOutcome] record, now covers EVERY primary target
                // (origin, nuvio engines, stremio addons) and hands them to the
                // BACKGROUND SWEEP above, where the answer is published, merged
                // and remembered by the same code that publishes every other
                // background find. One mechanism instead of two means the origin
                // cannot be asked twice at once, and no primary provider can be
                // left out because it belongs to a family the sweep did not know
                // about.
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
            // (A bundled yt-dlp engine used to take one last shot at the page
            // here when every provider type came up empty. It was removed in
            // 0.9.1 — see the CHANGELOG. Everything above still applies; the
            // remaining engines are the plugin's own loadLinks, the jar
            // extractor registry, MovieBlast, FallbackResolver and the
            // Nuvio/SkyStream JS runtimes.)
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
            // A `ytId` or `externalUrl` row is a LINK to the video, not a video
            // — Stremio's protocol allows both, plenty of addons use them for
            // real content (and every trailer row is a YouTube one), and
            // `DetailScreen.playableEvery` filters both out, so an addon whose
            // streams are all of those played nothing at all: "no playable
            // server found" from an addon that plays fine in Stremio. Resolved
            // here — once, bounded, after the union above so a remembered row is
            // resolved too — and the resolved list replaces the remembered one,
            // so a repeat lookup does not pay for it again.
            val resolvedResult = com.hikari.app.cs3.PlayableResolver.resolve(finalResult)
            if (resolvedResult !== finalResult) {
                finalResult = resolvedResult
                if (finalResult.isNotEmpty()) {
                    streamsRemembered[rememberKey] =
                        RememberedStreams(finalResult, System.currentTimeMillis())
                }
            }
            // The repos this pass answered OUT OF MEMORY ([crossEmpty], the
            // session's own "no such title" record) are re-asked for real
            // whenever the pass came back thin, and the repos it never finished
            // with are handed over too — both in ONE place now: the hand-off in
            // the pass's `finally` above (see the long note there). The memory
            // answers are the least trustworthy ones in the whole search (ONE
            // blank page is enough to create the record, and it then hides that
            // repo from every lookup for minutes — "sometimes it finds nothing at
            // all, and the next try finds plenty"), so they are merged into the
            // same sweep as the tail; a second sweep for the same video would
            // only join the first one and silently drop its own targets.
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
        /** The pass's PRIMARY targets that never really answered (see the
         *  primary hand-off in [streamsForInner]): asked DIRECTLY, with no title
         *  search at all, because they resolve from their own id — the title's
         *  own extension, the nuvio engines, and any Stremio addon the pass
         *  asked. */
        directTargets: List<ContentProvider>,
        snapshot: List<StreamSource>,
        sink: (suspend (List<StreamSource>) -> Unit)?,
        /** Ask these repos for REAL, ignoring the session's "no such title"
         *  record. Set when the record is the only reason they were not asked
         *  (see the empty-result block in [streamsForInner]). */
        ignoreEmptyRecord: Boolean = false,
    ) {
        // RECORDED before a sweep is even attempted: whatever a sweep cannot
        // take right now stays on the ledger instead of evaporating (see
        // [PendingWork]).
        notePendingWork(
            item,
            episode,
            targets.map { it.config.id },
            directTargets.map { it.config.id },
            ignoreEmptyRecord = ignoreEmptyRecord,
            resetTries = true,
        )
        launchSweepFor(item, episode, snapshot, sink)
    }

    /** Starts — or joins — the sweep for whatever is recorded as unfinished for
     *  this video. Idempotent per video: a call while one is running just
     *  registers its [sink] (and immediately hands it what the sweep has found
     *  so far), so tapping Play twice cannot double the work. */
    private fun launchSweepFor(
        item: MediaItem,
        episode: Episode?,
        snapshot: List<StreamSource>,
        sink: (suspend (List<StreamSource>) -> Unit)?,
        /** False for the automatic drain that follows a finished sweep: an entry
         *  that has already had [SWEEP_MAX_TRIES] sweeps is left for the next
         *  real lookup instead of being retried in a loop. A pass the user
         *  started always allows a retry (it resets the count). */
        allowExhausted: Boolean = true,
    ) {
        val key = streamsRememberedKey(item, episode)
        synchronized(sweeps) {
            // JOIN FIRST. Whether a sweep is already running for this video is
            // the most specific fact about it, and the ledger below is allowed
            // to be empty while one is (its ids were consumed when it started).
            // Asking the ledger first meant a second caller was answered
            // "nothing to do" and its sink was dropped — which is exactly what a
            // held sweep must not do when the player finally releases it.
            val joinable = sweeps[key]
            if (joinable != null && joinable.job?.isActive == true) {
                if (sink != null) joinable.sinks += sink
                val have = joinable.current
                if (sink != null && have.isNotEmpty()) {
                    HikariApp.instance.appScope.launch {
                        cancellableCatching { sink(have) }
                    }
                }
                return
            }
            val recorded = pendingWork[key] ?: return
            if (recorded.byTitle.isEmpty() && recorded.direct.isEmpty()) {
                pendingWork.remove(key)
                return
            }
            // HELD until the video is playing (see [holdSweepFor]): keep what
            // this call would have searched with, and who was waiting to hear
            // about the results, and start nothing.
            if (key in heldSweepKeys) {
                val held = heldSweeps.computeIfAbsent(key) {
                    HeldSweep(item, episode, emptyList())
                }
                if (snapshot.isNotEmpty()) {
                    held.snapshot = (held.snapshot + snapshot)
                        .distinctBy { it.infoHash ?: it.url }
                }
                if (sink != null) held.sinks += sink
                com.hikari.app.data.Logs.log(
                    "Search",
                    "sweep \"" + item.title + "\" held until the video is playing (" +
                        (recorded.byTitle.size + recorded.direct.size) +
                        " provider(s) unfinished)",
                )
                return
            }
            if (!allowExhausted && recorded.tries >= SWEEP_MAX_TRIES) return
            // A ceiling on how many sweeps run at once — see
            // [MAX_PARALLEL_SWEEPS]. Refusing to start one is honest, and no
            // longer loses anything: the work stays on the ledger and the next
            // freed slot takes it ([drainSweeps]).
            val live = sweeps.values.count { it.job?.isActive == true }
            if (live >= MAX_PARALLEL_SWEEPS) {
                com.hikari.app.data.Logs.log(
                    "Search",
                    "sweep \"" + item.title + "\" not started — $live sweep(s) already running (" +
                        (recorded.byTitle.size + recorded.direct.size) +
                        " provider(s) unfinished, kept for the next free slot)",
                )
                return
            }
            // Resolve the recorded ids back to providers. An id with no provider
            // any more (the extension was uninstalled or disabled meanwhile) is a
            // real answer and drops out — there is nobody left to ask.
            val byTitle = recorded.byTitle.toList().mapNotNull { manager.byId(it) }
            val direct = recorded.direct.toList().mapNotNull { manager.byId(it) }
            recorded.byTitle.clear()
            recorded.direct.clear()
            recorded.tries++
            if (byTitle.isEmpty() && direct.isEmpty()) {
                pendingWork.remove(key)
                return
            }
            val units = byTitle.map { SweepUnit(it, byTitle = true) } +
                direct.map { SweepUnit(it, byTitle = false) }
            val sweep = Sweep()
            sweep.ignoreEmptyRecord = recorded.ignoreEmptyRecord
            if (sink != null) sweep.sinks += sink
            units.forEach { sweepOwned.add(it.provider.config.id) }
            val job = HikariApp.instance.appScope.launch {
                try {
                    runSweep(item, episode, units, sweep, snapshot)
                } catch (e: Throwable) {
                    com.hikari.app.data.Logs.log(
                        "Search",
                        "sweep \"" + item.title + "\" ended early (" +
                            e.javaClass.simpleName +
                            (e.message?.let { ": $it" } ?: "") + ")",
                    )
                } finally {
                    units.forEach { sweepOwned.remove(it.provider.config.id) }
                    // Whatever this sweep never got an answer from goes back on
                    // the ledger, so a sweep that died, was cancelled, or ran out
                    // of rounds can never be the end of the search.
                    val unasked = units.filter { !sweep.answered.contains(it.provider.config.id) }
                    notePendingWork(
                        item,
                        episode,
                        unasked.filter { it.byTitle }.map { it.provider.config.id },
                        unasked.filterNot { it.byTitle }.map { it.provider.config.id },
                        ignoreEmptyRecord = sweep.ignoreEmptyRecord,
                        resetTries = false,
                    )
                    synchronized(sweeps) { if (sweeps[key] === sweep) sweeps.remove(key) }
                    bumpCrossStatus()
                    com.hikari.app.data.Logs.log(
                        "Search",
                        "sweep \"" + item.title + "\" finished — ${sweep.current.size} server(s) on the list",
                    )
                    // A slot just freed: take whatever is waiting for one.
                    drainSweeps()
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
                "sweep \"" + item.title + "\" → ${units.size} provider(s) the pass never finished " +
                    "(${direct.size} of them asked directly): searching in the background",
            )
        }
    }

    /** Start whatever the player was holding back for this video — see
     *  [holdSweepFor]. One launch per registered sink, so every caller that was
     *  waiting still hears about the results; the first carries the snapshot
     *  the pass had already found. */
    private fun releaseHeldSweep(held: HeldSweep) {
        val sinks = held.sinks.toList()
        val unfinished = pendingWork[streamsRememberedKey(held.item, held.episode)]
        com.hikari.app.data.Logs.log(
            "Search",
            "sweep \"" + held.item.title + "\" released — playback started" +
                (if (unfinished == null) " (nothing left to search)"
                else " (" + (unfinished.byTitle.size + unfinished.direct.size) +
                    " provider(s) to ask)"),
        )
        if (sinks.isEmpty()) {
            launchSweepFor(held.item, held.episode, held.snapshot, null)
            return
        }
        sinks.forEachIndexed { i, s ->
            launchSweepFor(held.item, held.episode, if (i == 0) held.snapshot else emptyList(), s)
        }
    }

    /** The automatic drain: every finished sweep runs this, so work that was
     *  recorded while all sweep slots were busy is picked up as soon as one
     *  frees — instead of waiting for the user to press Play again, which used to
     *  be the only way to get an unfinished repo's servers. */
    private fun drainSweeps() {
        if (pendingWork.isEmpty()) return
        if (sweeps.values.count { it.job?.isActive == true } >= MAX_PARALLEL_SWEEPS) return
        pendingWork.values.toList().forEach { entry ->
            val key = streamsRememberedKey(entry.item, entry.episode)
            if (sweeps[key]?.job?.isActive == true) return@forEach
            launchSweepFor(entry.item, entry.episode, emptyList(), null, allowExhausted = false)
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
        units: List<SweepUnit>,
        sweep: Sweep,
        snapshot: List<StreamSource>,
    ) {
        val key = streamsRememberedKey(item, episode)
        val acc = LinkedHashMap<String, StreamSource>()
        snapshot.forEach { acc[it.infoHash ?: it.url] = it }
        // …plus anything this video's servers were already known to be, so a
        // sweep that started from a cut-off pass (whose own snapshot is empty
        // because it never reached the line that records one) publishes the
        // UNION rather than a shorter list. [publish] writes that list back into
        // [streamsRemembered], and a repeat lookup merges it in — so publishing a
        // regression here is exactly how a server that played a minute ago would
        // disappear from the next attempt.
        streamsRemembered[key]
            ?.takeIf { System.currentTimeMillis() - it.at < REMEMBERED_STREAMS_TTL_MS }
            ?.list
            ?.forEach { acc.putIfAbsent(it.infoHash ?: it.url, it) }
        sweep.current = acc.values.toList()
        var lastEmitted = acc.size
        val budget = SWEEP_BUDGET_MS
        val started = System.currentTimeMillis()
        sweep.rounds++
        sweep.lastProgressAt = started
        // Time this sweep has spent deliberately WAITING for the foreground (see
        // the gate in the companion). It does not count against the round's
        // budget — a sweep that stood down for a pass must not lose the repos it
        // never got to because of it.
        val pausedMs = java.util.concurrent.atomic.AtomicLong(0L)
        // Repos whose turn never came before the round's budget ran out. They are
        // carried into another round below rather than dropped, because a repo
        // that is never asked is exactly what "it stopped at 87 of 159 and just
        // sat there" was: the search had not failed, it had simply stopped
        // asking. Rounds are capped so a pathological install cannot keep the
        // sweep (and its status line) alive forever.
        val unasked = java.util.concurrent.CopyOnWriteArrayList<SweepUnit>()

        suspend fun publish() {
            val list = synchronized(acc) { acc.values.toList() }
            if (list.isEmpty() || list.size == lastEmitted) return
            lastEmitted = list.size
            sweep.current = list
            sweep.lastProgressAt = System.currentTimeMillis()
            streamsRemembered[key] = RememberedStreams(list, System.currentTimeMillis())
            for (s in sweep.sinks) cancellableCatching { s(list) }
        }

        // WORKER POOL, and a bounded wait on every repo: the sweep has to be
        // able to FINISH even when a plugin never answers.
        //
        // The old shape launched one child per repo inside a single
        // `coroutineScope`, which waits for all of them — so ONE parked plugin
        // call kept the round open forever, and with it the sweep, its "still
        // searching" line and every round after it. That is the reported "stuck
        // on 30 still searching and it never moved": the work was not slow, it
        // was un-finishable. Now only [SWEEP_WORKERS] repos are worked on at
        // once, and each repo's two provider phases run DETACHED with a budget
        // ([SWEEP_REPO_BUDGET_MS]) — a call that outlives its budget is left to
        // finish on its own, and the repo is asked again later instead of
        // holding the search open.
        // A pass that was cut off hands over its PRIMARY targets here as well,
        // and those calls were still in flight (or queued on the nuvio engine
        // pool) a moment ago: this pause lets them let go, so the re-ask meets a
        // warm runtime instead of a queue — the same courtesy the origin's retry
        // used to get from [SWEEP_DIRECT_HEAD_START_MS].
        if (units.any { !it.byTitle }) kotlinx.coroutines.delay(SWEEP_DIRECT_HEAD_START_MS)
        val queue = java.util.concurrent.ConcurrentLinkedQueue<SweepUnit>(units)
        val workers = minOf(SWEEP_WORKERS, units.size).coerceAtLeast(1)
        kotlinx.coroutines.coroutineScope {
            repeat(workers) {
                launch {
                    while (true) {
                        val unit = queue.poll() ?: break
                        val p = unit.provider
                        // A search the user is waiting on always wins, and so
                        // does the moment right after the player closes: this
                        // worker parks until the foreground is free. The wait is
                        // taken off the round's clock (see [pausedMs]) and the
                        // sweep's "still making progress" stamp is refreshed
                        // while it waits, so a parked sweep is never reported as
                        // a wedged one.
                        val waited = awaitBackgroundClearance {
                            sweep.lastProgressAt = System.currentTimeMillis()
                        }
                        if (waited > 0) pausedMs.addAndGet(waited)
                        // Past the ceiling: stop starting new work. Whatever is
                        // already in flight still lands (and is published), and
                        // the repos that lost their turn are handed to the NEXT
                        // round (see [unasked]).
                        if (System.currentTimeMillis() - started - pausedMs.get() >= budget) {
                            unasked.add(unit)
                            continue
                        }
                        // Wedged recently (see [crossHung], which now EXPIRES):
                        // skip it rather than hold a worker on a call that cannot
                        // come back.
                        if (isHung(p.config.id)) continue
                        val id = p.config.id
                        val repo = p.config.name.ifBlank { id }
                        // The live tally (not a captured one): a later pass for
                        // this video replaces [crossTally], and the sweep's
                        // progress must show up on whichever tally the chooser
                        // is reading.
                        val tally = crossTally
                        // A pass's PRIMARY target: asked directly, with no title
                        // search (see [SweepUnit]).
                        if (!unit.byTitle) {
                            if (sweepAskDirect(p, item, episode, tally, acc, sweep)) {
                                sweep.answered.add(id)
                            }
                            publish()
                            continue
                        }
                        val scanned = detached(SWEEP_REPO_BUDGET_MS) {
                            crossExtensionSearch(
                                p,
                                item,
                                episode,
                                tally,
                                ignoreEmptyRecord = sweep.ignoreEmptyRecord,
                            )
                        }
                        if (scanned == null) {
                            // Parked (or threw): abandon it for this round. It
                            // is left for a later attempt rather than
                            // blacklisted, and it comes off the live progress
                            // line so the count on screen can resolve.
                            tally.running.remove(id)
                            tally.verdict[id] = "$repo — did not answer in time"
                            bumpCrossStatus()
                            sweep.lastProgressAt = System.currentTimeMillis()
                            com.hikari.app.data.Logs.log(
                                "Search",
                                "sweep \"${item.title}\" → $repo: no answer within " +
                                    "${SWEEP_REPO_BUDGET_MS / 1000}s — moving on " +
                                    "(it will be asked again)",
                            )
                            continue
                        }
                        val hit = scanned.first
                        val verdict = scanned.second
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
                                continue
                            }
                            com.hikari.app.data.Logs.log(
                                "Search",
                                "sweep \"${item.title}\" → $repo: found \"${hit.candidate.title}\" " +
                                    "— getting servers…",
                            )
                            val extracted = detached(SWEEP_REPO_BUDGET_MS) {
                                crossExtensionExtract(hit, item, episode)
                            }
                            if (extracted == null) {
                                tally.verdict[id] = "$repo — did not answer in time"
                                com.hikari.app.data.Logs.log(
                                    "Search",
                                    "sweep \"${item.title}\" → $repo: extraction gave no answer " +
                                        "within ${SWEEP_REPO_BUDGET_MS / 1000}s — moving on",
                                )
                                continue
                            }
                            val found = extracted.first
                            val why = extracted.second
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
                            // Off the "nothing is ever dropped" ledger: this repo
                            // has been ASKED (whatever its answer was), so it is
                            // not handed to another round or another sweep.
                            sweep.answered.add(id)
                            bumpCrossStatus()
                            sweep.lastProgressAt = System.currentTimeMillis()
                        }
                        publish()
                    }
                }
            }
        }
        publish()
        // Repos that lost their turn to the round's budget get another round —
        // up to [SWEEP_MAX_ROUNDS] of them — so the sweep asks every installed
        // extension instead of stopping at whichever one the clock reached.
        // Recursion is bounded by the round counter, and a wedged repo is never
        // carried forward (it would just block a round again). Whatever is STILL
        // unasked when the rounds are spent is not dropped either: it stays on
        // the unfinished-work ledger and is asked by the next sweep for this
        // video (see [PendingWork] and the sweep's `finally`).
        val left = unasked.filterNot { isHung(it.provider.config.id) }
        if (left.isNotEmpty() && sweep.rounds < SWEEP_MAX_ROUNDS) {
            com.hikari.app.data.Logs.log(
                "Search",
                "sweep \"${item.title}\" → round ${sweep.rounds}: ${left.size} repo(s) left " +
                    "unasked — asking them now (${sweep.current.size} server(s) so far)",
            )
            runSweep(item, episode, left, sweep, sweep.current)
        } else if (left.isNotEmpty()) {
            com.hikari.app.data.Logs.log(
                "Search",
                "sweep \"${item.title}\" ended after ${sweep.rounds} rounds with " +
                    "${left.size} repo(s) unasked — kept for the next sweep",
            )
        }
    }

    /**
     * The sweep's DIRECT half: ask ONE provider for this item+episode the way a
     * pass's primary targets are asked — no title search at all, straight at the
     * provider (a nuvio engine resolves from the TMDB id alone, and the title's
     * own extension already holds its own id). Its finds are merged into [acc]
     * (the sweep publishes them), it keeps the live "N still searching" line
     * honest through [CrossTally.primaryRunning], and it reports whether the
     * provider was really ASKED (a call that never came back is not asked, so it
     * stays on the ledger for another sweep — see [PendingWork]).
     */
    private suspend fun sweepAskDirect(
        p: ContentProvider,
        item: MediaItem,
        episode: Episode?,
        tally: CrossTally,
        acc: MutableMap<String, StreamSource>,
        sweep: Sweep,
    ): Boolean {
        val id = p.config.id
        val repo = p.config.name.ifBlank { id }
        tally.primaryRunning[id] = repo
        bumpCrossStatus()
        // A nuvio engine boots a native VM, and this is BACKGROUND work — the
        // video is already playing — so it takes one of the small background
        // slots instead of competing with a search the user is waiting on (see
        // NuvioRuntime.withBackgroundSlot).
        val got = if (p.config.type == ProviderType.NUVIO) {
            com.hikari.app.nuvio.NuvioRuntime.withBackgroundSlot {
                detached(SWEEP_REPO_BUDGET_MS) { fetchStreams(p, item, episode) }
            }
        } else {
            detached(SWEEP_REPO_BUDGET_MS) { fetchStreams(p, item, episode) }
        }
        try {
            if (got == null) {
                com.hikari.app.data.Logs.log(
                    "Search",
                    "sweep \"${item.title}\" → $repo (" + p.config.type.groupLabel +
                        "): no answer within ${SWEEP_REPO_BUDGET_MS / 1000}s — moving on " +
                        "(it will be asked again)",
                )
                return false
            }
            val found = tagGroup(got, p)
            if (found.isEmpty()) {
                // What the provider said about itself, when it said anything:
                // "no servers" is an answer; anything else is the reason it could
                // not answer. Either way it HAS been asked.
                val said = providerOutcome[id]
                com.hikari.app.data.Logs.log(
                    "Search",
                    "sweep \"${item.title}\" → $repo (" + p.config.type.groupLabel + "): " +
                        if (said == null || said == "no servers") "no servers" else "nothing ($said)",
                )
                return true
            }
            tally.primaryFound[id] = p.config.type.groupLabel
            // Proven: asked first on every later lookup of any title.
            crossProven.add(id)
            synchronized(acc) {
                found.forEach { s -> acc.putIfAbsent(s.infoHash ?: s.url, s) }
            }
            com.hikari.app.data.Logs.log(
                "Search",
                "sweep \"${item.title}\" → $repo (" + p.config.type.groupLabel + "): " +
                    "${found.size} servers (background, while playing)",
            )
            return true
        } finally {
            tally.primaryRunning.remove(id)
            bumpCrossStatus()
            sweep.lastProgressAt = System.currentTimeMillis()
        }
    }

    /** The repos a pass never really finished with: never asked, timed out, could
     *  not load, or MATCHED the title but had its links cut off when the pass
     *  ended. Exactly the set a background sweep is allowed to retry
     *  ([SWEEP_RETRY_BUCKETS] plus the matched-but-no-links bucket) — see the
     *  hand-off in the pass's `finally`.
     *
     *  "No such title" is deliberately absent: the repo's own search page really
     *  did come back without this title, so re-asking it is the pointless work
     *  that made the pass crawl. A repo with NO verdict at all counts as
     *  unfinished, because it is one the pass never got an answer from. */
    private fun sweepableTail(
        targets: List<ContentProvider>,
        found: Map<String, String>,
        verdicts: Map<String, String>,
    ): List<ContentProvider> = targets.filter { p ->
        val id = p.config.id
        if (isCfSkipped(id) || found.containsKey(id)) return@filter false
        val verdict = verdicts[id] ?: return@filter true
        val bucket = crossReasonBucket(verdict)
        bucket in SWEEP_RETRY_BUCKETS || bucket == SWEEP_MATCHED_NO_LINKS_BUCKET
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
     *     by TMDB id (they resolve without the title at all) — they are PRIMARY
     *     targets, and the pass's teardown hands the ones that did not answer to
     *     the same background re-ask (see the primary hand-off in
     *     [streamsForInner]), so being left out of THIS list costs them nothing;
     *   - extensions known to sit behind a verification wall ([crossCfSkip]),
     *     which are skipped before anything is queued for them. */
    private fun crossExtensionTargets(
        item: MediaItem,
        origin: ContentProvider?,
        /** The pass's OWN snapshot of the enabled provider list, taken once at
         *  the top of [streamsForInner]. Passed in (rather than re-read here)
         *  so the target list and the `installed=` counts printed next to it
         *  always describe the same list — see the note at the call site. */
        all: List<ContentProvider>,
        /** The pass's tally, which receives [CrossTally.filterReasons]. */
        tally: CrossTally,
        /** When non-null, ONLY extensions whose id is in this set are eligible —
         *  the "exception extensions" pass (see [SearchScope]), where the user
         *  named the exact repos that may be asked. Every other filter below
         *  still applies, so an exception that is blocked, hung or of a family
         *  that cannot be searched by title is still left out. */
        onlyIds: Set<String>? = null,
    ): List<ContentProvider> {
        val originType = origin?.config?.type
        val originIsStremio = originType == ProviderType.STREMIO
        // Why each installed extension did or did not make this pass's target
        // list, counted by reason and published to the PASS's tally so the
        // pass's own log line can print it. Without this, a target list that
        // shrinks between two taps of the same title ("245 targets, then 137,
        // and the second pass found a fraction of the servers") can only be
        // guessed at from a shared log — the numbers say WHICH filter dropped
        // those repos.
        val skipped = LinkedHashMap<String, Int>()
        fun noteSkipped(reason: String) {
            skipped[reason] = (skipped[reason] ?: 0) + 1
        }
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
        // `all` is the pass's snapshot of the enabled provider list, NOT a fresh
        // read of the manager (see this function's parameter note).
        val families = all
            .filter { p ->
                if (!p.config.enabled) {
                    noteSkipped("disabled")
                    return@filter false
                }
                if (p.config.id == item.providerId) {
                    noteSkipped("origin")
                    return@filter false
                }
                if (onlyIds != null && p.config.id !in onlyIds) {
                    noteSkipped("not-an-exception")
                    return@filter false
                }
                // An extension that answered with a Cloudflare verification wall
                // is dropped BEFORE anything is queued for it — no search slot,
                // no cold plugin load, no verdict, nothing in the progress line.
                // Skipping it here (rather than searching it and then reporting
                // the block) is the point: it is not worth searching, and its
                // block is not something to read in the server list.
                if (isCfSkipped(p.config.id)) {
                    noteSkipped("cf-skip")
                    return@filter false
                }
                // An extension whose plugin STOPPED COMING BACK earlier in this
                // session is dropped the same way (see [crossHung]): asking it
                // again only spends a slot and a cold load on a call that will
                // never answer. Reported once, honestly, rather than left to
                // look like a repo that has no such title.
                if (isHung(p.config.id)) {
                    noteSkipped("hung")
                    return@filter false
                }
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
                        noteSkipped("sky-host-blocked")
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
                    // An IPTV playlist carries its whole channel list locally
                    // once read, so "is this title on any of my channels?" costs
                    // a string scan — and a VOD/24-7 playlist genuinely can hold
                    // the title that is being looked for.
                    ProviderType.IPTV -> true
                    ProviderType.STREMIO -> if (originIsStremio) {
                        noteSkipped("family(Stremio)")
                        false
                    } else {
                        true
                    }
                    ProviderType.NUVIO -> {
                        noteSkipped("family(Nuvio)")
                        false
                    }
                    else -> {
                        noteSkipped("family(${p.config.type.groupLabel})")
                        false
                    }
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
        // Into THIS pass's tally — see [CrossTally.filterReasons].
        tally.filterReasons = skipped
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
        /** Ask for real even if this session recorded "no such title" for the
         *  query (set by a sweep the empty result started — see
         *  [streamsForInner]). */
        ignoreEmptyRecord: Boolean = false,
    ): Pair<CrossHit?, String?> {
        val repo = p.config.name.ifBlank { p.config.id }
        // Already known to need a verification wall this session: skipped
        // silently — no search slot, no "still searching" entry, no verdict.
        if (isCfSkipped(p.config.id)) return null to CROSS_VERDICT_SKIPPED
        val title = item.searchTitle.trim()
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
            ignoreEmpty = ignoreEmptyRecord,
        )
        // A search that THREW or TIMED OUT says nothing about the repo's
        // catalog — a cold plugin load ("the first call has to spin up its
        // runtime") is the usual cause, and the old code wrote that off as "no
        // matching title". Ask once more before giving up on this repo.
        if (attempt.best == null && attempt.why != null) {
            attempt = searchBestMatch(
                p,
                title,
                item,
                CROSS_EXT_MIN_MATCH,
                episode = episode,
                ignoreEmpty = ignoreEmptyRecord,
            )
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
                    ignoreEmpty = ignoreEmptyRecord,
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
        val meta = gated(CROSS_EXT_DETAIL_GATE, p.config.id, DETAIL_HANG_AFTER_MS) {
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
            val eps: List<Episode> = gated(CROSS_EXT_DETAIL_GATE, p.config.id, DETAIL_HANG_AFTER_MS) {
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
        val got: List<StreamSource> = gated(CROSS_EXT_EXTRACT_GATE, p.config.id) {
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
    /**
     * True when a provider's own message describes a call that did NOT really
     * complete — a timeout, a cut-off engine, an unreadable result — as opposed
     * to an ANSWER about the repo's catalogue ("no sources for this title", "no
     * matching title", "couldn't resolve a TMDB id").
     *
     * The difference is the whole of the reported "the first tap shows every
     * engine except nuvio": a nuvio provider whose cold QuickJS boot ran past
     * the pass's deadline returns an EMPTY list with "✗ provider timed out after
     * 45s" in its own error map, which used to be read as a plain "no servers"
     * answer — so it was never asked again, and the SAME provider answered in
     * seconds on the next tap. Anything classified here as a non-answer is
     * re-asked in the background (see the primary hand-off in [streamsForInner]);
     * anything classified as an answer is left alone, because re-asking a repo
     * that has already said "not here" only spends its time.
     */
    private fun isNoAnswer(text: String?): Boolean {
        if (text == null) return true
        val t = text.lowercase()
        return t.contains("timed out") ||
            t.contains("timeout") ||
            t.contains("no answer") ||
            t.contains("did not answer") ||
            t.contains("cut off") ||
            t.contains("still searching") ||
            t.contains("waiting for an engine slot") ||
            t.contains("unreadable") ||
            // What a provider's own engine says when it could not really run —
            // a QuickJS crash, a bridge failure, a budget that ran out (see
            // NuvioScraper). Without this a provider that THREW was recorded as
            // the answer "no servers" and never asked again, which is exactly
            // how a whole tab of working engines can read as "nothing here".
            t.contains("provider failed") ||
            t.contains("failed to") ||
            t.contains("couldn't be searched") ||
            t.contains("could not be searched")
    }

    private fun providerStreamMessage(p: ContentProvider): String? = when (p.config.type) {
        ProviderType.STREMIO -> StremioAddon.streamErrors[p.config.id]
        ProviderType.CS3 -> Cs3MainApiProvider.streamErrors[p.config.id]
        ProviderType.HIKARI -> HikariProviderAdapter.streamErrors[p.config.id]
        ProviderType.UNIVERSAL -> UniversalScraper.streamErrors[p.config.id]
        ProviderType.NUVIO -> com.hikari.app.nuvio.NuvioScraper.streamErrors[p.config.id]
        ProviderType.SKYSTREAM -> com.hikari.app.skystream.SkyStreamProvider.streamErrors[p.config.id]
        ProviderType.ANIYOMI -> com.hikari.app.aniyomi.AniyomiProvider.streamErrors[p.config.id]
        ProviderType.IPTV -> IptvProvider.iptvErrors[p.config.id]
        ProviderType.MANGA -> com.hikari.app.manga.MangaProvider.lastOutcome[p.config.id]
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
        /** Ignore the session's "no such title" record for this query and ask
         *  the extension for real (see [cachedEmptyTargets]). */
        ignoreEmpty: Boolean = false,
    ): SearchAttempt = gated(CROSS_EXT_SEARCH_GATE, p.config.id, SEARCH_HANG_AFTER_MS) {
        // Already answered "no such title" for this exact query a moment ago
        // (see [crossEmpty]): don't spend a slot — or a cold plugin load — on
        // the same question again.
        val cacheKey = crossEmptyKey(p.config.id, query)
        val cachedAt = if (ignoreEmpty) null else crossEmpty[cacheKey]
        if (cachedAt != null) {
            if (System.currentTimeMillis() - cachedAt < CROSS_EMPTY_TTL_MS) {
                // Answered out of this session's own record — still CONSULTED,
                // so the pass's progress line counts it (otherwise a pass that
                // resolved ten of eleven repos from cache read as "asked 1 of
                // 11 … done", which looks exactly like the search gave up).
                onCached?.invoke()
                return@gated SearchAttempt(null, null, cachedEmpty = true)
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
                return@gated SearchAttempt(hit.item, null)
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
            val scored = results.map { it to titleScore(item.searchTitle, item.year, it) }
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
        // A "title" that is really a URL — or that has our OWN search string
        // pasted into it after a query parameter — is a scraper's echo of the
        // request, not a catalogue entry. An adult tube repo returns page titles
        // like "… Nothing Beats a Car Wash&query=vengadores: endgame": every
        // significant word of the query is in there, so it sailed through the
        // containment rule below, scored above the threshold, and the player
        // started an unrelated video from it. Nothing that looks like an address
        // can be the user's title, so it is rejected before any scoring.
        if (looksLikeUrlEcho(candidate.title)) return false
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
        // The episode must be THE episode. A repo entry whose own name states an
        // episode number ("Renegade Immortal Episode 20", "第20集", "148") is only
        // usable for the episode being played — otherwise the entry is another
        // episode's page, and taking it hands the user a different video even
        // though the SHOW matches (the "it played the wrong episode from another
        // repo" half of the report). Only an explicit marker counts, so a title
        // that merely ends in a number ("Show Season 2") is not misread as an
        // episode number and cannot reject a perfectly good repo.
        if (episode != null && wanted.type == MediaType.SERIES) {
            val stated = statedEpisodeNumber(candidate.title)
            if (stated != null && stated != episode.number) {
                val wantedStated = statedEpisodeNumber(episode.name)
                if (wantedStated == null || wantedStated != stated) return false
            }
        }
        // The ORIGINAL name is what the extensions index: a title the app renamed
        // for display (TMDB language) must still be searched for by the name
        // their sites use, or every repo answers "no matching title" and the
        // pass comes back with nothing (see [MediaItem.searchTitle]).
        val a = normalizeTitle(wanted.searchTitle)
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
        val extends = sb.containsAll(sa)      // the repo names the show more fully
        val shortened = sa.containsAll(sb)    // the repo names LESS than we asked
        if (!extends && !shortened) return false
        // A repo entry that is a SHORTENED form of what we asked for is only
        // trusted when it still carries at least two significant words. One word
        // is how an unrelated video gets in: a repo page titled just "Renegade"
        // contains "renegade", which is part of "Renegade Immortal", so the old
        // rule accepted it and the player started whatever that page was — the
        // reported "an adult video titled Renegade gives me its server". A repo
        // that names the show MORE fully ("Renegade Immortal (Xian Ni)", "…Season
        // 2") is still accepted at any length, because that is the ordinary way
        // repositories label a show, and extending a title cannot introduce a
        // different series.
        if (shortened && !extends && sb.size < 2) return false
        // …and the first significant word must survive, so the match is anchored
        // to the head of the title rather than to a shared tail.
        if (!sb.contains(ta.first()) && !sa.contains(tb.first())) return false
        val yb = candidate.year
        if (wanted.year != null && yb != null && kotlin.math.abs(wanted.year - yb) > 1) {
            return false
        }
        return true
    }

    /**
     * True for a "title" that is really an address, or that carries a query
     * parameter — the shape a scraper's page title takes when the site echoes
     * the request back into it.
     *
     * This is not a cosmetic check: such a title CONTAINS the words the user
     * searched for (that is what the echo is), so it passes every
     * word-containment rule the matcher has and can score as the best match on
     * the page. Rejecting it is the difference between playing the right video
     * and playing whatever the repo's page happened to be titled.
     */
    private fun looksLikeUrlEcho(title: String): Boolean {
        val t = title.lowercase()
        if (t.contains("http://") || t.contains("https://") || t.contains("www.")) return true
        // `&query=`, `?q=`, `&amp;search=` …: an '=' inside a video title is not
        // punctuation, it is an address. The `&amp;` form is what a scraper
        // hands over when it escaped the URL for HTML.
        return QUERY_ECHO.containsMatchIn(t)
    }

    private val QUERY_ECHO =
        Regex("[?&](?:amp;)?(?:query|q|s|search|keyword|term|kw|text|name)=")

    /** The episode number a row's own name EXPLICITLY states ("Ep 148",
     *  "Episode 148", "E148", "第148集", or a name that is nothing but the
     *  number), or null when the name says nothing about numbering.
     *
     *  Deliberately narrower than [episodicNumber]: a trailing number is NOT read
     *  as an episode here ("Show Season 2" must not look like episode 2), because
     *  this one decides whether a repo entry belongs to a DIFFERENT episode than
     *  the one being played — a judgement that must never be made on a guess (see
     *  [confidentTitleMatch]). */
    private fun statedEpisodeNumber(name: String?): Int? {
        val n = name?.trim().orEmpty()
        if (n.isEmpty()) return null
        EPISODE_IN_NAME.find(n)?.let { return it.groupValues[1].toIntOrNull() }
        CJK_EPISODE_IN_NAME.find(n)?.let { return it.groupValues[1].toIntOrNull() }
        if (n.length <= 4 && n.all { it.isDigit() }) return n.toIntOrNull()
        return null
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
            ProviderType.MANGA -> com.hikari.app.manga.MangaProvider.lastOutcome
            ProviderType.IPTV -> IptvProvider.iptvErrors
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
    suspend fun episodesFor(
        item: MediaItem,
        onPartial: ((List<Episode>) -> Unit)? = null,
    ): List<Episode>? = withContext(Dispatchers.IO) {
        if (item.type == MediaType.UNKNOWN) return@withContext null
        synchronized(episodeCache) { episodeCache[item.uniqueId] }?.let {
            // Re-opening the page is instant: hand the cached list straight to
            // the caller before doing anything else.
            onPartial?.invoke(it)
            return@withContext it
        }
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
                // Publish the extension's own list the MOMENT it answers, before
                // any polish: the page must never read "Episodes (0)" over a
                // list that already exists (see [onPartial]).
                onPartial?.invoke(sorted)
                // Auto-translate FIRST, then the TMDB name lookup: when the user
                // has a TMDB language set, TMDB's own name for the episode is what
                // the page and the player should print, and it must not be
                // overwritten by the per-extension "translate this to English"
                // option (which is about the extension's own content, not about
                // the language the app is being read in).
                val translated = translateEpisodes(item.providerId, sorted)
                if (translated !== sorted) onPartial?.invoke(translated)
                val named = withRealEpisodeNames(item, translated)
                synchronized(episodeCache) { episodeCache[item.uniqueId] = named }
                if (named !== translated) onPartial?.invoke(named)
                return@withContext named
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
            episodesFromExtensions(item, onPartial)?.let { list ->
                // Same order as above: auto-translate first, then TMDB's names in
                // the app's chosen language (which win when they exist).
                val translated = translateEpisodes(item.providerId, list)
                if (translated !== list) onPartial?.invoke(translated)
                val named = withRealEpisodeNames(item, translated)
                synchronized(episodeCache) { episodeCache[item.uniqueId] = named }
                if (named !== translated) onPartial?.invoke(named)
                return@withContext named
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
     *
     * [onPartial] receives each better list as it lands, so the page fills in
     * while the rest of the sweep is still running.
     */
    private suspend fun episodesFromExtensions(
        item: MediaItem,
        onPartial: ((List<Episode>) -> Unit)? = null,
    ): List<Episode>? {
        if (item.type != MediaType.SERIES) return null
        // A channel list is not an episode list, and no other repo holds this
        // channel: an IPTV item is never "borrowed" from another extension (see
        // the origin rule in [streamsForInner]).
        if (IptvMark.of(item)) return null
        // "Server search: only this extension" (Settings → Playback): borrowing
        // another site's episode list is exactly the cross-extension behaviour
        // that switch turns off — and asking for it here would have made the
        // option leak anyway (the detail page would quietly scrape every other
        // extension the moment the origin's own list came back thin).
        //
        // Exception extensions are the exception: they are already searched for
        // servers on every lookup, so their episode list may be borrowed too.
        // (A title opened FROM such an extension never reaches this code — the
        // origin answers its own episode list first; see [SearchScope].)
        val exceptionIds = if (SearchScope.isException(item.providerId)) emptySet()
        else SearchScope.exceptions
        if (!SearchScope.allExtensions && exceptionIds.isEmpty()) return null
        val originType = manager.byId(item.providerId)?.config?.type
        val candidates = manager.providers.value
            .filter { p ->
                p.config.enabled &&
                    p.config.id != item.providerId &&
                    !isCfSkipped(p.config.id) &&
                    (SearchScope.allExtensions || p.config.id in exceptionIds) &&
                    when (p.config.type) {
                        ProviderType.CS3,
                        ProviderType.HIKARI,
                        ProviderType.UNIVERSAL,
                        ProviderType.SKYSTREAM,
                        ProviderType.ANIYOMI,
                        ProviderType.IPTV -> true
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
                                if (found.compareAndSet(cur, list)) {
                                    // Hand the page what we have RIGHT NOW instead
                                    // of waiting for the whole sweep to settle: a
                                    // list that exists a second after the page
                                    // opened must not stay invisible for the
                                    // settle window (see [onPartial]).
                                    runCatching { onPartial?.invoke(list) }
                                    break
                                }
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
            cancellableCatching { p.search(item.searchTitle, 1) }.getOrDefault(emptyList())
        } ?: return null
        val match = hits
            .filter { confidentTitleMatch(item, it, null) }
            .maxByOrNull { titleScore(item.searchTitle, item.year, it) }
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
        // The episode-name lookup keys off the show's ORIGINAL name (a display
        // title localized by the app's TMDB language is not what the wiki knows
        // it as).
        val showName = item.searchTitle
        // …and it asks in the app's chosen TMDB language when one is set: the
        // episode rows and the player's top bar print the same name, so a page
        // that is translated must not hand the player an English one (the
        // reported "the page says El primer baile, the player says First
        // Dance"). With no language chosen this stays exactly as it was.
        val language = com.hikari.app.nuvio.TmdbResolver.contentLanguage.takeIf { it.isNotBlank() }
        val needNames = eps.any { EpisodeTitles.needsEnglish(it.name, showName) }
        // Without a language there is nothing to do unless a row's own label
        // needs upgrading (that is what keeps ordinary shows from costing a
        // request); WITH one, every episode is looked up, because the provider's
        // real English title is still not the language the user chose.
        if (language == null && !needNames) return eps
        val names = withTimeoutOrNull(12_000) {
            EpisodeTitles.lookup(showName, item.year, numbers.toSet(), language)
        } ?: return eps
        if (names.isEmpty()) return eps
        var changed = false
        val out = eps.map { e ->
            val raw = e.name
            val replacement = when {
                names.english[e.number] != null -> names.english[e.number]
                raw.isNullOrBlank() -> names.generic[e.number]
                EpisodeTitles.looksMechanical(raw, showName) -> names.generic[e.number]
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

/**
 * What a background sweep the player is HOLDING needs in order to run once it is
 * released: the video, the snapshot the pass had already found, and every caller
 * that asked to hear about new results.
 *
 * File level on purpose: the hold's state lives in [ContentRepository]'s
 * companion object (it is process-wide, like every other sweep's), while the
 * launch that consumes it is a repository method.
 */
private class HeldSweep(
    val item: MediaItem,
    val episode: Episode?,
    @Volatile var snapshot: List<StreamSource>,
    val sinks: MutableList<suspend (List<StreamSource>) -> Unit> = mutableListOf(),
)
