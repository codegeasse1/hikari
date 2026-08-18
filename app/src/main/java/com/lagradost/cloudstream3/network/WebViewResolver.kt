@file:Suppress("DEPRECATION")

package com.lagradost.cloudstream3.network

import android.annotation.SuppressLint
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.hikari.app.HikariApp
import com.lagradost.cloudstream3.USER_AGENT
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking

/**
 * Real Android WebView-backed embed resolver.
 *
 * The jar ships the JVM (desktop) artifact of CloudStream, whose
 * `network.WebViewResolver` is a no-op stub (its `intercept()` just passes the
 * request through and `resolveUsingWebView` returns nothing) and whose
 * `com.lagradost.api` context helper is the JVM no-op variant. CloudStream
 * plugins — TamilBlasters' StreamHG/Hgcloud hgcloud.to dance, and many others —
 * pass a `WebViewResolver(Regex("(m3u8|master\\.txt)"))` to `app.get(…,
 * resolver = …)` so the player page's JS runs in a REAL WebView and the m3u8
 * request it fires is captured and handed back. With the stub that capture
 * never happens: the plugin's extractors silently produced zero servers and
 * only non-WebView jar extractors survived (a tamil movie showed a dead
 * "LuluStream" fallback instead of the "StreamHG" server CloudStream plays).
 *
 * This shadow class (see build.gradle.kts cloudstreamJarClean, which excludes
 * the jar's stub `WebViewResolver*.class`) replicates the behavior of
 * CloudStream's own `WebViewResolver.android.kt`: run the page in a WebView on
 * the main thread, capture the request whose URL matches `interceptUrl` (and
 * collect the ones matching `additionalUrls`), then hand the caller the
 * fixed/captured request so `app.get(...)` resolves to the real stream.
 */
class WebViewResolver(
    val interceptUrl: Regex,
    val additionalUrls: List<Regex> = emptyList(),
    val userAgent: String? = USER_AGENT,
    val useOkhttp: Boolean = true,
    val script: String? = null,
    val scriptCallback: ((String) -> Unit)? = null,
    val timeout: Long = DEFAULT_TIMEOUT,
) : Interceptor {

    companion object {
        const val DEFAULT_TIMEOUT = 60_000L

        @Volatile
        var webViewUserAgent: String? = null
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val fixedRequest = runBlocking { resolveUsingWebView(request) }.first
        return chain.proceed(fixedRequest ?: request)
    }

    suspend fun resolveUsingWebView(
        url: String,
        referer: String? = null,
        method: String = "GET",
        requestCallBack: (Request) -> Boolean = { false },
    ): Pair<Request?, List<Request>> =
        resolveUsingWebView(url, referer, emptyMap(), method, requestCallBack)

    suspend fun resolveUsingWebView(
        url: String,
        referer: String? = null,
        headers: Map<String, String>,
        method: String = "GET",
        requestCallBack: (Request) -> Boolean = { false },
    ): Pair<Request?, List<Request>> = runCatching {
        val builder = Request.Builder().url(url)
        builder.method(
            method,
            if (method.equals("GET", true) || method.equals("HEAD", true)) null
            else RequestBody.create(null, ByteArray(0))
        )
        headers.forEach { (k, v) -> builder.header(k, v) }
        if (!referer.isNullOrBlank()) builder.header("Referer", referer)
        resolveUsingWebView(builder.build(), requestCallBack)
    }.getOrDefault(null to emptyList())

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun resolveUsingWebView(
        request: Request,
        requestCallBack: (Request) -> Boolean = { false },
    ): Pair<Request?, List<Request>> {
        val url = request.url.toString()
        val main = Handler(Looper.getMainLooper())
        val fixedRequest = AtomicReference<Request?>(null)
        val extraRequests = Collections.synchronizedList(mutableListOf<Request>())
        val finished = CountDownLatch(1)
        var webView: WebView? = null

        fun destroyWebView() {
            main.post {
                webView?.let {
                    runCatching { it.stopLoading() }
                    runCatching { it.destroy() }
                }
                webView = null
            }
        }

        main.post {
            try {
                val context = HikariApp.instance
                val wv = WebView(context)
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true
                webViewUserAgent = wv.settings.userAgentString
                // CloudStream deliberately does not force a UA unless the
                // plugin asks for one — forcing it makes Cloudflare break.
                if (userAgent != null) wv.settings.userAgentString = userAgent
                wv.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? {
                        val reqUrl = request.url.toString()
                        if (script != null) {
                            Handler(Looper.getMainLooper()).post {
                                view.evaluateJavascript(script) { r -> scriptCallback?.invoke(r) }
                            }
                        }
                        if (interceptUrl.containsMatchIn(reqUrl)) {
                            toOkhttpRequest(request)?.let { r ->
                                fixedRequest.set(r)
                                requestCallBack(r)
                            }
                            finished.countDown()
                            destroyWebView()
                            return null
                        }
                        if (additionalUrls.any { it.containsMatchIn(reqUrl) }) {
                            toOkhttpRequest(request)?.let { r ->
                                extraRequests.add(r)
                                if (requestCallBack(r)) {
                                    finished.countDown()
                                    destroyWebView()
                                }
                            }
                        }
                        if (isBlacklisted(reqUrl)) {
                            return WebResourceResponse("image/png", null, ByteArrayInputStream(ByteArray(0)))
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onReceivedSslError(
                        view: WebView?,
                        handler: SslErrorHandler?,
                        error: SslError?,
                    ) {
                        handler?.proceed()
                    }
                }
                webView = wv
                wv.loadUrl(url, request.headers.toMap())
                // Safety net: a page that never fires a matching request must
                // not hang the plugin's loadLinks forever.
                main.postDelayed({ finished.countDown() }, timeout)
            } catch (t: Throwable) {
                finished.countDown()
            }
        }

        // Bounded wait (the countDown above is authoritative; this is a
        // second line of defense so a broken WebView can never hang us).
        runCatching { finished.await(timeout + 3_000L, TimeUnit.MILLISECONDS) }
        main.post {
            webView?.let {
                runCatching { it.stopLoading() }
                runCatching { it.destroy() }
            }
            webView = null
        }
        return fixedRequest.get() to extraRequests.toList()
    }

    private fun toOkhttpRequest(req: WebResourceRequest): Request? = runCatching {
        val builder = Request.Builder().url(req.url.toString())
        builder.method(
            req.method,
            if (req.method.equals("GET", true) || req.method.equals("HEAD", true)) null
            else RequestBody.create(null, ByteArray(0))
        )
        req.requestHeaders?.forEach { (k, v) -> builder.header(k, v) }
        builder.build()
    }.getOrNull()

    private val BLACKLISTED_FILES = listOf(
        ".jpg", ".png", ".webp", ".mpg", ".mpeg", ".jpeg", ".webm", ".mp4",
        ".mp3", ".gifv", ".flv", ".asf", ".mov", ".mng", ".mkv", ".ogg",
        ".avi", ".wav", ".woff2", ".woff", ".ttf", ".css", ".vtt", ".srt",
        ".ts", ".gif", "wss://",
    )

    private fun isBlacklisted(url: String): Boolean {
        val path = runCatching { URI(url).path ?: "" }.getOrDefault("")
        return BLACKLISTED_FILES.any { path.contains(it) } || url.endsWith("/favicon.ico")
    }
}
