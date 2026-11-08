package com.hikari.app.skystream

import android.content.Context
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.function
import com.hikari.app.net.CloudflareVerifier
import com.hikari.app.net.DohDns
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

object SkyStreamRuntime {

    // Same reasoning as NuvioRuntime: each engine is a native VM plus a ~450KB
    // cheerio parse, so bound how many run at once and let the rest queue.
    private const val MAX_CONCURRENT = 6
    private const val TV_CONCURRENT = 3
    private const val FETCH_TIMEOUT_MS = 30_000L
    private const val CALL_TIMEOUT_MS = 90_000L
    private const val VALIDATE_TIMEOUT_MS = 20_000L
    private const val CATALOG_TIMEOUT_MS = 75_000L
    /** Room on top of a call's budget for the pump loop's own bookkeeping. */
    private const val CALL_GRACE_MS = 20_000L
    /** Budget for one plugin call: catalogs get the long one (see above). */
    private fun budgetFor(fnName: String): Long =
        if (fnName == "getHome" || fnName == "search") CATALOG_TIMEOUT_MS else CALL_TIMEOUT_MS

    private const val FETCH_THREADS = 24
    /** Budget for one legacy `loadExtractor` call (a plugin is blocked on it). */
    private const val EXTRACT_TIMEOUT_MS = 25_000L

    /** Blocks of timer-pumping between engine rounds; also caps how long one
     *  pending timer may be slept for (a plugin that asks for a 30s delay gets
     *  a capped wait, since a shorter one is indistinguishable for scraping). */
    private const val TIMER_MAX_WAIT_MS = 2_000L

    private const val DEFAULT_UA = com.hikari.app.net.Http.UA

    private val concurrency = Semaphore(MAX_CONCURRENT)

    /** [TV_CONCURRENT]'s pool — see the note on the constant, and on [gate]. */
    private val tvConcurrency = Semaphore(TV_CONCURRENT)

    private val gate: Semaphore
        get() = if (com.hikari.app.data.PerfMode.tvDevice) tvConcurrency else concurrency

    /** Ring buffer of every bridge fetch outcome, for the sources sheet's
     *  diagnostics (mirrors NuvioRuntime's fetch log). */
    private val fetchLogEntries = ConcurrentLinkedDeque<String>()

    private val bootJs: String by lazy { readAsset("nuvio/boot.js") }
    private val cheerioJs: String by lazy { readAsset("nuvio/cheerio.js") }
    private val shimJs: String by lazy { readAsset("skystream/shim.js") }

    private fun readAsset(path: String): String =
        com.hikari.app.HikariApp.instance.assets.open(path).bufferedReader().readText()

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

    // ---- Per-plugin preferences (filesDir/skystream/settings/<pkg>.json) ----

    fun settingsFile(pluginId: String): File {
        val safe = pluginId.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        return File(com.hikari.app.HikariApp.instance.filesDir, "skystream/settings/$safe.json")
    }

    /** One plugin call's preference map: read once when the engine boots, and
     *  written back only if a plugin actually called setPreference. */
    private class Prefs(val file: File) {
        val values = JSONObject()
        var dirty = false

        init {
            val stored = runCatching { JSONObject(file.readText()) }.getOrNull()
            if (stored != null) {
                stored.keys().forEach { k -> runCatching { values.put(k, stored.get(k)) } }
            }
        }

        fun get(key: String): String? {
            if (!values.has(key)) return null
            val v = values.opt(key) ?: return null
            return if (v == JSONObject.NULL) null else v.toString()
        }

        fun set(key: String, value: String) {
            runCatching { values.put(key, value) }
            dirty = true
        }

