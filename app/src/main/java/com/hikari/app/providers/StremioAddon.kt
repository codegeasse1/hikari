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
 *
 * Type handling: the protocol's {type} URL segment is the addon's OWN literal
 * type string ("movie", "series", but also "tv", "anime", "channel", or any
 * custom type), NOT a normalized name. We keep the raw string end-to-end and
 * only normalize to MediaType for the UI, treating anything unknown as SERIES
 * so catalogs are never dropped as "no usable catalogs".
 */
class StremioAddon(override val config: ProviderConfig) : ContentProvider {

    companion object {
        /** Per-provider reason why its home catalog failed (empty = it works). */
        val catalogErrors = ConcurrentHashMap<String, String>()

        /** Set when a manifest loaded fine but declares NO catalogs — a normal
         *  stream-only addon (Torrentio, Comet, Novastream…). Not an error; it
         *  just contributes playback sources, never home rows. */
        val streamOnlyAddons = ConcurrentHashMap<String, Boolean>()

        /** Per-provider reason why source lookup came back empty. Displayed in
         *  the playback sheet so "no playable sources found" is explainable. */
        val streamErrors = ConcurrentHashMap<String, String>()
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

    /** Normalizes any addon type string to a MediaType. The Stremio client
     *  accepts arbitrary type strings; we map obvious movies to MOVIE and
     *  EVERYTHING else to SERIES so no catalog is ever dropped. */
    private fun typeOf(t: String): MediaType = when (t.lowercase()) {
        "movie", "movies", "film", "feature-film", "feature" -> MediaType.MOVIE
        else -> MediaType.SERIES
    }

    private fun catalogsOf(m: JSONObject): List<JSONObject> {
        val arr = m.optJSONArray("catalogs") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    /** The literal type segment to use in URLs — the addon's own string. */
    private fun typeSegment(rawType: String, type: MediaType): String =
        rawType.ifBlank { type.name.lowercase() }

    /** Whether the manifest declares the given resource. Addons that only list
     *  catalog/meta are metadata-only and will never produce streams. */
    private fun hasResource(m: JSONObject, name: String): Boolean {
        val arr = m.optJSONArray("resources") ?: return true // unknown → assume yes
        for (i in 0 until arr.length()) {
            val r = arr.opt(i)
            val n = when (r) {
                is String -> r
                is JSONObject -> r.optString("name")
                else -> null
            }
            if (n != null && n.equals(name, ignoreCase = true)) return true
        }
        return false
    }

    override suspend fun catalogs(): List<CatalogRef> {
        val m = loadManifest() ?: run {
            catalogErrors[config.id] =
                "Could not load manifest from $base/manifest.json — the host may be down, " +
                    "blocking non-browser requests, or behind Cloudflare."
            streamOnlyAddons.remove(config.id)
            return emptyList()
        }
        val out = LinkedHashMap<String, CatalogRef>()
        for (c in catalogsOf(m)) {
            val t = typeOf(c.optString("type"))
            val id = c.optString("id")
            val name = c.optString("name").ifBlank { id }
            if (id.isBlank()) continue
            val raw = c.optString("type").lowercase()
            out["$t|$id"] = CatalogRef(config.id, t, id, name, raw)
        }
        if (out.isEmpty()) {
            // Zero catalogs is NOT an error — stream-only addons (Torrentio,
            // Comet, Novastream…) are valid and common. They simply add
            // playback sources to titles opened from other addons.
            catalogErrors.remove(config.id)
            streamOnlyAddons[config.id] = true
        } else {
            catalogErrors.remove(config.id)
            streamOnlyAddons.remove(config.id)
        }
        return out.values.toList()
    }

    override suspend fun getCatalog(ref: CatalogRef, page: Int): List<MediaItem> {
        val extra = if (page > 1) "skip=${(page - 1) * 100}" else null
        val url = resUrl("catalog", typeSegment(ref.rawType, ref.type), ref.id, extra)
        val items = parseMetas(getJson(url), ref.rawType)
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
            out += parseMetas(
                getJson(resUrl("catalog", typeSegment(c.rawType, c.type), c.id, extra)),
                c.rawType,
            )
        }
        return out.distinctBy { it.uniqueId }
    }

    private fun parseMetas(json: JSONObject?, catalogRawType: String = ""): List<MediaItem> {
        json ?: return emptyList()
        val metas = json.optJSONArray("metas") ?: return emptyList()
        val out = mutableListOf<MediaItem>()
        for (i in 0 until metas.length()) {
            val m = metas.optJSONObject(i) ?: continue
            val id = m.optString("id")
            val title = m.optString("name")
            if (id.isBlank() || title.isBlank()) continue
            // Items usually carry their own type; fall back to the catalog's.
            val raw = m.optString("type").ifBlank { catalogRawType }
            val type = typeOf(raw)
            out += metaToItem(m, id, title, type, raw)
        }
        return out
    }

    private fun metaToItem(
        m: JSONObject,
        id: String,
        title: String,
        type: MediaType,
        rawType: String,
    ): MediaItem = MediaItem(
        providerId = config.id,
        id = id,
        title = title,
        type = type,
        posterUrl = m.optString("poster").ifBlank { null },
        backdropUrl = m.optString("background").ifBlank { m.optString("backdrop").ifBlank { null } },
        year = yearFromRelease(m.optString("releaseInfo")),
        overview = m.optString("description").ifBlank { null },
        genres = stringArray(m, "genres"),
        rawType = rawType,
    )

    private fun yearFromRelease(releaseInfo: String): Int? =
        Regex("""(19|20)\d{2}""").find(releaseInfo)?.value?.toIntOrNull()

    private fun stringArray(o: JSONObject, key: String): List<String> =
        o.optJSONArray(key)?.let { a ->
            (0 until a.length()).mapNotNull { a.optString(it).ifBlank { null } }
        } ?: emptyList()

    override suspend fun getMeta(item: MediaItem): MediaItem {
        val url = resUrl("meta", typeSegment(item.rawType, item.type), item.id)
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
        val json = getJson(resUrl("meta", typeSegment(item.rawType, item.type), item.id)) ?: return null
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

    /** True if this addon claims to know this video id (via manifest
     *  idPrefixes). The real client only queries addons whose prefix matches,
     *  so e.g. Torrentio isn't asked about a non-`tt` id. Addons without
     *  idPrefixes (or not yet loaded) accept everything. */
    fun acceptsId(id: String): Boolean {
        val m = manifest ?: return true
        val arr = m.optJSONArray("idPrefixes") ?: return true
        if (arr.length() == 0) return true
        for (i in 0 until arr.length()) {
            val p = arr.optString(i)
            if (p.isNotEmpty() && id.startsWith(p)) return true
        }
        return false
    }

    override suspend fun getStreams(item: MediaItem, episode: Episode?): List<StreamSource> {
        val m = loadManifest()
        val typeRaw = typeSegment(item.rawType, item.type)
        val idPart = episode?.id ?: item.id

        // Metadata-only addons (Cinemeta/Streaming-Catalogs style: catalogs +
        // meta but no stream resource) never answer /stream — exactly like the
        // real Stremio client, which only asks addons that declare the stream
        // resource. Sources for these titles come from the other installed
        // playback addons (Torrentio, Comet, Novastream…).
        if (m != null && !hasResource(m, "stream")) {
            streamErrors[config.id] =
                "This addon provides no streams (catalog/metadata only) — " +
                    "sources are fetched from your other playback addons."
            return emptyList()
        }

        // Try the exact URL first, then progressively looser variants so
        // addons with stricter matching still resolve: base id without the
        // :season:episode suffix, then common type segments.
        val attempts = linkedSetOf<String>()
        attempts += resUrl("stream", typeRaw, idPart)
        val baseId = stripVideoSuffix(idPart)
        if (baseId != idPart) attempts += resUrl("stream", typeRaw, baseId)
        for (alt in listOf("movie", "series", "tv", "anime", "channel")) {
            if (alt != typeRaw) attempts += resUrl("stream", alt, idPart)
        }

        val reasons = mutableListOf<String>()
        for (u in attempts) {
            val json = getJson(u) ?: run {
                reasons += "no response from ${u.take(120)}"
                continue
            }
            val streams = parseStreams(json)
            if (streams.isNotEmpty()) {
                streamErrors.remove(config.id)
                return streams
            }
            val n = json.optJSONArray("streams")?.length() ?: -1
            reasons += if (n >= 0) "addon returned $n empty stream rows from ${u.take(120)}"
            else "no 'streams' field in ${u.take(120)}"
        }

        streamErrors[config.id] = reasons.take(2).joinToString(" • ")
            .ifBlank { "Addon returned no playable streams." }
        return emptyList()
    }

    /** Strips a trailing season:episode (or season-episode) suffix from a video
     *  id so we can also try the bare movie/base id. */
    private fun stripVideoSuffix(id: String): String = id
        .replace(Regex(":\\d+:\\d+$"), "")
        .replace(Regex("-\\d+-\\d+$"), "")
        .replace(Regex(":\\d+$"), "")

    private fun parseStreams(json: JSONObject): List<StreamSource> {
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
