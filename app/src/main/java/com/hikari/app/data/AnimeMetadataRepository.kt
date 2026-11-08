package com.hikari.app.data
import com.hikari.app.HikariApp
import com.hikari.app.net.Http
import com.hikari.app.nuvio.TmdbResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

object AnimeMetadataRepository {
    /** org.json renders a JSON null as the literal string "null" — without
     *  this guard an absent AniList title/synonym becomes a searchable query
     *  for the word "null" (log-proven junk candidates on every sync). */
    private fun isNullWord(s: String): Boolean {
        val t = s.trim().lowercase()
        return t == "null" || t == "undefined" || t == "none"
    }
    data class Metadata(val title: String? = null, val rating: Double? = null, val nextEpisodeDate: String? = null, val source: String, val year: Int? = null, val overview: String? = null, val genres: List<String> = emptyList(), val posterUrl: String? = null, val backdropUrl: String? = null)
    data class SeasonLayout(val season: Int, val episodes: Int)
    private val seasonCache = ConcurrentHashMap<String, List<SeasonLayout>>()

        suspend fun seasonLayout(title: String): List<SeasonLayout> {
        return emptyList()
    }

    suspend fun enrich(app: HikariApp, item: MediaItem, type: ProviderType? = null): Metadata? {
        if (!isAnimeItem(item, type)) return null
        val store = app.store
        val wantSimkl = runCatching { store.animeSimkl() }.getOrDefault(true)
        val wantAnilist = runCatching { store.animeAnilist() }.getOrDefault(true)
        val wantTmdb = runCatching { store.animeTmdb() }.getOrDefault(true)
        if (!wantSimkl && !wantAnilist && !wantTmdb) return null
        val query = item.searchTitle.ifBlank { item.title }
        if (query.isBlank()) return null
        var title: String? = null
        var rating: Double? = null
        var next: String? = null
        var year: Int? = null
        var overview: String? = null
        var genres: List<String> = emptyList()
        var poster: String? = null
        var backdrop: String? = null
        var source: String? = null
        var aliases: Set<String> = emptySet()
        if (wantSimkl) {
            val simklMeta = runCatching {
                val id = simklClientId(app) ?: return@runCatching null
                withContext(Dispatchers.IO) { simklWithId(item, id) }
            }.getOrNull()
            simklMeta?.let { m ->
                if (title == null) m.title?.let { title = it; source = m.source }
                if (rating == null) m.rating?.let { rating = it; if (source == null) source = m.source }
                if (next == null) m.nextEpisodeDate?.let { next = it; if (source == null) source = m.source }
            }
        }
        if (wantAnilist) {
            runCatching { withContext(Dispatchers.IO) { animeFull(query) } }.getOrNull()?.let { full ->
                aliases = full.aliases
                if (title == null) full.title?.let { title = it; source = "AniList" }
                if (rating == null) full.rating?.let { rating = it; if (source == null) source = "AniList" }
                if (next == null) full.nextEpisodeDate?.let { next = it; if (source == null) source = "AniList" }
                if (year == null) full.year?.let { year = it }
                if (genres.isEmpty() && full.genres.isNotEmpty()) genres = full.genres
                if (poster == null) full.posterUrl?.let { poster = it }
                if (backdrop == null) full.backdropUrl?.let { backdrop = it }
            }
        }
        if (wantTmdb) {
            runCatching { withContext(Dispatchers.IO) { tmdbAnime(item, aliases) } }.getOrNull()?.let { m ->
                if (title == null) m.title?.let { title = it; source = m.source }
                if (rating == null) m.rating?.let { rating = it; if (source == null) source = m.source }
                if (year == null) m.year?.let { year = it }
                if (overview == null) m.overview?.let { overview = it }
                if (genres.isEmpty() && m.genres.isNotEmpty()) genres = m.genres
                if (poster == null) m.posterUrl?.let { poster = it }
                if (backdrop == null) m.backdropUrl?.let { backdrop = it }
                if (next == null) m.nextEpisodeDate?.let { next = it }
            }
        }
        val from = source ?: return null
        Logs.log(
            "AnimeMeta",
            "anime metadata for \"" + query.take(80) + "\" from " + from +
                " (title=" + (title != null) + " overview=" + (overview != null) +
                " poster=" + (poster != null) + ")",
        )
        return Metadata(title, rating, next, from, year, overview, genres, poster, backdrop)
    }

