package com.hikari.app.net

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.hikari.app.HikariApp
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
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
 * the fingerprint the clearance was minted for) and retry; otherwise we solve
 * it in a hidden off-screen WebView (nothing ever pops over the player) — wait
 * for the clearance to appear, then retry. A challenge the hidden solver cannot
 * pass is handled by context: during a bulk provider SEARCH the view is never
 * popped (a search must not throw windows over whatever the user is doing) —
 * the host is only recorded so the UI can say "Cloudflare check needed on X"
 * instead of the misleading "no matching title" — while a single site the user
 * is actually waiting on (play/extract) opens the visible verify view so the
 * check can be passed by hand, which is what the human-clickable challenges
 * need. A per-host in-flight guard keeps concurrent requests from stacking
 * WebViews,
 * and a short cooldown stops a just-failed solve from being retried in a tight
 * loop by the next request to the same host.
 */
object CloudflareVerifier {

    private const val SOLVE_TIMEOUT_MS = 90_000L
    // How long the hidden off-screen solver gets before giving up. Generous so
    // a solvable challenge usually passes invisibly; interactive challenges
    // (which need a human click) are left to the user's manual verify button.
    private const val HIDDEN_SOLVE_TIMEOUT_MS = 20_000L
    // How long a failed hidden solve suppresses retrying the same host, so a
    // burst of requests can't stack solver after solver; the next request a few
    // seconds later does retry.
    private const val HIDDEN_RETRY_COOLDOWN_MS = 5_000L
    // At most this many hidden solves run at once. A bulk search over every
    // installed extension can hit a dozen challenged hosts simultaneously, and
    // one WebView each would melt the phone and starve the searches themselves.
    private const val MAX_CONCURRENT_SOLVES = 2
    // A host we already failed to clear is not worth popping the verify view
    // for again this soon.
    private const val VERIFY_VIEW_COOLDOWN_MS = 5 * 60_000L

    /** Wall-clock budget for one hidden solve. Shortened while a bulk search
     *  pass is running (see [bulkSearchActive]): a repo stuck behind a challenge
     *  used to hold its search slot for the full 20s, and because the wait is a
     *  blocking latch (not a cancellable suspension point) the search timeout
     *  could not preempt it — which starved the repos still waiting to be asked
     *  and made an installed repo the user knows carries the title look like a
     *  repo that does not. */
    @Volatile
    var hiddenSolveBudgetMs: Long = HIDDEN_SOLVE_TIMEOUT_MS

    /** True while a bulk provider-search pass is running. During a pass a failed
     *  hidden solve only RECORDS the host; outside a pass it opens the visible
     *  verify view, because the user is then waiting on one specific site and a
     *  human-clickable challenge has to be shown to them. */
    @Volatile
    var bulkSearchActive = false

    /** Hosts that answered with a Cloudflare challenge we could not pass, with
     *  the time of the attempt. Drives the actionable hint ("Cloudflare check
     *  needed on <host>") instead of letting the block read as "this repo does
     *  not carry the title". */
    private val blockedHosts = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** When the visible verify view was last auto-opened for a host, so a burst
     *  of challenged requests cannot pop dialog after dialog. */
    private val verifyViewOpened = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private val solveSlots = Semaphore(MAX_CONCURRENT_SOLVES)

    private val lock = Any()
    private val inFlight = HashMap<String, CountDownLatch>()
    private val launchedFor = HashSet<String>()
    private val dismissedUntil = HashMap<String, Long>()
    private val hiddenSolves = HashMap<String, WebView>()

