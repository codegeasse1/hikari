package com.hikari.app.anymex

import com.hikari.app.data.CatalogRef
import com.hikari.app.data.Episode
import com.hikari.app.data.MediaItem
import com.hikari.app.data.MediaType
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.StreamSource
import com.hikari.app.net.Http
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
            val p = page.coerceAtLeast(1)
            val raw = when (ref.id) {
                CATALOG_LATEST -> AnymexRuntime.latest(mod, config.id, p)
                else -> AnymexRuntime.popular(mod, config.id, p)
            }
            val fromJs = mapItems(raw)
            if (fromJs.isNotEmpty()) return@withContext fromJs
            // Native fallbacks for well-known sources when the JS harness fails
            // (stale meta, site HTML changes, missing apiUrl on old installs).
            val native = nativeCatalog(ref.id, p)
            if (native.isNotEmpty()) {
                lastOutcome[config.id] = "✓ ${native.size} titles (native)"
            }
            native
        }

    override suspend fun search(query: String, page: Int): List<MediaItem> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            val mod = module() ?: return@withContext emptyList()
            val fromJs = mapItems(AnymexRuntime.search(mod, config.id, query, page.coerceAtLeast(1)))
            if (fromJs.isNotEmpty()) return@withContext fromJs
            nativeSearch(query, page.coerceAtLeast(1))
        }

    private fun isMangaDex(): Boolean {
        val n = config.name.lowercase()
        val extra = config.extra.orEmpty().lowercase()
        return "mangadex" in n || "mangadex" in extra
    }

    private fun isWebtoons(): Boolean {
        val n = config.name.lowercase()
        val extra = config.extra.orEmpty().lowercase()
        return "webtoon" in n || "webtoons" in extra
    }

    private fun nativeCatalog(catalogId: String, page: Int): List<MediaItem> = when {
        isMangaDex() -> nativeMangaDex(catalogId, page)
        isWebtoons() -> nativeWebtoons(catalogId, page)
        else -> emptyList()
    }

    private fun nativeSearch(query: String, page: Int): List<MediaItem> = when {
        isMangaDex() -> nativeMangaDexSearch(query, page)
        else -> emptyList()
    }

    private fun nativeMangaDex(catalogId: String, page: Int): List<MediaItem> {
        val offset = 20 * (page - 1)
        val order = if (catalogId == CATALOG_LATEST) "order[latestUploadedChapter]=desc"
            else "order[followedCount]=desc"
        val url = "https://api.mangadex.org/manga?limit=20&offset=$offset" +
            "&availableTranslatedLanguage[]=en&includes[]=cover_art" +
            "&contentRating[]=safe&contentRating[]=suggestive&$order"
        return parseMangaDexList(url)
    }

    private fun nativeMangaDexSearch(query: String, page: Int): List<MediaItem> {
        val offset = 20 * (page - 1)
        val q = java.net.URLEncoder.encode(query, Charsets.UTF_8.name())
        val url = "https://api.mangadex.org/manga?limit=20&offset=$offset&title=$q" +
            "&includes[]=cover_art&contentRating[]=safe&contentRating[]=suggestive" +
            "&availableTranslatedLanguage[]=en"
        return parseMangaDexList(url)
    }

    private fun parseMangaDexList(url: String): List<MediaItem> {
        val body = Http.fetchStringRobust(
            url,
            mapOf(
                "User-Agent" to Http.UA,
                "Accept" to "application/json",
                "Referer" to "https://mangadex.org/",
                "Origin" to "https://mangadex.org",
            ),
        ).getOrNull().orEmpty()
        if (body.isBlank()) {
            lastOutcome[config.id] = "✗ MangaDex API empty"
            return emptyList()
        }
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val data = root.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<MediaItem>()
        for (i in 0 until data.length()) {
            val e = data.optJSONObject(i) ?: continue
            val id = e.optString("id").trim()
            if (id.isBlank()) continue
            val attrs = e.optJSONObject("attributes") ?: continue
            val titles = attrs.optJSONObject("title") ?: JSONObject()
            val name = titles.optString("en").ifBlank {
                val keys = titles.keys()
                var found = ""
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = titles.optString(k)
                    if (v.isNotBlank()) { found = v; break }
                }
                found
            }
            if (name.isBlank()) continue
            var cover: String? = null
            val rels = e.optJSONArray("relationships")
            if (rels != null) {
                for (r in 0 until rels.length()) {
                    val rel = rels.optJSONObject(r) ?: continue
                    if (rel.optString("type") != "cover_art") continue
                    val fn = rel.optJSONObject("attributes")?.optString("fileName").orEmpty()
                    if (fn.isNotBlank()) {
                        cover = "https://uploads.mangadex.org/covers/$id/$fn"
                        break
                    }
                }
            }
            out += MediaItem(
                providerId = config.id,
                id = "/manga/$id",
                title = name,
                type = MediaType.SERIES,
                posterUrl = cover,
            )
        }
        return out
    }

    private fun nativeWebtoons(catalogId: String, page: Int): List<MediaItem> {
        if (page > 1) return emptyList()
        val sort = if (catalogId == CATALOG_LATEST) "?sortOrder=UPDATE" else ""
        val url = "https://www.webtoons.com/en/originals$sort"
        val html = Http.fetchStringRobust(
            url,
            mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
                "Accept" to "text/html",
                "Referer" to "https://www.webtoons.com/",
            ),
        ).getOrNull().orEmpty()
        if (html.isBlank()) {
            lastOutcome[config.id] = "✗ Webtoons page empty"
            return emptyList()
        }
        // Current site: <a ... href="https://www.webtoons.com/en/..."> with nested title
        val out = ArrayList<MediaItem>()
        val seen = HashSet<String>()
        // href + title patterns from current HTML
        val linkRe = Regex(
            """href="(https://www\.webtoons\.com/en/[^"]+/list\?[^"]+)"[^>]*>[\s\S]*?(?:class="[^"]*title[^"]*"[^>]*>([^<]+)|<p class="subj">([^<]+)|<strong[^>]*>([^<]+))""",
            RegexOption.IGNORE_CASE,
        )
        for (m in linkRe.findAll(html)) {
            val href = m.groupValues[1].trim()
            val title = (m.groupValues[2].ifBlank { m.groupValues[3] }.ifBlank { m.groupValues[4] }).trim()
            if (href.isBlank() || title.isBlank()) continue
            if (!seen.add(href)) continue
            out += MediaItem(
                providerId = config.id,
                id = href,
                title = title.replace("&amp;", "&").replace("&#39;", "'"),
                type = MediaType.SERIES,
                posterUrl = null,
            )
            if (out.size >= 60) break
        }
        // Fallback: any /list? titleCard links
        if (out.isEmpty()) {
            val simple = Regex("""href="(https://www\.webtoons\.com/en/[^"]+)"[^>]*class="[^"]*link[^"]*"[^>]*>""")
            for (m in simple.findAll(html)) {
                val href = m.groupValues[1]
                if ("/list?" !in href && "/episode" in href) continue
                if (!seen.add(href)) continue
                val name = href.substringAfterLast('/').substringBefore('?').replace('-', ' ')
                if (name.length < 2) continue
                out += MediaItem(
                    providerId = config.id,
                    id = href,
                    title = name.replaceFirstChar { it.uppercase() },
                    type = MediaType.SERIES,
                )
                if (out.size >= 40) break
            }
        }
        return out
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
        if (raw.isNullOrBlank()) {
            lastOutcome[config.id] = "✗ Empty catalog response"
            return emptyList()
        }
        val t = raw.trim()
        // Runtime returns {ok,data} envelopes — same as AnymexProvider.listArray.
        val arr: JSONArray? = runCatching { JSONArray(t) }.getOrNull()
            ?: runCatching {
                val o = JSONObject(t)
                if (o.has("ok") && !o.optBoolean("ok", true)) {
                    lastOutcome[config.id] = "✗ " + o.optString("error").ifBlank { "catalog error" }
                    return emptyList()
                }
                // {ok,data:{list:[...]}} from harness, or bare {list:[...]}
                fun digList(x: JSONObject?): JSONArray? {
                    if (x == null) return null
                    x.optJSONArray("list")?.let { return it }
                    x.optJSONArray("manga")?.let { return it }
                    x.optJSONArray("results")?.let { return it }
                    x.optJSONArray("data")?.let { return it }
                    x.optJSONObject("data")?.let { return digList(it) }
                    return null
                }
                digList(o)
            }.getOrNull()
        if (arr == null || arr.length() == 0) {
            if (lastOutcome[config.id]?.startsWith("✗") != true) {
                lastOutcome[config.id] = "✗ No titles in catalog"
            }
            return emptyList()
        }
        val out = ArrayList<MediaItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name").ifBlank { o.optString("title") }.trim()
            if (name.isBlank()) continue
            val link = o.optString("link").ifBlank { o.optString("url") }
                .ifBlank { o.optString("id") }.trim()
            if (link.isBlank()) continue
            out += MediaItem(
                providerId = config.id,
                id = link,
                title = name,
                type = MediaType.SERIES,
                posterUrl = o.optString("imageUrl").ifBlank { o.optString("image") }
                    .ifBlank { o.optString("cover") }.trim().ifBlank { null },
            )
        }
        if (out.isNotEmpty()) lastOutcome[config.id] = "✓ ${out.size} titles"
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
