package com.hikari.app.zangetsu

import com.hikari.app.data.CatalogRef
import com.hikari.app.data.Episode
import com.hikari.app.data.MediaItem
import com.hikari.app.data.MediaType
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.StreamSource
import com.hikari.app.data.SubtitleSource
import com.hikari.app.providers.ContentProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * A Zangetsu provider — one self-contained `.js` scraper speaking `getHome`
 * / `popular` / `search` / `getDetail` / `getEpisodes` / `getVideoSources`
 * (see [ZangetsuRuntime]).
 *
 * Items carry the source's own URL as their id; episodes carry the source's
 * episode URL (often a custom scheme packing season+episode). Anime sources
 * map to SERIES + rawType "anime" (tracker parity, so anime plays down the
 * anime path); movie sources map by their own type string.
 */
class ZangetsuProvider(override val config: ProviderConfig) : ContentProvider {

    companion object {
        /** Why a catalogue call produced nothing, for the screen's empty state. */
        val catalogErrors = ConcurrentHashMap<String, String>()
        /** Why a stream call produced nothing, for the sources sheet. */
        val streamErrors = ConcurrentHashMap<String, String>()
        private const val MAX_ITEMS = 500
        /** Pack scheme per movie provider whose `getVideoSources` unpacks a
         *  JSON href list (`4khd://…`, `uhd://…` …) instead of a page URL. */
        private val PACK_SCHEMES = mapOf(
            "fourkhdhub" to "4khd://",
            "uhdmovies" to "uhd://",
            "hdhub4u" to "hdh://",
            "vegamovies" to "vega://",
        )
    }

    private val sourceId: String get() = config.id.removePrefix("zangetsu|")
    private val dir: File get() = ZangetsuPluginManager.dirOf(config)

    private data class Info(val type: String, val name: String)
    private var info: Info? = null

    private suspend fun info(): Info {
        info?.let { return it }
        val payload = ZangetsuRuntime.call(dir, sourceId, "getInfo", "[]")
        val data = dataObject(payload)
        // getInfo is advisory: a source without it still works, typed by its
        // own items. Default to movie (most Zangetsu sources are movies).
        val t = data?.optString("type").orEmpty().lowercase().ifBlank { "movie" }
        return Info(t, data?.optString("name").orEmpty()).also { info = it }
    }

    private fun args(vararg pairs: Pair<String, Any?>): String {
        val o = JSONObject()
        for ((k, v) in pairs) {
            when (v) {
                null -> o.put(k, JSONObject.NULL)
                is Number, is Boolean, is String -> o.put(k, v)
                else -> o.put(k, v.toString())
            }
        }
        return JSONArray(listOf(o)).toString()
    }

    // ---- catalogue ----

    private suspend fun homeRows(): JSONArray? {
        val payload = ZangetsuRuntime.call(dir, sourceId, "getHome", "[{}]", catalogBudget = true)
        val data = dataOf(payload)
        if (data is JSONArray && data.length() > 0) {
            catalogErrors.remove(config.id)
            return data
        }
        // Fall back to the flattened popular list as one row.
        val pop = ZangetsuRuntime.call(dir, sourceId, "popular", "[{}]", catalogBudget = true)
        val pdata = dataOf(pop)
        if (pdata is JSONArray && pdata.length() > 0) {
            catalogErrors.remove(config.id)
            val row = JSONObject().put("title", "Popular").put("items", pdata)
            return JSONArray().put(row)
        }
        noteCatalogFailure(payload, "Catalogue")
        return null
    }

    override suspend fun catalogs(): List<CatalogRef> = withContext(Dispatchers.IO) {
        val rows = homeRows() ?: return@withContext emptyList()
        buildList {
            for (i in 0 until rows.length()) {
                val r = rows.optJSONObject(i) ?: continue
                val title = r.optString("title").trim().ifBlank { continue }
                add(CatalogRef(config.id, MediaType.UNKNOWN, "row:$title", title, "zangetsu"))
            }
        }.distinctBy { it.id }
    }

    override suspend fun homeCatalogs(): List<CatalogRef> = catalogs()

