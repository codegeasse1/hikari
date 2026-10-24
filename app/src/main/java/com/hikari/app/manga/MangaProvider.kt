package com.hikari.app.manga

import android.content.Context
import android.os.SystemClock
import com.hikari.app.HikariApp
import com.hikari.app.data.CatalogRef
import com.hikari.app.data.Episode
import com.hikari.app.data.Logs
import com.hikari.app.data.MediaItem
import com.hikari.app.data.MediaType
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.StreamSource
import com.hikari.app.net.Http
import com.hikari.app.providers.ContentProvider
import com.hikari.app.providers.ProviderGate
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.MangaSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/**
 * One manga engine, exposed to the rest of the app as an ordinary
 * [ContentProvider].
 *
 * Everything Hikari already does with a provider therefore works for manga too:
 * it appears in the Extensions list, in the Home picker, in Search, in the
 * engine filter — because it IS one. What differs is only what the calls mean:
 * a "catalog" is the source's Popular or Latest list, an "episode" is a chapter,
 * and a "stream" is one PAGE of that chapter (its image), which is exactly what
 * the reader needs and lets the whole page list travel through the existing
 * episode/stream plumbing unchanged.
 *
 * Manga sources are third-party code and every call can throw `LinkageError` or
 * `AbstractMethodError` (an extension built against a newer extensions-lib), so
 * every call is wrapped and reports a short reason through
 * [com.hikari.app.providers.ProviderGate]/`lastOutcome` like the anime engine
 * does — a broken extension shows a line in the UI instead of crashing a screen.
 */
class MangaProvider(override val config: ProviderConfig) : ContentProvider {

    /** "Popular" / "Latest" — the two lists every catalogue source offers. */
    companion object {
        const val CATALOG_POPULAR = "popular"
        const val CATALOG_LATEST = "latest"
        /**
         * Ceilings on the extension's own calls. Several sites hang instead
         * of failing — a catalogue read that never returns is the reported
         * "takes forever to load" extension. Past these the call is
         * abandoned and the honest reason ("the site took too long") is
         * recorded for the empty state; the funnel in
         * [com.hikari.app.data.ContentRepository.loadCatalogPage] carries
         * its own ceiling too, so the screen can never spin longer than the
         * shorter of the two.
         */
        const val EXT_CALL_TIMEOUT_MS = 30_000L
        const val WARM_SITE_TIMEOUT_MS = 15_000L
        val lastOutcome = ConcurrentHashMap<String, String>()
        /** True when the engine's last recorded outcome for [providerId] looks
         *  like a bot wall (HTTP 403/429/503/Cloudflare) rather than a dead
         *  site or a broken extension — the ONLY case where opening the site
         *  in the WebView can help. Catalogue screens gate their "Verify site"
         *  nudge/button on this, so a site that is simply down (521/404/500)
         *  says so instead of sending the reader to pass a check that was
         *  never the issue. No outcome yet (never tried) counts as true — the
         *  button is a tool until a failure says otherwise. */
        fun isWallOutcome(providerId: String): Boolean {
            val s = lastOutcome[providerId] ?: return true
            if (s.startsWith("✓") || s.startsWith("✔") || s.startsWith("○")) return false
            return WALL_RE.containsMatchIn(s)
        }
        private val WALL_RE = Regex(
            "\\b(403|429|503)\\b|cloudflare|just a moment|one moment, please|wsidchk" +
                "|verify you are human|ddos",
            RegexOption.IGNORE_CASE,
        )
        // Same universal preview-set guard as the Anymex engine (see there):
        // a page set repeating verbatim for another chapter is previews.
        private val previewSets = ConcurrentHashMap<String, String>()
        fun isPreviewUrl(u: String): Boolean {
            val low = u.lowercase()
            return "thumb" in low || "preview" in low || "?type=" in low || "thumbnail" in low
        }
        // Same cover served with per-page signed queries: exact-URL dedup
        // never sees them as equal, so compare query-stripped.
        fun normImg(u: String): String = u.substringBefore("?").substringBefore("#").trim().lowercase()
        fun isPreviewSet(providerId: String, chapterUrl: String, urls: List<String>): Boolean {
            if (urls.isEmpty()) return false
            val h = urls.sorted().joinToString("|").hashCode().toString() + "|" + urls.size
            val key = providerId + "|" + h
            val first = previewSets.putIfAbsent(key, chapterUrl)
            if (first != null && first != chapterUrl) return true
            if (previewSets.size > 500) previewSets.clear()
            return false
        }
    }

    private val context: Context get() = HikariApp.instance

    private val pkgName: String get() = MangaExtensionManager.packageOf(config)

    private val sourceIndex: Int get() = MangaExtensionManager.indexOf(config)

    private val extFile: File get() {
        val direct = File(config.url)
        if (config.url.isNotBlank() && direct.exists()) return direct
        // The stored path can go stale (a backup restore, a moved files dir);
        // the package name is the durable half of the identity.
        return MangaExtensionManager.extensionFile(HikariApp.instance, pkgName)
    }

    private fun source(): CatalogueSource? {
        // Bundled Kotatsu parsers (same extraction code the Kotatsu app runs)
        // need no extension file — the bridge speaks CatalogueSource directly.
        if (com.hikari.app.kotatsu.KotatsuRegistry.isKotatsuId(config.id)) {
            return runCatching {
                com.hikari.app.kotatsu.KotatsuBridge.forSource(
                    com.hikari.app.kotatsu.KotatsuRegistry.enumNameOf(config.id),
                )
            }.getOrNull()
        }
        // Fork parsers (skepsun kototoro / vendored hany18h) likewise ship
        // inside the app — their bridges speak CatalogueSource directly.
        if (com.hikari.app.kotatsu.fork.ForkRegistry.isForkSkId(config.id)) {
            return runCatching {
                com.hikari.app.kotatsu.fork.SkepsunBridge.forSource(
                    com.hikari.app.kotatsu.fork.ForkRegistry.enumNameOfForkId(config.id),
                )
            }.getOrNull()
        }
        if (com.hikari.app.kotatsu.fork.ForkRegistry.isForkHanyId(config.id)) {
            return runCatching {
                com.hikari.app.kotatsu.fork.Hany18hBridge.forSource(
                    com.hikari.app.kotatsu.fork.ForkRegistry.enumNameOfForkId(config.id),
                )
            }.getOrNull()
        }
        return MangaExtensionManager.sourceOf(context, extFile, sourceIndex) as? CatalogueSource
    }

    /** True when this row is a plain text-chapter (novel) source: no manga
     *  methods, no catalogue or search index — chapters + text only. */
    private val isNovel: Boolean get() = MangaExtensionManager.isNovelId(config.id) ||
        com.hikari.app.kotatsu.fork.ForkRegistry.isForkNovelId(config.id)

    /** The novel source for a novel row (null for manga rows). */
    private fun novelSrc(): Source? =
        MangaExtensionManager.novelSourceOf(context, extFile, MangaExtensionManager.novelIndexOf(config))

