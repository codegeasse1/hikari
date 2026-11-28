package com.hikari.desktop.engine

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

private val gson = Gson()

/** Loads a bundled harness asset as a JsScript (blank when missing). */
fun jsAsset(path: String): JsScript = JsScript(path, EngineAssets.read(path))

fun jsInline(name: String, source: String): JsScript = JsScript(name, source)

internal const val CHEERIO_HEAD =
    "var __nuvioModule = { exports: {} }; var module = __nuvioModule; var exports = module.exports;"
internal const val CHEERIO_TAIL = "globalThis.__nuvioCheerio = module.exports;"
internal const val SKY_CHEERIO_HEAD =
    "var __skyModule = { exports: {} }; var module = __skyModule; var exports = module.exports;"
internal const val SKY_CHEERIO_TAIL = "globalThis.__skyCheerio = module.exports;"

/** Port of NuvioRuntime.REGISTER_GLUE (module registry + fetch bridge + stub). */
internal fun nuvioRegisterGlue(): String =
    "globalThis.__nuvioRegisterModule('cheerio', globalThis.__nuvioCheerio);" +
        "if (typeof globalThis.CryptoJS !== 'undefined') globalThis.__nuvioRegisterModule('crypto-js', globalThis.CryptoJS);" +
        "globalThis.__nuvioFetchImpl = function (url, method, headersJson, body, followRedirects) {" +
        "  return globalThis.__hikariFetch(String(url), String(method || 'GET'), headersJson || '{}', body == null ? '' : String(body), followRedirects !== false);" +
        "};" +
        "globalThis.__nuvioSetSettings = globalThis.__nuvioSetSettings || function (s) { globalThis.__nuvioSettingsJson = s; };" +
        "globalThis.__nuvioBridgeStub = {" +
        "  onGetStreamsDone: function (cid, payload) { globalThis.__hikariOnStreamsDone(cid, payload); }," +
        "  onSettingsDone: function (cid, payload) { globalThis.__hikariOnStreamsDone(cid, payload); }," +
        "  fetch: null," +
        "  log: function (msg) { if (typeof globalThis.__hikariLog === 'function') globalThis.__hikariLog(String(msg)); }" +
        "};"

/** Port of SoraRuntime's register block. */
internal fun soraRegisterGlue(): String =
    "globalThis.__nuvioFetchImpl = function (url, method, headersJson, body, followRedirects) {" +
        "  return globalThis.__hikariFetch(String(url), String(method || 'GET'), headersJson || '{}', body == null ? '' : String(body), followRedirects !== false);" +
        "};" +
        "globalThis.__nuvioBridgeStub = {" +
        "  onGetStreamsDone: function () {}," +
        "  onSettingsDone: function () {}," +
        "  fetch: null," +
        "  log: function (m) { if (typeof globalThis.__soraLog === 'function') globalThis.__soraLog(String(m)); }" +
        "};" +
        "globalThis.__soraSettings = globalThis.__soraSettings || '{}';" +
        "globalThis.getSetting = globalThis.getSetting || function (k, d) { try { var o = JSON.parse(globalThis.__soraSettings || '{}'); var v = o[k]; return (v === undefined || v === null) ? d : v; } catch (e) { return d; } };"

/** SkyStream provider manifest injected as `globalThis.manifest`. */
internal fun skyManifestJson(pluginId: String, manifestExtra: String?): String {
    val base = JsonObject()
    base.addProperty("id", pluginId)
    base.addProperty("name", pluginId)
    base.addProperty("version", "1.0.0")
    runCatching {
        val extra = gson.fromJson(manifestExtra ?: "{}", JsonObject::class.java)
        for ((k, v) in extra.entrySet()) base.add(k, v)
    }
    return gson.toJson(base)
}

/** Port of SkyStreamRuntime.buildCall — generic fn invocation with callback/promise support. */
internal fun skyBuildCall(fnName: String, argsJson: String): String =
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
        "    var fn = globalThis[${JavetJsHost.jsQuote(fnName)}];" +
        "    if (typeof fn !== 'function') {" +
        "      __hikariDone(JSON.stringify({success: false, errorCode: 'NO_FUNCTION', message: ${JavetJsHost.jsQuote("plugin does not export $fnName")}}));" +
        "      return;" +
        "    }" +
        "    globalThis.__skyCallDone = false;" +
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

