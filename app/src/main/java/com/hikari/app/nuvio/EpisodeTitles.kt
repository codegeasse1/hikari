package com.hikari.app.nuvio

import com.hikari.app.data.TmdbMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Real episode titles by absolute episode number, from TMDB (preferred) and
 * Bangumi (filler).
 *
 * Site-scraping extensions label their episodes mechanically — "Swallowed Star
 * Episode 33 English Sub", "Renegade Immortal (Xian Ni) Episode 128" — because
 * that is literally what the page they scraped shows. The episode LIST has to
 * stay the site's (it is the ground truth for how many episodes exist and for
 * what the provider can actually play), but the NAMES should be the real ones,
 * so this resolves the title once against TMDB — whose `original_name` is also
 * the bridge to the native title Bangumi indexes — and Bangumi, and hands back
 * a number → name map for the numbers the caller actually has.
 *
 * TMDB wins wherever its name is a real title; Bangumi fills the rest (and
 * supplies the titles TMDB never got around to, which is common for donghua).
 * When neither database has a real title for an episode, the database's own
 * generic "Episode 129" is still used instead of the site's longer mechanical
 * label — and only when BOTH databases fail outright does the caller keep the
 * site's names. Everything is cached per title, including empty results (for a
 * short while, so a transient failure isn't re-queried on every detail-page
 * open but also doesn't stick for the whole session).
 */
object EpisodeTitles {

    private const val MAX_SEASONS = 24
    private const val MAX_EPISODES = 600
    private const val NEGATIVE_TTL_MS = 10 * 60 * 1000L

    private val DIGITS = Regex("\\d")
    private val CJK = Regex("[\\u3040-\\u30FF\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uF900-\\uFAFF]")
    /** An episode tag anywhere in a name ("Episode 27", "第27集"). */
    private val EMBEDDED = Regex("(?i)第\\s*\\d+\\s*[集話话]|\\b(?:episode|ep)\\s*\\d+\\b")
    private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")
    private val ASIAN_LANGS = setOf("zh", "ja", "ko")
    private val ASIAN_COUNTRIES = setOf("CN", "TW", "HK", "JP", "KR")

    /** TMDB editors often append the show's absolute episode number to a
     *  season-local title ("A Liang (27)"), which reads as noise on the row. */
    private val TRAILING_NUM = Regex("\\s*[（(]\\s*\\d{1,4}\\s*[)）]\\s*$")

    private class Entry(val at: Long, val names: Map<Int, String>)

    private val cache = ConcurrentHashMap<String, Entry>()

    /**
     * True when a name looks like the site's own mechanical label rather than a
     * real title: blank, "Episode 33" / "第33集", an episode tag embedded in a
     * longer string ("Swallowed Star Episode 33 English Sub", "剑来 第二季
     * 第27集"), or the show's own name plus a number. Real titles almost never
     * repeat the show's name or carry an episode tag, which is what makes the
     * test safe enough to decide whether a whole episode list is worth looking
     * up. The show-name comparison is CJK-aware ([squash]) because
     * [TmdbMeta.normalizeTitle] drops non-Latin scripts entirely, which used to
     * make every Chinese title look un-mechanical (and so never enriched).
     */
    fun looksMechanical(name: String?, showTitle: String): Boolean {
        val n = name?.trim().orEmpty()
        if (n.isEmpty()) return true
        if (BangumiMeta.isGenericName(n)) return true
        if (EMBEDDED.containsMatchIn(n)) return true
        if (!DIGITS.containsMatchIn(n)) return false
        val show = squash(showTitle)
        return show.length >= 4 && squash(n).contains(show)
    }

    /** Lower-cased, punctuation/space-free form — script-agnostic, so it can
     *  compare two Chinese titles as well as two Latin ones. */
    private fun squash(s: String): String = s.lowercase().replace(NON_ALNUM, "")

