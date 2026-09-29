package com.hikari.app.anymex

import com.hikari.app.data.CatalogRef
import com.hikari.app.data.Episode
import com.hikari.app.data.MediaItem
import com.hikari.app.data.MediaType
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.StreamSource
import com.hikari.app.providers.ContentProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class AnymexMangaProvider(override val config: ProviderConfig) : ContentProvider {

    companion object {
        val lastOutcome = ConcurrentHashMap<String, String>()
        const val CATALOG_POPULAR = "popular"
        const val CATALOG_LATEST = "latest"
        private const val MAX_CHAPTERS = 2000
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
            mapItems(raw)
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
        val arr = d.optJSONArray("chapters") ?: d.optJSONArray("episodes") ?: return@withContext null
        val out = ArrayList<Episode>()
        for (i in 0 until minOf(arr.length(), MAX_CHAPTERS)) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("url").ifBlank { o.optString("link") }.trim()
            if (url.isBlank()) continue
            val name = o.optString("name").trim().ifBlank { null }
            out += Episode(number = i + 1, id = url, name = name)
        }
        out.ifEmpty { null }
    }

    override suspend fun getStreams(item: MediaItem, episode: Episode?): List<StreamSource> =
        withContext(Dispatchers.IO) {
            val mod = module() ?: return@withContext emptyList()
            val chUrl = episode?.id?.takeIf { it.isNotBlank() } ?: return@withContext emptyList()
            val raw = AnymexRuntime.pages(mod, config.id, chUrl) ?: return@withContext emptyList()
            val arr = pageArray(raw) ?: return@withContext emptyList()
            val out = ArrayList<StreamSource>()
            for (i in 0 until arr.length()) {
                when (val e = arr.opt(i)) {
                    is JSONObject -> {
                        val u = e.optString("url").ifBlank { e.optString("imageUrl") }.trim()
                        if (u.isBlank()) continue
                        out += StreamSource(
                            name = "Page ${i + 1}",
                            url = u,
                            headers = mapHeaders(e.optJSONObject("headers")),
                            pageUrl = chUrl,
                            provider = "Anymex",
                            providerId = config.id,
                            providerName = config.name,
                        )
                    }
                    is String -> if (e.isNotBlank()) out += StreamSource(
                        name = "Page ${i + 1}",
                        url = e.trim(),
                        pageUrl = chUrl,
                        provider = "Anymex",
                        providerId = config.id,
                        providerName = config.name,
                    )
                    else -> {}
                }
            }
            lastOutcome[config.id] = "✓ ${out.size} pages"
            out
        }

    private fun mapHeaders(h: JSONObject?): Map<String, String> {
        if (h == null) return mapOf("User-Agent" to com.hikari.app.net.Http.UA)
        val m = LinkedHashMap<String, String>()
        h.keys().forEach { k ->
            val v = h.optString(k).filter { it.code in 32..126 }
            if (v.isNotBlank()) m.putIfAbsent(k, v)
        }
        m.putIfAbsent("User-Agent", com.hikari.app.net.Http.UA)
        return m
    }

    private fun mapItems(raw: String?): List<MediaItem> {
        if (raw.isNullOrBlank()) return emptyList()
        val t = raw.trim()
        val arr: JSONArray = runCatching { JSONArray(t) }.getOrNull()
            ?: runCatching { JSONObject(t).optJSONArray("list") }.getOrNull()
            ?: return emptyList()
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

    private fun pageArray(raw: String): JSONArray? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        runCatching { return JSONArray(t) }.getOrNull()?.let { return it }
        val o = runCatching { JSONObject(t) }.getOrNull() ?: return null
        o.optJSONArray("pages")?.let { return it }
        o.optJSONArray("list")?.let { return it }
        return null
    }

    private fun firstObject(raw: String): JSONObject? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        runCatching { return JSONObject(t) }.getOrNull()?.let { return it }
        runCatching {
            val arr = JSONArray(t)
            if (arr.length() > 0) return arr.optJSONObject(0)
        }
        return null
    }

    private fun genresOf(d: JSONObject): List<String> {
        val g = d.opt("genre") ?: d.opt("genres") ?: return emptyList()
        return when (g) {
            is JSONArray -> (0 until g.length()).mapNotNull { g.optString(it).trim().ifBlank { null } }
            is String -> g.split(",").map { it.trim() }.filter { it.isNotBlank() }
            else -> emptyList()
        }
    }
}
