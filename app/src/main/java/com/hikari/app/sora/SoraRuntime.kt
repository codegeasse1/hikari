package com.hikari.app.sora

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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Runs a Sora module (Sora / Luna / Anymex / Dartotsu / … — one
 * self-contained script with global `searchResults` / `extractDetails` /
 * `extractEpisodes` / `extractStreamUrl` entry points) inside a fresh
 * embedded QuickJS engine.
 *
 * The module reads the network through the spec's `fetchv2` bridge (plus a
 * best-effort `networkFetch` page capture — see assets/sora/harness.js),
 * which is served here by the same asynchronous OkHttp bridge the nuvio and
 * Vega runtimes use, so parallel requests inside one module are really
 * parallel. Loaded underneath is nuvio's harness (global `fetch`, `Buffer`)
 * because `fetchv2` is implemented on top of it.
 *
 * ONE MODULE PER CALL: the file is evaluated at top level (the spec forbids
 * IIFE wrapping, and the entry points must land in global scope), the call
 * script invokes one entry point, and the engine is closed afterwards. The
 * module source is bytecode-cached per provider+content so only the first
 * call pays the parse cost.
 */
object SoraRuntime {

    private const val MAX_CONCURRENT = 8
    private const val PERF_CONCURRENT = 4
    private const val TV_CONCURRENT = 2
    private const val FETCH_TIMEOUT_MS = 30_000L
    private const val CALL_TIMEOUT_MS = 60_000L
    /** Stream extraction must answer fast — Anymex-app users expect ~3–4s. */
    private const val STREAM_TIMEOUT_MS = 18_000L
    private const val CATALOG_TIMEOUT_MS = 75_000L
    private const val VALIDATE_TIMEOUT_MS = 20_000L
    private const val CALL_GRACE_MS = 20_000L
    private const val TIMER_MAX_WAIT_MS = 1_500L
    private const val ENGINE_MEMORY_LIMIT = 256L * 1024 * 1024

    private val engineMemoryLimit: Long
        get() = com.hikari.app.data.PerfMode.tvEngineMemoryLimit ?: ENGINE_MEMORY_LIMIT

    private val concurrency = Semaphore(MAX_CONCURRENT)
    private val perfConcurrency = Semaphore(PERF_CONCURRENT)
    private val tvConcurrency = Semaphore(TV_CONCURRENT)

    private val gate: Semaphore
        get() = when {
            com.hikari.app.data.PerfMode.tvDevice -> tvConcurrency
            com.hikari.app.data.PerfMode.active -> perfConcurrency
            else -> concurrency
        }

    private val bootJs: String by lazy { readAsset("nuvio/boot.js") }
    private val nuvioHarnessJs: String by lazy { readAsset("nuvio/harness.js") }
    private val soraHarnessJs: String by lazy { readAsset("sora/harness.js") }

    private val bytecodeCache = ConcurrentHashMap<String, ByteArray>()

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

    private suspend fun createEngine(
        deferred: CompletableDeferred<String>,
        inFlight: AtomicInteger,
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
            inFlight.incrementAndGet()
            try {
                bridgeFetchAsync(url, method, headersJson, body, followRedirects)
            } finally {
                inFlight.decrementAndGet()
            }
        }
        qjs.function("__soraDone") { args ->
            val payload = args.getOrNull(0)?.toString() ?: ""
            deferred.complete(payload)
            ""
        }
        qjs.function("__soraLog") { args ->
            val msg = args.getOrNull(0)?.toString() ?: ""
            android.util.Log.d("Sora", msg)
            ""
        }

