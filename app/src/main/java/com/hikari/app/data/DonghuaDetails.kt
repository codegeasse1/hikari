package com.hikari.app.data

import com.hikari.app.net.Http
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Anime/donghua metadata fallback for titles TMDB mistranslates.
 *
 * TMDB's search index is live-action-first: a generic English title like "Big
 * Brother" resolves to the sitcom (wrong poster, wrong overview, 165 fake
 * episodes) while every donghua around it resolves fine. Anime databases know
 * these titles natively, so anime candidates try them FIRST — a confident hit
 * wins outright and TMDB is never consulted for that title.
 *
 * Chain: AniList GraphQL (no key, best donghua coverage) → Bangumi (no key,
 * strongest for Chinese-original entries) → null (caller keeps TMDB/provider
 * art as before). The result is mapped into [AnimeInfo], which the callers
 * translate into whatever they would otherwise have read from TMDB; [source]
 * carries an "anilist:"/"bangumi:"-prefixed id so the UI labels the score
 * honestly and nobody ever calls a TMDB season endpoint with a foreign id.
 *
 * Port of the desktop client's DonghuaDetails, mapped onto this tree's shapes
 * (there is no TmdbDetails.Details here — the consumers are TmdbMeta,
 * TmdbScreenshots, TmdbStills and Ratings directly).
 */
object DonghuaDetails {

    /** One anime hit, in the fields the TMDB consumers need. All URLs here are
     *  ABSOLUTE (AniList/Bangumi serve full URLs) — never prefix them with an
     *  image.tmdb.org size the way TMDB paths are. */
    data class AnimeInfo(
        val title: String,
        val overview: String,
        val cover: String?,
        val banner: String?,
        val score10: Double,
        val votes: Int,
        /** "anilist:12345" or "bangumi:67890" — the honest score label and a
         *  key that can never collide with a TMDB id. */
        val source: String,
        val studios: List<String> = emptyList(),
    )

    /** What a title is, once the animation gate has run: [Anime] (AniList or
     *  Bangumi knew it — use that and never touch TMDB for this title),
     *  [Tmdb] (not an anime candidate, or TMDB itself confirmed animation —
     *  the normal TMDB path), [None] (an anime candidate neither source knew —
     *  the caller falls back to its old TMDB/provider behaviour). */
    sealed interface Pick {
        data class Anime(val info: AnimeInfo) : Pick
        object Tmdb : Pick
        object None : Pick
    }

    /** True when the item plausibly is anime/donghua (provider- or type-tagged). */
    fun isAnimeCandidate(item: MediaItem): Boolean {
        if (item.rawType.contains("anime", ignoreCase = true)) return true
        val p = item.providerId.lowercase()
        return p.contains("anime") || p.contains("donghua")
    }

    /**
     * The gate every TMDB consumer runs before resolving [item]. Anime
     * candidates get the animation check first: when TMDB's own details put
     * the title in Animation (genre id 16 or the name "Animation") TMDB knows
     * what it is and the normal path stands; otherwise AniList/Bangumi are
     * asked, and a confident hit wins outright so a Hollywood mistranslation
     * can never overwrite the donghua. An empty genre list is unknown, not a
     * verdict — candidates still try the anime sources in that case.
     */
    suspend fun pick(item: MediaItem): Pick {
        if (!isAnimeCandidate(item)) return Pick.Tmdb
        if (isTmdbAnimation(item)) return Pick.Tmdb
        return load(item)?.let { Pick.Anime(it) } ?: Pick.None
    }

    /** TMDB's own details call the title Animation (or not). Null/no genres =
     *  unknown. Never throws. The answer is stable per TMDB id, so it is
     *  remembered process-wide — every TMDB consumer runs [pick], and without
     *  this one detail open would re-ask the same details call ~9 times. */
    private val animMem = ConcurrentHashMap<String, Boolean>()

