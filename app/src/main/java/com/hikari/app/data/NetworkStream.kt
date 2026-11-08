package com.hikari.app.data

import com.hikari.app.HikariApp
import com.hikari.app.cs3.FallbackResolver
import com.hikari.app.net.Http
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.IdentityHashMap
import java.util.LinkedList
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient

object NetworkStream {

    const val MARKER = "netstream"

    /** True when [config] was added through the tab's "Network stream" mode. */
    fun isStream(config: ProviderConfig): Boolean =
        config.type == ProviderType.IPTV && config.extra == MARKER

    /** Anything with a scheme is worth trying; the resolver is what decides. A
     *  bare info hash (no scheme at all) counts too — it is magnetized on the
     *  way in, instead of being prefixed into an unplayable https URL that
     *  then listed as an IPTV playlist. */
    fun isStreamLink(url: String): Boolean {
        val u = url.trim()
        return u.startsWith("http://") || u.startsWith("https://") ||
            u.startsWith("magnet:", ignoreCase = true) || isBareInfoHash(u)
    }

    /**
     * A pasted info hash with no scheme at all: 40 hex chars (SHA-1) or 32
     * base32 chars — a torrent all the same.
     */
    fun isBareInfoHash(url: String): Boolean {
        val u = url.trim()
        return (u.length == 40 && u.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) ||
            (u.length == 32 && u.all { it in 'a'..'z' || it in 'A'..'Z' || it in '2'..'7' })
    }

