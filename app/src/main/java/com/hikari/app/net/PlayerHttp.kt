package com.hikari.app.net

import okhttp3.ConnectionPool
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okio.buffer
import okio.source

/**
 * The one OkHttp client every video byte flows through.
 *
 * The player used to build a throwaway client per [com.hikari.app.player.PlayerActivity]
 * (and [StreamProbe] a separate one), so every playback session started with a
 * cold DNS cache, a cold TLS session and an empty connection pool — the first
 * segment of every stream paid a full handshake before a single byte moved,
 * which is exactly the kind of startup stall that reads as "buffering".
 *
 * One shared, playback-tuned client instead:
 *  - [StreamProbe] derives its own client from this one via `newBuilder()`,
 *    which shares this connection pool + dispatcher, so the CDN connection the
 *    probe just opened (while it classified the stream) is still warm and is
 *    REUSED by media3's OkHttpDataSource for the first media request — no
 *    second handshake, no repeat DNS;
 *  - a bigger pool + per-host request budget lets HLS/DASH pull
 *    audio/video/subtitle segments (and seek prefetch) in parallel instead of
 *    the dispatcher queueing them behind each other;
 *  - a 20 s read timeout (down from 30 s) bounds how long a single hung CDN
 *    socket can stall the stream before the load-error policy opens a fresh
 *    connection and resumes at the current position via a Range request.
 *
 * The timeouts here are the *playback* timeouts. The probe deliberately
 * overrides them with much shorter ones so a dead wrapper page can never hold
 * up a source search.
 */
object PlayerHttp {

