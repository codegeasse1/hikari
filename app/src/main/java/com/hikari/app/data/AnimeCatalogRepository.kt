package com.hikari.app.data

import com.hikari.app.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

object AnimeCatalogRepository {
    private const val ENDPOINT = "https://graphql.anilist.co"

    suspend fun homeRows(): List<CatalogRow> = withContext(Dispatchers.IO) {
        val sections = listOf("Trending Anime" to "TRENDING_DESC", "Popular Anime" to "POPULARITY_DESC", "Top Rated Anime" to "SCORE_DESC", "Currently Airing" to "START_DATE_DESC", "New Anime" to "START_DATE_DESC")
        sections.mapNotNull { (title, sort) ->
            val items = query(sort, title == "Currently Airing")
            if (items.isEmpty()) null else CatalogRow("anilist-catalog", "AniList", title, items, "anilist-catalog|$sort|$title", sort, MediaType.SERIES, "anime")
        }
    }

    private suspend fun query(sort: String, airing: Boolean): List<MediaItem> {
        val status = if (airing) ", status: RELEASING" else ""
        val query = "query { Page(page: 1, perPage: 30) { media(type: ANIME$status, sort: [$sort]) { id format title { userPreferred english romaji } coverImage { large } startDate { year } averageScore nextAiringEpisode { airingAt episode } } } }"
        val raw = Http.postStringQuiet(ENDPOINT, JSONObject().put("query", query).toString()) ?: return emptyList()
        val media = JSONObject(raw).optJSONObject("data")?.optJSONObject("Page")?.optJSONArray("media") ?: JSONArray()
        return buildList {
            for (i in 0 until media.length()) {
                val o = media.optJSONObject(i) ?: continue
                val id = o.optInt("id", 0)
                if (id <= 0) continue
                val t = o.optJSONObject("title")
                val title = t?.optString("userPreferred").orEmpty().ifBlank { t?.optString("english").orEmpty() }.ifBlank { t?.optString("romaji").orEmpty() }.ifBlank { "Untitled" }
                add(MediaItem("anilist", id.toString(), title, if (o.optString("format").equals("MOVIE", true)) MediaType.MOVIE else MediaType.SERIES, o.optJSONObject("coverImage")?.optString("large"), o.optJSONObject("startDate")?.optInt("year", 0)?.takeIf { it > 0 }, rawType = "anime", rating = o.optDouble("averageScore", 0.0).takeIf { it > 0 }?.div(10.0), nextEpisodeDate = o.optJSONObject("nextAiringEpisode")?.optLong("airingAt", 0L)?.takeIf { it > 0 }?.let { java.time.Instant.ofEpochSecond(it).toString() }, metadataSource = "AniList"))
            }
        }
    }
}