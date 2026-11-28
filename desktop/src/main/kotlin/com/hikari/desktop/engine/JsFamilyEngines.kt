package com.hikari.desktop.engine

import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The other JS extension families, all hosted on the shared V8 runtime.
 * Entries are exact ports of each family's Android runtime; unknown edges
 * fall back to first-ok candidate lists (see FamilySpecs lenient parsers).
 */
private abstract class BaseJsEngine(
    private val familyKind: EngineKind,
    private val host: JavetJsHost,
    private val startTimeoutMs: Long = 75_000,
) : Engine {
    override val kind: EngineKind = familyKind

    fun dir(): File = File(NuvioEngine.homeDir(), "engines/${familyKind.prefix}").apply { mkdirs() }

    fun fileFor(providerId: String): File =
        File(dir(), providerId.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".js")

    fun readSource(provider: EngineProvider): String? =
        runCatching { fileFor(provider.id).readText() }.getOrNull()?.takeIf { it.isNotBlank() }

    data class CallOutcome(val ok: Boolean, val data: com.google.gson.JsonElement?, val raw: String?, val error: String?)

    suspend fun runCall(
        provider: EngineProvider,
        scripts: List<JsScript>,
        startEval: String,
        pumpEval: String?,
        timeoutMs: Long = startTimeoutMs,
    ): CallOutcome = withContext(Dispatchers.IO) {
        val result = host.execute(
            JsRun(scripts, startEval, pumpEval, timeoutMs),
            pluginId = provider.id,
        )
        if (!result.ok || result.payload.isNullOrBlank()) {
            return@withContext CallOutcome(false, null, result.payload, result.error ?: "engine gave no answer")
        }
        val (ok, data) = unwrapData(result.payload)
        if (!ok) return@withContext CallOutcome(false, null, result.payload, envelopeError(result.payload))
        CallOutcome(true, data, result.payload, null)
    }
}

// ---------------------------------------------------------------------------
// Anymex (mangayomi-format .js; getPopular/getLatestUpdates/search/getVideoList)
// ---------------------------------------------------------------------------

class AnymexEngine(host: JavetJsHost = JavetJsHost()) : BaseJsEngine(EngineKind.ANYMEX, host) {

    private fun scripts(): List<JsScript> = listOf(
        jsAsset("nuvio/boot.js"),
        jsInline("cheerio-head.js", CHEERIO_HEAD),
        jsAsset("nuvio/cheerio.js"),
        jsInline("cheerio-tail.js", CHEERIO_TAIL),
        jsAsset("nuvio/harness.js"),
        jsAsset("anymex/harness.js"),
        jsInline("anymex-register.js", anymexGlue()),
    )

    private fun anymexGlue(): String =
        "globalThis.__nuvioFetchImpl = function (url, method, headersJson, body, followRedirects) {" +
            "  return globalThis.__hikariFetch(String(url), String(method || 'GET'), headersJson || '{}', body == null ? '' : String(body), followRedirects !== false);" +
            "};" +
            "globalThis.__anymexSettings = '{}';"

    override suspend fun catalogs(provider: EngineProvider): List<EngineCatalog> = listOf(
        EngineCatalog(provider.id, "popular", "Popular", EngineMediaKind.SERIES),
        EngineCatalog(provider.id, "latest", "Latest Updates", EngineMediaKind.SERIES),
    )

    override suspend fun catalog(
        provider: EngineProvider,
        catalog: EngineCatalog,
        page: Int,
    ): List<EngineMedia> {
        if (readSource(provider) == null) return emptyList()
        val fn = if (catalog.id == "latest") "getLatestUpdates" else "getPopular"
        val out = runCall(
            provider, scripts(),
            "__anymexCall(${JavetJsHost.jsQuote(fn)}, ${JavetJsHost.jsQuote("[$page]")});",
            "__anymexFireTimer()",
        )
        return mediaListFromData(out.data, provider.id, EngineMediaKind.SERIES)
    }

    override suspend fun search(provider: EngineProvider, query: String, page: Int): List<EngineMedia> {
        if (query.isBlank() || readSource(provider) == null) return emptyList()
        val attempts = listOf(
            "[$page, ${JavetJsHost.jsQuote(query)}, []]",
            "[${JavetJsHost.jsQuote(query)}, $page, []]",
            "[${JavetJsHost.jsQuote(query)}]",
        )
        for (args in attempts) {
            val out = runCall(
                provider, scripts(),
                "__anymexCall(\"search\", ${JavetJsHost.jsQuote(args)});",
                "__anymexFireTimer()", 30_000,
            )
            if (out.ok && out.data != null) {
                val items = mediaListFromData(out.data, provider.id, EngineMediaKind.SERIES)
                if (items.isNotEmpty()) return items
                // A clean empty search is a real answer; stop trying shapes.
                if (out.error == null) return emptyList()
            }
        }
        return emptyList()
    }

