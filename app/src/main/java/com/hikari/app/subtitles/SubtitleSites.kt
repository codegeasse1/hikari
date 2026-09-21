package com.hikari.app.subtitles

import com.hikari.app.data.SubtitleSource
import com.hikari.app.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The subtitle SITES Hikari can search on its own — no addon, no account, no
 * API key.
 *
 * The player's "Load from internet" panel used to be able to ask only the
 * Stremio-style subtitle addons the user had installed, so a user with none
 * (the common case: an install of CloudStream-style extensions and nothing
 * else) got "No subtitle addon is installed" — and a user WITH one got
 * "No subtitles found for X" for most titles, because those addons answer only
 * for ids they recognise (`opensubtitles-v3` wants a `tt…` id and answers an
 * empty list with HTTP 200 to anything else).
 *
 * So this is a small set of DIRECT connections to the sites people actually
 * download subtitles from, each one a plain HTTP request to a public,
 * key-less endpoint that was verified alive from a real device profile:
 *
 *  - [OpenSubtitlesSite] — the OpenSubtitles catalogue behind its public mirror
 *    (the service `opensubtitles-v3.strem.io` itself is a client of). Tracks
 *    come back with their own download URLs: one request, no second hop.
 *  - [OpenSubtitlesOrgSite] — opensubtitles.org's own search API, which is the
 *    one that answers by NAME (`query-moana`) as well as by id, and carries the
 *    download count and rating of every track. Its file URLs are served through
 *    the same public mirror, keyed by the file id it returns.
 *  - [SubdlSite] — SubDL, 35 languages, every release as a .zip.
 *  - [SubtitleCatSite] — SubtitleCat, which re-serves what OpenSubtitles,
 *    Addic7ed and friends have, as a plain .srt per language.
 *  - [SubsceneSite] — Subscene, the community library, as a .zip per subtitle.
 *  - [YifySite] — the YIFY subtitle mirror, which is where a rip-based release
 *    name (the ones most streams actually carry) is most likely to match.
 *
 * Two rules every site here follows:
 *
 *  1. A site that fails, times out or is blocked contributes NOTHING and never
 *     stops the others — the caller runs them concurrently and shows whatever
 *     answers (see the player's dialog). A site going down must not be able to
 *     break subtitle search.
 *  2. Nothing here is allowed to raise the app's "Cloudflare verification
 *     needed" banner for the user's own extensions, so every request goes
 *     through [Http.getStringQuiet] (the client WITHOUT the Cloudflare
 *     interceptor) — the same rule the ratings lookups follow.
 */

/** A language, as a site printed it. */
data class SubLang(val code: String, val label: String)

/**
 * Site language codes → one shape.
 *
 * Every site spells languages differently — OpenSubtitles answers `eng` and
 * `ISO639: en`, SubDL answers `brazilian-portuguese` and `big5code`, Subscene
 * answers `Arabic`, SubtitleCat answers `pt-BR` — and the list has to be
 * sortable (the app's own language first, then English) and printable. One
 * table, one answer.
 */
object SubtitleLang {

    private val ROWS: List<Triple<String, List<String>, String>> = listOf(
        Triple("en", listOf("eng"), "English"),
        Triple("ar", listOf("ara"), "Arabic"),
        Triple("zh", listOf("chi", "zho"), "Chinese (Simplified)"),
        Triple("fa", listOf("per", "fas"), "Persian"),
        Triple("fr", listOf("fre", "fra"), "French"),
        Triple("de", listOf("ger", "deu"), "German"),
        Triple("es", listOf("spa"), "Spanish"),
        Triple("pt", listOf("por"), "Portuguese"),
        Triple("it", listOf("ita"), "Italian"),
        Triple("ru", listOf("rus"), "Russian"),
        Triple("hi", listOf("hin"), "Hindi"),
        Triple("bn", listOf("ben"), "Bengali"),
        Triple("ta", listOf("tam"), "Tamil"),
        Triple("te", listOf("tel"), "Telugu"),
        Triple("ml", listOf("mal"), "Malayalam"),
        Triple("kn", listOf("kan"), "Kannada"),
        Triple("mr", listOf("mar"), "Marathi"),
        Triple("gu", listOf("guj"), "Gujarati"),
        Triple("pa", listOf("pan"), "Punjabi"),
        Triple("ur", listOf("urd"), "Urdu"),
        Triple("ne", listOf("nep"), "Nepali"),
        Triple("si", listOf("sin", "snh"), "Sinhala"),
        Triple("th", listOf("tha"), "Thai"),
        Triple("vi", listOf("vie"), "Vietnamese"),
        Triple("id", listOf("ind"), "Indonesian"),
        Triple("ms", listOf("may", "msa"), "Malay"),
        Triple("tl", listOf("tgl", "fil"), "Filipino"),
        Triple("ja", listOf("jpn"), "Japanese"),
        Triple("ko", listOf("kor"), "Korean"),
        Triple("tr", listOf("tur"), "Turkish"),
        Triple("pl", listOf("pol"), "Polish"),
        Triple("nl", listOf("dut", "nld"), "Dutch"),
        Triple("sv", listOf("swe"), "Swedish"),
        Triple("no", listOf("nor", "nob"), "Norwegian"),
        Triple("da", listOf("dan"), "Danish"),
        Triple("fi", listOf("fin"), "Finnish"),
        Triple("is", listOf("ice", "isl"), "Icelandic"),
        Triple("cs", listOf("cze", "ces"), "Czech"),
        Triple("sk", listOf("slo", "slk"), "Slovak"),
        Triple("hu", listOf("hun"), "Hungarian"),
        Triple("ro", listOf("rum", "ron"), "Romanian"),
        Triple("bg", listOf("bul"), "Bulgarian"),
        Triple("el", listOf("gre", "ell"), "Greek"),
        Triple("he", listOf("heb", "iw"), "Hebrew"),
        Triple("uk", listOf("ukr"), "Ukrainian"),
        Triple("sr", listOf("srp"), "Serbian"),
        Triple("hr", listOf("hrv"), "Croatian"),
        Triple("sl", listOf("slv"), "Slovenian"),
        Triple("bs", listOf("bos"), "Bosnian"),
        Triple("mk", listOf("mac", "mkd"), "Macedonian"),
        Triple("sq", listOf("alb", "sqi"), "Albanian"),
        Triple("hy", listOf("arm", "hye"), "Armenian"),
        Triple("ka", listOf("geo", "kat"), "Georgian"),
        Triple("az", listOf("aze"), "Azerbaijani"),
        Triple("kk", listOf("kaz"), "Kazakh"),
        Triple("uz", listOf("uzb"), "Uzbek"),
        Triple("sw", listOf("swa"), "Swahili"),
        Triple("af", listOf("afr"), "Afrikaans"),
        Triple("am", listOf("amh"), "Amharic"),
        Triple("et", listOf("est"), "Estonian"),
        Triple("lv", listOf("lav"), "Latvian"),
        Triple("lt", listOf("lit"), "Lithuanian"),
        Triple("ca", listOf("cat"), "Catalan"),
        Triple("gl", listOf("glg"), "Galician"),
        Triple("eu", listOf("baq", "eus"), "Basque"),
        Triple("cy", listOf("wel", "cym"), "Welsh"),
        Triple("ga", listOf("gle"), "Irish"),
        Triple("mt", listOf("mlt"), "Maltese"),
        Triple("my", listOf("mya", "bur"), "Burmese"),
        Triple("km", listOf("khm"), "Khmer"),
        Triple("lo", listOf("lao"), "Lao"),
        Triple("mn", listOf("mon"), "Mongolian"),
        Triple("ps", listOf("pus"), "Pashto"),
        Triple("ku", listOf("kur"), "Kurdish"),
        Triple("be", listOf("bel"), "Belarusian"),
        Triple("so", listOf("som"), "Somali"),
        Triple("zu", listOf("zul"), "Zulu"),
        Triple("ha", listOf("hau"), "Hausa"),
        Triple("yo", listOf("yor"), "Yoruba"),
        Triple("ig", listOf("ibo"), "Igbo"),
        Triple("ti", listOf("tir"), "Tigrinya"),
        Triple("eo", listOf("epo"), "Esperanto"),
    )

    private val byCode = HashMap<String, SubLang>()
    private val byName = HashMap<String, SubLang>()

    /** Spellings that mean a language the table already carries under another
     *  name — what the odd site actually prints. */
    private val ALIASES: Map<String, String> = mapOf(
        "big5 code" to "zh",
        "big5code" to "zh",
        "chinese traditional" to "zh",
        "traditional chinese" to "zh",
        "cantonese" to "zh",
        "mandarin" to "zh",
        "farsi" to "fa",
        "brazilian portuguese" to "pt",
        "portuguese brazil" to "pt",
        "castilian" to "es",
        "latin american spanish" to "es",
        "flemish" to "nl",
        "valencian" to "ca",
        "serbo croatian" to "sr",
        "sorani" to "ku",
        "kurdish sorani" to "ku",
        "pushto" to "ps",
        "tagalog" to "tl",
        "hindi english" to "hi",
        "arabic english" to "ar",
        "chinese bilingual" to "zh",
    )

    init {
        for ((code, threes, label) in ROWS) {
            val lang = SubLang(code, label)
            byCode[code] = lang
            for (three in threes) byCode[three] = lang
            byName[label.lowercase()] = lang
            byName[label.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()] = lang
        }
        // A few spellings the table's own label does not produce.
        byName["chinese"] = byCode["zh"]!!
        byName["persian farsi"] = byCode["fa"]!!
        byName["portuguese brazilian"] = SubLang("pt", "Portuguese (BR)")
        byName["brazilian portuguese"] = SubLang("pt", "Portuguese (BR)")
        byName["norwegian bokmal"] = byCode["no"]!!
    }

    /** The language [raw] names — a code, a three-letter code, an English name,
     *  or a name with a country in it. Never null: an unrecognised value keeps
     *  its own spelling as the label, so a site can invent one without the row
     *  going blank. */
    fun of(raw: String?): SubLang {
        val s = raw?.trim().orEmpty()
        if (s.isBlank()) return SubLang("", "Subtitle")
        val low = s.lowercase()
        byCode[low]?.let { return refine(it, low) }
        val base = low.substringBefore('-').substringBefore('_').substringBefore('/')
            .substringBefore(' ').trim()
        byCode[base]?.let { return refine(it, low) }
        val key = low.replace(Regex("[^a-z0-9]+"), " ").trim()
        ALIASES[key]?.let { code -> byCode[code]?.let { return refine(it, low) } }
        byName[key]?.let { return it }
        // Last resort: the site's own spelling, tidied into something printable.
        val label = s.replace('-', ' ').replace('_', ' ')
            .split(' ').filter { it.isNotBlank() }
            .joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
        return SubLang(if (base.length in 2..3) base else "", label)
    }

    /** Country variants the table collapses: `pt-BR` and `zh-TW` are worth
     *  telling apart, `en-US` is not. */
    private fun refine(base: SubLang, low: String): SubLang = when {
        base.code == "pt" && ("br" in low.split('-', '_', ' ') || low.contains("brazil")) ->
            SubLang("pt", "Portuguese (BR)")
        base.code == "zh" && (low.contains("tw") || low.contains("hk") ||
            low.contains("hant") || low.contains("traditional") || low.contains("big")) ->
            SubLang("zh", "Chinese (Traditional)")
        base.code == "es" && (low.contains("419") || low.contains("lat")) ->
            SubLang("es", "Spanish (Latin)")
        else -> base
    }

    /**
     * How far down the list a track in this language belongs for a user whose
     * own language is [wanted]: their language, then English, then everything
     * else, then a track whose language the site never said.
     */
    fun rank(lang: String, wanted: String): Int {
        val c = lang.lowercase()
        return when {
            c.isBlank() -> 3
            wanted.isNotBlank() && c == wanted -> 0
            wanted.isNotBlank() && c.startsWith(wanted) -> 1
            wanted.isNotBlank() && wanted.startsWith(c) -> 1
            c == "en" -> 2
            else -> 4
        }
    }
}

/** What a subtitle search knows about the title being watched. */
data class SubtitleQuery(
    /** The name to search for — the box's own text, so the user can retype it. */
    val title: String,
    val year: Int? = null,
    /** `tt…` — resolved by the caller ([SubtitleIds]) so every site that wants
     *  one can use it; blank when the title could not be placed. */
    val imdbId: String = "",
    /** The TMDB id, when the item carries one (digits only). */
    val tmdbId: String = "",
    val isSeries: Boolean = false,
    val season: Int = 0,
    val episode: Int = 0,
    /** The app's own language ("ar"): used to order the results. */
    val locale: String = "",
)

/**
 * One subtitle a site offered, BEFORE it is downloaded.
 *
 * [url] is a direct file URL in every case (that is what the sites were picked
 * for): the player hands it to the same downloader, decoder and cue-check a
 * hand-picked file gets, so a site that answers with a landing page, a dead
 * link or a cue-less stub is rejected there instead of becoming a phantom row.
 */
data class SiteTrack(
    val siteId: String,
    val siteName: String,
    /** ISO-639-1 when the site said one, else blank. */
    val lang: String,
    /** "English" — what the row prints. */
    val langLabel: String,
    /** The release/file name the site printed. */
    val release: String,
    val url: String,
    /** "srt" | "zip" | … — only ever a hint; the downloader sniffs the bytes. */
    val format: String = "",
    val downloads: Int = 0,
    val rating: Double = 0.0,
    val hearingImpaired: Boolean = false,
    val trusted: Boolean = false,
    /** Headers this track's download needs (a hot-link-protected host wants the
     *  page it was listed on as its Referer). Empty for the sites that serve
     *  files to anyone. */
    val headers: Map<String, String> = emptyMap(),
)

/** One site Hikari can search. Implementations are stateless and are all
 *  queried concurrently; a failure is simply an empty list. */
interface SubtitleSite {
    val id: String
    val name: String
    suspend fun search(q: SubtitleQuery): List<SiteTrack>
}

/** The registry the player's dialog and the automatic fetch both work from. */
object SubtitleSites {

    val ALL: List<SubtitleSite> = listOf(
        OpenSubtitlesSite,
        OpenSubtitlesOrgSite,
        SubdlSite,
        SubtitleCatSite,
        SubsceneSite,
        YifySite,
    )

    fun byId(id: String): SubtitleSite? = ALL.firstOrNull { it.id == id }

    /** A search result as the player's own subtitle pipeline takes it. */
    fun toSource(track: SiteTrack): SubtitleSource = SubtitleSource(
        // The code when the site named one (the player's list groups by it), the
        // printable name otherwise.
        lang = track.lang.ifBlank { track.langLabel },
        url = track.url,
        name = listOf(track.siteName, track.release)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
            .take(120),
        headers = track.headers,
    )
}

/**
 * The one thing OpenSubtitles and Subscene need and a site-scraper item does not
 * carry: an `tt…` id (OpenSubtitles answers nothing else) and a year to match a
 * search result against.
 *
 * Resolution runs in the order that costs least: an id the item already carries,
 * then TMDB (through [com.hikari.app.nuvio.TmdbResolver], which resolves a
 * scraped title by NAME and is already cached and single-flight), then IMDb's
 * own suggestion endpoint, which needs no key and finds what TMDB could not.
 */
object SubtitleIds {

    suspend fun imdb(title: String, year: Int?, tmdbId: String, isSeries: Boolean): String =
        withContext(Dispatchers.IO) {
            val kind = if (isSeries) "tv" else "movie"
            val digits = tmdbId.takeWhile { it.isDigit() }
            if (digits.isNotEmpty()) {
                runCatching { com.hikari.app.data.TmdbBrowse.imdbId(digits, kind) }
                    .getOrNull()?.takeIf { it.startsWith("tt") }?.let { return@withContext it }
            }
            suggest(title, year, isSeries)
        }

    /** IMDb's suggestion endpoint answers `d:[{ l: title, y: year, q: "feature"|
     *  "TV series", id: "tt…" }]` for a name, with no API key. */
    private suspend fun suggest(title: String, year: Int?, isSeries: Boolean): String {
        val name = title.trim()
        if (name.length < 2) return ""
        val q = H.enc(name.lowercase())
        val body = Http.getStringQuiet(
            "https://v3.sg.media-imdb.com/suggestion/h/$q.json",
            mapOf("Accept" to "application/json"),
        ) ?: return ""
        val arr = runCatching { JSONObject(body).optJSONArray("d") }.getOrNull() ?: return ""
        val wanted = name.lowercase()
        var best = ""
        var bestScore = 0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").trim()
            if (!id.startsWith("tt")) continue
            val label = o.optString("l").lowercase().trim()
            if (label.isBlank()) continue
            var score = 0
            if (label == wanted) score += 50
            else if (wanted.length >= 5 && (label.startsWith(wanted) || wanted.startsWith(label))) score += 20
            else continue
            if (year != null && o.optString("y") == year.toString()) score += 30
            val isTv = o.optString("q").contains("TV", ignoreCase = true)
            if (isTv == isSeries) score += 5
            if (score > bestScore) {
                bestScore = score
                best = id
            }
        }
        return best
    }
}