        fun save() {
            if (!dirty) return
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText(values.toString())
            }
        }
    }

    // ---- Engine plumbing ----

    private fun quote(s: String): String = JSONObject.quote(s)

    private suspend fun QuickJs.evaluateVoid(js: String, name: String) {
        evaluate<Any?>("$js\n;void 0;\n", name, false)
    }

    private val fetchExecutor: ExecutorService = Executors.newFixedThreadPool(FETCH_THREADS)

    private class AsyncFetches {
        /** `promiseId` → the JSON payload `__skyResolveFetch` hands back to JS. */
        val results = LinkedBlockingQueue<Pair<String, String>>()

        /** Requests handed to the pool that have not been queued yet. */
        val pending = AtomicInteger(0)
    }

    private val client by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .addInterceptor { chain -> com.hikari.app.net.CloudflareVerifier.intercept(chain) }
            // Some extension-repo hosts answer only via a public resolver on
            // some devices/ISPs — fall back to DNS-over-HTTPS (see DohDns).
            .dns(DohDns)
            .build()
    }

    private suspend fun createEngine(
        deferred: CompletableDeferred<String>,
        pluginId: String,
        manifestJson: String,
        source: String,
        prefs: Prefs,
        fetches: AsyncFetches,
        budgetMs: Long = CALL_TIMEOUT_MS,
    ): QuickJs {
        val qjs = QuickJs.create(jobDispatcher = Dispatchers.Default)
        qjs.evaluationTimeoutMillis = budgetMs
        com.hikari.app.data.PerfMode.tvEngineMemoryLimit?.let { qjs.memoryLimit = it }

        qjs.function("__hikariFetch") { args ->
            val url = args.getOrNull(0)?.toString() ?: ""
            val method = args.getOrNull(1)?.toString() ?: "GET"
            val headersJson = args.getOrNull(2)?.toString() ?: "{}"
            val body = args.getOrNull(3)?.toString() ?: ""
            val followRedirects = args.getOrNull(4) as? Boolean ?: true
            bridgeFetch(url, method, headersJson, body, followRedirects)
        }
        // The asynchronous bridge the shim prefers: fire the request on the pool,
        // return immediately, and let the pump loop in [invoke] resolve the
        // plugin's promise when the response lands — so `Promise.all` over several
        // http_get calls is genuinely concurrent (see [AsyncFetches]).
        qjs.function("__hikariFetchAsync") { args ->
            val url = args.getOrNull(0)?.toString() ?: ""
            val method = (args.getOrNull(1)?.toString() ?: "GET").uppercase()
            val headersJson = args.getOrNull(2)?.toString() ?: "{}"
            val body = args.getOrNull(3)?.toString() ?: ""
            val followRedirects = args.getOrNull(4) as? Boolean ?: true
            val promiseId = args.getOrNull(5)?.toString().orEmpty()
            if (promiseId.isNotEmpty()) {
                fetches.pending.incrementAndGet()
                fetchExecutor.submit {
                    val json = runCatching {
                        fetchOnce(url, method, headersJson, body, System.currentTimeMillis())
                    }.getOrElse { e ->
                        val o = JSONObject()
                        o.put("ok", false)
                        o.put("status", 0)
                        o.put("statusText", e.message ?: "network error")
                        o.put("url", url)
                        o.put("headers", JSONObject())
                        o.put("body", "")
                        o.put("error", e.message ?: "network error")
                        o.toString()
                    }
                    fetches.results.add(promiseId to json)
                    fetches.pending.decrementAndGet()
                }
            }
            ""
        }
        // http_parallel in one native call, so a plugin's batch really is
        // parallel (the single-fetch bridge blocks the engine thread).
        qjs.function("__hikariFetchMany") { args ->
            val requestsJson = args.getOrNull(0)?.toString() ?: "[]"
            bridgeFetchMany(requestsJson)
        }
        // Legacy `loadExtractor(url, cb)`: a plugin can hand an embed URL to the
        // app's own extraction stack (see FallbackResolver.resolveEmbedUrl).
        qjs.function("__hikariExtract") { args ->
            val url = args.getOrNull(0)?.toString() ?: ""
            val referer = args.getOrNull(1)?.toString()?.takeIf { it.isNotBlank() }
            extractOnce(url, referer)
        }
        qjs.function("__hikariDone") { args ->
            val payload = args.getOrNull(0)?.toString() ?: ""
            deferred.complete(payload)
            ""
        }
        qjs.function("__hikariLog") { args ->
            val msg = args.getOrNull(0)?.toString() ?: ""
            android.util.Log.d("SkyStream[$pluginId]", msg)
            ""
        }
        qjs.function("__hikariPrefGet") { args ->
            prefs.get(args.getOrNull(0)?.toString() ?: "") ?: ""
        }
        qjs.function("__hikariPrefSet") { args ->
            prefs.set(args.getOrNull(0)?.toString() ?: "", args.getOrNull(1)?.toString() ?: "")
            ""
        }

        // 1. Polyfills (console, TextEncoder/Decoder, Blob, URL, CryptoJS, …).
        qjs.evaluateVoid(bootJs, "boot.js")
        // 2. The cheerio bundle, captured as a CommonJS module exactly like the
        //    nuvio runtime does (the shim reads globalThis.__skyCheerio).
        qjs.evaluateVoid(
            "var __skyModule = { exports: {} }; var module = __skyModule; var exports = module.exports;",
            "cheerio-head.js",
        )
        qjs.evaluateVoid(cheerioJs, "cheerio.js")
        qjs.evaluateVoid("globalThis.__skyCheerio = module.exports;", "cheerio-tail.js")
        // 3. Timers: SkyStream's engine provides them, QuickJS does not.
        qjs.evaluateVoid(TIMER_JS, "skystream-timers.js")
        // 4. The SkyStream plugin surface.
        qjs.evaluateVoid(shimJs, "skystream-shim.js")
        qjs.evaluateVoid("globalThis.manifest = $manifestJson;", "manifest.js")
        qjs.evaluateVoid(source, "$pluginId.js")
        return qjs
    }

    // ---- Public API ----

    suspend fun invoke(
        context: Context,
        pluginId: String,
        scriptFile: File,
        fnName: String,
        argsJson: String,
        /** Extra manifest keys for this call, as a JSON object (sub-provider
         *  id — see SkyStreamPluginManager.subIdOf). Merged into the
         *  plugin.json payload before the engine sees it. */
        manifestExtra: String? = null,
    ): String {
        val source = runCatching { scriptFile.readText() }.getOrNull()
        if (source.isNullOrBlank()) return failure("plugin file missing — reinstall this extension")
        val manifestJson = readManifest(scriptFile, manifestExtra)
        val budget = budgetFor(fnName)
        return gate.withPermit {
            withTimeoutOrNull(budget + CALL_GRACE_MS) {
                withContext(Dispatchers.Default) {
                    val deferred = CompletableDeferred<String>()
                    val prefs = Prefs(settingsFile(pluginId))
                    val fetches = AsyncFetches()
                    var qjs: QuickJs? = null
                    try {
                        qjs = createEngine(deferred, pluginId, manifestJson, source, prefs, fetches, budget)
                        qjs.evaluate<Any?>(buildCall(fnName, argsJson), "call.js", false)
                        val deadline = System.currentTimeMillis() + budget
                        var idleRounds = 0
                        var idleBail = false
                        while (!deferred.isCompleted && System.currentTimeMillis() < deadline) {
                            val next = qjs.evaluate<Any?>("__skyFireTimer()", "timer.js", false)
                            deliverFetches(qjs, fetches)
                            if (deferred.isCompleted) break
                            val wait = (next as? Number)?.toLong() ?: -1L
                            val busy = fetches.pending.get() > 0 || fetches.results.isNotEmpty()
                            when {
                                busy -> {
                                    idleRounds = 0
                                    // Stay responsive while requests are in flight.
                                    delay(if (wait >= 1 && wait <= 50) wait else 8)
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
                                    if (idleRounds >= 4) {
                                        idleBail = true
                                        break
                                    }
                                    delay(12)
                                }
                            }
                        }
                        if (deferred.isCompleted) normalise(deferred.await())
                        else if (idleBail) failure("the extension stopped responding before it could finish")
                        else failure("timed out after ${budget / 1000}s")
                    } catch (e: Throwable) {
                        if (deferred.isCompleted) normalise(deferred.await())
                        else failure(e.message ?: e.javaClass.simpleName)
                    } finally {
                        runCatching { qjs?.close() }
                        prefs.save()
                    }
                }
            } ?: failure("timed out after ${budget / 1000}s")
        }
    }

    /** Hands every finished async fetch to its JS promise, one `evaluate` per
     *  response so QuickJS drains the microtask queue (and the plugin's `await`
     *  chain, including any follow-up requests) between deliveries. */
    private suspend fun deliverFetches(qjs: QuickJs, fetches: AsyncFetches) {
        while (true) {
            val item = fetches.results.poll() ?: break
            runCatching {
                qjs.evaluate<Any?>(
                    "__skyResolveFetch(${quote(item.first)}, ${quote(item.second)})",
                    "resolve.js",
                    false,
                )
            }
        }
    }

    suspend fun validate(context: Context, pluginId: String, scriptFile: File): String {
        val source = runCatching { scriptFile.readText() }.getOrNull()
        if (source.isNullOrBlank()) return "NO"
        val manifestJson = readManifest(scriptFile)
        return withContext(Dispatchers.Default) {
            var qjs: QuickJs? = null
            try {
                val deferred = CompletableDeferred<String>()
                val prefs = Prefs(settingsFile(pluginId))
                qjs = createEngine(deferred, pluginId, manifestJson, source, prefs, AsyncFetches())
                // The plugin's own top-level code is done by now; the export
                // check must be instant, so give QuickJS a tight budget here
                // (an install a user is watching waits on this call).
                qjs.evaluationTimeoutMillis = VALIDATE_TIMEOUT_MS
                val verdict = qjs.evaluate<String?>(
                    "(function () {" +
                        "  var names = ['getHome', 'search', 'load', 'loadStreams'];" +
                        "  var missing = names.filter(function (n) { return typeof globalThis[n] !== 'function'; });" +
                        "  if (missing.length) return 'NO';" +
                        "  return 'OK';" +
                        "})();",
                    "validate.js", false,
                )
                verdict?.trim()?.takeIf { it.isNotBlank() } ?: "NO"
            } catch (e: Throwable) {
                "ERR: ${e.message ?: e.javaClass.simpleName}".take(300)
            } finally {
                runCatching { qjs?.close() }
            }
        }
    }

    private fun readManifest(scriptFile: File, manifestExtra: String? = null): String {
        val dir = scriptFile.parentFile ?: return manifestExtra ?: "{}"
        val f = File(dir, "plugin.json")
        val text = runCatching { f.takeIf { it.exists() }?.readText() }.getOrNull()
        val obj = if (text.isNullOrBlank()) JSONObject()
        else runCatching { JSONObject(text) }.getOrNull() ?: JSONObject()
        val custom = runCatching {
            SkyStreamPluginManager.customBaseUrl(dir.name)
        }.getOrNull()?.takeIf { it.startsWith("http") }
        if (custom != null) runCatching { obj.put("baseUrl", custom) }
        if (!manifestExtra.isNullOrBlank()) {
            runCatching {
                val extra = JSONObject(manifestExtra)
                extra.keys().forEach { k -> obj.put(k, extra.get(k)) }
            }
        }
        return obj.toString()
    }

    private fun buildCall(fnName: String, argsJson: String): String =
        "(function () {" +
            "  var finish = function (payload, err) {" +
            "    if (globalThis.__skyCallDone) return;" +
            "    globalThis.__skyCallDone = true;" +
            "    if (err) {" +
            "      __hikariDone(JSON.stringify({success: false, errorCode: 'JS_ERROR', message: String(err && err.message || err)}));" +
            "      return;" +
            "    }" +
            "    if (payload === undefined || payload === null) {" +
            "      __hikariDone(JSON.stringify({success: true, data: null}));" +
            "      return;" +
            "    }" +
            "    var text = null;" +
            "    try { text = JSON.stringify(payload); } catch (e) { text = null; }" +
            "    if (text === undefined || text === null || text === '') {" +
            "      __hikariDone(JSON.stringify({success: true, data: null}));" +
            "      return;" +
            "    }" +
            "    var head = text.charAt(0);" +
            "    if (head === '{' || head === '[') { __hikariDone(text); return; }" +
            "    __hikariDone(JSON.stringify({success: true, data: payload}));" +
            "  };" +
            "  try {" +
            "    var fn = globalThis[${quote(fnName)}];" +
            "    if (typeof fn !== 'function') {" +
            "      __hikariDone(JSON.stringify({success: false, errorCode: 'NO_FUNCTION', message: ${quote("plugin does not export " + fnName)}}));" +
            "      return;" +
            "    }" +
            "    var args = $argsJson;" +
            "    args.push(function (res) { finish(res, null); });" +
            "    var r = fn.apply(null, args);" +
            "    if (r && typeof r.then === 'function') {" +
            "      r.then(function (v) { finish(v, null); }, function (e) { finish(null, e); });" +
            "    } else if (r !== undefined) {" +
            "      finish(r, null);" +
            "    }" +
            "  } catch (e) {" +
            "    finish(null, e);" +
            "  }" +
            "})();"

    private fun normalise(payload: String): String {
        val text = payload.trim()
        if (text.isEmpty()) return failure("plugin returned nothing")
        if (text.startsWith("[")) return "{\"ok\":true,\"data\":$text}"
        val o = runCatching { JSONObject(text) }.getOrNull()
            ?: return failure("plugin returned an unreadable result")
        if (o.optBoolean("success", true)) {
            val data = when {
                !o.has("data") -> "null"
                o.isNull("data") -> "null"
                else -> {
                    val dv = o.opt("data")
                    if (dv is String) quote(dv) else dv.toString()
                }
            }
            return "{\"ok\":true,\"data\":$data}"
        }
        val code = o.optString("errorCode")
        val msg = o.optString("message").ifBlank { o.optString("error") }
        val detail = when {
            msg.isNotBlank() -> msg
            code.isNotBlank() -> code
            else -> "no results"
        }
        return failure(detail)
    }

    private fun failure(message: String): String =
        "{\"ok\":false,\"error\":${quote(message.take(400))}}"

    // ---- Fetch bridge ----

    private fun bridgeFetch(
        url: String,
        method: String,
        headersJson: String,
        body: String,
        followRedirects: Boolean,
    ): String {
        val started = System.currentTimeMillis()
        val m = method.uppercase()
        val task = fetchExecutor.submit<String> { fetchOnce(url, m, headersJson, body, started) }
        return try {
            task.get(FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            fetchLogLine(hostOf(url), m, "TIMEOUT", 0, System.currentTimeMillis() - started, " fetch did not finish in ${FETCH_TIMEOUT_MS / 1000}s")
            "{\"ok\":false,\"status\":0,\"statusText\":\"fetch timed out\",\"url\":${quote(url)},\"headers\":{},\"body\":\"\"}"
        }
    }

    /** A batch of requests fired in one native call (the shim's http_parallel). */
    private fun bridgeFetchMany(requestsJson: String): String {
        val arr = runCatching { org.json.JSONArray(requestsJson) }.getOrNull()
            ?: return "[]"
        val futures = ArrayList<java.util.concurrent.Future<String>>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: JSONObject()
            val url = o.optString("url")
            val m = o.optString("method", "GET").uppercase().ifBlank { "GET" }
            val headers = o.optJSONObject("headers")?.toString() ?: "{}"
            val body = o.optString("body")
            val started = System.currentTimeMillis()
            futures += fetchExecutor.submit<String> { fetchOnce(url, m, headers, body, started) }
        }
        val out = org.json.JSONArray()
        for (f in futures) {
            out.put(runCatching { f.get(FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
                .getOrElse { "{\"ok\":false,\"status\":0,\"headers\":{},\"body\":\"\"}" })
        }
        return out.toString()
    }

    private fun extractOnce(url: String, referer: String?): String {
        if (!url.startsWith("http")) return "[]"
        val started = System.currentTimeMillis()
        val task = fetchExecutor.submit<String> {
            val sources = kotlinx.coroutines.runBlocking {
                runCatching { com.hikari.app.cs3.FallbackResolver.resolveEmbedUrl(url, referer) }
                    .getOrDefault(emptyList())
            }
            val arr = org.json.JSONArray()
            for (s in sources) {
                val o = JSONObject()
                o.put("url", s.url)
                o.put("name", s.name)
                o.put("headers", JSONObject(s.headers))
                o.put("isM3u8", s.isM3u8)
                arr.put(o)
            }
            arr.toString()
        }
        return try {
            val out = task.get(EXTRACT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            fetchLogLine(
                hostOf(url), "EXTRACT",
                if (out == "[]") "none" else out.length.toString(),
                0, System.currentTimeMillis() - started, "",
            )
            out
        } catch (e: Exception) {
            fetchLogLine(
                hostOf(url), "EXTRACT", "TIMEOUT", 0,
                System.currentTimeMillis() - started,
                " extractor did not finish in ${EXTRACT_TIMEOUT_MS / 1000}s",
            )
            "[]"
        }
    }

    private fun fetchOnce(url: String, m: String, headersJson: String, body: String, started: Long): String {
        return try {
            val builder = Request.Builder().url(url).header("User-Agent", DEFAULT_UA)
            val h = runCatching { JSONObject(headersJson) }.getOrNull()
            if (h != null) {
                h.keys().forEach { k ->
                    // OkHttp only decompresses gzip/br transparently when the
                    // REQUEST carries no Accept-Encoding of its own; forwarding
                    // the plugin's "gzip, deflate, br" handed JS raw compressed
                    // bytes decoded as UTF-8 (same fix as the nuvio bridge).
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
                val extra = StringBuilder()
                if (bytes.size <= 200_000) {
                    val ct = r.headers["Content-Type"] ?: ""
                    val cfWorthScanning =
                        r.code == 403 || r.code == 503 ||
                            (r.code in 200..299 && ct.contains("html", ignoreCase = true))
                    if (cfWorthScanning) {
                        val low = String(bytes, Charsets.ISO_8859_1).lowercase()
                        if (CloudflareVerifier.isCloudflareChallenge(r, low)) {
                            extra.append(" CF-CHALLENGE-UNSOLVED")
                            CloudflareVerifier.markBlocked(url)
                        }
                    }
                }
                val ce = r.headers["Content-Encoding"]
                if (ce != null && ce.isNotBlank()) extra.append(" CE=").append(ce)
                if (r.code != 200 || bytes.size > 100_000) {
                    val ct = (r.headers["Content-Type"] ?: "?").substringBefore(";")
                    extra.append(" CT=").append(ct)
                    val preview = String(bytes, Charsets.ISO_8859_1).trim().take(60)
                        .replace(Regex("[^\\x20-\\x7E]"), ".")
                    extra.append(" [").append(preview).append("]")
                }
                fetchLogLine(hostOf(url), m, r.code.toString(), bytes.size, System.currentTimeMillis() - started, extra.toString())
                val out = JSONObject()
                out.put("ok", r.isSuccessful)
                out.put("status", r.code)
                out.put("statusText", r.message)
                out.put("url", r.request.url.toString())
                val hdrs = JSONObject()
                runCatching { r.headers.forEach { (k, v) -> if (!hdrs.has(k.lowercase())) hdrs.put(k.lowercase(), v) } }
                out.put("headers", hdrs)
                val charset = runCatching {
                    val ct = r.headers["Content-Type"] ?: ""
                    val enc = ct.substringAfter("charset=", "").trim().trim('"')
                    if (enc.isEmpty()) Charsets.UTF_8 else Charset.forName(enc)
                }.getOrNull() ?: Charsets.UTF_8
                out.put("body", String(bytes, charset))
                out.put("ms", System.currentTimeMillis() - started)
                out.toString()
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
            out.put("error", e.message ?: "network error")
            out.toString()
        }
    }

    private val TIMER_JS = """
        if (typeof globalThis.setTimeout !== 'function') {
          globalThis.__skyTimers = {};
          globalThis.__skyTimerSeq = 0;
          globalThis.setTimeout = function (callback, delay) {
            var id = 't_' + (++globalThis.__skyTimerSeq);
            globalThis.__skyTimers[id] = {
              due: Date.now() + (Number(delay) || 0),
              fn: function () {
                if (!globalThis.__skyTimers[id]) return;
                delete globalThis.__skyTimers[id];
                try { callback(); } catch (e) { try { console.error('Timeout error:', e); } catch (e2) {} }
              }
            };
            return id;
          };
          globalThis.clearTimeout = function (id) {
            if (!id) return;
            delete globalThis.__skyTimers[id];
          };
          globalThis.setInterval = function (callback, delay) {
            var id = 'i_' + (++globalThis.__skyTimerSeq);
            var period = Number(delay) || 0;
            globalThis.__skyTimers[id] = {
              due: Date.now() + period,
              fn: function () {
                if (!globalThis.__skyTimers[id]) return;
                try { callback(); } catch (e) { try { console.error('Interval error:', e); } catch (e2) {} }
                var t = globalThis.__skyTimers[id];
                if (t) t.due = Date.now() + period;
              }
            };
            return id;
          };
          globalThis.clearInterval = globalThis.clearTimeout;
        }
        globalThis.__skyFireTimer = function () {
          var ids = Object.keys(globalThis.__skyTimers || {});
          if (!ids.length) return -1;
          var now = Date.now();
          var best = null;
          var bestDue = Infinity;
          for (var i = 0; i < ids.length; i++) {
            var t = globalThis.__skyTimers[ids[i]];
            if (!t) continue;
            if (t.due < bestDue) { bestDue = t.due; best = ids[i]; }
          }
          if (best === null) return -1;
          if (bestDue > now) return Math.max(1, Math.round(bestDue - now));
          var fn = globalThis.__skyTimers[best] && globalThis.__skyTimers[best].fn;
          try { if (fn) fn(); } catch (e) {}
          return 0;
        };
    """.trimIndent()

    /** Normalisation entry point used by the provider (kept public so the
     *  provider can share one code path for every plugin function). */
    fun normalisePayload(payload: String): String = normalise(payload)
}
