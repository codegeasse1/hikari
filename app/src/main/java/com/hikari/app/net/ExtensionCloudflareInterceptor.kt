package com.hikari.app.net

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

open class ExtensionCloudflareInterceptor(
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
        if (cookie.contains("cf_clearance")) {
            builder.header("User-Agent", webViewUa())
        }
        return builder.build()
    }

    /** The UA a Cloudflare clearance is minted for on this device. */
    private fun webViewUa(): String = runCatching {
        com.hikari.app.HikariApp.instance.effectiveWebViewUa()
    }.getOrNull().orEmpty().ifBlank { defaultUserAgent() }

    private fun isChallenge(response: Response): Boolean {
        // Cloudflare's own signature — a 403/503 whose `Server` says cloudflare,
        // or the authoritative `cf-mitigated` header of a managed challenge.
        val code = response.code
        val server = response.header("Server")?.lowercase().orEmpty()
        if (!response.header("cf-mitigated").isNullOrBlank()) return true
        if (code == 403 || code == 503) {
            if (server.contains("cloudflare")) return true
        } else if (code != 429) {
            // A managed challenge USUALLY answers 403/503, but some modes
            // answer 200 carrying the challenge HTML — which the plugin then
            // parses as an empty page (the "gave nothing" empty row on a
            // walled host). Peek those document bodies for the interstitial
            // markers; media stays untouched (its type is never textual) and
            // non-document types are never read.
            if (code !in 200..299) return false
            val type = response.header("Content-Type")?.lowercase().orEmpty()
            if (type.isNotBlank() &&
                !type.contains("html") && !type.contains("text") && !type.contains("javascript")
            ) {
                return false
            }
            val body = peekText(response)
            if (body.isBlank()) return false
            return HARD_MARKERS.any { body.contains(it, ignoreCase = true) }
        }
        // Not a Cloudflare-served 403/503 (or a 429): the site's own refusal is
        // only a wall when the body really is an interstitial. A wall answers
        // with a small HTML/JS page; anything else at this status is the site's
        // own refusal and is handed straight back to the extension.
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

    /** First 64 KiB of the body as text, gunzipped when needed (peek only —
     *  the caller still reads the response normally). */
    private fun peekText(response: Response): String = runCatching {
        val bytes = response.peekBody(64L * 1024).bytes()
        val raw = if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
            java.util.zip.GZIPInputStream(bytes.inputStream().buffered()).readBytes()
        } else bytes
        String(raw, 0, minOf(raw.size, 64 * 1024), Charsets.UTF_8)
    }.getOrNull().orEmpty()

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
