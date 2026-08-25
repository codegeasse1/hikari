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

    /** Same current-Chrome fingerprint the WebView uses so probes and the site
     *  agree on what browser is "visiting" (Cloudflare checks consistency). */
    const val WEBVIEW_UA = UA

    private lateinit var client: OkHttpClient

    fun init() {
        client = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
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

    fun getStringStrict(url: String, headers: Map<String, String> = emptyMap()): Result<String> =
        try {
            get(url, headers).use { r ->
                if (r.isSuccessful) Result.success(r.body?.string() ?: "")
                else Result.failure(Exception("HTTP ${r.code} for $url"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }

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

    fun fetchStringRobust(url: String, headers: Map<String, String> = emptyMap()): Result<String> {
        var last: Throwable = Exception("Failed to fetch $url")
        for (u in urlVariants(url)) {
            for (attempt in 0 until 2) {
                val r = getStringStrict(u, headers)
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
