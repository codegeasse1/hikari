package com.hikari.app.data

import org.json.JSONArray
import org.json.JSONObject

enum class TrackerKind(
    /** Stable id — what the stored rows are keyed by, never shown. */
    val key: String,
    val label: String,
    /** How the user signs in. */
    val flow: TrackerFlow,
    /** Where the app the sign-in needs is created. */
    val registerUrl: String,
    /** Whether that page also hands out a client SECRET. */
    val needsSecret: Boolean,
    /** The kinds of title this service keeps a list of. */
    val scope: TrackerScope,
    /** One line on the card: what this service is actually good for. */
    val blurb: String,
) {
    ANILIST(
        "anilist",
        "AniList",
        TrackerFlow.CODE,
        "https://anilist.co/settings/developer",
        true,
        TrackerScope.ANIME,
        "Anime and manga — the database most extensions already use",
    ),
    MAL(
        "mal",
        "MyAnimeList",
        TrackerFlow.CODE,
        "https://myanimelist.net/apiconfig/create",
        false,
        TrackerScope.ANIME,
        "Anime — the oldest and most widely used list",
    ),
    KITSU(
        "kitsu",
        "Kitsu",
        TrackerFlow.PASSWORD,
        "https://kitsu.io/settings/apps",
        false,
        TrackerScope.ANIME,
        "Anime — sign in with the account itself, no browser needed",
    ),
    SIMKL(
        "simkl",
        "Simkl",
        TrackerFlow.DEVICE,
        "https://simkl.com/settings/developer",
        false,
        TrackerScope.ANIME_AND_VIDEO,
        "Anime, series and films in one list — no password, just a code",
    ),
    SHIKIMORI(
        "shikimori",
        "Shikimori",
        TrackerFlow.CODE,
        "https://shikimori.one/oauth/applications",
        true,
        TrackerScope.ANIME,
        "Anime — the Russian-language community's list",
    ),
    TRAKT(
        "trakt",
        "Trakt",
        TrackerFlow.DEVICE,
        "https://trakt.tv/oauth/applications",
        false,
        TrackerScope.VIDEO,
        "Films and series — the one to use for live-action",
    ),
    ;

    companion object {
        fun of(key: String): TrackerKind? = TrackerKind.entries.firstOrNull { it.key == key }
    }
}

/** How a service's sign-in works — see [com.hikari.app.tracker.TrackerApi]. */
enum class TrackerFlow {
    TOKEN,

    /** An authorization code arrives in the redirect and is exchanged. */
    CODE,

    /** No browser at all: the account's own e-mail and password (Kitsu). */
    PASSWORD,

    /** The app shows a short code, the user types it on the service's site. */
    PIN,

    /** Same as [PIN], but the code is confirmed on a device-code page (Trakt/Simkl OAuth 2). */
    DEVICE,
}

/** What kinds of title a service keeps a list of. Drives matching, not input. */
enum class TrackerScope {
    ANIME, VIDEO, ANIME_AND_VIDEO;

    /** Does this service keep anime (which is what Hikari's extensions list)? */
    val tracksAnime: Boolean get() = this != VIDEO

    /** Does it keep live-action films and series? */
    val tracksVideo: Boolean get() = this != ANIME
}

data class TrackerAccount(
    val kind: TrackerKind,
    /** The account's display name, as the service reports it. */
    val user: String = "",
    /** The account's id, where an API needs it in the URL or body (Kitsu, MAL). */
    val userId: String = "",
    val token: String = "",
    /** Refresh token, when the service issues one. */
    val refresh: String = "",
    /** When [token] stops working (epoch ms; 0 = no expiry known). */
    val expiresAt: Long = 0L,
) {
    /** Whether the stored token still has life left in it (5-minute margin). */
    val expired: Boolean
        get() = expiresAt > 0L && System.currentTimeMillis() > expiresAt - 5 * 60_000L

    /** "AniList · codegeasse", for the card. */
    val describe: String get() = kind.label + if (user.isBlank()) "" else " · " + user
}

/** The app credentials the user registered with a service (see [TrackerKind]). */
data class TrackerClient(
    val kind: TrackerKind,
    val id: String = "",
    val secret: String = "",
) {
    val ready: Boolean get() = id.isNotBlank() && (!kind.needsSecret || secret.isNotBlank())
}

object TrackerBuiltIn {
    /** Client ids shipped with the app, keyed by [TrackerKind.key]. */
    private val IDS = mapOf(
        "anilist" to "51890",
        "mal" to "c5982f6a1db357b98a41ec116ba00001",
        "simkl" to "fe031828569ee8ca40d2d09988cb4f2f7ae04857220fef7009decd81b3857813",
        "shikimori" to "",
        "trakt" to "xEjFc5nG3iAzqghfVK_uaJFD6vkJedqZLNxyFnltz-M",
        // Kitsu takes the account itself — no app registration exists.
    )

