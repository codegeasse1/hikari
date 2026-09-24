package com.hikari.app.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * The watch-progress services Hikari can sign in to (Settings → Trackers).
 *
 * The same shape CloudStream and Aniyomi offer: the user signs in ONCE with the
 * account they already have, and from then on what they watch in Hikari shows up
 * on that service's list without them ever opening it. Nothing is uploaded until
 * a title has actually been watched far enough to count (see
 * [com.hikari.app.tracker.TrackerSync]), and no service is contacted at all
 * until the user signs in to it.
 *
 * **Each service keeps its OWN credentials.** Every one of these APIs needs an
 * "app" registered with it (a client id, sometimes a secret) before anyone can
 * log in — that is how each service tells one third-party client from another.
 * Hikari deliberately does not ship somebody else's registered app: an app id
 * belongs to the developer who registered it, using it would impersonate them,
 * and it gets the whole app locked out the moment that registration is revoked
 * or rate-limited. So the card asks for the user's own, once per service, with
 * the exact page to get it ([registerUrl]) and the exact redirect URI to paste
 * ([com.hikari.app.tracker.TrackerApi.REDIRECT_URI]) spelled out. One extra
 * step, and it is the step that makes the sign-in actually work for them.
 */
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
        TrackerFlow.TOKEN,
        "https://anilist.co/settings/developer",
        false,
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
        true,
        TrackerScope.ANIME,
        "Anime — sign in with the account itself, no browser needed",
    ),
    SIMKL(
        "simkl",
        "Simkl",
        TrackerFlow.PIN,
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
    /** The access token arrives in the redirect (AniList's implicit grant). */
    TOKEN,

    /** An authorization code arrives in the redirect and is exchanged. */
    CODE,

    /** No browser at all: the account's own e-mail and password (Kitsu). */
    PASSWORD,

    /** The app shows a short code, the user types it on the service's site. */
    PIN,

    /** Same as [PIN], but the code is confirmed on a device-code page (Trakt). */
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

/**
 * One signed-in service, as stored. The token is a secret — it is a live session
 * on the user's own account: it lives in the app's private DataStore, is only
 * ever sent to the service that issued it, and is dropped the moment the user
 * signs out (see [AppStore.removeTrackerAccount]).
 */
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
)

/**
 * The title a push is about, in the terms a tracker search understands.
 *
 * Hikari's own [MediaType] only knows MOVIE/SERIES — an anime film and a
 * Hollywood film are the same value — so the services' own databases decide the
 * rest: an anime-only tracker searched for a live-action title simply finds no
 * confident match, which is the correct outcome and needs no hint from here.
 */
data class TrackerMedia(
    val title: String,
    val year: Int = 0,
    /** True for a film (no episode number). */
    val movie: Boolean = false,
    /** Episode number being watched (0 for a film or an unknown episode). */
    val episode: Int = 0,
    /** Season number, when the extension knows it (0 = unknown). */
    val season: Int = 0,
)

/**
 * The title's words, lowercased and stripped of everything that differs between
 * an extension's spelling and a tracker's: punctuation, accents, a leading
 * article, a trailing season/cour marker, a year, and the release-tag noise an
 * extension puts in its names ("Hindi Dub", "1080p", "Complete").
 *
 * "Re:Zero − Starting Life in Another World" and "ReZERO Starting Life in
 * Another World" land on the same key, and so do "One Piece" and "One Piece
 * [Hindi Dub] 1080p" — which is the point: without this, one extension's
 * punctuation or language tag would look like a different show to every tracker.
 */
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
    // Words that carry no title information at all. Kept as a LIST rather than a
    // regex so the reason for each one is readable: a language/quality tag is
    // how an extension spells the same show, and "the"/"of" disappear from one
    // service's title and not another's.
    for (noise in NOISE_WORDS) s = s.replace(Regex("\\b$noise\\b"), " ")
    return s.trim().replace(Regex("\\s+"), " ")
}

/**
 * Words dropped from a title before comparing it (see [normalizeTitle]).
 *
 * These are exactly the words that appear in ONE of the two spellings of a title
 * and not the other: the release tags an extension appends, and the little words
 * one database keeps and another drops. A word that changes WHICH title it is
 * ("shippuden", "brotherhood", "z", "gt") is deliberately absent — those must
 * keep two different shows apart.
 */
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

/**
 * How well a candidate's title matches what is being watched — the whole reason
 * an automatic push is safe to make at all.
 *
 * Watching "Naruto" must never mark "Naruto Shippuden" episode 5 complete, and
 * an extension's title is often the ROMAJI one while the tracker lists the
 * English name (or the reverse), so an exact string compare alone would quietly
 * refuse most real matches. Both lists are normalised first ([normalizeTitle]):
 * punctuation, articles, release tags, season numbers and years are already
 * gone by the time anything is scored.
 *
 *  * identical after normalising → 1.0;
 *  * one is the other with only trailing noise → 1.0 (the noise is already
 *    removed, so this is the identical case, and it is why "One Piece Dub" finds
 *    "One Piece");
 *  * the shorter one's words are a strict SUBSET → 0.85 at most, i.e. under
 *    [TrackerMatch.AUTO_THRESHOLD] on purpose: "Naruto" ⊂ "Naruto Shippuden" is
 *    exactly the pair that must not be pushed without being sure;
 *  * otherwise the Sørensen–Dice overlap of the two word sets.
 *
 * A year mismatch costs 0.15 when both years are known, which is what keeps a
 * remake apart from the original of the same name.
 */
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

/**
 * The stored shape of the tracker rows — plain JSON, so a row written by an
 * older build (or restored from a backup) is read as far as it goes instead of
 * throwing the whole list away. A row that cannot be read is skipped, never
 * fatal: one corrupt entry must not cost the user their other sign-ins.
 */
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

    /**
     * The matches already resolved, so a push needs no search: the same title
     * watched twenty times must not mean twenty searches (the services rate-limit,
     * and the user is waiting on the next episode), and the answer for a title
     * never changes unless the cache is dropped.
     */
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
                )
            }
            out
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /**
     * What has already been reported, as `key → when`. Without this, "Sync
     * watch history" would re-upload everything every time it is pressed (and
     * Simkl/Trakt would count each one as a separate play).
     */
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
