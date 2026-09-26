package com.hikari.app.data

import com.hikari.app.cs3.FallbackResolver
import com.hikari.app.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.URLEncoder
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

    /** Anything with a scheme is worth trying; the resolver is what decides. */
    fun isStreamLink(url: String): Boolean {
        val u = url.trim()
        return u.startsWith("http://") || u.startsWith("https://")
    }

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
        val url = rawUrl.trim()
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
        // 1. Already a stream: nothing to resolve, and asking a CDN to prove it
        //    would only add a round trip to every play.
        if (isDirectMedia(url)) return listOf(sourceOf(url, label))

        // 2. Two hosts whose transformation is mechanical.
        val drive = Http.normalizeDriveUrl(url)
        if (drive != url) return listOf(sourceOf(drive, label))
        pixeldrain(url)?.let { return listOf(sourceOf(it, label)) }

        // 3. The box hosts (Terabox / Telebox / …).
        if (isBoxLink(url)) {
            val files = stage { terabox(url) }.orEmpty()
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
     *  2. `/api/shorturlinfo?shorturl=<key>&root=1` — the share's metadata;
     *  3. `/share/list?…&shorturl=<key>&root=1` — its entries, `dlink` per file.
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
        val home = fetchText("$origin/main", BOX_UA, jar) ?: return emptyList()
        val tokens = fetchText(
            "$origin/api/shorturlinfo?shorturl=$key&root=1",
            BOX_UA,
            jar,
            referer = origin,
        ) ?: return emptyList()

        var list = boxList(origin, key, jsTokenOf(home), jar)
        if (list == null) {
            // A fresh page read (new cookies + token) is what a "verify" answer
            // asks for; one retry is enough for a challenge that is really just
            // a stale session.
            val retryHome = fetchText("$origin/main", BOX_UA, LinkedList()) ?: home
            list = boxList(origin, key, jsTokenOf(retryHome), LinkedList())
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

    /** `/share/list` for one share — the account of the entries at its root,
     *  plus (bounded) the video files inside any folder it holds. */
    private suspend fun boxList(
        origin: String,
        key: String,
        jsToken: String,
        jar: MutableList<String>,
    ): List<JSONObject>? {
        val root = boxListPage(origin, key, jsToken, jar, dir = null) ?: return null
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
            out += boxListPage(origin, key, jsToken, jar, dir = dir).orEmpty()
        }
        return out
    }

    private suspend fun boxListPage(
        origin: String,
        key: String,
        jsToken: String,
        jar: MutableList<String>,
        dir: String?,
    ): List<JSONObject>? {
        val query = buildString {
            append("$origin/share/list?$BOX_APP_QUERY")
            if (jsToken.isNotBlank()) append("&jsToken=").append(jsToken)
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
        if (json.optInt("errno", -1) != 0) return null
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
