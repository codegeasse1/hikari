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

    suspend fun seasonLayout(title: String): List<SeasonLayout> = withContext(Dispatchers.IO) {
        val wanted = title.trim()
        if (wanted.isBlank()) return@withContext emptyList()
        val key = wanted.lowercase()
        seasonCache[key]?.let { return@withContext it }

        fun escape(value: String): String = JSONObject.quote(value).removePrefix("\"").removeSuffix("\"")
        fun titleOf(o: JSONObject): String {
            val t = o.optJSONObject("title") ?: return ""
            return t.optString("userPreferred").trim().ifBlank { t.optString("english").trim() }
                .ifBlank { t.optString("romaji").trim() }
        }
        fun normalize(value: String): String = value.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .replace(Regex("\\s+"), " ").trim()
        fun similarity(a0: String, b0: String): Int {
            val a = normalize(a0); val b = normalize(b0)
            if (a.isBlank() || b.isBlank()) return 0
            if (a == b) return 100
            if (a.contains(b) || b.contains(a)) return 90
            val aa = a.split(" ").filter { it.length > 2 }.toSet()
            val bb = b.split(" ").filter { it.length > 2 }.toSet()
            if (aa.isEmpty() || bb.isEmpty()) return 0
            return aa.intersect(bb).size * 100 / maxOf(aa.size, bb.size)
        }
        fun explicitSeason(name: String): Int? {
            val patterns = listOf(
                Regex("""(?i)\b(\d+)(?:st|nd|rd|th)\s+season\b"""),
                Regex("""(?i)\bseason\s*(\d+)\b"""),
                Regex("""(?i)\b(first|second|third|fourth|fifth|sixth)\s+season\b""")
            )
            for (pattern in patterns) {
                val match = pattern.find(name) ?: continue
                val value = match.groupValues.getOrNull(1).orEmpty()
                value.toIntOrNull()?.let { return it }
                return when (value.lowercase()) {
                    "first" -> 1
                    "second" -> 2
                    "third" -> 3
                    "fourth" -> 4
                    "fifth" -> 5
                    "sixth" -> 6
                    else -> null
                }
            }
            return null
        }

        // Sequel rows (Season 3, 2nd Season, Part 2) search as the franchise
        // root: AniList resolves the base entry and the relation walk below
        // collects every season. The original title is still what similarity
        // is scored against.
        fun stripSequel(name: String): String = name.trim()
            .replace(Regex("""(?i)\s*[:\-–—]?\s*\bseason\s*\d+\s*$"""), "")
            .replace(Regex("""(?i)\s*\b\d+(?:st|nd|rd|th)\s+season\s*$"""), "")
            .replace(Regex("""(?i)\s*\b(?:part|cour)\s*\d+\s*$"""), "")
            .replace(Regex("""(?i)\s*\bs\d{1,2}\s*$"""), "")
            .trim()
        val queryTitle = stripSequel(wanted).ifBlank { wanted }
        val searchQuery = "query{Page(perPage:10){media(search:\"__WANTED__\",type:ANIME){id format episodes title{userPreferred english romaji}}}}"
        val searchRaw = Http.postStringQuiet(
            "https://graphql.anilist.co",
            JSONObject().put("query", searchQuery.replace("__WANTED__", escape(queryTitle))).toString()
        ) ?: return@withContext emptyList()
        val media = JSONObject(searchRaw).optJSONObject("data")?.optJSONObject("Page")?.optJSONArray("media")
            ?: return@withContext emptyList()

        val base = (0 until media.length()).mapNotNull { media.optJSONObject(it) }
            .filter { it.optString("format").equals("TV", true) }
            .maxByOrNull { candidate ->
                val candidateTitle = titleOf(candidate)
                similarity(wanted, candidateTitle) + if (explicitSeason(candidateTitle) == null) 15 else 0
            } ?: return@withContext emptyList()
        val baseId = base.optInt("id", 0)
        if (baseId <= 0) return@withContext emptyList()

        data class Node(val id: Int, val title: String, val episodes: Int, val year: Int)
        val nodes = LinkedHashMap<Int, Node>()
        val queue = ArrayDeque<Int>()
        val seen = HashSet<Int>()
        queue.add(baseId)

        while (queue.isNotEmpty() && seen.size < 12) {
            val id = queue.removeFirst()
            if (!seen.add(id)) continue
            val query = "query{Media(id:$id,type:ANIME){id format episodes seasonYear title{userPreferred english romaji} relations{edges{relationType node{id format episodes seasonYear title{userPreferred english romaji}}}}}}"
            val raw = Http.postStringQuiet("https://graphql.anilist.co", JSONObject().put("query", query).toString())
                ?: continue
            val mediaObject = JSONObject(raw).optJSONObject("data")?.optJSONObject("Media") ?: continue
            if (!mediaObject.optString("format").equals("TV", true)) continue
            val mediaTitle = titleOf(mediaObject)
            if (id != baseId && similarity(wanted, mediaTitle) < 55) continue
            val count = mediaObject.optInt("episodes", 0)
            if (count > 0) nodes[id] = Node(id, mediaTitle, count, mediaObject.optInt("seasonYear", 0))

            val edges = mediaObject.optJSONObject("relations")?.optJSONArray("edges") ?: continue
            for (i in 0 until edges.length()) {
                val edge = edges.optJSONObject(i) ?: continue
                val relation = edge.optString("relationType")
                if (relation != "SEQUEL" && relation != "PREQUEL") continue
                val child = edge.optJSONObject("node") ?: continue
                val childId = child.optInt("id", 0)
                if (childId <= 0 || childId in seen) continue
                if (similarity(wanted, titleOf(child)) >= 55) queue.add(childId)
            }
        }

        if (nodes.size < 2) return@withContext emptyList()
        val explicit = LinkedHashMap<Int, Int>()
        nodes.values.forEach { node ->
            explicitSeason(node.title)?.let { season ->
                if (season > 0) explicit[season] = (explicit[season] ?: 0) + node.episodes
            }
        }
        val result = if (explicit.size >= 2) {
            val marked = explicit.entries.sortedBy { it.key }
            val markedMinYear = nodes.values
                .filter { explicitSeason(it.title) != null }
                .map { it.year }.filter { it > 0 }.minOrNull()
            val unmarked = nodes.values
                .filter { explicitSeason(it.title) == null }
                .sortedWith(compareBy<Node> { it.year == 0 }.thenBy { it.year }.thenBy { it.id })
            // Nodes without a season in their title are usually the missing
            // early seasons (season 1 never says so): those that aired before
            // every marked season slot in below them, later ones extend above.
            // Without this a Season 3 row built a two-season layout that could
            // never match the full list, leaving it flat.
            val out = ArrayList<SeasonLayout>()
            val earlyPool = if (markedMinYear != null) {
                unmarked.filter { it.year in 1 until markedMinYear }
            } else {
                unmarked.take(1)
            }
            val slots = marked.minOf { it.key } - 1
            val keptEarly = if (slots > 0) earlyPool.takeLast(minOf(slots, earlyPool.size)) else emptyList()
            var s = marked.minOf { it.key } - keptEarly.size
            for (n in keptEarly) out += SeasonLayout(s++, n.episodes)
            for ((season, eps) in marked) out += SeasonLayout(season, eps)
            var next = marked.maxOf { it.key } + 1
            for (n in unmarked) {
                if (n in keptEarly) continue
                out += SeasonLayout(next++, n.episodes)
            }
            out.sortedBy { it.season }
        } else {
            nodes.values.sortedWith(compareBy<Node> { it.year == 0 }.thenBy { it.year }.thenBy { it.id })
                .mapIndexed { index, node -> SeasonLayout(index + 1, node.episodes) }
        }
        if (result.size >= 2) seasonCache[key] = result
        result
    }

    suspend fun enrich(app: HikariApp, item: MediaItem, type: ProviderType? = null): Metadata? = withContext(Dispatchers.IO) {
        val anime = isAnimeItem(item, type) || item.providerId in setOf("anilist", "mal", "kitsu", "shikimori")
        if (!anime || !runCatching { app.store.animeMetadataEnabled() }.getOrDefault(true)) return@withContext null
        val mode = normalizeMode(runCatching { app.store.animeMetadataSource() }.getOrDefault("anilist_simkl"))
        // The source picker is a real source selector, not merely a preference
        // for the rating. Both sources are asked in PARALLEL, so choosing two
        // costs the slower one — never the sum. The canonical record rides
        // along (shared 24h cache with the episode pass) so the header gets
        // AniList's year, overview and artwork too — not just its title.
        return@withContext coroutineScope {
            val ani = if (mode == "simkl") null
            else async { runCatching { aniList(item.searchTitle) }.getOrNull() }
            val sim = if (mode == "anilist") null
            else async { runCatching { simkl(app, item) }.getOrNull() }
            val full = if (mode == "simkl") null
            else async {
                runCatching {
                    kotlinx.coroutines.withTimeoutOrNull(12_000L) { animeFull(item.searchTitle) }
                }.getOrNull()
            }
            val a = ani?.await()
            val s = sim?.await()
            val f = full?.await()
            if (a == null && s == null && f == null) return@coroutineScope null
            Metadata(
                a?.title ?: f?.title ?: s?.title,
                if (mode == "simkl") s?.rating else a?.rating ?: f?.rating ?: s?.rating,
                a?.nextEpisodeDate ?: f?.nextEpisodeDate ?: s?.nextEpisodeDate,
                listOfNotNull(s?.source, a?.source ?: f?.let { "AniList" }).distinct().joinToString(" + "),
                a?.year ?: f?.year,
                f?.description,
                (a?.genres ?: emptyList()).ifEmpty { f?.genres.orEmpty() },
                a?.posterUrl ?: f?.posterUrl,
                a?.backdropUrl ?: f?.backdropUrl,
            )
        }
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

    fun includesAnilist(mode: String): Boolean = normalizeMode(mode) != "simkl"

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

    suspend fun animeFull(title: String): AnimeFull? = withContext(Dispatchers.IO) {
        val wanted = title.trim()
        if (wanted.isEmpty()) return@withContext null
        val key = wanted.lowercase()
        fullCache[key]?.let { if (System.currentTimeMillis() - it.at < FULL_TTL_MS) return@withContext it.full }
        val full = runCatching { fetchAnimeFull(wanted) }.getOrNull()
        fullCache[key] = FullEntry(System.currentTimeMillis(), full)
        if (fullCache.size > 300) fullCache.keys.firstOrNull()?.let { fullCache.remove(it) }
        full
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
