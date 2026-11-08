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

class MangaProvider(override val config: ProviderConfig) : ContentProvider {

    /** "Popular" / "Latest" — the two lists every catalogue source offers. */
    companion object {
        const val CATALOG_POPULAR = "popular"
        const val CATALOG_LATEST = "latest"
        const val EXT_CALL_TIMEOUT_MS = 30_000L
        const val WARM_SITE_TIMEOUT_MS = 15_000L
        val lastOutcome = ConcurrentHashMap<String, String>()
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

    private fun siteUrl(): String? = runCatching {
        MangaExtensionManager.siteUrlOf(config)
    }.getOrNull()?.takeIf { it.isNotBlank() }

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

    private fun isRetryableHttp(t: Throwable?): Boolean {
        var c: Throwable? = t
        while (c != null) {
            if (c is eu.kanade.tachiyomi.network.HttpException &&
                (c.code == 429 || c.code in 502..504 || c.code in 520..524)
            ) return true
            c = c.cause
        }
        return false
    }

    /** The host an empty manga answer came from, for the log — an empty list
     *  carries no exception, so without this the next report can never say
     *  whether the request went to the wrong URL or the right URL refused. */
    private fun logEmptyAnswer(what: String, src: CatalogueSource, mangaUrl: String?) {
        val base = runCatching {
            (src as? eu.kanade.tachiyomi.source.online.HttpSource)?.baseUrl
        }.getOrNull()
        val target = runCatching {
            if (mangaUrl != null && mangaUrl.startsWith("http")) mangaUrl
            else (base?.trimEnd('/') ?: "") + "/" + (mangaUrl ?: "").trimStart('/')
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: base
        val host = runCatching { java.net.URI(target).host }.getOrNull() ?: target
        Logs.log("Manga", "${config.name}: empty $what answer from $host")
    }

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

    private fun isMangaTimeSite(): Boolean {
        if (siteUrl().orEmpty().contains("mangatime", ignoreCase = true)) return true
        return runCatching { source()?.javaClass?.name }.getOrNull()
            ?.contains("mangatime", ignoreCase = true) == true
    }

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
        if (mangas == null && isRetryableHttp(first.exceptionOrNull())) {
            kotlinx.coroutines.delay(2_000)
            val retry = runCatching { withTimeoutOrNull(EXT_CALL_TIMEOUT_MS) { ask() } }
            retry.exceptionOrNull()?.let { if (it is kotlinx.coroutines.CancellationException) throw it }
            if (retry.isSuccess) {
                mangas = retry.getOrNull()
                failure = null
            } else {
                failure = retry.exceptionOrNull()?.let { reason(it) }
            }
        }
        if (mangas == null && failure == null) {
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
        if (mangas.isEmpty()) {
            logEmptyAnswer("${ref.name.lowercase()} page $page", src, null)
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
        val searchHung = found == null && failure == null
        if (searchHung) failure = "the site took too long to answer (over ${EXT_CALL_TIMEOUT_MS / 1000}s)"
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

    override suspend fun getEpisodes(item: MediaItem): List<Episode>? = gate {
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
        if (raw == null && isRetryableHttp(first.exceptionOrNull())) {
            kotlinx.coroutines.delay(2_000)
            val retry = runCatching { withTimeoutOrNull(EXT_CALL_TIMEOUT_MS) { ask() } }
            retry.exceptionOrNull()?.let { if (it is kotlinx.coroutines.CancellationException) throw it }
            if (retry.isSuccess) {
                raw = retry.getOrNull()
                failure = null
            } else {
                failure = retry.exceptionOrNull()?.let { reason(it) }
            }
        }
        if (raw == null && failure == null) {
            failure = "the site took too long to answer (over ${EXT_CALL_TIMEOUT_MS / 1000}s)"
        }
        // A hung first ask skips the warm-up retry (see the catalogue): the
        // site already burned the whole ceiling once.
        val episodesHung = raw == null && first.exceptionOrNull() == null
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
        if (raw.isEmpty()) {
            // Same host logging as the catalogue (see [logEmptyAnswer]): an
            // empty chapter list never names where it came from otherwise.
            logEmptyAnswer("chapters", src, sm(item).url)
            return@gate failEpisodes("no chapters in this answer")
        }
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
        lastOutcome[config.id] = "✗ " + msg.take(200)
        Logs.log("Manga", "${config.name}: $msg")
        return null
    }

    override suspend fun getStreams(item: MediaItem, episode: Episode?): List<StreamSource> =
        gate(ProviderGate.Lane.BACKGROUND) {
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
        if ("webtoons.com" in chapter.id.lowercase() && "viewer" in chapter.id.lowercase()) {
            val native = nativeWebtoonsPages(chapter.id)
            if (native.isNotEmpty()) {
                lastOutcome[config.id] = "✓ ${native.size} page(s) (native)"
                return@gate native
            }
        }
        try {
            var pageList = runCatching { src.getPageList(ch(chapter.id)) }.getOrNull()
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

    fun httpSource(): HttpSource? = runCatching { source() as? HttpSource }.getOrNull()

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

    private fun sortChapters(list: List<SChapter>): List<SChapter> {
        val numbered = list.filter { it.chapter_number > 0f }
        if (numbered.size >= 2) return list.sortedBy { if (it.chapter_number > 0f) it.chapter_number else Float.MAX_VALUE }
        return list.reversed()
    }

    private fun reason(t: Throwable): String {
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
        val compat = when {
            real is LinkageError ->
                " — this extension was built against a different version of the app's HTTP library"
            real is UnsupportedOperationException ->
                " — this extension does not implement the part of the extensions API Hikari " +
                    "asked it for, which means it was built for a different version of the " +
                    "extensions library. Try another extension for this title."
            else -> ""
        }
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

    fun statusLabel(item: MediaItem): String? {
        val raw = MangaRecordCache.get(item)?.status ?: return null
        return mangaStatusLabel(raw)
    }
}
