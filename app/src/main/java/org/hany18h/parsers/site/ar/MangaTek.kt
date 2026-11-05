package org.hany18h.parsers.site.ar

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.hany18h.parsers.MangaLoaderContext
import org.hany18h.parsers.MangaSourceParser
import org.hany18h.parsers.config.ConfigKey
import org.hany18h.parsers.core.PagedMangaParser
import org.hany18h.parsers.exception.ContentUnavailableException
import org.hany18h.parsers.model.*
import org.hany18h.parsers.util.*
import java.text.SimpleDateFormat
import java.security.MessageDigest
import java.util.*
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@MangaSourceParser("MANGATEK", "MangaTek", "ar", ContentType.MANGA)
internal class MangaTek(private val loaderContext: MangaLoaderContext) :
    PagedMangaParser(loaderContext, MangaParserSource.MANGATEK, pageSize = 24) {

    override val configKeyDomain = ConfigKey.Domain("mangatek.com")
    override val userAgentKey = ConfigKey.UserAgent(USER_AGENT)

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
        keys.add(ConfigKey.InterceptCloudflare(defaultValue = true))
    }

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = false,
            isMultipleTagsSupported = false,
            isTagsExclusionSupported = false,
        )

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.ALPHABETICAL,
    )

    override suspend fun getFilterOptions() = MangaListFilterOptions()

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val query = filter.query?.trim().orEmpty()
        val url = buildString {
            append("https://")
            append(domain)
            append("/manga-list")
            
            when {
                query.isNotEmpty() -> {
                    append("?search=")
                    append(query.urlEncoded())
                }
                else -> {
                    append("?sort=")
                    append(
                        when (order) {
                            SortOrder.POPULARITY -> "views"
                            SortOrder.ALPHABETICAL -> "title&sortOrder=ASC"
                            else -> "latest"
                        }
                    )
                }
            }
            
            if (page > 1) {
                append("&page=")
                append(page)
            }
        }

        val doc = loadDocument(url, if (query.isEmpty()) "a.manga-card" else "main h1")
        
        // إزالة العناصر المزعجة
        cleanDocument(doc)
        
        return doc.select("div.grid a.manga-card").mapNotNull { card ->
            val link = card.attr("href")
            if (link.isEmpty()) return@mapNotNull null
            
            val slug = link.removePrefix("/manga/")
            
            val title = card.selectFirst("h3")?.text()?.trim()
            if (title.isNullOrEmpty()) return@mapNotNull null
            
            val ratingElement = card.selectFirst("span:has(i.fa-star) > span:not(:has(i))")
            val rating = ratingElement?.text()?.toFloatOrNull()?.div(10) ?: RATING_UNKNOWN
            
            Manga(
                id = generateUid(slug),
                url = slug,
                publicUrl = "https://$domain$link",
                title = title,
                coverUrl = card.selectFirst("img")?.src(),
                altTitles = emptySet(),
                rating = rating,
                tags = emptySet(),
                authors = emptySet(),
                state = null,
                source = source,
                contentRating = ContentRating.SAFE,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val url = "https://$domain/manga/${manga.url}"
        val doc = loadDocument(url, "astro-island[component-url*='MangaChaptersLoader'], a[href^='/reader/']")
        
        // إزالة العناصر المزعجة
        cleanDocument(doc)
        
        val title = doc.selectFirst("h1")?.text() ?: manga.title
        
        // تحسين استخراج الوصف مع تنظيفه من الرسائل غير المرغوب فيها
        val description = extractCleanDescription(doc)
        
        val statusText = doc.selectFirst("span.border")?.text()
        val state = when {
            statusText?.contains("مستمر") == true -> MangaState.ONGOING
            statusText?.contains("مكتمل") == true -> MangaState.FINISHED
            statusText?.contains("متوقف") == true -> MangaState.PAUSED
            else -> null
        }
        
        val tags = doc.select("div.flex.gap-2 span.text-gray-300").mapNotNullToSet { tag ->
            val tagName = tag.text().trim()
            if (tagName.isEmpty()) return@mapNotNullToSet null
            MangaTag(
                key = tagName,
                title = tagName,
                source = source
            )
        }
        
        val ratingText = doc.selectFirst("span:has(i.fa-star)")?.text()
        val rating = ratingText?.replace(Regex("[^0-9.]"), "")?.toFloatOrNull()?.div(10) ?: manga.rating
        
        val chapters = parseChapters(manga.url, doc)
        
        return manga.copy(
            title = title,
            description = description,
            state = state,
            tags = tags,
            rating = rating,
            chapters = chapters,
        )
    }

    /**
     * تنظيف الوثيقة من العناصر المزعجة
     */
    private fun cleanDocument(doc: org.jsoup.nodes.Document) {
        // إزالة إشعارات مانع الإعلانات
        doc.select("[class*='adblock'], [id*='adblock'], [class*='ad-block'], [id*='ad-block']").remove()
        
        // إزالة الإشعارات والتنبيهات
        doc.select(".alert, .notice, .warning, .notification").remove()
        
        // إزالة overlays و modals
        doc.select(".overlay, .modal, .popup, [class*='overlay'], [id*='overlay']").remove()
        
        // إزالة الإعلانات
        doc.select(".ad, .ads, .advertisement, [class*='ad-'], [id*='ad-']").remove()
        
        // إزالة scripts غير ضرورية
        doc.select("script:not([src])").remove()
    }

    /**
     * استخراج وصف نظيف بدون رسائل مزعجة
     */
    private fun extractCleanDescription(doc: org.jsoup.nodes.Document): String? {
        val descriptionElement = doc.selectFirst("div.grid p, p.text-gray-300, div.description, div.synopsis")
        
        if (descriptionElement != null) {
            var description = descriptionElement.text().trim()
            
            // إزالة الجمل المتعلقة بمانع الإعلانات والرسائل المزعجة
            val unwantedPhrases = listOf(
                "يرجى تعطيل مانع الإعلانات",
                "قم بإيقاف مانع الإعلانات",
                "Please disable",
                "AdBlock",
                "ad blocker",
                "turn off your ad blocker",
                "disable your adblocker",
                "يبدو أنك تستخدم",
                "نرجو منك",
                "للمتابعة",
                "to continue"
            )
            
            for (phrase in unwantedPhrases) {
                // إزالة الجملة الكاملة التي تحتوي على العبارة
                val regex = Regex("[^.!?]*$phrase[^.!?]*[.!?]?", RegexOption.IGNORE_CASE)
                description = description.replace(regex, "")
            }
            
            // تنظيف المسافات الزائدة
            description = description.replace(Regex("\\s+"), " ").trim()
            
            return if (description.isNotEmpty()) description else null
        }
        
        return null
    }

    private fun parseChapters(mangaSlug: String, doc: Document): List<MangaChapter> {
        val scriptContent = doc.select("astro-island[component-url*='MangaChaptersLoader']")
            .attr("props")
        
        if (scriptContent.isEmpty()) {
            return parseChaptersFromHtml(doc)
        }
        
        return try {
            val chapters = mutableListOf<MangaChapter>()
            
            // استخراج الفصول من JSON
            val chapterPattern = """"chapter_number"\s*:\s*\[0,\s*"([^"]+)"\]""".toRegex()
            val chapterMatches = chapterPattern.findAll(scriptContent)
            
            chapterMatches.forEach { match ->
                val chapterNum = match.groupValues[1]
                
                chapters.add(
                    MangaChapter(
                        id = generateUid("$mangaSlug-$chapterNum"),
                        title = "الفصل $chapterNum",
                        number = chapterNum.toFloatOrNull() ?: 0f,
                        volume = 0,
                        url = "/reader/$mangaSlug/$chapterNum",
                        uploadDate = 0L,
                        source = source,
                        scanlator = null,
                        branch = null,
                    )
                )
            }
            
            chapters.reversed()
        } catch (e: Exception) {
            parseChaptersFromHtml(doc)
        }
    }

    /**
     * Fallback: تحليل الفصول من HTML مباشرة
     */
    private fun parseChaptersFromHtml(doc: org.jsoup.nodes.Document): List<MangaChapter> {
        return doc.select("div.manga-chapter a, div.grid a[href^='/reader/']").mapNotNull { element ->
            val chapterUrl = element.attr("href")
            if (chapterUrl.isEmpty()) return@mapNotNull null
            
            val chapterTitle = element.selectFirst("h3")?.text() ?: "Chapter"
            val chapterNumber = chapterTitle
                .replace(Regex("[^0-9.]"), "")
                .toFloatOrNull() ?: 0f
            
            val dateText = element.selectFirst("span:has(i.fa-calendar-alt)")?.text()
                ?: element.selectFirst("p.text-sm")?.text()
            
            MangaChapter(
                id = generateUid(chapterUrl),
                title = chapterTitle,
                number = chapterNumber,
                volume = 0,
                url = chapterUrl,
                uploadDate = parseDate(dateText),
                source = source,
                scanlator = null,
                branch = null,
            )
        }.reversed()
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val fullUrl = chapter.url.toAbsoluteUrl(domain)
        val doc = loadDocument(fullUrl, "div.manga-page img[src], div.manga-page img[data-src], div.manga-page img[data-url]")
        
        val imageUrls = extractReaderImageUrls(doc, domain)
        if (imageUrls.isEmpty()) throw ContentUnavailableException("MangaTek did not return any reader images")
        // Do not silently return text-free images if unlocking fails. Such pages
        // would otherwise be cached/downloaded as successfully completed chapters.
        val overlayPages = loadOverlayPages(doc, imageUrls.size)
        return imageUrls.mapIndexed { index, imageUrl ->
            MangaPage(
                id = generateUid("${chapter.id}-$index"),
                url = overlayPages[index]?.let { buildOverlayPageUrl(imageUrl, it) } ?: imageUrl,
                preview = null,
                source = source,
            )
        }
    }

    override suspend fun getPageUrl(page: MangaPage): String = if (
        page.url.startsWith(OVERLAY_PAGE_SCHEME, ignoreCase = true)
    ) page.url else super.getPageUrl(page)

    private suspend fun loadOverlayPages(document: Document, imageCount: Int): Map<Int, JSONObject> {
        val rawProps = document.selectFirst("astro-island[component-url*='ChapterImageViewer']")?.attr("props")
            ?.takeIf(String::isNotBlank) ?: return emptyMap()
        val props = JSONObject(rawProps)
        if (props.optJSONArray("textMode")?.optString(1) != "overlay") return emptyMap()
        val token = props.optJSONArray("unlockToken")?.optString(1).orEmpty()
        val chapterId = props.optJSONArray("chapterId")?.optLong(1) ?: 0L
        if (token.isEmpty() || chapterId <= 0L) {
            throw ContentUnavailableException("MangaTek speech text is missing its unlock token; reload the chapter")
        }
        val payload = JSONObject().put("chapterId", chapterId).put("token", token)
            .put("proof", computeUnlockProof(token, chapterId))
        val headers = siteHeaders("https://$domain/").newBuilder().set("Accept", "application/json").build()
        val response = webClient.httpPost(OVERLAY_UNLOCK_URL.toHttpUrl(), payload, headers).parseJson()
        if (!response.optBoolean("success")) {
            throw ContentUnavailableException("MangaTek could not unlock speech text; retry the chapter")
        }
        val encrypted = response.optString("overlay")
        val key = response.optString("key")
        if (encrypted.isBlank() || key.isBlank()) {
            throw ContentUnavailableException("MangaTek returned no speech text; retry the chapter")
        }
        val pages = decryptOverlayPages(encrypted, key)
        if (pages.length() == 0) throw ContentUnavailableException("MangaTek speech overlay is empty")
        val offset = response.optInt("overlay_page_offset", props.optJSONArray("overlayPageOffset")?.optInt(1) ?: 0)
        val overlays = buildMap {
            repeat(pages.length()) { position ->
                val page = pages.getJSONObject(position)
                val imageIndex = page.optInt("page_number", position + 1) + offset - 1
                if ((page.optJSONArray("overlays")?.length() ?: 0) > 0) {
                    if (imageIndex !in 0 until imageCount) {
                        throw ContentUnavailableException("MangaTek speech text does not match the reader images")
                    }
                    put(imageIndex, page)
                }
            }
        }
        if (overlays.isEmpty()) throw ContentUnavailableException("MangaTek returned no speech overlays")
        return overlays
    }

    /**
     * The site's current public pages reject the older Chrome 114 identity with
     * HTTP 403. Try the current browser identity over HTTP first, then let
     * WebView load the same public page if the HTTP client is still challenged.
     */
    private suspend fun loadDocument(url: String, readySelector: String): Document {
        val directResult = runCatchingCancellable {
            webClient.httpGet(url, siteHeaders("https://$domain/")).parseHtml()
        }
        directResult.getOrNull()?.takeIf { isUsablePage(it, readySelector) }?.let { return it }

        val quotedSelector = JSONObject.quote(readySelector)
        val webViewResult = runCatchingCancellable {
            val rawResult = loaderContext.evaluateJs(
                url,
                """
                (function() {
                  if (document.readyState === 'loading') return null;
                  if (!document.querySelector($quotedSelector)) return null;
                  return document.documentElement ? document.documentElement.outerHTML : null;
                })()
                """.trimIndent(),
            ) ?: return@runCatchingCancellable null
            decodeWebViewString(rawResult)?.let { Jsoup.parse(it, url) }
        }
        webViewResult.getOrNull()?.takeIf { isUsablePage(it, readySelector) }?.let { return it }

        webViewResult.exceptionOrNull()?.let { throw it }
        directResult.exceptionOrNull()?.let { throw it }
        error("MangaTek returned an incomplete page or a CAPTCHA instead of public content")
    }

    internal fun isUsablePage(document: Document, readySelector: String): Boolean =
        !isCaptchaPage(document) && document.selectFirst(readySelector) != null

    internal fun decodeWebViewString(rawResult: String): String? = runCatching {
        JSONObject("{\"value\":$rawResult}").optString("value").trim().takeIf(String::isNotEmpty)
    }.getOrNull()

    internal fun isCaptchaPage(document: Document): Boolean {
        val text = (document.title() + " " + document.text()).lowercase(Locale.ROOT)
        return CAPTCHA_MARKERS.any(text::contains)
    }

    private fun siteHeaders(referer: String): Headers = Headers.Builder()
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .add("Accept-Language", "ar,en-US;q=0.7,en;q=0.3")
        .add("Referer", referer)
        .add("Upgrade-Insecure-Requests", "1")
        .add("User-Agent", config[userAgentKey])
        .build()

    private fun parseDate(dateText: String?): Long {
        if (dateText.isNullOrEmpty()) return 0L
        
        return try {
            val formats = listOf(
                SimpleDateFormat("dd/MM/yyyy", Locale.US),
                SimpleDateFormat("yyyy-MM-dd", Locale.US),
            )
            
            for (format in formats) {
                try {
                    return format.parse(dateText)?.time ?: 0L
                } catch (_: Exception) {
                    continue
                }
            }
            0L
        } catch (e: Exception) {
            0L
        }
    }

    internal companion object {
        private const val OVERLAY_UNLOCK_URL = "https://api.mangatek.com/api/reader/unlock"
        private const val OVERLAY_PAGE_SCHEME = "mangatek-overlay://render"
        private const val READER_PROOF_SALT = "80c55d2d1f1432551b82f5304b60e1aced87f823a6ed309d"

        internal fun extractReaderImageUrls(document: Document, domain: String): List<String> = document
            .select("div.manga-page img[data-url], div.manga-page img[data-src], div.manga-page img[src]")
            .mapNotNull { image ->
                sequenceOf("data-url", "data-src", "src").map { image.attr(it).trim() }
                    .firstOrNull { it.isNotEmpty() && !it.startsWith("data:", ignoreCase = true) }
                    ?.toAbsoluteUrl(domain)
            }

        internal fun computeUnlockProof(token: String, chapterId: Long): String = MessageDigest.getInstance("SHA-256")
            .digest("$READER_PROOF_SALT|$token|$chapterId".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

        internal fun decryptOverlayPages(encrypted: String, keyHex: String) = encrypted.split(':').let { parts ->
            require(parts.size == 3) { "Invalid MangaTek overlay payload" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyHex.decodeHex(), "AES"), GCMParameterSpec(128, parts[0].decodeHex()))
            JSONObject(String(cipher.doFinal(parts[1].decodeHex() + parts[2].decodeHex()), Charsets.UTF_8)).getJSONArray("pages")
        }

        internal fun buildOverlayPageUrl(imageUrl: String, overlay: JSONObject): String {
            val encoder = Base64.getUrlEncoder().withoutPadding()
            val image = encoder.encodeToString(imageUrl.toByteArray(Charsets.UTF_8))
            val text = encoder.encodeToString(overlay.toString().toByteArray(Charsets.UTF_8))
            return "$OVERLAY_PAGE_SCHEME?image=$image&overlay=$text"
        }

        private fun String.decodeHex(): ByteArray {
            require(length % 2 == 0) { "Invalid MangaTek hexadecimal payload" }
            return ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }

        internal const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        private val CAPTCHA_MARKERS = listOf(
            "captcha",
            "cf-turnstile",
            "verify you are human",
            "التحقق من أنك إنسان",
            "مطلوب التحقق",
        )
    }
}