// ---------------------------------------------------------------------------
//  Shared parsing
// ---------------------------------------------------------------------------

/** The little bit of HTML handling every scraper here needs. */
private object H {

    fun strip(s: String): String = s.replace(Regex("<[^>]*>"), " ")

    fun unescape(s: String): String {
        if (s.indexOf('&') < 0) return s
        var t = s
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&#039;", "'")
            .replace("&apos;", "'")
            .replace("&hellip;", "…")
        t = Regex("&#x([0-9a-fA-F]+);").replace(t) { m ->
            runCatching { String(Character.toChars(m.groupValues[1].toInt(16))) }.getOrDefault("")
        }
        t = Regex("&#(\\d+);").replace(t) { m ->
            runCatching { String(Character.toChars(m.groupValues[1].toInt())) }.getOrDefault("")
        }
        return t
    }

    /** Tags, entities and runs of whitespace out — a value fit to print. */
    fun clean(s: String?): String =
        unescape(strip(s.orEmpty())).replace(Regex("\\s+"), " ").trim()

    fun enc(s: String): String =
        runCatching { java.net.URLEncoder.encode(s, "UTF-8") }.getOrDefault(s)

    /** For a URL PATH segment: a space must be `%20`, never `+`. */
    fun encPath(s: String): String = enc(s).replace("+", "%20")

