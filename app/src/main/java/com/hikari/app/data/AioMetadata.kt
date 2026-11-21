package com.hikari.app.data

import com.hikari.app.HikariApp
import com.hikari.app.net.Http
import com.hikari.app.nuvio.TmdbResolver
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
 * Backend: any AIOMetadata Stremio addon instance (e.g.
 * `https://aiometadata.elfhosted.com`). The user creates their config on the
 * site once (choosing TMDB/TVDB/TVMaze/MAL/IMDb/Fanart.tv sources) and pastes
 * the Install URL — or just its ID part — into Settings → Integrations →
 * Metadata sources. Everything after that is automatic.
 *
 * Order for EVERY engine (Hikari extensions, CloudStream plugins, Stremio
 * addons, Nuvio scrapers — they all funnel through [TmdbResolver],
 * [TmdbMeta] and `ContentRepository.episodesForInner`, which consult this
 * object first):
 *
 *   1. AioMetadata (when configured — see [configure]; first by user order
 *      in the metadata-addon pipeline, see [MetaSources])
 *   2. Installed metadata addons (Cinemeta and friends, provider order)
 *   3. TMDB (existing behaviour, unchanged)
 *   4. Extension/provider data (existing fallbacks, unchanged)
 *
 * A miss or a timeout at any earlier leg falls through to the next one, so a
 * dead/misconfigured Aio backend can never blank a page — it just costs one
 * short timeout.
 *
 * Speed (the "instant" requirement): every leg runs under a 1.2–1.5s budget
 * ([withTimeoutOrNull]), identical concurrent lookups share one network call
 * (single-flight [inflight]), and hits land in a two-level cache (process
 * memory + `filesDir/aio/`, 7-day meta TTL / 12h episode TTL) so the second
 * open of any title paints synchronously with zero network.
 *
 * Protocol (Stremio addon, lenient — unknown shapes are a miss, never a crash):
 *
 *   GET {root}/meta/movie|series/{tt…|tmdb:N|tvdb:N|mal:N}.json
 *
 *   { "meta": { "name": "…", "poster": "…", "background": "…",
 *               "logo": "…", "description": "…", "genres": […],
 *               "imdb_id": "tt…", "_tmdbId": "123", "year": "1999",
 *               "videos": [{ "season": 1, "episode": 2, "title": "…",
 *                            "overview": "…", "thumbnail": "…",
 *                            "released": "1999-10-20T…" }] } }
 *
 * A response is only TRUSTED when it echoes an id we sent or names our title
 * back (see [isTitleMatch]); anything else is treated as "not found" so a
 * wrong Aio row can never overwrite good TMDB/extension data.
 */
object AioMetadata {

    data class IdRef(val id: String, val mediaType: String) // tmdbId + "movie"|"tv"

    /** Host used when the user pasted only the bare install ID. */
    const val DEFAULT_HOST = "https://aiometadata.elfhosted.com"

    private const val MEM_MAX = 200
    private const val META_TTL_MS = 7L * 24 * 60 * 60 * 1000
    private const val EPS_TTL_MS = 12L * 60 * 60 * 1000
    private const val NET_BUDGET_MS = 1_500L
    private const val DISK_MAX = 300

    private data class Entry(val at: Long, val json: String)

    private val mem = ConcurrentHashMap<String, Entry>()
    private val inflight = ConcurrentHashMap<String, CompletableDeferred<JSONObject?>>()

    /** Persist the Install URL (or its bare ID part). Blank disables. */
    suspend fun configure(baseUrl: String, apiKey: String = "") {
        runCatching {
            HikariApp.instance.store.setAioBaseUrl(baseUrl.trim())
            HikariApp.instance.store.setAioApiKey(apiKey.trim())
        }
    }

    suspend fun storedRef(): String =
        runCatching { HikariApp.instance.store.aioBaseUrl() }.getOrDefault("")

    private suspend fun apiKey(): String =
        runCatching { HikariApp.instance.store.aioApiKey() }.getOrDefault("")

    suspend fun isConfigured(): Boolean = addonRoot() != null

    suspend fun animeEnabled(): Boolean =
        runCatching { HikariApp.instance.store.animeAio() }.getOrDefault(false)

