package com.hikari.app.net

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import com.hikari.app.HikariApp
import com.hikari.app.web.WebViewActivity
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Shared Cloudflare handling for both of Hikari's networking stacks:
 *  - its own [Http] client (repo.json / plugin lists / Stremio manifests /
 *    Hikari-extension scraping), via the interceptor registered in Http.init, and
 *  - the CloudStream jar's `app` NiceHTTP client (CS3 plugin content requests),
 *    via the interceptor HikariApp registers on the client it wires up.
 *
 * Mirrors CloudStream's own CloudflareKiller: a 403/503 whose `Server` header
 * says cloudflare is treated as a CF challenge. If the WebView cookie jar
 * already holds a cf_clearance for the host we attach it (plus the WebView UA,
 * the fingerprint the clearance was minted for) and retry; otherwise we
 * auto-open the "verify" WebView — the same view the Home globe button opens
 * manually, set to close itself the moment the challenge passes — wait for the
 * clearance to appear, then retry. A per-host in-flight guard keeps concurrent
 * requests from stacking WebViews, and a short dismissal cooldown keeps a
 * user who closed the view without solving from being re-prompted on every
 * retry of the same host.
 */
object CloudflareVerifier {

    private const val SOLVE_TIMEOUT_MS = 90_000L
    private const val DISMISS_COOLDOWN_MS = 60_000L

    private val lock = Any()
    private val inFlight = HashMap<String, CountDownLatch>()
    private val launchedFor = HashSet<String>()
    private val dismissedUntil = HashMap<String, Long>()

    /** Master switch: auto-open the verify WebView on a challenge (vs. just
     *  returning the challenge response and letting the caller surface it). */
    @Volatile
    var autoOpenEnabled = true

    /** cf_clearance (or the full cookie string containing it) for [url] from the
     *  WebView cookie jar — the jar the verify WebView keeps populated. */
    fun clearanceFor(url: String): String? {
        val cookie = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
        return cookie?.takeIf { it.contains("cf_clearance") }
    }

    /** CloudStream's own CloudflareKiller heuristic — a 403/503 served by Cloudflare. */
    fun isCloudflareChallenge(response: Response): Boolean {
        if (response.code != 403 && response.code != 503) return false
        val server = response.header("Server")?.lowercase() ?: return false
        return server.contains("cloudflare")
    }

    /**
     * OkHttp interceptor body, shared by the Http client and the jar's app
     * client. Passes the request through (attaching any existing cf_clearance
     * cookie so already-verified hosts skip the challenge entirely); on a
     * Cloudflare challenge it closes the challenge response, ensures a
     * cf_clearance exists — auto-opening the verify WebView when needed, never
     * from the main thread, which must not block — and retries with the cookie
     * + the WebView UA. Returns the challenge response when no clearance could
     * be obtained.
     */
    fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val firstReq = if (request.header("Cookie") == null) {
            val c = clearanceFor(request.url.toString())
            if (c != null) request.newBuilder().header("Cookie", c).build() else request
        } else request
        val first = chain.proceed(firstReq)
        if (!isCloudflareChallenge(first)) return first
        val url = request.url.toString()
        first.close()

        val host = request.url.host
        if (host == null) return chain.proceed(request)

        val now = System.currentTimeMillis()
        val cooled = synchronized(lock) { (dismissedUntil[host] ?: 0L) < now }
        if (clearanceFor(url) == null && autoOpenEnabled && cooled &&
            Looper.myLooper() != Looper.getMainLooper()
        ) {
            solve(host, url)
        }

        val cookie = clearanceFor(url)
        if (cookie != null) {
            val ua = runCatching { HikariApp.instance.effectiveWebViewUa() }.getOrNull() ?: Http.WEBVIEW_UA
            val retry = request.newBuilder()
                .header("User-Agent", ua)
                .header("Cookie", cookie)
                .build()
            return chain.proceed(retry)
        }
        return chain.proceed(request)
    }

    /** Blocking CF solve for [host]/[url]: auto-open the verify WebView once,
     *  wait for the clearance (or the view closing / timeout). Only ever called
     *  from background threads — it blocks. */
    private fun solve(host: String, url: String) {
        val latch: CountDownLatch = synchronized(lock) {
            inFlight.getOrPut(host) { CountDownLatch(1) }
        }
        val creator = synchronized(lock) { launchedFor.add(host) }
        if (creator) {
            Handler(Looper.getMainLooper()).post {
                try {
                    val app = HikariApp.instance
                    val intent = Intent(app, WebViewActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        putExtra("url", url)
                        putExtra("title", "Cloudflare verification")
                        putExtra("autoCloseWhenCloudflarePassed", true)
                        putExtra("verifyHost", host)
                    }
                    app.startActivity(intent)
                } catch (t: Throwable) {
                    android.util.Log.e("CloudflareVerifier", "verify launch failed", t)
                    // don't let waiters hang until the deadline
                    synchronized(lock) { latch.countDown() }
                }
            }
        }
        try {
            latch.await(SOLVE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
        }
        val cleared = clearanceFor(url) != null
        synchronized(lock) {
            if (inFlight[host] === latch) inFlight.remove(host)
            launchedFor.remove(host)
            if (!cleared) dismissedUntil[host] = System.currentTimeMillis() + DISMISS_COOLDOWN_MS
        }
    }

    /** Called by the verify WebView when it closes (challenge passed or the
     *  user dismissed it) — wakes every waiter so the retry runs immediately
     *  instead of waiting out the full deadline. */
    fun onVerifyViewClosed(host: String?) {
        if (host == null) return
        synchronized(lock) { inFlight[host]?.countDown() }
    }
}
