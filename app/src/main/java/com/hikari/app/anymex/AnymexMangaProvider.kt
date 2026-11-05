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
        // Same preview set served for different chapters = not pages (the
        // Ex-Love / My-Meddler class: the same 2 previews in every chapter of
        // every manga). Real pages differ per chapter, so a set repeating
        // verbatim for another chapter is previews/covers — honest empty.
        private val previewSets = ConcurrentHashMap<String, String>()
        fun isPreviewUrl(u: String): Boolean {
            val low = u.lowercase()
            return "thumb" in low || "preview" in low || "?type=" in low || "thumbnail" in low
        }
        fun normImg(u: String): String = u.substringBefore("?").substringBefore("#").trim().lowercase()
        // A header/divider image served inside EVERY chapter (the "Episode 4"
        // bar class): identical page SETS never repeat so isPreviewSet cannot
        // see it — one chrome image hiding inside otherwise-different pages.
        // Tracked per normalized URL across chapters: a URL seen in 3+
        // different chapters of one provider is site chrome, dropped going
        // forward. Sets (not counts) so reopening one chapter never trips it.
        private val chromeChapters = ConcurrentHashMap<String, MutableSet<String>>()
        // Headers open the chapter (page 1): the same exact image leading 2+
        // chapters is a title card, even before the 3-chapter general rule
        // trips. Position-gated (first page only) so shared covers mid-chapter
        // are never caught by it.
        private val chromeFirsts = ConcurrentHashMap<String, MutableSet<String>>()
        fun isChromeFirst(providerId: String, normUrl: String, chapterUrl: String): Boolean {
            if (normUrl.isBlank() || chapterUrl.isBlank()) return false
            val key = providerId + "|1|" + normUrl
            val set = chromeFirsts.getOrPut(key) { ConcurrentHashMap.newKeySet() }
            set.add(chapterUrl)
            if (chromeFirsts.size > 2000) chromeFirsts.clear()
            return set.size >= 2
        }
        fun isChromeImage(providerId: String, normUrl: String, chapterUrl: String): Boolean {
            if (normUrl.isBlank() || chapterUrl.isBlank()) return false
            val key = providerId + "|" + normUrl
            val set = chromeChapters.getOrPut(key) { ConcurrentHashMap.newKeySet() }
            set.add(chapterUrl)
            if (chromeChapters.size > 2000) chromeChapters.clear()
            return set.size >= 3
        }
        fun isPreviewSet(providerId: String, chapterUrl: String, urls: List<String>): Boolean {
            if (urls.isEmpty()) return false
            val h = urls.sorted().joinToString("|").hashCode().toString() + "|" + urls.size
            val key = providerId + "|" + h
            val first = previewSets.putIfAbsent(key, chapterUrl)
            if (first != null && first != chapterUrl) return true
            if (previewSets.size > 500) previewSets.clear()
            return false
        }
    }

    suspend fun preferenceDefs(): String {
        val m = module() ?: return "[]"
        return AnymexRuntime.preferenceDefs(m)
    }
    fun setPreference(key: String, rawJson: String) {
        val m = module() ?: return
        AnymexRuntime.setPreference(m, key, rawJson)
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

    override suspend fun warmDetail(item: MediaItem) {
        val m = module() ?: return
        com.hikari.app.anymex.AnymexRuntime.warm(m, config.id)
    }
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
                return@withContext native
            }
            // The module answered empty with no challenge to recognise (an
            // empty 200, a site that wants a browser session first): be the
            // browser once, offscreen, then ask the module again before the
            // homepage fallback reports. Spaced per host inside the solver.
            if (warmSite()) {
                val retryRaw = when (ref.id) {
                    CATALOG_LATEST -> AnymexRuntime.latest(mod, config.id, p)
                    else -> AnymexRuntime.popular(mod, config.id, p)
                }
                val retry = mapItems(retryRaw)
                if (retry.isNotEmpty()) {
                    lastOutcome[config.id] = "✓ ${retry.size} titles (js, warmed)"
                    return@withContext retry
                }
            }
            // Same stale-paths fallback as the video engine (see
            // AnymexHomepage) — but never for the known sites, whose native
            // API paths answer for them and whose homepage cards the native
            // detail calls could not open. Only on a recorded failure, and
            // only on page 1, which is the only page a front page has.
            if (p == 1 && !isMangaDex() && !isWebtoons() && !isComick() &&
                lastOutcome[config.id]?.startsWith("✗") == true
            ) {
                val home = runCatching { AnymexHomepage.titles(config) }.getOrDefault(emptyList())
                if (home.isNotEmpty()) {
                    lastOutcome[config.id] = "✓ ${home.size} titles (homepage)"
                    return@withContext home
                }
            }
            lastOutcome[config.id] = lastOutcome[config.id] ?: "✗ empty catalog"
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

    private val webtoonsUa =
        "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"

    private fun webtoonsTitleNo(idOrUrl: String): String =
        Regex("title_no=(\\d+)").find(idOrUrl)?.groupValues?.get(1).orEmpty()

    private fun webtoonsUnescape(s: String): String =
        s.replace("&amp;", "&").replace("&#039;", "'").replace("&#034;", "\"")
            .replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">")

    private fun webtoonsNormalize(u: String): String =
        webtoonsUnescape(u.trim()).replace("https://m.webtoons.com/", "https://www.webtoons.com/")

    private fun webtoonsFetch(url: String): String =
        Http.fetchStringRobust(
            url,
            mapOf(
                "User-Agent" to webtoonsUa,
                "Accept" to "text/html",
                "Referer" to "https://www.webtoons.com/",
            ),
        ).getOrNull().orEmpty()

    /**
     * The viewer HTML for a title. A viewer page carries the whole episode
     * sequence list server-rendered (the list page's own `<ul>` is filled
     * client-side, so the list URL alone can never yield chapters), and the
     * strip images of its own episode inline. Accepts a viewer URL (used
     * directly) or a list URL (bootstrapped through the first episode's
     * viewer link embedded in the list page's dto JSON). Blank when neither
     * shape is usable — the caller falls through to the JS module path.
     */
    private fun webtoonsViewerHtml(idOrUrl: String): String {
        val u = webtoonsNormalize(idOrUrl)
        if (u.isBlank()) return ""
        if ("viewer" in u && "episode_no=" in u) return webtoonsFetch(u)
        if (!u.startsWith("http") || webtoonsTitleNo(u).isEmpty()) return ""
        val listHtml = webtoonsFetch(u)
        if (listHtml.isBlank()) return ""
        val first = Regex(
            """https://(?:m\.|www\.)webtoons\.com/[^"'<>\s]*?/viewer\?title_no=\d+&(?:amp;)?episode_no=\d+"""
        ).find(listHtml)?.value?.let { webtoonsNormalize(it) }.orEmpty()
        if (first.isBlank()) return ""
        return webtoonsFetch(first)
    }

    /**
     * Native Webtoons chapters (verified against the live site 2026-10-12):
     * one viewer fetch yields every episode — `<a .../viewer?title_no=N
     * &episode_no=M><img ... alt="Ep. M - Title">` in the toolbar sequence
     * list. The JS module path stays as the fallback (Canvas/Discover titles
     * the regexes below do not cover keep working exactly as before).
     */
    private fun nativeWebtoonsChapters(item: MediaItem): List<Episode> {
        val html = webtoonsViewerHtml(item.id)
        if (html.isBlank()) return emptyList()
        val re = Regex(
            """<a href="(https://(?:m\.|www\.)webtoons\.com/[^"]*?/viewer\?title_no=\d+&(?:amp;)?episode_no=(\d+))"[^>]*>[\s\S]{0,2500}?<img[^>]*alt="([^"]*)""""
        )
        val byNo = LinkedHashMap<Int, Episode>()
        for (m in re.findAll(html)) {
            val no = m.groupValues[2].toIntOrNull() ?: continue
            if (no <= 0 || byNo.containsKey(no)) continue
            val name = webtoonsUnescape(m.groupValues[3]).trim().ifBlank { "Episode $no" }
            byNo[no] = Episode(
                number = no,
                id = webtoonsNormalize(m.groupValues[1]),
                name = name,
            )
            if (byNo.size >= MAX_CHAPTERS) break
        }
        return byNo.values.sortedBy { it.number }
    }

    /**
     * Native Webtoons pages (verified against the live site 2026-10-12): the
     * episode's own viewer HTML carries every full-size strip in its
     * `var imageList = [{url, sortOrder, ...}]` block — one list per episode,
     * which is the ONLY per-episode page source on the page. A page-wide
     * `webtoon-phinf` scan is kept strictly as a fallback (older markup):
     * without it every episode of a preview-walled title returned the same
     * series-level covers, i.e. identical "pages" in every chapter of every
     * manga. An imageList block that is present but empty means the episode
     * is locked — honest empty, never fallback covers.
     */
    private fun nativeWebtoonsPages(chapterUrl: String): List<StreamSource> {
        val u = webtoonsNormalize(chapterUrl)
        if (u.isBlank() || !u.startsWith("http") || "viewer" !in u) return emptyList()
        val html = webtoonsFetch(u)
        if (html.isBlank()) return emptyList()
        val headers = mapOf(
            "User-Agent" to webtoonsUa,
            "Referer" to "https://www.webtoons.com/",
        )
        fun toSource(img: String, n: Int) = StreamSource(
            name = "Page $n",
            url = img,
            headers = headers,
            pageUrl = u,
            provider = "Anymex",
            providerId = config.id,
            providerName = config.name,
        )
        // Primary: the episode's own strip list, in story order.
        val block = Regex("""var\s+imageList\s*=\s*\[(.*?)\];""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)
        if (block != null) {
            val urls = Regex("""url\s*:\s*"([^"]+)"""").findAll(block)
                .map { it.groupValues[1].trim() }
                .filter { it.startsWith("http") }
                .distinct()
                .toList()
            if (urls.isNotEmpty()) {
                return urls.take(400).mapIndexed { i, img -> toSource(img, i + 1) }
            }
            // Locked/preview episode: its strips are not in this HTML at all.
            return emptyList()
        }
        // Fallback for older markup without an imageList block.
        val seen = HashSet<String>()
        val out = ArrayList<StreamSource>()
        for (m in Regex("""https://webtoon-phinf\.pstatic\.net/[^"'<>\s]+""").findAll(html)) {
            val img = m.value.trim()
            if (img.isEmpty() || !seen.add(img)) continue
            val low = img.lowercase()
            if ("thumb" in low || "?type=" in low) continue
            out += toSource(img, out.size + 1)
            if (out.size >= 400) break
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
        val d = digDetail(raw) ?: return@withContext item
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
            isWebtoons() -> nativeWebtoonsChapters(item)
            else -> emptyList()
        }
        val episodes = if (native.isNotEmpty()) native else {
            val mod = module() ?: return@withContext null
            var raw = AnymexRuntime.detail(mod, config.id, item.id)
            var d = raw?.let { digDetail(it) }
            // A module behind a bot wall answers empty/throws on the first
            // call (the 0-chapters report over a site that lists fine in a
            // browser): be the browser once, then ask again before reporting.
            if (d == null && warmSite()) {
                raw = AnymexRuntime.detail(mod, config.id, item.id)
                d = raw?.let { digDetail(it) }
            }
            val dd = d
            if (dd == null) {
                lastOutcome[config.id] = "✗ " + detailError(raw).take(160)
                return@withContext null
            }
            // Modules disagree on where the chapters live: most answer a
            // top-level "chapters"/"episodes" array, but some nest it (per
            // volume, under "list"/"data", or beside a "titles" array that
            // only names the volumes). The recursive scan takes the first
            // non-empty array whose items look like chapters (objects with a
            // link) instead of reporting "no chapters" over an answer that
            // has them. Bounded (depth + node cap) so a huge detail blob
            // cannot hang the chapter load.
            var arr = dd.optJSONArray("chapters") ?: dd.optJSONArray("episodes")
                ?: dd.optJSONObject("data")?.optJSONArray("chapters")
                ?: dd.optJSONObject("data")?.optJSONArray("episodes")
                ?: dd.optJSONArray("list") ?: dd.optJSONArray("data")
                ?: findChapterArray(dd)
            // A module answering the chapter list bare (a top-level array of
            // chapter links, no detail object around it): digDetail kept the
            // first entry as the "detail", so no chapters were found above —
            // read the raw answer itself before reporting none.
            if (arr == null) {
                arr = runCatching { JSONArray(raw?.trim().orEmpty()) }.getOrNull()
                    ?.takeIf { it.length() > 0 && isChapterArray(it) }
            }
            if (arr == null) {
                lastOutcome[config.id] = "✗ no chapters in detail answer"
                return@withContext null
            }
            val out = ArrayList<Episode>()
            for (i in 0 until minOf(arr.length(), MAX_CHAPTERS)) {
                val rawEntry = arr.opt(i)
                if (rawEntry is String) {
                    val u = rawEntry.trim()
                    if (u.isBlank()) continue
                    out += Episode(number = out.size + 1, id = u, name = "Chapter " + (out.size + 1))
                    continue
                }
                val o = rawEntry as? JSONObject ?: continue
                val url = o.optString("url").ifBlank { o.optString("link") }.ifBlank { o.optString("href") }.ifBlank { o.optString("id") }.ifBlank { o.optString("slug") }.ifBlank { o.optString("path") }.ifBlank { o.optString("endpoint") }.ifBlank { o.optString("chapterId") }.trim()
                if (url.isBlank()) continue
                val name = o.optString("name").ifBlank { o.optString("title") }.ifBlank { o.optString("label") }
                    .trim().ifBlank { null }
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

    /**
     * First non-empty array in [root] whose items look like chapters
     * (objects carrying a url/link), at any nesting depth. Breadth-first so
     * the shallowest candidate wins; bounded so a pathological blob cannot
     * hang the load. Null when nothing chapter-shaped exists.
     */
    private fun findChapterArray(root: JSONObject): JSONArray? {
        val queue = ArrayDeque<Pair<Any, Int>>()
        queue += root to 0
        var seen = 0
        while (queue.isNotEmpty() && seen < 800) {
            val (node, depth) = queue.removeFirst()
            seen++
            if (node is JSONArray) {
                if (node.length() > 0 && isChapterArray(node)) return node
                if (depth < 4) {
                    for (i in 0 until node.length()) {
                        when (val e = node.opt(i)) {
                            is JSONObject, is JSONArray -> queue += e to depth + 1
                        }
                    }
                }
            } else if (node is JSONObject && depth < 4) {
                val keys = node.keys()
                while (keys.hasNext()) {
                    when (val v = node.opt(keys.next())) {
                        is JSONObject, is JSONArray -> queue += v to depth + 1
                    }
                }
            }
        }
        return null
    }

    /** True when [arr] holds at least one object with a chapter link. */
    private fun isChapterArray(arr: JSONArray): Boolean {
        for (i in 0 until minOf(arr.length(), 8)) {
            val rawEntry = arr.opt(i)
            if (rawEntry is String && rawEntry.trim().isNotBlank()) return true
            val o = rawEntry as? JSONObject ?: continue
            if (o.optString("url").isNotBlank() || o.optString("link").isNotBlank() || o.optString("href").isNotBlank() || o.optString("id").isNotBlank() || o.optString("slug").isNotBlank() || o.optString("path").isNotBlank() || o.optString("endpoint").isNotBlank() || o.optString("chapterId").isNotBlank()) return true
        }
        return false
    }

    override suspend fun getStreams(item: MediaItem, episode: Episode?): List<StreamSource> =
        withContext(Dispatchers.IO) {
            val chUrl = episode?.id?.takeIf { it.isNotBlank() } ?: return@withContext emptyList()
            // A covers-only module answers the series art for every chapter —
            // the per-page identity of a covers answer. Any page equal to the
            // detail poster is dropped at every branch below (not only when
            // ALL pages match it), so such a module reports honest empty
            // instead of painting the same image in every chapter.
            val chPoster = item.posterUrl?.trim().orEmpty()
            // Native MangaDex at-home server (no JS module required)
            if (isMangaDex()) {
                val pages = nativeMangaDexPages(chUrl)
                if (pages.isNotEmpty()) {
                    lastOutcome[config.id] = "✓ ${pages.size} pages"
                    return@withContext pages
                }
            }
            // Native Webtoons strips (no JS module required): the viewer HTML
            // carries every full-size strip inline — see nativeWebtoonsPages.
            // Universal: ANY module can hand back a webtoons.com viewer URL
            // while its own pages() serves the same 2 previews for every
            // chapter, so prefer the episode's own imageList whenever the
            // chapter itself is a Webtoons viewer link, regardless of owner.
            if (isWebtoons() || ("webtoons.com" in chUrl.lowercase() && "viewer" in chUrl.lowercase())) {
                val pages = nativeWebtoonsPages(chUrl)
                if (pages.isNotEmpty()) {
                    lastOutcome[config.id] = "✓ ${pages.size} pages (native)"
                    return@withContext pages
                }
            }
            val mod = module() ?: return@withContext emptyList()
            val raw = AnymexRuntime.pages(mod, config.id, chUrl)
            if (raw.isNullOrBlank()) {
                lastOutcome[config.id] = "✗ empty pages answer"
                return@withContext emptyList()
            }
            val arr = pageArray(raw)
            if (arr == null) {
                lastOutcome[config.id] = "✗ " + pageError(raw).take(160)
                return@withContext emptyList()
            }
            val seenAnymex = HashSet<String>()
            val seenAnymexNorm = HashSet<String>()
            val pageBase = siteBase()
            val out = ArrayList<StreamSource>()
            for (i in 0 until arr.length()) {
                when (val e = arr.opt(i)) {
                    is JSONObject -> {
                        val u = normPageUrl(
                            e.optString("url").ifBlank { e.optString("imageUrl") }
                                .ifBlank { e.optString("image") }.ifBlank { e.optString("src") }
                                .ifBlank { e.optString("originalUrl") },
                            pageBase, chUrl,
                        ) ?: continue
                        // The chapter URL itself is not a page (the fallback
                        // leaves the chapter behind as the image); repeats of
                        // one cover would paint every chapter with the same
                        // image, so they are dropped and de-duplicated here,
                        // keeping story order. Cross-chapter chrome (headers,
                        // dividers) goes too — see isChromeImage.
                        val nu = normImg(u)
                        if (isPreviewUrl(u) || (chPoster.isNotBlank() && (u == chPoster || nu == normImg(chPoster))) || !seenAnymex.add(u) || !seenAnymexNorm.add(nu) || isChromeImage(config.id, nu, chUrl) || (out.isEmpty() && isChromeFirst(config.id, nu, chUrl))) continue
                        // Hotlink-guarded CDNs refuse a page with no Referer even
                        // when no Cloudflare wall exists — the module often names
                        // none, so the chapter itself stands in (plus Origin/UA).
                        val h = mapHeaders(e.optJSONObject("headers")).toMutableMap()
                        if (h.keys.none { it.equals("Referer", ignoreCase = true) }) {
                            h["Referer"] = chUrl
                        }
                        if (h.keys.none { it.equals("Origin", ignoreCase = true) }) {
                            runCatching { android.net.Uri.parse(chUrl) }.getOrNull()?.let { uu ->
                                if (!uu.scheme.isNullOrBlank() && !uu.host.isNullOrBlank()) h["Origin"] = uu.scheme + "://" + uu.host
                            }
                        }
                        if (h.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                            h["User-Agent"] = com.hikari.app.net.Http.UA
                        }
                        out += StreamSource(
                            name = "Page ${out.size + 1}",
                            url = u,
                            headers = h,
                            pageUrl = chUrl,
                            provider = "Anymex",
                            providerId = config.id,
                            providerName = config.name,
                        )
                    }
                    is String -> {
                        val u = normPageUrl(e, pageBase, chUrl) ?: continue
                        val nu = normImg(u)
                        if (isPreviewUrl(u) || (chPoster.isNotBlank() && (u == chPoster || nu == normImg(chPoster))) || !seenAnymex.add(u) || !seenAnymexNorm.add(nu) || isChromeImage(config.id, nu, chUrl) || (out.isEmpty() && isChromeFirst(config.id, nu, chUrl))) continue
                        out += StreamSource(
                            name = "Page ${out.size + 1}",
                            url = u,
                            headers = mapOf("Referer" to chUrl, "User-Agent" to com.hikari.app.net.Http.UA),
                            pageUrl = chUrl,
                            provider = "Anymex",
                            providerId = config.id,
                            providerName = config.name,
                        )
                    }
                    else -> {}
                }
            }
            // Same 2 previews for every chapter of every manga (the Ex-Love /
            // My-Meddler class): real pages differ per chapter, so a set that
            // repeats verbatim for a different chapter is previews, not pages.
            if (out.isNotEmpty() && isPreviewSet(config.id, chUrl, out.map { it.url })) {
                lastOutcome[config.id] = "✗ chapter locked or pages-walled"
                return@withContext emptyList()
            }
            // A module answering the series covers for every chapter (the same
            // small set, matching the detail poster) is a walls/covers answer,
            // not pages — serving it paints identical "pages" in every chapter.
            // Report it honestly instead, like a locked Webtoons episode.
            if (out.size >= 2 && out.map { normImg(it.url) }.toSet().size == 1) {
                lastOutcome[config.id] = "chapter locked or pages-walled"
                return@withContext emptyList()
            }
            if (out.isNotEmpty() && chPoster.isNotBlank() && out.all { it.url == chPoster }) {
                lastOutcome[config.id] = "✗ chapter locked or pages-walled"
                return@withContext emptyList()
            }
            lastOutcome[config.id] = "✓ ${out.size} pages"
            out
        }

    private fun nativeMangaDexPages(chapterId: String): List<StreamSource> {
        if (chapterId.startsWith("http") && chapterId.indexOf("mangadex.org") < 0) {
            lastOutcome[config.id] = "external chapter (paid) — open in WebView"
            return emptyList()
        }
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
        if (data.length() >= 2) {
            val seenDex = HashSet<String>()
            for (i in 0 until data.length()) seenDex.add(normImg(data.optString(i)))
            if (seenDex.size == 1) {
                lastOutcome[config.id] = "chapter locked or pages-walled"
                return emptyList()
            }
        }
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
        val path = "/v1.0/search?sort=$sort&page=${page.coerceAtLeast(1)}&tachiyomi=true"
        return parseComickList(comickFetch(path))
    }

    private fun nativeComickSearch(query: String, page: Int): List<MediaItem> {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val path = "/v1.0/search?q=$q&page=${page.coerceAtLeast(1)}&tachiyomi=true"
        return parseComickList(comickFetch(path))
    }

    /**
     * Comick's API host has moved before (api.comick.fun answered, then empty):
     * try the known hosts in order with the same path and take the first that
     * answers JSON. One host's outage then reads as a slower catalog, never as
     * a permanently empty one.
     */
    private fun comickFetch(path: String): String {
        val headers = mapOf(
            "User-Agent" to "Tachiyomi Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:110.0) Gecko/20100101 Firefox/110.0",
            "Referer" to "https://comick.io/",
            "Accept" to "application/json",
        )
        val bases = listOf("https://api.comick.fun", "https://api.comick.io")
        for (base in bases) {
            val raw = Http.fetchStringRobust(base + path, headers).getOrNull().orEmpty()
            if (raw.isNotBlank() && !raw.trimStart().startsWith("<")) return raw
        }
        return ""
    }

    private fun parseComickList(raw: String): List<MediaItem> {
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
        val raw = comickFetch("/comic/$hid/chapters?lang=en&limit=100&tachiyomi=true")
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
            val ext = attrs.optString("externalUrl").trim()
            if (ext.startsWith("http")) {
                val chap = attrs.optString("chapter")
                out += Episode(number = chap.toFloatOrNull()?.toInt() ?: (i + 1), id = ext, name = if (chap.isNotBlank()) "Chapter " + chap + " · External" else "External chapter")
                continue
            }
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

    private fun siteBase(): String =
        runCatching { AnymexPluginManager.siteUrlOf(config) }.getOrNull()
            ?.trim()?.trimEnd('/')?.ifBlank { null }.orEmpty()

    /**
     * Unwraps the runtime {ok,data} envelope of a getDetail answer: the
     * detail object itself, whether it rides top-level, under "data", or as
     * the first item of a "data" array. Null on {ok:false} or unparseable —
     * the caller reports the envelope's own error instead of guessing.
     */
    private fun digDetail(raw: String?): JSONObject? {
        if (raw.isNullOrBlank()) return null
        val txt = raw.trim()
        val o = runCatching { JSONObject(txt) }.getOrNull() ?: return firstObject(txt)
        if (o.has("ok") && !o.optBoolean("ok", true)) return null
        o.optJSONObject("data")?.let { return it }
        val arr = o.optJSONArray("data")
        if (arr != null && arr.length() > 0) arr.optJSONObject(0)?.let { return it }
        return o
    }

    /** The honest one-line reason a pages answer carried no pages (a JS
     *  throw inside getPageList used to surface as a silent empty chapter). */
    private fun pageError(raw: String?): String {
        if (raw.isNullOrBlank()) return "empty pages answer"
        val txt = raw.trim()
        val o = runCatching { JSONObject(txt) }.getOrNull()
        if (o != null && o.has("ok") && !o.optBoolean("ok", true)) {
            return o.optString("error").ifBlank { "pages error" }
        }
        return "no pages in answer"
    }

    /** The honest one-line reason a detail answer carried no chapters. */
    private fun detailError(raw: String?): String {
        if (raw.isNullOrBlank()) return "empty detail answer"
        val txt = raw.trim()
        val o = runCatching { JSONObject(txt) }.getOrNull()
        if (o != null && o.has("ok") && !o.optBoolean("ok", true)) {
            return o.optString("error").ifBlank { "detail error" }
        }
        return "no chapters in detail answer"
    }

    /**
     * Normalizes one page URL to an absolute http(s) image link, or null
     * when it is not a page at all: split-fragment junk from string-split
     * parsers ("https:" stubs), document links (.html/.php), inline
     * data:/javascript:/blob: URIs, and the chapter URL itself. Mangayomi
     * modules serve scheme-relative (//host/…) and site-relative (/path)
     * art; both are resolved against the module's own site, the way the
     * Mangayomi app resolves them.
     */
    private fun normPageUrl(raw: String, base: String, chUrl: String): String? {
        var u = raw.trim()
        if (u.isBlank() || u.contains(" ") || u.contains("{") || u.contains("}")) return null
        // The module's own site is the authority for site-relative art, but
        // old installs may carry no base (no meta.json baseUrl, no source
        // URL): the chapter itself was fetched from that same site, so its
        // origin stands in — otherwise every relative page drops and only
        // absolute header images survive (the same-header-everywhere report).
        var b = base.trim().trimEnd('/')
        val absCh = when {
            chUrl.startsWith("http", ignoreCase = true) -> chUrl
            b.isNotBlank() -> b + "/" + chUrl.trimStart('/')
            else -> chUrl
        }
        if (b.isBlank()) {
            b = runCatching {
                val uu = java.net.URI(absCh)
                if (!uu.scheme.isNullOrBlank() && !uu.host.isNullOrBlank()) uu.scheme + "://" + uu.host else ""
            }.getOrNull().orEmpty()
        }
        if (u.startsWith("//")) u = "https:" + u
        else if (u.startsWith("/") && b.isNotBlank()) u = b + u
        else if (!u.startsWith("http", ignoreCase = true) && b.isNotBlank() &&
            u.contains("/") && !u.contains(":")) u = b + "/" + u.trimStart('/')
        if (!u.startsWith("http", ignoreCase = true)) return null
        val low = u.lowercase()
        if (low.startsWith("data:") || low.startsWith("javascript:") || low.startsWith("blob:")) return null
        val path = low.substringBefore("?").substringBefore("#")
        if (path.endsWith(".html") || path.endsWith(".htm") || path.endsWith(".php") ||
            path.endsWith(".asp") || path.endsWith(".aspx")) return null
        if (u.length < 12 || u == chUrl || u == absCh) return null
        return u
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

    /**
     * Be the browser once for a module that answered empty — the Anymex half
     * of MangaProvider.warmSite. A module that builds its own client (or a
     * site that answers the XHR with an empty 200) leaves no challenge to
     * recognise, so the catalog stayed blank with no Verify offered. Loads
     * the module's own site offscreen; spaced per host inside the solver.
     * BLOCKING — every caller is already on IO.
     */
    private fun warmSite(): Boolean {
        val site = runCatching { AnymexPluginManager.siteUrlOf(config) }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: return false
        return runCatching {
            com.hikari.app.net.CloudflareSolver.warm(url = site, userAgent = Http.UA)
        }.getOrDefault(false)
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
        // Modules disagree on where the pages live ({data:{pages}}, {images},
        // {urls}, …): scan bounded for the first page-shaped array instead of
        // reporting no pages over an answer that has them. Object items must
        // carry a page link; bare-string arrays only count under a page-ish
        // key, so a genres/tags array is never mistaken for pages.
        // Two passes: page-shaped keys first (pages/list/pageList/imageList),
        // generic art keys second (images/imgs/urls/sources/files/data) — a
        // header/cover array sitting beside the real pages must never win
        // (the same-header-in-every-chapter report).
        scanPages(o, narrow = true)?.let { return it }
        return scanPages(o, narrow = false)
    }

    private fun scanPages(o: JSONObject, narrow: Boolean): JSONArray? {
        val narrowKeys = setOf("pages", "list", "pagelist", "imagelist")
        val wideKeys = setOf("pages", "list", "data", "images", "imgs", "urls", "sources", "files", "pagelist", "imagelist")
        val pageKeys = if (narrow) narrowKeys else wideKeys
        val queue = ArrayDeque<JSONObject>()
        queue += o
        var seen = 0
        while (queue.isNotEmpty() && seen < 25) {
            val node = queue.removeFirst()
            seen++
            val keys = node.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                when (val v = node.opt(k)) {
                    is JSONArray -> {
                        if (v.length() == 0) continue
                        when (val first = v.opt(0)) {
                            is JSONObject ->
                                if (first.optString("url").isNotBlank() ||
                                    first.optString("imageUrl").isNotBlank() ||
                                    first.optString("image").isNotBlank() ||
                                    first.optString("src").isNotBlank() ||
                                    first.optString("originalUrl").isNotBlank()
                                ) {
                                    if (!narrow || k.lowercase() in pageKeys) return v
                                }
                            is String ->
                                if (k.lowercase() in pageKeys) return v
                            else -> {}
                        }
                    }
                    is JSONObject -> queue += v
                    else -> {}
                }
            }
        }
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