    private fun missingReason(): String = when {
        com.hikari.app.kotatsu.KotatsuRegistry.isKotatsuId(config.id) ->
            "The Kotatsu source could not be created"
        com.hikari.app.kotatsu.fork.ForkRegistry.isForkMangaId(config.id) ->
            "The fork source could not be created"
        !extFile.exists() -> "The extension file is missing — reinstall it in Extensions"
        else -> MangaExtensionManager.lastError ?: "The extension could not be loaded"
    }

    private fun <T> fail(msg: String, fallback: T): T {
        val short = "✗ " + msg.take(200)
        lastOutcome[config.id] = short
        Logs.log("Manga", "${config.name}: $msg")
        return fallback
    }

    /** Fork `NovelChapterContent.html` (complete rendered HTML) as plain reader
     *  text: paragraphs in order, blank when there is nothing readable. */
    private fun htmlToNovelText(html: String): String {
        if (html.isBlank()) return ""
        return runCatching {
            val doc = org.jsoup.Jsoup.parse(html)
            doc.select("script, style, noscript").remove()
            val paras = doc.select("p").map { it.text().trim() }.filter { it.isNotBlank() }
            if (paras.isNotEmpty()) paras.joinToString("\n\n") else doc.body()?.wholeText().orEmpty().trim()
        }.getOrDefault("").trim()
    }

