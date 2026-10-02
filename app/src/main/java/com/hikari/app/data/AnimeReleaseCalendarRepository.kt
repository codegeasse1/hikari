package com.hikari.app.data

import com.hikari.app.HikariApp
import com.hikari.app.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Instant

object AnimeReleaseCalendarRepository {
    data class Release(
        val simklId: Int,
        val title: String,
        val titleRomaji: String?,
        val poster: String?,
        val airAt: Long,
        val episode: Int?,
        val episodeTitle: String?,
        val finaleType: Int?,
        val totalEpisodes: Int?,
        val status: String?,
        val tmdbId: String?,
        val anilistId: String?,
        val url: String?,
    )

    private data class Cache(val at: Long, val items: List<Release>)
    @Volatile private var cache: Cache? = null

    suspend fun load(): List<Release> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        cache?.takeIf { now - it.at < 3 * 60 * 60 * 1000L }?.let { return@withContext it.items }

        val client = runCatching {
            HikariApp.instance.store.trackerClients()
                .firstOrNull { it.kind.key == "simkl" }?.id.orEmpty()
        }.getOrDefault("")

        if (client.isBlank()) return@withContext loadLegacy()

        val params = "client_id=" + URLEncoder.encode(client, "UTF-8") +
            "&app-name=Hikari&app-version=" +
            URLEncoder.encode(com.hikari.app.BuildConfig.VERSION_NAME, "UTF-8")
        val raw = Http.getStringQuiet(
            "https://data.simkl.in/calendar/v2/anime.json?" + params,
            mapOf("User-Agent" to "Hikari/" + com.hikari.app.BuildConfig.VERSION_NAME),
        ) ?: return@withContext cache?.items.orEmpty()

        val parsed = parseV2(raw)
        if (parsed.isNotEmpty()) cache = Cache(now, parsed)
        parsed
    }

    private fun parseV2(raw: String): List<Release> {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
        val calendar = root.optJSONArray("calendar") ?: return emptyList()
        val metadata = root.optJSONObject("metadata") ?: return emptyList()
        val out = ArrayList<Release>(calendar.length())

        for (i in 0 until calendar.length()) {
            val e = calendar.optJSONObject(i) ?: continue
            val id = e.optInt("simkl_id", 0)
            if (id <= 0) continue
            val m = metadata.optJSONObject(id.toString()) ?: continue
            val at = runCatching { Instant.parse(e.optString("date")).toEpochMilli() }.getOrNull() ?: continue
            val ep = e.optJSONObject("episode")
            val ids = m.optJSONObject("ids")
            val poster = m.optString("poster").trim().takeIf { it.isNotBlank() }
                ?.let { "https://wsrv.nl/?url=https://simkl.in/posters/" + it + "_m.webp&q=90" }

            out += Release(
                id,
                m.optString("title").trim().ifBlank { "Unknown anime" },
                m.optString("title_romaji").trim().takeIf { it.isNotBlank() },
                poster,
                at,
                ep?.optInt("episode", 0)?.takeIf { it > 0 },
                ep?.optString("title")?.trim()?.takeIf { it.isNotBlank() },
                if (e.has("finale_type") && !e.isNull("finale_type")) e.optInt("finale_type") else null,
                m.optInt("total_episodes", 0).takeIf { it > 0 },
                m.optString("status").takeIf { it.isNotBlank() },
                ids?.optString("tmdb").takeIf { !it.isNullOrBlank() && it != "0" },
                ids?.optString("anilist").takeIf { !it.isNullOrBlank() && it != "0" },
                m.optString("url").trim().takeIf { it.isNotBlank() },
            )
        }
        return out.distinctBy { it.simklId.toString() + ":" + it.airAt + ":" + it.episode }
            .sortedBy { it.airAt }
    }

    private suspend fun loadLegacy(): List<Release> {
        val raw = Http.getStringQuiet("https://data.simkl.in/calendar/anime.json")
            ?: return cache?.items.orEmpty()
        val root = runCatching { JSONObject(raw) }.getOrNull()
        val array = runCatching { org.json.JSONArray(raw) }.getOrNull()
            ?: root?.optJSONArray("anime")
            ?: root?.optJSONArray("items")
            ?: return emptyList()

        val out = ArrayList<Release>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val media = o.optJSONObject("anime") ?: o.optJSONObject("show") ?: o
            val title = media.optString("title").ifBlank { media.optString("name") }.trim()
            val date = listOf("airing_at", "airingAt", "release_date", "releaseDate", "date")
                .firstNotNullOfOrNull { key -> o.optString(key).takeIf { it.isNotBlank() } }
                ?: media.optString("airing_at").takeIf { it.isNotBlank() }
            val at = date?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: continue
            if (title.isBlank()) continue
            val id = media.optJSONObject("ids")?.optInt("simkl", 0) ?: media.optInt("id", 0)
            out += Release(id, title, null, null, at, null, null, null, null, null, null, null, null)
        }
        val result = out.sortedBy { it.airAt }
        if (result.isNotEmpty()) cache = Cache(System.currentTimeMillis(), result)
        return result
    }
}