    suspend fun moviesEnabled(): Boolean =
        runCatching { HikariApp.instance.store.videoAio() }.getOrDefault(false)

    /** The toggle that owns [item]: the Anime toggle for anime, the Movies &
     *  series toggle for everything else. Both default OFF (see AppStore). */
    suspend fun enabledFor(item: MediaItem): Boolean =
        if (item.type == MediaType.MOVIE) moviesEnabled()
        else if (item.rawType.contains("anime", true)) animeEnabled()
        else moviesEnabled()

    /**
     * The per-user addon root, e.g.
     * `https://aiometadata.elfhosted.com/stremio/<userId>`, or null when the
     * user has not pasted their Install URL / ID yet. Accepts the full
     * install URL (`…/manifest.json`, `…/configure…`), the addon root, or the
     * bare ID.
     */
    suspend fun addonRoot(): String? {
        val raw = storedRef().trim().trimEnd('/')
        if (raw.isBlank()) return null
        val stremioAt = raw.indexOf("/stremio/")
        if (stremioAt >= 0) {
            val tail = raw.substring(stremioAt + "/stremio/".length)
                .substringBefore('/')
                .substringBefore('?')
                .substringBefore('#')
                .trim()
            if (tail.length >= 3) {
                val host = raw.substring(0, stremioAt).trimEnd('/').ifBlank { DEFAULT_HOST }
                return "$host/stremio/$tail"
            }
            return null
        }
        // Bare ID (no host, no slashes): attach the default host.
        if (!raw.contains('/') && !raw.contains('.') && raw.length >= 3 &&
            raw.all { it.isLetterOrDigit() || it == '_' || it == '-' }
        ) {
            return "$DEFAULT_HOST/stremio/$raw"
        }
        // A bare host or URL without /stremio/: not directly usable (the meta
        // route is per-user), so treat as unconfigured rather than 404-looping.
        return null
    }

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
        if (item.type == MediaType.MOVIE) "movie" else "series"

    private fun imdbOf(item: MediaItem): String? {
        val id = item.id.trim()
        if (id.lowercase().startsWith("tt") && id.length >= 8) return id
        return null
    }

    private fun tmdbDigitsOf(item: MediaItem): String? {
        val id = item.id.trim()
        val bare = when {
            id.startsWith("tmdb:", ignoreCase = true) -> id.substringAfter(':').trim()
            id.startsWith("tmdb-", ignoreCase = true) -> id.substringAfter('-').trim()
            id.isNotEmpty() && id.all { it.isDigit() } -> id
            else -> return null
        }
        return bare.takeIf { it.isNotEmpty() && it.all { it.isDigit() } }
    }

    /** Stremio ids to ask the addon for, best first. */
    private suspend fun candidateIds(item: MediaItem): List<String> {
        // Kitsu rows resolve through their own keyless edge API into MAL /
        // TVDB ids (see [TrackerAnimeResolver.kitsuExternalIds]) — both are
        // namespaces the AIO backend answers — so a Kitsu id is sent to AIO
        // directly and never pays for a TMDB fuzzy search first.
        TrackerAnimeResolver.kitsuDigitsOf(item)?.let { kid ->
            val mapped = runCatching {
                withTimeoutOrNull(2_500) { TrackerAnimeResolver.kitsuExternalIds(kid) }
            }.getOrNull().orEmpty()
            if (mapped.isNotEmpty()) {
                com.hikari.app.data.Logs.log(
                    "Meta",
                    "aio kitsu:$kid -> " + mapped.entries.joinToString(",") { (k, v) -> "$k:$v" },
                )
            }
            val out = ArrayList<String>(2)
            mapped["myanimelist"]?.takeIf { it.all { c -> c.isDigit() } }?.let { out += "mal:$it" }
            mapped["thetvdb"]?.takeIf { it.all { c -> c.isDigit() } }?.let { out += "tvdb:$it" }
            if (out.isNotEmpty()) return out
        }
        imdbOf(item)?.let { return listOf(it) }
        tmdbDigitsOf(item)?.let {
            // A site scraper's own numeric row id is NOT a TMDB id — only
            // trust it when the provider is TMDB-backed (same rule as
            // TmdbResolver) or explicitly tmdb:-prefixed.
            val id = item.id.trim()
            val explicit = id.startsWith("tmdb:", ignoreCase = true) || id.startsWith("tmdb-", ignoreCase = true)
            val backed = item.providerId.equals("tmdb", true) ||
                item.providerId.startsWith("tmdb|", true) ||
                item.rawType.equals("tmdb", true)
            if (explicit || backed) return listOf("tmdb:$it")
        }
        // Last resort: TMDB-only resolution (allowAio=false is the recursion
        // guard — TmdbResolver.resolve would otherwise call straight back here).
        val resolved = runCatching {
            withTimeoutOrNull(NET_BUDGET_MS) { TmdbResolver.resolve(item, allowAio = false) }
        }.getOrNull()
        if (resolved != null && resolved.tmdbId.all { it.isDigit() }) {
            return listOf("tmdb:${resolved.tmdbId}")
        }
        return emptyList()
    }

