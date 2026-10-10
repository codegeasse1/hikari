package com.hikari.app.anymex

import com.hikari.app.data.MediaItem
import com.hikari.app.data.MediaType
import com.hikari.app.data.ProviderConfig
import com.hikari.app.net.Http

/**
 * The last resort for an Anymex catalogue that the extension's own calls
 * cannot list: the site's own front page, scraped for title cards.
 *
 * WHEN this runs. Only when the extension's popular AND latest calls have
 * already failed AND recorded why (see [AnymexProvider.catalogErrors]) — a
 * catalogue that is merely empty (a search-only source, the end of the
 * pages) is a legitimate answer and is left alone. The classic case is a
 * script whose catalogue paths went stale (HTTP 404 on every request) while
 * the site itself is alive — the globe button loads it fine, because that
 * opens the front page this scrapes.
 *
 * WHAT it returns. The front page's linked cards (anchor + image + title),
 * same-host links only, capped — real titles the rows can show instead of
 * the "its pages may have moved" error. Detail/episode/stream calls still go
 * through the extension, so a card whose page the script cannot parse simply
 * reports that when opened; a homepage that cannot be fetched (or has no
 * cards) returns empty and the recorded error stands.
 */
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
        // The shared client (not the quiet one): it carries the Cloudflare
        // clearance the globe view earned, so a site the user verified in the
        // WebView answers here too.
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
            // Cards without any image are the site's own navigation
            // ("Today", "Genres", "DMCA"…) — not titles. Letting one through
            // used to open a section page as if it were a video, whose
            // detail then resolved to whatever TMDB guessed from the bare
            // word ("Today" → the NBC morning show).
            if (poster == null) continue
            seen += href
            out += MediaItem(
                providerId = config.id,
                id = href,
                title = title,
                type = MediaType.SERIES,
                posterUrl = poster,
            )
        }
        return out
    }

    /**
     * The card image, first lazy-src spellings first. Plain calls on a
     * non-null element, the way the other scrapers read attributes — no
     * nullable chains, whose inferred types the compiler cannot resolve
     * against this jsoup build.
     */
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
