package com.hikari.ext.providers

import com.hikari.ext.HikariCatalog
import com.hikari.ext.HikariEpisode
import com.hikari.ext.HikariMedia
import com.hikari.ext.HikariMediaType
import com.hikari.ext.HikariNet
import com.hikari.ext.HikariProvider
import com.hikari.ext.HikariStream
import org.json.JSONObject

/**
 * Bundled example Hikari extension: YTS (yts.mx). A public JSON API — no keys,
 * no Cloudflare, no scraping — whose torrents play through the app's built-in
 * TorrServer engine. It ships inside the APK (auto-registered on first run) so
 * the extension system has a working provider out of the box, and doubles as
 * the reference implementation for docs/HIKARI_EXTENSIONS.md.
 */
class YtsProvider : HikariProvider {
    override val id = "yts"
    override val name = "YTS (Hikari)"
    override val mainUrl = "https://yts.mx"
    override val description = "Yify torrents via the public YTS API."
    override val tvTypes = setOf(HikariMediaType.MOVIE)

    override fun catalogs() = listOf(
        HikariCatalog("trending", "Trending Movies", HikariMediaType.MOVIE),
        HikariCatalog("latest", "Latest Movies", HikariMediaType.MOVIE),
    )

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private suspend fun api(path: String): JSONObject? =
        HikariNet.getJson("https://yts.mx/api/v2/$path")

    private fun moviesOf(json: JSONObject?): List<HikariMedia> {
        json ?: return emptyList()
        val arr = json.optJSONObject("data")?.optJSONArray("movies") ?: return emptyList()
        val out = mutableListOf<HikariMedia>()
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val id = m.optString("id")
            val title = m.optString("title")
            if (id.isBlank() || title.isBlank()) continue
            out += HikariMedia(
                id = id,
                title = title.trim(),
                type = HikariMediaType.MOVIE,
                posterUrl = m.optString("medium_cover_image")
                    .ifBlank { m.optString("large_cover_image").ifBlank { null } },
                year = m.optInt("year", 0).takeIf { it > 0 },
                overview = m.optString("summary").ifBlank { null },
                genres = stringArray(m, "genres"),
            )
        }
        return out
    }

    private fun stringArray(o: JSONObject, key: String): List<String> =
        o.optJSONArray(key)?.let { a ->
            (0 until a.length()).mapNotNull { a.optString(it).ifBlank { null } }
        } ?: emptyList()

    override suspend fun getCatalog(catalog: HikariCatalog, page: Int): List<HikariMedia> {
        val sort = if (catalog.id == "trending") "featured" else "date_added"
        return moviesOf(api("list_movies.json?limit=20&sort_by=$sort&order_by=desc&page=$page"))
    }

    override suspend fun search(query: String, page: Int): List<HikariMedia> =
        moviesOf(api("list_movies.json?query_term=${enc(query)}&limit=20&page=$page"))

    override suspend fun getMeta(media: HikariMedia): HikariMedia {
        val json = api("movie_details.json?movie_id=${media.id}")
            ?.optJSONObject("data")?.optJSONObject("movie") ?: return media
        return media.copy(
            overview = json.optString("description_intro").ifBlank { media.overview },
            genres = stringArray(json, "genres").ifEmpty { media.genres },
            backdropUrl = json.optString("background_image").ifBlank { media.backdropUrl },
        )
    }

    override suspend fun getStreams(media: HikariMedia, episode: HikariEpisode?): List<HikariStream> {
        val json = api("movie_details.json?movie_id=${media.id}")
        val movie = json?.optJSONObject("data")?.optJSONObject("movie") ?: return emptyList()
        val torrents = movie.optJSONArray("torrents") ?: return emptyList()
        val out = mutableListOf<HikariStream>()
        for (i in 0 until torrents.length()) {
            val t = torrents.optJSONObject(i) ?: continue
            val hash = t.optString("hash")
            if (hash.isBlank()) continue
            val quality = t.optString("quality").ifBlank { "Unknown" }
            val type = t.optString("type").ifBlank { "" }
            val size = t.optString("size").ifBlank { "" }
            val seeds = t.optInt("seeds", 0)
            out += HikariStream(
                name = buildString {
                    append(quality)
                    if (type.isNotBlank()) append(" ").append(type)
                    append(" · ").append(size.ifBlank { "?" })
                    append(" · ").append(seeds).append(" seeds")
                },
                isTorrent = true,
                infoHash = hash,
                trackers = TRACKERS,
            )
        }
        return out
    }

    companion object {
        private val TRACKERS = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://tracker.openbittorrent.com:6969/announce",
            "udp://open.stealth.si:80/announce",
            "udp://explodie.org:6969/announce",
            "udp://tracker.torrent.eu.org:451/announce",
            "http://tracker.openbittorrent.com:80/announce",
        )
    }
}
