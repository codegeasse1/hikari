package com.hikari.app.data

import com.hikari.app.HikariApp
import com.hikari.app.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

object MangaMetadataRepository {
    /** Where the user fetches their own free key (opened from Settings). */
    const val GET_KEY_URL = "https://pornhwadb.com/profile?tab=settings&section=developer"
    private const val BASE_URL = "https://pornhwadb.com/api/v1"
    /** One tracker step may never hang the chain: enrichment runs behind the
     *  page, but a stuck socket must still fail fast into the next source. */
    private const val SOURCE_TIMEOUT_MS = 12_000L

    data class MangaMeta(
        val description: String? = null,
        val genres: List<String> = emptyList(),
        val coverUrl: String? = null,
        val rating: Double? = null,
        val source: String = "AniList",
        val isAdult: Boolean = false,
        /** AniList media id behind this record (0 when the AniList step missed). */
        val anilistId: Int = 0,
        /** MyAnimeList id behind this record (0 when the Jikan step missed). */
        val malId: Int = 0,
        /** PornHwaDB slug behind this record (null when its step missed). */
        val pwdbSlug: String? = null,
    )

    /** One character: portrait + name for the detail page's row. */
    data class MangaCharacter(
        val name: String,
        val imageUrl: String? = null,
        /** "MAIN"/"SUPPORTING"/"Main"/"Supporting" — printed as-is. */
        val role: String? = null,
    )

    /** One AniList forum thread about the title (the Comments tab's rows). */
    data class MangaThread(
        val id: Int,
        val title: String,
        val replyCount: Int = 0,
        val viewCount: Int = 0,
        val replyUser: String? = null,
        /** Epoch seconds (AniList's repliedAt); 0 = unknown. */
        val repliedAtSec: Long = 0L,
    ) {
        val url: String get() = "https://anilist.co/forum/thread/$id"
    }

    /** One PornHwaDB review (the Comments tab for adult titles). */
    data class MangaReview(
        val user: String,
        val text: String,
        val rating: Double? = null,
        val createdAt: String = "",
    )

    private data class CacheEntry(val at: Long, val meta: MangaMeta?)
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private const val TTL_MS = 24 * 60 * 60 * 1000L
    /** Characters change rarely; threads/reviews move fast — shorter TTL. */
    private const val THREAD_TTL_MS = 60 * 60 * 1000L
    private val charCache = ConcurrentHashMap<String, Pair<Long, List<MangaCharacter>>>()
    private val threadCache = ConcurrentHashMap<String, Pair<Long, List<MangaThread>>>()
    private val reviewCache = ConcurrentHashMap<String, Pair<Long, List<MangaReview>>>()
    private val hintCache = ConcurrentHashMap<String, Pair<Long, Map<Int, String>>>()

    private fun isNullWord(s: String): Boolean {
        val t = s.trim().lowercase()
        return t == "null" || t == "undefined" || t == "none"
    }

