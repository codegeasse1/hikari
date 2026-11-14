package com.hikari.app.music.potoken

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import com.hikari.app.data.Logs
import com.hikari.app.net.DohDns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread

/**
 * Runs YouTube's BotGuard inside a hidden WebView and mints "PO tokens" with it.
 *
 * WHY THIS EXISTS: YouTube now only serves the first ~1 MB of an audio stream (about 1:04 of a
 * 128 kbit/s song) to a player that does not send a valid PO token (`pot=` in the stream address).
 * Every later request is answered with HTTP 403, so the music stopped at 1:04 and stayed on
 * "Loading". NewPipe solves this with exactly this WebView + BotGuard trick; this is a port of
 * org.schabi.newpipe.util.potoken.PoTokenWebView without RxJava.
 *
 * All the blocking calls here must be made from a background thread (never the main thread).
 */
internal class PoTokenWebView private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val webView = WebView(appContext)
    private val initDone = CompletableFuture<PoTokenWebView>()
    private val pending = ArrayList<Pair<String, CompletableFuture<String>>>()

    @Volatile
    private var expiresAtMs = 0L

    @Volatile
    private var closed = false

    private val network = OkHttpClient.Builder()
        .followRedirects(true)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .dns(DohDns)
        .build()

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure() {
        val s = webView.settings
        s.javaScriptEnabled = true
        if (Build.VERSION.SDK_INT >= 26) runCatching { s.safeBrowsingEnabled = false }
        s.userAgentString = USER_AGENT
        s.blockNetworkLoads = true // the WebView itself needs no internet
        webView.addJavascriptInterface(this, JS_INTERFACE)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                if (m.message().contains("Uncaught")) {
                    // Everything that can fail is guarded by try/catch, so an uncaught error means the
                    // WebView is too old for the script.
                    val text = "\"${m.message()}\", source: ${m.sourceId()} (${m.lineNumber()})"
                    Logs.log(TAG, "this WebView is broken: $text")
                    val e = BadWebViewException(text)
                    failInit(e)
                    popAll().forEach { (_, f) -> f.completeExceptionally(e) }
                }
                return super.onConsoleMessage(m)
            }
        }
    }

    // ------------------------------------------------------------------ initialization

    /** Main thread. Loads the page and starts the BotGuard download. */
    private fun begin() {
        thread(start = true, isDaemon = true, name = "pot-html") {
            try {
                val html = appContext.assets.open("po_token.html").bufferedReader().use { it.readText() }
                runOnMain {
                    webView.loadDataWithBaseURL(
                        "https://www.youtube.com",
                        // calls downloadAndRunBotguard() when the page has finished loading
                        html.replaceFirst("</script>", "\n$JS_INTERFACE.downloadAndRunBotguard()</script>"),
                        "text/html",
                        "utf-8",
                        null,
                    )
                }
            } catch (t: Throwable) {
                failInit(t)
            }
        }
    }

    @JavascriptInterface
    fun downloadAndRunBotguard() {
        botguardRequest("https://www.youtube.com/api/jnn/v1/Create", "[ \"$REQUEST_KEY\" ]") { body ->
            val parsed = parseChallengeData(body)
            runOnMain {
                webView.evaluateJavascript(
                    """try {
                        data = $parsed
                        runBotGuard(data).then(function (result) {
                            this.webPoSignalOutput = result.webPoSignalOutput
                            $JS_INTERFACE.onRunBotguardResult(result.botguardResponse)
                        }, function (error) {
                            $JS_INTERFACE.onJsInitializationError(error + "\n" + error.stack)
                        })
                    } catch (error) {
                        $JS_INTERFACE.onJsInitializationError(error + "\n" + error.stack)
                    }""",
                    null,
                )
            }
        }
    }

    @JavascriptInterface
    fun onJsInitializationError(error: String) {
        Logs.log(TAG, "BotGuard init error: " + error.take(300))
        failInit(buildExceptionForJsError(error))
    }

    @JavascriptInterface
    fun onRunBotguardResult(botguardResponse: String) {
        botguardRequest(
            "https://www.youtube.com/api/jnn/v1/GenerateIT",
            "[ \"$REQUEST_KEY\", \"$botguardResponse\" ]",
        ) { body ->
            val (integrityToken, expirationSeconds) = parseIntegrityTokenData(body)
            // 10 minutes of margin
            expiresAtMs = System.currentTimeMillis() + (expirationSeconds - 600L) * 1000L
            runOnMain {
                webView.evaluateJavascript("this.integrityToken = $integrityToken") {
                    initDone.complete(this)
                }
            }
        }
    }

    // ------------------------------------------------------------------ minting tokens

    /** Blocking. Mints the poToken for [identifier] (a video id or the visitor data). */
    fun generatePoToken(identifier: String): String {
        val result = CompletableFuture<String>()
        synchronized(pending) { pending.add(identifier to result) }
        val u8Identifier = stringToU8(identifier)
        val quoted = JSONObject.quote(identifier)
        runOnMain {
            webView.evaluateJavascript(
                """try {
                    identifier = $quoted
                    u8Identifier = $u8Identifier
                    poTokenU8 = obtainPoToken(webPoSignalOutput, integrityToken, u8Identifier)
                    poTokenU8String = ""
                    for (i = 0; i < poTokenU8.length; i++) {
                        if (i != 0) poTokenU8String += ","
                        poTokenU8String += poTokenU8[i]
                    }
                    $JS_INTERFACE.onObtainPoTokenResult(identifier, poTokenU8String)
                } catch (error) {
                    $JS_INTERFACE.onObtainPoTokenError(identifier, error + "\n" + error.stack)
                }""",
                null,
            )
        }
        return await(result, TOKEN_TIMEOUT_S) {
            synchronized(pending) { pending.removeAll { it.second === result } }
        }
    }

    @JavascriptInterface
    fun onObtainPoTokenError(identifier: String, error: String) {
        Logs.log(TAG, "obtainPoToken error: " + error.take(300))
        pop(identifier)?.completeExceptionally(buildExceptionForJsError(error))
    }

    @JavascriptInterface
    fun onObtainPoTokenResult(identifier: String, poTokenU8: String) {
        val token = try {
            u8ToBase64(poTokenU8)
        } catch (t: Throwable) {
            pop(identifier)?.completeExceptionally(t)
            return
        }
        pop(identifier)?.complete(token)
    }

    /** True once the integrity token is (almost) out of date: every token made after that is invalid. */
    fun isExpired(): Boolean = System.currentTimeMillis() >= expiresAtMs

    // ------------------------------------------------------------------ plumbing

    private fun pop(identifier: String): CompletableFuture<String>? = synchronized(pending) {
        val i = pending.indexOfFirst { it.first == identifier }
        if (i >= 0) pending.removeAt(i).second else null
    }

    private fun popAll(): List<Pair<String, CompletableFuture<String>>> = synchronized(pending) {
        val all = pending.toList()
        pending.clear()
        all
    }

    private fun runOnMain(block: () -> Unit) {
        if (!main.post { runCatching(block) }) throw PoTokenException("Could not run on main thread")
    }

    /** POSTs to BotGuard's service on a background thread and hands the 200 answer to [handle]. */
    private fun botguardRequest(url: String, data: String, handle: (String) -> Unit) {
        thread(start = true, isDaemon = true, name = "pot-net") {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json+protobuf")
                    .header("x-goog-api-key", GOOGLE_API_KEY)
                    .header("x-user-agent", "grpc-web-javascript/0.1")
                    .post(data.toByteArray().toRequestBody(null))
                    .build()
                network.newCall(req).execute().use { resp ->
                    if (resp.code != 200) throw PoTokenException("Invalid response code: " + resp.code)
                    handle(resp.body?.string().orEmpty())
                }
            } catch (t: Throwable) {
                failInit(t)
            }
        }
    }

    private fun failInit(error: Throwable) {
        initDone.completeExceptionally(error)
        popAll().forEach { (_, f) -> f.completeExceptionally(error) }
        close()
    }

    /** Releases the WebView. Safe to call from any thread and more than once. */
    fun close() {
        if (closed) return
        closed = true
        main.post {
            runCatching {
                webView.clearHistory()
                webView.loadUrl("about:blank")
                webView.onPause()
                webView.removeAllViews()
                webView.destroy()
            }
        }
    }

    companion object {
        private const val TAG = "HikariPoToken"

        // Public API key BotGuard's own page uses.
        private const val GOOGLE_API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw" // NOSONAR
        private const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.3"
        private const val JS_INTERFACE = "PoTokenWebView"

        /** Loading BotGuard on a slow television can take a while. */
        private const val INIT_TIMEOUT_S = 40L
        private const val TOKEN_TIMEOUT_S = 20L

        /** Blocking: builds the WebView, loads BotGuard and returns once it can mint tokens. */
        fun create(context: Context): PoTokenWebView {
            check(Looper.myLooper() != Looper.getMainLooper()) { "PoTokenWebView.create must not run on the main thread" }
            val created = CompletableFuture<PoTokenWebView>()
            val posted = Handler(Looper.getMainLooper()).post {
                try {
                    val w = PoTokenWebView(context)
                    w.configure()
                    created.complete(w)
                    w.begin()
                } catch (t: Throwable) {
                    created.completeExceptionally(t)
                }
            }
            if (!posted) throw PoTokenException("Could not run on main thread")
            val w = await(created, 10L) {}
            return await(w.initDone, INIT_TIMEOUT_S) { w.close() }
        }

        private fun <T> await(f: CompletableFuture<T>, seconds: Long, onTimeout: () -> Unit): T =
            try {
                f.get(seconds, TimeUnit.SECONDS)
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            } catch (e: TimeoutException) {
                onTimeout()
                throw PoTokenException("timed out after " + seconds + "s")
            }
    }
}
