package com.hikari.app.data

import com.hikari.app.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * TheTVDB fallback (TMDB -> TVDB -> AniList/Bangumi).
 *
 * Keyless for the user: TVDB's own website search API
 * (https://api4.thetvdb.com/web/search/queries) needs no API key, no login.
 * One POST returns poster, banner (wide), English overview, year, companies,
 * country and the TMDB/IMDb remote ids for cross-validation.
 *
 * Fenced, cached, never throws: a TVDB miss only loses its own fields.
 */
object TvdbArt {

    data class TvdbInfo(
        val title: String,
        val poster: String?,
        val banner: String?,
        val overviewEng: String,
        val year: Int?,
        val companies: List<String> = emptyList(),
        val network: String? = null,
        val country: String? = null,
        val tmdbId: String? = null,
        val imdbId: String? = null,
        val slug: String? = null,
    )

    private const val SEARCH_URL = "https://api4.thetvdb.com/web/search/queries"
    private const val TTL_HIT = 30L * 24L * 60L * 60L * 1000L
    private const val TTL_MISS = 6L * 60L * 60L * 1000L

    private val mem = ConcurrentHashMap<String, Pair<TvdbInfo?, Long>>()
    private val lock = Any()
    private var disk: JSONObject? = null
    private val cacheFile: File?
        get() = runCatching { java.io.File(com.hikari.app.HikariApp.instance.filesDir, "tvdb-cache.json") }.getOrNull()

    suspend fun lookup(item: MediaItem): TvdbInfo? = withContext(Dispatchers.IO) {
        val key = (item.searchTitle.trim().lowercase() + "|" + (item.year ?: 0) + "|" + item.type.name)
        if (key.length < 3) return@withContext null
        mem[key]?.let { (v, at) ->
            val ttl = if (v == null) TTL_MISS else TTL_HIT
            if (System.currentTimeMillis() - at < ttl) return@withContext v
        }
        readDisk(key)?.let { (v, at) ->
            val ttl = if (v == null) TTL_MISS else TTL_HIT
            if (System.currentTimeMillis() - at < ttl) {
                mem[key] = v to at
                return@withContext v
            }
        }
        val found = runCatching { fetch(item) }.getOrDefault(null)
        mem[key] = found to System.currentTimeMillis()
        writeDisk(key, found)
        found
    }

    suspend fun artwork(item: MediaItem): Pair<String?, String?>? {
        val info = lookup(item) ?: return null
        if (info.poster == null && info.banner == null) return null
        return info.poster to info.banner
    }

    fun backdropsFor(info: TvdbInfo?): List<String> {
        if (info == null) return emptyList()
        return listOfNotNull(info.banner).distinct()
    }

    private fun fetch(item: MediaItem): TvdbInfo? {
        val queries = LinkedHashSet<String>()
        item.allTitles.forEach { if (it.isNotBlank()) queries.add(it.trim()) }
        TmdbMeta.queryVariants(item.searchTitle).take(2).forEach { if (it.isNotBlank()) queries.add(it) }
        if (queries.isEmpty()) return null
        var best: JSONObject? = null
        var bestScore = 0
        var bestYear: Int? = null
        for (q in queries.take(3)) {
            val hits = searchTvdb(q) ?: continue
            for (i in 0 until hits.length()) {
                val h = hits.optJSONObject(i) ?: continue
                val type = h.optString("type").lowercase()
                val wantSeries = item.type != MediaType.MOVIE
                if (wantSeries && type != "series") {
                    if (item.type == MediaType.SERIES) continue
                }
                if (!wantSeries && item.type == MediaType.MOVIE && type != "movie") continue
                val names = ArrayList<String>()
                h.optString("name").takeIf { it.isNotBlank() }?.let { names.add(it) }
                val arr = h.optJSONArray("aliases")
                if (arr != null) for (a in 0 until arr.length()) {
                    arr.optString(a)?.takeIf { it.isNotBlank() }?.let { names.add(it) }
                }
                if (names.isEmpty()) continue
                var score = 0
                for (v in queries) for (n in names) {
                    score = maxOf(score, TmdbMeta.titleScore(v, n))
                }
                if (score == 0) continue
                val y = h.optString("year").take(4).toIntOrNull()
                    ?: h.optString("first_air_date").take(4).toIntOrNull()
                if (item.year != null && item.year > 0 && y != null) {
                    if (kotlin.math.abs(y - item.year) > 2) {
                        if (score < 40) continue
                    }
                    if (y == item.year) score += 25
                    else if (kotlin.math.abs(y - item.year) <= 1) score += 8
                }
                if (score > bestScore) {
                    bestScore = score
                    best = h
                    bestYear = y
                }
            }
            if (bestScore >= 60) break
        }
        val h = best ?: return null
        if (bestScore <= 0) return null
        val overviews = h.optJSONObject("overviews")
        val eng = overviews?.optString("eng")?.takeIf { it.isNotBlank() && it != "null" }
            ?: run {
                val arr = h.optJSONArray("overview_translated")
                var out: String? = null
                if (arr != null) for (i in 0 until arr.length()) {
                    val s = arr.optString(i)
                    if (s.startsWith("eng:")) { out = s.removePrefix("eng:").trim(); break }
                }
                out
            }.orEmpty()
        val poster = h.optString("poster").takeIf { it.isNotBlank() && it != "null" }
            ?: h.optString("image_url").takeIf { it.isNotBlank() && it != "null" }
            ?: h.optString("image").takeIf { it.isNotBlank() && it != "null" }
        val banner = h.optString("banner").takeIf { it.isNotBlank() && it != "null" }
        val companies = ArrayList<String>()
        h.optJSONArray("companies")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optString(i)?.takeIf { it.isNotBlank() }?.let { if (it !in companies) companies.add(it) }
            }
        }
        var tmdbId: String? = null
        var imdbId: String? = null
        h.optJSONArray("remote_ids")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val src = o.optString("sourceName")
                val id = o.optString("id").trim()
                if (id.isBlank()) continue
                if (src.contains("MovieDB", true) && tmdbId == null && id.all { it.isDigit() }) tmdbId = id
                if (src.equals("IMDB", true) && imdbId == null && id.startsWith("tt")) imdbId = id
            }
        }
        val year = bestYear
            ?: h.optString("year").take(4).toIntOrNull()
            ?: h.optString("first_air_date").take(4).toIntOrNull()
        if (poster == null && banner == null && eng.isBlank()) return null
        return TvdbInfo(
            title = h.optString("name").takeIf { it.isNotBlank() } ?: item.searchTitle,
            poster = poster,
            banner = banner,
            overviewEng = eng,
            year = year,
            companies = companies,
            network = h.optString("network").takeIf { it.isNotBlank() },
            country = h.optString("country").takeIf { it.isNotBlank() },
            tmdbId = tmdbId,
            imdbId = imdbId,
            slug = h.optString("slug").takeIf { it.isNotBlank() },
        )
    }

    private fun searchTvdb(query: String): org.json.JSONArray? {
        if (query.isBlank()) return null
        val body = JSONObject()
            .put("requests", org.json.JSONArray().put(JSONObject().put("indexName", "TVDB").put("params", JSONObject().put("query", query.trim()))))
            .toString()
        val text = Http.postStringQuiet(SEARCH_URL, body, mapOf("Content-Type" to "application/json", "Accept" to "application/json")) ?: return null
        return runCatching {
            JSONObject(text).optJSONArray("results")?.optJSONObject(0)?.optJSONArray("hits")
        }.getOrNull()
    }

    private fun readDisk(key: String): Pair<TvdbInfo?, Long>? = synchronized(lock) {
        return try {
            val root = disk ?: runCatching {
                cacheFile?.takeIf { it.exists() }?.let { JSONObject(it.readText()) }
            }.getOrNull()?.also { disk = it } ?: return null
            if (!root.has(key)) return null
            val e = root.optJSONObject(key) ?: return null
            val at = e.optLong("at")
            if (e.isNull("v")) return null to at
            val o = e.optJSONObject("v") ?: return null to at
            val info = TvdbInfo(
                title = o.optString("t"),
                poster = o.optString("p").takeIf { it.isNotBlank() },
                banner = o.optString("b").takeIf { it.isNotBlank() },
                overviewEng = o.optString("o"),
                year = o.optInt("y").takeIf { it > 0 },
                companies = o.optJSONArray("c")?.let { arr -> (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } } } ?: emptyList(),
                network = o.optString("n").takeIf { it.isNotBlank() },
                country = o.optString("co").takeIf { it.isNotBlank() },
                tmdbId = o.optString("tm").takeIf { it.isNotBlank() },
                imdbId = o.optString("im").takeIf { it.isNotBlank() },
                slug = o.optString("s").takeIf { it.isNotBlank() },
            )
            info to at
        } catch (_: Throwable) { null }
    }

    private fun writeDisk(key: String, v: TvdbInfo?) {
        synchronized(lock) {
            try {
                val root = disk ?: runCatching {
                    cacheFile?.takeIf { it.exists() }?.let { JSONObject(it.readText()) }
                }.getOrNull() ?: JSONObject()
                disk = root
                val e = JSONObject().put("at", System.currentTimeMillis())
                if (v != null) {
                    e.put("v", JSONObject()
                        .put("t", v.title)
                        .put("p", v.poster ?: "")
                        .put("b", v.banner ?: "")
                        .put("o", v.overviewEng)
                        .put("y", v.year ?: 0)
                        .put("c", org.json.JSONArray(v.companies))
                        .put("n", v.network ?: "")
                        .put("co", v.country ?: "")
                        .put("tm", v.tmdbId ?: "")
                        .put("im", v.imdbId ?: "")
                        .put("s", v.slug ?: ""))
                }
                root.put(key, e)
                if (root.length() > 300) {
                    val keys = root.keys().asSequence().toList()
                    val oldest = keys.sortedBy { root.optJSONObject(it)?.optLong("at") ?: 0L }
                    oldest.take(keys.size - 300).forEach { root.remove(it) }
                }
                cacheFile?.writeText(root.toString())
            } catch (_: Throwable) { }
        }
    }
}
// rebuild 0.12.64 attempt 2
