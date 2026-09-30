package com.hikari.app.net

import com.lagradost.cloudstream3.utils.getAndUnpack
import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * Repairs Fastream signed HLS URLs at the point they are actually requested.
 *
 * CloudStream's Fastream extractor does not replay an old master URL: it POSTs
 * /dl with the file_code and gets a fresh signed playlist. Hikari can keep a
 * source row alive for longer than that signed URL, so a 403 can otherwise be
 * mistaken for a dead server. This interceptor mirrors that recovery step
 * without changing ordinary requests.
 */
class FastreamRecoveryInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!isFastreamPlaylist(request.url.toString())) {
            return chain.proceed(request)
        }

        val first = chain.proceed(request)
        if (first.code != 403) return first

        val fresh = runCatching { freshPlaylist(request.url.toString()) }.getOrNull()
        if (fresh.isNullOrBlank() || fresh == request.url.toString()) {
            return first
        }

        first.close()

        // Keep the headers Media3/CloudStream already selected for this source,
        // but point the request at the newly signed playlist.
        val retry = request.newBuilder()
            .url(fresh)
            .header("Referer", "https://fastream.to/")
            .build()

        return chain.proceed(retry)
    }

    private fun isFastreamPlaylist(url: String): Boolean {
        val host = url.substringAfter("://", "").substringBefore('/').lowercase()
        return host.endsWith(".fastream.to") &&
            (url.contains(".m3u8", ignoreCase = true) ||
                url.contains(".urlset", ignoreCase = true) ||
                url.contains("/hls", ignoreCase = true))
    }

    /**
     * Fastream's HLS path contains the file code immediately before the
     * rendition suffix, e.g.:
     *   /hls2/.../kO4k6Oh7735c_l,n,.urlset/master.m3u8
     */
    private fun fileCode(url: String): String? {
        Regex(
            """/([A-Za-z0-9]{8,})_[^/]*\.urlset(?:/|$)""",
            RegexOption.IGNORE_CASE,
        ).find(url)?.groupValues?.getOrNull(1)?.let { return it }

        Regex(
            """/([A-Za-z0-9]{8,})_[^/]*(?:/|$)master\.m3u8""",
            RegexOption.IGNORE_CASE,
        ).find(url)?.groupValues?.getOrNull(1)?.let { return it }

        Regex(
            """emb\.html\\?([^=&/]+)=""",
            RegexOption.IGNORE_CASE,
        ).find(url)?.groupValues?.getOrNull(1)?.let { return it }

        return null
    }

    private fun freshPlaylist(url: String): String? {
        val code = fileCode(url) ?: return null

        val body = FormBody.Builder()
            .add("op", "embed")
            .add("file_code", code)
            .add("auto", "1")
            .build()

        val request = Request.Builder()
            .url("https://fastream.to/dl")
            .post(body)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36")
            .header("Referer", "https://fastream.to/")
            .header("Accept", "text/html,application/xhtml+xml")
            .build()

        // Use the same shared client/cookie jar, but bypass this interceptor
        // itself so the refresh request cannot recurse.
        val client = PlayerHttp.clientWithoutFastreamRecovery
        val html = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.string().orEmpty()
        }
        if (html.isBlank()) return null

        val unpacked = runCatching { getAndUnpack(html) }.getOrDefault(html)

        val absolute = Regex(
            """https?://[^"'\\s]+\\.m3u8[^"'\\s]*""",
            RegexOption.IGNORE_CASE,
        ).find(unpacked)?.value?.replace("\\/", "/")
        if (!absolute.isNullOrBlank()) return absolute

        val file = Regex(
            """file\\s*:\\s*["']([^"']+\\.m3u8[^"']*)["']""",
            RegexOption.IGNORE_CASE,
        ).find(unpacked)?.groupValues?.getOrNull(1)?.replace("\\/", "/")

        return when {
            file.isNullOrBlank() -> null
            file.startsWith("http", ignoreCase = true) -> file
            file.startsWith("/") -> "https://fastream.to$file"
            else -> "https://fastream.to/$file"
        }
    }
}