    override suspend fun streams(
        provider: EngineProvider,
        media: EngineMedia,
        episode: EngineEpisode?,
    ): List<EngineStream> {
        val source = readSource(provider) ?: return emptyList()
        val epUrl = episode?.id?.takeIf { it.isNotBlank() } ?: media.id
        if (epUrl.isBlank()) return emptyList()
        val argsJson = "[" + JavetJsHost.jsQuote(epUrl) + "]"
        val out = runCall(
            provider, scripts() + jsInline("anymex-source.js", source),
            "__anymexCall(\"getVideoList\", " + JavetJsHost.jsQuote(argsJson) + ");",
            "__anymexFireTimer()",
        )
        if (!out.ok || out.data == null) return emptyList()
        return streamsFromData(out.data, provider.id, provider.name).distinctBy { it.url }
    }

    companion object {
        val DEFAULT_REPOS = listOf(
            Triple(
                "https://raw.githubusercontent.com/gato404/kegareta-sauces/main/anime_index.json",
                "Kegareta Anime",
                "Anymex/Mangayomi JS anime extensions (kegareta-sauces)",
            ),
            Triple(
                "https://raw.githubusercontent.com/gato404/kegareta-sauces/main/index.json",
                "Kegareta Manga",
                "Anymex/Mangayomi JS manga extensions (kegareta-sauces)",
            ),
        )
    }
}

// ---------------------------------------------------------------------------
// Sora (searchResults / extractDetails / extractEpisodes / extractStreamUrl)
// ---------------------------------------------------------------------------

class SoraEngine(host: JavetJsHost = JavetJsHost()) : BaseJsEngine(EngineKind.SORA, host) {

    private fun scripts(): List<JsScript> = listOf(
        jsAsset("nuvio/boot.js"),
        jsAsset("nuvio/harness.js"),
        jsInline("sora-register.js", soraRegisterGlue()),
        jsAsset("sora/harness.js"),
    )

    private fun call(fn: String, argsJson: String): String =
        "__soraCall(${JavetJsHost.jsQuote(fn)}, ${JavetJsHost.jsQuote(argsJson)});\n;void 0;"

    override suspend fun search(provider: EngineProvider, query: String, page: Int): List<EngineMedia> {
        if (query.isBlank() || readSource(provider) == null) return emptyList()
        val args = "[${JavetJsHost.jsQuote(query)}, ${page.coerceAtLeast(1)}, []]"
        val out = runCall(provider, scripts() + sourceScript(provider), call("searchResults", args), "__soraFireTimer()")
        return mediaListFromData(out.data, provider.id, EngineMediaKind.SERIES)
    }

    override suspend fun meta(provider: EngineProvider, media: EngineMedia): EngineMedia {
        if (readSource(provider) == null) return media
        val args = "[${JavetJsHost.jsQuote(media.id)}]"
        val out = runCall(provider, scripts() + sourceScript(provider), call("extractDetails", args), "__soraFireTimer()", 45_000)
        val (ok, data) = unwrapData(out.raw)
        if (!ok || data == null || !data.isJsonObject) return media
        val o = data.asJsonObject
        val title = o.get("title")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
        val desc = o.get("description")?.takeIf { it.isJsonPrimitive }?.asString
            ?: o.get("overview")?.takeIf { it.isJsonPrimitive }?.asString
        val poster = o.get("poster")?.takeIf { it.isJsonPrimitive }?.asString
            ?: o.get("image")?.takeIf { it.isJsonPrimitive }?.asString
        return media.copy(
            title = title ?: media.title,
            overview = desc?.takeIf { it.isNotBlank() } ?: media.overview,
            posterUrl = poster?.takeIf { it.isNotBlank() } ?: media.posterUrl,
        )
    }

    override suspend fun episodes(provider: EngineProvider, media: EngineMedia): List<EngineEpisode> {
        if (readSource(provider) == null) return emptyList()
        val args = "[${JavetJsHost.jsQuote(media.id)}]"
        val out = runCall(provider, scripts() + sourceScript(provider), call("extractEpisodes", args), "__soraFireTimer()")
        return episodesFromData(out.data, provider.id, media.id)
    }

