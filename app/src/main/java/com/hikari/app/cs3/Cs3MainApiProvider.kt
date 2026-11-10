package com.hikari.app.cs3

import com.hikari.app.HikariApp
import com.hikari.app.data.CatalogRef
import com.hikari.app.data.DrmSpec
import com.hikari.app.data.Episode
import com.hikari.app.data.MediaItem
import com.hikari.app.data.MediaType
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.TitleQuality
import com.hikari.app.data.StreamSource
import com.hikari.app.data.SubtitleSource
import com.hikari.app.net.Http
import com.hikari.app.providers.ContentProvider
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.fixUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONObject

/**
 * Adapts a loaded CloudStream MainAPI to Hikari's ContentProvider contract so
 * .cs3 plugins appear in Home/Search/Detail/Player like any other provider.
 */
class Cs3MainApiProvider(override val config: ProviderConfig) : ContentProvider {

    companion object {
        val streamErrors = java.util.concurrent.ConcurrentHashMap<String, String>()

        val videoInterceptors = ConcurrentHashMap<String, okhttp3.Interceptor>()

        /** The cached video interceptor for [streamUrl], if its provider declared one. */
        fun videoInterceptorFor(streamUrl: String): okhttp3.Interceptor? =
            videoInterceptors[streamUrl]

        /** How long a cached load() stays usable (CloudStream: 10 minutes). */
        const val LOAD_CACHE_TTL_MS = 10 * 60 * 1000L

        /** Hard cap on cached load() entries per provider (CloudStream: 20). */
        const val LOAD_CACHE_MAX = 20

        /**
         * Hosts whose CDN hotlink check REQUIRES a Referer (Fastream is the
         * classic: `s40.fastream.to` 403s a bare request while the same URL
         * plays with `Referer: https://fastream.to/`). The player's generic
         * header walk (full → no-Referer → none) must NEVER strip Referer for
         * these — stripping is what turns one terminal 403 into three.
         */
        fun isRefererMandatory(url: String): Boolean {
            val host = url.substringAfter("://").substringBefore('/').substringBefore('?').lowercase()
            if (host.isBlank()) return false
            return host.contains("fastream") || host.contains("streamwish") ||
                host.contains("wish") || host.contains("vidhide") ||
                host.contains("filemoon") || host.contains("streamtape") ||
                host.contains("hsxcon") || host.contains("hsx")
        }

        suspend fun warmPlaybackSession(streamUrl: String, headers: Map<String, String>) {
            try {
                val ref = headers.entries.firstOrNull { it.key.equals("Referer", true) }?.value
                    ?.takeIf { it.startsWith("http") }
                    ?: when {
                        streamUrl.contains("fastream", true) -> "https://fastream.to"
                        else -> null
                    } ?: return
                withTimeoutOrNull(8_000) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching {
                            val client = cs3BaseClient()
                            val req = okhttp3.Request.Builder().url(ref)
                                .header("User-Agent", com.hikari.app.net.Http.UA)
                                .header("Referer", ref)
                                .build()
                            client.newCall(req).execute().use { }
                        }
                    }
                }
            } catch (_: Throwable) { }
        }

        fun cs3BaseClient(): okhttp3.OkHttpClient {
            return runCatching {
                val kt = Class.forName("com.lagradost.cloudstream3.MainActivityKt")
                val req = kt.getMethod("getApp").invoke(null) ?: return@runCatching null
                val base = runCatching {
                    req.javaClass.methods.firstOrNull { it.name == "getBaseClient" }?.invoke(req)
                }.getOrNull() ?: runCatching {
                    req.javaClass.getField("baseClient").get(req)
                }.getOrNull()
                base as? okhttp3.OkHttpClient
            }.getOrNull() ?: com.hikari.app.net.PlayerHttp.plainClient
        }

        /** How long the last loadLinks attempt took (ms) — proof the UI did something. */
        @Volatile
        var lastStreamsTimeMs: Long = 0L

        private val partialServers = java.util.concurrent.ConcurrentHashMap<String, List<com.hikari.app.data.StreamSource>>()

        fun partialFor(providerId: String): List<com.hikari.app.data.StreamSource> =
            partialServers[providerId] ?: emptyList()

        /** Budget for the direct MovieBlast movie fallback. */
        private const val MOVIEBLAST_CAP_MS = 20_000L

        /** How long a fetched CloudStream home page is reused — long enough that
         *  the catalogs() → getCatalog() pair of a single Home load shares one
         *  network call, short enough that a pull-to-refresh refetches it. */
        private const val HOME_ROWS_TTL_MS = 60_000L

        private const val STALE_ROWS_MS = 12_000L

        /** Budget for the universal extraction engine (StreamHG sign-dance
         *  needs several requests, so it's deliberately roomy). */
        private const val FALLBACK_CAP_MS = 20_000L

        /** Matches a provider payload whose stream URL came back empty
         *  (iStreamFlare: `{"id":"…","url": null}`) — the load() API lookup
         *  failed, so loadLinks has nothing to resolve. */
        private val HOLLOW_URL_RE = Regex("\"url\"\\s*:\\s*(null|\"\")", RegexOption.IGNORE_CASE)

        /** Per-provider reason why its home catalog failed (empty = it works). */
        val catalogErrors = java.util.concurrent.ConcurrentHashMap<String, String>()

        /** Per-provider reason why a title search failed; separate from home-catalog failures. */
        val searchErrors = java.util.concurrent.ConcurrentHashMap<String, String>()

        /**
         * CloudStream plugins set `posterHeaders` (e.g. LeakPorner demands
         * `Referer: https://leakporner.org/` for its 58img.top thumbnails), but
         * the app's image loader never saw them, so posters 403'd into blank
         * placeholders. This map lets the global Coil loader apply the exact
         * headers the provider declared for a poster URL.
         */
        val imageHeaders = ConcurrentHashMap<String, Map<String, String>>()

        /** Per image-host Referer fallback: records `Referer` for a host so a
         *  poster whose URL differs slightly (query params, www, scheme) still
         *  gets the right referer. */
        val imageHostReferers = ConcurrentHashMap<String, String>()

        init {
            // MRDS/51CG encrypt their pic.xustgq.cn posters; the plugins
            // usually decrypt them into data: URIs, but when that fails the
            // raw URL is hotlink-protected and needs the origin site as
            // Referer. (recordPosterHeaders may override per-plugin later.)
            imageHostReferers["pic.xustgq.cn"] = "https://51cg1.com/"
            imageHostReferers["www.pic.xustgq.cn"] = "https://51cg1.com/"
        }

        private fun recordPosterHeaders(url: String?, headers: Map<String, String>?) {
            if (url.isNullOrBlank() || headers.isNullOrEmpty()) return
            val trimmed = url.trim()
            imageHeaders[trimmed] = headers
            headers["Referer"]?.let { ref ->
                runCatching {
                    val host = java.net.URI(trimmed).host ?: return@runCatching
                    imageHostReferers[host.lowercase()] = ref
                    // Also cover the scheme-relative/wrapped variants
                    imageHostReferers["www." + host.lowercase()] = ref
                }
            }
        }

        /** Extracts the btih info hash from a magnet/URL (hex or base32). */
        fun infoHashOf(url: String): String? {
            Regex("""[?&]xt=urn:btih:([a-zA-Z0-9]{32,40})""").find(url)?.let { return it.groupValues[1] }
            Regex("""urn:btih:([a-zA-Z0-9]{32,40})""").find(url)?.let { return it.groupValues[1] }
            return null
        }

        /** Torrent file index from a magnet `index=` param, if any. */
        fun magnetIndex(url: String): Int? =
            Regex("""[?&]index=(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull()

        /** Tracker URLs embedded in a magnet link. */
        fun magnetTrackers(url: String): List<String> =
            Regex("""[?&]tr=([^&]+)""").findAll(url)
                .mapNotNull { m ->
                    runCatching { java.net.URLDecoder.decode(m.groupValues[1], "UTF-8") }.getOrNull()
                }
                .toList()

        private val iconFallbackCache = ConcurrentHashMap<String, String?>()

        /** Extensions without a repo icon still get one: the provider's own
         *  mainUrl favicon via Google's favicon service (the same trick the
         *  plugin repos themselves use). Never throws. */
        fun iconFallback(config: ProviderConfig): String? {
            iconFallbackCache[config.id]?.let { return it }
            val result = runCatching {
                val file = File(config.url)
                if (!file.exists()) return@runCatching null
                val apis = Cs3PluginManager.apisFor(HikariApp.instance, file)
                val index = config.id.substringAfterLast("|").toIntOrNull() ?: 0
                val mainUrl = apis.getOrNull(index)?.mainUrl?.takeIf { it.startsWith("http") }
                    ?: return@runCatching null
                val host = java.net.URI(mainUrl).host ?: return@runCatching null
                "https://www.google.com/s2/favicons?domain=$host&sz=64"
            }.getOrNull()
            // ConcurrentHashMap forbids null values — only cache hits.
            if (result != null) iconFallbackCache[config.id] = result
            return result
        }
    }

    @Volatile
    private var loadedApi: MainAPI? = null
    @Volatile
    private var apiResolved = false

    private val api: MainAPI?
        get() {
            if (apiResolved) return loadedApi
            synchronized(this) {
                if (apiResolved) return loadedApi
                val file = File(config.url)
                if (!file.exists()) {
                    apiResolved = true
                    return null
                }
                val apis = Cs3PluginManager.apisFor(HikariApp.instance, file)
                val index = config.id.substringAfterLast("|").toIntOrNull() ?: 0
                val a = apis.getOrNull(index)?.also { normalizeMainUrl(it) }
                if (a != null) {
                    loadedApi = a
                    apiResolved = true
                }
                return a
            }
        }

    /** Some repo builds ship Castle TV with a redirect-id base
     *  (`https://api.hlowb.com/9919952593151901`) — every endpoint 404s under
     *  that, while the plain base works. Strip the id segment when present. */
    private fun normalizeMainUrl(a: MainAPI) {
        val m = a.mainUrl
        if (!m.startsWith("https://api.hlowb.com/")) return
        val rest = m.removePrefix("https://api.hlowb.com/").trimEnd('/')
        if (rest.isEmpty() || !rest.all { it.isDigit() }) return
        val fixed = "https://api.hlowb.com"
        // Prefer the public Kotlin `var` setter; fall back to the backing
        // field (walking the hierarchy in case it lives on the base class).
        val ok = runCatching {
            a.javaClass.methods.firstOrNull { it.name == "setMainUrl" }
                ?.invoke(a, fixed) != null
        }.getOrDefault(false)
        if (!ok) {
            var c: Class<*>? = a.javaClass
            while (c != null) {
                try {
                    c.getDeclaredField("mainUrl").also { f ->
                        f.isAccessible = true
                        f.set(a, fixed)
                    }
                    break
                } catch (e: NoSuchFieldException) {
                    c = c.superclass
                }
            }
        }
    }

    /** Force the plugin dex to load now (called at app startup so the first
     *  home/catalog request doesn't race with class loading). */
    fun warm() {
        runCatching { api }
    }

    override val settingsAvailable: Boolean
        get() {
            val file = File(config.url)
            if (!file.exists()) return false
            if (Cs3PluginManager.hasSettings(file)) return true
            Cs3PluginManager.apisFor(HikariApp.instance, file)
            return Cs3PluginManager.hasSettings(file)
        }

    /** Opens the plugin's own settings screen (SKTech's sub-provider picker,
     *  etc.), the same thing CloudStream's tune button does. */
    override fun openSettings(activity: android.app.Activity?): Boolean {
        val file = File(config.url)
        if (!file.exists()) return false
        return Cs3PluginManager.openSettings(file, activity)
    }

    /** Cache-only check (safe on the main thread): true when the plugin is
     *  already loaded and exposes its settings callback, so a tap on the gear
     *  can open the screen straight away. */
    override val settingsReady: Boolean
        get() = runCatching {
            val file = File(config.url)
            file.exists() && Cs3PluginManager.hasSettings(file)
        }.getOrDefault(false)

    /** Loads the plugin if this session hasn't yet (the gear is rendered from
     *  the stored provider list, which outlives the in-memory plugin instance),
     *  so tapping it can never fail merely because the plugin wasn't warmed.
     *  Blocking — call from IO. */
    override fun prepareSettings(): Boolean {
        val file = File(config.url)
        if (!file.exists()) return false
        return Cs3PluginManager.ensureSettingsLoaded(HikariApp.instance, file)
    }

    /** Hard rebuild: drops the cached plugin instance so the next
     *  [prepareSettings] re-runs the plugin's `load()` against the CURRENT
     *  activity (see [ContentProvider.rebuildSettings]). Used when a plugin's
     *  settings screen failed with a stale-activity error. */
    override fun rebuildSettings(context: android.content.Context): Boolean {
        val file = File(config.url)
        if (!file.exists()) return false
        return runCatching { Cs3PluginManager.reload(context, file) }.isSuccess &&
            Cs3PluginManager.hasSettings(file)
    }

    private val loadCache = ConcurrentHashMap<String, Pair<Long, LoadResponse>>()

    private fun cachedLoad(url: String): LoadResponse? {
        val (at, resp) = loadCache[url] ?: return null
        if (System.currentTimeMillis() - at > LOAD_CACHE_TTL_MS) {
            loadCache.remove(url)
            return null
        }
        return resp
    }

    private fun putLoad(url: String, resp: LoadResponse) {
        loadCache[url] = System.currentTimeMillis() to resp
        if (loadCache.size > LOAD_CACHE_MAX * 2) {
            // Sweep the expired first; whatever is left beyond the cap goes
            // oldest-first. The map is small — a full scan is cheap.
            val now = System.currentTimeMillis()
            loadCache.entries.removeIf { now - it.value.first > LOAD_CACHE_TTL_MS }
            if (loadCache.size > LOAD_CACHE_MAX) {
                loadCache.entries
                    .sortedBy { it.value.first }
                    .take(loadCache.size - LOAD_CACHE_MAX)
                    .forEach { loadCache.remove(it.key) }
            }
        }
    }

    /** True when ANY load() entry exists for [url] — live or expired. The
     *  freshness retry (see ContentRepository) uses it to tell "this lookup
     *  replayed a cached title" from "this lookup already ran fresh". */
    fun hadCachedLoadFor(url: String): Boolean {
        if (loadCache.containsKey(url)) return true
        return loadCache.entries.any { it.value.second.url == url }
    }

    /** True when a load() entry for [url] exists but is older than the TTL —
     *  the rotten-input case. Removes it as a side effect (it is evicted
     *  either way before a fresh load runs). */
    fun hasStaleLoadFor(url: String): Boolean {
        val now = System.currentTimeMillis()
        var stale = false
        loadCache[url]?.let { (at, _) ->
            if (now - at > LOAD_CACHE_TTL_MS) {
                loadCache.remove(url)
                stale = true
            }
        }
        if (!stale) {
            val it = loadCache.entries.iterator()
            while (it.hasNext()) {
                val (k, v) = it.next()
                if (v.second.url == url && now - v.first > LOAD_CACHE_TTL_MS) {
                    it.remove()
                    stale = true
                }
            }
        }
        return stale
    }

    suspend fun effectivePluginTimeoutMs(): Long {
        val overrideMs = runCatching {
            com.hikari.app.HikariApp.instance.store.serverTimeoutFor(config.id)
                ?.coerceIn(20, 100)?.times(1000L)
        }.getOrDefault(null)
        if (overrideMs != null) return overrideMs
        val rawTimeout = runCatching { api?.loadLinksTimeoutMs }.getOrNull()
        return (rawTimeout ?: 120_000L).coerceIn(5_000L, 480_000L)
    }

    private val homePageCache = ConcurrentHashMap<String, Pair<Long, List<HomePageList>>>()

    private val catalogSnapshot = ConcurrentHashMap<String, Pair<Long, List<List<HomePageList>>>>()
    private val SNAPSHOT_TTL_MS = 5L * 60L * 1000L
    /** A provider that served at least one full row this pass: one dead tray
     *  must never brand the whole healthy provider (the screen-level
     *  "Couldn't load <provider>" over rows that actually loaded). Reset on
     *  every [catalogs()] pass. */
    private val catalogHealthy = ConcurrentHashMap<String, Boolean>()

    override suspend fun catalogs(): List<CatalogRef> = withContext(Dispatchers.IO) {
        val a = api ?: return@withContext emptyList()
        val pages = a.mainPage
        if (pages.isEmpty()) return@withContext emptyList()
        val out = ArrayList<CatalogRef>()
        val rowsPerPage: List<List<HomePageList>> = coroutineScope {
            pages.map { page ->
                async {
                    try {
                        fetchHomeRows(a, page, 1)
                    } catch (e: Throwable) {
                        if (e is CancellationException) throw e
                        emptyList()
                    }
                }
            }.awaitAll()
        }
        catalogSnapshot[config.id] = System.currentTimeMillis() to rowsPerPage
        catalogHealthy.remove(config.id)
        pages.forEachIndexed { pageIndex, page ->
            val rows = rowsPerPage.getOrElse(pageIndex) { emptyList() }
            if (rows.isEmpty()) {
                // The plugin answers with a single flat page (or is offline):
                // keep the old one-row-per-mainPage behaviour as a fallback.
                out += CatalogRef(config.id, catalogType(), page.data, page.name.ifBlank { page.data })
            } else {
                rows.forEachIndexed { rowIndex, row ->
                    val name = row.name.ifBlank { page.name.ifBlank { page.data } }
                    out += CatalogRef(config.id, catalogType(), rowRefId(pageIndex, rowIndex), name)
                }
            }
        }
        out.distinctBy { it.type to it.id }
    }

    private fun rowRefId(pageIndex: Int, rowIndex: Int): String = "row:$pageIndex:$rowIndex"

    private fun decodeRowRef(id: String): Pair<Int, Int>? {
        if (!id.startsWith("row:")) return null
        val parts = id.split(':')
        if (parts.size != 3) return null
        val pageIndex = parts[1].toIntOrNull() ?: return null
        val rowIndex = parts[2].toIntOrNull() ?: return null
        return pageIndex to rowIndex
    }

    /** The cache key a home page's rows are held under (see [homePageCache]). */
    private fun homePageKey(page: MainPageData, pageNumber: Int): String =
        "${page.data}\u0000$pageNumber"

    /** How long ago the rows for [key] were fetched, or -1 when not cached. */
    private fun cachedHomeRowsAge(key: String): Long =
        homePageCache[key]?.let { System.currentTimeMillis() - it.first } ?: -1L

    private fun rowFor(rows: List<HomePageList>, name: String, index: Int): HomePageList? {
        val wanted = normRowName(name)
        if (wanted.isNotEmpty()) {
            val matches = rows.withIndex().filter { normRowName(it.value.name) == wanted }
            if (matches.isNotEmpty()) {
                return matches.minByOrNull { kotlin.math.abs(it.index - index) }?.value
            }
        }
        return rows.getOrNull(index)
    }

    /** Row-name equality ignoring case/extra spaces: mirrors re-emit the
     *  same tray as "Hollywood (2026)" vs "hollywood  (2026)". */
    private fun normRowName(s: String): String =
        s.trim().lowercase().replace(Regex("\\s+"), " ")

    /** Fetches one mainPage's HomePageList rows (page 1 is briefly cached). */
    private suspend fun fetchHomeRows(
        a: MainAPI,
        page: MainPageData,
        pageNumber: Int,
        force: Boolean = false,
    ): List<HomePageList> {
        val key = homePageKey(page, pageNumber)
        if (pageNumber == 1 && !force) {
            homePageCache[key]?.let { (at, rows) ->
                if (System.currentTimeMillis() - at < HOME_ROWS_TTL_MS) return rows
            }
        }
        val resp = try {
            a.getMainPage(pageNumber, MainPageRequest(page.name, page.data, false))
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            // A brand-new plugin instance can fail its very first network call
            // while the runtime/session initializes — retry once.
            a.getMainPage(pageNumber, MainPageRequest(page.name, page.data, false))
        }
        val rows = resp?.items.orEmpty()
        if (pageNumber == 1 && rows.isNotEmpty()) {
            homePageCache[key] = System.currentTimeMillis() to rows
        }
        return rows
    }

    private fun catalogType(): MediaType {
        // Row-level honesty: only a row whose plugin serves ONE kind keeps
        // that kind. A mixed row (or one whose plugin reports nothing) is
        // UNKNOWN — claiming SERIES is what tagged every film on it and
        // opened each one onto an Episodes page. Per-item types already come
        // from the plugin's own SearchResponse (see toMediaItem); the detail
        // page corrects the rest once the episode list lands.
        val types = api?.supportedTypes ?: return MediaType.UNKNOWN
        val movieOnly = types.isNotEmpty() && types.all {
            it == TvType.Movie || it == TvType.AnimeMovie || it == TvType.NSFW
        }
        return if (movieOnly) MediaType.MOVIE else MediaType.UNKNOWN
    }

    override fun adultExtension(): Boolean? {
        val a = api ?: return null
        val types = a.supportedTypes ?: return null
        if (types.isEmpty()) return null
        return types.any { it == TvType.NSFW }
    }

    override suspend fun getCatalog(ref: CatalogRef, page: Int): List<MediaItem> =
        withContext(Dispatchers.IO) {
            val a = api
            if (a == null) {
                catalogErrors[config.id] = apiFailureReason()
                return@withContext emptyList()
            }
            // A Home row created by catalogs(): fetch the plugin's home page and
            // return only the HomePageList that this row stands for.
            val rowRef = decodeRowRef(ref.id)
            if (rowRef != null) {
                val (pageIndex, rowIndex) = rowRef
                if (page == 1) {
                    val snap = catalogSnapshot[config.id]
                    if (snap != null && System.currentTimeMillis() - snap.first < SNAPSHOT_TTL_MS) {
                        val snapRow = rowFor(snap.second.getOrNull(pageIndex).orEmpty(), ref.name, rowIndex)
                        val snapItems = snapRow?.list.orEmpty().mapNotNull { it.toMediaItem() }
                        if (snapItems.isNotEmpty()) {
                            catalogErrors.remove(config.id)
                            catalogHealthy[config.id] = true
                            return@withContext snapItems
                        }
                    }
                }
                val mainPage = a.mainPage.getOrNull(pageIndex)
                if (mainPage == null) {
                    catalogErrors[config.id] = "Home page ${ref.name} is no longer available — refresh Home"
                    return@withContext emptyList()
                }
                val key = homePageKey(mainPage, page)
                var rows = try {
                    fetchHomeRows(a, mainPage, page)
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    catalogErrors[config.id] = fullCause(e)
                    return@withContext emptyList()
                }
                var row = rowFor(rows, ref.name, rowIndex)
                var rowItems = row?.list.orEmpty().mapNotNull { it.toMediaItem() }
                if (rowItems.isEmpty() && (row == null || cachedHomeRowsAge(key) > STALE_ROWS_MS)) {
                    rows = try {
                        fetchHomeRows(a, mainPage, page, force = true)
                    } catch (e: Throwable) {
                        if (e is CancellationException) throw e
                        rows
                    }
                    row = rowFor(rows, ref.name, rowIndex)
                    rowItems = row?.list.orEmpty().mapNotNull { it.toMediaItem() }
                }
                if (rowItems.isEmpty()) {
                    var direct = emptyList<MediaItem>()
                    runCatching {
                        a.getMainPage(page, MainPageRequest(ref.name, mainPage.data, false))
                    }.getOrNull()?.let { resp ->
                        direct = resp.items.orEmpty().flatMap { r ->
                            r.list.orEmpty().mapNotNull { it.toMediaItem() }
                        }
                    }
                    if (direct.isNotEmpty()) {
                        catalogErrors.remove(config.id)
                        catalogHealthy[config.id] = true
                        return@withContext direct
                    }
                    val warmUrl = mainPage.data.takeIf { it.startsWith("http") }
                        ?: runCatching { a.mainUrl }.getOrNull()
                    if (warmUrl != null && com.hikari.app.net.CloudflareSolver.warm(
                            warmUrl, com.hikari.app.net.Http.UA, timeoutMs = 20_000L
                        )
                    ) {
                        rows = try {
                            fetchHomeRows(a, mainPage, page, force = true)
                        } catch (e: Throwable) {
                            if (e is CancellationException) throw e
                            rows
                        }
                        row = rowFor(rows, ref.name, rowIndex)
                        rowItems = row?.list.orEmpty().mapNotNull { it.toMediaItem() }
                        if (rowItems.isNotEmpty()) {
                            catalogErrors.remove(config.id)
                            catalogHealthy[config.id] = true
                            return@withContext rowItems
                        }
                    }
                    val rawCount = row?.list?.size ?: -1
                    val pluginFile = runCatching { java.io.File(config.url).name }.getOrDefault("?")
                    val mirror = runCatching { a.mainUrl }.getOrNull().orEmpty()
                    if (catalogHealthy[config.id] != true) {
                    val mirrorHost = runCatching { java.net.URI(mirror).host }.getOrDefault("")?.takeIf { it.isNotBlank() } ?: mirror.take(60)
                    catalogErrors[config.id] = if (row == null) {
                        "\"${ref.name}\" is no longer on its home page — refresh Home"
                    } else {
                        "No items in ${ref.name} — ${pluginFile} gave nothing on ${mirrorHost} (check the extension's mirror setting, or update it)"
                    }
                    }
                    runCatching {
                        com.hikari.app.data.Logs.log(
                            "Cs3Catalog",
                            "empty row provider=" + config.id + " ref=" + ref.name +
                                " page=" + pageIndex + ":" + rowIndex +
                                " matched=" + (row?.name ?: "-") +
                                " raw=" + rawCount + " parsed=" + rowItems.size +
                                " rows=" + rows.size +
                                " plugin=" + pluginFile +
                                " mirror=" + mirror.take(120),
                        )
                    }
                } else {
                    catalogErrors.remove(config.id)
                    catalogHealthy[config.id] = true
                }
                return@withContext rowItems
            }
            val resp = try {
                a.getMainPage(page, MainPageRequest(ref.name, ref.id, false))
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                // A brand-new plugin instance can fail its very first network
                // call while the runtime/session initializes — retry once.
                try {
                    a.getMainPage(page, MainPageRequest(ref.name, ref.id, false))
                } catch (e2: Throwable) {
                    if (e2 is CancellationException) throw e2
                    catalogErrors[config.id] = fullCause(e2)
                    return@withContext emptyList()
                }
            }
            if (resp == null) {
                catalogErrors[config.id] = "getMainPage returned null for ${ref.id}"
                return@withContext emptyList()
            }
            var items = resp.items.orEmpty().flatMap { row ->
                row.list.orEmpty().mapNotNull { it.toMediaItem() }
            }
            if (items.isEmpty()) {
                runCatching {
                    a.getMainPage(page, MainPageRequest(ref.name, ref.id, false))
                }.getOrNull()?.let { retry ->
                    val retryItems = retry.items.orEmpty().flatMap { row ->
                        row.list.orEmpty().mapNotNull { it.toMediaItem() }
                    }
                    if (retryItems.isNotEmpty()) items = retryItems
                }
            }
            if (items.isEmpty()) {
                catalogErrors[config.id] = "Page fetched but no items parsed from ${ref.id}"
                runCatching {
                    com.hikari.app.data.Logs.log(
                        "Cs3Catalog",
                        "empty catalog provider=" + config.id + " ref=" + ref.name + " id=" + ref.id.take(120),
                    )
                }
            } else {
                catalogErrors.remove(config.id)
            }
            items
        }

    override suspend fun search(query: String, page: Int): List<MediaItem> =
        withContext(Dispatchers.IO) {
            searchErrors.remove(config.id)
            val a = api
            if (a == null) {
                searchErrors[config.id] = apiFailureReason()
                streamErrors[config.id] = apiFailureReason()
                com.hikari.app.data.Logs.log(
                    "Provider",
                    "${config.name}: search skipped — ${apiFailureReason()}",
                )
                return@withContext emptyList()
            }
            val found = try {
                searchItems(a, query, page)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                // A brand-new plugin instance can fail its very first network
                // call while the runtime/session initializes — retry once.
                try {
                    searchItems(a, query, page)
                } catch (e2: Throwable) {
                    if (e2 is CancellationException) throw e2
                    val why = fullCause(e2)
                    val noSearch = why.contains("NotImplementedError") ||
                        why.contains("UnsupportedOperationException") ||
                        why.contains("not implemented", ignoreCase = true)
                    searchErrors[config.id] = if (noSearch) {
                        "This extension is extractor-only (no search) — it cannot be asked by title."
                    } else {
                        "Search failed: $why"
                    }
                    streamErrors[config.id] = searchErrors[config.id]!!
                    com.hikari.app.data.Logs.log(
                        "Provider",
                        "${config.name}: " + (if (noSearch) "has no search (extractor-only)" else "search failed — $why"),
                    )
                    emptyList()
                }
            }
            // Search verdict must not leave a stale error behind: a success
            // clears the search error so a later transient failure ("ex" found
            // nothing once on a bad network) doesn't brand the next open.
            if (found.isNotEmpty()) {
                streamErrors.remove(config.id)
            } else if (searchErrors[config.id] == null) {
                // No exception, just nothing: say why — site blocked vs
                // genuinely no match — instead of leaving the screen to show
                // a stale catalog-row error (e.g. "No items in Hollywood").
                val reason = diagnoseEmptySearch(a, query)
                searchErrors[config.id] = reason
                com.hikari.app.data.Logs.log(
                    "Provider",
                    "${config.name}: search \"$query\" → 0 items ($reason)",
                )
            }
            found.mapNotNull { it.toMediaItem() }
        }

    /** Why a search came back empty without throwing: probe the site root so
     *  the No-matches screen names the real reason (bot-block vs no match). */
    private suspend fun diagnoseEmptySearch(a: MainAPI, query: String): String {
        val root = runCatching { a.mainUrl }.getOrNull()?.takeIf { it.startsWith("http") }
            ?: return "search returned nothing for \"$query\""
        val note: String? = try {
            withTimeoutOrNull(10_000) {
                com.hikari.app.net.Http.getQuiet(root).use { res ->
                    when {
                        res.code == 401 || res.code == 403 ->
                            "the site refused this app (HTTP ${res.code}) — open Verify WebView or check the mirror setting"
                        res.code in 500..599 -> "the site itself is erroring right now (HTTP ${res.code})"
                        res.code !in 200..299 -> "the site answered HTTP ${res.code}"
                        else -> null
                    }
                }
            } ?: "the site could not be reached (network error or timeout)"
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            "the site could not be reached (network error or timeout)"
        }
        return note ?: "the site loads OK but has nothing for \"$query\""
    }

    private suspend fun searchItems(a: MainAPI, query: String, page: Int): List<SearchResponse> {
        return try {
            a.search(query, page)?.items.orEmpty()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            a.search(query).orEmpty()
        }
    }

    /** Human-readable reason why this provider's API object is unavailable. */
    private fun apiFailureReason(): String {
        val file = File(config.url)
        if (!file.exists()) {
            return "Plugin file is missing — open Extensions and reinstall this one."
        }
        val apis = Cs3PluginManager.apisFor(HikariApp.instance, file)
        val index = config.id.substringAfterLast("|").toIntOrNull() ?: 0
        if (index >= apis.size) {
            return "Plugin loaded ${apis.size} provider(s), but this entry needs #$index. Reinstall the plugin."
        }
        val detail = Cs3PluginManager.lastError
        return if (detail.isNullOrBlank()) "Plugin did not register this provider. Reinstall the plugin."
        else "Plugin failed to load:\n$detail"
    }

    override suspend fun getMeta(item: MediaItem): MediaItem {
        val resp = loadResponse(item.id) ?: return item
        val canonicalUrl = resp.url.takeIf { it.isNotBlank() } ?: item.id
        if (canonicalUrl != item.id) {
            putLoad(canonicalUrl, resp)
        }
        val mt = when (resp) {
            is MovieLoadResponse -> MediaType.MOVIE
            is TvSeriesLoadResponse, is AnimeLoadResponse -> MediaType.SERIES
            else -> item.type
        }
        return when (resp) {
            is MovieLoadResponse -> item.copy(
                id = canonicalUrl,
                type = mt,
                overview = resp.plot ?: item.overview,
                genres = resp.tags ?: item.genres,
                year = resp.year ?: item.year,
                posterUrl = resp.posterUrl ?: item.posterUrl,
                backdropUrl = resp.backgroundPosterUrl ?: item.backdropUrl,
            ).also { recordRespHeaders(resp) }
            is AnimeLoadResponse -> item.copy(
                id = canonicalUrl,
                type = mt,
                title = resp.engName?.takeIf { it.isNotBlank() } ?: item.title,
                overview = resp.plot ?: item.overview,
                genres = resp.tags ?: item.genres,
                year = resp.year ?: item.year,
                posterUrl = resp.posterUrl ?: item.posterUrl,
                backdropUrl = resp.backgroundPosterUrl ?: item.backdropUrl,
            ).also { recordRespHeaders(resp) }
            is TvSeriesLoadResponse -> item.copy(
                id = canonicalUrl,
                type = mt,
                overview = resp.plot ?: item.overview,
                genres = resp.tags ?: item.genres,
                year = resp.year ?: item.year,
                posterUrl = resp.posterUrl ?: item.posterUrl,
                backdropUrl = resp.backgroundPosterUrl ?: item.backdropUrl,
            ).also { recordRespHeaders(resp) }
            else -> item
        }
    }

    private fun recordRespHeaders(resp: LoadResponse) {
        recordPosterHeaders(resp.posterUrl, resp.posterHeaders)
        recordPosterHeaders(resp.backgroundPosterUrl, resp.posterHeaders)
    }

    override suspend fun getEpisodes(item: MediaItem): List<Episode>? = withContext(Dispatchers.IO) {
        // Movies never show Loading episodes.
        if (item.type == MediaType.MOVIE) return@withContext null
        val resp = loadResponse(item.id) ?: return@withContext null
        when (resp) {
            is AnimeLoadResponse -> {
                val eps = resp.episodes.values.flatten()
                if (eps.isEmpty()) null
                else eps
                    .sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: Int.MAX_VALUE }))
                    .distinctBy { it.data ?: "${it.season ?: 1}:${it.episode ?: 0}" }
                    .map { it.toHikari(resp.posterHeaders) }
            }
            is TvSeriesLoadResponse -> {
                if (resp.episodes.isEmpty()) null
                else resp.episodes
                    .sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: Int.MAX_VALUE }))
                    .distinctBy { it.data ?: "${it.season ?: 1}:${it.episode ?: 0}" }
                    .map { it.toHikari(resp.posterHeaders) }
            }
            else -> null
        }
    }

    override fun partialStreams(): List<StreamSource> =
        partialFor(config.id)

    override suspend fun getStreams(item: MediaItem, episode: Episode?): List<StreamSource> =
        withContext(Dispatchers.IO) {
            val a = api ?: return@withContext emptyList()
            // For SERIES, the plugin's per-episode data string lives on the
            // episode (it's a URL or a JSON payload the provider re-parses in
            // loadLinks) and is already in episode.id. For MOVIES there is no
            // episode: the provider serialized its source list into
            // MovieLoadResponse.dataUrl during load() (e.g. MoviesMod and
            // VegaMovies build `[{"source":"…"}]` and loadLinks does
            // parseJson<ArrayList<EpisodeLink>>(data)). Feeding the plain page
            // URL to loadLinks makes those providers throw Jackson's
            // "Unrecognized token 'https'" — so hand the plugin the response's
            // data string, exactly like CloudStream does.
            val movieData = if (episode == null) {
                (loadResponse(item.id) as? MovieLoadResponse)
                    ?.dataUrl
                    ?.takeIf { it.isNotBlank() }
            } else null
            val data = if (episode != null) episode.id else movieData ?: item.id
            if (data.isEmpty() || data == "[]" || data == "about:blank") {
                streamErrors[config.id] = "No playable source found on this extension."
                return@withContext emptyList()
            }
            val safeData = repairLoadLinksData(data, episode, item)
            val fallbackTarget = if (episode != null) data else item.id
            val started = System.currentTimeMillis()

            // The plugin's own extraction and our universal engine (jar
            // extractor registry + Rumble/Dood scraping + packed unpacking)
            // run IN PARALLEL and their results are MERGED — so a host the
            // plugin resolves and a host it misses (Rumble, ok.ru, …) both
            // appear, just like CloudStream's source picker. Neither engine is
            // allowed to block the other: once one finishes, the other gets a
            // short grace window and then we play what we have.
            // CloudStream lets each plugin declare its own loadLinks budget
            // (MainAPI.loadLinksTimeoutMs, CloudStream default 2 minutes — see
            // [effectivePluginTimeoutMs]). The user's per-extension
            // server-search time wins when set for this repo (Settings →
            // Playback & Servers → Server search time); otherwise the plugin's
            // own declared budget stands. Only the WAIT grows — a definitive
            // failure still surfaces the moment it happens, via the merge
            // loop below.
            // CopyOnWriteArrayList, not mutableListOf: a plugin's loadLinks
            // invokes these callbacks from its OWN coroutines, and providers
            // that fan out over several extractors (Goojara-style) call back
            // from several threads at once. Two concurrent `ArrayList.add`
            // calls is how a source list gets corrupted — the recorded crash
            // (`ArrayIndexOutOfBoundsException: length=49; index=49` at
            // `java.util.ArrayList.add`, thrown from an extension's callback)
            // is exactly that race, so the app's own callbacks are never
            // plain ArrayLists.
            val links = CopyOnWriteArrayList<com.lagradost.cloudstream3.utils.ExtractorLink>()
            val subs = CopyOnWriteArrayList<SubtitleFile>()
            val worker = Thread.currentThread()
            val overrideMs = runCatching {
                com.hikari.app.HikariApp.instance.store.serverTimeoutFor(config.id)
                    ?.coerceIn(20, 100)?.times(1000L)
            }.getOrDefault(null)
            val pluginTimeout = overrideMs ?: effectivePluginTimeoutMs()
            var deadline =
                started + maxOf(pluginTimeout, MOVIEBLAST_CAP_MS, FALLBACK_CAP_MS) + 2_000

            // Deliberately NOT coroutineScope: it waits for children to finish
            // cancelling, which would block here while a hung plugin drains its
            // native network call. A detached scope returns the moment we have
            // sources; the abandoned engine keeps running on IO in the background.
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val result = try {
                val pluginJob = scope.async {
                    try {
                        var completed: Boolean? = null
                        var elapsedMs = 0L
                        suspend fun runOnce(budget: Long, url: String) {
                            val s = System.currentTimeMillis()
                            completed = withTimeoutOrNull(budget) {
                                a.loadLinks(url, false, { subs.add(it) }, { links.add(it) })
                            }
                            elapsedMs += System.currentTimeMillis() - s
                        }
                        runOnce(pluginTimeout, safeData)
                        if ((completed != true || links.isEmpty()) && elapsedMs < 15_000) {
                            links.clear()
                            val retryBudget = minOf(pluginTimeout, 20_000L)
                            deadline = maxOf(deadline, System.currentTimeMillis() + retryBudget + 1_000)
                            runOnce(retryBudget, safeData)
                        }
                        if ((completed != true || links.isEmpty()) && isHollowPayload(data)) {
                            // Resolve the ORIGINAL search/page id BEFORE
                            // invalidateHollow — it finds the cache entry
                            // (key → payload) that invalidate is about to
                            // delete; after the wipe, originalIdOf comes up
                            // empty and the retry would just re-load the dead
                            // payload string instead of the real page.
                            val orig = originalIdOf(data) ?: item.id
                            invalidateHollow(data)
                            val freshBudget = minOf(pluginTimeout, 25_000L)
                            // Covers BOTH the fresh load() and the retried
                            // loadLinks, so the merge loop can't cut them off.
                            deadline = maxOf(deadline, System.currentTimeMillis() + freshBudget * 2 + 1_000)
                            val fresh = withTimeoutOrNull(freshBudget) { loadResponse(orig) }
                            val freshData = fresh?.url?.takeIf {
                                it.isNotBlank() && !isHollowPayload(it)
                            } ?: data
                            if (freshData != data) {
                                links.clear()
                                runOnce(freshBudget, repairLoadLinksData(freshData, episode, item))
                            }
                        }
                        // Same stale-data class, one step further out: the
                        // plugin came back empty-handed on a PAGE url (not an
                        // opaque payload), so find out whether that page is
                        // even alive before blaming the extraction. A
                        // 404/410 page means the plugin's stored data is stale
                        // (the site moved it, the upstream removed it) — the
                        // silent auto-updater swaps builds overnight, and a
                        // listing cached from the previous build can point at
                        // pages that no longer exist. Re-load() fresh and
                        // retry once exactly like the hollow case; a live page
                        // skips this entirely (the miss is in the engines, not
                        // the data), and an unchanged re-load retries nothing.
                        if ((completed != true || links.isEmpty()) && !isHollowPayload(data) &&
                            data.startsWith("http", ignoreCase = true)
                        ) {
                            val dead = withTimeoutOrNull(10_000) { pageGone(data) } ?: false
                            if (dead) {
                                com.hikari.app.data.Logs.log(
                                    "Provider",
                                    a.name + ": data page is gone (HTTP 404/410) — re-resolving fresh like a hollow payload",
                                )
                                val orig = originalIdOf(data) ?: item.id
                                invalidateHollow(data)
                                val freshBudget = minOf(pluginTimeout, 15_000L)
                                deadline = maxOf(deadline, System.currentTimeMillis() + freshBudget * 2 + 1_000)
                                val fresh = withTimeoutOrNull(freshBudget) { loadResponse(orig) }
                                val freshData = fresh?.url?.takeIf {
                                    it.isNotBlank() && !isHollowPayload(it)
                                } ?: data
                                if (freshData != data) {
                                    links.clear()
                                    runOnce(freshBudget, repairLoadLinksData(freshData, episode, item))
                                }
                            }
                        }
                        if (completed == null) {
                            streamErrors[config.id] =
                                "${a.name} timed out after ${pluginTimeout / 1000}s — the provider hung while resolving sources.\n" +
                                    "Stuck on thread '${worker.name}':\n" +
                                    worker.stackTrace.take(25).joinToString("\n") { "    at $it" }
                        } else {
                            val dataLabel = if (data.length > 96) data.take(96) + "…" else data
                            val who = "${a.name} [$dataLabel]"
                            streamErrors[config.id] = when {
                                completed != true -> "$who: no stream links found on the page."
                                links.isEmpty() -> "$who: page parsed OK, but produced no stream links."
                                else -> null
                            } ?: ""
                        }
                        links.toList()
                    } catch (e: Throwable) {
                        if (e is CancellationException) throw e
                        // The error screen shows this text: keep one human line
                        // (the full stack stays in logcat + the line below).
                        streamErrors[config.id] = shortCause(e)
                        com.hikari.app.data.Logs.log("Provider", a.name + ": loadLinks failed: " + fullCause(e).take(1200))
                        android.util.Log.e("Cs3Streams", "loadLinks failed for $data", e)
                        emptyList()
                    }
                }
                val fallbackJob = scope.async {
                    try {
                        // Roomier than the plugin budget by design: the
                        // StreamHG/StreamGG sign-dance (page -> player -> token
                        // API) takes several requests; 12s was too tight for
                        // the last-resort pass.
                        withTimeoutOrNull(FALLBACK_CAP_MS) {
                            // MovieLinkBD's rebuilt watch pages carry their
                            // files in an encrypted page blob the generic
                            // engine cannot see (and an older plugin finds no
                            // embeds at all — the recorded "page parsed OK,
                            // but produced no stream links"). Read those
                            // first; everything else falls through to the
                            // generic engine below.
                            val mlbd = if (MlbdResolver.matches(fallbackTarget)) {
                                runCatching { MlbdResolver.resolve(fallbackTarget, episode) }
                                    .getOrDefault(emptyList())
                            } else emptyList()
                            mlbd.ifEmpty { FallbackResolver.resolve(fallbackTarget) }
                        } ?: emptyList()
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        emptyList()
                    }
                }
                // MovieBlast MOVIES: the plugin's movie loader returns an empty
                // but "completed" result (its series path works — episodes
                // play), so resolve movies straight from the MovieBlast API:
                // search the title -> media detail -> signed CDN links. Runs in
                // parallel with the plugin; its sources are merged in the same
                // pass below and deduped by URL.
                val movieblastJob = scope.async {
                    try {
                        // Provider name is normally "MovieBlast"; also accept
                        // the plugin's own page URL as a signal in case a
                        // rebuilt plugin names itself differently.
                        val isMb = a.name.contains("MovieBlast", ignoreCase = true) ||
                            config.name.contains("MovieBlast", ignoreCase = true) ||
                            item.id.contains("movieblast", ignoreCase = true) ||
                            item.id.contains("cloud-mb", ignoreCase = true)
                        if (!isMb || episode != null) return@async emptyList()
                        withTimeoutOrNull(MOVIEBLAST_CAP_MS) { MovieBlastResolver.resolve(item, episode) }
                            ?: emptyList()
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        emptyList()
                    }
                }

                var pluginLinksSeen = -1
                var pluginSubsSeen = -1
                var pluginSourcesCache: List<StreamSource> = emptyList()
                suspend fun pluginSources(): List<StreamSource> {
                    if (links.size != pluginLinksSeen || subs.size != pluginSubsSeen) {
                        pluginLinksSeen = links.size
                        pluginSubsSeen = subs.size
                        pluginSourcesCache = runCatching {
                            toStreamSources(links.toList(), subs.toList())
                        }.getOrDefault(emptyList())
                    }
                    return pluginSourcesCache
                }

                fun fallbackSources(): List<StreamSource> =
                    if (fallbackJob.isCompleted) {
                        runCatching { fallbackJob.getCompleted() }.getOrDefault(emptyList())
                    } else emptyList()

                fun movieblastSources(): List<StreamSource> =
                    if (movieblastJob.isCompleted) {
                        runCatching { movieblastJob.getCompleted() }.getOrDefault(emptyList())
                    } else emptyList()

                val merged = LinkedHashMap<String, StreamSource>()
                // True once the plugin's loadLinks has emitted at least one
                // usable link (it streams them in as it goes).
                var sawPluginSource = false
                while (true) {
                    val ps = pluginSources()
                    if (ps.isNotEmpty()) sawPluginSource = true
                    for (s in ps) merged.putIfAbsent(s.url, s)
                    if (merged.isNotEmpty()) partialServers[config.id] = merged.values.toList()
                    val pluginDone = pluginJob.isCompleted
                    if (pluginDone && !sawPluginSource) {
                        for (s in fallbackSources()) merged.putIfAbsent(s.url, s)
                        for (s in movieblastSources()) merged.putIfAbsent(s.url, s)
                    }
                    val fallbackDone = fallbackJob.isCompleted
                    val movieblastDone = movieblastJob.isCompleted
                    if (pluginDone && (sawPluginSource || (fallbackDone && movieblastDone))) break
                    val now = System.currentTimeMillis()
                    // Universal fast-first: a meta provider (CineStream class) fans out
                    // internally for ~50s while the reference app shows the first mirrors
                    // in seconds (it renders each callback as it lands; we used to wait
                    // for the whole fan-out). Once the first plugin server is in hand
                    // and 3s have passed, hand it over now; the background sweep keeps
                    // filling the rest.
                    if (sawPluginSource && merged.isNotEmpty() && now - started >= 3_000) {
                        com.hikari.app.data.Logs.log(
                            "Provider",
                            a.name + ": early handover with " + merged.size + " server(s) after " + ((now - started) / 1000) + "s (rest fill in background)",
                        )
                        break
                    }
                    if (now > deadline) break
                    kotlinx.coroutines.delay(80)
                }
                if (!sawPluginSource && merged.isNotEmpty()) {
                    com.hikari.app.data.Logs.log(
                        "Provider",
                        a.name + ": plugin came up empty — ${merged.size} server(s) from the safety-net engine",
                    )
                }
                if (!pluginJob.isCompleted) pluginJob.cancel()
                if (!fallbackJob.isCompleted) fallbackJob.cancel()
                if (!movieblastJob.isCompleted) movieblastJob.cancel()
                val result = merged.values.toList()
                // Everything came up empty. The bundled yt-dlp engine used to
                // get one last shot at the raw page URL from here; it was
                // removed in 0.9.1 (see the CHANGELOG). Either way the player's
                // panel must not go silent: if no engine left a reason behind,
                // say that this extension found nothing.
                if (result.isEmpty()) {
                    probePageReachability(fallbackTarget, config.id)
                    if (streamErrors[config.id].isNullOrBlank()) {
                        streamErrors[config.id] = "No playable source found on this extension."
                    }
                }
                result
            } finally {
                partialServers.remove(config.id)
                scope.cancel()
            }

            if (result.isNotEmpty()) streamErrors.remove(config.id)
            lastStreamsTimeMs = System.currentTimeMillis() - started
            result
        }


    private suspend fun pageGone(pageUrl: String): Boolean {
        if (!pageUrl.startsWith("http", ignoreCase = true)) return false
        return try {
            com.hikari.app.net.Http.getQuiet(pageUrl).use { res ->
                res.code == 404 || res.code == 410
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            false
        }
    }

    private suspend fun probePageReachability(pageUrl: String, providerId: String) {
        if (!pageUrl.startsWith("http", ignoreCase = true)) return
        val note: String? = try {
            withTimeoutOrNull(10_000) {
                // Quiet client: a review-site fetch must never raise the Home
                // screen's "verification needed" banner (see Http.getQuiet).
                com.hikari.app.net.Http.getQuiet(pageUrl).use { res ->
                    val code = res.code
                    // The head is enough to spot a challenge or empty page —
                    // never download a video body for a diagnosis.
                    val peek = runCatching { res.peekBody(64 * 1024).string() }.getOrDefault("")
                    when {
                        code == 401 || code == 403 ->
                            "the site refused this app (HTTP $code) — it may open in a browser but not from here"
                        code in 500..599 -> "the site itself is erroring right now (HTTP $code)"
                        code == 404 -> "the title page is gone (HTTP 404)"
                        code !in 200..299 -> "the site answered HTTP $code"
                        peek.isBlank() -> "the site returned an empty page"
                        peek.contains("challenge-platform", ignoreCase = true) ||
                            (peek.contains("cloudflare", ignoreCase = true) &&
                                peek.contains("challenge", ignoreCase = true)) ||
                            peek.contains("just a moment", ignoreCase = true) ->
                            "the site is showing a bot check the app cannot pass on its own"
                        // The page loads: the miss is in the engines, and any
                        // reason they left behind already says so.
                        else -> "\u0000OK"
                    }
                }
            } ?: "the site could not be reached (network error or timeout)"
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            "the site could not be reached (network error or timeout)"
        }
        if (note == null || note == "\u0000OK") return
        val prev = streamErrors[providerId].orEmpty()
        streamErrors[providerId] =
            if (prev.isBlank()) "No playable source found: $note."
            else "$prev The title page itself: $note."
    }

    private fun enrichHotlinkHeaders(url: String, headers: MutableMap<String, String>) {
        val host = url.substringAfter("://").substringBefore('/').substringBefore('?').lowercase()
        if (host.isBlank()) return
        fun apexOf(h: String): String {
            val parts = h.split('.')
            return if (parts.size >= 3) parts.takeLast(2).joinToString(".") else h
        }
        val hot = listOf(
            "fastream", "streamwish", "streamtape", "lulu", "dood",
            "filemoon", "mixdrop", "voe.", "vidplay", "mp4upload", "upstream",
            "streamlare", "vidmoly", "rabbitstream", "megacloud", "vidcloud",
            "filelions", "streamhub", "streamruby", "streamvid", "lulustream",
            "wish", "vidhide", "hsxcon", "hsx",
        )
        if (hot.none { host.contains(it) }) {
            // Signed expiring links (auth_key/expire/signature/token) are hotlink-checked
            // even on unknown hosts: without a Referer they 403 (the Castle class).
            val q = url.substringAfter('?', "").lowercase()
            val signed = q.contains("auth_key") || q.contains("expire") || q.contains("signature") || q.contains("token")
            if (signed && !headers.keys.any { it.equals("Referer", true) }) {
                headers.putIfAbsent("Referer", "https://" + apexOf(host))
            }
            headers.putIfAbsent("User-Agent", com.hikari.app.net.Http.UA)
            return
        }
        if (host.contains("fastream")) {
            // The signed stream lives on a numbered CDN host (s40.fastream.to,
            // s41.fastream.to, …) but the hotlink check expects the SITE the
            // embed came from — CloudStream's own player sends the extractor's
            // verbatim referer (`https://fastream.to`, NO trailing slash, via
            // M3u8Helper with mainUrl). A trailing slash is a different string
            // to an exact-match hotlink check and 403s the same URL that plays
            // in CloudStream.
            headers.putIfAbsent("Referer", "https://fastream.to")
            headers.remove("Origin")
            headers.putIfAbsent("User-Agent", com.hikari.app.net.Http.UA)
            return
        }
        val apex = apexOf(host)
        // CS3 JwPlayerHelper uses referer = mainUrl (e.g. https://fastream.to)
        headers.putIfAbsent("Referer", "https://$apex")
        // Do NOT force Origin / Sec-Fetch-* — CloudStream does not.
        headers.putIfAbsent("User-Agent", com.hikari.app.net.Http.UA)
    }


    private fun decodeHtmlEntities(s: String): String {
        if (!s.contains('&')) return s
        var out = s
        repeat(3) {
            var next = out
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&#x27;", "'")
                .replace("&#x2F;", "/")
            next = runCatching {
                val sb = StringBuilder()
                var i = 0
                while (i < next.length) {
                    if (next[i] == '&' && i + 1 < next.length && next[i + 1] == '#') {
                        val semi = next.indexOf(';', i + 2)
                        if (semi in (i + 3)..(i + 8)) {
                            val body = next.substring(i + 2, semi)
                            val code = if (body.startsWith("x", true)) body.drop(1).toIntOrNull(16) else body.toIntOrNull()
                            if (code != null && code > 0 && code < 0x110000) {
                                sb.append(Character.toChars(code))
                                i = semi + 1
                                continue
                            }
                        }
                    }
                    sb.append(next[i])
                    i++
                }
                sb.toString()
            }.getOrDefault(next)
            if (next == out) return out
            out = next
            if (!out.contains('&')) return out
        }
        return out
    }

    private fun isLikelyHls(url: String): Boolean {
        val u = url.substringBefore('?').lowercase()
        return u.endsWith(".m3u8") || u.contains(".m3u8/")
    }

    private fun isLikelyDash(url: String): Boolean {
        val u = url.substringBefore('?').lowercase()
        return u.endsWith(".mpd") || u.contains(".mpd/")
    }

    /** Maps the plugin's raw ExtractorLinks into Hikari StreamSources with
     *  CloudStream-style names ("OkRuSSL 1080p") and referer/header merging. */
    private val torFileCache: MutableMap<String, Pair<Long, com.hikari.app.data.StreamSource?>> =
        java.util.Collections.synchronizedMap(
            object : LinkedHashMap<String, Pair<Long, com.hikari.app.data.StreamSource?>>(64, 0.75f, true) {
                override fun removeEldestEntry(
                    eldest: Map.Entry<String, Pair<Long, com.hikari.app.data.StreamSource?>>?,
                ): Boolean = size > 128
            },
        )

    private suspend fun toStreamSources(
        rawLinks: List<com.lagradost.cloudstream3.utils.ExtractorLink>,
        rawSubs: List<SubtitleFile>,
    ): List<StreamSource> {
        val a = api
        val now = System.currentTimeMillis()
        for (u in rawLinks.map { decodeHtmlEntities(it.url) }
            .filter { com.hikari.app.data.NetworkStream.isTorrentFile(it) }.distinct()) {
            val cached = torFileCache[u]
            if (cached == null || (cached.second == null && now - cached.first > 60_000)) {
                torFileCache[u] = now to runCatching {
                    withTimeoutOrNull(15_000) {
                        com.hikari.app.data.NetworkStream.torrentFileInfo(u, "")
                    }
                }.getOrNull()
            }
        }
        return rawLinks
            .filter {
                val u = decodeHtmlEntities(it.url)
                u.isNotBlank() && u != a?.mainUrl && it.type.name != "ERROR" &&
                    !isGarbageUrl(u) && isStreamableScheme(u)
            }
            .map { l ->
                // Decoded once (see above): every stored/transmitted use below
                // must see the real URL, never the HTML-escaped form.
                val streamUrl = decodeHtmlEntities(l.url)
                if (a != null) {
                    runCatching { a.getVideoInterceptor(l) }.getOrNull()?.let { videoInterceptors[streamUrl] = it }
                }
                val headers = LinkedHashMap<String, String>()
                l.headers?.forEach { (k, v) ->
                    val c = decodeHtmlEntities(v).filter { it.code < 128 }
                    if (c.isNotBlank()) headers[k] = c
                }
                val ref = decodeHtmlEntities(l.referer.orEmpty())
                if (ref.isNotBlank()) {
                    val c = ref.filter { it.code < 128 }
                    if (c.isNotBlank()) headers.putIfAbsent("Referer", c)
                }
                // Hotlink CDNs: CS3 player sends Referer only (not Origin).
                enrichHotlinkHeaders(streamUrl, headers)
                val subSources = rawSubs.map { SubtitleSource(it.lang.ifBlank { "Sub" }, decodeHtmlEntities(it.url)) }
                val torFile = torFileCache[streamUrl]?.second
                val isTorrent = l.type.name == "MAGNET" || l.type.name == "TORRENT" ||
                    streamUrl.startsWith("magnet:", true) || streamUrl.startsWith("torrent:", true) ||
                    torFile != null
                val qualityLabel = com.lagradost.cloudstream3.utils.Qualities.getStringByInt(l.quality)
                val baseName = l.name.ifBlank { "Stream" }
                StreamSource(
                    name = if (qualityLabel.isNotBlank() && !baseName.contains(qualityLabel, ignoreCase = true)) {
                        "$baseName $qualityLabel"
                    } else {
                        baseName
                    },
                    url = if (isTorrent) streamUrl else Http.normalizeDriveUrl(streamUrl),
                    headers = headers,
                    subtitles = subSources,
                    isM3u8 = l.isM3u8 || isLikelyHls(streamUrl),
                    isMpd = l.isDash || isLikelyDash(streamUrl),
                    isTorrent = isTorrent,
                    infoHash = if (isTorrent) infoHashOf(streamUrl) ?: torFile?.infoHash else null,
                    fileIdx = magnetIndex(streamUrl),
                    trackers = (magnetTrackers(streamUrl) + torFile?.trackers.orEmpty()).distinct(),
                    drm = drmSpecOf(l),
                )
            }
            .distinctBy { it.url }
    }

    private fun drmSpecOf(link: com.lagradost.cloudstream3.utils.ExtractorLink): DrmSpec? {
        val cls = link.javaClass
        // Walk up the hierarchy so a plugin's own DrmExtractorLink subclass is
        // still detected.
        var c: Class<*>? = cls
        var isDrm = false
        while (c != null) {
            if (c.name == "com.lagradost.cloudstream3.utils.DrmExtractorLink") { isDrm = true; break }
            c = c.superclass
        }
        if (!isDrm) return null
        fun str(name: String): String? =
            runCatching { cls.getMethod(name).invoke(link) as? String }
                .getOrNull()?.ifBlank { null }
        val kid = str("getKid")
        val key = str("getKey")
        val kty = str("getKty")
        val licenseUrl = str("getLicenseUrl")
        // `uuid` is an inline value class over java.util.UUID; Kotlin also emits
        // a synthetic java.util.UUID bridge getter — prefer the bridge, and
        // fall back to the value-class form's toString().
        var uuid: String? = null
        for (m in cls.methods) {
            // parameterTypes (API 1) rather than parameterCount (API 26+).
            if (m.name != "getUuid" || m.parameterTypes.isNotEmpty()) continue
            val v = runCatching { m.invoke(link) }.getOrNull() ?: continue
            val s = v.toString().ifBlank { null } ?: continue
            if (v is java.util.UUID) { uuid = s; break } else if (uuid == null) uuid = s
        }
        val params: Map<String, String> = runCatching {
            (cls.getMethod("getKeyRequestParameters").invoke(link) as? Map<*, *>)
                ?.entries
                ?.mapNotNull { (k, v) -> (k as? String)?.let { it to (v?.toString() ?: "") } }
                ?.toMap()
        }.getOrNull().orEmpty()
        if (kid == null && key == null && licenseUrl == null && uuid == null && params.isEmpty()) return null
        return DrmSpec(kid = kid, key = key, uuid = uuid, kty = kty, licenseUrl = licenseUrl, keyRequestParameters = params)
    }

    /** Non-streamable schemes an extractor can never meaningfully produce
     *  (data:/javascript:/about:/mailto:...) — never become playable sources. */
    private fun isStreamableScheme(url: String): Boolean {
        val scheme = runCatching { java.net.URI(url).scheme?.lowercase() }.getOrNull()
            ?: return url.startsWith("http") // be lenient with scheme-less URLs
        return scheme == "http" || scheme == "https" || scheme == "magnet" || scheme == "torrent"
    }

    /** Generic URL-scanner extractors sometimes capture non-content links from
     *  the embed page — the classic being the SVG xmlns namespace
     *  (http://www.w3.org/2000/svg), which is not a stream and would otherwise
     *  surface as a bogus "playable" source. */
    private fun isGarbageUrl(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull() ?: return false
        return host == "w3.org" || host.endsWith(".w3.org")
    }

    private fun fullCause(e: Throwable): String {
        val sb = StringBuilder()
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth < 4) {
            if (depth > 0) sb.append("\nCaused by: ")
            sb.append("${t.javaClass.simpleName}: ${t.message}\n")
            sb.append(t.stackTrace.take(5).joinToString("\n") { "    at $it" })
            t = t.cause
            depth++
        }
        return sb.toString()
    }

    private fun shortCause(e: Throwable): String {
        val heads = ArrayList<String>(3)
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth < 3) {
            val one = ((t.javaClass.simpleName + ": " + (t.message ?: "")).replace(Regex("\\s+"), " ").trim())
                .take(220)
            if (one.isNotBlank()) heads += one
            t = t.cause
            depth++
        }
        return heads.joinToString(" — ").take(400)
    }

    private fun repairLoadLinksData(
        data: String,
        episode: com.hikari.app.data.Episode?,
        item: MediaItem,
    ): String {
        val t = data.trim()
        if (!t.startsWith("{") || !t.endsWith("}")) return data
        return try {
            val o = JSONObject(t)
            if (o.has("id") && o.optString("id").isNotBlank()) return data
            val fallbackId = episode?.id?.takeIf { it.isNotBlank() } ?: item.id
            o.put("id", fallbackId)
            o.toString()
        } catch (_: Throwable) {
            data
        }
    }

    /** iStreamFlare-style providers put a JSON video-id list in LoadResponse.url
     *  with `"url": null` when their API lookup failed. */
    private fun isHollowPayload(url: String): Boolean = HOLLOW_URL_RE.containsMatchIn(url)

    /** Drops the cached load for a (possibly rewritten) url so a fresh load()
     *  runs — the hollow movie payload is cached under BOTH the original id
     *  and the payload itself, so clear both. */
    private fun invalidateHollow(url: String) {
        loadCache.remove(url)
        val it = loadCache.entries.iterator()
        while (it.hasNext()) {
            val (k, v) = it.next()
            if (v.second.url == url) it.remove()
        }
    }

    /** Finds the original search/page id that produced a (rewritten) url. */
    private fun originalIdOf(url: String): String? {
        for ((k, v) in loadCache) if (v.second.url == url) return k
        return null
    }

    fun evictLoadCacheFor(url: String) {
        loadCache.remove(url)
        val it = loadCache.entries.iterator()
        while (it.hasNext()) {
            val (k, v) = it.next()
            if (v.second.url == url || k == url) it.remove()
        }
    }

    /** One load() attempt, treating a plugin crash as a miss (never throwing). */
    private suspend fun tryLoad(a: MainAPI, id: String): LoadResponse? = try {
        // Like the CloudStream app's own APIRepository.load: fix the url first
        // (relative search urls become mainUrl-absolute; JSON data payloads
        // pass through untouched), so every plugin gets exactly what it would
        // get in the CloudStream app.
        a.load(a.fixUrl(id))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        null
    }

    private suspend fun loadResponse(id: String): LoadResponse? {
        cachedLoad(id)?.let { return it }
        val a = api ?: return null
        // Some providers' load() walks many pages (e.g. PimpBunny model pages
        // paginate up to 50) — cap it so the detail screen can never hang.
        val r = try {
            withTimeoutOrNull(com.hikari.app.net.NetTuning.timeout(45_000)) {
                val first = tryLoad(a, id)
                // Providers like iStreamFlare build a JSON video-id string and
                // fill its `url` field during load(); a hollow one (url:null,
                // or blank) usually means the very first network lookup after a
                // cold start failed, so give load() one retry before caching a
                // dead response forever.
                val hollow = first == null || first.url.isBlank() || isHollowPayload(first.url)
                if (!hollow) first else tryLoad(a, id)
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            null
} ?: return null
        putLoad(id, r)
        return r
    }

    private fun SearchResponse.toMediaItem(): MediaItem? {
        // `url` is what the item is opened by, so an item without one is not
        // openable — but the NAME is NOT required. A lot of CloudStream plugins
        // deliberately post a poster-only card: they build every search response
        // with an empty name and let the poster carry the title, e.g. CNC
        // Verse's NetflixMirrorProvider, whose whole home page is
        //
        //     newAnimeSearchResponse("", Id(id).toJson()) { posterUrl = … }
        //
        // (its search() path passes a real `it.t`, which is why SEARCH works while
        // the catalogue looked empty). CloudStream renders those cards fine, and
        // this converter used to drop every one of them for the blank name — so
        // every row of such an extension came back empty and the extension looked
        // like it had no catalogue at all, while the same extension in CloudStream
        // listed it. The real title arrives from the plugin's own load() when the
        // item is opened; until then the card is the poster, exactly as it is in
        // CloudStream.
        if (url.isBlank()) return null
        val mt = when (type) {
            // NSFW providers (LeakPorner, KanAV, …) label their single-video
            // results NSFW — treat as movies; getMeta later corrects actor
            // pages to SERIES from the LoadResponse type.
            TvType.Movie, TvType.AnimeMovie, TvType.NSFW -> MediaType.MOVIE
            TvType.TvSeries, TvType.Anime, TvType.Cartoon, TvType.OVA, TvType.AsianDrama -> MediaType.SERIES
            else -> MediaType.UNKNOWN
        }
        val year = when (this) {
            is com.lagradost.cloudstream3.MovieSearchResponse -> this.year
            is com.lagradost.cloudstream3.AnimeSearchResponse -> this.year
            is com.lagradost.cloudstream3.TvSeriesSearchResponse -> this.year
            else -> null
        }
        return MediaItem(
            providerId = config.id,
            id = url,
            title = name,
            type = mt,
            posterUrl = posterUrl,
            year = year,
            nsfw = type == TvType.NSFW,
            quality = TitleQuality.fromExtensionQuality(quality?.name),
        ).also { recordPosterHeaders(posterUrl, posterHeaders) }
    }

    private fun com.lagradost.cloudstream3.Episode.toHikari(
        respHeaders: Map<String, String>?,
    ): Episode {
        val num = episode ?: data?.substringAfterLast("|")?.toIntOrNull() ?: 1
        return Episode(
            number = num,
            id = data ?: num.toString(),
            name = name ?: "Episode $num",
            image = posterUrl,
            season = season?.takeIf { it > 0 } ?: 1,
        ).also { recordPosterHeaders(posterUrl, respHeaders) }
    }
}
