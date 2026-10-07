package com.hikari.app.data

import com.hikari.app.HikariApp
import com.hikari.app.net.Http
import com.hikari.app.nuvio.BangumiMeta
import com.hikari.app.nuvio.EpisodeTitles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

object TrackerAnimeResolver {
    private val trackerProviders = setOf("anilist", "simkl", "mal", "kitsu", "shikimori")

    data class Detail(
        val overview: String? = null,
        val genres: List<String> = emptyList(),
        val year: Int? = null,
        val posterUrl: String? = null,
        val backdropUrl: String? = null,
        val rating: Double? = null,
    )

    fun isTrackerAnime(item: MediaItem): Boolean {
        if (item.providerId.lowercase() !in trackerProviders) return false
        if (item.type != MediaType.SERIES) return false
        return item.rawType.contains("anime", true) || item.providerId.equals("anilist", true)
    }

    suspend fun fallbackEpisodes(item: MediaItem): List<Episode>? = withContext(Dispatchers.IO) {
        if (!isTrackerAnime(item)) return@withContext null
        val count = episodeCount(item) ?: return@withContext null
        if (count < 1 || count > 3000) return@withContext null
        List(count) { i -> Episode(number = i + 1, id = item.id + "#e" + (i + 1), name = "Episode " + (i + 1), season = 1) }
    }

    suspend fun trackerEpisodes(item: MediaItem): List<Episode>? = withContext(Dispatchers.IO) {
        if (!isTrackerAnime(item)) return@withContext null
        bangumiEpisodes(item)?.takeIf { it.size >= 3 }?.let { return@withContext enrichEpisodes(item, it) }
        simklCountEpisodes(item)?.let { return@withContext enrichEpisodes(item, it) }
        episodeCount(item)?.takeIf { it in 1..3000 }?.let { count ->
            return@withContext enrichEpisodes(item, List(count) { i ->
                Episode(number = i + 1, id = item.id + "#e" + (i + 1), name = "Episode " + (i + 1), season = 1)
            })
        }
        null
    }

    suspend fun detail(item: MediaItem): Detail? = withContext(Dispatchers.IO) {
        if (!isTrackerAnime(item)) return@withContext null
        if (item.providerId.equals("anilist", true)) {
            runCatching { anilistDetail(item) }.getOrNull()?.let { return@withContext it }
            return@withContext runCatching { anilistSearchDetail(item.searchTitle) }.getOrNull()
        }
        runCatching { simklDetail(item) }.getOrNull()?.let { return@withContext it }
        runCatching { anilistSearchDetail(item.searchTitle) }.getOrNull()
    }

    private val streamCache = java.util.concurrent.ConcurrentHashMap<Int, Pair<Long, Map<Int, StreamEp>>>()
    private val malCache = java.util.concurrent.ConcurrentHashMap<Int, Pair<Long, Int>>()
    private val offsetCache = java.util.concurrent.ConcurrentHashMap<Int, Pair<Long, Int?>>()
    private val jikanCache = java.util.concurrent.ConcurrentHashMap<Int, Pair<Long, Map<Int, JikanEp>>>()
    private val anilistIdCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Int?>>()
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    private class StreamEp(val name: String?, val thumbnail: String?)
    private class JikanEp(val name: String?, val aired: String?, val score: Double?)

    private fun fresh(at: Long): Boolean = System.currentTimeMillis() - at < DAY_MS

    private suspend fun enrichEpisodes(item: MediaItem, eps: List<Episode>): List<Episode> {
        if (eps.isEmpty()) return eps
        var out = tmdbFill(item, eps)
        out = streamingFill(item, out)
        out = jikanFill(item, out)
        return out
    }

    private fun filled(e: Episode, name: String?, overview: String?, released: String?, rating: Double?, runtime: Int?, image: String?): Episode {
        val nn = if (!name.isNullOrBlank() && EpisodeTitles.isGeneric(e.name)) name.trim() else null
        val ov = if (e.overview.isNullOrBlank()) overview?.trim()?.takeIf { it.isNotBlank() } else e.overview
        val rel = if (e.released.isNullOrBlank()) released?.trim()?.takeIf { it.isNotBlank() } else e.released
        val ra = e.rating ?: rating
        val ru = e.runtime ?: runtime
        val im = if (e.image.isNullOrBlank()) image?.trim()?.takeIf { it.isNotBlank() } else e.image
        if (nn == null && ov == e.overview && rel == e.released && ra == e.rating && ru == e.runtime && im == e.image) return e
        return e.copy(name = nn ?: e.name, overview = ov, released = rel, rating = ra, runtime = ru, image = im)
    }