    /**
     * The site this engine reads, for a warm-up.
     *
     * A manga provider's `config.url` is the LOCAL `.ext` path, so the site has
     * to come out of the extension's own source (`baseUrl`) — BLOCKING, which is
     * fine here because every caller is already inside [gate] on IO. Cached per
     * provider by the manager.
     */
    private fun siteUrl(): String? = runCatching {
        MangaExtensionManager.siteUrlOf(config)
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * Be the browser for one failed request — WITHOUT waiting for the user to
     * tap the globe.
     *
     * The reported shape was: a manga catalogue (and its chapter list) comes up
     * empty, no verification is ever offered, and the only thing that makes it
     * load is opening the site in the WebView by hand and closing it again —
     * i.e. the site wanted a real browser session and the app never tried to give
     * it one. The extension's own client only solves a challenge it can
     * RECOGNISE (see [com.hikari.app.net.ExtensionCloudflareInterceptor]), and a
     * manga source that builds its own OkHttp stack, or a site that answers the
     * XHR with an empty 200, leaves nothing to recognise — so the page stayed
     * blank.
     *
     * This loads the site once in the offscreen WebView ([CloudflareSolver.warm],
     * spaced per host) and says whether that earned something worth retrying.
     * NOTHING opens on the user's screen.
     *
     * With a ceiling: the offscreen load hangs indefinitely on some hosts,
     * and without it a catalogue that was merely slow became one that never
     * answers (the reported "takes forever to load" extensions).
     */
    private suspend fun warmSite(): Boolean =
        withTimeoutOrNull(WARM_SITE_TIMEOUT_MS) {
            val site = siteUrl() ?: return@withTimeoutOrNull false
        val ua = runCatching { HikariApp.instance.effectiveWebViewUa() }.getOrNull().orEmpty()
        val ok = runCatching {
            com.hikari.app.net.CloudflareSolver.warm(
                url = site,
                userAgent = ua.ifBlank { "Mozilla/5.0" },
            )
        }.getOrDefault(false)
        if (ok) Logs.log("Manga", "${config.name}: warmed $site after a refused request")
        ok
    } ?: false

    /**
     * Records a site as needing the user's own verification when a failure is a
     * bot wall rather than a broken extension — HTTP 403/429/503/Cloudflare.
     *
     * The anime engine has always done this ([
     * com.hikari.app.aniyomi.AniyomiProvider]); the manga engine did not, so a
     * walled manga site was never named to the UI and was never skipped by the
     * cross-extension search — it just answered nothing, every time.
     */
    private fun noteWall(text: String) {
        if (text.isBlank() || !WALL_MESSAGE.containsMatchIn(text)) return
        siteUrl()?.let { com.hikari.app.net.CloudflareVerifier.markBlocked(it) }
    }

    /** A wall's own words. Deliberately not "any 4xx" — a 404 is a missing page,
     *  not a challenge the user can pass. */
    private val WALL_MESSAGE = Regex(
        "\\b(403|429|503)\\b|cloudflare|just a moment|one moment, please|wsidchk" +
            "|verify you are human|ddos",
        RegexOption.IGNORE_CASE,
    )

    /**
     * True when this provider reads a MangaTime-shaped site — by its host, or
     * by the extension class itself. The native tRPC fallback below only ever
     * runs for those sources: its request shape is MangaTime's own
     * `search.searchSeries` contract, not a generic manga API.
     */
    private fun isMangaTimeSite(): Boolean {
        if (siteUrl().orEmpty().contains("mangatime", ignoreCase = true)) return true
        return runCatching { source()?.javaClass?.name }.getOrNull()
            ?.contains("mangatime", ignoreCase = true) == true
    }

    /**
     * MangaTime's own catalog query, run directly — the exact call its
     * extension makes (`searchSeries(page, limit 24, sortBy, "desc", query)`
     * against `/api/trpc/search.searchSeries?batch=1&input=…`), so a source
     * whose extension path answers empty-or-failed while the site is up still
     * lists Popular, Latest and search. Returned as plain [SManga]s in the
     * extension's own url shape (`/$type/$slug#$id`), so details, chapters and
     * pages keep flowing through the extension unchanged. BLOCKING — every
     * caller is already inside [gate] on IO.
     */
    private fun nativeMangaTime(ref: CatalogRef?, page: Int, query: String?): List<SManga>? {
        val base = siteUrl()?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return null
        val sortBy = when {
            !query.isNullOrBlank() -> "popularity"
            ref?.id == CATALOG_LATEST -> "recent"
            else -> "popularity"
        }
        val qJson = if (query.isNullOrBlank()) "null" else JSONObject.quote(query)
        val input = "{\"0\":{\"json\":{\"page\":${page.coerceAtLeast(1)}," +
            "\"limit\":24,\"sortBy\":\"$sortBy\",\"sortOrder\":\"desc\",\"query\":$qJson}}}"
        val url = base + "/api/trpc/search.searchSeries?batch=1&input=" +
            runCatching { URLEncoder.encode(input, "UTF-8") }.getOrNull().orEmpty()
        val body = runCatching { com.hikari.app.net.Http.get(url) }
            .getOrNull()?.use { r -> if (r.isSuccessful) r.body?.string() else null }
            ?: return null
        val json = runCatching { JSONArray(body) }.getOrNull()
            ?.optJSONObject(0)?.optJSONObject("result")
            ?.optJSONObject("data")?.optJSONObject("json") ?: return null
        val results = json.optJSONArray("results") ?: return emptyList()
        val out = ArrayList<SManga>(results.length())
        for (i in 0 until results.length()) {
            val o = results.optJSONObject(i) ?: continue
            val title = o.optString("title").trim()
            val slug = o.optString("slug").trim()
            if (title.isBlank() || slug.isBlank()) continue
            val type = o.optString("type").trim().ifBlank { "manga" }
            val id = o.optString("id").trim()
            val cover = o.optString("coverUrl").trim()
            val thumb = when {
                cover.startsWith("http") -> cover
                cover.isNotBlank() -> base + "/" + cover.trimStart('/')
                else -> null
            }
            out += SManga.create().apply {
                this.title = title
                this.url = "/$type/$slug#$id"
                this.thumbnail_url = thumb
            }
        }
        return out
    }

    /** See [com.hikari.app.aniyomi.AniyomiProvider.gate]: metadata and catalogs
     *  are what the user is looking at, and a stream lookup is background
     *  work that must never queue in front of them. */
    private suspend fun <T> gate(
        lane: ProviderGate.Lane = ProviderGate.Lane.INTERACTIVE,
        block: suspend () -> T,
    ): T = ProviderGate.withProvider(config.id, lane) {
        withContext(Dispatchers.IO) { block() }
    }

    // ---- Catalogues ----

    override suspend fun catalogs(): List<CatalogRef> = gate {
        // A novel source exposes no Popular/Latest index — only chapters of a
        // known title. Honest empty, never an error.
        if (isNovel) {
            lastOutcome[config.id] = "○ text source — no catalogue; open a title to read"
            return@gate emptyList()
        }
        val src = source() ?: return@gate emptyList()
        val out = mutableListOf(
            CatalogRef(config.id, MediaType.SERIES, CATALOG_POPULAR, "Popular", "manga"),
        )
        if (runCatching { src.supportsLatest }.getOrDefault(true)) {
            out += CatalogRef(config.id, MediaType.SERIES, CATALOG_LATEST, "Latest", "manga")
        }
        out
    }

    override suspend fun getCatalog(ref: CatalogRef, page: Int): List<MediaItem> = gate {
        if (isNovel) {
            lastOutcome[config.id] = "○ text source — no catalogue; open a title to read"
            return@gate emptyList()
        }
        val src = source() ?: return@gate fail(missingReason(), emptyList())
        val ask: suspend () -> List<eu.kanade.tachiyomi.source.model.SManga> = {
            when (ref.id) {
                CATALOG_LATEST -> src.getLatestUpdates(page)
                else -> src.getPopularManga(page)
            }.mangas
        }
        val first = runCatching { withTimeoutOrNull(EXT_CALL_TIMEOUT_MS) { ask() } }
        var mangas = first.getOrNull()
        var failure = first.exceptionOrNull()?.takeIf { it !is kotlinx.coroutines.CancellationException }
            ?.let { reason(it) }
        if (mangas == null && failure == null) {
            // The bounded ask gave up on a hung site (null verdict, no
            // exception): name it so the catalogue screen can say the site
            // timed out instead of spinning or crying wolf about a block.
            failure = "the site took too long to answer (over ${EXT_CALL_TIMEOUT_MS / 1000}s)"
        }
        val catalogHung = mangas == null && first.exceptionOrNull() == null
        first.exceptionOrNull()?.let { if (it is kotlinx.coroutines.CancellationException) throw it }
        // A REFUSED catalogue: either an exception, or (the case that used to be
        // invisible) a page the site served with nothing in it because the request
        // never got past its browser check. Be the browser once and ask again —
        // see [warmSite]; page 1 only, since an empty page 2+ is just the end of
        // the list. A hung first ask skips the warm-up retry: re-asking a host
        // that just hung only doubles the wait.
        if (page == 1 && (mangas == null || mangas.isEmpty()) && !catalogHung && warmSite()) {
            val second = runCatching { withTimeoutOrNull(EXT_CALL_TIMEOUT_MS) { ask() } }
            second.exceptionOrNull()?.let {
                if (it is kotlinx.coroutines.CancellationException) throw it
                // The RETRY's reason is the one worth reporting: the first
                // failure is often the wall this warm-up was meant to clear.
                failure = reason(it)
                mangas = null
            }
            if (second.isSuccess) {
                mangas = second.getOrNull()
                failure = null
            }
        }
        if (mangas == null) {
            // The extension path failed outright. On MangaTime-shaped sources the
            // catalog is a plain tRPC call (see nativeMangaTime) — ask the site
            // directly before reporting the failure.
            if (isMangaTimeSite()) {
                val native = runCatching { nativeMangaTime(ref, page, null) }.getOrNull()
                if (!native.isNullOrEmpty()) {
                    lastOutcome[config.id] = "✓ ${native.size} title(s)"
                    return@gate native.map { toItem(it, src) }
                }
            }
            val why = failure ?: "unknown error"
            noteWall(why)
            return@gate fail("catalog failed: $why", emptyList())
        }
        // An EMPTY page is not a success, and calling it one is what left the
        // reader staring at "The site may be blocking or down" with nothing else
        // to go on: no exception was thrown, so no reason was recorded anywhere,
        // and the one thing the app could have said ("the site answered, but this
        // list came back with no titles in it") was lost. It is reported now — it
        // is a different problem from a block, and it is the one the reader can
        // take back to the extension's own page (the site's markup moved), rather
        // than to a Cloudflare check that was never the issue.
        if (mangas.isEmpty()) {
            // The reported MangaTime shape: the extension answers 200 with no
            // titles while the site itself is up. Its catalog is one tRPC query
            // (see nativeMangaTime) — run that exact query directly. Page 1
            // only: an empty page 2+ is the end of the list, not a failure.
            if (page == 1 && isMangaTimeSite()) {
                val native = runCatching { nativeMangaTime(ref, page, null) }.getOrNull()
                if (!native.isNullOrEmpty()) {
                    lastOutcome[config.id] = "✓ ${native.size} title(s)"
                    return@gate native.map { toItem(it, src) }
                }
            }
            lastOutcome[config.id] =
                "✗ the site answered, but ${ref.name.lowercase()} came back with no titles " +
                    "(page $page) — its markup may have changed"
        } else {
            lastOutcome[config.id] = "✓ ${mangas.size} title(s)"
        }
        mangas.map { toItem(it, src) }
    }

    override suspend fun search(query: String, page: Int): List<MediaItem> = gate {
        if (isNovel) {
            lastOutcome[config.id] = "○ text source — no search index; open a title to read"
            return@gate emptyList()
        }
        val src = source() ?: return@gate fail(missingReason(), emptyList())
        val ask: suspend () -> List<eu.kanade.tachiyomi.source.model.SManga> = {
            val filters = runCatching { src.getFilterList() }.getOrDefault(FilterList())
            src.getSearchManga(page, query, filters).mangas
        }
        val first = runCatching { withTimeoutOrNull(EXT_CALL_TIMEOUT_MS) { ask() } }
        var found = first.getOrNull()
        var failure = first.exceptionOrNull()?.takeIf { it !is kotlinx.coroutines.CancellationException }
            ?.let { reason(it) }
        first.exceptionOrNull()?.let { if (it is kotlinx.coroutines.CancellationException) throw it }
        // A hung site abandons the ask with NO exception (null verdict): name
        // it, and skip the warm-up retry — warming a host that just hung only
        // doubles the wait.
        val searchHung = found == null && failure == null
        if (searchHung) failure = "the site took too long to answer (over ${EXT_CALL_TIMEOUT_MS / 1000}s)"
        // Only a FAILED search is retried: an empty result list is a perfectly
        // ordinary answer ("this engine has no such title"), and warming the
        // site for every miss would be a WebView load per search. A 403 from a
        // browser check is not an answer.
        if (found == null && !searchHung && warmSite()) {
            val second = runCatching { withTimeoutOrNull(EXT_CALL_TIMEOUT_MS) { ask() } }
            second.exceptionOrNull()?.let {
                if (it is kotlinx.coroutines.CancellationException) throw it
                failure = reason(it)
            }
            if (second.isSuccess) {
                found = second.getOrNull()
                failure = null
            }
        }
        if (found == null) {
            if (query.isNotBlank() && isMangaTimeSite()) {
                val native = runCatching { nativeMangaTime(null, page, query) }.getOrNull()
                if (!native.isNullOrEmpty()) {
                    lastOutcome[config.id] = "✓ ${native.size} result(s)"
                    return@gate native.map { toItem(it, src) }
                }
            }
            val why = failure ?: "unknown error"
            noteWall(why)
            return@gate fail("search failed: $why", emptyList())
        }
        lastOutcome[config.id] = "✓ ${found.size} result(s)"
        found.map { toItem(it, src) }
    }

    /**
     * A TAG search, not a title search: tapping a genre chip (Fantasy) must
     * return manga that CARRY the tag, not manga with the word in the title.
     * Runs the extension's own genre filter (the same filter list its browse
     * page offers) with an empty keyword, so the site answers from its tag
     * index. Returns null when the extension declares no usable genre filter
     * (callers then fall back to a keyword search plus the client-side tag
     * filter in MangaScreen) — null is "unsupported", never "no results".
     */
    suspend fun searchByGenre(genre: String, page: Int): List<MediaItem>? = gate {
        if (isNovel) return@gate null
        val src = source() ?: return@gate null
        val filters = runCatching { src.getFilterList() }.getOrDefault(FilterList())
        if (filters.isEmpty()) return@gate null
        val want = genre.trim().lowercase()
        if (want.isEmpty()) return@gate null
        var armed = false
        for (f in filters) {
            val field = f.name.trim().lowercase()
            val isGenreField = field.contains("genre") || field.contains("tag") || field.contains("categor")
            when (f) {
                is Filter.Select<*> -> {
                    if (!isGenreField) continue
                    val idx = f.values.indexOfFirst { it.toString().trim().lowercase() == want }
                    if (idx >= 0) { f.state = idx; armed = true }
                }
                is Filter.Group<*> -> {
                    if (!isGenreField) continue
                    for (child in f.state) {
                        if (child is Filter.CheckBox && child.name.trim().lowercase() == want) { child.state = true; armed = true }
                        if (child is Filter.TriState && child.name.trim().lowercase() == want) { child.state = Filter.TriState.STATE_INCLUDE; armed = true }
                    }
                }
                is Filter.CheckBox -> {
                    if (isGenreField && f.name.trim().lowercase() == want) { f.state = true; armed = true }
                }
                is Filter.TriState -> {
                    if (isGenreField && f.name.trim().lowercase() == want) { f.state = Filter.TriState.STATE_INCLUDE; armed = true }
                }
                else -> {}
            }
        }
        if (!armed) return@gate null
        val found = runCatching { src.getSearchManga(page, "", filters).mangas }.getOrNull() ?: return@gate null
        lastOutcome[config.id] = "✓ ${found.size} result(s)"
        found.map { toItem(it, src) }
    }

    // ---- Details ----

    /**
     * The extensions-lib 1.6 combined call — the ONE entry point a modern manga
     * extension actually implements — and the reason this engine could not list
     * chapters for a whole generation of sources.
     *
     * `getMangaDetails`/`getChapterList` (and their deprecated Rx `fetch*`
     * forms) are the OLD entry points: a source that predates extensions-lib
     * 1.6 answers them, and a `KeiSource` (every keiyoushi source since 2026)
     * **does not implement them at all**. Such a source overrides
     * `getMangaUpdate(manga, chapters, fetchDetails, fetchChapters)` instead and
     * leaves the two old calls to the base class — whose
     * `mangaDetailsRequest`/`chapterListRequest` build "baseUrl + manga.url".
     *
     * That is exactly the reported `chapters failed: HttpException: HTTP error
     * 404` on Comix. The catalogue hands out `SManga.url = "/12345-slug"` (the
     * extension's own `getMangaUrl` is what turns it into
     * "https://comix.to/title/12345-slug"), so the old chapter call asked the
     * site for "https://comix.to/12345-slug" — a page that does not exist — and
     * the site answered 404. Comix's chapter list is an API call that must be
     * SIGNED with a cipher the extension only learns inside its own
     * `getMangaUpdate`, so the old path could never have worked for it whatever
     * the URL looked like.
     *
     * Nekoread drives every manga extension through `getMangaUpdate` for this
     * reason, and this is the same call the ANIYOMI half of this app has always
     * used (`getAnimeEpisodeUpdate`, see
     * [com.hikari.app.aniyomi.AniyomiProvider]). For a source that predates the
     * combined call, Hikari's vendored `MangaSource` bridges `getMangaUpdate`
     * straight back to `getMangaDetails`/`getChapterList`, so nothing changes
     * for it.
     *
     * ONE flag per call, never both: `fetchDetails = true, fetchChapters =
     * false` IS "give me details", and the reverse IS "give me chapters" — so
     * each screen costs exactly the requests it used to, on an extension that
     * can actually answer them. (`fetchChapters = true` on the details call
     * would make every title's page fetch a chapter list nothing is going to
     * read — a second round trip per open, for nothing.)
     *
     * [gate] is what makes this safe for the sources that refuse it: `KeiSource`
     * throws if `getMangaUpdate` is in flight twice for the same manga, and
     * [ProviderGate] serialises every call into one extension instance.
     */
    private suspend fun updateOf(
        src: MangaSource,
        item: MediaItem,
        details: Boolean,
        chapters: Boolean,
    ) = src.getMangaUpdate(sm(item), emptyList(), details, chapters)

    override suspend fun getMeta(item: MediaItem): MediaItem = gate {
        // Novel details come straight from the novel source — no combined
        // update call, no catalogue round trip.
        if (isNovel) {
            val nsrc = novelSrc() ?: return@gate item
            return@gate try {
                val manga = nsrc.getMangaDetails(sm(item))
                lastOutcome[config.id] = "✓ details"
                item.copy(
                    title = manga.title.ifBlank { item.title },
                    posterUrl = manga.thumbnail_url?.takeIf { it.isNotBlank() } ?: item.posterUrl,
                    overview = manga.description?.takeIf { it.isNotBlank() } ?: item.overview,
                    genres = manga.getGenres().orEmpty().ifEmpty { item.genres },
                    year = item.year,
                    rating = manga.rating.takeIf { it > 0f }?.toDouble() ?: item.rating,
                )
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Logs.log("Manga", "${config.name}: details failed: ${reason(t)}")
                item
            }
        }
        val src = source() ?: return@gate item
        try {
            val manga = updateOf(src, item, details = true, chapters = false).manga
            lastOutcome[config.id] = "✓ details"
            item.copy(
                title = manga.title.ifBlank { item.title },
                posterUrl = manga.thumbnail_url?.takeIf { it.isNotBlank() } ?: item.posterUrl,
                overview = manga.description?.takeIf { it.isNotBlank() } ?: item.overview,
                genres = manga.getGenres().orEmpty().ifEmpty { item.genres },
                // The manga "status" rides along as the year-like line the
                // detail screen already draws; the manga screen shows it as the
                // published status instead.
                year = item.year,
                rating = manga.rating.takeIf { it > 0f }?.toDouble() ?: item.rating,
            )
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Logs.log("Manga", "${config.name}: details failed: ${reason(t)}")
            item
        }
    }

    /**
     * The chapter list, ALSO cached in [MangaStore] — the reader needs the whole
     * list for chapter navigation, the detail screen needs the names and dates,
     * and a source that is slow or blocked should not have to be asked twice for
     * the same thing.
     */
    override suspend fun getEpisodes(item: MediaItem): List<Episode>? = gate {
        // Novel chapters come straight from the novel source's chapter list —
        // the same Episode shape, cached in the same store, so the detail
        // screen and the novel reader work unchanged.
        if (isNovel) {
            val nsrc = novelSrc() ?: return@gate failEpisodes(missingReason())
            val raw = runCatching { nsrc.getChapterList(sm(item)) }.getOrNull()
            if (raw.isNullOrEmpty()) return@gate failEpisodes("no chapters in this answer")
            val sorted = sortChapters(raw)
            MangaStore.putChapters(item.providerId + "|" + item.id, sorted.map { it.toChapter() })
            lastOutcome[config.id] = "✓ ${sorted.size} chapter(s)"
            return@gate sorted.mapIndexed { i, c ->
                Episode(
                    number = i + 1,
                    id = c.url,
                    name = c.labelOf(),
                    image = null,
                    season = 1,
                )
            }
        }
        val src = source() ?: return@gate failEpisodes(missingReason())
        val ask: suspend () -> List<SChapter> = {
            updateOf(src, item, details = false, chapters = true).chapters
        }
        val first = runCatching { withTimeoutOrNull(EXT_CALL_TIMEOUT_MS) { ask() } }
        first.exceptionOrNull()?.let { if (it is kotlinx.coroutines.CancellationException) throw it }
        var raw = first.getOrNull()
        var failure = first.exceptionOrNull()?.let { reason(it) }
        if (raw == null && failure == null) {
            failure = "the site took too long to answer (over ${EXT_CALL_TIMEOUT_MS / 1000}s)"
        }
        // A hung first ask skips the warm-up retry (see the catalogue): the
        // site already burned the whole ceiling once.
        val episodesHung = raw == null && first.exceptionOrNull() == null
        // The same refused-site case as the catalogue: an empty (or failed)
        // chapter list on a site behind a browser check is what made the globe
        // button the only way to make a title's chapters appear. Warm the site
        // once and ask again.
        if (!episodesHung && (raw == null || raw.isEmpty())) {
            if (warmSite()) {
                val second = runCatching { withTimeoutOrNull(EXT_CALL_TIMEOUT_MS) { ask() } }
                second.exceptionOrNull()?.let {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    failure = reason(it)
                    raw = null
                }
                if (second.isSuccess) {
                    raw = second.getOrNull()
                    failure = null
                }
            }
        }
        if (raw == null) {
            val why = failure ?: "unknown error"
            noteWall(why)
            return@gate failEpisodes("chapters failed: $why")
        }
        // An empty answer is not chapters: some sites need the details call to
        // prime the session before the list exists. Prime once before calling
        // it empty, so a first-open "0 chapters" can still recover.
        if (raw.isEmpty()) {
            runCatching { withTimeoutOrNull(EXT_CALL_TIMEOUT_MS) { updateOf(src, item, details = true, chapters = true) } }
            val retry = runCatching { withTimeoutOrNull(EXT_CALL_TIMEOUT_MS) { ask() } }.getOrNull()
            if (!retry.isNullOrEmpty()) raw = retry
        }
        // Legacy-source bridge: some extensions answer getChapterList but leave
        // the combined update call empty — ask it directly before giving up.
        if (raw.isEmpty()) {
            val direct = runCatching { withTimeoutOrNull(EXT_CALL_TIMEOUT_MS) { src.getChapterList(sm(item)) } }.getOrNull()
            if (!direct.isNullOrEmpty()) raw = direct
        }
        if (raw.isEmpty()) return@gate failEpisodes("no chapters in this answer")
        val sorted = sortChapters(raw)
        MangaStore.putChapters(item.providerId + "|" + item.id, sorted.map { it.toChapter() })
        lastOutcome[config.id] = "✓ ${sorted.size} chapter(s)"
        sorted.mapIndexed { i, c ->
            Episode(
                number = i + 1,
                id = c.url,
                name = c.labelOf(),
                image = null,
                season = 1,
            )
        }
    }

    private fun failEpisodes(msg: String): List<Episode>? {
        // 72 characters cut the REASON off the line — "chapters failed: …okhttp"
        // and nothing more, exactly as reported. The detail screen prints this row
        // and has room for the whole sentence, and the log line below is never
        // truncated, so the cap only ever hid the one thing worth reading.
        lastOutcome[config.id] = "✗ " + msg.take(200)
        Logs.log("Manga", "${config.name}: $msg")
        // The cached list (when there is one) beats an empty screen: a source
        // that is temporarily down still lets the user open the chapters already
        // fetched for this title.
        return null
    }

    /**
     * The pages of one chapter, each as a [StreamSource]: `url` is the image URL
     * to load, `pageUrl` is the page's own URL on the site and `headers` carries
     * the source's own headers, so the reader's image loader can fetch from a
     * hotlink-protected CDN the way the extension itself would.
     *
     * This is Nekoread's `TachiyomiHttpSourceAdapter.getPageDescriptors` for this
     * app's plumbing: it builds each page from the extension's own `getPageList`
     * and resolves the image URL exactly as that class does (`page.imageUrl ?:
     * ext.getImageUrl(page)`, written back onto the page), so the pair the reader
     * downloads with is the pair the extension would send. The page URL is NOT
     * decoration: an extension's `imageRequest(page)` builds its Referer/Origin
     * from it, which is why a page fetched with an empty one came back refused or
     * scrambled.
     */
    override suspend fun getStreams(item: MediaItem, episode: Episode?): List<StreamSource> =
        gate(ProviderGate.Lane.BACKGROUND) {
        // A novel chapter is TEXT, not pages: each part travels as a
        // StreamSource with its text set and a blank url (the image reader
        // skips blank urls, so these can never leak into it). The novel
        // reader joins the parts in order.
        if (isNovel) {
            // Fork novels (skepsun / hany18h NovelChapterContent HTML) read
            // through their own bridges — no Tachiyomi novel source exists.
            if (com.hikari.app.kotatsu.fork.ForkRegistry.isForkNovelId(config.id)) {
                val chapter = episode ?: return@gate fail("No chapter selected", emptyList())
                return@gate try {
                    val html = when {
                        com.hikari.app.kotatsu.fork.ForkRegistry.isForkSkId(config.id) ->
                            com.hikari.app.kotatsu.fork.SkepsunBridge.forSource(
                                com.hikari.app.kotatsu.fork.ForkRegistry.enumNameOfForkId(config.id),
                            )?.chapterContent(chapter.id, chapter.name)
                        else ->
                            com.hikari.app.kotatsu.fork.Hany18hBridge.forSource(
                                com.hikari.app.kotatsu.fork.ForkRegistry.enumNameOfForkId(config.id),
                            )?.chapterContent(chapter.id, chapter.name)
                    }
                    val text = htmlToNovelText(html.orEmpty())
                    if (text.isBlank()) fail("chapter text unavailable", emptyList())
                    else {
                        lastOutcome[config.id] = "✓ 1 part"
                        listOf(
                            StreamSource(
                                name = chapter.name ?: "Chapter",
                                url = "",
                                text = text,
                                pageUrl = chapter.id,
                            )
                        )
                    }
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    fail("chapter text failed: ${reason(t)}", emptyList())
                }
            }
            val nsrc = novelSrc() ?: return@gate fail(missingReason(), emptyList())
            val chapter = episode ?: return@gate fail("No chapter selected", emptyList())
            val nt0 = SystemClock.elapsedRealtime()
            Logs.log("Manga", "${config.name} novel chapter start id=${chapter.id.take(80)}")
            return@gate try {
                val sch = ch(chapter.id).apply { name = chapter.name.orEmpty() }
                val pt0 = SystemClock.elapsedRealtime()
                val pages = runCatching { nsrc.getPageList(sch) }.getOrNull().orEmpty()
                Logs.log("Manga", "${config.name} novel pageList id=${chapter.id.take(60)} in ${SystemClock.elapsedRealtime() - pt0}ms pages=${pages.size}")
                val parts = pages.mapIndexedNotNull { i, pg ->
                    val ft0 = SystemClock.elapsedRealtime()
                    val text = runCatching { nsrc.fetchPageText(pg) }.getOrNull()?.trim().orEmpty()
                    Logs.log("Manga", "${config.name} novel page ${i + 1}/${pages.size} id=${chapter.id.take(40)} in ${SystemClock.elapsedRealtime() - ft0}ms chars=${text.length}")
                    if (text.isBlank()) null else StreamSource(
                        name = "Part ${i + 1}",
                        url = "",
                        text = text,
                        pageUrl = pg.url,
                    )
                }
                if (parts.isNotEmpty()) {
                    lastOutcome[config.id] = "✓ ${parts.size} part(s)"
                    Logs.log("Manga", "${config.name} novel chapter OK id=${chapter.id.take(60)} parts=${parts.size} chars=${parts.sumOf { it.text.length }} in ${SystemClock.elapsedRealtime() - nt0}ms")
                    parts
                } else {
                    // Single-text fallback: some novel sources serve the whole
                    // chapter as one page.
                    Logs.log("Manga", "${config.name} novel fallback single-text id=${chapter.id.take(60)}")
                    val st0 = SystemClock.elapsedRealtime()
                    val text = runCatching { nsrc.fetchPageText(Page(0, chapter.id)) }
                        .getOrNull()?.trim().orEmpty()
                    Logs.log("Manga", "${config.name} novel fallback id=${chapter.id.take(60)} in ${SystemClock.elapsedRealtime() - st0}ms chars=${text.length}")
                    if (text.isBlank()) fail("chapter text unavailable", emptyList())
                    else {
                        lastOutcome[config.id] = "✓ 1 part"
                        Logs.log("Manga", "${config.name} novel chapter OK id=${chapter.id.take(60)} parts=1 chars=${text.length} in ${SystemClock.elapsedRealtime() - nt0}ms")
                        listOf(
                            StreamSource(
                                name = chapter.name ?: "Chapter",
                                url = "",
                                text = text,
                                pageUrl = chapter.id,
                            )
                        )
                    }
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                fail("chapter text failed: ${reason(t)}", emptyList())
            }
        }
        val src = source() ?: return@gate fail(missingReason(), emptyList())
        val chapter = episode ?: return@gate fail("No chapter selected", emptyList())
        // Universal Webtoons strips (same class as the Anymex fix): a chapter
        // that IS a webtoons.com viewer link carries its own imageList — prefer
        // it over whatever the engine's page list serves (which can be the same
        // 2 previews for every chapter). Engine path below stays as fallback.
        if ("webtoons.com" in chapter.id.lowercase() && "viewer" in chapter.id.lowercase()) {
            val native = nativeWebtoonsPages(chapter.id)
            if (native.isNotEmpty()) {
                lastOutcome[config.id] = "✓ ${native.size} page(s) (native)"
                return@gate native
            }
        }
        try {
            // Mihon parity: a stale chapter entry (site moved the pages, session
            // rotated) fails getPageList with "Refresh Chapter List". Refresh the
            // chapter list once through the same combined call the chapter screen
            // uses (works for old and Kei sources alike) and retry on the fresh
            // entry before reporting failure.
            var pageList = runCatching { src.getPageList(ch(chapter.id)) }.getOrNull()
            // A site behind a browser check refuses the page list the same way
            // it refuses the chapter list (which warms in getEpisodes) — warm
            // once and ask again before the stale-entry retry, so a walled
            // chapter reads after verification instead of reporting failure.
            if (pageList == null && warmSite()) {
                pageList = runCatching { src.getPageList(ch(chapter.id)) }.getOrNull()
            }
            if (pageList == null) {
                runCatching {
                    val fresh = updateOf(src, item, details = false, chapters = true).chapters
                    val match = fresh.firstOrNull { it.url == chapter.id }
                        ?: fresh.firstOrNull { it.name == chapter.name && !chapter.name.isNullOrBlank() }
                    if (match != null) pageList = src.getPageList(match)
                }
            }
            val pages = pageList ?: return@gate fail("pages failed: Refresh Chapter List", emptyList())
            // The plain-client fallback (HikariPageSource.downloadTo) sends
            // exactly these headers: a source whose own headers name no Referer
            // gets one per page — the page's own URL first (hotlink guards
            // check the chapter, not the site root, and refuse a root Referer
            // with no wall to solve), the site root only when the page URL is
            // not absolute.
            val base = sourceHeaders(src)
            val hasRef = base.keys.any { it.equals("Referer", ignoreCase = true) }
            val hasUA = base.keys.any { it.equals("User-Agent", ignoreCase = true) }
            fun originOf(ref: String?): String? = runCatching {
                val u = android.net.Uri.parse(ref ?: return@runCatching null)
                if (u.scheme.isNullOrBlank() || u.host.isNullOrBlank()) null else u.scheme + "://" + u.host
            }.getOrNull()
            val site = siteUrl()
            val http = src as? HttpSource
            val origin = runCatching { http?.baseUrl?.trimEnd('/') }.getOrNull()
                .orEmpty().ifBlank { site?.trimEnd('/') }
            lastOutcome[config.id] = "✓ ${pages.size} page(s)"
            val seenPageUrls = HashSet<String>()
            val seenNorm = HashSet<String>()
            val outPages = pages.mapIndexed { i, page ->
                val url = pageUrl(src, page)
                if (url.isNotBlank()) page.imageUrl = url
                val headers = run {
                    var h = base
                    val ref = if (hasRef) null else (page.url.takeIf { it.startsWith("http") }
                        ?: page.url.takeIf { it.isNotBlank() }?.let { rel ->
                            origin?.let { o -> o.trimEnd('/') + "/" + rel.trimStart('/') }
                        } ?: site)
                    if (ref != null) h = h + ("Referer" to ref)
                    // Hotlink/CDN guards beyond Referer: Origin + UA travel with
                    // every page so a "Couldn't load page" is not one missing
                    // header away. Values already present are never overwritten.
                    val useRef = h["Referer"] ?: ref
                    val originV = originOf(useRef)
                    if (originV != null && h.keys.none { it.equals("Origin", ignoreCase = true) }) h = h + ("Origin" to originV)
                    if (!hasUA && h.keys.none { it.equals("User-Agent", ignoreCase = true) }) h = h + ("User-Agent" to com.hikari.app.net.Http.UA)
                    h
                }
                StreamSource(
                    name = "Page ${i + 1}",
                    url = url,
                    headers = headers,
                    pageUrl = page.url,
                )
                // A page without its own image is not a page: the fallback above
                // leaves the chapter URL itself as the image, so every page of
                // the chapter would download the same chapter/cover bytes and
                // the whole chapter (and every chapter like it) shows the same
                // image. Drop those, exact duplicates, and any page equal to
                // the detail poster (a covers-only answer), keeping story order.
            }.filter { p ->
                val poster = item.posterUrl?.trim().orEmpty()
                p.url.isNotBlank() && p.url != chapter.id && !isPreviewUrl(p.url) &&
                    (poster.isBlank() || p.url != poster) && (poster.isBlank() || normImg(p.url) != normImg(poster)) &&
                    seenPageUrls.add(p.url) && seenNorm.add(normImg(p.url))
            }
            // Same 2 previews in every chapter (universal): a set repeating
            // verbatim for another chapter is previews, not pages.
            if (outPages.isNotEmpty() && isPreviewSet(config.id, chapter.id, outPages.map { it.url })) {
                return@gate fail("chapter locked or pages-walled", emptyList())
            }
            if (outPages.size >= 2 && seenNorm.size == 1) return@gate fail("chapter locked or pages-walled", emptyList())
            if (outPages.isEmpty()) return@gate fail("pages failed: no readable pages", emptyList())
            lastOutcome[config.id] = "✓ ${outPages.size} page(s)"
            outPages
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            fail("pages failed: ${reason(t)}", emptyList())
        }
    }

    /**
     * The extension's own source, for the reader's page fetches.
     *
     * A chapter's pages are downloaded through THIS object — not through a
     * generic HTTP client — so the extension's `imageRequest(page)` headers,
     * its per-host rate limits and its own interceptors (a Descrambler on the
     * sites that scramble their pages, a 404 fallback, cookie handling) all
     * apply. Nekoread routes a page through `ext.getImage(page)` for exactly
     * this reason; [com.hikari.app.reader.source.HikariPageSource] does the
     * same with the source handed out here. BLOCKING (loading the extension is
     * part of it), so never call it on the main thread.
     */
    fun httpSource(): HttpSource? = runCatching { source() as? HttpSource }.getOrNull()

    /** The source's own request headers (its User-Agent, Referer, Origin, any
     *  cookies it set), which the reader's image loader replays so a
     *  hotlink-protected CDN accepts the page. Only an [HttpSource] has them —
     *  a source with its own network stack gets an empty map, and its pages are
     *  then fetched plainly. */
    private fun sourceHeaders(src: CatalogueSource): Map<String, String> = runCatching {
        val http = src as? HttpSource ?: return emptyMap()
        http.headers.toMultimap().mapValues { it.value.firstOrNull().orEmpty() }
    }.getOrDefault(emptyMap())

    /** The page's image URL. Most sources resolve it in `pageListParse`; for the
     *  rest the base class asks the site (see `HttpSource.getImageUrl`). */
    private suspend fun pageUrl(src: CatalogueSource, page: Page): String {
        page.imageUrl?.takeIf { it.isNotBlank() }?.let { return it }
        val http = src as? HttpSource
        val resolved = if (http != null) {
            runCatching { http.getImageUrl(page) }.getOrNull().orEmpty()
        } else {
            ""
        }
        val url = resolved.ifBlank { page.url }
        if (url.isNotBlank() && !url.startsWith("http")) {
            val base = runCatching { http?.baseUrl }.getOrNull()
            if (!base.isNullOrBlank()) return base.trimEnd('/') + "/" + url.trimStart('/')
        }
        return url
    }

    /**
     * Universal Webtoons viewer strips (mirrors the Anymex engine's own
     * imageList reader): the episode HTML carries every full-size strip in
     * `var imageList = [{url, ...}]` in story order. A present-but-empty list
     * is a locked episode — honest empty, never fallback covers. The phinf scan
     * is older-markup fallback only (thumbs/previews filtered, never served).
     */
    private fun nativeWebtoonsPages(chapterUrl: String): List<StreamSource> {
        val u = chapterUrl.trim()
            .replace("&amp;", "&")
            .replace("https://m.webtoons.com/", "https://www.webtoons.com/")
        if (u.isBlank() || !u.startsWith("http") || "viewer" !in u) return emptyList()
        val html = runCatching {
            Http.fetchStringRobust(
                u,
                mapOf(
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
                    "Accept" to "text/html",
                    "Referer" to "https://www.webtoons.com/",
                ),
            ).getOrNull()
        }.getOrNull().orEmpty()
        if (html.isBlank()) return emptyList()
        val headers = mapOf(
            "User-Agent" to Http.UA,
            "Referer" to "https://www.webtoons.com/",
        )
        val block = Regex("""var\s+imageList\s*=\s*\[(.*?)\];""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)
        if (block != null) {
            val urls = Regex("""url\s*:\s*"([^"]+)"""").findAll(block)
                .map { it.groupValues[1].trim() }
                .filter { it.startsWith("http") && !isPreviewUrl(it) }
                .distinct()
                .toList()
            if (urls.isNotEmpty()) {
                return urls.take(400).mapIndexed { i, img ->
                    StreamSource(
                        name = "Page ${i + 1}",
                        url = img,
                        headers = headers,
                        pageUrl = u,
                    )
                }
            }
            return emptyList()
        }
        val seen = HashSet<String>()
        val out = ArrayList<StreamSource>()
        for (m in Regex("""https://webtoon-phinf\.pstatic\.net/[^"'<>\s]+""").findAll(html)) {
            val img = m.value.trim()
            if (img.isEmpty() || !seen.add(img) || isPreviewUrl(img)) continue
            out += StreamSource(
                name = "Page ${out.size + 1}",
                url = img,
                headers = headers,
                pageUrl = u,
            )
            if (out.size >= 400) break
        }
        return out
    }