        qjs.evaluateCached("boot.js", bootJs)
        qjs.evaluateCached("nuvio-harness.js", nuvioHarnessJs)
        qjs.evaluate<Any?>(
            "globalThis.__nuvioFetchImpl = function (url, method, headersJson, body, followRedirects) {" +
                "  return globalThis.__hikariFetch(String(url), String(method || 'GET'), headersJson || '{}', body == null ? '' : String(body), followRedirects !== false);" +
                "};" +
                "globalThis.__nuvioBridgeStub = {" +
                "  onGetStreamsDone: function () {}," +
                "  onSettingsDone: function () {}," +
                "  fetch: null," +
                "  log: function (m) { if (typeof globalThis.__soraLog === 'function') globalThis.__soraLog(String(m)); }" +
                "};",
            "sora-register.js",
            false,
        )
        qjs.evaluateCached("sora-harness.js", soraHarnessJs)
        return qjs
    }

    private suspend fun QuickJs.evaluateCached(name: String, source: String) {
        if (source.isBlank()) return
        val compiled = bytecodeCache[name]
            ?: runCatching { compile(source, name, false) }
                .getOrNull()
                ?.also { bytecodeCache[name] = it }
        if (compiled != null && runCatching { evaluate<Any?>(compiled) }.isSuccess) return
        evaluate<Any?>(source, name, false)
    }

    private suspend fun run(
        moduleFile: File,
        providerId: String,
        fnName: String,
        argsJson: String,
        budgetMs: Long,
    ): String {
        if (!moduleFile.exists()) {
            return "{\"ok\":false,\"error\":${quote("this module has no script file — reinstall it")}}"
        }
        val source = runCatching { moduleFile.readText() }.getOrNull()
        if (source.isNullOrBlank()) {
            return "{\"ok\":false,\"error\":${quote("module script is empty — reinstall it")}}"
        }
        val started = System.currentTimeMillis()
        val inFlight = AtomicInteger(0)
        return gate.withPermit {
            withTimeoutOrNull(budgetMs + CALL_GRACE_MS) {
                withContext(Dispatchers.Default) {
                    val deferred = CompletableDeferred<String>()
                    var qjs: QuickJs? = null
                    try {
                        qjs = createEngine(deferred, inFlight)
                        runCatching {
                            val settingsJson = readModuleSettings(moduleFile).toString()
                            qjs.evaluate<Any?>(
                                "globalThis.__soraSettings = " + JSONObject.quote(settingsJson) + ";" +
                                    "globalThis.getSetting = function (k, d) { try { var o = JSON.parse(globalThis.__soraSettings || '{}'); var v = o[k]; return (v === undefined || v === null) ? d : v; } catch (e) { return d; } };",
                                "sora-settings.js",
                                false,
                            )
                        }
                        qjs.evaluateCached(
                            "sora/v2/$providerId/${source.hashCode()}",
                            source,
                        )
                        qjs.evaluate<Any?>(
                            "__soraCall(${quote(fnName)}, ${quote(argsJson)});\n;void 0;",
                            "sora-call.js",
                            false,
                        )
                        val deadline = System.currentTimeMillis() + budgetMs
                        var idleRounds = 0
                        var idleBail = false
                        while (!deferred.isCompleted && System.currentTimeMillis() < deadline) {
                            val next = qjs.evaluate<Any?>("__soraFireTimer()", "sora-timer.js", false)
                            if (deferred.isCompleted) break
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
                            "{\"ok\":false,\"error\":${quote("the module stopped responding before it could finish")}}"
                        } else {
                            "{\"ok\":false,\"error\":${quote("timed out after ${budgetMs / 1000}s")}}"
                        }
                    } catch (e: Throwable) {
                        if (deferred.isCompleted) deferred.await()
                        else "{\"ok\":false,\"error\":${quote(e.message ?: e.javaClass.simpleName)}}"
                    } finally {
                        runCatching { qjs?.close() }
                    }
                }
            } ?: "{\"ok\":false,\"error\":${quote("timed out after ${budgetMs / 1000}s")}}"
        }
    }

    // ---- public API ----

    suspend fun search(moduleFile: File, providerId: String, keyword: String): String =
        run(moduleFile, providerId, "searchResults", JSONObject.quote(keyword).let { "[$it]" }, CATALOG_TIMEOUT_MS)

    suspend fun details(moduleFile: File, providerId: String, url: String): String =
        run(moduleFile, providerId, "extractDetails", "[${quote(url)}]", CATALOG_TIMEOUT_MS)

    suspend fun episodes(moduleFile: File, providerId: String, url: String): String =
        run(moduleFile, providerId, "extractEpisodes", "[${quote(url)}]", CATALOG_TIMEOUT_MS)

    suspend fun streams(moduleFile: File, providerId: String, url: String): String =
        run(moduleFile, providerId, "extractStreamUrl", "[${quote(url)}]", STREAM_TIMEOUT_MS)

    /**
     * True when the script is a Sora video module: it exports the four anime
     * entry points. Starts with "OK"; "NO" means "not a Sora module";
     * "ERR:…" carries a detail message.
     */
    suspend fun moduleSettings(moduleFile: File): String = withContext(Dispatchers.Default) {
        if (!moduleFile.exists()) return@withContext "[]"
        val source = runCatching { moduleFile.readText() }.getOrNull()
        if (source.isNullOrBlank()) return@withContext "[]"
        var qjs: QuickJs? = null
        try {
            val deferred = CompletableDeferred<String>()
            qjs = createEngine(deferred, AtomicInteger(0))
            qjs.evaluationTimeoutMillis = VALIDATE_TIMEOUT_MS
            qjs.evaluate<Any?>(source, "sora-settings-src.js", false)
            val raw = qjs.evaluate<Any?>(
                "(function () {" +
                    " try {" +
                    "  if (typeof globalThis.getSettings === 'function') return JSON.stringify(globalThis.getSettings() || []);" +
                    "  if (globalThis.settings && typeof globalThis.settings === 'object') return JSON.stringify(globalThis.settings);" +
                    "  return '[]';" +
                    " } catch (e) { return '[]'; } })();",
                "sora-settings-list.js",
                false,
            )?.toString() ?: "[]"
            normalizeSettings(raw, moduleFile)
        } catch (e: Throwable) {
            "[]"
        } finally {
            runCatching { qjs?.close() }
        }
    }

    private fun normalizeSettings(raw: String, moduleFile: File): String {
        val stored = readModuleSettings(moduleFile)
        val arr = runCatching { JSONArray(raw) }.getOrNull()
        if (arr != null) {
            val out = JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val key = o.optString("key").trim()
                if (key.isEmpty()) continue
                val d = JSONObject(o.toString())
                if (d.optString("type").isBlank()) d.put("type", "edit_text")
                applyStored(d, key, stored)
                out.put(d)
            }
            return out.toString()
        }
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return "[]"
        val out = JSONArray()
        val keyIter = obj.keys()
        while (keyIter.hasNext()) {
            val key = keyIter.next()
            val v = obj.opt(key) ?: continue
            val d = if (v is JSONObject) JSONObject(v.toString()) else JSONObject()
            d.put("key", key)
            if (d.optString("type").isBlank()) {
                val kind = if (v is Boolean) "switch" else if (v is JSONArray) "multi_select" else "edit_text"
                d.put("type", kind)
            }
            applyStored(d, key, stored)
            out.put(d)
        }
        out.toString()
    }

    private fun applyStored(d: JSONObject, key: String, stored: JSONObject) {
        if (!stored.has(key)) return
        when (d.optString("type")) {
            "switch", "checkBox" -> d.put("value", stored.optBoolean(key, d.optBoolean("value", false)))
            "multi_select" -> d.put("values", stored.optJSONArray(key) ?: d.optJSONArray("values") ?: JSONArray())
            else -> d.put("value", stored.optString(key, d.optString("value")))
        }
    }

    fun readModuleSettings(moduleFile: File): JSONObject = runCatching {
        JSONObject(File(moduleFile.parentFile, "kv.json").takeIf { it.exists() }?.readText() ?: "{}")
    }.getOrDefault(JSONObject())

    fun setModuleSetting(moduleFile: File, key: String, rawJson: String) {
        if (key.isBlank()) return
        val file = File(moduleFile.parentFile, "kv.json")
        val obj = readModuleSettings(moduleFile)
        runCatching {
            if (rawJson.isEmpty()) obj.remove(key)
            else obj.put(key, org.json.JSONTokener(rawJson).nextValue())
        }
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(obj.toString())
        }
    }

    fun warm(moduleFile: File, providerId: String) {
        val src = runCatching { moduleFile.takeIf { it.exists() }?.readText() }.getOrNull()
        if (src.isNullOrBlank()) return
        val key = "sora/v2/" + providerId + "/" + src.hashCode()
        if (bytecodeCache.containsKey(key)) return
        runCatching {
            val qjs = QuickJs.create(jobDispatcher = Dispatchers.Default)
            try {
                qjs.evaluationTimeoutMillis = VALIDATE_TIMEOUT_MS
                runCatching { qjs.compile(src, key, false) }.getOrNull()?.also { bytecodeCache[key] = it }
            } finally {
                runCatching { qjs.close() }
            }
        }
    }

    suspend fun validate(context: Context, source: String): String =
        withContext(Dispatchers.Default) {
            var qjs: QuickJs? = null
            try {
                val deferred = CompletableDeferred<String>()
                qjs = createEngine(deferred, AtomicInteger(0))
                qjs.evaluationTimeoutMillis = VALIDATE_TIMEOUT_MS
                qjs.evaluate<Any?>(source, "sora-validate-src.js", false)
                val verdict = qjs.evaluate<Any?>(
                    "(function () {" +
                        " var f = ['searchResults','extractDetails','extractEpisodes','extractStreamUrl'];" +
                        " for (var i = 0; i < f.length; i++) {" +
                        "  if (typeof globalThis[f[i]] !== 'function') return 'NO'; }" +
                        " return 'OK'; })();",
                    "sora-validate.js",
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
            return failureJson(url, "fetch timed out")
        }
        return when (fetched) {
            is Fetched.Ok -> okFetchJson(url, m, started, fetched)
            is Fetched.Failure -> failureJson(url, fetched.reason)
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

    private fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host ?: url.take(48) }.getOrDefault(url.take(48))
}
