package com.hikari.app.music.potoken

import android.content.Context
import android.webkit.CookieManager
import com.hikari.app.data.Logs
import com.hikari.app.tv.TvMode
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.services.youtube.InnertubeClientRequestInfo
import org.schabi.newpipe.extractor.services.youtube.PoTokenProvider
import org.schabi.newpipe.extractor.services.youtube.PoTokenResult
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper

/**
 * Gives NewPipeExtractor the PO token it needs so the stream addresses it returns keep working past
 * the first megabyte (see [PoTokenWebView]). Port of NewPipe's PoTokenProviderImpl.
 *
 * Television only: the phone keeps its old, working behaviour (this returns null there).
 * If the device has no usable WebView, or BotGuard cannot be loaded, it returns null and the
 * extractor simply carries on without a token (and a failure is not repeated for [RETRY_AFTER_MS]).
 */
object HikariPoTokenProvider : PoTokenProvider {
    private const val TAG = "HikariPoToken"
    private const val RETRY_AFTER_MS = 120_000L

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

    private object Lock

    private var appContext: Context? = null
    private var visitorData: String? = null
    private var streamingPot: String? = null
    private var generator: PoTokenWebView? = null

    /** Remembers the application context (needed to create the WebView). */
    fun attach(ctx: Context) {
        appContext = ctx.applicationContext
    }

    /** Blocking, background thread only: builds the generator in advance so the first song does not wait for it. */
    fun prewarm() {
        if (!usable()) return
        runCatching { obtain("dQw4w9WgXcQ", forceRecreate = false) }
            .onFailure { noteFailure(it) }
    }

    private fun usable(): Boolean =
        TvMode.isTv && appContext != null && webViewSupported && !webViewBad &&
            System.currentTimeMillis() - failedAtMs > RETRY_AFTER_MS

    private fun noteFailure(t: Throwable) {
        if (t is BadWebViewException) webViewBad = true
        failedAtMs = System.currentTimeMillis()
        Logs.log(TAG, "no PO token: " + (t.message ?: t.javaClass.simpleName))
    }

    override fun getWebClientPoToken(videoId: String): PoTokenResult? {
        if (!usable()) return null
        return try {
            obtain(videoId, forceRecreate = false)
        } catch (t: Throwable) {
            noteFailure(t)
            null
        }
    }

    private fun obtain(videoId: String, forceRecreate: Boolean): PoTokenResult {
        val ctx = appContext ?: throw PoTokenException("no context")
        var recreated = false
        val gen: PoTokenWebView
        val visitor: String
        val streaming: String
        synchronized(Lock) {
            val shouldRecreate = generator == null || forceRecreate || generator!!.isExpired()
            if (shouldRecreate) {
                val info = InnertubeClientRequestInfo.ofWebClient()
                info.clientInfo.clientVersion = YoutubeParsingHelper.getClientVersion()
                visitorData = YoutubeParsingHelper.getVisitorDataFromInnertube(
                    info,
                    NewPipe.getPreferredLocalization(),
                    NewPipe.getPreferredContentCountry(),
                    YoutubeParsingHelper.getYouTubeHeaders(),
                    YoutubeParsingHelper.YOUTUBEI_V1_URL,
                    null,
                    false,
                )
                generator?.close()
                generator = null
                val fresh = PoTokenWebView.create(ctx)
                generator = fresh
                // The streaming poToken has to be generated exactly once, before any other token.
                streamingPot = fresh.generatePoToken(visitorData!!)
                Logs.log(TAG, "BotGuard ready, PO token generator created")
            }
            recreated = shouldRecreate
            gen = generator!!
            visitor = visitorData!!
            streaming = streamingPot!!
        }
        val playerPot = try {
            gen.generatePoToken(videoId)
        } catch (t: Throwable) {
            if (recreated) throw t
            // The WebView may have lost its content (the app was in the background): start over once.
            Logs.log(TAG, "token failed, recreating generator: " + t.message)
            return obtain(videoId, forceRecreate = true)
        }
        return PoTokenResult(visitor, playerPot, streaming)
    }
}
