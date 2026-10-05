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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
        private const val FILTER_TTL_MS = 10 * 60 * 1000L
        private val filterLists = ConcurrentHashMap<String, Pair<Long, List<AnymexFilter>>>()
        private val catalogLists = ConcurrentHashMap<String, Pair<Long, List<CatalogRef>>>()
    }

    /** One answered entry of an extension's `getFilterList` (its Categories /
     *  Sorts) — the browsable slices a video site exposes. */
    private data class AnymexFilterValue(val name: String, val value: String)
    private data class AnymexFilter(val type: String, val name: String, val values: List<AnymexFilterValue>)

    private fun module(): File? {
        if (config.url.isBlank()) return null
        val f = File(config.url)
        return if (f.exists()) f else null
    }

    override suspend fun catalogs(): List<CatalogRef> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        catalogLists[config.id]?.let { (at, list) ->
            if (now - at < FILTER_TTL_MS) return@withContext list
        }
        val out = ArrayList<CatalogRef>()
        out += CatalogRef(config.id, MediaType.SERIES, CATALOG_POPULAR, "Popular")
        out += CatalogRef(config.id, MediaType.SERIES, CATALOG_LATEST, "Latest")
        // The extension's own categories (123AV's Censored/Uncensored/… and
        // its Recent Update / Most viewed today / … sorts): one catalogue
        // each, so opening one lists that whole slice. The filter list is
        // fetched once here and cached — opening a category never refetches it.
        val flt = runCatching { filters() }.getOrDefault(emptyList())
        for ((fi, f) in flt.withIndex()) {
            for ((vi, v) in f.values.withIndex()) {
                out += CatalogRef(config.id, MediaType.SERIES, "flt:$fi:$vi", v.name)
            }
        }
        val done = out.toList()
        catalogLists[config.id] = now to done
        done
    }

    /**
     * The Home rows: Popular, Latest, then the sort slices first ("Most
     * viewed today", "Recent Update" — the Today/Recent the report asks for),
     * capped so one filter-heavy extension cannot flood Home with dozens of
     * rows. Every category stays reachable through the full [catalogs].
     */
    override suspend fun homeCatalogs(): List<CatalogRef> = withContext(Dispatchers.IO) {
        val all = runCatching { catalogs() }.getOrDefault(emptyList())
        if (all.size <= 2) return@withContext all
        val flt = runCatching { filters() }.getOrDefault(emptyList())
        val ordered = flt.sortedBy {
            if (it.type.contains("sort", true) || it.name.contains("sort", true)) 0 else 1
        }
        val wanted = LinkedHashSet<String>()
        wanted += CATALOG_POPULAR
        wanted += CATALOG_LATEST
        for (f in ordered) {
            val fi = flt.indexOf(f)
            for ((vi, _) in f.values.withIndex()) {
                if (wanted.size >= 8) break
                wanted += "flt:$fi:$vi"
            }
            if (wanted.size >= 8) break
        }
        val byId = all.associateBy { it.id }
        wanted.mapNotNull { byId[it] }
    }

    private suspend fun filters(): List<AnymexFilter> {
        val now = System.currentTimeMillis()
        filterLists[config.id]?.let { (at, list) ->
            if (now - at < FILTER_TTL_MS) return list
        }
        val mod = module() ?: return emptyList()
        val list = parseFilters(AnymexRuntime.filterList(mod, config.id))
        if (list.isNotEmpty()) filterLists[config.id] = now to list
        return list
    }

    private fun parseFilters(raw: String?): List<AnymexFilter> {
        if (raw.isNullOrBlank()) return emptyList()
        val arr = listArray(raw) ?: return emptyList()
        val out = ArrayList<AnymexFilter>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val values = o.optJSONArray("values") ?: continue
            val vals = ArrayList<AnymexFilterValue>()
            for (j in 0 until values.length()) {
                val v = values.optJSONObject(j) ?: continue
                val name = v.optString("name").trim()
                val value = v.optString("value").trim()
                if (name.isBlank() || value.isBlank()) continue
                vals += AnymexFilterValue(name, value)
            }
            if (vals.size < 2) continue
            val type = o.optString("type").trim().ifBlank { o.optString("type_name").trim() }
            val name = o.optString("name").trim().ifBlank { type }
            out += AnymexFilter(
                type = type.ifBlank { "filter$i" },
                name = name.ifBlank { "Category" },
                values = vals,
            )
        }
        return out
    }

    /**
     * Builds the Mangayomi filter argument for one answered category: the
     * FULL answered list with the wanted entry selected (`state` is the
     * selected index — the shape `search(query, page, filters)` reads) and
     * every other select answered at its default. Filters that are not
     * plain name/value selects are left out entirely rather than guessed.
     */
    private fun filterStateJson(all: List<AnymexFilter>, fi: Int, vi: Int): String {
        val arr = JSONArray()
        for ((index, f) in all.withIndex()) {
            val o = JSONObject()
            o.put("type", f.type)
            o.put("name", f.name)
            o.put("state", if (index == fi) vi else 0)
            val vals = JSONArray()
            for (v in f.values) {
                vals.put(JSONObject().put("name", v.name).put("value", v.value))
            }
            o.put("values", vals)
            arr.put(o)
        }
        return arr.toString()
    }

    private suspend fun filterCatalog(refId: String, page: Int): List<MediaItem> {
        val parts = refId.split(":")
        if (parts.size != 3) return emptyList()
        val fi = parts[1].toIntOrNull() ?: return emptyList()
        val vi = parts[2].toIntOrNull() ?: return emptyList()
        val mod = module() ?: return emptyList()
        val all = filters()
        val f = all.getOrNull(fi) ?: return emptyList()
        if (vi !in f.values.indices) return emptyList()
        val raw = AnymexRuntime.searchFiltered(mod, config.id, filterStateJson(all, fi, vi), page)
        markWall(raw)
        return mapItems(raw)
    }

    override suspend fun getCatalog(ref: CatalogRef, page: Int): List<MediaItem> =
        withContext(Dispatchers.IO) {
            if (ref.id.startsWith("flt:")) {
                val got = filterCatalog(ref.id, page.coerceAtLeast(1))
                if (got.isNotEmpty()) {
                    catalogErrors.remove(config.id)
                    lastOutcome.remove(config.id)
                }
                return@withContext got
            }
            val mod = module() ?: return@withContext emptyList()
            val first = when (ref.id) {
                CATALOG_LATEST -> AnymexRuntime.latest(mod, config.id, page.coerceAtLeast(1))
                else -> AnymexRuntime.popular(mod, config.id, page.coerceAtLeast(1))
            }
            val items = mapItems(first)
            if (items.isNotEmpty()) {
                catalogErrors.remove(config.id)
                lastOutcome.remove(config.id)
                return@withContext items
            }
            // Plenty of scripts only implement ONE of the two catalogue calls
            // well (the other answers empty or "not implemented") — trying the
            // sibling before reporting an empty catalogue is what makes those
            // extensions list titles instead of a blank page.
            val second = when (ref.id) {
                CATALOG_LATEST -> AnymexRuntime.popular(mod, config.id, page.coerceAtLeast(1))
                else -> AnymexRuntime.latest(mod, config.id, page.coerceAtLeast(1))
            }
            val retry = mapItems(second)
            if (retry.isNotEmpty()) {
                catalogErrors.remove(config.id)
                lastOutcome.remove(config.id)
                return@withContext retry
            }
            markWall(first)
            markWall(second)
            // The extension's own catalogue paths went stale but the site is
            // alive (the globe loads it fine): scrape the front page for
            // title cards instead of reporting "its pages may have moved".
            // Only when the engine recorded a real failure — a merely empty
            // catalogue (a search-only source, the end of the pages) is a
            // legitimate answer and is left alone — and only on page 1, which
            // is the only page a front page has.
            if (page == 1 && catalogErrors.containsKey(config.id)) {
                val home = runCatching { AnymexHomepage.titles(config) }.getOrDefault(emptyList())
                if (home.isNotEmpty()) {
                    catalogErrors.remove(config.id)
                    lastOutcome[config.id] = "✓ ${home.size} titles (homepage)"
                    return@withContext home
                }
            }
            // Page 2+ coming back empty is just the end of the catalogue, not
            // a failure — and a specific engine error already recorded (a dead
            // site, an HTTP status, a script error) must never be overwritten
            // by the generic line below: that overwrite is what hid every real
            // cause behind "no titles".
            if (page > 1) return@withContext retry
            if (!catalogErrors.containsKey(config.id)) {
                if (first.isNullOrBlank() && second.isNullOrBlank()) {
                    noteCatalogError("Empty catalogue answer — the site may be blocking or down")
                } else if (retry.isEmpty() && items.isEmpty()) {
                    noteCatalogError("The site returned no titles for this catalogue")
                }
            }
            retry
        }

    override suspend fun search(query: String, page: Int): List<MediaItem> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            val mod = module() ?: return@withContext emptyList()
            val raw = AnymexRuntime.search(mod, config.id, query, page.coerceAtLeast(1))
            markWall(raw)
            mapItems(raw)
        }

    override suspend fun getMeta(item: MediaItem): MediaItem = withContext(Dispatchers.IO) {
        val mod = module() ?: return@withContext item
        val raw = AnymexRuntime.detail(mod, config.id, item.id) ?: return@withContext item
        val d = firstObject(raw) ?: return@withContext item
        val title = d.optString("title").ifBlank { d.optString("name") }.trim()
        val overview = d.optString("description").trim().ifBlank { null }
        val genres = genresOf(d)
        val poster = absUrl(
            d.optString("imageUrl").ifBlank { d.optString("image") }.trim(),
            siteBase(),
        ).takeIf { it.isNotBlank() && !it.startsWith("data:") }
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
            ?: d.optJSONArray("data")
            ?: d.optJSONArray("entries")
            ?: d.optJSONArray("items")
            ?: return@withContext null
        val out = ArrayList<Episode>()
        for (i in 0 until minOf(arr.length(), MAX_EPISODES)) {
            val o = arr.optJSONObject(i) ?: continue
            val url = cleanLink(
                o.optString("url").ifBlank { o.optString("link") }
                    .ifBlank { o.optString("href") }.ifBlank { o.optString("id") }
                    .ifBlank { o.optString("episodeUrl") }.trim()
            )
            if (url.isBlank()) continue
            val name = o.optString("name").trim()
                .ifBlank { o.optString("title").trim() }
                .ifBlank { null }
            val n = numOf(o) ?: (i + 1)
            val season = o.opt("season")?.toString()?.toIntOrNull()?.takeIf { it > 0 } ?: 1
            out += Episode(number = n, id = url, name = name, season = season)
        }
        out.ifEmpty { null }
    }

    override suspend fun getStreams(item: MediaItem, episode: Episode?): List<StreamSource> =
        withContext(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            val mod = module() ?: return@withContext fail("✗ Source file is missing — reinstall it.")
            val epRel = episode?.id?.takeIf { it.isNotBlank() } ?: item.id
            // The episode page is fetched by name here (a bare Client.get),
            // not through the extension's own site-prefixed request — a
            // site-relative id would fetch nothing, so it is resolved first.
            val epUrl = absUrl(cleanLink(epRel), siteBase()).ifBlank { epRel }
            val raw = AnymexRuntime.videos(mod, config.id, epUrl)
                ?: return@withContext fail("✗ No playable sources for this title.")
            val answered = mapVideos(raw, siteBase())
            if (answered.isEmpty()) return@withContext fail("✗ No playable sources for this title.")
            // Playing logic (AnymeX app parity + one step further): an
            // extension that answers EMBED pages instead of files (watch,
            // player and embed links the extension itself did not extract)
            // used to hand those pages straight to the player, where every
            // one failed. When nothing in the answer looks directly playable,
            // each link goes through the same universal extraction engine
            // every other embed in the app goes through — so a video page that
            // AnymeX plays via its own extractors plays here too.
            val out = resolveEmbeds(answered)
            if (out.isEmpty()) return@withContext fail("✗ No playable sources for this title.")
            streamErrors.remove(config.id)
            lastOutcome[config.id] = "✓ ${out.size} source${if (out.size == 1) "" else "s"} in " +
                "${(System.currentTimeMillis() - started) / 1000}s"
            val distinct = out.distinctBy { it.url }
            StreamProbe.warmAsync(distinct)
            distinct
        }

    /**
     * Teaches Coil image loader the site Referer for a poster host.
     */
