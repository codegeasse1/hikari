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

object CloudflareSolver {

    private const val SOLVED_TTL_MS = 10 * 60_000L

    /** One poll interval. The challenge script itself finishes in well under a
     *  second once the page runs, so this is about how quickly we NOTICE. */
    private const val POLL_MS = 350L

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

    private const val WARM_COOLDOWN_MS = 90_000L

    private val warmedAt = ConcurrentHashMap<String, Long>()

    fun warm(
        url: String,
        userAgent: String,
        referer: String? = null,
        timeoutMs: Long = 25_000L,
    ): Boolean {
        val host = hostOf(url) ?: return false
        if (recentlySolved(url)) return true
        val last = warmedAt[host]
        if (last != null && System.currentTimeMillis() - last < WARM_COOLDOWN_MS) return false
        warmedAt[host] = System.currentTimeMillis()
        val lock = locks.getOrPut(host) { Any() }
        return synchronized(lock) {
            if (recentlySolved(url)) return@synchronized true
            val ok = runCatching { loadAndWait(url, userAgent, referer, timeoutMs) }
                .getOrDefault(false)
            if (ok) {
                solvedAt[host] = System.currentTimeMillis()
                CloudflareVerifier.clearBlocked(host)
                runCatching { CookieManager.getInstance().flush() }
            }
            ok
        }
    }

    /** True when this host was solved recently enough to trust. */
    fun recentlySolved(url: String): Boolean {
        val host = hostOf(url) ?: return false
        val at = solvedAt[host] ?: return false
        return System.currentTimeMillis() - at <= SOLVED_TTL_MS
    }

    fun solve(
        url: String,
        userAgent: String,
        referer: String? = null,
        timeoutMs: Long = 25_000L,
    ): Boolean {
        val host = hostOf(url) ?: return false
        if (CloudflareVerifier.needsVerification(url)) return false
        val lock = locks.getOrPut(host) { Any() }
        synchronized(lock) {
            if (recentlySolved(url)) return true
            val ok = runCatching { loadAndWait(url, userAgent, referer, timeoutMs) }
                .getOrDefault(false)
            if (ok) {
                solvedAt[host] = System.currentTimeMillis()
                CloudflareVerifier.clearBlocked(host)
                runCatching { CookieManager.getInstance().flush() }
            } else {
                // The user's globe button is the fallback for exactly this case.
                CloudflareVerifier.markBlocked(url)
            }
            return ok
        }
    }

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
