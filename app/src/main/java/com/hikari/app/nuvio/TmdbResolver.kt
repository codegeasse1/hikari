package com.hikari.app.nuvio

import com.hikari.app.HikariApp
import com.hikari.app.data.MediaItem
import com.hikari.app.data.NsfwGate
import com.hikari.app.data.MediaType
import com.hikari.app.data.TmdbMeta
import com.hikari.app.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Converts whatever identifier Hikari has for a title into the (tmdbId,
 * mediaType) pair that Nuvio providers consume. Strategy, in order:
 *  1. the id is already a numeric TMDB id → use it (probing movie/tv when the
 *     type is unknown);
 *  2. the id is an IMDb id (`tt…`) → TMDB /find with external_source=imdb_id;
 *  3. otherwise search TMDB by title + year.
 *
 * Public TMDB API keys — nuvio providers embed their own keys, these are used
 * only for Hikari's own resolution lookups and are the same keys those
 * providers ship in their (public) source.
 */
object TmdbResolver {

    data class Resolved(val tmdbId: String, val mediaType: String) // "movie" | "tv"

    private val API_KEYS = listOf(
        "68e094699525b18a70bab2f86b1fa706",
        "439c478a771f35c05022f9feabcca01c",
    )
    private const val API_BASE = "https://api.themoviedb.org/3"

    /**
     * The language TMDB CONTENT requests are answered in ("es-ES", "ja-JP" …).
     * Blank = TMDB's own default (English). Kept here rather than read from the
     * preference store on every call, because a fan-out source search makes
     * dozens of TMDB requests at once; it is mirrored from Settings at launch
     * and whenever the choice changes. Search/lookup endpoints deliberately
     * ignore it — a title search must match the ORIGINAL name too, and asking
     * TMDB to translate the query as well only narrows what it can find.
     */
    @Volatile
    var contentLanguage: String = ""

    private fun isLookupPath(path: String): Boolean =
        path.startsWith("/search") || path.startsWith("/find") ||
            path.contains("alternative_titles") || path.contains("external_ids")

    private val cacheFile get() = File(HikariApp.instance.filesDir, "nuvio/tmdb-cache.json")

    private val memory = ConcurrentHashMap<String, Resolved>()

    /** Single-flight guard: several Nuvio providers resolve the SAME item at
     *  once when a source search fans out, and without this each one fired its
     *  own TMDB lookup — duplicate network round-trips (and TMDB rate-limit
     *  pressure) that delayed the first server by seconds. Now the first
     *  caller does the lookup and the rest await it. */
    private val inflight = ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<Resolved?>>()

    /** Cheap pre-filter: can we plausibly resolve this item to a TMDB id? */
    fun isLikelyResolvable(item: MediaItem): Boolean {
        val id = item.id.trim()
        if (id.isNotEmpty() && id.all { it.isDigit() }) return true
        if (id.lowercase().startsWith("tt") && id.length >= 8) return true
        return item.title.isNotBlank()
    }

    suspend fun resolve(item: MediaItem): Resolved? {
        val key = cacheKey(item)
        memory[key]?.let { return it }
        loadCache()[key]?.let {
            memory[key] = it
            return it
        }
        // Single-flight: a fan-out source search resolves the same title from
        // every Nuvio provider at once — only the first caller pays for the
        // TMDB round-trip; the others await the same result.
        val deferred = kotlinx.coroutines.CompletableDeferred<Resolved?>()
        val prev = inflight.putIfAbsent(key, deferred)
        if (prev != null) return prev.await()
        try {
            val r = resolveNetwork(item)
            if (r != null) {
                memory[key] = r
                saveCache(key, r)
            }
            deferred.complete(r)
            return r
        } catch (t: Throwable) {
            deferred.complete(null)
            throw t
        } finally {
            inflight.remove(key)
        }
    }

