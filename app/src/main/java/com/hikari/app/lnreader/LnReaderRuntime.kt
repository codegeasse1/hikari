package com.hikari.app.lnreader

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import com.hikari.app.HikariApp
import com.hikari.app.data.PerfMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs LNReader novel-source plugins (compiled CommonJS off
 * `github.com/lnreader/lnreader-plugins`) inside a fresh embedded QuickJS
 * engine per call — the same shape as [com.hikari.app.nuvio.NuvioRuntime]:
 * one `QuickJs.create` per call, boot.js polyfills + the real cheerio bundle
 * + the nuvio provider harness evaluated in, every network request through the
 * same asynchronous fetch bridge (OkHttp, no WebView, no Cloudflare solving).
 *
 * The plugin contract (see the lnreader-plugins docs) is a default-exported
 * instance with `popularNovels(page, options)`, `parseNovel(path)`,
 * `parseChapter(path)`, `searchNovels(term, page)` and an optional
 * `parsePage(novelPath, page)`, importing `cheerio` and the `@libs/`
 * helpers
 * through `require(...)`. The harness already gives us a CommonJS `require`,
 * a standard `fetch` and cheerio; the [LNREADER_GLUE] below registers the
 * `@libs/` modules on top of those (fetch wrappers over the harness fetch,
 * NovelStatus/FilterTypes enums with their documented wire values, a
 * file-backed `storage`, a small but real `dayjs`, `urlencode`,
 * `isAbsoluteUrl`, `defaultCover`, `utils`) so a plugin's imports resolve to
 * working implementations instead of MODULE_NOT_FOUND.
 *
 * Deliberately v1: `fetchProto` (protobuf APIs — rare) and AES-GCM throw
 * an honest "not supported" only if a plugin actually reaches for them;
 * `customJS`/`customCSS` are stored at install but not executed (cosmetic
 * page tweaks); plugin settings screens are not exposed (their defaults
 * apply). None of that fails an install — a plugin that needs one of them
 * reports its own error on the call that needs it.
 */
object LnReaderRuntime {

    private const val CALL_TIMEOUT_MS = 60_000L
    private const val ENGINE_MEMORY_LIMIT = 256L * 1024 * 1024

    private val engineMemoryLimit: Long
        get() = PerfMode.tvEngineMemoryLimit ?: ENGINE_MEMORY_LIMIT

    /** Novels are browsed one source at a time — four engines is plenty. */
    private val concurrency = Semaphore(4)

    private val bootJs: String by lazy { readAsset("nuvio/boot.js") }
    private val cheerioJs: String by lazy { readAsset("nuvio/cheerio.js") }
    private val harnessJs: String by lazy { readAsset("nuvio/harness.js") }

    private fun readAsset(path: String): String =
        HikariApp.instance.assets.open(path).bufferedReader().readText()

    private fun quote(s: String): String = JSONObject.quote(s)

    private fun errJson(msg: String): String =
        "{\"ok\":false,\"error\":" + quote(msg) + "}"

    /** Compiled QuickJS bytecode for the runtime scripts, keyed by script
     *  name — one compile per process, not one per engine (see
     *  NuvioRuntime's bytecodeCache for why). */
    private val bytecodeCache = ConcurrentHashMap<String, ByteArray>()

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

