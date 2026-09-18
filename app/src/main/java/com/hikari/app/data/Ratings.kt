package com.hikari.app.data

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import com.hikari.app.HikariApp
import com.hikari.app.net.Http
import com.hikari.app.nuvio.TmdbResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Aggregate review scores for one title — the row of coloured IMDb /
 * Rotten Tomatoes / Metacritic / Letterboxd / TMDB badges under the Play
 * button, the way the reference clients show them.
 *
 * None of these sources needs an API key, and each one is independently
 * optional: a source that is blocked, slow, renamed or simply has no entry for
 * the title contributes nothing and the row renders the rest. That is the whole
 * design constraint here — a review site is decoration, so it must never be able
 * to delay or break the page it decorates.
 *
 *  1. **IMDb** — tried in order: OMDb (current numbers, one decimal, plus the
 *     vote count), Wikidata (`P444`/`P447` keyed by the IMDb id — the broadest
 *     coverage, but user-maintained and often stale), then the Cinemeta mirror
 *     Stremio uses. imdb.com itself answers a plain HTTP client with an empty
 *     body and IMDb's own GraphQL endpoint is Cloudflare-guarded, so a mirror is
 *     the only way to get a number at all; three of them make it reliable.
 *     When nothing knows the `tt` id, IMDb's own keyless suggestion endpoint
 *     resolves one from the title and year, and Cinemeta's search index is the
 *     fallback for a network where that host is blocked.
 *  2. **Rotten Tomatoes** `/{m|tv}/{slug}` — the page's `media-scorecard-json`
 *     carries the tomatometer AND the popcornmeter, each with its review count,
 *     average and sentiment: two badges from one request.
 *  3. **Metacritic** `/{movie|tv}/{slug}/` — the JSON-LD `aggregateRating`.
 *  4. **Letterboxd** `/imdb/{ttid}/` — `twitter:data2` ("4.46 out of 5").
 *  5. **TMDB** — the score the detail page's own details lookup already
 *     fetched, so the strip always has at least one badge.
 *
 * Results are cached on disk for a day (a review score does not move that
 * fast), which also keeps the request count to the sites at one per title —
 * the slug guesses below are cheap but they are still network round-trips.
 */
enum class RatingSource { IMDB, TOMATOMETER, POPCORN, METACRITIC, LETTERBOXD, TMDB }

/** The word a site itself uses for a score band. The label is the English UI
 *  key that `tr()` translates; the bands are applied by [Ratings] so the UI
 *  never has to know how a given site grades. */
enum class RatingVerdict(val label: String) {
    CERTIFIED_FRESH("Certified Fresh"),
    FRESH("Fresh"),
    ROTTEN("Rotten"),
    LIKED("Liked it"),
    DISLIKED("Didn't like it"),
    ACCLAIM("Acclaim"),
    FAVORABLE("Favorable"),
    MIXED("Mixed"),
    UNFAVORABLE("Unfavorable"),
    DISASTER("Dislike"),
}

/**
 * One badge: which site it came from, the number to show, and everything the
 * tap-through explanation needs. [votes] is the site's own review/rating count
 * when the page published one, [average] is the site's second number (RT's
 * "out of 5" critic average, Metacritic's user score), [verdict] is the band
 * word, and [url] the page the number came from so the dialog can offer to open
 * the source itself. Every field but the source and value is optional, so a
 * source that publishes nothing but a score still renders.
 */
data class TitleRating(
    val source: RatingSource,
    val value: String,
    val votes: Long? = null,
    val verdict: RatingVerdict? = null,
    val average: String? = null,
    val url: String? = null,
)

object Ratings {

    /** A day: review scores change on the order of days, not minutes. */
    private const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L

    /** A "nothing found" answer expires much sooner: it is usually a block or a
     *  bad slug guess rather than the truth about the title, and re-trying is
     *  what lets the scores appear once the site answers. */
    private const val EMPTY_TTL_MS = 2 * 60 * 60 * 1000L

    /** How long a source that published nothing is left alone before it is
     *  re-asked. The IMDb mirrors fail transiently (a rate-limited public key, a
     *  lazily-mirrored release), so they are re-asked soon; a missing review page
     *  or Metascore is usually the truth about a title, so those wait longer. */
    private const val RETRY_IMDB_MS = 15 * 60 * 1000L
    private const val RETRY_OTHER_MS = 3 * 60 * 60 * 1000L

    /** Per-source ceiling for a lookup. One site that hangs must cost a few
     *  seconds of one badge, never the whole strip. */
    private const val SOURCE_TIMEOUT_MS = 9_000L

    private val memory = ConcurrentHashMap<String, List<TitleRating>>()

    /**
     * One revision counter per title, for the poster badges.
     *
     * A poster's score comes from the cache ([cachedBadge]), and a cache read is
     * deliberately not observable — so a badge whose warm-up ([ensure]) landed
     * after the cell was drawn would stay blank until something else recomposed
     * it. A cell reads [revision] next to [cachedBadge], and a landing warm-up
     * bumps it, which recomposes exactly the cells showing that title (and
     * nothing else).
     */
    private val revisions = ConcurrentHashMap<String, MutableState<Long>>()

    /** The revision state for [key], created once and once only. Spelled with
     *  `computeIfAbsent` rather than `getOrPut` on purpose: `getOrPut` is a
     *  read-then-write, so two threads racing on a title's first warm-up could
     *  each build a state object and keep different ones — the cell would then
     *  observe a state nobody ever bumps and stay blank until something else
     *  recomposed it. */
    private fun revisionState(key: String): MutableState<Long> =
        revisions.computeIfAbsent(key) { mutableStateOf(0L) }

    /** The revision a poster badge should read alongside [cachedBadge]. */
    fun revision(item: MediaItem): Long = revisionState(cacheKey(item)).value

    private fun bumpRevision(key: String) {
        runCatching {
            val state = revisionState(key)
            state.value = state.value + 1L
        }
    }

