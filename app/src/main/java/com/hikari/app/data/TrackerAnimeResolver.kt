package com.hikari.app.data

import com.hikari.app.HikariApp
import com.hikari.app.net.Http
import com.hikari.app.nuvio.BangumiMeta
import com.hikari.app.nuvio.EpisodeTitles
import com.hikari.app.nuvio.TmdbResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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

    /**
     * Any row that came from a tracker library (AniList/Simkl/MAL/Kitsu/Shikimori/Trakt),
     * whatever its shape: anime series, anime film, live-action film or series. Manga rows
     * are excluded — they open in the reader, never on the video detail page, so tracker
     * video enrichment must never touch them.
     */
    fun isTrackerRow(item: MediaItem): Boolean {
        if (item.rawType.equals("manga", true)) return false
        val p = item.providerId.lowercase()
        return p in trackerProviders || p == "trakt"
    }

    /**
     * A tracker row whose header is worth filling on the video detail page: tracker anime
     * series (see [isTrackerAnime]) plus tracker films, which otherwise show no overview
     * at all because the anime-only detail lookup refuses them.
     */
    fun needsTrackerDetail(item: MediaItem): Boolean =
        isTrackerAnime(item) || (isTrackerRow(item) && item.type == MediaType.MOVIE)

    suspend fun fallbackEpisodes(item: MediaItem): List<Episode>? = withContext(Dispatchers.IO) {
        if (!isTrackerAnime(item)) return@withContext null
        val count = episodeCount(item) ?: return@withContext null
        if (count < 1 || count > 3000) return@withContext null
        List(count) { i -> Episode(number = i + 1, id = item.id + "#e" + (i + 1), name = "Episode " + (i + 1), season = 1) }
    }

    suspend fun trackerEpisodes(item: MediaItem): List<Episode>? = trackerEpisodesStaged(item, null)

    /** Staged tracker list: the numbered base list paints through [onBase]
     *  the moment the count is known (a second or two), while the TMDB /
     *  AniList / Jikan enrichment keeps running in the background for up to
     *  10 seconds total and the enriched list is the return value. When the
     *  databases never answer, the base list still stands — a series with
     *  episodes never reads as "Episodes (0)". */
    suspend fun trackerEpisodesStaged(item: MediaItem, onBase: (suspend (List<Episode>) -> Unit)? = null): List<Episode>? = withContext(Dispatchers.IO) {
        if (!isTrackerAnime(item)) return@withContext null
        val start = System.currentTimeMillis()
        val showD = async { runCatching { anilistShowFor(item) }.getOrNull() }
        val countD = async { runCatching { episodeCount(item) }.getOrNull() }
        val simklD = async { runCatching { simklCountEpisodes(item) }.getOrNull() }
        val show = withTimeoutOrNull(6_000) { showD.await() }
        val count = withTimeoutOrNull(5_000) { countD.await() }
            ?: show?.totalEp
            ?: show?.nextEp?.minus(1)?.takeIf { it > 0 }
        val simklBase = withTimeoutOrNull(6_000) { simklD.await() }?.takeIf { it.size >= 3 }
        showD.cancel(); countD.cancel(); simklD.cancel()
        val base = simklBase
            ?: count?.takeIf { it in 1..3000 }?.let { n ->
                List(n) { i -> Episode(number = i + 1, id = item.id + "#e" + (i + 1), name = "Episode " + (i + 1), season = 1) }
            }
        if (base.isNullOrEmpty()) return@withContext null
        runCatching { onBase?.invoke(base) }
        val remaining = 10_000 - (System.currentTimeMillis() - start)
        if (remaining <= 0) return@withContext base
        val enriched = withTimeoutOrNull(remaining) { enrichEpisodes(item, base) }
        if (enriched != null && enriched.any { !EpisodeTitles.isGeneric(it.name) || !it.overview.isNullOrBlank() }) enriched else base
    }

    suspend fun detail(item: MediaItem): Detail? = withContext(Dispatchers.IO) {
        // Tracker films carry no anime record: their overview/genres/year/rating come
        // from the TMDB film entry the exact Simkl mapping (or an exact title resolve)
        // names, so a saved film reads like any other source's film.
        if (item.type == MediaType.MOVIE && isTrackerRow(item)) {
            return@withContext runCatching { tmdbMovieDetail(item) }.getOrNull()
                ?: runCatching { anilistSearchDetail(item.searchTitle) }.getOrNull()
        }
        if (!isTrackerAnime(item)) return@withContext null
        if (item.providerId.equals("anilist", true)) {
            runCatching { anilistDetail(item) }.getOrNull()?.let { return@withContext it }
            return@withContext runCatching { anilistSearchDetail(item.searchTitle) }.getOrNull()
        }
        runCatching { simklDetail(item) }.getOrNull()?.let { return@withContext it }
        runCatching { anilistSearchDetail(item.searchTitle) }.getOrNull()
    }

    private val showCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, AnilistShow?>>()
    private val tmdbIdCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Int?>>()
    private val jikanCache = java.util.concurrent.ConcurrentHashMap<Int, Pair<Long, Map<Int, JikanEp>>>()
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    private class StreamEp(val name: String?, val thumbnail: String?)
    private class JikanEp(val name: String?, val aired: String?, val score: Double?)
    private class Prequel(val id: Int, val count: Int)
    private class AnilistShow(
        val id: Int,
        val malId: Int?,
        val aliases: Set<String>,
        val english: String?,
        val streams: Map<Int, StreamEp>,
        val prequels: List<Prequel>,
        val totalEp: Int?,
        val nextEp: Int?,
    )
    private class TmdbMaps(val names: EpisodeTitles.Names?, val details: Map<Int, EpisodeTitles.EpDetail>, val offset: Int)

    private fun fresh(at: Long): Boolean = System.currentTimeMillis() - at < DAY_MS

    private suspend fun enrichEpisodes(item: MediaItem, eps: List<Episode>): List<Episode> {
        if (eps.isEmpty()) return eps
        if (eps.none { EpisodeTitles.isGeneric(it.name) || it.overview.isNullOrBlank() || it.image.isNullOrBlank() }) return eps
        return try {
            supervisorScope {
                val want = eps.map { it.number }.toSet()
                val showD = async(Dispatchers.IO) {
                    withTimeoutOrNull(8_000) { runCatching { anilistShowFor(item) }.getOrNull() }
                }
                val tmdbD = async(Dispatchers.IO) {
                    withTimeoutOrNull(15_000) {
                        val show = showD.await()
                        runCatching { tmdbMaps(item, want, show) }.getOrNull()
                    }
                }
                val jikanD = async(Dispatchers.IO) {
                    withTimeoutOrNull(15_000) {
                        runCatching { jikanMap(item, showD.await()) }.getOrNull().orEmpty()
                    }
                }
                val show = showD.await()
                var out = eps
                val tm = tmdbD.await()
                if (tm != null && (tm.names != null || tm.details.isNotEmpty())) {
                    out = out.map { e ->
                        val a = e.number + tm.offset
                        val nm = tm.names?.english?.get(a) ?: tm.names?.generic?.get(a)
                        val d = tm.details[a]
                        filled(e, nm, d?.overview, d?.released, d?.rating, d?.runtime, d?.image)
                    }
                }
                if (show != null && show.streams.isNotEmpty()) {
                    out = out.map { e ->
                        val s = show.streams[e.number] ?: return@map e
                        val nn = if (!s.name.isNullOrBlank() && EpisodeTitles.isGeneric(e.name)) s.name else null
                        val im = if (e.image.isNullOrBlank()) s.thumbnail else e.image
                        if (nn == null && im == e.image) e else e.copy(name = nn ?: e.name, image = im)
                    }
                }
                val jmap = jikanD.await().orEmpty()
                if (jmap.isNotEmpty()) {
                    out = out.map { e ->
                        val j = jmap[e.number] ?: return@map e
                        filled(e, j.name, null, j.aired, j.score, null, null)
                    }
                }
                out
            }
        } catch (t: Throwable) {
            eps
        }
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

    private suspend fun tmdbMaps(item: MediaItem, want: Set<Int>, show: AnilistShow?): TmdbMaps? {
        val tmdbId = trackerTmdbId(item, show) ?: return null
        // A film id (anime films, tracker movies-as-series) has no /tv entry: its one
        // row is enriched from the /movie entry instead of per-episode /tv seasons.
        val tv = TmdbResolver.apiGet("/tv/" + tmdbId, emptyMap()) ?: return tmdbMovieMaps(item, tmdbId)
        val seasonsArr = tv.optJSONArray("seasons")
        val tmdbSeasons = (0 until (seasonsArr?.length() ?: 0)).mapNotNull { i ->
            val s = seasonsArr?.optJSONObject(i)
            val n = s?.optInt("season_number", -1) ?: -1
            val c = s?.optInt("episode_count", 0) ?: 0
            if (n > 0 && c > 0) n to c else null
        }.toMap()
        if (tmdbSeasons.isEmpty()) return null
        val hint = TmdbMeta.seasonHint(item.searchTitle)
        if (hint != null) {
            val c = tmdbSeasons[hint]
            if (c != null && kotlin.math.abs(c - want.size) <= maxOf(3, (want.size * 0.25).toInt())) {
                val names = runCatching { EpisodeTitles.lookupForId(tmdbId, want, hint) }.getOrNull()
                val details = runCatching { EpisodeTitles.detailsForId(tmdbId, want, hint) }.getOrNull().orEmpty()
                if ((names == null || names.isEmpty()) && details.isEmpty()) return null
                return TmdbMaps(names, details, 0)
            }
        }
        // Without an AniList record there is no prequel chain to offset by: the list is
        // read season-local (offset 0), which is exactly right for the common single-season
        // case. (Requiring the chain used to drop ALL TMDB enrichment whenever the show
        // lookup missed, leaving bare "Episode N" rows.)
        val offset = if (show == null) 0 else prequelOffset(show) ?: return null
        val abs = want.map { it + offset }.toSet()
        val names = runCatching { EpisodeTitles.lookupForId(tmdbId, abs, null) }.getOrNull()
        val details = runCatching { EpisodeTitles.detailsForId(tmdbId, abs, null) }.getOrNull().orEmpty()
        if ((names == null || names.isEmpty()) && details.isEmpty()) return null
        return TmdbMaps(names, details, offset)
    }

    /**
     * A tracker film's header from its TMDB film entry. Only a "movie"-namespace id is
     * ever read — a tv id here would wear another entry's details.
     */
    private suspend fun tmdbMovieDetail(item: MediaItem): Detail? {
        val id = runCatching { trackerTmdbId(item, null) }.getOrNull()
            ?.takeIf { it.mediaType.equals("movie", true) }?.tmdbId?.toIntOrNull()?.takeIf { it > 0 }
            ?: runCatching { TmdbResolver.resolve(item) }.getOrNull()
                ?.takeIf { it.mediaType.equals("movie", true) }?.tmdbId?.toIntOrNull()?.takeIf { it > 0 }
            ?: return null
        val obj = TmdbResolver.apiGet("/movie/$id", emptyMap()) ?: return null
        return parseTmdbDetail(obj)
    }

    private fun parseTmdbDetail(obj: JSONObject): Detail? {
        val overview = obj.optString("overview").trim().takeIf { it.isNotBlank() && it != "null" }
        val genres = (0 until (obj.optJSONArray("genres")?.length() ?: 0)).mapNotNull { i ->
            obj.optJSONArray("genres")?.optJSONObject(i)?.optString("name")?.trim()?.takeIf { it.isNotBlank() }
        }
        val year = obj.optString("release_date").ifBlank { obj.optString("first_air_date") }.take(4).toIntOrNull()
        val rating = obj.optDouble("vote_average", 0.0).takeIf { it > 0.0 }
        fun img(raw: String?): String? {
            val p = raw?.trim().orEmpty()
            if (p.isBlank() || p == "null") return null
            if (p.startsWith("http")) return p
            return "https://image.tmdb.org/t/p/w500" + (if (p.startsWith("/")) p else "/$p")
        }
        val poster = img(obj.optString("poster_path"))
        val backdrop = img(obj.optString("backdrop_path"))
        if (overview == null && genres.isEmpty() && year == null && rating == null) return null
        return Detail(overview, genres, year, poster, backdrop, rating)
    }

    /**
     * A film id's one-row enrichment (see [tmdbMaps]): the film's title names the single
     * "Episode 1" row and its overview/date/rating/runtime/poster fill the row's blanks.
     * The title is accepted only on a name match (the same 55+ gate as every AniList
     * lookup), so a wrong-namespace id can never repaint the row.
     */
    private suspend fun tmdbMovieMaps(item: MediaItem, tmdbId: Int): TmdbMaps? {
        val movie = TmdbResolver.apiGet("/movie/$tmdbId", emptyMap()) ?: return null
        if (!movie.has("id")) return null
        val title = movie.optString("title").ifBlank { movie.optString("original_title") }.trim()
            .takeIf { it.isNotBlank() && it != "null" } ?: return null
        val match = maxOf(
            AnimeMetadataRepository.matchAliases(item.searchTitle, setOf(title)),
            AnimeMetadataRepository.matchAliases(item.title, setOf(title)),
            TmdbMeta.titleScore(item.searchTitle, title),
        )
        if (match < 55) return null
        val poster = movie.optString("poster_path").trim().takeIf { it.isNotBlank() && it != "null" }
            ?.let { "https://image.tmdb.org/t/p/w500" + (if (it.startsWith("/")) it else "/$it") }
        val detail = EpisodeTitles.EpDetail(
            season = 1,
            number = 1,
            overview = movie.optString("overview").trim().takeIf { it.isNotBlank() && it != "null" },
            released = movie.optString("release_date").trim().take(10).takeIf { it.isNotBlank() },
            rating = movie.optDouble("vote_average", 0.0).takeIf { it > 0.0 },
            runtime = movie.optInt("runtime", 0).takeIf { it > 0 },
            image = poster,
        )
        return TmdbMaps(EpisodeTitles.Names(mapOf(1 to title), emptyMap()), mapOf(1 to detail), 0)
    }

    private suspend fun trackerTmdbId(item: MediaItem, show: AnilistShow?): Int? {
        val key = item.providerId.lowercase() + "|" + item.id
        tmdbIdCache[key]?.let { if (fresh(it.first)) return it.second }
        val id = runCatching { simklTmdbRef(item) }.getOrNull()?.tmdbId?.toIntOrNull()?.takeIf { it > 0 }
            ?: bridgeTmdbId(item, show)
            ?: runCatching { TmdbResolver.resolve(item) }.getOrNull()
                ?.takeIf { it.mediaType.equals("tv", true) }?.tmdbId?.toIntOrNull()?.takeIf { it > 0 }
        tmdbIdCache[key] = System.currentTimeMillis() to id
        return id
    }

    private suspend fun bridgeTmdbId(item: MediaItem, show: AnilistShow?): Int? {
        val aliases = show?.aliases?.takeIf { it.isNotEmpty() }
            ?: setOf(item.searchTitle).takeIf { item.searchTitle.isNotBlank() } ?: return null
        val ordered = buildList {
            show?.english?.takeIf { it.isNotBlank() }?.let { add(it) }
            for (a in aliases) if (a !in this) add(a)
        }.take(6)
        if (ordered.isEmpty()) return null
        val queries = ordered.flatMap { TmdbMeta.queryVariants(it).take(2) }.distinct().take(6)
        if (queries.isEmpty()) return null
        val sequelRow = TmdbMeta.seasonHint(item.searchTitle) != null
        var best: Int? = null
        var bestScore = 0
        for (kind in listOf("tv", "movie")) {
            for (q in queries) {
                val data = TmdbResolver.apiGet("/search/" + kind, mapOf("query" to q)) ?: continue
                val arr = data.optJSONArray("results") ?: continue
                for (i in 0 until minOf(arr.length(), 10)) {
                    val o = arr.optJSONObject(i) ?: continue
                    val oid = o.optString("id").trim().toIntOrNull()?.takeIf { it > 0 } ?: continue
                    var base = 0
                    for (n in listOf(o.optString("title"), o.optString("name"), o.optString("original_title"), o.optString("original_name"))) {
                        if (n.isBlank() || n == "null") continue
                        for (a in aliases) base = maxOf(base, TmdbMeta.titleScore(a, n))
                    }
                    if (base < 60) continue
                    val raw = o.optString("release_date").ifBlank { o.optString("first_air_date") };
                    val y = raw.take(4).toIntOrNull()
                    if (!sequelRow && item.year != null && item.year > 0) {
                        if (y == null || kotlin.math.abs(y - item.year) > 1) continue
                    }
                    val lang = o.optString("original_language").lowercase()
                    var ja = lang == "ja"
                    if (!ja) {
                        val genres = o.optJSONArray("genre_ids")
                        if (genres != null) for (g in 0 until genres.length()) {
                            if (genres.optInt(g) == 16) { ja = true; break }
                        }
                    }
                    if (!ja) continue
                    val score = base * 100 + o.optDouble("popularity", 0.0).toInt().coerceAtMost(99)
                    if (score > bestScore) { bestScore = score; best = oid }
                }
                if (bestScore >= 6000) return best
            }
        }
        return best
    }

    private suspend fun anilistShowFor(item: MediaItem): AnilistShow? {
        val direct = if (item.providerId.equals("anilist", true)) item.id.toIntOrNull()?.takeIf { it > 0 } else null
        if (direct != null) return anilistShowById(direct)
        val t = item.searchTitle.trim()
        if (t.isBlank()) return null
        val key = "t|" + t.lowercase()
        showCache[key]?.let { if (fresh(it.first)) return it.second }
        val raw = Http.postStringQuiet(
            "https://graphql.anilist.co",
            JSONObject().put("query", "query{Media(search:" + JSONObject.quote(t) + ",type:ANIME){id idMal title{userPreferred english romaji native} synonyms streamingEpisodes{title thumbnail} episodes nextAiringEpisode{episode} relations{edges{relationType node{id episodes}}}}}").toString(),
        )
        val media = runCatching { JSONObject(raw ?: "").optJSONObject("data")?.optJSONObject("Media") }.getOrNull()
        val show = media?.let { parseAnilistShow(it) }
        val verified = if (show != null && AnimeMetadataRepository.matchAliases(t, show.aliases) >= 55) show else null
        showCache[key] = System.currentTimeMillis() to verified
        if (verified != null) showCache["id|" + verified.id] = System.currentTimeMillis() to verified
        return verified
    }

    private suspend fun anilistShowById(id: Int): AnilistShow? {
        val key = "id|" + id
        showCache[key]?.let { if (fresh(it.first)) return it.second }
        val raw = Http.postStringQuiet(
            "https://graphql.anilist.co",
            JSONObject().put("query", "query{Media(id:" + id + ",type:ANIME){id idMal title{userPreferred english romaji native} synonyms streamingEpisodes{title thumbnail} episodes nextAiringEpisode{episode} relations{edges{relationType node{id episodes}}}}}").toString(),
        ) ?: return null
        val media = runCatching { JSONObject(raw).optJSONObject("data")?.optJSONObject("Media") }.getOrNull()
        val show = media?.let { parseAnilistShow(it) }
        if (show != null) showCache[key] = System.currentTimeMillis() to show
        return show
    }

    private fun parseAnilistShow(media: JSONObject): AnilistShow? {
        val id = media.optInt("id", 0).takeIf { it > 0 } ?: return null
        val titles = media.optJSONObject("title")
        val english = titles?.optString("english")?.trim()?.takeIf { it.isNotBlank() }
        val aliases = buildSet {
            listOf(titles?.optString("userPreferred"), english, titles?.optString("romaji"), titles?.optString("native")).forEach { v -> if (!v.isNullOrBlank()) add(v.trim()) }
            val syns = media.optJSONArray("synonyms")
            for (i in 0 until (syns?.length() ?: 0)) syns?.optString(i)?.trim()?.takeIf { it.isNotBlank() }?.let { add(it) }
        }
        val streams = HashMap<Int, StreamEp>()
        val arr = media.optJSONArray("streamingEpisodes")
        for (i in 0 until (arr?.length() ?: 0)) {
            val o = arr?.optJSONObject(i) ?: continue
            val m = Regex("""(?i)^(?:episode|ep)\s*0*(\d+)\s*[-–—:.]?\s*(.*)$""").find(o.optString("title").trim()) ?: continue
            val n = m.groupValues[1].toIntOrNull()?.takeIf { it in 1..4000 } ?: continue
            val name = m.groupValues[2].trim().takeIf { it.isNotBlank() }
            val thumb = o.optString("thumbnail").trim().takeIf { it.startsWith("http") }
            if (name == null && thumb == null) continue
            if (!streams.containsKey(n)) streams[n] = StreamEp(name, thumb)
        }
        val prequels = ArrayList<Prequel>()
        val edges = media.optJSONObject("relations")?.optJSONArray("edges")
        for (i in 0 until (edges?.length() ?: 0)) {
            val e = edges?.optJSONObject(i) ?: continue
            if (!e.optString("relationType").equals("prequel", true)) continue
            val node = e.optJSONObject("node") ?: continue
            val pid = node.optInt("id", 0)
            if (pid <= 0 || pid == id) continue
            prequels.add(Prequel(pid, node.optInt("episodes", 0)))
        }
        return AnilistShow(id, media.optInt("idMal", 0).takeIf { it > 0 }, aliases, english, streams, prequels,
            media.optInt("episodes", 0).takeIf { it > 0 },
            media.optJSONObject("nextAiringEpisode")?.optInt("episode", 0)?.takeIf { it > 1 })
    }

    private suspend fun prequelOffset(show: AnilistShow?): Int? {
        if (show == null) return null
        var sum = 0
        var cur: AnilistShow = show
        val seen = HashSet<Int>()
        var guard = 0
        while (guard++ < 8) {
            if (!seen.add(cur.id)) break
            val pre = cur.prequels.maxByOrNull { it.count } ?: break
            if (pre.count <= 0) return null
            sum += pre.count
            cur = anilistShowById(pre.id) ?: return null
        }
        return sum
    }

    private suspend fun jikanMap(item: MediaItem, show: AnilistShow?): Map<Int, JikanEp> {
        val malId = (if (item.providerId.equals("mal", true)) item.id.toIntOrNull()?.takeIf { it > 0 } else null)
            ?: show?.malId ?: return emptyMap()
        return jikanEpisodes(malId)
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
                val raw = Http.postStringQuiet(
                    "https://graphql.anilist.co",
                    JSONObject().put("query", "query(\$id:Int){Media(id:\$id,type:ANIME){episodes status nextAiringEpisode{episode}}}").put("variables", JSONObject().put("id", id)).toString(),
                ) ?: return null
                val media = runCatching {
                    JSONObject(raw).optJSONObject("data")?.optJSONObject("Media")
                }.getOrNull() ?: return null
                media.optInt("episodes", 0).takeIf { it > 0 }
                    ?: media.optJSONObject("nextAiringEpisode")?.optInt("episode", 0)?.minus(1)?.takeIf { it > 0 }
            }
            "mal" -> {
                val id = item.id.toIntOrNull() ?: return null
                val raw = Http.getStringQuiet("https://api.jikan.moe/v4/anime/" + id) ?: return null
                JSONObject(raw).optJSONObject("data")?.optInt("episodes", 0)?.takeIf { it > 0 }
            }
            "kitsu" -> {
                val id = item.id.toIntOrNull() ?: return null
                val raw = Http.getStringQuiet("https://kitsu.io/api/edge/anime/" + id + "?fields%5Banime%5D=episodeCount") ?: return null
                runCatching { JSONObject(raw).optJSONObject("data")?.optJSONObject("attributes") }
                    .getOrNull()?.optInt("episodeCount", 0)?.takeIf { it > 0 }
            }
            "shikimori" -> {
                val id = item.id.toIntOrNull() ?: return null
                val raw = Http.getStringQuiet("https://shikimori.one/api/animes/" + id) ?: return null
                runCatching { JSONObject(raw) }.getOrNull()?.let { o ->
                    o.optInt("episodes", 0).takeIf { it > 0 }
                        ?: o.optInt("episodes_aired", 0).takeIf { it > 0 }
                }
            }
            else -> null
        }
    }
}
