package com.hikari.app.nuvio

import com.hikari.app.data.TmdbMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Real episode titles by absolute episode number, from TMDB.
 *
 * A site-scraping extension labels its rows after whatever its page shows:
 * mechanically ("Swallowed Star Episode 33 English Sub"), or with the show's
 * own native titles, which read as Chinese in an English UI. Both are replaced
 * ONLY when TMDB has a title for that episode.
 *
 * The language is a parameter ([lookup]): the app asks in the user's chosen
 * TMDB language when one is set, so the episode rows — and the player's top bar,
 * which prints the same name — read in that language, and English otherwise.
 * (It used to be English-only, which is why a page could be translated while its
 * episode list and the player's episode line stayed English.) When TMDB has no
 * title at all, the row keeps exactly what its source gave it: the site's own
 * name for an extension item, and TMDB's own name for a Nuvio item — which has
 * no site behind it, so there is nothing better to prefer. TMDB does supply one
 * extra fallback, its generic "Episode 33", but that is used ONLY to shorten a
 * source label that is pure noise (the site's "Show Name Episode 33 English
 * Sub"); it never overwrites a real title.
 *
 * Lookups are cached per title (and per language), empty results included
 * (briefly, so a transient failure is not re-queried on every detail-page open).
 */
object EpisodeTitles {

    private const val MAX_SEASONS = 24
    private const val MAX_EPISODES = 600
    private const val NEGATIVE_TTL_MS = 10 * 60 * 1000L

    /** TMDB is asked for English explicitly: with no `language` it answers in
     *  the show's original language whenever a title has no translation. */
    private const val LANGUAGE = "en-US"

    private val DIGITS = Regex("\\d")
    private val CJK = Regex("[\\u3040-\\u30FF\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uF900-\\uFAFF]")
    /** An episode tag anywhere in a name ("Episode 27", "第27集"). */
    private val EMBEDDED = Regex("(?i)第\\s*\\d+\\s*[集話话]|\\b(?:episode|ep)\\s*\\d+\\b")
    private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")
    /** "Episode 33" / "Ep 12" / "12" / "第33集" — TMDB's placeholder for an
     *  episode its editors have not titled (or not translated). */
    private val GENERIC = Regex("(?i)^(episode|ep|e)?[\\s._-]*\\d+$|^第\\s*\\d+\\s*[集話话]$")
    /** TMDB editors often append the show's absolute episode number to a
     *  season-local title ("A Liang (27)"), which reads as noise on the row. */
    private val TRAILING_NUM = Regex("\\s*[（(]\\s*\\d{1,4}\\s*[)）]\\s*$")

    /**
     * Titles for the episode numbers the caller asked about. [english] are real
     * English titles from TMDB; [generic] are TMDB's own placeholders, which a
     * caller may use to clean up a source label that says nothing but the
     * episode number.
     */
    class Names(val english: Map<Int, String>, val generic: Map<Int, String>) {
        fun isEmpty(): Boolean = english.isEmpty() && generic.isEmpty()
    }

    private val EMPTY = Names(emptyMap(), emptyMap())

    private class Entry(val at: Long, val names: Names)

    private val cache = ConcurrentHashMap<String, Entry>()

    /** "Episode 33" / "第33集" — a placeholder rather than a title. */
    fun isGeneric(name: String?): Boolean = GENERIC.matches(name?.trim().orEmpty())

    /** True when [name] is worth a database lookup: missing, a mechanical site
     *  label, or written in a script that reads as foreign in an English UI. A
     *  row that is already a real English title has nothing to gain, which is
     *  what keeps ordinary shows from costing a request. */
    fun needsEnglish(name: String?, showTitle: String): Boolean {
        val n = name?.trim().orEmpty()
        if (n.isEmpty()) return true
        if (CJK.containsMatchIn(n)) return true
        return looksMechanical(n, showTitle)
    }

    /**
     * True when a name looks like a site's own mechanical label rather than a
     * real title: blank, "Episode 33" / "第33集", an episode tag embedded in a
     * longer string ("Swallowed Star Episode 33 English Sub", "剑来 第二季
     * 第27集"), or the show's own name plus a number. Real titles almost never
     * repeat the show's name or carry an episode tag, which is what makes the
     * test safe enough to decide how a whole episode list is treated. The
     * show-name comparison is script-agnostic ([squash]) because
     * [TmdbMeta.normalizeTitle] drops non-Latin scripts entirely.
     */
    fun looksMechanical(name: String?, showTitle: String): Boolean {
        val n = name?.trim().orEmpty()
        if (n.isEmpty()) return true
        if (isGeneric(n)) return true
        if (EMBEDDED.containsMatchIn(n)) return true
        if (!DIGITS.containsMatchIn(n)) return false
        val show = squash(showTitle)
        return show.length >= 4 && squash(n).contains(show)
    }

    /** Lower-cased, punctuation/space-free form — script-agnostic, so it can
     *  compare two Chinese titles as well as two Latin ones. */
    private fun squash(s: String): String = s.lowercase().replace(NON_ALNUM, "")

    /** Titles for the subset of [numbers] TMDB can name.
     *
     *  [language] is the TMDB code to ask in ("fr-FR" …); null/blank means
     *  English, which is what this used to be hard-coded to. The app's chosen
     *  TMDB language is passed in so an episode row — and the player's top bar,
     *  which prints the same name — reads in the language the user picked
     *  instead of in English. */
    suspend fun lookup(
        title: String,
        year: Int?,
        numbers: Set<Int>,
        language: String? = null,
    ): Names =
        withContext(Dispatchers.IO) {
            if (title.isBlank() || numbers.isEmpty()) return@withContext EMPTY
            val lang = language?.trim().orEmpty().ifBlank { LANGUAGE }
            // The raw title, not [TmdbMeta.normalizeTitle]'s form: that drops
            // CJK entirely, so every Chinese title would share one cache entry.
            // The language is part of the key: the same show has different
            // episode names in each one.
            val key = title.trim().lowercase() + "|" + (year ?: 0) + "|" + lang
            cache[key]?.let {
                if (it.names.english.isNotEmpty() || it.names.generic.isNotEmpty() ||
                    System.currentTimeMillis() - it.at < NEGATIVE_TTL_MS
                ) {
                    return@withContext it.names
                }
            }
            // A site item that names its season is listed with that season's own
            // numbering starting at 1, so the database has to be read the same
            // way instead of being concatenated into a continuous run.
            val season = TmdbMeta.seasonHint(title)
            val found = show(title, year, numbers, season, lang) ?: EMPTY
            cache[key] = Entry(System.currentTimeMillis(), found)
            found
        }

    /**
     * TMDB's entry for [title], or null when nothing matches by name. Site
     * titles are searched through their stripped forms too
     * ([TmdbMeta.queryVariants]) — "Sword of Coming Season 2" returns nothing
     * from TMDB's index while "Sword of Coming" resolves — but a candidate is
     * only accepted on a NAME match, never on the year alone, so a franchise
     * whose site title differs from TMDB's translation ("Battle Through The
     * Heavens" is "Fights Break Sphere") is deliberately left unresolved rather
     * than having another entry's episode titles mapped onto its list.
     */
    private suspend fun show(
        title: String,
        year: Int?,
        numbers: Set<Int>,
        season: Int?,
        language: String,
    ): Names? {
        val variants = TmdbMeta.queryVariants(title)
        if (variants.isEmpty()) return null
        var hit: JSONObject? = null
        var bestScore = 0
        for (v in variants) {
            val results = TmdbResolver.apiGet("/search/tv", mapOf("query" to v, "language" to language))
                ?.optJSONArray("results") ?: continue
            for (i in 0 until results.length()) {
                val o = results.optJSONObject(i) ?: continue
                val base = maxOf(
                    TmdbMeta.titleScore(v, o.optString("name")),
                    TmdbMeta.titleScore(v, o.optString("original_name")),
                )
                if (base == 0) continue
                val y = o.optString("first_air_date").take(4).toIntOrNull()
                val yearBonus = if (year != null && y != null) {
                    if (y == year) 20 else if (Math.abs(y - year) <= 1) 5 else 0
                } else {
                    0
                }
                // Tie-break on popularity: an original title can be indexed
                // twice — 剑来 is both the show itself and a one-episode short
                // filed as "The One" — and the popular entry is the one whose
                // episode list belongs to the item the user just opened.
                val popTier = minOf(99, (o.optDouble("popularity", 0.0) / 2).toInt())
                // Name quality dominates: an exact title match with a
                // wrong-looking year still beats a loose match that happens to
                // share the year.
                val score = base * 10_000 + yearBonus * 100 + popTier
                if (score > bestScore) {
                    bestScore = score
                    hit = o
                }
            }
            if (bestScore >= 400_000) break
        }
        val chosen = hit ?: return null
        val id = chosen.optInt("id")
        if (id <= 0) return null
        val details = TmdbResolver.apiGet("/tv/$id", mapOf("language" to language))
        return names(id, details ?: chosen, numbers, season, language)
    }

    /**
     * TMDB episode names keyed by ABSOLUTE episode number. TMDB restarts at 1
     * per season while a site lists (and numbers) a donghua's run continuously,
     * so seasons are re-based onto the running total in order. Stops as soon as
     * every requested number has a name — the usual single-season case costs
     * one request.
     *
     * When [season] is set the show is read as that season alone, keeping its
     * episode numbers untouched: the site's "Season 2" item is numbered 1..n
     * and so is TMDB's season 2. A season that TMDB doesn't have (or hasn't
     * filled yet) yields nothing rather than the wrong season's titles.
     */
    private suspend fun names(
        id: Int,
        obj: JSONObject,
        want: Set<Int>,
        season: Int?,
        language: String,
    ): Names {
        val seasons = obj.optJSONArray("seasons") ?: return EMPTY
        val rows = (0 until seasons.length()).mapNotNull { i ->
            val s = seasons.optJSONObject(i) ?: return@mapNotNull null
            val n = s.optInt("season_number")
            if (n > 0 && s.optInt("episode_count") > 0) n else null
        }
        val picked = if (season != null) rows.filter { it == season } else rows.take(MAX_SEASONS)
        if (picked.isEmpty()) return EMPTY
        val english = HashMap<Int, String>()
        val generic = HashMap<Int, String>()
        var running = 0
        for (sn in picked) {
            val sd = TmdbResolver.apiGet("/tv/$id/season/$sn", mapOf("language" to language)) ?: continue
            val eps = sd.optJSONArray("episodes") ?: continue
            val list = ArrayList<Pair<Int, String>>()
            for (i in 0 until eps.length()) {
                val e = eps.optJSONObject(i) ?: continue
                val en = e.optInt("episode_number")
                if (en <= 0) continue
                val nm = e.optString("name").trim().replace(TRAILING_NUM, "").trim()
                    .takeIf { it.isNotBlank() && it != "null" } ?: continue
                list += en to nm
            }
            if (list.isEmpty()) continue
            val first = list.first().first
            val start = if (season != null) first else maxOf(first, running + 1)
            // When a NON-English language was asked for, a name written in CJK is
            // TMDB's own translation for that language (or its fallback to the
            // show's original one) — and the user asked to read the page in that
            // language, so it is used. The old rule that rejected it existed only
            // because this lookup always asked for English, where a Chinese title
            // was no improvement on the row's own label.
            val englishOnly = language.equals(LANGUAGE, ignoreCase = true)
            for ((en, nm) in list) {
                val abs = start + (en - first)
                if (abs !in 1..4000) continue
                when {
                    isGeneric(nm) -> if (!generic.containsKey(abs)) generic[abs] = nm
                    !englishOnly -> english[abs] = nm
                    CJK.containsMatchIn(nm) -> Unit
                    else -> english[abs] = nm
                }
            }
            if (season != null) continue
            running = maxOf(running, english.keys.maxOrNull() ?: 0, generic.keys.maxOrNull() ?: 0)
            if (english.size + generic.size >= MAX_EPISODES) break
            if (want.all { english.containsKey(it) || generic.containsKey(it) }) break
        }
        return Names(english, generic)
    }
}