    /** Master switch: attempt the automatic (hidden, off-screen) Cloudflare
     *  solve on a challenge. When off, the challenge response is handed to the
     *  caller and the user verifies manually via the Home globe button. */
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
     *  the hidden solver would never run). */
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
     *  ever be minted — so attempting to solve one would only waste time and
     *  leave the request stuck on the blocked page. */
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
     * Cloudflare challenge it closes the challenge response, attempts a
     * cf_clearance via the hidden off-screen solver — never from the main
     * thread, which must not block — and retries with the cookie + the WebView
     * UA. Returns the challenge response when no clearance could be obtained.
     */
    fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
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
        if (clearanceFor(url) == null && autoOpenEnabled && solvable && cooled &&
            Looper.myLooper() != Looper.getMainLooper()
        ) {
            solve(host, url, solvable)
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
        // Still challenged (a human-clickable one, or the solver ran out of its
        // slot/budget). Record the host so the UI can name the real reason —
        // "Cloudflare check needed on <host>" — instead of reporting this repo
        // as having no matching title.
        noteBlocked(host)
        return chain.proceed(request)
    }

    /** Blocking CF solve for [host]/[url]. Runs the hidden off-screen solver —
     *  nothing pops over the player; if it can't mint a clearance (an
     *  interactive challenge that needs a human click, a hard WAF block, or
     *  another solve already occupies the solver slots), the host is recorded
     *  and — outside a bulk search — the visible verify view is opened so the
     *  user can pass the check by hand instead of the request failing with no
     *  explanation. Only ever called from background threads — it blocks. */
    private fun solve(host: String, url: String, solvable: Boolean) {
        val latch: CountDownLatch = synchronized(lock) {
            inFlight.getOrPut(host) { CountDownLatch(1) }
        }
        val creator = synchronized(lock) { launchedFor.add(host) }
        if (creator) {
            // Don't pile WebViews on top of each other (see MAX_CONCURRENT_SOLVES).
            val gotSlot = try {
                solveSlots.tryAcquire(if (bulkSearchActive) 2L else 15L, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                false
            }
            val solved = if (gotSlot) {
                try {
                    solveHidden(host, url)
                } finally {
                    solveSlots.release()
                }
            } else false
            if (!solved) {
                noteBlocked(host)
                maybeOpenVerifyView(host, url, solvable)
            }
            // Whether the hidden solve minted a clearance or not, wake every
            // waiter so the retry runs right away instead of sitting out the
            // 90s solve deadline.
            synchronized(lock) { latch.countDown() }
        }
        try {
            latch.await(SOLVE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
        }
        val cleared = clearanceFor(url) != null
        synchronized(lock) {
            if (inFlight[host] === latch) inFlight.remove(host)
            launchedFor.remove(host)
            if (!cleared) {
                // Short cooldown so a just-failed hidden solve isn't retried in
                // a tight loop by concurrent requests to the same host, but the
                // next request (a few seconds later) does retry it.
                dismissedUntil[host] = System.currentTimeMillis() + HIDDEN_RETRY_COOLDOWN_MS
            }
        }
    }

    /** Records a host whose challenge we could not pass (with the time), so the
     *  UI can name the real reason a repo produced nothing. */
    private fun noteBlocked(host: String) {
        val now = System.currentTimeMillis()
        blockedHosts[host] = now
        if (blockedHosts.size > 64) {
            val cutoff = now - 30 * 60_000L
            blockedHosts.entries.removeAll { it.value < cutoff }
        }
    }

    /** The most recently challenged host we could not clear, or null when
     *  nothing was blocked within [maxAgeMs]. Lets the search UI say
     *  "Cloudflare check needed on X" instead of "no matching title". */
    fun blockedHost(maxAgeMs: Long = 3 * 60_000L): String? {
        val now = System.currentTimeMillis()
        return blockedHosts.entries
            .filter { now - it.value <= maxAgeMs }
            .maxByOrNull { it.value }
            ?.key
    }

    /** Drops a host's blocked record (called once its clearance is in hand). */
    fun clearBlocked(host: String?) {
        if (host != null) blockedHosts.remove(host)
    }

    /**
     * Opens the visible verification WebView for [host] — the dialog the user
     * expects when a site needs a human click. Suppressed during a bulk search
     * pass (a pass must never throw windows over what the user is doing; the
     * host is recorded instead), rate-limited per host so a burst of challenged
     * requests can't pop dialog after dialog.
     */
    private fun maybeOpenVerifyView(host: String, url: String, solvable: Boolean) {
        if (!autoOpenEnabled || !solvable || bulkSearchActive) return
        val now = System.currentTimeMillis()
        if (now - (verifyViewOpened[host] ?: 0L) < VERIFY_VIEW_COOLDOWN_MS) return
        verifyViewOpened[host] = now
        runCatching {
            val app = HikariApp.instance
            app.startActivity(
                Intent(app, com.hikari.app.web.WebViewActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("url", url)
                    putExtra("title", "Verify: $host")
                    putExtra("autoCloseWhenCloudflarePassed", true)
                    putExtra("verifyHost", host)
                }
            )
        }.onFailure {
            android.util.Log.w("CloudflareVerifier", "could not open verify view for $host", it)
        }
    }

    /** Hidden off-screen solver: loads the challenged URL in an INVISIBLE
     *  WebView (real dimensions so the challenge JS gets a sane viewport, but
     *  never attached to a window and never drawn) and polls the shared cookie
     *  jar for cf_clearance. Returns true when a clearance appeared.
     *
     *  This is Hikari's dedicated "video verification" WebView — deliberately
     *  SEPARATE from the browsing WebView's redirect protection (the extension
     *  tab's ad-block webview cancels main-frame redirects to foreign hosts,
     *  which is exactly what a streaming site's redirect to the real video
     *  page looks like — the user's discovery of why the movie page never
     *  opened). Here redirects are NEVER blocked (the challenge → real page
     *  chain must complete), but ad hosts still are (same hosts lists the
     *  browsing view uses), and media is never blocked. */
    private fun solveHidden(host: String, url: String): Boolean {
        val created = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            try {
                val wv = WebView(HikariApp.instance)
                val ws = wv.settings
                ws.javaScriptEnabled = true
                ws.domStorageEnabled = true
                ws.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                ws.userAgentString = runCatching { HikariApp.instance.effectiveWebViewUa() }
                    .getOrNull() ?: Http.WEBVIEW_UA
                wv.visibility = View.INVISIBLE
                wv.layout(0, 0, 480, 320)
                wv.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                // Ad-blocking (cached hosts lists + built-ins) — subresources
                // from ad hosts are dropped so the verify page can't pull ads,
                // but media and main-frame redirects always pass. No redirect
                // protection here: that's the whole point of this WebView.
                val blocked = runCatching { AdBlocker.cachedResolve(HikariApp.instance) }
                    .getOrDefault(AdBlocker.BUILTIN.toSet())
                wv.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest
                    ): WebResourceResponse? {
                        if (request.isForMainFrame) return null
                        val rh = request.url.host ?: return null
                        if (AdBlocker.matches(rh, blocked) && !AdBlocker.isMediaLike(request)) {
                            return WebResourceResponse(
                                "text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))
                            )
                        }
                        return null
                    }

                    override fun onRenderProcessGone(
                        view: WebView?,
                        detail: android.webkit.RenderProcessGoneDetail?
                    ): Boolean {
                        // Claim the crash: returning false here lets the
                        // platform kill the whole app process (which is what
                        // produced the "app crashed on a previous launch"
                        // banner). The solve loop's own timeout ends the wait,
                        // so nothing hangs while we keep the app alive.
                        android.util.Log.w(
                            "CloudflareVerifier", "renderer gone for $host — keeping app alive"
                        )
                        return true
                    }
                }
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
                synchronized(lock) { hiddenSolves[host] = wv }
                wv.loadUrl(url)
            } catch (t: Throwable) {
                android.util.Log.e("CloudflareVerifier", "hidden solve create failed", t)
            } finally {
                created.countDown()
            }
        }
        created.await(5, TimeUnit.SECONDS)
        val budget = hiddenSolveBudgetMs.coerceAtLeast(2_000L)
        val deadline = System.currentTimeMillis() + budget
        while (System.currentTimeMillis() < deadline) {
            if (clearanceFor(url) != null) break
            Thread.sleep(400)
        }
        val solved = clearanceFor(url) != null
        val wv = synchronized(lock) { hiddenSolves.remove(host) }
        if (wv != null) {
            Handler(Looper.getMainLooper()).post {
                runCatching {
                    wv.stopLoading()
                    wv.destroy()
                }
            }
        }
        return solved
    }

    /** Called by the verify WebView when it closes (challenge passed or the
     *  user dismissed it) — wakes every waiter so the retry runs immediately
     *  instead of waiting out the full deadline. */
    fun onVerifyViewClosed(host: String?) {
        if (host == null) return
        synchronized(lock) { inFlight[host]?.countDown() }
        // The user just went through the site's verification: if a clearance is
        // in the jar now, any "Cloudflare check needed" note for it is stale.
        if (clearanceFor("https://$host/") != null) clearBlocked(host)
    }
}