    override suspend fun streams(
        provider: EngineProvider,
        media: EngineMedia,
        episode: EngineEpisode?,
    ): List<EngineStream> {
        val link = episode?.id?.takeIf { it.isNotBlank() } ?: media.id
        if (link.isBlank() || readSource(provider) == null) return emptyList()
        val args = "[${JavetJsHost.jsQuote(link)}]"
        val out = runCall(
            provider, scripts() + sourceScript(provider),
            call("extractStreamUrl", args), "__soraFireTimer()", 30_000,
        )
        if (!out.ok || out.data == null) return emptyList()
        return streamsFromData(out.data, provider.id, provider.name).distinctBy { it.url }
    }

    private fun sourceScript(provider: EngineProvider): JsScript =
        JsScript("sora-module.js", readSource(provider) ?: "")
}

// ---------------------------------------------------------------------------
// SkyStream (getHome / search / load / loadStreams via buildCall port)
// ---------------------------------------------------------------------------

class SkyStreamEngine(host: JavetJsHost = JavetJsHost()) : BaseJsEngine(EngineKind.SKYSTREAM, host) {

    private fun scripts(pluginId: String, manifestExtra: String?): List<JsScript> = listOf(
        jsInline("skystream-timers.js", skyTimerShim()),
        jsInline("cheerio-head.js", SKY_CHEERIO_HEAD),
        jsAsset("nuvio/cheerio.js"),
        jsInline("cheerio-tail.js", SKY_CHEERIO_TAIL),
        jsAsset("skystream/shim.js"),
        jsInline("manifest.js", "globalThis.manifest = ${skyManifestJson(pluginId, manifestExtra)};"),
        jsInline("skystream-glue.js", skyGlue()),
    )

    private fun skyGlue(): String =
        "globalThis.__hikariFetchImpl = globalThis.__hikariFetchImpl || function (url, method, headersJson, body, followRedirects) {" +
            "  return globalThis.__hikariFetch(String(url), String(method || 'GET'), headersJson || '{}', body == null ? '' : String(body), followRedirects !== false);" +
            "};"

    private fun sourceScript(provider: EngineProvider): JsScript =
        JsScript("skystream-plugin.js", readSource(provider) ?: "")

    private fun manifestExtra(provider: EngineProvider): String? {
        val f = File(fileFor(provider.id).parentFile, fileFor(provider.id).nameWithoutExtension + ".manifest.json")
        return runCatching { f.takeIf { it.exists() }?.readText() }.getOrNull()
    }

    private suspend fun invoke(
        provider: EngineProvider,
        fn: String,
        argsJson: String,
        timeoutMs: Long = 60_000,
    ): CallOutcome {
        val scripts = scripts(provider.id, manifestExtra(provider)) + sourceScript(provider)
        val out = runCall(provider, scripts, skyBuildCall(fn, argsJson), "__skyFireTimer()", timeoutMs)
        // buildCall answers {success, data|errorCode+message}; normalize.
        if (!out.ok) return out
        return out
    }

