package com.hikari.app.data

import com.hikari.app.HikariApp
import com.hikari.app.net.Http
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * AioMetadata-first metadata resolution.
 *
 * Order for EVERY engine (Hikari extensions, CloudStream plugins, Stremio
 * addons, Nuvio scrapers — they all funnel through [TmdbResolver],
 * [TmdbMeta] and `ContentRepository.episodesForInner`, which consult this
 * object first):
 *
 *   1. AioMetadata (when configured — see [configure])
 *   2. TMDB (existing behaviour, unchanged)
 *   3. Extension/provider data (existing fallbacks, unchanged)
 *
 * A miss or a timeout at any earlier leg falls through to the next one, so a
 * dead/misconfigured Aio backend can never blank a page — it just costs one
 * short timeout. Nothing here changes a function signature; the three call
 * sites are additive one-liners.
 *
 * Speed (the "instant" requirement): every leg runs under a 1.2–1.5s budget
 * ([withTimeoutOrNull]), identical concurrent lookups share one network call
 * (single-flight [inflight]), and hits land in a two-level cache (process
 * memory + `filesDir/aio/`, 7-day meta TTL / 12h episode TTL) so the second
 * open of any title paints synchronously with zero network.
 *
 * Backend contract (lenient — unknown shapes are a miss, never a crash):
 *
 *   GET {base}/api/meta?type=movie|tv&title=..&year=..[&imdb=tt..][&tmdb=..]
 *   GET {base}/meta/{movie|tv}/{tt....}              (id shortcut)
 *
 *   {
 *     "title": "…", "year": 1999, "overview": "…", "rating": 7.1,
 *     "poster": "https://…", "backdrop": "https://…",
 *     "tmdbId": "123", "imdbId": "tt1234567",
 *     "episodes": [
 *       {"season": 1, "number": 1, "name": "…", "overview": "…",
 *        "image": "https://…", "released": "1999-10-20", "runtime": 25}
 *     ]
 *   }
 *
 * TMDB-shaped aliases are accepted too (`poster_path`, `backdrop_path`,
 * `vote_average`, `release_date`/`first_air_date`, `name`/`title`,
 * `still_path`, `air_date`, `episode_number`). A response is only TRUSTED
 * when it names our title back (see [isTitleMatch]) or echoes an id we sent;
 * anything else is treated as "not found" so a wrong Aio row can never
 * overwrite good TMDB/extension data.
 */
object AioMetadata {

    data class IdRef(val id: String, val mediaType: String) // tmdbId + "movie"|"tv"

    private const val MEM_MAX = 200
    private const val META_TTL_MS = 7L * 24 * 60 * 60 * 1000
    private const val EPS_TTL_MS = 12L * 60 * 60 * 1000
    private const val NET_BUDGET_MS = 1_500L
    private const val DISK_MAX = 300

    private data class Entry(val at: Long, val json: String)

    private val mem = ConcurrentHashMap<String, Entry>()
    private val inflight = ConcurrentHashMap<String, CompletableDeferred<JSONObject?>>()

    private fun prefs() = runCatching {
        HikariApp.instance.getSharedPreferences("aio_meta", android.content.Context.MODE_PRIVATE)
    }.getOrNull()

    /** Persist the backend endpoint, e.g. `https://aio.example.com`. Blank disables. */
    fun configure(baseUrl: String, apiKey: String = "") {
        runCatching {
            prefs()?.edit()
                ?.putString("base_url", baseUrl.trim().trimEnd('/'))
                ?.putString("api_key", apiKey.trim())
                ?.apply()
        }
    }

    fun baseUrl(): String = runCatching { prefs()?.getString("base_url", "").orEmpty() }.getOrDefault("")

    private fun apiKey(): String = runCatching { prefs()?.getString("api_key", "").orEmpty() }.getOrDefault("")

    fun isConfigured(): Boolean = baseUrl().isNotBlank()

    // ------------------------------------------------------------------ cache

    private fun dir(): File = runCatching {
        File(HikariApp.instance.filesDir, "aio").apply { mkdirs() }
    }.getOrNull() ?: File("/tmp")

