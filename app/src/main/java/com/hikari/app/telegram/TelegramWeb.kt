package com.hikari.app.telegram

import com.hikari.app.net.Http
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** One playable video found in a channel's public web preview. */
data class TelegramVideo(
    /** The channel it came from, as "@name". */
    val channel: String,
    val messageId: Long,
    val title: String,
    /** The file on Telegram's CDN — the URL the player is handed. */
    val url: String,
    val posterUrl: String?,
    val duration: String?,
    val dateLabel: String?,
)

/** One page of a channel's public web preview. */
data class TelegramPage(
    /** The channel's own title, when the page carries one. */
    val title: String?,
    /** The playable videos on this page, newest first. */
    val videos: List<TelegramVideo>,
    val unpublished: Int = 0,
    val posts: Int = 0,
) {
    /** True when the page really was a feed (see [posts]). */
    val hasFeed: Boolean get() = posts > 0
}

data class TelegramPost(
    /** The post's caption, or its file name — what a row should print. */
    val title: String?,
    /** The playable file, when this page carries one. */
    val video: TelegramVideo?,
    /** True when the post exists but Telegram withheld its file from browsers. */
    val withheld: Boolean,
    /** The post's own still, when the page carries one. */
    val posterUrl: String?,
)

object TelegramWeb {

    private const val BASE = "https://t.me"

    /**
     * What the user pasted, as a channel handle: "@name". Accepts "@name",
     * "name", "t.me/name", "https://t.me/s/name" and a link to one post
     * ("…/name/123"), and answers null for anything that is not a channel —
     * the Add box uses that to say so before it stores a broken entry.
     */
    fun normalize(raw: String): String? {
        var s = raw.trim()
        if (s.isEmpty()) return null
        s = s.removePrefix("https://").removePrefix("http://")
        s = s.removePrefix("www.").removePrefix("t.me/").removePrefix("telegram.me/")
        s = s.removePrefix("s/")
        s = s.substringBefore('?').substringBefore('#')
        s = s.substringBefore('/')
        s = s.removePrefix("@").trim()
        if (!s.matches(Regex("[A-Za-z0-9_]{3,64}"))) return null
        return "@" + s
    }

    private fun pageUrl(channel: String, before: Long? = null, query: String? = null): String {
        val name = channel.removePrefix("@")
        val suffix = when {
            !query.isNullOrBlank() -> "?q=" + java.net.URLEncoder.encode(query, "UTF-8")
            before != null -> "?before=" + before
            else -> ""
        }
        return "$BASE/s/$name$suffix"
    }

