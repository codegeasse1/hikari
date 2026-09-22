package com.hikari.app.net

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * Clears Cloudflare (and the JS-interstitial family that behaves like it) for
 * the client EXTENSIONS use — see [CloudflareSolver] for why an extension
 * cannot do this for itself.
 *
 * Placed on [eu.kanade.tachiyomi.network.NetworkHelper]'s client, i.e. below
 * every Aniyomi/Mihon extension, because that client is the one they build on:
 * `AnimeHttpSource.network.client` and any `network.client.newBuilder()`
 * wrapper an extension adds (AnimeOnline.Ninja wraps it with its own
 * `VrfInterceptor`) all sit on top of this. The steps are the ones every
 * Mihon-family app takes, minus the dialog:
 *
 *  1. attach the cookies the shared WebView jar already holds for the host — a
 *     clearance the user earned with the globe button, or one an earlier solve
 *     earned — AND, when one of those cookies is a `cf_clearance`, present the
 *     request under the User-Agent that clearance was minted for. After that
 *     first solve, this alone is what makes the requests sail through;
 *  2. if the response is still a challenge, and another request for that host
 *     is not already solving it, load the page in an offscreen WebView
 *     ([CloudflareSolver], advertising the same UA) and retry ONCE with the
 *     cookie it earned;
 *  3. if that does not clear it either, record the host in
 *     [CloudflareVerifier] so the UI can offer the user's own verification
 *     (the globe button) instead of pretending the extension is broken.
 *
 * Images are deliberately NOT solved: a challenge on an image is a dead end
 * (the WebView loads documents, not pictures) and solving it would spend 20
 * seconds per cover. They get through on the clearance the document's solve
 * already stored in the jar — the same rule Nekoread's CloudflareInterceptor
 * uses.
 */
class ExtensionCloudflareInterceptor(
    /** The UA extension requests use when they set none — the one the solver's
     *  WebView must advertise for the clearance to be accepted. */
    private val defaultUserAgent: () -> String,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val first = chain.proceed(withJarCookies(request))
        if (!isChallenge(first)) return first
        if (isImageRequest(request)) return first
        val url = request.url.toString()
        val solved = CloudflareSolver.solve(
            url = url,
            // The solve runs in a WebView, and the clearance it earns has to be
            // usable by the requests that follow — so the WebView advertises the
            // SAME fingerprint as the verify view the user taps, not whatever UA
            // the extension happened to set on this one request. Two different
            // fingerprints fighting over one `cf_clearance` cookie is what made a
            // verified site fall back to challenging: every solve overwrote the
            // cookie the reader's own verification had earned.
            userAgent = webViewUa(),
            referer = request.header("Referer"),
        )
        if (!solved) return first
        first.close()
        val retry = chain.proceed(withJarCookies(request))
        if (isChallenge(retry)) {
            // Solved the challenge and still got one — treat the host as
            // needing the user's own verification rather than looping.
            CloudflareVerifier.markBlocked(url)
        }
        return retry
    }

    /** The request with the shared WebView jar's cookies attached, presented the
     *  way Cloudflare needs them. */
    private fun withJarCookies(request: Request): Request {
        val url = request.url.toString()
        val cookie = runCatching {
            android.webkit.CookieManager.getInstance().getCookie(url)
        }.getOrNull().orEmpty()
        if (cookie.isBlank()) return request
        val builder = request.newBuilder()
        if (request.header("Cookie") == null) builder.header("Cookie", cookie)
        // A `cf_clearance` is bound to the User-Agent it was minted for, and the
        // clearance in this jar was minted by a WebView. Presenting it with the
        // extension's own UA (or the default browser one) is a guaranteed
        // rejection, which is exactly what "I verified the site and it still says
        // I need to verify" is: the cookie was being sent, and thrown away. So a
        // request that carries a clearance advertises the UA the verify WebView
        // earns it under — HikariApp.effectiveWebViewUa, the same string the
        // verify view and the solver's view install.
        if (cookie.contains("cf_clearance")) {
            builder.header("User-Agent", webViewUa())
        }
        return builder.build()
    }

    /** The UA a Cloudflare clearance is minted for on this device. */
    private fun webViewUa(): String = runCatching {
        com.hikari.app.HikariApp.instance.effectiveWebViewUa()
    }.getOrNull().orEmpty().ifBlank { defaultUserAgent() }

    /**
     * True when a response is a bot wall rather than the page the extension
     * asked for.
     *
     * The cheap, header-only signals come first, and the body is only read for
     * the statuses a wall actually uses: an extension streams real media through
     * this client (video manifests and segments), and peeking a 64 KiB window
     * out of every response would be pure cost on the healthy ones.
     */
    private fun isChallenge(response: Response): Boolean {
        // Cloudflare's own signature — a 403/503 whose `Server` says cloudflare,
        // or the authoritative `cf-mitigated` header of a managed challenge.
        val code = response.code
        val server = response.header("Server")?.lowercase().orEmpty()
        if (!response.header("cf-mitigated").isNullOrBlank()) return true
        if (code == 403 || code == 503) {
            if (server.contains("cloudflare")) return true
        } else if (code != 429) {
            return false
        }
        // A wall answers with a small HTML/JS page. Anything else at this status
        // is the site's own refusal (or its rate limit) and is handed straight
        // back to the extension.
        val type = response.header("Content-Type")?.lowercase().orEmpty()
        if (type.isNotBlank() &&
            !type.contains("html") && !type.contains("text") && !type.contains("javascript")
        ) {
            return false
        }
        val body = runCatching { response.peekBody(64L * 1024).string() }.getOrNull().orEmpty()
        if (body.isBlank()) return false
        // Interstitial-only strings: "One moment, please" with a `wsidchk-form`
        // is what the AnimeOnline/DooPlay family serves (with 403) until its
        // script has run; the rest are Cloudflare's challenge markers.
        return HARD_MARKERS.any { body.contains(it, ignoreCase = true) }
    }

    private fun isImageRequest(request: Request): Boolean {
        val last = request.url.pathSegments.lastOrNull() ?: return false
        return last.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS
    }

    private companion object {
        /** Interstitial-only strings. All of them are generated by the challenge
         *  scripts themselves, so none of them can appear on a content page. */
        private val HARD_MARKERS = listOf(
            "just a moment",
            "one moment, please",
            "wsidchk",
            "cf_chl_",
            "challenge-platform",
            "cf-browser-verification",
            "checking your browser",
            "verify you are human",
            "attention required",
        )

        private val IMAGE_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "gif", "webp", "avif", "bmp", "heic", "heif", "jfif", "svg",
        )
    }
}
