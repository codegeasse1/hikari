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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

object SearchScope {
    @Volatile
    var allExtensions: Boolean = true

    @Volatile
    var engineFamilies: Set<String> = setOf("STREMIO", "NUVIO")

    /** Is [type]'s whole installed family searched for one of its titles? */
    fun family(type: ProviderType): Boolean = type.name in engineFamilies

    val nuvioFamily: Boolean get() = family(ProviderType.NUVIO)

    val stremioFamily: Boolean get() = family(ProviderType.STREMIO)

    /** Extension ids that are always asked for servers (see the class note). */
    @Volatile
    var exceptions: Set<String> = emptySet()

    /** Server-search picks for one Nuvio provider family row being OFF: only
     *  these installed Nuvio ids are asked for a Nuvio title (mirrored from
     *  `AppStore.searchNuvioIdsFlow`). Empty with the family on means the
     *  whole family, which is the default. */
    @Volatile
    var searchNuvioIds: Set<String> = emptySet()

    /** Server-search picks for one Stremio addon family row being OFF (see
     *  [searchNuvioIds]). */
    @Volatile
    var searchStremioIds: Set<String> = emptySet()

    /** Server-search picks for the per-engine family rows being OFF: only
     *  these installed ids of that engine are asked (see [searchNuvioIds]). */
    @Volatile
    var searchFamilyIds: Set<String> = emptySet()

    /** One-tap "this extension only" choice from Play (Settings → Playback &
     *  Servers → Playback start → "Ask where to play"): the provider id the
     *  NEXT lookup must restrict itself to. Consumed by [streamsForInner] the
     *  moment that pass starts, so exactly one lookup runs narrow. */
    @Volatile
    var oneShotOriginOnly: String? = null

    /** True while a server page / player session is open: lookups run
     *  unbounded (no stall/ceiling cut-off) and 0 is loading, never final. */
    @Volatile
    var serverPageOpen: Boolean = false

    /** The picked ids that apply to [type] when its family switch is off. */
    fun pickedIdsFor(type: ProviderType): Set<String> = when (type) {
        ProviderType.NUVIO -> searchNuvioIds
        ProviderType.STREMIO -> searchStremioIds
        else -> searchFamilyIds
    }

    /** Tracker rows: the "search every engine" switch for tracker library
     *  titles (Settings → Trackers), mirrored from the store like everything
     *  above because the scope filter reads it synchronously. */
    @Volatile
    var trackerSearchAll: Boolean = true

    /** Tracker rows: which engine families a tracker title may be searched
     *  through (mirrored from `AppStore.trackerEngineFamiliesFlow`). */
    @Volatile
    var trackerFamilies: Set<String> = setOf("NUVIO", "STREMIO")

    /** Tracker rows: the individually picked extensions a tracker title may
     *  be searched through (mirrored from
     *  `AppStore.trackerProviderIdsFlow`). */
    @Volatile
    var trackerProviders: Set<String> = emptySet()

    /** True when [id] is one of the extension ids marked as an exception. */
    fun isException(id: String?): Boolean = id != null && id in exceptions
}

class ContentRepository(private val manager: ProviderManager) {

    companion object {

        /** The live diagnostic state of ONE pass (see the class note above). */
        class CrossTally {
            val running = ConcurrentHashMap<String, String>()
            val verdict = ConcurrentHashMap<String, String>()
            val asked = ConcurrentHashMap<String, String>()
            val found = ConcurrentHashMap<String, String>()
            val installed = ConcurrentHashMap<String, Int>()
            val primaryRunning = ConcurrentHashMap<String, String>()
            val primaryAsked = ConcurrentHashMap<String, String>()
            val primaryFound = ConcurrentHashMap<String, String>()
            @Volatile
            var filterReasons: Map<String, Int> = emptyMap()
            /** When this tally last changed at all — see
             *  [crossStatusQuietForMs]. */
            @Volatile
            var changedAt: Long = System.currentTimeMillis()
            @Volatile
            var title: String = ""
        }

        /** The tally of the newest pass — the one a summary should describe. */
        @Volatile
        var crossTally = CrossTally()
            private set

        fun publishCrossTally(tally: CrossTally) {
            crossTally = tally
        }

        val crossEmpty = ConcurrentHashMap<String, Long>()

        const val CROSS_EMPTY_TTL_MS = 3 * 60 * 1000L

        class CrossMatch(val item: MediaItem, val at: Long)

        val crossMatch = ConcurrentHashMap<String, CrossMatch>()

        const val CROSS_MATCH_TTL_MS = 10 * 60 * 1000L

        val crossProven: MutableSet<String> = ConcurrentHashMap.newKeySet()

        val crossCfSkip = ConcurrentHashMap<String, Long>()

        const val CROSS_CF_SKIP_TTL_MS = 10 * 60 * 1000L

        /** True while [providerId] should be left out of the pass entirely. */
        fun isCfSkipped(providerId: String): Boolean {
            val at = crossCfSkip[providerId] ?: return false
            if (System.currentTimeMillis() - at < CROSS_CF_SKIP_TTL_MS) return true
            crossCfSkip.remove(providerId)
            return false
        }

        fun providerCounts(list: List<ContentProvider>): String = list
            .groupingBy { it.config.type.groupLabel }
            .eachCount()
            .entries
            .sortedBy { it.key }
            .joinToString(",") { "${it.key}=${it.value}" }

        /** Verdict sentinel meaning "this extension was skipped — say nothing
         *  about it anywhere". Never shown, never counted. */
        const val CROSS_VERDICT_SKIPPED = "\u0000skipped"

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
        const val SEARCH_HANG_AFTER_MS = 25_000L

        const val HUNG_TTL_MS = 120_000L

        val deadSessionUntil = ConcurrentHashMap<String, Long>()

        /** How long a twice-silent provider sits out (see [deadSessionUntil]). */
        const val DEAD_SESSION_TTL_MS = 30 * 60 * 1000L

        /** Consecutive full-budget silences per provider (see [deadSessionUntil]). */
        val noAnswerStrikes = ConcurrentHashMap<String, Int>()

        /** Two consecutive silences retire a provider for the session. */
        const val DEAD_SESSION_STRIKES = 2

        /** Records a full-budget silence from [providerId] (see [deadSessionUntil]). */
        fun noteNoAnswer(providerId: String) {
            val strikes = (noAnswerStrikes[providerId] ?: 0) + 1
            if (strikes >= DEAD_SESSION_STRIKES) {
                noAnswerStrikes.remove(providerId)
                val prev = deadSessionUntil.put(
                    providerId, System.currentTimeMillis() + DEAD_SESSION_TTL_MS
                )
                if (prev == null || System.currentTimeMillis() >= prev) {
                    com.hikari.app.data.Logs.log(
                        "Search",
                        "extension '" + providerId + "' gave no answer " + strikes +
                            " times in a row — sitting out for " +
                            (DEAD_SESSION_TTL_MS / 60000) + "m instead of holding " +
                            "\"still searching\" open",
                    )
                }
            } else {
                noAnswerStrikes[providerId] = strikes
            }
        }

        /** Records an actual answer from [providerId] — silence streak over. */
        fun noteAnswered(providerId: String) {
            noAnswerStrikes.remove(providerId)
        }

        /** …and for a meta/episode fetch, whose own budget is 20-40s. */
        const val DETAIL_HANG_AFTER_MS = 120_000L

        val providerOutcome = ConcurrentHashMap<String, String>()

        val providerOutcomeSeq = ConcurrentHashMap<String, Long>()
        private val outcomeSeq = java.util.concurrent.atomic.AtomicLong(0L)

        /** True while [providerId] should be left out of the search entirely —
         *  i.e. until its wedge expires (see [HUNG_TTL_MS]), or while it sits
         *  out its twice-silent session break (see [deadSessionUntil]). After
         *  either expires it is asked again like any other extension. */
        fun isHung(providerId: String): Boolean {
            deadSessionUntil[providerId]?.let { until ->
                if (System.currentTimeMillis() < until) return true
                deadSessionUntil.remove(providerId, until)
            }
            val at = crossHung[providerId] ?: return false
            if (System.currentTimeMillis() - at < HUNG_TTL_MS) return true
            crossHung.remove(providerId, at)
            return false
        }

        fun clearHung(providerId: String) {
            crossHung.remove(providerId)
        }

        /** True while [providerId] sits out its twice-silent session break
         *  (see [deadSessionUntil]) — expired entries are dropped. Unlike
         *  [isHung] this says nothing about the short wedge TTL. */
        fun isSittingOut(providerId: String): Boolean {
            val until = deadSessionUntil[providerId] ?: return false
            if (System.currentTimeMillis() < until) return true
            deadSessionUntil.remove(providerId, until)
            return false
        }

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

        class InFlight(
            /** The repo this call belongs to, for the progress line and the
             *  "stopped responding" note. The MAP is keyed by a per-call token
             *  instead (see [gated]). */
            val providerId: String,
            val gate: RefundableGate,
            val at: Long,
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

        val CROSS_QUIET_BUCKETS = setOf(
            "cloudflare check",
            "skipped",
        )

        class RememberedStreams(val list: List<StreamSource>, val at: Long)

        val streamsRemembered = ConcurrentHashMap<String, RememberedStreams>()

        fun rememberLiveServers(item: MediaItem, episode: Episode?, list: List<StreamSource>) {
            // Remember disabled per user: every Play re-searches live from zero.
            return
            // dead anchor below keeps the diff reviewable
            if (list.isEmpty()) return
            val key = streamsRememberedKey(item, episode)
            val now = System.currentTimeMillis()
            val prev = streamsRemembered[key]
                ?.takeIf { now - it.at < REMEMBERED_STREAMS_TTL_MS }?.list.orEmpty()
            val merged = (list + prev.filterNot { signedExpired(it) }).distinctBy { mergeKey(it) }.takeLast(256)
            if (merged.isEmpty()) return
            streamsRemembered[key] = RememberedStreams(merged, now)
            if (streamsRemembered.size > REMEMBERED_STREAMS_MAX) {
                val cutoff = now - REMEMBERED_STREAMS_TTL_MS
                streamsRemembered.entries.removeAll { it.value.at < cutoff }
                while (streamsRemembered.size > REMEMBERED_STREAMS_MAX) {
                    val oldest = streamsRemembered.entries.minByOrNull { it.value.at }?.key ?: break
                    streamsRemembered.remove(oldest)
                }
            }
        }

        /** Drops everything this title cached the moment its server page
         *  closes: every Play re-searches from zero instead of painting a
         *  stale list first. Remembered rows, unfinished work and held
         *  snapshots all go — the next lookup rebuilds them. */
        fun forgetLiveServers(item: MediaItem, episode: Episode?) {
            val key = streamsRememberedKey(item, episode)
            streamsRemembered.remove(key)
            pendingWork.remove(key)
            heldSweeps.remove(key)
        }

        const val REMEMBERED_STREAMS_TTL_MS = 60 * 60 * 1000L

        /** Hard ceiling on [streamsRemembered]: the TTL expiry above only
         *  purges when the map is already over this size, and a long session
         *  of fresh lookups could otherwise grow it without bound (every
         *  entry holds full server lists). Past the ceiling the oldest go. */
        const val REMEMBERED_STREAMS_MAX = 64

        /** Freshness window for [directTmdbCache] entries (also enforced on
         *  read), and its hard ceiling — same OOM-hygiene reasoning as
         *  [REMEMBERED_STREAMS_MAX]. */
        const val DIRECT_TMDB_TTL_MS = 30 * 60 * 1000L
        const val DIRECT_TMDB_MAX = 64

        /** Hard ceiling for `tmdbSeasonLayouts`: layouts never go stale so
         *  there is no TTL — overflow drops arbitrary entries, which simply
         *  recompute on demand. */
        const val SEASON_LAYOUT_MAX = 96

        /** Query params a signed URL rotates every call (megaplay token, CDN
         *  expiry): two rows differing ONLY here are one file. Everything else
         *  (host, path, other params) still tells servers apart, so HD-1 vs HD-2
         *  on different CDNs never merge. */
        private val VOLATILE_QUERY_KEYS = setOf(
            "token", "signature", "sig", "sign", "expires", "exp", "expiry",
        )

        /** Merge identity for remembered-vs-fresh union: the plain [identityKey]
         *  when no volatile param is present, else the URL with those params
         *  stripped — so a re-extracted server joins its remembered twin instead
         *  of stacking beside it as "(2)", "(3)", … */
        fun mergeKey(s: StreamSource): String {
            val u = s.url
            if (s.isTorrent || u.isBlank()) return s.identityKey()
            val q = u.indexOf('?')
            if (q < 0) return s.identityKey()
            val tail = u.substring(q + 1)
            val fragAt = tail.indexOf('#')
            val query = if (fragAt < 0) tail else tail.substring(0, fragAt)
            val frag = if (fragAt < 0) "" else tail.substring(fragAt)
            var dropped = false
            val kept = ArrayList<String>()
            for (part in query.split('&')) {
                if (part.substringBefore('=').trim().lowercase() in VOLATILE_QUERY_KEYS) {
                    dropped = true
                    continue
                }
                kept.add(part)
            }
            if (!dropped) return s.identityKey()
            return "u" + u.substring(0, q) +
                (if (kept.isEmpty()) "" else "?" + kept.joinToString("&")) + frag
        }

        /** A remembered signed URL whose own expiry timestamp is past is dead,
         *  not a server — it must not re-enter the list. Reads exp/expires/expiry
         *  as epoch seconds (10 digits) or millis (13 digits). */
        fun signedExpired(s: StreamSource): Boolean = signedExpired(s.url)

        /** A remembered signed URL whose own expiry timestamp is past is dead,
         *  not a server — it must not re-enter the list. Reads exp/expires/expiry
         *  as epoch seconds (10 digits) or millis (13 digits), plus the AWS-style
         *  X-Amz-Date + X-Amz-Expires pair that presigned R2/CDN links carry
         *  (opaque path-token links with no computable expiry are NOT judged
         *  here — a 404 at playback re-extracts them instead of replaying). */
        fun signedExpired(url: String): Boolean {
            val q = url.indexOf('?')
            if (q < 0) return false
            val now = System.currentTimeMillis()
            var amzDate: Long? = null
            var amzExpires: Long? = null
            for (part in url.substring(q + 1).split('&')) {
                val name = part.substringBefore('=').trim().lowercase()
                val value = part.substringAfter('=', "")
                if (name == "x-amz-date") { amzDate = parseAmzDate(value.trim()); continue }
                if (name == "x-amz-expires") { amzExpires = value.trim().toLongOrNull(); continue }
                if (name != "exp" && name != "expires" && name != "expiry") continue
                val digits = value.trim().takeWhile { it.isDigit() }
                val t = digits.toLongOrNull() ?: continue
                val ms = if (digits.length <= 10) t * 1000L else t
                if (ms in 1 until now - 60_000L) return true
            }
            if (amzDate != null && amzExpires != null &&
                amzExpires > 0 && amzDate + amzExpires * 1000L in 1 until now - 60_000L
            ) return true
            return false
        }

        /** Parses an X-Amz-Date stamp (yyyyMMdd'T'HHmmss'Z'). Null when the
         *  value is not in that shape — never throws, never condemns. */
        private fun parseAmzDate(v: String): Long? {
            if (v.length != 16 || v[8] != 'T' || v[15] != 'Z') return null
            return try {
                val cal = java.util.GregorianCalendar(java.util.TimeZone.getTimeZone("UTC"))
                cal.isLenient = false
                cal.set(
                    v.substring(0, 4).toInt(), v.substring(4, 6).toInt() - 1,
                    v.substring(6, 8).toInt(), v.substring(9, 11).toInt(),
                    v.substring(11, 13).toInt(), v.substring(13, 15).toInt(),
                )
                cal.set(java.util.Calendar.MILLISECOND, 0)
                cal.timeInMillis
            } catch (_: Exception) {
                null
            }
        }

        const val CROSS_THIN_RESULT = 6

        fun streamsRememberedKey(item: MediaItem, episode: Episode?): String =
            streamsRememberedKey(item, episode, "")

        fun streamsRememberedKey(item: MediaItem, episode: Episode?, extra: String): String =
            item.uniqueId + "|" + (episode?.id ?: "") + extra

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
            @Volatile
            var lastProgressAt: Long = System.currentTimeMillis()
            val answered: MutableSet<String> = ConcurrentHashMap.newKeySet()
            /** The scope this sweep may ask (see [PendingWork.scopeIds]): a pass
             *  whose own scope excludes it must not attach its sink to this
             *  sweep, or out-of-scope servers would stream into its list. */
            @Volatile
            var scopeIds: Set<String>? = null
        }

        class SweepUnit(val provider: ContentProvider, val byTitle: Boolean)

        /** The background sweeps currently running, keyed by
         *  [streamsRememberedKey] (title+episode). See [startSweepIfNeeded]. */
        val sweeps = ConcurrentHashMap<String, Sweep>()

        val sweepOwned: MutableSet<String> = ConcurrentHashMap.newKeySet()

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
            /** The scope a pass recorded with its unfinished work (see [SearchScope]
             *  and [scopeAllowedIds]): a later, narrower pass REPLACES it, so a wide
             *  lookup's backlog can never leak into a lookup whose own switches
             *  exclude those repos. Null = unconstrained (legacy entries). */
            @Volatile var scopeIds: Set<String>? = null,
            @Volatile var at: Long = System.currentTimeMillis(),
        )

        val pendingWork = ConcurrentHashMap<String, PendingWork>()

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

        fun pauseSweepFor(item: MediaItem, episode: Episode?): String {
            val key = holdSweepFor(item, episode)
            sweeps[key]?.job?.cancel()
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

        private suspend fun awaitBackgroundClearance(onWait: () -> Unit = {}): Long {
            if (!foregroundBusy()) return 0L
            val started = System.currentTimeMillis()
            while (foregroundBusy()) {
                onWait()
                kotlinx.coroutines.delay(150)
            }
            return System.currentTimeMillis() - started
        }

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
            /** The scope this pass allows (see [scopeAllowedIds]): REPLACED, not
             *  unioned — a narrower pass shrinks what the ledger may still ask,
             *  so an old wide backlog can never leak into it. Null keeps the
             *  recorded scope (automatic re-records). */
            scopeIds: Set<String>? = null,
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
            if (scopeIds != null) entry.scopeIds = scopeIds
            entry.at = now
        }

        fun sweepBusyFor(item: MediaItem, episode: Episode?): Boolean {
            val key = streamsRememberedKey(item, episode)
            if (key in heldSweepKeys) return false
            val sweep = sweeps[key]
            if (sweep != null && sweep.job?.isActive == true && sweepIsFresh(sweep)) return true
            val pending = pendingWork[key] ?: return false
            val waiting = pending.byTitle.isNotEmpty() || pending.direct.isNotEmpty()
            return waiting
        }

