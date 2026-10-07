package com.hikari.app.data
import com.hikari.app.HikariApp
import com.hikari.app.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

object AnimeMetadataRepository {
    data class Metadata(val title: String? = null, val rating: Double? = null, val nextEpisodeDate: String? = null, val source: String, val year: Int? = null, val overview: String? = null, val genres: List<String> = emptyList(), val posterUrl: String? = null, val backdropUrl: String? = null)
    data class SeasonLayout(val season: Int, val episodes: Int)
    private val seasonCache = ConcurrentHashMap<String, List<SeasonLayout>>()

        suspend fun seasonLayout(title: String): List<SeasonLayout> {
        return emptyList()
    }

        suspend fun enrich(app: HikariApp, item: MediaItem, type: ProviderType? = null): Metadata? {
        return null
    }

    /** Title match score (0-100) shared by every AniList first-hit check: a
     *  returned record is only trusted when one of its known spellings scores
     *  55+, so a near-miss search can never repaint the wrong show's details
     *  onto the page. */
    fun titleScore(a0: String, b0: String): Int {
        fun normalize(value: String): String = value.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .replace(Regex("\\s+"), " ").trim()
        val a = normalize(a0); val b = normalize(b0)
        if (a.isBlank() || b.isBlank()) return 0
        if (a == b) return 100
        if (a.contains(b) || b.contains(a)) return 90
        val aa = a.split(" ").filter { it.length > 2 }.toSet()
        val bb = b.split(" ").filter { it.length > 2 }.toSet()
        if (aa.isEmpty() || bb.isEmpty()) {
            return if (a.length <= 2 || b.length <= 2) 0 else 10
        }
        return aa.intersect(bb).size * 100 / maxOf(aa.size, bb.size)
    }

    /** Best score of [wanted] (and its sequel-stripped root) against any known
     *  spelling — the one gate every AniList lookup passes before its record
     *  is trusted. */
    fun matchAliases(wanted: String, aliases: Set<String>): Int {
        if (aliases.isEmpty()) return 0
        val roots = setOf(wanted.trim(), stripSequelTitle(wanted)).filter { it.isNotBlank() }
        var best = 0
        for (r in roots) for (a in aliases) best = maxOf(best, titleScore(r, a))
        return best
    }

    fun stripSequelTitle(name: String): String = name.trim()
        .replace(Regex("(?i)\\s*[:\\-–—]?\\s*\\bseason\\s*\\d+\\s*$"), "")
        .replace(Regex("(?i)\\s*\\b\\d+(?:st|nd|rd|th)\\s+season\\s*$"), "")
        .replace(Regex("(?i)\\s*\\b(?:part|cour)\\s*\\d+\\s*$"), "")
        .replace(Regex("(?i)\\s*\\bs\\d{1,2}\\s*$"), "")
        .trim()

    /** "auto" is the pre-0.10.95 stored value: AniList + Simkl, AniList first. */
    fun normalizeMode(raw: String): String = when (raw.trim().lowercase()) {
        "simkl", "anilist", "anilist_simkl", "full" -> raw.trim().lowercase()
        else -> "anilist_simkl"
    }

    fun includesAnilist(mode: String): Boolean = false

    /** True for anything that should read anime metadata: tracker anime rows,
     *  rows from anime-native engines (Anymex, Aniyomi) whatever their
     *  `rawType` says, and extension rows typed as anime. Extension anime used
     *  to fall through every check, so AniList never ran for exactly the rows
     *  that needed it most. */
    fun isAnimeItem(item: MediaItem, type: ProviderType? = null): Boolean =
        item.rawType.contains("anime", true) ||
            type == ProviderType.ANYMEX || type == ProviderType.ANIYOMI ||
            item.providerId.lowercase() in setOf("anilist", "mal", "kitsu", "shikimori", "simkl")

    data class AnimeEp(val number: Int, val title: String?, val image: String?)

    /**
     * One anime's canonical AniList record: every title spelling AniList knows
     * (romaji, English, native + synonyms), the description, the score and the
     * per-episode titles/thumbnails AniList carries. A single GraphQL read,
     * cached per title — the tracker sync matches against [aliases] so a
     * slightly different spelling ("a little bit different") still resolves
     * to the exact entry instead of "no confident match".
     */
    data class AnimeFull(
        val title: String?,
        val description: String?,
        val rating: Double?,
        val nextEpisodeDate: String?,
        val episodes: List<AnimeEp>,
        val aliases: Set<String>,
        val anilistId: Int,
        val year: Int? = null,
        val genres: List<String> = emptyList(),
        val posterUrl: String? = null,
        val backdropUrl: String? = null,
    )

    private data class FullEntry(val at: Long, val full: AnimeFull?)

    private val fullCache = ConcurrentHashMap<String, FullEntry>()
    private const val FULL_TTL_MS = 24 * 60 * 60 * 1000L

        suspend fun animeFull(title: String): AnimeFull? {
        return null
    }

