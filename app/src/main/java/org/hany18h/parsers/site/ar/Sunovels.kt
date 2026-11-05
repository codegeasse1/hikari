package org.hany18h.parsers.site.ar

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.hany18h.parsers.MangaLoaderContext
import org.hany18h.parsers.MangaSourceParser
import org.hany18h.parsers.config.ConfigKey
import org.hany18h.parsers.core.PagedMangaParser
import org.hany18h.parsers.model.*
import org.hany18h.parsers.util.*
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("SUNOVELS", "شمس الروايات", "ar", ContentType.NOVEL)
internal class Sunovels(context: MangaLoaderContext) : PagedMangaParser(
	context = context,
	source = MangaParserSource.SUNOVELS,
	pageSize = 24,
) {

	override val configKeyDomain = ConfigKey.Domain("sunovels.com")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.POPULARITY)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(isSearchSupported = true)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
		keys.add(ConfigKey.InterceptCloudflare(defaultValue = true))
	}

	private val chapterDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSX", Locale.US)

	init {
		// Both the library and chapter pagination use React's zero-based page index.
		setFirstPage(0)
	}

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		val doc = webClient.httpGet("https://$domain/library").parseHtml()
		val tags = doc.select(".categories .list a").mapNotNullToSet { element ->
			val title = element.text().trim()
			if (title.isEmpty() || title.equals("all", ignoreCase = true)) return@mapNotNullToSet null
			MangaTag(key = title, title = title, source = source)
		}
		return MangaListFilterOptions(
			availableTags = tags,
			availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
		)
	}

	override suspend fun getListPage(
		page: Int,
		order: SortOrder,
		filter: MangaListFilter,
	): List<Manga> {
		val url = buildString {
			append("https://")
			append(domain)
			if (filter.query.isNullOrBlank()) {
				append("/library?page=")
				append(page)
				filter.tags.oneOrThrowIfMany()?.let { tag ->
					append("&category=")
					append(tag.key.urlEncoded())
				}
				filter.states.oneOrThrowIfMany()?.let { state ->
					append("&status=")
					append(if (state == MangaState.FINISHED) "Completed" else "Ongoing")
				}
			} else {
				append("/search?title=")
				append(filter.query.orEmpty().trim().urlEncoded())
				append("&page=")
				append(page)
			}
		}
		val doc = webClient.httpGet(url).parseHtml()
		return parseMangaList(doc)
	}

	private fun parseMangaList(doc: Document): List<Manga> {
		return doc.select("ul.grid-list li.list-item > a[href^=/novel/]").mapNotNull { anchor ->
			val href = anchor.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
			val title = anchor.selectFirst("h4")?.text()?.trim().orEmpty()
			if (title.isEmpty()) return@mapNotNull null
			val status = anchor.selectFirst(".image-x .top")?.let(::parseState)
			val cover = extractRscCover(doc, href)
			Manga(
				id = generateUid(href),
				title = title,
				altTitles = emptySet(),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = null,
				coverUrl = cover,
				tags = emptySet(),
				state = status,
				authors = emptySet(),
				source = source,
			)
		}.distinctBy(Manga::url)
	}

	private fun extractRscCover(doc: Document, href: String): String? {
		val rsc = doc.select("script").joinToString(separator = "") { it.data() }
			.replace("\\\"", "\"")
			.replace("\\/", "/")
		val markerIndex = rsc.indexOf("\"href\":\"$href\"")
		if (markerIndex < 0) return null
		val tail = rsc.substring(markerIndex, (markerIndex + 1600).coerceAtMost(rsc.length))
		val src = Regex("\"src\":\"([^\"]+)\"").find(tail)?.groupValues?.get(1) ?: return null
		if (src.contains("placeholder")) return null
		return src.toAbsoluteUrl(domain)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val mangaUrl = manga.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(mangaUrl).parseHtml()
		val header = doc.selectFirst(".novel-header")
		val arabicTitle = header?.selectFirst(".main-head h3")?.text()?.trim()
		val englishTitle = header?.selectFirst(".main-head h1")?.text()?.trim()
		val cover = header?.selectFirst("figure.cover img")?.src() ?: manga.coverUrl
		val stateElement = header?.select(".header-stats span")?.firstOrNull { span ->
			span.selectFirst("small")?.text()?.trim() == "الحالة"
		}?.selectFirst("strong")
		val tags = header?.select(".categories a[href*=/library?category=]")?.mapNotNullToSet { a ->
			val title = a.text().trim()
			if (title.isEmpty()) return@mapNotNullToSet null
			MangaTag(key = title, title = title, source = source)
		}.orEmpty()
		val description = doc.selectFirst(".info-section .description")?.html()
			?: doc.selectFirst("meta[name=description]")?.attr("content")?.let { Element("p").text(it).outerHtml() }
		val rating = header?.selectFirst(".rating-star + strong")?.text()?.trim()
			?.toFloatOrNull()?.div(5f)?.coerceIn(0f, 1f) ?: manga.rating

		return manga.copy(
			title = arabicTitle?.takeIf { it.isNotEmpty() } ?: manga.title,
			altTitles = setOfNotNull(englishTitle?.takeIf { it.isNotEmpty() }),
			description = description,
			coverUrl = cover,
			largeCoverUrl = cover,
			rating = rating,
			state = stateElement?.let(::parseState) ?: manga.state,
			tags = tags,
			chapters = loadAllChapters(mangaUrl),
		)
	}

	private suspend fun loadAllChapters(mangaUrl: String): List<MangaChapter> {
		val firstDoc = webClient.httpGet("$mangaUrl?activeTab=chapters&page=0&sort=asc").parseHtml()
		val totalPages = findTotalChapterPages(firstDoc)
		val result = ArrayList(parseChapters(firstDoc))
		for (batch in (1 until totalPages).chunked(CHAPTER_PAGE_BATCH_SIZE)) {
			val chapters = coroutineScope {
				batch.map { page ->
					async {
						val doc = webClient.httpGet(
							"$mangaUrl?activeTab=chapters&page=$page&sort=asc",
						).parseHtml()
						parseChapters(doc)
					}
				}.awaitAll()
			}
			chapters.flattenTo(result)
		}
		return result.distinctBy(MangaChapter::url).sortedBy(MangaChapter::number)
	}

	private fun parseChapters(doc: Document): List<MangaChapter> {
		return doc.select("section#chapters a:has(li.list-item)[href^=/novel/]").mapNotNull { anchor ->
			if (anchor.selectFirst("svg[data-icon=lock]") != null) return@mapNotNull null
			val href = anchor.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
			val number = href.substringAfterLast('/').toFloatOrNull() ?: return@mapNotNull null
			val title = anchor.selectFirst(".chapter-title")?.text()?.trim()
				?: anchor.attr("title").trim().ifEmpty { "الفصل $number" }
			val date = anchor.selectFirst("time[dateTime]")?.attr("dateTime").orEmpty()
			val uploadDate = synchronized(chapterDateFormat) {
				runCatching { chapterDateFormat.parse(date)?.time ?: 0L }.getOrDefault(0L)
			}
			MangaChapter(
				id = generateUid(href),
				title = title,
				number = number,
				volume = 0,
				url = href,
				scanlator = null,
				uploadDate = uploadDate,
				branch = null,
				source = source,
			)
		}
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> = emptyList()

	override suspend fun getChapterContent(chapter: MangaChapter): NovelChapterContent? {
		val chapterUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(chapterUrl).parseHtml()
		val content = doc.selectFirst("div.chapter-content") ?: return null

		content.select(".d-none, [hidden], [aria-hidden=true], script, style, iframe, noscript, form, button")
			.remove()
		content.select("p, span, div").forEach { element ->
			if (ANTI_COPY_MARKER.matches(element.text().trim())) element.remove()
		}
		content.select("img").forEach(::promoteLazyImageSource)
		content.select("p").forEach { paragraph ->
			if (paragraph.text().trim().isEmpty() && paragraph.selectFirst("img") == null) paragraph.remove()
		}
		content.select("*").forEach { element ->
			element.removeAttr("class")
			element.removeAttr("id")
			element.removeAttr("style")
			element.removeAttr("translate")
		}

		val firstElement = content.children().firstOrNull()
		if (firstElement?.tagName() == "p" && firstElement.text().trim().startsWith("الفصل")) {
			firstElement.tagName("h2")
		} else {
			val title = doc.selectFirst(".chapter-header .titles h2")?.text()?.trim()
				?: chapter.title.orEmpty()
			if (title.isNotEmpty()) content.prependChild(Element("h1").text(title))
		}

		return NovelChapterContent(
			html = content.html(),
			images = content.select("img").mapNotNull { image ->
				image.src()?.let { imageUrl ->
					NovelImage(
						url = imageUrl,
						headers = mapOf(
							"Referer" to chapterUrl,
							"User-Agent" to config[userAgentKey],
						),
					)
				}
			}.distinctBy(NovelImage::url),
		)
	}

	private fun promoteLazyImageSource(image: Element) {
		if (image.attr("src").isNotBlank() && !image.attr("src").startsWith("data:")) return
		for (attribute in arrayOf("data-src", "data-lazy-src", "data-original", "data-url")) {
			val value = image.attr(attribute).trim()
			if (value.isNotEmpty()) {
				image.attr("src", value)
				return
			}
		}
	}

	private fun parseState(element: Element): MangaState? {
		val value = (element.className() + " " + element.text()).lowercase(Locale.ROOT)
		return when {
			"completed" in value || "مكتمل" in value -> MangaState.FINISHED
			"ongoing" in value || "new" in value || "مستمر" in value || "جديد" in value -> MangaState.ONGOING
			else -> null
		}
	}

	internal companion object {
		const val CHAPTER_PAGE_BATCH_SIZE = 5
		val ANTI_COPY_MARKER = Regex(
			"""^[0-9a-fA-F]{12,64}\s*شمس الروايات\s*[0-9a-fA-F]{12,64}$""",
		)

		internal fun findTotalChapterPages(document: Document): Int {
			val labelCount = document.select(".pagination a[aria-label]")
				.mapNotNull { Regex("""\d+""").find(it.attr("aria-label"))?.value?.toIntOrNull() }
				.maxOrNull()
				?: 1
			val hrefCount = document.select(".pagination a[href*=page]")
				.mapNotNull { anchor ->
					Regex("""(?:[?&]|&amp;)page=(\d+)""")
						.find(anchor.attr("href"))
						?.groupValues
						?.getOrNull(1)
						?.toIntOrNull()
						?.plus(1)
				}
				.maxOrNull()
				?: 1
			val payloadCount = document.select("script")
				.asSequence()
				.map { it.data() }
				.mapNotNull { script ->
					Regex("""\\"totalPages\\":(\d+)""")
						.find(script)
						?.groupValues
						?.getOrNull(1)
						?.toIntOrNull()
				}
				.maxOrNull()
				?: 1
			return maxOf(1, labelCount, hrefCount, payloadCount)
		}
	}
}