    /** Client secrets shipped with the app, keyed by [TrackerKind.key]. */
    private val SECRETS = mapOf(
        "anilist" to "",
        "shikimori" to "",
    )

    fun idFor(kind: TrackerKind): String = IDS[kind.key].orEmpty().trim()
    fun secretFor(kind: TrackerKind): String = SECRETS[kind.key].orEmpty().trim()

    /** A ready-to-use client for [kind], or null when nothing is shipped (and
     *  the dialog must ask the user, as before). */
    fun clientFor(kind: TrackerKind): TrackerClient? {
        val id = idFor(kind)
        if (id.isBlank()) return null
        val c = TrackerClient(kind, id, secretFor(kind))
        return if (c.ready) c else null
    }
}

/** One candidate a tracker search returned, and how well it matched. */
data class TrackerMatch(
    /** The service's own id for this title. */
    val id: String,
    val title: String,
    /** Total episodes the service knows about (0 = unknown). */
    val total: Int = 0,
    /** What the service calls this title (`anime`, `movie`, `show`, a format). */
    val category: String = "",
    /** 0..1 — how sure the title match is (see [TrackerMatch.scoreOf]). */
    val score: Double = 0.0,
    /** The year the service lists, when it has one. */
    val year: Int = 0,
    /** The candidate-side name that scored best (a romaji/English/native title
     *  or a synonym) — the tie-break fuel that tells an exact display-title
     *  hit apart from an alias hit. */
    val matchedVia: String = "",
    /** The candidate's own English title ("" when the service gives none) —
     *  an entry whose English title IS the watched title beats one that only
     *  carries it as a synonym ("Hidden Marriage" vs "Full Marks Hidden
     *  Marriage"). */
    val english: String = "",
)

data class TrackerMedia(
    val title: String,
    val year: Int = 0,
    /** True for a film (no episode number). */
    val movie: Boolean = false,
    /** Episode number being watched (0 for a film or an unknown episode). */
    val episode: Int = 0,
    /** True for manga chapters (searched/pushed as manga, not anime). */
    val manga: Boolean = false,
    /** Season number, when the extension knows it (0 = unknown). */
    val season: Int = 0,
)

fun normalizeTitle(raw: String): String {
    val lowered = java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase()
    var s = lowered
    // A year, a season/cour/part number: the most common reason two spellings of
    // one title differ, and the difference between a match and a wrong match.
    s = s.replace(Regex("\\b(19|20)\\d{2}\\b"), " ")
    s = s.replace(Regex("\\b(season|s)\\s*\\d{1,2}\\b"), " ")
    s = s.replace(Regex("\\b(part|cour|chapter)\\s*\\d{1,2}\\b"), " ")
    s = s.replace(Regex("[^a-z0-9]+"), " ")
    for (noise in NOISE_WORDS) s = s.replace(Regex("\\b$noise\\b"), " ")
    return s.trim().replace(Regex("\\s+"), " ")
}

private val NOISE_WORDS = listOf(
    "the", "a", "an", "of", "and", "to", "in", "on", "no",
    "dub", "sub", "subbed", "dubbed", "hindi", "tamil", "telugu", "malayalam",
    "english", "japanese", "korean", "chinese", "spanish", "portuguese", "multi",
    "audio", "dual", "original", "complete", "uncut", "uncensored", "remastered",
    "hd", "fhd", "uhd", "sd", "1080p", "720p", "480p", "2160p", "4k", "webrip",
    "web", "webdl", "bluray", "bdrip", "dvdrip", "hdtv", "x264", "x265", "hevc",
    "avc", "aac", "batch", "full", "movie", "film", "ova", "ona", "oad", "special",
    "specials", "tv", "series", "shorts", "short",
)

/** The words of a normalised title, ignoring one-character ones. */
private fun wordsOf(normalized: String): List<String> =
    normalized.split(' ').filter { it.isNotEmpty() }

fun matchScore(wanted: String, candidate: String, wantedYear: Int = 0, candidateYear: Int = 0): Double {
    val a = normalizeTitle(wanted)
    val b = normalizeTitle(candidate)
    if (a.isBlank() || b.isBlank()) return 0.0
    val wa = wordsOf(a).toSet()
    val wb = wordsOf(b).toSet()
    var score = when {
        a == b -> 1.0
        wa.isEmpty() || wb.isEmpty() -> 0.0
        // One title's words are all in the other's ("naruto" vs "naruto
        // shippuden"): a plausible match, never an automatic one.
        wa.containsAll(wb) || wb.containsAll(wa) -> 0.85
        else -> 2.0 * wa.intersect(wb).size / (wa.size + wb.size).toDouble()
    }
    if (wantedYear > 0 && candidateYear > 0 && kotlin.math.abs(wantedYear - candidateYear) > 1) {
        score -= 0.15
    }
    return score.coerceIn(0.0, 1.0)
}