    /** `key → source → when it was last asked`, so a source that answered
     *  nothing can be re-asked on a schedule instead of being frozen into the
     *  cached answer for a day. Persisted with the cache. */
    private val attempts = ConcurrentHashMap<String, ConcurrentHashMap<RatingSource, Long>>()

    /** Titles with a re-ask in flight, so re-opening a screen doesn't stack
     *  them. */
    private val refreshing = ConcurrentHashMap.newKeySet<String>()

    /** Re-asks run here rather than on a screen's scope: their job is to fix the
     *  cache for the next look, so they must not die with the screen that
     *  happened to trigger one. */
    private val refresher = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val lock = Any()
    private var disk: JSONObject? = null

    private val cacheFile: File get() = File(HikariApp.instance.filesDir, "ratings-cache.json")

    /**
     * Every badge known for [item]. [imdbId] comes from TMDB's `external_ids`
     * when it has one; [tmdbScore]/[tmdbVotes] are the values that lookup
     * already returned, so the TMDB badge costs no request.
     *
     * [onUpdate] (optional) is called with a LONGER list if a source that had
     * nothing to say at lookup time has since been re-asked and answered — the
     * strip grows instead of staying frozen on whatever the first attempt found
     * (see [missingSources]).
     */
    suspend fun load(
        item: MediaItem,
        imdbId: String?,
        tmdbScore: Double?,
        tmdbVotes: Int?,
        onUpdate: ((List<TitleRating>) -> Unit)? = null,
    ): List<TitleRating> = withContext(Dispatchers.IO) {
        val tmdb = tmdbBadge(tmdbScore, tmdbVotes, item)
        val key = cacheKey(item)
        memory[key]?.let { cached ->
            refreshInBackground(key, item, imdbId, cached, tmdb, onUpdate)
            return@withContext finish(cached, tmdb)
        }
        readDisk(key)?.let { cached ->
            memory[key] = cached
            refreshInBackground(key, item, imdbId, cached, tmdb, onUpdate)
            return@withContext finish(cached, tmdb)
        }

        // Nothing cached: one full pass. The scraped values are put in FIRST so
        // a fresher tomatometer/Metascore wins over Wikidata's (which is
        // user-maintained and can lag by years); the first source to claim a
        // slot keeps it (see [runSources]).
        //
        // When the caller brought neither an IMDb id nor a TMDB score (an
        // extension-sourced row, not a TMDB one) ask TMDB once for both: that
        // single request is also what gives the scraper sources an id to work
        // with, so the badge shows even when nothing else can name the title.
        val side = if (imdbId != null && tmdb != null) TmdbSide(null, imdbId)
        else tmdbSide(item, imdbId)
        val imdb = resolveImdb(item, side.imdbId)
        val want = applicableSources(item)
        val found = runSources(item, imdb, want)
        markAttempts(key, want)
        val list = found.values.sortedBy { it.source.ordinal }
        memory[key] = list
        writeDisk(key, list)
        finish(list, tmdb ?: side.badge)
    }

    /**
     * The score a poster's badge should print for [item], or null when nothing
     * has been looked up for it yet.
     *
     * The IMDb number comes first — it is the one the reference clients print,
     * and the one the yellow badge is for. When nothing could produce one (IMDb
     * publishes no `tt` id for the title, or every mirror of it is blocked on
     * the user's network) the TMDB average is used instead, which the same
     * lookup already fetched. That fallback is what makes a badge appear on
     * EVERY poster instead of only the ones the scrapers happened to answer for
     * — the reported "some posters show a rating, some show nothing".
     *
     * Cache-only and synchronous ON PURPOSE: this is what a poster's score badge
     * reads, and a Home row of sixty posters must never fire sixty lookups just
     * to draw itself. The detail page — which does the looking up — is what fills
     * the cache, and [ensure] is what warms it for titles nobody has opened.
     */
    fun cachedBadge(item: MediaItem): String? {
        val key = cacheKey(item)
        val list = memory[key] ?: readDisk(key)?.also { memory[key] = it } ?: return null
        list.firstOrNull { it.source == RatingSource.IMDB }
            ?.value?.takeIf { it.isNotBlank() }
            ?.let { return it }
        // "8.3/10" → "8.3": the badge prints a bare number, like the IMDb one.
        return list.firstOrNull { it.source == RatingSource.TMDB }
            ?.average?.substringBefore('/')?.trim()?.takeIf { it.isNotBlank() }
    }

    /** How many lookups [ensure] is allowed to have in flight at once. A grid
     *  asks for every poster it draws; without a ceiling, one Home screen would
     *  open hundreds of requests across five review sites and get the device
     *  rate-limited. Suspend-based, so a title waiting for a slot parks its
     *  coroutine instead of holding a thread. */
    private val ensureSlots = kotlinx.coroutines.sync.Semaphore(4)

    /** How many warm-ups may be waiting for a slot. Past this the oldest are
     *  simply not asked for (a Home feed can be thousands of posters long). */
    private const val MAX_ENSURE_QUEUE = 120

    /** Titles a warm-up has been started for and not yet finished — the dedupe
     *  that makes scrolling the same row ten times cost one pass. */
    private val ensureQueued = ConcurrentHashMap.newKeySet<String>()

