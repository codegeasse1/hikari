package com.hikari.app.anymex

import com.hikari.app.data.CatalogRef
import com.hikari.app.data.Episode
import com.hikari.app.data.MediaItem
import com.hikari.app.data.MediaType
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.StreamSource
import com.hikari.app.data.SubtitleSource
import com.hikari.app.net.Http
import com.hikari.app.net.StreamProbe
import com.hikari.app.providers.ContentProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class AnymexProvider(override val config: ProviderConfig) : ContentProvider {

    companion object {
        val streamErrors = ConcurrentHashMap<String, String>()
        val lastOutcome = ConcurrentHashMap<String, String>()
        val catalogErrors = ConcurrentHashMap<String, String>()
        const val CATALOG_POPULAR = "popular"
        const val CATALOG_LATEST = "latest"
        private const val MAX_STREAMS = 60
        private const val MAX_EPISODES = 2000
    }

    private fun module(): File? {
        if (config.url.isBlank()) return null
        val f = File(config.url)
        return if (f.exists()) f else null
    }

    override suspend fun catalogs(): List<CatalogRef> = listOf(
        CatalogRef(config.id, MediaType.SERIES, CATALOG_POPULAR, "Popular"),
        CatalogRef(config.id, MediaType.SERIES, CATALOG_LATEST, "Latest"),
    )

    override suspend fun getCatalog(ref: CatalogRef, page: Int): List<MediaItem> =
        withContext(Dispatchers.IO) {
            val mod = module() ?: return@withContext emptyList()
            val raw = when (ref.id) {
                CATALOG_LATEST -> AnymexRuntime.latest(mod, config.id, page.coerceAtLeast(1))
                else -> AnymexRuntime.popular(mod, config.id, page.coerceAtLeast(1))
            }
            val items = mapItems(raw)
            if (items.isNotEmpty()) {
                catalogErrors.remove(config.id)
                lastOutcome.remove(config.id)
            }
            items
        }

    override suspend fun search(query: String, page: Int): List<MediaItem> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            val mod = module() ?: return@withContext emptyList()
            mapItems(AnymexRuntime.search(mod, config.id, query, page.coerceAtLeast(1)))
        }

    override suspend fun getMeta(item: MediaItem): MediaItem = withContext(Dispatchers.IO) {
        val mod = module() ?: return@withContext item
        val raw = AnymexRuntime.detail(mod, config.id, item.id) ?: return@withContext item
        val d = firstObject(raw) ?: return@withContext item
        val title = d.optString("title").ifBlank { d.optString("name") }.trim()
        val overview = d.optString("description").trim().ifBlank { null }
        val genres = genresOf(d)
        val poster = d.optString("imageUrl").ifBlank { d.optString("image") }.trim().ifBlank { null }
        if (title.isBlank() && overview == null && genres.isEmpty() && poster == null) return@withContext item
        item.copy(
            title = title.ifBlank { item.title },
            overview = overview ?: item.overview,
            genres = if (genres.isNotEmpty()) genres else item.genres,
            posterUrl = poster ?: item.posterUrl,
        )
    }

    override suspend fun getEpisodes(item: MediaItem): List<Episode>? = withContext(Dispatchers.IO) {
        val mod = module() ?: return@withContext null
        val raw = AnymexRuntime.detail(mod, config.id, item.id) ?: return@withContext null
        val d = firstObject(raw) ?: return@withContext null
        val arr = d.optJSONArray("episodes")
            ?: d.optJSONArray("chapters")
            ?: d.optJSONArray("list")
            ?: return@withContext null
        val out = ArrayList<Episode>()
        for (i in 0 until minOf(arr.length(), MAX_EPISODES)) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("url").ifBlank { o.optString("link") }
                .ifBlank { o.optString("href") }.ifBlank { o.optString("id") }.trim()
            if (url.isBlank()) continue
            val name = o.optString("name").trim()
                .ifBlank { o.optString("title").trim() }
                .ifBlank { null }
            val n = o.opt("number")?.toString()?.toIntOrNull()
                ?: o.opt("episode")?.toString()?.toIntOrNull()
                ?: o.opt("ep")?.toString()?.toIntOrNull()
                ?: (i + 1)
            val season = o.opt("season")?.toString()?.toIntOrNull()?.takeIf { it > 0 } ?: 1
            out += Episode(number = n, id = url, name = name, season = season)
        }
        out.ifEmpty { null }
    }

    override suspend fun getStreams(item: MediaItem, episode: Episode?): List<StreamSource> =
        withContext(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            val mod = module() ?: return@withContext fail("✗ Source file is missing — reinstall it.")
            val epUrl = episode?.id?.takeIf { it.isNotBlank() } ?: item.id
            val raw = AnymexRuntime.videos(mod, config.id, epUrl)
                ?: return@withContext fail("✗ No playable sources for this title.")
            val out = mapVideos(raw)
            if (out.isEmpty()) return@withContext fail("✗ No playable sources for this title.")
            streamErrors.remove(config.id)
            lastOutcome[config.id] = "✓ ${out.size} source${if (out.size == 1) "" else "s"} in " +
                "${(System.currentTimeMillis() - started) / 1000}s"
            val distinct = out.distinctBy { it.url }
            StreamProbe.warmAsync(distinct)
            distinct
        }

    private fun mapItems(raw: String?): List<MediaItem> {
        if (raw.isNullOrBlank()) return emptyList()
        val arr = listArray(raw) ?: return emptyList()
        val out = ArrayList<MediaItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name").trim()
            if (name.isBlank()) continue
            val link = o.optString("link").ifBlank { o.optString("url") }.trim()
            if (link.isBlank()) continue
            out += MediaItem(
                providerId = config.id,
                id = link,
                title = name,
                type = MediaType.SERIES,
                posterUrl = o.optString("imageUrl").ifBlank { o.optString("image") }.trim().ifBlank { null },
            )
        }
        return out
    }

    private fun listArray(raw: String): JSONArray? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        runCatching { return JSONArray(t) }.getOrNull()?.let { return it }
        var o = runCatching { JSONObject(t) }.getOrNull() ?: return null
        // Harness wraps every call as { ok, data }. data is often
        // { list: [...], hasNextPage } (Mangayomi) rather than a bare array.
        if (o.has("ok")) {
            if (!o.optBoolean("ok", false)) {
                noteCatalogError(o.optString("error"))
                return null
            }
            when (val data = o.opt("data")) {
                is JSONArray -> return data
                is JSONObject -> o = data
                null -> return null
                else -> return null
            }
        }
        for (k in listOf("list", "results", "data", "videos", "items", "medias")) {
            o.optJSONArray(k)?.let { return it }
        }
        // Single media object — rare, but keep the old behaviour.
        if (o.has("name") || o.has("title") || o.has("link") || o.has("url")) {
            return JSONArray().apply { put(o) }
        }
        return null
    }

    private fun noteCatalogError(err: String) {
        if (err.isBlank()) return
        catalogErrors[config.id] = err.take(200)
        lastOutcome[config.id] = "✗ ${err.take(72)}"
    }

    private fun firstObject(raw: String): JSONObject? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        var o = runCatching { JSONObject(t) }.getOrNull()
        if (o != null) {
            if (o.has("ok")) {
                if (!o.optBoolean("ok", false)) return null
                when (val data = o.opt("data")) {
                    is JSONObject -> return data
                    is JSONArray -> return data.optJSONObject(0)
                    else -> return null
                }
            }
            return o
        }
        runCatching {
            val arr = JSONArray(t)
            if (arr.length() > 0) return arr.optJSONObject(0)
        }
        return null
    }

    private fun mapVideos(raw: String): List<StreamSource> {
        val out = ArrayList<StreamSource>()
        val t = raw.trim()
        if (t.isEmpty()) return out
        if (!t.startsWith("{") && !t.startsWith("[")) {
            if (t.startsWith("http", true) || t.startsWith("magnet:", true)) {
                videoTo(config.name, t, null, null, null)?.let { out += it }
            }
            return out
        }
        runCatching {
            val arr = listArray(t) ?: return out
            for (i in 0 until minOf(arr.length(), MAX_STREAMS)) {
                when (val e = arr.opt(i)) {
                    is JSONObject -> {
                        val u = e.optString("url").ifBlank { e.optString("originalUrl") }
                            .ifBlank { e.optString("streamUrl") }.trim()
                        if (u.isNotBlank()) {
                            val q = e.optString("quality").trim()
                            val sz = e.optString("size").trim()
                            val lang = e.optString("language").ifBlank { e.optString("lang") }.trim()
                            val detail = listOfNotNull(
                                q.ifBlank { null },
                                sz.ifBlank { null },
                                lang.ifBlank { null },
                            ).joinToString(" • ").ifBlank { null }
                            videoTo(
                                q.ifBlank { e.optString("title") }.ifBlank { e.optString("name") }.ifBlank { config.name },
                                u, e.optJSONObject("headers"), subsOf(e),
                                detail,
                            )?.let { out += it }
                        }
                    }
                    is String -> if (e.isNotBlank()) videoTo(config.name, e.trim(), null, null, null)?.let { out += it }
                    else -> {}
                }
            }
        }
        return out
    }

    private fun subsOf(o: JSONObject): List<SubtitleSource> {
        val arr = o.optJSONArray("subtitles") ?: o.optJSONArray("subs") ?: return emptyList()
        val out = ArrayList<SubtitleSource>()
        for (i in 0 until arr.length()) {
            when (val e = arr.opt(i)) {
                is JSONObject -> {
                    val u = e.optString("file").ifBlank { e.optString("url") }
                        .ifBlank { e.optString("uri") }.trim()
                    if (u.isBlank()) continue
                    out += SubtitleSource(
                        lang = e.optString("label").ifBlank { e.optString("language") }.ifBlank { "Sub" },
                        url = u,
                    )
                }
                is String -> if (e.isNotBlank()) out += SubtitleSource(lang = "Sub", url = e)
            }
        }
        return out
    }

    private fun videoTo(
        title: String, url: String, headers: JSONObject?,
        subs: List<SubtitleSource>?, quality: String?,
    ): StreamSource? {
        val u = url.trim()
        if (u.isBlank()) return null
        val h = LinkedHashMap<String, String>()
        headers?.keys()?.forEach { k ->
            val v = headers.optString(k).filter { it.code in 32..126 }
            if (v.isNotBlank()) h.putIfAbsent(k, v)
        }
        h.putIfAbsent("User-Agent", Http.UA)
        val isTorrent = u.startsWith("magnet:", true)
        return StreamSource(
            name = title.ifBlank { config.name },
            url = if (isTorrent) u else Http.normalizeDriveUrl(u),
            headers = h,
            subtitles = subs ?: emptyList(),
            isTorrent = isTorrent,
            isM3u8 = u.contains(".m3u8", true),
            isMpd = u.contains(".mpd", true),
            details = quality.orEmpty(),
            provider = "Anymex",
            providerId = config.id,
            providerName = config.name,
        )
    }

    private fun genresOf(d: JSONObject): List<String> {
        val g = d.opt("genre") ?: d.opt("genres") ?: return emptyList()
        return when (g) {
            is JSONArray -> (0 until g.length()).mapNotNull { g.optString(it).trim().ifBlank { null } }
            is String -> g.split(",").map { it.trim() }.filter { it.isNotBlank() }
            else -> emptyList()
        }
    }

    private fun fail(msg: String): List<StreamSource> {
        streamErrors[config.id] = msg
        lastOutcome[config.id] = msg.take(80)
        return emptyList()
    }
}