    // ---- Mapping ----

    private fun toItem(manga: SManga, src: CatalogueSource): MediaItem = MediaItem(
        providerId = config.id,
        id = manga.url,
        title = manga.title.ifBlank { manga.url },
        // A manga is a series of chapters — the same shape an anime has, which
        // keeps the shared catalogue/search/row code working untouched.
        type = MediaType.SERIES,
        posterUrl = manga.thumbnail_url?.takeIf { it.isNotBlank() },
        overview = manga.description?.takeIf { it.isNotBlank() },
        genres = manga.getGenres().orEmpty(),
        rawType = "manga",
    ).also {
        if (it.title.isNotBlank()) cacheManga(it, src)
    }

    /** Keeps the `SManga` the source handed us, so the detail/chapter calls can
     *  reuse its url instead of rebuilding a stub. */
    private fun cacheManga(item: MediaItem, src: CatalogueSource) {
        MangaRecordCache.put(item, src)
    }

    private fun sm(item: MediaItem): SManga {
        MangaRecordCache.get(item)?.let { return it }
        val stub = SManga.create()
        stub.url = item.id
        stub.title = item.title
        stub.thumbnail_url = item.posterUrl
        stub.description = item.overview
        return stub
    }

    private fun ch(url: String): SChapter = SChapter.create().apply { this.url = url }

