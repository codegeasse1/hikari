package com.hikari.app.nuvio

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Runs NuvioMobile-style JS providers inside hidden WebViews. Each provider is a
 * plain CommonJS module exporting `getStreams(tmdbId, mediaType, season, episode)`
 * (plus an optional `onSettings()`), following the official NuvioMobile
 * plugin-runtime conventions. The WebView only ever executes the provider code —
 * every network request goes through the synchronous NuvioBridge.fetch bridge
 * (OkHttp), so the WebView needs no real network access of its own.
 *
 * A small pool of WebViews (bounded) lets several providers resolve sources in
 * parallel; each pooled WebView is initialized once with the shared runtime
 * (runtime.html loads cheerio + crypto-js + harness.js as plain scripts — see
 * app/src/main/assets/nuvio/runtime.html).
 */
object NuvioRuntime {

    private const val MAX_WEBVIEWS = 3
    private const val FETCH_TIMEOUT_MS = 60_000L
    private const val CALL_TIMEOUT_MS = 90_000L

    // NuvioMobile's default UA when a provider sets none of its own
    // (FetchBridge.kt sends the provider's headers as-is; the provider side
    // falls back to this exact desktop string). Hikari used its own full
    // Chrome desktop UA here, which a few WAFs fingerprint and block — match
    // nuvio so providers behave identically.
    private const val NUVIO_DEFAULT_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"

    private class PooledWebView(val wv: WebView) {
        val ready = CompletableDeferred<Unit>()
        var inUse = false
    }

    private val lock = Any()
    private val allViews = mutableListOf<PooledWebView>()
    private val freeChannel = Channel<PooledWebView>(Channel.UNLIMITED)
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val fetchExecutor: ExecutorService = Executors.newFixedThreadPool(4)

