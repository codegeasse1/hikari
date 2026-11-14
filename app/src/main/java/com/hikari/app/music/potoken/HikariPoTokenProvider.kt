package com.hikari.app.music.potoken

import android.content.Context
import android.webkit.CookieManager
import com.hikari.app.data.Logs
import com.hikari.app.net.Http
import com.hikari.app.tv.TvMode
import org.json.JSONObject
import org.schabi.newpipe.extractor.services.youtube.PoTokenProvider
import org.schabi.newpipe.extractor.services.youtube.PoTokenResult
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * The one place that owns the BotGuard WebView ([PoTokenWebView]) and the PO token made with it.
 *
 * YouTube only serves the first ~1 MB of a song (about 1:04) to a player that sends no PO token
 * (`pot=` in the stream address) and, lately, refuses a request with no integrity proof at all
 * ("UNPLAYABLE - This content isn't available, try again later"). Two users of this class:
 *
 *  1. Hikari's own quick YouTube requests ([MusicApi]) take [streamingPot]: a visitor id plus the
 *     token bound to it. They send that visitor id in the request and add `&pot=` to the address.
 *     This does NOT need NewPipe at all (NewPipe's extractor cannot run on some televisions).
 *  2. NewPipeExtractor, through [getWebClientPoToken] (same as NewPipe's own app does).
 *
 * Television only: on a phone everything returns null and the phone behaves exactly as before.
 * If the device has no usable WebView or BotGuard cannot be loaded, null is returned (and the failure
 * is not repeated for [RETRY_AFTER_MS]), so lookups simply carry on without a token.
 */
object HikariPoTokenProvider : PoTokenProvider {
    private const val TAG = "HikariPoToken"
    private const val RETRY_AFTER_MS = 90_000L

    /** A caller waits this long for another thread that is building the generator, then goes on without a token. */
    private const val WAIT_FOR_BUILD_MS = 25_000L

    /** A visitor id and the streaming PO token that belongs to it. */
    class StreamingPot(val visitor: String, val pot: String)

    private val webViewSupported by lazy {
        try {
            CookieManager.getInstance()
            true
        } catch (t: Throwable) {
            false
        }
    }

    @Volatile
    private var webViewBad = false

    @Volatile
    private var failedAtMs = 0L

    private val lock = ReentrantLock()
    private var appContext: Context? = null
    private var visitorData: String? = null
    private var pot: String? = null
    private var generator: PoTokenWebView? = null

    /** Remembers the application context (needed to create the WebView). */
    fun attach(ctx: Context) {
        appContext = ctx.applicationContext
    }

    private fun usable(): Boolean =
        TvMode.isTv && appContext != null && webViewSupported && !webViewBad &&
            System.currentTimeMillis() - failedAtMs > RETRY_AFTER_MS

    private fun noteFailure(t: Throwable) {
        if (t is BadWebViewException) webViewBad = true
        failedAtMs = System.currentTimeMillis()
        Logs.log(TAG, "no PO token: " + (t.message ?: t.javaClass.simpleName))
    }

    /** Blocking, background thread only: builds the generator in advance so the first song does not wait for it. */
    fun prewarm() {
        streamingPot()
    }

    /**
     * Blocking, background thread only. The visitor id and the streaming PO token for it, or null when
     * there is none (not a television, no WebView, BotGuard failed, or another thread is still busy).
     * Every request that uses the token must send THIS visitor id.
     */
    fun streamingPot(): StreamingPot? {
        if (!usable()) return null
        return try {
            val s = ensure(forceRecreate = false) ?: return null
            StreamingPot(s.visitor, s.pot)
        } catch (t: Throwable) {
            noteFailure(t)
            null
        }
    }

    /** Throws the current visitor id and token away; the next [streamingPot] starts from a fresh visitor. */
    fun rotate() {
        // Busy = another thread is already building a brand-new pair, which is what rotating is for.
        if (!lock.tryLock(5, TimeUnit.SECONDS)) return
        try {
            generator?.close()
            generator = null
            visitorData = null
            pot = null
        } finally {
            lock.unlock()
        }
        Logs.log(TAG, "visitor id and PO token dropped, a new pair is made on the next song")
    }

    private class State(val generator: PoTokenWebView, val visitor: String, val pot: String)

    private fun ensure(forceRecreate: Boolean): State? {
        val ctx = appContext ?: throw PoTokenException("no context")
        if (!lock.tryLock(WAIT_FOR_BUILD_MS, TimeUnit.MILLISECONDS)) {
            Logs.log(TAG, "PO token generator is still being built, going on without a token")
            return null
        }
        try {
            val g = generator
            if (g == null || forceRecreate || g.isExpired() || visitorData == null || pot == null) {
                val visitor = fetchVisitorData() ?: throw PoTokenException("no visitor id")
                generator?.close()
                generator = null
                val fresh = PoTokenWebView.create(ctx)
                generator = fresh
                // The streaming poToken has to be generated exactly once, before any other token.
                pot = fresh.generatePoToken(visitor)
                visitorData = visitor
                Logs.log(TAG, "BotGuard ready: PO token made for a new visitor id")
            }
            return State(generator!!, visitorData!!, pot!!)
        } finally {
            lock.unlock()
        }
    }

    /** A brand-new anonymous visitor id from YouTube (no NewPipe code involved). */
    private fun fetchVisitorData(): String? = try {
        val body = JSONObject().put(
            "context",
            JSONObject().put(
                "client",
                JSONObject()
                    .put("clientName", "WEB")
                    .put("clientVersion", "2.20250925.01.00")
                    .put("hl", "en")
                    .put("gl", "US"),
            ),
        ).toString()
        Http.request(
            "POST",
            "https://www.youtube.com/youtubei/v1/visitor_id?prettyPrint=false",
            body,
            mapOf(
                "Origin" to "https://www.youtube.com",
                "X-YouTube-Client-Name" to "1",
                "X-YouTube-Client-Version" to "2.20250925.01.00",
            ),
        ).use { reply ->
            if (!reply.isSuccessful) {
                Logs.log(TAG, "visitor id request: HTTP " + reply.code)
                null
            } else {
                JSONObject(reply.body?.string().orEmpty())
                    .optJSONObject("responseContext")?.optString("visitorData")?.takeIf { it.isNotBlank() }
            }
        }
    } catch (e: Exception) {
        Logs.log(TAG, "visitor id request failed: " + e.message)
        null
    }

    // ------------------------------------------------------------------ NewPipeExtractor

    override fun getWebClientPoToken(videoId: String): PoTokenResult? {
        if (!usable()) return null
        return try {
            var s = ensure(forceRecreate = false) ?: return null
            val playerPot = try {
                s.generator.generatePoToken(videoId)
            } catch (t: Throwable) {
                // The WebView may have lost its content (app in the background): start over once.
                Logs.log(TAG, "token failed, recreating generator: " + t.message)
                s = ensure(forceRecreate = true) ?: return null
                s.generator.generatePoToken(videoId)
            }
            PoTokenResult(s.visitor, playerPot, s.pot)
        } catch (t: Throwable) {
            noteFailure(t)
            null
        }
    }
}
