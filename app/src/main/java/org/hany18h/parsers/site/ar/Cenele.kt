package org.hany18h.parsers.site.ar

import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.hany18h.parsers.MangaLoaderContext
import org.hany18h.parsers.MangaSourceParser
import org.hany18h.parsers.config.ConfigKey
import org.hany18h.parsers.core.PagedMangaParser
import org.hany18h.parsers.model.*
import org.hany18h.parsers.network.OkHttpWebClient
import org.hany18h.parsers.network.WebClient
import org.hany18h.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("CENELE", "فضاء الروايات", "ar", ContentType.NOVEL)
internal class Cenele(private val loaderContext: MangaLoaderContext) :
	PagedMangaParser(loaderContext, MangaParserSource.CENELE, pageSize = HTML_LIBRARY_PAGE_SIZE) {
	private val publicChapterClient: WebClient by lazy {
		OkHttpWebClient(
			loaderContext.httpClient.newBuilder()
				.cookieJar(CookieJar.NO_COOKIES)
				.cache(null)
				.build(),
			source,
		)
	}

	init {
		setFirstPage(firstPage = 1, firstPageForSearch = 1)
	}

	override val configKeyDomain = ConfigKey.Domain("cenele.com")
	override val userAgentKey = ConfigKey.UserAgent(CENELE_BROWSER_USER_AGENT)

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.ALPHABETICAL,
		SortOrder.NEWEST,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
		)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
		keys.add(ConfigKey.InterceptCloudflare(defaultValue = true))
	}

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = CENELE_GENRES.mapTo(LinkedHashSet()) { title ->
			MangaTag(key = title, title = title, source = source)
		},
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val query = filter.query?.trim().orEmpty()
		val sort = when (order) {
			SortOrder.POPULARITY -> "views"
			SortOrder.ALPHABETICAL -> "alphabet"
			else -> "latest"
		}
		val url = if (query.isNotEmpty()) {
			"https://$domain/?s=${query.urlEncoded()}&post_type=wp-manga&page=$page"
		} else {
			val tag = filter.tags.oneOrThrowIfMany()?.key
			val root = if (tag == null) "/cont/" else "/cont-genre/${tag.urlEncoded()}/"
			val path = if (page <= 1) root else "${root}page/$page/"
			"https://$domain$path?m_orderby=$sort"
		}
		val doc = loadPublicDocument(
			url,
			"https://$domain/",
			"article.nhv-library-card, div.row.c-tabs-item__content",
		)

		return doc.select("article.nhv-library-card, div.row.c-tabs-item__content").mapNotNull { card ->
			val link = card.selectFirst(".nhv-library-card__title a, .post-title a, h3 a, h4 a")
				?: card.selectFirst("a[href*=/cont/]")
				?: return@mapNotNull null
			val href = link.attrAsRelativeUrl("href")
			val title = card.selectFirst(".nhv-library-card__title, .post-title, h3, h4, .manga-name")
				?.text()?.trim().orEmpty()
				.ifEmpty { link.attr("title") }
			if (title.isEmpty()) return@mapNotNull null
			val status = parseState(
				card.selectFirst(".nhv-library-card__status, .summary-content")?.text().orEmpty(),
			)
			if (filter.states.isNotEmpty() && status !in filter.states) return@mapNotNull null
			Manga(
				id = generateUid(href),
				title = title,
				altTitles = emptySet(),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = null,
				coverUrl = card.selectFirst(".nhv-library-card__cover img, .tab-thumb img, img")?.src(),
				tags = emptySet(),
				state = status,
				authors = emptySet(),
				source = source,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val pageUrl = manga.url.substringBefore('#').toAbsoluteUrl(domain)
		val doc = loadPublicDocument(
			pageUrl,
			"https://$domain/cont/",
			"h1.nhv-novel-title, div.post-title h1",
		)
		val state = parseState(
			doc.selectFirst(".nhv-novel-status, .post-status .summary-content")?.text().orEmpty(),
		)
		val authors = doc.select(".nhv-novel-integrations a[href*=/cont-artist/], .author-content a")
			.mapNotNullToSet { it.text().trim().ifEmpty { null } }
		val tags = doc.select(".nhv-novel-genres a, .genres-content a").mapToSet { link ->
			MangaTag(
				key = link.attr("href").removeSuffix("/").substringAfterLast("/"),
				title = link.text().trim(),
				source = source,
			)
		}
		val cover = doc.selectFirst(".nhv-novel-cover img, .summary_image img")?.src() ?: manga.coverUrl
		val altTitle = doc.selectFirst(".manga-name-or, .post-content_item:contains(Alt) .summary-content")
			?.text()?.trim()

		return manga.copy(
			title = doc.selectFirst("h1.nhv-novel-title, div.post-title h1")?.text()?.trim() ?: manga.title,
			altTitles = setOfNotNull(altTitle?.ifEmpty { null }),
			url = manga.url.substringBefore('#'),
			publicUrl = pageUrl,
			description = loadDescription(doc),
			coverUrl = cover,
			largeCoverUrl = cover,
			state = state ?: manga.state,
			authors = authors,
			tags = tags,
			chapters = loadChapters(pageUrl, doc),
		)
	}

	private fun loadDescription(doc: Document): String? =
		doc.selectFirst(".nhv-novel-synopsis, div.summary__content, .manga-excerpt .excerpt-content")
			?.html()?.trim()?.takeIf(String::isNotEmpty)

	private suspend fun loadChapters(pageUrl: String, doc: Document): List<MangaChapter> {
		val request = chapterListRequest(doc)
		val mangaId = request?.first
			?: doc.selectFirst("[data-manga-id]")?.attr("data-manga-id")?.takeIf(String::isNotBlank)
			?: doc.selectFirst("#manga-chapters-holder")?.attr("data-id")?.takeIf(String::isNotBlank)
		if (request != null) {
			val items = org.jsoup.select.Elements()
			val seen = HashSet<String>()
			for (page in 1..MAX_CHAPTER_PAGES) {
				val response = publicChapterClient.httpPost(
					"https://$domain/wp-admin/admin-ajax.php".toHttpUrl(),
					mapOf(
						"action" to "nhv_manga_single_chapters_page",
						"nonce" to request.second,
						"manga_id" to request.first,
						"volume" to "-1",
						"page" to page.toString(),
						"per_page" to CHAPTERS_PER_PAGE.toString(),
						"order" to "desc",
					),
					Headers.Builder()
						.add("Accept", "application/json")
						.add("Referer", pageUrl)
						.add("X-Requested-With", "XMLHttpRequest")
						.add("User-Agent", config[userAgentKey])
						.build(),
				).parseJson()
				check(response.optBoolean("success")) { "Cenele rejected the public chapter list request" }
				val fragment = Jsoup.parseBodyFragment(response.optString("html"), pageUrl)
				val pageItems = fragment.select("li.wp-manga-chapter")
				val countBeforePage = seen.size
				for (item in pageItems) {
					val key = item.attr("data-chapter-id").ifBlank {
						item.selectFirst("a[href]")?.attr("href").orEmpty()
					}
					if (key.isNotBlank() && seen.add(key)) items.add(item)
				}
				check(pageItems.isEmpty() || seen.size > countBeforePage) {
					"Cenele chapter list stopped advancing"
				}
				if (!response.optBoolean("has_more")) {
					if (items.isNotEmpty()) return parseChapterElements(items, mangaId)
					break
				}
				check(pageItems.isNotEmpty()) {
					"Cenele chapter list stopped advancing"
				}
			}
			if (items.isNotEmpty()) error("Cenele chapter list exceeded $MAX_CHAPTER_PAGES pages")
		}
		val chapters = parseChapterElements(selectPublicChapterElements(doc), mangaId)
		val firstChapter = doc.select("a[href]:contains(ابدأ القراءة)")
			.firstOrNull { "/cont/" in it.attr("href") }
		val firstHref = firstChapter?.attrAsRelativeUrlOrNull("href")
		if (firstHref == null || chapters.any { it.url.substringBefore('#') == firstHref }) return chapters
		return listOf(
			MangaChapter(
				id = generateUid(firstHref),
				title = "الفصل الأول",
				number = 0f,
				volume = 0,
				url = firstHref,
				scanlator = null,
				uploadDate = 0L,
				branch = null,
				source = source,
			),
		) + chapters
	}

	private fun parseChapterElements(
		elements: org.jsoup.select.Elements,
		mangaId: String?,
	): List<MangaChapter> {
		val dateFormat = SimpleDateFormat("MMMM dd, yyyy", Locale("ar"))
		val dateFormatEn = SimpleDateFormat("MMMM dd, yyyy", Locale.ENGLISH)
		val dateFormatShort = SimpleDateFormat("MMM dd, yyyy", Locale.ENGLISH)

		return elements.mapIndexedNotNull { index, li ->
			val a = li.selectFirst("a") ?: return@mapIndexedNotNull null
			// نأخذ href النظيف بدون ?style=list
			val href = a.attrAsRelativeUrlOrNull("href") ?: return@mapIndexedNotNull null
			val chapterId = li.attr("data-chapter-id").takeIf(String::isNotBlank)
			val parserUrl = attachCeneleChapterLocator(href, mangaId, chapterId)
			val chapterName = a.text().trim()
			val dateText = li.selectFirst(".chapter-release-date i, .chapter-release-date")
				?.text()?.trim().orEmpty()

			val number = Regex("""(\d+\.?\d*)""").find(chapterName)
				?.value?.toFloatOrNull()
				?: (elements.size - index).toFloat()

			val uploadDate =
				runCatching { dateFormat.parse(dateText)?.time }.getOrNull()
					?: runCatching { dateFormatEn.parse(dateText)?.time }.getOrNull()
					?: runCatching { dateFormatShort.parse(dateText)?.time }.getOrNull()
					?: 0L

			MangaChapter(
				id = generateUid(href),
				title = chapterName,
				number = number,
				volume = 0,
				url = parserUrl,
				scanlator = null,
				uploadDate = uploadDate,
				branch = null,
				source = source,
			)
		}.reversed()
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> = emptyList()

	override suspend fun getChapterContent(chapter: MangaChapter): NovelChapterContent? {
		// نضمن إزالة ?style=list من أي URL قديم مخزن
		val cleanUrl = chapter.url
			.substringBefore('#')
			.replace("?style=list", "")
			.replace("&style=list", "")
			.toAbsoluteUrl(domain)
		val locator = parseCeneleChapterLocator(chapter.url)

		// Use only the public reader page. Its markup is randomized on every
		// response, so bypass cached/304 bodies and locate the stable chapter marker.
		val doc = loadPublicDocument(
			cleanUrl,
			"https://$domain/",
			".reading-content p, .reading-content img",
			noCache = true,
		)
		val content = findDirectChapterContent(doc, locator) ?: return null

		sanitizeChapterContent(content)

		// عنوان الفصل
		val title = doc.selectFirst("h3.chapter-name")?.text()?.trim()
			?: chapter.title
			?: ""

		return NovelChapterContent(
			html = buildString {
				if (title.isNotBlank()) append(Element("h1").text(title).outerHtml())
				append(content.html())
			},
			images = content.select("img").mapNotNull { image ->
				image.src()?.let { url ->
					NovelImage(
						url = url,
						headers = mapOf(
							"Referer" to cleanUrl,
							"User-Agent" to config[userAgentKey],
						),
					)
				}
			}.distinctBy(NovelImage::url),
		)
	}

	private suspend fun loadPublicDocument(
		url: String,
		referer: String,
		readySelector: String,
		noCache: Boolean = false,
	): Document {
		// Prefer the server-rendered public HTML. Android WebView is only a
		// fallback when the server blocks the direct request.
		val directResult = runCatchingCancellable {
			webClient.httpGet(url, siteHeaders(referer, noCache)).parseHtml()
		}
		directResult.getOrNull()?.takeUnless(::isBlockedDocument)?.let { return it }

		val webViewResult = runCatchingCancellable {
			val quotedSelector = JSONObject.quote(readySelector)
			val rawResult = loaderContext.evaluateJs(
				url,
				"""
				(function() {
				  if (document.readyState === 'loading') return null;
				  if (!document.querySelector($quotedSelector)) return null;
				  return document.documentElement ? document.documentElement.outerHTML : null;
				})()
				""".trimIndent(),
				mapOf("User-Agent" to config[userAgentKey], "Referer" to referer),
			) ?: return@runCatchingCancellable null
			decodeWebViewString(rawResult)?.let { Jsoup.parse(it, url) }
		}
		webViewResult.getOrNull()?.takeIf { isReadyDocument(it, readySelector) }?.let { return it }
		directResult.exceptionOrNull()?.let { throw it }
		webViewResult.exceptionOrNull()?.let { throw it }
		error("Cenele returned a server block page instead of public content")
	}

	private fun siteHeaders(referer: String, noCache: Boolean = false): Headers = Headers.Builder()
		.add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
		.add("Accept-Language", "ar,en-US;q=0.7,en;q=0.3")
		.add("Referer", referer)
		.add("Upgrade-Insecure-Requests", "1")
		.add("User-Agent", config[userAgentKey])
		.apply {
			if (noCache) {
				add("Cache-Control", "no-cache, no-store")
				add("Pragma", "no-cache")
			}
		}
		.build()

	internal companion object {
		private const val CHAPTERS_PER_PAGE = 100
		private const val MAX_CHAPTER_PAGES = 50
		private val CHAPTERS_POST_ID = Regex("""["']postId["']\s*:\s*["']?(\d+)""")
		private val CHAPTERS_NONCE = Regex("""["']chaptersNonce["']\s*:\s*["']([^"']+)""")

		internal fun chapterListRequest(doc: Document): Pair<String, String>? {
			val scripts = doc.select("script").joinToString("\n") { it.data() + it.html() }
			val id = CHAPTERS_POST_ID.find(scripts)?.groupValues?.getOrNull(1)
				?: doc.selectFirst("[data-manga-id]")?.attr("data-manga-id")
			val nonce = CHAPTERS_NONCE.find(scripts)?.groupValues?.getOrNull(1)
			return if (id.isNullOrBlank() || nonce.isNullOrBlank()) null else id to nonce
		}

		internal fun isReadyDocument(document: Document, selector: String): Boolean =
			!isBlockedDocument(document) && document.selectFirst(selector) != null

		internal fun selectPublicChapterElements(doc: Document): org.jsoup.select.Elements =
			doc.select(".nhv-novel-recent__list li.wp-manga-chapter, ul.main li.wp-manga-chapter")

		internal fun decodeWebViewString(rawResult: String): String? = runCatching {
			JSONObject("{\"value\":$rawResult}").optString("value").trim().takeIf(String::isNotEmpty)
		}.getOrNull()

		internal fun isBlockedDocument(document: Document): Boolean {
			val text = (document.title() + " " + document.text()).lowercase(Locale.ROOT)
			return BLOCK_PAGE_MARKERS.any(text::contains)
		}

		private val ZERO_WIDTH_MARKS = Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u206F\\uFEFF]")
		private const val CENELE_BROWSER_USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
			"AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
		private val HIDDEN_CSS_CLASS = Regex(
			"""\.([A-Za-z][\w-]*)\s*\{[^{}]*display\s*:\s*none(?:\s*!important)?[^{}]*\}""",
			RegexOption.IGNORE_CASE,
		)
		private val VISUALLY_HIDDEN_CSS_CLASS = Regex(
			"""\.([A-Za-z][\w-]*)\s*\{(?=[^{}]*position\s*:\s*absolute)(?=[^{}]*width\s*:\s*1px)(?=[^{}]*height\s*:\s*1px)[^{}]*\}""",
			RegexOption.IGNORE_CASE,
		)
		private const val HTML_LIBRARY_PAGE_SIZE = 10
		private val CENELE_GENRES = listOf(
			"أكشن", "استراتجي", "انتقام", "بالغ", "بطل شرير", "بناء مملكة", "بوليسي",
			"تاريخي", "تشويق", "حريم", "حياة يومية", "خيال", "خيال علمي", "دراما", "رعب",
			"رومانسية", "زراعة", "سحر", "شونين", "عسكري", "غموض", "فانتازيا", "فنون قتالية",
			"قوى خارقة", "كوميديا", "للكبار", "لعبة", "مأساة", "مدرسي", "مغامرة", "نظام", "نفسي",
		)
		private val BLOCK_PAGE_MARKERS = listOf(
			"تم حظرك من قبل الخادم",
			"حاول استخدام شبكة اتصال مختلفة",
			"you have been blocked",
			"attention required! | cloudflare",
			"sorry, you have been blocked",
		)

		internal fun findDirectChapterContent(
			doc: Document,
			locator: CeneleChapterLocator?,
		): Element? {
			val chapterRoot = if (locator != null) {
				doc.selectFirst(
					"#chapter-${locator.chapterId}, " +
						".reading-content[data-block-chapter-id=${locator.chapterId}]",
				)
			} else {
				doc.selectFirst(".reading-content.current, .reading-content[data-block-chapter-id]")
			}
			if (chapterRoot == null) return null
			// Cenele now randomizes the wrapper tag/class and adds `text-left` only
			// after JavaScript runs. The hidden chapter URL is the stable server-side
			// marker, so prefer its direct parent over generic article elements (which
			// can be donation/promo cards inside the same chapter container).
			val exactMarker = locator?.let { value ->
				chapterRoot.selectFirst("input#chapter-url-${value.chapterId}")
			} ?: chapterRoot.selectFirst("input[id^=chapter-url-]")
			return exactMarker?.parent()
				?: chapterRoot.selectFirst(".text-left, .text-content, .text-chapter-content, article")
		}

		internal fun sanitizeChapterContent(content: Element): Element {
			// These server-rendered blocks are invisible on Cenele but become
			// visible when the chapter HTML is displayed without the site's CSS.
			content.select("[data-nhv-reader-promo], [inert][data-nosnippet]").remove()

			val hiddenClasses = content.select("style").flatMap { style ->
				val css = style.data() + style.html()
				HIDDEN_CSS_CLASS.findAll(css).map { it.groupValues[1] }.toList() +
					VISUALLY_HIDDEN_CSS_CLASS.findAll(css).map { it.groupValues[1] }.toList()
			}.distinct()
			hiddenClasses.forEach { className -> content.getElementsByClass(className).remove() }

			// Cenele injects the anti-copy span inside the same <p> as the real text.
			// Remove the marker's following bait paragraph before deleting generic
			// templates. This keeps the relationship intact even if the warning text
			// changes while still preserving the surrounding real paragraphs.
			content.select("template[data-nhv-rb]").forEach { marker ->
				val bait = marker.nextElementSibling()
					?.takeIf { it.tagName() == "p" && isAntiCopyText(it.text()) }
				bait?.remove()
				marker.remove()
			}

			// Remove hidden descendants before examining visible paragraphs; checking
			// a parent's full text first would include the injected hidden watermark
			// and could classify a complete real paragraph as bait.
			content.select(
				"span[aria-hidden=true], " +
					"p[aria-hidden=true], " +
					"span[role=presentation], " +
					"p[role=presentation], " +
					"input[type=hidden], [hidden], .d-none, " +
					"[style*=\"display:none\"], [style*=\"display: none\"], " +
					"[style*=\"visibility:hidden\"], [style*=\"visibility: hidden\"], " +
					"script, style, ins, iframe, noscript, template, " +
					".adsbygoogle, .google-auto-placed, " +
					"[id^=ezoic], [id^=pf-], [id^=bg-ssp]",
			).remove()

			// Use ownText first so a future unrecognised hidden child cannot cause a
			// real paragraph to be deleted together with the watermark.
			content.select("p, span").forEach { element ->
				if (
					isAntiCopyText(element.ownText()) ||
					(element.children().isEmpty() && isAntiCopyText(element.text()))
				) {
					element.remove()
				}
			}
			content.select("img").forEach(::promoteLazyImageSource)
			content.select("p").forEach { paragraph ->
				if (paragraph.text().trim().isEmpty() && paragraph.selectFirst("img") == null) {
					paragraph.remove()
				}
			}
			return content
		}

		internal fun isAntiCopyText(value: String): Boolean {
			val normalized = ZERO_WIDTH_MARKS.replace(value, "")
				.replace(Regex("\\s+"), " ")
				.trim()
				.lowercase(Locale.ROOT)
			if ("فضاء الروايات" !in normalized && "cenele.com" !in normalized) return false
			return "نص تمويهي" in normalized ||
				"هذا تنبيه" in normalized ||
				"تطبيق سارق" in normalized ||
				"المصدر مسروق" in normalized
		}

		private fun promoteLazyImageSource(image: Element) {
			val current = image.attr("src").trim()
			if (current.isNotEmpty() && !current.startsWith("data:", true) && current != "#") return
			for (attribute in arrayOf("data-src", "data-lazy-src", "data-original", "data-url")) {
				val value = image.attr(attribute).trim()
				.takeIf { it.isNotEmpty() && !it.startsWith("data:", true) }
				?: continue
				image.attr("src", value)
					.removeAttr("srcset")
					.removeAttr("data-src")
					.removeAttr("data-lazy-src")
					.removeAttr("data-original")
					.removeAttr("data-url")
				return
			}
		}
	}

	private fun parseState(value: String): MangaState? {
		val status = value.trim().lowercase(Locale.ROOT)
		return when {
			status.contains("مستمر") || status.contains("ongoing") || status.contains("on-going") -> MangaState.ONGOING
			status.contains("مكتمل") || status.contains("completed") || status == "end" -> MangaState.FINISHED
			else -> null
		}
	}
}

internal data class CeneleChapterLocator(
	val mangaId: String,
	val chapterId: String,
)

internal fun attachCeneleChapterLocator(
	url: String,
	mangaId: String?,
	chapterId: String?,
): String {
	if (mangaId.isNullOrBlank() || chapterId.isNullOrBlank()) return url
	return "${url.substringBefore('#')}#cenele=$mangaId:$chapterId"
}

internal fun parseCeneleChapterLocator(url: String): CeneleChapterLocator? {
	val value = url.substringAfter("#cenele=", missingDelimiterValue = "")
	if (value.isEmpty()) return null
	val mangaId = value.substringBefore(':').takeIf(String::isNotBlank) ?: return null
	val chapterId = value.substringAfter(':', missingDelimiterValue = "").takeIf(String::isNotBlank) ?: return null
	return CeneleChapterLocator(mangaId, chapterId)
}