    /**
     * Warms the cache for [item] in the background — what the poster-rating
     * switch uses so scores start appearing on the rows instead of only on
     * titles the user has already opened.
     *
     * Called again on every redraw, so three cases are cheap and one is not:
     * a title with a score on file returns immediately; a title whose last
     * answer was scoreless is re-asked only once its retry window has passed
     * ([missingSources]) — which is what keeps a warm-up that happened to run
     * while the network or one of the mirrors was busy from freezing a blank
     * corner for hours; and a title nobody has looked up yet does one pass.
     *
     * Never awaited and deduped per title, so scrolling a row past the same
     * poster ten times costs one pass. Callers use it as a hint, not a request:
     * the badge reads [cachedBadge] on the next recomposition after this lands.
     */
    fun ensure(item: MediaItem) {
        val key = cacheKey(item)
        val cached = memory[key] ?: readDisk(key)?.also { memory[key] = it }
        // A score on file is the whole point of this call.
        if (cached != null && cached.isNotEmpty()) return
        // A scoreless answer is retried on the same schedule the detail page's
        // refresh uses. Titles nobody has a review for stay cheap (the windows
        // are 15 minutes / 3 hours), but one that was merely unlucky gets its
        // badge on a later scroll instead of staying bare.
        if (cached != null && missingSources(key, item, cached).isEmpty()) return
        // WAIT for a slot instead of giving up on it. Dropping the title is what
        // left whole rows of posters bare: the first four to reach this point
        // were served and the rest were skipped, and nothing asked for them
        // again until the user happened to scroll the row back into view.
        if (ensureQueued.size >= MAX_ENSURE_QUEUE) return
        if (!ensureQueued.add(key)) return
        refresher.launch {
            try {
                ensureSlots.acquire()
                try {
                    val side = tmdbSide(item, null)
                    // Publish TMDB's own average the moment it lands — one
                    // request instead of the five the scraper pass makes — so a
                    // Home row fills in seconds and the IMDb/tomato numbers
                    // join it as they arrive. Binding the badge to the END of
                    // the pass meant the slowest review site decided when the
                    // row's scores appeared, which is how some posters came up
                    // badged and their neighbours did not.
                    if (side.badge != null) {
                        val quick = finish(memory[key].orEmpty(), side.badge)
                        memory[key] = quick
                        writeDisk(key, quick)
                        bumpRevision(key)
                    }
                    val imdb = resolveImdb(item, side.imdbId)
                    val want = applicableSources(item)
                    val found = runSources(item, imdb, want)
                    markAttempts(key, want)
                    val list = finish(found.values.sortedBy { it.source.ordinal }, side.badge)
                    // Cached even when empty: an empty answer is still an
                    // answer, and remembering it (for [EMPTY_TTL_MS]) is what
                    // keeps a title nobody has a score for from being re-asked
                    // on every scroll.
                    memory[key] = list
                    writeDisk(key, list)
                    bumpRevision(key)
                } finally {
                    ensureSlots.release()
                }
            } catch (e: Exception) {
                // A failed warm-up is not news: the next scroll tries again.
            } finally {
                ensureQueued.remove(key)
            }
        }
    }

    /** The TMDB half of a title: its average (as a badge) and the `tt` id TMDB
     *  has on file for it, when the caller did not already have one.
     *
     *  This is the backstop that makes the badge reliable. The scraper-side
     *  sources all key off an IMDb id, and when none can be resolved the whole
     *  strip used to come up empty even for a famous, well-reviewed title; TMDB
     *  is the app's own resolver, already reachable wherever the rest of the app
     *  works, and one request answers both halves of the problem. */
    private suspend fun tmdbSide(item: MediaItem, imdbId: String?): TmdbSide {
        if (!TmdbResolver.isLikelyResolvable(item)) return TmdbSide(null, imdbId)
        val resolved = TmdbResolver.resolve(item) ?: return TmdbSide(null, imdbId)
        val data = TmdbResolver.apiGet(
            "/${resolved.mediaType}/${resolved.tmdbId}",
            mapOf("append_to_response" to "external_ids"),
        ) ?: return TmdbSide(null, imdbId)
        val fromTmdb = data.optJSONObject("external_ids")
            ?.optString("imdb_id")?.trim()?.takeIf { isImdbId(it) }
        val score = data.optDouble("vote_average", 0.0)
        val votes = data.optInt("vote_count", 0)
        return TmdbSide(tmdbBadge(score, votes, item), imdbId ?: fromTmdb)
    }

    private data class TmdbSide(val badge: TitleRating?, val imdbId: String?)

    /** Every source that could contribute a badge for [item] — the set the
     *  refresh logic checks a cached answer against. */    private fun applicableSources(item: MediaItem): Set<RatingSource> {
        val want = LinkedHashSet<RatingSource>(5)
        want.add(RatingSource.IMDB)
        want.add(RatingSource.TOMATOMETER)
        want.add(RatingSource.POPCORN)
        want.add(RatingSource.METACRITIC)
        // Letterboxd has no TV pages at all, so it is never "missing" on a
        // series — asking forever would be pure waste.
        if (item.type != MediaType.SERIES) want.add(RatingSource.LETTERBOXD)
        return want
    }

    /**
     * The cached badges that have not been published yet, but only where the
     * source has not been asked recently ([RETRY_IMDB_MS] for the IMDb mirrors,
     * [RETRY_OTHER_MS] for the review sites).
     *
     * This is what makes the strip grow instead of freezing. Every source is
     * independently optional, so a lookup that ran while one of them was
     * rate-limited, timed out, or simply had no page for a two-day-old release
     * used to be cached as-is for a whole day — which is why one title showed
     * IMDb and the next did not, no matter how many times it was re-opened.
     */
    private fun missingSources(
        key: String,
        item: MediaItem,
        cached: List<TitleRating>,
    ): Set<RatingSource> {
        val have = cached.mapTo(HashSet()) { it.source }
        val seen = attempts[key]
        val now = System.currentTimeMillis()
        return applicableSources(item).filterNot { it in have }.filter { src ->
            val last = seen?.get(src) ?: 0L
            val window = if (src == RatingSource.IMDB) RETRY_IMDB_MS else RETRY_OTHER_MS
            now - last >= window
        }.toSet()
    }