    /** The channel's own name (its title), or null when the page has none. */
    fun titleOf(html: String): String? = runCatching {
        val doc = Jsoup.parse(html)
        doc.selectFirst(".tgme_channel_info_header_title")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("meta[property=\"og:title\"]")
                ?.attr("content")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    fun load(
        channel: String,
        before: Long? = null,
        query: String? = null,
    ): Pair<String?, List<TelegramVideo>>? =
        loadPage(channel, before, query)?.let { it.title to it.videos }

    /** As [load], keeping the count of video posts Telegram did not publish —
     *  see [TelegramPage.unpublished]. */
    fun loadPage(
        channel: String,
        before: Long? = null,
        query: String? = null,
    ): TelegramPage? {
        val html = Http.getString(pageUrl(channel, before, query), HEADERS) ?: return null
        return parsePage(html, channel)
    }

    /** Every post on a preview page whose video can actually be played. */
    fun parse(html: String, channel: String): List<TelegramVideo> = parsePage(html, channel).videos

    /** [parse], plus the channel's title and how many video posts were withheld
     *  (see [TelegramPage]). */
    fun parsePage(html: String, channel: String): TelegramPage {
        val doc = runCatching { Jsoup.parse(html) }.getOrNull()
            ?: return TelegramPage(null, emptyList(), 0)
        val out = ArrayList<TelegramVideo>()
        var unpublished = 0
        val posts = doc.select("div.tgme_widget_message")
        for (msg in posts) {
            val id = msg.attr("data-post").substringAfterLast('/').toLongOrNull() ?: continue
            val video = msg.selectFirst("video")
            if (video == null) {
                if (msg.selectFirst(".message_media_not_supported") != null) unpublished++
                continue
            }
            val src = video.attr("src").ifBlank { video.attr("data-src") }
            if (!src.startsWith("http")) {
                if (msg.selectFirst(".tgme_widget_message_video_player") != null ||
                    msg.selectFirst(".message_media_not_supported") != null
                ) {
                    unpublished++
                }
                continue
            }
            val text = msg.selectFirst(".tgme_widget_message_text")?.text()?.trim().orEmpty()
            val fileName = msg.selectFirst(".tgme_widget_message_document_title")?.text()?.trim().orEmpty()
            val date = msg.selectFirst("time")?.attr("datetime")?.takeIf { it.isNotBlank() }
                ?: msg.selectFirst(".tgme_widget_message_date")?.text()?.trim().orEmpty()
            out.add(
                TelegramVideo(
                    channel = channel,
                    messageId = id,
                    title = listOf(text, fileName).firstOrNull { it.isNotBlank() }
                        ?: ("Video " + id),
                    url = src,
                    posterUrl = thumbnailOf(msg, video),
                    duration = msg.selectFirst(".tgme_widget_message_video_duration")
                        ?.text()?.trim()?.takeIf { it.isNotEmpty() },
                    dateLabel = friendlyDate(date),
                )
            )
        }
        return TelegramPage(
            title = titleOf(html),
            videos = out,
            unpublished = unpublished,
            posts = posts.size,
        )
    }

    /** One post's own page (`?embed=1`), which carries just that message. */
    fun loadPost(channel: String, messageId: Long): TelegramPost? {
        val name = channel.removePrefix("@")
        val html = Http.getString("$BASE/$name/$messageId?embed=1", HEADERS) ?: return null
        return parsePost(html, channel, messageId)
    }

    fun parsePost(html: String, channel: String, messageId: Long): TelegramPost {
        val doc = runCatching { Jsoup.parse(html) }.getOrNull()
            ?: return TelegramPost(null, null, false, null)
        val msg: Element? = doc.selectFirst("div.tgme_widget_message")
        if (msg == null) {
            val meta: Element? = doc.selectFirst("meta[property=\"og:description\"]")
            val descText: String = if (meta == null) "" else meta.attr("content").trim()
            val desc: String? = if (descText.isEmpty()) null else descText
            return TelegramPost(desc, null, false, null)
        }
        val textEl: Element? = msg.selectFirst(".tgme_widget_message_text")
        val fileEl: Element? = msg.selectFirst(".tgme_widget_message_document_title")
        val text: String = if (textEl == null) "" else textEl.text().trim()
        val fileName: String = if (fileEl == null) "" else fileEl.text().trim()
        val title: String? = when {
            text.isNotBlank() -> text
            fileName.isNotBlank() -> fileName
            else -> null
        }
        val videoEl: Element? = msg.selectFirst("video")
        var src: String = ""
        if (videoEl != null) {
            src = videoEl.attr("src")
            if (src.isBlank()) src = videoEl.attr("data-src")
        }
        val poster: String? = thumbnailOf(msg, videoEl)
        if (src.startsWith("http")) {
            val durationEl: Element? = msg.selectFirst(".tgme_widget_message_video_duration")
            val durText: String = if (durationEl == null) "" else durationEl.text().trim()
            return TelegramPost(
                title = title,
                video = TelegramVideo(
                    channel = channel,
                    messageId = messageId,
                    title = title ?: ("Video " + messageId),
                    url = src,
                    posterUrl = poster,
                    duration = if (durText.isBlank()) null else durText,
                    dateLabel = null,
                ),
                withheld = false,
                posterUrl = poster,
            )
        }
        val withheld = msg.selectFirst(".tgme_widget_message_video_player") != null ||
            msg.selectFirst(".message_media_not_supported") != null
        return TelegramPost(title, null, withheld, poster)
    }

    private fun thumbnailOf(msg: Element, video: Element?): String? {
        if (video != null) {
            val poster: String = video.attr("poster")
            if (poster.startsWith("http")) return poster
        }
        for (selector in listOf(
            ".tgme_widget_message_video_thumb",
            ".tgme_widget_message_photo_wrap",
            ".tgme_widget_message_video_thumb_wrap",
        )) {
            val style = msg.selectFirst(selector)?.attr("style").orEmpty()
            val url = backgroundUrl(style)
            if (url != null) return url
        }
        return null
    }

    /** `background-image:url('https://…')` → the url, when there is one. */
    private fun backgroundUrl(style: String): String? {
        val at = style.indexOf("url(")
        if (at < 0) return null
        return style.substring(at + 4)
            .substringBefore(')')
            .trim()
            .trim('\'', '"')
            .takeIf { it.startsWith("http") }
    }

    private fun friendlyDate(raw: String): String? {
        if (raw.isBlank()) return null
        val cleaned = raw.replace('T', ' ').substringBefore('+').substringBefore('Z').trim()
        return if (cleaned.length >= 16) cleaned.take(16) else cleaned
    }

    /** Telegram answers a plain browser happily; the UA is the app's own. */
    private val HEADERS = mapOf(
        "User-Agent" to Http.UA,
        "Accept-Language" to "en-US,en;q=0.9",
    )
}