    private fun SChapter.toChapter(): MangaChapter = MangaChapter(
        url = url,
        name = name,
        number = chapter_number,
        dateUpload = date_upload,
        scanlator = scanlator,
    )

    /** "Chapter 12", or the source's own chapter name when it has one — the same
     *  rule [MangaChapter.label] applies, for the `Episode` label the reader's
     *  chapter list is built from. */
    private fun SChapter.labelOf(): String =
        name.ifBlank { if (chapter_number >= 0f) "Chapter $chapter_number" else "Chapter" }

    /**
     * Newest-last, so the reader's "next chapter" walks forward in reading order.
     * Sources hand chapters back in whatever order their site lists them in
     * (usually newest first), and a source that numbers its chapters is sorted by
     * that number — Mihon's own rule.
     */
    private fun sortChapters(list: List<SChapter>): List<SChapter> {
        val numbered = list.filter { it.chapter_number > 0f }
        if (numbered.size >= 2) return list.sortedBy { if (it.chapter_number > 0f) it.chapter_number else Float.MAX_VALUE }
        return list.reversed()
    }

    /**
     * The most useful one-line description of [t].
     *
     * The DEEPEST cause is NOT the answer, and treating it as one is why the only
     * thing this app could say about a failed chapter list was
     * "OnNextValue: OnError while emitting onNext value: okhttp…".
     *
     * A manga source's request runs through the extension's own RxJava-1 HTTP
     * path (`HttpSource.fetchChapterList` → `Call.asObservableSuccess().map {
     * chapterListParse(it) }`), and Rx appends its own marker to the END of the
     * cause chain: `OnErrorThrowable.addValueAsLastCause` hangs an `OnNextValue`
     * — whose message names the VALUE that was being delivered (an okhttp
     * `Response`) — off the real throwable. So walking to the innermost cause
     * landed on Rx's wrapper and printed it as though it were the failure,
     * hiding the actual error underneath: a linkage error, an `HttpException`, an
     * exception out of the extension's own parser.
     *
     * So the wrappers are skipped and the innermost NON-wrapper is reported.
     * When that error carries no message of its own, the wrapper's is kept — it
     * at least names what was in flight when the parse blew up.
     */
    private fun reason(t: Throwable): String {
        // The cause chain, outermost first. `seen` is an identity set because a
        // throwable whose cause is itself — or a loop of two — would otherwise
        // spin here forever.
        val chain = ArrayList<Throwable>(4)
        val seen = java.util.IdentityHashMap<Throwable, Boolean>()
        var cursor: Throwable? = t
        while (cursor != null && seen.put(cursor, true) == null) {
            chain += cursor
            cursor = cursor.cause
        }
        val wrapper = chain.lastOrNull { isRxWrapper(it) }
        val real = chain.lastOrNull { !isRxWrapper(it) } ?: wrapper ?: t
        val label = real.javaClass.simpleName.ifBlank { real.javaClass.name }
        // A failing LINKAGE is worth a plain word, because it is not the site: a
        // manga extension is a separate APK compiled against its own copy of the
        // HTTP library, so a NoSuchMethodError/NoClassDefFoundError here means the
        // extension and this app disagree about that library's version.
        //
        // A bare `UnsupportedOperationException` is the same kind of statement from
        // the other side: the vendored base classes throw one from every hook an
        // extension MAY override (`HttpSource.chapterListParse` is the one on the
        // chapter-list path), so hitting it means this extension never provided
        // that hook — which is what an extension built against a different
        // version of the extensions library looks like. It is the "0 chapters
        // failed: UnsupportedOperationException" row, and it is an extension
        // problem, not a connection one, so it is said in words.
        val compat = when {
            real is LinkageError ->
                " — this extension was built against a different version of the app's HTTP library"
            real is UnsupportedOperationException ->
                " — this extension does not implement the part of the extensions API Hikari " +
                    "asked it for, which means it was built for a different version of the " +
                    "extensions library. Try another extension for this title."
            else -> ""
        }
        // The Rx wrapper's text only names what was in flight ("OnError while
        // emitting onNext value: okhttp3.Response.class"), which says nothing
        // about the failure — with a bare UnsupportedOperationException above it,
        // printing it made the whole row unreadable.
        val detail = real.message?.takeIf { it.isNotBlank() }
            ?: if (real is UnsupportedOperationException) null
            else wrapper?.message?.takeIf { it.isNotBlank() }
        return label + (detail?.let { ": $it" } ?: "") + compat
    }

