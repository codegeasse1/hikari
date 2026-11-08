package com.hikari.app.anymex

import com.hikari.app.data.MediaItem
import com.hikari.app.data.MediaType
import com.hikari.app.data.ProviderConfig
import com.hikari.app.net.Http

object AnymexHomepage {

    private const val MAX_ITEMS = 60

    private val SKIP_SEGMENTS = setOf(
        "login", "signin", "sign-in", "signup", "sign-up", "register",
        "privacy", "terms", "dmca", "contact", "about", "faq", "help",
        "rss", "sitemap", "sitemap.xml", "robots.txt", "search", "genre",
        "genres", "tag", "tags", "page",
    )

    fun titles(config: ProviderConfig): List<MediaItem> {
        val base = AnymexPluginManager.siteUrlOf(config)?.trim()?.trimEnd('/')
            ?.takeIf { it.startsWith("http") } ?: return emptyList()
        val html = runCatching {
            Http.getStringStrict(base + "/", mapOf("Referer" to base + "/")).getOrNull()
                ?: Http.getStringStrict(base, emptyMap()).getOrNull()
        }.getOrNull()?.takeIf { !it.isNullOrBlank() } ?: return emptyList()
        return scrape(base, html, config)
    }

    private fun scrape(base: String, html: String, config: ProviderConfig): List<MediaItem> {
        val doc = runCatching { org.jsoup.Jsoup.parse(html, base) }.getOrNull() ?: return emptyList()
        val host = runCatching { java.net.URI(base).host?.lowercase() }.getOrNull() ?: return emptyList()
        val seen = HashSet<String>()
        val out = ArrayList<MediaItem>()
        for (a in doc.select("a[href]")) {
            if (out.size >= MAX_ITEMS) break
            val href = a.attr("abs:href").substringBefore('#').trim()
            if (href.isBlank() || seen.contains(href)) continue
            val linkHost = runCatching { java.net.URI(href).host?.lowercase() }.getOrNull() ?: continue
            if (linkHost != host && !linkHost.endsWith(".$host")) continue
            val path = runCatching { java.net.URI(href).path?.lowercase().orEmpty() }.getOrDefault("")
            if (path.isBlank() || path == "/") continue
            val segments = path.trim('/').split('/').filter { it.isNotBlank() }
            if (segments.any { it in SKIP_SEGMENTS }) continue
            if (segments.any { it.endsWith(".css") || it.endsWith(".js") || it.endsWith(".xml") }) continue
            val img = a.selectFirst("img")
            val poster = if (img == null) null else posterOf(img)
            val title = titleOf(a, img)
                .replace(Regex("\\s+"), " ")
            if (title.length < 2) continue
            if (poster == null) continue
            seen += href
            // UNKNOWN until the episode list is known (see AniyomiProvider):
            // a scraped card carries no movie/series signal.
            out += MediaItem(
                providerId = config.id,
                id = href,
                title = title,
                type = MediaType.UNKNOWN,
                posterUrl = poster,
            )
        }
        return out
    }

    private fun posterOf(img: org.jsoup.nodes.Element): String? {
        val src = img.attr("abs:data-lazy-src").ifBlank { img.attr("abs:data-src") }
            .ifBlank { img.attr("abs:data-original") }
            .ifBlank { img.attr("abs:src") }
            .trim()
        return src.takeIf { it.startsWith("http") && !it.startsWith("data:") }
    }

    /** The card title: the image's alt/title text, else the link's own text. */
    private fun titleOf(a: org.jsoup.nodes.Element, img: org.jsoup.nodes.Element?): String {
        if (img != null) {
            val alt = img.attr("alt").trim()
            if (alt.isNotBlank()) return alt
            val label = img.attr("title").trim()
            if (label.isNotBlank()) return label
        }
        return a.text().trim()
    }
}
