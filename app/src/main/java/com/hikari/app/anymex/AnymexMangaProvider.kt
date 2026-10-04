package com.hikari.app.anymex

import com.hikari.app.data.CatalogRef
import com.hikari.app.data.Episode
import com.hikari.app.data.MediaItem
import com.hikari.app.data.MediaType
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.StreamSource
import com.hikari.app.manga.MangaChapter
import com.hikari.app.manga.MangaStore
import com.hikari.app.net.CloudflareVerifier
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
            val p = page.coerceAtLeast(1)
            // Known sites: native first (JS harness is unreliable for MangaDex
            // multi-lang + Webtoons HTML changes). JS still tried if native fails.
            if (isMangaDex() || isWebtoons() || isComick()) {
                val native = nativeCatalog(ref.id, p)
                if (native.isNotEmpty()) {
                    lastOutcome[config.id] = "✓ ${native.size} titles"
                    return@withContext native
                }
            }
            val mod = module() ?: return@withContext emptyList()
            val firstRaw = when (ref.id) {
                CATALOG_LATEST -> AnymexRuntime.latest(mod, config.id, p)
                else -> AnymexRuntime.popular(mod, config.id, p)
            }
            val fromJs = mapItems(firstRaw).ifEmpty {
                mapItems(
                    when (ref.id) {
                        CATALOG_LATEST -> AnymexRuntime.popular(mod, config.id, p)
                        else -> AnymexRuntime.latest(mod, config.id, p)
                    }
                )
            }
            if (fromJs.isNotEmpty()) {
                lastOutcome[config.id] = "✓ ${fromJs.size} titles (js)"
                return@withContext fromJs
            }
            val native = nativeCatalog(ref.id, p)
            if (native.isNotEmpty()) {
                lastOutcome[config.id] = "✓ ${native.size} titles (native)"
            } else {
                lastOutcome[config.id] = lastOutcome[config.id] ?: "✗ empty catalog"
            }
            native
        }

    override suspend fun search(query: String, page: Int): List<MediaItem> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            val p = page.coerceAtLeast(1)
            if (isMangaDex()) {
                val native = nativeSearch(query, p)
                if (native.isNotEmpty()) return@withContext native
            }
            val mod = module() ?: return@withContext nativeSearch(query, p)
            val fromJs = mapItems(AnymexRuntime.search(mod, config.id, query, p))
            if (fromJs.isNotEmpty()) return@withContext fromJs
            nativeSearch(query, p)
        }

    private fun isMangaDex(): Boolean {
        val blob = (config.name + " " + config.extra.orEmpty() + " " + config.id + " " + config.url).lowercase()
        return "mangadex" in blob
    }

    private fun isWebtoons(): Boolean {
        val blob = (config.name + " " + config.extra.orEmpty() + " " + config.id + " " + config.url).lowercase()
        // Webtoon Hatti is a different (Dart) site — don't claim it
        if ("webtoonhatti" in blob || "webtoon hatti" in blob) return false
        return "webtoon" in blob
    }

    private fun isComick(): Boolean {
        val blob = (config.name + " " + config.extra.orEmpty() + " " + config.id + " " + config.url).lowercase()
        return "comick" in blob
    }

    private fun nativeCatalog(catalogId: String, page: Int): List<MediaItem> = when {
        isMangaDex() -> nativeMangaDex(catalogId, page)
        isWebtoons() -> nativeWebtoons(catalogId, page)
        isComick() -> nativeComick(catalogId, page)
        else -> emptyList()
    }

    private fun nativeSearch(query: String, page: Int): List<MediaItem> = when {
        isMangaDex() -> nativeMangaDexSearch(query, page)
        isComick() -> nativeComickSearch(query, page)
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
        val out = ArrayList<MediaItem>()
        val seen = HashSet<String>()
        // Live HTML (2026): <a href="https://m.webtoons.com/en/.../list?title_no=N"
        // class="link _titleItem"> ... <strong class="title">Name</strong>
        val re = Regex(
            """href="(https://(?:m\.)?webtoons\.com/[^"]+)"[^>]*class="[^"]*link[^"]*"[^>]*>[\s\S]*?<strong class="title">([^<]+)</strong>""",
            RegexOption.IGNORE_CASE,
        )
        for (m in re.findAll(html)) {
            var href = m.groupValues[1].trim()
            val title = m.groupValues[2].trim()
                .replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\"")
            if (title.isBlank()) continue
            // Normalize mobile → desktop for later detail loads
            href = href.replace("https://m.webtoons.com/", "https://www.webtoons.com/")
            if (!seen.add(href)) continue
            // Poster from nearby img if present in the match window is optional
            out += MediaItem(
                providerId = config.id,
                id = href,
                title = title,
                type = MediaType.SERIES,
                posterUrl = null,
            )
            if (out.size >= 80) break
        }
        if (out.isEmpty()) {
            // Simpler: any title_no link + following title text within 800 chars
            val simple = Regex(
                """href="(https://(?:m\.)?webtoons\.com/[^"]*title_no=\d+[^"]*)"[\s\S]{0,800}?<strong class="title">([^<]+)</strong>""",
                RegexOption.IGNORE_CASE,
            )
            for (m in simple.findAll(html)) {
                var href = m.groupValues[1].replace("https://m.webtoons.com/", "https://www.webtoons.com/")
                val title = m.groupValues[2].trim()
                if (title.isBlank() || !seen.add(href)) continue
                out += MediaItem(
                    providerId = config.id,
                    id = href,
                    title = title.replace("&amp;", "&"),
                    type = MediaType.SERIES,
                )
                if (out.size >= 80) break
            }
        }
        if (out.isEmpty()) lastOutcome[config.id] = "✗ Webtoons parse 0 (html ${html.length})"
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
        // Native first for sites we know
        val native = when {
            isMangaDex() -> nativeMangaDexChapters(item)
            isComick() -> nativeComickChapters(item)
            else -> emptyList()
        }
        val episodes = if (native.isNotEmpty()) native else {
            val mod = module() ?: return@withContext null
            val raw = AnymexRuntime.detail(mod, config.id, item.id) ?: return@withContext null
            val d = firstObject(raw) ?: return@withContext null
            val arr = d.optJSONArray("chapters") ?: d.optJSONArray("episodes")
                ?: return@withContext null
            val out = ArrayList<Episode>()
            for (i in 0 until minOf(arr.length(), MAX_CHAPTERS)) {
                val o = arr.optJSONObject(i) ?: continue
                val url = o.optString("url").ifBlank { o.optString("link") }.trim()
                if (url.isBlank()) continue
                val name = o.optString("name").trim().ifBlank { null }
                out += Episode(number = i + 1, id = url, name = name)
            }
            out
        }
        if (episodes.isEmpty()) {
            lastOutcome[config.id] = "✗ no chapters"
            return@withContext null
        }
        // Detail screen only reads MangaStore — always bridge.
        val key = item.providerId + "|" + item.id
        MangaStore.putChapters(
            key,
            episodes.map { e ->
                MangaChapter(
                    url = e.id,
                    name = e.name ?: "Chapter ${e.number}",
                    number = e.number.toFloat(),
                )
            },
        )
        lastOutcome[config.id] = "✓ ${episodes.size} chapters"
        episodes
    }

    override suspend fun getStreams(item: MediaItem, episode: Episode?): List<StreamSource> =
        withContext(Dispatchers.IO) {
            val chUrl = episode?.id?.takeIf { it.isNotBlank() } ?: return@withContext emptyList()
            // Native MangaDex at-home server (no JS module required)
            if (isMangaDex()) {
                val pages = nativeMangaDexPages(chUrl)
                if (pages.isNotEmpty()) {
                    lastOutcome[config.id] = "✓ ${pages.size} pages"
                    return@withContext pages
                }
            }
            val mod = module() ?: return@withContext emptyList()
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

    private fun nativeMangaDexPages(chapterId: String): List<StreamSource> {
        val id = chapterId.substringAfterLast("/").substringBefore("?").trim()
        if (id.length < 30) return emptyList()
        val raw = Http.fetchStringRobust(
            "https://api.mangadex.org/at-home/server/$id",
            mapOf("User-Agent" to Http.UA, "Accept" to "application/json"),
        ).getOrNull().orEmpty()
        if (raw.isBlank()) return emptyList()
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
        val base = root.optString("baseUrl").trim().trimEnd('/')
        val chap = root.optJSONObject("chapter") ?: return emptyList()
        val hash = chap.optString("hash")
        val data = chap.optJSONArray("data") ?: chap.optJSONArray("dataSaver") ?: return emptyList()
        if (base.isBlank() || hash.isBlank()) return emptyList()
        val out = ArrayList<StreamSource>()
        for (i in 0 until data.length()) {
            val file = data.optString(i)
            if (file.isBlank()) continue
            val u = "$base/data/$hash/$file"
            out += StreamSource(
                name = "Page ${i + 1}",
                url = u,
                headers = mapOf("User-Agent" to Http.UA, "Referer" to "https://mangadex.org/"),
                pageUrl = chapterId,
                provider = "Anymex",
                providerId = config.id,
                providerName = config.name,
            )
        }
        return out
    }


    private fun nativeComick(catalogId: String, page: Int): List<MediaItem> {
        val sort = if (catalogId == CATALOG_LATEST) "uploaded" else "follow"
        val url = "https://api.comick.fun/v1.0/search?sort=$sort&page=${page.coerceAtLeast(1)}&tachiyomi=true"
        return parseComickList(url)
    }

    private fun nativeComickSearch(query: String, page: Int): List<MediaItem> {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "https://api.comick.fun/v1.0/search?q=$q&page=${page.coerceAtLeast(1)}&tachiyomi=true"
        return parseComickList(url)
    }

    private fun parseComickList(url: String): List<MediaItem> {
        val raw = Http.fetchStringRobust(
            url,
            mapOf(
                "User-Agent" to "Tachiyomi Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:110.0) Gecko/20100101 Firefox/110.0",
                "Referer" to "https://comick.io/",
                "Accept" to "application/json",
            ),
        ).getOrNull().orEmpty()
        if (raw.isBlank() || raw.trimStart().startsWith("<")) {
            lastOutcome[config.id] = "✗ Comick API empty"
            return emptyList()
        }
        val arr = runCatching {
            val t = raw.trim()
            if (t.startsWith("[")) JSONArray(t)
            else {
                val o = JSONObject(t)
                o.optJSONArray("data") ?: o.optJSONArray("comics")
            }
        }.getOrNull()
        if (arr == null) {
            lastOutcome[config.id] = "✗ Comick JSON"
            return emptyList()
        }
        val out = ArrayList<MediaItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = o.optString("title").ifBlank { o.optString("name") }.trim()
            if (title.isBlank()) continue
            val slug = o.optString("slug").ifBlank { o.optString("hid") }.trim()
            val hid = o.optString("hid").ifBlank { slug }
            if (hid.isBlank()) continue
            val cover = o.optString("cover_url").ifBlank {
                o.optJSONArray("md_covers")?.optJSONObject(0)?.optString("b2key").orEmpty()
            }.ifBlank { o.optString("cover") }
            val poster = when {
                cover.isBlank() -> null
                cover.startsWith("http") -> cover
                else -> "https://meo.comick.pictures/$cover"
            }
            out += MediaItem(
                providerId = config.id,
                id = hid,
                title = title,
                type = MediaType.SERIES,
                posterUrl = poster,
                rawType = "manga",
            )
        }
        if (out.isEmpty()) lastOutcome[config.id] = "✗ Comick parse 0"
        return out
    }

    private fun nativeComickChapters(item: MediaItem): List<Episode> {
        val hid = item.id.trim()
        if (hid.isBlank()) return emptyList()
        val url = "https://api.comick.fun/comic/$hid/chapters?lang=en&limit=100&tachiyomi=true"
        val raw = Http.fetchStringRobust(
            url,
            mapOf(
                "User-Agent" to "Tachiyomi Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:110.0) Gecko/20100101 Firefox/110.0",
                "Referer" to "https://comick.io/",
                "Accept" to "application/json",
            ),
        ).getOrNull().orEmpty()
        if (raw.isBlank()) return emptyList()
        val arr = runCatching {
            val o = JSONObject(raw)
            o.optJSONArray("chapters") ?: o.optJSONArray("data")
        }.getOrNull() ?: return emptyList()
        val out = ArrayList<Episode>()
        for (i in 0 until minOf(arr.length(), MAX_CHAPTERS)) {
            val c = arr.optJSONObject(i) ?: continue
            val chapHid = c.optString("hid").ifBlank { c.optString("id") }.trim()
            if (chapHid.isBlank()) continue
            val chap = c.optString("chap").ifBlank { c.optString("chapter") }
            val name = c.optString("title").ifBlank {
                if (chap.isNotBlank()) "Chapter $chap" else "Chapter ${i + 1}"
            }
            val num = chap.toFloatOrNull()?.toInt() ?: (i + 1)
            out += Episode(number = num, id = chapHid, name = name)
        }
        return out
    }

    private fun nativeMangaDexChapters(item: MediaItem): List<Episode> {
        val uuid = item.id.substringAfterLast("/").substringBefore("?").trim()
        if (uuid.length < 30) return emptyList()
        val url = "https://api.mangadex.org/manga/$uuid/feed?" +
            "limit=100&order%5Bchapter%5D=desc&translatedLanguage%5B%5D=en&" +
            "contentRating%5B%5D=safe&contentRating%5B%5D=suggestive&contentRating%5B%5D=erotica"
        val raw = Http.fetchStringRobust(
            url,
            mapOf("User-Agent" to Http.UA, "Accept" to "application/json"),
        ).getOrNull().orEmpty()
        if (raw.isBlank()) return emptyList()
        val arr = runCatching { JSONObject(raw).optJSONArray("data") }.getOrNull() ?: return emptyList()
        val out = ArrayList<Episode>()
        for (i in 0 until minOf(arr.length(), MAX_CHAPTERS)) {
            val c = arr.optJSONObject(i) ?: continue
            val id = c.optString("id").trim()
            if (id.isBlank()) continue
            val attrs = c.optJSONObject("attributes") ?: JSONObject()
            val chap = attrs.optString("chapter")
            val title = attrs.optString("title")
            val name = when {
                title.isNotBlank() && chap.isNotBlank() -> "Ch. $chap — $title"
                chap.isNotBlank() -> "Chapter $chap"
                title.isNotBlank() -> title
                else -> "Chapter ${i + 1}"
            }
            val num = chap.toFloatOrNull()?.toInt() ?: (i + 1)
            out += Episode(number = num, id = id, name = name)
        }
        return out
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

    /**
     * Teaches Coil image loader the extension site Referer for a cover host.
     */
    private fun recordPosterReferer(url: String?) {
        val u = url?.trim().orEmpty()
        if (u.isBlank() || !u.startsWith("http")) return
        val siteBase = runCatching { AnymexPluginManager.siteUrlOf(config) }.getOrNull()
            ?.trim()?.trimEnd('/')?.ifBlank { null }
        val ref = if (siteBase != null) siteBase + "/" else runCatching {
            val hh = java.net.URI(u).host ?: return
            "https://" + hh + "/"
        }.getOrNull() ?: return
        runCatching {
            val host = java.net.URI(u).host?.lowercase() ?: return@runCatching
            val m = com.hikari.app.cs3.Cs3MainApiProvider.imageHostReferers
            m.putIfAbsent(host, ref)
            m.putIfAbsent("www." + host, ref)
        }
    }

    private val wallRe = Regex(
        "\\b(403|429|503)\\b|cloudflare|just a moment|verify you are human|cf-chl|access denied",
        RegexOption.IGNORE_CASE,
    )

    /** Failed behind a bot wall: remember the site so Verify points at it. */
    private fun markWall(text: String?) {
        val t = text.orEmpty()
        if (t.isBlank() || !wallRe.containsMatchIn(t)) return
        val site = runCatching { AnymexPluginManager.siteUrlOf(config) }.getOrNull()
        if (!site.isNullOrBlank()) CloudflareVerifier.markBlocked(site)
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
                    val err = o.optString("error").ifBlank { "catalog error" }
                    lastOutcome[config.id] = "✗ " + err
                    markWall(err)
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
                .ifBlank { o.optString("href") }.ifBlank { o.optString("slug") }
                .ifBlank { o.optString("id") }.trim()
            if (link.isBlank()) continue
            val poster = o.optString("imageUrl").ifBlank { o.optString("image") }
                .ifBlank { o.optString("cover") }.ifBlank { o.optString("poster") }
                .ifBlank { o.optString("thumbnail") }.trim().ifBlank { null }
                ?.takeIf { !it.startsWith("data:") }
            if (!poster.isNullOrBlank()) recordPosterReferer(poster)
            out += MediaItem(
                providerId = config.id,
                id = link,
                title = name,
                type = MediaType.SERIES,
                posterUrl = poster,
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
