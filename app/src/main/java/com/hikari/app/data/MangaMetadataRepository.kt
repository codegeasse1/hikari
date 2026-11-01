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
    )

    private data class CacheEntry(val at: Long, val meta: MangaMeta?)
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private const val TTL_MS = 24 * 60 * 60 * 1000L

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
            ",type:MANGA){title{userPreferred english romaji native} synonyms " +
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
        return MangaMeta(desc, genres, cover, score, "AniList", media.optBoolean("isAdult", false))
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
            ?: return MangaMeta(coverUrl = bestCover, source = "PornHwaDB", isAdult = true)
        val data = runCatching { JSONObject(detailRaw).optJSONObject("data") }.getOrNull()
            ?: return MangaMeta(coverUrl = bestCover, source = "PornHwaDB", isAdult = true)
        val desc = data.optString("description").trim().takeIf { it.isNotBlank() }
        val tags = (0 until (data.optJSONArray("genreTags")?.length() ?: 0))
            .mapNotNull { i -> data.optJSONArray("genreTags")?.optString(i)?.trim()?.takeIf { it.isNotBlank() } }
        val cover = data.optString("coverImage").trim().takeIf { it.startsWith("http") } ?: bestCover
        val rating = data.optDouble("averageRating", 0.0).takeIf { it > 0 }
        return MangaMeta(desc, tags, cover, rating, "PornHwaDB", true)
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