    /** A comparison form of a title: case, punctuation and spacing removed, so
     *  "Moana (2016)" and "moana-2016" are the same thing. */
    fun key(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    fun fileExt(url: String): String {
        val last = url.substringAfterLast('/').substringBefore('?')
        return last.substringAfterLast('.', "").lowercase().takeIf { it.length in 2..4 } ?: ""
    }
}

// ---------------------------------------------------------------------------
//  1. OpenSubtitles
// ---------------------------------------------------------------------------

/** OpenSubtitles' own catalogue, through the public mirror the Stremio
 *  subtitle addon uses. It answers only for a `tt…` id, and it hands back a
 *  download URL per track — so one request searches and downloads in one go. */
private object OpenSubtitlesSite : SubtitleSite {
    override val id = "opensubtitles"
    override val name = "OpenSubtitles"
    private const val BASE = "https://opensubtitles-v3.strem.io"

    override suspend fun search(q: SubtitleQuery): List<SiteTrack> = withContext(Dispatchers.IO) {
        val imdb = q.imdbId.trim()
        if (!imdb.startsWith("tt")) return@withContext emptyList()
        val seg = if (q.isSeries) "series" else "movie"
        val mediaId = if (q.isSeries && q.season > 0 && q.episode > 0) {
            "$imdb:${q.season}:${q.episode}"
        } else imdb
        val body = Http.getStringQuiet(
            "$BASE/subtitles/$seg/$mediaId.json",
            mapOf("Accept" to "application/json"),
        ) ?: return@withContext emptyList()
        val arr = runCatching { JSONObject(body).optJSONArray("subtitles") }.getOrNull()
            ?: return@withContext emptyList()
        val out = ArrayList<SiteTrack>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("url").trim()
            if (url.isBlank()) continue
            val lang = SubtitleLang.of(o.optString("lang"))
            val fileName = o.optString("subtitleFileName").trim()
            val release = fileName.ifBlank { o.optString("movieReleaseName").trim() }
            out += SiteTrack(
                siteId = id,
                siteName = name,
                lang = lang.code,
                langLabel = lang.label,
                release = release,
                url = url,
                format = H.fileExt(fileName).ifBlank { "srt" },
                downloads = o.optString("g").toIntOrNull() ?: 0,
                headers = mapOf("Referer" to "$BASE/"),
            )
        }
        out.take(60)
    }
}

