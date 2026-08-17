package com.hikari.app.providers

import com.hikari.app.data.CatalogRef
import com.hikari.app.data.Episode
import com.hikari.app.data.MediaItem
import com.hikari.app.data.MediaType
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.StreamSource
import com.hikari.app.data.SubtitleSource
import com.hikari.app.net.Http
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * A full implementation of the Stremio Addon Protocol
 * (https://github.com/Stremio/stremio-addon-sdk) — the same protocol the real
 * Stremio client uses, so ANY Stremio addon works here:
 *
 *   /manifest.json                       addon metadata (catalogs, resources, config)
 *   /catalog/{type}/{id}.json            content feeds  (+ optional extra args in path:
 *                                        e.g. `/catalog/movie/top/search=foo.json`,
 *                                        `skip=100.json` for paging)
 *   /meta/{type}/{id}.json               full metadata (series episodes, movie details)
 *   /stream/{type}/{videoId}.json        playable streams (http URLs, torrent infoHash,
 *                                        YouTube ytId, externalUrl)
 *   /subtitles/{type}/{id}.json          subtitles
 *
 * Addon hosts get a browser-like fingerprint (User-Agent + Accept), https→http
 * fallback, the optional `/manifest.json` suffix and query params (addon
 * config keys) are preserved, and torrent streams carry their infoHash +
 * fileIdx + trackers so the player's TorrServer engine can actually play them.
 */
class StremioAddon(override val config: ProviderConfig) : ContentProvider {

    companion object {
        /** Per-provider reason why its home catalog failed (empty = it works). */
        val catalogErrors = ConcurrentHashMap<String, String>()
    }

    // ------------------------------------------------------------------
    //  URL handling — tolerate /manifest.json suffix and keep query params
    //  (some addons bake config like `?apiKey=...` into their install URL).
    // ------------------------------------------------------------------
    private val baseAndQuery: Pair<String, String> by lazy {
        val u = config.url.trim().trimEnd('/')
        val qi = u.indexOf('?')
        val path = if (qi >= 0) u.substring(0, qi) else u
        val query = if (qi >= 0) u.substring(qi + 1) else ""
        val clean = if (path.lowercase().endsWith("/manifest.json")) {
            path.dropLast("/manifest.json".length)
        } else path
        clean.trimEnd('/') to query
    }

    private val base: String get() = baseAndQuery.first
    private val query: String get() = baseAndQuery.second

    /** Builds a resource URL, optionally appending the extra-args segment
     *  (`search=...&skip=...`) that the protocol puts in the path. */
    private fun resUrl(resource: String, type: String, id: String, extra: String? = null): String {
        val e = extra?.takeIf { it.isNotBlank() }?.let { "/$it" } ?: ""
        val s = "$base/$resource/$type/$id$e.json"
        return if (query.isBlank()) s else "$s?$query"
    }

    private fun encode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    private var manifest: JSONObject? = null

    /** Fetches JSON with a browser-like fingerprint. The https→http fallback
     *  matters for addons served from IPFS/NAT boxes and for hosts whose
     *  http:// mirror behaves differently. Never throws. */
    private suspend fun getJson(url: String): JSONObject? {
        val headers = mapOf("Accept" to "application/json, text/plain, */*")
        for (u in listOf(url, url.replaceFirst("https://", "http://")).distinct()) {
            for (attempt in 0 until 2) {
                val body = Http.getString(u, headers)
                val json = body?.let { runCatching { JSONObject(it) }.getOrNull() }
                if (json != null) return json
                if (attempt == 0) {
                    // transient failures are common on first hit (cold serverless
                    // containers wake up with a 502/504) — retry once
                    try {
                        Thread.sleep(400L)
                    } catch (e: InterruptedException) {
                        return null
                    }
                }
            }
        }
        return null
    }

    private suspend fun loadManifest(): JSONObject? {
        manifest?.let { return it }
        val m = getJson("$base/manifest.json")
        manifest = m
        return m
    }

    private fun typeOf(t: String): MediaType = when (t.lowercase()) {
        "movie" -> MediaType.MOVIE
        "series", "channel", "tv", "anime" -> MediaType.SERIES
        else -> MediaType.UNKNOWN
    }

    private fun catalogsOf(m: JSONObject): List<JSONObject> {
        val arr = m.optJSONArray("catalogs") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    override suspend fun catalogs(): List<CatalogRef> {
        val m = loadManifest() ?: run {
            catalogErrors[config.id] =
                "Could not load manifest from $base/manifest.json — the host may be down, " +
                    "blocking non-browser requests, or behind Cloudflare."
            return emptyList()
        }
        val out = LinkedHashMap<String, CatalogRef>()
        for (c in catalogsOf(m)) {
            val t = typeOf(c.optString("type"))
            if (t == MediaType.UNKNOWN) continue
            val id = c.optString("id")
            val name = c.optString("name").ifBlank { id }
            if (id.isBlank()) continue
            out["$t|$id"] = CatalogRef(config.id, t, id, name)
        }
        if (out.isEmpty()) {
            catalogErrors[config.id] =
                "Manifest loaded but declared no usable catalogs (types supported: movie/series)."
        } else {
            catalogErrors.remove(config.id)
        }
        return out.values.toList()
    }

    override suspend fun getCatalog(ref: CatalogRef, page: Int): List<MediaItem> {
        val extra = if (page > 1) "skip=${(page - 1) * 100}" else null
        val url = resUrl("catalog", ref.type.name.lowercase(), ref.id, extra)
        val items = parseMetas(getJson(url))
        if (items.isEmpty()) {
            catalogErrors[config.id] =
                "Catalog '${ref.name}' returned no items from ${url.take(140)}…"
        } else {
            catalogErrors.remove(config.id)
        }
        return items
    }

    override suspend fun search(query: String, page: Int): List<MediaItem> {
        val out = mutableListOf<MediaItem>()
        for (c in catalogs().distinctBy { it.id }) {
            val extra = "search=${encode(query)}" + if (page > 1) "&skip=${(page - 1) * 100}" else ""
            out += parseMetas(getJson(resUrl("catalog", c.type.name.lowercase(), c.id, extra)))
        }
        return out.distinctBy { it.uniqueId }
    }

    private fun parseMetas(json: JSONObject?): List<MediaItem> {
        json ?: return emptyList()
        val metas = json.optJSONArray("metas") ?: return emptyList()
        val out = mutableListOf<MediaItem>()
        for (i in 0 until metas.length()) {
            val m = metas.optJSONObject(i) ?: continue
            val id = m.optString("id")
            val title = m.optString("name")
            if (id.isBlank() || title.isBlank()) continue
            val type = typeOf(m.optString("type"))
            if (type == MediaType.UNKNOWN) continue
            out += metaToItem(m, id, title, type)
        }
        return out
    }

    private fun metaToItem(m: JSONObject, id: String, title: String, type: MediaType): MediaItem =
        MediaItem(
            providerId = config.id,
            id = id,
            title = title,
            type = type,
            posterUrl = m.optString("poster").ifBlank { null },
            backdropUrl = m.optString("background").ifBlank { m.optString("backdrop").ifBlank { null } },
            year = yearFromRelease(m.optString("releaseInfo")),
            overview = m.optString("description").ifBlank { null },
            genres = stringArray(m, "genres"),
        )

    private fun yearFromRelease(releaseInfo: String): Int? =
        Regex("""(19|20)\d{2}""").find(releaseInfo)?.value?.toIntOrNull()

    private fun stringArray(o: JSONObject, key: String): List<String> =
        o.optJSONArray(key)?.let { a ->
            (0 until a.length()).mapNotNull { a.optString(it).ifBlank { null } }
        } ?: emptyList()

    override suspend fun getMeta(item: MediaItem): MediaItem {
        val url = resUrl("meta", item.type.name.lowercase(), item.id)
        val json = getJson(url) ?: return item
        val m = json.optJSONObject("meta") ?: json.optJSONArray("meta")?.optJSONObject(0) ?: return item
        return item.copy(
            overview = m.optString("description").ifBlank { item.overview },
            genres = stringArray(m, "genres").ifEmpty { item.genres },
            year = yearFromRelease(m.optString("releaseInfo")) ?: item.year,
            backdropUrl = m.optString("background").ifBlank { item.backdropUrl },
            posterUrl = m.optString("poster").ifBlank { item.posterUrl },
        )
    }

    override suspend fun getEpisodes(item: MediaItem): List<Episode>? {
        if (item.type != MediaType.SERIES) return null
        val json = getJson(resUrl("meta", "series", item.id)) ?: return null
        val meta = json.optJSONObject("meta") ?: json.optJSONArray("meta")?.optJSONObject(0) ?: return null
        val videos = meta.optJSONArray("videos") ?: return null
        val out = mutableListOf<Episode>()
        val seen = HashSet<String>()
        for (i in 0 until videos.length()) {
            val v = videos.optJSONObject(i) ?: continue
            val ep = v.optInt("episode", -1)
            val season = v.optInt("season", 1)
            if (ep < 0) continue
            val key = "$season:$ep"
            if (!seen.add(key)) continue // never drop later-season episodes
            out += Episode(
                number = ep,
                id = v.optString("id").ifBlank { "${item.id}:$season:$ep" },
                name = (v.optString("title").ifBlank { "Episode $ep" })
                    .let { if (season > 1) "S$season E$ep · $it" else it },
                image = v.optString("thumbnail").ifBlank { null },
            )
        }
        return out.sortedBy { it.number }
    }

    override suspend fun getStreams(item: MediaItem, episode: Episode?): List<StreamSource> {
        val idPart = episode?.id ?: item.id
        val url = resUrl("stream", item.type.name.lowercase(), idPart)
        val json = getJson(url) ?: return emptyList()
        val arr = json.optJSONArray("streams") ?: return emptyList()
        val out = mutableListOf<StreamSource>()
        for (i in 0 until arr.length()) {
            val st = arr.optJSONObject(i) ?: continue
            val name = st.optString("name").ifBlank {
                st.optString("title").ifBlank { st.optString("description") }
            }
            val infoHash = st.optString("infoHash").ifBlank { null }
            val streamUrl = st.optString("url").ifBlank { null }
            val ytId = st.optString("ytId").ifBlank { null }
            val externalUrl = st.optString("externalUrl").ifBlank { null }
            val subs = parseSubs(st.optJSONArray("subtitles"))
            // Headers some addons require the stream to be fetched with
            // (behaviorHints.proxyHeaders.request, e.g. an auth header).
            val proxyHeaders = st.optJSONObject("behaviorHints")
                ?.optJSONObject("proxyHeaders")
                ?.optJSONObject("request")
                ?.let { h ->
                    val map = LinkedHashMap<String, String>()
                    val keys = h.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        map[k] = h.optString(k)
                    }
                    map
                } ?: emptyMap()

            when {
                infoHash != null -> out += StreamSource(
                    name = name.ifBlank { "Torrent" },
                    url = "",
                    subtitles = subs,
                    isTorrent = true,
                    infoHash = infoHash,
                    fileIdx = if (st.has("fileIdx") && !st.isNull("fileIdx")) st.optInt("fileIdx") else null,
                    trackers = stringArray(st, "sources"),
                )
                ytId != null -> out += StreamSource(
                    name = name.ifBlank { "YouTube" },
                    url = "",
                    subtitles = subs,
                    ytId = ytId,
                )
                externalUrl != null -> out += StreamSource(
                    name = name.ifBlank { "External" },
                    url = externalUrl,
                    subtitles = subs,
                    externalUrl = true,
                )
                streamUrl != null -> out += StreamSource(
                    name = name.ifBlank { if (streamUrl.contains(".m3u8", true)) "HLS" else "Direct" },
                    url = streamUrl,
                    headers = proxyHeaders,
                    subtitles = subs,
                    isM3u8 = streamUrl.contains(".m3u8", true) || streamUrl.contains("master.txt", true),
                    isMpd = streamUrl.contains(".mpd", true),
                )
            }
        }
        return out
    }

    private fun parseSubs(arr: JSONArray?): List<SubtitleSource> {
        arr ?: return emptyList()
        val out = mutableListOf<SubtitleSource>()
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val u = s.optString("url")
            if (u.isBlank()) continue
            out += SubtitleSource(s.optString("lang").ifBlank { "Subtitle" }, u)
        }
        return out
    }
}