    private fun diskFile(kind: String): File = File(dir(), "aio-$kind-v1.json")

    private fun diskRead(kind: String, key: String, ttlMs: Long): JSONObject? = runCatching {
        val f = diskFile(kind)
        if (!f.exists()) return@runCatching null
        val root = JSONObject(f.readText())
        val o = root.optJSONObject(key) ?: return@runCatching null
        if (System.currentTimeMillis() - o.optLong("at") > ttlMs) return@runCatching null
        o.optJSONObject("p")
    }.getOrNull()

    private fun diskWrite(kind: String, key: String, payload: JSONObject) {
        runCatching {
            val f = diskFile(kind)
            val root = if (f.exists()) runCatching { JSONObject(f.readText()) }.getOrNull() ?: JSONObject() else JSONObject()
            root.put(key, JSONObject().put("at", System.currentTimeMillis()).put("p", payload))
            while (root.length() > DISK_MAX) {
                val oldest = root.keys().asSequence().toList()
                    .minByOrNull { root.optJSONObject(it)?.optLong("at") ?: 0L } ?: break
                root.remove(oldest)
            }
            f.writeText(root.toString())
        }
    }

    private fun memGet(key: String, ttlMs: Long): JSONObject? {
        val e = mem[key] ?: return null
        if (System.currentTimeMillis() - e.at > ttlMs) {
            mem.remove(key)
            return null
        }
        return runCatching { JSONObject(e.json) }.getOrNull()
    }

    private fun memPut(key: String, payload: JSONObject) {
        if (mem.size >= MEM_MAX) {
            val drop = mem.keys.firstOrNull() ?: return
            mem.remove(drop)
        }
        mem[key] = Entry(System.currentTimeMillis(), payload.toString())
    }

    // ------------------------------------------------------------------ fetch

    private fun typeSeg(item: MediaItem): String =
        if (item.type == MediaType.MOVIE) "movie" else "tv"

    private fun imdbOf(item: MediaItem): String? {
        val id = item.id.trim()
        if (id.lowercase().startsWith("tt") && id.length >= 8) return id
        return null
    }

    private suspend fun fetchRaw(item: MediaItem, kind: String): JSONObject? {
        val base = baseUrl()
        if (base.isBlank()) return null
        val key = "$kind|${item.searchTitle.lowercase()}|${item.year ?: 0}|${typeSeg(item)}|${imdbOf(item).orEmpty()}"
        val ttl = if (kind == "eps") EPS_TTL_MS else META_TTL_MS
        memGet(key, ttl)?.let { return it }
        diskRead(kind, key, ttl)?.let {
            memPut(key, it)
            return it
        }
        val deferred = CompletableDeferred<JSONObject?>()
        val prev = inflight.putIfAbsent(key, deferred)
        if (prev != null) return prev.await()
        try {
            val got = withTimeoutOrNull(NET_BUDGET_MS) { fetchNetwork(item, kind) }
            if (got != null) {
                memPut(key, got)
                diskWrite(kind, key, got)
            }
            deferred.complete(got)
            return got
        } catch (t: Throwable) {
            deferred.complete(null)
            return null
        } finally {
            inflight.remove(key)
        }
    }

