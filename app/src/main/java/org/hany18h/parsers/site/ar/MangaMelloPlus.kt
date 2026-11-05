package org.hany18h.parsers.site.ar

import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
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
import org.hany18h.parsers.util.generateUid
import org.hany18h.parsers.util.nullIfEmpty
import org.hany18h.parsers.util.parseJson
import org.hany18h.parsers.util.urlEncoded
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@MangaSourceParser("MANGAMELLO_PLUS", "MangaMello Plus", "ar", ContentType.MANGA)
internal class MangaMelloPlus(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.MANGAMELLO_PLUS, 40),
	Interceptor {

	override val configKeyDomain = ConfigKey.Domain("plus.mangamello.com")

	override val iconUrl =
		"https://raw.githubusercontent.com/hany18h/kotatsu-parsers/master/src/main/kotlin/org/koitharu/kotatsu/parsers/icons/MangamelloPlus.webp"

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = false,
			isMultipleTagsSupported = false,
			isTagsExclusionSupported = false,
		)

	override val availableSortOrders: Set<SortOrder> = linkedSetOf(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
	)

	override suspend fun getFilterOptions() = MangaListFilterOptions()

	private val apiHeaders: Headers
		get() = Headers.Builder()
			.add("Accept", "application/json")
			.add("Content-Type", "application/json")
			.add("app_version", PLUS_APP_VERSION)
			.add("installer", "com.android.vending")
			.add("lang", "ar")
			.add("device", "android")
			.add("time_zone", TimeZone.getDefault().id)
			// The Plus API intentionally hides routes from browser user agents.
			// Match the Flutter/Dart client shipped by the official app.
			.add("User-Agent", PLUS_API_USER_AGENT)
			.build()

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val host = request.url.host.lowercase(Locale.US)
		if (host in API_HOSTS || request.header("app_version") != null) return chain.proceed(request)
		val rewrittenUrl = rewriteLegacyImageUrl(request.url.toString())

		val referer = when {
			"lekmanga" in host -> "https://www.lekmanga.com/"
			"azorafly" in host || "azoramoon" in host -> "https://azorafly.com/"
			"olympustaff" in host -> "https://olympustaff.com/"
			else -> "https://plus.mangamello.com/"
		}
		return chain.proceed(
			request.newBuilder()
				.url(rewrittenUrl)
				.header("Referer", referer)
				.header("Origin", referer.trimEnd('/'))
				.header("User-Agent", IMAGE_USER_AGENT)
				.header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
				.build(),
		)
	}

	override suspend fun getListPage(
		page: Int,
		order: SortOrder,
		filter: MangaListFilter,
	): List<Manga> {
		val query = filter.query?.trim().orEmpty()
		val path = buildListPath(page, order, query)
		val results = findDataArray(apiGet(path))
		return (0 until results.length()).mapNotNull { index ->
			results.optJSONObject(index)?.let(::parseManga)
		}
	}

	private fun parseManga(json: JSONObject): Manga {
		val id = json.optString("id").ifBlank { json.optInt("id").toString() }
		val title = firstString(json, "title", "name").orEmpty()
		val cover = firstString(json, "img", "image", "cover", "poster")
		val rawRating = firstString(json, "ten_rate", "average_rate", "rate")
			?.toFloatOrNull()
			?: 0f
		val rating = when {
			rawRating <= 0f -> RATING_UNKNOWN
			rawRating > 1f -> rawRating / 10f
			else -> rawRating
		}
		return Manga(
			id = generateUid(id),
			url = "/mangas/$id",
			publicUrl = "https://mangamello.com/mangas/$id",
			coverUrl = cover,
			title = title,
			altTitles = emptySet(),
			rating = rating,
			tags = parseTags(json.optJSONArray("genres")),
			authors = parseAuthors(json),
			state = parseState(json),
			source = source,
			contentRating = ContentRating.SAFE,
		)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val mangaId = manga.url.substringAfterLast('/')
		val root = apiGet("mangas/$mangaId?relations=genres,chapters")
		val data = findDataObject(root)
		val parsed = parseManga(data)
		val embeddedChapters = findChapterArray(data)
		val chapters = if (embeddedChapters != null) {
			parseChapters(embeddedChapters, mangaId)
		} else {
			val chaptersRoot = apiGet("mangas/$mangaId/chapters?per_page=2000")
			parseChapters(findDataArray(chaptersRoot), mangaId)
		}
		return manga.copy(
			title = parsed.title.ifBlank { manga.title },
			coverUrl = parsed.coverUrl ?: manga.coverUrl,
			description = firstString(data, "summary", "description")?.nullIfEmpty(),
			altTitles = parsed.altTitles,
			rating = if (parsed.rating == RATING_UNKNOWN) manga.rating else parsed.rating,
			tags = parsed.tags.ifEmpty { manga.tags },
			authors = parsed.authors.ifEmpty { manga.authors },
			state = parsed.state ?: manga.state,
			chapters = chapters,
		)
	}

	private fun parseChapters(array: JSONArray, fallbackMangaId: String): List<MangaChapter> {
		val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
			timeZone = TimeZone.getTimeZone("UTC")
		}
		return (0 until array.length()).mapNotNull { index ->
			val item = array.optJSONObject(index) ?: return@mapNotNull null
			val chapterId = item.optString("id").ifBlank { item.optInt("id").toString() }
			if (chapterId == "0" || chapterId.isBlank()) return@mapNotNull null
			val mangaId = item.optString("manga_id").ifBlank { fallbackMangaId }
			val number = firstString(item, "order", "chapter", "number")
				?.toFloatOrNull()
				?: (index + 1f)
			val title = firstString(item, "title", "name").orEmpty()
			val createdAt = firstString(item, "created_at", "createdAt")
			val uploadDate = runCatching { dateFormat.parse(createdAt.orEmpty())?.time ?: 0L }.getOrDefault(0L)
			MangaChapter(
				id = generateUid(chapterId),
				title = title,
				number = number,
				volume = 0,
				url = "/mangas/$mangaId/chapters/$chapterId?relations=chapterImages",
				uploadDate = uploadDate,
				source = source,
				scanlator = null,
				branch = null,
			)
		}.sortedBy { it.number }
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val root = apiGet(chapter.url.trimStart('/'))
		val urls = extractImageUrls(root, "$PLUS_API_BASE/")
		return urls.mapIndexed { index, imageUrl ->
			MangaPage(
				id = generateUid("${chapter.id}-$index"),
				url = imageUrl,
				preview = null,
				source = source,
			)
		}
	}

	private suspend fun apiGet(path: String): JSONObject {
		var lastError: Exception? = null
		val configuredDomain = domain.trimEnd('/')
		val configuredApiBase = "https://$configuredDomain/api/v1"
		for (baseUrl in listOf(configuredApiBase, PLUS_API_BASE).distinct()) {
			try {
				return webClient.httpGet(
					"${baseUrl.trimEnd('/')}/${path.trimStart('/')}",
					apiHeaders,
				).parseJson()
			} catch (error: Exception) {
				lastError = error
			}
		}
		throw lastError ?: IllegalStateException("MangaMello API is unavailable")
	}

	private fun parseTags(array: JSONArray?): Set<MangaTag> {
		if (array == null) return emptySet()
		return (0 until array.length()).mapNotNullTo(LinkedHashSet()) { index ->
			val item = array.optJSONObject(index) ?: return@mapNotNullTo null
			val title = firstString(item, "name", "title") ?: return@mapNotNullTo null
			MangaTag(
				key = item.optString("id").ifBlank { title },
				title = title,
				source = source,
			)
		}
	}

	private fun parseAuthors(json: JSONObject): Set<String> {
		val result = LinkedHashSet<String>()
		for (key in AUTHOR_KEYS) {
			when (val value = json.opt(key)) {
				is String -> value.nullIfEmpty()?.let(result::add)
				is JSONObject -> firstString(value, "name", "title")?.let(result::add)
				is JSONArray -> for (index in 0 until value.length()) {
					when (val item = value.opt(index)) {
						is String -> item.nullIfEmpty()?.let(result::add)
						is JSONObject -> firstString(item, "name", "title")?.let(result::add)
					}
				}
			}
		}
		return result
	}

	private fun parseState(json: JSONObject): MangaState? {
		if (json.optInt("is_completed", -1) == 1) return MangaState.FINISHED
		return when (firstString(json, "status", "state")?.lowercase(Locale.US)) {
			"completed", "complete", "finished", "مكتملة", "مكتمل" -> MangaState.FINISHED
			"ongoing", "publishing", "مستمرة", "مستمر" -> MangaState.ONGOING
			"hiatus", "متوقفة", "متوقف" -> MangaState.PAUSED
			else -> null
		}
	}

	internal companion object {

		private const val PLUS_APP_VERSION = "1.1.7"
		private const val PLUS_API_BASE = "https://plus.mangamello.com/api/v1"
		private val API_HOSTS = setOf("plus.mangamello.com")
		private const val IMAGE_USER_AGENT =
			"Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 " +
				"(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
		private const val PLUS_API_USER_AGENT = "Dart/3.8 (dart:io)"
		private val AUTHOR_KEYS = arrayOf("authors", "author", "translator", "editor")
		private val IMAGE_CONTAINER_KEYS = arrayOf(
			"chapterImages",
			"chapter_images",
			"images",
			"pages",
			"data",
		)
		private val IMAGE_VALUE_KEYS = arrayOf(
			"src",
			"originalSrc",
			"original_src",
			"url",
			"image",
			"path",
			"link",
		)

		internal fun buildListPath(page: Int, order: SortOrder, query: String): String {
			if (query.isNotBlank()) {
				return "mangas/search?per_page=40&page=$page&relations=genres,type,ageRate" +
					"&title=${query.urlEncoded()}"
			}
			val sort = if (order == SortOrder.POPULARITY) "views" else "last_update"
			return "mangas?page=$page&per_page=40&relations=genres&sort_by=$sort&dir=desc"
		}

		private fun firstString(json: JSONObject, vararg keys: String): String? {
			for (key in keys) {
				val value = json.opt(key)
				?.takeUnless { it == JSONObject.NULL }
				?.toString()
				?.trim()
				?.nullIfEmpty()
			if (value != null && value != "null") return value
			}
			return null
		}

		private fun findDataObject(root: JSONObject): JSONObject =
			root.optJSONObject("data") ?: root

		private fun findDataArray(root: JSONObject): JSONArray {
			root.optJSONArray("data")?.let { return it }
			val dataObject = root.optJSONObject("data")
			dataObject?.optJSONArray("data")?.let { return it }
			dataObject?.optJSONArray("results")?.let { return it }
			root.optJSONArray("results")?.let { return it }
			return JSONArray()
		}

		private fun findChapterArray(data: JSONObject): JSONArray? {
			data.optJSONArray("chapters")?.let { return it }
			data.optJSONObject("chapters")?.let { nested ->
				nested.optJSONArray("data")?.let { return it }
			}
			return null
		}

		internal fun extractImageUrls(root: JSONObject, baseUrl: String): List<String> {
			val result = LinkedHashSet<String>()
			collectImages(root, baseUrl, result, false)
			return result.toList()
		}

		private fun collectImages(
			value: Any?,
			baseUrl: String,
			result: MutableSet<String>,
			acceptStrings: Boolean,
		) {
			when (value) {
				is JSONArray -> for (index in 0 until value.length()) {
					collectImages(value.opt(index), baseUrl, result, acceptStrings)
				}

				is JSONObject -> {
					for (key in IMAGE_VALUE_KEYS) {
						val candidate = value.opt(key)
						if (candidate is String) {
							val normalized = normalizeImageUrl(candidate, baseUrl)
							if (normalized != null) {
								result.add(normalized)
								break
							}
						}
					}
					for (key in IMAGE_CONTAINER_KEYS) {
						value.opt(key)?.let { collectImages(it, baseUrl, result, true) }
					}
				}

				is String -> if (acceptStrings) {
					normalizeImageUrl(value, baseUrl)?.let(result::add)
				}
			}
		}

		internal fun normalizeImageUrl(value: String, baseUrl: String): String? {
			val cleaned = value
				.trim()
				.trim('"', '\'')
				.replace("\\/", "/")
				.replace("\\u0026", "&")
				.nullIfEmpty()
				?: return null
			if (cleaned.startsWith("data:", ignoreCase = true)) return null
			return runCatching {
				val resolved = when {
					cleaned.startsWith("//") -> "https:$cleaned"
					cleaned.startsWith("http://", true) || cleaned.startsWith("https://", true) -> cleaned
					else -> URI(baseUrl).resolve(cleaned).toString()
				}
				rewriteLegacyImageUrl(resolved)
			}.getOrNull()
		}

		internal fun rewriteLegacyImageUrl(url: String): String {
			val uri = runCatching { URI(url) }.getOrNull() ?: return url
			val host = uri.host
				?.lowercase(Locale.US)
				?: return url
			if (host == TEMP_LEKMANGA_HOST) {
				val shard = TEMP_LEKMANGA_PATH.find(uri.path)
					?.groupValues
					?.getOrNull(1)
					?.takeIf(String::isNotBlank)
					?: return url
				return url.replaceFirst(
					host,
					"s${shard}storm.lekmanga.site",
					ignoreCase = true,
				)
			}
			val match = LEGACY_LEKMANGA_HOST.matchEntire(host) ?: return url
			val currentHost = "s${match.groupValues[1]}storm.lekmanga.site"
			return url.replaceFirst(host, currentHost, ignoreCase = true)
		}

		private const val TEMP_LEKMANGA_HOST = "tempstorm.lekmanga.site"
		private val TEMP_LEKMANGA_PATH = Regex("""^/manga/arb1(\d+)/""", RegexOption.IGNORE_CASE)
		private val LEGACY_LEKMANGA_HOST = Regex("""s(\d+)lekmangas\.lekmanga\.site""")
	}
}
