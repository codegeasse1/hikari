package com.hikari.app.skystream

import android.content.Context
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.function
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
import java.util.concurrent.TimeUnit

/**
 * Runs SkyStream plugins (`.sky` files: plugin.json + plugin.js) inside a fresh
 * embedded QuickJS engine per call, the same way [com.hikari.app.nuvio.NuvioRuntime]
 * runs nuvio providers — one engine, one plugin call, then the whole VM is torn
 * down so nothing can leak between plugins.
 *
 * A SkyStream plugin is a plain single-file script that publishes four
 * callbacks on `globalThis`:
 *
 *   getHome(cb)             -> cb({success, data: {"<category>": [item, …]}})
 *   search(query, cb)       -> cb({success, data: [item, …]})
 *   load(url, cb)           -> cb({success, data: item})   // + episodes[]
 *   loadStreams(url, cb)    -> cb({success, data: [stream, …]})
 *
 * …and reaches the network, HTML parsing, crypto and settings through globals
 * that SkyStream's own engine installs. This runtime recreates that surface:
 * `assets/skystream/shim.js` (loaded after nuvio's boot.js polyfills + cheerio
 * bundle, both of which Hikari already ships) defines http_get / http_post /
 * http_parallel / fetch, the DigitalOcean-style `parseHtml` DOM facade,
 * `parse_html`, `getAndUnpack`, `crypto.decryptAES`, `getPreference`,
 * `setPreference`, the MultimediaItem/Episode/StreamResult classes and the
 * timers. Everything that touches the network goes through the synchronous
 * `__hikariFetch` bridge (OkHttp), so the engine needs no WebView.
 *
 * Timing: the plugin's own `await`s resolve on the engine's microtask queue,
 * which QuickJS drains while `evaluate` runs — a plugin whose fetches are all
 * plain awaits therefore reports its result inside the initial call. Plugins
 * that use `setTimeout` (anti-bot delays, retry backoff, ~26 of the 36 official
 * plugins do) park a callback in the shim's timer registry instead; the loop
 * below fires those timers between `evaluate` rounds until the plugin answers
 * or the call budget runs out.
 */
object SkyStreamRuntime {

    // Same reasoning as NuvioRuntime: each engine is a native VM plus a ~450KB
    // cheerio parse, so bound how many run at once and let the rest queue.
    private const val MAX_CONCURRENT = 6
    private const val FETCH_TIMEOUT_MS = 30_000L
    private const val CALL_TIMEOUT_MS = 45_000L
    private const val VALIDATE_TIMEOUT_MS = 20_000L
    /** Budget for one legacy `loadExtractor` call (a plugin is blocked on it). */
    private const val EXTRACT_TIMEOUT_MS = 25_000L

    /** Blocks of timer-pumping between engine rounds; also caps how long one
     *  pending timer may be slept for (a plugin that asks for a 30s delay gets
     *  a capped wait, since a shorter one is indistinguishable for scraping). */
    private const val TIMER_MAX_WAIT_MS = 2_000L

    private const val DEFAULT_UA = com.hikari.app.net.Http.UA

    private val concurrency = Semaphore(MAX_CONCURRENT)

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

    /**
     * Runs a script and throws its completion value away.
     *
     * dokar's `evaluate<Any?>` marshals the script's *completion value* back to
     * Kotlin by `JSON.stringify`-ing it inside the engine. Extension scripts
     * that end in `Object.assign(globalThis, PluginModule)` — the export
     * pattern several of the published SkyStream plugins use, e.g. the `akash`
     * and `dev.cookie.*` repos — complete with `globalThis` itself as the value,
     * and stringifying that throws `TypeError: circular reference`. That used to
     * surface as *"Not a valid SkyStream extension: TypeError: circular
     * reference"* and blocked the install of every extension from those repos
     * (the script itself had run fine — only the value we never look at failed
     * to convert). Nothing in this runtime wants a completion value, so every
     * script is finished with `void 0` instead.
     */
    private fun QuickJs.evaluateVoid(js: String, name: String) {
        evaluate<Any?>("$js\n;void 0;\n", name, false)
    }

    private val fetchExecutor: ExecutorService = Executors.newFixedThreadPool(6)

