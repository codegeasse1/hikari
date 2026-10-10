package com.hikari.app.data

import com.hikari.app.cs3.FallbackResolver
import com.hikari.app.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.URLEncoder
import java.util.IdentityHashMap
import java.util.LinkedList

/**
 * A link the user pasted into the IPTV tab as a NETWORK STREAM, resolved to
 * something the player can actually open.
 *
 * A playlist's channels are URLs the provider is told are already playable, and
 * for an M3U panel that is true. A network stream is the other case: the user has
 * a link to a *file* on a host — an m3u8, a Terabox share, a Telebox box, an
 * MDisk file, a Diskwala/HubCloud-class download page — and what stands behind
 * the link has to be worked out before it can be played ("add network stream …
 * so user can add any link to stream … m3u8 link, terabox link, telebox link,
 * mdisk, diskwala and many more"). That work is here, so that everything else
 * about such an entry is an ordinary IPTV provider: it lists in the tab, its
 * channels (usually exactly one) are browseable, and the player asks it for
 * streams like any other.
 *
 * **The order of the attempts, and why.** Cheapest and most certain first, so a
 * link that is already playable never pays for a network round trip it does not
 * need, and each stage is bounded by its own timeout:
 *
 *  1. a media URL by its own shape (`.m3u8`, `.mp4`, …) — answered with no
 *     request at all;
 *  2. the two hosts whose flow is a two-line transformation (Google Drive, by
 *     [Http.normalizeDriveUrl], and Pixeldrain, whose API path is the share path
 *     with `/api/file/…?download`);
 *  3. Terabox and the box hosts that run the same API (Telebox, 1024tera, …) —
 *     see [terabox], which is the one flow here that is a real protocol;
 *  3b. MDisk-class download pages with a known API shape (Diskwala) — see
 *     [mdisk]; every other mdisk-class page keeps the generic stack;
 *  4. Hikari's own extraction stack ([FallbackResolver]): it fetches the page,
 *     unpacks packed player JS, scans for HLS/MP4, runs the dood/rumble dances,
 *     probes extensionless HLS and finally hands the URL to CloudStream's whole
 *     extractor registry — which is what covers "and many more": any host an
 *     installed CloudStream plugin knows how to open works here too;
 *  5. a Content-Type probe, for a direct-ish link with no extension;
 *  6. the link itself.
 *
 * That last step is deliberate. A link that nothing could open is still handed
 * to the player exactly as the user wrote it, rather than answered with "no
 * playable sources": the URL is the user's own, the player is the thing that
 * can say what a machine-readable resolver cannot ("this is a page, not a
 * video"), and dropping it would look like the app had lost the entry.
 */
object NetworkStream {

    /**
     * How a network stream is marked on its [ProviderConfig].
     *
     * The entry IS an IPTV provider — same listing, same browsing, same play
     * path — and this is what tells the two apart: [ProviderConfig.extra] is the
     * one free-form field a row already has, so a stream entry needs no new
     * storage schema and an older build reading one simply treats it as a
     * one-channel playlist (which is exactly what it would have been).
     */
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

    /**
     * True when [url] is a single playable file/stream address (a direct m3u8, mp4/mkv,
     * and friends) rather than a playlist of channels.
     *
     * The m3u extension is deliberately NOT on this list: it is the playlist format IPTV
     * panels speak, while m3u8/media extensions name one stream. The playlist-adding flows
     * refuse these so a lone video link can never become a one-channel
     * All-channels/Ungrouped folder that plays as live TV — Network stream mode
     * is the path that resolves and plays them as the files they are.
     */
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
        "nepnepbox", "telebox", "mirrobox", "shibabox",
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

