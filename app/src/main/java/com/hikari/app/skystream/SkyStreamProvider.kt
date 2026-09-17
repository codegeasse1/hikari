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

/**
 * Adapts a SkyStream plugin (`.sky` → plugin.js) to Hikari's ContentProvider
 * contract. Unlike nuvio providers, a SkyStream plugin carries its OWN
 * catalogue, search, details and streams — it is a whole site — so this class
 * maps one plugin onto every part of the contract:
 *
 *   catalogs()             <- getHome   { "<row>": [item, …] }
 *   getCatalog()           <- that row's items
 *   search(query)          <- search(query)
 *   getMeta()/getEpisodes() <- load(item.url)   (details + episodes[])
 *   getStreams()           <- loadStreams(episode.url or item.url)
 *
 * The item's `url` is an OPAQUE TOKEN the plugin invented (akashdh11's
 * providers encode `<base>|<type>|<id>`; others use a plain page URL or a
 * `tmdb:` id), so it is carried through Hikari verbatim as [MediaItem.id] and
 * handed straight back to the plugin — never parsed, never re-encoded.
 *
 * "Instant Load": a plugin may attach `streams[]` to each episode of the `load`
 * result. Those are mapped once in [getEpisodes] and replayed by [getStreams]
 * without touching the engine again, so tapping an episode the site already
 * resolved starts playing immediately instead of paying for a second scrape.
 */
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
        private const val MAX_STREAMS = 60
        private const val HOME_TTL_MS = 8 * 60 * 1000L

        /** What Home says when this extension's site really is challenged. */
        private const val CF_REASON =
            "Cloudflare wants a verification on this site — tap the globe button at the top, then retry."
    }

    /** `sky|<packageName>` → the plugin's package name (also its manifest id). */
    private val packageName: String get() = config.id.removePrefix("sky|")

    /**
     * The plugin's script. `config.url` holds the absolute path captured at
     * install time; if that no longer resolves (a restored backup, a moved data
     * dir, an app the OS relocated) fall back to the canonical location the
     * manager writes to — `filesDir/skystream/plugins/<packageName>/plugin.js`.
     * Reporting the extension as broken when its file is right there under the
     * name it was installed with is never the right answer.
     */
    private val scriptFile: File get() {
        val stored = File(config.url)
        if (stored.exists()) return stored
        val canonical = SkyStreamPluginManager.scriptFile(HikariApp.instance, packageName)
        return if (canonical.exists()) canonical else stored
    }

    /**
     * Why the last [invoke] produced nothing (file missing, unreadable payload).
     * SkyStream's plugin API is one call per function with no pagination, so
     * the ONLY honest way Home and search can tell "this extension is broken"
     * apart from "this extension carries no such title" is to carry the real
     * reason out of here instead of returning a bare null.
     */
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
        val payload = SkyStreamRuntime.invoke(
            HikariApp.instance,
            packageName,
            file,
            fn,
            argsJson,
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
            // Page numbers are 1-BASED across Hikari (see ContentProvider and
            // every other provider: `page.coerceAtLeast(1)`, `skip=(page-1)*100`).
            // Treating page 1 as "not the first page" here made every SkyStream
            // row come back empty the instant it was asked for, which is why
            // these extensions showed "Couldn't load …" on Home and answered
            // every cross-search in ~5ms with "no matching title". A SkyStream
            // plugin returns its whole row in ONE call, so only page 1 has items.
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

    /**
     * Why this extension produced no catalog, in Hikari's own words.
     *
     * Cloudflare is named ONLY when it is genuinely the reason: this
     * extension's own site is a host we could not pass a challenge on, or the
     * plugin's own error text says it hit a verification wall. Otherwise the
     * extension's real error is surfaced verbatim — a site that is down, a
     * plugin that crashed and an extension that really has no catalog are three
     * different stories, and telling the user the wrong one sends them to a
     * WebView that cannot help. (The old version asked
     * `CloudflareVerifier.blockedHost()` — the most recently challenged host in
     * the whole app — so one blocked site anywhere made every extension's
     * failure read as a Cloudflare wall.)
     */
    private fun catalogReason(err: String?, empty: Boolean): String {
        val e = err.orEmpty()
        // Cloudflare is named ONLY when this extension's OWN site is the host
        // that was challenged (or its own error text says so). The old check
        // asked `blockedHost()` — the most recently challenged host in the
        // WHOLE app — so one blocked site anywhere made every extension's empty
        // catalog read as "Cloudflare wants a verification", extensions that
        // were never behind a challenge included.
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
            // It answered: any note left by an earlier failure of this pass is
            // stale, and carrying it forward is what made a healthy repo read
            // back as "couldn't be searched".
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
            return@withContext item
        }
        val d = res.opt("data") as? JSONObject ?: return@withContext item
        val episodes = toEpisodes(item.id, d.optJSONArray("episodes"))
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

    // ---- Streams ----

    override suspend fun getStreams(item: MediaItem, episode: Episode?): List<StreamSource> =
        withContext(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            if (!scriptFile.exists()) {
                return@withContext fail("✗ Extension file missing — reinstall this extension.")
            }
            val token = episode?.id?.takeIf { it.isNotBlank() } ?: item.id
            instantStreams[token]?.takeIf { it.isNotEmpty() }?.let { instant ->
                streamErrors.remove(config.id)
                lastOutcome[config.id] = "✓ ${instant.size} source${if (instant.size == 1) "" else "s"} (instant)"
                com.hikari.app.net.StreamProbe.warmAsync(instant)
                return@withContext instant
            }
            val res = invoke("loadStreams", listOf(token))
            if (res == null) {
                return@withContext fail("✗ Extension did not answer — check Logs.")
            }
            if (!res.optBoolean("ok", false)) {
                val err = res.optString("error").ifBlank { "no sources found" }
                return@withContext fail("✗ $err")
            }
            val data = res.opt("data")
            val arr = when (data) {
                is JSONArray -> data
                is JSONObject -> data.optJSONArray("streams") ?: data.optJSONArray("sources")
                else -> null
            }
            if (arr == null || arr.length() == 0) {
                return@withContext fail("✗ No playable sources for this title.")
            }
            val out = mapStreams(arr)
            if (out.isEmpty()) {
                return@withContext fail("✗ Returned ${arr.length()} links but none playable.")
            }
            streamErrors.remove(config.id)
            lastOutcome[config.id] = "✓ ${out.size} source${if (out.size == 1) "" else "s"} in " +
                "${(System.currentTimeMillis() - startedAt) / 1000}s"
            instantStreams[token] = out
            val distinct = out.distinctBy { it.url }
            com.hikari.app.net.StreamProbe.warmAsync(distinct)
            distinct
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

    /**
     * One SkyStream `StreamResult` → Hikari [StreamSource].
     *
     * SkyStream's app routes `MAGIC_PROXY_v1`/`MAGIC_PROXY_v2`/`magic_m3u8:`
     * URLs through its own local proxy, which is what injects the headers the
     * CDN needs (and what serves a plugin-generated playlist). Hikari has no
     * such proxy, so the payloads are decoded here instead: both proxy forms
     * carry the REAL url (v2 also the headers to send with it), which is
     * exactly what the player needs, and a `magic_m3u8:` playlist becomes a
     * `data:` URL the player can read directly.
     */
    private fun toStreamSource(o: JSONObject): StreamSource? {
        var url = o.optString("url")
        if (url.isBlank()) return null
        val headers = LinkedHashMap<String, String>()

        url = decodeMagic(url, headers)

        val source = o.optString("source").ifBlank { o.optString("name") }
        val quality = o.optString("quality")
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
        val isM3u8 = url.startsWith("data:application/vnd.apple.mpegurl") || url.contains(".m3u8", true)
        val isMpd = url.contains(".mpd", true)
        return StreamSource(
            name = displayName,
            url = if (isTorrent || url.startsWith("data:")) url else Http.normalizeDriveUrl(url),
            headers = headers,
            subtitles = subs,
            isTorrent = isTorrent,
            isM3u8 = isM3u8,
            isMpd = isMpd,
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