    /** Every spelling AniList knows for [title]: exact-match fuel for tracker sync. */
    suspend fun aliasesFor(title: String): Set<String> =
        animeFull(title)?.aliases.orEmpty()

    private fun fetchAnimeFull(wanted: String): AnimeFull? {
        val query = "query{Media(search:" + JSONObject.quote(wanted) +
            ",type:ANIME){id title{userPreferred english romaji native} synonyms " +
            "description(asHtml:false) averageScore genres startDate{year} coverImage{large} bannerImage " +
            "nextAiringEpisode{airingAt episode} " +
            "streamingEpisodes{title thumbnail site}}}"
        val raw = Http.postStringQuiet(
            "https://graphql.anilist.co",
            JSONObject().put("query", query).toString(),
        ) ?: return null
        val media = runCatching {
            JSONObject(raw).optJSONObject("data")?.optJSONObject("Media")
        }.getOrNull() ?: return null
        val titles = media.optJSONObject("title")
        val displayTitle = titles?.optString("userPreferred").takeIf { !it.isNullOrBlank() }
            ?: titles?.optString("english").takeIf { !it.isNullOrBlank() }
        val aliases = buildSet {
            listOf(
                titles?.optString("userPreferred"),
                titles?.optString("english"),
                titles?.optString("romaji"),
                titles?.optString("native"),
            ).forEach { t -> if (!t.isNullOrBlank()) add(t.trim()) }
            val syns = media.optJSONArray("synonyms")
            for (i in 0 until (syns?.length() ?: 0)) {
                syns?.optString(i)?.trim()?.takeIf { it.isNotBlank() }?.let { add(it) }
            }
        }
        val score = media.optDouble("averageScore", 0.0).takeIf { it > 0 }?.div(10.0)
        val next = media.optJSONObject("nextAiringEpisode")?.optLong("airingAt", 0L)?.takeIf { it > 0 }
            ?.let { java.time.Instant.ofEpochSecond(it).toString() }
        val eps = ArrayList<AnimeEp>()
        val seen = HashSet<Int>()
        val streams = media.optJSONArray("streamingEpisodes")
        val epRe = Regex("""(?i)^\s*(?:episode|ep\.?|e)?\s*(\d{1,4})\s*[-–—:.|]?\s*(.*)$""")
        for (i in 0 until (streams?.length() ?: 0)) {
            val o = streams?.optJSONObject(i) ?: continue
            val m = epRe.find(o.optString("title").trim()) ?: continue
            val n = m.groupValues[1].toIntOrNull() ?: continue
            if (n < 1 || n > 4000 || !seen.add(n)) continue
            val name = m.groupValues[2].trim().takeIf { it.isNotBlank() }
            val img = o.optString("thumbnail").trim().takeIf { it.startsWith("http") }
            eps += AnimeEp(n, name, img)
        }
        if (displayTitle == null && aliases.isEmpty()) return null
        if (matchAliases(wanted, aliases) < 55) return null
        val year = media.optJSONObject("startDate")?.optInt("year", 0)?.takeIf { it > 0 }
        val genres = (0 until (media.optJSONArray("genres")?.length() ?: 0))
            .mapNotNull { i -> media.optJSONArray("genres")?.optString(i)?.trim()?.takeIf { it.isNotBlank() } }
        val poster = media.optJSONObject("coverImage")?.optString("large")?.trim()?.takeIf { it.startsWith("http") }
        val backdrop = media.optString("bannerImage").trim().takeIf { it.startsWith("http") }
        return AnimeFull(
            displayTitle,
            media.optString("description").trim().takeIf { it.isNotBlank() },
            score,
            next,
            eps.sortedBy { it.number },
            aliases,
            media.optInt("id", 0),
            year,
            genres,
            poster,
            backdrop,
        )
    }

    private fun aniList(title: String): Metadata? {
        if (title.isBlank()) return null
        val query = "query{Media(search:" + JSONObject.quote(title) + ",type:ANIME){title{userPreferred english romaji native} synonyms averageScore genres startDate{year} coverImage{large} bannerImage nextAiringEpisode{airingAt episode}}}"
        val raw = Http.postStringQuiet("https://graphql.anilist.co", JSONObject().put("query", query).toString()) ?: return null
        val media = JSONObject(raw).optJSONObject("data")?.optJSONObject("Media") ?: return null
        val titles = media.optJSONObject("title")
        val aliases = buildSet {
            listOf(titles?.optString("userPreferred"), titles?.optString("english"), titles?.optString("romaji"), titles?.optString("native")).forEach { v -> if (!v.isNullOrBlank()) add(v.trim()) }
            val syns = media.optJSONArray("synonyms")
            for (i in 0 until (syns?.length() ?: 0)) syns?.optString(i)?.trim()?.takeIf { it.isNotBlank() }?.let { add(it) }
        }
        if (matchAliases(title, aliases) < 55) return null
        val next = media.optJSONObject("nextAiringEpisode")?.optLong("airingAt", 0L)?.takeIf { it > 0 }
            ?.let { java.time.Instant.ofEpochSecond(it).toString() }
        val score = media.optDouble("averageScore", 0.0).takeIf { it > 0 }?.div(10.0)
        val displayTitle = titles?.optString("userPreferred").takeIf { !it.isNullOrBlank() }
            ?: titles?.optString("english").takeIf { !it.isNullOrBlank() }
        val year = media.optJSONObject("startDate")?.optInt("year", 0)?.takeIf { it > 0 }
        val genres = (0 until (media.optJSONArray("genres")?.length() ?: 0))
            .mapNotNull { i -> media.optJSONArray("genres")?.optString(i)?.trim()?.takeIf { it.isNotBlank() } }
        val poster = media.optJSONObject("coverImage")?.optString("large")?.trim()?.takeIf { it.startsWith("http") }
        val backdrop = media.optString("bannerImage").trim().takeIf { it.startsWith("http") }
        return Metadata(displayTitle, score, next, "AniList", year, null, genres, poster, backdrop)
    }