    /** Public trackers, so a trackerless magnet (or bare hash) has peers to
     *  find — other apps ship defaults, and without any a good hash resolves
     *  to "server failed". Appended only when the magnet names none. */
    private val DEFAULT_TRACKERS = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.tracker.cl:1337/announce",
        "udp://tracker.openbittorrent.com:6969/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://tracker.moeking.me:6969/announce",
        "udp://opentracker.i2p.rocks:6969/announce",
        "http://tracker.openbittorrent.com:80/announce",
    )

    /** A bare info hash as a magnet link, with default trackers. */
    fun magnetize(hash: String): String {
        val h = hash.trim()
        val tr = DEFAULT_TRACKERS.joinToString("") {
            "&tr=" + URLEncoder.encode(it, "UTF-8")
        }
        return "magnet:?xt=urn:btih:$h$tr"
    }

    /** [url] with default trackers appended when a magnet names none. */
    private fun withDefaultTrackers(url: String): String {
        if (!url.startsWith("magnet:", ignoreCase = true)) return url
        if (url.contains("&tr=", ignoreCase = true)) return url
        val tr = DEFAULT_TRACKERS.joinToString("") {
            "&tr=" + URLEncoder.encode(it, "UTF-8")
        }
        return url + tr
    }

    /**
     * Normalizes a pasted link exactly the way it is saved: surrounding and
     * inner whitespace stripped (hashes/URLs pasted with line breaks), a bare
     * info hash magnetized with default trackers, magnets kept verbatim, and
     * scheme-less hosts gaining their https:// prefix. One function so saving
     * and looking up can never disagree again (see IptvScreen.addNow, which
     * re-derives this to find the row Play just saved).
     */
    fun normalizePastedLink(link: String): String {
        val trimmed = link.trim().filter { !it.isWhitespace() }
        if (trimmed.startsWith("magnet:", ignoreCase = true)) return trimmed
        if (isBareInfoHash(trimmed)) return magnetize(trimmed)
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        if (trimmed.isBlank()) return ""
        return "https://$trimmed"
    }

    /** True for a magnet link or .torrent file: torrent-engine territory, never
     *  to be listed, grouped, badged or played as an IPTV channel. */
    fun isTorrentLink(url: String): Boolean {
        val u = url.trim()
        return u.startsWith("magnet:", ignoreCase = true) || isTorrentFile(u) || isBareInfoHash(u)
    }

    /**
     * A torrent FILE url, either convention: ".torrent" and the older ".tor".
     * (The two need separate checks — ".torrent" does not end in ".tor".)
     */
    fun isTorrentFile(url: String): Boolean {
        val path = url.trim().lowercase().substringBefore('?').substringBefore('#')
        return path.endsWith(".torrent") || path.endsWith(".tor")
    }

    /** True when [config] is a saved torrent stream (still an IPTV-type row in
     *  storage — no migration — but handled as a torrent everywhere it shows). */
    fun isTorrentConfig(config: ProviderConfig): Boolean =
        config.type == ProviderType.IPTV && isTorrentLink(config.url)

    fun isDirectMediaLink(url: String): Boolean {
        val u = url.trim()
        if (isTorrentLink(u)) return false
        if (!u.startsWith("http://") && !u.startsWith("https://")) return false
        val path = u.lowercase().substringBefore('?').substringBefore('#')
        return DIRECT_MEDIA_EXTENSIONS.any { path.endsWith(it) }
    }

    private val DIRECT_MEDIA_EXTENSIONS = listOf(
        ".m3u8", ".mp4", ".mkv", ".ts", ".webm", ".mpd",
        ".m4v", ".mov", ".avi", ".flv", ".mp3", ".m4a", ".aac",
        ".wmv", ".mpg", ".mpeg",
    )

    private val MEDIA_EXTENSIONS = listOf(
        ".m3u8", ".m3u", ".mp4", ".mkv", ".ts", ".webm", ".mpd",
        ".m4v", ".mov", ".avi", ".flv", ".mp3", ".m4a", ".aac",
    )

    private val VIDEO_EXTENSIONS = listOf(
        ".mp4", ".mkv", ".m4v", ".mov", ".avi", ".webm", ".flv", ".ts", ".wmv", ".mpg", ".mpeg",
    )

    /** Hosts whose links are a Terabox-family share. They all run one API, and
     *  the mirrors exist because the service is blocked in various countries, so
     *  the resolver works off the link's OWN host rather than a fixed one. */
    private val BOX_HOSTS = listOf(
        "terabox", "1024tera", "4funbox", "momerybox", "tibibox",
        "nepnepbox", "telebox", "mirrobox", "shibabox", "teraboxapp",
        "freeterabox", "terashare", "tbx",
    )

    private val MDISK_HOSTS = listOf("mdisk.", "mdisk.me", "disk.mdisk", "diskwala")

    /** What the API wants for a share: `app_id`/`channel`/`clienttype` are the
     *  web app's own values, and the desktop-app user agent is what the hosts
     *  answer a plain client with. */
    private const val BOX_APP_QUERY = "app_id=250528&web=1&channel=dubox&clienttype=0"
    private const val BOX_UA =
        "terabox;1.40.0.132;PC;PC-Windows;10.0.26100;WindowsTeraBox"

    private const val STAGE_TIMEOUT_MS = 20_000L
    private const val RESOLVE_BUDGET_MS = 45_000L

    suspend fun resolve(
        rawUrl: String,
        label: String,
        providerId: String,
        providerName: String,
        budgetMs: Long = RESOLVE_BUDGET_MS,
    ): List<StreamSource> = withContext(Dispatchers.IO) {
        // A bare info hash is a magnet with no scheme yet; a trackerless
        // magnet gets the default trackers (see above).
        val raw = rawUrl.trim()
        val url = if (isBareInfoHash(raw)) magnetize(raw) else withDefaultTrackers(raw)
        if (!isStreamLink(url)) return@withContext emptyList()
        val name = label.trim().ifBlank { hostOf(url) }
        val sources: List<StreamSource> = try {
            withTimeoutOrNull(budgetMs) { resolveBounded(url, name) } ?: emptyList()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            emptyList()
        }
        val chosen = sources.ifEmpty {
            if (isBoxLink(url) && boxShareKey(url) != null) return@withContext emptyList()
            listOf(sourceOf(url, name))
        }
        chosen.distinctBy { it.url }.map {
            it.copy(
                headers = withStreamHeaders(it.headers, url),
                provider = ProviderType.IPTV.groupLabel,
                providerId = providerId,
                providerName = providerName,
            )
        }
    }

    private fun withStreamHeaders(
        headers: Map<String, String>,
        pageUrl: String,
    ): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        headers.forEach { (k, v) -> if (k.isNotBlank() && v.isNotBlank()) out[k] = v }
        val names = out.keys.map { it.lowercase() }
        if (names.none { it == "user-agent" }) out["User-Agent"] = Http.UA
        if (names.none { it == "referer" || it == "referrer" || it == "referrer-policy" }) {
            val origin = runCatching { originOf(pageUrl) }.getOrNull()
            if (!origin.isNullOrBlank()) out["Referer"] = "$origin/"
        }
        return out
    }

    private suspend fun resolveBounded(url: String, label: String): List<StreamSource> {
        // Magnet links hand straight to the torrent engine.
        if (url.startsWith("magnet:", ignoreCase = true)) {
            return listOf(
                StreamSource(
                    name = label.ifBlank { "Torrent" },
                    url = url,
                    isTorrent = true,
                    infoHash = Regex(
                        """[?&]xt=urn:btih:([a-zA-Z0-9]{32,40})""",
                        RegexOption.IGNORE_CASE,
                    ).find(url)?.groupValues?.get(1),
                ),
            )
        }
        if (isTorrentFile(url)) {
            resolveTorrentFile(url, label)?.let { return listOf(it) }
            return listOf(
                StreamSource(
                    name = label.ifBlank { "Torrent" },
                    url = url,
                    isTorrent = true,
                ),
            )
        }
    // 1. Already a stream: nothing to resolve, and asking a CDN to prove it
        //    would only add a round trip to every play.
        if (isDirectMedia(url)) return listOf(sourceOf(url, label))

        // 2. Two hosts whose transformation is mechanical.
        val drive = Http.normalizeDriveUrl(url)
        if (drive != url) return listOf(sourceOf(drive, label))
        pixeldrain(url)?.let { return listOf(sourceOf(it, label)) }

        // 3. The box hosts (Terabox / Telebox / …).
        if (isBoxLink(url)) {
            if (boxShareKey(url) != null) return stage { terabox(url) }.orEmpty()
            val files = stage { terabox(url) }.orEmpty()
            if (files.isNotEmpty()) return files
        }

        // 3b. MDisk-class download pages with a known API shape (Diskwala).
        if (isMdiskLink(url)) {
            val files = stage { mdisk(url) }.orEmpty()
            if (files.isNotEmpty()) return files
        }

        val generic = stage { FallbackResolver.resolve(url) }.orEmpty()
        if (generic.isNotEmpty()) return generic
        val embed = stage { FallbackResolver.resolveEmbedUrl(url, url) }.orEmpty()
        if (embed.isNotEmpty()) return embed

        // 5. A link the CDN serves as video but names nothing like one.
        val mime = stage { sniff(url) }
        if (mime != null && isVideoMime(mime)) return listOf(sourceOf(url, label, mime))

        // 6. The link as the user wrote it.
        return listOf(sourceOf(url, label))
    }

    /** One stage, bounded and never fatal: [STAGE_TIMEOUT_MS] of silence is the
     *  same answer as an exception, and a cancellation from the CALLER still
     *  propagates. */
    private suspend fun <T> stage(block: suspend () -> T?): T? =
        try {
            withTimeoutOrNull(STAGE_TIMEOUT_MS) { block() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }

    // ---- the shape of a link ----

    /** A URL that IS the stream: it names a media container, or a playlist URL
     *  says so in its query (`?type=m3u8`, `?format=m3u_plus` on a single
     *  stream). */
    fun isDirectMedia(url: String): Boolean {
        val lower = url.lowercase()
        val path = lower.substringBefore('?')
        if (MEDIA_EXTENSIONS.any { path.endsWith(it) }) return true
        if (lower.contains(".m3u8")) return true
        val query = lower.substringAfter('?', "")
        return query.contains("type=m3u8") || query.contains("type=m3u") ||
            query.contains("format=m3u8")
    }


    suspend fun torrentFileInfo(url: String, label: String): StreamSource? =
        runCatching { resolveTorrentFile(url, label) }.getOrNull()

    private suspend fun resolveTorrentFile(url: String, label: String): StreamSource? =
    withContext(Dispatchers.IO) {
        val bytes = withTimeoutOrNull(20_000) {
            runCatching {
                Http.fetchBytesCancellable(url, mapOf("User-Agent" to Http.UA), 20)
            }.getOrNull()
        } ?: return@withContext null
        if (bytes.isEmpty() || bytes.size > 4 * 1024 * 1024) return@withContext null
        val parsed = runCatching { parseTorrent(bytes) }.getOrNull() ?: return@withContext null
        StreamSource(
            name = parsed.name.ifBlank { label.ifBlank { "Torrent" } },
            url = url,
            isTorrent = true,
            infoHash = parsed.infoHash,
            trackers = parsed.trackers,
        )
    }

private data class ParsedTorrent(
    val infoHash: String,
    val trackers: List<String>,
    val name: String,
)

private fun parseTorrent(bytes: ByteArray): ParsedTorrent? {
    val r = BReader(bytes)
    val top = r.parse() as? Map<String, Any?> ?: return null
    @Suppress("UNCHECKED_CAST")
    val info = top["info"] as? Map<String, Any?> ?: return null
    val span = r.spans[info] ?: return null
    val digest = java.security.MessageDigest.getInstance("SHA-1")
    digest.update(bytes, span.first, span.last - span.first + 1)
    val hash = digest.digest().joinToString("") { "%02x".format(it) }
    val trackers = LinkedHashSet<String>()
    (top["announce"] as? ByteArray)?.let { trackers += String(it, Charsets.UTF_8).trim() }
    collectTrackers(top["announce-list"], trackers)
    val name = (info["name"] as? ByteArray)?.let { String(it, Charsets.UTF_8).trim() }.orEmpty()
    return ParsedTorrent(
        hash,
        trackers.filter { it.startsWith("http") || it.startsWith("udp") },
        name,
    )
}

private fun collectTrackers(x: Any?, out: MutableSet<String>) {
    when (x) {
        is ByteArray -> out += String(x, Charsets.UTF_8).trim()
        is List<*> -> x.forEach { collectTrackers(it, out) }
    }
}

private class BReader(val b: ByteArray) {
    var i = 0
    val spans = IdentityHashMap<Any, IntRange>()
    fun parse(): Any? {
        if (i >= b.size) return null
        return when (b[i].toInt().toChar()) {
            'i' -> {
                i++
                val s = i
                while (i < b.size && b[i].toInt().toChar() != 'e') i++
                val v = String(b, s, i - s).toLongOrNull() ?: 0L
                i++
                v
            }
            'l' -> {
                i++
                val l = ArrayList<Any?>()
                val start = i - 1
                while (i < b.size && b[i].toInt().toChar() != 'e') l += parse()
                i++
                spans[l] = start..i - 1
                l
            }
            'd' -> {
                i++
                val m = LinkedHashMap<String, Any?>()
                val start = i - 1
                while (i < b.size && b[i].toInt().toChar() != 'e') {
                    val k = parse() as? ByteArray ?: break
                    m[String(k, Charsets.UTF_8)] = parse()
                }
                i++
                spans[m] = start..i - 1
                m
            }
            else -> {
                val cs = i
                while (i < b.size && b[i].toInt() in '0'.code..'9'.code) i++
                if (i >= b.size || b[i].toInt().toChar() != ':') return null
                val n = String(b, cs, i - cs).toIntOrNull() ?: return null
                i++
                if (n < 0 || i + n > b.size) return null
                val v = b.copyOfRange(i, i + n)
                i += n
                v
            }
        }
    }
}


    private fun isVideoMime(mime: String): Boolean {
        val m = mime.lowercase()
        return m.startsWith("video/") || m.startsWith("audio/") ||
            m.contains("mpegurl") || m.contains("dash+xml")
    }

    fun hostOf(url: String): String =
        url.substringAfter("://").substringBefore('/').substringBefore('?')

    private fun originOf(url: String): String {
        val scheme = if (url.startsWith("http://")) "http" else "https"
        return "$scheme://" + hostOf(url)
    }

    private fun isBoxLink(url: String): Boolean {
        val host = hostOf(url).lowercase()
        return BOX_HOSTS.any { host.contains(it) }
    }

    private fun sourceOf(
        url: String,
        name: String,
        contentType: String? = null,
        headers: Map<String, String> = emptyMap(),
        isM3u8: Boolean = false,
    ): StreamSource {
        val lower = url.lowercase().substringBefore('?')
        return StreamSource(
            name = name,
            url = url,
            headers = headers,
            isM3u8 = isM3u8 || lower.endsWith(".m3u8") || lower.endsWith(".m3u") ||
                (contentType?.contains("mpegurl", ignoreCase = true) == true),
            isMpd = lower.endsWith(".mpd") ||
                (contentType?.contains("dash+xml", ignoreCase = true) == true),
        )
    }

    // ---- pixeldrain ----

    /** `pixeldrain.com/u/<id>` (and `/file/<id>`) serve a player page; the file
     *  itself is the same path under `/api/file/`, which streams the bytes with
     *  range support — what the player needs for seeking. */
    private fun pixeldrain(url: String): String? {
        val host = hostOf(url).lowercase()
        if (!host.endsWith("pixeldrain.com")) return null
        if (url.contains("/api/file/")) return url
        val id = Regex("""/(?:u|file|d)/([A-Za-z0-9]+)""").find(url)?.groupValues?.get(1) ?: return null
        return "https://pixeldrain.com/api/file/$id?download"
    }

    // ---- terabox / telebox ----

    /**
     * The share key inside a box link, as the API wants it.
     *
     * A share is `https://<host>/s/<key>` (or `?surl=<key>` on the sharing
     * pages), and the key is the whole of that segment — the download API is
     * given it verbatim.
     */
    /** True when [url] is a Terabox-family share link (resolvable at play
     *  time — see [terabox]). Public so playlist channels can route per-URL. */
    fun isBoxShare(url: String): Boolean = boxShareKey(url) != null

    private fun boxShareKey(url: String): String? {
        val direct = Regex("""/s/([A-Za-z0-9_-]{5,})""").find(url)?.groupValues?.get(1)
            ?: Regex("""/sharing/([A-Za-z0-9_-]{5,})""").find(url)?.groupValues?.get(1)
            ?: Regex("""/share/([A-Za-z0-9_-]{5,})""").find(url)?.groupValues?.get(1)
        if (!direct.isNullOrBlank()) return direct
        val param = Regex("""[?&]surl=([A-Za-z0-9_-]{5,})""").find(url)?.groupValues?.get(1)
            ?: return null
        return if (param.startsWith("1")) param else "1$param"
    }

    private suspend fun terabox(url: String): List<StreamSource> {
        val key = boxShareKey(url) ?: return emptyList()
        val origin = originOf(url)
        val jar = LinkedList<String>()
        val shareHtml = fetchText(url, BOX_UA, jar, referer = origin)
        var token = if (!shareHtml.isNullOrBlank()) jsTokenOf(shareHtml) else ""
        val home = if (token.isNotBlank()) {
            shareHtml
        } else {
            fetchText("$origin/main", BOX_UA, jar).also {
                token = if (!it.isNullOrBlank()) jsTokenOf(it) else ""
            }
        }
        if (home.isNullOrBlank()) {
            Logs.log("NetStream", "terabox: no page html for $origin (share + /main both empty)")
            return emptyList()
        }
        Logs.log(
            "NetStream",
            "terabox: share page ${shareHtml?.length ?: 0} chars" +
                " tokenFrom=" + (if (!shareHtml.isNullOrBlank() && token.isNotBlank() && jsTokenOf(shareHtml).isNotBlank()) "share" else "main") +
                " yunData=" + (shareHtml?.contains("yunData") == true) +
                " verify=" + (shareHtml?.contains("verify", ignoreCase = true) == true),
        )
        // The reference client sends a per-session `browserid` cookie on the
        // API calls; without one the list endpoint can refuse an otherwise
        // identical request.
        if (jar.none { it.substringBefore('=').trim().equals("browserid", ignoreCase = true) }) {
            jar.add("browserid=" + freshBrowserId())
        }
        val tokens = fetchText(
            "$origin/api/shorturlinfo?shorturl=$key&root=1",
            BOX_UA,
            jar,
            referer = url,
        ) ?: run {
            Logs.log("NetStream", "terabox: shorturlinfo unreachable for $origin")
            return emptyList()
        }
        val tokensErrno = runCatching { JSONObject(tokens).optInt("errno", -1) }.getOrDefault(-1)
        var sekey = boxSekeyOf(tokens)
        var sig = boxSignOf(shareHtml) ?: boxSignOf(tokens)
        var ids = boxShareIdsOf(tokens)
        Logs.log(
            "NetStream",
            "terabox: shorturlinfo errno=$tokensErrno token=" +
                (if (token.isNotBlank()) "yes" else "none") +
                " sekey=" + (if (!sekey.isNullOrBlank()) "yes" else "none") +
                " sign=" + (if (sig != null) "yes" else "none") +
                " ids=" + (if (ids != null) "yes" else "none"),
        )
        // The metadata's own field names (randsk vs sekey, id shapes), so the
        // next report shows what the mirror actually sends — bounded.
        Logs.log("NetStream", "terabox: shorturlinfo keys=" + runCatching { JSONObject(tokens).keys().asSequence().toList() }.getOrDefault(emptyList()).joinToString(",").take(300))
        if (boxShareLocked(tokens)) {
            Logs.log("NetStream", "terabox: share needs a password/extraction code for $origin")
            return emptyList()
        }

        val infoEntries = if (tokensErrno == 0) boxInfoList(tokens) else null
        if (infoEntries != null) {
            var infoDlink = 0
            for (e in infoEntries) {
                if (e.optString("dlink").isNotBlank()) infoDlink++
            }
            Logs.log("NetStream", "terabox: shorturlinfo list=${infoEntries.size} dlink=$infoDlink for $origin")
        }
        var list: List<JSONObject>? = if (infoEntries != null && infoEntries.any { it.optString("dlink").isNotBlank() }) {
            Logs.log("NetStream", "terabox: resolving from shorturlinfo list for $origin")
            infoEntries
        } else {
            boxList(origin, key, token, jar, sekey, sig, ids, pageUrl = url)
        }
        if (list == null) {
            // A fresh page read (new cookies + token) is what a "verify" answer
            // asks for; one retry is enough for a challenge that is really just
            // a stale session. The share metadata is re-read too, so a rotated
            // sekey cannot doom the retry.
            Logs.log("NetStream", "terabox: list empty (shorturlinfo errno=$tokensErrno), retrying with fresh session")
            val retryJar = LinkedList<String>()
            val retryHome = fetchText(url, BOX_UA, retryJar, referer = origin)
                ?: fetchText("$origin/main", BOX_UA, retryJar)
                ?: home
            if (retryJar.none { it.substringBefore('=').trim().equals("browserid", ignoreCase = true) }) {
                retryJar.add("browserid=" + freshBrowserId())
            }
            val retryTokens = fetchText(
                "$origin/api/shorturlinfo?shorturl=$key&root=1",
                BOX_UA,
                retryJar,
                referer = url,
            )
            if (!retryTokens.isNullOrBlank() &&
                runCatching { JSONObject(retryTokens).optInt("errno", -1) }.getOrDefault(-1) == 0
            ) {
                sekey = boxSekeyOf(retryTokens) ?: sekey
                ids = boxShareIdsOf(retryTokens) ?: ids
            }
            sig = boxSignOf(retryHome) ?: boxSignOf(retryTokens) ?: sig
            val retryInfo = boxInfoList(retryTokens)
            list = if (retryInfo != null && retryInfo.any { it.optString("dlink").isNotBlank() }) {
                Logs.log("NetStream", "terabox: resolving from shorturlinfo list for $origin")
                retryInfo
            } else {
                boxList(origin, key, jsTokenOf(retryHome), retryJar, sekey, sig, ids, pageUrl = url)
            }
            if (list == null) {
                val shareAlive = runCatching { JSONObject(tokens).optInt("errno", -1) == 0 }
                    .getOrDefault(false)
                if (shareAlive) {
                    list = boxWebList(origin, url, key, sekey, sig, ids)
                }
                if (list == null) {
                    // Same root call, several cheap shapes (XHR headers, no
                    // token, browser UA, canonical session) — first errno=0
                    // wins; every shape is logged for the next report.
                    list = boxProbeList(origin, key, jsTokenOf(retryHome), retryJar, sekey, sig, ids, pageUrl = url)
                }
                if (list == null) {
                    val retryToken = jsTokenOf(retryHome)
                    val fallbacks = listOf("https://www.terabox.com", "https://terabox.com")
                        .filter { it != origin }
                    for (fb in fallbacks) {
                        list = boxList(fb, key, retryToken, retryJar, sekey, sig, ids, pageUrl = url)
                        if (list != null) {
                            Logs.log("NetStream", "terabox: list answered via fallback $fb for $origin")
                            break
                        }
                    }
                }
            }
        }
        val entries = list ?: return emptyList()
        // The share's metadata call is not needed for the dlinks, but it is what
        // tells a share that is gone from one whose root is an empty folder.
        val shareAlive = runCatching { JSONObject(tokens).optInt("errno", -1) == 0 }
            .getOrDefault(false)
        if (entries.isEmpty() && !shareAlive) return emptyList()

        val headers = boxHeaders(origin, jar)
        val files = ArrayList<StreamSource>()
        var noDlink = 0
        for (e in entries) {
            val dlink = e.optString("dlink").trim()
            if (dlink.isBlank() || !dlink.startsWith("http")) { noDlink++; continue }
            val file = e.optString("server_filename").ifBlank { e.optString("filename") }.trim()
            if (file.isNotBlank() && !looksLikeVideo(file)) continue
            files += sourceOf(dlink, file.ifBlank { key }, headers = headers)
            if (files.size >= 8) break
        }
        if (files.isEmpty() && entries.isNotEmpty()) {
            Logs.log("NetStream", "terabox: ${entries.size} entries but no playable dlinks (noDlink=$noDlink) for $origin")
        }
        return files
    }

    private fun boxSekeyOf(tokens: String): String? {
        val root = runCatching { JSONObject(tokens) }.getOrNull() ?: return null
        val scopes = listOf(
            root,
            root.optJSONArray("shorturlinfo")?.optJSONObject(0),
            root.optJSONObject("data"),
        )
        for (o in scopes) {
            if (o == null) continue
            o.optString("sekey").takeIf { it.isNotBlank() }?.let { return it }
            val randsk = o.optString("randsk").takeIf { it.isNotBlank() } ?: continue
            // A value with no escapes decodes to itself, so decoding
            // unconditionally is safe; the raw value stays the fallback.
            runCatching { URLDecoder.decode(randsk, "UTF-8") }
                .getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
            return randsk
        }
        return null
    }

    private fun boxShareIdsOf(tokens: String): Pair<String, String>? {
        val root = runCatching { JSONObject(tokens) }.getOrNull() ?: return null
        var shareid: String? = null
        var uk: String? = null
        fun str(o: JSONObject, vararg keys: String): String? {
            for (k in keys) {
                o.optString(k).takeIf { it.isNotBlank() && it != "null" }?.let { return it }
                o.opt(k)?.toString()?.takeIf { it.isNotBlank() && it != "null" }?.let { return it }
            }
            return null
        }
        val queue = ArrayDeque<JSONObject>()
        queue.add(root)
        val seen = HashSet<Int>()
        while (queue.isNotEmpty()) {
            val o = queue.removeFirst()
            if (!seen.add(System.identityHashCode(o))) continue
            if (shareid == null) shareid = str(o, "shareid", "share_id", "shareId")
            if (uk == null) uk = str(o, "uk", "share_uk", "shareUk", "share_uk_str")
            if (shareid != null && uk != null) return shareid to uk
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                when (val v = runCatching { o.get(k) }.getOrNull()) {
                    is JSONObject -> queue.add(v)
                    is org.json.JSONArray -> for (i in 0 until v.length()) {
                        (runCatching { v.optJSONObject(i) }.getOrNull())?.let { queue.add(it) }
                    }
                    else -> {}
                }
            }
        }
        return null
    }

    private fun boxSignOf(html: String?): Pair<String, Long>? {
        if (html.isNullOrBlank()) return null
        val sign = Regex(""""sign"\s*[:=]\s*"([A-Za-z0-9+/=_-]{16,})"""").find(html)?.groupValues?.get(1)
            ?: Regex("""['"]sign['"]\s*[:=]\s*['"]([^'"]{16,})['"]""").find(html)?.groupValues?.get(1)
            ?: Regex("""yunData\.sign\s*=\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1)
            ?: Regex("""\bsign\s*[:=]\s*['"]([A-Za-z0-9+/=_-]{16,})['"]""").find(html)?.groupValues?.get(1)
            ?: Regex("""[?&]sign=([A-Za-z0-9+/=_-]{16,})""").find(html)?.groupValues?.get(1)
            ?: return null
        val rawTs = Regex(""""timestamp"\s*[:=]\s*"?(\d{10,13})"?""").find(html)?.groupValues?.get(1)
            ?: Regex("""['"]timestamp['"]\s*[:=]\s*['"]?(\d{10,13})""").find(html)?.groupValues?.get(1)
            ?: Regex("""yunData\.timestamp\s*=\s*['"]?(\d{10,13})""").find(html)?.groupValues?.get(1)
            ?: Regex("""\btimestamp\s*[:=]\s*['"]?(\d{10,13})""").find(html)?.groupValues?.get(1)
            ?: Regex("""[?&]timestamp=(\d{10,13})""").find(html)?.groupValues?.get(1)
            ?: return null
        val ts = (if (rawTs.length > 10) rawTs.substring(0, 10) else rawTs).toLongOrNull()
            ?: return null
        return sign to ts
    }

    /** True when the share's own metadata says it needs a password or
     *  extraction code (narrow on purpose — unknown shapes stay resolvable). */
    private fun boxShareLocked(tokens: String): Boolean {
        val root = runCatching { JSONObject(tokens) }.getOrNull() ?: return false
        val flags = listOf("need_password", "needpassword", "need_pwd", "encrypted", "is_encrypt", "protected")
        if (flags.any { root.optInt(it, 0) == 1 || root.optBoolean(it, false) }) return true
        val first = root.optJSONArray("shorturlinfo")?.optJSONObject(0) ?: return false
        return flags.any { first.optInt(it, 0) == 1 || first.optBoolean(it, false) }
    }

    /** A per-session `browserid` cookie value, the way the reference client
     *  mints one (44 random bytes, base64) for its API calls. */
    private fun freshBrowserId(): String {
        val bytes = ByteArray(44)
        runCatching { java.security.SecureRandom().nextBytes(bytes) }
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }

    /** Call-shape overrides for one list attempt (see the variant probe):
     *  the UA the call presents, extra headers, and whether the token stays
     *  off the query. Defaults reproduce the long-standing call exactly. */
    private data class BoxCallCfg(
        val ua: String = BOX_UA,
        val extra: Map<String, String> = emptyMap(),
        val skipToken: Boolean = false,
    )

    /** `/share/list` for one share — the account of the entries at its root,
     *  plus (bounded) the video files inside any folder it holds. */
    private suspend fun boxList(
        origin: String,
        key: String,
        jsToken: String,
        jar: MutableList<String>,
        sekey: String?,
        sig: Pair<String, Long>?,
        ids: Pair<String, String>?,
        pageUrl: String,
        cfg: BoxCallCfg = BoxCallCfg(),
    ): List<JSONObject>? {
        val root = boxListPage(origin, key, jsToken, jar, sekey, sig, ids, pageUrl, dir = null, cfg = cfg) ?: return null
        val out = ArrayList<JSONObject>(root)
        var folders = 0
        for (e in root) {
            if (folders >= 3) break
            if (!isBoxFolder(e)) continue
            val dir = e.optString("path").trim()
            if (dir.isBlank()) continue
            folders++
            out += boxListPage(origin, key, jsToken, jar, sekey, sig, ids, pageUrl, dir = dir, cfg = cfg).orEmpty()
        }
        return out
    }

    private suspend fun boxListPage(
        origin: String,
        key: String,
        jsToken: String,
        jar: MutableList<String>,
        sekey: String?,
        sig: Pair<String, Long>?,
        ids: Pair<String, String>?,
        pageUrl: String,
        dir: String?,
        cfg: BoxCallCfg = BoxCallCfg(),
    ): List<JSONObject>? {
        val query = buildString {
            append("$origin/share/list?$BOX_APP_QUERY")
            if (jsToken.isNotBlank() && !cfg.skipToken) append("&jsToken=").append(URLEncoder.encode(jsToken, "UTF-8"))
            if (!sekey.isNullOrBlank()) append("&sekey=").append(URLEncoder.encode(sekey, "UTF-8"))
            // The page's own signature — the web client's calls always carry
            // it, and the endpoint refuses unsigned calls with errno 105.
            if (sig != null) append("&sign=").append(sig.first).append("&timestamp=").append(sig.second)
            if (ids != null) append("&shareid=").append(ids.first).append("&uk=").append(ids.second)
            append("&shorturl=").append(key)
            append("&by=name&order=asc&num=20000&page=1")
            if (dir.isNullOrBlank()) {
                append("&dir=&root=1")
            } else {
                append("&dir=").append(URLEncoder.encode(dir, "UTF-8"))
            }
        }
        val text = fetchText(query, cfg.ua, jar, referer = pageUrl, extra = cfg.extra) ?: return null
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val errno = json.optInt("errno", -1)
        if (errno != 0) {
            Logs.log(
                "NetStream",
                "terabox: share/list errno=$errno" +
                    json.optString("errmsg").takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty(),
            )
            return null
        }
        val arr = json.optJSONArray("list") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    private fun boxInfoList(tokens: String?): List<JSONObject>? {
        if (tokens.isNullOrBlank()) return null
        val root = runCatching { JSONObject(tokens) }.getOrNull() ?: return null
        val arr = root.optJSONArray("list")
            ?: root.optJSONArray("shorturlinfo")?.optJSONObject(0)?.optJSONArray("list")
            ?: root.optJSONObject("data")?.optJSONArray("list")
            ?: return null
        val out = ArrayList<JSONObject>(arr.length())
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { out += it }
        }
        return out
    }

    private suspend fun boxWebList(
        origin: String,
        pageUrl: String,
        key: String,
        sekey: String?,
        sig: Pair<String, Long>?,
        ids: Pair<String, String>?,
    ): List<JSONObject>? {
        val sess = boxWebViewSession(pageUrl) ?: run {
            Logs.log("NetStream", "terabox: web session empty for $origin")
            return null
        }
        val (html, cookies, web) = sess
        if (cookies.isEmpty() && html.isBlank()) {
            Logs.log("NetStream", "terabox: web session empty for $origin")
            val main = Handler(Looper.getMainLooper())
            main.post {
                runCatching { web.stopLoading() }
                runCatching { web.destroy() }
            }
            return null
        }
        try {
            val jar = LinkedList(cookies.distinct())
            var webToken = jsTokenOf(html)
            var webSig = boxSignOf(html) ?: sig
            var webSekey = sekey
            var webIds = ids
            val meta = fetchText(
                "$origin/api/shorturlinfo?shorturl=$key&root=1",
                BOX_UA,
                jar,
                referer = pageUrl,
            )
            if (!meta.isNullOrBlank() &&
                runCatching { JSONObject(meta).optInt("errno", -1) }.getOrDefault(-1) == 0
            ) {
                webSekey = boxSekeyOf(meta) ?: webSekey
                webIds = boxShareIdsOf(meta) ?: webIds
                webSig = boxSignOf(meta) ?: webSig
            }
            Logs.log(
                "NetStream",
                "terabox: web session page ${html.length} chars cookies=${jar.size}" +
                    " (" + jar.map { it.substringBefore('=') }.distinct().joinToString(",").take(200) + ")" +
                    " token=" + (if (webToken.isNotBlank()) "yes" else "none") +
                    " sekey=" + (if (!webSekey.isNullOrBlank()) "yes" else "none") +
                    " sign=" + (if (webSig != null) "yes" else "none") +
                    " ids=" + (if (webIds != null) "yes" else "none"),
            )
            val markers = listOf("yunData", "jsToken", "randsk", "\"sign\"", "\"timestamp\"", "shareid")
            Logs.log(
                "NetStream",
                "terabox: web page markers=" +
                    markers.joinToString(" ") { it + "=" + (if (html.contains(it, ignoreCase = true)) "yes" else "no") },
            )
            boxWebPageFetch(web, origin, key)?.let {
                Logs.log("NetStream", "terabox: list answered via web page-list for $origin")
                return it
            }
            val out = boxList(origin, key, webToken, jar, webSekey, webSig, webIds, pageUrl = pageUrl)
            if (out != null) {
                Logs.log("NetStream", "terabox: list answered via web session for $origin")
            } else {
                // The replay lost too: read the official client's own bundles
                // for the endpoints the DOM never carries (best effort, bounded).
                withTimeoutOrNull(25_000) { boxBundleRecon(html, jar, pageUrl, origin, meta) }
            }
            return out
        } finally {
            val main = Handler(Looper.getMainLooper())
            main.post {
                runCatching { web.stopLoading() }
                runCatching { web.destroy() }
            }
        }
    }

    private suspend fun boxWebViewSession(pageUrl: String): Triple<String, List<String>, WebView>? {
        val done = CompletableDeferred<Triple<String, List<String>, WebView?>?>()
        val main = Handler(Looper.getMainLooper())
        val holder = arrayOfNulls<WebView>(1)
        fun finish(value: Triple<String, List<String>, WebView?>?) {
            if (done.isCompleted) return
            if (value?.third == null) {
                runCatching { holder[0]?.destroy() }
                holder[0] = null
            }
            done.complete(value)
        }
        main.post {
            try {
                val cm = CookieManager.getInstance()
                cm.setAcceptCookie(true)
                val web = WebView(HikariApp.instance.applicationContext)
                holder[0] = web
                web.settings.javaScriptEnabled = true
                web.settings.domStorageEnabled = true
                web.settings.mediaPlaybackRequiresUserGesture = false
                web.webViewClient = object : WebViewClient() {
                    var settled = false
                    override fun onPageFinished(view: WebView, url: String) {
                        if (settled) return
                        settled = true
                        // Let the SPA render + drop its cookies before
                        // reading (bounded by the watchdog below).
                        main.postDelayed({
                            try {
                                val cookies = (cm.getCookie(pageUrl) ?: "")
                                    .split(";").map { it.trim() }
                                    .filter { it.isNotBlank() }
                                view.evaluateJavascript(
                                    "(function(){return document.documentElement.outerHTML})()",
                                ) { raw ->
                                    finish(Triple(unquoteJs(raw), cookies, view))
                                }
                            } catch (_: Throwable) {
                                finish(null)
                            }
                        }, 2500)
                    }
                }
                web.loadUrl(pageUrl)
            } catch (_: Throwable) {
                finish(null)
            }
        }
        // Whatever the page does (hang, challenge, redirect loop), the
        // deferred resolves after ~11s; the caller's own timeout is the backstop.
        main.postDelayed({ finish(null) }, 11_000)
        val sess = withTimeoutOrNull(12_000) { done.await() }
        val web = sess?.third
        if (sess == null || web == null) {
            main.post {
                runCatching { holder[0]?.destroy() }
                holder[0] = null
            }
            return null
        }
        return Triple(sess.first, sess.second, web)
    }

    /** One evaluateJavascript on [web] from a background thread: posts the
     *  evaluation to the main thread, waits for its callback, unquotes. */
    private suspend fun jsEval(web: WebView, expr: String): String? {
        val done = CompletableDeferred<String?>()
        val main = Handler(Looper.getMainLooper())
        main.post {
            try {
                web.evaluateJavascript(expr) { raw ->
                    if (!done.isCompleted) done.complete(unquoteJs(raw))
                }
            } catch (_: Throwable) {
                if (!done.isCompleted) done.complete(null)
            }
        }
        main.postDelayed({ if (!done.isCompleted) done.complete(null) }, 8000)
        return withTimeoutOrNull(9000) { done.await() }
    }

    private suspend fun boxWebPageFetch(
        web: WebView,
        origin: String,
        key: String,
    ): List<JSONObject>? {
        val main = Handler(Looper.getMainLooper())
        val keyLit = JSONObject.quote(key)
        // Fires the page's own fetch and parks the combined answer on
        // window.__tbResult; the poll below reads it back. Tokens are
        // scraped from the rendered DOM + window.yunData when present —
        // whatever subset the page holds goes on the call exactly the
        // way the official client's calls carry it.
        val fire = "(function(){" +
            "window.__tbResult='';" +
            "(async function(){" +
            "try{" +
            "var html=document.documentElement.outerHTML;" +
            "var yd='';" +
            "try{if(window.yunData)yd=JSON.stringify(window.yunData);}catch(e){}" +
            "var blob=html+'\\n'+yd;" +
            "function m2(re){var x=blob.match(re);return x?(x[1]||''):'';}" +
            "var jsToken=m2(/\"jsToken\"\\s*:\\s*\"([^\"]+)\"/)||m2(/window\\.jsToken\\s*=\\s*['\"]([^'\"]+)['\"]/);" +
            "var randsk=m2(/\"randsk\"\\s*:\\s*\"([^\"]+)\"/);" +
            "var sekey=m2(/\"sekey\"\\s*:\\s*\"([^\"]+)\"/);" +
            "if(!sekey&&randsk){try{sekey=decodeURIComponent(randsk);}catch(e){sekey=randsk;}}" +
            "var sign=m2(/\"sign\"\\s*:\\s*\"([A-Za-z0-9+\\/=\\-_]{8,})\"/);" +
            "var ts=m2(/\"timestamp\"\\s*[:=]\\s*\"?(\\d{10,13})/);" +
            "if(ts&&ts.length>10)ts=ts.substring(0,10);" +
            "var shareid=m2(/\"shareid\"\\s*:\\s*\"?(\\d{6,})/)||m2(/\"share_id\"\\s*:\\s*\"?(\\d{6,})/);" +
            "var uk=m2(/\"share_uk\"\\s*:\\s*\"?(\\d{6,})/)||m2(/\"uk\"\\s*:\\s*\"?(\\d{6,})/);" +
            "var base='/share/list?app_id=250528&web=1&channel=dubox&clienttype=0';" +
            "if(jsToken)base+='&jsToken='+encodeURIComponent(jsToken);" +
            "if(sekey)base+='&sekey='+encodeURIComponent(sekey);" +
            "if(sign)base+='&sign='+sign;" +
            "if(ts)base+='&timestamp='+ts;" +
            "if(shareid)base+='&shareid='+shareid;" +
            "if(uk)base+='&uk='+uk;" +
            "base+='&shorturl='+encodeURIComponent(" + keyLit + ")+'&by=name&order=asc&num=1000&page=1';" +
            "function tok(){return (jsToken?'T':'')+(sekey?'S':'')+(sign?'G':'')+(shareid?'I':'');}" +
            "async function get(dir){" +
            "var q=base+(dir?'&dir='+encodeURIComponent(dir):'&dir=&root=1');" +
            "try{var r=await fetch(q,{credentials:'include'});return await r.text();}catch(e){return '';}" +
            "};" +
            "var root=await get('');" +
            "var out={tok:tok(),errno:-99,root:root,more:[]};" +
            "try{" +
            "var j=JSON.parse(root);" +
            "out.errno=(j&&(typeof j.errno!=='undefined'))?j.errno:-98;" +
            "if(j&&j.errno===0&&j.list){var n=0;" +
            "for(var i=0;i<j.list.length&&n<3;i++){var e=j.list[i];" +
            "if((e.isdir===1||e.category===6)&&e.path){out.more.push(await get(e.path));n++;}}}" +
            "}catch(e){}" +
            "var s=JSON.stringify(out);" +
            "window.__tbResult=s.length>400000?s.substring(0,400000):s;" +
            "}catch(e){window.__tbResult='{\"tok\":\"ERR\",\"errno\":-97,\"root\":\"\",\"more\":[]}';}" +
            "})();})()"
        main.post { runCatching { web.evaluateJavascript(fire) {} } }
        var raw = ""
        for (i in 0 until 20) {
            kotlinx.coroutines.delay(500)
            raw = jsEval(web, "window.__tbResult||''") ?: ""
            if (raw.isNotBlank()) break
        }
        if (raw.isBlank() || !raw.trimStart().startsWith("{")) {
            Logs.log("NetStream", "terabox: web page-list no result for $origin")
            return null
        }
        val wrap = runCatching { JSONObject(raw) }.getOrNull()
        if (wrap == null) {
            Logs.log("NetStream", "terabox: web page-list unreadable result for $origin")
            return null
        }
        val tok = wrap.optString("tok").take(8)
        val errno = wrap.optInt("errno", -98)
        Logs.log("NetStream", "terabox: web page-list errno=$errno tok=$tok for $origin")
        if (errno != 0) return null
        val entries = ArrayList<JSONObject>()
        fun absorb(text: String?) {
            val arr = runCatching { JSONObject(text ?: "").optJSONArray("list") }.getOrNull() ?: return
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { entries += it }
            }
        }
        absorb(wrap.optString("root"))
        val more = wrap.optJSONArray("more")
        if (more != null) {
            for (i in 0 until more.length()) absorb(more.optString(i))
        }
        if (entries.isEmpty()) return emptyList()
        var noDlink = 0
        for (e in entries) {
            if (e.optString("dlink").isBlank()) noDlink++
        }
        Logs.log("NetStream", "terabox: web page-list entries=${entries.size} noDlink=$noDlink for $origin")
        return entries
    }

    /** Unquote an evaluateJavascript result (a JSON string literal). */
    private fun unquoteJs(quoted: String?): String {
        if (quoted.isNullOrBlank() || quoted == "null") return ""
        val t = quoted.trim()
        if (t.length < 2 || !t.startsWith("\"") || !t.endsWith("\"")) return t
        val inner = t.substring(1, t.length - 1)
        val sb = StringBuilder(inner.length)
        var i = 0
        while (i < inner.length) {
            val c = inner[i]
            if (c == '\\' && i + 1 < inner.length) {
                when (val n = inner[i + 1]) {
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        val hex = inner.substring(i + 2, (i + 6).coerceAtMost(inner.length))
                        val code = hex.toIntOrNull(16)
                        if (code != null) {
                            sb.append(code.toChar())
                            i += 4
                        } else {
                            sb.append(n)
                        }
                    }
                    else -> sb.append(n)
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private suspend fun boxProbeList(
        origin: String,
        key: String,
        jsToken: String,
        jar: MutableList<String>,
        sekey: String?,
        sig: Pair<String, Long>?,
        ids: Pair<String, String>?,
        pageUrl: String,
    ): List<JSONObject>? {
        val xhr = BoxCallCfg(
            extra = mapOf("X-Requested-With" to "XMLHttpRequest", "Origin" to origin),
        )
        boxListPage(origin, key, jsToken, jar, sekey, sig, ids, pageUrl, dir = null, cfg = xhr)
            ?.let {
                Logs.log("NetStream", "terabox: probe xhr errno=0 entries=${it.size} for $origin")
                return boxList(origin, key, jsToken, jar, sekey, sig, ids, pageUrl, xhr)
            }
        Logs.log("NetStream", "terabox: probe xhr refused for $origin")
        val noToken = BoxCallCfg(skipToken = true)
        boxListPage(origin, key, jsToken, jar, sekey, sig, ids, pageUrl, dir = null, cfg = noToken)
            ?.let {
                Logs.log("NetStream", "terabox: probe notoken errno=0 entries=${it.size} for $origin")
                return boxList(origin, key, jsToken, jar, sekey, sig, ids, pageUrl, noToken)
            }
        Logs.log("NetStream", "terabox: probe notoken refused for $origin")
        boxListPage(origin, key, jsToken, jar, sekey, sig, null, pageUrl, dir = null)
            ?.let {
                Logs.log("NetStream", "terabox: probe noids errno=0 entries=${it.size} for $origin")
                return boxList(origin, key, jsToken, jar, sekey, sig, null, pageUrl)
            }
        Logs.log("NetStream", "terabox: probe noids refused for $origin")
        val browserCfg = BoxCallCfg(ua = Http.UA)
        boxListPage(origin, key, jsToken, jar, sekey, sig, ids, pageUrl, dir = null, cfg = browserCfg)
            ?.let {
                Logs.log("NetStream", "terabox: probe browser-ua errno=0 entries=${it.size} for $origin")
                return boxList(origin, key, jsToken, jar, sekey, sig, ids, pageUrl, browserCfg)
            }
        Logs.log("NetStream", "terabox: probe browser-ua refused for $origin")
        // The canonical backend with its own session: some mirrors gate the
        // list while the canonical host answers the same key.
        val canon = "https://www.terabox.com"
        if (canon != origin) {
            val cjar = LinkedList<String>()
            if (cjar.none { it.substringBefore('=').trim().equals("browserid", ignoreCase = true) }) {
                cjar.add("browserid=" + freshBrowserId())
            }
            val cpage = fetchText("$canon/s/$key", BOX_UA, cjar, referer = canon)
            val ctoken = if (!cpage.isNullOrBlank()) jsTokenOf(cpage) else ""
            val cmeta = fetchText(
                "$canon/api/shorturlinfo?shorturl=$key&root=1",
                BOX_UA,
                cjar,
                referer = "$canon/s/$key",
            )
            val cerrno = runCatching { JSONObject(cmeta ?: "").optInt("errno", -1) }.getOrDefault(-1)
            Logs.log(
                "NetStream",
                "terabox: probe canon shorturlinfo errno=$cerrno token=" +
                    (if (ctoken.isNotBlank()) "yes" else "none"),
            )
            if (cerrno == 0 && !cmeta.isNullOrBlank()) {
                val csekey = boxSekeyOf(cmeta)
                val cids = boxShareIdsOf(cmeta)
                val csig = if (!cpage.isNullOrBlank()) boxSignOf(cpage) else null
                boxListPage(canon, key, ctoken, cjar, csekey, csig, cids, "$canon/s/$key", dir = null)
                    ?.let {
                        Logs.log("NetStream", "terabox: probe canon errno=0 entries=${it.size} for $origin")
                        return boxList(canon, key, ctoken, cjar, csekey, csig, cids, "$canon/s/$key")
                    }
                Logs.log("NetStream", "terabox: probe canon refused for $origin")
            }
        }
        return null
    }

    private suspend fun boxBundleRecon(
        html: String,
        webJar: List<String>,
        pageUrl: String,
        origin: String,
        meta: String?,
    ) {
        if (!meta.isNullOrBlank()) {
            val keys = runCatching {
                JSONObject(meta).keys().asSequence().toList()
            }.getOrDefault(emptyList())
            Logs.log("NetStream", "terabox: recon shorturlinfo keys=" + keys.joinToString(",").take(300))
        }
        val jar = LinkedList(webJar.distinct())
        val srcs = Regex("""<script[^>]+src="([^"]+)"""").findAll(html)
            .map { it.groupValues[1].trim() }.filter { it.isNotBlank() }.toList()
        Logs.log("NetStream", "terabox: recon scripts=" + srcs.size)
        val title = Regex("""<title[^>]*>(.*?)</title>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)?.trim()?.take(160)
        if (!title.isNullOrBlank()) {
            Logs.log("NetStream", "terabox: recon page title=" + title)
        }
        val domNeedles = listOf("fs_id", "server_filename", "dlink", "file_name", "playUrl", "video_url", "downloadLink")
        var domShown = 0
        for (n in domNeedles) {
            if (domShown >= 12) break
            var idx = html.indexOf(n, ignoreCase = true)
            var takes = 0
            while (idx >= 0 && takes < 2 && domShown < 12) {
                val lo = (idx - 60).coerceAtLeast(0)
                val hi = (idx + n.length + 60).coerceAtMost(html.length)
                val ctx = html.substring(lo, hi).replace(Regex("\\s+"), " ")
                Logs.log("NetStream", "terabox: recon [dom:" + n + "] ..." + ctx.take(220) + "...")
                domShown++
                takes++
                idx = html.indexOf(n, idx + 1, ignoreCase = true)
            }
        }
        val needles = listOf("share/list", "jsToken", "getJsToken", "getSign", "shorturlinfo", "dlink", "/api/")
        var shown = 0
        var fetched = 0
        for (raw in srcs) {
            if (shown >= 15) break
            // Page-relative bundle paths (no leading slash) resolve against
            // the mirror root — skipping them blinded the last round.
            val abs = when {
                raw.startsWith("http") -> raw
                raw.startsWith("//") -> "https:$raw"
                raw.startsWith("/") -> origin + raw
                raw.endsWith(".js") || raw.contains(".js?") -> origin + "/" + raw.trimStart('/')
                else -> continue
            }
            // The client's own code lives on its CDN (teraboxcdn), not the
            // mirror host — same-origin-only missed it entirely last round.
            val host = abs.substringAfter("://").substringBefore('/')
            if (!abs.startsWith(origin) && !host.endsWith("teraboxcdn.com")) continue
            if (!abs.substringBefore('?').endsWith(".js")) continue
            val js = withTimeoutOrNull(10_000) {
                fetchText(abs, BOX_UA, jar, referer = pageUrl)
            } ?: continue
            if (js.length > 2_000_000) continue
            fetched++
            for (n in needles) {
                if (shown >= 15) break
                var idx = js.indexOf(n, ignoreCase = true)
                var takes = 0
                while (idx >= 0 && takes < 2 && shown < 15) {
                    val lo = (idx - 60).coerceAtLeast(0)
                    val hi = (idx + n.length + 60).coerceAtMost(js.length)
                    val ctx = js.substring(lo, hi).replace(Regex("\\s+"), " ")
                    Logs.log("NetStream", "terabox: recon [$n] ..." + ctx.take(220) + "...")
                    shown++
                    takes++
                    idx = js.indexOf(n, idx + 1, ignoreCase = true)
                }
            }
        }
        Logs.log("NetStream", "terabox: recon bundles=" + fetched + "/" + srcs.size)
    }

    private fun isBoxFolder(e: JSONObject): Boolean =
        e.optInt("isdir", 0) == 1 || e.optInt("category", 0) == 6

    private fun looksLikeVideo(name: String): Boolean {
        val lower = name.lowercase()
        if (VIDEO_EXTENSIONS.any { lower.endsWith(it) }) return true
        // A name the host did not give an extension to is still worth handing to
        // the player when the share holds nothing else — see the caller.
        return !lower.contains('.')
    }

    // ---- mdisk-class download pages (diskwala) ----

    private fun isMdiskLink(url: String): Boolean {
        val host = hostOf(url).lowercase()
        return MDISK_HOSTS.any { host.contains(it) }
    }

    private suspend fun mdisk(url: String): List<StreamSource> {
        val host = hostOf(url).lowercase()
        // Only the host whose API shape is known is attempted; every other
        // mdisk-class page keeps the generic stack.
        if (!host.contains("diskwala")) return emptyList()
        // /app/<id> share pages (and any last-segment id form).
        val id = url.trim().substringBefore('?').substringBefore('#').trimEnd('/')
            .substringAfterLast('/').takeIf {
                it.length >= 8 && it.all { c -> c.isLetterOrDigit() }
            } ?: return emptyList()
        val api = "https://ddudapidd.diskwala.com/api/v1"
        for (body in listOf("{\"id\":\"$id\"}", "{\"file_id\":\"$id\"}")) {
            signedDiskwalaPost("$api/file/sign", body)?.let { signed ->
                pickSignedUrl(signed)?.let { return listOf(sourceOf(it, id)) }
            }
        }
        return emptyList()
    }

    /** One signed Diskwala API call (see above): the canonical SHA-256 is sent
     *  both as hex and as base64, since only the site's own signer knows which
     *  encoding its cryptogram wraps. Null unless the server answers 200 with
     *  a body. */
    private suspend fun signedDiskwalaPost(apiUrl: String, body: String): String? =
        withContext(Dispatchers.IO) {
            val path = runCatching {
                java.net.URI(apiUrl).path.removePrefix("/api/v1")
            }.getOrDefault("/file/sign")
            val ts = System.currentTimeMillis().toString()
            val canonical = "POST $path | params= | body=$body | ts=$ts"
            val digest = runCatching {
                val md = java.security.MessageDigest.getInstance("SHA-256")
                md.digest(canonical.toByteArray(Charsets.UTF_8))
            }.getOrNull() ?: return@withContext null
            val hex = digest.joinToString("") { "%02x".format(it) }
            val b64 = android.util.Base64.encodeToString(digest, android.util.Base64.NO_WRAP)
            for (crypt in listOf(hex, b64)) {
                val out = runCatching {
                    Http.postString(
                        apiUrl, body,
                        mapOf("Appicrypt" to crypt, "Appicrypt-ts" to ts),
                    )
                }.getOrNull()
                if (!out.isNullOrBlank()) return@withContext out
            }
            null
        }

    /** The playable URL inside a Diskwala sign answer: the known keys first,
     *  then the first http(s) URL in the body. */
    private fun pickSignedUrl(body: String): String? {
        runCatching {
            val o = JSONObject(body)
            for (k in listOf("url", "file_url", "download_url", "stream_url", "dlink", "signed_url")) {
                o.optString(k).takeIf { it.startsWith("http") }?.let { return it }
                val nested = o.optJSONObject(k)
                if (nested != null) {
                    for (k2 in listOf("url", "file_url", "download_url", "stream_url")) {
                        nested.optString(k2).takeIf { it.startsWith("http") }?.let { return it }
                    }
                }
            }
        }
        return Regex("""https?:\\/\\/[^"'\s\\]+""").find(body)?.value
            ?.replace("\\/", "/")
            ?.takeIf { it.startsWith("http") }
    }

    /** `jsToken` out of a box home page: the web app parks it in a
     *  `templateData` blob, and (older/other mirrors) in a `window.jsToken`
     *  line; both spellings are in the wild. */
    private fun jsTokenOf(html: String): String {
        Regex("\"jsToken\"\\s*:\\s*\"([^\"]+)\"").find(html)?.let { return it.groupValues[1] }
        Regex("'jsToken'\\s*:\\s*'([^']+)'").find(html)?.let { return it.groupValues[1] }
        Regex("window\\.jsToken\\s*=\\s*['\"]([^'\"]+)['\"]").find(html)
            ?.let { return it.groupValues[1] }
        return ""
    }

    private fun boxHeaders(origin: String, jar: List<String>): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        headers["User-Agent"] = BOX_UA
        headers["Referer"] = "$origin/"
        if (jar.isNotEmpty()) headers["Cookie"] = jar.joinToString("; ")
        return headers
    }

    // ---- fetching ----

    /** One GET with explicit headers, its `Set-Cookie`s folded into [jar], and
     *  the body as text. Null on any failure — every caller here treats a dead
     *  host as "this stage found nothing". */
    private fun fetchText(
        url: String,
        ua: String?,
        jar: MutableList<String>,
        referer: String? = null,
        extra: Map<String, String> = emptyMap(),
    ): String? {
        val headers = LinkedHashMap<String, String>()
        if (!ua.isNullOrBlank()) headers["User-Agent"] = ua
        if (!referer.isNullOrBlank()) headers["Referer"] = referer
        if (jar.isNotEmpty()) headers["Cookie"] = jar.joinToString("; ")
        headers["Accept"] = "*/*"
        extra.forEach { (k, v) -> if (k.isNotBlank() && v.isNotBlank()) headers[k] = v }
        return try {
            Http.get(url, headers).use { response ->
                response.headers.values("Set-Cookie").forEach { raw ->
                    val pair = raw.substringBefore(';').trim()
                    if (pair.isNotBlank()) jar.removeAll { it.substringBefore('=') == pair.substringBefore('=') }
                    if (pair.isNotBlank()) jar.add(pair)
                }
                if (response.isSuccessful) response.body?.string() else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** The Content-Type a URL answers with, from a one-byte range request (so a
     *  multi-gigabyte file is not downloaded to find out). Null when the host
     *  refuses ranges — in which case nothing is known and the caller falls
     *  through rather than guessing. */
    private fun sniff(url: String): String? = try {
        Http.get(url, mapOf("Range" to "bytes=0-1")).use { response ->
            response.header("Content-Type")
        }
    } catch (e: Exception) {
        null
    }
}