    private suspend fun fetchNetwork(item: MediaItem, kind: String): JSONObject? =
        withContext(Dispatchers.IO) {
            val base = baseUrl()
            if (base.isBlank()) return@withContext null
            val headers = LinkedHashMap<String, String>()
            headers["Accept"] = "application/json"
            if (apiKey().isNotBlank()) headers["Authorization"] = "Bearer ${apiKey()}"
            val title = item.searchTitle.trim()
            val imdb = imdbOf(item)
            val candidates = ArrayList<String>()
            if (imdb != null) candidates += "$base/meta/${typeSeg(item)}/$imdb"
            if (title.isNotBlank()) {
                val q = buildString {
                    append("$base/api/meta?type=${typeSeg(item)}")
                    append("&title=${java.net.URLEncoder.encode(title, "UTF-8")}")
                    if (item.year != null && item.year > 0) append("&year=${item.year}")
                    if (imdb != null) append("&imdb=$imdb")
                    val digits = item.id.trim().takeIf { it.all { c -> c.isDigit() } && it.isNotEmpty() }
                    if (digits != null) append("&tmdb=$digits")
                }
                candidates += q
            }
            for (url in candidates.distinct().take(2)) {
                val text = runCatching {
                    Http.getStringQuiet(url, headers)
                }.getOrNull() ?: continue
                if (text.isBlank()) continue
                val obj = runCatching { JSONObject(text) }.getOrNull() ?: continue
                // The backend may wrap: {data:{…}} / {result:{…}} / {meta:{…}}.
                val inner = obj.optJSONObject("data")
                    ?: obj.optJSONObject("result")
                    ?: obj.optJSONObject("meta")
                    ?: obj
                if (isTitleMatch(item, inner)) return@withContext inner
                // A wrapped list shape: {results:[{…best first…}]}.
                val arr = obj.optJSONArray("results") ?: obj.optJSONArray("items")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val cand = arr.optJSONObject(i) ?: continue
                        if (isTitleMatch(item, cand)) return@withContext cand
                    }
                }
            }
            null
        }

    /** Trust gate: the row must name our title back or echo an id we sent. */
    private fun isTitleMatch(item: MediaItem, o: JSONObject): Boolean {
        val sentImdb = imdbOf(item)?.lowercase()
        val sentTmdb = item.id.trim().takeIf { it.all { c -> c.isDigit() } && it.isNotEmpty() }
        val oImdb = o.optString("imdbId").ifBlank { o.optString("imdb_id") }.trim()
        val oTmdb = o.optString("tmdbId").ifBlank { o.optString("tmdb_id") }.trim()
        if (sentImdb != null && oImdb.equals(sentImdb, true)) return true
        if (sentTmdb != null && (oTmdb == sentTmdb)) return true
        val names = listOf(
            o.optString("title"), o.optString("name"),
            o.optString("original_title"), o.optString("original_name"),
        ).filter { it.isNotBlank() && it != "null" }
        if (names.isEmpty()) return false
        val variants = runCatching {
            item.allTitles.flatMap { TmdbMeta.queryVariants(it) }
        }.getOrNull().orEmpty()
        if (variants.isEmpty()) return false
        var best = 0
        for (v in variants) for (n in names) {
            best = maxOf(best, runCatching { TmdbMeta.titleScore(v, n) }.getOrDefault(0))
        }
        if (best < 25) return false
        // Year veto: a same-name row from a different year is a different title.
        val y = o.optInt("year", -1).takeIf { it > 0 }
            ?: o.optString("release_date").ifBlank { o.optString("first_air_date") }
                .take(4).toIntOrNull()
            ?: o.optString("released").take(4).toIntOrNull()
        if (y != null && item.year != null && item.year > 0 && kotlin.math.abs(y - item.year) > 2) return false
        return true
    }

    private fun str(o: JSONObject, vararg keys: String): String? {
        for (k in keys) {
            val v = o.optString(k).trim()
            if (v.isNotBlank() && v != "null") return v
        }
        return null
    }

    private fun imgUrl(o: JSONObject, vararg keys: String): String? {
        val v = str(o, *keys) ?: return null
        if (v.startsWith("/")) return "https://image.tmdb.org/t/p/w500$v"
        return v
    }

    // ------------------------------------------------------------- public API

    /**
     * Aio's TMDB row for [item], or null (unconfigured / miss / mismatch).
     * Called FIRST from `TmdbResolver.resolve` — every engine benefits.
     */
    suspend fun resolveId(item: MediaItem): IdRef? {
        val o = fetchRaw(item, "meta") ?: return null
        val tmdb = str(o, "tmdbId", "tmdb_id")?.trim()
            ?.takeIf { it.all { c -> c.isDigit() } && it.isNotEmpty() } ?: return null
        val seg = str(o, "mediaType", "media_type", "type")?.lowercase()
        val mediaType = when {
            item.type == MediaType.MOVIE -> "movie"
            seg == "movie" -> "movie"
            seg == "anime" -> "anime"
            else -> "tv"
        }
        return IdRef(tmdb, mediaType)
    }

    /**
     * Aio's poster/backdrop for [item], or null. Called FIRST from
     * `TmdbMeta.artwork` (movies AND series, all engines).
     */
    suspend fun artwork(item: MediaItem): Pair<String?, String?>? {
        val o = fetchRaw(item, "meta") ?: return null
        val poster = imgUrl(o, "poster", "posterUrl", "poster_url", "poster_path")
            ?: o.optJSONObject("images")?.let { imgUrl(it, "poster") }
        val backdrop = imgUrl(o, "backdrop", "backdropUrl", "backdrop_url", "backdrop_path", "banner")
            ?: o.optJSONObject("images")?.let { imgUrl(it, "backdrop") }
        if (poster == null && backdrop == null) return null
        return poster to backdrop
    }

    /**
     * Aio's episode list for [item], or null. Called FIRST from
     * `ContentRepository.episodesForInner` for non-scraper engines — this is
     * the wrong-episodes fix: Aio's layout wins over TMDB's when present.
     * Ids are synthetic but source-neutral (`#aio-s{season}e{number}`), the
     * same standing as TMDB's synthetic ids on these engines.
     */
    suspend fun episodes(item: MediaItem): List<Episode>? {
        val o = fetchRaw(item, "eps") ?: fetchRaw(item, "meta") ?: return null
        val rows = episodeRows(o) ?: return null
        if (rows.length() == 0) return null
        val out = ArrayList<Episode>(rows.length())
        for (i in 0 until rows.length()) {
            val e = rows.optJSONObject(i) ?: continue
            val number = e.optInt("number", e.optInt("episode", e.optInt("episode_number", -1)))
            if (number <= 0) continue
            val season = e.optInt("season", e.optInt("season_number", 1)).takeIf { it > 0 } ?: 1
            out += Episode(
                number = number,
                id = item.id + "#aio-s" + season + "e" + number,
                name = str(e, "name", "title"),
                image = imgUrl(e, "image", "still", "still_path", "thumbnail"),
                season = season,
                overview = str(e, "overview", "description"),
                released = str(e, "released", "air_date", "airDate")?.take(10),
                rating = e.optDouble("rating", e.optDouble("vote_average", 0.0)).takeIf { it > 0.0 },
                runtime = e.optInt("runtime", -1).takeIf { it > 0 },
            )
        }
        out.sortWith(compareBy({ it.season }, { it.number }))
        return out.takeIf { it.isNotEmpty() }
    }

    private fun episodeRows(o: JSONObject): JSONArray? {
        o.optJSONArray("episodes")?.let { return it }
        // Season-grouped shape: {seasons:[{season:1, episodes:[…]}, …]}.
        val seasons = o.optJSONArray("seasons")
        if (seasons != null && seasons.length() > 0) {
            val flat = JSONArray()
            for (i in 0 until seasons.length()) {
                val s = seasons.optJSONObject(i) ?: continue
                val sn = s.optInt("season", s.optInt("season_number", -1))
                val eps = s.optJSONArray("episodes") ?: continue
                for (j in 0 until eps.length()) {
                    val e = eps.optJSONObject(j) ?: continue
                    if (sn > 0 && e.optInt("season", -1) <= 0) e.put("season", sn)
                    flat.put(e)
                }
            }
            if (flat.length() > 0) return flat
        }
        return null
    }

    /**
     * Warm the cache for a visible row of titles (browse/search), bounded so
     * scrolling never fans out: at most 12 titles, 6 at a time, failures
     * swallowed. Second open of any prefetched title paints from cache.
     */
    suspend fun prefetch(items: List<MediaItem>) {
        if (!isConfigured() || items.isEmpty()) return
        runCatching {
            withTimeoutOrNull(8_000) {
                coroutineScope {
                    items.take(12).chunked(6).forEach { chunk ->
                        chunk.map { async(Dispatchers.IO) { fetchRaw(it, "meta") } }.awaitAll()
                    }
                }
            }
        }
    }
}
