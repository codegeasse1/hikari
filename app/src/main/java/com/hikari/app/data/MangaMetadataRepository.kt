package com.hikari.app.data

import com.hikari.app.HikariApp
import com.hikari.app.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Manga metadata with no login: keyless AniList manga records for every
 * title, plus PornHwaDB for adult titles when the user pasted their own
 * free key (Settings → Integrations → Metadata sources).
 *
 * External metadata WINS: AniList/PornHwaDB blurbs, genres, covers and
 * scores replace the provider's own row, whose text is only the fallback
 * when neither source knows the title. A candidate that cannot score 55+
 * against the known spellings never paints the row. The key is never
 * logged, only sent to pornhwadb.com in the `X-API-Key` header.
 */
object MangaMetadataRepository {
    /** Where the user fetches their own free key (opened from Settings). */
    const val GET_KEY_URL = "https://pornhwadb.com/profile?tab=settings&section=developer"
    private const val BASE_URL = "https://pornhwadb.com/api/v1"

    data class MangaMeta(
        val description: String? = null,
        val genres: List<String> = emptyList(),
        val coverUrl: String? = null,
        val rating: Double? = null,
        val source: String = "AniList",
        val isAdult: Boolean = false,
        /** AniList media id behind this record (0 when the AniList step missed). */
        val anilistId: Int = 0,
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

    /**
     * The external record for [fallbackTitle] (AniList, plus PornHwaDB for
     * adult/unmatched titles when its key is set), or null when neither
     * source knows it. Cached per title per day. Run this alongside the
     * provider fetch (see the detail screen) so it costs no extra wait.
     */
    suspend fun fetchExternal(app: HikariApp, fallbackTitle: String): MangaMeta? {
        val title = fallbackTitle.trim()
        if (title.isBlank()) return null
        val store = app.store
        val wantAnilist = runCatching { store.mangaAnilist() }.getOrDefault(true)
        val wantPwdb = runCatching { store.adultManhwa() }.getOrDefault(false)
        if (!wantAnilist && !wantPwdb) return null
        val pwdbKey = if (wantPwdb) runCatching { store.pornhwaApiKey() }.getOrDefault("").trim() else ""
        // The key state rides in the cache key: saving a key later must not
        // serve the keyless answer (and clearing it must not serve the
        // keyed one).
        val ck = (if (pwdbKey.isNotEmpty()) "P:" else "A:") + title.lowercase()
        cache[ck]?.let { if (System.currentTimeMillis() - it.at < TTL_MS) return it.meta }
        val anilist = if (wantAnilist) {
            runCatching { withContext(Dispatchers.IO) { fetchAnilistManga(title) } }.getOrNull()
        } else null
        // PornHwaDB is the adult-title source: it runs when AniList calls
        // the title adult or has no record of it at all — never for a
        // mainstream title AniList already covers, so everyday manga cost
        // one keyless lookup, not two.
        val pwdb = if (pwdbKey.isNotEmpty() && (anilist == null || anilist.isAdult)) {
            runCatching { withContext(Dispatchers.IO) { fetchPwdb(title, pwdbKey) } }.getOrNull()
        } else null
        val merged = if (anilist == null && pwdb == null) null else MangaMeta(
            description = pwdb?.description ?: anilist?.description,
            genres = if (!pwdb?.genres.isNullOrEmpty()) pwdb.genres else anilist?.genres.orEmpty(),
            coverUrl = pwdb?.coverUrl ?: anilist?.coverUrl,
            rating = pwdb?.rating ?: anilist?.rating,
            source = if (pwdb != null) "PornHwaDB" else "AniList",
            isAdult = (anilist?.isAdult == true) || pwdb != null,
            anilistId = anilist?.anilistId ?: 0,
            pwdbSlug = pwdb?.pwdbSlug,
        )
        cache[ck] = CacheEntry(System.currentTimeMillis(), merged)
        Logs.log(
            "MangaMeta",
            "manga metadata for \"" + title.take(80) + "\" from " + (merged?.source ?: "none") +
                " (anilist=" + (anilist != null) + " pwdb=" + (pwdb != null) + ")",
        )
        return merged
    }

    /**
     * The detail page's row: the external record wins wherever it has
     * something, the provider's row is only the fallback per field. Null
     * [ext] returns [base] untouched.
     */
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

    private fun fetchAnilistManga(wanted: String): MangaMeta? {
        val query = "query{Media(search:" + JSONObject.quote(wanted) +
            ",type:MANGA){id title{userPreferred english romaji native} synonyms " +
            "description(asHtml:false) averageScore genres isAdult countryOfOrigin " +
            "coverImage{large} startDate{year}}}"
        val raw = Http.postStringQuiet(
            "https://graphql.anilist.co",
            JSONObject().put("query", query).toString(),
        ) ?: return null
        val media = runCatching {
            JSONObject(raw).optJSONObject("data")?.optJSONObject("Media")
        }.getOrNull() ?: return null
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
            for (i in 0 until (syns?.length() ?: 0)) {
                syns?.optString(i)?.trim()?.takeIf { it.isNotBlank() && !isNullWord(it) }?.let { add(it) }
            }
        }
        // Same 55+ gate as the anime chain: a mistranslation never repaints.
        if (aliases.isEmpty() || AnimeMetadataRepository.matchAliases(wanted, aliases) < 55) return null
        val desc = media.optString("description").trim().takeIf { it.isNotBlank() }
        val genres = (0 until (media.optJSONArray("genres")?.length() ?: 0))
            .mapNotNull { i -> media.optJSONArray("genres")?.optString(i)?.trim()?.takeIf { it.isNotBlank() } }
        val cover = media.optJSONObject("coverImage")?.optString("large")?.trim()?.takeIf { it.startsWith("http") }
        val score = media.optDouble("averageScore", 0.0).takeIf { it > 0 }?.div(10.0)
        return MangaMeta(desc, genres, cover, score, "AniList", media.optBoolean("isAdult", false), media.optInt("id", 0))
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
        return MangaMeta(desc, tags, cover, rating, "PornHwaDB", true, 0, bestSlug)
    }