    /** Real names for the subset of [numbers] the databases know about. */
    suspend fun lookup(title: String, year: Int?, numbers: Set<Int>): Map<Int, String> =
        withContext(Dispatchers.IO) {
            if (title.isBlank() || numbers.isEmpty()) return@withContext emptyMap()
            // The raw title, not [TmdbMeta.normalizeTitle]'s form: that drops
            // CJK entirely, so every Chinese title would share one cache entry.
            val key = title.trim().lowercase() + "|" + (year ?: 0)
            cache[key]?.let {
                if (it.names.isNotEmpty() || System.currentTimeMillis() - it.at < NEGATIVE_TTL_MS) {
                    return@withContext it.names
                }
            }
            // A site item that names its season is listed with that season's own
            // numbering starting at 1, so the databases have to be read the same
            // way instead of being concatenated into a continuous run.
            val season = TmdbMeta.seasonHint(title)

            val out = HashMap<Int, String>()
            // A database's generic "Episode 33" / "第33集" is still a name the
            // database has for that episode — cleaner than the site's
            // "Swallowed Star Episode 33 English Sub" — so it is kept as a
            // fallback, behind any real title from either source. Only when
            // TMDB and Bangumi BOTH fail does the caller get an empty map and
            // keep the site's own labels.
            val fallback = HashMap<Int, String>()
            val hit = show(title, year, numbers, season)
            if (hit != null) {
                for ((n, name) in hit.names) {
                    if (n !in numbers) continue
                    if (BangumiMeta.isGenericName(name)) {
                        if (!fallback.containsKey(n)) fallback[n] = name
                    } else out[n] = name
                }
            }
            // Bangumi is anime/donghua only, and its English-keyword index is
            // loose, so it is only consulted for titles TMDB says are Asian (or
            // that are already written natively) — otherwise a coincidental
            // match would hang Chinese names on an unrelated English show.
            val native = CJK.containsMatchIn(title)
            if (hit == null || native || hit.asian) {
                val bgm = runCatching { BangumiMeta.episodes(title, hit?.original, year, season) }.getOrNull()
                if (bgm != null) {
                    for (e in bgm) {
                        val nm = e.name ?: continue
                        if (e.number !in numbers || out.containsKey(e.number)) continue
                        if (BangumiMeta.isGenericName(nm)) {
                            if (!fallback.containsKey(e.number)) fallback[e.number] = nm
                        } else out[e.number] = nm
                    }
                }
            }
            for ((n, nm) in fallback) if (!out.containsKey(n)) out[n] = nm
            cache[key] = Entry(System.currentTimeMillis(), out)
            out
        }

    private class Show(val original: String?, val asian: Boolean, val names: Map<Int, String>)

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
    private suspend fun show(title: String, year: Int?, numbers: Set<Int>, season: Int?): Show? {
        val variants = TmdbMeta.queryVariants(title)
        if (variants.isEmpty()) return null
        var hit: JSONObject? = null
        var bestScore = 0
        for (v in variants) {
            val results = TmdbResolver.apiGet("/search/tv", mapOf("query" to v))
                ?.optJSONArray("results") ?: continue
            for (i in 0 until results.length()) {
                val o = results.optJSONObject(i) ?: continue
                val base = maxOf(
                    TmdbMeta.titleScore(v, o.optString("name")),
                    TmdbMeta.titleScore(v, o.optString("original_name")),
                )
                if (base == 0) continue
                val y = o.optString("first_air_date").take(4).toIntOrNull()
                val bonus = if (year != null && y != null) {
                    if (y == year) 20 else if (Math.abs(y - year) <= 1) 5 else -15
                } else {
                    0
                }
                // Name quality dominates: an exact title match with a
                // wrong-looking year still beats a loose match that happens to
                // share the year.
                val score = base * 100 + bonus
                if (score > bestScore) {
                    bestScore = score
                    hit = o
                }
            }
            if (bestScore >= 4000) break
        }
        val chosen = hit ?: return null
        val id = chosen.optInt("id")
        if (id <= 0) return null
        val details = TmdbResolver.apiGet("/tv/$id", emptyMap())
        val original = details?.optString("original_name")?.trim()
            ?.takeIf { it.isNotBlank() && it != "null" }
        val lang = (details ?: chosen).optString("original_language").trim().lowercase()
        val countries = (details ?: chosen).optJSONArray("origin_country")
        val inAsia = countries != null && (0 until countries.length()).any {
            countries.optString(it).uppercase() in ASIAN_COUNTRIES
        }
        val asian = lang in ASIAN_LANGS || inAsia
        return Show(original, asian, names(id, details ?: chosen, numbers, season))
    }

    /**
     * TMDB episode names keyed by ABSOLUTE episode number. TMDB restarts at 1
     * per season while the site lists (and Bangumi) number a donghua's run
     * continuously, so seasons are re-based onto the running total in order,
     * exactly like [BangumiMeta] does. Stops as soon as every requested number
     * has a name — the usual single-season case costs one request.
     *
     * When [season] is set the show is read as that season alone, keeping its
     * episode numbers untouched: the site's "Season 2" item is numbered 1..n
     * and so is TMDB's season 2. A season that TMDB doesn't have (or hasn't
     * filled yet) yields nothing rather than the wrong season's titles.
     */
    private suspend fun names(id: Int, obj: JSONObject, want: Set<Int>, season: Int?): Map<Int, String> {
        val seasons = obj.optJSONArray("seasons") ?: return emptyMap()
        val rows = (0 until seasons.length()).mapNotNull { i ->
            val s = seasons.optJSONObject(i) ?: return@mapNotNull null
            val n = s.optInt("season_number")
            if (n > 0 && s.optInt("episode_count") > 0) n else null
        }
        val picked = if (season != null) rows.filter { it == season } else rows.take(MAX_SEASONS)
        if (picked.isEmpty()) return emptyMap()
        val out = HashMap<Int, String>()
        var running = 0
        for (sn in picked) {
            val sd = TmdbResolver.apiGet("/tv/$id/season/$sn", emptyMap()) ?: continue
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
            for ((en, nm) in list) {
                val abs = start + (en - first)
                if (abs in 1..4000) out[abs] = nm
            }
            if (season != null) continue
            running = out.keys.maxOrNull() ?: running
            if (out.size >= MAX_EPISODES) break
            if (want.all { out.containsKey(it) }) break
        }
        return out
    }
}
