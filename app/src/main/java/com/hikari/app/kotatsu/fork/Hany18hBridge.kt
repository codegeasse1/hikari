package com.hikari.app.kotatsu.fork

import com.hikari.app.data.Logs
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import org.hany18h.parsers.MangaParser
import org.hany18h.parsers.model.Manga
import org.hany18h.parsers.model.MangaChapter
import org.hany18h.parsers.model.MangaListFilter
import org.hany18h.parsers.model.MangaState
import org.hany18h.parsers.model.MangaTag
import org.hany18h.parsers.model.RATING_UNKNOWN
import org.hany18h.parsers.model.SortOrder

/**
 * A vendored hany18h [MangaParser] speaking the Tachiyomi [CatalogueSource] API —
 * the same bridge [com.hikari.app.kotatsu.KotatsuBridge] is for the official
 * parsers, so [com.hikari.app.manga.MangaProvider] drives fork manga/novel rows
 * unchanged (Popular / Latest, search, details, chapters, pages).
 *
 * Id mapping mirrors the official bridge: per-site Long ids collide across sites,
 * so Tachiyomi-facing traffic carries the site-relative url and synthetic numeric
 * ids derive from it.
 */
class Hany18hBridge(
	private val parser: MangaParser,
) : CatalogueSource {

	private val kSource = parser.source

	override val id: Long = ("hany18h|" + kSource.name).hashCode().toLong()
	override val name: String = (kSource as? org.hany18h.parsers.model.MangaParserSource)?.title ?: kSource.name
	override val lang: String = ((kSource as? org.hany18h.parsers.model.MangaParserSource)?.locale ?: "").ifBlank { "en" }.lowercase().take(2).ifBlank { "en" }
	override val supportsLatest: Boolean = SortOrder.UPDATED in parser.availableSortOrders

	override fun getFilterList(): FilterList = FilterList()

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
			Logs.log("Hany18h", "${kSource.name} list failed: ${it.message}")
			throw it
		}
		return MangasPage(found.map { toSManga(it, initialized = false) }, found.size >= PAGE_SIZE)
	}

	override suspend fun getMangaDetails(manga: SManga): SManga {
		val seed = seedManga(manga.url, manga.title, manga.thumbnail_url)
		val full = runCatching { parser.getDetails(seed) }.getOrElse {
			Logs.log("Hany18h", "${kSource.name} details failed: ${it.message}")
			throw it
		}
		return toSManga(full, initialized = true)
	}

	override suspend fun getChapterList(manga: SManga): List<SChapter> {
		val seed = seedManga(manga.url, manga.title, manga.thumbnail_url)
		val full = runCatching { parser.getDetails(seed) }.getOrElse {
			Logs.log("Hany18h", "${kSource.name} chapters failed: ${it.message}")
			throw it
		}
		return (full.chapters.orEmpty()).map(::toSChapter)
	}

	override suspend fun getPageList(chapter: SChapter): List<Page> {
		val src = (kSource as? org.hany18h.parsers.model.MangaParserSource) ?: return emptyList()
		val kChapter = MangaChapter(
			id = stableId(chapter.url),
			title = chapter.name.ifBlank { null },
			number = chapter.chapter_number,
			volume = 0,
			url = chapter.url,
			scanlator = chapter.scanlator,
			uploadDate = chapter.date_upload,
			branch = null,
			source = src,
		)
		val pages = runCatching { parser.getPages(kChapter) }.getOrElse {
			Logs.log("Hany18h", "${kSource.name} pages failed: ${it.message}")
			throw it
		}
		return pages.mapIndexed { i, kp ->
			val img = runCatching { parser.getPageUrl(kp) }.getOrNull()?.takeIf { it.isNotBlank() }
				?: kp.url
			Page(index = i, url = kp.url, imageUrl = absoluteUrl(img))
		}
	}

	/** Novel chapter HTML (null for manga rows) — the provider's text branch reads this. */
	suspend fun chapterContent(chapterUrl: String, chapterName: String?): String? {
		val src = (kSource as? org.hany18h.parsers.model.MangaParserSource) ?: return null
		val kc = MangaChapter(
			id = stableId(chapterUrl),
			title = chapterName?.ifBlank { null },
			number = 0f,
			volume = 0,
			url = chapterUrl,
			scanlator = null,
			uploadDate = 0L,
			branch = null,
			source = src,
		)
		val content = runCatching { parser.getChapterContent(kc) }.getOrNull() ?: return null
		return content.html.takeIf { it.isNotBlank() }
	}

	private fun seedManga(url: String, title: String, cover: String?): Manga {
		val src = (kSource as? org.hany18h.parsers.model.MangaParserSource)
			?: org.hany18h.parsers.model.MangaParserSource.MANGASWAT
		return Manga(
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
			source = src,
		)
	}

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
		// Parser domains are bare hosts (no scheme) — without one the request
		// layer rejects the URL ("no scheme was found") and details/chapters
		// pages all fail. A scheme-carrying domain is used verbatim.
		val d = parser.domain.trim().trimEnd('/')
		val base = if (d.startsWith("http://") || d.startsWith("https://")) d else "https://$d"
		return base + "/" + u.trimStart('/')
	}

	private fun stableId(url: String): Long = url.hashCode().toLong()

	private fun trimNum(n: Float): String =
		if (n == n.toLong().toFloat()) n.toLong().toString() else n.toString()

	companion object {
		private const val PAGE_SIZE = 20

		private val bridges = java.util.concurrent.ConcurrentHashMap<String, Hany18hBridge>()
		private val parsers = java.util.concurrent.ConcurrentHashMap<String, MangaParser>()

		fun parserOf(enumName: String): MangaParser? {
			parsers[enumName]?.let { return it }
			val src = runCatching { org.hany18h.parsers.model.MangaParserSource.valueOf(enumName) }.getOrNull()
				?: return null
			val p = runCatching { Hany18hContext.newParserInstance(src) }.getOrNull() ?: return null
			parsers[enumName] = p
			return p
		}

		fun forSource(enumName: String): Hany18hBridge? {
			bridges[enumName]?.let { return it }
			val parser = parserOf(enumName) ?: return null
			val bridge = Hany18hBridge(parser)
			bridges[enumName] = bridge
			return bridge
		}

		fun evict(enumName: String) {
			bridges.remove(enumName)
			parsers.remove(enumName)
		}

		@Suppress("unused")
		private fun tagsOf(tags: Set<MangaTag>): String = tags.joinToString(", ") { it.title }
	}
}