    /**
     * The LNReader module registry + plugin loader, evaluated after the nuvio
     * harness (which owns `require`, `fetch` and cheerio). Written as plain
     * string concatenation — no `$` or backticks anywhere, so Kotlin never
     * mistakes JS for a template.
     */
    private val LNREADER_GLUE: String =
        "globalThis.__lnreaderStorageData = {};" +
            "globalThis.__lnreaderStorageToJson = function () { return JSON.stringify(globalThis.__lnreaderStorageData || {}); };" +
            "globalThis.__lnreaderDone = function (cid, obj) { globalThis.__hikariOnLnDone(cid, JSON.stringify(obj)); };" +
            "globalThis.__lnreaderLoad = function (source) {" +
            "  var mod = { exports: {} };" +
            "  var fn = new Function('module', 'exports', 'require', source);" +
            "  fn(mod, mod.exports, globalThis.__nuvioRequire);" +
            "  var ex = mod.exports;" +
            "  if (ex && typeof ex === 'object') {" +
            "    if (ex.__esModule && ex['default'] !== undefined) ex = ex['default'];" +
            "    else if (ex['default'] !== undefined && ex['default'] !== null && typeof ex['default'] === 'object' && typeof ex['default'].popularNovels === 'function') ex = ex['default'];" +
            "  }" +
            "  if (typeof ex === 'function') {" +
            "    var proto = ex.prototype;" +
            "    if (proto && (typeof proto.popularNovels === 'function' || typeof proto.parseNovel === 'function')) ex = new ex();" +
            "    else if (typeof ex.popularNovels !== 'function' && typeof ex.parseNovel !== 'function') {" +
            "      try { var inst = new ex(); if (inst && typeof inst.popularNovels === 'function') ex = inst; } catch (e) {}" +
            "    }" +
            "  }" +
            "  return ex;" +
            "};" +
            "globalThis.__lnreaderRegister = function () {" +
            "  var R = globalThis.__nuvioRegisterModule;" +
            "  R('cheerio', globalThis.__nuvioCheerio);" +
            "  R('@libs/fetch', {" +
            "    fetchApi: function (url, init) {" +
            "      init = init || {};" +
            "      var headers = init.headers || {};" +
            "      if (typeof Headers !== 'undefined' && headers instanceof Headers) { var o = {}; headers.forEach(function (v, k) { o[k] = v; }); headers = o; }" +
            "      var body = (init.body === undefined || init.body === null) ? null : init.body;" +
            "      return globalThis.__nuvioFetch(String(url), { method: init.method || 'GET', headers: headers, body: body });" +
            "    }," +
            "    fetchText: function (url, init, encoding) {" +
            "      return globalThis.__nuvioFetch(String(url), { method: (init && init.method) || 'GET', headers: (init && init.headers) || {} })" +
            "        .then(function (res) { return res.text(); }, function () { return ''; })" +
            "        .then(function (t) { return t || ''; }, function () { return ''; });" +
            "    }," +
            "    fetchProto: function () { return Promise.reject(new Error('protobuf sources are not supported in Hikari')); }" +
            "  });" +
            "  R('@libs/novelStatus', { NovelStatus: { Unknown: 'Unknown', Ongoing: 'Ongoing', Completed: 'Completed', Licensed: 'Licensed', PublishingFinished: 'Publishing Finished', Cancelled: 'Cancelled', OnHiatus: 'On Hiatus', STUB: 'STUB', Inactive: 'Inactive' } });" +
            "  R('@libs/filterInputs', { FilterTypes: { Picker: 'Picker', TextInput: 'Text', Switch: 'Switch', CheckboxGroup: 'Checkbox', ExcludableCheckboxGroup: 'XCheckbox' }, Filters: {} });" +
            "  R('@libs/defaultCover', { defaultCover: '' });" +
            "  R('@libs/isAbsoluteUrl', { isUrlAbsolute: function (url) { return /^https?:\\/\\//i.test(String(url || '')); } });" +
            "  R('urlencode', { encode: function (s) { return encodeURIComponent(String(s)); }, decode: function (s) { try { return decodeURIComponent(String(s)); } catch (e) { return String(s); } } });" +
            "  R('htmlparser2', { Parser: globalThis.__lnreaderHtmlParser });" +
            "  R('@libs/aes', { gcm: function () { throw new Error('AES-protected sources are not supported in Hikari'); } });" +
            "  R('@libs/utils', {" +
            "    utf8ToBytes: function (s) { if (typeof TextEncoder !== 'undefined') return new TextEncoder().encode(String(s)); var u = unescape(encodeURIComponent(String(s))); var b = new Uint8Array(u.length); for (var i = 0; i < u.length; i++) b[i] = u.charCodeAt(i); return b; }," +
            "    bytesToUtf8: function (b) { if (typeof TextDecoder !== 'undefined') return new TextDecoder().decode(b); var s = ''; for (var i = 0; i < b.length; i++) s += String.fromCharCode(b[i]); return decodeURIComponent(escape(s)); }" +
            "  });" +
            "  R('@libs/storage', {" +
            "    storage: {" +
            "      get: function (k, raw) { var e = globalThis.__lnreaderStorageData[k]; if (e === undefined || e === null) return undefined; if (e.expires && Date.now() > e.expires) { delete globalThis.__lnreaderStorageData[k]; return undefined; } if (raw) return e; return e.value; }," +
            "      set: function (k, v, expires) { globalThis.__lnreaderStorageData[k] = { value: v, created: Date.now(), expires: (expires instanceof Date) ? expires.getTime() : (expires || null) }; }," +
            "      delete: function (k) { delete globalThis.__lnreaderStorageData[k]; }," +
            "      clearAll: function () { globalThis.__lnreaderStorageData = {}; }," +
            "      getAllKeys: function () { return Object.keys(globalThis.__lnreaderStorageData); }" +
            "    }," +
            "    localStorage: { get: function () { return undefined; } }," +
            "    sessionStorage: { get: function () { return undefined; } }" +
            "  });" +
            "  R('dayjs', globalThis.__lnreaderDayjs);" +
            "};" +
            "globalThis.__lnreaderDayjs = function (input) {" +
            "  function parse(v) {" +
            "    if (v === undefined || v === null || v === '') return new Date();" +
            "    if (v instanceof Date) return new Date(v.getTime());" +
            "    if (typeof v === 'number') return new Date(v);" +
            "    var s = String(v).trim();" +
            "    var rel = s.match(/^(\\d+)\\s+(second|minute|hour|day|week|month|year)s?\\s+ago$/i);" +
            "    if (rel) { var n = parseInt(rel[1], 10); var u = rel[2].toLowerCase(); var d0 = new Date();" +
            "      if (u === 'second') d0.setSeconds(d0.getSeconds() - n);" +
            "      else if (u === 'minute') d0.setMinutes(d0.getMinutes() - n);" +
            "      else if (u === 'hour') d0.setHours(d0.getHours() - n);" +
            "      else if (u === 'day') d0.setDate(d0.getDate() - n);" +
            "      else if (u === 'week') d0.setDate(d0.getDate() - 7 * n);" +
            "      else if (u === 'month') d0.setMonth(d0.getMonth() - n);" +
            "      else if (u === 'year') d0.setFullYear(d0.getFullYear() - n);" +
            "      return d0; }" +
            "    var t = Date.parse(s);" +
            "    return isNaN(t) ? new Date(NaN) : new Date(t);" +
            "  }" +
            "  var d = parse(input);" +
            "  function pad(n) { return (n < 10 ? '0' : '') + n; }" +
            "  function shift(dd, n, unit) { var o = new Date(dd.getTime());" +
            "    if (unit && unit[0] === 'y') o.setFullYear(o.getFullYear() + n);" +
            "    else if (unit && unit[0] === 'M') o.setMonth(o.getMonth() + n);" +
            "    else if (unit && (unit[0] === 'w')) o.setDate(o.getDate() + 7 * n);" +
            "    else if (unit && unit[0] === 'd') o.setDate(o.getDate() + n);" +
            "    else if (unit && unit[0] === 'h') o.setHours(o.getHours() + n);" +
            "    else if (unit && unit[0] === 'm') o.setMinutes(o.getMinutes() + n);" +
            "    else o.setSeconds(o.getSeconds() + n);" +
            "    return o; }" +
            "  return {" +
            "    isValid: function () { return !isNaN(d.getTime()); }," +
            "    toDate: function () { return new Date(d.getTime()); }," +
            "    valueOf: function () { return d.getTime(); }," +
            "    toISOString: function () { return d.toISOString(); }," +
            "    year: function () { return d.getFullYear(); }," +
            "    month: function () { return d.getMonth(); }," +
            "    date: function () { return d.getDate(); }," +
            "    format: function (f) { f = f || 'YYYY-MM-DD';" +
            "      return f.replace('YYYY', String(d.getFullYear())).replace('MM', pad(d.getMonth() + 1)).replace('DD', pad(d.getDate())).replace('HH', pad(d.getHours())).replace('mm', pad(d.getMinutes())).replace('ss', pad(d.getSeconds())); }," +
            "    add: function (n, unit) { return globalThis.__lnreaderDayjs(shift(d, n, unit)); }," +
            "    subtract: function (n, unit) { return globalThis.__lnreaderDayjs(shift(d, -n, unit)); }," +
            "    isAfter: function (o) { return d.getTime() > globalThis.__lnreaderDayjs(o).valueOf(); }," +
            "    isBefore: function (o) { return d.getTime() < globalThis.__lnreaderDayjs(o).valueOf(); }," +
            "    diff: function (o) { return d.getTime() - globalThis.__lnreaderDayjs(o).valueOf(); }" +
            "  };" +
            "};" +
            "globalThis.__lnreaderDayjs.extend = function () {};" +
            "globalThis.__lnreaderDayjs.utc = function (i) { return globalThis.__lnreaderDayjs(i); };" +
            "globalThis.__lnreaderVoidElements = {area:1,base:1,br:1,col:1,embed:1,hr:1,img:1,input:1,link:1,meta:1,param:1,source:1,track:1,wbr:1};" +
            "globalThis.__lnreaderRawText = {script:1,style:1,textarea:1,title:1};" +
            "globalThis.__lnreaderDecodeEntities = function (s) {" +
            "  return String(s).replace(/&(#\\d+|#x[0-9a-fA-F]+|[a-zA-Z]+);/g, function (m, e) {" +
            "    if (e.charAt(0) === '#') {" +
            "      var c = e.charAt(1).toLowerCase() === 'x' ? parseInt(e.slice(2), 16) : parseInt(e.slice(1), 10);" +
            "      return isNaN(c) ? m : String.fromCharCode(c);" +
            "    }" +
            "    var k = e.toLowerCase();" +
            "    if (k === 'amp') return '&';" +
            "    if (k === 'lt') return '<';" +
            "    if (k === 'gt') return '>';" +
            "    if (k === 'quot') return '\"';" +
            "    if (k === 'apos') return \"'\";" +
            "    if (k === 'nbsp') return ' ';" +
            "    return m;" +
            "  });" +
            "};" +
            "globalThis.__lnreaderHtmlParser = function (handlers, options) {" +
            "  var h = handlers || {};" +
            "  var buf = '';" +
            "  function emitText(t) { if (t !== '' && h.ontext) h.ontext(globalThis.__lnreaderDecodeEntities(t)); }" +
            "  function parseTag(s) {" +
            "    var i = 0, n = s.length;" +
            "    while (i < n && /\\s/.test(s.charAt(i))) i++;" +
            "    var closing = false;" +
            "    if (s.charAt(i) === '/') { closing = true; i++; }" +
            "    while (i < n && /\\s/.test(s.charAt(i))) i++;" +
            "    var name = '';" +
            "    while (i < n && /[^\\s=\\/>]/.test(s.charAt(i))) { name += s.charAt(i); i++; }" +
            "    name = name.toLowerCase();" +
            "    var attrs = {};" +
            "    var selfClose = false;" +
            "    if (!closing) {" +
            "      while (i < n) {" +
            "        while (i < n && /\\s/.test(s.charAt(i))) i++;" +
            "        if (i >= n) break;" +
            "        if (s.charAt(i) === '/') { selfClose = true; i++; continue; }" +
            "        var an = '';" +
            "        while (i < n && /[^\\s=\\/>]/.test(s.charAt(i))) { an += s.charAt(i); i++; }" +
            "        if (!an) { i++; continue; }" +
            "        while (i < n && /\\s/.test(s.charAt(i))) i++;" +
            "        var av = '';" +
            "        if (s.charAt(i) === '=') {" +
            "          i++;" +
            "          while (i < n && /\\s/.test(s.charAt(i))) i++;" +
            "          var q = s.charAt(i);" +
            "          if (q === '\"' || q === \"'\") {" +
            "            i++;" +
            "            var v = '';" +
            "            while (i < n && s.charAt(i) !== q) { v += s.charAt(i); i++; }" +
            "            i++;" +
            "            av = v;" +
            "          } else {" +
            "            var u = '';" +
            "            while (i < n && !/[\\s>]/.test(s.charAt(i))) { u += s.charAt(i); i++; }" +
            "            av = u;" +
            "          }" +
            "          av = globalThis.__lnreaderDecodeEntities(av);" +
            "        }" +
            "        attrs[an.toLowerCase()] = av;" +
            "      }" +
            "    }" +
            "    return {closing: closing, name: name, attrs: attrs, selfClose: selfClose};" +
            "  }" +
            "  function tokenize(html) {" +
            "    var i = 0, n = html.length, raw = null;" +
            "    while (i < n) {" +
            "      if (raw) {" +
            "        var idx = html.toLowerCase().indexOf('</' + raw, i);" +
            "        if (idx < 0) { emitText(html.slice(i)); i = n; raw = null; break; }" +
            "        if (idx > i) emitText(html.slice(i, idx));" +
            "        i = idx; raw = null; continue;" +
            "      }" +
            "      var lt = html.indexOf('<', i);" +
            "      if (lt < 0) { emitText(html.slice(i)); break; }" +
            "      if (lt > i) emitText(html.slice(i, lt));" +
            "      if (html.startsWith('<!--', lt)) {" +
            "        var ce = html.indexOf('-->', lt + 4);" +
            "        if (h.oncomment) h.oncomment(ce < 0 ? html.slice(lt + 4) : html.slice(lt + 4, ce));" +
            "        i = ce < 0 ? n : ce + 3; continue;" +
            "      }" +
            "      if (html.startsWith('<![CDATA[', lt)) {" +
            "        var de = html.indexOf(']]>', lt + 9);" +
            "        var data = de < 0 ? html.slice(lt + 9) : html.slice(lt + 9, de);" +
            "        if (h.ontext) h.ontext(data);" +
            "        i = de < 0 ? n : de + 3; continue;" +
            "      }" +
            "      if (html.startsWith('<!', lt) || html.startsWith('<?', lt)) {" +
            "        var gt0 = html.indexOf('>', lt + 2);" +
            "        i = gt0 < 0 ? n : gt0 + 1; continue;" +
            "      }" +
            "      var j = lt + 1, qq = null;" +
            "      while (j < n) {" +
            "        var ch = html.charAt(j);" +
            "        if (qq) { if (ch === qq) qq = null; }" +
            "        else if (ch === '\"' || ch === \"'\") qq = ch;" +
            "        else if (ch === '>') break;" +
            "        j++;" +
            "      }" +
            "      var t = parseTag(html.slice(lt + 1, j));" +
            "      i = j < n ? j + 1 : n;" +
            "      if (!t.name) continue;" +
            "      if (t.closing) { if (h.onclosetag) h.onclosetag(t.name); continue; }" +
            "      if (h.onopentag) h.onopentag(t.name, t.attrs);" +
            "      if (globalThis.__lnreaderVoidElements[t.name]) { if (h.onclosetag) h.onclosetag(t.name); }" +
            "      else if (!t.selfClose && globalThis.__lnreaderRawText[t.name]) raw = t.name;" +
            "    }" +
            "  }" +
            "  return {" +
            "    write: function (chunk) { buf += (chunk == null ? '' : String(chunk)); return this; }," +
            "    end: function (chunk) {" +
            "      if (chunk != null) buf += String(chunk);" +
            "      var all = buf; buf = '';" +
            "      tokenize(all);" +
            "      if (h.onend) h.onend();" +
            "      return this;" +
            "    }," +
            "    parseComplete: function (html) { tokenize(String(html == null ? '' : html)); }," +
            "    isVoidElement: function (tag) { return !!globalThis.__lnreaderVoidElements[String(tag || '').toLowerCase()]; }" +
            "  };" +
            "};"

