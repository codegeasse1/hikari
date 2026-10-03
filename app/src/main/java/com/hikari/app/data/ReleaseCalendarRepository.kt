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
        for (page in 1..3) {
            val data = TmdbResolver.apiGet(
                "/movie/upcoming",
                mapOf("region" to region.uppercase(), "page" to page.toString()),
            ) ?: break
            val results = data.optJSONArray("results") ?: break
            if (results.length() == 0) break
            for (i in 0 until results.length()) {
                parseMovie(results.optJSONObject(i) ?: continue)?.let { out += it }
            }
            if (page >= data.optInt("total_pages", 1)) break
        }
        return group(out.distinctBy { it.tmdbId }.take(90))
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
        return group(out.distinctBy { it.tmdbId }.take(90))
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
        // Airing lists carry first_air_date, not the episode's own date: an
        // "on the air" show airs this week, a "today" show airs today.
        val millis = if (kind == Kind.EPISODE) {
            startOfToday()
        } else {
            val date = o.optString("first_air_date").trim().takeIf { it.length >= 10 }
            (date?.let { dayMillis(it) } ?: startOfToday()).coerceAtLeast(startOfToday() - 30L * 86400000L)
        }
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

    private fun group(entries: List<Entry>): List<DayGroup> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
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
