package com.hikari.app.data

import com.hikari.app.HikariApp
import com.hikari.app.net.Http
import com.hikari.app.nuvio.BangumiMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

object TrackerAnimeResolver {
    private val trackerProviders = setOf("anilist", "simkl", "mal", "kitsu", "shikimori")

    data class Detail(
        val overview: String? = null,
        val genres: List<String> = emptyList(),
        val year: Int? = null,
        val posterUrl: String? = null,
        val backdropUrl: String? = null,
        val rating: Double? = null,
    )

    fun isTrackerAnime(item: MediaItem): Boolean {
        if (item.providerId.lowercase() !in trackerProviders) return false
        if (item.type != MediaType.SERIES) return false
        return item.rawType.contains("anime", true) || item.providerId.equals("anilist", true)
    }

    suspend fun fallbackEpisodes(item: MediaItem): List<Episode>? = withContext(Dispatchers.IO) {
        if (!isTrackerAnime(item)) return@withContext null
        val count = episodeCount(item) ?: return@withContext null
        if (count < 1 || count > 3000) return@withContext null
        List(count) { i -> Episode(number = i + 1, id = item.id + "#e" + (i + 1), name = "Episode " + (i + 1), season = 1) }
    }

    suspend fun trackerEpisodes(item: MediaItem): List<Episode>? = withContext(Dispatchers.IO) {
        if (!isTrackerAnime(item)) return@withContext null
        bangumiEpisodes(item)?.takeIf { it.size >= 3 }?.let { return@withContext it }
        simklCountEpisodes(item)?.let { return@withContext it }
        episodeCount(item)?.takeIf { it in 1..3000 }?.let { count ->
            return@withContext List(count) { i ->
                Episode(number = i + 1, id = item.id + "#e" + (i + 1), name = "Episode " + (i + 1), season = 1)
            }
        }
        null
    }

        suspend fun detail(item: MediaItem): Detail? {
        return null
    }

    data class TmdbRef(val tmdbId: String, val mediaType: String)

    suspend fun simklTmdbRef(item: MediaItem): TmdbRef? = withContext(Dispatchers.IO) {
        val clientId = simklClientId()
        if (clientId.isBlank()) return@withContext null
        val o = simklAnimeObject(item, clientId) ?: return@withContext null
        val tmdb = o.optJSONObject("ids")?.optInt("tmdb", 0)?.takeIf { it > 0 } ?: return@withContext null
        val kind = o.optString("anime_type").trim().lowercase()
            .ifBlank { o.optString("type").trim().lowercase() }
        val mediaType = if (kind == "movie") "movie" else "tv"
        TmdbRef(tmdb.toString(), mediaType)
    }

    private fun simklAnimeObject(item: MediaItem, clientId: String): JSONObject? {
        val direct = if (item.providerId.equals("simkl", true)) {
            item.id.toIntOrNull()?.takeIf { it > 0 }
        } else {
            null
        }
        val simklId = direct ?: simklIdFor(item.searchTitle, clientId) ?: return null
        val raw = Http.getStringQuiet(
            "https://api.simkl.com/anime/" + simklId + "?extended=full&client_id=" + URLEncoder.encode(clientId, "UTF-8"),
        ) ?: return null
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        return o.optJSONObject("anime") ?: o
    }

    private suspend fun simklClientId(): String =
        runCatching {
            HikariApp.instance.store.trackerClients()
                .firstOrNull { it.kind == TrackerKind.SIMKL }?.id.orEmpty()
        }.getOrDefault("")