/** opensubtitles.org's search API — the one that answers by NAME, and that
 *  reports each track's download count and rating. Its file URLs are served by
 *  the same public mirror, keyed by the file id it returns. */
private object OpenSubtitlesOrgSite : SubtitleSite {
    override val id = "opensubtitles-org"
    override val name = "OpenSubtitles search"
    private const val BASE = "https://rest.opensubtitles.org"
    private const val FILES = "https://subs5.strem.io/en/download/subencoding-stremio-utf8/src-api/file/"

    override suspend fun search(q: SubtitleQuery): List<SiteTrack> = withContext(Dispatchers.IO) {
        val imdb = q.imdbId.trim()
        val criteria = ArrayList<String>(3)
        if (q.isSeries && q.season > 0 && q.episode > 0) {
            criteria += "episode-" + q.episode
            criteria += "season-" + q.season
        }
        if (imdb.startsWith("tt")) {
            criteria += "imdbid-" + imdb.removePrefix("tt")
        } else {
            // No id: fall back to the full-text search by name, which is the
            // half of this site that OpenSubtitles' id endpoint cannot do.
            val name = q.title.trim()
            if (name.length < 2) return@withContext emptyList()
            criteria += "query-" + H.encPath(name)
        }
        // No language segment: the API's `sublanguageid-all` is refused (HTTP
        // 403 from its WAF) while the criteria-only form returns every
        // language, which is what the list wants to show anyway.
        val body = Http.getStringQuiet(
            "$BASE/search/" + criteria.joinToString("/"),
            mapOf("Accept" to "application/json"),
        ) ?: return@withContext emptyList()
        val arr = runCatching { JSONArray(body) }.getOrNull() ?: return@withContext emptyList()
        val out = ArrayList<SiteTrack>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("SubBad") == "1") continue
            val fileId = o.optString("IDSubtitleFile").trim()
            if (fileId.isBlank() || fileId == "0") continue
            val langRaw = o.optString("ISO639").ifBlank { o.optString("SubLanguageID") }
            val lang = SubtitleLang.of(langRaw.ifBlank { o.optString("LanguageName") })
            val fileName = o.optString("SubFileName").trim()
            out += SiteTrack(
                siteId = id,
                siteName = name,
                lang = lang.code,
                langLabel = lang.label.ifBlank { o.optString("LanguageName").trim() },
                release = fileName.ifBlank { o.optString("MovieReleaseName").trim() },
                url = FILES + fileId,
                format = o.optString("SubFormat").trim().ifBlank { H.fileExt(fileName).ifBlank { "srt" } },
                downloads = o.optString("SubDownloadsCnt").toIntOrNull() ?: 0,
                rating = o.optString("SubRating").toDoubleOrNull() ?: 0.0,
                hearingImpaired = o.optString("SubHearingImpaired") == "1",
                trusted = o.optString("SubFromTrusted") == "1",
                headers = mapOf("Referer" to "https://www.opensubtitles.org/"),
            )
        }
        out.sortedByDescending { it.downloads }.take(60)
    }
}

