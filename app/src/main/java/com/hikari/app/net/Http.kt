package com.hikari.app.net

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit

object Http {

    /** Desktop Chrome UA, matching CloudStream's own USER_AGENT. The WAFs that
     *  guard the TamilBlasters/StreamHG/luluvdo family serve their player pages
     *  and HLS CDNs to desktop browsers (the plugins' own requests even use a
     *  desktop Chrome 149); a mobile "… Mobile Safari" UA stands out to those
     *  WAFs and some answer 403 before ever checking the token. */
    const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    /** Honest app UA used when fetching Nuvio manifests and scraper files —
     *  mirrors the real nuvio app's own OkHttp client. Codeberg (which hosts
     *  the well-known nuvio repos) answers the spoofed desktop-Chrome UA with
     *  HTTP 403 but serves this app UA fine, so the nuvio fetch paths override
     *  the browser UA with this one (the desktop UA stays right for the
     *  CloudStream/Stremio content sites it was chosen for). */
    const val NUVIO_UA = "NuvioTV/1.0"

    /** Same current-Chrome fingerprint the WebView uses so probes and the site
     *  agree on what browser is "visiting" (Cloudflare checks consistency). */
    const val WEBVIEW_UA = UA

    private lateinit var client: OkHttpClient

    /** Same tuning, WITHOUT the Cloudflare interceptor — see [getQuiet]. */
    private lateinit var quietClient: OkHttpClient

    fun init() {
        client = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            // Cloudflare handling: attaches a WebView cf_clearance cookie when
            // one is present, and marks the host as blocked when a challenge
            // comes back — Hikari itself NEVER opens the verify WebView here.
            // The user opens it by tapping the globe button, and the requests
            // retry with the cookie that tap earned (see CloudflareVerifier).
            .addInterceptor { chain -> CloudflareVerifier.intercept(chain) }
            .dns(DohDns)
            .build()
        quietClient = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .dns(DohDns)
            .build()
    }

    /**
     * A request that bypasses [CloudflareVerifier] entirely. Used by the
     * background decorating lookups ([com.hikari.app.data.Ratings]): those go to
     * third-party review sites that put a challenge in front of a plain HTTP
     * client, and the interceptor would record that host as "blocked" — the flag
     * the Home screen turns into "verification needed on X". That banner is
     * about the user's own extensions, so a review site must never be able to
     * raise it.
     */
    fun getQuiet(url: String, headers: Map<String, String> = emptyMap()): Response {
        val builder = Request.Builder().url(url).header("User-Agent", UA)
        headers.forEach { (k, v) -> builder.header(k, v) }
        return quietClient.newCall(builder.build()).execute()
    }

    /** [getQuiet] as text, null for any non-2xx (so a guessed page URL that
     *  does not exist is simply "nothing found"). */
    fun getStringQuiet(url: String, headers: Map<String, String> = emptyMap()): String? =
        try {
            getQuiet(url, headers).use { if (it.isSuccessful) it.body?.string() else null }
        } catch (e: Exception) {
            null
        }

    fun get(url: String, headers: Map<String, String> = emptyMap()): Response {
        val builder = Request.Builder().url(url).header("User-Agent", UA)
        headers.forEach { (k, v) -> builder.header(k, v) }
        return client.newCall(builder.build()).execute()
    }

    fun post(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
        contentType: String = "application/json; charset=utf-8",
    ): Response {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .post(body.toRequestBody(contentType.toMediaType()))
        headers.forEach { (k, v) -> builder.header(k, v) }
        return client.newCall(builder.build()).execute()
    }

    /**
     * Any method, for the handful of APIs that are neither a plain GET nor a
     * POST: the trackers (Settings → Trackers) update a list entry with PATCH
     * or PUT, and Kitsu's JSON:API wants PATCH/POST under its own media type.
     * [body] may be null for a method that carries none; GET/HEAD are still
     * routed through [get] so a caller cannot accidentally send a body with
     * them.
     */
    fun request(
        method: String,
        url: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
        contentType: String = "application/json; charset=utf-8",
    ): Response {
        if (method.equals("GET", ignoreCase = true) && body == null) return get(url, headers)
        val builder = Request.Builder().url(url).header("User-Agent", UA)
        builder.method(
            method,
            (body ?: "").toRequestBody(contentType.toMediaType()),
        )
        headers.forEach { (k, v) -> builder.header(k, v) }
        return client.newCall(builder.build()).execute()
    }

