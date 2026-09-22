package com.hikari.app.net

import android.annotation.SuppressLint
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebView
import android.webkit.WebViewClient
import com.hikari.app.HikariApp
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Clears the Cloudflare challenge in front of ONE URL by loading it in an
 * offscreen WebView — silently, at most once per host.
 *
 * **Why Hikari needs this at all.** An Aniyomi/Mihon extension's HTTP client is
 * [eu.kanade.tachiyomi.network.NetworkHelper]'s, and an extension whose site
 * sits behind Cloudflare has no other way through: the extensions say so
 * themselves. AnimeOnline.Ninja's own source reads
 *
 * ```kotlin
 * override fun popularAnimeRequest(page: Int) = GET("$baseUrl/tendencias/", headers)
 * // …both hosts serve an identical managed challenge on every path,
 * // so keep each request single-hopped and let CloudflareInterceptor solve it
 * ```
 *
 * i.e. it expects the APP's client to clear the challenge — which is exactly
 * what Aniyomi, Tadami and Nekoread do with a hidden WebView. Hikari's own
 * [CloudflareVerifier] only ever *reused* a clearance the user had earned by
 * tapping the globe, so every catalog request from such an extension came back
 * as "Home failed: HTTP error 403" while unprotected extensions in the same
 * repo loaded fine.
 *
 * **How the solve reaches the extension.** The clearance Cloudflare hands the
 * WebView is a `cf_clearance` cookie in the shared WebView cookie jar
 * ([CookieManager]) — the same jar the extension's OkHttp client reads through
 * [eu.kanade.tachiyomi.network.AndroidCookieJar]. So the request the
 * interceptor retries afterwards is served normally, with no extra plumbing.
 *
 * **The User-Agent is not optional.** A `cf_clearance` is minted for the UA the
 * WebView presented and is rejected when the request that follows advertises a
 * different one, so [solve] takes the UA of the pending OkHttp request and makes
 * the WebView use exactly that ([ExtensionCloudflareInterceptor] passes the
 * request's own header).
 *
 * **Nothing opens on the user's screen.** The WebView is never attached to a
 * view, is destroyed as soon as the page settles, and the user's own
 * verification flow (the globe button) stays the fallback: a solve that fails
 * records the host in [CloudflareVerifier], which is what makes the UI offer
 * that deliberate tap.
 */
object CloudflareSolver {

    /**
     * How long a solve is honoured before the host is tried again. A clearance
     * lives for hours; this only decides how long we TRUST it, so a cookie that
     * gets dropped mid-session is re-earned on the next challenge instead of
     * being assumed forever.
     */
    private const val SOLVED_TTL_MS = 10 * 60_000L

    /** One poll interval. The challenge script itself finishes in well under a
     *  second once the page runs, so this is about how quickly we NOTICE. */
    private const val POLL_MS = 350L

    /**
     * How long the page is allowed to keep running AFTER it settles with a
     * normal page. Cloudflare is rarely the only step: animeonline.ninja answers
     * with a "One moment, please" form whose own script posts to install a
     * session cookie, and the protected keiyoushi mirrors do the same dance.
     * Letting the WebView sit on the page for a few seconds is what turns those
     * cookies into a request that actually returns content.
     */
    private const val SETTLE_GRACE_MS = 4_000L

    /** Titles that mean "this is still an interstitial". Checked on the main
     *  thread only (a WebView method must never be called off it), from
     *  `onPageFinished`. */
    private val INTERSTITIAL_TITLES = listOf(
        "just a moment",
        "one moment",
        "attention required",
        "checking your browser",
        "performing security verification",
        "verify you are human",
    )

    private val solvedAt = ConcurrentHashMap<String, Long>()

    /** One lock per host: ten parallel catalog requests must not each spawn a
     *  WebView — the second through tenth wait for the first solve and then use
     *  the cookie it earned. */
    private val locks = ConcurrentHashMap<String, Any>()

    /** True when this host was solved recently enough to trust. */
    fun recentlySolved(url: String): Boolean {
        val host = hostOf(url) ?: return false
        val at = solvedAt[host] ?: return false
        return System.currentTimeMillis() - at <= SOLVED_TTL_MS
    }

    /**
     * Loads [url] offscreen and waits for the challenge to clear. BLOCKING —
     * call it from a network thread, never from the UI thread. Returns true when
     * the page settled (cookies are in the shared jar, so the caller's retry
     * should now be served).
     */
    fun solve(
        url: String,
        userAgent: String,
        referer: String? = null,
        timeoutMs: Long = 25_000L,
    ): Boolean {
        val host = hostOf(url) ?: return false
        // Never re-load a host the app already knows it cannot pass: that just
        // burns the caller's budget on the same wall (and, on a hard WAF block,
        // the whole timeout).
        if (CloudflareVerifier.needsVerification(url)) return false
        val lock = locks.getOrPut(host) { Any() }
        synchronized(lock) {
            if (recentlySolved(url)) return true
            val ok = runCatching { loadAndWait(url, userAgent, referer, timeoutMs) }
                .getOrDefault(false)
            if (ok) {
                solvedAt[host] = System.currentTimeMillis()
                CloudflareVerifier.clearBlocked(host)
            } else {
                // The user's globe button is the fallback for exactly this case.
                CloudflareVerifier.markBlocked(url)
            }
            return ok
        }
    }

    /**
     * The WebView half. Everything WEBVIEW is done on the main thread; the
     * waiting is done on the caller's (network) thread, which only ever reads
     * the two atomics the main thread writes.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun loadAndWait(
        url: String,
        userAgent: String,
        referer: String?,
        timeoutMs: Long,
    ): Boolean {
        val main = Handler(Looper.getMainLooper())
        /** When a main-frame load finished with a page that is NOT an
         *  interstitial — i.e. the challenge passed. Re-armed by every later
         *  clean finish, so a redirect chain has to go quiet before we call it
         *  done. */
        val settledAt = AtomicLong(0L)
        /** When a `cf_clearance` appeared, whichever page produced it. */
        val clearanceAt = AtomicLong(0L)
        val aborted = AtomicBoolean(false)
        val done = CountDownLatch(1)
        var webView: WebView? = null

        fun destroy() {
            main.post {
                webView?.let { wv ->
                    runCatching { wv.stopLoading() }
                    runCatching { wv.loadUrl("about:blank") }
                    runCatching { wv.destroy() }
                }
                webView = null
            }
        }

        main.post {
            try {
                val wv = WebView(HikariApp.instance)
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true
                wv.settings.databaseEnabled = true
                wv.settings.userAgentString = userAgent
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                        val title = runCatching { view?.title }.getOrNull().orEmpty().lowercase()
                        val interstitial = title.isNotBlank() &&
                            INTERSTITIAL_TITLES.any { title.contains(it) }
                        if (!interstitial) settledAt.set(System.currentTimeMillis())
                    }

                    override fun onReceivedSslError(
                        view: WebView?,
                        handler: SslErrorHandler?,
                        error: SslError?,
                    ) {
                        handler?.proceed()
                    }

                    override fun onRenderProcessGone(
                        view: WebView?,
                        detail: android.webkit.RenderProcessGoneDetail?
                    ): Boolean {
                        // A crashed renderer (heavy/obfuscated challenge pages do
                        // this) must not take the app with it — claim the crash
                        // and let the waiter answer "not solved".
                        aborted.set(true)
                        runCatching { done.countDown() }
                        return true
                    }
                }
                webView = wv
                val extra = LinkedHashMap<String, String>()
                if (!referer.isNullOrBlank()) extra["Referer"] = referer
                wv.loadUrl(url, extra)
            } catch (t: Throwable) {
                aborted.set(true)
                runCatching { done.countDown() }
            }
        }

        val deadline = System.currentTimeMillis() + timeoutMs
        var solved = false
        while (System.currentTimeMillis() < deadline) {
            if (done.await(POLL_MS, TimeUnit.MILLISECONDS) || aborted.get()) break
            // Reading the jar is fine from any thread (it is what the extension
            // client's AndroidCookieJar does); touching the WebView is not, so
            // the page state is read on the main thread and handed to us above.
            if (clearanceAt.get() == 0L) {
                val cookie = runCatching { CookieManager.getInstance().getCookie(url) }
                    .getOrNull().orEmpty()
                if (cookie.contains("cf_clearance")) clearanceAt.set(System.currentTimeMillis())
            }
            val settled = settledAt.get()
            if (settled != 0L && System.currentTimeMillis() - settled >= SETTLE_GRACE_MS) {
                solved = true
                break
            }
            // A clearance in hand is proof the challenge itself passed, so a page
            // whose title never stops looking like an interstitial (some
            // interstitials hand over content without changing it) still counts
            // as solved — after a longer quiet period, so the form-post step
            // those pages run has had time to store its cookie too.
            val cleared = clearanceAt.get()
            if (cleared != 0L && System.currentTimeMillis() - cleared >= 2 * SETTLE_GRACE_MS) {
                solved = true
                break
            }
        }
        destroy()
        return solved
    }

    private fun hostOf(url: String): String? =
        runCatching { URI(url).host?.lowercase() }.getOrNull()?.takeIf { it.isNotBlank() }
}