    private suspend fun tmdbFill(item: MediaItem, eps: List<Episode>): List<Episode> {
        val tmdbId = runCatching { simklTmdbRef(item) }.getOrNull()?.tmdbId?.toIntOrNull()?.takeIf { it > 0 }
            ?: runCatching { com.hikari.app.nuvio.TmdbResolver.resolve(item) }.getOrNull()
                ?.takeIf { it.mediaType.equals("tv", true) }?.tmdbId?.toIntOrNull()?.takeIf { it > 0 }
            ?: return eps
        val tv = com.hikari.app.nuvio.TmdbResolver.apiGet("/tv/" + tmdbId, emptyMap()) ?: return eps
        val seasonsArr = tv.optJSONArray("seasons")
        val tmdbSeasons = (0 until (seasonsArr?.length() ?: 0)).mapNotNull { i ->
            val s = seasonsArr?.optJSONObject(i)
            val n = s?.optInt("season_number", -1) ?: -1
            val c = s?.optInt("episode_count", 0) ?: 0
            if (n > 0 && c > 0) n to c else null
        }.toMap()
        if (tmdbSeasons.isEmpty()) return eps
        val want = eps.map { it.number }.toSet()
        val hint = TmdbMeta.seasonHint(item.searchTitle)
        if (hint != null) {
            val c = tmdbSeasons[hint]
            if (c != null && kotlin.math.abs(c - eps.size) <= maxOf(3, (eps.size * 0.25).toInt())) {
                val names = runCatching { EpisodeTitles.lookupForId(tmdbId, want, hint) }.getOrNull()
                val details = runCatching { EpisodeTitles.detailsForId(tmdbId, want, hint) }.getOrNull().orEmpty()
                if ((names == null || names.isEmpty()) && details.isEmpty()) return eps
                return eps.map { e ->
                    val nm = names?.english?.get(e.number) ?: names?.generic?.get(e.number)
                    val d = details[e.number]
                    filled(e, nm, d?.overview, d?.released, d?.rating, d?.runtime, d?.image)
                }
            }
        }
        val offset = prequelOffset(item) ?: return eps
        val abs = want.map { it + offset }.toSet()
        val names = runCatching { EpisodeTitles.lookupForId(tmdbId, abs, null) }.getOrNull()
        val details = runCatching { EpisodeTitles.detailsForId(tmdbId, abs, null) }.getOrNull().orEmpty()
        if ((names == null || names.isEmpty()) && details.isEmpty()) return eps
        return eps.map { e ->
            val a = e.number + offset
            val nm = names?.english?.get(a) ?: names?.generic?.get(a)
            val d = details[a]
            filled(e, nm, d?.overview, d?.released, d?.rating, d?.runtime, d?.image)
        }
    }

    private suspend fun streamingFill(item: MediaItem, eps: List<Episode>): List<Episode> {
        if (eps.none { EpisodeTitles.isGeneric(it.name) || it.image.isNullOrBlank() }) return eps
        val anilistId = (if (item.providerId.equals("anilist", true)) item.id.toIntOrNull()?.takeIf { it > 0 } else null)
            ?: anilistIdForTitle(item.searchTitle) ?: return eps
        val streams = anilistStreams(anilistId)
        if (streams.isEmpty()) return eps
        var changed = false
        val out = eps.map { e ->
            val s = streams[e.number] ?: return@map e
            val nn = if (!s.name.isNullOrBlank() && EpisodeTitles.isGeneric(e.name)) s.name else null
            val im = if (e.image.isNullOrBlank()) s.thumbnail else e.image
            if (nn == null && im == e.image) return@map e
            changed = true
            e.copy(name = nn ?: e.name, image = im)
        }
        return if (changed) out else eps
    }

    private suspend fun jikanFill(item: MediaItem, eps: List<Episode>): List<Episode> {
        if (eps.none { EpisodeTitles.isGeneric(it.name) || it.overview.isNullOrBlank() }) return eps
        val malId = (if (item.providerId.equals("mal", true)) item.id.toIntOrNull()?.takeIf { it > 0 } else null)
            ?: anilistMalId(item) ?: return eps
        val got = jikanEpisodes(malId)
        if (got.isEmpty()) return eps
        var changed = false
        val out = eps.map { e ->
            val j = got[e.number] ?: return@map e
            val filledEp = filled(e, j.name, null, j.aired, j.score, null, null)
            if (filledEp != e) changed = true
            filledEp
        }
        return if (changed) out else eps
    }