/** Generic timer queue for engines whose harness lacks one (SkyStream-style). */
internal fun skyTimerShim(): String =
    "(function () {" +
        "  var nextId = 1; var queue = [];" +
        "  function fireTimer() {" +
        "    var now = Date.now(); var best = -1;" +
        "    queue.sort(function (a, b) { return a.at - b.at; });" +
        "    while (queue.length > 0 && queue[0].at <= now) {" +
        "      var t = queue.shift();" +
        "      if (t.dead) continue;" +
        "      try { t.fn(); } catch (e) {}" +
        "      if (t.repeat) { t.at = Date.now() + t.ms; queue.push(t); }" +
        "    }" +
        "    for (var i = 0; i < queue.length; i++) {" +
        "      if (queue[i].dead) continue;" +
        "      var wait = queue[i].at - Date.now();" +
        "      if (wait < 0) wait = 0;" +
        "      if (best < 0 || wait < best) best = wait;" +
        "    }" +
        "    return best;" +
        "  }" +
        "  globalThis.setTimeout = function (fn, ms) { var id = nextId++; queue.push({ id: id, fn: fn, at: Date.now() + (Number(ms) || 0), ms: (Number(ms) || 0), repeat: false, dead: false }); return id; };" +
        "  globalThis.clearTimeout = function (id) { for (var i = 0; i < queue.length; i++) if (queue[i].id === id && !queue[i].repeat) queue[i].dead = true; };" +
        "  globalThis.setInterval = function (fn, ms) { var id = nextId++; queue.push({ id: id, fn: fn, at: Date.now() + (Number(ms) || 0), ms: (Number(ms) || 0), repeat: true, dead: false }); return id; };" +
        "  globalThis.clearInterval = function (id) { for (var i = 0; i < queue.length; i++) if (queue[i].id === id) queue[i].dead = true; };" +
        "  globalThis.__skyFireTimer = fireTimer;" +
        "})();"

/** Nuvio streams call — exact port of NuvioRuntime.getStreams' buildCall. */
internal fun nuvioStreamsCall(
    cid: String,
    source: String,
    providerId: String,
    settings: String,
    tmdbId: String,
    mediaType: String,
    season: Int?,
    episode: Int?,
): String {
    val q = JavetJsHost::jsQuote
    val s = season?.toString() ?: "null"
    val e = episode?.toString() ?: "null"
    return "(async function () {" +
        "  try {" +
        "    globalThis.__nuvioSetSettings($settings);" +
        "    var provider = globalThis.__nuvioLoadProvider(${q(source)}, ${q(providerId)});" +
        "    var getStreams = provider && typeof provider.getStreams === 'function' ? provider.getStreams : globalThis.getStreams;" +
        "    if (typeof getStreams !== 'function') { globalThis.__nuvioBridgeStub.onGetStreamsDone(${q(cid)}, JSON.stringify({ ok: false, error: 'provider has no getStreams export' })); return; }" +
        "    var result = await getStreams(${q(tmdbId)}, ${q(mediaType)}, $s, $e);" +
        "    if (result === undefined || result === null) result = [];" +
        "    globalThis.__nuvioBridgeStub.onGetStreamsDone(${q(cid)}, JSON.stringify({ ok: true, data: result }));" +
        "  } catch (e) {" +
        "    globalThis.__nuvioBridgeStub.onGetStreamsDone(${q(cid)}, JSON.stringify({ ok: false, error: String(e && e.message || e) }));" +
        "  }" +
        "})();"
}

/** Nuvio module validation — exact port of the `validate` probe. */
internal fun nuvioValidateCall(source: String): String {
    val q = JavetJsHost::jsQuote
    return "(function () { try { var m = globalThis.__nuvioLoadProvider(${q(source)}, 'validate');" +
        " if (m && typeof m.getStreams === 'function') return 'OK'; return 'NO';" +
        " } catch (e) { return 'ERR:' + String(e && e.message || e); } })();"
}

// ---------------------------------------------------------------------------
// Lenient result parsing (gson) — every family funnels through these.
// ---------------------------------------------------------------------------

private fun JsonObject.str(vararg keys: String): String? {
    for (k in keys) {
        val v = get(k) ?: continue
        if (v.isJsonPrimitive) {
            val p = v.asJsonPrimitive
            if (p.isString) {
                val s = p.asString
                if (s.isNotBlank()) return s
            } else if (p.isNumber) return p.asString
        }
    }
    return null
}

private fun JsonObject.obj(key: String): JsonObject? {
    val v = get(key) ?: return null
    return if (v.isJsonObject) v.asJsonObject else null
}

private fun JsonObject.arr(key: String): JsonArray? {
    val v = get(key) ?: return null
    return if (v.isJsonArray) v.asJsonArray else null
}