        const val SWEEP_STALE_MS = 90_000L

        private fun sweepIsFresh(sweep: Sweep): Boolean =
            System.currentTimeMillis() - sweep.lastProgressAt < SWEEP_STALE_MS

        fun anySweepBusy(): Boolean = sweeps.values.any { it.job?.isActive == true && sweepIsFresh(it) }

        fun unfinishedSearchCount(): Int {
            val t = crossTally
            var n = t.running.size + t.primaryRunning.size + sweepOwned.size
            pendingWork.values.forEach { n += it.byTitle.size + it.direct.size }
            return n
        }

        fun crossEmptyKey(providerId: String, query: String): String =
            providerId + "|" + query.trim().lowercase()

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

        fun crossStatusQuietForMs(): Long =
            System.currentTimeMillis() - crossTally.changedAt

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
            verdict.contains("stopped responding") -> "stuck, skipped"
            else -> "other"
        }

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

        fun crossSummary(limit: Int = 3, title: String? = null): String? {
            val tally = crossTally
            if (title != null && tally.title.isNotBlank() && !tally.title.equals(title, true)) return null
            if (tally.verdict.isEmpty() && tally.found.isEmpty()) return null
            val counts = tally.verdict.values
                .groupingBy { crossReasonBucket(it) }
                .eachCount()
                .entries
                .filterNot { it.key in CROSS_QUIET_BUCKETS }
                .sortedByDescending { it.value }
                .take(limit)
                .joinToString(" · ") { "${it.value} ${crossBucketLabel(it.key)}" }
            return buildString {
                append("asked ${tally.asked.size + tally.primaryAsked.size}")
                val withServers = tally.found.size + tally.primaryFound.size
                if (withServers > 0) append(" · $withServers with servers")
                if (counts.isNotEmpty()) append(" · ").append(counts)
            }
        }
        /** How long [loadCatalogPage] waits before re-asking a catalogue that
         *  answered an empty page while the user was looking at it. */
        private const val CATALOG_RETRY_MS = 900L

        /** Ceiling on one catalogue page read inside [loadCatalogPage] — a
         *  hung host must end in the last good answer, never a forever
         *  spinner (see it). */
        private const val CATALOG_PAGE_TIMEOUT_MS = 20_000L

        private val lastGoodCatalogPage =
            object : LinkedHashMap<String, List<MediaItem>>(48, 0.75f, true) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<String, List<MediaItem>>?,
                ): Boolean = size > 240
            }

        /** Like runCatching but re-throws CancellationException, so a cancelled
         *  coroutine stops its provider call instead of swallowing the
         *  cancellation and keeping the network busy in the background. */
        private inline fun <T> catalogCatching(block: () -> T): Result<T> =
            try {
                Result.success(block())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                Result.failure(t)
            }

        suspend fun loadCatalogPage(
            p: ContentProvider?,
            ref: CatalogRef,
            page: Int,
            retryEmpty: Boolean = false,
            onCached: ((List<MediaItem>) -> Unit)? = null,
        ): List<MediaItem> {
            p ?: return emptyList()
            val key = "${p.config.id}|${ref.type}|${ref.id}|$page"
            val diskKey = MetaCache.catalogKey(p.config.id, ref, page)
            if (onCached != null) {
                MetaCache.cachedCatalog(diskKey)?.let(onCached)
            }
            var firstHung = false
            var items = withTimeoutOrNull(CATALOG_PAGE_TIMEOUT_MS) {
                catalogCatching { p.getCatalog(ref, page) }.getOrDefault(emptyList())
            } ?: run { firstHung = true; emptyList() }
            if (firstHung) {
                com.hikari.app.data.Logs.log(
                    "Catalog",
                    "${p.config.name}: page $page gave up after ${CATALOG_PAGE_TIMEOUT_MS / 1000}s — showing last answer",
                )
            }
            if (items.isEmpty() && retryEmpty && !firstHung) {
                delay(CATALOG_RETRY_MS)
                items = withTimeoutOrNull(CATALOG_PAGE_TIMEOUT_MS) {
                    catalogCatching { p.getCatalog(ref, page) }.getOrDefault(emptyList())
                } ?: emptyList()
            }
            if (items.isNotEmpty()) {
                synchronized(lastGoodCatalogPage) { lastGoodCatalogPage[key] = items }
                MetaCache.putCatalog(diskKey, items)
                return items
            }
            if (page != 1) return emptyList()
            val last = synchronized(lastGoodCatalogPage) { lastGoodCatalogPage[key] }.orEmpty()
            return last.ifEmpty { MetaCache.cachedCatalog(diskKey, Long.MAX_VALUE).orEmpty() }
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

    private val SEARCH_PAGE_TIMEOUT_MS get() = minOf(NetTuning.timeout(25_000L), 30_000L)
    private val SEARCH_PROVIDER_BUDGET_MS get() = minOf(NetTuning.timeout(90_000L), 90_000L)
    private val SEARCH_TOTAL_BUDGET_MS get() = minOf(NetTuning.timeout(60_000L), 60_000L)


    private val CROSS_EXT_GRACE_MS = 600L

    private val ORIGIN_WARM_PAUSE_MS = 2_500L

    private val ORIGIN_HEAD_START_MS = 250L
    private val SAME_ENGINE_HEAD_START_MS = 400L

    private val ORIGIN_SETTLE_MAX_MS = 2_000L

    private val SLOW_CALL_LOG_MS = 3_000L

    /** How long a background re-ask of a pass's PRIMARY targets waits before it
     *  runs: just enough for the pass's own cancelled calls to let go of the
     *  plugins (and of the nuvio engine slots), so the retry meets a warm
     *  runtime instead of a queue. See the hand-off in the pass's teardown. */
    private val SWEEP_DIRECT_HEAD_START_MS = 3_000L

    /** Total wall-clock budget for the whole cross-extension pass, measured
     *  from when it starts. Comfortably under the player's live-wait timeout so
     *  servers found here still reach a player that is already open and
     *  waiting. */
    private val CROSS_EXT_BUDGET_MS get() = minOf(NetTuning.timeout(60_000L), 60_000L)

    /** Verdicts a background sweep is allowed to retry (see
     *  [startSweepIfNeeded]). Everything here means "the repo was never really
     *  asked", as opposed to "the repo answered, and the answer was no". */
    private val SWEEP_RETRY_BUCKETS =
        setOf("no answer in time", "timed out", "search error", "could not load")

    private val SWEEP_MATCHED_NO_LINKS_BUCKET = "title found, no links"

    private val CROSS_SWEEP_MIN_TARGETS = 10

    private val MAX_PARALLEL_SWEEPS = 2

    private val SWEEP_BUDGET_MS get() = minOf(NetTuning.timeout(45_000L), 45_000L)

    private val SWEEP_MAX_ROUNDS = 2

    private val SWEEP_REPO_BUDGET_MS get() = minOf(NetTuning.timeout(45_000L), 50_000L)

    /** How many repos a sweep works on at once. Small on purpose: this is the
     *  background half of the search, running while the phone is already
     *  decoding video, and cold-loading thirty plugin runtimes at once is what
     *  wedges them. */
    private val SWEEP_WORKERS = 8

    private val CROSS_EXT_WAVE_START = 12
    /** Ceiling on one wave of repo searches — see [deviceFanOut]. A getter, not
     *  a value: the performance booster can be switched on while the app is
     *  running, and the NEXT lookup has to see it. */
    private val CROSS_EXT_WAVE_MAX: Int get() = deviceFanOut()
    // Tight stagger so answers arrive in big bursts: the sheet paints every
    // arrival window as one batch (20-30 rows landing together is the normal
    // case, not a special path), each paint as soon as it lands — never held
    // for a full set, never cancelling an in-flight extraction. Gaps are short
    // so the first servers paint in ~1s and the queue still never cold-loads
    // dozens of runtimes in the same instant. The wedge
    // watchdog ([CROSS_EXT_STALL_MS]) and the device fan-out ceiling still
    // bound the work; TV and booster caps are untouched.
    private val CROSS_EXT_WAVE_GAP_MS = 150L
    private val CROSS_EXT_WAVE_SLOW_MS = 400L

    private val CROSS_EXT_STALL_MS = 25_000L

    private val NUVIO_TAIL_MS = 25_000L

    private val CROSS_EXT_SEARCH_TIMEOUT_MS get() = minOf(NetTuning.timeout(20_000L), 25_000L)
    private val CROSS_EXT_EPISODES_TIMEOUT_MS get() = minOf(NetTuning.timeout(20_000L), 25_000L)
    private val CROSS_EXT_META_TIMEOUT_MS get() = minOf(NetTuning.timeout(15_000L), 20_000L)
    private val CROSS_EXT_STREAMS_TIMEOUT_MS get() = minOf(NetTuning.timeout(18_000L), 20_000L)

    private fun isAniyomi(p: ContentProvider) = p.config.type == ProviderType.ANIYOMI
    private val ANIYOMI_SEARCH_TIMEOUT_MS get() = minOf(NetTuning.timeout(20_000L), 25_000L)
    private val ANIYOMI_META_TIMEOUT_MS get() = minOf(NetTuning.timeout(45_000L), 60_000L)
    private val ANIYOMI_EPISODES_TIMEOUT_MS get() = minOf(NetTuning.timeout(75_000L), 90_000L)
    private val ANIYOMI_STREAMS_TIMEOUT_MS get() = minOf(NetTuning.timeout(35_000L), 40_000L)

    private fun searchTimeoutMs(p: ContentProvider) =
        if (isAniyomi(p)) ANIYOMI_SEARCH_TIMEOUT_MS else CROSS_EXT_SEARCH_TIMEOUT_MS

    private fun metaTimeoutMs(p: ContentProvider) =
        if (isAniyomi(p)) ANIYOMI_META_TIMEOUT_MS else CROSS_EXT_META_TIMEOUT_MS

    private fun episodesTimeoutMs(p: ContentProvider) =
        if (isAniyomi(p)) ANIYOMI_EPISODES_TIMEOUT_MS else CROSS_EXT_EPISODES_TIMEOUT_MS

    private fun streamsTimeoutMs(p: ContentProvider) =
        if (isAniyomi(p)) ANIYOMI_STREAMS_TIMEOUT_MS else CROSS_EXT_STREAMS_TIMEOUT_MS

    private suspend fun streamsTimeoutFor(p: ContentProvider): Long {
        val overrideSecs = runCatching {
            HikariApp.instance.store.serverTimeoutFor(p.config.id)
        }.getOrNull()
        if (overrideSecs != null) return overrideSecs * 1000L
        return streamsTimeoutMs(p)
    }

    /** The Detail screen's meta fetch. A manifest-backed addon answers in
     *  milliseconds; an Aniyomi extension is an APK that has to be class-loaded
     *  first, and timing THAT out is what left an Aniyomi item with no meta —
     *  and so with the raw catalogue type (see [AniyomiProvider.toItem]). */
    private fun metaForTimeoutMs(p: ContentProvider) =
        if (isAniyomi(p)) ANIYOMI_META_TIMEOUT_MS
        else if (p.config.type == ProviderType.SKYSTREAM) 30_000L
        else 15_000L

    /** The Detail screen's episode fetch — the same story as [metaForTimeoutMs]:
     *  an Aniyomi extension's episode list is a real scrape, and at 12s a cold
     *  one simply never answered. */
    private fun episodesForTimeoutMs(p: ContentProvider) =
        if (isAniyomi(p)) ANIYOMI_EPISODES_TIMEOUT_MS
        else if (p.config.type == ProviderType.SKYSTREAM) 30_000L
        else 12_000L

    /** One shared budget for the TMDB-id episode shortcut above: TMDB is found
     *  within it or the extension's own list is shown instead, instantly. */
    private val TMDB_SHORTCUT_MS = 3_000L

    /** How many installed extensions may be asked for an episode list when the
     *  origin's own list came back empty (see [episodesFromExtensions]). */
    private val EPISODES_FALLBACK_TARGETS = 12

    /** Wall-clock ceiling for that whole fallback sweep. Generous, because it
     *  is only entered when the detail page otherwise has NO episodes at all —
     *  but still bounded, so a page of dead extensions cannot hang the screen. */
    private val EPISODES_FALLBACK_BUDGET_MS get() = minOf(NetTuning.timeout(50_000L), 50_000L)

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

    private val CROSS_EXT_SEARCH_CONCURRENCY: Int = deviceFanOut()
    private val CROSS_EXT_EXTRACT_CONCURRENCY =
        if (com.hikari.app.data.PerfMode.tvDevice) 10 else 20
    private val CROSS_EXT_DETAIL_CONCURRENCY =
        if (com.hikari.app.data.PerfMode.tvDevice) 12 else 32
    private val CROSS_EXT_SEARCH_GATE = RefundableGate(CROSS_EXT_SEARCH_CONCURRENCY)
    private val CROSS_EXT_EXTRACT_GATE = RefundableGate(CROSS_EXT_EXTRACT_CONCURRENCY)
    private val CROSS_EXT_DETAIL_GATE = RefundableGate(CROSS_EXT_DETAIL_CONCURRENCY)

    private fun nuvioOrder(originId: String): Comparator<ContentProvider> = compareBy(
        { p: ContentProvider -> if (p.config.id == originId) 0 else 1 },
        { p: ContentProvider ->
            val idx = NUVIO_PRIORITY.indexOf(p.config.name.lowercase())
            if (idx >= 0) idx else NUVIO_PRIORITY.size
        },
    )

    private fun stremioOrder(originId: String): Comparator<ContentProvider> =
        compareBy { p: ContentProvider -> if (p.config.id == originId) 0 else 1 }

    private fun deviceFanOut(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        if (com.hikari.app.data.PerfMode.tvDevice) return (cores * 2).coerceIn(6, 16)
        if (com.hikari.app.data.PerfMode.active) return (cores * 3).coerceIn(12, 36)
        return (cores * 6).coerceIn(24, 96)
    }

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
            if (!call.refunded.get()) gate.release()
        }
    }

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

    private suspend fun fetchStreams(
        p: ContentProvider,
        item: MediaItem,
        episode: Episode?,
        /** [streamsForOutcome]'s running pass, or -1 when the caller is not a
         *  pass (a sweep, a detached re-ask): only a pass stamps verdicts, so
         *  only a pass can claim them as its own unfinished work. */
        passSeq: Long = -1L,
    ): List<StreamSource> {
        val isOrigin = p.config.id == item.providerId
        val playEp = resolveOwnEpisode(p, item, episode, isOrigin)
        val fullTimeoutMs =
            if (isAniyomi(p)) minOf(NetTuning.timeout(90_000L), 120_000L)
            else if (p.config.type == ProviderType.NUVIO) {
                minOf(maxOf(NetTuning.timeout(45_000L), 60_000L), 75_000L)
            } else NetTuning.timeout(45_000L)
        // CloudStream waits out the plugin's own loadLinks budget (up to 8
        // min, 2 min default — see APIRepository.getTimeout) on the title it
        // is playing. An attempt capped below that budget cancels a slow but
        // working provider mid-flight here while CloudStream plays it there.
        // So the ORIGIN CloudStream provider — the one this title came from —
        // gets the full plugin budget per attempt (capped at CloudStream's own
        // 8-minute max); the cross-extension pass keeps its discipline so one
        // slow repo cannot hold 250 others hostage.
        val originCs3BudgetMs = if (isOrigin && p is Cs3MainApiProvider) {
            runCatching { p.effectivePluginTimeoutMs() }.getOrDefault(0L)
                .coerceIn(0L, 480_000L)
        } else 0L
        val originSkyBudgetMs =
            if (isOrigin && p.config.type == ProviderType.SKYSTREAM) 90_000L else 0L
        val attemptBudgetMs = maxOf(fullTimeoutMs, originCs3BudgetMs, originSkyBudgetMs)
        var budgetMs = attemptBudgetMs
        val maxAttempts = if (isOrigin) maxOf(NetTuning.attempts(), 3) else NetTuning.attempts()
        var attempt = 0
        var lastWhy: String? = null
        var recovered = false
        while (true) {
            attempt++
            var timedOut = false
            val at = System.currentTimeMillis()
            val got = cancellableCatching {
                val r = withTimeoutOrNull(budgetMs) { p.getStreams(item, playEp) }
                if (r == null) timedOut = true
                r.orEmpty()
            }.getOrElse { t ->
                lastWhy = t.javaClass.simpleName + (t.message?.let { ": ${it.take(80)}" } ?: "")
                emptyList()
            }
            val took = (System.currentTimeMillis() - at) / 1000
            if (got.isNotEmpty()) {
                val combined = supplementDubSubVariants(p, item, playEp, got, attemptBudgetMs)
                providerOutcome.remove(p.config.id)
                if (passSeq >= 0L) providerOutcomeSeq.remove(p.config.id)
                if (playEp == null && item.type == MediaType.SERIES) {
                    SingleVideoEvidence.note(item.uniqueId)
                }
                // It answered — whatever it is, the wedge (if any) is over.
                com.hikari.app.providers.ExtensionRecovery.noteSuccess(p.config.id)
                if (isOrigin || took >= SLOW_CALL_LOG_MS / 1000) {
                    com.hikari.app.data.Logs.log(
                        "Provider",
                        p.config.name.ifBlank { p.config.id } +
                            " [${p.config.type.groupLabel}]" +
                            (if (isOrigin) " (this title's own extension)" else "") +
                            ": ${combined.size} server(s) in ${took}s (attempt $attempt/$maxAttempts)",
                    )
                }
                return combined
            }
            if (timedOut) lastWhy = "no answer in ${took}s"
            val gateStalled = lastWhy?.startsWith("ProviderStalledException") == true
            if ((timedOut || gateStalled) && !recovered &&
                com.hikari.app.providers.ExtensionRecovery.noteTimeout(p.config.id) >= 2 &&
                com.hikari.app.providers.ExtensionRecovery.shouldRecover(p.config.id)
            ) {
                recovered = true
                runCatching {
                    com.hikari.app.providers.ExtensionRecovery.recover(HikariApp.instance, p.config)
                }
                com.hikari.app.data.Logs.log(
                    "Provider",
                    (p.config.name.ifBlank { p.config.id }) +
                        " [${p.config.type.groupLabel}]: stall broken mid-lookup — " +
                        "one fresh attempt with the full ${attemptBudgetMs / 1000}s budget",
                )
            }
            if (attempt >= maxAttempts + (if (recovered) 1 else 0)) {
                val cleanRoomed = cleanRoomRetry(p, item, playEp, attemptBudgetMs, isOrigin, timedOut, lastWhy)
                if (cleanRoomed.isNotEmpty()) {
                    val combined =
                        supplementDubSubVariants(p, item, playEp, cleanRoomed, attemptBudgetMs)
                    providerOutcome.remove(p.config.id)
                    if (passSeq >= 0L) providerOutcomeSeq.remove(p.config.id)
                    com.hikari.app.providers.ExtensionRecovery.noteSuccess(p.config.id)
                    com.hikari.app.data.Logs.log(
                        "Provider",
                        p.config.name.ifBlank { p.config.id } +
                            " [${p.config.type.groupLabel}]" +
                            ": clean-room retry answered — " +
                            "${combined.size} server(s)",
                    )
                    return combined
                }
                // Stale input, healed here instead of failing the tap: the
                // episode `data` string (or the title load behind it) this
                // lookup replayed can predate the installed provider build (a
                // cached episode list plus a silent extension update — the
                // epoch in [MetaCache] stops new staleness) or simply have
                // rotted (signed/redirect/session payloads; CloudStream never
                // replays one — 10-minute load cache, no episode list on disk
                // at all). Fires on a deserialization-shaped provider error OR
                // when a cache actually fed this lookup — never for a lookup
                // that already ran fresh, and never for genuine empties.
                val healed = healStaleEpisodePayload(p, item, playEp, attemptBudgetMs)
                if (healed.isNotEmpty()) {
                    val combined =
                        supplementDubSubVariants(p, item, playEp, healed, attemptBudgetMs)
                    providerOutcome.remove(p.config.id)
                    if (passSeq >= 0L) providerOutcomeSeq.remove(p.config.id)
                    com.hikari.app.providers.ExtensionRecovery.noteSuccess(p.config.id)
                    com.hikari.app.data.Logs.log(
                        "Provider",
                        p.config.name.ifBlank { p.config.id } +
                            " [${p.config.type.groupLabel}]" +
                            ": stale episode payload re-resolved fresh — " +
                            "${combined.size} server(s)",
                    )
                    return combined
                }
                val why = lastWhy ?: "no servers for this episode"
                val said = providerStreamMessage(p)
                val noAnswer = lastWhy != null || isNoAnswer(said)
                providerOutcome[p.config.id] = when {
                    noAnswer -> "✗ " + (lastWhy ?: said ?: "the call never came back")
                    else -> "no servers"
                }
                if (passSeq >= 0L) providerOutcomeSeq[p.config.id] = passSeq
                com.hikari.app.data.Logs.log(
                    "Provider",
                    p.config.name.ifBlank { p.config.id } +
                        " [${p.config.type.groupLabel}]" +
                        (if (isOrigin) " (this title's own extension)" else "") +
                        ": " + when {
                            timedOut -> "✗ $why"
                            noAnswer -> "✗ " + (lastWhy ?: said ?: "the call never came back")
                            said != null -> said
                            else -> "no servers"
                        },
                )
                return got
            }
            val nextBudget = if (timedOut || took > 5) minOf(budgetMs, 10_000L) else attemptBudgetMs
            com.hikari.app.data.Logs.log(
                "Provider",
                p.config.name.ifBlank { p.config.id } + " [" + p.config.type.groupLabel + "]" +
                    ": attempt $attempt/$maxAttempts answered nothing" +
                    (lastWhy?.let { " ($it)" } ?: "") +
                    " — asking again with a ${nextBudget / 1000}s budget",
            )
            if (isOrigin && !timedOut && lastWhy == null && took < 3 &&
                providerStreamMessage(p) == null
            ) {
                kotlinx.coroutines.delay(ORIGIN_WARM_PAUSE_MS)
            }
            budgetMs = nextBudget
        }
    }

    private suspend fun healStaleEpisodePayload(
        p: ContentProvider,
        item: MediaItem,
        episode: Episode?,
        fullTimeoutMs: Long,
    ): List<StreamSource> {
        if (p.config.type != ProviderType.CS3) return emptyList()
        val api = p as? Cs3MainApiProvider ?: return emptyList()
        val deser = isDeserializationFailure(providerStreamMessage(p))
        val staleInput = api.hasStaleLoadFor(item.id) ||
            runCatching {
                com.hikari.app.data.MetaCache.hasEpisodes(item.uniqueId)
            }.getOrDefault(false)
        if (!deser && !staleInput) return emptyList()
        return try {
            api.evictLoadCacheFor(item.id)
            runCatching { com.hikari.app.data.MetaCache.evictEpisodes(item.uniqueId) }
            val freshEps = if (episode == null) {
                null
            } else {
                withTimeoutOrNull(20_000L) { api.getEpisodes(item) }.orEmpty()
            }
            val freshEp = if (episode == null) {
                null
            } else {
                if (freshEps.isNullOrEmpty()) return emptyList()
                // The re-resolved list is the new cache: the next tap must
                // replay THESE payloads, not the rotten ones just evicted.
                runCatching {
                    com.hikari.app.data.MetaCache.putEpisodes(
                        com.hikari.app.data.MetaCache.episodesKey(item.uniqueId),
                        freshEps,
                    )
                }
                freshEps.firstOrNull { it.season == episode.season && it.number == episode.number }
                    ?: freshEps.firstOrNull { it.number == episode.number }
                    ?: return emptyList()
            }
            val budget = minOf(fullTimeoutMs, 120_000L)
            withTimeoutOrNull(budget) { api.getStreams(item, freshEp) }.orEmpty()
        } catch (e: Throwable) {
            currentCoroutineContext().ensureActive()
            emptyList()
        }
    }

    private suspend fun cleanRoomRetry(
        p: ContentProvider,
        item: MediaItem,
        episode: Episode?,
        fullTimeoutMs: Long,
        isOrigin: Boolean,
        timedOut: Boolean,
        lastWhy: String?,
    ): List<StreamSource> {
        if (timedOut || lastWhy != null) return emptyList()
        if (p.config.type != ProviderType.CS3) return emptyList()
        val api = p as? Cs3MainApiProvider ?: return emptyList()
        val said = providerStreamMessage(p)
        if (isDeserializationFailure(said)) return emptyList()
        if (said != null && !isEmptySuccess(said)) return emptyList()
        if (said != null && isSiteWall(said)) return emptyList()
        if (!isOrigin) {
            com.hikari.app.data.Logs.log(
                "Provider",
                p.config.name.ifBlank { p.config.id } +
                    " [${p.config.type.groupLabel}]" +
                    ": non-origin empty-success — one bounded clean-room retry",
            )
        }
        return try {
            if (!com.hikari.app.providers.ExtensionRecovery.recoverEmpty(
                    HikariApp.instance, p.config
                )
            ) return emptyList()
            api.evictLoadCacheFor(item.id)
            runCatching { com.hikari.app.data.MetaCache.evictEpisodes(item.uniqueId) }
            val freshEp = if (episode == null) {
                null
            } else {
                val freshEps = withTimeoutOrNull(20_000L) { api.getEpisodes(item) }.orEmpty()
                if (freshEps.isEmpty()) return emptyList()
                runCatching {
                    com.hikari.app.data.MetaCache.putEpisodes(
                        com.hikari.app.data.MetaCache.episodesKey(item.uniqueId),
                        freshEps,
                    )
                }
                freshEps.firstOrNull { it.season == episode.season && it.number == episode.number }
                    ?: freshEps.firstOrNull { it.number == episode.number }
                    ?: return emptyList()
            }
            val budget = minOf(fullTimeoutMs, 120_000L)
            withTimeoutOrNull(budget) { api.getStreams(item, freshEp) }.orEmpty()
        } catch (e: Throwable) {
            currentCoroutineContext().ensureActive()
            emptyList()
        }
    }

    /**
     * True when a provider's own message is the answered-empty shape — it
     * ran and found nothing — as opposed to a specific diagnosis.
     */
    private fun isEmptySuccess(text: String?): Boolean {
        if (text == null) return true
        val t = text.lowercase()
        return t.contains("no stream links") ||
            t.contains("no playable source") ||
            t.contains("no sources for this title") ||
            t.contains("no servers")
    }

    /**
     * True when a provider's own message already names a wall reloading
     * cannot lift (refusal, bot check, dead/gone page, unreachable site).
     */
    private fun isSiteWall(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val t = text.lowercase()
        return t.contains("refused") ||
            t.contains("bot check") ||
            t.contains("cannot pass") ||
            t.contains("challenge") ||
            t.contains("http 401") ||
            t.contains("http 403") ||
            t.contains("http 404") ||
            t.contains("http 410") ||
            t.contains("http 5") ||
            t.contains("gone (http") ||
            t.contains("empty page") ||
            t.contains("could not be reached")
    }

    private fun isDeserializationFailure(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val t = text.lowercase()
        return t.contains("missingkotlinparameterexception") ||
            t.contains("creator parameter") ||
            t.contains("mismatchedinputexception") ||
            t.contains("unrecognizedpropertyexception") ||
            t.contains("invaliddefinitionexception") ||
            t.contains("cannot construct") ||
            t.contains("jsonmappingexception") ||
            t.contains("databind")
    }

    private suspend fun supplementDubSubVariants(
        p: ContentProvider,
        item: MediaItem,
        episode: Episode?,
        primary: List<StreamSource>,
        budgetMs: Long,
    ): List<StreamSource> {
        if (episode == null || p.config.id != item.providerId) return primary
        // Cross-module twins first (Sora dub/sub live in separate modules): the
        // twin's servers join the SAME pass, tagged, instead of never appearing.
        val base = if (p.config.type == ProviderType.SORA) {
            runCatching { soraTwinMerged(p, item, episode, primary, budgetMs) }.getOrDefault(primary)
        } else primary
        val group = EpisodeDubSub.variantsFor(item.uniqueId, episode.season, episode.number)
        if (group.size < 2) return base
        val siblings = EpisodeDubSub.siblingVariants(item.uniqueId, episode)
        if (siblings.isEmpty()) return base
        // The merged row's own name is cleaned of markers, so the audio each
        // ORIGINAL row names is read off the registered group instead.
        val kindOfId = group.associate { it.id to EpisodeDubSub.audioKindOf(it) }
        val seen = HashSet<String>(base.size + siblings.size)
        base.forEach { seen.add(it.url) }
        val out = ArrayList(base)
        for ((sibIndex, sib) in siblings.withIndex()) {
            val extra = withTimeoutOrNull(minOf(budgetMs, 30_000L)) {
                cancellableCatching { p.getStreams(item, sib) }.getOrDefault(emptyList())
            }.orEmpty()
            if (extra.isEmpty()) continue
            var kind = kindOfId[sib.id]
            if (kind == null) {
                kind = extra.mapNotNull { EpisodeDubSub.kindOfStream(it.name, it.url) }
                    .distinct()
                    .singleOrNull()
            }
            if (kind == null) kind = "Audio ${sibIndex + 2}"
            for (s in extra) {
                if (!seen.add(s.url)) continue
                out += s.copy(name = EpisodeDubSub.tagStream(s.name, kind))
            }
        }
        if (out.size == base.size) return base
        val primaryKind = kindOfId[episode.id]
            ?: base.mapNotNull { EpisodeDubSub.kindOfStream(it.name, it.url) }.distinct().singleOrNull()
            ?: "Audio 1"
        for (i in base.indices) {
            val s = out[i]
            if (!EpisodeDubSub.isTagged(s.name)) out[i] = s.copy(name = "${s.name} ($primaryKind)")
        }
        return out
    }

    private suspend fun soraTwinMerged(
        p: ContentProvider,
        item: MediaItem,
        episode: Episode,
        primary: List<StreamSource>,
        budgetMs: Long,
    ): List<StreamSource> {
        if (primary.isEmpty()) return primary
        val kinds = primary.mapNotNull { EpisodeDubSub.kindOfStream(it.name, it.url) }.distinct()
        if (kinds.size != 1) return primary
        val need = if (kinds[0] == "Dub") "Sub" else if (kinds[0] == "Sub") "Dub" else return primary
        // The origin itself must wear the opposite marker — unmarked names never
        // twin-merge, or two unrelated Sora repos would pour into each other.
        if (EpisodeDubSub.audioKindOf(Episode(number = 0, id = p.config.id, name = p.config.name)) == need) {
            return primary
        }
        if (EpisodeDubSub.audioKindOf(Episode(number = 0, id = p.config.id, name = p.config.name)) == null) {
            return primary
        }
        val twin = manager.providers.value.firstOrNull { o ->
            o.config.enabled && o.config.type == ProviderType.SORA && o.config.id != p.config.id &&
                twinStem(o.config.name) == twinStem(p.config.name) &&
                EpisodeDubSub.audioKindOf(Episode(number = 0, id = o.config.id, name = o.config.name)) == need
        } ?: return primary
        val extra = withTimeoutOrNull(minOf(budgetMs, 30_000L)) {
            cancellableCatching {
                val direct = twin.getStreams(item, episode)
                if (direct.isNotEmpty()) direct else {
                    val eps = twin.getEpisodes(item) ?: return@cancellableCatching emptyList<StreamSource>()
                    val match = eps.firstOrNull { it.season == episode.season && it.number == episode.number }
                        ?: return@cancellableCatching emptyList<StreamSource>()
                    twin.getStreams(item, match)
                }
            }.getOrDefault(emptyList())
        }.orEmpty()
        if (extra.isEmpty()) return primary
        // The twin must actually speak the missing side; same-sided answers
        // merge nothing.
        val twinKinds = extra.mapNotNull { EpisodeDubSub.kindOfStream(it.name, it.url) }.distinct()
        val kind = if (twinKinds == listOf(need)) need
        else twinKinds.singleOrNull()?.takeIf { it != kinds[0] } ?: return primary
        val seen = HashSet<String>(primary.size + extra.size)
        primary.forEach { seen.add(it.url) }
        val out = ArrayList(primary)
        for (s in extra) {
            if (!seen.add(s.url)) continue
            out += s.copy(name = EpisodeDubSub.tagStream(s.name, kind))
        }
        if (out.size == primary.size) return primary
        for (i in primary.indices) {
            val s = out[i]
            if (!EpisodeDubSub.isTagged(s.name)) out[i] = s.copy(name = "${s.name} (${kinds[0]})")
        }
        return out
    }

    /** Twin-module name stem: dub/sub/audio markers stripped, so "Anikoto DUB"
     *  and "Anikoto SUB" compare equal but unrelated repos never do. */
    private fun twinStem(name: String): String = name.lowercase()
        .replace(Regex("\\b(english\\s+)?dub(bed)?\\b|\\bsub(s|bed|titles?)?\\b|\\bsoftsub\\b|\\baudio\\b"), " ")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

    private fun tagGroup(
        list: List<StreamSource>,
        p: ContentProvider,
    ): List<StreamSource> {
        if (list.isEmpty()) return list
        val label = p.config.type.groupLabel
        return list.map { s ->
            s.copy(
                provider = s.provider.ifBlank { label },
                providerId = s.providerId.ifBlank { p.config.id },
                providerName = s.providerName.ifBlank { p.config.name },
            )
        }
    }

    private val HOME_PROVIDER_CEILING_MS get() = minOf(NetTuning.timeout(80_000L), 80_000L)

    /** Ceiling on one catalog's first page (served from the provider's own home
     *  cache for SkyStream extensions, so it is only reached by the scrapers). */
    private val HOME_CATALOG_CEILING_MS get() = minOf(NetTuning.timeout(20_000L), 20_000L)

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


    private fun isTmdbHomeFallbackProvider(p: ContentProvider): Boolean =
        p.config.type == ProviderType.NUVIO || p.config.type == ProviderType.STREMIO

    private suspend fun tmdbHomeRows(p: ContentProvider): List<CatalogRow> {
        val refs = TmdbBrowse.catalogRefs(
            p.config.name,
            p.config.id,
            kotlin.math.abs(p.config.id.hashCode()),
        ).take(12)
        return refs.mapNotNull { ref ->
            val items = runCatching { TmdbBrowse.items(p.config.id, ref.id, 1) }
                .getOrDefault(emptyList())
            if (items.isEmpty()) null
            else CatalogRow(
                providerId = p.config.id,
                providerName = p.config.name,
                title = ref.name,
                items = items,
                key = "${p.config.id}|${ref.type}|${ref.id}",
                catalogId = ref.id,
                type = ref.type,
                rawType = ref.rawType,
            )
        }
    }

    private suspend fun homeRowsForProvider(
        p: ContentProvider,
        providerCatalogsEnabled: Boolean,
        useTmdbFallback: Boolean,
    ): List<CatalogRow> {
        if (!providerCatalogsEnabled && useTmdbFallback && isTmdbHomeFallbackProvider(p)) {
            return tmdbHomeRows(p)
        }
        // The feed mixer (Manage Home Screen Feed): skipped catalogs never
        // load, and a custom order curates them — see AppStore.catalogOrderFlow.
        val mixerDisabled = runCatching { HikariApp.instance.store.disabledCatalogsFlow().first()[p.config.id] }
            .getOrDefault(null).orEmpty()
        val mixerOrder = runCatching { HikariApp.instance.store.catalogOrderFlow().first()[p.config.id] }
            .getOrDefault(null).orEmpty()
        val unordered = p.homeCatalogs()
            .filter { it.name !in mixerDisabled }
            .distinctBy { it.type to it.id }
        val catalogs = (if (mixerOrder.isEmpty()) unordered
        else unordered.sortedBy { mixerOrder.indexOf(it.name).takeIf { i -> i >= 0 } ?: Int.MAX_VALUE })
            .take(24)
        val rows: List<CatalogRow> = coroutineScope {
            catalogs.map { c ->
                async {
                    val items = withTimeoutOrNull(homeCatalogCeilingMs(p)) {
                        cancellableCatching { loadCatalogPage(p, c, 1) }
                            .getOrDefault(emptyList())
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
            }.awaitAll().filterNotNull()
        }
        // Every extension shows at least 5-6 home categories (trackers included): an
        // extension that only declares 1-2 catalogs gets honest slices of its own
        // items so Home never looks bare. Slices reuse the same items with unique
        // keys; nothing invented. Enough slices are built to reach 6 rows.
        if (rows.size in 1..5) {
            val all = rows.flatMap { it.items }.distinctBy { it.uniqueId }
            if (all.size >= 8) {
                val base = rows.first()
                val extra = ArrayList<CatalogRow>()
                fun slice(title: String, pick: List<MediaItem>, suffix: String) {
                    if (pick.size < 4) return
                    extra += base.copy(
                        title = title,
                        items = pick.take(40),
                        key = base.key + suffix,
                        catalogId = base.catalogId + suffix,
                    )
                }
                val need = 6 - rows.size
                val candidates = listOf(
                    "Trending Now" to all,
                    "Latest" to all.reversed(),
                    "Top Picks" to all.shuffled(),
                    "Popular" to all.shuffled(),
                    "Fresh Finds" to all.asReversed().shuffled(),
                )
                for ((title, pick) in candidates) {
                    if (extra.size >= need) break
                    slice(title, pick, ":" + title.lowercase().replace(' ', '-'))
                }
                val out = (rows + extra).distinctBy { it.title }.take(6)
                if (out.size > rows.size) return out
            }
        }
        return rows
    }

    /** Provider id -> engine kind, the index [HomeDedupe] needs: a [CatalogRow]
     *  knows its provider's id but not which engine that provider is. */
    private fun typeIndex(providers: List<ContentProvider>): Map<String, ProviderType> =
        providers.associate { it.config.id to it.config.type }

    /** One emission of [homeRowsStreamingWhere]: the rows placed so far in their
     *  curated order, with the titles an earlier row already carried removed when
     *  the feed spans engines that share a catalogue (see [HomeDedupe]). */
    private fun feedSnapshot(
        placed: Map<Int, CatalogRow>,
        types: Map<String, ProviderType>,
    ): List<CatalogRow> =
        HomeDedupe.apply(placed.entries.sortedBy { it.key }.map { it.value }, types)

    private fun noteCatalogTimeout(p: ContentProvider, ms: Long) {
        val msg = "Timed out after ${ms / 1000}s loading this extension's catalog."
        when (p) {
            is SkyStreamProvider -> SkyStreamProvider.catalogErrors[p.config.id] = msg
            is NuvioScraper -> NuvioScraper.catalogErrors[p.config.id] = msg
            is StremioAddon -> StremioAddon.catalogErrors[p.config.id] = msg
            is Cs3MainApiProvider -> Cs3MainApiProvider.catalogErrors[p.config.id] = msg
            is com.hikari.app.aniyomi.AniyomiProvider ->
                com.hikari.app.aniyomi.AniyomiProvider.catalogErrors[p.config.id] = msg
            is com.hikari.app.providers.HikariProviderAdapter ->
                com.hikari.app.providers.HikariProviderAdapter.catalogErrors[p.config.id] = msg
            is com.hikari.app.providers.vega.VegaProvider ->
                com.hikari.app.providers.vega.VegaProvider.catalogErrors[p.config.id] = msg
        }
    }

    suspend fun homeRows(providerId: String? = null, allowManga: Boolean = false): List<CatalogRow> = withContext(Dispatchers.IO) {
        val providerCatalogsEnabled = runCatching { HikariApp.instance.store.providerCatalogsEnabled() }.getOrDefault(true)
        if (!providerCatalogsEnabled) {
            runCatching { HikariApp.instance.store.ensureCinemetaAddon() }
            // addProvider persists first; ProviderManager only sees the new addon on refresh.
            // Without this refresh the filter below could legitimately find zero Stremio
            // providers, producing a blank Home when the user disabled provider catalogs.
            runCatching { HikariApp.instance.providers.refresh() }
        }
        val active = interleaveByProviderType(
            manager.providers.value
                .filter {
                    it.config.enabled &&
                        (allowManga || it.config.type != ProviderType.MANGA) &&
                        (providerId == null || it.config.id == providerId) &&
                        (providerCatalogsEnabled ||
                            (providerId != null && isTmdbHomeFallbackProvider(it)) ||
                            (it.config.type != ProviderType.NUVIO && it.config.type != ProviderType.STREMIO) ||
                            (it.config.name.equals("Cinemeta", ignoreCase = true) || it.config.id.equals("cinemeta", ignoreCase = true) || it.config.id.contains("cinemeta", ignoreCase = true)))
                }
                .distinctBy { it.config.id }
        )
        val providerGate = Semaphore(5)
        val catalogGate = Semaphore(12)
        val rows = coroutineScope {
            active.map { p ->
                async {
                    cancellableCatching {
                        providerGate.withPermit {
                            val loaded = withTimeoutOrNull(homeProviderCeilingMs(p)) {
                                homeRowsForProvider(p, providerCatalogsEnabled, providerId != null)
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
        // Rows from the engines that share a catalogue (Nuvio / Stremio) lose the
        // titles an earlier row of the feed already carried — see [HomeDedupe].
        translateRows(HomeDedupe.apply(rows, typeIndex(active)))
    }

    fun homeRowsStreaming(providerId: String? = null, allowManga: Boolean = false): Flow<List<CatalogRow>> = flow {
        val providerCatalogsEnabled = runCatching { HikariApp.instance.store.providerCatalogsEnabled() }.getOrDefault(true)
        val animeFallback = if (!providerCatalogsEnabled && providerId == null) {
            runCatching { AnimeCatalogRepository.homeRows() }.getOrDefault(emptyList())
        } else emptyList()
        homeRowsStreamingWhere(
            match = { providerId == null || it.config.id == providerId },
            allowDisabledProviderFallback = providerId != null,
            allowManga = allowManga,
        ).collect { rows -> emit(if (animeFallback.isEmpty()) rows else animeFallback + rows) }
    }

    fun homeRowsStreamingFor(ids: Set<String>, allowManga: Boolean = false): Flow<List<CatalogRow>> =
        homeRowsStreamingWhere(
            match = { it.config.id in ids },
            allowDisabledProviderFallback = true,
            allowManga = allowManga,
        )

    private fun homeRowsStreamingWhere(
        match: (ContentProvider) -> Boolean,
        allowDisabledProviderFallback: Boolean = false,
        allowManga: Boolean = false,
    ): Flow<List<CatalogRow>> = flow {
        val providerCatalogsEnabled = runCatching { HikariApp.instance.store.providerCatalogsEnabled() }.getOrDefault(true)
        if (!providerCatalogsEnabled) {
            runCatching { HikariApp.instance.store.ensureCinemetaAddon() }
            runCatching { HikariApp.instance.providers.refresh() }
        }
        val active = interleaveByProviderType(
            // Manga engines have their own tab, so they are not part of the Home
            // feed (see the note in [homeRows]).
            manager.providers.value.filter {
                it.config.enabled &&
                    (allowManga || it.config.type != ProviderType.MANGA) && match(it) &&
                    (providerCatalogsEnabled ||
                        (allowDisabledProviderFallback && isTmdbHomeFallbackProvider(it)) ||
                        (it.config.type != ProviderType.NUVIO && it.config.type != ProviderType.STREMIO) ||
                        it.config.name.equals("Cinemeta", ignoreCase = true))
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
        // The engine index every emission below is deduplicated against (see
        // [feedSnapshot] and [HomeDedupe]).
        val rowTypes = typeIndex(active)
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
                                val rowsForProvider = homeRowsForProvider(
                                p,
                                providerCatalogsEnabled,
                                allowDisabledProviderFallback,
                            )
                            rowsForProvider.forEachIndexed { ci, rawRow ->
                                val row = translateRows(listOf(rawRow)).firstOrNull() ?: rawRow
                                placed[pi * 100 + ci] = row
                                version.incrementAndGet()
                            }
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
                    val snapshot = feedSnapshot(placed, rowTypes)
                    lastSnapshot = snapshot
                    emit(snapshot)
                }
                if (jobs.all { it.isCompleted }) break
                if (System.currentTimeMillis() - started > 150_000L) break
                delay(100)
            }
            val finalSnapshot = feedSnapshot(placed, rowTypes)
            if (finalSnapshot != lastSnapshot) emit(finalSnapshot)
        } finally {
            scope.cancel()
            com.hikari.app.work.BackgroundWork.end(work)
        }
    }.flowOn(Dispatchers.IO)

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
                        var pageNo = page.coerceAtLeast(1)
                        val deadline = System.currentTimeMillis() + SEARCH_PROVIDER_BUDGET_MS
                        while (System.currentTimeMillis() < deadline) {
                            val items = cancellableCatching {
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

    class StreamLookup(val servers: List<StreamSource>, val complete: Boolean)

    suspend fun streamsFor(
        item: MediaItem,
        episode: Episode?,
        /** Called with the merged server list each time a provider adds new
         *  results, so callers can show servers progressively (Stremio-style)
         *  while the slower providers are still searching. */
        onProgress: (suspend (List<StreamSource>) -> Unit)? = null,
    ): List<StreamSource> = streamsForOutcome(item, episode, onProgress).servers

    suspend fun originStreams(
        item: MediaItem,
        episode: Episode?,
        budgetMs: Long = 12_000L,
    ): List<StreamSource> {
        val origin = manager.byId(item.providerId) ?: return emptyList()
        if (!origin.config.enabled) return emptyList()
        val got = if (origin.config.type == ProviderType.NUVIO) {
            com.hikari.app.nuvio.NuvioRuntime.withBackgroundSlot {
                detached(budgetMs) { fetchStreams(origin, item, episode) }
            }
        } else {
            detached(budgetMs) { fetchStreams(origin, item, episode) }
        }
        return got.orEmpty()
    }

    /** Direct streams for ONE episode from ONE provider — no sweep, no
     *  passes, never throws. The batch picker's server-first fill (see
     *  DetailScreen.resolveFirstServer): every selected episode searches at
     *  once, and the instant any episode reveals a provider, that provider is
     *  resolved for every other episode through here — instead of every
     *  episode re-running the full cross-extension discovery and re-finding
     *  the same mirrors. Episode mapping across providers is the same
     *  [resolveOwnEpisode] path the sweep itself uses (via [fetchStreams]). */
    suspend fun providerStreams(
        providerId: String,
        item: MediaItem,
        episode: Episode?,
        budgetMs: Long = 75_000L,
    ): List<StreamSource> {
        val p = manager.byId(providerId) ?: return emptyList()
        if (!p.config.enabled) return emptyList()
        return runCatching {
            if (p.config.type == ProviderType.NUVIO) {
                com.hikari.app.nuvio.NuvioRuntime.withBackgroundSlot {
                    detached(budgetMs) { fetchStreams(p, item, episode) }
                }
            } else {
                detached(budgetMs) { fetchStreams(p, item, episode) }
            }
        }.getOrDefault(null).orEmpty()
    }

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
        val passSeq = outcomeSeq.incrementAndGet()
        val outcomeBefore = HashMap(providerOutcome)
        try {
            val servers = streamsForInner(item, episode, onProgress, onOriginSettled, passSeq)
            if (servers.isNotEmpty()) return StreamLookup(servers, complete = true)
            // Remember disabled: no kept-list fallback — empty here is either
            // loading (server page open) or a real empty (caller decides via complete).
            // Empty is only an ANSWER when every provider really answered. A
            // cold plugin (its first call still spinning the runtime up), a
            // dropped request, a call cut off by the deadline — [fetchStreams]
            // records those as "✗ …" rather than "no servers" — knows nothing
            // about the title, and reporting it as "no playable sources" is the
            // "servers exist but the first tap finds none; the 4th or 5th works"
            // report (each manual retry was a fresh pass against a warmer
            // runtime). Say so honestly: the play flow retries an unfinished
            // lookup automatically instead of declaring none (see
            // [StreamLookup] and the final-read loop at the play call site).
            val unanswered = providerOutcome.any { (id, verdict) ->
                (providerOutcomeSeq[id] == passSeq || verdict != outcomeBefore[id]) &&
                    isNoAnswer(verdict)
            }
            return StreamLookup(emptyList(), complete = !unanswered)
        } catch (e: kotlinx.coroutines.CancellationException) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            com.hikari.app.data.Logs.log(
                "Search",
                "pass for \"${item.title.take(60)}\" was cut short (cancelled) — not an answer",
            )
            return StreamLookup(emptyList(), complete = false)
        } catch (e: Throwable) {
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

    fun recentlyFoundStreams(item: MediaItem, episode: Episode?): List<StreamSource> {
        // Remember disabled per user: every Play re-searches live from zero.
        return emptyList()
    }

    fun scopeAllowedIds(item: MediaItem): Set<String>? {
        val all = manager.providers.value.filter { it.config.enabled }
        val origin = manager.byId(item.providerId)
        val trackerBacked = item.providerId.lowercase() in setOf(
            "anilist", "simkl", "mal", "kitsu", "shikimori", "trakt"
        )
        if (trackerBacked) {
            if (!SearchScope.trackerSearchAll) return emptySet()
            val families = SearchScope.trackerFamilies
            val selected = SearchScope.trackerProviders
            return all.filter { it.config.enabled && (it.config.type.name in families || it.config.id in selected) }
                .map { it.config.id }.toSet()
        }
        val originIsIptv = com.hikari.app.data.IptvMark.of(item)
        val originIsException = originIsIptv || SearchScope.isException(item.providerId)
        val oneShot = SearchScope.oneShotOriginOnly?.takeIf { it == item.providerId }
        if (oneShot == null && !originIsException && SearchScope.allExtensions) return null
        val out = HashSet<String>()
        if (origin != null && origin.config.enabled) {
            out += origin.config.id
            if (oneShot == null) {
                if (SearchScope.family(origin.config.type)) {
                    all.filter { it.config.type == origin.config.type }.forEach { out += it.config.id }
                } else {
                    val picked = SearchScope.pickedIdsFor(origin.config.type)
                    if (picked.isEmpty()) {
                        all.filter { it.config.type == origin.config.type }.forEach { out += it.config.id }
                    } else {
                        all.filter { it.config.type == origin.config.type && it.config.id in picked }.forEach { out += it.config.id }
                    }
                }
                if (origin.config.type == ProviderType.NUVIO && SearchScope.nuvioFamily) {
                    all.filter { it.config.type == ProviderType.NUVIO }.forEach { out += it.config.id }
                }
                if (origin.config.type == ProviderType.STREMIO && SearchScope.stremioFamily) {
                    all.filter { it.config.type == ProviderType.STREMIO }.forEach { out += it.config.id }
                }
            }
        } else if (!originIsIptv) {
            val stremioPicked = SearchScope.searchStremioIds
            if (SearchScope.stremioFamily || stremioPicked.isEmpty()) {
                all.filter { it.config.type == ProviderType.STREMIO }.forEach { out += it.config.id }
            } else {
                all.filter { it.config.type == ProviderType.STREMIO && it.config.id in stremioPicked }.forEach { out += it.config.id }
            }
            if (com.hikari.app.nuvio.TmdbResolver.isLikelyResolvable(item)) {
                val nuvioPicked = SearchScope.searchNuvioIds
                if (SearchScope.nuvioFamily || nuvioPicked.isEmpty()) {
                    all.filter { it.config.type == ProviderType.NUVIO }.forEach { out += it.config.id }
                } else {
                    all.filter { it.config.type == ProviderType.NUVIO && it.config.id in nuvioPicked }.forEach { out += it.config.id }
                }
            }
        }
        if (!originIsException) out += SearchScope.exceptions
        return out
    }

    /** Keeps only the servers the current scope allows for [item] (see [scopeAllowedIds]). */
    fun filterToScope(item: MediaItem, list: List<StreamSource>): List<StreamSource> {
        val allowed = scopeAllowedIds(item) ?: return list
        return list.filter { it.providerId.isBlank() || it.providerId in allowed }
    }

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
        // Tracker-origin titles use the tracker-only server choices when enabled.
        item: MediaItem,
        episode: Episode?,
        onProgress: (suspend (List<StreamSource>) -> Unit)?,
        /** Called once, when the provider the title came FROM has answered (see
         *  the origin job in the pass below). The player holds its auto-start
         *  until it fires, so this is what ends that hold — early, the moment
         *  the origin has really spoken. */
        onOriginSettled: (() -> Unit)? = null,
        /** [streamsForOutcome]'s running pass (see [providerOutcomeSeq]): only
         *  a pass stamps verdicts, so the freshness check knows which
         *  unfinished entries are its own. */
        passSeq: Long = -1L,
    ): List<StreamSource> =
        withContext(Dispatchers.IO) {
            val all = manager.providers.value.filter { it.config.enabled }
            val origin = manager.byId(item.providerId)
            val trackerBacked = item.providerId.lowercase() in setOf(
                "anilist", "simkl", "mal", "kitsu", "shikimori", "trakt"
            )

            val originIsIptv = IptvMark.of(item)
            val originIsException = originIsIptv || SearchScope.isException(item.providerId)
            val oneShotNarrow = SearchScope.oneShotOriginOnly
                .takeIf { it == item.providerId }
                ?.also { SearchScope.oneShotOriginOnly = null }
            val trackerTargets = if (trackerBacked) {
                val on = runCatching { HikariApp.instance.store.trackerServerSearchAll() }.getOrDefault(true)
                if (!on) emptySet() else {
                    val families = runCatching { HikariApp.instance.store.trackerEngineFamilies() }
                        .getOrDefault(setOf(ProviderType.NUVIO.name, ProviderType.STREMIO.name))
                    val selected = runCatching { HikariApp.instance.store.trackerProviderIds() }.getOrDefault(emptySet())
                    all.filter { it.config.enabled && (it.config.type.name in families || it.config.id in selected) }
                        .map { it.config.id }.toSet()
                }
            } else emptySet()
            val scopeAll = if (trackerBacked) false else when {
                oneShotNarrow != null -> false
                originIsException -> false
                else -> SearchScope.allExtensions
            }
            val exceptions = when {
                oneShotNarrow != null -> emptySet()
                originIsException -> emptySet()
                trackerBacked -> trackerTargets
                else -> SearchScope.exceptions
            }
            // Read once, like the two above: one lookup must never be
            // half-scoped (see docs/SEARCH.md). "Search every Nuvio provider".
            val nuvioFamily = oneShotNarrow == null && SearchScope.nuvioFamily
            // Read once too: "Search every Stremio addon".
            val stremioFamily = oneShotNarrow == null && SearchScope.stremioFamily
            // THE APP'S OWN CATALOGUE IS NOT AN EXTENSION.
            //
            // A title browsed from Home / Search / Collections / a nuvio
            // catalogue import carries `providerId = "tmdb"` (see [TmdbMeta]),
            // and `manager.byId("tmdb")` names no provider at all. That left
            // `origin` null, and every branch below built its target list from
            // "the origin" — i.e. from nothing. With "Server search: only this
            // extension" on, the pass then had nothing to restrict TO and
            // returned before asking anybody, so a title opened from Home
            // reported "no playable server found" however many playback addons
            // were installed. That is the reported "the same PenguPlay addon
            // shows dozens of servers in Stremio and none here" — Stremio asks
            // every installed addon for a catalogue id; this pass asked none.
            // An origin the user has since uninstalled or switched off is the
            // same case.
            //
            // The id-resolving engines — Stremio addons and nuvio engines — are
            // the ones that CAN answer for such an item: they are handed the
            // item's own tmdb/imdb id and need no title search at all. So they
            // become the pass's targets, exactly the addons the real client asks
            // for a catalogue id.
            val originUsable = origin != null && origin.config.enabled
            val originless = !originIsIptv && !originUsable
            val stremioAddons = if (originless) {
                all.filter {
                    it.config.type == ProviderType.STREMIO &&
                        (!trackerBacked || it.config.id in trackerTargets)
                }
            } else {
                emptyList()
            }
            val primaryTargets = if (!scopeAll) {
                if (originless) stremioAddons else listOfNotNull(origin)
            } else if (origin?.config?.type == ProviderType.STREMIO || originless) {
                // Like the real client: ask every Stremio addon plus the origin.
                all.filter { p ->
                    p.config.id == item.providerId || originless ||
                        (p.config.type == ProviderType.STREMIO && SearchScope.family(ProviderType.STREMIO))
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
            //
            // "Search every Nuvio provider" (the Server search card) is the one
            // widening that does not depend on the scope switch: a title opened
            // FROM a nuvio provider keeps asking the whole nuvio family even in
            // "only this extension" mode, because those engines all resolve the
            // same (tmdbId, mediaType, season, episode) tuple — the family is
            // one source of servers, not a cross-search of unrelated sites.
            // [SearchScope.nuvioFamily] is that switch; off, it changes nothing
            // here.
            val originIsNuvio = origin?.config?.type == ProviderType.NUVIO
            val nuvioTargets = if (!scopeAll) {
                when {
                    // "Only this extension": a nuvio engine is another source, so
                    // none of them are asked (the origin, if it IS one, is already
                    // in [primaryTargets]) — except:
                    //
                    //  * when there was no extension to restrict to at all
                    //    ([originless]), where they are the item's only possible
                    //    source and asking them is the whole point;
                    //  * the whole family, when the origin IS a nuvio provider and
                    //    the family switch is on (see above);
                    //  * the ones marked as exceptions, which the user asked to
                    //    always include.
                    originless ->
                        if (com.hikari.app.nuvio.TmdbResolver.isLikelyResolvable(item)) {
                            all.filter {
                                it.config.type == ProviderType.NUVIO &&
                                    (!trackerBacked || it.config.id in trackerTargets)
                            }.sortedWith(nuvioOrder(item.providerId))
                        } else {
                            emptyList()
                        }
                    originIsNuvio && nuvioFamily ->
                        all.filter {
                            it.config.type == ProviderType.NUVIO &&
                                (!trackerBacked || it.config.id in trackerTargets)
                        }.sortedWith(nuvioOrder(item.providerId))
                    originIsNuvio ->
                        all.filter {
                            it.config.type == ProviderType.NUVIO &&
                                it.config.id in SearchScope.searchNuvioIds &&
                                (!trackerBacked || it.config.id in trackerTargets)
                        }.sortedWith(nuvioOrder(item.providerId))
                    exceptions.isEmpty() -> emptyList()
                    else -> all.filter {
                        it.config.type == ProviderType.NUVIO && it.config.id in exceptions
                    }
                }
            } else if (com.hikari.app.nuvio.TmdbResolver.isLikelyResolvable(item)) {
                all.filter {
                    it.config.type == ProviderType.NUVIO &&
                        (originless || it.config.id == item.providerId ||
                            SearchScope.family(ProviderType.NUVIO))
                }
                    .sortedWith(nuvioOrder(item.providerId))
            } else {
                emptyList()
            }
            val originIsStremio = origin?.config?.type == ProviderType.STREMIO
            val stremioTargets = if (!scopeAll && originIsStremio && stremioFamily) {
                all.filter { it.config.type == ProviderType.STREMIO }
                    .sortedWith(stremioOrder(item.providerId))
            } else if (!scopeAll && originIsStremio) {
                all.filter { it.config.type == ProviderType.STREMIO && it.config.id in SearchScope.searchStremioIds }
                    .sortedWith(stremioOrder(item.providerId))
            } else {
                emptyList()
            }
            val targets = (primaryTargets + nuvioTargets + stremioTargets).distinctBy { it.config.id }
            val stremioPrimaryIds = (if (originless) primaryTargets else stremioTargets)
                .filter { it.config.type == ProviderType.STREMIO }
                .mapTo(HashSet()) { it.config.id }
            val tally = CrossTally()
            val crossTargets = if (scopeAll) {
                crossExtensionTargets(item, origin, all, tally, alsoSkip = stremioPrimaryIds)
            } else if (exceptions.isEmpty()) {
                emptyList()
            } else {
                crossExtensionTargets(item, origin, all, tally, onlyIds = exceptions)
            }
            val sameEngine = if (origin == null || oneShotNarrow != null) {
                emptyList()
            } else if (SearchScope.family(origin.config.type)) {
                crossTargets.filter { it.config.type == origin.config.type }
            } else {
                val picked = SearchScope.pickedIdsFor(origin.config.type)
                crossTargets.filter { it.config.type == origin.config.type && it.config.id in picked }
            }
            if (origin != null && !SearchScope.family(origin.config.type)) {
                val siblings = all.count {
                    it.config.enabled && it.config.type == origin.config.type &&
                        it.config.id != origin.config.id
                }
                if (siblings > 0) {
                    Logs.log(
                        "Search",
                        "family(${origin.config.type.groupLabel}) switch is off — " +
                            "$siblings sibling repo(s) of the origin's engine are not asked " +
                            "for this title",
                    )
                }
            }
            val lateTargets = crossTargets.filter { it !in sameEngine }
            if (targets.isEmpty() && crossTargets.isEmpty()) return@withContext emptyList()
            tally.title = item.title
            publishCrossTally(tally)

            com.hikari.app.data.Logs.log(
            "Search",
            "start \"${item.title}\" (${item.type}) v=${com.hikari.app.BuildConfig.VERSION_NAME} " +
                "origin=" +
                (origin?.config?.name ?: if (originless) "app-catalogue(no extension)" else "?") + " " +
                "narrow=" + (oneShotNarrow ?: "-") + " " +
                "primary=${targets.size} nuvio=${nuvioTargets.size} " +
                "cross=${crossTargets.size} same=${sameEngine.size} late=${lateTargets.size} " +
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
            crossMatch.entries.removeAll { emptyNow - it.value.at >= CROSS_MATCH_TTL_MS }
            all.groupBy { it.config.type.groupLabel }
                .forEach { (label, list) -> crossInstalled[label] = list.size }
            all.filter { isHung(it.config.id) }.forEach { p ->
                crossVerdict[p.config.id] =
                    (p.config.name.ifBlank { p.config.id }) + " — stopped responding earlier"
            }
            targets.forEach { p ->
                val label = p.config.type.groupLabel
                primaryAsked[p.config.id] = label
                primaryRunning[p.config.id] = p.config.name.ifBlank { p.config.id }
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
            var passStartedAt = 0L
            var passDeadlineAt = 0L
            var passFound: List<StreamSource> = emptyList()
            /** Set at the very end of the pass body — clear of the `finally`
             *  below — so a teardown that runs without it knows the pass was
             *  cut off (cancelled or thrown) rather than finished. */
            var passCompleted = false
            var passStalled = false
            try {
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
                                tagGroup(fetchStreams(p, item, episode, passSeq), p)
                            } else {
                                tagGroup(fetchStreams(p, item, episode, passSeq), p)
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
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
                            if (passSeq >= 0L) providerOutcomeSeq[p.config.id] = passSeq
                            throw e
                        } finally {
                            if (p.config.id !in sweepOwned) primaryRunning.remove(p.config.id)
                            bumpCrossStatus()
                        }
                    }
                }
                val started = System.currentTimeMillis()
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
                val deadline = started + if (nuvioTargets.isNotEmpty()) {
                    minOf(NetTuning.timeout(70_000L), 75_000L)
                } else {
                    minOf(NetTuning.timeout(55_000L), 60_000L)
                }
                passStartedAt = started
                passDeadlineAt = deadline
                val crossGrace = if (targets.isEmpty()) 0L else CROSS_EXT_GRACE_MS
                val merged = LinkedHashMap<String, StreamSource>()
                fun merge(job: kotlinx.coroutines.Deferred<List<StreamSource>>) {
                    if (job.isCompleted) {
                        runCatching { job.getCompleted() }.getOrDefault(emptyList())
                            .forEach { s -> merged.putIfAbsent(s.identityKey(), s) }
                        passFound = merged.values.toList()
                    }
                }
                var lastEmitted = -1
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
                    var progress: List<StreamSource>? = null
                    if (onProgress != null) {
                        if (merged.size != lastEmitted) {
                            progress = merged.values.toList()
                        } else {
                            for (t in targets) {
                                val part = runCatching { t.partialStreams() }.getOrDefault(emptyList())
                                if (part.isEmpty()) continue
                                var grew = false
                                for (s in part) {
                                    if (merged.putIfAbsent(s.identityKey(), s) == null) grew = true
                                }
                                if (grew) break
                            }
                            if (merged.size != lastEmitted) progress = merged.values.toList()
                        }
                    }
                    if (progress != null) {
                        lastEmitted = merged.size
                        passFound = merged.values.toList()
                        onProgress?.invoke(progress)
                    }
                    val now = System.currentTimeMillis()
                    // New servers landing IS progress: a pass that is producing
                    // servers (however slowly) must never be called wedged.
                    if (merged.size != mergedCount) {
                        mergedCount = merged.size
                        lastProgressAt = now
                    }
                    launchWave(now)
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
                    launchPendingExtractions()
                    // Count the extractions that land, and for the wedge watchdog:
                    // anything completing anywhere is progress.
                    val exIt = extractJobs.iterator()
                    while (exIt.hasNext()) {
                        val j = exIt.next()
                        if (j.isCompleted) {
                            merge(j)
                            exIt.remove()
                            lastProgressAt = now
                        }
                    }
                    val outstanding = searchQueue.size + searchJobs.size + extractJobs.size
                    if (outstanding > 0 && now - lastProgressAt >= CROSS_EXT_STALL_MS) {
                        if (SearchScope.serverPageOpen) {
                            com.hikari.app.data.Logs.log(
                                "Search",
                                "pass over \"${item.title}\": nothing has answered in " +
                                    "${CROSS_EXT_STALL_MS / 1000}s with $outstanding repo(s) " +
                                    "still outstanding — server page is open, keep waiting",
                            )
                            lastProgressAt = now
                        } else {
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
                    }
                    val allDone = jobs.all { it.isCompleted } && searchQueue.isEmpty() &&
                        searchJobs.isEmpty() && extractJobs.all { it.isCompleted }
                    if (allDone) break
                    val nuvioStillRunning = targets.indices.any { i ->
                        targets[i].config.type == ProviderType.NUVIO && !jobs[i].isCompleted
                    }
                    val overCeiling = now > maxOf(deadline, started + CROSS_EXT_BUDGET_MS)
                    // Server page open: no ceiling — wait until everything answers.
                    // Absolute 5-min cap only guards a truly wedged provider; the
                    // unfinished hand-off below keeps painting via the sweep.
                    if (SearchScope.serverPageOpen) {
                        if (now > started + 300_000L) break
                    } else if (overCeiling && !(nuvioStillRunning && now <= deadline + NUVIO_TAIL_MS)) {
                        break
                    }
                    kotlinx.coroutines.delay(80)
                }
                jobs.forEach { it.cancel() }
                searchJobs.forEach { it.cancel() }
                extractJobs.forEach { it.cancel() }
                passFound = merged.values.toList()
                result = passFound
                val neverReached = crossTargets.count { !crossAsked.containsKey(it.config.id) }
                crossTargets.forEach { p ->
                    val id = p.config.id
                    // Skipped extensions are left out of the record entirely:
                    // nothing to count, nothing to show (see [crossCfSkip]).
                    if (isCfSkipped(id)) return@forEach
                    if (id in sweepOwned) return@forEach
                    if (crossVerdict.containsKey(id) || crossFound.containsKey(id)) return@forEach
                    val repo = p.config.name.ifBlank { id }
                    crossVerdict[id] = if (crossAsked.containsKey(id))
                        "$repo — was still searching when the pass ended"
                    else
                        "$repo — never reached (the pass ended before asking it)"
                }
                crossRunning.clear()
                sweepOwned.forEach { id ->
                    crossTargets.firstOrNull { it.config.id == id }?.let { p ->
                        crossRunning[id] = p.config.name.ifBlank { id }
                    }
                }
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
                exitForegroundPass()
                val tail = sweepableTail(crossTargets, crossFound, crossVerdict)
                val servable = passFound.size
                val ranOutOfTime = passStalled ||
                    System.currentTimeMillis() >=
                    maxOf(passDeadlineAt, passStartedAt + CROSS_EXT_BUDGET_MS)
                val fromMemory = cachedEmptyTargets(crossTargets, item.searchTitle)
                val reAsk = if (fromMemory.isNotEmpty() && servable < CROSS_THIN_RESULT) {
                    fromMemory
                } else {
                    emptyList()
                }
                val originRepo = origin
                val soleTargetDead = originRepo != null && !scopeAll &&
                    oneShotNarrow == null &&
                    SearchScope.family(originRepo.config.type) &&
                    crossTargets.isEmpty() && passFound.isEmpty() &&
                    providerOutcome[originRepo.config.id] != "no servers" &&
                    !isHung(originRepo.config.id)
                val familyFallback = when {
                    originRepo == null -> emptyList()
                    !soleTargetDead -> emptyList()
                    else -> all.filter { p ->
                        p.config.enabled && p.config.id != originRepo.config.id &&
                            p.config.type == originRepo.config.type &&
                            !isHung(p.config.id) && !isCfSkipped(p.config.id)
                    }
                }
                if (familyFallback.isNotEmpty()) {
                    com.hikari.app.data.Logs.log(
                        "Search",
                        "familyFallback \"" + item.title + "\": the extension this title " +
                            "came from never answered and the scope left no other " +
                            "target — asking ${familyFallback.size} sibling repo(s) of " +
                            "its ${originRepo?.config?.type?.groupLabel} engine by title",
                    )
                }
                val sweepTargets = (tail + reAsk + familyFallback).distinctBy { it.config.id }
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
                val worthSweeping = sweepTargets.isNotEmpty()
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
                crossTargets.forEach { p ->
                    val id = p.config.id
                    // Skipped extensions are left out of the record entirely:
                    // nothing to count, nothing to show (see [crossCfSkip]).
                    if (isCfSkipped(id)) return@forEach
                    if (id in sweepOwned) return@forEach
                    if (crossVerdict.containsKey(id) || crossFound.containsKey(id)) return@forEach
                    val repo = p.config.name.ifBlank { id }
                    crossVerdict[id] = if (crossAsked.containsKey(id))
                        "$repo — was still searching when the pass ended"
                    else
                        "$repo — never reached (the pass ended before asking it)"
                }
                crossRunning.clear()
                sweepOwned.forEach { id ->
                    crossTargets.firstOrNull { it.config.id == id }?.let { p ->
                        crossRunning[id] = p.config.name.ifBlank { id }
                    }
                }
                bumpCrossStatus()
            }
            var finalResult = result.filterNot { isGarbageUrl(it.url) }
                .distinctBy { mergeKey(it) }
            // (A bundled yt-dlp engine used to take one last shot at the page
            // here when every provider type came up empty. It was removed in
            // 0.9.1 — see the CHANGELOG. Everything above still applies; the
            // remaining engines are the plugin's own loadLinks, the jar
            // extractor registry, MovieBlast, FallbackResolver and the
            // Nuvio/SkyStream JS runtimes.)
            // Remember disabled per user: every lookup is a fresh live search.
            // No kept-list merge, no save — fresh result stands on its own.
            val resolvedResult = com.hikari.app.cs3.PlayableResolver.resolve(finalResult)
            if (resolvedResult !== finalResult) {
                finalResult = resolvedResult
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

    private fun startSweepIfNeeded(
        item: MediaItem,
        episode: Episode?,
        targets: List<ContentProvider>,
        directTargets: List<ContentProvider>,
        snapshot: List<StreamSource>,
        sink: (suspend (List<StreamSource>) -> Unit)?,
        /** Ask these repos for REAL, ignoring the session's "no such title"
         *  record. Set when the record is the only reason they were not asked
         *  (see the empty-result block in [streamsForInner]). */
        ignoreEmptyRecord: Boolean = false,
    ) {
        val scopeIds = scopeAllowedIds(item)
        notePendingWork(
            item,
            episode,
            targets.map { it.config.id },
            directTargets.map { it.config.id },
            ignoreEmptyRecord = ignoreEmptyRecord,
            resetTries = true,
            scopeIds = scopeIds,
        )
        launchSweepFor(item, episode, snapshot, sink, passScope = scopeIds)
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
        /** The calling pass's own scope (see [scopeAllowedIds]): a running sweep
         *  whose scope this pass excludes must not receive this pass's sink, or
         *  out-of-scope servers would stream into a list whose switches forbid
         *  them. Null = no opinion (automatic drains, held releases). */
        passScope: Set<String>? = null,
    ) {
        val key = streamsRememberedKey(item, episode)
        synchronized(sweeps) {
            val joinable = sweeps[key]
            if (joinable != null && joinable.job?.isActive == true) {
                val scopeOk = sink == null || joinable.scopeIds == null || passScope == null ||
                    passScope.containsAll(joinable.scopeIds!!)
                if (scopeOk && sink != null) joinable.sinks += sink
                val have = joinable.current
                if (scopeOk && sink != null && have.isNotEmpty()) {
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
            if (!allowExhausted && recorded.tries >= SWEEP_MAX_TRIES) {
                // Automatic drains already had their retries (see
                // SWEEP_MAX_TRIES): leave the remainder for the next real
                // lookup instead of re-arming a finished sweep forever.
                pendingWork.remove(key)
                return
            }
            if (key in heldSweepKeys) {
                val held = heldSweeps.computeIfAbsent(key) {
                    HeldSweep(item, episode, emptyList())
                }
                if (snapshot.isNotEmpty()) {
                    held.snapshot = (held.snapshot + snapshot)
                        .distinctBy { it.identityKey() }
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
            val allowed = recorded.scopeIds
            val byTitle = recorded.byTitle.toList().mapNotNull { manager.byId(it) }
                .filter { allowed == null || it.config.id in allowed }
            val direct = recorded.direct.toList().mapNotNull { manager.byId(it) }
                .filter { allowed == null || it.config.id in allowed }
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
            sweep.scopeIds = allowed
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
                    val unasked = units.filter { !sweep.answered.contains(it.provider.config.id) }
                    notePendingWork(
                        item,
                        episode,
                        unasked.filter { it.byTitle }.map { it.provider.config.id },
                        unasked.filterNot { it.byTitle }.map { it.provider.config.id },
                        ignoreEmptyRecord = sweep.ignoreEmptyRecord,
                        resetTries = false,
                        scopeIds = sweep.scopeIds,
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

    private suspend fun runSweep(
        item: MediaItem,
        episode: Episode?,
        units: List<SweepUnit>,
        sweep: Sweep,
        snapshot: List<StreamSource>,
    ) {
        val key = streamsRememberedKey(item, episode)
        val acc = LinkedHashMap<String, StreamSource>()
        snapshot.forEach { acc[it.identityKey()] = it }
        streamsRemembered[key]
            ?.takeIf { System.currentTimeMillis() - it.at < REMEMBERED_STREAMS_TTL_MS }
            ?.list
            ?.forEach { acc.putIfAbsent(it.identityKey(), it) }
        sweep.current = acc.values.toList()
        var lastEmitted = acc.size
        val budget = SWEEP_BUDGET_MS
        val started = System.currentTimeMillis()
        sweep.rounds++
        sweep.lastProgressAt = started
        val pausedMs = java.util.concurrent.atomic.AtomicLong(0L)
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

        if (units.any { !it.byTitle }) kotlinx.coroutines.delay(SWEEP_DIRECT_HEAD_START_MS)
        val queue = java.util.concurrent.ConcurrentLinkedQueue<SweepUnit>(units)
        val workers = minOf(SWEEP_WORKERS, units.size).coerceAtLeast(1)
        kotlinx.coroutines.coroutineScope {
            repeat(workers) {
                launch {
                    while (true) {
                        val unit = queue.poll() ?: break
                        val p = unit.provider
                        val waited = awaitBackgroundClearance {
                            sweep.lastProgressAt = System.currentTimeMillis()
                        }
                        if (waited > 0) pausedMs.addAndGet(waited)
                        if (isHung(p.config.id)) {
                            if (isSittingOut(p.config.id)) {
                                // 30-minute break: finished for this pass
                                // chain, NOT unfinished — re-recording it as
                                // pending here is what re-armed a new sweep
                                // every few seconds forever after two silences.
                                sweep.answered.add(p.config.id)
                                val rn = p.config.name.ifBlank { p.config.id }
                                crossTally.verdict[p.config.id] = "$rn — sitting out (silent twice in a row)"
                                bumpCrossStatus()
                            }
                            continue
                        }
                        val id = p.config.id
                        val repo = p.config.name.ifBlank { id }
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
                            tally.running.remove(id)
                            tally.verdict[id] = "$repo — did not answer in time"
                            bumpCrossStatus()
                            sweep.lastProgressAt = System.currentTimeMillis()
                            noteNoAnswer(id)
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
                                    // An answer, even when it is "nothing here".
                                    noteAnswered(id)
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
                                noteNoAnswer(id)
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
                                noteAnswered(id)
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
                                noteAnswered(id)
                                synchronized(acc) {
                                    found.forEach { s -> acc.putIfAbsent(s.identityKey(), s) }
                                }
                                com.hikari.app.data.Logs.log(
                                    "Search",
                                    "sweep \"${item.title}\" → $repo: ${found.size} servers " +
                                        "(background, while playing)",
                                )
                            }
                        } finally {
                            tally.running.remove(id)
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
        val left = unasked.filterNot { isHung(it.provider.config.id) }
        if (left.isNotEmpty()) {
            com.hikari.app.data.Logs.log(
                "Search",
                "sweep \"${item.title}\" → round ${sweep.rounds}: ${left.size} repo(s) left " +
                    "unasked — asking them now (${sweep.current.size} server(s) so far)",
            )
            runSweep(item, episode, left, sweep, sweep.current)
        }
    }

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
        val got = if (p.config.type == ProviderType.NUVIO) {
            com.hikari.app.nuvio.NuvioRuntime.withBackgroundSlot {
                detached(SWEEP_REPO_BUDGET_MS) { fetchStreams(p, item, episode) }
            }
        } else {
            detached(SWEEP_REPO_BUDGET_MS) { fetchStreams(p, item, episode) }
        }
        try {
            if (got == null) {
                noteNoAnswer(id)
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
                noteAnswered(id)
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
            noteAnswered(id)
            synchronized(acc) {
                found.forEach { s -> acc.putIfAbsent(s.identityKey(), s) }
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
        onlyIds: Set<String>? = null,
        alsoSkip: Set<String> = emptySet(),
    ): List<ContentProvider> {
        val originType = origin?.config?.type
        val originIsStremio = originType == ProviderType.STREMIO
        val skipped = LinkedHashMap<String, Int>()
        fun noteSkipped(reason: String) {
            skipped[reason] = (skipped[reason] ?: 0) + 1
        }
        fun rankOf(t: ProviderType): Int = when {
            originType != null && t == originType -> 0
            t == ProviderType.HIKARI -> 1
            t == ProviderType.CS3 -> 2
            t == ProviderType.UNIVERSAL -> 3
            t == ProviderType.SKYSTREAM -> 3
            t == ProviderType.ANIYOMI -> 3
            t == ProviderType.VEGA -> 3
            t == ProviderType.ZANGETSU -> 3
            t == ProviderType.FORK_VIDEO -> 3
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
                if (p.config.id in alsoSkip) {
                    noteSkipped("primary")
                    return@filter false
                }
                if (onlyIds != null && p.config.id !in onlyIds) {
                    noteSkipped("not-an-exception")
                    return@filter false
                }
                if (isCfSkipped(p.config.id)) {
                    noteSkipped("cf-skip")
                    return@filter false
                }
                if (isHung(p.config.id)) {
                    noteSkipped("hung")
                    return@filter false
                }
                if (p.config.type == ProviderType.SKYSTREAM) {
                    val host = runCatching {
                        com.hikari.app.skystream.SkyStreamPluginManager.siteHostOf(
                            com.hikari.app.skystream.SkyStreamPluginManager.scriptFile(
                                com.hikari.app.HikariApp.instance,
                                com.hikari.app.skystream.SkyStreamPluginManager.packageOf(p.config.id),
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
                if (originType != null && p.config.type == originType &&
                    (onlyIds == null || p.config.id !in onlyIds) &&
                    !SearchScope.family(p.config.type)
                ) {
                    noteSkipped("family(${p.config.type.groupLabel})")
                    return@filter false
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
                    ProviderType.ANIYOMI -> true
                    ProviderType.VEGA -> true
                    // Zangetsu sources export their own search too, so they
                    // are asked exactly like the other site-scraper families.
                    ProviderType.ZANGETSU -> true
                    // Fork-video sources export their own search too, so they
                    // are asked exactly like the other site-scraper families.
                    ProviderType.FORK_VIDEO -> true
                    ProviderType.SORA -> true
            ProviderType.ANYMEX -> true
            ProviderType.ANYMEX_MANGA -> true
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
        val out = ArrayList<ContentProvider>(CROSS_EXT_MAX_TARGETS)
        for (family in families) {
            if (originType == null || family.key != originType) continue
            out += family.value
        }
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

    private fun markCrossSearchStarted(
        p: ContentProvider,
        repo: String,
        title: String,
        tally: CrossTally,
        fromCache: Boolean = false,
    ) {
        tally.asked[p.config.id] = p.config.type.groupLabel
        if (fromCache) {
            bumpCrossStatus()
            return
        }
        tally.running[p.config.id] = repo
        bumpCrossStatus()
        com.hikari.app.data.Logs.log("Search", "cross \"$title\" → $repo: searching…")
    }

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
        if (best == null) {
            for (alt in item.allTitles) {
                if (alt.equals(title, ignoreCase = true)) continue
                                val altAttempt = searchBestMatch(
                    p,
                    alt,
                    item,
                    CROSS_EXT_MIN_MATCH,
                    episode = episode,
                    ignoreEmpty = ignoreEmptyRecord,
                )
                if (altAttempt.best != null) {
                    best = altAttempt.best
                    attempt = altAttempt
                    break
                }
            }
        }
        if (best == null && attempt.cachedEmpty) {
            // Answered (and remembered) "no such title" moments ago — report it
            // without re-recording a note or re-asking this repo.
            return null to "no matching title for \"$title\""
        }
        if (best == null) {
            val id = p.config.id
            val said = providerStreamMessage(p)?.takeIf { it != saidBefore && selfNote[id] != it }
            if (said != null) {
                if (com.hikari.app.net.CloudflareVerifier.isVerificationMessage(said)) {
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
            val match = matchCrossEpisode(eps, episode, best.title)
            if (match == null) {
                recordStreamMessage(p, "Has the title, but not S${episode.season}E${episode.number}.")
                return emptyList<StreamSource>() to
                    "has the title, but not S${episode.season}E${episode.number}"
            }
            ep = match
        }
        var gotFailure: String? = null
        var gotTimedOut = false
        val streamsBudget = streamsTimeoutFor(p)
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

    private fun matchCrossEpisode(
        eps: List<Episode>,
        wanted: Episode,
        /** Title of the repo entry the list was read from (see the call in
         *  [crossExtensionExtract]): needed for the season-conflict gate below. */
        entryTitle: String? = null,
    ): Episode? {
        if (eps.isEmpty()) return null
        eps.firstOrNull { it.season == wanted.season && it.number == wanted.number }
            ?.let { return it }
        if (wanted.number <= 0) return null
        val statedSeason = entryTitle?.let { TmdbMeta.seasonHint(it) }
        if (statedSeason != null && statedSeason != wanted.season &&
            eps.any { it.season == statedSeason }
        ) {
            return null
        }
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
        ProviderType.VEGA -> com.hikari.app.providers.vega.VegaProvider.streamErrors[p.config.id]
        ProviderType.ZANGETSU -> com.hikari.app.zangetsu.ZangetsuProvider.streamErrors[p.config.id]
        ProviderType.SORA -> com.hikari.app.sora.SoraProvider.streamErrors[p.config.id]
            ProviderType.ANYMEX -> com.hikari.app.anymex.AnymexProvider.streamErrors[p.config.id]
            ProviderType.ANYMEX_MANGA -> com.hikari.app.anymex.AnymexMangaProvider.lastOutcome[p.config.id]
        ProviderType.IPTV -> IptvProvider.iptvErrors[p.config.id]
        // LNReader novel sources share the MANGA type under an `lnreader|`
        // prefix but keep their own outcome map.
        ProviderType.MANGA -> if (com.hikari.app.lnreader.LnReaderProvider.isLnReaderId(p.config.id)) com.hikari.app.lnreader.LnReaderProvider.lastOutcome[p.config.id] else if (com.hikari.app.cs3.Cs3MangaProvider.isCs3MangaId(p.config.id)) com.hikari.app.cs3.Cs3MangaProvider.lastOutcome[p.config.id] else com.hikari.app.manga.MangaProvider.lastOutcome[p.config.id]
        ProviderType.FORK_VIDEO -> com.hikari.app.kotatsu.fork.ForkVideoProvider.lastOutcome[p.config.id]
    }

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

    private class SearchAttempt(val best: MediaItem?, val why: String?, val cachedEmpty: Boolean = false)

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
        val cacheKey = crossEmptyKey(p.config.id, query)
        val cachedAt = if (ignoreEmpty) null else crossEmpty[cacheKey]
        if (cachedAt != null) {
            if (System.currentTimeMillis() - cachedAt < CROSS_EMPTY_TTL_MS) {
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

    private fun confidentTitleMatch(
        wanted: MediaItem,
        candidate: MediaItem,
        episode: Episode?,
    ): Boolean {
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
        if (episode != null && wanted.type == MediaType.SERIES) {
            val stated = statedEpisodeNumber(candidate.title)
            if (stated != null && stated != episode.number) {
                val wantedStated = statedEpisodeNumber(episode.name)
                if (wantedStated == null || wantedStated != stated) return false
            }
        }
        val a = normalizeTitle(wanted.searchTitle)
        val b = normalizeTitle(candidate.title)
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        val ta = a.split(' ').filter { it.length > 2 }
        val tb = b.split(' ').filter { it.length > 2 }
        if (ta.isEmpty() || tb.isEmpty()) return false
        val sa = ta.toSet()
        val sb = tb.toSet()
        val extends = sb.containsAll(sa)      // the repo names the show more fully
        val shortened = sa.containsAll(sb)    // the repo names LESS than we asked
        if (!extends && !shortened) return false
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
            ProviderType.VEGA -> com.hikari.app.providers.vega.VegaProvider.streamErrors
            ProviderType.ZANGETSU -> com.hikari.app.zangetsu.ZangetsuProvider.streamErrors
            ProviderType.SORA -> com.hikari.app.sora.SoraProvider.streamErrors
            ProviderType.ANYMEX -> com.hikari.app.anymex.AnymexProvider.streamErrors
            ProviderType.ANYMEX_MANGA -> com.hikari.app.anymex.AnymexMangaProvider.lastOutcome
            ProviderType.MANGA -> if (com.hikari.app.lnreader.LnReaderProvider.isLnReaderId(origin.config.id)) com.hikari.app.lnreader.LnReaderProvider.lastOutcome else if (com.hikari.app.cs3.Cs3MangaProvider.isCs3MangaId(origin.config.id)) com.hikari.app.cs3.Cs3MangaProvider.lastOutcome else com.hikari.app.manga.MangaProvider.lastOutcome
            ProviderType.FORK_VIDEO -> com.hikari.app.kotatsu.fork.ForkVideoProvider.lastOutcome
            ProviderType.IPTV -> IptvProvider.iptvErrors
        }
        if (message == null) map.remove(id) else map[id] = message
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
    suspend fun metaFor(item: MediaItem): MediaItem =
        com.hikari.app.providers.ProviderGate.interactive { metaForInner(item) }

    private suspend fun metaForInner(item: MediaItem): MediaItem = withContext(Dispatchers.IO) {
        synchronized(metaCache) { metaCache[item.uniqueId] }?.let { return@withContext it }
        val metaKey = MetaCache.metaKey(item.uniqueId) +
            if (TrackerAnimeResolver.isTrackerAnime(item)) "|trk2" else ""
        MetaCache.cachedMeta(metaKey)?.let { cached ->
            if (!cached.overview.isNullOrBlank()) {
                synchronized(metaCache) { metaCache[item.uniqueId] = cached }
                return@withContext cached
            }
        }
        val originProvider = manager.byId(item.providerId)
        if (originProvider != null) {
            runCatching { originProvider.warmDetail(item) }
        }
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
            MetaCache.putMeta(metaKey, t)
            return@withContext t
        }
        val nativeIds = runCatching { HikariApp.instance.store.nativeProviders() }.getOrDefault(emptySet())
        if (originProvider != null && item.providerId in nativeIds) {
            val t = translateItem(result)
            synchronized(metaCache) { metaCache[item.uniqueId] = t }
            MetaCache.putMeta(metaKey, t)
            return@withContext t
        }
        val others = if (TrackerAnimeResolver.isTrackerAnime(result)) emptyList()
        else manager.providers.value.filter {
            it.config.enabled && it.config.id != item.providerId && it.config.type == ProviderType.STREMIO
        }
        if (others.size > 1 && result.backdropUrl == null && result.overview == null) {
            val raced = supervisorScope {
                others.take(4).map { alt ->
                    async(Dispatchers.IO) {
                        withTimeoutOrNull(8_000) { cancellableCatching { alt.getMeta(result) }.getOrDefault(result) }
                    }
                }.awaitAll().filterNotNull()
            }.firstOrNull { it.backdropUrl != null || it.overview != null }
            if (raced != null) {
                if (result.backdropUrl == null && raced.backdropUrl != null) result = result.copy(backdropUrl = raced.backdropUrl)
                if (result.overview == null && raced.overview != null) result = result.copy(overview = raced.overview)
                if (result.genres.isEmpty() && raced.genres.isNotEmpty()) result = result.copy(genres = raced.genres)
                if (result.year == null && raced.year != null) result = result.copy(year = raced.year)
            }
        }
        for (alt in others) {
            if (result.backdropUrl != null && result.overview != null) break
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
        // Tracker anime rows carry the tracker's numeric id, so TMDB can only ever
        // be reached by title search — and for anime Simkl's own records are the
        // correct ones (TMDB's anime coverage misfires on donghua and sequel
        // seasons). Fill what the origin left bare from Simkl first; the rating
        // pass below still applies on top. Tracker films take the same exit through
        // TrackerAnimeResolver.detail, which reads their TMDB film entry.
        if (TrackerAnimeResolver.needsTrackerDetail(result) && (result.overview.isNullOrBlank() || result.genres.isEmpty())) {
            runCatching { TrackerAnimeResolver.detail(result) }.getOrNull()?.let { d ->
                if (result.overview.isNullOrBlank() && !d.overview.isNullOrBlank()) result = result.copy(overview = d.overview)
                if (result.genres.isEmpty() && d.genres.isNotEmpty()) result = result.copy(genres = d.genres)
                if (result.year == null && d.year != null) result = result.copy(year = d.year)
                if (result.posterUrl.isNullOrBlank() && d.posterUrl != null) result = result.copy(posterUrl = d.posterUrl)
                if (result.backdropUrl == null && d.backdropUrl != null) result = result.copy(backdropUrl = d.backdropUrl)
                if (result.rating == null && d.rating != null) result = result.copy(rating = d.rating)
            }
        }
        val translated = translateItem(result)
        val engineType = manager.byId(translated.providerId)?.config?.type
        val animeMeta = runCatching { AnimeMetadataRepository.enrich(HikariApp.instance, translated, engineType) }.getOrNull()
        val finalMeta = animeMeta?.let {
            val animeRow = AnimeMetadataRepository.isAnimeItem(translated, engineType)
            translated.copy(
                title = it.title ?: translated.title,
                rating = it.rating ?: translated.rating,
                nextEpisodeDate = it.nextEpisodeDate ?: translated.nextEpisodeDate,
                metadataSource = it.source,
                year = if (animeRow) it.year ?: translated.year else translated.year,
                overview = translated.overview.takeIf { o -> !o.isNullOrBlank() } ?: it.overview,
                genres = translated.genres.ifEmpty { it.genres },
                posterUrl = translated.posterUrl.takeIf { u -> !u.isNullOrBlank() } ?: it.posterUrl,
                backdropUrl = translated.backdropUrl ?: it.backdropUrl,
            )
        } ?: translated
        synchronized(metaCache) { metaCache[item.uniqueId] = finalMeta }
        if (finalMeta != item) MetaCache.putMeta(metaKey, finalMeta)
        finalMeta
    }

    /** Episodes from the origin addon, falling back to the first other addon
     *  that can list them (some catalog addons serve videos for series via a
     *  different addon, e.g. Cinemeta-backed ids). Non-empty results are cached
     *  so re-opening a detail page doesn't repeat the whole lookup. */
    suspend fun episodesFor(
        // Movies never have episodes: answer instantly so movies never show Loading episodes.
        item: MediaItem,
        onPartial: ((List<Episode>) -> Unit)? = null,
    ): List<Episode>? = com.hikari.app.providers.ProviderGate.interactive {
        val got = episodesForInner(item, onPartial)
            ?.let { EpisodeDubSub.mergedFor(item.uniqueId, it) }
        if (!got.isNullOrEmpty()) return@interactive got
        val trk = TrackerAnimeResolver.trackerEpisodes(item)
            ?: TrackerAnimeResolver.fallbackEpisodes(item)
        if (trk != null) restoreAnimeSeasons(item, trk) else null
    }

    /**
     * A Vega title's packs (audio variants, seasons, quality rows) in provider
     * order — the detail page's picker. Empty for every other engine.
     */
    suspend fun vegaPacksFor(item: MediaItem): List<com.hikari.app.providers.vega.VegaPack> =
        withContext(Dispatchers.IO) {
            (manager.byId(item.providerId) as? com.hikari.app.providers.vega.VegaProvider)
                ?.packsFor(item).orEmpty()
        }

    suspend fun vegaPackSummaries(item: MediaItem): Map<Int, com.hikari.app.providers.vega.VegaPackSummary> =
        withContext(Dispatchers.IO) {
            (manager.byId(item.providerId) as? com.hikari.app.providers.vega.VegaProvider)
                ?.packSummaries(item).orEmpty()
        }

    /**
     * A Vega movie's playable quality rows in provider order — the detail
     * page's quality picker. Empty for every other engine.
     */
    suspend fun vegaMovieOptions(item: MediaItem): List<com.hikari.app.providers.vega.VegaMovieOption> =
        withContext(Dispatchers.IO) {
            (manager.byId(item.providerId) as? com.hikari.app.providers.vega.VegaProvider)
                ?.movieOptions(item).orEmpty()
        }

    /** [com.hikari.app.providers.vega.VegaProvider.selectionKey] for this
     *  title — "" for every other engine. */
    private fun vegaSelectionSuffix(item: MediaItem): String =
        (manager.byId(item.providerId) as? com.hikari.app.providers.vega.VegaProvider)
            ?.selectionKey(item.id).orEmpty()

    private fun streamsRememberedKey(item: MediaItem, episode: Episode?): String =
        streamsRememberedKey(item, episode, vegaSelectionSuffix(item))


        private val tmdbSeasonLayouts = java.util.concurrent.ConcurrentHashMap<String, List<AnimeMetadataRepository.SeasonLayout>>()

    /** A flat season=1 list cut into per-season local numbering per [layout], or
     *  null when the list does not match it (tolerance 20%). Provider episode ids
     *  are kept unchanged so playback links remain owned by the source. */
    private val directTmdbCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<Episode>>>()

    private suspend fun directTmdbEpisodes(item: MediaItem, tmdbId: Int): List<Episode>? {
        val key = tmdbId.toString()
        directTmdbCache[key]?.let { cached ->
            if (System.currentTimeMillis() - cached.first < DIRECT_TMDB_TTL_MS && cached.second.isNotEmpty()) return cached.second
        }
        val tv = com.hikari.app.nuvio.TmdbResolver.apiGet("/tv/" + tmdbId, emptyMap()) ?: return null
        val seasons = tv.optJSONArray("seasons") ?: return null
        val nums = (0 until seasons.length()).mapNotNull { i ->
            val s = seasons.optJSONObject(i) ?: return@mapNotNull null
            val n = s.optInt("season_number")
            if (n > 0 && s.optInt("episode_count") > 0) n else null
        }.take(24)
        if (nums.isEmpty()) return null
        val rows = coroutineScope {
            nums.map { sn ->
                async(Dispatchers.IO) {
                    val sd = com.hikari.app.nuvio.TmdbResolver.apiGet("/tv/" + tmdbId + "/season/" + sn, emptyMap())
                        ?: return@async emptyList<Episode>()
                    val eps = sd.optJSONArray("episodes") ?: return@async emptyList<Episode>()
                    buildList {
                        for (i in 0 until eps.length()) {
                            val e = eps.optJSONObject(i) ?: continue
                            val en = e.optInt("episode_number")
                            if (en <= 0) continue
                            val still = e.optString("still_path").trim()
                                .takeIf { it.isNotBlank() && it != "null" }
                                ?.let { "https://image.tmdb.org/t/p/w500" + it }
                            add(Episode(
                                number = en,
                                id = item.id + "#s" + sn + "e" + en,
                                name = e.optString("name").trim().takeIf { it.isNotBlank() && it != "null" },
                                image = still,
                                season = sn,
                                overview = e.optString("overview").trim().takeIf { it.isNotBlank() && it != "null" },
                                released = e.optString("air_date").trim().take(10).takeIf { it.isNotBlank() },
                                rating = e.optDouble("vote_average", 0.0).takeIf { it > 0.0 },
                                runtime = e.optInt("runtime", -1).takeIf { it > 0 },
                            ))
                        }
                    }
                }
            }.awaitAll().flatten()
        }
        if (rows.isEmpty()) return null
        val out = rows.sortedWith(compareBy({ it.season }, { it.number }))
        val past = out.filter { ep ->
            val air = ep.released ?: return@filter true
            air <= java.time.LocalDate.now().toString()
        }
        val final = (if (past.isNotEmpty()) past else out).sortedWith(compareBy({ it.season }, { it.number }))
        if (directTmdbCache.size >= DIRECT_TMDB_MAX) {
            val cutoff = System.currentTimeMillis() - DIRECT_TMDB_TTL_MS
            directTmdbCache.entries.removeAll { it.value.first < cutoff }
            while (directTmdbCache.size >= DIRECT_TMDB_MAX) {
                val oldest = directTmdbCache.entries.minByOrNull { it.value.first }?.key ?: break
                directTmdbCache.remove(oldest)
            }
        }
        directTmdbCache[key] = System.currentTimeMillis() to final
        return final
    }

    private fun splitFlatEpisodes(
        episodes: List<Episode>,
        layout: List<AnimeMetadataRepository.SeasonLayout>,
    ): List<Episode>? {
        if (layout.size < 2) return null
        val expected = layout.sumOf { it.episodes }
        val tolerance = maxOf(5, (expected * 0.20).toInt())
        if (kotlin.math.abs(expected - episodes.size) > tolerance) return null
        val out = ArrayList<Episode>(episodes.size)
        var cursor = 0
        for (bucket in layout) {
            var local = 1
            repeat(minOf(bucket.episodes, episodes.size - cursor)) {
                val original = episodes[cursor++]
                out += original.copy(season = bucket.season, number = local++)
            }
            if (cursor >= episodes.size) break
        }
        // More episodes than the layout covers (a new season is out but the
        // layout predates it): a whole season's worth starts a new season
        // instead of being dumped onto the last one; a couple of stragglers
        // still join it.
        if (cursor < episodes.size) {
            val remaining = episodes.size - cursor
            val restSeason = if (remaining >= 5) layout.last().season + 1 else layout.last().season
            var n = out.count { it.season == restSeason } + 1
            while (cursor < episodes.size) {
                val original = episodes[cursor++]
                out += original.copy(season = restSeason, number = n++)
            }
        }
        return out.takeIf { it.size == episodes.size }
    }

    /** TMDB's own season episode counts for [item], cached per title. Donghua is
     *  excluded by the caller: TMDB slices those by broadcast year while the
     *  sites serve one absolute numbering. */
    private suspend fun tmdbSeasonLayout(item: MediaItem): List<AnimeMetadataRepository.SeasonLayout> {
        if (!tmdbEpisodesAllowed(item)) return emptyList()
        val key = item.searchTitle.lowercase() + "|" + (item.year ?: 0)
        tmdbSeasonLayouts[key]?.let { return it }
        val out = withTimeoutOrNull(8_000) { tmdbSeasonLayoutNetwork(item) }.orEmpty()
        if (out.isNotEmpty()) {
            if (tmdbSeasonLayouts.size >= SEASON_LAYOUT_MAX) {
                // No timestamps here (a layout never goes stale) — drop
                // arbitrary overflow so a huge library cannot grow this
                // without bound; a dropped entry recomputes on demand.
                val drop = tmdbSeasonLayouts.keys.iterator()
                var left = tmdbSeasonLayouts.size - SEASON_LAYOUT_MAX + 1
                while (left > 0 && drop.hasNext()) { drop.next(); drop.remove(); left-- }
            }
            tmdbSeasonLayouts[key] = out
        }
        return out
    }

    private suspend fun tmdbSeasonLayoutNetwork(item: MediaItem): List<AnimeMetadataRepository.SeasonLayout> {
        val resolved = runCatching { com.hikari.app.nuvio.TmdbResolver.resolve(item) }.getOrNull()
            ?: return emptyList()
        if (!resolved.mediaType.equals("tv", true) && !resolved.mediaType.equals("anime", true)) return emptyList()
        val tv = com.hikari.app.nuvio.TmdbResolver.apiGet("/tv/" + resolved.tmdbId, emptyMap())
            ?: return emptyList()
        val arr = tv.optJSONArray("seasons") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val s = arr.optJSONObject(i) ?: return@mapNotNull null
            val n = s.optInt("season_number", -1)
            val c = s.optInt("episode_count", 0)
            if (n > 0 && c > 0) AnimeMetadataRepository.SeasonLayout(n, c) else null
        }
    }

    private suspend fun restoreAnimeSeasons(item: MediaItem, episodes: List<Episode>): List<Episode> {
        if (item.type != MediaType.SERIES || episodes.size < 8) return episodes
        if (item.providerId in runCatching { HikariApp.instance.store.nativeProviders() }.getOrDefault(emptySet())) return episodes
        if (episodes.map { it.season }.distinct().any { it > 1 }) return episodes
        val tmdbLayout = runCatching { tmdbSeasonLayout(item) }.getOrDefault(emptyList())
        splitFlatEpisodes(episodes, tmdbLayout)?.let { return it }
        return episodes
    }

    private suspend fun resolveOwnEpisode(
        p: ContentProvider,
        item: MediaItem,
        episode: Episode?,
        isOrigin: Boolean,
    ): Episode? {
        if (episode == null || !isOrigin) return episode
        if (!isSiteScraperOrigin(item)) return episode
        return try {
            val own = withTimeoutOrNull(20_000L) { p.getEpisodes(item) }.orEmpty()
            if (own.isEmpty()) return episode
            if (isTmdbSyntheticList(item, own)) return episode
            val match = own.firstOrNull { it.season == episode.season && it.number == episode.number }
                ?: own.firstOrNull { it.number == episode.number }
                ?: return episode
            if (match.id == episode.id) {
                episode
            } else {
                com.hikari.app.data.Logs.log(
                    "Provider",
                    p.config.name.ifBlank { p.config.id } +
                        " [${p.config.type.groupLabel}]" +
                        ": series tap carried a foreign episode id — " +
                        "playing the origin's own S${match.season}E${match.number} credential",
                )
                match
            }
        } catch (e: Throwable) {
            currentCoroutineContext().ensureActive()
            episode
        }
    }

    private fun isSiteScraperOrigin(item: MediaItem): Boolean =
        when (manager.byId(item.providerId)?.config?.type) {
            ProviderType.CS3, ProviderType.HIKARI, ProviderType.UNIVERSAL,
            ProviderType.SKYSTREAM, ProviderType.ANIYOMI, ProviderType.VEGA,
            ProviderType.SORA, ProviderType.ANYMEX, ProviderType.ZANGETSU,
            ProviderType.FORK_VIDEO -> true
            else -> false
        }

    private fun isTmdbSyntheticList(item: MediaItem, eps: List<Episode>): Boolean {
        if (eps.isEmpty()) return false
        val prefix = item.id + "#s"
        var synthetic = 0
        for (e in eps) {
            if (e.id.startsWith(prefix) && e.id.contains("e")) synthetic++
        }
        return synthetic * 2 >= eps.size
    }


    private class EpisodeDetailMemory {
        private val known = HashMap<Pair<Int, Int>, Episode>()

        fun carry(next: List<Episode>): List<Episode> {
            if (next.isEmpty()) return next
            var changed = false
            val out = ArrayList<Episode>(next.size)
            for (e in next) {
                val key = e.season to e.number
                val prev = known[key]
                if (prev != null && prev !== e) {
                    val merged = e.copy(
                        overview = e.overview.takeIf { !it.isNullOrBlank() } ?: prev.overview,
                        released = e.released.takeIf { !it.isNullOrBlank() } ?: prev.released,
                        rating = e.rating ?: prev.rating,
                        runtime = e.runtime ?: prev.runtime,
                        image = e.image.takeIf { !it.isNullOrBlank() } ?: prev.image,
                    )
                    if (merged != e) {
                        changed = true
                        known[key] = merged
                        out += merged
                        continue
                    }
                } else if (prev == null) {
                    known[key] = e
                }
                if (prev !== e) known[key] = e
                out += e
            }
            return if (changed) out else next
        }
    }

    private suspend fun episodesForInner(
        item: MediaItem,
        onPartial: ((List<Episode>) -> Unit)? = null,
    ): List<Episode>? = withContext(Dispatchers.IO) {
        val detailMem = EpisodeDetailMemory()
        val publish: (List<Episode>) -> List<Episode> = { list ->
            val enriched = detailMem.carry(list)
            onPartial?.invoke(enriched)
            enriched
        }
        val selKey = item.uniqueId + vegaSelectionSuffix(item)
        // The origin owns the list for a site-scraper (see
        // [isSiteScraperOrigin]): TMDB's synthetic ids can never play there.
        val scraperOrigin = isSiteScraperOrigin(item)
        synchronized(episodeCache) { episodeCache[selKey] }?.let {
            if (scraperOrigin && isTmdbSyntheticList(item, it)) {
                synchronized(episodeCache) { episodeCache.remove(selKey) }
                runCatching { MetaCache.evictEpisodes(selKey) }
            } else {
                val fixed = restoreAnimeSeasons(item, it)
                publish(fixed)
                if (fixed !== it) {
                    synchronized(episodeCache) { episodeCache[selKey] = fixed }
                }
                return@withContext fixed
            }
        }
        val epsKey = MetaCache.episodesKey(selKey) +
            if (TrackerAnimeResolver.isTrackerAnime(item)) "|trk2" else ""
        val cachedEps = MetaCache.cachedEpisodes(epsKey)
        // Poison check (see above): a TMDB-synthetic disk list for a
        // site-scraper is phantom seasons — evict it and ask the origin.
        val cachedPoisoned = scraperOrigin && cachedEps != null && isTmdbSyntheticList(item, cachedEps)
        if (cachedPoisoned) runCatching { MetaCache.evictEpisodes(selKey) }
        val cachedSeasoned = if (cachedPoisoned) null else cachedEps?.let { restoreAnimeSeasons(item, it) }
        cachedSeasoned?.let { publish(it) }
        if (!scraperOrigin && !TrackerAnimeResolver.isTrackerAnime(item) && cachedSeasoned == null && (item.type == MediaType.SERIES || item.type == MediaType.UNKNOWN)) {
            val tmdbEnd = System.currentTimeMillis() + TMDB_SHORTCUT_MS
            fun tmdbLeft() = (tmdbEnd - System.currentTimeMillis()).coerceAtLeast(0L)
            val tmdbId = if (TrackerAnimeResolver.isTrackerAnime(item)) {
                runCatching {
                    withTimeoutOrNull(tmdbLeft()) { com.hikari.app.nuvio.TmdbResolver.resolve(item) }
                }.getOrNull()
                    ?.takeIf { it.mediaType.equals("tv", true) }?.tmdbId?.toIntOrNull()?.takeIf { it > 0 }?.toString()
            } else {
                item.id.trim().takeIf { it.isNotEmpty() && it.all { c -> c.isDigit() } }
                    ?: runCatching {
                        withTimeoutOrNull(tmdbLeft()) { com.hikari.app.nuvio.TmdbResolver.resolve(item) }
                    }.getOrNull()
                        ?.takeIf { it.mediaType.equals("tv", true) }?.tmdbId?.toIntOrNull()?.takeIf { it > 0 }?.toString()
            }
            if (!tmdbId.isNullOrBlank() && tmdbId.all { c -> c.isDigit() } && tmdbLeft() > 0L) {
                val direct = runCatching {
                    withTimeoutOrNull(tmdbLeft()) {
                        directTmdbEpisodes(item, tmdbId.toInt())
                    }
                }.getOrNull()
                if (!direct.isNullOrEmpty()) {
                    val sorted = direct.sortedWith(compareBy({ it.season }, { it.number }))
                    val translated = publish(translateEpisodes(item.providerId, sorted))
                    val named = withTimeoutOrNull(tmdbLeft()) { withRealEpisodeNames(item, translated) }
                        ?: translated
                    return@withContext finishEpisodes(item, selKey, epsKey, named, publish)
                }
            }
        }
        val others = if (TrackerAnimeResolver.isTrackerAnime(item)) emptyList()
        else manager.providers.value.filter {
            it.config.enabled && it.config.id != item.providerId && it.config.type == ProviderType.STREMIO
        }
        val ordered = listOfNotNull(manager.byId(item.providerId)) +
            (if (item.type == MediaType.SERIES || item.type == MediaType.UNKNOWN) others else emptyList())

        // Do not serialize every episode lookup behind the first extension. A cold
        // CloudStream/Anymex runtime can take several seconds to answer while a
        // second installed source can already have the full list. Start a small,
        // bounded fan-out and publish each non-empty answer immediately. The
        // origin still wins when it eventually answers, preserving source
        // preference while making the first visible episode arrive as soon as
        // ANY capable extension responds.
        // A site-scraper's page shows ONLY its own list: the origin is asked
        // first and alone, so a slow TMDB answer or another engine's fuller
        // list can never paint seasons the origin never scraped. The reported
        // case: Attack on Titan opened from a CloudStream repo that scrapes
        // season 1 only — the page showed all 4 seasons, Play sent a foreign
        // episode id to the extension and found no sources; reopening showed
        // season 1 and played. An empty origin still falls through to the
        // borrow sweep below (a fallback, not the truth), and the next open
        // asks the origin first again.
        if (scraperOrigin && (item.type == MediaType.SERIES || item.type == MediaType.UNKNOWN)) {
            val origin = manager.byId(item.providerId)
            if (origin != null) {
                val raw = withTimeoutOrNull(episodesForTimeoutMs(origin)) {
                    cancellableCatching { origin.getEpisodes(item) }.getOrNull().orEmpty()
                }.orEmpty()
                val eps = EpisodeDubSub.mergedFor(item.uniqueId, raw)
                if (eps.isNotEmpty()) {
                    val restored = restoreAnimeSeasons(item, eps)
                    val sorted = publish(restored.sortedWith(compareBy({ it.season }, { it.number })))
                    val translated = publish(translateEpisodes(item.providerId, sorted))
                    val named = withRealEpisodeNames(item, translated)
                    return@withContext finishEpisodes(item, selKey, epsKey, named, publish)
                }
            }
        }
        if (!scraperOrigin && (item.type == MediaType.SERIES || item.type == MediaType.UNKNOWN)) {
            val targets = ordered.distinctBy { it.config.id }.take(8)
            val answers = supervisorScope {
                val jobs = targets.mapIndexed { index, p ->
                    async(Dispatchers.IO) {
                        val raw = withTimeoutOrNull(episodesForTimeoutMs(p)) {
                            cancellableCatching { p.getEpisodes(item) }.getOrNull().orEmpty()
                        }.orEmpty()
                        val eps = EpisodeDubSub.mergedFor(item.uniqueId, raw)
                        if (eps.isNotEmpty()) {
                            val restored = restoreAnimeSeasons(item, eps)
                            val sorted = publish(restored.sortedWith(compareBy({ it.season }, { it.number })))
                            Triple(index, p, sorted)
                        } else {
                            null
                        }
                    }
                }
                val first = jobs.getOrNull(0)?.await()
                if (first != null) {
                    for ((i, j) in jobs.withIndex()) if (i != 0) j.cancel()
                    listOf(first)
                } else {
                    jobs.awaitAll().filterNotNull()
                }
            }
            if (answers.isNotEmpty()) {
                val originIndex = answers.firstOrNull { it.first == 0 }
                val winner = originIndex ?: answers.minByOrNull { it.first }!!
                val sorted = winner.third
                val translated = publish(translateEpisodes(item.providerId, sorted))
                val named = withRealEpisodeNames(item, translated)
                return@withContext finishEpisodes(item, selKey, epsKey, named, publish)
            }
        } else if (!scraperOrigin) {
            for (p in ordered) {
                val raw = (withTimeoutOrNull(episodesForTimeoutMs(p)) {
                    cancellableCatching { p.getEpisodes(item) }.getOrNull() ?: emptyList()
                }) ?: emptyList()
                val eps = EpisodeDubSub.mergedFor(item.uniqueId, raw)
                if (eps.isNotEmpty()) {
                    val restored = restoreAnimeSeasons(item, eps)
                    val sorted = publish(restored.sortedWith(compareBy({ it.season }, { it.number })))
                    val translated = publish(translateEpisodes(item.providerId, sorted))
                    val named = withRealEpisodeNames(item, translated)
                    return@withContext finishEpisodes(item, selKey, epsKey, named, publish)
                }
            }
        }

        // Tracker rows resolve here — BEFORE the 50-second extension borrow
        // sweep — so the first paint lands in a second or two: the numbered
        // base list the moment the count is known, then the TMDB enrichment
        // replacing it when it lands (10 seconds total). An enriched tracker
        // list returns immediately; a still-bare one falls through to the
        // borrowed site list below, which paints over the base when it
        // arrives instead of the base clobbering it.
        var trkStaged: List<Episode>? = null
        if (TrackerAnimeResolver.isTrackerAnime(item) && (item.type == MediaType.SERIES || item.type == MediaType.UNKNOWN)) {
            val got = runCatching {
                TrackerAnimeResolver.trackerEpisodesStaged(item) { base ->
                    val list = publish(restoreAnimeSeasons(item, base))
                    publish(translateEpisodes(item.providerId, list))
                }
            }.getOrNull()?.takeIf { it.isNotEmpty() }
            trkStaged = got
            if (got != null && got.any { e ->
                    (!e.name.isNullOrBlank() && !EpisodeTitles.isGeneric(e.name)) || !e.overview.isNullOrBlank()
                }) {
                val list = publish(restoreAnimeSeasons(item, got))
                val translated = publish(translateEpisodes(item.providerId, list))
                val out = publish(translated)
                synchronized(episodeCache) { episodeCache[selKey] = out }
                MetaCache.putEpisodes(epsKey, out)
                return@withContext out
            }
        }
        if (item.type == MediaType.SERIES || item.type == MediaType.UNKNOWN) {
            episodesFromExtensions(item, { publish(it) })?.let { list ->
                val seasoned = publish(restoreAnimeSeasons(item, publish(list)))
                // Same order as above: auto-translate first, then TMDB's names in
                // the app's chosen language (which win when they exist).
                val translated = publish(translateEpisodes(item.providerId, seasoned))
                val named = withRealEpisodeNames(item, translated)
                return@withContext finishEpisodes(item, selKey, epsKey, named, publish)
            }
        }
        if (TrackerAnimeResolver.isTrackerAnime(item)) {
            trkStaged?.takeIf { it.isNotEmpty() }?.let { raw ->
                    val list = publish(restoreAnimeSeasons(item, raw))
                    val translated = publish(translateEpisodes(item.providerId, list))
                    val named = withRealEpisodeNames(item, translated)
                    return@withContext finishEpisodes(item, selKey, epsKey, named, publish)
                }
        }
        val merged = cachedSeasoned?.let { EpisodeDubSub.mergedFor(item.uniqueId, it) }
            ?: return@withContext null
        val filled = backfillEpisodeDetails(item, publish(merged))
        val out = publish(filled)
        if (out !== merged) {
            synchronized(episodeCache) { episodeCache[selKey] = out }
            MetaCache.putEpisodes(epsKey, out)
        }
        out
    }

    private suspend fun episodesFromExtensions(
        item: MediaItem,
        onPartial: ((List<Episode>) -> Unit)? = null,
    ): List<Episode>? {
        if (item.type != MediaType.SERIES) return null
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
        val trackerBacked = item.providerId.lowercase() in setOf(
            "anilist", "simkl", "mal", "kitsu", "shikimori", "trakt"
        )
        val exceptionIds = if (SearchScope.isException(item.providerId)) emptySet()
        else SearchScope.exceptions
        // A tracker row is a virtual source, not an installed extension. Its
        // episode list must therefore be borrowed by title even when the user's
        // ordinary "search all extensions" setting is off; otherwise a saved
        // AniList series can render perfectly in the library but has no provider
        // from which Hikari can obtain its actual episodes.
        // Episode discovery is metadata, not server search; do not inherit playback scope.
        val originType = manager.byId(item.providerId)?.config?.type
        val candidates = manager.providers.value
            .filter { p ->
                p.config.enabled &&
                    p.config.id != item.providerId &&
                    !isCfSkipped(p.config.id) &&
                    true
                    when (p.config.type) {
                        ProviderType.CS3,
                        ProviderType.HIKARI,
                        ProviderType.UNIVERSAL,
                        ProviderType.SKYSTREAM,
                        ProviderType.ANIYOMI,
                        ProviderType.VEGA,
                        ProviderType.SORA,
                        ProviderType.ANYMEX,
                        ProviderType.ANYMEX_MANGA,
                        ProviderType.ZANGETSU,
                        ProviderType.FORK_VIDEO,
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
                            while (true) {
                                val cur = found.get()
                                if (cur != null && cur.size >= list.size) break
                                if (found.compareAndSet(cur, list)) {
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
        return EpisodeDubSub.mergedFor(item.uniqueId, eps)
            .sortedWith(compareBy({ it.season }, { it.number }))
    }

    private suspend fun vegaTvKey(
        item: MediaItem,
        eps: List<Episode>,
    ): com.hikari.app.providers.vega.VegaTvKey? {
        if (!item.providerId.startsWith("vega|")) return null
        if (eps.map { it.season }.toSet().size != 1) return null
        val p = manager.byId(item.providerId)
            as? com.hikari.app.providers.vega.VegaProvider ?: return null
        return runCatching { p.tvKeyFor(item) }.getOrNull()
    }

    /** TMDB-backed episode naming/details for [item]'s kind, honoring the
     *  Integrations toggles (extension-own rows never reach here — they
     *  return before any rename is attempted). */
    private suspend fun tmdbEpisodesAllowed(item: MediaItem): Boolean {
        val store = HikariApp.instance.store
        val anime = AnimeMetadataRepository.isAnimeItem(item, manager.byId(item.providerId)?.config?.type)
        return runCatching { if (anime) store.animeTmdb() else store.videoTmdb() }.getOrDefault(true)
    }

    private suspend fun withRealEpisodeNames(item: MediaItem, eps: List<Episode>): List<Episode> {
        // Extension-own rows keep the origin's names untouched.
        if (item.providerId in runCatching { HikariApp.instance.store.nativeProviders() }.getOrDefault(emptySet())) return eps
        val anilistNamed = animeEpisodeNames(item, eps)
        if (eps.size < 3) return anilistNamed
        // TMDB naming only when TMDB is on for this kind of row.
        if (!tmdbEpisodesAllowed(item)) return anilistNamed
        val multiSeason = anilistNamed.map { it.season }.toSet().size > 1
        // A picked Vega pack names its own season ("[Season 3]"): the lookup
        // follows the pack, not the show title — otherwise every pack of a
        // multi-season post reads season 1's names. Cour parts ("PART - 2")
        // hold the season's second half, so their numbers shift past the
        // earlier part's files. Single-season lists only; everything else
        // keeps the long-standing title behavior exactly.
        val vegaTv = if (!multiSeason) vegaTvKey(item, anilistNamed) else null
        val vegaShift = vegaTv?.offset ?: 0
        val numbers = if (multiSeason) (1..anilistNamed.size).toSet() else eps.map { it.number + vegaShift }.toSet()
        val showName = item.searchTitle
        val language = com.hikari.app.nuvio.TmdbResolver.contentLanguage.takeIf { it.isNotBlank() }
        val needNames = anilistNamed.any { EpisodeTitles.needsEnglish(it.name, showName) }
        // Without a language there is nothing to do unless a row's own label
        // needs upgrading (that is what keeps ordinary shows from costing a
        // request); WITH one, every episode is looked up, because the provider's
        // real English title is still not the language the user chose.
        if (language == null && !needNames) return anilistNamed
        val seasonHint = TmdbMeta.seasonHint(showName)
        val seasonBase = HashMap<Int, Int>()
        if (multiSeason) {
            var acc = 0
            for ((sn, rows) in anilistNamed.groupBy { it.season }.toSortedMap()) {
                seasonBase[sn] = acc
                acc += rows.size
            }
        }
        fun absOf(e: Episode): Int = if (!multiSeason) e.number + vegaShift else (seasonBase[e.season] ?: 0) + e.number
        val names = withTimeoutOrNull(20_000) {
            val resolvedId = withTimeoutOrNull(8_000) {
                runCatching { com.hikari.app.nuvio.TmdbResolver.resolve(item) }.getOrNull()
            }?.takeIf { it.mediaType.equals("tv", true) }?.tmdbId?.toIntOrNull()?.takeIf { it > 0 }
            if (resolvedId != null) {
                runCatching { EpisodeTitles.lookupForId(resolvedId, numbers, vegaTv?.season ?: seasonHint, language) }.getOrNull()?.takeIf { !it.isEmpty() }
                    ?: runCatching { EpisodeTitles.lookup(showName, item.year, numbers, language) }.getOrNull()
            } else {
                runCatching { EpisodeTitles.lookup(showName, item.year, numbers, language) }.getOrNull()
            }
        } ?: return anilistNamed
        if (names.isEmpty()) return anilistNamed
        var changed = false
        val out = anilistNamed.map { e ->
            val raw = e.name
            val key = absOf(e)
            val replacement = when {
                names.english[key] != null -> names.english[key]
                raw.isNullOrBlank() -> names.generic[key]
                EpisodeTitles.looksMechanical(raw, showName) -> names.generic[key]
                else -> null
            }
            if (replacement != null && replacement != raw) {
                changed = true
                e.copy(name = replacement)
            } else {
                e
            }
        }
        return if (changed) out else anilistNamed
    }

    private suspend fun animeEpisodeNames(item: MediaItem, eps: List<Episode>): List<Episode> {
        if (eps.isEmpty()) return eps
        // One absolute numbering only: per-season restarts would map numbers
        // onto the wrong AniList rows.
        if (eps.map { it.season }.toSet().size > 1) return eps
        val store = HikariApp.instance.store
        if (!runCatching { store.animeAnilist() }.getOrDefault(true)) return eps
        if (item.providerId in runCatching { store.nativeProviders() }.getOrDefault(emptySet())) return eps
        if (!AnimeMetadataRepository.isAnimeItem(item, manager.byId(item.providerId)?.config?.type)) return eps
        val byNum = runCatching { AnimeMetadataRepository.animeFull(item.searchTitle) }.getOrNull()
            ?.episodes?.associateBy { it.number } ?: return eps
        if (byNum.isEmpty()) return eps
        var changed = false
        val out = eps.map { e ->
            val a = byNum[e.number] ?: return@map e
            val upgrade = e.name.isNullOrBlank() ||
                EpisodeTitles.looksMechanical(e.name, item.searchTitle) ||
                EpisodeTitles.needsEnglish(e.name, item.searchTitle)
            val newName = if (upgrade) (a.title ?: e.name) else e.name
            val newImage = if (e.image.isNullOrBlank()) a.image else e.image
            if (newName != e.name || newImage != e.image) {
                changed = true
                e.copy(name = newName, image = newImage)
            } else e
        }
        return if (changed) out else eps
    }

    private suspend fun finishEpisodes(
        item: MediaItem,
        selKey: String,
        epsKey: String,
        named: List<Episode>,
        publish: (List<Episode>) -> List<Episode>,
    ): List<Episode> {
        val restored = restoreAnimeSeasons(item, named)
        val enrichEnd = System.currentTimeMillis() + 3_000L
        fun enrichLeft() = (enrichEnd - System.currentTimeMillis()).coerceAtLeast(0L)
        val withNames = if (enrichLeft() > 0L) {
            withTimeoutOrNull(enrichLeft()) { withRealEpisodeNames(item, restored) } ?: restored
        } else restored
        val filled = if (enrichLeft() > 0L) {
            withTimeoutOrNull(enrichLeft()) { backfillEpisodeDetails(item, publish(withNames)) } ?: withNames
        } else withNames
        // AniList stills last: only blanks TMDB could not fill, anime rows
        // with AniList on, never touching extension-own rows.
        val stills = if (enrichLeft() > 0L) {
            withTimeoutOrNull(enrichLeft()) { fillAnimeStills(item, filled) } ?: filled
        } else filled
        val out = publish(stills)
        synchronized(episodeCache) { episodeCache[selKey] = out }
        MetaCache.putEpisodes(epsKey, out)
        return out
    }

    private suspend fun backfillEpisodeDetails(item: MediaItem, eps: List<Episode>): List<Episode> {
        if (eps.isEmpty()) return eps
        val list = eps
        if (list.all {
                !it.overview.isNullOrBlank() && !it.released.isNullOrBlank() &&
                    it.rating != null && it.runtime != null
            }
        ) {
            return list
        }
        // Extension-own rows keep the origin's episode details; TMDB details
        // only land when TMDB is on for this kind of row.
        if (item.providerId in runCatching { HikariApp.instance.store.nativeProviders() }.getOrDefault(emptySet())) return list
        if (!tmdbEpisodesAllowed(item)) return list
        // Same duplicate-number rule as [withRealEpisodeNames]: twins share one
        // TMDB row and each twin gets the same details filled in.
        val singleSeason = list.map { it.season }.toSet().size == 1
        val vegaTv = if (singleSeason) vegaTvKey(item, list) else null
        val vegaShift = vegaTv?.offset ?: 0
        val numbers = if (singleSeason) list.map { it.number + vegaShift }.toSet() else (1..list.size).toSet()
        val language = com.hikari.app.nuvio.TmdbResolver.contentLanguage.takeIf { it.isNotBlank() }
        val seasonHint = vegaTv?.season ?: TmdbMeta.seasonHint(item.searchTitle)
        val map = withTimeoutOrNull(22_000) {
            val resolvedId = withTimeoutOrNull(8_000) {
                runCatching { com.hikari.app.nuvio.TmdbResolver.resolve(item) }.getOrNull()
            }?.takeIf { it.mediaType.equals("tv", true) }?.tmdbId?.toIntOrNull()?.takeIf { it > 0 }
            if (resolvedId != null) {
                runCatching { EpisodeTitles.detailsForId(resolvedId, numbers, seasonHint, language) }.getOrNull()?.takeIf { it.isNotEmpty() }
                    ?: runCatching { EpisodeTitles.details(item.searchTitle, item.year, numbers, language) }.getOrNull()
            } else {
                runCatching { EpisodeTitles.details(item.searchTitle, item.year, numbers, language) }.getOrNull()
            }
        } ?: return list
        if (map.isEmpty()) return list
        var changed = false
        val out = list.map { e ->
            val d = if (singleSeason) map[e.number + vegaShift]
            else map.values.firstOrNull { it.season == e.season && it.number == e.number }
            if (d == null) return@map e
            val ov = if (e.overview.isNullOrBlank()) d.overview else e.overview
            val rel = if (e.released.isNullOrBlank()) d.released else e.released
            val ra = e.rating ?: d.rating
            val ru = e.runtime ?: d.runtime
            val im = if (e.image.isNullOrBlank()) d.image else e.image
            if (ov != e.overview || rel != e.released || ra != e.rating ||
                ru != e.runtime || im != e.image
            ) {
                changed = true
                e.copy(overview = ov, released = rel, rating = ra, runtime = ru, image = im)
            } else {
                e
            }
        }
        return if (changed) out else list
    }

    private suspend fun fillAnimeStills(item: MediaItem, eps: List<Episode>): List<Episode> {
        if (eps.isEmpty() || eps.none { it.image.isNullOrBlank() }) return eps
        if (eps.map { it.season }.toSet().size > 1) return eps
        val store = HikariApp.instance.store
        if (!runCatching { store.animeAnilist() }.getOrDefault(true)) return eps
        if (item.providerId in runCatching { store.nativeProviders() }.getOrDefault(emptySet())) return eps
        if (!AnimeMetadataRepository.isAnimeItem(item, manager.byId(item.providerId)?.config?.type)) return eps
        val byNum = runCatching { AnimeMetadataRepository.animeFull(item.searchTitle) }.getOrNull()
            ?.episodes?.associateBy { it.number } ?: return eps
        if (byNum.isEmpty()) return eps
        var changed = false
        val out = eps.map { e ->
            val img = byNum[e.number]?.image
            if (e.image.isNullOrBlank() && !img.isNullOrBlank()) {
                changed = true
                e.copy(image = img)
            } else e
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

private class HeldSweep(
    val item: MediaItem,
    val episode: Episode?,
    @Volatile var snapshot: List<StreamSource>,
    val sinks: MutableList<suspend (List<StreamSource>) -> Unit> = mutableListOf(),
)
