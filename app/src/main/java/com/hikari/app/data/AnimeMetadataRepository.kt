package com.hikari.app.data
import com.hikari.app.HikariApp
import com.hikari.app.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

object AnimeMetadataRepository {
    data class Metadata(val title: String? = null, val rating: Double? = null, val nextEpisodeDate: String? = null, val source: String)
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

        val searchQuery = "query{Page(perPage:10){media(search:\"__WANTED__\",type:ANIME){id format episodes title{userPreferred english romaji}}}}"
        val searchRaw = Http.postStringQuiet(
            "https://graphql.anilist.co",
            JSONObject().put("query", searchQuery.replace("__WANTED__", escape(wanted))).toString()
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
            explicit.entries.sortedBy { it.key }.map { SeasonLayout(it.key, it.value) }
        } else {
            nodes.values.sortedWith(compareBy<Node> { it.year == 0 }.thenBy { it.year }.thenBy { it.id })
                .mapIndexed { index, node -> SeasonLayout(index + 1, node.episodes) }
        }
        if (result.size >= 2) seasonCache[key] = result
        result
    }

    suspend fun enrich(app: HikariApp, item: MediaItem): Metadata? = withContext(Dispatchers.IO) {
        val anime = item.rawType.equals("anime", true) || item.providerId in setOf("anilist", "mal", "kitsu", "shikimori")
        if (!anime || !runCatching { app.store.animeMetadataEnabled() }.getOrDefault(true)) return@withContext null
        val mode = runCatching { app.store.animeMetadataSource() }.getOrDefault("auto").lowercase()
        val ani = runCatching { aniList(item.searchTitle) }.getOrNull()
        val sim = if (mode == "anilist") null else runCatching { simkl(app, item) }.getOrNull()
        if (ani == null && sim == null) return@withContext null
        Metadata(ani?.title ?: sim?.title, sim?.rating ?: ani?.rating, ani?.nextEpisodeDate,
            listOfNotNull(sim?.source, ani?.source).distinct().joinToString(" + "))
    }

    private fun aniList(title: String): Metadata? {
        if (title.isBlank()) return null
        val query = "query{Media(search:" + JSONObject.quote(title) + ",type:ANIME){title{userPreferred english romaji}averageScore nextAiringEpisode{airingAt episode}}}"
        val raw = Http.postStringQuiet("https://graphql.anilist.co", JSONObject().put("query", query).toString()) ?: return null
        val media = JSONObject(raw).optJSONObject("data")?.optJSONObject("Media") ?: return null
        val titles = media.optJSONObject("title")
        val next = media.optJSONObject("nextAiringEpisode")?.optLong("airingAt", 0L)?.takeIf { it > 0 }
            ?.let { java.time.Instant.ofEpochSecond(it).toString() }
        val score = media.optDouble("averageScore", 0.0).takeIf { it > 0 }?.div(10.0)
        val displayTitle = titles?.optString("userPreferred").takeIf { !it.isNullOrBlank() }
            ?: titles?.optString("english").takeIf { !it.isNullOrBlank() }
        return Metadata(displayTitle, score, next, "AniList")
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
        return Metadata(item.title, rating?.optDouble("rating", 0.0)?.takeIf { it > 0 }, null, "Simkl")
    }
}