    fun postString(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
        contentType: String = "application/json; charset=utf-8",
    ): String? = try {
        post(url, body, headers, contentType).use { if (it.isSuccessful) it.body?.string() else null }
    } catch (e: Exception) {
        null
    }

    /**
     * [postString] without [CloudflareVerifier]: for the background decorating
     * lookups (anime characters from AniList, see [com.hikari.app.data.AnimeCast])
     * whose host must never be able to raise the Home screen's "verification
     * needed" banner — that banner is about the user's own extensions.
     */
    fun postStringQuiet(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
        contentType: String = "application/json; charset=utf-8",
    ): String? = try {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .post(body.toRequestBody(contentType.toMediaType()))
        headers.forEach { (k, v) -> builder.header(k, v) }
        quietClient.newCall(builder.build()).execute()
            .use { if (it.isSuccessful) it.body?.string() else null }
    } catch (e: Exception) {
        null
    }

    fun getString(url: String, headers: Map<String, String> = emptyMap()): String? =
        try {
            get(url, headers).use { if (it.isSuccessful) it.body?.string() else null }
        } catch (e: Exception) {
            null
        }

    fun getBytes(url: String, headers: Map<String, String> = emptyMap()): ByteArray? =
        try {
            get(url, headers).use { if (it.isSuccessful) it.body?.bytes() else null }
        } catch (e: Exception) {
            null
        }