    private data class SimklCalendarEntry(val title: String, val next: String?, val ids: Set<String>)
    private var calendarAt = 0L
    private var calendar: List<SimklCalendarEntry> = emptyList()

    private suspend fun simklCalendar(): List<SimklCalendarEntry> {
        val now = System.currentTimeMillis()
        if (calendar.isNotEmpty() && now - calendarAt < 6 * 60 * 60 * 1000L) return calendar
        val raw = Http.getStringQuiet("https://data.simkl.in/calendar/anime.json") ?: return calendar
        val root = runCatching { JSONObject(raw) }.getOrNull()
        val array = runCatching { org.json.JSONArray(raw) }.getOrNull() ?: root?.optJSONArray("anime") ?: root?.optJSONArray("items") ?: return calendar
        val out = ArrayList<SimklCalendarEntry>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val media = o.optJSONObject("anime") ?: o.optJSONObject("show") ?: o
            val idsObject = media.optJSONObject("ids") ?: o.optJSONObject("ids")
            val ids = buildSet {
                idsObject?.let { idsObj -> listOf("simkl", "tmdb", "mal", "anilist", "imdb").forEach { key ->
                    val value = idsObj.optString(key).trim()
                    if (value.isNotBlank() && value != "0") add(key + ":" + value.lowercase())
                }}
            }
            val title = media.optString("title").ifBlank { media.optString("name") }.trim()
            val date = listOf("airing_at", "airingAt", "release_date", "releaseDate", "date")
                .firstNotNullOfOrNull { key -> o.optString(key).takeIf { it.isNotBlank() } }
                ?: media.optString("airing_at").takeIf { it.isNotBlank() }
            if (title.isNotBlank()) out += SimklCalendarEntry(title, date, ids)
        }
        if (out.isNotEmpty()) { calendar = out; calendarAt = now }
        return calendar
    }

    private fun normalized(value: String): String =
        value.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").replace(Regex("\\s+"), " ").trim()

    private suspend fun simklNextEpisode(item: MediaItem): String? {
        val key = item.providerId.lowercase() + ":" + item.id.lowercase()
        val entries = simklCalendar()
        return entries.firstOrNull { key in it.ids }?.next ?: entries.firstOrNull {
            normalized(it.title) == normalized(item.searchTitle) || normalized(it.title) == normalized(item.title)
        }?.next
    }

    private suspend fun simkl(app: HikariApp, item: MediaItem): Metadata? {
        val client = app.store.trackerClients().firstOrNull { it.kind == TrackerKind.SIMKL } ?: return null
        if (!client.ready) return null
        val clientId = URLEncoder.encode(client.id, "UTF-8")
        val url = if (item.providerId.equals("tmdb", true)) {
            "https://api.simkl.com/ratings?tmdb=" + URLEncoder.encode(item.id, "UTF-8") +
                "&type=anime&fields=simkl,ext,rank,release_status,year&client_id=" + clientId
        } else {
            val raw = Http.getStringQuiet("https://api.simkl.com/search/anime?q=" +
                URLEncoder.encode(item.searchTitle, "UTF-8") + "&client_id=" + clientId) ?: return null
            val array = org.json.JSONArray(raw)
            if (array.length() == 0) return null
            val first = array.optJSONObject(0) ?: return null
            val id = first.optJSONObject("ids")?.optInt("simkl", 0)?.takeIf { it > 0 }
                ?: first.optInt("id", 0).takeIf { it > 0 } ?: return null
            "https://api.simkl.com/ratings?simkl=$id&fields=simkl,ext,rank,release_status,year&client_id=$clientId"
        }
        val raw = Http.getStringQuiet(url) ?: return null
        val rating = JSONObject(raw).optJSONObject("simkl")
        val next = runCatching { simklNextEpisode(item) }.getOrNull()
        return Metadata(item.title, rating?.optDouble("rating", 0.0)?.takeIf { it > 0 }, next, "Simkl")
    }
}