    override suspend fun getCatalog(ref: CatalogRef, page: Int): List<MediaItem> =
        withContext(Dispatchers.IO) {
            // Rows arrive whole; page 1 serves the row, later pages are empty.
            if (page > 1) return@withContext emptyList()
            val title = ref.id.substringAfter("row:", "")
            val rows = homeRows() ?: return@withContext emptyList()
            for (i in 0 until rows.length()) {
                val r = rows.optJSONObject(i) ?: continue
                if (r.optString("title").trim() == title) return@withContext toItems(r.optJSONArray("items"))
            }
            emptyList()
        }

    override suspend fun search(query: String, page: Int): List<MediaItem> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            val payload = ZangetsuRuntime.call(
                dir, sourceId, "search",
                "[${JSONObject.quote(query)}, ${page.coerceAtLeast(1)}, {}]",
                catalogBudget = true,
            )
            val data = dataOf(payload) ?: run {
                noteCatalogFailure(payload, "Search")
                return@withContext emptyList()
            }
            catalogErrors.remove(config.id)
            toItems(data)
        }

    private fun noteCatalogFailure(payload: String, what: String) {
        val err = runCatching { JSONObject(payload).optString("error") }.getOrNull()
        val msg = if (err.isNullOrBlank()) "$what returned nothing" else err
        catalogErrors[config.id] = "$what failed — $msg".take(200)
    }

    private suspend fun toItems(data: Any?): List<MediaItem> {
        val arr = data as? JSONArray ?: return emptyList()
        val fallbackAnime = info().type == "anime"
        val out = ArrayList<MediaItem>(minOf(arr.length(), MAX_ITEMS))
        for (i in 0 until minOf(arr.length(), MAX_ITEMS)) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("url").trim()
            val title = o.optString("title").trim()
            if (url.isBlank() || title.isBlank()) continue
            val id = o.optString("id").trim().ifBlank { url }
            val t = o.optString("type").trim().lowercase()
            val anime = t == "anime" || (t.isBlank() && fallbackAnime)
            out += MediaItem(
                providerId = config.id,
                id = id,
                title = title,
                type = if (anime) MediaType.SERIES else mediaTypeOf(t),
                posterUrl = o.optString("cover").ifBlank { o.optString("poster") }
                    .ifBlank { o.optString("image") }.ifBlank { null },
                year = o.optInt("year", 0).takeIf { it > 0 },
                overview = o.optString("description").ifBlank { null },
                genres = stringList(o.optJSONArray("genres")),
                rating = o.optDouble("rating", 0.0).takeIf { it > 0 },
                rawType = if (anime) "anime" else t.ifBlank { "movie" },
            )
        }
        return out
    }

    // ---- details ----

    override suspend fun getMeta(item: MediaItem): MediaItem = withContext(Dispatchers.IO) {
        if (item.id.isBlank()) return@withContext item
        val payload = ZangetsuRuntime.call(dir, sourceId, "getDetail", "[${JSONObject.quote(item.id)}, {}]")
        val d = dataObject(payload) ?: return@withContext item
        val t = d.optString("type").trim().lowercase()
        val anime = t == "anime" || (t.isBlank() && item.rawType == "anime")
        MediaItem(
            providerId = item.providerId,
            id = item.id,
            title = d.optString("title").trim().ifBlank { item.title },
            type = if (anime) MediaType.SERIES else mediaTypeOf(t).takeIf { it != MediaType.UNKNOWN } ?: item.type,
            posterUrl = d.optString("cover").ifBlank { d.optString("poster") }
                .ifBlank { item.posterUrl.orEmpty() }.ifBlank { null },
            year = d.optInt("year", 0).takeIf { it > 0 } ?: item.year,
            overview = d.optString("description").ifBlank { item.overview.orEmpty() }.ifBlank { null },
            genres = stringList(d.optJSONArray("genres")).ifEmpty { item.genres },
            rating = d.optDouble("rating", 0.0).takeIf { it > 0 } ?: item.rating,
            rawType = if (anime) "anime" else t.ifBlank { item.rawType },
            originalTitle = item.originalTitle,
        )
    }

    override suspend fun getEpisodes(item: MediaItem): List<Episode>? =
        withContext(Dispatchers.IO) {
            if (item.id.isBlank()) return@withContext null
            ensureUhdNaukriPatch()
            val payload = ZangetsuRuntime.call(dir, sourceId, "getEpisodes", "[${JSONObject.quote(item.id)}, {}]")
            val data = dataOf(payload) as? JSONArray
            val list = buildList {
                if (data != null) for (i in 0 until data.length()) {
                    val e = data.optJSONObject(i) ?: continue
                    val url = e.optString("url").trim()
                    if (url.isBlank()) continue
                    add(
                        Episode(
                            number = e.optInt("number", i + 1).takeIf { it > 0 } ?: (i + 1),
                            id = url,
                            name = e.optString("title").ifBlank { "Episode ${i + 1}" },
                            image = e.optString("thumbnail").ifBlank { null },
                            overview = e.optString("description").ifBlank { null },
                            released = e.optString("date").ifBlank { null },
                        )
                    )
                }
            }
            if (list.isNotEmpty()) return@withContext list
            // A movie page whose download blocks the provider's own selectors
            // no longer match (site markup drifted): build its single packed
            // episode from the page itself, so Detail still offers Play and
            // the tap below carries a packed id the JS can unpack.
            harvestedEpisode(item)?.let { return@withContext listOf(it) }
            null
        }

    // ---- streams ----

    override suspend fun getStreams(item: MediaItem, episode: Episode?): List<StreamSource> =
        withContext(Dispatchers.IO) {
            var ep = episode
            if (ep == null) {
                // A movie tap carries no episode — but the pack-scheme
                // providers cannot unpack the bare detail URL (their instant
                // "no download links" reject). The packed single-episode id
                // from the provider's own list is the playback credential.
                val eps = runCatching { getEpisodes(item) }.getOrNull()
                ep = when {
                    eps.isNullOrEmpty() -> null
                    eps.size == 1 -> eps[0]
                    else -> eps.firstOrNull { isPackedId(it.id) }
                }
            }
            var epUrl = ep?.id?.trim().orEmpty().ifBlank { item.id.trim() }
            if (epUrl.isBlank()) {
                streamErrors[config.id] = "Nothing to play (empty link)."
                return@withContext emptyList()
            }
            ensureUhdNaukriPatch()
            var payload = ZangetsuRuntime.call(dir, sourceId, "getVideoSources", "[${JSONObject.quote(epUrl)}]")
            var data = dataOf(payload) as? JSONArray
            if ((data == null || data.length() == 0) && item.id.trim().startsWith("http")) {
                // The bare detail URL reached a JS unpacker (stale or missing
                // episode) — or a PACKED id answered empty because the page's
                // hub links drifted past the installed script's selectors
                // (uhdmovies' thenaukriadda ?sid= move). Harvest the page's hub
                // links and re-ask packed when the set is new, then fall back
                // to direct media files on the same page.
                val hrefs = harvestHubLinks(item.id.trim())
                val scheme = PACK_SCHEMES[sourceId]
                if (hrefs.isNotEmpty() && scheme != null) {
                    val repacked = scheme + java.net.URLEncoder.encode(JSONArray(hrefs).toString(), "UTF-8")
                    if (repacked != epUrl) {
                        epUrl = repacked
                        com.hikari.app.data.Logs.log(
                            "Zangetsu",
                            "$sourceId getVideoSources: re-asking packed with ${hrefs.size} harvested links",
                        )
                        payload = ZangetsuRuntime.call(dir, sourceId, "getVideoSources", "[${JSONObject.quote(epUrl)}]")
                        data = dataOf(payload) as? JSONArray
                    }
                }
                if (data == null || data.length() == 0) {
                    val direct = harvestDirectMedia(item.id.trim())
                    if (direct.isNotEmpty()) {
                        com.hikari.app.data.Logs.log(
                            "Zangetsu",
                            "$sourceId getVideoSources: playing ${direct.size} direct file(s) from the page",
                        )
                        streamErrors.remove(config.id)
                        return@withContext direct.map { toDirectSource(it, item.id.trim()) }
                    }
                }
            }
            if (data == null || data.length() == 0) {
                val err = runCatching { JSONObject(payload).optString("error") }.getOrNull()
                streamErrors[config.id] = if (err.isNullOrBlank()) "No playable sources." else err.take(200)
                return@withContext emptyList()
            }
            streamErrors.remove(config.id)
            buildList {
                for (i in 0 until data.length()) {
                    val s = data.optJSONObject(i) ?: continue
                    val url = s.optString("url").trim()
                    if (url.isBlank()) continue
                    val quality = s.optString("quality").trim().ifBlank { "auto" }
                    val container = s.optString("container").trim().lowercase()
                    val label = s.optString("label").trim()
                    val headers = linkedMapOf<String, String>()
                    s.optJSONObject("headers")?.keys()?.forEach { k ->
                        headers[k] = s.optJSONObject("headers").optString(k)
                    }
                    val subs = buildList {
                        val sa = s.optJSONArray("subtitles") ?: return@buildList
                        for (j in 0 until sa.length()) {
                            val t = sa.optJSONObject(j) ?: continue
                            val su = t.optString("url").ifBlank { t.optString("file") }.trim()
                            if (su.isBlank()) continue
                            add(
                                SubtitleSource(
                                    lang = t.optString("lang").ifBlank { t.optString("label") }.ifBlank { "sub" },
                                    url = su,
                                    name = t.optString("label"),
                                )
                            )
                        }
                    }
                    add(
                        StreamSource(
                            name = (label.ifBlank { quality }),
                            url = url,
                            headers = headers,
                            subtitles = subs,
                            isM3u8 = container == "hls" || container == "m3u8" || ".m3u8" in url,
                            isMpd = container == "mpd" || ".mpd" in url,
                            provider = "Zangetsu",
                            providerId = config.id,
                            providerName = config.name,
                            details = listOf(quality, container.ifBlank { null }, label.ifBlank { null })
                                .filterNotNull().distinct().joinToString(" • "),
                        )
                    )
                }
            }
        }

    // ---- helpers ----

    /** Marker the uhdmovies auto-patch leaves in provider.js (idempotent). */
    private val uhdNaukriMarker = "thenaukriadda-bypass-v2"

    /**
     * uhdmovies moved its maxbutton targets to `en.thenaukriadda.in/?sid=…`
     * (LinkPress `_lp_http` gate) while the shipped 1.0.3 script only resolves
     * hrefli/unblockedgames gates: the new gate was GET-only through the stock
     * chain, so every title ended as "no playable links resolved" (log-proven
     * on Bad Apples / Spider-Man / Evil Dead Burn — 12 harvested links
     * re-asked packed, zero resolved). The upstream repo is still on 1.0.3, so
     * the installed file is patched in place on first use: the gate is POSTed
     * once, then every landing form behind it is POSTed in turn (the runtime
     * now keeps cookies like the reference app, so the session survives the
     * walk) until a ?go= ticket or driveseed page appears for _resolveOne to
     * finish. Skipped (with a log line) when the file already moved on;
     * v1-patched files are upgraded to v2 in place.
     */
    private fun ensureUhdNaukriPatch() {
        if (sourceId != "uhdmovies") return
        try {
            val f = File(dir, "provider.js")
            if (!f.isFile) return
            var js = f.readText()
            if (js.contains(uhdNaukriMarker)) return
            if (js.contains("thenaukriadda-bypass-v1")) {
                val v1start = js.indexOf("// thenaukriadda-bypass-v1:")
                val v1end = js.indexOf("function _resolveOne(link) {")
                if (v1start >= 0 && v1end > v1start) js = js.removeRange(v1start, v1end)
                js = js.replace("thenaukriadda-bypass-v1", uhdNaukriMarker)
            }
            val oldIsLink =
                "/unblockedgames|hrefli|href\\.li|driveseed|driveleech|drive\\.|video-seed|video-leech|gdflix|gdtot|filepress|\\?go=|\\/go\\b|maxbutton/i"
            val newIsLink =
                "/unblockedgames|hrefli|href\\.li|driveseed|driveleech|drive\\.|video-seed|video-leech|gdflix|gdtot|filepress|\\?go=|\\/go\\b|maxbutton|thenaukriadda|\\?sid=|&sid=/i"
            val oldRoute =
                "var p = /unblockedgames|hrefli|href\\.li|\\?go=|\\/go\\b/.test(l) ? _bypassHrefli(link) : Promise.resolve(link);"
            val newRoute =
                "var p = /thenaukriadda|\\?sid=|&sid=/.test(l) ? _bypassNaukri(link) : (/unblockedgames|hrefli|href\\.li|\\?go=|\\/go\\b/.test(l) ? _bypassHrefli(link) : Promise.resolve(link));"
            if (!js.contains("function _resolveOne(link) {")
            ) {
                com.hikari.app.data.Logs.log(
                    "Zangetsu",
                    "uhdmovies patch skipped: selectors moved upstream",
                )
                return
            }
            if (js.contains(oldIsLink)) js = js.replace(oldIsLink, newIsLink)
            if (js.contains(oldRoute)) js = js.replace(oldRoute, newRoute)
            if (js.contains("function _bypassNaukri(")) {
                val v1s = js.indexOf("function _naukriSid(")
                    .takeIf { it >= 0 } ?: js.indexOf("function _bypassNaukri(")
                val v1e = js.indexOf("function _resolveOne(link) {")
                if (v1s >= 0 && v1e > v1s) js = js.removeRange(v1s, v1e)
            }
            js = js.replace("function _resolveOne(link) {", uhdNaukriJs + "\nfunction _resolveOne(link) {")
            if (!js.contains(uhdNaukriMarker)) return
            f.writeText(js)
            com.hikari.app.data.Logs.log(
                "Zangetsu",
                "uhdmovies provider patched for thenaukriadda (?sid=) links",
            )
        } catch (t: Throwable) {
            com.hikari.app.data.Logs.log("Zangetsu", "uhdmovies patch skipped: ${t.message}")
        }
    }

    private val uhdNaukriJs = """
// thenaukriadda-bypass-v2: uhdmovies moved its maxbutton targets to
// en.thenaukriadda.in/?sid=... (LinkPress _lp_http gate). POST the gate once,
// then walk every landing form behind it (cookies persist in the runtime like
// the reference app) until a ?go= ticket or a driveseed/driveleech page shows
// up for _resolveOne to finish.
function _naukriSid(h) {
  var m = h.match(/name=["']_lp_http["'][^>]*value=["']([^"']+)["']/i) ||
    h.match(/value=["']([^"']+)["'][^>]*name=["']_lp_http["']/i);
  return (m && m[1]) || '';
}
function _naukriAction(h, fallback) {
  var m = h.match(/<form[^>]+id=["']lp-land["'][^>]*action=["']([^"']+)["']/i) ||
    h.match(/<form[^>]+action=["']([^"']+)["'][^>]*id=["']lp-land["']/i) ||
    h.match(/<form[^>]+action=["']([^"']+)["']/i);
  var a = (m && m[1]) || '';
  if (!a) return fallback;
  if (/^https?:/i.test(a)) return a;
  return absUrl(a, fallback);
}
function _naukriWalk(html, ref, left) {
  if (!html) return Promise.resolve('');
  var go = (html.match(/\?go=([^"'&\s]+)/) || [])[1] || '';
  if (go) return Promise.resolve({ go: go, ref: ref });
  var dl = (html.match(/https?:\/\/[^\s"'<>]*drive(?:seed|leech)[^\s"'<>]*/i) || [])[0] || '';
  if (dl) return Promise.resolve({ drive: dl });
  if (left <= 0) return Promise.resolve('');
  var f = _form(html);
  var has = (f.action && true) || (f.data && Object.keys(f.data).length > 0);
  if (!has) {
    var go0 = (html.match(/\?go=([^"'&\s]+)/) || [])[0] || '';
    if (go0) return Promise.resolve({ rawGo: go0, ref: ref });
    var dl0 = (html.match(/https?:\/\/[^\s"'<>]*drive(?:seed|leech)[^\s"'<>]*/i) || [])[0] || '';
    if (dl0) return Promise.resolve({ drive: dl0 });
    return Promise.resolve('');
  }
  var host = (ref.match(/^(https?:\/\/[^/]+)/) || [])[1] || '';
  var tgt = (f.action && absUrl(f.action, host)) || ref;
  return _postForm(tgt, f.data, ref).then(function (h2) {
    if (!h2) return '';
    return _naukriWalk(h2, tgt, left - 1);
  });
}
function _bypassNaukri(url) {
  var host = (String(url).match(/^(https?:\/\/[^/]+)/) || [])[1] || '';
  return _get(url).then(function (h1) {
    var sid = _naukriSid(h1);
    if (!sid) return '';
    var target = _naukriAction(h1, url);
    return _postForm(target, { _lp_http: sid }, url).then(function (h2) {
      if (!h2) return '';
      return _naukriWalk(h2, target, 4).then(function (w) {
        if (!w) return '';
        if (typeof w === 'string') return w;
        if (w.drive) return w.drive;
        var sk = w.go || '';
        var ref = w.ref || target;
        if (!sk && w.rawGo) return absUrl(w.rawGo, host);
        if (!sk) return '';
        var host2 = (ref.match(/^(https?:\/\/[^/]+)/) || [])[1] || host;
        return fetch(host2 + '?go=' + sk, { headers: {
          'User-Agent': UA, 'Referer': ref
        } }).then(function (r) {
          var meta = (r.body || '').match(/http-equiv=["']refresh["'][^>]*content=["'][^"']*url=([^"']+)["']/i);
          var driveUrl = meta ? meta[1] : '';
          if (!driveUrl) return '';
          return _get(driveUrl).then(function (t) {
            var path = (t.match(/replace\(["']([^"']+)["']\)/) || [])[1] || '';
            if (!path || path === '/404') return '';
            return absUrl(path, (driveUrl.match(/^(https?:\/\/[^/]+)/) || [])[1] || host2);
          });
        });
      });
    });
  }).catch(function () { return ''; });
}
""".trimIndent()

    /** True for a provider-packed episode id (`4khd://…`, `uhd://…`,
     *  `anikoto://…`) as opposed to a bare http(s) page URL. */
    private fun isPackedId(id: String): Boolean {
        val u = id.trim()
        return u.contains("://") && !u.startsWith("http://") && !u.startsWith("https://")
    }

    /** One harvest per detail page per session: the page is fetched once and
     *  both the packed-episode builder and the stream fallback share it. */
    private val harvestCache = ConcurrentHashMap<String, List<String>>()

    private fun harvestedEpisode(item: MediaItem): Episode? {
        val scheme = PACK_SCHEMES[sourceId] ?: return null
        val pageUrl = item.id.trim()
        if (!pageUrl.startsWith("http")) return null
        val hrefs = harvestHubLinks(pageUrl)
        if (hrefs.isEmpty()) return null
        val packed = scheme + runCatching {
            java.net.URLEncoder.encode(JSONArray(hrefs).toString(), "UTF-8")
        }.getOrDefault("[]")
        return Episode(number = 1, id = packed, name = item.title.ifBlank { "Movie" })
    }

    /**
     * Hub/intermediate links off a movie/series detail page, for pages whose
     * own provider selectors no longer match (site markup drifted: hosts move
     * to fresh link-shortener domains like `greenmotors.club/?id=…` while the
     * installed `.js` still filters the old ones).
     *
     * Deliberately broader than any one provider's filter — hub pages,
     * redirect gates and button links — but never page furniture (styles,
     * scripts, images, anchors) and never video files (those are
     * [harvestDirectMedia]'s job).
     */
    private fun harvestHubLinks(pageUrl: String): List<String> {
        harvestCache[pageUrl]?.let { return it }
        val html = fetchPageHtml(pageUrl)
        val out = ArrayList<String>()
        if (html.isNotBlank()) {
            val origin = pageOrigin(pageUrl)
            val re = Regex("""<a[^>]+href\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
            for (m in re.findAll(html)) {
                val href = absPageUrl(m.groupValues[1].trim(), origin) ?: continue
                if (!isHubHref(href)) continue
                if (out.none { it.equals(href, ignoreCase = true) }) out += href
                if (out.size >= 12) break
            }
        }
        harvestCache[pageUrl] = out
        return out
    }

    private fun harvestDirectMedia(pageUrl: String): List<String> {
        val html = fetchPageHtml(pageUrl)
        if (html.isBlank()) return emptyList()
        val origin = pageOrigin(pageUrl)
        val out = ArrayList<String>()
        val re = Regex("""(?:href|src|data-src|content)\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
        for (m in re.findAll(html)) {
            val href = absPageUrl(m.groupValues[1].trim(), origin) ?: continue
            val path = href.substringBefore('?').substringBefore('#').lowercase()
            if (!path.startsWith("http://") && !path.startsWith("https://")) continue
            if (!(path.contains(".mp4") || path.contains(".mkv") ||
                    path.contains(".m3u8") || path.contains(".mpd"))) continue
            if (out.none { it.equals(href, ignoreCase = true) }) out += href
            if (out.size >= 8) break
        }
        return out
    }

    private fun toDirectSource(url: String, referer: String): StreamSource {
        val lower = url.substringBefore('?').lowercase()
        return StreamSource(
            name = config.name.ifBlank { sourceId } + " [direct]",
            url = url,
            headers = mapOf(
                "User-Agent" to com.hikari.app.net.Http.UA,
                "Referer" to referer,
            ),
            isM3u8 = ".m3u8" in lower,
            isMpd = ".mpd" in lower,
            provider = "Zangetsu",
            providerId = config.id,
            providerName = config.name,
            details = "direct file",
        )
    }

    private fun fetchPageHtml(pageUrl: String): String {
        return runCatching {
            com.hikari.app.net.Http.getString(
                pageUrl,
                mapOf("User-Agent" to com.hikari.app.net.Http.UA),
            )
        }.getOrNull().orEmpty()
    }

    private fun isHubHref(href: String): Boolean {
        val h = href.lowercase()
        if (!h.startsWith("http://") && !h.startsWith("https://")) return false
        if (h.contains("youtube.com") || h.contains("youtu.be") || h.contains("facebook.com") ||
            h.contains("twitter.com") || h.contains("instagram.com") || h.contains("telegram.me") ||
            h.contains("t.me/")
        ) return false
        val path = h.substringBefore('?').substringBefore('#')
        if (path.endsWith(".css") || path.endsWith(".js") || path.endsWith(".png") ||
            path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".webp") ||
            path.endsWith(".gif") || path.endsWith(".svg") || path.endsWith(".ico") ||
            path.endsWith(".woff") || path.endsWith(".woff2")
        ) return false
        if (path.contains(".mp4") || path.contains(".mkv") || path.contains(".m3u8") || path.contains(".mpd")) {
            return false
        }
        return h.contains("hubcloud") || h.contains("hubdrive") || h.contains("hblinks") ||
            h.contains("hubcdn") || h.contains("hubstream") || h.contains("hdstream") ||
            h.contains("greenmotors") || h.contains("pixeldra") || h.contains("gadgets") ||
            h.contains("thenaukriadda") || h.contains("?sid=") || h.contains("&sid=") ||
            h.contains("?id=") || h.contains("&id=") || h.contains("/redirect") ||
            h.contains("/links") || h.contains("maxbutton") || h.contains("hrefli") ||
            h.contains("vcloud") || h.contains("gdflix") || h.contains("driveseed") ||
            h.contains("driveleech") || h.contains("filepress") || h.contains("?go=") ||
            h.contains("/go/") || h.contains("unblockedgames")
    }

    private fun pageOrigin(pageUrl: String): String {
        val m = Regex("^(https?://[^/]+)", RegexOption.IGNORE_CASE).find(pageUrl.trim())
        return m?.groupValues?.get(1).orEmpty()
    }

    private fun absPageUrl(href: String, origin: String): String? {
        val h = href.trim()
        if (h.isBlank() || h.startsWith("#") || h.startsWith("javascript:", ignoreCase = true)) return null
        if (h.startsWith("http://") || h.startsWith("https://")) return h
        if (h.startsWith("//")) return "https:$h"
        if (origin.isBlank()) return null
        return if (h.startsWith("/")) origin + h else "$origin/$h"
    }

    private fun dataObject(payload: String): JSONObject? {
        val root = runCatching { JSONObject(payload) }.getOrNull() ?: return null
        if (!root.optBoolean("ok", false)) return null
        return root.optJSONObject("data")
    }

    private fun dataOf(payload: String): Any? {
        val root = runCatching { JSONObject(payload) }.getOrNull() ?: return null
        if (!root.optBoolean("ok", false)) return null
        if (root.isNull("data")) return null
        return root.get("data")
    }

    private fun stringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                arr.optString(i).trim().takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private fun mediaTypeOf(raw: String): MediaType = when (raw.lowercase()) {
        "movie" -> MediaType.MOVIE
        "tv", "series", "show" -> MediaType.SERIES
        else -> MediaType.UNKNOWN
    }
}
