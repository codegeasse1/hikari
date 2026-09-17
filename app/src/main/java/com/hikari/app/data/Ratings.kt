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
import java.util.concurrent.ConcurrentHashMap
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
 *  1. **Wikidata** — one SPARQL query keyed by the IMDb id (`P345`) returns the
 *     item's review scores (`P444`) together with the body that published each
 *     one (`P447`: IMDb, Rotten Tomatoes, Metacritic, Letterboxd). This is what
 *     makes an IMDb number possible at all: imdb.com itself answers a plain
 *     HTTP client with an empty body, and the keyless IMDb mirrors come and go.
 *  2. **Rotten Tomatoes** `/{m|tv}/{slug}` — the page's `media-scorecard-json`
 *     carries the tomatometer AND the popcornmeter, which is two badges from
 *     one request.
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

/** One badge: which site it came from and the number to show. */
data class TitleRating(val source: RatingSource, val value: String)

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
        val tmdb = tmdbBadge(tmdbScore, tmdbVotes)
        val key = cacheKey(item)
        memory[key]?.let { return@withContext finish(it, tmdb) }
        readDisk(key)?.let {
            memory[key] = it
            return@withContext finish(it, tmdb)
        }

        val imdb = imdbId?.trim()?.takeIf { isImdbId(it) }
            ?: item.id.trim().takeIf { isImdbId(it) }
        val isSeries = item.type == MediaType.SERIES

        val found = LinkedHashMap<RatingSource, String>()
        // Scraped values are put in FIRST so a fresher tomatometer/Metascore
        // wins over Wikidata's (which is user-maintained and can lag by years);
        // Wikidata then fills every gap — and is the only source for IMDb.
        coroutineScope {
            val jobs = listOf(
                async { guarded { rtScores(item, isSeries) } },
                async { guarded { metacriticScore(item, isSeries) } },
                async { if (imdb != null && !isSeries) guarded { letterboxd(imdb) } else emptyList() },
                async { if (imdb != null) guarded { wikidata(imdb) } else emptyList() },
            )
            for (job in jobs) {
                for (r in job.await()) {
                    if (r.value.isNotBlank() && !found.containsKey(r.source)) {
                        found[r.source] = r.value
                    }
                }
            }
        }
        val list = found.entries
            .sortedBy { it.key.ordinal }
            .map { TitleRating(it.key, it.value) }
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

    private fun tmdbBadge(score: Double?, votes: Int?): TitleRating? {
        // A brand-new title with a handful of votes has a meaningless average:
        // TMDB's own site hides the score below 10 votes, and so do we.
        if (score == null || score <= 0.0) return null
        if ((votes ?: 0) < 10) return null
        return TitleRating(RatingSource.TMDB, "${(score * 10).roundToInt()}%")
    }

    private fun cacheKey(item: MediaItem): String =
        TmdbMeta.normalizeTitle(item.title) + "|" + (item.year ?: 0) + "|" + item.type.name

    // ------------------------------------------------------------ Wikidata --

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
                by.contains("imdb") -> out.add(TitleRating(RatingSource.IMDB, outOf(rating, "/10")))
                by.contains("rotten") && rating.endsWith("%") ->
                    out.add(TitleRating(RatingSource.TOMATOMETER, rating))
                by.contains("metacritic") -> out.add(TitleRating(RatingSource.METACRITIC, outOf(rating, "/100")))
                by.contains("letterboxd") -> out.add(TitleRating(RatingSource.LETTERBOXD, outOf(rating, "/5")))
            }
        }
        return out.filter { it.value.isNotBlank() }
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
            val html = Http.getStringQuiet("https://www.rottentomatoes.com/$kind/$candidate")
                ?: continue
            if (!matches(html, item)) continue
            val out = ArrayList<TitleRating>(2)
            val critic = Regex("\"criticsScore\"\\s*:\\s*\\{[^}]*?\"score\"\\s*:\\s*\"(\\d+)\"")
                .find(html)?.groupValues?.get(1)
            if (critic != null) out.add(TitleRating(RatingSource.TOMATOMETER, "$critic%"))
            val audience = Regex("\"audienceScore\"\\s*:\\s*\\{[^}]*?\"score\"\\s*:\\s*\"(\\d+)\"")
                .find(html)?.groupValues?.get(1)
            if (audience != null) out.add(TitleRating(RatingSource.POPCORN, "$audience%"))
            return out
        }
        return emptyList()
    }

    // ----------------------------------------------------------- Metacritic --

    /** The Metascore from the critic-score block of the page's JSON-LD. */
    private fun metacriticScore(item: MediaItem, isSeries: Boolean): List<TitleRating> {
        val kind = if (isSeries) "tv" else "movie"
        for (candidate in slugCandidates(item, "-")) {
            val html = Http.getStringQuiet("https://www.metacritic.com/$kind/$candidate/")
                ?: continue
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
            return listOf(TitleRating(RatingSource.METACRITIC, value))
        }
        return emptyList()
    }

    // ----------------------------------------------------------- Letterboxd --

    /** The community average (out of 5) from the IMDb-id redirect page. */
    private fun letterboxd(imdb: String): List<TitleRating> {
        val html = Http.getStringQuiet("https://letterboxd.com/imdb/$imdb/") ?: return emptyList()
        val value = Regex("name=\"twitter:data2\"\\s+content=\"([0-9.]+)\\s+out of 5\"")
            .find(html)?.groupValues?.get(1)
            ?: Regex("content=\"([0-9.]+)\\s+out of 5\"\\s*/>\\s*<meta name=\"twitter:image")
                .find(html)?.groupValues?.get(1)
            ?: return emptyList()
        return listOf(TitleRating(RatingSource.LETTERBOXD, value))
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
        return kotlin.math.abs(pageYear - year) <= 1
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
            val pair = arr.optJSONArray(i) ?: continue
            val source = runCatching { RatingSource.valueOf(pair.optString(0)) }.getOrNull() ?: continue
            val value = pair.optString(1)
            if (value.isNotBlank()) out.add(TitleRating(source, value))
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
                arr.put(JSONArray().put(r.source.name).put(r.value))
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