    /** Same bridge glue the nuvio engines get (fetch + completion + log),
     *  pointed at this runtime's own done callback. */
    private val REGISTER_GLUE: String =
        "globalThis.__nuvioRegisterModule('cheerio', globalThis.__nuvioCheerio);" +
            "if (typeof globalThis.CryptoJS !== 'undefined') globalThis.__nuvioRegisterModule('crypto-js', globalThis.CryptoJS);" +
            "globalThis.__nuvioFetchImpl = function (url, method, headersJson, body, followRedirects) {" +
            "  return globalThis.__hikariFetch(String(url), String(method || 'GET'), headersJson || '{}', body == null ? '' : String(body), followRedirects !== false);" +
            "};" +
            "globalThis.__nuvioBridgeStub = {" +
            "  onGetStreamsDone: function (cid, payload) { globalThis.__hikariOnLnDone(cid, payload); }," +
            "  onSettingsDone: function (cid, payload) { globalThis.__hikariOnLnDone(cid, payload); }," +
            "  fetch: null," +
            "  log: function (msg) { if (typeof globalThis.__hikariLog === 'function') globalThis.__hikariLog(String(msg)); }" +
            "};"

    // ---- Per-plugin storage (filesDir/lnreader/storage/<id>.json) ----

    fun storageFile(pluginId: String): File {
        val safe = pluginId.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        return File(HikariApp.instance.filesDir, "lnreader/storage/$safe.json")
    }