    override suspend fun catalogs(provider: EngineProvider): List<EngineCatalog> {
        if (readSource(provider) == null) return emptyList()
        val out = invoke(provider, "getHome", "[]", 75_000)
        // getHome answers rows; each row becomes a shelf when it carries items.
        val data = out.data
        if (data == null || !data.isJsonArray) {
            return listOf(EngineCatalog(provider.id, "home", "Home", EngineMediaKind.SERIES))
        }
        val rows = data.asJsonArray.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }
        if (rows.isEmpty()) return listOf(EngineCatalog(provider.id, "home", "Home", EngineMediaKind.SERIES))
        return rows.mapIndexed { i, r ->
            val title = r.get("title")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
                ?: "Row ${i + 1}"
            EngineCatalog(provider.id, "home:$i", title, EngineMediaKind.SERIES)
        }
    }

    override suspend fun catalog(
        provider: EngineProvider,
        catalog: EngineCatalog,
        page: Int,
    ): List<EngineMedia> {
        if (readSource(provider) == null) return emptyList()
        if (page > 1) return emptyList()
        val out = invoke(provider, "getHome", "[]", 75_000)
        val data = out.data
        if (data == null || !data.isJsonArray) return emptyList()
        val idx = catalog.id.substringAfter("home:", "").toIntOrNull()
        val pool = if (idx == null) {
            data.asJsonArray.flatMap { row ->
                row.takeIf { it.isJsonObject }?.asJsonObject?.get("items")?.takeIf { it.isJsonArray }?.asJsonArray?.toList()
                    ?: row.takeIf { it.isJsonObject }?.asJsonObject?.get("list")?.takeIf { it.isJsonArray }?.asJsonArray?.toList()
                    ?: emptyList()
            }
        } else {
            val row = data.asJsonArray.getOrNull(idx)?.takeIf { it.isJsonObject }?.asJsonObject
                ?: return emptyList()
            (row.get("items")?.takeIf { it.isJsonArray }?.asJsonArray?.toList()
                ?: row.get("list")?.takeIf { it.isJsonArray }?.asJsonArray?.toList()
                ?: emptyList())
        }
        return pool.mapNotNull { el ->
            if (!el.isJsonObject) return@mapNotNull null
            runCatching { mediaFromJson(el.asJsonObject, provider.id, EngineMediaKind.SERIES) }.getOrNull()
        }
    }

    override suspend fun search(provider: EngineProvider, query: String, page: Int): List<EngineMedia> {
        if (query.isBlank() || readSource(provider) == null) return emptyList()
        if (page > 1) return emptyList()
        val out = invoke(provider, "search", "[${JavetJsHost.jsQuote(query)}]")
        return mediaListFromData(out.data, provider.id, EngineMediaKind.SERIES)
    }

    override suspend fun meta(provider: EngineProvider, media: EngineMedia): EngineMedia {
        if (readSource(provider) == null) return media
        val out = invoke(provider, "load", "[${JavetJsHost.jsQuote(media.id)}]", 45_000)
        val data = out.data
        if (data == null || !data.isJsonObject) return media
        val found = mediaFromJson(data.asJsonObject, provider.id, media.kind) ?: return media
        return media.copy(
            title = found.title,
            overview = found.overview ?: media.overview,
            posterUrl = found.posterUrl ?: media.posterUrl,
            backdropUrl = found.backdropUrl ?: media.backdropUrl,
            year = found.year ?: media.year,
            rating = found.rating ?: media.rating,
            genres = found.genres.ifEmpty { media.genres },
        )
    }

    override suspend fun streams(
        provider: EngineProvider,
        media: EngineMedia,
        episode: EngineEpisode?,
    ): List<EngineStream> {
        val token = episode?.id?.takeIf { it.isNotBlank() } ?: media.id
        if (token.isBlank() || readSource(provider) == null) return emptyList()
        val out = invoke(provider, "loadStreams", "[${JavetJsHost.jsQuote(token)}]", 45_000)
        if (!out.ok || out.data == null) return emptyList()
        // streamsForToken shape: array, or {streams|sources}.
        val data = out.data!!
        val arr = when {
            data.isJsonArray -> data
            data.isJsonObject -> {
                val o = data.asJsonObject
                o.getAsJsonArray("streams") ?: o.getAsJsonArray("sources") ?: return emptyList()
            }
            else -> return emptyList()
        }
        return streamsFromData(arr, provider.id, provider.name).distinctBy { it.url }
    }
}

// ---------------------------------------------------------------------------
// Zangetsu (__zCall("search"|"getVideoSources", ...))
// ---------------------------------------------------------------------------

class ZangetsuEngine(host: JavetJsHost = JavetJsHost()) : BaseJsEngine(EngineKind.ZANGETSU, host) {

    private fun scripts(): List<JsScript> = listOf(
        jsAsset("nuvio/boot.js"),
        jsInline("cheerio-head.js", CHEERIO_HEAD),
        jsAsset("nuvio/cheerio.js"),
        jsInline("cheerio-tail.js", CHEERIO_TAIL),
        jsAsset("nuvio/harness.js"),
        jsInline("zangetsu-register.js", zangetsuGlue()),
        jsInline("zangetsu-call.js", zCallDef()),
    )

    private fun zangetsuGlue(): String =
        "globalThis.__nuvioFetchImpl = function (url, method, headersJson, body, followRedirects) {" +
            "  return globalThis.__hikariFetch(String(url), String(method || 'GET'), headersJson || '{}', body == null ? '' : String(body), followRedirects !== false);" +
            "};" +
            "globalThis.__zSettings = '{}';"

