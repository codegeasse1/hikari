package org.hany18h.parsers.site.ar

import org.json.JSONArray
import org.jsoup.nodes.Document
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
import org.hany18h.parsers.model.RATING_UNKNOWN
import org.hany18h.parsers.model.SortOrder
import org.hany18h.parsers.util.attrAsRelativeUrlOrNull
import org.hany18h.parsers.util.generateUid
import org.hany18h.parsers.util.mapNotNullToSet
import org.hany18h.parsers.util.parseHtml
import org.hany18h.parsers.util.src
import org.hany18h.parsers.util.toAbsoluteUrl
import org.hany18h.parsers.util.urlEncoded
import java.util.EnumSet

@MangaSourceParser("MANGADAR", "MangaDar", "ar", ContentType.MANGA)
internal class MangaDar(context: MangaLoaderContext) : PagedMangaParser(
	context = context,
	source = MangaParserSource.MANGADAR,
	pageSize = 24,
) {

	override val configKeyDomain = ConfigKey.Domain("mangadar.com")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.RATING,
		SortOrder.ALPHABETICAL,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(isSearchSupported = true)

	override suspend fun getFilterOptions() = MangaListFilterOptions()

	override suspend fun getListPage(
		page: Int,
		order: SortOrder,
		filter: MangaListFilter,
	): List<Manga> {
		val url = buildString {
			append("https://")
			append(domain)
			append(if (page > 1) "/manga/page/$page/" else "/manga/")
			val parameters = ArrayList<String>(2)
			filter.query?.trim()?.takeIf(String::isNotEmpty)?.let { query ->
				parameters += "s=${query.urlEncoded()}"
			}
			when (order) {
				SortOrder.POPULARITY -> parameters += "sort=popular"
				SortOrder.RATING -> parameters += "sort=rating"
				SortOrder.ALPHABETICAL -> parameters += "sort=az"
				else -> Unit
			}
			if (parameters.isNotEmpty()) append(parameters.joinToString(prefix = "?", separator = "&"))
		}
		val doc = webClient.httpGet(url).parseHtml()
		return doc.select("main a[href*=/manga/]:has(h3):has(img)").mapNotNull { anchor ->
			val href = anchor.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
			if (!MANGA_PATH.matches(href)) return@mapNotNull null
			val title = anchor.selectFirst("h3")?.text()?.trim().orEmpty()
			if (title.isEmpty()) return@mapNotNull null
			Manga(
				id = generateUid(href),
				title = title,
				altTitles = emptySet(),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = ContentRating.SAFE,
				coverUrl = anchor.selectFirst("img")?.src(),
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source,
			)
		}.distinctBy(Manga::url)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val title = doc.selectFirst("main h1")?.text()?.trim().orEmpty().ifEmpty { manga.title }
		val cover = doc.selectFirst("main img[src*=/wp-content/uploads/]")?.src() ?: manga.coverUrl
		val description = doc.select("main h2").firstOrNull { it.text().contains("ملخص القصة") }
			?.parent()?.selectFirst("p")?.html()
		val tags = doc.select("main a[href*=/genre/]").mapNotNullToSet { anchor ->
			val tagTitle = anchor.text().trim()
			if (tagTitle.isEmpty()) return@mapNotNullToSet null
			MangaTag(key = anchor.attr("href"), title = tagTitle, source = source)
		}
		val authors = metadata(doc, "المؤلف")?.split(',', '،')
			?.map(String::trim)?.filter(String::isNotEmpty)?.toSet().orEmpty()
		return manga.copy(
			title = title,
			description = description,
			coverUrl = cover,
			largeCoverUrl = cover,
			state = parseState(metadata(doc, "الحالة")),
			tags = tags,
			authors = authors,
			chapters = parseChapters(doc),
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		val json = doc.selectFirst("script[type=application/json][id^=mv-pages-]")?.data()?.trim()
		val urls = if (!json.isNullOrEmpty()) {
			runCatching {
				val array = JSONArray(json)
				List(array.length()) { index -> array.optString(index) }.filter(String::isNotEmpty)
			}.getOrDefault(emptyList())
		} else {
			emptyList()
		}
		val resolved = urls.ifEmpty {
			doc.select(".reader-page img").mapNotNull { it.src() }
		}
		return resolved.distinct().map { imageUrl ->
			MangaPage(
				id = generateUid(imageUrl),
				url = imageUrl,
				preview = null,
				source = source,
			)
		}
	}

	private fun parseChapters(doc: Document): List<MangaChapter> {
		val data = doc.select("[x-data]").firstNotNullOfOrNull { element ->
			ROWS_PATTERN.find(element.attr("x-data"))?.groupValues?.get(1)
		} ?: return emptyList()
		val rows = runCatching { JSONArray(data) }.getOrElse { return emptyList() }
		return List(rows.length()) { index -> rows.optJSONArray(index) }.mapNotNull { row ->
			row ?: return@mapNotNull null
			val absoluteUrl = row.optString(2)
			if (absoluteUrl.isEmpty()) return@mapNotNull null
			val href = absoluteUrl.substringAfter(domain).let { if (it.startsWith('/')) it else "/$it" }
			val numberText = row.optString(1)
			MangaChapter(
				id = generateUid(href),
				title = "الفصل $numberText",
				number = row.optDouble(4, numberText.toDoubleOrNull() ?: 0.0).toFloat(),
				volume = 0,
				url = href,
				scanlator = null,
				uploadDate = row.optLong(3) * 1000L,
				branch = null,
				source = source,
			)
		}.distinctBy(MangaChapter::url).sortedBy(MangaChapter::number)
	}

	private fun metadata(doc: Document, label: String): String? {
		return doc.select("main div.flex").firstNotNullOfOrNull { row ->
			val values = row.select("span")
			if (values.firstOrNull()?.text()?.trim() == label) values.lastOrNull()?.text()?.trim() else null
		}
	}

	private fun parseState(value: String?): MangaState? = when {
		value == null -> null
		value.contains("مستمر") -> MangaState.ONGOING
		value.contains("مكتمل") -> MangaState.FINISHED
		value.contains("متوقف") -> MangaState.PAUSED
		else -> null
	}

	private companion object {
		val MANGA_PATH = Regex("^/manga/[^/]+/?$")
		// The chapter array is followed by the user's read-state array. It used to be
		// followed by `cover`, so anchoring to that field made every title look empty.
		val ROWS_PATTERN = Regex("rows:\\s*(\\[\\[.*?]])\\s*,\\s*read:", RegexOption.DOT_MATCHES_ALL)
	}
}