// ---------------------------------------------------------------------------
//  3. SubDL
// ---------------------------------------------------------------------------

/** SubDL. `/search/<title>` finds the title page, and the title page lists
 *  every language as its own block with a direct `.zip` per release — so two
 *  requests answer with the whole shelf, languages and all. */
private object SubdlSite : SubtitleSite {
    override val id = "subdl"
    override val name = "SubDL"
    private const val BASE = "https://subdl.com"

    override suspend fun search(q: SubtitleQuery): List<SiteTrack> = withContext(Dispatchers.IO) {
        val title = q.title.trim()
        if (title.length < 2) return@withContext emptyList()
        val search = Http.getStringQuiet("$BASE/search/" + H.encPath(H.key(title).replace(' ', '-')))
            ?: return@withContext emptyList()
        // Each result is `<a href="/subtitle/sd2456/moana"> … <img alt="Moana">`.
        val found = Regex("href=\"(/subtitle/(sd\\d+)/[^\"]*)\"[\\s\\S]{0,700}?alt=\"([^\"]*)\"")
            .findAll(search)
            .map { it.groupValues[1] to H.clean(it.groupValues[3]) }
            .distinctBy { it.first }
            .toList()
        if (found.isEmpty()) return@withContext emptyList()
        val want = H.key(title)
        val pick = found.firstOrNull { H.key(it.second) == want }
            ?: found.firstOrNull { H.key(it.second).startsWith(want) || want.startsWith(H.key(it.second)) }
            ?: found.first()
        val page = Http.getStringQuiet(BASE + pick.first) ?: return@withContext emptyList()
        parse(page).take(70)
    }

