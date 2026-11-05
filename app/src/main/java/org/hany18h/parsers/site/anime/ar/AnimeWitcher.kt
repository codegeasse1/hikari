package org.hany18h.parsers.site.anime.ar

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Cookie
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.hany18h.parsers.MangaAuthAccount
import org.hany18h.parsers.MangaAuthException
import org.hany18h.parsers.MangaLoaderContext
import org.hany18h.parsers.MangaParserCredentialsAuthProvider
import org.hany18h.parsers.MangaSourceParser
import org.hany18h.parsers.config.ConfigKey
import org.hany18h.parsers.core.PagedMangaParser
import org.hany18h.parsers.exception.AuthRequiredException
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
import org.hany18h.parsers.util.await
import org.hany18h.parsers.util.generateUid
import org.hany18h.parsers.util.parseJson
import org.hany18h.parsers.util.parseRaw
import org.hany18h.parsers.util.urlEncoded
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone

/**
 * AnimeWitcher's website is only a landing page. Its Android client reads the
 * public catalog from Algolia. Viewing servers require a verified Firebase account.
 */
@MangaSourceParser("ANIME_WITCHER", "AnimeWitcher", "ar", ContentType.ANIME)
internal class AnimeWitcher(context: MangaLoaderContext) : PagedMangaParser(
	context = context,
	source = MangaParserSource.ANIME_WITCHER,
	pageSize = PAGE_SIZE,
	searchPageSize = PAGE_SIZE,
), MangaParserCredentialsAuthProvider {

	override val configKeyDomain = ConfigKey.Domain("www.animewitcher.com")
	override val authUrl: String = "https://www.animewitcher.com/"

	private val authMutex = Mutex()
	private val catalogSettingsMutex = Mutex()
	private var catalogCredentials: Pair<String, String>? = null

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.NEWEST,
		SortOrder.NEWEST_ASC,
		SortOrder.ALPHABETICAL,
		SortOrder.ALPHABETICAL_DESC,
		SortOrder.RELEVANCE,
	)

	override val filterCapabilities = MangaListFilterCapabilities(isSearchSupported = true)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override suspend fun getFilterOptions() = MangaListFilterOptions()

	override suspend fun isAuthorized(): Boolean =
		readCookie(COOKIE_EMAIL_VERIFIED) == "1" &&
			readCookie(COOKIE_REFRESH_TOKEN) != null

	override suspend fun getUsername(): String =
		getAccount()?.username ?: throw AuthRequiredException(source)

	override suspend fun getAccount(): MangaAuthAccount? = authMutex.withLock {
		getAccountLocked(forceRefresh = false)
	}

	override suspend fun refreshAccount(): MangaAuthAccount? = authMutex.withLock {
		getAccountLocked(forceRefresh = true)
	}

	override suspend fun signIn(email: String, password: String): MangaAuthAccount = authMutex.withLock {
		val normalizedEmail = email.trim().lowercase(Locale.US)
		val response = authPost(
			endpoint = "accounts:signInWithPassword",
			body = JSONObject()
				.put("email", normalizedEmail)
				.put("password", password)
				.put("returnSecureToken", true),
		)
		saveSession(response)
		val token = response.getString("idToken")
		val uid = response.getString("localId")
		ensureUserDocument(
			uid = uid,
			email = normalizedEmail,
			displayName = normalizedEmail.substringBefore('@'),
			idToken = token,
		)
		lookupAccount(token) ?: throw MangaAuthException("USER_NOT_FOUND")
	}

	override suspend fun signUp(
		displayName: String,
		email: String,
		password: String,
	): MangaAuthAccount = authMutex.withLock {
		val normalizedEmail = email.trim().lowercase(Locale.US)
		val normalizedName = displayName.trim()
		val response = authPost(
			endpoint = "accounts:signUp",
			body = JSONObject()
				.put("email", normalizedEmail)
				.put("password", password)
				.put("returnSecureToken", true),
		)
		saveSession(response, normalizedName)
		val token = response.getString("idToken")
		ensureUserDocument(
			uid = response.getString("localId"),
			email = normalizedEmail,
			displayName = normalizedName,
			idToken = token,
		)
		sendVerificationEmailLocked(token)
		lookupAccount(token)
			?: MangaAuthAccount(normalizedEmail, normalizedName, isEmailVerified = false)
	}

	override suspend fun sendVerificationEmail() {
		authMutex.withLock {
			val token = validIdTokenLocked(forceRefresh = false)
				?: throw AuthRequiredException(source)
			sendVerificationEmailLocked(token)
		}
	}

	override suspend fun sendPasswordReset(email: String) {
		authPost(
			endpoint = "accounts:sendOobCode",
			body = JSONObject()
				.put("requestType", "PASSWORD_RESET")
				.put("email", email.trim().lowercase(Locale.US)),
		)
	}

	override suspend fun signOut() {
		authMutex.withLock {
			clearSession()
		}
	}

	override suspend fun getListPage(
		page: Int,
		order: SortOrder,
		filter: MangaListFilter,
	): List<Manga> {
		val index = when (order) {
			SortOrder.NEWEST -> "series_year_desc"
			SortOrder.NEWEST_ASC -> "series_year_asc"
			SortOrder.ALPHABETICAL_DESC -> "series_name_desc"
			SortOrder.RELEVANCE -> "series"
			else -> "series_name_asc"
		}
		val body = JSONObject()
			.put("query", filter.query?.trim().orEmpty())
			.put("hitsPerPage", PAGE_SIZE)
			.put("page", page - 1)
			.put("attributesToRetrieve", ALGOLIA_ATTRIBUTES)
		val response = queryAlgolia(index, body)
		val hits = response.optJSONArray("hits") ?: return emptyList()
		return buildList {
			for (i in 0 until hits.length()) {
				hits.optJSONObject(i)?.let(::parseCatalogItem)?.let(::add)
			}
		}.distinctBy(Manga::id)
	}

	private suspend fun getCatalogCredentials(forceRefresh: Boolean): Pair<String, String> =
		catalogSettingsMutex.withLock {
			if (!forceRefresh) catalogCredentials?.let { return@withLock it }
			val settings = fetchDocument("Settings", "constants")
			val credentials = extractAlgoliaCatalogCredentials(settings)
				?: throw IllegalStateException("AnimeWitcher catalog settings are unavailable")
			catalogCredentials = credentials
			credentials
		}

	private suspend fun queryAlgolia(index: String, body: JSONObject): JSONObject {
		var lastError: Exception? = null
		repeat(2) { attempt ->
			val (appId, searchKey) = getCatalogCredentials(forceRefresh = attempt != 0)
			val headers = Headers.Builder()
				.add("X-Algolia-Application-Id", appId)
				.add("X-Algolia-API-Key", searchKey)
				.build()
			for (host in algoliaReadHosts(appId)) {
				try {
					return webClient.httpPost(
						"https://$host/1/indexes/$index/query".toHttpUrl(),
						body,
						headers,
					).parseJson()
				} catch (error: CancellationException) {
					throw error
				} catch (error: Exception) {
					lastError = error
				}
			}
		}
		throw lastError ?: IllegalStateException("AnimeWitcher catalog is unavailable")
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val animeId = decode(manga.url.substringAfter(ANIME_PATH))
		val fields = fetchDocument("anime_list", animeId).optJSONObject("fields")
			?: return manga
		val details = fields.firestoreMap("details")
		val poster = fields.firestoreString("poster_uri") ?: manga.coverUrl
		val tags = fields.firestoreStrings("tags").mapTo(LinkedHashSet()) {
			MangaTag(title = it, key = it, source = source)
		}
		val altTitles = buildSet {
			addAll(fields.firestoreStrings("other_names"))
			details?.firestoreString("english_title")?.let(::add)
		}
		val studios = details?.firestoreStrings("studio").orEmpty().toSet()
		val rating = fields.firestoreNumber("average_rate")
			?: fields.firestoreMap("rating")?.firestoreNumber("rate")
		val chapters = loadEpisodes(animeId)

		return manga.copy(
			title = fields.firestoreString("name") ?: manga.title,
			altTitles = altTitles.ifEmpty { manga.altTitles },
			publicUrl = "https://$domain/",
			coverUrl = poster,
			largeCoverUrl = poster,
			description = fields.firestoreString("story") ?: manga.description,
			tags = tags.ifEmpty { manga.tags },
			state = parseState(details?.firestoreString("state")) ?: manga.state,
			authors = studios.ifEmpty { manga.authors },
			rating = rating?.let(::normalizeRating) ?: manga.rating,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> = emptyList()

	override suspend fun getVideoStreams(chapter: MangaChapter): List<AnimeStream> {
		val path = chapter.url.substringAfter(EPISODE_PATH)
		val parts = path.split('/', limit = 2)
		if (parts.size != 2) return emptyList()
		val animeId = decode(parts[0])
		val episodeId = decode(parts[1])
		val servers = loadServers(animeId, episodeId)
		return buildList {
			for (server in servers) {
				if (server.firestoreBoolean("visible") == false) continue
				val link = server.firestoreString("link") ?: continue
				val direct = resolveVideoUrl(
					link = link,
					directLink = server.firestoreBoolean("direct_link") == true,
				) ?: continue
				val serverName = displayServerName(server.firestoreString("name"))
				val quality = server.firestoreString("quality")?.takeIf(String::isNotBlank)
				add(
					AnimeStream(
						name = listOfNotNull("AnimeWitcher", serverName, quality)
							.joinToString(" • "),
						url = direct,
						headers = mapOf(
							"Referer" to link,
							"User-Agent" to config[userAgentKey],
						),
						quality = quality,
					),
				)
			}
		}.distinctBy(AnimeStream::url)
	}

	private suspend fun resolveVideoUrl(link: String, directLink: Boolean): String? {
		toDirectVideoUrl(link, directLink)?.let { return it }
		val url = runCatching { link.toHttpUrl() }.getOrNull() ?: return null
		return when {
			url.host == "streamtape.com" || url.host.endsWith(".streamtape.com") ->
				resolveStreamTape(link)
			url.host == "mediafire.com" || url.host.endsWith(".mediafire.com") ->
				resolveMediaFire(link)
			url.host == "krakenfiles.com" || url.host.endsWith(".krakenfiles.com") ->
				resolveKrakenFiles(link)
			else -> null
		}
	}

	private suspend fun resolveStreamTape(link: String): String? {
		val raw = loadEmbedPage(link) ?: return null
		findDirectVideoInPage(raw)?.let { return it }
		val fragment = STREAM_TAPE_FRAGMENT.find(raw)?.groupValues?.getOrNull(1)
			?.decodeHtmlUrl()
			?.trim()
			?.takeIf { it.isNotEmpty() && '+' !in it }
			?: return null
		return "https://streamtape.com/get_video?id=$fragment"
	}

	private suspend fun resolveMediaFire(link: String): String? {
		val raw = loadEmbedPage(link) ?: return null
		findDirectVideoInPage(raw)?.let { return it }
		val document = Jsoup.parse(raw, link)
		return document.selectFirst("a#downloadButton[href], a.input.popsok[href], a[aria-label*=Download][href]")
			?.absUrl("href")
			?.takeIf { it.isNotBlank() && it != link }
	}

	private suspend fun resolveKrakenFiles(link: String): String? {
		val raw = loadEmbedPage(link) ?: return null
		findDirectVideoInPage(raw)?.let { return it }
		val document = Jsoup.parse(raw, link)
		val form = document.selectFirst("form#dl-form, form[action*=download]") ?: return null
		val action = form.absUrl("action").ifEmpty { link }
		val values = form.select("input[name]").associate { input ->
			input.attr("name") to input.attr("value")
		}
		val response = runCatching {
			webClient.httpPost(
				action.toHttpUrl(),
				values,
				serverPageHeaders(link).newBuilder()
					.add("X-Requested-With", "XMLHttpRequest")
					.build(),
			).parseRaw()
		}.getOrNull() ?: return null
		return runCatching { JSONObject(response).optString("url") }
			.getOrNull()
			?.decodeHtmlUrl()
			?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
			?: findDirectVideoInPage(response)
	}

	private suspend fun loadEmbedPage(link: String): String? = runCatching {
		webClient.httpGet(link, serverPageHeaders(link)).parseRaw()
	}.getOrNull()

	private fun serverPageHeaders(referer: String) = Headers.Builder()
		.add("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
		.add("Referer", referer)
		.add("User-Agent", config[userAgentKey])
		.build()

	private suspend fun loadEpisodes(animeId: String): List<MangaChapter> {
		val summary = runCatching {
			fetchDocument(
				"anime_list",
				animeId,
				"episodes_summery",
				"summery",
			)
		}.getOrNull()
		val summarizedEpisodes = parseEpisodes(animeId, summary?.optJSONObject("fields"))
		if (summarizedEpisodes.isNotEmpty()) {
			return summarizedEpisodes
		}

		val documents = runCatching {
			fetchCollection(EPISODES_PAGE_SIZE, "anime_list", animeId, "episodes")
		}.getOrDefault(emptyList())
		return parseEpisodeDocuments(animeId, documents)
	}

	private suspend fun loadServers(animeId: String, episodeId: String): List<JSONObject> {
		val authHeaders = firestoreAuthHeaders()
		val result = ArrayList<JSONObject>()
		try {
			fetchCollectionAuthenticated(
				SERVERS_PAGE_SIZE,
				authHeaders,
				"anime_list",
				animeId,
				"episodes",
				episodeId,
				"servers",
			).mapNotNullTo(result) { it.optJSONObject("fields") }
		} catch (error: CancellationException) {
			throw error
		} catch (error: AuthRequiredException) {
			throw error
		} catch (_: Exception) {
			// Older episodes can have only the compact servers2 document.
		}
		val summary = try {
			fetchDocumentAuthenticated(
				authHeaders,
				"anime_list",
				animeId,
				"episodes",
				episodeId,
				"servers2",
				"all_servers",
			)
		} catch (error: CancellationException) {
			throw error
		} catch (error: AuthRequiredException) {
			throw error
		} catch (_: Exception) {
			null
		}
		val values = summary?.optJSONObject("fields")?.firestoreArray("servers")
		if (values != null) {
			for (i in 0 until values.length()) {
				values.optJSONObject(i)
					?.optJSONObject("mapValue")
					?.optJSONObject("fields")
					?.let(result::add)
			}
		}
		return result.distinctBy { server -> server.firestoreString("link") }
	}

	private fun parseCatalogItem(item: JSONObject): Manga? {
		val animeId = item.optString("objectID").takeIf(String::isNotBlank) ?: return null
		val title = item.optString("name").takeIf(String::isNotBlank) ?: return null
		val details = item.optJSONObject("details")
		val tags = item.optJSONArray("tags").toStringSet().mapTo(LinkedHashSet()) {
			MangaTag(title = it, key = it, source = source)
		}
		val cover = item.optJSONObject("poster")?.optString("large")?.takeIf(String::isNotBlank)
			?: item.optJSONObject("aniList_poster")?.optString("large")?.takeIf(String::isNotBlank)
			?: item.optString("poster_uri").takeIf(String::isNotBlank)
		val rating = item.optJSONObject("rating")?.optDouble("rate", Double.NaN)
			?.takeUnless(Double::isNaN)
			?.let(::normalizeRating)
			?: RATING_UNKNOWN
		return Manga(
			id = generateUid(animeId),
			title = title,
			altTitles = setOfNotNull(
				details?.optString("english_title")?.takeIf(String::isNotBlank),
			),
			url = "$ANIME_PATH${animeId.urlEncoded()}",
			publicUrl = "https://$domain/",
			rating = rating,
			contentRating = parseContentRating(details?.optString("age"), tags),
			coverUrl = cover,
			tags = tags,
			state = parseState(details?.optString("state")),
			authors = details?.optJSONArray("studio").toStringSet(),
			description = item.optString("story").takeIf(String::isNotBlank),
			source = source,
		)
	}

	private fun parseEpisodes(animeId: String, fields: JSONObject?): List<MangaChapter> {
		val episodes = fields?.firestoreArray("episodes") ?: return emptyList()
		return buildList {
			for (i in 0 until episodes.length()) {
				val episode = episodes.optJSONObject(i)
					?.optJSONObject("mapValue")
					?.optJSONObject("fields")
					?: continue
				val episodeId = episode.firestoreString("doc_id") ?: continue
				parseEpisode(animeId, episodeId, episode, i)?.let(::add)
			}
		}.distinctBy(MangaChapter::id).sortedBy(MangaChapter::number)
	}

	private fun parseEpisodeDocuments(animeId: String, documents: List<JSONObject>): List<MangaChapter> =
		documents.mapIndexedNotNull { index, document ->
			val fields = document.optJSONObject("fields") ?: return@mapIndexedNotNull null
			val episodeId = fields.firestoreString("doc_id")
				?: document.optString("name").substringAfterLast('/').takeIf(String::isNotBlank)
				?: return@mapIndexedNotNull null
			parseEpisode(animeId, episodeId, fields, index)
		}.distinctBy(MangaChapter::id).sortedBy(MangaChapter::number)

	private fun parseEpisode(
		animeId: String,
		episodeId: String,
		fields: JSONObject,
		index: Int,
	): MangaChapter {
		val name = fields.firestoreString("name")
		val translatedTitle = fields.firestoreString("title_translated")
			?: fields.firestoreMap("title_translated")?.firestoreString("ar")
			?: fields.firestoreString("title_en")
		val number = firstNumber(name)
			?: firstNumber(episodeId)
			?: (index + 1).toFloat()
		return MangaChapter(
			id = generateUid("$animeId/$episodeId"),
			title = listOfNotNull(name, translatedTitle)
				.filter(String::isNotBlank)
				.distinct()
				.joinToString(" — ")
				.ifBlank { "الحلقة ${formatNumber(number)}" },
			number = number,
			volume = 0,
			url = "$EPISODE_PATH${animeId.urlEncoded()}/${episodeId.urlEncoded()}",
			scanlator = "AnimeWitcher",
			uploadDate = 0L,
			branch = null,
			source = source,
		)
	}

	private suspend fun fetchDocument(vararg segments: String): JSONObject {
		val builder = FIRESTORE_DOCUMENTS.toHttpUrl().newBuilder()
		for (segment in segments) {
			builder.addPathSegment(segment)
		}
		builder.addQueryParameter("key", FIREBASE_API_KEY)
		return webClient.httpGet(builder.build()).parseJson()
	}

	private suspend fun fetchCollection(pageSize: Int, vararg segments: String): List<JSONObject> {
		val result = ArrayList<JSONObject>()
		var pageToken: String? = null
		var page = 0
		do {
			val builder = FIRESTORE_DOCUMENTS.toHttpUrl().newBuilder()
			for (segment in segments) {
				builder.addPathSegment(segment)
			}
			builder.addQueryParameter("key", FIREBASE_API_KEY)
			builder.addQueryParameter("pageSize", pageSize.toString())
			pageToken?.let { builder.addQueryParameter("pageToken", it) }
			val response = webClient.httpGet(builder.build()).parseJson()
			val documents = response.optJSONArray("documents")
			if (documents != null) {
				for (i in 0 until documents.length()) {
					documents.optJSONObject(i)?.let(result::add)
				}
			}
			pageToken = response.optString("nextPageToken").takeIf(String::isNotBlank)
			page++
		} while (pageToken != null && page < MAX_FIRESTORE_PAGES)
		return result
	}

	private suspend fun fetchDocumentAuthenticated(
		headers: Headers,
		vararg segments: String,
	): JSONObject {
		val builder = FIRESTORE_DOCUMENTS.toHttpUrl().newBuilder()
		for (segment in segments) {
			builder.addPathSegment(segment)
		}
		builder.addQueryParameter("key", FIREBASE_API_KEY)
		return webClient.httpGet(builder.build(), headers).parseJson()
	}

	private suspend fun fetchCollectionAuthenticated(
		pageSize: Int,
		headers: Headers,
		vararg segments: String,
	): List<JSONObject> {
		val result = ArrayList<JSONObject>()
		var pageToken: String? = null
		var page = 0
		do {
			val builder = FIRESTORE_DOCUMENTS.toHttpUrl().newBuilder()
			for (segment in segments) {
				builder.addPathSegment(segment)
			}
			builder.addQueryParameter("key", FIREBASE_API_KEY)
			builder.addQueryParameter("pageSize", pageSize.toString())
			pageToken?.let { builder.addQueryParameter("pageToken", it) }
			val response = webClient.httpGet(builder.build(), headers).parseJson()
			val documents = response.optJSONArray("documents")
			if (documents != null) {
				for (i in 0 until documents.length()) {
					documents.optJSONObject(i)?.let(result::add)
				}
			}
			pageToken = response.optString("nextPageToken").takeIf(String::isNotBlank)
			page++
		} while (pageToken != null && page < MAX_FIRESTORE_PAGES)
		return result
	}

	private suspend fun firestoreAuthHeaders(): Headers = authMutex.withLock {
		var token = validIdTokenLocked(forceRefresh = false)
			?: throw AuthRequiredException(source)
		val account = if (readCookie(COOKIE_EMAIL_VERIFIED) == "1") {
			null
		} else {
			token = validIdTokenLocked(forceRefresh = true)
				?: throw AuthRequiredException(source)
			lookupAccount(token)
		}
		if (account != null && !account.isEmailVerified) {
			throw AuthRequiredException(source)
		}
		if (account == null && readCookie(COOKIE_EMAIL_VERIFIED) != "1") {
			throw AuthRequiredException(source)
		}
		Headers.Builder()
			.add("Authorization", "Bearer $token")
			.add("Accept", "application/json")
			.build()
	}

	private suspend fun getAccountLocked(forceRefresh: Boolean): MangaAuthAccount? {
		val token = try {
			validIdTokenLocked(forceRefresh)
		} catch (error: MangaAuthException) {
			if (error.code in SESSION_INVALID_ERRORS) {
				clearSession()
				return null
			}
			throw error
		} ?: return null
		return try {
			lookupAccount(token)
		} catch (error: MangaAuthException) {
			if (error.code in SESSION_INVALID_ERRORS) {
				clearSession()
				null
			} else {
				throw error
			}
		}
	}

	private suspend fun validIdTokenLocked(forceRefresh: Boolean): String? {
		if (!forceRefresh) {
			readCookie(COOKIE_ID_TOKEN)?.let { return it }
		}
		val refreshToken = readCookie(COOKIE_REFRESH_TOKEN) ?: return null
		val form = FormBody.Builder()
			.add("grant_type", "refresh_token")
			.add("refresh_token", refreshToken)
			.build()
		val request = Request.Builder()
			.url("$SECURE_TOKEN_ENDPOINT?key=$FIREBASE_API_KEY")
			.post(form)
			.build()
		val response = executeJsonRequest(request)
			?: throw MangaAuthException("EMPTY_RESPONSE")
		saveSession(response)
		return response.optString("id_token").takeIf(String::isNotBlank)
			?: response.optString("idToken").takeIf(String::isNotBlank)
	}

	private suspend fun lookupAccount(idToken: String): MangaAuthAccount? {
		val response = authPost(
			endpoint = "accounts:lookup",
			body = JSONObject().put("idToken", idToken),
		)
		val user = response.optJSONArray("users")?.optJSONObject(0) ?: return null
		val email = user.optString("email").takeIf(String::isNotBlank)
			?: decodeCookie(readCookie(COOKIE_EMAIL))
			?: return null
		val displayName = user.optString("displayName").takeIf(String::isNotBlank)
			?: decodeCookie(readCookie(COOKIE_DISPLAY_NAME))
		val account = MangaAuthAccount(
			username = email,
			displayName = displayName,
			isEmailVerified = user.optBoolean("emailVerified", false),
		)
		saveLongLivedCookie(COOKIE_EMAIL, encodeCookie(email))
		displayName?.let { saveLongLivedCookie(COOKIE_DISPLAY_NAME, encodeCookie(it)) }
		saveLongLivedCookie(COOKIE_EMAIL_VERIFIED, if (account.isEmailVerified) "1" else "0")
		return account
	}

	private suspend fun sendVerificationEmailLocked(idToken: String) {
		authPost(
			endpoint = "accounts:sendOobCode",
			body = JSONObject()
				.put("requestType", "VERIFY_EMAIL")
				.put("idToken", idToken),
		)
	}

	private suspend fun ensureUserDocument(
		uid: String,
		email: String,
		displayName: String,
		idToken: String,
	) {
		val documentUrl = FIRESTORE_DOCUMENTS.toHttpUrl().newBuilder()
			.addPathSegment("users")
			.addPathSegment(uid)
			.addQueryParameter("key", FIREBASE_API_KEY)
			.build()
		val getRequest = Request.Builder()
			.url(documentUrl)
			.header("Authorization", "Bearer $idToken")
			.get()
			.build()
		if (executeJsonRequest(getRequest, allowNotFound = true) != null) {
			return
		}

		val createUrl = FIRESTORE_DOCUMENTS.toHttpUrl().newBuilder()
			.addPathSegment("users")
			.addQueryParameter("documentId", uid)
			.addQueryParameter("key", FIREBASE_API_KEY)
			.build()
		val fields = JSONObject()
			.put("uid", firestoreString(uid))
			.put("email", firestoreString(email))
			.put("user_name", firestoreString(displayName))
			.put("banned", JSONObject().put("booleanValue", false))
			.put("app_version_code", JSONObject().put("integerValue", "0"))
			.put("registration_date", JSONObject().put("timestampValue", firestoreTimestamp()))
			.put("statistics", emptyFirestoreMap())
			.put("settings", emptyFirestoreMap())
			.put("statistics_user_anime", emptyFirestoreMap())
		val requestBody = JSONObject()
			.put("fields", fields)
			.toString()
			.toRequestBody(JSON_MEDIA_TYPE)
		val createRequest = Request.Builder()
			.url(createUrl)
			.header("Authorization", "Bearer $idToken")
			.post(requestBody)
			.build()
		try {
			executeJsonRequest(createRequest)
		} catch (error: MangaAuthException) {
			if (error.code != "ALREADY_EXISTS") throw error
		}
	}

	private suspend fun authPost(endpoint: String, body: JSONObject): JSONObject {
		val request = Request.Builder()
			.url("$IDENTITY_TOOLKIT_ENDPOINT/$endpoint?key=$FIREBASE_API_KEY")
			.post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
			.build()
		return executeJsonRequest(request) ?: throw MangaAuthException("EMPTY_RESPONSE")
	}

	private suspend fun executeJsonRequest(
		request: Request,
		allowNotFound: Boolean = false,
	): JSONObject? {
		val response = context.httpClient.newCall(request).await()
		response.use {
			val payload = it.body.string()
			val json = payload.takeIf(String::isNotBlank)?.let(::JSONObject) ?: JSONObject()
			if (it.isSuccessful) return json
			if (allowNotFound && it.code == 404) return null
			val error = json.optJSONObject("error")
			val rawCode = error?.optString("message").orEmpty()
			val code = rawCode.substringBefore(" : ")
				.ifBlank { error?.optString("status").orEmpty() }
				.ifBlank { "HTTP_${it.code}" }
			throw MangaAuthException(code, rawCode.ifBlank { it.message })
		}
	}

	private fun saveSession(response: JSONObject, displayName: String? = null) {
		val idToken = response.optString("idToken").takeIf(String::isNotBlank)
			?: response.optString("id_token").takeIf(String::isNotBlank)
			?: throw MangaAuthException("MISSING_ID_TOKEN")
		val refreshToken = response.optString("refreshToken").takeIf(String::isNotBlank)
			?: response.optString("refresh_token").takeIf(String::isNotBlank)
			?: readCookie(COOKIE_REFRESH_TOKEN)
			?: throw MangaAuthException("MISSING_REFRESH_TOKEN")
		val expiresIn = response.optString("expiresIn")
			.ifBlank { response.optString("expires_in") }
			.toLongOrNull()
			?: DEFAULT_TOKEN_LIFETIME_SECONDS
		saveCookie(
			name = COOKIE_ID_TOKEN,
			value = idToken,
			expiresAt = System.currentTimeMillis() + (expiresIn - TOKEN_EXPIRY_SKEW_SECONDS)
				.coerceAtLeast(MIN_TOKEN_LIFETIME_SECONDS) * 1000L,
		)
		saveLongLivedCookie(COOKIE_REFRESH_TOKEN, refreshToken)
		displayName?.takeIf(String::isNotBlank)?.let {
			saveLongLivedCookie(COOKIE_DISPLAY_NAME, encodeCookie(it))
		}
		response.optString("email").takeIf(String::isNotBlank)?.let {
			saveLongLivedCookie(COOKIE_EMAIL, encodeCookie(it))
		}
	}

	private fun clearSession() {
		for (name in AUTH_COOKIE_NAMES) {
			saveCookie(name, "", expiresAt = 0L)
		}
	}

	private fun readCookie(name: String): String? =
		context.cookieJar.loadForRequest(AUTH_COOKIE_URL)
			.firstOrNull { it.name == name }
			?.value
			?.takeIf(String::isNotBlank)

	private fun saveLongLivedCookie(name: String, value: String) {
		saveCookie(name, value, System.currentTimeMillis() + AUTH_COOKIE_LIFETIME_MS)
	}

	private fun saveCookie(name: String, value: String, expiresAt: Long) {
		context.cookieJar.saveFromResponse(
			AUTH_COOKIE_URL,
			listOf(
				Cookie.Builder()
					.name(name)
					.value(value)
					.hostOnlyDomain(AUTH_COOKIE_URL.host)
					.path("/")
					.expiresAt(expiresAt)
					.secure()
					.httpOnly()
					.build(),
			),
		)
	}

	private fun encodeCookie(value: String): String =
		context.encodeBase64(value.toByteArray(StandardCharsets.UTF_8))

	private fun decodeCookie(value: String?): String? = value?.let {
		runCatching {
			String(context.decodeBase64(it), StandardCharsets.UTF_8)
		}.getOrNull()
	}

	private fun firestoreTimestamp(): String = SimpleDateFormat(
		"yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
		Locale.US,
	).apply {
		timeZone = TimeZone.getTimeZone("UTC")
	}.format(Date())

	private fun firestoreString(value: String): JSONObject =
		JSONObject().put("stringValue", value)

	private fun emptyFirestoreMap(): JSONObject =
		JSONObject().put("mapValue", JSONObject().put("fields", JSONObject()))

	private fun parseContentRating(
		age: String?,
		tags: Set<MangaTag>,
	): ContentRating = if (
		age?.contains("18") == true ||
		tags.any { it.title.contains("هنتاي") || it.title.contains("بالغ") }
	) {
		ContentRating.ADULT
	} else {
		ContentRating.SAFE
	}

	private fun parseState(value: String?): MangaState? = when {
		value.isNullOrBlank() -> null
		value.contains("مكتمل") || value.contains("منتهي") || value.contains("finished", true) ->
			MangaState.FINISHED
		value.contains("قادم") || value.contains("upcoming", true) -> MangaState.UPCOMING
		else -> MangaState.ONGOING
	}

	private fun displayServerName(value: String?): String? = when (value) {
		"PD" -> "PixelDrain"
		"KF" -> "KrakenFiles"
		"MF" -> "MediaFire"
		else -> value?.takeIf(String::isNotBlank)
	}

	private fun firstNumber(value: String?): Float? =
		value?.let { NUMBER.find(it)?.value?.toFloatOrNull() }

	private fun formatNumber(value: Float): String =
		if (value % 1f == 0f) value.toInt().toString() else value.toString()

	private fun decode(value: String): String = URLDecoder.decode(value, "UTF-8")

	internal companion object {
		private const val PAGE_SIZE = 30
		private const val EPISODES_PAGE_SIZE = 1000
		private const val SERVERS_PAGE_SIZE = 100
		private const val MAX_FIRESTORE_PAGES = 20
		private const val ANIME_PATH = "/anime/"
		private const val EPISODE_PATH = "/episode/"
		internal fun algoliaReadHosts(appId: String) = listOf(
			"$appId-dsn.algolia.net",
			"$appId-1.algolianet.com",
			"$appId-2.algolianet.com",
			"$appId-3.algolianet.com",
		)

		internal fun extractAlgoliaCatalogCredentials(document: JSONObject): Pair<String, String>? {
			val settings = document.optJSONObject("fields")?.firestoreMap("search_settings") ?: return null
			val appId = settings.firestoreString("app_id_v4") ?: return null
			val browseKey = settings.firestoreString("browse_api_key") ?: return null
			if (!appId.matches(Regex("[A-Za-z0-9]+"))) return null
			return appId to browseKey
		}
		private const val FIREBASE_API_KEY = "AIzaSyAcbWRwfFNnCpoydDXlEALWnM_TYVcJOMU"
		private const val FIRESTORE_DOCUMENTS =
			"https://firestore.googleapis.com/v1/projects/animewitcher-1c66d/databases/(default)/documents"
		private const val IDENTITY_TOOLKIT_ENDPOINT =
			"https://identitytoolkit.googleapis.com/v1"
		private const val SECURE_TOKEN_ENDPOINT =
			"https://securetoken.googleapis.com/v1/token"
		private const val COOKIE_ID_TOKEN = "aw_id_token"
		private const val COOKIE_REFRESH_TOKEN = "aw_refresh_token"
		private const val COOKIE_EMAIL = "aw_email"
		private const val COOKIE_DISPLAY_NAME = "aw_display_name"
		private const val COOKIE_EMAIL_VERIFIED = "aw_email_verified"
		private const val DEFAULT_TOKEN_LIFETIME_SECONDS = 3600L
		private const val TOKEN_EXPIRY_SKEW_SECONDS = 60L
		private const val MIN_TOKEN_LIFETIME_SECONDS = 30L
		private const val AUTH_COOKIE_LIFETIME_MS = 10L * 365L * 24L * 60L * 60L * 1000L
		// An internal-only cookie scope keeps session tokens in the app's cookie storage
		// without ever attaching them to requests made to AnimeWitcher's public website.
		private val AUTH_COOKIE_URL = "https://animewitcher-auth.invalid/".toHttpUrl()
		private val AUTH_COOKIE_NAMES = arrayOf(
			COOKIE_ID_TOKEN,
			COOKIE_REFRESH_TOKEN,
			COOKIE_EMAIL,
			COOKIE_DISPLAY_NAME,
			COOKIE_EMAIL_VERIFIED,
		)
		private val SESSION_INVALID_ERRORS = setOf(
			"INVALID_ID_TOKEN",
			"INVALID_REFRESH_TOKEN",
			"TOKEN_EXPIRED",
			"USER_DISABLED",
			"USER_NOT_FOUND",
		)
		private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
		private val NUMBER = Regex("""\d+(?:\.\d+)?""")
		private val PIXEL_DRAIN = Regex(
			"""^https?://(?:www\.)?pixeldrain\.com/u/([A-Za-z0-9]+)(?:[/?#].*)?$""",
			RegexOption.IGNORE_CASE,
		)
		private val DIRECT_VIDEO = Regex(
			"""\.(?:m3u8|mp4)(?:[?#].*)?$""",
			RegexOption.IGNORE_CASE,
		)
		private val DIRECT_VIDEO_IN_PAGE = Regex(
			"""https?:\\?/\\?/[^'"<>\s]+\.(?:m3u8|mp4)(?:\?[^'"<>\s]*)?""",
			RegexOption.IGNORE_CASE,
		)
		private val STREAM_TAPE_FRAGMENT = Regex(
			"""get_video\?id=([^'"<\s\\]+)""",
			RegexOption.IGNORE_CASE,
		)
		private val ALGOLIA_ATTRIBUTES = JSONArray(
			listOf(
				"objectID",
				"name",
				"tags",
				"poster_uri",
				"poster",
				"aniList_poster",
				"details",
				"rating",
				"type",
				"story",
			),
		)

		internal fun toDirectVideoUrl(link: String, directLink: Boolean): String? {
			val normalized = link.trim()
			if (!normalized.startsWith("https://") && !normalized.startsWith("http://")) {
				return null
			}
			PIXEL_DRAIN.matchEntire(normalized)?.groupValues?.getOrNull(1)?.let {
				return "https://pixeldrain.com/api/file/$it"
			}
			if (!directLink && isKnownEmbedPage(normalized)) {
				return null
			}
			return normalized.takeIf { directLink || DIRECT_VIDEO.containsMatchIn(it) }
		}

		private fun isKnownEmbedPage(link: String): Boolean = runCatching {
			val url = link.toHttpUrl()
			when {
				url.host == "streamtape.com" || url.host.endsWith(".streamtape.com") -> true
				url.host == "krakenfiles.com" || url.host.endsWith(".krakenfiles.com") ->
					url.encodedPath.startsWith("/view/")
				url.host == "mediafire.com" || url.host.endsWith(".mediafire.com") ->
					url.encodedPath.startsWith("/file/")
				else -> false
			}
		}.getOrDefault(true)

		internal fun findDirectVideoInPage(raw: String): String? =
			DIRECT_VIDEO_IN_PAGE.find(raw)?.value?.decodeHtmlUrl()

		private fun String.decodeHtmlUrl(): String = this
			.replace("\\/", "/")
			.replace("\\u0026", "&")
			.replace("&amp;", "&")
			.replace("&#038;", "&")
			.let { if (it.startsWith("//")) "https:$it" else it }

		private fun normalizeRating(value: Double): Float =
			(value / 10.0).coerceIn(0.0, 1.0).toFloat()
	}
}

private fun JSONObject.firestoreString(name: String): String? =
	optJSONObject(name)?.optString("stringValue")?.takeIf(String::isNotBlank)

private fun JSONObject.firestoreBoolean(name: String): Boolean? {
	val field = optJSONObject(name) ?: return null
	return if (field.has("booleanValue")) field.optBoolean("booleanValue") else null
}

private fun JSONObject.firestoreNumber(name: String): Double? {
	val field = optJSONObject(name) ?: return null
	return when {
		field.has("doubleValue") -> field.optDouble("doubleValue").takeUnless(Double::isNaN)
		field.has("integerValue") -> field.optString("integerValue").toDoubleOrNull()
		else -> null
	}
}

private fun JSONObject.firestoreMap(name: String): JSONObject? =
	optJSONObject(name)?.optJSONObject("mapValue")?.optJSONObject("fields")

private fun JSONObject.firestoreArray(name: String): JSONArray? =
	optJSONObject(name)?.optJSONObject("arrayValue")?.optJSONArray("values")

private fun JSONObject.firestoreStrings(name: String): List<String> {
	val values = firestoreArray(name) ?: return emptyList()
	return buildList {
		for (i in 0 until values.length()) {
			values.optJSONObject(i)?.optString("stringValue")
				?.takeIf(String::isNotBlank)
				?.let(::add)
		}
	}
}

private fun JSONArray?.toStringSet(): Set<String> {
	if (this == null) return emptySet()
	return buildSet {
		for (i in 0 until length()) {
			optString(i).takeIf(String::isNotBlank)?.let(::add)
		}
	}
}
