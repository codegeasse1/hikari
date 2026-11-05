package org.hany18h.parsers.site.anime.ar

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
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
import org.hany18h.parsers.util.attrAsAbsoluteUrlOrNull
import org.hany18h.parsers.util.generateUid
import org.hany18h.parsers.util.parseHtml
import org.hany18h.parsers.util.parseRaw
import org.hany18h.parsers.util.toAbsoluteUrl
import org.hany18h.parsers.util.toRelativeUrl
import org.hany18h.parsers.util.urlEncoded
import java.net.URI
import java.util.EnumSet

@MangaSourceParser("ANIME_RISTO", "RistoAnime", "ar", ContentType.ANIME)
internal class RistoAnime(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.ANIME_RISTO, pageSize = PAGE_SIZE, searchPageSize = PAGE_SIZE) {

	override val configKeyDomain = ConfigKey.Domain("ristoanime.me")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.POPULARITY,
		SortOrder.UPDATED,
	)

	override val filterCapabilities = MangaListFilterCapabilities(isSearchSupported = true)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override suspend fun getFilterOptions() = MangaListFilterOptions()

	override suspend fun getListPage(
		page: Int,
		order: SortOrder,
		filter: MangaListFilter,
	): List<Manga> {
		val query = filter.query?.trim().orEmpty()
		val document = if (query.isNotEmpty()) {
			if (page > 1) return emptyList()
			webClient.httpPost(
				url = "https://$domain/wp-content/themes/TopAnime/Ajaxt/Searching.php".toHttpUrl(),
				form = mapOf("search" to query.urlEncoded()),
				extraHeaders = Headers.Builder()
					.add("X-Requested-With", "XMLHttpRequest")
					.add("Referer", "https://$domain/")
					.build(),
			).parseHtml()
		} else {
			val suffix = if (page == 1) "" else "?offset=$page"
			webClient.httpGet("https://$domain/series/$suffix").parseHtml()
		}

		val selector = if (query.isEmpty()) ".BlocksHolder .MovieItem" else ".SearchResultInner"
		return document.select(selector)
			.mapNotNull { if (query.isEmpty()) parseSeriesCard(it) else parseSearchResult(it) }
			.distinctBy(Manga::id)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val document = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val title = document.selectFirst("h1.PostTitle")?.text()?.trim().orEmpty().ifEmpty { manga.title }
		val cover = document.selectFirst(".InnerPoster img")?.attrAsAbsoluteUrlOrNull("src")
			?: document.selectFirst(".singleCover .BG")?.let(::backgroundImageUrl)
			?: manga.coverUrl
		val description = document.selectFirst(".StoryArea p")?.text()?.trim()
		val tags = document.select(".TaxContent a[href*='/genre/']")
			.mapNotNullTo(LinkedHashSet()) { element ->
				val name = element.text().trim().takeIf(String::isNotEmpty) ?: return@mapNotNullTo null
				MangaTag(title = name, key = element.attr("href"), source = source)
			}
		val statusText = document.select(".TaxContent li")
			.firstOrNull { it.selectFirst("span")?.text()?.contains("الحالة") == true }
			?.text().orEmpty()
		val chapters = document.select(".EpisodesList > a[href]")
			.mapIndexedNotNull { index, element -> parseEpisode(element, index) }
			.distinctBy(MangaChapter::id)
			.sortedBy(MangaChapter::number)

		return manga.copy(
			title = title,
			publicUrl = manga.url.toAbsoluteUrl(domain),
			coverUrl = cover,
			largeCoverUrl = cover,
			description = description,
			tags = tags.ifEmpty { manga.tags },
			state = when {
				statusText.contains("مكتمل") || statusText.contains("finished", true) -> MangaState.FINISHED
				statusText.contains("قادم") || statusText.contains("upcoming", true) -> MangaState.UPCOMING
				else -> MangaState.ONGOING
			},
			rating = parseRating(document.selectFirst(".imdbRBox span")?.text()) ?: manga.rating,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> = emptyList()

	override suspend fun getVideoStreams(chapter: MangaChapter): List<AnimeStream> {
		val episodeUrl = chapter.url.toAbsoluteUrl(domain).trimEnd('/')
		val watchUrl = "$episodeUrl/watch"
		val servers = webClient.httpGet(watchUrl).parseHtml()
			.select("#WatchList li[data-watch]")
			.mapNotNull { element ->
				val url = resolveUrl(element.baseUri(), element.attr("data-watch")) ?: return@mapNotNull null
				Server(
					name = element.text().replace(LEADING_SERVER_INDEX, "").trim().ifEmpty { hostName(url) },
					url = url,
				)
			}
			.sortedBy { serverPriority(it.url) }

		val result = ArrayList<AnimeStream>()
		for (server in servers.take(MAX_SERVER_PROBES)) {
			val directUrls = runCatching { extractDirectMediaUrls(server.url) }.getOrDefault(emptyList())
			for ((index, directUrl) in directUrls.withIndex()) {
				result += AnimeStream(
					name = if (directUrls.size == 1) server.name else "${server.name} ${index + 1}",
					url = directUrl,
					headers = mapOf(
						"Referer" to server.url,
						"User-Agent" to config[userAgentKey],
					),
				)
			}
			// Resolving every embed page is expensive and was the reason this source
			// kept phones hot even before playback started. The servers are already
			// priority-sorted, so stop after the first working provider.
			if (result.isNotEmpty()) break
		}
		return result.distinctBy(AnimeStream::url)
	}

	private fun parseSeriesCard(element: Element): Manga? {
		val link = element.selectFirst("a[href]") ?: return null
		val url = link.attrAsAbsoluteUrlOrNull("href") ?: return null
		if (!url.contains("/series/")) return null
		val title = element.selectFirst(".title h4")?.text()?.trim()
			.orEmpty().ifEmpty { link.attr("title").trim() }
		if (title.isEmpty()) return null
		val tags = element.select(".genre").mapNotNullTo(LinkedHashSet()) { genre ->
			val name = genre.text().trim().takeIf(String::isNotEmpty) ?: return@mapNotNullTo null
			MangaTag(title = name, key = name, source = source)
		}
		return createAnime(
			title = title,
			url = url,
			cover = element.selectFirst(".poster")?.let(::backgroundImageUrl),
			tags = tags,
			rating = RATING_UNKNOWN,
		)
	}

	private fun parseSearchResult(element: Element): Manga? {
		val link = element.selectFirst("h1 a[href]") ?: return null
		val url = link.attrAsAbsoluteUrlOrNull("href") ?: return null
		val container = element.closest("li") ?: element.parent()
		return createAnime(
			title = link.text().trim().takeIf(String::isNotEmpty) ?: return null,
			url = url,
			cover = container?.selectFirst(".SearchThumb img")?.attrAsAbsoluteUrlOrNull("src"),
			tags = emptySet(),
			rating = RATING_UNKNOWN,
		)
	}

	private fun createAnime(
		title: String,
		url: String,
		cover: String?,
		tags: Set<MangaTag>,
		rating: Float,
	): Manga {
		val relativeUrl = url.toRelativeUrl(domain)
		return Manga(
			id = generateUid(relativeUrl),
			title = title,
			altTitles = emptySet(),
			url = relativeUrl,
			publicUrl = url,
			rating = rating,
			contentRating = ContentRating.SAFE,
			coverUrl = cover,
			tags = tags,
			state = null,
			authors = emptySet(),
			source = source,
		)
	}

	private fun parseEpisode(element: Element, index: Int): MangaChapter? {
		val absoluteUrl = element.attrAsAbsoluteUrlOrNull("href") ?: return null
		val relativeUrl = absoluteUrl.toRelativeUrl(domain)
		val number = element.selectFirst("em")?.text()?.let(::firstNumber)
			?: firstNumber(element.text())
			?: (index + 1).toFloat()
		return MangaChapter(
			id = generateUid(relativeUrl),
			title = "الحلقة ${formatEpisodeNumber(number)}",
			number = number,
			volume = 0,
			url = relativeUrl,
			scanlator = "RistoAnime",
			uploadDate = 0L,
			branch = null,
			source = source,
		)
	}

	private suspend fun extractDirectMediaUrls(embedUrl: String): List<String> {
		if (embedUrl.endsWith(".m3u8", true) || embedUrl.substringBefore('?').endsWith(".mp4", true)) {
			return listOf(embedUrl)
		}
		val headers = Headers.Builder()
			.add("Referer", "https://$domain/")
			.add("User-Agent", config[userAgentKey])
			.build()
		val raw = webClient.httpGet(embedUrl, headers).parseRaw()
		val decoded = Parser.unescapeEntities(raw, false)
			.replace("\\/", "/")
			.replace("\\u0026", "&", ignoreCase = true)
		return findDirectMediaUrls(decoded)
	}

	private fun backgroundImageUrl(element: Element): String? {
		val style = element.attr("data-style").ifEmpty { element.attr("style") }
		return resolveUrl(element.baseUri(), BACKGROUND_URL.find(style)?.groupValues?.getOrNull(2).orEmpty())
	}

	private fun resolveUrl(baseUrl: String, value: String): String? {
		if (value.isBlank()) return null
		return runCatching {
			val normalized = if (value.startsWith("//")) "https:$value" else value.trim()
			URI(baseUrl).resolve(normalized).toString()
		}.getOrNull()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
	}

	private fun parseRating(text: String?): Float? {
		val parts = text?.let { RATING_NUMBER.findAll(it).map { match -> match.value.toFloat() }.toList() }
			.orEmpty()
		if (parts.isEmpty()) return null
		val score = if (parts.size >= 2 && parts[0] == 10f) parts[1] else parts[0]
		return (score / 10f).coerceIn(0f, 1f)
	}

	private fun firstNumber(text: String): Float? = RATING_NUMBER.find(text)?.value?.toFloatOrNull()

	private fun serverPriority(url: String): Int = when {
		"vidmoly" in url -> 0
		"sendvid" in url -> 1
		"sibnet" in url -> 2
		else -> 10
	}

	private fun hostName(url: String): String = runCatching {
		URI(url).host?.removePrefix("www.").orEmpty()
	}.getOrDefault("").ifEmpty { "Server" }

	private fun formatEpisodeNumber(number: Float): String =
		if (number % 1f == 0f) number.toInt().toString() else number.toString()

	private data class Server(val name: String, val url: String)

	internal companion object {
		const val PAGE_SIZE = 25
		const val MAX_STREAMS = 8
		const val MAX_SERVER_PROBES = 3
		const val MAX_URLS_PER_SERVER = 3
		val BACKGROUND_URL = Regex("""url\((['\"]?)(.*?)\1\)""", RegexOption.IGNORE_CASE)
		val RATING_NUMBER = Regex("""\d+(?:\.\d+)?""")
		val LEADING_SERVER_INDEX = Regex("""^\s*\d+(?:\.\d+)?\s*""")
		val DIRECT_MEDIA_URL = Regex(
			"""https?://[^\s\"'<>]+?\.(?:m3u8|mp4)(?:\?[^\s\"'<>]*)?""",
			RegexOption.IGNORE_CASE,
		)

		fun findDirectMediaUrls(raw: String): List<String> = DIRECT_MEDIA_URL.findAll(raw)
			.map { it.value.trimEnd('\\', '"', '\'', ')', ']') }
			.filter { it.startsWith("https://") || it.startsWith("http://") }
			.distinct()
			.take(MAX_URLS_PER_SERVER)
			.toList()
	}
}