    private val client by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            // Some extension-repo hosts answer only via a public resolver on
            // some devices/ISPs — fall back to DNS-over-HTTPS (see DohDns).
            .dns(DohDns)
            .build()
    }

    /**
     * Boots a fresh engine: native bridges, polyfills, cheerio, the SkyStream
     * shim, then the plugin's manifest + source. The plugin functions are left
     * callable on globalThis; the caller runs one of them. Returns the engine;
     * the caller must close() it in a finally.
     */
    private suspend fun createEngine(
        deferred: CompletableDeferred<String>,
        pluginId: String,
        manifestJson: String,
        source: String,
        prefs: Prefs,
    ): QuickJs {
        val qjs = QuickJs.create(jobDispatcher = Dispatchers.Default)
        qjs.evaluationTimeoutMillis = CALL_TIMEOUT_MS

        qjs.function("__hikariFetch") { args ->
            val url = args.getOrNull(0)?.toString() ?: ""
            val method = args.getOrNull(1)?.toString() ?: "GET"
            val headersJson = args.getOrNull(2)?.toString() ?: "{}"
            val body = args.getOrNull(3)?.toString() ?: ""
            val followRedirects = args.getOrNull(4) as? Boolean ?: true
            bridgeFetch(url, method, headersJson, body, followRedirects)
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
        // 5. The plugin's own manifest (plugins read manifest.baseUrl) and its
        //    source. Both are plain scripts assigning globals — the source in
        //    particular usually ends in an export helper whose return value is
        //    cyclic, which is why it goes through evaluateVoid.
        qjs.evaluateVoid("globalThis.manifest = $manifestJson;", "manifest.js")
        qjs.evaluateVoid(source, "$pluginId.js")
        return qjs
    }

    // ---- Public API ----

    /**
     * Runs one plugin function in a fresh engine and returns the normalised
     * as `{"ok":true,"data":…}` when the plugin reported success and
     * `{"ok":false,"error":"…"}` when it reported a failure or threw.
     * [argsJson] is a JSON array of the call's arguments (the completion
     * callback is appended by the wrapper).
     */
    suspend fun invoke(
        context: Context,
        pluginId: String,
        scriptFile: File,
        fnName: String,
        argsJson: String,
    ): String {
        val source = runCatching { scriptFile.readText() }.getOrNull()
        if (source.isNullOrBlank()) return failure("plugin file missing — reinstall this extension")
        val manifestJson = readManifest(scriptFile)
        return concurrency.withPermit {
            withTimeoutOrNull(CALL_TIMEOUT_MS + 20_000L) {
                withContext(Dispatchers.Default) {
                    val deferred = CompletableDeferred<String>()
                    val prefs = Prefs(settingsFile(pluginId))
                    var qjs: QuickJs? = null
                    try {
                        qjs = createEngine(deferred, pluginId, manifestJson, source, prefs)
                        qjs.evaluate<Any?>(buildCall(fnName, argsJson), "call.js", false)
                        val deadline = System.currentTimeMillis() + CALL_TIMEOUT_MS
                        while (!deferred.isCompleted && System.currentTimeMillis() < deadline) {
                            val next = qjs.evaluate<Any?>("__skyFireTimer()", "timer.js", false)
                            // A timer callback usually does its fetch + completes
                            // inside this very evaluate: return the answer now
                            // instead of sleeping out the timer's delay first
                            // (that wait was pure latency on the player's
                            // "instant play" path).
                            if (deferred.isCompleted) break
                            val wait = (next as? Number)?.toLong() ?: -1L
                            if (wait < 0) break
                            delay(wait.coerceAtMost(TIMER_MAX_WAIT_MS))
                        }
                        if (deferred.isCompleted) normalise(deferred.await())
                        else failure("timed out after ${CALL_TIMEOUT_MS / 1000}s")
                    } catch (e: Throwable) {
                        if (deferred.isCompleted) normalise(deferred.await())
                        else failure(e.message ?: e.javaClass.simpleName)
                    } finally {
                        runCatching { qjs?.close() }
                        prefs.save()
                    }
                }
            } ?: failure("timed out after ${CALL_TIMEOUT_MS / 1000}s")
        }
    }

    /**
     * True when the plugin loads and exports all four callbacks. Starts with
     * "OK"; "ERR:…" carries the reason; anything else means "not a SkyStream
     * plugin".
     */
    suspend fun validate(context: Context, pluginId: String, scriptFile: File): String {
        val source = runCatching { scriptFile.readText() }.getOrNull()
        if (source.isNullOrBlank()) return "NO"
        val manifestJson = readManifest(scriptFile)
        return withContext(Dispatchers.Default) {
            var qjs: QuickJs? = null
            try {
                val deferred = CompletableDeferred<String>()
                val prefs = Prefs(settingsFile(pluginId))
                qjs = createEngine(deferred, pluginId, manifestJson, source, prefs)
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

    /** The plugin's plugin.json (next to its plugin.js), as a JSON object the
     *  engine can assign to `manifest`. Falls back to an empty object so a
     *  plugin that reads manifest.baseUrl fails on its own terms. */
    private fun readManifest(scriptFile: File): String {
        val dir = scriptFile.parentFile ?: return "{}"
        val f = File(dir, "plugin.json")
        val text = runCatching { f.takeIf { it.exists() }?.readText() }.getOrNull()
        if (text.isNullOrBlank()) return "{}"
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return "{}"
        return obj.toString()
    }

    /**
     * The call wrapper. Mirrors SkyStream's own invoke wrapper: the completion
     * callback is handed to the plugin as its last argument *and* attached to a
     * returned promise, and whichever fires first wins (many plugins do both).
     */
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

    /**
     * Normalises the raw payload the plugin handed us — either SkyStream's
     * `{success, data|errorCode+message}` envelope, or (a few community
     * plugins) a bare array — into `{"ok":…}`.
     */
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

    /**
     * `loadExtractor(url, cb)` support: runs Hikari's own extraction stack on one
     * embed URL and returns it as the JSON array the shim turns into
     * StreamResults. Blocking (a QuickJS bridge cannot suspend), so it owns a
     * pool thread and is bounded by [EXTRACT_TIMEOUT_MS].
     */
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
                if (r.code == 403 || r.code == 503) {
                    val low = String(bytes, Charsets.ISO_8859_1).lowercase()
                    if (low.contains("just a moment") || low.contains("attention required") ||
                        low.contains("cf-chl") || low.contains("checking your browser")
                    ) extra.append(" CF-CHALLENGE-UNSOLVED")
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

    /**
     * SkyStream's timer globals, reimplemented for QuickJS. `setTimeout` parks
     * its callback in a registry; the host fires them one per round through
     * `__skyFireTimer`, which returns the delay it slept for (or -1 when no
     * timer is pending, which ends the pumping). `setInterval` re-arms itself.
     */
    private val TIMER_JS = """
        if (typeof globalThis.setTimeout !== 'function') {
          globalThis.__skyTimers = {};
          globalThis.__skyTimerDelays = {};
          globalThis.__skyTimerSeq = 0;
          globalThis.setTimeout = function (callback, delay) {
            var id = 't_' + (++globalThis.__skyTimerSeq);
            globalThis.__skyTimers[id] = function () {
              if (!globalThis.__skyTimers[id]) return;
              delete globalThis.__skyTimers[id];
              try { callback(); } catch (e) { try { console.error('Timeout error:', e); } catch (e2) {} }
            };
            globalThis.__skyTimerDelays[id] = delay || 0;
            return id;
          };
          globalThis.clearTimeout = function (id) {
            if (!id) return;
            delete globalThis.__skyTimers[id];
            delete globalThis.__skyTimerDelays[id];
          };
          globalThis.setInterval = function (callback, delay) {
            var id = 'i_' + (++globalThis.__skyTimerSeq);
            globalThis.__skyTimers[id] = function () {
              if (!globalThis.__skyTimers[id]) return;
              try { callback(); } catch (e) { try { console.error('Interval error:', e); } catch (e2) {} }
              if (globalThis.__skyTimers[id]) globalThis.__skyTimerDelays[id] = delay || 0;
            };
            globalThis.__skyTimerDelays[id] = delay || 0;
            return id;
          };
          globalThis.clearInterval = globalThis.clearTimeout;
        }
        globalThis.__skyFireTimer = function () {
          var ids = Object.keys(globalThis.__skyTimers);
          if (!ids.length) return -1;
          var id = ids[0];
          var delay = globalThis.__skyTimerDelays[id] || 0;
          delete globalThis.__skyTimerDelays[id];
          var fn = globalThis.__skyTimers[id];
          try { if (fn) fn(); } catch (e) {}
          return delay;
        };
    """.trimIndent()

    /** Normalisation entry point used by the provider (kept public so the
     *  provider can share one code path for every plugin function). */
    fun normalisePayload(payload: String): String = normalise(payload)
}