    /** One track per release row of the title page, grouped by language. The
     *  page announces each group with `data-language`/`data-language-name`, and
     *  every row inside it carries its download count, its release name and a
     *  `dl.subdl.com/subtitle/<id>.zip` link. */
    private fun parse(page: String): List<SiteTrack> {
        val out = ArrayList<SiteTrack>()
        val langAt = Regex("data-language=\"([a-z0-9\\-]+)\"\\s+data-language-name=\"([^\"]*)\"")
            .findAll(page)
            .map { it.range.first to (it.groupValues[1] to H.clean(it.groupValues[2])) }
            .toList()
        for ((index, pair) in langAt.withIndex()) {
            val end = langAt.getOrNull(index + 1)?.first ?: page.length
            val block = page.substring(index, minOf(end, page.length))
            val lang = SubtitleLang.of(pair.second.ifBlank { pair.first })
            for (row in block.split("<li").drop(1)) {
                val tag = row.substringBefore('>')
                val downloads = Regex("data-downloads=\"(\\d+)\"").find(tag)?.groupValues?.get(1)
                    ?.toIntOrNull() ?: 0
                val rowId = Regex("data-id=\"(\\d+)\"").find(tag)?.groupValues?.get(1) ?: ""
                val release = H.clean(Regex("<h4[^>]*>([\\s\\S]*?)</h4>").find(row)?.groupValues?.get(1))
                val url = Regex("href=\"(https://dl\\.subdl\\.com/[^\"]+)\"").find(row)
                    ?.groupValues?.get(1) ?: continue
                if (release.isBlank() && rowId.isBlank()) continue
                out += SiteTrack(
                    siteId = id,
                    siteName = name,
                    lang = lang.code,
                    langLabel = lang.label,
                    release = release,
                    url = url,
                    format = "zip",
                    downloads = downloads,
                    hearingImpaired = tag.contains("data-hi"),
                )
            }
        }
        return out.distinctBy { it.url }.sortedByDescending { it.downloads }
    }
}