    /**
     * Every playable source behind [rawUrl], ready for the player.
     *
     * Never throws and never returns an empty list for a link that has a scheme:
     * see the class doc for the last-resort step. [label] is the entry's name
     * (the channel's), used for the source's own label in the player's server
     * list.
     */
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
        val chosen = sources.ifEmpty { listOf(sourceOf(url, name)) }
        chosen.distinctBy { it.url }.map {
            it.copy(
                headers = withStreamHeaders(it.headers, url),
                provider = ProviderType.IPTV.groupLabel,
                providerId = providerId,
                providerName = providerName,
            )
        }
    }

    /**
     * The headers a BROWSER would send for this stream, filled in wherever the
     * link itself did not say.
     *
     * A "network stream" is an address copied out of a page — the player page,
     * a share, a copied video address — and those hosts hotlink-protect it: the
     * CDN checks the request's Referer and User-Agent and answers 403 without
     * them, which the player reports as
     * `ExoPlaybackException [ERROR_CODE_IO_BAD_HTTP_STATUS] 403` on a playlist
     * that plays fine in the browser it was copied from.
     *
     * The page's ORIGIN is the right Referer: [pageUrl] is what the user pasted
     * (before any extraction), so it is the page the link came from — and when
     * they pasted the stream address itself, it is the stream's own origin, which
     * is what such hosts check for. Headers an extractor already chose are kept
     * untouched (the box hosts' own User-Agent/Referer/Cookie, for instance).
     *
     * Nothing here can break a link that already works: the player drops headers
     * one step at a time when a server rejects them (see
     * PlayerActivity.headerVariant), so a wrong guess costs one retry while a
     * missing one costs the whole play.
     */
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
        // A torrent FILE is not playable bytes — the player would be handed
        // the file itself and fail. Parse it into the info hash (+ trackers)
        // the torrent engine needs instead.
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
            // A share link (…/s/<key>) resolves ONLY through the box API: the
            // generic stack below would hand back the share page itself as a
            // "server", and the player cannot parse HTML as video
            // (CONTAINER_UNSUPPORTED). Returning the API result directly keeps
            // a refused list honest ("no servers", errno in the log) instead
            // of a fake row that dies in the player.
            if (boxShareKey(url) != null) return stage { terabox(url) }.orEmpty()
            val files = stage { terabox(url) }.orEmpty()
            if (files.isNotEmpty()) return files
        }

        // 3b. MDisk-class download pages with a known API shape (Diskwala).
        if (isMdiskLink(url)) {
            val files = stage { mdisk(url) }.orEmpty()
            if (files.isNotEmpty()) return files
        }

        // 4. Hikari's own extraction stack — a page with embeds, then the page
        //    treated as a single embed, then CloudStream's whole registry. Each
        //    of the three is bounded inside FallbackResolver as well.
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


    /**
     * Public for the extension engines: turn a torrent FILE url into its
     * torrent source (info hash + trackers parsed out of the file), or null
     * when the file cannot be fetched or parsed. Bounded inside, never throws.
     */
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
    private fun boxShareKey(url: String): String? {
        val direct = Regex("""/s/([A-Za-z0-9_-]{5,})""").find(url)?.groupValues?.get(1)
        if (!direct.isNullOrBlank()) return direct
        val param = Regex("""[?&]surl=([A-Za-z0-9_-]{5,})""").find(url)?.groupValues?.get(1)
            ?: return null
        return if (param.startsWith("1")) param else "1$param"
    }

    /**
     * A Terabox-family share, as a list of playable files.
     *
     * The flow is the service's own web API, in the order it needs:
     *
     *  1. the host's home page, for `jsToken` (an anti-bot token the API refuses
     *     requests without) and for the cookies it sets;
     *  2. `/api/shorturlinfo?shorturl=<key>&root=1` — the share's metadata,
     *     including its `sekey`;
     *  3. `/share/list?…&shorturl=<key>&root=1&sekey=<sekey>` — its entries,
     *     `dlink` per file (a list call without the share's own `sekey` is
     *     refused with errno 105 — that refusal is the whole failure class).
     *
     * Everything is asked of the link's OWN host: the box service lives on a
     * dozen mirror domains (terabox.com, 1024tera.com, 4funbox.com, telebox.link
     * …), they are all the same API, and going through a fixed host would mean
     * every link from another mirror failed. A host that answers the list call
     * with the "verify" errno (`4000020`) is retried once with freshly minted
     * cookies, which is what that errno means.
     *
     * The returned sources carry the box user agent, the host as Referer and the
     * session cookie: a `dlink` is hotlink-protected and answers a bare request
     * with an error page rather than the file.
     */
    private suspend fun terabox(url: String): List<StreamSource> {
        val key = boxShareKey(url) ?: return emptyList()
        val origin = originOf(url)
        val jar = LinkedList<String>()
        // Tokens and cookies come from the SHARE page first: it sets the
        // share cookies and (on most mirrors) embeds the jsToken, while /main
        // is the fallback when the share page carries neither (bot-walled
        // HTML, a redirect that dropped the token).
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
        val tokens = fetchText(
            "$origin/api/shorturlinfo?shorturl=$key&root=1",
            BOX_UA,
            jar,
            referer = origin,
        ) ?: run {
            Logs.log("NetStream", "terabox: shorturlinfo unreachable for $origin")
            return emptyList()
        }
        val tokensErrno = runCatching { JSONObject(tokens).optInt("errno", -1) }.getOrDefault(-1)
        var sekey = boxSekeyOf(tokens)
        Logs.log(
            "NetStream",
            "terabox: shorturlinfo errno=$tokensErrno token=" +
                (if (token.isNotBlank()) "yes" else "none") +
                " sekey=" + (if (!sekey.isNullOrBlank()) "yes" else "none"),
        )
        if (boxShareLocked(tokens)) {
            Logs.log("NetStream", "terabox: share needs a password/extraction code for $origin")
            return emptyList()
        }

        var list = boxList(origin, key, token, jar, sekey)
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
            val retryTokens = fetchText(
                "$origin/api/shorturlinfo?shorturl=$key&root=1",
                BOX_UA,
                retryJar,
                referer = origin,
            )
            if (!retryTokens.isNullOrBlank() &&
                runCatching { JSONObject(retryTokens).optInt("errno", -1) }.getOrDefault(-1) == 0
            ) {
                sekey = boxSekeyOf(retryTokens) ?: sekey
            }
            list = boxList(origin, key, jsTokenOf(retryHome), retryJar, sekey)
        }
        val entries = list ?: return emptyList()
        // The share's metadata call is not needed for the dlinks, but it is what
        // tells a share that is gone from one whose root is an empty folder.
        val shareAlive = runCatching { JSONObject(tokens).optInt("errno", -1) == 0 }
            .getOrDefault(false)
        if (entries.isEmpty() && !shareAlive) return emptyList()

        val headers = boxHeaders(origin, jar)
        val files = ArrayList<StreamSource>()
        for (e in entries) {
            val dlink = e.optString("dlink").trim()
            if (dlink.isBlank() || !dlink.startsWith("http")) continue
            val file = e.optString("server_filename").ifBlank { e.optString("filename") }.trim()
            if (file.isNotBlank() && !looksLikeVideo(file)) continue
            files += sourceOf(dlink, file.ifBlank { key }, headers = headers)
            if (files.size >= 8) break
        }
        return files
    }

    /** The share's `sekey` inside a shorturlinfo answer — the list call's
     *  password for THAT share. Top level first, then the first
     *  `shorturlinfo` entry, then a `data` object; null when absent. */
    private fun boxSekeyOf(tokens: String): String? {
        val root = runCatching { JSONObject(tokens) }.getOrNull() ?: return null
        root.optString("sekey").takeIf { it.isNotBlank() }?.let { return it }
        root.optJSONArray("shorturlinfo")?.optJSONObject(0)
            ?.optString("sekey")?.takeIf { it.isNotBlank() }?.let { return it }
        root.optJSONObject("data")?.optString("sekey")
            ?.takeIf { it.isNotBlank() }?.let { return it }
        return null
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

    /** `/share/list` for one share — the account of the entries at its root,
     *  plus (bounded) the video files inside any folder it holds. */
    private suspend fun boxList(
        origin: String,
        key: String,
        jsToken: String,
        jar: MutableList<String>,
        sekey: String?,
    ): List<JSONObject>? {
        val root = boxListPage(origin, key, jsToken, jar, sekey, dir = null) ?: return null
        val out = ArrayList<JSONObject>(root)
        // A share of a season is a folder of episodes: one level down, and at
        // most a few folders, keeps a huge share from turning into a hundred
        // requests.
        var folders = 0
        for (e in root) {
            if (folders >= 3) break
            if (!isBoxFolder(e)) continue
            val dir = e.optString("path").trim()
            if (dir.isBlank()) continue
            folders++
            out += boxListPage(origin, key, jsToken, jar, sekey, dir = dir).orEmpty()
        }
        return out
    }

    private suspend fun boxListPage(
        origin: String,
        key: String,
        jsToken: String,
        jar: MutableList<String>,
        sekey: String?,
        dir: String?,
    ): List<JSONObject>? {
        val query = buildString {
            append("$origin/share/list?$BOX_APP_QUERY")
            if (jsToken.isNotBlank()) append("&jsToken=").append(jsToken)
            if (!sekey.isNullOrBlank()) append("&sekey=").append(URLEncoder.encode(sekey, "UTF-8"))
            append("&shorturl=").append(key)
            append("&by=name&order=asc&num=20000&page=1")
            if (dir.isNullOrBlank()) {
                append("&root=1")
            } else {
                append("&dir=").append(URLEncoder.encode(dir, "UTF-8"))
            }
        }
        val text = fetchText(query, BOX_UA, jar, referer = origin) ?: return null
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

    /**
     * Diskwala / MDisk-class download pages. Their file API signs every
     * request (Appicrypt headers over a canonical SHA-256 — the site's own
     * bundle computes it in a signer module). Best-effort: the plain-hash
     * equivalents are attempted, and anything the server refuses falls through
     * to the generic extraction stack below, so a wrong guess costs nothing.
     */
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
            // Canonical form, exactly like the site's own signer builds it:
            // "METHOD path | params=<sorted JSON or empty> | body=<sorted JSON
            // or empty> | ts=<ms>". Our bodies are single-key compact JSON, so
            // they already match the signer's sorted form.
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
    ): String? {
        val headers = LinkedHashMap<String, String>()
        if (!ua.isNullOrBlank()) headers["User-Agent"] = ua
        if (!referer.isNullOrBlank()) headers["Referer"] = referer
        if (jar.isNotEmpty()) headers["Cookie"] = jar.joinToString("; ")
        headers["Accept"] = "*/*"
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
