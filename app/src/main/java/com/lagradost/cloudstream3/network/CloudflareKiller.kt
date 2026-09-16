@file:Suppress("DEPRECATION")

package com.lagradost.cloudstream3.network

import android.webkit.CookieManager
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.net.URI

/**
 * Hikari's replacement for CloudStream's own `CloudflareKiller`.
 *
 * The jar ships `com.lagradost.cloudstream3.network.CloudflareKiller` compiled
 * against the ANDROID CloudStream API, and it is a landmine for this app in
 * three separate ways:
 *
 *  1. `init` calls `CookieManager.getInstance().removeAllCookies(null)`. Every
 *     extension that wraps its requests with a `CloudflareKiller()` instance
 *     (Cinemacity's whole interceptor stack does) therefore WIPES the WebView
 *     cookie jar — including the `cf_clearance` the user just earned with the
 *     app's verify WebView — on each construction, which is exactly why a
 *     verification never stuck and the challenge kept coming back.
 *  2. On a 403/503 it calls `WebViewResolver(...).resolveUsingWebView(url)`,
 *     i.e. it loads the challenged site — a real, full page load — from the
 *     networking stack, with no user interaction at all. Hikari's rule is that
 *     a WebView is only ever opened by an explicit tap (the globe/verify
 *     button), never automatically.
 *  3. It calls `WebViewResolver.Companion.getWebViewUserAgent1()`, which the
 *     jar's own (desktop) `WebViewResolver` stub never declared, so any
 *     request that reached it died with
 *     `NoSuchMethodError: No virtual method getWebViewUserAgent1()...
 *     at CloudflareKiller.proceed(CloudflareKiller.kt:99)`
 *     on an OkHttp dispatcher thread — an immediate app crash (the 0.3.85
 *     `hikari-crash.log`, and the reason Home's crash banner kept appearing).
 *
 * So the jar classes are excluded in `cloudstreamJarClean` (app/build.gradle.kts)
 * and this class is shadowed here with the SAME public surface the jar exposed
 * — verified against the jar's own method table, so a plugin compiled against
 * it links exactly: `<init>()V`, `getSavedCookies()Ljava/util/Map;`,
 * `getCookieHeaders(Ljava/lang/String;)Lokhttp3/Headers;`,
 * `intercept(Lokhttp3/Interceptor$Chain;)Lokhttp3/Response;` and the companion's
 * `parseCookieMap(Ljava/lang/String;)Ljava/util/Map;`.
 *
 * What it does instead of the WebView dance: the ONE thing that actually
 * works — use the clearance the user's own verification put in the WebView
 * cookie jar (the same cookie Hikari's CloudflareVerifier attaches), retry a
 * Cloudflare 403/503 exactly once with it, and never touch a WebView or the
 * cookie store. Hikari's `CloudflareVerifier` interceptor, which is on the
 * client every CS3 plugin gets, is what records the blocked host and lets the
 * UI offer the deliberate verification tap.
 */
class CloudflareKiller : Interceptor {

    companion object {
        const val TAG = "CloudflareKiller"

        /** `"a=1; b=2"` → `{"a":"1", "b":"2"}` — CloudStream's own parser. */
        fun parseCookieMap(cookie: String): Map<String, String> {
            return cookie.split(";")
                .mapNotNull { part ->
                    val eq = part.indexOf('=')
                    if (eq <= 0) return@mapNotNull null
                    part.substring(0, eq).trim() to part.substring(eq + 1).trim()
                }
                .filter { it.first.isNotBlank() && it.second.isNotBlank() }
                .toMap()
        }
    }

    /** Cookies this interceptor has seen for a host, kept in the shape
     *  CloudStream's own class exposed (`host -> cookie name/value map`). */
    val savedCookies: MutableMap<String, Map<String, String>> =
        java.util.concurrent.ConcurrentHashMap()

    /** User-agent + previously-seen cookie headers for [url], mirroring what
     *  CloudStream's class returned (some plugins pass this straight into a
     *  request). Never throws — a broken URL simply yields no headers. */
    fun getCookieHeaders(url: String): Headers = runCatching {
        val builder = Headers.Builder()
        userAgent()?.takeIf { it.isNotBlank() }?.let { builder.add("user-agent", it) }
        savedCookies[hostOf(url)]?.forEach { (k, v) -> builder.add(k, v) }
        // The real session the request needs: the jar's own clearance, attached
        // as a proper Cookie header (what the CDN actually reads).
        webViewCookie(url)?.takeIf { it.contains("cf_clearance") }?.let { builder.add("Cookie", it) }
        builder.build()
    }.getOrDefault(Headers.Builder().build())

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = hostOf(request.url.toString())

        // Attach the jar's clearance (if any) up front: after the user has
        // verified a host once, this is what makes every later request from
        // this extension sail straight through.
        val initialCookie = webViewCookie(request.url.toString())
        val withCookie = if (request.header("Cookie") == null && !initialCookie.isNullOrBlank()) {
            request.newBuilder().header("Cookie", initialCookie).build()
        } else request

        val response = chain.proceed(withCookie)

        // Only a Cloudflare-served 403/503 is a challenge; everything else is
        // handed back untouched.
        if (response.code != 403 && response.code != 503) return response
        val server = response.header("Server")?.lowercase().orEmpty()
        if (!server.contains("cloudflare")) return response

        // A challenge WE cannot pass: no WebView is ever created here (see the
        // class doc). If a clearance appeared in the jar in the meantime — the
        // user just verified — remember it and retry exactly once.
        val fresh = webViewCookie(request.url.toString())
        if (fresh.isNullOrBlank() || fresh == initialCookie) return response
        if (host != null) savedCookies[host] = parseCookieMap(fresh)
        response.close()
        return chain.proceed(request.newBuilder().header("Cookie", fresh).build())
    }

    private fun hostOf(url: String): String? =
        runCatching { URI(url).host?.lowercase() }.getOrNull()

    private fun webViewCookie(url: String): String? =
        runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()

    /** The UA the WebViews advertise — the fingerprint a `cf_clearance` was
     *  minted against, so it is also the UA the retry must use. */
    private fun userAgent(): String? =
        WebViewResolver.webViewUserAgent
            ?: runCatching { com.hikari.app.HikariApp.instance.effectiveWebViewUa() }.getOrNull()
}