    /**
     * Re-asks the sources a cached answer is still missing, off the caller's
     * thread, and hands the longer list to [onUpdate] when it lands.
     *
     * Deliberately NOT awaited: the screen already has the badges it had before,
     * so the extra work must never cost the user a wait — and if it finds
     * nothing (the usual case for a genuinely unrated or unreleased title) the
     * cache simply records the attempt and tries again in a few hours.
     */
    private fun refreshInBackground(
        key: String,
        item: MediaItem,
        imdbId: String?,
        cached: List<TitleRating>,
        tmdb: TitleRating?,
        onUpdate: ((List<TitleRating>) -> Unit)?,
    ) {
        val missing = missingSources(key, item, cached)
        if (missing.isEmpty()) return
        // One re-ask per title at a time: the detail screen can be re-opened (or
        // recomposed) while a refresh is still in flight.
        if (!refreshing.add(key)) return
        refresher.launch {
            try {
                // Same TMDB backstop as [ensure]: it is what fills a strip whose
                // every scraper-side answer was empty, and it is the only way a
                // title with no resolvable `tt` id gets a score at all.
                val side = if (cached.any { it.source == RatingSource.TMDB }) TmdbSide(null, imdbId)
                else tmdbSide(item, imdbId)
                val imdb = resolveImdb(item, side.imdbId)
                val found = runSources(item, imdb, missing)
                markAttempts(key, missing)
                val tmdbNow = tmdb ?: side.badge
                if (found.isEmpty() && tmdbNow == null) return@launch
                val merged = LinkedHashMap<RatingSource, TitleRating>()
                for (r in cached) merged[r.source] = r
                for ((source, r) in found) if (!merged.containsKey(source)) merged[source] = r
                if (tmdbNow != null && !merged.containsKey(RatingSource.TMDB)) {
                    merged[RatingSource.TMDB] = tmdbNow
                }
                val list = merged.values.sortedBy { it.source.ordinal }
                memory[key] = list
                writeDisk(key, list)
                bumpRevision(key)
                runCatching { onUpdate?.invoke(finish(list, tmdbNow)) }
            } catch (e: Exception) {
                // A failed re-ask is not news: the next open tries again.
            } finally {
                refreshing.remove(key)
            }
        }
    }

    /** The `tt` id to look IMDb up with: the one TMDB gave us, an id the item
     *  itself carries, or IMDb's own keyless suggestion endpoint. Re-run on every
     *  refresh, so a title that had no id at first (a brand-new release TMDB had
     *  no `external_ids` for yet) starts showing an IMDb badge once one exists. */
    private suspend fun resolveImdb(item: MediaItem, imdbId: String?): String? {
        imdbId?.trim()?.takeIf { isImdbId(it) }?.let { return it }
        item.id.trim().takeIf { isImdbId(it) }?.let { return it }
        return imdbSuggestionId(item) ?: cinemetaSearchId(item)
    }

