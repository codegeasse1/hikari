package org.hany18h.parsers.site.anime.ar

import okhttp3.Headers
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.hany18h.parsers.MangaLoaderContext
import org.hany18h.parsers.MangaSourceParser
import org.hany18h.parsers.config.ConfigKey
import org.hany18h.parsers.core.PagedMangaParser
import org.hany18h.parsers.model.AnimeStream
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
import org.hany18h.parsers.util.parseRaw
import org.hany18h.parsers.util.src
import org.hany18h.parsers.util.toAbsoluteUrl
import org.hany18h.parsers.util.urlEncoded
import java.util.EnumSet

@MangaSourceParser("ANIME3RB", "Anime3rb", "ar", ContentType.ANIME)
internal class Anime3rb(context: MangaLoaderContext) : PagedMangaParser(
	context = context,
	source = MangaParserSource.ANIME3RB,
	pageSize = PAGE_SIZE,
	searchPageSize = SEARCH_PAGE_SIZE,
) {

	override val configKeyDomain = ConfigKey.Domain("anime3rb.com")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.RATING,
		SortOrder.ALPHABETICAL,
		SortOrder.NEWEST,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(isSearchSupported = true)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
		keys.add(ConfigKey.InterceptCloudflare(defaultValue = true))
	}

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		val document = webClient.httpGet("https://$domain/titles/list").parseHtml()
		val tags = document.select("[href*='/genre/']").mapNotNullToSet { element ->
			parseTag(element)
		}
		return MangaListFilterOptions(availableTags = tags)
	}

	override suspend fun getListPage(
		page: Int,
		order: SortOrder,
		filter: MangaListFilter,
	): List<Manga> {
		val query = filter.query?.trim().orEmpty()
		if (query.isNotEmpty() && page > 1) return emptyList()

		val selectedTag = if (query.isEmpty()) filter.tags.firstOrNull() else null
		val path = selectedTag?.key?.takeIf { it.startsWith("/genre/") } ?: "/titles/list"
		val (sortBy, sortDirection) = when (order) {
			SortOrder.RATING -> "rate" to "desc"
			SortOrder.ALPHABETICAL -> "name" to "asc"
			SortOrder.NEWEST -> "release_date" to "desc"
			else -> "addition_date" to "desc"
		}
		val url = buildString {
			append("https://")
			append(domain)
			append(path)
			append("?page=")
			append(page)
			append("&sort_by=")
			append(sortBy)
			append("&sort_dir=")
			append(sortDirection)
			if (query.isNotEmpty()) {
				append("&q=")
				append(query.urlEncoded())
			}
		}
		val document = webClient.httpGet(url).parseHtml()
		val result = if (query.isNotEmpty()) {
			document.select(".search-results a.simple-title-card[href*='/titles/']")
				.mapNotNull(::parseSearchResult)
		} else {
			document.select(".title-card").mapNotNull(::parseTitleCard)
		}
		return result.distinctBy(Manga::id)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val document = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val heading = document.selectFirst("h1")
		val detailsContainer = heading?.parent()?.parent()
		val title = heading?.selectFirst("span")?.text()?.trim().orEmpty().ifEmpty { manga.title }
		val cover = document.selectFirst("meta[property=og:image]")?.attr("content")
			?.takeIf(String::isNotBlank) ?: manga.coverUrl
		val description = document.select("p.leading-loose.text-justify")
			.filter { it.text().isNotBlank() }
			.maxByOrNull { it.text().length }
			?.outerHtml()
			?: manga.description
		val altTitles = document.select("label").firstOrNull { label ->
			label.ownText().trim().startsWith("أسماء أخرى")
		}?.parent()?.select("h2")
			?.mapNotNullToSet { it.text().trim().takeIf(String::isNotEmpty) }
			.orEmpty()
		val tags = detailsContainer?.select("a[href*='/genre/']")
			?.mapNotNullToSet(::parseTag)
			.orEmpty()
		val authors = document.select("a[href*='/c/original-creator/']")
			.mapNotNullToSet { it.text().trim().takeIf(String::isNotEmpty) }
		val stateText = document.select("tr").firstOrNull { row ->
			row.selectFirst("td")?.text()?.trim()?.startsWith("الحالة") == true
		}?.select("td")?.getOrNull(1)?.text().orEmpty()
		val chapters = document.select(".video-list a[href*='/episode/']")
			.mapIndexedNotNull { index, element -> parseEpisode(element, index) }
			.distinctBy(MangaChapter::id)
			.sortedBy(MangaChapter::number)

		return manga.copy(
			title = title,
			altTitles = altTitles.ifEmpty { manga.altTitles },
			publicUrl = manga.url.toAbsoluteUrl(domain),
			coverUrl = cover,
			largeCoverUrl = cover,
			description = description,
			tags = tags.ifEmpty { manga.tags },
			state = parseState(stateText) ?: manga.state,
			authors = authors.ifEmpty { manga.authors },
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> = emptyList()

	override suspend fun getVideoStreams(chapter: MangaChapter): List<AnimeStream> {
		val episodeUrl = chapter.url.toAbsoluteUrl(domain)
		val document = webClient.httpGet(episodeUrl).parseHtml()
		val result = ArrayList<AnimeStream>()
		findDownloadSources(document).mapTo(result) { video ->
			AnimeStream(
				name = "Anime3rb • ${video.quality}",
				url = video.url,
				headers = mapOf(
					"Referer" to episodeUrl,
					"User-Agent" to config[userAgentKey],
				),
				quality = video.quality,
			)
		}

		for (playerUrl in findPlayerUrls(document)) {
			val playerHeaders = Headers.Builder()
				.add("Referer", episodeUrl)
				.add("User-Agent", config[userAgentKey])
				.build()
			val playerHtml = runCatching {
				webClient.httpGet(playerUrl, playerHeaders).parseRaw()
			}.getOrNull() ?: continue
			for (video in findVideoSources(playerHtml)) {
				result += AnimeStream(
					name = "Anime3rb • ${video.quality}",
					url = video.url,
					headers = mapOf(
						"Referer" to playerUrl,
						"User-Agent" to config[userAgentKey],
					),
					quality = video.quality,
				)
			}
		}
		return result.distinctBy(AnimeStream::url)
	}

	private fun parseTitleCard(element: Element): Manga? {
		val link = element.selectFirst("a[href*='/titles/']") ?: return null
		val relativeUrl = link.attrAsRelativeUrlOrNull("href") ?: return null
		if (relativeUrl == "/titles/list") return null
		val title = element.selectFirst(".title-name, .details h2")?.text()?.trim()
			?.takeIf(String::isNotEmpty) ?: return null
		val tags = element.select(".genres [href*='/genre/']").mapNotNullToSet(::parseTag)
		return createAnime(
			title = title,
			altTitles = emptySet(),
			relativeUrl = relativeUrl,
			cover = element.selectFirst("img")?.src(),
			tags = tags,
			rating = parseRating(element),
			description = element.selectFirst(".synopsis")?.text()?.trim(),
		)
	}

	private fun parseSearchResult(element: Element): Manga? {
		val relativeUrl = element.attrAsRelativeUrlOrNull("href") ?: return null
		val title = element.selectFirst("h4")?.text()?.trim()?.takeIf(String::isNotEmpty) ?: return null
		val altTitle = element.selectFirst("h5")?.text()?.trim()?.takeIf(String::isNotEmpty)
		return createAnime(
			title = title,
			altTitles = setOfNotNull(altTitle),
			relativeUrl = relativeUrl,
			cover = element.selectFirst("img")?.src(),
			tags = emptySet(),
			rating = parseRating(element),
			description = null,
		)
	}

	private fun createAnime(
		title: String,
		altTitles: Set<String>,
		relativeUrl: String,
		cover: String?,
		tags: Set<MangaTag>,
		rating: Float,
		description: String?,
	): Manga = Manga(
		id = generateUid(relativeUrl),
		title = title,
		altTitles = altTitles,
		url = relativeUrl,
		publicUrl = relativeUrl.toAbsoluteUrl(domain),
		rating = rating,
		contentRating = if (tags.any { it.key.endsWith("/erotica") }) {
			ContentRating.ADULT
		} else {
			ContentRating.SAFE
		},
		coverUrl = cover,
		tags = tags,
		state = null,
		authors = emptySet(),
		description = description,
		source = source,
	)

	private fun parseEpisode(element: Element, index: Int): MangaChapter? {
		val relativeUrl = element.attrAsRelativeUrlOrNull("href") ?: return null
		val numberText = element.selectFirst(".video-data span")?.text().orEmpty()
		val number = NUMBER.find(numberText)?.value?.toFloatOrNull()
			?: NUMBER.find(relativeUrl.substringAfterLast('/'))?.value?.toFloatOrNull()
			?: (index + 1).toFloat()
		val episodeName = element.selectFirst(".video-data p")?.text()?.trim().orEmpty()
		val title = buildString {
			append("الحلقة ")
			append(formatNumber(number))
			if (episodeName.isNotEmpty()) {
				append(" — ")
				append(episodeName)
			}
		}
		return MangaChapter(
			id = generateUid(relativeUrl),
			title = title,
			number = number,
			volume = 0,
			url = relativeUrl,
			scanlator = "Anime3rb",
			uploadDate = 0L,
			branch = null,
			source = source,
		)
	}

	private fun parseTag(element: Element): MangaTag? {
		val href = element.attr("href").trim()
		val slug = href.substringAfter("/genre/", missingDelimiterValue = "")
			.substringBefore('?')
			.substringBefore('#')
			.trim('/')
			.takeIf(String::isNotEmpty) ?: return null
		val title = element.text().trim().takeIf(String::isNotEmpty) ?: return null
		return MangaTag(title = title, key = "/genre/$slug", source = source)
	}

	private fun parseRating(element: Element): Float {
		val badge = element.select(".badge").firstOrNull { item ->
			item.selectFirst(".sr-only")?.text()?.contains("التقييم") == true
		} ?: return RATING_UNKNOWN
		val value = NUMBER.findAll(badge.text()).lastOrNull()?.value?.toFloatOrNull() ?: return RATING_UNKNOWN
		return (value / 10f).coerceIn(0f, 1f)
	}

	private fun parseState(value: String): MangaState? = when {
		value.contains("منتهي") || value.contains("مكتمل") -> MangaState.FINISHED
		value.contains("قادم") -> MangaState.UPCOMING
		value.contains("مستمر") || value.contains("يعرض") -> MangaState.ONGOING
		else -> null
	}

	private fun formatNumber(value: Float): String =
		if (value % 1f == 0f) value.toInt().toString() else value.toString()

	internal companion object {
		const val PAGE_SIZE = 20
		const val SEARCH_PAGE_SIZE = 12
		val NUMBER = Regex("""\d+(?:\.\d+)?""")

		fun findPlayerUrls(document: Document): List<String> = buildList {
			document.getElementsByAttribute("wire:snapshot").mapNotNullTo(this) { element ->
				runCatching {
					JSONObject(element.attr("wire:snapshot"))
						.optJSONObject("data")
						?.optString("video_url")
						?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
				}.getOrNull()
			}
			for (element in document.select("iframe[src], a[href*=/player/], [data-url], [data-src], [onclick]")) {
				for (attribute in arrayOf("src", "href", "data-url", "data-src")) {
					val raw = element.attr(attribute).trim()
					if (raw.isEmpty()) continue
					val absolute = element.absUrl(attribute).ifEmpty {
						raw.takeIf { it.startsWith("http://") || it.startsWith("https://") }.orEmpty()
					}
					if (absolute.isNotEmpty() && ("/player/" in absolute || "vid3rb" in absolute)) add(absolute)
				}
				ATTRIBUTE_URL.findAll(element.attr("onclick")).forEach { match ->
					decodeAttributeUrl(match.value)?.let(::add)
				}
			}
		}.distinct()

		fun findDownloadSources(document: Document): List<ParsedVideoSource> =
			document.select("a[href*=/download/]").mapNotNull { anchor ->
				val url = anchor.absUrl("href").ifEmpty { anchor.attr("href") }
					.takeIf { it.startsWith("http://") || it.startsWith("https://") }
					?: return@mapNotNull null
				val context = listOf(anchor.text(), anchor.parent()?.text()).joinToString(" ")
				val quality = QUALITY.find(context)?.groupValues?.getOrNull(1)?.let { "${it}p" }
					?: if (context.contains("HEVC", true)) "HEVC" else "Auto"
				ParsedVideoSource(url = url, quality = quality)
			}.distinctBy(ParsedVideoSource::url)

		fun findVideoSources(raw: String): List<ParsedVideoSource> {
			val array = findAssignedJsonArrays(raw, "video_sources")
				.asSequence()
				.mapNotNull { runCatching { JSONArray(it) }.getOrNull() }
				.firstOrNull { it.length() > 0 }
				?: return emptyList()
			return buildList {
				for (index in 0 until array.length()) {
					val item = array.optJSONObject(index) ?: continue
					if (item.optBoolean("premium", false)) continue
					val url = item.optString("src").takeIf {
						it.startsWith("https://") || it.startsWith("http://")
					} ?: continue
					val quality = item.optString("label")
						.takeIf(String::isNotBlank)
						?: item.optString("res").takeIf(String::isNotBlank)?.let { "${it}p" }
						?: "Auto"
					add(ParsedVideoSource(url = url, quality = quality))
				}
			}.distinctBy(ParsedVideoSource::url)
		}

		private fun findAssignedJsonArrays(raw: String, variable: String): List<String> = buildList {
			var searchFrom = 0
			while (searchFrom < raw.length) {
				val variableIndex = raw.indexOf(variable, searchFrom)
				if (variableIndex < 0) break
				searchFrom = variableIndex + variable.length
				if (variableIndex > 0 && raw[variableIndex - 1].isJavaIdentifierPart()) continue
				if (searchFrom < raw.length && raw[searchFrom].isJavaIdentifierPart()) continue

				var assignmentIndex = searchFrom
				while (assignmentIndex < raw.length && raw[assignmentIndex].isWhitespace()) assignmentIndex++
				if (raw.getOrNull(assignmentIndex) != '=') continue
				var arrayStart = assignmentIndex + 1
				while (arrayStart < raw.length && raw[arrayStart].isWhitespace()) arrayStart++
				if (raw.getOrNull(arrayStart) != '[') continue

				var depth = 0
				var isInString = false
				var isEscaped = false
				for (index in arrayStart until raw.length) {
					val character = raw[index]
					if (isInString) {
						when {
							isEscaped -> isEscaped = false
							character == '\\' -> isEscaped = true
							character == '"' -> isInString = false
						}
						continue
					}
					when (character) {
						'"' -> isInString = true
						'[' -> depth++
						']' -> {
							depth--
							if (depth == 0) {
								add(raw.substring(arrayStart, index + 1))
								searchFrom = index + 1
								break
							}
						}
					}
				}
			}
		}

		private fun decodeAttributeUrl(value: String): String? {
			val normalized = value
				.replace("\\/", "/")
				.replace("\\u0026", "&")
				.replace("&amp;", "&")
			return normalized.takeIf { it.startsWith("http://") || it.startsWith("https://") }
		}

		private val ATTRIBUTE_URL = Regex("""https?:\\?/\\?/[^'"\s)]+""", RegexOption.IGNORE_CASE)
		private val QUALITY = Regex("""(?:^|\D)(360|480|720|1080|1440|2160)\s*p?(?:\D|$)""", RegexOption.IGNORE_CASE)
	}
}

internal data class ParsedVideoSource(
	val url: String,
	val quality: String,
)
