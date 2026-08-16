package com.hikari.app.providers

import com.hikari.app.data.CatalogRef
import com.hikari.app.data.Episode
import com.hikari.app.data.MediaItem
import com.hikari.app.data.MediaType
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.StreamSource
import com.hikari.app.data.SubtitleSource
import com.hikari.app.net.Http
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONObject

/**
 * A JSON-rule based scraper. The `extra` field holds the full config.
 *
 * Two modes:
 *
 * 1) HTML mode — plain selector rules:
 * {
 *   "name": "MySite", "baseUrl": "https://...", "homeUrl": "/",
 *   "catalogs": [{"id":"home","name":"Home","type":"movie"}],
 *   "search": { "url": "/search?q={query}", "item": ".item", "title": ".title",
 *               "href": "a@href", "poster": "img@src" },
 *   "detail": { "title": "h1", "poster": ".poster img@src", "overview": ".desc", "type": "series" },
 *   "episodes": { "url": "{href}", "item": ".episode", "number": "data-ep", "href": "a@href" },
 *   "streams": { "video": "video@src", "m3u8": "source[type*=m3u8]@src", "iframe": "iframe@src" }
 * }
 *
 * 2) JSON-API mode — for SPA/API-only sites like JustAnime. Add an "api" block:
 * {
 *   "name": "JustAnime", "baseUrl": "https://justanime.to",
 *   "api": {
 *     "base": "https://core.justanime.to/api",
 *     "proxy": "https://neko.justanime.to/m3u8-proxy",
 *     "headers": { "User-Agent": "...", "Accept": "...", "Referer": "https://justanime.to/",
 *                  "Origin": "https://justanime.to" },
 *     "catalogs": [ {"id":"trending","name":"Trending","type":"series"}, ... ],
 *     "homePath": "/home",
 *     "searchPath": "/search", "searchResults": "results",
 *     "detailPath": "/anime/{id}", "detailData": "data",
 *     "episodesPath": "/anime/{id}/episodes", "episodesPageParam": "page",
 *     "episodesItems": "episodes", "episodesHasNext": "hasNextPage", "episodesMaxPages": 30,
 *     "episodeNumber": "number", "episodeName": "title",
 *     "streamsPath": "/watch/{id}/episode/{ep}/anineko/{lang}/hd1",
 *     "streamsLangs": "sub,dub", "streamsSources": "sources",
 *     "streamsUrl": "url", "streamsQuality": "quality", "streamsIsM3u8": "isM3U8",
 *     "streamsSubtitles": "subtitles", "streamsSubUrl": "url", "streamsSubLang": "lang",
 *     "streamsHeaders": "headers", "proxyStreams": true
 *   }
 * }
 *
 * API mode conventions (matching JustAnime's API; tune fields via config):
 * - list items: id, title {english/romaji} or string, cover | coverImage.extraLarge | bannerImage
 * - detail: format == "MOVIE" -> movie, otherwise series
 * - episode stream pages use {id}/{ep}/{lang} path placeholders
 */
class UniversalScraper(override val config: ProviderConfig) : ContentProvider {

    private val conf = runCatching { JSONObject(config.extra ?: "{}") }.getOrDefault(JSONObject())
    private val api = conf.optJSONObject("api")
    private val base: String get() = conf.optString("baseUrl").trimEnd('/')
    private var homeJson: JSONObject? = null

    private fun typeOf(t: String): MediaType = when (t.lowercase()) {
        "movie" -> MediaType.MOVIE
        "series" -> MediaType.SERIES
        else -> MediaType.UNKNOWN
    }

    private fun absUrl(u: String): String =
        if (u.startsWith("http")) u else base + (if (u.startsWith("/")) "" else "/") + u

    private fun pick(scope: Element, selector: String, attr: String?): String? {
        if (selector.isBlank()) return null
        val el = scope.select(selector).first() ?: return null
        return if (attr != null) el.attr(attr).ifBlank { null } else el.text().trim().ifBlank { null }
    }