    fun loadStorage(pluginId: String): String =
        runCatching { storageFile(pluginId).takeIf { it.exists() }?.readText() }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: "{}"

    fun saveStorage(pluginId: String, json: String) {
        runCatching {
            val f = storageFile(pluginId)
            f.parentFile?.mkdirs()
            f.writeText(json.ifBlank { "{}" })
        }
    }

    // ---- Engine plumbing ----

    private suspend fun createEngine(deferred: CompletableDeferred<String>): QuickJs {
        val qjs = QuickJs.create(jobDispatcher = Dispatchers.Default)
        qjs.evaluationTimeoutMillis = CALL_TIMEOUT_MS
        qjs.memoryLimit = engineMemoryLimit
        // The shared fetch stack: every LNReader request rides the same
        // OkHttp client (DoH, timeouts, logging) as the nuvio bridge.
        qjs.asyncFunction("__hikariFetch") { args ->
            val url = args.getOrNull(0)?.toString() ?: ""
            val method = args.getOrNull(1)?.toString() ?: "GET"
            val headersJson = args.getOrNull(2)?.toString() ?: "{}"
            val body = args.getOrNull(3)?.toString() ?: ""
            val followRedirects = args.getOrNull(4) as? Boolean ?: true
            com.hikari.app.nuvio.NuvioRuntime.bridgeFetchAsync(url, method, headersJson, body, followRedirects)
        }
        qjs.function("__hikariOnLnDone") { args ->
            val payload = args.getOrNull(1)?.toString() ?: ""
            deferred.complete(payload)
            ""
        }
        qjs.function("__hikariLog") { args ->
            android.util.Log.d("LnReader", args.getOrNull(0)?.toString() ?: "")
            ""
        }
        com.hikari.app.nuvio.NuvioCryptoBridge.bindAll(qjs)
        qjs.evaluateCached("boot.js", bootJs)
        qjs.evaluateCached("cheerio-head.js", "var __nuvioModule = { exports: {} }; var module = __nuvioModule; var exports = module.exports;")
        qjs.evaluateCached("cheerio.js", cheerioJs)
        qjs.evaluateCached("cheerio-tail.js", "globalThis.__nuvioCheerio = module.exports;")
        qjs.evaluateCached("harness.js", harnessJs)
        qjs.evaluateCached("lnreader-register.js", REGISTER_GLUE)
        qjs.evaluateCached("lnreader-glue.js", LNREADER_GLUE)
        return qjs
    }

