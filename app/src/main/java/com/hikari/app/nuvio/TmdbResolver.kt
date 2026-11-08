package com.hikari.app.nuvio

import com.hikari.app.HikariApp
import com.hikari.app.data.MediaItem
import com.hikari.app.data.TrackerAnimeResolver
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

object TmdbResolver {

    data class Resolved(val tmdbId: String, val mediaType: String) // "movie" | "tv"

    private val API_KEYS = listOf(
        "68e094699525b18a70bab2f86b1fa706",
        "439c478a771f35c05022f9feabcca01c",
    )

    /** The rotation head for non-TMDB callers that need ONE key without the
     *  retry loop (see [apiGet]) — the Zangetsu runtime's enrichment calls. */
    fun builtinKey(): String = API_KEYS.firstOrNull().orEmpty()
    private const val API_BASE = "https://api.themoviedb.org/3"

    @Volatile
    var contentLanguage: String = ""

    private fun isLookupPath(path: String): Boolean =
        path.startsWith("/search") || path.startsWith("/find") ||
            path.contains("alternative_titles") || path.contains("external_ids")

    private val cacheFile get() = File(HikariApp.instance.filesDir, "nuvio/tmdb-cache-v4.json")

    private val memory = ConcurrentHashMap<String, Resolved>()

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
        val trackerRow = isTrackerAnime(item)
        if (!trackerRow) {
            loadCache()[key]?.let {
                memory[key] = it
                return it
            }
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
                if (!isTrackerAnime(item)) saveCache(key, r)
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
        tmdbPrefixedId(id)?.let { return "id|$it|${typeHint(item)}" }
        return when {
            id.isNotEmpty() && id.all { it.isDigit() } && isTmdbBacked(item) -> "id|$id|${typeHint(item)}"
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

    private fun isTmdbBacked(item: MediaItem): Boolean {
        if (item.providerId.equals("tmdb", ignoreCase = true)) return true
        if (item.providerId.startsWith("tmdb|")) return true
        if (item.rawType.equals("tmdb", ignoreCase = true)) return true
        return false
    }

    private suspend fun resolveNetwork(item: MediaItem): Resolved? {
        val id = item.id.trim()
        if (isTrackerAnime(item)) {
            runCatching { TrackerAnimeResolver.simklTmdbRef(item) }.getOrNull()?.let { ref ->
                if (ref.tmdbId.isNotBlank()) return Resolved(ref.tmdbId, ref.mediaType)
            }
            // No exact Simkl mapping (new-season anime often has none yet):
            // fall back to an EXACT title search — exact name, matching year
            // and a Japanese-origin signal, or nothing. Anything looser has
            // repeatedly picked an unrelated show. This only ever feeds
            // stream/artwork lookups by id; tracker details and episode lists
            // never go through TMDB.
            return searchByTitleExactAnime(item)
        }
        tmdbPrefixedId(id)?.let { return resolveNumericId(it, item) }
        if (id.isNotEmpty() && id.all { it.isDigit() }) {
            // TMDB-backed rows name their TMDB row directly; an extension's
            // numeric id is its own business (see isTmdbBacked) and goes to
            // the title search below instead of resolving as someone else.
            if (isTmdbBacked(item)) {
                return resolveNumericId(id, item)
            }
            return searchByTitle(item)
        }
        if (id.lowercase().startsWith("tt") && id.length >= 8) {
            return resolveImdb(id, item)
        }
        return searchByTitle(item)
    }

    private suspend fun searchByTitleExactAnime(item: MediaItem): Resolved? {
        val title = item.searchTitle
        if (title.isBlank()) return null
        val variants = TmdbMeta.queryVariants(title).take(4)
        if (variants.isEmpty()) return null
        val sequelRow = TmdbMeta.seasonHint(title) != null
        var best: Resolved? = null
        var bestScore = 0
        for (kind in listOf("tv", "movie")) {
            for (v in variants) {
                val data = apiGet("/search/$kind", mapOf("query" to v)) ?: continue
                val arr = data.optJSONArray("results") ?: continue
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val oid = o.optString("id").trim()
                    if (oid.isBlank() || oid == "null") continue
                    var base = 0
                    for (n in listOf(o.optString("title"), o.optString("name"), o.optString("original_title"), o.optString("original_name"))) {
                        if (n.isBlank() || n == "null") continue
                        for (vv in variants) base = maxOf(base, TmdbMeta.titleScore(vv, n))
                    }
                    if (base < 60) continue
                    val raw = o.optString("release_date").ifBlank { o.optString("first_air_date") }
                    val y = raw.take(4).toIntOrNull()
                    if (!sequelRow && item.year != null && item.year > 0) {
                        if (y == null || kotlin.math.abs(y - item.year) > 1) continue
                    }
                    val lang = o.optString("original_language").lowercase()
                    var ja = lang == "ja"
                    if (!ja) {
                        val genres = o.optJSONArray("genre_ids")
                        if (genres != null) {
                            for (g in 0 until genres.length()) {
                                if (genres.optInt(g) == 16) { ja = true; break }
                            }
                        }
                    }
                    if (!ja) {
                        val originals = listOf(o.optString("original_title"), o.optString("original_name"))
                        ja = variants.any { vv -> originals.any { nn -> TmdbMeta.normalizeTitle(nn) == TmdbMeta.normalizeTitle(vv) && vv.isNotBlank() } }
                    }
                    if (!ja) continue
                    val score = base * 100 + o.optDouble("popularity", 0.0).toInt().coerceAtMost(99)
                    if (score > bestScore) { bestScore = score; best = Resolved(oid, kind) }
                }
                if (bestScore >= 6000) return best
            }
        }
        return best
    }
    /** A tmdb:digits id names its TMDB row directly (what Stremio catalogue
     * rows carry) — resolving it by title search instead was fuzzy work that
     * sometimes picked the wrong show. */
    private fun tmdbPrefixedId(id: String): String? {
        val t = id.trim()
        val digits = when {
            t.startsWith("tmdb:", ignoreCase = true) -> t.substringAfter(':').trim()
            t.startsWith("tmdb-", ignoreCase = true) -> t.substringAfter('-').trim()
            else -> return null
        }
        return digits.takeIf { it.isNotEmpty() && it.all { it.isDigit() } }
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

    private suspend fun searchByTitle(item: MediaItem): Resolved? {
        if (isGenericShortTitle(item)) return null
        // The original name first: a title the app renamed for display (TMDB
        // language) still has to be looked up by the name TMDB indexes it under.
        val title = item.searchTitle
        if (title.isBlank()) return null
        val variants = item.allTitles.flatMap { TmdbMeta.queryVariants(it) }.distinct()
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
                    var score = candidateScore(o, variants, year)
                    if (score in 1..39 && year != null && year > 0) {
                        val raw = o.optString("release_date").ifBlank { o.optString("first_air_date") }
                        val y = raw.take(4).toIntOrNull()
                        if (y != null && kotlin.math.abs(y - year) > 2) score = 0
                    }
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
            ?: searchViaImdb(item)
    }

    private fun isTrackerAnime(item: MediaItem): Boolean {
        if (item.providerId.lowercase() !in setOf("anilist", "simkl", "mal", "kitsu", "shikimori")) return false
        return item.rawType.contains("anime", true) || item.providerId.equals("anilist", true)
    }

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
            val baseScore = score
            if (item.year != null && o.optString("y") == item.year.toString()) score += 25
            // Same year veto as the name search: a prefix-level IMDb hint for a
            // different year's title must not resolve the page to it.
            if (baseScore < 50 && item.year != null && item.year > 0) {
                val y = o.optString("y").toIntOrNull()
                if (y != null && kotlin.math.abs(y - item.year) > 2) continue
            }
            if (score > bestScore) {
                bestScore = score
                bestId = id
            }
        }
        val tt = bestId ?: return null
        return runCatching { resolveImdb(tt, item) }.getOrNull()
    }

    private fun isGenericShortTitle(item: MediaItem): Boolean {
        if (item.providerId.equals("tmdb", true)) return false
        if (item.providerId.lowercase() in setOf("anilist", "simkl", "mal", "kitsu", "shikimori", "trakt")) return false
        val id = item.id.trim()
        if (id.isEmpty()) return false
        if (id.all { it.isDigit() }) return false
        if (id.lowercase().startsWith("tt") && id.length >= 8) return false
        if (id.startsWith("tmdb:", true) || id.startsWith("tmdb-", true)) return false
        if (item.year != null && item.year > 0) return false
        val variants = TmdbMeta.queryVariants(item.searchTitle)
        if (variants.isEmpty()) return true
        return variants.all { v ->
            TmdbMeta.normalizeTitle(v).replace(" ", "").length <= 5
        }
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
            val personalKey = runCatching { HikariApp.instance.store.tmdbApiKey() }.getOrDefault("")
            val keys = buildList { if (personalKey.isNotBlank()) add(personalKey); addAll(API_KEYS) }.distinct()
            for (key in keys) {
                val params = LinkedHashMap<String, String>(query)
                if (lang.isNotBlank()) params["language"] = lang
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
