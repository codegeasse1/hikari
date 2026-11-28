package com.hikari.desktop.engine

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Pure M3U(8) playlist parsing — no I/O, fully unit-testable. */
object M3uParser {

    data class Entry(
        val name: String,
        val url: String,
        val group: String?,
        val logo: String?,
        val headers: Map<String, String> = emptyMap(),
    )

    fun parse(text: String): List<Entry> {
        val out = mutableListOf<Entry>()
        var name: String? = null
        var group: String? = null
        var logo: String? = null
        val headers = LinkedHashMap<String, String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    name = null
                    group = null
                    logo = null
                    headers.clear()
                    group = attr(line, "group-title")
                    logo = attr(line, "tvg-logo")
                    val cut = line.indexOf(',')
                    name = if (cut >= 0) line.substring(cut + 1).trim().takeIf { it.isNotEmpty() } else null
                }
                line.startsWith("#EXTVLCOPT", ignoreCase = true) -> {
                    val opt = line.substringAfter(":", "").trim()
                    if (opt.startsWith("http-referrer=", ignoreCase = true)) {
                        headers["Referer"] = opt.substringAfter("=").trim()
                    } else if (opt.startsWith("http-user-agent=", ignoreCase = true)) {
                        headers["User-Agent"] = opt.substringAfter("=").trim()
                    }
                }
                line.startsWith("#") -> Unit
                else -> {
                    val url = line.trim()
                    if (url.startsWith("http", true) || url.startsWith("rtmp", true) || url.startsWith("rtsp", true)) {
                        out.add(
                            Entry(
                                name = name?.takeIf { it.isNotBlank() } ?: url.substringAfterLast('/').substringBefore('?'),
                                url = url,
                                group = group?.takeIf { it.isNotBlank() },
                                logo = logo?.takeIf { it.isNotBlank() },
                                headers = headers.toMap(),
                            ),
                        )
                    }
                    name = null
                    group = null
                    logo = null
                    headers.clear()
                }
            }
        }
        return out
    }

    private fun attr(line: String, key: String): String? {
        val i = line.indexOf("$key=\"", ignoreCase = true)
        if (i < 0) return null
        val start = i + key.length + 2
        val end = line.indexOf('"', start)
        if (end < 0) return null
        return line.substring(start, end).trim().takeIf { it.isNotEmpty() }
    }
}

/**
 * IPTV engine: M3U playlists and Xtream Codes API portals as catalogs.
 * Live TV entries are SERIES-kind single-episode rows (playable directly);
 * VOD movies/series map to movie/series rows with real episodes.
 */
