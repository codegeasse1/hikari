package com.hikari.app.data

import com.hikari.app.HikariApp
import com.hikari.app.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

object TrackerLibraryRepository {
    data class Shelf(val key: String, val title: String, val items: List<MediaItem>)

    suspend fun load(app: HikariApp): Result<List<Shelf>> = withContext(Dispatchers.IO) {
        when (runCatching { app.store.trackerLibrarySource() }.getOrDefault("nuvio").lowercase()) {
            "trakt" -> loadTrakt(app)
            "simkl" -> loadSimkl(app)
            "mdblist" -> Result.failure(IllegalStateException("MDBList library is available after connecting MDBList."))
            else -> Result.success(emptyList())
        }
    }

    private suspend fun loadTrakt(app: HikariApp): Result<List<Shelf>> {
        val account = app.store.trackers().firstOrNull { it.kind == TrackerKind.TRAKT }
            ?: return Result.failure(IllegalStateException("Connect Trakt in Settings → Trackers first."))
        val client = app.store.trackerClients().firstOrNull { it.kind == TrackerKind.TRAKT }
            ?: TrackerClient(TrackerKind.TRAKT)
        if (!client.ready) return Result.failure(IllegalStateException("Trakt app credentials are not configured."))
        val headers = mapOf("Authorization" to "Bearer \${account.token}", "trakt-api-version" to "2", "trakt-api-key" to client.id)
        val user = account.user.ifBlank { return Result.failure(IllegalStateException("Trakt username is unavailable; reconnect Trakt.")) }
        val out = ArrayList<Shelf>()

        suspend fun get(path: String): JSONArray? = runCatching {
            val response = Http.get("https://api.trakt.tv$path", headers)
            if (!response.isSuccessful) return@runCatching null
            JSONArray(response.body?.string().orEmpty())
        }.getOrNull()

        val movies = get("/users/\${enc(user)}/watchlist/movies?extended=full&page=1&limit=250").orEmpty()
            .mapNotNull { traktItem(it as? JSONObject, MediaType.MOVIE) }
        val shows = get("/users/\${enc(user)}/watchlist/shows?extended=full&page=1&limit=250").orEmpty()
            .mapNotNull { traktItem(it as? JSONObject, MediaType.SERIES) }
        if (movies.isNotEmpty()) out += Shelf("trakt.watchlist.movies", "Trakt Watchlist · Movies", movies)
        if (shows.isNotEmpty()) out += Shelf("trakt.watchlist.shows", "Trakt Watchlist · Shows", shows)

        val lists = runCatching {
            val response = Http.get("https://api.trakt.tv/users/\${enc(user)}/lists", headers)
            if (!response.isSuccessful) JSONArray() else JSONArray(response.body?.string().orEmpty())
        }.getOrDefault(JSONArray())

        for (i in 0 until lists.length()) {
            val list = lists.optJSONObject(i) ?: continue
            val ids = list.optJSONObject("ids")
            val slug = ids?.optString("slug").orEmpty().ifBlank {
                list.optString("name").lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
            }
            if (slug.isBlank()) continue
            val response = runCatching {
                Http.get("https://api.trakt.tv/users/\${enc(user)}/lists/\${enc(slug)}/items?extended=full&page=1&limit=1000", headers)
            }.getOrNull() ?: continue
            if (!response.isSuccessful) continue
            val array = runCatching { JSONArray(response.body?.string().orEmpty()) }.getOrNull() ?: continue
            val items = buildList {
                for (j in 0 until array.length()) {
                    val row = array.optJSONObject(j) ?: continue
                    val type = row.optString("type")
                    val media = row.optJSONObject(type) ?: continue
                    val mt = if (type == "movie") MediaType.MOVIE else MediaType.SERIES
                    traktMedia(media, mt)?.let(::add)
                }
            }.distinctBy { it.uniqueId }
            if (items.isNotEmpty()) out += Shelf("trakt.list.$slug", list.optString("name", slug), items)
        }
        Result.success(out)
    }

