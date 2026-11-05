package org.hany18h.parsers.site.ar

import okhttp3.CacheControl
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.HttpStatusException
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
import org.hany18h.parsers.model.MangaSource
import org.hany18h.parsers.model.MangaState
import org.hany18h.parsers.model.MangaTag
import org.hany18h.parsers.model.RATING_UNKNOWN
import org.hany18h.parsers.model.SortOrder
import org.hany18h.parsers.util.await
import org.hany18h.parsers.util.generateUid
import org.hany18h.parsers.util.requireBody
import java.util.EnumSet
import kotlin.random.Random

/**
 * Books exposed by the official Al Hilal Library Android application.
 *
 * The service returns complete books as PDF files. The chapter/page URLs use
 * fragments understood by Manga Peak's generic PDF page renderer; HTTP never
 * receives those fragments.
 */
@MangaSourceParser("ALHILALLIBRARY", "مكتبة الهلال", "ar", ContentType.NOVEL)
internal class AlHilalLibrary(private val loaderContext: MangaLoaderContext) : PagedMangaParser(
	context = loaderContext,
	source = MangaParserSource.ALHILALLIBRARY,
	pageSize = PAGE_SIZE,
) {

	override val configKeyDomain = ConfigKey.Domain(API_DOMAIN)

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.NEWEST,
		SortOrder.POPULARITY,
		SortOrder.RATING,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(isSearchSupported = true)

	override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions()

	override suspend fun getListPage(
		page: Int,
		order: SortOrder,
		filter: MangaListFilter,
	): List<Manga> {
		val query = filter.query?.trim().orEmpty()
		val response = if (query.isNotEmpty()) {
			apiRequest(
				endpoint = "searchBooks.php",
				body = JSONObject().put("query", query).put("page", page),
			)
		} else {
			val (category, reference) = when (order) {
				SortOrder.POPULARITY -> "topDownloaded" to 3
				SortOrder.RATING -> "topRated" to 2
				else -> "recent" to 1
			}
			apiRequest(
				endpoint = "getAllBooks.php",
				body = JSONObject()
					.put("isCat", false)
					.put("booksCatRef", reference)
					.put("booksCat", category)
					.put("page", page),
			)
		}
		return parseBookList(JSONArray(response))
	}

	internal fun parseBookList(array: JSONArray): List<Manga> = buildList {
		for (index in 0 until array.length()) {
			val item = array.optJSONObject(index) ?: continue
			parseBook(item)?.let(::add)
		}
	}.distinctBy(Manga::id)

	private fun parseBook(item: JSONObject): Manga? {
		val bookId = item.optInt("id").takeIf { it > 0 } ?: return null
		val title = item.optString("title").trim().takeIf(String::isNotEmpty) ?: return null
		val author = item.optString("author").trim().takeIf(String::isNotEmpty)
		val category = item.optString("category").trim().takeIf(String::isNotEmpty)
		val rating = item.optDouble("rating", Double.NaN).takeUnless(Double::isNaN)
			?.div(5.0)?.toFloat()?.coerceIn(0f, 1f) ?: RATING_UNKNOWN
		return Manga(
			id = generateUid("alhilal:$bookId"),
			title = title,
			altTitles = emptySet(),
			url = "/book/$bookId",
			publicUrl = PLAY_STORE_URL,
			rating = rating,
			contentRating = ContentRating.SAFE,
			coverUrl = item.optString("image").trim().takeIf(String::isNotEmpty),
			tags = category?.let { setOf(MangaTag(it, it, source)) }.orEmpty(),
			state = MangaState.FINISHED,
			authors = author?.let(::setOf).orEmpty(),
			source = source,
		)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val bookId = manga.url.substringAfterLast('/').toIntOrNull()
			?: error("Invalid Al Hilal book URL: ${manga.url}")
		val response = JSONObject(
			apiRequest("getBookData.php", JSONObject().put("bookID", bookId)),
		)
		val book = response.optJSONObject("book") ?: error("Book data is missing")
		val pageCount = book.optInt("pagesNum").coerceIn(1, MAX_PDF_PAGES)
		val pdfUrl = book.optString("pdfUrl").trim().takeIf(String::isNotEmpty)
			?: error("PDF URL is missing")
		val author = book.optString("author").trim().takeIf(String::isNotEmpty)
		val category = book.optString("category").trim().takeIf(String::isNotEmpty)
		val chapterUrl = "${resolvePdfUrl(bookId, pdfUrl)}#$PDF_BOOK_FRAGMENT$pageCount"
		return manga.copy(
			title = book.optString("title").trim().takeIf(String::isNotEmpty) ?: manga.title,
			description = book.optString("description").trim().takeIf(String::isNotEmpty),
			coverUrl = book.optString("image").trim().takeIf(String::isNotEmpty) ?: manga.coverUrl,
			largeCoverUrl = book.optString("image").trim().takeIf(String::isNotEmpty) ?: manga.coverUrl,
			rating = book.optDouble("rating", Double.NaN).takeUnless(Double::isNaN)
				?.div(5.0)?.toFloat()?.coerceIn(0f, 1f) ?: manga.rating,
			tags = category?.let { setOf(MangaTag(it, it, source)) }.orEmpty(),
			state = MangaState.FINISHED,
			authors = author?.let(::setOf).orEmpty(),
			chapters = listOf(
				MangaChapter(
					id = generateUid(chapterUrl),
					title = "الكتاب الكامل",
					number = 1f,
					volume = 0,
					url = chapterUrl,
					scanlator = "مكتبة الهلال",
					uploadDate = 0L,
					branch = null,
					source = source,
				),
			),
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val pageCount = PDF_BOOK_PAGES.find(chapter.url)?.groupValues?.getOrNull(1)?.toIntOrNull()
			?.coerceIn(1, MAX_PDF_PAGES)
			?: error("PDF page count is missing")
		val pdfUrl = chapter.url.substringBefore('#')
		return List(pageCount) { index ->
			val pageUrl = "$pdfUrl#$PDF_PAGE_FRAGMENT$index"
			MangaPage(
				id = generateUid(pageUrl),
				url = pageUrl,
				preview = null,
				source = source,
			)
		}
	}

	private suspend fun apiRequest(endpoint: String, body: JSONObject): String {
		val payload = encodeRequest(body.toString())
		val request = Request.Builder()
			.url("https://$domain/API/v4/$endpoint")
			.post(payload.toRequestBody(null))
			.cacheControl(CacheControl.FORCE_NETWORK)
			.tag(MangaSource::class.java, source)
			.build()
		return loaderContext.httpClient.newCall(request).await().use { response ->
			if (!response.isSuccessful) {
				throw HttpStatusException(response.message, response.code, request.url.toString())
			}
			decodeResponse(response.requireBody().string())
		}
	}

	private fun encodeRequest(json: String): String = buildString {
		append(randomToken(17))
		append('é')
		append(randomToken(8))
		append(loaderContext.encodeBase64(json.toByteArray(Charsets.UTF_8)))
	}

	internal fun decodeResponse(raw: String): String {
		// The server inserts decorative characters into Base64 and occasionally
		// emits an invalid UTF-8 byte. Keeping only the Base64 alphabet is more
		// robust than the official client's four explicit replacements.
		val cleaned = raw.filter { it in BASE64_ALPHABET }
		require(cleaned.length > RESPONSE_PREFIX_LENGTH) { "Invalid Al Hilal response" }
		val outer = loaderContext.decodeBase64(cleaned.substring(RESPONSE_PREFIX_LENGTH))
			.toString(Charsets.UTF_8)
		require(outer.length > INNER_PREFIX_LENGTH + 2) { "Invalid Al Hilal payload" }
		val inner = outer.substring(INNER_PREFIX_LENGTH)
		val paddingLength = inner.take(2).toIntOrNull() ?: error("Invalid Al Hilal padding")
		require(inner.length >= 2 + paddingLength) { "Incomplete Al Hilal payload" }
		return inner.substring(2 + paddingLength)
	}

	internal fun resolvePdfUrl(bookId: Int, apiPdfUrl: String): String =
		if (KNOWN_PDF_REDIRECT.matches(apiPdfUrl)) {
			"https://alkawn-lib.site/Books/PDF/Book$bookId.pdf"
		} else {
			apiPdfUrl
		}

	private fun randomToken(length: Int): String = buildString(length) {
		repeat(length) { append(TOKEN_ALPHABET[Random.nextInt(TOKEN_ALPHABET.length)]) }
	}

	internal companion object {
		private const val API_DOMAIN = "alhilal-lib.site"
		private const val PLAY_STORE_URL =
			"https://play.google.com/store/apps/details?id=com.alhilal.library"
		private const val PAGE_SIZE = 30
		private const val MAX_PDF_PAGES = 5000
		private const val RESPONSE_PREFIX_LENGTH = 23
		private const val INNER_PREFIX_LENGTH = 17
		private const val PDF_BOOK_FRAGMENT = "pdf-pages="
		private const val PDF_PAGE_FRAGMENT = "pdf-page="
		private const val TOKEN_ALPHABET =
			"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0404034130873806_4040375842067949"
		private const val BASE64_ALPHABET =
			"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/="
		private val PDF_BOOK_PAGES = Regex("#$PDF_BOOK_FRAGMENT(\\d+)")
		private val KNOWN_PDF_REDIRECT = Regex(
			"""https?://(?:www\.)?(?:alkawn|alhilal)-lib\.site/API/v4/bookPDF\.php(?:\?.*)?""",
			RegexOption.IGNORE_CASE,
		)
	}
}