    /** Port of ZangetsuRuntime's embedded `__zCall` definition. */
    private fun zCallDef(): String =
        "globalThis.__zCall = function(fn, argsJson) {" +
            "  var args = [];" +
            "  try { args = JSON.parse(argsJson); } catch (e) {" +
            "    __zDone(JSON.stringify({ ok: false, error: 'bad args' }));" +
            "    return;" +
            "  }" +
            "  var f = globalThis[fn];" +
            "  if (typeof f !== 'function') {" +
            "    __zDone(JSON.stringify({ ok: false, error: 'no such function: ' + fn }));" +
            "    return;" +
            "  }" +
            "  var done = function(v) {" +
            "    var s;" +
            "    try { s = JSON.stringify({ ok: true, data: (typeof v === 'undefined' ? null : v) }); }" +
            "    catch (e) { s = JSON.stringify({ ok: false, error: 'unserializable result' }); }" +
            "    __zDone(s);" +
            "  };" +
            "  var fail = function(e) {" +
            "    var m = 'failed';" +
            "    try { m = String((e && e.message) || e); } catch (x) {}" +
            "    __zDone(JSON.stringify({ ok: false, error: m }));" +
            "  };" +
            "  try {" +
            "    var r = f.apply(null, args);" +
            "    if (r && typeof r.then === 'function') r.then(done, fail);" +
            "    else done(r);" +
            "  } catch (e) { fail(e); }" +
            "};"

    override suspend fun catalogs(provider: EngineProvider): List<EngineCatalog> {
        if (readSource(provider) == null) return emptyList()
        // Home rows entry varies by provider build; try the known spellings.
        for (fn in listOf("home", "getHome", "homeRows")) {
            val out = runCall(
                provider, scripts() + JsScript("zangetsu-source.js", readSource(provider) ?: ""),
                "__zCall(${JavetJsHost.jsQuote(fn)}, ${JavetJsHost.jsQuote("[]")});",
                null, 45_000,
            )
            if (out.ok && out.data != null) {
                val rows = when {
                    out.data!!.isJsonArray -> out.data!!.asJsonArray.mapNotNull {
                        it.takeIf { e -> e.isJsonObject }?.asJsonObject
                    }
                    else -> emptyList()
                }
                if (rows.isNotEmpty()) {
                    return rows.mapIndexed { i, r ->
                        val title = r.get("title")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
                            ?: "Row ${i + 1}"
                        EngineCatalog(provider.id, "row:$i:$title", title, EngineMediaKind.SERIES)
                    }
                }
                return listOf(EngineCatalog(provider.id, "home", "Home", EngineMediaKind.SERIES))
            }
        }
        return listOf(EngineCatalog(provider.id, "home", "Home", EngineMediaKind.SERIES))
    }

    override suspend fun catalog(
        provider: EngineProvider,
        catalog: EngineCatalog,
        page: Int,
    ): List<EngineMedia> {
        if (readSource(provider) == null || page > 1) return emptyList()
        for (fn in listOf("home", "getHome", "homeRows")) {
            val out = runCall(
                provider, scripts() + JsScript("zangetsu-source.js", readSource(provider) ?: ""),
                "__zCall(${JavetJsHost.jsQuote(fn)}, ${JavetJsHost.jsQuote("[]")});",
                null, 45_000,
            )
            if (!out.ok || out.data == null) continue
            val rows = if (out.data!!.isJsonArray) {
                out.data!!.asJsonArray.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }
            } else emptyList()
            val title = catalog.id.substringAfter("row:", "").substringBeforeLast(":").ifBlank { null }
                ?: catalog.id.substringAfterLast(":")
            val pool = if (catalog.id == "home" || title == "home") {
                rows.flatMap { r ->
                    (r.getAsJsonArray("items") ?: r.getAsJsonArray("list"))?.toList() ?: emptyList()
                }
            } else {
                val row = rows.firstOrNull {
                    it.get("title")?.takeIf { e -> e.isJsonPrimitive }?.asString == title
                } ?: continue
                ((row.getAsJsonArray("items") ?: row.getAsJsonArray("list"))?.toList() ?: emptyList())
            }
            return pool.mapNotNull { el ->
                if (!el.isJsonObject) return@mapNotNull null
                runCatching { mediaFromJson(el.asJsonObject, provider.id, EngineMediaKind.SERIES) }.getOrNull()
            }
        }
        return emptyList()
    }

    override suspend fun search(provider: EngineProvider, query: String, page: Int): List<EngineMedia> {
        if (query.isBlank() || readSource(provider) == null) return emptyList()
        val args = "[${JavetJsHost.jsQuote(query)}, ${page.coerceAtLeast(1)}, {}]"
        val out = runCall(
            provider, scripts() + JsScript("zangetsu-source.js", readSource(provider) ?: ""),
            "__zCall(\"search\", ${JavetJsHost.jsQuote(args)});",
            null,
        )
        return mediaListFromData(out.data, provider.id, EngineMediaKind.SERIES)
    }

    override suspend fun streams(
        provider: EngineProvider,
        media: EngineMedia,
        episode: EngineEpisode?,
    ): List<EngineStream> {
        val epUrl = episode?.id?.takeIf { it.isNotBlank() } ?: media.id
        if (epUrl.isBlank() || readSource(provider) == null) return emptyList()
        val args = "[${JavetJsHost.jsQuote(epUrl)}]"
        val out = runCall(
            provider, scripts() + JsScript("zangetsu-source.js", readSource(provider) ?: ""),
            "__zCall(\"getVideoSources\", ${JavetJsHost.jsQuote(args)});",
            null, 45_000,
        )
        if (!out.ok || out.data == null) return emptyList()
        return streamsFromData(out.data, provider.id, provider.name).distinctBy { it.url }
    }
}