    private val client by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            // Plain OkHttp, mirroring NuvioMobile's own httpRequestRaw: no
            // Cloudflare interception, no cookie jar, no UA rewriting, no
            // WebViews. nuvio's native fetch is deliberately bare — if a
            // provider's site serves a CF challenge nuvio does NOT try to
            // solve it (those addons simply return nothing), and neither do we.
            // This also means no cf_clearance dance and no WebViewActivity
            // popping over the player during nuvio searches.
            .build()
    }

    // ---- Settings persistence (per provider, filesDir/nuvio/settings/<id>.json) ----

    fun settingsFile(providerId: String): File {
        val safe = providerId.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        return File(com.hikari.app.HikariApp.instance.filesDir, "nuvio/settings/$safe.json")
    }

    fun saveSettings(providerId: String, json: String) {
        runCatching {
            val f = settingsFile(providerId)
            f.parentFile?.mkdirs()
            f.writeText(json.ifBlank { "{}" })
        }
    }

    fun loadSettings(providerId: String): String =
        runCatching { settingsFile(providerId).takeIf { it.exists() }?.readText() }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: "{}"

    // ---- WebView pool ----

    @SuppressLint("SetJavaScriptEnabled")
    private fun createAndInit(context: Context): PooledWebView {
        val wv = WebView(context.applicationContext)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.allowFileAccess = true
        wv.settings.blockNetworkLoads = true
        wv.settings.cacheMode = WebSettings.LOAD_NO_CACHE
        wv.addJavascriptInterface(NuvioBridge(), "NuvioBridge")
        val p = PooledWebView(wv)
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (p.ready.isCompleted) return
                if (url.startsWith("file://")) {
                    // runtime.html boots cheerio + crypto-js + harness as plain
                    // scripts (no evaluateJavascript size ceiling), then sets
                    // window.__nuvioReady. Confirm it actually ran.
                    wv.evaluateJavascript(
                        "window.__nuvioReady === true && typeof window.__nuvioRequire === 'function'"
                    ) { r ->
                        if (r?.contains("true") == true) p.ready.complete(Unit)
                        else p.ready.completeExceptionally(Exception("nuvio runtime failed to boot"))
                    }
                } else {
                    p.ready.completeExceptionally(Exception("nuvio runtime page failed to load: $url"))
                }
            }
        }
        wv.loadUrl("file:///android_asset/nuvio/runtime.html")
        return p
    }

    private fun quote(s: String): String = JSONObject.quote(s)

    /** Grabs a free pooled WebView, creating one up to the pool cap. Suspends
     *  until one is available; the WebView is guaranteed ready on return. */
    private suspend fun acquire(context: Context): PooledWebView? {
        freeChannel.tryReceive().getOrNull()?.let { p -> waitReady(p); return p }
        var created: PooledWebView? = null
        synchronized(lock) {
            if (allViews.size < MAX_WEBVIEWS) {
                val p = createAndInit(context)
                allViews.add(p)
                created = p
            }
        }
        if (created != null) {
            waitReady(created!!)
            return created
        }
        // Pool full — block until an in-flight call returns its WebView.
        val p = freeChannel.receive() ?: return null
        waitReady(p)
        return p
    }

    private suspend fun waitReady(p: PooledWebView) {
        if (p.ready.isCompleted) return
        withTimeoutOrNull(15_000) { p.ready.await() }
    }

    private fun release(p: PooledWebView) {
        p.inUse = false
        freeChannel.trySend(p)
    }

    // ---- Public API ----

    /** Runs provider.getStreams(...) and returns the raw JSON payload string
     *  (`{"ok":true,"data":[...]}` or `{"ok":false,"error":"..."}`). */
    suspend fun getStreams(
        context: Context,
        source: String,
        providerId: String,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?,
    ): String = callWebView(context, source, providerId) { wv, cid ->
        val settings = loadSettings(providerId)
        val s = if (season == null) "null" else season.toString()
        val e = if (episode == null) "null" else episode.toString()
        "window.__nuvioSetSettings($settings);" +
            "window.__nuvioRunProvider(${quote(source)}, ${quote(providerId)}, ${quote(cid)}, " +
            "${quote(tmdbId)}, ${quote(mediaType)}, $s, $e);"
    }

    /** Runs provider.onSettings() and returns the layout JSON payload. */
    suspend fun getSettingsLayout(
        context: Context,
        source: String,
        providerId: String,
    ): String = callWebView(context, source, providerId) { wv, cid ->
        val settings = loadSettings(providerId)
        "window.__nuvioSetSettings($settings);" +
            "window.__nuvioRunSettings(${quote(source)}, ${quote(providerId)}, ${quote(cid)});"
    }

    /** True when the JS module loads and exports a usable getStreams function. */
    suspend fun validate(context: Context, source: String): String =
        withContext(Dispatchers.Main) {
            val p = acquire(context) ?: return@withContext "ERR: all nuvio workers busy"
            try {
                var result = ""
                val js = "(function(){ try { var m = window.__nuvioLoadProvider(${quote(source)}, 'validate');" +
                    " if (m && typeof m.getStreams === 'function') return 'OK'; return 'NO';" +
                    " } catch(e) { return 'ERR:' + String(e && e.message || e); } })();"
                p.wv.evaluateJavascript(js) { r ->
                    // evaluateJavascript returns the JS string JSON-quoted, so a
                    // verdict of "OK" arrives as "\"OK\"" — strip the quotes or
                    // installScraper's startsWith("OK") check would reject every
                    // valid provider.
                    result = r?.trim()?.removeSurrounding("\"") ?: ""
                }
                // evaluateJavascript is async — poll for the callback result.
                for (i in 0 until 100) {
                    if (result.isNotBlank()) break
                    kotlinx.coroutines.delay(100)
                }
                return@withContext result.ifBlank { "ERR: validation timed out" }
            } finally {
                release(p)
            }
        }

    private suspend fun callWebView(
        context: Context,
        source: String,
        providerId: String,
        buildJs: (WebView, String) -> String,
    ): String = withContext(Dispatchers.Main) {
        val p = acquire(context) ?: return@withContext "{\"ok\":false,\"error\":\"all nuvio workers busy\"}"
        val cid = java.util.UUID.randomUUID().toString()
        val deferred = CompletableDeferred<String>()
        inFlight[cid] = deferred
        try {
            val js = buildJs(p.wv, cid)
            p.wv.evaluateJavascript(js, null)
            val payload = withTimeoutOrNull(CALL_TIMEOUT_MS) { deferred.await() }
            if (payload == null) {
                // Hung JS — drop this WebView and rebuild on demand.
                recycle(p)
                "{\"ok\":false,\"error\":\"provider timed out after ${CALL_TIMEOUT_MS / 1000}s\"}"
            } else {
                payload
            }
        } finally {
            inFlight.remove(cid)
            if (!p.wv.isAttachedToWindow && p.ready.isCompleted) {
                // no-op guard; keep the view in the pool regardless
            }
            release(p)
        }
    }

    /** Rebuilds a wedged WebView from scratch (drops it from the pool). */
    private fun recycle(p: PooledWebView) {
        synchronized(lock) {
            allViews.remove(p)
        }
        runCatching { p.wv.destroy() }
    }

    fun onDone(cid: String, payload: String) {
        inFlight.remove(cid)?.complete(payload)
    }

    /** Synchronous fetch bridge invoked from the WebView's JavaBridge thread.
     *  Returns a JSON string the harness parses into a fetch-like response. */
    fun bridgeFetch(
        url: String,
        method: String,
        headersJson: String,
        body: String,
        followRedirects: Boolean,
    ): String {
        val started = System.currentTimeMillis()
        val task = fetchExecutor.submit<JSONObject> {
            try {
                val builder = Request.Builder()
                    .url(url)
                    .header("User-Agent", NUVIO_DEFAULT_UA)
                val h = runCatching { JSONObject(headersJson) }.getOrNull()
                if (h != null) {
                    h.keys().forEach { k ->
                        // Strip the provider's explicit Accept-Encoding (nuvio
                        // does the same: FetchBridge's withoutAcceptEncoding()).
                        // OkHttp only transparently decompresses gzip/br when
                        // the REQUEST doesn't carry its own Accept-Encoding —
                        // passing "gzip, deflate, br" through made Hikari hand
                        // the JS raw compressed bytes decoded as UTF-8, i.e.
                        // garbage, so every provider that sets it (vidlink,
                        // dvdplay, vidnest, vidrock, vixsrc, mallumv, castle,
                        // xprime, ...) came back "no sources" here but fine in
                        // nuvio. "identity" would be harmless, but drop it too
                        // for exact parity.
                        if (k.equals("Accept-Encoding", ignoreCase = true)) return@forEach
                        runCatching { builder.header(k, h.getString(k)) }
                    }
                }
                val m = method.uppercase()
                if (body.isNotEmpty() && (m == "POST" || m == "PUT" || m == "PATCH")) {
                    val type = if (h != null && h.has("Content-Type")) h.getString("Content-Type")
                    else "application/x-www-form-urlencoded; charset=utf-8"
                    builder.method(m, okhttp3.RequestBody.create(type.toMediaTypeOrNull(), body))
                } else {
                    builder.method(if (m == "HEAD") "HEAD" else "GET", null)
                }
                val resp = client.newCall(builder.build()).execute()
                resp.use { r ->
                    val bytes = r.body?.bytes() ?: ByteArray(0)
                    val out = JSONObject()
                    out.put("ok", r.isSuccessful)
                    out.put("status", r.code)
                    out.put("statusText", r.message)
                    out.put("url", r.request.url.toString())
                    // Lowercase header names, exactly like nuvio's response
                    // headers map (provider JS does headers['content-type'],
                    // headers.get('location'), ...).
                    val hdrs = JSONObject()
                    runCatching {
                        r.headers.forEach { (k, v) -> if (!hdrs.has(k.lowercase())) hdrs.put(k.lowercase(), v) }
                    }
                    out.put("headers", hdrs)
                    // Honor the response charset (nuvio: contentType().charset()
                    // ?: UTF-8). Hikari decoded every body as UTF-8, which
                    // mojibake'd latin-1/iso-8859-1 pages into garbage and made
                    // providers miss their JSON/HTML markers.
                    val charset = runCatching {
                        val ct = r.headers["Content-Type"] ?: ""
                        val enc = ct.substringAfter("charset=", "").trim().trim('"')
                        if (enc.isEmpty()) Charsets.UTF_8 else Charset.forName(enc)
                    }.getOrNull() ?: Charsets.UTF_8
                    out.put("body", String(bytes, charset))
                    out.put("bodyBase64", Base64.encodeToString(bytes, Base64.NO_WRAP))
                    out.put("ms", System.currentTimeMillis() - started)
                    return@submit out
                }
            } catch (e: Throwable) {
                val out = JSONObject()
                out.put("ok", false)
                out.put("status", 0)
                out.put("statusText", e.message ?: "network error")
                out.put("url", url)
                out.put("headers", JSONObject())
                out.put("body", "")
                out.put("bodyBase64", "")
                out.put("error", e.message ?: "network error")
                return@submit out
            }
        }
        return try {
            task.get(FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS).toString()
        } catch (e: Exception) {
            "{\"ok\":false,\"status\":0,\"statusText\":\"fetch timed out\",\"url\":${quote(url)}," +
                "\"headers\":{},\"body\":\"\",\"bodyBase64\":\"\"}"
        }
    }

    private class NuvioBridge {
        @JavascriptInterface
        fun fetch(url: String, method: String, headersJson: String, body: String, followRedirects: Boolean): String =
            NuvioRuntime.bridgeFetch(url, method, headersJson, body, followRedirects)

        @JavascriptInterface
        fun onGetStreamsDone(cid: String, payload: String) {
            NuvioRuntime.onDone(cid, payload)
        }

        @JavascriptInterface
        fun onSettingsDone(cid: String, payload: String) {
            NuvioRuntime.onDone(cid, payload)
        }

        @JavascriptInterface
        fun log(msg: String) {
            android.util.Log.d("Nuvio", msg)
        }
    }
}
