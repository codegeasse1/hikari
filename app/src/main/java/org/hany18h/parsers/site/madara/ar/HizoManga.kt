package org.hany18h.parsers.site.madara.ar

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.hany18h.parsers.MangaLoaderContext
import org.hany18h.parsers.MangaSourceParser
import org.hany18h.parsers.config.ConfigKey
import org.hany18h.parsers.model.ContentType
import org.hany18h.parsers.model.Manga
import org.hany18h.parsers.model.MangaChapter
import org.hany18h.parsers.model.MangaListFilter
import org.hany18h.parsers.model.MangaPage
import org.hany18h.parsers.model.MangaParserSource
import org.hany18h.parsers.model.NovelChapterContent
import org.hany18h.parsers.model.NovelImage
import org.hany18h.parsers.model.SortOrder
import org.hany18h.parsers.network.UserAgents
import org.hany18h.parsers.site.madara.MadaraParser
import org.hany18h.parsers.util.attrAsRelativeUrlOrNull
import org.hany18h.parsers.util.generateUid
import org.hany18h.parsers.util.parseHtml
import org.hany18h.parsers.util.src
import org.hany18h.parsers.util.toAbsoluteUrl
import org.hany18h.parsers.util.urlEncoded

@MangaSourceParser("HIZOMANGA", "Hizo Manga", "ar", ContentType.NOVEL)
internal class HizoManga(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.HIZOMANGA, "hizomanga.net", pageSize = 10) {

	// Hizo rejects Android WebView user agents and redirects them to
	// /no-webview.html, which itself redirects forever. Use an ordinary
	// mobile Chrome identity for this source only.
	override val userAgentKey = ConfigKey.UserAgent(UserAgents.CHROME_MOBILE)

