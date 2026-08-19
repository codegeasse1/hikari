@file:Suppress("DEPRECATION")

package com.lagradost.cloudstream3.network

import android.annotation.SuppressLint
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
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

        /** Forces a muted play + clicks common play overlays. Kept as a shared
         *  constant so both the throttled nudge and the post-finish retries use
         *  the exact same script. */
        private val AUTOPLAY_JS = """
            (function(){
              var tryPlay = function(){
                var vids = document.querySelectorAll('video');
                for (var i=0;i<vids.length;i++){
                  var v = vids[i];
                  try {
                    if (v.paused) { v.muted = true; var p = v.play(); if (p && p.catch) p.catch(function(){}); }
                    setTimeout(function(){ try { v.muted = false; } catch(e){} }, 400);
                  } catch(e){}
                }
                var sels = ['.play','.play-btn','.play-button','[aria-label="Play"]',
                            '[aria-label="Play video"]','.bigPlayButton','.vjs-big-play-button','.jw-icon-display',
                            '.plyr__control--overlaid','button[title="Play"]','.play_btn'];
                for (var s=0;s<sels.length;s++){
                  var els = document.querySelectorAll(sels[s]);
                  for (var j=0;j<els.length;j++){
                    var el = els[j];
                    if (el.offsetParent !== null || el.getBoundingClientRect().height > 0) {
                      try { el.click(); } catch(e){}
                    }
                  }
                }
                // Last resort: any visible button whose label/aria mentions
                // play/start — covers players that name their overlay oddly.
                try {
                  var all=document.querySelectorAll('button,[role="button"],.control,label[for]');
                  for (var k=0;k<all.length;k++){
                    var el=all[k];
                    if (el.offsetParent===null && el.getBoundingClientRect().height<=0) continue;
                    var label=(el.getAttribute('aria-label')||'')+' '+(el.textContent||'').trim();
                    var low=label.toLowerCase();
                    if (low.indexOf('play')>=0 && low.length<40) { try { el.click(); } catch(e){} }
                  }
                } catch(e){}
              };
              tryPlay();
              setTimeout(tryPlay, 1000);
              setTimeout(tryPlay, 3000);
            })();
        """.trimIndent()
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
        // The FIRST WebView on a fresh process is slow (renderer spawn, cookie
        // store init) and some CDNs need the page loaded twice to establish
        // the session — a plugin that gets nothing on attempt #1 often succeeds
        // instantly on #2 (the "first play only shows the fallback server"
        // symptom). Retry once when the first pass captured nothing.
        val first = resolveOnce(request, requestCallBack)
        if (first.first != null || first.second.isNotEmpty()) return first
        return resolveOnce(request, requestCallBack)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun resolveOnce(
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
                // Streaming players refuse to autoplay with sound when the
                // WebView requires a user gesture — and a capture WebView
                // never gets tapped, so the player just sits there and never
                // requests the manifest (hanime.tv / hstream.moe showed "no
                // playable sources" for exactly this reason). Allow
                // programmatic play, then injectAutoplay() kicks the player.
                wv.settings.mediaPlaybackRequiresUserGesture = false
                webViewUserAgent = wv.settings.userAgentString
                // CloudStream deliberately does not force a UA unless the
                // plugin asks for one — forcing it makes Cloudflare break.
                // The app's setting (Settings → WebView user agent) decides the
                // UA instead: stock Android default by default (passes the CF
                // JS challenge), or the user's custom UA when they override.
                // The plugin-requested UA is only honored when the user turned
                // the override off and typed nothing.
                wv.settings.userAgentString = HikariApp.instance.effectiveWebViewUa(userAgent)
                wv.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? {
                        val reqUrl = request.url.toString()
                        // The player is often only wired up by the time the
                        // page has made a request — nudge it to start (muted
                        // play is always allowed). Throttled + posted to the
                        // main thread (shouldInterceptRequest runs on a
                        // background thread and WebView calls there crash).
                        nudgeAutoplay(view)
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

                    override fun onPageFinished(view: WebView?, url: String?) {
                        // Page loaded (possibly a WAF challenge that then
                        // reloads) — start the player if one exists. Players
                        // initialize lazily, so keep nudging for a few seconds.
                        nudgeAutoplay(view)
                        for (delay in listOf(1000L, 3000L, 6000L, 9000L, 12000L)) {
                            mainHandler.postDelayed({
                                runCatching { view?.evaluateJavascript(AUTOPLAY_JS, null) }
                            }, delay)
                        }
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

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Guards against spamming the page with autoplay nudges on EVERY
     *  subresource request (shouldInterceptRequest fires for each one). */
    @Volatile
    private var lastAutoplayAt = 0L

    /**
     * Some sites show a "click to play" overlay or gate the stream behind a
     * solved challenge (Altcha proof-of-work on hstream, Cloudflare on
     * hanime.tv) — once the page settles, force the video element to start
     * (muted play is exempt from autoplay policy) and click common play
     * overlays. Runs a few times because players initialize lazily.
     */
    private fun nudgeAutoplay(view: WebView?) {
        if (view == null) return
        val now = System.currentTimeMillis()
        if (now - lastAutoplayAt < 1500) return
        lastAutoplayAt = now
        // shouldInterceptRequest runs on a background thread — WebView methods
        // MUST run on the main thread, or the renderer throws
        // "A WebView method was called on thread '…'" (seen as a crash banner
        // on Home). Always bounce through the main looper.
        mainHandler.post { runCatching { view.evaluateJavascript(AUTOPLAY_JS, null) } }
    }

    private fun toOkhttpRequest(req: WebResourceRequest): Request? = runCatching {
        val builder = Request.Builder().url(req.url.toString())
        builder.method(
            req.method,
            if (req.method.equals("GET", true) || req.method.equals("HEAD", true)) null
            else RequestBody.create(null, ByteArray(0))
        )
        var hasCookie = false
        req.requestHeaders?.forEach { (k, v) ->
            builder.header(k, v)
            if (k.equals("Cookie", ignoreCase = true)) hasCookie = true
        }
        // The WebView never puts the Cookie header in requestHeaders, but the
        // session (cf_clearance, login tokens) is what makes the CDN serve the
        // stream. Attach the WebView's cookies so the plugin's subsequent
        // request actually gets served.
        if (!hasCookie) {
            val cookie = runCatching { CookieManager.getInstance().getCookie(req.url.toString()) }.getOrNull()
            if (!cookie.isNullOrBlank()) builder.header("Cookie", cookie)
        }
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