class IptvEngine(
    private val tmdbKey: () -> String = { "" },
) : Engine {
    override val kind: EngineKind = EngineKind.IPTV

    private val gson = Gson()

    private val client: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }

    fun dir(): File = NuvioEngine.homeDir().resolve("engines/iptv").apply { mkdirs() }

    private fun getText(url: String, headers: Map<String, String> = emptyMap()): String? {
        return runCatching {
            val b = HttpRequest.newBuilder(URI.create(url.trim()))
                .timeout(Duration.ofSeconds(45))
                .header("User-Agent", EngineHttp.NUVIO_DEFAULT_UA)
            for ((k, v) in headers) runCatching { b.header(k, v) }
            b.GET()
            val resp = client.send(b.build(), HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() !in 200..299) null else resp.body()
        }.getOrNull()
    }

    private data class Portal(val schemeHost: String, val user: String, val pass: String)

    /** `portalUrl|user|pass` source spelling for Xtream logins. */
    private fun portalOf(source: String): Portal? {
        val parts = source.split("|")
        if (parts.size != 3) return null
        val base = parts[0].trim().trimEnd('/')
        if (base.isBlank() || parts[1].isBlank()) return null
        return Portal(base, parts[1].trim(), parts[2])
    }

    private fun xtreamApi(p: Portal, action: String, extra: String = ""): JsonObject? {
        val url = buildString {
            append(p.schemeHost)
            append("/player_api.php?username=")
            append(URLEncoder.encode(p.user, "UTF-8"))
            append("&password=")
            append(URLEncoder.encode(p.pass, "UTF-8"))
            if (action.isNotBlank()) {
                append("&action=")
                append(action)
            }
            if (extra.isNotBlank()) append(extra)
        }
        val text = getText(url) ?: return null
        return runCatching { gson.fromJson(text, JsonObject::class.java) }.getOrNull()
    }

    override suspend fun catalogs(provider: EngineProvider): List<EngineCatalog> =
        withContext(Dispatchers.IO) {
            val portal = portalOf(provider.source)
            if (portal != null) {
                return@withContext listOf(
                    EngineCatalog(provider.id, "live", "Live TV", EngineMediaKind.SERIES),
                    EngineCatalog(provider.id, "vod", "Movies", EngineMediaKind.MOVIE),
                    EngineCatalog(provider.id, "series", "Series", EngineMediaKind.SERIES),
                )
            }
            // M3U: group titles become shelves.
            val text = playlistText(provider) ?: return@withContext emptyList()
            val groups = M3uParser.parse(text).mapNotNull { it.group }.distinct()
            if (groups.isEmpty()) {
                return@withContext listOf(EngineCatalog(provider.id, "all", "All Channels", EngineMediaKind.SERIES))
            }
            listOf(EngineCatalog(provider.id, "all", "All Channels", EngineMediaKind.SERIES)) +
                groups.take(40).mapIndexed { i, g -> EngineCatalog(provider.id, "group:$i", g, EngineMediaKind.SERIES) }
        }

    override suspend fun catalog(
        provider: EngineProvider,
        catalog: EngineCatalog,
        page: Int,
    ): List<EngineMedia> = withContext(Dispatchers.IO) {
        val portal = portalOf(provider.source)
        if (portal != null) return@withContext xtreamCatalog(portal, provider, catalog, page)
        val text = playlistText(provider) ?: return@withContext emptyList()
        val entries = M3uParser.parse(text)
        val pool = when {
            catalog.id == "all" -> entries
            catalog.id.startsWith("group:") -> {
                val groups = entries.mapNotNull { it.group }.distinct()
                val g = groups.getOrNull(catalog.id.removePrefix("group:").toIntOrNull() ?: -1)
                    ?: return@withContext emptyList()
                entries.filter { it.group == g }
            }
            else -> entries
        }
        val from = (page.coerceAtLeast(1) - 1) * 100
        pool.drop(from).take(100).map { e ->
            EngineMedia(
                providerId = provider.id,
                id = "m3u:" + e.url,
                title = e.name,
                kind = EngineMediaKind.SERIES,
                posterUrl = e.logo,
                genres = listOfNotNull(e.group),
            )
        }
    }

    override suspend fun search(provider: EngineProvider, query: String, page: Int): List<EngineMedia> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            val portal = portalOf(provider.source)
            if (portal != null) {
                val q = query.lowercase()
                val all = xtreamCatalog(portal, provider, EngineCatalog(provider.id, "live", "", EngineMediaKind.SERIES), 1) +
                    xtreamCatalog(portal, provider, EngineCatalog(provider.id, "vod", "", EngineMediaKind.MOVIE), 1) +
                    xtreamCatalog(portal, provider, EngineCatalog(provider.id, "series", "", EngineMediaKind.SERIES), 1)
                val from = (page.coerceAtLeast(1) - 1) * 100
                return@withContext all.filter { it.title.contains(q, true) }.drop(from).take(100)
            }
            val text = playlistText(provider) ?: return@withContext emptyList()
            val q = query.lowercase()
            M3uParser.parse(text)
                .filter { it.name.contains(q, true) }
                .take(100)
                .map { e ->
                    EngineMedia(
                        providerId = provider.id,
                        id = "m3u:" + e.url,
                        title = e.name,
                        kind = EngineMediaKind.SERIES,
                        posterUrl = e.logo,
                        genres = listOfNotNull(e.group),
                    )
                }
        }

    override suspend fun episodes(provider: EngineProvider, media: EngineMedia): List<EngineEpisode> =
        withContext(Dispatchers.IO) {
            val portal = portalOf(provider.source) ?: return@withContext listOf(
                EngineEpisode(provider.id, media.id, media.id, 1, 1, media.title),
            )
            if (!media.id.startsWith("xtream:series:")) {
                return@withContext listOf(EngineEpisode(provider.id, media.id, media.id, 1, 1, media.title))
            }
            val seriesId = media.id.removePrefix("xtream:series:")
            val info = xtreamApi(portal, "get_series_info", "&series_id=" + URLEncoder.encode(seriesId, "UTF-8"))
                ?: return@withContext emptyList()
            val episodes = info.getAsJsonObject("episodes") ?: return@withContext emptyList()
            val out = mutableListOf<EngineEpisode>()
            for ((seasonKey, seasonVal) in episodes.entrySet()) {
                val sn = seasonKey.toIntOrNull() ?: continue
                if (!seasonVal.isJsonArray) continue
                for (el in seasonVal.asJsonArray) {
                    if (!el.isJsonObject) continue
                    val o = el.asJsonObject
                    val eid = o.get("id")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
                    val num = o.get("episode_num")?.takeIf { it.isJsonPrimitive }?.asInt ?: continue
                    val name = o.get("title")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
                        ?: "Episode $num"
                    out.add(EngineEpisode(provider.id, media.id, "xtream:episode:$eid", sn, num, name))
                }
            }
            out.sortedWith(compareBy({ it.season }, { it.number }))
        }

    override suspend fun streams(
        provider: EngineProvider,
        media: EngineMedia,
        episode: EngineEpisode?,
    ): List<EngineStream> = withContext(Dispatchers.IO) {
        val portal = portalOf(provider.source)
        if (portal != null) return@withContext xtreamStreams(portal, provider, media, episode)
        val raw = media.id.removePrefix("m3u:")
        val url = episode?.id?.takeIf { it.startsWith("http", true) } ?: raw
        if (url.isBlank()) return@withContext emptyList()
        listOf(EngineStream(provider.id, media.title, url))
    }

    // ---- internals ----

    private fun playlistText(provider: EngineProvider): String? {
        val src = provider.source.trim()
        if (src.startsWith("http", true)) return getText(src)
        return runCatching { File(src).takeIf { it.exists() }?.readText() }.getOrNull()
    }

    private fun xtreamCatalog(
        portal: Portal,
        provider: EngineProvider,
        catalog: EngineCatalog,
        page: Int,
    ): List<EngineMedia> {
        val action = when (catalog.id) {
            "live" -> "get_live_streams"
            "vod" -> "get_vod_streams"
            else -> "get_series"
        }
        val list = rootArray(portal, action) ?: return emptyList()
        val from = (page.coerceAtLeast(1) - 1) * 100
        return list.drop(from).take(100).mapNotNull { el ->
            if (!el.isJsonObject) return@mapNotNull null
            val o = el.asJsonObject
            val sid = o.get("stream_id")?.takeIf { it.isJsonPrimitive }?.asString
                ?: o.get("series_id")?.takeIf { it.isJsonPrimitive }?.asString
                ?: return@mapNotNull null
            val name = o.get("name")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val art = o.get("stream_icon")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
            when (catalog.id) {
                "vod" -> EngineMedia(provider.id, "xtream:vod:$sid", name, EngineMediaKind.MOVIE, posterUrl = art)
                "series" -> EngineMedia(provider.id, "xtream:series:$sid", name, EngineMediaKind.SERIES, posterUrl = art)
                else -> EngineMedia(provider.id, "xtream:live:$sid", name, EngineMediaKind.SERIES, posterUrl = art)
            }
        }
    }

    private fun rootArray(portal: Portal, action: String): com.google.gson.JsonArray? {
        val text = getText(
            "${portal.schemeHost}/player_api.php?username=${URLEncoder.encode(portal.user, "UTF-8")}" +
                "&password=${URLEncoder.encode(portal.pass, "UTF-8")}&action=$action",
        ) ?: return null
        val el = runCatching { gson.fromJson(text, com.google.gson.JsonElement::class.java) }.getOrNull()
            ?: return null
        if (el.isJsonArray) return el.asJsonArray
        if (el.isJsonObject) return el.asJsonObject.getAsJsonArray("results")
        return null
    }

    private fun xtreamStreams(
        portal: Portal,
        provider: EngineProvider,
        media: EngineMedia,
        episode: EngineEpisode?,
    ): List<EngineStream> {
        val base = portal.schemeHost
        val u = URLEncoder.encode(portal.user, "UTF-8")
        val p = URLEncoder.encode(portal.pass, "UTF-8")
        val id = media.id
        return when {
            id.startsWith("xtream:live:") -> {
                val sid = id.removePrefix("xtream:live:")
                listOf(EngineStream(provider.id, media.title, "$base/live/$u/$p/$sid.m3u8"))
            }
            id.startsWith("xtream:vod:") -> {
                val sid = id.removePrefix("xtream:vod:")
                listOf(
                    EngineStream(provider.id, media.title + " · HLS", "$base/movie/$u/$p/$sid.m3u8"),
                    EngineStream(provider.id, media.title + " · MP4", "$base/movie/$u/$p/$sid.mp4"),
                )
            }
            id.startsWith("xtream:series:") || (episode != null && episode.id.startsWith("xtream:episode:")) -> {
                val eid = (episode?.id ?: id).removePrefix("xtream:episode:")
                listOf(
                    EngineStream(provider.id, (episode?.title ?: media.title) + " · HLS", "$base/series/$u/$p/$eid.m3u8"),
                    EngineStream(provider.id, (episode?.title ?: media.title) + " · MP4", "$base/series/$u/$p/$eid.mp4"),
                )
            }
            else -> emptyList()
        }
    }
}
