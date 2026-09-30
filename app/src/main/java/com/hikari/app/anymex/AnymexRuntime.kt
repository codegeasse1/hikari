package com.hikari.app.anymex

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
 * Runs a Mangayomi-format JavaScript extension (Anymex / Mangayomi /
 * Dartotsu — one script defining `mangayomiSources` + a `DefaultExtension
 * extends MProvider` class) inside a fresh embedded QuickJS engine.
 *
 * The script reads the network through `Client` (backed by the same
 * asynchronous OkHttp bridge the other runtimes use), parses pages through
 * `Document` (backed by the cheerio bundle), keeps settings in
 * `SharedPreferences` (persisted per provider to kv.json) and uses the
 * Kotlin-style `String.substringAfter/substringBefore` helpers mangayomi
 * defines — all recreated in assets/anymex/harness.js. Loaded underneath is
 * nuvio's harness (global `fetch`, `Buffer`) because `Client` is implemented
 * on top of it.
 *
 * ONE MODULE PER CALL: the file is evaluated at top level (the class must
 * land in global scope), the call script invokes one `DefaultExtension`
 * method, and the engine is closed afterwards. The module source is
 * bytecode-cached per provider+content so only the first call pays the parse
 * cost. Dart extensions (`.dart` sources) cannot run here at all — they are
 * listed but never installed (see AnymexPluginManager).
 */
object AnymexRuntime {

    private const val MAX_CONCURRENT = 8
    private const val PERF_CONCURRENT = 4
    private const val TV_CONCURRENT = 2
    private const val FETCH_TIMEOUT_MS = 30_000L
    private const val CALL_TIMEOUT_MS = 60_000L
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
    private val cheerioJs: String by lazy { readAsset("nuvio/cheerio.js") }
    private val nuvioHarnessJs: String by lazy { readAsset("nuvio/harness.js") }
    private val anymexHarnessJs: String by lazy { readAsset("anymex/harness.js") }

    private val bytecodeCache = ConcurrentHashMap<String, ByteArray>()

    private class Kv(private val file: File) {
        val obj: JSONObject = runCatching {
            JSONObject(file.takeIf { it.exists() }?.readText() ?: "{}")
        }.getOrDefault(JSONObject())

        var changed = false

        fun json(): String = obj.toString()

        fun set(key: String, rawJson: String) {
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

    private suspend fun createEngine(
        deferred: CompletableDeferred<String>,
        kv: Kv,
        inFlight: AtomicInteger,
        hostMetaJson: String,
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
        qjs.function("__anymexDone") { args ->
            val payload = args.getOrNull(0)?.toString() ?: ""
            deferred.complete(payload)
            ""
        }
        qjs.function("__anymexKvSet") { args ->
            val key = args.getOrNull(0)?.toString() ?: ""
            val json = args.getOrNull(1)?.toString() ?: ""
            if (key.isNotEmpty()) kv.set(key, json)
            ""
        }
        qjs.function("__anymexLog") { args ->
            val msg = args.getOrNull(0)?.toString() ?: ""
            android.util.Log.d("Anymex", msg)
            ""
        }
        qjs.asyncFunction("__anymexExtract") { args ->
            val url = args.getOrNull(0)?.toString() ?: ""
            val quality = args.getOrNull(1)?.toString() ?: ""
            inFlight.incrementAndGet()
            try {
                bridgeExtractAsync(url, quality)
            } finally {
                inFlight.decrementAndGet()
            }
        }

        qjs.evaluateCached("boot.js", bootJs)
        qjs.evaluateCached(
            "cheerio-head.js",
            "var __nuvioModule = { exports: {} }; var module = __nuvioModule; var exports = module.exports;",
        )
        qjs.evaluateCached("cheerio.js", cheerioJs)
        qjs.evaluateCached("cheerio-tail.js", "globalThis.__nuvioCheerio = module.exports;")
        qjs.evaluateCached("nuvio-harness.js", nuvioHarnessJs)
        qjs.evaluate<Any?>(
            "globalThis.__nuvioFetchImpl = function (url, method, headersJson, body, followRedirects) {" +
                "  return globalThis.__hikariFetch(String(url), String(method || 'GET'), headersJson || '{}', body == null ? '' : String(body), followRedirects !== false);" +
                "};" +
                "globalThis.__nuvioBridgeStub = {" +
                "  onGetStreamsDone: function () {}," +
                "  onSettingsDone: function () {}," +
                "  fetch: null," +
                "  log: function (m) { if (typeof globalThis.__anymexLog === 'function') globalThis.__anymexLog(String(m)); }" +
                "};" +
                "globalThis.__anymexKvJson = ${quote(kv.json())};" +
                "globalThis.__anymexHostMeta = ${quote(hostMetaJson)};",
            "anymex-register.js",
            false,
        )
        qjs.evaluateCached("anymex-harness.js", anymexHarnessJs)
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
            return "{\"ok\":false,\"error\":${quote("this extension has no script file — reinstall it")}}"
        }
        val source = runCatching { moduleFile.readText() }.getOrNull()
        if (source.isNullOrBlank()) {
            return "{\"ok\":false,\"error\":${quote("extension script is empty — reinstall it")}}"
        }
        val kv = Kv(File(moduleFile.parentFile, "kv.json"))
        val hostMetaJson = hostMetaOf(moduleFile)
        val inFlight = AtomicInteger(0)
        return gate.withPermit {
            withTimeoutOrNull(budgetMs + CALL_GRACE_MS) {
                withContext(Dispatchers.Default) {
                    val deferred = CompletableDeferred<String>()
                    var qjs: QuickJs? = null
                    try {
                        qjs = createEngine(deferred, kv, inFlight, hostMetaJson)
                        qjs.evaluateCached(
                            "anymex/v2/$providerId/${source.hashCode()}",
                            source,
                        )
                        seedPrefs(qjs, kv)
                        qjs.evaluate<Any?>(
                            "__anymexCall(${quote(fnName)}, ${quote(argsJson)});\n;void 0;",
                            "anymex-call.js",
                            false,
                        )
                        val deadline = System.currentTimeMillis() + budgetMs
                        var idleRounds = 0
                        var idleBail = false
                        while (!deferred.isCompleted && System.currentTimeMillis() < deadline) {
                            val next = qjs.evaluate<Any?>("__anymexFireTimer()", "anymex-timer.js", false)
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
                            "{\"ok\":false,\"error\":${quote("the extension stopped responding before it could finish")}}"
                        } else {
                            "{\"ok\":false,\"error\":${quote("timed out after ${budgetMs / 1000}s")}}"
                        }
                    } catch (e: Throwable) {
                        if (deferred.isCompleted) deferred.await()
                        else "{\"ok\":false,\"error\":${quote(e.message ?: e.javaClass.simpleName)}}"
                    } finally {
                        runCatching { qjs?.close() }
                        kv.save()
                    }
                }
            } ?: "{\"ok\":false,\"error\":${quote("timed out after ${budgetMs / 1000}s")}}"
        }
    }

