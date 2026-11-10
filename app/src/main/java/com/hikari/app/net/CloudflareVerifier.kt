package com.hikari.app.net

import android.webkit.CookieManager
import com.hikari.app.HikariApp
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.CountDownLatch

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

    fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        // A clearance is minted for the WebView UA that solved the challenge —
        // sending it under a different UA gets rejected, so align both.
        val firstReq = if (request.header("Cookie") == null) {
            val c = clearanceFor(request.url.toString())
            if (c != null) {
                val b = request.newBuilder().header("Cookie", c)
                if (c.contains("cf_clearance")) {
                    val ua = runCatching { HikariApp.instance.effectiveWebViewUa() }
                        .getOrNull()?.takeIf { it.isNotBlank() } ?: Http.WEBVIEW_UA
                    b.header("User-Agent", ua)
                }
                b.build()
            } else request
        } else request
        val first = chain.proceed(firstReq)
        val bodyText = peekBody(first)
        if (!isCloudflareChallenge(first, bodyText)) return first
        val url = request.url.toString()
        first.close()

        val host = request.url.host ?: return chain.proceed(request)

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

    fun markBlocked(url: String) {
        val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull() ?: return
        if (host.isNotBlank()) noteBlocked(host)
    }

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
        runCatching { CookieManager.getInstance().flush() }
        if (host == null) return
        synchronized(lock) { inFlight[host]?.countDown() }
        if (clearanceFor("https://$host/") != null) clearBlocked(host)
    }
}
