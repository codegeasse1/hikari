package com.hikari.app.kotatsu

import com.hikari.app.data.Logs
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import org.koitharu.kotatsu.parsers.MangaParser
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder

/**
 * A Kotatsu [MangaParser] speaking the Tachiyomi [CatalogueSource] API, so
 * [com.hikari.app.manga.MangaProvider] drives it like any manga extension:
 * Popular / Latest catalogues, search, details, chapters and pages.
 *
 * Id mapping: Kotatsu ids are per-site Longs that collide across sites, so the
 * Tachiyomi-facing ids are stable hashes of the site-relative urls instead —
 * every round trip (`MediaItem.id` ⇄ `SManga.url` ⇄ Kotatsu `url`) carries the
 * url itself, and synthetic numeric ids are derived from it wherever the
 * Kotatsu models demand one.
 */
class KotatsuBridge(
    private val parser: MangaParser,
) : CatalogueSource {

    private val kSource = parser.source

    override val id: Long = ("kotatsu|" + kSource.name).hashCode().toLong()
    override val name: String = kSource.title
    override val lang: String = kSource.locale.ifBlank { "en" }.lowercase().take(2).ifBlank { "en" }
    override val supportsLatest: Boolean = SortOrder.UPDATED in parser.availableSortOrders

    override fun getFilterList(): FilterList = FilterList()

    /** The parser's live site root, for verification/warm-up. */
    fun parserDomain(): String = runCatching { parser.domain }.getOrDefault("")

    override suspend fun getPopularManga(page: Int): MangasPage =
        list(page, SortOrder.POPULARITY, null)

    override suspend fun getLatestUpdates(page: Int): MangasPage =
        list(page, SortOrder.UPDATED, null)

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage =
        list(page, SortOrder.RELEVANCE, query)

    private suspend fun list(page: Int, want: SortOrder, query: String?): MangasPage {
        val order = when {
            want in parser.availableSortOrders -> want
            else -> parser.availableSortOrders.firstOrNull() ?: SortOrder.POPULARITY
        }
        val filter = if (query.isNullOrBlank()) MangaListFilter.EMPTY else MangaListFilter(query = query)
        val offset = (page.coerceAtLeast(1) - 1) * PAGE_SIZE
        val found = runCatching { parser.getList(offset, order, filter) }.getOrElse {
            Logs.log("Kotatsu", "${kSource.name} list failed: ${it.message}")
            throw it
        }
        return MangasPage(found.map { toSManga(it, initialized = false) }, found.size >= PAGE_SIZE)
    }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val seed = seedManga(manga.url, manga.title, manga.thumbnail_url)
        val full = runCatching { parser.getDetails(seed) }.getOrElse {
            Logs.log("Kotatsu", "${kSource.name} details failed: ${it.message}")
            throw it
        }
        return toSManga(full, initialized = true)
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> {
        val seed = seedManga(manga.url, manga.title, manga.thumbnail_url)
        val full = runCatching { parser.getDetails(seed) }.getOrElse {
            Logs.log("Kotatsu", "${kSource.name} chapters failed: ${it.message}")
            throw it
        }
        return (full.chapters.orEmpty()).map(::toSChapter)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val kChapter = MangaChapter(
            id = stableId(chapter.url),
            title = chapter.name.ifBlank { null },
            number = chapter.chapter_number,
            volume = 0,
            url = chapter.url,
            scanlator = chapter.scanlator,
            uploadDate = chapter.date_upload,
            branch = null,
            source = kSource,
        )
        val pages = runCatching { parser.getPages(kChapter) }.getOrElse {
            Logs.log("Kotatsu", "${kSource.name} pages failed: ${it.message}")
            throw it
        }
        return pages.mapIndexed { i, kp ->
            val img = runCatching { parser.getPageUrl(kp) }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: kp.url
            Page(index = i, url = kp.url, imageUrl = img)
        }
    }

    // ---- Mapping ----

    private fun seedManga(url: String, title: String, cover: String?): Manga = Manga(
        id = stableId(url),
        title = title.ifBlank { url },
        altTitles = emptySet(),
        url = url,
        publicUrl = absoluteUrl(url),
        rating = RATING_UNKNOWN,
        contentRating = null,
        coverUrl = cover?.takeIf { it.isNotBlank() },
        tags = emptySet(),
        state = null,
        authors = emptySet(),
        source = kSource,
    )

    private fun toSManga(km: Manga, initialized: Boolean): SManga =
        SManga.create().apply {
            url = km.url
            title = km.title.ifBlank { km.url }
            author = km.authors.firstOrNull()
            artist = null
            description = km.description?.takeIf { it.isNotBlank() }
            genre = km.tags.joinToString(", ") { it.title }.takeIf { it.isNotBlank() }
            status = when (km.state) {
                MangaState.ONGOING -> SManga.ONGOING
                MangaState.FINISHED -> SManga.COMPLETED
                MangaState.ABANDONED -> SManga.CANCELLED
                MangaState.PAUSED -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
            thumbnail_url = km.largeCoverUrl?.takeIf { it.isNotBlank() } ?: km.coverUrl?.takeIf { it.isNotBlank() }
            update_strategy = UpdateStrategy.ALWAYS_UPDATE
            this.initialized = initialized
        }

    private fun toSChapter(kc: MangaChapter): SChapter =
        SChapter.create().apply {
            url = kc.url
            name = kc.title?.takeIf { it.isNotBlank() }
                ?: if (kc.number > 0f) "Chapter ${trimNum(kc.number)}" else "Chapter"
            date_upload = kc.uploadDate
            chapter_number = kc.number
            scanlator = kc.scanlator
        }

    private fun absoluteUrl(url: String): String {
        val u = url.trim()
        if (u.startsWith("http://") || u.startsWith("https://")) return u
        val d = parser.domain.trim().trimEnd('/')
        return d + "/" + u.trimStart('/')
    }

    private fun stableId(url: String): Long = url.hashCode().toLong()

    private fun trimNum(n: Float): String =
        if (n == n.toLong().toFloat()) n.toLong().toString() else n.toString()

    companion object {
        private const val PAGE_SIZE = 20

        /** Cached bridge per Kotatsu source name (`MANGADEX`, …). */
        private val bridges = java.util.concurrent.ConcurrentHashMap<String, KotatsuBridge>()

        fun forSource(enumName: String): KotatsuBridge? {
            bridges[enumName]?.let { return it }
            val parser = KotatsuRegistry.parserOf(enumName) ?: return null
            val bridge = KotatsuBridge(parser)
            bridges[enumName] = bridge
            return bridge
        }

        /** Drops the cached bridge for [enumName] (see [KotatsuRegistry.evict]). */
        fun evict(enumName: String) {
            bridges.remove(enumName)
        }

        @Suppress("unused")
        private fun tagsOf(tags: Set<MangaTag>): String = tags.joinToString(", ") { it.title }
    }
}
