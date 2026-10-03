package com.hikari.app.nuvio

import com.hikari.app.data.TmdbMeta
import com.hikari.app.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Bangumi (bgm.tv) — the Chinese animation database — used as a SECOND episode
 * source beside TMDB.
 *
 * TMDB is Hikari's metadata backbone (ids, artwork, air dates), but its
 * coverage of long-running Chinese donghua stalls on some titles: 斗破苍穹
 * (Battle Through the Heavens) is still frozen at its 2018 first season (45
 * episodes) on TMDB while the show is well past 150. Bangumi tracks those week
 * by week with the real episode titles, and — crucially — its per-season
 * `sort` numbers concatenate to the SAME absolute numbering the providers
 * expect, so Bangumi episodes merge into a TMDB list by number alone:
 *
 *   仙逆        sort  1..24    (= TMDB episodes   1..24)
 *   仙逆 年番   sort 25..76    (= TMDB episodes  25..76)
 *   仙逆 年番2  sort 77..128
 *   仙逆 年番3  sort 129..180
 *
 * Seasons whose numbering restarts at 1 (斗破苍穹 第二季 …) are re-based onto the
 * running total, and movies/specials are skipped so they can never shift the
 * real episode numbering. No API key is needed; Bangumi only asks for a
 * User-Agent that identifies the caller.
 */
object BangumiMeta {

    /** One episode on Bangumi's absolute numbering. */
    data class Ep(val number: Int, val name: String?, val airDate: String?)

    private const val BASE = "https://api.bgm.tv"
    private const val UA = "Hikari/0.3.81 (Android; +https://perchance.org/hikari)"

    /** Subjects that must never join the main numbering: movies and specials
     *  carry their own 1..n numbering on Bangumi and would collide with (and
     *  shift) the real episodes. Matched against the subject name. */
    private val NOT_MAIN = Regex(
        "(?i)剧场版|劇場版|特别篇|特別篇|特典|番外|Q版|小剧场|小劇場|合集|总集|预告|花絮|声优|声優|舞台|广播剧|写真"
    )

    private val CJK = Regex("[\\u3040-\\u30FF\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uF900-\\uFAFF]")

    /** "Episode 128" / "Ep 12" / "12" / "第12集" — titles TMDB only fills in
     *  for the part of a show its editors got around to. */
    private val GENERIC = Regex("(?i)^(episode|ep|e)?[\\s._-]*\\d+$|^第\\s*\\d+\\s*[集話话]$")

    /** Search → episodes survives across detail-page visits for the session. */
    private val cache = ConcurrentHashMap<String, List<Ep>>()

    fun isGenericName(name: String?): Boolean {
        val n = name?.trim().orEmpty()
        return n.isEmpty() || GENERIC.matches(n)
    }

    /**
     * Every episode Bangumi knows for [title], on absolute numbering, or null
     * when Bangumi has no usable match. [originalTitle] (TMDB's `original_name`)
     * is searched first when it is Chinese/Japanese — Bangumi's English-title
     * index is loose, while the native title is exact.
     *
     * [seasonHint] handles a site item that IS one season of a franchise
     * ("Sword of Coming Season 2"): Bangumi keeps numbering a franchise's
     * seasons continuously (its 第二季 subject starts at sort 27), while the
     * site lists that season from 1, so subjects are re-based to a season-local
     * 1..n and only the ones whose name carries that season's marker are used.
     * Without a hint the historical absolute concatenation is kept.
     */
    suspend fun episodes(
        title: String,
        originalTitle: String?,
        year: Int?,
        seasonHint: Int? = null,
    ): List<Ep>? {
        val native = originalTitle?.trim()?.takeIf { it.isNotBlank() && it != "null" && CJK.containsMatchIn(it) }
        val base = (native ?: title).trim()
        if (base.isBlank()) return null
        val key = if (seasonHint == null) base else "$base|s$seasonHint"
        cache[key]?.let { return it }
        val hit = fetch(base, asciiSearch = native == null, year = year, seasonHint = seasonHint) ?: return null
        if (hit.isNotEmpty()) cache[key] = hit
        return hit
    }