    private fun enc(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private suspend fun scrapeList(url: String, rules: JSONObject): List<MediaItem> {
        val html = Http.getString(url) ?: return emptyList()
        val doc = runCatching { Jsoup.parse(html, url) }.getOrNull() ?: return emptyList()
        val itemSel = rules.optString("item")
        if (itemSel.isBlank()) return emptyList()
        val titleSel = rules.optString("title").ifBlank { "h3, h2, .title, a" }
        val hrefSel = rules.optString("href").ifBlank { "a" }
        val posterSel = rules.optString("poster").ifBlank { "img" }
        val yearSel = rules.optString("year").ifBlank { null }
        val out = mutableListOf<MediaItem>()
        for (el in doc.select(itemSel)) {
            val title = pick(el, titleSel, null) ?: continue
            if (title.isBlank()) continue
            val href = el.select(hrefSel).first()?.attr("abs:href")
            val poster = el.select(posterSel).first()?.attr("abs:src")
            val year = yearSel?.let { pick(el, it, null) }
                ?.let { s -> s.filter { c -> c.isDigit() }.take(4).toIntOrNull() }
            out += MediaItem(
                providerId = config.id,
                id = href ?: title,
                title = title,
                type = MediaType.UNKNOWN,
                posterUrl = poster?.ifBlank { null },
                year = year,
            )
        }
        return out
    }

    // ---------------------------------------------------------------
    // JSON-API mode helpers
    // ---------------------------------------------------------------

    private fun apiHeaders(): Map<String, String> {
        val h = api?.optJSONObject("headers") ?: return emptyMap()
        val out = HashMap<String, String>()
        val keys = h.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            out[k] = h.optString(k)
        }
        return out
    }

    private fun proxyWrap(target: String, headerJson: String): String {
        val p = api?.optString("proxy")?.takeIf { it.isNotBlank() } ?: return target
        return p + "?url=" + enc(target) + "&headers=" + enc(headerJson)
    }

    private fun parseApi(text: String): JSONObject? = runCatching {
        val j = JSONObject(text)
        if (j.has("error")) null else j
    }.getOrNull()

    /**
     * Fetches an API endpoint. Tries the API base directly first (it works
     * when the API accepts our headers), then falls back to the site's own
     * proxy (some CDNs only accept the site's Origin) twice.
     */
    private suspend fun apiGet(path: String, params: Map<String, String> = emptyMap()): JSONObject? {
        val apiBase = api?.optString("base")?.takeIf { it.isNotBlank() } ?: return null
        val q = if (params.isEmpty()) "" else "?" + params.entries.joinToString("&") { (k, v) ->
            "${enc(k)}=${enc(v)}"
        }
        val url = apiBase.trimEnd('/') + path + q
        val headers = apiHeaders()
        Http.getStringStrict(url, headers).getOrNull()?.let { parseApi(it) }?.let { return it }
        if (api?.optString("proxy")?.isBlank() != false) return null
        val headerJson = JSONObject(headers).toString()
        for (i in 0 until 2) {
            Http.getStringStrict(proxyWrap(url, headerJson), headers)
                .getOrNull()?.let { parseApi(it) }?.let { return it }
        }
        return null
    }

    private suspend fun apiHome(): JSONObject? {
        homeJson?.let { return it }
        val path = api?.optString("homePath")?.ifBlank { "/home" } ?: "/home"
        return apiGet(path)?.also { homeJson = it }
    }

    private fun titleOf(o: JSONObject): String {
        val t = o.opt("title")
        if (t is JSONObject) {
            val eng = t.optString("english").ifBlank { t.optString("romaji") }
            if (eng.isNotBlank()) return eng.trim()
        } else if (t is String && t.isNotBlank()) return t.trim()
        return o.optString("title").trim()
    }

    private fun posterOf(o: JSONObject): String? {
        val coverImage = o.optJSONObject("coverImage")?.optString("extraLarge").orEmpty()
        val cover = o.optString("cover")
        val banner = o.optString("bannerImage")
        return listOf(coverImage, cover, banner).firstOrNull {
            it.startsWith("http://") || it.startsWith("https://")
        }
    }

    private fun typeOfApi(o: JSONObject): MediaType {
        val f = o.optString("format").ifBlank { o.optString("type") }
        return if (f.equals("MOVIE", true)) MediaType.MOVIE else MediaType.SERIES
    }

    private fun toApiMedia(o: JSONObject): MediaItem? {
        val id = o.optString("id").ifBlank { return null }
        val title = titleOf(o).ifBlank { return null }
        val year = o.optInt("year", 0).takeIf { it > 0 }
        return MediaItem(
            providerId = config.id,
            id = id,
            title = title,
            type = typeOfApi(o),
            posterUrl = posterOf(o),
            year = year,
        )
    }

