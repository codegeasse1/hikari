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

    /** CloudStream's own CloudflareKiller heuristic — a 403/503 served by
     *  Cloudflare — OR any response whose body is a known CF challenge/block
     *  page (some challenge modes answer with a 200/other status carrying the
     *  challenge HTML, so the status+Server check alone would miss them and
     *  the verify WebView would never auto-open). */
    fun isCloudflareChallenge(response: Response, bodyText: String = peekBody(response)): Boolean {
        if (response.code == 403 || response.code == 503) {
            val server = response.header("Server")?.lowercase()
            if (server != null && server.contains("cloudflare")) return true
        }
        if (bodyText.isEmpty()) return false
        return HARD_BLOCK_MARKERS.any { bodyText.contains(it) } ||
            CHALLENGE_MARKERS.any { bodyText.contains(it) }
    }

    /** Body markers that mean a Cloudflare response is a HARD WAF block
     *  ("Sorry, you have been blocked") rather than a solvable challenge. A
     *  block can never be passed by the verify WebView — no cf_clearance will
     *  ever be minted — so auto-opening for one would only leave a dead browser
     *  sitting on top of the player (an episode tap popping a blocked host's
     *  page while the player already has a working source). */
    private val HARD_BLOCK_MARKERS = listOf(
        "you have been blocked",
        "sorry, you have been blocked",
        "access denied",
        "request blocked",
        "cf-error-details",
        "error 1020",
        "cf-error-code",
    )

    /** Body markers that mean the response is a genuine solvable WAF challenge
     *  (managed challenge / Turnstile) the verify WebView can actually pass. */
    private val CHALLENGE_MARKERS = listOf(
        "just a moment",
        "attention required",
        "challenges.cloudflare.com",
        "challenge-platform",
        "cf-chl",
        "cf_chl_opt",
        "turnstile",
        "hcaptcha",
        "verify you are human",
        "checking your browser",
        "performing security verification",
    )

    /** Peeks the first 64 KiB of the response body (without consuming it, so
     *  the caller still reads it normally), gunzipping when needed. */
    private fun peekBody(response: Response): String {
        return runCatching {
            val bytes = response.peekBody(64 * 1024).bytes()
            val raw = if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
                java.util.zip.GZIPInputStream(bytes.inputStream().buffered()).readBytes()
            } else bytes
            String(raw, 0, minOf(raw.size, 64 * 1024), Charsets.UTF_8).lowercase()
        }.getOrNull().orEmpty()
    }

    /** True when the challenge response is one the verify WebView can actually
     *  solve. Explicitly-blocked pages return false, undecidable bodies
     *  default to solvable so the feature keeps working if decoding fails. */
    private fun isSolvableChallenge(response: Response, bodyText: String): Boolean {
        if (HARD_BLOCK_MARKERS.any { bodyText.contains(it) }) return false
        return bodyText.isBlank() || CHALLENGE_MARKERS.any { bodyText.contains(it) }
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
        // Nuvio provider fetches set this header: they still get any existing
        // cf_clearance attached and a UA/cookie retry, but the verify WebView
        // must NEVER auto-open full-screen over the player from one of them
        // (ad/anti-bot pages were popping over playback).
        val suppressAutoOpen = request.header("X-Hikari-NoCfAutoOpen") == "1"
        val firstReq = if (request.header("Cookie") == null) {
            val c = clearanceFor(request.url.toString())
            if (c != null) request.newBuilder().header("Cookie", c).build() else request
        } else request
        val first = chain.proceed(firstReq)
        val bodyText = peekBody(first)
        if (!isCloudflareChallenge(first, bodyText)) return first
        val url = request.url.toString()
        val solvable = isSolvableChallenge(first, bodyText)
        first.close()

        val host = request.url.host
        if (host == null) return chain.proceed(request)

        val now = System.currentTimeMillis()
        val cooled = synchronized(lock) { (dismissedUntil[host] ?: 0L) < now }
        if (clearanceFor(url) == null && autoOpenEnabled && solvable && cooled && !suppressAutoOpen &&
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
