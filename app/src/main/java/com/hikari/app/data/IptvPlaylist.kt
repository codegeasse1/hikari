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
    val language: String = "",
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
        val language: String,
    )

    /**
     * Parses [text] into channels, in playlist order. Duplicate stream URLs are
     * dropped (mirrors of the same channel are common), keeping the first name
     * seen for a URL.
     *
     * [base] is where the playlist itself was read from (its URL, or the local
     * file path). A playlist's `tvg-logo` is not always absolute — plenty of
     * panels write `//cdn.example.com/x.png`, `/logos/x.png`, or a bare relative
     * path — and every one of those was DISCARDED (the old rule was "must start
     * with http"), which left those channels on the placeholder icon forever.
     * With the playlist's own address to resolve against they all load.
     */
    fun parse(text: String, max: Int = 20_000, base: String = ""): List<IptvChannel> {
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
                        val logo = resolveLogo(
                            attrs["tvg-logo"].orEmpty()
                                .ifBlank { attrs["logo"].orEmpty() }
                                .ifBlank { attrs["tvg-logo-small"].orEmpty() }
                                .ifBlank { attrs["logo-small"].orEmpty() },
                            base,
                        )
                        val lang = attrs["tvg-language"].orEmpty()
                            .ifBlank { attrs["language"].orEmpty() }
                            .trim()
                        pending = Pending(name, logo, group, attrs["tvg-id"]?.takeIf { it.isNotBlank() }, lang)
                    }
                    // The group can also sit on its own line AFTER the #EXTINF.
                    upper.startsWith("#EXTGRP:") -> {
                        val g = line.substringAfter(':').trim()
                        if (g.isNotBlank()) pending = (pending ?: Pending(null, null, "", null, "")).copy(group = g)
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
                language = pending?.language.orEmpty().trim(),
            )
            pending = null
            if (out.size >= max) break
        }
        return out
    }

    /** The group a channel is listed under, with the playlists that declare no
     *  group at all collected in one place. */
    fun groupOf(c: IptvChannel): String = c.group.ifBlank { "Ungrouped" }

    /** Grouping modes for a playlist page (see the IPTV tab): "groups" is the
     *  playlist's own `group-title` sections, "language" buckets channels by
     *  spoken language, "category" by what they show. */
    fun normalizeGroupMode(mode: String?): String =
        if (mode == "language" || mode == "category") mode else "groups"

    /** The tile a channel belongs to under [mode] (see [normalizeGroupMode]). */
    fun groupKey(c: IptvChannel, mode: String): String = when (normalizeGroupMode(mode)) {
        "language" -> languageOf(c)
        "category" -> categoryOf(c)
        else -> groupOf(c)
    }

    /** Spoken language of a channel: the playlist's own `tvg-language` first,
     *  then a name match (Hindi, English, Tamil, Spanish, …), else Others. */
    fun languageOf(c: IptvChannel): String {
        normalizeLanguage(c.language)?.let { return it }
        val n = " " + c.name.lowercase() + " " + c.group.lowercase() + " "
        for ((label, keys) in LANGUAGE_KEYS) {
            for (k in keys) if (n.contains(" " + k + " ") || n.contains(k + " ")) return label
        }
        return "Others"
    }

    /** What a channel shows: Kids, News, Sports, Movies, Music, Entertainment,
     *  Documentary, Religious or Others — matched from its group and name, so
     *  a playlist with no useful `group-title` still sorts into shelves. */
    fun categoryOf(c: IptvChannel): String {
        val n = " " + c.group.lowercase() + " " + c.name.lowercase() + " "
        for ((label, keys) in CATEGORY_KEYS) {
            for (k in keys) if (n.contains(k)) return label
        }
        return if (c.group.isNotBlank()) c.group else "Others"
    }

    private fun normalizeLanguage(raw: String): String? {
        val v = raw.trim().lowercase()
        if (v.isEmpty()) return null
        for ((label, keys) in LANGUAGE_KEYS) {
            if (v == label.lowercase() || v in keys) return label
        }
        return raw.trim().replaceFirstChar { it.uppercase() }.takeIf { it.isNotBlank() }
    }

    private val LANGUAGE_KEYS: List<Pair<String, List<String>>> = listOf(
        "Hindi" to listOf("hindi", "hin"),
        "English" to listOf("english", "eng"),
        "Tamil" to listOf("tamil", "tam"),
        "Telugu" to listOf("telugu", "tel"),
        "Malayalam" to listOf("malayalam", "mal"),
        "Kannada" to listOf("kannada", "kan"),
        "Punjabi" to listOf("punjabi", "pun", "panjabi"),
        "Bengali" to listOf("bengali", "beng", "bangla"),
        "Marathi" to listOf("marathi", "mar"),
        "Gujarati" to listOf("gujarati", "guj"),
        "Urdu" to listOf("urdu", "urd"),
        "Spanish" to listOf("spanish", "espanol", "español", "spa"),
        "French" to listOf("french", "francais", "français", "fra", "fre"),
        "German" to listOf("german", "deutsch", "deu", "ger"),
        "Italian" to listOf("italian", "italiano", "ita"),
        "Portuguese" to listOf("portuguese", "portugues", "por"),
        "Arabic" to listOf("arabic", "ara"),
        "Turkish" to listOf("turkish", "tur"),
        "Russian" to listOf("russian", "rus"),
        "Persian" to listOf("persian", "farsi", "iran"),
    )

    private val CATEGORY_KEYS: List<Pair<String, List<String>>> = listOf(
        "Kids" to listOf("kid", "cartoon", "toon", "pogo", "chutti", "chintu", "nick", "disney junior", "baby"),
        "News" to listOf("news", "aaj tak", "ndtv", "republic", "bbc", "cnn", "abp", "zee news", "headline"),
        "Sports" to listOf("sport", "espn", "star sports", "cricket", "football", "tennis", "f1 ", "wwe"),
        "Movies" to listOf("movie", "cinema", "film", "hbo", "star movies", "sony max", "zee cinema", "24/7"),
        "Music" to listOf("music", "mtv", "vh1", "9x", "sangeet", "radio mirchi", "song"),
        "Documentary" to listOf("discovery", "nat geo", "national geographic", "history", "animal planet", "docu"),
        "Religious" to listOf("sanskar", "aarti", "bhakti", "god ", "spiritual", "quran", "bible"),
        "Entertainment" to listOf("entertainment", "star plus", "zee tv", "colors", "sony sab", "sab tv", "comedy", "serial", "drama", "starplus"),
    )

    /**
     * Turns a playlist's `tvg-logo` value into a URL that can actually be
     * fetched, using [base] (where the playlist came from) for the relative
     * forms. Null when there is nothing usable at all — the caller then draws
     * its own tile (see [com.hikari.app.ui.IptvArt]).
     *
     * Spaces are percent-encoded: panels routinely ship `tvg-logo="http://host/my
     * logo.png"`, and an unencoded space makes the image request fail outright.
     */
    fun resolveLogo(raw: String, base: String = ""): String? {
        val v = raw.trim().replace(" ", "%20")
        if (v.isEmpty()) return null
        if (v.startsWith("http://") || v.startsWith("https://")) return v
        if (v.startsWith("data:")) return v
        // Protocol-relative ("//cdn.example.com/x.png"): inherit our own scheme,
        // which is what a browser does with it.
        if (v.startsWith("//")) return schemeOf(base) + v
        // Root-relative ("/logos/x.png"): the playlist's own host.
        if (v.startsWith("/")) return originOf(base)?.plus(v)
        if (v.contains('/')) {
            val first = v.substringBefore('/')
            // "cdn.example.com/x.png" is a host with no scheme; "logos/x.png" is
            // a path on the playlist's host.
            return if (first.contains('.')) "http://$v" else originOf(base)?.plus("/$v")
        }
        // A bare filename has no host to resolve against — unusable on its own.
        return null
    }

    /** "https:" / "http:" for [base], defaulting to https when unknown. */
    private fun schemeOf(base: String): String =
        if (base.trim().startsWith("http://", true)) "http:" else "https:"

    /** "scheme://host[:port]" of [base], or null when [base] is not a URL. */
    private fun originOf(base: String): String? {
        val b = base.trim()
        val i = b.indexOf("://")
        if (i <= 0) return null
        val rest = b.substring(i + 3)
        val host = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        if (host.isBlank()) return null
        return b.substring(0, i + 3) + host
    }

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
