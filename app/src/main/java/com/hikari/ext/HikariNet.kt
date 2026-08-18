package com.hikari.ext

import com.lagradost.cloudstream3.network.WebViewResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/** A request the page fired that matched the capture regex. */
data class HikariWebViewResult(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
)

/** Full HTTP response summary (for callers that must inspect errors). */
data class HikariResponse(
    val status: Int,
    val url: String,
    val body: String? = null,
)

/**
 * The helper library Hikari extensions are written against. All helpers are
 * plain functions over the app's hardened networking stack (redirects,
 * generous timeouts, browser User-Agent, CloudStream's Conscrypt TLS setup),
 * so extensions never have to fight CDNs by themselves.
 */
object HikariNet {

    /** Browser-like headers for scraping (desktop Chrome fingerprint). */
    val browserHeaders: Map<String, String> = mapOf(
        "User-Agent" to com.hikari.app.net.Http.UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
    )

    /** GET and return the response body as text (null on any failure). */
    suspend fun getString(url: String, headers: Map<String, String> = emptyMap()): String? =
        withContext(Dispatchers.IO) {
            com.hikari.app.net.Http.getString(url, headers)
        }

    /** GET and parse the response as JSON (null on failure). */
    suspend fun getJson(url: String, headers: Map<String, String> = emptyMap()): JSONObject? =
        withContext(Dispatchers.IO) {
            getString(url, headers)?.let { runCatching { JSONObject(it) }.getOrNull() }
        }

    suspend fun getBytes(url: String, headers: Map<String, String> = emptyMap()): ByteArray? =
        withContext(Dispatchers.IO) {
            com.hikari.app.net.Http.getBytes(url, headers)
        }

    /** Full response (status + body) for callers that must see error codes. */
    suspend fun fetch(url: String, headers: Map<String, String> = emptyMap()): HikariResponse? =
        withContext(Dispatchers.IO) {
            try {
                com.hikari.app.net.Http.get(url, headers).use { r ->
                    HikariResponse(r.code, r.request.url.toString(), r.body?.string())
                }
            } catch (t: Throwable) {
                null
            }
        }

    /**
     * Runs [url] in a real Android WebView (on the main thread, exactly like
     * the CloudStream runtime does) and returns every request the page fired
     * whose URL matches [capture] (or [additional]). This is the helper that
     * makes StreamHG/hgcloud-style embeds work: the player page's own JS runs
     * in a browser, and the m3u8 (or master.txt) it requests comes back as a
     * [HikariWebViewResult] (URL + headers), ready to hand to the player.
     */
    suspend fun resolveWithWebView(
        url: String,
        capture: Regex,
        additional: List<Regex> = emptyList(),
        timeoutMs: Long = 60_000,
    ): List<HikariWebViewResult> = withContext(Dispatchers.IO) {
        val resolver = WebViewResolver(
            interceptUrl = capture,
            additionalUrls = additional,
            timeout = timeoutMs,
        )
        runCatching {
            val (fixed, extra) = resolver.resolveUsingWebView(url)
            buildList {
                fixed?.let { add(it.toResult()) }
                extra.forEach { add(it.toResult()) }
            }
        }.getOrDefault(emptyList())
    }

    private fun Request.toResult() =
        HikariWebViewResult(url.toString(), headers.toMap().filterValues { it.isNotBlank() })
}