    private suspend fun isTmdbAnimation(item: MediaItem): Boolean =
        runCatching {
            withContext(Dispatchers.IO) {
                val resolved = com.hikari.app.nuvio.TmdbResolver.resolve(item)
                    ?: return@withContext false
                if (resolved.tmdbId.toLongOrNull() == null) return@withContext false
                val key = resolved.mediaType + "/" + resolved.tmdbId
                animMem[key]?.let { return@withContext it }
                val seg = if (resolved.mediaType == "movie") "movie" else "tv"
                val d = com.hikari.app.nuvio.TmdbResolver.apiGet(
                    "/$seg/${resolved.tmdbId}", emptyMap()
                )
                val genres = d?.optJSONArray("genres")
                val hit = genres != null && (0 until genres.length()).any { i ->
                    val g = genres.optJSONObject(i) ?: return@any false
                    g.optInt("id", 0) == 16 ||
                        g.optString("name").equals("animation", ignoreCase = true)
                }
                if (animMem.size >= 500) animMem.clear()
                animMem[key] = hit
                hit
            }
        }.getOrDefault(false)

    /** Single-flight guard: the detail page, the hero and the ratings warm-up
     *  resolve the same titles at once, and without this each one fires its own
     *  AniList/Bangumi burst — duplicate traffic that burns rate-limit budget
     *  and turns hits into misses (provider art again). */
    private val inflight = ConcurrentHashMap<String, CompletableDeferred<AnimeInfo?>>()

    /** Success-only memory: a hit is stable (anime metadata barely moves), a
     *  miss is usually a hiccup or a spelling AniList doesn't share, so misses
     *  are NOT remembered and the next open tries again. */
    private val mem = ConcurrentHashMap<String, AnimeInfo>()

