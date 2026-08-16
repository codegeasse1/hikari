package com.hikari.app.providers

import com.hikari.app.data.CatalogRef
import com.hikari.app.data.Episode
import com.hikari.app.data.MediaItem
import com.hikari.app.data.MediaType
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.StreamSource
import com.hikari.app.data.SubtitleSource
import com.hikari.app.net.Http
import org.json.JSONObject

class StremioAddon(override val config: ProviderConfig) : ContentProvider {

    private val base: String get() = config.url.trimEnd('/')
    private var manifest: JSONObject? = null

    private suspend fun loadManifest(): JSONObject? {
        manifest?.let { return it }
        val m = Http.getString("$base/manifest.json")
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
        manifest = m
        return m
    }

    private fun typeOf(t: String): MediaType = when (t.lowercase()) {
        "movie" -> MediaType.MOVIE
        "series" -> MediaType.SERIES
        else -> MediaType.UNKNOWN
    }

    override suspend fun catalogs(): List<CatalogRef> {
        val m = loadManifest() ?: return emptyList()
        val arr = m.optJSONArray("catalogs") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val c = arr.optJSONObject(i) ?: return@mapNotNull null
            val t = typeOf(c.optString("type"))
            if (t == MediaType.UNKNOWN) return@mapNotNull null
            CatalogRef(config.id, t, c.optString("id"), c.optString("name").ifBlank { c.optString("id") })
        }
    }

    override suspend fun getCatalog(ref: CatalogRef, page: Int): List<MediaItem> {
        val url = "$base/catalog/${ref.type.name.lowercase()}/${ref.id}.json" +
            if (page > 1) "?page=$page" else ""
        return parseItems(url)
    }

    override suspend fun search(query: String, page: Int): List<MediaItem> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val out = mutableListOf<MediaItem>()
        for (c in catalogs().distinctBy { it.id }) {
            val url = "$base/catalog/${c.type.name.lowercase()}/${c.id}/search=$encoded.json"
            out += parseItems(url)
        }
        return out.distinctBy { it.uniqueId }
    }

    private suspend fun parseItems(url: String): List<MediaItem> {
        val s = Http.getString(url) ?: return emptyList()
        val json = runCatching { JSONObject(s) }.getOrNull() ?: return emptyList()
        val metas = json.optJSONArray("metas") ?: return emptyList()
        val out = mutableListOf<MediaItem>()
        for (i in 0 until metas.length()) {
            val m = metas.optJSONObject(i) ?: continue
            val id = m.optString("id")
            val title = m.optString("name")
            if (id.isBlank() || title.isBlank()) continue
            out += MediaItem(
                providerId = config.id,
                id = id,
                title = title,
                type = typeOf(m.optString("type")),
                posterUrl = m.optString("poster").ifBlank { null },
                backdropUrl = m.optString("background").ifBlank { null },
                year = m.optString("year").toIntOrNull(),
                overview = m.optString("description").ifBlank { null },
                genres = m.optJSONArray("genres")
                    ?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
            )
        }
        return out
    }

    override suspend fun getMeta(item: MediaItem): MediaItem {
        if (item.overview != null || item.genres.isNotEmpty()) return item
        val url = "$base/meta/${item.type.name.lowercase()}/${item.id}.json"
        val json = Http.getString(url)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return item
        val m = json.optJSONObject("meta") ?: return item
        return item.copy(
            overview = m.optString("description").ifBlank { item.overview },
            genres = m.optJSONArray("genres")
                ?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: item.genres,
            year = m.optString("year").toIntOrNull() ?: item.year,
            backdropUrl = m.optString("background").ifBlank { item.backdropUrl },
        )
    }

    override suspend fun getEpisodes(item: MediaItem): List<Episode>? {
        if (item.type != MediaType.SERIES) return null
        val json = Http.getString("$base/meta/series/${item.id}.json")
            ?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        val meta = json.optJSONObject("meta") ?: return null
        val videos = meta.optJSONArray("videos") ?: return null
        val out = mutableListOf<Episode>()
        for (i in 0 until videos.length()) {
            val v = videos.optJSONObject(i) ?: continue
            val ep = v.optInt("episode", -1)
            if (ep < 0) continue
            val season = v.optInt("season", 1)
            out += Episode(
                number = ep,
                id = "${item.id}:$season:$ep",
                name = v.optString("title").ifBlank { "Episode $ep" },
                image = v.optString("thumbnail").ifBlank { null },
            )
        }
        return out.sortedBy { it.number }.distinctBy { it.number }
    }

    override suspend fun getStreams(item: MediaItem, episode: Episode?): List<StreamSource> {
        val idPart = episode?.id ?: item.id
        val url = "$base/stream/${item.type.name.lowercase()}/$idPart.json"
        val json = Http.getString(url)?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return emptyList()
        val arr = json.optJSONArray("streams") ?: return emptyList()
        val subs = getSubtitles(item, episode)
        val out = mutableListOf<StreamSource>()
        for (i in 0 until arr.length()) {
            val st = arr.optJSONObject(i) ?: continue
            val infoHash = st.optString("infoHash").ifBlank { null }
            val streamUrl = st.optString("url").ifBlank { null }
            val title = st.optString("name").ifBlank { st.optString("title") }
            when {
                infoHash != null ->
                    out += StreamSource(title.ifBlank { "Torrent" }, "", subtitles = subs, isTorrent = true, infoHash = infoHash)
                streamUrl != null -> {
                    val isM3u8 = streamUrl.contains(".m3u8", true)
                    out += StreamSource(
                        title.ifBlank { if (isM3u8) "HLS" else "Direct" },
                        streamUrl,
                        subtitles = subs,
                    )
                }
            }
        }
        return out
    }

    private suspend fun getSubtitles(item: MediaItem, episode: Episode?): List<SubtitleSource> {
        if (item.type != MediaType.SERIES) return emptyList()
        val idPart = episode?.id ?: item.id
        val json = Http.getString("$base/subtitles/${item.type.name.lowercase()}/$idPart.json")
            ?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return emptyList()
        val arr = json.optJSONArray("subtitles") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val st = arr.optJSONObject(i) ?: return@mapNotNull null
            val u = st.optString("url")
            if (u.isBlank()) null else SubtitleSource(st.optString("lang").ifBlank { "Subtitle" }, u)
        }
    }
}