    // ---- public API ----

    suspend fun popular(moduleFile: File, providerId: String, page: Int): String =
        run(moduleFile, providerId, "getPopular", "[$page]", CATALOG_TIMEOUT_MS)

    suspend fun latest(moduleFile: File, providerId: String, page: Int): String =
        run(moduleFile, providerId, "getLatestUpdates", "[$page]", CATALOG_TIMEOUT_MS)

    suspend fun search(moduleFile: File, providerId: String, query: String, page: Int): String =
        run(moduleFile, providerId, "search", "[${quote(query)}, $page, []]", CATALOG_TIMEOUT_MS)

    suspend fun detail(moduleFile: File, providerId: String, url: String): String =
        run(moduleFile, providerId, "getDetail", "[${quote(url)}]", CATALOG_TIMEOUT_MS)

    suspend fun videos(moduleFile: File, providerId: String, url: String): String =
        run(moduleFile, providerId, "getVideoList", "[${quote(url)}]", CALL_TIMEOUT_MS)

    suspend fun pages(moduleFile: File, providerId: String, url: String): String =
        run(moduleFile, providerId, "getPageList", "[${quote(url)}]", CALL_TIMEOUT_MS)

    data class Inspection(val ok: Boolean, val isManga: Boolean, val name: String)

    suspend fun inspect(context: Context, source: String, hostMetaJson: String = "{}"): Inspection =
        withContext(Dispatchers.Default) {
            var qjs: QuickJs? = null
            try {
                val deferred = CompletableDeferred<String>()
                qjs = createEngine(deferred, Kv(File(context.cacheDir, "anymex-inspect-kv.json")), AtomicInteger(0), hostMetaJson)
                qjs.evaluationTimeoutMillis = VALIDATE_TIMEOUT_MS
                qjs.evaluate<Any?>(source, "anymex-inspect-src.js", false)
                val meta = qjs.evaluate<Any?>(
                    "(function () {" +
                        " try {" +
                        "  if (typeof globalThis.DefaultExtension !== 'function') return JSON.stringify({ ok: false, error: 'no DefaultExtension' });" +
                        "  var s = null;" +
                        "  try {" +
                        "    if (Array.isArray(globalThis.mangayomiSources) && globalThis.mangayomiSources.length) s = globalThis.mangayomiSources[0];" +
                        "  } catch (e) {}" +
                        "  if (!s) {" +
                        "    try {" +
                        "      var keys = Object.getOwnPropertyNames(globalThis);" +
                        "      for (var i = 0; i < keys.length; i++) {" +
                        "        var v = globalThis[keys[i]];" +
                        "        if (Array.isArray(v) && v.length && v[0] && typeof v[0] === 'object' && (v[0].baseUrl || v[0].name)) { s = v[0]; break; }" +
                        "      }" +
                        "    } catch (e2) {}" +
                        "  }" +
                        "  if (!s) { try { s = JSON.parse(globalThis.__anymexHostMeta || '{}'); } catch (e3) { s = {}; } }" +
                        "  var hasVideo = false, hasPages = false, hasPopular = false;" +
                        "  try {" +
                        "    var ext = globalThis.__anymexExtGet && globalThis.__anymexExtGet();" +
                        "    if (ext) {" +
                        "      hasVideo = typeof ext.getVideoList === 'function';" +
                        "      hasPages = typeof ext.getPageList === 'function';" +
                        "      hasPopular = typeof ext.getPopular === 'function';" +
                        "    } else {" +
                        "      hasPopular = true;" +
                        "    }" +
                        "  } catch (e4) { hasPopular = true; }" +
                        "  if (!hasVideo && !hasPages && !hasPopular) return JSON.stringify({ ok: false, error: 'no catalog/video/pages' });" +
                        "  return JSON.stringify({ ok: true, isManga: (!hasVideo && hasPages), name: (s && s.name) || '' });" +
                        " } catch (e) { return JSON.stringify({ ok: false, error: String(e && e.message || e) }); } })();",
                    "anymex-inspect.js",
                    false,
                )?.toString()
                val o = runCatching { JSONObject(meta ?: "") }.getOrNull()
                if (o != null && o.optBoolean("ok", false)) {
                    Inspection(true, o.optBoolean("isManga", false), o.optString("name"))
                } else {
                    Inspection(false, false, "")
                }
            } catch (e: Throwable) {
                Inspection(false, false, "")
            } finally {
                runCatching { qjs?.close() }
            }
        }

