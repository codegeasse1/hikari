package com.hikari.app.net

import android.webkit.CookieManager
import com.hikari.app.HikariApp
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.CountDownLatch

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
 * the fingerprint the clearance was minted for) and retry with it.
 *
 * NOTHING in this object ever creates a WebView. A challenge it cannot clear
 * by reusing an existing clearance is simply handed back to the caller, and
 * the host is recorded so the UI can offer the deliberate verification: the
 * user taps the globe button on Home, the verify WebView opens on their tap
 * only, and the clearance it earns is reused by every later request. Earlier
 * builds loaded the challenge in a hidden off-screen WebView here; that is
 * gone on purpose (a verification page must never open on its own, and the
 * hidden loads also fought the user's own verification).
 */
object CloudflareVerifier {

    /** Hosts that answered with a Cloudflare challenge we could not pass, with
     *  the time of the attempt. Drives the actionable hint ("Cloudflare check
     *  needed on <host>") instead of letting the block read as "this repo does
     *  not carry the title". */
    private val blockedHosts = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private val lock = Any()
    private val inFlight = HashMap<String, CountDownLatch>()

    /** cf_clearance (or the full cookie string containing it) for [url] from the
     *  WebView cookie jar — the jar the verify WebView keeps populated. */
    fun clearanceFor(url: String): String? {
        val cookie = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
        return cookie?.takeIf { it.contains("cf_clearance") }
    }

    /** CloudStream's own CloudflareKiller heuristic — a 403/503 served by
     *  Cloudflare — OR any response whose body is a known CF challenge/block
     *  page (some challenge modes answer with a 200/other status carrying the
     *  challenge HTML, so the status+Server check alone would miss them). */
    fun isCloudflareChallenge(response: Response, bodyText: String = peekBody(response)): Boolean {
        if (response.code == 403 || response.code == 503) {
            val server = response.header("Server")?.lowercase()
            if (server != null && server.contains("cloudflare")) return true
        }
        if (bodyText.isEmpty()) return false
        return CF_MARKERS.any { bodyText.contains(it) }
    }

    /**
     * Body markers that mean the response really IS a Cloudflare interstitial —
     * a managed challenge ("Just a moment…", `challenge-platform`, `cf_chl_opt`)
     * or a hard WAF block ("you have been blocked", `cf-error-details`).
     *
     * Deliberately EXCLUDES the loose strings an ordinary page can carry:
     * `turnstile`, `hcaptcha`, `cf-chl` and `access denied`/`request blocked`
     * all appear on perfectly healthy pages (a site that embeds a Turnstile or
     * hCaptcha widget, a CDN's own 403, a copy-pasted footer), and matching them
     * made Hikari record the site as "challenged" — which is how a working
     * extension ended up reported as "Cloudflare wants a verification on this
     * site". Every real CF interstitial above also carries `challenge-platform`
     * or `cf-error-*`, so nothing genuine is lost.
     */
    private val CF_MARKERS = listOf(
        "just a moment",
        "attention required",
        "checking your browser",
        "performing security verification",
        "verify you are human",
        "challenge-platform",
        "cf_chl_opt",
        "cf-chl-opt",
        "cf_chl_",
        "you have been blocked",
        "cf-error-details",
        "cf-error-code",
        "error 1020",
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

    /** True when a provider's own error text is really a Cloudflare / WAF
     *  verification wall rather than something the user can act on. Used to
     *  keep the raw extension wording ("Cloudflare blocked. Go to Settings 'n
     *  Bypass Cloudflare.") out of the per-source, per-extension and player
     *  diagnostics — those lists exist to pick a server, not to read an
     *  extension's troubleshooting note — while still letting the Home screen
     *  say, in Hikari's own words, that THIS provider needs a verification. */
    fun isVerificationMessage(message: String?): Boolean {
        val m = message?.lowercase() ?: return false
        if (m.isBlank()) return false
        return m.contains("cloudflare") ||
            m.contains("verify you are human") ||
            m.contains("performing security verification") ||
            m.contains("challenge-platform") ||
            m.contains("cf_clearance") ||
            m.contains("just a moment")
    }

    /**
     * OkHttp interceptor body, shared by the Http client and the jar's app
     * client. Attaches any cf_clearance the verify WebView already earned for
     * the host (so an already-verified host skips the challenge entirely), and
     * on a Cloudflare challenge retries once with that clearance plus the
     * WebView UA it was minted for. No WebView is ever created here (see the
     * class doc) — a challenge nothing can clear is handed back to the caller
     * and the host recorded, so the UI can offer the user's own verification.
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
        first.close()

        val host = request.url.host ?: return chain.proceed(request)

        // Only a clearance ALREADY in the WebView cookie jar can clear this —
        // the user earned it by tapping verify. Nothing is loaded in the
        // background to try to earn one.
        val cookie = clearanceFor(url)
        if (cookie != null) {
            val ua = runCatching { HikariApp.instance.effectiveWebViewUa() }.getOrNull() ?: Http.WEBVIEW_UA
            val retry = request.newBuilder()
                .header("User-Agent", ua)
                .header("Cookie", cookie)
                .build()
            val second = chain.proceed(retry)
            if (isCloudflareChallenge(second, peekBody(second))) noteBlocked(host) else clearBlocked(host)
            return second
        }
        // Still challenged, nothing to reuse: record the host so the UI can
        // offer the deliberate verification (the globe button) instead of
        // reporting this repo as having no matching title.
        noteBlocked(host)
        return chain.proceed(request)
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

    /**
     * Public form of [noteBlocked] for callers that hold a URL rather than a
     * host: the SkyStream fetch bridge (which spots a challenge body in its own
     * fetch log) and the WebView resolver (which spots one in the page title).
     * Recording it here is what lets [needsVerification] short-circuit every
     * LATER attempt at the same host — the whole point of the record.
     */
    fun markBlocked(url: String) {
        val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull() ?: return
        if (host.isNotBlank()) noteBlocked(host)
    }

    /**
     * True when a WebView page TITLE is a Cloudflare interstitial. The title is
     * the earliest reliable signal a challenge is on screen — it is set before
     * the challenge script finishes, and on a hard block it is the only thing
     * that ever changes — so the resolver uses it to stop waiting immediately
     * instead of burning its whole timeout on a page that will never play.
     */
    fun isChallengeTitle(title: String?): Boolean {
        val t = title?.lowercase()?.trim() ?: return false
        if (t.isBlank()) return false
        return CHALLENGE_TITLES.any { t.contains(it) }
    }

    /** Titles Cloudflare's interstitials use (blocks and managed challenges). */
    private val CHALLENGE_TITLES = listOf(
        "just a moment",
        "attention required",
        "checking your browser",
        "performing security verification",
        "verify you are human",
        "one more step",
        "ddos protection",
        "security check",
    )

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

    /**
     * True when [host] itself — not some unrelated site — is one we could not
     * pass a challenge on inside [maxAgeMs]. The comparison is exact-or-subdomain
     * in both directions, so `www.example.com` and `example.com` count as the
     * same site while a different host never does.
     *
     * This is what lets a provider's empty catalog be blamed on Cloudflare only
     * when that provider's OWN site was the one challenged: the UI used to ask
     * [blockedHost] (the most recently challenged host in the entire app), so a
     * single blocked site made every extension's failure read as a Cloudflare
     * wall — including extensions that never saw a challenge.
     */
    fun isBlockedHost(host: String?, maxAgeMs: Long = VERIFY_WINDOW_MS): Boolean {
        val h = host?.lowercase()?.trim().orEmpty()
        if (h.isBlank()) return false
        val now = System.currentTimeMillis()
        return blockedHosts.entries.any { (b, at) ->
            now - at <= maxAgeMs && (h == b || h.endsWith(".$b") || b.endsWith(".$h"))
        }
    }

    /** Drops a host's blocked record (called once its clearance is in hand). */
    fun clearBlocked(host: String?) {
        if (host != null) blockedHosts.remove(host)
    }

    /** The window a Cloudflare challenge is remembered for — see [needsVerification]. */
    const val VERIFY_WINDOW_MS = 10 * 60_000L

    /**
     * True when playing [url] would need the "verify you are human" step first:
     * its host answered a Cloudflare challenge we could not clear, and no
     * `cf_clearance` cookie for it is in the jar.
     *
     * The host has to match EXACTLY. A blocked parent (or child) domain used to
     * withhold every source whose host merely sat under it — one challenged
     * `example.com` API hid `cdn3.example.com` media that needed no clearance
     * at all, and with a few dozen extensions reporting challenges at once that
     * could withhold a whole lookup's worth of perfectly playable servers.
     *
     * The source search uses this to SORT what still needs verification to the
     * end of the list, never to empty it: a challenged host must not be able to
     * make a lookup that found servers report "no playable server found".
     */
    fun needsVerification(url: String, maxAgeMs: Long = VERIFY_WINDOW_MS): Boolean {
        val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull() ?: return false
        if (host.isBlank()) return false
        val now = System.currentTimeMillis()
        val challenged = blockedHosts.entries.any { (h, at) -> host == h && now - at <= maxAgeMs }
        if (!challenged) return false
        return clearanceFor(url) == null
    }

    /** Called by the verify WebView when it closes (challenge passed or the
     *  user dismissed it) — wakes any waiter and drops the host's blocked
     *  record once a clearance is in the jar, so the next search lists its
     *  servers again without waiting out the record's own age. */
    fun onVerifyViewClosed(host: String?) {
        // The user has just been through the site's verification. Two things make
        // that stick: the cookies have to reach the DISK (the WebView's jar is
        // written lazily, and a clearance that only lives in memory is an app
        // restart away from being re-challenged), and any record of the host
        // being walled has to go, or every screen keeps saying "verification
        // needed" for a site the user has already verified.
        runCatching { CookieManager.getInstance().flush() }
        if (host == null) return
        synchronized(lock) { inFlight[host]?.countDown() }
        // Ask about the host itself rather than the page that was opened: a
        // clearance is set for the domain, and the site the extension reads may
        // be a different path (or subdomain) than the page the user verified on.
        if (clearanceFor("https://$host/") != null) clearBlocked(host)
    }
}
