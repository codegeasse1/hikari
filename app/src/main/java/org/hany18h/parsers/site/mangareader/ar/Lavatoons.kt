package org.hany18h.parsers.site.mangareader.ar

import org.hany18h.parsers.MangaLoaderContext
import org.hany18h.parsers.MangaSourceParser
import org.hany18h.parsers.model.*
import org.hany18h.parsers.site.mangareader.MangaReaderParser
import org.hany18h.parsers.util.*
import java.text.SimpleDateFormat

@MangaSourceParser("LAVATOONS", "Lavatoons", "ar", ContentType.MANGA)
internal class Lavatoons(context: MangaLoaderContext) :
    MangaReaderParser(
        context,
        MangaParserSource.LAVATOONS,
        "lavascans.com",
        pageSize = 20,
        searchPageSize = 10,
    ) {
    
    override val isNetShieldProtected = true
    
    // تغيير URL القائمة
    override val listUrl = "/browse-manga"
    
    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildString {
            append("https://")
            append(domain)

            when {
                !filter.query.isNullOrEmpty() -> {
                    // البحث يستخدم الجذر مباشرة
                    append("/?s=")
                    append(filter.query.urlEncoded())
                    append("&page=")
                    append(page.toString())
                }

                else -> {
                    append(listUrl)
                    append("/?order=")
                    append(
                        when (order) {
                            SortOrder.ALPHABETICAL -> "title"
                            SortOrder.ALPHABETICAL_DESC -> "titlereverse"
                            SortOrder.NEWEST -> "latest"
                            SortOrder.POPULARITY -> "popular"
                            SortOrder.UPDATED -> "update"
                            else -> "update"
                        },
                    )

                    filter.tags.forEach {
                        append("&")
                        append("genre[]".urlEncoded())
                        append("=")
                        append(it.key)
                    }

                    filter.tagsExclude.forEach {
                        append("&")
                        append("genre[]".urlEncoded())
                        append("=")
                        append("-")
                        append(it.key)
                    }

                    if (filter.states.isNotEmpty()) {
                        filter.states.oneOrThrowIfMany()?.let {
                            append("&status=")
                            when (it) {
                                MangaState.ONGOING -> append("ongoing")
                                MangaState.FINISHED -> append("completed")
                                MangaState.PAUSED -> append("hiatus")
                                else -> append("")
                            }
                        }
                    }

                    filter.types.oneOrThrowIfMany()?.let {
                        append("&type=")
                        append(
                            when (it) {
                                ContentType.MANGA -> "manga"
                                ContentType.MANHWA -> "manhwa"
                                ContentType.MANHUA -> "manhua"
                                ContentType.COMICS -> "comic"
                                ContentType.NOVEL -> "novel"
                                else -> ""
                            },
                        )
                    }

                    append("&page=")
                    append(page.toString())
                }
            }
        }
        return parseMangaList(webClient.httpGet(url).parseHtml())
    }
    
    // المحددات الموحدة للبحث والتصفح
    override val selectMangaList = "div.magma-grid article.legend-card, div.manga-list-grid article.legend-card"
    override val selectMangaListImg = "img.legend-img"
    override val selectMangaListTitle = "h3.legend-title"
    
    // محدد الفصول من HTML صفحة التفاصيل
    override val selectChapter = "div.lh-chapters-sec div.ch-list-grid div.ch-item"
    
    // تاريخ بصيغة yyyy/MM/dd
    override val datePattern = "yyyy/MM/dd"
    
    override suspend fun getDetails(manga: Manga): Manga {
        val docs = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
        val dateFormat = SimpleDateFormat(datePattern, sourceLocale)
        
        // استخراج الفصول
        val chapters = docs.select(selectChapter).mapChapters(reversed = true) { index, element ->
            val a = element.selectFirst("a.ch-main-anchor") ?: return@mapChapters null
            val url = a.attrAsRelativeUrl("href")
            
            // استخراج رقم الفصل من "فصل 22"
            val chapterText = element.selectFirst("span.ch-num")?.text() ?: ""
            val chapterNumber = chapterText.replace("فصل", "").trim().toFloatOrNull() ?: (index + 1f)
            
            MangaChapter(
                id = generateUid(url),
                title = chapterText.ifEmpty { "Chapter ${index + 1}" },
                url = url,
                number = chapterNumber,
                volume = 0,
                scanlator = null,
                uploadDate = dateFormat.parseSafe(element.selectFirst("span.ch-date")?.text()),
                branch = null,
                source = source,
            )
        }
        
        return parseInfo(docs, manga, chapters)
    }
    
    override suspend fun parseInfo(
        docs: org.jsoup.nodes.Document,
        manga: Manga,
        chapters: List<MangaChapter>
    ): Manga {
        val title = docs.selectFirst("h1.lh-title")?.text()
            ?: docs.selectFirst("h1")?.text()
            ?: manga.title
        
        val description = docs.selectFirst("div.lh-story div.lh-story-content")?.text()
        
        // الحالة من "مستمر" أو "مكتمل"
        val statusText = docs.selectFirst("span.status-badge-lux")?.text()
        val state = when {
            statusText?.contains("مستمر") == true -> MangaState.ONGOING
            statusText?.contains("مكتمل") == true -> MangaState.FINISHED
            statusText?.contains("متوقف") == true -> MangaState.PAUSED
            else -> null
        }
        
        // التصنيفات
        val tags = docs.select("div.lh-genres a.lh-genre-tag").mapNotNullToSet { tag ->
            MangaTag(
                title = tag.text(),
                key = tag.attr("href").substringAfterLast("/").substringBefore("/"),
                source = source,
            )
        }
        
        // التقييم من "★ 9.0"
        val ratingText = docs.selectFirst("span.lh-meta-item:has(i.fa-star)")?.text()
        val rating = ratingText?.replace(Regex("[^0-9.]"), "")?.toFloatOrNull()?.div(10) ?: RATING_UNKNOWN
        
        return manga.copy(
            title = title,
            description = description,
            state = state,
            tags = tags,
            rating = rating,
            chapters = chapters,
        )
    }
    
    override fun parseMangaList(docs: org.jsoup.nodes.Document): List<Manga> {
        return docs.select(selectMangaList).mapNotNull { card ->
            val posterLink = card.selectFirst("a.legend-poster") ?: return@mapNotNull null
            val relativeUrl = posterLink.attrAsRelativeUrl("href")
            
            // التقييم من div.legend-rating
            val ratingText = card.selectFirst("div.legend-rating")?.text()
            val rating = ratingText?.replace(Regex("[^0-9.]"), "")?.toFloatOrNull()?.div(10) ?: RATING_UNKNOWN
            
            // الحالة من div.legend-ribbon (في التصفح) أو span.legend-ribbon (في البحث)
            val ribbonElement = card.selectFirst("div.legend-ribbon span, span.legend-ribbon")
            val statusText = ribbonElement?.text()
            val state = when {
                statusText?.contains("مستمر") == true -> MangaState.ONGOING
                statusText?.contains("مكتمل") == true -> MangaState.FINISHED
                statusText?.contains("متوقف") == true -> MangaState.PAUSED
                else -> null
            }
            
            // العنوان - بدعم للعناوين بدون رابط في البحث
            val titleElement = card.selectFirst(selectMangaListTitle)
            val title = titleElement?.let { 
                it.selectFirst("a")?.text() ?: it.text()
            } ?: posterLink.attr("href").substringAfterLast("/").substringBefore("/")
            
            Manga(
                id = generateUid(relativeUrl),
                url = relativeUrl,
                title = title,
                altTitles = emptySet(),
                publicUrl = posterLink.attrAsAbsoluteUrl("href"),
                rating = rating,
                contentRating = if (isNsfwSource) ContentRating.ADULT else null,
                coverUrl = card.selectFirst(selectMangaListImg)?.src(),
                tags = emptySet(),
                state = state,
                authors = emptySet(),
                source = source,
            )
        }
    }
}