    private fun hostMetaOf(moduleFile: File): String {
        val meta = runCatching {
            JSONObject(moduleFile.parentFile?.let { File(it, "meta.json") }?.takeIf { it.exists() }?.readText() ?: "{}")
        }.getOrNull() ?: JSONObject()
        val out = JSONObject()
        out.put("name", meta.optString("name"))
        out.put("baseUrl", meta.optString("baseUrl"))
        out.put("apiUrl", meta.optString("apiUrl"))
        val lang = meta.optString("lang").ifBlank { "en" }
        out.put("lang", lang)
        out.put("iconUrl", meta.optString("iconUrl"))
        return out.toString()
    }

    private suspend fun seedPrefs(qjs: QuickJs, kv: Kv) {
        if (kv.obj.has("__prefsSeeded")) return
        val raw = runCatching {
            qjs.evaluate<Any?>(
                "(function () {" +
                    " try {" +
                    "  var ext = globalThis.__anymexExtGet();" +
                    "  if (!ext || typeof ext.getSourcePreferences !== 'function') return '[]';" +
                    "  var p = ext.getSourcePreferences();" +
                    "  if (p && typeof p.then === 'function') return '[]';" +
                    "  return JSON.stringify(p || []);" +
                    " } catch (e) { return '[]'; } })();",
                "anymex-prefs.js",
                false,
            )?.toString()
        }.getOrNull() ?: "[]"
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val key = o.optString("key").trim()
                if (key.isEmpty() || key == "__prefsSeeded" || kv.obj.has(key)) continue
                prefDefault(o)?.let { kv.set(key, it) }
            }
        }
        kv.set("__prefsSeeded", "1")
        kv.save()
    }

    private fun prefDefault(o: JSONObject): String? {
        o.optJSONObject("listPreference")?.let { lp ->
            val values = lp.optJSONArray("entryValues") ?: return@let
            if (values.length() == 0) return@let
            val idx = lp.optInt("valueIndex", 0).coerceIn(0, values.length() - 1)
            return JSONObject.quote(values.optString(idx))
        }
        o.optJSONObject("editTextPreference")?.let { ep ->
            val v = ep.optString("defaultValue").ifBlank { ep.optString("value") }
            return JSONObject.quote(v)
        }
        o.optJSONObject("multiSelectListPreference")?.let { mp ->
            val vals = mp.optJSONArray("values")
            if (vals != null) return vals.toString()
            return "[]"
        }
        o.optJSONObject("switchPreferenceCompat")?.let { sp ->
            if (sp.has("default")) return sp.optBoolean("default", false).toString()
            if (sp.has("value")) return sp.optBoolean("value", false).toString()
        }
        o.optJSONObject("checkBoxPreference")?.let { cp ->
            if (cp.has("default")) return cp.optBoolean("default", false).toString()
        }
        return null
    }

    private suspend fun bridgeExtractAsync(url: String, quality: String): String {
        val u = url.trim()
        if (u.isEmpty()) return "[]"
        val found = withTimeoutOrNull(45_000) {
            withContext(Dispatchers.IO) {
                runCatching { com.hikari.app.cs3.FallbackResolver.resolve(u) }.getOrNull()
            }
        }.orEmpty()
        val arr = JSONArray()
        for (s in found) {
            val link = s.url.trim()
            if (link.isEmpty()) continue
            val o = JSONObject()
            o.put("url", link)
            o.put("originalUrl", link)
            o.put("quality", quality.ifBlank { s.name.ifBlank { "Origin" } })
            val hdrs = JSONObject()
            s.headers.forEach { (k, v) -> runCatching { hdrs.put(k, v) } }
            o.put("headers", hdrs)
            arr.put(o)
        }
        return arr.toString()
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
            is Fetched.Ok -> okFetchJson(url, fetched)
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

    private fun okFetchJson(url: String, ok: Fetched.Ok): String {
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
}