    suspend fun fetchExternal(app: HikariApp, fallbackTitle: String): MangaMeta? {
        val title = fallbackTitle.trim()
        if (title.isBlank()) return null
        val store = app.store
        val wantAnilist = runCatching { store.mangaAnilist() }.getOrDefault(true)
        val wantPwdb = runCatching { store.adultManhwa() }.getOrDefault(false)
        if (!wantAnilist && !wantPwdb) return null
        val pwdbKey = if (wantPwdb) runCatching { store.pornhwaApiKey() }.getOrDefault("").trim() else ""
        val ck = (if (pwdbKey.isNotEmpty()) "P:" else "A:") + title.lowercase()
        cache[ck]?.let { if (System.currentTimeMillis() - it.at < TTL_MS) return it.meta }
        val anilist = if (wantAnilist) {
            // Bounded: one dropped/hung request fails fast into Jikan/Kitsu/
            // MangaDex instead of stalling the chain (their retries on the
            // next open cover blips — failures are never cached, see below).
            withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
                runCatching { withContext(Dispatchers.IO) { fetchAnilistManga(title) } }.getOrNull()
            }
        } else null
        // Jikan (MyAnimeList) fallback, then Kitsu, then MangaDex: only when
        // AniList drew a blank, so normal loads pay nothing extra.
        val jikan = if (anilist == null) {
            withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
                runCatching { withContext(Dispatchers.IO) { fetchJikanManga(title) } }.getOrNull()
            }
        } else null
        // Kitsu (keyless, Fastly CDN — reachable where Jikan stalls): metadata
        // only, no characters endpoint worth trusting.
        val kitsu = if (anilist == null && jikan == null) {
            withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
                runCatching { withContext(Dispatchers.IO) { fetchKitsuManga(title) } }.getOrNull()
            }
        } else null
        val dex = if (anilist == null && jikan == null && kitsu == null) {
            withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
                runCatching { withContext(Dispatchers.IO) { fetchDexManga(title) } }.getOrNull()
            }
        } else null
        val pwdb = if (pwdbKey.isNotEmpty() && (anilist == null || anilist.isAdult)) {
            withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
                runCatching { withContext(Dispatchers.IO) { fetchPwdb(title, pwdbKey) } }.getOrNull()
            }
        } else null
        val merged = if (anilist == null && jikan == null && kitsu == null && dex == null && pwdb == null) null else MangaMeta(
            description = pwdb?.description ?: anilist?.description ?: jikan?.description ?: kitsu?.description ?: dex?.description,
            genres = if (!pwdb?.genres.isNullOrEmpty()) pwdb.genres else (anilist?.genres?.takeIf { it.isNotEmpty() }
                ?: jikan?.genres?.takeIf { it.isNotEmpty() } ?: kitsu?.genres?.takeIf { it.isNotEmpty() } ?: dex?.genres.orEmpty()),
            coverUrl = pwdb?.coverUrl ?: anilist?.coverUrl ?: jikan?.coverUrl ?: kitsu?.coverUrl ?: dex?.coverUrl,
            rating = pwdb?.rating ?: anilist?.rating ?: jikan?.rating ?: kitsu?.rating ?: dex?.rating,
            source = if (pwdb != null) "PornHwaDB" else if (anilist != null) "AniList" else if (jikan != null) "Jikan" else if (kitsu != null) "Kitsu" else "MangaDex",
            isAdult = (anilist?.isAdult == true) || pwdb != null,
            anilistId = anilist?.anilistId ?: 0,
            malId = jikan?.malId ?: 0,
            pwdbSlug = pwdb?.pwdbSlug,
        )
        // Never latch a failure: only real answers are cached, so a flaky
        // request retries fresh on the next open instead of missing for 24h.
        if (merged != null) cache[ck] = CacheEntry(System.currentTimeMillis(), merged)
        Logs.log(
            "MangaMeta",
            "manga metadata for \"" + title.take(80) + "\" from " + (merged?.source ?: "none") +
                " (anilist=" + (anilist != null) + " jikan=" + (jikan != null) + " kitsu=" + (kitsu != null) + " dex=" + (dex != null) + " pwdb=" + (pwdb != null) + ")",
        )
        return merged
    }

    fun mergeExternalFirst(base: MediaItem, ext: MangaMeta?): MediaItem {
        if (ext == null) return base
        val desc = ext.description?.takeIf { it.isNotBlank() }
            ?: base.overview?.takeIf { it.isNotBlank() } ?: base.overview
        val genres = if (ext.genres.isNotEmpty()) ext.genres else base.genres
        val poster = ext.coverUrl?.takeIf { it.isNotBlank() }
            ?: base.posterUrl?.takeIf { it.isNotBlank() } ?: base.posterUrl
        val rating = ext.rating ?: base.rating ?: base.rating
        if (desc == base.overview && genres == base.genres && poster == base.posterUrl && rating == base.rating) return base
        return base.copy(
            overview = desc,
            genres = genres,
            posterUrl = poster,
            rating = rating,
            metadataSource = ext.source,
        )
    }

    /** Extension titles carry site junk AniList never spells that way —
     *  "(Manhwa)", trailing "Novel", " - Asura", ... A cleaned variant is
     *  tried as a second query before falling down the (slow, throttled)
     *  Jikan/Kitsu/Dex chain. The 55+ gate still guards every hit, so a
     *  wrong spelling can never repaint the page. */
    internal fun cleanMangaTitle(raw: String): String {
        var t = raw.trim()
        t = t.replace(Regex("(?i)\\s*[\\(\\[\\{（【]\\s*(manhwa|manhua|manga|webtoon|novel|light\\s+novel|raw|official|oneshot|one[\\s\\-]*shot|doujinshi|adult|18\\+)\\s*[\\)\\]\\}）】]"), "")
        t = t.replace(Regex("(?i)\\s*[-–—|:]+\\s*(manhwa|manhua|manga|webtoon|novel|light\\s+novel|raw|official)\\s*$"), "")
        t = t.replace(Regex("(?i)\\s+(manhwa|manhua|webtoon|novel|light\\s+novel|comic|raw)\\s*$"), "")
        return t.replace(Regex("\\s{2,}"), " ").trim()
    }

    /** AniList search as a Page (top 8, best-by-gate): the top-1 Media query
     *  404s outright for titles AniList ranks oddly, which read as "no record".
     *  Only runs when metadata is wanted; characters still need a matched id. */
    private fun fetchAnilistManga(wanted: String): MangaMeta? {
        // Raw title first, cleaned variant second (site junk like "(Manhwa)"
        // or a trailing "Novel" never matches, but costs one cheap keyless
        // query to retry without). Best score across both wins.
        val queries = listOf(wanted, cleanMangaTitle(wanted))
            .map { it.trim() }.filter { it.isNotBlank() }.distinct()
        var best: JSONObject? = null
        var bestScore = 0
        for (q in queries) {
            val (media, score) = searchAnilistManga(q)
            if (score > bestScore) {
                bestScore = score
                best = media
            }
            if (bestScore >= 90) break
        }
        // Same 55+ gate as the anime chain: a mistranslation never repaints.
        val media = best ?: return null
        if (bestScore < 55) return null
        val desc = media.optString("description").trim().takeIf { it.isNotBlank() }
        val genres = (0 until (media.optJSONArray("genres")?.length() ?: 0))
            .mapNotNull { i -> media.optJSONArray("genres")?.optString(i)?.trim()?.takeIf { it.isNotBlank() } }
        val cover = media.optJSONObject("coverImage")?.optString("large")?.trim()?.takeIf { it.startsWith("http") }
        val score = media.optDouble("averageScore", 0.0).takeIf { it > 0 }?.div(10.0)
        return MangaMeta(desc, genres, cover, score, "AniList", media.optBoolean("isAdult", false), media.optInt("id", 0))
    }

    /** One AniList Page search, best record + its gate score (0 when blank). */
    private fun searchAnilistManga(query: String): Pair<JSONObject?, Int> {
        val raw = Http.postStringQuiet(
            "https://graphql.anilist.co",
            JSONObject().put("query", "query{Page(perPage:8){media(search:" + JSONObject.quote(query) +
                ",type:MANGA){id title{userPreferred english romaji native} synonyms " +
                "description(asHtml:false) averageScore genres isAdult countryOfOrigin " +
                "coverImage{large} startDate{year}}}}").toString(),
        ) ?: return null to 0
        val arr = runCatching {
            JSONObject(raw).optJSONObject("data")?.optJSONObject("Page")?.optJSONArray("media")
        }.getOrNull() ?: return null to 0
        var best: JSONObject? = null
        var bestScore = 0
        for (i in 0 until arr.length()) {
            val media = arr.optJSONObject(i) ?: continue
            val titles = media.optJSONObject("title")
            val aliases = buildSet {
                listOf(
                    titles?.optString("userPreferred"),
                    titles?.optString("english"),
                    titles?.optString("romaji"),
                    titles?.optString("native"),
                ).forEach { t ->
                    if (!t.isNullOrBlank() && !isNullWord(t)) add(t.trim())
                }
                val syns = media.optJSONArray("synonyms")
                for (k in 0 until (syns?.length() ?: 0)) {
                    syns?.optString(k)?.trim()?.takeIf { it.isNotBlank() && !isNullWord(it) }?.let { add(it) }
                }
            }
            if (aliases.isEmpty()) continue
            val s = AnimeMetadataRepository.matchAliases(query, aliases)
            if (s > bestScore) {
                bestScore = s
                best = media
            }
        }
        return best to bestScore
    }

    /** Jikan (MyAnimeList, keyless) is throttled to one call per 400ms so a
     *  burst of detail opens never trips its 3 req/s limit. */
    @Volatile
    private var lastJikanAt = 0L

    private fun jikanThrottle() {
        val wait = 400L - (System.currentTimeMillis() - lastJikanAt)
        if (wait > 0) runCatching { Thread.sleep(wait) }
        lastJikanAt = System.currentTimeMillis()
    }

    /** Title search on Jikan, same 55+ gate as the AniList step. Only runs
     *  when AniList missed, so normal loads pay nothing. */
    private fun fetchJikanManga(wanted: String): MangaMeta? {
        jikanThrottle()
        val q = URLEncoder.encode(wanted, "UTF-8")
        val raw = Http.getStringQuiet(
            "https://api.jikan.moe/v4/manga?q=$q&limit=5&order_by=members&sort=desc",
        ) ?: return null
        val arr = runCatching {
            JSONObject(raw).optJSONArray("data")
        }.getOrNull() ?: return null
        var best: JSONObject? = null
        var bestScore = 0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val aliases = buildSet {
                o.optString("title").trim().takeIf { it.isNotBlank() }?.let { add(it) }
                o.optString("title_english").trim().takeIf { it.isNotBlank() }?.let { add(it) }
                val syns = o.optJSONArray("title_synonyms")
                for (k in 0 until (syns?.length() ?: 0)) {
                    syns?.optString(k)?.trim()?.takeIf { it.isNotBlank() }?.let { add(it) }
                }
            }
            if (aliases.isEmpty()) continue
            val s = AnimeMetadataRepository.matchAliases(wanted, aliases)
            if (s > bestScore) {
                bestScore = s
                best = o
            }
        }
        if (best == null || bestScore < 55) return null
        val genres = (0 until (best.optJSONArray("genres")?.length() ?: 0))
            .mapNotNull { i -> best.optJSONArray("genres")?.optJSONObject(i)?.optString("name")?.trim()?.takeIf { it.isNotBlank() } }
        val cover = best.optJSONObject("images")?.optJSONObject("jpg")
            ?.optString("large_image_url")?.trim()?.takeIf { it.startsWith("http") }
        val score = best.optDouble("score", 0.0).takeIf { it > 0 }
        return MangaMeta(
            best.optString("synopsis").trim().takeIf { it.isNotBlank() },
            genres, cover, score, "Jikan", false, 0, best.optInt("mal_id", 0),
        )
    }

    /** Characters for a MAL id (Jikan, keyless). Role reads "Main"/"Supporting". */
    private fun fetchJikanCharacters(malId: Int): List<MangaCharacter> {
        jikanThrottle()
        val raw = Http.getStringQuiet("https://api.jikan.moe/v4/manga/$malId/characters")
            ?: return emptyList()
        val arr = runCatching {
            JSONObject(raw).optJSONArray("data")
        }.getOrNull() ?: return emptyList()
        val out = ArrayList<MangaCharacter>(24)
        for (i in 0 until arr.length()) {
            if (out.size >= 24) break
            val o = arr.optJSONObject(i) ?: continue
            val c = o.optJSONObject("character") ?: continue
            val name = c.optString("name").trim().takeIf { it.isNotBlank() } ?: continue
            val img = c.optJSONObject("images")?.optJSONObject("jpg")
                ?.optString("image_url")?.trim()?.takeIf { it.startsWith("http") }
            out += MangaCharacter(name, img, o.optString("role").trim().takeIf { it.isNotBlank() })
        }
        return out
    }

    /** MangaDex (keyless) metadata fallback: description, genres and cover.
     *  No characters — MangaDex's character data is too thin to trust. */
    /** Kitsu (keyless): description, genres and cover. No characters — its
     *  castings are too thin to trust, so characters still come from
     *  AniList/Jikan. Fires only when both missed. */
    private fun fetchKitsuManga(wanted: String): MangaMeta? {
        val q = URLEncoder.encode(wanted, "UTF-8")
        val raw = Http.getStringQuiet(
            "https://kitsu.io/api/edge/manga?filter[text]=$q&page[limit]=8&include=genres",
        ) ?: return null
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val arr = root.optJSONArray("data") ?: return null
        val genreNames = HashMap<String, String>()
        val inc = root.optJSONArray("included")
        for (i in 0 until (inc?.length() ?: 0)) {
            val g = inc?.optJSONObject(i) ?: continue
            if (g.optString("type") != "genres") continue
            val name = g.optJSONObject("attributes")?.optString("name")?.trim()
            if (!name.isNullOrBlank()) genreNames[g.optString("id")] = name
        }
        var best: JSONObject? = null
        var bestScore = 0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("type") != "manga") continue
            val a = o.optJSONObject("attributes") ?: continue
            val aliases = buildSet {
                a.optString("canonicalTitle").trim().takeIf { it.isNotBlank() }?.let { add(it) }
                val tt = a.optJSONObject("titles")
                if (tt != null) {
                    val ks = tt.keys()
                    while (ks.hasNext()) {
                        tt.optString(ks.next()).trim().takeIf { it.isNotBlank() }?.let { add(it) }
                    }
                }
                val ab = a.optJSONArray("abbreviatedTitles")
                for (k in 0 until (ab?.length() ?: 0)) {
                    ab?.optString(k)?.trim()?.takeIf { it.isNotBlank() }?.let { add(it) }
                }
                a.optString("slug").trim().takeIf { it.isNotBlank() }?.let { slug ->
                    slug.split("-").joinToString(" ") { w ->
                        w.replaceFirstChar { c -> c.uppercase() }
                    }.takeIf { it.isNotBlank() }?.let { add(it) }
                }
            }
            if (aliases.isEmpty()) continue
            val s = AnimeMetadataRepository.matchAliases(wanted, aliases)
            if (s > bestScore) {
                bestScore = s
                best = o
            }
        }
        if (best == null || bestScore < 55) return null
        val a = best.optJSONObject("attributes") ?: return null
        var desc: String? = a.optString("synopsis").trim().takeIf { it.isNotBlank() }
        val genres = (0 until (a.optJSONObject("relationships")?.optJSONObject("genres")
            ?.optJSONArray("data")?.length() ?: 0)).mapNotNull { i ->
            val id = a.optJSONObject("relationships")?.optJSONObject("genres")
                ?.optJSONArray("data")?.optJSONObject(i)?.optString("id")
            genreNames[id]
        }.take(6)
        val cover = a.optJSONObject("posterImage")?.optString("large")?.trim()?.takeIf { it.startsWith("http") }
            ?: a.optJSONObject("posterImage")?.optString("original")?.trim()?.takeIf { it.startsWith("http") }
        val score = a.optDouble("averageRating", 0.0).takeIf { it > 0 }?.let { if (it > 10) it / 10 else it }
        return MangaMeta(desc, genres, cover, score, "Kitsu", false, 0, 0)
    }

    private fun fetchDexManga(wanted: String): MangaMeta? {
        val q = URLEncoder.encode(wanted, "UTF-8")
        val raw = Http.getStringQuiet(
            "https://api.mangadex.org/manga?title=$q&limit=5&contentRating[]=safe" +
                "&contentRating[]=suggestive&includes[]=cover_art&order[relevance]=desc",
        ) ?: return null
        val arr = runCatching {
            JSONObject(raw).optJSONArray("data")
        }.getOrNull() ?: return null
        var bestId: String? = null
        var bestAttr: JSONObject? = null
        var bestScore = 0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val a = o.optJSONObject("attributes") ?: continue
            val titles = a.optJSONObject("title")
            val aliases = buildSet {
                if (titles != null) {
                    val keys = titles.keys()
                    while (keys.hasNext()) {
                        titles.optString(keys.next()).trim().takeIf { it.isNotBlank() }?.let { add(it) }
                    }
                }
                val alts = a.optJSONArray("altTitles")
                for (k in 0 until (alts?.length() ?: 0)) {
                    val t = alts?.optJSONObject(k) ?: continue
                    val keys = t.keys()
                    while (keys.hasNext()) {
                        t.optString(keys.next()).trim().takeIf { it.isNotBlank() }?.let { add(it) }
                    }
                }
            }
            if (aliases.isEmpty()) continue
            val s = AnimeMetadataRepository.matchAliases(wanted, aliases)
            if (s > bestScore) {
                bestScore = s
                bestId = o.optString("id")
                bestAttr = a
            }
        }
        if (bestAttr == null || bestScore < 55) return null
        val a = bestAttr
        val descObj = a.optJSONObject("description")
        var desc: String? = null
        if (descObj != null) {
            desc = descObj.optString("en").trim().takeIf { it.isNotBlank() }
            if (desc == null) {
                val keys = descObj.keys()
                while (keys.hasNext()) {
                    desc = descObj.optString(keys.next()).trim().takeIf { it.isNotBlank() }
                    if (desc != null) break
                }
            }
        }
        val genres = (0 until (a.optJSONArray("tags")?.length() ?: 0)).mapNotNull { i ->
            val t = a.optJSONArray("tags")?.optJSONObject(i) ?: return@mapNotNull null
            if (t.optJSONObject("attributes")?.optString("group") !in listOf("genre", "theme")) return@mapNotNull null
            t.optJSONObject("attributes")?.optJSONObject("name")?.optString("en")?.trim()?.takeIf { it.isNotBlank() }
        }.take(6)
        var cover: String? = null
        val rels = JSONObject(raw).optJSONArray("data")
        outer@ for (i in 0 until (rels?.length() ?: 0)) {
            val o = rels?.optJSONObject(i) ?: continue
            if (o.optString("id") != bestId) continue
            val rr = o.optJSONArray("relationships")
            for (k in 0 until (rr?.length() ?: 0)) {
                val r = rr?.optJSONObject(k) ?: continue
                if (r.optString("type") != "cover_art") continue
                val file = r.optJSONObject("attributes")?.optString("fileName")?.trim()
                if (!file.isNullOrBlank()) {
                    cover = "https://uploads.mangadex.org/covers/$bestId/$file"
                    break@outer
                }
            }
        }
        return MangaMeta(desc, genres, cover?.takeIf { it.startsWith("http") }, null, "MangaDex", false, 0, 0)
    }

    private fun fetchPwdb(wanted: String, key: String): MangaMeta? {
        val headers = mapOf("X-API-Key" to key)
        val q = URLEncoder.encode(wanted, "UTF-8")
        val searchRaw = Http.getStringQuiet("$BASE_URL/search?q=$q&type=pornhwa&limit=5", headers) ?: return null
        val arr = runCatching {
            JSONObject(searchRaw).optJSONObject("data")?.optJSONArray("pornhwa")
        }.getOrNull() ?: return null
        var bestSlug: String? = null
        var bestScore = 0
        var bestCover: String? = null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val t = o.optString("title").trim()
            if (t.isEmpty()) continue
            // One candidate spelling is enough here — the API already
            // searched; this only rejects a wrong-title hit.
            val s = AnimeMetadataRepository.matchAliases(wanted, setOf(t))
            if (s > bestScore) {
                bestScore = s
                bestSlug = o.optString("slug").trim().takeIf { it.isNotBlank() }
                bestCover = o.optString("coverImage").trim().takeIf { it.startsWith("http") }
            }
        }
        if (bestSlug == null || bestScore < 55) return null
        val detailRaw = Http.getStringQuiet("$BASE_URL/pornhwa/$bestSlug", headers)
            ?: return MangaMeta(coverUrl = bestCover, source = "PornHwaDB", isAdult = true, pwdbSlug = bestSlug)
        val data = runCatching { JSONObject(detailRaw).optJSONObject("data") }.getOrNull()
            ?: return MangaMeta(coverUrl = bestCover, source = "PornHwaDB", isAdult = true, pwdbSlug = bestSlug)
        val desc = data.optString("description").trim().takeIf { it.isNotBlank() }
        val tags = (0 until (data.optJSONArray("genreTags")?.length() ?: 0))
            .mapNotNull { i -> data.optJSONArray("genreTags")?.optString(i)?.trim()?.takeIf { it.isNotBlank() } }
        val cover = data.optString("coverImage").trim().takeIf { it.startsWith("http") } ?: bestCover
        val rating = data.optDouble("averageRating", 0.0).takeIf { it > 0 }
        return MangaMeta(desc, tags, cover, rating, "PornHwaDB", true, 0, 0, bestSlug)
    }

    suspend fun fetchCharacters(app: HikariApp, fallbackTitle: String, ext: MangaMeta? = null): List<MangaCharacter> =
        withContext(Dispatchers.IO) {
            val key = fallbackTitle.trim().lowercase()
            if (key.isBlank()) return@withContext emptyList()
            charCache[key]?.let { (at, list) ->
                if (System.currentTimeMillis() - at < TTL_MS) return@withContext list
            }
            // The detail screen passes its already-resolved enrichment: one
            // AniList search per open, not one per row (header + characters +
            // threads + reviews + hints used to fan out into five).
            val resolved = ext ?: runCatching { fetchExternal(app, fallbackTitle) }.getOrNull()
            // AniList first (keyless, every title); Jikan (MAL) when AniList
            // missed; PornHwaDB only when neither has a record. One retry per
            // step: a dropped request must not read as "no characters".
            var out = if ((resolved?.anilistId ?: 0) > 0) {
                runCatching { fetchAnilistCharacters(resolved!!.anilistId) }.getOrDefault(emptyList())
                    .ifEmpty {
                        runCatching {
                            kotlinx.coroutines.delay(800)
                            fetchAnilistCharacters(resolved!!.anilistId)
                        }.getOrDefault(emptyList())
                    }
            } else emptyList()
            if (out.isEmpty() && (resolved?.malId ?: 0) > 0) {
                out = runCatching { fetchJikanCharacters(resolved!!.malId) }.getOrDefault(emptyList())
            }
            if (out.isEmpty() && !resolved?.pwdbSlug.isNullOrBlank()) {
                val k = runCatching { app.store.pornhwaApiKey() }.getOrDefault("").trim()
                if (k.isNotEmpty()) {
                    out = runCatching { fetchPwdbCharacters(resolved!!.pwdbSlug!!, k) }.getOrDefault(emptyList())
                }
            }
            // Only real answers are cached: an empty list is "unknown", not
            // "none", and retries fresh on the next open.
            if (out.isNotEmpty()) charCache[key] = System.currentTimeMillis() to out
            Logs.log("MangaMeta", "manga characters for \"" + fallbackTitle.take(60) + "\": " + out.size)
            out
        }

    private fun fetchAnilistCharacters(mediaId: Int): List<MangaCharacter> {
        val query = "query{Media(id:" + mediaId +
            ",type:MANGA){characters(sort:[ROLE,RELEVANCE],perPage:24)" +
            "{edges{role node{name{full} image{large}}}}}}"
        val raw = Http.postStringQuiet(
            "https://graphql.anilist.co",
            JSONObject().put("query", query).toString(),
        ) ?: return emptyList()
        val edges = runCatching {
            JSONObject(raw).optJSONObject("data")?.optJSONObject("Media")
                ?.optJSONObject("characters")?.optJSONArray("edges")
        }.getOrNull() ?: return emptyList()
        val out = ArrayList<MangaCharacter>(24)
        for (i in 0 until edges.length()) {
            if (out.size >= 24) break
            val edge = edges.optJSONObject(i) ?: continue
            val node = edge.optJSONObject("node") ?: continue
            val name = node.optJSONObject("name")?.optString("full")?.trim().orEmpty()
            if (name.isBlank()) continue
            val img = node.optJSONObject("image")?.optString("large")?.trim()?.takeIf { it.startsWith("http") }
            val role = edge.optString("role").trim().takeIf { it.isNotBlank() }
            out += MangaCharacter(name, img, role)
        }
        return out
    }

    private fun fetchPwdbCharacters(slug: String, key: String): List<MangaCharacter> {
        val raw = Http.getStringQuiet(
            "$BASE_URL/pornhwa/$slug/characters",
            mapOf("X-API-Key" to key),
        ) ?: return emptyList()
        val arr = runCatching {
            JSONObject(raw).optJSONArray("data")
        }.getOrNull() ?: return emptyList()
        val out = ArrayList<MangaCharacter>(24)
        for (i in 0 until arr.length()) {
            if (out.size >= 24) break
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name").trim()
            if (name.isBlank()) continue
            val img = o.optString("image").trim().takeIf { it.startsWith("http") }
            val role = o.optString("role").trim().takeIf { it.isNotBlank() && it != "null" }
            out += MangaCharacter(name, img, role)
        }
        return out
    }

    /**
     * The Comments tab's AniList rows: forum threads about this title, newest
     * reply first (the reference layout's Threads section). Keyless.
     */
    suspend fun fetchThreads(app: HikariApp, fallbackTitle: String, ext: MangaMeta? = null): List<MangaThread> =
        withContext(Dispatchers.IO) {
            val key = fallbackTitle.trim().lowercase()
            if (key.isBlank()) return@withContext emptyList()
            threadCache[key]?.let { (at, list) ->
                if (System.currentTimeMillis() - at < THREAD_TTL_MS) return@withContext list
            }
            val id = (ext ?: runCatching { fetchExternal(app, fallbackTitle) }.getOrNull())?.anilistId ?: 0
            val out = if (id > 0) runCatching { fetchAnilistThreads(id) }.getOrDefault(emptyList())
            else emptyList()
            threadCache[key] = System.currentTimeMillis() to out
            out
        }

    private fun fetchAnilistThreads(mediaId: Int): List<MangaThread> {
        val query = "query{Page(perPage:20){threads(mediaCategoryId:" + mediaId +
            ",sort:REPLIED_AT_DESC){id title replyCount viewCount repliedAt replyUser{name}}}}"
        val raw = Http.postStringQuiet(
            "https://graphql.anilist.co",
            JSONObject().put("query", query).toString(),
        ) ?: return emptyList()
        val arr = runCatching {
            JSONObject(raw).optJSONObject("data")?.optJSONObject("Page")?.optJSONArray("threads")
        }.getOrNull() ?: return emptyList()
        val out = ArrayList<MangaThread>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optInt("id", 0)
            val title = o.optString("title").trim()
            if (id <= 0 || title.isBlank()) continue
            out += MangaThread(
                id = id,
                title = title,
                replyCount = o.optInt("replyCount", 0),
                viewCount = o.optInt("viewCount", 0),
                replyUser = o.optJSONObject("replyUser")?.optString("name")?.trim()?.takeIf { it.isNotBlank() },
                repliedAtSec = o.optLong("repliedAt", 0L),
            )
        }
        return out
    }

    /**
     * The Comments tab's PornHwaDB rows (adult titles with a key): user
     * reviews, newest first. Read-only — writing goes through AniList.
     */
    suspend fun fetchReviews(app: HikariApp, fallbackTitle: String, ext: MangaMeta? = null): List<MangaReview> =
        withContext(Dispatchers.IO) {
            val key = fallbackTitle.trim().lowercase()
            if (key.isBlank()) return@withContext emptyList()
            reviewCache[key]?.let { (at, list) ->
                if (System.currentTimeMillis() - at < THREAD_TTL_MS) return@withContext list
            }
            val resolved = ext ?: runCatching { fetchExternal(app, fallbackTitle) }.getOrNull()
            val slug = resolved?.pwdbSlug?.takeIf { it.isNotBlank() }
            val apiKey = runCatching { app.store.pornhwaApiKey() }.getOrDefault("").trim()
            val out = if (slug != null && apiKey.isNotEmpty()) {
                runCatching { fetchPwdbReviews(slug, apiKey) }.getOrDefault(emptyList())
            } else emptyList()
            reviewCache[key] = System.currentTimeMillis() to out
            out
        }

    private fun fetchPwdbReviews(slug: String, key: String): List<MangaReview> {
        val raw = Http.getStringQuiet(
            "$BASE_URL/pornhwa/$slug/reviews?limit=20&sort=date&order=desc",
            mapOf("X-API-Key" to key),
        ) ?: return emptyList()
        val arr = runCatching {
            JSONObject(raw).optJSONArray("data")
        }.getOrNull() ?: return emptyList()
        val out = ArrayList<MangaReview>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val text = o.optString("reviewText").trim()
            if (text.isBlank()) continue
            out += MangaReview(
                user = o.optString("userDisplayName").trim().takeIf { it.isNotBlank() } ?: "Anonymous",
                text = text,
                rating = o.optDouble("userRating", Double.NaN).takeIf { !it.isNaN() },
                createdAt = o.optString("createdAt").trim().take(10),
            )
        }
        return out
    }

    suspend fun fetchChapterHints(app: HikariApp, fallbackTitle: String, ext: MangaMeta? = null): Map<Int, String> =
        withContext(Dispatchers.IO) {
            val key = fallbackTitle.trim().lowercase()
            if (key.isBlank()) return@withContext emptyMap()
            hintCache[key]?.let { (at, map) ->
                if (System.currentTimeMillis() - at < TTL_MS) return@withContext map
            }
            val resolved = ext ?: runCatching { fetchExternal(app, fallbackTitle) }.getOrNull()
            val slug = resolved?.pwdbSlug?.takeIf { it.isNotBlank() }
            val apiKey = runCatching { app.store.pornhwaApiKey() }.getOrDefault("").trim()
            val out = LinkedHashMap<Int, String>()
            if (slug != null && apiKey.isNotEmpty()) {
                runCatching {
                    val raw = Http.getStringQuiet(
                        "$BASE_URL/pornhwa/$slug/chapters?all=true&limit=100",
                        mapOf("X-API-Key" to apiKey),
                    ) ?: return@runCatching
                    val arr = JSONObject(raw).optJSONArray("data") ?: return@runCatching
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val n = o.optInt("chapterStart", -1)
                        val d = o.optString("description").trim()
                        if (n > 0 && d.isNotBlank() && out.size < 500) out.putIfAbsent(n, d)
                    }
                }
            }
            hintCache[key] = System.currentTimeMillis() to out
            out
        }

    suspend fun postThread(
        app: HikariApp,
        fallbackTitle: String,
        threadTitle: String,
        body: String,
    ): Result<Int> = withContext(Dispatchers.IO) {
        val mediaId = runCatching { fetchExternal(app, fallbackTitle) }.getOrNull()?.anilistId ?: 0
        if (mediaId <= 0) {
            return@withContext Result.failure(Exception("AniList doesn't know this title, so there's nowhere to post it."))
        }
        val token = runCatching { app.store.trackers() }.getOrNull()
            ?.firstOrNull { it.kind == TrackerKind.ANILIST }?.token.orEmpty().trim()
        if (token.isBlank()) {
            return@withContext Result.failure(Exception("NOT_SIGNED_IN"))
        }
        val payload = JSONObject()
            .put("query", "mutation(\$t:String,\$b:String,\$m:[Int]){SaveThread(title:\$t,body:\$b,mediaCategories:\$m){id}}")
            .put(
                "variables",
                JSONObject()
                    .put("t", threadTitle.trim())
                    .put("b", body.trim())
                    .put("m", org.json.JSONArray().put(mediaId)),
            ).toString()
        val raw: String? = try {
            Http.postStringQuiet(
                "https://graphql.anilist.co",
                payload,
                mapOf("Authorization" to "Bearer $token", "Accept" to "application/json"),
            )
        } catch (e: Exception) {
            null
        }
        if (raw == null) {
            return@withContext Result.failure(Exception("AniList didn't answer — check the connection and try again."))
        }
        val root = runCatching { JSONObject(raw) }.getOrNull()
            ?: return@withContext Result.failure(Exception("AniList answered something unreadable."))
        val gqlErr = root.optJSONArray("errors")?.optJSONObject(0)?.optString("message")?.trim()
        if (!gqlErr.isNullOrBlank()) {
            val expired = gqlErr.contains("Invalid token", true) || root.toString().contains("Unauthorized", true)
            return@withContext Result.failure(
                Exception(if (expired) "The AniList sign-in expired — sign out and sign in again." else "AniList refused it: $gqlErr")
            )
        }
        val id = root.optJSONObject("data")?.optJSONObject("SaveThread")?.optInt("id", 0) ?: 0
        if (id <= 0) {
            return@withContext Result.failure(Exception("AniList didn't create the thread."))
        }
        threadCache.remove(fallbackTitle.trim().lowercase())
        Result.success(id)
    }

    /**
     * 200 = the key works, 401 = rejected, anything else = network/server
     * trouble. Never logs the key.
     */
    suspend fun checkApiKey(key: String): Int = withContext(Dispatchers.IO) {
        val k = key.trim()
        if (k.isEmpty()) return@withContext 0
        runCatching {
            Http.getQuiet(
                "$BASE_URL/search?q=test&type=pornhwa&limit=1",
                mapOf("X-API-Key" to k),
            ).use { it.code }
        }.getOrDefault(-1)
    }
}