    suspend fun load(item: MediaItem): AnimeInfo? {
        val key = item.providerId.lowercase() + "|" + item.type + "|" +
            item.searchTitle.lowercase() + "|" + (item.year ?: 0)
        mem[key]?.let { return it }
        inflight[key]?.let { return it.await() }
        val deferred = CompletableDeferred<AnimeInfo?>()
        inflight[key] = deferred
        try {
            val r = withContext(Dispatchers.IO) {
                runCatching { fromAniList(item) }.getOrNull()
                    ?: runCatching { fromBangumi(item) }.getOrNull()
            }
            if (r != null) {
                if (mem.size >= 300) mem.clear()
                mem[key] = r
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

    // ── AniList ──────────────────────────────────────────────────────────

    private fun fromAniList(item: MediaItem): AnimeInfo? {
        val queries = linkedSetOf<String>()
        item.allTitles.forEach { queries.add(it) }
        TmdbMeta.queryVariants(item.searchTitle).take(2).forEach { queries.add(it) }
        var best: JSONObject? = null
        var bestScore = 0
        for (q in queries) {
            if (q.isBlank()) continue
            val media = anilistSearch(q) ?: continue
            for (i in 0 until media.length()) {
                val m = media.optJSONObject(i) ?: continue
                val names = anilistNames(m)
                if (names.isEmpty()) continue
                var score = 0
                for (v in queries) for (n in names) {
                    score = maxOf(score, TmdbMeta.titleScore(v, n))
                }
                if (score == 0) continue
                if (item.year != null && item.year > 0) {
                    val y = m.optInt("seasonYear", 0)
                    if (y == item.year) score += 25
                    else if (y > 0 && kotlin.math.abs(y - item.year) <= 1) score += 8
                }
                // Animation-only index: no genre penalty needed, but a
                // series-length donghua matching a series-length entry wins
                // over a movie-length one.
                if (score > bestScore) {
                    bestScore = score
                    best = m
                }
            }
            if (bestScore >= 40) break
        }
        val m = best ?: return null
        if (bestScore <= 0) return null
        val id = m.optInt("id", 0)
        if (id <= 0) return null
        val title = m.optJSONObject("title")
        val name = title?.optString("english")?.takeIf { it.isNotBlank() && it != "null" }
            ?: title?.optString("romaji")?.takeIf { it.isNotBlank() && it != "null" }
            ?: title?.optString("native")?.takeIf { it.isNotBlank() && it != "null" }
            ?: item.searchTitle
        val cover = m.optJSONObject("coverImage")
        val coverUrl = cover?.optString("extraLarge")?.takeIf { it.isNotBlank() && it != "null" }
            ?: cover?.optString("large")?.takeIf { it.isNotBlank() && it != "null" }
        val banner = m.optString("bannerImage").takeIf { it.isNotBlank() && it != "null" }
        val score10 = m.optInt("averageScore", 0) / 10.0
        val studios = ArrayList<String>()
        m.optJSONObject("studios")?.optJSONArray("nodes")?.let { arr ->
            for (i in 0 until arr.length()) {
                val n = arr.optJSONObject(i)?.optString("name").orEmpty()
                if (n.isNotBlank() && n != "null") studios.add(n)
            }
        }
        if (score10 <= 0.0 && banner == null && coverUrl == null) return null
        return AnimeInfo(
            title = name,
            overview = stripHtml(m.optString("description")),
            cover = coverUrl,
            banner = banner,
            score10 = score10,
            votes = 0,
            source = "anilist:$id",
            studios = studios,
        )
    }

    private fun anilistSearch(query: String): org.json.JSONArray? {
        // Quiet client: a metadata service must never raise the Home screen's
        // "verification needed" banner (see AnimeCast — same rule).
        val gql = "{\"query\":\"query (\$search: String) { Page(perPage: 8) { media(search: \$search, type: ANIME) { id title { romaji english native } description coverImage { extraLarge large } bannerImage averageScore episodes seasonYear studios { nodes { name } } } } }\",\"variables\":{\"search\":" + JSONObject.quote(query) + "}}"
        val text = Http.postStringQuiet("https://graphql.anilist.co", gql) ?: return null
        return runCatching {
            JSONObject(text).optJSONObject("data")
                ?.optJSONObject("Page")
                ?.optJSONArray("media")
        }.getOrNull()
    }

    private fun anilistNames(m: JSONObject): List<String> {
        val t = m.optJSONObject("title") ?: return emptyList()
        return listOf(t.optString("romaji"), t.optString("english"), t.optString("native"))
            .filter { it.isNotBlank() && it != "null" }
    }

    // ── Bangumi ──────────────────────────────────────────────────────────

    private fun fromBangumi(item: MediaItem): AnimeInfo? {
        val queries = linkedSetOf<String>()
        item.allTitles.forEach { queries.add(it) }
        TmdbMeta.queryVariants(item.searchTitle).take(2).forEach { queries.add(it) }
        var best: JSONObject? = null
        var bestScore = 0
        for (q in queries) {
            if (q.isBlank()) continue
            val enc = runCatching { java.net.URLEncoder.encode(q, "UTF-8") }.getOrNull() ?: continue
            val text = Http.getString(
                "https://api.bgm.tv/search/subject/$enc?type=2&responseGroup=large",
                mapOf("User-Agent" to "hikari-donghua-fallback/1.0"),
            ) ?: continue
            val arr = runCatching { JSONObject(text).optJSONArray("list") }.getOrNull() ?: continue
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                val names = listOf(s.optString("name"), s.optString("name_cn"))
                    .filter { it.isNotBlank() && it != "null" }
                if (names.isEmpty()) continue
                var score = 0
                for (v in queries) for (n in names) {
                    score = maxOf(score, TmdbMeta.titleScore(v, n))
                }
                if (score == 0) continue
                if (score > bestScore) {
                    bestScore = score
                    best = s
                }
            }
            if (bestScore >= 40) break
        }
        val s = best ?: return null
        if (bestScore <= 0) return null
        val id = s.optLong("id", 0L)
        if (id <= 0L) return null
        val images = s.optJSONObject("images")
        val coverUrl = images?.optString("large")?.takeIf { it.isNotBlank() && it != "null" }
            ?: images?.optString("common")?.takeIf { it.isNotBlank() && it != "null" }
        val score10 = s.optJSONObject("rating")?.optDouble("score", 0.0) ?: 0.0
        val votes = s.optJSONObject("rating")?.optInt("total", 0) ?: 0
        val summary = s.optString("summary").takeIf { it.isNotBlank() && it != "null" }.orEmpty()
        if (score10 <= 0.0 && coverUrl == null && summary.isBlank()) return null
        return AnimeInfo(
            title = s.optString("name_cn").takeIf { it.isNotBlank() && it != "null" }
                ?: s.optString("name").takeIf { it.isNotBlank() && it != "null" }
                ?: item.searchTitle,
            overview = summary.trim(),
            cover = coverUrl,
            banner = null,
            score10 = score10,
            votes = votes,
            source = "bangumi:$id",
        )
    }

    private fun stripHtml(raw: String): String {
        if (raw.isBlank() || raw == "null") return ""
        return raw.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .trim()
    }
}