    /**
     * Runs one plugin method call in a fresh engine. [inner] is JS that runs
     * with `plugin` bound to the loaded instance and must end by calling
     * `globalThis.__lnreaderDone(CID, {...})` exactly once. Returns the raw
     * payload string (`{"ok":true,"data":…}` or `{"ok":false,"error":"…"}`).
     */
    private suspend fun runPlugin(pluginFile: File, pluginId: String, inner: String): String {
        return concurrency.withPermit {
            withTimeoutOrNull(CALL_TIMEOUT_MS) {
                withContext(Dispatchers.Default) {
                    val cid = UUID.randomUUID().toString()
                    val deferred = CompletableDeferred<String>()
                    val qjs = createEngine(deferred)
                    try {
                        val source = runCatching { pluginFile.readText() }.getOrNull()
                            ?: return@withContext errJson("The source file is missing — reinstall it in Extensions")
                        var storageJson = loadStorage(pluginId)
                        if (runCatching { JSONObject(storageJson) }.getOrNull() == null) {
                            storageJson = "{}"
                        }
                        val call = "(async function () {" +
                            "  try {" +
                            "    globalThis.__lnreaderStorageData = $storageJson;" +
                            "    globalThis.__lnreaderRegister();" +
                            "    var plugin = globalThis.__lnreaderLoad(${quote(source)});" +
                            "    " + inner.replace("CID", quote(cid)) +
                            "  } catch (e) {" +
                            "    globalThis.__lnreaderDone(${quote(cid)}, { ok: false, error: String((e && e.message) || e) });" +
                            "  }" +
                            "})();"
                        qjs.evaluate<Any?>(call, "lnreader-call.js", false)
                        val payload = deferred.await()
                        // Persist whatever the plugin stored back to its file.
                        runCatching {
                            val back = qjs.evaluate<Any?>(
                                "__lnreaderStorageToJson()",
                                "lnreader-store.js",
                                false,
                            )?.toString()
                            if (!back.isNullOrBlank() && back != "{}") saveStorage(pluginId, back)
                        }
                        payload
                    } catch (e: Throwable) {
                        if (deferred.isCompleted) deferred.await()
                        else errJson(e.message ?: e.javaClass.simpleName)
                    } finally {
                        runCatching { qjs.close() }
                    }
                }
            } ?: errJson("The source timed out after ${CALL_TIMEOUT_MS / 1000}s")
        }
    }

