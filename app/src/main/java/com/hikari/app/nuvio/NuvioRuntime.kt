package com.hikari.app.nuvio

import android.util.Base64
import android.content.Context
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import com.hikari.app.net.DohDns
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

object NuvioRuntime {

    private const val MAX_CONCURRENT = 12
    /** [MAX_CONCURRENT] with the performance booster on (Settings →
     *  Performance): half as many engines at once, for a device that cannot
     *  afford twelve QuickJS VMs while it also draws the UI. */
    private const val PERF_CONCURRENT = 5
    private const val TV_CONCURRENT = 4
    private const val FETCH_TIMEOUT_MS = 60_000L
    private const val CALL_TIMEOUT_MS = 60_000L
    private const val VALIDATE_TIMEOUT_MS = 20_000L

    private const val ENGINE_MEMORY_LIMIT = 256L * 1024 * 1024

    private val engineMemoryLimit: Long
        get() = com.hikari.app.data.PerfMode.tvEngineMemoryLimit ?: ENGINE_MEMORY_LIMIT

    /** The default User-Agent for nuvio bridge fetches, byte-identical to
     *  NuvioMobile's own FetchBridge fallback: providers that send no UA get
     *  this one, so sites answer Hikari exactly what they answer nuvio. */
    private const val NUVIO_DEFAULT_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    /** Binary-body marker: the harness sends `b64:<base64>` when a provider
     *  POSTs an ArrayBuffer/typed-array body (see __nuvioFetch). The bridge
     *  args are all strings, so binary rides the same channel with a prefix
     *  the reference client's bodyKind/base64 pair fills. */
    internal const val BINARY_BODY_PREFIX = "b64:"

    /** Bounds how many providers run their JS engines at once (see above). */
    private val concurrency = Semaphore(MAX_CONCURRENT)

    private val perfConcurrency = Semaphore(PERF_CONCURRENT)

    /** [TV_CONCURRENT]'s pool — see the note on the constant, and on [gate]. */
    private val tvConcurrency = Semaphore(TV_CONCURRENT)

    private val gate: Semaphore
        get() = when {
            // The television's own pool comes FIRST: the booster below is a
            // switch the user can turn off, and a 1GB box is not.
            com.hikari.app.data.PerfMode.tvDevice -> tvConcurrency
            com.hikari.app.data.PerfMode.active -> perfConcurrency
            else -> concurrency
        }

    private const val BACKGROUND_CONCURRENT = 3
    private val backgroundConcurrency = Semaphore(BACKGROUND_CONCURRENT)

    suspend fun <T> withBackgroundSlot(block: suspend () -> T): T =
        backgroundConcurrency.withPermit { block() }

    private val fetchLogEntries = ConcurrentLinkedDeque<Pair<Int, String>>()

    /** Monotonic sequence behind [fetchLogEntries] — every entry carries its own
     *  number so a call can ask for "what happened since I started" (see
     *  [fetchLogMark]). */
    private val fetchLogCount = java.util.concurrent.atomic.AtomicInteger(0)

    /** When each provider's JS actually started (right after it acquired an
     *  engine slot) — lets the sources sheet distinguish "cut off while still
     *  queued" from "cut off mid-run". */
    private val providerRunStart = ConcurrentHashMap<String, Long>()

    /** Engine scripts, read from assets once and cached (they never change
     *  while the app runs). */
    private val bootJs: String by lazy { readAsset("nuvio/boot.js") }
    private val cheerioJs: String by lazy { readAsset("nuvio/cheerio.js") }
    private val harnessJs: String by lazy { readAsset("nuvio/harness.js") }

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

    private fun needsCheerio(source: String): Boolean =
        source.contains("cheerio", ignoreCase = true) || source.contains("$(")

    private fun cheerioRetryWorthwhile(payload: String): Boolean {
        if (payload.contains("timed out")) return false
        return runCatching { org.json.JSONObject(payload).optBoolean("ok", false) }
            .getOrDefault(false) == false
    }

    fun resetRunTracking() {
        providerRunStart.clear()
    }

    fun providerStartedAt(providerId: String): Long? = providerRunStart[providerId]

    fun resetFetchLog() {
        fetchLogEntries.clear()
    }