    private fun cacheKey(item: MediaItem): String {
        val id = item.id.trim()
        return when {
            id.isNotEmpty() && id.all { it.isDigit() } -> "id|$id|${typeHint(item)}"
            id.lowercase().startsWith("tt") -> "imdb|$id|${typeHint(item)}"
            // The ORIGINAL name is the one TMDB indexes; a display name
            // localized by the app's TMDB language would search for the wrong
            // thing (see [MediaItem.originalTitle]).
            else -> "search|${item.searchTitle.lowercase()}|${item.year ?: 0}|${typeHint(item)}"
        }
    }

    private fun typeHint(item: MediaItem): String = when (item.type) {
        MediaType.MOVIE -> "movie"
        MediaType.SERIES -> if (item.rawType.contains("anime", true)) "anime" else "tv"
        MediaType.UNKNOWN -> "unknown"
    }

    private suspend fun resolveNetwork(item: MediaItem): Resolved? {
        val id = item.id.trim()
        if (id.isNotEmpty() && id.all { it.isDigit() }) {
            return resolveNumericId(id, item)
        }
        if (id.lowercase().startsWith("tt") && id.length >= 8) {
            return resolveImdb(id, item)
        }
        return searchByTitle(item)
    }

    private suspend fun resolveNumericId(id: String, item: MediaItem): Resolved? {
        return when (item.type) {
            MediaType.MOVIE -> Resolved(id, "movie")
            MediaType.SERIES -> Resolved(id, if (item.rawType.contains("anime", true)) "anime" else "tv")
            else -> {
                // Unknown type: probe both namespaces (first that exists).
                if (apiGet("/tv/$id", emptyMap())?.has("id") == true) Resolved(id, "tv")
                else if (apiGet("/movie/$id", emptyMap())?.has("id") == true) Resolved(id, "movie")
                else null
            }
        }
    }

    private suspend fun resolveImdb(id: String, item: MediaItem): Resolved? {
        val data = apiGet("/find/$id", mapOf("external_source" to "imdb_id")) ?: return null
        val movies = data.optJSONArray("movie_results")
        val tvs = data.optJSONArray("tv_results")
        fun first(arr: JSONArray?): String? =
            if (arr != null && arr.length() > 0) arr.optJSONObject(0)?.optString("id") else null
        return when (item.type) {
            MediaType.MOVIE -> first(movies)?.let { Resolved(it, "movie") }
            MediaType.SERIES -> first(tvs)?.let { Resolved(it, if (item.rawType.contains("anime", true)) "anime" else "tv") }
            else -> first(tvs)?.let { Resolved(it, "tv") }
                ?: first(movies)?.let { Resolved(it, "movie") }
        }
    }

