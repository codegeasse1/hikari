package com.lagradost.nicehttp

import okhttp3.Headers
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Minimal reimplementation of the nicehttp library's NiceResponse.
 * Only the surface the compiled CloudStream .cs3 plugins reference is needed.
 */
class NiceResponse(
    val okhttpResponse: okhttp3.Response?,
    val text: String,
    val code: Int,
    val url: String,
    val isSuccessful: Boolean,
    val headers: Headers,
    val cookies: Map<String, String>,
) {
    val document: Document
        get() = Jsoup.parse(text, url)

    companion object {
        fun from(r: okhttp3.Response): NiceResponse {
            val text = r.body?.string().orEmpty()
            val cookies = LinkedHashMap<String, String>()
            for (i in 0 until r.headers.size) {
                if (r.headers.name(i).equals("Set-Cookie", ignoreCase = true)) {
                    val value = r.headers.value(i)
                    val eq = value.indexOf('=')
                    if (eq > 0) {
                        cookies[value.substring(0, eq).trim()] =
                            value.substring(eq + 1).substringBefore(';').trim()
                    }
                }
            }
            return NiceResponse(
                okhttpResponse = r,
                text = text,
                code = r.code,
                url = r.request.url.toString(),
                isSuccessful = r.isSuccessful,
                headers = r.headers,
                cookies = cookies,
            )
        }

        fun failure(code: Int, text: String, url: String): NiceResponse =
            NiceResponse(null, text, code, url, false, Headers.headersOf(), emptyMap())
    }
}
