package com.hikari.app.manga

import android.content.Context
import com.hikari.app.HikariApp
import com.hikari.app.data.CatalogRef
import com.hikari.app.data.Episode
import com.hikari.app.data.Logs
import com.hikari.app.data.MediaItem
import com.hikari.app.data.MediaType
import com.hikari.app.data.ProviderConfig
import com.hikari.app.data.StreamSource
import com.hikari.app.providers.ContentProvider
import com.hikari.app.providers.ProviderGate
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.MangaSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

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
        val lastOutcome = ConcurrentHashMap<String, String>()
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

    private fun source(): CatalogueSource? =
        MangaExtensionManager.sourceOf(context, extFile, sourceIndex) as? CatalogueSource

    private fun missingReason(): String = when {
        !extFile.exists() -> "The extension file is missing — reinstall it in Extensions"
        else -> MangaExtensionManager.lastError ?: "The extension could not be loaded"
    }

    private fun <T> fail(msg: String, fallback: T): T {
        val short = "✗ " + msg.take(72)
        lastOutcome[config.id] = short
        Logs.log("Manga", "${config.name}: $msg")
        return fallback
    }

    private fun <T> gate(block: suspend () -> T): T =
        ProviderGate.withProvider(config.id) { withContext(Dispatchers.IO) { block() } }

    // ---- Catalogues ----

    override suspend fun catalogs(): List<CatalogRef> = gate {
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
        val src = source() ?: return@gate fail(missingReason(), emptyList())
        try {
            val mangas = when (ref.id) {
                CATALOG_LATEST -> src.getLatestUpdates(page)
                else -> src.getPopularManga(page)
            }
            lastOutcome[config.id] = "✓ ${mangas.mangas.size} title(s)"
            mangas.mangas.map { toItem(it, src) }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            fail("catalog failed: ${reason(t)}", emptyList())
        }
    }

    override suspend fun search(query: String, page: Int): List<MediaItem> = gate {
        val src = source() ?: return@gate fail(missingReason(), emptyList())
        try {
            val filters = runCatching { src.getFilterList() }.getOrDefault(FilterList())
            val found = src.getSearchManga(page, query, filters)
            lastOutcome[config.id] = "✓ ${found.mangas.size} result(s)"
            found.mangas.map { toItem(it, src) }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            fail("search failed: ${reason(t)}", emptyList())
        }
    }

    // ---- Details ----

    override suspend fun getMeta(item: MediaItem): MediaItem = gate {
        val src = source() ?: return@gate item
        try {
            val details = src.getMangaDetails(sm(item))
            lastOutcome[config.id] = "✓ details"
            item.copy(
                title = details.title.ifBlank { item.title },
                posterUrl = details.thumbnail_url?.takeIf { it.isNotBlank() } ?: item.posterUrl,
                overview = details.description?.takeIf { it.isNotBlank() } ?: item.overview,
                genres = details.getGenres().orEmpty().ifEmpty { item.genres },
                // The manga "status" rides along as the year-like line the
                // detail screen already draws; the manga screen shows it as the
                // published status instead.
                year = item.year,
                rating = details.rating.takeIf { it > 0f }?.toDouble() ?: item.rating,
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
        val src = source() ?: return@gate failEpisodes(missingReason())
        try {
            val raw = src.getChapterList(sm(item))
            val sorted = sortChapters(raw)
            MangaStore.putChapters(item.providerId + "|" + item.id, sorted.map { it.toChapter() })
            lastOutcome[config.id] = "✓ ${sorted.size} chapter(s)"
            sorted.mapIndexed { i, c ->
                Episode(
                    number = i + 1,
                    id = c.url,
                    name = c.label,
                    image = null,
                    season = 1,
                )
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            failEpisodes("chapters failed: ${reason(t)}")
        }
    }

    private fun failEpisodes(msg: String): List<Episode>? {
        lastOutcome[config.id] = "✗ " + msg.take(72)
        Logs.log("Manga", "${config.name}: $msg")
        // The cached list (when there is one) beats an empty screen: a source
        // that is temporarily down still lets the user open the chapters already
        // fetched for this title.
        return null
    }

    /**
     * The pages of one chapter, each as a [StreamSource]: `url` is the image URL
     * to load and `headers` carries the source's own headers, so the reader's
     * image loader can fetch from a hotlink-protected CDN the way the extension
     * itself would.
     */
    override suspend fun getStreams(item: MediaItem, episode: Episode?): List<StreamSource> = gate {
        val src = source() ?: return@gate fail(missingReason(), emptyList())
        val chapter = episode ?: return@gate fail("No chapter selected", emptyList())
        try {
            val pageList = src.getPageList(ch(chapter.id))
            val headers = sourceHeaders(src)
            lastOutcome[config.id] = "✓ ${pageList.size} page(s)"
            pageList.mapIndexed { i, page ->
                StreamSource(
                    name = "Page ${i + 1}",
                    url = pageUrl(src, page),
                    headers = headers,
                )
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            fail("pages failed: ${reason(t)}", emptyList())
        }
    }

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

    private fun reason(t: Throwable): String {
        var c: Throwable = t
        while (c.cause != null && c.cause !== c) c = c.cause!!
        return c::class.java.simpleName + (c.message?.let { ": $it" } ?: "")
    }
}

/** The `SManga` per item, so `getMangaDetails`/`getChapterList` get the url and
 *  title the source itself reported (some sources build their requests from
 *  more than the url). */
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