    private suspend fun anilistMalId(item: MediaItem): Int? {
        if (!item.providerId.equals("anilist", true)) return null
        val id = item.id.toIntOrNull()?.takeIf { it > 0 } ?: return null
        malCache[id]?.let { if (fresh(it.first)) return it.second.takeIf { v -> v > 0 } }
        val raw = Http.postStringQuiet(
            "https://graphql.anilist.co",
            JSONObject().put("query", "query{Media(id:" + id + ",type:ANIME){idMal}}").toString(),
        ) ?: return null
        val mal = runCatching {
            JSONObject(raw).optJSONObject("data")?.optJSONObject("Media")?.optInt("idMal", 0)?.takeIf { it > 0 }
        }.getOrNull()
        if (mal != null) malCache[id] = System.currentTimeMillis() to mal
        return mal
    }

    private suspend fun anilistStreams(anilistId: Int): Map<Int, StreamEp> {
        streamCache[anilistId]?.let { if (fresh(it.first)) return it.second }
        val raw = Http.postStringQuiet(
            "https://graphql.anilist.co",
            JSONObject().put("query", "query{Media(id:" + anilistId + ",type:ANIME){streamingEpisodes{title thumbnail}}}").toString(),
        )
        val out = HashMap<Int, StreamEp>()
        val arr = runCatching {
            JSONObject(raw ?: "").optJSONObject("data")?.optJSONObject("Media")?.optJSONArray("streamingEpisodes")
        }.getOrNull()
        for (i in 0 until (arr?.length() ?: 0)) {
            val o = arr?.optJSONObject(i) ?: continue
            val m = Regex("""(?i)^(?:episode|ep)\s*0*(\d+)\s*[-–—:.]?\s*(.*)$""").find(o.optString("title").trim()) ?: continue
            val n = m.groupValues[1].toIntOrNull()?.takeIf { it in 1..4000 } ?: continue
            val name = m.groupValues[2].trim().takeIf { it.isNotBlank() }
            val thumb = o.optString("thumbnail").trim().takeIf { it.startsWith("http") }
            if (name == null && thumb == null) continue
            if (!out.containsKey(n)) out[n] = StreamEp(name, thumb)
        }
        streamCache[anilistId] = System.currentTimeMillis() to out
        return out
    }

    private suspend fun anilistIdForTitle(title: String): Int? {
        val t = title.trim()
        if (t.isBlank()) return null
        anilistIdCache[t.lowercase()]?.let { if (fresh(it.first)) return it.second }
        val raw = Http.postStringQuiet(
            "https://graphql.anilist.co",
            JSONObject().put("query", "query{Media(search:" + JSONObject.quote(t) + ",type:ANIME){id title{userPreferred english romaji native} synonyms}}").toString(),
        ) ?: return null
        val media = runCatching { JSONObject(raw).optJSONObject("data")?.optJSONObject("Media") }.getOrNull()
        val id = media?.optInt("id", 0)?.takeIf { it > 0 }
        if (id == null) {
            anilistIdCache[t.lowercase()] = System.currentTimeMillis() to null
            return null
        }
        val titles = media?.optJSONObject("title")
        val aliases = buildSet {
            listOf(titles?.optString("userPreferred"), titles?.optString("english"), titles?.optString("romaji"), titles?.optString("native")).forEach { v -> if (!v.isNullOrBlank()) add(v.trim()) }
            val syns = media?.optJSONArray("synonyms")
            for (i in 0 until (syns?.length() ?: 0)) syns?.optString(i)?.trim()?.takeIf { it.isNotBlank() }?.let { add(it) }
        }
        val found = if (AnimeMetadataRepository.matchAliases(t, aliases) >= 55) id else null
        anilistIdCache[t.lowercase()] = System.currentTimeMillis() to found
        return found
    }

