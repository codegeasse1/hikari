package com.hikari.app.providers.vega

import android.content.Context
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import com.hikari.app.HikariApp
import com.hikari.app.net.DohDns
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaTypeOrNull
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

/**
 * Runs a Vega provider (github.com/Zenda-Cross/vega-providers) inside a fresh
 * embedded QuickJS engine.
 *
 * A Vega provider is a set of standalone CommonJS modules — one file per
 * function — that the real Vega app loads with `require` and hands a
 * `providerContext` holding axios, cheerio, the repo's common headers, a
 * per-provider key/value store and a WebView solver. [VegaRuntime] recreates
 * that surface (see assets/vega/harness.js) on top of the pieces Hikari already
 * ships for nuvio: boot.js's polyfills, the real cheerio bundle, and nuvio's
 * harness (global `fetch`, `Buffer`, `require`), with every request going
 * through the ASYNCHRONOUS `__hikariFetch` bridge below so a provider's
 * `Promise.all([...])` really is parallel.
 *
 * ONE MODULE PER CALL. Vega's files are self-contained bundles (no cross-file
 * `require` anywhere in the published dist), so a call only ever needs its own
 * file: a catalog read loads catalog.js, a search loads posts.js, a stream
 * lookup loads stream.js. The file is wrapped in a CommonJS function so its
 * top-level `var`s cannot collide with anything, and the wrapper is compiled to
 * QuickJS bytecode once and reused for every later call ([bytecodeCache]) —
 * the same trick [com.hikari.app.nuvio.NuvioRuntime] uses to stop paying the
 * parse cost per provider.
 */
object VegaRuntime {

    /** Engines running at once. A Vega call is one native VM plus (usually) the
     *  cheerio bundle, so the count is bounded and the rest queue. */
    private const val MAX_CONCURRENT = 8
    /** [MAX_CONCURRENT] with the performance booster on (Settings → Performance). */
    private const val PERF_CONCURRENT = 4
    private const val FETCH_TIMEOUT_MS = 30_000L
    /** One provider call. Same ceiling nuvio uses: a cold engine plus a slow
     *  site fetch plus extraction is normal. */
    private const val CALL_TIMEOUT_MS = 60_000L
    /** getPosts/getSearchPosts/getMeta — a catalog or a detail page needs
     *  several page fetches and can outlast [CALL_TIMEOUT_MS] on a slow link. */
    private const val CATALOG_TIMEOUT_MS = 75_000L
    private const val VALIDATE_TIMEOUT_MS = 20_000L
    /** Room on top of a call's budget for the pump loop's own bookkeeping. */
    private const val CALL_GRACE_MS = 20_000L
    private const val TIMER_MAX_WAIT_MS = 1_500L
    private const val ENGINE_MEMORY_LIMIT = 256L * 1024 * 1024

    private val concurrency = Semaphore(MAX_CONCURRENT)
    private val perfConcurrency = Semaphore(PERF_CONCURRENT)
    private val gate: Semaphore
        get() = if (com.hikari.app.data.PerfMode.active) perfConcurrency else concurrency

    private val bootJs: String by lazy { readAsset("nuvio/boot.js") }
    private val cheerioJs: String by lazy { readAsset("nuvio/cheerio.js") }
    private val nuvioHarnessJs: String by lazy { readAsset("nuvio/harness.js") }
    private val vegaHarnessJs: String by lazy { readAsset("vega/harness.js") }
    private val commonHeadersJs: String by lazy { readAsset("vega/commonHeaders.js") }

    /** Compiled QuickJS bytecode, keyed by script name. Immutable runtime
     *  scripts are keyed by name; a provider module by provider+file+hash so a
     *  re-install (or an update) invalidates it. */
    private val bytecodeCache = ConcurrentHashMap<String, ByteArray>()

    /** Diagnostic ring buffer (host, status, size, latency), mirrored to the log
     *  so a failing provider is diagnosable from a log file. */
    private val fetchLogEntries = ConcurrentLinkedDeque<String>()

    fun resetFetchLog() {
        fetchLogEntries.clear()
    }

    fun fetchLogSnapshot(): List<String> = fetchLogEntries.toList()

    // ---- KV store: filesDir/vega/providers/<value>/kv.json ----

