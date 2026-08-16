package com.lagradost.cloudstream3.network

import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.hikari.app.HikariApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * Minimal WebView-based resolver so plugins that need JS to reach their HLS
 * stream (e.g. Anikoto) can run. Loads the page in a headless WebView, captures
 * qualifying network requests (first = "fixed", rest = "extra") and optionally
 * evaluates a JS snippet, returning its string result via scriptCallback.
 */
class WebViewResolver(
    val interceptUrl: Regex,
    val additionalUrls: List<Regex> = emptyList(),
    val userAgent: String? = null,
    val useOkhttp: Boolean = false,
    val script: String? = null,
    val scriptCallback: ((String) -> Unit)? = null,
    val timeout: Long = 20_000L,
    val maxAttempts: Int = 1,
) {

    suspend fun resolveUsingWebView(
        url: String,
        referer: String? = null,
        userAgent: String? = null,
        requestCallBack: (Request) -> Boolean = { true },
    ): Pair<Request?, List<Request>> {
        val fixed = AtomicReference<Request?>()
        val extras = CopyOnWriteArrayList<Request>()
        if (useOkhttp) {
            try {
                val builder = Request.Builder().url(url)
                    .header("User-Agent", userAgent ?: "Mozilla/5.0")
                if (referer != null) builder.header("Referer", referer)
                val resp = okhttp3.OkHttpClient()
                    .newCall(builder.build())
                    .execute()
                val text = resp.body?.string().orEmpty()
                Regex("""https?://[^\s"'<>\\]+?\.m3u8[^\s"'<>\\]*""", RegexOption.IGNORE_CASE)
                    .findAll(text)
                    .forEach { m ->
                        val req = Request.Builder().url(m.value).build()
                        if (fixed.get() == null) fixed.set(req) else extras.add(req)
                    }
            } catch (e: Exception) {
            }
        } else {
            runWebView(url, referer, userAgent, requestCallBack, fixed, extras)
        }
        return fixed.get() to extras.toList()
    }

    private suspend fun runWebView(
        url: String,
        referer: String?,
        ua: String?,
        requestCallBack: (Request) -> Boolean,
        fixed: AtomicReference<Request?>,
        extras: MutableList<Request>,
    ) {
        withContext(Dispatchers.Main) {
            val ctx = HikariApp.instance.applicationContext
            val handler = Handler(Looper.getMainLooper())
            var webView: WebView? = null
            withTimeoutOrNull(timeout) {
                suspendCancellableCoroutine { cont ->
                    handler.post {
                        try {
                            val wv = WebView(ctx)
                            webView = wv
                            wv.settings.javaScriptEnabled = true
                            wv.settings.domStorageEnabled = true
                            wv.settings.userAgentString = ua ?: WebSettings.getDefaultUserAgent(ctx)
                            wv.webViewClient = object : WebViewClient() {
                                override fun shouldInterceptRequest(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                ): WebResourceResponse? {
                                    val u = request?.url?.toString() ?: return null
                                    val ok = Request.Builder().url(u).build()
                                    if (requestCallBack(ok)) {
                                        if (fixed.get() == null) fixed.set(ok) else extras.add(ok)
                                    }
                                    return null
                                }

                                override fun onPageFinished(view: WebView?, pageUrl: String?) {
                                    if (!cont.isActive) return
                                    if (script != null) {
                                        view?.evaluateJavascript(script) { s ->
                                            if (cont.isActive) {
                                                scriptCallback?.invoke(s)
                                                cont.resume(Unit)
                                            }
                                        }
                                    } else {
                                        scriptCallback?.invoke("")
                                        cont.resume(Unit)
                                    }
                                }

                                override fun onReceivedError(
                                    view: WebView?,
                                    errorCode: Int,
                                    description: String?,
                                    failingUrl: String?,
                                ) {
                                    if (cont.isActive) {
                                        scriptCallback?.invoke("")
                                        cont.resume(Unit)
                                    }
                                }
                            }
                            val headers = mapOf("Referer" to (referer ?: url.substringBefore("?")))
                            wv.loadUrl(url, headers)
                        } catch (e: Exception) {
                            if (cont.isActive) cont.resume(Unit)
                        }
                    }
                    cont.invokeOnCancellation {
                        handler.post { webView?.destroy() }
                    }
                }
            }
            handler.post { webView?.destroy() }
        }
    }
}
