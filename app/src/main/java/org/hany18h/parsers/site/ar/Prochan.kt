package org.hany18h.parsers.site.ar

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.hany18h.parsers.MangaLoaderContext
import org.hany18h.parsers.MangaSourceParser
import org.hany18h.parsers.config.ConfigKey
import org.hany18h.parsers.core.PagedMangaParser
import org.hany18h.parsers.model.*
import org.hany18h.parsers.util.*
import org.hany18h.parsers.util.json.mapJSONNotNull
import java.text.SimpleDateFormat
import java.util.*
import java.util.Base64

@MangaSourceParser("PROCHAN", "ProChan", "ar")
internal class ProChan(context: MangaLoaderContext) : PagedMangaParser(
	context,
	source = MangaParserSource.PROCHAN,
	pageSize = 18,
), Interceptor {

	override val configKeyDomain = ConfigKey.Domain("procomic.pro")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.ALPHABETICAL,
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

	private val dateFormat by lazy {
		SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
	}

	private val imageCdnHosts = setOf(
		"app.procomic.net",
		"app.procomic.pro",
		"cdn1.procomic.net",
		"cdn2.procomic.net",
		"cdn3.procomic.net",
		"cdn4.procomic.net",
		"cdn2.procomic.pro",
		"cdn3.procomic.pro",
		"cdn4.procomic.pro",
		"img1.procomic.net",
		"img2.procomic.net",
		"img3.procomic.net",
		"img4.procomic.net",
		"img1.procomic.pro",
		"img2.procomic.pro",
		"img3.procomic.pro",
		"img4.procomic.pro",
		"cdn2.prochan.net",
		"cdn3.prochan.net",
	)

	// In-memory cache of every series (≈25 pages x 18 ≈ 450 items).
	// procomic.pro's /search endpoint is locked behind Cloudflare Turnstile,
	// so we do client-side title filtering instead.
	private val allSeriesMutex = Mutex()
	private var allSeriesCache: List<JSONObject>? = null
	private var allSeriesCacheAt = 0L
	private val allSeriesTtlMs = 5 * 60 * 1000L

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val host = request.url.host
		val isProcomicHost = host.contains("procomic") || host.contains("prochan")
		val isImageCdnRequest = host in imageCdnHosts
		val websiteOrigin = if (host == PROCOMIC_NET || host.endsWith(".$PROCOMIC_NET")) {
			"https://$PROCOMIC_NET"
		} else {
			"https://$domain"
		}

		val newRequestBuilder = request.newBuilder()
			.header("Referer", "$websiteOrigin/")
			.header("Origin", websiteOrigin)
			.header("Accept-Language", "en-US,en;q=0.9,ar;q=0.8")
			.header(
				"User-Agent",
				config[userAgentKey],
			)

		if (isImageCdnRequest) {
			newRequestBuilder
				.header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
				.header("Sec-Fetch-Dest", "image")
				.header("Sec-Fetch-Mode", "no-cors")
				.header("Sec-Fetch-Site", "same-site")
		} else {
			newRequestBuilder.header("Accept", "*/*")
		}

		val response = chain.proceed(newRequestBuilder.build())

		val contentType = response.header("Content-Type") ?: ""
		if (contentType.contains("octet-stream") || contentType.isEmpty()) {
			val path = request.url.encodedPath.lowercase()
			val fixedType = when {
				path.endsWith(".avif") -> "image/avif"
				path.endsWith(".webp") -> "image/webp"
				path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
				path.endsWith(".png") -> "image/png"
				path.endsWith(".gif") -> "image/gif"
				isProcomicHost -> "image/jpeg"
				else -> null
			}
			if (fixedType != null) {
				return response.newBuilder()
					.header("Content-Type", fixedType)
					.build()
			}
		}
		return response
	}

	override suspend fun getFilterOptions() = MangaListFilterOptions()

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val query = filter.query?.trim()
		if (!query.isNullOrEmpty()) {
			return searchPage(query, page)
		}

		val endpoint = when (order) {
			SortOrder.UPDATED -> "latest-updates"
			SortOrder.POPULARITY -> "popular"
			SortOrder.ALPHABETICAL -> "az"
			else -> "latest-updates"
		}

		val url = "https://$domain/api/public/content/$endpoint" +
			"?limit=$pageSize&category=comics&page=$page"

		val json = webClient.httpGet(url).parseJson()
		val data = json.optJSONArray("data") ?: return emptyList()

		return data.mapJSONNotNull { item ->
			parseMangaFromList(item)
		}
	}

	private suspend fun searchPage(query: String, page: Int): List<Manga> {
		val all = loadAllSeries()
		val matches = all.mapNotNull { item ->
			val title = bestTitle(item)
			val slug = item.optString("mangaSlug").ifEmpty { item.optString("slug") }
			if (title.contains(query, ignoreCase = true) || slug.contains(query, ignoreCase = true)) {
				parseMangaFromList(item)
			} else {
				null
			}
		}
		val from = (page - 1) * pageSize
		if (from >= matches.size) return emptyList()
		val to = (from + pageSize).coerceAtMost(matches.size)
		return matches.subList(from, to)
	}

	private suspend fun loadAllSeries(): List<JSONObject> = allSeriesMutex.withLock {
		val cached = allSeriesCache
		val now = System.currentTimeMillis()
		if (cached != null && (now - allSeriesCacheAt) < allSeriesTtlMs) {
			return@withLock cached
		}
		val all = mutableListOf<JSONObject>()
		var p = 1
		while (p <= 60) {
			val url = "https://$domain/api/public/content/latest-updates" +
				"?limit=$pageSize&category=comics&page=$p"
			val json = runCatching { webClient.httpGet(url).parseJson() }.getOrNull() ?: break
			val data = json.optJSONArray("data") ?: break
			if (data.length() == 0) break
			for (i in 0 until data.length()) {
				data.optJSONObject(i)?.let { all.add(it) }
			}
			p++
		}
		allSeriesCache = all
		allSeriesCacheAt = now
		all
	}

	private fun bestTitle(item: JSONObject): String =
		item.optString("mangaTitle").ifEmpty { item.optString("title") }

	private fun parseMangaFromList(item: JSONObject): Manga? {
		val id = item.optInt("mangaId").takeIf { it > 0 }
			?: item.optInt("id").takeIf { it > 0 }
			?: return null
		val slug = item.optString("mangaSlug").takeIf { it.isNotEmpty() }
			?: item.optString("slug").takeIf { it.isNotEmpty() }
			?: return null
		val title = bestTitle(item).takeIf { it.isNotEmpty() } ?: return null
		val type = item.optString("type", "manhua")

		if (type == "novel") return null

		val coverUrl = getBestCover(item)
		val status = item.optString("status", "")
		val mangaUrl = "/series/$type/$id/$slug"
		val contentDomain = getProChanContentDomain(item, domain)

		return Manga(
			id = generateUid(mangaUrl),
			title = title,
			altTitles = emptySet(),
			url = mangaUrl,
			publicUrl = "https://$contentDomain$mangaUrl",
			rating = RATING_UNKNOWN,
			contentRating = if (item.optBoolean("isSensitiveImage")) {
				ContentRating.ADULT
			} else {
				null
			},
			coverUrl = coverUrl,
			tags = emptySet(),
			state = parseState(status),
			authors = emptySet(),
			description = null,
			chapters = emptyList(),
			source = source,
		)
	}

	private fun getBestCover(item: JSONObject): String {
		val appCover = item.optJSONObject("coverImageApp")
		if (appCover != null) {
			val card = appCover.optJSONObject("card")
			val mobile = card?.optString("mobile")?.takeIf { it.isNotEmpty() }
			if (mobile != null) return mobile
			val desktop = appCover.optString("desktop").takeIf { it.isNotEmpty() }
			if (desktop != null) return desktop
		}
		return item.optString("coverImage", "")
	}

	private fun parseState(status: String): MangaState? = when {
		status.contains("مستمر", ignoreCase = true) -> MangaState.ONGOING
		status.contains("مكتمل", ignoreCase = true) -> MangaState.FINISHED
		status.contains("متوقف", ignoreCase = true) -> MangaState.ABANDONED
		status.contains("ongoing", ignoreCase = true) -> MangaState.ONGOING
		status.contains("completed", ignoreCase = true) -> MangaState.FINISHED
		else -> null
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val publicUrl = manga.publicUrl.toHttpUrlOrNull()
		val mangaPath = publicUrl?.encodedPath
			?: manga.url.substringBefore('?').substringBefore('#')
		val parts = mangaPath.split("/").filter { it.isNotEmpty() }
		if (parts.size < 4) return manga
		val id = parts[2]
		val contentDomain = when {
			manga.coverUrl?.contains(".$PROCOMIC_NET/", ignoreCase = true) == true -> PROCOMIC_NET
			else -> publicUrl?.host ?: domain
		}

		val chaptersData = runCatching {
			loadAllChapters(contentDomain, id)
		}.getOrElse { return manga }

		return manga.copy(
			chapters = parseChapters(chaptersData, mangaPath, contentDomain),
		)
	}

	private suspend fun loadAllChapters(contentDomain: String, contentId: String): JSONArray {
		val result = JSONArray()
		val seenIds = HashSet<Int>()
		val seenPageSignatures = HashSet<String>()
		var page = 1
		while (page <= MAX_CHAPTER_PAGES) {
			val chaptersUrl = "https://$contentDomain/api/public/chapters" +
				"?contentId=$contentId&page=$page&limit=$CHAPTER_PAGE_SIZE&order=asc"
			val response = webClient.httpGet(chaptersUrl).parseJson()
			val chapters = response.optJSONArray("chapters") ?: break
			if (chapters.length() == 0) break
			val signature = (0 until chapters.length())
				.joinToString(",") { index -> chapters.optJSONObject(index)?.optInt("id").toString() }
			if (!seenPageSignatures.add(signature)) break
			appendUniqueProChanChapters(result, chapters, seenIds)
			if (!response.optBoolean("hasMore", chapters.length() >= CHAPTER_PAGE_SIZE)) break
			page++
		}
		return result
	}

	private fun parseChapters(
		data: JSONArray,
		mangaPath: String,
		contentDomain: String,
	): List<MangaChapter> {
		return filterAndSortProChanChapters(data).mapNotNull { item ->
			val chapterId = item.optInt("id").takeIf { it > 0 } ?: return@mapNotNull null

			val chapterNumber = item.optString("chapter_number").takeIf { it.isNotEmpty() }
				?: item.optString("number").takeIf { it.isNotEmpty() }
				?: return@mapNotNull null

			val chapterNum = chapterNumber.toFloatOrNull() ?: return@mapNotNull null

			val title = item.optString("title").takeIf {
				it.isNotEmpty() && it != "null" && it != chapterNumber
			}

			val publishedAt = item.optString("published_at", "")
				.takeIf { it.isNotEmpty() }
				?: item.optString("publishedAt", "")

			val uploadDate = runCatching {
				dateFormat.parse(publishedAt)?.time ?: 0L
			}.getOrDefault(0L)

			val chapterPath = "${mangaPath.trimEnd('/')}/$chapterId/$chapterNumber"
			val chapterUrl = "https://$contentDomain$chapterPath"

			MangaChapter(
				id = generateUid(chapterPath),
				title = title,
				number = chapterNum,
				volume = 0,
				url = chapterUrl,
				scanlator = null,
				uploadDate = uploadDate,
				branch = null,
				source = source,
			)
		}
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapterUrl = chapter.url.toAbsoluteUrl(domain)
		val parsedChapterUrl = chapterUrl.toHttpUrlOrNull() ?: return emptyList()
		val parts = parsedChapterUrl.pathSegments
		val chapterId = parts.getOrNull(4) ?: return emptyList()
		val contentDomain = parsedChapterUrl.host
		val doc = webClient.httpGet(chapterUrl).parseHtml()

		val pages = mutableListOf<MangaPage>()
		var pageIndex = 0

		val allText = decodeProChanNextPayload(
			doc.select("script").joinToString("") { it.data() },
		)
		val cdnPath = Regex(""""(?:cdnPath|cdn_path)":"([^"]+)"""")
			.find(allText)
			?.groupValues
			?.getOrNull(1)
			?.takeIf(String::isNotBlank)
			?: when (parts.getOrNull(1)?.lowercase(Locale.ROOT)) {
				"manga" -> "cdn1"
				"manhua" -> "cdn2"
				"manhwa" -> "cdn3"
				else -> "cdn2"
			}

		val appImagesPattern = Regex(
			""""appImages":\[(.*?)\]""",
			RegexOption.DOT_MATCHES_ALL,
		)

		var foundAppImages = false
		appImagesPattern.find(allText)?.groupValues?.get(1)?.let { raw ->
			runCatching {
				val arr = JSONArray("[$raw]")
				for (i in 0 until arr.length()) {
					val item = arr.optJSONObject(i) ?: continue
					val url = item.optString("mobile").takeIf { it.isNotEmpty() }
						?: item.optString("desktop").takeIf { it.isNotEmpty() }
						?: continue
					pages.add(
						MangaPage(
							id = generateUid("${chapter.id}-$pageIndex"),
							url = url,
							preview = null,
							source = source,
						),
					)
					pageIndex++
					foundAppImages = true
				}
			}
		}

		val tokenPattern = Regex(""""token":"([^"]+)"""")
		val splitPattern = Regex(""""splitIndex":(\d+)""")
		val token = tokenPattern.find(allText)?.groupValues?.get(1)
		val splitIndex = splitPattern.find(allText)?.groupValues?.get(1) ?: "3"

		if (!token.isNullOrEmpty()) {
			val deferredUrl = "https://$contentDomain/chapter-deferred-media/$chapterId" +
				"?token=$token&split=$splitIndex"

			val deferredData = runCatching {
				webClient.httpGet(deferredUrl, pageHeaders(chapterUrl)).parseJson()
			}.getOrNull()?.optJSONObject("data")

			deferredData?.optJSONArray("images")?.let { images ->
				for (i in 0 until images.length()) {
					val imgUrl = images.optString(i).takeIf { it.isNotEmpty() } ?: continue
					val playableUrl = if (isProtectedProComicImage(imgUrl)) {
						signCdnImage(imgUrl, chapterUrl, contentDomain) ?: continue
					} else {
						imgUrl
					}
					pages.add(
						MangaPage(
							id = generateUid("${chapter.id}-def-$pageIndex"),
							url = playableUrl,
							preview = null,
							source = source,
						),
					)
					pageIndex++
				}
			}

			deferredData?.optJSONArray("maps")?.let { maps ->
				for (i in 0 until maps.length()) {
					val descriptor = maps.optJSONObject(i) ?: continue
					val map = if (descriptor.optJSONArray("pieces") != null) {
						descriptor
					} else {
						resolveProtectedMap(
							chapterId = chapterId,
							descriptor = descriptor,
							cdnPath = cdnPath,
							pageIndex = i,
							referer = chapterUrl,
							contentDomain = contentDomain,
						) ?: continue
					}
					val mapUrl = buildProChanStitchUrl(map, contentDomain) ?: continue

					pages.add(
						MangaPage(
							id = generateUid("${chapter.id}-map-$pageIndex"),
							url = mapUrl,
							preview = null,
							source = source,
						),
					)
					pageIndex++
				}
			}
		}

		if (!foundAppImages && pages.isEmpty()) {
			val imagesPattern = Regex(""""images":\["(.*?)"\]""", RegexOption.DOT_MATCHES_ALL)
			imagesPattern.find(allText)?.groupValues?.get(1)?.split("\",\"")?.forEach { url ->
				if (url.isNotEmpty()) {
					pages.add(
						MangaPage(
							id = generateUid("${chapter.id}-fb-$pageIndex"),
							url = url,
							preview = null,
							source = source,
						),
					)
					pageIndex++
				}
			}
		}

		return pages
	}

	private suspend fun signCdnImage(
		rawUrl: String,
		referer: String,
		contentDomain: String,
	): String? {
		val result = runCatching {
			webClient.httpPost(
				"https://$contentDomain/api/cdn-image/sign".toHttpUrl(),
				JSONObject().put("url", rawUrl),
				jsonHeaders(referer),
			).parseJson()
		}.getOrNull() ?: return null
		val token = result.optString("token").takeIf(String::isNotBlank) ?: return null
		val expires = result.optLong("expires").takeIf { it > 0L } ?: return null
		return buildProChanSignedImageUrl(contentDomain, rawUrl, token, expires)
	}

	private suspend fun resolveProtectedMap(
		chapterId: String,
		descriptor: JSONObject,
		cdnPath: String,
		pageIndex: Int,
		referer: String,
		contentDomain: String,
	): JSONObject? {
		val token = descriptor.optString("token").takeIf(String::isNotBlank) ?: return null
		val method = descriptor.optString("method", "browser_session")
			.takeIf(String::isNotBlank)
			?: "browser_session"
		val payload = JSONObject()
			.put("token", token)
			.put("method", method)
			.put("cdnPath", cdnPath)
			.put("pageIndex", pageIndex)
		val response = runCatching {
			webClient.httpPost(
				"https://$contentDomain/chapter-map-proxy-plan/$chapterId".toHttpUrl(),
				payload,
				jsonHeaders(referer),
			).parseJson()
		}.getOrNull() ?: return null
		return response.optJSONObject("data")?.optJSONObject("map")
			?: response.optJSONObject("map")
	}

	private fun jsonHeaders(referer: String): Headers = Headers.Builder()
		.add("Accept", "application/json, text/plain, */*")
		.add("Content-Type", "application/json")
		.add(
			"Origin",
			referer.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" } ?: "https://$domain",
		)
		.add("Referer", referer)
		.add("User-Agent", config[userAgentKey])
		.build()

	private fun pageHeaders(referer: String): Headers = Headers.Builder()
		.add("Accept", "application/json,text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
		.add("Accept-Language", "ar,en-US;q=0.8,en;q=0.7")
		.add("Referer", referer)
		.add("User-Agent", config[userAgentKey])
		.build()

	private companion object {
		const val PROCOMIC_NET = "procomic.net"
		const val CHAPTER_PAGE_SIZE = 50
		const val MAX_CHAPTER_PAGES = 100
	}

	override suspend fun getPageUrl(page: MangaPage): String = page.url
}

internal fun getProChanContentDomain(item: JSONObject, fallbackDomain: String): String =
	if (item.optBoolean("isBlockedSeries", false)) "procomic.net" else fallbackDomain

internal fun appendUniqueProChanChapters(
	target: JSONArray,
	page: JSONArray,
	seenIds: MutableSet<Int>,
): Int {
	var added = 0
	for (i in 0 until page.length()) {
		val item = page.optJSONObject(i) ?: continue
		val chapterId = item.optInt("id")
		if (chapterId > 0 && !seenIds.add(chapterId)) continue
		target.put(item)
		added++
	}
	return added
}

internal fun filterAndSortProChanChapters(data: JSONArray): List<JSONObject> {
	return data.mapJSONNotNull { item ->
		val language = item.optString("language").trim()
		if (language.isNotEmpty() && !language.equals("AR", ignoreCase = true)) {
			return@mapJSONNotNull null
		}
		val coinsRequired = maxOf(
			item.optInt("coins_required", 0),
			item.optInt("coinsRequired", 0),
		)
		if (
			coinsRequired > 0 ||
			item.optBoolean("lockedForever", false) ||
			item.optBoolean("lockedByCoins", false) ||
			item.optBoolean("lockedByExclusive", false)
		) {
			return@mapJSONNotNull null
		}
		val number = item.optString("chapter_number")
			.ifBlank { item.optString("number") }
			.toFloatOrNull()
			?: return@mapJSONNotNull null
		// Android's org.json.JSONObject has no put(String, Float) overload.
		// Force the API-compatible Number overload instead of emitting a JVM-only
		// Float method call that crashes while the chapter list is being built.
		item.put("_normalized_chapter_number", number.toDouble())
	}.distinctBy { it.optInt("id") }
		.sortedWith(
			compareBy<JSONObject> { it.optDouble("_normalized_chapter_number", Double.MAX_VALUE) }
				.thenBy { it.optInt("id") },
		)
}

private fun isProtectedProComicImage(url: String): Boolean =
	(url.contains(".procomic.pro/", ignoreCase = true) ||
		url.contains(".procomic.net/", ignoreCase = true) ||
		url.contains(".prochan.net/", ignoreCase = true)) &&
		(url.contains("/cdn", ignoreCase = true) || url.contains("cdn", ignoreCase = true))

internal fun decodeProChanNextPayload(raw: String): String {
	var result = raw
	repeat(3) {
		val decoded = result
			.replace("\\\\", "\\")
			.replace("\\\"", "\"")
			.replace("\\/", "/")
		if (decoded == result) return result
		result = decoded
	}
	return result
}

internal fun buildProChanSignedImageUrl(
	domain: String,
	rawUrl: String,
	token: String,
	expires: Long,
): String = "https://$domain/api/cdn-image?url=${rawUrl.urlEncoded()}" +
	"&token=${token.urlEncoded()}&expires=$expires"

internal fun buildProChanStitchUrl(map: JSONObject, domain: String): String? {
	val pieces = map.optJSONArray("pieces") ?: return null
	val order = map.optJSONArray("order")
	val sourcePieces = (0 until pieces.length()).map { index -> pieces.optString(index) }
	val orderedPieces = if (order == null || order.length() == 0) {
		sourcePieces
	} else {
		(0 until order.length()).mapNotNull { index ->
			sourcePieces.getOrNull(order.optInt(index, -1))
		}
	}
		.map { it.trim() }
		.filter(String::isNotEmpty)
		.map { it.toAbsoluteUrl(domain) }
	if (orderedPieces.isEmpty()) return null

	val dimensions = map.optJSONArray("dim")
	val width = dimensions?.optInt(0, 800)?.coerceAtLeast(1) ?: 800
	val height = dimensions?.optInt(1, 1000)?.coerceAtLeast(1) ?: 1000
	val mode = map.optString("mode", "vertical").ifBlank { "vertical" }
	val encodedPieces = Base64.getUrlEncoder().withoutPadding()
		.encodeToString(orderedPieces.joinToString("|").toByteArray(Charsets.UTF_8))
	val encodedRects = map.optJSONArray("rects")
		?.takeIf { it.length() == orderedPieces.size }
		?.toString()
		?.toByteArray(Charsets.UTF_8)
		?.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
		?.let { "&rects=$it" }
		.orEmpty()
	return "prochan-map://stitch?w=$width&h=$height" +
		"&mode=${mode.urlEncoded()}&pieces=$encodedPieces$encodedRects"
}
