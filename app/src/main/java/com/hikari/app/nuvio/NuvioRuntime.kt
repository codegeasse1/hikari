package com.hikari.app.nuvio

import android.util.Base64
import android.content.Context
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.function
import com.hikari.app.net.DohDns
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Runs NuvioMobile-style JS providers inside a fresh embedded QuickJS engine
 * per call — exactly how the real NuvioMobile app runs plugins (com.nuvio.app
 * PluginRuntime: one `QuickJs.create` per provider call, plain HTTP through a
 * synchronous fetch bridge, no WebView, no Cloudflare verification).
 *
 * Each provider is a plain CommonJS module exporting
 * `getStreams(tmdbId, mediaType, season, episode)` (plus an optional
 * `onSettings()`), following the official NuvioMobile plugin conventions. The
 * engine only ever executes the provider code — every network request goes
 * through the synchronous `__hikariFetch` bridge (OkHttp), so the JS runtime
 * needs no WebView and no network capability of its own. The shared runtime
 * (assets/nuvio/boot.js polyfills + assets/nuvio/cheerio.js + assets/nuvio/
 * harness.js) is evaluated into the engine before each provider runs, so no
 * state ever leaks between providers and a hung/crashing provider can only
 * kill its own fresh engine.
 *
 * Compared with the previous WebView pool this removes:
 *   - the WebView pool (PooledWebView/Waiter/acquire/release/recycle) — the
 *     whole engine is torn down per call, so no shared state can wedge,
 *   - CloudflareVerifier interception — nuvio itself does no CF solving, it
 *     just hands the provider whatever HTTP returns, so neither do we,
 *   - the main-thread hop — everything runs on Dispatchers.Default.
 */
object NuvioRuntime {

    // Cap on concurrently-running engines. Each engine is a native QuickJS VM
    // plus its own JS context, so the count is bounded and the extra providers
    // queue on the semaphore instead of spawning 20+ VMs at once.
    //
    // nuvio's own limit is 10 (PluginRuntime.MAX_CONCURRENT_PLUGINS), and it
    // used to be 6 here — which, with a 20+ provider install, meant the tail of
    // the queue never got an engine before the search budget ran out, so only
    // the first few providers' servers ever reached the player (the reported
    // "in nuvio all the plugins show servers, in hikari only 2-3").
    //
    // 12 rather than nuvio's 10, because a typical curated install is a dozen
    // engines (the sources sheet reports it as "Nuvio 12"): with 10 slots the
    // last two QUEUE for a slot instead of running, and on a phone that is
    // already running the 400-repo cross-extension sweep their whole budget can
    // be spent waiting — nuvio, which runs nothing but the engines, never has
    // this problem. Two extra native VMs is a rounding error next to the
    // engines that then answer in the same window.
    private const val MAX_CONCURRENT = 12
    private const val FETCH_TIMEOUT_MS = 30_000L
    // CALL_TIMEOUT_MS bounds a provider's whole JS execution. It is nuvio's own
    // per-plugin ceiling (PluginRuntime.PLUGIN_TIMEOUT_MS = 60s): a provider
    // that needs its cold boot plus a slow site fetch plus extraction is
    // normal, and 45s cut off providers that nuvio happily finishes. The same
    // value is set as QuickJS's evaluationTimeoutMillis, so even a provider
    // stuck in busy JS (infinite loop) is cut off natively instead of hanging
    // the engine forever.
    private const val CALL_TIMEOUT_MS = 60_000L
    private const val VALIDATE_TIMEOUT_MS = 20_000L

    // Hikari's full desktop Chrome UA as the default for nuvio bridge fetches.
    // Providers that set their own UA header still override this.
    private const val NUVIO_DEFAULT_UA = com.hikari.app.net.Http.UA

    /** Bounds how many providers run their JS engines at once (see above). */
    private val concurrency = Semaphore(MAX_CONCURRENT)

    /** Diagnostic ring buffer of every bridgeFetch outcome (host, status,
     *  size, latency). Shown on the Detail screen when no sources are found so
     *  a failing provider reports exactly what HTTP really returned — a 403
     *  Cloudflare challenge (site blocked the device IP), a network error, or
     *  just slow. Cleared at the start of each sources search. */
    private val fetchLogEntries = ConcurrentLinkedDeque<String>()