    /**
     * The `tt` id from Cinemeta's own search index — the last way to get one when
     * TMDB published no `external_ids` and IMDb's suggestion endpoint is
     * unreachable (it is a `media-imdb.com` host, which some networks block).
     *
     * Without an id there is no IMDb badge at all, which is the other half of why
     * some titles showed one and others did not: this endpoint answers any title
     * with a keyless search, so a brand-new release now gets an id — and with it
     * an IMDb, a Letterboxd and a Wikidata lookup — the moment IMDb has a page.
     * The same guards as the suggestion endpoint apply (right kind, a close
     * enough name, the year within one), because a search for a common title
     * happily returns the wrong film.
     */
    private fun cinemetaSearchId(item: MediaItem): String? {
        val kind = if (item.type == MediaType.SERIES) "series" else "movie"
        val q = URLEncoder.encode(
            TmdbMeta.queryVariants(item.title).firstOrNull() ?: item.title, "UTF-8"
        ).replace("+", "%20")
        val body = Http.getStringQuiet("https://v3-cinemeta.strem.io/catalog/$kind/top/search=$q.json")
            ?: return null
        val arr = runCatching { JSONObject(body).optJSONArray("metas") }.getOrNull() ?: return null
        var best: String? = null
        var bestScore = -1
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("imdb_id").trim().ifBlank { o.optString("id").trim() }
            if (!isImdbId(id)) continue
            // The year the row prints: "2026" for a film, "2008-2013" for a show
            // (so the first four characters are the year it started).
            val shown = o.optString("releaseInfo").trim().take(4).toIntOrNull()
                ?: (o.opt("year") as? Number)?.toInt()
            var score = TmdbMeta.titleScore(item.title, o.optString("name"))
            if (shown != null && item.year != null) {
                val diff = abs(shown - item.year)
                if (diff > 1) continue
                if (diff == 0) score += 20
            }
            if (score > bestScore) {
                bestScore = score
                best = id
            }
        }
        return if (bestScore >= 25) best else null
    }

    /** Runs the sources that can contribute to any source in [want], and returns
     *  what they published. The job order IS the precedence: the first source to
     *  claim a slot keeps it. */
    private suspend fun runSources(
        item: MediaItem,
        imdb: String?,
        want: Set<RatingSource>,
    ): LinkedHashMap<RatingSource, TitleRating> {
        if (want.isEmpty()) return LinkedHashMap()
        val isSeries = item.type == MediaType.SERIES
        fun need(vararg sources: RatingSource) = sources.any { it in want }
        // Wikidata carries an IMDb score, a tomatometer, a Metascore and a
        // Letterboxd average, so any of those being wanted is enough to ask it.
        val wantWikidata = need(
            RatingSource.IMDB, RatingSource.TOMATOMETER,
            RatingSource.METACRITIC, RatingSource.LETTERBOXD,
        ) && imdb != null

        val found = LinkedHashMap<RatingSource, TitleRating>()
        coroutineScope {
            val jobs = ArrayList<Deferred<List<TitleRating>>>(5)
            if (need(RatingSource.TOMATOMETER, RatingSource.POPCORN)) {
                jobs += async { guarded { rtScores(item, isSeries) } }
            }
            if (need(RatingSource.METACRITIC)) {
                jobs += async { guarded { metacriticScore(item, isSeries) } }
            }
            if (!isSeries && imdb != null && need(RatingSource.LETTERBOXD)) {
                jobs += async { guarded { letterboxd(imdb) } }
            }
            if (imdb != null && need(RatingSource.IMDB)) {
                jobs += async { guarded { omdb(imdb) } }
            }
            if (wantWikidata) {
                jobs += async { guarded { wikidata(imdb!!) } }
            }
            if (imdb != null && need(RatingSource.IMDB)) {
                jobs += async { guarded { cinemeta(imdb, isSeries) } }
            }
            for (job in jobs) {
                for (r in job.await()) {
                    if (r.value.isNotBlank() && !found.containsKey(r.source)) {
                        found[r.source] = r
                    }
                }
            }
        }
        return found
    }

    /** Records that [sources] were asked for [key] just now — the clock the
     *  re-ask window is measured against. */
    private fun markAttempts(key: String, sources: kotlin.collections.Collection<RatingSource>) {
        if (sources.isEmpty()) return
        val now = System.currentTimeMillis()
        val row = attempts.getOrPut(key) { ConcurrentHashMap() }
        for (s in sources) row[s] = now
    }

    /** Never let one source's failure — or one site that simply hangs — cancel
     *  or hold up the whole strip. */
    private suspend fun guarded(block: suspend () -> List<TitleRating>): List<TitleRating> =
        runCatching { withTimeoutOrNull(SOURCE_TIMEOUT_MS) { block() } ?: emptyList() }
            .getOrDefault(emptyList())

    /** Adds the TMDB badge (which needs no request) to a cached result. */
    private fun finish(cached: List<TitleRating>, tmdb: TitleRating?): List<TitleRating> {
        if (tmdb == null || cached.any { it.source == RatingSource.TMDB }) return cached
        return (cached + tmdb).sortedBy { it.source.ordinal }
    }

    private fun tmdbBadge(score: Double?, votes: Int?, item: MediaItem): TitleRating? {
        // A brand-new title with a handful of votes has a meaningless average:
        // TMDB's own site hides the score below 10 votes, and so do we.
        if (score == null || score <= 0.0) return null
        if ((votes ?: 0) < 10) return null
        val percent = (score * 10).roundToInt()
        return TitleRating(
            source = RatingSource.TMDB,
            value = "$percent%",
            votes = votes?.toLong(),
            verdict = percentVerdict(percent),
            average = String.format(Locale.US, "%.1f/10", score),
            url = tmdbUrl(item),
        )
    }

    private fun tmdbUrl(item: MediaItem): String? {
        val raw = item.id.trim()
        val id = raw.substringAfterLast(':').takeIf { it.all { c -> c.isDigit() } } ?: return null
        val kind = if (item.type == MediaType.SERIES) "tv" else "movie"
        return "https://www.themoviedb.org/$kind/$id"
    }

    private fun cacheKey(item: MediaItem): String =
        TmdbMeta.normalizeTitle(item.title) + "|" + (item.year ?: 0) + "|" + item.type.name

    // ------------------------------------------------------ IMDb (via id) --

    /**
     * OMDb: the IMDb rating and its vote count as IMDb itself reports them
     * (one decimal, e.g. "7.0"), which is what the badge is supposed to look
     * like. It is tried before Wikidata because Wikidata's `P444` is
     * user-maintained and frequently missing (or years stale) for new releases.
     *
     * The `trilogy` key is OMDb's long-standing public demo key. It is
     * rate-limited, which is exactly why this source is allowed to fail
     * silently and is backed by two others.
     */
    private fun omdb(imdb: String): List<TitleRating> {
        val body = Http.getStringQuiet("https://www.omdbapi.com/?apikey=trilogy&r=json&i=$imdb")
            ?: return emptyList()
        val obj = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        if (!obj.optString("Response").equals("True", ignoreCase = true)) return emptyList()
        val value = imdbValue(obj.optString("imdbRating")) ?: return emptyList()
        val votes = obj.optString("imdbVotes").filter { it.isDigit() }.toLongOrNull()
        return listOf(
            TitleRating(
                source = RatingSource.IMDB,
                value = value,
                votes = votes,
                verdict = score10Verdict(value.toDoubleOrNull()),
                url = "https://www.imdb.com/title/$imdb/",
            )
        )
    }

    /**
     * Review scores from Wikidata, keyed by the IMDb id. The label service
     * resolves each score's publisher to an English name ("IMDb", "Rotten
     * Tomatoes", "Metacritic", "Letterboxd"), which is matched loosely — a
     * spelling change on Wikidata must not lose the numbers.
     */
    private fun wikidata(imdb: String): List<TitleRating> {
        val query = "SELECT ?rating ?byLabel WHERE {" +
            " ?item wdt:P345 " + JSONObject.quote(imdb) + " ." +
            " ?item p:P444 ?st . ?st ps:P444 ?rating . ?st pq:P447 ?by ." +
            " SERVICE wikibase:label { bd:serviceParam wikibase:language \"en\". } }"
        val url = "https://query.wikidata.org/sparql?format=json&query=" +
            URLEncoder.encode(query, "UTF-8")
        val body = Http.getStringQuiet(url, mapOf("Accept" to "application/sparql-results+json"))
            ?: return emptyList()
        val bindings = runCatching {
            JSONObject(body).getJSONObject("results").getJSONArray("bindings")
        }.getOrNull() ?: return emptyList()

        val out = ArrayList<TitleRating>(4)
        for (i in 0 until bindings.length()) {
            val b = bindings.optJSONObject(i) ?: continue
            val rating = b.optJSONObject("rating")?.optString("value")?.trim().orEmpty()
            val by = b.optJSONObject("byLabel")?.optString("value")?.trim()?.lowercase().orEmpty()
            if (rating.isBlank()) continue
            when {
                by.contains("imdb") -> imdbValue(rating)?.let {
                    out.add(
                        TitleRating(
                            source = RatingSource.IMDB,
                            value = it,
                            verdict = score10Verdict(it.toDoubleOrNull()),
                            url = "https://www.imdb.com/title/$imdb/",
                        )
                    )
                }
                by.contains("rotten") && rating.endsWith("%") -> {
                    val p = rating.trim().removeSuffix("%").toIntOrNull()
                    out.add(
                        TitleRating(
                            source = RatingSource.TOMATOMETER,
                            value = "$p%",
                            verdict = p?.let { tomatoVerdict(it, false) },
                            url = "https://www.rottentomatoes.com/",
                        )
                    )
                }
                by.contains("metacritic") -> {
                    val v = outOf(rating, "/100").substringBefore(".").trim()
                    out.add(
                        TitleRating(
                            source = RatingSource.METACRITIC,
                            value = v,
                            verdict = v.toIntOrNull()?.let { metacriticVerdict(it) },
                            url = "https://www.metacritic.com/",
                        )
                    )
                }
                by.contains("letterboxd") -> {
                    val v = outOf(rating, "/5")
                    out.add(
                        TitleRating(
                            source = RatingSource.LETTERBOXD,
                            value = v,
                            verdict = v.toDoubleOrNull()?.let { score5Verdict(it) },
                            url = "https://letterboxd.com/imdb/$imdb/",
                        )
                    )
                }
            }
        }
        return out.filter { it.value.isNotBlank() }
    }

    /**
     * Cinemeta — the meta addon Stremio runs — as the last IMDb word. It has no
     * key and answers anything with a `tt` id, but it mirrors IMDb lazily, so an
     * empty `imdbRating` for a new release is normal and means "no badge here".
     */
    private fun cinemeta(imdb: String, isSeries: Boolean): List<TitleRating> {
        val kind = if (isSeries) "series" else "movie"
        val body = Http.getStringQuiet("https://v3-cinemeta.strem.io/meta/$kind/$imdb.json")
            ?: return emptyList()
        val meta = runCatching { JSONObject(body).optJSONObject("meta") }.getOrNull() ?: return emptyList()
        val value = imdbValue(meta.optString("imdbRating")) ?: return emptyList()
        return listOf(
            TitleRating(
                source = RatingSource.IMDB,
                value = value,
                verdict = score10Verdict(value.toDoubleOrNull()),
                url = "https://www.imdb.com/title/$imdb/",
            )
        )
    }

    /**
     * IMDb's own keyless suggestion endpoint, used only when nothing else knows
     * the title's `tt` id. It returns a compact list of matches with their type
     * and year, so the same verification the scraped pages get is applied here
     * (a name score, and the year within a year) before an id is trusted.
     */
    private fun imdbSuggestionId(item: MediaItem): String? {
        val q = URLEncoder.encode(
            TmdbMeta.queryVariants(item.title).firstOrNull() ?: item.title, "UTF-8"
        ).replace("+", "%20")
        val body = Http.getStringQuiet("https://v2.sg.media-imdb.com/suggestion/x/$q.json")
            ?: return null
        val arr = runCatching { JSONObject(body).optJSONArray("d") }.getOrNull() ?: return null
        var best: String? = null
        var bestScore = -1
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").trim()
            if (!isImdbId(id)) continue
            val wantSeries = item.type == MediaType.SERIES
            val q2 = o.optString("q").lowercase()
            if (q2.isNotBlank()) {
                val isSeries = q2.contains("series") || q2.contains("episode")
                if (isSeries != wantSeries) continue
            }
            var score = TmdbMeta.titleScore(item.title, o.optString("l"))
            val year = o.opt("y") as? Number
            if (year != null && item.year != null) {
                val diff = abs(year.toInt() - item.year)
                if (diff > 1) continue
                if (diff == 0) score += 20
            }
            if (score > bestScore) {
                bestScore = score
                best = id
            }
        }
        return if (bestScore >= 25) best else null
    }

    /** "8.7/10" or "8" → "8.7"/"8.0"; a score that isn't a positive number is
     *  no score. Always one decimal, so IMDb never reads "8" next to "7.0". */
    private fun imdbValue(raw: String): String? {
        val d = raw.trim().substringBefore("/").trim().toDoubleOrNull() ?: return null
        if (d <= 0.0 || d > 10.0) return null
        return String.format(Locale.US, "%.1f", d)
    }

    /** "8.7/10" → "8.7"; a bare number passes through. */
    private fun outOf(raw: String, suffix: String): String {
        val v = raw.substringBefore("/").trim().removeSuffix(suffix)
        return v.trim()
    }

    // ------------------------------------------------------ Rotten Tomatoes --

    /**
     * Tomatometer + popcornmeter from the film/TV page. The slug is a guess —
     * RT has no keyless search endpoint a plain client may use (its
     * `napi/search` answers 403) — so the page is verified against the title
     * and year before its numbers are trusted, and a wrong guess is simply no
     * badge rather than another film's score.
     */
    private fun rtScores(item: MediaItem, isSeries: Boolean): List<TitleRating> {
        val kind = if (isSeries) "tv" else "m"
        for (candidate in slugCandidates(item, "_")) {
            val pageUrl = "https://www.rottentomatoes.com/$kind/$candidate"
            val html = Http.getStringQuiet(pageUrl) ?: continue
            if (!matches(html, item)) continue
            val out = ArrayList<TitleRating>(2)
            criticBlock(html)?.let { b ->
                val score = b.optString("score").trim().toIntOrNull()
                if (score != null) {
                    out.add(
                        TitleRating(
                            source = RatingSource.TOMATOMETER,
                            value = "$score%",
                            votes = b.opt("ratingCount") as? Long
                                ?: (b.opt("ratingCount") as? Number)?.toLong(),
                            verdict = tomatoVerdict(score, b.optBoolean("certified")),
                            average = b.optString("averageRating").trim()
                                .takeIf { it.isNotBlank() }?.let { "$it/5" },
                            url = pageUrl,
                        )
                    )
                }
            }
            audienceBlock(html)?.let { b ->
                val score = b.optString("score").trim().toIntOrNull()
                if (score != null) {
                    out.add(
                        TitleRating(
                            source = RatingSource.POPCORN,
                            value = "$score%",
                            votes = b.opt("reviewCount") as? Long
                                ?: (b.opt("reviewCount") as? Number)?.toLong(),
                            verdict = if (score >= 60) RatingVerdict.LIKED else RatingVerdict.DISLIKED,
                            average = b.optString("averageRating").trim()
                                .takeIf { it.isNotBlank() }?.let { "$it/5" },
                            url = pageUrl,
                        )
                    )
                }
            }
            return out
        }
        return emptyList()
    }

    /** The `"criticsScore": { ... }` object of RT's scorecard JSON. Braces are
     *  balanced-scanned rather than matched with a non-greedy regex, because a
     *  nested object (the sentiments/badges block) sits inside it on some
     *  pages and a `[^}]*` match would silently stop short. */
    private fun criticBlock(html: String): JSONObject? = scoreBlock(html, "criticsScore")

    private fun audienceBlock(html: String): JSONObject? = scoreBlock(html, "audienceScore")

    private fun scoreBlock(html: String, key: String): JSONObject? {
        val at = html.indexOf("\"$key\"")
        if (at < 0) return null
        val open = html.indexOf('{', at)
        if (open < 0) return null
        var depth = 0
        var i = open
        var inString = false
        var escaped = false
        while (i < html.length) {
            val c = html[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> depth++
                !inString && c == '}' -> {
                    depth--
                    if (depth == 0) {
                        val body = html.substring(open, i + 1)
                        return runCatching { JSONObject(body) }.getOrNull()
                    }
                }
            }
            i++
        }
        return null
    }

    // ----------------------------------------------------------- Metacritic --

    /** The Metascore from the critic-score block of the page's JSON-LD. */
    private fun metacriticScore(item: MediaItem, isSeries: Boolean): List<TitleRating> {
        val kind = if (isSeries) "tv" else "movie"
        for (candidate in slugCandidates(item, "-")) {
            val pageUrl = "https://www.metacritic.com/$kind/$candidate/"
            val html = Http.getStringQuiet(pageUrl) ?: continue
            if (!matches(html, item)) continue
            val ld = jsonLd(html) ?: return emptyList()
            val agg = ld.optJSONObject("aggregateRating") ?: return emptyList()
            val name = agg.optString("name").lowercase()
            // A "User Score" block is not what this badge means; the badge is
            // the Metascore (the critics' number).
            if (name.isNotBlank() && !name.contains("metascore") && !name.contains("meta")) return emptyList()
            val value = when (val raw = agg.opt("ratingValue")) {
                is Number -> raw.toInt().toString()
                is String -> raw.trim().substringBefore(".").takeIf { it.isNotBlank() }
                else -> null
            } ?: return emptyList()
            val votes = (agg.opt("ratingCount") as? Number)?.toLong()
                ?: agg.optString("ratingCount").filter { it.isDigit() }.toLongOrNull()
            return listOf(
                TitleRating(
                    source = RatingSource.METACRITIC,
                    value = value,
                    votes = votes,
                    verdict = value.toIntOrNull()?.let { metacriticVerdict(it) },
                    url = pageUrl,
                )
            )
        }
        return emptyList()
    }

    // ----------------------------------------------------------- Letterboxd --

    /** The community average (out of 5) from the IMDb-id redirect page. */
    private fun letterboxd(imdb: String): List<TitleRating> {
        val pageUrl = "https://letterboxd.com/imdb/$imdb/"
        val html = Http.getStringQuiet(pageUrl) ?: return emptyList()
        val value = Regex("name=\"twitter:data2\"\\s+content=\"([0-9.]+)\\s+out of 5\"")
            .find(html)?.groupValues?.get(1)
            ?: Regex("content=\"([0-9.]+)\\s+out of 5\"\\s*/>\\s*<meta name=\"twitter:image")
                .find(html)?.groupValues?.get(1)
            ?: return emptyList()
        val votes = Regex("(?i)based on ([0-9,]+) ratings").find(html)
            ?.groupValues?.get(1)?.filter { it.isDigit() }?.toLongOrNull()
        return listOf(
            TitleRating(
                source = RatingSource.LETTERBOXD,
                value = value,
                votes = votes,
                verdict = value.toDoubleOrNull()?.let { score5Verdict(it) },
                average = "$value/5",
                url = pageUrl,
            )
        )
    }

    // -------------------------------------------------------------- Bands --
    // The bands the sites themselves publish, so a 33% tomatometer reads
    // "Rotten" and a 96 Metascore reads "Acclaim" without the UI guessing.

    private fun tomatoVerdict(score: Int, certified: Boolean): RatingVerdict = when {
        score >= 75 && certified -> RatingVerdict.CERTIFIED_FRESH
        score >= 60 -> RatingVerdict.FRESH
        else -> RatingVerdict.ROTTEN
    }

    private fun metacriticVerdict(score: Int): RatingVerdict = when {
        score >= 81 -> RatingVerdict.ACCLAIM
        score >= 61 -> RatingVerdict.FAVORABLE
        score >= 40 -> RatingVerdict.MIXED
        score >= 20 -> RatingVerdict.UNFAVORABLE
        else -> RatingVerdict.DISASTER
    }

    private fun percentVerdict(score: Int): RatingVerdict = when {
        score >= 80 -> RatingVerdict.ACCLAIM
        score >= 70 -> RatingVerdict.FAVORABLE
        score >= 50 -> RatingVerdict.MIXED
        else -> RatingVerdict.UNFAVORABLE
    }

    private fun score10Verdict(score: Double?): RatingVerdict? {
        val s = score ?: return null
        return when {
            s >= 8.0 -> RatingVerdict.ACCLAIM
            s >= 7.0 -> RatingVerdict.FAVORABLE
            s >= 5.0 -> RatingVerdict.MIXED
            s >= 3.5 -> RatingVerdict.UNFAVORABLE
            else -> RatingVerdict.DISASTER
        }
    }

    private fun score5Verdict(score: Double): RatingVerdict = when {
        score >= 4.0 -> RatingVerdict.ACCLAIM
        score >= 3.5 -> RatingVerdict.FAVORABLE
        score >= 2.5 -> RatingVerdict.MIXED
        score >= 1.5 -> RatingVerdict.UNFAVORABLE
        else -> RatingVerdict.DISASTER
    }

    // -------------------------------------------------------------- Shared --

    /** The one JSON-LD object a film/tv page carries (name, year, scores). */
    private fun jsonLd(html: String): JSONObject? {
        val block = Regex(
            "<script type=\"application/ld\\+json\"[^>]*>([\\s\\S]*?)</script>"
        ).find(html)?.groupValues?.get(1) ?: return null
        val cleaned = block.replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
        return runCatching { JSONObject(cleaned) }.getOrNull()
    }

    /**
     * True when a fetched page really is [item]: its JSON-LD title names the
     * same film/show, and — when we know it — the year agrees. Without this a
     * guessed slug such as `/m/crash` would silently show the 1996 film's score
     * on the 2004 one.
     */
    private fun matches(html: String, item: MediaItem): Boolean {
        val ld = jsonLd(html) ?: return false
        val name = ld.optString("name").trim()
        if (name.isBlank()) return false
        if (TmdbMeta.titleScore(item.title, name.replace(Regex("\\(\\d{4}\\)"), "").trim()) < 25) return false
        val year = item.year ?: return true
        val created = ld.optString("dateCreated").trim()
        val pageYear = created.take(4).toIntOrNull() ?: return true
        return abs(pageYear - year) <= 1
    }

    /**
     * Slug guesses for a page URL, best first: the plain title, then the
     * title with the year appended (the form both sites use when two titles
     * share a name).
     */
    private fun slugCandidates(item: MediaItem, sep: String): List<String> {
        val slug = slugify(item.title, sep)
        if (slug.isBlank()) return emptyList()
        val year = item.year
        return if (year != null && year > 1900) listOf(slug, "$slug$sep$year") else listOf(slug)
    }

    private fun slugify(title: String, sep: String): String {
        val plain = runCatching {
            Normalizer.normalize(title, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        }.getOrDefault(title)
        val ch = sep.first()
        return plain.lowercase()
            .replace("&", " and ")
            .replace(Regex("[^a-z0-9]+"), sep)
            .trim { it == ch }
    }

    private fun isImdbId(id: String): Boolean =
        id.length >= 8 && id.startsWith("tt") && id.drop(2).all { it.isDigit() }

    // --------------------------------------------------------------- Cache --

    private fun readDisk(key: String): List<TitleRating>? {
        val entry = runCatching { load().optJSONObject(key) }.getOrNull() ?: return null
        // When each source was last asked, restored alongside the badges so the
        // re-ask schedule survives a restart. An entry written by an older build
        // has no "t" block: every source then reads as "asked a long time ago",
        // which gives each already-cached title exactly one self-heal pass after
        // the update and then settles onto the normal windows.
        entry.optJSONObject("t")?.let { times ->
            val row = attempts.getOrPut(key) { ConcurrentHashMap() }
            for (i in 0 until times.length()) {
                val name = times.names()?.optString(i) ?: continue
                val src = runCatching { RatingSource.valueOf(name) }.getOrNull() ?: continue
                row[src] = times.optLong(name)
            }
        }
        val arr = entry.optJSONArray("r") ?: return null
        val ttl = if (arr.length() == 0) EMPTY_TTL_MS else CACHE_TTL_MS
        if (System.currentTimeMillis() - entry.optLong("at") > ttl) return null
        val out = ArrayList<TitleRating>(arr.length())
        for (i in 0 until arr.length()) {
            val row = arr.optJSONArray(i) ?: continue
            val source = runCatching { RatingSource.valueOf(row.optString(0)) }.getOrNull() ?: continue
            val value = row.optString(1)
            if (value.isBlank()) continue
            // Entries written by older builds are [source, value] only; every
            // extra field is read positionally and defaults when absent.
            out.add(
                TitleRating(
                    source = source,
                    value = value,
                    votes = if (row.length() > 2) row.optLong(2).takeIf { it > 0 } else null,
                    verdict = if (row.length() > 3) {
                        runCatching { RatingVerdict.valueOf(row.optString(3)) }.getOrNull()
                    } else null,
                    average = if (row.length() > 4) row.optString(4).takeIf { it.isNotBlank() } else null,
                    url = if (row.length() > 5) row.optString(5).takeIf { it.isNotBlank() } else null,
                )
            )
        }
        return out
    }

    private fun writeDisk(key: String, list: List<TitleRating>) {
        synchronized(lock) {
            val root = load()
            val entry = JSONObject()
            entry.put("at", System.currentTimeMillis())
            val arr = JSONArray()
            for (r in list) {
                arr.put(
                    JSONArray()
                        .put(r.source.name)
                        .put(r.value)
                        .put(r.votes ?: JSONObject.NULL)
                        .put(r.verdict?.name ?: JSONObject.NULL)
                        .put(r.average ?: JSONObject.NULL)
                        .put(r.url ?: JSONObject.NULL)
                )
            }
            entry.put("r", arr)
            attempts[key]?.let { times ->
                val obj = JSONObject()
                for ((src, at) in times) obj.put(src.name, at)
                entry.put("t", obj)
            }
            root.put(key, entry)
            // Bound the file: a title that was looked up days ago is worth less
            // than the write, and this is a cache, not a database.
            if (root.length() > 400) {
                val keys = root.keys().asSequence().toList()
                    .sortedBy { root.optJSONObject(it)?.optLong("at") ?: 0L }
                for (k in keys.take(root.length() - 300)) root.remove(k)
            }
            runCatching { cacheFile.parentFile?.mkdirs(); cacheFile.writeText(root.toString()) }
        }
    }

    /** The whole cache as JSON; an empty object when there is no file yet (so
     *  the first write has something to write into). */
    private fun load(): JSONObject {
        synchronized(lock) {
            disk?.let { return it }
            val text = runCatching { cacheFile.readText() }.getOrNull()
            val parsed = text?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
            disk = parsed
            return parsed
        }
    }
}
