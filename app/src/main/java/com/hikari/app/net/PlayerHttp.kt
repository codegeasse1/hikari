package com.hikari.app.net

import okhttp3.ConnectionPool
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * The one OkHttp client every video byte flows through.
 *
 * The player used to build a throwaway client per [com.hikari.app.player.PlayerActivity]
 * (and [StreamProbe] a separate one), so every playback session started with a
 * cold DNS cache, a cold TLS session and an empty connection pool — the first
 * segment of every stream paid a full handshake before a single byte moved,
 * which is exactly the kind of startup stall that reads as "buffering".
 *
 * One shared, playback-tuned client instead:
 *  - [StreamProbe] derives its own client from this one via newBuilder(),
 *    which shares this connection pool + dispatcher, so the CDN connection the
 *    probe just opened is still warm;
 *  - a bigger pool + per-host request budget lets HLS/DASH pull
 *    audio/video/subtitle segments in parallel;
 *  - a 20 s read timeout bounds how long a single hung CDN socket can stall the
 *    stream before the load-error policy opens a fresh connection.
 */
object PlayerHttp {

    /**
     * Shared with CS3 plugin HTTP so extractor cookies are present when
     * ExoPlayer fetches the final media URL. CloudStream uses the same HTTP
     * session across extraction and playback for precisely this reason.
     */
    val cookieJar: CookieJar = object : CookieJar {
        private val store = ConcurrentHashMap<String, List<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (cookies.isEmpty()) return
            val host = url.topPrivateDomain() ?: url.host
            val existing = store[host].orEmpty().associateBy { it.name }.toMutableMap()
            for (cookie in cookies) {
                existing[cookie.name] = cookie
            }
            store[host] = existing.values.toList()
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val host = url.topPrivateDomain() ?: url.host
            val all = store[host].orEmpty() + store[url.host].orEmpty()
            return all.filter { it.matches(url) }.distinctBy { it.name }
        }
    }

    /**
     * A copy of the playback client without FastreamRecoveryInterceptor.
     * The recovery interceptor uses this for its /dl refresh request so a
     * recovery request can never recursively trigger another recovery.
     */
    val clientWithoutFastreamRecovery: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .dns(DohDns)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
            .dispatcher(
                Dispatcher().apply {
                    maxRequests = 64
                    maxRequestsPerHost = 16
                }
            )
            .build()
    }

    val client: OkHttpClient by lazy {
        clientWithoutFastreamRecovery.newBuilder()
            .addInterceptor(FastreamRecoveryInterceptor())
            .build()
    }
}