/** The score above which a match is pushed without asking. See [matchScore]. */
const val TRACKER_AUTO_THRESHOLD = 0.9

fun sequelBase(normalized: String): String {
    var s = " " + normalized.trim() + " "
    s = s.replace(Regex("\\b(movie|film|ova|ona|oad|special|specials|part|cour|season|s)\\s*\\d{1,2}\\b"), " ")
    s = s.replace(Regex("\\b(final|finale|season finale|last season|the final|the finale)\\b(\\s*\\d{1,2})?\\s*$"), " ")
    s = s.replace(Regex("\\b(movie|film|ova|ona|oad|special)\\s*$"), " ")
    return s.trim().replace(Regex("\\s+"), " ")
}

private val TITLE_ALIASES = listOf(
    setOf("doupo cangqiong", "battle through heavens", "fights break sphere", "fight sphere", "btth"),
    setOf("zhu xian", "jade dynasty"),
    setOf("xian ni", "renegade immortal"),
    setOf("a record of mortal journey to immortality", "a mortal journey to immortality"),
    setOf("one hundred thousand years of qi refining", "lian qi shi wan nian"),
    setOf("wu dong qian kun", "martial master", "martial universe"),
    setOf("against gods", "against god", "ni tian xie shen"),
    setOf("against sky supreme", "against the sky supreme"),
    setOf("stellar transformations", "stars transformation", "xing chen bian"),
    setOf("link click", "shiguang dailiren"),
    setOf("soul land", "douluo dalu", "douluo continent"),
    setOf("perfect world", "wanmei shijie"),
    setOf("swallowed star", "tunshi xingkong"),
    setOf("throne of seal", "shen yin wangzuo", "shen yin wang zuo"),
    setOf("tales of demons and gods", "yaoshenji", "yao shen ji"),
    setOf("a will eternal", "yi nian yongheng"),
    setOf("apotheosis", "bai lian cheng shen"),
    setOf("shrouding the heavens", "zhe tian"),
    // Probed against the live AniList catalogue 2026-10-17 (both directions):
    // the English title searches zero rows, the pinyin/romaji finds id 97828.
    setOf(
        "bringing nation s husband home",
        "bringing national husband home",
        "guomin laogong dai huijia",
        "guomin laogong dai huijia tou wen 55 ci",
    ),
)

/** Every spelling of [title] worth searching and scoring: the title itself,
 *  its sequel-stripped base, and any known alias forms. */
fun titleVariants(title: String): List<String> {
    val out = LinkedHashSet<String>()
    val n = normalizeTitle(title)
    if (n.isNotBlank()) out.add(n)
    val base = sequelBase(n)
    if (base.isNotBlank()) out.add(base)
    for (alias in TITLE_ALIASES) {
        if (alias.any { n == it || n.contains(it) || base == it }) {
            for (form in alias) {
                if (form.isNotBlank()) out.add(form.trim())
            }
            break
        }
    }
    return out.toList()
}

fun seasonFromTitle(title: String): Int {
    val t = title.trim()
    if (t.isEmpty()) return 0
    val pats = listOf(
        Regex("""(?i)\bseason\s*0?(\d{1,2})\b"""),
        Regex("""(?i)\b(\d{1,2})(?:st|nd|rd|th)\s+season\b"""),
        Regex("""(?i)\bpart\s*0?(\d)\b"""),
        Regex("""(?i)\bcour\s*0?(\d)\b"""),
        Regex("""(?i)\bs0?(\d{1,2})(?!\s*e\d)"""),
        Regex("""第([一二三四五六七八九十\d]{1,3})季"""),
    )
    for (re in pats) {
        val m = re.find(t) ?: continue
        val g = m.groupValues[1]
        val n = g.toIntOrNull() ?: cnNumeral(g)
        if (n in 1..30) return n
    }
    return 0
}

