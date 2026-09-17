package com.hikari.app.data

import com.hikari.app.HikariApp
import com.hikari.app.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
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
 *     resolves one from the title and year.
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

    private val memory = ConcurrentHashMap<String, List<TitleRating>>()
    private val lock = Any()
    private var disk: JSONObject? = null

    private val cacheFile: File get() = File(HikariApp.instance.filesDir, "ratings-cache.json")

    /**
     * Every badge known for [item]. [imdbId] comes from TMDB's `external_ids`
     * when it has one; [tmdbScore]/[tmdbVotes] are the values that lookup
     * already returned, so the TMDB badge costs no request.
     */
    suspend fun load(
        item: MediaItem,
        imdbId: String?,
        tmdbScore: Double?,
        tmdbVotes: Int?,
    ): List<TitleRating> = withContext(Dispatchers.IO) {
        val tmdb = tmdbBadge(tmdbScore, tmdbVotes, item)
        val key = cacheKey(item)
        memory[key]?.let { return@withContext finish(it, tmdb) }
        readDisk(key)?.let {
            memory[key] = it
            return@withContext finish(it, tmdb)
        }

        var imdb = imdbId?.trim()?.takeIf { isImdbId(it) }
            ?: item.id.trim().takeIf { isImdbId(it) }
        // No id from TMDB: ask IMDb itself for one. This is what makes the IMDb
        // badge appear on titles TMDB has no `external_ids` for.
        if (imdb == null) imdb = imdbSuggestionId(item)
        val isSeries = item.type == MediaType.SERIES

        val found = LinkedHashMap<RatingSource, TitleRating>()
        // Scraped values are put in FIRST so a fresher tomatometer/Metascore
        // wins over Wikidata's (which is user-maintained and can lag by years);
        // the jobs are awaited in the order below, and the first source to
        // claim a slot keeps it.
        coroutineScope {
            val jobs = listOf(
                async { guarded { rtScores(item, isSeries) } },
                async { guarded { metacriticScore(item, isSeries) } },
                async { if (imdb != null && !isSeries) guarded { letterboxd(imdb) } else emptyList() },
                async { if (imdb != null) guarded { omdb(imdb) } else emptyList() },
                async { if (imdb != null) guarded { wikidata(imdb) } else emptyList() },
                async { if (imdb != null) guarded { cinemeta(imdb, isSeries) } else emptyList() },
            )
            for (job in jobs) {
                for (r in job.await()) {
                    if (r.value.isNotBlank() && !found.containsKey(r.source)) {
                        found[r.source] = r
                    }
                }
            }
        }
        val list = found.values.sortedBy { it.source.ordinal }
        memory[key] = list
        writeDisk(key, list)
        finish(list, tmdb)
    }

    /** Never let one source's failure cancel the whole strip. */
    private suspend fun guarded(block: suspend () -> List<TitleRating>): List<TitleRating> =
        runCatching { block() }.getOrDefault(emptyList())

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
