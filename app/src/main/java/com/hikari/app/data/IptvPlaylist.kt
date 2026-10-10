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
    /** The playlist's own `tvg-country` (usually an ISO code like "BD"/"IN").
     *  Empty when the playlist declares none — [countryOf] then falls back to
     *  the channel/group name, so grouping by country still works. */
    val country: String = "",
    /** Per-channel request headers from `#EXTVLCOPT:http-*` / `#KODIPROP`
     *  lines (Referer / User-Agent / Cookie). Empty for plain playlists —
     *  the provider falls back to the playlist origin + app UA. */
    val headers: Map<String, String> = emptyMap(),
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
        val country: String,
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
        var pendingHeaders: LinkedHashMap<String, String>? = null
        var lastAttrName: String? = null
        fun takeHeaders(): Map<String, String> {
            val h = pendingHeaders?.toMap().orEmpty()
            pendingHeaders = null
            return h
        }
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#")) {
                val upper = line.uppercase()
                when {
                    upper.startsWith("#EXTVLCOPT") -> {
                        // `#EXTVLCOPT:http-referrer=…` / `http-user-agent=…` /
                        // `http-cookie=…` / `http-header=Key: value` — the ONLY
                        // reason some m3u8 channels play in VLC/TiviMate but
                        // 403'd here (headers were dropped as "not channels").
                        parseVlcOpt(line)?.let { (k, v) ->
                            if (pendingHeaders == null) pendingHeaders = LinkedHashMap()
                            // Later lines win; keep first-seen key casing.
                            val existing = pendingHeaders!!.keys.firstOrNull { it.equals(k, true) }
                            if (existing != null) pendingHeaders!!.remove(existing)
                            pendingHeaders!![k] = v
                        }
                        continue
                    }
                    upper.startsWith("#KODIPROP") -> {
                        // Kodi `inputstream.adaptive` props sometimes carry
                        // `inputstream.adaptive.license_key` / stream headers.
                        parseKodiProp(line)?.let { (k, v) ->
                            if (k.equals("User-Agent", true) || k.equals("Referer", true) ||
                                k.equals("Cookie", true) || k.equals("Origin", true)
                            ) {
                                if (pendingHeaders == null) pendingHeaders = LinkedHashMap()
                                val existing = pendingHeaders!!.keys.firstOrNull { it.equals(k, true) }
                                if (existing != null) pendingHeaders!!.remove(existing)
                                pendingHeaders!![k] = v
                            }
                        }
                        continue
                    }
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
                        val country = attrs["tvg-country"].orEmpty()
                            .ifBlank { attrs["country"].orEmpty() }
                            .trim()
                        pending = Pending(name, logo, group, attrs["tvg-id"]?.takeIf { it.isNotBlank() }, lang, country)
                    }
                    // The group can also sit on its own line AFTER the #EXTINF.
                    upper.startsWith("#EXTGRP:") -> {
                        val g = line.substringAfter(':').trim()
                        if (g.isNotBlank()) pending = (pending ?: Pending(null, null, "", null, "", "")).copy(group = g)
                    }
                    // #EXTM3U/#EXT-X-…: not channels (VLCOPT/KODIPROP handled above).
                }
                continue
            }
            if (!STREAM_SCHEME.containsMatchIn(line)) continue
            val key = line
            if (!seen.add(key)) {
                pending = null
                pendingHeaders = null
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
                country = pending?.country.orEmpty().trim(),
                headers = takeHeaders(),
            )
            pending = null
            if (out.size >= max) break
        }
        return out
    }

    /** `#EXTVLCOPT:<key>=<value>` → canonical header pair, or null. */
    private fun parseVlcOpt(line: String): Pair<String, String>? {
        val body = line.substringAfter(":", "").trim()
        if (body.isEmpty()) return null
        val eq = body.indexOf('=')
        if (eq <= 0) return null
        val k = body.substring(0, eq).trim().lowercase()
        val v = body.substring(eq + 1).trim().trim('"').trim()
        if (v.isEmpty()) return null
        return when {
            k == "http-referrer" || k == "http-referer" || k == "referrer" || k == "referer" -> "Referer" to v
            k == "http-user-agent" || k == "user-agent" -> "User-Agent" to v
            k == "http-cookie" || k == "cookie" -> "Cookie" to v
            k == "http-origin" || k == "origin" -> "Origin" to v
            k.startsWith("http-header-") -> {
                val name = k.removePrefix("http-header-").trim()
                if (name.isEmpty()) null else name to v
            }
            k.startsWith("http-header:") -> {
                val rest = v
                val ci = rest.indexOf(':')
                if (ci <= 0) null else rest.substring(0, ci).trim() to rest.substring(ci + 1).trim()
            }
            else -> null
        }
    }

    /** `#KODIPROP:<k>=<v>` header-ish pairs, or null. */
    private fun parseKodiProp(line: String): Pair<String, String>? {
        val body = line.substringAfter(":", "").trim()
        if (body.isEmpty()) return null
        val eq = body.indexOf('=')
        if (eq <= 0) return null
        val k = body.substring(0, eq).trim()
        val v = body.substring(eq + 1).trim()
        if (v.isEmpty()) return null
        // `inputstream.adaptive.stream_headers=Referer=…&User-Agent=…`
        if (k.equals("inputstream.adaptive.stream_headers", true)) {
            // Kept simple: first header in the &-joined list (usually Referer).
            val first = v.split('&').firstOrNull()?.trim().orEmpty()
            val ci = first.indexOf('=')
            if (ci <= 0) return null
            val name = first.substring(0, ci).trim()
            val value = first.substring(ci + 1).trim()
            if (name.isEmpty() || value.isEmpty()) return null
            return name to value
        }
        return null
    }

    /** The group a channel is listed under, with the playlists that declare no
     *  group at all collected in one place. */
    fun groupOf(c: IptvChannel): String = c.group.ifBlank { "Ungrouped" }

    /** Grouping modes for a playlist page (see the IPTV tab): "groups" is the
     *  playlist's own `group-title` sections, "language" buckets channels by
     *  spoken language, "category" by what they show, "country" by origin. */
    fun normalizeGroupMode(mode: String?): String =
        if (mode == "language" || mode == "category" || mode == "country") mode else "groups"

    /** The tile a channel belongs to under [mode] (see [normalizeGroupMode]). */
    fun groupKey(c: IptvChannel, mode: String): String = when (normalizeGroupMode(mode)) {
        "language" -> languageOf(c)
        "category" -> categoryOf(c)
        "country" -> countryOf(c)
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

    /** Where a channel is from: the playlist's own `tvg-country` first (usually
     *  an ISO code — "BD", "IN", "US" — resolved below), then a name/group
     *  match ("[BD]", "Bangla", "USA", …), else Others. */
    fun countryOf(c: IptvChannel): String {
        isoCountry(c.country)?.let { return it }
        val n = " " + c.name.lowercase() + " " + c.group.lowercase() + " "
        for ((label, keys) in COUNTRY_KEYS) {
            for (k in keys) if (n.contains(k)) return label
        }
        return "Others"
    }

    /** Resolves a `tvg-country` value: a 2-letter ISO code maps through
     *  [COUNTRY_CODES], anything longer is taken as a name already. */
    private fun isoCountry(raw: String): String? {
        val v = raw.trim()
        if (v.isEmpty()) return null
        if (v.length == 2) {
            COUNTRY_CODES[v.uppercase()]?.let { return it }
        }
        return v.replaceFirstChar { it.uppercase() }.takeIf { it.isNotBlank() }
    }

    private val COUNTRY_CODES: Map<String, String> = mapOf(
        "IN" to "India", "US" to "USA", "GB" to "UK", "UK" to "UK",
        "BD" to "Bangladesh", "PK" to "Pakistan", "NP" to "Nepal",
        "LK" to "Sri Lanka", "AE" to "UAE", "SA" to "Saudi Arabia",
        "CA" to "Canada", "AU" to "Australia", "FR" to "France",
        "DE" to "Germany", "ES" to "Spain", "IT" to "Italy",
        "PT" to "Portugal", "NL" to "Netherlands", "RU" to "Russia",
        "TR" to "Turkey", "CN" to "China", "JP" to "Japan",
        "KR" to "South Korea", "ID" to "Indonesia", "MY" to "Malaysia",
        "SG" to "Singapore", "PH" to "Philippines", "TH" to "Thailand",
        "VN" to "Vietnam", "EG" to "Egypt", "ZA" to "South Africa",
        "NG" to "Nigeria", "BR" to "Brazil", "MX" to "Mexico",
        "AR" to "Argentina", "AF" to "Afghanistan", "IR" to "Iran",
        "IQ" to "Iraq", "QA" to "Qatar", "KW" to "Kuwait",
        "OM" to "Oman", "BH" to "Bahrain", "JO" to "Jordan",
        "LB" to "Lebanon", "IL" to "Israel", "MM" to "Myanmar",
    )

    private val COUNTRY_KEYS: List<Pair<String, List<String>>> = listOf(
        "India" to listOf("india", " hindi", "hindi ", "[in]", "(in)", " indian"),
        "USA" to listOf("usa", "america", "[us]", "(us)", " united states"),
        "UK" to listOf(" uk", "uk ", "britain", "british", "england", "[uk]", " london"),
        "Bangladesh" to listOf("bangladesh", "bangla", "bengali", "[bd]", "(bd)", "bdix", " dhaka"),
        "Pakistan" to listOf("pakistan", " pak ", "urdu", "[pk]", "(pk)", " lahore", " karachi"),
        "Nepal" to listOf("nepal", "nepali", "[np]", "(np)"),
        "Sri Lanka" to listOf("sri lanka", "srilanka", "[lk]", "(lk)"),
        "UAE" to listOf("uae", "dubai", "arab emirates", "[ae]", "(ae)"),
        "Saudi Arabia" to listOf("saudi", "ksa", "[sa]", "(sa)"),
        "Canada" to listOf("canada", "canadian", "[ca]", "(ca)"),
        "Australia" to listOf("australia", " aussie", "[au]", "(au)"),
        "France" to listOf("france", "french", "français", "francais", "[fr]", "(fr)"),
        "Germany" to listOf("germany", "german", "deutsch", "[de]", "(de)"),
        "Spain" to listOf("spain", "spanish", "espana", "españa", "[es]", "(es)"),
        "Italy" to listOf("italy", "italian", "[it]", "(it)"),
        "Portugal" to listOf("portugal", "portuguese", "[pt]", "(pt)"),
        "Netherlands" to listOf("netherlands", "dutch", "holland", "[nl]", "(nl)"),
        "Russia" to listOf("russia", "russian", "[ru]", "(ru)"),
        "Turkey" to listOf("turkey", "turkish", "turkiye", "türkiye", "[tr]", "(tr)"),
        "China" to listOf("china", "chinese", "mandarin", "[cn]", "(cn)", "cctv"),
        "Japan" to listOf("japan", "japanese", "nihon", "[jp]", "(jp)"),
        "South Korea" to listOf("korea", "korean", "seoul", "[kr]", "(kr)"),
        "Indonesia" to listOf("indonesia", "[id]", "(id)"),
        "Malaysia" to listOf("malaysia", "[my]", "(my)"),
        "Singapore" to listOf("singapore", "[sg]", "(sg)"),
        "Philippines" to listOf("philippines", "filipino", "pinoy", "[ph]", "(ph)"),
        "Thailand" to listOf("thailand", "thai ", "[th]", "(th)"),
        "Vietnam" to listOf("vietnam", "[vn]", "(vn)"),
        "Egypt" to listOf("egypt", "[eg]", "(eg)"),
        "South Africa" to listOf("south africa", "[za]", "(za)"),
        "Nigeria" to listOf("nigeria", "[ng]", "(ng)"),
        "Brazil" to listOf("brazil", "brasil", "[br]", "(br)"),
        "Mexico" to listOf("mexico", "[mx]", "(mx)"),
        "Iran" to listOf("iran", "persian", "farsi", "[ir]", "(ir)"),
        "Qatar" to listOf("qatar", "al jazeera", "aljazeera", "[qa]", "(qa)"),
    )

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
