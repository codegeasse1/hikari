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

object PlayerHttp {

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
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            // Many aggregator links bounce https → http (HubCloud et al.).
            .followSslRedirects(true)
            .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
            .dispatcher(
                Dispatcher().apply {
                    // Buttery-smooth cap: probes share this pool with playback +
                    // extraction. 64/16 let one title's probe storm (60+ parallel
                    // bodies in the OOM log) fill the heap. 24/6 still leaves headroom
                    // for playback while bounding the storm.
                    maxRequests = 24
                    maxRequestsPerHost = 6
                }
            )
            .build()
    }

    val plainClient: OkHttpClient by lazy {
        client.newBuilder().apply { interceptors().clear() }.build()
    }

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