	override val stylePage = ""
	override val datePattern = "yyyy-MM-dd"

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(ConfigKey.InterceptCloudflare(defaultValue = true))
	}

	override suspend fun getListPage(
		page: Int,
		order: SortOrder,
		filter: MangaListFilter,
	): List<Manga> {
		val pageNumber = page.coerceAtLeast(1)
		val query = filter.query?.trim()
		val url = if (query.isNullOrEmpty()) {
			buildString {
				append("https://$domain/series/")
				if (pageNumber > 1) {
					clear()
					append("https://$domain/series/page/$pageNumber/")
				}
				append("?m_orderby=")
				append(
					when (order) {
						SortOrder.POPULARITY -> "views"
						SortOrder.NEWEST -> "new-manga"
						SortOrder.ALPHABETICAL -> "alphabet"
						SortOrder.RATING -> "rating"
						else -> "latest"
					},
				)
			}
		} else {
			buildString {
				append("https://$domain")
				if (pageNumber > 1) append("/page/$pageNumber")
				append("/?s=${query.urlEncoded()}&post_type=wp-manga")
			}
		}
		return parseMangaList(webClient.httpGet(url).parseHtml())
	}

	override suspend fun getChapters(manga: Manga, doc: Document): List<MangaChapter> =
		parseHizoChapters(doc)

	override suspend fun loadChapters(mangaUrl: String, document: Document): List<MangaChapter> {
		// Current Hizo detail pages already contain the complete chapter list.
		// Prefer it so devices with stricter redirect/cookie handling never enter
		// a WordPress AJAX redirect loop. Keep AJAX only for older/partial pages.
		parseHizoChapters(document).takeIf(List<MangaChapter>::isNotEmpty)?.let { return it }
		val url = mangaUrl.toAbsoluteUrl(domain).trimEnd('/') + "/ajax/chapters/"
		val chapterDocument = runCatching {
			webClient.httpPost(url, emptyMap()).parseHtml()
		}.getOrNull()
		return parseHizoChapters(chapterDocument ?: document)
	}

	private fun parseHizoChapters(document: Document): List<MangaChapter> {
		return document.select("li.wp-manga-chapter").mapIndexedNotNull { index, item ->
			val anchor = item.selectFirst("a[href]") ?: return@mapIndexedNotNull null
			val href = anchor.attrAsRelativeUrlOrNull("href") ?: return@mapIndexedNotNull null
			val title = anchor.text().trim().ifEmpty { "الفصل ${index + 1}" }
			val number = NUMBER.find(title)?.value?.toFloatOrNull()
				?: NUMBER.find(href.substringAfterLast('/'))?.value?.toFloatOrNull()
				?: (index + 1).toFloat()
			MangaChapter(
				id = generateUid(href),
				title = title,
				number = number,
				volume = 0,
				url = href,
				scanlator = null,
				uploadDate = 0L,
				branch = null,
				source = source,
			)
		}.distinctBy(MangaChapter::id).sortedBy(MangaChapter::number)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapterUrl = chapter.url.toAbsoluteUrl(domain)
		val document = webClient.httpGet(chapterUrl).parseHtml()
		return extractHizoImageUrls(document, chapterUrl).mapIndexed { index, url ->
			MangaPage(
				id = generateUid("${chapter.id}:$index:$url"),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	override suspend fun getChapterContent(chapter: MangaChapter): NovelChapterContent? {
		val chapterUrl = chapter.url.toAbsoluteUrl(domain)
		val document = webClient.httpGet(chapterUrl).parseHtml()
		val content = selectHizoChapterContent(document) ?: return null
		sanitizeHizoChapterContent(content)
		if (content.text().isBlank() && content.selectFirst("img") == null) return null

		val title = document.selectFirst(".reading-content.current h3.chapter-name")
			?.text()
			?.trim()
			?.takeIf(String::isNotEmpty)
			?: chapter.title.orEmpty()
		return NovelChapterContent(
			html = buildString {
				if (title.isNotEmpty()) append(Element("h1").text(title).outerHtml())
				append(content.html())
			},
			images = content.select("img").mapNotNull { image ->
				resolveHizoImageUrl(image, chapterUrl)?.let { imageUrl ->
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

	internal companion object {

		private val NUMBER = Regex("""\d+(?:\.\d+)?""")

		internal fun countCurrentListingCards(document: Document): Int =
			document.select("div.page-item-detail").size

		internal fun selectHizoChapterContent(document: Document): Element? =
			document.selectFirst(
				".reading-content.current > .text-left, " +
					".reading-content.current .text-left, " +
					".read-container > .text-left, " +
					".reading-content.current, " +
					".read-container > .reading-content, " +
					".entry-content_wrap .reading-content",
			)

		internal fun extractHizoImageUrls(document: Document, chapterUrl: String): List<String> {
			val content = selectHizoChapterContent(document) ?: return emptyList()
			return content.select(
				"div.page-break img, img.wp-manga-chapter-img, img[data-src], " +
					"img[data-lazy-src], img[data-original], img[data-url], " +
					"img[data-cfsrc], img[data-srcset], img[srcset], img[src]",
			).mapNotNull { image ->
				resolveHizoImageUrl(image, chapterUrl)
			}.distinct()
		}

		internal fun sanitizeHizoChapterContent(content: Element): Element {
			content.select(
				"script, style, iframe, noscript, form, input, select, option, textarea, button, " +
					"svg, canvas, ins, .adsbygoogle, [class*=advert], [id*=advert], " +
					"[hidden], [aria-hidden=true], .d-none",
			).remove()
			content.select("img").forEach(::promoteLazyImage)
			content.select("p, div, span").forEach { element ->
				if (element.text().trim().isEmpty() && element.selectFirst("img") == null) {
					element.remove()
				}
			}
			content.select("*").forEach { element ->
				element.removeAttr("class")
					.removeAttr("id")
					.removeAttr("style")
					.removeAttr("width")
					.removeAttr("height")
					.removeAttr("loading")
					.removeAttr("decoding")
			}
			return content
		}

		private fun promoteLazyImage(image: Element) {
			val value = sequenceOf(
				"data-src",
				"data-lazy-src",
				"data-original",
				"data-url",
				"data-cfsrc",
				"src",
				"data-srcset",
				"srcset",
			).map { image.attr(it).trim() }.firstOrNull {
				it.isNotEmpty() &&
					it != "#" &&
					!it.startsWith("data:", ignoreCase = true) &&
					!it.contains("placeholder", ignoreCase = true)
			}?.substringBefore(',')
				?.trim()
				?.substringBefore(' ')
				?.trim()
				?.takeIf(String::isNotEmpty)
				?: return
			image.attr("src", value)
				.removeAttr("srcset")
				.removeAttr("data-src")
				.removeAttr("data-lazy-src")
				.removeAttr("data-original")
				.removeAttr("data-url")
		}

		private fun resolveHizoImageUrl(image: Element, chapterUrl: String): String? {
			promoteLazyImage(image)
			val value = image.attr("src").trim().takeIf(String::isNotEmpty) ?: return null
			return runCatching {
				value.toAbsoluteUrl(java.net.URI(chapterUrl).host)
			}.getOrElse {
				runCatching { java.net.URI(chapterUrl).resolve(value).toString() }.getOrDefault(value)
			}
		}
	}
}
