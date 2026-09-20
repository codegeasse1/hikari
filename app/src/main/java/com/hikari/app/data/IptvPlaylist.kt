package com.hikari.app.data

/**
 * One channel of an M3U/M3U8 playlist.
 *
 * [group] is the playlist's own `group-title` (the sections an IPTV app shows:
 * "Sports", "Movies", "News"…), and [url] is the stream the channel plays —
 * a direct HLS link, an MPEG-TS link, or whatever the provider serves.
 */
data class IptvChannel(
    val name: String,
    val url: String,
    val logo: String? = null,
    val group: String = "",
    val tvgId: String? = null,
)

/**
 * Reader for the playlist format every IPTV provider speaks.
 *
 * The shape is one `#EXTINF` line per channel followed by the channel's stream
 * URL:
 *
 * ```
 * #EXTM3U
 * #EXTINF:-1 tvg-id="sky1" tvg-logo="https://…/sky1.png" group-title="Sports",Sky Sports 1
 * http://example.com/live/12345.m3u8
 * ```
 *
 * Real playlists are messier than the spec, so this tolerates the variants that
 * actually appear in the wild:
 *  - attributes in any order, with or without quotes, `tvg-logo` or plain
 *    `logo`, `group-title`/`group`/`#EXTGRP:` for the group;
 *  - the channel NAME is what follows the LAST comma of the `#EXTINF` line
 *    (names legitimately contain commas: "News, Live"), falling back to
 *    `tvg-name` and then to the URL's own file name;
 *  - plain URL lines with no `#EXTINF` at all (a bare list of links);
 *  - `#EXTVLCOPT`/`#KODIPROP`/`#EXT-X-…` lines, which are not channels;
 *  - a completely empty "playlist" is reported as such, so the caller can fall
 *    back to treating a single pasted m3u8 link as one channel.
 */
object IptvPlaylist {

    /** Attribute pairs inside an `#EXTINF` line: `key="value"` or `key=value`. */
    private val ATTR = Regex("([A-Za-z0-9_-]+)\\s*=\\s*\"([^\"]*)\"")

    /** Stream schemes worth keeping — anything else on its own line is either a
     *  comment the playlist forgot to mark, or a fragment of a wrapped URL. */
    private val STREAM_SCHEME = Regex(
        "^(https?|rtmp|rtmps|rtsp|udp|rtp|mms|mmsh)://",
        RegexOption.IGNORE_CASE,
    )

    /** One playlist line's worth of a channel: a channel can only be emitted
     *  once its URL line arrives. */
    private data class Pending(
        val name: String?,
        val logo: String?,
        val group: String,
        val tvgId: String?,
    )

    /**
     * Parses [text] into channels, in playlist order. Duplicate stream URLs are
     * dropped (mirrors of the same channel are common), keeping the first name
     * seen for a URL.
     */
    fun parse(text: String, max: Int = 20_000): List<IptvChannel> {
        val out = ArrayList<IptvChannel>()
        val seen = HashSet<String>()
        var pending: Pending? = null
        var lastAttrName: String? = null
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#")) {
                val upper = line.uppercase()
                when {
                    upper.startsWith("#EXTINF") -> {
                        val comma = line.lastIndexOf(',')
                        val head = if (comma >= 0) line.substring(0, comma) else line
                        val tail = if (comma >= 0) line.substring(comma + 1).trim() else ""
                        val attrs = HashMap<String, String>()
                        for (m in ATTR.findAll(head)) {
                            val key = m.groupValues[1].lowercase()
                            attrs[key] = m.groupValues[2].trim()
                            lastAttrName = m.groupValues[2].trim().ifBlank { null }
                        }
                        // Some playlists use `tvg-name` as the ONLY name and
                        // leave the comma-tag empty, and some put the name
                        // before the attributes — the comma-tag wins when it has
                        // anything in it.
                        val name = tail.ifBlank { attrs["tvg-name"].orEmpty() }
                            .ifBlank { lastAttrName.orEmpty() }
                            .takeIf { it.isNotBlank() }
                        val group = attrs["group-title"].orEmpty()
                            .ifBlank { attrs["group"].orEmpty() }
                        val logo = attrs["tvg-logo"].orEmpty()
                            .ifBlank { attrs["logo"].orEmpty() }
                            .ifBlank { attrs["tvg-logo-small"].orEmpty() }
                            .takeIf { it.startsWith("http") }
                        pending = Pending(name, logo, group, attrs["tvg-id"]?.takeIf { it.isNotBlank() })
                    }
                    // The group can also sit on its own line AFTER the #EXTINF.
                    upper.startsWith("#EXTGRP:") -> {
                        val g = line.substringAfter(':').trim()
                        if (g.isNotBlank()) pending = (pending ?: Pending(null, null, "", null)).copy(group = g)
                    }
                    // #EXTM3U/#EXTVLCOPT/#KODIPROP/#EXT-X-…: not channels.
                }
                continue
            }
            if (!STREAM_SCHEME.containsMatchIn(line)) continue
            val key = line
            if (!seen.add(key)) {
                pending = null
                continue
            }
            val name = pending?.name ?: nameFromUrl(line)
            out += IptvChannel(
                name = name,
                url = line,
                logo = pending?.logo,
                group = pending?.group.orEmpty().trim(),
                tvgId = pending?.tvgId,
            )
            pending = null
            if (out.size >= max) break
        }
        return out
    }

    /** The group a channel is listed under, with the playlists that declare no
     *  group at all collected in one place. */
    fun groupOf(c: IptvChannel): String = c.group.ifBlank { "Ungrouped" }

    /** A channel name for a URL line that carried no `#EXTINF`: its last path
     *  segment, with the extension trimmed, else the host. */
    private fun nameFromUrl(url: String): String {
        val withoutQuery = url.substringBefore('?').trimEnd('/')
        val last = withoutQuery.substringAfterLast('/')
        val base = last.substringBeforeLast('.')
        if (base.isNotBlank() && base.length <= 60) return base
        return Regex("^[a-z]+://([^/:]+)", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.get(1) ?: url.take(60)
    }
}
