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

/** One page of a channel's public web preview. */
data class TelegramPage(
    /** The channel's own title, when the page carries one. */
    val title: String?,
    /** The playable videos on this page, newest first. */
    val videos: List<TelegramVideo>,
    /**
     * How many posts on this page carry a video that Telegram does NOT publish
     * to a browser — the message shows Telegram's own "media not supported /
     * view in Telegram" block instead of a `<video src>`.
     *
     * This number is the difference between two very different answers: "this
     * channel has no videos" and "this channel's videos are not available
     * anonymously". Telegram withholds the file for a large upload (the
     * reported channel's posts are 12:58 / 855 MB) and for content its owner
     * restricted, and a channel of those looks EMPTY to [parse] — which is what
     * the tab used to tell the user. It is what the account-based fallback keys
     * on (see the Telegram screen): the same channel read through the user's own
     * Telegram login has the files.
     */
    val unpublished: Int = 0,
    /**
     * How many POSTS the page carried, videos or not.
     *
     * Zero means something entirely different from "a channel with no videos":
     * Telegram publishes a feed for public CHANNELS and for nothing else — a
     * public GROUP's `t.me/s/<name>` is a small landing page with no posts at all
     * (only the link-preview metadata), and so is a channel whose owner turned
     * the preview off. A zero here therefore reads "Telegram gives browsers no
     * preview of this chat", and the tab must not answer "this channel has no
     * videos" — which is exactly what it did for a public group full of them.
     * See [hasFeed].
     */
    val posts: Int = 0,
) {
    /** True when the page really was a feed (see [posts]). */
    val hasFeed: Boolean get() = posts > 0
}

/**
 * One linked post, as Telegram's own embed page (`?embed=1`) shows it.
 *
 * `t.me/s/<name>` is a channel's FEED and carries nothing for a group; the embed
 * page is the single post itself — the same widget the Telegram website drops
 * into a blog — so it is how ONE video link is read without an account. It
 * carries the `<video src>` when Telegram publishes the file to a browser, the
 * caption in every case, and the "media not supported" block when it withholds
 * the file (see [TelegramPage.unpublished]).
 */
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
        val doc = Jsoup.parse(html)
        doc.selectFirst(".tgme_channel_info_header_title")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            // A page with NO feed (a public group, or a channel whose preview
            // Telegram does not publish) carries no channel header at all — only
            // the link-preview metadata, whose title is still the chat's real
            // name. Without this the row printed the @handle, which is what the
            // user saw for a group whose name Telegram knows perfectly well.
            ?: doc.selectFirst("meta[property=\"og:title\"]")
                ?.attr("content")
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
        // Every post the page carried, films its videos or not: the COUNT is how
        // the screen tells an empty channel from a chat Telegram publishes no
        // preview of at all (see [TelegramPage.posts]).
        val posts = doc.select("div.tgme_widget_message")
        for (msg in posts) {
            val id = msg.attr("data-post").substringAfterLast('/').toLongOrNull() ?: continue
            val video = msg.selectFirst("video")
            if (video == null) {
                // A video post whose player arrived with no `<video>` element at
                // all — counted the same way as one Telegram refuses to give a
                // source for (see TelegramPage.unpublished).
                if (msg.selectFirst(".message_media_not_supported") != null) unpublished++
                continue
            }
            val src = video.attr("src").ifBlank { video.attr("data-src") }
            if (!src.startsWith("http")) {
                // A post that IS a video Telegram will not hand to a browser:
                // either its player arrived without a source, or the message
                // carries the "media not supported" block. Counted, so the tab
                // can tell the user the truth about the channel (and fall back to
                // the account) instead of reporting an empty channel.
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

    /**
     * [loadPost] without the fetch — the embed page's own markup.
     *
     * One message widget is all the page has, so this reuses the feed parser's
     * pieces ([thumbnailOf]) rather than its whole loop. A page with no widget at
     * all means the link points at nothing Telegram will show a browser about it
     * (a private post, a deleted one, a typo), and the link-preview description
     * is still the post's words when Telegram answered at all.
     */
    fun parsePost(html: String, channel: String, messageId: Long): TelegramPost {
        val doc = runCatching { Jsoup.parse(html) }.getOrNull()
            ?: return TelegramPost(null, null, false, null)
        val msg = doc.selectFirst("div.tgme_widget_message")
        if (msg == null) {
            val desc = doc.selectFirst("meta[property=\"og:description\"]")
                ?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }
            return TelegramPost(desc, null, false, null)
        }
        val text = msg.selectFirst(".tgme_widget_message_text")?.text()?.trim().orEmpty()
        val fileName = msg.selectFirst(".tgme_widget_message_document_title")?.text()?.trim().orEmpty()
        val title = listOf(text, fileName).firstOrNull { it.isNotBlank() }
        val video = msg.selectFirst("video")
        val src = video?.let { it.attr("src").ifBlank { it.attr("data-src") } }.orEmpty()
        val poster = thumbnailOf(msg, video)
        if (src.startsWith("http")) {
            return TelegramPost(
                title = title,
                video = TelegramVideo(
                    channel = channel,
                    messageId = messageId,
                    title = title ?: ("Video " + messageId),
                    url = src,
                    posterUrl = poster,
                    duration = msg.selectFirst(".tgme_widget_message_video_duration")
                        ?.text()?.trim()?.takeIf { it.isNotEmpty() },
                    dateLabel = null,
                ),
                withheld = false,
                posterUrl = poster,
            )
        }
        // The post is there and it IS a video post, but with no source: Telegram
        // withholds the file from browsers (a large upload, or saving restricted)
        // — the same thing a feed page's `unpublished` counts.
        val withheld = msg.selectFirst(".tgme_widget_message_video_player") != null ||
            msg.selectFirst(".message_media_not_supported") != null
        return TelegramPost(title, null, withheld, poster)
    }

    private fun thumbnailOf(msg: Element, video: Element?): String? {
        video?.attr("poster")?.takeIf { it.startsWith("http") }?.let { return it }
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