    /** When each provider's JS actually started (right after it acquired an
     *  engine slot) — lets the sources sheet distinguish "cut off while still
     *  queued" from "cut off mid-run". */
    private val providerRunStart = ConcurrentHashMap<String, Long>()

    /** Engine scripts, read from assets once and cached (they never change
     *  while the app runs). */
    private val bootJs: String by lazy { readAsset("nuvio/boot.js") }
    private val cheerioJs: String by lazy { readAsset("nuvio/cheerio.js") }
    private val harnessJs: String by lazy { readAsset("nuvio/harness.js") }

    /** The glue that ties the harness to the native bridges (module registry,
     *  fetch implementation, the bridge stub the harness calls back into). */
    private val REGISTER_GLUE: String =
        "globalThis.__nuvioRegisterModule('cheerio', globalThis.__nuvioCheerio);" +
            "if (typeof globalThis.CryptoJS !== 'undefined') globalThis.__nuvioRegisterModule('crypto-js', globalThis.CryptoJS);" +
            "globalThis.__nuvioFetchImpl = function (url, method, headersJson, body, followRedirects) {" +
            "  return globalThis.__hikariFetch(String(url), String(method || 'GET'), headersJson || '{}', body == null ? '' : String(body), followRedirects !== false);" +
            "};" +
            "globalThis.__nuvioBridgeStub = {" +
            "  onGetStreamsDone: function (cid, payload) { globalThis.__hikariOnStreamsDone(cid, payload); }," +
            "  onSettingsDone: function (cid, payload) { globalThis.__hikariOnStreamsDone(cid, payload); }," +
            "  fetch: null," +
            "  log: function (msg) { if (typeof globalThis.__hikariLog === 'function') globalThis.__hikariLog(String(msg)); }" +
            "};"

    /** Same glue for the validate engine, whose bridge calls are no-ops: the
     *  module is only loaded and inspected there, never run. */
    private val REGISTER_GLUE_VALIDATE: String =
        "globalThis.__nuvioRegisterModule('cheerio', globalThis.__nuvioCheerio);" +
            "if (typeof globalThis.CryptoJS !== 'undefined') globalThis.__nuvioRegisterModule('crypto-js', globalThis.CryptoJS);" +
            "globalThis.__nuvioFetchImpl = function (url, method, headersJson, body, followRedirects) {" +
            "  return globalThis.__hikariFetch(String(url), String(method || 'GET'), headersJson || '{}', body == null ? '' : String(body), followRedirects !== false);" +
            "};" +
            "globalThis.__nuvioBridgeStub = { onGetStreamsDone: function () {}, onSettingsDone: function () {}, fetch: null, log: function () {} };"

    /** Compiled QuickJS bytecode for the scripts above, keyed by script name.
     *
     *  Every provider call boots a FRESH engine, and the runtime scripts are
     *  ~550KB of JS (cheerio alone is 450KB) — so compiling them from source in
     *  every engine meant every provider call paid the full parse again, and a
     *  20-provider search spent most of its budget parsing the same bundle 20
     *  times. nuvio does exactly this caching (JsRuntime's cached
     *  polyfill/call bytecode) and it is why its search returns far more
     *  servers in the same wall-clock time.
     *
     *  QuickJS compiles to bytecode that any engine of the same build can run,
     *  so one compile serves every later VM. The cache lives for the process
     *  (the scripts are immutable assets). */
    private val bytecodeCache = ConcurrentHashMap<String, ByteArray>()

    /** Evaluates [source] in this engine through the bytecode cache: compile
     *  once, evaluate the bytecode from then on. Falls back to evaluating the
     *  source directly if compiling (or running the bytecode) fails, so a
     *  compiler hiccup can never make a provider stop working. */
    private suspend fun QuickJs.evaluateCached(name: String, source: String) {
        val compiled = bytecodeCache[name]
            ?: runCatching { compile(source, name, false) }
                .getOrNull()
                ?.also { bytecodeCache[name] = it }
        if (compiled != null && runCatching { evaluate<Any?>(compiled) }.isSuccess) {
            return
        }
        evaluate<Any?>(source, name, false)
    }

    private fun readAsset(path: String): String =
        com.hikari.app.HikariApp.instance.assets.open(path).bufferedReader().readText()

    fun resetRunTracking() {
        providerRunStart.clear()
    }

    fun providerStartedAt(providerId: String): Long? = providerRunStart[providerId]