    /**
     * The row of circular portraits under the genres: AniList characters for
     * every resolved title, PornHwaDB characters for adult titles with a key.
     * Empty when neither source knows the title — the section then stays hidden.
     */
    suspend fun fetchCharacters(app: HikariApp, fallbackTitle: String): List<MangaCharacter> =
        withContext(Dispatchers.IO) {
            val key = fallbackTitle.trim().lowercase()
            if (key.isBlank()) return@withContext emptyList()
            charCache[key]?.let { (at, list) ->
                if (System.currentTimeMillis() - at < TTL_MS) return@withContext list
            }
            val ext = runCatching { fetchExternal(app, fallbackTitle) }.getOrNull()
            // AniList first (keyless, every title); PornHwaDB only when AniList
            // has no record but the adult DB does.
            var out = if ((ext?.anilistId ?: 0) > 0) {
                runCatching { fetchAnilistCharacters(ext!!.anilistId) }.getOrDefault(emptyList())
            } else emptyList()
            if (out.isEmpty() && !ext?.pwdbSlug.isNullOrBlank()) {
                val k = runCatching { app.store.pornhwaApiKey() }.getOrDefault("").trim()
                if (k.isNotEmpty()) {
                    out = runCatching { fetchPwdbCharacters(ext!!.pwdbSlug!!, k) }.getOrDefault(emptyList())
                }
            }
            charCache[key] = System.currentTimeMillis() to out
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
    suspend fun fetchThreads(app: HikariApp, fallbackTitle: String): List<MangaThread> =
        withContext(Dispatchers.IO) {
            val key = fallbackTitle.trim().lowercase()
            if (key.isBlank()) return@withContext emptyList()
            threadCache[key]?.let { (at, list) ->
                if (System.currentTimeMillis() - at < THREAD_TTL_MS) return@withContext list
            }
            val id = runCatching { fetchExternal(app, fallbackTitle) }.getOrNull()?.anilistId ?: 0
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
    suspend fun fetchReviews(app: HikariApp, fallbackTitle: String): List<MangaReview> =
        withContext(Dispatchers.IO) {
            val key = fallbackTitle.trim().lowercase()
            if (key.isBlank()) return@withContext emptyList()
            reviewCache[key]?.let { (at, list) ->
                if (System.currentTimeMillis() - at < THREAD_TTL_MS) return@withContext list
            }
            val ext = runCatching { fetchExternal(app, fallbackTitle) }.getOrNull()
            val slug = ext?.pwdbSlug?.takeIf { it.isNotBlank() }
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

    /**
     * External chapter subtitles by chapter number: PornHwaDB scene
     * descriptions keyed by their starting chapter. AniList publishes no
     * per-chapter titles for manga, so it contributes nothing here — the
     * extension's own rows stand wherever this map has no entry, which is
     * why the list still paints instantly.
     */
    suspend fun fetchChapterHints(app: HikariApp, fallbackTitle: String): Map<Int, String> =
        withContext(Dispatchers.IO) {
            val key = fallbackTitle.trim().lowercase()
            if (key.isBlank()) return@withContext emptyMap()
            hintCache[key]?.let { (at, map) ->
                if (System.currentTimeMillis() - at < TTL_MS) return@withContext map
            }
            val ext = runCatching { fetchExternal(app, fallbackTitle) }.getOrNull()
            val slug = ext?.pwdbSlug?.takeIf { it.isNotBlank() }
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

    /**
     * Posts a forum thread about this title to AniList — the Comments tab's
     * "write" path. Needs the title resolved on AniList AND the user signed
     * in to AniList (Settings → Trackers); otherwise the Result carries the
     * sentence the dialog shows. Returns the new thread id on success.
     */
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