    private fun doneOk(resultExpr: String): String =
        "globalThis.__lnreaderDone(CID, { ok: true, data: ($resultExpr) });"

    private fun doneMissing(method: String): String =
        "globalThis.__lnreaderDone(CID, { ok: false, error: 'this source has no $method' });"

    /** `popularNovels(page, {showLatestNovels, filters})` — the front-page
     *  list. [latest] selects the Latest view. */
    suspend fun popularNovels(pluginFile: File, pluginId: String, page: Int, latest: Boolean): String =
        runPlugin(
            pluginFile, pluginId,
            "if (!plugin || typeof plugin.popularNovels !== 'function') { " + doneMissing("popularNovels") + " }" +
                " else { var r = await plugin.popularNovels($page, { showLatestNovels: " + (if (latest) "true" else "false") + ", filters: ((plugin && plugin.filters) || {}) });" +
                " if (r === undefined || r === null) r = [];" +
                " " + doneOk("r") + " }",
        )

    /** `searchNovels(term, page)`. */
    suspend fun searchNovels(pluginFile: File, pluginId: String, term: String, page: Int): String =
        runPlugin(
            pluginFile, pluginId,
            "if (!plugin || typeof plugin.searchNovels !== 'function') { " + doneMissing("searchNovels") + " }" +
                " else { var r = await plugin.searchNovels(${quote(term)}, $page);" +
                " if (r === undefined || r === null) r = [];" +
                " " + doneOk("r") + " }",
        )