    private suspend fun simklDetail(item: MediaItem): Detail? {
        val clientId = simklClientId()
        if (clientId.isBlank()) return null
        val data = simklAnimeObject(item, clientId) ?: return null
        val overview = data.optString("overview").trim().takeIf { it.isNotBlank() }
        val genres = (0 until (data.optJSONArray("genres")?.length() ?: 0))
            .mapNotNull { i -> data.optJSONArray("genres")?.optString(i)?.trim()?.takeIf { it.isNotBlank() } }
        val year = data.optInt("year", 0).takeIf { it > 0 }
            ?: data.optString("first_aired").take(4).toIntOrNull()
            ?: data.optString("year").take(4).toIntOrNull()
        val rating = data.optJSONObject("ratings")?.optJSONObject("simkl")
            ?.optDouble("rating", 0.0)?.takeIf { it > 0 }
        val poster = data.optString("poster").trim().takeIf { it.isNotBlank() && it != "null" }
        val posterUrl = when {
            poster == null -> null
            poster.startsWith("http") -> poster
            else -> "https://wsrv.nl/?url=https://simkl.in/posters/" + poster + "_m.webp&q=90"
        }
        if (overview == null && genres.isEmpty() && year == null && rating == null) return null
        return Detail(overview, genres, year, posterUrl, null, rating)
    }