    private suspend fun fetch(base: String, asciiSearch: Boolean, year: Int?, seasonHint: Int?): List<Ep>? =
        withContext(Dispatchers.IO) {
            val markers = seasonHint?.let { TmdbMeta.seasonMarkers(it) }
            val body = JSONObject()
                .put("keyword", base)
                .put("filter", JSONObject().put("type", JSONArray().put(2)))
                .toString()
            val text = Http.postString(
                "$BASE/v0/search/subjects",
                body,
                mapOf("User-Agent" to UA, "Accept" to "application/json"),
            ) ?: return@withContext null
            val arr = runCatching { JSONObject(text).optJSONArray("data") }.getOrNull()
                ?: return@withContext null

            val candidates = ArrayList<JSONObject>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name").trim()
                val nameCn = o.optString("name_cn").trim()
                val eps = o.optInt("eps")
                if (name.isBlank()) continue
                if (NOT_MAIN.containsMatchIn(name) || NOT_MAIN.containsMatchIn(nameCn)) continue
                if (markers != null &&
                    markers.none { name.contains(it, true) || nameCn.contains(it, true) }
                ) continue
                if (asciiSearch) {
                    // Bangumi indexes English titles loosely (a search for
                    // "Battle Through the Heavens" also returns "Battle for
                    // Terra"), so the name cannot be trusted here — use the
                    // first-air year and a sane episode count instead.
                    if (eps < 4) continue
                    val y = o.optString("date").take(4).toIntOrNull()
                    if (year != null && y != null && Math.abs(y - year) > 1) continue
                } else {
                    val hay = listOf(name, nameCn).filter { it.isNotBlank() }
                    if (hay.none { it.contains(base) || base.contains(it) }) continue
                }
                candidates.add(o)
            }
            if (candidates.isEmpty()) return@withContext null
            candidates.sortBy { it.optString("date").ifBlank { "9999-99-99" } }

            val out = ArrayList<Ep>()
            var running = 0
            var used = 0
            for (o in candidates) {
                if (used >= 6) break
                val subjectId = o.optInt("id")
                if (subjectId <= 0) continue
                val eps = subjectEpisodes(subjectId) ?: continue
                if (eps.isEmpty()) continue
                used++
                val firstSort = eps.first().sort
                // Season-local numbering when the caller named a season: the
                // site's "Season 2" list starts at 1, and so must the map.
                val start = if (seasonHint != null) running + 1 else maxOf(firstSort, running + 1)
                for (e in eps) {
                    val n = start + (e.sort - firstSort)
                    if (n > 0 && n <= 4000) out += Ep(n, e.name, e.air)
                }
                running = out.maxOfOrNull { it.number } ?: running
            }
            if (out.isEmpty()) return@withContext null
            out.distinctBy { it.number }.sortedBy { it.number }
        }

    private class RawEp(val sort: Int, val name: String?, val air: String?)

    /** A subject's episode list, paged (Bangumi caps a page at 100). */
    private fun subjectEpisodes(subjectId: Int): List<RawEp>? {
        val rows = ArrayList<JSONObject>()
        var offset = 0
        var total = -1
        var pages = 0
        while (pages < 4) {
            val url = "$BASE/v0/episodes?subject_id=$subjectId&limit=100&offset=$offset"
            val text = Http.getString(url, mapOf("User-Agent" to UA, "Accept" to "application/json")) ?: break
            val obj = runCatching { JSONObject(text) }.getOrNull() ?: break
            if (total < 0) total = obj.optInt("total", 0)
            val arr = obj.optJSONArray("data") ?: break
            if (arr.length() == 0) break
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { rows.add(it) }
            offset += arr.length()
            pages++
            if (total in 1..offset) break
        }
        if (rows.isEmpty()) return null
        return rows.mapNotNull { o ->
            val sort = o.optInt("sort").takeIf { it > 0 } ?: o.optInt("ep")
            if (sort <= 0) return@mapNotNull null
            val name = o.optString("name_cn").trim()
                .ifBlank { o.optString("name").trim() }
                .takeIf { it.isNotBlank() && it != "null" }
            val air = o.optString("airdate").trim().takeIf { it.length == 10 }
            RawEp(sort, name, air)
        }.sortedBy { it.sort }
    }
}
