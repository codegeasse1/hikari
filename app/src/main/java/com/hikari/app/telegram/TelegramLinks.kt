package com.hikari.app.telegram

import com.hikari.app.data.AppStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * The individual video links the Telegram tab keeps in its "Video links"
 * section.
 *
 * A channel is a FEED — adding one browses everything it posts. A video link is
 * ONE post, `t.me/<channel>/<id>`, which is what you have when someone sends you
 * a single video: there is no channel to browse, the video is the whole thing.
 *
 * The LINK is the identity. The title and the still are only what Telegram told
 * us about it when it was added, and the file itself is deliberately NOT stored:
 * a Telegram file reference expires within days, so a stored one is worthless
 * later. Every play re-reads the link ([Td.linkVideo] with the account,
 * [TelegramWeb.loadPost] without), which is what keeps a row playable months
 * after it was added.
 */
object TelegramLinks {

    /** One stored link. */
    data class Link(
        /** Canonical `https://t.me/<channel>/<id>` — the identity of the row. */
        val url: String,
        /** The channel half: `@name`, or `c/<internal id>` for a private link. */
        val channel: String,
        val messageId: Long,
        /** What Telegram calls the post; blank until (or unless) it could be read. */
        val title: String,
        /** A still for the row, when Telegram gave us one. It may expire — the
         *  row draws its play icon underneath it, so a stale one costs nothing. */
        val poster: String = "",
    ) {
        /** True for a `t.me/c/<internal id>/<id>` link, which only the account
         *  can resolve (it points inside a channel by numeric id, not by name). */
        val isPrivate: Boolean get() = channel.startsWith("c/")
    }

    fun decode(json: String): List<Link> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val url = o.optString("url").trim()
                if (url.isBlank()) null
                else Link(
                    url = url,
                    channel = o.optString("channel").trim(),
                    messageId = o.optLong("id"),
                    title = o.optString("title").trim(),
                    poster = o.optString("poster").trim(),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun encode(list: List<Link>): String {
        val arr = JSONArray()
        for (link in list) {
            arr.put(
                JSONObject()
                    .put("url", link.url)
                    .put("channel", link.channel)
                    .put("id", link.messageId)
                    .put("title", link.title)
                    .put("poster", link.poster)
            )
        }
        return arr.toString()
    }

    /** [existing] plus one link, unless it is already there (by its URL). */
    suspend fun add(store: AppStore, existing: List<Link>, link: Link) {
        if (existing.any { it.url.equals(link.url, ignoreCase = true) }) return
        store.setTelegramLinks(encode(existing + link))
    }

    suspend fun remove(store: AppStore, existing: List<Link>, url: String) {
        store.setTelegramLinks(encode(existing.filterNot { it.url.equals(url, true) }))
    }

    /**
     * [link] with its title (and, when telegram gives one up, its still) filled
     * in.
     *
     * The account is asked first — it is the only reader that sees a post whose
     * file Telegram withholds from browsers, and the only one that can read a
     * private `c/…` link at all — and the post's own embed page second. Neither
     * answering is not a reason to refuse the link: the title is cosmetic, and
     * the file may well be readable once the user signs in (or after Telegram
     * publishes it), which is why the row stores the link and re-reads it.
     */
    suspend fun describe(link: Link): Link {
        if (Td.isSignedIn()) {
            val video = runCatching { Td.linkVideo(link.url) }.getOrNull()
            if (video != null) {
                return link.copy(title = video.title.trim().ifBlank { link.title })
            }
        }
        val post = runCatching { TelegramWeb.loadPost(link.channel, link.messageId) }.getOrNull()
            ?: return link
        return link.copy(
            title = post.title?.trim()?.takeIf { it.isNotBlank() } ?: link.title,
            poster = post.posterUrl.orEmpty().ifBlank { link.poster },
        )
    }

    /**
     * What the user pasted, as a link to ONE post — or null, which is how the
     * Add box says "that is not a video link" before storing something that can
     * never play.
     *
     * Accepts `t.me/<name>/<id>`, `https://t.me/s/<name>/<id>`, the `?single` /
     * `?comment` variants Telegram's own "Copy link" produces, `telegram.me` and
     * a `www.` prefix, a `t.me/c/<internal>/<id>` private link, and a
     * `tg://resolve?domain=…&post=…` link. A bare channel, a profile or a post
     * id nothing can be read from is refused.
     */
    fun normalize(raw: String): Link? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        if (text.startsWith("tg://", ignoreCase = true)) {
            val params = text.substringAfter('?', "")
                .split('&')
                .mapNotNull { part ->
                    val at = part.indexOf('=')
                    if (at <= 0) null
                    else part.substring(0, at).lowercase() to part.substring(at + 1)
                }
                .toMap()
            val name = params["domain"] ?: return null
            val post = params["post"]?.toLongOrNull() ?: return null
            return build(name, post)
        }
        var s = text
        s = s.removePrefix("https://").removePrefix("http://").removePrefix("www.")
        s = s.removePrefix("t.me/").removePrefix("telegram.me/").removePrefix("telegram.dog/")
        s = s.substringBefore('?').substringBefore('#')
        var parts = s.split('/').filter { it.isNotBlank() }
        if (parts.isNotEmpty() && parts[0].equals("s", true)) parts = parts.drop(1)
        if (parts.isEmpty()) return null
        // t.me/c/<internal id>/<message id> — the link Telegram gives for a post
        // in a channel whose @name the user does not have.
        if (parts.size >= 3 && parts[0].equals("c", true)) {
            val post = parts[2].toLongOrNull() ?: return null
            val internal = parts[1].filter { it.isDigit() }
            if (internal.isEmpty()) return null
            return build("c/$internal", post)
        }
        if (parts.size < 2) return null
        val post = parts[1].toLongOrNull() ?: return null
        return build(parts[0], post)
    }

    private fun build(channelRaw: String, post: Long): Link? {
        if (post <= 0) return null
        val channel = channelRaw.trim().removePrefix("@")
        if (channel.startsWith("c/")) {
            val internal = channel.removePrefix("c/")
            if (internal.isEmpty() || !internal.all { it.isDigit() }) return null
            return Link("https://t.me/c/$internal/$post", "c/$internal", post, "")
        }
        if (!channel.matches(Regex("[A-Za-z0-9_]{3,64}"))) return null
        return Link("https://t.me/$channel/$post", "@$channel", post, "")
    }
}