    /** True for RxJava's own plumbing types — see [reason]. */
    private fun isRxWrapper(t: Throwable): Boolean = t.javaClass.name.startsWith("rx.")
}

/** The `SManga` per item, so `getMangaUpdate` (and, on a legacy source, the
 *  `getChapterList` it bridges to) gets the url and title the source itself
 *  reported (some sources build their requests from more than the url). */
private object MangaRecordCache {
    private val map = object : LinkedHashMap<String, Pair<SManga, CatalogueSource>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<SManga, CatalogueSource>>?) =
            size > 256
    }

    fun put(item: MediaItem, src: CatalogueSource) {
        val m = SManga.create()
        m.url = item.id
        m.title = item.title
        m.thumbnail_url = item.posterUrl
        m.description = item.overview
        synchronized(map) { map[item.providerId + "|" + item.id] = m to src }
    }

    fun get(item: MediaItem): SManga? = synchronized(map) { map[item.providerId + "|" + item.id]?.first }

    fun source(item: MediaItem): CatalogueSource? = synchronized(map) { map[item.providerId + "|" + item.id]?.second }
}

/** The records the manga screens need about a provider, without loading it. */
object MangaMark {
    /** True for an item that came out of a manga engine. */
    fun isManga(item: MediaItem): Boolean = MangaExtensionManager.isMangaProviderId(item.providerId)

    fun isMangaProvider(id: String): Boolean = MangaExtensionManager.isMangaProviderId(id)

    /** The `MangaRecord` for an item (used by the detail screen and the library). */
    fun record(item: MediaItem, providerName: String): MangaRecord = MangaRecord(
        providerId = item.providerId,
        providerName = providerName,
        url = item.id,
        title = item.title,
        description = item.overview,
        genres = item.genres,
        posterUrl = item.posterUrl,
    )

    /**
     * The source's published status ("Ongoing", "Completed", …) for an item, when
     * the source has told us — the `SManga` behind the item carries it, and it is
     * only knowable while that item is still in the cache (it comes from the
     * catalogue/search response that produced it). Null when unknown, which the
     * detail screen simply leaves out.
     */
    fun statusLabel(item: MediaItem): String? {
        val raw = MangaRecordCache.get(item)?.status ?: return null
        return mangaStatusLabel(raw)
    }
}
