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
 *  - [StreamProbe] derives its own client from this one via `newBuilder()`,
 *    which shares this connection pool + dispatcher, so the CDN connection the
 *    probe just opened (while it classified the stream) is still warm and is
 *    REUSED by media3's OkHttpDataSource for the first media request — no
 *    second handshake, no repeat DNS;
 *  - a bigger pool + per-host request budget lets HLS/DASH pull
 *    audio/video/subtitle segments (and seek prefetch) in parallel instead of
 *    the dispatcher queueing them behind each other;
 *  - a 20 s read timeout (down from 30 s) bounds how long a single hung CDN
 *    socket can stall the stream before the load-error policy opens a fresh
 *    connection and resumes at the current position via a Range request.
 *
 * The timeouts here are the *playback* timeouts. The probe deliberately
 * overrides them with much shorter ones so a dead wrapper page can never hold
 * up a source search.
 */
object PlayerHttp {

    /**
     * Shared cookie jar for CS3 extraction and playback. CloudStream's extractor may
     * establish a session cookie before returning a signed media URL (Fastream is a
     * common example). Keeping the jar shared is safe; the player itself otherwise
     * remains the stable 0.10.66 networking path.
     */
    val cookieJar: CookieJar = object : CookieJar {
        private val store = ConcurrentHashMap<String, List<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (cookies.isEmpty()) return
            val host = url.topPrivateDomain() ?: url.host
            val existing = store[host].orEmpty().associateBy { it.name }.toMutableMap()
            for (cookie in cookies) existing[cookie.name] = cookie
            store[host] = existing.values.toList()
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val host = url.topPrivateDomain() ?: url.host
            val all = store[host].orEmpty() + store[url.host].orEmpty()
            return all.filter { it.matches(url) }.distinctBy { it.name }
        }
    }

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(cookieJar)
            // Playback must use the same Cloudflare/session-aware path as
            // extraction. Some hosts return a playable URL only after the
            // extractor has established clearance cookies; a separate plain
            // OkHttp client made ExoPlayer lose that session and produced a
            // black/no-video player even though the source was resolved.
            .addInterceptor { chain -> CloudflareVerifier.intercept(chain) }
            .dns(DohDns)
            // Give up on a dead host quickly: failover to the next server must
            // not be gated on a long TCP timeout.
            .connectTimeout(15, TimeUnit.SECONDS)
            // Per-socket-read cap. A CDN that stops delivering bytes is
            // retried on a fresh connection (resuming via a Range request)
            // rather than left "buffering" indefinitely.
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            // Many aggregator links bounce https → http (HubCloud et al.).
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
}