    private suspend fun getApiCatalog(ref: CatalogRef): List<MediaItem> {
        val json = apiHome() ?: return emptyList()
        val arr = json.optJSONArray(ref.id) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i -> toApiMedia(arr.optJSONObject(i)) }
    }

    private suspend fun getApiSearch(query: String, page: Int): List<MediaItem> {
        val path = api?.optString("searchPath")?.ifBlank { "/search" } ?: "/search"
        val qParam = api?.optString("searchQueryParam")?.ifBlank { "query" } ?: "query"
        val pParam = api?.optString("searchPageParam")?.ifBlank { "page" } ?: "page"
        val json = apiGet(path, mapOf(qParam to query, pParam to page.toString())) ?: return emptyList()
        val arr = json.optJSONArray(api?.optString("searchResults")?.ifBlank { "results" } ?: "results")
            ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i -> toApiMedia(arr.optJSONObject(i)) }
    }

    private suspend fun getApiMeta(item: MediaItem): MediaItem {
        val path = api?.optString("detailPath")?.ifBlank { "/anime/{id}" } ?: "/anime/{id}"
        val json = apiGet(path.replace("{id}", item.id)) ?: return item
        val d = (api?.optString("detailData")?.ifBlank { null }?.let { json.optJSONObject(it) }) ?: json
        val title = titleOf(d).ifBlank { item.title }
        val overview = d.optString("description")
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { item.overview }
        val genres = d.optJSONArray("genres")
            ?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }
            ?: item.genres
        val year = d.optInt("seasonYear", 0).takeIf { it > 0 }
            ?: d.optInt("year", 0).takeIf { it > 0 }
            ?: item.year
        val type = typeOfApi(d).takeIf { it != MediaType.UNKNOWN } ?: item.type
        return item.copy(
            title = title,
            type = type,
            posterUrl = posterOf(d) ?: item.posterUrl,
            year = year,
            overview = overview,
            genres = genres,
            backdropUrl = d.optString("bannerImage").takeIf { it.startsWith("http") } ?: item.backdropUrl,
        )
    }

    private suspend fun getApiEpisodes(item: MediaItem): List<Episode>? {
        val path = api?.optString("episodesPath")?.ifBlank { "/anime/{id}/episodes" } ?: "/anime/{id}/episodes"
        val basePath = path.replace("{id}", item.id)
        val itemsKey = api?.optString("episodesItems")?.ifBlank { "episodes" } ?: "episodes"
        val hasNextKey = api?.optString("episodesHasNext")?.ifBlank { "hasNextPage" } ?: "hasNextPage"
        val pageParam = api?.optString("episodesPageParam")?.ifBlank { "page" } ?: "page"
        val maxPages = api?.optInt("episodesMaxPages", 30) ?: 30
        val numField = api?.optString("episodeNumber")?.ifBlank { "number" } ?: "number"
        val nameField = api?.optString("episodeName")?.ifBlank { "title" } ?: "title"
        val out = mutableListOf<Episode>()
        var page = 1
        while (page <= maxPages) {
            val json = apiGet(basePath, mapOf(pageParam to page.toString())) ?: break
            val arr = json.optJSONArray(itemsKey) ?: break
            if (arr.length() == 0) break
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val num = o.optInt(numField, 0)
                if (num <= 0) continue
                out += Episode(num, "${item.id}|$num", o.optString(nameField).trim().ifBlank { null })
            }
            if (!json.optBoolean(hasNextKey, false)) break
            page++
        }
        return out.distinctBy { it.number }.sortedBy { it.number }.ifEmpty { null }
    }

    private suspend fun getApiStreams(item: MediaItem, episode: Episode?): List<StreamSource> {
        val path = api?.optString("streamsPath")
            ?.ifBlank { "/watch/{id}/episode/{ep}/anineko/{lang}/hd1" }
            ?: "/watch/{id}/episode/{ep}/anineko/{lang}/hd1"
        val basePath = path
            .replace("{id}", item.id)
            .replace("{ep}", (episode?.number ?: 1).toString())
        val langs = api?.optString("streamsLangs")?.ifBlank { "sub,dub" }?.split(",")
            ?.map { it.trim() }?.filter { it.isNotBlank() }
            ?: listOf("sub", "dub")
        val sourcesKey = api?.optString("streamsSources")?.ifBlank { "sources" } ?: "sources"
        val urlField = api?.optString("streamsUrl")?.ifBlank { "url" } ?: "url"
        val qualityField = api?.optString("streamsQuality")?.ifBlank { "quality" } ?: "quality"
        val isM3u8Field = api?.optString("streamsIsM3u8")?.ifBlank { "isM3U8" } ?: "isM3U8"
        val subsKey = api?.optString("streamsSubtitles")?.ifBlank { "subtitles" } ?: "subtitles"
        val subUrlField = api?.optString("streamsSubUrl")?.ifBlank { "url" } ?: "url"
        val subLangField = api?.optString("streamsSubLang")?.ifBlank { "lang" } ?: "lang"
        val hdrsKey = api?.optString("streamsHeaders")?.ifBlank { "headers" } ?: "headers"
        val proxyStreams = api?.optBoolean("proxyStreams", true) ?: true
        val apiHdrs = apiHeaders()
        val out = mutableListOf<StreamSource>()
        for (lang in langs) {
            val json = apiGet(basePath.replace("{lang}", lang)) ?: continue
            val respHdrs = json.optJSONObject(hdrsKey)
            val streamHeaders = if (respHdrs != null) {
                mapOf(
                    "Referer" to respHdrs.optString("Referer"),
                    "Origin" to respHdrs.optString("Origin"),
                    "User-Agent" to (apiHdrs["User-Agent"] ?: ""),
                ).filterValues { it.isNotBlank() }
            } else apiHdrs
            val subs = json.optJSONArray(subsKey)?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val s = arr.optJSONObject(i) ?: return@mapNotNull null
                    val u = s.optString(subUrlField)
                    if (u.isBlank()) null
                    else SubtitleSource(s.optString(subLangField).ifBlank { lang }, u)
                }
            } ?: emptyList()
            val sources = json.optJSONArray(sourcesKey) ?: continue
            for (i in 0 until sources.length()) {
                val s = sources.optJSONObject(i) ?: continue
                val raw = s.optString(urlField).trim()
                if (raw.isBlank()) continue
                val u = absUrl(raw)
                val quality = s.optString(qualityField).ifBlank { "Auto" }
                val isM3u8 = s.optBoolean(isM3u8Field, u.contains(".m3u8", true))
                val label = lang.replaceFirstChar { it.uppercase() } + " " + quality
                out += StreamSource(label, u, headers = streamHeaders, subtitles = subs, isM3u8 = isM3u8)
                if (proxyStreams && api?.optString("proxy")?.isNotBlank() == true) {
                    val proxyHdrs = mutableMapOf<String, String>()
                    apiHdrs["User-Agent"]?.let { proxyHdrs["User-Agent"] = it }
                    proxyHdrs["Referer"] = base + "/"
                    proxyHdrs["Origin"] = base
                    val proxyUrl = proxyWrap(u, JSONObject(proxyHdrs).toString())
                    out += StreamSource(
                        "$label (proxy)",
                        proxyUrl,
                        headers = proxyHdrs,
                        subtitles = subs,
                        isM3u8 = isM3u8,
                    )
                }
            }
        }
        return out.distinctBy { it.url + "|" + it.name }
    }

    // ---------------------------------------------------------------
    // Interface
    // ---------------------------------------------------------------

    override suspend fun catalogs(): List<CatalogRef> {
        if (api != null) {
            val arr = api.optJSONArray("catalogs") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val c = arr.optJSONObject(i) ?: return@mapNotNull null
                val t = typeOf(c.optString("type"))
                if (t == MediaType.UNKNOWN) return@mapNotNull null
                CatalogRef(config.id, t, c.optString("id"), c.optString("name").ifBlank { c.optString("id") })
            }
        }
        if (base.isBlank()) return emptyList()
        val out = mutableListOf<CatalogRef>()
        val arr = conf.optJSONArray("catalogs")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                val t = typeOf(c.optString("type"))
                if (t != MediaType.UNKNOWN) {
                    out += CatalogRef(config.id, t, c.optString("id"), c.optString("name"))
                }
            }
        }
        if (out.isEmpty() && conf.optString("homeUrl").isNotBlank()) {
            out += CatalogRef(config.id, MediaType.UNKNOWN, "home", "Home")
        }
        return out
    }

    override suspend fun getCatalog(ref: CatalogRef, page: Int): List<MediaItem> {
        if (api != null) return getApiCatalog(ref)
        val rules = conf.optJSONObject("search") ?: return emptyList()
        val tpl = conf.optString("catalogUrl").ifBlank { rules.optString("url") }
        if (tpl.isBlank()) return emptyList()
        val url = absUrl(tpl.replace("{page}", page.toString()))
        return scrapeList(url, rules)
    }

    override suspend fun search(query: String, page: Int): List<MediaItem> {
        if (api != null) return getApiSearch(query, page)
        val rules = conf.optJSONObject("search") ?: return emptyList()
        val tpl = rules.optString("url")
        if (tpl.isBlank()) return emptyList()
        val url = absUrl(
            tpl
                .replace("{query}", java.net.URLEncoder.encode(query, "UTF-8"))
                .replace("{page}", page.toString())
        )
        return scrapeList(url, rules)
    }

    override suspend fun getMeta(item: MediaItem): MediaItem {
        if (api != null) return getApiMeta(item)
        val d = conf.optJSONObject("detail") ?: return item
        if (item.id == item.title && !item.id.startsWith("http")) return item
        val html = Http.getString(item.id) ?: return item
        val doc = runCatching { Jsoup.parse(html, item.id) }.getOrNull() ?: return item
        val title = pick(doc, d.optString("title"), null) ?: item.title
        val poster = pick(doc, d.optString("poster"), "src")
        val overview = pick(doc, d.optString("overview"), null)
        val type = typeOf(d.optString("type"))
        return MediaItem(
            providerId = item.providerId,
            id = item.id,
            title = title,
            type = type,
            posterUrl = poster?.ifBlank { item.posterUrl },
            year = item.year,
            overview = overview?.ifBlank { item.overview },
        )
    }

    override suspend fun getEpisodes(item: MediaItem): List<Episode>? {
        if (api != null) return getApiEpisodes(item)
        val e = conf.optJSONObject("episodes") ?: return null
        val tpl = e.optString("url")
        if (tpl.isBlank()) return null
        val pageUrl = absUrl(tpl.replace("{href}", item.id))
        val html = Http.getString(pageUrl) ?: return null
        val doc = runCatching { Jsoup.parse(html, pageUrl) }.getOrNull() ?: return null
        val itemSel = e.optString("item")
        if (itemSel.isBlank()) return null
        val hrefSel = e.optString("href").ifBlank { "a" }
        val numSel = e.optString("number").ifBlank { null }
        val nameSel = e.optString("name").ifBlank { null }
        val out = mutableListOf<Episode>()
        for (el in doc.select(itemSel)) {
            val href = el.select(hrefSel).first()?.attr("abs:href") ?: continue
            val number = numSel?.let { pick(el, it, null) }
                ?.let { s -> s.filter { c -> c.isDigit() }.toIntOrNull() }
                ?: (out.size + 1)
            val name = nameSel?.let { pick(el, it, null) }
            out += Episode(number, href, name?.ifBlank { null })
        }
        return out.sortedBy { it.number }.distinctBy { it.number }
    }

    override suspend fun getStreams(item: MediaItem, episode: Episode?): List<StreamSource> {
        if (api != null) return getApiStreams(item, episode)
        val s = conf.optJSONObject("streams") ?: return emptyList()
        val pageUrl = episode?.id ?: item.id
        val results = mutableListOf<StreamSource>()
        findStreams(pageUrl, s, results, 0)
        return results.distinctBy { it.url }
    }

    private suspend fun findStreams(
        pageUrl: String,
        s: JSONObject,
        out: MutableList<StreamSource>,
        depth: Int
    ) {
        if (depth > 2) return
        val html = Http.getString(pageUrl) ?: return
        val doc = runCatching { Jsoup.parse(html, pageUrl) }.getOrNull() ?: return

        val videoSel = s.optString("video").ifBlank { "video" }
        for (v in doc.select(videoSel)) {
            val src = v.attr("src").ifBlank { v.attr("data-src") }
            if (src.isNotBlank()) out += StreamSource("Direct", absUrl(src))
        }
        val m3u8Sel = s.optString("m3u8").ifBlank { "source[type*=m3u8]" }
        for (v in doc.select(m3u8Sel)) {
            val src = v.attr("src")
            if (src.isNotBlank()) out += StreamSource("HLS", absUrl(src))
        }
        val iframeSel = s.optString("iframe").ifBlank { "iframe" }
        for (f in doc.select(iframeSel)) {
            val src = f.attr("src")
            if (src.isBlank()) continue
            findStreams(absUrl(src), s, out, depth + 1)
        }
    }
}