// ---------------------------------------------------------------------------
//  4. SubtitleCat
// ---------------------------------------------------------------------------

/** SubtitleCat re-serves what OpenSubtitles/Addic7ed and friends hold, as a
 *  plain `.srt` per language.
 *
 *  Its search lists RELEASES, and a release's languages live on its own page, so
 *  the three most-downloaded releases are opened and expanded — that is what
 *  makes a row say "Arabic" instead of "Moana.2016.1080p.WEB-DL…" and leaves the
 *  user picking a language rather than guessing at a rip name. */
private object SubtitleCatSite : SubtitleSite {
    override val id = "subtitlecat"
    override val name = "SubtitleCat"
    private const val BASE = "https://subtitlecat.com"

    override suspend fun search(q: SubtitleQuery): List<SiteTrack> = withContext(Dispatchers.IO) {
        val title = q.title.trim()
        if (title.length < 2) return@withContext emptyList()
        val search = Http.getStringQuiet("$BASE/index.php?search=" + H.enc(title))
            ?: return@withContext emptyList()
        val releases = Regex("<tr>\\s*<td>\\s*<a href=\"(subs/[^\"]+\\.html)\"[^>]*>([\\s\\S]*?)</a>([\\s\\S]*?)</tr>")
            .findAll(search)
            .map { m ->
                val downloads = Regex("Downloads</span><span class=\"sub-table__metric-value\">(\\d+)")
                    .find(m.groupValues[3])?.groupValues?.get(1)?.toIntOrNull() ?: 0
                Triple(m.groupValues[1], H.clean(m.groupValues[2]), downloads)
            }
            .distinctBy { it.first }
            .sortedByDescending { it.third }
            .take(3)
            .toList()
        if (releases.isEmpty()) return@withContext emptyList()
        val perRelease = coroutineScope {
            releases.map { (href, releaseName, downloads) ->
                async {
                    val page = Http.getStringQuiet("$BASE/" + href.trimStart('/')) ?: return@async emptyList<SiteTrack>()
                    val tracks = ArrayList<SiteTrack>()
                    for (m in Regex("<a id=\"download_([A-Za-z\\-]+)\"[^>]*href=\"([^\"]+)\"")
                        .findAll(page)) {
                        val code = m.groupValues[1]
                        val url = m.groupValues[2].let {
                            if (it.startsWith("http")) it else BASE + "/" + it.trimStart('/')
                        }
                        if (!url.contains(".srt", true) && !url.contains(".ass", true)) continue
                        val lang = SubtitleLang.of(code)
                        tracks += SiteTrack(
                            siteId = id,
                            siteName = name,
                            lang = lang.code,
                            langLabel = lang.label,
                            release = releaseName,
                            url = url,
                            format = H.fileExt(url).ifBlank { "srt" },
                            downloads = downloads,
                            headers = mapOf("Referer" to "$BASE/" + href.trimStart('/')),
                        )
                    }
                    tracks
                }
            }.awaitAll().flatten()
        }
        // One row per language: the list is a language menu, not a wall of the
        // same film's rips.
        perRelease
            .sortedByDescending { it.downloads }
            .distinctBy { it.langLabel }
            .take(60)
        }
}

// ---------------------------------------------------------------------------
//  5. Subscene
// ---------------------------------------------------------------------------

/** Subscene: `/search?query=<title>` finds the title (with its year), and the
 *  title page lists every subtitle it holds — language, release, hearing-
 *  impaired flag — with a `.zip` at a URL built from the subtitle's own id. */
private object SubsceneSite : SubtitleSite {
    override val id = "subscene"
    override val name = "Subscene"
    private const val BASE = "https://subscene.best"
    private const val FILES = "https://res.subscene.best/file/"

    override suspend fun search(q: SubtitleQuery): List<SiteTrack> = withContext(Dispatchers.IO) {
        val title = q.title.trim()
        if (title.length < 2) return@withContext emptyList()
        val search = Http.getStringQuiet("$BASE/search?query=" + H.enc(title))
            ?: return@withContext emptyList()
        val entries = Regex("<div class=\"title\">\\s*<a href=\"/subscene/(\\d+)\">([\\s\\S]*?)</a>")
            .findAll(search)
            .map { it.groupValues[1] to H.clean(it.groupValues[2]) }
            .toList()
        if (entries.isEmpty()) return@withContext emptyList()
        val want = H.key(title)
        val pick = entries.firstOrNull { H.key(it.second.substringBefore('(')) == want }
            ?: entries.firstOrNull { H.key(it.second).startsWith(want) }
            ?: entries.first()
        val page = Http.getStringQuiet("$BASE/subscene/" + pick.first) ?: return@withContext emptyList()
        parse(page).take(80)
    }

