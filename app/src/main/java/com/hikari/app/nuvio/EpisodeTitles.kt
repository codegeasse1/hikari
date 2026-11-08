package com.hikari.app.nuvio

import com.hikari.app.data.TmdbMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

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

    suspend fun lookup(
        title: String,
        year: Int?,
        numbers: Set<Int>,
        language: String? = null,
    ): Names =
        withContext(Dispatchers.IO) {
            if (!runCatching { com.hikari.app.HikariApp.instance.store.tmdbEnabled() && com.hikari.app.HikariApp.instance.store.tmdbModule("episodes") }.getOrDefault(true)) return@withContext EMPTY
            if (title.isBlank() || numbers.isEmpty()) return@withContext EMPTY
            val lang = language?.trim().orEmpty().ifBlank { LANGUAGE }
            val key = title.trim().lowercase() + "|" + (year ?: 0) + "|" + lang
            cache[key]?.let {
                if (it.names.english.isNotEmpty() || it.names.generic.isNotEmpty() ||
                    System.currentTimeMillis() - it.at < NEGATIVE_TTL_MS
                ) {
                    return@withContext it.names
                }
            }
            val season = TmdbMeta.seasonHint(title)
            val found = show(title, year, numbers, season, lang) ?: EMPTY
            cache[key] = Entry(System.currentTimeMillis(), found)
            found
        }

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
                val popTier = minOf(99, (o.optDouble("popularity", 0.0) / 2).toInt())
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
    class EpDetail(
        val season: Int,
        val number: Int,
        val overview: String?,
        val released: String?,
        val rating: Double?,
        val runtime: Int?,
        val image: String?,
    )

    private const val DETAILS_TTL_MS = 30 * 60 * 1000L
    private const val IMG_BASE = "https://image.tmdb.org/t/p/w500"

    private class DetailsEntry(val at: Long, val details: Map<Int, EpDetail>)

    private val detailsCache = ConcurrentHashMap<String, DetailsEntry>()

    suspend fun details(
        title: String,
        year: Int?,
        numbers: Set<Int>,
        language: String? = null,
    ): Map<Int, EpDetail> =
        withContext(Dispatchers.IO) {
            if (!runCatching { com.hikari.app.HikariApp.instance.store.tmdbEnabled() && com.hikari.app.HikariApp.instance.store.tmdbModule("episodes") }.getOrDefault(true)) return@withContext emptyMap()
            if (title.isBlank() || numbers.isEmpty()) return@withContext emptyMap()
            val lang = language?.trim().orEmpty().ifBlank { LANGUAGE }
            val key = "d|" + title.trim().lowercase() + "|" + (year ?: 0) + "|" + lang
            detailsCache[key]?.let {
                if (it.details.isNotEmpty() ||
                    System.currentTimeMillis() - it.at < DETAILS_TTL_MS
                ) {
                    return@withContext it.details
                }
            }
            val season = TmdbMeta.seasonHint(title)
            val found = showDetails(title, year, season, lang, numbers) ?: emptyMap()
            detailsCache[key] = DetailsEntry(System.currentTimeMillis(), found)
            found
        }

    suspend fun lookupForId(
        tmdbId: Int,
        numbers: Set<Int>,
        season: Int? = null,
        language: String? = null,
    ): Names = withContext(Dispatchers.IO) {
        if (tmdbId <= 0 || numbers.isEmpty()) return@withContext EMPTY
        val lang = language?.trim().orEmpty().ifBlank { LANGUAGE }
        val obj = TmdbResolver.apiGet("/tv/$tmdbId", mapOf("language" to lang)) ?: return@withContext EMPTY
        names(tmdbId, obj, numbers, season, lang)
    }

    suspend fun detailsForId(
        tmdbId: Int,
        numbers: Set<Int>,
        season: Int? = null,
        language: String? = null,
    ): Map<Int, EpDetail> = withContext(Dispatchers.IO) {
        if (tmdbId <= 0 || numbers.isEmpty()) return@withContext emptyMap()
        val lang = language?.trim().orEmpty().ifBlank { LANGUAGE }
        readSeasonDetails(tmdbId, season, lang, numbers)
    }

    private suspend fun readSeasonDetails(
        tmdbId: Int,
        season: Int?,
        language: String,
        want: Set<Int>,
    ): Map<Int, EpDetail> {
        val obj = TmdbResolver.apiGet("/tv/$tmdbId", mapOf("language" to language)) ?: return emptyMap()
        val seasons = obj.optJSONArray("seasons") ?: return emptyMap()
        val rows = (0 until seasons.length()).mapNotNull { i ->
            val s = seasons.optJSONObject(i) ?: return@mapNotNull null
            val n = s.optInt("season_number")
            if (n > 0 && s.optInt("episode_count") > 0) n else null
        }
        val picked = if (season != null) rows.filter { it == season } else rows.take(MAX_SEASONS)
        if (picked.isEmpty()) return emptyMap()
        val out = HashMap<Int, EpDetail>()
        var running = 0
        for (sn in picked) {
            val sd = TmdbResolver.apiGet("/tv/$tmdbId/season/$sn", mapOf("language" to language)) ?: continue
            val eps = sd.optJSONArray("episodes") ?: continue
            val list = ArrayList<EpDetail>()
            for (i in 0 until eps.length()) {
                val e = eps.optJSONObject(i) ?: continue
                val en = e.optInt("episode_number")
                if (en <= 0) continue
                val still = e.optString("still_path").trim()
                    .takeIf { it.isNotBlank() && it != "null" }?.let { IMG_BASE + it }
                list += EpDetail(
                    season = sn,
                    number = en,
                    overview = e.optString("overview").trim().takeIf { it.isNotBlank() && it != "null" },
                    released = e.optString("air_date").trim().take(10).takeIf { it.isNotBlank() },
                    rating = e.optDouble("vote_average", 0.0).takeIf { it > 0.0 },
                    runtime = e.optInt("runtime", -1).takeIf { it > 0 },
                    image = still,
                )
            }
            if (list.isEmpty()) continue
            val first = list.first().number
            val start = if (season != null) first else maxOf(first, running + 1)
            for (d in list) {
                val abs = start + (d.number - first)
                if (abs !in 1..4000) continue
                if (!out.containsKey(abs)) out[abs] = d
            }
            if (season != null) continue
            running = maxOf(running, out.keys.maxOrNull() ?: 0)
            if (out.size >= MAX_EPISODES) break
            if (want.isNotEmpty() && want.all { out.containsKey(it) }) break
        }
        return out
    }

    private suspend fun showDetails(
        title: String,
        year: Int?,
        season: Int?,
        language: String,
        want: Set<Int>,
    ): Map<Int, EpDetail>? {
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
                val popTier = minOf(99, (o.optDouble("popularity", 0.0) / 2).toInt())
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
        return readSeasonDetails(id, season, language, want)
    }
}