// ---------------------------------------------------------------------------
// Vega (__vegaCall("search"|"getStreams"|..., {..}) + providerContext)
// ---------------------------------------------------------------------------

class VegaEngine(host: JavetJsHost = JavetJsHost()) : BaseJsEngine(EngineKind.VEGA, host) {

    private fun scripts(): List<JsScript> = listOf(
        jsAsset("nuvio/boot.js"),
        jsInline("cheerio-head.js", CHEERIO_HEAD),
        jsAsset("nuvio/cheerio.js"),
        jsInline("cheerio-tail.js", CHEERIO_TAIL),
        jsAsset("nuvio/harness.js"),
        jsAsset("vega/commonHeaders.js"),
        jsAsset("vega/harness.js"),
        jsInline("vega-register.js", vegaGlue()),
    )

    private fun vegaGlue(): String =
        "globalThis.__nuvioFetchImpl = function (url, method, headersJson, body, followRedirects) {" +
            "  return globalThis.__hikariFetch(String(url), String(method || 'GET'), headersJson || '{}', body == null ? '' : String(body), followRedirects !== false);" +
            "};" +
            "globalThis.__vegaProviderContext = {};" +
            "globalThis.__vegaProviderValue = undefined;"

    private suspend fun vegaCall(
        provider: EngineProvider,
        fn: String,
        argsJson: String,
        timeoutMs: Long = 60_000,
    ): CallOutcome {
        val source = readSource(provider) ?: return CallOutcome(false, null, null, "script missing")
        return runCall(
            provider, scripts() + JsScript("vega-source.js", source),
            "__vegaCall(${JavetJsHost.jsQuote(fn)}, ${JavetJsHost.jsQuote(argsJson)});",
            null, timeoutMs,
        )
    }

    override suspend fun search(provider: EngineProvider, query: String, page: Int): List<EngineMedia> {
        if (query.isBlank()) return emptyList()
        val args = "{\"query\":${JavetJsHost.jsQuote(query)},\"page\":${page.coerceAtLeast(1)}}"
        val out = vegaCall(provider, "search", args)
        return mediaListFromData(out.data, provider.id, EngineMediaKind.SERIES)
    }

    override suspend fun streams(
        provider: EngineProvider,
        media: EngineMedia,
        episode: EngineEpisode?,
    ): List<EngineStream> {
        val link = episode?.id?.takeIf { it.isNotBlank() } ?: media.id
        if (link.isBlank()) return emptyList()
        val id = UUID.randomUUID().toString()
        for (fn in listOf("getStreams", "loadStreams", "getVideoList")) {
            val args = "{\"link\":${JavetJsHost.jsQuote(link)}," +
                "\"url\":${JavetJsHost.jsQuote(link)}," +
                "\"id\":${JavetJsHost.jsQuote(id)}," +
                "\"title\":${JavetJsHost.jsQuote(media.title)}}"
            val out = vegaCall(provider, fn, args, 45_000)
            if (out.ok && out.data != null) {
                val streams = streamsFromData(out.data, provider.id, provider.name)
                if (streams.isNotEmpty()) return streams.distinctBy { it.url }
            }
        }
        return emptyList()
    }
}
