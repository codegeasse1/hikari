package org.hany18h.parsers.site.ar

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Headers
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
import org.hany18h.parsers.network.UserAgents
import org.hany18h.parsers.util.generateUid
import org.hany18h.parsers.util.parseJson
import org.hany18h.parsers.util.urlEncoded
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

/**
 * Arabic manga and manhwa from Kawaii Manga's public web client API.
 * The short-lived app token is requested exactly as the website does and is
 * cached until shortly before expiry so opening a chapter does not create a
 * token request for every image.
 */
@MangaSourceParser("KAWAIIMANGA", "كواي مانجا", "ar", ContentType.MANGA)
internal class KawaiiManga(context: MangaLoaderContext) : PagedMangaParser(
	context = context,
	source = MangaParserSource.KAWAIIMANGA,
	pageSize = PAGE_SIZE,
) {

	override val configKeyDomain = ConfigKey.Domain("kawaiimanga.org")
	override val userAgentKey = ConfigKey.UserAgent(UserAgents.CHROME_MOBILE)

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.RATING,
		SortOrder.ALPHABETICAL,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(isSearchSupported = true)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions()

	override suspend fun getListPage(
		page: Int,
		order: SortOrder,
		filter: MangaListFilter,
	): List<Manga> {
		val query = filter.query?.trim().orEmpty()
		if (query.isNotEmpty() && page > 1) return emptyList()
		val action = if (query.isNotEmpty()) "search&q=${query.urlEncoded()}" else buildString {
			append("browse&page=")
			append(page)
			append("&limit=")
			append(PAGE_SIZE)
			append("&sort=")
			append(
				when (order) {
					SortOrder.POPULARITY -> "views"
					SortOrder.RATING -> "rating"
					SortOrder.ALPHABETICAL -> "title"
					else -> "latest"
				},
			)
		}
		val response = apiGet(action)
		val results = response.optJSONArray("results") ?: return emptyList()
		return buildList(results.length()) {
			for (index in 0 until results.length()) {
				results.optJSONObject(index)?.let { add(parseManga(it)) }
			}
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val slug = manga.url.substringAfter("/manga/").substringBefore('/')
		val response = apiGet("series&slug=${slug.urlEncoded()}")
		val details = parseManga(response)
		val chaptersJson = response.optJSONArray("chapters")
		val chapters = buildList {
			if (chaptersJson != null) {
				for (index in 0 until chaptersJson.length()) {
					val item = chaptersJson.optJSONObject(index) ?: continue
					val chapterId = item.optString("id").trim()
					if (chapterId.isEmpty()) continue
					val number = item.optDouble("number", index + 1.0).toFloat()
					add(
						MangaChapter(
							id = generateUid(chapterId),
							title = item.optString("title").trim().ifEmpty { "الفصل ${formatNumber(number)}" },
							number = number,
							volume = 0,
							url = "/chapter/$chapterId",
							scanlator = "كواي مانجا",
							uploadDate = parseDate(item.optString("createdAt")),
							branch = null,
							source = source,
						),
					)
				}
			}
		}.sortedBy(MangaChapter::number)
		return details.copy(
			id = manga.id,
			url = manga.url,
			publicUrl = manga.publicUrl,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapterId = chapter.url.substringAfter("/chapter/").substringBefore('/')
		val pages = apiGet("pages&chapterId=${chapterId.urlEncoded()}").optJSONArray("pages")
			?: return emptyList()
		return buildList(pages.length()) {
			for (index in 0 until pages.length()) {
				val url = pages.optString(index).trim()
				if (url.isNotEmpty()) {
					add(MangaPage(generateUid("$chapterId:$index:$url"), url, null, source))
				}
			}
		}
	}

	private fun parseManga(json: JSONObject): Manga {
		val slug = json.optString("slug").trim()
		val id = json.optString("id").trim().ifEmpty { slug }
		val titleAr = json.optString("titleAr").trim()
		val title = titleAr.ifEmpty {
			json.optString("title").trim().ifEmpty { json.optString("titleEn").trim() }
		}
		val altTitles = buildSet {
			listOf(json.optString("title"), json.optString("titleEn"), titleAr)
				.map(String::trim)
				.filterTo(this) { it.isNotEmpty() && it != title }
			json.optJSONArray("altTitles")?.let { array ->
				for (index in 0 until array.length()) {
					array.optString(index).trim().takeIf(String::isNotEmpty)?.let(::add)
				}
			}
		}
		val tags = buildSet {
			json.optJSONArray("genres")?.let { array ->
				for (index in 0 until array.length()) {
					val genre = array.optString(index).trim()
					if (genre.isNotEmpty()) add(MangaTag(genre, genre, source))
				}
			}
		}
		val authors = setOfNotNull(
			json.optString("author").trim().takeIf(String::isNotEmpty),
			json.optString("artist").trim().takeIf(String::isNotEmpty),
		)
		val rating = json.optDouble("rating", Double.NaN).takeUnless(Double::isNaN)
			?.div(5.0)?.toFloat()?.coerceIn(0f, 1f) ?: RATING_UNKNOWN
		return Manga(
			id = generateUid(id),
			title = title,
			altTitles = altTitles,
			url = "/manga/$slug",
			publicUrl = "https://$domain/manga/$slug",
			rating = rating,
			contentRating = ContentRating.SAFE,
			coverUrl = json.optString("coverUrl").trim().takeIf(String::isNotEmpty),
			tags = tags,
			state = when (json.optString("status").lowercase(Locale.ENGLISH)) {
				"ongoing" -> MangaState.ONGOING
				"completed", "finished" -> MangaState.FINISHED
				"hiatus", "paused" -> MangaState.PAUSED
				else -> null
			},
			authors = authors,
			source = source,
			description = json.optString("description").trim().takeIf(String::isNotEmpty),
			largeCoverUrl = json.optString("coverUrl").trim().takeIf(String::isNotEmpty),
		)
	}

	private val tokenMutex = Mutex()
	@Volatile private var apiToken: String? = null
	@Volatile private var tokenExpiresAt = 0L

	private suspend fun apiGet(action: String): JSONObject {
		val token = getApiToken()
		return webClient.httpGet("$API_BASE/own?action=$action", apiHeaders(token)).parseJson()
	}

	private suspend fun getApiToken(): String = tokenMutex.withLock {
		val now = System.currentTimeMillis()
		apiToken?.takeIf { now < tokenExpiresAt }?.let { return@withLock it }
		val response = webClient.httpGet("$API_BASE/token", apiHeaders(null)).parseJson()
		val token = response.getString("token")
		val expiresIn = response.optLong("expiresIn", 1800L).coerceAtLeast(60L)
		apiToken = token
		tokenExpiresAt = now + (expiresIn - TOKEN_EXPIRY_MARGIN_SECONDS) * 1000L
		token
	}

	private fun apiHeaders(token: String?): Headers = Headers.Builder()
		.add("Accept", "application/json")
		.add("Origin", "https://$domain")
		.add("Referer", "https://$domain/")
		.add("User-Agent", config[userAgentKey])
		.add("x-app-key", APP_KEY)
		.apply { if (token != null) add("x-app-token", token) }
		.build()

	private fun parseDate(value: String): Long {
		if (value.isBlank()) return 0L
		return synchronized(DATE_FORMAT) { runCatching { DATE_FORMAT.parse(value)?.time }.getOrNull() } ?: 0L
	}

	private fun formatNumber(number: Float): String =
		if (number % 1f == 0f) number.toInt().toString() else number.toString()

	internal companion object {
		private const val API_BASE = "https://manga-api.kawaii-anime.com/api/manga"
		private const val APP_KEY = "km_2026_live"
		private const val PAGE_SIZE = 30
		private const val TOKEN_EXPIRY_MARGIN_SECONDS = 60L
		private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSX", Locale.ENGLISH)
	}
}