    private suspend fun prequelOffset(item: MediaItem): Int? {
        val anilistId = (if (item.providerId.equals("anilist", true)) item.id.toIntOrNull()?.takeIf { it > 0 } else null)
            ?: anilistIdForTitle(item.searchTitle) ?: return null
        offsetCache[anilistId]?.let { if (fresh(it.first)) return it.second }
        var sum = 0
        var cur = anilistId
        val seen = HashSet<Int>()
        var ok = true
        repeat(8) {
            if (!seen.add(cur)) return@repeat
            val pre = prequelOf(cur)
            if (pre == null) return@repeat
            if (pre.second <= 0) {
                ok = false
                return@repeat
            }
            sum += pre.second
            cur = pre.first
        }
        val out = if (ok) sum else null
        offsetCache[anilistId] = System.currentTimeMillis() to out
        return out
    }

    private suspend fun prequelOf(anilistId: Int): Pair<Int, Int>? {
        val raw = Http.postStringQuiet(
            "https://graphql.anilist.co",
            JSONObject().put("query", "query{Media(id:" + anilistId + ",type:ANIME){relations{edges{relationType version} node{id episodes}}}}").toString(),
        ) ?: return null
        val edges = runCatching {
            JSONObject(raw).optJSONObject("data")?.optJSONObject("Media")?.optJSONObject("relations")?.optJSONArray("edges")
        }.getOrNull() ?: return null
        var best: Pair<Int, Int>? = null
        for (i in 0 until edges.length()) {
            val e = edges.optJSONObject(i) ?: continue
            if (!e.optString("relationType").equals("prequel", true)) continue
            val node = e.optJSONObject("node") ?: continue
            val id = node.optInt("id", 0)
            if (id <= 0) continue
            val count = node.optInt("episodes", 0)
            if (count <= 0) return null
            if (best == null || count > best.second) best = id to count
        }
        return best
    }

    private suspend fun jikanEpisodes(malId: Int): Map<Int, JikanEp> {
        jikanCache[malId]?.let { if (fresh(it.first)) return it.second }
        val out = HashMap<Int, JikanEp>()
        var page = 1
        var lastPage = 1
        repeat(30) {
            val raw = Http.getStringQuiet("https://api.jikan.moe/v4/anime/" + malId + "/episodes?page=" + page) ?: return@repeat
            val root = runCatching { JSONObject(raw) }.getOrNull() ?: return@repeat
            lastPage = root.optJSONObject("pagination")?.optInt("last_visible_page", 1)?.takeIf { it > 0 } ?: 1
            val arr = root.optJSONArray("data")
            for (i in 0 until (arr?.length() ?: 0)) {
                val o = arr?.optJSONObject(i) ?: continue
                val n = o.optInt("mal_id", 0)
                if (n !in 1..4000 || out.containsKey(n)) continue
                val name = o.optString("title_romanji").trim().takeIf { it.isNotBlank() && !it.equals("null", true) }
                    ?: o.optString("title").trim().takeIf { it.isNotBlank() && !it.equals("null", true) }
                val aired = o.optString("aired").trim().take(10).takeIf { it.isNotBlank() && !it.equals("null", true) }
                val score = o.optDouble("score", 0.0).takeIf { it > 0.0 }
                if (name == null && aired == null && score == null) continue
                out[n] = JikanEp(name, aired, score)
            }
            if (page >= lastPage) return@repeat
            page++
            delay(400)
        }
        jikanCache[malId] = System.currentTimeMillis() to out
        return out
    }

    data class TmdbRef(val tmdbId: String, val mediaType: String)

    suspend fun simklTmdbRef(item: MediaItem): TmdbRef? = withContext(Dispatchers.IO) {
        val clientId = simklClientId()
        if (clientId.isBlank()) return@withContext null
        val o = simklAnimeObject(item, clientId) ?: return@withContext null
        val tmdb = o.optJSONObject("ids")?.optInt("tmdb", 0)?.takeIf { it > 0 } ?: return@withContext null
        val kind = o.optString("anime_type").trim().lowercase()
            .ifBlank { o.optString("type").trim().lowercase() }
        val mediaType = if (kind == "movie") "movie" else "tv"
        TmdbRef(tmdb.toString(), mediaType)
    }

    private fun simklAnimeObject(item: MediaItem, clientId: String): JSONObject? {
        val direct = if (item.providerId.equals("simkl", true)) {
            item.id.toIntOrNull()?.takeIf { it > 0 }
        } else {
            null
        }
        val simklId = direct ?: simklIdFor(item.searchTitle, clientId) ?: return null
        val raw = Http.getStringQuiet(
            "https://api.simkl.com/anime/" + simklId + "?extended=full&client_id=" + URLEncoder.encode(clientId, "UTF-8"),
        ) ?: return null
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        return o.optJSONObject("anime") ?: o
    }