/** Unwraps `{ok,data} / {success,data} / [...] / {streams|sources|results|list}` envelopes. */
fun unwrapData(payload: String?): Pair<Boolean, JsonElement?> {
    if (payload.isNullOrBlank()) return false to null
    val root = runCatching { gson.fromJson(payload, JsonElement::class.java) }.getOrNull()
        ?: return false to null
    if (root.isJsonArray) return true to root
    if (!root.isJsonObject) {
        return if (root.isJsonPrimitive) true to root else false to null
    }
    val o = root.asJsonObject
    val ok = when {
        o.has("ok") -> o.get("ok").takeIf { it.isJsonPrimitive }?.asBoolean ?: false
        o.has("success") -> o.get("success").takeIf { it.isJsonPrimitive }?.asBoolean ?: false
        else -> true
    }
    val data = o.get("data")
        ?: o.get("streams")
        ?: o.get("sources")
        ?: o.get("results")
        ?: o.get("list")
        ?: o.get("videos")
    if (data == null || (data.isJsonNull)) return ok to null
    return ok to data
}

fun envelopeError(payload: String?): String? {
    if (payload.isNullOrBlank()) return "empty engine answer"
    val root = runCatching { gson.fromJson(payload, JsonElement::class.java) }.getOrNull()
    if (root == null || !root.isJsonObject) return null
    val o = root.asJsonObject
    val err = o.str("error", "message", "errorMessage")
    if (err != null) return err.take(200)
    if (o.has("ok") && !(o.get("ok")?.asBoolean ?: true)) return "provider reported failure"
    if (o.has("success") && !(o.get("success")?.asBoolean ?: true)) return "provider reported failure"
    return null
}

private fun headersFrom(el: JsonElement?): Map<String, String> {
    if (el == null || el.isJsonNull) return emptyMap()
    if (el.isJsonObject) {
        val out = LinkedHashMap<String, String>()
        for ((k, v) in el.asJsonObject.entrySet()) {
            if (v.isJsonPrimitive) out[k] = v.asString
        }
        return out
    }
    return emptyMap()
}

private fun subtitlesFrom(el: JsonElement?): List<EngineSubtitle> {
    if (el == null || el.isJsonNull) return emptyList()
    if (!el.isJsonArray) return emptyList()
    return el.asJsonArray.mapNotNull { t ->
        if (!t.isJsonObject) return@mapNotNull null
        val o = t.asJsonObject
        val url = o.str("url", "file", "src", "link") ?: return@mapNotNull null
        val label = o.str("label", "lang", "language", "name") ?: "subtitle"
        EngineSubtitle(label, url)
    }
}

/** One stream item in any family's clothing. */
fun streamFromJson(o: JsonObject, providerId: String, fallbackName: String): EngineStream? {
    val url = o.str("url", "file", "src", "link", "href", "streamUrl", "videoUrl", "stream", "source")
        ?: return null
    if (url.isBlank() || url == "#" || url.startsWith("javascript:", true)) return null
    val name = o.str("name", "title", "label", "server", "serverName", "source", "provider", "qualityName")
        ?: fallbackName
    val quality = o.str("quality", "resolution", "label2")
    val headers = headersFrom(o.get("headers") ?: o.get("httpHeaders") ?: o.get("requestHeaders"))
    val subs = subtitlesFrom(o.get("subtitles") ?: o.get("captions") ?: o.get("tracks") ?: o.get("subs"))
    return EngineStream(providerId, name, url.trim(), headers, quality, subs)
}

/** A bare string is a URL-only stream (Sora sometimes answers plain links). */
fun streamFromString(url: String, providerId: String, fallbackName: String): EngineStream? {
    val u = url.trim().trim('"')
    if (u.isBlank() || !(u.startsWith("http", true) || u.startsWith("magnet:", true))) return null
    return EngineStream(providerId, fallbackName, u)
}

fun streamsFromData(data: JsonElement?, providerId: String, fallbackName: String): List<EngineStream> {
    if (data == null || data.isJsonNull) return emptyList()
    if (data.isJsonPrimitive) {
        val s = data.asString
        return listOfNotNull(streamFromString(s, providerId, fallbackName))
    }
    if (data.isJsonObject) {
        val o = data.asJsonObject
        // Single object that IS a stream.
        val direct = runCatching { streamFromJson(o, providerId, fallbackName) }.getOrNull()
        if (direct != null) return listOf(direct)
        // Nested lists.
        for (k in listOf("streams", "sources", "results", "list", "videos", "data", "items")) {
            val nested = o.get(k)
            if (nested != null && nested.isJsonArray) {
                return streamsFromData(nested, providerId, fallbackName)
            }
        }
        return emptyList()
    }
    return data.asJsonArray.mapNotNull { el ->
        when {
            el.isJsonObject -> runCatching {
                streamFromJson(el.asJsonObject, providerId, fallbackName)
            }.getOrNull()
            el.isJsonPrimitive -> runCatching {
                streamFromString(el.asString, providerId, fallbackName)
            }.getOrNull()
            else -> null
        }
    }
}