    /** `parseNovel(path)` — novel info + first page of chapters. */
    suspend fun parseNovel(pluginFile: File, pluginId: String, path: String): String =
        runPlugin(
            pluginFile, pluginId,
            "if (!plugin || typeof plugin.parseNovel !== 'function') { " + doneMissing("parseNovel") + " }" +
                " else { var r = await plugin.parseNovel(${quote(path)});" +
                " if (r === undefined || r === null) r = {};" +
                " " + doneOk("r") + " }",
        )

    /** `parsePage(novelPath, page)` — a later chapter-list page. */
    suspend fun parsePage(pluginFile: File, pluginId: String, novelPath: String, page: String): String =
        runPlugin(
            pluginFile, pluginId,
            "if (!plugin || typeof plugin.parsePage !== 'function') { " + doneMissing("parsePage") + " }" +
                " else { var r = await plugin.parsePage(${quote(novelPath)}, ${quote(page)});" +
                " if (r === undefined || r === null) r = {};" +
                " " + doneOk("r") + " }",
        )

    /** `parseChapter(path)` — the chapter's HTML text. */
    suspend fun parseChapter(pluginFile: File, pluginId: String, path: String): String =
        runPlugin(
            pluginFile, pluginId,
            "if (!plugin || typeof plugin.parseChapter !== 'function') { " + doneMissing("parseChapter") + " }" +
                " else { var r = await plugin.parseChapter(${quote(path)});" +
                " if (r === undefined || r === null) r = '';" +
                " " + doneOk("r") + " }",
        )