    fun fetchLogSnapshot(): List<String> = fetchLogEntries.map { it.second }

    fun fetchLogMark(): Int = fetchLogCount.get()

    fun fetchLogSince(mark: Int, limit: Int = 8): List<String> =
        fetchLogEntries.filter { it.first > mark }.take(limit).map { it.second }

    private fun fetchLogLine(host: String, m: String, status: String, bytes: Int, ms: Long, extra: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        fetchLogEntries.addFirst(
            fetchLogCount.incrementAndGet() to "$ts $m $host -> $status ${bytes}b ${ms}ms$extra",
        )
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

    private val client by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            // Every provider fetch in the whole app used to be submitted to a
            // fixed pool of FOUR threads and BLOCKED on, so only four nuvio
            // requests could ever be in flight —
            // however many engines were running — and a single site that hung
            // (NetMirror in the user's own log: three concurrent attempts, "no
            // answer in 92s" each) took a quarter of that away from every other
            // provider. The reference client has no such ceiling: its bridge is
            // asynchronous, so all the providers' requests are simply in flight
            // together. The pool is gone; OkHttp's own dispatcher hands the
            // wait, and its limits are those of the network rather than of this
            // class (see [executeFetch]).
            .dispatcher(
                Dispatcher().apply {
                    maxRequests = 64
                    // The default of 5 per host throttles the many providers
                    // that hammer one API with a dozen parallel calls.
                    maxRequestsPerHost = 12
                }
            )
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

    private val noRedirectClient by lazy {
        client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    private fun clientFor(followRedirects: Boolean): OkHttpClient =
        if (followRedirects) client else noRedirectClient

    /** Boots a fresh engine: native bridges, then boot.js + cheerio.js +
     *  harness.js + the bridge/register glue. Returns the engine; caller must
     *  close() it in a finally. */
    private suspend fun createEngine(
        deferred: CompletableDeferred<String>,
        withCheerio: Boolean = true,
    ): QuickJs {
        val qjs = QuickJs.create(jobDispatcher = Dispatchers.Default)
        qjs.evaluationTimeoutMillis = CALL_TIMEOUT_MS
        qjs.memoryLimit = engineMemoryLimit

        qjs.asyncFunction("__hikariFetch") { args ->
            val url = args.getOrNull(0)?.toString() ?: ""
            val method = args.getOrNull(1)?.toString() ?: "GET"
            val headersJson = args.getOrNull(2)?.toString() ?: "{}"
            val body = args.getOrNull(3)?.toString() ?: ""
            val followRedirects = args.getOrNull(4) as? Boolean ?: true
            bridgeFetchAsync(url, method, headersJson, body, followRedirects)
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
        qjs.evaluateCached("cheerio-head.js", "var __nuvioModule = { exports: {} }; var module = __nuvioModule; var exports = module.exports;")
        if (withCheerio) qjs.evaluateCached("cheerio.js", cheerioJs)
        qjs.evaluateCached("cheerio-tail.js", "globalThis.__nuvioCheerio = module.exports;")
        // 3. Provider harness (CommonJS require, fetch, provider loader, shims).
        qjs.evaluateCached("harness.js", harnessJs)
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
        val wantsCheerio = needsCheerio(source)
        val first = runProvider(
            source = source,
            providerId = providerId,
            settings = settings,
            withCheerio = wantsCheerio,
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
        if (wantsCheerio || !cheerioRetryWorthwhile(first)) return first
        com.hikari.app.data.Logs.log(
            "Nuvio",
            "$providerId: failed without cheerio — retrying with it",
        )
        return runProvider(
            source = source,
            providerId = providerId,
            settings = settings,
            withCheerio = true,
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
            withCheerio = needsCheerio(source),
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
        withCheerio: Boolean = true,
        buildCall: (String) -> String,
    ): String {
        return gate.withPermit {
            withTimeoutOrNull(CALL_TIMEOUT_MS) {
                withContext(Dispatchers.Default) {
                    val cid = java.util.UUID.randomUUID().toString()
                    val deferred = CompletableDeferred<String>()
                    val qjs = createEngine(deferred, withCheerio)
                    providerRunStart[providerId] = System.currentTimeMillis()
                    try {
                        qjs.evaluate<Any?>(buildCall(cid), "call.js", false)
                        deferred.await()
                    } catch (e: Throwable) {
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
            qjs.memoryLimit = engineMemoryLimit
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


    /** One HTTP response that really arrived, already read into memory. */
    private sealed class Fetched {
        class Ok(
            val status: Int,
            val message: String,
            val finalUrl: String,
            val headers: Map<String, String>,
            val bytes: ByteArray,
        ) : Fetched()

        class Failure(val reason: String) : Fetched()
    }

    suspend fun bridgeFetchAsync(
        url: String,
        method: String,
        headersJson: String,
        body: String,
        followRedirects: Boolean,
    ): String {
        val started = System.currentTimeMillis()
        val m = method.uppercase()
        val request = try {
            buildFetchRequest(url, m, headersJson, body)
        } catch (t: Throwable) {
            fetchLogLine(hostOf(url), m, "ERR", 0, 0L, " " + (t.message ?: "bad request"))
            return failureJson(url, t.message ?: "bad request")
        }
        val fetched = withTimeoutOrNull(FETCH_TIMEOUT_MS) { executeFetch(request, followRedirects) }
        if (fetched == null) {
            fetchLogLine(
                hostOf(url), m, "TIMEOUT", 0, System.currentTimeMillis() - started,
                " fetch did not finish in " + (FETCH_TIMEOUT_MS / 1000) + "s",
            )
            return failureJson(url, "fetch timed out")
        }
        return when (fetched) {
            is Fetched.Ok -> okFetchJson(url, m, started, fetched)
            is Fetched.Failure -> {
                fetchLogLine(
                    hostOf(url), m, "ERR", 0, System.currentTimeMillis() - started,
                    " " + fetched.reason,
                )
                failureJson(url, fetched.reason)
            }
        }
    }

    private suspend fun executeFetch(request: Request, followRedirects: Boolean): Fetched =
        suspendCancellableCoroutine { cont ->
            val call = clientFor(followRedirects).newCall(request)
            cont.invokeOnCancellation { runCatching { call.cancel() } }
            runCatching {
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (!cont.isCancelled) {
                            cont.resume(Fetched.Failure(e.message ?: e.javaClass.simpleName))
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val outcome = try {
                            val bytes = response.body?.bytes() ?: ByteArray(0)
                            Fetched.Ok(
                                status = response.code,
                                message = response.message,
                                finalUrl = response.request.url.toString(),
                                headers = lowerHeaders(response.headers),
                                bytes = bytes,
                            )
                        } catch (t: Throwable) {
                            Fetched.Failure(t.message ?: t.javaClass.simpleName)
                        } finally {
                            runCatching { response.close() }
                        }
                        if (!cont.isCancelled) cont.resume(outcome)
                    }
                })
            }.onFailure { t ->
                if (!cont.isCancelled) cont.resume(Fetched.Failure(t.message ?: t.javaClass.simpleName))
            }
        }

    /** The response's headers with lowercase names and one entry per name (the
     *  shape the harness and the providers read: `headers['content-type']`). */
    private fun lowerHeaders(headers: okhttp3.Headers): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (name in headers.names()) {
            val key = name.lowercase()
            if (out.containsKey(key)) continue
            out[key] = headers.values(name).joinToString(",")
        }
        return out
    }

    /** The request a bridge fetch describes: the provider's own headers (with
     *  the Accept-Encoding caveat below) and the app's browser UA by default. */
    private fun buildFetchRequest(
        url: String,
        method: String,
        headersJson: String,
        body: String,
    ): Request {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", NUVIO_DEFAULT_UA)
        val h = runCatching { JSONObject(headersJson) }.getOrNull()
        if (h != null) {
            h.keys().forEach { k ->
                if (k.equals("Accept-Encoding", ignoreCase = true)) return@forEach
                runCatching { builder.header(k, h.getString(k)) }
            }
        }
        // Request body, mirroring NuvioMobile's httpRequestRaw: POST/PUT/PATCH/
        // DELETE may carry one; anything else never does. The harness sends
        // binary (ArrayBuffer/typed-array) bodies as `b64:<base64>` (see
        // BINARY_BODY_PREFIX) so they ride the string bridge verbatim instead
        // of arriving as "[object Uint8Array]". Default media types match the
        // reference exactly: form-encoded for POST, JSON for PUT/PATCH/DELETE.
        val bodyBytes: ByteArray? = when {
            body.startsWith(BINARY_BODY_PREFIX) -> runCatching {
                android.util.Base64.decode(body.removePrefix(BINARY_BODY_PREFIX), android.util.Base64.DEFAULT)
            }.getOrNull()
            body.isNotEmpty() -> body.toByteArray(Charsets.UTF_8)
            else -> null
        }
        if (bodyBytes != null && (method == "POST" || method == "PUT" || method == "PATCH" || method == "DELETE")) {
            val type = if (h != null && h.has("Content-Type")) h.getString("Content-Type")
            else if (method == "POST") "application/x-www-form-urlencoded" else "application/json"
            val mediaType = runCatching { type.toMediaType() }.getOrNull()
                ?: throw IllegalArgumentException("bad Content-Type: $type")
            builder.method(method, bodyBytes.toRequestBody(mediaType))
        } else {
            builder.method(if (method == "HEAD") "HEAD" else "GET", null)
        }
        return builder.build()
    }

    /** The `{ok:true,...}` payload the harness turns into a Response, for a
     *  response that really arrived — plus the fetch-log line that makes a
     *  failing provider diagnosable (a 403 Cloudflare wall, a compressed body
     *  the provider cannot parse, or a JSON API hit). */
    private fun okFetchJson(url: String, method: String, started: Long, ok: Fetched.Ok): String {
        val extra = StringBuilder()
        if (ok.status == 403 || ok.status == 503) {
            val low = String(ok.bytes, Charsets.ISO_8859_1).lowercase()
            if (low.contains("just a moment") || low.contains("attention required") ||
                low.contains("cf-chl") || low.contains("checking your browser")
            ) extra.append(" CF-CHALLENGE-UNSOLVED")
        }
        val ce = ok.headers["content-encoding"]
        if (ce != null && ce.isNotBlank()) extra.append(" CE=").append(ce)
        if (ok.status != 200 || ok.bytes.size > 100_000) {
            val ct = (ok.headers["content-type"] ?: "?").substringBefore(";")
            extra.append(" CT=").append(ct)
            val preview = String(ok.bytes, Charsets.ISO_8859_1).trim().take(60)
                .replace(Regex("[^\\x20-\\x7E]"), ".")
            extra.append(" [").append(preview).append("]")
        }
        fetchLogLine(
            hostOf(url), method, ok.status.toString(), ok.bytes.size,
            System.currentTimeMillis() - started, extra.toString(),
        )
        val out = JSONObject()
        out.put("ok", ok.status in 200..299)
        out.put("status", ok.status)
        out.put("statusText", ok.message)
        out.put("url", ok.finalUrl)
        val hdrs = JSONObject()
        ok.headers.forEach { (k, v) -> runCatching { hdrs.put(k, v) } }
        out.put("headers", hdrs)
        // Honor the response charset (nuvio: contentType().charset() ?: UTF-8).
        val charset = runCatching {
            val enc = (ok.headers["content-type"] ?: "").substringAfter("charset=", "").trim().trim('"')
            if (enc.isEmpty()) Charsets.UTF_8 else Charset.forName(enc)
        }.getOrNull() ?: Charsets.UTF_8
        out.put("body", String(ok.bytes, charset))
        out.put("bodyBase64", Base64.encodeToString(ok.bytes, Base64.NO_WRAP))
        out.put("ms", System.currentTimeMillis() - started)
        return out.toString()
    }

    /** The `{ok:false,...}` payload for a request that never produced a
     *  response — the shape the harness's providers already handle. */
    private fun failureJson(url: String, reason: String): String =
        "{\"ok\":false,\"status\":0,\"statusText\":" + quote(reason) +
            ",\"url\":" + quote(url) +
            ",\"headers\":{},\"body\":\"\",\"bodyBase64\":\"\"}"

    /** [bridgeFetchAsync] for the non-suspend engine paths ([validate], whose
     *  module load only inspects the provider and never fetches). */
    fun bridgeFetch(
        url: String,
        method: String,
        headersJson: String,
        body: String,
        followRedirects: Boolean,
    ): String = runBlocking { bridgeFetchAsync(url, method, headersJson, body, followRedirects) }
}
