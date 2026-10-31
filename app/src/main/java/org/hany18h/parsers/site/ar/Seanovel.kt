package org.hany18h.parsers.site.ar

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Element
import org.hany18h.parsers.MangaLoaderContext
import org.hany18h.parsers.MangaSourceParser
import org.hany18h.parsers.config.ConfigKey
import org.hany18h.parsers.core.PagedMangaParser
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
import org.hany18h.parsers.util.mapNotNullToSet
import org.hany18h.parsers.util.oneOrThrowIfMany
import org.hany18h.parsers.util.parseHtml
import org.hany18h.parsers.util.parseJson
import org.hany18h.parsers.util.parseJsonArray
import org.hany18h.parsers.util.src
import org.hany18h.parsers.util.toAbsoluteUrl
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("SEANOVEL", "بحر الروايات", "ar", ContentType.NOVEL)
internal class Seanovel(context: MangaLoaderContext) : PagedMangaParser(
	context = context,
	source = MangaParserSource.SEANOVEL,
	pageSize = PAGE_SIZE,
) {

	override val configKeyDomain = ConfigKey.Domain("seanovel.org")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.NEWEST,
		SortOrder.POPULARITY,
		SortOrder.ALPHABETICAL,
		SortOrder.RELEVANCE,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isMultipleTagsSupported = true,
		)

	init {
		setFirstPage(1)
	}

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		val index = loadSearchIndex()
		val tags = LinkedHashSet<MangaTag>()
		for (i in 0 until index.length()) {
			val genres = index.optJSONObject(i)?.optJSONArray("genres") ?: continue
			for (j in 0 until genres.length()) {
				val title = genres.optString(j).trim()
				.takeIf(String::isNotEmpty)
				?: continue
				tags += MangaTag(key = title, title = formatTagTitle(title), source = source)
			}
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
		val query = filter.query?.trim()?.lowercase(Locale.ROOT).orEmpty()
		val selectedTags = filter.tags.mapTo(HashSet()) { it.key.lowercase(Locale.ROOT) }
		val selectedState = filter.states.oneOrThrowIfMany()

		val items = buildList {
			val index = loadSearchIndex()
			for (i in 0 until index.length()) {
				val item = index.optJSONObject(i) ?: continue
				if (!matchesFilter(item, query, selectedTags, selectedState)) continue
				add(item)
			}
		}.sortedWith(indexComparator(order, query))

		val start = ((page - 1).coerceAtLeast(0) * PAGE_SIZE).coerceAtMost(items.size)
		val end = (start + PAGE_SIZE).coerceAtMost(items.size)
		return items.subList(start, end).mapNotNull(::parseIndexItem)
	}

	private suspend fun loadSearchIndex(): JSONArray =
		webClient.httpGet("https://$domain/api/search-index").parseJsonArray()

	private fun matchesFilter(
		item: JSONObject,
		query: String,
		selectedTags: Set<String>,
		selectedState: MangaState?,
	): Boolean {
		if (query.isNotEmpty()) {
			val searchable = sequenceOf(
				item.optString("title_ar"),
				item.optString("title_original"),
				item.optString("author"),
			).joinToString(" ").lowercase(Locale.ROOT)
			if (query !in searchable) return false
		}
		if (selectedTags.isNotEmpty()) {
			val genres = item.optJSONArray("genres")
				?.let(::jsonStrings)
				.orEmpty()
				.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
			if (!genres.containsAll(selectedTags)) return false
		}
		if (selectedState != null && parseState(item.optString("status")) != selectedState) {
			return false
		}
		return true
	}

	private fun indexComparator(order: SortOrder, query: String): Comparator<JSONObject> = when (order) {
		SortOrder.ALPHABETICAL -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.optString("title_ar") }
		SortOrder.POPULARITY -> compareByDescending { it.optInt("chapters_count") }
		SortOrder.NEWEST -> compareByDescending { it.optString("first_published_at") }
		SortOrder.RELEVANCE -> {
			if (query.isEmpty()) {
				compareByDescending { it.optString("last_updated") }
			} else {
				compareBy<JSONObject> {
					when {
						it.optString("title_ar").equals(query, ignoreCase = true) -> 0
						it.optString("title_ar").startsWith(query, ignoreCase = true) -> 1
						else -> 2
					}
				}.thenByDescending { it.optString("last_updated") }
			}
		}
		else -> compareByDescending { it.optString("last_updated") }
	}

	private fun parseIndexItem(item: JSONObject): Manga? {
		val slug = item.optString("slug").trim().takeIf(String::isNotEmpty) ?: return null
		val title = item.optString("title_ar").trim().takeIf(String::isNotEmpty) ?: return null
		val path = "/novels/$slug"
		val coverVersion = item.optString("cover_version").trim()
		val cover = buildString {
			append("https://")
			append(domain)
			append("/api/novel/")
			append(slug)
			append("/cover?type=webp")
			if (coverVersion.isNotEmpty()) {
				append("&v=")
				append(coverVersion)
			}
		}
		return Manga(
			id = generateUid(path),
			title = title,
			altTitles = setOfNotNull(item.optString("title_original").trim().ifEmpty { null }),
			url = path,
			publicUrl = path.toAbsoluteUrl(domain),
			rating = RATING_UNKNOWN,
			contentRating = null,
			coverUrl = cover,
			tags = item.optJSONArray("genres")?.let(::parseTags).orEmpty(),
			state = parseState(item.optString("status")),
			authors = setOfNotNull(item.optString("author").trim().ifEmpty { null }),
			source = source,
		)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val mangaUrl = manga.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(mangaUrl).parseHtml()
		val cover = doc.selectFirst("img.novel-cover")?.src() ?: manga.coverUrl
		val title = doc.selectFirst("h1.novel-title")?.text()?.trim().orEmpty()
		val altTitle = doc.selectFirst(".novel-original-title")?.text()?.trim()
		val tags = doc.select("a.genre-pill-modern-ios[href*='genre=']")
			.mapNotNullToSet { anchor ->
				val value = anchor.text().trim().takeIf(String::isNotEmpty)
					?: return@mapNotNullToSet null
				MangaTag(key = value, title = formatTagTitle(value), source = source)
			}
		val author = doc.select(".stat-col-ios").firstOrNull {
			it.selectFirst(".stat-lbl-ios")?.text()?.trim() == "المؤلف"
		}?.selectFirst(".stat-val-ios")?.text()?.trim()
		val status = doc.selectFirst(".novel-status-badge")?.text()?.trim()

		return manga.copy(
			title = title.ifEmpty { manga.title },
			altTitles = setOfNotNull(altTitle?.ifEmpty { null }).ifEmpty { manga.altTitles },
			description = doc.selectFirst(".novel-description-para")?.outerHtml() ?: manga.description,
			coverUrl = cover,
			largeCoverUrl = cover,
			tags = tags.ifEmpty { manga.tags },
			state = status?.let(::parseState) ?: manga.state,
			authors = setOfNotNull(author?.ifEmpty { null }).ifEmpty { manga.authors },
			chapters = loadAllChapters(manga.url),
		)
	}

	private suspend fun loadAllChapters(mangaPath: String): List<MangaChapter> {
		val slug = mangaPath.substringAfter("/novels/").substringBefore('/')
		if (slug.isEmpty()) return emptyList()
		val result = ArrayList<MangaChapter>()
		var offset = 0
		var received: Int
		var hasMore: Boolean
		do {
			val payload = webClient.httpGet(
				"https://$domain/api/novel/$slug/chapters" +
					"?offset=$offset&limit=$CHAPTER_PAGE_SIZE&sort=asc",
			).parseJson()
			val chapters = payload.optJSONArray("chapters") ?: break
			received = chapters.length()
			for (i in 0 until chapters.length()) {
				parseChapter(slug, chapters.optJSONObject(i))?.let(result::add)
			}
			offset = payload.optInt("offset", offset) + received
			val total = payload.optInt("total", result.size)
			hasMore = payload.optBoolean("hasMore", offset < total)
		} while (hasMore && received > 0)
		return result.distinctBy(MangaChapter::url).sortedBy(MangaChapter::number)
	}

	private fun parseChapter(slug: String, item: JSONObject?): MangaChapter? {
		item ?: return null
		val chapterId = item.optString("id").trim().takeIf(String::isNotEmpty) ?: return null
		val number = item.optString("chapter_number").toFloatOrNull()
			?: chapterId.toFloatOrNull()
			?: return null
		val href = "/novels/$slug/chapters/$chapterId"
		return MangaChapter(
			id = generateUid(href),
			title = item.optString("title").trim().ifEmpty { "الفصل ${formatNumber(number)}" },
			number = number,
			volume = item.optString("volume").toIntOrNull() ?: 0,
			url = href,
			scanlator = "بحر الروايات",
			uploadDate = parseIsoDate(item.optString("date")),
			branch = null,
			source = source,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> = emptyList()

	override suspend fun getChapterContent(chapter: MangaChapter): NovelChapterContent? {
		val chapterUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(chapterUrl).parseHtml()
		val content = doc.selectFirst("article.reader-content") ?: return null
		sanitizeChapterContent(content)
		if (content.text().isBlank() && content.selectFirst("img") == null) return null

		val title = doc.selectFirst("h1.reader-chapter-title")?.text()?.trim()
			?: chapter.title.orEmpty()
		if (title.isNotEmpty()) content.prependChild(Element("h1").text(title))

		return NovelChapterContent(
			html = content.html(),
			images = content.select("img").mapNotNull { image ->
				image.src()?.let { url ->
					NovelImage(
						url = url,
						headers = mapOf(
							"Referer" to chapterUrl,
							"User-Agent" to config[userAgentKey],
						),
					)
				}
			}.distinctBy(NovelImage::url),
		)
	}

	private fun parseTags(values: JSONArray): Set<MangaTag> = jsonStrings(values).mapTo(LinkedHashSet()) {
		MangaTag(key = it, title = formatTagTitle(it), source = source)
	}

	private fun parseState(value: String): MangaState? = when {
		value.equals("completed", ignoreCase = true) || "مكتمل" in value -> MangaState.FINISHED
		value.equals("ongoing", ignoreCase = true) || "مستمر" in value -> MangaState.ONGOING
		else -> null
	}

	private fun parseIsoDate(value: String): Long {
		if (value.isBlank()) return 0L
		val normalized = value.replace(
			Regex("""\.(\d{3})\d*(Z|[+-]\d{2}:?\d{2})$"""),
			".$1$2",
		)
		return synchronized(ISO_DATE_FORMAT) {
			runCatching { ISO_DATE_FORMAT.parse(normalized)?.time ?: 0L }.getOrDefault(0L)
		}
	}

	private fun formatNumber(number: Float): String =
		if (number % 1f == 0f) number.toInt().toString() else number.toString()

	internal companion object {
		const val PAGE_SIZE = 24
		const val CHAPTER_PAGE_SIZE = 100
		private val ISO_DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSX", Locale.US)

		internal fun sanitizeChapterContent(content: Element): Element {
			content.select(
				".sr-only, script, style, iframe, noscript, form, button, " +
					"[hidden], [aria-hidden=true], .adsbygoogle",
			).remove()
			content.select("img").forEach { image ->
				if (image.attr("src").isBlank()) {
					for (attribute in arrayOf("data-src", "data-lazy-src", "data-original")) {
						val value = image.attr(attribute).trim()
						.takeIf(String::isNotEmpty)
						?: continue
						image.attr("src", value)
						break
					}
				}
			}
			content.select("*").forEach { element ->
				element.removeAttr("class")
				.removeAttr("id")
				.removeAttr("style")
				.removeAttr("data-reader-initial-content")
			}
			content.select("p").forEach { paragraph ->
				if (paragraph.text().isBlank() && paragraph.selectFirst("img") == null) paragraph.remove()
			}
			return content
		}

		private fun jsonStrings(values: JSONArray): List<String> = buildList {
			for (i in 0 until values.length()) {
				values.optString(i).trim().takeIf(String::isNotEmpty)?.let(::add)
			}
		}

		private fun formatTagTitle(value: String): String =
			value.replaceFirstChar { character ->
				if (character.isLowerCase()) character.titlecase(Locale.ROOT) else character.toString()
			}
	}
}