    private suspend fun fetchRaw(item: MediaItem, kind: String): JSONObject? {
        val root = addonRoot() ?: return null
        val ids = candidateIds(item)
        if (ids.isEmpty()) {
            com.hikari.app.data.Logs.log("Meta", "aio: no usable id for \"${item.title.take(50)}\" — skipped")
            return null
        }
        val key = "$kind|${typeSeg(item)}|${ids.first().lowercase()}|${item.searchTitle.lowercase()}|${item.year ?: 0}"
        val ttl = if (kind == "eps") EPS_TTL_MS else META_TTL_MS
        memGet(key, ttl)?.let {
            com.hikari.app.data.Logs.log("Meta", "aio $kind mem-hit ${ids.first()} for \"${item.title.take(50)}\"")
            return it
        }
        diskRead(kind, key, ttl)?.let {
            memPut(key, it)
            com.hikari.app.data.Logs.log("Meta", "aio $kind disk-hit ${ids.first()} for \"${item.title.take(50)}\"")
            return it
        }
        val deferred = CompletableDeferred<JSONObject?>()
        val prev = inflight.putIfAbsent(key, deferred)
        if (prev != null) return prev.await()
        try {
            val start = System.currentTimeMillis()
            val got = withTimeoutOrNull(NET_BUDGET_MS) { fetchNetwork(root, item, kind, ids) }
            val ms = System.currentTimeMillis() - start
            if (got != null) {
                memPut(key, got)
                diskWrite(kind, key, got)
                if (kind == "eps") {
                    val n = got.optJSONArray("videos")?.length() ?: 0
                    com.hikari.app.data.Logs.log(
                        "Meta",
                        "aio $kind ${ids.first()} for \"${item.title.take(50)}\": $n rows in ${ms}ms",
                    )
                } else {
                    val tmdb = got.optString("_tmdbId").trim().takeIf { it.isNotEmpty() && it != "null" }
                    com.hikari.app.data.Logs.log(
                        "Meta",
                        "aio $kind ${ids.first()} for \"${item.title.take(50)}\": hit" +
                            (if (!tmdb.isNullOrBlank()) " _tmdbId=$tmdb" else "") + " in ${ms}ms",
                    )
                }
            } else {
                com.hikari.app.data.Logs.log(
                    "Meta",
                    "aio $kind ${ids.first()} for \"${item.title.take(50)}\": miss/timeout in ${ms}ms",
                )
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

    private suspend fun fetchNetwork(
        root: String,
        item: MediaItem,
        kind: String,
        ids: List<String>,
    ): JSONObject? = withContext(Dispatchers.IO) {
        val headers = LinkedHashMap<String, String>()
        headers["Accept"] = "application/json"
        if (apiKey().isNotBlank()) headers["Authorization"] = "Bearer ${apiKey()}"
        val seg = typeSeg(item)
        for (sid in ids.distinct().take(2)) {
            val enc = runCatching {
                java.net.URLEncoder.encode(sid, "UTF-8")
            }.getOrNull() ?: continue
            val url = "$root/meta/$seg/$enc.json"
            val text = runCatching { Http.getStringQuiet(url, headers) }.getOrNull()
                ?: continue
            if (text.isBlank()) continue
            val meta = runCatching { JSONObject(text).optJSONObject("meta") }.getOrNull()
                ?: continue
            if (isTrusted(item, sid, meta)) return@withContext meta
        }
        null
    }

    /** Trust gate: the row must echo an id we sent or name our title back. */
    private fun isTrusted(item: MediaItem, sentId: String, meta: JSONObject): Boolean {
        val sent = sentId.lowercase()
        val echoed = listOf(
            meta.optString("id"),
            meta.optString("imdb_id"),
            meta.optString("_imdbId"),
            meta.optString("_tmdbId"),
            meta.optString("_tvdbId"),
        ).map { it.trim().lowercase() }.filter { it.isNotBlank() && it != "null" }
        if (sent.startsWith("tt")) {
            if (echoed.any { it == sent }) return true
        } else if (sent.startsWith("tmdb:")) {
            val digits = sent.substringAfter(':')
            if (echoed.any { it == digits || it == sent }) return true
        }
        // Fall back to the name check (same bar as the old generic client).
        val names = listOf(meta.optString("name"))
            .filter { it.isNotBlank() && it != "null" }
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
        val y = meta.optString("year").take(4).toIntOrNull()
            ?: meta.optString("releaseInfo").take(4).toIntOrNull()
            ?: meta.optString("released").take(4).toIntOrNull()
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

    // ------------------------------------------------------------- public API

    /**
     * Aio's TMDB row for [item], or null (unconfigured / miss / mismatch).
     * Called FIRST from `TmdbResolver.resolve` — every engine benefits.
     */
    suspend fun resolveId(item: MediaItem): IdRef? {
        if (!enabledFor(item)) return null
        val o = fetchRaw(item, "meta") ?: return null
        val tmdb = o.optString("_tmdbId").trim()
            .takeIf { it.isNotEmpty() && it.all { c -> c.isDigit() } } ?: return null
        val mediaType = when {
            item.type == MediaType.MOVIE -> "movie"
            item.rawType.contains("anime", true) -> "anime"
            else -> "tv"
        }
        return IdRef(tmdb, mediaType)
    }

    /**
     * Aio's poster/backdrop for [item], or null. Called FIRST from
     * `TmdbMeta.artwork` (movies AND series, all engines).
     */
    suspend fun artwork(item: MediaItem): Pair<String?, String?>? {
        if (!enabledFor(item)) return null
        val o = fetchRaw(item, "meta") ?: return null
        val poster = str(o, "poster")
        val backdrop = str(o, "background")
        if (poster == null && backdrop == null) return null
        return poster to backdrop
    }

    /**
     * Aio's raw `/meta` document for [item] (unconfigured / miss / mismatch ->
     * null). Backs the metadata-addon pipeline (see [MetaSources]): text
     * fields are trusted by the same gate as every other leg ([fetchRaw]).
     */
    suspend fun meta(item: MediaItem): JSONObject? {
        if (!enabledFor(item)) return null
        return fetchRaw(item, "meta")
    }

    /**
     * Aio's episode list for [item], or null. Called FIRST from
     * `ContentRepository.episodesForInner` for non-scraper engines — this is
     * the wrong-episodes fix: Aio's layout wins over TMDB's when present.
     * Ids are synthetic but source-neutral (`#aio-s{season}e{number}`), the
     * same standing as TMDB's synthetic ids on these engines.
     */
    suspend fun episodes(item: MediaItem): List<Episode>? {
        if (!enabledFor(item)) return null
        val o = fetchRaw(item, "eps") ?: fetchRaw(item, "meta") ?: return null
        val arr = o.optJSONArray("videos") ?: return null
        if (arr.length() == 0) return null
        val out = ArrayList<Episode>(arr.length())
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val number = e.optInt("episode", -1)
            if (number <= 0) continue
            val season = e.optInt("season", 1).takeIf { it >= 0 } ?: 1
            val thumb = str(e, "thumbnail")
                ?.takeIf { !it.contains("missing_thumbnail") && !it.contains("missing-thumbnail") }
            out += Episode(
                number = number,
                id = item.id + "#aio-s" + season + "e" + number,
                name = str(e, "title", "name"),
                image = thumb,
                season = season,
                overview = str(e, "overview"),
                released = str(e, "released")?.take(10)?.takeIf { it.length == 10 },
                rating = null,
                runtime = null,
            )
        }
        out.sortWith(compareBy({ it.season }, { it.number }))
        return out.takeIf { it.isNotEmpty() }
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