    private fun simklIdFor(title: String, clientId: String): Int? {
        val q = stripSequelSuffix(title).ifBlank { title.trim() }
        if (q.isBlank()) return null
        val raw = Http.getStringQuiet(
            "https://api.simkl.com/search/anime?q=" + URLEncoder.encode(q, "UTF-8") +
                "&client_id=" + URLEncoder.encode(clientId, "UTF-8"),
        ) ?: return null
        val arr = runCatching { org.json.JSONArray(raw) }.getOrNull() ?: return null
        if (arr.length() == 0) return null
        // Simkl returns candidates in its own order — for sequel / donghua
        // titles the first hit is not always the right show, so the best
        // title match wins instead of blindly taking index 0.
        var best = arr.optJSONObject(0)
        var bestScore = -1
        for (i in 0 until minOf(arr.length(), 10)) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("title").trim()
            val score = if (name.isBlank()) 0 else sequelSimilarity(q, name)
            if (score > bestScore) {
                bestScore = score
                best = o
            }
        }
        val pick = best ?: return null
        return pick.optJSONObject("ids")?.optInt("simkl", 0)?.takeIf { it > 0 }
            ?: pick.optInt("id", 0).takeIf { it > 0 }
    }
    private fun stripSequelSuffix(title: String): String =
        title.trim()
            .replace(Regex("""(?i)\s*[:\-–—]?\s*\bseason\s*\d+\s*$"""), "")
            .replace(Regex("""(?i)\s*\b\d+(?:st|nd|rd|th)\s+season\s*$"""), "")
            .replace(Regex("""(?i)\s*\b(?:part|cour)\s*\d+\s*$"""), "")
            .replace(Regex("""(?i)\s*\bs\d{1,2}\s*$"""), "")
            .trim()
    private fun sequelSimilarity(a0: String, b0: String): Int {
        val a = a0.lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()
        val b = b0.lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()
        if (a.isBlank() || b.isBlank()) return 0
        if (a == b) return 100
        if (a.contains(b) || b.contains(a)) return 90
        val aa = a.split(" ").filter { it.length > 2 }.toSet()
        val bb = b.split(" ").filter { it.length > 2 }.toSet()
        if (aa.isEmpty() || bb.isEmpty()) return 0
        return aa.intersect(bb).size * 100 / maxOf(aa.size, bb.size)
    }

    private suspend fun simklCountEpisodes(item: MediaItem): List<Episode>? {
        val clientId = simklClientId()
        if (clientId.isBlank()) return null
        val data = simklAnimeObject(item, clientId) ?: return null
        val total = data.optInt("total_episodes", 0)
        if (total < 1 || total > 3000) return null
        return List(total) { i ->
            Episode(number = i + 1, id = item.id + "#e" + (i + 1), name = "Episode " + (i + 1), season = 1)
        }
    }

    private val cjk = Regex("[\u3040-\u30ff\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]")

        private suspend fun bangumiEpisodes(item: MediaItem): List<Episode>? {
        return null
    }

    private suspend fun anilistDetail(item: MediaItem): Detail? {
        if (!item.providerId.equals("anilist", true)) return null
        val id = item.id.toIntOrNull()?.takeIf { it > 0 } ?: return null
        val query = "query{Media(id:" + id + ",type:ANIME){description genres startDate{year} averageScore coverImage{large} bannerImage}}"
        val raw = Http.postStringQuiet(
            "https://graphql.anilist.co",
            JSONObject().put("query", query).toString(),
        ) ?: return null
        val media = runCatching {
            JSONObject(raw).optJSONObject("data")?.optJSONObject("Media")
        }.getOrNull() ?: return null
        return parseAnilistMedia(media)
    }
    private suspend fun anilistSearchDetail(title: String): Detail? {
        val t = title.trim()
        if (t.isBlank()) return null
        val query = "query{Media(search:" + JSONObject.quote(t) + ",type:ANIME){title{userPreferred english romaji native} synonyms description genres startDate{year} averageScore coverImage{large} bannerImage}}"
        val raw = Http.postStringQuiet(
            "https://graphql.anilist.co",
            JSONObject().put("query", query).toString(),
        ) ?: return null
        val media = runCatching {
            JSONObject(raw).optJSONObject("data")?.optJSONObject("Media")
        }.getOrNull() ?: return null
        val titles = media.optJSONObject("title")
        val aliases = buildSet {
            listOf(titles?.optString("userPreferred"), titles?.optString("english"), titles?.optString("romaji"), titles?.optString("native")).forEach { v -> if (!v.isNullOrBlank()) add(v.trim()) }
            val syns = media.optJSONArray("synonyms")
            for (i in 0 until (syns?.length() ?: 0)) syns?.optString(i)?.trim()?.takeIf { it.isNotBlank() }?.let { add(it) }
        }
        if (AnimeMetadataRepository.matchAliases(t, aliases) < 55) return null
        return parseAnilistMedia(media)
    }
    private fun parseAnilistMedia(media: JSONObject): Detail? {
        val overview = media.optString("description").trim()
            .replace(Regex("<br[^>]*>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&").replace("&quot;", "\"").replace("&#039;", "'").replace("&lt;", "<").replace("&gt;", ">")
            .trim().takeIf { it.isNotBlank() }
        val genres = (0 until (media.optJSONArray("genres")?.length() ?: 0))
            .mapNotNull { i -> media.optJSONArray("genres")?.optString(i)?.trim()?.takeIf { it.isNotBlank() } }
        val year = media.optJSONObject("startDate")?.optInt("year", 0)?.takeIf { it > 0 }
        val rating = media.optDouble("averageScore", 0.0).takeIf { it > 0 }?.div(10.0)
        val poster = media.optJSONObject("coverImage")?.optString("large")?.trim()?.takeIf { it.startsWith("http") }
        val backdrop = media.optString("bannerImage").trim().takeIf { it.startsWith("http") }
        if (overview == null && genres.isEmpty() && year == null && rating == null) return null
        return Detail(overview, genres, year, poster, backdrop, rating)
    }

    private suspend fun episodeCount(item: MediaItem): Int? {
        return when (item.providerId.lowercase()) {
            "anilist" -> {
                val id = item.id.toIntOrNull() ?: return null
                val q = "query($id:Int){Media(id:$id){episodes}}"
                val raw = Http.postStringQuiet(
                    "https://graphql.anilist.co",
                    JSONObject().put("query", q).put("variables", JSONObject().put("id", id)).toString(),
                    mapOf("Accept" to "application/json", "Content-Type" to "application/json"),
                ) ?: return null
                JSONObject(raw).optJSONObject("data")?.optJSONObject("Media")?.optInt("episodes", 0)?.takeIf { it > 0 }
            }
            "mal" -> {
                val id = item.id.toIntOrNull() ?: return null
                val raw = Http.getStringQuiet("https://api.jikan.moe/v4/anime/" + id) ?: return null
                JSONObject(raw).optJSONObject("data")?.optInt("episodes", 0)?.takeIf { it > 0 }
            }
            else -> null
        }
    }
}