private fun recordPosterReferer(url: String?, siteBase: String?) {
            val u = url?.trim().orEmpty()
            if (u.isBlank() || !u.startsWith("http")) return
            val sb = siteBase?.trim()?.trimEnd('/')?.ifBlank { null }
            val ref = if (sb != null) sb + "/" else runCatching {
                    val h = java.net.URI(u).host ?: return
                    "https://" + h + "/"
                }.getOrNull() ?: return
                runCatching {
                    val host = java.net.URI(u).host?.lowercase() ?: return@runCatching
                    val m = com.hikari.app.cs3.Cs3MainApiProvider.imageHostReferers
                    m.putIfAbsent(host, ref)
                    m.putIfAbsent("www." + host, ref)
                }
        }
    
        /**
     * The extension's site root (`https://host/lang`, else the origin), or
     * null when the install names none.
     */
    private fun siteBase(): String? =
        runCatching { AnymexPluginManager.siteUrlOf(config) }
            .getOrNull()?.trim()?.trimEnd('/')?.takeIf { it.startsWith("http") }

    private fun siteOrigin(base: String?): String? =
        base?.let {
            runCatching {
                val u = java.net.URI(it)
                val port = if (u.port > 0) ":${u.port}" else ""
                "${u.scheme}://${u.host}$port"
            }.getOrNull()
        }

    /**
     * Tidies a link the extension PRINTED (detail/episode ids go straight back
     * into the extension, which re-attaches its own site prefix — so they stay
     * site-relative here). Extensions join paths by hand (`"/" + href`), which
     * produces a leading `//` that is NOT scheme-relative (no dotted host) —
     * 123AV's `//en/v/…` links are exactly that — so it collapses to one
     * slash. A genuine `//host/…` URL gains its scheme instead.
     */
    private fun cleanLink(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty() || !t.startsWith("//")) return t
        val rest = t.substring(2)
        val host = rest.substringBefore('/')
        if (host.contains('.') || host.contains(':')) return "https:$t"
        return "/" + rest.trimStart('/')
    }

    /**
     * Resolves art and stream URLs — things loaded OUTSIDE the extension, by
     * Coil and the player — against the site. Extensions routinely print
     * those relative (`data-src="/cover/1.jpg"`), which loads as nothing.
     * Absolute, magnet and data: URLs pass through untouched.
     */
    private fun absUrl(raw: String, base: String?): String {
        val t = raw.trim()
        if (t.isEmpty()) return t
        if (t.startsWith("http", true) || t.startsWith("magnet:", true) || t.startsWith("data:")) return t
        val b = base?.trim()?.trimEnd('/')?.takeIf { it.startsWith("http") } ?: return t
        if (t.startsWith("//")) {
            val rest = t.substring(2)
            val host = rest.substringBefore('/')
            if (host.contains('.') || host.contains(':')) return "https:$t"
            return (siteOrigin(b) ?: b) + "/" + rest.trimStart('/')
        }
        if (t.startsWith("/")) return (siteOrigin(b) ?: b) + t
        return "$b/$t"
    }

    private fun mapItems(raw: String?): List<MediaItem> {
        if (raw.isNullOrBlank()) return emptyList()
        val arr = listArray(raw) ?: return emptyList()
        val base = siteBase()
        val out = ArrayList<MediaItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name").ifBlank { o.optString("title") }.trim()
            if (name.isBlank()) continue
            val link = cleanLink(
                o.optString("link").ifBlank { o.optString("url") }
                    .ifBlank { o.optString("href") }.ifBlank { o.optString("id") }.trim()
            )
            if (link.isBlank()) continue
            out += MediaItem(
                providerId = config.id,
                id = link,
                title = name,
                type = MediaType.SERIES,
                posterUrl = absUrl(
                    o.optString("imageUrl").ifBlank { o.optString("image") }
                        .ifBlank { o.optString("cover") }.ifBlank { o.optString("poster") }
                        .ifBlank { o.optString("thumbnail") }.ifBlank { o.optString("artwork") }
                        .ifBlank { o.optString("coverUrl") }.trim(),
                    base,
                ).ifBlank { null }
                    ?.takeIf { !it.startsWith("data:") }
                    ?.also { recordPosterReferer(it, base) },
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
        markWall(err)
    }

    private val wallRe = Regex(
        "\\b(403|429|503)\\b|cloudflare|just a moment|one moment, please|verify you are human|cf-chl|ddos|access denied|attention required",
        RegexOption.IGNORE_CASE,
    )

    private fun markWall(text: String?) {
        val t = text.orEmpty()
        if (t.isBlank() || !wallRe.containsMatchIn(t)) return
        val site = runCatching { AnymexPluginManager.siteUrlOf(config) }.getOrNull()
        if (!site.isNullOrBlank()) com.hikari.app.net.CloudflareVerifier.markBlocked(site)
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

    private fun numOf(o: JSONObject): Int? {
        for (k in listOf("number", "episode_number", "episodeNumber", "episode", "ep", "no", "index", "num")) {
            if (o.isNull(k)) continue
            o.opt(k)?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { s ->
                s.toIntOrNull() ?: s.toFloatOrNull()?.toInt()?.takeIf { it > 0 }
            }?.let { return it }
        }
        return null
    }

    private fun mapVideos(raw: String, base: String?): List<StreamSource> {
        val out = ArrayList<StreamSource>()
        val t = raw.trim()
        if (t.isEmpty()) return out
        if (!t.startsWith("{") && !t.startsWith("[")) {
            if (t.startsWith("http", true) || t.startsWith("magnet:", true)) {
                videoTo(config.name, absUrl(t, base), null, null, null)?.let { out += it }
            }
            return out
        }
        runCatching {
            val arr = listArray(t) ?: return out
            for (i in 0 until minOf(arr.length(), MAX_STREAMS)) {
                when (val e = arr.opt(i)) {
                    is JSONObject -> {
                        val u = absUrl(
                            e.optString("url").ifBlank { e.optString("originalUrl") }
                                .ifBlank { e.optString("streamUrl") }.ifBlank { e.optString("file") }
                                .ifBlank { e.optString("src") }.ifBlank { e.optString("link") }.trim(),
                            base,
                        )
                        if (u.isNotBlank()) {
                            val q = e.optString("quality").ifBlank { e.optString("label") }
                                .ifBlank { e.optString("resolution") }.trim()
                            val sz = e.optString("size").trim()
                            val lang = e.optString("language").ifBlank { e.optString("lang") }.trim()
                            val detail = listOfNotNull(
                                q.ifBlank { null },
                                sz.ifBlank { null },
                                lang.ifBlank { null },
                            ).joinToString(" • ").ifBlank { null }
                            videoTo(
                                q.ifBlank { e.optString("title") }.ifBlank { e.optString("name") }.ifBlank { config.name },
                                u, e.optJSONObject("headers"), subsOf(e, base),
                                detail,
                            )?.let { out += it }
                        }
                    }
                    is String -> if (e.isNotBlank()) videoTo(config.name, absUrl(e.trim(), base), null, null, null)?.let { out += it }
                    else -> {}
                }
            }
        }
        return out
    }

    private fun subsOf(o: JSONObject, base: String?): List<SubtitleSource> {
        val arr = o.optJSONArray("subtitles") ?: o.optJSONArray("subs") ?: return emptyList()
        val out = ArrayList<SubtitleSource>()
        for (i in 0 until arr.length()) {
            when (val e = arr.opt(i)) {
                is JSONObject -> {
                    val u = absUrl(
                        e.optString("file").ifBlank { e.optString("url") }
                            .ifBlank { e.optString("uri") }.trim(),
                        base,
                    )
                    if (u.isBlank()) continue
                    out += SubtitleSource(
                        lang = e.optString("label").ifBlank { e.optString("language") }.ifBlank { "Sub" },
                        url = u,
                    )
                }
                is String -> if (e.isNotBlank()) out += SubtitleSource(lang = "Sub", url = absUrl(e, base))
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

    private val directRe = Regex(
        "\\.(m3u8|mpd|mp4|mkv|avi|webm|mov|m4v|ts)([?#]|$)",
        RegexOption.IGNORE_CASE,
    )

    private fun looksDirect(u: String): Boolean {
        val t = u.trim()
        if (t.startsWith("magnet:", true)) return true
        return directRe.containsMatchIn(t)
    }

    /**
     * Embed fan-out for [getStreams]: when the extension's answer holds no
     * directly playable file, every http(s) link is offered to the universal
     * extraction engine (fetch the page, unpack its player config, scan for
     * HLS/MP4, run the host dances, then the jar's extractor registry) with
     * the answer's own Referer attached. Links that resolve keep the
     * extension's identity and gain its server label; links that do not stay
     * on the list untouched, so an unresolvable answer degrades to exactly
     * what the extension printed instead of an empty failure.
     */
    private suspend fun resolveEmbeds(found: List<StreamSource>): List<StreamSource> {
        if (found.any { looksDirect(it.url) }) return found
        val cands = found.filter { it.url.startsWith("http", true) }.take(8)
        if (cands.isEmpty()) return found
        val cracked = coroutineScope {
            cands.map { s ->
                async {
                    val ref = s.headers.entries
                        .firstOrNull { it.key.equals("Referer", true) }?.value
                    val got = runCatching {
                        withTimeoutOrNull(25_000) {
                            com.hikari.app.cs3.FallbackResolver.resolveEmbedUrl(s.url, ref)
                        }.orEmpty()
                    }.getOrDefault(emptyList())
                    s to got
                }
            }.awaitAll()
        }
        val merged = ArrayList<StreamSource>(found.size)
        val seen = HashSet<String>()
        for ((s, got) in cracked) {
            if (got.isEmpty()) {
                if (seen.add(s.url)) merged += s
                continue
            }
            for (r in got) {
                val u = r.url.trim()
                if (u.isEmpty() || !seen.add(u)) continue
                val label = r.name.ifBlank { s.name }
                merged += r.copy(
                    name = if (label.isBlank() || r.name.contains(s.name, true)) r.name
                    else "${r.name} · ${s.name}",
                    provider = "Anymex",
                    providerId = config.id,
                    providerName = config.name,
                )
            }
        }
        for (s in found) if (seen.add(s.url)) merged += s
        return merged
    }

    private fun genresOf(d: JSONObject): List<String> {        val g = d.opt("genre") ?: d.opt("genres") ?: return emptyList()
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
