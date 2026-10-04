package com.hikari.app.data

import com.hikari.app.nuvio.TmdbResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object ReleaseCalendarRepository {

    enum class Kind(val key: String) { MOVIE("movie"), TV("tv"), EPISODE("episode") }

    data class Entry(
        val kind: Kind,
        val tmdbId: Int,
        val title: String,
        val year: String?,
        val poster: String?,
        val genres: List<String>,
        val blurb: String?,
        val dateMillis: Long,
        val dateLabel: String,
    )

    data class DayGroup(val label: String, val sortKey: Long, val entries: List<Entry>)

    val COUNTRIES = listOf(
        "US" to "United States",
        "IN" to "India",
        "GB" to "United Kingdom",
        "JP" to "Japan",
        "KR" to "South Korea",
        "FR" to "France",
        "DE" to "Germany",
        "ES" to "Spain",
        "IT" to "Italy",
        "BR" to "Brazil",
        "MX" to "Mexico",
        "CA" to "Canada",
        "AU" to "Australia",
        "AE" to "UAE",
        "SA" to "Saudi Arabia",
        "TR" to "Turkiye",
        "RU" to "Russia",
        "CN" to "China",
        "TW" to "Taiwan",
        "TH" to "Thailand",
    )

    fun countryName(code: String): String =
        COUNTRIES.firstOrNull { it.first == code }?.second ?: code

    private data class Cache(val at: Long, val groups: List<DayGroup>)
    private val caches = java.util.concurrent.ConcurrentHashMap<String, Cache>()

    suspend fun load(kind: Kind, region: String): List<DayGroup> = withContext(Dispatchers.IO) {
        val key = kind.key + "|" + region.uppercase()
        val now = System.currentTimeMillis()
        caches[key]?.takeIf { now - it.at < 3 * 60 * 60 * 1000L }?.let { return@withContext it.groups }
        val groups = when (kind) {
            Kind.MOVIE -> loadMovies(region)
            Kind.TV -> loadTv("/tv/on_the_air", region, Kind.TV)
            Kind.EPISODE -> loadTv("/tv/airing_today", region, Kind.EPISODE)
        }
        if (groups.isNotEmpty()) caches[key] = Cache(now, groups)
        groups.ifEmpty { caches[key]?.groups.orEmpty() }
    }

    private suspend fun loadMovies(region: String): List<DayGroup> {
        val out = ArrayList<Entry>()
        // While the adult-content switch is off /movie/upcoming is re-asked as
        // a dateless discover query (see NsfwGate), which answers with every
        // film ever made — the 1959/1971 rows in the report. Pin that query to
        // the upcoming window, and never trust a movie older than yesterday no
        // matter which endpoint answered.
        val query = LinkedHashMap<String, String>()
        query["region"] = region.uppercase()
        if (NsfwGate.rewritesToMovies("/movie/upcoming")) {
            val today = LocalDate.now(ZoneId.systemDefault())
            query["primary_release_date.gte"] = today.toString()
            query["primary_release_date.lte"] = today.plusDays(90).toString()
        }
        for (page in 1..3) {
            query["page"] = page.toString()
            val data = TmdbResolver.apiGet("/movie/upcoming", query) ?: break
            val results = data.optJSONArray("results") ?: break
            if (results.length() == 0) break
            for (i in 0 until results.length()) {
                parseMovie(results.optJSONObject(i) ?: continue)?.let { out += it }
            }
            if (page >= data.optInt("total_pages", 1)) break
        }
        val floor = startOfToday() - 86400000L
        return group(out.distinctBy { it.tmdbId }.filter { it.dateMillis >= floor }.take(90))
    }

    private suspend fun loadTv(path: String, region: String, kind: Kind): List<DayGroup> {
        val out = ArrayList<Entry>()
        for (page in 1..3) {
            val data = TmdbResolver.apiGet(
                path,
                mapOf("page" to page.toString()),
            ) ?: break
            val results = data.optJSONArray("results") ?: break
            if (results.length() == 0) break
            for (i in 0 until results.length()) {
                parseTv(results.optJSONObject(i) ?: continue, kind, region)?.let { out += it }
            }
            if (page >= data.optInt("total_pages", 1)) break
        }
        // These lists carry no episode date — on_the_air answers with shows
        // airing now, airing_today with shows airing today — so grouping them
        // by first_air_date (a 2011/2019 year clamped to this week) is what
        // filed old shows under "Fri, Sep 04, 2026". One honest bucket each.
        val label = if (kind == Kind.EPISODE) "Airing today" else "On the air now"
        return group(out.distinctBy { it.tmdbId }.take(90), singleLabel = label)
    }

    private fun parseMovie(o: JSONObject): Entry? {
        val id = o.optInt("id", 0)
        if (id <= 0) return null
        val title = o.optString("title").trim().ifBlank { return null }
        val date = o.optString("release_date").trim().takeIf { it.length >= 10 } ?: return null
        val millis = dayMillis(date) ?: return null
        val genres = o.optJSONArray("genre_ids")?.let { arr ->
            List(arr.length()) { TmdbGenres.nameOf("movie", arr.optInt(it)) }.filterNotNull().take(3)
        }.orEmpty()
        return Entry(
            Kind.MOVIE, id, title,
            date.take(4).takeIf { it[0].isDigit() },
            posterOf(o.optString("poster_path")),
            genres,
            o.optString("overview").trim().takeIf { it.isNotBlank() },
            millis, dayLabel(millis),
        )
    }

    private fun parseTv(o: JSONObject, kind: Kind, region: String): Entry? {
        val id = o.optInt("id", 0)
        if (id <= 0) return null
        val title = o.optString("name").trim().ifBlank { return null }
        // Airing lists carry no episode date at all (first_air_date is when
        // the SHOW started, not when an episode airs), so the entry lives in
        // the list's own bucket (see loadTv) instead of a fabricated day.
        val millis = startOfToday()
        val genres = o.optJSONArray("genre_ids")?.let { arr ->
            List(arr.length()) { TmdbGenres.nameOf("tv", arr.optInt(it)) }.filterNotNull().take(3)
        }.orEmpty()
        val year = o.optString("first_air_date").trim().take(4).takeIf { it.isNotEmpty() && it[0].isDigit() }
        val blurb = if (kind == Kind.EPISODE) "Airing today · " + countryName(region)
        else o.optString("overview").trim().takeIf { it.isNotBlank() }
        return Entry(
            kind, id, title, year,
            posterOf(o.optString("poster_path")),
            genres, blurb,
            millis, dayLabel(millis),
        )
    }

    private fun group(entries: List<Entry>, singleLabel: String? = null): List<DayGroup> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        if (singleLabel != null && entries.isNotEmpty()) {
            return listOf(DayGroup(singleLabel, startOfToday(), entries.sortedBy { it.title }))
        }
        return entries.groupBy { it.dateMillis }.toList()
            .sortedBy { it.first }
            .map { (millis, list) ->
                val label = dayLabel(millis, today)
                DayGroup(label, millis, list.sortedBy { it.title })
            }
    }

    private fun posterOf(path: String): String? {
        val p = path.trim().takeIf { it.isNotBlank() && it != "null" } ?: return null
        return "https://image.tmdb.org/t/p/w500" + (if (p.startsWith("/")) p else "/$p")
    }

    private fun dayMillis(iso: String): Long? = runCatching {
        LocalDate.parse(iso.take(10)).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }.getOrNull()

    private fun startOfToday(): Long =
        LocalDate.now(ZoneId.systemDefault()).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun dayLabel(millis: Long, today: LocalDate = LocalDate.now(ZoneId.systemDefault())): String {
        val date = java.time.Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
        val fmt = DateTimeFormatter.ofPattern("MMM dd, yyyy")
        return when (date) {
            today -> "Today, " + date.format(fmt)
            today.plusDays(1) -> "Tomorrow, " + date.format(fmt)
            else -> date.format(DateTimeFormatter.ofPattern("EEE, MMM dd, yyyy"))
        }
    }
}