    /**
     * Resolves an extension/scraper item by name. Site metas decorate titles in
     * ways TMDB's search index does not match — "Sword of Coming Season 2" and
     * "Battle Through The Heavens: Origin" both return ZERO results for the
     * literal query — and a franchise's English name on a site is often a
     * different translation than TMDB's ("Battle Through The Heavens" is
     * "Fights Break Sphere" there). Either case used to leave the item
     * unresolved, which silently removed the Cast, Trailers, Details,
     * Related and Similar sections on the whole detail page.
     *
     * So: search every progressively stripped form of the title
     * ([TmdbMeta.queryVariants]) and score all candidates together, and when
     * nothing matches by name, look through the alternate titles of the top
     * results before giving up. Only names are used to accept a match — the
     * year merely breaks ties — because a wrong-but-close year must never pick
     * a different show.
     */
    private suspend fun searchByTitle(item: MediaItem): Resolved? {
        // The original name first: a title the app renamed for display (TMDB
        // language) still has to be looked up by the name TMDB indexes it under.
        val title = item.searchTitle
        if (title.isBlank()) return null
        val variants = TmdbMeta.queryVariants(title)
        if (variants.isEmpty()) return null
        val kinds = when (item.type) {
            MediaType.MOVIE -> listOf("movie")
            MediaType.UNKNOWN -> listOf("movie", "tv")
            else -> listOf("tv")
        }
        val year = item.year
        val seen = HashSet<String>()
        val found = ArrayList<Pair<String, String>>() // id to kind, in rank order
        var best: Resolved? = null
        var bestScore = 0
        for (kind in kinds) {
            for (v in variants) {
                val data = apiGet("/search/$kind", mapOf("query" to v)) ?: continue
                val arr = data.optJSONArray("results") ?: continue
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id").trim()
                    if (id.isBlank() || id == "null") continue
                    if (seen.add(id)) found.add(id to kind)
                    val score = candidateScore(o, variants, year)
                    if (score > bestScore) {
                        bestScore = score
                        best = Resolved(id, kind)
                    }
                }
                // An exact/prefix name hit is decided — no need to ask TMDB
                // about the looser forms of the same title.
                if (bestScore >= 40) return best
            }
        }
        if (best != null && bestScore > 0) return best
        return alternativeMatch(found, variants)
            ?: best
            // Still nothing: the title is known to IMDb and to nobody's search
            // index in the form the site printed it ("… Episode 172 English
            // Subtitles", a fan-translated name, a romanisation TMDB spells
            // differently). IMDb's suggestion endpoint needs no key and answers
            // with the tt-id, which TMDB can then be asked about directly — a
            // second chance that costs two requests and only runs when every
            // name search has already come back empty.
            ?: searchViaImdb(item)
    }

    /**
     * Last-resort resolution through IMDb's suggestion endpoint: title → tt-id
     * → TMDB `/find`. The same endpoint [TmdbMeta] uses for artwork, and the
     * only lookup available here that does not depend on TMDB's search index
     * agreeing with the site's spelling of a name.
     */
    private suspend fun searchViaImdb(item: MediaItem): Resolved? {
        val title = item.searchTitle.trim()
        if (title.isBlank()) return null
        val q = runCatching {
            java.net.URLEncoder.encode(title.lowercase(), "UTF-8")
        }.getOrNull() ?: return null
        val text = Http.getString(
            "https://v3.sg.media-imdb.com/suggestion/h/$q.json",
            mapOf("Accept" to "application/json"),
        ) ?: return null
        val arr = runCatching { JSONObject(text).optJSONArray("d") }.getOrNull() ?: return null
        val wanted = TmdbMeta.normalizeTitle(title)
        if (wanted.isBlank()) return null
        var bestId: String? = null
        var bestScore = 0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").trim()
            if (!id.startsWith("tt") || id.length < 8) continue
            val label = TmdbMeta.normalizeTitle(o.optString("l"))
            if (label.isBlank()) continue
            var score = when {
                label == wanted -> 50
                wanted.length >= 5 && (label.startsWith(wanted) || wanted.startsWith(label)) -> 30
                else -> 0
            }
            if (score == 0) continue
            if (item.year != null && o.optString("y") == item.year.toString()) score += 25
            if (score > bestScore) {
                bestScore = score
                bestId = id
            }
        }
        val tt = bestId ?: return null
        return runCatching { resolveImdb(tt, item) }.getOrNull()
    }

    /** Name-match score for one search result against every title variant. */
    private fun candidateScore(o: JSONObject, variants: List<String>, year: Int?): Int {
        val names = listOf(
            o.optString("title"), o.optString("name"),
            o.optString("original_title"), o.optString("original_name"),
        ).filter { it.isNotBlank() && it != "null" }
        if (names.isEmpty()) return 0
        var score = 0
        for (v in variants) {
            for (n in names) score = maxOf(score, TmdbMeta.titleScore(v, n))
        }
        if (score == 0) return 0
        if (year != null && year > 0) {
            val raw = o.optString("release_date").ifBlank { o.optString("first_air_date") }
            val y = raw.take(4).toIntOrNull()
            if (y == year) score += 25 else if (y != null && Math.abs(y - year) <= 1) score += 8
        }
        return score
    }

    /**
     * Last resort for a title TMDB indexes under a different translation: check
     * the alternate titles of the first few search results (TMDB keeps
     * "Battle Through the Heavens" as an alias of "Fights Break Sphere"). Only
     * the top results are checked, so a miss costs a couple of requests.
     */
    private suspend fun alternativeMatch(
        found: List<Pair<String, String>>,
        variants: List<String>,
    ): Resolved? {
        for ((id, kind) in found.take(4)) {
            val d = apiGet("/$kind/$id/alternative_titles", emptyMap()) ?: continue
            val arr = d.optJSONArray("results") ?: continue
            for (i in 0 until arr.length()) {
                val alt = arr.optJSONObject(i)?.optString("title")?.trim().orEmpty()
                if (alt.isBlank() || alt == "null") continue
                if (variants.any { TmdbMeta.titleScore(it, alt) >= 40 }) return Resolved(id, kind)
            }
        }
        return null
    }

    /** GETs a TMDB endpoint, rotating the API key on auth/rate errors.
     *
     *  ALWAYS hops to [Dispatchers.IO] first: OkHttp's `execute()` is blocking,
     *  and every caller of this used to inherit ITS thread — the detail screen's
     *  background shelf/extras lookup ran on `viewModelScope` (the main thread),
     *  so the call was killed by NetworkOnMainThreadException and swallowed by
     *  its `runCatching`, leaving the Cast/Trailers/Details/Related/Similar
     *  sections silently missing on every title. Suspending on IO here makes
     *  every present and future caller safe by construction. */
    suspend fun apiGet(
        path: String,
        query: Map<String, String>,
        language: String? = null,
    ): JSONObject? =
        withContext(Dispatchers.IO) {
            val lang = language ?: if (contentLanguage.isNotBlank() && !isLookupPath(path)) {
                contentLanguage
            } else {
                ""
            }
            for (key in API_KEYS) {
                val params = LinkedHashMap<String, String>(query)
                if (lang.isNotBlank()) params["language"] = lang
                // The adult-content switch, enforced AT THE REQUEST for a
                // catalogue query. A catalogue answer carries no certificate of
                // its own — a row is a title, a poster and a year — so there is
                // nothing in it to filter against, and asking TMDB to leave the
                // films out is the only thing that keeps an R-rated title out of
                // every row, grid and Production/Network catalogue at once. This
                // also rewrites the movie LIST endpoints (`/movie/popular`,
                // `/trending/*`…) into the discover query that CAN be told, which
                // is why turning the switch off changes those rows too (see
                // [NsfwGate.restrictRequest]). A no-op for every other endpoint
                // and for every user with the switch on.
                val askPath = NsfwGate.restrictRequest(path, params)
                params["api_key"] = key
                val qs = params.entries.joinToString("&") { (k, v) ->
                    "${java.net.URLEncoder.encode(k, "UTF-8")}=${java.net.URLEncoder.encode(v, "UTF-8")}"
                }
                val url = "$API_BASE$askPath?$qs"
                val text = Http.getString(url, mapOf("Accept" to "application/json")) ?: continue
                val obj = runCatching { JSONObject(text) }.getOrNull() ?: continue
                if (obj.optString("status_message").contains("Invalid API key", true)) continue
                return@withContext obj
            }
            null
        }

    // ---- tiny disk cache (survives restarts; bounded) ----

    private fun loadCache(): Map<String, Resolved> = runCatching {
        val f = cacheFile
        if (!f.exists()) return@runCatching emptyMap()
        val arr = JSONArray(f.readText())
        val out = HashMap<String, Resolved>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val k = o.optString("k")
            val id = o.optString("id")
            val t = o.optString("t")
            if (k.isNotBlank() && id.isNotBlank() && t.isNotBlank()) out[k] = Resolved(id, t)
        }
        out
    }.getOrDefault(emptyMap())

    private fun saveCache(key: String, r: Resolved) {
        runCatching {
            val cur = loadCache().toMutableMap()
            cur[key] = r
            val arr = JSONArray()
            var kept = 0
            for ((k, v) in cur) {
                if (kept >= 300) break
                arr.put(JSONObject().put("k", k).put("id", v.tmdbId).put("t", v.mediaType))
                kept++
            }
            val f = cacheFile
            f.parentFile?.mkdirs()
            f.writeText(arr.toString())
        }
    }
}