    /**
     * Streams a download to [dest], reporting (downloadedBytes, totalBytes) through
     * [onProgress] on each chunk. totalBytes is -1 when unknown. Returns true on success.
     */
    fun downloadTo(
        url: String,
        dest: java.io.File,
        headers: Map<String, String> = emptyMap(),
        onProgress: ((Long, Long) -> Unit)? = null,
    ): Boolean = try {
        get(url, headers).use { resp ->
            if (!resp.isSuccessful) return false
            val body = resp.body ?: return false
            val total = body.contentLength()
            dest.parentFile?.mkdirs()
            body.byteStream().use { input ->
                dest.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        onProgress?.invoke(done, total)
                    }
                }
            }
            true
        }
    } catch (e: Exception) {
        dest.delete()
        false
    }

    fun getStringStrict(
        url: String,
        headers: Map<String, String> = emptyMap(),
        readTimeoutSec: Long = 30,
    ): Result<String> =
        try {
            val call = clientFor(readTimeoutSec)
            val builder = Request.Builder().url(url).header("User-Agent", UA)
            headers.forEach { (k, v) -> builder.header(k, v) }
            call.newCall(builder.build()).execute().use { r ->
                if (r.isSuccessful) Result.success(r.body?.string() ?: "")
                else Result.failure(Exception("HTTP ${r.code} for $url"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }

    /**
     * The shared client with a longer per-read timeout, for the one kind of
     * fetch that is genuinely big: a Mihon/Aniyomi repo index can be a 1.5 MB
     * JSON file (keiyoushi's `index.json` is), and 30s per read is not always
     * enough for that on a slow mobile connection. Built through `newBuilder()`
     * so the connection pool, dispatcher and interceptors (Cloudflare handling,
     * DoH) stay shared with every other request.
     */
    private fun clientFor(readTimeoutSec: Long): OkHttpClient =
        if (readTimeoutSec <= 30) client
        else client.newBuilder().readTimeout(readTimeoutSec, TimeUnit.SECONDS).build()

    private val GITHUB_RAW =
        Regex("^https://raw\\.githubusercontent\\.com/([^/]+)/([^/]+)/([^/]+)/(.+)$")

    /**
     * URL + a jsDelivr CDN mirror (global CDN, reachable where GitHub raw often isn't),
     * each retried once. Returns the first success or the last failure.
     */
    private fun urlVariants(url: String): List<String> {
        val variants = mutableListOf(url)
        GITHUB_RAW.matchEntire(url)?.let { m ->
            val user = m.groupValues[1]
            val repo = m.groupValues[2]
            val branch = m.groupValues[3]
            val path = m.groupValues[4]
            variants.add("https://cdn.jsdelivr.net/gh/$user/$repo@$branch/$path")
        }
        return variants
    }

    fun fetchStringRobust(
        url: String,
        headers: Map<String, String> = emptyMap(),
        readTimeoutSec: Long = 30,
    ): Result<String> {
        var last: Throwable = Exception("Failed to fetch $url")
        for (u in urlVariants(url)) {
            for (attempt in 0 until 2) {
                val r = getStringStrict(u, headers, readTimeoutSec)
                if (r.isSuccess) return r
                r.exceptionOrNull()?.let { last = it }
                try {
                    Thread.sleep(300L)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
        return Result.failure(last)
    }

    fun fetchBytesRobust(url: String, headers: Map<String, String> = emptyMap()): ByteArray? {
        for (u in urlVariants(url)) {
            for (attempt in 0 until 2) {
                val b = getBytes(u, headers)
                if (b != null) return b
                try {
                    Thread.sleep(300L)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
        return null
    }

    /**
     * Downloads a whole file for an EXTENSION INSTALL, streaming it into memory
     * with a per-read timeout and an overall call timeout that are both sized
     * for a slow phone connection, and a FAILURE MESSAGE THAT SAYS WHICH failure
     * it was.
     *
     * This exists because the old path (`fetchBytesRobust`'s `body.bytes()`
     * behind a 30s read timeout) reported every failure the same way — "Download
     * timed out — check your connection" — including the ones that were not
     * timeouts at all: an index whose `apk` field is a bare file name resolved
     * against the wrong repo root answers HTTP 404 in milliseconds, and the user
     * was told to check their (perfectly fine) connection for it. The raw-GitHub
     * ↔ jsDelivr mirror pair is tried as well, so an ISP block on one of them
     * still installs.
     */
    fun downloadBytes(
        url: String,
        headers: Map<String, String> = emptyMap(),
        readTimeoutSec: Long = 60,
        callTimeoutSec: Long = 300,
    ): Result<ByteArray> {
        var last: Throwable = Exception("the file could not be downloaded")
        for (variant in urlVariants(url)) {
            val attempt = runCatching {
                val perCall = client.newBuilder()
                    .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
                    .callTimeout(callTimeoutSec, TimeUnit.SECONDS)
                    .build()
                val builder = Request.Builder().url(variant).header("User-Agent", UA)
                headers.forEach { (k, v) -> builder.header(k, v) }
                perCall.newCall(builder.build()).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw Exception(describeFailure(variant, resp.code, variant != url))
                    }
                    resp.body?.bytes() ?: throw Exception("the server sent an empty file")
                }
            }
            attempt.getOrNull()?.let { return Result.success(it) }
            attempt.exceptionOrNull()?.let { last = it }
            try {
                Thread.sleep(300L)
            } catch (e: InterruptedException) {
                break
            }
        }
        return Result.failure(last)
    }

    /** One line the user can act on, for a download that did not happen. */
    private fun describeFailure(url: String, code: Int, mirrored: Boolean): String = when {
        code == 404 -> "the file is not there (HTTP 404 at ${url.substringAfter("://")})" +
            if (mirrored) " or at its jsDelivr mirror" else ""
        code == 403 -> "the host refused the request (HTTP 403)"
        code == 429 -> "the host is rate-limiting downloads right now (HTTP 429) — try again in a minute"
        code in 500..599 -> "the host failed to serve the file (HTTP $code) — try again in a minute"
        else -> "the host answered HTTP $code"
    }

    /**
     * Turns Google Drive share/download URLs into the direct-download form that
     * serves raw file bytes (no virus-scan HTML page). Handles:
     *   drive.google.com/uc?export=download&id=X
     *   drive.google.com/open?id=X
     *   drive.google.com/file/d/<id>/view
     */
    fun normalizeDriveUrl(url: String): String {
        val u = url.trim().trim('"', '\'')
        if (u.isBlank()) return u
        val id = Regex("""drive\.google\.com/(?:uc|open)\?(?:.*&)?id=([^&\s"']+)""")
            .find(u)?.groupValues?.get(1)
            ?: Regex("""drive\.google\.com/file/d/([^/\s"']+)""")
                .find(u)?.groupValues?.get(1)
        return if (id != null) {
            "https://drive.usercontent.google.com/download?id=$id&export=download&confirm=t"
        } else u
    }
}
