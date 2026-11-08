package com.hikari.app.skystream

import com.hikari.app.HikariApp
import com.hikari.app.data.CatalogRef
import com.hikari.app.data.Episode
import com.hikari.app.data.MediaItem
import com.hikari.app.data.MediaType
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.StreamSource
import com.hikari.app.data.SubtitleSource
import com.hikari.app.net.Http
import com.hikari.app.providers.ContentProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class SkyStreamProvider(override val config: ProviderConfig) : ContentProvider {

    companion object {
        /** Per-provider last failure (shown on the Detail screen). */
        val streamErrors = ConcurrentHashMap<String, String>()

        /** Per-provider last lookup outcome — one short line per extension,
         *  shown in the sources sheet (same contract as NuvioScraper). */
        val lastOutcome = ConcurrentHashMap<String, String>()

        /** Per-provider home failure (shown on the Home empty state). */
        val catalogErrors = ConcurrentHashMap<String, String>()

        private const val MAX_ITEMS_PER_ROW = 80
        private const val MAX_EPISODES = 800
        /** Per-call stream cap: the reference app caps one loadStreams answer
         *  at 200 (see _kMaxStreamsPerCall) — cutting at 60 dropped real
         *  mirrors from big meta providers. */
        private const val MAX_STREAMS = 200
        private const val HOME_TTL_MS = 8 * 60 * 1000L

        /** What Home says when this extension's site would not load for us.
         *  Deliberately neutral: Hikari never names the wall (see the project
         *  rule that a blocked extension is skipped silently rather than
         *  reported), it just points at the extension's own site. */
        private const val CF_REASON =
            "This extension's site wouldn't load — open it in the WebView from the globe button, then retry."
    }

    /** `sky|<packageName>[|<feedId>]` → the plugin's package name (also its
     *  manifest id and its directory — see SkyStreamPluginManager.packageOf).
     *  [subId] is a bundle's feed id, injected as `manifest.providerId` so a
     *  multi-feed plugin answers instead of NOT_FOUND. */
    private val packageName: String get() = SkyStreamPluginManager.packageOf(config.id)
    private val subId: String? get() = SkyStreamPluginManager.subIdOf(config.id)

    private val scriptFile: File get() {
        val stored = File(config.url)
        if (stored.exists()) return stored
        val canonical = SkyStreamPluginManager.scriptFile(HikariApp.instance, packageName)
        return if (canonical.exists()) canonical else stored
    }

    @Volatile
    private var lastFailure: String? = null

    /** This extension's own site host (`domains[0]`, else `baseUrl` from its
     *  plugin.json), read once. Cloudflare is only ever blamed on this host. */
    @Volatile
    private var siteHostRead = false

    @Volatile
    private var siteHostValue: String? = null

    private fun siteHost(): String? {
        if (!siteHostRead) {
            siteHostValue = runCatching { SkyStreamPluginManager.siteHostOf(scriptFile) }.getOrNull()
            siteHostRead = true
        }
        return siteHostValue
    }

    /** One plugin call at a time per provider: `getHome` is expensive (~1-3s of
     *  a fresh engine + the site's home page) and the dashboard asks for the
     *  same rows from several places at once. */
    private val lock = Mutex()

    private class Home(val at: Long, val categories: List<Pair<String, JSONArray>>)

    @Volatile
    private var homeCache: Home? = null

    /** `load` results, keyed by the item token they were loaded for. */
    private val loadedMeta = ConcurrentHashMap<String, MediaItem>()

    /** Episodes per item token (also the source of the instant-play streams). */
    private val loadedEpisodes = ConcurrentHashMap<String, List<Episode>>()

    /** Instant-load streams, keyed by the token `loadStreams` would receive. */
    private val instantStreams = ConcurrentHashMap<String, List<StreamSource>>()

    private suspend fun invoke(fn: String, args: List<String>): JSONObject? = withContext(Dispatchers.IO) {
        val file = scriptFile
        if (!file.exists()) {
            lastFailure = "extension file missing — reinstall this extension"
            return@withContext null
        }
        val argsJson = JSONArray().apply { args.forEach { put(it) } }.toString()
        val extra = subId?.let { sid ->
            runCatching { JSONObject().put("providerId", sid).toString() }.getOrNull()
        }
        val payload = SkyStreamRuntime.invoke(
            HikariApp.instance,
            packageName,
            file,
            fn,
            argsJson,
            extra,
        )
        val parsed = runCatching { JSONObject(payload) }.getOrNull()
        lastFailure = if (parsed == null) {
            "extension did not answer ${fn}() — check Logs"
        } else null
        parsed
    }

    private fun fail(msg: String): List<StreamSource> {
        streamErrors[config.id] = msg
        lastOutcome[config.id] = msg.take(80)
        return emptyList()
    }

    // ---- Catalogue ----

    override suspend fun catalogs(): List<CatalogRef> = withContext(Dispatchers.IO) {
        val home = home() ?: return@withContext emptyList()
        home.categories.map { (name, _) ->
            CatalogRef(config.id, MediaType.UNKNOWN, "cat:$name", name, "skystream")
        }
    }

    override suspend fun getCatalog(ref: CatalogRef, page: Int): List<MediaItem> =
        withContext(Dispatchers.IO) {
            if (page > 1) return@withContext emptyList()
            val home = home() ?: return@withContext emptyList()
            val name = ref.id.removePrefix("cat:")
            val arr = home.categories.firstOrNull { it.first == name }?.second
                ?: return@withContext emptyList()
            (0 until minOf(arr.length(), MAX_ITEMS_PER_ROW))
                .mapNotNull { i -> toItem(arr.optJSONObject(i)) }
        }

    /** The plugin's `getHome` payload, cached briefly so the dashboard's rows
     *  and each row's items come from ONE plugin call. */
    private suspend fun home(): Home? {
        val cached = homeCache
        if (cached != null && System.currentTimeMillis() - cached.at < HOME_TTL_MS) return cached
        return lock.withLock {
            val again = homeCache
            if (again != null && System.currentTimeMillis() - again.at < HOME_TTL_MS) return@withLock again
            val res = invoke("getHome", emptyList())
            if (res == null) {
                catalogErrors[config.id] = "Home failed: " +
                    (lastFailure ?: "this extension did not answer (it may have crashed — check Logs).")
                return@withLock null
            }
            if (!res.optBoolean("ok", false)) {
                catalogErrors[config.id] = catalogReason(res.optString("error"), empty = true)
                return@withLock null
            }
            val data = res.opt("data")
            val cats = mutableListOf<Pair<String, JSONArray>>()
            if (data is JSONObject) {
                val keys = data.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = data.opt(k)
                    if (v is JSONArray && v.length() > 0) cats.add(k to v)
                }
            } else if (data is JSONArray) {
                cats.add("Home" to data)
            }
            if (cats.isEmpty()) {
                catalogErrors[config.id] = catalogReason(null, empty = true)
                return@withLock null
            }
            catalogErrors.remove(config.id)
            Home(System.currentTimeMillis(), cats).also { homeCache = it }
        }
    }

    private fun catalogReason(err: String?, empty: Boolean): String {
        val e = err.orEmpty()
        if (com.hikari.app.net.CloudflareVerifier.isVerificationMessage(e)) return CF_REASON
        val host = siteHost()
        if (host != null && com.hikari.app.net.CloudflareVerifier.isBlockedHost(host)) return CF_REASON
        if (e.isNotBlank()) return "Home failed: $e"
        return if (empty) "This extension returned an empty catalog (its site may have changed)."
        else "Home failed."
    }

    // ---- Search ----

    override suspend fun search(query: String, page: Int): List<MediaItem> =
        withContext(Dispatchers.IO) {
            // 1-based pages — see [getCatalog].
            if (query.isBlank() || page > 1) return@withContext emptyList()
            val res = invoke("search", listOf(query))
            if (res == null || !res.optBoolean("ok", false)) {
                // A search that never ran must never be reported as "this repo
                // has no such title" — say what actually happened.
                val why = res?.optString("error")?.takeIf { it.isNotBlank() }
                    ?: lastFailure
                    ?: "the extension returned an unreadable result"
                catalogErrors[config.id] = "Search failed: $why"
                lastOutcome[config.id] = "✗ $why".take(80)
                return@withContext emptyList()
            }
            lastOutcome.remove(config.id)
            val data = res.opt("data")
            val arr = when (data) {
                is JSONArray -> data
                is JSONObject -> data.optJSONArray("results") ?: data.optJSONArray("items")
                else -> null
            } ?: return@withContext emptyList()
            (0 until minOf(arr.length(), MAX_ITEMS_PER_ROW))
                .mapNotNull { i -> toItem(arr.optJSONObject(i)) }
        }

    private fun toItem(o: JSONObject?): MediaItem? {
        o ?: return null
        val url = o.optString("url")
        val title = o.optString("title").ifBlank { o.optString("name") }
        if (url.isBlank() || title.isBlank()) return null
        val rawType = o.optString("type").ifBlank { "movie" }
        return MediaItem(
            providerId = config.id,
            id = url,
            title = title,
            type = mediaTypeOf(rawType),
            posterUrl = o.optString("posterUrl").ifBlank { o.optString("poster") }.ifBlank { null },
            year = o.optInt("year").takeIf { it > 1800 },
            overview = o.optString("description").ifBlank { o.optString("overview") }.ifBlank { null },
            backdropUrl = o.optString("bannerUrl").ifBlank { o.optString("backdropUrl") }
                .ifBlank { o.optString("logoUrl") }.ifBlank { null },
            rawType = rawType,
        )
    }

    private fun mediaTypeOf(raw: String): MediaType = when (raw.lowercase()) {
        "series", "anime", "tv", "tvseries", "tvshow" -> MediaType.SERIES
        "movie", "short", "film" -> MediaType.MOVIE
        else -> MediaType.UNKNOWN
    }

    // ---- Details + episodes ----

    override suspend fun getMeta(item: MediaItem): MediaItem = withContext(Dispatchers.IO) {
        if (item.id.isBlank()) return@withContext item
        loadedMeta[item.id]?.let { return@withContext it }
        val res = invoke("load", listOf(item.id)) ?: return@withContext item
        if (!res.optBoolean("ok", false)) {
            val err = res.optString("error")
            if (err.isNotBlank()) streamErrors[config.id] = "✗ $err"
            com.hikari.app.data.Logs.log(
                "Provider",
                "${config.name} [SkyStream]: details failed for \"${item.title}\" — " +
                    (err.ifBlank { lastFailure ?: "no reason given" }),
            )
            return@withContext item
        }
        val d = res.opt("data") as? JSONObject ?: return@withContext item
        val episodes = toEpisodes(item.id, d.optJSONArray("episodes"))
        com.hikari.app.data.Logs.log(
            "Provider",
            "${config.name} [SkyStream]: details for \"${item.title}\" — " +
                "${episodes.size} episode(s)" +
                if (episodes.isEmpty()) " (episode picks fall back to the title link)" else "",
        )
        if (episodes.isNotEmpty()) {
            loadedEpisodes[item.id] = episodes
            seedInstant(d, item.id)
        }
        val rawType = d.optString("type").ifBlank { item.rawType }
        val type = if (episodes.isNotEmpty()) MediaType.SERIES else mediaTypeOf(rawType)
            .takeIf { it != MediaType.UNKNOWN } ?: item.type
        val out = MediaItem(
            providerId = item.providerId,
            id = item.id,
            title = d.optString("title").ifBlank { item.title },
            type = type,
            posterUrl = d.optString("posterUrl").ifBlank { item.posterUrl },
            year = d.optInt("year").takeIf { it > 1800 } ?: item.year,
            overview = d.optString("description").ifBlank { item.overview.orEmpty() }
                .ifBlank { null },
            genres = item.genres,
            backdropUrl = d.optString("bannerUrl").ifBlank { item.backdropUrl },
            rawType = rawType,
        )
        loadedMeta[item.id] = out
        out
    }

    override suspend fun getEpisodes(item: MediaItem): List<Episode>? = withContext(Dispatchers.IO) {
        // Movies never show Loading episodes.
        if (item.type == MediaType.MOVIE) return@withContext null
        loadedEpisodes[item.id]?.takeIf { it.isNotEmpty() }?.let { return@withContext it }
        getMeta(item)
        loadedEpisodes[item.id]?.takeIf { it.isNotEmpty() }
    }

    private fun toEpisodes(token: String, arr: JSONArray?): List<Episode> {
        arr ?: return emptyList()
        val out = mutableListOf<Episode>()
        for (i in 0 until minOf(arr.length(), MAX_EPISODES)) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("url")
            if (url.isBlank()) continue
            val season = o.optInt("season", 1).takeIf { it > 0 } ?: 1
            val number = o.optInt("episode").takeIf { it > 0 }
                ?: o.optInt("number").takeIf { it > 0 }
                ?: (i + 1)
            val name = o.optString("name").ifBlank { o.optString("title") }.ifBlank { null }
            out += Episode(
                number = number,
                id = url,
                name = name,
                image = o.optString("image").ifBlank { o.optString("posterUrl") }.ifBlank { null },
                season = season,
            )
            val streams = mapStreams(o.optJSONArray("streams"))
            if (streams.isNotEmpty()) instantStreams[url] = streams
        }
        if (out.isEmpty()) instantStreams.remove(token)
        return out
    }

    /** A movie `load` result may carry a top-level `streams` array too — treat
     *  it the same way (instant play on the title's own token). */
    private fun seedInstant(d: JSONObject, token: String) {
        val streams = mapStreams(d.optJSONArray("streams"))
        if (streams.isNotEmpty()) instantStreams[token] = streams
    }

    private fun resolveToken(item: MediaItem, episode: Episode?): String {
        val eps = loadedEpisodes[item.id].orEmpty()
        val wanted = episode?.id?.takeIf { it.isNotBlank() }
        if (wanted != null && (eps.isEmpty() || eps.any { it.id == wanted })) return wanted
        if (episode != null && eps.isNotEmpty()) {
            eps.firstOrNull { it.season == episode.season && it.number == episode.number }?.let {
                return it.id
            }
            eps.firstOrNull { it.number == episode.number }?.let {
                com.hikari.app.data.Logs.log(
                    "Provider",
                    "${config.name} [SkyStream]: S${episode.season}E${episode.number} " +
                        "is S${it.season}E${it.number} on this site — resolving there",
                )
                return it.id
            }
        }
        return wanted ?: item.id
    }

    // ---- Streams ----

    override suspend fun getStreams(item: MediaItem, episode: Episode?): List<StreamSource> =
        withContext(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            if (!scriptFile.exists()) {
                return@withContext fail("✗ Extension file missing — reinstall this extension.")
            }
            if (loadedEpisodes[item.id] == null && loadedMeta[item.id] == null) {
                getMeta(item)
            }
            val token = resolveToken(item, episode)
            instantStreams[token]?.takeIf { it.isNotEmpty() }?.let { instant ->
                streamErrors.remove(config.id)
                lastOutcome[config.id] = "✓ ${instant.size} source${if (instant.size == 1) "" else "s"} (instant)"
                com.hikari.app.net.StreamProbe.warmAsync(instant)
                return@withContext instant
            }
            val direct = streamsForToken(token)
            if (direct.isNotEmpty()) {
                streamErrors.remove(config.id)
                lastOutcome[config.id] = "✓ ${direct.size} source${if (direct.size == 1) "" else "s"} in " +
                    "${(System.currentTimeMillis() - startedAt) / 1000}s"
                instantStreams[token] = direct
                val distinct = direct.distinctBy { it.url }
                com.hikari.app.net.StreamProbe.warmAsync(distinct)
                return@withContext distinct
            }
            if (token == item.id) {
                val via = searchFallback(item, episode)
                if (via.isNotEmpty()) {
                    streamErrors.remove(config.id)
                    lastOutcome[config.id] = "✓ ${via.size} source${if (via.size == 1) "" else "s"} in " +
                        "${(System.currentTimeMillis() - startedAt) / 1000}s (re-matched)"
                    val distinct = via.distinctBy { it.url }
                    com.hikari.app.net.StreamProbe.warmAsync(distinct)
                    return@withContext distinct
                }
            }
            return@withContext fail(
                // The plugin's own last words when it gave any (a refused
                // episode, a dead call) — otherwise the generic verdict.
                streamErrors[config.id]?.takeIf {
                    it.startsWith("✗ ") && it != "✗ no sources found"
                } ?: "✗ No playable sources for this title."
            )
        }

    /** One `loadStreams` round-trip with no bookkeeping (see [getStreams]). */
    private suspend fun streamsForToken(token: String): List<StreamSource> {
        val res = invoke("loadStreams", listOf(token))
        if (res == null) {
            streamErrors[config.id] = "✗ Extension did not answer — check Logs."
            return emptyList()
        }
        if (!res.optBoolean("ok", false)) {
            val err = res.optString("error").ifBlank { "no sources found" }
            streamErrors[config.id] = "✗ $err"
            return emptyList()
        }
        val data = res.opt("data")
        val arr = when (data) {
            is JSONArray -> data
            is JSONObject -> data.optJSONArray("streams") ?: data.optJSONArray("sources")
            else -> null
        } ?: return emptyList()
        return mapStreams(arr)
    }

    private suspend fun searchFallback(item: MediaItem, episode: Episode?): List<StreamSource> {
        val queries = LinkedHashSet<String>()
        if (item.title.isNotBlank()) queries.add(item.title)
        val base = com.hikari.app.data.sequelBase(com.hikari.app.data.normalizeTitle(item.title))
        if (base.isNotBlank()) queries.add(base)
        for (q in queries) {
            val hits = search(q, 1)
            val strict = com.hikari.app.data.normalizeTitle(q)
            val found = hits.firstOrNull {
                com.hikari.app.data.normalizeTitle(it.title) == strict
            } ?: continue
            getMeta(found)
            val eps = loadedEpisodes[found.id].orEmpty()
            val ep = if (episode == null) {
                eps.firstOrNull()
            } else {
                eps.firstOrNull { it.season == episode.season && it.number == episode.number }
                    ?: eps.firstOrNull { it.number == episode.number }
            }
            val token = ep?.id?.takeIf { it.isNotBlank() } ?: found.id
            instantStreams[token]?.takeIf { it.isNotEmpty() }?.let { return it }
            val out = streamsForToken(token)
            if (out.isNotEmpty()) {
                com.hikari.app.data.Logs.log(
                    "Provider",
                    "${config.name} [SkyStream]: re-matched \"${item.title}\" " +
                        "to \"${found.title}\" — ${out.size} source(s)",
                )
                instantStreams[token] = out
                return out
            }
        }
        return emptyList()
    }

    private fun mapStreams(arr: JSONArray?): List<StreamSource> {
        arr ?: return emptyList()
        val out = mutableListOf<StreamSource>()
        for (i in 0 until minOf(arr.length(), MAX_STREAMS)) {
            val o = arr.optJSONObject(i) ?: continue
            toStreamSource(o)?.let { out.add(it) }
        }
        return out
    }

    private fun toStreamSource(o: JSONObject): StreamSource? {
        var url = o.optString("url")
        if (url.isBlank()) return null
        val headers = LinkedHashMap<String, String>()

        url = decodeMagic(url, headers)

        val source = o.optString("source").ifBlank { o.optString("name") }
        val quality = o.optString("quality")
        val size = o.optString("size")
        val base = source.ifBlank { config.name }
        val displayName = if (quality.isNotBlank() && !base.contains(quality, true)) "$base $quality" else base

        val providerHeaders = o.optJSONObject("headers")
        providerHeaders?.keys()?.forEach { k ->
            val v = providerHeaders.optString(k).filter { it.code < 128 }
            if (v.isNotBlank()) headers.putIfAbsent(k, v)
        }
        headers.putIfAbsent("User-Agent", Http.UA)

        val subs = mutableListOf<SubtitleSource>()
        val subArr = o.optJSONArray("subtitles")
        if (subArr != null) {
            for (i in 0 until subArr.length()) {
                val st = subArr.optJSONObject(i) ?: continue
                val su = st.optString("url")
                if (su.isBlank()) continue
                subs.add(
                    SubtitleSource(
                        st.optString("lang").ifBlank { st.optString("language") }
                            .ifBlank { st.optString("label") }.ifBlank { "Sub" },
                        su,
                    )
                )
            }
        }

        val isTorrent = url.startsWith("magnet:", true) || url.startsWith("torrent:", true)
        val isM3u8 = o.optBoolean("isM3U8", false) ||
            url.startsWith("data:application/vnd.apple.mpegurl") || url.contains(".m3u8", true)
        val isMpd = url.contains(".mpd", true)
        val kid = o.optString("drmKid").takeIf { it.isNotBlank() }
        val key = o.optString("drmKey").takeIf { it.isNotBlank() }
        val license = o.optString("licenseUrl").takeIf { it.isNotBlank() }
        val drm = if (kid == null && key == null && license == null) null
        else com.hikari.app.data.DrmSpec(kid = kid, key = key, licenseUrl = license)
        return StreamSource(
            name = displayName,
            url = if (isTorrent || url.startsWith("data:")) url else Http.normalizeDriveUrl(url),
            headers = headers,
            subtitles = subs,
            isTorrent = isTorrent,
            isM3u8 = isM3u8,
            isMpd = isMpd,
            details = size,
            drm = drm,
        )
    }

    private fun decodeMagic(raw: String, headers: MutableMap<String, String>): String {
        if (raw.startsWith("magic_m3u8:")) {
            return "data:application/vnd.apple.mpegurl;base64," + raw.removePrefix("magic_m3u8:").trim()
        }
        if (raw.startsWith("MAGIC_PROXY_v2")) {
            val json = base64Json(raw.substring("MAGIC_PROXY_v2".length)) ?: return raw
            json.optJSONObject("headers")?.keys()?.forEach { k ->
                val v = json.optJSONObject("headers")?.optString(k) ?: return@forEach
                if (v.isNotBlank()) headers.putIfAbsent(k, v)
            }
            return json.optString("url").takeIf { it.startsWith("http") } ?: raw
        }
        if (raw.startsWith("MAGIC_PROXY_v1") || raw.startsWith("MAGIC_PROXY:")) {
            val isV1 = raw.startsWith("MAGIC_PROXY_v1")
            val b64 = raw.substring(if (isV1) "MAGIC_PROXY_v1".length else "MAGIC_PROXY:".length)
            val real = base64Text(b64)
            return real?.takeIf { it.startsWith("http") } ?: raw
        }
        return raw
    }

    private fun base64Text(b64: String): String? = runCatching {
        val clean = b64.trim()
        val bytes = runCatching {
            android.util.Base64.decode(clean, android.util.Base64.DEFAULT)
        }.getOrElse {
            android.util.Base64.decode(clean, android.util.Base64.URL_SAFE)
        }
        String(bytes, Charsets.UTF_8)
    }.getOrNull()

    private fun base64Json(b64: String): JSONObject? =
        base64Text(b64)?.let { runCatching { JSONObject(it) }.getOrNull() }
}
