package com.hikari.app.kotatsu.fork

import com.hikari.app.data.Logs
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import org.skepsun.kototoro.parsers.ContentParser
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.parsers.model.SortOrder

/**
 * A skepsun (kototoro) [ContentParser] speaking the Tachiyomi [CatalogueSource]
 * API — the same bridge [com.hikari.app.kotatsu.KotatsuBridge] is for the
 * official parsers, so [com.hikari.app.manga.MangaProvider] drives fork
 * manga/novel rows unchanged.
 *
 * Video sources ([ContentType.VIDEO]/HENTAI_VIDEO) expose their playback as pages
 * (an M3U8/MP4 url per page); those rows ride [ForkVideoProvider] instead and
 * never reach this bridge.
 */
class SkepsunBridge(
	private val parser: ContentParser,
) : CatalogueSource {

	private val kSource = parser.source
	private val enumSrc = kSource as? ContentParserSource

	override val id: Long = ("forksk|" + kSource.toString()).hashCode().toLong()
	override val name: String = runCatching { (kSource as? ContentParserSource)?.title }.getOrNull()
		?: kSource.toString()
	override val lang: String = runCatching { (kSource as? ContentParserSource)?.locale }.getOrNull()
		?.ifBlank { "en" }?.lowercase()?.take(2)?.ifBlank { "en" } ?: "en"
	override val supportsLatest: Boolean = sortOf("UPDATED") in parser.availableSortOrders

	override fun getFilterList(): FilterList = FilterList()

	fun parserDomain(): String = runCatching { parser.domain }.getOrDefault("")

	override suspend fun getPopularManga(page: Int): MangasPage =
		list(page, "POPULARITY", null)

	override suspend fun getLatestUpdates(page: Int): MangasPage =
		list(page, "UPDATED", null)

	override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage =
		list(page, "RELEVANCE", query)

	private fun sortOf(name: String): SortOrder =
		runCatching { SortOrder.valueOf(name) }
			.getOrNull() ?: parser.availableSortOrders.firstOrNull() ?: SortOrder.POPULARITY

	private suspend fun list(page: Int, wantName: String, query: String?): MangasPage {
		val want = runCatching { SortOrder.valueOf(wantName) }.getOrNull()
		val order = when {
			want != null && want in parser.availableSortOrders -> want
			else -> parser.availableSortOrders.firstOrNull() ?: SortOrder.POPULARITY
		}
		val filter = filterOf(query)
		val offset = (page.coerceAtLeast(1) - 1) * PAGE_SIZE
		val found = runCatching { parser.getList(offset, order, filter) }.getOrElse {
			Logs.log("Fork", "${kSource} list failed: ${it.message}")
			throw it
		}
		return MangasPage(found.map { toSManga(it, initialized = false) }, found.size >= PAGE_SIZE)
	}

	private fun filterOf(query: String?): ContentListFilter =
		if (query.isNullOrBlank()) ContentListFilter.EMPTY else ContentListFilter(query = query)

	override suspend fun getMangaDetails(manga: SManga): SManga {
		val seed = seedManga(manga.url, manga.title, manga.thumbnail_url)
		val full = runCatching { parser.getDetails(seed) }.getOrElse {
			Logs.log("Fork", "${kSource} details failed: ${it.message}")
			throw it
		}
		return toSManga(full, initialized = true)
	}

	override suspend fun getChapterList(manga: SManga): List<SChapter> {
		val seed = seedManga(manga.url, manga.title, manga.thumbnail_url)
		val full = runCatching { parser.getDetails(seed) }.getOrElse {
			Logs.log("Fork", "${kSource} chapters failed: ${it.message}")
			throw it
		}
		return (full.chapters.orEmpty()).map(::toSChapter)
	}

	override suspend fun getPageList(chapter: SChapter): List<Page> {
		val src = enumSrc ?: return emptyList()
		val kChapter = ContentChapter(
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
			Logs.log("Fork", "${kSource} pages failed: ${it.message}")
			throw it
		}
		return pages.mapIndexed { i, kp ->
			val img = runCatching { parser.getPageUrl(kp) }.getOrNull()?.takeIf { it.isNotBlank() }
				?: kp.url
			Page(index = i, url = kp.url, imageUrl = absoluteUrl(img))
		}
	}

	/** Novel chapter HTML (null for manga rows and when the parser serves none). */
	suspend fun chapterContent(chapterUrl: String, chapterName: String?): String? {
		val src = enumSrc ?: return null
		val kc = ContentChapter(
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
		return novelHtmlOf(content)?.takeIf { it.isNotBlank() }
	}

	/** Reads the fork's `NovelChapterContent` without naming its fields at compile
	 *  time (the shape is `html` + images; reflection keeps this compiling even
	 *  if the fork renames a member). */
	private fun novelHtmlOf(content: Any): String? {
		val cls = content.javaClass
		val candidates = listOf("html", "content", "text", "body")
		for (name in candidates) {
			runCatching { cls.getField(name).get(content) as? String }
				.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
			runCatching { cls.getMethod("get" + name.replaceFirstChar { it.uppercase() }) }
				.getOrNull()?.let { m ->
					runCatching { m.invoke(content) as? String }.getOrNull()
						?.takeIf { it.isNotBlank() }?.let { return it }
				}
		}
		return null
	}

	private fun seedManga(url: String, title: String, cover: String?): Content {
		val src = enumSrc ?: throw IllegalStateException("Skepsun source missing")
		return Content(
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

	private fun toSManga(km: Content, initialized: Boolean): SManga =
		SManga.create().apply {
			url = km.url
			title = km.title.ifBlank { km.url }
			author = km.authors.firstOrNull()
			artist = null
			description = km.description?.takeIf { it.isNotBlank() }
			genre = km.tags.joinToString(", ") { it.title }.takeIf { it.isNotBlank() }
			status = when (km.state?.name) {
				"ONGOING" -> SManga.ONGOING
				"FINISHED" -> SManga.COMPLETED
				"ABANDONED" -> SManga.CANCELLED
				"PAUSED" -> SManga.ON_HIATUS
				else -> SManga.UNKNOWN
			}
			thumbnail_url = km.largeCoverUrl?.takeIf { it.isNotBlank() } ?: km.coverUrl?.takeIf { it.isNotBlank() }
			update_strategy = UpdateStrategy.ALWAYS_UPDATE
			this.initialized = initialized
		}

	private fun toSChapter(kc: ContentChapter): SChapter =
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
		// Parser domains are bare hosts (`91pinse.com`, no scheme) — without
		// one the request layer rejects the URL ("no scheme was found") and
		// details/chapters/pages all fail. A scheme-carrying domain (mirror
		// overrides) is used verbatim.
		val d = parser.domain.trim().trimEnd('/')
		val base = if (d.startsWith("http://") || d.startsWith("https://")) d else "https://$d"
		return base + "/" + u.trimStart('/')
	}

	/** Direct media urls + per-page request headers for a VIDEO chapter: the
	 *  parser's playback API answers with the file per page, and each page
	 *  carries the headers (Referer, cookies, UA) its host demands — the same
	 *  headers Kotatsu's own reader sends per image. [getPageList] cannot
	 *  carry them (Tachiyomi pages hold no headers), so video rows read this
	 *  instead. Urls are absolutized (see [absoluteUrl]). */
	suspend fun videoPages(chapterUrl: String, chapterName: String?): List<Pair<String, Map<String, String>>> {
		val src = enumSrc ?: return emptyList()
		val kc = ContentChapter(
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
		val pages = runCatching { parser.getPages(kc) }.getOrElse {
			Logs.log("Fork", "${kSource} video pages failed: ${it.message}")
			throw it
		}
		return pages.mapNotNull { kp ->
			val url = runCatching { parser.getPageUrl(kp) }.getOrNull()?.takeIf { it.isNotBlank() }
				?: kp.url.takeIf { it.isNotBlank() }
				?: return@mapNotNull null
			absoluteUrl(url) to kp.headers.orEmpty()
		}
	}

	private fun stableId(url: String): Long = url.hashCode().toLong()

	private fun trimNum(n: Float): String =
		if (n == n.toLong().toFloat()) n.toLong().toString() else n.toString()

	companion object {
		private const val PAGE_SIZE = 20

		private val bridges = java.util.concurrent.ConcurrentHashMap<String, SkepsunBridge>()
		private val parsers = java.util.concurrent.ConcurrentHashMap<String, ContentParser>()

		fun parserOf(enumName: String): ContentParser? {
			parsers[enumName]?.let { return it }
			val src = runCatching { ContentParserSource.valueOf(enumName) }.getOrNull() ?: return null
			val p = runCatching { ForkContentContext.newParserInstance(src) }.getOrNull() ?: return null
			parsers[enumName] = p
			return p
		}

		fun forSource(enumName: String): SkepsunBridge? {
			bridges[enumName]?.let { return it }
			val parser = parserOf(enumName) ?: return null
			val bridge = SkepsunBridge(parser)
			bridges[enumName] = bridge
			return bridge
		}

		fun evict(enumName: String) {
			bridges.remove(enumName)
			parsers.remove(enumName)
		}

		@Suppress("unused")
		private fun tagsOf(tags: Set<ContentTag>): String = tags.joinToString(", ") { it.title }
	}
}