    private suspend fun loadSimkl(app: HikariApp): Result<List<Shelf>> {
        val account = app.store.trackers().firstOrNull { it.kind == TrackerKind.SIMKL }
            ?: return Result.failure(IllegalStateException("Connect Simkl in Settings → Trackers first."))
        val client = app.store.trackerClients().firstOrNull { it.kind == TrackerKind.SIMKL }
            ?: TrackerClient(TrackerKind.SIMKL)
        if (!client.ready) return Result.failure(IllegalStateException("Simkl app credentials are not configured."))
        val response = runCatching {
            Http.get("https://api.simkl.com/sync/all-items/?extended=full", mapOf(
                "Authorization" to "Bearer \${account.token}",
                "simkl-api-key" to client.id,
            ))
        }.getOrElse { return Result.failure(it) }
        if (!response.isSuccessful) return Result.failure(IllegalStateException("Simkl returned HTTP \${response.code}."))
        val root = JSONObject(response.body?.string().orEmpty())
        val out = ArrayList<Shelf>()

        fun parse(key: String, title: String, type: MediaType) {
            val array = root.optJSONArray(key) ?: return
            val items = buildList {
                for (i in 0 until array.length()) {
                    val row = array.optJSONObject(i) ?: continue
                    val item = row.optJSONObject("movie") ?: row.optJSONObject("show") ?: row.optJSONObject("anime") ?: row
                    simklMedia(item, type)?.let(::add)
                }
            }.distinctBy { it.uniqueId }
            if (items.isNotEmpty()) out += Shelf("simkl.$key", title, items)
        }
        parse("movies", "Simkl · Movies", MediaType.MOVIE)
        parse("tv_shows", "Simkl · TV Shows", MediaType.SERIES)
        parse("anime", "Simkl · Anime", MediaType.SERIES)
        Result.success(out)
    }

    private fun traktItem(row: JSONObject?, type: MediaType): MediaItem? =
        row?.let { traktMedia(it.optJSONObject("movie") ?: it.optJSONObject("show") ?: return@let null, type) }

    private fun traktMedia(o: JSONObject, type: MediaType): MediaItem? {
        val ids = o.optJSONObject("ids") ?: return null
        val tmdb = ids.optInt("tmdb", 0)
        val imdb = ids.optString("imdb").takeIf { it.startsWith("tt") }
        val id = if (tmdb > 0) tmdb.toString() else imdb ?: return null
        val provider = if (tmdb > 0) "tmdb" else "stremio"
        val image = o.optJSONObject("images")?.optJSONObject("poster")?.optString("full")
        return MediaItem(providerId = provider, id = id, title = o.optString("title").ifBlank { "Untitled" },
            type = type, posterUrl = image, year = o.optInt("year", 0).takeIf { it > 0 },
            rawType = if (provider == "tmdb") "tmdb" else if (type == MediaType.MOVIE) "movie" else "series")
    }

    private fun simklMedia(o: JSONObject, type: MediaType): MediaItem? {
        val ids = o.optJSONObject("ids") ?: return null
        val tmdb = ids.optInt("tmdb", 0)
        val imdb = ids.optString("imdb").takeIf { it.startsWith("tt") }
        val id = if (tmdb > 0) tmdb.toString() else imdb ?: return null
        val provider = if (tmdb > 0) "tmdb" else "stremio"
        return MediaItem(providerId = provider, id = id,
            title = o.optString("title").ifBlank { o.optString("name") }.ifBlank { "Untitled" },
            type = type, year = o.optInt("year", 0).takeIf { it > 0 },
            posterUrl = o.optString("poster").takeIf { it.startsWith("http") },
            rawType = if (provider == "tmdb") "tmdb" else if (type == MediaType.MOVIE) "movie" else "series")
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}

private fun JSONArray.orEmpty(): List<Any?> = List(length()) { opt(it) }
