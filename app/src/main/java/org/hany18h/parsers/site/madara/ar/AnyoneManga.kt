package org.hany18h.parsers.site.madara.ar

import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.hany18h.parsers.MangaLoaderContext
import org.hany18h.parsers.MangaSourceParser
import org.hany18h.parsers.config.ConfigKey
import org.hany18h.parsers.exception.ParseException
import org.hany18h.parsers.model.Manga
import org.hany18h.parsers.model.MangaChapter
import org.hany18h.parsers.model.MangaListFilter
import org.hany18h.parsers.model.MangaPage
import org.hany18h.parsers.model.MangaParserSource
import org.hany18h.parsers.model.RATING_UNKNOWN
import org.hany18h.parsers.model.SortOrder
import org.hany18h.parsers.site.madara.MadaraParser
import org.hany18h.parsers.util.attrAsRelativeUrlOrNull
import org.hany18h.parsers.util.generateUid
import org.hany18h.parsers.util.parseHtml
import org.hany18h.parsers.util.src
import org.hany18h.parsers.util.toAbsoluteUrl
import java.net.URI

@MangaSourceParser("ANYONEMANGA", "AnyoneManga", "ar")
internal class AnyoneManga(context: MangaLoaderContext) :
	MadaraParser(
		context,
		MangaParserSource.valueOf("ANYONEMANGA"),
		"anyonemanga.com",
		pageSize = 20,
	), Interceptor {

	override val datePattern = "d MMMM، yyyy"
	override val stylePage = ""
	override val withoutAjax = true

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(ConfigKey.InterceptCloudflare(defaultValue = true))
	}

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		return chain.proceed(
			request.newBuilder()
				.header("Referer", "https://$domain/")
				.header("Origin", "https://$domain")
				.header("Accept-Language", "ar,en-US;q=0.8,en;q=0.7")
				.header("User-Agent", config[userAgentKey])
				.build(),
		)
	}

	override suspend fun getListPage(
		page: Int,
		order: SortOrder,
		filter: MangaListFilter,
	): List<Manga> {
		// The old Madara archive endpoint is now protected by a bot challenge.
		// The public home page contains the complete, small catalogue.
		if (page > 1) return emptyList()
		val catalogue = parseMangaList(
			webClient.httpGet("https://$domain/").parseHtml(),
		)
		val query = filter.query?.trim()
		val filtered = if (query.isNullOrEmpty()) {
			catalogue
		} else {
			catalogue.filter { manga ->
				manga.title.contains(query, ignoreCase = true) ||
					manga.altTitles.any { it.contains(query, ignoreCase = true) }
			}
		}
		return when (order) {
			SortOrder.ALPHABETICAL -> filtered.sortedBy { it.title.lowercase() }
			SortOrder.ALPHABETICAL_DESC -> filtered.sortedByDescending { it.title.lowercase() }
			SortOrder.RATING -> filtered.sortedByDescending(Manga::rating)
			SortOrder.RATING_ASC -> filtered.sortedBy(Manga::rating)
			else -> filtered
		}
	}

	override fun parseMangaList(doc: Document): List<Manga> {
		val cards = doc.select("div.am-manga-card")
		if (cards.isEmpty()) return super.parseMangaList(doc)
		return parseAnyoneCatalogue(doc, domain, source, ::generateUid)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapterUrl = chapter.url.toAbsoluteUrl(domain)
		val document = webClient.httpGet(chapterUrl).parseHtml()
		val urls = extractAnyoneChapterImageUrls(document, chapterUrl)
		if (urls.isEmpty()) throw ParseException("Image src not found", chapterUrl)
		return urls.mapIndexed { index, url ->
			MangaPage(
				id = generateUid("${chapter.id}:$index:$url"),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	internal companion object {

		internal fun parseAnyoneCatalogue(
			document: Document,
			domain: String,
			source: MangaParserSource,
			idFactory: (String) -> Long = { it.hashCode().toLong() },
		): List<Manga> = document.select("div.am-manga-card").mapNotNull { card ->
			val anchor = card.selectFirst(".am-manga-card__title a[href]")
				?: card.selectFirst(".am-manga-card__thumb a[href]")
				?: return@mapNotNull null
			val href = anchor.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
			val title = anchor.text().trim()
				.ifEmpty { anchor.attr("title").trim() }
				.ifEmpty { return@mapNotNull null }
			val rating = card.selectFirst(".am-manga-card__rating .score, span.total_votes")
				?.text()
				?.trim()
				?.toFloatOrNull()
				?.div(5f)
				?.coerceIn(0f, 1f)
				?: RATING_UNKNOWN
			Manga(
				id = idFactory(href),
				title = title,
				altTitles = emptySet(),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				rating = rating,
				contentRating = null,
				coverUrl = card.selectFirst(".am-manga-card__thumb img, img")?.src(),
				tags = emptySet(),
				state = null,
				authors = card.select(".am-manga-card__author a")
					.mapNotNullTo(LinkedHashSet()) { it.text().trim().ifEmpty { null } },
				source = source,
			)
		}.distinctBy(Manga::id)

		internal fun resolveAnyoneImageUrl(image: Element, baseUrl: String): String? {
			val raw = sequenceOf(
				"data-src",
				"data-lazy-src",
				"data-original",
				"data-url",
				"data-cfsrc",
				"src",
				"data-srcset",
				"srcset",
			).map { image.attr(it).trim() }.firstOrNull { value ->
				value.isNotEmpty() &&
					value != "#" &&
					!value.startsWith("data:", ignoreCase = true) &&
					!value.contains("placeholder", ignoreCase = true)
			}?.substringBefore(',')
				?.trim()
				?.substringBefore(' ')
				?.trim()
				?.takeIf(String::isNotEmpty)
				?: return null
			return runCatching {
				when {
					raw.startsWith("//") -> "https:$raw"
					raw.startsWith("http://") || raw.startsWith("https://") -> raw
					else -> URI(baseUrl).resolve(raw).toString()
				}
			}.getOrDefault(raw)
		}

		internal fun extractAnyoneChapterImageUrls(
			document: Document,
			chapterUrl: String,
		): List<String> {
			// AnyoneManga's custom theme now uses am-reading-content instead of
			// Madara's traditional reading-content wrapper.
			val root = document.selectFirst(
				".am-reading-content, .am-reading-wrap .reading-content, " +
					".reading-content.current, .main-col-inner .reading-content, " +
					".read-container .reading-content, .reading-content",
			) ?: return emptyList()
			return root.select(
				"div.page-break img, img.wp-manga-chapter-img, img[data-src], " +
					"img[data-lazy-src], img[data-original], img[data-url], " +
					"img[data-cfsrc], img[data-srcset], img[srcset], img[src]",
			).mapNotNull { image ->
				resolveAnyoneImageUrl(image, chapterUrl)
			}.distinct()
		}
	}
}