    private suspend fun simklClientId(): String =
        runCatching {
            HikariApp.instance.store.trackerClients()
                .firstOrNull { it.kind == TrackerKind.SIMKL }?.id.orEmpty()
        }.getOrDefault("")

    private suspend fun simklDetail(item: MediaItem): Detail? {
        val clientId = simklClientId()
        if (clientId.isBlank()) return null
        val data = simklAnimeObject(item, clientId) ?: return null
        val overview = data.optString("overview").trim().takeIf { it.isNotBlank() }
        val genres = (0 until (data.optJSONArray("genres")?.length() ?: 0))
            .mapNotNull { i -> data.optJSONArray("genres")?.optString(i)?.trim()?.takeIf { it.isNotBlank() } }
        val year = data.optInt("year", 0).takeIf { it > 0 }
            ?: data.optString("first_aired").take(4).toIntOrNull()
            ?: data.optString("year").take(4).toIntOrNull()
        val rating = data.optJSONObject("ratings")?.optJSONObject("simkl")
            ?.optDouble("rating", 0.0)?.takeIf { it > 0 }
        val poster = data.optString("poster").trim().takeIf { it.isNotBlank() && it != "null" }
        val posterUrl = when {
            poster == null -> null
            poster.startsWith("http") -> poster
            else -> "https://wsrv.nl/?url=https://simkl.in/posters/" + poster + "_m.webp&q=90"
        }
        if (overview == null && genres.isEmpty() && year == null && rating == null) return null
        return Detail(overview, genres, year, posterUrl, null, rating)
    }

