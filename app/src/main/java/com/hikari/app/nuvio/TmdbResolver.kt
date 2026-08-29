package com.hikari.app.nuvio

import com.hikari.app.HikariApp
import com.hikari.app.data.MediaItem
import com.hikari.app.data.MediaType
import com.hikari.app.net.Http
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
    private val cacheFile get() = File(HikariApp.instance.filesDir, "nuvio/tmdb-cache.json")

    private val memory = ConcurrentHashMap<String, Resolved>()

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
        val r = resolveNetwork(item)
        if (r != null) {
            memory[key] = r
            saveCache(key, r)
        }
        return r
    }

    private fun cacheKey(item: MediaItem): String {
        val id = item.id.trim()
        return when {
            id.isNotEmpty() && id.all { it.isDigit() } -> "id|$id|${typeHint(item)}"
            id.lowercase().startsWith("tt") -> "imdb|$id|${typeHint(item)}"
            else -> "search|${item.title.trim().lowercase()}|${item.year ?: 0}|${typeHint(item)}"
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

    private suspend fun searchByTitle(item: MediaItem): Resolved? {
        val title = item.title.trim()
        if (title.isBlank()) return null
        val query = mapOf("query" to title)
        val candidates = mutableListOf<Resolved>()
        when (item.type) {
            MediaType.MOVIE -> candidates.addAll(searchType("movie", query, item))
            MediaType.SERIES -> candidates.addAll(searchType(
                if (item.rawType.contains("anime", true)) "tv" else "tv", query, item))
            MediaType.UNKNOWN -> {
                candidates.addAll(searchType("movie", query, item))
                candidates.addAll(searchType("tv", query, item))
            }
        }
        return candidates.firstOrNull { it.tmdbId.isNotBlank() }
    }

    private suspend fun searchType(type: String, query: Map<String, String>, item: MediaItem): List<Resolved> {
        val data = apiGet("/search/$type", query) ?: return emptyList()
        val arr = data.optJSONArray("results") ?: return emptyList()
        val title = item.title.trim().lowercase()
        val year = item.year
        var best: Resolved? = null
        var bestScore = 0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("title").ifBlank { o.optString("name") }.lowercase()
            var score = 0
            if (name == title) score += 50
            else if (name.contains(title) || title.contains(name)) score += 15
            if (year != null && year > 0) {
                val y = runCatching { o.optString("release_date").takeIf { it.isNotBlank() }?.take(4)?.toInt() }
                    .getOrNull()
                    ?: runCatching { o.optString("first_air_date").takeIf { it.isNotBlank() }?.take(4)?.toInt() }
                        .getOrNull()
                if (y == year) score += 35
            }
            if (score > bestScore) {
                bestScore = score
                best = Resolved(o.optString("id"), type)
            }
        }
        return listOfNotNull(best)
    }

    /** GETs a TMDB endpoint, rotating the API key on auth/rate errors. */
    private suspend fun apiGet(path: String, query: Map<String, String>): JSONObject? {
        for (key in API_KEYS) {
            val params = query + ("api_key" to key)
            val qs = params.entries.joinToString("&") { (k, v) ->
                "${java.net.URLEncoder.encode(k, "UTF-8")}=${java.net.URLEncoder.encode(v, "UTF-8")}"
            }
            val url = "$API_BASE$path?$qs"
            val text = Http.getString(url, mapOf("Accept" to "application/json")) ?: continue
            val obj = runCatching { JSONObject(text) }.getOrNull() ?: continue
            if (obj.optString("status_message").contains("Invalid API key", true)) continue
            return obj
        }
        return null
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