    /** The title page is one `<table>` whose rows are
     *  `<td class="a1"><a href="/subtitle/123"><div><span class="l r …">Arabic</span>
     *  <span class="new">Release.Name</span></div></a></td>` followed by the
     *  file count, the H.I. cell and the uploader. */
    private fun parse(page: String): List<SiteTrack> {
        val out = ArrayList<SiteTrack>()
        for (chunk in page.split("<td class=\"a1\"").drop(1)) {
            val head = chunk.take(1400)
            val subId = Regex("<a href=\"/subtitle/(\\d+)\"").find(head)?.groupValues?.get(1) ?: continue
            val langName = H.clean(
                Regex("<span class=\"[^\"]*\">\\s*([^<]*?)\\s*</span>").find(head)?.groupValues?.get(1)
            )
            val release = H.clean(
                Regex("<span class=\"new\">\\s*([\\s\\S]*?)\\s*</span>").find(head)?.groupValues?.get(1)
            )
            if (langName.isBlank()) continue
            val hiCell = Regex("<td class=\"a40\"[^>]*>([\\s\\S]{0,60}?)</td>").find(chunk)
                ?.groupValues?.get(1)
            val lang = SubtitleLang.of(langName)
            out += SiteTrack(
                siteId = id,
                siteName = name,
                lang = lang.code,
                langLabel = lang.label,
                release = release,
                url = FILES + subId + ".zip",
                format = "zip",
                hearingImpaired = hiCell != null && !hiCell.contains("nbsp") && H.clean(hiCell).isNotBlank(),
            )
        }
        return out.distinctBy { it.url }
    }
}

// ---------------------------------------------------------------------------
//  6. YIFY
// ---------------------------------------------------------------------------

/** The YIFY subtitle mirror. Its title page is a table of
 *  `<tr data-id> <td>rating</td> <td><span class="sub-lang">Arabic</span></td>
 *  <td><a href="/subtitles/<slug>">release</a></td>`, and each of those has a
 *  `.zip` at `/subtitle/<slug>.zip`.
 *
 *  That download is hot-link protected — fetched bare it answers HTTP 403 — so
 *  every track carries the title page as its Referer, which is what the file is
 *  served for. */
private object YifySite : SubtitleSite {
    override val id = "yify"
    override val name = "YIFYSubtitles"
    private const val HOST = "https://yifysubtitles.ch"

    override suspend fun search(q: SubtitleQuery): List<SiteTrack> = withContext(Dispatchers.IO) {
        val imdb = q.imdbId.trim()
        var page = if (imdb.startsWith("tt")) {
            Http.getStringQuiet("$HOST/movie-imdb/$imdb") ?: ""
        } else ""
        if (page.isBlank() || !page.contains("/subtitles/")) {
            val title = q.title.trim()
            if (title.length < 2) return@withContext emptyList()
            val search = Http.getStringQuiet("$HOST/search?q=" + H.encPath(title))
                ?: return@withContext emptyList()
            val hit = Regex("href=\"(/movie-imdb/tt\\d+)\"").find(search)?.groupValues?.get(1)
                ?: Regex("href=\"(/movie/[^\"]+)\"").find(search)?.groupValues?.get(1)
                ?: return@withContext emptyList()
            page = Http.getStringQuiet(HOST + hit) ?: return@withContext emptyList()
        }
        val out = ArrayList<SiteTrack>()
        for (row in page.split("<tr data-id=\"").drop(1)) {
            val head = row.take(1600)
            val langName = H.clean(Regex("<span class=\"sub-lang\">([\\s\\S]*?)</span>").find(head)?.groupValues?.get(1))
            val href = Regex("href=\"(/subtitles/[^\"]+)\"").find(head)?.groupValues?.get(1) ?: continue
            val release = H.clean(
                Regex("href=\"/subtitles/[^\"]+\"[^>]*>([\\s\\S]*?)</a>").find(head)?.groupValues?.get(1)
            )
            val rating = Regex("label-success\">([\\d.]+)<").find(head)?.groupValues?.get(1)
                ?.toDoubleOrNull() ?: 0.0
            val lang = SubtitleLang.of(langName)
            if (lang.label.isBlank() || lang.label == "Subtitle") continue
            out += SiteTrack(
                siteId = id,
                siteName = name,
                lang = lang.code,
                langLabel = lang.label,
                release = release,
                url = HOST + "/subtitle/" + href.removePrefix("/subtitles/") + ".zip",
                format = "zip",
                rating = rating,
                headers = mapOf("Referer" to HOST + href),
            )
        }
        out.distinctBy { it.url }.take(80)
    }
}