    private fun simklIdFor(title: String, clientId: String): Int? {
        val q = stripSequelSuffix(title).ifBlank { title.trim() }
        if (q.isBlank()) return null
        val raw = Http.getStringQuiet(
            "https://api.simkl.com/search/anime?q=" + URLEncoder.encode(q, "UTF-8") +
                "&client_id=" + URLEncoder.encode(clientId, "UTF-8"),
        ) ?: return null
        val arr = runCatching { org.json.JSONArray(raw) }.getOrNull() ?: return null
        if (arr.length() == 0) return null
        // Simkl returns candidates in its own order — for sequel / donghua
        // titles the first hit is not always the right show, so the best
        // title match wins instead of blindly taking index 0.
        var best = arr.optJSONObject(0)
        var bestScore = -1
        for (i in 0 until minOf(arr.length(), 10)) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("title").trim()
            val score = if (name.isBlank()) 0 else sequelSimilarity(q, name)
            if (score > bestScore) {
                bestScore = score
                best = o
            }
        }
        val pick = best ?: return null
        return pick.optJSONObject("ids")?.optInt("simkl", 0)?.takeIf { it > 0 }
            ?: pick.optInt("id", 0).takeIf { it > 0 }
    }
    private fun stripSequelSuffix(title: String): String =
        title.trim()
            .replace(Regex("""(?i)\s*[:\-–—]?\s*\bseason\s*\d+\s*$"""), "")
            .replace(Regex("""(?i)\s*\b\d+(?:st|nd|rd|th)\s+season\s*$"""), "")
            .replace(Regex("""(?i)\s*\b(?:part|cour)\s*\d+\s*$"""), "")
            .replace(Regex("""(?i)\s*\bs\d{1,2}\s*$"""), "")
            .trim()
    private fun sequelSimilarity(a0: String, b0: String): Int {
        val a = a0.lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()
        val b = b0.lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()
        if (a.isBlank() || b.isBlank()) return 0
        if (a == b) return 100
        if (a.contains(b) || b.contains(a)) return 90
        val aa = a.split(" ").filter { it.length > 2 }.toSet()
        val bb = b.split(" ").filter { it.length > 2 }.toSet()
        if (aa.isEmpty() || bb.isEmpty()) return 0
        return aa.intersect(bb).size * 100 / maxOf(aa.size, bb.size)
    }

    private suspend fun simklCountEpisodes(item: MediaItem): List<Episode>? {
        val clientId = simklClientId()
        if (clientId.isBlank()) return null
        val data = simklAnimeObject(item, clientId) ?: return null
        val total = data.optInt("total_episodes", 0)
        if (total < 1 || total > 3000) return null
        return List(total) { i ->
            Episode(number = i + 1, id = item.id + "#e" + (i + 1), name = "Episode " + (i + 1), season = 1)
        }
    }


        private suspend fun bangumiEpisodes(item: MediaItem): List<Episode>? {
        return null
    }

    private suspend fun anilistDetail(item: MediaItem): Detail? {
        if (!item.providerId.equals("anilist", true)) return null
        val id = item.id.toIntOrNull()?.takeIf { it > 0 } ?: return null
        val query = "query{Media(id:" + id + ",type:ANIME){description genres startDate{year} averageScore coverImage{large} bannerImage}}"
        val raw = Http.postStringQuiet(
            "https://graphql.anilist.co",
            JSONObject().put("query", query).toString(),
        ) ?: return null
        val media = runCatching {
            JSONObject(raw).optJSONObject("data")?.optJSONObject("Media")
        }.getOrNull() ?: return null
        return parseAnilistMedia(media)
    }
    private suspend fun anilistSearchDetail(title: String): Detail? {
        val t = title.trim()
        if (t.isBlank()) return null
        val query = "query{Media(search:" + JSONObject.quote(t) + ",type:ANIME){title{userPreferred english romaji native} synonyms description genres startDate{year} averageScore coverImage{large} bannerImage}}"
        val raw = Http.postStringQuiet(
            "https://graphql.anilist.co",
            JSONObject().put("query", query).toString(),
        ) ?: return null
        val media = runCatching {
            JSONObject(raw).optJSONObject("data")?.optJSONObject("Media")
        }.getOrNull() ?: return null
        val titles = media.optJSONObject("title")
        val aliases = buildSet {
            listOf(titles?.optString("userPreferred"), titles?.optString("english"), titles?.optString("romaji"), titles?.optString("native")).forEach { v -> if (!v.isNullOrBlank()) add(v.trim()) }
            val syns = media.optJSONArray("synonyms")
            for (i in 0 until (syns?.length() ?: 0)) syns?.optString(i)?.trim()?.takeIf { it.isNotBlank() }?.let { add(it) }
        }
        if (AnimeMetadataRepository.matchAliases(t, aliases) < 55) return null
        return parseAnilistMedia(media)
    }
    private fun parseAnilistMedia(media: JSONObject): Detail? {
        val overview = media.optString("description").trim()
            .replace(Regex("<br[^>]*>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&").replace("&quot;", "\"").replace("&#039;", "'").replace("&lt;", "<").replace("&gt;", ">")
            .trim().takeIf { it.isNotBlank() }
        val genres = (0 until (media.optJSONArray("genres")?.length() ?: 0))
            .mapNotNull { i -> media.optJSONArray("genres")?.optString(i)?.trim()?.takeIf { it.isNotBlank() } }
        val year = media.optJSONObject("startDate")?.optInt("year", 0)?.takeIf { it > 0 }
        val rating = media.optDouble("averageScore", 0.0).takeIf { it > 0 }?.div(10.0)
        val poster = media.optJSONObject("coverImage")?.optString("large")?.trim()?.takeIf { it.startsWith("http") }
        val backdrop = media.optString("bannerImage").trim().takeIf { it.startsWith("http") }
        if (overview == null && genres.isEmpty() && year == null && rating == null) return null
        return Detail(overview, genres, year, poster, backdrop, rating)
    }

    private suspend fun episodeCount(item: MediaItem): Int? {
        return when (item.providerId.lowercase()) {
            "anilist" -> {
                val id = item.id.toIntOrNull() ?: return null
                val q = "query($id:Int){Media(id:$id){episodes}}"
                val raw = Http.postStringQuiet(
                    "https://graphql.anilist.co",
                    JSONObject().put("query", q).put("variables", JSONObject().put("id", id)).toString(),
                    mapOf("Accept" to "application/json", "Content-Type" to "application/json"),
                ) ?: return null
                JSONObject(raw).optJSONObject("data")?.optJSONObject("Media")?.optInt("episodes", 0)?.takeIf { it > 0 }
            }
            "mal" -> {
                val id = item.id.toIntOrNull() ?: return null
                val raw = Http.getStringQuiet("https://api.jikan.moe/v4/anime/" + id) ?: return null
                JSONObject(raw).optJSONObject("data")?.optInt("episodes", 0)?.takeIf { it > 0 }
            }
            else -> null
        }
    }
}