/** Chinese-numeral season (第一季 → 1 … 第十季 → 10) for [seasonFromTitle]. */
private fun cnNumeral(s: String): Int {
    val v = mapOf('一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
    if (s == "十") return 10
    if (s.startsWith("十")) return 10 + (v[s.last()] ?: 0)
    if (s.endsWith("十")) return (v[s.first()] ?: 0) * 10
    return v[s.singleOrNull()] ?: 0
}

fun isMainlineEntry(title: String, movie: Boolean): Boolean {
    val t = " $title ".lowercase()
    if (Regex("""\bspecials?\b""").containsMatchIn(t)) return false
    if (Regex("""\bova\b""").containsMatchIn(t)) return false
    if (Regex("""\boad\b""").containsMatchIn(t)) return false
    if (!movie && Regex("""\bmovie\b""").containsMatchIn(t)) return false
    if (!movie && Regex("""\bfilm\b""").containsMatchIn(t)) return false
    return true
}

fun exactTitleHit(wanted: String, c: TrackerMatch): Int {
    val w = normalizeTitle(wanted)
    if (w.isBlank()) return 0
    if (normalizeTitle(c.title) == w) return 1
    if (c.english.isNotBlank() && normalizeTitle(c.english) == w) return 1
    if (c.matchedVia.isNotBlank() && normalizeTitle(c.matchedVia) == w) return 1
    return 0
}

fun matchScoreAny(wanted: String, candidate: String, wantedYear: Int = 0, candidateYear: Int = 0): Double {
    var best = matchScore(wanted, candidate, wantedYear, candidateYear)
    val wb = sequelBase(normalizeTitle(wanted))
    val cb = sequelBase(normalizeTitle(candidate))
    if (wb.isNotBlank() && wb == cb) best = maxOf(best, 0.95)
    for (w in titleVariants(wanted)) {
        for (c in titleVariants(candidate)) {
            if (w == c) return 1.0
            best = maxOf(best, matchScore(w, c, wantedYear, candidateYear))
        }
    }
    return best.coerceIn(0.0, 1.0)
}

object TrackerStore {

    fun encodeAccounts(accounts: List<TrackerAccount>): String {
        val arr = JSONArray()
        for (a in accounts) {
            arr.put(
                JSONObject().apply {
                    put("kind", a.kind.key)
                    put("user", a.user)
                    put("userId", a.userId)
                    put("token", a.token)
                    put("refresh", a.refresh)
                    put("expires", a.expiresAt)
                }
            )
        }
        return arr.toString()
    }

    fun parseAccounts(json: String?): List<TrackerAccount> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val kind = TrackerKind.of(o.optString("kind")) ?: return@mapNotNull null
                val token = o.optString("token")
                if (token.isBlank()) return@mapNotNull null
                TrackerAccount(
                    kind = kind,
                    user = o.optString("user"),
                    userId = o.optString("userId"),
                    token = token,
                    refresh = o.optString("refresh"),
                    expiresAt = o.optLong("expires", 0L),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun encodeClients(clients: List<TrackerClient>): String {
        val arr = JSONArray()
        for (c in clients) {
            arr.put(
                JSONObject().apply {
                    put("kind", c.kind.key)
                    put("id", c.id)
                    put("secret", c.secret)
                }
            )
        }
        return arr.toString()
    }

    fun parseClients(json: String?): List<TrackerClient> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val kind = TrackerKind.of(o.optString("kind")) ?: return@mapNotNull null
                TrackerClient(kind, o.optString("id"), o.optString("secret"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** The key a matched title is remembered under: service + normalised title. */
    fun matchKey(kind: TrackerKind, title: String): String = kind.key + "|" + normalizeTitle(title)

    fun encodeMatches(matches: Map<String, TrackerMatch>): String {
        val arr = JSONArray()
        for ((key, m) in matches.entries.take(MAX_MATCHES)) {
            arr.put(
                JSONObject().apply {
                    put("key", key)
                    put("id", m.id)
                    put("title", m.title)
                    put("total", m.total)
                    put("cat", m.category)
                    put("year", m.year)
                    put("via", m.matchedVia)
                    put("en", m.english)
                }
            )
        }
        return arr.toString()
    }

    fun parseMatches(json: String?): Map<String, TrackerMatch> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val arr = JSONArray(json)
            val out = LinkedHashMap<String, TrackerMatch>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val key = o.optString("key")
                if (key.isBlank()) continue
                out[key] = TrackerMatch(
                    id = o.optString("id"),
                    title = o.optString("title"),
                    total = o.optInt("total", 0),
                    category = o.optString("cat"),
                    year = o.optInt("year", 0),
                    // A cached match was, by definition, confident enough to
                    // push with; it is not re-scored on the way out.
                    score = 1.0,
                    matchedVia = o.optString("via"),
                    english = o.optString("en"),
                )
            }
            out
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun encodeDone(done: Map<String, Long>): String {
        val arr = JSONArray()
        for ((key, at) in done.entries.sortedByDescending { it.value }.take(MAX_DONE)) {
            arr.put(JSONObject().put("k", key).put("at", at))
        }
        return arr.toString()
    }

    fun parseDone(json: String?): Map<String, Long> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val arr = JSONArray(json)
            val out = LinkedHashMap<String, Long>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val key = o.optString("k")
                if (key.isNotBlank()) out[key] = o.optLong("at", 0L)
            }
            out
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /** A resolved match is dropped past this many titles. */
    private const val MAX_MATCHES = 300

    /** And the pushed-episode log past this many entries (newest kept). */
    private const val MAX_DONE = 600
}