    /** `plugin.site` — the source's own site, for the globe/WebView button. */
    suspend fun siteUrl(pluginFile: File, pluginId: String): String {
        val payload = runPlugin(
            pluginFile, pluginId,
            "var s = (plugin && (plugin.site || '')) || '';" +
                " " + doneOk("String(s)"),
        )
        val o = runCatching { JSONObject(payload) }.getOrNull() ?: return ""
        if (!o.optBoolean("ok", false)) return ""
        return o.optString("data").trim()
    }

    /**
     * True when the JS module loads and exports a usable LNReader source.
     * Must start with "OK"; "ERR:..." carries a detail message.
     */
    suspend fun validate(source: String): String =
        withContext(Dispatchers.Default) {
            val tmp = runCatching {
                val f = File.createTempFile("lnreader-validate", ".js", HikariApp.instance.cacheDir)
                f.writeText(source)
                f
            }.getOrNull() ?: return@withContext "ERR: could not stage the source for validation"
            try {
                val payload = runPlugin(
                    tmp, "validate",
                    "if (!plugin) { " + doneMissing("plugin export") + " }" +
                        " else { var need = ['popularNovels', 'parseNovel', 'parseChapter', 'searchNovels'];" +
                        " var missing = need.filter(function (m) { return typeof plugin[m] !== 'function'; });" +
                        " if (missing.length > 0) { globalThis.__lnreaderDone(CID, { ok: false, error: 'missing: ' + missing.join(', ') }); }" +
                        " else { " + doneOk("'lnreader'") + " } }",
                )
                val o = runCatching { JSONObject(payload) }.getOrNull()
                if (o != null && o.optBoolean("ok", false)) "OK"
                else "ERR: " + o?.optString("error", "not an LNReader source").orEmpty().take(200)
            } finally {
                runCatching { tmp.delete() }
                runCatching { storageFile("validate").delete() }
            }
        }
}