    private suspend fun tmdbAnime(item: MediaItem, aliases: Set<String>): Metadata? {
        val resolved = runCatching { TmdbResolver.resolve(item) }.getOrNull() ?: return null
        if (!resolved.mediaType.equals("tv", true) && !resolved.mediaType.equals("movie", true)) return null
        val details = runCatching {
            TmdbResolver.apiGet("/" + resolved.mediaType + "/" + resolved.tmdbId, emptyMap())
        }.getOrNull() ?: return null
        val movie = resolved.mediaType.equals("movie", true)
        val tmdbTitle = (if (movie) details.optString("title").ifBlank { details.optString("original_title") }
        else details.optString("name").ifBlank { details.optString("original_name") })
            .trim().takeIf { it.isNotBlank() }
        val known = aliases + item.searchTitle + item.title
        val title = tmdbTitle?.takeIf { matchAliases(it, known) >= 55 }
        val year = (if (movie) details.optString("release_date") else details.optString("first_air_date"))
            .trim().take(4).toIntOrNull()?.takeIf { it > 1900 }
        val genres = (0 until (details.optJSONArray("genres")?.length() ?: 0))
            .mapNotNull { i -> details.optJSONArray("genres")?.optJSONObject(i)?.optString("name")?.trim()?.takeIf { it.isNotBlank() } }
        val poster = details.optString("poster_path").trim().takeIf { it.isNotBlank() }
            ?.let { "https://image.tmdb.org/t/p/w500" + it }
        val backdrop = details.optString("backdrop_path").trim().takeIf { it.isNotBlank() }
            ?.let { "https://image.tmdb.org/t/p/w1280" + it }
        val rating = details.optDouble("vote_average", 0.0).takeIf { it > 0 }
        val ov = details.optString("overview").trim().takeIf { it.isNotBlank() }
        if (title == null && rating == null && ov == null && genres.isEmpty() && poster == null && year == null) return null
        return Metadata(title, rating, null, "TMDB", year, ov, genres, poster, backdrop)
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

    fun isAnimeItem(item: MediaItem, type: ProviderType? = null): Boolean =
        item.rawType.contains("anime", true) ||
            type == ProviderType.ANYMEX || type == ProviderType.ANIYOMI ||
            item.providerId.lowercase() in setOf("anilist", "mal", "kitsu", "shikimori", "simkl")

    data class AnimeEp(val number: Int, val title: String?, val image: String?)

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
        val key = title.trim().lowercase()
        if (key.isBlank()) return null
        fullCache[key]?.let { if (System.currentTimeMillis() - it.at < FULL_TTL_MS) return it.full }
        val f = withContext(Dispatchers.IO) { fetchAnimeFull(title) } ?: return null
        fullCache[key] = FullEntry(System.currentTimeMillis(), f)
        return f
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
        val displayTitle = titles?.optString("userPreferred").takeIf { !it.isNullOrBlank() && !isNullWord(it) }
            ?: titles?.optString("english").takeIf { !it.isNullOrBlank() && !isNullWord(it) }
        val aliases = buildSet {
            listOf(
                titles?.optString("userPreferred"),
                titles?.optString("english"),
                titles?.optString("romaji"),
                titles?.optString("native"),
            ).forEach { t -> if (!t.isNullOrBlank() && !isNullWord(t)) add(t.trim()) }
            val syns = media.optJSONArray("synonyms")
            for (i in 0 until (syns?.length() ?: 0)) {
                syns?.optString(i)?.trim()?.takeIf { it.isNotBlank() && !isNullWord(it) }?.let { add(it) }
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

    /** Simkl app ID with no login step: the connected Simkl tracker's when
     *  there is one, else the free app ID pasted in Settings → Integrations
     *  → Anime. Null when neither exists — the Simkl step is then skipped
     *  and AniList/TMDB carry the row. */
    private suspend fun simklClientId(app: HikariApp): String? {
        val trackerId = runCatching {
            app.store.trackerClients().firstOrNull { it.kind == TrackerKind.SIMKL }?.id.orEmpty()
        }.getOrDefault("").trim()
        if (trackerId.isNotBlank()) return trackerId
        return runCatching { app.store.animeSimklAppId() }.getOrDefault("").trim()
            .takeIf { it.isNotBlank() }
    }

    private suspend fun simklWithId(item: MediaItem, clientId: String): Metadata? {
        val encoded = URLEncoder.encode(clientId, "UTF-8")
        val url = if (item.providerId.equals("tmdb", true)) {
            "https://api.simkl.com/ratings?tmdb=" + URLEncoder.encode(item.id, "UTF-8") +
                "&type=anime&fields=simkl,ext,rank,release_status,year&client_id=" + encoded
        } else {
            val raw = Http.getStringQuiet("https://api.simkl.com/search/anime?q=" +
                URLEncoder.encode(item.searchTitle, "UTF-8") + "&client_id=" + encoded) ?: return null
            val array = org.json.JSONArray(raw)
            if (array.length() == 0) return null
            val first = array.optJSONObject(0) ?: return null
            val id = first.optJSONObject("ids")?.optInt("simkl", 0)?.takeIf { it > 0 }
                ?: first.optInt("id", 0).takeIf { it > 0 } ?: return null
            "https://api.simkl.com/ratings?simkl=$id&fields=simkl,ext,rank,release_status,year&client_id=$encoded"
        }
        val raw = Http.getStringQuiet(url) ?: return null
        val rating = JSONObject(raw).optJSONObject("simkl")
        val next = runCatching { simklNextEpisode(item) }.getOrNull()
        return Metadata(item.title, rating?.optDouble("rating", 0.0)?.takeIf { it > 0 }, next, "Simkl")
    }
}