    private class Kv(private val file: File) {
        val obj: JSONObject = runCatching {
            JSONObject(file.takeIf { it.exists() }?.readText() ?: "{}")
        }.getOrDefault(JSONObject())

        var changed = false

        fun json(): String = obj.toString()

        fun set(key: String, rawJson: String) {
            // An empty payload means "this key was deleted" (see the harness).
            if (rawJson.isEmpty()) {
                obj.remove(key)
                changed = true
                return
            }
            runCatching {
                val value = org.json.JSONTokener(rawJson).nextValue()
                obj.put(key, value)
                changed = true
            }
        }

        fun del(key: String) {
            obj.remove(key)
            changed = true
        }

        fun clear() {
            val keys = mutableListOf<String>()
            obj.keys().forEach { keys += it }
            keys.forEach { obj.remove(it) }
            changed = true
        }

        fun save() {
            if (!changed) return
            runCatching {
                if (obj.length() == 0) {
                    file.delete()
                    return
                }
                file.parentFile?.mkdirs()
                file.writeText(obj.toString())
            }
        }
    }

    private fun readAsset(path: String): String =
        runCatching {
            HikariApp.instance.assets.open(path).bufferedReader().readText()
        }.getOrDefault("")

    private fun quote(s: String): String = JSONObject.quote(s)

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .dispatcher(
                Dispatcher().apply {
                    maxRequests = 64
                    maxRequestsPerHost = 12
                }
            )
            .dns(DohDns)
            .build()
    }

    private val noRedirectClient: OkHttpClient by lazy {
        client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    private fun clientFor(followRedirects: Boolean): OkHttpClient =
        if (followRedirects) client else noRedirectClient

    // ---- engine ----

    /** Boots a fresh engine: native bridges, polyfills, cheerio (when the caller
     *  wants it), nuvio's harness (fetch/Buffer/require), the Vega register glue
     *  (commonHeaders + kv + this call's args) and the Vega harness. */
    private suspend fun createEngine(
        deferred: CompletableDeferred<String>,
        kv: Kv,
        value: String,
        withCheerio: Boolean,
        /** How many bridge fetches are in flight in this engine — the pump loop
         *  uses it to tell "waiting on the network" from "stopped responding". */
        inFlight: java.util.concurrent.atomic.AtomicInteger,
    ): QuickJs {
        val qjs = QuickJs.create(jobDispatcher = Dispatchers.Default)
        qjs.evaluationTimeoutMillis = CALL_TIMEOUT_MS
        qjs.memoryLimit = ENGINE_MEMORY_LIMIT

        qjs.asyncFunction("__hikariFetch") { args ->
            val url = args.getOrNull(0)?.toString() ?: ""
            val method = args.getOrNull(1)?.toString() ?: "GET"
            val headersJson = args.getOrNull(2)?.toString() ?: "{}"
            val body = args.getOrNull(3)?.toString() ?: ""
            val followRedirects = args.getOrNull(4) as? Boolean ?: true
            inFlight.incrementAndGet()
            try {
                bridgeFetchAsync(url, method, headersJson, body, followRedirects)
            } finally {
                inFlight.decrementAndGet()
            }
        }
        qjs.function("__vegDone") { args ->
            val payload = args.getOrNull(0)?.toString() ?: ""
            deferred.complete(payload)
            ""
        }
        qjs.function("__vegaKvSet") { args ->
            val key = args.getOrNull(0)?.toString() ?: ""
            val json = args.getOrNull(1)?.toString() ?: ""
            if (key.isNotEmpty()) kv.set(key, json)
            ""
        }
        qjs.function("__vegaKvDel") { args ->
            val key = args.getOrNull(0)?.toString() ?: ""
            if (key.isNotEmpty()) kv.del(key)
            ""
        }
        qjs.function("__vegaKvClear") { _ ->
            kv.clear()
            ""
        }
        qjs.function("__vegaLog") { args ->
            val msg = args.getOrNull(0)?.toString() ?: ""
            android.util.Log.d("Vega", msg)
            ""
        }

        // 1. Polyfills (console, URL, TextEncoder/Decoder, Blob, AbortController,
        //    atob/btoa, crypto).
        qjs.evaluateCached("boot.js", bootJs)
        // 2. The cheerio bundle, captured as a CommonJS module like nuvio does.
        qjs.evaluateCached(
            "cheerio-head.js",
            "var __nuvioModule = { exports: {} }; var module = __nuvioModule; var exports = module.exports;",
        )
        if (withCheerio) qjs.evaluateCached("cheerio.js", cheerioJs)
        qjs.evaluateCached("cheerio-tail.js", "globalThis.__nuvioCheerio = module.exports;")
        // 3. nuvio's harness: global fetch over the bridge, Buffer, require, the
        //    module registry cheerio is registered in.
        qjs.evaluateCached("nuvio-harness.js", nuvioHarnessJs)
        // 4. Register glue: cheerio module, the fetch bridge, the module loader
        //    for the common-headers object, and this provider's value + kv. Not
        //    cached — it carries per-call state.
        qjs.evaluate<Any?>(
            buildRegisterScript(kv, value),
            "vega-register.js",
            false,
        )
        // 5. The Vega providerContext + call machinery.
        qjs.evaluateCached("vega-harness.js", vegaHarnessJs)
        return qjs
    }

    /**
     * The glue that ties nuvio's harness to Vega's expectations.
     *
     * `__vegaLoadIsolated` compiles a CommonJS file inside a real function scope
     * (`new Function`) so its top-level `var`s never leak into the engine's
     * global scope — used for the shared common-headers module, whose helper
     * names (`__defProp`, `__export`, …) are the same ones every provider bundle
     * declares.
     */
    private fun buildRegisterScript(kv: Kv, value: String): String =
        "globalThis.__nuvioRegisterModule('cheerio', globalThis.__nuvioCheerio);" +
            "globalThis.__nuvioFetchImpl = function (url, method, headersJson, body, followRedirects) {" +
            "  return globalThis.__hikariFetch(String(url), String(method || 'GET'), headersJson || '{}', body == null ? '' : String(body), followRedirects !== false);" +
            "};" +
            "globalThis.__nuvioBridgeStub = {" +
            "  onGetStreamsDone: function () {}," +
            "  onSettingsDone: function () {}," +
            "  fetch: null," +
            "  log: function (m) { if (typeof globalThis.__vegaLog === 'function') globalThis.__vegaLog(String(m)); }" +
            "};" +
            "globalThis.__vegaLoadIsolated = function (source, name) {" +
            "  var mod = { exports: {} };" +
            "  var f = new Function('module', 'exports', 'require', '__dirname', '__filename', source);" +
            "  f(mod, mod.exports, globalThis.__nuvioRequire, '/', '/' + (name || 'module.js'));" +
            "  return mod.exports;" +
            "};" +
            "globalThis.__vegaCommonHeadersJson = JSON.stringify((function () {" +
            "  try {" +
            "    var ex = globalThis.__vegaLoadIsolated(${quote(commonHeadersJs)}, 'headers.js');" +
            "    return (ex && ex.headers) ? ex.headers : {};" +
            "  } catch (e) { return {}; }" +
            "})());" +
            "globalThis.__vegaProviderValue = ${quote(value)};" +
            "globalThis.__vegaKvJson = ${quote(kv.json())};"

    /** Evaluates [source] in this engine through the bytecode cache: compile
     *  once, run the bytecode from then on. Falls back to the source when
     *  compiling (or running the bytecode) fails. */
    private suspend fun QuickJs.evaluateCached(name: String, source: String) {
        if (source.isBlank()) return
        val compiled = bytecodeCache[name]
            ?: runCatching { compile(source, name, false) }
                .getOrNull()
                ?.also { bytecodeCache[name] = it }
        if (compiled != null && runCatching { evaluate<Any?>(compiled) }.isSuccess) return
        evaluate<Any?>(source, name, false)
    }

    /** Wraps a provider module body in a CommonJS function and runs it, leaving
     *  its exports on `globalThis.__vegaExports`. The wrapper is what makes the
     *  module's top-level `var`s private and, being a single script, it is
     *  bytecode-cached per provider+file+content.
     *
     *  `module`/`module.exports` are the SAME object handed to the body as both
     *  parameters, exactly as CommonJS does — a body that writes
     *  `exports.foo = …` must end up in `module.exports`, and one that
     *  reassigns `module.exports = …` (the published `headers.js` does) must be
     *  what the caller reads back. */
    private suspend fun QuickJs.loadModule(cacheKey: String, name: String, source: String) {
        val wrapped =
            "globalThis.__vegaExports = (function () {\n" +
                "  var module = { exports: {} };\n" +
                "  (function (module, exports, require, __dirname, __filename) {\n" +
                source +
                "\n  })(module, module.exports, globalThis.__nuvioRequire, '/', '/' + ${quote("$name.js")});\n" +
                "  return module.exports;\n" +
                "})();"
        evaluateCached(cacheKey, wrapped)
    }

    /**
     * Loads one provider file and runs [callScript] (which uses the harness's
     * `__vegaCall`/`__vegaCallMany`/`__vegaCatalogJson`), returning the raw
     * payload the provider produced (`{"ok":true,"data":…}` or
     * `{"ok":false,"error":"…"}`).
     */
    private suspend fun run(
        providerDir: File,
        providerId: String,
        value: String,
        fileName: String,
        withCheerio: Boolean,
        budgetMs: Long,
        callScript: String,
    ): String {
        val file = File(providerDir, "$fileName.js")
        if (!file.exists()) {
            return "{\"ok\":false,\"error\":${quote("this provider has no $fileName.js")}}"
        }
        val source = runCatching { file.readText() }.getOrNull()
        if (source.isNullOrBlank()) {
            return "{\"ok\":false,\"error\":${quote("$fileName.js is empty — reinstall this provider")}}"
        }
        val kv = Kv(File(providerDir, "kv.json"))
        val started = System.currentTimeMillis()
        val inFlight = java.util.concurrent.atomic.AtomicInteger(0)
        return gate.withPermit {
            withTimeoutOrNull(budgetMs + CALL_GRACE_MS) {
                withContext(Dispatchers.Default) {
                    val deferred = CompletableDeferred<String>()
                    var qjs: QuickJs? = null
                    try {
                        qjs = createEngine(deferred, kv, value, withCheerio, inFlight)
                        val key = "vega/$providerId/$fileName/${source.hashCode()}"
                        qjs.loadModule(key, fileName, source)
                        qjs.evaluate<Any?>("$callScript\n;void 0;", "vega-call.js", false)
                        val deadline = System.currentTimeMillis() + budgetMs
                        var idleRounds = 0
                        var idleBail = false
                        while (!deferred.isCompleted && System.currentTimeMillis() < deadline) {
                            val next = qjs.evaluate<Any?>("__vegaFireTimer()", "vega-timer.js", false)
                            if (deferred.isCompleted) break
                            // -1 = nothing parked, 0 = a timer just fired, >0 =
                            // ms until the next one is due (never early — providers
                            // use setTimeout for Promise.race guards and retry
                            // backoff, and firing early would abort a healthy
                            // request).
                            val wait = (next as? Number)?.toLong() ?: -1L
                            val busy = inFlight.get() > 0
                            when {
                                busy -> {
                                    idleRounds = 0
                                    delay(if (wait >= 1 && wait <= 40) wait else 8)
                                }
                                wait > 0 -> {
                                    idleRounds = 0
                                    delay(wait.coerceAtMost(TIMER_MAX_WAIT_MS))
                                }
                                wait == 0L -> {
                                    idleRounds = 0
                                    delay(1)
                                }
                                else -> {
                                    // Nothing parked and nothing in flight: the
                                    // provider either finished on its own (and a
                                    // promise `then` is about to run) or has
                                    // stopped responding. A few rounds is enough
                                    // to notice the former.
                                    idleRounds++
                                    if (idleRounds >= 6) {
                                        idleBail = true
                                        break
                                    }
                                    delay(12)
                                }
                            }
                        }
                        if (deferred.isCompleted) deferred.await()
                        else if (idleBail) {
                            "{\"ok\":false,\"error\":${quote("the provider stopped responding before it could finish")}}"
                        } else {
                            "{\"ok\":false,\"error\":${quote("timed out after ${budgetMs / 1000}s")}}"
                        }
                    } catch (e: Throwable) {
                        if (deferred.isCompleted) deferred.await()
                        else "{\"ok\":false,\"error\":${quote(e.message ?: e.javaClass.simpleName)}}"
                    } finally {
                        runCatching { qjs?.close() }
                        kv.save()
                        logCall(providerId, fileName, System.currentTimeMillis() - started)
                    }
                }
            } ?: "{\"ok\":false,\"error\":${quote("timed out after ${budgetMs / 1000}s")}}"
        }
    }

    private fun logCall(providerId: String, fileName: String, ms: Long) {
        if (fetchLogEntries.size > 200) return
        fetchLogEntries.addFirst("$providerId $fileName ${ms}ms")
        while (fetchLogEntries.size > 200) fetchLogEntries.pollLast()
    }

    // ---- public API ----

    /** catalog.js's `catalog`/`genres` arrays as `{"catalog":[…],"genres":[…]}`. */
    suspend fun catalogJson(
        providerDir: File,
        providerId: String,
        value: String,
    ): String? {
        val payload = run(
            providerDir = providerDir,
            providerId = providerId,
            value = value,
            fileName = "catalog",
            withCheerio = false,
            budgetMs = 15_000L,
            callScript = "__vegDone(globalThis.__vegaCatalogJson());",
        )
        // `__vegaCatalogJson` answers the raw JSON (not the {ok,data} envelope).
        return payload.takeIf { it.startsWith("{") && it.contains("\"catalog\"") }
    }

    /** Calls one exported function from [fileName] with the given JSON args. */
    suspend fun call(
        providerDir: File,
        providerId: String,
        value: String,
        fileName: String,
        fnName: String,
        argsJson: String,
        withCheerio: Boolean = true,
        catalogBudget: Boolean = false,
    ): String = run(
        providerDir = providerDir,
        providerId = providerId,
        value = value,
        fileName = fileName,
        withCheerio = withCheerio,
        budgetMs = if (catalogBudget) CATALOG_TIMEOUT_MS else CALL_TIMEOUT_MS,
        callScript = "__vegaCall(${quote(fnName)}, ${quote(argsJson)});",
    )

    /** Calls [fnName] once per object in [argsArrayJson], sequentially. */
    suspend fun callMany(
        providerDir: File,
        providerId: String,
        value: String,
        fileName: String,
        fnName: String,
        argsArrayJson: String,
    ): String = run(
        providerDir = providerDir,
        providerId = providerId,
        value = value,
        fileName = fileName,
        withCheerio = true,
        budgetMs = CATALOG_TIMEOUT_MS,
        callScript = "__vegaCallMany(${quote(fnName)}, ${quote(argsArrayJson)});",
    )

    /**
     * True when the provider's files load and it exports something usable.
     * Starts with "OK"; "NO" means "not a Vega provider"; "ERR:…" carries a
     * detail message.
     */
    suspend fun validate(context: Context, providerDir: File, providerId: String, value: String): String =
        withContext(Dispatchers.Default) {
            val probe = listOf("stream" to "getStream", "posts" to "getPosts", "meta" to "getMeta")
                .firstOrNull { File(providerDir, "${it.first}.js").exists() }
                ?: return@withContext "NO"
            val source = runCatching { File(providerDir, "${probe.first}.js").readText() }.getOrNull()
                ?: return@withContext "NO"
            var qjs: QuickJs? = null
            try {
                val deferred = CompletableDeferred<String>()
                qjs = createEngine(
                    deferred,
                    Kv(File(providerDir, "kv.json")),
                    value,
                    withCheerio = false,
                    inFlight = java.util.concurrent.atomic.AtomicInteger(0),
                )
                qjs.evaluationTimeoutMillis = VALIDATE_TIMEOUT_MS
                qjs.loadModule("validate/$providerId/${probe.first}", probe.first, source)
                val verdict = qjs.evaluate<Any?>(
                    "(function () { var ex = globalThis.__vegaExports || {};" +
                        " if (typeof ex[${quote(probe.second)}] === 'function') return 'OK';" +
                        " if (ex['default'] && typeof ex['default'][${quote(probe.second)}] === 'function') return 'OK';" +
                        " return 'NO'; })();",
                    "vega-validate.js",
                    false,
                )?.toString()?.trim()
                verdict?.takeIf { it.isNotBlank() } ?: "NO"
            } catch (e: Throwable) {
                "ERR: ${e.message ?: e.javaClass.simpleName}".take(300)
            } finally {
                runCatching { qjs?.close() }
            }
        }

    // ---- fetch bridge ----

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

    /** One provider fetch, AWAITED rather than blocked, so parallel requests
     *  inside one provider are really parallel and a cancellation reaches the
     *  socket. */
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
            return failureJson(url, t.message ?: "bad request")
        }
        val fetched = withTimeoutOrNull(FETCH_TIMEOUT_MS) { executeFetch(request, followRedirects) }
        if (fetched == null) {
            fetchLogLine(hostOf(url), m, "TIMEOUT", 0, System.currentTimeMillis() - started, " fetch did not finish in ${FETCH_TIMEOUT_MS / 1000}s")
            return failureJson(url, "fetch timed out")
        }
        return when (fetched) {
            is Fetched.Ok -> okFetchJson(url, m, started, fetched)
            is Fetched.Failure -> {
                fetchLogLine(hostOf(url), m, "ERR", 0, System.currentTimeMillis() - started, " " + fetched.reason)
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
                        if (!cont.isCancelled) cont.resume(Fetched.Failure(e.message ?: e.javaClass.simpleName))
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

    private fun lowerHeaders(headers: okhttp3.Headers): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (name in headers.names()) {
            val key = name.lowercase()
            if (out.containsKey(key)) continue
            out[key] = headers.values(name).joinToString(", ")
        }
        return out
    }

    private fun buildFetchRequest(
        url: String,
        method: String,
        headersJson: String,
        body: String,
    ): Request {
        val builder = Request.Builder().url(url).header("User-Agent", com.hikari.app.net.Http.UA)
        val h = runCatching { JSONObject(headersJson) }.getOrNull()
        if (h != null) {
            h.keys().forEach { k ->
                // OkHttp only decompresses gzip/br transparently when the REQUEST
                // carries no Accept-Encoding of its own — forwarding the
                // provider's "gzip, deflate, br" handed JS raw compressed bytes
                // decoded as UTF-8 (same fix as the nuvio bridge).
                if (k.equals("Accept-Encoding", ignoreCase = true)) return@forEach
                val v = runCatching { h.getString(k) }.getOrNull() ?: return@forEach
                if (v.isBlank()) return@forEach
                runCatching { builder.header(k, v) }
            }
        }
        if (body.isNotEmpty() && (method == "POST" || method == "PUT" || method == "PATCH")) {
            val type = if (h != null && h.has("Content-Type")) h.getString("Content-Type")
            else "application/x-www-form-urlencoded; charset=utf-8"
            builder.method(method, okhttp3.RequestBody.create(type.toMediaTypeOrNull(), body))
        } else {
            builder.method(if (method == "HEAD") "HEAD" else "GET", null)
        }
        return builder.build()
    }

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
        val charset = runCatching {
            val enc = (ok.headers["content-type"] ?: "").substringAfter("charset=", "").trim().trim('"')
            if (enc.isEmpty()) Charsets.UTF_8 else Charset.forName(enc)
        }.getOrNull() ?: Charsets.UTF_8
        out.put("body", String(ok.bytes, charset))
        out.put("bodyBase64", android.util.Base64.encodeToString(ok.bytes, android.util.Base64.NO_WRAP))
        out.put("ms", System.currentTimeMillis() - started)
        return out.toString()
    }

    private fun failureJson(url: String, reason: String): String =
        "{\"ok\":false,\"status\":0,\"statusText\":" + quote(reason) +
            ",\"url\":" + quote(url) +
            ",\"headers\":{},\"body\":\"\",\"bodyBase64\":\"\"}"

    fun bridgeFetch(
        url: String,
        method: String,
        headersJson: String,
        body: String,
        followRedirects: Boolean,
    ): String = runBlocking { bridgeFetchAsync(url, method, headersJson, body, followRedirects) }

    private fun fetchLogLine(host: String, m: String, status: String, bytes: Int, ms: Long, extra: String) {
        if (fetchLogEntries.size > 200) return
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        fetchLogEntries.addFirst("$ts $m $host -> $status ${bytes}b ${ms}ms$extra")
        while (fetchLogEntries.size > 200) fetchLogEntries.pollLast()
    }

    private fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host ?: url.take(48) }.getOrDefault(url.take(48))
}
