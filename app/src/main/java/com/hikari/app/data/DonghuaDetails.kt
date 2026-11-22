package com.hikari.app.data

import com.hikari.app.net.Http
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

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

    /** A hit counts only when it has something to show: overview, score or votes.
     *  A cover-only / score-zero / blank-overview hit is a miss — the caller
     *  falls through to TMDB instead of going bare (the DonghuaStream bare case). */
    fun isUsable(info: AnimeInfo): Boolean =
        info.overview.isNotBlank() || info.score10 > 0.0 || info.votes > 0

    suspend fun pick(item: MediaItem): Pick {
        // Memoized per open: extras(), every shelf() row and the anime
        // fallback each call pick() for the same title, and every miss used
        // to cost its own AniList round-trip. 10-min TTL, bounded.
        val key = "pick|" + item.providerId.lowercase() + "|" + item.type + "|" +
            item.id.lowercase() + "|" + item.searchTitle.lowercase()
        pickMem[key]?.let { (at, p) ->
            if (System.currentTimeMillis() - at < PICK_TTL_MS) return p
        }
        val p = pickUncached(item)
        pickMem[key] = System.currentTimeMillis() to p
        if (pickMem.size > 200) pickMem.keys.firstOrNull()?.let { pickMem.remove(it) }
        return p
    }

    private val pickMem = ConcurrentHashMap<String, Pair<Long, Pick>>()
    private const val PICK_TTL_MS = 10 * 60 * 1000L

    private suspend fun pickUncached(item: MediaItem): Pick {
        if (!isAnimeCandidate(item)) return secondChancePick(item)
        if (isTmdbAnimation(item)) return Pick.Tmdb
        val info = load(item) ?: return Pick.None
        return if (isUsable(info)) Pick.Anime(info) else Pick.None
    }

    private suspend fun secondChancePick(item: MediaItem): Pick {
        val p = item.providerId.lowercase()
        if (p == "tmdb" || p.startsWith("tmdb|") ||
            p in setOf("anilist", "simkl", "mal", "kitsu", "shikimori", "trakt")
        ) return Pick.Tmdb
        if (isTmdbAnimation(item)) return Pick.Tmdb
        // Live-action veto (see [isConfidentLiveActionTmdb]): a title TMDB
        // confidently resolves as a NON-Animation entry must not be hijacked
        // by an exact anime namesake ("Iron Man" 2008 vs Marvel Anime), which
        // otherwise blanks its cast/trailers/Related rows and wears the
        // anime's score. True anime films keep the Animation genre, so they
        // still pick anime below. Fail-closed (unknown -> old behaviour).
        if (runCatching { isConfidentLiveActionTmdb(item) }.getOrDefault(false)) return Pick.Tmdb
        val info = secondChanceAnime(item)
            ?: runCatching { load(item) }.getOrNull()?.takeIf { li ->
                val qs = (item.allTitles + TmdbMeta.queryVariants(item.searchTitle))
                    .filter { it.isNotBlank() }
                qs.any { q -> TmdbMeta.titleScore(q, li.title) >= 60 }
            }
            ?: return Pick.None
        return if (isUsable(info)) Pick.Anime(info) else Pick.None
    }

    /** True when TMDB confidently identifies [item] as a NON-Animation entry:
     *  same >=40 title bar as the shelves trust gate, year within 2 when both
     *  sides know it, and a genres list that names no Animation. Anything
     *  unknown (unresolvable, nameless, genreless, network miss) returns false
     *  so the caller keeps today's behaviour. The pure decision lives in
     *  [liveActionVeto] (JVM-tested); this is just the network wrapper. */
    private suspend fun isConfidentLiveActionTmdb(item: MediaItem): Boolean =
        withContext(Dispatchers.IO) {
            val resolved = com.hikari.app.nuvio.TmdbResolver.resolve(item) ?: return@withContext false
            if (resolved.tmdbId.toLongOrNull() == null) return@withContext false
            val seg = if (resolved.mediaType == "movie") "movie" else "tv"
            val d = com.hikari.app.nuvio.TmdbResolver.apiGet("/$seg/${resolved.tmdbId}", emptyMap())
                ?: return@withContext false
            val names = listOf(
                d.optString("title"), d.optString("name"),
                d.optString("original_title"), d.optString("original_name"),
            ).filter { it.isNotBlank() && it != "null" }
            val variants = (item.allTitles + TmdbMeta.queryVariants(item.searchTitle))
                .distinct().filter { it.isNotBlank() }
            val raw = d.optString("release_date").ifBlank { d.optString("first_air_date") }
            val genres = d.optJSONArray("genres")
            val ids = ArrayList<Int>(genres?.length() ?: 0)
            val gnames = ArrayList<String>(genres?.length() ?: 0)
            for (i in 0 until (genres?.length() ?: 0)) {
                val g = genres?.optJSONObject(i) ?: continue
                ids.add(g.optInt("id", 0))
                gnames.add(g.optString("name"))
            }
            liveActionVeto(names, variants, item.year, raw.take(4).toIntOrNull(), ids, gnames)
        }

    /** Pure live-action veto decision (see [isConfidentLiveActionTmdb]):
     *  confident title match, no year clash, genres known with no Animation.
     *  Plain data in/out so the JVM unit tests cover it. */
    fun liveActionVeto(
        tmdbNames: List<String>,
        titleVariants: List<String>,
        itemYear: Int?,
        tmdbYear: Int?,
        genreIds: List<Int>,
        genreNames: List<String>,
    ): Boolean {
        if (tmdbNames.isEmpty() || titleVariants.isEmpty()) return false
        var best = 0
        for (v in titleVariants) for (n in tmdbNames) best = maxOf(best, TmdbMeta.titleScore(v, n))
        if (best < 40) return false
        if (itemYear != null && itemYear > 0 && tmdbYear != null &&
            kotlin.math.abs(tmdbYear - itemYear) > 2
        ) return false
        if (genreIds.isEmpty()) return false
        if (genreIds.any { it == 16 }) return false
        if (genreNames.any { it.equals("animation", ignoreCase = true) }) return false
        return true
    }

    /** Stable-miss memory for the second-chance probe: an AniList answer that
     *  names nothing is a fact, not a hiccup, so it is remembered (12h) — but
     *  a failed request is never remembered and retries on the next open. */
    private val missMem = ConcurrentHashMap<String, Long>()
    private const val MISS_TTL_MS = 12 * 60 * 60 * 1000L

    /** One exact-title AniList probe (single request): is there an anime by
     *  this exact name? Exact-level only (score 60) — a prefix/containment
     *  match must never flip a correctly resolved live-action title onto an
     *  anime namesake. */
    private suspend fun secondChanceAnime(item: MediaItem): AnimeInfo? {
        val q = item.searchTitle.trim()
        if (q.isBlank()) return null
        val key = "probe|" + item.providerId.lowercase() + "|" + q.lowercase()
        missMem[key]?.let { if (System.currentTimeMillis() - it < MISS_TTL_MS) return null }
        val media = withContext(Dispatchers.IO) { anilistSearch(q) } ?: return null
        val queries = linkedSetOf(q)
        item.allTitles.forEach { t -> if (t.isNotBlank()) queries.add(t.trim()) }
        var best: JSONObject? = null
        var bestScore = 0
        for (i in 0 until media.length()) {
            val m = media.optJSONObject(i) ?: continue
            val names = anilistNames(m)
            if (names.isEmpty()) continue
            var score = 0
            for (v in queries) for (n in names) {
                score = maxOf(score, TmdbMeta.titleScore(v, n))
            }
            if (score > bestScore) {
                bestScore = score
                best = m
            }
        }
        if (best == null || bestScore < 60) {
            if (missMem.size >= 500) missMem.clear()
            missMem[key] = System.currentTimeMillis()
            return null
        }
        return buildInfo(best, item.searchTitle)
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
                if (score > bestScore) {
                    bestScore = score
                    best = m
                }
            }
            if (bestScore >= 40) break
        }
        val m = best ?: return null
        if (bestScore <= 0) return null
        return buildInfo(m, item.searchTitle)
    }

    /** Shared AniList media → AnimeInfo mapping for [fromAniList] and the
     *  second-chance probe (see [secondChanceAnime]). A scoreless coverless
     *  bannerless hit is a miss — the caller falls through to TMDB instead of
     *  going bare. */
    private fun buildInfo(m: JSONObject, fallbackTitle: String): AnimeInfo? {
        val id = m.optInt("id", 0)
        if (id <= 0) return null
        val title = m.optJSONObject("title")
        val name = title?.optString("english")?.takeIf { it.isNotBlank() && it != "null" }
            ?: title?.optString("romaji")?.takeIf { it.isNotBlank() && it != "null" }
            ?: title?.optString("native")?.takeIf { it.isNotBlank() && it != "null" }
            ?: fallbackTitle
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
        val gql = "{\"query\":\"query (\$search: String) { Page(perPage: 8) { media(search: \$search, type: ANIME) { id title { romaji english native } synonyms description coverImage { extraLarge large } bannerImage averageScore episodes seasonYear studios { nodes { name } } } } }\",\"variables\":{\"search\":" + JSONObject.quote(query) + "}}"
        val text = Http.postStringQuiet("https://graphql.anilist.co", gql) ?: return null
        return runCatching {
            JSONObject(text).optJSONObject("data")
                ?.optJSONObject("Page")
                ?.optJSONArray("media")
        }.getOrNull()
    }

    private fun anilistNames(m: JSONObject): List<String> {
        val out = ArrayList<String>(8)
        val t = m.optJSONObject("title")
        if (t != null) {
            for (k in listOf("romaji", "english", "native")) {
                t.optString(k).takeIf { it.isNotBlank() && it != "null" }?.let { out += it }
            }
        }
        m.optJSONArray("synonyms")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optString(i)?.takeIf { it.isNotBlank() }?.let { out += it }
            }
        }
        return out.distinct()
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
