package com.hikari.app.telegram

import com.hikari.app.net.Http
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** One playable video found in a channel's public web preview. */
data class TelegramVideo(
    /** The channel it came from, as "@name". */
    val channel: String,
    /**
     * The post's own id inside that channel. It is what paging asks for
     * (`?before=<id>` returns the posts OLDER than it), so the list can be
     * walked back through a channel's history a page at a time.
     */
    val messageId: Long,
    val title: String,
    /** The file on Telegram's CDN — the URL the player is handed. */
    val url: String,
    val posterUrl: String?,
    val duration: String?,
    val dateLabel: String?,
)

/**
 * Telegram channels, read from the web preview Telegram publishes for them.
 *
 * A channel's public page (`t.me/s/<name>`) is the same feed the Telegram website
 * shows, and it carries the video files' own CDN URLs — which is what makes
 * channels playable in Hikari with no account, no API keys and no native
 * library. It is also the honest limit of this: what it can read is exactly what
 * is public. A private channel, or the Saved Messages chat, is only readable by
 * an authorised client (TDLib/MTProto) — see the Telegram tab's own note.
 */
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
        Jsoup.parse(html)
            .selectFirst(".tgme_channel_info_header_title")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    /**
     * The channel's title and one page of its videos, or null when the page
     * could not be fetched at all (offline, a typo'd channel, a 404).
     *
     * [before] walks the history backwards: pass the last video's message id to
     * get the page of posts older than it.
     */
    fun load(
        channel: String,
        before: Long? = null,
        query: String? = null,
    ): Pair<String?, List<TelegramVideo>>? {
        val html = Http.getString(pageUrl(channel, before, query), HEADERS) ?: return null
        return titleOf(html) to parse(html, channel)
    }

    /** Every post on a preview page whose video can actually be played. */
    fun parse(html: String, channel: String): List<TelegramVideo> {
        val doc = runCatching { Jsoup.parse(html) }.getOrNull() ?: return emptyList()
        val out = ArrayList<TelegramVideo>()
        for (msg in doc.select("div.tgme_widget_message")) {
            val id = msg.attr("data-post").substringAfterLast('/').toLongOrNull() ?: continue
            val video = msg.selectFirst("video") ?: continue
            val src = video.attr("src").ifBlank { video.attr("data-src") }
            if (!src.startsWith("http")) continue
            val text = msg.selectFirst(".tgme_widget_message_text")?.text()?.trim().orEmpty()
            val fileName = msg.selectFirst(".tgme_widget_message_document_title")?.text()?.trim().orEmpty()
            val date = msg.selectFirst("time")?.attr("datetime")?.takeIf { it.isNotBlank() }
                ?: msg.selectFirst(".tgme_widget_message_date")?.text()?.trim().orEmpty()
            out.add(
                TelegramVideo(
                    channel = channel,
                    messageId = id,
                    // The post's own words are the title when it has any (a
                    // channel that posts "Episode 12" writes exactly that), and
                    // the file name is the fallback.
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
        return out
    }

    private fun thumbnailOf(msg: Element, video: Element): String? {
        video.attr("poster").takeIf { it.startsWith("http") }?.let { return it }
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

    /**
     * "2026-09-24T04:56:12+00:00" → "2026-09-24 04:56". Telegram's own preview
     * prints a relative date ("Sep 24"), which is not what a list of videos
     * wants — the user is looking for when something was posted.
     */
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