    fun resetFetchLog() {
        fetchLogEntries.clear()
    }

    fun fetchLogSnapshot(): List<String> = fetchLogEntries.toList()

    private fun fetchLogLine(host: String, m: String, status: String, bytes: Int, ms: Long, extra: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        fetchLogEntries.addFirst("$ts $m $host -> $status ${bytes}b ${ms}ms$extra")
        while (fetchLogEntries.size > 150) fetchLogEntries.pollLast()
    }

    private fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host ?: url.take(48) }.getOrDefault(url.take(48))

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

    // ---- Engine plumbing ----

    private fun quote(s: String): String = JSONObject.quote(s)

    private val fetchExecutor: ExecutorService = Executors.newFixedThreadPool(4)

    private val client by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            // Plain OkHttp, mirroring NuvioMobile's own httpRequestRaw: no
            // cookie jar, no UA rewriting, transparent gzip via the bridge's
            // Accept-Encoding stripping, and — unlike the 0.3.5x builds — NO
            // CloudflareVerifier interceptor. nuvio does no CF solving and no
            // hidden verify WebView; it hands the provider whatever HTTP
            // returns and the provider either works or reports its own error.
            // That is exactly the behaviour the user asked to port. Sites that
            // sit behind an interactive Cloudflare challenge simply won't
            // resolve (they don't in nuvio either); the fetch log records the
            // challenge so it's diagnosable.
            .dns(DohDns)
            .build()
    }

    /** Boots a fresh engine: native bridges, then boot.js + cheerio.js +
     *  harness.js + the bridge/register glue. Returns the engine; caller must
     *  close() it in a finally. */
    private suspend fun createEngine(deferred: CompletableDeferred<String>): QuickJs {
        val qjs = QuickJs.create(jobDispatcher = Dispatchers.Default)
        qjs.evaluationTimeoutMillis = CALL_TIMEOUT_MS

        // Native bridges (synchronous, called from JS on the engine thread).
        qjs.function("__hikariFetch") { args ->
            val url = args.getOrNull(0)?.toString() ?: ""
            val method = args.getOrNull(1)?.toString() ?: "GET"
            val headersJson = args.getOrNull(2)?.toString() ?: "{}"
            val body = args.getOrNull(3)?.toString() ?: ""
            val followRedirects = args.getOrNull(4) as? Boolean ?: true
            bridgeFetch(url, method, headersJson, body, followRedirects)
        }
        qjs.function("__hikariOnStreamsDone") { args ->
            val payload = args.getOrNull(1)?.toString() ?: ""
            deferred.complete(payload)
            ""
        }
        qjs.function("__hikariLog") { args ->
            val msg = args.getOrNull(0)?.toString() ?: ""
            android.util.Log.d("Nuvio", msg)
            ""
        }
        NuvioCryptoBridge.bindAll(qjs)

        // 1. Polyfills (console, TextEncoder/Decoder, Blob, URL, AbortController,
        //    crypto/CryptoJS backed by NuvioCryptoBridge, array/object/string).
        qjs.evaluateCached("boot.js", bootJs)
        // 2. The real cheerio bundle, captured as a plain module like nuvio's
        //    runtime.html did (evaluateJavascript's size ceiling is a non-issue
        //    here, but booting it as a script keeps the exact same path).
        qjs.evaluateCached("cheerio-head.js", "var __nuvioModule = { exports: {} }; var module = __nuvioModule; var exports = module.exports;")
        qjs.evaluateCached("cheerio.js", cheerioJs)
        qjs.evaluateCached("cheerio-tail.js", "globalThis.__nuvioCheerio = module.exports;")
        // 3. Provider harness (CommonJS require, fetch, provider loader, shims).
        qjs.evaluateCached("harness.js", harnessJs)
        // 4. Glue: register cheerio/crypto-js modules (harness aliases
        //    cheerio-without-node-native + react-native-cheerio), point the
        //    harness's fetch at the native bridge, and give __bridge() a stub
        //    whose onGetStreamsDone/onSettingsDone flow back to the native
        //    completion. This mirrors exactly what the WebView's
        //    addJavascriptInterface + runtime.html provided.
        qjs.evaluateCached("register.js", REGISTER_GLUE)
        return qjs
    }

    // ---- Public API ----

    /** Runs provider.getStreams(...) in a fresh engine and returns the raw JSON
     *  payload string (`{"ok":true,"data":[...]}` or `{"ok":false,"error":"..."}`). */
    suspend fun getStreams(
        context: Context,
        source: String,
        providerId: String,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?,
    ): String {
        val settings = loadSettings(providerId).ifBlank { "{}" }
        return runProvider(
            source = source,
            providerId = providerId,
            settings = settings,
            buildCall = { cid ->
                val s = if (season == null) "null" else season.toString()
                val e = if (episode == null) "null" else episode.toString()
                "(async function () {" +
                "  try {" +
                "    globalThis.__nuvioSetSettings($settings);" +
                "    var provider = globalThis.__nuvioLoadProvider(${quote(source)}, ${quote(providerId)});" +
                "    var getStreams = provider && typeof provider.getStreams === 'function' ? provider.getStreams : globalThis.getStreams;" +
                "    if (typeof getStreams !== 'function') { globalThis.__nuvioBridgeStub.onGetStreamsDone(${quote(cid)}, JSON.stringify({ ok: false, error: 'provider has no getStreams export' })); return; }" +
                "    var result = await getStreams(${quote(tmdbId)}, ${quote(mediaType)}, $s, $e);" +
                "    if (result === undefined || result === null) result = [];" +
                "    globalThis.__nuvioBridgeStub.onGetStreamsDone(${quote(cid)}, JSON.stringify({ ok: true, data: result }));" +
                "  } catch (e) {" +
                "    globalThis.__nuvioBridgeStub.onGetStreamsDone(${quote(cid)}, JSON.stringify({ ok: false, error: String(e && e.message || e) }));" +
                "  }" +
                "})();"
        },
    )
    }

    /** Runs provider.onSettings() in a fresh engine and returns the layout JSON
     *  payload. */
    suspend fun getSettingsLayout(
        context: Context,
        source: String,
        providerId: String,
    ): String {
        val settings = loadSettings(providerId).ifBlank { "{}" }
        return runProvider(
            source = source,
            providerId = providerId,
            settings = settings,
            buildCall = { cid ->
                "(async function () {" +
                    "  try {" +
                    "    globalThis.__nuvioSetSettings($settings);" +
                    "    var provider = globalThis.__nuvioLoadProvider(${quote(source)}, ${quote(providerId)});" +
                    "    var onSettings = provider && typeof provider.onSettings === 'function' ? provider.onSettings : null;" +
                "    if (!onSettings) { globalThis.__nuvioBridgeStub.onSettingsDone(${quote(cid)}, JSON.stringify({ ok: true, data: [] })); return; }" +
                "    var layout = await onSettings();" +
                "    globalThis.__nuvioBridgeStub.onSettingsDone(${quote(cid)}, JSON.stringify({ ok: true, data: layout || [] }));" +
                "  } catch (e) {" +
                "    globalThis.__nuvioBridgeStub.onSettingsDone(${quote(cid)}, JSON.stringify({ ok: false, error: String(e && e.message || e) }));" +
                "  }" +
                "})();"
        },
    )
    }

    private suspend fun runProvider(
        source: String,
        providerId: String,
        settings: String,
        buildCall: (String) -> String,
    ): String {
        return concurrency.withPermit {
            withTimeoutOrNull(CALL_TIMEOUT_MS) {
                withContext(Dispatchers.Default) {
                    val cid = java.util.UUID.randomUUID().toString()
                    val deferred = CompletableDeferred<String>()
                    val qjs = createEngine(deferred)
                    providerRunStart[providerId] = System.currentTimeMillis()
                    try {
                        qjs.evaluate<Any?>(buildCall(cid), "call.js", false)
                        // evaluate() returns once the async IIFE settles, which
                        // happens when onGetStreamsDone/onSettingsDone completed
                        // the deferred above — so await() returns immediately.
                        deferred.await()
                    } catch (e: Throwable) {
                        // Engine-level failure (boot error, native interrupt after
                        // evaluationTimeoutMillis, coroutine cancellation). The
                        // provider's own errors already flow through the bridge.
                        if (deferred.isCompleted) deferred.await()
                        else "{\"ok\":false,\"error\":${quote(e.message ?: e.javaClass.simpleName)}}"
                    } finally {
                        runCatching { qjs.close() }
                    }
                }
            } ?: "{\"ok\":false,\"error\":\"provider timed out after ${CALL_TIMEOUT_MS / 1000}s\"}"
        }
    }

    /** True when the JS module loads and exports a usable getStreams function.
     *  Must start with "OK"; "ERR:..." carries a detail message; anything else
     *  means "not a valid nuvio provider". */
    suspend fun validate(context: Context, source: String): String =
        withContext(Dispatchers.Default) {
            val qjs = QuickJs.create(jobDispatcher = Dispatchers.Default)
            qjs.evaluationTimeoutMillis = VALIDATE_TIMEOUT_MS
            try {
                NuvioCryptoBridge.bindAll(qjs)
                qjs.function("__hikariFetch") { args ->
                    bridgeFetch(
                        args.getOrNull(0)?.toString() ?: "",
                        args.getOrNull(1)?.toString() ?: "GET",
                        args.getOrNull(2)?.toString() ?: "{}",
                        args.getOrNull(3)?.toString() ?: "",
                        args.getOrNull(4) as? Boolean ?: true,
                    )
                }
                qjs.evaluateCached("boot.js", bootJs)
                qjs.evaluateCached("cheerio-head.js", "var __nuvioModule = { exports: {} }; var module = __nuvioModule; var exports = module.exports;")
                qjs.evaluateCached("cheerio.js", cheerioJs)
                qjs.evaluateCached("cheerio-tail.js", "globalThis.__nuvioCheerio = module.exports;")
                qjs.evaluateCached("harness.js", harnessJs)
                qjs.evaluateCached("register-validate.js", REGISTER_GLUE_VALIDATE)
                val result = qjs.evaluate<String?>(
                    "(function () { try { var m = globalThis.__nuvioLoadProvider(${quote(source)}, 'validate');" +
                        " if (m && typeof m.getStreams === 'function') return 'OK'; return 'NO';" +
                        " } catch (e) { return 'ERR:' + String(e && e.message || e); } })();",
                    "validate.js", false,
                )
                result?.trim()?.takeIf { it.isNotBlank() } ?: "NO"
            } catch (e: Throwable) {
                "ERR: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                runCatching { qjs.close() }
            }
        }

    /** Synchronous fetch bridge invoked from JS on the engine thread. Returns a
     *  JSON string the harness parses into a fetch-like response. */
    fun bridgeFetch(
        url: String,
        method: String,
        headersJson: String,
        body: String,
        followRedirects: Boolean,
    ): String {
        val started = System.currentTimeMillis()
        val m = method.uppercase()
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
                    val host = hostOf(url)
                    val extra = StringBuilder()
                    if (r.code == 403 || r.code == 503) {
                        val low = String(bytes, Charsets.ISO_8859_1).lowercase()
                        if (low.contains("just a moment") || low.contains("attention required") ||
                            low.contains("cf-chl") || low.contains("checking your browser")
                        ) extra.append(" CF-CHALLENGE-UNSOLVED")
                    }
                    val ce = r.headers["Content-Encoding"]
                    if (ce != null && ce.isNotBlank()) extra.append(" CE=").append(ce)
                    // For failures or big bodies, log what the bytes actually
                    // look like — tells us if a 200 is a JSON API hit, an HTML
                    // challenge page, or (CE!=gzip) compressed garbage the
                    // provider can't parse.
                    if (r.code != 200 || bytes.size > 100_000) {
                        val ct = (r.headers["Content-Type"] ?: "?").substringBefore(";")
                        extra.append(" CT=").append(ct)
                        val preview = String(bytes, Charsets.ISO_8859_1).trim().take(60)
                            .replace(Regex("[^\\x20-\\x7E]"), ".")
                        extra.append(" [").append(preview).append("]")
                    }
                    fetchLogLine(host, m, r.code.toString(), bytes.size, System.currentTimeMillis() - started, extra.toString())
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
                    // ?: UTF-8).
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
                fetchLogLine(hostOf(url), m, "ERR", 0, System.currentTimeMillis() - started, " ${e.message ?: "network error"}")
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
            fetchLogLine(hostOf(url), m, "TIMEOUT", 0, System.currentTimeMillis() - started, " fetch did not finish in ${FETCH_TIMEOUT_MS / 1000}s")
            "{\"ok\":false,\"status\":0,\"statusText\":\"fetch timed out\",\"url\":${quote(url)}," +
                "\"headers\":{},\"body\":\"\",\"bodyBase64\":\"\"}"
        }
    }
}
