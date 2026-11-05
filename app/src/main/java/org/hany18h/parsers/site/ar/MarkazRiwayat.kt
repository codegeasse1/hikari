package org.hany18h.parsers.site.ar

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Headers
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Element
import org.hany18h.parsers.MangaLoaderContext
import org.hany18h.parsers.MangaSourceParser
import org.hany18h.parsers.config.ConfigKey
import org.hany18h.parsers.core.PagedMangaParser
import org.hany18h.parsers.model.ContentRating
import org.hany18h.parsers.model.ContentType
import org.hany18h.parsers.model.Manga
import org.hany18h.parsers.model.MangaChapter
import org.hany18h.parsers.model.MangaListFilter
import org.hany18h.parsers.model.MangaListFilterCapabilities
import org.hany18h.parsers.model.MangaListFilterOptions
import org.hany18h.parsers.model.MangaPage
import org.hany18h.parsers.model.MangaParserSource
import org.hany18h.parsers.model.MangaState
import org.hany18h.parsers.model.MangaTag
import org.hany18h.parsers.model.NovelChapterContent
import org.hany18h.parsers.model.NovelImage
import org.hany18h.parsers.model.RATING_UNKNOWN
import org.hany18h.parsers.model.SortOrder
import org.hany18h.parsers.util.generateUid
import org.hany18h.parsers.util.parseHtml
import org.hany18h.parsers.util.parseJson
import org.hany18h.parsers.util.src
import org.hany18h.parsers.util.toAbsoluteUrl
import org.hany18h.parsers.util.toRelativeUrl
import org.hany18h.parsers.util.urlEncoded
import java.net.URI
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("MARKAZ_RIWAYAT", "مركز الروايات", "ar", ContentType.NOVEL)
internal class MarkazRiwayat(context: MangaLoaderContext) : PagedMangaParser(
	context = context,
	source = MangaParserSource.MARKAZ_RIWAYAT,
	pageSize = PAGE_SIZE,
	searchPageSize = PAGE_SIZE,
) {

	private val sessionMutex = Mutex()

	@Volatile
	private var isSiteSessionReady = false

	override val configKeyDomain = ConfigKey.Domain("markazriwayat.com")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.ALPHABETICAL,
	)

	override val filterCapabilities = MangaListFilterCapabilities(
		isMultipleTagsSupported = true,
		isSearchSupported = true,
		isSearchWithFiltersSupported = true,
	)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
		keys.add(ConfigKey.InterceptCloudflare(defaultValue = true))
	}

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		ensureSiteSession()
		val response = webClient.httpGet("$REST_BASE/filters", requestHeaders()).parseJson()
		val tags = parseFilterTags(response.optJSONArray("genres"))
		return MangaListFilterOptions(
			availableTags = tags,
			availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.ABANDONED),
		)
	}

	override suspend fun getListPage(
		page: Int,
		order: SortOrder,
		filter: MangaListFilter,
	): List<Manga> {
		ensureSiteSession()
		val url = buildString {
			append(REST_BASE)
			append("/library?page=")
			append(page)
			append("&per_page=")
			append(PAGE_SIZE)
			filter.query?.trim()?.takeIf(String::isNotEmpty)?.let {
				append("&search=")
				append(it.urlEncoded())
			}
			filter.states.firstOrNull()?.let {
				append("&status=")
				append(stateKey(it))
			}
			if (filter.tags.isNotEmpty()) {
				append("&genres=")
				append(filter.tags.joinToString(",") { it.key }.urlEncoded())
			}
			when (order) {
				SortOrder.POPULARITY -> append("&sort=views")
				SortOrder.ALPHABETICAL -> append("&sort=name")
				else -> Unit
			}
		}
		val items = webClient.httpGet(url, requestHeaders()).parseJson().optJSONArray("items") ?: return emptyList()
		return buildList {
			for (index in 0 until items.length()) {
				items.optJSONObject(index)?.let(::parseLibraryItem)?.let(::add)
			}
		}.distinctBy(Manga::id)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val mangaId = manga.url.substringAfterLast('/').toIntOrNull() ?: return manga
		val document = webClient.httpGet(manga.publicUrl, requestHeaders()).parseHtml()
		val cover = document.selectFirst(".manga-cover-wrap > img")
			?.let(::promoteLazyImage)
			?: document.selectFirst("meta[property=og:image]")?.attr("content")
			?: manga.coverUrl
		val tags = document.select("a.pill[href*='/tasnif/']").mapNotNullTo(LinkedHashSet()) { element ->
			val title = element.text().trim().takeIf(String::isNotEmpty) ?: return@mapNotNullTo null
			val key = element.attr("href").substringAfter("/tasnif/").substringBefore('/').takeIf(String::isNotEmpty)
				?: title
			MangaTag(title = title, key = key, source = source)
		}
		val authors = document.select(".manga-author a.manga-author__link")
			.mapNotNullTo(LinkedHashSet()) { it.text().trim().takeIf(String::isNotEmpty) }
		val description = document.selectFirst("#manga-summary")?.html()
			?.let(::normalizeMarkdownBold)
			?.takeIf(String::isNotBlank)
			?: manga.description
		val rating = document.selectFirst("[data-rating-value]")?.text()?.toFloatOrNull()
			?.takeIf { it > 0f }?.div(5f)?.coerceIn(0f, 1f)
			?: manga.rating

		return manga.copy(
			title = document.selectFirst("h1.manga-title")?.text()?.trim()
				?.takeIf(String::isNotEmpty) ?: manga.title,
			altTitles = parseAlternativeTitles(document.select("script[type=application/ld+json]"))
				.ifEmpty { manga.altTitles },
			coverUrl = cover,
			largeCoverUrl = cover,
			description = description,
			tags = tags.ifEmpty { manga.tags },
			state = parseState(document.selectFirst(".manga-status-pill")?.text()) ?: manga.state,
			authors = authors.ifEmpty { manga.authors },
			rating = rating,
			chapters = loadAllChapters(mangaId),
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> = emptyList()

	override suspend fun getChapterContent(chapter: MangaChapter): NovelChapterContent? {
		val chapterUrl = chapter.url.toAbsoluteUrl(domain)
		val document = webClient.httpGet(chapterUrl, requestHeaders(chapterUrl)).parseHtml()
		val content = document.selectFirst(".reading-content .text-right")
			?: document.selectFirst(".reading-content")
			?: return null
		sanitizeChapterContent(content)
		if (content.text().isBlank() && content.selectFirst("img") == null) return null

		val images = content.select("img[src]").mapNotNull { image ->
			image.src()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }?.let { imageUrl ->
				NovelImage(
					url = imageUrl,
					headers = mapOf(
						"Referer" to chapterUrl,
						"User-Agent" to config[userAgentKey],
					),
				)
			}
		}.distinctBy(NovelImage::url)

		return NovelChapterContent(
			html = content.html(),
			images = images,
		)
	}

	private suspend fun loadAllChapters(mangaId: Int): List<MangaChapter> {
		ensureSiteSession()
		val result = ArrayList<MangaChapter>()
		var page = 1
		var hasMore = false
		do {
			val response = webClient.httpGet(
				"$REST_BASE/manga-chapters?manga_id=$mangaId&page=$page" +
					"&per_page=$CHAPTER_PAGE_SIZE&order=asc",
				requestHeaders(),
			).parseJson()
			val items = response.optJSONArray("items") ?: break
			for (index in 0 until items.length()) {
				val item = items.optJSONObject(index) ?: continue
				if (item.optBoolean("early", false)) continue
				val absoluteUrl = item.optString("url").takeIf(String::isNotBlank) ?: continue
				val number = item.optString("num").toFloatOrNull()
					?: NUMBER.find(item.optString("label"))?.value?.toFloatOrNull()
					?: continue
				result += MangaChapter(
					id = generateUid(absoluteUrl),
					title = item.optString("label").trim().ifEmpty { "الفصل ${formatNumber(number)}" },
					number = number,
					volume = 0,
					url = absoluteUrl.toRelativeUrl(domain),
					scanlator = null,
					uploadDate = item.optLong("ts", 0L).takeIf { it > 0 }?.times(1000L) ?: 0L,
					branch = null,
					source = source,
				)
			}
			hasMore = response.optBoolean("has_more", false)
			page++
		} while (hasMore && page <= MAX_CHAPTER_PAGES)
		return result.distinctBy(MangaChapter::id).sortedBy(MangaChapter::number)
	}

	private fun parseLibraryItem(item: JSONObject): Manga? {
		val mangaId = item.optInt("id").takeIf { it > 0 } ?: return null
		val title = item.optString("title").trim().takeIf(String::isNotEmpty) ?: return null
		val publicUrl = item.optString("link").takeIf(String::isNotBlank) ?: return null
		val tags = parseItemTags(item.optJSONArray("genres"))
		val description = item.optString("summary_preview_html").takeIf(String::isNotBlank)
			?.let(::normalizeMarkdownBold)
		return Manga(
			id = generateUid(mangaId.toString()),
			title = title,
			altTitles = emptySet(),
			url = "$INTERNAL_NOVEL_PATH$mangaId",
			publicUrl = publicUrl,
			rating = RATING_UNKNOWN,
			contentRating = ContentRating.SAFE,
			coverUrl = item.optString("cover").takeIf(String::isNotBlank),
			tags = tags,
			state = parseState(item.optJSONObject("status")?.optString("key")),
			authors = emptySet(),
			description = description,
			source = source,
		)
	}

	private fun parseFilterTags(array: JSONArray?): Set<MangaTag> {
		if (array == null) return emptySet()
		return buildSet {
			for (index in 0 until array.length()) {
				val item = array.optJSONObject(index) ?: continue
				val title = item.optString("name").trim().takeIf(String::isNotEmpty) ?: continue
				val key = item.optString("slug").trim().takeIf(String::isNotEmpty) ?: title
				add(MangaTag(title = title, key = key, source = source))
			}
		}
	}

	private fun parseItemTags(array: JSONArray?): Set<MangaTag> {
		if (array == null) return emptySet()
		return buildSet {
			for (index in 0 until array.length()) {
				val item = array.optJSONObject(index) ?: continue
				val title = item.optString("name").trim().takeIf(String::isNotEmpty) ?: continue
				val key = item.optString("slug").trim().takeIf(String::isNotEmpty) ?: title
				add(MangaTag(title = title, key = key, source = source))
			}
		}
	}

	private fun parseAlternativeTitles(scripts: Iterable<Element>): Set<String> = buildSet {
		for (script in scripts) {
			val json = runCatching { JSONObject(script.data()) }.getOrNull() ?: continue
			if (!json.optString("@type").equals("Book", true)) continue
			json.optString("alternateName").trim().takeIf(String::isNotEmpty)?.let(::add)
		}
	}

	private fun parseState(value: String?): MangaState? {
		val normalized = value.orEmpty().lowercase(Locale.ROOT)
		return when {
			"end" in normalized || "complete" in normalized || "مكتمل" in normalized -> MangaState.FINISHED
			"cancel" in normalized || "hold" in normalized || "متوقف" in normalized -> MangaState.ABANDONED
			"ongoing" in normalized || "on-going" in normalized || "مستمر" in normalized -> MangaState.ONGOING
			else -> null
		}
	}

	private fun stateKey(state: MangaState): String = when (state) {
		MangaState.FINISHED -> "end"
		MangaState.ABANDONED -> "canceled"
		else -> "on-going"
	}

	private fun formatNumber(number: Float): String =
		if (number % 1f == 0f) number.toInt().toString() else number.toString()

	private suspend fun ensureSiteSession() {
		if (isSiteSessionReady) return
		sessionMutex.withLock {
			if (isSiteSessionReady) return@withLock
			// Cloudflare may allow a normal document navigation while rejecting a
			// cold OkHttp request made directly to wp-json. Opening the public
			// library first also gives the app's Cloudflare resolver a real HTML
			// page on which it can complete a managed challenge and persist the
			// resulting cookies before the REST request is retried.
			webClient.httpGet(
				"https://$domain/library/",
				browserHeaders(),
			).parseHtml()
			isSiteSessionReady = true
		}
	}

	private fun browserHeaders(): Headers = Headers.Builder()
		.add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
		.add("Accept-Language", "ar,en-US;q=0.8,en;q=0.7")
		.add("Sec-Fetch-Dest", "document")
		.add("Sec-Fetch-Mode", "navigate")
		.add("Sec-Fetch-Site", "none")
		.add("Upgrade-Insecure-Requests", "1")
		.add("User-Agent", config[userAgentKey])
		.build()

	private fun requestHeaders(referer: String = "https://$domain/library/"): Headers = Headers.Builder()
		.add("Accept", "application/json, text/plain, */*")
		.add("Accept-Language", "ar,en-US;q=0.8,en;q=0.7")
		.add("Origin", "https://$domain")
		.add("Referer", referer)
		.add("Sec-Fetch-Dest", "empty")
		.add("Sec-Fetch-Mode", "cors")
		.add("Sec-Fetch-Site", "same-origin")
		.add("User-Agent", config[userAgentKey])
		.build()

	internal companion object {
		const val PAGE_SIZE = 24
		const val CHAPTER_PAGE_SIZE = 200
		const val MAX_CHAPTER_PAGES = 50
		const val REST_BASE = "https://markazriwayat.com/wp-json/theam/v1"
		const val INTERNAL_NOVEL_PATH = "/api-novel/"
		val NUMBER = Regex("""\d+(?:\.\d+)?""")
		val EMPTY_SEPARATOR = Regex("""^[\s=_\-–—•·]{5,}$""")
		val MARKDOWN_BOLD = Regex("""\*\*(.+?)\*\*""")

		fun sanitizeChapterContent(content: Element): Element {
			content.select(
				"script, style, iframe, noscript, form, input, select, option, textarea, button, label, svg, canvas, " +
					"[class*=donat], [id*=donat], [class*=support], [id*=support], " +
					"[class*=paypal], [id*=paypal], [class*=vip], [id*=vip], " +
					".theam-chobf, [data-theam-chobf], [hidden], [aria-hidden=true]",
			).remove()
			content.select("img").forEach { promoteLazyImage(it) }
			content.select("p, div, span").forEach { element ->
				if (EMPTY_SEPARATOR.matches(element.text().trim()) && element.selectFirst("img") == null) {
					element.remove()
				}
			}
			content.select("p").forEach { paragraph ->
				if (paragraph.text().trim().isEmpty() && paragraph.selectFirst("img") == null) {
					paragraph.remove()
				}
			}
			content.select("*").forEach { element ->
				element.removeAttr("class")
				.removeAttr("id")
				.removeAttr("style")
				.removeAttr("dir")
				.removeAttr("data-lazyloaded")
				.removeAttr("loading")
				.removeAttr("decoding")
			}
			return content
		}

		fun promoteLazyImage(image: Element): String? {
			val current = image.attr("src").trim().takeUnless {
				it.isEmpty() || it.startsWith("data:", true) || it == "#"
			}
			val value = current ?: sequenceOf(
				"data-src",
				"data-lazy-src",
				"data-original",
				"data-url",
			).map { image.attr(it).trim() }.firstOrNull {
				it.isNotEmpty() && !it.startsWith("data:", true)
			} ?: return null
			val absolute = runCatching {
				when {
					value.startsWith("//") -> "https:$value"
					value.startsWith("http://") || value.startsWith("https://") -> value
					else -> URI(image.baseUri()).resolve(value).toString()
				}
			}.getOrDefault(value)
			image.attr("src", absolute)
				.removeAttr("srcset")
				.removeAttr("data-src")
				.removeAttr("data-lazy-src")
				.removeAttr("data-original")
				.removeAttr("data-url")
			return absolute
		}

		fun normalizeMarkdownBold(html: String): String =
			MARKDOWN_BOLD.replace(html) { match -> "<strong>${match.groupValues[1]}</strong>" }
	}
}
