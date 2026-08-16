package com.lagradost.cloudstream3.network

import okhttp3.Interceptor
import okhttp3.Response

/**
 * CloudStream plugins return this as their video interceptor to defeat Cloudflare
 * challenges. Hikari doesn't bundle a full WebView challenge solver; the interceptor
 * passes requests through so the class contract (and linkage) stays intact.
 */
class CloudflareKiller : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        return chain.proceed(chain.request())
    }
}