/** One media item (search/catalog row) in any family's clothing. */
fun mediaFromJson(o: JsonObject, providerId: String, defaultKind: EngineMediaKind): EngineMedia? {
    val title = o.str("title", "name", "label") ?: return null
    if (title.isBlank()) return null
    val id = o.str("id", "url", "link", "href") ?: return null
    if (id.isBlank()) return null
    val typeRaw = (o.str("type", "mediaType", "media_type") ?: "").lowercase()
    val kind = when {
        typeRaw.contains("tv") || typeRaw.contains("series") || typeRaw.contains("show") || typeRaw.contains("anime") -> EngineMediaKind.SERIES
        typeRaw.contains("movie") || typeRaw.contains("film") -> EngineMediaKind.MOVIE
        else -> defaultKind
    }
    val poster = o.str("poster", "posterUrl", "poster_url", "image", "cover", "coverUrl", "thumbnail", "thumbnailUrl", "imageUrl", "img", "artwork", "posterPath")
    val backdrop = o.str("backdrop", "backdropUrl", "backdrop_url", "banner", "fanart", "cover", "background")
    val overview = o.str("overview", "description", "plot", "synopsis", "summary")
    val year = o.str("year", "releaseYear")?.take(4)?.toIntOrNull()
        ?: o.str("releaseDate", "release_date", "firstAirDate", "first_air_date", "airDate")?.take(4)?.toIntOrNull()
    val rating = o.str("rating", "vote", "voteAverage", "vote_average", "score")?.toDoubleOrNull()
    val genres = when (val g = o.get("genres") ?: o.get("genre")) {
        null -> emptyList()
        else -> when {
            g.isJsonArray -> g.asJsonArray.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() } }
            g.isJsonPrimitive -> g.asString.split(",", "/", "|").map { it.trim() }.filter { it.isNotBlank() }
            else -> emptyList()
        }
    }
    return EngineMedia(providerId, id, title, kind, poster, backdrop, overview, year, rating, genres)
}

fun mediaListFromData(data: JsonElement?, providerId: String, defaultKind: EngineMediaKind): List<EngineMedia> {
    if (data == null || data.isJsonNull) return emptyList()
    val arr = when {
        data.isJsonArray -> data.asJsonArray
        data.isJsonObject -> {
            val o = data.asJsonObject
            val nested = o.get("results") ?: o.get("list") ?: o.get("items") ?: o.get("data")
            if (nested != null && nested.isJsonArray) nested.asJsonArray else return emptyList()
        }
        else -> return emptyList()
    }
    return arr.mapNotNull { el ->
        if (!el.isJsonObject) return@mapNotNull null
        runCatching { mediaFromJson(el.asJsonObject, providerId, defaultKind) }.getOrNull()
    }
}

/** One episode in any family's clothing. */
fun episodeFromJson(
    o: JsonObject,
    providerId: String,
    mediaId: String,
    indexFallback: Int,
): EngineEpisode? {
    val id = o.str("id", "url", "link", "href") ?: return null
    if (id.isBlank()) return null
    val title = o.str("title", "name", "label") ?: "Episode ${indexFallback + 1}"
    val season = o.str("season", "seasonNumber", "s")?.toIntOrNull() ?: 1
    val number = o.str("episode", "episodeNumber", "number", "e", "ep")?.toIntOrNull()
        ?: Regex("""[Ee](\d{1,3})""").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?: (indexFallback + 1)
    val overview = o.str("overview", "description", "plot")
    return EngineEpisode(providerId, mediaId, id, season, number, title, overview)
}

fun episodesFromData(
    data: JsonElement?,
    providerId: String,
    mediaId: String,
): List<EngineEpisode> {
    if (data == null || data.isJsonNull) return emptyList()
    val arr = when {
        data.isJsonArray -> data.asJsonArray
        data.isJsonObject -> {
            val o = data.asJsonObject
            val nested = o.get("episodes") ?: o.get("list") ?: o.get("items") ?: o.get("data") ?: o.get("results")
            if (nested != null && nested.isJsonArray) nested.asJsonArray else return emptyList()
        }
        else -> return emptyList()
    }
    return arr.mapIndexedNotNull { i, el ->
        if (!el.isJsonObject) return@mapIndexedNotNull null
        runCatching { episodeFromJson(el.asJsonObject, providerId, mediaId, i) }.getOrNull()
    }.sortedWith(compareBy({ it.season }, { it.number }))
}
