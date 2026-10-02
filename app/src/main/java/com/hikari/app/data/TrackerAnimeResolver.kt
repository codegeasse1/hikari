package com.hikari.app.data

import com.hikari.app.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

object TrackerAnimeResolver {
    private val trackerProviders = setOf("anilist", "simkl", "mal", "kitsu", "shikimori")

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
                val raw = Http.getStringQuiet("https://api.jikan.moe/v4/anime/$id") ?: return null
                JSONObject(raw).optJSONObject("data")?.optInt("episodes", 0)?.takeIf { it > 0 }
            }
            else -> null
        }
    }
}
