package com.hikari.ext

import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.hikari.app.HikariApp
import com.lagradost.cloudstream3.network.WebViewResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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

    /**
     * GET like [getString], but when the plain HTTP client gets blocked (a
     * Cloudflare/DataDome challenge page or a hard failure — the common way
     * hanime1.me, hanime.tv, hstream.moe and friends refuse okhttp while
     * serving real browsers) it re-fetches the page inside a REAL WebView and
     * returns the rendered HTML. The WebView advertises the stock Android
     * WebView UA, which is the fingerprint Cloudflare's JS challenge actually
     * passes, so catalog/search/video pages that okhttp can never read become
     * fetchable. Results are intentionally NOT cached here — callers that want
     * a 10-minute cache (getCached) do their own.
     */
    suspend fun getStringSmart(url: String, headers: Map<String, String> = emptyMap()): String? {
        val plain = getString(url, headers)
        if (plain != null && !looksLikeChallengePage(plain)) return plain
        return getStringRendered(url)
    }

    /** GET and return the rendered DOM HTML by loading [url] in a real WebView. */
    suspend fun getStringRendered(url: String, timeoutMs: Long = 25_000): String? =
        withContext(Dispatchers.IO) {
            val main = Handler(Looper.getMainLooper())
            val result = AtomicReference<String?>()
            val latch = CountDownLatch(1)
            var webView: WebView? = null
            main.post {
                try {
                    val app = HikariApp.instance
                    val wv = WebView(app)
                    wv.settings.javaScriptEnabled = true
                    wv.settings.domStorageEnabled = true
                    wv.settings.userAgentString = app.effectiveWebViewUa()
                    wv.webChromeClient = WebChromeClient()
                    wv.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            view?.evaluateJavascript(
                                "(function(){return document.documentElement.outerHTML;})()"
                            ) { res ->
                                if (res != null) result.set(unescapeJsString(res))
                                latch.countDown()
                            }
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?,
                        ) {
                            // keep waiting — the main frame may still finish, or
                            // the safety timeout below fires
                        }
                    }
                    webView = wv
                    wv.loadUrl(url)
                    main.postDelayed({ latch.countDown() }, timeoutMs)
                } catch (t: Throwable) {
                    latch.countDown()
                }
            }
            runCatching { latch.await(timeoutMs + 3_000L, TimeUnit.MILLISECONDS) }
            main.post {
                webView?.let {
                    runCatching { it.stopLoading() }
                    runCatching { it.destroy() }
                }
                webView = null
            }
            result.get()
        }

    /** Distinguishes a WAF challenge page from real content. */
    private fun looksLikeChallengePage(html: String): Boolean {
        val probe = html.take(40_000)
        return CHALLENGE_MARKERS.any { probe.contains(it, ignoreCase = true) }
    }

    private val CHALLENGE_MARKERS = listOf(
        "cf-chl-",
        "challenge-platform",
        "cdn-cgi/challenge-platform",
        "cf-browser-verification",
        "cf-mitigated",
        "cf-turnstile",
        "Just a moment",
        "Attention Required!",
        "enablejs",
        "Pardon Our Interruption",
        "Checking your browser",
        "checking your browser",
        "Verify you are human",
        "verify you are human",
        "hcaptcha",
        "h-captcha",
        "Access Denied",
        "datadome",
        "detectportal",
    )

    /** evaluateJavascript returns a JS string literal — decode it. */
    private fun unescapeJsString(s: String): String? = runCatching {
        val v = org.json.JSONTokener(s).nextValue()
        (v as? String) ?: if (v === org.json.JSONObject.NULL) null else v.toString()
    }.getOrNull()

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

    private fun Request.toResult(): HikariWebViewResult {
        val headers = headers.toMap().toMutableMap().filterValues { it.isNotBlank() }.toMutableMap()
        // The WebView does not expose Cookie in WebResourceRequest headers, but
        // the player NEEDS it: hgcloud/StreamHG, hanime1's player and hstream's
        // DASH all serve the manifest/video only to the session that visited
        // the watch page (cf_clearance, session tokens). Attach the WebView's
        // cookies for this URL so playback actually starts instead of 403ing.
        if (!headers.containsKey("Cookie")) {
            val cookie = runCatching { CookieManager.getInstance().getCookie(url.toString()) }.getOrNull()
            if (!cookie.isNullOrBlank()) headers["Cookie"] = cookie
        }
        return HikariWebViewResult(url.toString(), headers)
    }
}