    /**
     * Shared cookie jar for CS3 extraction and playback. CloudStream's extractor may
     * establish a session cookie before returning a signed media URL (Fastream is a
     * common example). Keeping the jar shared is safe; the player itself otherwise
     * remains the stable 0.10.66 networking path.
     */
    val cookieJar: CookieJar = object : CookieJar {
        private val store = ConcurrentHashMap<String, List<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (cookies.isEmpty()) return
            val host = url.topPrivateDomain() ?: url.host
            val existing = store[host].orEmpty().associateBy { it.name }.toMutableMap()
            for (cookie in cookies) existing[cookie.name] = cookie
            store[host] = existing.values.toList()
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val host = url.topPrivateDomain() ?: url.host
            val all = store[host].orEmpty() + store[url.host].orEmpty()
            return all.filter { it.matches(url) }.distinctBy { it.name }
        }
    }

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(cookieJar)
            // Playback must use the same Cloudflare/session-aware path as
            // extraction. Some hosts return a playable URL only after the
            // extractor has established clearance cookies; a separate plain
            // OkHttp client made ExoPlayer lose that session and produced a
            // black/no-video player even though the source was resolved.
            .addInterceptor { chain -> CloudflareVerifier.intercept(chain) }
            .dns(DohDns)
            // Give up on a dead host quickly: failover to the next server must
            // not be gated on a long TCP timeout.
            .connectTimeout(15, TimeUnit.SECONDS)
            // Per-socket-read cap. A CDN that stops delivering bytes is
            // retried on a fresh connection (resuming via a Range request)
            // rather than left "buffering" indefinitely.
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            // Many aggregator links bounce https → http (HubCloud et al.).
            .followSslRedirects(true)
            .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
            .dispatcher(
                Dispatcher().apply {
                    maxRequests = 64
                    maxRequestsPerHost = 16
                }
            )
            .build()
    }

    /**
     * CloudStream-parity playback client, used ONLY for CloudStream sources
     * (see PlayerActivity): the pool, dispatcher, timeouts, DNS and the shared
     * extraction/playback cookie jar of [client], but WITHOUT the
     * Cloudflare-verifier interceptor — the CloudStream app plays video bytes
     * through its plain baseClient, and a clearance-cookie mutation on a
     * signed CDN URL is a 403-vs-play difference no header tweak can fix.
     * Every other engine keeps playing through [client], untouched.
     */
    val plainClient: OkHttpClient by lazy {
        client.newBuilder().apply { interceptors().clear() }.build()
    }

    /**
     * Loopback-only playback client: [plainClient] plus a gunzip-magic pass.
     * An extension's own local proxy answers HTTP 200 labelled `mpegurl`
     * while the body is GZIP bytes (proven by the `loopback diag` line: first
     * bytes 1F 8B, never #EXTM3U) — ExoPlayer compares the first bytes and
     * dies malformed. The pass peeks the first two bytes of every response
     * and, ONLY on gzip magic, swaps the body for a streaming gunzip of it
     * (length unknown from there, encoding header dropped). Plain responses
     * pass through untouched — the peek buffers, it never consumes — so this
     * cannot break a healthy proxy. Playlist AND segments flow through the
     * same OkHttpDataSource factory, so one pass covers both.
     */
    /** Upstream headers a loopback proxy declared in its /m3u8 URL
     *  (referer/useragent query params), remembered per loopback authority so
     *  the SAME headers ride on that proxy's /segment fetches too: segment
     *  URLs carry no query params of their own, and the proxy forwards
     *  whatever the player sent upstream — without them its upstream fetch
     *  dies and the manifest it hands back is a single fallback URL line
     *  instead of #EXTM3U (the MissAV "plays in the reference apps,
     *  malformed here" class). */
    private val loopbackHeadersByAuthority = ConcurrentHashMap<String, Map<String, String>>()

    private fun rememberLoopbackHeaders(url: HttpUrl) {
        if (url.host != "localhost" && url.host != "127.0.0.1" && url.host != "::1" && url.host != "[::1]") return
        var ref: String? = null
        var ua: String? = null
        for (i in 0 until url.querySize) {
            when (url.queryParameterName(i).lowercase()) {
                "referer", "referrer" -> if (ref == null) ref = url.queryParameterValue(i)?.takeIf { it.isNotBlank() }
                "useragent", "user-agent" -> if (ua == null) ua = url.queryParameterValue(i)?.takeIf { it.isNotBlank() }
            }
        }
        if (ref == null && ua == null) return
        val map = LinkedHashMap<String, String>()
        if (ref != null) map["Referer"] = ref
        if (ua != null) map["User-Agent"] = ua
        loopbackHeadersByAuthority[url.host + ":" + url.port] = map
    }

    private fun isLoopbackUrl(url: HttpUrl): Boolean =
        url.host == "localhost" || url.host == "127.0.0.1" || url.host == "::1" || url.host == "[::1]"

    val loopbackClient: OkHttpClient by lazy {
        plainClient.newBuilder().addInterceptor { chain ->
            var req = chain.request()
            val reqUrl = req.url
            val isLoopback = isLoopbackUrl(reqUrl)
            if (isLoopback) {
                // Segments inherit the /m3u8 URL's declared upstream headers.
                rememberLoopbackHeaders(reqUrl)
                val remembered = loopbackHeadersByAuthority[reqUrl.host + ":" + reqUrl.port]
                if (remembered != null) {
                    val b = req.newBuilder()
                    var changed = false
                    for ((k, v) in remembered) {
                        if (req.header(k) == null) {
                            runCatching { b.header(k, v) }.onSuccess { changed = true }
                        }
                    }
                    if (changed) req = b.build()
                }
            }
            val resp = chain.proceed(req)
            val body = resp.body
            val gunzipped = runCatching {
                if (body == null) return@runCatching null
                val src = body.source()
                src.request(2)
                val buf = src.buffer
                if (buf.size < 2 || buf[0] != 0x1f.toByte() || buf[1] != 0x8b.toByte()) return@runCatching null
                val gz = java.util.zip.GZIPInputStream(src.inputStream())
                object : okhttp3.ResponseBody() {
                    override fun contentType(): okhttp3.MediaType? = body.contentType()
                    override fun contentLength(): Long = -1L
                    override fun source(): okio.BufferedSource = gz.source().buffer()
                }
            }.getOrNull()
            if (gunzipped != null) return@addInterceptor resp.newBuilder().removeHeader("Content-Encoding").body(gunzipped).build()
            // Single-URL fallback repair: the proxy answered 200/mpegurl with
            // a bare `http://…/segment?…` line (its upstream fetch died) — no
            // ExoPlayer will ever parse that as HLS. Wrap the named URL(s) in
            // a minimal manifest so the server fails over to playing instead
            // of malformed-manifest on every quality. Genuine manifests start
            // with #EXTM3U and never enter here.
            val repaired = runCatching {
                if (body == null) return@runCatching null
                if (!isLoopback) return@runCatching null
                if (!reqUrl.encodedPath.contains("m3u8", ignoreCase = true)) return@runCatching null
                val src = body.source()
                src.request(4)
                val buf = src.buffer
                if (buf.size < 4) return@runCatching null
                if (buf[0] != 0x68.toByte() || buf[1] != 0x74.toByte() ||
                    buf[2] != 0x74.toByte() || buf[3] != 0x70.toByte()
                ) return@runCatching null
                src.request(32_768)
                val text = buf.clone().readUtf8().trim()
                if (!text.startsWith("http", ignoreCase = true) || text.contains("#EXTM3U")) return@runCatching null
                val urls = text.lines().map { it.trim() }
                    .filter { it.startsWith("http://") || it.startsWith("https://") }.take(50)
                if (urls.isEmpty()) return@runCatching null
                val sb = StringBuilder(
                    "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:10\n#EXT-X-MEDIA-SEQUENCE:0\n"
                )
                for (u in urls) sb.append("#EXTINF:10.0,\n").append(u).append('\n')
                sb.append("#EXT-X-ENDLIST\n")
                val bytes = sb.toString().toByteArray(Charsets.UTF_8)
                object : okhttp3.ResponseBody() {
                    override fun contentType(): okhttp3.MediaType? = body.contentType()
                    override fun contentLength(): Long = bytes.size.toLong()
                    override fun source(): okio.BufferedSource = okio.Buffer().write(bytes)
                }
            }.getOrNull()
            if (repaired != null) {
                runCatching { body?.close() }
                return@addInterceptor resp.newBuilder().removeHeader("Content-Encoding").body(repaired).build()
            }
            resp
        }.build()
    }
}
