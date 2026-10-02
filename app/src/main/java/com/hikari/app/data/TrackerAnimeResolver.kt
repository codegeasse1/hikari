package com.hikari.app.data

import com.hikari.app.HikariApp
import com.hikari.app.net.Http
import com.hikari.app.nuvio.BangumiMeta
import kotlinx.coroutines.Dispatchers
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

    suspend fun detail(item: MediaItem): Detail? = withContext(Dispatchers.IO) {
        if (!isTrackerAnime(item)) return@withContext null
        if (!runCatching { HikariApp.instance.store.animeMetadataEnabled() }.getOrDefault(true)) return@withContext null
        val mode = runCatching { HikariApp.instance.store.animeMetadataSource() }.getOrDefault("auto").lowercase()
        if (mode != "anilist") {
            simklDetail(item)?.let { return@withContext it }
        }
        anilistDetail(item)
    }

    private fun simklClientId(): String =
        runCatching {
            HikariApp.instance.store.trackerClients()
                .firstOrNull { it.kind == TrackerKind.SIMKL }?.id.orEmpty()
        }.getOrDefault("")

    private suspend fun simklDetail(item: MediaItem): Detail? {
        val clientId = simklClientId()
        if (clientId.isBlank()) return null
        val simklId = if (item.providerId.equals("simkl", true)) {
            item.id.toIntOrNull()?.takeIf { it > 0 }
        } else {
            null
        } ?: simklIdFor(item.searchTitle, clientId) ?: return null
        val raw = Http.getStringQuiet(
            "https://api.simkl.com/anime/" + simklId + "?extended=full&client_id=" + URLEncoder.encode(clientId, "UTF-8"),
        ) ?: return null
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val data = o.optJSONObject("anime") ?: o
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
        if (title.isBlank()) return null
        val raw = Http.getStringQuiet(
            "https://api.simkl.com/search/anime?q=" + URLEncoder.encode(title, "UTF-8") +
                "&client_id=" + URLEncoder.encode(clientId, "UTF-8"),
        ) ?: return null
        val arr = runCatching { org.json.JSONArray(raw) }.getOrNull() ?: return null
        if (arr.length() == 0) return null
        val first = arr.optJSONObject(0) ?: return null
        return first.optJSONObject("ids")?.optInt("simkl", 0)?.takeIf { it > 0 }
            ?: first.optInt("id", 0).takeIf { it > 0 }
    }

    private suspend fun simklCountEpisodes(item: MediaItem): List<Episode>? {
        val clientId = simklClientId()
        if (clientId.isBlank()) return null
        val simklId = if (item.providerId.equals("simkl", true)) {
            item.id.toIntOrNull()?.takeIf { it > 0 }
        } else {
            null
        } ?: simklIdFor(item.searchTitle, clientId) ?: return null
        val raw = Http.getStringQuiet(
            "https://api.simkl.com/anime/" + simklId + "?extended=full&client_id=" + URLEncoder.encode(clientId, "UTF-8"),
        ) ?: return null
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val data = o.optJSONObject("anime") ?: o
        val total = data.optInt("total_episodes", 0)
        if (total < 1 || total > 3000) return null
        return List(total) { i ->
            Episode(number = i + 1, id = item.id + "#e" + (i + 1), name = "Episode " + (i + 1), season = 1)
        }
    }

    private val cjk = Regex("[\u3040-\u30ff\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]")

    private suspend fun bangumiEpisodes(item: MediaItem): List<Episode>? {
        val title = item.searchTitle
        if (title.isBlank()) return null
        val original = item.originalTitle.trim().takeIf { it.isNotBlank() && cjk.containsMatchIn(it) }
        if (!cjk.containsMatchIn(title) && original == null) return null
        val hint = TmdbMeta.seasonHint(title)
        val eps = runCatching { BangumiMeta.episodes(title, original, item.year, hint) }.getOrNull()
            .orEmpty()
        if (eps.isEmpty()) return null
        return eps.sortedBy { it.number }.map { e ->
            Episode(number = e.number, id = item.id + "#e" + e.number, name = e.name?.takeIf { it.isNotBlank() }, season = 1)
        }
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
        val backdrop = media.optJSONObject("bannerImage")?.trim()?.takeIf { it.startsWith("http") }
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
