package com.hikari.app.providers

import com.hikari.app.data.CatalogRef
import com.hikari.app.data.Episode
import com.hikari.app.data.MediaItem
import com.hikari.app.data.MediaType
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.StreamSource
import com.hikari.app.net.Http
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONObject

/**
 * A JSON-rule based scraper. The `extra` field holds the full config:
 *
 * {
 *   "name": "MySite", "baseUrl": "https://...", "homeUrl": "/",
 *   "catalogs": [{"id":"home","name":"Home","type":"movie"}],
 *   "search": { "url": "/search?q={query}", "item": ".item", "title": ".title",
 *               "href": "a@href", "poster": "img@src" },
 *   "detail": { "title": "h1", "poster": ".poster img@src", "overview": ".desc", "type": "series" },
 *   "episodes": { "url": "{href}", "item": ".episode", "number": "data-ep", "href": "a@href" },
 *   "streams": { "video": "video@src", "m3u8": "source[type*=m3u8]@src", "iframe": "iframe@src" }
 * }
 */
class UniversalScraper(override val config: ProviderConfig) : ContentProvider {

    private val conf = runCatching { JSONObject(config.extra ?: "{}") }.getOrDefault(JSONObject())
    private val base: String get() = conf.optString("baseUrl").trimEnd('/')

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

    override suspend fun catalogs(): List<CatalogRef> {
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
        val rules = conf.optJSONObject("search") ?: return emptyList()
        val tpl = conf.optString("catalogUrl").ifBlank { rules.optString("url") }
        if (tpl.isBlank()) return emptyList()
        val url = absUrl(tpl.replace("{page}", page.toString()))
        return scrapeList(url, rules)
    }

    override suspend fun search(query: String, page: Int): List<MediaItem> {
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

    override suspend fun getMeta(item: MediaItem): MediaItem {
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
